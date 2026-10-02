"""Request-time access control: token revocation and the affiliation gate."""

from flask import jsonify, request
from flask_jwt_extended import get_jwt_identity, verify_jwt_in_request

from app.extensions import db, jwt
from app.models import User
from app.security.entitlements import account_active, affiliation_ok


def token_is_revoked(_jwt_header, jwt_payload):
    """Reject deleted/suspended users and JWTs predating a password reset."""

    try:
        user = db.session.get(User, int(jwt_payload.get('sub')))
    except (TypeError, ValueError):
        return True
    if user is None:
        return True
    # An admin-set suspension (is_active = False) revokes access immediately for
    # any auth method — the super-admin is exempt (account_active handles that).
    if not account_active(user):
        return True
    credential = user.local_credential
    if credential is not None and credential.status == 'suspended':
        return True
    expected_version = credential.session_version if credential else 0
    # Tokens issued before session versioning had no ``sv`` claim and could
    # otherwise retain the old 30-day lifetime after deployment.
    return 'sv' not in jwt_payload or jwt_payload.get('sv') != expected_version


def enforce_affiliation_gate():
    """Server-side military-affiliation gate.

    The frontend hides the app until a user clears the gate, but a signed-in
    user could otherwise call the API directly with their token. Refuse every
    ``/api/*`` request from an unverified, unapproved user — except the auth
    flows they still need (login, register, Google, /me, and .mil verification
    itself), which all live under ``/api/auth/``.
    """
    path = request.path
    if not path.startswith('/api/') or path.startswith('/api/auth/'):
        return None
    try:
        verify_jwt_in_request(optional=True)
        identity = get_jwt_identity()
    except Exception:  # noqa: BLE001 — bad/expired token: let the view's own guard answer
        return None
    if identity is None:
        return None  # anonymous request to a public endpoint — unchanged
    try:
        user = db.session.get(User, int(identity))
    except (TypeError, ValueError):
        return None
    if user is not None and not affiliation_ok(user):
        return jsonify({
            "error": "Military affiliation verification is required to use this feature.",
            "code": "affiliation_required",
        }), 403
    return None


def register_hooks(app):
    jwt.token_in_blocklist_loader(token_is_revoked)
    app.before_request(enforce_affiliation_gate)
