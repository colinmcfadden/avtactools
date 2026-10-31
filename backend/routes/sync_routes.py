from flask import Blueprint, jsonify, request
from flask_jwt_extended import get_jwt_identity, jwt_required

import sync_support as sync

sync_bp = Blueprint('sync', __name__)


@sync_bp.route('/api/sync/changes', methods=['GET'])
@jwt_required()
def changes():
    """Everything of the caller's that changed after ``since``, deletions included.

    A device keeps the returned ``cursor`` and sends it back as ``since`` next time;
    ``has_more`` means ask again straight away. Nothing is returned for a record the
    device has already seen at that cursor, and nothing is ever skipped: the order
    is a per-user counter, not a timestamp (see sync_support).
    """
    try:
        since = int(request.args.get('since', 0))
        limit = int(request.args.get('limit', sync.DEFAULT_PAGE))
    except ValueError:
        return jsonify({"error": "since and limit must be integers", "code": "invalid_cursor"}), 400
    if since < 0 or limit < 1:
        return jsonify({"error": "since must be 0 or more and limit at least 1", "code": "invalid_cursor"}), 400
    limit = min(limit, sync.MAX_PAGE)

    page, cursor, has_more = sync.changes_since(int(get_jwt_identity()), since, limit)
    response = jsonify({"cursor": cursor, "has_more": has_more, "changes": page})
    # One user's data, and it changes: never cache it.
    response.headers['Cache-Control'] = 'private, no-store'
    return response
