"""Serving terrain heights to the 3D view, sampled from the local DEMs.

Cesium needs elevation per map tile to build a terrain surface. The usual
delivery format is quantized-mesh, which is an adaptive triangulation with its
own encoding and a pre-generation step over the whole coverage area. This
serves plain heightmaps instead — a fixed grid of Int16 metres per tile — which
Cesium consumes through ``CustomHeightmapTerrainProvider``.

That trade is deliberate. Quantized-mesh wins at continental scale, where
adaptive triangulation saves a great deal over a fixed grid. This view is a
single landing zone a few hundred metres across, where a 65x65 grid is already
finer than the 1/3 arc-second source, and the fixed grid costs nothing to
generate, needs no storage, and picks up new DEMs the moment they are mounted.

Heights are metres above the ellipsoid, which is the datum Cesium places
terrain against. USGS 3DEP DEMs are NAVD88 orthometric, so the geoid
separation has to be added — the same correction the LiDAR pipeline applies,
for the same reason: without it the surface sits about 30 m off in Georgia.
"""

from __future__ import annotations

import math
import os
from functools import lru_cache

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.transform import Affine, from_bounds
from rasterio.warp import reproject, transform_bounds
from rasterio.warp import transform as warp_transform
from rasterio.windows import Window
from rasterio.windows import from_bounds as window_from_bounds

from terrain_provider import LOCAL_CATALOG

# Cesium's default terrain tiling scheme covers the globe with two tiles at
# level 0, each 90 degrees tall, and quarters them at every level below.
ROOT_TILES_X = 2
ROOT_TILES_Y = 1

# 65 rather than 64: heightmap grids share their edge samples with the
# neighbouring tile, so a power-of-two-plus-one avoids a seam.
TILE_SAMPLES = 65

# A sanity bound, not a detail limit.
#
# This was 14, on the reasoning that a tile finer than the 1/3 arc-second
# source carries no new detail so there was no point serving one. That is true
# about detail and wrong about mechanism: refusing a tile does not stop Cesium
# subdividing, it just leaves that tile with no elevation. Zooming in far
# enough dropped the surface to the ellipsoid — some 600 m below the real
# ground in north Georgia — and tore a hole in the globe exactly where the
# camera was pointed.
#
# Oversampling costs nothing and stays smooth: a 16 m tile at level 20 samples
# in 16 ms and interpolates the DEM cleanly. The bound below exists only so a
# runaway client cannot ask for arbitrarily deep tiles; Cesium stops long
# before it, because geometric error halves each level.
MAX_LEVEL = 22

WGS84 = "EPSG:4326"
GEOID = "EPSG:5703"


class TerrainTileError(RuntimeError):
    """A terrain tile could not be produced."""


def tile_bounds(level: int, x: int, y: int):
    """Geographic bounds of a tile, as ``(west, south, east, north)`` degrees."""
    if level < 0 or x < 0 or y < 0:
        raise TerrainTileError("negative tile coordinate")
    across = ROOT_TILES_X << level
    down = ROOT_TILES_Y << level
    if x >= across or y >= down:
        raise TerrainTileError(f"tile {level}/{x}/{y} is outside the world")

    width = 360.0 / across
    height = 180.0 / down
    west = -180.0 + x * width
    north = 90.0 - y * height
    return (west, north - height, west + width, north)


@lru_cache(maxsize=1)
def _geoid_transformer():
    """NAVD88 -> ellipsoidal height, or None when the grids are unavailable."""
    from pyproj import Transformer

    return Transformer.from_crs(f"{WGS84}+{GEOID.split(':')[1]}",
                                "EPSG:4979", always_xy=True)


def geoid_offset(lon: float, lat: float, *, transformer=None) -> float:
    """Metres to add to an orthometric height to get an ellipsoidal one.

    Constant across one tile to within a few centimetres — the geoid varies far
    more slowly than a tile is wide — so it is sampled once at the centre
    rather than per grid cell.
    """
    transformer = transformer or _geoid_transformer()
    _lon, _lat, height = transformer.transform(lon, lat, 0.0)
    return height


