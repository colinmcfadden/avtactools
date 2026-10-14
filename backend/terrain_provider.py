"""Shared terrain sources and raster slope analysis.

The mission application treats terrain as server-side data.  Approved local
rasters are never exposed as files to the browser; only a clipped visual
overlay and analysis metadata leave this module.

Provider order in ``auto`` mode is:
    local high-resolution GeoTIFF/COG -> local DTED2 -> Terrarium fallback

Configure one or more local data roots with ``TERRAIN_DATA_DIR`` (paths are
separated using the platform path separator).  Do not place controlled terrain
data inside the source repository or Docker build context; mount it at runtime.
"""

from __future__ import annotations

import base64
import math
import os
import threading
import time
from contextlib import ExitStack
from dataclasses import dataclass
from functools import lru_cache
from concurrent.futures import ThreadPoolExecutor
from typing import Iterable

import cv2
import mercantile
import numpy as np
import requests
import rasterio
from rasterio.crs import CRS
from rasterio.features import rasterize
from rasterio.merge import merge
from rasterio.transform import Affine, array_bounds, from_origin
from rasterio.vrt import WarpedVRT
from rasterio.warp import Resampling, transform, transform_bounds


TERRARIUM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
REQUEST_HEADERS = {"User-Agent": "AvTacTools terrain service"}
WEB_MERCATOR_HALF_WORLD_M = 20037508.342789244

# General map-band thresholds.  Directional UH-60 checks are calculated only
# when the caller supplies a landing heading.
SLOPE_BANDS_DEG = (3.0, 6.0, 10.0, 15.0)
# The UH-60's slope landing limits, as the owner gives them (2026-10-13): 15° nose up (the ground rising ahead, "nose high"),
# 6° nose down ("nose low"), 15° of cross slope. Calm-wind limits: the clients reduce them for the wind (AGENTS.md §13).
UH60_LIMITS_DEG = {"noseHigh": 15.0, "noseLow": 6.0, "crossSlope": 15.0}


@dataclass(frozen=True)
class TerrainGrid:
    """A single-band elevation grid with a metric affine transform."""

    elevation_m: np.ndarray
    transform: Affine
    crs: CRS
    bounds_latlon: tuple[float, float, float, float]  # south, west, north, east
    source: str
    resolution_m: float
    xres_m: float
    yres_m: float
    vertical_datum: str


@dataclass(frozen=True)
class RasterEntry:
    path: str
    kind: str
    bounds_latlon: tuple[float, float, float, float]
    resolution_m: float
    vertical_datum: str


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def bounds_for_polygon(polygon: Iterable[Iterable[float]], padding_m: float | None = None):
    """Return a padded (south, west, north, east) extent around [lat, lon] points."""
    points = [(float(p[0]), float(p[1])) for p in polygon]
    if len(points) < 3:
        raise ValueError("A terrain analysis polygon needs at least three points")

    south = min(p[0] for p in points)
    north = max(p[0] for p in points)
    west = min(p[1] for p in points)
    east = max(p[1] for p in points)
    center_lat = (south + north) / 2.0
    padding_m = padding_m if padding_m is not None else _env_float("TERRAIN_ANALYSIS_PADDING_M", 40.0)
    lat_pad = padding_m / 111320.0
    lon_pad = padding_m / max(1.0, 111320.0 * math.cos(math.radians(center_lat)))
    return south - lat_pad, west - lon_pad, north + lat_pad, east + lon_pad


def _decode_terrarium(png_bytes: bytes) -> np.ndarray | None:
    """Decode Terrarium RGB to elevation metres."""
    arr = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
    if arr is None:
        return None
    b = arr[:, :, 0].astype(np.float32)
    g = arr[:, :, 1].astype(np.float32)
    r = arr[:, :, 2].astype(np.float32)
    return (r * 256.0 + g + b / 256.0) - 32768.0


@lru_cache(maxsize=256)
def _terrarium_tile(z: int, x: int, y: int) -> np.ndarray | None:
    """Fetch and cache one immutable Terrarium tile."""
    try:
        response = requests.get(
            TERRARIUM_URL.format(z=z, x=x, y=y), headers=REQUEST_HEADERS, timeout=15
        )
        if response.status_code != 200:
            return None
        dem = _decode_terrarium(response.content)
        if dem is not None:
            dem.setflags(write=False)
        return dem
    except requests.exceptions.RequestException:
        return None


def terrarium_zoom_for_radius(lat: float, radius_m: float, target_radius_px: int = 800) -> int:
    """Choose the threat-mask zoom without sampling beyond available source detail."""
    span = 156543.03 * math.cos(math.radians(lat))
    zoom = math.floor(math.log2(target_radius_px * span / radius_m)) if radius_m > 0 else 12
    return int(max(9, min(13, zoom)))


