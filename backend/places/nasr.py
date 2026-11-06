"""The FAA's NASR subscription, read into places (docs/PLACE_SEARCH.md §4.1).

NASR's CSV extract comes as one zip per area: ``<DD>_<Mon>_<YYYY>_APT_CSV.zip`` holds ``APT_BASE.csv``,
``…_NAV_CSV.zip`` ``NAV_BASE.csv`` and ``…_FIX_CSV.zip`` ``FIX_BASE.csv``. The all-areas zip holds them
too, so any zip that has one of the three files is read for it. Each row becomes a place dict (``PLACE_FIELDS``).

A row with no usable position is left out, never put at 0°, 0°: a place in the wrong spot is worse than none
(AGENTS.md §14).
"""

import csv
import io
import math
import zipfile


PLACE_FIELDS = ("id", "source", "kind", "detail", "name", "ident", "icao", "city", "county", "state", "lat", "lon",
                "elev_ft", "use", "status", "military", "rank")

# APT_BASE's SITE_TYPE_CODE.
SITE_KINDS = {
    "A": ("airport", "Airport"),
    "H": ("heliport", "Heliport"),
    "C": ("seaplane_base", "Seaplane base"),
    "U": ("ultralight", "Ultralight park"),
    "G": ("gliderport", "Gliderport"),
    "B": ("balloonport", "Balloonport"),
}
# OWNERSHIP_TYPE_CODE: the military owners, said in place of "private" (every one is private use).
MILITARY = {"MA": "Air Force", "MN": "Navy", "MR": "Army", "CG": "Coast Guard"}

# NAV_BASE's NAV_TYPE, in the words a crew reads on a chart.
NAV_KINDS = {
    "VOR": ("vor", "VOR"),
    "VOR/DME": ("vordme", "VOR/DME"),
    "VORTAC": ("vortac", "VORTAC"),
    "TACAN": ("tacan", "TACAN"),
    "DME": ("dme", "DME"),
    "NDB": ("ndb", "NDB"),
    "NDB/DME": ("ndbdme", "NDB/DME"),
    "MARINE NDB": ("ndb", "Marine NDB"),
    "VOT": ("vot", "VOT"),
    "FAN MARKER": ("marker", "Fan marker"),
}

# A smaller number comes first among places as far away and as good a match (search.py): what a crew is most
# likely after. Closed places are put after open ones by the search itself, not here.
RANK_PUBLIC = 0
RANK_MILITARY = 1
RANK_PRIVATE = 2
RANK_NAVAID = 3
RANK_FIX = 4

FILES = ("APT_BASE.csv", "NAV_BASE.csv", "FIX_BASE.csv")


class NasrError(ValueError):
    """A NASR file this cannot read: the wrong columns, or none of the files it needs."""


def _number(text):
    try:
        value = float(text)
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


def _position(row, lat_key="LAT_DECIMAL", lon_key="LONG_DECIMAL"):
    lat, lon = _number(row.get(lat_key)), _number(row.get(lon_key))
    if lat is None or lon is None or not -90 <= lat <= 90 or not -180 <= lon <= 180:
        return None
    if lat == 0 and lon == 0:
        return None
    return lat, lon


def _elevation(text):
    value = _number(text)
    return None if value is None else int(round(value))


def _clean(text):
    return " ".join(str(text or "").split())


def _require(reader, name, columns):
    missing = [c for c in columns if c not in (reader.fieldnames or ())]
    if missing:
        raise NasrError(f"{name} has no column {', '.join(missing)}: is this a NASR CSV extract?")


def airports(lines):
    """Places from ``APT_BASE.csv``: airports, heliports, seaplane bases and the other landing sites."""
    reader = csv.DictReader(lines)
    _require(reader, "APT_BASE.csv", ("SITE_NO", "SITE_TYPE_CODE", "ARPT_ID", "ARPT_NAME", "LAT_DECIMAL",
                                      "LONG_DECIMAL", "FACILITY_USE_CODE", "OWNERSHIP_TYPE_CODE", "ARPT_STATUS"))
    for row in reader:
        at = _position(row)
        kind = SITE_KINDS.get(_clean(row.get("SITE_TYPE_CODE")))
        ident = _clean(row.get("ARPT_ID"))
        if at is None or kind is None or not ident:
            continue
        military = MILITARY.get(_clean(row.get("OWNERSHIP_TYPE_CODE")))
        use = "public" if _clean(row.get("FACILITY_USE_CODE")) == "PU" else "private"
        status = "closed" if _clean(row.get("ARPT_STATUS")) in ("CI", "CP") else "open"
        words = [kind[1]]
        if military:
            words.append(military)
        elif use == "private":
            words.append("private")
        if status == "closed":
            words.append("closed")
        rank = RANK_MILITARY if military else RANK_PUBLIC if use == "public" else RANK_PRIVATE
        yield {
            "id": f"nasr:apt:{_clean(row.get('SITE_NO')) or ident}",
            "source": "nasr",
            "kind": kind[0],
            "detail": ", ".join(words),
            "name": _clean(row.get("ARPT_NAME")) or None,
            "ident": ident,
            "icao": _clean(row.get("ICAO_ID")) or None,
            "city": _clean(row.get("CITY")) or None,
            "state": _clean(row.get("STATE_CODE")) or None,
            "lat": at[0],
            "lon": at[1],
            "elev_ft": _elevation(row.get("ELEV")),
            "use": use,
            "status": status,
            "military": military,
            "rank": rank,
        }


