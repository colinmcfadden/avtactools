from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity
from sqlalchemy.exc import IntegrityError

import sync_support as sync
from models import db, SavedLZ
from entitlements import require_feature

lz_bp = Blueprint('lz', __name__)


def _summary(s):
    return {
        "id": s.id,
        "name": s.name,
        "created_at": s.created_at.isoformat(),
        "updated_at": s.updated_at.isoformat(),
        **sync.sync_fields(s),
    }


def _full(s):
    return {**_summary(s), "lz_data": s.lz_data}


def _live(user_id, lz_id):
    return SavedLZ.query.filter_by(id=lz_id, user_id=user_id, deleted_at=None).first()


@lz_bp.route('/api/lz', methods=['GET'])
@jwt_required()
def list_saved_lzs():
    user_id = int(get_jwt_identity())
    saved = (
        SavedLZ.query.filter_by(user_id=user_id, deleted_at=None)
        .order_by(SavedLZ.updated_at.desc())
        .all()
    )
    return jsonify([_summary(s) for s in saved])


@lz_bp.route('/api/lz', methods=['POST'])
@jwt_required()
@require_feature('cloud_save')
def create_saved_lz():
    user_id = int(get_jwt_identity())
    data = request.json or {}
    name = data.get('name')
    lz_data = data.get('lz_data')

    if not name or lz_data is None:
        return jsonify({"error": "Missing name or lz_data"}), 400

    # An app creating a record offline names it first; creating the same one twice
    # (a retry after a lost response) returns the first rather than a duplicate.
    client_uuid = None
    if data.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(data.get('client_uuid'))
        if client_uuid is None:
            return jsonify({"error": "client_uuid must be a UUID", "code": "invalid_client_uuid"}), 400
        existing = SavedLZ.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            return sync.with_etag(jsonify(_summary(existing)), existing)

    saved = SavedLZ(
        user_id=user_id, name=name, lz_data=lz_data,
        client_uuid=client_uuid or sync.new_client_uuid(),
    )
    db.session.add(saved)
    try:
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    except IntegrityError:
        # A concurrent request with the same client_uuid won the race.
        db.session.rollback()
        existing = SavedLZ.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing is None:
            raise
        return sync.with_etag(jsonify(_summary(existing)), existing)

    return sync.with_etag(jsonify(_summary(saved)), saved), 201


@lz_bp.route('/api/lz/<int:lz_id>', methods=['GET'])
@jwt_required()
def get_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, lz_id)

    if not saved:
        return jsonify({"error": "Not found"}), 404

    return sync.with_etag(jsonify(_full(saved)), saved)


@lz_bp.route('/api/lz/<int:lz_id>', methods=['PUT'])
@jwt_required()
@require_feature('cloud_save')
def update_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, lz_id)

    if not saved:
        return jsonify({"error": "Not found"}), 404

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    data = request.json or {}
    if 'name' in data:
        saved.name = data['name']
    if 'lz_data' in data:
        saved.lz_data = data['lz_data']

    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()

    return sync.with_etag(jsonify(_summary(saved)), saved)


@lz_bp.route('/api/lz/<int:lz_id>', methods=['DELETE'])
@jwt_required()
def delete_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = SavedLZ.query.filter_by(id=lz_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404
    if saved.deleted_at is not None:
        return jsonify({"status": "deleted"})        # deleting twice is not an error

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    # A tombstone, so a device that was offline learns of the deletion, with the
    # content gone: deleted means gone.
    saved.lz_data = {}
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()

    return jsonify({"status": "deleted", "revision": saved.revision})
