"""Field against computed: where the unit's survey and EZ/PZ's measurement disagree, and what the page prints (docs/LZ_SURVEYS.md §2).

Only the measured quantities have both (§11, R3): the grid, the lat/long, the elevation, the size, the slope and the approach's
angle (the survey profile's, against the LiDAR's shallowest clear angle from the touchdown point along the best approach, step L4).
Where both exist the computed value is printed, unless an admin overrides it with the field's, with a reason. The approach's angle
can also be typed by the editor over the LiDAR's, with a reason (the owner, 2026-10-27): the page prints it as the editor's. An admin
can also accept a disagreement (the computed value stands, said so), which takes it off the count. A choice is kept with the two
values it was made on: once either changes (the field retyped, the LZ measured again), it no longer settles the disagreement, which
is counted again until an admin looks at it; an angle the editor typed is looked at again whether or not the two disagree, since
the page no longer prints it.

The thresholds below only flag a value for review; none of them is a safety limit.
"""

from __future__ import annotations

import math
import re
from datetime import datetime

from pygeodesy import mgrs
from pygeodesy.ellipsoidalExact import LatLon

# How far apart two values may be before they are called a disagreement.
POSITION_M = 50.0          # the import's own check of grid against lat/long (§2)
ELEVATION_FT = 25.0        # a few times a 10 m DEM's vertical error
SLOPE_DEG = 2.0
SIZE_FRACTION = 0.10
APPROACH_DEG = 5.0         # the field's angles are paced and judged by eye: the old book's read a median 7.3° from the LiDAR's
OBSTACLE_TREE_FT = 16.4    # a tree's 5 m (survey/obstacles.py's TREE_MIN_M): vegetation under it is brush

KEYS = ("grid", "latLong", "elevation", "size", "slope", "approach")
LABELS = {"grid": "Grid", "latLong": "Lat/long", "elevation": "Elevation", "size": "Size", "slope": "Slope",
          "approach": "Approach angle"}
CHOICES = ("computed", "field", "typed")
# What the editor may type over the LiDAR's: the approach's angle, in degrees.
TYPED = ("approach",)
MAX_REASON = 500

_NUMBER = re.compile(r"[-−‐–]?\s*[0-9]+(?:\.[0-9]+)?")


def _numbers(text):
    """The numbers in a field's text; one too long to be a number anyone typed (it reads as infinity) is left out."""
    numbers = (float(m.replace("−", "-").replace("‐", "-").replace("–", "-").replace(" ", "")) for m in _NUMBER.findall(text or ""))
    return [n for n in numbers if math.isfinite(n)]


def parse_grid(text):
    """An MGRS grid as the book writes it ("16S GD 6341 3199"), as (lat, lon) of its square's centre, or None."""
    if not isinstance(text, str) or not text.strip():
        return None
    try:
        point = mgrs.parseMGRS(re.sub(r"\s+", "", text).upper()).toLatLon()
    except Exception:  # PyGeodesy's own errors for a grid it cannot read
        return None
    return point.lat, point.lon


_TOKEN = re.compile(r"[NSEW]|-?[0-9]+(?:\.[0-9]+)?", re.IGNORECASE)


def _angle(numbers, hemisphere):
    """Degrees from 1 to 3 numbers (degrees, minutes, seconds) and a hemisphere letter, or None."""
    if not 1 <= len(numbers) <= 3 or any(n < 0 for n in numbers[1:]) or any(n >= 60 for n in numbers[1:]):
        return None
    value = abs(numbers[0]) + sum(n / 60 ** i for i, n in enumerate(numbers[1:], start=1))
    return -value if numbers[0] < 0 or hemisphere in ("S", "W") else value


