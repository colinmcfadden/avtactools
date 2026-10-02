"""The military-affiliation gate: a signed-in user who has not cleared it reaches the auth flows and nothing else."""

import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from affiliation_gate import enforce_affiliation_gate  # noqa: E402
from auth_harness import ANDROID, NativeAuthCase  # noqa: E402
from models import User, db  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402

GATE_CODE = "affiliation_required"


class AffiliationGateTests(NativeAuthCase):
    extra_blueprints = (lz_bp, config_bp)

    def setUp(self):
        super().setUp()
        self.app.before_request(enforce_affiliation_gate)        # as app.py installs it
        self.make_account()
        self.head = {"Authorization": f'Bearer {self.login(ANDROID)["access_token"]}'}

    def approve(self, **changes):
        with self.app.app_context():
            user = User.query.filter_by(email="pilot@example.com").one()
            for key, value in changes.items():
                setattr(user, key, value)
            db.session.commit()

    def test_a_user_who_has_not_cleared_it_is_refused_everything_outside_the_auth_flows(self):
        for method, path in (("get", "/api/lz"), ("post", "/api/lz"), ("get", "/api/config")):
            response = getattr(self.client, method)(path, headers=self.head)
            self.assertEqual(response.status_code, 403, path)
            self.assertEqual(response.get_json()["code"], GATE_CODE)
        self.assertIn("Military affiliation verification", response.get_json()["error"])

    def test_the_auth_flows_stay_open_to_them(self):
        self.assertEqual(self.client.get("/api/auth/me", headers=self.head).status_code, 200)

    def test_an_approved_user_gets_through(self):
        self.approve(access_approved=True)
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 200)

    def test_a_verified_mil_address_clears_it_too(self):
        from datetime import datetime
        self.approve(mil_verified_at=datetime.utcnow())
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 200)

    def test_an_admin_is_trusted(self):
        self.approve(role="admin")
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 200)

    def test_a_request_with_no_token_is_not_the_gate_s_business(self):
        self.assertEqual(self.client.get("/api/config").status_code, 200)                # public
        self.assertEqual(self.client.get("/api/lz").status_code, 401)                    # the view's own guard

    def test_a_token_that_cannot_be_read_is_left_to_the_view(self):
        bad = {"Authorization": "Bearer not.a.token"}
        self.assertIn(self.client.get("/api/lz", headers=bad).status_code, (401, 422))

    def test_every_user_is_checked_on_every_request_not_remembered(self):
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 403)
        self.approve(access_approved=True)
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 200)
        self.approve(access_approved=False)
        self.assertEqual(self.client.get("/api/lz", headers=self.head).status_code, 403)


if __name__ == "__main__":
    import unittest
    unittest.main()
