"""Custom aircraft profiles, and keeping them in step across devices.

A user's own profiles (``user_id`` set) sync like saved LZs: an identity the
device chooses, a revision, tombstones and a place in the change feed. The master
list (``user_id`` NULL) is the admin's, is read by everyone, and does not sync:
the apps just fetch it.
"""

import sys
import uuid
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import PASSWORD, NativeAuthCase  # noqa: E402
from models import AircraftProfile, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.admin_routes import admin_bp  # noqa: E402
from routes.aircraft_routes import aircraft_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402


class AircraftCase(NativeAuthCase):
    extra_blueprints = (aircraft_bp, lz_bp, sync_bp, admin_bp)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = self.auth(self.login())
        with self.app.app_context():
            db.session.add(AircraftProfile(
                user_id=None, slug='uh-60l', name='UH-60L Black Hawk', designation='UH-60L',
                perf_source='vidx', sort_order=10,
            ))
            db.session.add(AircraftProfile(
                user_id=None, slug='retired', name='Retired', designation='OLD', is_active=False,
            ))
            db.session.commit()

    def auth(self, session):
        return {'Authorization': f'Bearer {session["access_token"]}'}

    def other_user(self):
        self.make_account('other@example.com')
        return self.auth(self.login(email='other@example.com'))

    def new_profile(self, headers=None, **body):
        body = {'name': 'My Hawk', 'designation': 'MH-60', **body}
        response = self.client.post('/api/aircraft-profiles', headers=headers or self.head, json=body)
        self.assertIn(response.status_code, (200, 201), response.get_data(as_text=True))
        return response.get_json()

    def listing(self, headers=None):
        response = self.client.get('/api/aircraft-profiles', headers=headers or self.head)
        self.assertEqual(response.status_code, 200)
        return response.get_json()

    def feed(self, since=0, headers=None):
        return self.client.get(f'/api/sync/changes?since={since}', headers=headers or self.head)

    def rows(self, **filters):
        with self.app.app_context():
            return AircraftProfile.query.filter_by(**filters).all()


