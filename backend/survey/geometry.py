"""A boundary's area, how many aircraft fit in it, and its smallest rotated rectangle (docs/LZ_SURVEYS.md §3.3).

The area and the fit are the web's (``lzAreaAndCapacity`` in frontend/src/components/MissionSummary.jsx, over
``getPolygonArea`` and ``capacityForArea``), ported line for line and held to contracts/fixtures/planning/summary.json, so a
survey says the same area the LZ/PZ panel does.
"""

from __future__ import annotations

import math

# The web's numbers, as it writes them (utils/Helpers.js, feature/aircraft/aircraftProfiles.js).
EARTH_RADIUS_FT = 20902231
METERS_TO_FEET = 3.280839895
SQ_FT_PER_ACRE = 43560.0
METRES_PER_DEGREE = 111320.0

# The UH-60L the fit is counted for: the web's FALLBACK_PROFILE (planning/aircraft.json's `fallbackProfile`).
UH60L = {"designation": "UH-60L", "rotor_diameter_m": 16.357, "rotor_tip_clearance_m": 60}


def _js_round(value: float) -> int:
    """JavaScript's Math.round: halves go up, not to even (AGENTS.md §17)."""
    return int(math.floor(value + 0.5))


def polygon_area_sq_ft(coords) -> float:
    """``getPolygonArea``: the spherical-excess area of [[lat, lon], …] in square feet, unsigned; 0 for fewer than three points."""
    if not coords or len(coords) < 3:
        return 0.0
    area = 0.0
    for i in range(len(coords)):
        j = (i + 1) % len(coords)
        lat1 = coords[i][0] * (math.pi / 180)
        lat2 = coords[j][0] * (math.pi / 180)
        lon1 = coords[i][1] * (math.pi / 180)
        lon2 = coords[j][1] * (math.pi / 180)
        area += (lon2 - lon1) * (2 + math.sin(lat1) + math.sin(lat2))
    area = (area * EARTH_RADIUS_FT * EARTH_RADIUS_FT) / 2.0
    return abs(area)


def capacity_for_area(area_sq_ft: float, profile=None) -> int:
    """``capacityForArea``: whole squares of one aircraft's centre spacing (rotor plus tip clearance) in the area."""
    profile = profile or UH60L
    spacing_ft = (float(profile["rotor_diameter_m"]) + float(profile["rotor_tip_clearance_m"])) * METERS_TO_FEET
    spot = spacing_ft ** 2
    if not math.isfinite(area_sq_ft) or area_sq_ft <= 0 or spot <= 0:
        return 0
    return int(math.floor(area_sq_ft / spot))


def area_and_capacity(coords, profile=None) -> dict:
    """``lzAreaAndCapacity``: the area in square feet, rounded as JavaScript rounds, and the fit."""
    if not coords:
        return {"area": 0, "heloCount": 0}
    area = polygon_area_sq_ft(coords)
    return {"area": _js_round(area), "heloCount": max(0, capacity_for_area(area, profile))}


def _hull(points):
    """The convex hull of (x, y) points, anticlockwise (Andrew's monotone chain)."""
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def smallest_rectangle(coords):
    """The boundary's smallest rotated rectangle: ``lengthM`` along its long side, ``widthM`` across, and ``axisDeg``, the long side's
    direction in degrees true, 0 to 179.9 (an axis has two ends: 30 is also 210). Measured in a flat east-north frame round the
    boundary's middle, good to a metre or so over an LZ. None for fewer than three distinct points or no area."""
    if not coords or len(coords) < 3:
        return None
    lat0 = sum(c[0] for c in coords) / len(coords)
    lon0 = sum(c[1] for c in coords) / len(coords)
    east_per_deg = METRES_PER_DEGREE * math.cos(math.radians(lat0))
    points = [((c[1] - lon0) * east_per_deg, (c[0] - lat0) * METRES_PER_DEGREE) for c in coords]
    hull = _hull(points)
    if len(hull) < 3:
        return None

    best = None
    for i in range(len(hull)):
        (x0, y0), (x1, y1) = hull[i], hull[(i + 1) % len(hull)]
        length = math.hypot(x1 - x0, y1 - y0)
        if length == 0:
            continue
        ux, uy = (x1 - x0) / length, (y1 - y0) / length       # along this edge
        along = [x * ux + y * uy for x, y in hull]
        across = [-x * uy + y * ux for x, y in hull]
        a, b = max(along) - min(along), max(across) - min(across)
        if best is None or a * b < best[0] - 1e-9:
            best = (a * b, a, b, ux, uy)
    if best is None or best[0] <= 0:
        return None
    _area, a, b, ux, uy = best
    if a >= b:
        length_m, width_m, dx, dy = a, b, ux, uy
    else:
        length_m, width_m, dx, dy = b, a, -uy, ux
    axis = math.degrees(math.atan2(dx, dy)) % 180.0
    axis = round(axis, 1) % 180.0
    return {"lengthM": length_m, "widthM": width_m, "axisDeg": axis + 0.0}
