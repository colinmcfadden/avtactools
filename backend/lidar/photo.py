"""Photographic colour for streamed pieces, laid on in the background.

docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, *Photographic colour, in the
background* (the owner's choice of 2026-10-26: USGS's service). A piece is
streamed in its natural palette (``lidar.pieces``) so the first view never
waits; once it is cached it is offered here, and two workers of the pass's own,
apart from gunicorn's request threads, fetch USDA's NAIP imagery for its box
from USGS The National Map, colour each point from the pixel under it and write
the piece again under the same name. A later view of that ground is
photographic.

- The positions are not touched: only the RGB bytes of the ``.pnts`` change.
- A piece made photographic is marked beside it (``<name>.photo``), which is
  how the server knows to let a browser keep it for the week (a palette piece
  is kept an hour, so the photograph reaches a later view).
- Measured at Ellijay (2026-10-26): with the pass running, a cold first view
  was as fast as without (first points 1.5 s, the LZ's square 11.4 s); the
  pass then made 164 of its 168 pieces photographic in about 8.6 minutes, a
  median of 3.3 s an image (the slowest 47 s), 40–60 ms of CPU a piece.
- Nothing is fetched while an analysis runs, as terrain warming waits.
- A fetch that fails leaves the palette, and the piece is offered again when
  it is next served, after ``retry_after_s`` (10 minutes).
- The imagery is NAIP 2023 (leaf-on) at Ellijay, the LiDAR 2018: a tree cut
  since colours the canopy's points as ground, and the reverse.
"""

from __future__ import annotations

import collections
import io
import json
import struct
import threading
import time

import numpy as np

NAIP_EXPORT = ("https://imagery.nationalmap.gov/arcgis/rest/services/"
               "USGSNAIPImagery/ImageServer/exportImage")
CREDIT = "Point colour: USDA NAIP, from USGS The National Map"

# NAIP's own resolution: nothing is gained asking for finer.
MIN_PIXEL_M = 0.6
# The service gives up to 4,000 a side; a coarse piece needs far fewer.
MAX_PIXELS = 2000
FETCH_TIMEOUT_S = (5, 60)
FETCH_TRIES = 2

DEFAULT_WORKERS = 2
DEFAULT_LIMIT = 5000
# A fetch that failed (a timeout, the service busy) is worth another try soon.
DEFAULT_RETRY_AFTER_S = 600

_A = 6378137.0
_F = 1.0 / 298.257223563
_E2 = _F * (2.0 - _F)


class PhotoError(RuntimeError):
    """No imagery for a piece this time; the palette stays."""


_session = None
_session_lock = threading.Lock()


def _http():
    global _session
    with _session_lock:
        if _session is None:
            import requests
            from requests.adapters import HTTPAdapter

            session = requests.Session()
            session.headers["User-Agent"] = "avtactools-lidar/1.0"
            session.mount("https://", HTTPAdapter(pool_maxsize=4))
            _session = session
        return _session


def usgs_image(box, width: int, height: int) -> np.ndarray:
    """NAIP over ``box`` (EPSG:3857 metres) as a ``height × width`` RGB array."""
    import requests
    from PIL import Image

    params = {"bbox": ",".join(str(float(v)) for v in box), "bboxSR": 3857, "imageSR": 3857,
              "size": f"{width},{height}", "format": "jpg", "f": "image"}
    for attempt in range(FETCH_TRIES):
        try:
            response = _http().get(NAIP_EXPORT, params=params, timeout=FETCH_TIMEOUT_S)
        except requests.RequestException:
            if attempt + 1 < FETCH_TRIES:
                continue
            raise PhotoError("USGS could not be reached") from None
        if response.status_code >= 500 and attempt + 1 < FETCH_TRIES:
            continue
        # A fault comes back as a 200 with JSON in it.
        if response.status_code != 200 or not response.headers.get("Content-Type", "").startswith("image/"):
            raise PhotoError(f"USGS answered {response.status_code} without an image")
        try:
            image = np.asarray(Image.open(io.BytesIO(response.content)).convert("RGB"))
        except Exception:  # noqa: BLE001 — Pillow raises many kinds
            raise PhotoError("USGS sent an image that cannot be read") from None
        if image.shape != (height, width, 3):
            raise PhotoError(f"USGS sent {image.shape[1]} × {image.shape[0]}, not {width} × {height}")
        return image
    raise PhotoError("USGS could not be reached")


