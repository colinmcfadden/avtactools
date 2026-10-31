"""The web's lasting sign-in: a refresh token in an HttpOnly cookie, a short access token in the page's memory."""

import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from flask import Flask

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import refresh_cookie  # noqa: E402
from auth_harness import ANDROID, PASSWORD, NativeAuthCase  # noqa: E402
from cors_config import configure_cors, cors_origins  # noqa: E402
from models import LocalCredential, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402

WEB = {'X-EZPZ-Session': 'cookie'}


class Cookie(dict):
    def __init__(self, value):
        super().__init__()
        self.value = value


class WebSessionCase(NativeAuthCase):
    def setUp(self):
        super().setUp()
        # Each test sends the cookie it means to, as a browser holding it would; no jar adds one behind its back.
        self.client = self.app.test_client(use_cookies=False)
        self.make_account()

    def web_login(self, cookie=None, email='pilot@example.com'):
        headers = dict(WEB)
        if cookie:
            headers['Cookie'] = f'{refresh_cookie.REFRESH_COOKIE}={cookie}'
        response = self.client.post('/api/auth/login', headers=headers, json={'email': email, 'password': PASSWORD})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response

    def web_refresh(self, cookie, headers=WEB):
        sent = dict(headers)
        if cookie is not None:
            sent['Cookie'] = f'{refresh_cookie.REFRESH_COOKIE}={cookie}'
        return self.client.post('/api/auth/refresh', headers=sent)

    @staticmethod
    def set_cookie(response):
        """The refresh cookie the response sets, as ``(Cookie, header)``, or None.

        Read by hand: ``http.cookies`` drops a whole header with an attribute it does not know (``Partitioned``).
        """
        for header in response.headers.getlist('Set-Cookie'):
            first, *attributes = [part.strip() for part in header.split(';')]
            name, _, value = first.partition('=')
            if name == refresh_cookie.REFRESH_COOKIE:
                found = Cookie(value)
                for attribute in attributes:
                    key, _, setting = attribute.partition('=')
                    found[key.lower()] = setting
                return found, header
        return None

    def cookie_of(self, response):
        found = self.set_cookie(response)
        self.assertIsNotNone(found, 'no refresh cookie was set')
        return found[0].value

    def lifetime(self, access_token):
        claims = self.claims(access_token)
        return claims['exp'] - claims['iat']

    def past_grace(self):
        for row in self.rows():
            if row.used_at:
                self.age(row.id, used_at=row.used_at - timedelta(minutes=5))


class SignInTests(WebSessionCase):
    def test_a_web_sign_in_that_asks_gets_its_refresh_token_only_as_a_locked_down_cookie(self):
        response = self.web_login()
        body = response.get_json()
        self.assertNotIn('refresh_token', body)
        self.assertEqual(body['session'], 'cookie')
        morsel, header = self.set_cookie(response)
        self.assertTrue(morsel.value)
        self.assertEqual(morsel['path'], '/api/auth')
        self.assertEqual(int(morsel['max-age']), 30 * 24 * 3600)
        lowered = header.lower()
        for attribute in ('httponly', 'secure', 'samesite=none', 'partitioned'):
            with self.subTest(attribute=attribute):
                self.assertIn(attribute, lowered)

    def test_the_access_token_belongs_to_the_browser_session(self):
        body = self.web_login().get_json()
        rows = self.rows()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].client, 'web')
        self.assertEqual(self.claims(body['access_token'])['sid'], rows[0].family)
        # A sign-in's lives the usual day: a browser that will not keep the cookie keeps this token instead.
        self.assertEqual(self.lifetime(body['access_token']), 24 * 3600)
        self.assertEqual(body['expires_in'], 24 * 3600)

    def test_a_sign_in_that_does_not_ask_is_as_it_always_was(self):
        response = self.client.post('/api/auth/login', json={'email': 'pilot@example.com', 'password': PASSWORD})
        self.assertIsNone(self.set_cookie(response))
        self.assertNotIn('sid', self.claims(response.get_json()['access_token']))
        self.assertEqual(self.rows(), [])

    def test_a_native_app_that_sends_the_header_is_still_a_native_app(self):
        response = self.client.post('/api/auth/login', headers={**ANDROID, **WEB},
                                    json={'email': 'pilot@example.com', 'password': PASSWORD})
        self.assertIsNone(self.set_cookie(response))
        self.assertIn('refresh_token', response.get_json())

    def test_a_new_sign_in_ends_the_session_whose_cookie_it_replaces(self):
        first = self.web_login()
        old_cookie, old_access = self.cookie_of(first), first.get_json()['access_token']
        second = self.web_login(cookie=old_cookie)
        self.assertEqual(self.me(old_access).status_code, 401)
        self.assertEqual(self.web_refresh(old_cookie).status_code, 401)
        self.assertEqual(self.web_refresh(self.cookie_of(second)).status_code, 200)

    def test_a_cookie_from_a_native_family_is_not_revoked_by_a_web_sign_in(self):
        phone = self.login(ANDROID)
        self.web_login(cookie=phone['refresh_token'])
        self.assertEqual(self.refresh(phone['refresh_token']).status_code, 200)


