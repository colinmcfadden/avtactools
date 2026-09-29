"""Startup checks for the JWT secret and email delivery, on every deployment.

Both were keyed to FLY_APP_NAME, so the self-hosted deployment behind a
Cloudflare tunnel skipped them: a missing JWT_SECRET_KEY there signed every
session with the public string 'dev-secret-change-me', letting anyone forge a
login. A bare local run must still start with no configuration at all.
"""

import sys
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from security_config import (  # noqa: E402
    is_production,
    resolve_jwt_secret,
    validate_email_configuration,
)

FLY = {'FLY_APP_NAME': 'ez-pz'}
CLOUDFLARE = {'TRUSTED_PROXY': 'cloudflare'}
EXPLICIT = {'APP_ENV': 'production'}
DEPLOYMENTS = {'fly': FLY, 'cloudflare': CLOUDFLARE, 'app_env': EXPLICIT}

STRONG_SECRET = 'x' * 32
EMAIL_OK = {
    'RESEND_API_KEY': 're_test',
    'EMAIL_FROM': 'EZ-PZ <security@accounts.example.com>',
}


class IsProductionTests(unittest.TestCase):
    def test_every_deployment_signal_counts(self):
        for name, environ in DEPLOYMENTS.items():
            self.assertTrue(is_production(environ), name)

    def test_any_declared_proxy_counts(self):
        """Declaring an edge at all means the app is deployed, even one this
        file has no client-IP header for."""
        self.assertTrue(is_production({'TRUSTED_PROXY': 'fly'}))
        self.assertTrue(is_production({'TRUSTED_PROXY': 'nginx'}))

    def test_app_env_is_case_and_space_insensitive(self):
        for value in ('production', 'PRODUCTION', ' Production ', 'prod'):
            self.assertTrue(is_production({'APP_ENV': value}), value)

    def test_a_bare_local_run_is_not_production(self):
        self.assertFalse(is_production({}))
        self.assertFalse(is_production({'APP_ENV': 'development'}))
        self.assertFalse(is_production({'TRUSTED_PROXY': '  ', 'FLY_APP_NAME': ''}))


class JwtSecretTests(unittest.TestCase):
    def test_fly_without_a_secret_refuses_to_start(self):
        with self.assertRaises(RuntimeError):
            resolve_jwt_secret(FLY)

    def test_cloudflare_without_a_secret_refuses_to_start(self):
        """The Coolify deployment — the case that used to fall back silently."""
        with self.assertRaises(RuntimeError):
            resolve_jwt_secret(CLOUDFLARE)

    def test_app_env_production_without_a_secret_refuses_to_start(self):
        with self.assertRaises(RuntimeError):
            resolve_jwt_secret(EXPLICIT)

    def test_a_short_secret_is_refused_on_every_deployment(self):
        for name, environ in DEPLOYMENTS.items():
            with self.assertRaises(RuntimeError, msg=name):
                resolve_jwt_secret({**environ, 'JWT_SECRET_KEY': 'x' * 31})

    def test_local_dev_without_a_secret_still_uses_the_fallback(self):
        self.assertEqual(resolve_jwt_secret({}), 'dev-secret-change-me')

    def test_local_dev_accepts_whatever_secret_it_is_given(self):
        self.assertEqual(resolve_jwt_secret({'JWT_SECRET_KEY': 'short'}), 'short')

    def test_a_32_character_secret_passes_everywhere(self):
        for name, environ in {**DEPLOYMENTS, 'local': {}}.items():
            self.assertEqual(
                resolve_jwt_secret({**environ, 'JWT_SECRET_KEY': STRONG_SECRET}),
                STRONG_SECRET,
                name,
            )


class EmailConfigurationTests(unittest.TestCase):
    def test_every_deployment_requires_a_resend_key(self):
        for name, environ in DEPLOYMENTS.items():
            with self.assertRaises(RuntimeError, msg=name):
                validate_email_configuration(
                    {**environ, 'EMAIL_FROM': EMAIL_OK['EMAIL_FROM']})

    def test_every_deployment_refuses_the_resend_test_sender(self):
        for name, environ in DEPLOYMENTS.items():
            with self.assertRaises(RuntimeError, msg=name):
                validate_email_configuration({
                    **environ,
                    'RESEND_API_KEY': 're_test',
                    'EMAIL_FROM': 'EZ-PZ <onboarding@resend.dev>',
                })

    def test_a_verified_sender_passes_everywhere(self):
        for name, environ in DEPLOYMENTS.items():
            self.assertIsNone(
                validate_email_configuration({**environ, **EMAIL_OK}), name)

    def test_local_dev_needs_no_email_configuration(self):
        self.assertIsNone(validate_email_configuration({}))


if __name__ == '__main__':
    unittest.main()
