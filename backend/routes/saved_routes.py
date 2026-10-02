import json
import io

from flask import Blueprint, request, jsonify, send_file
from flask_jwt_extended import jwt_required, get_jwt_identity
from sqlalchemy.exc import IntegrityError

import sync_support as sync
from models import db, SavedRoute
from entitlements import require_feature

saved_routes_bp = Blueprint('saved_routes', __name__)


def _summary(r):
    return {
        "id": r.id,
        "name": r.name,
        "kind": r.kind,
        "file_name": r.file_name,
        "has_file": r.msnx_file is not None,
        "created_at": r.created_at.isoformat(),
        "updated_at": r.updated_at.isoformat(),
        **sync.sync_fields(r),
    }


def _full(r):
    return {**_summary(r), "route_data": r.route_data}


def _live(user_id, route_id):
    return SavedRoute.query.filter_by(id=route_id, user_id=user_id, deleted_at=None).first()


@saved_routes_bp.route('/api/routes', methods=['GET'])
@jwt_required()
def list_saved_routes():
    user_id = int(get_jwt_identity())
    saved = (
        SavedRoute.query.filter_by(user_id=user_id, deleted_at=None)
        .order_by(SavedRoute.updated_at.desc())
        .all()
    )
    return jsonify([_summary(r) for r in saved])


@saved_routes_bp.route('/api/routes', methods=['POST'])
@jwt_required()
@require_feature('cloud_save')
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

    # An app creating a route offline names it first; creating the same one twice
    # (a retry after a lost response) returns the first rather than a duplicate.
    client_uuid = None
    if request.form.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(request.form.get('client_uuid'))
        if client_uuid is None:
            return jsonify({"error": "client_uuid must be a UUID", "code": "invalid_client_uuid"}), 400
        existing = SavedRoute.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            return sync.with_etag(jsonify(_summary(existing)), existing)

    saved = SavedRoute(
        user_id=user_id,
        name=name,
        kind=kind,
        route_data=route_data,
        msnx_file=msnx_file,
        file_name=file_name,
        client_uuid=client_uuid or sync.new_client_uuid(),
    )
    db.session.add(saved)
    try:
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        existing = SavedRoute.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing is None:
            raise
        return sync.with_etag(jsonify(_summary(existing)), existing)

    return sync.with_etag(jsonify(_summary(saved)), saved), 201


@saved_routes_bp.route('/api/routes/<int:route_id>', methods=['GET'])
@jwt_required()
def get_saved_route(route_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, route_id)

    if not saved:
        return jsonify({"error": "Not found"}), 404

    return sync.with_etag(jsonify(_full(saved)), saved)


@saved_routes_bp.route('/api/routes/<int:route_id>/file', methods=['GET'])
@jwt_required()
def get_saved_route_file(route_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, route_id)

    if not saved or saved.msnx_file is None:
        return jsonify({"error": "Not found"}), 404

    return send_file(
        io.BytesIO(saved.msnx_file),
        mimetype='application/octet-stream',
        as_attachment=True,
        download_name=saved.file_name or f"{saved.name}.msnx",
    )


@saved_routes_bp.route('/api/routes/<int:route_id>', methods=['PUT'])
@jwt_required()
@require_feature('cloud_save')
def update_saved_route(route_id):
    user_id = int(get_jwt_identity())
    saved = _live(user_id, route_id)

    if not saved:
        return jsonify({"error": "Not found"}), 404

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    # Same multipart shape as create; kind is fixed at creation. Fields are
    # optional — only what's sent gets overwritten.
    name = request.form.get('name')
    if name:
        saved.name = name

    route_data_raw = request.form.get('route_data')
    if route_data_raw:
        try:
            saved.route_data = json.loads(route_data_raw)
        except ValueError:
            return jsonify({"error": "route_data is not valid JSON"}), 400

    upload = request.files.get('msnx')
    if upload:
        saved.msnx_file = upload.read()
        saved.file_name = upload.filename

    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()
    return sync.with_etag(jsonify(_summary(saved)), saved)


@saved_routes_bp.route('/api/routes/<int:route_id>', methods=['DELETE'])
@jwt_required()
def delete_saved_route(route_id):
    user_id = int(get_jwt_identity())
    saved = SavedRoute.query.filter_by(id=route_id, user_id=user_id).first()

    if not saved:
        return jsonify({"error": "Not found"}), 404
    if saved.deleted_at is not None:
        return jsonify({"status": "deleted"})

    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    # A tombstone with the content gone: the mission file is the largest thing we hold.
    saved.route_data = {}
    saved.msnx_file = None
    saved.file_name = None
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()

    return jsonify({"status": "deleted", "revision": saved.revision})
