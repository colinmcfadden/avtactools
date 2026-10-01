"""The point-cloud build service: its queue, its staging, and its HTTP surface.

The real build (PDAL, py3dtiles) is replaced by a stand-in that writes the
files a finished tileset has, so these exercise everything the service itself
decides: what gets queued, when a result becomes visible, and what a caller is
told.
"""

import http.client
import json
import shutil
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lidar import catalog  # noqa: E402
from lidar.build import BuildError, BuildRequest  # noqa: E402
from lidar.tiles import TileBuildError  # noqa: E402
from lidar.worker import (  # noqa: E402
    ABANDON_AFTER_S, STAGING_PREFIX, TOKEN_HEADER, Builder, BuildCancelled,
    QueueFull, cancellable_runner, make_handler, request_factory)

TARGET = (34.596407, -84.128098, 250.0)
OTHER = (34.783817, -84.082190, 250.0)
WATCHER = "tab-aaaaaaaa"
OTHER_WATCHER = "tab-bbbbbbbb"


def fake_build(request, out_dir, *, stage=lambda _s: None, **_kwargs):
    """Write what a finished build leaves behind."""
    stage("processing points")
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "tileset.json").write_text('{"asset": {}}', encoding="utf-8")
    catalog.write_manifest(out_dir, request.lat, request.lon,
                           radius_m=request.radius_m)
    return {}


def make_request(lat, lon, radius_m):
    return BuildRequest(lat=lat, lon=lon, radius_m=radius_m)


class Clock:
    def __init__(self, now=1000.0):
        self.now = now

    def __call__(self):
        return self.now


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root, ignore_errors=True))
        self.clock = Clock()
        self.builder = Builder(self.root, make_request=make_request,
                               build=fake_build, queue_limit=3, clock=self.clock)

    def key(self, target=TARGET):
        return catalog.key_for(target[0], target[1], radius_m=target[2])

    def test_a_new_target_is_queued(self):
        job = self.builder.submit(*TARGET)
        self.assertEqual(job["state"], "queued")
        self.assertEqual(job["key"], self.key())
        self.assertEqual(job["position"], 0)

    def test_asking_twice_returns_the_same_build(self):
        """The 3D window re-asks on reopen; a second build would only cost time."""
        first = self.builder.submit(*TARGET)
        second = self.builder.submit(*TARGET)
        self.assertEqual(first["key"], second["key"])
        self.assertEqual(self.builder.pending(), 1)

    def test_somewhere_already_built_is_done_without_queueing(self):
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        fresh = Builder(self.root, make_request=make_request, build=fake_build)
        self.assertEqual(fresh.submit(*TARGET)["state"], "done")
        self.assertEqual(fresh.pending(), 0)

    def test_queue_position_counts_the_builds_ahead(self):
        self.builder.submit(*TARGET)
        second = self.builder.submit(*OTHER)
        self.assertEqual(second["position"], 1)

    def test_a_full_queue_refuses_rather_than_growing(self):
        for index in range(3):
            self.builder.submit(34.0 + index * 0.1, -84.0, 250.0)
        with self.assertRaises(QueueFull):
            self.builder.submit(35.5, -84.0, 250.0)

    def test_a_finished_build_is_published_under_its_key(self):
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        self.assertEqual(self.builder.status(self.key())["state"], "done")
        self.assertTrue(catalog.exists(self.key(), root=self.root))
        self.assertTrue((self.root / self.key() / catalog.MANIFEST_FILENAME).exists())

    def test_nothing_is_visible_under_the_real_key_until_the_build_finishes(self):
        """A half-written tileset must never be served."""
        seen = {}

        def watching_build(request, out_dir, **kwargs):
            seen["during"] = catalog.exists(self.key(), root=self.root)
            seen["staged_in"] = Path(out_dir).name
            return fake_build(request, out_dir, **kwargs)

        self.builder.build = watching_build
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        self.assertFalse(seen["during"])
        self.assertTrue(seen["staged_in"].startswith(STAGING_PREFIX))
        self.assertFalse(any(p.name.startswith(STAGING_PREFIX)
                             for p in self.root.iterdir()))

    def test_a_staging_directory_is_not_listed_as_a_tileset(self):
        staging = self.root / f"{STAGING_PREFIX}{self.key()}"
        staging.mkdir()
        (staging / "tileset.json").write_text("{}", encoding="utf-8")
        self.assertEqual(catalog.available(root=self.root), [])

    def test_a_failed_build_reports_why_and_leaves_nothing_behind(self):
        def failing(_request, out_dir, **_kwargs):
            Path(out_dir).mkdir(parents=True)
            raise BuildError("no USGS lidar covers 1.00000, 1.00000")

        self.builder.build = failing
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        job = self.builder.status(self.key())
        self.assertEqual(job["state"], "failed")
        self.assertIn("no USGS lidar", job["error"])
        self.assertFalse(catalog.exists(self.key(), root=self.root))
        self.assertEqual(list(self.root.iterdir()), [])

    def test_a_crash_is_reported_without_leaking_the_traceback(self):
        def crashing(*_args, **_kwargs):
            raise RuntimeError("internal detail /opt/app/lidar/tiles.py")

        self.builder.build = crashing
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        job = self.builder.status(self.key())
        self.assertEqual(job["state"], "failed")
        self.assertNotIn("/opt/app", job["error"])

    def test_a_failed_target_can_be_asked_for_again(self):
        self.builder.build = lambda *_a, **_k: (_ for _ in ()).throw(BuildError("x"))
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        self.builder.build = fake_build
        self.assertEqual(self.builder.submit(*TARGET)["state"], "queued")

    def test_progress_reports_the_current_stage(self):
        stages = []

        def staged(request, out_dir, *, stage, **kwargs):
            stage("locating survey")
            stages.append(self.builder.status(self.key())["stage"])
            return fake_build(request, out_dir, stage=stage)

        self.builder.build = staged
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        self.assertEqual(stages, ["locating survey"])

    def test_finished_jobs_are_forgotten_after_an_hour(self):
        self.builder.build = lambda *_a, **_k: (_ for _ in ()).throw(BuildError("x"))
        self.builder.submit(*TARGET)
        self.builder.process(self.key(), *TARGET)
        self.clock.now += 3601
        self.builder.submit(*OTHER)            # pruning happens on submit
        self.assertIsNone(self.builder.status(self.key()))

    def test_builds_left_half_done_by_a_restart_are_cleared(self):
        stale = self.root / f"{STAGING_PREFIX}{self.key()}"
        stale.mkdir()
        self.builder._clear_stale_staging()
        self.assertFalse(stale.exists())


