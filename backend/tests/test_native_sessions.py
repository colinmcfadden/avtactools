"""Refresh tokens, device sessions and account deletion for the native apps."""

import os
import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from flask import Flask
from flask_jwt_extended import JWTManager, decode_token

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import refresh_tokens  # noqa: E402
from auth_rate_limit import clear_rate_limits  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from models import (  # noqa: E402
    AccountToken, AircraftProfile, LocalCredential, LoginEvent, SavedLZ, SavedRoute, User, db,
)
from routes.auth import auth_bp  # noqa: E402
from token_revocation import is_revoked  # noqa: E402

PASSWORD = 'a secure flight password'
ANDROID = {'X-EZPZ-Client': 'android/1.4.0 (212)'}
IOS = {'X-EZPZ-Client': 'ios/2.0.0 (9)'}


class NativeAuthCase(unittest.TestCase):
    def setUp(self):
        clear_rate_limits()
        self.app = Flask(__name__)
        self.app.config.update(
            TESTING=True,
            SQLALCHEMY_DATABASE_URI='sqlite://',
            SQLALCHEMY_TRACK_MODIFICATIONS=False,
            JWT_SECRET_KEY='test-only-secret-that-is-over-32-bytes',
            JWT_ACCESS_TOKEN_EXPIRES=timedelta(hours=24),
            GOOGLE_CLIENT_ID='test-client-id',
        )
        db.init_app(self.app)
        jwt = JWTManager(self.app)

        # The production check, not a copy of it.
        @jwt.token_in_blocklist_loader
        def revoked(_header, payload):
            return is_revoked(payload)

        self.app.register_blueprint(auth_bp)
        with self.app.app_context():
            db.create_all()
        self.tokens = []
        patchers = [
            patch('routes.auth.send_verification_email', side_effect=lambda _u, t: self.tokens.append(t) or True),
            patch('routes.auth.send_password_reset_email', side_effect=lambda _u, t: self.tokens.append(t) or True),
            patch('routes.auth.send_welcome_email', return_value=True),
            patch('routes.auth.send_password_changed_email', return_value=True),
            patch('routes.auth.send_new_account_notification', return_value=True),
        ]
        for p in patchers:
            p.start()
            self.addCleanup(p.stop)
        self.client = self.app.test_client()
        self.addCleanup(self._teardown_db)

    def _teardown_db(self):
        with self.app.app_context():
            db.session.remove()
            db.drop_all()

    # -- helpers -----------------------------------------------------------

    def make_account(self, email='pilot@example.com'):
        self.assertEqual(self.client.post('/api/auth/register', json={
            'name': 'Test Pilot', 'email': email, 'password': PASSWORD}).status_code, 202)
        self.assertEqual(self.client.post('/api/auth/verify-email', json={
            'token': self.tokens[-1], 'password': PASSWORD}).status_code, 200)

    def login(self, headers=None, email='pilot@example.com'):
        response = self.client.post('/api/auth/login', headers=headers or {}, json={'email': email, 'password': PASSWORD})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response.get_json()

    def refresh(self, token):
        return self.client.post('/api/auth/refresh', json={'refresh_token': token})

    def me(self, access_token):
        return self.client.get('/api/auth/me', headers={'Authorization': f'Bearer {access_token}'})

    def claims(self, access_token):
        with self.app.app_context():
            return decode_token(access_token)

    def rows(self, **filters):
        with self.app.app_context():
            return AccountToken.query.filter_by(purpose='refresh', **filters).order_by(AccountToken.id).all()

    def age(self, row_id, **changes):
        """Move a stored token's timestamps, standing in for time passing."""
        with self.app.app_context():
            row = db.session.get(AccountToken, row_id)
            for key, value in changes.items():
                setattr(row, key, value)
            db.session.commit()


