"""Focused tests for secure, cross-device threat KMZ links."""

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from flask import Flask
from flask_jwt_extended import JWTManager, create_access_token


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from routes.threat_routes import (  # noqa: E402
    _threat_download_store,
    threat_bp,
)
from threat_download_store import ThreatDownloadStore  # noqa: E402


class ThreatDownloadStoreTests(unittest.TestCase):
    def test_links_expire_and_have_a_bounded_download_count(self):
        now = [100.0]
        store = ThreatDownloadStore(
            ttl_seconds=600,
            max_downloads=2,
            max_entries=2,
            clock=lambda: now[0],
        )

        token = store.create(b'kmz-data', 'threats.kmz')
        first, status = store.take(token)
        self.assertEqual(status, 'ok')
        self.assertEqual(first.contents, b'kmz-data')
        self.assertEqual(first.remaining_downloads, 1)
        second, status = store.take(token)
        self.assertEqual(status, 'ok')
        self.assertEqual(second.remaining_downloads, 0)
        self.assertEqual(store.take(token), (None, 'missing'))

        expiring = store.create(b'other-data', 'other.kmz')
        now[0] += 601
        self.assertEqual(store.take(expiring), (None, 'expired'))

    def test_store_bounds_each_file_and_total_memory(self):
        store = ThreatDownloadStore(
            ttl_seconds=600,
            max_downloads=1,
            max_entries=10,
            max_entry_bytes=4,
            max_total_bytes=6,
        )
        first = store.create(b'1234', 'first.kmz')
        second = store.create(b'5678', 'second.kmz')

        # Adding the second entry evicts the oldest to remain under six bytes.
        self.assertEqual(store.take(first), (None, 'missing'))
        self.assertEqual(store.take(second)[1], 'ok')
        with self.assertRaises(ValueError):
            store.create(b'12345', 'too-large.kmz')


class ThreatQrRouteTests(unittest.TestCase):
    def setUp(self):
        _threat_download_store.clear()
        self.app = Flask(__name__)
        self.app.config.update(
            TESTING=True,
            JWT_SECRET_KEY='test-only-secret-that-is-over-32-bytes',
        )
        JWTManager(self.app)
        self.app.register_blueprint(threat_bp)
        with self.app.app_context():
            self.jwt = create_access_token(identity='1')
        self.client = self.app.test_client()

    def tearDown(self):
        _threat_download_store.clear()

    @property
    def auth_headers(self):
        return {'Authorization': f'Bearer {self.jwt}'}

    def test_link_creation_requires_authentication(self):
        response = self.client.post('/api/threats-kmz-link', json={
            'threats': [{'name': 'Threat 1'}],
        })
        self.assertEqual(response.status_code, 401)

    def test_qr_url_contains_only_an_opaque_token(self):
        marker = 'SENSITIVE-THREAT-NAME'
        with patch(
            'routes.threat_routes.build_threats_kmz',
            return_value=b'generated-kmz',
        ):
            created = self.client.post(
                '/api/threats-kmz-link',
                headers=self.auth_headers,
                json={
                    'fileName': 'mission-threats.kmz',
                    'threats': [{'name': marker}],
                },
            )

        self.assertEqual(created.status_code, 201)
        body = created.get_json()
        self.assertIn('/api/threats-kmz?token=', body['downloadPath'])
        self.assertNotIn('data=', body['downloadPath'])
        self.assertNotIn(marker, created.get_data(as_text=True))
        self.assertEqual(body['expiresInSeconds'], 600)
        self.assertEqual(body['maxDownloads'], 3)
        self.assertEqual(created.headers['Cache-Control'], 'no-store, max-age=0')

        downloaded = self.client.get(body['downloadPath'])
        self.assertEqual(downloaded.status_code, 200)
        self.assertEqual(downloaded.data, b'generated-kmz')
        self.assertEqual(
            downloaded.mimetype,
            'application/vnd.google-earth.kmz',
        )
        self.assertEqual(downloaded.headers['X-Downloads-Remaining'], '2')
        self.assertEqual(downloaded.headers['Cache-Control'], 'no-store, max-age=0')

    def test_public_get_rejects_legacy_data_payloads(self):
        response = self.client.get('/api/threats-kmz?data=encoded-threats')
        self.assertEqual(response.status_code, 400)
        self.assertIn('Legacy data links', response.get_json()['error'])


if __name__ == '__main__':
    unittest.main()
