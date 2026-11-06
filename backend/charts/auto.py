"""The automatic cycle (docs/VFR_CHARTS.md §5.7): each cycle of the FAA's VFR charts built when the FAA posts it, and switched to at
its effective time, with no one pressing anything. The Data tab is where it is watched, and where an admin cancels, switches or rolls
back.

**Where it runs.** A *look* runs only where ``DATA_ADMIN=write`` and ``CHART_TILES_DIR`` is set: production. Staging (``read``) serves
what production built and never looks. Whether a look may also *build* is ``CHART_AUTO``: on by default only where
``security_config.is_production`` holds, ``off`` turns it off there too, ``on`` turns it on elsewhere (``mode``). Off, a look still
does its housekeeping and still sends the expiry emails.

**What starts it.** A daemon thread beside the terrain warm-up (``start``): a look 5 minutes after start, then every 6 hours, or 30
minutes after a look that found another job under way. One look at a time across processes, by a non-blocking ``flock`` on
``auto.lock``: a redeploy runs two containers for a moment, and the tab's one-job rule holds within one process only. Everything in
a look is caught and logged; nothing reaches a request or ends the thread.

**One look** (``look``): (1) writes down a switch whose time has passed and deletes builds retired a day ago (any write does);
(2) registers a finished build nobody registered, resumes every job left queued or running, and deletes leftovers; (3) works out the
current cycle and the next, and whether each is posted; (4) picks the first that wants a build; (5) starts it, taking its number and
its record in one write; (6) when a build it started is registered, schedules or switches to it in the same write (``registered``).

**What it leaves alone.** An admin's choice is never undone, and sticks for the rest of that cycle: a build an admin made of it
(``admin_built``), a build of its own deleted or its schedule cancelled (``dropped``), its build cancelled while it ran
(``cancelled``). A cycle not posted is waited for and never a try; nor is a job waiting, a build already running, or a restart. A
failed build is a try: three at most, six hours apart, then ``gave_up``. A refusal for room gives up at once.

**Being told.** Each event is emailed once (``told``, claimed under the state's lock before it is sent), to ``CHART_NOTIFY_EMAIL``,
else ``SUPER_ADMIN_EMAIL``: a cycle built and scheduled or switched to, a cycle given up, a cycle waiting more than 24 hours behind
another job, the live build seven days from its expiry with nothing scheduled, and the live build expired (judged on ``effective``,
so a switch due at that instant counts first).

Its record of each cycle is ``state.json``'s ``auto[<cycle>]``, and of its looks ``looks``.
"""
from __future__ import annotations

import html
import logging
import os
import shutil
import threading
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from . import catalog, faa, locks, state

log = logging.getLogger(__name__)

FIRST_LOOK_S = 300
LOOK_EVERY_S = 6 * 3600
BUSY_LOOK_S = 1800
TRY_GAP = timedelta(hours=6)
TRIES = 3
WAIT_TELL = timedelta(hours=24)
WARN_BEFORE = timedelta(days=7)
LOCK = "auto.lock"
AUTOMATIC = state.AUTOMATIC

_ON = ("on", "1", "true", "yes")
_OFF = ("off", "0", "false", "no")


def mode(environ=os.environ) -> dict:
    """Whether this server looks, whether a look may build, and why, in words for the tab (§5.7)."""
    import data_admin
    from security_config import is_production

    if not data_admin.can_write(environ):
        return {"looks": False, "builds": False,
                "why": "Off here: writes are off (DATA_ADMIN is not write), so this server serves what production builds."}
    if state.root(environ) is None:
        return {"looks": False, "builds": False, "why": "Off: CHART_TILES_DIR is not set."}
    if not locks.supported():
        return {"looks": False, "builds": False, "why": "Off: this server cannot lock a file across processes."}
    value = (environ.get("CHART_AUTO") or "").strip().lower()
    if value in _OFF:
        return {"looks": True, "builds": False,
                "why": "Off (CHART_AUTO=off): it keeps the chart folder tidy and sends the expiry emails, and builds nothing."}
    if value in _ON:
        return {"looks": True, "builds": True, "why": "On (CHART_AUTO=on)."}
    if is_production(environ):
        return {"looks": True, "builds": True, "why": "On, as on every production server."}
    return {"looks": True, "builds": False,
            "why": "Off outside production (CHART_AUTO=on turns it on): it keeps the chart folder tidy and builds nothing."}


