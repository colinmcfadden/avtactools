"""Build the VFR chart tiles from the FAA's GeoTIFFs: steps 3 to 7 of a build (docs/VFR_CHARTS.md §5.3).

C0 runs it by hand over a few charts, from a folder of the FAA's zips into a folder of its own::

    python -m charts.build <zips> <out> atlanta-sec atlanta-tac washington-hel …

C1 runs the same steps as a Data tab job; downloading, unzipping, the build's number and ``state.json`` are C1's. Here:

- ``check``: a chart is a georeferenced one-band file with a palette, its edition dates are read from its metadata, and its layout is
  the one its outline was made from (§4.2). Comparing the neatline's pixels along the outline is C1's.
- ``prepare``: the palette expanded to RGB before anything is resampled (palette indices cannot be averaged), and the outline made an
  alpha mask; a tiled GeoTIFF in the chart's own projection.
- ``tile``: each stored tile warped with Lanczos from every chart under it that is shown at its level, laid by tier (sectionals
  first), WebP at quality 90, into one MBTiles file. Level 11 is also kept with sectionals alone where a finer chart is drawn on it, to
  seed the levels below.
- ``lower``: levels 10 to 5, each tile four of the level below shrunk with Lanczos (in premultiplied alpha): sectionals only (§5.3).
- ``validate``: the tile counts, a tile at each tier against a reference warped by another path, a level-10 tile in a TAC's ground
  against the sectional alone, every chart present.
- ``coverage``: the build's ``coverage.json`` (§4.5).

Levels are of 512-pixel tiles (§4.1): MapLibre's zoom, Leaflet's less one. MBTiles numbers rows from the south (TMS); everything
here numbers them from the north, as the URL does, and flips them at the file.
"""
from __future__ import annotations

import io
import json
import math
import os
import re
import sqlite3
import time
import zipfile
from dataclasses import dataclass
from datetime import date

import numpy as np
import rasterio
from PIL import Image
from pyproj import Transformer
from rasterio.enums import ColorInterp, Resampling
from rasterio.features import rasterize
from rasterio.transform import Affine
from rasterio.vrt import WarpedVRT
from rasterio.warp import reproject
from rasterio.windows import Window

from . import catalog, neatline

TILE = 512
QUALITY = 90
MERC = "EPSG:3857"
HALF = 20037508.342789244       # half the width of Web Mercator's square, in metres
TOP = 11                        # the level the sectionals are stored to, and the lower levels are shrunk from
LOWEST = 5                      # the lowest level stored: where the sectional tier is first shown
SCAN_DPI = 300                  # the FAA's GeoTIFFs are scanned at 300 dots an inch: a chart's scale from its pixel size
LANCZOS = 3                     # the kernel's radius, in source pixels at 1:1


class Refused(Exception):
    """A chart that cannot be built in this form, with the reason in words (§5.3, step 3)."""


# ------------------------------------------------------------------------------------------------------------- tile maths


def tile_box(z: int, x: int, y: int):
    """A tile's bounds in Web Mercator metres: (west, south, east, north); ``y`` from the north."""
    size = 2 * HALF / 2 ** z
    return -HALF + x * size, HALF - (y + 1) * size, -HALF + (x + 1) * size, HALF - y * size


def tile_transform(z: int, x: int, y: int) -> Affine:
    w, _, _, n = tile_box(z, x, y)
    px = 2 * HALF / 2 ** z / TILE
    return Affine(px, 0, w, 0, -px, n)


def tms_row(z: int, y: int) -> int:
    return 2 ** z - 1 - y


_TO_MERC = Transformer.from_crs("EPSG:4326", MERC, always_xy=True)
_FROM_MERC = Transformer.from_crs(MERC, "EPSG:4326", always_xy=True)


def to_merc(lon, lat):
    """Degrees to Web Mercator. NAD83 is taken as WGS84, as PROJ does between them without a grid (about a metre in CONUS)."""
    lat = np.clip(np.asarray(lat, float), -85.0511, 85.0511)
    return _TO_MERC.transform(np.asarray(lon, float), lat)


# ---------------------------------------------------------------------------------------------------------------- checking


@dataclass
class Checked:
    chart: catalog.Chart
    member: str                 # GDAL's path to the chart inside its zip
    start: date                 # the edition's dates, as printed
    end: date
    scale: int                  # 1:scale, from the pixel size at 300 dpi, to three figures
    metres_per_pixel: float


_DATE = {k: re.compile(k + r"\s*:\s*(\d{8})") for k in ("Beginning_Date", "Ending_Date")}


