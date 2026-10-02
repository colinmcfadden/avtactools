"""
Threat terrain-masking and .ths export.

Two endpoints:
  POST /api/threat-mask   radial line-of-sight viewshed over an open DEM, returned
                          as translucent PNG overlays (per radar / altitude band).
  POST /api/threats-ths   builds an AMPS .ths (SQLite) threat overlay file from the
                          in-memory threats, using the bundled cleaned template so the
                          exact AMPS schema is preserved.

Elevation data is the open AWS "Terrarium" terrain-RGB tile set (no API key), decoded
to metres. Threats are export-only. Secure QR handoffs keep a bounded KMZ in process
memory for no more than ten minutes; they are never written to the database or URL.
"""

import io

from flask import Blueprint, current_app, request, jsonify, send_file, url_for
from flask_jwt_extended import jwt_required, verify_jwt_in_request

from app.security.entitlements import require_feature
from app.services.terrain import tiles as terrain_tiles
from app.services.threats.kmz import build_threats_kmz
from app.services.threats.ths import build_ths_bytes
from app.services.threats.viewshed import (
    NMI_TO_M,
    fetch_dem,
    radar_elev_m,
    render_mask_png,
)
from app.stores.threat_downloads import ThreatDownloadStore

threat_bp = Blueprint('threat', __name__)

THREAT_QR_TTL_SECONDS = 10 * 60
THREAT_QR_MAX_DOWNLOADS = 3
_threat_download_store = ThreatDownloadStore(
    ttl_seconds=THREAT_QR_TTL_SECONDS,
    max_downloads=THREAT_QR_MAX_DOWNLOADS,
    max_entries=128,
)


@threat_bp.route('/api/threat-mask', methods=['POST'])
@jwt_required()
@require_feature('threats')
@terrain_tiles.pauses_warming
def threat_mask():
    """
    Body: { lat, lon, radars: [ {
      type, rangeNmi, antennaHeightFt, aglNotMsl, showMask,
      bands: [ {altFt, color, alpha, viewable} ]
    } ] }
    Returns: { bounds: [[south,west],[north,east]], radars: [ {type, png} ] }.
    """
    try:
        body = request.get_json(silent=True) or {}
        lat = float(body['lat']); lon = float(body['lon'])
        radars = body.get('radars', [])
        if not radars:
            return jsonify({'error': 'No radars supplied'}), 400

        max_range_m = max(float(r.get('rangeNmi', 0)) for r in radars) * NMI_TO_M
        if max_range_m <= 0:
            return jsonify({'error': 'Radar range must be positive'}), 400

        dem, meta = fetch_dem(lat, lon, max_range_m)
        if dem is None:
            return jsonify({'error': 'Terrain data unavailable for this area'}), 502

        out_radars = []
        for radar in radars:
            if not radar.get('showMask', True):
                continue
            bands = [b for b in radar.get('bands', []) if b.get('viewable', True)]
            if not bands:
                continue
            radar_elev = radar_elev_m(dem, meta, radar.get('antennaHeightFt', 0),
                                      radar.get('aglNotMsl'))
            range_px = int(round(float(radar['rangeNmi']) * NMI_TO_M / meta['mpp']))
            range_px = min(range_px, max(dem.shape))
            png = render_mask_png(dem, meta, radar_elev, bands, range_px)
            if png:
                out_radars.append({'type': radar.get('type', 0), 'png': png})

        bounds = [[meta['south'], meta['west']], [meta['north'], meta['east']]]
        return jsonify({'bounds': bounds, 'radars': out_radars})

    except (KeyError, ValueError, TypeError) as e:
        return jsonify({'error': f'Bad request: {e}'}), 400
    except Exception as e:  # noqa: BLE001
        import traceback; traceback.print_exc()
        return jsonify({'error': str(e)}), 500


# --- KMZ overlay (ForeFlight / ATAK / Aero App) -------------------------------


def _kmz_name(body):
    name = str(body.get('fileName') or 'threats.kmz')
    if not name.lower().endswith('.kmz'):
        name += '.kmz'
    return name


