"""
Threat terrain-masking and .ths export.

Two endpoints:
  POST /api/threat-mask   radial line-of-sight viewshed over an open DEM, returned
                          as translucent PNG overlays (per radar / altitude band).
  POST /api/threats-ths   builds an AMPS .ths (SQLite) threat overlay file from the
                          in-memory threats, using the bundled cleaned template so the
                          exact AMPS schema is preserved.

Elevation data is the open AWS "Terrarium" terrain-RGB tile set (no API key), decoded
to metres. Nothing is persisted — threats are export-only per the product decision.
"""

import os
import io
import math
import base64
import sqlite3
import shutil
import tempfile
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import cv2
import requests
import mercantile
from flask import Blueprint, request, jsonify, send_file

threat_bp = Blueprint('threat', __name__)

TERRARIUM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
FT_TO_M = 0.3048
NMI_TO_M = 1852.0
EARTH_RADIUS_M = 6371000.0
REFRACTION_K = 0.13  # standard atmospheric refraction coefficient
TEMPLATE_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'threat_template.ths')


# --- DEM mosaic -----------------------------------------------------------

def _pick_zoom(lat, radius_m, target_radius_px=800):
    """Highest terrarium zoom that keeps the viewshed radius within ~target px."""
    span = 156543.03 * math.cos(math.radians(lat))
    z = math.floor(math.log2(target_radius_px * span / radius_m)) if radius_m > 0 else 12
    return int(max(9, min(13, z)))


def _global_px(lat, lon, z):
    """Web-mercator global pixel coordinate (256 px tiles) for a lat/lon."""
    n = 2 ** z
    x = (lon + 180.0) / 360.0 * n * 256.0
    sin_lat = math.sin(math.radians(lat))
    y = (0.5 - math.log((1 + sin_lat) / (1 - sin_lat)) / (4 * math.pi)) * n * 256.0
    return x, y


def _decode_terrarium(png_bytes):
    """Terrarium RGB -> metres. elevation = R*256 + G + B/256 - 32768."""
    arr = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
    if arr is None:
        return None
    b = arr[:, :, 0].astype(np.float32)
    g = arr[:, :, 1].astype(np.float32)
    r = arr[:, :, 2].astype(np.float32)
    return (r * 256.0 + g + b / 256.0) - 32768.0


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
    """
    Mosaics terrarium tiles covering a radius_m box around (lat, lon).
    Returns (dem_metres 2D array, meta) where meta carries the geographic bounds,
    the radar's pixel position, and metres-per-pixel.
    """
    z = _pick_zoom(lat, radius_m)
    dlat = radius_m / 111320.0
    dlon = radius_m / (111320.0 * math.cos(math.radians(lat)))
    west, east = lon - dlon, lon + dlon
    south, north = lat - dlat, lat + dlat

    tiles = list(mercantile.tiles(west, south, east, north, z))
    if not tiles:
        return None, None
    xs = [t.x for t in tiles]
    ys = [t.y for t in tiles]
    min_x, max_x, min_y, max_y = min(xs), max(xs), min(ys), max(ys)
    cols = (max_x - min_x + 1) * 256
    rows = (max_y - min_y + 1) * 256
    dem = np.zeros((rows, cols), np.float32)

    def grab(t):
        try:
            resp = requests.get(TERRARIUM_URL.format(z=z, x=t.x, y=t.y), headers=HEADERS, timeout=12)
            if resp.status_code != 200:
                return t, None
            return t, _decode_terrarium(resp.content)
        except requests.exceptions.RequestException:
            return t, None

    with ThreadPoolExecutor(max_workers=8) as pool:
        for t, tile_dem in pool.map(grab, tiles):
            if tile_dem is None:
                continue
            r0 = (t.y - min_y) * 256
            c0 = (t.x - min_x) * 256
            dem[r0:r0 + 256, c0:c0 + 256] = tile_dem

    dem = _prep_dem(dem)

    origin_x = min_x * 256
    origin_y = min_y * 256
    gx, gy = _global_px(lat, lon, z)
    radar_col = gx - origin_x
    radar_row = gy - origin_y
    mpp = 156543.03 * math.cos(math.radians(lat)) / (2 ** z)

    # Geographic bounds of the mosaic, from its corner tiles.
    nw = mercantile.bounds(mercantile.Tile(min_x, min_y, z))
    se = mercantile.bounds(mercantile.Tile(max_x, max_y, z))
    meta = {
        'z': z, 'mpp': mpp,
        'radar_row': radar_row, 'radar_col': radar_col,
        'north': nw.north, 'west': nw.west, 'south': se.south, 'east': se.east,
    }
    return dem, meta


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

        # Observer ground = max over the radar's immediate 3x3 footprint. A real
        # radar site has clear line-of-sight over its own coarse DEM cell, so it
        # shouldn't be shadowed by a single adjacent pixel of its own hilltop.
        rr = int(round(meta['radar_row']))
        cc = int(round(meta['radar_col']))
        r0, r1 = max(0, rr - 1), min(dem.shape[0], rr + 2)
        c0, c1 = max(0, cc - 1), min(dem.shape[1], cc + 2)
        ground_at_radar = float(dem[r0:r1, c0:c1].max())

        out_radars = []
        for radar in radars:
            if not radar.get('showMask', True):
                continue
            bands = [b for b in radar.get('bands', []) if b.get('viewable', True)]
            if not bands:
                continue
            ant_m = float(radar.get('antennaHeightFt', 0)) * FT_TO_M
            radar_elev = (ground_at_radar + ant_m) if radar.get('aglNotMsl') else ant_m
            range_px = int(round(float(radar['rangeNmi']) * NMI_TO_M / meta['mpp']))
            range_px = min(range_px, max(dem.shape) )
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


