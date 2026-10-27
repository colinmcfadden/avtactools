"""The snapshot: what EZ/PZ measured of one LZ, frozen into its survey (docs/LZ_SURVEYS.md §3.3).

``measure`` works everything out from the survey's LZ/PZ diagram and the field's best approach; nothing is stored here.
The route shows the admin what changed (``differences``) before the survey takes the new snapshot, so a measurement is
never replaced in silence, and a published revision keeps the snapshot it was signed with.

- **Grid and lat/long** from the diagram's target, the touchdown point: MGRS at 1 m, truncated as utils/mgrs.js does, by
  PyGeodesy, and the lat/long in degrees and decimal minutes, as the book writes it.
- **Elevation** is the target's height in the DEM ``terrain_provider.load_best_terrain`` chooses for the LZ, the one the slope
  and the profiles are read in, named with its resolution and vertical datum. Never the SRTM30 point of /api/analyze-field
  (§2: that is how the draft printed 2093 ft over a profile that tops out at 2067 ft).
- **Size, axis and area** from the boundary: its smallest rotated rectangle, and the area by the web's own formula.
- **Fit**: UH-60Ls by area, a note only, never Max A/C (a judgement, the admin's).
- **Slope at the best approach**: the 95th percentile, inside the boundary, of the nose-up, nose-down and cross slope along the
  field's best approach, from the same Horn gradients as /api/terrain-analysis's ``byHeading``, judged against the UH-60 limits
  with no wind (a survey is not for a day's weather). The average and the steepest are kept beside it. A slope is over its
  limit only when greater than it at 0.1° (AGENTS.md §13).
- **The slope map, contours and profiles**: /api/terrain-analysis's answer, kept for the editor and the page.

Where something cannot be measured it is None and ``problems`` says why in words: a value is never made up (AGENTS.md §14).

A snapshot keeps what it was measured for (the target, the boundary and the best approach), so ``stale`` can say when the survey has
moved on since: its numbers would then print beside a plan and an approach they were not measured for, and it is measured again before
it is sent for review or published.
"""

from __future__ import annotations

import math
import re
from datetime import datetime

import numpy as np
from pygeodesy.ellipsoidalExact import LatLon

import terrain_provider as tp
from survey import geometry

# 2: the snapshot keeps the boundary and the best approach it was measured for (``stale``).
SNAPSHOT_VERSION = 2
FEET_PER_METRE = tp.FEET_PER_METRE
# The UH-60 limits by the names a survey uses (terrain_provider's are the slope tile's "noseHigh"/"noseLow").
LIMITS_DEG = {
    "noseUp": tp.UH60_LIMITS_DEG["noseHigh"],
    "noseDown": tp.UH60_LIMITS_DEG["noseLow"],
    "cross": tp.UH60_LIMITS_DEG["crossSlope"],
}
LIMIT_WORDS = {"noseUp": "nose up", "noseDown": "nose down", "cross": "cross slope"}
# How far round the target to read the DEM when there is no boundary yet.
TARGET_ONLY_PAD_M = 60.0
# Past this on its long side a boundary is not an LZ (a slip of the mouse, a polygon of a county), and measuring it would read
# a DEM, or fetch Terrarium tiles, over all of it.
MAX_BOUNDARY_M = 3000.0


class CannotMeasure(ValueError):
    """Why a survey cannot be measured, with a ``code`` for the API's answer."""

    def __init__(self, message, code):
        super().__init__(message)
        self.code = code


