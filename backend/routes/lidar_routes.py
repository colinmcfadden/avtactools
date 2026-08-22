"""Serving pre-generated LiDAR point cloud tilesets.

Tilesets are built offline by ``python -m lidar`` and served from disk here.
Generation needs PDAL and py3dtiles — a four-gigabyte toolchain the API has no
reason to carry — and an LZ does not move, so the work is done once and cached
rather than on request.

Everything is behind JWT. The point data itself is public domain, but *which*
places have tilesets is not: an unauthenticated endpoint would let anyone
enumerate where a unit has been planning to land.
"""

from flask import Blueprint, jsonify, request, send_from_directory
from flask_jwt_extended import jwt_required

from lidar import catalog
from lidar.aoi import DEFAULT_RADIUS_M

lidar_bp = Blueprint("lidar", __name__)

# Cesium fetches a tileset's children itself, so these responses are hit far
# more often than the app's own endpoints and the content never changes for a
# given key — the key includes everything that determines the contents.
TILE_CACHE_SECONDS = 7 * 24 * 3600


def _tileset_url(key: str) -> str:
    """Where the frontend should point Cesium.

    Relative to the API root rather than the site root: the frontend's API base
    already carries the `/api` prefix, so including it here would double it.
    """
    return f"/lidar/tilesets/{key}/tileset.json"


@lidar_bp.route("/api/lidar/tilesets/<key>", methods=["GET"])
@jwt_required()
def tileset_status(key):
    """Whether a tileset has been generated for this key."""
    if not catalog.is_valid_key(key):
        return jsonify({"error": "Malformed tileset key."}), 400
    if not catalog.exists(key):
        return jsonify({
            "key": key,
            "available": False,
            "message": "No point cloud has been generated for this location yet.",
        }), 404
    return jsonify({
        "key": key,
        "available": True,
        "url": _tileset_url(key),
    })


@lidar_bp.route("/api/lidar/tilesets/<key>/<path:filename>", methods=["GET"])
@jwt_required()
def tileset_file(key, filename):
    """Serve one file from a tileset — the root json or any of its tiles."""
    directory = catalog.path_for(key)
    if directory is None:
        return jsonify({"error": "Malformed tileset key."}), 400
    if not directory.is_dir():
        return jsonify({"error": "Tileset not found."}), 404

    # send_from_directory refuses to escape the directory it is given, so a
    # traversal in `filename` is rejected rather than followed.
    response = send_from_directory(directory, filename,
                                   max_age=TILE_CACHE_SECONDS,
                                   conditional=True)
    # max_age alone emits "public", which would let Cloudflare or any other
    # shared cache hold a tile and hand it to a different user. These are
    # behind a bearer token and their content is an LZ's location, so the
    # caching has to stay in the requesting browser.
    response.cache_control.public = False
    response.cache_control.private = True
    return response


@lidar_bp.route("/api/lidar/tilesets", methods=["GET"])
@jwt_required()
def list_tilesets():
    """Keys with a tileset on disk, so a client can resolve several at once."""
    return jsonify({"keys": catalog.available()})


@lidar_bp.route("/api/lidar/resolve", methods=["POST"])
@jwt_required()
def resolve():
    """Key for a target, and whether it has been generated.

    A POST rather than a GET with query parameters: the coordinates are the
    sensitive part, and this keeps them out of URLs, request logs and the
    browser's history.
    """
    data = request.get_json(silent=True) or {}
    try:
        lat = float(data["lat"])
        lon = float(data["lon"])
    except (KeyError, TypeError, ValueError):
        return jsonify({"error": "lat and lon are required."}), 400

    radius = data.get("radius_m")
    try:
        radius = float(radius) if radius is not None else DEFAULT_RADIUS_M
    except (TypeError, ValueError):
        return jsonify({"error": "radius_m must be a number."}), 400
    if not 50 <= radius <= 2000:
        return jsonify({"error": "radius_m must be between 50 and 2000."}), 400

    # Coverage first, exact key second. A target is rarely re-entered to the
    # last decimal — it arrives from an MGRS round-trip, a map click, or a
    # nudged marker — and hashing rounded coordinates turns a tenth of a metre
    # across a cell boundary into a different key. Asking which built area
    # contains the target answers the question actually being asked.
    key = catalog.find_covering(lat, lon)
    if key is None:
        key = catalog.key_for(lat, lon, radius_m=radius)

    available = catalog.exists(key)
    body = {
        "key": key,
        "available": available,
        "url": _tileset_url(key) if available else None,
    }
    if not available:
        # Echo the target back so the client can say how to build it. These
        # are the caller's own coordinates, already in the request body, so
        # nothing new is disclosed — and without them the client can only
        # report an opaque key nobody can act on.
        body["target"] = {"lat": lat, "lon": lon, "radius_m": radius}
    return jsonify(body)
