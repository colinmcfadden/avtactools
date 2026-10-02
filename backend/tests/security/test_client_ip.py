"""Whose address the app believes, and when the session cookie is Secure.

Both were keyed to FLY_APP_NAME, which meant moving the app off Fly silently
changed two security properties at once: admin session cookies lost the Secure
flag, and every caller collapsed into a single rate-limit bucket because the
only address visible behind a tunnel is the tunnel's own.
"""

import unittest

from app.security.config import (
    resolve_client_ip,
    session_cookie_secure,
    trusted_proxy_header,
)


class FakeRequest:
    def __init__(self, remote_addr='10.0.0.1', headers=None):
        self.remote_addr = remote_addr
        self.headers = headers or {}


class TrustedProxyTests(unittest.TestCase):
    def test_fly_needs_no_configuration(self):
        self.assertEqual(trusted_proxy_header({'FLY_APP_NAME': 'x'}), 'Fly-Client-IP')

    def test_cloudflare_is_named_explicitly(self):
        self.assertEqual(
            trusted_proxy_header({'TRUSTED_PROXY': 'cloudflare'}), 'CF-Connecting-IP')

    def test_nothing_is_trusted_by_default(self):
        """A bare run must not believe any header — anyone can send one."""
        self.assertIsNone(trusted_proxy_header({}))

    def test_an_unknown_proxy_name_trusts_nothing(self):
        self.assertIsNone(trusted_proxy_header({'TRUSTED_PROXY': 'nginx'}))

    def test_the_name_is_case_and_space_insensitive(self):
        self.assertEqual(
            trusted_proxy_header({'TRUSTED_PROXY': '  CloudFlare '}), 'CF-Connecting-IP')

    def test_an_explicit_proxy_overrides_fly_detection(self):
        header = trusted_proxy_header(
            {'FLY_APP_NAME': 'x', 'TRUSTED_PROXY': 'cloudflare'})
        self.assertEqual(header, 'CF-Connecting-IP')


class ClientIpTests(unittest.TestCase):
    def test_the_declared_edges_header_is_used(self):
        request = FakeRequest(headers={'CF-Connecting-IP': '203.0.113.7'})
        self.assertEqual(
            resolve_client_ip(request, {'TRUSTED_PROXY': 'cloudflare'}), '203.0.113.7')

    def test_without_a_declared_edge_the_header_is_ignored(self):
        """Otherwise any caller could pick their own rate-limit bucket."""
        request = FakeRequest(headers={'CF-Connecting-IP': '203.0.113.7'})
        self.assertEqual(resolve_client_ip(request, {}), '10.0.0.1')

    def test_the_wrong_edges_header_is_ignored(self):
        """Behind Cloudflare, a Fly header is just caller-supplied text."""
        request = FakeRequest(headers={'Fly-Client-IP': '203.0.113.7'})
        self.assertEqual(
            resolve_client_ip(request, {'TRUSTED_PROXY': 'cloudflare'}), '10.0.0.1')

    def test_a_forged_value_falls_back_to_the_peer(self):
        # Unparseable text would otherwise become a rate-limit key of its own.
        request = FakeRequest(headers={'CF-Connecting-IP': 'not-an-address'})
        self.assertEqual(
            resolve_client_ip(request, {'TRUSTED_PROXY': 'cloudflare'}), '10.0.0.1')

    def test_a_missing_header_falls_back_to_the_peer(self):
        self.assertEqual(
            resolve_client_ip(FakeRequest(), {'TRUSTED_PROXY': 'cloudflare'}), '10.0.0.1')

    def test_ipv6_is_normalised(self):
        request = FakeRequest(headers={'CF-Connecting-IP': '2001:0DB8::0001'})
        self.assertEqual(
            resolve_client_ip(request, {'TRUSTED_PROXY': 'cloudflare'}), '2001:db8::1')

    def test_a_peerless_request_is_never_none(self):
        """The value becomes a rate-limit key, so it has to be a string."""
        self.assertEqual(resolve_client_ip(FakeRequest(remote_addr=None), {}), 'unknown')

    def test_fly_keeps_working_untouched(self):
        request = FakeRequest(headers={'Fly-Client-IP': '198.51.100.4'})
        self.assertEqual(
            resolve_client_ip(request, {'FLY_APP_NAME': 'x'}), '198.51.100.4')


class SessionCookieSecureTests(unittest.TestCase):
    def test_secure_behind_cloudflare(self):
        self.assertTrue(session_cookie_secure({'TRUSTED_PROXY': 'cloudflare'}))

    def test_secure_on_fly_as_before(self):
        self.assertTrue(session_cookie_secure({'FLY_APP_NAME': 'x'}))

    def test_not_secure_for_a_bare_local_run(self):
        """A Secure cookie is never sent over http://localhost, so admin
        sign-in would break during local development."""
        self.assertFalse(session_cookie_secure({}))

    def test_an_explicit_setting_wins_over_detection(self):
        self.assertFalse(
            session_cookie_secure({'FLY_APP_NAME': 'x', 'SESSION_COOKIE_SECURE': 'false'}))
        self.assertTrue(session_cookie_secure({'SESSION_COOKIE_SECURE': 'true'}))

    def test_common_truthy_spellings_are_accepted(self):
        for value in ('1', 'true', 'TRUE', 'yes', 'on'):
            self.assertTrue(session_cookie_secure({'SESSION_COOKIE_SECURE': value}), value)
        for value in ('0', 'false', 'no', 'off'):
            self.assertFalse(session_cookie_secure({'SESSION_COOKIE_SECURE': value}), value)

    def test_an_unrecognised_value_falls_back_to_detection(self):
        self.assertTrue(
            session_cookie_secure({'FLY_APP_NAME': 'x', 'SESSION_COOKIE_SECURE': 'maybe'}))


if __name__ == '__main__':
    unittest.main()
