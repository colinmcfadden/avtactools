"""Whether a DEM is fit to go into the terrain folder (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §4, §10.1 item 4).

The 3D terrain treats every DEM as NAVD88 and adds the geoid to it (terrain_tiles), so a DEM in any other datum, in feet,
or in ellipsoidal heights is silently wrong in 3D: the trap AGENTS.md §13 says recurs. Each check says what failed in
words. USGS's own 1/3″ and 1 m files state no vertical datum (their CRS is EPSG:4269 or a UTM zone alone), so one fetched
from TNM's 3DEP DEM products is NAVD88 by what it is, and is tagged so; a heights check against Terrarium (built from
USGS's NED) catches feet, ellipsoidal heights and a wrong product all the same.
"""

from __future__ import annotations

import math
import os
import statistics
from dataclasses import dataclass, field

import numpy as np
import rasterio
from rasterio.warp import transform, transform_bounds

from .usgs import GEOID_BOX

MAX_RESOLUTION_M = 15.0
SAMPLES = 5            # a 5 × 5 grid of points, inside the DEM
MAX_MEDIAN_OFF_M = 5.0
MIN_COMPARED = 10      # points both sources answer for, or the comparison says nothing
TERRARIUM_ZOOM = 12


@dataclass
class Checked:
    ok: bool
    reasons: list = field(default_factory=list)   # why it was refused
    notes: list = field(default_factory=list)     # what is worth knowing but refuses nothing
    stated_datum: str | None = None               # the vertical datum the file states, if any
    resolution_m: float | None = None
    bounds: list | None = None                    # [west, south, east, north]


def terrarium_heights(lons, lats):
    """Terrarium's height at each place, metres, or None where its tile did not come."""
    from terrain_provider import _terrarium_tile

    heights = []
    scale = 2 ** TERRARIUM_ZOOM
    for lon, lat in zip(lons, lats):
        x = (lon + 180.0) / 360.0 * scale
        y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * scale
        tile = _terrarium_tile(TERRARIUM_ZOOM, int(x), int(y))
        if tile is None:
            heights.append(None)
            continue
        size = tile.shape[0]
        heights.append(float(tile[min(size - 1, int((y % 1) * size)), min(size - 1, int((x % 1) * size))]))
    return heights


def _vertical(dataset) -> str | None:
    """The vertical datum a GeoTIFF's CRS states, read as a compound CRS: "NAVD88" only for NAVD88 heights in metres,
    "other" for any other datum or unit (NAVD88 in US survey feet reads 3.28 times too high), None for none stated."""
    from pyproj import CRS
    from pyproj.exceptions import CRSError

    if dataset.crs is None:
        return None
    try:
        crs = CRS.from_wkt(dataset.crs.to_wkt())
    except CRSError:
        return "other"
    vertical = next((part for part in crs.sub_crs_list if part.is_vertical), None) if crs.is_compound else None
    if vertical is None:
        return None
    names = f"{vertical.name} {vertical.datum.name if vertical.datum else ''}".upper().replace(" ", "")
    navd88 = "NAVD88" in names or "NORTHAMERICANVERTICALDATUM1988" in names
    unit = (vertical.axis_info[0].unit_name if vertical.axis_info else "").lower()
    return "NAVD88" if navd88 and unit in ("metre", "meter") else "other"


def check_dem(path, *, heights=None) -> Checked:
    from terrain_provider import _raster_resolution_m

    heights = heights or terrarium_heights
    reasons, notes = [], []
    try:
        with rasterio.Env(GTIFF_REPORT_COMPD_CS="YES"):
            with rasterio.open(path) as dataset:
                return _check_open(dataset, reasons, notes, heights, _raster_resolution_m)
    except (rasterio.errors.RasterioError, OSError) as error:
        return Checked(False, [f"Not a GeoTIFF that can be read ({error.__class__.__name__})."])


