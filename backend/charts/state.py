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

**What is served** is ``live``, as ``effective(state, now)`` says: once ``next.at`` has passed, ``next.build`` is live, the build that
was live is ``previous``, and ``next`` is empty. Nothing runs at a scheduled time; every reader asks ``effective`` (``serving``, over a
copy of the file kept in memory and read again only when its inode, ``mtime_ns`` or size changes), and **a reader never writes**.
Every write (``update``) first writes down a switch whose time has passed, at ``next.at`` and with its line in ``history``, then deletes
the builds retired more than a day ago that are not live, previous or next, then does what was asked. The admin's actions are
``switch``, ``schedule``, ``roll_back``, ``cancel_schedule`` and ``delete_build``; each refuses in words (``Refused``) what it may not do,
writing nothing of it. A build deleted is named in ``gone`` and never listed again, so a folder a deletion could not remove is never
registered back (§5.5).
"""
from __future__ import annotations

import copy
import json
import logging
import os
import re
import secrets
import shutil
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from . import catalog, locks

log = logging.getLogger(__name__)

STATE = "state.json"
LOCK = "state.lock"
BUILDS = "builds"
INCOMING = ".incoming"
TRASH = ".trash"
HISTORY_KEPT = 50
GONE_KEPT = 500
RETIRED_KEPT = timedelta(days=1)    # a build that stopped being live or previous is deleted this long after (§5.5)
AUTOMATIC = "Automatic"             # who the automatic cycle is, in history and on a job (§5.7)

BUILD_NAME = re.compile(r"^(\d{4}-\d{2}-\d{2})\.([1-9][0-9]{0,5})$")


class StateError(RuntimeError):
    """``state.json`` cannot be read: said in words, and nothing is written over it."""


class Refused(Exception):
    """An action that may not be done now, said in words. Nothing it asked is written (what a passed switch or a purge wrote is)."""


def root(environ=os.environ) -> Path | None:
    """The chart volume, from ``CHART_TILES_DIR``; None when unset (no charts on this server)."""
    value = (environ.get("CHART_TILES_DIR") or "").strip()
    if not value or value.lower() in ("none", "off"):
        return None
    return Path(value)


def empty() -> dict:
    return {"version": 1, "live": None, "previous": None, "next": None, "numbers": {}, "builds": {}, "history": [], "auto": {},
            "gone": [], "looks": {}}


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso(moment: datetime) -> str:
    """An instant as ``state.json`` writes it: UTC, to the second, with a ``Z``."""
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def instant(text) -> datetime | None:
    """An instant ``state.json`` or ``coverage.json`` wrote (``2026-12-24T09:01:00Z``, or to the minute), or None."""
    if not isinstance(text, str) or not text:
        return None
    try:
        moment = datetime.fromisoformat(text[:-1] + "+00:00" if text.endswith("Z") else text)
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


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
    for key in ("numbers", "builds", "auto", "looks"):
        if not isinstance(state.get(key), dict):
            state[key] = {}
    for key in ("history", "gone"):
        if not isinstance(state.get(key), list):
            state[key] = []
    return state


_cache_lock = threading.Lock()
_cache: dict[str, tuple] = {}


def current(where: Path) -> dict:
    """The state as last written, kept in memory and read again only when the file changed (its inode, ``mtime_ns`` or size): what
    every reader of what is served starts from (§5.5). Shared: never change what it gives."""
    try:
        st = os.stat(where / STATE)
        key = (st.st_ino, st.st_mtime_ns, st.st_size)
    except FileNotFoundError:
        key = None
    with _cache_lock:
        hit = _cache.get(str(where))
    if hit is not None and hit[0] == key:
        return hit[1]
    found = read(where) if key is not None else empty()
    with _cache_lock:
        _cache[str(where)] = (key, found)
    return found


def serving(where: Path, now: datetime | None = None) -> dict:
    """What is served at ``now``: the state as last written, with a scheduled switch whose time has passed applied. Writes nothing."""
    return effective(current(where), now or now_utc())


def effective(state: dict, now: datetime) -> dict:
    """The state as it stands at ``now`` (§5.5): once ``next.at`` has passed, ``next.build`` is live, the build that was live is
    ``previous``, and ``next`` is empty. Pure: the state given is not changed, and one with nothing to apply is given back as it is."""
    nxt = state.get("next")
    if nxt is None:
        return state
    at = instant(nxt.get("at")) if isinstance(nxt, dict) else None
    if at is None:
        return {**state, "next": None}
    if now < at:
        return state
    build = nxt.get("build")
    if build not in state["builds"] or build == state.get("live"):
        return {**state, "next": None}
    return {**state, "live": build, "previous": state.get("live"), "next": None}


def roles(state: dict) -> dict:
    """Which build is live, previous and scheduled next, by name (None where none is)."""
    nxt = state.get("next")
    return {"live": state.get("live"), "previous": state.get("previous"),
            "next": nxt.get("build") if isinstance(nxt, dict) else None}


def cycle_of(info: dict) -> date | None:
    try:
        return date.fromisoformat(str(info.get("cycle")))
    except (TypeError, ValueError, AttributeError):
        return None


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


def update(where: Path, change, now: datetime | None = None):
    """Reads, changes and writes the state under its lock; ``change(state)`` changes it in place and its answer is returned.

    Every write first writes down a switch whose time has passed and deletes the builds retired more than a day ago (§5.5), so the
    file never says one thing while ``effective`` says another for longer than the next write. A change that raises ``Refused``
    writes nothing of its own, but what was settled is written all the same. The folders of the builds deleted go after the write."""
    now = now or now_utc()
    doomed, written = [], False
    try:
        with locks.locked(where / LOCK):
            state = read(where)
            settled = _settle(state, now)
            doomed = _purge(state, now)
            kept = copy.deepcopy(state)
            try:
                answer = change(state)
            except Refused:
                if settled or doomed:
                    _write(where, kept)
                    written = True
                raise
            _write(where, state)
            written = True
    finally:
        if written:
            remove_folders(where, doomed)
    return answer


def _settle(state: dict, now: datetime) -> bool:
    """Writes down a scheduled switch whose time has passed, at its time (§5.5). True if anything changed."""
    nxt = state.get("next")
    if nxt is None:
        return False
    at = instant(nxt.get("at")) if isinstance(nxt, dict) else None
    if at is None:
        state["next"] = None
        return True
    if now < at:
        return False
    state["next"] = None
    build = nxt.get("build")
    if build in state["builds"] and build != state.get("live"):
        before = state.get("live")
        _make_live(state, build, at)
        _history(state, {"did": "switch", "how": "scheduled", "from": before, "to": build, "by": nxt.get("by") or "",
                         "at": iso(at)})
    return True


def _purge(state: dict, now: datetime) -> list[str]:
    """Forgets every build retired more than a day ago that is not live, previous or next, and gives their names: their folders
    are deleted once the file is written (§5.5)."""
    held = set(roles(state).values())
    doomed = []
    for name, info in state["builds"].items():
        if name in held or not isinstance(info, dict):
            continue
        retired = instant(info.get("retired"))
        if retired is not None and now - retired > RETIRED_KEPT:
            doomed.append(name)
    for name in doomed:
        _forget(state, name)
        _history(state, {"did": "delete", "build": name, "why": "retired", "by": AUTOMATIC, "at": iso(now)})
    return doomed


def _forget(state: dict, name: str) -> None:
    state["builds"].pop(name, None)
    if name not in state["gone"]:
        state["gone"].append(name)
        state["gone"] = state["gone"][-GONE_KEPT:]


def _history(state: dict, line: dict) -> None:
    state["history"].append(line)


def _unretire(state: dict, *names) -> None:
    for name in names:
        info = state["builds"].get(name)
        if isinstance(info, dict):
            info["retired"] = None


def _make_live(state: dict, build: str, at: datetime) -> None:
    """``previous`` takes the live build and ``live`` this one. The previous build pushed out is retired at ``at`` (a scheduled
    switch written down late retires it at its time), unless it is still something; the two kept are not retired."""
    before, pushed = state.get("live"), state.get("previous")
    state["live"], state["previous"] = build, before
    _unretire(state, build, before)
    if pushed and pushed not in roles(state).values():
        info = state["builds"].get(pushed)
        if isinstance(info, dict):
            info["retired"] = iso(at)


def _listed(state: dict, build: str) -> dict:
    info = state["builds"].get(build)
    if not isinstance(info, dict):
        raise Refused(f"{build} is not a build the chart folder holds.")
    return info


def _automatic(state: dict, build: str) -> dict | None:
    """The automatic cycle's record of the cycle ``build`` is of, when it made that build (§5.7)."""
    for record in state["auto"].values():
        if isinstance(record, dict) and record.get("started") == build:
            return record
    return None


