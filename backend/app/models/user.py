from datetime import datetime

from app.extensions import db


class User(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    # Existing deployments originally required every user to have a Google
    # subject.  New password accounts use a ``local:<uuid>`` compatibility
    # subject, which keeps older databases (where this column is still NOT
    # NULL) working while allowing Google to be linked later.
    google_id = db.Column(db.String(100), unique=True, nullable=True)
    email = db.Column(db.String(120), unique=True, nullable=False)
    name = db.Column(db.String(120), nullable=False)
    picture = db.Column(db.String(500), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    # Admin access control. `role` gates the admin dashboard; `is_active` gates
    # sign-in for every auth method (Google + password); `features` holds
    # per-user entitlement overrides ({key: bool}; missing key => enabled).
    # See security/entitlements.py. Columns are added to existing databases by the
    # idempotent ALTERs in database/migrations.py.
    role = db.Column(db.String(20), nullable=False, default='user')
    is_active = db.Column(db.Boolean, nullable=False, default=True)
    features = db.Column(db.JSON, nullable=True)

    # Military affiliation. A user proves DoD affiliation by verifying control of
    # a .mil address (mil_verified_at set), or an admin approves them manually
    # (access_approved). New users default to unapproved; the ALTER grandfathers
    # everyone who existed before the gate was introduced. See security/entitlements.py.
    mil_email = db.Column(db.String(120), nullable=True)
    mil_verified_at = db.Column(db.DateTime, nullable=True)
    access_approved = db.Column(db.Boolean, nullable=False, default=False)

    # This creates a relationship so you can easily get all LZs for a user (e.g., user.saved_lzs)
    saved_lzs = db.relationship('SavedLZ', backref='author', lazy=True)
    saved_routes = db.relationship('SavedRoute', backref='author', lazy=True)
    local_credential = db.relationship(
        'LocalCredential',
        back_populates='user',
        cascade='all, delete-orphan',
        uselist=False,
    )
    account_tokens = db.relationship(
        'AccountToken',
        back_populates='user',
        cascade='all, delete-orphan',
        lazy=True,
    )


class LocalCredential(db.Model):
    """Password authentication state kept separate from Google identity data.

    A separate table lets existing Google-only databases adopt local accounts
    through ``db.create_all()`` without an unsafe in-place rebuild of ``user``.
    """

    __tablename__ = 'local_credential'

    user_id = db.Column(
        db.Integer,
        db.ForeignKey('user.id', ondelete='CASCADE'),
        primary_key=True,
    )
    password_hash = db.Column(db.String(255), nullable=False)
    email_verified_at = db.Column(db.DateTime, nullable=True)
    status = db.Column(db.String(32), nullable=False, default='pending_email')
    last_login_at = db.Column(db.DateTime, nullable=True)
    # Incrementing this value revokes every JWT issued for the password account
    # before a credential-sensitive event such as a password reset.
    session_version = db.Column(db.Integer, nullable=False, default=0)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    updated_at = db.Column(
        db.DateTime,
        default=datetime.utcnow,
        onupdate=datetime.utcnow,
        nullable=False,
    )

    user = db.relationship('User', back_populates='local_credential')


class AccountToken(db.Model):
    """Single-use, expiring email verification or password-reset token."""

    __tablename__ = 'account_token'

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(
        db.Integer,
        db.ForeignKey('user.id', ondelete='CASCADE'),
        nullable=False,
        index=True,
    )
    purpose = db.Column(db.String(32), nullable=False, index=True)
    token_hash = db.Column(db.String(64), unique=True, nullable=False, index=True)
    expires_at = db.Column(db.DateTime, nullable=False)
    used_at = db.Column(db.DateTime, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)

    user = db.relationship('User', back_populates='account_tokens')


class LoginEvent(db.Model):
    """A successful sign-in, for the admin per-user login history."""

    __tablename__ = 'login_event'

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(
        db.Integer,
        db.ForeignKey('user.id', ondelete='CASCADE'),
        nullable=False,
        index=True,
    )
    method = db.Column(db.String(20), nullable=False, default='password')  # google | password
    ip = db.Column(db.String(64), nullable=True)
    user_agent = db.Column(db.String(400), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False, index=True)
