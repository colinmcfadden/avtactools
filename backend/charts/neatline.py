"""Find a chart's neatline and propose the polygon it is clipped to (docs/VFR_CHARTS.md §4.2).

The FAA publishes none, so EZ/PZ keeps its own: one GeoJSON polygon per chart, in degrees on NAD83, in ``charts/outlines/``. This
module proposes them; a person checks each one over the image ``check_image`` draws before it is committed.

How a chart is framed decides how its edges are found:
- **A sectional** ends along meridians and parallels (§4.2): a side is the quarter-degree meridian or parallel that has a dark line
  along it, with the white margin outside and the chart's tints inside (``graticule_side``). Some sides have no neatline at all: the
  chart runs on past its nominal edge to the edge of the file, overlapping its neighbour. Such a side is *open*, and takes the edge
  of the neighbour it overlaps (``resolve_open_sides``), so the two meet along one line.
- **A TAC, a helicopter sheet or an inset** is framed by a few straight lines in the file's pixels, a rectangle up to a slight turn
  (``frame_side``).

What neither finds is written in ``charts/outlines/edits.json`` by the person checking, and applied here:
- a sectional's side given by hand: a meridian or parallel, or ``"file"`` for a side that runs on to the edge of the file (a coast or a
  border with no sectional beyond it, the chart's body reaching the edge of the sheet);
- a framed chart's line given by hand (``lines``), or its corners (``outer_pixels``);
- *cuts*: an inset or a panel printed over the chart's body, as a ring in the file's pixels (``find_cut`` proposes one from a rough box).
  A cut inside the body is a hole; one across the body's edge notches it.
Every outline is kept inside the file, a few pixels in from its edge.

A chart's file is read whole (one 8-bit band, about 200 MB for a sectional). Nothing here writes anywhere but where it is told.
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass, field

import numpy as np
import rasterio
from pyproj import Transformer
from rasterio.transform import Affine

from . import catalog

NAD83 = "EPSG:4269"
DARK = 110          # luminance below which a pixel is ink
WHITE = 225         # every channel above which a pixel is paper, for finding a neatline's margin (the pale grey of Canada counts)
PAPER = 250         # every channel at or above which a pixel is bare paper: the white border a sheet keeps at its edge
STEP = 0.25         # sectionals end on quarter degrees
OUTLINES = os.path.join(os.path.dirname(__file__), "outlines")


# ---------------------------------------------------------------------------------------------------------------- reading


@dataclass
class Source:
    """One chart's file as read: its palette indices and how they read."""

    width: int
    height: int
    transform: Affine
    crs: object
    index: np.ndarray            # (height, width) uint8
    dark: np.ndarray             # 256 bools: the palette entry is ink
    white: np.ndarray            # 256 bools: the palette entry is paper
    palette: np.ndarray          # (256, 3) uint8
    to_crs: Transformer = field(repr=False, default=None)
    to_deg: Transformer = field(repr=False, default=None)

    def pixels(self, lon, lat):
        """Degrees on NAD83 to fractional (col, row) of pixel centres."""
        x, y = self.to_crs.transform(np.asarray(lon, float), np.asarray(lat, float))
        col, row = ~self.transform * (x, y)
        return np.asarray(col) - 0.5, np.asarray(row) - 0.5

    def degrees(self, col, row):
        """Fractional (col, row) of pixel centres to degrees on NAD83."""
        x, y = self.transform * (np.asarray(col, float) + 0.5, np.asarray(row, float) + 0.5)
        lon, lat = self.to_deg.transform(x, y)
        return np.asarray(lon), np.asarray(lat)

    def is_dark(self, rows, cols):
        return self.dark[self.index[rows, cols]]

    def is_white(self, rows, cols):
        return self.white[self.index[rows, cols]]


def read(path: str) -> Source:
    with rasterio.open(path) as ds:
        if ds.count != 1 or ds.crs is None:
            raise ValueError(f"{path}: not a georeferenced one-band chart")
        cmap = ds.colormap(1)
        palette = np.zeros((256, 3), np.uint8)
        for i, rgba in cmap.items():
            palette[i] = rgba[:3]
        index = ds.read(1)
        src = Source(ds.width, ds.height, ds.transform, ds.crs, index, None, None, palette)
    lum = palette @ np.array([0.299, 0.587, 0.114])
    src.dark = lum < DARK
    src.white = (palette > WHITE).all(axis=1)
    src.to_crs = Transformer.from_crs(NAD83, src.crs, always_xy=True)
    src.to_deg = Transformer.from_crs(src.crs, NAD83, always_xy=True)
    return src


# -------------------------------------------------------------------------------------------------------- sectionals' sides

SIDES = ("north", "east", "south", "west")


@dataclass
class Side:
    side: str
    value: float | None          # degrees: a latitude for north/south, a longitude for east/west; None while open
    how: str                     # "neatline" | "open" | "neighbour:<id>" | "content" | "file" | "edit"
    support: float = 0.0         # share of the line with ink on it
    outside: float = 0.0         # share of paper just outside
    inside: float = 0.0          # share of paper just inside


