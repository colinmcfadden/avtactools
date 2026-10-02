"""Building the application: settings, the database bootstrap, start-up work.

Until now nothing built the real app in a test, so none of this was covered:
the app was assembled by importing a module, and importing it loaded SAM.
"""

import logging
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import inspect  # noqa: E402

from app import create_app  # noqa: E402
from app.config import cors_origins, load_config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import AircraftProfile  # noqa: E402
from app.paths import BACKEND_DIR  # noqa: E402
from app.services.aircraft.seed import SEED_PROFILES  # noqa: E402
from app.version import __version__  # noqa: E402

SECRET = "factory-test-secret-over-thirty-two-chars"
PRODUCTION = {"TRUSTED_PROXY": "cloudflare", "JWT_SECRET_KEY": SECRET,
              "RESEND_API_KEY": "re_test", "EMAIL_FROM": "EZ/PZ <noreply@ezpztac.app>"}

BLUEPRINTS = {"export_bp", "terrain", "lidar", "location", "weather", "auth", "lz", "saved_routes",
              "point_sets", "threat", "route_share", "aircraft", "admin", "health"}


class ConfigTests(unittest.TestCase):
    def test_without_a_database_url_the_app_uses_a_local_sqlite_file_in_backend(self):
        config = load_config({})
        self.assertEqual(config["SQLALCHEMY_DATABASE_URI"],
                         "sqlite:///" + os.path.join(str(BACKEND_DIR), "ezpz.db"))
        self.assertNotIn("SQLALCHEMY_ENGINE_OPTIONS", config)      # pooling is for Postgres only

    def test_postgres_gets_a_named_driver_and_a_pool_that_survives_idle_drops(self):
        config = load_config({"DATABASE_URL": "postgres://u:p@db.example:5432/app"})
        self.assertEqual(config["SQLALCHEMY_DATABASE_URI"], "postgresql+psycopg2://u:p@db.example:5432/app")
        self.assertEqual(config["SQLALCHEMY_ENGINE_OPTIONS"], {"pool_pre_ping": True, "pool_recycle": 280})

    def test_tokens_last_a_day(self):
        from datetime import timedelta
        self.assertEqual(load_config({})["JWT_ACCESS_TOKEN_EXPIRES"], timedelta(hours=24))

    def test_the_admin_session_secret_defaults_to_the_jwt_secret(self):
        self.assertEqual(load_config({"JWT_SECRET_KEY": SECRET})["SECRET_KEY"], SECRET)
        self.assertEqual(load_config({"JWT_SECRET_KEY": SECRET, "ADMIN_SESSION_SECRET": "other"})["SECRET_KEY"],
                         "other")

    def test_admin_cookies_are_httponly_and_lax_and_secure_behind_a_declared_proxy(self):
        local = load_config({})
        self.assertTrue(local["SESSION_COOKIE_HTTPONLY"])
        self.assertEqual(local["SESSION_COOKIE_SAMESITE"], "Lax")
        self.assertFalse(local["SESSION_COOKIE_SECURE"])
        self.assertTrue(load_config(PRODUCTION)["SESSION_COOKIE_SECURE"])

    def test_production_refuses_to_start_without_its_secrets(self):
        with self.assertRaises(RuntimeError):
            load_config({"TRUSTED_PROXY": "cloudflare"})                       # no JWT secret
        with self.assertRaises(RuntimeError):
            load_config({"TRUSTED_PROXY": "cloudflare", "JWT_SECRET_KEY": SECRET})   # no email

    def test_allowed_origins_come_from_a_comma_separated_list(self):
        self.assertEqual(cors_origins({}), ["http://localhost:3000"])
        self.assertEqual(cors_origins({"CORS_ORIGINS": " https://a.example , ,https://b.example"}),
                         ["https://a.example", "https://b.example"])


class FactoryTestCase(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp(prefix="factory-"))
        self.addCleanup(shutil.rmtree, self.folder, True)
        self.db_file = self.folder / "test.db"

    def environ(self, **extra):
        return {"DATABASE_URL": f"sqlite:///{self.db_file.as_posix()}", "JWT_SECRET_KEY": SECRET, **extra}

    def build(self, **kwargs):
        kwargs.setdefault("environ", self.environ())
        kwargs.setdefault("background_tasks", False)
        app = create_app(**kwargs)

        def release():
            with app.app_context():
                db.session.remove()
                db.engine.dispose()
        self.addCleanup(release)
        return app


class CreateAppTests(FactoryTestCase):
    def test_every_blueprint_is_registered(self):
        self.assertEqual(set(self.build().blueprints), BLUEPRINTS)

    def test_the_app_is_not_a_singleton(self):
        self.assertIsNot(self.build(), self.build(environ=self.environ()))

    def test_settings_can_be_overridden_for_tests(self):
        app = self.build(config={"TESTING": True, "JWT_ACCESS_TOKEN_EXPIRES": False})
        self.assertTrue(app.config["TESTING"])
        self.assertFalse(app.config["JWT_ACCESS_TOKEN_EXPIRES"])

    def test_the_health_check_reports_the_version(self):
        client = self.build().test_client()
        self.assertEqual(client.get("/").get_json(),
                         {"status": "online", "service": "AvTacTools Backend", "version": __version__})

    def test_the_admin_host_is_sent_to_the_admin_sign_in(self):
        response = self.build().test_client().get("/", headers={"Host": "admin.ezpztac.app"})
        self.assertEqual((response.status_code, response.headers["Location"]), (302, "/admin/login"))

    def test_only_the_configured_origins_may_call_the_api(self):
        client = self.build(environ=self.environ(CORS_ORIGINS="https://ezpztac.app")).test_client()
        allowed = client.options("/api/auth/me", headers={"Origin": "https://ezpztac.app",
                                                          "Access-Control-Request-Method": "GET"})
        other = client.options("/api/auth/me", headers={"Origin": "https://evil.example",
                                                        "Access-Control-Request-Method": "GET"})
        self.assertEqual(allowed.headers.get("Access-Control-Allow-Origin"), "https://ezpztac.app")
        self.assertIsNone(other.headers.get("Access-Control-Allow-Origin"))

    def test_admin_pages_render_from_the_packaged_templates(self):
        response = self.build().test_client().get("/admin/login")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"<form", response.data)

    def test_production_settings_are_checked_when_the_app_is_built(self):
        with self.assertRaises(RuntimeError):
            create_app(environ={"TRUSTED_PROXY": "cloudflare", "DATABASE_URL": "sqlite://"},
                       background_tasks=False)


