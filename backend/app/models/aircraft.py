from datetime import datetime

from app.extensions import db


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