def _extent_degrees(src: Source):
    cols = np.r_[np.linspace(0, src.width - 1, 50), np.full(50, src.width - 1), np.linspace(0, src.width - 1, 50), np.zeros(50)]
    rows = np.r_[np.zeros(50), np.linspace(0, src.height - 1, 50), np.full(50, src.height - 1), np.linspace(0, src.height - 1, 50)]
    lon, lat = src.degrees(cols, rows)
    return lon.min(), lat.min(), lon.max(), lat.max()


def _line_score(src: Source, side: str, value: float, lo: float, hi: float, n: int = 400, slack: int = 3):
    """How a meridian or parallel looks in the file: (support, white outside, white inside), or None off the file."""
    along = np.linspace(lo, hi, n)
    if side in ("north", "south"):
        col, row = src.pixels(along, np.full(n, value))
    else:
        col, row = src.pixels(np.full(n, value), along)
    col, row = np.rint(col).astype(int), np.rint(row).astype(int)
    pad = slack + 18
    ok = (col >= pad) & (col < src.width - pad) & (row >= pad) & (row < src.height - pad)
    if ok.sum() < n // 4:
        return None
    col, row = col[ok], row[ok]
    if side in ("north", "south"):
        def at(off):
            return row + off, col
    else:
        def at(off):
            return row, col + off
    hit = np.zeros(len(col), bool)
    for off in range(-slack, slack + 1):
        hit |= src.is_dark(*at(off))
    # outward is north (rows up), east (cols right), south (rows down), west (cols left)
    sign = {"north": -1, "south": 1, "east": 1, "west": -1}[side]
    out = np.mean([src.is_white(*at(sign * k)).mean() for k in range(slack + 2, slack + 16)])
    inn = np.mean([src.is_white(*at(-sign * k)).mean() for k in range(slack + 2, slack + 16)])
    return float(hit.mean()), float(out), float(inn)


def graticule_side(src: Source, side: str, extent=None) -> tuple[Side, list]:
    """The quarter-degree meridian or parallel that is this side's neatline, or an open side; with every candidate's scores."""
    w, s, e, n = extent or _extent_degrees(src)
    if side in ("north", "south"):
        mid, lo, hi = (s + n) / 2, w, e
        values = np.arange(math.ceil(mid / STEP) * STEP, n, STEP) if side == "north" else np.arange(math.floor(mid / STEP) * STEP, s, -STEP)
    else:
        mid, lo, hi = (w + e) / 2, s, n
        values = np.arange(math.ceil(mid / STEP) * STEP, e, STEP) if side == "east" else np.arange(math.floor(mid / STEP) * STEP, w, -STEP)
    scored = []
    for v in values:
        sc = _line_score(src, side, float(v), lo, hi)
        if sc:
            scored.append((round(float(v), 4), *sc))
    best = None
    for v, sup, out, inn in scored:
        if sup >= 0.6 and out >= 0.6 and out - inn >= 0.25:
            if best is None or sup * (out - inn) > best[1] * (best[2] - best[3]):
                best = (v, sup, out, inn)
    if best is None:
        return Side(side, None, "open"), scored
    return Side(side, best[0], "neatline", best[1], best[2], best[3]), scored


# ------------------------------------------------------------------------------------------------- framed charts' sides


@dataclass
class Line:
    """A straight line in the file's pixels: offset = a + b * (position along the side - c0), offset measured from the side's edge."""

    side: str
    a: float
    b: float
    c0: float
    support: float = 0.0
    outside: float = 0.0
    inside: float = 0.0


def _profile_points(src: Source, side: str, depth: float, k: int = 120):
    """Thin ink runs met going in from one side of the file, along ``k`` lines across it: [(position, offset)]."""
    length = src.width if side in ("top", "bottom") else src.height
    reach = int((src.height if side in ("top", "bottom") else src.width) * depth)
    pts = []
    for pos in np.linspace(length * 0.04, length * 0.96, k).astype(int):
        off = np.arange(reach)
        if side == "top":
            ink = src.is_dark(off, np.full(reach, pos))
        elif side == "bottom":
            ink = src.is_dark(src.height - 1 - off, np.full(reach, pos))
        elif side == "left":
            ink = src.is_dark(np.full(reach, pos), off)
        else:
            ink = src.is_dark(np.full(reach, pos), src.width - 1 - off)
        d = np.diff(np.r_[0, ink.astype(np.int8), 0])
        starts, ends = np.flatnonzero(d == 1), np.flatnonzero(d == -1)
        for a, b in zip(starts, ends):
            if b - a <= 6:
                pts.append((pos, (a + b - 1) / 2))
    return np.array(pts, float).reshape(-1, 2), length


def _line_pixels(src: Source, line: Line, n: int = 600):
    length = src.width if line.side in ("top", "bottom") else src.height
    pos = np.linspace(length * 0.02, length * 0.98, n)
    off = line.a + line.b * (pos - line.c0)
    if line.side == "top":
        return off, pos            # rows, cols
    if line.side == "bottom":
        return src.height - 1 - off, pos
    if line.side == "left":
        return pos, off
    return pos, src.width - 1 - off


