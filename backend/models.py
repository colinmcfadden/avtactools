from flask_sqlalchemy import SQLAlchemy
from sqlalchemy.orm import declared_attr
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

    # Admin access control. `role` gates the admin dashboard; `is_active` gates
    # sign-in for every auth method (Google + password); `features` holds
    # per-user entitlement overrides ({key: bool}; missing key => enabled).
    # See entitlements.py. Columns are added to existing databases by the
    # idempotent ALTERs in app.py.
    role = db.Column(db.String(20), nullable=False, default='user')
    is_active = db.Column(db.Boolean, nullable=False, default=True)
    features = db.Column(db.JSON, nullable=True)

    # Military affiliation. A user proves DoD affiliation by verifying control of
    # a .mil address (mil_verified_at set), or an admin approves them manually
    # (access_approved). New users default to unapproved; the ALTER grandfathers
    # everyone who existed before the gate was introduced. See entitlements.py.
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

    # Refresh tokens only (purpose 'refresh'; see refresh_tokens.py). Every token
    # a device is issued, one after another as it refreshes, shares a `family` --
    # that chain is one signed-in device. `client` names the app for the device
    # list ("android/1.4.0 (212)"), and `session_version` is the credential's at
    # issue, so a password reset ends every family. NULL for every other purpose.
    family = db.Column(db.String(36), nullable=True, index=True)
    client = db.Column(db.String(80), nullable=True)
    session_version = db.Column(db.Integer, nullable=True)

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
    # Which app signed in, from the X-EZPZ-Client header, e.g. "android/1.4.0 (212)".
    # NULL for the web app today and for anything that sent no (or a malformed) header.
    client = db.Column(db.String(80), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False, index=True)


class SyncMixin:
    """What a saved record needs to be kept in step across devices.

    The web app, the Android app and the iOS app each keep a copy and edit it
    offline, so a record needs an identity that does not depend on the server
    (``client_uuid``), a version to detect edits made on top of a stale copy
    (``revision``), a tombstone so a deletion can reach devices that were offline
    (``deleted_at``), and a place in one per-user change order (``change_seq``)
    that a device can ask "everything after" about. See sync_support.py.

    Added to existing databases by ``schema_sync``; ``app.py`` adds the unique
    index. ``revision`` defaults to 1, which backfills the rows that predate it.
    """

    client_uuid = db.Column(db.String(36), nullable=True)
    revision = db.Column(db.Integer, nullable=False, default=1)
    deleted_at = db.Column(db.DateTime, nullable=True)
    change_seq = db.Column(db.Integer, nullable=True, index=True)
    # The Idempotency-Key of the last write, so a retry of it after a lost
    # response is recognised as the same write rather than as a conflict with it.
    last_idem_key = db.Column(db.String(64), nullable=True)

    @staticmethod
    def sync_indexes(cls):
        """One record per client identity per user: a retried create cannot duplicate.

        A model with indexes of its own lists these in its ``__table_args__`` too.
        """
        return (db.Index(f'ux_{cls.__tablename__}_user_client_uuid', 'user_id', 'client_uuid', unique=True),)

    @declared_attr
    def __table_args__(cls):  # noqa: N805 — SQLAlchemy's declared_attr convention
        return SyncMixin.sync_indexes(cls)


