"""
USGS's topographic map (The National Map, USGSTopo), fetched tile by tile for a mission overlay's base map
(`overlay_image.py`). Keyless and public domain; the same tiles the web's USGS base map draws.

Its tiles stop at level 16 (about 2 m a pixel in the lower 48), and over open water it has none at the finer levels,
where a coarser tile of the same place does exist. So a tile USGS has none for is drawn from its nearest ancestor,
enlarged: the same water, coarser. Only where no ancestor within a few levels exists (outside the United States) is
the area refused.

A GeoTIFF's base (at most a few thousand tiles) is fetched whole and kept for the one request, as the bytes USGS sent,
and decoded when it is drawn (`fetch`, `tile`). An MBTiles file's can be tens of thousands of tiles, more than a server
should hold at once, so it is streamed (`stream`): fetched a little ahead of where the file is being written and let go
of once written. Only the ancestors drawn over water are kept, a few thousand at most.
"""

import io
import time
from collections import OrderedDict, deque
from concurrent.futures import ThreadPoolExecutor

import requests
from PIL import Image
from requests.adapters import HTTPAdapter

URL = 'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}'
MAX_ZOOM = 16
TILE = 256
WORKERS = 16          # about 70 tiles a second from a home connection (measured 2026-11-15)
TRIES = 3
ANCESTOR_LEVELS = 6   # how far up a tile over water is looked for
DECODED_KEPT = 256    # decoded tiles kept at once (about 50 MB)
STREAM_AHEAD = 256    # tiles a stream fetches ahead of the one being written (about 4 MB)
ANCESTORS_KEPT = 2048 # ancestors kept while streaming, as USGS sent them (about 60 MB)


