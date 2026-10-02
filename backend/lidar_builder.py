"""Talking to the point-cloud build service (backend/lidar/worker.py).

The API never runs the LiDAR toolchain itself. PDAL, py3dtiles and the geoid
grids live in their own image, and that image runs as a small service beside
this one, writing into the shared tileset store. This module forwards build
requests to it and reads back progress.

Unconfigured is a normal state, not an error: a deployment without the service
(Fly, or local development without Docker) simply cannot build on demand, and
the 3D window falls back to telling an operator what to run.

    LIDAR_BUILDER_URL     e.g. http://lidar-builder:8090 — unset disables builds
    LIDAR_BUILDER_TOKEN   shared secret; must match the service's
"""

from __future__ import annotations

import os

import requests

TOKEN_HEADER = "X-Builder-Token"
# Who is waiting on a build, and whether to keep it, travel as headers rather
# than in the URL. The API and the service deploy separately, so for a moment
# one is always newer than the other: a service that predates these headers
# ignores them and still finds the build, where a query string it did not
# expect made it answer 404 and the 3D window give up.
WATCHER_HEADER = "X-Build-Watcher"
KEEP_HEADER = "X-Build-Keep"

# The service answers from memory; anything slower than this is a service that
# is down or wedged, and the browser should hear so rather than wait.
TIMEOUT_S = 5


class BuilderUnavailable(RuntimeError):
    """The build service could not be reached or refused the request."""


class BuilderBusy(BuilderUnavailable):
    """The build queue is full."""


def configured() -> bool:
    return bool(os.environ.get("LIDAR_BUILDER_URL", "").strip())


def _url(path: str) -> str:
    return os.environ["LIDAR_BUILDER_URL"].strip().rstrip("/") + path


def _headers() -> dict:
    token = os.environ.get("LIDAR_BUILDER_TOKEN", "").strip()
    return {TOKEN_HEADER: token} if token else {}


def submit(lat: float, lon: float, radius_m: float, *, keep: bool = False,
           watcher: str | None = None) -> dict:
    """Ask for a build. Returns the job, which may already be running or done.

    ``watcher`` identifies who is waiting; the service drops a build once
    nobody is, unless ``keep`` asks it to finish regardless.
    """
    try:
        response = requests.post(_url("/builds"),
                                 json={"lat": lat, "lon": lon, "radius_m": radius_m,
                                       "keep": bool(keep), "watcher": watcher},
                                 headers=_headers(), timeout=TIMEOUT_S)
    except requests.RequestException as error:
        raise BuilderUnavailable("The build service is not reachable.") from error
    if response.status_code == 429:
        raise BuilderBusy("The build queue is full. Try again in a few minutes.")
    if response.status_code not in (200, 202):
        raise BuilderUnavailable(f"The build service refused the request "
                                 f"({response.status_code}).")
    return response.json()


def _watching(watcher: str | None, keep: bool = False) -> dict:
    headers = _headers()
    if watcher:
        headers[WATCHER_HEADER] = watcher
    if keep:
        headers[KEEP_HEADER] = "1"
    return headers


def status(key: str, *, watcher: str | None = None, keep: bool = False) -> dict | None:
    """A build's progress, or None if the service has no record of it.

    Asking counts as still waiting for it.
    """
    try:
        response = requests.get(_url(f"/builds/{key}"),
                                headers=_watching(watcher, keep), timeout=TIMEOUT_S)
    except requests.RequestException as error:
        raise BuilderUnavailable("The build service is not reachable.") from error
    if response.status_code == 404:
        return None
    if response.status_code != 200:
        raise BuilderUnavailable(f"The build service refused the request "
                                 f"({response.status_code}).")
    return response.json()


def release(key: str, *, watcher: str | None = None) -> dict | None:
    """This watcher has stopped waiting. None if the service never had the build."""
    try:
        response = requests.delete(_url(f"/builds/{key}"),
                                   headers=_watching(watcher), timeout=TIMEOUT_S)
    except requests.RequestException as error:
        raise BuilderUnavailable("The build service is not reachable.") from error
    if response.status_code == 404:
        return None
    if response.status_code != 200:
        raise BuilderUnavailable(f"The build service refused the request "
                                 f"({response.status_code}).")
    return response.json()