def parse_lat_long(text):
    """A lat/long as the book or a GPS writes it ("N 34 35.579 W084 07.402", "34.5930, -84.1234", "34°35'34.7\"N 84°07'24.1\"W"),
    as (lat, lon), or None. The hemisphere letters go all before their numbers or all after them; with none, the numbers are signed
    and split in half."""
    if not isinstance(text, str) or not text.strip():
        return None
    tokens = _TOKEN.findall(text.replace("−", "-").replace("–", "-"))
    letters = [i for i, t in enumerate(tokens) if t.isalpha()]
    numbers = [float(t) if not t.isalpha() else None for t in tokens]
    if not letters:
        if len(tokens) not in (2, 4, 6):
            return None
        half = len(tokens) // 2
        parts = [(numbers[:half], ""), (numbers[half:], "")]
    elif len(letters) == 2 and letters[0] == 0:
        parts = [(numbers[1:letters[1]], tokens[0].upper()), (numbers[letters[1] + 1:], tokens[letters[1]].upper())]
    elif len(letters) == 2 and letters[1] == len(tokens) - 1:
        parts = [(numbers[:letters[0]], tokens[letters[0]].upper()), (numbers[letters[0] + 1:-1], tokens[-1].upper())]
    else:
        return None
    (first, first_h), (second, second_h) = parts
    lat, lon = _angle(first, first_h), _angle(second, second_h)
    if lat is None or lon is None:
        return None
    if first_h in ("E", "W") and second_h in ("N", "S"):
        lat, lon = lon, lat
    elif first_h in ("E", "W") or second_h in ("N", "S"):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


def _distance_m(a, b):
    return LatLon(*a).distanceTo(LatLon(*b))


def _component(text):
    """Which slope the field's words are about: cross ("roll", "side low"), nose up or nose down, else the steepest."""
    lowered = (text or "").lower()
    if any(word in lowered for word in ("roll", "cross", "side")):
        return "cross"
    if "nose up" in lowered or "upslope" in lowered or "up slope" in lowered:
        return "noseUp"
    if "nose down" in lowered or "downslope" in lowered or "down slope" in lowered:
        return "noseDown"
    return None


def _feet(numbers, text):
    """Two sizes in feet from text such as "500 x 300", "150 m x 90 m"."""
    if len(numbers) < 2:
        return None
    scale = 3.28084 if re.search(r"\d\s*m\b|metre|meter", text or "", re.IGNORECASE) else 1.0
    return sorted((abs(numbers[0]) * scale, abs(numbers[1]) * scale), reverse=True)


def _row(key, field_text, computed_text, apart, disagrees):
    return {"key": key, "label": LABELS[key], "field": field_text, "computed": computed_text, "apart": apart, "disagrees": disagrees}


