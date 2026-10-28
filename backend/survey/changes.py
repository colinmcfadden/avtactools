"""A published revision's CHANGES line (docs/LZ_SURVEYS.md §3.4), suggested from what is printed: for the first revision, how the page
differs from the field survey the old book held ("New format. Grid refined; lat/long recomputed; elevation, size and slope from
LZ/PZ."); for a later one, what changed from the revision published last. The publishing admin can edit it before it is kept.
"""

from __future__ import annotations

from survey import compare
from survey.compile import compile_page

MAX_CHANGES = 500

# What a reader would call each printed fact, in the order the page prints them.
FACTS = (
    ("grid", "grid"), ("latLong", "lat/long"), ("ppr", "PPR"), ("approach", "best approach"), ("exit", "best exit"),
    ("axis", "LZ axis"), ("maxAc", "max A/C"), ("elevation", "elevation"), ("surface", "surface"), ("slope", "slope"),
)


def _join(words):
    words = list(words)
    if len(words) <= 1:
        return "".join(words)
    return ", ".join(words[:-1]) + " and " + words[-1]


def _sentence(parts):
    text = "; ".join(p for p in parts if p)
    return text[:1].upper() + text[1:] + "." if text else ""


def first(field, snapshot, overrides):
    """REV 1: the page against the book's field survey."""
    rows = {r["key"]: r for r in compare.compare(field or {}, snapshot or None, overrides or {})}
    parts = []
    for key, refined, new in (("grid", "grid refined", "grid from LZ/PZ"), ("latLong", "lat/long recomputed", "lat/long from LZ/PZ")):
        row = rows[key]
        if row["printed"] == "computed":
            parts.append(refined if row["field"] else new)
    measured = [rows[k]["label"].lower() for k in ("elevation", "size", "slope") if rows[k]["printed"] == "computed"]
    if measured:
        parts.append(f"{_join(measured)} from LZ/PZ")
    kept = [rows[k]["label"].lower() for k in compare.KEYS if rows[k]["printed"] == "field" and rows[k]["computed"]]
    if kept:
        parts.append(f"the survey's {_join(kept)} kept")
    return ("New format. " + _sentence(parts)).strip()[:MAX_CHANGES]


def _value(fact):
    return fact.get("value") if isinstance(fact, dict) else fact


def later(previous, current, book):
    """REV n: what is printed differently from the revision published last. ``previous`` and ``current`` are compile sources."""
    before = compile_page(previous, book=book)
    after = compile_page(current, book=book)
    changed = [name for key, name in FACTS if _value(before["facts"].get(key)) != _value(after["facts"].get(key))]
    if (before["size"] or {}).get("value") != (after["size"] or {}).get("value"):
        changed.append("size")
    if (before["hlzId"], before["name"]) != (after["hlzId"], after["name"]):
        changed.insert(0, "name")
    parts = [f"{_join(changed)} changed"] if changed else []
    if [h["text"] for h in before["hazards"]] != [h["text"] for h in after["hazards"]]:
        parts.append("hazards updated")
    if before["notes"] != after["notes"]:
        parts.append("notes updated")
    if [p["assetId"] for p in before["photos"]] != [p["assetId"] for p in after["photos"]]:
        parts.append("new photos")
    if (before["view3d"] or {}).get("assetId") != (after["view3d"] or {}).get("assetId"):
        parts.append("new 3D view")
    if (previous.get("snapshot") or {}).get("measuredAt") != (current.get("snapshot") or {}).get("measuredAt"):
        parts.append("measured again")
    return (_sentence(parts) or "Reissued; nothing printed changed.")[:MAX_CHANGES]