# ------------------------------------------------------------------------------------------------------------- the thread


_thread_lock = threading.Lock()
_thread: threading.Thread | None = None
_wake = threading.Event()


def start(app) -> None:
    """Starts the looks, once per process, where this server looks at all."""
    global _thread
    if not mode()["looks"]:
        return
    with _thread_lock:
        if _thread is not None:
            return
        _thread = threading.Thread(target=_loop, args=(app,), name="chart-auto", daemon=True)
        _thread.start()


def soon() -> None:
    """Looks within the minute rather than at the next look's time (an admin's *Try again*)."""
    _wake.set()


def _loop(app, first: float = FIRST_LOOK_S) -> None:
    wait = first
    while True:
        if _wake.wait(wait):
            _wake.clear()
            _wake.wait(60)      # a minute, so what the admin did has landed
            _wake.clear()
        try:
            wait = look(app)
        except Exception:  # noqa: BLE001 — a look never ends the thread
            log.exception("charts: the automatic cycle's look failed")
            wait = LOOK_EVERY_S


def look(app, *, now: datetime | None = None, http=None) -> float:
    """One look (§5.7): what it does is in the module's docstring. Gives the seconds to the next. Never raises."""
    try:
        with app.app_context():
            m = mode()
            if not m["looks"]:
                return LOOK_EVERY_S
            where = state.root()
            fd = locks.try_lock(where / LOCK)
            if fd is None:
                return LOOK_EVERY_S        # another process looks now
            try:
                return _look(app, where, m, now or state.now_utc(), http)
            finally:
                locks.release(fd)
    except Exception:  # noqa: BLE001 — logged, and the next look comes as ever
        log.exception("charts: the automatic cycle's look failed")
        try:
            from models import db

            with app.app_context():
                db.session.rollback()
        except Exception:  # noqa: BLE001
            pass
        return LOOK_EVERY_S


def _look(app, where: Path, m: dict, now: datetime, http) -> float:
    from data_admin import jobs

    error = None
    busy = False
    state.update(where, lambda s: None, now)                    # 1: a passed switch written down, retired builds deleted
    try:
        jobs.RUNNER.resume(app)                                 # 2: registers what was finished, resumes what was left
        _leftovers(where)
        if m["builds"]:
            busy = _build(app, where, now, http)                # 3 to 5 (6 is ``registered``)
    except Exception:  # noqa: BLE001 — the emails and the record of the look still go
        log.exception("charts: the automatic cycle's look failed part way")
        error = "The last look failed part way; the server's log has it."
    tell(where, now, m)
    wait = BUSY_LOOK_S if busy else LOOK_EVERY_S

    def note(s):
        s["looks"] = {"last": state.iso(now), "next": state.iso(now + timedelta(seconds=wait)), "busy": busy, "error": error}

    state.update(where, note, now)
    return wait


# ------------------------------------------------------------------------------------------------------------- step 2


def _leftovers(where: Path) -> None:
    """Deletes what neither ``state.json`` nor a job names: ``builds/*`` and ``.staging-*`` folders, ``.incoming/`` when no chart
    build runs, and ``.trash/`` (§5.7, step 2). A staging folder whose lock is held is never touched."""
    from models import DataJob

    from . import job as chart_job

    rows = DataJob.query.filter(DataJob.kind == chart_job.KIND).all()
    active = {r.build for r in rows if r.state in ("queued", "running") and r.build}
    held = state.read(where)
    named = set(held["builds"]) | active
    # A build done and not yet registered (its parent registers it in a moment) is named too, unless it was deleted.
    named |= {r.build for r in rows if r.state == "done" and r.build and r.build not in held["gone"]}
    builds = where / state.BUILDS
    if builds.is_dir():
        for folder in builds.iterdir():
            if folder.name in named or not state.BUILD_NAME.match(folder.name):
                continue
            log.info("charts: deleting a build folder nothing names")
            state.remove_folders(where, [folder.name])
    for staging in where.glob(".staging-*"):
        name = staging.name[len(".staging-"):]
        if name in active or not state.BUILD_NAME.match(name):
            continue
        chart_job.clean(where, name)          # deletes nothing while its lock is held
    incoming = where / state.INCOMING
    if incoming.is_dir() and not active:
        shutil.rmtree(incoming, ignore_errors=True)
    trash = where / state.TRASH
    if trash.is_dir():
        shutil.rmtree(trash, ignore_errors=True)