def _finite(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def target_of(diagram):
    """The diagram's target as (lat, lon), or None."""
    target = (diagram or {}).get("target") if isinstance(diagram, dict) else None
    if not isinstance(target, dict):
        return None
    lat, lon = _finite(target.get("lat")), _finite(target.get("lon", target.get("lng")))
    if lat is None or lon is None or not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
        return None
    return lat, lon


def _ring(value):
    if not isinstance(value, list):
        return None
    points = []
    for point in value:
        if not isinstance(point, (list, tuple)) or len(point) < 2:
            return None
        lat, lon = _finite(point[0]), _finite(point[1])
        if lat is None or lon is None:
            return None
        points.append([lat, lon])
    if len(points) >= 2 and points[0] == points[-1]:
        points = points[:-1]
    return points if len(points) >= 3 else None


def boundary_of(diagram):
    """The LZ's boundary, [[lat, lon], …]: the analysis's (detectedLZ), else one drawn by hand and not analysed yet (customLZ)."""
    analysis = (diagram or {}).get("analysis") if isinstance(diagram, dict) else None
    if not isinstance(analysis, dict):
        return None
    return _ring(analysis.get("detectedLZ")) or _ring(analysis.get("customLZ"))


_LEADING_NUMBER = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)")


def _rounded_ring(ring):
    return [[round(lat, 7) + 0.0, round(lon, 7) + 0.0] for lat, lon in ring] if ring else None


def _rounded_heading(heading):
    return None if heading is None else round(heading, 1) + 0.0


def heading_of(text):
    """A heading typed as the book writes it ("360", "045°", "270 deg"), in degrees 0..359.9, or None. 360 is north."""
    if not isinstance(text, str):
        return None
    match = _LEADING_NUMBER.match(text)
    if not match:
        return None
    value = float(match.group(1))
    if value > 360:
        return None
    return value % 360.0


def grid_text(lat, lon):
    """MGRS at 1 m, as utils/mgrs.js writes it ("16S GD 63830 31670"), or "" outside MGRS's latitudes."""
    try:
        return LatLon(lat, lon).toMgrs().toStr(prec=0, sep=" ")
    except Exception:  # PyGeodesy raises its own kinds of error for the poles
        return ""


def _ddm(value, positive, negative, degree_digits):
    hemisphere = positive if value >= 0 else negative
    value = abs(value)
    degrees = int(math.floor(value))
    thousandths = int(round((value - degrees) * 60 * 1000))
    if thousandths >= 60000:
        degrees, thousandths = degrees + 1, thousandths - 60000
    return f"{hemisphere}{degrees:0{degree_digits}d} {thousandths // 1000:02d}.{thousandths % 1000:03d}"


def lat_long_text(lat, lon):
    """Degrees and decimal minutes, as the book writes them: "N34 35.579 W084 07.402"."""
    return f"{_ddm(lat, 'N', 'S', 2)} {_ddm(lon, 'E', 'W', 3)}"


def _terrain_bounds(points, pad_m=None):
    if len(points) >= 3:
        return tp.bounds_for_polygon(points, padding_m=pad_m)
    lat, lon = points[0]
    pad = pad_m if pad_m is not None else TARGET_ONLY_PAD_M
    dlat = pad / tp.METRES_PER_DEGREE
    dlon = pad / max(1.0, tp.METRES_PER_DEGREE * math.cos(math.radians(lat)))
    return lat - dlat, lon - dlon, lat + dlat, lon + dlon


def _stats(values):
    if values.size == 0:
        return None
    return {
        "p95": round(float(np.percentile(values, 95)), 1) + 0.0,
        "mean": round(float(values.mean()), 1) + 0.0,
        "max": round(float(values.max()), 1) + 0.0,
    }


