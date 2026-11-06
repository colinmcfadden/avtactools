"""A VFR chart build as a Data tab job (docs/VFR_CHARTS.md §5; C1): ``state.json`` and its lock, the routes, the build process and
the API's side of it.

In CI's list (``.github/workflows/backend.yaml``): it imports neither ``app.py`` nor ``terrain_tiles``. The FAA is stood in for
(``FakeFaa``) with the two small charts ``test_charts`` makes. The build process runs in this process (``run_child``) save where a
real one is the point: its lock against a resume, and what it imports.
"""
import json
import os
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import unittest
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
for _path in (BACKEND_DIR, BACKEND_DIR / "tests"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from flask import Flask  # noqa: E402

from charts import build, catalog, faa, job as chart_job, locks, state  # noqa: E402
from data_admin import jobs, volumes  # noqa: E402
from models import DataJob, User, db  # noqa: E402
from test_charts import SEC, TAC, _chart  # noqa: E402

CYCLE = date(2026, 10, 29)
NEXT = date(2026, 12, 24)
CHARTS = {SEC.id: SEC, TAC.id: TAC}
ROW = ("state", "token", "stage", "error", "files", "work_done", "work_total", "bytes_done", "bytes_total", "files_done",
       "cancel_requested", "finished_at", "build")


@lru_cache(maxsize=1)
def made():
    """The two charts as the FAA zips them, made once: their zips' bytes and their outlines."""
    folder = tempfile.mkdtemp(prefix="chart-zips-")
    outlines = {
        SEC.id: _chart(folder, SEC, (-84.85, 34.25, -84.25, 34.75), 500_000, [(150, 190, 140), (190, 210, 160), (120, 160, 120)]),
        TAC.id: _chart(folder, TAC, (-84.65, 34.42, -84.45, 34.58), 250_000, [(200, 80, 60), (230, 140, 90)]),
    }
    zips = {c.id: (Path(folder) / c.zip).read_bytes() for c in (SEC, TAC)}
    shutil.rmtree(folder, ignore_errors=True)
    return zips, outlines


class Env:
    """Environment variables for one test, put back after."""

    def __init__(self, case, **values):
        patcher = patch.dict(os.environ, {k: str(v) for k, v in values.items()})
        patcher.start()
        case.addCleanup(patcher.stop)


def until(condition, timeout=30.0, what="it"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(0.1)
    raise AssertionError(f"waited {timeout:.0f} s for {what}")


class FakeResponse:
    def __init__(self, status, body=b"", *, length=None):
        self.status_code, self._body = status, body
        self.headers = {"Content-Length": str(len(body) if length is None else length)}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def iter_content(self, size):
        for start in range(0, len(self._body), 64 * 1024):
            yield self._body[start:start + 64 * 1024]


class FakeFaa:
    """The FAA's chart host: a HEAD answers a file's size, a GET its bytes (from a Range), by URL; a URL it has not is a 404."""

    def __init__(self, files):
        self.files, self.asked = dict(files), []

    def head(self, url, headers=None, allow_redirects=True, **_):
        self.asked.append(("HEAD", url, allow_redirects))
        body = self.files.get(url)
        return FakeResponse(404) if body is None else FakeResponse(200, length=len(body))

    def get(self, url, headers=None, allow_redirects=True, **_):
        self.asked.append(("GET", url, allow_redirects))
        body = self.files.get(url)
        if body is None:
            return FakeResponse(404)
        start = int(headers["Range"].split("=")[1].rstrip("-")) if headers and headers.get("Range") else 0
        return FakeResponse(206 if start else 200, body[start:])


# --------------------------------------------------------------------------------------------- state.json, with no database


class StateTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)

    def test_build_numbers_are_never_given_twice(self):
        self.assertEqual([state.take_number(self.root, CYCLE) for _ in range(3)], ["2026-10-29.1", "2026-10-29.2", "2026-10-29.3"])
        self.assertEqual(state.take_number(self.root, NEXT), "2026-12-24.1")
        # A build deleted, its folder gone: its number is not given again (its tiles may be in caches under its URL).
        (self.root / "builds" / "2026-10-29.3").mkdir(parents=True)
        shutil.rmtree(self.root / "builds")
        self.assertEqual(state.take_number(self.root, CYCLE), "2026-10-29.4")
        self.assertEqual(state.read(self.root)["numbers"], {"2026-10-29": 4, "2026-12-24": 1})

    def test_writers_at_once_all_land(self):
        names, errors = [], []

        def take():
            try:
                for _ in range(5):
                    names.append(state.take_number(self.root, CYCLE))
                    state.register(self.root, names[-1], {"cycle": CYCLE.isoformat()})
            except Exception as error:  # noqa: BLE001
                errors.append(error)

        threads = [threading.Thread(target=take) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        self.assertEqual(errors, [])
        self.assertEqual(sorted(names, key=state._order), [f"2026-10-29.{n}" for n in range(1, 41)])
        held = state.read(self.root)
        self.assertEqual(held["numbers"], {"2026-10-29": 40})
        self.assertEqual(len(held["builds"]), 40)
        self.assertEqual([p.name for p in self.root.iterdir() if p.name.startswith(".state")], [])   # no temporary file left

    def test_registering_is_idempotent(self):
        self.assertTrue(state.register(self.root, "2026-10-29.1", {"cycle": "2026-10-29", "tiles": 5}))
        self.assertFalse(state.register(self.root, "2026-10-29.1", {"cycle": "2026-10-29", "tiles": 99}))
        self.assertEqual(state.read(self.root)["builds"], {"2026-10-29.1": {"cycle": "2026-10-29", "tiles": 5, "retired": None}})

    def test_a_state_that_cannot_be_read_is_said_and_never_written_over(self):
        for text in ("{nope", "[1, 2]"):
            (self.root / state.STATE).write_text(text)
            with self.assertRaises(state.StateError):
                state.read(self.root)
            with self.assertRaises(state.StateError):
                state.take_number(self.root, CYCLE)
            self.assertEqual((self.root / state.STATE).read_text(), text)
        (self.root / state.STATE).write_text(json.dumps({"numbers": {"2026-10-29": "x"}}))
        with self.assertRaisesRegex(state.StateError, "not a number"):
            state.take_number(self.root, CYCLE)
        self.assertEqual(state.read(Path(tempfile.mkdtemp()))["builds"], {})       # none yet is an empty state

    def test_a_builds_name_is_all_that_reaches_a_path(self):
        for bad in ("../2026-10-29.1", "2026-10-29.0", "2026-10-29.1/..", "2026-10-29", "", None, "2026-10-29.1234567"):
            with self.assertRaises(ValueError, msg=bad):
                state.check_name(bad)
        self.assertEqual(state.staging_dir(self.root, "2026-10-29.2"), self.root / ".staging-2026-10-29.2")
        self.assertEqual(state.incoming_dir(self.root, "2026-10-29.2"), self.root / ".incoming" / "2026-10-29.2")

    def test_the_newest_copy_a_listed_build_keeps(self):
        def keep(build_name, folder):
            path = self.root / "builds" / build_name / "sources" / folder
            path.mkdir(parents=True)
            (path / "x.geojson").write_text("{}")

        keep("2026-10-29.1", "2026-10-29")
        keep("2026-12-24.2", "2026-10-29")             # build 2 of December kept October's copy
        keep("2027-02-18.1", "2027-02-18")             # not listed: a folder alone is no build
        for name in ("2026-10-29.1", "2026-12-24.2"):
            state.register(self.root, name, {})
        self.assertEqual(state.holding(self.root, "x"), ("2026-12-24.2", self.root / "builds/2026-12-24.2/sources/2026-10-29"))
        self.assertIsNone(state.holding(self.root, "y"))

    def test_a_lock_is_one_holders_and_cleaning_waits_for_it(self):
        staging = state.staging_dir(self.root, "2026-10-29.1")
        (staging / "out").mkdir(parents=True)
        fd = locks.try_lock(staging / chart_job.LOCK)
        self.assertIsNotNone(fd)
        self.assertIsNone(locks.try_lock(staging / chart_job.LOCK))
        self.assertTrue(locks.held(staging / chart_job.LOCK))
        self.assertFalse(chart_job.clean(self.root, "2026-10-29.1"))       # held: deletes nothing, nor the lock file
        self.assertTrue((staging / "out").is_dir() and (staging / chart_job.LOCK).is_file())
        locks.release(fd)
        self.assertFalse(locks.held(staging / chart_job.LOCK))
        self.assertTrue(chart_job.clean(self.root, "2026-10-29.1"))
        self.assertFalse(staging.exists())
        self.assertFalse(locks.held(self.root / "never-made"))
        self.assertFalse((self.root / "never-made").exists())


class CatalogJobTests(unittest.TestCase):
    def test_the_cycle_in_effect_turns_at_0901z(self):
        self.assertEqual(faa.current_cycle(datetime(2026, 12, 24, 9, 0, tzinfo=timezone.utc)), CYCLE)
        self.assertEqual(faa.current_cycle(datetime(2026, 12, 24, 9, 1, tzinfo=timezone.utc)), NEXT)
        self.assertEqual(faa.next_cycle(datetime(2026, 11, 5, tzinfo=timezone.utc)), NEXT)

    def test_a_job_downloads_the_catalogs_zips_once_each_from_the_faa_alone(self):
        files = chart_job.files_for(CYCLE)
        self.assertEqual(len(files), len({(c.folder, c.zip) for c in catalog.CHARTS}))
        self.assertEqual(sorted(i for f in files for i in f["charts"]), sorted(c.id for c in catalog.CHARTS))
        for f in files:
            self.assertTrue(faa.allowed_url(f["url"]), f["url"])
            self.assertTrue(f["url"].startswith("https://aeronav.faa.gov/visual/10-29-2026/"))
            self.assertEqual((f["state"], f["size"], f["done"]), ("queued", None, 0))
        for bad in ("http://aeronav.faa.gov/visual/x.zip", "https://aeronav.faa.gov.evil/visual/x.zip",
                    "https://aeronav.faa.gov:8443/visual/x.zip", "https://u:p@aeronav.faa.gov/visual/x.zip",
                    "https://aeronav.faa.gov/other/x.zip", "https://aeronav.faa.gov/visual/../x.zip"):
            self.assertFalse(faa.allowed_url(bad), bad)

    def test_a_few_charts_only_on_a_developers_machine(self):
        self.assertEqual([c.id for c in chart_job.only({"CHART_BUILD_ONLY": "atlanta-sec, nonsense ,atlanta-tac"})],
                         ["atlanta-sec", "atlanta-tac"])
        self.assertIsNone(chart_job.only({"CHART_BUILD_ONLY": "nonsense"}))
        self.assertIsNone(chart_job.only({"CHART_BUILD_ONLY": "atlanta-sec", "APP_ENV": "production"}))
        self.assertIsNone(chart_job.only({}))
        self.assertEqual(chart_job.processes({}), 2)
        self.assertEqual([chart_job.processes({"CHART_BUILD_PROCESSES": v}) for v in ("0", "3", "99", "x")], [1, 3, 8, 2])


# --------------------------------------------------------------------------------------------- with a database


class ChartCase(unittest.TestCase):
    """A chart folder, a database in a file (so a build process and a second connection can share it), and the two test charts in
    place of the catalog's."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.root = self.tmp / "charts"
        self.root.mkdir()
        self.dbfile = self.tmp / "ezpz.db"
        Env(self, CHART_TILES_DIR=self.root, DATA_ADMIN="write", DATA_ADMIN_SAME_DISK="allow", DATA_ADMIN_RESERVE_GB="0",
            DATABASE_URL=f"sqlite:///{self.dbfile}", CHART_BUILD_ONLY="")
        for name in ("APP_ENV", "TRUSTED_PROXY", "FLY_APP_NAME"):
            if name in os.environ:
                patcher = patch.dict(os.environ)
                patcher.start()
                self.addCleanup(patcher.stop)
                del os.environ[name]
        self.zips, outlines = made()
        self.outlines = dict(outlines)
        for target, name, value in ((chart_job, "chart", CHARTS.__getitem__), (chart_job, "outline", lambda i: self.outlines[i]),
                                    (chart_job, "LOOK_EVERY_S", 0), (volumes, "stream_headroom", lambda *a, **k: 0)):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        with closing(sqlite3.connect(self.dbfile)) as c:
            c.execute("PRAGMA journal_mode=WAL")
        self.app = Flask(__name__, root_path=str(BACKEND_DIR))
        self.app.config.update(SQLALCHEMY_DATABASE_URI=f"sqlite:///{self.dbfile}", TESTING=True,
                               SECRET_KEY="test-only-admin-session-key")
        db.init_app(self.app)
        context = self.app.app_context()
        context.push()

        def close():
            db.session.remove()
            db.engine.dispose()
            context.pop()

        self.addCleanup(close)
        db.create_all()

    # -- helpers --------------------------------------------------------------------------------------------------

    def faa(self, *, missing=()):
        return FakeFaa({catalog.zip_url(cycle, c.folder, c.zip): self.zips[c.id]
                        for cycle in (CYCLE, NEXT) for c in (SEC, TAC) if c.id not in missing})

    def make_job(self, cycle=CYCLE, charts=(SEC, TAC), **fields):
        files = chart_job.files_for(cycle, list(charts))
        job = DataJob(id=jobs.new_id(), kind=chart_job.KIND, product=chart_job.PRODUCT, files=files, files_total=len(files),
                      bytes_total=0, build=state.take_number(self.root, cycle), stage=chart_job.STAGES[0],
                      requested_by_name="Admin")
        for key, value in fields.items():
            setattr(job, key, value)
        db.session.add(job)
        db.session.commit()
        return job.id

    def row(self, job_id):
        db.session.expire_all()
        job = db.session.get(DataJob, job_id)
        values = SimpleNamespace(**{k: getattr(job, k) for k in ROW}, job=job)
        db.session.commit()
        return values

    def snapshot(self, job_id):
        values = vars(self.row(job_id)).copy()
        values.pop("job")
        return values

    def started(self, job_id):
        """What ``watch`` writes before it starts a build process: a new token, and running."""
        token = os.urandom(8).hex()
        DataJob.query.filter_by(id=job_id).update({"token": token, "state": "running"})
        db.session.commit()
        return token

    def run_build(self, job_id, *, faa=None, token=None):
        token = token or self.started(job_id)
        return chart_job.run_child(job_id, token, http=faa or self.faa(), workers=1)

    def write(self, sql, *args):
        """Through a connection of its own, as another process writes."""
        with closing(sqlite3.connect(self.dbfile, timeout=10)) as c:
            c.execute(sql, args)
            c.commit()

    def leftovers(self, name):
        return [p for p in (state.staging_dir(self.root, name), state.incoming_dir(self.root, name)) if p.exists()]


class InlineChild:
    """The build process run here, at once: what ``watch`` starts, where a test need not start an interpreter."""

    pid = None

    def __init__(self, job_id, token, faa):
        self.code = chart_job.run_child(job_id, token, http=faa, workers=1)

    def poll(self):
        return self.code


class BuildJobTests(ChartCase):
    def test_a_build_from_its_first_stage_to_registered(self):
        fake = self.faa()
        job_id = self.make_job()
        chart_job.watch(job_id, start=lambda j, token, headroom: InlineChild(j, token, fake), poll=0.01, analysis=lambda: False)
        r = self.row(job_id)
        name = r.build
        self.assertEqual((r.state, r.stage, r.error, name), ("done", "finish", None, "2026-10-29.1"))
        self.assertEqual([f["state"] for f in r.files], ["done", "done"])
        self.assertEqual([f["size"] for f in r.files], [len(self.zips[SEC.id]), len(self.zips[TAC.id])])
        self.assertEqual(r.bytes_done, r.bytes_total)
        self.assertEqual({(how, url.rsplit("/", 1)[1], redirects) for how, url, redirects in fake.asked},
                         {(how, z, False) for how in ("HEAD", "GET") for z in ("Test.zip", "Test_TAC.zip")})

        folder = state.build_dir(self.root, name)
        self.assertEqual(sorted(p.name for p in folder.iterdir()), ["coverage.json", "report.json", "sources", "vfr.mbtiles"])
        self.assertEqual(sorted(p.name for p in (folder / "sources" / "2026-10-29").iterdir()),
                         ["Test.zip", "Test_TAC.zip", "test-sec.geojson", "test-tac.geojson"])
        self.assertEqual((folder / "sources/2026-10-29/Test.zip").read_bytes(), self.zips[SEC.id])
        self.assertEqual(self.leftovers(name), [])
        coverage = json.loads((folder / "coverage.json").read_text())
        report = json.loads((folder / "report.json").read_text())
        self.assertEqual((coverage["build"], coverage["cycle"], report["made_by"], report["held_back"]),
                         (name, "2026-10-29", "Admin", []))
        self.assertEqual(report["tiles"], coverage["tiles"])
        self.assertEqual(report["validate"]["refused"], [])
        with closing(sqlite3.connect(f"file:{folder / 'vfr.mbtiles'}?mode=ro", uri=True)) as mb:
            self.assertEqual(dict(mb.execute("SELECT name, value FROM metadata"))["version"], name)
            self.assertEqual(mb.execute("SELECT count(*) FROM tiles").fetchone()[0], sum(coverage["tiles"].values()))

        listed = state.read(self.root)
        self.assertEqual(listed["numbers"], {"2026-10-29": 1})
        info = listed["builds"][name]
        self.assertEqual((info["cycle"], info["job"], info["by"], info["held_back"], info["retired"]),
                         ("2026-10-29", job_id, "Admin", [], None))
        self.assertEqual(info["tiles"], sum(coverage["tiles"].values()))
        self.assertEqual((info["effective"], info["expires"]), (coverage["effective"], coverage["expires"]))

    def test_a_build_stopped_part_way_resumes_redoing_nothing_and_is_registered_at_the_next_look(self):
        job_id = self.make_job()
        name = self.row(job_id).build
        original_lower, original_tile = build._lower_one, build._tile_one
        calls = {"lower": 0, "tile": 0}

        def lower_then_gone(task):          # the API process goes after three tiles of the lower levels (a redeploy)
            calls["lower"] += 1
            if calls["lower"] > 3:
                raise chart_job.ParentGone()
            return original_lower(task)

        with patch.object(build, "_lower_one", lower_then_gone):
            self.assertEqual(self.run_build(job_id), chart_job.PARENT_GONE)
        r = self.row(job_id)
        self.assertEqual((r.state, r.stage, r.error, r.finished_at), ("running", "lower", None, None))   # for the next resume
        staging = state.staging_dir(self.root, name)
        self.assertFalse(locks.held(staging / chart_job.LOCK))
        self.assertTrue((staging / chart_job.OUT / "vfr.mbtiles").is_file())

        # The parent that was killed left its pause file: stale, it holds nothing (a wait would sleep, which this refuses).
        (staging / chart_job.PAUSE).touch()
        old = time.time() - 600
        os.utime(staging / chart_job.PAUSE, (old, old))

        def counting(kind, original):
            def fn(task):
                calls[kind] += 1
                return original(task)
            return fn

        calls.update(lower=0, tile=0)
        with patch.object(build, "_lower_one", counting("lower", original_lower)), \
                patch.object(build, "_tile_one", counting("tile", original_tile)), \
                patch.object(chart_job.time, "sleep", side_effect=AssertionError("the build waited")):
            self.assertEqual(self.run_build(job_id), chart_job.DONE)
        r = self.row(job_id)
        self.assertEqual((r.state, r.stage), ("done", "finish"))
        self.assertEqual(calls["tile"], 0)
        folder = state.build_dir(self.root, name)
        with closing(sqlite3.connect(f"file:{folder / 'vfr.mbtiles'}?mode=ro", uri=True)) as mb:
            held = {}
            for z, x, row in mb.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
                held.setdefault(z, set()).add((x, build.tms_row(z, row)))
        lower_levels = sum(len({(x // 2, y // 2) for x, y in held[z + 1]}) for z in range(build.LOWEST, build.TOP))
        self.assertEqual(calls["lower"] + 3, lower_levels)          # each lower tile made once, across the two runs
        self.assertEqual(self.leftovers(name), [])

        # Its parent was killed between the build's done and registering it: the next look at the tab registers it, once.
        self.assertNotIn(name, state.read(self.root)["builds"])
        runner = jobs.Runner()
        runner.inline = True
        runner.resume(self.app)
        listed = state.read(self.root)
        self.assertIn(name, listed["builds"])
        runner.resume(self.app)
        self.assertEqual(state.read(self.root), listed)
        self.assertFalse(chart_job.register(self.row(job_id).job, self.root))

    def test_a_cancel_stops_the_build_and_cleans_up(self):
        job_id = self.make_job()
        name = self.row(job_id).build
        original = build._tile_one

        def cancelled_at_the_first_tile(task):
            self.write("UPDATE data_job SET cancel_requested = 1 WHERE id = ?", job_id)
            return original(task)

        with patch.object(build, "_tile_one", cancelled_at_the_first_tile):
            self.assertEqual(self.run_build(job_id), chart_job.DONE)
        r = self.row(job_id)
        self.assertEqual((r.state, r.stage, r.error), ("cancelled", "tile", None))
        self.assertIsNotNone(r.finished_at)
        self.assertEqual(self.leftovers(name), [])
        self.assertFalse((self.root / "builds").exists())
        self.assertEqual(state.read(self.root)["builds"], {})
        self.assertEqual(state.read(self.root)["numbers"], {"2026-10-29": 1})        # its number is spent

        # cancelled before it ever started: nothing is started, and it is cancelled
        job_id = self.make_job(cancel_requested=True)
        chart_job.watch(job_id, start=lambda *a: self.fail("a build process was started"))
        self.assertEqual(self.row(job_id).state, "cancelled")

    def test_no_room_fails_the_build_in_words_and_cleans_up(self):
        cases = (({"DATA_ADMIN_RESERVE_GB": "1000000"}, None, "Not enough room in the chart folder"),
                 ({"DATA_ADMIN_SAME_DISK": ""}, True, "The chart folder is not a mounted volume here"))
        for env, container_disk, words in cases:
            with self.subTest(words), patch.dict(os.environ, env), \
                    patch.object(volumes, "on_container_disk", return_value=bool(container_disk)):
                job_id = self.make_job()
                self.assertEqual(self.run_build(job_id), chart_job.DONE)
                r = self.row(job_id)
                self.assertEqual((r.state, r.stage), ("failed", "sizes"))
                self.assertIn(words, r.error)
                self.assertNotIn(str(self.root), r.error)
                self.assertEqual(self.leftovers(r.build), [])

    def test_a_sectional_with_no_older_copy_refuses_the_build(self):
        moved = json.loads(json.dumps(self.outlines[SEC.id]))
        moved["properties"]["made_from"]["width"] += 1
        self.outlines[SEC.id] = moved
        job_id = self.make_job()
        self.assertEqual(self.run_build(job_id), chart_job.DONE)
        r = self.row(job_id)
        self.assertEqual((r.state, r.stage), ("failed", "check"))
        self.assertIn("The Test Sectional was refused (it is ", r.error)
        self.assertIn("no older build keeps a copy to stand in for it", r.error)
        self.assertEqual(self.leftovers(r.build), [])
        self.assertFalse((self.root / "builds").exists())

        self.outlines[SEC.id] = made()[1][SEC.id]          # and one the FAA would not give
        job_id = self.make_job()
        self.assertEqual(self.run_build(job_id, faa=self.faa(missing={SEC.id})), chart_job.DONE)
        r = self.row(job_id)
        self.assertEqual(r.state, "failed")
        self.assertIn("The Test Sectional was refused (The FAA answered 404 for this file)", r.error)
        self.assertEqual(r.files[0]["state"], "failed")

    def test_a_chart_held_back_is_stood_in_for_by_the_newest_copy_a_build_keeps(self):
        # October: the FAA has no TAC, and no build keeps one, so it is left out and the build goes on
        first = self.make_job(CYCLE)
        self.assertEqual(self.run_build(first, faa=self.faa(missing={TAC.id})), chart_job.DONE)
        r = self.row(first)
        self.assertEqual(r.state, "done", r.error)
        self.assertTrue(chart_job.register(r.job, self.root))
        report = json.loads((state.build_dir(self.root, r.build) / "report.json").read_text())
        self.assertEqual(report["held_back"], [{"id": TAC.id, "title": TAC.title, "reason": "The FAA answered 404 for this file",
                                                "kept": None}])
        self.assertIn("Test TAC: left out, The FAA answered 404 for this file; no older build keeps a copy of it.",
                      r.files[1]["reasons"])
        self.assertEqual([c["id"] for c in json.loads((state.build_dir(self.root, r.build) / "coverage.json").read_text())
                          ["charts"]], [SEC.id])

        # December: the FAA has no sectional; October's build keeps its copy, which stands in for it
        second = self.make_job(NEXT)
        self.assertEqual(self.run_build(second, faa=self.faa(missing={SEC.id})), chart_job.DONE)
        r2 = self.row(second)
        self.assertEqual(r2.state, "done", r2.error)
        self.assertTrue(chart_job.register(r2.job, self.root))
        folder = state.build_dir(self.root, r2.build)
        self.assertEqual(sorted(str(p.relative_to(folder / "sources")) for p in (folder / "sources").rglob("*") if p.is_file()),
                         ["2026-10-29/Test.zip", "2026-10-29/test-sec.geojson", "2026-12-24/Test_TAC.zip",
                          "2026-12-24/test-tac.geojson"])
        coverage = json.loads((folder / "coverage.json").read_text())
        self.assertEqual({c["id"]: c["kept"] for c in coverage["charts"]}, {SEC.id: True, TAC.id: False})
        held_back = state.read(self.root)["builds"][r2.build]["held_back"]
        self.assertEqual([(h["id"], h["kept"]) for h in held_back], [(SEC.id, "2026-10-29.1")])
        self.assertIn("Test Sectional: held back, The FAA answered 404 for this file; the copy in build 2026-10-29.1 stands in "
                      "for it.", r2.files[0]["reasons"])

    def test_the_api_never_writes_over_the_build_processes_progress(self):
        job_id = self.make_job()
        files = self.row(job_id).files
        files[0].update(size=10, done=7, state="downloading")
        writes = [{"stage": "download", "files": json.dumps(files), "bytes_done": 7, "bytes_total": 10},
                  {"stage": "tile", "work_done": 7, "work_total": 9},
                  {"state": "failed", "error": "The build did not validate: a tile is missing",
                   "finished_at": "2026-11-05 10:00:00.000000"}]

        class Child:      # a build process: it writes its row through a connection of its own, while its token is the row's
            pid = None

            def __init__(child, token):
                child.token, child.polls = token, 0

            def poll(child):
                if child.polls == len(writes):
                    return chart_job.DONE
                fields = writes[child.polls]
                child.polls += 1
                self.write(f"UPDATE data_job SET {', '.join(k + ' = ?' for k in fields)} WHERE id = ? AND token = ?",
                           *fields.values(), job_id, child.token)
                return None

        chart_job.watch(job_id, start=lambda j, token, headroom: Child(token), poll=0.01, analysis=lambda: False)
        r = self.row(job_id)
        self.assertEqual((r.state, r.stage, r.work_done, r.work_total, r.bytes_done, r.bytes_total),
                         ("failed", "tile", 7, 9, 7, 10))
        self.assertEqual(r.files, files)
        self.assertEqual(r.error, "The build did not validate: a tile is missing")

    def test_a_build_process_that_dies_with_no_end_state(self):
        killed = []
        job_id = self.make_job()
        lock = state.staging_dir(self.root, self.row(job_id).build) / chart_job.LOCK
        holder = {}

        class Dead:       # killed (out of memory, say): no end state written, perhaps a worker of it still holding the lock
            pid = None

            def __init__(child, hold):
                child.hold = hold

            def poll(child):
                if child.hold:
                    lock.parent.mkdir(parents=True, exist_ok=True)
                    holder["fd"] = locks.try_lock(lock)
                return -9

        with patch.object(chart_job, "_kill_group", killed.append), patch.object(chart_job, "LOCK_WAIT_S", 0):
            chart_job.watch(job_id, start=lambda *a: Dead(True), poll=0.01, analysis=lambda: False)
            r = self.row(job_id)
            self.assertEqual((r.state, r.error), ("running", None))      # never failed while anything holds its lock
            locks.release(holder.pop("fd"))
            chart_job.watch(job_id, start=lambda *a: Dead(False), poll=0.01, analysis=lambda: False)
        r = self.row(job_id)
        self.assertEqual(r.state, "failed")
        self.assertEqual(r.error, "The build process stopped without finishing; the server's log may say why.")
        self.assertEqual(len(killed), 2)                                # its group goes, whatever is left of it
        self.assertEqual(self.leftovers(r.build), [])

    def test_a_build_process_that_wrote_nothing_leaves_its_row_for_the_next_look(self):
        killed = []
        job_id = self.make_job()
        for code in (chart_job.BUSY, chart_job.PARENT_GONE, chart_job.REPLACED):
            child = SimpleNamespace(pid=None, poll=lambda code=code: code)
            with patch.object(chart_job, "_kill_group", killed.append):
                chart_job.watch(job_id, start=lambda *a: child, poll=0.01, analysis=lambda: False)
            r = self.row(job_id)
            self.assertEqual((r.state, r.error, r.finished_at), ("running", None, None), code)
        self.assertEqual(killed, [])                  # nothing of it is left to kill, and its lock was never taken
        self.assertTrue(chart_job.resumable(r.job))

    def test_a_build_process_whose_token_has_gone_or_whose_lock_is_held_writes_nothing(self):
        job_id = self.make_job()
        token = self.started(job_id)
        before = self.snapshot(job_id)
        with self.assertRaises(chart_job.Replaced):
            chart_job.Row(job_id, "not-the-token").update(stage="tile")
        with self.assertRaises(chart_job.Replaced):
            chart_job.Row(job_id, "not-the-token").cancel_requested()
        self.assertEqual(chart_job.run_child(job_id, "not-the-token", http=self.faa(), workers=1), chart_job.REPLACED)
        staging = state.staging_dir(self.root, before["build"])
        staging.mkdir()
        fd = locks.try_lock(staging / chart_job.LOCK)
        try:
            self.assertEqual(chart_job.run_child(job_id, token, http=self.faa(), workers=1), chart_job.BUSY)
            self.assertFalse(chart_job.resumable(self.row(job_id).job))
        finally:
            locks.release(fd)
        self.assertTrue(chart_job.resumable(self.row(job_id).job))
        self.assertEqual(self.snapshot(job_id), before)

    def test_a_pause_holds_only_while_it_is_fresh(self):
        pause = self.tmp / ".pause"
        self.assertFalse(build.pause_holds(str(pause)))
        pause.touch()
        self.assertTrue(build.pause_holds(str(pause)))
        old = time.time() - build.PAUSE_FRESH_S - 1
        os.utime(pause, (old, old))
        self.assertFalse(build.pause_holds(str(pause)))

        job_id = self.make_job()
        token = self.started(job_id)
        b = chart_job.Build(self.row(job_id).job, token, self.root)
        b.staging.mkdir()
        b.pause.touch()
        threading.Timer(1.2, b.pause.unlink).start()
        began = time.monotonic()
        b.wait()                                          # held while fresh, let go the moment it goes
        self.assertGreater(time.monotonic() - began, 1.0)
        b.pause.touch()
        os.utime(b.pause, (old, old))
        with patch.object(chart_job.time, "sleep", side_effect=AssertionError("a stale pause held the build")):
            b.wait()

        # a worker process between its tasks: a stale pause holds it no more than the build
        with patch.object(build, "_GUARD", (str(b.pause), os.getppid())), \
                patch.object(build.time, "sleep", side_effect=AssertionError("a stale pause held a worker")):
            build._guard()

        with self.assertRaises(chart_job.ParentGone):
            chart_job.Build(self.row(job_id).job, token, self.root, parent=-1).look(force=True)


# --------------------------------------------------------------------------------------------- the routes


class RouteTests(ChartCase):
    def setUp(self):
        super().setUp()
        from routes.admin_routes import admin_bp

        self.app.register_blueprint(admin_bp)
        admin = User(email="admin@example.com", name="Admin", role="admin", google_id="g-admin")
        db.session.add(admin)
        db.session.commit()
        self.dashboard = self.app.test_client()
        with self.dashboard.session_transaction() as session:
            session["admin_uid"] = admin.id
            session["csrf"] = "token"
        self.runner = jobs.Runner()
        self.runner.inline = True
        for target, name, value in ((jobs, "RUNNER", self.runner), (faa, "posted", lambda cycle, *a, **k: self.posted)):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.posted = True
        self.now = faa.current_cycle()

    def post(self, body, *, csrf="token"):
        return self.dashboard.post("/admin/data/charts/builds", json=body, headers={"X-CSRF": csrf})

    def panel(self):
        response = self.dashboard.get("/admin/data/charts.json")
        self.assertEqual(response.headers["Cache-Control"], "private, no-store")
        return response.get_json()

    def test_the_panel(self):
        body = self.panel()
        self.assertEqual((body["configured"], body["can_write"], body["refusal"], body["room"], body["builds"]),
                         (True, True, None, None, []))
        self.assertEqual([(c["which"], c["cycle"], c["posted"], c["built"]) for c in body["cycles"]],
                         [("current", self.now.isoformat(), True, []),
                          ("next", (self.now + timedelta(days=56)).isoformat(), True, [])])
        self.assertTrue(body["cycles"][0]["effective"].endswith("T09:01Z"))
        state.register(self.root, f"{self.now}.1", {"cycle": self.now.isoformat(), "tiles": 12, "held_back": [
            {"id": "x", "title": "X TAC", "reason": "r", "kept": None}]})
        body = self.panel()
        self.assertEqual([(b["name"], b["tiles"], b["held_back"]) for b in body["builds"]],
                         [(f"{self.now}.1", 12, [{"title": "X TAC", "reason": "r", "kept": None}])])
        self.assertEqual(body["cycles"][0]["built"], [f"{self.now}.1"])
        self.assertNotIn(str(self.root), json.dumps(body))
        page = self.dashboard.get("/admin/data").get_data(as_text=True)
        self.assertIn("VFR charts", page)
        self.assertIn("charts.json", page)

    def test_a_build_is_the_catalogs_and_one_job_at_a_time(self):
        created = self.post({"cycle": self.now.isoformat()})
        self.assertEqual(created.status_code, 202, created.get_data(as_text=True))
        made_job = created.get_json()["job"]
        self.assertEqual((made_job["kind"], made_job["build"], made_job["stage"], made_job["state"]),
                         ("chart_build", f"{self.now}.1", "sizes", "queued"))
        self.assertEqual(made_job["files_total"], len({(c.folder, c.zip) for c in catalog.CHARTS}))
        self.assertEqual(state.read(self.root)["numbers"], {self.now.isoformat(): 1})
        self.assertTrue(self.runner.knows(made_job["id"]))
        busy = self.post({"cycle": self.now.isoformat()})
        self.assertEqual((busy.status_code, busy.get_json()["error"]), (409, "Another job is under way. Wait for it, or cancel it."))
        listed = self.dashboard.get("/admin/data/jobs.json").get_json()["jobs"][0]
        self.assertEqual((listed["build"], listed["stage"], listed["work_done"]), (f"{self.now}.1", "sizes", None))

        # the job gone (the number stays spent), and a developer's few charts
        DataJob.query.delete()
        db.session.commit()
        with patch.dict(os.environ, {"CHART_BUILD_ONLY": "atlanta-sec"}):
            again = self.post({"cycle": (self.now + timedelta(days=56)).isoformat()}).get_json()["job"]
        self.assertEqual([f["name"] for f in again["files"]], [catalog.BY_ID["atlanta-sec"].zip])
        DataJob.query.delete()
        db.session.commit()
        self.assertEqual(self.post({"cycle": self.now.isoformat()}).get_json()["job"]["build"], f"{self.now}.2")

    def test_refused_on_the_containers_disk_and_below_the_reserve(self):
        cases = (({"DATA_ADMIN_SAME_DISK": ""}, True, "The chart folder is not a mounted volume here"),
                 ({"DATA_ADMIN_RESERVE_GB": "1000000"}, False, "Not enough room in the chart folder"))
        for env, container_disk, words in cases:
            with self.subTest(words), patch.dict(os.environ, env), \
                    patch.object(volumes, "on_container_disk", return_value=container_disk):
                refused = self.post({"cycle": self.now.isoformat()})
                self.assertEqual(refused.status_code, 400)
                self.assertIn(words, refused.get_json()["error"])
                self.assertIn(words, self.panel()["room"])
        self.assertEqual(DataJob.query.count(), 0)
        self.assertFalse((self.root / state.STATE).exists())            # no number taken

    def test_only_a_posted_cycle_the_current_or_the_next(self):
        for body, words in (({"cycle": "2026-09-03"}, "Only the current cycle or the next"),
                            ({"cycle": "nonsense"}, "Only the current cycle or the next"), ({}, "Only the current")):
            self.assertIn(words, self.post(body).get_json()["error"])
        self.posted = False
        self.assertIn("has not posted the charts of", self.post({"cycle": self.now.isoformat()}).get_json()["error"])
        self.posted = None
        self.assertIn("could not be asked", self.post({"cycle": self.now.isoformat()}).get_json()["error"])
        self.posted = True
        self.assertEqual(self.post({"cycle": self.now.isoformat()}, csrf="wrong").status_code, 400)
        with patch.dict(os.environ, {"DATA_ADMIN": "read"}):
            self.assertEqual(self.post({"cycle": self.now.isoformat()}).status_code, 403)
        with patch.dict(os.environ, {"CHART_TILES_DIR": ""}):
            self.assertEqual(self.panel()["refusal"], "CHART_TILES_DIR is not set on this server.")
            self.assertEqual(self.post({"cycle": self.now.isoformat()}).get_json()["error"],
                             "CHART_TILES_DIR is not set on this server.")
        self.assertEqual(DataJob.query.count(), 0)


# --------------------------------------------------------------------------------------------- real build processes


class ProcessTests(ChartCase):
    """``python -m charts.job``, started as the API starts it: a fresh interpreter in a process group of its own."""

    def ready_job(self):
        """A job whose zips are down, at its unzip stage: its process goes no further than the pause file it finds there."""
        job_id = self.make_job()
        files = self.row(job_id).files
        for f in files:
            f.update(size=100, done=100, state="downloaded")
        DataJob.query.filter_by(id=job_id).update({"files": files, "stage": "unzip"})
        db.session.commit()
        return job_id

    def test_a_resume_while_the_old_process_holds_the_lock_starts_nothing_and_one_after_starts_again(self):
        job_id = self.ready_job()
        name = self.row(job_id).build
        staging = state.staging_dir(self.root, name)
        lock = staging / chart_job.LOCK
        staging.mkdir()
        (staging / chart_job.PAUSE).touch()                # crews wait on an analysis: it holds where it is
        old_token = self.started(job_id)
        old = chart_job._start(job_id, old_token, 0)       # the build process of a container about to go
        self.addCleanup(lambda: old.poll() is None and os.killpg(old.pid, signal.SIGKILL))
        until(lambda: locks.held(lock) and self.row(job_id).stage == "unzip", what="the old build process")
        before = self.snapshot(job_id)

        # The new container looks: the lock is held, so it writes nothing and starts nothing ...
        self.assertFalse(chart_job.resumable(self.row(job_id).job))
        runner = jobs.Runner()
        runner.inline = True
        runner.resume(self.app)
        self.assertFalse(runner.knows(job_id))
        chart_job.watch(job_id, start=lambda *a: self.fail("a second build process was started"))
        # ... and a second build process all the same (a race between two looks) cannot take the lock: it writes nothing.
        second = chart_job._start(job_id, old_token, 0)
        self.assertEqual(second.wait(60), chart_job.BUSY)
        self.assertEqual(self.snapshot(job_id), before)
        self.assertIsNone(old.poll())

        # The old container goes, its build process with it; the next look starts the build again, once.
        os.killpg(old.pid, signal.SIGKILL)
        old.wait(30)
        until(lambda: not locks.held(lock), what="the lock to be let go")
        self.assertEqual(self.snapshot(job_id), before)
        self.assertTrue(chart_job.resumable(self.row(job_id).job))
        started, failures = [], []

        def start(*args):
            started.append(chart_job._start(*args))
            return started[-1]

        def resume():
            with self.app.app_context():
                try:
                    chart_job.watch(job_id, start=start, poll=0.2, analysis=lambda: True)
                except Exception as error:  # noqa: BLE001
                    failures.append(error)
                finally:
                    db.session.remove()

        watcher = threading.Thread(target=resume)
        watcher.start()
        until(lambda: locks.held(lock) and self.row(job_id).token not in (None, old_token), what="the new build process")
        self.assertEqual(self.row(job_id).state, "running")
        self.write("UPDATE data_job SET cancel_requested = 1 WHERE id = ?", job_id)
        watcher.join(90)
        self.assertFalse(watcher.is_alive())
        self.assertEqual(failures, [])
        self.assertEqual(len(started), 1)
        self.assertEqual(started[0].returncode, chart_job.DONE)
        r = self.row(job_id)
        self.assertEqual((r.state, r.error), ("cancelled", None))      # never failed
        self.assertEqual(self.leftovers(name), [])

    def test_the_build_process_opens_the_database_alone_imports_no_torch_and_stops_without_its_parent(self):
        job_id = self.make_job()
        token = self.started(job_id)
        probe = textwrap.dedent("""
            import json, runpy, sys
            try:
                runpy.run_module("charts.job", run_name="__main__", alter_sys=True)
                code = 0
            except SystemExit as stop:
                code = stop.code
            heavy = sorted(m for m in ("torch", "ultralytics", "terrain_tiles", "terrain_provider", "app", "routes", "lidar",
                                       "cv2", "scipy") if m in sys.modules)
            print(json.dumps({"code": code, "heavy": heavy}))
        """)

        def run(*args):
            done = subprocess.run([sys.executable, "-c", probe, job_id, *args], cwd=BACKEND_DIR, capture_output=True, text=True,
                                  timeout=120)
            self.assertEqual(done.returncode, 0, done.stderr)
            return json.loads(done.stdout.strip().splitlines()[-1])

        before = self.snapshot(job_id)
        self.assertEqual(run(token, "--parent", "1"), {"code": chart_job.PARENT_GONE, "heavy": []})
        self.assertEqual(self.snapshot(job_id), before)
        with patch.dict(os.environ, {"CHART_TILES_DIR": str(self.tmp / "not-mounted")}):
            self.assertEqual(run(token), {"code": chart_job.DONE, "heavy": []})
        r = self.row(job_id)
        self.assertEqual((r.state, r.error), ("failed", "The chart folder is not there: is its volume mounted?"))


if __name__ == "__main__":
    unittest.main()