def _close_gaps(grid: np.ndarray, *, passes: int = 24) -> np.ndarray:
    """Fill cells with no DEM data by spreading the nearest real elevation.

    The obvious fill is sea level, and it is badly wrong. A coarse tile spans
    hundreds of kilometres and our DEMs cover a few states, so most of its
    cells have no data: filling them with zero cut a trough 1200 m deep and
    thousands of cells wide through the middle distance. Cesium cannot skirt a
    seam that size, so the globe tore open and the sky showed through it.

    Spreading the nearest known height keeps the surface continuous instead.
    The result is wrong in the far field — a plateau where there is really
    ocean or another state — but it is smooth, and nothing at that distance is
    being measured. Anything still unreached after the dilation passes takes
    the tile mean, which only happens on tiles with a sliver of coverage.
    """
    filled = grid.copy()
    for _ in range(passes):
        missing = np.isnan(filled)
        if not missing.any():
            return filled
        # Average of whichever of the four neighbours are already known.
        total = np.zeros_like(filled)
        count = np.zeros_like(filled)
        for axis, shift in ((0, 1), (0, -1), (1, 1), (1, -1)):
            neighbour = np.roll(filled, shift, axis=axis)
            known = ~np.isnan(neighbour)
            total[known] += neighbour[known]
            count[known] += 1
        spreadable = missing & (count > 0)
        filled[spreadable] = total[spreadable] / count[spreadable]

    remaining = np.isnan(filled)
    if remaining.any():
        filled[remaining] = np.nanmean(grid)
    return filled


# Pixels read per tile sample along each axis. Bilinear resampling onto the
# tile grid wants a little more than one source pixel per sample; much more is
# reading detail the tile then throws away.
OVERSAMPLE = 2


def _read_patch(dataset, bounds, samples: int, dst_transform):
    """One DEM's heights on a tile's grid, read only as finely as the tile needs.

    Reading a DEM's full band to fill a 65x65 grid is what made coarse tiles
    take minutes. A 1/3 arc-second DEM is 10812 pixels square — 468 MB — and a
    level 0 tile touches every DEM mounted, so the first tile Cesium asked for
    meant reading tens of gigabytes. Nothing on the globe loads until that tile
    does, so the surface around the point cloud simply never appeared.

    Instead this reads only the window of the DEM inside the tile, decimated to
    about twice the tile's own resolution. GDAL serves a decimated read from
    the file's overviews (USGS DEMs carry them to 1/32), so a continent-sized
    tile costs a few hundred kilobytes per DEM. Near the landing point, where a
    tile is smaller than the window's pixel count, nothing is decimated at all.

    Returns None when the DEM does not reach into the tile.
    """
    west, south, east, north = bounds
    # Two samples of margin on every side. Bilinear interpolation at the
    # tile's edge needs the source pixels just beyond it; cropping exactly to
    # the tile cut them off and left edge cells up to 2 m out.
    pad_x = (east - west) / (samples - 1) * 2
    pad_y = (north - south) / (samples - 1) * 2
    left, bottom, right, top = transform_bounds(WGS84, dataset.crs,
                                                west - pad_x, south - pad_y,
                                                east + pad_x, north + pad_y,
                                                densify_pts=21)
    window = window_from_bounds(left, bottom, right, top,
                                transform=dataset.transform)

    # Whole pixels, clipped to the DEM.
    col0 = max(0, math.floor(window.col_off))
    row0 = max(0, math.floor(window.row_off))
    col1 = min(dataset.width, math.ceil(window.col_off + window.width))
    row1 = min(dataset.height, math.ceil(window.row_off + window.height))
    if col1 <= col0 or row1 <= row0:
        return None
    width, height = col1 - col0, row1 - row0

    # What share of the tile this window spans, and so how many pixels it
    # needs to contribute.
    span_x = abs(dataset.transform.a) * width / max(right - left, 1e-12)
    span_y = abs(dataset.transform.e) * height / max(top - bottom, 1e-12)
    out_w = max(2, min(width, math.ceil(samples * OVERSAMPLE * span_x)))
    out_h = max(2, min(height, math.ceil(samples * OVERSAMPLE * span_y)))

    read_window = Window(col0, row0, width, height)
    data = dataset.read(1, window=read_window, out_shape=(out_h, out_w),
                        resampling=Resampling.bilinear, masked=True)
    source = np.ma.filled(data.astype(np.float32), np.nan)
    src_transform = (dataset.window_transform(read_window)
                     * Affine.scale(width / out_w, height / out_h))

    patch = np.full((samples, samples), np.nan, dtype=np.float32)
    reproject(
        source=source,
        destination=patch,
        src_transform=src_transform,
        src_crs=dataset.crs,
        src_nodata=np.nan,
        dst_transform=dst_transform,
        dst_crs=WGS84,
        dst_nodata=np.nan,
        resampling=Resampling.bilinear,
    )
    return patch