class WebStillWorksTests(AircraftCase):
    """What the web app does with these routes, which sync must not change."""

    def test_the_list_is_the_active_master_profiles_then_the_users_own(self):
        mine = self.new_profile()
        slugs = [p['slug'] for p in self.listing()]
        self.assertEqual(slugs, ['uh-60l', mine['slug']])          # the retired one is hidden
        self.assertEqual([p['is_system'] for p in self.listing()], [True, False])

    def test_a_custom_profile_is_private_to_its_owner(self):
        self.new_profile()
        other = self.other_user()
        self.assertEqual([p['slug'] for p in self.listing(other)], ['uh-60l'])

    def test_create_fills_defaults_and_makes_a_slug_from_the_designation(self):
        made = self.client.post('/api/aircraft-profiles', headers=self.head, json={})
        self.assertEqual(made.status_code, 201)
        body = made.get_json()
        self.assertEqual((body['name'], body['designation'], body['slug']), ('Custom aircraft', 'CUSTOM', 'custom'))
        self.assertEqual((body['perf_source'], body['is_system']), ('custom', False))
        self.assertEqual(body['rotor_diameter_m'], 16.357)

    def test_a_second_profile_with_the_same_designation_gets_a_numbered_slug(self):
        self.assertEqual(self.new_profile()['slug'], 'mh-60')
        self.assertEqual(self.new_profile()['slug'], 'mh-60-2')
        self.assertEqual(self.new_profile()['slug'], 'mh-60-3')

    def test_two_users_may_use_the_same_slug(self):
        self.new_profile()
        self.assertEqual(self.new_profile(headers=self.other_user())['slug'], 'mh-60')

    def test_validation_is_as_before(self):
        for body, fragment in (
            ({'name': '  '}, 'Name is required'),
            ({'designation': ''}, 'Designation is required'),
            ({'rotor_diameter_m': 0.5}, 'rotor_diameter_m must be between'),
            ({'rotor_diameter_m': 'wide'}, 'must be a number'),
            ({'default_airspeed_type': 'mach'}, 'Airspeed type must be one of'),
            ({'default_altitude_ref': 'hae'}, "Altitude reference must be 'agl' or 'msl'"),
            ({'min_altitude_ft_msl': 9000, 'max_altitude_ft_msl': 8000}, 'cannot exceed'),
        ):
            with self.subTest(body=body):
                response = self.client.post('/api/aircraft-profiles', headers=self.head, json=body)
                self.assertEqual(response.status_code, 400)
                self.assertIn(fragment, response.get_json()['error'])
        self.assertEqual(self.rows(user_id=1), [])                  # nothing half-made

    def test_update_changes_only_what_is_sent(self):
        mine = self.new_profile(rotor_diameter_m=14.0)
        response = self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head, json={'name': 'Renamed'})
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual((body['name'], body['designation'], body['rotor_diameter_m']), ('Renamed', 'MH-60', 14.0))

    def test_a_refused_update_changes_nothing(self):
        mine = self.new_profile()
        response = self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head,
                                   json={'name': 'Changed', 'rotor_diameter_m': 999})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.listing()[1]['name'], 'My Hawk')

    def test_admin_only_fields_are_ignored_on_the_user_endpoints(self):
        mine = self.new_profile(perf_source='vidx', is_active=False, sort_order=1)
        self.assertEqual((mine['perf_source'], mine['sort_order']), ('custom', 100))

    def test_a_master_profile_is_visible_but_not_editable_or_deletable_here(self):
        master_id = self.listing()[0]['id']
        for call in (
            lambda: self.client.put(f'/api/aircraft-profiles/{master_id}', headers=self.head, json={'name': 'x'}),
            lambda: self.client.delete(f'/api/aircraft-profiles/{master_id}', headers=self.head),
        ):
            self.assertEqual(call().status_code, 404)
        self.assertEqual(self.listing()[0]['name'], 'UH-60L Black Hawk')

    def test_one_user_cannot_edit_or_delete_anothers(self):
        mine = self.new_profile()
        other = self.other_user()
        self.assertEqual(self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=other, json={'name': 'x'}).status_code, 404)
        self.assertEqual(self.client.delete(f'/api/aircraft-profiles/{mine["id"]}', headers=other).status_code, 404)
        self.assertEqual(self.listing()[1]['name'], 'My Hawk')

    def test_delete_removes_it_from_the_list(self):
        mine = self.new_profile()
        response = self.client.delete(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head)
        self.assertEqual((response.status_code, response.get_json()['status']), (200, 'deleted'))
        self.assertEqual([p['slug'] for p in self.listing()], ['uh-60l'])

    def test_without_the_entitlement_the_master_list_reads_but_nothing_can_be_made(self):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = {'aircraft_profiles': False}
            db.session.commit()
        self.assertEqual([p['slug'] for p in self.listing()], ['uh-60l'])
        self.assertEqual(self.client.post('/api/aircraft-profiles', headers=self.head, json={}).status_code, 403)

    def test_everything_needs_a_token(self):
        for call in (
            lambda: self.client.get('/api/aircraft-profiles'),
            lambda: self.client.post('/api/aircraft-profiles', json={}),
            lambda: self.client.put('/api/aircraft-profiles/1', json={}),
            lambda: self.client.delete('/api/aircraft-profiles/1'),
        ):
            self.assertEqual(call().status_code, 401)

    def test_an_edit_with_none_of_the_new_headers_is_last_writer_wins(self):
        mine = self.new_profile()
        path = f'/api/aircraft-profiles/{mine["id"]}'
        for name in ('One', 'Two', 'Three'):
            self.assertEqual(self.client.put(path, headers=self.head, json={'name': name}).status_code, 200)
        self.assertEqual(self.listing()[1]['name'], 'Three')


