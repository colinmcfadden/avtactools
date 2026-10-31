from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity
from sqlalchemy.exc import IntegrityError

import sync_support as sync
from models import db, SavedPointSet
from entitlements import require_feature

point_sets_bp = Blueprint('point_sets', __name__)


def _summary(ps):
    return {
        "id": ps.id,
        "name": ps.name,
        "point_count": len(ps.points_data or []),
        "created_at": ps.created_at.isoformat(),
        "updated_at": ps.updated_at.isoformat(),
        **sync.sync_fields(ps),
    }


def _full(ps):
    return {**_summary(ps), "points": ps.points_data}


def _live(user_id, set_id):
    return SavedPointSet.query.filter_by(id=set_id, user_id=user_id, deleted_at=None).first()


@point_sets_bp.route('/api/pointsets', methods=['GET'])
@jwt_required()
def list_point_sets():
    user_id = int(get_jwt_identity())
    sets = (
        SavedPointSet.query.filter_by(user_id=user_id, deleted_at=None)
        .order_by(SavedPointSet.updated_at.desc())
        .all()
    )
    return jsonify([_summary(ps) for ps in sets])


@point_sets_bp.route('/api/pointsets', methods=['POST'])
@jwt_required()
@require_feature('cloud_save')
def create_point_set():
    user_id = int(get_jwt_identity())
    body = request.get_json(silent=True) or {}

    name = body.get('name')
    points = body.get('points')
    if not name or not isinstance(points, list) or not points:
        return jsonify({"error": "Missing name or points"}), 400

    client_uuid = None
    if body.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(body.get('client_uuid'))
        if client_uuid is None:
            return jsonify({"error": "client_uuid must be a UUID", "code": "invalid_client_uuid"}), 400
        existing = SavedPointSet.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            return sync.with_etag(jsonify(_summary(existing)), existing)

    saved = SavedPointSet(
        user_id=user_id, name=name, points_data=points,
        client_uuid=client_uuid or sync.new_client_uuid(),
    )
    db.session.add(saved)
    try:
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        existing = SavedPointSet.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing is None:
            raise
        return sync.with_etag(jsonify(_summary(existing)), existing)
    return sync.with_etag(jsonify(_summary(saved)), saved), 201


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['GET'])
@jwt_required()
def get_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, set_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404

    return sync.with_etag(jsonify(_full(saved)), saved)


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['PUT'])
@jwt_required()
@require_feature('cloud_save')
def update_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, set_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    body = request.get_json(silent=True) or {}
    if body.get('name'):
        saved.name = body['name']
    if isinstance(body.get('points'), list) and body['points']:
        saved.points_data = body['points']

    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()
    return sync.with_etag(jsonify(_summary(saved)), saved)


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['DELETE'])
@jwt_required()
def delete_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = SavedPointSet.query.filter_by(id=set_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404
    if saved.deleted_at is not None:
        return jsonify({"status": "deleted"})

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    saved.points_data = []
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()
    return jsonify({"status": "deleted", "revision": saved.revision})
