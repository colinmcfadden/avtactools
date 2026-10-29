"""Notifications (docs/NOTIFICATIONS.md): what each change tells whom, what is still so, and the three routes, held to
contracts/openapi.yaml."""

import sys
from datetime import datetime, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import notify  # noqa: E402
from auth_harness import PASSWORD  # noqa: E402
from models import MissionPackInvite, Notification, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes import notification_routes  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.notification_routes import notification_bp  # noqa: E402
from routes.pack_routes import pack_bp  # noqa: E402
from routes.point_sets import point_sets_bp  # noqa: E402
from routes.saved_routes import saved_routes_bp  # noqa: E402
from routes.team_routes import team_bp  # noqa: E402
from test_packs import PackCase  # noqa: E402

SPEC = load_spec()
FEED = '/api/notifications'


class NotificationCase(PackCase):
    extra_blueprints = (lz_bp, saved_routes_bp, point_sets_bp, pack_bp, team_bp, notification_bp)

    def setUp(self):
        super().setUp()
        notification_routes._swept_at = None

    def feed(self, who, **headers):
        response = self.client.get(FEED, headers={**who['head'], **headers})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        body = response.get_json()
        self.assertEqual(check_response(SPEC, FEED, 'get', 200, body), [])
        return body

    def shown(self, who):
        return [(n['type'], (n['pack'] or n['team'] or {}).get('name')) for n in self.feed(who)['notifications']]

    def rows(self, **filters):
        with self.app.app_context():
            return Notification.query.filter_by(**filters).order_by(Notification.id).all()

    def shift(self, column, by, **filters):
        """Move ``column`` of the matching rows by ``by``, as the clock would have."""
        with self.app.app_context():
            for row in Notification.query.filter_by(**filters).all():
                setattr(row, column, getattr(row, column) + by)
            db.session.commit()

    def delete_account(self, who):
        return self.ok(self.call(who, 'delete', '/api/auth/me', json={'confirm': 'DELETE', 'password': PASSWORD}))

    def two_minutes_on(self):
        self.shift('deliver_after', -notify.FINISH_DELAY - timedelta(seconds=1), type='pack_finished')

    def invite(self, uuid_, email, role='editor', by=None):
        return self.ok(self.call(by or self.colin, 'post', f'/api/packs/{uuid_}/invites', json={'email': email, 'role': role}),
                       201)['invite']

    def add(self, uuid_, who, role='editor'):
        team = self.team(self.colin, who, name=f'Team {who["name"]}')
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/members', json={'user_id': who['id'], 'role': role}), 201)
        return team