class TopoError(Exception):
    """The topo map could not be had: `code` is `base_unavailable` (USGS did not answer) or `base_not_covered`."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def _session():
    session = requests.Session()
    session.mount('https://', HTTPAdapter(pool_connections=2, pool_maxsize=WORKERS))
    session.headers['User-Agent'] = 'EZPZ mission overlay'
    return session


class UsgsTopo:
    """The tiles one overlay draws on, by (z, x, y) with x already brought into the world."""

    def __init__(self, session=None, sleep=time.sleep):
        self._session = session or _session()
        self._sleep = sleep
        self._raw = {}                  # (z, x, y) -> bytes, or None where USGS has no tile
        self._decoded = OrderedDict()   # (z, x, y) -> RGB image, the newest last
        self._ancestors = OrderedDict() # (z, x, y) -> bytes or None, fetched while streaming, the newest last

    def fetch(self, keys, progress=None):
        """Fetches every tile in `keys` and, for each USGS has none for, its ancestors until one is found.
        `progress(done, total)` is told as the tiles in `keys` come."""
        wanted = set(keys)
        self._get_all(wanted, progress)
        missing = {key for key in wanted if self._raw[key] is None}
        for up in range(1, ANCESTOR_LEVELS + 1):
            if not missing:
                return
            parents = {(z - up, x >> up, y >> up) for z, x, y in missing if z - up >= 0}
            self._get_all(parents)
            missing = {(z, x, y) for z, x, y in missing
                       if z - up < 0 or self._raw[(z - up, x >> up, y >> up)] is None}
        if missing:
            raise TopoError('base_not_covered',
                            'USGS’s topo map does not cover all of this area. Make the overlay without a base map.')

    def _get_all(self, keys, progress=None):
        todo = [key for key in keys if key not in self._raw]
        if not todo:
            return
        pool = ThreadPoolExecutor(min(WORKERS, len(todo)))
        try:
            for done, (key, data) in enumerate(zip(todo, pool.map(self._get, todo)), start=1):
                self._raw[key] = data
                if progress is not None:
                    progress(done, len(todo))   # may raise to stop: what was not yet fetched is not
        finally:
            pool.shutdown(wait=True, cancel_futures=True)

    def stream(self, keys):
        """Yields (key, bytes or None) for each of `keys`, in order, fetched up to STREAM_AHEAD ahead and kept by
        nobody: None is a tile USGS has none for, which `image_of` draws from an ancestor. Closing the generator (or
        an error) lets go of what was asked ahead."""
        keys = iter(keys)
        pool = ThreadPoolExecutor(WORKERS)
        pending = deque()
        try:
            for key in keys:
                pending.append((key, pool.submit(self._get, key)))
                if len(pending) >= STREAM_AHEAD:
                    break
            while pending:
                key, future = pending.popleft()
                data = future.result()
                for following in keys:
                    pending.append((following, pool.submit(self._get, following)))
                    break
                yield key, data
        finally:
            pool.shutdown(wait=True, cancel_futures=True)

    def jpeg_of(self, key, data):
        """A streamed tile's own bytes where USGS sent a JPEG, to be kept as they came; else None."""
        return data if data is not None and data[:3] == b'\xff\xd8\xff' else None

    def image_of(self, key, data):
        """A streamed tile as a 256-pixel RGB image: its own bytes, or the part of its nearest ancestor that covers it,
        enlarged, fetched once and kept while streaming."""
        if data is not None:
            return self._read(data)
        z, x, y = key
        for up in range(1, ANCESTOR_LEVELS + 1):
            if z - up < 0:
                break
            parent = (z - up, x >> up, y >> up)
            if parent in self._raw:
                raw = self._raw[parent]
            elif parent in self._ancestors:
                self._ancestors.move_to_end(parent)
                raw = self._ancestors[parent]
            else:
                raw = self._get(parent)
                self._ancestors[parent] = raw
                if len(self._ancestors) > ANCESTORS_KEPT:
                    self._ancestors.popitem(last=False)
            if raw is not None:
                return self._enlarged(self._decode(parent, raw), key, parent, up)
        raise TopoError('base_not_covered',
                        'USGS’s topo map does not cover all of this area. Make the overlay without a base map.')

    def _get(self, key):
        z, x, y = key
        url = URL.format(z=z, x=x, y=y)
        for attempt in range(TRIES):
            try:
                answer = self._session.get(url, timeout=(5, 20), allow_redirects=False)
            except requests.RequestException:
                answer = None
            if answer is not None:
                if answer.status_code == 200 and answer.content:
                    return answer.content
                if answer.status_code == 404:
                    return None
            if attempt + 1 < TRIES:
                self._sleep(0.5 * (attempt + 1))
        raise TopoError('base_unavailable',
                        'The USGS topo map could not be fetched. Try again, or make the overlay without it.')

    def jpeg(self, z, x, y):
        """The tile's own bytes where USGS sent a JPEG for it, to be kept as they came; else None."""
        data = self._raw.get((z, x, y))
        return data if data is not None and data[:3] == b'\xff\xd8\xff' else None

    def tile(self, z, x, y):
        """The tile as a 256-pixel RGB image: its own, or the part of its nearest ancestor that covers it, enlarged."""
        for up in range(0, ANCESTOR_LEVELS + 1):
            key = (z - up, x >> up, y >> up)
            if self._raw.get(key) is None:
                continue
            image = self._decode(key, self._raw[key])
            if up == 0:
                return image
            return self._enlarged(image, (z, x, y), key, up)
        raise TopoError('base_not_covered',
                        'USGS’s topo map does not cover all of this area. Make the overlay without a base map.')

    @staticmethod
    def _enlarged(image, key, parent, up):
        """The part of `parent`'s image that covers `key`, `up` levels finer, enlarged to a tile."""
        size = TILE / 2 ** up
        left = (key[1] - (parent[1] << up)) * size
        top = (key[2] - (parent[2] << up)) * size
        return image.resize((TILE, TILE), Image.Resampling.BICUBIC, box=(left, top, left + size, top + size))

    @staticmethod
    def _read(data):
        try:
            image = Image.open(io.BytesIO(data))
            image.load()
        except Exception:  # noqa: BLE001 — Pillow raises many kinds for a file it cannot read
            raise TopoError('base_unavailable', 'The USGS topo map sent a tile that could not be read. Try again.') from None
        if image.size != (TILE, TILE):
            raise TopoError('base_unavailable', 'The USGS topo map sent a tile that could not be read. Try again.')
        return image.convert('RGB')

    def _decode(self, key, data):
        image = self._decoded.get(key)
        if image is not None:
            self._decoded.move_to_end(key)
            return image
        image = self._read(data)
        self._decoded[key] = image
        if len(self._decoded) > DECODED_KEPT:
            self._decoded.popitem(last=False)
        return image
