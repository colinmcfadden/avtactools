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
import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from rasterio.transform import from_origin  # noqa: E402

import refresh_tokens  # noqa: E402
from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.aircraft_routes import aircraft_bp  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402
from routes.saved_routes import saved_routes_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from routes.weather_routes import weather_bp  # noqa: E402
from terrain_provider import LocalRasterCatalog  # noqa: E402

# The terrain blueprint loads the SAM model when it is imported, which downloads 350 MB when the weights are missing. The model is
# stood in for below, where the route is asked; none of the weights are needed to import it.
if "routes.terrain_routes" not in sys.modules:
    sys.modules.setdefault("ultralytics", MagicMock())
from routes.terrain_routes import terrain_bp  # noqa: E402

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
    extra_blueprints = (config_bp, lz_bp, saved_routes_bp, point_sets_bp, aircraft_bp, sync_bp, terrain_bp, weather_bp)

    def setUp(self):
        super().setUp()
        self.recorded = []
        self.mil_codes = []
        mil = patch("routes.auth.send_mil_verification_email", side_effect=lambda _email, code, _name: self.mil_codes.append(code) or True)
        mil.start()
        self.addCleanup(mil.stop)

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

    def test_the_recorded_responses_are_what_the_server_says(self):
        self.scenario()
        self.accounts()
        self.terrain()
        self.planning()
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
