"""Saved LZs, routes and point sets, and keeping them in step across devices."""

import io
import json
import sys
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import sync_support  # noqa: E402
from auth_harness import ANDROID, IOS, NativeAuthCase  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from models import (  # noqa: E402
    SavedLZ, SavedPointSet, SavedRoute, SyncCounter, User, db,
)
from routes.lz_routes import lz_bp  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402
from routes.saved_routes import saved_routes_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402

POINTS = [{"name": "A", "lat": 34.5, "lon": -84.1}, {"name": "B", "lat": 34.6, "lon": -84.2}]


class SavedRecordCase(NativeAuthCase):
    extra_blueprints = (lz_bp, saved_routes_bp, point_sets_bp, sync_bp)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = self.auth(self.login())

    def auth(self, session):
        return {'Authorization': f'Bearer {session["access_token"]}'}

    def other_user(self):
        self.make_account('other@example.com')
        return self.auth(self.login(email='other@example.com'))

    # LZs
    def new_lz(self, name='LZ HAWK', headers=None, **extra):
        response = self.client.post('/api/lz', headers=headers or self.head,
                                    json={'name': name, 'lz_data': {'target': [34.5, -84.1]}, **extra})
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        return response.get_json()

    # Routes (multipart, as the web sends them)
    def new_route(self, name='Route 1', kind='sketch', with_file=False, headers=None, **fields):
        data = {'name': name, 'kind': kind, 'route_data': json.dumps({'points': []}), **fields}
        if with_file:
            data['msnx'] = (io.BytesIO(b'PK\x03\x04 mission bytes'), 'mission.msnx')
        response = self.client.post('/api/routes', headers=headers or self.head, data=data,
                                    content_type='multipart/form-data')
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        return response.get_json()

    def new_set(self, name='Points', headers=None, **extra):
        response = self.client.post('/api/pointsets', headers=headers or self.head,
                                    json={'name': name, 'points': POINTS, **extra})
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        return response.get_json()

    def feed(self, since=0, limit=None, headers=None):
        query = f'?since={since}' + (f'&limit={limit}' if limit else '')
        return self.client.get(f'/api/sync/changes{query}', headers=headers or self.head)

    def rows(self, model, **filters):
        with self.app.app_context():
            return model.query.filter_by(**filters).all()