def _metadata_dates(text: str):
    """The edition's dates from the chart's FGDC metadata (its ``.htm``): the first Beginning_Date and Ending_Date."""
    plain = re.sub(r"<[^>]+>", " ", text)
    found = {}
    for k, rx in _DATE.items():
        m = rx.search(plain)
        if not m:
            raise Refused(f"its metadata gives no {k}")
        v = m.group(1)
        found[k] = date(int(v[:4]), int(v[4:6]), int(v[6:]))
    if found["Ending_Date"] < found["Beginning_Date"]:
        raise Refused("its metadata ends before it begins")
    return found["Beginning_Date"], found["Ending_Date"]


def check(zips: str, chart: catalog.Chart, outline: dict | None = None) -> Checked:
    """Step 3: is this the chart its outline was made for, and which edition is it?"""
    member = neatline._member(zips, chart)
    try:
        ds = rasterio.open(member)
    except rasterio.errors.RasterioIOError as e:
        raise Refused(f"its file cannot be read ({e})") from None
    with ds:
        if ds.count != 1 or ds.dtypes[0] != "uint8":
            raise Refused("it is not one band of 8 bits")
        try:
            ds.colormap(1)
        except ValueError:
            raise Refused("it has no palette") from None
        if ds.crs is None or not ds.crs.is_projected:
            raise Refused("it is not georeferenced in a projection")
        t = ds.transform
        if t.b or t.d:
            raise Refused("its pixels are turned")
        width, height = ds.width, ds.height
        mpp = abs(t.a) * ds.crs.linear_units_factor[1]
    outline = outline or neatline.load(chart.id)
    made = outline["properties"]["made_from"]
    if (width, height) != (made["width"], made["height"]):
        raise Refused(f"it is {width} by {height} pixels; its outline was made from one of {made['width']} by {made['height']}")
    if any(abs(a - b) > 1e-6 * max(1.0, abs(b)) for a, b in zip(tuple(t)[:6], made["transform"])):
        raise Refused("its georeferencing has changed since its outline was made")
    htm = chart.tif.rsplit(".", 1)[0] + ".htm"
    try:
        with zipfile.ZipFile(os.path.join(zips, chart.zip)) as zf:
            text = zf.read(htm).decode("utf-8", "replace")
    except KeyError:
        raise Refused(f"its zip holds no {htm}") from None
    start, end = _metadata_dates(text)
    # to three figures: 1:62,500 for an inset, whose metadata gives its main chart's scale ("Insets are of varying scales")
    scale = int(float(f"{mpp * SCAN_DPI / 0.0254:.3g}"))
    return Checked(chart, member, start, end, scale, mpp)


# --------------------------------------------------------------------------------------------------------------- preparing


def _rings(outline: dict):
    geom = outline["geometry"]
    if geom["type"] != "Polygon":
        raise ValueError("an outline is one polygon")
    return [[tuple(p) for p in r[:-1]] for r in geom["coordinates"]]


def outline_mask(src: neatline.Source, outline: dict) -> np.ndarray:
    """The outline as the chart's pixels: 255 inside (a pixel whose centre is), 0 outside and in its holes."""
    rings = []
    for ring in _rings(outline):
        pts = neatline.densify(ring, 0.01)          # a parallel is an arc in the chart's projection
        col, row = src.pixels([p[0] for p in pts], [p[1] for p in pts])
        rings.append([(c + 0.5, r + 0.5) for c, r in zip(col.tolist(), row.tolist())])
    return rasterize([({"type": "Polygon", "coordinates": rings}, 255)], out_shape=(src.height, src.width),
                     transform=Affine.identity(), fill=0, dtype="uint8")


def prepare(checked: Checked, work: str, outline: dict | None = None) -> str:
    """Step 4: the chart as RGBA in its own projection, transparent outside its outline. Written whole, then renamed, so a resumed
    build never takes half a file for a whole one."""
    chart = checked.chart
    path = os.path.join(work, f"{chart.id}.tif")
    if os.path.exists(path):
        return path
    src = neatline.read(checked.member)
    mask = outline_mask(src, outline or neatline.load(chart.id))
    profile = dict(driver="GTiff", width=src.width, height=src.height, count=4, dtype="uint8", crs=src.crs,
                   transform=src.transform, tiled=True, blockxsize=TILE, blockysize=TILE, compress="deflate", predictor=2,
                   zlevel=6, photometric="RGB", alpha="YES", num_threads="ALL_CPUS", bigtiff="IF_SAFER")
    part = path + ".part"
    with rasterio.open(part, "w", **profile) as dst:
        dst.colorinterp = [ColorInterp.red, ColorInterp.green, ColorInterp.blue, ColorInterp.alpha]
        step = 2048
        for r0 in range(0, src.height, step):
            r1 = min(src.height, r0 + step)
            rgb = src.palette[src.index[r0:r1]]
            a = mask[r0:r1]
            rgb[a == 0] = 0
            dst.write(np.concatenate([rgb.transpose(2, 0, 1), a[None]]), window=Window(0, r0, src.width, r1 - r0))
    os.replace(part, path)
    return path


