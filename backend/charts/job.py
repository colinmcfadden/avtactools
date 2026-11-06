"""A VFR chart build as a Data tab job (docs/VFR_CHARTS.md §5.1 to §5.4): the process that builds, and the API's side that starts it,
watches it and registers what it made.

**The build process** (``python -m charts.job <job> <token> …``, ``run_child``) is a fresh interpreter, never a fork of the API, which
holds SAM: it opens the database through ``db_app`` and imports only the models and the chart code (a test checks ``torch`` never
comes). It runs at ``nice`` 10 in a process group of its own, with ``CHART_BUILD_PROCESSES`` workers (``build.pool``), and goes through
the stages of §5.3, each of which resumes: ``sizes``, ``download``, ``unzip``, ``check``, ``prepare``, ``tile``, ``lower``,
``validate``, ``finish``. Everything it writes is on the chart volume:

- ``.incoming/<build>/``: the zips as they come down (``<zip>.part`` until whole), and each chart unzipped (``<chart>.unzipping/``,
  renamed ``<chart>/`` once whole);
- ``.staging-<build>/``: its ``.lock``, its ``.pause``, ``checked.json`` (what the check found), ``work/`` (the prepared charts and the
  lower levels' seeds) and ``out/`` (``vfr.mbtiles``, ``coverage.json``, ``report.json`` and ``sources/``), which is renamed to
  ``builds/<build>/`` when the build is whole. The lock stays where it is through the rename (§5.1).

**One build process at a time**, by the staging folder's lock, never by a pid (§5.1). A process that cannot take it exits with
``BUSY`` and writes nothing. Every write it makes to the job's row is conditional on the row still carrying the token it was started
with (``Row``); a process whose token has gone exits with ``REPLACED`` and writes nothing more. It owns its progress and its end state;
it looks between tiles and download chunks for a cancel, for its parent (``PARENT_GONE``: it writes no end state, so the row stays
``running`` for the next ``resume``) and for a fresh pause file. It never writes ``state.json``.

**The API's side** (``watch``, from the runner's thread) starts the process under a new token once the lock is free, keeps the pause
file in step with the analyses crews wait on, kills the group 30 s after a cancel it has not answered, and, once it has ended:
registers a ``done`` build in ``state.json`` (§5.3, step 8); leaves a ``BUSY`` or ``PARENT_GONE`` row as it is; and writes ``failed``
(or ``cancelled``) for its own child that died without an end state, only once the lock is free. ``recover`` registers a finished build
whose parent was killed before it could, and ``resumable`` says whether a ``running`` row may be started again (its lock is free).
"""
from __future__ import annotations

import json
import logging
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
import zipfile
from datetime import date, datetime
from pathlib import Path

from data_admin import jobs, volumes
from models import db, DataJob

from . import build, catalog, faa, locks, neatline, state

log = logging.getLogger(__name__)

KIND = "chart_build"
PRODUCT = "vfr"
STAGES = ("sizes", "download", "unzip", "check", "prepare", "tile", "lower", "validate", "finish")

DONE, BUSY, PARENT_GONE, REPLACED = 0, 75, 76, 77     # the build process's exit codes: DONE means it wrote its end state

LOCK = ".lock"
PAUSE = ".pause"
OUT = "out"
WORK = "work"
CHECKED = "checked.json"
EMPTY = "empty.json"
SOURCES = "sources"
LABEL = "The chart folder"

POLL_S = 1.0                    # the parent's look at its child, its row and the analyses
CANCEL_GRACE_S = 30             # a cancel not answered in this long has the build's group killed
LOCK_WAIT_S = 30                # how long the parent waits for a dead child's lock to be let go
SAVE_EVERY_S = 2.0              # progress written at most this often
LOOK_EVERY_S = 2.0              # a cancel and the parent looked for at most this often
ROOM_EVERY_TILES = 1000

GB = volumes.GB
MAX_ZIP_BYTES = 2 * GB          # far above the FAA's largest (about 100 MB); a bound, not a guess
MAX_MEMBER_BYTES = 2 * GB
# What a build writes, from its zips' size (§5.4: about 22 GB from about 3.5 GB of zips for CONUS): the zips, kept as its sources; the
# charts unzipped; prepared as RGB (2 to 3 times the palette files); its tiles (about 7 GB).
UNZIP_FACTOR = 1.1
PREPARE_FACTOR = 2.5
TILES_FACTOR = 2.0
ESTIMATE_FACTOR = 1 + UNZIP_FACTOR + UNZIP_FACTOR * PREPARE_FACTOR + TILES_FACTOR
CONUS_ESTIMATE_BYTES = 22 * GB  # the room asked for before the zips' sizes are known (the job's first stage asks them)
TILE_GUESS_BYTES = 85_000       # a tile's size until the build has measured its own (§4.4)

_ZIP_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,80}\.zip$")
_FOLDER_NAME = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
BACKEND = Path(__file__).resolve().parent.parent


class Replaced(Exception):
    """The row no longer carries this process's token: another took over. Nothing more is written."""


class ParentGone(Exception):
    """The API process that started this one has gone (a redeploy, a restart): stop, writing no end state."""


class BuildRefused(Exception):
    """The build cannot go on, said in words (a sectional with no older copy, a validation that failed)."""


# ------------------------------------------------------------------------------------------------------------- settings


