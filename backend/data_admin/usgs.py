"""USGS's DEMs for an area, from The National Map's API (TNM Access).

Tried 2026-11-04 (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10.2): keyless, JSON, a page of up to 100 products a request,
each with its size, footprint in degrees and download URL on ``prd-tnm.s3.amazonaws.com``. TNM lists every dated version
of a 1/3″ cell (six for n35w085, 2011 to 2026), so a search keeps the newest of each cell or tile, and marks the ones the
server holds already, by name or by footprint (two overlapping DEMs would both enter the mosaic).
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from urllib.parse import urlparse

API = "https://tnmaccess.nationalmap.gov/api/v1/products"
USER_AGENT = "avtactools-data-admin/1.0"
PAGE = 100
MAX_PAGES = 10
# A search runs in a request, and Cloudflare ends one at 100 s: the whole search, every page, ends well before.
SEARCH_DEADLINE_S = 30

# Where files may come from, whatever TNM's answer says: a download is never sent anywhere else.
HOSTS = {"prd-tnm.s3.amazonaws.com", "rockyweb.usgs.gov"}

# The geoid grids both images bake (AGENTS.md §13): outside them PROJ passes heights through and reports success.
GEOID_BOX = (-125.0, 24.0, -66.0, 50.0)

PRODUCTS = {
    "13": {"dataset": "National Elevation Dataset (NED) 1/3 arc-second", "label": "1/3 arc-second (about 10 m)",
           "resolution_m": 10.0},
    "1m": {"dataset": "Digital Elevation Model (DEM) 1 meter", "label": "1 metre", "resolution_m": 1.0},
}

# Anything smaller is not a DEM tile: TNM lists a 465 KB "1 x 1 degree" item beside n35w085's real ones.
MIN_FILE_BYTES = 1_000_000
OVERLAP_HELD = 0.95

_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,200}\.tif$")
_CELL_13 = re.compile(r"_13_(n\d{2}w\d{3})(?:_|\.)", re.IGNORECASE)
_TILE_1M = re.compile(r"^USGS_1M_(\d{1,2})_(x\d+y\d+)_(.+)\.tif$", re.IGNORECASE)
# The older 1 m products' names: no zone, the tile then the project.
_TILE_1M_OLD = re.compile(r"^USGS_one_meter_(x\d+y\d+)_(.+)\.tif$", re.IGNORECASE)
# A project's name becomes a folder: nothing that could climb out of it, or start a dot folder the catalog skips.
_PROJECT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$")


class SearchError(RuntimeError):
    """TNM could not answer, or the area cannot be searched; the message says why, in words."""


@dataclass
class Candidate:
    name: str            # the file name it is kept under
    url: str
    size: int
    bounds: list         # [west, south, east, north], degrees
    published: str
    cell: str            # the cell or tile it covers, as USGS names it
    folder: list         # where it goes under a DEM root, folder by folder
    held: str | None = None   # the DEM on the server it would duplicate, relative to its root
    held_path: str | None = None  # the same, whole: kept on the server, never sent to the page
    # True when ``held`` is the same tile (its name, its cell, or a footprint about the same): Replace may move it aside.
    # False when a larger DEM covers it: never replaced, since that would take the rest of the larger one's ground too.
    same_tile: bool = False

    def as_dict(self):
        return asdict(self)

    def for_page(self):
        found = asdict(self)
        found.pop("held_path")
        return found


def check_area(bbox) -> tuple:
    try:
        west, south, east, north = (float(value) for value in bbox)
    except (TypeError, ValueError):
        raise SearchError("Draw an area on the map first.") from None
    if not (west < east and south < north):
        raise SearchError("That area has no size.")
    box = GEOID_BOX
    if west < box[0] or east > box[2] or south < box[1] or north > box[3]:
        raise SearchError("Only the lower 48 can be added: outside it the 3D terrain's geoid grids do not reach.")
    return west, south, east, north


def allowed_url(url: str) -> bool:
    try:
        parts = urlparse(url or "")
        return parts.scheme == "https" and parts.hostname in HOSTS and not parts.username and not parts.port
    except ValueError:      # a port that is not a number, an address that is not one
        return False


def _get_json(params, http, deadline):
    import time

    left = deadline - time.monotonic()
    if left <= 1:
        raise SearchError("The National Map is slow to answer. Draw a smaller area, or try again in a minute.")
    try:
        response = http.get(API, params=params, timeout=(min(10, left), left), headers={"User-Agent": USER_AGENT})
    except Exception:  # noqa: BLE001 — any failure to reach it is the same to the admin
        raise SearchError("The National Map did not answer. Try again in a minute.") from None
    if response.status_code != 200:
        raise SearchError(f"The National Map answered {response.status_code}. Try again in a minute.")
    try:
        return response.json()
    except ValueError:
        raise SearchError("The National Map sent something that is not JSON.") from None


def products(bbox, product: str, *, http=None) -> list:
    """Every product TNM lists for the area, raw, page by page."""
    if product not in PRODUCTS:
        raise SearchError("Choose 1/3 arc-second or 1 metre.")
    import time

    if http is None:
        import requests as http
    west, south, east, north = check_area(bbox)
    items, offset = [], 0
    deadline = time.monotonic() + SEARCH_DEADLINE_S
    for _ in range(MAX_PAGES):
        page = _get_json({"datasets": PRODUCTS[product]["dataset"], "bbox": f"{west},{south},{east},{north}",
                          "prodFormats": "GeoTIFF", "max": PAGE, "offset": offset, "outputFormat": "JSON"}, http, deadline)
        batch = page.get("items") or []
        items.extend(batch)
        offset += len(batch)
        total = page.get("total") or 0
        if not batch or offset >= total:
            return items
    raise SearchError(f"More than {PAGE * MAX_PAGES} files in that area. Draw a smaller one.")


def _bounds(item):
    box = item.get("boundingBox") or {}
    try:
        return [float(box["minX"]), float(box["minY"]), float(box["maxX"]), float(box["maxY"])]
    except (KeyError, TypeError, ValueError):
        return None


def candidates(items, product: str) -> tuple:
    """The newest file of each cell or tile, and how many were left out (older versions, or not a DEM tile)."""
    newest, skipped = {}, 0
    for item in items:
        url = str(item.get("downloadURL") or "")
        name = url.rsplit("/", 1)[-1]
        bounds = _bounds(item)
        try:
            size = int(item.get("sizeInBytes") or 0)
        except (TypeError, ValueError):
            size = 0
        if not (allowed_url(url) and _NAME.match(name) and bounds and size >= MIN_FILE_BYTES):
            skipped += 1
            continue
        if product == "13":
            match = _CELL_13.search(name)
            if not match:
                skipped += 1
                continue
            cell, folder = match.group(1).lower(), ["usgs", "13"]
        else:
            match = _TILE_1M.match(name)
            old = None if match else _TILE_1M_OLD.match(name)
            if match:
                cell, project = f"{match.group(1)} {match.group(2).lower()}", match.group(3)
            elif old:
                cell, project = old.group(1).lower(), old.group(2)
            else:
                skipped += 1
                continue
            if not _PROJECT.match(project):
                skipped += 1
                continue
            folder = ["usgs", "1m", project]
        found = Candidate(name=name, url=url, size=size, bounds=bounds, cell=cell, folder=folder,
                          published=str(item.get("publicationDate") or "") + "|" + str(item.get("lastUpdated") or ""))
        held = newest.get(cell)
        if held is None or found.published > held.published:
            if held is not None:
                skipped += 1
            newest[cell] = found
        else:
            skipped += 1
    chosen = sorted(newest.values(), key=lambda c: (-c.bounds[3], c.bounds[0]))
    for candidate in chosen:
        candidate.published = candidate.published.split("|", 1)[0]
    return chosen, skipped


def _overlap(a, b) -> float:
    """How much of box ``a`` box ``b`` covers, 0 to 1 (both [west, south, east, north])."""
    width = min(a[2], b[2]) - max(a[0], b[0])
    height = min(a[3], b[3]) - max(a[1], b[1])
    area = (a[2] - a[0]) * (a[3] - a[1])
    if width <= 0 or height <= 0 or area <= 0:
        return 0.0
    return width * height / area


def mark_held(chosen, entries, product: str, relative) -> None:
    """Marks each candidate the server already holds. The same tile: the same file name, the same 1/3″ cell under any of
    USGS's names for it, or a DEM of about its resolution and footprint (each covering 95 % of the other). Or covered: a
    larger DEM of about its resolution covering 95 % of it, which a download would only overlap. ``relative`` names an
    entry's path for the admin."""
    nominal = PRODUCTS[product]["resolution_m"]
    by_name = {}
    for entry in entries:
        by_name.setdefault(entry.path.replace("\\", "/").rsplit("/", 1)[-1].lower(), entry)
    for candidate in chosen:
        entry, same = by_name.get(candidate.name.lower()), True
        if entry is None and product == "13":
            entry = next((e for n, e in by_name.items() if f"_13_{candidate.cell}" in n), None)
        if entry is None:
            for other in entries:
                south, west, north, east = other.bounds_latlon
                box = [west, south, east, north]
                if nominal / 2.5 <= other.resolution_m <= nominal * 2.5 and \
                        _overlap(candidate.bounds, box) >= OVERLAP_HELD:
                    entry, same = other, _overlap(box, candidate.bounds) >= OVERLAP_HELD
                    if same:
                        break
        candidate.held = relative(entry.path) if entry is not None else None
        candidate.held_path = entry.path if entry is not None else None
        candidate.same_tile = entry is not None and same


_3DEP_NAME = re.compile(r"^USGS_(13_n\d{2}w\d{3}|1M_\d{1,2}_x\d+y\d+_.+|one_meter_.+|1_n\d{2}w\d{3}|2_n\d{2}w\d{3})"
                        r"[^/\\]*\.tif$", re.IGNORECASE)


def is_3dep_name(path: str) -> bool:
    """Whether a file is named as USGS names its 3DEP DEMs (NAVD88 in the lower 48 by what they are)."""
    return bool(_3DEP_NAME.match(str(path).replace("\\", "/").rsplit("/", 1)[-1]))
