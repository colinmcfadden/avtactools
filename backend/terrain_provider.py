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
UH60_LIMITS_DEG = {"noseHigh": 6.0, "noseLow": 15.0, "crossSlope": 15.0}


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
        self._refreshed_at = 0.0

    def _roots(self):
        raw = os.environ.get("TERRAIN_DATA_DIR", "")
        return [root for root in raw.split(os.pathsep) if root and os.path.isdir(root)]

    def entries(self):
        refresh_after = _env_float("TERRAIN_CATALOG_REFRESH_SECONDS", 300.0)
        if time.monotonic() - self._refreshed_at < refresh_after:
            return self._entries

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


def _directional_summary(mask, grad_east, grad_north, heading_deg: float):
    heading_rad = math.radians(heading_deg % 360.0)
    forward = grad_east * math.sin(heading_rad) + grad_north * math.cos(heading_rad)
    right = grad_east * math.cos(heading_rad) - grad_north * math.sin(heading_rad)
    along_deg = np.degrees(np.arctan(forward))[mask]
    cross_deg = np.abs(np.degrees(np.arctan(right))[mask])
    nose_high = np.maximum(along_deg, 0.0)
    nose_low = np.maximum(-along_deg, 0.0)
    return {
        "headingDeg": round(heading_deg % 360.0, 1),
        "noseHighMaxDeg": round(float(nose_high.max()), 1),
        "noseLowMaxDeg": round(float(nose_low.max()), 1),
        "crossSlopeMaxDeg": round(float(cross_deg.max()), 1),
        "noseHighOverLimitPct": _pct(nose_high, nose_high > UH60_LIMITS_DEG["noseHigh"]),
        "noseLowOverLimitPct": _pct(nose_low, nose_low > UH60_LIMITS_DEG["noseLow"]),
        "crossSlopeOverLimitPct": _pct(cross_deg, cross_deg > UH60_LIMITS_DEG["crossSlope"]),
    }


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
        "thresholds": {"bands": SLOPE_BANDS_DEG, "uh60": UH60_LIMITS_DEG},
    }
