"""Serving the VFR chart tiles (docs/VFR_CHARTS.md §7), and what ``/api/config`` says of them (§4.5, §4.6).

- **Which builds.** Any build ``state.json`` lists is served, not only the live one: a client holding an older config still draws,
  and the Data tab shows a build before it is switched to. A name it does not list, one that is not a build's name, a server with no
  ``CHART_TILES_DIR`` and a build whose files will not open are all ``Gone``: a 404 no cache keeps (``no-store``), logged where it is
  the server's fault. Nothing from the URL reaches a path but a name ``state.check_name`` let through.
- **Every tile a client may ask for is answered** (§4.5): a stored tile; past level 11 a tile that is not stored, from its nearest stored
  ancestor, cropped and enlarged with Lanczos (§4.1: the build composites every chart shown at a level under each tile it stores, so
  below a TAC's or a helicopter chart's edge the sectional's level-11 tile is the one to enlarge); and ground with nothing to draw, a
  shared transparent tile. Outside the build's levels or ``bounds`` it is ``OutOfRange``.
- **One read-only connection per build** (``mode=ro&immutable=1``: a build never changes under its name), in one registry for the
  process, used under a lock per build (a thread's connection cannot be closed from another). When ``state.json`` changes, connections
  to builds it no longer lists are closed; so is one not used for 10 minutes, so a deleted build's space comes back (staging never
  deletes, and would otherwise hold them open).

Pillow only: ``charts.build`` needs rasterio, which a server that only serves never imports. Its tile size, WebP quality and level 11
are repeated here, and a test holds them to the build's.
"""
from __future__ import annotations

import io
import json
import logging
import math
import sqlite3
import threading
import time
from collections import OrderedDict
from datetime import date, datetime
from pathlib import Path

from PIL import Image

from . import state

log = logging.getLogger(__name__)

TILE = 512                # as charts.build.TILE
QUALITY = 90              # as charts.build.QUALITY
TOP = 11                  # as charts.build.TOP: the sectionals' top level, under every finer tier
IDLE_S = 600              # a build's connection not used this long is closed
SWEEP_S = 60              # how often the registry looks for those
ENLARGED_KEPT = 400       # enlarged tiles kept in memory (Cloudflare keeps the rest)
MBTILES = "vfr.mbtiles"
COVERAGE = "coverage.json"
BOUNDS_SLACK = 1e-5       # coverage.json's bounds are rounded to five decimals

# Where ArcGIS's VFR service has charts and a CONUS build does not (§4.6), as level-12 tiles of its 256-px Web Mercator grid:
# (name, first column, last column, first row, last row), rows from the north. Measured on 2026-11-06 (UTC) from its tilemap, each edge
# tile fetched (contracts/scripts/arcgis_vfr.py, contracts/fixtures/charts/arcgis_vfr.json, which a test holds this to). Alaska is
# two, split at the antimeridian: the Western Aleutians reach 180° from the west.
FALLBACK_LEVEL = 12
FALLBACK_TILES = (
    ("alaska", 0, 583, 833, 1375),
    ("western-aleutians", 3968, 4095, 1328, 1375),
    ("hawaii", 216, 295, 1768, 1839),
    ("marianas", 3680, 3711, 1864, 1903),
    ("american-samoa", 80, 127, 2192, 2217),
)


class Gone(Exception):
    """A build that is not served: not listed, not a build's name, no chart folder, or files that will not open."""


class OutOfRange(Exception):
    """A tile outside a listed build's levels or bounds."""


def _lon(x: float, z: int) -> float:
    return x / (1 << z) * 360 - 180


def _lat(y: float, z: int) -> float:
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))


def tms_row(z: int, y: int) -> int:
    """MBTiles numbers rows from the south; the URL, from the north."""
    return (1 << z) - 1 - y


def fallback_regions() -> list[tuple[str, list[float]]]:
    """The table's rectangles, ``[west, south, east, north]`` in degrees, rounded outward to six decimals."""
    out = []
    for name, x0, x1, y0, y1 in FALLBACK_TILES:
        z = FALLBACK_LEVEL
        out.append((name, [math.floor(_lon(x0, z) * 1e6) / 1e6, math.floor(_lat(y1 + 1, z) * 1e6) / 1e6,
                           math.ceil(_lon(x1 + 1, z) * 1e6) / 1e6, math.ceil(_lat(y0, z) * 1e6) / 1e6]))
    return out