def _rows(field, snapshot):
    field = field or {}
    snapshot = snapshot or {}
    rows = []
    target = snapshot.get("target") or {}
    here = (target.get("lat"), target.get("lon")) if target.get("lat") is not None else None

    for key, parse in (("grid", parse_grid), ("latLong", parse_lat_long)):
        typed = (field.get(key) or "").strip()
        computed = snapshot.get(key) or ""
        if not typed or not computed or here is None:
            rows.append(_row(key, typed, computed, None, False))
            continue
        there = parse(typed)
        if there is None:
            rows.append(_row(key, typed, computed, "cannot be read", True))
            continue
        metres = _distance_m(here, there)
        rows.append(_row(key, typed, computed, f"{metres:,.0f} m apart", metres > POSITION_M))

    typed = (field.get("elevationFt") or "").strip()
    elevation = snapshot.get("elevation") or {}
    computed = f"{elevation['ft']} ft MSL" if elevation.get("ft") is not None else ""
    numbers = _numbers(typed)
    if typed and computed and numbers:
        feet = elevation["ft"] - numbers[0]
        rows.append(_row("elevation", typed, computed, f"{abs(feet):,.0f} ft apart", abs(feet) > ELEVATION_FT))
    else:
        rows.append(_row("elevation", typed, computed, None, bool(typed and computed)))

    typed = (field.get("size") or "").strip()
    size = snapshot.get("size") or {}
    computed = f"{size['lengthFt']:,} × {size['widthFt']:,} ft" if size.get("lengthFt") is not None else ""
    sizes = _feet(_numbers(typed), typed)
    if typed and computed and sizes:
        off = max(abs(sizes[0] - size["lengthFt"]) / max(size["lengthFt"], 1), abs(sizes[1] - size["widthFt"]) / max(size["widthFt"], 1))
        rows.append(_row("size", typed, computed, f"{off * 100:.0f}% apart", off > SIZE_FRACTION))
    else:
        rows.append(_row("size", typed, computed, None, bool(typed and computed)))

    typed = (field.get("slope") or "").strip()
    slope = snapshot.get("slope") or {}
    which = _component(typed)
    if which is None and slope.get("cross"):
        which = max(("noseUp", "noseDown", "cross"), key=lambda k: slope[k]["p95"])
    measured = (slope.get(which) or {}).get("p95") if which else None
    words = {"noseUp": "nose up", "noseDown": "nose down", "cross": "cross"}
    computed = f"{words[which]} {measured:.1f}° (95th percentile)" if measured is not None else ""
    numbers = _numbers(typed)
    if typed and computed and numbers:
        degrees = abs(abs(numbers[0]) - measured)
        rows.append(_row("slope", typed, computed, f"{degrees:.1f}° apart", degrees > SLOPE_DEG))
    else:
        rows.append(_row("slope", typed, computed, None, bool(typed and computed)))

    # The survey profile ("100 ft at 200 m": 100 ft up 200 m out from the touchdown point, where the descent from 100 ft AGL begins) as
    # an angle, against the LiDAR's shallowest clear angle from the touchdown point along the best approach.
    feet, metres = (_numbers(field.get(key) or "") for key in ("profileObstacleFt", "profileDistanceM"))
    typed, angle = "", None
    if feet and metres:
        typed = f"{feet[0]:g} ft at {metres[0]:g} m"
        if feet[0] > 0 and metres[0] > 0:
            angle = math.degrees(math.atan2(feet[0] * 0.3048, metres[0]))
            typed = f"{angle:.1f}° ({typed})"
    # A lane measured before its angle was from the touchdown point (a snapshot older than version 5, which has no ``start``) has none.
    lane = (snapshot.get("obstacles") or {}).get("approach") or {}
    clearance = lane.get("clearance") if isinstance(lane.get("start"), dict) else None
    computed = lidar_angle_words(clearance)
    if typed and computed and angle is None:
        rows.append(_row("approach", typed, computed, "cannot be read", True))
    elif typed and computed:
        degrees = abs(angle - max(clearance["angleDeg"], 0.0))
        rows.append(_row("approach", typed, computed, f"{degrees:.1f}° apart", degrees > APPROACH_DEG))
    else:
        rows.append(_row("approach", typed, computed, None, False))
    return rows


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def lidar_angle_words(clearance):
    """The LiDAR's angle along a way as the comparison says it: "14.4° (LiDAR: 35 ft tree 41 m out)", or "" where it has none."""
    if not isinstance(clearance, dict) or not _finite(clearance.get("angleDeg")):
        return ""
    obstacle = clearance.get("obstacle")
    if clearance["angleDeg"] <= 0 or not isinstance(obstacle, dict):
        return f"{max(clearance['angleDeg'], 0.0):.1f}° (LiDAR: nothing in the way)"
    what = "the ground" if obstacle.get("kind") == "terrain" else f"{obstacle.get('heightFt') or 0:.0f} ft {_thing(obstacle)}"
    out = f" {obstacle['fromTargetM']:.0f} m out" if _finite(obstacle.get("fromTargetM")) else ""
    return f"{clearance['angleDeg']:.1f}° (LiDAR: {what}{out})"


def _thing(obstacle):
    """What the LiDAR calls an obstacle, in words: a tree (vegetation of 5 m or more), brush, a building, a tower, a wire."""
    kind = obstacle.get("kind")
    if kind == "vegetation":
        return "tree" if (obstacle.get("heightFt") or 0) >= OBSTACLE_TREE_FT else "brush"
    return {"building": "building", "tower": "tower", "wire": "wire", "bridge": "bridge"}.get(kind, "obstacle")


