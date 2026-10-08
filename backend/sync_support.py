"""Keeping saved LZs, routes and point sets in step between the web and the apps.

The native apps work offline and edit their own copy, so the server has to cope
with the same record being changed in two places. The rules, from
docs/NATIVE_APPS_PLAN.md ("Sync and conflicts"):

* A record has an identity that does not depend on the server: ``client_uuid``,
  chosen by whoever creates it. Creating the same one twice returns the first.
* Every change bumps ``revision``. A client sends the revision it based its edit
  on as ``If-Match``; if the server has moved on, nothing is overwritten and the
  server's copy comes back with a 409, so the client can keep both.
* A deletion is a tombstone (``deleted_at``), so a device that was offline
  learns of it. The content is blanked at once: deleted means gone.
* A user's own aircraft profiles sync the same way. The admin-managed master
  list does not (nobody edits it from a device; the apps refetch it).
* Every change is given the next number in a per-user order (``change_seq``), and
  ``GET /api/sync/changes?since=<n>`` returns everything after ``n``. A counter,
  not a timestamp: timestamps tie, and a lower one can commit after a higher one.
  Taking the next number locks the user's counter until the write commits, so
  that cannot happen here.
* A retried write (the response was lost) carries the same ``Idempotency-Key``
  and is recognised, rather than mistaken for a conflict with itself.

All of it is additive for the web: no existing field, route or status changes
meaning, and a request with none of the new headers behaves as before.
"""

import re
import uuid
from datetime import datetime

from flask import jsonify
from sqlalchemy import select, update

from models import AircraftProfile, SavedLZ, SavedPointSet, SavedRoute, SyncCounter, db

IF_MATCH_ANY = '*'
INVALID = object()

_UUID = re.compile(r'^[0-9a-fA-F-]{8,36}$')
_IDEMPOTENCY_KEY = re.compile(r'^[\x21-\x7e]{1,64}$')            # printable ASCII, no spaces
_IF_MATCH = re.compile(r'^(?:W/)?"?(\d{1,9})"?$')

# Largest page the change feed will return.
MAX_PAGE = 500
DEFAULT_PAGE = 100


def new_client_uuid():
    return str(uuid.uuid4())


def valid_client_uuid(value):
    """The identifier as given (lower-cased), or None if it is not a plausible UUID."""
    if not isinstance(value, str) or not _UUID.match(value):
        return None
    return value.lower()


def idempotency_key(request):
    """The request's ``Idempotency-Key``, or None. A malformed key is ignored."""
    key = request.headers.get('Idempotency-Key')
    return key if key and _IDEMPOTENCY_KEY.match(key) else None


def parse_if_match(request):
    """The revision the client based its edit on.

    Returns an int, ``IF_MATCH_ANY`` for ``*``, None when the header is absent
    (last writer wins, as the web has always behaved), or ``INVALID``.
    """
    raw = request.headers.get('If-Match')
    if raw is None:
        return None
    raw = raw.strip()
    if raw == '*':
        return IF_MATCH_ANY
    match = _IF_MATCH.match(raw)
    return int(match.group(1)) if match else INVALID


def next_seq(user_id):
    """The next number in this user's change order. Holds their counter until commit."""
    _ensure_counter(user_id)
    db.session.execute(
        update(SyncCounter).where(SyncCounter.user_id == user_id).values(seq=SyncCounter.seq + 1)
    )
    return db.session.execute(
        select(SyncCounter.seq).where(SyncCounter.user_id == user_id)
    ).scalar_one()


def _ensure_counter(user_id):
    dialect = db.engine.dialect.name
    if dialect == 'postgresql':
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    db.session.execute(
        insert(SyncCounter).values(user_id=user_id, seq=0).on_conflict_do_nothing(index_elements=['user_id'])
    )


def stamp(record, user_id, idem_key=None, deleted=False):
    """Record that ``record`` just changed: bump its revision and give it the next sequence number."""
    record.revision = (record.revision or 0) + 1
    record.change_seq = next_seq(user_id)
    record.last_idem_key = idem_key
    if deleted:
        record.deleted_at = datetime.utcnow()


def sync_fields(record):
    """The fields every saved-record response gains."""
    return {'client_uuid': record.client_uuid, 'revision': record.revision}


def with_etag(response, record):
    response.headers['ETag'] = f'"{record.revision}"'
    return response


