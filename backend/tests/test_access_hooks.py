"""Who is let in: token revocation and the military-affiliation gate.

These run against the real application, because the rules live in hooks the app
registers and a bare test app would not have them. They are the server-side
backstop for everything the UI hides, so each way of being refused is pinned.
"""

import os
import shutil
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from flask_jwt_extended import create_access_token  # noqa: E402

from app import create_app  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import LocalCredential, User  # noqa: E402

SECRET = "access-test-secret-over-thirty-two-chars-long"
GATED = "/api/aircraft-profiles"        # any /api route outside /api/auth/


class AccessHookTests(unittest.TestCase):
    def setUp(self):
        folder = Path(tempfile.mkdtemp(prefix="access-"))
        self.addCleanup(shutil.rmtree, folder, True)
        self.app = create_app(environ={"DATABASE_URL": f"sqlite:///{(folder / 'test.db').as_posix()}",
                                       "JWT_SECRET_KEY": SECRET}, background_tasks=False)

        def release():
            with self.app.app_context():
                db.session.remove()
                db.engine.dispose()
        self.addCleanup(release)
        self.client = self.app.test_client()

    def user(self, email="user@example.mil", *, password_account=None, **fields):
        """Create a user and return a bearer header for it."""
        with self.app.app_context():
            fields.setdefault("access_approved", True)
            user = User(email=email, name=email.split("@")[0], google_id=f"g-{email}", **fields)
            db.session.add(user)
            db.session.commit()
            version = 0
            if password_account:
                db.session.add(LocalCredential(user_id=user.id, password_hash="x",
                                               status=password_account.get("status", "active"),
                                               session_version=password_account.get("version", 0)))
                db.session.commit()
                version = password_account.get("version", 0)
            return user.id, version

    def token(self, user_id, version=0, **claims):
        with self.app.app_context():
            extra = {} if version is None else {"sv": version}
            return {"Authorization": "Bearer " + create_access_token(
                identity=str(user_id), additional_claims={**extra, **claims})}

    def get(self, path, headers=None):
        return self.client.get(path, headers=headers or {})

    # --- authentication ---------------------------------------------------------
    def test_no_token_is_refused(self):
        self.assertEqual(self.get("/api/auth/me").status_code, 401)
        self.assertEqual(self.get(GATED).status_code, 401)

    def test_a_signed_in_approved_user_is_let_through(self):
        user_id, version = self.user()
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, version)).status_code, 200)
        self.assertEqual(self.get(GATED, self.token(user_id, version)).status_code, 200)

    def test_a_token_for_a_user_who_no_longer_exists_is_refused(self):
        self.assertEqual(self.get("/api/auth/me", self.token(99999)).status_code, 401)

    def test_a_suspended_user_is_locked_out_at_once_whatever_token_they_hold(self):
        user_id, version = self.user(is_active=False)
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, version)).status_code, 401)

    def test_the_super_admin_cannot_be_locked_out(self):
        with patch.dict(os.environ, {"SUPER_ADMIN_EMAIL": "root@example.mil"}):
            user_id, version = self.user("root@example.mil", is_active=False)
            self.assertEqual(self.get("/api/auth/me", self.token(user_id, version)).status_code, 200)

    def test_a_token_from_before_session_versioning_is_refused(self):
        user_id, _ = self.user()
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, version=None)).status_code, 401)

    def test_a_password_reset_revokes_older_tokens(self):
        user_id, _ = self.user("local@example.mil", password_account={"version": 2})
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, 1)).status_code, 401)   # issued earlier
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, 2)).status_code, 200)

    def test_a_suspended_password_account_is_refused(self):
        user_id, version = self.user("local@example.mil",
                                     password_account={"status": "suspended", "version": 0})
        self.assertEqual(self.get("/api/auth/me", self.token(user_id, version)).status_code, 401)

    # --- the affiliation gate -----------------------------------------------------
    def test_an_unverified_user_can_reach_the_sign_in_flows_but_nothing_else(self):
        user_id, version = self.user("pending@example.mil", access_approved=False)
        headers = self.token(user_id, version)
        self.assertEqual(self.get("/api/auth/me", headers).status_code, 200)     # needed to verify
        response = self.get(GATED, headers)
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.get_json()["code"], "affiliation_required")

    def test_verifying_a_mil_address_clears_the_gate(self):
        user_id, version = self.user("verified@example.mil", access_approved=False,
                                     mil_verified_at=datetime.utcnow())
        self.assertEqual(self.get(GATED, self.token(user_id, version)).status_code, 200)

    def test_an_admin_clears_the_gate_without_either(self):
        user_id, version = self.user("boss@example.mil", access_approved=False, role="admin")
        self.assertEqual(self.get(GATED, self.token(user_id, version)).status_code, 200)

    def test_the_gate_leaves_non_api_pages_alone(self):
        user_id, version = self.user("pending@example.mil", access_approved=False)
        self.assertEqual(self.get("/", self.token(user_id, version)).status_code, 200)

    def test_a_garbage_token_is_left_for_the_route_to_refuse(self):
        response = self.get(GATED, {"Authorization": "Bearer not-a-token"})
        self.assertIn(response.status_code, (401, 422))
        self.assertNotEqual(response.status_code, 403)


if __name__ == "__main__":
    unittest.main()
