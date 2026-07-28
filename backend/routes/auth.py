import hashlib
import ipaddress
import os
import re
import secrets
import uuid
from datetime import datetime, timedelta

from flask import Blueprint, current_app, jsonify, request
from flask_jwt_extended import create_access_token, get_jwt_identity, jwt_required
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from werkzeug.security import check_password_hash, generate_password_hash

from auth_rate_limit import check_rate_limits
from email_service import (
    send_new_account_notification,
    send_password_changed_email,
    send_password_reset_email,
    send_verification_email,
    send_welcome_email,
    send_mil_verification_email,
)
from models import AccountToken, LocalCredential, LoginEvent, User, db
from entitlements import (
    account_active,
    affiliation_ok,
    is_admin,
    is_super_admin,
    resolve_features,
)


auth_bp = Blueprint('auth', __name__)

PASSWORD_MIN_LENGTH = 15
PASSWORD_MAX_LENGTH = 128
VERIFY_PURPOSE = 'verify_email'
RESET_PURPOSE = 'password_reset'
MIL_VERIFY_PURPOSE = 'mil_verify'
EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")
# Any .mil address (all DoD services), e.g. army.mil, mail.mil, us.army.mil.
MIL_EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.mil$", re.IGNORECASE)
# Unambiguous code alphabet (no 0/O/1/I) for a typable cross-device code.
_MIL_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
COMMON_WEAK_PASSWORDS = frozenset({
    '123456789012345',
    'adminadminadminadmin',
    'correcthorsebatterystaple',
    'iloveyouiloveyou',
    'letmeinletmeinletmein',
    'password123456',
    'passwordpassword',
    'qwertyqwertyqwerty',
    'welcomecomewelcome',
})
# A valid, deliberately impossible scrypt hash used to make unknown-email and
# Google-only login attempts perform the same expensive password verification
# as local-account attempts. It is a module constant, never generated per call.
DUMMY_PASSWORD_HASH = (
    'scrypt:32768:8:1$ezpz-auth-timing-salt$'
    + ('0' * 128)
)
GENERIC_REGISTER_MESSAGE = (
    "If the address is eligible, a verification email will arrive shortly."
)
GENERIC_RESET_MESSAGE = (
    "If an eligible account exists, an email will arrive shortly."
)


def _now():
    return datetime.utcnow()


def _client_ip():
    peer = request.remote_addr or 'unknown'
    if os.environ.get('FLY_APP_NAME'):
        fly_peer = request.headers.get('Fly-Client-IP', '').strip()
        try:
            peer = str(ipaddress.ip_address(fly_peer))
        except ValueError:
            pass
    return peer


def _generate_mil_code():
    return ''.join(secrets.choice(_MIL_CODE_ALPHABET) for _ in range(8))


def _payload():
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else {}


def _normalize_email(value):
    return str(value or '').strip().casefold()


def _valid_email(email):
    # The deployed User.email column is VARCHAR(120); keep validation aligned
    # until a managed schema migration expands it.
    return 3 <= len(email) <= 120 and EMAIL_PATTERN.fullmatch(email) is not None


def _password_error(password):
    if not isinstance(password, str):
        return "Password is required."
    if len(password) < PASSWORD_MIN_LENGTH:
        return f"Password must be at least {PASSWORD_MIN_LENGTH} characters."
    if len(password) > PASSWORD_MAX_LENGTH:
        return f"Password must be at most {PASSWORD_MAX_LENGTH} characters."
    compact = re.sub(r'\s+', '', password.casefold())
    if compact in COMMON_WEAK_PASSWORDS:
        return "Choose a less common password."
    return None


def _pending_password_hash():
    """Return a valid hash whose random password is never disclosed or stored."""

    return generate_password_hash(secrets.token_urlsafe(48), method='scrypt')


