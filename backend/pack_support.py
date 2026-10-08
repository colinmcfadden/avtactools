"""Mission packs and teams: who may do what, the per-pack change order, and the shapes the routes return.

The rules (docs/MISSION_PACKS.md has the reasons):

* **Roles.** A pack has one owner (finish, reopen, delete, members, invites,
  sharing), editors and viewers. Someone in a team the pack is shared with has
  the pack's ``team_role`` unless they are a member in their own right.
* **One order per pack.** Every change, content or membership, takes the next
  number in the pack's ``head_seq`` while holding the pack's row, so a lower
  number never commits after a higher one. ``lock`` takes the row with an UPDATE
  first, as ``sync_support.next_seq`` does, which also serialises SQLite writers.
* **Finished means read-only for everyone**, the owner included, until the owner
  reopens it. Reading, exporting and copying out still work.
* **Nothing reaches back into the library.** Items are copies; a pack never edits
  or reveals someone's one-off records.
* **Threats are never part of a pack.** There is no item kind for them.
"""

import hashlib
import json
import logging
import os
import re
import secrets
from datetime import datetime, timedelta

from sqlalchemy import case, event, func, select, text, update
from sqlalchemy.orm import Session

from models import (
    MissionPack, MissionPackEvent, MissionPackInvite, MissionPackItem, MissionPackMember, MissionPackSeen,
    SavedLZ, SavedPointSet, SavedRoute, Team, TeamMember, User, db,
)

ROLES = ('owner', 'editor', 'viewer')
EDIT_ROLES = ('owner', 'editor')
GRANTABLE_ROLES = ('editor', 'viewer')
TEAM_ROLES = ('owner', 'admin', 'member')
TEAM_MANAGERS = ('owner', 'admin')

INVITE_LIFETIME = timedelta(days=14)
MAX_ITEM_BYTES = 5 * 1024 * 1024
MAX_BATCH = 200
MAX_PAGE = 500
DEFAULT_PAGE = 200
MAX_DESCRIPTION = 2000
MAX_SUMMARY = 300
SYSTEM_ACTOR = 'EZ-PZ'

LIBRARY_MODELS = {'lz': SavedLZ, 'route': SavedRoute, 'pointset': SavedPointSet}
# Everything a pack holds, which goes when the pack does (a deleted pack keeps only its tombstone row).
PACK_CONTENT = (MissionPackEvent, MissionPackItem, MissionPackInvite, MissionPackMember, MissionPackSeen)


def now():
    return datetime.utcnow()


def iso(moment):
    return moment.isoformat() if moment else None


# -- Access ---------------------------------------------------------------------

def live_pack(pack_uuid):
    if not isinstance(pack_uuid, str) or len(pack_uuid) > 36:
        return None
    return MissionPack.query.filter_by(uuid=pack_uuid, deleted_at=None).first()


def role_of(pack, user_id):
    """The caller's role in the pack, or None. Their own membership wins over the team's."""
    member = MissionPackMember.query.filter_by(pack_id=pack.id, user_id=user_id).first()
    if member:
        return member.role
    if pack.team_id and TeamMember.query.filter_by(team_id=pack.team_id, user_id=user_id).first():
        return pack.team_role
    return None


def visible_packs(user_id):
    """Every live pack the user is in, directly or through a team."""
    own = select(MissionPackMember.pack_id).where(MissionPackMember.user_id == user_id)
    teams = select(TeamMember.team_id).where(TeamMember.user_id == user_id)
    return MissionPack.query.filter(
        MissionPack.deleted_at.is_(None),
        MissionPack.id.in_(own) | MissionPack.team_id.in_(teams),
    )


def team_role_of(team_id, user_id):
    member = TeamMember.query.filter_by(team_id=team_id, user_id=user_id).first()
    return member.role if member else None


def share_a_team(user_id, other_id):
    mine = select(TeamMember.team_id).where(TeamMember.user_id == user_id)
    return TeamMember.query.filter(TeamMember.user_id == other_id, TeamMember.team_id.in_(mine)).first() is not None


# -- The pack's change order ----------------------------------------------------

