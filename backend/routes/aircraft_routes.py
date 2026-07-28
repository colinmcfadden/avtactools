"""Aircraft profile API.

Every signed-in user reads the admin-managed master list plus any custom
profiles they built themselves. Creating/editing/deleting a *custom* profile
requires the ``aircraft_profiles`` entitlement — a restricted user keeps full
access to the master list, they just can't add their own.

Master profiles are read-only here; they're managed in the admin dashboard.
"""

import io

from flask import Blueprint, request, jsonify, send_file
from flask_jwt_extended import jwt_required, get_jwt_identity

from models import db, AircraftProfile
from entitlements import require_feature

aircraft_bp = Blueprint('aircraft', __name__)

# Guardrails for user-entered geometry. Wide enough for anything rotary-wing
# (Little Bird through Chinook) while rejecting values that would break the map
# footprint or the separation math.
MIN_ROTOR_M, MAX_ROTOR_M = 1.0, 60.0
MAX_CLEARANCE_M = 1000.0

AIRSPEED_TYPES = ('ground', 'indicated', 'true')
ALTITUDE_REFS = ('agl', 'msl')

# Fields a user may set on their own profile, with (type, minimum, maximum).
# Anything outside this map — is_active, sort_order, vidx, perf_source — is
# admin-only and silently ignored on the user-facing endpoints.
_NUMERIC_FIELDS = {
    'rotor_diameter_m': (MIN_ROTOR_M, MAX_ROTOR_M),
    'rotor_tip_clearance_m': (0.0, MAX_CLEARANCE_M),
    'default_airspeed_kts': (1.0, 400.0),
    'max_indicated_kts': (1.0, 400.0),
    'default_altitude_ft': (-2000.0, 30000.0),
    'min_altitude_ft_msl': (-2000.0, 30000.0),
    'max_altitude_ft_msl': (-2000.0, 30000.0),
    'default_fuel_flow_lb_hr': (0.0, 20000.0),
    'default_gross_weight_lb': (0.0, 200000.0),
}


def _slugify(value, fallback='custom'):
    cleaned = ''.join(c.lower() if c.isalnum() else '-' for c in (value or ''))
    parts = [p for p in cleaned.split('-') if p]
    return '-'.join(parts)[:60] or fallback


def _apply_fields(profile, body, errors):
    """Validate and copy user-settable fields from ``body`` onto ``profile``."""
    if 'name' in body:
        name = (body.get('name') or '').strip()
        if not name:
            errors.append('Name is required.')
        else:
            profile.name = name[:120]

    if 'designation' in body:
        designation = (body.get('designation') or '').strip()
        if not designation:
            errors.append('Designation is required.')
        else:
            profile.designation = designation[:40]

    if 'icon_key' in body:
        profile.icon_key = (body.get('icon_key') or 'generic').strip()[:40] or 'generic'

    if 'default_airspeed_type' in body:
        value = (body.get('default_airspeed_type') or '').strip().lower()
        if value not in AIRSPEED_TYPES:
            errors.append(f"Airspeed type must be one of {', '.join(AIRSPEED_TYPES)}.")
        else:
            profile.default_airspeed_type = value

    if 'default_altitude_ref' in body:
        value = (body.get('default_altitude_ref') or '').strip().lower()
        if value not in ALTITUDE_REFS:
            errors.append("Altitude reference must be 'agl' or 'msl'.")
        else:
            profile.default_altitude_ref = value

    if 'amps_vehicle_description' in body:
        raw = (body.get('amps_vehicle_description') or '').strip()
        profile.amps_vehicle_description = raw[:200] or None

    for field, (low, high) in _NUMERIC_FIELDS.items():
        if field not in body:
            continue
        try:
            value = float(body[field])
        except (TypeError, ValueError):
            errors.append(f'{field} must be a number.')
            continue
        if value != value or value in (float('inf'), float('-inf')):
            errors.append(f'{field} must be a finite number.')
            continue
        if not (low <= value <= high):
            errors.append(f'{field} must be between {low} and {high}.')
            continue
        setattr(profile, field, value)

    # A max below the min would make the planner's altitude clamp unsatisfiable.
    if profile.min_altitude_ft_msl is not None and profile.max_altitude_ft_msl is not None:
        if profile.min_altitude_ft_msl > profile.max_altitude_ft_msl:
            errors.append('Minimum altitude cannot exceed maximum altitude.')