def _rate_limit(scope, email=None):
    limits = {
        'login': ((30, 600), (10, 600)),
        'register': ((10, 3600), (5, 3600)),
        'resend': ((10, 3600), (3, 3600)),
        'forgot': ((10, 3600), (5, 3600)),
        'google': ((30, 600), None),
        'reset': ((20, 3600), None),
        'mil_request': ((10, 3600), None),
        'mil_verify': ((20, 600), None),
    }
    ip_rule, email_rule = limits[scope]
    # Fly injects Fly-Client-IP at its trusted edge. Only honor it when this
    # process is actually running as a Fly app; locally, use the WSGI peer and
    # never trust caller-supplied X-Forwarded-For.
    peer = request.remote_addr or 'unknown'
    if os.environ.get('FLY_APP_NAME'):
        fly_peer = request.headers.get('Fly-Client-IP', '').strip()
        try:
            peer = str(ipaddress.ip_address(fly_peer))
        except ValueError:
            pass
    rules = [(f'{scope}:ip', peer, ip_rule[0], ip_rule[1])]
    if email and email_rule:
        rules.append((f'{scope}:email', email, email_rule[0], email_rule[1]))
    retry_after = check_rate_limits(rules)
    if not retry_after:
        return None

    response = jsonify({
        "status": "error",
        "code": "rate_limited",
        "message": "Too many attempts. Try again later.",
        "error": "Too many attempts. Try again later.",
    })
    response.status_code = 429
    response.headers['Retry-After'] = str(retry_after)
    return response


def _find_user_by_email(email):
    return User.query.filter(func.lower(User.email) == email).first()


def _is_google_identity(user):
    return bool(user.google_id and not user.google_id.startswith('local:'))


def _serialize_user(user):
    credential = user.local_credential
    return {
        "id": user.id,
        "email": user.email,
        "name": user.name,
        "picture": user.picture,
        "email_verified": (
            credential.email_verified_at is not None
            if credential is not None
            else _is_google_identity(user)
        ),
        "account_status": credential.status if credential else "active",
        "has_password": credential is not None,
        "role": user.role or "user",
        "is_admin": is_admin(user),
        "is_active": account_active(user),
        "features": resolve_features(user),
        "mil_email": user.mil_email,
        "affiliation_verified": user.mil_verified_at is not None,
        "access_ok": affiliation_ok(user),
    }


def _token_hash(raw_token):
    return hashlib.sha256(raw_token.encode('utf-8')).hexdigest()


def _invalidate_tokens(user_id, purpose, used_at=None):
    AccountToken.query.filter_by(
        user_id=user_id,
        purpose=purpose,
        used_at=None,
    ).update({"used_at": used_at or _now()}, synchronize_session=False)


def _create_account_token(user, purpose, lifetime):
    now = _now()
    _invalidate_tokens(user.id, purpose, now)
    raw_token = secrets.token_urlsafe(32)
    db.session.add(AccountToken(
        user_id=user.id,
        purpose=purpose,
        token_hash=_token_hash(raw_token),
        expires_at=now + lifetime,
    ))
    return raw_token


def _find_valid_token(raw_token, purpose):
    if not isinstance(raw_token, str) or not raw_token:
        return None
    return AccountToken.query.filter(
        AccountToken.token_hash == _token_hash(raw_token),
        AccountToken.purpose == purpose,
        AccountToken.used_at.is_(None),
        AccountToken.expires_at > _now(),
    ).first()


def _auth_success(user, method='password'):
    # The configured super-admin is always an active admin; enforce it on every
    # sign-in so the account can't be locked out via the database or dashboard.
    if is_super_admin(user):
        changed = False
        if user.role != 'admin':
            user.role = 'admin'
            changed = True
        if user.is_active is not True:
            user.is_active = True
            changed = True
        if changed:
            db.session.commit()
    elif not account_active(user):
        # Suspended by an admin — refuse to mint a token for any auth method.
        return _error("This account is not available.", 403, "account_unavailable")

    # Record the successful sign-in for the admin per-user login history.
    try:
        db.session.add(LoginEvent(
            user_id=user.id,
            method=method,
            ip=_client_ip(),
            user_agent=(request.headers.get('User-Agent') or '')[:400] or None,
        ))
        db.session.commit()
    except Exception:  # noqa: BLE001 — history is best-effort, never blocks login
        db.session.rollback()

    credential = user.local_credential
    return jsonify({
        "status": "success",
        "access_token": create_access_token(
            identity=str(user.id),
            additional_claims={
                "sv": credential.session_version if credential else 0,
            },
        ),
        "user": _serialize_user(user),
    })


def _error(message, status_code=400, code=None):
    body = {"status": "error", "message": message, "error": message}
    if code:
        body["code"] = code
    return jsonify(body), status_code


