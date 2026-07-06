import json
import io

from flask import Blueprint, request, jsonify, send_file
from flask_jwt_extended import jwt_required, get_jwt_identity

from models import db, SavedRoute

saved_routes_bp = Blueprint('saved_routes', __name__)


def _summary(r):
    return {
        "id": r.id,
        "name": r.name,
        "kind": r.kind,
        "file_name": r.file_name,
        "created_at": r.created_at.isoformat(),
        "updated_at": r.updated_at.isoformat(),
    }


@saved_routes_bp.route('/api/routes', methods=['GET'])
@jwt_required()
def list_saved_routes():
    user_id = int(get_jwt_identity())
    saved = (
        SavedRoute.query.filter_by(user_id=user_id)
        .order_by(SavedRoute.updated_at.desc())
        .all()
    )
    return jsonify([_summary(r) for r in saved])


@saved_routes_bp.route('/api/routes', methods=['POST'])
@jwt_required()
def create_saved_route():
    user_id = int(get_jwt_identity())

    # multipart/form-data: fields + optional msnx file part
    name = request.form.get('name')
    kind = request.form.get('kind', 'sketch')
    route_data_raw = request.form.get('route_data')

    if not name or not route_data_raw:
        return jsonify({"error": "Missing name or route_data"}), 400
    if kind not in ('sketch', 'mission'):
        return jsonify({"error": "Invalid kind"}), 400

    try:
        route_data = json.loads(route_data_raw)
    except ValueError:
        return jsonify({"error": "route_data is not valid JSON"}), 400

    msnx_file = None
    file_name = None
    upload = request.files.get('msnx')
    if upload:
        msnx_file = upload.read()
        file_name = upload.filename
    if kind == 'mission' and msnx_file is None:
        return jsonify({"error": "Mission saves require the msnx file"}), 400

    saved = SavedRoute(
        user_id=user_id,
        name=name,
        kind=kind,
        route_data=route_data,
        msnx_file=msnx_file,
        file_name=file_name,
    )
    db.session.add(saved)
    db.session.commit()

    return jsonify(_summary(saved)), 201


@saved_routes_bp.route('/api/routes/<int:route_id>', methods=['GET'])
@jwt_required()
def get_saved_route(route_id):
    user_id = int(get_jwt_identity())
    saved = SavedRoute.query.filter_by(id=route_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404

    body = _summary(saved)
    body["route_data"] = saved.route_data
    return jsonify(body)


@saved_routes_bp.route('/api/routes/<int:route_id>/file', methods=['GET'])
@jwt_required()
def get_saved_route_file(route_id):
    user_id = int(get_jwt_identity())
    saved = SavedRoute.query.filter_by(id=route_id, user_id=user_id).first()

    if not saved or saved.msnx_file is None:
        return jsonify({"error": "Not found"}), 404

    return send_file(
        io.BytesIO(saved.msnx_file),
        mimetype='application/octet-stream',
        as_attachment=True,
        download_name=saved.file_name or f"{saved.name}.msnx",
    )


@saved_routes_bp.route('/api/routes/<int:route_id>', methods=['DELETE'])
@jwt_required()
def delete_saved_route(route_id):
    user_id = int(get_jwt_identity())
    saved = SavedRoute.query.filter_by(id=route_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404

    db.session.delete(saved)
    db.session.commit()

    return jsonify({"status": "deleted"})
