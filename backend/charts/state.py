"""``state.json``: what the chart volume holds and serves (docs/VFR_CHARTS.md §5.5).

A file on the chart volume, not a database row: staging mounts the same volume with a database of its own, and both must read the
same builds. Only a server with ``DATA_ADMIN=write`` writes it.

- **Writers** take an exclusive ``flock`` on ``state.lock``, read the file, change it, write a temporary file and rename it over the
  old one, then let go (``update``). Two writers at once both land.
- **Readers** take no lock: the rename makes each read see one whole version (``read``).
- **Build numbers** come from ``numbers``, the last number given each cycle, taken under the lock when a build job is created
  (``take_number``). Never from the folders on disk: a build deleted and made again would get the deleted one's number, and its URL
  would answer with the deleted build's tiles from every cache that holds them (§4.5).
- **A finished build is registered** by the API, never by the build process (``register``), and registering is idempotent: a parent
  killed between its child's ``done`` and the write leaves a folder the next look registers (§5.3, step 8).

C1 writes ``numbers`` and ``builds``. What is live, switching, rolling back, the schedule and deleting are C2's (§5.5).
"""
from __future__ import annotations

import json
import os
import re
import secrets
from datetime import date
from pathlib import Path

from . import locks

STATE = "state.json"
LOCK = "state.lock"
BUILDS = "builds"
INCOMING = ".incoming"
HISTORY_KEPT = 50

BUILD_NAME = re.compile(r"^(\d{4}-\d{2}-\d{2})\.([1-9][0-9]{0,5})$")


class StateError(RuntimeError):
    """``state.json`` cannot be read: said in words, and nothing is written over it."""


def root(environ=os.environ) -> Path | None:
    """The chart volume, from ``CHART_TILES_DIR``; None when unset (no charts on this server)."""
    value = (environ.get("CHART_TILES_DIR") or "").strip()
    if not value or value.lower() in ("none", "off"):
        return None
    return Path(value)


def empty() -> dict:
    return {"version": 1, "live": None, "previous": None, "next": None, "numbers": {}, "builds": {}, "history": [], "auto": {}}


def read(where: Path) -> dict:
    """The state as last written, or an empty one where none has been. Takes no lock."""
    try:
        with open(where / STATE, encoding="utf-8") as f:
            found = json.load(f)
    except FileNotFoundError:
        return empty()
    except (OSError, ValueError) as error:
        raise StateError(f"The chart folder's state.json cannot be read ({error.__class__.__name__}).") from None
    if not isinstance(found, dict):
        raise StateError("The chart folder's state.json is not what it should be.")
    state = empty()
    state.update(found)
    for key in ("numbers", "builds", "auto"):
        if not isinstance(state.get(key), dict):
            state[key] = {}
    if not isinstance(state.get("history"), list):
        state["history"] = []
    return state


def _write(where: Path, state: dict) -> None:
    state["history"] = state["history"][-HISTORY_KEPT:]
    part = where / f".{STATE}.{secrets.token_hex(4)}"
    try:
        with open(part, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=1, sort_keys=True)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(part, where / STATE)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    try:   # the rename itself, so a crash after it cannot bring the old file back
        fd = os.open(where, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError:
        pass


def update(where: Path, change):
    """Reads, changes and writes the state under its lock; ``change(state)`` changes it in place and its answer is returned."""
    with locks.locked(where / LOCK):
        state = read(where)
        answer = change(state)
        _write(where, state)
        return answer


def take_number(where: Path, cycle: date) -> str:
    """The next build's name for ``cycle``, ``2026-10-29.2``: one more than the last given that cycle, never given again."""
    key = cycle.isoformat()

    def change(state):
        try:
            last = int(state["numbers"].get(key) or 0)
        except (TypeError, ValueError):
            raise StateError("The chart folder's state.json has a build number that is not a number.") from None
        state["numbers"][key] = last + 1
        return f"{key}.{last + 1}"

    return update(where, change)


def register(where: Path, build: str, info: dict) -> bool:
    """Lists a finished build. True if it was listed now, False if it was already (registering again changes nothing)."""
    def change(state):
        if build in state["builds"]:
            return False
        state["builds"][build] = {**info, "retired": None}
        return True

    return update(where, change)


def check_name(build: str) -> str:
    """A build's name as it may be used in a path: refuses anything else."""
    if not isinstance(build, str) or not BUILD_NAME.match(build):
        raise ValueError("not a build's name")
    return build


def build_dir(where: Path, build: str) -> Path:
    return where / BUILDS / check_name(build)


def staging_dir(where: Path, build: str) -> Path:
    return where / f".staging-{check_name(build)}"


def incoming_dir(where: Path, build: str) -> Path:
    """Where a build's zips come down and are unzipped: one folder for each build, so a zip of one cycle is never taken for
    another's (the FAA names them the same every cycle)."""
    return where / INCOMING / check_name(build)


def holding(where: Path, chart_id: str, state: dict | None = None):
    """The newest copy of a chart that a listed build keeps: ``(build, folder)``, the folder under its ``sources/`` that holds the
    chart's zip and the outline it was checked against, or None (§4.2, §5.3 step 3)."""
    state = read(where) if state is None else state
    for build in sorted(state["builds"], key=_order, reverse=True):
        sources = where / BUILDS / build / "sources"
        try:
            folders = sorted((p for p in sources.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True)
        except OSError:
            continue
        for folder in folders:
            if (folder / f"{chart_id}.geojson").is_file():
                return build, folder
    return None


def _order(build: str):
    m = BUILD_NAME.match(build)
    return (m.group(1), int(m.group(2))) if m else ("", 0)