def _frame_score(src: Source, line: Line, slack: int = 2):
    rows, cols = _line_pixels(src, line)
    rows, cols = np.rint(rows).astype(int), np.rint(cols).astype(int)
    pad = slack + 18
    ok = (cols >= pad) & (cols < src.width - pad) & (rows >= pad) & (rows < src.height - pad)
    rows, cols = rows[ok], cols[ok]
    vertical = line.side in ("top", "bottom")

    def at(o):
        return (rows + o, cols) if vertical else (rows, cols + o)

    hit = np.zeros(len(rows), bool)
    for o in range(-slack, slack + 1):
        hit |= src.is_dark(*at(o))
    sign = {"top": -1, "bottom": 1, "left": -1, "right": 1}[line.side]
    out = np.mean([src.is_white(*at(sign * k)).mean() for k in range(slack + 2, slack + 16)])
    inn = np.mean([src.is_white(*at(-sign * k)).mean() for k in range(slack + 2, slack + 16)])
    return float(hit.mean()), float(out), float(inn)


def frame_side(src: Source, side: str, depth: float = 0.45) -> tuple[Line | None, list]:
    """The straight frame line along one side: candidates by a Hough vote over thin ink runs, then the one with paper outside."""
    pts, length = _profile_points(src, side, depth)
    c0 = length / 2
    k = len(np.unique(pts[:, 0])) if len(pts) else 0
    cands = []
    for b in np.arange(-0.02, 0.02001, 0.0002):  # up to 1.15 degrees of turn: Colorado Springs TAC's right side is turned 0.6
        a = pts[:, 1] - b * (pts[:, 0] - c0)
        hist = np.bincount(np.rint(a).clip(0).astype(int))
        votes = hist + np.r_[hist[1:], 0] + np.r_[0, hist[:-1]]
        for i in np.flatnonzero(votes >= 0.4 * k):
            cands.append((votes[i], float(i), float(b)))
    cands.sort(reverse=True)
    kept: list[tuple] = []
    for v, a, b in cands:
        if all(abs(a - ka) > 4 or abs(b - kb) > 0.002 for _, ka, kb in kept):
            kept.append((v, a, b))
        if len(kept) >= 40:
            break
    scored = []
    for v, a, b in kept:
        line = Line(side, a, b, c0)
        # refine the offset to the half pixel
        best = max((_frame_score(src, Line(side, a + d, b, c0), slack=1)[0], a + d) for d in (-1, -0.5, 0, 0.5, 1))
        line.a = best[1]
        line.support, line.outside, line.inside = _frame_score(src, line)
        scored.append(line)
    # Paper outside, and of the lines with paper outside, the one with most of the chart inside it: a helicopter sheet's body is
    # mostly white itself, so a margin's rule (white both sides) must lose to the frame (white outside, the map inside).
    good = [ln for ln in scored if ln.support >= 0.55 and ln.outside >= 0.85 and ln.outside - ln.inside >= 0.03]
    if not good:
        good = [ln for ln in scored if ln.support >= 0.55 and ln.outside >= 0.6 and ln.outside - ln.inside >= 0.25]
    if not good:
        return None, scored
    return min(good, key=lambda ln: (round(ln.inside, 2), -ln.support)), scored


def _intersect(l1: Line, l2: Line, src: Source):
    """The pixel (col, row) where two frame lines of adjacent sides cross."""
    def as_xy(line):  # returns a function pos -> (col,row) and direction
        def f(pos):
            r, c = _line_point(src, line, pos)
            return np.array([c, r])
        return f
    f1, f2 = as_xy(l1), as_xy(l2)
    # Solve by iteration: both lines are nearly axis aligned.
    p1 = f1(0.0 if l2.side in ("left", "top") else (src.width if l1.side in ("top", "bottom") else src.height))
    for _ in range(30):
        pos2 = p1[0] if l2.side in ("top", "bottom") else p1[1]
        p2 = f2(pos2)
        pos1 = p2[0] if l1.side in ("top", "bottom") else p2[1]
        p1 = f1(pos1)
    return p1


def _line_point(src: Source, line: Line, pos: float):
    off = line.a + line.b * (pos - line.c0)
    if line.side == "top":
        return off, pos
    if line.side == "bottom":
        return src.height - 1 - off, pos
    if line.side == "left":
        return pos, off
    return pos, src.width - 1 - off


def frame_polygon(src: Source, lines: dict[str, Line]) -> list[tuple[float, float]]:
    """The frame's corners in pixels, clockwise from the top left."""
    order = ("top", "right", "bottom", "left")
    corners = []
    for i, s in enumerate(order):
        nxt = order[(i + 1) % 4]
        corners.append(tuple(_intersect(lines[s], lines[nxt], src)))
    # corners[i] is between side i and side i+1: top∩right, right∩bottom, bottom∩left, left∩top
    tr, br, bl, tl = corners
    return [tl, tr, br, bl]


# --------------------------------------------------------------------------------------------------------------- polygons


def densify(ring, step: float):
    """Points every ``step`` along a closed ring, the corners kept."""
    out = []
    for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
        n = max(1, int(math.ceil(math.hypot(x1 - x0, y1 - y0) / step)))
        for i in range(n):
            t = i / n
            out.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
    return out


def pixel_ring_degrees(src: Source, ring, step: float = 128.0):
    """A ring in the file's pixels as degrees, densified so that a straight line in the file stays one on the map."""
    pts = densify(list(ring), step)
    lon, lat = src.degrees([p[0] for p in pts], [p[1] for p in pts])
    return [(round(float(x), 6), round(float(y), 6)) for x, y in zip(lon, lat)]


