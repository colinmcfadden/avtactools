"""Focused tests for password accounts, one-time tokens, and Google linking."""

import sys
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

from flask import Flask
from flask_jwt_extended import JWTManager


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_rate_limit import clear_rate_limits  # noqa: E402
from models import AccountToken, User, db  # noqa: E402
from routes.auth import auth_bp  # noqa: E402
from security_config import resolve_jwt_secret, validate_email_configuration  # noqa: E402


class SecurityConfigTests(unittest.TestCase):
    def test_fly_requires_a_32_character_jwt_secret(self):
        with self.assertRaises(RuntimeError):
            resolve_jwt_secret({'FLY_APP_NAME': 'ez-pz'})
        with self.assertRaises(RuntimeError):
            resolve_jwt_secret({
                'FLY_APP_NAME': 'ez-pz',
                'JWT_SECRET_KEY': 'too-short',
            })

        secret = 'x' * 32
        self.assertEqual(resolve_jwt_secret({
            'FLY_APP_NAME': 'ez-pz',
            'JWT_SECRET_KEY': secret,
        }), secret)

    def test_fly_requires_production_email_delivery_configuration(self):
        with self.assertRaises(RuntimeError):
            validate_email_configuration({'FLY_APP_NAME': 'ez-pz'})
        with self.assertRaises(RuntimeError):
            validate_email_configuration({
                'FLY_APP_NAME': 'ez-pz',
                'RESEND_API_KEY': 're_test',
                'EMAIL_FROM': 'EZ-PZ <onboarding@resend.dev>',
            })

        self.assertIsNone(validate_email_configuration({
            'FLY_APP_NAME': 'ez-pz',
            'RESEND_API_KEY': 're_test',
            'EMAIL_FROM': 'EZ-PZ <security@accounts.example.com>',
        }))
        self.assertIsNone(validate_email_configuration({}))