class IdentityAndRevisionTests(AircraftCase):
    def test_a_custom_profile_has_an_identity_and_a_revision_from_birth(self):
        made = self.new_profile()
        self.assertEqual(made['revision'], 1)
        self.assertTrue(uuid.UUID(made['client_uuid']))
        listed = self.listing()[1]
        self.assertEqual((listed['client_uuid'], listed['revision']), (made['client_uuid'], 1))

    def test_the_master_list_carries_no_sync_fields(self):
        # Nobody edits these from a device, and the apps just refetch the list.
        master = self.listing()[0]
        self.assertNotIn('client_uuid', master)
        self.assertNotIn('revision', master)

    def test_a_device_names_its_own_profile(self):
        mine = str(uuid.uuid4())
        self.assertEqual(self.new_profile(client_uuid=mine)['client_uuid'], mine)
        folded = str(uuid.uuid4())
        self.assertEqual(self.new_profile(client_uuid=folded.upper())['client_uuid'], folded)

    def test_creating_the_same_profile_twice_returns_the_first(self):
        mine = str(uuid.uuid4())
        first = self.new_profile(client_uuid=mine)
        again = self.new_profile(client_uuid=mine, name='A different name')
        self.assertEqual(first['id'], again['id'])
        self.assertEqual(again['name'], 'My Hawk')
        self.assertEqual(len(self.rows(user_id=1)), 1)

    def test_a_retried_create_is_200_not_201(self):
        mine = str(uuid.uuid4())
        statuses = [
            self.client.post('/api/aircraft-profiles', headers=self.head, json={'client_uuid': mine}).status_code
            for _ in range(2)
        ]
        self.assertEqual(statuses, [201, 200])

    def test_the_database_itself_refuses_two_profiles_with_one_identity(self):
        from sqlalchemy.exc import IntegrityError
        mine = str(uuid.uuid4())
        with self.app.app_context():
            uid = User.query.one().id
            for slug in ('a', 'b'):
                db.session.add(AircraftProfile(user_id=uid, slug=slug, name=slug, designation=slug, client_uuid=mine))
                if slug == 'a':
                    db.session.commit()
            with self.assertRaises(IntegrityError):
                db.session.commit()
            db.session.rollback()

    def test_master_profiles_and_old_rows_without_an_identity_do_not_collide(self):
        with self.app.app_context():
            db.session.add_all(
                AircraftProfile(user_id=None, slug=f'm{i}', name='m', designation='m') for i in range(3)
            )
            db.session.commit()                                       # three more beside the two already there
            self.assertEqual(AircraftProfile.query.filter_by(user_id=None).count(), 5)

    def test_two_users_may_use_the_same_identity(self):
        mine = str(uuid.uuid4())
        self.new_profile(client_uuid=mine)
        other = self.new_profile(client_uuid=mine, headers=self.other_user())
        self.assertEqual(other['client_uuid'], mine)
        self.assertEqual(len(self.rows(client_uuid=mine)), 2)

    def test_a_malformed_identity_is_refused(self):
        for bad in ('not-a-uuid', 'x' * 40, 5, '', '<script>'):
            with self.subTest(bad=bad):
                response = self.client.post('/api/aircraft-profiles', headers=self.head, json={'client_uuid': bad})
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'invalid_client_uuid')
        self.assertEqual(self.rows(user_id=1), [])

    def test_each_edit_bumps_the_revision_and_says_so(self):
        mine = self.new_profile()
        for expected in (2, 3):
            response = self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head, json={'name': f'v{expected}'})
            self.assertEqual(response.get_json()['revision'], expected)
            self.assertEqual(response.headers['ETag'], f'"{expected}"')

    def test_an_edit_the_server_refuses_does_not_bump_the_revision(self):
        mine = self.new_profile()
        bad = self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head, json={'rotor_diameter_m': 999})
        self.assertEqual(bad.status_code, 400)
        self.assertEqual(self.listing()[1]['revision'], 1)

    def test_the_create_response_carries_the_etag(self):
        response = self.client.post('/api/aircraft-profiles', headers=self.head, json={})
        self.assertEqual(response.headers['ETag'], '"1"')