class ConfigurationTests(unittest.TestCase):
    def test_imagery_colouring_when_imagery_is_available(self):
        make = request_factory({"LIDAR_IMAGERY": "/x/imagery.xml"})
        request = make(*TARGET)
        self.assertEqual(request.color_by, "imagery")
        self.assertEqual(request.imagery, "/x/imagery.xml")

    def test_a_collection_falls_back_to_aws_off_its_edge(self):
        make = request_factory({"LIDAR_COLLECTION": "/data/lidar"})
        request = make(*TARGET)
        self.assertEqual(str(request.collection).replace("\\", "/"), "/data/lidar")
        self.assertTrue(request.collection_fallback)

    def test_a_context_ring_narrower_than_the_core_is_dropped(self):
        self.assertIsNone(request_factory({"LIDAR_BUILD_CONTEXT_M": "200"})(*TARGET).context_m)
        self.assertEqual(request_factory({"LIDAR_BUILD_CONTEXT_M": "1000"})(*TARGET).context_m,
                         1000.0)


class HttpTests(unittest.TestCase):
    TOKEN = "builder-test-token"

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root, ignore_errors=True))
        self.builder = Builder(self.root, make_request=make_request,
                               build=fake_build, queue_limit=1)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0),
                                          make_handler(self.builder, self.TOKEN))
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def call(self, method, path, body=None, token=TOKEN):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        headers = {"Content-Type": "application/json"}
        if token:
            headers[TOKEN_HEADER] = token
        connection.request(method, path, body=json.dumps(body) if body is not None else None,
                           headers=headers)
        response = connection.getresponse()
        payload = json.loads(response.read() or b"{}")
        connection.close()
        return response.status, payload

    def test_health_needs_no_token(self):
        status, body = self.call("GET", "/health", token=None)
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])

    def test_builds_need_the_token(self):
        status, _ = self.call("POST", "/builds",
                              {"lat": TARGET[0], "lon": TARGET[1], "radius_m": 250},
                              token="wrong")
        self.assertEqual(status, 401)

    def test_a_build_is_accepted(self):
        status, body = self.call("POST", "/builds",
                                 {"lat": TARGET[0], "lon": TARGET[1], "radius_m": 250})
        self.assertEqual(status, 202)
        self.assertEqual(body["state"], "queued")

    def test_a_malformed_target_is_refused(self):
        status, _ = self.call("POST", "/builds", {"lat": "north", "lon": 1, "radius_m": 250})
        self.assertEqual(status, 400)

    def test_an_oversized_area_is_refused(self):
        """Builds are interactive; wide landscape comes from the context ring."""
        status, _ = self.call("POST", "/builds",
                              {"lat": TARGET[0], "lon": TARGET[1], "radius_m": 5000})
        self.assertEqual(status, 400)

    def test_a_full_queue_answers_429(self):
        self.call("POST", "/builds", {"lat": TARGET[0], "lon": TARGET[1], "radius_m": 250})
        status, _ = self.call("POST", "/builds",
                              {"lat": OTHER[0], "lon": OTHER[1], "radius_m": 250})
        self.assertEqual(status, 429)

    def test_progress_is_read_by_key(self):
        _, job = self.call("POST", "/builds",
                           {"lat": TARGET[0], "lon": TARGET[1], "radius_m": 250})
        status, body = self.call("GET", f"/builds/{job['key']}")
        self.assertEqual(status, 200)
        self.assertEqual(body["key"], job["key"])

    def test_an_unknown_build_is_404(self):
        status, _ = self.call("GET", "/builds/" + "0" * 16)
        self.assertEqual(status, 404)

    def test_a_malformed_key_never_reaches_the_builder(self):
        status, _ = self.call("GET", "/builds/../../etc")
        self.assertEqual(status, 404)

    def test_asking_with_a_watcher_keeps_the_build(self):
        _, job = self.call("POST", "/builds", {"lat": TARGET[0], "lon": TARGET[1],
                                               "radius_m": 250, "watcher": WATCHER})
        status, body = self.call("GET", f"/builds/{job['key']}?watcher={WATCHER}")
        self.assertEqual((status, body["state"]), (200, "queued"))

    def test_leaving_drops_an_unkept_build(self):
        _, job = self.call("POST", "/builds", {"lat": TARGET[0], "lon": TARGET[1],
                                               "radius_m": 250, "watcher": WATCHER})
        status, body = self.call("DELETE", f"/builds/{job['key']}?watcher={WATCHER}")
        self.assertEqual((status, body["state"]), (200, "cancelled"))

    def test_leaving_a_kept_build_leaves_it_running(self):
        _, job = self.call("POST", "/builds", {"lat": TARGET[0], "lon": TARGET[1],
                                               "radius_m": 250, "watcher": WATCHER,
                                               "keep": True})
        _, body = self.call("DELETE", f"/builds/{job['key']}?watcher={WATCHER}")
        self.assertEqual(body["state"], "queued")

    def test_leaving_needs_the_token(self):
        status, _ = self.call("DELETE", "/builds/" + "0" * 16, token="wrong")
        self.assertEqual(status, 401)


