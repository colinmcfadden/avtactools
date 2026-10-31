"""`POST /api/threats-ths` after the builder moved to ths_export.py: the route still hands back a readable .ths."""

import sqlite3
import sys
import tempfile
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from routes.threat_routes import threat_bp  # noqa: E402

BODY = {
    "fileName": "mine",
    "threats": [{"name": "Route threat", "lat": 34.5, "lon": -84.25, "radars": [{"type": 1, "rangeNmi": 9, "bands": []}]}],
}


class ThsRouteTests(NativeAuthCase):
    extra_blueprints = (threat_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.headers = {"Authorization": f'Bearer {self.login()["access_token"]}'}

    def test_the_route_returns_a_database_with_the_threats_in_it(self):
        response = self.client.post("/api/threats-ths", headers=self.headers, json=BODY)
        self.assertEqual(response.status_code, 200)
        self.assertIn("mine.ths", response.headers["Content-Disposition"])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "x.ths"
            path.write_bytes(response.get_data())
            con = sqlite3.connect(path)
            self.assertEqual(list(con.execute("SELECT OFFICIAL_NAME, LATITUDE_DEG FROM THREATS")), [("Route threat", 34.5)])
            self.assertEqual(list(con.execute("SELECT SYSTEM_NAME, USE_ENGAGEMENT, USE_DETECTION FROM SYSTEM")), [("Route threat", 1, 0)])
            con.close()

    def test_the_name_gets_its_extension(self):
        response = self.client.post("/api/threats-ths", headers=self.headers, json={**BODY, "fileName": "x.THS"})
        self.assertIn("x.THS", response.headers["Content-Disposition"])
        response = self.client.post("/api/threats-ths", headers=self.headers, json={"threats": BODY["threats"]})
        self.assertIn("threats.ths", response.headers["Content-Disposition"])

    def test_no_threats_is_refused_and_a_token_is_needed(self):
        self.assertEqual(self.client.post("/api/threats-ths", headers=self.headers, json={"threats": []}).status_code, 400)
        self.assertEqual(self.client.post("/api/threats-ths", json=BODY).status_code, 401)


if __name__ == "__main__":
    import unittest
    unittest.main()