@auth_bp.route('/api/auth/register', methods=['POST'])
def register():
    body = _payload()
    name = str(body.get('name') or '').strip()
    email = _normalize_email(body.get('email'))

    limited = _rate_limit('register', email)
    if limited:
        return limited

    if not name or len(name) > 120:
        return _error("Enter a name between 1 and 120 characters.")
    if not _valid_email(email):
        return _error("Enter a valid email address.")
    raw_token = None
    try:
        user = _find_user_by_email(email)
        if user is None:
            # The compatibility subject satisfies older deployed schemas where
            # google_id is still NOT NULL. It is replaced if Google is linked.
            user = User(
                google_id=f"local:{uuid.uuid4().hex}",
                email=email,
                name=name,
            )
            db.session.add(user)
            db.session.flush()
            user.local_credential = LocalCredential(
                password_hash=_pending_password_hash(),
                status='pending_email',
            )
            raw_token = _create_account_token(
                user, VERIFY_PURPOSE, timedelta(hours=24)
            )
        elif user.local_credential is None and not _is_google_identity(user):
            # Recover an incomplete local record. Passwords for established
            # Google accounts may only be added from a future authenticated
            # account-settings flow; public registration must not attach one.
            user.local_credential = LocalCredential(
                password_hash=_pending_password_hash(),
                status='pending_email',
            )
            db.session.flush()
            raw_token = _create_account_token(
                user, VERIFY_PURPOSE, timedelta(hours=24)
            )
        elif (
            user.local_credential is not None
            and user.local_credential.status == 'pending_email'
        ):
            # Registration is email-first: no caller-chosen password exists
            # until verification, so safely rotate the pending link.
            user.name = name
            raw_token = _create_account_token(
                user, VERIFY_PURPOSE, timedelta(hours=24)
            )

        db.session.commit()
    except IntegrityError:
        # A concurrent request may have created the account first. Preserve the
        # generic response so this endpoint cannot enumerate registered users.
        db.session.rollback()
        current_app.logger.info("Concurrent or duplicate registration for %s", email)
        raw_token = None

    if raw_token:
        send_verification_email(user, raw_token)

    return jsonify({
        "status": "success",
        "message": GENERIC_REGISTER_MESSAGE,
        "requires_verification": True,
    }), 202


@auth_bp.route('/api/auth/login', methods=['POST'])
def password_login():
    body = _payload()
    email = _normalize_email(body.get('email'))
    password = body.get('password')
    limited = _rate_limit('login', email)
    if limited:
        return limited
    user = _find_user_by_email(email) if _valid_email(email) else None
    credential = user.local_credential if user else None
    password_for_check = password if isinstance(password, str) else ''
    password_matches = check_password_hash(
        credential.password_hash if credential else DUMMY_PASSWORD_HASH,
        password_for_check,
    )

    if (
        credential is None
        or not isinstance(password, str)
        or not password_matches
    ):
        return _error("Invalid email or password.", 401, "invalid_credentials")

    # Use the same response as an unknown account. Revealing that an address is
    # specifically awaiting verification would allow account enumeration.
    if credential.status == 'pending_email' or credential.email_verified_at is None:
        return _error("Invalid email or password.", 401, "invalid_credentials")

    if credential.status != 'active':
        return _error("This account is not available.", 403, "account_unavailable")

    credential.last_login_at = _now()
    db.session.commit()
    return _auth_success(user)


@auth_bp.route('/api/auth/verify-email', methods=['POST'])
def verify_email():
    body = _payload()
    token = _find_valid_token(body.get('token'), VERIFY_PURPOSE)
    if token is None or token.user.local_credential is None:
        return _error(
            "This verification link is invalid or has expired.",
            400,
            "invalid_token",
        )
    password_error = _password_error(body.get('password'))
    if password_error:
        return _error(password_error)

    now = _now()
    credential = token.user.local_credential
    credential.password_hash = generate_password_hash(
        body['password'], method='scrypt'
    )
    credential.email_verified_at = now
    credential.status = 'active'
    token.used_at = now
    _invalidate_tokens(token.user_id, VERIFY_PURPOSE, now)
    db.session.commit()

    send_welcome_email(token.user)
    send_new_account_notification(token.user)
    return jsonify({
        "status": "success",
        "message": "Email verified. You can now sign in.",
    })


