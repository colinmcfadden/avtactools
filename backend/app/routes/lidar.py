"""Serving LiDAR point cloud tilesets, and asking for missing ones to be built.

Tilesets are served from disk here. Generating one needs PDAL and py3dtiles — a
multi-gigabyte toolchain the API has no reason to carry — so builds run in a
separate service (``lidar.worker``) that writes into the same store. The API
only forwards requests to it and relays progress; see ``lidar_client``. An LZ
does not move, so each place is built once and then served from cache.

Everything is behind JWT. The point data itself is public domain, but *which*
places have tilesets is not: an unauthenticated endpoint would let anyone
enumerate where a unit has been planning to land.
"""

import re

from flask import Blueprint, jsonify, request, send_from_directory
from flask_jwt_extended import jwt_required

from app.services import lidar_client
from lidar import catalog
from lidar.aoi import DEFAULT_RADIUS_M

lidar_bp = Blueprint("lidar", __name__)

# Cesium fetches a tileset's children itself, so these responses are hit far
# more often than the app's own endpoints and the content never changes for a
# given key — the key includes everything that determines the contents.
TILE_CACHE_SECONDS = 7 * 24 * 3600

# Builds are interactive — someone is waiting in the 3D window — so their area
# is capped tighter than lookups are. Matches the service's own limit.
BUILD_RADIUS_MAX_M = 1000


class _BadTarget(ValueError):
    pass


def _parse_target(data, *, max_radius):
    """lat, lon and radius from a JSON body, or _BadTarget with a reason."""
    try:
        lat = float(data["lat"])
        lon = float(data["lon"])
    except (KeyError, TypeError, ValueError):
        raise _BadTarget("lat and lon are required.") from None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise _BadTarget("lat and lon are out of range.")

    radius = data.get("radius_m")
    try:
        radius = float(radius) if radius is not None else DEFAULT_RADIUS_M
    except (TypeError, ValueError):
        raise _BadTarget("radius_m must be a number.") from None
    if not 50 <= radius <= max_radius:
        raise _BadTarget(f"radius_m must be between 50 and {max_radius:.0f}.")
    return lat, lon, radius


def _available_body(key):
    return {
        "key": key,
        "available": True,
        "url": _tileset_url(key),
        # Optional: only built when a context ring was asked for. The viewer
        # draws it under the core so range never costs detail at the centre.
        "contextUrl": _context_url(key) if catalog.has_context(key) else None,
    }


def _context_url(key: str) -> str:
    """The wider, thinned ring built beside a core tileset."""
    return f"/lidar/tilesets/{key}/{catalog.CONTEXT_DIRNAME}/tileset.json"


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
    try:
        lat, lon, radius = _parse_target(request.get_json(silent=True) or {},
                                         max_radius=2000)
    except _BadTarget as error:
        return jsonify({"error": str(error)}), 400

    # Coverage first, exact key second. A target is rarely re-entered to the
    # last decimal — it arrives from an MGRS round-trip, a map click, or a
    # nudged marker — and hashing rounded coordinates turns a tenth of a metre
    # across a cell boundary into a different key. Asking which built area
    # contains the target answers the question actually being asked.
    key = catalog.find_covering(lat, lon)
    if key is None:
        key = catalog.key_for(lat, lon, radius_m=radius)

    if catalog.exists(key):
        return jsonify(_available_body(key))

    return jsonify({
        "key": key,
        "available": False,
        "url": None,
        "contextUrl": None,
        # Whether asking for a build will do anything. Without the service the
        # client falls back to telling an operator what to run.
        "canBuild": lidar_client.configured(),
        # Echo the target back so the client can say how to build it. These
        # are the caller's own coordinates, already in the request body, so
        # nothing new is disclosed — and without them the client can only
        # report an opaque key nobody can act on.
        "target": {"lat": lat, "lon": lon, "radius_m": radius},
    })


@lidar_bp.route("/api/lidar/build", methods=["POST"])
@jwt_required()
def start_build():
    """Ask the build service for a point cloud at this target.

    Idempotent: asking again while a build is queued or running returns that
    build, and asking for somewhere already built returns it as done. The 3D
    window calls this whenever it opens on unbuilt ground.

    ``watcher`` is an opaque id for the browser tab waiting on the build; the
    service drops a build nobody is waiting for. ``keep`` (a saved LZ) asks
    for it to finish regardless.
    """
    data = request.get_json(silent=True) or {}
    try:
        lat, lon, radius = _parse_target(data, max_radius=BUILD_RADIUS_MAX_M)
    except _BadTarget as error:
        return jsonify({"error": str(error)}), 400

    covering = catalog.find_covering(lat, lon)
    if covering:
        return jsonify({"state": "done", **_available_body(covering)})

    if not lidar_client.configured():
        return jsonify({"error": "Point clouds cannot be built on this server.",
                        "code": "builder_not_configured"}), 503
    try:
        job = lidar_client.submit(lat, lon, radius, keep=data.get("keep") is True,
                                   watcher=_watcher_id(data.get("watcher")))
    except lidar_client.BuilderBusy as error:
        return jsonify({"error": str(error), "code": "builder_busy"}), 429
    except lidar_client.BuilderUnavailable as error:
        return jsonify({"error": str(error), "code": "builder_unavailable"}), 503
    return jsonify(_job_body(job)), 202


@lidar_bp.route("/api/lidar/build/<key>", methods=["GET"])
@jwt_required()
def build_status(key):
    """Progress of a build, by its opaque key — no coordinates in the URL."""
    if not catalog.is_valid_key(key):
        return jsonify({"error": "Malformed tileset key."}), 400
    if catalog.exists(key):
        return jsonify({"state": "done", **_available_body(key)})
    if not lidar_client.configured():
        return jsonify({"error": "Point clouds cannot be built on this server.",
                        "code": "builder_not_configured"}), 503
    try:
        job = lidar_client.status(key, watcher=_watcher_id(request.args.get("watcher")),
                                   keep=request.args.get("keep") == "1")
    except lidar_client.BuilderUnavailable as error:
        return jsonify({"error": str(error), "code": "builder_unavailable"}), 503
    if job is None:
        return jsonify({"error": "No build is running for this location.",
                        "code": "no_build"}), 404
    return jsonify(_job_body(job))


@lidar_bp.route("/api/lidar/build/<key>", methods=["DELETE"])
@jwt_required()
def release_build(key):
    """Stop waiting for a build. The service drops it if nobody else is waiting
    and it was not asked to be kept; a finished or unknown build is a no-op."""
    if not catalog.is_valid_key(key):
        return jsonify({"error": "Malformed tileset key."}), 400
    if not lidar_client.configured():
        return ("", 204)
    try:
        job = lidar_client.release(key, watcher=_watcher_id(request.args.get("watcher")))
    except lidar_client.BuilderUnavailable as error:
        return jsonify({"error": str(error), "code": "builder_unavailable"}), 503
    if job is None:
        return ("", 204)
    return jsonify(_job_body(job))


# Opaque per-tab ids; anything else is dropped rather than forwarded.
_WATCHER_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def _watcher_id(value):
    return value if isinstance(value, str) and _WATCHER_ID.match(value) else None


def _job_body(job):
    """The service's view of a job, plus where to find the result when done."""
    body = {k: job.get(k) for k in ("key", "state", "stage", "error",
                                    "position", "elapsed_s")}
    key = job.get("key")
    if job.get("state") == "done" and key and catalog.exists(key):
        body.update(_available_body(key))
    return body