def _check_open(dataset, reasons, notes, heights, resolution_of) -> Checked:
    if dataset.driver != "GTiff":
        return Checked(False, [f"Not a GeoTIFF (it reads as {dataset.driver})."])
    if dataset.crs is None:
        return Checked(False, ["It has no coordinate system."])
    if dataset.count != 1:
        reasons.append(f"It has {dataset.count} bands; a DEM has one, of heights.")
    if dataset.nodata is None:
        reasons.append("It has no nodata value, so gaps would read as real ground.")
    if not np.issubdtype(np.dtype(dataset.dtypes[0]), np.number):
        reasons.append(f"Its values are {dataset.dtypes[0]}, not numbers.")
    if dataset.crs.is_projected:
        unit = (dataset.crs.linear_units or "").lower()
        if unit not in ("metre", "meter", "m", "metres", "meters"):
            reasons.append(f"Its horizontal unit is {unit or 'unstated'}, not metres.")

    west, south, east, north = transform_bounds(dataset.crs, "EPSG:4326", *dataset.bounds, densify_pts=21)
    bounds = [west, south, east, north]
    box = GEOID_BOX
    if west < box[0] or east > box[2] or south < box[1] or north > box[3]:
        reasons.append("It reaches outside the lower 48, where the 3D terrain's geoid grids do not reach.")
    resolution = resolution_of(dataset, (south, west, north, east))
    if resolution > MAX_RESOLUTION_M:
        reasons.append(f"Its cells are {resolution:.0f} m; slope analysis takes 15 m or finer.")
    if not dataset.profile.get("tiled"):
        reasons.append("It is not tiled, so the 3D terrain would read it very slowly.")

    stated = _vertical(dataset)
    if stated == "other":
        reasons.append("It states a vertical datum or unit other than NAVD88 metres; the 3D terrain would put its ground wrong.")

    if not reasons:
        compared = _compare(dataset, bounds, heights)
        if compared is None:
            notes.append("Heights not compared with Terrarium (it did not answer, or too few points had data).")
        elif abs(compared) > MAX_MEDIAN_OFF_M:
            reasons.append(
                f"Its heights are {compared:+.1f} m from Terrarium's at the median: feet, ellipsoidal heights or the "
                "wrong product, not NAVD88 metres.")
        else:
            notes.append(f"Heights {compared:+.1f} m from Terrarium's at the median.")
    return Checked(not reasons, reasons, notes, stated, resolution, bounds)


def _compare(dataset, bounds, heights) -> float | None:
    west, south, east, north = bounds
    lons, lats = [], []
    for i in range(SAMPLES):
        for j in range(SAMPLES):
            lons.append(west + (east - west) * (0.1 + 0.8 * i / (SAMPLES - 1)))
            lats.append(south + (north - south) * (0.1 + 0.8 * j / (SAMPLES - 1)))
    xs, ys = transform("EPSG:4326", dataset.crs, lons, lats)
    values = [float(v[0]) for v in dataset.sample(list(zip(xs, ys)), indexes=1)]
    reference = heights(lons, lats)
    differences = [value - other for value, other in zip(values, reference)
                   if other is not None and math.isfinite(value) and value != dataset.nodata and value > -1000]
    if len(differences) < MIN_COMPARED:
        return None
    return statistics.median(differences)


def tag_navd88(path) -> None:
    """Records NAVD88 as a DEM's vertical datum in its ``.aux.xml`` (GDAL's side file, which it folds into the dataset's
    tags, where the catalog reads ``VERT_DATUM``). Kept apart from the GeoTIFF, which stays as USGS published it; an
    existing side file keeps everything else it holds."""
    import xml.etree.ElementTree as ElementTree

    side = f"{path}.aux.xml"
    if os.path.exists(side):
        tree = ElementTree.parse(side)
        root = tree.getroot()
    else:
        root = ElementTree.Element("PAMDataset")
        tree = ElementTree.ElementTree(root)
    metadata = next((m for m in root.findall("Metadata") if not m.get("domain")), None)
    if metadata is None:
        metadata = ElementTree.SubElement(root, "Metadata")
    item = next((i for i in metadata.findall("MDI") if i.get("key") == "VERT_DATUM"), None)
    if item is None:
        item = ElementTree.SubElement(metadata, "MDI", key="VERT_DATUM")
    item.text = "NAVD88"
    partial = f"{side}.part"
    tree.write(partial, encoding="unicode")
    os.replace(partial, side)
