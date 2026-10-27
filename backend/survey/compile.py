"""A survey's page as data (docs/LZ_SURVEYS.md §5): every word and number the one-page PDF prints, worked out from a survey, or a
published revision, before anything is drawn. Pure: the route gathers the survey, its book and its place in the book; this decides
what is printed; ``figures`` and ``pages`` draw and lay it out.

What is printed where the field survey and EZ/PZ both have a value is ``compare``'s ``printed`` (§2: the computed value, unless an
admin chose the survey's). Judgements (best approach and exit, max A/C, surface, hazards, remarks, PPR) are the field's (§11, R3).
Nothing is made up for a value nobody has: it prints as a dash (AGENTS.md §14).
"""

from __future__ import annotations

import math
import re
from datetime import date, datetime

from survey import compare, measure, profile

PAGE_FORMAT = "ezpz-survey-page"
MONTHS = ("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
DASH = "—"
FEET_PER_METRE = 3.28084
STATUS_MARKS = {"draft": "DRAFT", "in_review": "IN REVIEW"}
# The slope map's bands, as terrain_provider._render_slope_png draws them.
SLOPE_LEGEND = "green under 3°, blue 3–6°, amber 6–10°, orange 10–15°, red 15° and over"
# A phone number in a PPR line: "706-864-3327 x5233", "(706) 864-3327", "DSN 312-367-1234".
_PHONE = re.compile(r"(?:DSN\s*)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]\d{4}(?:\s*(?:x|ext\.?)\s*\d+)?", re.IGNORECASE)
_NUMBER = re.compile(r"-?[0-9]+(?:\.[0-9]+)?")
_YEAR = re.compile(r"\b(19|20)(\d{2})\b")


def _text(value, limit=2000):
    return value.strip()[:limit] if isinstance(value, str) else ""


def _one_line(value):
    return re.sub(r"\s+", " ", _text(value))


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _get(mapping, *path):
    value = mapping
    for key in path:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def short_date(when):
    """26 OCT 26, as the page's header prints its date."""
    return f"{when.day:02d} {MONTHS[when.month - 1]} {when.year % 100:02d}"


def long_date(when):
    return f"{when.day:02d} {MONTHS[when.month - 1]} {when.year}"


def as_date(value):
    """A date from an ISO date or time, or None."""
    if isinstance(value, (date, datetime)):
        return value
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "")[:19])
    except ValueError:
        try:
            return datetime.strptime(value[:10], "%Y-%m-%d")
        except ValueError:
            return None


def heading_words(value):
    """A heading as the page prints it, "360°" (north is 360, as crews say it), or None."""
    heading = value if _finite(value) else measure.heading_of(value)
    if heading is None:
        return None
    whole = int(round(heading)) % 360
    return f"{whole or 360:03d}°"


def axis_words(axis):
    if not _finite(axis):
        return None
    first = int(round(axis)) % 180
    return f"{first:03d}°/{first + 180:03d}°"


def _ddm(value, positive, negative, digits):
    hemisphere = positive if value >= 0 else negative
    value = abs(value)
    degrees = int(math.floor(value))
    thousandths = int(round((value - degrees) * 60000))
    if thousandths >= 60000:
        degrees, thousandths = degrees + 1, thousandths - 60000
    return f"{hemisphere}{degrees:0{digits}d}°{thousandths // 1000:02d}.{thousandths % 1000:03d}′"


def lat_long_marks(lat, lon):
    """N34°35.579′ W084°07.402′: the computed lat/long with its marks, as the page prints it."""
    return f"{_ddm(lat, 'N', 'S', 2)} {_ddm(lon, 'E', 'W', 3)}"


def short_taken(text):
    """OCT 2023 as OCT 23, for a photo's caption; the text as it is otherwise."""
    text = _one_line(text).upper()
    return _YEAR.sub(lambda m: m.group(2), text) if text else ""


def split_ppr(text):
    """A PPR line as (who, number): "5th RTB Ops 706-864-3327 x5233" is ("5th RTB Ops", "706-864-3327 x5233"). With no number in it,
    the whole line is the number's place and nobody is named."""
    text = _one_line(text)
    match = _PHONE.search(text)
    if not match:
        return "", text
    who = text[:match.start()].strip(" :-–,")
    return who, text[match.start():].strip()


