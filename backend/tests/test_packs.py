"""Mission packs and teams: the routes, held to their rules, and to contracts/openapi.yaml."""

import json
import os
import sys
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import pack_support  # noqa: E402
from auth_harness import PASSWORD, NativeAuthCase  # noqa: E402
from entitlements import resolve_features  # noqa: E402
from models import (  # noqa: E402
    MissionPack, MissionPackEvent, MissionPackInvite, MissionPackItem, MissionPackMember, MissionPackSeen,
    SavedLZ, Team, TeamMember, User, db,
)
from openapi_check import check_response, load_spec  # noqa: E402
from routes.admin_routes import admin_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.pack_routes import pack_bp  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402
from routes.saved_routes import saved_routes_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from routes.team_routes import team_bp  # noqa: E402

LZ_DATA = {
    'schemaVersion': 2, 'id': 'd-1', 'name': 'LZ HAWK', 'status': 'analyzed',
    'flightData': {'callSign': 'HAWK 6', 'landingHeading': 270},
    'graphics': {'helicopters': [{'id': 'h-1', 'lat': 34.5, 'lon': -84.1, 'heading': 270}]},
}


class PackCase(NativeAuthCase):
    extra_blueprints = (lz_bp, saved_routes_bp, point_sets_bp, pack_bp, team_bp)

    def setUp(self):
        super().setUp()
        # Postgres enforces foreign keys; make SQLite do it too, so a deleted account
        # that a pack still points at fails here and not in production.
        with self.app.app_context():
            with db.engine.connect() as connection:
                connection.exec_driver_sql('PRAGMA foreign_keys=ON')
        self.mail = []
        for target, kind in (
            ('routes.pack_routes.send_pack_invite_email', 'pack_invite'),
            ('routes.pack_routes.send_pack_added_email', 'pack_added'),
            ('routes.team_routes.send_team_invite_email', 'team_invite'),
        ):
            patcher = patch(target, side_effect=lambda *args, kind=kind: self.mail.append((kind, args)) or True)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.op_number = 0
        self.colin = self.person('colin@example.com', 'Colin')
        self.sam = self.person('sam@example.com', 'Sam')
        self.alex = self.person('alex@example.com', 'Alex')

    # -- people ------------------------------------------------------------

    def person(self, email, name, packs=True):
        """A signed-in person. Mission packs are not launched (entitlements.DEFAULT_OFF), so by default the
        person is given them as an admin gives a tester; ``packs=False`` leaves them as anyone else is."""
        self.assertEqual(self.client.post('/api/auth/register', json={
            'name': name, 'email': email, 'password': PASSWORD}).status_code, 202)
        self.assertEqual(self.client.post('/api/auth/verify-email', json={
            'token': self.tokens[-1], 'password': PASSWORD}).status_code, 200)
        session = self.login(email=email)
        if packs:
            with self.app.app_context():
                db.session.get(User, session['user']['id']).features = {'mission_packs': True}
                db.session.commit()
        return {'id': session['user']['id'], 'email': email, 'name': name,
                'head': {'Authorization': f'Bearer {session["access_token"]}'}}

    # -- requests ----------------------------------------------------------

    def call(self, who, method, path, **kwargs):
        return getattr(self.client, method)(path, headers=who['head'], **kwargs)

    def ok(self, response, status=200):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        return response.get_json()

    def new_pack(self, who=None, name='OP DK', **extra):
        return self.ok(self.call(who or self.colin, 'post', '/api/packs', json={'name': name, **extra}), 201)

    def pack(self, uuid_, who=None):
        return self.ok(self.call(who or self.colin, 'get', f'/api/packs/{uuid_}'))

    def op(self, op_type, item, **fields):
        self.op_number += 1
        return {'type': op_type, 'item': item, 'client_op_id': f'op-{self.op_number}', **fields}

    def send(self, pack_uuid, *ops, who=None, **extra):
        return self.call(who or self.colin, 'post', f'/api/packs/{pack_uuid}/ops', json={'ops': list(ops), **extra})

    def add_lz(self, pack_uuid, item='lz-1', who=None, data=None):
        return self.ok(self.send(pack_uuid, self.op('item.create', item, kind='lz', name='LZ HAWK',
                                                    data=data or LZ_DATA), who=who))

    def item(self, pack_body, item_uuid):
        return next((i for i in pack_body['items'] if i['uuid'] == item_uuid), None)

    def events(self, pack_uuid, who=None, since=0):
        return self.ok(self.call(who or self.colin, 'get', f'/api/packs/{pack_uuid}/events?since={since}'))['events']

    def member(self, pack_uuid, who, role='editor', by=None):
        """Put ``who`` in the pack through an emailed invitation, accepted."""
        self.ok(self.call(by or self.colin, 'post', f'/api/packs/{pack_uuid}/invites',
                          json={'email': who['email'], 'role': role}), 201)
        mine = self.ok(self.call(who, 'get', '/api/invites'))['invites']
        self.ok(self.call(who, 'post', f'/api/invites/{mine[0]["id"]}/accept'))

    def team(self, owner, *members, name='B Co 2-10 AVN'):
        team = self.ok(self.call(owner, 'post', '/api/teams', json={'name': name}), 201)
        for who in members:
            link = self.ok(self.call(owner, 'post', f'/api/teams/{team["id"]}/invites', json={}), 201)
            self.ok(self.call(who, 'post', '/api/invites/accept', json={'token': link['token']}))
        return team

    def db_rows(self, model, **filters):
        with self.app.app_context():
            return model.query.filter_by(**filters).all()


class PackBasicsTests(PackCase):
    def test_a_new_pack_is_its_owners(self):
        body = self.new_pack(description='Air assault rehearsal')
        self.assertEqual((body['name'], body['role'], body['status'], body['head_seq']), ('OP DK', 'owner', 'active', 1))
        self.assertEqual(body['owner'], {'id': self.colin['id'], 'name': 'Colin'})
        self.assertEqual([m['user_id'] for m in body['members']], [self.colin['id']])
        self.assertEqual(body['items'], [])
        listed = self.ok(self.call(self.colin, 'get', '/api/packs'))['packs']
        self.assertEqual([p['uuid'] for p in listed], [body['uuid']])
        self.assertEqual(self.events(body['uuid'])[0]['type'], 'pack.create')

    def test_someone_not_in_a_pack_cannot_tell_it_exists(self):
        body = self.new_pack()
        self.assertEqual(self.ok(self.call(self.sam, 'get', '/api/packs'))['packs'], [])
        for method, path in (('get', ''), ('get', '/events'), ('put', ''), ('delete', ''), ('post', '/finish')):
            response = self.call(self.sam, method, f'/api/packs/{body["uuid"]}{path}', json={})
            self.assertEqual((response.status_code, response.get_json()['code']), (404, 'pack_not_found'), path)
        self.assertEqual(self.send(body['uuid'], self.op('item.delete', 'x'), who=self.sam).status_code, 404)
        self.assertEqual(self.call(self.sam, 'get', '/api/packs/not-a-pack').status_code, 404)

    def test_names_and_descriptions_are_checked(self):
        for name in ('', '   ', 'x' * 101, 'line\nbreak', None, 7):
            response = self.call(self.colin, 'post', '/api/packs', json={'name': name})
            self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_name'), repr(name))
        response = self.call(self.colin, 'post', '/api/packs', json={'name': 'OP', 'description': 'x' * 2001})
        self.assertEqual(response.get_json()['code'], 'invalid_description')
        self.assertEqual(self.new_pack(name='  OP DK  ')['name'], 'OP DK')

    def test_a_pack_made_twice_with_one_uuid_is_one_pack(self):
        mine = str(uuid.uuid4())
        first = self.new_pack(uuid=mine)
        again = self.ok(self.call(self.colin, 'post', '/api/packs', json={'name': 'OP DK', 'uuid': mine}))
        self.assertEqual((first['uuid'], again['uuid']), (mine, mine))
        self.assertEqual(len(self.db_rows(MissionPack)), 1)
        taken = self.call(self.sam, 'post', '/api/packs', json={'name': 'OP X', 'uuid': mine})
        self.assertEqual((taken.status_code, taken.get_json()['code']), (409, 'uuid_taken'))
        self.assertEqual(self.call(self.colin, 'post', '/api/packs', json={'name': 'X', 'uuid': 'no'}).status_code, 400)

    def test_renaming_and_describing_are_logged(self):
        body = self.new_pack()
        self.ok(self.call(self.colin, 'put', f'/api/packs/{body["uuid"]}', json={'name': 'OP EAGLE'}))
        self.ok(self.call(self.colin, 'put', f'/api/packs/{body["uuid"]}', json={'description': 'Night'}))
        got = self.pack(body['uuid'])
        self.assertEqual((got['name'], got['description']), ('OP EAGLE', 'Night'))
        lines = [e['summary'] for e in self.events(body['uuid'])]
        self.assertIn('Colin renamed the pack from "OP DK" to "OP EAGLE".', lines)

    def test_deleting_leaves_a_tombstone_and_nothing_in_it(self):
        body = self.new_pack()
        self.add_lz(body['uuid'])
        self.member(body['uuid'], self.sam)
        self.assertEqual(self.call(self.sam, 'delete', f'/api/packs/{body["uuid"]}').status_code, 403)
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{body["uuid"]}'))
        self.assertEqual(self.call(self.colin, 'get', f'/api/packs/{body["uuid"]}').status_code, 404)
        self.assertEqual(self.ok(self.call(self.sam, 'get', '/api/packs'))['packs'], [])
        for model in (MissionPackItem, MissionPackEvent, MissionPackMember):
            self.assertEqual(self.db_rows(model), [], model.__name__)
        [row] = self.db_rows(MissionPack)
        self.assertEqual((row.name, row.deleted_at is not None), ('', True))

    def test_everything_needs_the_entitlement(self):
        with self.app.app_context():
            user = db.session.get(User, self.sam['id'])
            user.features = {'mission_packs': False}
            db.session.commit()
        for method, path in (('get', '/api/packs'), ('post', '/api/packs'), ('get', '/api/teams'),
                             ('get', '/api/invites'), ('get', '/api/users/search?q=co')):
            response = self.call(self.sam, method, path, json={'name': 'X'})
            self.assertEqual((response.status_code, response.get_json()['code']), (403, 'feature_disabled'), path)

    def test_until_launch_nobody_has_packs_unless_given_them(self):
        dana = self.person('dana@example.com', 'Dana', packs=False)
        self.assertFalse(self.ok(self.call(dana, 'get', '/api/auth/me'))['features']['mission_packs'])
        response = self.call(dana, 'get', '/api/packs')
        self.assertEqual((response.status_code, response.get_json()['code']), (403, 'feature_disabled'))
        # An invitation reaches nobody who cannot open packs yet: the link is refused the same way.
        uuid_ = self.new_pack()['uuid']
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites', json={'email': dana['email']}), 201)
        self.assertEqual(self.call(dana, 'post', '/api/invites/accept', json={'token': 'x'}).status_code, 403)
        # A tester has them.
        self.assertTrue(self.ok(self.call(self.sam, 'get', '/api/auth/me'))['features']['mission_packs'])

    def test_launching_turns_packs_on_for_everyone_with_nothing_stored(self):
        dana = self.person('dana@example.com', 'Dana', packs=False)
        with patch('entitlements.DEFAULT_OFF', frozenset()):
            self.assertTrue(self.ok(self.call(dana, 'get', '/api/auth/me'))['features']['mission_packs'])
            self.ok(self.call(dana, 'get', '/api/packs'))

    def test_everything_needs_a_token(self):
        for method, path in (('get', '/api/packs'), ('post', '/api/packs/x/ops'), ('get', '/api/teams'),
                             ('get', '/api/invites'), ('get', '/api/users/search?q=co')):
            self.assertEqual(getattr(self.client, method)(path).status_code, 401, path)

    def test_responses_are_never_cached(self):
        body = self.new_pack()
        response = self.call(self.colin, 'get', f'/api/packs/{body["uuid"]}')
        self.assertEqual(response.headers['Cache-Control'], 'private, no-store')


class OperationTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']

    def test_an_item_made_by_an_operation_holds_the_document_as_sent(self):
        answer = self.add_lz(self.uuid)
        self.assertEqual(answer['results'], [{'client_op_id': 'op-1', 'seq': 2, 'status': 'applied', 'reason': None}])
        self.assertEqual(answer['head_seq'], 2)
        item = self.item(self.pack(self.uuid), 'lz-1')
        self.assertEqual((item['kind'], item['name'], item['data'], item['revision'], item['seq']),
                         ('lz', 'LZ HAWK', LZ_DATA, 1, 2))
        self.assertEqual(item['created_by'], {'id': self.colin['id'], 'name': 'Colin'})
        self.assertIsNone(item['source'])

    def test_operations_apply_in_order_and_each_is_numbered_and_logged(self):
        self.add_lz(self.uuid)
        answer = self.ok(self.send(
            self.uuid,
            self.op('set', 'lz-1', path=['flightData', 'landingHeading'], value=90, summary='Colin turned LZ HAWK to 090.'),
            self.op('patch', 'lz-1', path=['graphics', 'helicopters', {'id': 'h-1'}], value={'lat': 34.6}),
            self.op('upsert', 'lz-1', path=['graphics', 'helicopters'], value={'id': 'h-2', 'lat': 34.7, 'lon': -84.2}),
        ))
        self.assertEqual([r['seq'] for r in answer['results']], [3, 4, 5])
        self.assertEqual([e['seq'] for e in answer['events']], [3, 4, 5])
        data = self.item(self.pack(self.uuid), 'lz-1')['data']
        self.assertEqual(data['flightData']['landingHeading'], 90)
        self.assertEqual([h['id'] for h in data['graphics']['helicopters']], ['h-1', 'h-2'])
        self.assertEqual(data['graphics']['helicopters'][0]['lat'], 34.6)
        item = self.item(self.pack(self.uuid), 'lz-1')
        self.assertEqual((item['revision'], item['seq']), (4, 5))
        lines = [e['summary'] for e in self.events(self.uuid)]
        self.assertEqual(lines[-3:], ['Colin turned LZ HAWK to 090.', 'Colin edited "LZ HAWK".', 'Colin edited "LZ HAWK".'])

    def test_a_skipped_operation_is_logged_with_its_reason_and_changes_nothing(self):
        self.add_lz(self.uuid)
        before = self.item(self.pack(self.uuid), 'lz-1')
        answer = self.ok(self.send(self.uuid, self.op('patch', 'lz-1', path=['graphics', 'helicopters', {'id': 'h-9'}],
                                                      value={'lat': 1})))
        self.assertEqual(answer['results'][0], {'client_op_id': 'op-2', 'seq': 3, 'status': 'skipped',
                                                'reason': 'target_missing'})
        after = self.item(self.pack(self.uuid), 'lz-1')
        self.assertEqual((after['data'], after['revision'], after['seq']), (before['data'], 1, 2))
        self.assertEqual(self.events(self.uuid)[-1]['status'], 'skipped')

    def test_a_malformed_operation_refuses_the_whole_batch(self):
        self.add_lz(self.uuid)
        response = self.send(self.uuid,
                             self.op('set', 'lz-1', path=['flightData', 'landingHeading'], value=90),
                             self.op('set', 'lz-1', path=['graphics', 'helicopters', 0, 'lat'], value=1))
        body = response.get_json()
        self.assertEqual((response.status_code, body['code'], body['index'], body['reason']),
                         (400, 'invalid_op', 1, 'bad_path'))
        self.assertEqual(self.pack(self.uuid)['head_seq'], 2)
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-1')['data'], LZ_DATA)

    def test_what_a_batch_must_look_like(self):
        cases = [
            ({'ops': []}, 'invalid_batch'), ({}, 'invalid_batch'), ({'ops': 'x'}, 'invalid_batch'),
            ({'ops': [{}] * (pack_support.MAX_BATCH + 1)}, 'invalid_batch'),
            ({'ops': [self.op('item.delete', 'x')], 'base_seq': -1}, 'invalid_batch'),
            ({'ops': [self.op('item.delete', 'x')], 'base_seq': True}, 'invalid_batch'),
            ({'ops': ['x']}, 'invalid_op'),
            ({'ops': [{'type': 'item.delete', 'item': 'x'}]}, 'invalid_op'),                      # no client_op_id
            ({'ops': [{**self.op('item.delete', 'x'), 'client_op_id': 'has space'}]}, 'invalid_op'),
            ({'ops': [self.op('item.delete', 'x'), {**self.op('item.delete', 'y'), 'client_op_id': 'op-dup'},
                      {**self.op('item.delete', 'z'), 'client_op_id': 'op-dup'}]}, 'invalid_op'),
            ({'ops': [{**self.op('item.delete', 'x'), 'summary': 7}]}, 'invalid_op'),
        ]
        for body, code in cases:
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/ops', json=body)
            self.assertEqual((response.status_code, response.get_json()['code']), (400, code), body)
        self.assertEqual(self.pack(self.uuid)['head_seq'], 1)

    def test_only_the_server_replaces_an_item_whole(self):
        self.add_lz(self.uuid)
        response = self.send(self.uuid, self.op('item.replace', 'lz-1', data={}))
        self.assertEqual((response.status_code, response.get_json()['reason']), (400, 'unknown_type'))

    def test_a_batch_sent_again_is_answered_from_the_log_and_not_applied_twice(self):
        self.add_lz(self.uuid)
        batch = [self.op('upsert', 'lz-1', path=['graphics', 'helicopters'], value={'id': 'h-2'}),
                 self.op('item.rename', 'lz-1', name='LZ EAGLE')]
        first = self.ok(self.send(self.uuid, *batch))
        again = self.ok(self.send(self.uuid, *batch))
        self.assertEqual(again['results'], first['results'])
        self.assertEqual(again['head_seq'], first['head_seq'])
        self.assertEqual(again['events'], [])
        self.assertEqual(len(self.events(self.uuid)), 4)
        # Part of a batch seen before: only what is new is applied.
        third = self.op('item.rename', 'lz-1', name='LZ CROW')
        mixed = self.ok(self.send(self.uuid, batch[1], third))
        self.assertEqual([r['seq'] for r in mixed['results']], [4, 5])
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-1')['name'], 'LZ CROW')

    def test_base_seq_brings_back_everything_after_it(self):
        self.add_lz(self.uuid)
        self.ok(self.send(self.uuid, self.op('item.rename', 'lz-1', name='LZ EAGLE')))
        answer = self.ok(self.send(self.uuid, self.op('item.rename', 'lz-1', name='LZ CROW'), base_seq=1))
        self.assertEqual([e['seq'] for e in answer['events']], [2, 3, 4])
        self.assertFalse(answer['has_more'])

    def test_the_log_pages_by_cursor(self):
        self.add_lz(self.uuid)
        for name in ('A', 'B', 'C', 'D'):
            self.ok(self.send(self.uuid, self.op('item.rename', 'lz-1', name=name)))
        page = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/events?since=1&limit=2'))
        self.assertEqual(([e['seq'] for e in page['events']], page['cursor'], page['has_more'], page['head_seq']),
                         ([2, 3], 3, True, 6))
        rest = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/events?since=3&limit=50'))
        self.assertEqual(([e['seq'] for e in rest['events']], rest['cursor'], rest['has_more']), ([4, 5, 6], 6, False))
        empty = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/events?since=6'))
        self.assertEqual((empty['events'], empty['cursor']), ([], 6))
        for query in ('since=x', 'since=-1', 'limit=0'):
            self.assertEqual(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/events?{query}').status_code, 400)

    def test_an_event_carries_who_what_and_the_operation(self):
        self.add_lz(self.uuid)
        event = self.events(self.uuid)[-1]
        self.assertEqual((event['type'], event['item'], event['actor'], event['status'], event['client_op_id']),
                         ('item.create', 'lz-1', {'id': self.colin['id'], 'name': 'Colin'}, 'applied', 'op-1'))
        self.assertEqual(event['op'], {'type': 'item.create', 'item': 'lz-1', 'kind': 'lz', 'name': 'LZ HAWK', 'data': LZ_DATA})
        self.assertEqual(event['summary'], 'Colin added the LZ "LZ HAWK".')

    def test_a_summary_is_trimmed_and_cut_to_300_characters(self):
        self.add_lz(self.uuid)
        self.ok(self.send(self.uuid, self.op('item.rename', 'lz-1', name='X', summary='  ' + 'y' * 400)))
        self.assertEqual(self.events(self.uuid)[-1]['summary'], 'y' * 300)

    def test_an_item_too_large_is_refused_and_nothing_is_kept(self):
        self.add_lz(self.uuid)
        with patch.object(pack_support, 'MAX_ITEM_BYTES', 2000):
            response = self.send(self.uuid, self.op('item.rename', 'lz-1', name='OK'),
                                 self.op('set', 'lz-1', path=['notes'], value='x' * 3000))
        self.assertEqual((response.status_code, response.get_json()['code'], response.get_json()['item']),
                         (413, 'item_too_large', 'lz-1'))
        got = self.pack(self.uuid)
        self.assertEqual((got['head_seq'], self.item(got, 'lz-1')['name']), (2, 'LZ HAWK'))

    def test_a_deleted_item_is_gone_and_its_uuid_is_never_used_again(self):
        self.add_lz(self.uuid)
        self.ok(self.send(self.uuid, self.op('item.delete', 'lz-1')))
        self.assertIsNone(self.item(self.pack(self.uuid), 'lz-1'))
        self.assertEqual(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/items/lz-1').status_code, 404)
        [row] = self.db_rows(MissionPackItem)
        self.assertEqual((row.name, row.data, row.deleted_at is not None), ('', None, True))
        again = self.ok(self.send(self.uuid, self.op('item.create', 'lz-1', kind='lz', name='BACK', data={})))
        self.assertEqual(again['results'][0]['reason'], 'item_exists')

    def test_a_point_set_and_a_route_set_are_items_too(self):
        self.ok(self.send(
            self.uuid,
            self.op('item.create', 'ps-1', kind='pointset', name='LOCAL', data=[{'id': 'lps-0', 'name': 'A'}]),
            self.op('item.create', 'rt-1', kind='route', name='MISSION 1',
                    data={'version': 1, 'routes': [{'id': 'r-1', 'points': [{'id': 'p-1'}, {'id': 'p-3'}]}]}),
            self.op('insert', 'rt-1', path=['routes', {'id': 'r-1'}, 'points'], after='p-1', value={'id': 'p-2'}),
            self.op('remove', 'ps-1', path=[{'id': 'lps-0'}]),
        ))
        got = self.pack(self.uuid)
        self.assertEqual(self.item(got, 'ps-1')['data'], [])
        self.assertEqual([p['id'] for p in self.item(got, 'rt-1')['data']['routes'][0]['points']], ['p-1', 'p-2', 'p-3'])

    def test_one_item_can_be_read_alone(self):
        self.add_lz(self.uuid)
        body = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/items/lz-1'))
        self.assertEqual((body['item']['data'], body['head_seq']), (LZ_DATA, 2))


class TwoEditorsTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']
        self.member(self.uuid, self.sam)
        self.add_lz(self.uuid)

    def test_edits_to_different_fields_both_stand(self):
        self.ok(self.send(self.uuid, self.op('set', 'lz-1', path=['flightData', 'landingHeading'], value=90)))
        self.ok(self.send(self.uuid, self.op('set', 'lz-1', path=['flightData', 'callSign'], value='HAWK 7'), who=self.sam))
        flight = self.item(self.pack(self.uuid), 'lz-1')['data']['flightData']
        self.assertEqual(flight, {'callSign': 'HAWK 7', 'landingHeading': 90})

    def test_edits_to_one_field_leave_the_later_one(self):
        self.ok(self.send(self.uuid, self.op('set', 'lz-1', path=['flightData', 'landingHeading'], value=90), base_seq=4))
        late = self.ok(self.send(self.uuid, self.op('set', 'lz-1', path=['flightData', 'landingHeading'], value=180),
                                 who=self.sam, base_seq=4))
        # Sam's answer shows Colin's edit came first, so Sam's client knows to rebase.
        self.assertEqual([e['actor']['name'] for e in late['events']], ['Colin', 'Sam'])
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-1')['data']['flightData']['landingHeading'], 180)
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-1')['updated_by'], {'id': self.sam['id'], 'name': 'Sam'})

    def test_an_edit_to_something_already_deleted_is_skipped(self):
        self.ok(self.send(self.uuid, self.op('remove', 'lz-1', path=['graphics', 'helicopters', {'id': 'h-1'}])))
        late = self.ok(self.send(self.uuid, self.op('patch', 'lz-1', path=['graphics', 'helicopters', {'id': 'h-1'}],
                                                    value={'lat': 1}), who=self.sam))
        self.assertEqual(late['results'][0]['reason'], 'target_missing')

    def test_a_viewer_reads_but_cannot_edit(self):
        self.member(self.uuid, self.alex, role='viewer')
        self.assertEqual(self.pack(self.uuid, who=self.alex)['role'], 'viewer')
        response = self.send(self.uuid, self.op('item.rename', 'lz-1', name='X'), who=self.alex)
        self.assertEqual((response.status_code, response.get_json()['code']), (403, 'pack_read_only'))
        self.assertEqual(self.call(self.alex, 'put', f'/api/packs/{self.uuid}', json={'name': 'X'}).status_code, 403)


class LibraryTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']
        self.lz = self.ok(self.call(self.colin, 'post', '/api/lz', json={'name': 'LZ HAWK', 'lz_data': LZ_DATA}), 201)

    def copy_in(self, who=None, **body):
        return self.call(who or self.colin, 'post', f'/api/packs/{self.uuid}/items',
                         json={'source': {'kind': 'lz', 'id': self.lz['id']}, **body})

    def test_a_library_lz_is_copied_in_and_remembers_where_it_came_from(self):
        body = self.ok(self.copy_in(item='lz-a'), 201)
        item = body['item']
        self.assertEqual((item['uuid'], item['name'], item['data']), ('lz-a', 'LZ HAWK', LZ_DATA))
        self.assertEqual(item['source'], {'kind': 'lz', 'uuid': self.lz['client_uuid'], 'revision': 1, 'original': 'same',
                                          'original_updated_at': self.lz['updated_at'], 'pack_changes': None,
                                          'last_pack_change': None})
        self.assertEqual(body['event']['summary'], 'Colin added "LZ HAWK" (copied from their library).')
        self.assertEqual(body['event']['op']['source'], {'kind': 'lz', 'uuid': self.lz['client_uuid'], 'revision': 1})

    def test_the_copy_is_the_packs_and_the_original_is_untouched(self):
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.send(self.uuid, self.op('set', 'lz-a', path=['flightData', 'landingHeading'], value=90)))
        original = self.ok(self.call(self.colin, 'get', f'/api/lz/{self.lz["id"]}'))
        self.assertEqual((original['lz_data'], original['revision']), (LZ_DATA, 1))
        self.assertEqual(len(self.ok(self.call(self.colin, 'get', '/api/lz'))), 1)

    def test_only_whoever_copied_it_learns_the_original_changed(self):
        self.member(self.uuid, self.sam)
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.call(self.colin, 'put', f'/api/lz/{self.lz["id"]}', json={'lz_data': {**LZ_DATA, 'name': 'NEW'}}))
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-a')['source']['original'], 'changed')
        self.assertIsNone(self.item(self.pack(self.uuid, who=self.sam), 'lz-a')['source']['original'])

    def test_update_from_original_replaces_the_content_and_is_logged(self):
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.call(self.colin, 'put', f'/api/lz/{self.lz["id"]}', json={'lz_data': {**LZ_DATA, 'name': 'NEW'}}))
        body = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-a/update-from-original'))
        self.assertEqual(body['item']['data']['name'], 'NEW')
        self.assertEqual(body['item']['source']['original'], 'same')
        self.assertEqual((body['event']['type'], body['event']['op']['data']['name'], body['event']['op']['source_revision']),
                         ('item.replace', 'NEW', 2))

    def heading(self, item, value, summary):
        return self.op('patch', item, path=['flightData'], value={'landingHeading': value}, summary=summary)

    def test_whoever_copied_it_learns_what_updating_would_replace(self):
        self.member(self.uuid, self.sam)
        self.ok(self.copy_in(item='lz-a'), 201)
        # Sam's one edit goes as two operations with one sentence, then another edit; Colin renames
        # the copy (updating keeps the name) and an edit to something gone is skipped: neither counts.
        self.ok(self.send(self.uuid, self.heading('lz-a', 90, 'Sam set the landing heading of LZ HAWK to 090°.'),
                          self.heading('lz-a', 95, 'Sam set the landing heading of LZ HAWK to 090°.'), who=self.sam))
        self.ok(self.send(self.uuid, self.op('item.rename', 'lz-a', name='LZ HAWK 2')))
        self.ok(self.send(self.uuid, self.op('patch', 'lz-a', path=['graphics', 'helicopters', {'id': 'gone'}],
                                             value={'heading': 1})))
        self.ok(self.send(self.uuid, self.heading('lz-a', 270, 'Sam set the landing heading of LZ HAWK to 270°.'),
                          who=self.sam))
        source = self.item(self.pack(self.uuid), 'lz-a')['source']
        self.assertEqual((source['pack_changes'], source['last_pack_change']), (None, None))   # nothing to update to

        edited = self.ok(self.call(self.colin, 'put', f'/api/lz/{self.lz["id"]}', json={'lz_data': {**LZ_DATA, 'name': 'NEW'}}))
        source = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/items/lz-a'))['item']['source']
        self.assertEqual((source['original'], source['original_updated_at'], source['pack_changes']),
                         ('changed', edited['updated_at'], 2))
        self.assertEqual(source['last_pack_change']['actor'], {'id': self.sam['id'], 'name': 'Sam'})
        self.assertEqual(source['last_pack_change']['summary'], 'Sam set the landing heading of LZ HAWK to 270°.')
        self.assertTrue(source['last_pack_change']['created_at'])
        theirs = self.ok(self.call(self.sam, 'get', f'/api/packs/{self.uuid}/items/lz-a'))['item']['source']
        self.assertEqual([theirs[k] for k in ('original', 'original_updated_at', 'pack_changes', 'last_pack_change')],
                         [None, None, None, None])

        # Updating starts the count again: only what is done to the copy after it counts.
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-a/update-from-original'))
        self.ok(self.send(self.uuid, self.heading('lz-a', 180, 'Colin set the landing heading of NEW to 180°.')))
        self.ok(self.call(self.colin, 'put', f'/api/lz/{self.lz["id"]}', json={'lz_data': {**LZ_DATA, 'name': 'NEWER'}}))
        source = self.item(self.pack(self.uuid), 'lz-a')['source']
        self.assertEqual((source['pack_changes'], source['last_pack_change']['actor']['name']), (1, 'Colin'))

    def test_an_original_changed_with_nothing_changed_in_the_pack_replaces_nothing(self):
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.call(self.colin, 'put', f'/api/lz/{self.lz["id"]}', json={'lz_data': {**LZ_DATA, 'name': 'NEW'}}))
        source = self.item(self.pack(self.uuid), 'lz-a')['source']
        self.assertEqual((source['original'], source['pack_changes'], source['last_pack_change']), ('changed', 0, None))

    def test_nobody_else_can_reach_into_someone_elses_library(self):
        self.member(self.uuid, self.sam)
        self.ok(self.copy_in(item='lz-a'), 201)
        response = self.call(self.sam, 'post', f'/api/packs/{self.uuid}/items/lz-a/update-from-original')
        self.assertEqual((response.status_code, response.get_json()['code']), (403, 'not_your_original'))
        response = self.copy_in(who=self.sam)
        self.assertEqual((response.status_code, response.get_json()['code']), (404, 'source_not_found'))

    def test_an_original_deleted_since_cannot_be_updated_from(self):
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.call(self.colin, 'delete', f'/api/lz/{self.lz["id"]}'))
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-a')['source']['original'], 'deleted')
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-a/update-from-original')
        self.assertEqual((response.status_code, response.get_json()['code']), (409, 'original_gone'))
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-a')['data'], LZ_DATA)

    def test_copying_the_same_thing_twice_under_one_id_is_one_copy(self):
        self.ok(self.copy_in(item='lz-a'), 201)
        self.ok(self.copy_in(item='lz-a'), 200)
        self.assertEqual(len(self.pack(self.uuid)['items']), 1)
        self.ok(self.send(self.uuid, self.op('item.create', 'lz-b', kind='lz', name='X', data={})))
        self.assertEqual(self.copy_in(item='lz-b').get_json()['code'], 'item_exists')

    def test_sketched_routes_and_point_sets_come_in_but_an_amps_mission_does_not(self):
        import io
        import json
        sketch = self.ok(self.call(self.colin, 'post', '/api/routes', content_type='multipart/form-data', data={
            'name': 'MISSION 1', 'kind': 'sketch', 'route_data': json.dumps({'version': 1, 'routes': []})}), 201)
        mission = self.ok(self.call(self.colin, 'post', '/api/routes', content_type='multipart/form-data', data={
            'name': 'AMPS', 'kind': 'mission', 'route_data': json.dumps({}),
            'msnx': (io.BytesIO(b'PK\x03\x04'), 'm.msnx')}), 201)
        points = self.ok(self.call(self.colin, 'post', '/api/pointsets', json={'name': 'LOCAL', 'points': [{'name': 'A'}]}), 201)
        route = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items', json={'source': {'kind': 'route', 'id': sketch['id']}})
        self.assertEqual(self.ok(route, 201)['item']['data'], {'version': 1, 'routes': []})
        refused = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items', json={'source': {'kind': 'route', 'id': mission['id']}})
        self.assertEqual((refused.status_code, refused.get_json()['code']), (400, 'mission_not_supported'))
        by_uuid = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                            json={'source': {'kind': 'pointset', 'client_uuid': points['client_uuid']}})
        self.assertEqual(self.ok(by_uuid, 201)['item']['data'], [{'name': 'A'}])
        for source in ({'kind': 'threat', 'id': 1}, {'kind': 'lz'}, 'x'):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items', json={'source': source})
            self.assertIn(response.status_code, (400, 404), source)

    def test_a_copy_can_be_named_on_the_way_in(self):
        self.assertEqual(self.ok(self.copy_in(name='LZ ONE'), 201)['item']['name'], 'LZ ONE')
        self.assertEqual(self.copy_in(name='').get_json()['code'], 'invalid_name')

    def test_any_member_saves_a_copy_to_their_own_library(self):
        self.member(self.uuid, self.sam, role='viewer')
        self.ok(self.copy_in(item='lz-a'), 201)
        saved = self.ok(self.call(self.sam, 'post', f'/api/packs/{self.uuid}/items/lz-a/library',
                                  json={'name': 'MY HAWK'}), 201)
        self.assertEqual((saved['kind'], saved['name'], saved['revision']), ('lz', 'MY HAWK', 1))
        mine = self.ok(self.call(self.sam, 'get', f'/api/lz/{saved["id"]}'))
        self.assertEqual(mine['lz_data'], LZ_DATA)
        self.assertEqual(len(self.ok(self.call(self.colin, 'get', '/api/lz'))), 1)    # Colin's library is unchanged

    def test_an_empty_point_set_cannot_be_saved_to_the_library(self):
        self.ok(self.send(self.uuid, self.op('item.create', 'ps-1', kind='pointset', name='EMPTY', data=[])))
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/ps-1/library')
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'empty_point_set'))