# ------------------------------------------------------------------------------------------------------------- footprints


@dataclass
class Footprint:
    """A chart's outline in Web Mercator, and its box."""

    id: str
    tier: str
    rings: list                 # outer first, then holes; each a list of (x, y)
    box: tuple                  # west, south, east, north


def footprint(chart: catalog.Chart, outline: dict | None = None) -> Footprint:
    rings = []
    for ring in _rings(outline or neatline.load(chart.id)):
        pts = neatline.densify(ring, 0.05)
        x, y = to_merc([p[0] for p in pts], [p[1] for p in pts])
        rings.append(list(zip(x.tolist(), y.tolist())))
    xs = [p[0] for p in rings[0]]
    ys = [p[1] for p in rings[0]]
    return Footprint(chart.id, chart.tier, rings, (min(xs), min(ys), max(xs), max(ys)))


def tiles_under(fp: Footprint, z: int, inside: bool = False) -> set:
    """The tiles at level ``z`` the footprint touches (or, with ``inside``, those it covers whole)."""
    size = 2 * HALF / 2 ** z
    w, s, e, n = fp.box
    x0, x1 = int(math.floor((w + HALF) / size)), int(math.floor((e + HALF) / size))
    y0, y1 = int(math.floor((HALF - n) / size)), int(math.floor((HALF - s) / size))
    shape = (y1 - y0 + 1, x1 - x0 + 1)
    tr = Affine(size, 0, -HALF + x0 * size, 0, -size, HALF - y0 * size)
    geom = {"type": "Polygon", "coordinates": fp.rings}
    if inside:
        # covered whole: every one of a 4 by 4 grid of points in the cell is inside (holes are not)
        grid = rasterize([(geom, 1)], out_shape=(shape[0] * 4, shape[1] * 4),
                         transform=tr * Affine.scale(0.25), fill=0, dtype="uint8")
        cells = grid.reshape(shape[0], 4, shape[1], 4).min(axis=(1, 3))
    else:
        cells = rasterize([(geom, 1)], out_shape=shape, transform=tr, fill=0, all_touched=True, dtype="uint8")
    rows, cols = np.nonzero(cells)
    return {(int(c) + x0, int(r) + y0) for r, c in zip(rows, cols)}


def levels(tier: str):
    """The levels a tier's charts are rendered at directly: from where it is shown (11 at the lowest) to where it is stored."""
    shown, stored = catalog.TIERS[tier]
    return range(max(shown, TOP), stored + 1)


def plan(prints: list[Footprint]) -> dict[int, dict[tuple, list[str]]]:
    """Each stored tile above level 10 and the charts drawn on it, coarsest tier first: every chart shown at that level whose box
    meets the tile (one whose outline misses it draws nothing)."""
    order = {t: i for i, t in enumerate(catalog.TIER_ORDER)}
    rank = {c.id: i for i, c in enumerate(catalog.CHARTS)}          # within a tier, the catalog's order; others last
    out: dict[int, dict[tuple, list[str]]] = {}
    for fp in prints:
        for z in levels(fp.tier):
            for xy in tiles_under(fp, z):
                out.setdefault(z, {})[xy] = []
    for z, tiles in out.items():
        shown = sorted((fp for fp in prints if catalog.TIERS[fp.tier][0] <= z), key=lambda f: (order[f.tier], rank.get(f.id, len(rank)), f.id))
        for (x, y), layers in tiles.items():
            w, s, e, n = tile_box(z, x, y)
            for fp in shown:
                bw, bs, be, bn = fp.box
                if bw < e and be > w and bs < n and bn > s:
                    layers.append(fp.id)
    return out


# --------------------------------------------------------------------------------------------------------------- rendering

_OPEN: dict[str, object] = {}
_TO: dict[str, Transformer] = {}


def _dataset(path: str):
    ds = _OPEN.get(path)
    if ds is None:
        ds = _OPEN[path] = rasterio.open(path)
    return ds


