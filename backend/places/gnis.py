"""USGS's Geographic Names Information System, read into places (docs/PLACE_SEARCH.md §4.2).

GNIS's national file, ``DomesticNames_AllStates_Text.zip``, holds one pipe-delimited UTF-8 file per state and
territory (``Text/DomesticNames_GA.txt``…). A feature that reaches into a neighbour is listed in both states'
files under one ``feature_id``, so each is read once. Every class is taken but *Civil* and *Census*, which are
administrative areas that repeat what *Populated Place* says (*City of Blairsville* beside *Blairsville*; the
owner's answer to §12 Q4).

GNIS gives no elevation (its current files dropped it in 2021), so none is made up. A feature with no usable
position is left out, never put at 0°, 0° (AGENTS.md §14). A name GNIS marks ``(historical)`` is a place that
was, and is put after the places that are, as a closed airport is.
"""

import csv
import io
import zipfile

from .nasr import _clean, _number

# Ranks after NASR's (nasr.RANK_*): an airfield or navaid before a name, then what a crew most likely means.
RANK_PLACE = 5      # populated places and military installations
RANK_TERRAIN = 6    # summits, gaps, ridges and the rest of the land
RANK_WATER = 7      # lakes, reservoirs, bays and the rest of the water
RANK_STREAM = 8     # streams and canals: 250,000 of them, so after everything else of the same name and distance

LEFT_OUT = frozenset({"Civil", "Census"})
_PLACES = frozenset({"Populated Place", "Military", "Crossing"})
_WATER = frozenset({"Reservoir", "Lake", "Spring", "Bay", "Swamp", "Falls", "Rapids", "Gut", "Channel", "Sea",
                    "Glacier"})
_STREAMS = frozenset({"Stream", "Canal", "Arroyo"})

COLUMNS = ("feature_id", "feature_name", "feature_class", "state_name", "county_name", "prim_lat_dec",
           "prim_long_dec")
# The FILE every state's text sits in, and the one member that says when the file was made.
MEMBER_PREFIX = "DomesticNames_"

STATES = {
    "Alabama": "AL", "Alaska": "AK", "Arizona": "AZ", "Arkansas": "AR", "California": "CA", "Colorado": "CO",
    "Connecticut": "CT", "Delaware": "DE", "District of Columbia": "DC", "Florida": "FL", "Georgia": "GA",
    "Hawaii": "HI", "Idaho": "ID", "Illinois": "IL", "Indiana": "IN", "Iowa": "IA", "Kansas": "KS",
    "Kentucky": "KY", "Louisiana": "LA", "Maine": "ME", "Maryland": "MD", "Massachusetts": "MA", "Michigan": "MI",
    "Minnesota": "MN", "Mississippi": "MS", "Missouri": "MO", "Montana": "MT", "Nebraska": "NE", "Nevada": "NV",
    "New Hampshire": "NH", "New Jersey": "NJ", "New Mexico": "NM", "New York": "NY", "North Carolina": "NC",
    "North Dakota": "ND", "Ohio": "OH", "Oklahoma": "OK", "Oregon": "OR", "Pennsylvania": "PA",
    "Rhode Island": "RI", "South Carolina": "SC", "South Dakota": "SD", "Tennessee": "TN", "Texas": "TX",
    "Utah": "UT", "Vermont": "VT", "Virginia": "VA", "Washington": "WA", "West Virginia": "WV", "Wisconsin": "WI",
    "Wyoming": "WY", "American Samoa": "AS", "Guam": "GU", "Commonwealth of the Northern Mariana Islands": "MP",
    "Puerto Rico": "PR", "United States Virgin Islands": "VI",
}


class GnisError(ValueError):
    """A GNIS file this cannot read: the wrong columns, or no state's text in it."""


def kind_of(feature_class):
    """``Populated Place`` → ``("populated_place", "Populated place")``: the slug and the words a row shows."""
    text = _clean(feature_class)
    words = text[:1].upper() + text[1:].lower()
    return text.lower().replace(" ", "_"), words


def rank_of(feature_class):
    if feature_class in _PLACES:
        return RANK_PLACE
    if feature_class in _STREAMS:
        return RANK_STREAM
    if feature_class in _WATER:
        return RANK_WATER
    return RANK_TERRAIN


def places(lines):
    """Places from one state's text (pipe-delimited, with its header), Civil and Census left out."""
    reader = csv.DictReader(lines, delimiter="|", quoting=csv.QUOTE_NONE)
    missing = [c for c in COLUMNS if c not in (reader.fieldnames or ())]
    if missing:
        raise GnisError(f"GNIS text has no column {', '.join(missing)}: is this GNIS's DomesticNames file?")
    for row in reader:
        feature_class = _clean(row.get("feature_class"))
        name = _clean(row.get("feature_name"))
        feature_id = _clean(row.get("feature_id"))
        if not feature_class or feature_class in LEFT_OUT or not name or not feature_id:
            continue
        lat, lon = _number(row.get("prim_lat_dec")), _number(row.get("prim_long_dec"))
        if lat is None or lon is None or not -90 <= lat <= 90 or not -180 <= lon <= 180 or (lat == 0 and lon == 0):
            continue
        kind, words = kind_of(feature_class)
        historical = "(historical)" in name.lower()
        state = _clean(row.get("state_name"))
        yield {
            "id": f"gnis:{feature_id}",
            "source": "gnis",
            "kind": kind,
            "detail": words + (", historical" if historical else ""),
            "name": name,
            "ident": None,
            "icao": None,
            "city": None,
            "county": _clean(row.get("county_name")) or None,
            "state": STATES.get(state, state) or None,
            "lat": lat,
            "lon": lon,
            "elev_ft": None,
            "use": None,
            "status": "historical" if historical else "open",
            "military": None,
            "rank": rank_of(feature_class),
        }


def _members(archive):
    return [info for info in archive.infolist()
            if info.filename.rsplit("/", 1)[-1].startswith(MEMBER_PREFIX) and info.filename.endswith(".txt")]


def read(path):
    """Every place in GNIS's national file at ``path``, each feature once, as they are read (a generator: the
    file is about 900,000 places, which the index takes one at a time rather than all held at once).

    Raises GnisError for a file it cannot read.
    """
    seen = set()
    with zipfile.ZipFile(path) as archive:
        members = _members(archive)
        if not members:
            raise GnisError("No state's text in the GNIS file given.")
        for info in members:
            with archive.open(info) as raw:
                lines = io.TextIOWrapper(raw, encoding="utf-8-sig", errors="replace", newline="")
                for place in places(lines):
                    if place["id"] in seen:
                        continue
                    seen.add(place["id"])
                    yield place


def file_date(path):
    """The date (``YYYY-MM-DD``) GNIS's file was made, as its members are stamped, or None."""
    with zipfile.ZipFile(path) as archive:
        stamps = [info.date_time for info in _members(archive)]
    if not stamps:
        return None
    year, month, day = max(stamps)[:3]
    return f"{year:04d}-{month:02d}-{day:02d}"