# ------------------------------------------------------------------------------------------------------------- steps 3 to 5


def _build(app, where: Path, now: datetime, http) -> bool:
    """Steps 3 to 5: builds the first cycle that wants one. True when it found another job under way (look again in 30 minutes)."""
    from data_admin import jobs, volumes
    from models import DataJob

    from . import job as chart_job

    rows = DataJob.query.filter(DataJob.kind == chart_job.KIND).all()
    busy = DataJob.query.filter(DataJob.state.in_(("queued", "running"))).first() is not None
    held = state.update(where, lambda s: _note_jobs(s, rows, now, where, busy=busy) or _copy(s), now)
    for cycle in (faa.current_cycle(now), faa.next_cycle(now)):
        why = _not_wanted(held, rows, cycle, now, where)
        if why:
            continue
        posted = faa.posted(cycle, http)
        if not posted:
            continue        # not posted, or the FAA could not be asked: waited for, never a try
        key = cycle.isoformat()
        under_way = DataJob.query.filter(DataJob.state.in_(("queued", "running"))).first()
        if under_way is not None:
            _wait(where, key, under_way, now)
            return True
        try:
            volumes.check_room(where, chart_job.CONUS_ESTIMATE_BYTES, label=chart_job.LABEL)
        except volumes.NoRoom as error:
            _give_up(where, key, str(error), now, room=True)
            return False
        job_id = jobs.new_id()

        def then(s, name, job_id):
            record = _record(s, key)
            record.update(started=name, job_id=job_id, last_try=state.iso(now), tries=int(record.get("tries") or 0) + 1,
                          error=None, waiting_since=None, waiting_job=None)

        try:
            chart_job.start(app, cycle, by_id=None, by_name=AUTOMATIC, then=then, job_id=job_id, now=now)
        except jobs.Busy:
            under_way = DataJob.query.filter(DataJob.state.in_(("queued", "running"))).first()
            if under_way is not None:
                _wait(where, key, under_way, now)
            return True
        log.info("charts: the automatic cycle started a build of %s", key)
        return False
    return False


def _copy(s: dict) -> dict:
    import copy

    return copy.deepcopy(s)


def _record(s: dict, key: str) -> dict:
    record = s["auto"].get(key)
    if not isinstance(record, dict):
        record = s["auto"][key] = {}
    return record


def _not_wanted(s: dict, rows, cycle: date, now: datetime, where: Path) -> str | None:
    """Why a cycle wants no build now, or None when it wants one (§5.7, step 4). Whether it is posted is asked after. A build done
    and not yet listed holds its cycle back only while it can still be listed (its parent lists it in a moment, or step 2 does):
    one whose folder has gone would hold it back for ever, and is a failed try (``_note_jobs``)."""
    key = cycle.isoformat()
    record = s["auto"].get(key) if isinstance(s["auto"].get(key), dict) else {}
    for mark, why in (("admin_built", "left to an admin's build"), ("dropped", "left to an admin"),
                      ("cancelled", "its build was cancelled by an admin"), ("gave_up", "given up"),
                      ("built", "built")):
        if record.get(mark):
            return why
    if any(isinstance(info, dict) and info.get("cycle") == key for info in s["builds"].values()):
        return "a build of it exists"
    for r in rows:
        if not (r.build or "").startswith(key + "."):
            continue
        if r.state in ("queued", "running"):
            return "a build of it is under way"
        if (r.state == "done" and r.build not in s["builds"] and r.build not in s["gone"]
                and (state.build_dir(where, r.build) / "coverage.json").is_file()):
            return "a build of it is finished and waits to be listed"
    if int(record.get("tries") or 0) >= TRIES:
        return "tried three times"
    last = state.instant(record.get("last_try"))
    if last is not None and now - last < TRY_GAP:
        return "tried less than six hours ago"
    return None