class WebStillWorksTests(SavedRecordCase):
    """Nothing tested these routes before. This is what the web app does with them, unchanged."""

    def test_lz_round_trip(self):
        created = self.client.post('/api/lz', headers=self.head, json={'name': 'LZ A', 'lz_data': {'k': 1}})
        self.assertEqual(created.status_code, 201)
        body = created.get_json()
        for field in ('id', 'name', 'created_at', 'updated_at'):
            self.assertIn(field, body)
        self.assertEqual(body['name'], 'LZ A')

        listed = self.client.get('/api/lz', headers=self.head).get_json()
        self.assertEqual([r['id'] for r in listed], [body['id']])
        self.assertNotIn('lz_data', listed[0])                       # the list is summaries

        got = self.client.get(f'/api/lz/{body["id"]}', headers=self.head).get_json()
        self.assertEqual(got['lz_data'], {'k': 1})

        updated = self.client.put(f'/api/lz/{body["id"]}', headers=self.head, json={'name': 'LZ B', 'lz_data': {'k': 2}})
        self.assertEqual(updated.status_code, 200)
        self.assertEqual(updated.get_json()['name'], 'LZ B')
        self.assertEqual(self.client.get(f'/api/lz/{body["id"]}', headers=self.head).get_json()['lz_data'], {'k': 2})

        deleted = self.client.delete(f'/api/lz/{body["id"]}', headers=self.head)
        self.assertEqual(deleted.status_code, 200)
        self.assertEqual(deleted.get_json()['status'], 'deleted')
        self.assertEqual(self.client.get(f'/api/lz/{body["id"]}', headers=self.head).status_code, 404)
        self.assertEqual(self.client.get('/api/lz', headers=self.head).get_json(), [])

    def test_lz_validation_and_not_found_are_as_before(self):
        self.assertEqual(self.client.post('/api/lz', headers=self.head, json={'name': 'x'}).status_code, 400)
        self.assertEqual(self.client.post('/api/lz', headers=self.head, json={'lz_data': {}}).status_code, 400)
        self.assertEqual(self.client.get('/api/lz/999', headers=self.head).get_json(), {'error': 'Not found'})
        self.assertEqual(self.client.put('/api/lz/999', headers=self.head, json={}).status_code, 404)
        self.assertEqual(self.client.delete('/api/lz/999', headers=self.head).status_code, 404)

    def test_a_partial_lz_update_changes_only_what_is_sent(self):
        lz = self.new_lz()
        self.client.put(f'/api/lz/{lz["id"]}', headers=self.head, json={'name': 'renamed'})
        got = self.client.get(f'/api/lz/{lz["id"]}', headers=self.head).get_json()
        self.assertEqual((got['name'], got['lz_data']), ('renamed', {'target': [34.5, -84.1]}))

    def test_everything_needs_a_token(self):
        for method, path in (('get', '/api/lz'), ('post', '/api/lz'), ('get', '/api/routes'),
                             ('get', '/api/pointsets'), ('get', '/api/sync/changes')):
            with self.subTest(route=f'{method} {path}'):
                self.assertEqual(getattr(self.client, method)(path).status_code, 401)

    def test_one_user_cannot_see_or_touch_anothers(self):
        lz, route, pset = self.new_lz(), self.new_route(), self.new_set()
        other = self.other_user()
        # (path, how that kind is edited: the web sends routes as multipart, the rest as JSON)
        edits = (
            (f'/api/lz/{lz["id"]}', {'json': {'name': 'x'}}),
            (f'/api/routes/{route["id"]}', {'data': {'name': 'x'}, 'content_type': 'multipart/form-data'}),
            (f'/api/pointsets/{pset["id"]}', {'json': {'name': 'x'}}),
        )
        for path, edit in edits:
            with self.subTest(path=path):
                self.assertEqual(self.client.get(path, headers=other).status_code, 404)
                self.assertEqual(self.client.put(path, headers=other, **edit).status_code, 404)
                self.assertEqual(self.client.delete(path, headers=other).status_code, 404)
        # ...and none of it touched the owner's records.
        self.assertEqual(self.client.get(f'/api/lz/{lz["id"]}', headers=self.head).get_json()['name'], 'LZ HAWK')
        self.assertEqual(self.client.get('/api/lz', headers=other).get_json(), [])
        self.assertEqual(self.feed(headers=other).get_json()['changes'], [])

    def test_route_round_trip_with_a_mission_file(self):
        route = self.new_route('Mission', kind='mission', with_file=True)
        for field in ('id', 'name', 'kind', 'file_name', 'created_at', 'updated_at'):
            self.assertIn(field, route)
        self.assertEqual((route['kind'], route['file_name']), ('mission', 'mission.msnx'))
        self.assertEqual(self.client.get(f'/api/routes/{route["id"]}', headers=self.head).get_json()['route_data'], {'points': []})
        download = self.client.get(f'/api/routes/{route["id"]}/file', headers=self.head)
        self.assertEqual(download.data, b'PK\x03\x04 mission bytes')

        renamed = self.client.put(f'/api/routes/{route["id"]}', headers=self.head,
                                  data={'name': 'Mission 2'}, content_type='multipart/form-data')
        self.assertEqual(renamed.get_json()['name'], 'Mission 2')
        self.assertEqual(self.client.delete(f'/api/routes/{route["id"]}', headers=self.head).get_json()['status'], 'deleted')
        self.assertEqual(self.client.get(f'/api/routes/{route["id"]}/file', headers=self.head).status_code, 404)
        self.assertEqual(self.client.get('/api/routes', headers=self.head).get_json(), [])

    def test_route_validation_is_as_before(self):
        post = lambda **d: self.client.post('/api/routes', headers=self.head, data=d, content_type='multipart/form-data')  # noqa: E731
        self.assertEqual(post(name='x').status_code, 400)
        self.assertEqual(post(name='x', route_data='{}', kind='nope').status_code, 400)
        self.assertEqual(post(name='x', route_data='not json').status_code, 400)
        self.assertEqual(post(name='x', route_data='{}', kind='mission').status_code, 400)   # a mission needs its file

    def test_point_set_round_trip(self):
        pset = self.new_set('Alpha')
        self.assertEqual(pset['point_count'], 2)
        got = self.client.get(f'/api/pointsets/{pset["id"]}', headers=self.head).get_json()
        self.assertEqual(got['points'], POINTS)
        updated = self.client.put(f'/api/pointsets/{pset["id"]}', headers=self.head, json={'name': 'Beta'})
        self.assertEqual(updated.get_json()['name'], 'Beta')
        self.assertEqual(self.client.delete(f'/api/pointsets/{pset["id"]}', headers=self.head).get_json()['status'], 'deleted')
        self.assertEqual(self.client.get('/api/pointsets', headers=self.head).get_json(), [])
        self.assertEqual(self.client.post('/api/pointsets', headers=self.head, json={'name': 'x', 'points': []}).status_code, 400)

    def test_a_request_with_none_of_the_new_headers_is_last_writer_wins_as_ever(self):
        lz = self.new_lz()
        for i in range(3):
            self.assertEqual(self.client.put(f'/api/lz/{lz["id"]}', headers=self.head, json={'name': f'v{i}'}).status_code, 200)


