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

import io
import json
import math
import os
import re
import sys
import tempfile
import uuid
from datetime import datetime, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from unittest.mock import MagicMock, patch  # noqa: E402

import cv2  # noqa: E402
import mercantile  # noqa: E402
import requests  # noqa: E402
import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from rasterio.transform import from_origin  # noqa: E402
from werkzeug.security import generate_password_hash  # noqa: E402

import refresh_tokens  # noqa: E402
from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from models import LocalCredential, MissionPackInvite, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.aircraft_routes import aircraft_bp  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.pack_routes import pack_bp  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402
from routes.saved_routes import saved_routes_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from routes.team_routes import team_bp  # noqa: E402
from routes.weather_routes import weather_bp  # noqa: E402
from terrain_provider import LocalRasterCatalog  # noqa: E402

# The terrain blueprint loads the SAM model when it is imported, which downloads 350 MB when the weights are missing. The model is
# stood in for below, where the route is asked; none of the weights are needed to import it.
if "routes.terrain_routes" not in sys.modules:
    sys.modules.setdefault("ultralytics", MagicMock())
from routes.terrain_routes import terrain_bp  # noqa: E402
from routes.threat_routes import threat_bp  # noqa: E402

FIXTURE = BACKEND_DIR.parent / "contracts" / "fixtures" / "network" / "responses.json"
UPDATE = os.environ.get("UPDATE_CONTRACTS") == "1"
FLOAT_TOLERANCE = 1e-12


def settle_floats(committed, fresh):
    """``fresh``, with every non-integer number that is within FLOAT_TOLERANCE (relative) of the committed one
    at the same place replaced by the committed one. Anything else that differs is left to fail the comparison."""
    if isinstance(fresh, float) and isinstance(committed, (int, float)) and not isinstance(committed, bool):
        return committed if math.isclose(fresh, committed, rel_tol=FLOAT_TOLERANCE, abs_tol=FLOAT_TOLERANCE) else fresh
    if isinstance(fresh, dict) and isinstance(committed, dict):
        return {key: settle_floats(committed[key], value) if key in committed else value for key, value in fresh.items()}
    if isinstance(fresh, list) and isinstance(committed, list) and len(fresh) == len(committed):
        return [settle_floats(old, new) for old, new in zip(committed, fresh)]
    return fresh

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
        if key == "token":
            return "<token>"                                        # a team's single-use invitation link
        if key == "id" and re.fullmatch(r"[0-9a-f]{32}", value):
            return "<session-id>"
        if UUID.match(value) and value not in KEPT_UUIDS:
            return "<uuid>"
        if TIMESTAMP.match(value) and (key or "").endswith("_at"):
            return "<timestamp>"
    return value


