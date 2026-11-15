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

import os
import io
import math
import re
import base64

import numpy as np
import cv2
from flask import Blueprint, current_app, request, jsonify, send_file, url_for
from flask_jwt_extended import jwt_required, verify_jwt_in_request

import terrain_tiles
from mask_rings import mask_rings
from ths_export import build_ths_bytes
from terrain_provider import load_terrarium_radius
from threat_download_store import ThreatDownloadStore
from entitlements import require_feature

threat_bp = Blueprint('threat', __name__)

FT_TO_M = 0.3048
NMI_TO_M = 1852.0
EARTH_RADIUS_M = 6371000.0
REFRACTION_K = 0.13  # standard atmospheric refraction coefficient
THREAT_QR_TTL_SECONDS = 10 * 60
THREAT_QR_MAX_DOWNLOADS = 3
_threat_download_store = ThreatDownloadStore(
    ttl_seconds=THREAT_QR_TTL_SECONDS,
    max_downloads=THREAT_QR_MAX_DOWNLOADS,
    max_entries=128,
)


def _prep_dem(dem):
    """
    Fills deep voids (nodata) and lightly smooths the DEM. At ~60 m resolution a
    single anomalous cell one pixel from the radar can sit above a low antenna
    and cast a hard, quadrant-wide radial shadow with no real terrain behind it;
    a light 3x3 blur removes those single-cell spikes so shadows follow real
    ridgelines instead of pixel noise.
    """
    d = dem
    void = d <= -1000.0  # Terrarium encodes sea level as 0; only deep voids are nodata
    if void.any() and (~void).any():
        idx = np.arange(d.size)
        flat = d.ravel().copy()
        good = ~void.ravel()
        flat[~good] = np.interp(idx[~good], idx[good], flat[good])
        d = flat.reshape(d.shape)
    return cv2.GaussianBlur(d, (3, 3), 0)


def fetch_dem(lat, lon, radius_m):
    """Shared Terrarium mosaic used by both threat masking and slope analysis."""
    dem, meta = load_terrarium_radius(lat, lon, radius_m)
    if dem is None:
        return None, None
    return _prep_dem(dem.copy()), meta


# --- Viewshed -------------------------------------------------------------

def viewshed(dem, radar_row, radar_col, radar_elev_m, target_agl_m, max_r_px, mpp):
    """
    Vectorized radial line-of-sight viewshed. Marks every cell where an aircraft
    flying target_agl_m above the ground is visible to a radar antenna at
    radar_elev_m (MSL), within max_r_px. Accounts for earth curvature + refraction.
    """
    H, W = dem.shape
    visible = np.zeros((H, W), bool)
    n_az = max(720, int(2 * math.pi * max_r_px))
    steps = np.arange(1, max_r_px + 1)
    dist = steps * mpp
    drop = (1 - REFRACTION_K) * dist * dist / (2 * EARTH_RADIUS_M)  # curvature dip

    az = np.linspace(0, 2 * math.pi, n_az, endpoint=False)
    for ang in az:
        cols = np.round(radar_col + math.cos(ang) * steps).astype(np.intp)
        rows = np.round(radar_row + math.sin(ang) * steps).astype(np.intp)
        ok = (cols >= 0) & (cols < W) & (rows >= 0) & (rows < H)
        if not ok.any():
            continue
        cut = np.argmax(~ok) if (~ok).any() else len(ok)  # stop at first off-grid sample
        if cut == 0:
            continue
        rr, cc, dd, drp = rows[:cut], cols[:cut], dist[:cut], drop[:cut]
        ground = dem[rr, cc]
        # Blocking-terrain angle from the radar, running max along the ray.
        terr_angle = (ground - drp - radar_elev_m) / dd
        prev_max = np.maximum.accumulate(
            np.concatenate(([-np.inf], terr_angle[:-1]))
        )
        target_angle = (ground + target_agl_m - drp - radar_elev_m) / dd
        seen = target_angle >= prev_max
        if seen.any():
            np.logical_or.at(visible.reshape(-1), rr[seen] * W + cc[seen], True)
    return visible


# --- Rendering ------------------------------------------------------------

def _hex_to_bgr(hex_color):
    h = (hex_color or '#ff0000').lstrip('#')
    r = int(h[0:2], 16); g = int(h[2:4], 16); b = int(h[4:6], 16)
    return (b, g, r)


def radar_elev_m(dem, meta, antenna_ft, agl):
    """Antenna MSL elevation. Ground = max over the radar's 3x3 footprint so a
    single coarse DEM cell can't shadow the whole site (see _prep_dem)."""
    rr = int(round(meta['radar_row']))
    cc = int(round(meta['radar_col']))
    r0, r1 = max(0, rr - 1), min(dem.shape[0], rr + 2)
    c0, c1 = max(0, cc - 1), min(dem.shape[1], cc + 2)
    ground = float(dem[r0:r1, c0:c1].max())
    ant = float(antenna_ft) * FT_TO_M
    return (ground + ant) if agl else ant