class AircraftProfile(SyncMixin, db.Model):
    """An airframe's planning defaults, footprint, and AMPS binding.

    Two flavours share this table:

    * ``user_id IS NULL`` — a *master* profile from the admin-managed list.
      Visible to everyone; only an admin can edit one.
    * ``user_id`` set — a *custom* profile the user built for themselves when
      the master list didn't cover their airframe. Private to that user and
      gated by the ``aircraft_profiles`` entitlement.

    Geometry drives the map footprint and separation alerts; the performance
    block seeds route planning; ``vidx_file`` (when present) lets MSNX export
    ship the real AMPS vehicle installation instead of the template's UH-60L.
    """

    __tablename__ = 'aircraft_profile'

    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(
        db.Integer,
        db.ForeignKey('user.id', ondelete='CASCADE'),
        nullable=True,
        index=True,
    )
    # Stable identifier for master profiles so re-seeding updates rather than
    # duplicates, and so a saved map can reference a profile across databases.
    slug = db.Column(db.String(60), nullable=False, index=True)
    name = db.Column(db.String(120), nullable=False)
    designation = db.Column(db.String(40), nullable=False)
    # Chooses the map sprite (see frontend aircraftIcons.js); unknown keys fall
    # back to a generic rotary-wing silhouette rather than rendering nothing.
    icon_key = db.Column(db.String(40), nullable=False, default='generic')

    # --- Footprint / separation -------------------------------------------
    # Rotor diameter is the aircraft's turning footprint. For tandem-rotor
    # airframes it's the overall rotor span, which is what spacing keys off.
    rotor_diameter_m = db.Column(db.Float, nullable=False, default=16.357)
    # Required clear space between two rotor-tip paths (not between centers).
    # Admin-editable per platform; center spacing is derived, never stored.
    rotor_tip_clearance_m = db.Column(db.Float, nullable=False, default=60.0)

    # --- Route planning defaults ------------------------------------------
    default_airspeed_kts = db.Column(db.Float, nullable=False, default=100)
    default_airspeed_type = db.Column(db.String(20), nullable=False, default='ground')
    max_indicated_kts = db.Column(db.Float, nullable=False, default=193)
    default_altitude_ft = db.Column(db.Float, nullable=False, default=50)
    default_altitude_ref = db.Column(db.String(10), nullable=False, default='agl')
    min_altitude_ft_msl = db.Column(db.Float, nullable=False, default=-2000)
    max_altitude_ft_msl = db.Column(db.Float, nullable=False, default=20000)
    default_fuel_flow_lb_hr = db.Column(db.Float, nullable=False, default=960)
    default_gross_weight_lb = db.Column(db.Float, nullable=False, default=16000)
    # Where the performance block came from, so nobody plans fuel off a number
    # that was never validated:
    #   vidx      — extracted from a real AMPS vehicle installation
    #   published — public spec figures, seeded as a starting point only
    #   custom    — entered by hand in the admin or by a user
    perf_source = db.Column(db.String(20), nullable=False, default='custom')

    # --- AMPS binding ------------------------------------------------------
    # The <vehicledescription> string AMPS writes into mission/vehicles.xml,
    # e.g. "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L". Used to recognise
    # the airframe on import and to rewrite vehicles.xml when a vidx is present.
    amps_vehicle_description = db.Column(db.String(200), nullable=True)
    # Real AMPS bytes that let export produce this airframe instead of the
    # template's UH-60L. Two accepted shapes, both zips:
    #
    #   msnx — a whole mission saved out of AMPS for this airframe. Preferred:
    #          the package is internally consistent (vidx, FileInfo, .rels,
    #          vehicles.xml all agree) because AMPS wrote it, so export just
    #          uses it as the base template.
    #   vidx — a bare vehicle installation. Export transplants it into the
    #          default template, rewriting the OPC part names and
    #          vehicledescription. Works, but more moving parts.
    #
    # With neither, export keeps the UH-60L installation and warns — an
    # airframe can't be faked without files that came from AMPS.
    template_file = db.Column(db.LargeBinary, nullable=True)
    template_name = db.Column(db.String(120), nullable=True)
    template_kind = db.Column(db.String(10), nullable=True)  # 'msnx' | 'vidx'

    is_active = db.Column(db.Boolean, nullable=False, default=True)
    sort_order = db.Column(db.Integer, nullable=False, default=100)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    @declared_attr
    def __table_args__(cls):  # noqa: N805 — SQLAlchemy's declared_attr convention
        return (db.Index('ix_aircraft_profile_owner_slug', 'user_id', 'slug'), *SyncMixin.sync_indexes(cls))

    @property
    def is_system(self):
        return self.user_id is None

    @property
    def has_template(self):
        return self.template_file is not None

    def to_dict(self):
        """Client-facing shape. Never includes the vidx bytes — only a flag.

        A user's own profile also says which copy it is (``client_uuid``,
        ``revision``) so a device can sync it; the master list is the admin's and
        does not sync, so it carries neither.
        """
        body = self._shape()
        if self.user_id is not None:
            body["client_uuid"] = self.client_uuid
            body["revision"] = self.revision
        return body

    def _shape(self):
        return {
            "id": self.id,
            "slug": self.slug,
            "name": self.name,
            "designation": self.designation,
            "icon_key": self.icon_key or 'generic',
            "is_system": self.is_system,
            "rotor_diameter_m": self.rotor_diameter_m,
            "rotor_tip_clearance_m": self.rotor_tip_clearance_m,
            "default_airspeed_kts": self.default_airspeed_kts,
            "default_airspeed_type": self.default_airspeed_type,
            "max_indicated_kts": self.max_indicated_kts,
            "default_altitude_ft": self.default_altitude_ft,
            "default_altitude_ref": self.default_altitude_ref,
            "min_altitude_ft_msl": self.min_altitude_ft_msl,
            "max_altitude_ft_msl": self.max_altitude_ft_msl,
            "default_fuel_flow_lb_hr": self.default_fuel_flow_lb_hr,
            "default_gross_weight_lb": self.default_gross_weight_lb,
            "perf_source": self.perf_source,
            "amps_vehicle_description": self.amps_vehicle_description,
            "has_template": self.has_template,
            "template_kind": self.template_kind,
            "template_name": self.template_name,
            "sort_order": self.sort_order,
        }


