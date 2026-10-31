"""The server-side military-affiliation gate, as a function the app and the tests share.

``app.py`` installs it as a ``before_request`` hook. It lives here so a small app (a test, or the
live server the native apps' integration tests run against) can install the same gate instead of a
copy of it.
"""

from flask import jsonify, request
from flask_jwt_extended import get_jwt_identity, verify_jwt_in_request

from entitlements import affiliation_ok
from models import User, db


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