def load_terrarium_bounds(bounds_latlon, zoom: int = 13) -> TerrainGrid | None:
    """Mosaic Terrarium tiles covering a small geographic extent."""
    south, west, north, east = bounds_latlon
    tiles = list(mercantile.tiles(west, south, east, north, zoom))
    if not tiles:
        return None

    min_x, max_x = min(t.x for t in tiles), max(t.x for t in tiles)
    min_y, max_y = min(t.y for t in tiles), max(t.y for t in tiles)
    rows, cols = (max_y - min_y + 1) * 256, (max_x - min_x + 1) * 256
    dem = np.full((rows, cols), np.nan, dtype=np.float32)

    def fetch(tile):
        return tile, _terrarium_tile(zoom, tile.x, tile.y)

    with ThreadPoolExecutor(max_workers=8) as pool:
        for tile, tile_dem in pool.map(fetch, tiles):
            if tile_dem is None:
                continue
            r0, c0 = (tile.y - min_y) * 256, (tile.x - min_x) * 256
            dem[r0:r0 + 256, c0:c0 + 256] = tile_dem

    if not np.isfinite(dem).any():
        return None

    pixel_m_mercator = (2 * WEB_MERCATOR_HALF_WORLD_M) / (256 * (2**zoom))
    affine = from_origin(
        -WEB_MERCATOR_HALF_WORLD_M + min_x * 256 * pixel_m_mercator,
        WEB_MERCATOR_HALF_WORLD_M - min_y * 256 * pixel_m_mercator,
        pixel_m_mercator,
        pixel_m_mercator,
    )
    nw = mercantile.bounds(mercantile.Tile(min_x, min_y, zoom))
    se = mercantile.bounds(mercantile.Tile(max_x, max_y, zoom))
    center_lat = (nw.north + se.south) / 2.0
    ground_mpp = pixel_m_mercator * math.cos(math.radians(center_lat))
    return TerrainGrid(
        elevation_m=dem,
        transform=affine,
        crs=CRS.from_epsg(3857),
        bounds_latlon=(se.south, nw.west, nw.north, se.east),
        source="terrarium",
        resolution_m=ground_mpp,
        xres_m=ground_mpp,
        yres_m=ground_mpp,
        vertical_datum="source-dependent Terrarium DEM",
    )


def load_terrarium_radius(lat: float, lon: float, radius_m: float):
    """Threat-compatible Terrarium mosaic and metadata."""
    dlat = radius_m / 111320.0
    dlon = radius_m / max(1.0, 111320.0 * math.cos(math.radians(lat)))
    grid = load_terrarium_bounds(
        (lat - dlat, lon - dlon, lat + dlat, lon + dlon),
        terrarium_zoom_for_radius(lat, radius_m),
    )
    if grid is None:
        return None, None

    x, y = transform("EPSG:4326", grid.crs, [lon], [lat])
    radar_col, radar_row = ~grid.transform * (x[0], y[0])
    south, west, north, east = grid.bounds_latlon
    return grid.elevation_m, {
        "z": terrarium_zoom_for_radius(lat, radius_m),
        "mpp": grid.resolution_m,
        "radar_row": radar_row,
        "radar_col": radar_col,
        "north": north,
        "west": west,
        "south": south,
        "east": east,
    }


def _utm_crs(lat: float, lon: float) -> CRS:
    zone = int((lon + 180.0) / 6.0) + 1
    return CRS.from_epsg((32600 if lat >= 0 else 32700) + zone)


def _raster_resolution_m(dataset, bounds_latlon) -> float:
    xres, yres = abs(dataset.transform.a), abs(dataset.transform.e)
    if dataset.crs and dataset.crs.is_geographic:
        center_lat = (bounds_latlon[0] + bounds_latlon[2]) / 2.0
        return max(yres * 111320.0, xres * 111320.0 * math.cos(math.radians(center_lat)))
    return max(xres, yres)


def _entry_kind(path: str) -> str | None:
    suffix = os.path.splitext(path)[1].lower()
    if suffix == ".dt2":
        return "dted2"
    if suffix in {".tif", ".tiff", ".cog"}:
        return "cog"
    return None