def _sources_for(bounds_latlon):
    """Local DEM files intersecting a tile, highest resolution first."""
    south, west, north, east = bounds_latlon
    entries = [entry for entry in LOCAL_CATALOG.entries()
               if LOCAL_CATALOG._intersects(entry, (south, west, north, east))]
    return sorted(entries, key=lambda entry: entry.resolution_m)


def sample_tile(level: int, x: int, y: int, *, samples: int = TILE_SAMPLES):
    """A ``samples x samples`` grid of ellipsoidal heights, or None if uncovered.

    None is a normal answer: the DEMs cover part of one country, and Cesium
    asks for tiles across the whole globe. The provider treats an uncovered
    tile as flat rather than an error.
    """
    west, south, east, north = tile_bounds(level, x, y)
    sources = _sources_for((south, west, north, east))
    if not sources:
        return None

    destination = np.full((samples, samples), np.nan, dtype=np.float32)
    transform = from_bounds(west, south, east, north, samples, samples)

    filled = False
    for entry in sources:
        try:
            with rasterio.open(entry.path) as dataset:
                patch = _read_patch(dataset, (west, south, east, north),
                                    samples, transform)
        except (rasterio.errors.RasterioError, OSError, ValueError):
            continue
        if patch is None:
            continue

        # Later sources are coarser, so they only fill what is still missing.
        gaps = np.isnan(destination)
        if gaps.any():
            destination[gaps] = patch[gaps]
            filled = True
        if not np.isnan(destination).any():
            break

    if not filled or np.isnan(destination).all():
        return None

    destination = _close_gaps(destination)

    try:
        destination += geoid_offset((west + east) / 2.0, (south + north) / 2.0)
    except Exception:  # noqa: BLE001 — grids missing; see module docstring
        # Better to serve orthometric heights than nothing, but say so loudly
        # rather than silently placing the surface 30 m out.
        if _geoid_required():
            raise TerrainTileError(
                "geoid grids unavailable; terrain would be ~30 m out. "
                "Install them with projsync or set PROJ_NETWORK=ON."
            )

    return destination


# One request's worth of points. A 40 km route sampled every 30 m is about
# 1,300; this leaves room for several routes without letting one request turn
# into minutes of DEM reads.
MAX_POINTS = 5000


def _geoid_required() -> bool:
    return os.environ.get("TERRAIN_REQUIRE_GEOID", "").strip().lower() in {"1", "true", "yes"}