class ConflictTests(AircraftCase):
    def setUp(self):
        super().setUp()
        self.mine = self.new_profile()
        self.path = f'/api/aircraft-profiles/{self.mine["id"]}'
        # Edited somewhere else, so the server is now at revision 2.
        self.client.put(self.path, headers=self.head, json={'name': 'Edited elsewhere'})

    def put(self, if_match, **body):
        return self.client.put(self.path, headers={**self.head, 'If-Match': if_match}, json=body or {'name': 'Mine'})

    def test_an_edit_on_the_current_revision_goes_through(self):
        self.assertEqual(self.put('"2"').status_code, 200)

    def test_an_edit_on_a_stale_revision_is_refused_with_the_servers_copy(self):
        response = self.put('"1"')
        self.assertEqual(response.status_code, 409)
        body = response.get_json()
        self.assertEqual(body['code'], 'revision_conflict')
        self.assertEqual((body['server']['name'], body['server']['revision']), ('Edited elsewhere', 2))
        self.assertEqual(response.headers['ETag'], '"2"')
        self.assertEqual(self.listing()[1]['name'], 'Edited elsewhere')            # nothing overwritten

    def test_a_revision_from_the_future_is_also_a_conflict(self):
        self.assertEqual(self.put('"9"').status_code, 409)

    def test_if_match_forms_are_accepted(self):
        for form in ('2', '"2"', 'W/"2"', '*'):
            with self.subTest(form=form):
                self.rewind()
                self.assertEqual(self.put(form, name='ok').status_code, 200)

    def rewind(self):
        """Put the revision back to 2 so each form is tried against the same server state."""
        with self.app.app_context():
            row = AircraftProfile.query.filter_by(id=self.mine['id']).one()
            row.revision = 2
            db.session.commit()

    def test_a_malformed_if_match_is_refused(self):
        for bad in ('x', '"two"', '1.5', '-1'):
            with self.subTest(bad=bad):
                response = self.put(bad)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'invalid_if_match')

    def test_a_delete_on_a_stale_revision_is_a_conflict_and_deletes_nothing(self):
        response = self.client.delete(self.path, headers={**self.head, 'If-Match': '"1"'})
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.get_json()['server']['name'], 'Edited elsewhere')
        self.assertEqual(len(self.listing()), 2)

    def test_a_retried_update_is_recognised_not_refused(self):
        headers = {**self.head, 'If-Match': '"2"', 'Idempotency-Key': 'once-only'}
        first = self.client.put(self.path, headers=headers, json={'name': 'Mine'})
        retry = self.client.put(self.path, headers=headers, json={'name': 'Mine'})     # same stale If-Match, lost response
        self.assertEqual((first.status_code, retry.status_code), (200, 200))
        self.assertEqual(retry.get_json()['revision'], 3)                           # not bumped twice

    def test_a_different_key_on_a_stale_revision_is_still_a_conflict(self):
        headers = {**self.head, 'If-Match': '"1"', 'Idempotency-Key': 'another'}
        self.assertEqual(self.client.put(self.path, headers=headers, json={'name': 'x'}).status_code, 409)

    def test_the_conflict_check_is_per_user(self):
        other = self.other_user()
        self.assertEqual(self.client.put(self.path, headers={**other, 'If-Match': '"2"'}, json={'name': 'x'}).status_code, 404)