class LocalRasterCatalog:
    """A small in-memory catalog of approved, mounted local terrain rasters."""

    def __init__(self):
        self._entries: list[RasterEntry] = []
        # Never scanned. Not 0.0: time.monotonic() counts from boot on Linux, so on a machine up for less than the refresh
        # interval (a fresh container host, a CI runner) a zero looked like a scan moments ago and every DEM went unseen.
        self._refreshed_at = float("-inf")
        # Requests run on several threads; one rescan of every DEM is enough.
        self._refresh_lock = threading.Lock()

    def _roots(self):
        raw = os.environ.get("TERRAIN_DATA_DIR", "")
        return [root for root in raw.split(os.pathsep) if root and os.path.isdir(root)]

    def entries(self):
        refresh_after = _env_float("TERRAIN_CATALOG_REFRESH_SECONDS", 300.0)
        if time.monotonic() - self._refreshed_at < refresh_after:
            return self._entries
        with self._refresh_lock:
            # Another thread may have refreshed while this one waited.
            if time.monotonic() - self._refreshed_at < refresh_after:
                return self._entries
            return self._refresh()

    def _refresh(self):
        entries: list[RasterEntry] = []
        for root in self._roots():
            for directory, _, files in os.walk(root):
                for name in files:
                    path = os.path.join(directory, name)
                    kind = _entry_kind(path)
                    if kind is None:
                        continue
                    try:
                        with rasterio.open(path) as dataset:
                            if dataset.crs is None:
                                continue
                            west, south, east, north = transform_bounds(
                                dataset.crs, "EPSG:4326", *dataset.bounds, densify_pts=21
                            )
                            bounds = (south, west, north, east)
                            tags = dataset.tags()
                            vertical_datum = tags.get("VERT_DATUM") or tags.get("vertical_datum")
                            if not vertical_datum:
                                vertical_datum = "EGM96 (DTED)" if kind == "dted2" else "source metadata required"
                            entries.append(
                                RasterEntry(
                                    path=path,
                                    kind=kind,
                                    bounds_latlon=bounds,
                                    resolution_m=_raster_resolution_m(dataset, bounds),
                                    vertical_datum=vertical_datum,
                                )
                            )
                    except (rasterio.errors.RasterioError, OSError, ValueError):
                        continue
        self._entries = entries
        self._refreshed_at = time.monotonic()
        return entries

    @staticmethod
    def _intersects(entry: RasterEntry, requested) -> bool:
        south, west, north, east = requested
        es, ew, en, ee = entry.bounds_latlon
        return not (ee < west or ew > east or en < south or es > north)

    def _load_mosaic(self, entries: list[RasterEntry], requested, source: str) -> TerrainGrid | None:
        if not entries:
            return None
        south, west, north, east = requested
        target_crs = _utm_crs((south + north) / 2.0, (west + east) / 2.0)
        target_bounds = transform_bounds("EPSG:4326", target_crs, west, south, east, north, densify_pts=21)
        min_resolution = _env_float("TERRAIN_LOCAL_MIN_RESOLUTION_M", 1.0)
        resolution = max(min_resolution, min(entry.resolution_m for entry in entries))
        width_m, height_m = target_bounds[2] - target_bounds[0], target_bounds[3] - target_bounds[1]
        max_pixels = _env_int("TERRAIN_MAX_GRID_SIZE", 1024)
        resolution = max(resolution, width_m / max_pixels, height_m / max_pixels)

        with ExitStack() as stack:
            sources = []
            for entry in entries:
                try:
                    dataset = stack.enter_context(rasterio.open(entry.path))
                    sources.append(
                        stack.enter_context(
                            WarpedVRT(dataset, crs=target_crs, resampling=Resampling.bilinear)
                        )
                    )
                except (rasterio.errors.RasterioError, OSError, ValueError):
                    continue
            if not sources:
                return None
            mosaic, affine = merge(
                sources,
                bounds=target_bounds,
                res=resolution,
                indexes=1,
                nodata=np.nan,
                resampling=Resampling.bilinear,
            )

        elevation_m = np.asarray(mosaic[0], dtype=np.float32)
        coverage = np.isfinite(elevation_m).mean()
        if coverage < 0.99:
            return None

        out_west, out_south, out_east, out_north = array_bounds(
            elevation_m.shape[0], elevation_m.shape[1], affine
        )
        ll_west, ll_south, ll_east, ll_north = transform_bounds(
            target_crs, "EPSG:4326", out_west, out_south, out_east, out_north, densify_pts=21
        )
        datums = {entry.vertical_datum for entry in entries}
        return TerrainGrid(
            elevation_m=elevation_m,
            transform=affine,
            crs=target_crs,
            bounds_latlon=(ll_south, ll_west, ll_north, ll_east),
            source=source,
            resolution_m=max(abs(affine.a), abs(affine.e)),
            xres_m=abs(affine.a),
            yres_m=abs(affine.e),
            vertical_datum=datums.pop() if len(datums) == 1 else "mixed local source metadata",
        )

    def load_best(self, requested) -> TerrainGrid | None:
        all_entries = self.entries()
        highres_max = _env_float("TERRAIN_LOCAL_HIGHRES_MAX_M", 15.0)
        highres = [
            entry for entry in all_entries
            if entry.kind == "cog" and entry.resolution_m <= highres_max and self._intersects(entry, requested)
        ]
        grid = self._load_mosaic(highres, requested, "local_highres_cog")
        if grid is not None:
            return grid

        dted2 = [
            entry for entry in all_entries
            if entry.kind == "dted2" and self._intersects(entry, requested)
        ]
        return self._load_mosaic(dted2, requested, "local_dted2")


LOCAL_CATALOG = LocalRasterCatalog()


def load_best_terrain(bounds_latlon) -> TerrainGrid | None:
    """Load terrain according to the configured local-first policy."""
    mode = os.environ.get("TERRAIN_SOURCE", "auto").strip().lower()
    if mode not in {"remote", "terrarium"}:
        local = LOCAL_CATALOG.load_best(bounds_latlon)
        if local is not None:
            return local
        if mode == "local":
            return None

    zoom = _env_int("TERRAIN_TERRARIUM_SLOPE_ZOOM", 13)
    return load_terrarium_bounds(bounds_latlon, max(9, min(13, zoom)))


