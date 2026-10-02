"""Application settings, read from the environment when the app is created."""

import os
from datetime import timedelta

from app.database.url import database_uri, is_postgres
from app.paths import BACKEND_DIR
from app.security.config import (
    resolve_jwt_secret,
    session_cookie_secure,
    validate_email_configuration,
)

DEFAULT_CORS_ORIGINS = "http://localhost:3000"


def cors_origins(environ=os.environ):
    """Origins allowed to call /api/*: ``CORS_ORIGINS``, comma-separated."""
    return [
        origin.strip()
        for origin in environ.get("CORS_ORIGINS", DEFAULT_CORS_ORIGINS).split(",")
        if origin.strip()
    ]


def load_config(environ=os.environ):
    """Flask settings built from ``environ``.

    Raises RuntimeError in production when the JWT secret or the email
    settings are missing (see app/security/config.py), so a misconfigured
    deployment refuses to start rather than run with a public default.
    """
    # Use a managed database when DATABASE_URL is set (e.g. Supabase Postgres in
    # production); fall back to a local SQLite file for development. See
    # app/database/url.py for why the Postgres driver is named explicitly.
    database_url = database_uri(environ, 'sqlite:///' + os.path.join(str(BACKEND_DIR), 'ezpz.db'))
    config = {
        'SQLALCHEMY_DATABASE_URI': database_url,
        'SQLALCHEMY_TRACK_MODIFICATIONS': False,
    }

    # Managed Postgres (Neon/Supabase) and the Fly VM both drop idle connections —
    # Neon autosuspends its compute after a few minutes. Without validation, the
    # pool hands out a dead socket on the next request and the query raises
    # OperationalError ("server closed the connection unexpectedly"), which surfaces
    # to the browser as an intermittent 500 on the first call after an idle period
    # (typically GET /auth/me on page load). pool_pre_ping runs a cheap liveness
    # check and transparently reconnects; pool_recycle proactively retires
    # connections before the server's own idle timeout can. Only meaningful for a
    # real connection pool, so scope it to Postgres and leave SQLite dev untouched.
    if is_postgres(database_url):
        config['SQLALCHEMY_ENGINE_OPTIONS'] = {
            'pool_pre_ping': True,
            'pool_recycle': 280,
        }
    config['JWT_SECRET_KEY'] = resolve_jwt_secret(environ)
    validate_email_configuration(environ)
    # A one-day session balances an operational planning workflow with reasonable
    # exposure if a bearer token is lost. Password resets revoke older tokens via
    # the per-account session-version claim below.
    config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=24)

    # Server-side session for the admin dashboard (separate from the SPA's JWT).
    # Falls back to the JWT secret so a single strong secret is enough to configure.
    config['SECRET_KEY'] = environ.get('ADMIN_SESSION_SECRET') or config['JWT_SECRET_KEY']
    config.update(
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE='Lax',
        # Secure wherever an HTTPS edge is declared, not only on Fly — this was
        # keyed to FLY_APP_NAME, so moving the app anywhere else silently dropped
        # the flag and sent admin session cookies in the clear.
        SESSION_COOKIE_SECURE=session_cookie_secure(environ),
    )
    return config
