"""A survey page's figures, drawn on the server from the snapshot (docs/LZ_SURVEYS.md §5), never screenshots of the editor, so the
page is the same whoever prints it: the plan (satellite tiles stitched as the brief's are, the slope map at the asked opacity, the
contours, the boundary, the approach and exit), the compass rose with the LZ's shape, the slope profiles as the web's
``SlopeProfile.jsx`` draws them, and the field survey's profile.

Lines and words are SVG, so they print sharp; the map under the plan is a JPEG. Everything here is escaped or a number.
"""

from __future__ import annotations

import base64
import io
import math
from xml.sax.saxutils import escape

from PIL import Image

from brief import figures as brief_figures

# The plan's image, at the brief's 200 pixels to the printed inch: about 3.9 by 4.2 inches.
PLAN_W, PLAN_H = 780, 840
WHITE = "#ffffff"
INK = "#101317"
BOUNDARY = "#ffd166"
APPROACH = "#ffffff"
EXIT = "#ffffff"
CONTOUR = "#101317"
CONTOUR_HALO = "rgba(255,255,255,0.85)"
METRES_PER_DEG = 111320.0


def _num(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _offset(lat, lon, bearing_deg, metres):
    """A place ``metres`` from (lat, lon) along a true bearing, flat-earth (an LZ is a few hundred metres across)."""
    rad = math.radians(bearing_deg)
    dlat = metres * math.cos(rad) / METRES_PER_DEG
    dlon = metres * math.sin(rad) / (METRES_PER_DEG * max(0.01, math.cos(math.radians(lat))))
    return lat + dlat, lon + dlon


def _edge_distance(target, boundary, bearing_deg):
    """How far from the target, along a bearing, the boundary's farthest crossing is, in metres; None without one."""
    if not boundary:
        return None
    lat0, lon0 = target
    k = math.cos(math.radians(lat0))
    pts = [((lon - lon0) * METRES_PER_DEG * k, (lat - lat0) * METRES_PER_DEG) for lat, lon in boundary]
    dx, dy = math.sin(math.radians(bearing_deg)), math.cos(math.radians(bearing_deg))
    best = None
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        ex, ey = x2 - x1, y2 - y1
        denom = dx * ey - dy * ex
        if abs(denom) < 1e-12:
            continue
        t = (x1 * ey - y1 * ex) / denom
        u = (x1 * dy - y1 * dx) / denom
        if t >= 0 and 0 <= u <= 1:
            best = t if best is None else max(best, t)
    return best


def _overlay(canvas, frame, overlay, bounds, opacity):
    """The slope map laid on the base image over its lat/lon bounds, as the web's image overlay lays it."""
    if not isinstance(overlay, str) or "," not in overlay or not brief_figures.valid_bounds(bounds):
        return canvas
    try:
        image = Image.open(io.BytesIO(base64.b64decode(overlay.split(",", 1)[1]))).convert("RGBA")
    except Exception:  # noqa: BLE001 - a raster that will not read leaves the plan without it, said on the page by its legend
        return canvas
    (south, west), (north, east) = bounds
    x0, y0 = frame.project(north, west)
    x1, y1 = frame.project(south, east)
    width, height = int(round(x1 - x0)), int(round(y1 - y0))
    if width < 1 or height < 1 or width > 20000 or height > 20000:
        return canvas
    image = image.resize((width, height), Image.NEAREST)
    alpha = image.getchannel("A").point(lambda a: int(a * opacity))
    image.putalpha(alpha)
    base = canvas.convert("RGBA")
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    layer.paste(image, (int(round(x0)), int(round(y0))))      # clipped to the frame where the raster runs past it
    return Image.alpha_composite(base, layer).convert("RGB")


def _text(x, y, words, size, colour=WHITE, anchor="start", weight=700, halo=INK, halo_w=5):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size:.3g}" text-anchor="{anchor}" fill="{colour}" stroke="{halo}" '
            f'stroke-width="{halo_w}" paint-order="stroke" font-weight="{weight}">{escape(str(words))}</text>')


def _fit(words, most):
    """``words`` cut to at most ``most`` characters, the last of them "…", where they are longer."""
    most = max(most, 2)
    return words if len(words) <= most else words[:most - 1].rstrip() + "…"


def _box_label(x, y, words, size=20, anchor="start"):
    """A word on a white box, as the draft's contour and arrow labels are."""
    words = str(words)
    width = size * 0.62 * len(words) + 10
    left = x if anchor == "start" else x - width / 2 if anchor == "middle" else x - width
    return (f'<rect x="{left:.1f}" y="{y - size * 0.95:.1f}" width="{width:.1f}" height="{size * 1.3:.1f}" fill="{WHITE}" '
            f'stroke="{INK}" stroke-width="1.5"/>'
            f'<text x="{left + 5:.1f}" y="{y:.1f}" font-size="{size}" fill="{INK}" font-weight="700">{escape(words)}</text>')