class IdentityAndRevisionTests(SavedRecordCase):
    def test_every_record_has_an_identity_and_a_revision_from_birth(self):
        for record in (self.new_lz(), self.new_route(), self.new_set()):
            self.assertEqual(record['revision'], 1)
            self.assertTrue(uuid.UUID(record['client_uuid']))

    def test_a_device_names_its_own_record(self):
        mine = str(uuid.uuid4())
        self.assertEqual(self.new_lz(client_uuid=mine)['client_uuid'], mine)
        self.assertEqual(self.new_set(client_uuid=mine.upper())['client_uuid'], mine)       # case is folded
        self.assertEqual(self.new_route(client_uuid=mine)['client_uuid'], mine)

    def test_creating_the_same_record_twice_returns_the_first(self):
        # A retry after a lost response must not leave two copies.
        mine = str(uuid.uuid4())
        for make, model in ((self.new_lz, SavedLZ), (self.new_route, SavedRoute), (self.new_set, SavedPointSet)):
            with self.subTest(model=model.__name__):
                first = make(client_uuid=mine)
                again = make(client_uuid=mine)
                self.assertEqual(first['id'], again['id'])
                self.assertEqual(len(self.rows(model)), 1)

    def test_a_retried_create_is_200_not_201(self):
        mine = str(uuid.uuid4())
        first = self.client.post('/api/lz', headers=self.head, json={'name': 'x', 'lz_data': {}, 'client_uuid': mine})
        again = self.client.post('/api/lz', headers=self.head, json={'name': 'x', 'lz_data': {}, 'client_uuid': mine})
        self.assertEqual((first.status_code, again.status_code), (201, 200))

    def test_the_database_itself_refuses_two_records_with_one_identity(self):
        # The application checks first, but two simultaneous creates can both pass
        # that check; the unique index is what actually stops the duplicate.
        from sqlalchemy.exc import IntegrityError
        mine = str(uuid.uuid4())
        with self.app.app_context():
            uid = User.query.one().id
            db.session.add(SavedLZ(user_id=uid, name='a', lz_data={}, client_uuid=mine))
            db.session.commit()
            db.session.add(SavedLZ(user_id=uid, name='b', lz_data={}, client_uuid=mine))
            with self.assertRaises(IntegrityError):
                db.session.commit()
            db.session.rollback()

    def test_records_without_an_identity_do_not_collide(self):
        # Rows that predate sync have none; NULLs are not equal for uniqueness.
        with self.app.app_context():
            uid = User.query.one().id
            db.session.add_all([SavedLZ(user_id=uid, name=n, lz_data={}) for n in 'abc'])
            db.session.commit()
            self.assertEqual(SavedLZ.query.count(), 3)

    def test_two_users_may_use_the_same_identity(self):
        mine = str(uuid.uuid4())
        self.new_lz(client_uuid=mine)
        other = self.new_lz(client_uuid=mine, headers=self.other_user())
        self.assertEqual(other['client_uuid'], mine)
        self.assertEqual(len(self.rows(SavedLZ)), 2)

    def test_a_malformed_identity_is_refused(self):
        for bad in ('not-a-uuid', 'x' * 40, 5, '', '<script>'):
            with self.subTest(bad=bad):
                response = self.client.post('/api/lz', headers=self.head, json={'name': 'x', 'lz_data': {}, 'client_uuid': bad})
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'invalid_client_uuid')
        self.assertEqual(self.rows(SavedLZ), [])

    def test_each_edit_bumps_the_revision_and_says_so(self):
        lz = self.new_lz()
        for expected in (2, 3):
            response = self.client.put(f'/api/lz/{lz["id"]}', headers=self.head, json={'name': f'v{expected}'})
            self.assertEqual(response.get_json()['revision'], expected)
            self.assertEqual(response.headers['ETag'], f'"{expected}"')
        got = self.client.get(f'/api/lz/{lz["id"]}', headers=self.head)
        self.assertEqual((got.get_json()['revision'], got.headers['ETag']), (3, '"3"'))

    def test_an_edit_the_server_refuses_does_not_bump_the_revision(self):
        route = self.new_route()
        bad = self.client.put(f'/api/routes/{route["id"]}', headers=self.head, data={'route_data': 'not json'},
                              content_type='multipart/form-data')
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(self.client.get(f'/api/routes/{route["id"]}', headers=self.head).get_json()['revision'], 1)