def lock(pack):
    """Hold the pack's row until commit and read it fresh. Every write to a pack starts here."""
    db.session.execute(
        update(MissionPack).where(MissionPack.id == pack.id).values(head_seq=MissionPack.head_seq)
        .execution_options(synchronize_session=False)
    )
    db.session.refresh(pack)
    return pack


def actor_name(user):
    return (user.name or user.email or '')[:120] if user is not None else SYSTEM_ACTOR


def append(pack, user, payload, summary, item_uuid=None, client_op_id=None, status='applied', reason=None):
    """Add the next event to a locked pack. The caller commits."""
    pack.head_seq += 1
    pack.updated_at = now()
    row = MissionPackEvent(
        pack_id=pack.id, seq=pack.head_seq, user_id=user.id if user is not None else None,
        actor_name=actor_name(user), item_uuid=item_uuid, op_type=payload['type'], payload=payload,
        summary=(summary or '')[:MAX_SUMMARY], client_op_id=client_op_id, status=status, reason=reason,
        created_at=now(),
    )
    db.session.add(row)
    announce_event(pack, row)
    return row


def clean_summary(value):
    return value.strip()[:MAX_SUMMARY] if isinstance(value, str) else ''


# -- Shapes ---------------------------------------------------------------------

def names(user_ids):
    ids = {i for i in user_ids if i is not None}
    if not ids:
        return {}
    return {u.id: u.name for u in User.query.filter(User.id.in_(ids)).all()}


def person(user_id, known):
    return {'id': user_id, 'name': known.get(user_id, '')} if user_id is not None else None


def event_body(event):
    return {
        'seq': event.seq,
        'type': event.op_type,
        'item': event.item_uuid,
        'actor': {'id': event.user_id, 'name': event.actor_name},
        'summary': event.summary,
        'status': event.status,
        'reason': event.reason,
        'client_op_id': event.client_op_id,
        'op': event.payload,
        'created_at': iso(event.created_at),
    }


def original_state(item, viewer_id):
    """Whether the library record an item was copied from has moved on: only its owner may know.

    ``same``, ``changed`` or ``deleted`` for the person who copied it in; None for
    everyone else, since the original is their private record, and for a copy with no
    ``source_uuid`` (one made from a record saved before sync, before copying named it):
    looking that up would find whichever of their unnamed records came first.
    """
    if not item.source_kind or not item.source_uuid or item.created_by is None or item.created_by != viewer_id:
        return None
    model = LIBRARY_MODELS.get(item.source_kind)
    original = model.query.filter_by(user_id=viewer_id, client_uuid=item.source_uuid).first() if model else None
    if original is None or original.deleted_at is not None:
        return 'deleted'
    return 'changed' if (original.revision or 0) > (item.source_revision or 0) else 'same'


def item_body(item, viewer_id, known, full=True):
    body = {
        'uuid': item.uuid,
        'kind': item.kind,
        'name': item.name,
        'revision': item.revision,
        'seq': item.change_seq,
        'created_by': person(item.created_by, known),
        'updated_by': person(item.updated_by, known),
        'created_at': iso(item.created_at),
        'updated_at': iso(item.updated_at),
        'source': None if not item.source_kind else {
            'kind': item.source_kind,
            'uuid': item.source_uuid,
            'revision': item.source_revision,
            'original': original_state(item, viewer_id),
        },
    }
    if full:
        body['data'] = item.data
    return body


def team_brief(team_id, team_role=None):
    team = db.session.get(Team, team_id) if team_id else None
    if team is None:
        return None
    body = {'id': team.id, 'name': team.name, 'member_count': TeamMember.query.filter_by(team_id=team.id).count()}
    if team_role is not None:
        body['role'] = team_role
    return body


def item_counts(pack):
    """Live items by kind, every kind present: what a list of packs shows ("3 LZ/PZ · 2 routes")."""
    counts = dict.fromkeys(LIBRARY_MODELS, 0)
    rows = (db.session.query(MissionPackItem.kind, func.count(MissionPackItem.id))
            .filter(MissionPackItem.pack_id == pack.id, MissionPackItem.deleted_at.is_(None))
            .group_by(MissionPackItem.kind).all())
    for kind, count in rows:
        if kind in counts:
            counts[kind] = count
    return counts


