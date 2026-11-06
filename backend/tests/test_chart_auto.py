"""The VFR charts after a build (docs/VFR_CHARTS.md §5.5–5.7; C2): ``state.json``'s actions and ``effective``, the Charts card's
routes, and the automatic cycle.

In CI's list (``.github/workflows/backend.yaml``): it imports neither ``app.py`` nor ``terrain_tiles``. The clock is a fake
(``state.now_utc`` and every ``now`` passed), the FAA's "is it posted" is a table, and a build is stood in for: ``chart_job.watch`` is
replaced by one that leaves what a build process leaves (its folder and its row done, failed, out of room, cancelled or still running),
and registers it as the parent does. C1's tests run real builds.
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
for _path in (BACKEND_DIR, BACKEND_DIR / "tests"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from charts import auto, catalog, faa, job as chart_job, locks, state  # noqa: E402
from data_admin import jobs  # noqa: E402
from models import DataJob, User, db  # noqa: E402
from test_chart_jobs import ChartCase, Env  # noqa: E402

OCT = date(2026, 10, 29)
DEC = date(2026, 12, 24)
FEB = date(2027, 2, 18)
SEP = date(2026, 9, 3)
AUTO = state.AUTOMATIC


def at(text):
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


def info(cycle, by=AUTO, **more):
    """What ``state.json`` lists of a finished build of ``cycle``."""
    return {"cycle": cycle.isoformat(), "effective": catalog.effective_instant(cycle).strftime("%Y-%m-%dT%H:%MZ"),
            "expires": catalog.cycle_ends(cycle).strftime("%Y-%m-%dT%H:%MZ"), "made": "2026-01-01T00:00:00Z", "by": by,
            "bytes": 10, "tiles": 4, "held_back": [], **more}


def put(root, name, by=AUTO, *, folder=True, now=None):
    """A finished build of ``name``'s cycle, listed, with its folder."""
    cycle = date.fromisoformat(name.split(".")[0])
    if folder:
        (state.build_dir(root, name)).mkdir(parents=True, exist_ok=True)
        (state.build_dir(root, name) / "tiles.mbtiles").write_bytes(b"x")
    state.update(root, lambda s: s["numbers"].__setitem__(cycle.isoformat(),
                                                          max(int(s["numbers"].get(cycle.isoformat()) or 0),
                                                              int(name.split(".")[1]))), now)
    state.register(root, name, info(cycle, by), now=now)


# --------------------------------------------------------------------------------------------- §5.5, with no database


class ActionTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)

    def held(self):
        return state.read(self.root)

    def test_effective_is_pure_and_applies_a_switch_once_its_time_has_passed(self):
        before = {"live": "2026-10-29.1", "previous": None, "builds": {"2026-10-29.1": {}, "2026-12-24.1": {}},
                  "next": {"build": "2026-12-24.1", "at": "2026-12-24T09:01:00Z", "by": "Ann"}}
        kept = json.loads(json.dumps(before))
        self.assertIs(state.effective(before, at("2026-12-24T09:00:59")), before)
        after = state.effective(before, at("2026-12-24T09:01:00"))
        self.assertEqual((after["live"], after["previous"], after["next"]), ("2026-12-24.1", "2026-10-29.1", None))
        self.assertEqual(before, kept)          # never changed
        # next naming a build not listed, or the live one, or a time that cannot be read: nothing to switch to
        for nxt in ({"build": "2027-02-18.1", "at": "2026-12-24T09:01:00Z"}, {"build": "2026-10-29.1", "at": "2026-12-24T09:01:00Z"},
                    {"build": "2026-12-24.1", "at": "soon"}):
            gone = state.effective({**before, "next": nxt}, at("2026-12-25T00:00:00"))
            self.assertEqual((gone["live"], gone["next"]), ("2026-10-29.1", None), nxt)

    def test_a_scheduled_switch_is_seen_by_every_reader_and_written_only_by_the_next_write(self):
        put(self.root, "2026-10-29.1")
        put(self.root, "2026-12-24.1")
        state.switch(self.root, "2026-10-29.1", "Ann", at("2026-11-01T00:00:00"))
        self.assertEqual(state.schedule(self.root, "2026-12-24.1", "Ann", at("2026-12-01T00:00:00")), "2026-12-24T09:01:00Z")
        written = (self.root / state.STATE).stat().st_mtime_ns, (self.root / state.STATE).read_bytes()
        self.assertEqual(state.serving(self.root, at("2026-12-24T09:00:00"))["live"], "2026-10-29.1")
        seen = state.serving(self.root, at("2026-12-24T09:01:00"))
        self.assertEqual((seen["live"], seen["previous"], seen["next"]), ("2026-12-24.1", "2026-10-29.1", None))
        self.assertEqual(((self.root / state.STATE).stat().st_mtime_ns, (self.root / state.STATE).read_bytes()), written)
        # the next write writes it down, at its time and by whom it was scheduled
        state.update(self.root, lambda s: None, at("2026-12-26T00:00:00"))
        held = self.held()
        self.assertEqual((held["live"], held["previous"], held["next"]), ("2026-12-24.1", "2026-10-29.1", None))
        self.assertEqual(held["history"][-1], {"did": "switch", "how": "scheduled", "from": "2026-10-29.1", "to": "2026-12-24.1",
                                               "by": "Ann", "at": "2026-12-24T09:01:00Z"})

    def test_switch_clears_next_and_roll_back_keeps_it(self):
        for name in ("2026-09-03.1", "2026-10-29.1", "2026-10-29.2", "2026-12-24.1"):
            put(self.root, name)
        now = at("2026-12-01T00:00:00")
        state.switch(self.root, "2026-09-03.1", "Ann", now)
        state.switch(self.root, "2026-10-29.1", "Ann", now)
        state.schedule(self.root, "2026-12-24.1", "Ann", now)
        state.switch(self.root, "2026-10-29.2", "Bob", now)
        held = self.held()
        self.assertEqual((held["live"], held["previous"], held["next"]), ("2026-10-29.2", "2026-10-29.1", None))
        self.assertEqual(held["history"][-1]["cleared"], "2026-12-24.1")
        self.assertEqual(held["builds"]["2026-09-03.1"]["retired"], "2026-12-01T00:00:00Z")      # pushed out of previous
        state.schedule(self.root, "2026-12-24.1", "Ann", now)
        state.roll_back(self.root, "Bob", now)
        held = self.held()
        self.assertEqual((held["live"], held["previous"], held["next"]["build"]), ("2026-10-29.1", "2026-10-29.2", "2026-12-24.1"))
        state.roll_back(self.root, "Bob", now)          # a roll back undone by another
        self.assertEqual((self.held()["live"], self.held()["previous"]), ("2026-10-29.2", "2026-10-29.1"))
        self.assertEqual([h["did"] for h in self.held()["history"]],
                         ["switch", "switch", "schedule", "switch", "schedule", "roll back", "roll back"])

    def test_what_is_refused_and_says_why(self):
        put(self.root, "2026-10-29.1")
        put(self.root, "2026-12-24.1")
        now = at("2026-12-01T00:00:00")
        refusals = (
            (lambda: state.switch(self.root, "2026-12-24.1", "Ann", now), "not in effect until 2026-12-24T09:01:00Z"),
            (lambda: state.schedule(self.root, "2026-10-29.1", "Ann", now), "in effect already"),
            (lambda: state.switch(self.root, "2027-02-18.1", "Ann", now), "not a build the chart folder holds"),
            (lambda: state.roll_back(self.root, "Ann", now), "no previous build"),
            (lambda: state.cancel_schedule(self.root, "Ann", now), "No switch is scheduled"),
        )
        for act, words in refusals:
            with self.subTest(words), self.assertRaisesRegex(state.Refused, words):
                act()
        self.assertFalse((self.root / state.STATE).read_text().count('"history": [\n  {'))     # nothing was recorded
        state.switch(self.root, "2026-10-29.1", "Ann", now)
        with self.assertRaisesRegex(state.Refused, "live already"):
            state.switch(self.root, "2026-10-29.1", "Ann", now)
        state.schedule(self.root, "2026-12-24.1", "Ann", now)
        for build, words in (("2026-10-29.1", "is live"), ("2026-12-24.1", "scheduled")):
            with self.assertRaisesRegex(state.Refused, words):
                state.delete_build(self.root, build, "Ann", now)
        # cancelled after its time: refused, and the switch it made is written down all the same
        with self.assertRaisesRegex(state.Refused, "roll back undoes it"):
            state.cancel_schedule(self.root, "Ann", at("2026-12-24T09:01:00"))
        held = self.held()
        self.assertEqual((held["live"], held["next"]), ("2026-12-24.1", None))
        with self.assertRaisesRegex(state.Refused, "previous build"):
            state.delete_build(self.root, "2026-10-29.1", "Ann", at("2026-12-24T10:00:00"))
        self.assertTrue(state.build_dir(self.root, "2026-10-29.1").is_dir())

    def test_a_build_retired_a_day_ago_is_deleted_at_the_next_write_and_one_never_switched_to_is_kept(self):
        for name in ("2026-09-03.1", "2026-10-29.1", "2026-10-29.2", "2026-12-24.1", "2026-12-24.2"):
            put(self.root, name)
        state.switch(self.root, "2026-09-03.1", "Ann", at("2026-11-01T00:00:00"))
        state.switch(self.root, "2026-10-29.1", "Ann", at("2026-11-01T00:00:00"))
        state.schedule(self.root, "2026-12-24.1", "Ann", at("2026-12-01T00:00:00"))
        # written down late: September's build is retired at the switch's time, 0901Z, not at the write's
        state.update(self.root, lambda s: None, at("2026-12-25T09:00:00"))
        held = self.held()
        self.assertEqual(held["builds"]["2026-09-03.1"]["retired"], "2026-12-24T09:01:00Z")
        self.assertIn("2026-09-03.1", held["builds"])                                    # a day is not yet over
        state.update(self.root, lambda s: None, at("2026-12-25T09:01:01"))
        held = self.held()
        self.assertNotIn("2026-09-03.1", held["builds"])
        self.assertFalse(state.build_dir(self.root, "2026-09-03.1").exists())
        self.assertEqual(held["history"][-1], {"did": "delete", "build": "2026-09-03.1", "why": "retired", "by": AUTO,
                                               "at": "2026-12-25T09:01:01Z"})
        self.assertEqual(sorted(held["builds"]), ["2026-10-29.1", "2026-10-29.2", "2026-12-24.1", "2026-12-24.2"])
        # a year on, the builds never switched to are still there
        state.update(self.root, lambda s: None, at("2027-12-01T00:00:00"))
        self.assertIn("2026-10-29.2", self.held()["builds"])
        self.assertIn("2026-12-24.2", self.held()["builds"])
        self.assertEqual(sorted(p.name for p in (self.root / "builds").iterdir()), sorted(self.held()["builds"]))

    def test_a_build_switched_back_to_within_its_day_stays_and_stays_live(self):
        for name in ("2026-10-29.1", "2026-10-29.2", "2026-10-29.3"):
            put(self.root, name)
        now = at("2026-11-01T00:00:00")
        for name in ("2026-10-29.1", "2026-10-29.2", "2026-10-29.3"):
            state.switch(self.root, name, "Ann", now)
        self.assertEqual(self.held()["builds"]["2026-10-29.1"]["retired"], "2026-11-01T00:00:00Z")
        state.switch(self.root, "2026-10-29.1", "Ann", now + timedelta(hours=20))        # back to it within its day
        self.assertIsNone(self.held()["builds"]["2026-10-29.1"]["retired"])
        state.update(self.root, lambda s: None, now + timedelta(days=3))
        held = self.held()
        self.assertEqual(held["live"], "2026-10-29.1")
        self.assertTrue(state.build_dir(self.root, "2026-10-29.1").is_dir())
        self.assertEqual(held["previous"], "2026-10-29.3")
        self.assertNotIn("2026-10-29.2", held["builds"])          # pushed out by the switch back, and its day over

    def test_a_build_live_previous_or_next_is_never_deleted_whatever_its_retired_stamp_says(self):
        for name in ("2026-10-29.1", "2026-10-29.2", "2026-12-24.1"):
            put(self.root, name)
        now = at("2026-11-01T00:00:00")
        state.switch(self.root, "2026-10-29.1", "Ann", now)
        state.switch(self.root, "2026-10-29.2", "Ann", now)
        state.schedule(self.root, "2026-12-24.1", "Ann", now)

        def stamp(s):                   # a file written by hand, or by a version that got it wrong
            for info in s["builds"].values():
                info["retired"] = "2026-10-01T00:00:00Z"

        state.update(self.root, stamp, now)
        state.update(self.root, lambda s: None, now + timedelta(days=3))
        held = self.held()
        self.assertEqual(sorted(held["builds"]), ["2026-10-29.1", "2026-10-29.2", "2026-12-24.1"])
        self.assertEqual(sorted(p.name for p in (self.root / "builds").iterdir()), sorted(held["builds"]))

    def test_delete_then_rebuild_gives_a_new_number_and_a_deleted_build_is_never_listed_again(self):
        name = state.take_number(self.root, OCT)
        put(self.root, name)
        state.delete_build(self.root, name, "Ann", at("2026-11-01T00:00:00"))
        self.assertFalse(state.build_dir(self.root, name).exists())
        held = self.held()
        self.assertEqual((held["builds"], held["gone"]), ({}, [name]))
        self.assertEqual(held["history"][-1], {"did": "delete", "build": name, "by": "Ann", "at": "2026-11-01T00:00:00Z"})
        self.assertFalse(state.register(self.root, name, info(OCT)))           # a parent registering it late
        self.assertEqual(self.held()["builds"], {})
        self.assertEqual(state.take_number(self.root, OCT), "2026-10-29.2")

    def test_two_writers_at_once_both_land(self):
        put(self.root, "2026-10-29.1")
        put(self.root, "2026-12-24.1")
        now = at("2026-12-01T00:00:00")
        errors = []

        def run(act):
            try:
                for _ in range(10):
                    act()
            except Exception as error:  # noqa: BLE001
                errors.append(error)

        acts = (lambda: state.take_number(self.root, DEC),
                lambda: state.schedule(self.root, "2026-12-24.1", "Ann", now),
                lambda: state.switch(self.root, "2026-10-29.1", "Bob", now) if self.held()["live"] is None else None)
        threads = [threading.Thread(target=run, args=(a,)) for a in acts]
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        self.assertEqual(errors, [])
        held = self.held()
        self.assertEqual(held["numbers"]["2026-12-24"], 11)
        self.assertEqual((held["live"], held["next"]["build"]), ("2026-10-29.1", "2026-12-24.1"))
        self.assertEqual([h["did"] for h in held["history"]].count("schedule"), 10)

    def test_folders_go_after_the_write_through_the_trash(self):
        put(self.root, "2026-10-29.1")
        with patch.object(shutil, "rmtree") as removed:
            state.delete_build(self.root, "2026-10-29.1", "Ann")
        aside = Path(removed.call_args[0][0])
        self.assertEqual(aside.parent, self.root / state.TRASH)
        self.assertTrue(aside.name.startswith("2026-10-29.1."))
        self.assertTrue((aside / "tiles.mbtiles").is_file())          # whole, moved in one rename
        self.assertFalse(state.build_dir(self.root, "2026-10-29.1").exists())


