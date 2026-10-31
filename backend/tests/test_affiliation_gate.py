"""The military-affiliation gate: a signed-in user who has not cleared it reaches the auth flows and nothing else."""

import sys
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import pack_support  # noqa: E402
from affiliation_gate import enforce_affiliation_gate  # noqa: E402
from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from models import AccountToken, User, db  # noqa: E402
from routes.admin_routes import admin_bp  # noqa: E402
from routes.auth import keep_unconfirmed_mil_addresses_pending  # noqa: E402
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


class MilCase(NativeAuthCase):
    """A signed-in account behind the gate, with the codes the .mil flow emails kept by address."""

    extra_blueprints = (lz_bp, config_bp, admin_bp)

    def setUp(self):
        super().setUp()
        self.app.before_request(enforce_affiliation_gate)
        self.codes = {}
        mailer = patch("routes.auth.send_mil_verification_email",
                       side_effect=lambda address, code, _name: self.codes.__setitem__(address, code) or True)
        mailer.start()
        self.addCleanup(mailer.stop)
        self.make_account()
        self.head = self.sign_in()

    def sign_in(self, email="pilot@example.com"):
        return {"Authorization": f'Bearer {self.login(ANDROID, email=email)["access_token"]}'}

    def ask(self, address, head=None):
        return self.client.post("/api/auth/mil/request", headers=head or self.head, json={"email": address})

    def enter(self, address, head=None):
        return self.client.post("/api/auth/mil/verify", headers=head or self.head, json={"code": self.codes[address]})

    def verify(self, address, head=None):
        self.assertEqual(self.ask(address, head).status_code, 202)
        self.assertEqual(self.enter(address, head).status_code, 200)

    def account(self, email="pilot@example.com"):
        """What is stored of the account's .mil address, and the addresses an emailed invitation reaches it at."""
        with self.app.app_context():
            user = User.query.filter_by(email=email).one()
            return {"mil_email": user.mil_email, "pending": user.mil_pending_email,
                    "verified_at": user.mil_verified_at, "addresses": pack_support.addresses(user)}

    def through_the_gate(self, head=None):
        return self.client.get("/api/lz", headers=head or self.head).status_code == 200

    def store(self, email="pilot@example.com", **columns):
        with self.app.app_context():
            user = User.query.filter_by(email=email).one()
            for key, value in columns.items():
                setattr(user, key, value)
            db.session.commit()


