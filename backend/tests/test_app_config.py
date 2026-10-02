"""GET /api/config: what an app needs before anyone signs in."""

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from flask import Flask

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app_config import CONFIG_VERSION, build_config, public_mapbox_token  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402

DEFAULT_TOKEN = "pk.default-token-from-export-service"


def build(environ=None, lidar=False, default=DEFAULT_TOKEN):
    return build_config(environ or {}, server_version="1.7.7", lidar_builds=lidar, mapbox_default=default)


class BuildConfigTests(unittest.TestCase):
    def test_a_bare_environment_says_nothing_is_required_or_wrong(self):
        config = build()
        self.assertEqual(config["configVersion"], CONFIG_VERSION)
        self.assertEqual(config["serverVersion"], "1.7.7")
        self.assertEqual(config["minAppVersion"], {"android": None, "ios": None})
        self.assertEqual(config["maintenance"], {"active": False, "message": None})
        self.assertEqual(config["services"], {"lidarBuilds": False, "packs": False})
        self.assertEqual(config["mapbox"], {"publicToken": DEFAULT_TOKEN})

    def test_minimum_versions_are_per_platform(self):
        config = build({"MIN_APP_VERSION_ANDROID": "1.2.0", "MIN_APP_VERSION_IOS": " 2.0.0 "})
        self.assertEqual(config["minAppVersion"], {"android": "1.2.0", "ios": "2.0.0"})

    def test_a_malformed_minimum_is_ignored_rather_than_served(self):
        # An app comparing itself against garbage could lock every crew out.
        for bad in ("latest", "1.2", "1.2.3.4", "v1.2.3", "1.2.x", "-1.0.0", "1.2.3-beta"):
            with self.subTest(bad=bad):
                with self.assertLogs("app_config", level="WARNING"):
                    config = build({"MIN_APP_VERSION_ANDROID": bad})
                self.assertIsNone(config["minAppVersion"]["android"])

    def test_a_maintenance_message_turns_maintenance_on(self):
        config = build({"MAINTENANCE_MESSAGE": "  Back at 1800Z.  "})
        self.assertEqual(config["maintenance"], {"active": True, "message": "Back at 1800Z."})

    def test_a_blank_maintenance_message_is_off(self):
        self.assertEqual(build({"MAINTENANCE_MESSAGE": "   "})["maintenance"]["active"], False)

    def test_a_long_maintenance_message_is_cut_to_the_contract_limit(self):
        message = build({"MAINTENANCE_MESSAGE": "x" * 900})["maintenance"]["message"]
        self.assertEqual(len(message), 500)

    def test_lidar_builds_follow_the_service_being_configured(self):
        self.assertTrue(build(lidar=True)["services"]["lidarBuilds"])
        self.assertFalse(build(lidar=False)["services"]["lidarBuilds"])

    def test_the_mission_pack_service_does_not_exist_yet(self):
        self.assertFalse(build(lidar=True)["services"]["packs"])


class MapboxTokenTests(unittest.TestCase):
    def test_the_environment_overrides_the_default(self):
        self.assertEqual(public_mapbox_token({"MAPBOX_PUBLIC_TOKEN": "pk.rotated"}, DEFAULT_TOKEN), "pk.rotated")

    def test_the_default_is_used_when_the_environment_has_none(self):
        self.assertEqual(public_mapbox_token({}, DEFAULT_TOKEN), DEFAULT_TOKEN)

    def test_a_secret_token_is_never_served(self):
        # The endpoint is anonymous. A sk. token set by mistake must not leak.
        with self.assertLogs("app_config", level="WARNING"):
            self.assertIsNone(public_mapbox_token({"MAPBOX_PUBLIC_TOKEN": "sk.eyJ1IjoieCJ9.secret"}, DEFAULT_TOKEN))

    def test_a_secret_default_is_not_served_either(self):
        with self.assertLogs("app_config", level="WARNING"):
            self.assertIsNone(public_mapbox_token({}, "sk.not-public"))

    def test_nothing_configured_serves_nothing(self):
        self.assertIsNone(public_mapbox_token({}, None))
        self.assertIsNone(public_mapbox_token({"MAPBOX_PUBLIC_TOKEN": "  "}, None))


class ConfigRouteTests(unittest.TestCase):
    def setUp(self):
        app = Flask(__name__)
        app.register_blueprint(config_bp)
        self.client = app.test_client()
        patcher = patch("routes.config_routes._default_mapbox_token", return_value=DEFAULT_TOKEN)
        patcher.start()
        self.addCleanup(patcher.stop)

    def get(self, **env):
        with patch.dict(os.environ, env, clear=False):
            return self.client.get("/api/config")

    def test_it_is_public(self):
        response = self.get()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["configVersion"], CONFIG_VERSION)

    def test_it_is_cached_briefly_and_shared(self):
        # Identical for everyone, so `public` is right; user data uses `private`.
        self.assertEqual(self.get().headers["Cache-Control"], "public, max-age=60")

    def test_it_reflects_the_environment(self):
        config = self.get(
            MIN_APP_VERSION_ANDROID="1.3.0", MAINTENANCE_MESSAGE="Down for a bit",
            LIDAR_BUILDER_URL="http://builder:8090", MAPBOX_PUBLIC_TOKEN="pk.rotated",
        ).get_json()
        self.assertEqual(config["minAppVersion"]["android"], "1.3.0")
        self.assertTrue(config["maintenance"]["active"])
        self.assertTrue(config["services"]["lidarBuilds"])
        self.assertEqual(config["mapbox"]["publicToken"], "pk.rotated")

    def test_lidar_is_off_without_a_builder(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("LIDAR_BUILDER_URL", None)
            self.assertFalse(self.client.get("/api/config").get_json()["services"]["lidarBuilds"])

    def test_it_takes_no_credentials_and_writes_nothing(self):
        self.assertEqual(self.client.post("/api/config").status_code, 405)
        self.assertEqual(self.client.delete("/api/config").status_code, 405)


if __name__ == "__main__":
    unittest.main()
