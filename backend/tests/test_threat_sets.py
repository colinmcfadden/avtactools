"""Threats kept in the Library (``/api/threatsets``): synced like a saved LZ, behind ``threats`` and, to write, ``cloud_save``."""

import sys
import uuid
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from models import SavedThreatSet, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from routes.threat_set_routes import MAX_BYTES, threat_set_bp  # noqa: E402

SPEC = load_spec()
THREAT = {
    'id': 'threat-1', 'name': 'SA-11', 'milstdId': 'SHGPEWMAI------', 'lat': 34.5, 'lon': -84.1, 'information': '', 'source': 'SOF',
    'showThreat': True, 'visible': True, 'radars': [],
    'mask': {'bounds': [[34.0, -84.6], [35.0, -83.6]], 'radars': [{'type': 0, 'png': 'data:image/png;base64,AAAA'}],
             'for': {'lat': 34.5, 'lon': -84.1, 'radars': []}},
}
DOCUMENT = {'version': 1, 'threats': [THREAT]}


class ThreatSetCase(NativeAuthCase):
    extra_blueprints = (threat_set_bp, sync_bp)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}

    def turn(self, **features):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = features
            db.session.commit()

    def conforms(self, response, method, template):
        problems = check_response(SPEC, template, method, response.status_code, response.get_json())
        self.assertEqual(problems, [], f'{method} {template} {response.status_code}')
        return response

    def new_set(self, name='OP DK THREATS', **extra):
        response = self.client.post('/api/threatsets', headers=self.head, json={'name': name, 'data': DOCUMENT, **extra})
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        self.conforms(response, 'POST', '/api/threatsets')
        return response.get_json()

    def here(self, saved):
        return f"/api/threatsets/{saved['id']}"

    def feed(self, since=0):
        response = self.client.get(f'/api/sync/changes?since={since}', headers=self.head)
        self.assertEqual(response.status_code, 200)
        self.conforms(response, 'GET', '/api/sync/changes')
        return response.get_json()


class EntitlementTests(ThreatSetCase):
    def test_threats_off_is_refused_at_every_route(self):
        saved = self.new_set()
        self.turn(threats=False)
        here = self.here(saved)
        for method, url, body in (('get', '/api/threatsets', None), ('post', '/api/threatsets', {'name': 'X', 'data': DOCUMENT}),
                                  ('get', here, None), ('put', here, {'name': 'X'}), ('delete', here, None)):
            response = getattr(self.client, method)(url, headers=self.head, json=body)
            self.assertEqual(response.status_code, 403, f'{method} {url}')
            self.assertEqual(response.get_json()['code'], 'feature_disabled')

    def test_cloud_save_off_refuses_writes_and_leaves_reading(self):
        saved = self.new_set()
        self.turn(cloud_save=False)
        self.assertEqual(self.client.get(self.here(saved), headers=self.head).status_code, 200)
        self.assertEqual(self.client.post('/api/threatsets', headers=self.head, json={'name': 'X', 'data': DOCUMENT}).status_code, 403)
        self.assertEqual(self.client.put(self.here(saved), headers=self.head, json={'name': 'X'}).status_code, 403)