def render_mask_png(dem, meta, radar_elev_m, bands, max_r_px):
    """
    Composites the viewable altitude bands into one RGBA PNG. Larger (higher)
    bands are drawn first so the more-restrictive low-altitude exposure sits on
    top. Returns a base64 data URL, or None if nothing is visible.
    """
    H, W = dem.shape
    rgba = np.zeros((H, W, 4), np.uint8)
    any_visible = False
    for band in sorted(bands, key=lambda b: -b['altFt']):
        vis = viewshed(dem, meta['radar_row'], meta['radar_col'],
                       radar_elev_m, band['altFt'] * FT_TO_M, max_r_px, meta['mpp'])
        if not vis.any():
            continue
        any_visible = True
        b, g, r = _hex_to_bgr(band.get('color'))
        alpha = int(round(band.get('alpha', 0.35) * 255))
        rgba[vis, 0] = b
        rgba[vis, 1] = g
        rgba[vis, 2] = r
        rgba[vis, 3] = alpha
    if not any_visible:
        return None
    bgra = rgba[:, :, [0, 1, 2, 3]]
    ok, buf = cv2.imencode('.png', bgra)
    if not ok:
        return None
    return 'data:image/png;base64,' + base64.b64encode(buf.tobytes()).decode('ascii')


# --- Endpoints ------------------------------------------------------------

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