class InvitationTests(NotificationCase):
    def test_an_invitation_to_a_pack_tells_the_account_at_its_address(self):
        uuid_ = self.new_pack()['uuid']
        invite = self.invite(uuid_, 'Sam@Example.com ', role='viewer')
        [note] = self.feed(self.sam)['notifications']
        self.assertEqual(note['type'], 'pack_invite')
        self.assertEqual(note['group'], 'invitations')
        self.assertEqual((note['pack'], note['actor'], note['role'], note['invite_id'], note['team']),
                         ({'uuid': uuid_, 'name': 'OP DK'}, {'name': 'Colin'}, 'viewer', invite['id'], None))
        self.assertEqual(note['key'], f'pack_invite:{invite["id"]}')
        self.assertFalse(note['seen'] or note['quiet'])
        self.assertEqual(self.feed(self.colin)['notifications'], [])          # nothing for the person who did it

    def test_what_is_kept_is_ids_and_the_actors_name_never_a_token(self):
        uuid_ = self.new_pack()['uuid']
        invite = self.invite(uuid_, 'sam@example.com')
        [row] = self.rows(user_id=self.sam['id'])
        self.assertEqual((row.pack_uuid, row.invite_id, row.fields), (uuid_, invite['id'], {'actor': {'name': 'Colin'}, 'role': 'editor'}))
        with self.app.app_context():
            token = db.session.get(MissionPackInvite, invite['id']).token_hash
        self.assertNotIn(token, str(row.fields))
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}'))
        self.assertNotIn('OP DK', str(self.rows()[0].fields))                     # a pack's name never outlives it here

    def test_a_verified_mil_address_is_reached_and_an_unverified_one_is_not(self):
        with self.app.app_context():
            sam = db.session.get(User, self.sam['id'])
            sam.mil_email, sam.mil_verified_at = 'sam.b.mil@army.mil', datetime.utcnow()
            alex = db.session.get(User, self.alex['id'])
            alex.mil_email = 'alex.c.mil@army.mil'                              # asked for a code, never entered it
            db.session.commit()
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'sam.b.mil@army.mil')
        self.invite(uuid_, 'alex.c.mil@army.mil')
        self.assertEqual(self.shown(self.sam), [('pack_invite', 'OP DK')])
        self.assertEqual(self.shown(self.alex), [])

    def test_an_address_with_no_account_tells_nobody(self):
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'kim@example.com')
        self.assertEqual(self.rows(), [])

    def test_answering_or_withdrawing_an_invitation_takes_its_notification_down(self):
        uuid_ = self.new_pack()['uuid']
        accepted = self.invite(uuid_, 'sam@example.com')
        declined = self.invite(uuid_, 'alex@example.com')
        self.ok(self.call(self.sam, 'post', f'/api/invites/{accepted["id"]}/accept'))
        self.ok(self.call(self.alex, 'post', f'/api/invites/{declined["id"]}/decline'))
        self.assertEqual((self.shown(self.sam), self.shown(self.alex)), ([], []))
        dana = self.person('dana@example.com', 'Dana')
        withdrawn = self.invite(uuid_, 'dana@example.com')
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}/invites/{withdrawn["id"]}'))
        self.assertEqual(self.shown(dana), [])
        self.assertTrue(all(row.withdrawn_at for row in self.rows()))

    def test_an_invitation_that_expired_is_no_longer_listed(self):
        uuid_ = self.new_pack()['uuid']
        invite = self.invite(uuid_, 'sam@example.com')
        with self.app.app_context():
            db.session.get(MissionPackInvite, invite['id']).expires_at = datetime.utcnow() - timedelta(seconds=1)
            db.session.commit()
        self.assertEqual(self.shown(self.sam), [])

    def test_sent_again_the_same_day_it_updates_and_a_day_later_it_alerts_again(self):
        uuid_ = self.new_pack()['uuid']
        invite = self.invite(uuid_, 'sam@example.com')
        [first] = self.feed(self.sam)['notifications']
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites', json={'email': 'sam@example.com', 'role': 'viewer'}))
        [again] = self.feed(self.sam)['notifications']
        self.assertEqual((again['id'], again['role']), (first['id'], 'viewer'))    # the same notification, its role now
        self.shift('created_at', -timedelta(days=1, seconds=1), user_id=self.sam['id'])
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites/{invite["id"]}/resend'))
        [renewed] = self.feed(self.sam)['notifications']
        self.assertNotEqual(renewed['id'], first['id'])                            # a new id, so devices alert again
        self.assertEqual(len(self.rows(user_id=self.sam['id'])), 2)

    def test_sent_again_once_seen_it_stays_one_seen_notification_until_a_day_has_passed(self):
        uuid_ = self.new_pack()['uuid']
        invite = self.invite(uuid_, 'sam@example.com')
        [first] = self.feed(self.sam)['notifications']
        self.ok(self.call(self.sam, 'post', f'{FEED}/seen', json={'ids': [first['id']]}))
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites/{invite["id"]}/resend'))
        self.assertEqual([(n['id'], n['seen']) for n in self.feed(self.sam)['notifications']], [(first['id'], True)])
        self.shift('created_at', -timedelta(days=1, seconds=1), user_id=self.sam['id'])
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/invites/{invite["id"]}/resend'))
        [again] = self.feed(self.sam)['notifications']
        self.assertEqual((again['seen'], again['id'] != first['id']), (False, True))

    def test_an_invitation_to_what_they_are_already_in_is_not_listed(self):
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'sam@example.com')
        self.add(uuid_, self.sam)                                                  # added by name before answering
        self.assertEqual([t for t, _ in self.shown(self.sam)], ['pack_added'])
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': 'B Co'}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={'email': 'alex@example.com'}), 201)
        link = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={}), 201)
        self.ok(self.call(self.alex, 'post', '/api/invites/accept', json={'token': link['token']}))
        self.assertEqual(self.shown(self.alex), [])

    def test_a_team_invitation_by_email_tells_its_account_and_a_link_tells_nobody(self):
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': '1-171 AVN'}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={}), 201)
        invite = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites',
                                   json={'email': 'sam@example.com'}), 201)['invite']
        [note] = self.feed(self.sam)['notifications']
        self.assertEqual((note['type'], note['team'], note['pack'], note['role'], note['invite_id']),
                         ('team_invite', {'id': team['id'], 'name': '1-171 AVN'}, None, 'member', invite['id']))
        self.assertEqual(len(self.rows()), 1)
        self.ok(self.call(self.colin, 'put', f'/api/teams/{team["id"]}', json={'name': '1-171 AVN (GSAB)'}))
        self.assertEqual(self.shown(self.sam), [('team_invite', '1-171 AVN (GSAB)')])
        self.ok(self.call(self.colin, 'delete', f'/api/teams/{team["id"]}'))
        self.assertEqual(self.shown(self.sam), [])
        self.assertIsNotNone(self.rows(user_id=self.sam['id'])[0].withdrawn_at)

    def test_a_team_invitation_withdrawn_or_accepted_goes(self):
        team = self.ok(self.call(self.colin, 'post', '/api/teams', json={'name': 'B Co'}), 201)
        to_sam = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={'email': 'sam@example.com'}), 201)
        to_alex = self.ok(self.call(self.colin, 'post', f'/api/teams/{team["id"]}/invites', json={'email': 'alex@example.com'}), 201)
        self.ok(self.call(self.colin, 'delete', f'/api/teams/{team["id"]}/invites/{to_sam["invite"]["id"]}'))
        self.ok(self.call(self.alex, 'post', f'/api/invites/{to_alex["invite"]["id"]}/accept'))
        self.assertEqual((self.shown(self.sam), self.shown(self.alex)), ([], []))