def _amps_dtg(dt=None):
    """AMPS .ths DATE_TIME format observed as DDHHMMSSMMYYYY."""
    dt = dt or datetime.utcnow()
    return dt.strftime('%d%H%M%S%m%Y')


def build_ths_bytes(threats):
    """Builds a .ths (SQLite) from the cleaned template and the given threats."""
    tmp = tempfile.NamedTemporaryFile(suffix='.ths', delete=False)
    tmp.close()
    try:
        shutil.copy(TEMPLATE_PATH, tmp.name)
        con = sqlite3.connect(tmp.name)
        cur = con.cursor()
        for t in ('THREATS', 'THREATRADAR', 'SYSTEM', 'LINKS'):
            cur.execute(f'DELETE FROM {t}')

        for i, threat in enumerate(threats, start=1):
            lat = float(threat['lat']); lon = float(threat['lon'])
            name = (threat.get('name') or f'Threat {i}')[:50]
            cur.execute(
                """INSERT INTO THREATS
                (ID,CORRELATION_CODE,MILSTD_ID,LATITUDE_DEG,LONGITUDE_DEG,DATE_TIME,OFFICIAL_NAME,
                 APPROVED_NICKNAME,ELLIPSE_ANGLE_DEG,ELLIPSE_SMAJ_NMI,ELLIPSE_SMIN_NMI,INFORMATION,
                 SHOW_THREAT,SHOW_ELLIPSES,ENABLE_EDIT,SOURCE,OB_TYPE,LABEL_TEXT_LEFT,LABEL_TEXT_RIGHT,geom)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (i, int(threat.get('correlationCode', 2100000000 + i)),
                 (threat.get('milstdId') or 'SHGPEWMAI------')[:15], lat, lon, _amps_dtg(),
                 name, (threat.get('nickname') or '')[:50], 0.0, 0.0, 0.0,
                 (threat.get('information') or '')[:255],
                 1 if threat.get('showThreat', True) else 0,
                 1 if threat.get('showEllipses', False) else 0,
                 1 if threat.get('enableEdit', True) else 0,
                 (threat.get('source') or 'SOF')[:32], int(threat.get('obType', 0)), '', '', None))

            for radar in threat.get('radars', []):
                bands = radar.get('bands', [])
                elevs = [int(b.get('altFt', 0)) for b in bands] + [0, 0, 0]
                colors = [int(b.get('colorIndex', d)) for b, d in zip(bands, (1, 3, 5))] + [1, 3, 5]
                views = [1 if b.get('viewable', True) else 0 for b in bands] + [1, 1, 1]
                cur.execute(
                    """INSERT INTO THREATRADAR
                    (ID,RADAR_TYPE,RADAR_LATITUDE_DEG,RADAR_LONGITUDE_DEG,SHOW_MASK,SHOW_RANGE_RINGS,
                     RANGE_NMI,RANGE_LIMITED,CUSTOM_RANGE_NMI,RADAR_ELEVATION,ANTENNAE_HEIGHT_FT,AGL_NOT_MSL,
                     ELEVATION1,ELEVATION2,ELEVATION3,DRAW_STYLE,BRUSH_STYLE,COLOR1,COLOR2,COLOR3,
                     MASK1_VIEWABLE,MASK2_VIEWABLE,MASK3_VIEWABLE,geom)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (i, int(radar.get('type', 0)), 0.0, 0.0,
                     1 if radar.get('showMask', True) else 0,
                     1 if radar.get('showRangeRings', True) else 0,
                     float(radar.get('rangeNmi', 0)), 0, 0.0, 0,
                     float(radar.get('antennaHeightFt', 0)),
                     1 if radar.get('aglNotMsl', False) else 0,
                     elevs[0], elevs[1], elevs[2], 2, 0,
                     colors[0], colors[1], colors[2], views[0], views[1], views[2], None))

            cur.execute(
                """INSERT INTO SYSTEM (SYSTEM_CODE,SYSTEM_GROUP,SYSTEM_NAME,EQUIPMENT_FKEY,
                   USE_ENGAGEMENT,USE_DETECTION) VALUES (?,?,?,?,?,?)""",
                (i, int(threat.get('systemGroup', 2)), name, i,
                 1 if any(r.get('type') == 1 for r in threat.get('radars', [])) else 0,
                 1 if any(r.get('type') == 0 for r in threat.get('radars', [])) else 0))

        con.commit()
        con.close()
        with open(tmp.name, 'rb') as f:
            return f.read()
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass


@threat_bp.route('/api/threats-ths', methods=['POST'])
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