def image_size(box, *, spacing_m: float) -> tuple:
    """Pixels across and down for a piece's box: NAIP's 0.6 m, or half the
    piece's spacing where that is coarser, at most ``MAX_PIXELS`` a side."""
    width_m, height_m = box[2] - box[0], box[3] - box[1]
    pixel = max(MIN_PIXEL_M, spacing_m / 2.0, max(width_m, height_m) / MAX_PIXELS)
    return max(1, int(round(width_m / pixel))), max(1, int(round(height_m / pixel)))


def sample(image: np.ndarray, box, x, y) -> np.ndarray:
    """The image's colour at each place, blended between the four pixel
    centres round it; a place past the outer centres takes the edge."""
    height, width = image.shape[:2]
    fx = (np.asarray(x, float) - box[0]) / (box[2] - box[0]) * width - 0.5
    fy = (box[3] - np.asarray(y, float)) / (box[3] - box[1]) * height - 0.5
    fx = np.clip(fx, 0.0, width - 1.0)
    fy = np.clip(fy, 0.0, height - 1.0)
    x0 = np.minimum(np.floor(fx).astype(int), max(width - 2, 0))
    y0 = np.minimum(np.floor(fy).astype(int), max(height - 2, 0))
    x1, y1 = np.minimum(x0 + 1, width - 1), np.minimum(y0 + 1, height - 1)
    ax, ay = (fx - x0)[:, None], (fy - y0)[:, None]
    pixels = image.astype(np.float64)
    blended = (pixels[y0, x0] * (1 - ax) * (1 - ay) + pixels[y0, x1] * ax * (1 - ay)
               + pixels[y1, x0] * (1 - ax) * ay + pixels[y1, x1] * ax * ay)
    return np.clip(np.rint(blended), 0, 255).astype(np.uint8)


def _tables(data: bytes):
    _magic, _version, _length, table_json, table_binary, _bj, _bb = struct.unpack("<4s6I", data[:28])
    table = json.loads(data[28:28 + table_json])
    return table, 28 + table_json


def _mercator(data: bytes, table: dict, body: int):
    """A piece's points in EPSG:3857 metres, from its quantized ECEF."""
    count = int(table["POINTS_LENGTH"])
    quantized = np.frombuffer(data, "<u2", 3 * count,
                              body + table["POSITION_QUANTIZED"]["byteOffset"]).reshape(-1, 3)
    ecef = (np.array(table["QUANTIZED_VOLUME_OFFSET"])
            + quantized / 65535.0 * np.array(table["QUANTIZED_VOLUME_SCALE"]))
    lon = np.arctan2(ecef[:, 1], ecef[:, 0])
    p = np.hypot(ecef[:, 0], ecef[:, 1])
    lat = np.arctan2(ecef[:, 2], p * (1 - _E2))
    for _ in range(3):
        normal = _A / np.sqrt(1 - _E2 * np.sin(lat) ** 2)
        height = p / np.cos(lat) - normal
        lat = np.arctan2(ecef[:, 2], p * (1 - _E2 * normal / (normal + height)))
    return lon * _A, _A * np.log(np.tan(np.pi / 4 + lat / 2))


