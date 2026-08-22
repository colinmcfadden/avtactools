"""The tileset API.

Point cloud data is public domain, but *which* locations have tilesets is not —
an open endpoint would let anyone enumerate where a unit has been planning to
land. So everything is behind JWT, keys are opaque, and coordinates are sent in
a body rather than a URL.
"""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from flask import Flask
from flask_jwt_extended import JWTManager, create_access_token

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from lidar import catalog  # noqa: E402
from routes.lidar_routes import lidar_bp  # noqa: E402

TARGET = {"lat": 34.591552, "lon": -84.128225}


class LidarApiTests(unittest.TestCase):
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
                     patch("models.db", MagicMock()),
                     patch("entitlements.has_feature", return_value=True)):
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

    # --- access -----------------------------------------------------------

    def test_every_endpoint_requires_a_token(self):
        """Otherwise the endpoint discloses where planning has happened."""
        key = catalog.key_for(**TARGET, radius_m=250)
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
        self.assertEqual(body["target"]["radius_m"], 250)

    def test_a_built_target_does_not_echo_coordinates_back(self):
        """Nothing needs them once there is a tileset to point at."""
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=250)
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
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=250)
        context = catalog.path_for(key) / catalog.CONTEXT_DIRNAME
        context.mkdir(parents=True)
        (context / "tileset.json").write_text('{"asset": {}}', encoding="utf-8")

        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertIsNotNone(body["contextUrl"])
        self.assertIn(catalog.CONTEXT_DIRNAME, body["contextUrl"])

    def test_no_context_ring_is_reported_when_none_was_built(self):
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        catalog.write_manifest(catalog.path_for(key), TARGET["lat"],
                               TARGET["lon"], radius_m=250)
        body = self.client.post("/api/lidar/resolve", json=TARGET,
                                headers=self.auth).get_json()
        self.assertIsNone(body["contextUrl"])

    def test_the_context_ring_is_served_like_any_other_tile(self):
        """No API change needed: it is a path under the same key."""
        key = catalog.key_for(**TARGET, radius_m=250)
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
        key = catalog.key_for(**TARGET, radius_m=250)
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
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"asset", response.data)

    def test_tiles_are_cacheable(self):
        """Cesium fetches many children per view and the key pins the content."""
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        self.assertIn("max-age", response.headers.get("Cache-Control", ""))

    def test_tiles_are_never_cached_by_a_shared_proxy(self):
        """A tile's content is an LZ's location, and the endpoint is auth-gated.

        Flask's max_age emits "public" on its own, which would let Cloudflare
        or any other shared cache hold a tile and serve it to someone else.
        """
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        response = self.client.get(f"/api/lidar/tilesets/{key}/tileset.json",
                                   headers=self.auth)
        cache_control = response.headers.get("Cache-Control", "")
        self.assertIn("private", cache_control)
        self.assertNotIn("public", cache_control)

    def test_an_ungenerated_tileset_is_a_clean_404(self):
        key = catalog.key_for(**TARGET, radius_m=250)
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
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        secret = self.root / "secret.txt"
        secret.write_text("should not be reachable")

        for attempt in ("../secret.txt", "..%2Fsecret.txt", "points/../../secret.txt"):
            response = self.client.get(
                f"/api/lidar/tilesets/{key}/{attempt}", headers=self.auth)
            self.assertNotEqual(response.status_code, 200, attempt)
            self.assertNotIn(b"should not be reachable", response.data)

    def test_listing_reports_only_complete_tilesets(self):
        key = catalog.key_for(**TARGET, radius_m=250)
        self.make_tileset(key)
        (self.root / "0123456789abcdef").mkdir()      # started, no tileset.json
        body = self.client.get("/api/lidar/tilesets", headers=self.auth).get_json()
        self.assertEqual(body["keys"], [key])


if __name__ == "__main__":
    unittest.main()
