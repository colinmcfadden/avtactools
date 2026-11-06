"""The Data tab's background jobs, one at a time: DEMs downloaded from USGS (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10.4,
§10.5), and VFR chart builds (``kind`` ``chart_build``, docs/VFR_CHARTS.md §5), which run in a process of their own that the runner
starts and watches (``charts.job``).

A download is mostly waiting on the network, which a thread of its own does without taking one of gunicorn's request
threads (AGENTS.md §9: one worker, eight threads). Each file comes down into ``<DEM root>/.incoming`` as a ``.part`` (the
same file system, so the move into place is a rename; the catalog skips dot folders), resumes with ``Range`` after a
dropped connection or a restart, is checked (``validate``), tagged NAVD88 where it states no datum, and renamed into
``<root>/usgs/13/`` or ``<root>/usgs/1m/<project>/``. Once a job has put a DEM in place the catalog is scanned at once and
the 3D terrain's coarse tiles are warmed again, the old generation let go of after (``terrain_tiles.rewarm_after_change``).

Progress is the job's database row, so the page polls it and a restart finds what was left (``resume``). No place, file
name or URL is logged.
"""

from __future__ import annotations

import collections
import logging
from dataclasses import dataclass
import os
import secrets
import shutil
import threading
import time
from datetime import datetime
from pathlib import Path

from models import db, DataJob
from . import usgs, validate, volumes

log = logging.getLogger(__name__)

CHUNK = 1024 * 1024
ROOM_EVERY = 256 * 1024 * 1024   # bytes between looks at the free space while a file comes down
SAVE_EVERY_S = 2.0
TRIES = 4
TIMEOUT = (10, 60)
MAX_JOB_FILES = 100
MAX_JOB_BYTES = 20 * volumes.GB
TRASH_DAYS = 7
STALE_PART_S = 48 * 3600

INCOMING = ".incoming"
TRASH = ".trash"


class Cancelled(Exception):
    pass


@dataclass(frozen=True)
class Source:
    """Where a job may fetch from, and how it names itself: a file is fetched only from a URL ``allowed`` passes, and no redirect is
    followed, whatever a URL in the job's row says."""

    name: str                   # in the words a failure is said in: "USGS answered 503"
    allowed: object             # url -> bool
    user_agent: str
    wrong_size: str             # the words for a file whose length is not the one listed


USGS = Source("USGS", usgs.allowed_url, usgs.USER_AGENT, "USGS's file is not the size The National Map lists.")


class FileFailed(RuntimeError):
    """One file could not be had (USGS refused it, it was not the size listed, it kept failing): that file is marked failed
    with this reason and the job goes on to the next."""


def dem_root(environ=os.environ) -> Path | None:
    """Where new DEMs go: the first of ``TERRAIN_DATA_DIR``'s folders that exists."""
    for root in (environ.get("TERRAIN_DATA_DIR") or "").split(os.pathsep):
        if root.strip() and os.path.isdir(root.strip()):
            return Path(root.strip())
    return None


def new_id() -> str:
    return secrets.token_hex(16)


def plan(candidates, *, replace: bool) -> list:
    """The files a job will fetch, as its row keeps them: each new candidate, and with ``replace`` each that is the same
    tile as one the server holds. One that a larger DEM covers is never fetched (it would only overlap it), and one held
    file is never replaced for two candidates."""
    files, replacing = [], set()
    for candidate in candidates:
        held_path = candidate.get("held_path")
        if candidate.get("held"):
            if not (replace and candidate.get("same_tile")) or held_path in replacing:
                continue
            replacing.add(held_path)
        files.append({"name": candidate["name"], "url": candidate["url"], "size": int(candidate["size"]),
                      "bounds": candidate["bounds"], "folder": candidate["folder"], "held": candidate.get("held"),
                      "held_path": held_path if candidate.get("held") else None,
                      "state": "queued", "done": 0, "reasons": [], "notes": []})
    return files


