"""
A person's map overlays (KMZ and KML) in the Library. Web only, on purpose: Android does not draw
them, so these are plain rows with none of the sync columns and a delete that is a DELETE (AGENTS.md
§7). The server keeps the file as it came and never opens it; the browser reads it, as hostile
input (`feature/overlays/parseKmz.js`). Not in contracts/openapi.yaml, so nothing an app may rely on.
"""

import io

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required

from entitlements import require_feature
from models import SavedOverlay, db

overlay_bp = Blueprint('overlays', __name__)

MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_PER_ACCOUNT = 100
MAX_NAME = 100
MAX_FILE_NAME = 255
MAX_SUMMARY = 200


def _private(response):
    # An account's own files: Flask's defaults would let Cloudflare keep one person's for another (AGENTS.md §14).
    response.headers['Cache-Control'] = 'private, no-store'
    return response


def _summary(o):
    return {
        "id": o.id,
        "name": o.name,
        "file_name": o.file_name,
        "file_size": o.file_size,
        "summary": o.summary or "",
        "created_at": o.created_at.isoformat(),
        "updated_at": o.updated_at.isoformat(),
    }


def _mine(user_id, overlay_id):
    return SavedOverlay.query.filter_by(id=overlay_id, user_id=user_id).first()


def _clean_name(value):
    """The name, trimmed, or None if there is none or it is too long or holds a control character."""
    if not isinstance(value, str):
        return None
    name = value.strip()
    if not name or len(name) > MAX_NAME or any(ord(ch) < 32 or ord(ch) == 127 for ch in name):
        return None
    return name


def _looks_like_an_overlay(data):
    """A zip, or text that starts as XML does. The browser decides what it really is, each time it opens it."""
    if data[:4] == b'PK\x03\x04':
        return True
    head = data[:64]
    if head[:2] in (b'\xff\xfe', b'\xfe\xff'):
        return True
    return head.removeprefix(b'\xef\xbb\xbf').lstrip()[:1] == b'<'


def _full(user_id):
    return SavedOverlay.query.filter_by(user_id=user_id).count() >= MAX_PER_ACCOUNT


def _too_many():
    return jsonify({"error": f"A Library holds at most {MAX_PER_ACCOUNT} overlays. Delete one first.", "code": "too_many_overlays"}), 409


@overlay_bp.route('/api/overlays', methods=['GET'])
@jwt_required()
def list_overlays():
    user_id = int(get_jwt_identity())
    saved = SavedOverlay.query.filter_by(user_id=user_id).order_by(SavedOverlay.updated_at.desc()).all()
    return _private(jsonify([_summary(o) for o in saved]))


@overlay_bp.route('/api/overlays', methods=['POST'])
@jwt_required()
@require_feature('cloud_save')
def create_overlay():
    user_id = int(get_jwt_identity())
    name = _clean_name(request.form.get('name'))
    if name is None:
        return jsonify({"error": f"A name of 1 to {MAX_NAME} characters is required.", "code": "invalid_name"}), 400
    if request.content_length is not None and request.content_length > MAX_FILE_BYTES + 64 * 1024:
        return jsonify({"error": "This overlay is too large to save.", "code": "too_large"}), 413
    upload = request.files.get('file')
    if upload is None:
        return jsonify({"error": "The overlay's file is required.", "code": "missing_file"}), 400
    # One byte past the limit is all it takes to know it is over.
    data = upload.read(MAX_FILE_BYTES + 1)
    if len(data) > MAX_FILE_BYTES:
        return jsonify({"error": "This overlay is too large to save.", "code": "too_large"}), 413
    if not _looks_like_an_overlay(data):
        return jsonify({"error": "This is not a KMZ or KML file.", "code": "not_an_overlay"}), 400
    if _full(user_id):
        return _too_many()
    saved = SavedOverlay(
        user_id=user_id,
        name=name,
        file=data,
        file_name=(upload.filename or f"{name}.kmz")[:MAX_FILE_NAME],
        file_size=len(data),
        summary=(request.form.get('summary') or '')[:MAX_SUMMARY],
    )
    db.session.add(saved)
    db.session.commit()
    return _private(jsonify(_summary(saved))), 201


@overlay_bp.route('/api/overlays/<int:overlay_id>/file', methods=['GET'])
@jwt_required()
def get_overlay_file(overlay_id):
    saved = _mine(int(get_jwt_identity()), overlay_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    return _private(send_file(
        io.BytesIO(saved.file),
        mimetype='application/octet-stream',
        as_attachment=True,
        download_name=saved.file_name or f"{saved.name}.kmz",
    ))


@overlay_bp.route('/api/overlays/<int:overlay_id>', methods=['PUT'])
@jwt_required()
@require_feature('cloud_save')
def rename_overlay(overlay_id):
    saved = _mine(int(get_jwt_identity()), overlay_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    name = _clean_name((request.get_json(silent=True) or {}).get('name'))
    if name is None:
        return jsonify({"error": f"A name of 1 to {MAX_NAME} characters is required.", "code": "invalid_name"}), 400
    saved.name = name
    db.session.commit()
    return _private(jsonify(_summary(saved)))


@overlay_bp.route('/api/overlays/<int:overlay_id>/duplicate', methods=['POST'])
@jwt_required()
@require_feature('cloud_save')
def duplicate_overlay(overlay_id):
    user_id = int(get_jwt_identity())
    saved = _mine(user_id, overlay_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    name = _clean_name((request.get_json(silent=True) or {}).get('name'))
    if name is None:
        return jsonify({"error": f"A name of 1 to {MAX_NAME} characters is required.", "code": "invalid_name"}), 400
    if _full(user_id):
        return _too_many()
    copy = SavedOverlay(user_id=user_id, name=name, file=saved.file, file_name=saved.file_name, file_size=saved.file_size, summary=saved.summary)
    db.session.add(copy)
    db.session.commit()
    return _private(jsonify(_summary(copy))), 201


@overlay_bp.route('/api/overlays/<int:overlay_id>', methods=['DELETE'])
@jwt_required()
def delete_overlay(overlay_id):
    saved = _mine(int(get_jwt_identity()), overlay_id)
    if not saved:
        return jsonify({"error": "Not found"}), 404
    db.session.delete(saved)
    db.session.commit()
    return jsonify({"status": "deleted"})
