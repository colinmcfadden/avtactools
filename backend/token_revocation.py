"""Whether an access token may still be used. One place, so the app and its tests agree."""

from entitlements import account_active
from models import User, db
from refresh_tokens import family_is_live


def is_revoked(jwt_payload):
    """True for a token that must be refused.

    Refused when: its user is gone or suspended; it predates the account's last
    password reset (its session version no longer matches); or it belongs to a
    native device session (``sid``) that has since been signed out or revoked.
    """
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
    if 'sv' not in jwt_payload or jwt_payload.get('sv') != expected_version:
        return True
    # A native device's access token dies with its session, not 24 hours later.
    sid = jwt_payload.get('sid')
    if sid is not None and not family_is_live(sid):
        return True
    return False