class LegacyLibraryTests(PackCase):
    """Records saved before sync have no client_uuid until something names them, and a copy finds its
    original by that name: without one it would find whichever unnamed record came first."""

    extra_blueprints = PackCase.extra_blueprints + (sync_bp,)

    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']
        self.long_ago = datetime(2025, 1, 2, 3, 4, 5)
        with self.app.app_context():
            # BRAVO was last edited before ALPHA, so the feed gives it the earlier place.
            rows = [SavedLZ(user_id=self.colin['id'], name=name, lz_data={**LZ_DATA, 'name': name}, revision=1,
                            created_at=self.long_ago, updated_at=self.long_ago + timedelta(days=days))
                    for name, days in (('ALPHA', 1), ('BRAVO', 0))]
            db.session.add_all(rows)
            db.session.commit()
            self.alpha, self.bravo = (row.id for row in rows)

    def copy(self, lz_id, item, status=201):
        return self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                                 json={'item': item, 'source': {'kind': 'lz', 'id': lz_id}}), status)['item']

    def lz(self, lz_id):
        with self.app.app_context():
            return db.session.get(SavedLZ, lz_id)

    def edit(self, lz_id, name):
        self.ok(self.call(self.colin, 'put', f'/api/lz/{lz_id}', json={'lz_data': {**LZ_DATA, 'name': name}}))

    def test_copying_one_names_it_and_the_copy_follows_that_one_only(self):
        item = self.copy(self.bravo, 'lz-b')
        bravo, alpha = self.lz(self.bravo), self.lz(self.alpha)
        self.assertEqual(item['source'], {'kind': 'lz', 'uuid': bravo.client_uuid, 'revision': 1, 'original': 'same',
                                          'original_updated_at': self.long_ago.isoformat(), 'pack_changes': None,
                                          'last_pack_change': None})
        self.assertIsNotNone(bravo.client_uuid)
        self.assertIsNone(alpha.client_uuid)                                     # only what was copied
        self.assertEqual((bravo.revision, bravo.updated_at), (1, self.long_ago))  # being named is not an edit
        self.assertEqual(self.copy(self.bravo, 'lz-b', status=200)['uuid'], 'lz-b')    # asked again: the same copy

        self.edit(self.alpha, 'ALPHA EDITED')
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-b')['source']['original'], 'same')
        self.edit(self.bravo, 'BRAVO EDITED')
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-b')['source']['original'], 'changed')
        body = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-b/update-from-original'))
        self.assertEqual(body['item']['data']['name'], 'BRAVO EDITED')
        self.assertEqual(self.copy(self.alpha, 'lz-a')['source']['uuid'], self.lz(self.alpha).client_uuid)

    def test_a_device_knows_it_by_the_same_name_and_in_the_same_place(self):
        item = self.copy(self.bravo, 'lz-b')
        changes = self.ok(self.call(self.colin, 'get', '/api/sync/changes'))['changes']
        self.assertEqual([c['name'] for c in changes], ['BRAVO', 'ALPHA'])
        self.assertEqual(changes[0]['client_uuid'], item['source']['uuid'])
        self.assertTrue(changes[1]['client_uuid'])

    def test_a_copy_that_cannot_say_where_it_came_from_has_no_original(self):
        self.copy(self.bravo, 'lz-b')
        with self.app.app_context():
            # As a copy of an unnamed record was stored before copying named it.
            MissionPackItem.query.filter_by(uuid='lz-b').one().source_uuid = None
            db.session.commit()
        self.edit(self.alpha, 'ALPHA EDITED')
        pack = self.pack(self.uuid)
        self.assertEqual(self.item(pack, 'lz-b')['source'], {'kind': 'lz', 'uuid': None, 'revision': 1, 'original': None,
                                                             'original_updated_at': None, 'pack_changes': None,
                                                             'last_pack_change': None})
        self.assertEqual(check_response(load_spec(), '/api/packs/{uuid}', 'get', 200, pack), [])
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-b/update-from-original')
        self.assertEqual((response.status_code, response.get_json()['code']), (409, 'original_unknown'))
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-b')['data']['name'], 'BRAVO')


class FinishTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']
        self.member(self.uuid, self.sam)
        self.add_lz(self.uuid)
        self.lz = self.ok(self.call(self.colin, 'post', '/api/lz', json={'name': 'LZ CROW', 'lz_data': {}}), 201)

    def finish(self, who=None):
        return self.call(who or self.colin, 'post', f'/api/packs/{self.uuid}/finish')

    def test_only_the_owner_finishes(self):
        response = self.finish(self.sam)
        self.assertEqual((response.status_code, response.get_json()['code']), (403, 'owner_only'))
        body = self.ok(self.finish())
        self.assertEqual((body['status'], body['finished_by']['name']), ('finished', 'Colin'))
        self.assertEqual(self.events(self.uuid)[-1]['type'], 'pack.finish')

    def test_a_finished_pack_refuses_every_edit_the_owners_included(self):
        self.ok(self.finish())
        attempts = [
            self.send(self.uuid, self.op('item.rename', 'lz-1', name='X')),
            self.send(self.uuid, self.op('item.rename', 'lz-1', name='X'), who=self.sam),
            self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items', json={'source': {'kind': 'lz', 'id': self.lz['id']}}),
            self.call(self.colin, 'put', f'/api/packs/{self.uuid}', json={'name': 'X'}),
        ]
        for response in attempts:
            body = response.get_json()
            self.assertEqual((response.status_code, body['code'], body['finished_by']['name']), (423, 'pack_finished', 'Colin'))
            self.assertIn('read-only for everyone', body['error'])
        self.assertEqual(self.item(self.pack(self.uuid), 'lz-1')['name'], 'LZ HAWK')

    def test_reading_copying_out_and_membership_still_work(self):
        self.ok(self.finish())
        self.ok(self.call(self.sam, 'post', f'/api/packs/{self.uuid}/items/lz-1/library'), 201)
        copy = self.ok(self.call(self.sam, 'post', f'/api/packs/{self.uuid}/duplicate'), 201)
        self.assertEqual((copy['name'], copy['status'], copy['role']), ('OP DK (copy)', 'active', 'owner'))
        self.ok(self.call(self.colin, 'put', f'/api/packs/{self.uuid}/members/{self.sam["id"]}', json={'role': 'viewer'}))

    def test_reopening_lets_edits_through_again(self):
        self.ok(self.finish())
        self.assertEqual(self.call(self.sam, 'post', f'/api/packs/{self.uuid}/reopen').status_code, 403)
        body = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/reopen'))
        self.assertEqual((body['status'], body['finished_at']), ('active', None))
        self.ok(self.send(self.uuid, self.op('item.rename', 'lz-1', name='X'), who=self.sam))
        self.assertEqual([e['type'] for e in self.events(self.uuid)][-3:], ['pack.finish', 'pack.reopen', 'item.rename'])

    def test_finishing_twice_logs_once(self):
        self.ok(self.finish())
        self.ok(self.finish())
        self.assertEqual([e['type'] for e in self.events(self.uuid)].count('pack.finish'), 1)


class DuplicateTests(PackCase):
    def test_a_duplicate_is_a_new_pack_with_copies_and_no_link_to_anyones_library(self):
        source = self.new_pack()
        lz = self.ok(self.call(self.colin, 'post', '/api/lz', json={'name': 'LZ HAWK', 'lz_data': LZ_DATA}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{source["uuid"]}/items',
                          json={'item': 'lz-a', 'source': {'kind': 'lz', 'id': lz['id']}}), 201)
        self.add_lz(source['uuid'], item='lz-b')
        self.ok(self.send(source['uuid'], self.op('item.delete', 'lz-b')))
        self.member(source['uuid'], self.sam)
        copy = self.ok(self.call(self.sam, 'post', f'/api/packs/{source["uuid"]}/duplicate', json={'name': 'OP DK 2'}), 201)
        self.assertEqual((copy['name'], copy['owner']['name'], [i['uuid'] for i in copy['items']]),
                         ('OP DK 2', 'Sam', ['lz-a']))
        self.assertIsNone(copy['items'][0]['source'])
        self.assertEqual([e['type'] for e in self.events(copy['uuid'], who=self.sam)], ['pack.create', 'item.create'])
        self.assertEqual(len(self.pack(source['uuid'])['items']), 1)


class MemberTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']

    def test_a_teammate_is_added_by_name_and_told(self):
        self.team(self.colin, self.sam)
        body = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/members',
                                 json={'user_id': self.sam['id'], 'role': 'viewer'}), 201)
        self.assertEqual((body['member']['name'], body['member']['role']), ('Sam', 'viewer'))
        self.assertEqual(self.pack(self.uuid, who=self.sam)['role'], 'viewer')
        self.assertEqual([m[0] for m in self.mail], ['pack_added'])
        self.assertEqual(self.events(self.uuid)[-1]['summary'], 'Colin added Sam as a viewer.')
        again = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/members', json={'user_id': self.sam['id']})
        self.assertEqual(again.get_json()['code'], 'already_member')

    def test_someone_outside_your_teams_is_invited_by_email_instead(self):
        for user_id in (self.sam['id'], self.colin['id'], 9999, 'x'):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/members', json={'user_id': user_id})
            self.assertEqual((response.status_code, response.get_json()['code']), (400, 'not_a_teammate'), user_id)

    def test_only_the_owner_manages_members(self):
        self.member(self.uuid, self.sam)
        self.team(self.sam, self.alex)
        for method, path, body in (('post', '/members', {'user_id': self.alex['id']}),
                                   ('post', '/invites', {'email': 'x@example.com'}),
                                   ('put', f'/members/{self.colin["id"]}', {'role': 'viewer'})):
            response = self.call(self.sam, method, f'/api/packs/{self.uuid}{path}', json=body)
            self.assertEqual((response.status_code, response.get_json()['code']), (403, 'owner_only'), path)

    def test_roles_change_and_ownership_is_handed_over(self):
        self.member(self.uuid, self.sam)
        self.ok(self.call(self.colin, 'put', f'/api/packs/{self.uuid}/members/{self.sam["id"]}', json={'role': 'viewer'}))
        self.assertEqual(self.pack(self.uuid, who=self.sam)['role'], 'viewer')
        self.ok(self.call(self.colin, 'put', f'/api/packs/{self.uuid}/members/{self.sam["id"]}', json={'role': 'owner'}))
        got = self.pack(self.uuid, who=self.sam)
        self.assertEqual((got['role'], got['owner']['name']), ('owner', 'Sam'))
        self.assertEqual(self.pack(self.uuid)['role'], 'editor')
        self.assertEqual(self.events(self.uuid)[-1]['type'], 'pack.transfer')
        response = self.call(self.colin, 'put', f'/api/packs/{self.uuid}/members/{self.sam["id"]}', json={'role': 'viewer'})
        self.assertEqual(response.status_code, 403)

    def test_the_owner_cannot_step_down_or_leave_without_handing_over(self):
        response = self.call(self.colin, 'put', f'/api/packs/{self.uuid}/members/{self.colin["id"]}', json={'role': 'editor'})
        self.assertEqual(response.get_json()['code'], 'owner_must_transfer')
        response = self.call(self.colin, 'delete', f'/api/packs/{self.uuid}/members/{self.colin["id"]}')
        self.assertEqual(response.get_json()['code'], 'owner_must_transfer')

    def test_a_member_leaves_or_is_removed(self):
        self.member(self.uuid, self.sam)
        self.member(self.uuid, self.alex)
        self.ok(self.call(self.sam, 'delete', f'/api/packs/{self.uuid}/members/{self.sam["id"]}'))
        self.assertEqual(self.call(self.sam, 'get', f'/api/packs/{self.uuid}').status_code, 404)
        self.assertEqual(self.call(self.alex, 'delete', f'/api/packs/{self.uuid}/members/{self.colin["id"]}').status_code, 403)
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}/members/{self.alex["id"]}'))
        self.assertEqual([e['summary'] for e in self.events(self.uuid)][-2:],
                         ['Sam left the pack.', 'Colin removed Alex from the pack.'])


class TeamShareTests(PackCase):
    def setUp(self):
        super().setUp()
        self.squad = self.team(self.colin, self.sam)
        self.uuid = self.new_pack(team_id=self.squad['id'], team_role='viewer')['uuid']

    def test_a_team_sees_a_pack_shared_with_it_with_the_teams_role(self):
        got = self.pack(self.uuid, who=self.sam)
        self.assertEqual((got['role'], got['team']['name'], got['team']['role']), ('viewer', 'B Co 2-10 AVN', 'viewer'))
        self.assertEqual(self.send(self.uuid, self.op('item.delete', 'x'), who=self.sam).status_code, 403)
        # Someone joining the team later sees it too.
        link = self.ok(self.call(self.colin, 'post', f'/api/teams/{self.squad["id"]}/invites', json={}), 201)
        self.ok(self.call(self.alex, 'post', '/api/invites/accept', json={'token': link['token']}))
        self.assertEqual([p['uuid'] for p in self.ok(self.call(self.alex, 'get', '/api/packs'))['packs']], [self.uuid])

    def test_a_members_own_role_wins_over_the_teams(self):
        self.member(self.uuid, self.sam, role='editor')
        self.assertEqual(self.pack(self.uuid, who=self.sam)['role'], 'editor')

    def test_unsharing_takes_it_away_and_is_logged(self):
        self.ok(self.call(self.colin, 'put', f'/api/packs/{self.uuid}', json={'team_id': None}))
        self.assertEqual(self.call(self.sam, 'get', f'/api/packs/{self.uuid}').status_code, 404)
        self.assertEqual(self.events(self.uuid)[-1]['summary'], 'Colin stopped sharing the pack with a team.')

    def test_a_pack_is_shared_only_with_a_team_you_are_in(self):
        other = self.team(self.alex, name='C Co')
        response = self.call(self.colin, 'put', f'/api/packs/{self.uuid}', json={'team_id': other['id']})
        self.assertEqual(response.get_json()['code'], 'not_in_team')
        response = self.call(self.colin, 'post', '/api/packs', json={'name': 'X', 'team_id': other['id']})
        self.assertEqual(response.get_json()['code'], 'not_in_team')
        response = self.call(self.colin, 'put', f'/api/packs/{self.uuid}', json={'team_role': 'owner'})
        self.assertEqual(response.get_json()['code'], 'invalid_role')

    def test_everyone_who_can_open_it_is_counted_once(self):
        # Colin owns it and Sam has it through the team: two, though only Colin is a member.
        got = self.pack(self.uuid)
        self.assertEqual((got['member_count'], got['audience_count']), (1, 2))
        # Sam a member too is still one person; Alex, outside the team, adds one.
        self.member(self.uuid, self.sam, role='editor')
        self.member(self.uuid, self.alex, role='viewer')
        [listed] = self.ok(self.call(self.colin, 'get', '/api/packs'))['packs']
        self.assertEqual((listed['member_count'], listed['audience_count']), (3, 3))
        # Someone joining the team later can open it, so is counted.
        dana = self.person('dana@example.com', 'Dana')
        link = self.ok(self.call(self.colin, 'post', f'/api/teams/{self.squad["id"]}/invites', json={}), 201)
        self.ok(self.call(dana, 'post', '/api/invites/accept', json={'token': link['token']}))
        self.assertEqual(self.pack(self.uuid, who=self.alex)['audience_count'], 4)
        # Unshared, only the members can.
        self.ok(self.call(self.colin, 'put', f'/api/packs/{self.uuid}', json={'team_id': None}))
        self.assertEqual(self.pack(self.uuid)['audience_count'], 3)

    def test_deleting_the_team_unshares_its_packs(self):
        self.ok(self.call(self.colin, 'delete', f'/api/teams/{self.squad["id"]}'))
        self.assertEqual(self.call(self.sam, 'get', f'/api/packs/{self.uuid}').status_code, 404)
        self.assertIsNone(self.pack(self.uuid)['team'])
        self.assertIn('was deleted', self.events(self.uuid)[-1]['summary'])