def navaids(lines):
    """Places from ``NAV_BASE.csv``: VORs, TACANs, DMEs, NDBs and the rest."""
    reader = csv.DictReader(lines)
    _require(reader, "NAV_BASE.csv", ("NAV_ID", "NAV_TYPE", "NAME", "LAT_DECIMAL", "LONG_DECIMAL", "NAV_STATUS"))
    for row in reader:
        at = _position(row)
        kind = NAV_KINDS.get(_clean(row.get("NAV_TYPE")))
        ident = _clean(row.get("NAV_ID"))
        if at is None or kind is None or not ident:
            continue
        status = "closed" if _clean(row.get("NAV_STATUS")) == "SHUTDOWN" else "open"
        detail = kind[1] + (", shut down" if status == "closed" else "")
        yield {
            "id": f"nasr:nav:{ident}:{kind[0]}:{_clean(row.get('STATE_CODE')) or _clean(row.get('COUNTRY_CODE'))}",
            "source": "nasr",
            "kind": kind[0],
            "detail": detail,
            "name": _clean(row.get("NAME")) or None,
            "ident": ident,
            "icao": None,
            "city": _clean(row.get("CITY")) or None,
            "state": _clean(row.get("STATE_CODE")) or None,
            "lat": at[0],
            "lon": at[1],
            "elev_ft": _elevation(row.get("ELEV")),
            "use": "public" if _clean(row.get("PUBLIC_USE_FLAG")) == "Y" else "private",
            "status": status,
            "military": None,
            "rank": RANK_NAVAID,
        }


def fixes(lines):
    """Places from ``FIX_BASE.csv``: fixes and VFR waypoints, which have an identifier and no name."""
    reader = csv.DictReader(lines)
    _require(reader, "FIX_BASE.csv", ("FIX_ID", "LAT_DECIMAL", "LONG_DECIMAL", "FIX_USE_CODE"))
    for row in reader:
        at = _position(row)
        ident = _clean(row.get("FIX_ID"))
        if at is None or not ident:
            continue
        vfr = _clean(row.get("FIX_USE_CODE")) == "VFR"
        yield {
            "id": f"nasr:fix:{ident}:{_clean(row.get('ICAO_REGION_CODE')) or _clean(row.get('STATE_CODE'))}",
            "source": "nasr",
            "kind": "vfr_waypoint" if vfr else "fix",
            "detail": "VFR waypoint" if vfr else "Fix",
            "name": None,
            "ident": ident,
            "icao": None,
            "city": None,
            "state": _clean(row.get("STATE_CODE")) or None,
            "lat": at[0],
            "lon": at[1],
            "elev_ft": None,
            "use": None,
            "status": "open",
            "military": None,
            "rank": RANK_FIX,
        }


READERS = {"APT_BASE.csv": airports, "NAV_BASE.csv": navaids, "FIX_BASE.csv": fixes}


def _members(archive):
    """The NASR files in ``archive`` by their base name, wherever in it they are."""
    found = {}
    for info in archive.infolist():
        base = info.filename.rsplit("/", 1)[-1]
        if base in READERS and base not in found:
            found[base] = info
    return found


def effective_date(zips):
    """The cycle's effective date (``YYYY-MM-DD``) as its rows say it (``EFF_DATE``), or None."""
    for path in zips:
        with zipfile.ZipFile(path) as archive:
            for info in _members(archive).values():
                with archive.open(info) as raw:
                    reader = csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", errors="replace"))
                    for row in reader:
                        text = _clean(row.get("EFF_DATE"))
                        if len(text) == 10 and text[4] == "/" and text[7] == "/":
                            return text.replace("/", "-")
                        break
    return None


def read(zips):
    """Every place in the NASR CSV zips ``zips`` (paths), each a dict of ``PLACE_FIELDS``, ids unique.

    Raises NasrError when the three files are not all there, or one has the wrong columns.
    """
    places, seen, read_files = [], {}, set()
    for path in zips:
        with zipfile.ZipFile(path) as archive:
            for base, info in _members(archive).items():
                if base in read_files:
                    continue
                read_files.add(base)
                with archive.open(info) as raw:
                    lines = io.TextIOWrapper(raw, encoding="utf-8-sig", errors="replace", newline="")
                    for place in READERS[base](lines):
                        # Two rows the FAA keeps apart can come to one id (a navaid's ident, type and state); number the rest.
                        count = seen.get(place["id"], 0)
                        seen[place["id"]] = count + 1
                        if count:
                            place["id"] = f"{place['id']}:{count + 1}"
                        places.append(place)
    missing = [name for name in FILES if name not in read_files]
    if missing:
        raise NasrError(f"No {', '.join(missing)} in the NASR files given.")
    return places

