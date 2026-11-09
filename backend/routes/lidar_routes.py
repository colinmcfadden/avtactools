"""Serving LiDAR point cloud tilesets, and asking for missing ones to be built.

Tilesets are served from disk here. Generating one needs PDAL and py3dtiles — a
multi-gigabyte toolchain the API has no reason to carry — so builds run in a
separate service (``lidar.worker``) that writes into the same store. The API
only forwards requests to it and relays progress; see ``lidar_builder``. An LZ
does not move, so each place is built once and then served from cache.

Everything is behind JWT. The point data itself is public domain, but *which*
places have tilesets is not: an unauthenticated endpoint would let anyone
enumerate where a unit has been planning to land.
"""

import re

from flask import Blueprint, jsonify, request, send_file, send_from_directory
from flask_jwt_extended import get_jwt_identity, jwt_required

import entitlements
import lidar_builder
from lidar import catalog, stream
from lidar.aoi import DEFAULT_RADIUS_M

lidar_bp = Blueprint("lidar", __name__)

# Cesium fetches a tileset's children itself, so these responses are hit far
# more often than the app's own endpoints and the content never changes for a
# given key — the key includes everything that determines the contents.
TILE_CACHE_SECONDS = 7 * 24 * 3600
PALETTE_CACHE_SECONDS = 3600

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


def _streamed_body(key, lat, lon, radius):
    """A target's survey, streamed (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md,
    Part C). Its tileset is the whole survey, a state's octree, so ``frame``
    says where to look: the caller's own place, its radius, and the ground's
    ellipsoidal height there (null where nothing says it)."""
    return {
        "key": key,
        "available": True,
        "streamed": True,
        "url": _tileset_url(key),
        "contextUrl": None,
        "frame": {"lat": lat, "lon": lon, "radius_m": radius,
                  "height_m": _ground_height(lat, lon)},
    }


def _ground_height(lat, lon):
    """Ellipsoidal metres at a place: the 3D terrain's own DEMs first, so the
    view opens on the ground it draws, else Terrarium's (USGS's NED) plus the
    geoid. None rather than a guess."""
    try:
        import terrain_tiles
        ground, _geoid = terrain_tiles.sample_points([(lat, lon)])
        if ground[0] is not None:
            return ground[0]
        from routes.terrain_routes import _M_TO_FT, _sample_elevations_ft
        feet = _sample_elevations_ft([{"lat": lat, "lon": lon}])[0]
        if feet is None:
            return None
        separation = terrain_tiles.geoid_offset(lon, lat)
        if abs(separation) < 1.0:
            return None
        return round(feet / _M_TO_FT + separation, 1)
    except Exception:  # noqa: BLE001 — framing falls back to the tileset
        return None


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
        streamer = stream.streamer()
        if streamer is not None and streamer.has_survey(key):
            return _streamed_file(streamer, key, filename)
        return jsonify({"error": "Tileset not found."}), 404
    # The same 404 as a file that is not there, so a refusal says nothing
    # about what the folder holds.
    if not catalog.is_served_file(filename):
        return jsonify({"error": "File not found."}), 404

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


def _streamed_file(streamer, key, filename):
    try:
        path = streamer.file(key, filename)
    except stream.StreamError as error:
        return jsonify({"error": str(error), "code": error.code}), error.status
    # A piece still in its palette is kept an hour, so the photograph the
    # background pass lays on (lidar.photo) reaches a later view.
    waiting = filename.endswith(".pnts") and not streamer.photographic(key, filename)
    response = send_file(path, conditional=True,
                         max_age=PALETTE_CACHE_SECONDS if waiting else TILE_CACHE_SECONDS,
                         mimetype=("application/json" if filename.endswith(".json")
                                   else "application/octet-stream"))
    response.cache_control.public = False
    response.cache_control.private = True
    return response


@lidar_bp.route("/api/lidar/tilesets", methods=["GET"])
@jwt_required()
def list_tilesets():
    """Keys with a tileset on disk: admins only.

    It is every place a 3D view was ever built, and neither app calls it.
    """
    if not _caller_is_admin():
        return jsonify({"error": "Admins only.", "code": "admin_required"}), 403
    return jsonify({"keys": catalog.available()})


def _caller_is_admin():
    from models import User, db
    try:
        user = db.session.get(User, int(get_jwt_identity()))
    except (TypeError, ValueError):
        user = None
    return entitlements.is_admin(user) and entitlements.account_active(user)


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
        lat, lon, radius = _parse_target(data, max_radius=2000)
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

    # Streamed only to a client that says it frames on ``frame``: one that
    # frames on the tileset's own sphere would open on the whole state, so it
    # keeps the build flow.
    streamer = stream.streamer() if data.get("stream") is True else None
    if streamer is not None:
        try:
            found = streamer.survey_for(lat, lon)
        except Exception:  # noqa: BLE001 — the coverage index is unreachable
            found = None
        if found is not None:
            return jsonify(_streamed_body(found[1], lat, lon, radius))

    return jsonify({
        "key": key,
        "available": False,
        "url": None,
        "contextUrl": None,
        # Whether asking for a build will do anything. Without the service the
        # client falls back to telling an operator what to run.
        "canBuild": lidar_builder.configured(),
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

    if not lidar_builder.configured():
        return jsonify({"error": "Point clouds cannot be built on this server.",
                        "code": "builder_not_configured"}), 503
    try:
        job = lidar_builder.submit(lat, lon, radius, keep=data.get("keep") is True,
                                   watcher=_watcher_id(data.get("watcher")))
    except lidar_builder.BuilderBusy as error:
        return jsonify({"error": str(error), "code": "builder_busy"}), 429
    except lidar_builder.BuilderUnavailable as error:
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
    if not lidar_builder.configured():
        return jsonify({"error": "Point clouds cannot be built on this server.",
                        "code": "builder_not_configured"}), 503
    try:
        job = lidar_builder.status(key, watcher=_watcher_id(request.args.get("watcher")),
                                   keep=request.args.get("keep") == "1")
    except lidar_builder.BuilderUnavailable as error:
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
    if not lidar_builder.configured():
        return ("", 204)
    try:
        job = lidar_builder.release(key, watcher=_watcher_id(request.args.get("watcher")))
    except lidar_builder.BuilderUnavailable as error:
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