class InviteTests(PackCase):
    def setUp(self):
        super().setUp()
        self.uuid = self.new_pack()['uuid']

    def invite(self, email, role='editor', who=None):
        return self.call(who or self.colin, 'post', f'/api/packs/{self.uuid}/invites', json={'email': email, 'role': role})

    def test_an_invited_account_finds_it_waiting_and_accepts(self):
        body = self.ok(self.invite('SAM@example.com', 'viewer'), 201)
        self.assertEqual((body['invite']['email'], body['invite']['status'], body['email_sent']),
                         ('sam@example.com', 'pending', True))
        kind, (to, inviter, pack_name, _token, has_account) = self.mail[-1]
        self.assertEqual((kind, to, inviter, pack_name, has_account),
                         ('pack_invite', 'sam@example.com', 'Colin', 'OP DK', True))
        [waiting] = self.ok(self.call(self.sam, 'get', '/api/invites'))['invites']
        self.assertEqual((waiting['pack'], waiting['role'], waiting['invited_by']['name']),
                         ({'uuid': self.uuid, 'name': 'OP DK'}, 'viewer', 'Colin'))
        accepted = self.ok(self.call(self.sam, 'post', f'/api/invites/{waiting["id"]}/accept'))
        self.assertEqual((accepted['pack']['role'], accepted['invite']['status']), ('viewer', 'accepted'))
        self.assertEqual(self.events(self.uuid)[-1]['summary'], 'Sam joined the pack as a viewer.')
        self.assertEqual(self.ok(self.call(self.sam, 'get', '/api/invites'))['invites'], [])

    def test_someone_without_an_account_finds_it_once_they_sign_up(self):
        self.ok(self.invite('newpilot@example.com'), 201)
        self.assertFalse(self.mail[-1][1][4])                                # told to create an account
        newcomer = self.person('NewPilot@example.com', 'Newcomer')
        [waiting] = self.ok(self.call(newcomer, 'get', '/api/invites'))['invites']
        self.ok(self.call(newcomer, 'post', f'/api/invites/{waiting["id"]}/accept'))
        self.assertEqual(self.pack(self.uuid, who=newcomer)['role'], 'editor')

    def test_an_invite_to_a_verified_mil_address_reaches_the_account(self):
        with self.app.app_context():
            user = db.session.get(User, self.sam['id'])
            user.mil_email, user.mil_verified_at = 'sam.pilot.mil@army.mil', pack_support.now()
            db.session.commit()
        self.ok(self.invite('sam.pilot.mil@army.mil'), 201)
        self.assertEqual(len(self.ok(self.call(self.sam, 'get', '/api/invites'))['invites']), 1)

    def test_the_link_works_whatever_address_the_person_signed_in_with(self):
        self.ok(self.invite('sam.work@example.com'), 201)
        token = self.mail[-1][1][3]
        self.assertEqual(self.ok(self.call(self.alex, 'get', '/api/invites'))['invites'], [])
        self.ok(self.call(self.alex, 'post', '/api/invites/accept', json={'token': token}))
        self.assertEqual(self.pack(self.uuid, who=self.alex)['role'], 'editor')
        again = self.call(self.sam, 'post', '/api/invites/accept', json={'token': token})
        self.assertEqual((again.status_code, again.get_json()['code']), (410, 'invite_gone'))
        for token in ('nope', '', None, 'x' * 300):
            self.assertEqual(self.call(self.sam, 'post', '/api/invites/accept', json={'token': token}).status_code, 404)

    def test_an_invite_is_only_answered_by_its_address(self):
        self.ok(self.invite('sam@example.com'), 201)
        invite_id = self.db_rows(MissionPackInvite)[0].id
        self.assertEqual(self.call(self.alex, 'post', f'/api/invites/{invite_id}/accept').status_code, 404)
        self.assertEqual(self.call(self.alex, 'post', f'/api/invites/{invite_id}/decline').status_code, 404)

    def test_declining_revoking_and_expiry(self):
        self.ok(self.invite('sam@example.com'), 201)
        [invite] = self.ok(self.call(self.sam, 'get', '/api/invites'))['invites']
        self.assertEqual(self.ok(self.call(self.sam, 'post', f'/api/invites/{invite["id"]}/decline'))['invite']['status'], 'declined')
        self.assertEqual(self.call(self.sam, 'post', f'/api/invites/{invite["id"]}/accept').get_json()['code'], 'invite_gone')

        self.ok(self.invite('alex@example.com'), 201)
        [pending] = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/invites'))['invites']
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}/invites/{pending["id"]}'))
        self.assertEqual(self.ok(self.call(self.alex, 'get', '/api/invites'))['invites'], [])
        self.assertEqual(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}/invites/{pending["id"]}').status_code, 404)

        self.ok(self.invite('late@example.com'), 201)
        with self.app.app_context():
            row = MissionPackInvite.query.filter_by(email='late@example.com').first()
            row.expires_at = pack_support.now() - timedelta(minutes=1)
            db.session.commit()
        late = self.person('late@example.com', 'Late')
        self.assertEqual(self.ok(self.call(late, 'get', '/api/invites'))['invites'], [])
        listed = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/invites'))['invites']
        self.assertEqual([i['status'] for i in listed], ['expired'])

    def test_inviting_again_refreshes_the_invitation_and_resend_sends_a_new_link(self):
        self.ok(self.invite('sam@example.com'), 201)
        first = self.mail[-1][1][3]
        self.ok(self.invite('sam@example.com', 'viewer'), 200)
        self.assertEqual(len(self.db_rows(MissionPackInvite)), 1)
        self.assertEqual(self.db_rows(MissionPackInvite)[0].role, 'viewer')
        [pending] = self.ok(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/invites'))['invites']
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/invites/{pending["id"]}/resend'))
        latest = self.mail[-1][1][3]
        self.assertNotEqual(first, latest)
        self.assertEqual(self.call(self.sam, 'post', '/api/invites/accept', json={'token': first}).status_code, 404)
        self.ok(self.call(self.sam, 'post', '/api/invites/accept', json={'token': latest}))

    def test_what_an_invitation_must_be(self):
        self.member(self.uuid, self.sam)
        self.assertEqual(self.invite('sam@example.com').get_json()['code'], 'already_member')
        self.assertEqual(self.invite('not an address').get_json()['code'], 'invalid_email')
        self.assertEqual(self.invite('x@example.com', 'owner').get_json()['code'], 'invalid_role')

    def test_invitations_are_rate_limited(self):
        for n in range(30):
            self.ok(self.invite(f'p{n}@example.com'), 201)
        response = self.invite('one-more@example.com')
        self.assertEqual((response.status_code, response.get_json()['code']), (429, 'rate_limited'))
        self.assertIn('Retry-After', response.headers)

    def test_an_invitation_to_a_deleted_pack_is_gone(self):
        self.ok(self.invite('sam@example.com'), 201)
        token = self.mail[-1][1][3]
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}'))
        self.assertEqual(self.ok(self.call(self.sam, 'get', '/api/invites'))['invites'], [])
        self.assertEqual(self.call(self.sam, 'post', '/api/invites/accept', json={'token': token}).status_code, 404)


class TeamTests(PackCase):
    def test_a_team_is_made_listed_and_private_to_its_members(self):
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': ' B Co 2-10 AVN '}), 201)
        self.assertEqual((team['name'], team['role'], team['member_count']), ('B Co 2-10 AVN', 'owner', 1))
        self.assertEqual(self.ok(self.call(self.colin, 'get', '/api/teams'))['teams'][0]['id'], team['id'])
        self.assertEqual(self.call(self.sam, 'get', f'/api/teams/{team["id"]}').status_code, 404)
        self.assertEqual(self.call(self.colin, 'post', '/api/teams', json={'name': ''}).get_json()['code'], 'invalid_name')

    def test_a_link_is_single_use(self):
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': 'B Co'}), 201)
        link = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={}), 201)
        self.assertIsNone(link['invite']['email'])
        joined = self.ok(self.call(self.sam, 'post', '/api/invites/accept', json={'token': link['token']}))
        self.assertEqual(joined['team']['role'], 'member')
        self.assertEqual(self.call(self.alex, 'post', '/api/invites/accept', json={'token': link['token']}).status_code, 410)
        # The token is shown once: a listing never carries it.
        self.assertNotIn('token', str(self.ok(self.call(self.colin, 'get', f'/api/teams/{team["id"]}/invites'))))

    def test_an_emailed_team_invitation(self):
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': 'B Co'}), 201)
        body = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites',
                                 json={'email': 'sam@example.com', 'role': 'admin'}), 201)
        self.assertTrue(body['email_sent'])
        self.assertNotIn('token', body)
        [waiting] = self.ok(self.call(self.sam, 'get', '/api/invites'))['invites']
        self.assertEqual((waiting['team']['name'], waiting['pack']), ('B Co', None))
        self.ok(self.call(self.sam, 'post', f'/api/invites/{waiting["id"]}/accept'))
        self.assertEqual(self.ok(self.call(self.sam, 'get', f'/api/teams/{team["id"]}'))['role'], 'admin')

    def test_admins_invite_and_remove_members_and_only_the_owner_does_more(self):
        team = self.team(self.colin, self.sam, self.alex)
        self.ok(self.call(self.colin, 'put', f'/api/teams/{team["id"]}/members/{self.sam["id"]}', json={'role': 'admin'}))
        response = self.call(self.sam, 'post', f'/api/teams/{team["id"]}/invites', json={'role': 'admin'})
        self.assertEqual(response.get_json()['code'], 'invalid_role')
        self.ok(self.call(self.sam, 'post', f'/api/teams/{team["id"]}/invites', json={}), 201)
        self.assertEqual(self.call(self.sam, 'put', f'/api/teams/{team["id"]}/members/{self.alex["id"]}',
                                   json={'role': 'admin'}).status_code, 403)
        self.assertEqual(self.call(self.alex, 'post', f'/api/teams/{team["id"]}/invites', json={}).status_code, 403)
        self.ok(self.call(self.sam, 'delete', f'/api/teams/{team["id"]}/members/{self.alex["id"]}'))
        self.assertEqual(self.call(self.sam, 'delete', f'/api/teams/{team["id"]}/members/{self.colin["id"]}').status_code, 409)
        self.assertEqual(self.call(self.sam, 'delete', f'/api/teams').status_code, 405)

    def test_the_owner_hands_the_team_over_before_leaving(self):
        team = self.team(self.colin, self.sam)
        response = self.call(self.colin, 'delete', f'/api/teams/{team["id"]}/members/{self.colin["id"]}')
        self.assertEqual(response.get_json()['code'], 'owner_must_transfer')
        self.ok(self.call(self.colin, 'put', f'/api/teams/{team["id"]}/members/{self.sam["id"]}', json={'role': 'owner'}))
        self.assertEqual(self.ok(self.call(self.colin, 'get', f'/api/teams/{team["id"]}'))['role'], 'admin')
        self.ok(self.call(self.colin, 'delete', f'/api/teams/{team["id"]}/members/{self.colin["id"]}'))
        self.assertEqual(self.call(self.colin, 'get', f'/api/teams/{team["id"]}').status_code, 404)

    def test_renaming(self):
        team = self.team(self.colin, self.sam)
        self.assertEqual(self.ok(self.call(self.colin, 'put', f'/api/teams/{team["id"]}', json={'name': 'C Co'}))['name'], 'C Co')
        self.assertEqual(self.call(self.sam, 'put', f'/api/teams/{team["id"]}', json={'name': 'D Co'}).status_code, 403)


class SearchTests(PackCase):
    def setUp(self):
        super().setUp()
        self.team(self.colin, self.sam)

    def search(self, q, who=None):
        return self.call(who or self.colin, 'get', f'/api/users/search?q={q}')

    def test_only_teammates_are_found(self):
        self.assertEqual(self.ok(self.search('sam'))['users'], [{'id': self.sam['id'], 'name': 'Sam', 'email': 'sam@example.com'}])
        self.assertEqual(self.ok(self.search('example.com'))['users'], [{'id': self.sam['id'], 'name': 'Sam', 'email': 'sam@example.com'}])
        self.assertEqual(self.ok(self.search('alex'))['users'], [])                     # not in a team with Colin
        self.assertEqual(self.ok(self.search('colin'))['users'], [])                    # never yourself
        self.assertEqual(self.ok(self.search('example', who=self.alex))['users'], [])   # Alex is in no team

    def test_a_mil_address_they_did_not_sign_in_with_is_never_searched(self):
        with self.app.app_context():
            user = db.session.get(User, self.sam['id'])
            user.mil_email, user.mil_verified_at = 'hidden.name.mil@army.mil', pack_support.now()
            db.session.commit()
        self.assertEqual(self.ok(self.search('hidden'))['users'], [])

    def test_wildcards_are_taken_literally(self):
        self.assertEqual(self.ok(self.search('%25%25'))['users'], [])
        self.assertEqual(self.ok(self.search('s_m'))['users'], [])

    def test_a_query_is_two_characters_or_more(self):
        self.assertEqual(self.search('s').get_json()['code'], 'invalid_query')
        self.assertEqual(self.call(self.colin, 'get', '/api/users/search').status_code, 400)


