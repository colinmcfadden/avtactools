"""dev_user.py: the account a developer signs in with against a local backend.

What matters is that the account it makes can really sign in through the production login route (with the native client header, as the apps
send), is past the `.mil` gate, and that the script will not touch anything but a local SQLite file.
"""

import os
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from flask import Flask  # noqa: E402
from flask_jwt_extended import JWTManager  # noqa: E402

import dev_user  # noqa: E402
from auth_rate_limit import clear_rate_limits  # noqa: E402
from models import LocalCredential, User, db  # noqa: E402
from routes.auth import auth_bp  # noqa: E402
from token_revocation import is_revoked  # noqa: E402

ANDROID = {"X-EZPZ-Client": "android/1.4.0 (212)"}
PASSWORD = "a secure flight password"


class DevUserTests(unittest.TestCase):
    def setUp(self):
        clear_rate_limits()
        self.folder = tempfile.TemporaryDirectory()
        self.url = "sqlite:///" + os.path.join(self.folder.name, "dev.db")
        env = patch.dict(os.environ, {"DATABASE_URL": self.url}, clear=False)
        env.start()
        self.addCleanup(env.stop)
        for var in ("TRUSTED_PROXY", "FLY_APP_NAME", "APP_ENV"):
            os.environ.pop(var, None)
        self.addCleanup(self.folder.cleanup)

    def make(self, *args, password=PASSWORD):
        dev_user.main(["--password", password, *args])

    def client(self):
        """The production login route over the same database file the script wrote."""
        app = Flask("login-check")
        app.config.update(
            TESTING=True, SQLALCHEMY_DATABASE_URI=self.url, SQLALCHEMY_TRACK_MODIFICATIONS=False,
            JWT_SECRET_KEY="test-only-secret-that-is-over-32-bytes", JWT_ACCESS_TOKEN_EXPIRES=timedelta(hours=24), GOOGLE_CLIENT_ID="x",
        )
        db.init_app(app)
        jwt = JWTManager(app)
        jwt.token_in_blocklist_loader(lambda _h, payload: is_revoked(payload))
        app.register_blueprint(auth_bp)
        return app, app.test_client()

    def login(self, client, email, password=PASSWORD):
        return client.post("/api/auth/login", json={"email": email, "password": password}, headers=ANDROID)

    def test_the_account_it_makes_signs_in_through_the_real_route_and_is_past_the_gate(self):
        self.make("Dev@Example.com")
        app, client = self.client()
        response = self.login(client, "dev@example.com")
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertTrue(body["user"]["access_ok"])
        self.assertTrue(body["user"]["is_active"])
        self.assertFalse(body["user"]["is_admin"])
        self.assertTrue(body["refresh_token"])                      # a native client gets a refresh token

    def test_the_address_is_matched_without_regard_to_case(self):
        self.make("Dev@Example.com")
        app, client = self.client()
        self.assertEqual(self.login(client, "DEV@example.COM").status_code, 200)

    def test_a_wrong_password_and_an_unknown_address_are_refused_with_the_same_words(self):
        self.make("dev@example.com")
        app, client = self.client()
        wrong = self.login(client, "dev@example.com", "not the password at all")
        unknown = self.login(client, "nobody@example.com")
        self.assertEqual((wrong.status_code, unknown.status_code), (401, 401))
        self.assertEqual(wrong.get_json()["message"], unknown.get_json()["message"])
        self.assertEqual(wrong.get_json()["message"], "Invalid email or password.")

    def test_admin_makes_an_administrator(self):
        self.make("boss@example.com", "--admin")
        app, client = self.client()
        self.assertTrue(self.login(client, "boss@example.com").get_json()["user"]["is_admin"])

    def test_running_it_again_resets_the_password_and_signs_out_earlier_sessions(self):
        self.make("dev@example.com")
        app, client = self.client()
        old_token = self.login(client, "dev@example.com").get_json()["access_token"]
        self.make("dev@example.com", password="another long password")
        with app.app_context():
            self.assertEqual(User.query.count(), 1)                 # the same account, not a second one
            self.assertEqual(LocalCredential.query.one().session_version, 2)
        self.assertEqual(self.login(client, "dev@example.com").status_code, 401)                  # the old password no longer works
        self.assertEqual(self.login(client, "dev@example.com", "another long password").status_code, 200)
        me = client.get("/api/auth/me", headers={"Authorization": f"Bearer {old_token}"})
        self.assertEqual(me.status_code, 401)                       # what was issued before is revoked

    def test_an_account_that_was_suspended_is_active_again(self):
        self.make("dev@example.com")
        app, client = self.client()
        with app.app_context():
            User.query.one().is_active = False
            db.session.commit()
        self.assertEqual(self.login(client, "dev@example.com").status_code, 403)                  # suspended: refused
        self.make("dev@example.com")
        self.assertEqual(self.login(client, "dev@example.com").status_code, 200)

    def test_an_account_stored_in_another_case_is_found_not_duplicated(self):
        app, _ = self.client()
        with app.app_context():
            db.create_all()
            db.session.add(User(email="Dev@Example.com", name="Earlier", google_id="local:earlier"))
            db.session.commit()
        self.make("dev@example.com")
        with app.app_context():
            self.assertEqual(User.query.count(), 1)
            self.assertEqual(LocalCredential.query.count(), 1)

    def test_a_weak_or_short_password_and_a_bad_address_are_refused_and_nothing_is_made(self):
        for args, password in ((["dev@example.com"], "short"), (["dev@example.com"], "password"), (["not-an-address"], PASSWORD)):
            with self.assertRaises(SystemExit):
                self.make(*args, password=password)
        app, _ = self.client()
        with app.app_context():
            db.create_all()
            self.assertEqual(User.query.count(), 0)

    def test_it_refuses_a_database_that_is_not_a_local_sqlite_file(self):
        with patch.dict(os.environ, {"DATABASE_URL": "postgresql://user:secret@db.example.com/ezpz"}):
            with self.assertRaises(SystemExit) as stopped:
                self.make("dev@example.com")
        self.assertIn("Refusing", str(stopped.exception))

    def test_it_refuses_a_host_that_looks_like_a_deployment(self):
        for var, value in (("TRUSTED_PROXY", "cloudflare"), ("FLY_APP_NAME", "backend"), ("APP_ENV", "production")):
            with patch.dict(os.environ, {var: value}):
                with self.assertRaises(SystemExit) as stopped:
                    self.make("dev@example.com")
            self.assertIn("Refusing", str(stopped.exception), var)


if __name__ == "__main__":
    unittest.main()