def _horn_gradients(elevation_m: np.ndarray, xres_m: float, yres_m: float):
    """Return slope degrees plus east/north rise-run gradients using Horn 3x3."""
    h, w = elevation_m.shape
    slope = np.full((h, w), np.nan, dtype=np.float32)
    grad_east = np.full((h, w), np.nan, dtype=np.float32)
    grad_north = np.full((h, w), np.nan, dtype=np.float32)
    if h < 3 or w < 3:
        return slope, grad_east, grad_north

    z = elevation_m
    neighbors = np.stack(
        (z[:-2, :-2], z[:-2, 1:-1], z[:-2, 2:], z[1:-1, :-2], z[1:-1, 1:-1],
         z[1:-1, 2:], z[2:, :-2], z[2:, 1:-1], z[2:, 2:])
    )
    valid = np.isfinite(neighbors).all(axis=0)
    dzdx = ((z[:-2, 2:] + 2 * z[1:-1, 2:] + z[2:, 2:]) -
            (z[:-2, :-2] + 2 * z[1:-1, :-2] + z[2:, :-2])) / (8.0 * xres_m)
    # Array rows increase southward, so negate the southward derivative to get north.
    dzdn = -((z[2:, :-2] + 2 * z[2:, 1:-1] + z[2:, 2:]) -
             (z[:-2, :-2] + 2 * z[:-2, 1:-1] + z[:-2, 2:])) / (8.0 * yres_m)
    grad_east[1:-1, 1:-1] = np.where(valid, dzdx, np.nan)
    grad_north[1:-1, 1:-1] = np.where(valid, dzdn, np.nan)
    slope[1:-1, 1:-1] = np.where(
        valid, np.degrees(np.arctan(np.hypot(dzdx, dzdn))), np.nan
    )
    return slope, grad_east, grad_north


def _polygon_mask(grid: TerrainGrid, polygon) -> np.ndarray:
    lats = [float(point[0]) for point in polygon]
    lons = [float(point[1]) for point in polygon]
    xs, ys = transform("EPSG:4326", grid.crs, lons, lats)
    ring = list(zip(xs, ys))
    if ring[0] != ring[-1]:
        ring.append(ring[0])
    geometry = {"type": "Polygon", "coordinates": [ring]}
    return rasterize(
        [(geometry, 1)],
        out_shape=grid.elevation_m.shape,
        transform=grid.transform,
        fill=0,
        all_touched=True,
        dtype=np.uint8,
    ).astype(bool)


def _pct(values: np.ndarray, condition: np.ndarray) -> float:
    return round(float(condition.sum()) / len(values) * 100.0, 1) if len(values) else 0.0


def _along_and_across(grad_east, grad_north, heading_deg: float):
    """Each cell's slope along [heading_deg] (positive where the ground rises ahead: nose high) and across it, in degrees."""
    heading_rad = math.radians(heading_deg % 360.0)
    forward = grad_east * math.sin(heading_rad) + grad_north * math.cos(heading_rad)
    right = grad_east * math.cos(heading_rad) - grad_north * math.sin(heading_rad)
    return np.degrees(np.arctan(forward)), np.abs(np.degrees(np.arctan(right)))


def _steepest(along_deg, cross_deg):
    """The steepest nose high, nose low and cross slope among these cells, in degrees to 0.1 (a zero is 0.0, never -0.0)."""
    nose_high = np.maximum(along_deg, 0.0)
    nose_low = np.maximum(-along_deg, 0.0)
    return tuple(round(float(values.max()), 1) + 0.0 for values in (nose_high, nose_low, cross_deg))


def _directional_summary(mask, grad_east, grad_north, heading_deg: float):
    along, across = _along_and_across(grad_east, grad_north, heading_deg)
    along_deg = along[mask]
    cross_deg = across[mask]
    nose_high = np.maximum(along_deg, 0.0)
    nose_low = np.maximum(-along_deg, 0.0)
    high, low, cross = _steepest(along_deg, cross_deg)
    return {
        "headingDeg": round(heading_deg % 360.0, 1),
        "noseHighMaxDeg": high,
        "noseLowMaxDeg": low,
        "crossSlopeMaxDeg": cross,
        "noseHighOverLimitPct": _pct(nose_high, nose_high > UH60_LIMITS_DEG["noseHigh"]),
        "noseLowOverLimitPct": _pct(nose_low, nose_low > UH60_LIMITS_DEG["noseLow"]),
        "crossSlopeOverLimitPct": _pct(cross_deg, cross_deg > UH60_LIMITS_DEG["crossSlope"]),
    }


# Up to this many cells, the table by heading looks at every one; past it, only at those that can be the steepest (below).
_EVERY_CELL_UP_TO = 256


def _depth_inside(xs, ys, corner_xs, corner_ys):
    """How far inside the convex polygon of the given corners each cell (xs, ys) is (negative outside), or None when it has no inside."""
    if len(corner_xs) < 3:
        return None
    next_xs, next_ys = np.roll(corner_xs, -1), np.roll(corner_ys, -1)
    dxs, dys = next_xs - corner_xs, next_ys - corner_ys
    lengths = np.hypot(dxs, dys)
    twice_area = float(np.sum(corner_xs * next_ys - next_xs * corner_ys))
    if twice_area == 0.0 or not np.all(lengths > 0.0):
        return None
    inward = math.copysign(1.0, twice_area)                                # the inside is left of each edge when the corners turn anticlockwise
    depth = np.full(len(xs), np.inf)
    for x0, y0, dx, dy, length in zip(corner_xs, corner_ys, dxs, dys, lengths):
        nx, ny = -dy * inward / length, dx * inward / length               # the edge's unit normal, pointing inside
        np.minimum(depth, nx * xs + ny * ys - (nx * x0 + ny * y0), out=depth)
    return depth


