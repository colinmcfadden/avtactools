"""Keeping the places index to the FAA's current NASR cycle (docs/PLACE_SEARCH.md §10).

The FAA's keyless APRA API says which 28-day cycle is in effect; when the index under ``PLACES_DIR`` is not of
that cycle (or of this code's schema), the cycle's three CSV zips are fetched from ``nfdc.faa.gov``, checked,
built into a new ``places-<build>.sqlite`` beside the old, and ``current.json`` switched to it. Anything that
fails leaves the build being served as it was.

On the server this runs in a background thread (``start``), every six hours, waiting while an analysis runs, as
the terrain warm-up does. It reaches only the FAA's two hosts, over https, with no redirects followed.
"""

import json
import logging
import os
import shutil
import tempfile
import threading
import time
from urllib.parse import urlsplit

import requests

try:
    import fcntl
except ImportError:  # Windows, where the owner runs the backend locally: one process, so no lock is needed
    fcntl = None

from . import index, nasr, store

log = logging.getLogger("places")

APRA = "https://external-api.faa.gov/apra/nfdc/nasr/chart"
CSV_BASE = "https://nfdc.faa.gov/webContent/28DaySub/extra/"
HOSTS = frozenset({"external-api.faa.gov", "nfdc.faa.gov"})
AREAS = ("APT", "NAV", "FIX")
MAX_ZIP_BYTES = 64 * 1024 * 1024
# A build needs its downloads (about 13 MB), the new index (about 19 MB) and room to spare.
MIN_FREE_BYTES = 512 * 1024 * 1024
KEEP_BUILDS = 2
EVERY_SECONDS = 6 * 3600
TIMEOUT = (10, 60)
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

# Fewer than these and the files are not a whole cycle: the check refuses the build.
MINIMUM = {"airport": 10_000, "heliport": 4_000, "fix": 50_000}
# More than this change in any of them against the last build is refused too: a cycle moves a few dozen rows.
MAX_CHANGE = 0.05


class RefreshError(RuntimeError):
    """A refresh that could not be made; the build being served stays."""


