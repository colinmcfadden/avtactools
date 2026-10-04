"""The local account helper is useful locally and impossible to use on a deployment."""

import sys
import unittest
from pathlib import Path

from flask import Flask
from flask_jwt_extended import JWTManager
from werkzeug.security import check_password_hash


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from dev_user import create_dev_user, register_dev_user_command  # noqa: E402
from models import AccountToken, LocalCredential, User, db  # noqa: E402
from routes.auth import auth_bp  # noqa: E402


class DevelopmentUserTests(unittest.TestCase):
    def setUp(self):
        self.app = Flask(__name__)
        self.app.config.update(
            TESTING=True,
            SQLALCHEMY_DATABASE_URI="sqlite://",
            SQLALCHEMY_TRACK_MODIFICATIONS=False,
            JWT_SECRET_KEY="test-only-secret-that-is-over-32-bytes",
        )
        db.init_app(self.app)
        JWTManager(self.app)
        self.app.register_blueprint(auth_bp)
        self.context = self.app.app_context()
        self.context.push()
        db.create_all()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.context.pop()

    def test_it_creates_a_verified_approved_account_for_the_normal_login(self):
        user = create_dev_user(
            "Pilot@Local.EZPZ.Test",
            "a local flight password",
            environ={},
        )

        self.assertEqual(user.email, "pilot@local.ezpz.test")
        self.assertTrue(user.is_active)
        self.assertTrue(user.access_approved)
        self.assertEqual(user.local_credential.status, "active")
        self.assertIsNotNone(user.local_credential.email_verified_at)
        self.assertTrue(check_password_hash(user.local_credential.password_hash, "a local flight password"))
        signed_in = self.app.test_client().post("/api/auth/login", json={
            "email": user.email,
            "password": "a local flight password",
        })
        self.assertEqual(signed_in.status_code, 200)
        self.assertTrue(signed_in.get_json()["user"]["access_ok"])

    def test_the_cli_prompts_for_the_password_and_reports_the_account(self):
        register_dev_user_command(self.app)
        result = self.app.test_cli_runner().invoke(
            args=["create-dev-user"],
            input="a local flight password\na local flight password\n",
        )

        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn("Local development account ready: pilot@local.ezpz.test", result.output)
        self.assertEqual(User.query.one().email, "pilot@local.ezpz.test")

    def test_resetting_it_replaces_the_password_and_revokes_older_tokens(self):
        user = create_dev_user("pilot@local.test", "the first local password", environ={})
        db.session.add(AccountToken(
            user_id=user.id,
            purpose="refresh",
            token_hash="old-token",
            expires_at=user.local_credential.email_verified_at,
        ))
        db.session.commit()

        updated = create_dev_user("pilot@local.test", "the second local password", environ={})

        self.assertEqual(updated.id, user.id)
        self.assertEqual(updated.local_credential.session_version, 1)
        self.assertTrue(check_password_hash(updated.local_credential.password_hash, "the second local password"))
        self.assertEqual(AccountToken.query.count(), 0)

    def test_it_refuses_every_production_signal(self):
        for environ in (
            {"APP_ENV": "production"},
            {"FLY_APP_NAME": "ezpz"},
            {"TRUSTED_PROXY": "cloudflare"},
        ):
            with self.subTest(environ=environ), self.assertRaisesRegex(ValueError, "cannot be created in production"):
                create_dev_user("pilot@local.test", "a local flight password", environ=environ)
        self.assertEqual(User.query.count(), 0)

    def test_it_requires_the_same_password_length_as_normal_accounts(self):
        with self.assertRaisesRegex(ValueError, "15 to 128"):
            create_dev_user("pilot@local.test", "too short", environ={})


if __name__ == "__main__":
    unittest.main()

