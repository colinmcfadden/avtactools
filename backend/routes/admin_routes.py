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

from models import db, User, SavedRoute, SavedLZ, SavedPointSet
from entitlements import (
    FEATURES, FEATURE_KEYS, resolve_features,
    is_admin, is_super_admin, account_active,
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
        'admin/users.html',
        users=rows, q=q, page=page, pages=pages, total=total,
    )


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
    return render_template(
        'admin/user_detail.html',
        u=user, counts=counts, features=resolve_features(user),
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
    db.session.delete(user)  # cascades local_credential + account_tokens
    db.session.commit()
    flash('User deleted.', 'ok')
    return redirect(url_for('admin.users'))