def _arrow(x0, y0, x1, y1, colour, width=7, head=24):
    angle = math.atan2(y1 - y0, x1 - x0)
    left = (x1 - head * math.cos(angle - 0.42), y1 - head * math.sin(angle - 0.42))
    right = (x1 - head * math.cos(angle + 0.42), y1 - head * math.sin(angle + 0.42))
    shaft_end = (x1 - head * 0.8 * math.cos(angle), y1 - head * 0.8 * math.sin(angle))
    return (f'<line x1="{x0:.1f}" y1="{y0:.1f}" x2="{shaft_end[0]:.1f}" y2="{shaft_end[1]:.1f}" stroke="{INK}" stroke-width="{width + 4}" '
            f'stroke-linecap="round"/>'
            f'<line x1="{x0:.1f}" y1="{y0:.1f}" x2="{shaft_end[0]:.1f}" y2="{shaft_end[1]:.1f}" stroke="{colour}" stroke-width="{width}" '
            f'stroke-linecap="round"/>'
            f'<polygon points="{x1:.1f},{y1:.1f} {left[0]:.1f},{left[1]:.1f} {right[0]:.1f},{right[1]:.1f}" fill="{colour}" '
            f'stroke="{INK}" stroke-width="2"/>')


def _minutes(value, positive, negative, digits):
    """34-35.8: degrees and decimal minutes as a chart's edge ticks write them."""
    value = abs(value)
    degrees = int(math.floor(value))
    minutes = (value - degrees) * 60
    return f"{degrees:0{digits}d}-{minutes:04.1f}"


def _ticks(frame):
    """Lat/long ticks every tenth of a minute (or a whole minute when that crowds) along the left and bottom edges, and a metres
    scale up the left, as the draft's plan has them."""
    parts = []
    (top_lat, left_lon) = _unproject(frame, 0, 0)
    (bottom_lat, right_lon) = _unproject(frame, frame.width, frame.height)
    span_min = (top_lat - bottom_lat) * 60
    step = 0.1 if span_min <= 1.2 else 0.5 if span_min <= 6 else 1.0
    first = math.ceil(bottom_lat * 60 / step) * step
    m = first
    while m <= top_lat * 60:
        _x, y = frame.project(m / 60, left_lon)
        if 30 < y < frame.height - 30:
            parts.append(f'<line x1="0" y1="{y:.1f}" x2="18" y2="{y:.1f}" stroke="{WHITE}" stroke-width="2"/>')
            parts.append(_box_label(24, y + 7, _minutes(m / 60, "N", "S", 2), size=17))
        m += step
    span_lon_min = (right_lon - left_lon) * 60
    step = 0.1 if span_lon_min <= 1.5 else 0.5 if span_lon_min <= 7 else 1.0
    m = math.ceil(left_lon * 60 / step) * step
    while m <= right_lon * 60:
        x, _y = frame.project(bottom_lat, m / 60)
        if 60 < x < frame.width - 250:             # clear of the scale bar and the attribution
            parts.append(f'<line x1="{x:.1f}" y1="{frame.height}" x2="{x:.1f}" y2="{frame.height - 18}" stroke="{WHITE}" stroke-width="2"/>')
            parts.append(_box_label(x, frame.height - 26, _minutes(m / 60, "E", "W", 2), size=17, anchor="middle"))
        m += step
    return "".join(parts)


def _unproject(frame, x, y):
    """The lat/lon at a pixel of the frame (Web Mercator)."""
    size = frame.tile_px * (2 ** frame.zoom)
    wx, wy = (x + frame.left) / size, (y + frame.top) / size
    lon = wx * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * wy))))
    return lat, lon


def _scale_bar(frame):
    metres = brief_figures._nice_scale(frame.metres_per_px() * frame.width * 0.3)
    if not metres:
        return ""
    bar = metres / frame.metres_per_px()
    words = f"{metres / 1000:g} km" if metres >= 1000 else f"{metres:g} m"
    x, y = frame.width - 30 - bar, frame.height - 60
    return (f'<rect x="{x:.1f}" y="{y}" width="{bar:.1f}" height="10" fill="{WHITE}" stroke="{INK}" stroke-width="2"/>'
            + _text(x + bar, y - 10, words, 20, anchor="end"))