class IssuingTests(NativeAuthCase):
    def setUp(self):
        super().setUp()
        self.make_account()

    def test_a_native_sign_in_gets_a_refresh_token_and_a_session_id(self):
        body = self.login(ANDROID)
        self.assertTrue(body['refresh_token'])
        self.assertEqual(body['refresh_expires_in'], 30 * 24 * 3600)
        self.assertTrue(self.claims(body['access_token'])['sid'])

    def test_ios_gets_one_too(self):
        self.assertIn('refresh_token', self.login(IOS))

    def test_the_web_gets_neither_so_its_exposure_is_unchanged(self):
        for headers in ({}, {'X-EZPZ-Client': 'web/1.7.7'}, {'X-EZPZ-Client': 'windows/1.0.0'},
                        {'X-EZPZ-Client': '<script>'}):
            with self.subTest(headers=headers):
                body = self.login(headers)
                self.assertNotIn('refresh_token', body)
                self.assertNotIn('sid', self.claims(body['access_token']))

    def test_the_token_is_stored_only_as_a_hash(self):
        body = self.login(ANDROID)
        rows = self.rows()
        self.assertEqual(len(rows), 1)
        self.assertNotEqual(rows[0].token_hash, body['refresh_token'])
        self.assertEqual(len(rows[0].token_hash), 64)
        self.assertEqual(rows[0].client, 'android/1.4.0 (212)')
        self.assertEqual(rows[0].family, self.claims(body['access_token'])['sid'])
        self.assertGreater(rows[0].expires_at, datetime.utcnow() + timedelta(days=29))

    def test_each_device_is_its_own_session(self):
        first, second = self.login(ANDROID), self.login(IOS)
        self.assertNotEqual(self.claims(first['access_token'])['sid'], self.claims(second['access_token'])['sid'])

    def test_a_sign_in_is_still_recorded_with_its_client(self):
        self.login(ANDROID)
        with self.app.app_context():
            self.assertEqual(LoginEvent.query.order_by(LoginEvent.id.desc()).first().client, 'android/1.4.0 (212)')


class RefreshTests(NativeAuthCase):
    def setUp(self):
        super().setUp()
        self.make_account()
        self.session = self.login(ANDROID)

    def test_a_refresh_returns_a_working_access_token_and_the_next_refresh_token(self):
        response = self.refresh(self.session['refresh_token'])
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertNotEqual(body['refresh_token'], self.session['refresh_token'])
        self.assertEqual(self.me(body['access_token']).status_code, 200)
        self.assertEqual(self.claims(body['access_token'])['sid'], self.claims(self.session['access_token'])['sid'])

    def test_the_chain_carries_on(self):
        token = self.session['refresh_token']
        for _ in range(4):
            response = self.refresh(token)
            self.assertEqual(response.status_code, 200)
            token = response.get_json()['refresh_token']
            # An honest client never repeats a token; move the clock past the grace window.
            for row in self.rows():
                if row.used_at:
                    self.age(row.id, used_at=row.used_at - timedelta(minutes=5))
        self.assertEqual(len(self.rows()), 5)
        self.assertEqual(len({r.family for r in self.rows()}), 1)

    def test_an_unknown_malformed_or_missing_token_is_refused(self):
        for token in ('nope', '', None, 5, 'x' * 500):
            with self.subTest(token=token):
                response = self.refresh(token)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.get_json()['code'], 'invalid_refresh_token')

    def test_an_expired_token_is_refused(self):
        self.age(self.rows()[0].id, expires_at=datetime.utcnow() - timedelta(seconds=1))
        self.assertEqual(self.refresh(self.session['refresh_token']).status_code, 401)

    def test_a_refresh_token_is_not_an_access_token(self):
        self.assertEqual(self.me(self.session['refresh_token']).status_code, 422)

    def test_the_session_ends_at_the_hard_cap_however_often_it_is_refreshed(self):
        self.age(self.rows()[0].id, created_at=datetime.utcnow() - timedelta(days=181))
        response = self.refresh(self.session['refresh_token'])
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_json()['code'], 'refresh_expired')
        self.assertEqual(self.me(self.session['access_token']).status_code, 401)

    def test_a_password_reset_ends_every_device_session(self):
        other = self.login(IOS)
        self.client.post('/api/auth/forgot-password', json={'email': 'pilot@example.com'})
        reset = self.client.post('/api/auth/reset-password', json={'token': self.tokens[-1], 'password': 'a different secure password'})
        self.assertEqual(reset.status_code, 200)
        for token in (self.session['refresh_token'], other['refresh_token']):
            response = self.refresh(token)
            self.assertEqual(response.status_code, 401)
        self.assertEqual(self.me(self.session['access_token']).status_code, 401)

    def test_a_suspended_account_cannot_refresh(self):
        with self.app.app_context():
            user = User.query.one()
            user.is_active = False
            db.session.commit()
        response = self.refresh(self.session['refresh_token'])
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.get_json()['code'], 'account_unavailable')

    def test_refreshing_does_not_count_as_a_sign_in(self):
        before = self._events()
        self.refresh(self.session['refresh_token'])
        self.assertEqual(self._events(), before)

    def _events(self):
        with self.app.app_context():
            return LoginEvent.query.count()