def _first_number(text):
    match = _NUMBER.search((text or "").replace("−", "-"))
    return float(match.group()) if match else None


def _feet_words(value):
    return f"{int(round(value)):,}′"


def _printed(rows, key):
    row = next((r for r in rows if r["key"] == key), None)
    return (row or {}).get("printed"), row


def _fact(value, source):
    return {"value": value if value else DASH, "from": source if value else None}


def _grid_fact(rows, field, snapshot):
    printed, _row = _printed(rows, "grid")
    if printed == "computed":
        return _fact(snapshot.get("grid"), "computed")
    if printed == "field":
        return _fact(_one_line(field.get("grid")).upper(), "field")
    return _fact(None, None)


def _lat_long_fact(rows, field, snapshot):
    printed, _row = _printed(rows, "latLong")
    target = snapshot.get("target") if isinstance(snapshot.get("target"), dict) else {}
    if printed == "computed" and _finite(target.get("lat")) and _finite(target.get("lon")):
        return _fact(lat_long_marks(target["lat"], target["lon"]), "computed")
    if printed == "field":
        return _fact(_one_line(field.get("latLong")).upper(), "field")
    return _fact(None, None)


def _elevation_fact(rows, field, snapshot):
    printed, _row = _printed(rows, "elevation")
    if printed == "computed" and _finite(_get(snapshot, "elevation", "ft")):
        return _fact(_feet_words(snapshot["elevation"]["ft"]), "computed")
    if printed == "field":
        number = _first_number(field.get("elevationFt"))
        return _fact(_feet_words(number) if number is not None else _one_line(field.get("elevationFt")), "field")
    return _fact(None, None)


def _size(rows, field, snapshot):
    printed, _row = _printed(rows, "size")
    size = snapshot.get("size") if isinstance(snapshot.get("size"), dict) else None
    if printed == "computed" and size:
        return {"value": f"{size['lengthFt']:,}′ × {size['widthFt']:,}′", "from": "computed"}
    if printed == "field":
        return {"value": _one_line(field.get("size")), "from": "field"}
    return {"value": None, "from": None}


def _slope_fact(rows, field, snapshot):
    printed, _row = _printed(rows, "slope")
    slope = snapshot.get("slope") if isinstance(snapshot.get("slope"), dict) else None
    if printed == "field":
        return _fact(_one_line(field.get("slope")), "field")
    if slope and slope.get("call") in ("within", "exceeded") and all(isinstance(slope.get(k), dict) for k in ("noseUp", "noseDown", "cross")):
        words = (f"up {slope['noseUp']['p95']:.1f}° · down {slope['noseDown']['p95']:.1f}° · cross {slope['cross']['p95']:.1f}° (P95)")
        return _fact(words, "computed")
    if slope and isinstance(slope.get("overall"), dict):
        return _fact(f"{slope['overall']['p95']:.1f}° steepest way (P95)", "computed")
    return _fact(None, None)


def _hazards(field, snapshot):
    """The computed slope call first, then the field's hazards (§5)."""
    out = []
    slope = snapshot.get("slope") if isinstance(snapshot.get("slope"), dict) else None
    if slope and slope.get("call") == "exceeded":
        heading = heading_words(slope.get("headingDeg"))
        parts = [f"{measure.LIMIT_WORDS[k]} {slope[k]['p95']:.1f}° over the {measure.LIMITS_DEG[k]:g}° limit"
                 for k in slope.get("exceeded") or [] if isinstance(slope.get(k), dict)]
        if parts:
            out.append({"text": f"Slope at {heading} (95th percentile): {'; '.join(parts)}.", "from": "computed"})
    typed = _one_line(field.get("hazards"))
    if typed:
        out.append({"text": typed if typed.endswith(".") else typed + ".", "from": "field"})
    return out


