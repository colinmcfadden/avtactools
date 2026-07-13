from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity

from models import db, SavedPointSet

point_sets_bp = Blueprint('point_sets', __name__)


def _summary(ps):
    return {
        "id": ps.id,
        "name": ps.name,
        "point_count": len(ps.points_data or []),
        "created_at": ps.created_at.isoformat(),
        "updated_at": ps.updated_at.isoformat(),
    }


@point_sets_bp.route('/api/pointsets', methods=['GET'])
@jwt_required()
def list_point_sets():
    user_id = int(get_jwt_identity())
    sets = (
        SavedPointSet.query.filter_by(user_id=user_id)
        .order_by(SavedPointSet.updated_at.desc())
        .all()
    )
    return jsonify([_summary(ps) for ps in sets])


@point_sets_bp.route('/api/pointsets', methods=['POST'])
@jwt_required()
def create_point_set():
    user_id = int(get_jwt_identity())
    body = request.get_json(silent=True) or {}

    name = body.get('name')
    points = body.get('points')
    if not name or not isinstance(points, list) or not points:
        return jsonify({"error": "Missing name or points"}), 400

    saved = SavedPointSet(user_id=user_id, name=name, points_data=points)
    db.session.add(saved)
    db.session.commit()
    return jsonify(_summary(saved)), 201


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['GET'])
@jwt_required()
def get_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = SavedPointSet.query.filter_by(id=set_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404

    body = _summary(saved)
    body["points"] = saved.points_data
    return jsonify(body)


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['PUT'])
@jwt_required()
def update_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = SavedPointSet.query.filter_by(id=set_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404

    body = request.get_json(silent=True) or {}
    if body.get('name'):
        saved.name = body['name']
    if isinstance(body.get('points'), list) and body['points']:
        saved.points_data = body['points']

    db.session.commit()
    return jsonify(_summary(saved))


@point_sets_bp.route('/api/pointsets/<int:set_id>', methods=['DELETE'])
@jwt_required()
def delete_point_set(set_id):
    user_id = int(get_jwt_identity())
    saved = SavedPointSet.query.filter_by(id=set_id, user_id=user_id).first()
    if not saved:
        return jsonify({"error": "Not found"}), 404

    db.session.delete(saved)
    db.session.commit()
    return jsonify({"status": "deleted"})