def _hull(xs, ys):
    """The convex hull's corners, as indices into the cells."""
    return cv2.convexHull(np.column_stack((xs, ys)).astype(np.float32), returnPoints=False).ravel()


def _steepest_candidates(east, north):
    """The cells, by their gradients, that can be the steepest in some direction: all of them that matter to the table by heading.

    A slope along (or across) a heading is a linear function of a cell's gradient, and a linear function over a set of points is greatest
    at a corner of their convex hull. So only cells on the hull's edge, or within a float's rounding of it, can be a heading's steepest, and
    360 headings are worked out over a few dozen cells instead of every cell of a large LZ, with the same answer: each cell's numbers are
    the same whichever others are beside it, and the one that is greatest is always kept."""
    if len(east) <= _EVERY_CELL_UP_TO:
        return east, north
    xs, ys = east.astype(np.float64), north.astype(np.float64)            # float32 values, exact in float64
    # How far inside the hull a cell may be and still tie with its edge once the float32 products, sums and arctangents have rounded:
    # about 1e-7 of the steepest gradient. A hundred times that costs nothing (a few more cells) and leaves no doubt.
    margin = 1e-5 * float(np.max(np.hypot(xs, ys)))
    # First a polygon of the cells furthest out in sixteen directions. It lies inside the hull, so a cell deeper inside it than the margin is
    # deeper inside the hull too and goes now; the hull's own corners are never among those, so the hull is then found among what is left.
    furthest = np.unique([int(np.argmax(math.cos(a) * xs + math.sin(a) * ys)) for a in np.arange(16) * (math.pi / 8.0)])
    inner = _hull(xs[furthest], ys[furthest])
    depth = _depth_inside(xs, ys, xs[furthest][inner], ys[furthest][inner])
    if depth is not None:
        near = depth <= margin
        xs, ys, east, north = xs[near], ys[near], east[near], north[near]
    corners = _hull(xs, ys)
    depth = _depth_inside(xs, ys, xs[corners], ys[corners])
    if depth is None:
        # Every gradient on one line (a plane, or ground that tilts one way only): there is no inside to leave out, but cells with the same
        # gradient have the same numbers, so one of each is enough.
        unique = np.unique(np.column_stack((east, north)), axis=0)
        return unique[:, 0], unique[:, 1]
    keep = depth <= margin
    return east[keep], north[keep]


def _slope_by_heading(mask, grad_east, grad_north):
    """The steepest nose high, nose low and cross slope over the LZ for every whole heading 0..359, by _directional_summary's arithmetic,
    so a client can judge any landing heading, and recommend one, without asking again. The headings are the grid's: true north for the
    Terrarium tiles (Web Mercator), the UTM grid's north for a local DEM."""
    east, north = _steepest_candidates(grad_east[mask], grad_north[mask])
    table = []
    for heading in range(360):
        high, low, cross = _steepest(*_along_and_across(east, north, heading))
        table.append({"heading": heading, "noseHighMaxDeg": high, "noseLowMaxDeg": low, "crossSlopeMaxDeg": cross})
    return table


def _render_slope_png(slope_deg: np.ndarray, display_mask: np.ndarray) -> str:
    """Encode a transparent banded slope raster (OpenCV BGRA order)."""
    image = np.zeros((*slope_deg.shape, 4), dtype=np.uint8)
    # B, G, R, alpha: <3, <6, <10, <15, >=15 degrees.
    bands = (
        (slope_deg < 3.0, (34, 197, 94, 145)),
        ((slope_deg >= 3.0) & (slope_deg < 6.0), (235, 99, 59, 150)),
        ((slope_deg >= 6.0) & (slope_deg < 10.0), (56, 189, 248, 160)),
        ((slope_deg >= 10.0) & (slope_deg < 15.0), (22, 119, 255, 170)),
        (slope_deg >= 15.0, (68, 68, 239, 185)),
    )
    for condition, color in bands:
        image[display_mask & condition] = color
    ok, encoded = cv2.imencode(".png", image)
    if not ok:
        raise RuntimeError("Could not encode the slope overlay")
    return "data:image/png;base64," + base64.b64encode(encoded.tobytes()).decode("ascii")


FEET_PER_METRE = 3.28084
# Contour intervals in feet, finest first: the finest that keeps the ground round an LZ to about a dozen lines is used.
CONTOUR_INTERVALS_FT = (1, 2, 5, 10, 20, 25, 50, 100)
CONTOUR_MAX_LINES = 12
# Every fifth line is a major (index) one, as on a topographic map.
CONTOUR_MAJOR_EVERY = 5
# Contours run this far past the LZ's boundary, so the ground just beyond its edge shows (faded on the clients).
CONTOUR_RING_M = 40.0
# The ground is smoothed over about this much before it is contoured, so a DEM's noise does not draw specks and wiggles: the lines are
# for reading the shape of the ground, and the slope itself is measured from the unsmoothed grid.
CONTOUR_SMOOTHING_M = 2.0
# A line is simplified to within this much of itself (a fraction of a cell), and a closed ring shorter than this many cells round is left out.
CONTOUR_SIMPLIFY_CELLS = 0.35
CONTOUR_MIN_RING_CELLS = 12.0
# Labels keep this far apart (a label is about 40 px across, and an LZ is looked at near a metre a pixel). Each height's goes on its
# longest piece inside the LZ, halfway along if there is room there, else further along it, else none; major lines are placed first.
CONTOUR_LABEL_SPACING_M = 40.0
CONTOUR_LABEL_ALONG = (0.5, 0.35, 0.65, 0.2, 0.8)