def _notes(field, snapshot):
    """The numbered notes: the field's warning and remarks (one a line), its max weight, then what the page needs said."""
    notes = []
    warning = _one_line(field.get("warning"))
    if warning:
        notes.append(warning)
    for line in (field.get("remarks") or "").splitlines() if isinstance(field.get("remarks"), str) else []:
        line = _one_line(line)
        if line:
            notes.append(line)
    weight = _one_line(field.get("maxWeight"))
    if weight:
        notes.append(f"Max weight: {weight}.")
    fit = snapshot.get("fit") if isinstance(snapshot.get("fit"), dict) else None
    if fit and isinstance(fit.get("count"), int):
        notes.append(f"Area fits {fit['count']} × {fit.get('aircraft') or 'UH-60L'} by LZ/PZ geometry; Max A/C governs.")
    interval = _get(snapshot, "terrain", "contours", "intervalFt")
    if snapshot.get("terrain"):
        contours = f"{interval:g}′ contours; " if _finite(interval) else ""
        notes.append(f"Plan: slope {SLOPE_LEGEND}; {contours}LZ boundary dashed.")
    return notes


def _shape(snapshot, boundary):
    """The boundary in metres east and north of its middle, for the compass rose; and its size and area in words."""
    size = snapshot.get("size") if isinstance(snapshot.get("size"), dict) else None
    if not boundary:
        return None
    lat0 = sum(p[0] for p in boundary) / len(boundary)
    lon0 = sum(p[1] for p in boundary) / len(boundary)
    k = math.cos(math.radians(lat0))
    xy = [[(lon - lon0) * 111320.0 * k, (lat - lat0) * 111320.0] for lat, lon in boundary]
    return {
        "xy": xy,
        "size": f"LZ {size['lengthFt']:,}′ × {size['widthFt']:,}′" if size else None,
        "area": f"{size['areaAcres']:.1f} AC · TRUE N" if size and _finite(size.get("areaAcres")) else "TRUE N",
    }


def _photos(assets, field):
    """The north photo and the one down the best approach, each the first of its kind; another photo where one is missing."""
    photos = [a for a in assets if a.get("kind") in ("photo_north", "photo_approach", "photo_other")]
    north = next((a for a in photos if a["kind"] == "photo_north"), None)
    approach = next((a for a in photos if a["kind"] == "photo_approach"), None)
    others = [a for a in photos if a["kind"] == "photo_other"]
    out = []
    when_default = short_taken(field.get("surveyedOn"))
    for asset, label in ((north, "NORTH"), (approach, None)):
        if asset is None and others:
            asset = others.pop(0)
            label = None
        if asset is None:
            continue
        if label is None:
            heading = heading_words(asset.get("heading_deg")) or heading_words(field.get("approachDeg"))
            label = (f"APCH {heading}" if asset["kind"] == "photo_approach" else _one_line(asset.get("caption")).upper()[:28]) or "PHOTO"
        when = short_taken(asset.get("taken_on")) or when_default
        out.append({"assetId": asset["id"], "caption": f"{label} · {when}" if when else label})
    return out


def _view3d(assets):
    capture = next((a for a in assets if a.get("kind") == "view_3d"), None)
    if capture is None:
        return None
    heading = capture.get("heading_deg")
    caption = f"3D · LOOKING {int(round(heading)) % 360:03d}°T" if _finite(heading) else "3D"
    return {"assetId": capture["id"], "caption": caption}


def _rise_words(line):
    if line["lz"] is None:
        return "MISSES THE LZ"
    if line["changeFt"] is None:
        return "NO HEIGHT AT AN EDGE OF THE LZ"
    feet = profile.js_round(abs(line["changeFt"]))
    if feet < profile.LEVEL_FT:
        return "LEVEL THROUGH LZ"
    return f"{'RISES' if line['changeFt'] > 0 else 'FALLS'} {feet}′ THROUGH LZ · {profile.to_fixed(line['averageDeg'], 1)}° AVG"


def _profiles(snapshot, approach):
    profiles = _get(snapshot, "terrain", "profiles")
    heading = measure.heading_of(approach) if isinstance(approach, str) else None
    found = profile.slope_profile_for(profiles, heading)
    if found is None:
        return None
    along, across = found["along"], found["across"]
    return {
        "along": {**along, "title": f"PROFILE ALONG {heading_words(along['heading'])} · {_rise_words(along)}",
                  "start": "APCH END", "end": "DEP END"},
        "across": {**across, "title": f"ACROSS · {_rise_words(across)}",
                   "start": heading_words((across["heading"] + 180) % 360), "end": heading_words(across["heading"])},
    }


