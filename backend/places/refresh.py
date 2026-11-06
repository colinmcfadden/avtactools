"""Keeping the places index to the FAA's current NASR cycle and USGS's latest GNIS file (docs/PLACE_SEARCH.md §10).

The FAA's keyless APRA API says which 28-day cycle is in effect, and GNIS's national file on USGS's bucket says
when it was last changed (its ``Last-Modified``). The sources are kept under ``PLACES_DIR/sources/`` (the cycle's
three CSV zips, GNIS's one zip), so a new NASR cycle is built with the GNIS file already held and a new GNIS
file with the cycle already held. When the index under ``PLACES_DIR`` is not of both (or of this code's schema),
a new ``places-<build>.sqlite`` is built beside the old, checked, and ``current.json`` switched to it. Anything
that fails leaves the build being served as it was.

GNIS is a million rows: about 10 s and 330 MB to build (measured on a Mac), which in the API's own interpreter
would hold its lock against every request for those seconds. So the server builds in a process of its own, at
low priority (``python -m places build``); the API process only fetches and switches. GNIS that cannot be
fetched leaves out nothing it had: the last file kept is built with, and a server that has never had one builds
NASR alone until it does.

On the server this runs in a background thread (``start``), every six hours, waiting while an analysis runs, as
the terrain warm-up does. It reaches only the FAA's two hosts and USGS's bucket, over https, with no redirects
followed.
"""

import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit

import requests

try:
    import fcntl
except ImportError:  # Windows, where the owner runs the backend locally: one process, so no lock is needed
    fcntl = None

from . import gnis, index, nasr, store

log = logging.getLogger("places")

APRA = "https://external-api.faa.gov/apra/nfdc/nasr/chart"
CSV_BASE = "https://nfdc.faa.gov/webContent/28DaySub/extra/"
GNIS_URL = ("https://prd-tnm.s3.amazonaws.com/StagedProducts/GeographicNames/DomesticNames/"
            "DomesticNames_AllStates_Text.zip")
HOSTS = frozenset({"external-api.faa.gov", "nfdc.faa.gov", "prd-tnm.s3.amazonaws.com"})
AREAS = ("APT", "NAV", "FIX")
MAX_ZIP_BYTES = 64 * 1024 * 1024
# GNIS's national file was 45.8 MB on 2026-11-02.
MAX_GNIS_BYTES = 160 * 1024 * 1024
# A build needs the sources (about 60 MB), the new index (about 145 MB), the two kept, and room to spare.
MIN_FREE_BYTES = 1024 * 1024 * 1024
SOURCES = "sources"
GNIS_FILE = "DomesticNames_AllStates_Text.zip"
GNIS_STATE = "gnis.json"
# A build in its own process: ten seconds on a Mac, far longer is something wrong.
BUILD_TIMEOUT = 15 * 60
KEEP_BUILDS = 2
EVERY_SECONDS = 6 * 3600
TIMEOUT = (10, 60)
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

# Fewer than these and the files are not a whole cycle: the check refuses the build.
MINIMUM = {"airport": 10_000, "heliport": 4_000, "fix": 50_000}
# The same for GNIS (2026-11-02: 185,307 populated places, 69,237 summits, 230,945 streams), when it is built with.
MINIMUM_GNIS = {"populated_place": 150_000, "summit": 50_000, "stream": 180_000}
# More than this change in any of them against the last build is refused too: a cycle moves a few dozen rows,
# and a build without GNIS never replaces one with it.
MAX_CHANGE = 0.05


class RefreshError(RuntimeError):
    """A refresh that could not be made; the build being served stays."""