class ReuseTests(NativeAuthCase):
    """A spent token coming back is a lost response, or it is a copy."""

    def setUp(self):
        super().setUp()
        self.make_account()
        self.session = self.login(ANDROID)
        self.first = self.session['refresh_token']

    def test_a_repeat_straight_away_is_a_lost_response_and_is_reissued(self):
        lost = self.refresh(self.first)              # the device never sees this answer
        again = self.refresh(self.first)             # so it asks again
        self.assertEqual(lost.status_code, 200)
        self.assertEqual(again.status_code, 200)
        # Exactly one token is live afterwards: a retry must not fork the session.
        live = [r for r in self.rows() if r.used_at is None]
        self.assertEqual(len(live), 1)
        self.assertEqual(self.refresh(again.get_json()['refresh_token']).status_code, 200)

    def test_the_token_from_the_lost_response_no_longer_works_for_good(self):
        lost = self.refresh(self.first).get_json()
        self.refresh(self.first)                     # the retry replaces it
        row = next(r for r in self.rows() if r.token_hash == refresh_tokens._hash(lost['refresh_token']))
        self.assertIsNotNone(row.used_at)

    def test_a_spent_token_returning_after_the_window_is_theft_and_ends_the_session(self):
        stolen = self.first
        newer = self.refresh(stolen).get_json()      # the honest device rotates
        for row in self.rows():
            self.age(row.id, used_at=row.used_at - timedelta(minutes=5) if row.used_at else None)
        replay = self.refresh(stolen)                # a copy of the old token turns up later
        self.assertEqual(replay.status_code, 401)
        self.assertEqual(replay.get_json()['code'], 'refresh_reuse_detected')
        # Both sides are out: the thief, and the honest device whose token is now spent too.
        self.assertEqual(self.refresh(newer['refresh_token']).status_code, 401)
        self.assertEqual(self.me(newer['access_token']).status_code, 401)

    def test_the_thief_gets_nothing_from_the_window_either_once_it_has_passed(self):
        self.refresh(self.first)
        self.age(self.rows()[0].id, used_at=datetime.utcnow() - refresh_tokens.REUSE_GRACE - timedelta(seconds=1))
        self.assertEqual(self.refresh(self.first).status_code, 401)

    def test_two_requests_with_one_token_cannot_fork_the_session(self):
        # Both read the token as unspent; the atomic spend lets only one win.
        with self.app.app_context():
            found_a = refresh_tokens.find(self.first)
            found_b = refresh_tokens.find(self.first)
            token_a, failure_a = refresh_tokens.rotate(found_a)
            token_b, failure_b = refresh_tokens.rotate(found_b)
            db.session.commit()
            self.assertIsNone(failure_a)
            self.assertIsNone(failure_b)             # the loser is read as a retry, not as theft
            live = AccountToken.query.filter_by(purpose='refresh', used_at=None).count()
            self.assertEqual(live, 1)


