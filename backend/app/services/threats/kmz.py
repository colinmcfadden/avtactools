"""The KMZ overlay (ForeFlight / ATAK / Aero App): marker, range rings and the
terrain mask as filled vector polygons."""

import io
import math
import zipfile

import cv2
import numpy as np

from app.services.threats.viewshed import (
    FT_TO_M,
    NMI_TO_M,
    fetch_dem,
    radar_elev_m,
    viewshed,
)


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
    contours, _ = cv2.findContours(vis.astype(np.uint8), cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_SIMPLE)
    rings = []
    for c in contours:
        if cv2.contourArea(c) < min_area_px:
            continue
        approx = cv2.approxPolyDP(c, epsilon_px, True)
        if len(approx) < 3:
            continue
        pts = [_pixel_to_lonlat(float(p[0][1]), float(p[0][0]), meta, H, W) for p in approx]
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


def build_threats_kmz(threats):
    """
    Builds a KMZ overlay from the threats: a marker, range rings, and the
    terrain mask as filled vector polygons (per radar / altitude band). Uses the
    same viewshed as the on-screen mask. Returns KMZ bytes.
    """
    folders = []
    for i, threat in enumerate(threats, start=1):
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

        folders.append(f'<Folder><name>{name}</name>{"".join(placemarks)}</Folder>')

    kml = ('<?xml version="1.0" encoding="UTF-8"?>'
           '<kml xmlns="http://www.opengis.net/kml/2.2"><Document>'
           '<name>Threats</name>' + ''.join(folders) + '</Document></kml>')

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('doc.kml', kml)
    return buf.getvalue()