def processes(environ=os.environ) -> int:
    """``CHART_BUILD_PROCESSES``: the build's worker processes, 2 by default, 1 to 8."""
    try:
        value = int((environ.get("CHART_BUILD_PROCESSES") or "2").strip())
    except ValueError:
        value = 2
    return max(1, min(8, value))


def chart(chart_id: str) -> catalog.Chart:
    """A chart by its id (a test stands its own in)."""
    return catalog.BY_ID[chart_id]


def outline(chart_id: str) -> dict:
    """A chart's committed outline (a test stands its own in)."""
    return neatline.load(chart_id)


def only(environ=os.environ):
    """``CHART_BUILD_ONLY``, a developer's way to build a few charts rather than the whole of CONUS (a comma-separated list of chart
    ids): the charts named, or None for all of them. Never in production, where a build is always the whole catalog."""
    from security_config import is_production

    value = (environ.get("CHART_BUILD_ONLY") or "").strip()
    if not value or is_production(environ):
        return None
    chosen = [catalog.BY_ID[i.strip()] for i in value.split(",") if i.strip() in catalog.BY_ID]
    return chosen or None


def files_for(cycle: date, charts=None) -> list[dict]:
    """The job's files: each zip of the cycle once, with the charts in it. Made by the server from the catalog, never from the page."""
    files, seen = [], {}
    for c in charts or catalog.CHARTS:
        key = (c.folder, c.zip)
        if key not in seen:
            seen[key] = {"name": c.zip, "folder": c.folder, "url": catalog.zip_url(cycle, c.folder, c.zip), "size": None,
                         "done": 0, "state": "queued", "reasons": [], "notes": [], "charts": []}
            files.append(seen[key])
        seen[key]["charts"].append(c.id)
    return files


def cycle_of(job) -> date:
    """The cycle a job builds, from its build's name."""
    return date.fromisoformat(state.check_name(job.build).split(".")[0])


# ------------------------------------------------------------------------------------------------------------- the row


class Row:
    """The job's row as the build process writes it: only while it carries the process's token (§5.1)."""

    def __init__(self, job_id: str, token: str):
        self.job_id, self.token = job_id, token

    def _mine(self):
        return DataJob.query.filter(DataJob.id == self.job_id, DataJob.token == self.token)

    def update(self, **fields) -> None:
        try:
            changed = self._mine().update(fields, synchronize_session=False)
            db.session.commit()
        except Exception:
            db.session.rollback()
            raise
        if not changed:
            raise Replaced()

    def cancel_requested(self) -> bool:
        try:
            value = db.session.query(DataJob.cancel_requested).filter(DataJob.id == self.job_id,
                                                                       DataJob.token == self.token).scalar()
        finally:
            db.session.commit()          # ends the read, so it holds nothing (local SQLite locks the whole file)
        if value is None:
            raise Replaced()
        return bool(value)


# ------------------------------------------------------------------------------------------------------------- the build