class SyncCounter(db.Model):
    """Per-user change counter: the order every saved record's changes happened in.

    Taking the next number locks the user's row until the write commits, so a
    lower number can never commit after a higher one. That is what lets a device
    keep a cursor and trust it has seen everything up to it.
    """

    __tablename__ = 'sync_counter'

    user_id = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='CASCADE'), primary_key=True)
    seq = db.Column(db.Integer, nullable=False, default=0)


class SavedRoute(SyncMixin, db.Model):
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


class SavedPointSet(SyncMixin, db.Model):
    """A named set of local points (imported from an .LPS file) for map display."""
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    # [{name, description, group, icon, elevationM, lat, lon}, ...]
    points_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class SavedLZ(SyncMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)

    lz_data = db.Column(db.JSON, nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


# -- Mission packs -------------------------------------------------------------
#
# A pack is a shared container of LZs, route sets and point sets that every member
# edits through a server-ordered stream of small operations (pack_ops.py). It is
# stored apart from the one-off library above, so nothing here touches SavedLZ,
# SavedRoute, SavedPointSet or the per-user sync feed. Every table is new and made
# by db.create_all(). See docs/MISSION_PACKS.md and pack_support.py.


class Team(db.Model):
    """A unit or section ("B Co 2-10 AVN"). People find each other by name only through a shared team."""

    __tablename__ = 'team'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(100), nullable=False)
    created_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)


class TeamMember(db.Model):
    __tablename__ = 'team_member'

    id = db.Column(db.Integer, primary_key=True)
    team_id = db.Column(db.Integer, db.ForeignKey('team.id', ondelete='CASCADE'), nullable=False, index=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='CASCADE'), nullable=False, index=True)
    role = db.Column(db.String(10), nullable=False, default='member')  # owner | admin | member
    joined_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)

    __table_args__ = (db.UniqueConstraint('team_id', 'user_id', name='ux_team_member'),)


class MissionPack(db.Model):
    """The pack itself. ``head_seq`` is its own change counter: every event takes the next number under a row lock."""

    __tablename__ = 'mission_pack'

    id = db.Column(db.Integer, primary_key=True)
    uuid = db.Column(db.String(36), unique=True, nullable=False, index=True)
    # Always set: deleting the owner's account hands the pack on, or deletes it
    # when nobody else is in it (pack_support.release_account).
    owner_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False, index=True)
    # Shared with a whole team: its members see the pack with ``team_role``.
    team_id = db.Column(db.Integer, db.ForeignKey('team.id', ondelete='SET NULL'), nullable=True, index=True)
    team_role = db.Column(db.String(10), nullable=False, default='editor')  # editor | viewer
    name = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text, nullable=False, default='')
    # finished: the server refuses every edit, the owner's included, until the owner reopens it.
    status = db.Column(db.String(10), nullable=False, default='active')  # active | finished
    finished_at = db.Column(db.DateTime, nullable=True)
    finished_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    head_seq = db.Column(db.Integer, nullable=False, default=0)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)
    # A tombstone, content gone, so a device that asks about it learns it was deleted.
    deleted_at = db.Column(db.DateTime, nullable=True)


class MissionPackMember(db.Model):
    __tablename__ = 'mission_pack_member'

    id = db.Column(db.Integer, primary_key=True)
    pack_id = db.Column(db.Integer, db.ForeignKey('mission_pack.id', ondelete='CASCADE'), nullable=False, index=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='CASCADE'), nullable=False, index=True)
    role = db.Column(db.String(10), nullable=False, default='editor')  # owner | editor | viewer
    added_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    added_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)

    __table_args__ = (db.UniqueConstraint('pack_id', 'user_id', name='ux_mission_pack_member'),)