def _overlaps(a, b) -> bool:
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def fallback(boxes) -> list[list[float]]:
    """Where the clients draw ArcGIS: each rectangle of the table that no chart of the build reaches (``boxes``, each chart's
    outline's ``[west, south, east, north]``). A region added to the build later leaves the list by itself."""
    return [box for _, box in fallback_regions() if not any(_overlaps(box, b) for b in boxes)]


def _transparent() -> bytes:
    buf = io.BytesIO()
    Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0)).save(buf, "WEBP", lossless=True)
    return buf.getvalue()


TRANSPARENT = _transparent()


def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


class _Build:
    """One build's open archive and what its ``coverage.json`` says of it."""

    def __init__(self, where: Path, name: str):
        folder = state.build_dir(where, name)
        self.name = name
        self.lock = threading.Lock()
        self.closed = False
        self.used = 0.0
        try:
            self.coverage = (folder / COVERAGE).read_bytes()
            cov = json.loads(self.coverage)
            self.min, self.max, self.bounds = cov["minLevel"], cov["maxLevel"], cov["bounds"]
            if not (isinstance(self.min, int) and isinstance(self.max, int) and 0 <= self.min <= self.max <= 22
                    and isinstance(self.bounds, list) and len(self.bounds) == 4 and all(map(_number, self.bounds))):
                raise ValueError("levels or bounds")
            self.cycle = date.fromisoformat(cov["cycle"]).isoformat()
            self.effective, self.expires = state.instant(cov["effective"]), state.instant(cov["expires"])
            if self.effective is None or self.expires is None:
                raise ValueError("effective or expires")
            self.boxes = [_box(c["outline"]) for c in cov["charts"]]
        except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
            raise Gone(f"{name}: coverage.json ({error.__class__.__name__})") from None
        try:
            self.db = sqlite3.connect(f"{(folder / MBTILES).resolve().as_uri()}?mode=ro&immutable=1", uri=True,
                                      check_same_thread=False)
            self.db.execute("SELECT 1 FROM tiles LIMIT 1").fetchall()
        except sqlite3.Error as error:
            raise Gone(f"{name}: {MBTILES} ({error.__class__.__name__})") from None

    def stored(self, z: int, x: int, y: int) -> bytes | None:
        row = self.db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                              (z, x, tms_row(z, y))).fetchone()
        return bytes(row[0]) if row else None

    def close(self) -> None:
        with self.lock:
            if not self.closed:
                self.closed = True
                self.db.close()


def _box(outline: dict) -> list[float]:
    ring = outline["coordinates"][0]
    lons, lats = [p[0] for p in ring], [p[1] for p in ring]
    if not ring or not all(map(_number, lons + lats)):
        raise ValueError("outline")
    return [min(lons), min(lats), max(lons), max(lats)]


