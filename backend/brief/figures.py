"""A brief's figures: the base map's tiles for an area, stitched into one image, and the plan drawn over it as SVG.

A figure spec comes from the client's compileBrief (``step.figure``: kind, the ref in focus, bounds) and the brief's
``geometry`` (every route, LZ/PZ, threat and rally point in lat/lon, with its words already written), so this module
only projects and draws (docs/MISSION_BRIEF.md §2.6). Lines and labels stay vector (SVG), so they print sharp; the map
under them is a JPEG.

A tile that cannot be fetched leaves its square plain grey and the figure says so; a figure is never left out. Tiles are
kept in memory between figures and briefs (``_cache``), by source and address only: a tile says nothing about a plan.
"""

import io
import math
import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from xml.sax.saxutils import escape

import requests
from PIL import Image

from export_service import MAPBOX_ACCESS_TOKEN

# The base maps a figure can be drawn on, as the web's mapStyles.js draws them: an image's pixels per tile, the most
# detailed zoom the source has, and its attribution.
SOURCES = {
    'satellite': {
        'url': 'https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{z}/{x}/{y}@2x?access_token=' + MAPBOX_ACCESS_TOKEN,
        'tile_px': 1024, 'max_zoom': 19, 'attribution': '© Mapbox © OpenStreetMap © Maxar',
    },
    'topo': {
        'url': 'https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/{z}/{x}/{y}@2x?access_token=' + MAPBOX_ACCESS_TOKEN,
        'tile_px': 1024, 'max_zoom': 19, 'attribution': '© Mapbox © OpenStreetMap',
    },
    'sectional': {
        'url': 'https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/{z}/{y}/{x}',
        'tile_px': 256, 'max_zoom': 12, 'attribution': 'FAA VFR sectional chart',
    },
}

# A figure's image, in pixels: about 200 per inch across a letter page's printable width, and short enough to leave a
# step's facts, items and script room on the same page.
WIDTH_PX = 1500
HEIGHT_PX = 700
MIN_ZOOM = 3
TILE_TIMEOUT_S = 12
_CACHE_TILES = 600

_cache = OrderedDict()
_cache_lock = threading.Lock()
_session = requests.Session()


# -- Web Mercator ---------------------------------------------------------------------------------------------------

def world_px(lat, lon, zoom, tile_px):
    """A place as pixels of the whole world's image at a zoom, for tiles of ``tile_px`` (x east, y south)."""
    lat = max(-85.05112878, min(85.05112878, lat))
    size = tile_px * (2 ** zoom)
    x = (lon + 180.0) / 360.0 * size
    siny = math.sin(math.radians(lat))
    y = (0.5 - math.log((1 + siny) / (1 - siny)) / (4 * math.pi)) * size
    return x, y


def choose_zoom(bounds, width_px, height_px, tile_px, max_zoom):
    """The most detailed zoom at which the bounds ([[south, west], [north, east]]) fit the image."""
    (south, west), (north, east) = bounds
    for zoom in range(max_zoom, MIN_ZOOM - 1, -1):
        x0, y0 = world_px(north, west, zoom, tile_px)
        x1, y1 = world_px(south, east, zoom, tile_px)
        if abs(x1 - x0) <= width_px and abs(y1 - y0) <= height_px:
            return zoom
    return MIN_ZOOM


class Frame:
    """The image a figure is drawn on: the bounds' middle at the image's middle, at a zoom where they fit."""

    def __init__(self, bounds, source, width_px=WIDTH_PX, height_px=HEIGHT_PX):
        self.source = SOURCES.get(source) or SOURCES['satellite']
        self.tile_px = self.source['tile_px']
        self.width = width_px
        self.height = height_px
        self.zoom = choose_zoom(bounds, width_px, height_px, self.tile_px, self.source['max_zoom'])
        (south, west), (north, east) = bounds
        self.mid_lat = (south + north) / 2
        cx0, cy0 = world_px(north, west, self.zoom, self.tile_px)
        cx1, cy1 = world_px(south, east, self.zoom, self.tile_px)
        self.left = (cx0 + cx1) / 2 - width_px / 2
        self.top = (cy0 + cy1) / 2 - height_px / 2

    def project(self, lat, lon):
        x, y = world_px(lat, lon, self.zoom, self.tile_px)
        return x - self.left, y - self.top

    def metres_per_px(self):
        return 2 * math.pi * 6378137 * math.cos(math.radians(self.mid_lat)) / (self.tile_px * 2 ** self.zoom)