class DeletionTests(AircraftCase):
    def test_a_deletion_leaves_a_tombstone_with_the_content_gone(self):
        mine = self.new_profile(name='Secret airframe', amps_vehicle_description='Air:Rotary Wing:X')
        with self.app.app_context():
            row = AircraftProfile.query.filter_by(id=mine['id']).one()
            row.template_file, row.template_name, row.template_kind = b'PK\x03\x04 amps bytes', 't.msnx', 'msnx'
            db.session.commit()

        self.assertEqual(self.client.delete(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head).status_code, 200)
        (stone,) = self.rows(user_id=1)
        self.assertIsNotNone(stone.deleted_at)
        self.assertEqual(stone.revision, 2)
        self.assertEqual((stone.name, stone.designation), ('', ''))
        self.assertIsNone(stone.amps_vehicle_description)
        self.assertEqual((stone.template_file, stone.template_name, stone.template_kind), (None, None, None))
        self.assertEqual(stone.client_uuid, mine['client_uuid'])                    # identity survives

    def test_a_deleted_profile_is_gone_to_everything_but_the_feed(self):
        mine = self.new_profile()
        path = f'/api/aircraft-profiles/{mine["id"]}'
        self.client.delete(path, headers=self.head)
        self.assertEqual([p['slug'] for p in self.listing()], ['uh-60l'])
        self.assertEqual(self.client.put(path, headers=self.head, json={'name': 'x'}).status_code, 404)
        self.assertEqual(self.client.get(f'{path}/template', headers=self.head).status_code, 404)

    def test_deleting_twice_is_not_an_error(self):
        mine = self.new_profile()
        path = f'/api/aircraft-profiles/{mine["id"]}'
        self.assertEqual(self.client.delete(path, headers=self.head).status_code, 200)
        self.assertEqual(self.client.delete(path, headers=self.head).status_code, 200)
        self.assertEqual(self.rows(user_id=1)[0].revision, 2)

    def test_a_deleted_identity_is_not_resurrected_by_a_retried_create(self):
        mine = str(uuid.uuid4())
        made = self.new_profile(client_uuid=mine)
        self.client.delete(f'/api/aircraft-profiles/{made["id"]}', headers=self.head)
        again = self.client.post('/api/aircraft-profiles', headers=self.head, json={'client_uuid': mine})
        self.assertEqual(again.status_code, 200)                                    # the tombstone, not a new profile
        self.assertEqual(len(self.rows(user_id=1)), 1)
        self.assertEqual([p['slug'] for p in self.listing()], ['uh-60l'])

    def test_a_deleted_profiles_slug_can_be_used_again(self):
        first = self.new_profile()
        self.client.delete(f'/api/aircraft-profiles/{first["id"]}', headers=self.head)
        self.assertEqual(self.new_profile()['slug'], 'mh-60')

    def test_a_live_profile_still_forces_a_numbered_slug_beside_a_tombstone(self):
        first = self.new_profile()
        self.client.delete(f'/api/aircraft-profiles/{first["id"]}', headers=self.head)
        self.assertEqual(self.new_profile()['slug'], 'mh-60')
        self.assertEqual(self.new_profile()['slug'], 'mh-60-2')


