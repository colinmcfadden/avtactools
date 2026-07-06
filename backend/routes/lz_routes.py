from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required, get_jwt_identity

from models import db, SavedLZ

lz_bp = Blueprint('lz', __name__)


@lz_bp.route('/api/lz', methods=['GET'])
@jwt_required()
def list_saved_lzs():
    user_id = int(get_jwt_identity())
    saved = (
        SavedLZ.query.filter_by(user_id=user_id)
        .order_by(SavedLZ.updated_at.desc())
        .all()
    )
    return jsonify([
        {
            "id": s.id,
            "name": s.name,
            "created_at": s.created_at.isoformat(),
            "updated_at": s.updated_at.isoformat(),
        }
        for s in saved
    ])


@lz_bp.route('/api/lz', methods=['POST'])
@jwt_required()
def create_saved_lz():
    user_id = int(get_jwt_identity())
    data = request.json or {}
    name = data.get('name')
    lz_data = data.get('lz_data')

    if not name or lz_data is None:
        return jsonify({"error": "Missing name or lz_data"}), 400

    saved = SavedLZ(user_id=user_id, name=name, lz_data=lz_data)
    db.session.add(saved)
    db.session.commit()

    return jsonify({
        "id": saved.id,
        "name": saved.name,
        "created_at": saved.created_at.isoformat(),
        "updated_at": saved.updated_at.isoformat(),
    }), 201


@lz_bp.route('/api/lz/<int:lz_id>', methods=['GET'])
@jwt_required()
def get_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = SavedLZ.query.filter_by(id=lz_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404

    return jsonify({
        "id": saved.id,
        "name": saved.name,
        "lz_data": saved.lz_data,
        "created_at": saved.created_at.isoformat(),
        "updated_at": saved.updated_at.isoformat(),
    })


@lz_bp.route('/api/lz/<int:lz_id>', methods=['PUT'])
@jwt_required()
def update_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = SavedLZ.query.filter_by(id=lz_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404

    data = request.json or {}
    if 'name' in data:
        saved.name = data['name']
    if 'lz_data' in data:
        saved.lz_data = data['lz_data']

    db.session.commit()

    return jsonify({
        "id": saved.id,
        "name": saved.name,
        "created_at": saved.created_at.isoformat(),
        "updated_at": saved.updated_at.isoformat(),
    })


@lz_bp.route('/api/lz/<int:lz_id>', methods=['DELETE'])
@jwt_required()
def delete_saved_lz(lz_id):
    user_id = int(get_jwt_identity())
    saved = SavedLZ.query.filter_by(id=lz_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404

    db.session.delete(saved)
    db.session.commit()

    return jsonify({"status": "deleted"})
