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

A build runs only while someone wants it. The 3D window asks after its build
every few seconds; a job nobody has asked about for ABANDON_AFTER_S is dropped
— queued or mid-run — unless it was asked to be kept (a saved LZ). Without
this, a refreshed page left its build running and every LZ opened afterwards
queued behind work nobody would ever look at.

Builds someone is watching run first. A saved LZ asks for its point cloud in
the background (kept, nobody watching); that should be ready by the time
anyone opens it, but never make a person in the 3D window wait behind it.

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
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from . import catalog, pipeline
from .build import BuildError, BuildRequest, build_for_target
from .tiles import TileBuildError

STAGING_PREFIX = ".staging-"
DEFAULT_PORT = 8090
DEFAULT_QUEUE_LIMIT = 8

# Builds are interactive — someone is waiting in the 3D window — so the area is
# capped at an LZ-sized radius. Wider landscape comes from the context ring.
RADIUS_RANGE = (50.0, 1000.0)

# Finished jobs are kept long enough for a poller to see the outcome, then
# dropped so the job table cannot grow without bound.
FINISHED_TTL_S = 3600

# How long a build survives without anyone asking after it. The 3D window asks
# every 3 s, but a browser slows a background tab's timers to once a minute, so
# this has to outlast that or a build would be dropped while its owner simply
# looked at another tab.
ABANDON_AFTER_S = 90
REAP_INTERVAL_S = 5

# Someone is actively waiting on a build if they asked after it this recently.
# The 3D window asks every 3 s; a save's background request asks once.
WAITING_WITHIN_S = 15

# How often a running tool is checked for cancellation.
CANCEL_POLL_S = 0.5

# Who is waiting on a build: an opaque id per browser tab. Requests without one
# share a single anonymous watcher.
ANONYMOUS = "anonymous"
_WATCHER = re.compile(r"^[A-Za-z0-9_-]{8,64}$")

TOKEN_HEADER = "X-Builder-Token"
BUNDLED_IMAGERY = Path(__file__).with_name("mapbox_imagery.xml")

ACTIVE = ("queued", "running")


class QueueFull(RuntimeError):
    """Too many builds already waiting."""


class BuildCancelled(Exception):
    """The build was dropped because nobody was waiting for it any more."""


def _watcher(value) -> str:
    return value if isinstance(value, str) and _WATCHER.match(value) else ANONYMOUS


def _kill(process: subprocess.Popen) -> None:
    """Stop a tool and anything it started.

    py3dtiles converts with a pool of worker processes; killing only the
    parent would leave them running. Each tool is started in its own session,
    so its process group is everything it spawned.
    """
    try:
        if hasattr(os, "killpg"):
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except OSError:
        pass