def _terrain_table(snapshot, profiles):
    slope = snapshot.get("slope") if isinstance(snapshot.get("slope"), dict) else None
    if not slope or not isinstance(slope.get("overall"), dict):
        return None
    heading = heading_words(slope.get("headingDeg"))
    columns = [("noseUp", "Nose up"), ("noseDown", "Nose down"), ("cross", "Cross"), ("overall", "Any way")]

    def cell(key, stat):
        value = _get(slope, key, stat)
        return f"{value:.1f}°" if _finite(value) else DASH

    rows = [
        {"label": "95th percentile", "cells": [cell(k, "p95") for k, _ in columns], "strong": True},
        {"label": "Average", "cells": [cell(k, "mean") for k, _ in columns]},
        {"label": "Steepest cell", "cells": [cell(k, "max") for k, _ in columns]},
        {"label": "UH-60 limit, no wind", "cells": [f"{measure.LIMITS_DEG[k]:g}°" if k in measure.LIMITS_DEG else DASH for k, _ in columns]},
    ]
    resolution = slope.get("resolutionM")
    source = "LOCAL DEM" if slope.get("source") == "local" else "TERRARIUM" if slope.get("source") else "DEM"
    elevation = snapshot.get("elevation") if isinstance(snapshot.get("elevation"), dict) else {}
    datum = elevation.get("verticalDatum")
    # A local DEM's datum is a name (NAVD88); Terrarium's is a sentence, which the title has no room for.
    datum = datum.upper() if isinstance(datum, str) and len(datum) <= 12 else None
    title = f"TERRAIN · {source}" + (f" {resolution:g} M" if _finite(resolution) else "") + (f" · {datum}" if datum else "")
    rise = None
    if profiles:
        parts = []
        for key, name in (("along", "along"), ("across", "across")):
            line = profiles[key]
            if line["changeFt"] is not None and line["lz"] is not None:
                parts.append(f"{name} {'+' if line['changeFt'] > 0 else '−' if line['changeFt'] < 0 else ''}"
                             f"{profile.js_round(abs(line['changeFt']))}′ ({profile.to_fixed(line['averageDeg'], 1)}°)")
        rise = "Rise through the LZ: " + ", ".join(parts) if parts else None
    return {
        "title": title,
        "heading": f"Slope at {heading}" if heading else "Slope (no best approach)",
        "columns": [name for _key, name in columns],
        "rows": rows,
        "call": slope.get("words"),
        "rise": rise,
        "dem": ", ".join(elevation.get("dem") or []) or None,
    }


def _survey_profile(field):
    feet = _first_number(field.get("profileObstacleFt"))
    metres = _first_number(field.get("profileDistanceM"))
    if feet is None or metres is None or feet <= 0 or metres <= 0:
        return None
    angle = math.degrees(math.atan2(feet * 0.3048, metres))
    who = " · ".join(t for t in (_one_line(field.get("surveyedOn")), _one_line(field.get("surveyedBy"))) if t).upper()
    return {"obstacleFt": int(round(feet)), "distanceM": int(round(metres)), "angleDeg": round(angle, 1), "who": who}


def _signoff_words(signoff):
    if not isinstance(signoff, dict):
        return None
    who = " ".join(t for t in (_one_line(signoff.get("rank")).upper(), _one_line(signoff.get("name")).upper()) if t)
    title = _one_line(signoff.get("title")).upper()
    when = _one_line(signoff.get("date")).upper()
    if not who:
        return None
    return "APPROVED: " + ", ".join(t for t in (who, title) if t) + (f" · {when}" if when else "")