def check_precondition(record, request, serialize_full):
    """The response to send instead of applying a write, or None to go ahead.

    * The same write again (matching ``Idempotency-Key``): the record as it now is, 200.
    * A stale ``If-Match``: 409 with the server's copy, nothing overwritten.
    * A malformed ``If-Match``: 400.
    """
    key = idempotency_key(request)
    if key and record.last_idem_key == key:
        return with_etag(jsonify(serialize_full(record)), record)

    expected = parse_if_match(request)
    if expected is INVALID:
        return jsonify({
            'error': 'If-Match must be the record\'s revision, e.g. "3".',
            'code': 'invalid_if_match',
        }), 400
    if expected is None or expected == IF_MATCH_ANY or expected == record.revision:
        return None
    response = jsonify({
        'error': 'The record changed on the server. Nothing was overwritten.',
        'code': 'revision_conflict',
        'server': serialize_full(record),
    })
    response.status_code = 409
    return with_etag(response, record)


def tombstone_aircraft(profile, user_id, idem_key=None):
    """Delete a user's aircraft profile the way a saved record is deleted: gone, but remembered.

    The airframe's AMPS bytes are the largest thing it holds, and "deleted" has to
    mean they are gone. The caller commits.
    """
    profile.name = ''
    profile.designation = ''
    profile.amps_vehicle_description = None
    profile.template_file = None
    profile.template_name = None
    profile.template_kind = None
    stamp(profile, user_id, idem_key, deleted=True)


# -- The change feed ---------------------------------------------------------

_KINDS = (
    ('lz', SavedLZ),
    ('route', SavedRoute),
    ('pointset', SavedPointSet),
    ('aircraft', AircraftProfile),
)


def assign_missing(user_id):
    """Give records that predate sync (or were written by an older server) an identity and a place.

    Done when the feed is read rather than once at startup: during a rolling
    deploy an older instance can still be writing records that have neither.
    """
    pending = []
    for _kind, model in _KINDS:
        pending += model.query.filter(
            model.user_id == user_id,
            (model.change_seq.is_(None)) | (model.client_uuid.is_(None)),
        ).all()
    if not pending:
        return
    # Oldest first, so the order they were last edited in is the order they are fed.
    pending.sort(key=lambda r: (r.updated_at or datetime.min, r.id))
    for record in pending:
        if record.client_uuid is None:
            record.client_uuid = new_client_uuid()
        if record.change_seq is None:
            record.change_seq = next_seq(user_id)
    db.session.commit()


def give_identity(record):
    """Give one record that predates sync its ``client_uuid`` now, rather than at the next read of the feed.

    For something that has to name the record before then (a mission pack remembers what it copied by
    it). The name is the one ``assign_missing`` would give, and the feed still gives the record its
    place in the order when it is read: the feed names every record before it reads any, so one with no
    name has never been fed and no device knows it by another. Only this row changes, and its
    ``updated_at`` stays, because being named is not an edit and the library lists by it. Two requests
    naming it at once agree on the first name. The caller commits; returns the name.
    """
    model = type(record)
    db.session.execute(
        update(model).where(model.id == record.id, model.client_uuid.is_(None))
        .values(client_uuid=new_client_uuid(), updated_at=model.updated_at)
        .execution_options(synchronize_session=False)
    )
    db.session.refresh(record)
    return record.client_uuid


def _change(kind, record):
    body = {
        'type': kind,
        'id': record.id,
        'client_uuid': record.client_uuid,
        'revision': record.revision,
        'deleted': record.deleted_at is not None,
        'name': record.name,
        'created_at': record.created_at.isoformat() if record.created_at else None,
        'updated_at': record.updated_at.isoformat() if record.updated_at else None,
        'seq': record.change_seq,
    }
    if kind == 'lz':
        body['data'] = record.lz_data
    elif kind == 'aircraft':
        body['data'] = {} if record.deleted_at is not None else record.to_dict()
    elif kind == 'route':
        body.update(kind=record.kind, file_name=record.file_name, has_file=record.msnx_file is not None,
                    data=record.route_data)
    else:
        body['data'] = record.points_data
    return body


def changes_since(user_id, since, limit):
    """Everything that changed after ``since``, oldest first: ``(changes, cursor, has_more)``.

    Deletions are included, as tombstones. ``cursor`` is the sequence number to ask
    for next; it equals ``since`` when nothing changed.
    """
    assign_missing(user_id)
    found = []
    for kind, model in _KINDS:
        rows = (
            model.query.filter(model.user_id == user_id, model.change_seq > since)
            .order_by(model.change_seq).limit(limit + 1).all()
        )
        found += [(record.change_seq, kind, record) for record in rows]
    found.sort(key=lambda item: item[0])
    has_more = len(found) > limit
    page = found[:limit]
    cursor = page[-1][0] if page else since
    return [_change(kind, record) for _seq, kind, record in page], cursor, has_more