def slope_at_heading(grid, boundary, heading_deg):
    """The slope inside ``boundary`` over ``grid``: overall, and at ``heading_deg`` (None for none) nose up, nose down and cross, each
    its 95th percentile, mean and steepest; and the call against the UH-60 limits. None when no cell inside has a slope."""
    xres, yres = max(grid.xres_m, 0.01), max(grid.yres_m, 0.01)
    slope_deg, grad_east, grad_north = tp._horn_gradients(grid.elevation_m, xres, yres)
    mask = tp._polygon_mask(grid, boundary) & np.isfinite(slope_deg)
    if not mask.any():
        return None
    result = {
        "headingDeg": None if heading_deg is None else round(heading_deg, 1) + 0.0,
        "limits": dict(LIMITS_DEG),
        "overall": _stats(slope_deg[mask]),
        "noseUp": None,
        "noseDown": None,
        "cross": None,
        "exceeded": [],
        "call": "heading_required",
        "sampleCount": int(mask.sum()),
        "resolutionM": round(grid.resolution_m, 1),
        "source": grid.source,
    }
    if heading_deg is None:
        result["words"] = "Set the best approach to judge the slope against the UH-60 limits."
        return result
    along, across = tp._along_and_across(grad_east, grad_north, heading_deg)
    along, across = along[mask], across[mask]
    result["noseUp"] = _stats(np.maximum(along, 0.0))
    result["noseDown"] = _stats(np.maximum(-along, 0.0))
    result["cross"] = _stats(across)
    exceeded = [key for key in ("noseUp", "noseDown", "cross") if result[key]["p95"] > LIMITS_DEG[key]]
    result["exceeded"] = exceeded
    result["call"] = "exceeded" if exceeded else "within"
    heading = f"{int(round(heading_deg)) % 360:03d}°"
    if exceeded:
        parts = [f"{LIMIT_WORDS[k]} {result[k]['p95']:.1f}°, limit {LIMITS_DEG[k]:g}°" for k in exceeded]
        result["words"] = f"Limit exceeded at {heading} (95th percentile): {'; '.join(parts)}."
    else:
        result["words"] = (
            f"Within limits at {heading} (95th percentile): nose up {result['noseUp']['p95']:.1f}°, "
            f"nose down {result['noseDown']['p95']:.1f}°, cross {result['cross']['p95']:.1f}°."
        )
    return result


def _terrain_block(boundary):
    """The slope map, contours and profiles of /api/terrain-analysis for the boundary, without the table by heading (the clients'
    slope tile is not the survey's call, §11 R7). Raises RuntimeError as the analysis does when nothing covers the LZ."""
    analysis = tp.build_slope_analysis(boundary)
    return {key: analysis.get(key) for key in ("overlay", "bounds", "source", "resolutionM", "verticalDatum", "stats", "contours", "profiles")}


def measure(diagram, field, by_name="", now=None):
    """The snapshot for a survey's diagram and field values. Raises CannotMeasure with no target, or a boundary no LZ could have."""
    target = target_of(diagram)
    if target is None:
        raise CannotMeasure("Set the LZ's target (its touchdown point) before measuring.", "no_target")
    lat, lon = target
    boundary = boundary_of(diagram)
    if boundary is not None:
        rect = geometry.smallest_rectangle(boundary + [[lat, lon]])
        if rect is not None and rect["lengthM"] > MAX_BOUNDARY_M:
            raise CannotMeasure("The boundary (with the target) is more than 3 km across: too large to be an LZ.", "too_large_to_measure")
    heading = heading_of((field or {}).get("approachDeg"))
    problems = []

    snapshot = {
        "version": SNAPSHOT_VERSION,
        "measuredAt": (now or datetime.utcnow()).replace(microsecond=0).isoformat() + "Z",
        "measuredBy": by_name or "",
        "target": {"lat": round(lat, 7), "lon": round(lon, 7)},
        "boundary": _rounded_ring(boundary),
        "approachDeg": _rounded_heading(heading),
        "grid": grid_text(lat, lon),
        "latLong": lat_long_text(lat, lon),
        "elevation": None,
        "size": None,
        "fit": None,
        "slope": None,
        "terrain": None,
        "problems": problems,
    }

    points = (boundary or []) + [[lat, lon]]
    grid = tp.load_best_terrain(_terrain_bounds(points))
    if grid is None:
        problems.append("No terrain covers this LZ, so its elevation and slope are not measured.")
    else:
        (height_m,) = tp._sample(grid, [lat], [lon])
        if height_m is None:
            problems.append("The terrain has no height at the target.")
        else:
            snapshot["elevation"] = {
                "ft": int(round(height_m * FEET_PER_METRE)),
                "m": round(height_m, 1),
                "source": grid.source,
                "dem": list(grid.names),
                "resolutionM": round(grid.resolution_m, 1),
                "verticalDatum": grid.vertical_datum,
            }

    if boundary is None:
        problems.append("The LZ has no boundary yet: find or draw one to measure its size and slope.")
        return snapshot

    rect = geometry.smallest_rectangle(boundary)
    area = geometry.polygon_area_sq_ft(boundary)
    if rect is not None:
        snapshot["size"] = {
            "lengthFt": int(round(rect["lengthM"] * FEET_PER_METRE)),
            "widthFt": int(round(rect["widthM"] * FEET_PER_METRE)),
            "axisDeg": rect["axisDeg"],
            "areaSqFt": geometry.area_and_capacity(boundary)["area"],
            "areaAcres": round(area / geometry.SQ_FT_PER_ACRE, 2),
        }
        snapshot["fit"] = {"aircraft": geometry.UH60L["designation"], "count": geometry.capacity_for_area(area)}
    if heading is None:
        problems.append("The best approach is not set, so the slope is not judged at it.")
    if grid is not None:
        snapshot["slope"] = slope_at_heading(grid, boundary, heading)
        if snapshot["slope"] is None:
            problems.append("The terrain has no slope inside the boundary.")
    try:
        snapshot["terrain"] = _terrain_block(boundary)
    except RuntimeError:
        problems.append("The slope map and profiles could not be drawn: no terrain covers the LZ.")
    return snapshot