def take_number(where: Path, cycle: date, *, then=None, now: datetime | None = None) -> str:
    """The next build's name for ``cycle``, ``2026-10-29.2``: one more than the last given that cycle, never given again.
    ``then(state, name)``, if given, is applied in the same write (who the build is for, §5.7)."""
    key = cycle.isoformat()

    def change(state):
        try:
            last = int(state["numbers"].get(key) or 0)
        except (TypeError, ValueError):
            raise StateError("The chart folder's state.json has a build number that is not a number.") from None
        state["numbers"][key] = last + 1
        name = f"{key}.{last + 1}"
        if then is not None:
            then(state, name)
        return name

    return update(where, change, now)


def register(where: Path, build: str, info: dict, *, then=None, now: datetime | None = None) -> bool:
    """Lists a finished build. True if it was listed now, False if it was already, or was deleted (registering again changes
    nothing, and a deleted build is never listed again). ``then(state, build, now)``, if given, is applied in the same write (the
    automatic cycle's schedule or switch, §5.7)."""
    now = now or now_utc()

    def change(state):
        if build in state["builds"] or build in state["gone"]:
            return False
        state["builds"][build] = {**info, "retired": None}
        if then is not None:
            then(state, build, now)
        return True

    return update(where, change, now)


# ------------------------------------------------------------------------------------------------------------- actions (§5.5)


