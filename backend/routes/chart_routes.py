"""The VFR chart tiles and each build's ``coverage.json`` (docs/VFR_CHARTS.md §7): public, outside ``/api`` on purpose.

Public FAA data, so no sign-in, and outside ``/api`` so neither the affiliation gate nor flask-cors's ``/api/*`` rules apply: each
answer sets its own CORS header (the LZ card's capture draws tiles with ``crossOrigin="anonymous"``) and its own caching:
- a tile, the transparent tile and ``coverage.json``: ``public, max-age=31536000, immutable`` (a build never changes under its name);
- outside a build's levels or bounds, or numbers that are not a tile's: 404, ``public, max-age=86400``;
- a build ``state.json`` does not list (deleted, never made), or whose files will not open: 404, ``no-store`` and
  ``X-Chart-Build: gone``, so no cache keeps the refusal and a client's next tile error asks for the config again (§4.5).
"""
import re

from flask import Blueprint, Response, jsonify

from charts import serve, state

chart_bp = Blueprint('charts', __name__)

_NUMBER = re.compile(r"^(0|[1-9][0-9]{0,6})$")
IMMUTABLE = "public, max-age=31536000, immutable"
OUT_OF_RANGE = "public, max-age=86400"


def _headers(response, cache):
    response.headers['Cache-Control'] = cache
    response.headers['Access-Control-Allow-Origin'] = '*'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


def _gone():
    response = _headers(jsonify({"error": "build_gone"}), 'no-store')
    response.headers['X-Chart-Build'] = 'gone'
    return response, 404


def _out_of_range():
    return _headers(jsonify({"error": "out_of_range"}), OUT_OF_RANGE), 404


@chart_bp.route('/charts/vfr/<build>/<z>/<x>/<y>.webp', methods=['GET'])
def chart_tile(build, z, x, y):
    where = state.root()
    try:
        serve.REGISTRY.build(where, build)
        if not all(_NUMBER.match(n) for n in (z, x, y)):
            return _out_of_range()
        data = serve.REGISTRY.tile(where, build, int(z), int(x), int(y))
    except serve.Gone:
        return _gone()
    except serve.OutOfRange:
        return _out_of_range()
    return _headers(Response(data, mimetype='image/webp'), IMMUTABLE)


@chart_bp.route('/charts/vfr/<build>/coverage.json', methods=['GET'])
def chart_coverage(build):
    try:
        data = serve.REGISTRY.coverage(state.root(), build)
    except serve.Gone:
        return _gone()
    return _headers(Response(data, mimetype='application/json'), IMMUTABLE)