def _no_store(response):
    response.headers['Cache-Control'] = 'no-store, max-age=0'
    response.headers['Pragma'] = 'no-cache'
    response.headers['Referrer-Policy'] = 'no-referrer'
    response.headers['X-Robots-Tag'] = 'noindex, nofollow'
    return response


@threat_bp.route('/api/threats-kmz-link', methods=['POST'])
@jwt_required()
@require_feature('threats')
def create_threats_kmz_link():
    """Create a short-lived public KMZ link for a QR code.

    The authenticated request supplies the threats once. Only an opaque random
    token is returned and placed in the QR URL; the generated KMZ remains in
    process memory until it expires or reaches its download limit.
    """
    try:
        body = request.get_json(silent=True) or {}
        threats = body.get('threats', [])
        if not threats:
            return jsonify({'error': 'No threats supplied'}), 400

        data = build_threats_kmz(threats)
        try:
            token = _threat_download_store.create(data, _kmz_name(body))
        except ValueError:
            return jsonify({
                'error': (
                    'This KMZ is too large for a temporary QR link. '
                    'Use the direct KMZ download instead.'
                ),
            }), 413
        response = jsonify({
            'downloadPath': url_for('threat.threats_kmz', token=token),
            'expiresInSeconds': THREAT_QR_TTL_SECONDS,
            'maxDownloads': THREAT_QR_MAX_DOWNLOADS,
        })
        return _no_store(response), 201
    except Exception:  # noqa: BLE001
        current_app.logger.exception('Unable to create threat KMZ QR link')
        return jsonify({
            'error': 'Unable to create a secure threat download link',
        }), 500


@threat_bp.route('/api/threats-kmz', methods=['GET', 'POST'])
def threats_kmz():
    """Download a KMZ directly (authenticated POST) or by an opaque QR token."""
    if request.method == 'POST':
        verify_jwt_in_request()
    try:
        if request.method == 'GET':
            # Explicitly reject the old URL-embedded payload format. Threat
            # details must never be carried in URLs, browser history, or logs.
            if request.args.get('data') is not None:
                return jsonify({
                    'error': 'Legacy data links are no longer supported',
                }), 400

            if set(request.args.keys()) - {'token'}:
                return jsonify({'error': 'Unexpected download parameter'}), 400

            token = request.args.get('token', '')
            if not token or len(request.args.getlist('token')) != 1:
                return jsonify({'error': 'Missing download token'}), 400
            download, status = _threat_download_store.take(token)
            if status == 'expired':
                return jsonify({'error': 'This download link has expired'}), 410
            if download is None:
                return jsonify({'error': 'Download link not found'}), 404

            response = send_file(
                io.BytesIO(download.contents),
                mimetype='application/vnd.google-earth.kmz',
                as_attachment=True,
                download_name=download.file_name,
                max_age=0,
            )
            response.headers['X-Downloads-Remaining'] = str(
                download.remaining_downloads
            )
            return _no_store(response)

        body = request.get_json(silent=True) or {}
        threats = body.get('threats', [])
        if not threats:
            return jsonify({'error': 'No threats supplied'}), 400
        data = build_threats_kmz(threats)
        response = send_file(
            io.BytesIO(data),
            mimetype='application/vnd.google-earth.kmz',
            as_attachment=True,
            download_name=_kmz_name(body),
        )
        return _no_store(response)
    except Exception as e:  # noqa: BLE001
        import traceback; traceback.print_exc()
        return jsonify({'error': str(e)}), 500


@threat_bp.route('/api/threats-ths', methods=['POST'])
@jwt_required()
@require_feature('threats')
def threats_ths():
    """Body: { threats: [...], fileName? }. Returns the .ths file for download."""
    try:
        body = request.get_json(silent=True) or {}
        threats = body.get('threats', [])
        if not threats:
            return jsonify({'error': 'No threats supplied'}), 400
        data = build_ths_bytes(threats)
        name = body.get('fileName') or 'threats.ths'
        if not name.lower().endswith('.ths'):
            name += '.ths'
        return send_file(io.BytesIO(data), mimetype='application/octet-stream',
                         as_attachment=True, download_name=name)
    except Exception as e:  # noqa: BLE001
        import traceback; traceback.print_exc()
        return jsonify({'error': str(e)}), 500