def audience_count(pack):
    """Everyone who can open the pack, each once: its members, and the members of the team it is shared
    with. ``member_count`` is only the first, so a pack shared with an 18-person team would say 1."""
    people = select(MissionPackMember.user_id).where(MissionPackMember.pack_id == pack.id)
    if pack.team_id:
        people = people.union(select(TeamMember.user_id).where(TeamMember.team_id == pack.team_id))
    return db.session.execute(select(func.count()).select_from(people.subquery())).scalar()


def seen_seq(pack, user_id):
    row = MissionPackSeen.query.filter_by(pack_id=pack.id, user_id=user_id).first() if user_id is not None else None
    return row.seen_seq if row else 0


def mark_seen(pack, user_id, seq):
    """Record that ``user_id`` has looked at the pack up to event ``seq``. Never goes back, and never past
    ``head_seq``. The caller commits; a second writer racing on the first marker is the caller's to retry."""
    seq = max(0, min(seq, pack.head_seq))
    row = MissionPackSeen.query.filter_by(pack_id=pack.id, user_id=user_id).first()
    if row is None:
        row = MissionPackSeen(pack_id=pack.id, user_id=user_id, seen_seq=seq, seen_at=now())
        db.session.add(row)
    else:
        row.seen_seq = max(row.seen_seq, seq)
        row.seen_at = now()
    return row


def pack_summary(pack, role, user_id=None):
    """``user_id`` is the caller's: ``seen_seq`` is how far they have looked."""
    known = names([pack.owner_id, pack.finished_by])
    counts = item_counts(pack)
    return {
        'uuid': pack.uuid,
        'name': pack.name,
        'description': pack.description or '',
        'status': pack.status,
        'role': role,
        'owner': person(pack.owner_id, known),
        'team': team_brief(pack.team_id, pack.team_role),
        'head_seq': pack.head_seq,
        'seen_seq': seen_seq(pack, user_id),
        'member_count': MissionPackMember.query.filter_by(pack_id=pack.id).count(),
        'audience_count': audience_count(pack),
        'item_count': sum(counts.values()),
        'item_counts': counts,
        'finished_at': iso(pack.finished_at),
        'finished_by': person(pack.finished_by, known),
        'created_at': iso(pack.created_at),
        'updated_at': iso(pack.updated_at),
    }


def member_bodies(pack):
    members = (
        MissionPackMember.query.filter_by(pack_id=pack.id)
        .order_by(MissionPackMember.added_at, MissionPackMember.id).all()
    )
    users = {u.id: u for u in User.query.filter(User.id.in_([m.user_id for m in members])).all()} if members else {}
    seen = {s.user_id: s.seen_at for s in MissionPackSeen.query.filter_by(pack_id=pack.id).all()} if members else {}
    return [{
        'user_id': m.user_id,
        'name': users[m.user_id].name if m.user_id in users else '',
        'email': users[m.user_id].email if m.user_id in users else '',
        'role': m.role,
        'added_at': iso(m.added_at),
        'seen_at': iso(seen.get(m.user_id)),
    } for m in members]


def pack_full(pack, user_id, role):
    items = (
        MissionPackItem.query.filter_by(pack_id=pack.id, deleted_at=None)
        .order_by(MissionPackItem.created_at, MissionPackItem.id).all()
    )
    known = names([i.created_by for i in items] + [i.updated_by for i in items])
    return {
        **pack_summary(pack, role, user_id),
        'members': member_bodies(pack),
        'items': [item_body(i, user_id, known) for i in items],
        'live_url': live_url(),
    }


# -- Telling the live service ----------------------------------------------------
#
# Every change is announced to the live service (backend/realtime) with a Postgres
# NOTIFY sent inside the transaction that made it, so a change that rolls back is
# never announced and announcements arrive in commit order. A payload must stay
# under Postgres's 8,000-byte limit: an event too large to carry (an item made or
# replaced whole) is announced by its number alone, and clients fetch it from
# GET /api/packs/<uuid>/events. Elsewhere (SQLite) nothing is sent, and clients poll.