def warp(path: str, z: int, x: int, y: int) -> np.ndarray | None:
    """One prepared chart warped into one tile, with Lanczos, weighted by its alpha: (4, 512, 512) uint8, or None where it draws
    nothing. Only the window of the chart under the tile is read, with room for the kernel."""
    ds = _dataset(path)
    key = ds.crs.to_wkt()
    t = _TO.get(key)
    if t is None:
        t = _TO[key] = Transformer.from_crs(MERC, ds.crs, always_xy=True)
    w, s, e, n = tile_box(z, x, y)
    k = 9
    xs = np.r_[np.linspace(w, e, k), np.full(k, e), np.linspace(e, w, k), np.full(k, w)]
    ys = np.r_[np.full(k, n), np.linspace(n, s, k), np.full(k, s), np.linspace(s, n, k)]
    cx, cy = t.transform(xs, ys)
    col, row = ~ds.transform * (np.asarray(cx), np.asarray(cy))
    shrink = max((col.max() - col.min()) / TILE, (row.max() - row.min()) / TILE)
    pad = int(math.ceil(LANCZOS * max(1.0, shrink))) + 2
    c0, r0 = max(0, int(math.floor(col.min())) - pad), max(0, int(math.floor(row.min())) - pad)
    c1, r1 = min(ds.width, int(math.ceil(col.max())) + pad), min(ds.height, int(math.ceil(row.max())) + pad)
    if c0 >= c1 or r0 >= r1:
        return None
    win = Window(c0, r0, c1 - c0, r1 - r0)
    src = ds.read(window=win)
    if not src[3].any():
        return None
    dst = np.zeros((4, TILE, TILE), np.uint8)
    reproject(src, dst, src_transform=ds.window_transform(win), src_crs=ds.crs, dst_transform=tile_transform(z, x, y),
              dst_crs=MERC, resampling=Resampling.lanczos, src_alpha=4, dst_alpha=4)
    return dst if dst[3].any() else None


def composite(layers: list[np.ndarray]) -> np.ndarray | None:
    """Layers (4, H, W) laid bottom first, each over what is under it: (H, W, 4) uint8, or None if nothing shows."""
    layers = [d for d in layers if d is not None]
    if not layers:
        return None
    for i in range(len(layers) - 1, -1, -1):        # an opaque layer hides everything under it
        if (layers[i][3] == 255).all():
            layers = layers[i:]
            break
    if len(layers) == 1:
        return np.ascontiguousarray(layers[0].transpose(1, 2, 0))
    p = np.zeros((3,) + layers[0].shape[1:], np.float32)
    a = np.zeros(layers[0].shape[1:], np.float32)
    for d in layers:
        da = d[3].astype(np.float32) / 255
        p = d[:3] * da + p * (1 - da)
        a = da + a * (1 - da)
    rgb = np.where(a > 0, p / np.maximum(a, 1e-6), 0)
    out = np.concatenate([np.clip(np.rint(rgb), 0, 255), np.rint(a * 255)[None]]).astype(np.uint8)
    return np.ascontiguousarray(out.transpose(1, 2, 0))


def encode(rgba: np.ndarray | None) -> bytes | None:
    """WebP at quality 90: RGB where the tile is opaque, RGBA where the edge of the coverage crosses it; None if it is empty."""
    if rgba is None or not rgba[..., 3].any():
        return None
    if (rgba[..., 3] == 255).all():
        im = Image.fromarray(rgba[..., :3], "RGB")
    else:
        im = Image.fromarray(rgba, "RGBA")
    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=QUALITY, method=4)
    return buf.getvalue()


def decode(data: bytes) -> np.ndarray:
    return np.asarray(Image.open(io.BytesIO(data)).convert("RGBA"))


def _seed_path(work: str, z: int, x: int, y: int) -> str:
    return os.path.join(work, "seed", str(z), f"{x}_{y}.png")


def _tile_one(args):
    """A worker's tile: (z, x, y, webp or None, seconds to render, seconds to encode)."""
    z, x, y, layers, work = args
    t0 = time.perf_counter()
    drawn = [(tier, warp(path, z, x, y)) for tier, path in layers]
    rgba = composite([d for _, d in drawn])
    if z == TOP and any(tier != "sectional" and d is not None for tier, d in drawn):
        alone = composite([d for tier, d in drawn if tier == "sectional"])
        if alone is None:                       # a finer chart where no sectional is: nothing below
            alone = np.zeros((TILE, TILE, 4), np.uint8)
        seed = _seed_path(work, z, x, y)
        os.makedirs(os.path.dirname(seed), exist_ok=True)
        Image.fromarray(alone, "RGBA").save(seed + ".part.png", compress_level=1)
        os.replace(seed + ".part.png", seed)
    t1 = time.perf_counter()
    data = encode(rgba)
    return z, x, y, data, t1 - t0, time.perf_counter() - t1


