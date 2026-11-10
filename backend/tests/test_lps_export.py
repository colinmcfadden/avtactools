"""The .LPS writer (``lps_export``) and its route. The readers are held to ``contracts/fixtures/sqlite/local-points.lps``, so what is written is held to that file."""

import sqlite3
import struct
import sys
import tempfile
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from lps_export import MAX_POINTS, LpsExportError, build_lps_bytes, spatialite_point  # noqa: E402
from models import User, db  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402

FIXTURE = BACKEND_DIR.parent / 'contracts' / 'fixtures' / 'sqlite' / 'local-points.lps'
POINT = {'id': 'pt-1', 'name': 'HAWK', 'description': 'Ridge line', 'group': 'North', 'icon': '', 'elevationFt': 1730.5, 'lat': 34.5, 'lon': -84.25}


def rows_of(data, query='SELECT ID, Description, GroupName, IconName, Elevation, Coordinate FROM Points ORDER BY Pedigree'):
    with tempfile.NamedTemporaryFile(suffix='.lps') as file:
        file.write(data)
        file.flush()
        connection = sqlite3.connect(file.name)
        try:
            return connection.execute(query).fetchall()
        finally:
            connection.close()


def decoded(blob):
    """What the readers take from a blob: longitude and latitude after the MBR."""
    return struct.unpack('<2d', blob[43:59])


class WriterTests(unittest.TestCase):
    def test_writes_a_row_for_each_point(self):
        data = build_lps_bytes([POINT, {**POINT, 'name': 'DOVE', 'elevationFt': None, 'group': ''}])
        rows = rows_of(data)
        self.assertEqual(rows[0][:5], ('HAWK', 'Ridge line', 'North', '', 1730.5))
        self.assertEqual(decoded(rows[0][5]), (-84.25, 34.5))
        self.assertEqual(rows[1][:2], ('DOVE', 'Ridge line'))
        self.assertEqual(rows[1][2], 'Default', 'a point with no group goes where the reader puts it')
        self.assertIsNone(rows[1][4], 'no elevation stays NULL, never 0')

    def test_has_exactly_the_table_the_readers_are_held_to(self):
        query = "SELECT type, name, sql FROM sqlite_master ORDER BY name"
        self.assertEqual(rows_of(build_lps_bytes([POINT]), query), rows_of(FIXTURE.read_bytes(), query))

    def test_a_blob_is_byte_for_byte_the_ones_the_readers_read(self):
        # The little-endian POINTs only: the fixture also holds other shapes, for the readers to refuse.
        blobs = rows_of(FIXTURE.read_bytes(), "SELECT Coordinate FROM Points WHERE length(Coordinate) = 60 AND hex(Coordinate) LIKE '0001E6%' AND substr(hex(Coordinate), 77, 10) = '7C01000000'")
        self.assertGreater(len(blobs), 0)
        for (blob,) in blobs:
            self.assertEqual(spatialite_point(*decoded(blob)), blob)

    def test_cuts_text_to_the_widths_by_characters(self):
        rows = rows_of(build_lps_bytes([{**POINT, 'name': '😀' * 60, 'description': 'd' * 300, 'group': 'g' * 60}]))
        self.assertEqual(rows[0][0], '😀' * 50)
        self.assertEqual(len(rows[0][1]), 255)
        self.assertEqual(len(rows[0][2]), 50)

    def test_refuses_what_is_not_a_set_of_points(self):
        for bad in (None, [], 'points', {'points': []}):
            with self.assertRaises(LpsExportError):
                build_lps_bytes(bad)

    def test_refuses_too_many(self):
        with self.assertRaises(LpsExportError):
            build_lps_bytes([POINT] * (MAX_POINTS + 1))

    def test_refuses_a_point_with_no_place_and_says_which(self):
        cases = [{**POINT, 'lat': None}, {**POINT, 'lat': 91}, {**POINT, 'lon': -181}, {**POINT, 'lat': 'x'}, {**POINT, 'lon': True}, {**POINT, 'lat': float('nan')}, 'point']
        for bad in cases:
            with self.assertRaises(LpsExportError) as caught:
                build_lps_bytes([POINT, bad])
            self.assertIn('Point 2', str(caught.exception))

    def test_an_elevation_that_is_no_number_is_no_elevation(self):
        for elevation in ('high', True, float('inf')):
            self.assertIsNone(rows_of(build_lps_bytes([{**POINT, 'elevationFt': elevation}]))[0][4])

    def test_text_that_looks_like_sql_is_only_text(self):
        name = "X'); DROP TABLE Points;--"
        self.assertEqual(rows_of(build_lps_bytes([{**POINT, 'name': name}]))[0][0], name[:50])


class RouteTests(NativeAuthCase):
    extra_blueprints = (point_sets_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}

    def test_answers_the_file_and_keeps_it_private(self):
        response = self.client.post('/api/points-lps', headers=self.head, json={'points': [POINT]})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['Cache-Control'], 'private, no-store')
        self.assertEqual(rows_of(response.get_data())[0][0], 'HAWK')

    def test_refuses_nothing_to_export_in_words(self):
        response = self.client.post('/api/points-lps', headers=self.head, json={'points': []})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()['code'], 'invalid_points')

    def test_needs_a_sign_in(self):
        self.assertEqual(self.client.post('/api/points-lps', json={'points': [POINT]}).status_code, 401)

    def test_needs_exports(self):
        with self.app.app_context():
            User.query.filter_by(email='pilot@example.com').one().features = {'exports': False}
            db.session.commit()
        response = self.client.post('/api/points-lps', headers=self.head, json={'points': [POINT]})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.get_json()['code'], 'feature_disabled')


if __name__ == '__main__':
    unittest.main()