def _bilinear(dataset, xs, ys) -> np.ndarray:
    """Heights at points in the DEM's own CRS, interpolated as the tiles are.

    Bilinear rather than the nearest pixel, because the surface the viewer
    draws is bilinear: a nearest-pixel height on a slope can sit a metre or
    two off it, which is enough to float a route's curtain off the ground.
    NaN where any of the four surrounding pixels is missing.
    """
    inverse = ~dataset.transform
    heights = np.full(len(xs), np.nan)
    for index, (x, y) in enumerate(zip(xs, ys)):
        col, row = inverse * (x, y)
        # Pixel centres sit half a pixel in from the corner the transform names.
        col -= 0.5
        row -= 0.5
        col0, row0 = math.floor(col), math.floor(row)
        if col0 < 0 or row0 < 0 or col0 + 1 >= dataset.width or row0 + 1 >= dataset.height:
            continue
        # Consecutive route samples fall in the same DEM block, which GDAL
        # keeps cached, so a read per point stays cheap.
        block = dataset.read(1, window=Window(col0, row0, 2, 2), masked=True)
        if np.ma.getmaskarray(block).any():
            continue
        fx, fy = col - col0, row - row0
        top = block[0, 0] * (1 - fx) + block[0, 1] * fx
        bottom = block[1, 0] * (1 - fx) + block[1, 1] * fx
        heights[index] = top * (1 - fy) + bottom * fy
    return heights


def sample_points(points):
    """Ellipsoidal ground height and geoid separation at each point, in metres.

    ``points`` is a sequence of ``(lat, lon)``. Returns two lists, aligned with
    it: the ground — on the same surface the terrain tiles draw, from the same
    DEMs with the same correction — and the geoid separation there, which is
    what turns an MSL altitude into a height Cesium can place.

    Ground is None where no local DEM covers the point: the terrain tiles draw
    those places flat, and a height invented for them would put a route's
    curtain somewhere that is not the ground.

    Without geoid grids PROJ passes heights through unchanged. The tiles then
    serve orthometric heights, and so does this — ground and route stay in one
    frame, so clearance between them is still right.
    """
    count = len(points)
    if count == 0:
        return [], []
    lats = np.array([float(lat) for lat, _lon in points])
    lons = np.array([float(lon) for _lat, lon in points])
    ground = np.full(count, np.nan)

    sources = _sources_for((lats.min(), lons.min(), lats.max(), lons.max()))
    for entry in sources:
        pending = np.flatnonzero(np.isnan(ground))
        if pending.size == 0:
            break
        south, west, north, east = entry.bounds_latlon
        inside = pending[(lats[pending] >= south) & (lats[pending] <= north)
                         & (lons[pending] >= west) & (lons[pending] <= east)]
        if inside.size == 0:
            continue
        try:
            with rasterio.open(entry.path) as dataset:
                xs, ys = warp_transform(WGS84, dataset.crs,
                                        lons[inside].tolist(), lats[inside].tolist())
                # Sources run finest first, so a coarser DEM only fills what
                # the finer ones could not.
                ground[inside] = _bilinear(dataset, xs, ys)
        except (rasterio.errors.RasterioError, OSError, ValueError):
            continue

    try:
        _x, _y, geoid = _geoid_transformer().transform(lons, lats, np.zeros(count))
        geoid = np.asarray(geoid, dtype=float)
    except Exception:  # noqa: BLE001 — grids missing; see sample_tile
        if _geoid_required():
            raise TerrainTileError(
                "geoid grids unavailable; heights would be ~30 m out. "
                "Install them with projsync or set PROJ_NETWORK=ON."
            )
        geoid = np.zeros(count)

    ellipsoidal = ground + geoid
    return ([None if np.isnan(value) else round(float(value), 2) for value in ellipsoidal],
            [round(float(value), 2) for value in geoid])


def encode(heights: np.ndarray) -> bytes:
    """Heights as little-endian Int16 metres, the width Cesium's callback reads.

    Int16 spans -32768..32767 m, which covers every land elevation on earth
    with a metre to spare, and halves the transfer against Float32.
    """
    clipped = np.clip(np.round(heights), -32768, 32767)
    return clipped.astype("<i2").tobytes()