def _contour_interval_ft(relief_ft: float) -> int:
    for interval in CONTOUR_INTERVALS_FT:
        if relief_ft / interval <= CONTOUR_MAX_LINES:
            return interval
    return CONTOUR_INTERVALS_FT[-1]


def _inside_ring(xs: np.ndarray, ys: np.ndarray, ring_x: np.ndarray, ring_y: np.ndarray) -> np.ndarray:
    """Whether each point is inside a closed ring (even-odd ray casting, vectorised over the points)."""
    inside = np.zeros(len(xs), dtype=bool)
    x0s, y0s = ring_x, ring_y
    x1s, y1s = np.roll(ring_x, -1), np.roll(ring_y, -1)
    for x0, y0, x1, y1 in zip(x0s, y0s, x1s, y1s):
        if y0 == y1:
            continue
        crosses = (y0 > ys) != (y1 > ys)
        at_x = x0 + (ys - y0) * (x1 - x0) / (y1 - y0)
        inside ^= crosses & (xs < at_x)
    return inside


def _split_by(flags: np.ndarray):
    """Runs of equal flags as (start, end, flag), end exclusive; neighbouring runs share their joining vertex, so they meet."""
    runs = []
    start = 0
    for i in range(1, len(flags) + 1):
        if i == len(flags) or flags[i] != flags[start]:
            runs.append((start, min(i + 1, len(flags)), bool(flags[start])))
            start = i
    return runs


def _slope_contours(grid: TerrainGrid, polygon, polygon_mask: np.ndarray) -> dict:
    """
    Contour lines of the ground in and just round the LZ, in feet: `intervalFt`, and `lines`, each a piece of one contour with its
    `elevationFt`, whether it is a `major` (every fifth) line, whether it lies `inside` the boundary (the clients fade the ring outside it),
    its `points` as [lat, lon], and, on the longest inside piece of each elevation, a `label` point for its number. The heights are the
    source's own (NAVD88 for the local DEMs), as the slope is.
    """
    from contourpy import LineType, contour_generator

    cell_m = max(min(grid.xres_m, grid.yres_m), 0.01)
    reach = max(1, int(math.ceil(CONTOUR_RING_M / cell_m)))
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * reach + 1, 2 * reach + 1))
    zone = cv2.dilate(polygon_mask.astype(np.uint8), kernel).astype(bool) & np.isfinite(grid.elevation_m)
    if not zone.any():
        return {"intervalFt": CONTOUR_INTERVALS_FT[0], "lines": []}

    elevation_ft = grid.elevation_m.astype(np.float64) * FEET_PER_METRE
    sigma = CONTOUR_SMOOTHING_M / cell_m
    if sigma >= 0.3:
        # Smoothed where there is ground only: the gaps (NaN) are weighted out rather than spread into their neighbours.
        known = np.isfinite(elevation_ft)
        weights = cv2.GaussianBlur(known.astype(np.float64), (0, 0), sigma)
        blurred = cv2.GaussianBlur(np.where(known, elevation_ft, 0.0), (0, 0), sigma)
        with np.errstate(invalid="ignore", divide="ignore"):
            elevation_ft = np.where(known, blurred / weights, np.nan)
    inside_values = elevation_ft[polygon_mask & np.isfinite(elevation_ft)]
    relief = elevation_ft[zone]
    interval = _contour_interval_ft(float(relief.max() - relief.min()))
    if inside_values.size == 0:
        return {"intervalFt": interval, "lines": []}
    first = math.ceil(float(relief.min()) / interval) * interval
    last = math.floor(float(relief.max()) / interval) * interval

    masked = np.ma.masked_array(elevation_ft, mask=~zone)
    generator = contour_generator(z=masked, line_type=LineType.Separate)

    lats = [float(point[0]) for point in polygon]
    lons = [float(point[1]) for point in polygon]
    ring_x, ring_y = (np.asarray(v, dtype=np.float64) for v in transform("EPSG:4326", grid.crs, lons, lats))

    def to_metres(path):
        # Pixel centres to the grid's metres: column, row (from the top) through its affine transform.
        cols, rows = path[:, 0] + 0.5, path[:, 1] + 0.5
        t = grid.transform
        return t.c + cols * t.a + rows * t.b, t.f + cols * t.d + rows * t.e

    def to_latlon(xs, ys):
        lon_deg, lat_deg = transform(grid.crs, "EPSG:4326", list(map(float, xs)), list(map(float, ys)))
        return [[round(lat, 6), round(lon, 6)] for lat, lon in zip(lat_deg, lon_deg)]

    lines = []
    longest = []
    level = first
    while level <= last:
        best = None  # (length, index of the line, the piece's dense xs and ys)
        for path in generator.lines(level):
            if len(path) < 2:
                continue
            if np.allclose(path[0], path[-1]) and float(np.hypot(*np.diff(path, axis=0).T).sum()) < CONTOUR_MIN_RING_CELLS:
                continue                                                               # a speck: a ring a few cells round
            xs, ys = to_metres(path)
            inside = _inside_ring(xs, ys, ring_x, ring_y)
            # Split where the line crosses the boundary first, on every vertex, then simplify each piece: simplified first, a straight
            # line across the LZ would keep only its two ends, both outside it.
            for start, end, is_inside in _split_by(inside):
                if end - start < 2:
                    continue
                piece = path[start:end]
                kept = cv2.approxPolyDP(piece.astype(np.float32).reshape(-1, 1, 2), CONTOUR_SIMPLIFY_CELLS, False).reshape(-1, 2)
                if np.array_equal(piece[0], piece[-1]) and not np.array_equal(kept[0], kept[-1]):
                    kept = np.vstack([kept, kept[:1]])                                  # a ring stays closed
                lines.append({
                    "elevationFt": int(level),
                    "major": int(level) % (interval * CONTOUR_MAJOR_EVERY) == 0,
                    "inside": is_inside,
                    "points": to_latlon(*to_metres(kept.astype(np.float64))),
                    "label": None,
                })
                if is_inside:
                    steps = np.hypot(np.diff(xs[start:end]), np.diff(ys[start:end]))
                    length = float(steps.sum())
                    if best is None or length > best[0]:
                        best = (length, len(lines) - 1, xs[start:end], ys[start:end])
        if best is not None:
            longest.append(best)
        level += interval

    placed = []
    for length, index, xs, ys in sorted(longest, key=lambda b: (not lines[b[1]]["major"], -b[0])):
        along = np.concatenate([[0.0], np.cumsum(np.hypot(np.diff(xs), np.diff(ys)))])
        for fraction in CONTOUR_LABEL_ALONG:
            i = min(int(np.searchsorted(along, length * fraction)), len(xs) - 1)
            if all(math.hypot(xs[i] - px, ys[i] - py) >= CONTOUR_LABEL_SPACING_M for px, py in placed):
                placed.append((xs[i], ys[i]))
                lines[index]["label"] = to_latlon(np.array([xs[i]]), np.array([ys[i]]))[0]
                break
    return {"intervalFt": interval, "lines": lines}