class DatabaseBootstrapTests(FactoryTestCase):
    def profile_count(self, app):
        with app.app_context():
            return AircraftProfile.query.count()

    def test_a_new_database_gets_its_tables_and_the_master_aircraft(self):
        app = self.build()
        with app.app_context():
            tables = set(inspect(db.engine).get_table_names())
        self.assertTrue({"user", "local_credential", "account_token", "login_event", "aircraft_profile",
                         "saved_route", "saved_point_set", "saved_lz"} <= tables)
        self.assertEqual(self.profile_count(app), len(SEED_PROFILES))

    def test_starting_again_adds_nothing_and_keeps_admin_edits(self):
        first = self.build()
        with first.app_context():
            profile = AircraftProfile.query.filter_by(slug="uh60l").one()
            profile.name = "Renamed by an admin"
            db.session.commit()
        second = self.build()
        self.assertEqual(self.profile_count(second), len(SEED_PROFILES))
        with second.app_context():
            self.assertEqual(AircraftProfile.query.filter_by(slug="uh60l").one().name, "Renamed by an admin")

    def test_a_database_from_an_older_version_gets_the_columns_it_lacks(self):
        """create_all never alters a table; the start-up statements add what is missing."""
        con = sqlite3.connect(self.db_file)
        con.executescript("""
            CREATE TABLE user (id INTEGER PRIMARY KEY, google_id VARCHAR(100), email VARCHAR(120) NOT NULL,
                               name VARCHAR(120) NOT NULL, created_at DATETIME);
            CREATE TABLE local_credential (user_id INTEGER PRIMARY KEY, password_hash VARCHAR(255) NOT NULL,
                                           email_verified_at DATETIME, status VARCHAR(32) NOT NULL,
                                           last_login_at DATETIME, created_at DATETIME NOT NULL,
                                           updated_at DATETIME NOT NULL);
            INSERT INTO user (id, email, name) VALUES (1, 'old@example.mil', 'Old User');
        """)
        con.commit()
        con.close()

        app = self.build()
        with app.app_context():
            columns = {c["name"] for c in inspect(db.engine).get_columns("user")}
            credential = {c["name"] for c in inspect(db.engine).get_columns("local_credential")}
            row = db.session.execute(db.text(
                'SELECT role, is_active, access_approved FROM "user" WHERE id = 1')).one()
        self.assertTrue({"picture", "role", "is_active", "features", "mil_email", "mil_verified_at",
                         "access_approved"} <= columns)
        self.assertIn("session_version", credential)
        # Everyone who existed before the affiliation gate is let through.
        self.assertEqual(tuple(row), ("user", 1, 1))


class BackgroundTaskTests(FactoryTestCase):
    def build_with_tasks(self, **env):
        with patch("app.startup.terrain_tiles.start_warming") as warming, \
                patch("app.startup.field_detection.preload_async") as preload:
            app = self.build(environ=self.environ(**env), background_tasks=True)
        return app, warming, preload

    def test_they_are_skipped_when_not_wanted(self):
        with patch("app.startup.terrain_tiles.start_warming") as warming, \
                patch("app.startup.field_detection.preload_async") as preload:
            self.build(background_tasks=False)
        warming.assert_not_called()
        preload.assert_not_called()

    def test_the_terrain_cache_is_warmed_and_the_model_preloaded_once(self):
        _, warming, preload = self.build_with_tasks()
        warming.assert_called_once()
        preload.assert_called_once()

    def test_missing_point_cloud_builds_are_called_out(self):
        with patch.dict(os.environ, {}, clear=False) as env:
            env.pop("LIDAR_BUILDER_URL", None)
            with self.assertLogs("app", level=logging.WARNING) as logs:
                self.build_with_tasks()
        self.assertTrue(any("point-cloud builds are off" in line for line in logs.output))

    def test_a_configured_build_service_is_not_complained_about(self):
        with patch.dict(os.environ, {"LIDAR_BUILDER_URL": "http://lidar-builder:8090"}):
            with self.assertNoLogs("app", level=logging.WARNING):
                self.build_with_tasks()

    def test_terrain_that_would_sit_off_the_point_cloud_is_called_out(self):
        with patch.dict(os.environ, {"LIDAR_BUILDER_URL": "http://x:1", "TERRAIN_DATA_DIR": "/data/topo"}), \
                patch("app.startup.terrain_tiles.geoid_grids_available", return_value=False):
            with self.assertLogs("app", level=logging.WARNING) as logs:
                self.build_with_tasks()
        self.assertTrue(any("geoid grids unavailable" in line for line in logs.output))


if __name__ == "__main__":
    unittest.main()