class SavingTests(ThreatSetCase):
    def test_a_set_keeps_its_threats_and_masks_whole(self):
        saved = self.new_set()
        self.assertEqual(saved['threat_count'], 1)
        full = self.conforms(self.client.get(self.here(saved), headers=self.head), 'GET', '/api/threatsets/{id}').get_json()
        self.assertEqual(full['data'], DOCUMENT)

    def test_the_list_says_how_many_without_the_threats(self):
        self.new_set()
        rows = self.conforms(self.client.get('/api/threatsets', headers=self.head), 'GET', '/api/threatsets').get_json()
        self.assertEqual([(r['name'], r['threat_count']) for r in rows], [('OP DK THREATS', 1)])
        self.assertNotIn('data', rows[0])

    def test_a_set_may_be_emptied_and_says_so(self):
        saved = self.new_set()
        response = self.client.put(self.here(saved), headers=self.head, json={'data': {'version': 1, 'threats': []}})
        self.conforms(response, 'PUT', '/api/threatsets/{id}')
        self.assertEqual(response.get_json()['threat_count'], 0)

    def test_a_create_with_the_same_identity_makes_one(self):
        identity = str(uuid.uuid4())
        first = self.new_set(client_uuid=identity)
        again = self.new_set(name='ANOTHER', client_uuid=identity)
        self.assertEqual(first['id'], again['id'])
        self.assertEqual(again['name'], 'OP DK THREATS')

    def test_what_cannot_be_stored_is_refused_in_words(self):
        cases = [({'name': 'X'}, 'missing_fields'), ({'name': 'X' * 101, 'data': DOCUMENT}, 'invalid_name'), ({'name': '  ', 'data': DOCUMENT}, 'invalid_name'),
                 ({'name': 'X', 'data': []}, 'invalid_data'), ({'name': 'X', 'data': {'threats': {}}}, 'invalid_data'), ({'name': 'X', 'data': DOCUMENT, 'client_uuid': 'no'}, 'invalid_client_uuid')]
        for body, code in cases:
            response = self.client.post('/api/threatsets', headers=self.head, json=body)
            self.assertEqual((response.status_code, response.get_json()['code']), (400, code), body)

    def test_a_set_too_large_is_refused_before_it_is_read(self):
        big = {'name': 'X', 'data': {'version': 1, 'threats': [{'png': 'A' * MAX_BYTES}]}}
        response = self.client.post('/api/threatsets', headers=self.head, json=big)
        self.assertEqual((response.status_code, response.get_json()['code']), (413, 'too_large'))

    def test_another_account_cannot_reach_it(self):
        saved = self.new_set()
        self.make_account('other@example.com')
        head = {'Authorization': f'Bearer {self.login(email="other@example.com")["access_token"]}'}
        self.assertEqual(self.client.get(self.here(saved), headers=head).status_code, 404)
        self.assertEqual(self.client.get('/api/threatsets', headers=head).get_json(), [])


class SyncTests(ThreatSetCase):
    def test_a_stale_edit_overwrites_nothing_and_answers_the_server_copy(self):
        saved = self.new_set()
        self.client.put(self.here(saved), headers={**self.head, 'If-Match': '"1"'}, json={'name': 'NEWER'})
        response = self.client.put(self.here(saved), headers={**self.head, 'If-Match': '"1"'}, json={'name': 'STALE'})
        self.conforms(response, 'PUT', '/api/threatsets/{id}')
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()['server']['name'], 'NEWER')
        self.assertEqual(response.get_json()['server']['data'], DOCUMENT)

    def test_the_feed_carries_the_document(self):
        saved = self.new_set()
        change = [c for c in self.feed()['changes'] if c['type'] == 'threatset']
        self.assertEqual([(c['id'], c['client_uuid'], c['data']) for c in change], [(saved['id'], saved['client_uuid'], DOCUMENT)])

    def test_a_deletion_is_a_tombstone_with_nothing_left_in_it(self):
        saved = self.new_set()
        cursor = self.feed()['cursor']
        response = self.client.delete(self.here(saved), headers=self.head)
        self.conforms(response, 'DELETE', '/api/threatsets/{id}')
        self.assertEqual(self.client.get(self.here(saved), headers=self.head).status_code, 404)
        self.assertEqual(self.client.get('/api/threatsets', headers=self.head).get_json(), [])
        change = self.feed(cursor)['changes'][0]
        self.assertEqual((change['type'], change['deleted'], change['name'], change['data']), ('threatset', True, '', {}))
        with self.app.app_context():
            row = SavedThreatSet.query.one()
            self.assertEqual((row.threat_data, row.threat_count), ({}, 0))
        self.assertEqual(self.client.delete(self.here(saved), headers=self.head).status_code, 200)
