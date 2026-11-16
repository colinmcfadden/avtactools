"""
USGS's topographic map (The National Map, USGSTopo), fetched tile by tile for a mission overlay's base map
(`overlay_image.py`). Keyless and public domain; the same tiles the web's USGS base map draws.

Its tiles stop at level 16 (about 2 m a pixel in the lower 48), and over open water it has none at the finer levels,
where a coarser tile of the same place does exist. So a tile USGS has none for is drawn from its nearest ancestor,
enlarged: the same water, coarser. Only where no ancestor within a few levels exists (outside the United States) is
the area refused.

Everything fetched is kept for the one request, as the bytes USGS sent, and decoded when it is drawn.
"""

import io
import time
from collections import OrderedDict
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

    def fetch(self, keys):
        """Fetches every tile in `keys` and, for each USGS has none for, its ancestors until one is found."""
        wanted = set(keys)
        self._get_all(wanted)
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

    def _get_all(self, keys):
        todo = [key for key in keys if key not in self._raw]
        if not todo:
            return
        with ThreadPoolExecutor(min(WORKERS, len(todo))) as pool:
            for key, data in zip(todo, pool.map(self._get, todo)):
                self._raw[key] = data

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
            image = self._decode(key)
            if up == 0:
                return image
            size = TILE / 2 ** up
            left = (x - (key[1] << up)) * size
            top = (y - (key[2] << up)) * size
            return image.resize((TILE, TILE), Image.Resampling.BICUBIC, box=(left, top, left + size, top + size))
        raise TopoError('base_not_covered',
                        'USGS’s topo map does not cover all of this area. Make the overlay without a base map.')

    def _decode(self, key):
        image = self._decoded.get(key)
        if image is not None:
            self._decoded.move_to_end(key)
            return image
        try:
            image = Image.open(io.BytesIO(self._raw[key]))
            image.load()
        except Exception:  # noqa: BLE001 — Pillow raises many kinds for a file it cannot read
            raise TopoError('base_unavailable', 'The USGS topo map sent a tile that could not be read. Try again.') from None
        if image.size != (TILE, TILE):
            raise TopoError('base_unavailable', 'The USGS topo map sent a tile that could not be read. Try again.')
        image = image.convert('RGB')
        self._decoded[key] = image
        if len(self._decoded) > DECODED_KEPT:
            self._decoded.popitem(last=False)
        return image
