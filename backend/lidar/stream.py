"""3D streamed from USGS's octree on AWS, a piece at a time, cached.

docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C. In place of a build per LZ
(137 s for a 500 m radius), a target resolves at once to the whole survey
covering it, published as a tileset written from USGS's own index
(``lidar.ept``), and each piece a viewer asks for is fetched from AWS,
converted (``lidar.pieces``), cached and served. The first view of a place
costs what its pieces cost to download, about half a second each, eight at a
time.

On only where ``LIDAR_STREAM_CACHE_DIR`` names a writable directory (a
persistent mount in production, e.g. ``/data/cache/lidar``). Everything under
it can be deleted at any time; a survey is never republished under its name,
so nothing in it goes stale.

    LIDAR_STREAM_CACHE_DIR      the cache; unset turns streaming off
    LIDAR_STREAM_CACHE_MAX_GB   pieces past this are let go, oldest first (200)
    LIDAR_STREAM_PHOTO          off: no photographic pass (``lidar.photo``; on)

The browser never talks to AWS: AWS sees this server, never a crew.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
from pathlib import Path

from . import catalog, coverage, ept, names, pieces
from .coverage import EPT_BASE

# Surveys whose index pages are held in memory at once; a statewide survey's
# root page is a few megabytes parsed.
MAX_SURVEYS_HELD = 8

DEFAULT_CACHE_MAX_GB = 200.0

# AWS answers a piece in half a second; one that has not started answering in
# this long is a connection to try again, and the viewer asks again anyway.
CONNECT_TIMEOUT_S = 5
READ_TIMEOUT_S = 30

# A piece is about 350 KB; the largest seen are a few MB. A page of the index
# is under a megabyte. Anything far past these is not what was asked for.
MAX_PIECE_BYTES = 64 * 1024 * 1024
MAX_JSON_BYTES = 16 * 1024 * 1024

# How often the cache is measured against its cap: every this many new files.
PRUNE_EVERY = 200
# A file read is marked as used at most this often, so a busy view does not
# rewrite inode times on every request.
TOUCH_EVERY_S = 3600

_SURVEY = re.compile(r"^[A-Za-z0-9_.-]{1,200}$")
_FILE = re.compile(r"^(tileset|[0-9a-f]{16})\.(json|pnts)$")

USER_AGENT = "avtactools-lidar/1.0"


class StreamError(RuntimeError):
    """Streaming could not answer; ``code`` says why, for the client."""

    def __init__(self, message: str, *, code: str, status: int):
        super().__init__(message)
        self.code = code
        self.status = status


def cache_dir() -> Path | None:
    value = (os.environ.get("LIDAR_STREAM_CACHE_DIR") or "").strip()
    return Path(value) if value else None


def enabled() -> bool:
    return cache_dir() is not None


def _cap_bytes() -> float:
    try:
        return float(os.environ.get("LIDAR_STREAM_CACHE_MAX_GB") or DEFAULT_CACHE_MAX_GB) * 1e9
    except ValueError:
        return DEFAULT_CACHE_MAX_GB * 1e9


def is_stream_file(name: str) -> bool:
    return bool(_FILE.match(name or ""))


# Tries of one file before it is given up on: a viewer never asks for a failed
# tile again (Cesium does not), so a reset connection would leave a hole.
FETCH_TRIES = 3
FETCH_BACKOFF_S = 0.25

_session = None
_session_lock = threading.Lock()


def _http():
    """One pooled session, so pieces reuse AWS connections instead of paying
    a TLS handshake each."""
    global _session
    with _session_lock:
        if _session is None:
            import requests
            from requests.adapters import HTTPAdapter

            session = requests.Session()
            session.headers["User-Agent"] = USER_AGENT
            session.mount("https://", HTTPAdapter(pool_connections=4, pool_maxsize=32))
            _session = session
        return _session


def _http_get(url: str, limit: int) -> bytes:
    import requests

    for attempt in range(FETCH_TRIES):
        retry = attempt + 1 < FETCH_TRIES
        try:
            with _http().get(url, stream=True,
                             timeout=(CONNECT_TIMEOUT_S, READ_TIMEOUT_S)) as response:
                if response.status_code == 404:
                    raise StreamError("USGS has no such file.", code="not_found", status=404)
                if response.status_code >= 500 and retry:
                    time.sleep(FETCH_BACKOFF_S * (attempt + 1))
                    continue
                if response.status_code != 200:
                    raise StreamError(f"USGS answered {response.status_code}.",
                                      code="upstream_unavailable", status=502)
                body = bytearray()
                for chunk in response.iter_content(256 * 1024):
                    body.extend(chunk)
                    if len(body) > limit:
                        raise StreamError("USGS sent more than a piece holds.",
                                          code="upstream_unavailable", status=502)
                return bytes(body)
        except requests.RequestException:
            if retry:
                time.sleep(FETCH_BACKOFF_S * (attempt + 1))
                continue
            raise StreamError("USGS could not be reached.", code="upstream_unavailable",
                              status=502) from None
    raise StreamError("USGS could not be reached.", code="upstream_unavailable", status=502)


def _geoid(lons, lats):
    import terrain_tiles

    if not lons:
        return []
    import numpy as np

    _x, _y, heights = terrain_tiles._geoid_transformer().transform(
        np.asarray(lons, dtype=float), np.asarray(lats, dtype=float), np.zeros(len(lons)))
    return [float(h) for h in np.atleast_1d(heights)]


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.part")
    partial.write_bytes(data)
    partial.replace(path)


class Streamer:
    """Everything streamed, for one cache directory.

    ``fetch(url, limit)`` reads a URL (AWS by default); ``geoid(lons, lats)``
    gives the separation at places; ``index`` is the coverage index as
    ``coverage.load_index`` returns it. Tests pass all three.
    """

    def __init__(self, root: Path, *, fetch=_http_get, geoid=_geoid, index=None,
                 base: str = EPT_BASE, secret: str | None = None):
        self.root = Path(root)
        self._fetch = fetch
        self._geoid = geoid
        self._index = index
        self._base = base.rstrip("/")
        self._secret = secret
        # Re-entrant: finding a survey's index asks for the survey list too.
        self._lock = threading.RLock()
        self._busy = {}
        self._surveys = None
        self._infos = {}
        self._hierarchies = {}
        self._written = 0
        # The photographic pass (``lidar.photo.PhotoPass``) cached pieces are
        # offered to; None for none.
        self.photo = None

    # -- names -------------------------------------------------------------

    def _secret_now(self) -> str:
        return self._secret if self._secret is not None else catalog.key_secret()

    def _load_index(self):
        if self._index is None:
            path = self.root / coverage.CACHE_FILENAME
            self._index = coverage.load_index(path, download=True)
        return self._index

    def _by_key(self) -> dict:
        """Every survey in the index by its key, with its footprint's box."""
        with self._lock:
            if self._surveys is None:
                secret = self._secret_now()
                surveys = {}
                for survey, geometry in self._load_index():
                    if not _SURVEY.match(survey.name):
                        continue
                    surveys[names.survey_key(survey.name, secret=secret)] = (
                        survey, geometry, _box(geometry))
                self._surveys = surveys
            return self._surveys

    def survey_for(self, lat: float, lon: float):
        """The survey to stream for a target, best first as builds choose it,
        and its key; None where USGS has none."""
        candidates = [(survey, geometry, key)
                      for key, (survey, geometry, box) in self._by_key().items()
                      if box and box[0] <= lon <= box[2] and box[1] <= lat <= box[3]]
        covering = [(survey, key) for survey, geometry, key in candidates
                    if coverage.contains(geometry, lon, lat)]
        if not covering:
            return None
        covering.sort(key=lambda item: (-(item[0].year or 0), -item[0].points))
        return covering[0]

    def has_survey(self, key: str) -> bool:
        return catalog.is_valid_key(key) and key in self._by_key()

    # -- what USGS publishes, cached ------------------------------------------

    def _cached_json(self, path: Path, url: str):
        if path.is_file():
            try:
                return json.loads(path.read_bytes())
            except ValueError:
                path.unlink(missing_ok=True)
        data = self._fetch(url, MAX_JSON_BYTES)
        try:
            document = json.loads(data)
        except ValueError:
            raise StreamError("USGS sent an index that is not JSON.",
                              code="upstream_unavailable", status=502) from None
        _write_atomic(path, data)
        return document

    def _survey(self, key: str):
        entry = self._by_key().get(key)
        if entry is None:
            raise StreamError("No such tileset.", code="not_found", status=404)
        return entry[0]

    def info(self, key: str) -> ept.Info:
        info = self._infos.get(key)
        if info is None:
            survey = self._survey(key)
            document = self._cached_json(self.root / key / "ept.json",
                                         f"{self._base}/{survey.name}/ept.json")
            try:
                info = ept.Info.read(document)
            except ept.EptError as error:
                raise StreamError(f"This survey cannot be streamed: {error}",
                                  code="not_streamable", status=404) from None
            self._infos[key] = info
        return info

    def hierarchy(self, key: str) -> ept.Hierarchy:
        with self._lock:
            hierarchy = self._hierarchies.get(key)
            if hierarchy is None:
                survey = self._survey(key)

                def page(node, key=key, survey=survey):
                    return self._cached_json(
                        self.root / key / "h" / f"{node}.json",
                        f"{self._base}/{survey.name}/ept-hierarchy/{node}.json")

                while len(self._hierarchies) >= MAX_SURVEYS_HELD:
                    self._hierarchies.pop(next(iter(self._hierarchies)))
                hierarchy = self._hierarchies[key] = ept.Hierarchy(page)
            return hierarchy

    # -- serving -------------------------------------------------------------

    def _checked_geoid(self, lons, lats):
        separations = self._geoid(lons, lats)
        if any(abs(value) < 1.0 for value in separations):
            # PROJ passes heights through and reports success without its
            # grids; every place in the lower 48 is at least 7 m off.
            raise StreamError("Geoid grids unavailable: heights would be about 30 m out.",
                              code="geoid_unavailable", status=503)
        return separations

    def _page_of(self, key: str, node: ept.Node) -> ept.Node:
        """The index page that lists a node, read from the root down."""
        hierarchy = self.hierarchy(key)
        page = ept.ROOT
        path = []
        current = node
        while current.depth > 0:
            path.append(current)
            current = ept.Node(current.depth - 1, current.x // 2, current.y // 2,
                               current.z // 2)
        for step in [ept.ROOT] + list(reversed(path)):
            count = hierarchy.count(step, page_root=page)
            if count == 0:
                raise StreamError("No such tile.", code="not_found", status=404)
            if count < 0 and step != node:
                page = step
            elif count < 0:
                return page
        return page

    def file(self, key: str, name: str) -> Path:
        """The cached path of a tileset file, made on first ask."""
        match = _FILE.match(name or "")
        if not match:
            raise StreamError("No such file.", code="not_found", status=404)
        stem, kind = match.groups()
        path = self.root / key / name
        if path.is_file():
            self._touch(path)
            self._offer(key, name, kind)
            return path

        survey = self._survey(key)
        if stem == "tileset":
            if kind != "json":
                raise StreamError("No such file.", code="not_found", status=404)
            node = ept.ROOT
        else:
            node = names.piece_node(survey.name, stem, secret=self._secret_now())
            if node is None:
                raise StreamError("No such file.", code="not_found", status=404)

        with self._claim(path):
            if path.is_file():
                return path
            page = self._page_of(key, node)
            if kind == "json":
                data = self._tileset(key, survey, node, page)
            else:
                data = self._piece(key, survey, node)
            _write_atomic(path, data)
            self._wrote()
        self._offer(key, name, kind)
        return path

    # -- photographic colour (lidar.photo) -------------------------------------

    @staticmethod
    def _mark(path: Path) -> Path:
        return path.with_name(path.name + ".photo")

    def photographic(self, key: str, name: str) -> bool:
        """Whether a cached piece has its photograph."""
        return self._mark(self.root / key / name).is_file()

    def _offer(self, key: str, name: str, kind: str) -> None:
        if kind == "pnts" and self.photo is not None and not self.photographic(key, name):
            self.photo.offer(key, name)

    def make_photographic(self, key: str, name: str, image) -> bool:
        """Colour a cached piece from imagery, in place; False when there is
        nothing to do (let go of, or done already)."""
        from . import photo

        path = self.root / key / name
        match = _FILE.match(name or "")
        survey = self._by_key().get(key)
        if not match or match.group(2) != "pnts" or survey is None or not path.is_file() \
                or self.photographic(key, name):
            return False
        node = names.piece_node(survey[0].name, match.group(1), secret=self._secret_now())
        if node is None:
            return False
        try:
            data = path.read_bytes()
        except OSError:
            return False
        recoloured = photo.recolour(data, image, spacing_m=self.info(key).spacing(node))
        with self._claim(path):
            if not path.is_file():
                return False
            _write_atomic(path, recoloured)
            _write_atomic(self._mark(path), b"")
        return True

    def _tileset(self, key, survey, node, page) -> bytes:
        info = self.info(key)
        secret = self._secret_now()
        try:
            document, _joined = ept.tileset(
                info, self.hierarchy(key), node, page_root=page,
                name=lambda n: names.piece_name(survey.name, n, secret=secret),
                geoid=self._checked_geoid)
        except ept.EptError:
            raise StreamError("No such tile.", code="not_found", status=404) from None
        return json.dumps(document, separators=(",", ":")).encode()

    def _piece(self, key, survey, node) -> bytes:
        info = self.info(key)
        data = self._fetch(self._piece_url(survey, node), MAX_PIECE_BYTES)
        lon, lat = ept.centre_lonlat(info, node)
        separation = self._checked_geoid([lon], [lat])[0]
        try:
            span = self._survey_range(key, survey, data if node == ept.ROOT else None)
            body, _kept = pieces.convert(data, classified=survey.likely_classified,
                                         geoid_m=separation, survey_range=span)
        except pieces.PieceError:
            raise StreamError("USGS sent a piece that cannot be read.",
                              code="upstream_unavailable", status=502) from None
        return body

    def _piece_url(self, survey, node) -> str:
        return f"{self._base}/{survey.name}/ept-data/{node}.laz"

    def _survey_range(self, key, survey, root_data=None):
        """The survey's intensity range, taken once from its root piece (a
        sample across all of it) and kept, so every piece is shaded alike.
        ``root_data`` is the root's LAZ where the caller has it already."""
        path = self.root / key / "stats.json"
        with self._claim(path):
            if path.is_file():
                try:
                    found = json.loads(path.read_bytes()).get("intensity")
                    return tuple(found) if found else None
                except (ValueError, TypeError, AttributeError):
                    path.unlink(missing_ok=True)
            data = root_data if root_data is not None else self._fetch(
                self._piece_url(survey, ept.ROOT), MAX_PIECE_BYTES)
            span = pieces.survey_intensity_range(data)
            _write_atomic(path, json.dumps({"intensity": list(span) if span else None}).encode())
            return span

    # -- the cache ------------------------------------------------------------

    def _claim(self, path: Path):
        """One request makes a file; others asking for it meanwhile wait."""
        streamer = self

        class Claim:
            def __enter__(self):
                with streamer._lock:
                    lock = streamer._busy.setdefault(path, [threading.Lock(), 0])
                    lock[1] += 1
                lock[0].acquire()
                self.lock = lock

            def __exit__(self, *_exc):
                self.lock[0].release()
                with streamer._lock:
                    self.lock[1] -= 1
                    if not self.lock[1]:
                        streamer._busy.pop(path, None)

        return Claim()

    @staticmethod
    def _touch(path: Path) -> None:
        try:
            if time.time() - path.stat().st_mtime > TOUCH_EVERY_S:
                os.utime(path)
        except OSError:
            pass

    def _wrote(self) -> None:
        with self._lock:
            self._written += 1
            due = self._written % PRUNE_EVERY == 0
        if due:
            self.prune()

    def prune(self, cap: float | None = None) -> int:
        """Let go of the pieces used longest ago until the cache is under its
        cap. The survey's index and tileset files stay: they are small and
        every view starts from them. Returns the bytes freed."""
        cap = _cap_bytes() if cap is None else cap
        files, total = [], 0
        for path in self.root.rglob("*"):
            try:
                if not path.is_file():
                    continue
                stat = path.stat()
            except OSError:
                continue
            total += stat.st_size
            if path.suffix == ".pnts":
                files.append((stat.st_mtime, stat.st_size, path))
        freed = 0
        for _mtime, size, path in sorted(files):
            if total - freed <= cap:
                break
            try:
                path.unlink()
                freed += size
            except OSError:
                continue
            self._mark(path).unlink(missing_ok=True)
        return freed


def _box(geometry):
    """West, south, east, north of a footprint's outer rings, or None."""
    lons, lats = [], []
    for polygon in coverage._rings(geometry):
        for lon, lat, *_rest in polygon[0]:
            lons.append(lon)
            lats.append(lat)
    return (min(lons), min(lats), max(lons), max(lats)) if lons else None


_streamer = None
_streamer_lock = threading.Lock()


def streamer() -> Streamer | None:
    """The process's streamer, or None with streaming off."""
    global _streamer
    root = cache_dir()
    if root is None:
        return None
    with _streamer_lock:
        if _streamer is None or _streamer.root != root:
            if _streamer is not None and _streamer.photo is not None:
                _streamer.photo.stop()
            _streamer = Streamer(root)
            if (os.environ.get("LIDAR_STREAM_PHOTO") or "on").strip().lower() != "off":
                from . import photo

                _streamer.photo = photo.PhotoPass(_streamer)
        return _streamer