class Registry:
    """Every build's open archive in the process, by chart folder and name."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._open: dict[tuple[str, str], _Build] = {}
        self._seen: dict[str, object] = {}      # the state.current object each folder's connections were last checked against
        self._swept = clock()
        self._enlarged: OrderedDict = OrderedDict()
        self._enlarged_lock = threading.Lock()

    def open_names(self, where: Path) -> set[str]:
        with self._lock:
            return {name for (w, name) in self._open if w == str(where)}

    def _sync(self, where: Path) -> set[str]:
        """The builds ``state.json`` lists, closing the connections to any it no longer does."""
        try:
            listed = state.current(where)
        except state.StateError as error:
            log.warning("charts: %s", error)
            listed = state.empty()
        names = set(listed["builds"])
        gone = []
        with self._lock:
            if self._seen.get(str(where)) is not listed:
                self._seen[str(where)] = listed
                for key in [k for k in self._open if k[0] == str(where) and k[1] not in names]:
                    gone.append(self._open.pop(key))
        for build in gone:
            build.close()
        return names

    def _sweep(self) -> None:
        now = self.clock()
        idle = []
        with self._lock:
            if now - self._swept < SWEEP_S:
                return
            self._swept = now
            for key in [k for k, b in self._open.items() if now - b.used >= IDLE_S]:
                idle.append(self._open.pop(key))
        for build in idle:
            build.close()

    def build(self, where: Path | None, name: str) -> _Build:
        if where is None:
            raise Gone("no chart folder")
        try:
            state.check_name(name)
        except ValueError:
            raise Gone("not a build's name") from None
        self._sweep()
        if name not in self._sync(where):
            raise Gone(f"{name}: not listed")
        key = (str(where), name)
        with self._lock:
            found = self._open.get(key)
            if found is None or found.closed:
                try:
                    found = _Build(where, name)
                except Gone as error:
                    log.warning("charts: %s", error)
                    raise
                self._open[key] = found
            found.used = self.clock()
            return found

    def _read(self, where, name, read):
        """``read(build)`` under the build's lock, opening it again if it was closed meanwhile (idle, or by a change of state)."""
        for _ in range(2):
            found = self.build(where, name)
            with found.lock:
                if not found.closed:
                    try:
                        return read(found)
                    except sqlite3.Error as error:
                        log.warning("charts: %s: %s", name, error.__class__.__name__)
                        raise Gone(f"{name}: {MBTILES}") from None
        raise Gone(f"{name}: closed while read")

    def coverage(self, where: Path | None, name: str) -> bytes:
        return self.build(where, name).coverage

    def tile(self, where: Path | None, name: str, z: int, x: int, y: int) -> bytes:
        """A tile of a listed build: stored, enlarged from its nearest stored ancestor, or transparent."""
        found = self.build(where, name)
        if not (found.min <= z <= found.max and 0 <= x < 1 << z and 0 <= y < 1 << z):
            raise OutOfRange()
        w, s, e, n = found.bounds
        if not _overlaps([_lon(x, z), _lat(y + 1, z), _lon(x + 1, z), _lat(y, z)],
                         [w - BOUNDS_SLACK, s - BOUNDS_SLACK, e + BOUNDS_SLACK, n + BOUNDS_SLACK]):
            raise OutOfRange()
        data = self._read(where, name, lambda b: b.stored(z, x, y))
        if data is not None:
            return data
        if z <= TOP:
            return TRANSPARENT
        key = (str(where), name, z, x, y)
        with self._enlarged_lock:
            if key in self._enlarged:
                self._enlarged.move_to_end(key)
                return self._enlarged[key]
        out = TRANSPARENT
        for up in range(1, z - max(TOP, found.min) + 1):
            parent = self._read(where, name, lambda b: b.stored(z - up, x >> up, y >> up))
            if parent is not None:
                out = enlarge(parent, up, x, y)
                break
        with self._enlarged_lock:
            self._enlarged[key] = out
            while len(self._enlarged) > ENLARGED_KEPT:
                self._enlarged.popitem(last=False)
        return out

    def close_all(self) -> None:
        with self._lock:
            builds = list(self._open.values())
            self._open.clear()
            self._seen.clear()
        for build in builds:
            build.close()
        with self._enlarged_lock:
            self._enlarged.clear()


def enlarge(data: bytes, up: int, x: int, y: int) -> bytes:
    """The part of an ancestor ``up`` levels above that tile ``(x, y)`` covers, enlarged to a whole tile with Lanczos (§4.1), as WebP
    at the build's quality: RGB where it is opaque, the transparent tile where nothing is drawn."""
    scale = 1 << up
    side = TILE / scale
    left, top = (x % scale) * side, (y % scale) * side
    with Image.open(io.BytesIO(data)) as im:
        im = im.convert("RGBA") if im.mode not in ("RGB", "RGBA") else im
        out = im.resize((TILE, TILE), Image.Resampling.LANCZOS, box=(left, top, left + side, top + side))
    if out.mode == "RGBA":
        alpha = out.getchannel("A")
        lo, hi = alpha.getextrema()
        if hi == 0:
            return TRANSPARENT
        if lo == 255:
            out = out.convert("RGB")
    buf = io.BytesIO()
    out.save(buf, "WEBP", quality=QUALITY, method=4)
    return buf.getvalue()


REGISTRY = Registry()


def config(where: Path | None, now: datetime | None = None, registry: Registry = REGISTRY) -> dict | None:
    """``/api/config``'s ``charts`` (§4.5): the live build, as ``effective`` says at ``now``, or None where nothing is live (the
    clients then draw ArcGIS everywhere, §4.6)."""
    if where is None:
        return None
    try:
        held = state.serving(where, now)
    except state.StateError as error:
        log.warning("charts: %s", error)
        return None
    live = held.get("live")
    if not live or not isinstance(held["builds"].get(live), dict):
        return None
    try:
        found = registry.build(where, live)
    except Gone:
        return None
    return {"vfr": {
        "build": live,
        "cycle": found.cycle,
        "tiles": f"/charts/vfr/{live}/{{z}}/{{x}}/{{y}}.webp",
        "tileSize": TILE,
        "minZoom": found.min,
        "maxZoom": found.max,
        "effective": state.iso(found.effective),
        "expires": state.iso(found.expires),
        "coverage": f"/charts/vfr/{live}/{COVERAGE}",
        "bounds": list(found.bounds),
        "fallback": fallback(found.boxes),
    }}

