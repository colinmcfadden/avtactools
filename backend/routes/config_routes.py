import os

from flask import Blueprint, jsonify

import lidar_builder
from app_config import build_config

try:
    from version import __version__
except ImportError:
    __version__ = "0.0.0-dev"

config_bp = Blueprint('config', __name__)


def _default_mapbox_token():
    """The public token the export code already ships, used until MAPBOX_PUBLIC_TOKEN is set."""
    from export_service import MAPBOX_ACCESS_TOKEN
    return MAPBOX_ACCESS_TOKEN


@config_bp.route('/api/config', methods=['GET'])
def app_config():
    """Public, unauthenticated: what an app needs before anyone signs in.

    The same answer for everyone, so a short shared cache is safe (and spares the
    single gunicorn worker a request per app launch). ``public`` is deliberate
    here and nowhere near user data: auth-gated responses use ``private``.
    """
    payload = build_config(
        os.environ,
        server_version=__version__,
        lidar_builds=lidar_builder.configured(),
        mapbox_default=_default_mapbox_token(),
    )
    response = jsonify(payload)
    response.headers['Cache-Control'] = 'public, max-age=60'
    return response