def typed_angle(value):
    """An angle the editor typed, as kept: degrees to a tenth, over 0 and under 90; None for anything else."""
    if not _finite(value):
        return None
    value = round(float(value), 1)
    return value if 0 < value < 90 else None


def compare(field, snapshot, overrides):
    """Every measured quantity: the field's and the computed value, how far apart, whether they disagree, the admin's choice if it
    still stands, and which is ``printed``: "computed", "field", "typed" (the editor's angle, the choice's ``value``) or None. ``open``
    is a disagreement nobody has settled, or an angle the editor typed that no longer stands."""
    overrides = overrides or {}
    out = []
    for row in _rows(field, snapshot):
        choice = overrides.get(row["key"])
        choice = choice if isinstance(choice, dict) else None
        typed = bool(choice) and choice.get("choice") == "typed"
        standing = (
            choice is not None
            and choice.get("field") == row["field"]
            and choice.get("computed") == row["computed"]
            and (not typed or (row["key"] in TYPED and bool(row["computed"]) and typed_angle(choice.get("value")) is not None))
        )
        row["override"] = choice
        row["overrideStands"] = bool(standing)
        if standing and typed:
            row["printed"] = "typed"
        elif not row["computed"]:
            row["printed"] = "field" if row["field"] else None
        elif standing and choice.get("choice") == "field":
            row["printed"] = "field"
        else:
            row["printed"] = "computed"
        row["open"] = (row["disagrees"] or typed) and not standing
        out.append(row)
    return out


def open_count(field, snapshot, overrides):
    return sum(1 for row in compare(field, snapshot, overrides) if row["open"])


def clean_overrides(value, previous, field, snapshot, by_name, now=None):
    """The overrides to store from what the client sent: {key: {choice, reason, value}} (``value`` the angle, for a typed one). An
    entry whose choice, reason and value are as stored is kept as it was (who, when and the values it was made on); a new or changed
    one is stamped with who made it, when, and the two values it was made on. A stored choice on a value no longer compared (the
    approach's obstacle, before its angle) is let go. Raises ValueError for anything else."""
    if not isinstance(value, dict):
        raise ValueError("overrides must be an object")
    previous = previous if isinstance(previous, dict) else {}
    rows = {row["key"]: row for row in _rows(field, snapshot)}
    stamp = (now or datetime.utcnow()).replace(microsecond=0).isoformat() + "Z"
    out = {}
    for key, entry in value.items():
        if key not in KEYS:
            if key in previous:
                continue
            raise ValueError(f"there is no measured value called {key!r}")
        if not isinstance(entry, dict) or entry.get("choice") not in CHOICES:
            raise ValueError(f"{key}: the choice is 'computed', 'field' or 'typed'")
        reason = entry.get("reason") or ""
        if not isinstance(reason, str) or len(reason) > MAX_REASON:
            raise ValueError(f"{key}: the reason is text of at most {MAX_REASON} characters")
        reason = reason.strip()
        if entry["choice"] == "field" and not reason:
            raise ValueError(f"{key}: printing the field's value over the computed one needs a reason")
        angle = None
        if entry["choice"] == "typed":
            if key not in TYPED:
                raise ValueError(f"{key}: only the approach's angle can be typed")
            angle = typed_angle(entry.get("value"))
            if angle is None:
                raise ValueError(f"{key}: the angle is a number of degrees over 0 and under 90")
            if not reason:
                raise ValueError(f"{key}: an angle typed over the LiDAR's needs a reason")
            if not rows[key]["computed"]:
                raise ValueError(f"{key}: the LiDAR has no angle here to type over: type the survey profile instead")
        old = previous.get(key)
        if (isinstance(old, dict) and old.get("choice") == entry["choice"] and (old.get("reason") or "") == reason
                and old.get("value") == angle):
            out[key] = old
            continue
        row = rows[key]
        out[key] = {"choice": entry["choice"], "reason": reason, "by": by_name or "", "at": stamp,
                    "field": row["field"], "computed": row["computed"]}
        if angle is not None:
            out[key]["value"] = angle
    return out
