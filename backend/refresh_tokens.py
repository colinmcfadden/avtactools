"""Refresh tokens for the native apps: staying signed in for days with no signal.

A crew planning at a FARP cannot be asked to sign in every 24 hours, and the
access token (a JWT) lives that long. The Android and iOS apps are also given a
refresh token, which buys a new access token without a password.

Design, and why:

* **Native clients only.** A refresh token lives 30 days, far longer than the web
  app's 24 h token, and the web keeps its token in ``localStorage`` where any
  script can read it. So only a client that identifies itself as android or ios
  (``X-EZPZ-Client``) is issued one; the web's exposure does not change.
* **Rotating.** Each use spends the token and returns the next one, so a copied
  token is good once at most. The next ones form a *family*: one signed-in device.
* **Reuse detection.** A spent token coming back is either a lost response (the
  phone was in a dead zone) or a copy. Within ``REUSE_GRACE`` it is the former and
  the device is simply issued another; after that it is treated as theft and the
  whole family is revoked.
* **Stored hashed**, like every other account token (SHA-256 of a 384-bit random
  string), as ``AccountToken`` rows with purpose ``refresh``.
* **Sliding, with a cap.** Each refresh extends the life by 30 days, but a family
  ends 180 days after its first sign-in, so a device that is never signed out
  does not stay signed in for ever.
* **Immediate revoke.** The access tokens carry the family as ``sid`` and are
  refused as soon as the family has no live token (``token_revocation``), so
  "sign out my lost phone" does not wait up to a day for the access token to lapse.
"""

import hashlib
import secrets
import uuid
from datetime import datetime, timedelta

from client_header import client_label, parse_client
from models import AccountToken, db

REFRESH_PURPOSE = 'refresh'

REFRESH_LIFETIME = timedelta(days=30)
FAMILY_MAX_AGE = timedelta(days=180)

# A spent token presented again within this long is read as a lost response, not theft.
REUSE_GRACE = timedelta(seconds=30)

# Refresh rows are kept (spent ones too, to detect reuse) until they would have
# expired anyway, then dropped.
_PRUNE_AFTER_EXPIRY = timedelta(days=1)

NATIVE_PLATFORMS = ('android', 'ios')


def _hash(raw_token):
    return hashlib.sha256(raw_token.encode('utf-8')).hexdigest()


def native_client_label(request):
    """The app label (``android/1.4.0 (212)``) if this request is from a native app, else None."""
    parsed = parse_client(request.headers.get('X-EZPZ-Client'))
    if parsed is None or parsed['platform'] not in NATIVE_PLATFORMS:
        return None
    return client_label(request.headers.get('X-EZPZ-Client'))


def _new_row(user_id, family, client, session_version, now):
    raw = secrets.token_urlsafe(48)
    db.session.add(AccountToken(
        user_id=user_id,
        purpose=REFRESH_PURPOSE,
        token_hash=_hash(raw),
        expires_at=now + REFRESH_LIFETIME,
        family=family,
        client=client,
        session_version=session_version,
    ))
    return raw


def _prune(user_id, now):
    AccountToken.query.filter(
        AccountToken.user_id == user_id,
        AccountToken.purpose == REFRESH_PURPOSE,
        AccountToken.expires_at < now - _PRUNE_AFTER_EXPIRY,
    ).delete(synchronize_session=False)


def issue_family(user_id, session_version, client, now=None):
    """Start a new signed-in device. Returns ``(raw_token, family)``; the caller commits."""
    now = now or datetime.utcnow()
    _prune(user_id, now)
    family = uuid.uuid4().hex
    return _new_row(user_id, family, client, session_version, now), family


def find(raw_token, now=None):
    """The unexpired refresh row for a presented token, spent or not, else None."""
    now = now or datetime.utcnow()
    if not isinstance(raw_token, str) or not raw_token or len(raw_token) > 200:
        return None
    return AccountToken.query.filter(
        AccountToken.token_hash == _hash(raw_token),
        AccountToken.purpose == REFRESH_PURPOSE,
        AccountToken.expires_at > now,
    ).first()


