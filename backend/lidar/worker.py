"""Build service: generates point clouds on request, beside the tileset store.

    python -m lidar.worker

Runs inside the LiDAR toolchain image, where PDAL and py3dtiles live, and
writes into the same tileset directory the API serves from. The API forwards
build requests here; it never runs the tools itself, so the web-facing
container needs neither the 5 GB toolchain nor access to Docker.

Deliberately small. One build runs at a time — a build saturates a CPU core and
can read hundreds of megabytes — and the rest wait in a bounded queue. Each
build is staged under a hidden name and moved into place only once complete,
so the API never serves a half-written tileset. Coordinates are never logged:
the log carries tileset keys, which are opaque, and stage names.

Configuration (environment):

    LIDAR_TILES_DIR          tileset store, shared with the API   (/data/tiles)
    LIDAR_COLLECTION         downloaded LAZ to prefer over AWS    (unset: AWS only)
    LIDAR_CACHE_DIR          where the USGS coverage index lives  (/data/cache in the image)
    LIDAR_IMAGERY            GDAL raster for point colour         (bundled Mapbox description)
    LIDAR_BUILD_CONTEXT_M    also build a landscape ring this wide (unset: none)
    LIDAR_BUILDER_TOKEN      shared secret the API must present   (unset: no check)
    LIDAR_BUILDER_PORT       listening port                       (8090)
    LIDAR_BUILDER_QUEUE      maximum queued + running builds      (8)
"""

from __future__ import annotations

import hmac
import itertools
import json
import os
import queue
import re
import shutil
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import catalog, pipeline
from .build import BuildError, BuildRequest, build_for_target

STAGING_PREFIX = ".staging-"
DEFAULT_PORT = 8090
DEFAULT_QUEUE_LIMIT = 8

# Builds are interactive — someone is waiting in the 3D window — so the area is
# capped at an LZ-sized radius. Wider landscape comes from the context ring.
RADIUS_RANGE = (50.0, 1000.0)

# Finished jobs are kept long enough for a poller to see the outcome, then
# dropped so the job table cannot grow without bound.
FINISHED_TTL_S = 3600

TOKEN_HEADER = "X-Builder-Token"
BUNDLED_IMAGERY = Path(__file__).with_name("mapbox_imagery.xml")

ACTIVE = ("queued", "running")


class QueueFull(RuntimeError):
    """Too many builds already waiting."""


def _log(message: str) -> None:
    print(f"[lidar-builder] {message}", file=sys.stderr, flush=True)


def request_factory(environ=os.environ):
    """Turn a target into a BuildRequest using the service's configuration."""
    collection = environ.get("LIDAR_COLLECTION") or None
    imagery = environ.get("LIDAR_IMAGERY") or (
        str(BUNDLED_IMAGERY) if BUNDLED_IMAGERY.exists() else None)
    context = environ.get("LIDAR_BUILD_CONTEXT_M")
    context_m = float(context) if context else None

    def make(lat: float, lon: float, radius_m: float) -> BuildRequest:
        return BuildRequest(
            lat=lat, lon=lon, radius_m=radius_m,
            context_m=context_m if context_m and context_m > radius_m else None,
            # Photographic colour when imagery is available; classification
            # colours otherwise, which still separate ground from canopy.
            color_by=(pipeline.COLOR_BY_IMAGERY if imagery
                      else pipeline.COLOR_BY_CLASSIFICATION),
            imagery=imagery,
            collection=Path(collection) if collection else None,
            # A target off the edge of the download is built from AWS rather
            # than refused, or built with one side missing.
            collection_fallback=True,
        )

    return make


