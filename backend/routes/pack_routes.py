"""Mission packs: the packs, the operation stream every member edits through, the log, items, members and invites.

Every write takes the pack's row first (pack_support.lock), so changes are numbered
in one order per pack and a reader of ``GET /api/packs/<uuid>`` sees the items as
of one ``head_seq``. A pack the caller is not in is a 404: its existence is not
revealed. Everything needs the ``mission_packs`` entitlement. See docs/MISSION_PACKS.md.
"""

import copy
import json
import re
import uuid as uuid_lib

from flask import Blueprint, jsonify, request
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm.attributes import flag_modified

import pack_ops
import pack_support as packs
import sync_support as sync
from auth_rate_limit import check_rate_limits
from email_service import send_pack_added_email, send_pack_invite_email
from entitlements import account_active, has_feature, require_feature
from models import (
    MissionPack, MissionPackEvent, MissionPackInvite, MissionPackItem, MissionPackMember,
    SavedLZ, SavedPointSet, SavedRoute, User, db,
)
from routes.auth import _normalize_email, _valid_email

pack_bp = Blueprint('packs', __name__)

_CLIENT_OP_ID = re.compile(r'^[\x21-\x7e]{1,64}\Z')       # printable ASCII, as an Idempotency-Key
_CONTROL = re.compile(r'[\x00-\x1f\x7f]')
_KIND_WORDS = {'lz': 'LZ', 'route': 'routes', 'pointset': 'points'}


# -- Helpers --------------------------------------------------------------------

def _error(message, status, code=None, **extra):
    body = {'error': message}
    if code:
        body['code'] = code
    body.update(extra)
    response = jsonify(body)
    response.status_code = status
    return response


def _reply(body, status=200):
    response = jsonify(body)
    response.status_code = status
    # One team's working data, and it changes: never cached, never shared by an edge.
    response.headers['Cache-Control'] = 'private, no-store'
    return response


def _body():
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else {}


def _me():
    return db.session.get(User, int(get_jwt_identity()))


def clean_name(value):
    """A pack's or a team's name: trimmed, 1-100 characters, no control characters (it goes in email subjects)."""
    if not isinstance(value, str):
        return None
    name = value.strip()
    if not name or len(name) > 100 or _CONTROL.search(name):
        return None
    return name


def _load(pack_uuid, user_id, need=None):
    """``(pack, role, None)``, or ``(None, None, response)`` when the caller may not do this."""
    pack = packs.live_pack(pack_uuid)
    role = packs.role_of(pack, user_id) if pack is not None else None
    if role is None:
        return None, None, _error('Not found', 404, 'pack_not_found')
    if need == 'edit' and role not in packs.EDIT_ROLES:
        return None, None, _error('You can view this pack but not change it.', 403, 'pack_read_only')
    if need == 'owner' and role != 'owner':
        return None, None, _error("Only the pack's owner can do that.", 403, 'owner_only')
    return pack, role, None


def _finished(pack):
    who = packs.names([pack.finished_by]).get(pack.finished_by) or 'its owner'
    return _error(
        f"This pack was finished by {who}. It is read-only for everyone until the owner reopens it.",
        423, 'pack_finished',
        finished_at=packs.iso(pack.finished_at), finished_by=packs.person(pack.finished_by, packs.names([pack.finished_by])),
    )


def _full(pack, user_id):
    return packs.pack_full(pack, user_id, packs.role_of(pack, user_id))


def _too_large(data):
    return len(json.dumps(data, separators=(',', ':'))) > packs.MAX_ITEM_BYTES


def _new_pack(owner, name, description, team_id=None, team_role='editor', pack_uuid=None):
    """A pack with its owner as a member and its first event. The caller commits."""
    pack = MissionPack(
        uuid=pack_uuid or str(uuid_lib.uuid4()), owner_id=owner.id, name=name, description=description,
        team_id=team_id, team_role=team_role, status='active', head_seq=0,
    )
    db.session.add(pack)
    db.session.flush()
    db.session.add(MissionPackMember(pack_id=pack.id, user_id=owner.id, role='owner', added_by=owner.id))
    packs.append(pack, owner, {'type': 'pack.create', 'name': name, 'team_id': team_id},
                 f'{packs.actor_name(owner)} created the pack.')
    return pack


def _add_item(pack, user, item_uuid, kind, name, data, summary, source=None):
    """A new item and its item.create event, on a locked pack. The caller commits."""
    payload = {'type': 'item.create', 'item': item_uuid, 'kind': kind, 'name': name, 'data': data}
    if source:
        payload['source'] = source
    event = packs.append(pack, user, payload, summary, item_uuid=item_uuid)
    item = MissionPackItem(
        pack_id=pack.id, uuid=item_uuid, kind=kind, name=name, data=copy.deepcopy(data), revision=1,
        change_seq=pack.head_seq, created_by=user.id if user else None, updated_by=user.id if user else None,
        source_kind=source['kind'] if source else None, source_uuid=source['uuid'] if source else None,
        source_revision=source['revision'] if source else None,
    )
    db.session.add(item)
    return item, event