class ChangeFeedTests(AircraftCase):
    def changes(self, since=0, headers=None):
        return self.feed(since, headers).get_json()['changes']

    def test_a_custom_profile_arrives_in_the_feed_whole(self):
        mine = self.new_profile(rotor_diameter_m=14.0)
        (change,) = self.changes()
        self.assertEqual(change['type'], 'aircraft')
        self.assertEqual((change['id'], change['client_uuid'], change['revision']), (mine['id'], mine['client_uuid'], 1))
        self.assertEqual((change['name'], change['deleted']), ('My Hawk', False))
        self.assertEqual(change['data'], mine)
        self.assertEqual(change['data']['rotor_diameter_m'], 14.0)

    def test_the_master_list_is_never_in_the_feed(self):
        self.assertEqual(self.changes(), [])
        self.new_profile()
        self.assertEqual([c['type'] for c in self.changes()], ['aircraft'])

    def test_an_edit_moves_it_to_the_end_with_its_new_revision(self):
        mine = self.new_profile()
        self.client.post('/api/lz', headers=self.head, json={'name': 'LZ', 'lz_data': {}})
        self.client.put(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head, json={'name': 'Edited'})
        changes = self.changes()
        self.assertEqual([(c['type'], c['revision']) for c in changes], [('lz', 1), ('aircraft', 2)])
        self.assertEqual(changes[-1]['data']['name'], 'Edited')

    def test_a_deletion_comes_through_as_a_tombstone(self):
        mine = self.new_profile()
        self.client.delete(f'/api/aircraft-profiles/{mine["id"]}', headers=self.head)
        (change,) = self.changes()
        self.assertEqual((change['deleted'], change['name'], change['data'], change['revision']), (True, '', {}, 2))
        self.assertEqual(change['client_uuid'], mine['client_uuid'])

    def test_a_cursor_picks_up_where_it_left_off(self):
        self.new_profile()
        cursor = self.feed().get_json()['cursor']
        self.assertEqual(self.changes(cursor), [])
        self.new_profile()
        self.assertEqual(len(self.changes(cursor)), 1)

    def test_a_user_sees_only_their_own_profiles(self):
        self.new_profile()
        other = self.other_user()
        self.assertEqual(self.changes(headers=other), [])

    def test_profiles_that_predate_sync_are_given_an_identity_and_a_place(self):
        with self.app.app_context():
            uid = User.query.one().id
            db.session.add(AircraftProfile(user_id=uid, slug='old', name='Old', designation='OLD'))
            db.session.commit()
        (change,) = self.changes()
        self.assertTrue(uuid.UUID(change['client_uuid']))
        self.assertEqual((change['revision'], change['name']), (1, 'Old'))
        self.assertEqual(self.listing()[1]['client_uuid'], change['client_uuid'])

    def test_giving_old_rows_an_identity_leaves_the_master_list_alone(self):
        self.new_profile()
        self.changes()
        for master in self.rows(user_id=None):
            self.assertIsNone(master.client_uuid)
            self.assertIsNone(master.change_seq)

    def test_the_account_goes_with_its_profiles_and_tombstones(self):
        made = self.new_profile()
        self.new_profile()
        self.client.delete(f'/api/aircraft-profiles/{made["id"]}', headers=self.head)
        response = self.client.delete('/api/auth/me', headers=self.head, json={'confirm': 'DELETE', 'password': PASSWORD})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        self.assertEqual(self.rows(user_id=1), [])
        self.assertEqual(len(self.rows(user_id=None)), 2)                           # the master list stays


