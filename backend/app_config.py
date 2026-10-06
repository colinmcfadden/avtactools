"""The public ``GET /api/config`` payload.

Store builds stay in the field for months, so the server has to be able to say
"update required", announce maintenance, say which optional services are up,
and hand out the Mapbox public token without a new app release.

Everything here is public: it is served without a sign-in, so nothing secret may
ever appear in it. The Mapbox value is only ever a ``pk.`` token for that reason.
"""

import logging
import re

log = logging.getLogger(__name__)

# Bumped if the shape of the payload changes in a way an old app cannot read.
CONFIG_VERSION = 1

_VERSION = re.compile(r"^\d{1,4}\.\d{1,4}\.\d{1,4}$")
_MAINTENANCE_MAX = 500


def _min_version(environ, key):
    """A ``X.Y.Z`` minimum version from the environment, or None. A malformed value
    is ignored (and logged) rather than served: an app comparing against garbage
    could lock every crew out."""
    value = (environ.get(key) or "").strip()
    if not value:
        return None
    if not _VERSION.match(value):
        log.warning("%s=%r is not X.Y.Z; ignoring it", key, value)
        return None
    return value


def public_mapbox_token(environ, default=None):
    """The Mapbox token the apps should use, only if it is a *public* ``pk.`` token.

    This endpoint is anonymous. A ``sk.`` secret token set here by mistake must
    never be served, so anything else is dropped (and logged).
    """
    token = (environ.get("MAPBOX_PUBLIC_TOKEN") or default or "").strip()
    if not token:
        return None
    if not token.startswith("pk."):
        log.warning("MAPBOX_PUBLIC_TOKEN is not a pk. token; not serving it")
        return None
    return token


def build_config(environ, *, server_version, lidar_builds, mapbox_default=None):
    """The payload for ``GET /api/config``.

    ``environ`` is a mapping (``os.environ``). ``lidar_builds`` is whether the
    3D point-cloud build service is configured. ``mapbox_default`` is used when
    ``MAPBOX_PUBLIC_TOKEN`` is unset.
    """
    message = (environ.get("MAINTENANCE_MESSAGE") or "").strip()[:_MAINTENANCE_MAX]
    return {
        "configVersion": CONFIG_VERSION,
        "serverVersion": server_version,
        "minAppVersion": {
            "android": _min_version(environ, "MIN_APP_VERSION_ANDROID"),
            "ios": _min_version(environ, "MIN_APP_VERSION_IOS"),
        },
        "maintenance": {
            "active": bool(message),
            "message": message or None,
        },
        "services": {
            "lidarBuilds": bool(lidar_builds),
            # The map-pack service (offline maps and terrain for an area; docs/NATIVE_APPS_PLAN.md,
            # P3) does not exist yet. Not mission packs, the shared containers in pack_routes.py:
            # the field kept its name because the Android app already reads it.
            "packs": False,
        },
        "mapbox": {
            "publicToken": public_mapbox_token(environ, mapbox_default),
        },
    }