class RevocationTests(NativeAuthCase):
    def setUp(self):
        super().setUp()
        self.make_account()
        self.phone = self.login(ANDROID)
        self.tablet = self.login(IOS)

    def test_logout_ends_this_device_at_once(self):
        headers = {'Authorization': f'Bearer {self.phone["access_token"]}'}
        self.assertEqual(self.me(self.phone['access_token']).status_code, 200)
        self.assertEqual(self.client.post('/api/auth/logout', headers=headers).status_code, 200)
        self.assertEqual(self.me(self.phone['access_token']).status_code, 401)
        self.assertEqual(self.refresh(self.phone['refresh_token']).status_code, 401)
        # ...and only this device.
        self.assertEqual(self.me(self.tablet['access_token']).status_code, 200)

    def test_a_revoked_session_cannot_be_revived_inside_the_grace_window(self):
        # The flaw this guards: a revoked token marked "spent" within the last 30
        # seconds would read as a lost response and be re-issued.
        token = self.refresh(self.phone['refresh_token']).get_json()
        headers = {'Authorization': f'Bearer {token["access_token"]}'}
        self.client.post('/api/auth/logout', headers=headers)
        for stale in (token['refresh_token'], self.phone['refresh_token']):
            self.assertEqual(self.refresh(stale).status_code, 401)

    def test_the_device_list_names_each_device_and_marks_this_one(self):
        headers = {'Authorization': f'Bearer {self.phone["access_token"]}'}
        sessions = self.client.get('/api/auth/sessions', headers=headers).get_json()['sessions']
        self.assertEqual({s['client'] for s in sessions}, {'android/1.4.0 (212)', 'ios/2.0.0 (9)'})
        self.assertEqual([s['client'] for s in sessions if s['current']], ['android/1.4.0 (212)'])
        self.assertTrue(all(s['created_at'].endswith('Z') and s['last_active_at'].endswith('Z') for s in sessions))

    def test_a_lost_device_can_be_signed_out_from_another(self):
        tablet_headers = {'Authorization': f'Bearer {self.tablet["access_token"]}'}
        phone_id = self.claims(self.phone['access_token'])['sid']
        response = self.client.delete(f'/api/auth/sessions/{phone_id}', headers=tablet_headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.me(self.phone['access_token']).status_code, 401)
        self.assertEqual(self.refresh(self.phone['refresh_token']).status_code, 401)
        self.assertEqual(self.me(self.tablet['access_token']).status_code, 200)
        listed = self.client.get('/api/auth/sessions', headers=tablet_headers).get_json()['sessions']
        self.assertEqual(len(listed), 1)

    def test_one_user_cannot_sign_out_another_users_device(self):
        self.make_account('other@example.com')
        intruder = self.login(ANDROID, email='other@example.com')
        phone_id = self.claims(self.phone['access_token'])['sid']
        response = self.client.delete(
            f'/api/auth/sessions/{phone_id}',
            headers={'Authorization': f'Bearer {intruder["access_token"]}'},
        )
        self.assertEqual(response.status_code, 404)
        self.assertEqual(self.me(self.phone['access_token']).status_code, 200)

    def test_an_unknown_session_is_not_found(self):
        headers = {'Authorization': f'Bearer {self.phone["access_token"]}'}
        self.assertEqual(self.client.delete('/api/auth/sessions/nope', headers=headers).status_code, 404)

    def test_the_web_token_has_no_session_to_revoke_and_keeps_working(self):
        web = self.login()
        headers = {'Authorization': f'Bearer {web["access_token"]}'}
        self.assertEqual(self.client.post('/api/auth/logout', headers=headers).status_code, 200)
        self.assertEqual(self.me(web['access_token']).status_code, 200)

    def test_the_lists_need_a_token(self):
        self.assertEqual(self.client.get('/api/auth/sessions').status_code, 401)
        self.assertEqual(self.client.post('/api/auth/logout').status_code, 401)


class HousekeepingTests(NativeAuthCase):
    def test_long_expired_tokens_are_dropped_when_the_next_one_is_issued(self):
        self.make_account()
        self.login(ANDROID)
        self.age(self.rows()[0].id, expires_at=datetime.utcnow() - timedelta(days=3))
        self.login(ANDROID)
        self.assertEqual(len(self.rows()), 1)

    def test_a_signed_out_session_does_not_count_as_a_device(self):
        self.make_account()
        session = self.login(ANDROID)
        headers = {'Authorization': f'Bearer {session["access_token"]}'}
        self.client.post('/api/auth/logout', headers=headers)
        fresh = self.login(IOS)
        listed = self.client.get('/api/auth/sessions', headers={'Authorization': f'Bearer {fresh["access_token"]}'})
        self.assertEqual(len(listed.get_json()['sessions']), 1)


