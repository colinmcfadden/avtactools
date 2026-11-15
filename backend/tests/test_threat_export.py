"""Exporting threats for ForeFlight and ATAK (`POST /api/threats-kmz`): every open set in one file, a folder each,
as KMZ or KML. The terrain mask needs elevation tiles from the network, so `fetch_dem` is stood in for here
(its polygons are held in tests/test_mask_rings.py)."""

import io
import re
import sys
import unittest
import zipfile
from pathlib import Path
from unittest import mock

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from routes import threat_routes  # noqa: E402
from routes.threat_routes import threat_bp  # noqa: E402


def _threat(name, lat=34.6, lon=-84.2):
    return {'name': name, 'lat': lat, 'lon': lon, 'milstdId': 'SHGPEWMAI------', 'information': '',
            'radars': [{'type': 0, 'rangeNmi': 7, 'antennaHeightFt': 20, 'aglNotMsl': True, 'showMask': True,
                        'showRangeRings': True, 'bands': []}]}


SETS = [
    {'name': 'NORTH <A&B>', 'threats': [_threat('SA-8'), _threat('ZSU-23')]},
    {'name': 'EMPTY', 'threats': []},
    {'name': 'SOUTH', 'threats': [_threat('SA-15', lat=34.4)]},
]


def _folders(kml):
    """The Folder names in document order, nested ones indented."""
    names, depth = [], 0
    for tag in re.finditer(r'<Folder><name>([^<]*)</name>|</Folder>', kml):
        if tag.group(0) == '</Folder>':
            depth -= 1
        else:
            names.append('  ' * depth + tag.group(1))
            depth += 1
    return names


class ThreatExport(NativeAuthCase):
    extra_blueprints = (threat_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}
        no_terrain = mock.patch.object(threat_routes, 'fetch_dem', return_value=(None, None))
        no_terrain.start()
        self.addCleanup(no_terrain.stop)

    def post(self, body):
        return self.client.post('/api/threats-kmz', headers=self.head, json=body)

    def test_every_set_is_a_folder_of_its_threats(self):
        response = self.post({'fileName': 'MISSION 1.kmz', 'sets': SETS})
        self.assertEqual(response.status_code, 200, response.data[:300])
        self.assertEqual(response.mimetype, 'application/vnd.google-earth.kmz')
        self.assertIn('filename="MISSION 1.kmz"', response.headers['Content-Disposition'])
        kml = zipfile.ZipFile(io.BytesIO(response.data)).read('doc.kml').decode()
        self.assertIn('<Document><name>MISSION 1</name>', kml)
        # An empty set is left out; a set's name is escaped.
        self.assertEqual(_folders(kml), ['NORTH &lt;A&amp;B&gt;', '  SA-8', '  ZSU-23', 'SOUTH', '  SA-15'])
        self.assertEqual(kml.count('<Point>'), 3)

    def test_kml_is_the_same_document_unzipped(self):
        response = self.post({'fileName': 'MISSION 1.kmz', 'sets': SETS, 'format': 'kml'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.mimetype, 'application/vnd.google-earth.kml+xml')
        self.assertIn('filename="MISSION 1.kml"', response.headers['Content-Disposition'])
        kml = response.get_data(as_text=True)
        self.assertTrue(kml.startswith('<?xml'))
        zipped = zipfile.ZipFile(io.BytesIO(self.post({'fileName': 'MISSION 1.kmz', 'sets': SETS}).data)).read('doc.kml')
        self.assertEqual(kml, zipped.decode())

    def test_a_flat_list_still_makes_the_file_it_always_did(self):
        response = self.post({'threats': [_threat('SA-8')]})
        self.assertEqual(response.status_code, 200)
        self.assertIn('filename=threats.kmz', response.headers['Content-Disposition'])
        kml = zipfile.ZipFile(io.BytesIO(response.data)).read('doc.kml').decode()
        self.assertEqual(_folders(kml), ['SA-8'])

    def test_what_is_refused(self):
        for body in ({'sets': []}, {'sets': [{'name': 'A', 'threats': []}]}, {'sets': [{'name': 'A'}]},
                     {'sets': 'all'}, {'threats': []}, {'threats': ['SA-8']},
                     {'threats': [_threat(f'T{i}') for i in range(threat_routes.MAX_EXPORT_THREATS + 1)]}):
            response = self.post(body)
            self.assertEqual(response.status_code, 400, body if len(str(body)) < 200 else 'too many')
            self.assertIn('error', response.get_json())


if __name__ == '__main__':
    unittest.main()
