"""The slope profile as the web reads it: feature/terrain/groundProfile.js, function by function, so the page prints the line the
editor and the LZ/PZ panel draw (docs/LZ_SURVEYS.md §5). Held to contracts/fixtures/planning/profile.json, as the native apps are.

``profiles`` is /api/terrain-analysis's (a line through the LZ's middle every ``headingStepDeg``, kept in the survey's snapshot).
"""

from __future__ import annotations

import math
from decimal import ROUND_HALF_UP, Decimal

FEET_PER_METRE = 3.28084
# Under this many feet of rise or fall through the LZ, it is called level.
LEVEL_FT = 1


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def js_round(value):
    """JavaScript's Math.round: halves up, never to even."""
    return math.floor(value + 0.5)


def to_fixed(value, digits):
    """JavaScript's toFixed for a number at or above zero: the exact binary value, a tie rounded up."""
    return str(Decimal(value).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP))


def heading_text(heading):
    return f"{str(heading).rjust(3, '0')}°"


def _readable(profiles):
    if not isinstance(profiles, dict):
        return None
    step_m, half_m, heading_step = profiles.get("stepM"), profiles.get("halfLengthM"), profiles.get("headingStepDeg")
    if not _is_number(step_m) or step_m <= 0 or not _is_number(half_m):
        return None
    if not isinstance(heading_step, int) or isinstance(heading_step, bool) or heading_step <= 0:
        return None
    lines = profiles.get("lines")
    if not isinstance(lines, list) or not lines:
        return None
    return profiles


def _height_at(elevations, step_m, half_m, at_m):
    at = (at_m + half_m) / step_m
    i = math.floor(at)
    if i < 0 or i >= len(elevations):
        return None
    if i == len(elevations) - 1:
        return elevations[i] if _is_number(elevations[i]) else None
    a, b = elevations[i], elevations[i + 1]
    if not _is_number(a) or not _is_number(b):
        return None
    return a + (b - a) * (at - i)


def profile_line(profiles, heading):
    """The ground along the line through the LZ's middle at ``heading`` (true): the measured line nearest it, as ``heading`` (0..359),
    ``distancesFt``, ``elevationsFt`` (None where there is no height), ``lz`` (the boundary's span in feet, or None), ``changeFt`` and
    ``averageDeg`` (the rise or fall through the LZ and its average slope, None when an end has no height). None when it is not there."""
    p = _readable(profiles)
    if p is None or not _is_number(heading):
        return None
    step = p["headingStepDeg"]
    nearest = (js_round(heading / step) * step) % 360
    reversed_ = nearest >= 180
    base = nearest - 180 if reversed_ else nearest
    line = next((l for l in p["lines"] if isinstance(l, dict) and _is_number(l.get("heading")) and l.get("heading") == base), None)
    if line is None or not isinstance(line.get("elevationsFt"), list) or len(line["elevationsFt"]) < 2:
        return None
    count = len(line["elevationsFt"])
    heights = [h if _is_number(h) else None for h in line["elevationsFt"]]
    elevations = list(reversed(heights)) if reversed_ else heights
    half = p["stepM"] * (count - 1) / 2
    distances_ft = [(i * p["stepM"] - half) * FEET_PER_METRE for i in range(count)]
    span = line.get("lz")
    span = span if isinstance(span, list) and len(span) == 2 and all(_is_number(v) for v in span) else None
    lz_m = ([-span[1], -span[0]] if reversed_ else [span[0], span[1]]) if span else None
    change_ft = average_deg = None
    if lz_m and lz_m[1] > lz_m[0]:
        entry = _height_at(elevations, p["stepM"], half, lz_m[0])
        exit_ = _height_at(elevations, p["stepM"], half, lz_m[1])
        if entry is not None and exit_ is not None:
            change_ft = exit_ - entry
            average_deg = math.degrees(math.atan2(abs(change_ft), (lz_m[1] - lz_m[0]) * FEET_PER_METRE))
    return {
        "heading": nearest,
        "distancesFt": distances_ft,
        "elevationsFt": elevations,
        "lz": [lz_m[0] * FEET_PER_METRE, lz_m[1] * FEET_PER_METRE] if lz_m else None,
        "changeFt": change_ft,
        "averageDeg": average_deg,
    }


def change_words(line):
    if line["lz"] is None:
        return "the line misses the LZ"
    if line["changeFt"] is None:
        return "no height at an edge of the LZ"
    feet = js_round(abs(line["changeFt"]))
    if feet < LEVEL_FT:
        return "level through the LZ"
    return f"{'rises' if line['changeFt'] > 0 else 'falls'} {feet} ft through the LZ, {to_fixed(line['averageDeg'], 1)}° on average"


def slope_profile_for(profiles, heading):
    """The profile ``along`` the heading (the approach behind the middle, the departure ahead) and ``across`` it (left to right), each
    with its ``words``. None with no heading or no profiles."""
    if heading is None:
        return None
    along = profile_line(profiles, heading)
    across = profile_line(profiles, heading + 90)
    if along is None or across is None:
        return None
    return {
        "along": {**along, "words": f"Along {heading_text(along['heading'])}: {change_words(along)}"},
        "across": {**across, "words": f"Across, left to right: {change_words(across)}"},
    }
