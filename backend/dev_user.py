"""Explicit local-development account setup.

This is a CLI convenience, not an authentication bypass: the account signs in
through the normal endpoint and receives normal JWT and refresh tokens.  The
command refuses every production-marked process and every non-SQLite database
so a known development account cannot be created on a deployment.
"""

import os
import uuid
from datetime import datetime

import click
from flask import current_app
from werkzeug.security import generate_password_hash

from models import AccountToken, LocalCredential, User, db
from security_config import is_production


DEFAULT_EMAIL = "pilot@local.ezpz.test"
PASSWORD_MIN_LENGTH = 15
PASSWORD_MAX_LENGTH = 128


def create_dev_user(email, password, name="Local Pilot", environ=None):
    """Create or reset one approved password account in the local SQLite DB."""

    environ = os.environ if environ is None else environ
    if is_production(environ):
        raise ValueError("Development users cannot be created in production.")
    if db.engine.url.get_backend_name() != "sqlite":
        raise ValueError("Development users can be created only in a local SQLite database.")

    email = str(email or "").strip().casefold()
    name = str(name or "").strip()
    if not email or "@" not in email or len(email) > 120:
        raise ValueError("Enter a valid email address.")
    if not name or len(name) > 120:
        raise ValueError("Enter a name between 1 and 120 characters.")
    if not isinstance(password, str) or len(password) < PASSWORD_MIN_LENGTH or len(password) > PASSWORD_MAX_LENGTH:
        raise ValueError(f"Password must be {PASSWORD_MIN_LENGTH} to {PASSWORD_MAX_LENGTH} characters.")

    now = datetime.utcnow()
    user = User.query.filter(db.func.lower(User.email) == email).first()
    if user is None:
        user = User(
            google_id=f"local:{uuid.uuid4().hex}",
            email=email,
            name=name,
        )
        db.session.add(user)
        db.session.flush()
    else:
        user.name = name

    credential = user.local_credential
    if credential is None:
        credential = LocalCredential(user_id=user.id, password_hash="")
        user.local_credential = credential
    else:
        # Resetting this local password invalidates every token issued before it.
        credential.session_version = (credential.session_version or 0) + 1

    credential.password_hash = generate_password_hash(password, method="scrypt")
    credential.email_verified_at = now
    credential.status = "active"
    user.is_active = True
    user.access_approved = True
    AccountToken.query.filter_by(user_id=user.id).delete(synchronize_session=False)
    db.session.commit()
    return user


def register_dev_user_command(app):
    """Register the guarded command on the Flask application."""

    @app.cli.command("create-dev-user")
    @click.option("--email", default=DEFAULT_EMAIL, show_default=True)
    @click.option("--name", default="Local Pilot", show_default=True)
    @click.password_option(confirmation_prompt=True)
    def command(email, name, password):
        """Create or reset a verified account in the local SQLite database."""

        try:
            user = create_dev_user(email, password, name)
        except ValueError as error:
            raise click.ClickException(str(error)) from error
        click.echo(f"Local development account ready: {user.email}")
        click.echo("Sign in normally; this account is verified and approved.")