def revoke_family(family, now=None):
    """End a device's session for good. The caller commits.

    Every token in the family, spent ones included, is expired, so ``find`` can
    never return one again. That matters: a *spent* token presented within the
    grace window is accepted as a lost response, so revoking by merely marking
    tokens spent would leave the latest one good for another 30 seconds.
    """
    if not family:
        return
    now = now or datetime.utcnow()
    in_family = (
        AccountToken.family == family,
        AccountToken.purpose == REFRESH_PURPOSE,
    )
    AccountToken.query.filter(*in_family, AccountToken.used_at.is_(None)).update(
        {'used_at': now}, synchronize_session=False
    )
    AccountToken.query.filter(*in_family, AccountToken.expires_at > now).update(
        {'expires_at': now}, synchronize_session=False
    )


def _drop_live_tokens(family, now):
    """Spend whatever live tokens a family holds, leaving the family itself alive.

    Used when a device repeats a request after losing the response: the token that
    response carried is retired and the device is given a fresh one.
    """
    AccountToken.query.filter(
        AccountToken.family == family,
        AccountToken.purpose == REFRESH_PURPOSE,
        AccountToken.used_at.is_(None),
    ).update({'used_at': now}, synchronize_session=False)


def family_is_live(family, now=None):
    """Whether the device still holds a usable token. False once it is signed out or revoked."""
    if not family:
        return False
    now = now or datetime.utcnow()
    return db.session.query(AccountToken.id).filter(
        AccountToken.family == family,
        AccountToken.purpose == REFRESH_PURPOSE,
        AccountToken.used_at.is_(None),
        AccountToken.expires_at > now,
    ).first() is not None


def _family_started(family):
    return db.session.query(db.func.min(AccountToken.created_at)).filter(
        AccountToken.family == family,
        AccountToken.purpose == REFRESH_PURPOSE,
    ).scalar()


def rotate(found, now=None):
    """Spend ``found`` and issue the next token in its family.

    Returns ``(raw_token, None)`` on success or ``(None, code)`` where ``code`` is
    ``refresh_reuse_detected`` or ``refresh_expired``. Either way the caller must
    commit, because a failure may have revoked the family.
    """
    now = now or datetime.utcnow()
    family = found.family

    # Spend it atomically: if two requests carry the same token at once, exactly
    # one wins here and the other takes the "already spent" path below.
    won = AccountToken.query.filter(
        AccountToken.id == found.id,
        AccountToken.used_at.is_(None),
    ).update({'used_at': now}, synchronize_session=False)
    db.session.refresh(found)

    if not won:
        spent_at = found.used_at or now
        if now - spent_at > REUSE_GRACE:
            revoke_family(family, now)
            return None, 'refresh_reuse_detected'
        # A lost response, not a copy: drop whatever that response was carrying
        # and give the device a fresh one.
        _drop_live_tokens(family, now)

    started = _family_started(family) or now
    if now - started > FAMILY_MAX_AGE:
        revoke_family(family, now)
        return None, 'refresh_expired'

    _prune(found.user_id, now)
    return _new_row(found.user_id, family, found.client, found.session_version, now), None


def list_sessions(user_id, current_family=None, now=None):
    """The user's signed-in devices, newest activity first."""
    now = now or datetime.utcnow()
    rows = AccountToken.query.filter_by(user_id=user_id, purpose=REFRESH_PURPOSE).all()
    families = {}
    for row in rows:
        entry = families.setdefault(row.family, {'rows': []})
        entry['rows'].append(row)

    sessions = []
    for family, entry in families.items():
        rows = entry['rows']
        if not any(r.used_at is None and r.expires_at > now for r in rows):
            continue                                          # signed out, revoked or lapsed
        newest = max(rows, key=lambda r: r.created_at)
        sessions.append({
            'id': family,
            'client': newest.client,
            'created_at': _iso(min(r.created_at for r in rows)),
            'last_active_at': _iso(newest.created_at),
            'current': family == current_family,
        })
    sessions.sort(key=lambda s: s['last_active_at'], reverse=True)
    return sessions


def _iso(moment):
    return moment.replace(microsecond=0).isoformat() + 'Z'