def _heading_words(value):
    return f"{int(round(value)) % 360 or 360:03d}°" if isinstance(value, (int, float)) and math.isfinite(value) else "none"


def stale(snapshot, diagram, field):
    """Why ``snapshot`` no longer describes the survey, in words: empty when it does, or when there is none (not measured is said
    elsewhere). The target, the boundary and the best approach are each compared with what it was measured for."""
    if not isinstance(snapshot, dict) or not snapshot:
        return []
    if not isinstance(snapshot.get("version"), int) or snapshot["version"] < 2 or "boundary" not in snapshot or "approachDeg" not in snapshot:
        return ["It was measured by an earlier version of EZ/PZ."]
    reasons = []
    target = target_of(diagram)
    then = snapshot.get("target") if isinstance(snapshot.get("target"), dict) else {}
    if target is None or (round(target[0], 7), round(target[1], 7)) != (then.get("lat"), then.get("lon")):
        reasons.append("The target moved after it was measured.")
    if _rounded_ring(boundary_of(diagram)) != snapshot.get("boundary"):
        reasons.append("The boundary changed after it was measured.")
    now = _rounded_heading(heading_of((field or {}).get("approachDeg")))
    if now != snapshot.get("approachDeg"):
        reasons.append(f"The best approach changed after it was measured: measured at {_heading_words(snapshot.get('approachDeg'))}, "
                       f"now {_heading_words(now)}.")
    return reasons


def _pick(snapshot, *path):
    value = snapshot
    for key in path:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


# What a change of snapshot is told by: (label, path into the snapshot).
COMPARED = (
    ("Grid", ("grid",)),
    ("Lat/long", ("latLong",)),
    ("Elevation (ft MSL)", ("elevation", "ft")),
    ("DEM", ("elevation", "dem")),
    ("Length (ft)", ("size", "lengthFt")),
    ("Width (ft)", ("size", "widthFt")),
    ("LZ axis (°)", ("size", "axisDeg")),
    ("Area (acres)", ("size", "areaAcres")),
    ("UH-60Ls by area", ("fit", "count")),
    ("Slope judged at (°)", ("slope", "headingDeg")),
    ("Nose up, 95th percentile (°)", ("slope", "noseUp", "p95")),
    ("Nose down, 95th percentile (°)", ("slope", "noseDown", "p95")),
    ("Cross slope, 95th percentile (°)", ("slope", "cross", "p95")),
    ("Slope call", ("slope", "call")),
)


def _shown(value):
    if isinstance(value, list):
        return ", ".join(str(v) for v in value)
    return value


def differences(before, after):
    """What a new snapshot changes, as [{label, before, after}], for the admin to see before it replaces the old one."""
    changes = []
    for label, path in COMPARED:
        old, new = _shown(_pick(before or {}, *path)), _shown(_pick(after or {}, *path))
        if old != new:
            changes.append({"label": label, "before": old, "after": new})
    return changes
