"""``POST /api/places/search``: places by name or identifier, for the web's search (docs/PLACE_SEARCH.md §6).

The text is in the body, never the URL, so no request log, proxy or analytics names a place, and it is never
logged here: an error says its kind only. ``near`` only orders the answer, and is rounded to 0.1° before it is
used. The answer is ``Cache-Control: no-store``. Behind ``place_search`` (not launched: ``entitlements.DEFAULT_OFF``).

Web only for now: not in ``contracts/openapi.yaml``, so no app relies on it and the API version does not move.
Android is to search its own copy of the index on the device (§7).
"""

import logging
import math

from flask import Blueprint, jsonify, request
from flask_jwt_extended import get_jwt_identity, jwt_required

from auth_rate_limit import check_rate_limits
from entitlements import require_feature
from places import store
from places.search import MAX_LIMIT

place_bp = Blueprint("places", __name__)
log = logging.getLogger("places")

MAX_TEXT = 100
# Per person: a lookup is made at most once per pause in typing, so ten a second is far more than anyone types.
RATE = (10, 1)


def _refuse(message, code, status):
    response = jsonify({"error": message, "code": code})
    response.status_code = status
    response.headers["Cache-Control"] = "no-store"
    return response


def _near(value):
    if not isinstance(value, dict):
        return None
    lat, lon = value.get("lat"), value.get("lon")
    if isinstance(lat, bool) or isinstance(lon, bool) or not isinstance(lat, (int, float)) \
            or not isinstance(lon, (int, float)):
        return None
    if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90):
        return None
    lon = ((lon + 180) % 360) - 180
    return round(lat, 1), round(lon, 1)


@place_bp.route("/api/places/search", methods=["POST"])
@jwt_required()
@require_feature("place_search")
def search_places():
    retry_after = check_rate_limits([("places:user", get_jwt_identity(), *RATE)])
    if retry_after:
        response = _refuse("Too many searches at once. Try again in a moment.", "rate_limited", 429)
        response.headers["Retry-After"] = str(retry_after)
        return response
    body = request.get_json(silent=True)
    if not isinstance(body, dict) or not isinstance(body.get("q"), str):
        return _refuse("Send the text to search for as q.", "invalid_query", 400)
    text = body["q"]
    if len(text) > MAX_TEXT:
        return _refuse(f"Search for at most {MAX_TEXT} characters.", "invalid_query", 400)
    limit = body.get("limit", 8)
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= MAX_LIMIT:
        return _refuse(f"limit is a whole number from 1 to {MAX_LIMIT}.", "invalid_limit", 400)
    try:
        status, places = store.search(text, _near(body.get("near")), limit)
    except Exception as error:  # noqa: BLE001 — said by its kind alone: the request holds what was searched for
        log.warning("places: search failed (%s)", type(error).__name__)
        return _refuse("The place search failed.", "search_failed", 500)
    response = jsonify({**status, "places": places})
    response.headers["Cache-Control"] = "no-store"
    return response
