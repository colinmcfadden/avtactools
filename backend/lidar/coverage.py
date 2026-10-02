"""Choosing which USGS survey to read for a given landing point.

USGS publishes an Entwine index of every 3DEP project on AWS Open Data, and
each index can be queried remotely for an area of interest — no download. The
list of projects and their footprints is one GeoJSON file, so picking the right
survey for a target is a point-in-polygon test against it.

Which survey matters more than it sounds. Areas are commonly covered several
times over, and the surveys are not equivalent: ARRA_GA_LakeLanier_2010 has
ground classified and nothing else, at a density too low to resolve individual
trees, while GA_Statewide_B3_2018 covers the same ground with full ASPRS
classification. Reading the older one produces a bare terrain sheet and no
obstruction data at all.
"""

from __future__ import annotations

import json
import os
import re
import urllib.request
from dataclasses import dataclass
from pathlib import Path

INDEX_URL = "https://usgs.entwine.io/boundaries/resources.geojson"
CACHE_FILENAME = "usgs_ept_coverage.geojson"
USER_AGENT = "avtactools-lidar/1.0"

# A four-digit year in the project name is how the surveys date themselves;
# there is no year field in the index.
_YEAR = re.compile(r"(19|20)\d{2}")

# Classification quality tracks vintage closely. USGS moved to the 3DEP base
# specification, which requires vegetation classes, from around 2015; ARRA-era
# projects predate it and generally classify ground only.
CLASSIFIED_FROM_YEAR = 2015


class CoverageError(RuntimeError):
    """The coverage index could not be read, or covers nothing useful."""


@dataclass(frozen=True)
class Survey:
    name: str
    url: str
    points: int
    year: int | None

    @property
    def likely_classified(self) -> bool:
        """Whether to expect vegetation classes rather than ground only."""
        return self.year is not None and self.year >= CLASSIFIED_FROM_YEAR


def cache_path(root=None) -> Path:
    root = Path(root or os.environ.get("LIDAR_CACHE_DIR", ".")).expanduser()
    return root / CACHE_FILENAME


def fetch_index(destination=None, *, url=INDEX_URL, opener=None) -> Path:
    """Download the coverage index, which is about 8 MB and rarely changes.

    Written to a temporary name and moved into place, so a download cut short
    never leaves a truncated index that later loads as "no coverage anywhere".
    """
    destination = Path(destination or cache_path())
    destination.parent.mkdir(parents=True, exist_ok=True)
    open_url = opener or urllib.request.urlopen
    # The host refuses urllib's default User-Agent with a 403.
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    partial = destination.with_suffix(destination.suffix + ".part")
    with open_url(request) as response, partial.open("wb") as handle:
        handle.write(response.read())
    partial.replace(destination)
    return destination


def load_index(path=None, *, download=True) -> list:
    """Every published survey, as ``Survey`` records."""
    path = Path(path or cache_path())
    if not path.exists():
        if not download:
            raise CoverageError(f"no coverage index at {path}")
        fetch_index(path)

    document = json.loads(path.read_text(encoding="utf-8"))
    surveys = []
    for feature in document.get("features", []):
        properties = feature.get("properties", {})
        name = properties.get("name")
        if not name:
            continue
        found = _YEAR.search(name)
        surveys.append((Survey(name=name,
                               url=properties.get("url", ""),
                               points=int(properties.get("count", 0)),
                               year=int(found.group()) if found else None),
                        feature.get("geometry")))
    if not surveys:
        raise CoverageError(f"{path} holds no surveys")
    return surveys


def _rings(geometry) -> list:
    if not geometry:
        return []
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    return []


def contains(geometry, lon: float, lat: float) -> bool:
    """Ray-casting point-in-polygon against a footprint's outer rings.

    Holes are ignored: a survey's footprint may exclude water bodies, and
    treating a lake as "no coverage" would refuse a perfectly good shoreline
    target whose area of interest is mostly land.
    """
    for polygon in _rings(geometry):
        outer = polygon[0]
        inside = False
        count = len(outer)
        for index in range(count):
            x1, y1 = outer[index][0], outer[index][1]
            x2, y2 = outer[(index + 1) % count][0], outer[(index + 1) % count][1]
            if (y1 > lat) != (y2 > lat):
                crossing = (x2 - x1) * (lat - y1) / (y2 - y1) + x1
                if lon < crossing:
                    inside = not inside
        if inside:
            return True
    return False


def surveys_for(lat: float, lon: float, *, index=None) -> list:
    """Every survey covering a point, best first.

    Ranked by vintage before size: a newer survey is the one likely to carry
    vegetation classification, and that matters more for an LZ than raw point
    count. Size breaks ties among surveys of the same year.
    """
    entries = index if index is not None else load_index()
    covering = [survey for survey, geometry in entries
                if contains(geometry, lon, lat)]
    return sorted(covering, key=lambda s: (-(s.year or 0), -s.points))


def best_survey(lat: float, lon: float, *, index=None) -> Survey:
    """The survey to read for this target."""
    covering = surveys_for(lat, lon, index=index)
    if not covering:
        raise CoverageError(f"no USGS lidar covers {lat:.5f}, {lon:.5f}")
    return covering[0]