def _xml_escape(s):
    return (str(s).replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;'))


def _kml_color(hex_color, alpha):
    """KML aabbggrr from an #rrggbb hex + 0..1 alpha."""
    h = (hex_color or '#ff0000').lstrip('#')
    return '%02x%s%s%s' % (int(max(0, min(1, alpha)) * 255), h[4:6], h[2:4], h[0:2])


def _pixel_to_lonlat(row, col, meta, H, W):
    lon = meta['west'] + (col / W) * (meta['east'] - meta['west'])
    lat = meta['north'] - (row / H) * (meta['north'] - meta['south'])
    return lon, lat


def _viewshed_rings(vis, meta, H, W, min_area_px=8, epsilon_px=1.5):
    """
    Traces a boolean visibility grid into simplified lon/lat polygon rings via
    contour detection, so the mask travels as vector shapes that render in
    ForeFlight/ATAK/Aero App (unlike a raster image overlay).
    """
    rings = []
    for ring in mask_rings(vis, min_area_px, epsilon_px):
        pts = [_pixel_to_lonlat(float(row), float(col), meta, H, W) for col, row in ring]
        pts.append(pts[0])  # close the ring
        rings.append(pts)
    return rings


def _circle_coords(lat, lon, radius_m, n=72):
    coords = []
    for i in range(n + 1):
        th = 2 * math.pi * i / n
        dlat = (radius_m / 111320.0) * math.cos(th)
        dlon = (radius_m / (111320.0 * math.cos(math.radians(lat)))) * math.sin(th)
        coords.append((lon + dlon, lat + dlat))
    return coords


def _coords_str(coords):
    return ' '.join('%.6f,%.6f,0' % (lon, lat) for lon, lat in coords)


MAX_EXPORT_THREATS = 1000  # each one is a viewshed per band; Android refuses a .ths with more (AGENTS.md §17)


def _threat_folder(threat, i):
    """One threat's KML Folder: the terrain mask as filled vector polygons (per radar / altitude band, the same
    viewshed as the on-screen mask), its range rings and its marker."""
    lat = float(threat['lat']); lon = float(threat['lon'])
    name = _xml_escape(threat.get('name') or f'Threat {i}')
    radars = threat.get('radars', [])

    max_range_m = max((float(r.get('rangeNmi', 0)) for r in radars), default=0) * NMI_TO_M
    placemarks = []

    if max_range_m > 0:
        dem, meta = fetch_dem(lat, lon, max_range_m)
        if dem is not None:
            H, W = dem.shape
            for radar in radars:
                if not radar.get('showMask', True):
                    continue
                r_elev = radar_elev_m(dem, meta, radar.get('antennaHeightFt', 0),
                                      radar.get('aglNotMsl'))
                range_px = min(int(round(float(radar.get('rangeNmi', 0)) * NMI_TO_M / meta['mpp'])),
                               max(dem.shape))
                label = 'Engagement' if radar.get('type') == 1 else 'Detection'
                # Highest band first so lower (more restrictive) bands draw on top.
                for band in sorted([b for b in radar.get('bands', []) if b.get('viewable', True)],
                                   key=lambda b: -float(b.get('altFt', 0))):
                    vis = viewshed(dem, meta['radar_row'], meta['radar_col'], r_elev,
                                   float(band['altFt']) * FT_TO_M, range_px, meta['mpp'])
                    rings = _viewshed_rings(vis, meta, H, W)
                    if not rings:
                        continue
                    fill = _kml_color(band.get('color'), band.get('alpha', 0.35))
                    polys = ''.join(
                        f'<Polygon><outerBoundaryIs><LinearRing><coordinates>'
                        f'{_coords_str(r)}</coordinates></LinearRing></outerBoundaryIs></Polygon>'
                        for r in rings)
                    placemarks.append(
                        f'<Placemark><name>{name} — {label} mask {int(band["altFt"])} ft</name>'
                        f'<Style><LineStyle><color>{fill}</color><width>1</width></LineStyle>'
                        f'<PolyStyle><color>{fill}</color></PolyStyle></Style>'
                        f'<MultiGeometry>{polys}</MultiGeometry></Placemark>')

    # Range rings (always vector).
    for radar in radars:
        if not radar.get('showRangeRings', True):
            continue
        rng_m = float(radar.get('rangeNmi', 0)) * NMI_TO_M
        if rng_m <= 0:
            continue
        label = 'Engagement' if radar.get('type') == 1 else 'Detection'
        line = _kml_color('#ef4444' if radar.get('type') == 1 else '#fbbf24', 1.0)
        placemarks.append(
            f'<Placemark><name>{name} — {label} {radar.get("rangeNmi")} nmi</name>'
            f'<Style><LineStyle><color>{line}</color><width>2</width></LineStyle>'
            f'<PolyStyle><fill>0</fill></PolyStyle></Style>'
            f'<LineString><tessellate>1</tessellate><coordinates>'
            f'{_coords_str(_circle_coords(lat, lon, rng_m))}</coordinates></LineString></Placemark>')

    # Threat marker.
    desc = _xml_escape(
        f"{threat.get('milstdId', '')}  {threat.get('information', '')}".strip())
    placemarks.append(
        f'<Placemark><name>{name}</name><description>{desc}</description>'
        f'<Style><IconStyle><color>ff0000ff</color><scale>1.2</scale></IconStyle></Style>'
        f'<Point><coordinates>{lon:.6f},{lat:.6f},0</coordinates></Point></Placemark>')

    return f'<Folder><name>{name}</name>{"".join(placemarks)}</Folder>'


def build_threats_kml(threats, sets=None, title='Threats'):
    """
    The threats as KML text: a Folder for each threat, inside a Folder for each set when `sets` is given
    ([{name, threats}], the open sets of the Threats panel), else at the top. Numbered across the whole file.
    """
    counter = iter(range(1, 1 << 30))
    if sets:
        body = ''.join(
            f'<Folder><name>{_xml_escape(group.get("name") or "Threats")}</name>'
            + ''.join(_threat_folder(threat, next(counter)) for threat in group['threats'])
            + '</Folder>'
            for group in sets)
    else:
        body = ''.join(_threat_folder(threat, next(counter)) for threat in threats)
    return ('<?xml version="1.0" encoding="UTF-8"?>'
            '<kml xmlns="http://www.opengis.net/kml/2.2"><Document>'
            f'<name>{_xml_escape(title)}</name>' + body + '</Document></kml>')


def build_threats_kmz(threats, sets=None, title='Threats'):
    """The KML above, zipped as `doc.kml`. Returns KMZ bytes."""
    buf = io.BytesIO()
    import zipfile
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('doc.kml', build_threats_kml(threats, sets, title))
    return buf.getvalue()


def _export_threats(body):
    """The threats a request asks for, and its sets if it gave any, checked: (threats, sets, error)."""
    raw_sets = body.get('sets')
    if raw_sets is not None:
        if not isinstance(raw_sets, list) or not all(
                isinstance(g, dict) and isinstance(g.get('threats'), list) for g in raw_sets):
            return None, None, 'Each set needs a list of threats'
        sets = [g for g in raw_sets if g['threats']]
        threats = [t for g in sets for t in g['threats']]
    else:
        threats = body.get('threats', [])
        sets = None
        if not isinstance(threats, list):
            return None, None, 'No threats supplied'
    if not threats:
        return None, None, 'No threats supplied'
    if not all(isinstance(t, dict) for t in threats):
        return None, None, 'Each threat must be an object'
    if len(threats) > MAX_EXPORT_THREATS:
        return None, None, f'At most {MAX_EXPORT_THREATS} threats can go in one file'
    return threats, sets, None


def _file_title(file_name):
    """The Document's name: the file's, without its extension."""
    stem = re.sub(r'\.(kmz|kml)$', '', str(file_name), flags=re.IGNORECASE).strip()
    return stem or 'Threats'


def _kmz_name(body, extension='.kmz'):
    name = str(body.get('fileName') or 'threats.kmz')
    name = re.sub(r'\.(kmz|kml)$', '', name, flags=re.IGNORECASE) + extension
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
        threats, sets, problem = _export_threats(body)
        if problem:
            return jsonify({'error': problem}), 400

        data = build_threats_kmz(threats, sets, _file_title(_kmz_name(body)))
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
        threats, sets, problem = _export_threats(body)
        if problem:
            return jsonify({'error': problem}), 400
        # `format: "kml"` for the plain KML: ATAK and Google Earth take either, and some EFB apps only KML.
        if body.get('format') == 'kml':
            name = _kmz_name(body, '.kml')
            data = build_threats_kml(threats, sets, _file_title(name)).encode('utf-8')
            mimetype = 'application/vnd.google-earth.kml+xml'
        else:
            name = _kmz_name(body)
            data = build_threats_kmz(threats, sets, _file_title(name))
            mimetype = 'application/vnd.google-earth.kmz'
        response = send_file(io.BytesIO(data), mimetype=mimetype, as_attachment=True, download_name=name)
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
