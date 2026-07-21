from flask_sqlalchemy import SQLAlchemy
from datetime import datetime

db = SQLAlchemy()


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


class SavedRoute(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # "sketch" = geometry JSON only; "mission" = also carries the full .msnx
    # bytes (serialized from the edited in-memory state at save time).
    kind = db.Column(db.String(20), nullable=False, default='sketch')
    route_data = db.Column(db.JSON, nullable=False)
    msnx_file = db.Column(db.LargeBinary, nullable=True)
    file_name = db.Column(db.String(255), nullable=True)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SavedPointSet(db.Model):
    """A named set of local points (imported from an .LPS file) for map display."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # [{name, description, group, icon, elevationM, lat, lon}, ...]
    points_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SavedLZ(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    lz_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