class RefreshTests(WebSessionCase):
    def setUp(self):
        super().setUp()
        self.signed_in = self.web_login()
        self.cookie = self.cookie_of(self.signed_in)

    def test_a_refresh_rotates_the_cookie_and_gives_a_short_access_token(self):
        response = self.web_refresh(self.cookie)
        self.assertEqual(response.status_code, 200, response.get_json())
        body = response.get_json()
        self.assertNotIn('refresh_token', body)
        self.assertNotEqual(self.cookie_of(response), self.cookie)
        self.assertEqual(self.lifetime(body['access_token']), 15 * 60)
        self.assertEqual(body['expires_in'], 15 * 60)
        self.assertEqual(self.claims(body['access_token'])['sid'], self.claims(self.signed_in.get_json()['access_token'])['sid'])
        self.assertEqual(self.me(body['access_token']).status_code, 200)

    def test_the_chain_carries_on_and_has_no_end_while_it_is_used(self):
        cookie = self.cookie
        for _ in range(4):
            response = self.web_refresh(cookie)
            self.assertEqual(response.status_code, 200)
            cookie = self.cookie_of(response)
            self.past_grace()
        self.age(self.rows()[0].id, created_at=datetime.utcnow() - timedelta(days=400))
        self.assertEqual(self.web_refresh(cookie).status_code, 200)

    def test_without_the_header_the_cookie_is_not_read_so_another_site_cannot_spend_it(self):
        for headers in ({}, {'X-EZPZ-Session': 'yes'}, {'X-Requested-With': 'XMLHttpRequest'}):
            with self.subTest(headers=headers):
                response = self.web_refresh(self.cookie, headers=headers)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.get_json()['code'], 'invalid_refresh_token')
                self.assertIsNone(self.set_cookie(response))      # nothing touched: the page's own cookie stands
        self.assertTrue(all(row.used_at is None for row in self.rows()))
        self.assertEqual(self.web_refresh(self.cookie).status_code, 200)

    def test_asking_with_no_cookie_says_so_apart_from_an_expired_session(self):
        response = self.web_refresh(None)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_json()['code'], 'refresh_cookie_missing')

    def test_a_token_is_refreshed_only_the_way_it_was_issued(self):
        # A web token never comes back in a body, and so never leaves its cookie.
        self.assertEqual(self.refresh(self.cookie).status_code, 401)
        phone = self.login(ANDROID)
        refused = self.web_refresh(phone['refresh_token'])
        self.assertEqual(refused.status_code, 401)
        self.assertEqual(self.cookie_of(refused), '')                # told to forget it
        self.assertEqual(self.refresh(phone['refresh_token']).status_code, 200)
        self.assertEqual(self.web_refresh(self.cookie).status_code, 200)

    def test_a_repeat_inside_the_grace_period_is_a_lost_response(self):
        first = self.web_refresh(self.cookie)
        again = self.web_refresh(self.cookie)
        self.assertEqual(again.status_code, 200)
        self.assertEqual(self.web_refresh(self.cookie_of(again)).status_code, 200)
        # The answer that was "lost" carried a cookie that no longer works.
        self.past_grace()
        self.assertEqual(self.web_refresh(self.cookie_of(first)).status_code, 401)

    def test_a_copied_cookie_used_after_the_grace_period_ends_the_session_and_is_cleared(self):
        fresh = self.web_refresh(self.cookie)
        access = fresh.get_json()['access_token']
        self.past_grace()
        stolen = self.web_refresh(self.cookie)
        self.assertEqual(stolen.status_code, 401)
        self.assertEqual(stolen.get_json()['code'], 'refresh_reuse_detected')
        self.assertEqual(self.cookie_of(stolen), '')
        self.assertEqual(self.me(access).status_code, 401)
        self.assertEqual(self.web_refresh(self.cookie_of(fresh)).status_code, 401)

    def test_a_browser_unused_for_30_days_must_sign_in_again(self):
        self.age(self.rows()[0].id, expires_at=datetime.utcnow() - timedelta(minutes=1))
        response = self.web_refresh(self.cookie)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.cookie_of(response), '')

    def test_a_password_reset_ends_it(self):
        self.client.post('/api/auth/forgot-password', json={'email': 'pilot@example.com'})
        reset = self.client.post('/api/auth/reset-password', json={'token': self.tokens[-1], 'password': 'a different secure password'})
        self.assertEqual(reset.status_code, 200)
        self.assertEqual(self.me(self.signed_in.get_json()['access_token']).status_code, 401)
        response = self.web_refresh(self.cookie)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.cookie_of(response), '')

    def test_a_suspension_ends_it_at_once(self):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.is_active = False
            db.session.commit()
        self.assertEqual(self.me(self.signed_in.get_json()['access_token']).status_code, 401)
        response = self.web_refresh(self.cookie)
        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.get_json()['code'], 'account_unavailable')
        self.assertEqual(self.cookie_of(response), '')

    def test_a_suspended_credential_ends_it_too(self):
        with self.app.app_context():
            LocalCredential.query.one().status = 'suspended'
            db.session.commit()
        self.assertEqual(self.web_refresh(self.cookie).status_code, 403)

    def test_deleting_the_account_ends_it(self):
        token = self.signed_in.get_json()['access_token']
        done = self.client.delete('/api/auth/me', headers={'Authorization': f'Bearer {token}'},
                                  json={'confirm': 'DELETE', 'password': PASSWORD})
        self.assertEqual(done.status_code, 200)
        self.assertEqual(self.web_refresh(self.cookie).status_code, 401)


