"""Terrain masking: which ground a threat radar can see, and how it is drawn.

Elevation data is the open AWS "Terrarium" terrain-RGB tile set (no API key),
decoded to metres.
"""

import base64
import math

import cv2
import numpy as np

from app.services.terrain.provider import load_terrarium_radius

FT_TO_M = 0.3048
NMI_TO_M = 1852.0
EARTH_RADIUS_M = 6371000.0
REFRACTION_K = 0.13  # standard atmospheric refraction coefficient


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