class ConflictTests(SavedRecordCase):
    """Two places edit the same record: nothing may be silently overwritten."""

    def put(self, lz, revision=None, **body):
        headers = dict(self.head)
        if revision is not None:
            headers['If-Match'] = f'"{revision}"'
        return self.client.put(f'/api/lz/{lz["id"]}', headers=headers, json=body)

    def test_an_edit_on_the_current_revision_goes_through(self):
        lz = self.new_lz()
        self.assertEqual(self.put(lz, revision=1, name='ok').status_code, 200)

    def test_an_edit_on_a_stale_revision_is_refused_with_the_servers_copy(self):
        lz = self.new_lz()
        self.put(lz, name='edited on the web')                                  # revision 2
        phone = self.put(lz, revision=1, name='edited on the phone', lz_data={'phone': True})
        self.assertEqual(phone.status_code, 409)
        body = phone.get_json()
        self.assertEqual(body['code'], 'revision_conflict')
        self.assertEqual(body['server']['name'], 'edited on the web')
        self.assertEqual(body['server']['revision'], 2)
        self.assertEqual(body['server']['lz_data'], {'target': [34.5, -84.1]})
        self.assertEqual(phone.headers['ETag'], '"2"')
        # Nothing was overwritten.
        got = self.client.get(f'/api/lz/{lz["id"]}', headers=self.head).get_json()
        self.assertEqual((got['name'], got['revision']), ('edited on the web', 2))

    def test_a_revision_from_the_future_is_also_a_conflict(self):
        lz = self.new_lz()
        self.assertEqual(self.put(lz, revision=9, name='x').status_code, 409)

    def test_the_conflict_is_the_same_for_routes_and_point_sets(self):
        route, pset = self.new_route(), self.new_set()
        self.client.put(f'/api/routes/{route["id"]}', headers=self.head, data={'name': 'web'}, content_type='multipart/form-data')
        self.client.put(f'/api/pointsets/{pset["id"]}', headers=self.head, json={'name': 'web'})
        stale = {**self.head, 'If-Match': '"1"'}
        r = self.client.put(f'/api/routes/{route["id"]}', headers=stale, data={'name': 'phone'}, content_type='multipart/form-data')
        p = self.client.put(f'/api/pointsets/{pset["id"]}', headers=stale, json={'name': 'phone'})
        self.assertEqual((r.status_code, p.status_code), (409, 409))
        self.assertEqual((r.get_json()['server']['name'], p.get_json()['server']['name']), ('web', 'web'))
        self.assertIn('route_data', r.get_json()['server'])
        self.assertIn('points', p.get_json()['server'])

    def test_if_match_forms_are_accepted(self):
        lz = self.new_lz()
        for value in ('"1"', '1', 'W/"1"', '*'):
            with self.subTest(value=value):
                fresh = self.new_lz()
                headers = {**self.head, 'If-Match': value}
                self.assertEqual(self.client.put(f'/api/lz/{fresh["id"]}', headers=headers, json={'name': 'x'}).status_code, 200)

    def test_a_malformed_if_match_is_refused(self):
        lz = self.new_lz()
        for value in ('abc', '"1" "2"', '1.5', '-1', ''):
            with self.subTest(value=value):
                headers = {**self.head, 'If-Match': value}
                response = self.client.put(f'/api/lz/{lz["id"]}', headers=headers, json={'name': 'x'})
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'invalid_if_match')

    def test_the_phone_and_the_web_scenario_end_to_end(self):
        # The phone syncs, goes offline and edits; the web edits the same LZ; the
        # phone reconnects. The web's work must survive and the phone's must be kept.
        lz = self.new_lz(client_uuid=str(uuid.uuid4()))
        seen = lz['revision']                                                    # the phone's copy
        self.put(lz, name='web edit')                                            # the web, meanwhile
        attempt = self.put(lz, revision=seen, name='phone edit')
        self.assertEqual(attempt.status_code, 409)
        server = attempt.get_json()['server']
        # The phone keeps its own as a separate record ("NAME (from phone)") and adopts the server's.
        kept = self.new_lz(name='phone edit (from phone)', client_uuid=str(uuid.uuid4()))
        names = {r['name'] for r in self.client.get('/api/lz', headers=self.head).get_json()}
        self.assertEqual(names, {server['name'], kept['name']})
        # With the server's revision it can now edit the original.
        self.assertEqual(self.put(lz, revision=server['revision'], name='merged').status_code, 200)


