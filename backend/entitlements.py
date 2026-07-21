"""Per-user feature entitlements and the super-admin bootstrap.

Feature access is stored per user as a JSON map of ``{feature_key: bool}`` on
``User.features``. A missing key means enabled, so existing users and any
newly-introduced feature default to ON and no one is locked out by surprise.
Admins always have every feature. The single super-admin (configured by the
``SUPER_ADMIN_EMAIL`` environment variable) is always an active admin and can
never be demoted or suspended.
"""

import os

# Canonical feature keys and human labels for the admin UI. Add new keys here;
# they default to enabled for everyone until an admin turns one off.
FEATURES = (
    ("lz_pz_tools", "LZ/PZ tools", "Terrain analysis and LZ/PZ diagram tools"),
    ("routes", "Routes", "Sketch and plan routes"),
    ("msnx_import", "Import .msnx", "Import AMPS mission files"),
    ("threats", "Threats", "Threat planning and masks"),
    ("cloud_save", "Cloud save", "Save/load LZs, routes, and point sets"),
    ("exports", "Exports", "Export LZ cards, MSNX, ForeFlight, KMZ, .ths"),
)

FEATURE_KEYS = tuple(key for key, _label, _desc in FEATURES)


def super_admin_email():
    return (os.environ.get("SUPER_ADMIN_EMAIL") or "").strip().casefold()


def is_super_admin(user):
    if user is None:
        return False
    target = super_admin_email()
    return bool(target) and (user.email or "").strip().casefold() == target


def is_admin(user):
    return user is not None and (getattr(user, "role", "user") == "admin" or is_super_admin(user))


def account_active(user):
    if user is None:
        return False
    if is_super_admin(user):
        return True
    # Column may be absent/None on rows created before the migration; treat as
    # active so a missing value never blocks sign-in.
    active = getattr(user, "is_active", True)
    return True if active is None else bool(active)


def resolve_features(user):
    """Effective ``{key: bool}`` entitlement map for a user."""
    if user is None:
        return {key: False for key in FEATURE_KEYS}
    if is_admin(user):
        return {key: True for key in FEATURE_KEYS}
    stored = user.features if isinstance(getattr(user, "features", None), dict) else {}
    return {key: bool(stored.get(key, True)) for key in FEATURE_KEYS}


def has_feature(user, key):
    return resolve_features(user).get(key, False)


def require_feature(key):
    """Decorator for JWT-protected routes: 403 unless the caller has ``key``.

    Place it directly below ``@jwt_required()`` so the identity is available.
    Server-side backstop for the UI gating — a non-entitled user can't reach the
    action by calling the API directly.
    """
    from functools import wraps

    def decorator(view):
        @wraps(view)
        def wrapped(*args, **kwargs):
            from flask import jsonify
            from flask_jwt_extended import get_jwt_identity
            from models import db, User
            try:
                user = db.session.get(User, int(get_jwt_identity()))
            except (TypeError, ValueError):
                user = None
            if not has_feature(user, key):
                return jsonify({
                    "error": "This feature isn't enabled for your account.",
                    "code": "feature_disabled",
                }), 403
            return view(*args, **kwargs)
        return wrapped

    return decorator