def check_job(files, root) -> None:
    """Refuses, in words, a job too big to start or with no room for it."""
    if not files:
        raise volumes.NoRoom("Nothing to download: every file there is on the server already.")
    if len(files) > MAX_JOB_FILES:
        raise volumes.NoRoom(f"{len(files)} files is more than one job takes ({MAX_JOB_FILES}). Draw a smaller area.")
    total = sum(f["size"] for f in files)
    if total > MAX_JOB_BYTES:
        raise volumes.NoRoom(f"{total / volumes.GB:.1f} GB is more than one job takes "
                             f"({MAX_JOB_BYTES / volumes.GB:.0f} GB). Draw a smaller area.")
    if root is None:
        raise volumes.NoRoom("TERRAIN_DATA_DIR names no folder that exists here.")
    volumes.check_room(root, total, label="The DEM folder")


class Runner:
    """One thread running jobs one at a time, in the order asked (``survey/book.Printer``'s pattern). Nothing is lost
    when the server restarts: the next look at a job still marked as running asks again (``resume``)."""

    def __init__(self):
        self.inline = False     # tests: queued jobs wait for run_pending() in the test's own thread
        self.started = datetime.utcnow()
        self._queue = collections.deque()
        self._known = set()
        self._lock = threading.Lock()
        self._thread = None

    def enqueue(self, app, job_id):
        with self._lock:
            if job_id not in self._known:
                self._known.add(job_id)
                self._queue.append(job_id)
            if self.inline or not self._queue or (self._thread is not None and self._thread.is_alive()):
                return
            self._thread = threading.Thread(target=self._work, args=(app,), name="data-jobs", daemon=True)
            self._thread.start()

    def knows(self, job_id):
        with self._lock:
            return job_id in self._known

    def resume(self, app):
        """Asks again for every job a stopped process left queued or running; only where writes are on. A chart build left running
        is asked again only once its lock is free: held, it still runs somewhere (the other container of a redeploy), and is left
        as it is to be looked at again (docs/VFR_CHARTS.md §5.1). A chart build its process finished and nobody registered is
        registered (§5.3, step 8)."""
        import data_admin

        if not data_admin.can_write():
            return
        from charts import job as chart_job

        chart_job.recover(self)
        for job in DataJob.query.filter(DataJob.state.in_(("queued", "running"))).all():
            if self.knows(job.id):
                continue
            if job.kind == chart_job.KIND and not chart_job.resumable(job):
                continue
            self.enqueue(app, job.id)

    def _next(self):
        with self._lock:
            if not self._queue:
                self._thread = None
                return None
            return self._queue.popleft()

    def _one(self, app, job_id):
        try:
            with app.app_context():
                job = db.session.get(DataJob, job_id)
                kind = job.kind if job is not None else None
                if kind == "chart_build":
                    from charts import job as chart_job

                    chart_job.watch(job_id)
                else:
                    run(job_id)
        except Exception:  # noqa: BLE001 - run says its own failures; this is for one that escaped it
            log.exception("data admin: job failed")
        finally:
            with self._lock:
                self._known.discard(job_id)

    def _work(self, app):
        try:
            while True:
                job_id = self._next()
                if job_id is None:
                    return
                self._one(app, job_id)
        finally:
            with self._lock:
                if self._thread is threading.current_thread():
                    self._thread = None

    def run_pending(self, app):
        while True:
            job_id = self._next()
            if job_id is None:
                return
            self._one(app, job_id)


RUNNER = Runner()


class _Progress:
    """Writes a job's progress to its row now and then, and reads back whether it was cancelled."""

    def __init__(self, job):
        self.job = job
        # The files as the job works on them: saving writes copies (so the JSON column sees a change), and these stay the
        # ones the job changes.
        self.files = [dict(f) for f in job.files or []]
        self.saved = 0.0

    def save(self, *, force=False):
        now = time.monotonic()
        if not force and now - self.saved < SAVE_EVERY_S:
            return
        self.saved = now
        job = self.job
        job.files = [dict(f) for f in self.files]   # a new list, so the JSON column is written
        job.files_done = sum(1 for f in self.files if f["state"] in ("done", "refused", "held"))
        job.bytes_done = sum(f["done"] for f in self.files)
        db.session.commit()
        db.session.refresh(job, ["cancel_requested"])
        if job.cancel_requested:
            raise Cancelled()


def _wait_for_analysis():
    """Heavy work first (AGENTS.md §9): nothing is fetched while an analysis, viewshed or export runs."""
    try:
        import terrain_tiles
        terrain_tiles._no_analysis.wait()
    except Exception:  # noqa: BLE001
        pass