def box_ring(w, s, e, n):
    """A sectional's outline: meridians and parallels are straight in degrees, so four corners say it all."""
    return [(w, n), (e, n), (e, s), (w, s)]


def closed(ring):
    ring = [tuple(p) for p in ring]
    return ring + [ring[0]] if ring[0] != ring[-1] else ring


def ring_area(ring) -> float:
    """Signed area in the ring's own units; positive anticlockwise (x east, y north)."""
    r = closed(ring)
    return 0.5 * sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(r, r[1:]))


def oriented(ring, ccw: bool):
    """GeoJSON's rule (RFC 7946): outer rings anticlockwise, holes clockwise."""
    r = [tuple(p) for p in ring]
    if r[0] == r[-1]:
        r = r[:-1]
    if (ring_area(r) > 0) != ccw:
        r = r[::-1]
    return closed(r)


INSET = 4           # pixels an outline keeps from the edge of the file


def clip_ring(ring, x0: float, y0: float, x1: float, y1: float):
    """A ring cut to an upright rectangle (Sutherland and Hodgman): what of it lies inside, or [] if nothing does."""
    def cut(pts, inside, cross):
        out = []
        for i, cur in enumerate(pts):
            prev = pts[i - 1]
            if inside(cur):
                if not inside(prev):
                    out.append(cross(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(cross(prev, cur))
        return out

    def at_x(x):
        return lambda p, q: (x, p[1] + (q[1] - p[1]) * (x - p[0]) / (q[0] - p[0]))

    def at_y(y):
        return lambda p, q: (p[0] + (q[0] - p[0]) * (y - p[1]) / (q[1] - p[1]), y)

    pts = [tuple(map(float, p)) for p in ring]
    if pts and pts[0] == pts[-1]:
        pts = pts[:-1]
    for inside, cross in ((lambda p: p[0] >= x0, at_x(x0)), (lambda p: p[0] <= x1, at_x(x1)),
                          (lambda p: p[1] >= y0, at_y(y0)), (lambda p: p[1] <= y1, at_y(y1))):
        if not pts:
            break
        pts = cut(pts, inside, cross)
    return pts


def simplify(ring, eps: float):
    """Douglas and Peucker on a closed ring, in its own units: points that lie within ``eps`` of a straight line are dropped."""
    import cv2

    a = np.asarray([tuple(p) for p in ring], np.float32).reshape(-1, 1, 2)
    out = cv2.approxPolyDP(a, eps, True).reshape(-1, 2)
    return [(float(x), float(y)) for x, y in out]


def file_box(src: Source):
    return INSET, INSET, src.width - 1 - INSET, src.height - 1 - INSET


FILE_EDGE = {"north": "top", "south": "bottom", "east": "right", "west": "left"}


def paper_border(src: Source, edge: str, ring, step: int = 25, reach: int = 400, run: int = 5, window: int = 15, pad: float = 2.0):
    """The bare paper along one edge of the file, as a cut in the file's pixels, or None when there is none to speak of.

    A side that runs on to the edge of the file seldom reaches it: the sheet keeps a white border of its own there, a few pixels to more
    than a hundred deep, sometimes a wedge or a tilted line. Its depth is measured every ``step`` pixels along the edge, where ``ring``
    (the outline in the file's pixels) reaches it, as the first ``run`` pixels in a row that are not bare paper. The depths are smoothed
    by a median over ``window`` of them, so a label printed over the border, or a white patch in the body, does not count, and ``pad``
    is added. Bare paper is every channel at ``PAPER`` or above: the pale grey Canada and Mexico are printed in is not paper.
    Returns (cut, deepest) with the deepest border in pixels.
    """
    import cv2

    paper = (src.palette >= PAPER).all(axis=1)
    along = src.width if edge in ("top", "bottom") else src.height
    poly = np.asarray(ring, np.float32).reshape(-1, 1, 2)
    x0, y0, x1, y1 = file_box(src)
    at = []
    for p in range(0, along, step):
        q = {"top": (p, y0 + 2), "bottom": (p, y1 - 2), "left": (x0 + 2, p), "right": (x1 - 2, p)}[edge]
        if cv2.pointPolygonTest(poly, (float(q[0]), float(q[1])), False) > 0:
            at.append(p)
    if len(at) < 2:
        return None
    at = np.asarray(at)
    reach = min(reach, src.height if edge in ("top", "bottom") else src.width)
    block = {"top": lambda: src.index[:reach, at],
             "bottom": lambda: src.index[-reach:, at][::-1],
             "left": lambda: src.index[at, :reach].T,
             "right": lambda: src.index[at, -reach:][:, ::-1].T}[edge]()
    ink = ~paper[block]                                               # (reach, samples): not paper
    sums = np.cumsum(np.vstack([np.zeros((1, ink.shape[1]), int), ink]), axis=0)
    solid = (sums[run:] - sums[:-run]) == run                         # a run of `run` non-paper pixels starts here
    depth = np.where(solid.any(axis=0), solid.argmax(axis=0), reach).astype(float)
    from scipy.ndimage import median_filter

    depth = median_filter(depth, size=min(window, len(depth)), mode="nearest")
    if depth.max() + pad <= INSET + 0.5:
        return None
    d = depth - 0.5 + pad                                             # from the file's edge to where the cut ends
    far = -10.0
    if edge == "top":
        line = [(float(p), float(v)) for p, v in zip(at, d)]
        cut = [(far, far), (src.width + 10.0, far), (src.width + 10.0, line[-1][1])] + line[::-1] + [(far, line[0][1])]
    elif edge == "bottom":
        line = [(float(p), src.height - 1 - float(v)) for p, v in zip(at, d)]
        bot = src.height + 10.0
        cut = [(far, bot), (far, line[0][1])] + line + [(src.width + 10.0, line[-1][1]), (src.width + 10.0, bot)]
    elif edge == "left":
        line = [(float(v), float(p)) for p, v in zip(at, d)]
        cut = [(far, far), (line[0][0], far)] + line + [(line[-1][0], src.height + 10.0), (far, src.height + 10.0)]
    else:
        line = [(src.width - 1 - float(v), float(p)) for p, v in zip(at, d)]
        right = src.width + 10.0
        cut = [(right, far), (right, src.height + 10.0), (line[-1][0], src.height + 10.0)] + line[::-1] + [(line[0][0], far)]
    return cut, int(depth.max())


def apply_cuts(src: Source, outer, cuts):
    """The outline with the cuts taken out, in the file's pixels: (outer, holes). A cut wholly inside is a hole, kept exact; once any
    cut crosses the edge, the outline is drawn as a mask at the file's own resolution and traced again, so it is notched."""
    import cv2

    box = file_box(src)
    cuts = [c for c in (clip_ring(c, *box) for c in cuts) if len(c) >= 3]
    poly = np.asarray(outer, np.float64)
    inner = all(cv2.pointPolygonTest(poly.astype(np.float32).reshape(-1, 1, 2), (float(x), float(y)), True) > 1
                for c in cuts for x, y in c)
    if inner:
        return outer, cuts
    x0, y0 = np.floor(poly.min(axis=0)).astype(int)
    x1, y1 = np.ceil(poly.max(axis=0)).astype(int)
    mask = np.zeros((y1 - y0 + 3, x1 - x0 + 3), np.uint8)
    shift = 4

    def fixed(ring):
        return [np.rint((np.asarray(ring, np.float64) - (x0 - 1, y0 - 1)) * (1 << shift)).astype(np.int32).reshape(-1, 1, 2)]

    cv2.fillPoly(mask, fixed(outer), 1, shift=shift)
    for c in cuts:
        cv2.fillPoly(mask, fixed(c), 0, shift=shift)
    contours, hierarchy = cv2.findContours(mask, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    if not contours:
        raise ValueError("the cuts leave nothing of the chart")
    top = [i for i, h in enumerate(hierarchy[0]) if h[3] < 0]
    keep = max(top, key=lambda i: cv2.contourArea(contours[i]))
    if any(cv2.contourArea(contours[i]) > 1e4 for i in top if i != keep):
        raise ValueError("the cuts split the chart in two")

    def back(c):
        return [(x + x0 - 1, y + y0 - 1) for x, y in simplify(c.reshape(-1, 2), 0.75)]

    holes = [back(contours[i]) for i, h in enumerate(hierarchy[0]) if h[3] == keep and cv2.contourArea(contours[i]) > 16]
    return back(contours[keep]), holes


def find_cut(src: Source, box, reach: int = 80, pieces: int = 8, pad: float = 6.0):
    """An inset's or a panel's outline in the file's pixels, from a rough box (x0, y0, x1, y1) round it.

    Each edge is where, coming in from outside, a line along it turns to paper (the white frame insets are printed in), found in
    ``pieces`` places and fitted as one straight line, so an inset printed a little turned is cut as it lies. An edge within ``reach`` of
    the edge of the file is that edge. The ring is moved ``pad`` pixels out all round, past the black rule some insets have."""
    x0, y0, x1, y1 = box
    fits = {}
    for side in ("top", "right", "bottom", "left"):
        across = side in ("top", "bottom")
        a, b = (x0, x1) if across else (y0, y1)
        pos = {"top": y0, "bottom": y1, "left": x0, "right": x1}[side]
        size = src.height if across else src.width
        out = -1 if side in ("top", "left") else 1
        if pos - reach <= 0 and out < 0:
            fits[side] = (0.0, -10.0)
            continue
        if pos + reach >= size - 1 and out > 0:
            fits[side] = (0.0, size + 10.0)
            continue
        pts = []
        for k in range(pieces):
            lo = max(0, int(a + (b - a) * (0.05 + 0.9 * k / pieces)))
            hi = min(src.width if across else src.height, int(a + (b - a) * (0.05 + 0.9 * (k + 1) / pieces)))
            if hi - lo < 10:
                continue
            for off in range(reach, -reach - 1, -1):
                p = int(pos + out * off)
                line = src.index[p, lo:hi] if across else src.index[lo:hi, p]
                if src.white[line].mean() > 0.6:
                    pts.append(((lo + hi) / 2, p))
                    break
        pts = np.asarray(pts, float)
        if len(pts) < 3:
            raise ValueError(f"no edge found along the {side}")
        for _ in range(2):
            m, c = np.polyfit(pts[:, 0], pts[:, 1], 1)
            res = np.abs(pts[:, 1] - (m * pts[:, 0] + c))
            if len(pts) > 3 and res.max() > 3:
                pts = pts[res <= max(3.0, np.median(res) * 3)]
        fits[side] = (float(m), float(c + out * pad))

    def meet(h, v):
        (mh, ch), (mv, cv) = fits[h], fits[v]          # y = mh x + ch;  x = mv y + cv
        y = (mh * cv + ch) / (1 - mh * mv)
        return (mv * y + cv, y)

    return [meet("top", "left"), meet("top", "right"), meet("bottom", "right"), meet("bottom", "left")]


def feature(chart: catalog.Chart, src: Source, outer, holes=(), how=None) -> dict:
    """The committed form of a chart's outline: a GeoJSON feature with what it was made from, so a later cycle can be compared."""
    return {
        "type": "Feature",
        "properties": {
            "id": chart.id,
            "title": chart.title,
            "tier": chart.tier,
            "file": chart.tif,
            "made_from": {
                "width": src.width,
                "height": src.height,
                "transform": [round(v, 9) for v in tuple(src.transform)[:6]],
            },
            "edges": how or {},
        },
        "geometry": {
            "type": "Polygon",
            "coordinates": [[list(p) for p in oriented(outer, True)]] + [[list(p) for p in oriented(h, False)] for h in holes],
        },
    }


def feature_text(feat: dict) -> str:
    """An outline as committed: its properties as they read, then a point to a line, so a corner moved is one line of a diff."""
    head = {k: v for k, v in feat.items() if k != "geometry"}
    props = json.dumps(head, indent=1, ensure_ascii=False)[:-2]          # without its closing brace
    rings = ",\n".join("   [\n" + ",\n".join(f"    [{json.dumps(x)},{json.dumps(y)}]" for x, y in r) + "\n   ]"
                       for r in feat["geometry"]["coordinates"])
    geom = f' "geometry": {{\n  "type": {json.dumps(feat["geometry"]["type"])},\n  "coordinates": [\n{rings}\n  ]\n }}'
    return props + ",\n" + geom + "\n}\n"


def load(chart_id: str) -> dict:
    with open(os.path.join(OUTLINES, f"{chart_id}.geojson"), encoding="utf-8") as f:
        return json.load(f)


def load_edits() -> dict:
    path = os.path.join(OUTLINES, "edits.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# ------------------------------------------------------------------------------------------------------------ proposing


def propose_sectional(src: Source):
    ext = _extent_degrees(src)
    sides = {s: graticule_side(src, s, ext)[0] for s in SIDES}
    return sides, ext


def propose_framed(src: Source):
    lines = {}
    for s in ("top", "right", "bottom", "left"):
        line, _ = frame_side(src, s)
        if line is None:
            raise ValueError(f"no frame line along the {s}")
        lines[s] = line
    return lines


def resolve_open_sides(sections: dict[str, dict[str, Side]], extents: dict[str, tuple]) -> list[str]:
    """Give each open side the edge it shares with the sectionals beyond it; return the sides left open, for a person to decide.

    A side is resolved only where the charts beyond it cover the whole of it: then it takes their facing neatline (they must
    agree), or, where they are open too, the middle of the overlap on a quarter degree. Cutting a side at a neighbour that covers
    only part of it would leave ground no chart shows (Phoenix's south, beside El Paso's north). ``sections`` maps a chart's id to
    its sides and is changed in place.
    """
    facing = {"north": "south", "south": "north", "east": "west", "west": "east"}
    left: list[str] = []

    def value(cid, name):
        v = sections[cid][name].value
        w, s, e, n = extents[cid]
        return v if v is not None else {"west": w, "south": s, "east": e, "north": n}[name]

    # A side's span is between its neighbouring sides, which may be open themselves until a later round resolves them.
    for _ in range(4):
        left = []
        before = sum(sd.value is None and sd.how != "file" for sides in sections.values() for sd in sides.values())
        _resolve_round(sections, extents, value, facing, left)
        if sum(sd.value is None and sd.how != "file" for sides in sections.values() for sd in sides.values()) == before:
            break
    return left


def content_side(src: Source, side: str, lo: float, hi: float, extent) -> float | None:
    """For an open side no sectional lies beyond (a coast, the border): the outermost quarter degree the chart's body reaches all
    along, between its neighbouring sides ``lo`` and ``hi``. Paper just outside it means a margin, not the body: never that."""
    w, s, e, n = extent
    if side in ("north", "south"):
        values = np.arange(math.floor(n / STEP) * STEP, (s + n) / 2, -STEP) if side == "north" else np.arange(math.ceil(s / STEP) * STEP, (s + n) / 2, STEP)
    else:
        values = np.arange(math.floor(e / STEP) * STEP, (w + e) / 2, -STEP) if side == "east" else np.arange(math.ceil(w / STEP) * STEP, (w + e) / 2, STEP)
    for v in values:
        along = np.linspace(lo, hi, 400)
        col, row = src.pixels(along, np.full(400, v)) if side in ("north", "south") else src.pixels(np.full(400, v), along)
        if (col < 40).any() or (row < 40).any() or (col > src.width - 41).any() or (row > src.height - 41).any():
            continue
        sc = _line_score(src, side, float(v), lo, hi)
        if sc and sc[1] < 0.5:
            return round(float(v), 4)
    return None


def _resolve_round(sections, extents, value, facing, left):
    for cid, sides in sections.items():
        for name, side in sides.items():
            if side.value is not None or side.how == "file":
                continue
            w, s, e, n = extents[cid]
            ns = name in ("north", "south")
            lo, hi = (value(cid, "west"), value(cid, "east")) if ns else (value(cid, "south"), value(cid, "north"))
            edge = {"north": n, "south": s, "east": e, "west": w}[name]
            out = 1 if name in ("north", "east") else -1
            beyond = []
            for oid in sections:
                if oid == cid:
                    continue
                ow, os_, oe, on = extents[oid]
                near, far = ((os_, on) if ns else (ow, oe)) if out > 0 else ((on, os_) if ns else (oe, ow))
                olo, ohi = (value(oid, "west"), value(oid, "east")) if ns else (value(oid, "south"), value(oid, "north"))
                if (near - edge) * out < 0 and (far - edge) * out > 1.0 and min(hi, ohi) - max(lo, olo) > 0.45:
                    beyond.append((oid, olo, ohi, near))
            covered, at = lo, None
            for oid, olo, ohi, _ in sorted(beyond, key=lambda b: b[1]):
                if olo <= covered + 0.1:
                    covered = max(covered, ohi)
            if not beyond or covered < hi - 0.1:
                left.append(f"{cid}:{name}")
                continue
            theirs = {sections[oid][facing[name]].value for oid, *_ in beyond if sections[oid][facing[name]].how == "neatline"}
            if len(theirs) == 1:
                at = theirs.pop()
            elif not theirs:
                mid = (edge + sum(b[3] for b in beyond) / len(beyond)) / 2
                at = round(mid / STEP) * STEP
            if at is None:
                left.append(f"{cid}:{name}")
                continue
            side.value, side.how = float(at), "neighbour:" + ",".join(sorted(b[0] for b in beyond))


# ------------------------------------------------------------------------------------------------------- checking by eye


def overview(src: Source, width: int):
    """The whole file shrunk to ``width`` pixels (nearest), as RGB, and the factor it was shrunk by."""
    from PIL import Image

    f = max(1, int(math.ceil(src.width / width)))
    small = src.palette[src.index[::f, ::f]]
    return Image.fromarray(small), f


def ring_pixels(src: Source, ring_deg, step_deg: float = 0.02):
    """A ring in degrees as the file's pixels, densified in degrees so a parallel is drawn as the arc it is."""
    pts = densify([tuple(p) for p in ring_deg[:-1]] if tuple(ring_deg[0]) == tuple(ring_deg[-1]) else list(ring_deg), step_deg)
    col, row = src.pixels([p[0] for p in pts], [p[1] for p in pts])
    return list(zip(col.tolist(), row.tolist()))


def check_image(src: Source, feat: dict, out: str, width: int = 1400) -> None:
    """The chart with what lies outside its polygon dimmed and the polygon drawn in red, for a person to check."""
    from PIL import Image, ImageDraw

    img, f = overview(src, width)
    mask = Image.new("L", img.size, 0)
    draw = ImageDraw.Draw(mask)
    rings = [[(c / f, r / f) for c, r in ring_pixels(src, ring)] for ring in feat["geometry"]["coordinates"]]
    draw.polygon(rings[0], fill=255)
    for hole in rings[1:]:
        draw.polygon(hole, fill=0)
    dim = Image.blend(img, Image.new("RGB", img.size, (40, 40, 40)), 0.6)
    img = Image.composite(img, dim, mask)
    d = ImageDraw.Draw(img)
    for ring in rings:
        d.line(ring + ring[:1], fill=(255, 0, 0), width=2)
    img.save(out)


# ------------------------------------------------------------------------------------------------------------- the tool


def _member(zips: str, chart: catalog.Chart) -> str:
    return f"/vsizip/{os.path.join(zips, chart.zip)}/{chart.tif}"


def _propose_one(args):
    zips, cid = args
    chart = catalog.BY_ID[cid]
    src = read(_member(zips, chart))
    edit = load_edits().get(cid, {})
    if chart.tier == "sectional":
        sides, ext = propose_sectional(src)
        for name, value in edit.get("sides", {}).items():
            sides[name] = Side(name, None, "file") if value == "file" else Side(name, float(value), "edit")
        return cid, {"sides": {k: vars(v) for k, v in sides.items()}, "extent": [float(v) for v in ext]}
    if "outer_pixels" in edit:  # a body no four straight lines bound (New York HEL's steps): the person checking traced it
        return cid, {"lines": {}}
    lines = {}
    for s in ("top", "right", "bottom", "left"):
        if s in edit.get("lines", {}):
            a, b = edit["lines"][s]
            n = src.width if s in ("top", "bottom") else src.height
            lines[s] = Line(s, float(a), float(b), n / 2)
            continue
        line, _ = frame_side(src, s)
        if line is None:
            return cid, {"error": f"no frame line along the {s}"}
        lines[s] = line
    return cid, {"lines": {k: vars(v) for k, v in lines.items()}}


def _content_one(args):
    zips, cid, name, sides, extent = args
    src = read(_member(zips, catalog.BY_ID[cid]))
    w, s, e, n = extent
    get = lambda k, d: sides[k].value if sides[k].value is not None else d  # noqa: E731
    lo, hi = (get("west", w), get("east", e)) if name in ("north", "south") else (get("south", s), get("north", n))
    return cid, name, content_side(src, name, lo, hi, extent)


def outline(src: Source, chart: catalog.Chart, found: dict, edit: dict):
    """A chart's outline in degrees, from what was found and what the person checking wrote: (outer, holes, how each edge was had)."""
    cuts = [[tuple(p) for p in c] for c in edit.get("cuts", [])]
    border = []
    if chart.tier == "sectional":
        sides = found["sides"]
        how = {k: v["how"] for k, v in sides.items()}
        left = [k for k, v in sides.items() if v["value"] is None and v["how"] != "file"]
        if left:
            raise ValueError(f"open sides left: {left}")
        w, s, e, n = found["extent"]
        beyond = {"west": w - 1, "south": s - 1, "east": e + 1, "north": n + 1}
        at = {k: beyond[k] if v["how"] == "file" else v["value"] for k, v in sides.items()}
        box = [tuple(p) for p in edit["outer"]] if "outer" in edit else box_ring(at["west"], at["south"], at["east"], at["north"])
        if box[0] == box[-1]:
            box = box[:-1]
        pts = densify(box, 0.02)
        col, row = src.pixels([p[0] for p in pts], [p[1] for p in pts])
        x0, y0, x1, y1 = file_box(src)
        if not cuts and ((col >= x0) & (col <= x1) & (row >= y0) & (row <= y1)).all():
            return box, [], how
        px = simplify(clip_ring(list(zip(col.tolist(), row.tolist())), x0, y0, x1, y1), 0.25)
        borders = {}
        for name, v in sides.items():
            if v["how"] == "file" and len(px) >= 3:
                found_border = paper_border(src, FILE_EDGE[name], px)
                if found_border:
                    border += [found_border[0]]
                    borders[name] = found_border[1]
        if borders:
            how = dict(how, border=borders)
    else:
        lines = {k: Line(**v) for k, v in found["lines"].items()}
        px = clip_ring(edit.get("outer_pixels") or frame_polygon(src, lines), *file_box(src))
        how = {k: "edit" if k in edit.get("lines", {}) else "neatline" for k in lines}
        if "outer_pixels" in edit:
            how = {"outer": "edit"}
    if len(px) < 3:
        raise ValueError("the outline lies outside the file")
    holes = []
    if cuts or border:
        px, holes = apply_cuts(src, px, cuts + border)
    if cuts:
        how = dict(how, cuts=len(cuts))
    return pixel_ring_degrees(src, px), [pixel_ring_degrees(src, h) for h in holes], how


def _feature_one(args):
    zips, cid, found, out = args
    chart = catalog.BY_ID[cid]
    src = read(_member(zips, chart))
    try:
        outer, holes, how = outline(src, chart, found, load_edits().get(cid, {}))
    except ValueError as e:
        return cid, str(e)
    feat = feature(chart, src, outer, holes, how)
    with open(os.path.join(out, f"{cid}.geojson"), "w", encoding="utf-8") as f:
        f.write(feature_text(feat))
    check_image(src, feat, os.path.join(out, f"{cid}.png"))
    return cid, None


def main(argv=None) -> int:
    """``python -m charts.neatline <zips> <out> [chart id …]``: propose outlines, and the images to check them by."""
    import argparse
    from concurrent.futures import ProcessPoolExecutor

    ap = argparse.ArgumentParser(description=main.__doc__)
    ap.add_argument("zips", help="a folder holding the FAA's zips, as downloaded")
    ap.add_argument("out", help="where to write <id>.geojson and <id>.png")
    ap.add_argument("ids", nargs="*", help="only these charts (sectionals are resolved against every sectional named)")
    ap.add_argument("--processes", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    a = ap.parse_args(argv)
    ids = a.ids or [c.id for c in catalog.CHARTS]
    os.makedirs(a.out, exist_ok=True)
    with ProcessPoolExecutor(a.processes) as pool:
        found = dict(pool.map(_propose_one, [(a.zips, i) for i in ids]))
    sections = {i: {k: Side(**v) for k, v in f["sides"].items()} for i, f in found.items() if "sides" in f}
    edits = load_edits()
    for s in resolve_open_sides(sections, {i: tuple(found[i]["extent"]) for i in sections}):
        cid, name = s.split(":")
        if name in edits.get(cid, {}).get("sides", {}):
            continue
        found[cid]["content"] = found[cid].get("content", []) + [name]
    with ProcessPoolExecutor(a.processes) as pool:
        for cid, name, v in pool.map(_content_one, [(a.zips, cid, name, sections[cid], found[cid]["extent"])
                                                   for cid in sections for name in found[cid].get("content", [])]):
            sections[cid][name] = Side(name, v, "content" if v is not None else "open")
            if v is None:
                print(f"{cid}:{name}: open, with no sectional beyond it, and no body reaching a quarter degree")
    for i, sides in sections.items():
        found[i]["sides"] = {k: vars(v) for k, v in sides.items()}
    with open(os.path.join(a.out, "found.json"), "w", encoding="utf-8") as f:
        json.dump(found, f, indent=1)
    bad = {i: f["error"] for i, f in found.items() if "error" in f}
    with ProcessPoolExecutor(a.processes) as pool:
        for cid, err in pool.map(_feature_one, [(a.zips, i, found[i], a.out) for i in ids if i not in bad]):
            if err:
                bad[cid] = err
    for cid, err in sorted(bad.items()):
        print(f"{cid}: {err}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