NOTIFY_CHANNEL = 'mission_pack_events'
NOTIFY_LIMIT = 7500
_QUEUE = 'mission_pack_announcements'
_LIVE_URL = re.compile(r'^wss?://[^\s]+\Z')
log = logging.getLogger(__name__)


def live_url():
    """Where clients open the live stream, or None (then they poll). ``REALTIME_PUBLIC_URL``, ws:// or wss:// only."""
    url = (os.environ.get('REALTIME_PUBLIC_URL') or '').strip()
    if not url:
        return None
    if not _LIVE_URL.match(url):
        log.warning('REALTIME_PUBLIC_URL must start ws:// or wss://; ignored')
        return None
    return url


def announce_event(pack, event_row):
    db.session.info.setdefault(_QUEUE, []).append(
        {'pack': pack.uuid, 'seq': event_row.seq, 'event': event_body(event_row)})


def announce_deleted(pack_uuid):
    db.session.info.setdefault(_QUEUE, []).append({'pack': pack_uuid, 'deleted': True})


def notification_payloads(queued):
    """The NOTIFY payloads for what a transaction changed, each under the size limit."""
    payloads = []
    for item in queued:
        payload = json.dumps(item, separators=(',', ':'))
        if 'event' in item and len(payload.encode('utf-8')) > NOTIFY_LIMIT:
            payload = json.dumps({'pack': item['pack'], 'seq': item['seq']}, separators=(',', ':'))
        payloads.append(payload)
    return payloads


def _emit(session, payloads):
    if session.get_bind().dialect.name != 'postgresql':
        return
    for payload in payloads:
        session.execute(text('SELECT pg_notify(:channel, :payload)'), {'channel': NOTIFY_CHANNEL, 'payload': payload})


@event.listens_for(Session, 'before_commit')
def _announce(session):
    queued = session.info.pop(_QUEUE, None)
    if queued:
        _emit(session, notification_payloads(queued))


@event.listens_for(Session, 'after_transaction_end')
def _forget(session, transaction):
    # A transaction that rolled back (or a request that ended without committing) announces nothing.
    if transaction.parent is None:
        session.info.pop(_QUEUE, None)


# -- Invites --------------------------------------------------------------------

def new_token():
    raw = secrets.token_urlsafe(32)
    return raw, token_hash(raw)


def token_hash(raw):
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()


def is_pending(invite):
    return invite.status == 'pending' and invite.expires_at > now()


def addresses(user):
    """The addresses an emailed invite reaches this person at: the sign-in one, and a verified .mil one."""
    found = {(user.email or '').strip().casefold()}
    if user.mil_verified_at is not None and user.mil_email:
        found.add(user.mil_email.strip().casefold())
    return found - {''}


def invite_body(invite):
    known = names([invite.invited_by])
    body = {
        'id': invite.id,
        'email': invite.email,
        'role': invite.role,
        'status': invite.status if invite.status != 'pending' or invite.expires_at > now() else 'expired',
        'invited_by': person(invite.invited_by, known),
        'expires_at': iso(invite.expires_at),
        'created_at': iso(invite.created_at),
    }
    if invite.pack_id:
        pack = db.session.get(MissionPack, invite.pack_id)
        body['pack'] = {'uuid': pack.uuid, 'name': pack.name} if pack and pack.deleted_at is None else None
        body['team'] = None
    else:
        team = db.session.get(Team, invite.team_id)
        body['pack'] = None
        body['team'] = {'id': team.id, 'name': team.name} if team else None
    return body


# -- When an account is deleted -------------------------------------------------

def _purge_pack(pack):
    """Delete a pack and everything in it, rows and all. Only when nobody else is in it."""
    announce_deleted(pack.uuid)
    for model in PACK_CONTENT:
        model.query.filter_by(pack_id=pack.id).delete(synchronize_session=False)
    db.session.delete(pack)


