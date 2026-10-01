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
from lidar.worker import (  # noqa: E402
    STAGING_PREFIX, TOKEN_HEADER, Builder, QueueFull, make_handler,
    request_factory)

TARGET = (34.596407, -84.128098, 250.0)
OTHER = (34.783817, -84.082190, 250.0)


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


if __name__ == "__main__":
    unittest.main()
