"""Coordinate handling for 3DEP LiDAR, and the vertical datum guard.

USGS 3DEP tiles are published in a *compound* CRS — a projected horizontal
system plus NAVD88 orthometric heights, e.g.::

    NAD83(2011) / Conus Albers + NAVD88 height - Geoid12B (Meters)

Cesium wants WGS84 ellipsoidal heights. Converting between the two needs a
geoid model, and in north Georgia the separation is about **-30 m (-100 ft)**.

The dangerous part is how PROJ behaves without the grid: it does not raise, it
silently returns the orthometric height unchanged. Every treetop would then sit
100 ft off in a tool used to judge obstruction clearance. So the transform is
verified rather than trusted — see ``assert_vertical_datum_applied``.
"""

from __future__ import annotations

from pyproj import CRS, Transformer

# What Cesium consumes: WGS84 geocentric metres (ECEF).
ECEF = "EPSG:4978"
# Geographic 3D, used to inspect a height in metres above the ellipsoid.
GEOGRAPHIC_3D = "EPSG:4979"

# A separation this small means no geoid was applied. Real values across CONUS
# run roughly -35 m to -10 m; nowhere in the lower 48 is it near zero.
MIN_EXPECTED_SEPARATION_M = 1.0


class VerticalDatumError(RuntimeError):
    """The geoid grid is unavailable, so heights would be silently wrong."""


def compound_crs(horizontal: str, vertical: str = "EPSG:5703") -> CRS:
    """Compound CRS from a horizontal code and a vertical one (NAVD88 default)."""
    return CRS.from_string(f"{horizontal}+{vertical}")


def has_vertical_datum(crs: CRS) -> bool:
    """Whether this CRS carries height information at all.

    A 2D source needs no geoid transform, and demanding one would reject
    perfectly good data.
    """
    return len(crs.axis_info) >= 3


def to_ecef(source: CRS):
    """Transformer from a source CRS into the frame Cesium renders in."""
    return Transformer.from_crs(source, CRS.from_string(ECEF), always_xy=True)


def geoid_separation(source: CRS, x: float, y: float, z: float) -> float:
    """Ellipsoidal minus orthometric height at a point, in metres.

    Zero means no geoid was applied — which is the failure this module exists
    to catch, because PROJ reports it as success.
    """
    to_geographic = Transformer.from_crs(
        source, CRS.from_string(GEOGRAPHIC_3D), always_xy=True)
    _lon, _lat, ellipsoidal = to_geographic.transform(x, y, z)
    return ellipsoidal - z


def assert_vertical_datum_applied(source: CRS, sample) -> float:
    """Fail loudly if heights are passing through untransformed.

    ``sample`` is one representative ``(x, y, z)`` from the tile. Returns the
    separation so a caller can log it, since an unexpected value is itself worth
    seeing in the build output.
    """
    if not has_vertical_datum(source):
        return 0.0

    separation = geoid_separation(source, *sample)
    if abs(separation) < MIN_EXPECTED_SEPARATION_M:
        raise VerticalDatumError(
            "Geoid grid unavailable: NAVD88 heights are passing through "
            f"unchanged (separation {separation:.2f} m). Points would be placed "
            "roughly 100 ft off. Enable PROJ_NETWORK=ON, or install the grids "
            "with `projsync --bbox ...`, before generating tiles."
        )
    return separation
