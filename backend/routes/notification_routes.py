"""Notifications: what a person's devices say, and which they want (docs/NOTIFICATIONS.md §3.3).

``GET /api/notifications`` answers the outbox notify.py writes, as it holds now: a row is shown only while what it
says is still so (an invitation still waiting for them, a pack they are still in, still finished, still theirs), under
the names the pack and the team have now. There is no cursor: the list is short (a few a week, 30 days), and a device
keeps which ids it has shown, so a finish whose two minutes end after a later row was read is never missed. It
answers an ``ETag`` of the body, so a poll that finds nothing new is a 304 of a few hundred bytes. A device takes
down what is seen or no longer listed.

The routes need a token and the .mil gate, as every route does, but not ``mission_packs``: a person without it is
answered an empty list, since every notification is about packs and teams.
"""

import hashlib
import json
import threading
from datetime import timedelta

from flask import Blueprint, make_response, request
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy import update

import notify
import pack_support as packs
from entitlements import has_feature
from models import MissionPack, MissionPackInvite, MissionPackMember, Notification, Team, TeamMember, User, db
from routes.pack_routes import _error, _reply

notification_bp = Blueprint('notifications', __name__)

MAX_FEED = 100
MAX_ROWS = 1000          # read before what is no longer so is passed over, so that cannot crowd out what is
MAX_IDS = 200
SWEEP_EVERY = timedelta(hours=1)

_sweep_lock = threading.Lock()
_swept_at = None


def _me():
    return db.session.get(User, int(get_jwt_identity()))


def settings_of(user):
    """Each group on unless the person turned it off; anything stored that is not ``false`` is on."""
    stored = user.notification_settings if isinstance(user.notification_settings, dict) else {}
    return {group: stored.get(group) is not False for group in notify.SETTINGS}


def _sweep():
    """Delete everyone's notifications older than 30 days, at most once an hour, from whichever request comes."""
    global _swept_at
    moment = notify.now()
    if not _sweep_lock.acquire(blocking=False):
        return
    try:
        if _swept_at is not None and moment - _swept_at < SWEEP_EVERY:
            return
        Notification.query.filter(Notification.created_at <= moment - notify.KEPT_FOR).delete(synchronize_session=False)
        db.session.commit()
        _swept_at = moment
    except Exception:  # noqa: BLE001 — a sweep that fails is tried at the next feed; the feed itself must answer
        db.session.rollback()
    finally:
        _sweep_lock.release()


def _still_so(rows, user):
    """``(row, pack, team, role)`` for each row whose news still holds, with the pack and team as they are now."""
    moment = notify.now()
    uuids = {r.pack_uuid for r in rows if r.pack_uuid}
    live_packs = {p.uuid: p for p in MissionPack.query.filter(MissionPack.uuid.in_(uuids),
                                                              MissionPack.deleted_at.is_(None)).all()} if uuids else {}
    ids = {r.invite_id for r in rows if r.invite_id is not None}
    invites = {i.id: i for i in MissionPackInvite.query.filter(MissionPackInvite.id.in_(ids)).all()} if ids else {}
    team_ids = {i.team_id for i in invites.values() if i.team_id}
    teams = {t.id: t for t in Team.query.filter(Team.id.in_(team_ids)).all()} if team_ids else {}
    mine = packs.addresses(user)
    # Someone already in what they are invited to (added by name meanwhile) has nothing to answer.
    in_packs = {row.pack_id for row in db.session.query(MissionPackMember.pack_id).filter_by(user_id=user.id)}
    in_teams = {row.team_id for row in db.session.query(TeamMember.team_id).filter_by(user_id=user.id)}
    roles = {}

    def role(pack):
        if pack.id not in roles:
            roles[pack.id] = packs.role_of(pack, user.id)
        return roles[pack.id]

    for row in rows:
        pack = live_packs.get(row.pack_uuid)
        if row.type in ('pack_invite', 'team_invite'):
            invite = invites.get(row.invite_id)
            if invite is None or invite.status != 'pending' or invite.expires_at <= moment or invite.email not in mine:
                continue
            team = teams.get(invite.team_id)
            if row.type == 'pack_invite' and (pack is None or invite.pack_id != pack.id or pack.id in in_packs):
                continue
            if row.type == 'team_invite' and (team is None or team.id in in_teams):
                continue
            yield row, pack, team, invite.role
        elif pack is None or role(pack) is None:
            continue
        elif row.type == 'pack_finished' and pack.status != 'finished':
            continue
        elif row.type == 'pack_owner' and pack.owner_id != user.id:
            continue
        else:
            yield row, pack, None, (row.fields or {}).get('role') if row.type == 'pack_added' else None