class SignOutTests(WebSessionCase):
    def setUp(self):
        super().setUp()
        self.signed_in = self.web_login()
        self.cookie = self.cookie_of(self.signed_in)
        self.access = self.signed_in.get_json()['access_token']

    def test_signing_out_ends_the_session_and_clears_the_cookie(self):
        response = self.client.post('/api/auth/logout', headers={
            'Authorization': f'Bearer {self.access}',
            'Cookie': f'{refresh_cookie.REFRESH_COOKIE}={self.cookie}',
        })
        self.assertEqual(response.status_code, 200)
        morsel, _ = self.set_cookie(response)
        self.assertEqual(morsel.value, '')
        self.assertEqual(morsel['path'], '/api/auth')
        self.assertEqual(self.me(self.access).status_code, 401)
        self.assertEqual(self.web_refresh(self.cookie).status_code, 401)

    def test_a_native_sign_out_sets_no_cookie(self):
        phone = self.login(ANDROID)
        response = self.client.post('/api/auth/logout', headers={'Authorization': f'Bearer {phone["access_token"]}'})
        self.assertIsNone(self.set_cookie(response))

    def test_the_browser_is_listed_and_can_be_signed_out_from_another_device(self):
        phone = self.login(ANDROID)
        headers = {'Authorization': f'Bearer {phone["access_token"]}'}
        sessions = self.client.get('/api/auth/sessions', headers=headers).get_json()['sessions']
        web = [s for s in sessions if s['client'] == 'web']
        self.assertEqual(len(web), 1)
        self.assertEqual(self.client.delete(f'/api/auth/sessions/{web[0]["id"]}', headers=headers).status_code, 200)
        self.assertEqual(self.me(self.access).status_code, 401)
        self.assertEqual(self.web_refresh(self.cookie).status_code, 401)


