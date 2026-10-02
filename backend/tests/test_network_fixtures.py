"""Real server responses, recorded for the native apps' API clients.

The Android and iOS clients decode these exact bodies in their own tests (strictly: an
unknown field fails them there, so a client cannot silently lose something the server
sends) and replay them against a mock server to check their auth and retry behaviour.
They are the server's own words, not hand-written examples.

Everything that varies from run to run (tokens, timestamps, generated ids, the server
version) is replaced by a placeholder, so the file is stable. As with the other
fixtures, a run without ``UPDATE_CONTRACTS=1`` fails if the server now says something
different, and with it rewrites ``contracts/fixtures/network/responses.json``.

    cd backend; $env:UPDATE_CONTRACTS="1"; python -m pytest tests/test_network_fixtures.py
"""

import json
import os
import re
import sys
import uuid
from datetime import datetime, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import refresh_tokens  # noqa: E402
from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from routes.aircraft_routes import aircraft_bp  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402

FIXTURE = BACKEND_DIR.parent / "contracts" / "fixtures" / "network" / "responses.json"
UPDATE = os.environ.get("UPDATE_CONTRACTS") == "1"

# Placeholders for what changes from run to run.
JWT = re.compile(r"^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")
KEPT_UUIDS = set()          # identities the scenario chose itself, which are not placeholders