def _lower_one(args):
    """A tile of a lower level from the four below it, sectionals only: from level 11's sectional-only copy where there is one, the
    stored tile elsewhere, and below that from the lossless copy each lower tile leaves in ``work``."""
    z, x, y, mbtiles, work = args
    t0 = time.perf_counter()
    mosaic = Image.new("RGBA", (2 * TILE, 2 * TILE), (0, 0, 0, 0))
    any_child = False
    db = None
    for dy in (0, 1):
        for dx in (0, 1):
            cz, cx, cy = z + 1, 2 * x + dx, 2 * y + dy
            seed = _seed_path(work, cz, cx, cy)
            im = None
            if os.path.exists(seed):
                im = Image.open(seed).convert("RGBA")
            elif cz == TOP:
                if db is None:
                    db = sqlite3.connect(f"file:{mbtiles}?mode=ro", uri=True)
                row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                                 (cz, cx, tms_row(cz, cy))).fetchone()
                if row:
                    im = Image.open(io.BytesIO(row[0])).convert("RGBA")
            if im is not None:
                mosaic.paste(im, (dx * TILE, dy * TILE))
                any_child = True
    if db is not None:
        db.close()
    if not any_child:
        return z, x, y, None, time.perf_counter() - t0, 0.0
    small = mosaic.resize((TILE, TILE), Image.Resampling.LANCZOS)      # Pillow shrinks RGBA premultiplied
    rgba = np.asarray(small)
    if z > LOWEST:
        seed = _seed_path(work, z, x, y)
        os.makedirs(os.path.dirname(seed), exist_ok=True)
        small.save(seed + ".part.png", compress_level=1)
        os.replace(seed + ".part.png", seed)
    t1 = time.perf_counter()
    return z, x, y, encode(rgba), t1 - t0, time.perf_counter() - t1


# --------------------------------------------------------------------------------------------------------------- the file


class MBTiles:
    """One build's tiles. One process writes it; tiles are put in batches. WAL while it is written, so the lower levels' workers can
    read the level above, and a single file once it is finished."""

    def __init__(self, path: str):
        self.path = path
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS name ON metadata (name)")
        self.db.execute("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, "
                        "tile_data BLOB)")
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
        self.db.commit()
        self.pending = 0

    def keys(self) -> set:
        return {(z, x, tms_row(z, r)) for z, x, r in self.db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles")}

    def put(self, z: int, x: int, y: int, data: bytes) -> None:
        self.db.execute("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", (z, x, tms_row(z, y), sqlite3.Binary(data)))
        self.pending += 1
        if self.pending >= 256:
            self.commit()

    def get(self, z: int, x: int, y: int) -> bytes | None:
        row = self.db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                              (z, x, tms_row(z, y))).fetchone()
        return row[0] if row else None

    def commit(self) -> None:
        self.db.commit()
        self.pending = 0

    def finish(self, meta: dict) -> None:
        for k, v in meta.items():
            self.db.execute("INSERT OR REPLACE INTO metadata VALUES (?, ?)", (k, str(v)))
        self.commit()
        self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        self.db.execute("PRAGMA journal_mode=DELETE")
        self.db.close()


# ------------------------------------------------------------------------------------------------------------- the coverage


def native_level(c: Checked, fp: Footprint) -> float:
    """The level at which one of the chart's pixels is one of a tile's, at the middle of its outline."""
    w, s, e, n = fp.box
    _, lat = _FROM_MERC.transform((w + e) / 2, (s + n) / 2)
    return math.log2(2 * HALF * math.cos(math.radians(lat)) / (TILE * c.metres_per_pixel))


