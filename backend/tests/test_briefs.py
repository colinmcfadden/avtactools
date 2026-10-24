"""Mission briefs kept in the Library (``/api/briefs``): synced like a saved LZ, behind ``mission_brief``."""

import sys
import uuid
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from entitlements import DEFAULT_OFF, FEATURE_KEYS  # noqa: E402
from models import SavedBrief, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.admin_routes import admin_bp  # noqa: E402
from routes.brief_routes import brief_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402

SPEC = load_spec()
DOCUMENT = {
    'version': 1, 'template': {'id': 'amb', 'version': 1},
    'source': {'pack': None, 'lzs': ['d-1'], 'routeSets': []},
    'flow': [{'ref': 'lz:d-1', 'role': 'LZ'}],
    'answers': {'2.b': {'value': 'Move C Co to LZ HAWK.'}, '5.a.iii': {'mode': 'standard'}},
}


class BriefCase(NativeAuthCase):
    extra_blueprints = (brief_bp, sync_bp)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = self.auth(self.login())
        self.give('pilot@example.com')

    def auth(self, session):
        return {'Authorization': f'Bearer {session["access_token"]}'}

    def give(self, email, **features):
        """Turn the feature on as an admin does for a tester (it is not launched: entitlements.DEFAULT_OFF)."""
        with self.app.app_context():
            user = User.query.filter_by(email=email).one()
            user.features = {'mission_brief': True, **features}
            db.session.commit()

    def conforms(self, response, method, template):
        problems = check_response(SPEC, template, method, response.status_code, response.get_json())
        self.assertEqual(problems, [], f'{method} {template} {response.status_code}')
        return response

    def new_brief(self, name='OP DK BRIEF', headers=None, **extra):
        response = self.client.post('/api/briefs', headers=headers or self.head,
                                    json={'name': name, 'data': DOCUMENT, **extra})
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        self.conforms(response, 'POST', '/api/briefs')
        return response.get_json()

    def feed(self, since=0, headers=None):
        response = self.client.get(f'/api/sync/changes?since={since}', headers=headers or self.head)
        self.assertEqual(response.status_code, 200)
        self.conforms(response, 'GET', '/api/sync/changes')
        return response.get_json()

    def rows(self, **filters):
        with self.app.app_context():
            return SavedBrief.query.filter_by(**filters).all()


class EntitlementTests(BriefCase):
    def test_it_is_a_feature_off_until_launch(self):
        self.assertIn('mission_brief', FEATURE_KEYS)
        self.assertIn('mission_brief', DEFAULT_OFF)

    def test_every_route_refuses_an_account_without_it(self):
        brief = self.new_brief()
        self.give('pilot@example.com', mission_brief=False)
        for method, path, body in (('get', '/api/briefs', None), ('post', '/api/briefs', {'name': 'x', 'data': {}}),
                                   ('get', f'/api/briefs/{brief["id"]}', None),
                                   ('put', f'/api/briefs/{brief["id"]}', {'name': 'x'}),
                                   ('delete', f'/api/briefs/{brief["id"]}', None)):
            with self.subTest(route=f'{method} {path}'):
                response = getattr(self.client, method)(path, headers=self.head, json=body)
                self.assertEqual(response.status_code, 403)
                self.assertEqual(response.get_json()['code'], 'feature_disabled')
        # ...and nothing was changed by the refused write or delete.
        (row,) = self.rows()
        self.assertEqual((row.name, row.revision, row.deleted_at), ('OP DK BRIEF', 1, None))

    def test_an_account_nobody_ticked_has_it_off(self):
        self.make_account('other@example.com')
        other = self.auth(self.login(email='other@example.com'))
        self.assertFalse(self.client.get('/api/auth/me', headers=other).get_json()['features']['mission_brief'])
        self.assertEqual(self.client.get('/api/briefs', headers=other).status_code, 403)

    def test_a_write_needs_cloud_save_too(self):
        brief = self.new_brief()
        self.give('pilot@example.com', cloud_save=False)
        self.assertEqual(self.client.post('/api/briefs', headers=self.head, json={'name': 'x', 'data': {}}).status_code, 403)
        self.assertEqual(self.client.put(f'/api/briefs/{brief["id"]}', headers=self.head, json={'name': 'x'}).status_code, 403)
        self.assertEqual(self.client.get(f'/api/briefs/{brief["id"]}', headers=self.head).status_code, 200)


