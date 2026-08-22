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
from rasterio.warp import reproject
from rasterio.transform import from_bounds

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
                patch = np.full((samples, samples), np.nan, dtype=np.float32)
                reproject(
                    source=rasterio.band(dataset, 1),
                    destination=patch,
                    src_crs=dataset.crs,
                    dst_crs=WGS84,
                    dst_transform=transform,
                    dst_nodata=np.nan,
                    resampling=Resampling.bilinear,
                )
        except (rasterio.errors.RasterioError, OSError, ValueError):
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
        if os.environ.get("TERRAIN_REQUIRE_GEOID", "").strip().lower() in {"1", "true", "yes"}:
            raise TerrainTileError(
                "geoid grids unavailable; terrain would be ~30 m out. "
                "Install them with projsync or set PROJ_NETWORK=ON."
            )

    return destination


def encode(heights: np.ndarray) -> bytes:
    """Heights as little-endian Int16 metres, the width Cesium's callback reads.

    Int16 spans -32768..32767 m, which covers every land elevation on earth
    with a metre to spare, and halves the transfer against Float32.
    """
    clipped = np.clip(np.round(heights), -32768, 32767)
    return clipped.astype("<i2").tobytes()