class IdempotencyTests(SavedRecordCase):
    """A write whose response was lost is retried; it must not look like a conflict with itself."""

    def test_a_retried_update_is_recognised_not_refused(self):
        lz = self.new_lz()
        headers = {**self.head, 'If-Match': '"1"', 'Idempotency-Key': 'put-abc-1'}
        first = self.client.put(f'/api/lz/{lz["id"]}', headers=headers, json={'name': 'once'})
        again = self.client.put(f'/api/lz/{lz["id"]}', headers=headers, json={'name': 'once'})   # still says If-Match 1
        self.assertEqual((first.status_code, again.status_code), (200, 200))
        self.assertEqual(again.get_json()['revision'], 2)                        # applied once, not twice
        self.assertEqual(self.rows(SavedLZ)[0].revision, 2)

    def test_a_different_key_with_a_stale_revision_is_still_a_conflict(self):
        lz = self.new_lz()
        self.client.put(f'/api/lz/{lz["id"]}', headers={**self.head, 'Idempotency-Key': 'k1'}, json={'name': 'a'})
        stale = {**self.head, 'If-Match': '"1"', 'Idempotency-Key': 'k2'}
        self.assertEqual(self.client.put(f'/api/lz/{lz["id"]}', headers=stale, json={'name': 'b'}).status_code, 409)

    def test_a_retried_delete_is_recognised(self):
        route = self.new_route()
        headers = {**self.head, 'If-Match': '"1"', 'Idempotency-Key': 'del-1'}
        self.assertEqual(self.client.delete(f'/api/routes/{route["id"]}', headers=headers).status_code, 200)
        self.assertEqual(self.client.delete(f'/api/routes/{route["id"]}', headers=headers).status_code, 200)
        self.assertEqual(self.rows(SavedRoute)[0].revision, 2)

    def test_a_malformed_key_is_ignored_not_trusted(self):
        lz = self.new_lz()
        for bad in ('has space', 'x' * 65, 'tab\there'):
            with self.subTest(bad=bad):
                response = self.client.put(f'/api/lz/{lz["id"]}', headers={**self.head, 'Idempotency-Key': bad}, json={'name': 'n'})
                self.assertEqual(response.status_code, 200)
        self.assertIsNone(self.rows(SavedLZ)[0].last_idem_key)