# The slope profiles: lines through the LZ's middle reaching this far past its farthest corner, so the ground of the approach and the
# departure shows; one every PROFILE_HEADING_STEP_DEG of heading (a line serves its heading and the opposite one, read backwards), with at
# most PROFILE_MAX_SAMPLES heights along each, no closer than the terrain's own cells.
PROFILE_BEYOND_M = 300.0
PROFILE_HEADING_STEP_DEG = 2
PROFILE_MAX_SAMPLES = 121
METRES_PER_DEGREE = 111320.0


def _local_frame(polygon):
    """The LZ's middle (its area centroid) and a flat east-north frame round it in metres, good to a metre or so over a few kilometres."""
    lats = np.array([float(p[0]) for p in polygon])
    lons = np.array([float(p[1]) for p in polygon])
    lat0, lon0 = float(lats.mean()), float(lons.mean())
    east_per_deg = METRES_PER_DEGREE * math.cos(math.radians(lat0))
    xs, ys = (lons - lon0) * east_per_deg, (lats - lat0) * METRES_PER_DEGREE
    x1, y1 = np.roll(xs, -1), np.roll(ys, -1)
    cross = xs * y1 - x1 * ys
    area = cross.sum() / 2.0
    if abs(area) > 1e-6:
        cx, cy = ((xs + x1) * cross).sum() / (6 * area), ((ys + y1) * cross).sum() / (6 * area)
    else:
        cx, cy = float(xs.mean()), float(ys.mean())
    centre_lat, centre_lon = lat0 + cy / METRES_PER_DEGREE, lon0 + cx / east_per_deg
    return (centre_lat, centre_lon), xs - cx, ys - cy, east_per_deg


def _lz_span(xs, ys, heading_deg):
    """Where a line through the middle at [heading_deg] crosses the boundary: the nearest and farthest crossings, in metres along it."""
    ux, uy = math.sin(math.radians(heading_deg)), math.cos(math.radians(heading_deg))
    found = []
    for i in range(len(xs)):
        ax, ay, bx, by = xs[i], ys[i], xs[(i + 1) % len(xs)], ys[(i + 1) % len(xs)]
        ex, ey = bx - ax, by - ay
        denom = ux * ey - uy * ex
        if abs(denom) < 1e-12:
            continue
        t = (ax * ey - ay * ex) / denom        # along the line
        s = (ax * uy - ay * ux) / denom        # along the edge, 0..1
        if -1e-9 <= s <= 1 + 1e-9:
            found.append(t)
    return [round(float(min(found)), 1), round(float(max(found)), 1)] if len(found) >= 2 else None