def _note_jobs(s: dict, rows, now: datetime, where: Path, *, busy: bool) -> None:
    """What became of the builds it started (a failure, an admin's cancel, a start that never made its job, a build finished
    whose folder went before it was listed), and the admin's builds of a cycle listed before anything recorded them (C1's).
    ``rows`` are the chart jobs, ``busy`` whether any job of any kind (a DEM download too) is queued or running. Applied under
    the state's lock."""
    by_build = {r.build: r for r in rows if r.build}
    if not busy:
        for record in s["auto"].values():       # nothing to wait behind now: a wait that ended is not emailed later
            if isinstance(record, dict) and record.get("waiting_since"):
                record["waiting_since"] = record["waiting_job"] = None
    for key, record in list(s["auto"].items()):
        if not isinstance(record, dict):
            continue
        started = record.get("started")
        if not started or record.get("seen") == started or record.get("built") or record.get("cancelled"):
            continue
        if started in s["builds"]:
            continue
        row = by_build.get(started)
        if row is None:
            if record.get("job_id"):
                _failed(record, "The build could not be started; the server's log may say why.", now, room=False)
            continue
        if row.state in ("queued", "running"):
            continue
        if row.state == "done":
            if started in s["gone"] or (state.build_dir(where, started) / "coverage.json").is_file():
                continue                # listed in a moment, by its parent or step 2
            _failed(record, "The build finished, but its folder went before it was listed.", now, room=False)
            record["seen"] = started
            continue
        if row.state == "cancelled":
            record["cancelled"] = record.get("cancelled") or {
                "by": "", "at": state.iso(row.finished_at.replace(tzinfo=timezone.utc)) if row.finished_at else state.iso(now)}
            record["seen"] = started
            continue
        _failed(record, row.error or "The build failed.", now, room=row.stage == _room_stage())
        record["seen"] = started
    for name, info in s["builds"].items():
        if not isinstance(info, dict) or info.get("by") == AUTOMATIC:
            continue
        key = info.get("cycle")
        if not isinstance(key, str):
            continue
        record = _record(s, key)
        if record.get("started") != name and not record.get("admin_built"):
            record["admin_built"] = {"build": name, "by": info.get("by") or "", "at": info.get("made")}


def _room_stage() -> str:
    from . import job as chart_job

    return chart_job.ROOM_STAGE


def _failed(record: dict, why: str, now: datetime, *, room: bool) -> None:
    record["error"] = why[:300]
    if room:
        record["gave_up"] = {"at": state.iso(now), "why": why[:300], "room": True}
    elif int(record.get("tries") or 0) >= TRIES:
        record["gave_up"] = {"at": state.iso(now), "why": why[:300]}


def _give_up(where: Path, key: str, why: str, now: datetime, *, room: bool) -> None:
    def change(s):
        record = _record(s, key)
        record["error"] = why[:300]
        record["gave_up"] = {"at": state.iso(now), "why": why[:300], **({"room": True} if room else {})}

    state.update(where, change, now)


def _wait(where: Path, key: str, row, now: datetime) -> None:
    def change(s):
        record = _record(s, key)
        record["waiting_since"] = record.get("waiting_since") or state.iso(now)
        record["waiting_job"] = {"id": row.id, "kind": row.kind, "build": row.build, "product": row.product,
                                 "by": row.requested_by_name or ""}

    state.update(where, change, now)


# ------------------------------------------------------------------------------------------------------------- what admins do


def admin_started(s: dict, name: str, by: str, now: datetime | None = None) -> None:
    """A build an admin started (*Build now*): the automatic cycle leaves its cycle to them, switch included (§5.7, step 4).
    Applied in the write that takes its number."""
    key = name.split(".")[0]
    record = _record(s, key)
    record["admin_built"] = {"build": name, "by": by, "at": state.iso(now or state.now_utc())}


def cancelled(where: Path, build: str, by: str, now: datetime | None = None) -> None:
    """An admin cancelled a build the automatic cycle started: it is not started again, and no "given up" email goes (§5.7)."""
    now = now or state.now_utc()

    def change(s):
        record = state._automatic(s, build)
        if record is not None and not record.get("cancelled"):
            record["cancelled"] = {"by": by, "at": state.iso(now)}

    state.update(where, change, now)