def _checked(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.hostname not in HOSTS:
        raise RefreshError(f"Not a NASR address: {parts.hostname}")
    return url


def edition(name="current", get=requests.get):
    """The effective date (``YYYY-MM-DD``) of NASR's ``current`` or ``next`` cycle, from the APRA API."""
    response = get(_checked(f"{APRA}?edition={name}"), headers={"Accept": "application/json"},
                   timeout=TIMEOUT, allow_redirects=False)
    if response.status_code != 200:
        raise RefreshError(f"APRA answered {response.status_code}")
    try:
        text = response.json()["edition"][0]["editionDate"]
        month, day, year = (int(part) for part in text.split("/"))
    except (ValueError, KeyError, IndexError, TypeError, AttributeError) as error:
        raise RefreshError("APRA's answer has no edition date") from error
    return f"{year:04d}-{month:02d}-{day:02d}"


def zip_names(cycle):
    """The CSV zips of the cycle effective on ``cycle`` (``YYYY-MM-DD``): ``29_Oct_2026_APT_CSV.zip`` …"""
    year, month, day = (int(part) for part in cycle.split("-"))
    stem = f"{day:02d}_{_MONTHS[month - 1]}_{year:04d}"
    return [f"{stem}_{area}_CSV.zip" for area in AREAS]


def download(url, path, get=requests.get, max_bytes=MAX_ZIP_BYTES):
    """Fetch ``url`` (a NASR host only) to ``path``, refusing a redirect and anything over ``max_bytes``."""
    response = get(_checked(url), stream=True, timeout=TIMEOUT, allow_redirects=False)
    try:
        if response.status_code != 200:
            raise RefreshError(f"{urlsplit(url).path.rsplit('/', 1)[-1]}: {response.status_code}")
        written = 0
        with open(path, "wb") as handle:
            for chunk in response.iter_content(chunk_size=1 << 16):
                written += len(chunk)
                if written > max_bytes:
                    raise RefreshError("A NASR file is larger than any cycle's")
                handle.write(chunk)
    finally:
        response.close()
    return path


def check(places, previous_kinds=None):
    """Refuse a build whose counts say it is not a whole cycle (RefreshError); the counts by kind otherwise."""
    kinds = {}
    for place in places:
        kinds[place["kind"]] = kinds.get(place["kind"], 0) + 1
    for kind, least in MINIMUM.items():
        if kinds.get(kind, 0) < least:
            raise RefreshError(f"Only {kinds.get(kind, 0)} of kind {kind}: not a whole NASR cycle")
        before = (previous_kinds or {}).get(kind)
        if before and abs(kinds[kind] - before) > MAX_CHANGE * before:
            raise RefreshError(f"{kind}: {kinds[kind]} against {before} in the last build")
    return kinds


def _write_current(root, current):
    handle, temporary = tempfile.mkstemp(prefix=".current-", dir=root)
    with os.fdopen(handle, "w", encoding="utf-8") as out:
        json.dump(current, out, sort_keys=True)
    os.replace(temporary, os.path.join(root, store.CURRENT))


def _previous_kinds(root, current):
    if not current:
        return None
    import sqlite3
    try:
        connection = sqlite3.connect(f"file:{os.path.join(root, current['file'])}?mode=ro&immutable=1", uri=True)
        try:
            return json.loads(index.read_meta(connection).get("kinds") or "{}")
        finally:
            connection.close()
    except (sqlite3.Error, ValueError):
        return None


def _prune(root, keep):
    builds = sorted(name for name in os.listdir(root) if name.startswith("places-") and name.endswith(".sqlite"))
    for name in builds[:-KEEP_BUILDS]:
        if name != keep:
            try:
                os.remove(os.path.join(root, name))
            except OSError:
                pass


def build_from(zips, root, cycle=None, previous_kinds=None):
    """Build an index of the NASR CSV zips ``zips`` into ``root`` and serve it. Returns the new ``current``."""
    effective = nasr.effective_date(zips)
    if cycle and effective != cycle:
        raise RefreshError(f"The files say {effective}, not the cycle {cycle}")
    cycle = cycle or effective
    if not cycle:
        raise RefreshError("The files say no effective date")
    places = nasr.read(zips)
    check(places, previous_kinds)
    build = f"{cycle}.s{index.SCHEMA}"
    name = f"places-{build}.sqlite"
    staging = tempfile.mkdtemp(prefix=".staging-", dir=root)
    try:
        index.build(places, os.path.join(staging, name), {"nasr_cycle": cycle, "build": build})
        os.replace(os.path.join(staging, name), os.path.join(root, name))
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    current = {"build": build, "file": name, "nasr_cycle": cycle}
    _write_current(root, current)
    _prune(root, name)
    return current


def refresh(root, get=requests.get):
    """Bring ``root``'s index to NASR's current cycle. Returns ``"current"`` (nothing to do) or ``"built"``."""
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, ".lock"), "w") as lock:
        # Another process (staging beside production, a second container) may be building into the same folder.
        if fcntl:
            fcntl.flock(lock, fcntl.LOCK_EX)
        cycle = edition("current", get)
        current = store.read_current(root)
        if current and current.get("nasr_cycle") == cycle and current.get("build", "").endswith(f".s{index.SCHEMA}") \
                and os.path.exists(os.path.join(root, current["file"])):
            return "current"
        if shutil.disk_usage(root).free < MIN_FREE_BYTES:
            raise RefreshError("Not enough free disk under PLACES_DIR")
        downloads = tempfile.mkdtemp(prefix=".download-", dir=root)
        try:
            zips = [download(CSV_BASE + name, os.path.join(downloads, name), get) for name in zip_names(cycle)]
            build_from(zips, root, cycle, _previous_kinds(root, current))
        finally:
            shutil.rmtree(downloads, ignore_errors=True)
        return "built"


def start():
    """Keep ``PLACES_DIR``'s index current in a background thread, unless ``PLACES_REFRESH=off``. Returns the thread."""
    root = store.places_dir()
    if not root or (os.environ.get("PLACES_REFRESH") or "").strip().lower() == "off":
        return None

    def run():
        import terrain_tiles  # the server's one gate for heavy work; imported here so the CLI needs no terrain stack
        while True:
            terrain_tiles._no_analysis.wait()  # as lidar/photo.py waits
            try:
                if refresh(root) == "built":
                    log.info("places: built NASR cycle %s", (store.read_current(root) or {}).get("nasr_cycle"))
            except Exception as error:  # noqa: BLE001 — a refresh is tried again later; the old build stays
                # The error's kind and words only: a refresh holds no search text, but keep the rule anyway.
                log.warning("places: refresh failed (%s: %s)", type(error).__name__, error)
            time.sleep(EVERY_SECONDS)

    thread = threading.Thread(target=run, name="places-refresh", daemon=True)
    thread.start()
    return thread