class AuthFlowTests(unittest.TestCase):
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

        @jwt.token_in_blocklist_loader
        def token_is_revoked(_header, payload):
            user = db.session.get(User, int(payload['sub']))
            if user is None or 'sv' not in payload:
                return True
            credential = user.local_credential
            expected = credential.session_version if credential else 0
            return payload['sv'] != expected

        self.app.register_blueprint(auth_bp)
        with self.app.app_context():
            db.create_all()

        self.verification_tokens = []
        self.reset_tokens = []
        self.patchers = [
            patch(
                'routes.auth.send_verification_email',
                side_effect=lambda _user, token: self.verification_tokens.append(token) or True,
            ),
            patch(
                'routes.auth.send_password_reset_email',
                side_effect=lambda _user, token: self.reset_tokens.append(token) or True,
            ),
            patch('routes.auth.send_welcome_email', return_value=True),
            patch('routes.auth.send_password_changed_email', return_value=True),
            patch('routes.auth.send_new_account_notification', return_value=True),
        ]
        for patcher in self.patchers:
            patcher.start()
        self.client = self.app.test_client()

    def tearDown(self):
        for patcher in reversed(self.patchers):
            patcher.stop()
        with self.app.app_context():
            db.session.remove()
            db.drop_all()

    def _register(
        self,
        email='pilot@example.com',
        password='a secure flight password',
        name='Test Pilot',
    ):
        return self.client.post('/api/auth/register', json={
            'name': name,
            'email': email,
            'password': password,
        })

    def _verify_and_login(self, password='a secure flight password'):
        self.assertEqual(self._register().status_code, 202)
        raw_token = self.verification_tokens[-1]
        verified = self.client.post(
            '/api/auth/verify-email', json={
                'token': raw_token,
                'password': password,
            }
        )
        self.assertEqual(verified.status_code, 200)
        login = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': password,
        })
        self.assertEqual(login.status_code, 200)
        return login.get_json()['access_token'], raw_token

    def test_registration_requires_one_time_email_verification(self):
        response = self._register()
        self.assertEqual(response.status_code, 202)
        self.assertEqual(len(self.verification_tokens), 1)
        raw_token = self.verification_tokens[0]
        self.assertNotIn(raw_token, response.get_data(as_text=True))

        before_verify = self.client.post('/api/auth/login', json={
            'email': 'PILOT@example.com',
            'password': 'a secure flight password',
        })
        self.assertEqual(before_verify.status_code, 401)
        self.assertEqual(before_verify.get_json()['code'], 'invalid_credentials')

        with self.app.app_context():
            user = User.query.one()
            self.assertTrue(user.google_id.startswith('local:'))
            self.assertNotEqual(
                user.local_credential.password_hash,
                'a secure flight password',
            )
            stored_token = AccountToken.query.one()
            self.assertNotEqual(stored_token.token_hash, raw_token)

        missing_password = self.client.post(
            '/api/auth/verify-email', json={'token': raw_token}
        )
        self.assertEqual(missing_password.status_code, 400)
        self.assertIn('Password is required', missing_password.get_json()['message'])

        verified = self.client.post(
            '/api/auth/verify-email', json={
                'token': raw_token,
                'password': 'a secure flight password',
            }
        )
        self.assertEqual(verified.status_code, 200)
        reused = self.client.post(
            '/api/auth/verify-email', json={
                'token': raw_token,
                'password': 'a secure flight password',
            }
        )
        self.assertEqual(reused.status_code, 400)

        logged_in = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': 'a secure flight password',
        })
        self.assertEqual(logged_in.status_code, 200)
        jwt = logged_in.get_json()['access_token']
        me = self.client.get(
            '/api/auth/me', headers={'Authorization': f'Bearer {jwt}'}
        )
        self.assertEqual(me.status_code, 200)
        self.assertTrue(me.get_json()['email_verified'])

    def test_password_reset_is_single_use_and_revokes_older_jwt(self):
        old_jwt, _ = self._verify_and_login()
        forgot = self.client.post(
            '/api/auth/forgot-password', json={'email': 'pilot@example.com'}
        )
        self.assertEqual(forgot.status_code, 202)
        reset_token = self.reset_tokens[-1]

        reset = self.client.post('/api/auth/reset-password', json={
            'token': reset_token,
            'password': 'a different secure password',
        })
        self.assertEqual(reset.status_code, 200)
        reuse = self.client.post('/api/auth/reset-password', json={
            'token': reset_token,
            'password': 'yet another secure password',
        })
        self.assertEqual(reuse.status_code, 400)

        old_session = self.client.get(
            '/api/auth/me', headers={'Authorization': f'Bearer {old_jwt}'}
        )
        self.assertEqual(old_session.status_code, 401)
        old_password = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': 'a secure flight password',
        })
        self.assertEqual(old_password.status_code, 401)
        new_password = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': 'a different secure password',
        })
        self.assertEqual(new_password.status_code, 200)

    def test_google_login_discards_unverified_preregistered_password(self):
        self.assertEqual(self._register(email='victim@example.com').status_code, 202)
        claims = {
            'sub': 'verified-google-subject',
            'email': 'victim@example.com',
            'email_verified': True,
            'name': 'Actual Owner',
        }
        with (
            patch('routes.auth.id_token.verify_oauth2_token', return_value=claims),
            patch('routes.auth.send_welcome_email', return_value=True) as welcome,
            patch(
                'routes.auth.send_new_account_notification', return_value=True
            ) as notify_admin,
        ):
            google = self.client.post(
                '/api/auth/google', json={'token': 'google-credential'}
            )
        self.assertEqual(google.status_code, 200)
        self.assertFalse(google.get_json()['user']['has_password'])
        welcome.assert_called_once()
        notify_admin.assert_called_once()

        with self.app.app_context():
            self.assertEqual(User.query.count(), 1)
            user = User.query.one()
            self.assertEqual(user.google_id, 'verified-google-subject')
            self.assertEqual(user.name, 'Actual Owner')
            self.assertIsNone(user.local_credential)

        attacker_password = self.client.post('/api/auth/login', json={
            'email': 'victim@example.com',
            'password': 'a secure flight password',
        })
        self.assertEqual(attacker_password.status_code, 401)

    def test_new_google_user_gets_welcome_and_admin_notification(self):
        claims = {
            'sub': 'brand-new-google-subject',
            'email': 'new-google@example.com',
            'email_verified': True,
            'name': 'New Google User',
        }
        with (
            patch('routes.auth.id_token.verify_oauth2_token', return_value=claims),
            patch('routes.auth.send_welcome_email', return_value=True) as welcome,
            patch(
                'routes.auth.send_new_account_notification', return_value=True
            ) as notify_admin,
        ):
            response = self.client.post(
                '/api/auth/google', json={'token': 'google-credential'}
            )

        self.assertEqual(response.status_code, 200)
        welcome.assert_called_once()
        notify_admin.assert_called_once()

    def test_public_registration_cannot_attach_password_to_google_user(self):
        with self.app.app_context():
            db.session.add(User(
                google_id='existing-google-subject',
                email='google@example.com',
                name='Google User',
            ))
            db.session.commit()

        response = self._register(email='google@example.com')
        self.assertEqual(response.status_code, 202)
        self.assertEqual(self.verification_tokens, [])
        with self.app.app_context():
            self.assertIsNone(User.query.one().local_credential)

    def test_duplicate_pending_registration_rotates_token_not_password(self):
        first = self._register(password='an attacker supplied password')
        self.assertEqual(first.status_code, 202)
        original_token = self.verification_tokens[-1]
        duplicate = self._register(
            password='a different secure password',
            name='Updated Pilot Name',
        )
        self.assertEqual(duplicate.status_code, 202)
        self.assertEqual(len(self.verification_tokens), 2)
        replacement_token = self.verification_tokens[-1]
        self.assertNotEqual(original_token, replacement_token)
        with self.app.app_context():
            self.assertEqual(User.query.one().name, 'Updated Pilot Name')

        expired_original = self.client.post(
            '/api/auth/verify-email', json={
                'token': original_token,
                'password': 'an attacker supplied password',
            }
        )
        self.assertEqual(expired_original.status_code, 400)
        verified = self.client.post('/api/auth/verify-email', json={
            'token': replacement_token,
            'password': 'password chosen after email proof',
        })
        self.assertEqual(verified.status_code, 200)
        preregistration_password = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': 'an attacker supplied password',
        })
        self.assertEqual(preregistration_password.status_code, 401)
        verified_password = self.client.post('/api/auth/login', json={
            'email': 'pilot@example.com',
            'password': 'password chosen after email proof',
        })
        self.assertEqual(verified_password.status_code, 200)

    def test_login_rate_limit_returns_retry_after(self):
        for _ in range(10):
            response = self.client.post('/api/auth/login', json={
                'email': 'unknown@example.com',
                'password': 'a secure flight password',
            })
            self.assertEqual(response.status_code, 401)
        blocked = self.client.post('/api/auth/login', json={
            'email': 'unknown@example.com',
            'password': 'a secure flight password',
        })
        self.assertEqual(blocked.status_code, 429)
        self.assertIn('Retry-After', blocked.headers)

    def test_unknown_and_google_only_logins_run_dummy_password_check(self):
        with patch('routes.auth.check_password_hash', return_value=False) as check:
            unknown = self.client.post('/api/auth/login', json={
                'email': 'unknown@example.com',
                'password': 'a secure flight password',
            })
        self.assertEqual(unknown.status_code, 401)
        check.assert_called_once()

        with self.app.app_context():
            db.session.add(User(
                google_id='google-only-subject',
                email='google-only@example.com',
                name='Google Only',
            ))
            db.session.commit()
        with patch('routes.auth.check_password_hash', return_value=False) as check:
            google_only = self.client.post('/api/auth/login', json={
                'email': 'google-only@example.com',
                'password': 'a secure flight password',
            })
        self.assertEqual(google_only.status_code, 401)
        check.assert_called_once()

    def test_google_login_is_rate_limited_per_client_ip(self):
        for _ in range(30):
            response = self.client.post('/api/auth/google', json={})
            self.assertEqual(response.status_code, 503)
        blocked = self.client.post('/api/auth/google', json={})
        self.assertEqual(blocked.status_code, 429)
        self.assertIn('Retry-After', blocked.headers)

    def test_common_long_password_is_rejected(self):
        self.assertEqual(self._register().status_code, 202)
        response = self.client.post('/api/auth/verify-email', json={
            'token': self.verification_tokens[-1],
            'password': 'passwordpassword',
        })
        self.assertEqual(response.status_code, 400)
        self.assertIn('less common', response.get_json()['message'])


if __name__ == '__main__':
    unittest.main()