def _sweep(root: Path) -> None:
    """Lets go of what is past keeping: replaced DEMs after a week, and ``.part`` files no job has touched for 48 hours."""
    now = time.time()
    trash = root / TRASH
    if trash.is_dir():
        for child in trash.iterdir():
            try:
                if now - child.stat().st_mtime > TRASH_DAYS * 86400:
                    shutil.rmtree(child, ignore_errors=True) if child.is_dir() else child.unlink()
            except OSError:
                pass
    incoming = root / INCOMING
    if incoming.is_dir():
        for child in incoming.glob("*.part"):
            try:
                if now - child.stat().st_mtime > STALE_PART_S:
                    child.unlink()
            except OSError:
                pass


def _download(http, entry, part: Path, root: Path, progress, *, source: Source = USGS, label: str = "The DEM folder",
              wait=None, headroom: int | None = None):
    """Fetches one file into ``part``, resuming what is there; raises on a failure that trying again would not mend. ``progress``
    is anything with a ``save()`` (which raises to stop), ``wait`` is called between chunks (by default it waits out an analysis),
    and ``headroom`` is handed to the room checks (``volumes.check_room``)."""
    if not source.allowed(entry["url"]):
        raise FileFailed(f"Not one of {source.name}'s file hosts.")
    wait = _wait_for_analysis if wait is None else wait
    expected = entry["size"]
    last_room = 0
    for attempt in range(TRIES):
        have = part.stat().st_size if part.exists() else 0
        if have > expected:
            part.unlink()
            have = 0
        if have == expected:
            return
        headers = {"User-Agent": source.user_agent}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            with http.get(entry["url"], headers=headers, stream=True, timeout=TIMEOUT, allow_redirects=False) as response:
                status = response.status_code
                if status == 200 and have:
                    have = 0                         # the host would not resume: start again
                elif status == 429 or status >= 500:
                    raise ConnectionError(f"{source.name} answered {status}")     # busy: try again after a pause
                elif status not in (200, 206):
                    # A redirect is not followed: it could lead anywhere (usgs.HOSTS, charts.faa.HOST).
                    raise FileFailed(f"{source.name} answered {status} for this file.")
                length = response.headers.get("Content-Length")
                try:
                    length = None if length is None else int(length)
                except ValueError:
                    length = None
                if length is not None and length != expected - have:
                    raise FileFailed(source.wrong_size)
                with open(part, "ab" if have else "wb") as out:
                    for chunk in response.iter_content(CHUNK):
                        out.write(chunk)
                        have += len(chunk)
                        entry["done"] = have
                        if have > expected:
                            raise FileFailed(f"{source.name} sent more than the file's size.")
                        if have - last_room >= ROOM_EVERY:
                            last_room = have
                            volumes.check_room(root, expected - have, label=label, headroom=headroom)
                        progress.save()
                        wait()
            if have == expected:
                return
        except (requests_errors(), ConnectionError, TimeoutError):
            pass                     # a dropped connection or a busy host: resume from what came, after a pause
        time.sleep(min(30, 2 ** attempt))
    raise FileFailed("The download kept failing. Try again later.")


def requests_errors():
    import requests
    return requests.exceptions.RequestException


def _inside(path, root: Path) -> bool:
    try:
        Path(os.path.realpath(path)).relative_to(os.path.realpath(root))
        return True
    except ValueError:
        return False


def _put_in_place(entry, part: Path, root: Path, replace: bool, checked) -> Path:
    target = root.joinpath(*entry["folder"], entry["name"])
    target.parent.mkdir(parents=True, exist_ok=True)
    held = Path(entry["held_path"]) if entry.get("held_path") else None
    if target.exists() and not replace:
        raise FileFailed("A file of that name is there already.")
    if replace:
        trash = root / TRASH / f"{datetime.utcnow().strftime('%Y%m%dT%H%M%S')}-{secrets.token_hex(3)}"
        for old in {p for p in (held, target) if p is not None and p.is_file()}:
            trash.mkdir(parents=True, exist_ok=True)
            for each in (old, Path(f"{old}.aux.xml")):
                if each.exists():
                    os.replace(each, trash / each.name)
    if checked.stated_datum is None:
        validate.tag_navd88(target)    # USGS's 3DEP DEMs are NAVD88 by what they are (§10.1 item 4)
    os.replace(part, target)
    return target