def _checked(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.hostname not in HOSTS:
        raise RefreshError(f"Not a NASR or GNIS address: {parts.hostname}")
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
    """Fetch ``url`` (a NASR or GNIS host only) to ``path``, refusing a redirect and anything over ``max_bytes``."""
    response = get(_checked(url), stream=True, timeout=TIMEOUT, allow_redirects=False)
    try:
        if response.status_code != 200:
            raise RefreshError(f"{urlsplit(url).path.rsplit('/', 1)[-1]}: {response.status_code}")
        written = 0
        with open(path, "wb") as handle:
            for chunk in response.iter_content(chunk_size=1 << 16):
                written += len(chunk)
                if written > max_bytes:
                    raise RefreshError("A file is larger than any the FAA or USGS has published")
                handle.write(chunk)
    finally:
        response.close()
    return path


def check(kinds, previous_kinds=None, with_gnis=False):
    """Refuse a build whose counts by kind say it is not a whole NASR cycle, or GNIS file (RefreshError)."""
    least = {**MINIMUM, **(MINIMUM_GNIS if with_gnis else {})}
    for kind, count in least.items():
        if kinds.get(kind, 0) < count:
            source = "GNIS file" if kind in MINIMUM_GNIS else "NASR cycle"
            raise RefreshError(f"Only {kinds.get(kind, 0)} of kind {kind}: not a whole {source}")
    for kind in {**MINIMUM, **MINIMUM_GNIS}:
        before = (previous_kinds or {}).get(kind)
        if before and abs(kinds.get(kind, 0) - before) > MAX_CHANGE * before:
            raise RefreshError(f"{kind}: {kinds.get(kind, 0)} against {before} in the last build")
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
    # Oldest first by when each was written: a build's name says its sources, not its order.
    builds = sorted((name for name in os.listdir(root) if name.startswith("places-") and name.endswith(".sqlite")),
                    key=lambda name: os.stat(os.path.join(root, name)).st_mtime_ns)
    for name in builds[:-KEEP_BUILDS]:
        if name != keep:
            try:
                os.remove(os.path.join(root, name))
            except OSError:
                pass


build_name = index.build_name


def build_from(zips, root, cycle=None, previous_kinds=None, gnis_zip=None, built_at=None):
    """Build an index of the NASR CSV zips ``zips`` and GNIS's file ``gnis_zip`` (or none) into ``root``, check it
    and serve it. Returns the new ``current``. ``built_at`` fixes the time it says it was built (fixtures)."""
    effective = nasr.effective_date(zips)
    if cycle and effective != cycle:
        raise RefreshError(f"The files say {effective}, not the cycle {cycle}")
    cycle = cycle or effective
    if not cycle:
        raise RefreshError("The files say no effective date")
    gnis_date = gnis.file_date(gnis_zip) if gnis_zip else None
    if gnis_zip and not gnis_date:
        raise RefreshError("The GNIS file holds no state's names")

    def places():
        yield from nasr.read(zips)
        if gnis_zip:
            yield from gnis.read(gnis_zip)

    build = build_name(cycle, gnis_date)
    name = f"places-{build}.sqlite"
    meta = {"nasr_cycle": cycle, "build": build, **({"gnis_date": gnis_date} if gnis_date else {}),
            **({"built_at": built_at} if built_at else {})}
    staging = tempfile.mkdtemp(prefix=".staging-", dir=root)
    try:
        path = os.path.join(staging, name)
        index.build(places(), path, meta)
        check(_kinds_in(path), previous_kinds, with_gnis=bool(gnis_zip))
        os.replace(path, os.path.join(root, name))
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    current = {"build": build, "file": name, "nasr_cycle": cycle, **({"gnis_date": gnis_date} if gnis_date else {})}
    _write_current(root, current)
    _prune(root, name)
    return current


def _kinds_in(path):
    import sqlite3
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return json.loads(index.read_meta(connection).get("kinds") or "{}")
    finally:
        connection.close()


def build_here(root, zips, cycle=None, gnis_zip=None):
    """Build in this interpreter, checked against what ``root`` serves now: the CLI, and the child the server runs."""
    os.makedirs(root, exist_ok=True)
    return build_from(zips, root, cycle, _previous_kinds(root, store.read_current(root)), gnis_zip)


def fetch_nasr(root, cycle, get=requests.get):
    """The cycle's three CSV zips under ``sources/nasr/<cycle>/``, fetched when they are not there yet."""
    folder = os.path.join(root, SOURCES, "nasr")
    os.makedirs(folder, exist_ok=True)
    held = os.path.join(folder, cycle)
    names = zip_names(cycle)
    if not all(os.path.exists(os.path.join(held, name)) for name in names):
        downloads = tempfile.mkdtemp(prefix=".download-", dir=folder)
        try:
            for name in names:
                download(CSV_BASE + name, os.path.join(downloads, name), get)
            shutil.rmtree(held, ignore_errors=True)
            os.replace(downloads, held)
        finally:
            shutil.rmtree(downloads, ignore_errors=True)
    for other in os.listdir(folder):
        if other != cycle:
            shutil.rmtree(os.path.join(folder, other), ignore_errors=True)
    return [os.path.join(held, name) for name in names]


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as handle:
            value = json.load(handle)
        return value if isinstance(value, dict) else None
    except (OSError, ValueError):
        return None


def fetch_gnis(root, get=requests.get, head=requests.head):
    """GNIS's national file under ``sources/gnis/``, fetched when USGS's has changed since: ``(path, date)``, or
    ``(None, None)`` with none held. A fetch that fails keeps the file held (and is said in the log)."""
    folder = os.path.join(root, SOURCES, "gnis")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, GNIS_FILE)
    state = _read_json(os.path.join(folder, GNIS_STATE)) if os.path.exists(path) else None
    held = (path, state.get("date")) if state and state.get("date") else (None, None)
    try:
        response = head(_checked(GNIS_URL), timeout=TIMEOUT, allow_redirects=False)
        modified = response.headers.get("Last-Modified") if response.status_code == 200 else None
    except requests.RequestException:
        modified = None
    if held[0] and (modified is None or modified == state.get("last_modified")):
        return held
    handle, temporary = tempfile.mkstemp(prefix=".download-", dir=folder)
    os.close(handle)
    try:
        download(GNIS_URL, temporary, get, MAX_GNIS_BYTES)
        date = gnis.file_date(temporary)
        if not date:
            raise RefreshError("The GNIS file holds no state's names")
        os.replace(temporary, path)
    except Exception as error:  # noqa: BLE001 — refused, cut short, a damaged zip: the file held stays
        log.warning("places: GNIS not fetched (%s: %s)", type(error).__name__, error)
        return held
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)
    with open(os.path.join(folder, GNIS_STATE), "w", encoding="utf-8") as out:
        json.dump({"last_modified": modified, "date": date}, out, sort_keys=True)
    return path, date


