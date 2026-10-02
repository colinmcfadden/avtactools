"""The tileset API.

Point cloud data is public domain, but *which* locations have tilesets is not —
an open endpoint would let anyone enumerate where a unit has been planning to
land. So everything is behind JWT, keys are opaque, and coordinates are sent in
a body rather than a URL.
"""

import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from flask import Flask
from flask_jwt_extended import JWTManager, create_access_token

from lidar import aoi, catalog
from app.routes.lidar import lidar_bp

TARGET = {"lat": 34.591552, "lon": -84.128225}
# Whatever the app builds by default; the tests follow it rather than pin it.
RADIUS = aoi.DEFAULT_RADIUS_M


class LidarApiHarness(unittest.TestCase):
    """An app with only the LiDAR blueprint, a token, and a temp tile store."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="tiles-"))
        self.addCleanup(shutil.rmtree, self.root, True)

        self.app = Flask(__name__)
        self.app.config.update(TESTING=True,
                               JWT_SECRET_KEY="test-only-secret-over-32-bytes-long")
        JWTManager(self.app)
        self.app.register_blueprint(lidar_bp)
        with self.app.app_context():
            self.jwt = create_access_token(identity="1")
        self.client = self.app.test_client()

        for item in (patch("lidar.catalog.tiles_dir", return_value=self.root),
                     patch("app.extensions.db", MagicMock()),
                     patch("app.security.entitlements.has_feature", return_value=True)):
            item.start()
            self.addCleanup(item.stop)

    @property
    def auth(self):
        return {"Authorization": f"Bearer {self.jwt}"}

    def make_tileset(self, key, body='{"asset":{"version":"1.0"}}'):
        directory = self.root / key
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "tileset.json").write_text(body)
        return directory


class LidarApiTests(LidarApiHarness):
    # --- access -----------------------------------------------------------

    def test_every_endpoint_requires_a_token(self):
        """Otherwise the endpoint discloses where planning has happened."""
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        for method, path in [("get", f"/api/lidar/tilesets/{key}"),
                             ("get", f"/api/lidar/tilesets/{key}/tileset.json"),
                             ("get", "/api/lidar/tilesets"),
                             ("post", "/api/lidar/resolve")]:
            response = getattr(self.client, method)(path)
            self.assertEqual(response.status_code, 401, path)

    # --- resolve ----------------------------------------------------------

    def test_resolve_returns_a_key_without_echoing_the_location(self):
        response = self.client.post("/api/lidar/resolve", headers=self.auth,
                                    json=TARGET)
        body = response.get_json()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(catalog.is_valid_key(body["key"]))
        self.assertNotIn("lat", body)
        self.assertNotIn("lon", body)

    def test_an_unbuilt_target_comes_back_with_its_coordinates(self):
        """So the client can say how to build it.

        Reporting only an opaque key left the user with a hash and no action;
        the coordinates are the caller's own, already in the request body.
        """
        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertFalse(body["available"])
        self.assertAlmostEqual(body["target"]["lat"], TARGET["lat"])
        self.assertAlmostEqual(body["target"]["lon"], TARGET["lon"])
        self.assertEqual(body["target"]["radius_m"], RADIUS)

    def test_a_built_target_does_not_echo_coordinates_back(self):
        """Nothing needs them once there is a tileset to point at."""
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=RADIUS)
        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertTrue(body["available"])
        self.assertNotIn("target", body)

    def test_a_context_ring_is_reported_when_one_was_built(self):
        """Range is a second, thinned tileset rather than a wider single one.

        Widening one build thins the centre too: 500 m radius yielded
        1.08 points/m2 against 3.50 at 250 m, so extending range cost detail
        exactly where the aircraft touches down.
        """
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=RADIUS)
        context = catalog.path_for(key) / catalog.CONTEXT_DIRNAME
        context.mkdir(parents=True)
        (context / "tileset.json").write_text('{"asset": {}}', encoding="utf-8")

        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertIsNotNone(body["contextUrl"])
        self.assertIn(catalog.CONTEXT_DIRNAME, body["contextUrl"])

    def test_no_context_ring_is_reported_when_none_was_built(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=RADIUS)
        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertIsNone(body["contextUrl"])

    def test_the_context_ring_is_served_like_any_other_tile(self):
        """No API change needed: it is a path under the same key."""
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        context = catalog.path_for(key) / catalog.CONTEXT_DIRNAME
        context.mkdir(parents=True)
        (context / "tileset.json").write_text('{"asset": {}}', encoding="utf-8")
        response = self.client.get(
            f"/api/lidar/tilesets/{key}/{catalog.CONTEXT_DIRNAME}/tileset.json",
            headers=self.auth)
        self.assertEqual(response.status_code, 200)

    def test_resolve_reports_absence_rather_than_failing(self):
        """A target with no tileset yet is a normal state, not an error."""
        body = self.client.post("/api/lidar/resolve", headers=self.auth,
                                json=TARGET).get_json()
        self.assertFalse(body["available"])
        self.assertIsNone(body["url"])

    def test_resolve_points_at_a_generated_tileset(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        body = self.client.post("/api/lidar/resolve", headers=self.auth,
                                json=TARGET).get_json()
        self.assertTrue(body["available"])
        # Relative to the API root, not the site root: the frontend's API base
        # already carries the /api prefix and would otherwise double it.
        self.assertEqual(body["url"], f"/lidar/tilesets/{key}/tileset.json")
        self.assertFalse(body["url"].startswith("/api/"))

    def test_resolve_rejects_a_missing_or_unusable_target(self):
        for payload in ({}, {"lat": 34.5}, {"lat": "x", "lon": -84.1}):
            response = self.client.post("/api/lidar/resolve", headers=self.auth,
                                        json=payload)
            self.assertEqual(response.status_code, 400, payload)

    def test_resolve_bounds_the_radius(self):
        """An unbounded radius is a request to process the whole survey."""
        for radius in (10, 100000):
            response = self.client.post("/api/lidar/resolve", headers=self.auth,
                                        json={**TARGET, "radius_m": radius})
            self.assertEqual(response.status_code, 400, radius)

    # --- serving ----------------------------------------------------------

    def test_a_generated_tileset_is_served(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"asset", response.data)

    def test_tiles_are_cacheable(self):
        """Cesium fetches many children per view and the key pins the content."""
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        self.assertIn("max-age", response.headers.get("Cache-Control", ""))

    def test_tiles_are_never_cached_by_a_shared_proxy(self):
        """A tile's content is an LZ's location, and the endpoint is auth-gated.

        Flask's max_age emits "public" on its own, which would let Cloudflare
        or any other shared cache hold a tile and serve it to someone else.
        """
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        cache_control = response.headers.get("Cache-Control", "")
        self.assertIn("private", cache_control)
        self.assertNotIn("public", cache_control)

    def test_an_ungenerated_tileset_is_a_clean_404(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        response = self.client.get(f"/api/lidar/tilesets/{key}", headers=self.auth)
        self.assertEqual(response.status_code, 404)
        self.assertFalse(response.get_json()["available"])

    def test_a_malformed_key_is_rejected_before_touching_the_disk(self):
        for hostile in ("..", "%2e%2e", "not-a-key", "ABCD"):
            response = self.client.get(f"/api/lidar/tilesets/{hostile}",
                                       headers=self.auth)
            self.assertIn(response.status_code, (400, 404), hostile)

    def test_traversal_through_the_filename_is_refused(self):
        """The key is validated, so the filename is the remaining way out."""
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        secret = self.root / "secret.txt"
        secret.write_text("should not be reachable")

        for attempt in ("../secret.txt", "..%2Fsecret.txt", "points/../../secret.txt"):
            response = self.client.get(
                f"/api/lidar/tilesets/{key}/{attempt}", headers=self.auth)
            self.assertNotEqual(response.status_code, 200, attempt)
            self.assertNotIn(b"should not be reachable", response.data)

    def test_listing_reports_only_complete_tilesets(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        self.make_tileset(key)
        (self.root / "0123456789abcdef").mkdir()      # started, no tileset.json
        body = self.client.get("/api/lidar/tilesets", headers=self.auth).get_json()
        self.assertEqual(body["keys"], [key])


class BuildApiTests(LidarApiHarness):
    """Asking for a point cloud where none exists yet.

    The API never runs the toolchain; it forwards to the build service. These
    replace that service with stand-ins and check what the browser is told.
    """

    JOB = {"key": "0123456789abcdef", "state": "queued", "stage": "waiting",
           "error": None, "position": 0}

    def setUp(self):
        super().setUp()
        self.env = patch.dict("os.environ", {"LIDAR_BUILDER_URL": "http://builder:8090"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def build(self, target=None):
        return self.client.post("/api/lidar/build", headers=self.auth,
                                json=target or TARGET)

    def test_the_build_endpoints_need_a_token(self):
        self.assertEqual(self.client.post("/api/lidar/build", json=TARGET).status_code, 401)
        self.assertEqual(self.client.get("/api/lidar/build/" + "0" * 16).status_code, 401)

    def test_resolve_says_whether_a_build_can_be_asked_for(self):
        body = self.client.post("/api/lidar/resolve", headers=self.auth,
                                json=TARGET).get_json()
        self.assertTrue(body["canBuild"])
        with patch.dict("os.environ", {"LIDAR_BUILDER_URL": ""}):
            body = self.client.post("/api/lidar/resolve", headers=self.auth,
                                    json=TARGET).get_json()
        self.assertFalse(body["canBuild"])

    def test_a_build_is_forwarded_and_accepted(self):
        with patch("app.services.lidar_client.submit", return_value=self.JOB) as submit:
            response = self.build()
        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.get_json()["state"], "queued")
        submit.assert_called_once_with(TARGET["lat"], TARGET["lon"], RADIUS,
                                       keep=False, watcher=None)

    # --- who is waiting -----------------------------------------------------

    def test_the_watcher_and_keep_are_forwarded(self):
        with patch("app.services.lidar_client.submit", return_value=self.JOB) as submit:
            self.build({**TARGET, "watcher": "tab-12345678", "keep": True})
        submit.assert_called_once_with(TARGET["lat"], TARGET["lon"], RADIUS,
                                       keep=True, watcher="tab-12345678")

    def test_a_malformed_watcher_is_not_forwarded(self):
        with patch("app.services.lidar_client.submit", return_value=self.JOB) as submit:
            self.build({**TARGET, "watcher": "../etc/passwd"})
        self.assertIsNone(submit.call_args.kwargs["watcher"])

    def test_polling_counts_as_waiting(self):
        with patch("app.services.lidar_client.status", return_value=self.JOB) as status:
            self.client.get(f"/api/lidar/build/{self.JOB['key']}?watcher=tab-12345678&keep=1",
                            headers=self.auth)
        status.assert_called_once_with(self.JOB["key"], watcher="tab-12345678", keep=True)

    def test_leaving_a_build_is_forwarded(self):
        cancelled = {**self.JOB, "state": "cancelled"}
        with patch("app.services.lidar_client.release", return_value=cancelled) as release:
            response = self.client.delete(
                f"/api/lidar/build/{self.JOB['key']}?watcher=tab-12345678", headers=self.auth)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["state"], "cancelled")
        release.assert_called_once_with(self.JOB["key"], watcher="tab-12345678")

    def test_leaving_needs_a_token(self):
        self.assertEqual(self.client.delete("/api/lidar/build/" + "0" * 16).status_code, 401)

    def test_leaving_a_build_the_service_never_had_is_harmless(self):
        with patch("app.services.lidar_client.release", return_value=None):
            response = self.client.delete("/api/lidar/build/" + "0" * 16, headers=self.auth)
        self.assertEqual(response.status_code, 204)

    def test_leaving_without_a_service_is_harmless(self):
        with patch.dict("os.environ", {"LIDAR_BUILDER_URL": ""}):
            response = self.client.delete("/api/lidar/build/" + "0" * 16, headers=self.auth)
        self.assertEqual(response.status_code, 204)

    def test_leaving_a_malformed_key_is_refused(self):
        response = self.client.delete("/api/lidar/build/not-a-key", headers=self.auth)
        self.assertEqual(response.status_code, 400)

    def test_somewhere_already_built_is_done_without_asking_the_service(self):
        key = catalog.key_for(**TARGET, radius_m=RADIUS)
        catalog.write_manifest(self.make_tileset(key), TARGET["lat"], TARGET["lon"],
                               radius_m=RADIUS)
        with patch("app.services.lidar_client.submit") as submit:
            body = self.build().get_json()
        submit.assert_not_called()
        self.assertEqual(body["state"], "done")
        self.assertTrue(body["url"].endswith("/tileset.json"))

    def test_without_a_service_the_answer_is_503_not_a_crash(self):
        with patch.dict("os.environ", {"LIDAR_BUILDER_URL": ""}):
            response = self.build()
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.get_json()["code"], "builder_not_configured")

    def test_an_unreachable_service_is_503(self):
        from app.services import lidar_client
        with patch("app.services.lidar_client.submit",
                   side_effect=lidar_client.BuilderUnavailable("down")):
            response = self.build()
        self.assertEqual(response.status_code, 503)

    def test_a_full_queue_is_429(self):
        from app.services import lidar_client
        with patch("app.services.lidar_client.submit", side_effect=lidar_client.BuilderBusy("full")):
            self.assertEqual(self.build().status_code, 429)

    def test_build_areas_are_capped_tighter_than_lookups(self):
        """Builds are interactive; a 2 km radius is minutes of someone's wait."""
        response = self.build({**TARGET, "radius_m": 2000})
        self.assertEqual(response.status_code, 400)

    def test_progress_is_relayed_by_key(self):
        running = {**self.JOB, "state": "running", "stage": "processing points",
                   "elapsed_s": 12}
        with patch("app.services.lidar_client.status", return_value=running):
            body = self.client.get(f"/api/lidar/build/{self.JOB['key']}",
                                   headers=self.auth).get_json()
        self.assertEqual(body["stage"], "processing points")
        self.assertEqual(body["elapsed_s"], 12)

    def test_a_finished_build_reports_where_the_tileset_is(self):
        key = self.JOB["key"]
        self.make_tileset(key)
        with patch("app.services.lidar_client.status") as status:
            body = self.client.get(f"/api/lidar/build/{key}", headers=self.auth).get_json()
        status.assert_not_called()          # the disk is the source of truth
        self.assertEqual(body["state"], "done")
        self.assertEqual(body["url"], f"/lidar/tilesets/{key}/tileset.json")

    def test_progress_for_an_unknown_build_is_404(self):
        with patch("app.services.lidar_client.status", return_value=None):
            response = self.client.get("/api/lidar/build/" + "0" * 16, headers=self.auth)
        self.assertEqual(response.status_code, 404)

    def test_a_malformed_key_never_reaches_the_service(self):
        with patch("app.services.lidar_client.status") as status:
            response = self.client.get("/api/lidar/build/not-a-key", headers=self.auth)
        self.assertEqual(response.status_code, 400)
        status.assert_not_called()


if __name__ == "__main__":
    unittest.main()