class MilAddressTests(MilCase):
    """A .mil address is the account's, for the gate and for invitations, only once the code sent to it is entered."""

    def test_an_address_asked_for_waits_for_its_code(self):
        self.assertEqual(self.ask("Pilot@Army.mil").status_code, 202)
        self.assertEqual(self.account(), {"mil_email": None, "pending": "pilot@army.mil", "verified_at": None,
                                          "addresses": {"pilot@example.com"}})
        self.assertFalse(self.through_the_gate())
        # The clients' .mil screen opens on the code step when there is an address waiting for one.
        me = self.client.get("/api/auth/me", headers=self.head).get_json()
        self.assertEqual((me["mil_email"], me["affiliation_verified"], me["access_ok"]), ("pilot@army.mil", False, False))

        answer = self.enter("pilot@army.mil")
        self.assertEqual(answer.status_code, 200)
        self.assertEqual((answer.get_json()["user"]["mil_email"], answer.get_json()["user"]["access_ok"]),
                         ("pilot@army.mil", True))
        account = self.account()
        self.assertEqual((account["mil_email"], account["pending"]), ("pilot@army.mil", None))
        self.assertIsNotNone(account["verified_at"])
        self.assertEqual(account["addresses"], {"pilot@example.com", "pilot@army.mil"})
        self.assertTrue(self.through_the_gate())

    def test_a_cleared_account_gains_nothing_by_asking_for_another_address(self):
        self.verify("pilot@army.mil")
        cleared = self.account()

        self.assertEqual(self.ask("victim@army.mil").status_code, 202)
        wrong = self.client.post("/api/auth/mil/verify", headers=self.head, json={"code": "ABCDEFGH"})
        self.assertEqual(wrong.status_code, 400)

        # Still cleared, still trusted with the address it proved, and with nothing more.
        self.assertEqual(self.account(), {**cleared, "pending": "victim@army.mil"})
        self.assertTrue(self.through_the_gate())
        me = self.client.get("/api/auth/me", headers=self.head).get_json()
        self.assertEqual((me["mil_email"], me["affiliation_verified"], me["access_ok"]), ("pilot@army.mil", True, True))

    def test_entering_the_new_address_s_code_moves_the_account_to_it(self):
        self.verify("pilot@army.mil")
        self.verify("pilot.new@army.mil")
        account = self.account()
        self.assertEqual((account["mil_email"], account["pending"]), ("pilot.new@army.mil", None))
        self.assertEqual(account["addresses"], {"pilot@example.com", "pilot.new@army.mil"})

    def test_a_code_proves_only_the_address_it_was_sent_to(self):
        self.assertEqual(self.ask("first@army.mil").status_code, 202)
        self.assertEqual(self.ask("second@army.mil").status_code, 202)
        refused = self.enter("first@army.mil")
        self.assertEqual((refused.status_code, refused.get_json()["code"]), (400, "invalid_code"))
        self.assertIsNone(self.account()["mil_email"])
        self.assertEqual(self.enter("second@army.mil").status_code, 200)
        self.assertEqual(self.account()["mil_email"], "second@army.mil")

    def test_a_code_with_no_address_pending_proves_nothing(self):
        self.assertEqual(self.ask("pilot@army.mil").status_code, 202)
        self.store(mil_pending_email=None)
        refused = self.enter("pilot@army.mil")
        self.assertEqual((refused.status_code, refused.get_json()["code"]), (400, "invalid_code"))
        self.assertEqual(self.account()["verified_at"], None)

    def test_an_address_another_account_has_verified_is_refused(self):
        self.make_account("colin@example.com")
        colin = self.sign_in("colin@example.com")
        self.assertEqual(self.ask("shared@army.mil").status_code, 202)       # asked first...
        mine = self.codes["shared@army.mil"]
        self.verify("shared@army.mil", colin)                               # ...but confirmed elsewhere first
        late = self.client.post("/api/auth/mil/verify", headers=self.head, json={"code": mine})
        self.assertEqual(late.status_code, 409)
        self.assertEqual(self.ask("shared@army.mil").status_code, 409)
        self.assertEqual(self.account()["addresses"], {"pilot@example.com"})
        self.assertFalse(self.through_the_gate())

    def test_an_address_another_account_signs_in_with_is_refused_once_its_code_is_entered(self):
        self.make_account("sam@army.mil")
        # Asking is not refused: that would tell anyone signed in which .mil addresses have an account.
        self.assertEqual(self.ask("sam@army.mil").status_code, 202)
        refused = self.enter("sam@army.mil")
        self.assertEqual(refused.status_code, 409)
        self.assertIn("sign-in address", refused.get_json()["error"])
        self.assertEqual(self.account()["addresses"], {"pilot@example.com"})

    def test_a_sign_up_never_confirmed_does_not_keep_an_address_from_its_owner(self):
        self.assertEqual(self.client.post("/api/auth/register", json={
            "name": "Squatter", "email": "sam@army.mil", "password": PASSWORD}).status_code, 202)
        self.verify("sam@army.mil")
        self.assertEqual(self.account()["mil_email"], "sam@army.mil")

    def test_the_admin_page_tells_the_confirmed_address_from_the_one_waiting(self):
        self.verify("pilot@army.mil")
        self.assertEqual(self.ask("victim@army.mil").status_code, 202)
        self.make_account("admin@example.com")
        self.store("admin@example.com", role="admin")
        self.app.secret_key = "test-only-admin-session-key"
        self.app.root_path = str(BACKEND_DIR)
        with self.app.app_context():
            admin_id, pilot_id = (User.query.filter_by(email=e).one().id for e in ("admin@example.com", "pilot@example.com"))
        dashboard = self.app.test_client()
        with dashboard.session_transaction() as session:
            session["admin_uid"] = admin_id
        page = dashboard.get(f"/admin/users/{pilot_id}").get_data(as_text=True)
        self.assertIn('<div class="mono">pilot@army.mil</div>', page)
        self.assertIn("victim@army.mil <span class=\"muted\">(not entered)</span>", page)