class WebRoundTripTests(BriefCase):
    """What the web sends: no If-Match, no Idempotency-Key, no client_uuid. The last writer wins."""

    def test_round_trip(self):
        created = self.client.post('/api/briefs', headers=self.head, json={'name': 'BRIEF A', 'data': DOCUMENT})
        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.headers['ETag'], '"1"')
        body = self.conforms(created, 'POST', '/api/briefs').get_json()
        self.assertNotIn('data', body)                                   # a create answers the summary

        listed = self.conforms(self.client.get('/api/briefs', headers=self.head), 'GET', '/api/briefs').get_json()
        self.assertEqual([b['id'] for b in listed], [body['id']])
        self.assertNotIn('data', listed[0])

        got = self.conforms(self.client.get(f'/api/briefs/{body["id"]}', headers=self.head), 'GET', '/api/briefs/{id}')
        self.assertEqual(got.get_json()['data'], DOCUMENT)

        changed = {**DOCUMENT, 'answers': {'2.b': {'value': 'changed'}}}
        updated = self.client.put(f'/api/briefs/{body["id"]}', headers=self.head, json={'name': 'BRIEF B', 'data': changed})
        self.conforms(updated, 'PUT', '/api/briefs/{id}')
        self.assertEqual((updated.status_code, updated.get_json()['revision']), (200, 2))
        # Someone else's change is simply overwritten without If-Match, as the web has always behaved.
        again = self.client.put(f'/api/briefs/{body["id"]}', headers=self.head, json={'data': DOCUMENT})
        self.assertEqual(again.status_code, 200)
        got = self.client.get(f'/api/briefs/{body["id"]}', headers=self.head).get_json()
        self.assertEqual((got['name'], got['data'], got['revision']), ('BRIEF B', DOCUMENT, 3))

        deleted = self.conforms(self.client.delete(f'/api/briefs/{body["id"]}', headers=self.head), 'DELETE', '/api/briefs/{id}')
        self.assertEqual(deleted.get_json(), {'status': 'deleted', 'revision': 4})
        self.assertEqual(self.client.get(f'/api/briefs/{body["id"]}', headers=self.head).status_code, 404)
        self.assertEqual(self.client.get('/api/briefs', headers=self.head).get_json(), [])
        self.assertEqual(self.client.put(f'/api/briefs/{body["id"]}', headers=self.head, json={'name': 'x'}).status_code, 404)
        # Deleting twice is not an error.
        self.assertEqual(self.client.delete(f'/api/briefs/{body["id"]}', headers=self.head).get_json(), {'status': 'deleted'})

    def test_what_cannot_be_stored_is_refused(self):
        for body, code in (({'name': 'x'}, 'missing_fields'), ({'data': {}}, 'missing_fields'),
                           ({'name': '', 'data': {}}, 'invalid_name'), ({'name': '   ', 'data': {}}, 'invalid_name'),
                           ({'name': 'x' * 101, 'data': {}}, 'invalid_name'), ({'name': 7, 'data': {}}, 'invalid_name'),
                           ({'name': 'x', 'data': []}, 'invalid_data'), ({'name': 'x', 'data': None}, 'invalid_data'),
                           ({'name': 'x', 'data': {}, 'client_uuid': 'nope'}, 'invalid_client_uuid')):
            with self.subTest(body=body):
                response = self.conforms(self.client.post('/api/briefs', headers=self.head, json=body), 'POST', '/api/briefs')
                self.assertEqual((response.status_code, response.get_json()['code']), (400, code))
        self.assertEqual(self.rows(), [])
        # 100 characters, counted as characters, fit.
        self.new_brief(name='É' * 100)

    def test_an_edit_that_cannot_be_stored_changes_nothing(self):
        brief = self.new_brief()
        for body in ({'name': ''}, {'data': 'text'}, {'name': 'ok', 'data': [1]}):
            with self.subTest(body=body):
                self.assertEqual(self.client.put(f'/api/briefs/{brief["id"]}', headers=self.head, json=body).status_code, 400)
        (row,) = self.rows()
        self.assertEqual((row.name, row.brief_data, row.revision), ('OP DK BRIEF', DOCUMENT, 1))

    def test_one_person_cannot_see_or_touch_anothers(self):
        brief = self.new_brief()
        self.make_account('other@example.com')
        other = self.auth(self.login(email='other@example.com'))
        self.give('other@example.com')
        path = f'/api/briefs/{brief["id"]}'
        self.assertEqual(self.client.get(path, headers=other).status_code, 404)
        self.assertEqual(self.client.put(path, headers=other, json={'name': 'x'}).status_code, 404)
        self.assertEqual(self.client.delete(path, headers=other).status_code, 404)
        self.assertEqual(self.client.get('/api/briefs', headers=other).get_json(), [])
        self.assertEqual(self.feed(headers=other)['changes'], [])
        self.assertEqual(self.client.get(path, headers=self.head).get_json()['name'], 'OP DK BRIEF')

    def test_everything_needs_a_token(self):
        for method, path in (('get', '/api/briefs'), ('post', '/api/briefs'), ('get', '/api/briefs/1'),
                             ('put', '/api/briefs/1'), ('delete', '/api/briefs/1')):
            with self.subTest(route=f'{method} {path}'):
                self.assertEqual(getattr(self.client, method)(path).status_code, 401)