SPEC = load_spec()
SPEC_PATHS = [(re.compile("^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(template)) + "$"), template) for template in SPEC["paths"]]


def documented(method, path, status):
    """(spec path, status is documented) for a route the contract describes, else None."""
    for pattern, template in SPEC_PATHS:
        if pattern.match(path):
            operation = SPEC["paths"][template].get(method.lower())
            if operation is None:
                return None
            return template, str(status) in operation["responses"]
    return None


class NetworkFixtureTests(NativeAuthCase):
    extra_blueprints = (config_bp, lz_bp, saved_routes_bp, point_sets_bp, aircraft_bp, sync_bp, terrain_bp, threat_bp, weather_bp,
                        pack_bp, team_bp)

    def setUp(self):
        super().setUp()
        self.recorded = []
        self.mil_codes = []
        mil = patch("routes.auth.send_mil_verification_email", side_effect=lambda _email, code, _name: self.mil_codes.append(code) or True)
        mil.start()
        self.addCleanup(mil.stop)
        # The mission packs' mail is replaced as the auth mail is: the routes run as in production up to the mail call.
        for sender in ("routes.pack_routes.send_pack_invite_email", "routes.pack_routes.send_pack_added_email",
                       "routes.team_routes.send_team_invite_email"):
            mail = patch(sender, return_value=True)
            mail.start()
            self.addCleanup(mail.stop)

    def rec(self, name, response, *headers):
        """Keep a response: its status, a few headers, and its body (JSON, or None)."""
        entry = {"name": name, "method": response.request.method, "path": response.request.path, "status": response.status_code}
        kept = {h: response.headers[h] for h in headers if h in response.headers}
        if kept:
            entry["headers"] = kept
        entry["body"] = normalise(response.get_json(silent=True))
        self.recorded.append(entry)
        # A recorded response of a route the contract describes must be one the contract describes: the apps decode
        # what the spec says, so a status or a body it does not cover is a gap in the spec (or a change in the server).
        found = documented(response.request.method, response.request.path, response.status_code)
        if found is not None and response.get_json(silent=True) is not None:
            template, known = found
            if not known and self.cross_cutting(response):
                return response
            self.assertTrue(known, f"{name}: {response.status_code} from {response.request.method} {template} is not in contracts/openapi.yaml")
            body = response.get_json()
            problems = check_response(SPEC, template, response.request.method, response.status_code, body)
            self.assertEqual(problems, [], f"{name}: {template} {response.status_code} does not match the contract")
        return response

    @staticmethod
    def cross_cutting(response):
        """Answers any route behind a token can give, described once in the spec's introduction, not per route."""
        body = response.get_json(silent=True) or {}
        if response.status_code in (401, 422) and set(body) == {"msg"}:
            return True                                                        # the JWT library's refusal of a token
        if response.status_code == 403 and body.get("code") == "affiliation_required":
            return True                                                        # the .mil gate
        if response.status_code == 403 and body.get("code") == "feature_disabled":
            return True                                                        # an entitlement the account lacks (mission_packs, on every pack and team route)
        return False

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

        # Saved routes: a set of sketched routes under one name, sent as multipart/form-data as the web does
        route_uuid = "5c2b7e90-1d3a-4f6b-8a21-3e9d0c4b7a18"
        KEPT_UUIDS.add(route_uuid)
        sketches = json.dumps({"version": 1, "routes": [{"id": "sketch-1", "name": "ROUTE 1", "color": "#FF453A", "visible": True, "points": [
            {"id": "p1", "lat": 34.5, "lon": -84.2, "ele": None, "kind": "amps", "ptType": "target", "name": ".TGT", "role": "start"},
            {"id": "p2", "lat": 34.6, "lon": -84.0, "ele": None, "kind": "amps", "ptType": "target", "name": ".TGT", "role": "waypoint"}]}]})
        form = {"content_type": "multipart/form-data"}
        self.rec("route: create", self.client.post("/api/routes", headers=head, data={"name": "ROUTES", "kind": "sketch", "route_data": sketches, "client_uuid": route_uuid}, **form), "ETag")
        self.rec("route: create again with the same identity", self.client.post("/api/routes", headers=head, data={"name": "ROUTES", "kind": "sketch", "route_data": "{}", "client_uuid": route_uuid}, **form), "ETag")
        self.rec("route: a mission needs its file", self.client.post("/api/routes", headers=head, data={"name": "m", "kind": "mission", "route_data": "{}"}, **form))
        self.rec("route: not a kind", self.client.post("/api/routes", headers=head, data={"name": "m", "kind": "other", "route_data": "{}"}, **form))
        self.rec("route: not JSON", self.client.post("/api/routes", headers=head, data={"name": "m", "route_data": "{"}, **form))
        self.rec("route: nothing sent", self.client.post("/api/routes", headers=head, data={}, **form))
        route_id = self.client.get("/api/routes", headers=head).get_json()[0]["id"]
        self.rec("route: list", self.client.get("/api/routes", headers=head))
        self.rec("route: get", self.client.get(f"/api/routes/{route_id}", headers=head), "ETag")
        self.rec("route: update", self.client.put(f"/api/routes/{route_id}", headers={**head, "If-Match": '"1"'}, data={"name": "ROUTES 2"}, **form), "ETag")
        self.rec("route: update on a stale revision", self.client.put(f"/api/routes/{route_id}", headers={**head, "If-Match": '"1"'}, data={"name": "mine"}, **form), "ETag")
        self.rec("route: not found", self.client.get("/api/routes/9999", headers=head))

        # Saved point sets: the points of an .LPS import, as the web parses them (the server does not look inside)
        set_uuid = "9e41d6a3-7b20-4c58-b3f1-0a6d82c5e9b4"
        KEPT_UUIDS.add(set_uuid)
        points = [
            {"id": "lps-0-ab12cd", "name": "BLUE 1", "description": "Landing zone", "group": "LZ", "icon": "lz", "elevationFt": 1730.0, "lat": 34.5123, "lon": -84.2231},
            {"id": "lps-1-ef34gh", "name": "FARP", "description": "", "group": "Default", "icon": "", "elevationFt": None, "lat": 34.601, "lon": -84.119},
        ]
        self.rec("pointset: create", self.client.post("/api/pointsets", headers=head, json={"name": "NORTH GA", "points": points, "client_uuid": set_uuid}), "ETag")
        self.rec("pointset: create again with the same identity", self.client.post("/api/pointsets", headers=head, json={"name": "NORTH GA", "points": points, "client_uuid": set_uuid}), "ETag")
        self.rec("pointset: no points", self.client.post("/api/pointsets", headers=head, json={"name": "EMPTY", "points": []}))
        self.rec("pointset: no name", self.client.post("/api/pointsets", headers=head, json={"points": points}))
        self.rec("pointset: not a UUID", self.client.post("/api/pointsets", headers=head, json={"name": "x", "points": points, "client_uuid": "nope"}))
        set_id = self.client.get("/api/pointsets", headers=head).get_json()[0]["id"]
        self.rec("pointset: list", self.client.get("/api/pointsets", headers=head))
        self.rec("pointset: get", self.client.get(f"/api/pointsets/{set_id}", headers=head), "ETag")
        self.rec("pointset: update", self.client.put(f"/api/pointsets/{set_id}", headers={**head, "If-Match": '"1"'}, json={"name": "NORTH GA 2"}), "ETag")
        self.rec("pointset: update on a stale revision", self.client.put(f"/api/pointsets/{set_id}", headers={**head, "If-Match": '"1"'}, json={"name": "mine"}), "ETag")
        self.rec("pointset: not found", self.client.get("/api/pointsets/9999", headers=head))

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
        self.rec("route: delete", self.client.delete(f"/api/routes/{route_id}", headers={**head, "If-Match": '"2"'}))
        self.rec("pointset: delete", self.client.delete(f"/api/pointsets/{set_id}", headers={**head, "If-Match": '"2"'}))
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

    def accounts(self):
        """Sign-up, verification, password reset and the .mil affiliation gate: the screens a new user meets first."""
        a = {**ANDROID, "Content-Type": "application/json"}
        email = "new.pilot@example.com"
        self.rec("register", self.client.post("/api/auth/register", headers=a, json={"name": "New Pilot", "email": email}))
        self.rec("register: the same address again looks the same", self.client.post("/api/auth/register", headers=a, json={"name": "New Pilot", "email": email}))
        self.rec("register: not an email address", self.client.post("/api/auth/register", headers=a, json={"name": "New Pilot", "email": "nope"}))
        self.rec("register: no name", self.client.post("/api/auth/register", headers=a, json={"name": "", "email": "x@example.com"}))
        token = self.tokens[-1]
        self.rec("verify-email: a link that is not valid", self.client.post("/api/auth/verify-email", headers=a, json={"token": "nonsense", "password": PASSWORD}))
        self.rec("verify-email: a password that is too weak", self.client.post("/api/auth/verify-email", headers=a, json={"token": token, "password": "short"}))
        self.rec("verify-email", self.client.post("/api/auth/verify-email", headers=a, json={"token": token, "password": PASSWORD}))
        self.rec("login: right after verifying", self.client.post("/api/auth/login", headers=a, json={"email": email, "password": PASSWORD}))
        self.rec("resend-verification", self.client.post("/api/auth/resend-verification", headers=a, json={"email": email}))
        self.rec("forgot-password", self.client.post("/api/auth/forgot-password", headers=a, json={"email": email}))
        self.rec("forgot-password: an address nobody has looks the same", self.client.post("/api/auth/forgot-password", headers=a, json={"email": "nobody@example.com"}))
        reset = self.tokens[-1]
        self.rec("reset-password: a password that is too weak", self.client.post("/api/auth/reset-password", headers=a, json={"token": reset, "password": "short"}))
        self.rec("reset-password: a link that is not valid", self.client.post("/api/auth/reset-password", headers=a, json={"token": "nonsense", "password": PASSWORD}))
        self.rec("reset-password", self.client.post("/api/auth/reset-password", headers=a, json={"token": reset, "password": PASSWORD + " 2"}))
        for _ in range(2):                                                    # three an hour for one address, then it refuses
            self.client.post("/api/auth/resend-verification", headers=a, json={"email": email})
        self.rec("too many attempts", self.client.post("/api/auth/resend-verification", headers=a, json={"email": email}), "Retry-After")

        # The .mil gate: a signed-in account that has not cleared it.
        session = self.client.post("/api/auth/login", headers=a, json={"email": email, "password": PASSWORD + " 2"}).get_json()
        head = self.bearer(session["access_token"])
        self.rec("mil: me before the gate is cleared", self.client.get("/api/auth/me", headers=head))
        self.rec("mil/request: not a .mil address", self.client.post("/api/auth/mil/request", headers=head, json={"email": "pilot@example.com"}))
        self.rec("mil/request", self.client.post("/api/auth/mil/request", headers=head, json={"email": "new.pilot@example.mil"}))
        self.rec("mil/verify: the wrong code", self.client.post("/api/auth/mil/verify", headers=head, json={"code": "000000"}))
        self.rec("mil/verify", self.client.post("/api/auth/mil/verify", headers=head, json={"code": self.mil_codes[-1]}))

    def terrain(self):
        """Analysing a landing zone. The segmentation model and the network are stood in for (the weights are 350 MB and the tile and
        elevation services are other people's); the route code, the slope maths over a real GeoTIFF and the response are the server's own."""
        self.make_account(email="analyst@example.com")
        head = self.bearer(self.login(ANDROID, "analyst@example.com")["access_token"])

        tile_png = cv2.imencode(".png", np.full((256, 256, 3), 90, np.uint8))[1].tobytes()

        class Reply:
            def __init__(self, status=200, body=None, content=b""):
                self.status_code, self._body, self.content = status, body, content

            def json(self):
                return self._body

        def sam(masks):
            """A model whose one answer is the given pixel polygon, or no mask at all."""
            result = MagicMock()
            result.masks = None if masks is None else MagicMock(xy=[np.array(masks, dtype=float)])
            return MagicMock(predict=MagicMock(return_value=[result]))

        outline = [[100, 90], [160, 92], [165, 150], [105, 155]]            # pixels of the 256-pixel tile
        elevation = Reply(body={"results": [{"elevation": 1234.5}]})

        def services(tile_status=200):
            def get(url, **_kw):
                return elevation if "opentopodata" in url else Reply(tile_status, content=tile_png)
            return patch("routes.terrain_routes.requests.get", side_effect=get)

        target = {"lat": 34.0965, "lon": -117.095}
        with services(), patch("routes.terrain_routes.model", sam(outline)):
            self.rec("analyze-field", self.client.post("/api/analyze-field", headers=head, json=target))
        with services(), patch("routes.terrain_routes.model", sam(None)):
            self.rec("analyze-field: no distinct area at the point", self.client.post("/api/analyze-field", headers=head, json=target))
        with services(404), patch("routes.terrain_routes.model", sam(outline)):
            self.rec("analyze-field: the map tile is unavailable", self.client.post("/api/analyze-field", headers=head, json=target))
        self.rec("analyze-field: not coordinates", self.client.post("/api/analyze-field", headers=head, json={"lat": "x", "lon": 1}))

        # Slope over a real GeoTIFF: ~11 m cells rising east, with a hill in one corner, so every statistic has something to say.
        rows, cols = np.mgrid[0:120, 0:120]
        dem = (300.0 + 0.5 * cols + 6.0 * np.exp(-(((rows - 90) ** 2) + ((cols - 90) ** 2)) / 200.0)).astype(np.float32)
        polygon = [[34.098, -117.097], [34.098, -117.093], [34.094, -117.093], [34.094, -117.097]]
        with tempfile.TemporaryDirectory() as directory:
            with rasterio.open(Path(directory) / "lz.tif", "w", driver="GTiff", height=120, width=120, count=1, dtype="float32",
                               crs="EPSG:4326", transform=from_origin(-117.10, 34.10, 0.0001, 0.0001), nodata=-9999) as out:
                out.write(dem, 1)
            env = {"TERRAIN_DATA_DIR": directory, "TERRAIN_SOURCE": "local"}
            with patch.dict(os.environ, env, clear=False), patch("terrain_provider.LOCAL_CATALOG", LocalRasterCatalog()):
                self.rec("terrain-analysis", self.client.post("/api/terrain-analysis", headers=head, json={"polygon": polygon, "landingHeading": 270}))
                self.rec("terrain-analysis: no landing heading", self.client.post("/api/terrain-analysis", headers=head, json={"polygon": polygon}))
                self.rec("terrain-analysis: a heading that is not a number",
                         self.client.post("/api/terrain-analysis", headers=head, json={"polygon": polygon, "landingHeading": "abc"}))
                self.rec("terrain-analysis: no terrain source covers the polygon",
                         self.client.post("/api/terrain-analysis", headers=head, json={"polygon": [[40.0, -100.0], [40.0, -99.99], [40.01, -99.99]]}))
        self.rec("terrain-analysis: too few points", self.client.post("/api/terrain-analysis", headers=head, json={"polygon": [[1, 2], [3, 4]]}))

    def threat_mask(self):
        """What a threat's radars can see. The elevation tiles are other people's, so the terrain is stood in for (a ridge between the radar and the east, over
        a gentle slope); the route, the viewshed maths, the colouring and the response are the server's own."""
        self.make_account(email="threats@example.com")
        head = self.bearer(self.login(ANDROID, "threats@example.com")["access_token"])

        size = 160
        rows, cols = np.mgrid[0:size, 0:size]
        dem = (300.0 + 0.1 * cols + 90.0 * np.exp(-((cols - 110) ** 2) / 40.0)).astype(np.float32)        # a north-south ridge east of the radar
        meta = {"radar_row": 80.0, "radar_col": 50.0, "mpp": 60.0, "south": 34.50, "west": -84.60, "north": 34.60, "east": -84.50}

        def radar(kind, range_nmi, show_mask=True, bands=None, antenna_ft=30.0):
            return {
                "type": kind, "rangeNmi": range_nmi, "antennaHeightFt": antenna_ft, "aglNotMsl": True, "showMask": show_mask, "showRangeRings": True,
                "bands": bands if bands is not None else [
                    {"altFt": 100, "color": "#FF0000", "alpha": 0.4, "colorIndex": 0, "viewable": True},
                    {"altFt": 500, "color": "#00FF00", "alpha": 0.35, "colorIndex": 1, "viewable": True},
                    {"altFt": 2000, "color": "#0000FF", "alpha": 0.3, "colorIndex": 2, "viewable": False},
                ],
            }

        place = {"lat": 34.55, "lon": -84.57}
        with patch("routes.threat_routes.fetch_dem", return_value=(dem, meta)):
            self.rec("threat-mask", self.client.post("/api/threat-mask", headers=head, json={**place, "radars": [radar(0, 2.0), radar(1, 1.0, show_mask=False)]}))
            self.rec("threat-mask: nothing to show", self.client.post("/api/threat-mask", headers=head, json={**place, "radars": [radar(0, 2.0, show_mask=False)]}))
            self.rec("threat-mask: no radar sees anything at a band that is not viewable",
                     self.client.post("/api/threat-mask", headers=head, json={**place, "radars": [radar(0, 2.0, bands=[])]}))
            self.rec("threat-mask: no radars", self.client.post("/api/threat-mask", headers=head, json={**place, "radars": []}))
            self.rec("threat-mask: a range that is not positive", self.client.post("/api/threat-mask", headers=head, json={**place, "radars": [radar(0, 0.0)]}))
            self.rec("threat-mask: no longitude", self.client.post("/api/threat-mask", headers=head, json={"lat": 34.55, "radars": [radar(0, 2.0)]}))
        with patch("routes.threat_routes.fetch_dem", return_value=(None, None)):
            self.rec("threat-mask: no terrain for the area", self.client.post("/api/threat-mask", headers=head, json={**place, "radars": [radar(0, 2.0)]}))

    def planning(self):
        """Planning a route: ground elevations and the wind at each point. The elevation tiles and the weather service are other people's, so
        what they answer is stood in for; the routes, the choice of station and of observation or forecast, and the responses are the server's own."""
        self.make_account(email="planner@example.com")
        head = self.bearer(self.login(ANDROID, "planner@example.com")["access_token"])

        class Reply:
            def __init__(self, status=200, body=None, content=b""):
                self.status_code, self._body, self.content = status, body, content

            def json(self):
                return self._body

        # Terrarium encodes metres as R*256 + G + B/256 - 32768: this tile is 400 m (1312 ft) everywhere. OpenCV writes blue, green, red.
        tile = cv2.imencode(".png", np.full((256, 256, 3), (0, 144, 129), np.uint8))[1].tobytes()
        unreadable = mercantile.tile(-100.0, 40.0, 13)

        def tiles(url, **_kw):
            return Reply(404) if f"/13/{unreadable.x}/{unreadable.y}.png" in url else Reply(200, content=tile)

        points = [{"lat": 34.7, "lon": -84.1}, {"lat": 40.0, "lon": -100.0}]
        with patch("routes.terrain_routes.requests.get", side_effect=tiles):
            self.rec("elevations", self.client.post("/api/elevations", headers=head, json={"points": points}))
            self.rec("elevations: no points", self.client.post("/api/elevations", headers=head, json={"points": []}))
            self.rec("elevations: a point that is not one", self.client.post("/api/elevations", headers=head, json={"points": [{"lat": "x", "lon": 1}]}))
        with patch("routes.terrain_routes._sample_elevations_ft", side_effect=RuntimeError("the tile service fell over")):
            self.rec("elevations: sampling fails", self.client.post("/api/elevations", headers=head, json={"points": points}))

        # Two stations: the first reports 270 at 12 kt, the second a variable wind. Only the first has a forecast, for a time far in the future.
        far = 4_070_908_800                                                       # 2099-01-01T00:00:00Z
        metars = [
            {"icaoId": "KRYY", "lat": 34.01, "lon": -84.6, "wdir": 270, "wspd": 12, "temp": 18},
            {"icaoId": "KCNI", "lat": 34.31, "lon": -84.42, "wdir": "VRB", "wspd": 3, "temp": 16},
        ]
        tafs = [{"icaoId": "KRYY", "lat": 34.01, "lon": -84.6, "fcsts": [{"timeFrom": far - 86400, "timeTo": far + 86400, "wdir": 300, "wspd": 20}]}]

        def weather(observed=True):
            def get(url, **_kw):
                if "/metar" in url:
                    return Reply(200, metars if observed else [])
                return Reply(200, tafs if observed else [])
            return patch("routes.weather_routes.requests.get", side_effect=get)

        asked = [
            {"id": "p1", "lat": 34.0, "lon": -84.6},                                             # no time: the observation
            {"id": "p2", "lat": 34.05, "lon": -84.55, "time": "2099-01-01T00:00:00.000Z"},       # far ahead: the forecast
            {"id": "p3", "lat": 34.3, "lon": -84.4, "time": "2020-01-01T00:00:00.000Z"},         # in the past: the observation, the second station
            {"id": "p4", "lat": 34.3, "lon": -84.4, "time": "2099-01-01T00:00:00.000Z"},         # far ahead, but the nearest forecast is the first station's
        ]
        with weather():
            self.rec("route-winds", self.client.post("/api/route-winds", headers=head, json={"points": asked}))
            self.rec("route-winds: no points", self.client.post("/api/route-winds", headers=head, json={"points": []}))
        with weather(observed=False):
            self.rec("route-winds: no station answers", self.client.post("/api/route-winds", headers=head, json={"points": asked}))
        self.rec("route-winds: not a list of points", self.client.post("/api/route-winds", headers=head, json={"points": "oops"}))

    def weather_summary(self):
        """The weather at a landing zone: the nearest station's report and the NOTAMs around it. The METAR service and the FAA's NOTAM search are
        other people's, so what they answer is stood in for; which station is nearest, the numbers kept, the defaults when nobody answers, and the
        two shapes the NOTAMs come back in (grouped by feature, or a sentence) are the server's own."""
        self.make_account(email="weather@example.com")
        head = self.bearer(self.login(ANDROID, "weather@example.com")["access_token"])

        class Reply:
            def __init__(self, status=200, body=None):
                self.status_code, self._body = status, body

            def json(self):
                return self._body

        stations = [
            {"icaoId": "KRYY", "name": "Cobb County Airport", "lat": 34.01, "lon": -84.6, "temp": 18, "dewp": 9, "wspd": 12, "wdir": 270, "wgst": 20,
             "visib": 10, "altim": 1015.9, "fltcat": "VFR", "rawOb": "KRYY 031655Z 27012G20KT 10SM CLR 18/09 A3000"},
            {"icaoId": "KCNI", "name": "Cherokee County", "lat": 34.31, "lon": -84.42, "temp": 16.5, "dewp": 12.2, "wspd": 3, "wdir": "VRB", "visib": "10+",
             "fltcat": "MVFR", "rawOb": "KCNI 031655Z VRB03KT 10SM SCT025 17/12 A2995"},
        ]
        notams = {"notamList": [
            {"featureName": "Obstruction", "traditionalMessage": "  !FDC 6/1234 CRANE 340FT AGL 3NM N  "},
            {"featureName": "Obstruction", "traditionalMessage": "!FDC 6/2222 TOWER LGT OTS"},
            {"featureName": "Airspace", "traditionalMessage": "!ZTL 10/044 TEMPORARY FLIGHT RESTRICTION"},
            {"featureName": "Airspace", "traditionalMessage": ""},
            "not an item",
        ]}

        def metar(reports=None, raises=False, status=200):
            def get(url, **_kw):
                if raises:
                    raise requests.exceptions.ConnectionError("no route to host")
                return Reply(status, stations if reports is None else reports)
            return patch("routes.weather_routes.requests.get", side_effect=get)

        def faa(body=None, status=200, raises=False):
            def post(url, **_kw):
                if raises:
                    raise RuntimeError("the NOTAM search fell over")
                return Reply(status, notams if body is None else body)
            return patch("routes.weather_routes.requests.post", side_effect=post)

        near_second = {"lat": "34.3", "lng": "-84.4"}
        with metar(), faa():
            self.rec("weather", self.client.get("/api/weather", headers=head, query_string={"lat": "34.0", "lng": "-84.6"}))
            self.rec("weather: the second station is nearer", self.client.get("/api/weather", headers=head, query_string=near_second))
        with metar(reports=[]), faa(body=[]):
            self.rec("weather: no station and no NOTAMs", self.client.get("/api/weather", headers=head, query_string=near_second))
        with metar(raises=True), faa(status=503):
            self.rec("weather: both services down", self.client.get("/api/weather", headers=head, query_string=near_second))
        with metar(), faa(raises=True):
            self.rec("weather: the NOTAM search fails", self.client.get("/api/weather", headers=head, query_string=near_second))
        self.rec("weather: no position", self.client.get("/api/weather", headers=head))
        self.rec("weather: a position that is not one", self.client.get("/api/weather", headers=head, query_string={"lat": "x", "lng": "1"}))

    def pack_person(self, email, name, features=None):
        """A signed-in account, made in the database: the sign-ups above have used what one address may make in an hour. Mission packs
        are not launched (entitlements.DEFAULT_OFF), so ``features`` turns them on as an admin does for a tester, or off."""
        with self.app.app_context():
            user = User(email=email, name=name, role="user", google_id=f"local:{email}", access_approved=True, features=features)
            db.session.add(user)
            db.session.flush()
            db.session.add(LocalCredential(user_id=user.id, password_hash=generate_password_hash(PASSWORD),
                                           email_verified_at=datetime.utcnow(), status="active", session_version=0))
            db.session.commit()
            user_id = user.id
        return {"id": user_id, "head": self.bearer(self.login(ANDROID, email)["access_token"])}

    def packs(self):
        """Mission packs, teams, invitations and finding people (docs/MISSION_PACKS.md): every route's answer, and every refusal the
        spec documents but one, ``original_unknown``, which only a copy made before copies named their original can give.

        The pack and the library record are named by the device, as the apps name them, so the recorded paths are the same every
        run. The live service is not configured, as where clients poll, except for one answer that shows its address."""
        realtime = patch.dict(os.environ)
        realtime.start()
        self.addCleanup(realtime.stop)
        os.environ.pop("REALTIME_PUBLIC_URL", None)

        pack_uuid = "6d1c2e8a-4b3f-4a51-9e07-2f8b1c5d7a90"
        scratch_uuid = "a7e3b9c2-1f04-4d6e-8b5a-3c9d0e2f4a61"            # a pack made to be deleted
        library_uuid = "0c4f8e2b-7a19-4c3d-a6e5-9b1d2f3e4a57"            # a library LZ copied into the pack
        KEPT_UUIDS.update({pack_uuid, scratch_uuid, library_uuid})

        # An item holds the library's JSON, less what is each person's own (the web's sharedLzData); the server does not look inside.
        chalk_1, chalk_2 = 1759900000001, 1759900000002
        hawk = {
            "schemaVersion": 2, "status": "analyzed",
            "target": {"lat": 34.783817, "lon": -84.08219, "mgrs": "16S GD 66993 52949"},
            "mapData": {"zoom": 17},
            "flightData": {"callSign": "HAWK 6", "landing_hdg": "270°", "takeoff_hdg": "090°"},
            "analysis": {"latLong": "34.78382, -84.08219", "gridElevation": "4050", "customLZ": None,
                         "detectedLZ": [[34.7832, -84.0828], [34.7844, -84.0828], [34.7844, -84.0814]]},
            "graphics": {"doghouses": [], "helicopters": [{"id": chalk_1, "lat": 34.7837, "lon": -84.0823, "heading": 270, "profileRef": "uh60l"}],
                         "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": None},
        }
        routes = {"version": 1, "routes": [{"id": "sketch-1", "name": "ROUTE 1", "color": "#FF453A", "points": [
            {"id": "p1", "lat": 34.5, "lon": -84.2, "kind": "amps", "ptType": "target", "name": ".TGT", "role": "start"},
            {"id": "p2", "lat": 34.6, "lon": -84.0, "kind": "amps", "ptType": "target", "name": ".TGT", "role": "waypoint"}]}]}
        points = [{"id": "lps-0-ab12cd", "name": "BLUE 1", "description": "Landing zone", "group": "LZ", "icon": "lz", "elevationFt": 1730.0,
                   "lat": 34.5123, "lon": -84.2231}]
        falcon = {"schemaVersion": 2, "id": library_uuid, "name": "LZ FALCON", "status": "analyzed",
                  "target": {"lat": 34.70, "lon": -84.15}, "flightData": {"callSign": "FALCON 6"}, "view": {"mapStyle": "satellite"}}

        on = {"mission_packs": True}
        colin = self.pack_person("colin@example.com", "Colin", on)                  # owns the team and the pack
        sam = self.pack_person("sam@example.com", "Sam", on)                        # joins the team by its link, then the pack by name
        alex = self.pack_person("alex@example.com", "Alex", {**on, "cloud_save": False})   # invited by email; keeps no library
        dana = self.pack_person("dana@example.com", "Dana", on)                     # in nothing, so every pack and team is a 404 to her
        # Pat is an account an admin has turned packs off for. The "off" is stored, so she stays without them once packs launch
        # (taken out of entitlements.DEFAULT_OFF); with nothing stored she would have them then, and this refusal would go unrecorded.
        pat = self.pack_person("pat@example.com", "Pat", {"mission_packs": False})

        def ask(who, method, path, **kwargs):
            return getattr(self.client, method)(path, headers=who["head"], **kwargs)

        self.rec("packs: an account without mission packs", ask(pat, "get", "/api/packs"))

        # Teams: what sharing a pack and adding someone by name need.
        team = self.rec("team: create", ask(colin, "post", "/api/teams", json={"name": "B Co 2-10 AVN"})).get_json()
        here_team = f"/api/teams/{team['id']}"
        self.rec("team: create, a name that is not one", ask(colin, "post", "/api/teams", json={"name": "  "}))
        self.rec("teams", ask(colin, "get", "/api/teams"))
        link = self.rec("team invite: a link", ask(colin, "post", f"{here_team}/invites", json={})).get_json()
        self.rec("invite: accept from a link", ask(sam, "post", "/api/invites/accept", json={"token": link["token"]}))
        self.rec("invite: accept from a link already used", ask(sam, "post", "/api/invites/accept", json={"token": link["token"]}))
        self.rec("invite: accept from a link that is not one", ask(sam, "post", "/api/invites/accept", json={"token": "nonsense"}))
        stale = ask(colin, "post", f"{here_team}/invites", json={}).get_json()
        with self.app.app_context():                                                # two weeks pass
            row = db.session.get(MissionPackInvite, stale["invite"]["id"])
            row.created_at, row.expires_at = datetime.utcnow() - timedelta(days=15), datetime.utcnow() - timedelta(days=1)
            db.session.commit()
        self.rec("invite: accept from a link that has expired", ask(dana, "post", "/api/invites/accept", json={"token": stale["token"]}))
        to_alex = self.rec("team invite: by email", ask(colin, "post", f"{here_team}/invites", json={"email": "alex@example.com"})).get_json()["invite"]
        self.rec("team invite: someone in the team", ask(colin, "post", f"{here_team}/invites", json={"email": "sam@example.com"}))
        self.rec("team invite: not an email address", ask(colin, "post", f"{here_team}/invites", json={"email": "nope"}))
        self.rec("team invites", ask(colin, "get", f"{here_team}/invites"))
        self.rec("invites: mine, to a team", ask(alex, "get", "/api/invites"))
        self.rec("invite: decline one addressed to someone else", ask(sam, "post", f"/api/invites/{to_alex['id']}/decline"))
        self.rec("invite: decline", ask(alex, "post", f"/api/invites/{to_alex['id']}/decline"))
        self.rec("invite: decline one already answered", ask(alex, "post", f"/api/invites/{to_alex['id']}/decline"))
        to_kim = ask(colin, "post", f"{here_team}/invites", json={"email": "kim@example.com"}).get_json()["invite"]
        self.rec("team invite: withdraw", ask(colin, "delete", f"{here_team}/invites/{to_kim['id']}"))
        self.rec("team invite: withdraw one that is not waiting", ask(colin, "delete", f"{here_team}/invites/{to_kim['id']}"))
        self.rec("team: rename, by a member", ask(sam, "put", here_team, json={"name": "Mine"}))
        self.rec("team invites: by a member", ask(sam, "get", f"{here_team}/invites"))
        self.rec("team invite: by a member", ask(sam, "post", f"{here_team}/invites", json={}))
        self.rec("team invite: withdraw, by a member", ask(sam, "delete", f"{here_team}/invites/{to_kim['id']}"))
        self.rec("team member: remove, by a member", ask(sam, "delete", f"{here_team}/members/{colin['id']}"))
        self.rec("team: get", ask(colin, "get", here_team))
        self.rec("team: rename", ask(colin, "put", here_team, json={"name": "B Co"}))
        self.rec("team: rename, a name that is not one", ask(colin, "put", here_team, json={"name": ""}))
        self.rec("team member: change a role", ask(colin, "put", f"{here_team}/members/{sam['id']}", json={"role": "admin"}))
        self.rec("team member: not a role", ask(colin, "put", f"{here_team}/members/{sam['id']}", json={"role": "boss"}))
        self.rec("team member: change a role, by an admin", ask(sam, "put", f"{here_team}/members/{colin['id']}", json={"role": "member"}))
        self.rec("team member: someone not in the team", ask(colin, "put", f"{here_team}/members/{dana['id']}", json={"role": "member"}))
        self.rec("team member: the owner hands the team over first",
                 ask(colin, "put", f"{here_team}/members/{colin['id']}", json={"role": "member"}))
        self.rec("team invite: admin, which only the owner gives", ask(sam, "post", f"{here_team}/invites", json={"role": "admin"}))
        # A team someone is not in answers as one that does not exist.
        for name, method, path in (("team: get", "get", here_team), ("team: rename", "put", here_team), ("team: delete", "delete", here_team),
                                   ("team invites", "get", f"{here_team}/invites"), ("team invite", "post", f"{here_team}/invites")):
            self.rec(f"{name}, a team the caller is not in", ask(dana, method, path, json={"name": "Mine"}))

        # Finding people: teammates only.
        self.rec("users: search", ask(colin, "get", "/api/users/search", query_string={"q": "sa"}))
        self.rec("users: search finds only teammates", ask(colin, "get", "/api/users/search", query_string={"q": "alex"}))
        self.rec("users: search, too short", ask(colin, "get", "/api/users/search", query_string={"q": "s"}))

        # The pack
        here = f"/api/packs/{pack_uuid}"
        made = {"name": "OP DK", "description": "Air assault rehearsal", "uuid": pack_uuid}
        self.rec("pack: create", ask(colin, "post", "/api/packs", json=made))
        self.rec("pack: create again with the same uuid", ask(colin, "post", "/api/packs", json=made))
        self.rec("pack: create, a name that is not one", ask(colin, "post", "/api/packs", json={"name": ""}))
        self.rec("pack: create, a description that is too long", ask(colin, "post", "/api/packs", json={"name": "OP", "description": "x" * 2001}))
        self.rec("pack: create, a uuid that is not one", ask(colin, "post", "/api/packs", json={"name": "OP", "uuid": "nope"}))
        self.rec("pack: create, a uuid someone else has", ask(sam, "post", "/api/packs", json={"name": "OP", "uuid": pack_uuid}))
        self.rec("packs", ask(colin, "get", "/api/packs"), "Cache-Control")
        self.rec("pack: get", ask(colin, "get", here))
        with patch.dict(os.environ, {"REALTIME_PUBLIC_URL": "wss://live.example.com"}):
            self.rec("pack: get, where the live service runs", ask(colin, "get", here))
        self.rec("pack: access", ask(colin, "get", f"{here}/access"))
        self.rec("pack: rename and describe", ask(colin, "put", here, json={"name": "OP EAGLE", "description": "Night air assault"}))
        self.rec("pack: share with a team", ask(colin, "put", here, json={"team_id": team["id"], "team_role": "viewer"}))
        self.rec("pack: share with a team the owner is not in", ask(colin, "put", here, json={"team_id": 9999}))
        self.rec("pack: rename, by a viewer", ask(sam, "put", here, json={"name": "Mine"}))

        # The operation stream: everyone's edits, in one order. The first batch carries no base_seq, so only its own events come back.
        ops = f"{here}/ops"
        first = self.rec("ops: make an LZ, a route set and a point set", ask(colin, "post", ops, json={"ops": [
            {"type": "item.create", "item": "lz-1", "kind": "lz", "name": "LZ HAWK", "data": hawk, "client_op_id": "op-1",
             "summary": 'Colin added the LZ "LZ HAWK".'},
            {"type": "item.create", "item": "rt-1", "kind": "route", "name": "ROUTES", "data": routes, "client_op_id": "op-2"},
            {"type": "item.create", "item": "pts-1", "kind": "pointset", "name": "NORTH GA", "data": points, "client_op_id": "op-3"},
        ]})).get_json()
        edits = {"base_seq": first["head_seq"], "ops": [
            {"type": "set", "item": "lz-1", "path": ["graphics", "helicopters", {"id": chalk_1}, "heading"], "value": 300,
             "client_op_id": "op-4", "summary": "Colin turned Chalk 1 on LZ HAWK."},
            {"type": "set", "item": "lz-1", "path": ["flightData", "callSign"], "value": None, "client_op_id": "op-5"},
            {"type": "insert", "item": "lz-1", "path": ["graphics", "helicopters"], "after": None, "client_op_id": "op-6",
             "value": {"id": chalk_2, "lat": 34.7839, "lon": -84.0821, "heading": 270, "profileRef": "uh60l"}},
            {"type": "remove", "item": "lz-1", "path": ["graphics", "helicopters", {"id": 1759900009999}], "client_op_id": "op-7"},
            {"type": "item.rename", "item": "rt-1", "name": "ROUTES NORTH", "client_op_id": "op-8"},
        ]}
        self.rec("ops: edits, one skipped, with the events after base_seq", ask(colin, "post", ops, json=edits))
        self.rec("ops: the same batch again, answered from the log", ask(colin, "post", ops, json=edits))
        self.rec("ops: a malformed operation", ask(colin, "post", ops, json={"ops": [
            {"type": "item.delete", "item": "pts-1", "client_op_id": "op-9"},
            {"type": "set", "item": "lz-1", "path": ["flightData", "callSign"], "client_op_id": "op-10"}]}))
        self.rec("ops: no operations", ask(colin, "post", ops, json={"ops": []}))
        delete_points = {"ops": [{"type": "item.delete", "item": "pts-1", "client_op_id": "op-11"}]}
        self.rec("ops: by a viewer", ask(sam, "post", ops, json=delete_points))
        self.rec("ops: an item that would pass 5 MB", ask(colin, "post", ops, json={"ops": [
            {"type": "set", "item": "lz-1", "path": ["notes"], "value": "x" * (5 * 1024 * 1024), "client_op_id": "op-12"}]}))

        # The log, and how far someone has looked in it.
        self.rec("events: a page", ask(colin, "get", f"{here}/events", query_string={"since": 0, "limit": 3}))
        self.rec("events: the rest", ask(colin, "get", f"{here}/events", query_string={"since": 3}))
        self.rec("events: a cursor that is not a number", ask(colin, "get", f"{here}/events", query_string={"since": "x"}))
        self.rec("seen", ask(colin, "put", f"{here}/seen", json={"seq": 3}))
        self.rec("seen: not a number", ask(colin, "put", f"{here}/seen", json={"seq": "x"}))

        # Items copied in from the library, updated from it, and saved back to it.
        items = f"{here}/items"
        original = ask(colin, "post", "/api/lz", json={"name": "LZ FALCON", "lz_data": falcon, "client_uuid": library_uuid}).get_json()
        copy_in = {"source": {"kind": "lz", "client_uuid": library_uuid}, "item": "lz-2"}
        self.rec("item: copy in from the library", ask(colin, "post", items, json=copy_in))
        self.rec("item: copy in again with the same item", ask(colin, "post", items, json=copy_in))
        self.rec("item: copy in, an id the pack has", ask(colin, "post", items, json={**copy_in, "item": "lz-1"}))
        self.rec("item: copy in what is not in the library", ask(colin, "post", items, json={"source": {"kind": "lz", "id": 9999}, "item": "lz-3"}))
        self.rec("item: copy in, not a kind of record", ask(colin, "post", items, json={"source": {"kind": "threat"}}))
        self.rec("item: copy in, an id that is not one", ask(colin, "post", items, json={**copy_in, "item": "../lz"}))
        self.rec("item: copy in, by a viewer", ask(sam, "post", items, json={**copy_in, "item": "lz-3"}))
        self.rec("item: get", ask(colin, "get", f"{items}/lz-2"))
        # The original moves on in the library, and the copy in the pack.
        ask(colin, "put", f"/api/lz/{original['id']}", json={"lz_data": {**falcon, "flightData": {"callSign": "FALCON 1"}}})
        ask(colin, "post", ops, json={"ops": [{"type": "set", "item": "lz-2", "path": ["flightData", "callSign"], "value": "FALCON 2",
                                                "client_op_id": "op-13", "summary": "Colin changed the call sign on LZ FALCON."}]})
        self.rec("item: get, after the original and the copy both changed", ask(colin, "get", f"{items}/lz-2"))
        self.rec("item: update from the original", ask(colin, "post", f"{items}/lz-2/update-from-original", json={}))
        self.rec("item: update from the original, an item not copied from the caller's library",
                 ask(colin, "post", f"{items}/lz-1/update-from-original", json={}))
        self.rec("item: update from the original, an item not in the pack", ask(colin, "post", f"{items}/lz-9/update-from-original", json={}))
        ask(colin, "put", f"/api/lz/{original['id']}", json={"lz_data": {**falcon, "notes": "x" * (5 * 1024 * 1024)}})
        self.rec("item: copy in an original larger than 5 MB", ask(colin, "post", items, json={**copy_in, "item": "lz-3"}))
        self.rec("item: update from an original larger than 5 MB", ask(colin, "post", f"{items}/lz-2/update-from-original", json={}))
        ask(colin, "delete", f"/api/lz/{original['id']}")
        self.rec("item: update from an original that is gone", ask(colin, "post", f"{items}/lz-2/update-from-original", json={}))
        self.rec("item: get, an item not in the pack", ask(colin, "get", f"{items}/lz-9"))
        saved = self.rec("item: save to the library", ask(colin, "post", f"{items}/lz-1/library", json={"name": "LZ HAWK (OP EAGLE)"})).get_json()
        self.rec("item: save to the library, a name that is not one", ask(colin, "post", f"{items}/lz-1/library", json={"name": ""}))
        self.rec("item: save to the library, an item not in the pack", ask(colin, "post", f"{items}/lz-9/library", json={}))
        ask(colin, "post", ops, json={"ops": [{"type": "item.create", "item": "pts-2", "kind": "pointset", "name": "NO POINTS", "data": [],
                                                "client_op_id": "op-14"}]})
        self.rec("item: save a point set with no points to the library", ask(colin, "post", f"{items}/pts-2/library", json={}))
        # What the library holds that a pack cannot: an imported AMPS mission, and a record that is not what its kind says.
        form = {"content_type": "multipart/form-data"}
        mission = ask(colin, "post", "/api/routes", data={"name": "MISSION", "kind": "mission", "route_data": "{}",
                                                          "msnx": (io.BytesIO(b"stored, never opened, by the server"), "mission.msnx")}, **form).get_json()
        self.rec("item: copy in an imported mission", ask(colin, "post", items, json={"source": {"kind": "route", "id": mission["id"]}, "item": "rt-2"}))
        odd = ask(colin, "post", "/api/routes", data={"name": "ODD", "kind": "sketch", "route_data": "[]"}, **form).get_json()
        self.rec("item: copy in a record that cannot be read", ask(colin, "post", items, json={"source": {"kind": "route", "id": odd["id"]}, "item": "rt-2"}))

        # Members: a teammate added by name, roles, and handing the pack over.
        members = f"{here}/members"
        self.rec("member: add a teammate", ask(colin, "post", members, json={"user_id": sam["id"], "role": "editor"}))
        self.rec("member: add someone already in", ask(colin, "post", members, json={"user_id": sam["id"]}))
        self.rec("member: add someone who is not a teammate", ask(colin, "post", members, json={"user_id": alex["id"]}))
        self.rec("member: add, by an editor", ask(sam, "post", members, json={"user_id": colin["id"]}))
        self.rec("member: change a role", ask(colin, "put", f"{members}/{sam['id']}", json={"role": "viewer"}))
        ask(colin, "put", f"{members}/{sam['id']}", json={"role": "editor"})
        self.rec("member: not a role", ask(colin, "put", f"{members}/{sam['id']}", json={"role": "boss"}))
        self.rec("member: change the role of someone not in the pack", ask(colin, "put", f"{members}/{dana['id']}", json={"role": "viewer"}))
        self.rec("member: the owner hands the pack over first", ask(colin, "put", f"{members}/{colin['id']}", json={"role": "editor"}))
        self.rec("member: change a role, by an editor", ask(sam, "put", f"{members}/{colin['id']}", json={"role": "viewer"}))

        # Invitations to the pack, by email.
        invites = f"{here}/invites"
        to_alex = self.rec("pack invite: by email", ask(colin, "post", invites, json={"email": "alex@example.com", "role": "editor"})).get_json()["invite"]
        self.rec("pack invite: the same address again", ask(colin, "post", invites, json={"email": "Alex@Example.com"}))
        self.rec("pack invite: not an email address", ask(colin, "post", invites, json={"email": "nope"}))
        self.rec("pack invite: someone in the pack", ask(colin, "post", invites, json={"email": "sam@example.com"}))
        self.rec("pack invite: by an editor", ask(sam, "post", invites, json={"email": "kim@example.com"}))
        self.rec("pack invites", ask(colin, "get", invites))
        self.rec("pack invites: by an editor", ask(sam, "get", invites))
        self.rec("pack invite: send again", ask(colin, "post", f"{invites}/{to_alex['id']}/resend"))
        self.rec("pack invite: send again, one that is not waiting", ask(colin, "post", f"{invites}/9999/resend"))
        self.rec("pack invite: send again, by an editor", ask(sam, "post", f"{invites}/{to_alex['id']}/resend"))
        self.rec("invites: mine, to a pack", ask(alex, "get", "/api/invites"))
        self.rec("invite: accept one addressed to someone else", ask(sam, "post", f"/api/invites/{to_alex['id']}/accept"))
        self.rec("invite: accept", ask(alex, "post", f"/api/invites/{to_alex['id']}/accept"))
        self.rec("invite: accept one already answered", ask(alex, "post", f"/api/invites/{to_alex['id']}/accept"))
        self.rec("item: save to the library, an account without cloud save", ask(alex, "post", f"{items}/lz-1/library", json={}))
        to_kim = ask(colin, "post", invites, json={"email": "kim@example.com", "role": "viewer"}).get_json()["invite"]
        self.rec("pack invite: withdraw, by an editor", ask(sam, "delete", f"{invites}/{to_kim['id']}"))
        self.rec("pack invite: withdraw", ask(colin, "delete", f"{invites}/{to_kim['id']}"))
        self.rec("pack invite: withdraw one that is not waiting", ask(colin, "delete", f"{invites}/{to_kim['id']}"))

        # Leaving, handing over, finishing and reopening.
        self.rec("member: remove", ask(colin, "delete", f"{members}/{alex['id']}"))
        self.rec("member: remove someone not in the pack", ask(colin, "delete", f"{members}/{dana['id']}"))
        self.rec("member: remove, by an editor", ask(sam, "delete", f"{members}/{colin['id']}"))
        self.rec("member: the owner leaves", ask(colin, "delete", f"{members}/{colin['id']}"))
        self.rec("member: hand the pack over", ask(colin, "put", f"{members}/{sam['id']}", json={"role": "owner"}))
        self.rec("pack: finish, by an editor", ask(colin, "post", f"{here}/finish"))
        self.rec("pack: finish", ask(sam, "post", f"{here}/finish"))
        self.rec("ops: a finished pack", ask(colin, "post", ops, json=delete_points))
        # A batch whose answer was lost, sent again after the pack was finished: the refusal says what the pack took of it.
        self.rec("ops: a batch the pack took, sent again once it was finished", ask(colin, "post", ops, json=edits))
        self.rec("pack: rename a finished pack", ask(colin, "put", here, json={"name": "Mine"}))
        self.rec("item: copy in, to a finished pack", ask(colin, "post", items, json={"source": {"kind": "lz", "id": saved["id"]}, "item": "lz-4"}))
        self.rec("item: update from the original, in a finished pack", ask(colin, "post", f"{items}/lz-2/update-from-original", json={}))
        self.rec("pack: reopen, by an editor", ask(colin, "post", f"{here}/reopen"))
        self.rec("pack: reopen", ask(sam, "post", f"{here}/reopen"))
        ask(sam, "put", f"{members}/{colin['id']}", json={"role": "owner"})                  # and back
        self.rec("pack: duplicate", ask(colin, "post", f"{here}/duplicate", json={"name": "OP EAGLE 2"}))
        self.rec("pack: duplicate, a name that is not one", ask(colin, "post", f"{here}/duplicate", json={"name": ""}))
        ask(colin, "post", "/api/packs", json={"name": "OP SCRATCH", "uuid": scratch_uuid})
        self.rec("pack: delete, by an editor", ask(sam, "delete", here))
        self.rec("pack: delete", ask(colin, "delete", f"/api/packs/{scratch_uuid}"))
        self.rec("pack: delete one already deleted", ask(colin, "delete", f"/api/packs/{scratch_uuid}"))
        # Someone not in a pack is told what they would be told if it did not exist, on every route.
        for name, method, path in (
                ("pack: get", "get", here), ("pack: rename", "put", here), ("pack: delete", "delete", here),
                ("pack: access", "get", f"{here}/access"), ("pack: finish", "post", f"{here}/finish"),
                ("pack: reopen", "post", f"{here}/reopen"), ("pack: duplicate", "post", f"{here}/duplicate"),
                ("ops", "post", ops), ("events", "get", f"{here}/events"), ("seen", "put", f"{here}/seen"),
                ("item: copy in", "post", items), ("member: add", "post", members),
                ("pack invites", "get", invites), ("pack invite", "post", invites)):
            body = {"name": "Mine", "seq": 1, "user_id": sam["id"], "email": "kim@example.com", **delete_points, **copy_in}
            self.rec(f"{name}, a pack the caller is not in", ask(dana, method, path, json=body))

        # Thirty invitations an hour per person, to packs and to teams.
        waiting = ask(colin, "post", invites, json={"email": "kim@example.com"}).get_json()["invite"]
        for _ in range(30):
            if ask(colin, "post", f"{invites}/{waiting['id']}/resend").status_code == 429:
                break
        self.rec("pack invite: too many", ask(colin, "post", invites, json={"email": "lee@example.com"}))
        self.rec("pack invite: send again, too many", ask(colin, "post", f"{invites}/{waiting['id']}/resend"))
        for _ in range(30):
            if ask(colin, "post", f"{here_team}/invites", json={}).status_code == 429:
                break
        self.rec("team invite: too many", ask(colin, "post", f"{here_team}/invites", json={}))

        # The team, last: deleting it stops the pack being shared with it.
        self.rec("team member: the owner leaves", ask(colin, "delete", f"{here_team}/members/{colin['id']}"))
        self.rec("team member: remove someone not in the team", ask(colin, "delete", f"{here_team}/members/{dana['id']}"))
        self.rec("team: delete, by an admin", ask(sam, "delete", here_team))
        self.rec("team member: remove", ask(colin, "delete", f"{here_team}/members/{sam['id']}"))
        self.rec("team: delete", ask(colin, "delete", here_team))

        # Last, so the events it adds move no number above: a batch whose answer was lost, sent again by someone who may now
        # only view. The refusal says what the pack took of it.
        lost = {"ops": [{"type": "set", "item": "lz-1", "path": ["flightData", "callSign"], "value": "SAM 6", "client_op_id": "op-15"}]}
        self.assertEqual(ask(colin, "put", f"{members}/{sam['id']}", json={"role": "editor"}).status_code, 200)
        self.assertEqual(ask(sam, "post", ops, json=lost).status_code, 200)
        self.assertEqual(ask(colin, "put", f"{members}/{sam['id']}", json={"role": "viewer"}).status_code, 200)
        self.rec("ops: a batch the pack took, sent again by someone who may now only view", ask(sam, "post", ops, json=lost))

    def test_the_recorded_responses_are_what_the_server_says(self):
        self.scenario()
        self.accounts()
        self.terrain()
        self.threat_mask()
        self.planning()
        self.weather_summary()
        self.packs()
        document = {
            "description": "Real responses from the Flask API (tests/test_network_fixtures.py), with tokens, timestamps, "
                           "generated ids and the server version replaced by placeholders. The native apps decode each body "
                           "with their own types, strictly, and replay them against a mock server.",
            "generatedBy": "backend/tests/test_network_fixtures.py (UPDATE_CONTRACTS=1)",
            "responses": self.recorded,
        }
        committed = json.loads(FIXTURE.read_text(encoding="utf-8")) if FIXTURE.exists() else None
        # Windows and Linux maths libraries can differ in a double's last digit (a boundary's latitude
        # came out 34.10071753947543 on one and 34.100717539475426 on the other), so a number within
        # that of the committed one counts as the same and keeps its committed digits.
        fresh = json.loads(json.dumps(document["responses"]))
        document["responses"] = settle_floats(committed["responses"], fresh) if committed else fresh
        text = self.dumps(document)
        if UPDATE:
            FIXTURE.parent.mkdir(parents=True, exist_ok=True)
            FIXTURE.write_text(text, encoding="utf-8", newline="\n")           # LF on Windows too, as .gitattributes keeps it
            return
        self.assertIsNotNone(committed, f"{FIXTURE} is missing; run with UPDATE_CONTRACTS=1")
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