class DeletionTests(SavedRecordCase):
    def test_a_deletion_leaves_a_tombstone_with_the_content_gone(self):
        lz = self.new_lz('LZ SECRET', client_uuid=str(uuid.uuid4()))
        route = self.new_route('Secret route', kind='mission', with_file=True)
        pset = self.new_set('Secret points')
        for path in (f'/api/lz/{lz["id"]}', f'/api/routes/{route["id"]}', f'/api/pointsets/{pset["id"]}'):
            self.assertEqual(self.client.delete(path, headers=self.head).status_code, 200)

        (stone_lz,) = self.rows(SavedLZ)
        (stone_route,) = self.rows(SavedRoute)
        (stone_set,) = self.rows(SavedPointSet)
        for stone in (stone_lz, stone_route, stone_set):
            self.assertIsNotNone(stone.deleted_at)
            self.assertEqual(stone.revision, 2)
            self.assertEqual(stone.name, '')
        self.assertEqual(stone_lz.lz_data, {})
        self.assertEqual(stone_route.route_data, {})
        self.assertIsNone(stone_route.msnx_file)                                   # the biggest thing we held
        self.assertIsNone(stone_route.file_name)
        self.assertEqual(stone_set.points_data, [])
        self.assertEqual(stone_lz.client_uuid, lz['client_uuid'])                  # identity survives

    def test_a_deleted_record_is_gone_to_everything_but_the_feed(self):
        lz = self.new_lz()
        self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head)
        self.assertEqual(self.client.get(f'/api/lz/{lz["id"]}', headers=self.head).status_code, 404)
        self.assertEqual(self.client.put(f'/api/lz/{lz["id"]}', headers=self.head, json={'name': 'x'}).status_code, 404)
        self.assertEqual(self.client.get('/api/lz', headers=self.head).get_json(), [])

    def test_deleting_twice_is_not_an_error(self):
        lz = self.new_lz()
        self.assertEqual(self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head).status_code, 200)
        self.assertEqual(self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head).status_code, 200)
        self.assertEqual(self.rows(SavedLZ)[0].revision, 2)

    def test_a_delete_on_a_stale_revision_is_a_conflict_and_deletes_nothing(self):
        lz = self.new_lz()
        self.client.put(f'/api/lz/{lz["id"]}', headers=self.head, json={'name': 'edited elsewhere'})
        response = self.client.delete(f'/api/lz/{lz["id"]}', headers={**self.head, 'If-Match': '"1"'})
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()['server']['name'], 'edited elsewhere')
        self.assertEqual(self.client.get(f'/api/lz/{lz["id"]}', headers=self.head).status_code, 200)

    def test_a_deleted_identity_is_not_resurrected_by_a_retried_create(self):
        mine = str(uuid.uuid4())
        lz = self.new_lz(client_uuid=mine)
        self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head)
        again = self.client.post('/api/lz', headers=self.head, json={'name': 'x', 'lz_data': {}, 'client_uuid': mine})
        self.assertEqual(again.status_code, 200)                                    # the tombstone, not a new record
        self.assertEqual(len(self.rows(SavedLZ)), 1)
        self.assertEqual(self.client.get('/api/lz', headers=self.head).get_json(), [])