def _rate_limited(scope, user_id, limit, window):
    retry_after = check_rate_limits([(scope, user_id, limit, window)])
    if not retry_after:
        return None
    response = _error('Too many invitations. Try again later.', 429, 'rate_limited')
    response.headers['Retry-After'] = str(retry_after)
    return response


# -- Packs ----------------------------------------------------------------------

@pack_bp.route('/api/packs', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def list_packs():
    user_id = int(get_jwt_identity())
    rows = packs.visible_packs(user_id).order_by(MissionPack.updated_at.desc(), MissionPack.id.desc()).all()
    return _reply({'packs': [packs.pack_summary(p, packs.role_of(p, user_id), user_id) for p in rows]})


@pack_bp.route('/api/packs', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def create_pack():
    me = _me()
    body = _body()
    name = clean_name(body.get('name'))
    if name is None:
        return _error('Give the pack a name of 1 to 100 characters.', 400, 'invalid_name')
    description = body.get('description', '')
    if not isinstance(description, str) or len(description) > packs.MAX_DESCRIPTION:
        return _error('The description must be text of 2,000 characters at most.', 400, 'invalid_description')

    team_id, team_role = body.get('team_id'), body.get('team_role', 'editor')
    if team_id is not None:
        if not isinstance(team_id, int) or isinstance(team_id, bool) or packs.team_role_of(team_id, me.id) is None:
            return _error("You can only share a pack with a team you are in.", 400, 'not_in_team')
        if team_role not in packs.GRANTABLE_ROLES:
            return _error('team_role must be editor or viewer.', 400, 'invalid_role')

    # A device that made the pack offline names it first; making it twice returns the first.
    pack_uuid = None
    if body.get('uuid') is not None:
        pack_uuid = sync.valid_client_uuid(body.get('uuid'))
        if pack_uuid is None:
            return _error('uuid must be a UUID', 400, 'invalid_uuid')
        existing = MissionPack.query.filter_by(uuid=pack_uuid).first()
        if existing is not None:
            if existing.owner_id == me.id and existing.deleted_at is None:
                return _reply(_full(existing, me.id))
            return _error('That uuid is taken.', 409, 'uuid_taken')

    pack = _new_pack(me, name, description.strip(), team_id, team_role if team_id else 'editor', pack_uuid)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        existing = MissionPack.query.filter_by(uuid=pack_uuid).first() if pack_uuid else None
        if existing is None or existing.owner_id != me.id:
            raise
        pack = existing
    return _reply(_full(pack, me.id), 201)


@pack_bp.route('/api/packs/<pack_uuid>', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def get_pack(pack_uuid):
    user_id = int(get_jwt_identity())
    pack, role, refused = _load(pack_uuid, user_id)
    if refused:
        return refused
    # The items as of one head_seq: a writer holds the row until it commits, so this
    # waits for one in progress (FOR SHARE on Postgres; SQLite writes one at a time).
    db.session.execute(select(MissionPack.id).where(MissionPack.id == pack.id).with_for_update(read=True))
    db.session.refresh(pack)
    body = packs.pack_full(pack, user_id, role)
    db.session.commit()
    return _reply(body)


@pack_bp.route('/api/packs/<pack_uuid>/access', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def pack_access(pack_uuid):
    """Whether the caller may see the pack, and as whom: what the live service asks before it opens a socket."""
    me = _me()
    pack, role, refused = _load(pack_uuid, me.id)
    if refused:
        return refused
    return _reply({'role': role, 'status': pack.status, 'head_seq': pack.head_seq,
                   'user': {'id': me.id, 'name': me.name}})


@pack_bp.route('/api/packs/<pack_uuid>', methods=['PUT'])
@jwt_required()
@require_feature('mission_packs')
def update_pack(pack_uuid):
    me = _me()
    body = _body()
    sharing = 'team_id' in body or 'team_role' in body
    editing = 'name' in body or 'description' in body
    pack, role, refused = _load(pack_uuid, me.id, need='owner' if sharing else 'edit')
    if refused:
        return refused

    changes = {}
    if 'name' in body:
        name = clean_name(body['name'])
        if name is None:
            return _error('Give the pack a name of 1 to 100 characters.', 400, 'invalid_name')
        changes['name'] = name
    if 'description' in body:
        if not isinstance(body['description'], str) or len(body['description']) > packs.MAX_DESCRIPTION:
            return _error('The description must be text of 2,000 characters at most.', 400, 'invalid_description')
        changes['description'] = body['description'].strip()
    share = {}
    if sharing:
        team_id = body.get('team_id', pack.team_id)
        team_role = body.get('team_role', pack.team_role)
        if team_id is not None and (not isinstance(team_id, int) or isinstance(team_id, bool)
                                    or packs.team_role_of(team_id, me.id) is None):
            return _error("You can only share a pack with a team you are in.", 400, 'not_in_team')
        if team_role not in packs.GRANTABLE_ROLES:
            return _error('team_role must be editor or viewer.', 400, 'invalid_role')
        share = {'team_id': team_id, 'team_role': team_role}

    packs.lock(pack)
    if editing and pack.status == 'finished':
        return _finished(pack)
    who = packs.actor_name(me)
    if changes:
        before = pack.name
        for key, value in changes.items():
            setattr(pack, key, value)
        summary = (f'{who} renamed the pack from "{before}" to "{changes["name"]}".' if 'name' in changes
                   else f"{who} changed the pack's description.")
        packs.append(pack, me, {'type': 'pack.update', **changes}, summary)
    if share and (share['team_id'], share['team_role']) != (pack.team_id, pack.team_role):
        pack.team_id, pack.team_role = share['team_id'], share['team_role']
        team = packs.team_brief(pack.team_id)
        summary = (f'{who} shared the pack with the team "{team["name"]}" as {pack.team_role}s.' if team
                   else f'{who} stopped sharing the pack with a team.')
        packs.append(pack, me, {'type': 'pack.share', **share}, summary)
    db.session.commit()
    return _reply(_full(pack, me.id))


@pack_bp.route('/api/packs/<pack_uuid>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def delete_pack(pack_uuid):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    packs.lock(pack)
    # A tombstone, so a device that asks learns it was deleted; everything in it is gone.
    for model in packs.PACK_CONTENT:
        model.query.filter_by(pack_id=pack.id).delete(synchronize_session=False)
    pack.name, pack.description, pack.team_id = '', '', None
    pack.deleted_at = packs.now()
    packs.announce_deleted(pack.uuid)
    db.session.commit()
    return _reply({'status': 'deleted'})


@pack_bp.route('/api/packs/<pack_uuid>/finish', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def finish_pack(pack_uuid):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    packs.lock(pack)
    if pack.status != 'finished':
        pack.status, pack.finished_at, pack.finished_by = 'finished', packs.now(), me.id
        packs.append(pack, me, {'type': 'pack.finish'},
                     f'{packs.actor_name(me)} finished the pack. It is read-only for everyone.')
        db.session.commit()
    return _reply(_full(pack, me.id))


@pack_bp.route('/api/packs/<pack_uuid>/reopen', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def reopen_pack(pack_uuid):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    packs.lock(pack)
    if pack.status == 'finished':
        pack.status, pack.finished_at, pack.finished_by = 'active', None, None
        packs.append(pack, me, {'type': 'pack.reopen'}, f'{packs.actor_name(me)} reopened the pack.')
        db.session.commit()
    return _reply(_full(pack, me.id))


@pack_bp.route('/api/packs/<pack_uuid>/duplicate', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def duplicate_pack(pack_uuid):
    """A new pack, owned by the caller, with a copy of every item. Works on a finished pack."""
    me = _me()
    source, _role, refused = _load(pack_uuid, me.id)
    if refused:
        return refused
    body = _body()
    name = clean_name(body['name']) if 'name' in body else clean_name(f'{source.name[:93]} (copy)')
    if name is None:
        return _error('Give the pack a name of 1 to 100 characters.', 400, 'invalid_name')

    pack = _new_pack(me, name, source.description or '')
    who = packs.actor_name(me)
    items = (MissionPackItem.query.filter_by(pack_id=source.id, deleted_at=None)
             .order_by(MissionPackItem.created_at, MissionPackItem.id).all())
    for item in items:
        # Not linked to any library original: that link is only for whoever copied it in.
        _add_item(pack, me, item.uuid, item.kind, item.name, item.data,
                  f'{who} copied "{item.name}" from the pack "{source.name}".')
    db.session.commit()
    return _reply(_full(pack, me.id), 201)


# -- The operation stream and the log --------------------------------------------

def _default_summary(user, op, name_before):
    who = packs.actor_name(user)
    name = name_before or 'an item'
    if op['type'] == 'item.create':
        return f'{who} added the {_KIND_WORDS[op["kind"]]} "{op["name"]}".'
    if op['type'] == 'item.delete':
        return f'{who} removed "{name}".'
    if op['type'] == 'item.rename':
        return f'{who} renamed "{name}" to "{op["name"]}".'
    return f'{who} edited "{name}".'


def _result(event):
    return {'client_op_id': event.client_op_id, 'seq': event.seq, 'status': event.status, 'reason': event.reason}


@pack_bp.route('/api/packs/<pack_uuid>/ops', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def apply_ops(pack_uuid):
    """Apply a batch of operations in order: each is numbered, logged and applied, or logged as skipped.

    Each carries a ``client_op_id``; one the pack has already seen is answered from
    the log and not applied again, so a batch whose answer was lost is safe to send
    again. A malformed operation refuses the whole batch (400) before anything is
    applied. With ``base_seq``, the answer also carries every event after it, so
    the client can rebase its unconfirmed edits in one round trip.
    """
    me = _me()
    body = request.get_json(silent=True)
    ops = body.get('ops') if isinstance(body, dict) else None
    if not isinstance(ops, list) or not 1 <= len(ops) <= packs.MAX_BATCH:
        return _error(f'Send "ops": a list of 1 to {packs.MAX_BATCH} operations.', 400, 'invalid_batch')
    base_seq = body.get('base_seq')
    if base_seq is not None and (not isinstance(base_seq, int) or isinstance(base_seq, bool) or base_seq < 0):
        return _error('base_seq must be a whole number, 0 or more.', 400, 'invalid_batch')

    seen = set()
    for index, op in enumerate(ops):
        def malformed(reason, index=index):
            return _error(f'Operation {index} is malformed ({reason}).', 400, 'invalid_op', index=index, reason=reason)
        if not isinstance(op, dict):
            return malformed('bad_op')
        client_op_id = op.get('client_op_id')
        if not isinstance(client_op_id, str) or not _CLIENT_OP_ID.match(client_op_id) or client_op_id in seen:
            return malformed('bad_client_op_id')
        seen.add(client_op_id)
        if op.get('type') not in pack_ops.CLIENT_OP_TYPES:
            return malformed('unknown_type')
        reason = pack_ops.validate(op)
        if reason:
            return malformed(reason)
        if 'summary' in op and not isinstance(op['summary'], str):
            return malformed('bad_summary')

    pack, _role, refused = _load(pack_uuid, me.id, need='edit')
    if refused:
        return refused
    packs.lock(pack)
    if pack.status == 'finished':
        return _finished(pack)

    done = {e.client_op_id: e for e in MissionPackEvent.query.filter(
        MissionPackEvent.pack_id == pack.id, MissionPackEvent.client_op_id.in_(seen)).all()}
    # Deleted items too: a uuid is never reused.
    rows = {i.uuid: i for i in MissionPackItem.query.filter(
        MissionPackItem.pack_id == pack.id, MissionPackItem.uuid.in_({op['item'] for op in ops})).all()}
    state = {u: {'kind': r.kind, 'name': r.name, 'data': copy.deepcopy(r.data), 'deleted': r.deleted_at is not None}
             for u, r in rows.items()}

    results, created, applied = [], [], {}
    for op in ops:
        if op['client_op_id'] in done:
            results.append(_result(done[op['client_op_id']]))
            continue
        payload = {k: v for k, v in op.items() if k not in ('client_op_id', 'summary')}
        name_before = state[op['item']]['name'] if op['item'] in state else None
        status, reason = pack_ops.apply(state, payload)
        summary = packs.clean_summary(op.get('summary')) or _default_summary(me, op, name_before)
        event = packs.append(pack, me, payload, summary, item_uuid=op['item'],
                             client_op_id=op['client_op_id'], status=status, reason=reason)
        if status == pack_ops.APPLIED:
            applied[op['item']] = applied.get(op['item'], 0) + 1
            rows.setdefault(op['item'], None)
            state[op['item']]['seq'] = pack.head_seq
        results.append(_result(event))
        created.append(event)

    for item_uuid, count in applied.items():
        after = state[item_uuid]
        if not after['deleted'] and _too_large(after['data']):
            db.session.rollback()
            return _error('That would make the item larger than 5 MB.', 413, 'item_too_large', item=item_uuid)
        row = rows[item_uuid]
        if row is None:
            row = MissionPackItem(pack_id=pack.id, uuid=item_uuid, kind=after['kind'], revision=0, created_by=me.id)
            db.session.add(row)
        row.name = after['name']
        row.data = after['data']
        flag_modified(row, 'data')
        row.revision = (row.revision or 0) + count
        row.change_seq = after['seq']
        row.updated_by = me.id
        row.updated_at = packs.now()
        if after['deleted'] and row.deleted_at is None:
            row.deleted_at = packs.now()
    db.session.commit()

    has_more = False
    if base_seq is not None:
        events = (MissionPackEvent.query.filter(MissionPackEvent.pack_id == pack.id, MissionPackEvent.seq > base_seq)
                  .order_by(MissionPackEvent.seq).limit(packs.MAX_PAGE + 1).all())
        has_more = len(events) > packs.MAX_PAGE
        events = events[:packs.MAX_PAGE]
    else:
        events = created
    return _reply({
        'head_seq': pack.head_seq,
        'results': results,
        'events': [packs.event_body(e) for e in events],
        'has_more': has_more,
    })


@pack_bp.route('/api/packs/<pack_uuid>/events', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def list_events(pack_uuid):
    """The log after ``since``, oldest first: what a client replays to catch up, and the History panel's lines."""
    user_id = int(get_jwt_identity())
    pack, _role, refused = _load(pack_uuid, user_id)
    if refused:
        return refused
    try:
        since = int(request.args.get('since', 0))
        limit = int(request.args.get('limit', packs.DEFAULT_PAGE))
    except ValueError:
        return _error('since and limit must be whole numbers.', 400, 'invalid_cursor')
    if since < 0 or limit < 1:
        return _error('since must be 0 or more and limit at least 1.', 400, 'invalid_cursor')
    limit = min(limit, packs.MAX_PAGE)
    rows = (MissionPackEvent.query.filter(MissionPackEvent.pack_id == pack.id, MissionPackEvent.seq > since)
            .order_by(MissionPackEvent.seq).limit(limit + 1).all())
    page = rows[:limit]
    return _reply({
        'events': [packs.event_body(e) for e in page],
        'cursor': page[-1].seq if page else since,
        'has_more': len(rows) > limit,
        'head_seq': pack.head_seq,
    })


@pack_bp.route('/api/packs/<pack_uuid>/seen', methods=['PUT'])
@jwt_required()
@require_feature('mission_packs')
def mark_pack_seen(pack_uuid):
    """How far the caller has looked: the newest event they have seen. It never goes back, so a tab with an
    older copy cannot undo another's. Any member, viewers and finished packs included. Not part of the log."""
    user_id = int(get_jwt_identity())
    seq = _body().get('seq')
    if not isinstance(seq, int) or isinstance(seq, bool) or seq < 0:
        return _error('seq must be a whole number, 0 or more.', 400, 'invalid_seq')
    pack, _role, refused = _load(pack_uuid, user_id)
    if refused:
        return refused
    row = packs.mark_seen(pack, user_id, seq)
    try:
        db.session.commit()
    except IntegrityError:
        # Two tabs made the first marker at once: the other's is there now, so move that one.
        db.session.rollback()
        row = packs.mark_seen(packs.live_pack(pack_uuid), user_id, seq)
        db.session.commit()
    return _reply({'seen_seq': row.seen_seq, 'seen_at': packs.iso(row.seen_at)})


# -- Items ----------------------------------------------------------------------

def _library_record(kind, user_id, source):
    model = packs.LIBRARY_MODELS[kind]
    query = model.query.filter_by(user_id=user_id, deleted_at=None)
    if isinstance(source.get('id'), int) and not isinstance(source.get('id'), bool):
        return query.filter_by(id=source['id']).first()
    if isinstance(source.get('client_uuid'), str):
        return query.filter_by(client_uuid=source['client_uuid'].lower()).first()
    return None


def _library_data(kind, record):
    if kind == 'lz':
        return record.lz_data
    if kind == 'route':
        return record.route_data
    return record.points_data


def _item_reply(pack, item, event, user_id, status):
    known = packs.names([item.created_by, item.updated_by])
    return _reply({
        'item': packs.item_body(item, user_id, known),
        'event': packs.event_body(event) if event is not None else None,
        'head_seq': pack.head_seq,
    }, status)


@pack_bp.route('/api/packs/<pack_uuid>/items', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def copy_in(pack_uuid):
    """Copy one of the caller's library records into the pack, remembering where it came from."""
    me = _me()
    body = _body()
    source = body.get('source') if isinstance(body.get('source'), dict) else {}
    kind = source.get('kind')
    if kind not in packs.LIBRARY_MODELS:
        return _error('source.kind must be lz, route or pointset.', 400, 'invalid_source')
    item_uuid = body.get('item') if body.get('item') is not None else str(uuid_lib.uuid4())
    if not isinstance(item_uuid, str) or pack_ops.validate({'type': 'item.delete', 'item': item_uuid}):
        return _error('item must be an id of letters, digits and _ . : -, 64 at most.', 400, 'invalid_item')
    if 'name' in body and pack_ops.validate({'type': 'item.rename', 'item': 'x', 'name': body['name']}):
        return _error('The name must be 1 to 100 characters.', 400, 'invalid_name')

    pack, _role, refused = _load(pack_uuid, me.id, need='edit')
    if refused:
        return refused
    record = _library_record(kind, me.id, source)
    if record is None:
        return _error('That is not in your library.', 404, 'source_not_found')
    if kind == 'route' and record.kind != 'sketch':
        return _error('Imported AMPS missions cannot go in a pack yet; sketched routes can.', 400, 'mission_not_supported')
    data = _library_data(kind, record)
    if (kind == 'pointset') != isinstance(data, list) or not isinstance(data, (dict, list)):
        return _error('That record cannot be read.', 400, 'unreadable_source')
    if _too_large(data):
        return _error('That is larger than 5 MB.', 413, 'item_too_large')

    packs.lock(pack)
    if pack.status == 'finished':
        return _finished(pack)
    existing = MissionPackItem.query.filter_by(pack_id=pack.id, uuid=item_uuid).first()
    if existing is not None:
        if existing.deleted_at is None and existing.source_uuid == record.client_uuid and existing.created_by == me.id:
            db.session.commit()
            return _item_reply(pack, existing, None, me.id, 200)                # the same copy, asked again
        return _error('The pack already has an item with that id.', 409, 'item_exists')

    name = body['name'] if 'name' in body else ((record.name or '')[:100].strip() or _KIND_WORDS[kind].upper())
    summary = packs.clean_summary(body.get('summary')) or \
        f'{packs.actor_name(me)} added "{name}" (copied from their library).'
    item, event = _add_item(pack, me, item_uuid, kind, name, copy.deepcopy(data), summary,
                            source={'kind': kind, 'uuid': record.client_uuid, 'revision': record.revision})
    db.session.commit()
    return _item_reply(pack, item, event, me.id, 201)


def _live_item(pack, item_uuid):
    return MissionPackItem.query.filter_by(pack_id=pack.id, uuid=item_uuid, deleted_at=None).first()


@pack_bp.route('/api/packs/<pack_uuid>/items/<item_uuid>', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def get_item(pack_uuid, item_uuid):
    user_id = int(get_jwt_identity())
    pack, _role, refused = _load(pack_uuid, user_id)
    if refused:
        return refused
    item = _live_item(pack, item_uuid)
    if item is None:
        return _error('Not found', 404, 'item_not_found')
    known = packs.names([item.created_by, item.updated_by])
    return _reply({'item': packs.item_body(item, user_id, known), 'head_seq': pack.head_seq})


@pack_bp.route('/api/packs/<pack_uuid>/items/<item_uuid>/update-from-original', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def update_from_original(pack_uuid, item_uuid):
    """Replace a copied item's content with its library original as it is now. Only whoever copied it in may."""
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='edit')
    if refused:
        return refused
    packs.lock(pack)
    if pack.status == 'finished':
        return _finished(pack)
    item = _live_item(pack, item_uuid)
    if item is None:
        return _error('Not found', 404, 'item_not_found')
    if not item.source_kind or item.created_by != me.id:
        return _error('Only the person who copied this in from their library can update it from the original.',
                      403, 'not_your_original')
    model = packs.LIBRARY_MODELS[item.source_kind]
    original = model.query.filter_by(user_id=me.id, client_uuid=item.source_uuid, deleted_at=None).first()
    if original is None:
        return _error('The original is no longer in your library.', 409, 'original_gone')
    data = _library_data(item.source_kind, original)
    if _too_large(data):
        return _error('The original is larger than 5 MB.', 413, 'item_too_large')

    payload = {'type': 'item.replace', 'item': item.uuid, 'data': copy.deepcopy(data), 'source_revision': original.revision}
    state = {item.uuid: {'kind': item.kind, 'name': item.name, 'data': item.data, 'deleted': False}}
    status, reason = pack_ops.apply(state, payload)
    if status != pack_ops.APPLIED:
        return _error('The original cannot be read.', 400, reason)
    summary = packs.clean_summary(_body().get('summary')) or \
        f'{packs.actor_name(me)} updated "{item.name}" from the original in their library.'
    event = packs.append(pack, me, payload, summary, item_uuid=item.uuid)
    item.data = state[item.uuid]['data']
    flag_modified(item, 'data')
    item.revision += 1
    item.change_seq = pack.head_seq
    item.updated_by = me.id
    item.updated_at = packs.now()
    item.source_revision = original.revision
    db.session.commit()
    return _item_reply(pack, item, event, me.id, 200)


@pack_bp.route('/api/packs/<pack_uuid>/items/<item_uuid>/library', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def save_to_library(pack_uuid, item_uuid):
    """Save a copy of an item to the caller's own library. Any member, finished pack or not."""
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id)
    if refused:
        return refused
    if not has_feature(me, 'cloud_save'):
        return _error("This feature isn't enabled for your account.", 403, 'feature_disabled')
    item = _live_item(pack, item_uuid)
    if item is None:
        return _error('Not found', 404, 'item_not_found')
    body = _body()
    name = body['name'] if 'name' in body else item.name
    if pack_ops.validate({'type': 'item.rename', 'item': 'x', 'name': name}):
        return _error('The name must be 1 to 100 characters.', 400, 'invalid_name')

    data = copy.deepcopy(item.data)
    if item.kind == 'lz':
        record = SavedLZ(user_id=me.id, name=name, lz_data=data)
    elif item.kind == 'route':
        record = SavedRoute(user_id=me.id, name=name, kind='sketch', route_data=data)
    else:
        if not data:
            return _error('A point set with no points cannot be saved.', 400, 'empty_point_set')
        record = SavedPointSet(user_id=me.id, name=name, points_data=data)
    record.client_uuid = sync.new_client_uuid()
    db.session.add(record)
    sync.stamp(record, me.id)
    db.session.commit()
    return _reply({'kind': item.kind, 'id': record.id, 'client_uuid': record.client_uuid,
                   'name': record.name, 'revision': record.revision}, 201)


# -- Members --------------------------------------------------------------------

def _user(user_id):
    user = db.session.get(User, user_id) if isinstance(user_id, int) and not isinstance(user_id, bool) else None
    return user if user is not None and account_active(user) else None


def _member_body(pack, user_id):
    return next((m for m in packs.member_bodies(pack) if m['user_id'] == user_id), None)


@pack_bp.route('/api/packs/<pack_uuid>/members', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def add_member(pack_uuid):
    """Add a teammate directly. Anyone else is invited by email."""
    me = _me()
    body = _body()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    role = body.get('role', 'editor')
    if role not in packs.GRANTABLE_ROLES:
        return _error('role must be editor or viewer.', 400, 'invalid_role')
    target = _user(body.get('user_id'))
    if target is None or target.id == me.id or not packs.share_a_team(me.id, target.id):
        return _error('Only people in a team with you can be added by name. Invite anyone else by email.',
                      400, 'not_a_teammate')

    packs.lock(pack)
    if MissionPackMember.query.filter_by(pack_id=pack.id, user_id=target.id).first():
        return _error('They are already in this pack.', 409, 'already_member')
    db.session.add(MissionPackMember(pack_id=pack.id, user_id=target.id, role=role, added_by=me.id))
    packs.append(pack, me, {'type': 'member.add', 'user_id': target.id, 'name': target.name, 'role': role},
                 f'{packs.actor_name(me)} added {target.name} as {"an editor" if role == "editor" else "a viewer"}.')
    db.session.commit()
    try:
        send_pack_added_email(target, me.name, pack.name)
    except Exception:  # noqa: BLE001 — a notice; the membership stands without it
        pass
    return _reply({'member': _member_body(pack, target.id)}, 201)


@pack_bp.route('/api/packs/<pack_uuid>/members/<int:user_id>', methods=['PUT'])
@jwt_required()
@require_feature('mission_packs')
def change_member(pack_uuid, user_id):
    """Change a member's role. Making someone the owner hands the pack over; the old owner stays as an editor."""
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    role = _body().get('role')
    if role not in packs.ROLES:
        return _error('role must be owner, editor or viewer.', 400, 'invalid_role')
    packs.lock(pack)
    member = MissionPackMember.query.filter_by(pack_id=pack.id, user_id=user_id).first()
    if member is None:
        return _error('They are not a member of this pack.', 404, 'member_not_found')
    if member.user_id == me.id:
        if role != 'owner':
            return _error('Make someone else the owner first.', 409, 'owner_must_transfer')
        return _reply({'member': _member_body(pack, user_id)})
    if member.role == role:
        return _reply({'member': _member_body(pack, user_id)})

    name = packs.names([user_id]).get(user_id, '')
    who = packs.actor_name(me)
    if role == 'owner':
        MissionPackMember.query.filter_by(pack_id=pack.id, user_id=me.id).update({'role': 'editor'})
        member.role, pack.owner_id = 'owner', user_id
        packs.append(pack, me, {'type': 'pack.transfer', 'user_id': user_id, 'name': name},
                     f'{who} made {name} the owner of the pack.')
    else:
        member.role = role
        packs.append(pack, me, {'type': 'member.role', 'user_id': user_id, 'name': name, 'role': role},
                     f'{who} made {name} {"an editor" if role == "editor" else "a viewer"}.')
    db.session.commit()
    return _reply({'member': _member_body(pack, user_id)})


@pack_bp.route('/api/packs/<pack_uuid>/members/<int:user_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def remove_member(pack_uuid, user_id):
    """The owner removes someone, or a member leaves. The owner leaves only after handing the pack over."""
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need=None if user_id == me.id else 'owner')
    if refused:
        return refused
    packs.lock(pack)
    member = MissionPackMember.query.filter_by(pack_id=pack.id, user_id=user_id).first()
    if member is None:
        return _error('They are not a member of this pack (someone in a shared team is removed by unsharing it).',
                      404, 'member_not_found')
    if member.role == 'owner':
        return _error('Make someone else the owner first.', 409, 'owner_must_transfer')
    name = packs.names([user_id]).get(user_id, '')
    db.session.delete(member)
    summary = f'{name} left the pack.' if user_id == me.id else f'{packs.actor_name(me)} removed {name} from the pack.'
    packs.append(pack, me, {'type': 'member.remove', 'user_id': user_id, 'name': name}, summary)
    db.session.commit()
    return _reply({'status': 'removed'})


# -- Invites --------------------------------------------------------------------

def _send_pack_invite(pack, invite, raw_token, inviter):
    has_account = User.query.filter(func.lower(User.email) == invite.email).first() is not None
    try:
        return bool(send_pack_invite_email(invite.email, inviter.name, pack.name, raw_token, has_account))
    except Exception:  # noqa: BLE001 — the invite stands; the owner can resend
        return False


@pack_bp.route('/api/packs/<pack_uuid>/invites', methods=['GET'])
@jwt_required()
@require_feature('mission_packs')
def list_pack_invites(pack_uuid):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    invites = (MissionPackInvite.query.filter_by(pack_id=pack.id, status='pending')
               .order_by(MissionPackInvite.created_at.desc()).all())
    return _reply({'invites': [packs.invite_body(i) for i in invites]})


@pack_bp.route('/api/packs/<pack_uuid>/invites', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def invite_to_pack(pack_uuid):
    """Invite anyone by email. Someone without an account finds it waiting once they sign up and clear the .mil gate."""
    me = _me()
    body = _body()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    email = _normalize_email(body.get('email'))
    if not _valid_email(email):
        return _error('Enter a valid email address.', 400, 'invalid_email')
    role = body.get('role', 'editor')
    if role not in packs.GRANTABLE_ROLES:
        return _error('role must be editor or viewer.', 400, 'invalid_role')
    limited = _rate_limited('pack_invite:user', me.id, 30, 3600)
    if limited:
        return limited

    packs.lock(pack)
    member_ids = select(MissionPackMember.user_id).where(MissionPackMember.pack_id == pack.id)
    if User.query.filter(func.lower(User.email) == email, User.id.in_(member_ids)).first():
        return _error('They are already in this pack.', 409, 'already_member')
    raw, hashed = packs.new_token()
    invite = MissionPackInvite.query.filter_by(pack_id=pack.id, email=email, status='pending').first()
    status = 200 if invite is not None else 201
    if invite is None:
        invite = MissionPackInvite(pack_id=pack.id, email=email, status='pending')
        db.session.add(invite)
    invite.token_hash, invite.role, invite.invited_by = hashed, role, me.id
    invite.expires_at = packs.now() + packs.INVITE_LIFETIME
    packs.append(pack, me, {'type': 'invite.create', 'email': email, 'role': role},
                 f'{packs.actor_name(me)} invited {email} as {"an editor" if role == "editor" else "a viewer"}.')
    db.session.commit()
    sent = _send_pack_invite(pack, invite, raw, me)
    return _reply({'invite': packs.invite_body(invite), 'email_sent': sent}, status)


@pack_bp.route('/api/packs/<pack_uuid>/invites/<int:invite_id>/resend', methods=['POST'])
@jwt_required()
@require_feature('mission_packs')
def resend_pack_invite(pack_uuid, invite_id):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    invite = MissionPackInvite.query.filter_by(id=invite_id, pack_id=pack.id, status='pending').first()
    if invite is None:
        return _error('Not found', 404, 'invite_not_found')
    limited = _rate_limited('pack_invite:user', me.id, 30, 3600)
    if limited:
        return limited
    raw, invite.token_hash = packs.new_token()
    invite.expires_at = packs.now() + packs.INVITE_LIFETIME
    db.session.commit()
    sent = _send_pack_invite(pack, invite, raw, me)
    return _reply({'invite': packs.invite_body(invite), 'email_sent': sent})


@pack_bp.route('/api/packs/<pack_uuid>/invites/<int:invite_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_packs')
def revoke_pack_invite(pack_uuid, invite_id):
    me = _me()
    pack, _role, refused = _load(pack_uuid, me.id, need='owner')
    if refused:
        return refused
    packs.lock(pack)
    invite = MissionPackInvite.query.filter_by(id=invite_id, pack_id=pack.id, status='pending').first()
    if invite is None:
        return _error('Not found', 404, 'invite_not_found')
    invite.status = 'revoked'
    packs.append(pack, me, {'type': 'invite.revoke', 'email': invite.email},
                 f"{packs.actor_name(me)} withdrew {invite.email}'s invitation.")
    db.session.commit()
    return _reply({'invite': packs.invite_body(invite)})
