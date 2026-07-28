"""Server-rendered admin dashboard (mounted at /admin, served on
admin.ezpztac.app).

Access is gated by a server-side session established through the admin login
(Google or email+password). Every request re-verifies that the session user is
still an active admin, so a demotion or suspension takes effect immediately. The
configured super-admin (SUPER_ADMIN_EMAIL) can never be demoted, suspended, or
deleted here.
"""

import functools
import os
import secrets
from datetime import timedelta

from flask import (
    Blueprint, render_template, request, redirect, url_for,
    session, abort, flash, jsonify,
)
from sqlalchemy import func, or_
from werkzeug.security import check_password_hash
from google.oauth2 import id_token
from google.auth.transport import requests as google_requests

from models import db, User, SavedRoute, SavedLZ, SavedPointSet, LoginEvent, AircraftProfile
from entitlements import (
    FEATURES, FEATURE_KEYS, resolve_features,
    is_admin, is_super_admin, account_active, affiliation_ok,
)
from amps_package import inspect_amps_package
from aircraft_seed import ICON_CHOICES
from routes.aircraft_routes import (
    AIRSPEED_TYPES, ALTITUDE_REFS,
    _NUMERIC_FIELDS as AIRCRAFT_NUMERIC_FIELDS,
    _slugify as _aircraft_slug,
)
from auth_rate_limit import check_rate_limits
from email_service import send_verification_email, send_password_reset_email
from routes.auth import _create_account_token, VERIFY_PURPOSE, RESET_PURPOSE

admin_bp = Blueprint('admin', __name__, url_prefix='/admin')

PER_PAGE = 25


# --- session / CSRF helpers -------------------------------------------------

def _csrf_token():
    token = session.get('csrf')
    if not token:
        token = secrets.token_urlsafe(32)
        session['csrf'] = token
    return token


def _check_csrf():
    supplied = request.form.get('csrf') or request.headers.get('X-CSRF')
    if not supplied or supplied != session.get('csrf'):
        abort(400, 'Invalid or missing CSRF token')


def _current_admin():
    uid = session.get('admin_uid')
    if not uid:
        return None
    user = db.session.get(User, uid)
    if user and is_admin(user) and account_active(user):
        return user
    return None


def admin_required(view):
    @functools.wraps(view)
    def wrapped(*args, **kwargs):
        admin = _current_admin()
        if not admin:
            session.pop('admin_uid', None)
            return redirect(url_for('admin.login'))
        request.admin = admin
        return view(*args, **kwargs)
    return wrapped


@admin_bp.context_processor
def _inject():
    return {
        'csrf_token': _csrf_token,
        'FEATURES': FEATURES,
        'resolve_features': resolve_features,
        'is_admin': is_admin,
        'is_super_admin': is_super_admin,
        'account_active': account_active,
        'affiliation_ok': affiliation_ok,
        'current_admin': _current_admin(),
        'ICON_CHOICES': ICON_CHOICES,
    }


def _client_ip():
    if os.environ.get('FLY_APP_NAME'):
        return (request.headers.get('Fly-Client-IP') or request.remote_addr or 'unknown').strip()
    return request.remote_addr or 'unknown'


# --- authentication ---------------------------------------------------------

@admin_bp.route('/login', methods=['GET', 'POST'])
def login():
    if _current_admin():
        return redirect(url_for('admin.users'))

    error = None
    if request.method == 'POST':
        _check_csrf()
        if check_rate_limits([('admin_login:ip', _client_ip(), 20, 600)]):
            error = "Too many attempts. Try again shortly."
        else:
            email = (request.form.get('email') or '').strip().casefold()
            password = request.form.get('password') or ''
            user = User.query.filter(func.lower(User.email) == email).first()
            cred = user.local_credential if user else None
            password_ok = bool(
                cred
                and cred.status == 'active'
                and cred.email_verified_at is not None
                and check_password_hash(cred.password_hash, password)
            )
            if password_ok and is_admin(user) and account_active(user):
                session['admin_uid'] = user.id
                return redirect(url_for('admin.users'))
            error = "Invalid credentials, or that account is not an admin."

    return render_template(
        'admin/login.html',
        error=error,
        google_client_id=os.environ.get('GOOGLE_CLIENT_ID', ''),
    )


