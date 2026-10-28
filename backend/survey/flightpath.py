"""How each way in and out is planned (docs/LZ_SURVEYS.md §16; the owner, 2026-10-27): at 30° where what stands allows it, else at the
LiDAR's shallowest clear angle, said to be steeper; or at the angle the survey's editor typed over the plan. Its descent begins where
that path is the survey's own approach height above the highest obstacle for good (survey/obstacles.py's ``descent_point``): the height
its profile gives (the old book's are 75 to 150 ft), 100 ft where it gives none, and 100 ft for the exit, which the survey gives none.

Worked out from the lane's 10 m steps whenever the page or the editor asks, never kept in the snapshot: the survey's height can be
retyped without measuring again.
"""

from __future__ import annotations

import math

from survey import compare, obstacles

PLAN_DEG = 30.0
DEFAULT_HEIGHT_FT = obstacles.AHO_FT


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def height_of(field):
    """The survey's own approach height above the highest obstacle, in feet: the first number in its profile's height as the comparison
    reads it, where that is over 0, else 100 ft."""
    typed = (field or {}).get("profileObstacleFt")
    numbers = compare.numbers_in(typed) if isinstance(typed, str) else []
    return numbers[0] if numbers and numbers[0] > 0 else DEFAULT_HEIGHT_FT


def plan(lane, height_ft=DEFAULT_HEIGHT_FT, typed=None):
    """One way's plan, from its lane as the snapshot keeps it: {angleDeg, minimumDeg, typed, steep, heightFt, descent}. ``angleDeg`` is
    30°, or the LiDAR's shallowest clear angle (``minimumDeg``) where that is steeper (``steep``), or the editor's ``typed`` angle;
    ``descent`` where a path at it is ``height_ft`` above the highest obstacle for good (``descent_point``). None where the LiDAR has
    no angle along the way (none measured, a lane from before version 5, no returns, no ground at the touchdown point)."""
    if not isinstance(lane, dict) or not isinstance(lane.get("start"), dict):
        return None
    clear = lane.get("clearance")
    if not isinstance(clear, dict) or not _finite(clear.get("angleDeg")):
        return None
    least = max(float(clear["angleDeg"]), 0.0)
    angle = typed if typed is not None else max(least, PLAN_DEG)
    bins = [b if isinstance(b, dict) else {} for b in lane.get("bins") or []]
    step = lane.get("binM") if _finite(lane.get("binM")) else obstacles.BIN_M
    descent = obstacles.descent_point(angle, lane["start"].get("groundFt"), [b.get("topFt") for b in bins],
                                      [b.get("groundFt") for b in bins], height_ft=height_ft, bin_m=step)
    return {"angleDeg": angle, "minimumDeg": least, "typed": typed is not None, "steep": typed is None and least > PLAN_DEG,
            "heightFt": height_ft, "descent": descent}


def typed_of(rows):
    """The angle the editor typed over the approach's plan, where that choice stands in ``compare``'s rows; else None."""
    row = next((r for r in rows or [] if r.get("key") == "approach"), None)
    if not row or row.get("printed") != "typed":
        return None
    return compare.typed_angle((row.get("override") or {}).get("value"))


def plans(field, snapshot, overrides, rows=None):
    """The approach's and the exit's plans for a survey as it stands: {approach, exit}, each None where there is none. The approach is
    planned from the survey's own height, at the angle the editor typed where that choice stands (``rows``, ``compare``'s, worked out
    here where not given)."""
    found = (snapshot or {}).get("obstacles")
    if not isinstance(found, dict) or found.get("status") != "measured":
        return {"approach": None, "exit": None}
    typed = typed_of(compare.compare(field, snapshot, overrides) if rows is None else rows)
    return {"approach": plan(found.get("approach"), height_of(field), typed), "exit": plan(found.get("exit"))}