def coverage(build: str, cycle: date, charts: list[Checked], outlines: dict[str, dict], prints: dict[str, Footprint], counts: dict,
             eps: float = 0.0005):
    """The build's ``coverage.json`` (§4.5): every chart in it, with what the clients show of it. Outlines simplified to ``eps``
    degrees (about 50 m) and five decimals: they say which chart is in view, not where its pixels end."""
    out = []
    lons, lats = [], []
    for c in charts:
        rings = []
        for i, ring in enumerate(_rings(outlines[c.chart.id])):
            x0, y0 = ring[0]                    # simplified about its first point, so float32 keeps the digits
            near = neatline.simplify([(x - x0, y - y0) for x, y in ring], eps)
            r = [(round(x + x0, 5), round(y + y0, 5)) for x, y in near]
            rings.append([list(p) for p in neatline.oriented(r, i == 0)])
        lons += [p[0] for p in rings[0]]
        lats += [p[1] for p in rings[0]]
        shown, stored = catalog.TIERS[c.chart.tier]
        out.append({
            "id": c.chart.id,
            "title": c.chart.title,
            "tier": c.chart.tier,
            "scale": c.scale,
            "edition": {
                "start": c.start.isoformat(),
                "end": c.end.isoformat(),
                "effective": catalog.effective_instant(c.start).strftime("%Y-%m-%dT%H:%MZ"),
                "expires": catalog.expires_instant(c.end).strftime("%Y-%m-%dT%H:%MZ"),
            },
            "kept": False,
            "shownFrom": shown,
            "storedTo": stored,
            "nativeLevel": round(native_level(c, prints[c.chart.id]), 2),
            "outline": {"type": "Polygon", "coordinates": rings},
        })
    levels_held = sorted(counts)
    return {
        "version": 1,
        "build": build,
        "cycle": cycle.isoformat(),
        "effective": catalog.effective_instant(cycle).strftime("%Y-%m-%dT%H:%MZ"),
        "expires": min(catalog.expires_instant(c.end) for c in charts).strftime("%Y-%m-%dT%H:%MZ"),
        "tileSize": TILE,
        "minLevel": levels_held[0],
        "maxLevel": levels_held[-1],
        "bounds": [round(min(lons), 5), round(min(lats), 5), round(max(lons), 5), round(max(lats), 5)],
        "tiles": {str(z): counts[z] for z in levels_held},
        "charts": out,
    }


def coverage_text(cov: dict) -> str:
    """``coverage.json`` as written: a field to a line and a chart to a line, so a chart that changes is one line of a diff (and the
    fixture in ``contracts/fixtures/charts/`` follows the folder's rule)."""
    def one(v):
        return json.dumps(v, ensure_ascii=False, separators=(",", ":"))

    lines = []
    for k, v in cov.items():
        if k == "charts":
            lines.append(' "charts": [\n' + ",\n".join("  " + one(c) for c in v) + "\n ]")
        else:
            lines.append(f" {json.dumps(k)}: {one(v)}")
    return "{\n" + ",\n".join(lines) + "\n}\n"


# ------------------------------------------------------------------------------------------------------------- validating


def reference(path: str, z: int, x: int, y: int) -> np.ndarray:
    """A chart warped into a tile by another path (GDAL's warped VRT over the prepared file): (H, W, 4)."""
    with rasterio.open(path) as ds, WarpedVRT(ds, crs=MERC, transform=tile_transform(z, x, y), width=TILE, height=TILE,
                                              resampling=Resampling.lanczos) as vrt:
        return np.ascontiguousarray(vrt.read().transpose(1, 2, 0))


def difference(a: np.ndarray, b: np.ndarray) -> dict:
    d = np.abs(a[..., :3].astype(np.int16) - b[..., :3].astype(np.int16))
    mse = float((d.astype(np.float64) ** 2).mean())
    return {"mean": round(float(d.mean()), 2), "p99": int(np.percentile(d, 99)),
            "psnr": round(10 * math.log10(255 ** 2 / mse), 1) if mse else None}