def try_again(where: Path, cycle: str, by: str, now: datetime | None = None) -> None:
    """*Try again* on a cycle given up: clears its tries, so the next look builds it (§5.6)."""
    now = now or state.now_utc()

    def change(s):
        record = s["auto"].get(cycle)
        if not isinstance(record, dict) or not record.get("gave_up"):
            raise state.Refused("That cycle has not been given up.")
        for key in ("gave_up", "tries", "last_try", "error", "seen", "started", "job_id"):
            record.pop(key, None)
        record["told"] = [t for t in record.get("told") or [] if t != "gave_up"]
        state._history(s, {"did": "try again", "cycle": cycle, "by": by, "at": state.iso(now)})

    state.update(where, change, now)
    soon()


# ------------------------------------------------------------------------------------------------------------- step 6


def registered(s: dict, build: str, now: datetime) -> None:
    """When a build the automatic cycle started is registered (by its parent, or by a look), in the same write (§5.7, step 6):
    schedules it for its cycle's effective time if nothing is scheduled; or switches to it now, if its cycle is in effect and the
    live build is of an older cycle, or there is none; or neither, if an admin has set ``next`` or made a build of the cycle."""
    info = s["builds"].get(build)
    cycle = state.cycle_of(info) if isinstance(info, dict) else None
    if cycle is None:
        return
    key = cycle.isoformat()
    record = s["auto"].get(key)
    if not isinstance(record, dict) or record.get("started") != build:
        return
    record["built"] = {"build": build, "at": state.iso(now)}
    record["seen"] = build
    if record.get("admin_built") or record.get("dropped") or record.get("cancelled"):
        record["left"] = "an admin's choice"
        return
    if any(name != build and isinstance(other, dict) and other.get("cycle") == key and other.get("by") != AUTOMATIC
           for name, other in s["builds"].items()):
        record["left"] = "an admin made a build of this cycle"
        return
    nxt = s.get("next")
    if isinstance(nxt, dict) and nxt.get("by") != AUTOMATIC:
        record["left"] = "an admin scheduled a switch"
        return
    at = catalog.effective_instant(cycle)
    if at > now:
        if nxt is not None:
            record["left"] = "another switch is scheduled"
            return
        s["next"] = {"build": build, "at": state.iso(at), "by": AUTOMATIC}
        state._unretire(s, build)
        record["scheduled"] = state.iso(at)
        state._history(s, {"did": "schedule", "to": build, "when": state.iso(at), "by": AUTOMATIC, "at": state.iso(now)})
        return
    live = s.get("live")
    live_cycle = state.cycle_of(s["builds"][live]) if isinstance(s["builds"].get(live), dict) else None
    if live is not None and live_cycle is not None and live_cycle >= cycle:
        record["left"] = "the live build is of this cycle or a later one"
        return
    if isinstance(nxt, dict):
        later = state.cycle_of(s["builds"].get(nxt.get("build")) or {})
        if later is None or later <= cycle:
            s["next"] = None
    state._make_live(s, build, now)
    record["switched"] = state.iso(now)
    state._history(s, {"did": "switch", "from": live, "to": build, "by": AUTOMATIC, "at": state.iso(now)})


# ------------------------------------------------------------------------------------------------------------- being told


def recipient(environ=os.environ) -> str | None:
    for name in ("CHART_NOTIFY_EMAIL", "SUPER_ADMIN_EMAIL"):
        value = (environ.get(name) or "").strip()
        if value and "@" in value:
            return value
    return None


