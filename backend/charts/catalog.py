"""Which FAA charts the VFR tileset is built from, and where each cycle's files are (docs/VFR_CHARTS.md §2, §4.1).

Each chart is one GeoTIFF inside one of the FAA's zips; some zips hold several (a TAC and its Flyway chart, a helicopter sheet and its
inset). A chart's ``id`` names its outline in ``charts/outlines/<id>.geojson`` and its entry in a build's ``coverage.json``.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

FAA_VISUAL = "https://aeronav.faa.gov/visual"

# Tile levels are of 512-pixel tiles: a web zoom (Leaflet's 256-pixel zoom) is one more (§4.1).
TIERS = {
    #             shown from, stored to (tile levels); order is the order they are laid, coarsest first
    "sectional": (5, 11),
    "tac": (11, 12),
    "helicopter": (12, 13),
    "inset": (13, 14),
}
TIER_ORDER = ("sectional", "tac", "helicopter", "inset")

# A cycle every 56 days; this one is known to have taken effect at 0901Z on 29 Oct 2026.
_KNOWN_CYCLE = date(2026, 10, 29)
_CYCLE_DAYS = 56


@dataclass(frozen=True)
class Chart:
    id: str          # slug of the file's name: "atlanta-sec"
    title: str       # what people are shown: "Atlanta Sectional"
    tier: str        # a key of TIERS
    folder: str      # the FAA's folder for the zip
    zip: str         # "Atlanta.zip"
    tif: str         # the member: "Atlanta SEC.tif"

    @property
    def shown_from(self) -> int:
        return TIERS[self.tier][0]

    @property
    def stored_to(self) -> int:
        return TIERS[self.tier][1]


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def _chart(tif: str, tier: str, folder: str, zip_: str, title: str) -> Chart:
    return Chart(slug(tif.rsplit(".", 1)[0]), title, tier, folder, zip_, tif)


def _sectional(name: str) -> Chart:
    return _chart(f"{name} SEC.tif", "sectional", "sectional-files", name.replace(" ", "_") + ".zip", f"{name} Sectional")


def _tac(name: str, zip_name: str | None = None) -> Chart:
    zip_ = (zip_name or name).replace(" ", "_") + "_TAC.zip"
    return _chart(f"{name} TAC.tif", "tac", "tac-files", zip_, f"{name} TAC")


def _heli(tif_name: str, zip_: str, title: str, tier: str = "helicopter") -> Chart:
    return _chart(f"{tif_name}.tif", tier, "heli_files", zip_, title)


SECTIONALS = [_sectional(n) for n in (
    "Albuquerque", "Atlanta", "Billings", "Brownsville", "Charlotte", "Cheyenne", "Chicago", "Cincinnati", "Dallas-Ft Worth",
    "Denver", "Detroit", "El Paso", "Great Falls", "Green Bay", "Halifax", "Houston", "Jacksonville", "Kansas City",
    "Klamath Falls", "Lake Huron", "Las Vegas", "Los Angeles", "Memphis", "Miami", "Montreal", "New Orleans", "New York",
    "Omaha", "Phoenix", "Salt Lake City", "San Antonio", "San Francisco", "Seattle", "St Louis", "Twin Cities", "Washington",
    "Wichita",
)]

TACS = [_tac(n) for n in (
    "Atlanta", "Baltimore-Washington", "Boston", "Charlotte", "Chicago", "Cincinnati", "Cleveland", "Dallas-Ft Worth", "Denver",
    "Detroit", "Houston", "Kansas City", "Las Vegas", "Los Angeles", "Memphis", "Miami", "Minneapolis-St Paul", "New Orleans",
    "New York", "Philadelphia", "Phoenix", "Pittsburgh", "Salt Lake City", "San Diego", "San Francisco", "Seattle", "St Louis",
)] + [
    _tac("Colorado Springs", "Denver"),
    _tac("Portland", "Seattle"),
    _tac("Tampa", "Tampa-Orlando"),
    _tac("Orlando", "Tampa-Orlando"),
    # TAC tier, though not TACs (§4.1)
    _chart("Grand Canyon General Aviation.tif", "tac", "grand_canyon_files", "Grand_Canyon.zip", "Grand Canyon General Aviation"),
    _heli("Eastern Long Island HEL", "New_York_Heli.zip", "Eastern Long Island Helicopter", tier="tac"),
]

HELICOPTERS = [
    _heli("Baltimore HEL", "Balt-Wash_Heli.zip", "Baltimore Helicopter"),
    _heli("Washington HEL", "Balt-Wash_Heli.zip", "Washington Helicopter"),
    _heli("Boston HEL", "Boston_Heli.zip", "Boston Helicopter"),
    _heli("Chicago HEL", "Chicago_Heli.zip", "Chicago Helicopter"),
    _heli("Dallas-Ft Worth HEL", "Dallas-Ft_Worth_Heli.zip", "Dallas-Ft Worth Helicopter"),
    _heli("Detroit HEL", "Detroit_Heli.zip", "Detroit Helicopter"),
    _heli("Houston North HEL", "Houston_Heli.zip", "Houston North Helicopter"),
    _heli("Houston South HEL", "Houston_Heli.zip", "Houston South Helicopter"),
    _heli("Los Angeles East HEL", "Los_Angeles_Heli.zip", "Los Angeles East Helicopter"),
    _heli("Los Angeles West HEL", "Los_Angeles_Heli.zip", "Los Angeles West Helicopter"),
    _heli("New York HEL", "New_York_Heli.zip", "New York Helicopter"),
]

INSETS = [
    _heli("Washington Inset HEL", "Balt-Wash_Heli.zip", "Washington Inset", tier="inset"),
    _heli("Boston Downtown HEL", "Boston_Heli.zip", "Boston Downtown Helicopter", tier="inset"),
    _heli("Chicago O'Hare Inset HEL", "Chicago_Heli.zip", "Chicago O'Hare Inset", tier="inset"),
    _heli("Dallas-Love Inset HEL", "Dallas-Ft_Worth_Heli.zip", "Dallas-Love Inset", tier="inset"),
    _heli("Downtown Manhattan HEL", "New_York_Heli.zip", "Downtown Manhattan Helicopter", tier="inset"),
]

CHARTS = SECTIONALS + TACS + HELICOPTERS + INSETS
BY_ID = {c.id: c for c in CHARTS}


def zips() -> list[tuple[str, str]]:
    """Each (folder, zip) a CONUS build downloads, once."""
    seen: dict[tuple[str, str], None] = {}
    for c in CHARTS:
        seen[(c.folder, c.zip)] = None
    return list(seen)


def cycle_on(day: date) -> date:
    """The effective date of the cycle in effect on ``day`` (from 0901Z; on the day itself before then, use the day before)."""
    return _KNOWN_CYCLE + timedelta(days=((day - _KNOWN_CYCLE).days // _CYCLE_DAYS) * _CYCLE_DAYS)


def effective_instant(cycle: date) -> datetime:
    """A cycle takes effect at 0901Z on its date (§4.5)."""
    return datetime(cycle.year, cycle.month, cycle.day, 9, 1, tzinfo=timezone.utc)


def expires_instant(end: date) -> datetime:
    """A chart printed as valid to ``end`` expires at the next cycle's effective instant: 0901Z the day after (§4.5)."""
    return effective_instant(end + timedelta(days=1))


def zip_url(cycle: date, folder: str, zip_: str) -> str:
    return f"{FAA_VISUAL}/{cycle:%m-%d-%Y}/{folder}/{zip_}"