class MissionPackInvite(db.Model):
    """An invitation to a pack or to a team, by email or (teams only) by a single-use link.

    The token is stored as SHA-256, like AccountToken. An emailed invite is also
    matched by address, so someone who signs up later finds it waiting once they
    clear the .mil gate.
    """

    __tablename__ = 'mission_pack_invite'

    id = db.Column(db.Integer, primary_key=True)
    pack_id = db.Column(db.Integer, db.ForeignKey('mission_pack.id', ondelete='CASCADE'), nullable=True, index=True)
    team_id = db.Column(db.Integer, db.ForeignKey('team.id', ondelete='CASCADE'), nullable=True, index=True)
    email = db.Column(db.String(120), nullable=True, index=True)  # lower-cased; NULL for a link invite
    token_hash = db.Column(db.String(64), unique=True, nullable=False)
    invited_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    role = db.Column(db.String(10), nullable=False)
    status = db.Column(db.String(10), nullable=False, default='pending')  # pending | accepted | declined | revoked
    expires_at = db.Column(db.DateTime, nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    accepted_at = db.Column(db.DateTime, nullable=True)
    accepted_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)


class MissionPackItem(db.Model):
    """An LZ, a set of sketched routes or a point set inside a pack.

    ``data`` is the same JSON the library keeps (lz_data, route_data, points_data),
    so every editor and exporter works on it unchanged. An item copied in from the
    library remembers where it came from (``source_*``) so whoever copied it can
    update it from the original later; the copy is the pack's, and nothing reaches back.
    """

    __tablename__ = 'mission_pack_item'

    id = db.Column(db.Integer, primary_key=True)
    pack_id = db.Column(db.Integer, db.ForeignKey('mission_pack.id', ondelete='CASCADE'), nullable=False, index=True)
    uuid = db.Column(db.String(64), nullable=False)
    kind = db.Column(db.String(10), nullable=False)  # lz | route | pointset
    name = db.Column(db.String(100), nullable=False)
    data = db.Column(db.JSON, nullable=True)  # NULL once deleted
    revision = db.Column(db.Integer, nullable=False, default=1)
    change_seq = db.Column(db.Integer, nullable=False, default=0)  # the event that last changed it
    created_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    updated_by = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)
    source_kind = db.Column(db.String(10), nullable=True)
    source_uuid = db.Column(db.String(36), nullable=True)
    source_revision = db.Column(db.Integer, nullable=True)
    deleted_at = db.Column(db.DateTime, nullable=True)

    __table_args__ = (db.UniqueConstraint('pack_id', 'uuid', name='ux_mission_pack_item'),)


class MissionPackEvent(db.Model):
    """One numbered change to a pack: the edit log, and what clients replay to catch up.

    Append-only. ``summary`` is the sentence the client that made the change wrote
    ("Colin moved Chalk 2 on LZ Hawk"); ``payload`` is the operation itself.
    ``actor_name`` is kept as it was, so the log still reads after an account is gone.
    """

    __tablename__ = 'mission_pack_event'

    id = db.Column(db.Integer, primary_key=True)
    pack_id = db.Column(db.Integer, db.ForeignKey('mission_pack.id', ondelete='CASCADE'), nullable=False, index=True)
    seq = db.Column(db.Integer, nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id', ondelete='SET NULL'), nullable=True)
    actor_name = db.Column(db.String(120), nullable=False, default='')
    item_uuid = db.Column(db.String(64), nullable=True)
    op_type = db.Column(db.String(32), nullable=False)
    payload = db.Column(db.JSON, nullable=False)
    summary = db.Column(db.String(300), nullable=False, default='')
    client_op_id = db.Column(db.String(64), nullable=True)
    status = db.Column(db.String(10), nullable=False, default='applied')  # applied | skipped
    reason = db.Column(db.String(32), nullable=True)  # why it was skipped
    created_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)

    __table_args__ = (
        db.UniqueConstraint('pack_id', 'seq', name='ux_mission_pack_event_seq'),
        # A retried batch is recognised rather than applied twice.
        db.UniqueConstraint('pack_id', 'client_op_id', name='ux_mission_pack_event_client_op'),
    )