def validate(mb: MBTiles, expected: dict[int, set], empty: dict[int, set], charts: list[Checked], paths: dict[str, str],
             prints: dict[str, Footprint], sectionals: list[str]) -> dict:
    """Step 7, in the shape ``validate.check_dem`` uses: refused with reasons in words, and notes that refuse nothing."""
    refused, notes = [], []
    held = {}
    for z, x, r in mb.db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
        held.setdefault(z, set()).add((x, tms_row(z, r)))
    for z in sorted(expected):
        want = expected[z] - empty.get(z, set())
        have = held.get(z, set())
        if have != want:
            refused.append(f"level {z}: {len(want)} tiles expected, {len(have)} held ({len(want - have)} missing, "
                           f"{len(have - want)} not expected)")
        else:
            notes.append(f"level {z}: {len(have)} tiles ({len(empty.get(z, ()))} with nothing on them, not stored)")
    ids = {c.chart.id for c in charts}
    for s in sectionals:
        if s not in ids:
            refused.append(f"the {catalog.BY_ID[s].title if s in catalog.BY_ID else s} is missing")
    order = {t: i for i, t in enumerate(catalog.TIER_ORDER)}
    for c in charts:
        z = c.chart.stored_to
        fp = prints[c.chart.id]
        # a tile it covers whole, with nothing finer (or later of its tier) over it
        others = set()
        for o in prints.values():
            if o.id != fp.id and catalog.TIERS[o.tier][0] <= z and order[o.tier] >= order[fp.tier]:
                others |= tiles_under(o, z)
        inside = sorted(tiles_under(fp, z, inside=True) - others)
        if not inside:
            notes.append(f"{c.chart.title}: no tile at level {z} that it covers alone, so none compared")
            continue
        mx = sum(x for x, _ in inside) / len(inside)
        my = sum(y for _, y in inside) / len(inside)
        x, y = min(inside, key=lambda t: (t[0] - mx) ** 2 + (t[1] - my) ** 2)
        data = mb.get(z, x, y)
        if data is None:
            refused.append(f"{c.chart.title}: tile {z}/{x}/{y} is missing")
            continue
        got, ref = decode(data), reference(paths[c.chart.id], z, x, y)
        same = difference(got, decode(encode(ref)))          # the warp, apart from WebP's loss
        loss = difference(got, ref)                          # what WebP at quality 90 costs this tile
        line = f"{c.chart.title}: tile {z}/{x}/{y} against a reference warped by GDAL's VRT and encoded alike: mean " \
               f"{same['mean']}; WebP's own loss, mean {loss['mean']}, PSNR {loss['psnr']} dB"
        (notes if same["mean"] <= 1 and (loss["psnr"] or 99) >= 28 else refused).append(line)
    # a level-10 tile in a TAC's ground holds the sectional alone
    for c in charts:
        if c.chart.tier != "tac":
            continue
        inside = sorted(tiles_under(prints[c.chart.id], 10, inside=True))
        if not inside:
            continue
        x, y = inside[len(inside) // 2]
        data = mb.get(10, x, y)
        under = [s for s in charts if s.chart.tier == "sectional" and (x, y) in tiles_under(prints[s.chart.id], 10, inside=True)]
        if data is None or not under:
            continue
        got = decode(data)
        sec = difference(got, decode(encode(reference(paths[under[0].chart.id], 10, x, y))))
        tac = difference(got, decode(encode(reference(paths[c.chart.id], 10, x, y))))
        line = f"level 10 tile 10/{x}/{y}, inside the {c.chart.title}: mean difference {sec['mean']} from the " \
               f"{under[0].chart.title} alone, {tac['mean']} from the {c.chart.title}"
        (notes if sec["mean"] < tac["mean"] / 2 else refused).append(line)
    return {"refused": refused, "notes": notes}


# --------------------------------------------------------------------------------------------------------------- the build


class _Here:
    """A pool of one: the work in this process, so a test or a debugger sees it."""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def map(self, fn, *its, chunksize=1):
        return map(fn, *its)


def _pool(processes: int):
    from concurrent.futures import ProcessPoolExecutor

    return ProcessPoolExecutor(processes) if processes > 1 else _Here()


def _prepare_one(args):
    return prepare(*args)


def build(zips: str, out: str, ids: list[str], processes: int = 2, label: str | None = None, log=print,
          charts: dict[str, catalog.Chart] | None = None, outlines: dict[str, dict] | None = None) -> dict:
    """Steps 3 to 7 over the charts named, into ``out``: ``vfr.mbtiles``, ``coverage.json``, ``report.json`` and ``work/``.
    ``charts`` and ``outlines`` default to the catalog's and the committed ones (a test passes its own)."""
    work = os.path.join(out, "work")
    os.makedirs(work, exist_ok=True)
    known = charts or catalog.BY_ID
    charts = [known[i] for i in ids]
    outlines = outlines or {c.id: neatline.load(c.id) for c in charts}
    report: dict = {"charts": {}, "times": {}}

    t = time.perf_counter()
    checked = []
    for c in charts:
        try:
            checked.append(check(zips, c, outlines[c.id]))
        except Refused as e:
            report["charts"][c.id] = f"refused: {e}"
            log(f"{c.id}: refused, {e}")
    if any(c.tier == "sectional" and c.id not in {k.chart.id for k in checked} for c in charts):
        raise Refused("a sectional was refused, and there is no older copy to stand in for it")
    cycle = catalog.cycle_on(min(c.start for c in checked))
    label = label or f"{cycle.isoformat()}.1"
    report["times"]["check"] = round(time.perf_counter() - t, 1)

    t = time.perf_counter()
    with _pool(min(processes, len(checked))) as pool:
        paths = dict(zip([c.chart.id for c in checked],
                         pool.map(_prepare_one, [(c, work, outlines[c.chart.id]) for c in checked])))
    report["times"]["prepare"] = round(time.perf_counter() - t, 1)
    log(f"prepared {len(paths)} charts in {report['times']['prepare']} s")

    prints = {c.chart.id: footprint(c.chart, outlines[c.chart.id]) for c in checked}
    tiers = {c.chart.id: c.chart.tier for c in checked}
    top = plan(list(prints.values()))
    mb = MBTiles(os.path.join(out, "vfr.mbtiles"))
    have = mb.keys()
    expected = {z: set(tiles) for z, tiles in top.items()}
    empty: dict[int, set] = {}
    render, enc = [], []

    def take(results):
        for z, x, y, data, tr, te in results:
            if data is None:
                empty.setdefault(z, set()).add((x, y))
            else:
                mb.put(z, x, y, data)
                render.append(tr)
                enc.append(te)

    t = time.perf_counter()
    tasks = [(z, x, y, [(tiers[i], paths[i]) for i in layers], work)
             for z in sorted(top) for (x, y), layers in sorted(top[z].items()) if (z, x, y) not in have]
    log(f"tiling {len(tasks)} tiles at levels {sorted(top)} on {processes} processes")
    with _pool(processes) as pool:
        take(pool.map(_tile_one, tasks, chunksize=4))
    mb.commit()
    report["times"]["tile"] = round(time.perf_counter() - t, 1)
    report["times"]["tile_render_mean"] = round(float(np.mean(render)), 3) if render else None
    report["times"]["tile_encode_mean"] = round(float(np.mean(enc)), 3) if enc else None
    report["times"]["tile_count"] = len(tasks)

    t = time.perf_counter()
    below = {xy for xy in expected.get(TOP, set())} - empty.get(TOP, set())
    with _pool(processes) as pool:
        for z in range(TOP - 1, LOWEST - 1, -1):
            below = {(x // 2, y // 2) for x, y in below}
            expected[z] = set(below)
            tasks = [(z, x, y, mb.path, work) for x, y in sorted(below) if (z, x, y) not in have]
            take(pool.map(_lower_one, tasks, chunksize=8))
            mb.commit()
            below = below - empty.get(z, set())
    report["times"]["lower"] = round(time.perf_counter() - t, 1)

    counts = {z: len(expected[z] - empty.get(z, set())) for z in expected}
    cov = coverage(label, cycle, checked, outlines, prints, counts)
    with open(os.path.join(out, "coverage.json"), "w", encoding="utf-8") as f:
        f.write(coverage_text(cov))
    w, s, e, n = cov["bounds"]
    report["validate"] = validate(mb, expected, empty, checked, paths, prints,
                                  [c.chart.id for c in checked if c.chart.tier == "sectional"])
    mb.finish({"name": "FAA VFR charts", "format": "webp", "type": "baselayer", "version": label,
               "description": "FAA VFR sectional, terminal area and helicopter charts, stacked (docs/VFR_CHARTS.md)",
               "attribution": "FAA", "bounds": f"{w},{s},{e},{n}", "minzoom": cov["minLevel"], "maxzoom": cov["maxLevel"],
               "tilesize": TILE})
    report["counts"] = {str(z): counts[z] for z in sorted(counts)}
    report["empty"] = {str(z): len(v) for z, v in sorted(empty.items())}
    report["size_bytes"] = os.path.getsize(os.path.join(out, "vfr.mbtiles"))
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)
    return report


def main(argv=None) -> int:
    """``python -m charts.build <zips> <out> <chart id> …``: build the charts named into ``out`` (C0's prototype of §5.3)."""
    import argparse

    ap = argparse.ArgumentParser(description=main.__doc__)
    ap.add_argument("zips", help="a folder holding the FAA's zips, as downloaded")
    ap.add_argument("out", help="where the build goes: vfr.mbtiles, coverage.json, report.json, work/")
    ap.add_argument("ids", nargs="+", help="the charts to build")
    ap.add_argument("--processes", type=int, default=2)
    ap.add_argument("--build", dest="label", help="the build's name, by default <cycle>.1")
    a = ap.parse_args(argv)
    report = build(a.zips, a.out, a.ids, a.processes, a.label)
    print(json.dumps({k: report[k] for k in ("times", "counts", "empty", "size_bytes")}, indent=1))
    for line in report["validate"]["notes"]:
        print("  ", line)
    for line in report["validate"]["refused"]:
        print("REFUSED", line)
    return 1 if report["validate"]["refused"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