def recolour(data: bytes, image, *, spacing_m: float) -> bytes:
    """A ``.pnts`` with each point coloured from imagery: ``image(box, width,
    height)`` gives the RGB array over the points' box. Only the colour bytes
    change."""
    table, body = _tables(data)
    count = int(table.get("POINTS_LENGTH", 0))
    if not count:
        return data
    x, y = _mercator(data, table, body)
    box = list(map(float, (x.min(), y.min(), x.max(), y.max())))
    # A side under a metre (one point, or a row of them) is widened to a metre
    # about its middle, the other side kept.
    for low, high in ((0, 2), (1, 3)):
        if box[high] - box[low] < 1.0:
            middle = (box[low] + box[high]) / 2
            box[low], box[high] = middle - 0.5, middle + 0.5
    box = tuple(box)
    width, height = image_size(box, spacing_m=spacing_m)
    pixels = np.asarray(image(box, width, height))
    if pixels.shape != (height, width, 3):
        raise PhotoError(f"imagery of shape {pixels.shape}, not {(height, width, 3)}")
    rgb = sample(pixels, box, x, y)
    start = body + table["RGB"]["byteOffset"]
    return data[:start] + rgb.tobytes() + data[start + 3 * count:]


class PhotoPass:
    """The queue of cached pieces waiting for their photograph, and its workers.

    ``streamer`` is the ``lidar.stream.Streamer`` whose cache it recolours;
    ``image`` fetches imagery (``usgs_image``); ``workers`` threads of its own
    (0 in tests, which run ``run_pending``).
    """

    def __init__(self, streamer, *, image=usgs_image, workers: int = DEFAULT_WORKERS,
                 limit: int = DEFAULT_LIMIT, retry_after_s: float = DEFAULT_RETRY_AFTER_S):
        self.streamer = streamer
        self._image = image
        self._limit = limit
        self._retry_after_s = retry_after_s
        self._lock = threading.Lock()
        self._ready = threading.Condition(self._lock)
        self._queue = collections.deque()
        self._offered = set()
        self._failed = {}
        self._stopped = False
        self._threads = [threading.Thread(target=self._work, name=f"lidar-photo-{n}", daemon=True)
                         for n in range(workers)]
        for thread in self._threads:
            thread.start()

    # -- offering ----------------------------------------------------------

    def offer(self, key: str, name: str) -> bool:
        """Queue a cached palette piece; False when it is queued already, failed
        lately, or the queue is full."""
        job = (key, name)
        with self._lock:
            if job in self._offered or len(self._queue) >= self._limit:
                return False
            failed_at = self._failed.get(job)
            if failed_at is not None and time.monotonic() - failed_at < self._retry_after_s:
                return False
            self._failed.pop(job, None)
            self._offered.add(job)
            self._queue.append(job)
            self._ready.notify()
            return True

    def waiting(self) -> int:
        with self._lock:
            return len(self._queue)

    def forget(self) -> None:
        """Drop what was offered and waits: as after a restart."""
        with self._lock:
            self._queue.clear()
            self._offered.clear()

    def stop(self) -> None:
        with self._lock:
            self._stopped = True
            self._ready.notify_all()

    # -- working -----------------------------------------------------------

    @staticmethod
    def _quiet(wait_s=None) -> bool:
        """Whether no analysis runs (waiting for one to end, up to ``wait_s``)."""
        try:
            import terrain_tiles
        except ImportError:
            return True
        return terrain_tiles._no_analysis.wait(wait_s)

    def run_pending(self, wait_s=None) -> int:
        """Recolour everything waiting, in this thread; how many were made
        photographic. Stops early, leaving the rest, while an analysis runs
        past ``wait_s``."""
        done = 0
        while True:
            if not self._quiet(wait_s):
                return done
            with self._lock:
                if not self._queue:
                    return done
                job = self._queue.popleft()
            done += self._run(job)

    def _work(self) -> None:
        while True:
            with self._lock:
                while not self._queue and not self._stopped:
                    self._ready.wait()
                if self._stopped:
                    return
                job = self._queue.popleft()
            self._quiet()
            try:
                self._run(job)
            except Exception:  # noqa: BLE001 — a worker never dies of one piece
                pass

    def _run(self, job) -> int:
        key, name = job
        try:
            made = self.streamer.make_photographic(key, name, self._image)
        except PhotoError:
            made = False
            with self._lock:
                self._failed[job] = time.monotonic()
        with self._lock:
            self._offered.discard(job)
        return int(bool(made))