def _sample(grid: TerrainGrid, lats, lons):
    """The grid's heights at the points, bilinearly; None where any cell it needs has no data or the point is off the grid."""
    xs, ys = transform("EPSG:4326", grid.crs, list(lons), list(lats))
    inverse = ~grid.transform
    out = []
    rows, cols = grid.elevation_m.shape
    for x, y in zip(xs, ys):
        col, row = inverse * (x, y)
        col, row = col - 0.5, row - 0.5                # from the grid's corner to its cells' centres
        c0, r0 = int(math.floor(col)), int(math.floor(row))
        if c0 < 0 or r0 < 0 or c0 + 1 >= cols or r0 + 1 >= rows:
            out.append(None)
            continue
        fc, fr = col - c0, row - r0
        cell = grid.elevation_m[r0:r0 + 2, c0:c0 + 2].astype(np.float64)
        if not np.isfinite(cell).all():
            out.append(None)
            continue
        top = cell[0, 0] * (1 - fc) + cell[0, 1] * fc
        bottom = cell[1, 0] * (1 - fc) + cell[1, 1] * fc
        out.append(float(top * (1 - fr) + bottom * fr))
    return out


def _slope_profiles(polygon) -> dict | None:
    """
    The ground along lines through the LZ's middle, for the slope profile the clients draw along and across the landing heading: `center`
    ([lat, lon]), `stepM` between heights, `halfLengthM` each way, `headingStepDeg`, and `lines`, one for each heading 0, 2, … 178, each with its
    `elevationsFt` (from `halfLengthM` behind the middle, the way the heading points from, to `halfLengthM` ahead; null where there is no data)
    and `lz`, where the boundary is along it in metres from the middle (behind negative), or null. A heading of 180 or more is the line of the
    opposite one, read backwards. Null when no terrain covers the ground round the LZ.
    """
    centre, xs, ys, east_per_deg = _local_frame(polygon)
    reach = float(np.hypot(xs, ys).max()) + PROFILE_BEYOND_M
    grid = load_best_terrain(bounds_for_polygon(polygon, padding_m=reach + 60.0))
    if grid is None:
        return None
    step = max(grid.resolution_m, 2 * reach / (PROFILE_MAX_SAMPLES - 1))
    count = int(math.ceil(2 * reach / step)) + 1
    half = step * (count - 1) / 2.0
    distances = np.linspace(-half, half, count)
    lines = []
    for heading in range(0, 180, PROFILE_HEADING_STEP_DEG):
        ux, uy = math.sin(math.radians(heading)), math.cos(math.radians(heading))
        lats = centre[0] + distances * uy / METRES_PER_DEGREE
        lons = centre[1] + distances * ux / east_per_deg
        heights = _sample(grid, lats, lons)
        lines.append({
            "heading": heading,
            "elevationsFt": [None if h is None else round(h * FEET_PER_METRE, 1) for h in heights],
            "lz": _lz_span(xs, ys, heading),
        })
    return {
        "center": [round(centre[0], 7), round(centre[1], 7)],
        "stepM": round(step, 3),
        "halfLengthM": round(half, 3),
        "headingStepDeg": PROFILE_HEADING_STEP_DEG,
        "lines": lines,
    }


def build_slope_analysis(polygon, landing_heading_deg: float | None = None) -> dict:
    """Build a clipped continuous slope-overlay response for the frontend."""
    requested = bounds_for_polygon(polygon)
    grid = load_best_terrain(requested)
    if grid is None:
        raise RuntimeError("No configured terrain source covers this LZ")

    xres = max(grid.xres_m, 0.01)
    yres = max(grid.yres_m, 0.01)
    slope_deg, grad_east, grad_north = _horn_gradients(grid.elevation_m, xres, yres)
    polygon_mask = _polygon_mask(grid, polygon)
    display_mask = polygon_mask & np.isfinite(slope_deg)
    values = slope_deg[display_mask]
    if len(values) == 0:
        raise RuntimeError("Terrain source has no usable slope samples inside this LZ")

    stats = {
        "maxDeg": round(float(values.max()), 1),
        "p95Deg": round(float(np.percentile(values, 95)), 1),
        "areaOver6Pct": _pct(values, values > 6.0),
        "areaOver10Pct": _pct(values, values > 10.0),
        "areaOver15Pct": _pct(values, values > 15.0),
        "sampleCount": int(len(values)),
        "sampleAreaM2": round(float(len(values) * xres * yres), 1),
    }
    directional = None
    if landing_heading_deg is not None and math.isfinite(landing_heading_deg):
        directional = _directional_summary(display_mask, grad_east, grad_north, landing_heading_deg)

    south, west, north, east = grid.bounds_latlon
    return {
        "overlay": _render_slope_png(slope_deg, display_mask),
        "bounds": [[south, west], [north, east]],
        "source": grid.source,
        "resolutionM": round(grid.resolution_m, 1),
        "verticalDatum": grid.vertical_datum,
        "stats": stats,
        "directional": directional,
        "byHeading": _slope_by_heading(display_mask, grad_east, grad_north),
        "contours": _slope_contours(grid, polygon, polygon_mask),
        "profiles": _slope_profiles(polygon),
        "thresholds": {"bands": SLOPE_BANDS_DEG, "uh60": UH60_LIMITS_DEG},
    }
