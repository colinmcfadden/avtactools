"""Threats kept in the Library: ``/api/threatsets``, synced like a saved LZ.

A set is ``{"version": 1, "threats": [...]}``; the server reads only how many threats it holds. The routes are ``/api/briefs``'s,
rule for rule: a ``client_uuid`` makes a create repeatable, ``If-Match`` and ``Idempotency-Key`` keep two devices from overwriting
each other, and a deletion is a tombstone. A set may have no threats: the person took the last one off and it stays theirs to fill.
Every route needs ``threats``; a write needs ``cloud_save`` too, as any saved record does.
"""

from flask import Blueprint, jsonify, request
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy.exc import IntegrityError

import sync_support as sync
from entitlements import require_feature
from models import SavedThreatSet, db

threat_set_bp = Blueprint('threat_sets', __name__)

MAX_NAME = 100
# A set carries its terrain masks, a few tens of kilobytes each. A pack item's limit, so any saved set can later be copied into a pack.
MAX_BYTES = 5 * 1024 * 1024


def _summary(s):
    return {
        "id": s.id,
        "name": s.name,
        "threat_count": s.threat_count,
        "created_at": s.created_at.isoformat(),
        "updated_at": s.updated_at.isoformat(),
        **sync.sync_fields(s),
    }


def _full(s):
    return {**_summary(s), "data": s.threat_data}


def _live(user_id, set_id):
    return SavedThreatSet.query.filter_by(id=set_id, user_id=user_id, deleted_at=None).first()


def _too_large():
    if request.content_length and request.content_length > MAX_BYTES:
        return jsonify({"error": "That set of threats is larger than 5 MB.", "code": "too_large"}), 413
    return None


def _bad_field(body, require):
    """The refusal for a body whose ``name`` or ``data`` cannot be stored, or None. ``require``: both must be there."""
    if require and ('name' not in body or 'data' not in body):
        return jsonify({"error": "Send a name and the threats.", "code": "missing_fields"}), 400
    if 'name' in body:
        name = body['name']
        # The column holds 100 characters; a longer name would fail as a 500 in Postgres and hold up a device's outbox.
        if not isinstance(name, str) or not name.strip() or len(name) > MAX_NAME:
            return jsonify({"error": f"The name must be 1 to {MAX_NAME} characters.", "code": "invalid_name"}), 400
    if 'data' in body:
        data = body['data']
        if not isinstance(data, dict) or not isinstance(data.get('threats'), list):
            return jsonify({"error": "data must be an object with a list of threats.", "code": "invalid_data"}), 400
    return None


def _body():
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else {}


@threat_set_bp.route('/api/threatsets', methods=['GET'])
@jwt_required()
@require_feature('threats')
def list_threat_sets():
    user_id = int(get_jwt_identity())
    saved = (
        SavedThreatSet.query.filter_by(user_id=user_id, deleted_at=None)
        .order_by(SavedThreatSet.updated_at.desc())
        .all()
    )
    return jsonify([_summary(s) for s in saved])


@threat_set_bp.route('/api/threatsets', methods=['POST'])
@jwt_required()
@require_feature('threats')
@require_feature('cloud_save')
def create_threat_set():
    user_id = int(get_jwt_identity())
    refused = _too_large()
    if refused:
        return refused
    body = _body()
    refused = _bad_field(body, require=True)
    if refused:
        return refused

    client_uuid = None
    if body.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(body.get('client_uuid'))
        if client_uuid is None:
            return jsonify({"error": "client_uuid must be a UUID", "code": "invalid_client_uuid"}), 400
        existing = SavedThreatSet.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            return sync.with_etag(jsonify(_summary(existing)), existing)

    saved = SavedThreatSet(
        user_id=user_id, name=body['name'], threat_data=body['data'], threat_count=len(body['data']['threats']),
        client_uuid=client_uuid or sync.new_client_uuid(),
    )
    db.session.add(saved)
    try:
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    except IntegrityError:
        # A concurrent request with the same client_uuid won the race.
        db.session.rollback()
        existing = SavedThreatSet.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing is None:
            raise
        return sync.with_etag(jsonify(_summary(existing)), existing)

    return sync.with_etag(jsonify(_summary(saved)), saved), 201


@threat_set_bp.route('/api/threatsets/<int:set_id>', methods=['GET'])
@jwt_required()
@require_feature('threats')
def get_threat_set(set_id):
    saved = _live(int(get_jwt_identity()), set_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    return sync.with_etag(jsonify(_full(saved)), saved)


@threat_set_bp.route('/api/threatsets/<int:set_id>', methods=['PUT'])
@jwt_required()
@require_feature('threats')
@require_feature('cloud_save')
def update_threat_set(set_id):
    user_id = int(get_jwt_identity())
    refused = _too_large()
    if refused:
        return refused
    saved = _live(user_id, set_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    body = _body()
    refused = _bad_field(body, require=False)
    if refused:
        return refused
    if 'name' in body:
        saved.name = body['name']
    if 'data' in body:
        saved.threat_data = body['data']
        saved.threat_count = len(body['data']['threats'])

    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()
    return sync.with_etag(jsonify(_summary(saved)), saved)


@threat_set_bp.route('/api/threatsets/<int:set_id>', methods=['DELETE'])
@jwt_required()
@require_feature('threats')
def delete_threat_set(set_id):
    user_id = int(get_jwt_identity())
    saved = SavedThreatSet.query.filter_by(id=set_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404
    if saved.deleted_at is not None:
        return jsonify({"status": "deleted"})        # deleting twice is not an error

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    # A tombstone, so a device that was offline learns of the deletion, with the content gone.
    saved.threat_data = {}
    saved.threat_count = 0
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()
    return jsonify({"status": "deleted", "revision": saved.revision})