def pending(s: dict, now: datetime, m: dict | None = None) -> list[tuple[str, str, str, str]]:
    """The emails owed, as ``(cycle, event, subject, text)``, judged on the state as it stands (``effective``: the caller settles
    it first). Marks nothing."""
    out = []
    for key, record in sorted(s["auto"].items()):
        if not isinstance(record, dict):
            continue
        told = record.get("told") or []
        built = record.get("built")
        if built and "built" not in told:
            out.append((key, "built", *_built_words(s, key, record)))
        if record.get("gave_up") and "gave_up" not in told and not record.get("cancelled"):
            why = record["gave_up"].get("why") or "no reason was recorded"
            out.append((key, "gave_up", f"VFR charts {key}: the automatic build gave up",
                        f"The automatic build of the {key} VFR charts gave up: {why}\n\n"
                        "Make room or fix what is wrong, then press Try again on the Data tab's Charts card, or build the cycle "
                        "from there."))
        since = state.instant(record.get("waiting_since"))
        if since is not None and now - since > WAIT_TELL and "waiting" not in told and not built:
            job = record.get("waiting_job") or {}
            what = f"the {job.get('build')} chart build" if job.get("build") else "a DEM download"
            out.append((key, "waiting", f"VFR charts {key}: waiting behind another job",
                        f"The automatic build of the {key} VFR charts has waited since {_when(since)} behind {what} "
                        f"(started by {job.get('by') or 'someone'}). It starts when that job ends."))
    live = s.get("live")
    info = s["builds"].get(live) if live else None
    if isinstance(info, dict):
        key = str(info.get("cycle"))
        told = (s["auto"].get(key) or {}).get("told") or [] if isinstance(s["auto"].get(key), dict) else []
        expires = state.instant(info.get("expires"))
        if expires is not None and now >= expires and f"expired:{live}" not in told:
            out.append((key, f"expired:{live}", f"VFR charts expired: {live} is past its date",
                        f"The live VFR chart build, {live}, expired at {_when(expires)} and nothing newer is live. The web and "
                        f"the apps draw it with a red Expired notice.\n\n{_why_nothing(s, now, m)}"))
        elif (expires is not None and expires - now <= WARN_BEFORE and s.get("next") is None
              and f"expiring:{live}" not in told and now < expires):
            out.append((key, f"expiring:{live}", f"VFR charts: {live} expires in {max(0, (expires - now).days)} days",
                        f"The live VFR chart build, {live}, expires at {_when(expires)}, and no switch to a newer one is "
                        f"scheduled.\n\n{_why_nothing(s, now, m)}"))
    return out


def _built_words(s: dict, key: str, record: dict) -> tuple[str, str]:
    build = record["built"].get("build")
    info = s["builds"].get(build) if isinstance(s["builds"].get(build), dict) else {}
    if record.get("switched"):
        how = f"It is live now (switched at {_when(state.instant(record['switched']))})."
    elif record.get("scheduled"):
        how = f"It is scheduled to go live at {_when(state.instant(record['scheduled']))}."
    else:
        how = f"It was not switched to: {record.get('left') or 'something else is live'}. The Data tab's Charts card can switch to it."
    held = [h for h in info.get("held_back") or [] if isinstance(h, dict)]
    lines = [f"The {key} VFR charts were built as {build}: {info.get('tiles') or 0:,} tiles. {how}"]
    if held:
        lines.append("Held back from this cycle, each kept from the last edition:")
        lines += [f"- {h.get('title') or h.get('id')}: {h.get('reason') or 'no reason given'}" for h in held]
    return f"VFR charts {key} built as {build}", "\n".join(lines)


def _why_nothing(s: dict, now: datetime, m: dict | None) -> str:
    if m is not None and not m.get("builds"):
        return f"The automatic cycle builds nothing on this server. {m.get('why') or ''}".strip()
    nxt = faa.next_cycle(now)
    key = nxt.isoformat()
    unswitched = [name for name, info in s["builds"].items() if isinstance(info, dict) and info.get("cycle") == key]
    if unswitched:
        return (f"A build of the next cycle exists ({', '.join(sorted(unswitched))}) and is not scheduled: an admin made it and "
                "keeps the decision to switch. Switch to it from the Data tab's Charts card.")
    record = s["auto"].get(key) if isinstance(s["auto"].get(key), dict) else {}
    if record.get("gave_up"):
        return f"The automatic build of {key} gave up: {record['gave_up'].get('why') or ''}"
    if record.get("dropped") or record.get("cancelled"):
        return f"An admin cancelled the automatic build of {key}: it is left to them."
    return f"The FAA has not posted the {key} charts yet, or they are still being built."


def _when(moment: datetime | None) -> str:
    return moment.strftime("%d %b %Y %H%MZ") if moment else "an unknown time"