def cancellable_runner(cancel: threading.Event, *, poll_s: float = CANCEL_POLL_S):
    """Run the external tools, stopping the current one when ``cancel`` is set.

    Behaves like tiles._run otherwise: stdout on success, TileBuildError with
    the tool's own message on failure.
    """
    def run(command):
        if cancel.is_set():
            raise BuildCancelled()
        process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True,
                                   start_new_session=True)
        while True:
            try:
                stdout, stderr = process.communicate(timeout=poll_s)
                break
            except subprocess.TimeoutExpired:
                if cancel.is_set():
                    _kill(process)
                    process.communicate()
                    raise BuildCancelled() from None
        if process.returncode != 0:
            raise TileBuildError(
                f"{command[0]} failed ({process.returncode}):\n"
                f"{(stderr or stdout or '').strip()[:2000]}"
            )
        return stdout

    return run


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
        self._lock = threading.Lock()
        # Wakes the worker thread when something is queued.
        self._ready = threading.Condition(self._lock)
        self._sequence = itertools.count()
        self._thread = None

    # -- requests ---------------------------------------------------------

    def submit(self, lat: float, lon: float, radius_m: float, *,
               keep: bool = False, watcher: str | None = None) -> dict:
        """Queue a build, or report the one already queued or finished.

        Asking twice for the same place returns the same job — the 3D window
        re-requests on reopen, and a second build of identical ground would
        only cost time. ``keep`` builds it even once nobody is waiting.
        """
        key = catalog.key_for(lat, lon, radius_m=radius_m)
        with self._lock:
            self._prune()
            if catalog.exists(key, root=self.root):
                return {"key": key, "state": "done"}
            job = self._jobs.get(key)
            if job and job["state"] in ACTIVE:
                self._watch(job, watcher, keep)
                return self._snapshot(job)
            active = sum(1 for j in self._jobs.values() if j["state"] in ACTIVE)
            if active >= self.queue_limit:
                raise QueueFull(f"{active} builds already waiting")
            job = {"key": key, "state": "queued", "stage": "waiting",
                   "error": None, "seq": next(self._sequence),
                   "queued_at": self.clock(), "started_at": None,
                   "finished_at": None, "keep": False, "watchers": {},
                   "cancel": threading.Event(), "target": (lat, lon, radius_m)}
            self._watch(job, watcher, keep)
            self._jobs[key] = job
            self._ready.notify()
            return self._snapshot(job)

    def status(self, key: str, *, watcher: str | None = None,
               keep: bool = False) -> dict | None:
        """A build's progress. Asking counts as still waiting for it."""
        with self._lock:
            job = self._jobs.get(key)
            if job:
                if job["state"] in ACTIVE:
                    self._watch(job, watcher, keep)
                return self._snapshot(job)
        if catalog.exists(key, root=self.root):
            return {"key": key, "state": "done"}
        return None

    def release(self, key: str, *, watcher: str | None = None) -> dict | None:
        """This watcher has stopped waiting; drop the build if nobody else is."""
        with self._lock:
            job = self._jobs.get(key)
            if not job:
                return None
            job["watchers"].pop(_watcher(watcher), None)
            if job["state"] in ACTIVE and self._abandoned(job, self.clock()):
                self._cancel(job)
            return self._snapshot(job)

    def reap(self) -> None:
        """Drop every build nobody has asked after recently."""
        with self._lock:
            now = self.clock()
            for job in list(self._jobs.values()):
                if job["state"] in ACTIVE and self._abandoned(job, now):
                    self._cancel(job)

    def _watch(self, job: dict, watcher: str | None, keep: bool) -> None:
        job["watchers"][_watcher(watcher)] = self.clock()
        if keep:
            job["keep"] = True

    @staticmethod
    def _rank(job: dict, now: float) -> tuple:
        """Order to build in: anyone actively waiting first, then oldest first."""
        waiting = any(now - seen <= WAITING_WITHIN_S for seen in job["watchers"].values())
        return (not waiting, job["seq"])

    def next_job(self, *, block: bool = True) -> dict | None:
        """The queued job to build next. Public so tests can check the order."""
        with self._ready:
            while True:
                now = self.clock()
                queued = [j for j in self._jobs.values() if j["state"] == "queued"]
                if queued:
                    return min(queued, key=lambda j: self._rank(j, now))
                if not block:
                    return None
                self._ready.wait()

    @staticmethod
    def _abandoned(job: dict, now: float) -> bool:
        if job["keep"]:
            return False
        return all(now - seen > ABANDON_AFTER_S for seen in job["watchers"].values())

    def _cancel(self, job: dict) -> None:
        """Call with the lock held."""
        if job["state"] == "queued":
            job.update(state="cancelled", stage="cancelled", finished_at=self.clock())
            _log(f"{job['key']} dropped before starting: nobody is waiting for it")
        elif not job["cancel"].is_set():
            job["cancel"].set()
            _log(f"{job['key']} stopping: nobody is waiting for it")

    def pending(self) -> int:
        with self._lock:
            return sum(1 for j in self._jobs.values() if j["state"] in ACTIVE)

    def _snapshot(self, job: dict) -> dict:
        view = {k: job[k] for k in ("key", "state", "stage", "error")}
        now = self.clock()
        if job["state"] == "queued":
            rank = self._rank(job, now)
            view["position"] = sum(
                1 for j in self._jobs.values()
                if j["state"] == "running"
                or (j["state"] == "queued" and self._rank(j, now) < rank))
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
        threading.Thread(target=self._reap_forever, name="lidar-reaper",
                         daemon=True).start()

    def _reap_forever(self) -> None:
        while True:
            time.sleep(REAP_INTERVAL_S)
            self.reap()

    def _run(self) -> None:
        while True:
            job = self.next_job()
            self.process(job["key"], *job["target"])

    def process(self, key: str, lat: float, lon: float, radius_m: float) -> None:
        """Run one build. Public so tests can drive it without the thread."""
        staging = self.root / f"{STAGING_PREFIX}{key}"
        final = self.root / key

        with self._lock:
            job = self._jobs.get(key)
            # Dropped while it waited, or a later request for the same place
            # already ran: either way this queue entry has nothing to do.
            if not job or job["state"] != "queued":
                return
            job.update(state="running", stage="starting", started_at=self.clock())
        _log(f"{key} started")

        cancel = job["cancel"]

        def stage(name: str) -> None:
            if cancel.is_set():
                raise BuildCancelled()
            job["stage"] = name
            _log(f"{key} {name}")

        try:
            shutil.rmtree(staging, ignore_errors=True)
            self.root.mkdir(parents=True, exist_ok=True)
            self.build(self.make_request(lat, lon, radius_m), staging, stage=stage,
                       runner=cancellable_runner(cancel))
            if cancel.is_set():
                raise BuildCancelled()
            # Complete on disk before it becomes visible under its real name.
            if final.exists():
                shutil.rmtree(final)
            os.replace(staging, final)
            with self._lock:
                job.update(state="done", stage="done", finished_at=self.clock())
            _log(f"{key} done in {round(job['finished_at'] - job['started_at'])}s")
        except BuildCancelled:
            with self._lock:
                job.update(state="cancelled", stage="cancelled", finished_at=self.clock())
            _log(f"{key} stopped after {round(job['finished_at'] - job['started_at'])}s")
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

        def _job_request(self):
            """(key, query) for /builds/<key>, or None."""
            parts = urlsplit(self.path)
            match = _JOB_PATH.match(parts.path)
            if not match:
                return None
            query = {name: values[0] for name, values in parse_qs(parts.query).items()}
            return match.group(1), query

        def do_GET(self):
            if self.path == "/health":
                return self._send(200, {"ok": True, "pending": builder.pending()})
            if not self._authorised():
                return self._send(401, {"error": "unauthorised"})
            found = self._job_request()
            if not found:
                return self._send(404, {"error": "not found"})
            key, query = found
            job = builder.status(key, watcher=query.get("watcher"),
                                 keep=query.get("keep") == "1")
            if job is None:
                return self._send(404, {"error": "no such build"})
            return self._send(200, job)

        def do_DELETE(self):
            if not self._authorised():
                return self._send(401, {"error": "unauthorised"})
            found = self._job_request()
            if not found:
                return self._send(404, {"error": "not found"})
            key, query = found
            job = builder.release(key, watcher=query.get("watcher"))
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
                return self._send(202, builder.submit(
                    lat, lon, radius, keep=body.get("keep") is True,
                    watcher=body.get("watcher")))
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