class ContractTests(WebSessionCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def conforms(self, response, path, method, status):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(check_response(self.spec, path, method, status, response.get_json()), [])

    def test_the_web_answers_are_the_documented_ones(self):
        signed_in = self.web_login()
        self.conforms(signed_in, '/api/auth/login', 'post', 200)
        self.conforms(self.web_refresh(self.cookie_of(signed_in)), '/api/auth/refresh', 'post', 200)
        self.conforms(self.web_refresh(None), '/api/auth/refresh', 'post', 401)


class CorsTests(unittest.TestCase):
    """The credentials the cookie needs are answered only on /api/auth, and only to the origins listed."""

    def app_for(self, origins):
        app = Flask(__name__)
        configure_cors(app, cors_origins({'CORS_ORIGINS': origins}))

        @app.route('/api/auth/refresh', methods=['POST'])
        def refresh():
            return {'status': 'success'}

        @app.route('/api/lz', methods=['GET'])
        def lz():
            return {'items': []}

        return app.test_client()

    def preflight(self, client, path, origin, method='POST'):
        return client.options(path, headers={
            'Origin': origin,
            'Access-Control-Request-Method': method,
            'Access-Control-Request-Headers': 'content-type,x-ezpz-session',
        })

    def test_the_web_may_refresh_with_credentials(self):
        client = self.app_for('https://ezpztac.app')
        response = self.preflight(client, '/api/auth/refresh', 'https://ezpztac.app')
        self.assertEqual(response.headers.get('Access-Control-Allow-Origin'), 'https://ezpztac.app')
        self.assertEqual(response.headers.get('Access-Control-Allow-Credentials'), 'true')
        self.assertIn('x-ezpz-session', response.headers.get('Access-Control-Allow-Headers', '').lower())
        actual = client.post('/api/auth/refresh', headers={'Origin': 'https://ezpztac.app'})
        self.assertEqual(actual.headers.get('Access-Control-Allow-Credentials'), 'true')

    def test_another_site_is_not_answered(self):
        client = self.app_for('https://ezpztac.app')
        response = self.preflight(client, '/api/auth/refresh', 'https://evil.example')
        self.assertIsNone(response.headers.get('Access-Control-Allow-Origin'))
        self.assertIsNone(response.headers.get('Access-Control-Allow-Credentials'))

    def test_previews_by_pattern_are_answered(self):
        client = self.app_for(r'https://ezpztac.app,^https://avtactools-[a-z0-9-]+-colin[.]vercel[.]app$')
        origin = 'https://avtactools-git-x-colin.vercel.app'
        response = self.preflight(client, '/api/auth/refresh', origin)
        self.assertEqual(response.headers.get('Access-Control-Allow-Origin'), origin)
        self.assertEqual(response.headers.get('Access-Control-Allow-Credentials'), 'true')

    def test_other_routes_take_no_credentials(self):
        client = self.app_for('https://ezpztac.app')
        response = client.get('/api/lz', headers={'Origin': 'https://ezpztac.app'})
        self.assertEqual(response.headers.get('Access-Control-Allow-Origin'), 'https://ezpztac.app')
        self.assertIsNone(response.headers.get('Access-Control-Allow-Credentials'))

    def test_a_wildcard_never_comes_with_credentials(self):
        client = self.app_for('*')
        response = self.preflight(client, '/api/auth/refresh', 'https://evil.example')
        self.assertIsNone(response.headers.get('Access-Control-Allow-Credentials'))
        self.assertNotEqual(response.headers.get('Access-Control-Allow-Origin'), 'https://evil.example')


if __name__ == '__main__':
    unittest.main()
