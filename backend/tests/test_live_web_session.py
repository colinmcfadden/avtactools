"""The web's lasting sign-in against the real server, over a real socket (tests/live_server.py).

What a browser does with the cookie is played by hand here: the ``Set-Cookie`` it is given is kept and sent
back as a browser on HTTPS would (an HTTP cookie jar would never send a Secure cookie to this ``http://``
server). So this holds the server's half end to end (CORS preflights, headers on the wire, rotation, the
grace period, months of use, the ways a session ends), and the web's own tests hold the page's half.
"""

import json
import os
import subprocess
import sys
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
PASSWORD = 'a secure flight password'
ORIGIN = 'https://ezpztac.app'
COOKIE = 'ezpz_refresh'


class Answer:
    def __init__(self, status, headers, body):
        self.status, self.headers = status, headers
        self.body = json.loads(body) if body else None

    def cookie(self):
        """The refresh cookie's value and its attributes, lower case, or None when none is set."""
        for header in self.headers.get_all('Set-Cookie') or []:
            first, *attributes = [part.strip() for part in header.split(';')]
            name, _, value = first.partition('=')
            if name == COOKIE:
                return value, [attribute.lower() for attribute in attributes]
        return None


class Browser:
    """One browser profile: its cookie, sent where a browser would send it (under /api/auth, with credentials)."""

    def __init__(self, api):
        self.api, self.cookie = api, None

    def call(self, method, path, body=None, headers=None, session=True, token=None):
        sent = {'Origin': ORIGIN, **(headers or {})}
        if session:
            sent['X-EZPZ-Session'] = 'cookie'
        if body is not None:
            sent['Content-Type'] = 'application/json'
        if token:
            sent['Authorization'] = f'Bearer {token}'
        if self.cookie and path.startswith('/api/auth'):
            sent['Cookie'] = f'{COOKIE}={self.cookie}'
        request = urllib.request.Request(self.api + path, method=method, headers=sent,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                answer = Answer(response.status, response.headers, response.read())
        except urllib.error.HTTPError as error:
            answer = Answer(error.code, error.headers, error.read())
        set_cookie = answer.cookie()
        if set_cookie is not None:
            self.cookie = set_cookie[0] or None                   # an empty value clears it
        return answer

    def sign_in(self, email):
        return self.call('POST', '/api/auth/login', {'email': email, 'password': PASSWORD})

    def refresh(self, **kwargs):
        return self.call('POST', '/api/auth/refresh', **kwargs)

    def me(self, token):
        return self.call('GET', '/api/auth/me', token=token, session=False)


class LiveWebSessionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        env = {**os.environ, 'CORS_ORIGINS': ORIGIN}
        cls.process = subprocess.Popen([sys.executable, '-B', str(BACKEND_DIR / 'tests' / 'live_server.py')],
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env)
        line = cls.process.stdout.readline()
        if not line.startswith('READY'):
            cls.process.kill()
            raise RuntimeError(f'the live server did not start: {line!r}')
        cls.api = f'http://127.0.0.1:{line.split()[1]}'

    @classmethod
    def tearDownClass(cls):
        cls.process.kill()
        cls.process.wait()

    def post(self, path, body):
        request = urllib.request.Request(self.api + path, method='POST', data=json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read())

    def setUp(self):
        self.post('/__test__/clear-rate-limits', {})
        self.email = f'pilot-{uuid.uuid4().hex[:8]}@example.com'
        self.post('/__test__/account', {'email': self.email, 'password': PASSWORD})
        self.browser = Browser(self.api)

    def signed_in(self):
        answer = self.browser.sign_in(self.email)
        self.assertEqual(answer.status, 200, answer.body)
        return answer

    # -- the tests -----------------------------------------------------------------

    def test_a_preflight_from_the_web_lets_it_send_the_cookie_and_one_from_elsewhere_does_not(self):
        def preflight(origin):
            request = urllib.request.Request(self.api + '/api/auth/refresh', method='OPTIONS', headers={
                'Origin': origin, 'Access-Control-Request-Method': 'POST',
                'Access-Control-Request-Headers': 'content-type,x-ezpz-session'})
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.headers

        ours = preflight(ORIGIN)
        self.assertEqual(ours.get('Access-Control-Allow-Origin'), ORIGIN)
        self.assertEqual(ours.get('Access-Control-Allow-Credentials'), 'true')
        self.assertIn('x-ezpz-session', ours.get('Access-Control-Allow-Headers', '').lower())
        theirs = preflight('https://evil.example')
        self.assertIsNone(theirs.get('Access-Control-Allow-Origin'))

    def test_a_sign_in_sets_a_cookie_no_script_can_read_and_a_refresh_rotates_it(self):
        answer = self.signed_in()
        value, attributes = answer.cookie()
        self.assertNotIn('refresh_token', answer.body)
        for attribute in ('httponly', 'secure', 'samesite=none', 'partitioned', 'path=/api/auth'):
            self.assertIn(attribute, attributes)
        self.assertEqual(answer.headers.get('Access-Control-Allow-Credentials'), 'true')

        refreshed = self.browser.refresh()
        self.assertEqual(refreshed.status, 200, refreshed.body)
        self.assertNotIn('refresh_token', refreshed.body)
        self.assertEqual(refreshed.body['expires_in'], 900)
        self.assertNotEqual(self.browser.cookie, value)
        self.assertEqual(self.browser.me(refreshed.body['access_token']).status, 200)

    def test_a_page_that_reloads_is_still_signed_in_with_nothing_but_its_cookie(self):
        self.signed_in()
        for _ in range(3):
            answer = self.browser.refresh()                       # each reload starts with no token in memory
            self.assertEqual(answer.status, 200)
            self.post('/__test__/age-refresh', {'seconds': 60})  # an honest page never repeats a token

    def test_months_of_use_never_end_it_but_30_days_unused_does(self):
        self.signed_in()
        # Used every 25 days for 250 days: past the old 180-day limit, and each refresh slides the 30 days on.
        for _ in range(10):
            self.post('/__test__/age-sessions', {'days': 25})
            answer = self.browser.refresh()
            self.assertEqual(answer.status, 200, answer.body)
        access = answer.body['access_token']
        self.post('/__test__/age-sessions', {'days': 31})
        lapsed = self.browser.refresh()
        self.assertEqual(lapsed.status, 401)
        self.assertEqual(lapsed.body['code'], 'invalid_refresh_token')
        self.assertIsNone(self.browser.cookie)                    # told to forget it
        self.assertEqual(self.browser.me(access).status, 401)      # the session it belonged to is over

    def test_a_lost_answer_repeated_at_once_is_answered_again(self):
        self.signed_in()
        before = self.browser.cookie
        self.assertEqual(self.browser.refresh().status, 200)
        self.browser.cookie = before                              # the answer, and its cookie, never arrived
        again = self.browser.refresh()
        self.assertEqual(again.status, 200)
        self.post('/__test__/age-refresh', {'seconds': 60})
        self.assertEqual(self.browser.refresh().status, 200)

    def test_a_copied_cookie_after_the_grace_period_ends_the_session(self):
        self.signed_in()
        copied = self.browser.cookie
        fresh = self.browser.refresh()
        self.post('/__test__/age-refresh', {'seconds': 60})
        thief = Browser(self.api)
        thief.cookie = copied
        stolen = thief.refresh()
        self.assertEqual(stolen.status, 401)
        self.assertEqual(stolen.body['code'], 'refresh_reuse_detected')
        self.assertEqual(self.browser.refresh().status, 401)       # the owner's copy is ended with it
        self.assertEqual(self.browser.me(fresh.body['access_token']).status, 401)

    def test_another_site_cannot_spend_it_without_the_header(self):
        self.signed_in()
        held = self.browser.cookie
        forged = self.browser.refresh(session=False, headers={'Origin': 'https://evil.example'})
        self.assertEqual(forged.status, 401)
        self.assertEqual(self.browser.cookie, held)
        self.assertEqual(self.browser.refresh().status, 200)

    def test_signing_out_ends_it_at_once(self):
        token = self.signed_in().body['access_token']
        out = self.browser.call('POST', '/api/auth/logout', token=token)
        self.assertEqual(out.status, 200)
        self.assertIsNone(self.browser.cookie)
        self.assertEqual(self.browser.me(token).status, 401)

    def test_a_password_reset_ends_it(self):
        token = self.signed_in().body['access_token']
        Browser(self.api).call('POST', '/api/auth/forgot-password', {'email': self.email}, session=False)
        reset_token = json.loads(urllib.request.urlopen(
            f'{self.api}/__test__/email?kind=reset&to={self.email}', timeout=10).read())['secret']
        done = Browser(self.api).call('POST', '/api/auth/reset-password',
                                      {'token': reset_token, 'password': 'another secure flight password'}, session=False)
        self.assertEqual(done.status, 200, done.body)
        self.assertEqual(self.browser.me(token).status, 401)
        self.assertEqual(self.browser.refresh().status, 401)

    def test_a_native_app_signing_the_browser_out_ends_it(self):
        token = self.signed_in().body['access_token']
        phone = Browser(self.api).call('POST', '/api/auth/login', {'email': self.email, 'password': PASSWORD},
                                       headers={'X-EZPZ-Client': 'android/1.4.0 (212)'}, session=False)
        self.assertIn('refresh_token', phone.body)
        sessions = Browser(self.api).call('GET', '/api/auth/sessions', token=phone.body['access_token'], session=False)
        web = [s for s in sessions.body['sessions'] if s['client'] == 'web']
        self.assertEqual(len(web), 1)
        gone = Browser(self.api).call('DELETE', f'/api/auth/sessions/{web[0]["id"]}',
                                      token=phone.body['access_token'], session=False)
        self.assertEqual(gone.status, 200)
        self.assertEqual(self.browser.me(token).status, 401)
        self.assertEqual(self.browser.refresh().status, 401)


if __name__ == '__main__':
    unittest.main()