# -- Tiles ----------------------------------------------------------------------------------------------------------

def _fetch_tile(source_id, url, z, x, y):
    key = (source_id, z, x, y)
    with _cache_lock:
        if key in _cache:
            _cache.move_to_end(key)
            return _cache[key]
    try:
        response = _session.get(url.format(z=z, x=x, y=y), timeout=TILE_TIMEOUT_S)
        response.raise_for_status()
        image = Image.open(io.BytesIO(response.content)).convert('RGB')
    except Exception:  # noqa: BLE001 - a tile that will not come is a grey square, said on the figure
        return None
    with _cache_lock:
        _cache[key] = image
        while len(_cache) > _CACHE_TILES:
            _cache.popitem(last=False)
    return image


def base_image(frame, source_id):
    """The base map under the frame as RGB, and how many tiles could not be fetched."""
    source = frame.source
    tile_px = frame.tile_px
    count = 2 ** frame.zoom
    first_x, first_y = math.floor(frame.left / tile_px), math.floor(frame.top / tile_px)
    last_x, last_y = math.floor((frame.left + frame.width - 1) / tile_px), math.floor((frame.top + frame.height - 1) / tile_px)
    wanted = [(tx, ty) for ty in range(first_y, last_y + 1) for tx in range(first_x, last_x + 1) if 0 <= ty < count]
    with ThreadPoolExecutor(max_workers=8) as pool:
        images = list(pool.map(lambda t: _fetch_tile(source_id, source['url'], frame.zoom, t[0] % count, t[1]), wanted))
    canvas = Image.new('RGB', (frame.width, frame.height), (205, 202, 192))
    missing = 0
    for (tx, ty), image in zip(wanted, images):
        if image is None:
            missing += 1
            continue
        if image.size != (tile_px, tile_px):
            image = image.resize((tile_px, tile_px))
        canvas.paste(image, (round(tx * tile_px - frame.left), round(ty * tile_px - frame.top)))
    return canvas, missing


def jpeg(image):
    out = io.BytesIO()
    image.save(out, format='JPEG', quality=82, optimize=True)
    return out.getvalue()


# -- The plan over it -----------------------------------------------------------------------------------------------

ROUTE_COLOUR = '#5aa7d4'
FOCUS_COLOUR = '#ffd166'
ROLE_COLOURS = {'LZ': '#ffd166', 'PZ': '#6fe3a5', 'FARP': '#f0b429', 'HA': '#c9d0d8', 'LAAGER': '#c9d0d8', 'FALSE_LZ': '#e0a0ff'}


def _num(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _point(frame, lat, lon):
    if not (_num(lat) and _num(lon)):
        return None
    return frame.project(lat, lon)


def _label(x, y, text, size=22, colour='#ffffff', anchor='start', dx=12, dy=-10):
    text = escape(str(text))
    return (f'<text x="{x + dx:.1f}" y="{y + dy:.1f}" font-size="{size}" text-anchor="{anchor}" fill="{colour}" '
            f'stroke="#101317" stroke-width="5" paint-order="stroke" font-weight="700">{text}</text>')


def _polyline(frame, line, colour, width, dash=None, opacity=1.0):
    pts = [p for p in (_point(frame, lat, lon) for lat, lon in line) if p]
    if len(pts) < 2:
        return ''
    d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in pts)
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ''
    return (f'<polyline points="{d}" fill="none" stroke="#101317" stroke-width="{width + 4}" stroke-linejoin="round" '
            f'stroke-opacity="{0.6 * opacity:.2f}"/>'
            f'<polyline points="{d}" fill="none" stroke="{colour}" stroke-width="{width}" stroke-linejoin="round" '
            f'stroke-linecap="round" stroke-opacity="{opacity:.2f}"{dash_attr}/>')