@admin_bp.route('/login/google', methods=['POST'])
def login_google():
    _check_csrf()
    body = request.get_json(silent=True) or {}
    token = body.get('credential')
    client_id = os.environ.get('GOOGLE_CLIENT_ID')
    if not token or not client_id:
        return jsonify({'ok': False, 'error': 'Google sign-in is not configured.'}), 400
    try:
        idinfo = id_token.verify_oauth2_token(token, google_requests.Request(), client_id)
        email = (idinfo.get('email') or '').strip().casefold()
        verified = idinfo.get('email_verified') in (True, 'true', 'True')
        if verified and email:
            user = User.query.filter(func.lower(User.email) == email).first()
            if user and is_admin(user) and account_active(user):
                session['admin_uid'] = user.id
                return jsonify({'ok': True, 'redirect': url_for('admin.users')})
    except Exception:  # noqa: BLE001
        pass
    return jsonify({'ok': False, 'error': "That account isn't an admin, or sign-in failed."}), 403


@admin_bp.route('/logout', methods=['POST'])
def logout():
    _check_csrf()
    session.pop('admin_uid', None)
    return redirect(url_for('admin.login'))


# --- user management --------------------------------------------------------

@admin_bp.route('/')
@admin_required
def users():
    q = (request.args.get('q') or '').strip()
    try:
        page = max(1, int(request.args.get('page', 1)))
    except (TypeError, ValueError):
        page = 1

    query = User.query
    if q:
        like = f"%{q}%"
        query = query.filter(or_(User.email.ilike(like), User.name.ilike(like)))
    total = query.count()
    rows = (
        query.order_by(User.created_at.desc())
        .offset((page - 1) * PER_PAGE)
        .limit(PER_PAGE)
        .all()
    )
    pages = max(1, (total + PER_PAGE - 1) // PER_PAGE)
    return render_template(
        'admin/users.html', nav_section='users',
        users=rows, q=q, page=page, pages=pages, total=total,
    )


LOGINS_PER_PAGE = 15


@admin_bp.route('/users/<int:uid>')
@admin_required
def user_detail(uid):
    user = db.session.get(User, uid)
    if not user:
        abort(404)
    counts = {
        'routes': SavedRoute.query.filter_by(user_id=uid).count(),
        'lzs': SavedLZ.query.filter_by(user_id=uid).count(),
        'point_sets': SavedPointSet.query.filter_by(user_id=uid).count(),
    }
    try:
        lp = max(1, int(request.args.get('lp', 1)))
    except (TypeError, ValueError):
        lp = 1
    login_q = LoginEvent.query.filter_by(user_id=uid).order_by(LoginEvent.created_at.desc())
    login_total = login_q.count()
    logins = login_q.offset((lp - 1) * LOGINS_PER_PAGE).limit(LOGINS_PER_PAGE).all()
    login_pages = max(1, (login_total + LOGINS_PER_PAGE - 1) // LOGINS_PER_PAGE)
    return render_template(
        'admin/user_detail.html', nav_section='users',
        u=user, counts=counts, features=resolve_features(user),
        logins=logins, lp=lp, login_pages=login_pages, login_total=login_total,
    )


def _load_target(uid):
    user = db.session.get(User, uid)
    if not user:
        abort(404)
    return user


@admin_bp.route('/users/<int:uid>/active', methods=['POST'])
@admin_required
def set_active(uid):
    _check_csrf()
    user = _load_target(uid)
    if is_super_admin(user):
        flash("The super-admin can't be suspended.", 'error')
    else:
        user.is_active = request.form.get('active') == '1'
        db.session.commit()
        flash('Access ' + ('restored.' if user.is_active else 'suspended.'), 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/access-approval', methods=['POST'])
@admin_required
def set_access_approval(uid):
    _check_csrf()
    user = _load_target(uid)
    user.access_approved = request.form.get('approved') == '1'
    db.session.commit()
    flash('Affiliation access ' + ('approved.' if user.access_approved else 'revoked.'), 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/role', methods=['POST'])
@admin_required
def set_role(uid):
    _check_csrf()
    user = _load_target(uid)
    role = request.form.get('role')
    if is_super_admin(user):
        flash("The super-admin's role is fixed.", 'error')
    elif role in ('user', 'admin'):
        user.role = role
        db.session.commit()
        flash('Role updated to ' + role + '.', 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/features', methods=['POST'])
@admin_required
def set_features(uid):
    _check_csrf()
    user = _load_target(uid)
    enabled = set(request.form.getlist('feature'))
    user.features = {key: (key in enabled) for key in FEATURE_KEYS}
    db.session.commit()
    flash('Feature access updated.', 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/edit', methods=['POST'])
@admin_required
def edit_user(uid):
    _check_csrf()
    user = _load_target(uid)
    name = (request.form.get('name') or '').strip()
    if name and len(name) <= 120:
        user.name = name
        db.session.commit()
        flash('Name saved.', 'ok')
    else:
        flash('Enter a name between 1 and 120 characters.', 'error')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/resend-verification', methods=['POST'])
@admin_required
def resend_verification(uid):
    _check_csrf()
    user = _load_target(uid)
    if user.local_credential is None:
        flash('This is a Google account with no password to verify.', 'error')
    else:
        raw = _create_account_token(user, VERIFY_PURPOSE, timedelta(hours=24))
        db.session.commit()
        send_verification_email(user, raw)
        flash('Verification email sent.', 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/reset-password', methods=['POST'])
@admin_required
def reset_password(uid):
    _check_csrf()
    user = _load_target(uid)
    if user.local_credential is None:
        flash('This account has no password (Google sign-in).', 'error')
    else:
        raw = _create_account_token(user, RESET_PURPOSE, timedelta(minutes=60))
        db.session.commit()
        send_password_reset_email(user, raw)
        flash('Password-reset email sent.', 'ok')
    return redirect(url_for('admin.user_detail', uid=uid))


@admin_bp.route('/users/<int:uid>/delete', methods=['POST'])
@admin_required
def delete_user(uid):
    _check_csrf()
    user = _load_target(uid)
    if is_super_admin(user):
        flash("The super-admin can't be deleted.", 'error')
        return redirect(url_for('admin.user_detail', uid=uid))
    if request.admin.id == user.id:
        flash("You can't delete the account you're signed in with.", 'error')
        return redirect(url_for('admin.user_detail', uid=uid))
    # Typed-email confirmation guards against an accidental destructive click.
    if (request.form.get('confirm_email') or '').strip().casefold() != (user.email or '').casefold():
        flash("Deletion cancelled: the confirmation email didn't match.", 'error')
        return redirect(url_for('admin.user_detail', uid=uid))

    SavedRoute.query.filter_by(user_id=uid).delete()
    SavedLZ.query.filter_by(user_id=uid).delete()
    SavedPointSet.query.filter_by(user_id=uid).delete()
    LoginEvent.query.filter_by(user_id=uid).delete()
    db.session.delete(user)  # cascades local_credential + account_tokens
    db.session.commit()
    flash('User deleted.', 'ok')
    return redirect(url_for('admin.users'))


# --- aircraft profiles ------------------------------------------------------

# Uploaded AMPS packages. The UH-60L vidx alone is ~640 KB and a saved mission
# runs a few MB, so allow headroom while still refusing anything absurd.
MAX_TEMPLATE_BYTES = 32 * 1024 * 1024


def _aircraft_form_errors(profile, form):
    """Copy the admin form onto ``profile``; return a list of problems."""
    errors = []

    name = (form.get('name') or '').strip()
    designation = (form.get('designation') or '').strip()
    if not name:
        errors.append('Name is required.')
    if not designation:
        errors.append('Designation is required.')
    profile.name = name[:120]
    profile.designation = designation[:40]
    profile.icon_key = (form.get('icon_key') or 'generic').strip()[:40] or 'generic'

    description = (form.get('amps_vehicle_description') or '').strip()
    profile.amps_vehicle_description = description[:200] or None

    airspeed_type = (form.get('default_airspeed_type') or 'ground').strip().lower()
    if airspeed_type not in AIRSPEED_TYPES:
        errors.append('Invalid airspeed type.')
    else:
        profile.default_airspeed_type = airspeed_type

    altitude_ref = (form.get('default_altitude_ref') or 'agl').strip().lower()
    if altitude_ref not in ALTITUDE_REFS:
        errors.append('Invalid altitude reference.')
    else:
        profile.default_altitude_ref = altitude_ref

    perf_source = (form.get('perf_source') or 'custom').strip().lower()
    profile.perf_source = perf_source if perf_source in ('vidx', 'published', 'custom') else 'custom'

    for field, (low, high) in AIRCRAFT_NUMERIC_FIELDS.items():
        raw = form.get(field)
        if raw is None or str(raw).strip() == '':
            continue
        try:
            value = float(raw)
        except (TypeError, ValueError):
            errors.append(f'{field.replace("_", " ")} must be a number.')
            continue
        if not (low <= value <= high):
            errors.append(f'{field.replace("_", " ")} must be between {low} and {high}.')
            continue
        setattr(profile, field, value)

    try:
        profile.sort_order = int(form.get('sort_order') or profile.sort_order or 100)
    except (TypeError, ValueError):
        errors.append('Sort order must be a whole number.')

    profile.is_active = form.get('is_active') == 'on'

    if (
        profile.min_altitude_ft_msl is not None
        and profile.max_altitude_ft_msl is not None
        and profile.min_altitude_ft_msl > profile.max_altitude_ft_msl
    ):
        errors.append('Minimum altitude cannot exceed maximum altitude.')

    return errors


@admin_bp.route('/aircraft')
@admin_required
def aircraft():
    master = (
        AircraftProfile.query.filter(AircraftProfile.user_id.is_(None))
        .order_by(AircraftProfile.sort_order, AircraftProfile.name)
        .all()
    )
    custom = (
        AircraftProfile.query.filter(AircraftProfile.user_id.isnot(None))
        .order_by(AircraftProfile.updated_at.desc())
        .limit(100)
        .all()
    )
    owners = {}
    if custom:
        owner_ids = {p.user_id for p in custom}
        owners = {u.id: u for u in User.query.filter(User.id.in_(owner_ids)).all()}
    return render_template(
        'admin/aircraft.html', nav_section='aircraft',
        master=master, custom=custom, owners=owners,
    )


@admin_bp.route('/aircraft/new')
@admin_required
def aircraft_new():
    # An unsaved instance so the form template renders one code path for both
    # create and edit.
    blank = AircraftProfile(
        slug='', name='', designation='', icon_key='generic',
        rotor_diameter_m=16.357, rotor_tip_clearance_m=60.0,
        default_airspeed_kts=100, default_airspeed_type='ground',
        max_indicated_kts=193, default_altitude_ft=50, default_altitude_ref='agl',
        min_altitude_ft_msl=-2000, max_altitude_ft_msl=20000,
        default_fuel_flow_lb_hr=960, default_gross_weight_lb=16000,
        perf_source='custom', is_active=True, sort_order=100,
    )
    return render_template('admin/aircraft_form.html', nav_section='aircraft', p=blank, creating=True, owner=None)


@admin_bp.route('/aircraft/<int:pid>')
@admin_required
def aircraft_detail(pid):
    profile = db.session.get(AircraftProfile, pid)
    if not profile:
        abort(404)
    owner = db.session.get(User, profile.user_id) if profile.user_id else None
    return render_template(
        'admin/aircraft_form.html', nav_section='aircraft', p=profile, creating=False, owner=owner,
    )


@admin_bp.route('/aircraft/create', methods=['POST'])
@admin_required
def aircraft_create():
    _check_csrf()
    profile = AircraftProfile(user_id=None)
    errors = _aircraft_form_errors(profile, request.form)

    slug = _aircraft_slug(request.form.get('slug') or profile.designation or profile.name)
    if not slug:
        errors.append('Slug is required.')
    elif AircraftProfile.query.filter(
        AircraftProfile.user_id.is_(None), AircraftProfile.slug == slug
    ).first():
        errors.append(f'A master profile with the slug "{slug}" already exists.')
    profile.slug = slug

    if errors:
        for message in errors:
            flash(message, 'error')
        return redirect(url_for('admin.aircraft_new'))

    db.session.add(profile)
    db.session.commit()
    flash(f'Created {profile.name}.', 'ok')
    return redirect(url_for('admin.aircraft_detail', pid=profile.id))


@admin_bp.route('/aircraft/<int:pid>/save', methods=['POST'])
@admin_required
def aircraft_save(pid):
    _check_csrf()
    profile = db.session.get(AircraftProfile, pid)
    if not profile:
        abort(404)

    errors = _aircraft_form_errors(profile, request.form)
    if errors:
        db.session.rollback()
        for message in errors:
            flash(message, 'error')
    else:
        db.session.commit()
        flash('Saved.', 'ok')
    return redirect(url_for('admin.aircraft_detail', pid=pid))


@admin_bp.route('/aircraft/<int:pid>/template', methods=['POST'])
@admin_required
def aircraft_template(pid):
    """Attach or clear the AMPS package that makes export use this airframe."""
    _check_csrf()
    profile = db.session.get(AircraftProfile, pid)
    if not profile:
        abort(404)

    if request.form.get('clear') == '1':
        profile.template_file = None
        profile.template_name = None
        profile.template_kind = None
        db.session.commit()
        flash('Template removed. Exports for this profile fall back to the UH-60L package.', 'ok')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    upload = request.files.get('template')
    if not upload or not upload.filename:
        flash('Choose a .msnx or .vidx file to upload.', 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    filename = os.path.basename(upload.filename)
    extension = filename.rsplit('.', 1)[-1].lower() if '.' in filename else ''
    if extension not in ('msnx', 'vidx'):
        flash('Unsupported file type. Upload a .msnx mission or a .vidx vehicle installation.', 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    data = upload.read(MAX_TEMPLATE_BYTES + 1)
    if len(data) > MAX_TEMPLATE_BYTES:
        flash(f'File is too large (limit {MAX_TEMPLATE_BYTES // (1024 * 1024)} MB).', 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))
    if not data:
        flash('That file is empty.', 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    # Both formats are OPC zips. Reject anything else now rather than letting
    # the browser-side exporter choke on a corrupt package later.
    detected = inspect_amps_package(data, extension)
    if detected.get('error'):
        flash(detected['error'], 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    profile.template_file = data
    profile.template_name = filename[:120]
    profile.template_kind = extension
    # A saved mission states exactly what AMPS calls this airframe; adopt it
    # unless an admin already typed one in.
    if detected.get('vehicle_description') and not profile.amps_vehicle_description:
        profile.amps_vehicle_description = detected['vehicle_description'][:200]
    db.session.commit()

    note = ''
    if detected.get('vehicle_description'):
        note = f' Detected airframe: {detected["vehicle_description"]}.'
    flash(f'Attached {filename}.{note}', 'ok')
    return redirect(url_for('admin.aircraft_detail', pid=pid))


@admin_bp.route('/aircraft/<int:pid>/delete', methods=['POST'])
@admin_required
def aircraft_delete(pid):
    _check_csrf()
    profile = db.session.get(AircraftProfile, pid)
    if not profile:
        abort(404)
    if (request.form.get('confirm_slug') or '').strip() != profile.slug:
        flash("Deletion cancelled: the confirmation slug didn't match.", 'error')
        return redirect(url_for('admin.aircraft_detail', pid=pid))

    label = profile.name
    was_master = profile.is_system
    db.session.delete(profile)
    db.session.commit()
    # A deleted seed slug comes back on the next boot; say so rather than
    # letting it look like a bug.
    hint = ''
    if was_master:
        hint = ' Seeded profiles reappear on restart. Retire instead of deleting to keep one hidden.'
    flash(f'Deleted {label}.{hint}', 'ok')
    return redirect(url_for('admin.aircraft'))


@admin_bp.route('/aircraft/<int:pid>/promote', methods=['POST'])
@admin_required
def aircraft_promote(pid):
    """Copy a user's custom profile into the master list."""
    _check_csrf()
    source = db.session.get(AircraftProfile, pid)
    if not source or source.is_system:
        abort(404)

    base = _aircraft_slug(source.designation or source.name) or f'custom-{source.id}'
    slug, suffix = base, 2
    while AircraftProfile.query.filter(
        AircraftProfile.user_id.is_(None), AircraftProfile.slug == slug
    ).first():
        slug, suffix = f'{base}-{suffix}', suffix + 1

    copy = AircraftProfile(
        user_id=None, slug=slug, name=source.name, designation=source.designation,
        icon_key=source.icon_key,
        rotor_diameter_m=source.rotor_diameter_m,
        rotor_tip_clearance_m=source.rotor_tip_clearance_m,
        default_airspeed_kts=source.default_airspeed_kts,
        default_airspeed_type=source.default_airspeed_type,
        max_indicated_kts=source.max_indicated_kts,
        default_altitude_ft=source.default_altitude_ft,
        default_altitude_ref=source.default_altitude_ref,
        min_altitude_ft_msl=source.min_altitude_ft_msl,
        max_altitude_ft_msl=source.max_altitude_ft_msl,
        default_fuel_flow_lb_hr=source.default_fuel_flow_lb_hr,
        default_gross_weight_lb=source.default_gross_weight_lb,
        amps_vehicle_description=source.amps_vehicle_description,
        perf_source=source.perf_source,
        is_active=True,
        sort_order=500,
    )
    db.session.add(copy)
    db.session.commit()
    flash(
        f'Promoted "{source.name}" to the master list. Review its values before relying on them.',
        'ok',
    )
    return redirect(url_for('admin.aircraft_detail', pid=copy.id))