class AdminEditsReachDevicesTests(AircraftCase):
    """The admin dashboard can edit, retire and delete a user's profile; their devices must hear of it."""

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
        self.mine = self.new_profile()
        self.base = f'/admin/aircraft/{self.mine["id"]}'

    FORM = {'csrf': 'token', 'name': 'Renamed by admin', 'designation': 'MH-60', 'is_active': 'on'}

    def server_copy(self):
        return self.listing()[1]

    def test_an_admin_edit_bumps_the_revision_and_is_in_the_feed(self):
        self.assertEqual(self.admin.post(f'{self.base}/save', data=self.FORM).status_code, 302)
        self.assertEqual((self.server_copy()['name'], self.server_copy()['revision']), ('Renamed by admin', 2))
        (change,) = self.feed().get_json()['changes']
        self.assertEqual((change['revision'], change['data']['name']), (2, 'Renamed by admin'))

    def test_a_device_editing_the_old_copy_then_gets_the_conflict(self):
        self.admin.post(f'{self.base}/save', data=self.FORM)
        stale = self.client.put(f'/api/aircraft-profiles/{self.mine["id"]}', headers={**self.head, 'If-Match': '"1"'},
                                json={'name': 'Mine'})
        self.assertEqual(stale.status_code, 409)
        self.assertEqual(stale.get_json()['server']['name'], 'Renamed by admin')

    def test_a_refused_admin_edit_changes_nothing_and_bumps_nothing(self):
        self.admin.post(f'{self.base}/save', data={**self.FORM, 'name': ''})
        self.assertEqual((self.server_copy()['name'], self.server_copy()['revision']), ('My Hawk', 1))

    def test_clearing_a_template_bumps_the_revision(self):
        with self.app.app_context():
            row = AircraftProfile.query.filter_by(id=self.mine['id']).one()
            row.template_file, row.template_name, row.template_kind = b'PK\x03\x04', 't.msnx', 'msnx'
            db.session.commit()
        self.admin.post(f'{self.base}/template', data={'csrf': 'token', 'clear': '1'})
        self.assertEqual((self.server_copy()['has_template'], self.server_copy()['revision']), (False, 2))

    def test_an_admin_deleting_a_users_profile_leaves_a_tombstone_for_their_devices(self):
        response = self.admin.post(f'{self.base}/delete', data={'csrf': 'token', 'confirm_slug': self.mine['slug']})
        self.assertEqual(response.status_code, 302)
        (stone,) = self.rows(user_id=1)
        self.assertIsNotNone(stone.deleted_at)
        self.assertEqual(stone.name, '')
        (change,) = self.feed().get_json()['changes']
        self.assertEqual((change['deleted'], change['revision']), (True, 2))

    def test_an_admin_deleting_a_master_profile_still_removes_the_row(self):
        master = self.rows(slug='retired')[0]
        self.admin.post(f'/admin/aircraft/{master.id}/delete', data={'csrf': 'token', 'confirm_slug': 'retired'})
        self.assertEqual(self.rows(slug='retired'), [])

    def test_the_dashboard_lists_live_custom_profiles_not_tombstones(self):
        gone = self.new_profile(name='Scrapped airframe')
        self.client.delete(f'/api/aircraft-profiles/{gone["id"]}', headers=self.head)
        page = self.admin.get('/admin/aircraft')
        self.assertEqual(page.status_code, 200, page.get_data(as_text=True)[:300])
        html = page.get_data(as_text=True)
        self.assertIn('My Hawk', html)
        self.assertNotIn(f'/admin/aircraft/{gone["id"]}"', html)

    def test_a_tombstone_is_not_found_in_the_dashboard(self):
        self.client.delete(f'/api/aircraft-profiles/{self.mine["id"]}', headers=self.head)
        for path, data in ((self.base, None), (f'{self.base}/save', self.FORM), (f'{self.base}/promote', {'csrf': 'token'}),
                           (f'{self.base}/delete', {'csrf': 'token', 'confirm_slug': self.mine['slug']})):
            with self.subTest(path=path):
                response = self.admin.post(path, data=data) if data else self.admin.get(path)
                self.assertEqual(response.status_code, 404)
        self.assertEqual(self.rows(user_id=1)[0].revision, 2)                       # none of it touched the tombstone