def _arrow(x, y, heading, length, colour):
    """An arrow from (x, y) the way a heading points (true, map north up)."""
    rad = math.radians(heading)
    tx, ty = x + math.sin(rad) * length, y - math.cos(rad) * length
    left = math.radians(heading + 150)
    right = math.radians(heading - 150)
    head = 18
    return (f'<line x1="{x:.1f}" y1="{y:.1f}" x2="{tx:.1f}" y2="{ty:.1f}" stroke="{colour}" stroke-width="5" stroke-linecap="round"/>'
            f'<polygon points="{tx:.1f},{ty:.1f} {tx + math.sin(left) * head:.1f},{ty - math.cos(left) * head:.1f} '
            f'{tx + math.sin(right) * head:.1f},{ty - math.cos(right) * head:.1f}" fill="{colour}"/>')


def _nice_scale(metres):
    """The longest round distance (1, 2 or 5 times a power of ten) no longer than ``metres``."""
    if metres <= 0:
        return 0
    power = 10 ** math.floor(math.log10(metres))
    for step in (5, 2, 1):
        if step * power <= metres:
            return step * power
    return power


def overlay_svg(frame, spec, geometry, missing_tiles=0):
    """The plan over the frame as one SVG of the frame's size."""
    parts = []
    routes = geometry.get('routes') or {}
    stops = geometry.get('stops') or {}
    focus = spec.get('ref')
    kind = spec.get('kind')

    for ref in spec.get('routes') or list(routes):
        route = routes.get(ref)
        if not route:
            continue
        lit = kind in ('route', 'departure') and ref == focus
        colour = FOCUS_COLOUR if lit else (route.get('color') or ROUTE_COLOUR)
        parts.append(_polyline(frame, route.get('line') or [], colour, 7 if lit else 5, opacity=1.0 if lit or kind != 'route' else 0.55))
        for p in route.get('points') or []:
            at = _point(frame, p.get('lat'), p.get('lon'))
            if not at:
                continue
            parts.append(f'<circle cx="{at[0]:.1f}" cy="{at[1]:.1f}" r="7" fill="#101317" stroke="{colour}" stroke-width="3"/>')
            if lit or kind in ('overview', 'rally'):
                parts.append(_label(*at, p.get('name') or '', size=20))

    for ref in spec.get('stops') or list(stops):
        stop = stops.get(ref)
        if not stop:
            continue
        at = _point(frame, stop.get('lat'), stop.get('lon'))
        if not at:
            continue
        colour = ROLE_COLOURS.get(stop.get('role'), '#ffd166')
        close = kind == 'stop' and ref == focus
        boundary = [p for p in (_point(frame, lat, lon) for lat, lon in (stop.get('boundary') or [])) if p]
        if close and len(boundary) >= 3:
            d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in boundary)
            parts.append(f'<polygon points="{d}" fill="{colour}" fill-opacity="0.16" stroke="{colour}" stroke-width="4"/>')
        if close:
            for craft in stop.get('aircraft') or []:
                spot = _point(frame, craft.get('lat'), craft.get('lon'))
                if spot:
                    parts.append(f'<circle cx="{spot[0]:.1f}" cy="{spot[1]:.1f}" r="11" fill="#101317" stroke="#64f338" stroke-width="3"/>')
                    parts.append(_arrow(spot[0], spot[1], craft.get('heading') or 0, 26, '#64f338'))
            if _num(stop.get('landingHeading')):
                parts.append(_arrow(at[0] - math.sin(math.radians(stop['landingHeading'])) * 150,
                                    at[1] + math.cos(math.radians(stop['landingHeading'])) * 150,
                                    stop['landingHeading'], 120, '#ffffff'))
        x, y = at
        parts.append(f'<rect x="{x - 9:.1f}" y="{y - 9:.1f}" width="18" height="18" fill="#101317" stroke="{colour}" stroke-width="4"/>')
        parts.append(_label(x, y, stop.get('name') or '', size=26 if close else 22, colour='#ffffff'))

    for threat in geometry.get('threats') or []:
        at = _point(frame, threat.get('lat'), threat.get('lon'))
        if at:
            parts.append(f'<circle cx="{at[0]:.1f}" cy="{at[1]:.1f}" r="14" fill="#e06a62" fill-opacity="0.35" stroke="#e06a62" stroke-width="4"/>')
            parts.append(_label(*at, threat.get('name') or 'Threat', size=20, colour='#ffb3ad'))

    if kind == 'rally':
        for rally in geometry.get('rally') or []:
            at = _point(frame, rally.get('lat'), rally.get('lon'))
            if at:
                x, y = at
                parts.append(f'<polygon points="{x:.1f},{y - 16:.1f} {x + 14:.1f},{y + 10:.1f} {x - 14:.1f},{y + 10:.1f}" '
                             f'fill="#101317" stroke="#ffd166" stroke-width="4"/>')
                parts.append(_label(x, y, f"{rally.get('name')} ({rally.get('label')})", size=22, colour='#ffd166'))

    # North arrow (the map is north up), a scale bar and the attribution.
    parts.append('<g transform="translate(60 70)"><polygon points="0,-34 14,10 0,2 -14,10" fill="#ffffff" stroke="#101317" '
                 'stroke-width="3"/><text x="0" y="40" font-size="22" text-anchor="middle" fill="#ffffff" stroke="#101317" '
                 'stroke-width="4" paint-order="stroke" font-weight="800">N</text></g>')
    metres = _nice_scale(frame.metres_per_px() * frame.width * 0.22)
    if metres:
        bar = metres / frame.metres_per_px()
        words = f'{metres / 1000:g} km' if metres >= 1000 else f'{metres:g} m'
        y = frame.height - 40
        parts.append(f'<rect x="40" y="{y}" width="{bar:.1f}" height="10" fill="#ffffff" stroke="#101317" stroke-width="2"/>')
        parts.append(f'<text x="40" y="{y - 10}" font-size="20" fill="#ffffff" stroke="#101317" stroke-width="4" '
                     f'paint-order="stroke" font-weight="700">{escape(words)}</text>')
    note = frame.source['attribution']
    if missing_tiles:
        note = f'{missing_tiles} map tile{"s" if missing_tiles != 1 else ""} could not be fetched · {note}'
    parts.append(f'<text x="{frame.width - 16}" y="{frame.height - 14}" font-size="16" text-anchor="end" fill="#ffffff" '
                 f'stroke="#101317" stroke-width="4" paint-order="stroke">{escape(note)}</text>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {frame.width} {frame.height}" class="fig-overlay">{"".join(parts)}</svg>'


def bounds_around(points, pad_m=300):
    """[[south, west], [north, east]] round lat/lon pairs, at least ``pad_m`` past them each way; None for none."""
    valid = [(lat, lon) for lat, lon in points if _num(lat) and _num(lon)]
    if not valid:
        return None
    lats = [p[0] for p in valid]
    lons = [p[1] for p in valid]
    d_lat = pad_m / 111320
    d_lon = d_lat / max(0.01, math.cos(math.radians(sum(lats) / len(lats))))
    return [[min(lats) - d_lat, min(lons) - d_lon], [max(lats) + d_lat, max(lons) + d_lon]]


def valid_bounds(bounds):
    try:
        (south, west), (north, east) = bounds
        return all(_num(v) for v in (south, west, north, east)) and -90 <= south < north <= 90 and west < east
    except (TypeError, ValueError):
        return False


def draw(spec, geometry, source_id):
    """A figure: its base map as JPEG bytes and its overlay as SVG, or None when the spec names no area."""
    bounds = spec.get('bounds')
    if not valid_bounds(bounds):
        return None
    frame = Frame(bounds, source_id)
    image, missing = base_image(frame, source_id)
    return {'jpeg': jpeg(image), 'svg': overlay_svg(frame, spec, geometry, missing), 'zoom': frame.zoom}