class Build:
    """One run of the build process over its job: the stages, from the one its row names."""

    def __init__(self, job, token: str, where: Path, *, headroom: int = 0, http=None, parent: int | None = None,
                 workers: int | None = None):
        self.job_id, self.token, self.where = job.id, token, where
        self.name = state.check_name(job.build)
        self.cycle = cycle_of(job)
        self.row = Row(job.id, token)
        self.headroom = int(headroom or 0)
        self.http = http
        self.parent = parent
        self.workers = workers or processes()
        self.files = [dict(f) for f in job.files or []]
        self.stage = job.stage if job.stage in STAGES else STAGES[0]
        self.made_by = job.requested_by_name or ""
        self.staging = state.staging_dir(where, self.name)
        self.incoming = state.incoming_dir(where, self.name)
        self.out = self.staging / OUT
        self.work = self.staging / WORK
        self.pause = self.staging / PAUSE
        self.work_done = job.work_done or 0
        self.work_total = job.work_total or 0
        self._saved = self._looked = 0.0

    # -- looking out ---------------------------------------------------------------------------------------------

    def look(self, *, force: bool = False) -> None:
        """Raises if the build is to stop: its parent gone, or a cancel asked for."""
        now = time.monotonic()
        if not force and now - self._looked < LOOK_EVERY_S:
            return
        self._looked = now
        if self.parent is not None and os.getppid() != self.parent:
            raise ParentGone()
        if self.row.cancel_requested():
            raise jobs.Cancelled()

    def wait(self) -> None:
        """Waits while crews wait on an analysis: the pause file, while it is fresh (a stale one holds nothing)."""
        while build.pause_holds(self.pause):
            self.look(force=True)
            time.sleep(1)

    def between(self) -> None:
        self.look()
        self.wait()
        self.save()

    def save(self, *, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._saved < SAVE_EVERY_S:
            return
        self._saved = now
        files = [dict(f) for f in self.files]
        self.row.update(files=files, stage=self.stage, work_done=self.work_done, work_total=self.work_total,
                        files_done=sum(1 for f in files if f["state"] not in ("queued", "downloading")),
                        bytes_done=sum(int(f.get("done") or 0) for f in files),
                        bytes_total=sum(int(f.get("size") or 0) for f in files))

    def room(self, adding: int) -> None:
        volumes.check_room(self.where, int(adding), label=LABEL, headroom=self.headroom)

    # -- the run ----------------------------------------------------------------------------------------------------

    def run(self) -> int:
        try:
            self.row.update(state="running", error=None)
            self.look(force=True)
            for name in STAGES[STAGES.index(self.stage):]:
                self.stage = name
                self.work_done = self.work_total = 0
                self.save(force=True)
                getattr(self, "_" + name)()
            return DONE
        except ParentGone:
            return PARENT_GONE
        except Replaced:
            return REPLACED
        except jobs.Cancelled:
            return self._end("cancelled", None)
        except (volumes.NoRoom, BuildRefused) as error:
            return self._end("failed", str(error))
        except Exception:  # noqa: BLE001 — the admin sees that it failed; the log says why, with no place in it
            log.exception("chart build: stopped")
            db.session.rollback()
            return self._end("failed", "Something went wrong on the server; the log has it.")

    def _end(self, how: str, error: str | None) -> int:
        clean(self.where, self.name, keep_lock=True)
        for f in self.files:
            if f["state"] in ("downloading", "unzipping"):
                f["state"] = "queued" if how == "cancelled" else "failed"
        try:
            self.row.update(state=how, error=(error or None) and error[:300], finished_at=datetime.utcnow(),
                            files=[dict(f) for f in self.files])
        except Replaced:
            return REPLACED
        return DONE

    # -- 0: the sizes ---------------------------------------------------------------------------------------------------

    def _sizes(self) -> None:
        for entry in self.files:
            if entry.get("size") or entry["state"] != "queued":
                continue
            self.look()
            try:
                entry["size"] = self._size(entry)
            except jobs.FileFailed as error:
                entry["state"], entry["reasons"] = "failed", [str(error)]
            self.save()
        total = sum(int(f.get("size") or 0) for f in self.files if f["state"] != "failed")
        self.room(int(total * ESTIMATE_FACTOR) - self._written())

    def _size(self, entry) -> int:
        if not faa.allowed_url(entry["url"]) or not _ZIP_NAME.match(entry["name"]):
            raise jobs.FileFailed("Not one of the FAA's chart files.")
        for attempt in range(jobs.TRIES):
            try:
                response = self.http.head(entry["url"], headers={"User-Agent": faa.USER_AGENT}, timeout=jobs.TIMEOUT,
                                          allow_redirects=False)
                status = response.status_code
                if status == 200:
                    try:
                        size = int(response.headers.get("Content-Length"))
                    except (TypeError, ValueError):
                        raise jobs.FileFailed("The FAA gave no size for this file.") from None
                    if not 0 < size <= MAX_ZIP_BYTES:
                        raise jobs.FileFailed("The FAA's file is not a size a chart's zip can be.")
                    return size
                if status != 429 and status < 500:
                    raise jobs.FileFailed(f"The FAA answered {status} for this file.")
            except jobs.requests_errors():
                pass
            except (ConnectionError, TimeoutError):
                pass
            time.sleep(min(30, 2 ** attempt))
        raise jobs.FileFailed("The FAA could not be asked for this file's size. Try again later.")

    def _written(self) -> int:
        """What the build has written already, which the free space shows."""
        total = 0
        for top in (self.incoming, self.staging):
            for folder, _, names in os.walk(top):
                for name in names:
                    try:
                        total += os.path.getsize(os.path.join(folder, name))
                    except OSError:
                        pass
        return total

    # -- 1: download ----------------------------------------------------------------------------------------------------

    def _download(self) -> None:
        self.incoming.mkdir(parents=True, exist_ok=True)
        self.work_total = len(self.files)
        for entry in self.files:
            self.work_done += 1
            if entry["state"] not in ("queued", "downloading"):
                continue
            if not _ZIP_NAME.match(entry["name"]):
                entry["state"], entry["reasons"] = "failed", ["Not one of the FAA's chart files."]
                continue
            target = self.incoming / entry["name"]
            if target.is_file() and target.stat().st_size == entry["size"]:
                entry["state"], entry["done"] = "downloaded", entry["size"]
                continue
            part = self.incoming / f"{entry['name']}.part"
            self.room(entry["size"] - (part.stat().st_size if part.exists() else 0))
            entry["state"] = "downloading"
            self.save(force=True)
            try:
                jobs._download(self.http, entry, part, self.where, _Progress(self), source=faa.SOURCE, label=LABEL,
                               wait=self.wait, headroom=self.headroom)
                os.replace(part, target)
                entry["state"] = "downloaded"
            except jobs.FileFailed as error:
                entry["state"], entry["reasons"] = "failed", [str(error)]
                part.unlink(missing_ok=True)
            self.save(force=True)

    # -- 2: unzip -------------------------------------------------------------------------------------------------------

    def _unzip(self) -> None:
        todo = [e for e in self.files if e["state"] == "downloaded"]
        self.work_total = len(todo)
        for entry in todo:
            self.between()
            self.work_done += 1
            try:
                self._unzip_one(entry)
                entry["state"] = "unzipped"
            except jobs.FileFailed as error:
                entry["state"], entry["reasons"] = "failed", [str(error)]
            self.save(force=True)

    def _unzip_one(self, entry) -> None:
        path = self.incoming / entry["name"]
        try:
            with zipfile.ZipFile(path) as zf:
                wanted = []
                for chart_id in entry["charts"]:
                    c = chart(chart_id)
                    if (self.incoming / c.id).is_dir():
                        continue
                    members = {}
                    for name in (c.tif, c.tif.rsplit(".", 1)[0] + ".tfw", build.htm_name(c)):
                        try:
                            members[name] = zf.getinfo(name)
                        except KeyError:
                            continue
                    if c.tif not in members:
                        entry.setdefault("notes", []).append(f"{c.title}: its zip holds no {c.tif}")
                        continue
                    if any(info.file_size > MAX_MEMBER_BYTES for info in members.values()):
                        raise jobs.FileFailed(f"{c.title}: a file in its zip is larger than a chart can be.")
                    wanted.append((c, members))
                self.room(sum(info.file_size for _, members in wanted for info in members.values()))
                for c, members in wanted:
                    self._extract(zf, c, members)
        except (zipfile.BadZipFile, zipfile.LargeZipFile, OSError, EOFError) as error:
            raise jobs.FileFailed(f"The zip cannot be read ({error.__class__.__name__}).") from None

    def _extract(self, zf, c, members) -> None:
        """A chart's files, into a folder of their own that is renamed into place once whole. Only the names the catalog gives are
        taken, and each is written under its own name in that folder: nothing in the zip decides a path."""
        final = self.incoming / c.id
        part = self.incoming / f"{c.id}.unzipping"
        shutil.rmtree(part, ignore_errors=True)
        part.mkdir()
        for name, info in members.items():
            written = 0
            with zf.open(info) as src, open(part / os.path.basename(name), "wb") as dst:
                while True:
                    block = src.read(jobs.CHUNK)
                    if not block:
                        break
                    written += len(block)
                    if written > MAX_MEMBER_BYTES:
                        raise jobs.FileFailed(f"{c.title}: a file in its zip is larger than a chart can be.")
                    dst.write(block)
                    self.look()
        os.replace(part, final)

    # -- 3: check -------------------------------------------------------------------------------------------------------

    def _check(self) -> None:
        """Each chart checked (§4.2). One that fails, or whose zip could not be had, is held back: a copy kept by the newest build
        that has one stands in for it, copied into this build's sources. A sectional with none refuses the build here."""
        own = self.out / SOURCES / self.cycle.isoformat()
        own.mkdir(parents=True, exist_ok=True)
        old_state = state.read(self.where)
        found, held_back = [], []
        ids = [i for e in self.files for i in e["charts"]]
        self.work_total = len(ids)
        for entry in self.files:
            for chart_id in entry["charts"]:
                self.between()
                self.work_done += 1
                c = chart(chart_id)
                try:
                    checked, line = self._check_own(entry, c), None
                    with open(own / f"{c.id}.geojson", "w", encoding="utf-8") as f:
                        f.write(neatline.feature_text(outline(c.id)))
                    found.append(_checked_dict(checked, own.name))
                    continue
                except build.Refused as error:
                    reason = str(error)
                kept = self._held_copy(c, old_state)
                if kept is None:
                    if c.tier == "sectional":
                        raise BuildRefused(f"The {c.title} was refused ({reason}), and no older build keeps a copy to stand in for "
                                           "it: its ground would be a hole.")
                    line = f"{c.title}: left out, {reason}; no older build keeps a copy of it."
                    held_back.append({"id": c.id, "title": c.title, "reason": reason, "kept": None})
                else:
                    checked, folder = kept
                    found.append(_checked_dict(checked, folder))
                    line = f"{c.title}: held back, {reason}; the copy in build {checked.kept} stands in for it."
                    held_back.append({"id": c.id, "title": c.title, "reason": reason, "kept": checked.kept})
                entry["reasons"] = [*entry.get("reasons", []), line]
        _write_json(self.staging / CHECKED, {"charts": found, "held_back": held_back})
        self.save(force=True)

    def _check_own(self, entry, c) -> build.Checked:
        if entry["state"] != "unzipped":
            raise build.Refused(entry["reasons"][0].rstrip(".") if entry.get("reasons") else "its zip could not be had")
        folder = self.incoming / c.id
        if not folder.is_dir():
            raise build.Refused(f"its zip holds no {c.tif}")
        try:
            htm = (folder / os.path.basename(build.htm_name(c))).read_text(encoding="utf-8", errors="replace")
        except OSError:
            raise build.Refused(f"its zip holds no {build.htm_name(c)}") from None
        return build.check_file(c, str(folder / os.path.basename(c.tif)), htm, outline(c.id))

    def _held_copy(self, c, old_state):
        """The newest copy of ``c`` a listed build keeps, copied into this build's sources and checked against the outline it was checked
        against then: ``(Checked, folder name)``, or None."""
        held = state.holding(self.where, c.id, old_state)
        if held is None:
            return None
        old_build, old_folder = held
        if not _FOLDER_NAME.match(old_folder.name.replace(".", "-")):
            return None
        mine = self.out / SOURCES / old_folder.name
        mine.mkdir(parents=True, exist_ok=True)
        try:
            for name in (c.zip, f"{c.id}.geojson"):
                if not (mine / name).is_file():
                    src = old_folder / name
                    self.room(src.stat().st_size)
                    shutil.copyfile(src, mine / f"{name}.part")
                    os.replace(mine / f"{name}.part", mine / name)
            with open(mine / f"{c.id}.geojson", encoding="utf-8") as f:
                kept_outline = json.load(f)
            checked = build.check(str(mine), c, kept_outline)
        except (OSError, ValueError, build.Refused):
            log.warning("chart build: a kept copy could not be used")
            return None
        checked.kept = old_build
        return checked, mine.name

    # -- 4: prepare -----------------------------------------------------------------------------------------------------

    def _charts(self):
        """What the check found, with each chart's outline: from ``checked.json`` and the outlines kept with its sources."""
        with open(self.staging / CHECKED, encoding="utf-8") as f:
            found = json.load(f)
        checked, outlines = [], {}
        for d in found["charts"]:
            c = chart(d["id"])
            with open(self.out / SOURCES / d["source"] / f"{c.id}.geojson", encoding="utf-8") as f:
                outlines[c.id] = json.load(f)
            checked.append(build.Checked(c, d["member"], date.fromisoformat(d["start"]), date.fromisoformat(d["end"]),
                                         d["scale"], d["mpp"], d["kept"]))
        return checked, outlines, found["held_back"]

    def _prepare(self) -> None:
        checked, outlines, _ = self._charts()
        self.work.mkdir(parents=True, exist_ok=True)
        todo = [c for c in checked if not (self.work / f"{c.chart.id}.tif").is_file()]
        self.room(sum(_member_size(c.member) for c in todo) * PREPARE_FACTOR)
        self.work_total, self.work_done = len(checked), len(checked) - len(todo)

        def take(done):
            self.work_done += len(done)

        with build.pool(min(self.workers, max(1, len(todo))), str(self.pause)) as workers:
            build.run_tasks(workers, build._prepare_one, [(c, str(self.work), outlines[c.chart.id]) for c in todo], take,
                            self.between, window=self.workers * 2)
        # each zip goes with the build as its sources once its charts are prepared; what was unzipped goes
        own = self.out / SOURCES / self.cycle.isoformat()
        for entry in self.files:
            if entry["state"] != "unzipped":
                continue
            path = self.incoming / entry["name"]
            if any(c.chart.id in entry["charts"] and c.kept is None for c in checked) and path.is_file():
                os.replace(path, own / entry["name"])
            for chart_id in entry["charts"]:
                shutil.rmtree(self.incoming / chart_id, ignore_errors=True)
            entry["state"] = "done"
        self.save(force=True)

    # -- 5 and 6: tiles -------------------------------------------------------------------------------------------------

    def _plan(self):
        checked, outlines, held_back = self._charts()
        prints = {c.chart.id: build.footprint(c.chart, outlines[c.chart.id]) for c in checked}
        tiers = {c.chart.id: c.chart.tier for c in checked}
        paths = {c.chart.id: str(self.work / f"{c.chart.id}.tif") for c in checked}
        top = build.plan(list(prints.values()))
        return checked, outlines, held_back, prints, tiers, paths, top

    def _mbtiles(self) -> build.MBTiles:
        self.out.mkdir(parents=True, exist_ok=True)
        return build.MBTiles(str(self.out / "vfr.mbtiles"))

    def _empties(self) -> dict[int, set]:
        """The tiles that came out with nothing on them, which are not stored: kept beside the build, so a resumed build neither
        draws them again nor takes them for missing."""
        try:
            with open(self.staging / EMPTY, encoding="utf-8") as f:
                return {int(z): {tuple(t) for t in tiles} for z, tiles in json.load(f).items()}
        except (OSError, ValueError, TypeError):
            return {}

    def _keep_empties(self, empties: dict[int, set]) -> None:
        _write_json(self.staging / EMPTY, {str(z): sorted(map(list, tiles)) for z, tiles in sorted(empties.items())})

    def _tile(self) -> None:
        _, _, _, _, tiers, paths, top = self._plan()
        empties = self._empties()
        mb = self._mbtiles()
        try:
            skip = mb.keys() | {(z, x, y) for z, tiles in empties.items() for x, y in tiles}
            tasks = build.tile_tasks(top, tiers, paths, str(self.work), skip)
            self.work_total = sum(len(t) for t in top.values())
            self.work_done = self.work_total - len(tasks)
            self._tiles(mb, build._tile_one, tasks, empties, lower_guess=self.work_total // 3)
        finally:
            mb.commit()
            mb.db.close()

    def _lower(self) -> None:
        """Each level below 11 from the one above it, as the file and the empties kept say it stands: so a resumed build works out
        again what it had done, and does only the rest (§5.3, step 6)."""
        empties = self._empties()
        mb = self._mbtiles()
        try:
            for z in range(build.TOP - 1, build.LOWEST - 1, -1):
                held = _held(mb)
                below = {(x // 2, y // 2) for x, y in held.get(z + 1, set())}
                skip = {(z, x, y) for x, y in held.get(z, set()) | empties.get(z, set())}
                tasks = build.lower_tasks(z, below, mb.path, str(self.work), skip)
                self.work_total, self.work_done = len(below), len(below) - len(tasks)
                self._tiles(mb, build._lower_one, tasks, empties, lower_guess=len(below) // 3)
                mb.commit()
        finally:
            mb.commit()
            mb.db.close()

    def _tiles(self, mb, fn, tasks, empties: dict[int, set], *, lower_guess: int) -> None:
        stored = {"tiles": 0, "bytes": 0}
        last = {"room": 0, "kept": time.monotonic()}

        def take(results):
            for z, x, y, data, _, _ in results:
                self.work_done += 1
                if data is None:
                    empties.setdefault(z, set()).add((x, y))
                else:
                    mb.put(z, x, y, data)
                    stored["tiles"] += 1
                    stored["bytes"] += len(data)
            if time.monotonic() - last["kept"] > SAVE_EVERY_S:
                last["kept"] = time.monotonic()
                self._keep_empties(empties)
            if self.work_done - last["room"] >= ROOM_EVERY_TILES:
                last["room"] = self.work_done
                mean = stored["bytes"] / stored["tiles"] if stored["tiles"] else TILE_GUESS_BYTES
                self.room(((self.work_total - self.work_done) + lower_guess) * mean)

        try:
            with build.pool(self.workers, str(self.pause)) as workers:
                build.run_tasks(workers, fn, tasks, take, self.between, window=self.workers * 4)
        finally:
            self._keep_empties(empties)

    # -- 7: validate ----------------------------------------------------------------------------------------------------

    def _validate(self) -> None:
        checked, outlines, held_back, prints, tiers, paths, top = self._plan()
        mb = self._mbtiles()
        try:
            expected, empty = _expected(mb, top, self._empties())
            report = build.validate(mb, expected, empty, checked, paths, prints,
                                    [i for e in self.files for i in e["charts"] if chart(i).tier == "sectional"])
        finally:
            mb.commit()
            mb.db.close()
        _write_json(self.staging / "validate.json", report)
        if report["refused"]:
            raise BuildRefused("The build did not validate: " + "; ".join(report["refused"]))

    # -- 8: finish ------------------------------------------------------------------------------------------------------

    def _finish(self) -> None:
        final = state.build_dir(self.where, self.name)
        if not final.is_dir():
            checked, outlines, held_back, prints, tiers, paths, top = self._plan()
            mb = self._mbtiles()
            expected, empty = _expected(mb, top, self._empties())
            counts = {z: len(expected[z] - empty.get(z, set())) for z in expected}
            cov = build.coverage(self.name, self.cycle, checked, outlines, prints, counts)
            with open(self.out / "coverage.json.part", "w", encoding="utf-8") as f:
                f.write(build.coverage_text(cov))
            os.replace(self.out / "coverage.json.part", self.out / "coverage.json")
            w, s, e, n = cov["bounds"]
            mb.finish({"name": "FAA VFR charts", "format": "webp", "type": "baselayer", "version": self.name,
                       "description": "FAA VFR sectional, terminal area and helicopter charts, stacked (docs/VFR_CHARTS.md)",
                       "attribution": "FAA", "bounds": f"{w},{s},{e},{n}", "minzoom": cov["minLevel"],
                       "maxzoom": cov["maxLevel"], "tilesize": build.TILE})
            try:
                with open(self.staging / "validate.json", encoding="utf-8") as f:
                    checks = json.load(f)
            except (OSError, ValueError):
                checks = {"refused": [], "notes": []}
            _write_json(self.out / "report.json", {
                "build": self.name, "cycle": self.cycle.isoformat(), "made_by": self.made_by,
                "tiles": {str(z): counts[z] for z in sorted(counts)}, "held_back": held_back, "validate": checks})
            shutil.rmtree(self.work, ignore_errors=True)
            final.parent.mkdir(parents=True, exist_ok=True)
            os.replace(self.out, final)
        shutil.rmtree(self.incoming, ignore_errors=True)
        self.row.update(state="done", stage="finish", error=None, finished_at=datetime.utcnow(),
                        files=[dict(f) for f in self.files])


class _Progress:
    """What ``jobs._download`` saves through: the build's own progress, which also looks for a cancel and the parent."""

    def __init__(self, b: Build):
        self.b = b

    def save(self, *, force: bool = False):
        self.b.look()
        self.b.save(force=force)


def _checked_dict(c: build.Checked, source: str) -> dict:
    return {"id": c.chart.id, "member": c.member, "start": c.start.isoformat(), "end": c.end.isoformat(), "scale": c.scale,
            "mpp": c.metres_per_pixel, "kept": c.kept, "source": source}


def _member_size(member: str) -> int:
    if member.startswith("/vsizip/"):
        return 300_000_000      # a kept copy, read in its zip: about the largest chart unzipped
    try:
        return os.path.getsize(member)
    except OSError:
        return 0


def _held(mb) -> dict[int, set]:
    held: dict[int, set] = {}
    for z, x, r in mb.db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
        held.setdefault(z, set()).add((x, build.tms_row(z, r)))
    return held


def _expected(mb, top, empties: dict[int, set]) -> tuple[dict, dict]:
    """The tiles each level should hold, and those of them that came out empty: above 10 the plan's, below each the parents of the
    tiles the level above holds (§5.3, steps 5 and 6). A tile expected and neither held nor kept as empty is missing, which
    validating refuses."""
    held = _held(mb)
    expected = {z: set(tiles) for z, tiles in top.items()}
    for z in range(build.TOP - 1, build.LOWEST - 1, -1):
        expected[z] = {(x // 2, y // 2) for x, y in held.get(z + 1, set())}
    empty = {z: expected[z] & empties.get(z, set()) for z in expected}
    return expected, empty


def _write_json(path: Path, value) -> None:
    part = path.with_name(path.name + ".part")
    with open(part, "w", encoding="utf-8") as f:
        json.dump(value, f, indent=1)
    os.replace(part, path)


# ------------------------------------------------------------------------------------------------------------- cleaning up


def clean(where: Path, name: str, *, keep_lock: bool = False) -> bool:
    """Deletes a build's staging and what it downloaded, as a failed download deletes its ``.part`` files: False, deleting nothing,
    while its lock is held elsewhere. The build process, which holds the lock, passes ``keep_lock``: all but the lock goes, and its
    folder goes once it has let go. Anyone else takes the lock first, so a build that runs still is never deleted under it, nor its
    lock file taken from it (a new one would let a second build start)."""
    staging = state.staging_dir(where, name)
    fd = None
    if not keep_lock and staging.is_dir():
        fd = locks.try_lock(staging / LOCK)
        if fd is None:
            return False
    try:
        if staging.is_dir():
            for child in staging.iterdir():
                if child.name == LOCK:
                    continue
                if child.is_dir() and not child.is_symlink():
                    shutil.rmtree(child, ignore_errors=True)
                else:
                    child.unlink(missing_ok=True)
        shutil.rmtree(state.incoming_dir(where, name), ignore_errors=True)
        if not keep_lock:
            _drop(staging)
    finally:
        if fd is not None:
            locks.release(fd)
    return True


def _drop(staging: Path) -> None:
    """The staging folder itself, once nothing in it is wanted: called only by whoever holds or has just let go of its lock, for a
    job whose row has ended (nothing starts it again)."""
    shutil.rmtree(staging, ignore_errors=True)


# ------------------------------------------------------------------------------------------------------------- the process


def run_child(job_id: str, token: str, *, headroom: int = 0, http=None, parent: int | None = None,
              workers: int | None = None, where: Path | None = None) -> int:
    """The build process's work, in an app context: takes the staging lock or exits ``BUSY``, then runs the build (``Build``)."""
    where = where or state.root()
    job = db.session.get(DataJob, job_id)
    db.session.commit()
    if job is None or job.token != token or job.state not in ("queued", "running"):
        return REPLACED
    if where is None:
        Row(job_id, token).update(state="failed", error="CHART_TILES_DIR is not set on this server.",
                                  finished_at=datetime.utcnow())
        return DONE
    if http is None:
        import requests

        http = requests.Session()
    if not where.is_dir():
        Row(job_id, token).update(state="failed", error="The chart folder is not there: is its volume mounted?",
                                  finished_at=datetime.utcnow())
        return DONE
    b = Build(job, token, where, headroom=headroom, http=http, parent=parent, workers=workers)
    b.staging.mkdir(exist_ok=True)
    fd = locks.try_lock(b.staging / LOCK)
    if fd is None:
        return BUSY
    code = None
    try:
        code = b.run()
        return code
    finally:
        locks.release(fd)
        if code == DONE:
            _drop(b.staging)


def main(argv=None) -> int:
    """``python -m charts.job <job id> <token> [--headroom N] [--parent PID]``: the build process, started by ``watch``."""
    import argparse

    ap = argparse.ArgumentParser(description=main.__doc__)
    ap.add_argument("job")
    ap.add_argument("token")
    ap.add_argument("--headroom", type=int, default=0)
    ap.add_argument("--parent", type=int, default=None)
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO)
    try:
        os.nice(10)
    except OSError:
        pass
    build.die_with_parent()
    if a.parent is not None and os.getppid() != a.parent:
        return PARENT_GONE
    import db_app

    app = db_app.make_app()
    with app.app_context():
        try:
            return run_child(a.job, a.token, headroom=a.headroom, parent=a.parent)
        finally:
            db.session.remove()


# ------------------------------------------------------------------------------------------------------------- the API's side


def _start(job_id: str, token: str, headroom: int):
    """The build process: a fresh interpreter in a session (so a process group) of its own."""
    return subprocess.Popen([sys.executable, "-m", "charts.job", job_id, token, "--headroom", str(int(headroom)),
                             "--parent", str(os.getpid())], cwd=str(BACKEND), start_new_session=True,
                            stdin=subprocess.DEVNULL)


def _analysis_running() -> bool:
    """Whether an analysis, viewshed or export runs in this process now (``terrain_tiles``, where the API has loaded it)."""
    tiles = sys.modules.get("terrain_tiles")
    flag = getattr(tiles, "_no_analysis", None)
    return flag is not None and not flag.is_set()


def _keep_pause(staging: Path, running: bool) -> None:
    pause = staging / PAUSE
    try:
        if running and staging.is_dir():
            pause.touch()
            os.utime(pause)
        else:
            pause.unlink(missing_ok=True)
    except OSError:
        pass


def _kill_group(child) -> None:
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        pass


def _fresh(job_id: str):
    db.session.expire_all()
    job = db.session.get(DataJob, job_id)
    db.session.commit()
    return job


def watch(job_id: str, *, start=None, poll: float = POLL_S, analysis=_analysis_running) -> None:
    """Runs a chart build job: starts its process under a new token, watches it to its end, and does what its end asks (§5.1)."""
    job = db.session.get(DataJob, job_id)
    if job is None or job.state not in ("queued", "running"):
        return
    where = state.root()
    why = None
    if where is None:
        why = "CHART_TILES_DIR is not set on this server."
    elif not locks.supported():
        why = "Chart builds need a Linux or macOS server: this one cannot lock a file across processes."
    else:
        try:
            state.check_name(job.build or "")
        except ValueError:
            why = "The job names no build."
    if why:
        job.state, job.error, job.finished_at = "failed", why, datetime.utcnow()
        db.session.commit()
        return
    staging = state.staging_dir(where, job.build)
    if locks.held(staging / LOCK):
        return          # it runs somewhere already (the other container of a redeploy): looked at again later
    if job.cancel_requested:
        clean(where, job.build)
        job.state, job.finished_at = "cancelled", datetime.utcnow()
        db.session.commit()
        return
    (staging / PAUSE).unlink(missing_ok=True)       # one left by a parent that was killed holds nothing
    token = secrets.token_hex(16)
    job.token, job.state, job.started_at, job.error = token, "running", job.started_at or datetime.utcnow(), None
    db.session.commit()
    try:
        headroom = volumes.stream_headroom(where)
    except Exception:  # noqa: BLE001 — no streamed cache to walk is no room to leave for one
        headroom = 0
    child = (start or _start)(job_id, token, headroom)
    code, cancelled = _follow(job_id, child, staging, poll, analysis)
    _keep_pause(staging, False)
    _after(job_id, token, code, cancelled, child, where)


def _follow(job_id, child, staging, poll, analysis):
    """Watches the child to its end: keeps its pause file in step, and kills its group once a cancel has gone unanswered too long."""
    asked = None
    while True:
        code = child.poll()
        if code is not None:
            return code, asked is not None
        _keep_pause(staging, analysis())
        try:
            row = _fresh(job_id)
            stop = row is None or row.cancel_requested
        except Exception:  # noqa: BLE001 — the database dropped the session: look again
            db.session.rollback()
            stop = False
        if stop and asked is None:
            asked = time.monotonic()
        if asked is not None and time.monotonic() - asked > CANCEL_GRACE_S:
            _kill_group(child)
        time.sleep(poll)


def _after(job_id, token, code, cancelled, child, where) -> None:
    job = _fresh(job_id)
    if job is None:
        return
    name = job.build
    if job.state == "done":
        register(job, where)
        return
    if job.state in ("failed", "cancelled"):
        clean(where, name)           # deletes nothing while a lock is held: it is the lock's holder's
        return
    if code in (BUSY, PARENT_GONE, REPLACED):
        return          # it wrote nothing: the row is as it was, for the next look
    # Died without an end state (killed, out of memory): the rest of its group goes too, and only once its lock is free is the
    # row written, and only while it is still this child's.
    _kill_group(child)
    lock = state.staging_dir(where, name) / LOCK
    waited = 0.0
    while locks.held(lock) and waited < LOCK_WAIT_S:
        time.sleep(1)
        waited += 1
    if locks.held(lock):
        return
    if cancelled or job.cancel_requested:
        fields = {"state": "cancelled", "finished_at": datetime.utcnow()}
    else:
        log.warning("chart build: the build process ended without finishing (exit status %s)", code)
        fields = {"state": "failed", "error": "The build process stopped without finishing; the server's log may say why.",
                  "finished_at": datetime.utcnow()}
    try:
        Row(job_id, token).update(**fields)
    except Replaced:
        return
    clean(where, name)


# ------------------------------------------------------------------------------------------------------------- registering


def build_info(job, folder: Path) -> dict:
    """What ``state.json`` lists of a finished build (§5.5), from its folder."""
    with open(folder / "coverage.json", encoding="utf-8") as f:
        cov = json.load(f)
    try:
        with open(folder / "report.json", encoding="utf-8") as f:
            report = json.load(f)
    except (OSError, ValueError):
        report = {}
    size = 0
    for top, _, names in os.walk(folder):
        for n in names:
            try:
                size += os.path.getsize(os.path.join(top, n))
            except OSError:
                pass
    made = job.finished_at or datetime.utcfromtimestamp(os.path.getmtime(folder / "coverage.json"))
    return {"cycle": cov["cycle"], "effective": cov["effective"], "expires": cov["expires"],
            "made": made.strftime("%Y-%m-%dT%H:%M:%SZ"), "by": job.requested_by_name or "", "job": job.id, "bytes": size,
            "tiles": sum(int(v) for v in cov.get("tiles", {}).values()), "held_back": report.get("held_back", [])}


def register(job, where: Path) -> bool:
    """Lists a finished build in ``state.json``, once: True if it was listed now (§5.3, step 8)."""
    folder = state.build_dir(where, job.build)
    if not (folder / "coverage.json").is_file():
        return False
    listed = state.register(where, job.build, build_info(job, folder))
    clean(where, job.build)          # what a build process killed after its row said done left behind
    return listed


def recover(runner=None, *, limit: int = 20) -> None:
    """At a look at the tab: registers a finished build its parent was killed before registering, and lets go of what a failed or
    cancelled build left whose lock is free (§5.3, step 8)."""
    where = state.root()
    if where is None or not locks.supported():
        return
    try:
        listed = state.read(where)["builds"]
    except state.StateError:
        log.warning("chart build: state.json cannot be read")
        return
    recent = (DataJob.query.filter(DataJob.kind == KIND, DataJob.state.in_(("done", "failed", "cancelled")))
              .order_by(DataJob.created_at.desc()).limit(limit).all())
    for job in recent:
        try:
            name = state.check_name(job.build or "")
        except ValueError:
            continue
        if runner is not None and runner.knows(job.id):
            continue
        try:
            if job.state == "done" and name not in listed:
                if register(job, where):
                    listed = state.read(where)["builds"]
            elif state.staging_dir(where, name).exists() or state.incoming_dir(where, name).exists():
                clean(where, name)
        except (OSError, state.StateError, ValueError, KeyError):
            log.warning("chart build: recovering a build failed")
    db.session.commit()


def resumable(job) -> bool:
    """Whether a chart job left queued or running may be started again: not while its lock is held (it runs somewhere still)."""
    where = state.root()
    if where is None or not locks.supported():
        return True         # watch says why, in words
    try:
        return not locks.held(state.staging_dir(where, job.build) / LOCK)
    except ValueError:
        return True


if __name__ == "__main__":
    raise SystemExit(main())