def normalise(value, key=None):
    if isinstance(value, dict):
        return {k: normalise(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [normalise(v, key) for v in value]
    if isinstance(value, str):
        if JWT.match(value):
            return "<access-token>"
        if key == "refresh_token":
            return "<refresh-token>"
        if key == "publicToken":
            return "pk.<token>"
        if key == "serverVersion":
            return "<version>"
        if key == "id" and re.fullmatch(r"[0-9a-f]{32}", value):
            return "<session-id>"
        if UUID.match(value) and value not in KEPT_UUIDS:
            return "<uuid>"
        if TIMESTAMP.match(value) and (key or "").endswith("_at"):
            return "<timestamp>"
    return value


class NetworkFixtureTests(NativeAuthCase):
    extra_blueprints = (config_bp, lz_bp, aircraft_bp, sync_bp)

    def setUp(self):
        super().setUp()
        self.recorded = []

    def rec(self, name, response, *headers):
        """Keep a response: its status, a few headers, and its body (JSON, or None)."""
        entry = {"name": name, "method": response.request.method, "path": response.request.path, "status": response.status_code}
        kept = {h: response.headers[h] for h in headers if h in response.headers}
        if kept:
            entry["headers"] = kept
        entry["body"] = normalise(response.get_json(silent=True))
        self.recorded.append(entry)
        return response

    @staticmethod
    def bearer(token):
        return {**ANDROID, "Authorization": f"Bearer {token}"}

    def scenario(self):
        mine = "7f1f0a52-6c4e-4b2a-9d3e-0a1b2c3d4e5f"          # chosen here, so it is not a placeholder
        KEPT_UUIDS.add(mine)

        self.rec("config", self.client.get("/api/config", headers=ANDROID), "Cache-Control")

        self.make_account()
        creds = {"email": "pilot@example.com", "password": PASSWORD}
        self.rec("login: wrong password", self.client.post("/api/auth/login", headers=ANDROID, json={**creds, "password": "wrong"}))
        login = self.rec("login: android", self.client.post("/api/auth/login", headers=ANDROID, json=creds)).get_json()
        self.rec("login: web, which gets no refresh token", self.client.post("/api/auth/login", json=creds))
        token = login["access_token"]
        self.rec("me", self.client.get("/api/auth/me", headers=self.bearer(token)))
        self.rec("sessions", self.client.get("/api/auth/sessions", headers=self.bearer(token)))
        self.rec("no token", self.client.get("/api/lz", headers=ANDROID))

        # Refresh: rotate, repeat the same request (a lost response), then a token that is long spent.
        first = self.rec("refresh", self.client.post("/api/auth/refresh", headers=ANDROID, json={"refresh_token": login["refresh_token"]})).get_json()
        self.rec("refresh: the same request again within the grace period",
                 self.client.post("/api/auth/refresh", headers=ANDROID, json={"refresh_token": login["refresh_token"]}))
        for row in self.rows():
            if row.used_at:
                self.age(row.id, used_at=datetime.utcnow() - refresh_tokens.REUSE_GRACE - timedelta(minutes=5))
        self.rec("refresh: a spent token after the grace period", self.client.post("/api/auth/refresh", headers=ANDROID, json={"refresh_token": login["refresh_token"]}))
        self.rec("refresh: not a token", self.client.post("/api/auth/refresh", headers=ANDROID, json={"refresh_token": "nonsense"}))
        # (the reuse above revoked the family, so sign in again for the rest)
        login = self.client.post("/api/auth/login", headers=ANDROID, json=creds).get_json()
        head = self.bearer(login["access_token"])
        del first

        # Saved LZs
        self.rec("lz: create", self.client.post("/api/lz", headers=head, json={"name": "LZ HAWK", "lz_data": {"schema": 2, "target": [34.5, -84.1]}, "client_uuid": mine}), "ETag")
        self.rec("lz: create again with the same identity", self.client.post("/api/lz", headers=head, json={"name": "LZ HAWK", "lz_data": {}, "client_uuid": mine}), "ETag")
        lz_id = self.client.get("/api/lz", headers=head).get_json()[0]["id"]
        self.rec("lz: list", self.client.get("/api/lz", headers=head))
        self.rec("lz: get", self.client.get(f"/api/lz/{lz_id}", headers=head), "ETag")
        self.rec("lz: update", self.client.put(f"/api/lz/{lz_id}", headers={**head, "If-Match": '"1"'}, json={"name": "LZ HAWK 2"}), "ETag")
        self.rec("lz: update on a stale revision", self.client.put(f"/api/lz/{lz_id}", headers={**head, "If-Match": '"1"'}, json={"name": "mine"}), "ETag")
        self.rec("lz: malformed If-Match", self.client.put(f"/api/lz/{lz_id}", headers={**head, "If-Match": "x"}, json={}))
        self.rec("lz: not found", self.client.get("/api/lz/9999", headers=head))

        # Aircraft profiles
        self.rec("aircraft: list with no profiles of my own", self.client.get("/api/aircraft-profiles", headers=head))
        profile = self.client.post("/api/aircraft-profiles", headers=head, json={"name": "My Hawk", "designation": "MH-60", "client_uuid": str(uuid.uuid4())})
        self.rec("aircraft: create", profile, "ETag")
        pid = profile.get_json()["id"]
        self.rec("aircraft: list", self.client.get("/api/aircraft-profiles", headers=head))
        self.rec("aircraft: update", self.client.put(f"/api/aircraft-profiles/{pid}", headers={**head, "If-Match": '"1"'}, json={"name": "Renamed"}), "ETag")
        self.rec("aircraft: update on a stale revision", self.client.put(f"/api/aircraft-profiles/{pid}", headers={**head, "If-Match": '"1"'}, json={"name": "x"}), "ETag")
        self.rec("aircraft: refused", self.client.put(f"/api/aircraft-profiles/{pid}", headers=head, json={"rotor_diameter_m": 999}))

        # The change feed, with an LZ, a profile, and a deletion
        gone = self.client.post("/api/lz", headers=head, json={"name": "gone", "lz_data": {"a": 1}}).get_json()
        self.client.delete(f"/api/lz/{gone['id']}", headers=head)
        self.rec("sync: changes", self.client.get("/api/sync/changes?since=0", headers=head), "Cache-Control")
        self.rec("sync: nothing new", self.client.get("/api/sync/changes?since=999", headers=head))
        self.rec("sync: bad cursor", self.client.get("/api/sync/changes?since=x", headers=head))

        self.rec("lz: delete", self.client.delete(f"/api/lz/{lz_id}", headers={**head, "If-Match": '"2"'}))
        self.rec("aircraft: delete", self.client.delete(f"/api/aircraft-profiles/{pid}", headers={**head, "If-Match": '"2"'}))

        # Sessions, sign-out and account deletion
        sessions = self.client.get("/api/auth/sessions", headers=head).get_json()["sessions"]
        self.rec("sessions: revoke one that is not mine", self.client.delete("/api/auth/sessions/" + "0" * 32, headers=head))
        self.rec("logout", self.client.post("/api/auth/logout", headers=head))
        self.rec("after logout, the token is refused", self.client.get("/api/auth/me", headers=head))
        self.assertTrue(sessions)

        again = self.client.post("/api/auth/login", headers=ANDROID, json=creds).get_json()
        self.rec("account deletion: refused without proof", self.client.delete("/api/auth/me", headers=self.bearer(again["access_token"]), json={"confirm": "DELETE"}))
        self.rec("account deletion", self.client.delete("/api/auth/me", headers=self.bearer(again["access_token"]), json={"confirm": "DELETE", "password": PASSWORD}))

    def test_the_recorded_responses_are_what_the_server_says(self):
        self.scenario()
        document = {
            "description": "Real responses from the Flask API (tests/test_network_fixtures.py), with tokens, timestamps, "
                           "generated ids and the server version replaced by placeholders. The native apps decode each body "
                           "with their own types, strictly, and replay them against a mock server.",
            "generatedBy": "backend/tests/test_network_fixtures.py (UPDATE_CONTRACTS=1)",
            "responses": self.recorded,
        }
        text = self.dumps(document)
        if UPDATE:
            FIXTURE.parent.mkdir(parents=True, exist_ok=True)
            FIXTURE.write_text(text, encoding="utf-8")
            return
        self.assertTrue(FIXTURE.exists(), f"{FIXTURE} is missing; run with UPDATE_CONTRACTS=1")
        committed = json.loads(FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual(committed, json.loads(text), "the server's responses changed; review the diff, then regenerate")
        self.assertGreater(len(committed["responses"]), 30)

    @staticmethod
    def dumps(document):
        """One response per line, so a regenerated file diffs response by response."""
        lines = ["{"]
        for key in ("description", "generatedBy"):
            lines.append(f"  {json.dumps(key)}: {json.dumps(document[key], ensure_ascii=False)},")
        lines.append('  "responses": [')
        rows = document["responses"]
        for i, row in enumerate(rows):
            lines.append("    " + json.dumps(row, ensure_ascii=False) + ("," if i < len(rows) - 1 else ""))
        lines.append("  ]")
        lines.append("}")
        return "\n".join(lines) + "\n"


if __name__ == "__main__":
    import unittest
    unittest.main()