class ChangeFeedTests(SavedRecordCase):
    def test_it_returns_everything_from_the_start_across_all_three_kinds(self):
        lz, route, pset = self.new_lz(), self.new_route(kind='mission', with_file=True), self.new_set()
        body = self.feed().get_json()
        self.assertEqual([c['type'] for c in body['changes']], ['lz', 'route', 'pointset'])
        self.assertFalse(body['has_more'])
        self.assertEqual(body['cursor'], body['changes'][-1]['seq'])
        lz_change, route_change, set_change = body['changes']
        self.assertEqual((lz_change['id'], lz_change['data']), (lz['id'], {'target': [34.5, -84.1]}))
        self.assertEqual((route_change['kind'], route_change['has_file'], route_change['data']), ('mission', True, {'points': []}))
        self.assertEqual(set_change['data'], POINTS)
        for change in body['changes']:
            self.assertEqual((change['revision'], change['deleted']), (1, False))
            self.assertTrue(change['client_uuid'])

    def test_a_cursor_picks_up_where_it_left_off(self):
        self.new_lz('one')
        first = self.feed().get_json()
        self.assertEqual(self.feed(since=first['cursor']).get_json()['changes'], [])
        self.assertEqual(self.feed(since=first['cursor']).get_json()['cursor'], first['cursor'])
        self.new_lz('two')
        later = self.feed(since=first['cursor']).get_json()
        self.assertEqual([c['name'] for c in later['changes']], ['two'])
        self.assertGreater(later['cursor'], first['cursor'])

    def test_an_edit_moves_a_record_to_the_end_with_its_new_revision(self):
        a, b = self.new_lz('a'), self.new_lz('b')
        cursor = self.feed().get_json()['cursor']
        self.client.put(f'/api/lz/{a["id"]}', headers=self.head, json={'name': 'a2'})
        changes = self.feed(since=cursor).get_json()['changes']
        self.assertEqual([(c['name'], c['revision']) for c in changes], [('a2', 2)])
        full = self.feed().get_json()['changes']
        self.assertEqual([c['name'] for c in full], ['b', 'a2'])                    # one entry per record

    def test_deletions_come_through_as_tombstones(self):
        lz = self.new_lz()
        cursor = self.feed().get_json()['cursor']
        self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head)
        (change,) = self.feed(since=cursor).get_json()['changes']
        self.assertEqual((change['deleted'], change['id'], change['revision'], change['data']), (True, lz['id'], 2, {}))
        self.assertEqual(change['client_uuid'], lz['client_uuid'])

    def test_paging_visits_every_change_once(self):
        made = [self.new_lz(f'lz{i}')['id'] for i in range(3)] + [self.new_route(f'r{i}')['id'] for i in range(2)] + \
               [self.new_set(f's{i}')['id'] for i in range(2)]
        seen, cursor, pages = [], 0, 0
        while True:
            page = self.feed(since=cursor, limit=3).get_json()
            seen += [(c['type'], c['id']) for c in page['changes']]
            cursor, pages = page['cursor'], pages + 1
            if not page['has_more']:
                break
            self.assertEqual(len(page['changes']), 3)
        self.assertEqual(pages, 3)
        self.assertEqual(len(seen), 7)
        self.assertEqual(len(set(seen)), 7)                                          # none twice, none missing
        self.assertEqual(sorted(i for t, i in seen if t == 'lz'), sorted(made[:3]))

    def test_the_order_is_one_total_order_across_kinds(self):
        self.new_set(); self.new_lz(); self.new_route(); self.new_lz()               # noqa: E702
        seqs = [c['seq'] for c in self.feed().get_json()['changes']]
        self.assertEqual(seqs, sorted(seqs))
        self.assertEqual(len(set(seqs)), len(seqs))

    def test_a_user_sees_only_their_own_changes_and_their_own_order(self):
        self.new_lz('mine')
        other = self.other_user()
        self.new_lz('theirs', headers=other)
        self.assertEqual([c['name'] for c in self.feed().get_json()['changes']], ['mine'])
        self.assertEqual([c['name'] for c in self.feed(headers=other).get_json()['changes']], ['theirs'])

    def test_bad_parameters_are_refused(self):
        for query in ('since=abc', 'since=-1', 'limit=0', 'limit=x', 'since=1.5'):
            with self.subTest(query=query):
                response = self.client.get(f'/api/sync/changes?{query}', headers=self.head)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'invalid_cursor')

    def test_the_page_size_is_capped(self):
        # Shrink the cap so the test can hit it: a request for far more gets only the cap.
        for i in range(3):
            self.new_lz(f'lz{i}')
        with patch.object(sync_support, 'MAX_PAGE', 2):
            body = self.client.get('/api/sync/changes?limit=100000', headers=self.head).get_json()
        self.assertEqual(len(body['changes']), 2)
        self.assertTrue(body['has_more'])

    def test_it_is_never_cached(self):
        self.assertEqual(self.feed().headers['Cache-Control'], 'private, no-store')

    def test_records_that_predate_sync_are_given_an_identity_and_a_place(self):
        # Rows written before this existed, or by an older server instance mid-deploy.
        with self.app.app_context():
            uid = User.query.one().id
            old = datetime.utcnow() - timedelta(days=5)
            db.session.add_all([
                SavedLZ(user_id=uid, name='newer', lz_data={}, updated_at=old + timedelta(days=1), revision=1),
                SavedLZ(user_id=uid, name='older', lz_data={}, updated_at=old, revision=1),
            ])
            db.session.commit()
        first = self.feed().get_json()
        self.assertEqual([c['name'] for c in first['changes']], ['older', 'newer'])     # in the order they were last edited
        self.assertTrue(all(c['client_uuid'] for c in first['changes']))
        again = self.feed().get_json()
        self.assertEqual([c['client_uuid'] for c in again['changes']], [c['client_uuid'] for c in first['changes']])

    def test_late_old_server_writes_are_picked_up_after_a_cursor(self):
        self.new_lz('current')
        cursor = self.feed().get_json()['cursor']
        with self.app.app_context():
            db.session.add(SavedLZ(user_id=User.query.one().id, name='from the old server', lz_data={}, revision=1))
            db.session.commit()
        later = self.feed(since=cursor).get_json()['changes']
        self.assertEqual([c['name'] for c in later], ['from the old server'])


class DeletingAnAccountCleansUpTests(SavedRecordCase):
    def test_the_counter_and_tombstones_go_with_the_account(self):
        lz = self.new_lz()
        self.client.delete(f'/api/lz/{lz["id"]}', headers=self.head)
        with self.app.app_context():
            self.assertEqual(SyncCounter.query.count(), 1)
        from auth_harness import PASSWORD
        done = self.client.delete('/api/auth/me', headers=self.head, json={'confirm': 'DELETE', 'password': PASSWORD})
        self.assertEqual(done.status_code, 200)
        with self.app.app_context():
            self.assertEqual(SyncCounter.query.count(), 0)
            self.assertEqual(SavedLZ.query.count(), 0)