def _north(frame):
    x = frame.width - 50
    return (f'<g transform="translate({x} 64)"><rect x="-26" y="-48" width="52" height="84" fill="{WHITE}" stroke="{INK}" stroke-width="2"/>'
            f'<polygon points="0,-38 14,6 0,-2 -14,6" fill="{INK}"/>'
            f'<text x="0" y="30" font-size="24" text-anchor="middle" fill="{INK}" font-weight="800">N</text></g>')


def plan(spec, width_px=PLAN_W, height_px=PLAN_H):
    """The plan figure: {'jpeg', 'svg'} for a page's ``plan`` (compile.compile_page), or None without a target."""
    if not isinstance(spec, dict) or not isinstance(spec.get("target"), dict):
        return None
    target = (spec["target"]["lat"], spec["target"]["lon"])
    boundary = spec.get("boundary") or []
    points = list(boundary) + [target]
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    extent = max((max(lats) - min(lats)) * METRES_PER_DEG,
                 (max(lons) - min(lons)) * METRES_PER_DEG * math.cos(math.radians(target[0])), 60.0)
    bounds = brief_figures.bounds_around(points, pad_m=max(90.0, extent * 0.38))
    frame = brief_figures.Frame(bounds, "satellite", width_px, height_px)
    image, missing = brief_figures.base_image(frame, "satellite")
    image = _overlay(image, frame, spec.get("overlay"), spec.get("overlayBounds"), spec.get("opacity") or 0.55)
    parts = []

    contours = spec.get("contours") if isinstance(spec.get("contours"), dict) else {}
    for line in contours.get("lines") or []:
        if not isinstance(line, dict) or not isinstance(line.get("points"), list):
            continue
        pts = [frame.project(p[0], p[1]) for p in line["points"] if isinstance(p, list) and len(p) == 2 and _num(p[0]) and _num(p[1])]
        if len(pts) < 2:
            continue
        d = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        width = 2.6 if line.get("major") else 1.5
        opacity = 1.0 if line.get("inside") else 0.55
        parts.append(f'<polyline points="{d}" fill="none" stroke="{CONTOUR_HALO}" stroke-width="{width + 2.5}" stroke-opacity="{opacity:.2f}" '
                     f'stroke-linejoin="round"/>'
                     f'<polyline points="{d}" fill="none" stroke="{CONTOUR}" stroke-width="{width}" stroke-opacity="{opacity:.2f}" '
                     f'stroke-linejoin="round"/>')
    # The name's box and the target are kept clear; a contour's number that would sit on them, or on a number already placed, is left
    # out (the server spaces them 40 m apart, which at a page's zoom can be a label's width), majors first.
    tx, ty = frame.project(*target)
    # A name too long for the room on either side of the target is cut to the wider side, never drawn off the picture.
    room = max(frame.width - 10 - (tx + 26), tx - 26 - 10)
    name = _fit(spec.get("label") or "", int((room - 16) // 16))
    sub = _fit(spec.get("sublabel") or "", int((room - 16) // 12))
    name_w = max(len(name) * 16 + 16, len(sub) * 12 + 16)
    name_x = tx + 26 if tx + 26 + name_w < frame.width - 10 else tx - 26 - name_w
    taken = [(tx - 18, ty - 18, tx + 18, ty + 18), (name_x, ty - 34, name_x + name_w, ty + 22)]

    def clear(box):
        return all(box[2] < t[0] or box[0] > t[2] or box[3] < t[1] or box[1] > t[3] for t in taken)

    labelled = [line for line in contours.get("lines") or [] if isinstance(line, dict) and isinstance(line.get("label"), list)
                and len(line["label"]) == 2 and _num(line["label"][0]) and _num(line["label"][1]) and _num(line.get("elevationFt"))]
    for line in sorted(labelled, key=lambda l: not l.get("major")):
        x, y = frame.project(line["label"][0], line["label"][1])
        words = f"{int(line['elevationFt'])}′"
        width = 18 * 0.62 * len(words) + 10
        box = (x - width / 2 - 2, y - 12, x + width / 2 + 2, y + 13)
        if clear(box):
            taken.append(box)
            parts.append(_box_label(x, y + 7, words, size=18, anchor="middle"))

    ring = [frame.project(lat, lon) for lat, lon in boundary]
    if len(ring) >= 3:
        d = " ".join(f"{x:.1f},{y:.1f}" for x, y in ring)
        parts.append(f'<polygon points="{d}" fill="none" stroke="{INK}" stroke-width="7" stroke-opacity="0.55"/>'
                     f'<polygon points="{d}" fill="none" stroke="{BOUNDARY}" stroke-width="4" stroke-dasharray="16 10"/>')

    for heading, words, outward in ((spec.get("approachDeg"), spec.get("approachWords"), False), (spec.get("exitDeg"), spec.get("exitWords"), True)):
        if not _num(heading):
            continue
        edge = _edge_distance(target, boundary, heading if outward else (heading + 180) % 360) or 25.0
        length = max(70.0, min(220.0, extent * 0.45))
        if outward:
            start = _offset(*target, heading, edge + 12)
            end = _offset(*target, heading, edge + 12 + length)
            label_at = end
            text = f"EXIT {words}"
        else:
            start = _offset(*target, (heading + 180) % 360, edge + 12 + length)
            end = _offset(*target, (heading + 180) % 360, edge + 12)
            label_at = start
            text = f"APCH {words}"
        (x0, y0), (x1, y1) = frame.project(*start), frame.project(*end)
        parts.append(_arrow(x0, y0, x1, y1, APPROACH if not outward else EXIT))
        lx, ly = frame.project(*label_at)
        lx = min(max(lx + 14, 70), frame.width - 150)
        ly = min(max(ly + 8, 40), frame.height - 50)
        parts.append(_box_label(lx, ly, text, size=21))

    parts.append(f'<circle cx="{tx:.1f}" cy="{ty:.1f}" r="15" fill="{WHITE}" stroke="{INK}" stroke-width="3"/>'
                 f'<circle cx="{tx:.1f}" cy="{ty:.1f}" r="6" fill="{INK}"/>')
    if name:
        parts.append(f'<rect x="{name_x:.1f}" y="{ty - 34:.1f}" width="{name_w:.1f}" height="{56 if sub else 36}" fill="{WHITE}" stroke="{INK}" stroke-width="2"/>'
                     f'<text x="{name_x + 8:.1f}" y="{ty - 9:.1f}" font-size="26" font-weight="800" fill="{INK}">{escape(name)}</text>'
                     + (f'<text x="{name_x + 8:.1f}" y="{ty + 15:.1f}" font-size="18" fill="{INK}">{escape(sub)}</text>' if sub else ""))

    parts.append(_ticks(frame))
    parts.append(_north(frame))
    parts.append(_scale_bar(frame))
    note = frame.source["attribution"]
    if missing:
        note = f"{missing} map tile{'s' if missing != 1 else ''} could not be fetched · {note}"
    parts.append(_text(frame.width - 12, frame.height - 12, note, 14, anchor="end", weight=400, halo_w=4))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {frame.width} {frame.height}" class="fig-overlay">{"".join(parts)}</svg>'
    return {"jpeg": brief_figures.jpeg(image), "svg": svg, "zoom": frame.zoom, "missing": missing}


def compass(shape, approach_deg=None, size=200):
    """The compass rose with the LZ's outline inside it, north up, and the approach line through its middle."""
    r = size / 2 - 8
    c = size / 2
    parts = [f'<circle cx="{c}" cy="{c}" r="{r}" fill="{WHITE}" stroke="{INK}" stroke-width="2.5"/>']
    for deg in range(0, 360, 30):
        rad = math.radians(deg)
        inner = r - (12 if deg % 90 == 0 else 7)
        parts.append(f'<line x1="{c + math.sin(rad) * inner:.1f}" y1="{c - math.cos(rad) * inner:.1f}" x2="{c + math.sin(rad) * r:.1f}" '
                     f'y2="{c - math.cos(rad) * r:.1f}" stroke="{INK}" stroke-width="{2 if deg % 90 == 0 else 1.2}"/>')
    parts.append(f'<polygon points="{c},{c - r + 2} {c + 7},{c - r + 20} {c - 7},{c - r + 20}" fill="{INK}"/>')
    if _num(approach_deg):
        rad = math.radians(approach_deg)
        dx, dy = math.sin(rad) * (r - 4), -math.cos(rad) * (r - 4)
        parts.append(f'<line x1="{c - dx:.1f}" y1="{c - dy:.1f}" x2="{c + dx:.1f}" y2="{c + dy:.1f}" stroke="{INK}" stroke-width="1.6"/>')
    xy = (shape or {}).get("xy") if isinstance(shape, dict) else None
    if xy and len(xy) >= 3:
        reach = max(math.hypot(x, y) for x, y in xy) or 1.0
        scale = (r * 0.78) / reach
        d = " ".join(f"{c + x * scale:.1f},{c - y * scale:.1f}" for x, y in xy)
        parts.append(f'<polygon points="{d}" fill="#e2e2e2" stroke="{INK}" stroke-width="2"/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" class="compass">{"".join(parts)}</svg>'


# The web's helicopter outlines (feature/terrain/SlopeProfile.jsx, HELI_SIDE and HELI_REAR), path for path.
HELI_SIDE = (28, 16, (
    "M2 1.2H26V2.6H2Z", "M13.3 2.6H14.7V5H13.3Z",
    "M9 5H19.5C23 5 25.5 7.2 26 10C25.6 12 24 13 21 13H10C8.6 13 8 12 8 10.8V6C8 5.4 8.4 5 9 5Z",
    "M1 7.6L8.2 8L8.2 10.2L1 9.4Z", "M0.6 4.6L2.4 4.6L2.6 9.6L0.6 9.6Z", "M9.5 14.4H23V15.4H9.5Z",
    "M12 13H13V14.4H12Z", "M19.5 13H20.5V14.4H19.5Z",
))
HELI_REAR = (26, 16, (
    "M1 1.2H25V2.6H1Z", "M12.3 2.6H13.7V5H12.3Z",
    "M9.5 5H16.5C17.9 5 19 6.1 19 7.5V11C19 12.4 17.9 13.5 16.5 13.5H9.5C8.1 13.5 7 12.4 7 11V7.5C7 6.1 8.1 5 9.5 5Z",
    "M8.6 12.6L9.6 13.4L6.4 15.6L5.4 14.8Z", "M17.4 12.6L16.4 13.4L19.6 15.6L20.6 14.8Z",
))
# Flat ground is not drawn as a cliff: the height axis spans at least this many feet (SlopeProfile.jsx's MIN_SPAN_FT).
MIN_SPAN_FT = 30


def profile(line, view, width=640, height=170, treetops=None):
    """One slope profile as the web draws it: the ground over a hatched fill, the LZ marked, its top and bottom heights, the ends named,
    and the helicopter (``view``: "side" on the approach end, nose to the LZ; "rear" over the LZ). ``treetops``, the LiDAR's tallest
    point in each stretch (step L4), is drawn over the ground where given. None when the line has under two heights."""
    known = [h for h in line["elevationsFt"] if h is not None]
    if len(known) < 2:
        return None
    tops = [t for t in (treetops or []) if t is not None]
    low, high = min(known), max(known + tops)
    span = max(high - low, MIN_SPAN_FT)
    middle = (low + high) / 2
    floor, ceil = middle - span / 2, middle + span / 2
    left, right, top, bottom = 70.0, width - 6.0, 34.0, height - 30.0
    first, last = line["distancesFt"][0], line["distancesFt"][-1]

    def sx(d):
        return left + (d - first) / (last - first) * (right - left)

    def sy(h):
        return bottom - (h - floor) / span * (bottom - top)

    runs, run = [], []
    for d, h in zip(line["distancesFt"], line["elevationsFt"]):
        if h is None:
            if len(run) > 1:
                runs.append(run)
            run = []
        else:
            run.append((d, h))
    if len(run) > 1:
        runs.append(run)

    parts = [f'<defs><pattern id="hatch-{view}" width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
             f'<line x1="0" y1="0" x2="0" y2="7" stroke="#9a9a9a" stroke-width="1.2"/></pattern></defs>']
    lz = line.get("lz")
    if lz:
        x0, x1 = sx(lz[0]), sx(lz[1])
        parts.append(f'<line x1="{x0:.1f}" y1="{top}" x2="{x0:.1f}" y2="{bottom}" stroke="{INK}" stroke-width="1.4" stroke-dasharray="5 4"/>'
                     f'<line x1="{x1:.1f}" y1="{top}" x2="{x1:.1f}" y2="{bottom}" stroke="{INK}" stroke-width="1.4" stroke-dasharray="5 4"/>'
                     f'<rect x="{x0:.1f}" y="{bottom - 1:.1f}" width="{max(3.0, x1 - x0):.1f}" height="8" fill="{INK}"/>'
                     f'<text x="{(x0 + x1) / 2:.1f}" y="{bottom + 22:.1f}" font-size="15" text-anchor="middle" font-weight="800">LZ</text>')
    for run in runs:
        pts = " ".join(f"{sx(d):.1f},{sy(h):.1f}" for d, h in run)
        parts.append(f'<polygon points="{sx(run[0][0]):.1f},{bottom} {pts} {sx(run[-1][0]):.1f},{bottom}" fill="url(#hatch-{view})"/>')
        parts.append(f'<polyline points="{pts}" fill="none" stroke="{INK}" stroke-width="2.2" stroke-linejoin="round"/>')
    if tops:
        # A run of steps where something stands: one stand of trees is not joined to the next across the ground between them.
        tree_runs, run = [], []
        for d, t in zip(line["distancesFt"], treetops):
            if t is None:
                if run:
                    tree_runs.append(run)
                run = []
            else:
                run.append((d, t))
        if run:
            tree_runs.append(run)
        for run in tree_runs:
            if len(run) == 1:
                (d, t), = run
                parts.append(f'<line x1="{sx(d):.1f}" y1="{sy(t):.1f}" x2="{sx(d):.1f}" y2="{bottom:.1f}" stroke="#2e7d32" stroke-width="1.8"/>')
            else:
                parts.append(f'<polyline points="{" ".join(f"{sx(d):.1f},{sy(t):.1f}" for d, t in run)}" fill="none" stroke="#2e7d32" '
                             f'stroke-width="1.8" stroke-dasharray="3 2"/>')
    # The highest ground in the LZ, as the draft marks it, and the axis's low end in the gutter.
    inside = [(d, h) for d, h in zip(line["distancesFt"], line["elevationsFt"]) if h is not None and lz and lz[0] <= d <= lz[1]]
    if inside:
        d, h = max(inside, key=lambda p: p[1])
        # Beside the point where the helicopter is over it (the line across), above it otherwise.
        at = (sx(d) + 8, sy(h) + 4, "start") if view != "side" else (sx(d), sy(h) - 9, "middle")
        parts.append(f'<circle cx="{sx(d):.1f}" cy="{sy(h):.1f}" r="3.5" fill="{INK}"/>'
                     f'<text x="{at[0]:.1f}" y="{at[1]:.1f}" font-size="15" text-anchor="{at[2]}" font-weight="800">{int(round(h)):,}′</text>')
    parts.append(f'<text x="{left - 6:.1f}" y="{sy(min(known)) + 5:.1f}" font-size="14" text-anchor="end">{int(round(min(known))):,}′</text>')
    parts.append(f'<text x="{left:.1f}" y="{height - 4}" font-size="14">{escape(str(line.get("start") or ""))}</text>'
                 f'<text x="{right:.1f}" y="{height - 4}" font-size="14" text-anchor="end">{escape(str(line.get("end") or ""))}</text>')
    art_w, art_h, paths = HELI_SIDE if view == "side" else HELI_REAR
    scale = 1.6
    if view == "side":
        reach = [h for d, h in zip(line["distancesFt"], line["elevationsFt"]) if h is not None and d <= first + (last - first) * 0.15]
        hx = left + 4
    else:
        middle_d = (lz[0] + lz[1]) / 2 if lz else (first + last) / 2
        reach = [h for d, h in inside] if inside else known
        hx = sx(middle_d) - art_w * scale / 2
    hy = max(0.0, min(sy(h) for h in (reach or known)) - art_h * scale - 3)
    parts.append(f'<g transform="translate({hx:.1f} {hy:.1f}) scale({scale})" fill="{INK}">'
                 + "".join(f'<path d="{d}"/>' for d in paths) + "</g>")
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" class="profile">{"".join(parts)}</svg>'


def survey_profile(spec, width=300, height=120):
    """The field survey's profile, as the old book drew it: the HLZ, an obstacle's height at a distance, and the angle over it."""
    if not isinstance(spec, dict):
        return None
    ground = height - 34
    hx, ox = 30.0, width - 50.0
    oy = 22.0
    parts = [
        f'<line x1="6" y1="{ground}" x2="{width - 6}" y2="{ground}" stroke="{INK}" stroke-width="2"/>',
        f'<rect x="{hx - 18}" y="{ground - 7}" width="36" height="9" fill="{INK}"/>',
        f'<text x="{hx}" y="{ground + 20}" font-size="14" text-anchor="middle" font-weight="800">HLZ</text>',
        f'<line x1="{ox}" y1="{ground}" x2="{ox}" y2="{oy}" stroke="{INK}" stroke-width="1.6" stroke-dasharray="5 4"/>',
        f'<line x1="{hx + 18}" y1="{ground - 4}" x2="{ox}" y2="{oy}" stroke="{INK}" stroke-width="2"/>',
        f'<polygon points="{hx + 18},{ground - 4} {hx + 34},{ground - 12} {hx + 32},{ground - 2}" fill="{INK}"/>',
        f'<text x="{ox + 8}" y="{(ground + oy) / 2 + 5}" font-size="16" font-weight="800">{spec["obstacleFt"]}′</text>',
        f'<text x="{(hx + ox) / 2}" y="{ground + 20}" font-size="15" text-anchor="middle">{spec["distanceM"]} m</text>',
        f'<text x="{(hx + ox) / 2 - 10}" y="{(ground + oy) / 2 - 8}" font-size="15" font-weight="800" text-anchor="middle">{spec["angleDeg"]:.1f}°</text>',
    ]
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" class="field-profile">{"".join(parts)}</svg>'


TREETOPS = "#2e6b30"


def clearance(spec, width=512, height=104):
    """The LiDAR's angles (step L4) as the page's clearance box draws them, from the touchdown point: the approach on the left, flown
    towards the LZ, and the exit on the right, each its track's ground (thicker over the LZ) and the highest thing in the way in each 10
    m of its lane out to 1,000 m; the path at the angle printed out to where it is 100 ft above the ground (a dot), then 100 ft above
    the ground past it (dashed); the thing that sets the LiDAR's angle (a triangle) and what stands higher than 100 ft past the dot (a
    ring); and its words over it (how much of the lane the survey covers in the corner, where it is not all of it). None where neither
    way was measured. The box prints it 0.58 in high and its full width, about 0.40 pt to a unit: its words are 11 to 15 units, 4.4 to
    6 pt."""
    sides = [s for s in ((spec or {}).get("approach"), (spec or {}).get("exit")) if isinstance(s, dict)]
    values = [v for s in sides for v in s["groundFt"] + s["topsFt"] + [s.get("startFt")] if _num(v)]
    values += [h for s in sides for _m, h in (s.get("path") or []) + (s.get("level") or []) if _num(h)]
    if not values:
        return None
    low, high = min(values), max(values)
    span = max(high - low, 60.0)
    floor = low - span * 0.06
    # The plot starts below the third line of words, and the marks drawn over its top (a triangle's tip, a ring) clear them.
    top, bottom, pad, gap = 50.0, height - 14.0, 4.0, 30.0
    half = (width - 2 * pad - gap) / 2.0

    def sy(h):
        return bottom - (h - floor) / (span * 1.06) * (bottom - top)

    # The LZ between the two ways, on the touchdown point's ground where it is known (both ways start from it).
    start = next((s["startFt"] for s in sides if _num(s.get("startFt"))), None)
    lz_y = sy(start) - 3 if start is not None else bottom - 6
    parts = [f'<rect x="{pad + half:.1f}" y="{lz_y:.1f}" width="{gap:.1f}" height="6" fill="{INK}"/>',
             f'<text x="{width / 2:.1f}" y="{height - 2:.1f}" font-size="12" font-weight="800" text-anchor="middle">LZ</text>']
    for spec_side, left in ((spec.get("approach"), True), (spec.get("exit"), False)):
        if not isinstance(spec_side, dict):
            continue
        step = spec_side.get("binM") or 10
        length = step * max(len(spec_side["groundFt"]), 1)

        def sx(metres, left=left, length=length):
            return (pad + half - metres / length * half) if left else (pad + half + gap + metres / length * half)

        def line(points, colour, widthpx, dashed=False):
            runs, run = [], []
            for metres, v in points:
                if _num(v):
                    run.append(f"{sx(metres):.1f},{sy(v):.1f}")
                else:
                    if len(run) > 1:
                        runs.append(run)
                    run = []
            if len(run) > 1:
                runs.append(run)
            dash = ' stroke-dasharray="4 3"' if dashed else ""
            return "".join(f'<polyline points="{" ".join(r)}" fill="none" stroke="{colour}" stroke-width="{widthpx}" '
                           f'stroke-linejoin="round"{dash}/>' for r in runs)

        def steps(values, step=step):
            return [((k + 0.5) * step, v) for k, v in enumerate(values)]

        # The treetops under the ground's line, so they show only where something stands above it; the ground thicker over the LZ.
        parts.append(line(steps(spec_side["topsFt"]), TREETOPS, 1.1))
        parts.append(line(steps(spec_side["groundFt"]), INK, 1.6))
        if _num(spec_side.get("edgeM")) and _num(spec_side.get("startFt")):
            inside = [(0.0, spec_side["startFt"])] + [(m, v) for m, v in steps(spec_side["groundFt"]) if m < spec_side["edgeM"]]
            parts.append(line(inside, INK, 3.2))
        path, level = spec_side.get("path") or [], spec_side.get("level") or []
        parts.append(line([tuple(p) for p in path], INK, 1.3))
        parts.append(line([tuple(p) for p in level], INK, 1.1, dashed=True))
        if level:
            x, y = sx(level[0][0]), sy(level[0][1])
            parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="2.4" fill="{INK}"/>')
        obstacle = spec_side.get("obstacle")
        if obstacle and _num(spec_side.get("startFt")) and _num(obstacle.get("riseFt")) and _num(obstacle.get("fromTargetM")):
            # The thing that sets the LiDAR's angle, as the angle sees it: its rise over the touchdown point's ground.
            x1, y1 = sx(obstacle["fromTargetM"]), sy(spec_side["startFt"] + obstacle["riseFt"])
            parts.append(f'<polygon points="{x1:.1f},{y1 - 6:.1f} {x1 - 4:.1f},{y1 + 1:.1f} {x1 + 4:.1f},{y1 + 1:.1f}" fill="{INK}"/>')
        for tall in spec_side.get("tall") or []:
            parts.append(f'<circle cx="{sx(tall["fromTargetM"]):.1f}" cy="{sy(tall["topFt"]):.1f}" r="3" fill="#ffffff" stroke="{INK}" '
                         f'stroke-width="1.2"/>')
        x_words = pad + 2 if left else width - pad - 2
        anchor = "start" if left else "end"
        lines = ((13, 15, 800, spec_side.get("angle") or spec_side["label"] + " " + (spec_side.get("heading") or "")),
                 (27, 13, 400, spec_side.get("climb")),
                 (40, 11.5, 800, spec_side.get("what")))
        for y, size, weight, words in lines:
            if words:
                parts.append(f'<text x="{x_words:.1f}" y="{y}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}">'
                             f'{escape(words)}</text>')
        parts.append(f'<text x="{(pad if left else width - pad):.1f}" y="{height - 2:.1f}" font-size="11" '
                     f'font-weight="{800 if spec_side.get("partial") else 400}" text-anchor="{anchor}">'
                     f'{escape(spec_side.get("partial") or f"{length / 1000:g} KM")}</text>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" class="clearance">{"".join(parts)}</svg>'


# The cover's overview map: about 7.6 by 7.0 inches at the plan's 200 pixels to the inch.
OVERVIEW_W, OVERVIEW_H = 1520, 1400


def overview(spec, width_px=OVERVIEW_W, height_px=OVERVIEW_H):
    """The book's cover map: {'jpeg', 'svg'}, every page's LZ as a dot with its page number beside it, on the topographic map; None with
    no point to show. ``spec``: {'points': [{'n', 'lat', 'lon'}]}. A number goes beside its dot where it keeps clear of those already
    placed (right, left, above, below), else to the right regardless: LZs half a kilometre apart share a few pixels at this scale."""
    points = [(p["lat"], p["lon"], p["n"]) for p in (spec or {}).get("points") or []
              if isinstance(p, dict) and _num(p.get("lat")) and _num(p.get("lon")) and isinstance(p.get("n"), int)]
    if not points:
        return None
    bounds = brief_figures.bounds_around([(lat, lon) for lat, lon, _n in points], pad_m=1500)
    frame = brief_figures.Frame(bounds, "topo", width_px, height_px)
    image, missing = brief_figures.base_image(frame, "topo")
    parts, labels, taken = [], [], []
    placed = [(frame.project(lat, lon), n) for lat, lon, n in points]
    for (x, y), _n in placed:
        taken.append((x - 9, y - 9, x + 9, y + 9))
        parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="8" fill="#d62828" stroke="{WHITE}" stroke-width="3"/>')

    def clear(box):
        return all(box[2] < t[0] or box[0] > t[2] or box[3] < t[1] or box[1] > t[3] for t in taken)

    for (x, y), n in placed:
        words = str(n)
        w, h = 22 * 0.62 * len(words) + 12, 30
        tries = [(x + 12, y - h / 2), (x - 12 - w, y - h / 2), (x - w / 2, y - 14 - h), (x - w / 2, y + 14)]
        left, top = next(((l, t) for l, t in tries if clear((l, t, l + w, t + h))), tries[0])
        taken.append((left, top, left + w, top + h))
        labels.append(f'<rect x="{left:.1f}" y="{top:.1f}" width="{w:.1f}" height="{h}" fill="{WHITE}" stroke="{INK}" stroke-width="1.6"/>'
                      f'<text x="{left + w / 2:.1f}" y="{top + 22:.1f}" font-size="22" text-anchor="middle" fill="{INK}" '
                      f'font-weight="800">{escape(words)}</text>')
    parts.extend(labels)
    parts.append(_north(frame))
    parts.append(_scale_bar(frame))
    note = frame.source["attribution"]
    if missing:
        note = f"{missing} map tile{'s' if missing != 1 else ''} could not be fetched · {note}"
    parts.append(_text(frame.width - 12, frame.height - 12, note, 16, anchor="end", weight=400, halo_w=4))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {frame.width} {frame.height}" class="fig-overlay">{"".join(parts)}</svg>'
    return {"jpeg": brief_figures.jpeg(image), "svg": svg, "zoom": frame.zoom, "missing": missing}
