"""Mission briefs kept in the Library: ``/api/briefs``, synced like a saved LZ (docs/MISSION_BRIEF.md §3).

A brief here is the person's answers (``data``, the brief document), never a copy of the plan, and the server does
not look inside it. The routes are ``/api/lz``'s, rule for rule: a ``client_uuid`` makes a create repeatable,
``If-Match`` and ``Idempotency-Key`` keep two devices from overwriting each other, a request with neither keeps
last-writer-wins for the web, and a deletion is a tombstone. Every route needs ``mission_brief``, which is not
launched yet (``entitlements.DEFAULT_OFF``); a write needs ``cloud_save`` too, as any saved record does.
"""

from flask import Blueprint, jsonify, request
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy.exc import IntegrityError

import sync_support as sync
from entitlements import require_feature
from models import SavedBrief, db

brief_bp = Blueprint('briefs', __name__)

MAX_NAME = 100


def _summary(s):
    return {
        "id": s.id,
        "name": s.name,
        "created_at": s.created_at.isoformat(),
        "updated_at": s.updated_at.isoformat(),
        **sync.sync_fields(s),
    }


def _full(s):
    return {**_summary(s), "data": s.brief_data}


def _live(user_id, brief_id):
    return SavedBrief.query.filter_by(id=brief_id, user_id=user_id, deleted_at=None).first()


def _bad_field(data, require):
    """The refusal for a body whose ``name`` or ``data`` cannot be stored, or None. ``require``: both must be there."""
    if require and ('name' not in data or 'data' not in data):
        return jsonify({"error": "Send a name and the brief's data.", "code": "missing_fields"}), 400
    if 'name' in data:
        name = data['name']
        # The column holds 100 characters; a longer name would fail as a 500 in Postgres and hold up a device's outbox.
        if not isinstance(name, str) or not name.strip() or len(name) > MAX_NAME:
            return jsonify({"error": f"The name must be 1 to {MAX_NAME} characters.", "code": "invalid_name"}), 400
    if 'data' in data and not isinstance(data['data'], dict):
        return jsonify({"error": "data must be the brief document, an object.", "code": "invalid_data"}), 400
    return None


@brief_bp.route('/api/briefs', methods=['GET'])
@jwt_required()
@require_feature('mission_brief')
def list_briefs():
    user_id = int(get_jwt_identity())
    saved = (
        SavedBrief.query.filter_by(user_id=user_id, deleted_at=None)
        .order_by(SavedBrief.updated_at.desc())
        .all()
    )
    return jsonify([_summary(s) for s in saved])


@brief_bp.route('/api/briefs', methods=['POST'])
@jwt_required()
@require_feature('mission_brief')
@require_feature('cloud_save')
def create_brief():
    user_id = int(get_jwt_identity())
    data = request.get_json(silent=True)
    data = data if isinstance(data, dict) else {}
    refused = _bad_field(data, require=True)
    if refused:
        return refused

    client_uuid = None
    if data.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(data.get('client_uuid'))
        if client_uuid is None:
            return jsonify({"error": "client_uuid must be a UUID", "code": "invalid_client_uuid"}), 400
        existing = SavedBrief.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            return sync.with_etag(jsonify(_summary(existing)), existing)

    saved = SavedBrief(
        user_id=user_id, name=data['name'], brief_data=data['data'],
        client_uuid=client_uuid or sync.new_client_uuid(),
    )
    db.session.add(saved)
    try:
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    except IntegrityError:
        # A concurrent request with the same client_uuid won the race.
        db.session.rollback()
        existing = SavedBrief.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing is None:
            raise
        return sync.with_etag(jsonify(_summary(existing)), existing)

    return sync.with_etag(jsonify(_summary(saved)), saved), 201


@brief_bp.route('/api/briefs/<int:brief_id>', methods=['GET'])
@jwt_required()
@require_feature('mission_brief')
def get_brief(brief_id):
    saved = _live(int(get_jwt_identity()), brief_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    return sync.with_etag(jsonify(_full(saved)), saved)


@brief_bp.route('/api/briefs/<int:brief_id>', methods=['PUT'])
@jwt_required()
@require_feature('mission_brief')
@require_feature('cloud_save')
def update_brief(brief_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, brief_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    data = request.get_json(silent=True)
    data = data if isinstance(data, dict) else {}
    refused = _bad_field(data, require=False)
    if refused:
        return refused
    if 'name' in data:
        saved.name = data['name']
    if 'data' in data:
        saved.brief_data = data['data']

    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()
    return sync.with_etag(jsonify(_summary(saved)), saved)


@brief_bp.route('/api/briefs/<int:brief_id>', methods=['DELETE'])
@jwt_required()
@require_feature('mission_brief')
def delete_brief(brief_id):
    user_id = int(get_jwt_identity())
    saved = SavedBrief.query.filter_by(id=brief_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404
    if saved.deleted_at is not None:
        return jsonify({"status": "deleted"})        # deleting twice is not an error

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    # A tombstone, so a device that was offline learns of the deletion, with the content gone.
    saved.brief_data = {}
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()
    return jsonify({"status": "deleted", "revision": saved.revision})