class AbandonedBuildTests(unittest.TestCase):
    """A build runs only while someone is waiting for it, unless it is kept.

    A refreshed page used to leave its build running, and every LZ opened
    afterwards queued behind work nobody would ever look at.
    """

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root, ignore_errors=True))
        self.clock = Clock()
        self.builds = []

        def recording_build(request, out_dir, **kwargs):
            self.builds.append(request)
            return fake_build(request, out_dir, **kwargs)

        self.builder = Builder(self.root, make_request=make_request,
                               build=recording_build, clock=self.clock)
        self.key = catalog.key_for(TARGET[0], TARGET[1], radius_m=TARGET[2])

    def later(self, seconds):
        self.clock.now += seconds

    def test_a_build_nobody_asks_after_is_dropped(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        self.later(ABANDON_AFTER_S + 1)
        self.builder.reap()
        self.assertEqual(self.builder.status(self.key)["state"], "cancelled")
        self.assertEqual(self.builder.pending(), 0)

    def test_asking_after_a_build_keeps_it(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        for _ in range(5):
            self.later(ABANDON_AFTER_S - 10)
            self.builder.status(self.key, watcher=WATCHER)
            self.builder.reap()
        self.assertEqual(self.builder.status(self.key)["state"], "queued")

    def test_a_kept_build_outlives_its_watchers(self):
        """A saved LZ is worth finishing even if the page goes away."""
        self.builder.submit(*TARGET, watcher=WATCHER, keep=True)
        self.builder.release(self.key, watcher=WATCHER)
        self.later(ABANDON_AFTER_S * 10)
        self.builder.reap()
        self.assertEqual(self.builder.status(self.key)["state"], "queued")

    def test_keeping_can_be_asked_for_after_the_build_started(self):
        """Saving an LZ while its point cloud builds."""
        self.builder.submit(*TARGET, watcher=WATCHER)
        self.builder.status(self.key, watcher=WATCHER, keep=True)
        self.later(ABANDON_AFTER_S * 10)
        self.builder.reap()
        self.assertEqual(self.builder.status(self.key)["state"], "queued")

    def test_the_last_watcher_leaving_drops_it_at_once(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        job = self.builder.release(self.key, watcher=WATCHER)
        self.assertEqual(job["state"], "cancelled")

    def test_one_watcher_leaving_does_not_drop_another_watchers_build(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        self.builder.submit(*TARGET, watcher=OTHER_WATCHER)
        job = self.builder.release(self.key, watcher=WATCHER)
        self.assertEqual(job["state"], "queued")

    def test_a_dropped_build_is_never_run(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        self.builder.release(self.key, watcher=WATCHER)
        self.builder.process(self.key, *TARGET)
        self.assertEqual(self.builds, [])
        self.assertFalse(catalog.exists(self.key, root=self.root))

    def test_builds_behind_a_dropped_one_move_up(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        behind = self.builder.submit(*OTHER, watcher=OTHER_WATCHER)
        self.assertEqual(behind["position"], 1)
        self.builder.release(self.key, watcher=WATCHER)
        other_key = catalog.key_for(OTHER[0], OTHER[1], radius_m=OTHER[2])
        self.assertEqual(self.builder.status(other_key)["position"], 0)

    def test_a_dropped_place_can_be_asked_for_again(self):
        self.builder.submit(*TARGET, watcher=WATCHER)
        self.builder.release(self.key, watcher=WATCHER)
        again = self.builder.submit(*TARGET, watcher=WATCHER)
        self.assertEqual(again["state"], "queued")
        # Two queue entries now name this place; the first runs the new job
        # and the second finds nothing left to do.
        self.builder.process(self.key, *TARGET)
        self.builder.process(self.key, *TARGET)
        self.assertEqual(len(self.builds), 1)
        self.assertEqual(self.builder.status(self.key)["state"], "done")

    def test_a_running_build_stops_and_leaves_nothing_behind(self):
        builder = self.builder
        key = self.key

        def abandoned_mid_build(request, out_dir, *, stage, **_kwargs):
            stage("processing points")
            Path(out_dir).mkdir(parents=True, exist_ok=True)
            builder.release(key, watcher=WATCHER)
            stage("building tiles")   # the next stage notices
            raise AssertionError("should have been cancelled")

        builder.build = abandoned_mid_build
        builder.submit(*TARGET, watcher=WATCHER)
        builder.process(key, *TARGET)
        self.assertEqual(builder.status(key)["state"], "cancelled")
        self.assertFalse(catalog.exists(key, root=self.root))
        self.assertEqual(list(self.root.iterdir()), [])

    def test_requests_without_a_watcher_id_share_one(self):
        """Older clients still get a working lease."""
        self.builder.submit(*TARGET)
        self.builder.status(self.key, watcher="not a valid id!")
        self.assertEqual(self.builder.release(self.key)["state"], "cancelled")


class CancellableRunnerTests(unittest.TestCase):
    def test_output_comes_back_like_the_plain_runner(self):
        run = cancellable_runner(threading.Event())
        self.assertEqual(run([sys.executable, "-c", "print('ok')"]).strip(), "ok")

    def test_a_failing_tool_reports_its_error(self):
        run = cancellable_runner(threading.Event())
        with self.assertRaises(TileBuildError) as caught:
            run([sys.executable, "-c", "import sys; sys.exit('bad input')"])
        self.assertIn("bad input", str(caught.exception))

    def test_cancelling_stops_a_running_tool_promptly(self):
        import time
        cancel = threading.Event()
        run = cancellable_runner(cancel, poll_s=0.05)
        threading.Timer(0.3, cancel.set).start()
        started = time.monotonic()
        with self.assertRaises(BuildCancelled):
            run([sys.executable, "-c", "import time; time.sleep(30)"])
        self.assertLess(time.monotonic() - started, 5)

    def test_nothing_starts_once_cancelled(self):
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(BuildCancelled):
            cancellable_runner(cancel)([sys.executable, "-c", "print('ran')"])


if __name__ == "__main__":
    unittest.main()