class AccountDeletionTests(NativeAuthCase):
    def setUp(self):
        super().setUp()
        self.make_account()
        self.session = self.login(ANDROID)
        self.headers = {'Authorization': f'Bearer {self.session["access_token"]}'}
        with self.app.app_context():
            uid = User.query.one().id
            db.session.add_all([
                SavedLZ(user_id=uid, name='LZ', lz_data={}),
                SavedRoute(user_id=uid, name='R', route_data={}),
                AircraftProfile(user_id=uid, slug='mine', name='Mine', designation='X'),
            ])
            db.session.commit()

    def delete(self, **body):
        return self.client.delete('/api/auth/me', headers=self.headers, json=body)

    def test_it_deletes_the_account_and_everything_saved_under_it(self):
        response = self.delete(confirm='DELETE', password=PASSWORD)
        self.assertEqual(response.status_code, 200)
        with self.app.app_context():
            self.assertEqual(User.query.count(), 0)
            self.assertEqual(LocalCredential.query.count(), 0)
            self.assertEqual(AccountToken.query.count(), 0)
            self.assertEqual(SavedLZ.query.count(), 0)
            self.assertEqual(SavedRoute.query.count(), 0)
            self.assertEqual(AircraftProfile.query.count(), 0)
            self.assertEqual(LoginEvent.query.count(), 0)
        self.assertEqual(self.me(self.session['access_token']).status_code, 401)
        self.assertEqual(self.refresh(self.session['refresh_token']).status_code, 401)

    def test_it_leaves_other_users_alone(self):
        self.make_account('other@example.com')
        self.login(email='other@example.com')
        self.delete(confirm='DELETE', password=PASSWORD)
        with self.app.app_context():
            self.assertEqual([u.email for u in User.query.all()], ['other@example.com'])

    def test_it_asks_for_the_word_first(self):
        for body in ({}, {'password': PASSWORD}, {'confirm': 'delete', 'password': PASSWORD}, {'confirm': True, 'password': PASSWORD}):
            with self.subTest(body=body):
                response = self.delete(**body)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.get_json()['code'], 'confirmation_required')
        with self.app.app_context():
            self.assertEqual(User.query.count(), 1)

    def test_a_token_alone_is_not_proof_it_is_the_owner(self):
        response = self.delete(confirm='DELETE')
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_json()['code'], 'reauthentication_required')

    def test_the_wrong_password_deletes_nothing(self):
        response = self.delete(confirm='DELETE', password='not the password at all')
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_json()['code'], 'invalid_credentials')
        with self.app.app_context():
            self.assertEqual(User.query.count(), 1)

    def test_a_google_account_confirms_with_a_fresh_google_token(self):
        claims = {'sub': 'g-77', 'email': 'gpilot@example.com', 'email_verified': True, 'name': 'G'}
        with patch('routes.auth.id_token.verify_oauth2_token', return_value=claims):
            sign_in = self.client.post('/api/auth/google', headers=ANDROID, json={'token': 'cred'}).get_json()
        headers = {'Authorization': f'Bearer {sign_in["access_token"]}'}
        # No password to give, and nothing proves identity without a token.
        self.assertEqual(self.client.delete('/api/auth/me', headers=headers, json={'confirm': 'DELETE'}).status_code, 401)
        # A token for a different Google account is refused.
        other = {'sub': 'someone-else', 'email': 'gpilot@example.com', 'email_verified': True}
        with patch('routes.auth.id_token.verify_oauth2_token', return_value=other):
            wrong = self.client.delete('/api/auth/me', headers=headers, json={'confirm': 'DELETE', 'google_token': 't'})
        self.assertEqual(wrong.status_code, 401)
        # An invalid token is refused.
        with patch('routes.auth.id_token.verify_oauth2_token', side_effect=ValueError('bad')):
            invalid = self.client.delete('/api/auth/me', headers=headers, json={'confirm': 'DELETE', 'google_token': 't'})
        self.assertEqual(invalid.status_code, 401)
        with self.app.app_context():
            self.assertIsNotNone(User.query.filter_by(email='gpilot@example.com').first())
        with patch('routes.auth.id_token.verify_oauth2_token', return_value=claims):
            done = self.client.delete('/api/auth/me', headers=headers, json={'confirm': 'DELETE', 'google_token': 't'})
        self.assertEqual(done.status_code, 200)
        with self.app.app_context():
            self.assertIsNone(User.query.filter_by(email='gpilot@example.com').first())

    def test_the_super_admin_cannot_be_deleted(self):
        with patch.dict(os.environ, {'SUPER_ADMIN_EMAIL': 'pilot@example.com'}):
            response = self.delete(confirm='DELETE', password=PASSWORD)
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.get_json()['code'], 'super_admin_protected')
        with self.app.app_context():
            self.assertEqual(User.query.count(), 1)

    def test_it_is_rate_limited(self):
        statuses = [self.delete(confirm='DELETE', password='wrong wrong wrong').status_code for _ in range(7)]
        self.assertEqual(statuses[:5], [401] * 5)
        self.assertEqual(statuses[5], 429)