def _visible_profiles(user_id):
    """Active master profiles plus every profile this user owns."""
    return (
        AircraftProfile.query.filter(
            db.or_(
                db.and_(
                    AircraftProfile.user_id.is_(None),
                    AircraftProfile.is_active.is_(True),
                ),
                AircraftProfile.user_id == user_id,
            )
        )
        .order_by(AircraftProfile.user_id.isnot(None), AircraftProfile.sort_order, AircraftProfile.name)
        .all()
    )


@aircraft_bp.route('/api/aircraft-profiles', methods=['GET'])
@jwt_required()
def list_profiles():
    user_id = int(get_jwt_identity())
    return jsonify([p.to_dict() for p in _visible_profiles(user_id)])


@aircraft_bp.route('/api/aircraft-profiles', methods=['POST'])
@jwt_required()
@require_feature('aircraft_profiles')
def create_profile():
    user_id = int(get_jwt_identity())
    body = request.get_json(silent=True) or {}

    profile = AircraftProfile(user_id=user_id, perf_source='custom')
    # Defaults for anything the client leaves out, so a sparse payload still
    # produces a usable profile rather than a half-populated row.
    profile.name = 'Custom aircraft'
    profile.designation = 'CUSTOM'

    errors = []
    _apply_fields(profile, body, errors)
    if errors:
        return jsonify({"error": errors[0], "errors": errors}), 400

    base = _slugify(profile.designation or profile.name)
    taken = {
        slug for (slug,) in db.session.query(AircraftProfile.slug)
        .filter(AircraftProfile.user_id == user_id).all()
    }
    slug, suffix = base, 2
    while slug in taken:
        slug, suffix = f'{base}-{suffix}', suffix + 1
    profile.slug = slug

    db.session.add(profile)
    db.session.commit()
    return jsonify(profile.to_dict()), 201


def _own_profile(profile_id, user_id):
    return AircraftProfile.query.filter_by(id=profile_id, user_id=user_id).first()


@aircraft_bp.route('/api/aircraft-profiles/<int:profile_id>', methods=['PUT'])
@jwt_required()
@require_feature('aircraft_profiles')
def update_profile(profile_id):
    user_id = int(get_jwt_identity())
    profile = _own_profile(profile_id, user_id)
    if not profile:
        # Master profiles are visible but not editable here; don't leak which.
        return jsonify({"error": "Not found"}), 404

    errors = []
    _apply_fields(profile, request.get_json(silent=True) or {}, errors)
    if errors:
        db.session.rollback()
        return jsonify({"error": errors[0], "errors": errors}), 400

    db.session.commit()
    return jsonify(profile.to_dict())


@aircraft_bp.route('/api/aircraft-profiles/<int:profile_id>', methods=['DELETE'])
@jwt_required()
@require_feature('aircraft_profiles')
def delete_profile(profile_id):
    user_id = int(get_jwt_identity())
    profile = _own_profile(profile_id, user_id)
    if not profile:
        return jsonify({"error": "Not found"}), 404

    db.session.delete(profile)
    db.session.commit()
    return jsonify({"status": "deleted"})


@aircraft_bp.route('/api/aircraft-profiles/<int:profile_id>/template', methods=['GET'])
@jwt_required()
def download_template(profile_id):
    """The AMPS bytes for this airframe, when an admin has attached them.

    MSNX export fetches this to build the mission on the right airframe — a
    whole ``.msnx`` is used as the base package, a bare ``.vidx`` is
    transplanted into the default template. 404 when absent, which the exporter
    treats as "fall back to UH-60L and warn".
    """
    user_id = int(get_jwt_identity())
    profile = AircraftProfile.query.filter(
        AircraftProfile.id == profile_id,
        db.or_(
            AircraftProfile.user_id.is_(None),
            AircraftProfile.user_id == user_id,
        ),
    ).first()
    if not profile or not profile.template_file:
        return jsonify({"error": "No AMPS template on file"}), 404

    response = send_file(
        io.BytesIO(profile.template_file),
        mimetype='application/octet-stream',
        as_attachment=False,
        download_name=profile.template_name or f'{profile.slug}.{profile.template_kind or "bin"}',
    )
    # The exporter branches on this rather than sniffing the bytes.
    response.headers['X-Template-Kind'] = profile.template_kind or ''
    return response