def _body(row, pack, team, role):
    actor = (row.fields or {}).get('actor')
    return {
        'id': row.id,
        'type': row.type,
        'group': notify.GROUPS[row.type],
        'key': row.collapse_key,
        'pack': {'uuid': pack.uuid, 'name': pack.name} if pack is not None else None,
        'team': {'id': team.id, 'name': team.name} if team is not None else None,
        'actor': {'name': str(actor.get('name') or '')} if isinstance(actor, dict) else None,
        'invite_id': row.invite_id,
        'role': role,
        'created_at': packs.iso(row.created_at),
        'updated_at': packs.iso(row.updated_at),
        'seen': row.seen_at is not None,
        'quiet': bool(row.quiet),
    }


def feed(user):
    """The person's notifications as their devices should show them, newest first."""
    if not has_feature(user, 'mission_packs'):
        return []
    moment = notify.now()
    rows = (Notification.query.filter(
        Notification.user_id == user.id, Notification.withdrawn_at.is_(None), Notification.deliver_after <= moment,
        Notification.created_at > moment - notify.KEPT_FOR, Notification.type.in_(tuple(notify.GROUPS)))
        .order_by(Notification.created_at.desc(), Notification.id.desc()).limit(MAX_ROWS).all())
    return [_body(*found) for found in _still_so(rows, user)][:MAX_FEED]


@notification_bp.route('/api/notifications', methods=['GET'])
@jwt_required()
def list_notifications():
    _sweep()
    me = _me()
    body = {'notifications': feed(me), 'settings': settings_of(me)}
    tag = '"' + hashlib.sha256(json.dumps(body, sort_keys=True, separators=(',', ':')).encode()).hexdigest()[:32] + '"'
    # Compared weakly (RFC 9110 §13.1.2): a proxy that compresses the body, as Cloudflare does, hands the tag on as W/"…".
    asked = [t.strip().removeprefix('W/') for t in request.headers.get('If-None-Match', '').split(',')]
    if tag in asked:
        response = make_response('', 304)
        response.headers['Cache-Control'] = 'private, no-store'
    else:
        response = _reply(body)
    response.headers['ETag'] = tag
    return response


@notification_bp.route('/api/notifications/seen', methods=['POST'])
@jwt_required()
def mark_seen():
    """Seen on one device, so taken down on every other. Ids that are not the caller's are passed over."""
    body = request.get_json(silent=True)
    ids = body.get('ids') if isinstance(body, dict) else None
    if (not isinstance(ids, list) or len(ids) > MAX_IDS
            or any(not isinstance(i, int) or isinstance(i, bool) for i in ids)):
        return _error(f'ids must be a list of at most {MAX_IDS} notification ids.', 400, 'invalid_ids')
    me = _me()
    mine = [row.id for row in db.session.query(Notification.id).filter(
        Notification.user_id == me.id, Notification.id.in_(set(ids)))] if ids else []
    if mine:
        Notification.query.filter(Notification.id.in_(mine), Notification.seen_at.is_(None)).update(
            {Notification.seen_at: notify.now()}, synchronize_session=False)
        db.session.commit()
    return _reply({'ids': sorted(mine)})


@notification_bp.route('/api/notifications/settings', methods=['PUT'])
@jwt_required()
def change_settings():
    """Turn groups on or off for every device. A group this server does not know is passed over; the answer says what holds."""
    body = request.get_json(silent=True)
    if not isinstance(body, dict) or any(not isinstance(body[g], bool) for g in notify.SETTINGS if g in body):
        return _error('Send each setting as true or false.', 400, 'invalid_settings')
    me = _me()
    # The row first, as pack_support.lock takes a pack's: two devices turning off two groups at once both stand.
    db.session.execute(update(User).where(User.id == me.id).values(notification_settings=User.notification_settings)
                       .execution_options(synchronize_session=False))
    db.session.refresh(me)
    changed = {**settings_of(me), **{g: body[g] for g in notify.SETTINGS if g in body}}
    me.notification_settings = changed
    db.session.commit()
    return _reply({'settings': settings_of(me)})