class AccountDeletionTests(PackCase):
    def delete_account(self, who):
        return self.ok(self.call(who, 'delete', '/api/auth/me', json={'confirm': 'DELETE', 'password': PASSWORD}))

    def test_a_pack_passes_to_the_longest_standing_editor(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.alex, role='viewer')
        self.member(uuid_, self.sam, role='editor')
        self.add_lz(uuid_)
        self.delete_account(self.colin)
        got = self.pack(uuid_, who=self.sam)
        self.assertEqual((got['role'], got['owner']['name']), ('owner', 'Sam'))
        events = self.events(uuid_, who=self.sam)
        self.assertEqual([e['type'] for e in events][-2:], ['pack.transfer', 'member.remove'])
        self.assertIn("its owner's account was deleted", events[-2]['summary'])
        made = next(e for e in events if e['type'] == 'item.create')
        self.assertEqual(made['actor'], {'id': None, 'name': 'Colin'})            # the log still reads
        self.assertIsNone(self.item(got, 'lz-1')['created_by'])

    def test_with_only_viewers_left_the_longest_standing_one_takes_it(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.alex, role='viewer')
        self.member(uuid_, self.sam, role='viewer')
        self.delete_account(self.colin)
        self.assertEqual(self.pack(uuid_, who=self.alex)['role'], 'owner')

    def test_a_pack_shared_only_with_a_team_passes_to_a_teammate(self):
        team = self.team(self.colin, self.sam)
        uuid_ = self.new_pack(team_id=team['id'])['uuid']
        self.delete_account(self.colin)
        got = self.pack(uuid_, who=self.sam)
        self.assertEqual((got['role'], got['team']['name']), ('owner', 'B Co 2-10 AVN'))

    def test_a_pack_nobody_else_is_in_is_deleted_with_the_account(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_)
        gone = self.new_pack(name='OLD')['uuid']
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{gone}'))
        self.delete_account(self.colin)
        for model in (MissionPack, MissionPackItem, MissionPackEvent, MissionPackMember):
            self.assertEqual(self.db_rows(model), [], model.__name__)

    def test_their_memberships_end_and_are_logged(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.delete_account(self.sam)
        self.assertEqual([m['name'] for m in self.pack(uuid_)['members']], ['Colin'])
        self.assertEqual(self.events(uuid_)[-1]['summary'], 'Sam left: their account was deleted.')

    def test_a_team_passes_to_an_admin_first_and_goes_when_empty(self):
        team = self.team(self.colin, self.alex, self.sam)
        self.ok(self.call(self.colin, 'put', f'/api/teams/{team["id"]}/members/{self.sam["id"]}', json={'role': 'admin'}))
        solo = self.team(self.colin, name='SOLO')
        self.delete_account(self.colin)
        self.assertEqual(self.ok(self.call(self.sam, 'get', f'/api/teams/{team["id"]}'))['role'], 'owner')
        with self.app.app_context():
            self.assertIsNone(db.session.get(Team, solo['id']))
            self.assertEqual(TeamMember.query.filter_by(user_id=self.colin['id']).count(), 0)

    def test_invitations_they_sent_still_work(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites', json={'email': 'alex@example.com'}), 201)
        self.delete_account(self.colin)
        [waiting] = self.ok(self.call(self.alex, 'get', '/api/invites'))['invites']
        self.assertIsNone(waiting['invited_by'])
        self.ok(self.call(self.alex, 'post', f'/api/invites/{waiting["id"]}/accept'))
        with self.app.app_context():
            self.assertIsNone(db.session.get(User, self.colin['id']))
            self.assertEqual(SavedLZ.query.count(), 0)


class AnnouncementTests(PackCase):
    """What the API tells the live service, in the transaction that made the change (pack_support)."""

    def setUp(self):
        super().setUp()
        self.sent = []
        self.emit = patch.object(pack_support, '_emit',
                                 side_effect=lambda _session, payloads: self.sent.append([json.loads(p) for p in payloads]))
        self.emit.start()
        self.addCleanup(self.emit.stop)

    def test_each_committed_change_is_announced_once_with_its_event(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_)
        self.ok(self.send(uuid_, self.op('item.rename', 'lz-1', name='A'), self.op('item.rename', 'lz-1', name='B')))
        self.assertEqual([[(a['pack'], a['seq']) for a in commit] for commit in self.sent],
                         [[(uuid_, 1)], [(uuid_, 2)], [(uuid_, 3), (uuid_, 4)]])
        last = self.sent[-1][-1]['event']
        self.assertEqual((last['type'], last['op']['name'], last['actor']['name']), ('item.rename', 'B', 'Colin'))
        self.assertEqual(last, self.events(uuid_)[-1])                 # the same body the log gives

    def test_a_change_that_is_refused_or_rolled_back_is_never_announced(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_)
        before = len(self.sent)
        self.assertEqual(self.send(uuid_, self.op('set', 'lz-1', path=[], value=1)).status_code, 400)
        with patch.object(pack_support, 'MAX_ITEM_BYTES', 100):
            self.assertEqual(self.send(uuid_, self.op('set', 'lz-1', path=['notes'], value='x' * 200)).status_code, 413)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
        self.assertEqual(self.send(uuid_, self.op('item.rename', 'lz-1', name='X')).status_code, 423)
        self.assertEqual([a['event']['type'] for commit in self.sent[before:] for a in commit], ['pack.finish'])

    def test_an_event_too_large_to_carry_is_announced_by_its_number(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_, data={**LZ_DATA, 'notes': 'x' * 9000})
        self.assertEqual(self.sent[-1], [{'pack': uuid_, 'seq': 2}])
        for payload in pack_support.notification_payloads([{'pack': uuid_, 'seq': 3, 'event': {'op': 'y' * 7400}}]):
            self.assertLessEqual(len(payload.encode('utf-8')), pack_support.NOTIFY_LIMIT)

    def test_a_deleted_pack_is_announced(self):
        uuid_ = self.new_pack()['uuid']
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}'))
        self.assertEqual(self.sent[-1], [{'pack': uuid_, 'deleted': True}])

    def test_a_pack_that_goes_with_an_account_is_announced(self):
        uuid_ = self.new_pack()['uuid']
        self.ok(self.call(self.colin, 'delete', '/api/auth/me', json={'confirm': 'DELETE', 'password': PASSWORD}))
        self.assertIn({'pack': uuid_, 'deleted': True}, self.sent[-1])

    def test_nothing_is_sent_to_a_database_that_is_not_postgres(self):
        self.emit.stop()                                  # the real _emit, on this test's SQLite
        with patch.object(pack_support, 'text', side_effect=AssertionError('pg_notify on SQLite')):
            self.new_pack()


class LiveUrlTests(PackCase):
    def test_the_pack_says_where_its_live_stream_is(self):
        uuid_ = self.new_pack()['uuid']
        for value, expected in (('wss://live.example.com', 'wss://live.example.com'), ('ws://127.0.0.1:8091/live', 'ws://127.0.0.1:8091/live'),
                                ('', None), ('https://live.example.com', None), ('wss://has space', None)):
            with self.subTest(value=value), patch.dict(os.environ, {'REALTIME_PUBLIC_URL': value}):
                self.assertEqual(self.pack(uuid_)['live_url'], expected)

    def test_access_is_what_the_live_service_asks(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam, role='viewer')
        self.assertEqual(self.ok(self.call(self.sam, 'get', f'/api/packs/{uuid_}/access')),
                         {'role': 'viewer', 'status': 'active', 'head_seq': 3, 'user': {'id': self.sam['id'], 'name': 'Sam'}})
        self.assertEqual(self.call(self.alex, 'get', f'/api/packs/{uuid_}/access').status_code, 404)
        self.assertEqual(self.client.get(f'/api/packs/{uuid_}/access').status_code, 401)


class AdminTests(PackCase):
    extra_blueprints = PackCase.extra_blueprints + (admin_bp,)

    def setUp(self):
        super().setUp()
        self.app.secret_key = 'test-only-admin-session-key'
        self.app.root_path = str(BACKEND_DIR)        # so the dashboard's templates are found, as in production
        with self.app.app_context():
            admin = User(email='admin@example.com', name='Admin', role='admin')
            db.session.add(admin)
            db.session.commit()
            admin_id = admin.id
        self.admin = self.app.test_client()
        with self.admin.session_transaction() as sess:
            sess['admin_uid'] = admin_id
            sess['csrf'] = 'token'

    def test_the_lists_say_who_is_in_what_and_never_what_a_pack_holds(self):
        team = self.team(self.colin, self.sam)
        uuid_ = self.new_pack(team_id=team['id'])['uuid']
        self.add_lz(uuid_)
        packs_page = self.admin.get('/admin/packs').get_data(as_text=True)
        self.assertIn('OP DK', packs_page)
        self.assertIn('colin@example.com', packs_page)
        self.assertIn('B Co 2-10 AVN (editors)', packs_page)
        self.assertNotIn('HAWK 6', packs_page)                      # the LZ's content
        self.assertIn('OP DK', self.admin.get('/admin/packs?q=dk').get_data(as_text=True))
        self.assertNotIn(uuid_, self.admin.get('/admin/packs?q=zzz').get_data(as_text=True))
        teams_page = self.admin.get('/admin/teams').get_data(as_text=True)
        self.assertIn('B Co 2-10 AVN', teams_page)
        self.assertIn('Colin', teams_page)

    def test_only_an_admin_sees_them(self):
        self.assertEqual(self.client.get('/admin/packs').status_code, 302)
        self.assertEqual(self.client.get('/admin/teams').status_code, 302)

    def stored_features(self, who):
        with self.app.app_context():
            return db.session.get(User, who['id']).features

    def save_features(self, who, *ticked):
        response = self.admin.post(f'/admin/users/{who["id"]}/features', data={'csrf': 'token', 'feature': list(ticked)})
        self.assertEqual(response.status_code, 302)

    def test_saving_someone_s_features_never_stores_an_off_for_packs(self):
        dana = self.person('dana@example.com', 'Dana', packs=False)
        page = self.admin.get(f'/admin/users/{dana["id"]}').get_data(as_text=True)
        self.assertIn('Not launched yet', page)
        # Turning another feature off for them stores every launched feature, and nothing for packs...
        self.save_features(dana, 'lz_pz_tools', 'routes', 'msnx_import', 'cloud_save', 'exports', 'aircraft_profiles')
        stored = self.stored_features(dana)
        self.assertEqual(stored['threats'], False)
        self.assertNotIn('mission_packs', stored)
        self.assertEqual(self.call(dana, 'get', '/api/packs').status_code, 403)
        # ...so when packs launch, they have them like everyone else, and keep threats off.
        with patch('entitlements.DEFAULT_OFF', frozenset()):
            features = self.ok(self.call(dana, 'get', '/api/auth/me'))['features']
            self.assertEqual((features['mission_packs'], features['threats']), (True, False))
        # Ticking packs makes them a tester.
        self.save_features(dana, 'mission_packs', 'threats')
        self.assertEqual(self.stored_features(dana)['mission_packs'], True)
        self.ok(self.call(dana, 'get', '/api/packs'))

    def test_an_admin_always_has_packs(self):
        with self.app.app_context():
            admin = User.query.filter_by(email='admin@example.com').first()
            self.assertTrue(resolve_features(admin)['mission_packs'])
            self.assertFalse(resolve_features(User(email='x@example.com'))['mission_packs'])

    def test_deleting_a_user_from_the_dashboard_hands_their_packs_on(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        response = self.admin.post(f'/admin/users/{self.colin["id"]}/delete',
                                   data={'csrf': 'token', 'confirm_email': 'colin@example.com'})
        self.assertEqual(response.status_code, 302)
        self.assertEqual(self.pack(uuid_, who=self.sam)['owner']['name'], 'Sam')


class SeenTests(PackCase):
    """How far each person has looked, and what a list of packs counts."""

    def seen(self, uuid_, seq, who=None):
        return self.call(who or self.colin, 'put', f'/api/packs/{uuid_}/seen', json={'seq': seq})

    def test_a_marker_moves_forward_only_and_never_past_the_head(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_)                                              # events 1 (create) and 2
        self.assertEqual(self.pack(uuid_)['seen_seq'], 0)
        self.assertEqual(self.ok(self.seen(uuid_, 1))['seen_seq'], 1)
        self.assertEqual(self.ok(self.seen(uuid_, 0))['seen_seq'], 1)   # an older tab cannot undo it
        self.assertEqual(self.ok(self.seen(uuid_, 99))['seen_seq'], 2)  # clamped to head_seq
        self.assertEqual(self.pack(uuid_)['seen_seq'], 2)
        self.assertEqual(self.ok(self.call(self.colin, 'get', '/api/packs'))['packs'][0]['seen_seq'], 2)

    def test_each_person_has_their_own_and_members_show_when_they_last_looked(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.add_lz(uuid_)
        self.ok(self.seen(uuid_, 3, who=self.sam))
        self.assertEqual(self.pack(uuid_, who=self.sam)['seen_seq'], 3)
        self.assertEqual(self.pack(uuid_)['seen_seq'], 0)
        members = {m['user_id']: m for m in self.pack(uuid_)['members']}
        self.assertIsNotNone(members[self.sam['id']]['seen_at'])
        self.assertIsNone(members[self.colin['id']]['seen_at'])

    def test_a_viewer_and_a_finished_pack_still_keep_a_marker_but_a_stranger_does_not(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam, role='viewer')
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
        self.assertGreater(self.ok(self.seen(uuid_, 9, who=self.sam))['seen_seq'], 0)
        self.assertEqual(self.seen(uuid_, 1, who=self.alex).status_code, 404)
        for bad in (-1, '3', True, None, 1.5):
            self.assertEqual(self.call(self.colin, 'put', f'/api/packs/{uuid_}/seen', json={'seq': bad}).status_code, 400, bad)

    def test_markers_are_not_in_the_log_and_go_with_the_pack_or_the_account(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        head = self.pack(uuid_)['head_seq']
        self.ok(self.seen(uuid_, head))
        self.ok(self.seen(uuid_, head, who=self.sam))
        self.assertEqual(self.pack(uuid_)['head_seq'], head)
        self.assertEqual(len(self.db_rows(MissionPackSeen)), 2)
        self.ok(self.call(self.sam, 'delete', '/api/auth/me', json={'confirm': 'DELETE', 'password': PASSWORD}))
        self.assertEqual([r.user_id for r in self.db_rows(MissionPackSeen)], [self.colin['id']])
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}'))
        self.assertEqual(self.db_rows(MissionPackSeen), [])

    def test_a_list_counts_items_by_kind(self):
        uuid_ = self.new_pack()['uuid']
        self.add_lz(uuid_)
        self.ok(self.send(uuid_, self.op('item.create', 'rt-1', kind='route', name='INGRESS', data={'version': 1, 'routes': []}),
                          self.op('item.create', 'ps-1', kind='pointset', name='POINTS', data=[{'id': 'a'}]),
                          self.op('item.create', 'lz-2', kind='lz', name='LZ CROW', data=LZ_DATA),
                          self.op('item.delete', 'lz-2')))
        [summary] = self.ok(self.call(self.colin, 'get', '/api/packs'))['packs']
        self.assertEqual(summary['item_counts'], {'lz': 1, 'route': 1, 'pointset': 1})
        self.assertEqual(summary['item_count'], 3)


class ContractTests(PackCase):
    """Real responses, held to contracts/openapi.yaml."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def conforms(self, response, path, method, status):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(check_response(self.spec, path, method, status, response.get_json()), [], f'{method} {path}')

    def test_packs_items_and_the_log(self):
        self.conforms(self.call(self.colin, 'post', '/api/packs', json={'name': 'OP DK', 'description': 'x'}),
                      '/api/packs', 'post', 201)
        uuid_ = self.ok(self.call(self.colin, 'get', '/api/packs'))['packs'][0]['uuid']
        self.conforms(self.call(self.colin, 'post', '/api/packs', json={'name': ''}), '/api/packs', 'post', 400)
        self.conforms(self.call(self.colin, 'get', '/api/packs'), '/api/packs', 'get', 200)
        lz = self.ok(self.call(self.colin, 'post', '/api/lz', json={'name': 'LZ HAWK', 'lz_data': LZ_DATA}), 201)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/items',
                                json={'item': 'lz-a', 'source': {'kind': 'lz', 'id': lz['id']}}),
                      '/api/packs/{uuid}/items', 'post', 201)
        self.conforms(self.send(uuid_, self.op('item.create', 'ps-1', kind='pointset', name='P', data=[{'id': 'a'}]),
                                self.op('remove', 'ps-1', path=[{'id': 'zz'}]), base_seq=0),
                      '/api/packs/{uuid}/ops', 'post', 200)
        self.conforms(self.send(uuid_, self.op('set', 'ps-1', path=[], value=1)), '/api/packs/{uuid}/ops', 'post', 400)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}'), '/api/packs/{uuid}', 'get', 200)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}/access'), '/api/packs/{uuid}/access', 'get', 200)
        self.conforms(self.call(self.sam, 'get', f'/api/packs/{uuid_}'), '/api/packs/{uuid}', 'get', 404)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}/events?since=0'), '/api/packs/{uuid}/events', 'get', 200)
        self.conforms(self.call(self.colin, 'put', f'/api/packs/{uuid_}/seen', json={'seq': 2}), '/api/packs/{uuid}/seen', 'put', 200)
        self.conforms(self.call(self.colin, 'put', f'/api/packs/{uuid_}/seen', json={'seq': -1}), '/api/packs/{uuid}/seen', 'put', 400)
        self.conforms(self.call(self.sam, 'put', f'/api/packs/{uuid_}/seen', json={'seq': 1}), '/api/packs/{uuid}/seen', 'put', 404)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}'), '/api/packs/{uuid}', 'get', 200)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}/items/lz-a'), '/api/packs/{uuid}/items/{item}', 'get', 200)
        self.ok(self.send(uuid_, self.op('patch', 'lz-a', path=['flightData'], value={'landingHeading': 90})))
        self.ok(self.call(self.colin, 'put', f'/api/lz/{lz["id"]}', json={'name': 'NEW'}))
        changed = self.call(self.colin, 'get', f'/api/packs/{uuid_}/items/lz-a')
        self.conforms(changed, '/api/packs/{uuid}/items/{item}', 'get', 200)
        self.assertEqual(changed.get_json()['item']['source']['pack_changes'], 1)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/items/lz-a/update-from-original'),
                      '/api/packs/{uuid}/items/{item}/update-from-original', 'post', 200)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/items/lz-a/library'),
                      '/api/packs/{uuid}/items/{item}/library', 'post', 201)
        self.conforms(self.call(self.colin, 'put', f'/api/packs/{uuid_}', json={'name': 'OP EAGLE'}),
                      '/api/packs/{uuid}', 'put', 200)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'), '/api/packs/{uuid}/finish', 'post', 200)
        self.conforms(self.send(uuid_, self.op('item.delete', 'lz-a')), '/api/packs/{uuid}/ops', 'post', 423)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/reopen'), '/api/packs/{uuid}/reopen', 'post', 200)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/duplicate'), '/api/packs/{uuid}/duplicate', 'post', 201)
        self.conforms(self.call(self.colin, 'delete', f'/api/packs/{uuid_}'), '/api/packs/{uuid}', 'delete', 200)

    def test_members_invites_teams_and_search(self):
        uuid_ = self.new_pack()['uuid']
        team = self.call(self.colin, 'post', '/api/teams', json={'name': 'B Co'})
        self.conforms(team, '/api/teams', 'post', 201)
        team_id = team.get_json()['id']
        link = self.call(self.colin, 'post', f'/api/teams/{team_id}/invites', json={})
        self.conforms(link, '/api/teams/{id}/invites', 'post', 201)
        self.ok(self.call(self.sam, 'post', '/api/invites/accept', json={'token': link.get_json()['token']}))
        self.conforms(self.call(self.colin, 'get', '/api/teams'), '/api/teams', 'get', 200)
        self.conforms(self.call(self.colin, 'get', f'/api/teams/{team_id}'), '/api/teams/{id}', 'get', 200)
        self.conforms(self.call(self.colin, 'get', '/api/users/search?q=sam'), '/api/users/search', 'get', 200)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/members', json={'user_id': self.sam['id']}),
                      '/api/packs/{uuid}/members', 'post', 201)
        self.conforms(self.call(self.colin, 'put', f'/api/packs/{uuid_}/members/{self.sam["id"]}', json={'role': 'viewer'}),
                      '/api/packs/{uuid}/members/{user_id}', 'put', 200)
        self.conforms(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites', json={'email': 'alex@example.com'}),
                      '/api/packs/{uuid}/invites', 'post', 201)
        self.conforms(self.call(self.colin, 'get', f'/api/packs/{uuid_}/invites'), '/api/packs/{uuid}/invites', 'get', 200)
        self.conforms(self.call(self.alex, 'get', '/api/invites'), '/api/invites', 'get', 200)
        invite_id = self.ok(self.call(self.alex, 'get', '/api/invites'))['invites'][0]['id']
        self.conforms(self.call(self.alex, 'post', f'/api/invites/{invite_id}/accept'), '/api/invites/{invite_id}/accept', 'post', 200)
        self.conforms(self.call(self.colin, 'delete', f'/api/packs/{uuid_}/members/{self.sam["id"]}'),
                      '/api/packs/{uuid}/members/{user_id}', 'delete', 200)

    def test_the_checker_catches_a_broken_pack(self):
        body = self.new_pack()
        self.add_lz(body['uuid'])
        body = self.pack(body['uuid'])
        self.assertEqual(check_response(self.spec, '/api/packs/{uuid}', 'get', 200, body), [])
        self.assertTrue(check_response(self.spec, '/api/packs/{uuid}', 'get', 200, dict(body, surprise=1)))
        self.assertTrue(check_response(self.spec, '/api/packs/{uuid}', 'get', 200, dict(body, role='admin')))
        self.assertTrue(check_response(self.spec, '/api/packs/{uuid}', 'get', 200, dict(body, owner=None)))
        self.assertTrue(check_response(self.spec, '/api/packs/{uuid}', 'get', 200,
                                       dict(body, items=[dict(body['items'][0], kind='threat')])))
        del body['head_seq']
        self.assertTrue(check_response(self.spec, '/api/packs/{uuid}', 'get', 200, body))

    def test_every_documented_pack_route_exists_with_its_methods(self):
        routes = {}
        for rule in self.app.url_map.iter_rules():
            path = (rule.rule.replace('<pack_uuid>', '{uuid}').replace('<item_uuid>', '{item}')
                    .replace('<int:user_id>', '{user_id}').replace('<int:team_id>', '{id}')
                    .replace('<int:invite_id>', '{invite_id}'))
            routes.setdefault(path, set()).update(m.lower() for m in rule.methods)
        documented = [p for p in self.spec['paths'] if p.startswith(('/api/packs', '/api/teams', '/api/invites', '/api/users'))]
        self.assertGreaterEqual(len(documented), 15)
        for path in documented:
            for method in (m for m in self.spec['paths'][path] if m != 'parameters'):
                with self.subTest(route=f'{method.upper()} {path}'):
                    self.assertIn(method, routes.get(path, set()))


if __name__ == '__main__':
    import unittest
    unittest.main()
