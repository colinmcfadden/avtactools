"""A small app with real sign-in, for tests that need an authenticated client.

Wires the production revocation check (``token_revocation.is_revoked``), not a
copy of it, so tests of anything behind a token exercise the real thing. A test
module adds the blueprints it is about through ``extra_blueprints``.
"""

import sys
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

from flask import Flask
from flask_jwt_extended import JWTManager, decode_token

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_rate_limit import clear_rate_limits  # noqa: E402
from models import AccountToken, db  # noqa: E402
from routes.auth import auth_bp  # noqa: E402
from token_revocation import is_revoked  # noqa: E402

PASSWORD = 'a secure flight password'
ANDROID = {'X-EZPZ-Client': 'android/1.4.0 (212)'}
IOS = {'X-EZPZ-Client': 'ios/2.0.0 (9)'}


class NativeAuthCase(unittest.TestCase):
    extra_blueprints = ()

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
        for blueprint in self.extra_blueprints:
            self.app.register_blueprint(blueprint)
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