# --------------------------------------------------------------------------------------------- the automatic cycle (§5.7)


class AutoCase(ChartCase):
    """A chart folder, a database, a clock, the FAA's answers and an inbox. A look is ``self.look()``; the builds it starts run
    when ``self.builds_run()`` says so, each as ``self.outcomes`` says (done unless told)."""

    def setUp(self):
        super().setUp()
        Env(self, CHART_AUTO="on", CHART_NOTIFY_EMAIL="owner@example.com")
        self.clock = at("2026-12-03T12:00:00")
        self.posted = {OCT: True, DEC: True}
        self.sent, self.outcomes = [], []
        self.runner = jobs.Runner()
        self.runner.inline = True
        for target, name, value in (
                (jobs, "RUNNER", self.runner), (state, "now_utc", lambda: self.clock),
                (faa, "posted", lambda cycle, *a, **k: self.posted.get(cycle, False)),
                (chart_job, "watch", self.fake_watch), (chart_job, "CONUS_ESTIMATE_BYTES", 1024),
                (auto, "_wake", threading.Event())):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        import email_service

        patcher = patch.object(email_service, "send_email",
                               lambda to, subject, text, html_body: self.sent.append((to, subject, text)) or True)
        patcher.start()
        self.addCleanup(patcher.stop)

    # -- helpers ------------------------------------------------------------------------------------------------

    def look(self, **kw):
        return auto.look(self.app, now=self.clock, **kw)

    def later(self, **delta):
        self.clock += timedelta(**delta)

    def held(self):
        return state.read(self.root)

    def record(self, cycle):
        return self.held()["auto"].get(cycle.isoformat(), {})

    def chart_jobs(self):
        db.session.expire_all()
        rows = DataJob.query.filter_by(kind=chart_job.KIND).order_by(DataJob.created_at).all()
        db.session.commit()
        return rows

    def subjects(self):
        return [subject for _, subject, _ in self.sent]

    def fake_watch(self, job_id):
        """What a build process and its parent leave, as ``self.outcomes`` says."""
        job = db.session.get(DataJob, job_id)
        outcome = self.outcomes.pop(0) if self.outcomes else "done"
        cycle = chart_job.cycle_of(job)
        if outcome in ("done", "killed"):
            folder = state.build_dir(self.root, job.build)
            folder.mkdir(parents=True)
            (folder / "coverage.json").write_text(json.dumps({**info(cycle), "tiles": {"5": 3, "6": 7}}))
            (folder / "report.json").write_text(json.dumps({"held_back": [{"id": "x", "title": "X TAC", "reason": "late"}]}))
            job.state = "done"
        elif outcome == "failed":
            job.state, job.stage, job.error = "failed", "download", "The FAA's server stopped answering."
        elif outcome == "room":
            job.state, job.stage, job.error = "failed", chart_job.ROOM_STAGE, "Not enough room in the chart folder."
        elif outcome == "cancelled":
            job.state = "cancelled"
        elif outcome == "running":
            job.state = "running"
            db.session.commit()
            return
        job.finished_at = datetime.utcnow()
        db.session.commit()
        if outcome == "done":
            chart_job.register(job, self.root)

    def builds_run(self, *outcomes):
        self.outcomes.extend(outcomes)
        self.runner.run_pending(self.app)

    def live_build(self, name, by=AUTO):
        put(self.root, name, by)
        state.switch(self.root, name, by, max(self.clock, catalog.effective_instant(date.fromisoformat(name.split(".")[0]))))