def _finish(job, progress) -> None:
    """Writes how a job ended, whatever state the session is in: a job left marked as running would be resumed at every
    look at the page, and hold off every other job."""
    state, error, finished = job.state, job.error, job.finished_at
    try:
        progress.save(force=True)
        return
    except Cancelled:
        db.session.commit()
        return
    except Exception:  # noqa: BLE001 — the database dropped the session (a pooler restart): write it afresh
        log.exception("data admin: saving a job's end failed; trying again")
        db.session.rollback()
    fresh = db.session.get(DataJob, job.id)
    if fresh is not None:
        fresh.state, fresh.error, fresh.finished_at = state, error, finished
        fresh.files = [dict(f) for f in progress.files]
        db.session.commit()


def run(job_id: str, *, http=None) -> None:
    """Runs one job to its end, writing how it stands to its row."""
    job = db.session.get(DataJob, job_id)
    if job is None or job.state not in ("queued", "running"):
        return
    if http is None:
        import requests
        http = requests.Session()
    job.state, job.started_at, job.error = "running", job.started_at or datetime.utcnow(), None
    progress = _Progress(job)
    root = dem_root()
    placed = 0
    try:
        if root is None:
            raise volumes.NoRoom("TERRAIN_DATA_DIR names no folder that exists here.")
        volumes.check_room(root, 0, label="The DEM folder")
        _sweep(root)
        (root / INCOMING).mkdir(exist_ok=True)
        progress.save(force=True)     # reads cancel_requested before anything is fetched
        for entry in progress.files:
            if entry["state"] in ("done", "refused", "held"):
                continue
            if not usgs.allowed_url(entry["url"]):
                entry["state"], entry["reasons"] = "refused", ["Not one of USGS's file hosts."]
                continue
            target = root.joinpath(*entry["folder"], entry["name"])
            held = entry.get("held_path")
            if job.replace and held and not _inside(held, root):
                entry["state"], entry["reasons"] = "refused", [
                    "The file it would replace is in another DEM folder; replace that one by hand."]
                continue
            if target.exists() and not job.replace:
                entry["state"] = "held"
                continue
            part = root / INCOMING / f"{job.id}-{entry['name']}.part"
            volumes.check_room(root, entry["size"] - (part.stat().st_size if part.exists() else 0), label="The DEM folder")
            entry["state"] = "downloading"
            progress.save(force=True)
            try:
                _download(http, entry, part, root, progress)
                entry["state"] = "checking"
                progress.save(force=True)
                checked = validate.check_dem(part)
                entry["notes"] = checked.notes
                if not checked.ok:
                    entry["state"], entry["reasons"] = "refused", checked.reasons
                    part.unlink(missing_ok=True)
                else:
                    _put_in_place(entry, part, root, job.replace, checked)
                    entry["state"] = "done"
                    placed += 1
            except FileFailed as error:
                entry["state"], entry["reasons"] = "failed", [str(error)]
                part.unlink(missing_ok=True)
            progress.save(force=True)
        job.state = "done"
    except Cancelled:
        job.state = "cancelled"
    except volumes.NoRoom as error:
        job.state, job.error = "failed", str(error)[:300]
    except Exception:  # noqa: BLE001 — the admin sees that it failed; the log says why, with no place in it
        log.exception("data admin: job stopped")
        db.session.rollback()
        job.state, job.error = "failed", "Something went wrong on the server; the log has it."
    finally:
        for entry in progress.files:
            if entry["state"] in ("downloading", "checking"):
                entry["state"] = "queued" if job.state == "cancelled" else "failed"
        if job.state in ("cancelled", "failed") and root is not None:
            for entry in progress.files:
                if entry["state"] != "done":
                    (root / INCOMING / f"{job.id}-{entry['name']}.part").unlink(missing_ok=True)
        job.finished_at = datetime.utcnow()
        _finish(job, progress)
        if placed:
            import terrain_tiles
            from terrain_provider import LOCAL_CATALOG

            LOCAL_CATALOG.refresh()
            terrain_tiles.rewarm_after_change()