class SequenceTests(SavedRecordCase):
    def test_numbers_are_taken_in_order_and_never_repeat(self):
        with self.app.app_context():
            uid = User.query.one().id
            taken = [sync_support.next_seq(uid) for _ in range(5)]
            db.session.commit()
        self.assertEqual(taken, [1, 2, 3, 4, 5])

    def test_each_user_has_their_own_counter(self):
        other = self.other_user()
        self.new_lz(); self.new_lz(); self.new_lz(headers=other)                      # noqa: E702
        mine = [c['seq'] for c in self.feed().get_json()['changes']]
        theirs = [c['seq'] for c in self.feed(headers=other).get_json()['changes']]
        self.assertEqual((mine, theirs), ([1, 2], [1]))


class ContractTests(SavedRecordCase):
    """Real responses, held to contracts/openapi.yaml."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def conforms(self, response, path, method, status):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(check_response(self.spec, path, method, status, response.get_json()), [])

    def test_saving_listing_and_reading_an_lz(self):
        mine = str(uuid.uuid4())
        body = {'name': 'LZ', 'lz_data': {'a': 1}, 'client_uuid': mine}
        self.conforms(self.client.post('/api/lz', headers=self.head, json=body), '/api/lz', 'post', 201)
        self.conforms(self.client.post('/api/lz', headers=self.head, json=body), '/api/lz', 'post', 200)
        self.conforms(self.client.get('/api/lz', headers=self.head), '/api/lz', 'get', 200)
        lz_id = self.client.get('/api/lz', headers=self.head).get_json()[0]['id']
        self.conforms(self.client.get(f'/api/lz/{lz_id}', headers=self.head), '/api/lz/{id}', 'get', 200)

    def test_editing_and_conflicts(self):
        lz = self.new_lz()
        path = f'/api/lz/{lz["id"]}'
        self.conforms(self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'a'}), '/api/lz/{id}', 'put', 200)
        stale = self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'b'})
        self.conforms(stale, '/api/lz/{id}', 'put', 409)
        self.conforms(self.client.put(path, headers={**self.head, 'If-Match': 'x'}, json={}), '/api/lz/{id}', 'put', 400)
        self.conforms(self.client.put('/api/lz/9999', headers=self.head, json={}), '/api/lz/{id}', 'put', 404)

    def test_deleting(self):
        lz = self.new_lz()
        path = f'/api/lz/{lz["id"]}'
        self.conforms(self.client.delete(path, headers=self.head), '/api/lz/{id}', 'delete', 200)
        self.conforms(self.client.get(path, headers=self.head), '/api/lz/{id}', 'get', 404)

    def test_the_feed_with_every_kind_and_a_tombstone(self):
        self.new_lz(); self.new_route(kind='mission', with_file=True); self.new_set()                # noqa: E702
        gone = self.new_lz('gone')
        self.client.delete(f'/api/lz/{gone["id"]}', headers=self.head)
        self.conforms(self.feed(), '/api/sync/changes', 'get', 200)
        self.assertEqual({c['type'] for c in self.feed().get_json()['changes']}, {'lz', 'route', 'pointset'})
        self.assertTrue(any(c['deleted'] for c in self.feed().get_json()['changes']))
        self.conforms(self.client.get('/api/sync/changes?since=zzz', headers=self.head), '/api/sync/changes', 'get', 400)

    def test_every_documented_saved_record_route_exists_with_its_methods(self):
        routes = {}
        for rule in self.app.url_map.iter_rules():
            routes.setdefault(rule.rule.replace('<int:lz_id>', '{id}'), set()).update(m.lower() for m in rule.methods)
        for path, operations in self.spec['paths'].items():
            if path.startswith(('/api/lz', '/api/sync')):
                for method in (m for m in operations if m != 'parameters'):
                    with self.subTest(route=f'{method.upper()} {path}'):
                        self.assertIn(method, routes.get(path, set()))

    def test_the_checker_catches_a_broken_feed(self):
        self.new_lz()
        feed = self.feed().get_json()
        broken = dict(feed, cursor='zero')
        self.assertTrue(check_response(self.spec, '/api/sync/changes', 'get', 200, broken))
        del feed['changes'][0]['revision']
        self.assertTrue(check_response(self.spec, '/api/sync/changes', 'get', 200, feed))


if __name__ == '__main__':
    import unittest
    unittest.main()