def compile_page(source, *, book, page=None, version="", printed_on=None, opacity=55):
    """The page's model. ``source``: hlzId, name, status, revision (REV n, published only) or nextRevision, field, overrides, diagram,
    snapshot, assets (dicts with id, kind, caption, heading_deg, taken_on), signoff, changes. ``book``: name, unitLine. ``page``: n
    and of, or None outside the book. ``printed_on``: the date printed (a published revision's is its own)."""
    field = source.get("field") if isinstance(source.get("field"), dict) else {}
    snapshot = source.get("snapshot") if isinstance(source.get("snapshot"), dict) else {}
    diagram = source.get("diagram") if isinstance(source.get("diagram"), dict) else {}
    assets = [a for a in source.get("assets") or [] if isinstance(a, dict) and isinstance(a.get("id"), int)]
    rows = compare.compare(field, snapshot or None, source.get("overrides") or {})
    printed_on = printed_on or datetime.utcnow()
    status = source.get("status") if source.get("status") in ("draft", "in_review", "published") else "draft"
    revision = source.get("revision") if status == "published" else source.get("nextRevision")
    boundary = measure.boundary_of(diagram)
    target = measure.target_of(diagram)
    approach = _text(field.get("approachDeg"))
    exit_ = _text(field.get("exitDeg"))
    who, number = split_ppr(field.get("ppr"))
    day, night = _one_line(field.get("maxAcDay")), _one_line(field.get("maxAcNight"))
    max_ac = " / ".join(t for t in ((f"{day} DAY" if day else ""), (f"{night} NIGHT" if night else "")) if t)
    size = _size(rows, field, snapshot)
    profiles = _profiles(snapshot, approach)
    elevation = _elevation_fact(rows, field, snapshot)
    name = _one_line(source.get("name")).upper()
    short_name = re.sub(r"^(LZ|PZ|HLZ)\s+", "", name) or name

    return {
        "format": PAGE_FORMAT,
        "hlzId": _one_line(source.get("hlzId")).upper(),
        "name": name,
        "book": {"name": _one_line(book.get("name")).upper(), "unitLine": _one_line(book.get("unitLine")).upper()},
        "status": status,
        "mark": STATUS_MARKS.get(status),
        "revision": f"REV {revision}" if isinstance(revision, int) and revision > 0 else None,
        "printedOn": short_date(printed_on),
        "printedLong": long_date(printed_on),
        "page": page if isinstance(page, dict) and isinstance(page.get("n"), int) else None,
        "version": version,
        "facts": {
            "grid": _grid_fact(rows, field, snapshot),
            "latLong": _lat_long_fact(rows, field, snapshot),
            "pprWho": who,
            "ppr": number or DASH,
            "approach": heading_words(approach) or DASH,
            "exit": heading_words(exit_) or DASH,
            "axis": axis_words(_get(snapshot, "size", "axisDeg")) or DASH,
            "maxAc": max_ac.upper() or DASH,
            "elevation": elevation,
            "surface": _one_line(field.get("surface")).upper() or DASH,
            "slope": _slope_fact(rows, field, snapshot),
            "slopeHeading": heading_words(approach),
        },
        "hazards": _hazards(field, snapshot),
        "notes": _notes(field, snapshot),
        "shape": _shape(snapshot, boundary),
        "size": size,
        "plan": {
            "target": {"lat": target[0], "lon": target[1]} if target else None,
            "boundary": boundary,
            "approachDeg": measure.heading_of(approach),
            "exitDeg": measure.heading_of(exit_),
            "approachWords": heading_words(approach),
            "exitWords": heading_words(exit_),
            "contours": _get(snapshot, "terrain", "contours"),
            "overlay": _get(snapshot, "terrain", "overlay"),
            "overlayBounds": _get(snapshot, "terrain", "bounds"),
            "opacity": max(10, min(100, int(opacity))) / 100 if _finite(opacity) else 0.55,
            "label": short_name,
            "sublabel": " ".join(t for t in (_one_line(source.get("hlzId")).upper(), elevation["value"] if elevation["value"] != DASH else "") if t),
        } if target else None,
        "view3d": _view3d(assets),
        "photos": _photos(assets, field),
        "profiles": profiles,
        "terrain": _terrain_table(snapshot, profiles),
        "surveyProfile": _survey_profile(field),
        "clearance": None,
        "comparisons": [{"key": r["key"], "label": r["label"], "printed": r["printed"]} for r in rows],
        "problems": [p for p in snapshot.get("problems") or [] if isinstance(p, str)],
        "measured": bool(snapshot),
        "footer": {
            "changes": _one_line(source.get("changes")),
            "signoff": _signoff_words(source.get("signoff")),
        },
    }
