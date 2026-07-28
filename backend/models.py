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


class AircraftProfile(db.Model):
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

    __table_args__ = (
        db.Index('ix_aircraft_profile_owner_slug', 'user_id', 'slug'),
    )

    @property
    def is_system(self):
        return self.user_id is None

    @property
    def has_template(self):
        return self.template_file is not None

    def to_dict(self):
        """Client-facing shape. Never includes the vidx bytes — only a flag."""
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