def _heir(pack, user_id):
    """Who takes a pack over: the longest-standing editor, else viewer, else someone in its team."""
    member = (
        MissionPackMember.query.filter(MissionPackMember.pack_id == pack.id, MissionPackMember.user_id != user_id)
        .order_by(case((MissionPackMember.role == 'editor', 0), else_=1), MissionPackMember.added_at, MissionPackMember.id)
        .first()
    )
    if member is not None:
        return member
    if pack.team_id:
        teammate = (
            TeamMember.query.filter(TeamMember.team_id == pack.team_id, TeamMember.user_id != user_id)
            .order_by(TeamMember.joined_at, TeamMember.id).first()
        )
        if teammate is not None:
            member = MissionPackMember(pack_id=pack.id, user_id=teammate.user_id, role='editor', added_at=now())
            db.session.add(member)
            return member
    return None


def release_account(user_id):
    """Hand on or delete what an account leaves in packs and teams, before the account itself goes.

    A pack it owns passes to the longest-standing editor (else viewer, else someone
    in the team it is shared with) and is deleted only when nobody else is in it.
    Its other memberships end, each logged. Its name stays in the log as written;
    every other reference to it is cleared. The caller deletes the user and commits.
    """
    for pack in MissionPack.query.filter_by(owner_id=user_id, deleted_at=None).all():
        lock(pack)
        heir = _heir(pack, user_id)
        if heir is None:
            _purge_pack(pack)
            continue
        heir.role = 'owner'
        pack.owner_id = heir.user_id
        heir_name = names([heir.user_id]).get(heir.user_id, '')
        append(pack, None, {'type': 'pack.transfer', 'user_id': heir.user_id, 'name': heir_name},
               f"{heir_name} owns the pack now: its owner's account was deleted.")
    # Deleted packs keep a tombstone row, which still names its owner.
    for pack in MissionPack.query.filter(MissionPack.owner_id == user_id, MissionPack.deleted_at.isnot(None)).all():
        _purge_pack(pack)

    MissionPackSeen.query.filter_by(user_id=user_id).delete(synchronize_session=False)
    leaving = names([user_id]).get(user_id, '')
    for membership in MissionPackMember.query.filter_by(user_id=user_id).all():
        pack = db.session.get(MissionPack, membership.pack_id)
        if pack is not None and pack.deleted_at is None:
            lock(pack)
            append(pack, None, {'type': 'member.remove', 'user_id': user_id, 'name': leaving},
                   f"{leaving} left: their account was deleted.")
        db.session.delete(membership)

    for team_member in TeamMember.query.filter_by(user_id=user_id, role='owner').all():
        heir = (
            TeamMember.query.filter(TeamMember.team_id == team_member.team_id, TeamMember.user_id != user_id)
            .order_by(case((TeamMember.role == 'admin', 0), else_=1), TeamMember.joined_at, TeamMember.id).first()
        )
        if heir is not None:
            heir.role = 'owner'
        else:
            delete_team(db.session.get(Team, team_member.team_id), None)
    TeamMember.query.filter_by(user_id=user_id).delete(synchronize_session=False)

    for model, columns in (
        (MissionPackEvent, ('user_id',)),
        (MissionPackItem, ('created_by', 'updated_by')),
        (MissionPack, ('finished_by',)),
        (MissionPackMember, ('added_by',)),
        (MissionPackInvite, ('invited_by', 'accepted_by')),
        (Team, ('created_by',)),
    ):
        for column in columns:
            model.query.filter(getattr(model, column) == user_id).update(
                {column: None}, synchronize_session=False)


def delete_team(team, actor):
    """Delete a team. Packs shared with it stop being shared, and each says so in its log."""
    if team is None:
        return
    for pack in MissionPack.query.filter_by(team_id=team.id, deleted_at=None).all():
        lock(pack)
        pack.team_id = None
        append(pack, actor, {'type': 'pack.share', 'team_id': None},
               f'The team "{team.name}" was deleted, so its members no longer see this pack through it.')
    MissionPack.query.filter(MissionPack.team_id == team.id).update({'team_id': None}, synchronize_session=False)
    MissionPackInvite.query.filter_by(team_id=team.id).delete(synchronize_session=False)
    TeamMember.query.filter_by(team_id=team.id).delete(synchronize_session=False)
    db.session.delete(team)