class Builder:
    """A single-worker build queue writing into a tileset store."""

    def __init__(self, root, *, make_request, build=build_for_target,
                 queue_limit=DEFAULT_QUEUE_LIMIT, clock=time.time):
        self.root = Path(root)
        self.make_request = make_request
        self.build = build
        self.queue_limit = queue_limit
        self.clock = clock
        self._jobs: dict[str, dict] = {}
        self._queue: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._sequence = itertools.count()
        self._thread = None

    # -- requests ---------------------------------------------------------

    def submit(self, lat: float, lon: float, radius_m: float) -> dict:
        """Queue a build, or report the one already queued or finished.

        Asking twice for the same place returns the same job — the 3D window
        re-requests on reopen, and a second build of identical ground would
        only cost time.
        """
        key = catalog.key_for(lat, lon, radius_m=radius_m)
        with self._lock:
            self._prune()
            if catalog.exists(key, root=self.root):
                return {"key": key, "state": "done"}
            job = self._jobs.get(key)
            if job and job["state"] in ACTIVE:
                return self._snapshot(job)
            active = sum(1 for j in self._jobs.values() if j["state"] in ACTIVE)
            if active >= self.queue_limit:
                raise QueueFull(f"{active} builds already waiting")
            job = {"key": key, "state": "queued", "stage": "waiting",
                   "error": None, "seq": next(self._sequence),
                   "queued_at": self.clock(), "started_at": None,
                   "finished_at": None}
            self._jobs[key] = job
            self._queue.put((key, lat, lon, radius_m))
            return self._snapshot(job)

    def status(self, key: str) -> dict | None:
        with self._lock:
            job = self._jobs.get(key)
            if job:
                return self._snapshot(job)
        if catalog.exists(key, root=self.root):
            return {"key": key, "state": "done"}
        return None

    def pending(self) -> int:
        with self._lock:
            return sum(1 for j in self._jobs.values() if j["state"] in ACTIVE)

    def _snapshot(self, job: dict) -> dict:
        view = {k: job[k] for k in ("key", "state", "stage", "error")}
        now = self.clock()
        if job["state"] == "queued":
            view["position"] = sum(
                1 for j in self._jobs.values()
                if j["state"] in ACTIVE and j["seq"] < job["seq"])
        if job["started_at"]:
            view["elapsed_s"] = round((job["finished_at"] or now) - job["started_at"])
        return view

    def _prune(self) -> None:
        cutoff = self.clock() - FINISHED_TTL_S
        for key in [k for k, j in self._jobs.items()
                    if j["state"] not in ACTIVE and (j["finished_at"] or 0) < cutoff]:
            del self._jobs[key]

    # -- work -------------------------------------------------------------

    def start(self) -> None:
        self._clear_stale_staging()
        self._thread = threading.Thread(target=self._run, name="lidar-builder",
                                        daemon=True)
        self._thread.start()

    def _run(self) -> None:
        while True:
            item = self._queue.get()
            try:
                self.process(*item)
            finally:
                self._queue.task_done()

    def process(self, key: str, lat: float, lon: float, radius_m: float) -> None:
        """Run one build. Public so tests can drive it without the thread."""
        job = self._jobs[key]
        staging = self.root / f"{STAGING_PREFIX}{key}"
        final = self.root / key

        def stage(name: str) -> None:
            job["stage"] = name
            _log(f"{key} {name}")

        with self._lock:
            job.update(state="running", stage="starting", started_at=self.clock())
        _log(f"{key} started")

        try:
            shutil.rmtree(staging, ignore_errors=True)
            self.root.mkdir(parents=True, exist_ok=True)
            self.build(self.make_request(lat, lon, radius_m), staging, stage=stage)
            # Complete on disk before it becomes visible under its real name.
            if final.exists():
                shutil.rmtree(final)
            os.replace(staging, final)
            with self._lock:
                job.update(state="done", stage="done", finished_at=self.clock())
            _log(f"{key} done in {round(job['finished_at'] - job['started_at'])}s")
        except BuildError as error:
            self._fail(job, str(error))
        except Exception:  # noqa: BLE001 — a crashed build must not kill the queue
            _log(f"{key} crashed:\n{traceback.format_exc()}")
            self._fail(job, "The build failed unexpectedly. Check the builder log.")
        finally:
            shutil.rmtree(staging, ignore_errors=True)

    def _fail(self, job: dict, message: str) -> None:
        with self._lock:
            job.update(state="failed", error=message, finished_at=self.clock())
        _log(f"{job['key']} failed: {message}")

    def _clear_stale_staging(self) -> None:
        """Remove builds left half-done by a restart; they can never finish."""
        if not self.root.is_dir():
            return
        for child in self.root.iterdir():
            if child.is_dir() and child.name.startswith(STAGING_PREFIX):
                shutil.rmtree(child, ignore_errors=True)


# -- HTTP -----------------------------------------------------------------

_JOB_PATH = re.compile(r"^/builds/([0-9a-f]{16})$")


def _number(body: dict, name: str, low: float, high: float) -> float:
    value = float(body[name])
    if not low <= value <= high:
        raise ValueError(f"{name} out of range")
    return value


def make_handler(builder: Builder, token: str | None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "lidar-builder/1"

        def log_message(self, fmt, *args):
            # Paths carry only opaque keys, but stay quiet anyway; the build
            # log above says what matters.
            return

        def _send(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _authorised(self) -> bool:
            if not token:
                return True
            presented = self.headers.get(TOKEN_HEADER, "")
            return hmac.compare_digest(presented.encode(), token.encode())

        def do_GET(self):
            if self.path == "/health":
                return self._send(200, {"ok": True, "pending": builder.pending()})
            if not self._authorised():
                return self._send(401, {"error": "unauthorised"})
            match = _JOB_PATH.match(self.path)
            if not match:
                return self._send(404, {"error": "not found"})
            job = builder.status(match.group(1))
            if job is None:
                return self._send(404, {"error": "no such build"})
            return self._send(200, job)

        def do_POST(self):
            if self.path != "/builds":
                return self._send(404, {"error": "not found"})
            if not self._authorised():
                return self._send(401, {"error": "unauthorised"})
            try:
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
                lat = _number(body, "lat", -90.0, 90.0)
                lon = _number(body, "lon", -180.0, 180.0)
                radius = _number(body, "radius_m", *RADIUS_RANGE)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "lat, lon and radius_m are required"})
            try:
                return self._send(202, builder.submit(lat, lon, radius))
            except QueueFull:
                return self._send(429, {"error": "The build queue is full. Try again shortly."})

    return Handler


def main() -> int:
    root = Path(os.environ.get("LIDAR_TILES_DIR", catalog.DEFAULT_TILES_DIR))
    port = int(os.environ.get("LIDAR_BUILDER_PORT", DEFAULT_PORT))
    token = os.environ.get("LIDAR_BUILDER_TOKEN") or None
    limit = int(os.environ.get("LIDAR_BUILDER_QUEUE", DEFAULT_QUEUE_LIMIT))

    builder = Builder(root, make_request=request_factory(), queue_limit=limit)
    builder.start()

    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(builder, token))
    _log(f"listening on :{port}, writing to {root}"
         f"{'' if token else ' (no token set — reachable by anything on its network)'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