class PackTests(NotificationCase):
    def test_someone_added_by_name_is_told_with_the_role_they_were_given(self):
        uuid_ = self.new_pack()['uuid']
        self.add(uuid_, self.sam, role='viewer')
        [note] = self.feed(self.sam)['notifications']
        self.assertEqual((note['type'], note['role'], note['actor'], note['invite_id']), ('pack_added', 'viewer', {'name': 'Colin'}, None))
        self.ok(self.call(self.colin, 'put', f'/api/packs/{uuid_}/members/{self.sam["id"]}', json={'role': 'editor'}))
        self.assertEqual(self.feed(self.sam)['notifications'][0]['role'], 'viewer')    # what happened; the pack says what is now
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}/members/{self.sam["id"]}'))
        self.assertEqual(self.shown(self.sam), [])                                 # no longer in it: no longer so

    def test_a_finish_tells_everyone_in_the_pack_but_whoever_finished_it_two_minutes_later(self):
        team = self.team(self.colin, self.alex, name='C Co')
        uuid_ = self.new_pack(team_id=team['id'])['uuid']                          # Alex is in it through the team
        self.member(uuid_, self.sam)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
        self.assertEqual((self.shown(self.sam), self.shown(self.alex)), ([], []))   # not yet
        self.two_minutes_on()
        self.assertEqual(self.shown(self.sam), [('pack_finished', 'OP DK')])
        self.assertEqual(self.shown(self.alex), [('pack_finished', 'OP DK')])
        self.assertEqual(self.shown(self.colin), [])
        self.assertEqual(self.feed(self.sam)['notifications'][0]['actor'], {'name': 'Colin'})

    def test_a_reopen_within_the_two_minutes_sends_nothing_and_after_them_takes_it_down(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/reopen'))
        self.two_minutes_on()
        self.assertEqual(self.shown(self.sam), [])
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
        self.two_minutes_on()
        [first] = self.feed(self.sam)['notifications']
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/reopen'))
        self.assertEqual(self.shown(self.sam), [])
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))      # finished again: told again
        self.two_minutes_on()
        [second] = self.feed(self.sam)['notifications']
        self.assertNotEqual(second['id'], first['id'])

    def test_added_again_or_handed_back_is_news_again(self):
        uuid_ = self.new_pack()['uuid']
        self.add(uuid_, self.sam)
        [added] = self.feed(self.sam)['notifications']
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}/members/{self.sam["id"]}'))
        self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/members', json={'user_id': self.sam['id'], 'role': 'viewer'}), 201)
        [again] = self.feed(self.sam)['notifications']
        self.assertEqual((again['id'] != added['id'], again['role']), (True, 'viewer'))   # a new id: devices alert for it
        members = f'/api/packs/{uuid_}/members'
        self.ok(self.call(self.colin, 'put', f'{members}/{self.sam["id"]}', json={'role': 'editor'}))
        self.ok(self.call(self.colin, 'put', f'{members}/{self.sam["id"]}', json={'role': 'owner'}))
        first = [n['id'] for n in self.feed(self.sam)['notifications'] if n['type'] == 'pack_owner']
        self.ok(self.call(self.sam, 'put', f'{members}/{self.colin["id"]}', json={'role': 'owner'}))
        self.ok(self.call(self.colin, 'put', f'{members}/{self.sam["id"]}', json={'role': 'owner'}))
        second = [n['id'] for n in self.feed(self.sam)['notifications'] if n['type'] == 'pack_owner']
        self.assertEqual(len(first), 1)
        self.assertEqual(len(second), 1)
        self.assertNotEqual(first, second)

    def test_a_refused_finish_tells_nobody(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.assertEqual(self.call(self.sam, 'post', f'/api/packs/{uuid_}/finish').status_code, 403)
        self.assertEqual(self.rows(type='pack_finished'), [])

    def test_a_pack_handed_over_tells_its_new_owner_while_it_is_theirs(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.ok(self.call(self.colin, 'put', f'/api/packs/{uuid_}/members/{self.sam["id"]}', json={'role': 'owner'}))
        [note] = self.feed(self.sam)['notifications']
        self.assertEqual((note['type'], note['group'], note['actor']), ('pack_owner', 'pack_owner', {'name': 'Colin'}))
        self.ok(self.call(self.sam, 'put', f'/api/packs/{uuid_}/members/{self.colin["id"]}', json={'role': 'owner'}))
        self.assertEqual(self.shown(self.sam), [])
        self.assertEqual(self.shown(self.colin), [('pack_owner', 'OP DK')])

    def test_the_names_are_the_ones_the_pack_has_now(self):
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'sam@example.com')
        self.ok(self.call(self.colin, 'put', f'/api/packs/{uuid_}', json={'name': 'OP EAGLE'}))
        self.assertEqual(self.shown(self.sam), [('pack_invite', 'OP EAGLE')])

    def test_a_pack_deleted_takes_everything_about_it_down(self):
        uuid_ = self.new_pack()['uuid']
        self.add(uuid_, self.sam)
        self.invite(uuid_, 'alex@example.com')
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{uuid_}'))
        self.assertEqual((self.shown(self.sam), self.shown(self.alex)), ([], []))
        self.assertTrue(all(row.withdrawn_at for row in self.rows()))

    def test_an_account_deleted_takes_its_notifications_and_hands_its_pack_on_with_one(self):
        uuid_ = self.new_pack()['uuid']
        self.member(uuid_, self.sam)
        self.invite(uuid_, 'alex@example.com', by=self.colin)
        self.ok(self.call(self.sam, 'post', '/api/packs', json={'name': 'OP SAM'}), 201)
        self.ok(self.call(self.alex, 'post', '/api/packs', json={'name': 'OP ALEX'}), 201)
        self.delete_account(self.alex)
        self.assertEqual(self.rows(user_id=self.alex['id']), [])
        self.delete_account(self.colin)                                            # Sam, the only one left, inherits OP DK
        [note] = self.feed(self.sam)['notifications']
        self.assertEqual((note['type'], note['pack']['name'], note['actor']), ('pack_owner', 'OP DK', None))


class RouteTests(NotificationCase):
    def test_an_account_without_mission_packs_is_answered_an_empty_list_and_its_settings(self):
        kim = self.person('kim@example.com', 'Kim', packs=False)
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'kim@example.com')
        self.assertEqual(self.feed(kim), {'notifications': [], 'settings': {
            'invitations': True, 'pack_finished': True, 'pack_owner': True}})

    def test_nothing_new_is_a_304_and_anything_new_a_new_tag(self):
        first = self.client.get(FEED, headers=self.sam['head'])
        tag = first.headers['ETag']
        self.assertEqual(first.headers['Cache-Control'], 'private, no-store')
        again = self.client.get(FEED, headers={**self.sam['head'], 'If-None-Match': tag})
        self.assertEqual((again.status_code, again.get_data(), again.headers['ETag']), (304, b'', tag))
        self.invite(self.new_pack()['uuid'], 'sam@example.com')
        changed = self.client.get(FEED, headers={**self.sam['head'], 'If-None-Match': tag})
        self.assertEqual(changed.status_code, 200)
        self.assertNotEqual(changed.headers['ETag'], tag)
        self.assertEqual(self.client.get(FEED, headers={**self.sam['head'], 'If-None-Match': f'"x", {changed.headers["ETag"]}'}).status_code, 304)
        # Behind a proxy that compresses the body (Cloudflare), the tag comes back weak.
        self.assertEqual(self.client.get(FEED, headers={**self.sam['head'], 'If-None-Match': f'W/{changed.headers["ETag"]}'}).status_code, 304)

    def test_seen_on_one_device_is_seen_on_all_and_only_the_callers_own(self):
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'sam@example.com')
        self.invite(uuid_, 'alex@example.com')
        [sams] = self.feed(self.sam)['notifications']
        [alexs] = self.feed(self.alex)['notifications']
        answer = self.ok(self.call(self.sam, 'post', f'{FEED}/seen', json={'ids': [sams['id'], alexs['id'], 99999]}))
        self.assertEqual(check_response(SPEC, f'{FEED}/seen', 'post', 200, answer), [])
        self.assertEqual(answer, {'ids': [sams['id']]})
        self.assertTrue(self.feed(self.sam)['notifications'][0]['seen'])
        self.assertFalse(self.feed(self.alex)['notifications'][0]['seen'])
        self.ok(self.call(self.sam, 'post', f'{FEED}/seen', json={'ids': [sams['id']]}))      # twice changes nothing
        for bad in ({'ids': 'x'}, {'ids': [True]}, {'ids': ['1']}, {}, [1], {'ids': list(range(201))}):
            refused = self.call(self.sam, 'post', f'{FEED}/seen', json=bad)
            self.assertEqual((refused.status_code, refused.get_json()['code']), (400, 'invalid_ids'), bad)
            self.assertEqual(check_response(SPEC, f'{FEED}/seen', 'post', 400, refused.get_json()), [])

    def test_settings_are_on_until_turned_off_and_a_group_not_known_is_passed_over(self):
        path = f'{FEED}/settings'
        answer = self.ok(self.call(self.sam, 'put', path, json={'pack_finished': False, 'something_new': False}))
        self.assertEqual(check_response(SPEC, path, 'put', 200, answer), [])
        self.assertEqual(answer['settings'], {'invitations': True, 'pack_finished': False, 'pack_owner': True})
        self.ok(self.call(self.sam, 'put', path, json={'pack_owner': False}))
        self.assertEqual(self.feed(self.sam)['settings'], {'invitations': True, 'pack_finished': False, 'pack_owner': False})
        for bad in ({'invitations': 'no'}, {'pack_owner': 0}, [True]):
            refused = self.call(self.sam, 'put', path, json=bad)
            self.assertEqual((refused.status_code, refused.get_json()['code']), (400, 'invalid_settings'), bad)
        self.assertEqual(self.feed(self.sam)['settings']['pack_owner'], False)

    def test_an_off_group_is_still_listed(self):
        self.ok(self.call(self.sam, 'put', f'{FEED}/settings', json={'invitations': False}))
        self.invite(self.new_pack()['uuid'], 'sam@example.com')
        self.assertEqual(self.shown(self.sam), [('pack_invite', 'OP DK')])

    def test_past_ten_in_an_hour_they_come_quietly(self):
        for n in range(notify.ALERTS_AN_HOUR + 1):
            self.invite(self.new_pack(name=f'OP {n}')['uuid'], 'sam@example.com')
        notes = self.feed(self.sam)['notifications']
        self.assertEqual([n['quiet'] for n in notes], [True] + [False] * notify.ALERTS_AN_HOUR)   # newest first

    def test_finishes_reopened_in_time_never_alerted_so_they_do_not_count(self):
        team = self.team(self.colin, self.sam, name='C Co')         # Sam is in each pack through the team: nothing to tell him
        for n in range(notify.ALERTS_AN_HOUR + 1):
            uuid_ = self.new_pack(name=f'OP {n}', team_id=team['id'])['uuid']
            self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/finish'))
            self.ok(self.call(self.colin, 'post', f'/api/packs/{uuid_}/reopen'))
        with self.app.app_context():                                # three minutes on: every finish's two minutes have passed
            for row in Notification.query.all():
                for column in ('created_at', 'updated_at', 'deliver_after', 'withdrawn_at'):
                    if getattr(row, column) is not None:
                        setattr(row, column, getattr(row, column) - timedelta(minutes=3))
            db.session.commit()
        self.assertEqual(self.shown(self.sam), [])
        self.invite(self.new_pack(name='OP LAST')['uuid'], 'sam@example.com')
        self.assertFalse(self.feed(self.sam)['notifications'][0]['quiet'])

    def test_rows_no_longer_so_do_not_crowd_out_one_that_is(self):
        uuid_ = self.new_pack()['uuid']
        self.invite(uuid_, 'sam@example.com')
        with self.app.app_context():
            later = datetime.utcnow() + timedelta(seconds=1)
            for n in range(notification_routes.MAX_FEED + 20):                    # newer, about a pack that is gone
                db.session.add(Notification(user_id=self.sam['id'], type='pack_added', collapse_key=f'pack_added:gone-{n}',
                                            pack_uuid=f'gone-{n}', fields={'actor': None, 'role': 'editor'},
                                            created_at=later, updated_at=later, deliver_after=datetime.utcnow()))
            db.session.commit()
        self.assertEqual(self.shown(self.sam), [('pack_invite', 'OP DK')])

    def test_a_month_on_they_are_swept(self):
        self.invite(self.new_pack()['uuid'], 'sam@example.com')
        self.shift('created_at', -notify.KEPT_FOR - timedelta(seconds=1))
        self.assertEqual(self.shown(self.sam), [])
        self.assertEqual(self.rows(), [])

    def test_a_token_is_needed(self):
        self.assertEqual(self.client.get(FEED).status_code, 401)
        self.assertEqual(self.client.post(f'{FEED}/seen', json={'ids': []}).status_code, 401)
        self.assertEqual(self.client.put(f'{FEED}/settings', json={}).status_code, 401)


class WithdrawTests(NotificationCase):
    def test_withdrawing_nothing_in_particular_is_refused_rather_than_taking_everything(self):
        with self.app.app_context(), self.assertRaises(ValueError):
            notify.withdraw(types=('pack_finished',))