class ContractTests(NativeAuthCase):
    """Real responses, held to contracts/openapi.yaml."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def setUp(self):
        super().setUp()
        self.make_account()

    def conforms(self, response, path, method, status):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(check_response(self.spec, path, method, status, response.get_json()), [])

    def auth(self, session):
        return {'Authorization': f'Bearer {session["access_token"]}'}

    def test_sign_in_for_native_and_web(self):
        for headers in (ANDROID, IOS, {}):
            with self.subTest(headers=headers):
                response = self.client.post('/api/auth/login', headers=headers, json={'email': 'pilot@example.com', 'password': PASSWORD})
                self.conforms(response, '/api/auth/login', 'post', 200)
        native = self.client.post('/api/auth/login', headers=ANDROID, json={'email': 'pilot@example.com', 'password': PASSWORD}).get_json()
        # The schema makes these optional (the web gets neither); a native app must get both.
        self.assertIn('refresh_token', native)
        self.assertEqual(native['refresh_expires_in'], 30 * 24 * 3600)

    def test_google_sign_in(self):
        claims = {'sub': 'g-1', 'email': 'g@example.com', 'email_verified': True, 'name': 'G'}
        with patch('routes.auth.id_token.verify_oauth2_token', return_value=claims):
            self.conforms(self.client.post('/api/auth/google', headers=ANDROID, json={'token': 't'}), '/api/auth/google', 'post', 200)

    def test_refresh(self):
        session = self.login(ANDROID)
        self.conforms(self.refresh(session['refresh_token']), '/api/auth/refresh', 'post', 200)

    def test_every_error_has_the_documented_shape(self):
        self.conforms(self.refresh('nope'), '/api/auth/refresh', 'post', 401)
        bad = self.client.post('/api/auth/login', json={'email': 'pilot@example.com', 'password': 'wrong'})
        self.conforms(bad, '/api/auth/login', 'post', 401)
        session = self.login(ANDROID)
        self.conforms(self.client.delete('/api/auth/sessions/nope', headers=self.auth(session)), '/api/auth/sessions/{id}', 'delete', 404)
        self.conforms(self.client.delete('/api/auth/me', headers=self.auth(session), json={}), '/api/auth/me', 'delete', 400)
        self.conforms(self.client.delete('/api/auth/me', headers=self.auth(session), json={'confirm': 'DELETE'}), '/api/auth/me', 'delete', 401)

    def test_sessions_logout_me_and_delete(self):
        phone, tablet = self.login(ANDROID), self.login(IOS)
        listed = self.client.get('/api/auth/sessions', headers=self.auth(phone))
        self.conforms(listed, '/api/auth/sessions', 'get', 200)
        self.assertEqual(len(listed.get_json()['sessions']), 2)
        self.conforms(self.me(phone['access_token']), '/api/auth/me', 'get', 200)
        tablet_id = self.claims(tablet['access_token'])['sid']
        self.conforms(self.client.delete(f'/api/auth/sessions/{tablet_id}', headers=self.auth(phone)), '/api/auth/sessions/{id}', 'delete', 200)
        self.conforms(self.client.post('/api/auth/logout', headers=self.auth(phone)), '/api/auth/logout', 'post', 200)
        again = self.login(ANDROID)
        done = self.client.delete('/api/auth/me', headers=self.auth(again), json={'confirm': 'DELETE', 'password': PASSWORD})
        self.conforms(done, '/api/auth/me', 'delete', 200)

    def test_every_documented_auth_route_exists_with_its_methods(self):
        routes = {}
        for rule in self.app.url_map.iter_rules():
            documented = rule.rule.replace('<session_id>', '{id}')
            routes.setdefault(documented, set()).update(m.lower() for m in rule.methods)
        for path, operations in self.spec['paths'].items():
            if not path.startswith('/api/auth/'):
                continue
            for method in operations:
                with self.subTest(route=f'{method.upper()} {path}'):
                    self.assertIn(method, routes.get(path, set()))

    def test_the_checker_still_catches_a_broken_native_response(self):
        session = self.login(ANDROID)
        broken = dict(session)
        del broken['access_token']
        self.assertTrue(check_response(self.spec, '/api/auth/login', 'post', 200, broken))
        broken = dict(session, refresh_expires_in='soon')
        self.assertTrue(check_response(self.spec, '/api/auth/login', 'post', 200, broken))
        sessions = {'sessions': [{'id': 'x', 'client': None, 'created_at': 't', 'last_active_at': 't', 'current': 'yes'}]}
        self.assertTrue(check_response(self.spec, '/api/auth/sessions', 'get', 200, sessions))


if __name__ == '__main__':
    unittest.main()