def switch(where: Path, build: str, by: str, now: datetime | None = None) -> None:
    """Switch to this build: ``previous`` takes the live one, this one is live, and ``next`` is cleared. Refused for a build whose
    cycle is not yet in effect: switched early, crews would plan on charts not yet valid."""
    now = now or now_utc()

    def change(state):
        info = _listed(state, build)
        if build == state.get("live"):
            raise Refused(f"{build} is live already.")
        cycle = cycle_of(info)
        if cycle is None:
            raise Refused(f"{build} names no cycle.")
        starts = catalog.effective_instant(cycle)
        if starts > now:
            raise Refused(f"{build} is for a cycle not in effect until {iso(starts)}: schedule its switch instead.")
        before, cleared = state.get("live"), roles(state)["next"]
        state["next"] = None
        _make_live(state, build, now)
        line = {"did": "switch", "from": before, "to": build, "by": by, "at": iso(now)}
        if cleared and cleared != build:
            line["cleared"] = cleared
        _history(state, line)

    update(where, change, now)


def schedule(where: Path, build: str, by: str, now: datetime | None = None) -> str:
    """Switch to this build at its cycle's effective time (0901Z on its date): sets ``next``. Only for a cycle not yet in effect.
    Gives the time."""
    now = now or now_utc()

    def change(state):
        info = _listed(state, build)
        cycle = cycle_of(info)
        if cycle is None:
            raise Refused(f"{build} names no cycle.")
        at = catalog.effective_instant(cycle)
        if at <= now:
            raise Refused(f"{build}'s cycle is in effect already: switch to it.")
        before = roles(state)["next"]
        state["next"] = {"build": build, "at": iso(at), "by": by}
        _unretire(state, build)
        line = {"did": "schedule", "to": build, "when": iso(at), "by": by, "at": iso(now)}
        if before and before != build:
            line["replaced"] = before
        _history(state, line)
        return iso(at)

    return update(where, change, now)


def roll_back(where: Path, by: str, now: datetime | None = None) -> None:
    """``live`` and ``previous`` change places, so a roll back can itself be undone. ``next`` is kept: a roll back is about the cycle
    in effect, and the next cycle's switch still happens at its time."""
    now = now or now_utc()

    def change(state):
        before, back = state.get("live"), state.get("previous")
        if not back or not isinstance(state["builds"].get(back), dict):
            raise Refused("There is no previous build to roll back to.")
        state["live"], state["previous"] = back, before
        _unretire(state, back, before)
        _history(state, {"did": "roll back", "from": before, "to": back, "by": by, "at": iso(now)})

    update(where, change, now)


def cancel_schedule(where: Path, by: str, now: datetime | None = None) -> None:
    """Clears ``next``. Refused once its time has passed: the switch has happened by then, and the way back is a roll back. An
    automatic build's schedule cancelled is the admin's choice, and the automatic cycle leaves that cycle to them (§5.7)."""
    now = now or now_utc()

    def change(state):
        nxt = state.get("next")
        if not isinstance(nxt, dict):
            raise Refused("No switch is scheduled. One whose time has come has happened: a roll back undoes it.")
        state["next"] = None
        record = _automatic(state, nxt.get("build"))
        if record is not None:
            record["dropped"] = {"what": "schedule", "by": by, "at": iso(now)}
        _history(state, {"did": "cancel schedule", "build": nxt.get("build"), "by": by, "at": iso(now)})

    update(where, change, now)


def delete_build(where: Path, build: str, by: str, now: datetime | None = None) -> None:
    """Takes a build out of ``state.json``, then deletes its folder. Never the live build, the previous one or a scheduled one. An
    automatic build deleted is the admin's choice: its cycle is not built again by itself (§5.7)."""
    now = now or now_utc()

    def change(state):
        _listed(state, build)
        held = roles(state)
        for role in ("live", "previous", "next"):
            if held[role] == build:
                word = {"live": "live", "previous": "the previous build (what a roll back goes to)",
                        "next": "scheduled to be switched to"}[role]
                raise Refused(f"{build} is {word}, so it is kept.")
        _forget(state, build)
        record = _automatic(state, build)
        if record is not None:
            record["dropped"] = {"what": "build", "by": by, "at": iso(now)}
        _history(state, {"did": "delete", "build": build, "by": by, "at": iso(now)})

    update(where, change, now)
    remove_folders(where, [build])


def remove_folders(where: Path, names) -> None:
    """Deletes builds' folders, each moved aside first (one rename), so a folder is never half there under its name. What cannot
    be deleted is logged and left in ``.trash/`` for the automatic cycle's next look (§5.7, step 2)."""
    for name in names:
        try:
            folder = build_dir(where, name)
        except ValueError:
            continue
        if not folder.exists():
            continue
        trash = where / TRASH
        try:
            trash.mkdir(exist_ok=True)
            aside = trash / f"{name}.{secrets.token_hex(4)}"
            os.replace(folder, aside)
        except OSError:
            log.warning("charts: a deleted build's folder could not be moved aside")
            continue
        shutil.rmtree(aside, ignore_errors=True)


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
