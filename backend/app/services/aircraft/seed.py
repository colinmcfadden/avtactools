"""Master aircraft profiles shipped with the app.

Seeded idempotently at startup and thereafter owned by the admin dashboard.
Re-seeding only fills in profiles whose slug is missing — it never overwrites
an admin's edits, so tuning a value in the dashboard survives every deploy.

A word on the numbers, because this feeds mission planning:

* ``rotor_diameter_m`` is published airframe geometry. For the tandem-rotor
  CH-47 it is the *overall* rotor span (fore tip to aft tip), since that is the
  footprint that governs spacing — not the 60 ft diameter of one disc.
* ``rotor_tip_clearance_m`` is seeded at 60 m for every platform, matching the
  rule the app already used for the UH-60. Per-platform clearances are
  doctrinal and are left for an admin to set in the dashboard. Center spacing
  is always derived (diameter + clearance), so platforms already separate
  differently from geometry alone.
* Only the UH-60L performance block is authoritative — it was extracted from
  the UH60L.vidx bundled in the mission template. Everything else is tagged
  ``published`` and should be verified before it is trusted for fuel planning.
"""

# Map sprites available to a profile. Keys must match the frontend's
# aircraftIcons.js; anything unknown renders the generic silhouette.
ICON_CHOICES = (
    ("uh60", "UH-60 / H-60 (single main rotor, tail boom)"),
    ("ah64", "AH-64 (attack, slim fuselage)"),
    ("ch47", "CH-47 (tandem rotor)"),
    ("uh72", "UH-72 (light utility)"),
    ("mh6", "MH-6 / AH-6 (light, egg fuselage)"),
    ("generic", "Generic rotary wing"),
)

# Airframe geometry, in meters, from published specifications.
_UH60_ROTOR_M = 16.357   # 53 ft 8 in
_AH64_ROTOR_M = 14.63    # 48 ft 0 in
_CH47_ROTOR_M = 30.14    # 98 ft 10.7 in overall span (tandem)
_UH72_ROTOR_M = 11.00    # 36 ft 1 in
_MD530_ROTOR_M = 8.38    # 27 ft 6 in

# Doctrinal per-platform clearances belong to the admin; seed the existing rule.
_DEFAULT_CLEARANCE_M = 60.0

SEED_PROFILES = (
    {
        "slug": "uh60l",
        "name": "UH-60L Black Hawk",
        "designation": "UH-60L",
        "icon_key": "uh60",
        "rotor_diameter_m": _UH60_ROTOR_M,
        "sort_order": 10,
        # Straight out of UH60L.vidx (Standard Aircraft Preferences.xml + FPM
        # Preferences.xml) — the same numbers the planner has always used.
        "default_airspeed_kts": 100,
        "default_airspeed_type": "ground",
        "max_indicated_kts": 193,
        "default_altitude_ft": 50,
        "default_altitude_ref": "agl",
        "default_fuel_flow_lb_hr": 960,
        "default_gross_weight_lb": 16000,
        "perf_source": "vidx",
        "amps_vehicle_description": "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L",
    },
    {
        "slug": "uh60m",
        "name": "UH-60M Black Hawk",
        "designation": "UH-60M",
        "icon_key": "uh60",
        "rotor_diameter_m": _UH60_ROTOR_M,
        "sort_order": 20,
        "default_airspeed_kts": 120,
        "max_indicated_kts": 193,
        "default_fuel_flow_lb_hr": 1100,
        "default_gross_weight_lb": 17000,
        "perf_source": "published",
    },
    {
        "slug": "hh60m",
        "name": "HH-60M Medevac",
        "designation": "HH-60M",
        "icon_key": "uh60",
        "rotor_diameter_m": _UH60_ROTOR_M,
        "sort_order": 30,
        "default_airspeed_kts": 120,
        "max_indicated_kts": 193,
        "default_fuel_flow_lb_hr": 1100,
        "default_gross_weight_lb": 17000,
        "perf_source": "published",
    },
    {
        "slug": "ah64d",
        "name": "AH-64D Apache Longbow",
        "designation": "AH-64D",
        "icon_key": "ah64",
        "rotor_diameter_m": _AH64_ROTOR_M,
        "sort_order": 40,
        "default_airspeed_kts": 130,
        "max_indicated_kts": 158,
        "default_fuel_flow_lb_hr": 1200,
        "default_gross_weight_lb": 16000,
        "perf_source": "published",
    },
    {
        "slug": "ah64e",
        "name": "AH-64E Apache Guardian",
        "designation": "AH-64E",
        "icon_key": "ah64",
        "rotor_diameter_m": _AH64_ROTOR_M,
        "sort_order": 50,
        "default_airspeed_kts": 140,
        "max_indicated_kts": 164,
        "default_fuel_flow_lb_hr": 1250,
        "default_gross_weight_lb": 17650,
        "perf_source": "published",
    },
    {
        "slug": "ch47f",
        "name": "CH-47F Chinook",
        "designation": "CH-47F",
        "icon_key": "ch47",
        "rotor_diameter_m": _CH47_ROTOR_M,
        "sort_order": 60,
        "default_airspeed_kts": 130,
        "max_indicated_kts": 170,
        "default_fuel_flow_lb_hr": 2400,
        "default_gross_weight_lb": 33000,
        "perf_source": "published",
    },
    {
        "slug": "uh72a",
        "name": "UH-72A Lakota",
        "designation": "UH-72A",
        "icon_key": "uh72",
        "rotor_diameter_m": _UH72_ROTOR_M,
        "sort_order": 70,
        "default_airspeed_kts": 110,
        "max_indicated_kts": 145,
        "default_fuel_flow_lb_hr": 400,
        "default_gross_weight_lb": 7903,
        "perf_source": "published",
    },
    {
        "slug": "mh6m",
        "name": "MH-6M Little Bird",
        "designation": "MH-6M",
        "icon_key": "mh6",
        "rotor_diameter_m": _MD530_ROTOR_M,
        "sort_order": 80,
        "default_airspeed_kts": 100,
        "max_indicated_kts": 130,
        "default_fuel_flow_lb_hr": 300,
        "default_gross_weight_lb": 3550,
        "perf_source": "published",
    },
    {
        "slug": "ah6m",
        "name": "AH-6M Little Bird",
        "designation": "AH-6M",
        "icon_key": "mh6",
        "rotor_diameter_m": _MD530_ROTOR_M,
        "sort_order": 90,
        "default_airspeed_kts": 100,
        "max_indicated_kts": 130,
        "default_fuel_flow_lb_hr": 300,
        "default_gross_weight_lb": 3550,
        "perf_source": "published",
    },
)

# The profile new users start on and the fallback whenever a referenced profile
# is missing (deleted, retired, or a saved map from another database).
DEFAULT_PROFILE_SLUG = "uh60l"


def seed_aircraft_profiles(db, AircraftProfile):
    """Insert any master profile whose slug isn't present yet.

    Only touches ``user_id IS NULL`` rows. Existing profiles are left exactly
    as they are so admin edits are never clobbered by a redeploy.
    """
    existing = {
        slug
        for (slug,) in db.session.query(AircraftProfile.slug)
        .filter(AircraftProfile.user_id.is_(None))
        .all()
    }
    added = 0
    for spec in SEED_PROFILES:
        if spec["slug"] in existing:
            continue
        fields = dict(spec)
        fields.setdefault("rotor_tip_clearance_m", _DEFAULT_CLEARANCE_M)
        db.session.add(AircraftProfile(user_id=None, **fields))
        added += 1
    if added:
        db.session.commit()
    return added