def tell(where: Path, now: datetime | None = None, m: dict | None = None) -> int:
    """Sends the emails owed, each once: each is claimed in ``state.json`` under its lock before it is sent, so two processes never
    both send one. Gives how many went. A send that fails is logged; it is not sent again."""
    now = now or state.now_utc()
    m = m if m is not None else mode()

    def claim(s):
        owed = pending(s, now, m)
        for key, event, _, _ in owed:
            record = _record(s, key)
            record["told"] = [*(record.get("told") or []), event][-20:]
        return owed

    owed = state.update(where, claim, now)
    if not owed:
        return 0
    to = recipient()
    if to is None:
        log.warning("charts: %d email(s) owed but neither CHART_NOTIFY_EMAIL nor SUPER_ADMIN_EMAIL is set", len(owed))
        return 0
    from email_service import send_email

    sent = 0
    for _, _, subject, text in owed:
        body = "".join(f"<p>{html.escape(p).replace(chr(10), '<br>')}</p>" for p in text.split("\n\n"))
        try:
            if send_email(to, subject, text, body):
                sent += 1
            else:
                log.warning("charts: an email could not be sent")
        except Exception:  # noqa: BLE001
            log.exception("charts: an email could not be sent")
    return sent


def tell_quietly(where: Path) -> None:
    """``tell`` after a registration, from the job's thread: a failure is logged, never raised."""
    try:
        tell(where)
    except Exception:  # noqa: BLE001
        log.exception("charts: the automatic cycle's emails failed")


# ------------------------------------------------------------------------------------------------------------- the tab


def describe(s: dict, cycle: date, posted, now: datetime, m: dict) -> dict:
    """What the automatic cycle did, or will do, for a cycle, in words for the Charts card, with its tone."""
    key = cycle.isoformat()
    record = s["auto"].get(key) if isinstance(s["auto"].get(key), dict) else {}
    tries = int(record.get("tries") or 0)
    tried = f" ({tries} {'try' if tries == 1 else 'tries'})" if tries else ""
    if record.get("gave_up"):
        return {"text": f"Given up{tried}: {record['gave_up'].get('why') or ''}", "tone": "bad", "try_again": True}
    if record.get("cancelled"):
        c = record["cancelled"]
        return {"text": f"Cancelled by {c.get('by') or 'an admin'} at {c.get('at')}: left to an admin.", "tone": "note"}
    if record.get("dropped"):
        d = record["dropped"]
        what = "its schedule cancelled" if d.get("what") == "schedule" else "its build deleted"
        return {"text": f"Left to an admin ({what} by {d.get('by') or 'an admin'}).", "tone": "note"}
    if record.get("admin_built"):
        a = record["admin_built"]
        return {"text": f"Left to an admin's build ({a.get('build')}, by {a.get('by') or 'an admin'}).", "tone": "note"}
    built = record.get("built")
    if built:
        if record.get("switched"):
            how = f"switched to at {record['switched']}"
        elif record.get("scheduled"):
            how = f"switches at {record['scheduled']}"
        else:
            how = f"not switched: {record.get('left') or ''}"
        return {"text": f"Built {built.get('at')} as {built.get('build')}, {how}.", "tone": "good"}
    if record.get("started") and record.get("seen") != record["started"]:
        return {"text": f"Building as {record['started']}{tried}.", "tone": "note"}
    if record.get("waiting_since"):
        job = record.get("waiting_job") or {}
        return {"text": f"Waiting since {record['waiting_since']} behind another job ({job.get('build') or job.get('kind')}).",
                "tone": "warn"}
    if record.get("error"):
        after = state.instant(record.get("last_try"))
        when = f" Tries again after {state.iso(after + TRY_GAP)}." if after is not None and m.get("builds") else ""
        return {"text": f"Failed{tried}: {record['error']}{when}", "tone": "warn"}
    if not m.get("builds"):
        return {"text": "Not built automatically here.", "tone": "note"}
    if posted is False:
        return {"text": "Waiting for the FAA to post it.", "tone": "note"}
    if any(isinstance(i, dict) and i.get("cycle") == key for i in s["builds"].values()):
        return {"text": "A build of it exists.", "tone": "note"}
    return {"text": "Built at the next look.", "tone": "note"}
