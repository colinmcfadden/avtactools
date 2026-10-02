"""Turning a map target into an area of interest the pipeline can crop to.

The app knows an LZ as a latitude and longitude. A LiDAR source knows its own
projected coordinates — Albers for the published tiles, Web Mercator for the
Entwine-indexed copy on AWS — so a target has to be projected into whichever
frame the source is written in before it can be cropped.

Distances are metres on the ground. Both source CRSs are metric, but Web
Mercator's scale factor grows with latitude: at 34.6°N a metre of ground is
about 1.21 Mercator units. Ignoring that would quietly shrink every area of
interest by a fifth.
"""

from __future__ import annotations

import math

from pyproj import CRS, Transformer

WGS84 = "EPSG:4326"

# The two frames 3DEP is published in, and the vertical datum both carry.
# NAVD88 heights are what makes the geoid transform in crs.py necessary.
ALBERS_NAVD88 = "EPSG:6350+5703"      # the LAZ tiles as downloaded
WEB_MERCATOR_NAVD88 = "EPSG:3857+5703"  # the AWS Entwine copy

# Enough to see the approach ends and the treeline around a landing point.
DEFAULT_RADIUS_M = 500.0


def _horizontal(crs_string: str) -> CRS:
    """The horizontal half of a CRS — cropping is a 2D operation.

    Takes "EPSG:6350+5703" and WKT alike. This used to split the string at
    "+", which cut WKT apart: a LAS 1.4 tile states its CRS as compound WKT
    named "NAD83(2011) / Conus Albers + NAVD88 height", plus sign included, and
    every build from the downloaded collection crashed on it.
    """
    crs = CRS.from_user_input(crs_string)
    return crs.sub_crs_list[0] if crs.is_compound else crs


def mercator_scale(latitude: float) -> float:
    """Web Mercator units per ground metre at this latitude.

    Mercator preserves angles by stretching distance away from the equator, so
    its units are only metres at the equator. A 500 m box specified without
    this correction would be 500 units — about 413 m of ground in Georgia.
    """
    return 1.0 / math.cos(math.radians(latitude))


def bbox_for(lat: float, lon: float, *, radius_m: float = DEFAULT_RADIUS_M,
             source_srs: str = WEB_MERCATOR_NAVD88):
    """Square area of interest around a target, in the source's coordinates.

    Returns ``(xmin, ymin, xmax, ymax)`` — the order pipeline.build expects.
    """
    target = _horizontal(source_srs)
    to_source = Transformer.from_crs(CRS.from_string(WGS84), target, always_xy=True)
    x, y = to_source.transform(lon, lat)

    half = radius_m
    if target.to_epsg() == 3857:
        half *= mercator_scale(lat)

    return (x - half, y - half, x + half, y + half)


def ground_extent_m(bbox, *, source_srs: str) -> float:
    """Width of a bbox in real ground metres, for reporting what was built."""
    target = _horizontal(source_srs)
    width = bbox[2] - bbox[0]
    if target.to_epsg() != 3857:
        return width
    # Undo the scale factor using the latitude at the box's centre.
    to_wgs = Transformer.from_crs(target, CRS.from_string(WGS84), always_xy=True)
    _lon, lat = to_wgs.transform((bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2)
    return width / mercator_scale(lat)