def build_in_child(root, zips, gnis_zip, cycle):
    """Build in a process of its own, at low priority, so the API's interpreter is never held by it."""
    command = [sys.executable, "-m", "places", "build", "--low-priority", "--dir", root, "--cycle", cycle]
    for path in zips:
        command += ["--zip", path]
    if gnis_zip:
        command += ["--gnis", gnis_zip]
    backend = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    try:
        result = subprocess.run(command, cwd=backend, capture_output=True, text=True, timeout=BUILD_TIMEOUT)
    except subprocess.TimeoutExpired as error:
        raise RefreshError("The build took too long") from error
    if result.returncode != 0:
        lines = [line for line in (result.stderr or "").strip().splitlines() if line.strip()]
        raise RefreshError(f"The build failed: {lines[-1] if lines else result.returncode}")
    return store.read_current(root)


def refresh(root, get=requests.get, head=requests.head, run=build_in_child):
    """Bring ``root``'s index to NASR's current cycle and GNIS's latest file. Returns ``"current"`` (nothing to
    do) or ``"built"``."""
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, ".lock"), "w") as lock:
        # Another process (staging beside production, a second container) may be building into the same folder.
        if fcntl:
            fcntl.flock(lock, fcntl.LOCK_EX)
        cycle = edition("current", get)
        if shutil.disk_usage(root).free < MIN_FREE_BYTES:
            raise RefreshError("Not enough free disk under PLACES_DIR")
        zips = fetch_nasr(root, cycle, get)
        gnis_zip, gnis_date = fetch_gnis(root, get, head)
        current = store.read_current(root)
        if current and current.get("build") == build_name(cycle, gnis_date) \
                and os.path.exists(os.path.join(root, current["file"])):
            return "current"
        run(root, zips, gnis_zip, cycle)
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
                    current = store.read_current(root) or {}
                    log.info("places: built NASR cycle %s, GNIS %s", current.get("nasr_cycle"), current.get("gnis_date"))
            except Exception as error:  # noqa: BLE001 — a refresh is tried again later; the old build stays
                # The error's kind and words only: a refresh holds no search text, but keep the rule anyway.
                log.warning("places: refresh failed (%s: %s)", type(error).__name__, error)
            time.sleep(EVERY_SECONDS)

    thread = threading.Thread(target=run, name="places-refresh", daemon=True)
    thread.start()
    return thread