@auth_bp.route('/api/auth/resend-verification', methods=['POST'])
def resend_verification():
    email = _normalize_email(_payload().get('email'))
    limited = _rate_limit('resend', email)
    if limited:
        return limited
    user = _find_user_by_email(email) if _valid_email(email) else None
    credential = user.local_credential if user else None
    raw_token = None

    if credential is not None and credential.status == 'pending_email':
        latest = AccountToken.query.filter_by(
            user_id=user.id,
            purpose=VERIFY_PURPOSE,
        ).order_by(AccountToken.created_at.desc()).first()
        if latest is None or (_now() - latest.created_at) >= timedelta(seconds=60):
            raw_token = _create_account_token(
                user, VERIFY_PURPOSE, timedelta(hours=24)
            )
            db.session.commit()

    if raw_token:
        send_verification_email(user, raw_token)
    return jsonify({"status": "success", "message": GENERIC_REGISTER_MESSAGE}), 202


@auth_bp.route('/api/auth/forgot-password', methods=['POST'])
def forgot_password():
    email = _normalize_email(_payload().get('email'))
    limited = _rate_limit('forgot', email)
    if limited:
        return limited
    user = _find_user_by_email(email) if _valid_email(email) else None
    credential = user.local_credential if user else None
    raw_token = None

    if (
        credential is not None
        and credential.status == 'active'
        and credential.email_verified_at is not None
    ):
        raw_token = _create_account_token(
            user, RESET_PURPOSE, timedelta(minutes=60)
        )
        db.session.commit()

    if raw_token:
        send_password_reset_email(user, raw_token)
    return jsonify({"status": "success", "message": GENERIC_RESET_MESSAGE}), 202


@auth_bp.route('/api/auth/reset-password', methods=['POST'])
def reset_password():
    body = _payload()
    limited = _rate_limit('reset')
    if limited:
        return limited
    password_error = _password_error(body.get('password'))
    if password_error:
        return _error(password_error)

    token = _find_valid_token(body.get('token'), RESET_PURPOSE)
    if token is None or token.user.local_credential is None:
        return _error(
            "This password reset link is invalid or has expired.",
            400,
            "invalid_token",
        )

    now = _now()
    token.user.local_credential.password_hash = generate_password_hash(
        body['password'], method='scrypt'
    )
    token.user.local_credential.session_version = (
        token.user.local_credential.session_version or 0
    ) + 1
    token.used_at = now
    _invalidate_tokens(token.user_id, RESET_PURPOSE, now)
    db.session.commit()
    send_password_changed_email(token.user)
    return jsonify({
        "status": "success",
        "message": "Password updated. You can now sign in.",
    })


@auth_bp.route('/api/auth/google', methods=['POST'])
def google_auth():
    limited = _rate_limit('google')
    if limited:
        return limited
    token = _payload().get('token')
    client_id = current_app.config.get('GOOGLE_CLIENT_ID') or os.environ.get(
        'GOOGLE_CLIENT_ID'
    )
    if not token or not client_id:
        return _error("Google sign-in is not configured.", 503)

    try:
        idinfo = id_token.verify_oauth2_token(
            token, google_requests.Request(), client_id
        )
        verified_claim = idinfo.get('email_verified')
        email_is_verified = verified_claim is True or str(
            verified_claim
        ).lower() == 'true'
        email = _normalize_email(idinfo.get('email'))
        google_id = str(idinfo.get('sub') or '')
        if not google_id or not _valid_email(email) or not email_is_verified:
            raise ValueError("Google email is not verified")

        name = str(idinfo.get('name') or 'Pilot').strip()[:120] or 'Pilot'
        picture = idinfo.get('picture')

        created_google_user = False
        activated_pending_google = False
        user = User.query.filter_by(google_id=google_id).first()
        if user is None:
            user = _find_user_by_email(email)
            if user is not None:
                if _is_google_identity(user) and user.google_id != google_id:
                    db.session.rollback()
                    return _error("Unable to link this Google account.", 409)
                user.google_id = google_id
            else:
                user = User(
                    email=email,
                    name=name,
                    google_id=google_id,
                    picture=picture,
                )
                db.session.add(user)
                created_google_user = True

        credential = user.local_credential
        if credential is not None:
            if credential.status == 'suspended':
                db.session.rollback()
                return _error(
                    "This account is not available.", 403, "account_unavailable"
                )
            if (
                credential.status == 'pending_email'
                and credential.email_verified_at is None
            ):
                # A third party can pre-register a victim's email with an
                # attacker-chosen password. A later verified Google login must
                # discard that unproven credential, never activate it.
                _invalidate_tokens(user.id, VERIFY_PURPOSE)
                db.session.delete(credential)
                user.name = name
                activated_pending_google = True
            else:
                credential.last_login_at = _now()

        if picture and user.picture != picture:
            user.picture = picture
        db.session.commit()
        if created_google_user or activated_pending_google:
            send_welcome_email(user)
            send_new_account_notification(user)
        return _auth_success(user, 'google')
    except ValueError:
        db.session.rollback()
        return _error("Invalid Google token.", 401, "invalid_google_token")
    except IntegrityError:
        db.session.rollback()
        return _error("Unable to link this Google account.", 409)
    except Exception:
        db.session.rollback()
        current_app.logger.exception("Google authentication failed")
        return _error("Google authentication is temporarily unavailable.", 503)