class AutoTests(AutoCase):
    def test_a_posted_cycle_is_built_and_scheduled_and_said_once(self):
        self.live_build("2026-10-29.1")
        self.assertEqual(self.look(), auto.LOOK_EVERY_S)
        [row] = self.chart_jobs()
        self.assertEqual((row.build, row.requested_by, row.requested_by_name, row.state), ("2026-12-24.1", None, AUTO, "queued"))
        record = self.record(DEC)
        self.assertEqual((record["started"], record["job_id"], record["tries"], record["last_try"]),
                         ("2026-12-24.1", row.id, 1, "2026-12-03T12:00:00Z"))
        self.builds_run()
        held = self.held()
        self.assertEqual(held["next"], {"build": "2026-12-24.1", "at": "2026-12-24T09:01:00Z", "by": AUTO})
        self.assertEqual(held["history"][-1], {"did": "schedule", "to": "2026-12-24.1", "when": "2026-12-24T09:01:00Z",
                                               "by": AUTO, "at": "2026-12-03T12:00:00Z"})
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24 built as 2026-12-24.1"])
        to, _, text = self.sent[0]
        self.assertEqual(to, "owner@example.com")
        self.assertIn("scheduled to go live at 24 Dec 2026 0901Z", text)
        self.assertIn("X TAC: late", text)
        for _ in range(3):              # built once, said once
            self.later(hours=6)
            self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(len(self.sent), 1)
        # its time comes: it is served, and written down by the next look
        self.clock = at("2026-12-24T09:01:00")
        self.assertEqual(state.serving(self.root, self.clock)["live"], "2026-12-24.1")
        self.look()
        self.assertEqual((self.held()["live"], self.held()["previous"]), ("2026-12-24.1", "2026-10-29.1"))
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(self.held()["looks"]["last"], "2026-12-24T09:01:00Z")

    def test_a_current_cycle_with_nothing_live_is_built_and_switched_to(self):
        self.clock = at("2026-11-05T12:00:00")
        self.posted = {OCT: True, DEC: False}
        self.look()
        self.builds_run()
        held = self.held()
        self.assertEqual((held["live"], held["previous"], held["next"]), ("2026-10-29.1", None, None))
        self.assertEqual(held["history"][-1], {"did": "switch", "from": None, "to": "2026-10-29.1", "by": AUTO,
                                               "at": "2026-11-05T12:00:00Z"})
        self.assertIn("It is live now", self.sent[0][2])

    def test_a_late_current_cycle_is_switched_to_over_an_older_live_build(self):
        self.clock = at("2026-11-05T12:00:00")
        self.posted = {OCT: True, DEC: False}
        self.live_build("2026-09-03.1")
        self.look()
        self.builds_run()
        self.assertEqual((self.held()["live"], self.held()["previous"]), ("2026-10-29.1", "2026-09-03.1"))

    def test_a_deleted_build_or_a_cancelled_schedule_is_not_made_again(self):
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run()
        state.cancel_schedule(self.root, "Ann", self.clock)
        self.assertEqual(self.record(DEC)["dropped"], {"what": "schedule", "by": "Ann", "at": "2026-12-03T12:00:00Z"})
        state.delete_build(self.root, "2026-12-24.1", "Ann", self.clock)
        for _ in range(3):
            self.later(hours=7)
            self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertIsNone(self.held()["next"])
        self.assertEqual(self.held()["numbers"]["2026-12-24"], 1)
        self.assertIn("Left to an admin", auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"])

    def test_a_build_the_admin_deleted_is_not_made_again(self):
        self.clock = at("2026-11-05T12:00:00")
        self.posted = {OCT: True, DEC: False}
        self.live_build("2026-09-03.1")
        self.outcomes.append("done")
        self.look()
        self.builds_run()
        state.roll_back(self.root, "Ann", self.clock)                 # back to September: October is previous now
        put(self.root, "2026-09-03.2", "Ann")
        state.switch(self.root, "2026-09-03.2", "Ann", self.clock)    # October pushed out of previous
        state.delete_build(self.root, "2026-10-29.1", "Ann", self.clock)
        self.later(hours=7)
        self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(self.record(OCT)["dropped"]["what"], "build")

    def test_an_admins_build_of_the_cycle_leaves_it_to_them(self):
        self.clock = at("2026-11-05T12:00:00")
        self.posted = {OCT: True, DEC: False}
        put(self.root, "2026-10-29.1", "Ann")           # made by an admin (C1's way, recorded by the next look)
        self.look()
        self.assertEqual(self.chart_jobs(), [])
        held = self.held()
        self.assertEqual((held["live"], held["next"]), (None, None))
        self.assertEqual(self.record(OCT)["admin_built"]["build"], "2026-10-29.1")
        self.assertIn("Left to an admin's build", auto.describe(held, OCT, True, self.clock, auto.mode())["text"])

    def test_an_admins_build_that_failed_still_leaves_the_cycle_to_them(self):
        self.live_build("2026-10-29.1")
        chart_job.start(self.app, DEC, by_name="Ann", then=lambda s, name, _job: auto.admin_started(s, name, "Ann", self.clock),
                        now=self.clock)
        self.builds_run("failed")
        for _ in range(4):
            self.later(hours=7)
            self.look()
        self.assertEqual([r.requested_by_name for r in self.chart_jobs()], ["Ann"])
        self.assertEqual(self.record(DEC)["admin_built"], {"build": "2026-12-24.1", "by": "Ann", "at": "2026-12-03T12:00:00Z"})
        self.assertEqual(self.sent, [])

    def test_registered_leaves_an_admins_next_and_an_admins_build_of_the_cycle(self):
        now = at("2026-12-03T12:00:00")
        base = {"live": "2026-10-29.1", "previous": None, "next": None, "builds": {"2026-10-29.1": info(OCT),
                                                                                  "2026-12-24.1": info(DEC)},
                "auto": {"2026-12-24": {"started": "2026-12-24.1"}}, "history": []}
        s = json.loads(json.dumps(base))
        s["next"] = {"build": "2026-12-24.1", "at": "2026-12-24T09:01:00Z", "by": "Ann"}   # an admin scheduled it by hand
        auto.registered(s, "2026-12-24.1", now)
        self.assertEqual((s["next"]["by"], s["history"], s["auto"]["2026-12-24"]["left"]), ("Ann", [], "an admin scheduled a switch"))
        s = json.loads(json.dumps(base))
        s["builds"]["2026-12-24.2"] = info(DEC, "Ann")
        auto.registered(s, "2026-12-24.1", now)
        self.assertEqual((s["next"], s["history"]), (None, []))
        s = json.loads(json.dumps(base))
        s["builds"]["2026-10-29.2"] = info(OCT)
        s["auto"]["2026-10-29"] = {"started": "2026-10-29.2"}
        s["live"] = "2026-10-29.1"
        s["builds"]["2026-10-29.1"] = info(OCT, "Ann")      # an admin's live build of the cycle
        auto.registered(s, "2026-10-29.2", now)
        self.assertEqual((s["live"], s["history"]), ("2026-10-29.1", []))
        for mark in ("cancelled", "dropped", "admin_built"):            # finished after the admin's choice: not switched to
            s = json.loads(json.dumps(base))
            s["auto"]["2026-12-24"][mark] = {"by": "Ann"}
            auto.registered(s, "2026-12-24.1", now)
            self.assertEqual((s["next"], s["history"], s["auto"]["2026-12-24"]["left"]), (None, [], "an admin's choice"), mark)
        # a build nobody's automatic cycle started is not its business
        s = json.loads(json.dumps(base))
        s["auto"] = {}
        auto.registered(s, "2026-12-24.1", now)
        self.assertEqual((s["next"], s["auto"]), (None, {}))

    def test_an_automatic_build_cancelled_mid_run_is_not_started_again_and_burns_no_try(self):
        self.live_build("2026-10-29.1")
        Env(self, SUPER_ADMIN_EMAIL="")
        self.look()
        self.builds_run("running")
        [row] = self.chart_jobs()
        admin = User(email="admin@example.com", name="Ann", role="admin", google_id="g-ann")
        db.session.add(admin)
        db.session.commit()
        from routes.admin_routes import admin_bp

        self.app.register_blueprint(admin_bp)
        client = self.app.test_client()
        with client.session_transaction() as session:
            session["admin_uid"], session["csrf"] = admin.id, "token"
        response = client.post(f"/admin/data/jobs/{row.id}/cancel", headers={"X-CSRF": "token"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.record(DEC)["cancelled"]["by"], "Ann")
        self.later(minutes=1)
        self.assertEqual(self.look(), auto.LOOK_EVERY_S)         # still running, and nothing else wanted: no wait behind it
        self.builds_run("cancelled")
        for _ in range(3):
            self.later(minutes=30)
            self.look()
        self.later(hours=12)
        self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(self.record(DEC)["tries"], 1)
        self.assertNotIn("gave_up", self.record(DEC))
        self.assertEqual(self.sent, [])
        self.assertIn("Cancelled by Ann", auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"])

    def test_its_own_build_running_for_a_day_is_not_a_wait_and_starts_nothing_more(self):
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run("running")
        for _ in range(8):
            self.later(hours=6)
            self.assertEqual(self.look(), auto.LOOK_EVERY_S)
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertIsNone(self.record(DEC).get("waiting_since"))
        self.assertEqual(self.sent, [])
        self.assertTrue(auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"].startswith("Building as 2026-12-24.1"))

    def test_a_cancel_the_job_noted_itself_is_kept_and_not_started_again(self):
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run("cancelled")          # cancelled, with no word from the route (a resumed build that ended so)
        for _ in range(4):
            self.later(hours=7)
            self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(self.record(DEC)["cancelled"]["by"], "")
        self.assertEqual(self.sent, [])

    def test_a_build_done_and_not_yet_listed_holds_its_cycle_while_it_can_be_listed(self):
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run("killed")
        with patch.object(chart_job, "recover", lambda *a, **k: None):     # its parent about to list it
            for _ in range(3):
                self.later(hours=7)
                self.look()
            self.assertEqual(len(self.chart_jobs()), 1)
            self.assertIsNone(self.record(DEC).get("error"))                # not a failure while it can be listed
            self.assertEqual(auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"],
                             "Building as 2026-12-24.1 (1 try).")
            shutil.rmtree(state.build_dir(self.root, "2026-12-24.1"))       # never listable now: it is tried again
            self.later(hours=7)
            self.look()
        self.assertEqual([r.build for r in self.chart_jobs()], ["2026-12-24.1", "2026-12-24.2"])
        self.assertEqual(self.record(DEC)["tries"], 2)

    def test_a_build_whose_folder_went_before_it_was_listed_is_a_failed_try(self):
        self.live_build("2026-10-29.1")
        why = "The build finished, but its folder went before it was listed."
        with patch.object(chart_job, "recover", lambda *a, **k: None):
            for n in range(1, 4):
                self.look()
                self.builds_run("killed")
                self.assertEqual(len(self.chart_jobs()), n)
                shutil.rmtree(state.build_dir(self.root, f"2026-12-24.{n}"))
                self.later(minutes=1)
                self.look()
                self.assertEqual((self.record(DEC)["tries"], self.record(DEC)["error"]), (n, why))
                self.assertEqual(len(self.chart_jobs()), n)               # a try: the next waits six hours
                if n < 3:
                    self.assertNotIn("gave_up", self.record(DEC))
                    self.assertTrue(auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"].startswith(
                        f"Failed ({n} tr"))
                self.later(hours=6)
            self.look()
        self.assertEqual(len(self.chart_jobs()), 3)
        self.assertEqual(self.record(DEC)["gave_up"]["why"], why)
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24: the automatic build gave up"])
        self.assertEqual(auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"], f"Given up (3 tries): {why}")

    def test_a_last_try_given_up_as_an_admin_cancels_it_is_not_emailed(self):
        # the cancel lands between the look that notes the failure and its emails: the cancel is the admin's, and says nothing
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run("running")

        def given_up(s):
            s["auto"]["2026-12-24"]["gave_up"] = {"at": "2026-12-03T12:00:00Z", "why": "The FAA's server stopped answering."}

        state.update(self.root, given_up, self.clock)
        auto.cancelled(self.root, "2026-12-24.1", "Ann", self.clock)
        self.later(minutes=1)
        self.look()
        self.assertEqual(self.sent, [])

    def test_a_cycle_not_posted_is_waited_for_and_burns_no_try(self):
        self.live_build("2026-10-29.1")
        self.posted = {OCT: True, DEC: False}
        for _ in range(10):
            self.look()
            self.later(hours=6)
        self.posted[DEC] = None                  # the FAA could not be asked
        self.look()
        self.assertEqual(self.chart_jobs(), [])
        self.assertEqual(self.record(DEC), {})
        self.assertEqual(auto.describe(self.held(), DEC, False, self.clock, auto.mode())["text"], "Waiting for the FAA to post it.")
        self.posted[DEC] = True
        self.look()
        self.assertEqual(self.record(DEC)["tries"], 1)

    def test_a_given_up_current_cycle_lets_the_next_be_built(self):
        self.live_build("2026-09-03.1")
        state.update(self.root, lambda s: s["auto"].__setitem__("2026-10-29", {"gave_up": {"why": "x", "at": "x"}, "tries": 3}))
        self.look()
        self.builds_run()
        self.assertEqual(self.held()["next"]["build"], "2026-12-24.1")
        self.assertEqual([r.build for r in self.chart_jobs()], ["2026-12-24.1"])

    def test_another_job_makes_it_wait_and_a_long_wait_is_said_once(self):
        self.live_build("2026-10-29.1")
        dem = DataJob(id=jobs.new_id(), kind="dem_download", product="1m", files=[], state="running",
                      requested_by_name="Bob")
        db.session.add(dem)
        db.session.commit()
        self.assertEqual(self.look(), auto.BUSY_LOOK_S)
        self.assertTrue(self.runner.knows(dem.id))                 # a DEM row left running is resumed, with no page opened
        self.assertEqual(self.chart_jobs(), [])
        self.assertEqual(self.record(DEC)["waiting_since"], "2026-12-03T12:00:00Z")
        self.assertNotIn("tries", self.record(DEC))
        for _ in range(50):
            self.later(minutes=30)
            self.look()
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24: waiting behind another job"])
        self.assertIn("a DEM download (started by Bob)", self.sent[0][2])
        dem.state = "done"
        db.session.commit()
        self.later(minutes=30)
        self.look()
        self.assertEqual([r.build for r in self.chart_jobs()], ["2026-12-24.1"])
        self.assertIsNone(self.record(DEC).get("waiting_since"))

    def test_a_wait_that_ended_is_not_said(self):
        self.live_build("2026-10-29.1")
        self.posted = {OCT: True, DEC: True}
        dem = DataJob(id=jobs.new_id(), kind="dem_download", product="1m", files=[], state="running", requested_by_name="Bob")
        db.session.add(dem)
        db.session.commit()
        self.look()
        dem.state = "done"
        db.session.commit()
        self.posted[DEC] = False                 # nothing wanted now
        self.later(hours=6)
        self.look()
        self.later(days=2)
        self.look()
        self.assertEqual(self.sent, [])

    def test_three_failures_give_up_with_one_email_and_try_again_clears_it(self):
        self.live_build("2026-10-29.1")
        for n in range(1, 4):
            self.look()
            self.builds_run("failed")
            self.assertEqual(self.record(DEC)["tries"], n)
            self.later(hours=5)
            self.look()                      # less than six hours since the try: nothing
            self.assertEqual(len(self.chart_jobs()), n)
            self.later(hours=1, minutes=1)
        self.look()
        self.assertEqual(len(self.chart_jobs()), 3)
        record = self.record(DEC)
        self.assertEqual(record["gave_up"]["why"], "The FAA's server stopped answering.")
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24: the automatic build gave up"])
        described = auto.describe(self.held(), DEC, True, self.clock, auto.mode())
        self.assertEqual((described["tone"], described.get("try_again")), ("bad", True))
        self.later(days=1)
        self.look()
        self.assertEqual(len(self.sent), 1)
        auto.try_again(self.root, "2026-12-24", "Ann", self.clock)
        self.assertEqual(self.held()["history"][-1]["did"], "try again")
        self.assertEqual(auto.describe(self.held(), DEC, True, self.clock, auto.mode())["text"], "Built at the next look.")
        self.look()
        self.builds_run()
        self.assertEqual(self.held()["next"]["build"], "2026-12-24.4")
        with self.assertRaisesRegex(state.Refused, "not been given up"):
            auto.try_again(self.root, "2026-12-24", "Ann", self.clock)

    def test_a_refusal_for_room_gives_up_at_once_with_one_email(self):
        self.live_build("2026-10-29.1")
        with patch.dict(os.environ, {"DATA_ADMIN_RESERVE_GB": "1000000"}):
            self.look()
            self.later(hours=7)
            self.look()
        self.assertEqual(self.chart_jobs(), [])
        self.assertTrue(self.record(DEC)["gave_up"]["room"])
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24: the automatic build gave up"])
        self.assertIn("Not enough room", self.sent[0][2])
        # and a build that runs out of room part way gives up at once too
        auto.try_again(self.root, "2026-12-24", "Ann", self.clock)
        self.look()
        self.builds_run("room")
        self.later(hours=7)
        self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertTrue(self.record(DEC)["gave_up"]["room"])
        self.assertEqual(self.subjects(), ["VFR charts 2026-12-24: the automatic build gave up"] * 2)     # said again

    def test_a_parent_killed_before_registering_leaves_a_build_the_next_look_registers_and_schedules(self):
        self.live_build("2026-10-29.1")
        self.look()
        self.builds_run("killed")
        self.assertNotIn("2026-12-24.1", self.held()["builds"])
        self.later(hours=6)
        self.look()
        self.assertEqual(self.held()["next"]["build"], "2026-12-24.1")
        self.assertEqual(len(self.chart_jobs()), 1)
        self.assertEqual(len(self.sent), 1)

    def test_off_builds_nothing_and_still_says_the_charts_are_expiring_and_expired(self):
        Env(self, CHART_AUTO="off")
        self.live_build("2026-10-29.1")
        self.clock = at("2026-12-17T09:01:00")          # seven days before it expires
        self.look()
        self.assertEqual(self.chart_jobs(), [])
        self.assertEqual(self.subjects(), ["VFR charts: 2026-10-29.1 expires in 7 days"])
        self.assertIn("CHART_AUTO=off", self.sent[0][2])
        self.later(hours=6)
        self.look()
        self.assertEqual(len(self.sent), 1)
        self.clock = at("2026-12-24T09:01:00")
        self.look()
        self.assertEqual(self.subjects()[1:], ["VFR charts expired: 2026-10-29.1 is past its date"])
        self.later(days=3)
        self.look()
        self.assertEqual(len(self.sent), 2)

    def test_the_expired_email_is_not_sent_at_0901z_when_a_switch_is_due_then(self):
        self.live_build("2026-10-29.1")
        put(self.root, "2026-12-24.1")
        state.schedule(self.root, "2026-12-24.1", AUTO, self.clock)
        self.clock = at("2026-12-24T09:01:00")
        self.look()
        self.assertEqual(self.sent, [])
        self.assertEqual(self.held()["live"], "2026-12-24.1")

    def test_outside_production_it_builds_nothing_unless_asked_and_read_does_nothing(self):
        Env(self, CHART_AUTO="")
        self.live_build("2026-10-29.1")
        self.look()
        self.assertEqual(self.chart_jobs(), [])
        self.assertIn("Off outside production", auto.mode()["why"])
        self.assertTrue(self.held()["looks"]["last"])               # it looked: housekeeping and the emails
        import security_config

        with patch.object(security_config, "is_production", lambda environ=os.environ: True):
            self.assertEqual(auto.mode()["why"], "On, as on every production server.")
            self.look()
        self.assertEqual(len(self.chart_jobs()), 1)
        written = (self.root / state.STATE).read_bytes()
        with patch.dict(os.environ, {"DATA_ADMIN": "read", "CHART_AUTO": "on"}):
            self.assertEqual(auto.mode(), {"looks": False, "builds": False, "why": auto.mode()["why"]})
            self.later(days=30)
            self.assertEqual(self.look(), auto.LOOK_EVERY_S)
        self.assertEqual((self.root / state.STATE).read_bytes(), written)
        with patch.dict(os.environ, {"CHART_TILES_DIR": ""}):
            self.assertFalse(auto.mode()["looks"])

    def test_one_look_at_a_time_across_processes(self):
        self.live_build("2026-10-29.1")
        fd = locks.try_lock(self.root / auto.LOCK)          # another process looking now
        try:
            self.look()
        finally:
            locks.release(fd)
        self.assertEqual(self.chart_jobs(), [])
        self.look()
        self.assertEqual(len(self.chart_jobs()), 1)

    def test_a_look_that_throws_is_logged_and_the_thread_goes_on(self):
        self.live_build("2026-10-29.1")
        with patch.object(jobs.Runner, "resume", side_effect=RuntimeError("boom")), self.assertLogs("charts.auto", "ERROR"):
            self.assertEqual(self.look(), auto.LOOK_EVERY_S)
        self.assertIn("failed part way", self.held()["looks"]["error"])
        self.assertEqual(self.chart_jobs(), [])
        # the thread: a look that raises (as it never should) is followed by the next
        calls, again = [], threading.Event()

        def look(app):
            calls.append(app)
            if len(calls) == 1:
                raise RuntimeError("boom")
            again.set()
            time.sleep(60)
            return 3600

        with patch.object(auto, "look", look), patch.object(auto, "LOOK_EVERY_S", 0), self.assertLogs("charts.auto", "ERROR"):
            thread = threading.Thread(target=auto._loop, args=(self.app, 0), daemon=True)
            thread.start()
            self.assertTrue(again.wait(10))
        self.assertTrue(thread.is_alive())

    def test_leftovers_nothing_names_are_deleted(self):
        self.live_build("2026-10-29.1")
        for folder in (state.build_dir(self.root, "2026-10-29.7"), state.staging_dir(self.root, "2026-10-29.8"),
                       self.root / state.INCOMING / "2026-10-29.8", self.root / state.TRASH / "x"):
            folder.mkdir(parents=True)
        held_staging = state.staging_dir(self.root, "2026-10-29.9")
        held_staging.mkdir()
        fd = locks.try_lock(held_staging / chart_job.LOCK)
        try:
            Env(self, CHART_AUTO="off")
            self.look()
        finally:
            locks.release(fd)
        self.assertEqual(sorted(p.name for p in self.root.iterdir() if p.name not in (state.STATE, state.LOCK, auto.LOCK)),
                         [".staging-2026-10-29.9", "builds"])
        self.assertEqual([p.name for p in (self.root / "builds").iterdir()], ["2026-10-29.1"])


# --------------------------------------------------------------------------------------------- the Charts card's routes


class ChartRouteTests(AutoCase):
    def setUp(self):
        super().setUp()
        from routes.admin_routes import admin_bp

        self.app.register_blueprint(admin_bp)
        admin = User(email="ann@example.com", name="Ann", role="admin", google_id="g-ann")
        db.session.add(admin)
        db.session.commit()
        self.client = self.app.test_client()
        with self.client.session_transaction() as session:
            session["admin_uid"], session["csrf"] = admin.id, "token"
        self.clock = datetime.now(timezone.utc).replace(microsecond=0)
        self.current, self.next = faa.current_cycle(self.clock), faa.next_cycle(self.clock)
        self.posted = {self.current: True, self.next: False}

    def act(self, path, *, csrf="token"):
        response = self.client.post(f"/admin/data/charts/{path}", headers={"X-CSRF": csrf})
        if response.is_json:            # the dashboard's own CSRF refusal is its HTML 400
            self.assertEqual(response.headers["Cache-Control"], "private, no-store")
        return response

    def panel(self):
        return self.client.get("/admin/data/charts.json").get_json()

    def test_each_action_and_what_the_card_offers(self):
        cur, nxt, old = (f"{c}.1" for c in (self.current, self.next, self.current - timedelta(days=56)))
        for name in (old, cur, nxt):
            put(self.root, name, "Ann")
        body = self.panel()
        offers = {b["name"]: (b["role"], b["actions"]) for b in body["builds"]}
        self.assertEqual(offers, {old: ("built", ["switch", "delete"]), cur: ("built", ["switch", "delete"]),
                                  nxt: ("built", ["schedule", "delete"])})
        self.assertEqual(self.act(f"builds/{nxt}/switch").get_json()["error"].split(":")[0],
                         f"{nxt} is for a cycle not in effect until {catalog.effective_instant(self.next):%Y-%m-%dT%H}")
        self.assertTrue(self.act(f"builds/{old}/switch").get_json()["ok"])
        self.assertTrue(self.act(f"builds/{cur}/switch").get_json()["ok"])
        self.assertEqual(self.act(f"builds/{nxt}/schedule").get_json()["at"],
                         catalog.effective_instant(self.next).strftime("%Y-%m-%dT%H:%M:%SZ"))
        body = self.panel()
        offers = {b["name"]: (b["role"], b["actions"]) for b in body["builds"]}
        self.assertEqual(offers, {old: ("previous", ["switch"]), cur: ("live", []), nxt: ("next", [])})
        self.assertEqual((body["serving"]["live"], body["serving"]["next"]["build"], body["serving"]["next"]["by"],
                          body["serving"]["roll_back"], body["serving"]["cancel_schedule"]), (cur, nxt, "Ann", True, True))
        self.assertEqual(body["cycles"][0]["serving"], "live")
        self.assertEqual(body["cycles"][1]["serving"], "next")
        self.assertTrue(self.act("roll-back").get_json()["ok"])
        self.assertEqual(state.read(self.root)["live"], old)
        self.assertTrue(self.act("schedule/cancel").get_json()["ok"])
        self.assertIn("No switch is scheduled", self.act("schedule/cancel").get_json()["error"])
        self.assertTrue(self.act(f"builds/{nxt}/delete").get_json()["ok"])
        self.assertFalse(state.build_dir(self.root, nxt).exists())
        self.assertEqual(self.act(f"builds/{old}/delete").status_code, 400)
        history = self.panel()["history"]
        self.assertEqual([(h["did"], h["by"]) for h in history[:3]], [("delete", "Ann"), ("cancel schedule", "Ann"),
                                                                       ("roll back", "Ann")])
        self.assertNotIn(str(self.root), json.dumps(self.panel()))

    def test_refused_without_csrf_in_read_mode_and_for_a_name_that_is_no_build(self):
        put(self.root, f"{self.current}.1", "Ann")
        written = (self.root / state.STATE).read_bytes()
        self.assertEqual(self.act(f"builds/{self.current}.1/switch", csrf="wrong").status_code, 400)
        with patch.dict(os.environ, {"DATA_ADMIN": "read"}):
            self.assertEqual(self.act(f"builds/{self.current}.1/switch").status_code, 403)
            self.assertEqual(self.panel()["builds"][0]["actions"], ["switch", "delete"])     # what applies, shown read-only
        for bad in ("..%2Fx", "2026-10-29", "x.1"):
            response = self.act(f"builds/{bad}/delete")
            self.assertIn(response.status_code, (400, 404))
        self.assertEqual(self.act("cycles/nonsense/try-again").get_json()["error"], "That is not a build's name.")
        self.assertEqual((self.root / state.STATE).read_bytes(), written)

    def test_the_card_says_what_the_automatic_cycle_does(self):
        body = self.panel()
        self.assertEqual((body["auto"]["looks"], body["auto"]["builds"]), (True, True))
        self.assertEqual(body["cycles"][1]["auto"]["text"], "Waiting for the FAA to post it.")
        self.assertEqual(body["cycles"][0]["auto"]["text"], "Built at the next look.")
        auto.look(self.app, now=self.clock)
        body = self.panel()
        self.assertEqual(body["auto"]["last"], state.iso(self.clock))
        self.assertTrue(body["cycles"][0]["auto"]["text"].startswith(f"Building as {self.current}.1 (1 try)"))
        state.update(self.root, lambda s: s["auto"][self.current.isoformat()].update(gave_up={"why": "no room"}))
        self.assertEqual(self.act(f"cycles/{self.next}/try-again").get_json()["error"], "That cycle has not been given up.")
        self.assertTrue(self.act(f"cycles/{self.current}/try-again").get_json()["ok"])
        self.assertNotIn("gave_up", state.read(self.root)["auto"][self.current.isoformat()])
        self.assertTrue(auto._wake.is_set())


if __name__ == "__main__":
    unittest.main()