class SyncTests(BriefCase):
    """What a device does: its own identity, If-Match, Idempotency-Key, and the change feed."""

    def test_creating_the_same_brief_twice_gives_the_first(self):
        mine = str(uuid.uuid4())
        first = self.client.post('/api/briefs', headers=self.head, json={'name': 'A', 'data': DOCUMENT, 'client_uuid': mine})
        again = self.client.post('/api/briefs', headers=self.head, json={'name': 'B', 'data': {}, 'client_uuid': mine.upper()})
        self.assertEqual((first.status_code, again.status_code), (201, 200))
        self.conforms(again, 'POST', '/api/briefs')
        self.assertEqual(again.get_json(), first.get_json())
        self.assertEqual(again.get_json()['client_uuid'], mine)
        self.assertEqual(len(self.rows()), 1)

    def test_a_stale_edit_overwrites_nothing(self):
        brief = self.new_brief()
        path = f'/api/briefs/{brief["id"]}'
        self.assertEqual(self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'MINE'}).status_code, 200)
        stale = self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'THEIRS'})
        self.assertEqual(stale.status_code, 409)
        self.conforms(stale, 'PUT', '/api/briefs/{id}')
        self.assertEqual((stale.get_json()['code'], stale.get_json()['server']['name']), ('revision_conflict', 'MINE'))
        self.assertEqual(stale.get_json()['server']['data'], DOCUMENT)
        self.assertEqual(stale.headers['ETag'], '"2"')
        # A delete on a stale revision deletes nothing either.
        refused = self.client.delete(path, headers={**self.head, 'If-Match': '"1"'})
        self.assertEqual(refused.status_code, 409)
        self.conforms(refused, 'DELETE', '/api/briefs/{id}')
        self.assertIsNone(self.rows()[0].deleted_at)
        self.assertEqual(self.client.put(path, headers={**self.head, 'If-Match': 'x'}, json={}).status_code, 400)

    def test_a_retried_write_is_answered_not_refused(self):
        brief = self.new_brief()
        path = f'/api/briefs/{brief["id"]}'
        write = {'headers': {**self.head, 'If-Match': '"1"', 'Idempotency-Key': 'edit-1'}, 'json': {'name': 'ONCE'}}
        self.assertEqual(self.client.put(path, **write).status_code, 200)
        again = self.client.put(path, **write)
        self.assertEqual(again.status_code, 200)
        self.conforms(again, 'PUT', '/api/briefs/{id}')
        self.assertEqual((again.get_json()['name'], again.get_json()['revision']), ('ONCE', 2))
        self.assertEqual(self.rows()[0].revision, 2)

    def test_the_feed_carries_briefs_and_their_deletion(self):
        brief = self.new_brief()
        (change,) = self.feed()['changes']
        self.assertEqual((change['type'], change['id'], change['name'], change['data'], change['deleted']),
                         ('brief', brief['id'], 'OP DK BRIEF', DOCUMENT, False))
        self.assertEqual(change['client_uuid'], brief['client_uuid'])
        cursor = self.feed()['cursor']
        self.client.delete(f'/api/briefs/{brief["id"]}', headers=self.head)
        (stone,) = self.feed(since=cursor)['changes']
        self.assertEqual((stone['type'], stone['deleted'], stone['name'], stone['data']), ('brief', True, '', {}))
        (row,) = self.rows()
        self.assertEqual((row.name, row.brief_data), ('', {}))

    def test_the_feed_orders_briefs_among_the_other_kinds(self):
        self.new_brief(name='FIRST')
        with self.app.app_context():
            from models import SavedLZ
            import sync_support
            uid = User.query.filter_by(email='pilot@example.com').one().id
            lz = SavedLZ(user_id=uid, name='LZ', lz_data={}, client_uuid=sync_support.new_client_uuid())
            db.session.add(lz)
            sync_support.stamp(lz, uid)
            db.session.commit()
        self.new_brief(name='LAST')
        self.assertEqual([(c['type'], c['name']) for c in self.feed()['changes']],
                         [('brief', 'FIRST'), ('lz', 'LZ'), ('brief', 'LAST')])


class AccountDeletionTests(BriefCase):
    extra_blueprints = BriefCase.extra_blueprints + (admin_bp,)

    def test_deleting_the_account_removes_its_briefs(self):
        self.new_brief()
        session = self.login()
        response = self.client.delete('/api/auth/me', headers={**ANDROID, **self.auth(session)},
                                      json={'confirm': 'DELETE', 'password': PASSWORD})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        self.assertEqual(self.rows(), [])

    def test_an_admin_deleting_the_account_removes_them_too(self):
        self.new_brief()
        self.app.secret_key = 'test-only-admin-session-key'
        self.app.root_path = str(BACKEND_DIR)        # so the dashboard's templates are found, as in production
        with self.app.app_context():
            admin = User(email='admin@example.com', name='Admin', role='admin')
            db.session.add(admin)
            db.session.commit()
            admin_id, uid = admin.id, User.query.filter_by(email='pilot@example.com').one().id
        dashboard = self.app.test_client()
        with dashboard.session_transaction() as sess:
            sess['admin_uid'] = admin_id
            sess['csrf'] = 'token'
        response = dashboard.post(f'/admin/users/{uid}/delete', data={'csrf': 'token', 'confirm_email': 'pilot@example.com'})
        self.assertEqual(response.status_code, 302)
        self.assertEqual(self.rows(), [])

if __name__ == '__main__':  # pragma: no cover
    import unittest
    unittest.main()