class UnconfirmedMilAddressTests(MilCase):
    """Accounts the old flow left trusted with an address no code confirmed are put right at startup, and only those."""

    def test_an_address_asked_for_before_the_fix_waits_for_its_code(self):
        self.assertEqual(self.ask("pilot@army.mil").status_code, 202)
        self.store(mil_email="pilot@army.mil", mil_pending_email=None)            # as the old flow left it
        with self.app.app_context():
            self.assertEqual(keep_unconfirmed_mil_addresses_pending(), 1)
        self.assertEqual(self.account(), {"mil_email": None, "pending": "pilot@army.mil", "verified_at": None,
                                          "addresses": {"pilot@example.com"}})
        self.assertEqual(self.enter("pilot@army.mil").status_code, 200)            # its code still works
        self.assertEqual(self.account()["mil_email"], "pilot@army.mil")

    def test_an_address_asked_for_after_verifying_is_no_longer_trusted(self):
        self.verify("pilot@army.mil")
        verified_at = self.account()["verified_at"]
        self.assertEqual(self.ask("victim@army.mil").status_code, 202)
        self.store(mil_email="victim@army.mil", mil_pending_email=None)            # as the old flow left it
        self.assertIn("victim@army.mil", self.account()["addresses"])

        with self.app.app_context():
            self.assertEqual(keep_unconfirmed_mil_addresses_pending(), 1)
        # Which address it verified was never kept, so neither is trusted; the account stays cleared.
        self.assertEqual(self.account(), {"mil_email": None, "pending": "victim@army.mil", "verified_at": verified_at,
                                          "addresses": {"pilot@example.com"}})
        self.assertTrue(self.through_the_gate())
        with self.app.app_context():
            self.assertEqual(keep_unconfirmed_mil_addresses_pending(), 0)

    def test_a_code_asked_twice_after_verifying_is_found_too(self):
        self.verify("pilot@army.mil")
        for address in ("victim@army.mil", "victim.two@army.mil"):
            self.assertEqual(self.ask(address).status_code, 202)
        self.store(mil_email="victim.two@army.mil", mil_pending_email=None)
        with self.app.app_context():
            self.assertEqual(keep_unconfirmed_mil_addresses_pending(), 1)
        self.assertEqual(self.account()["pending"], "victim.two@army.mil")

    def test_what_the_flow_writes_now_is_left_alone(self):
        self.verify("pilot@army.mil")                                               # confirmed
        self.make_account("colin@example.com")
        colin = self.sign_in("colin@example.com")
        self.verify("colin@army.mil", colin)
        self.assertEqual(self.ask("colin.new@army.mil", colin).status_code, 202)   # confirmed, and another asked since
        self.make_account("sam@example.com")
        self.assertEqual(self.ask("sam@army.mil", self.sign_in("sam@example.com")).status_code, 202)   # only asked
        before = [self.account(e) for e in ("pilot@example.com", "colin@example.com", "sam@example.com")]
        with self.app.app_context():
            self.assertEqual(keep_unconfirmed_mil_addresses_pending(), 0)
            self.assertGreater(AccountToken.query.filter_by(purpose="mil_verify").count(), 0)
        self.assertEqual([self.account(e) for e in ("pilot@example.com", "colin@example.com", "sam@example.com")], before)


if __name__ == "__main__":
    import unittest
    unittest.main()