class ContractTests(AircraftCase):
    """Real responses, held to contracts/openapi.yaml."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def conforms(self, response, path, method, status):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(check_response(self.spec, path, method, status, response.get_json()), [])

    ITEM = '/api/aircraft-profiles/{id}'

    def test_listing_and_creating(self):
        mine = str(uuid.uuid4())
        body = {'name': 'Mine', 'designation': 'X', 'client_uuid': mine}
        self.conforms(self.client.post('/api/aircraft-profiles', headers=self.head, json=body), '/api/aircraft-profiles', 'post', 201)
        self.conforms(self.client.post('/api/aircraft-profiles', headers=self.head, json=body), '/api/aircraft-profiles', 'post', 200)
        listing = self.client.get('/api/aircraft-profiles', headers=self.head)
        self.conforms(listing, '/api/aircraft-profiles', 'get', 200)
        self.assertEqual([p['is_system'] for p in listing.get_json()], [True, False])      # both shapes were checked

    def test_refusals(self):
        self.conforms(self.client.post('/api/aircraft-profiles', headers=self.head, json={'rotor_diameter_m': 999}),
                      '/api/aircraft-profiles', 'post', 400)
        self.conforms(self.client.post('/api/aircraft-profiles', headers=self.head, json={'client_uuid': 'nope'}),
                      '/api/aircraft-profiles', 'post', 400)

    def test_editing_and_conflicts(self):
        made = self.new_profile()
        path = f'/api/aircraft-profiles/{made["id"]}'
        self.conforms(self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'a'}), self.ITEM, 'put', 200)
        self.conforms(self.client.put(path, headers={**self.head, 'If-Match': '"1"'}, json={'name': 'b'}), self.ITEM, 'put', 409)
        self.conforms(self.client.put(path, headers={**self.head, 'If-Match': 'x'}, json={}), self.ITEM, 'put', 400)
        self.conforms(self.client.put(path, headers=self.head, json={'rotor_diameter_m': 999}), self.ITEM, 'put', 400)
        self.conforms(self.client.put('/api/aircraft-profiles/9999', headers=self.head, json={}), self.ITEM, 'put', 404)

    def test_deleting(self):
        made = self.new_profile()
        path = f'/api/aircraft-profiles/{made["id"]}'
        self.conforms(self.client.delete(path, headers={**self.head, 'If-Match': '"9"'}), self.ITEM, 'delete', 409)
        self.conforms(self.client.delete(path, headers=self.head), self.ITEM, 'delete', 200)
        self.conforms(self.client.delete('/api/aircraft-profiles/9999', headers=self.head), self.ITEM, 'delete', 404)

    def test_without_the_entitlement(self):
        made = self.new_profile()
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = {'aircraft_profiles': False}
            db.session.commit()
        self.conforms(self.client.post('/api/aircraft-profiles', headers=self.head, json={}), '/api/aircraft-profiles', 'post', 403)
        self.conforms(self.client.put(f'/api/aircraft-profiles/{made["id"]}', headers=self.head, json={}), self.ITEM, 'put', 403)

    def test_the_feed_carries_profiles_and_their_tombstones(self):
        keep = self.new_profile()
        gone = self.new_profile()
        self.client.delete(f'/api/aircraft-profiles/{gone["id"]}', headers=self.head)
        response = self.feed()
        self.conforms(response, '/api/sync/changes', 'get', 200)
        changes = response.get_json()['changes']
        self.assertEqual({c['type'] for c in changes}, {'aircraft'})
        self.assertEqual(sorted(c['deleted'] for c in changes), [False, True])
        self.assertEqual({c['client_uuid'] for c in changes}, {keep['client_uuid'], gone['client_uuid']})

    def test_every_documented_aircraft_route_exists_with_its_methods(self):
        routes = {}
        for rule in self.app.url_map.iter_rules():
            routes.setdefault(rule.rule.replace('<int:profile_id>', '{id}'), set()).update(m.lower() for m in rule.methods)
        for path, operations in self.spec['paths'].items():
            if path.startswith('/api/aircraft-profiles'):
                for method in (m for m in operations if m != 'parameters'):
                    with self.subTest(route=f'{method.upper()} {path}'):
                        self.assertIn(method, routes.get(path, set()))

    def test_the_checker_catches_a_drifted_profile(self):
        made = self.new_profile()
        self.assertEqual(check_response(self.spec, '/api/aircraft-profiles/{id}', 'put', 200, made), [])
        self.assertTrue(check_response(self.spec, '/api/aircraft-profiles/{id}', 'put', 200, {**made, 'surprise': 1}))
        self.assertTrue(check_response(self.spec, '/api/aircraft-profiles/{id}', 'put', 200, {**made, 'rotor_diameter_m': '16'}))
        missing = {k: v for k, v in made.items() if k != 'perf_source'}
        self.assertTrue(check_response(self.spec, '/api/aircraft-profiles/{id}', 'put', 200, missing))


if __name__ == '__main__':
    import unittest
    unittest.main()