@auth_bp.route('/api/auth/mil/request', methods=['POST'])
@jwt_required()
def mil_request():
    """Email a short affiliation-verification code to the caller's .mil address."""
    user = db.session.get(User, int(get_jwt_identity()))
    if not user:
        return _error("User not found.", 404)
    limited = _rate_limit('mil_request')
    if limited:
        return limited

    email = _normalize_email(_payload().get('email'))
    if len(email) > 120 or not MIL_EMAIL_PATTERN.fullmatch(email):
        return _error("Enter a valid .mil email address.")

    # A .mil address can clear only one account.
    taken = User.query.filter(
        func.lower(User.mil_email) == email,
        User.mil_verified_at.isnot(None),
        User.id != user.id,
    ).first()
    if taken:
        return _error("That .mil address is already verified on another account.", 409)

    user.mil_email = email  # pending until the code is confirmed
    code = _generate_mil_code()
    _invalidate_tokens(user.id, MIL_VERIFY_PURPOSE)
    db.session.add(AccountToken(
        user_id=user.id,
        purpose=MIL_VERIFY_PURPOSE,
        token_hash=_token_hash(code),
        expires_at=_now() + timedelta(minutes=30),
    ))
    db.session.commit()
    send_mil_verification_email(email, code, user.name)
    return jsonify({
        "status": "success",
        "message": "A verification code was sent to your .mil address.",
    }), 202


@auth_bp.route('/api/auth/mil/verify', methods=['POST'])
@jwt_required()
def mil_verify():
    """Confirm the emailed code and mark the account military-affiliation verified."""
    user = db.session.get(User, int(get_jwt_identity()))
    if not user:
        return _error("User not found.", 404)
    limited = _rate_limit('mil_verify')
    if limited:
        return limited

    code = str(_payload().get('code') or '').strip().upper()
    token = _find_valid_token(code, MIL_VERIFY_PURPOSE)
    if token is None or token.user_id != user.id:
        return _error("That code is invalid or has expired.", 400, "invalid_code")

    if user.mil_email:
        taken = User.query.filter(
            func.lower(User.mil_email) == user.mil_email.lower(),
            User.mil_verified_at.isnot(None),
            User.id != user.id,
        ).first()
        if taken:
            return _error("That .mil address is already verified on another account.", 409)

    now = _now()
    user.mil_verified_at = now
    token.used_at = now
    _invalidate_tokens(user.id, MIL_VERIFY_PURPOSE, now)
    db.session.commit()
    return jsonify({"status": "success", "user": _serialize_user(user)})


@auth_bp.route('/api/auth/me', methods=['GET'])
@jwt_required()
def me():
    user = db.session.get(User, int(get_jwt_identity()))
    if not user:
        return _error("User not found.", 404)
    if user.local_credential and user.local_credential.status == 'suspended':
        return _error("This account is not available.", 403)
    if not account_active(user):
        return _error("This account is not available.", 403, "account_unavailable")
    return jsonify(_serialize_user(user))
