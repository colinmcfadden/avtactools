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
import re
import threading
from collections import OrderedDict
from types import SimpleNamespace
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
    """A tile as RGB, or None. The cache keeps the tile as it came (a few hundred KB), not decoded (3 MB at 1024 px), so a
    brief of strip charts cannot leave gigabytes of tiles beside the analysis model."""
    key = (source_id, z, x, y)
    with _cache_lock:
        data = _cache.get(key)
        if data is not None:
            _cache.move_to_end(key)
    if data is None:
        try:
            response = _session.get(url.format(z=z, x=x, y=y), timeout=TILE_TIMEOUT_S)
            response.raise_for_status()
            data = response.content
            Image.open(io.BytesIO(data)).verify()
        except Exception:  # noqa: BLE001 - a tile that will not come is a grey square, said on the figure
            return None
        with _cache_lock:
            _cache[key] = data
            while len(_cache) > _CACHE_TILES:
                _cache.popitem(last=False)
    try:
        return Image.open(io.BytesIO(data)).convert('RGB')
    except Exception:  # noqa: BLE001
        return None


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


_HEX_COLOUR = re.compile(r'^#[0-9A-Fa-f]{6}$')


def _colour(value, default):
    """A colour from the model only as #RRGGBB: anything else (a pack member typed it) is the default, never markup."""
    return value if isinstance(value, str) and _HEX_COLOUR.match(value) else default


def _point(frame, lat, lon):
    if not (_num(lat) and _num(lon)):
        return None
    return frame.project(lat, lon)


def _label(x, y, text, size=22, colour='#ffffff', anchor='start', dx=12, dy=-10):
    text = escape(str(text))
    return (f'<text x="{x + dx:.1f}" y="{y + dy:.1f}" font-size="{size:.3g}" text-anchor="{anchor}" fill="{colour}" '
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
        colour = FOCUS_COLOUR if lit else _colour(route.get('color'), ROUTE_COLOUR)
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


# -- Strip charts ---------------------------------------------------------------------------------------------------
#
# A strip (compileBrief's ``strips``) is a rectangle of ground along a route, ``length`` metres up its ``course`` (true)
# and ``width`` across, round ``centre``, drawn at ``scale`` with the direction of flight up. The base map is fetched
# north up round the turned rectangle, brought to the strip's own metres per pixel, turned and cut out in one affine
# transform; the plan is drawn over it in the strip's own pixels, so its words stay upright. The 1 km MGRS grid comes in
# the strip (``grid``, worked out by the client) and is only projected and drawn here.

STRIP_PX_PER_CM = 200 / 2.54     # the same 200 px to the printed inch as the other figures
PT = 200 / 72                    # pixels to a printed point
# Printed sizes, in points, of a strip's words: read on a kneeboard, so none under 6.
GRID_PT, LEG_PT, POINT_PT, PLACE_PT, NOTE_PT = 6, 7, 8, 7, 5
STRIP_MAX_MOSAIC_PX = 7000       # a mosaic larger than this is fetched a zoom coarser
STRIP_CM = (5.5, 21.5)          # a strip as printed, width by length: compileBrief's STRIP_CM
STRIP_MAX_PATH = 4000
STRIP_MAX_ITEMS = 400
STRIP_MAX_LINES = 2000
STRIP_MAX_LINE_POINTS = 600
STRIP_MAX_GRID_POINTS = 40000
GRID_COLOURS = {'satellite': ('#ffffff', 0.62), 'topo': ('#1d3a6b', 0.55), 'sectional': ('#1d3a6b', 0.6)}


def _pair(value):
    return (value[0], value[1]) if isinstance(value, (list, tuple)) and len(value) == 2 and _num(value[0]) and _num(value[1]) else None


def _small_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and 0 < value <= 1000


def _str_or_none(value):
    return value is None or isinstance(value, str)


def valid_strip(strip):
    """Whether a strip is one this module can draw: its numbers finite and in range, its size the printed strip's at its
    scale (the image's pixels are the printed strip's, never the model's to choose), its lists and words of a size and type
    it will draw. A strip that is not is left out of the brief, never the brief failed for it."""
    if not isinstance(strip, dict):
        return False
    centre = _pair(strip.get('centre'))
    if not centre or not -85 < centre[0] < 85 or not -540 < centre[1] < 540:
        return False
    if not (_num(strip.get('course')) and 0 <= strip['course'] <= 360):
        return False
    if not (_num(strip.get('scale')) and 1000 <= strip['scale'] <= 10000000):
        return False
    for key, cm in (('width', STRIP_CM[0]), ('length', STRIP_CM[1])):
        ground = strip.get(key)
        if not _num(ground) or abs(ground / strip['scale'] * 100 - cm) > cm * 0.01:
            return False
    if not (_small_int(strip.get('part')) and _small_int(strip.get('parts'))):
        return False
    if not all(_str_or_none(strip.get(k)) for k in ('name', 'courseText')):
        return False
    path = strip.get('path')
    if not isinstance(path, list) or not 2 <= len(path) <= STRIP_MAX_PATH or not all(_pair(p) for p in path):
        return False
    for key in ('points', 'legs', 'stops', 'threats'):
        items = strip.get(key, [])
        if not isinstance(items, list) or len(items) > STRIP_MAX_ITEMS or not all(isinstance(i, dict) for i in items):
            return False
    if not all(_str_or_none(s.get('role')) for s in strip.get('stops', [])):
        return False
    grid = strip.get('grid')
    if grid is not None:
        lines = grid.get('lines') if isinstance(grid, dict) else None
        if not isinstance(lines, list) or len(lines) > STRIP_MAX_LINES:
            return False
        if not all(isinstance(l, dict) and isinstance(l.get('points'), list) and len(l['points']) <= STRIP_MAX_LINE_POINTS
                   and _str_or_none(l.get('kind')) and _str_or_none(l.get('axis')) and _str_or_none(l.get('text')) for l in lines):
            return False
        if sum(len(l['points']) for l in lines) > STRIP_MAX_GRID_POINTS:
            return False
        for key in ('squares', 'others'):
            if not isinstance(grid.get(key, []), list) or not all(isinstance(q, str) for q in grid.get(key, [])):
                return False
    return True


class StripFrame:
    """A strip's own pixels: (0, 0) its top left, the course up, ``metres_per_px`` the same everywhere along it."""

    def __init__(self, strip, source_id):
        self.source = SOURCES.get(source_id) or SOURCES['topo']
        self.tile_px = self.source['tile_px']
        self.width = round(STRIP_CM[0] * STRIP_PX_PER_CM)
        self.height = round(STRIP_CM[1] * STRIP_PX_PER_CM)
        self.metres_per_px = strip['length'] / self.height
        self.course = strip['course'] % 360
        theta = math.radians(self.course)
        self.cos, self.sin = math.cos(theta), math.sin(theta)
        lat, lon = strip['centre']
        self.mid_lat = lat
        ground = 2 * math.pi * 6378137 * math.cos(math.radians(lat))
        # The coarsest zoom still as sharp as the strip, so the mosaic is only ever brought down to it.
        zoom = math.ceil(math.log2(ground / (self.tile_px * self.metres_per_px)))
        self.zoom = max(MIN_ZOOM, min(self.source['max_zoom'], zoom))
        while True:
            self.scale = self.metres_per_px / (ground / (self.tile_px * 2 ** self.zoom))   # world px per strip px
            half_w = self.scale * (self.width / 2 * abs(self.cos) + self.height / 2 * abs(self.sin)) + 2
            half_h = self.scale * (self.width / 2 * abs(self.sin) + self.height / 2 * abs(self.cos)) + 2
            if max(half_w, half_h) * 2 <= STRIP_MAX_MOSAIC_PX or self.zoom <= MIN_ZOOM:
                break
            self.zoom -= 1
        self.cx, self.cy = world_px(lat, lon, self.zoom, self.tile_px)
        self.mosaic = SimpleNamespace(source=self.source, tile_px=self.tile_px, zoom=self.zoom,
                                      left=self.cx - half_w, top=self.cy - half_h,
                                      width=math.ceil(2 * half_w), height=math.ceil(2 * half_h))

    def project(self, lat, lon):
        """A place in the strip's pixels."""
        x, y = world_px(lat, lon, self.zoom, self.tile_px)
        dx, dy = x - self.cx, y - self.cy
        sx = (dx * self.cos + dy * self.sin) / self.scale
        sy = (-dx * self.sin + dy * self.cos) / self.scale
        return self.width / 2 + sx, self.height / 2 + sy


def strip_image(frame, source_id):
    """The base map under a strip, turned so its course is up, as RGB of the strip's size; and the tiles not fetched."""
    mosaic, missing = base_image(frame.mosaic, source_id)
    cx, cy = frame.cx - frame.mosaic.left, frame.cy - frame.mosaic.top
    s = frame.scale
    if s > 1.05:
        # Brought down to the strip's scale first, so the turn does not alias the map's fine lines and words.
        size = (max(1, round(mosaic.width / s)), max(1, round(mosaic.height / s)))
        mosaic = mosaic.resize(size, Image.Resampling.LANCZOS)
        cx, cy, s = cx / frame.scale, cy / frame.scale, 1.0
    w, h, c, n = frame.width, frame.height, frame.cos, frame.sin
    data = (s * c, -s * n, cx - s * c * w / 2 + s * n * h / 2,
            s * n, s * c, cy - s * n * w / 2 - s * c * h / 2)
    image = mosaic.transform((w, h), Image.Transform.AFFINE, data, resample=Image.Resampling.BICUBIC, fillcolor=(205, 202, 192))
    return image, missing


def _crossings(points, edge, width, height):
    """Where a polyline crosses one edge of the strip: y for the long edges, x for the top and bottom."""
    at = {'left': ('x', 0.0), 'right': ('x', float(width)), 'top': ('y', 0.0), 'bottom': ('y', float(height))}[edge]
    out = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if at[0] == 'x' and (x0 - at[1]) * (x1 - at[1]) < 0:
            out.append(y0 + (y1 - y0) * (at[1] - x0) / (x1 - x0))
        elif at[0] == 'y' and (y0 - at[1]) * (y1 - at[1]) < 0:
            out.append(x0 + (x1 - x0) * (at[1] - y0) / (y1 - y0))
    return out


# The north arrow's middle, in from the strip's top right corner so the right edge stays free for the grid's digits.
STRIP_NORTH_AT = (-66, 70)
LABEL_GAP = 2                    # px between two labels' boxes, their dark halos included
# How far a label may slide along its edge from where its line meets it, when that spot is taken: lines of one kind meet
# an edge at least 1 km apart (31 px at 1:250,000), so a label slid this far is still nearest its own line.
LABEL_SLIDES = (0, -6, 6, -12, 12)


def _label_box(x, baseline, words, size, anchor):
    """The box a bold label of digits takes, its 2.5 px halo included: each figure is about 0.6 of the type's size."""
    w = len(words) * size * 0.6 + 5
    x0 = {'start': x - 2.5, 'end': x - w + 2.5}.get(anchor, x - w / 2)
    return (x0, baseline - size * 0.75 - 2.5, x0 + w, baseline + 2.5)


def _fits(box, placed, width, height):
    x0, y0, x1, y1 = box
    if x0 < 1 or y0 < 1 or x1 > width - 1 or y1 > height - 1:
        return False
    g = LABEL_GAP
    return not any(x0 < b[2] + g and b[0] < x1 + g and y0 < b[3] + g and b[1] < y1 + g for b in placed)


def _edge_labels(points, text, axis, width, height, placed, colour, own_only):
    """A grid line's digits, once, just inside the strip where the line meets its edge: an easting's on the left edge and
    a northing's on the right (a turned strip crosses both kinds on both edges, so the side says which); a line that never
    reaches its own side, or finds no room there, is numbered where it does cross, at the top, the bottom or the other
    side, with E or N before the digits. A label goes only where its box is clear of every other (``placed``: the labels
    so far, the north arrow and the attribution), slid a little along the edge if it must (``LABEL_SLIDES``); at the top
    and the bottom in either of two rows, since lines a kilometre apart at 1:250,000 meet those edges closer together
    than a label is wide. Every line is given its own side first (``own_only``), and only then the others what is left."""
    own, other = ('left', 'right') if axis == 'easting' else ('right', 'left')
    mark = 'E' if axis == 'easting' else 'N'
    size = GRID_PT * PT
    for edge in (own,) if own_only else ('top', 'bottom', other):
        words = text if edge == own else mark + text
        for where in _crossings(points, edge, width, height):
            if edge in ('left', 'right'):
                x, anchor = (4, 'start') if edge == 'left' else (width - 4, 'end')
                spots = [(x, where + slide + size * 0.36, anchor) for slide in LABEL_SLIDES]
            else:
                spots = [(where + slide, (size * 0.75 + 4 + 20 * row) if edge == 'top' else (height - 6 - 20 * row), 'middle')
                         for slide in LABEL_SLIDES for row in (0, 1)]
            for x, baseline, anchor in spots:
                box = _label_box(x, baseline, words, size, anchor)
                if _fits(box, placed, width, height):
                    placed.append(box)
                    return [_label(x, baseline, words, size=size, colour=colour, anchor=anchor, dx=0, dy=0)]
    return []


def _inner_label(points, text, axis, width, height, placed, colour):
    """A line no edge had room to number is numbered on itself, a little way in from where it enters the strip at either
    end (as a topographic sheet prints values inside its neatline), with E or N before the digits, where it is clear."""
    size = GRID_PT * PT
    words = ('E' if axis == 'easting' else 'N') + text
    inside = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        steps = max(1, int(math.hypot(x1 - x0, y1 - y0) / 8))
        for k in range(steps):
            x, y = x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps
            if 8 <= x <= width - 8 and 8 <= y <= height - 8:
                inside.append((x, y))
    for i in range(min(30, (len(inside) + 1) // 2)):
        for x, y in (inside[i], inside[-1 - i]):
            box = _label_box(x, y + size * 0.36, words, size, 'middle')
            if _fits(box, placed, width, height):
                placed.append(box)
                return [_label(x, y + size * 0.36, words, size=size, colour=colour, anchor='middle', dx=0, dy=0)]
    return []


def strip_overlay_svg(frame, strip, source_id, missing_tiles=0):
    """The plan over a strip, in the strip's pixels: the grid and its digits, the route, its checkpoints, a note on each
    leg, the LZ/PZs and threats on it, a north arrow turned to true north, a scale bar and the attribution."""
    parts = []
    width, height = frame.width, frame.height
    grid_colour, grid_opacity = GRID_COLOURS.get(source_id, GRID_COLOURS['topo'])
    label_colour = '#ffffff'
    grid = strip.get('grid') if isinstance(strip.get('grid'), dict) else {}
    nx, ny = width + STRIP_NORTH_AT[0], STRIP_NORTH_AT[1]
    note = frame.source['attribution']
    if missing_tiles:
        note = f'{missing_tiles} map tile{"s" if missing_tiles != 1 else ""} not fetched · {note}'
    note_size = NOTE_PT * PT
    # What the grid's digits keep clear of: the north arrow and its N, and the attribution along the bottom right.
    placed = [(nx - 26, ny - 26, nx + 26, ny + 24 + GRID_PT * PT + 3),
              (width - 8 - len(note) * note_size * 0.55, height - 5 - note_size * 0.75, width - 4, height - 2)]
    numbered = []
    for line in grid.get('lines') or []:
        pts = [frame.project(*p) for p in (_pair(q) for q in line.get('points') or []) if p]
        if len(pts) < 2:
            continue
        kind = line.get('kind')
        weight = {'zone': 2.4, 'major': 1.8}.get(kind, 0.9)
        dash = ' stroke-dasharray="10 6"' if kind == 'zone' else ''
        d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in pts)
        parts.append(f'<polyline points="{d}" fill="none" stroke="{grid_colour}" stroke-width="{weight}" '
                     f'stroke-opacity="{grid_opacity}"{dash}/>')
        text = line.get('text')
        if isinstance(text, str) and kind != 'zone':
            numbered.append((pts, text[:4], line.get('axis')))
    left_over = []
    for pts, text, axis in numbered:
        label = _edge_labels(pts, text, axis, width, height, placed, label_colour, own_only=True)
        parts.extend(label) if label else left_over.append((pts, text, axis))
    bare = []
    for pts, text, axis in left_over:
        label = _edge_labels(pts, text, axis, width, height, placed, label_colour, own_only=False)
        parts.extend(label) if label else bare.append((pts, text, axis))
    for pts, text, axis in bare:
        parts.extend(_inner_label(pts, text, axis, width, height, placed, label_colour))

    path = [p for p in (_pair(q) for q in strip.get('path') or []) if p]
    pts = [frame.project(lat, lon) for lat, lon in path]
    if len(pts) >= 2:
        d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in pts)
        parts.append(f'<polyline points="{d}" fill="none" stroke="#101317" stroke-width="9" stroke-linejoin="round" stroke-opacity="0.7"/>'
                     f'<polyline points="{d}" fill="none" stroke="{FOCUS_COLOUR}" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"/>')

    for stop in strip.get('stops') or []:
        at = _point(frame, stop.get('lat'), stop.get('lon')) if isinstance(stop, dict) else None
        if at:
            colour = ROLE_COLOURS.get(stop.get('role'), '#ffd166')
            parts.append(f'<rect x="{at[0] - 8:.1f}" y="{at[1] - 8:.1f}" width="16" height="16" fill="#101317" stroke="{colour}" stroke-width="3.5"/>')
            parts.append(_label(*at, _text_of(stop.get('name')), size=PLACE_PT * PT, anchor='end' if at[0] > width / 2 else 'start',
                                dx=-13 if at[0] > width / 2 else 13, dy=-9))
    for threat in strip.get('threats') or []:
        at = _point(frame, threat.get('lat'), threat.get('lon')) if isinstance(threat, dict) else None
        if at:
            parts.append(f'<circle cx="{at[0]:.1f}" cy="{at[1]:.1f}" r="11" fill="#e06a62" fill-opacity="0.35" stroke="#e06a62" stroke-width="3"/>')
            parts.append(_label(*at, _text_of(threat.get('name')) or 'Threat', size=PLACE_PT * PT, colour='#ffb3ad'))

    for point in strip.get('points') or []:
        at = _point(frame, point.get('lat'), point.get('lon')) if isinstance(point, dict) else None
        if at:
            left = at[0] > width / 2
            parts.append(f'<circle cx="{at[0]:.1f}" cy="{at[1]:.1f}" r="7" fill="#101317" stroke="{FOCUS_COLOUR}" stroke-width="3"/>')
            parts.append(_label(*at, _text_of(point.get('name')), size=POINT_PT * PT, anchor='end' if left else 'start',
                                dx=-12 if left else 12, dy=7))

    for leg in strip.get('legs') or []:
        at = _pair(leg.get('at')) if isinstance(leg, dict) else None
        if not at:
            continue
        x, y = frame.project(*at)
        right = x <= width / 2
        anchor, dx = ('start', 14) if right else ('end', -14)
        lines = [' '.join(t for t in (_text_of(leg.get('course')), _text_of(leg.get('distance'))) if t),
                 _text_of(leg.get('time')), _text_of(leg.get('altitude'))]
        for i, words in enumerate(w for w in lines if w):
            parts.append(_label(x, y, words, size=LEG_PT * PT, colour='#ffd166', anchor=anchor, dx=dx, dy=-12 + i * (LEG_PT * PT + 2)))

    # North, turned to true north on the strip; a scale only where there is no grid (the 1 km squares are the scale, and
    # a bar in a corner would leave the lines that cross it unnumbered); and whose map it is.
    parts.append(f'<g transform="translate({nx} {ny}) rotate({-frame.course:.2f})"><polygon points="0,-24 11,9 0,3 -11,9" '
                 f'fill="#ffffff" stroke="#101317" stroke-width="2.5"/></g>'
                 f'<text x="{nx}" y="{ny + 24 + GRID_PT * PT:.0f}" font-size="{GRID_PT * PT:.1f}" text-anchor="middle" fill="#ffffff" stroke="#101317" '
                 f'stroke-width="4" paint-order="stroke" font-weight="800">N</text>')
    metres = None if numbered else _nice_scale(frame.metres_per_px * width * 0.45)
    if metres:
        bar = metres / frame.metres_per_px
        words = f'{metres / 1000:g} km' if metres >= 1000 else f'{metres:g} m'
        y = height - 38
        parts.append(f'<rect x="12" y="{y}" width="{bar:.1f}" height="8" fill="#ffffff" stroke="#101317" stroke-width="1.5"/>')
        parts.append(f'<text x="12" y="{y - 7}" font-size="{GRID_PT * PT:.1f}" fill="#ffffff" stroke="#101317" stroke-width="4" '
                     f'paint-order="stroke" font-weight="700">{escape(words)}</text>')
    parts.append(f'<text x="{width - 6}" y="{height - 5}" font-size="{note_size:.1f}" text-anchor="end" fill="#ffffff" '
                 f'stroke="#101317" stroke-width="3" paint-order="stroke">{escape(note)}</text>')
    # Stretched as the image is, so the plan never drifts off the map if the box is a hair off the strip's shape.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" preserveAspectRatio="none" '
            f'class="fig-overlay">{"".join(parts)}</svg>')


def _text_of(value, limit=60):
    return value[:limit] if isinstance(value, str) else ''


def draw_strip(strip, source_id):
    """A strip chart: its base map turned course up as JPEG bytes, and the plan over it as SVG; None for a strip this
    module cannot draw."""
    if not valid_strip(strip):
        return None
    frame = StripFrame(strip, source_id)
    image, missing = strip_image(frame, source_id)
    return {'jpeg': jpeg(image), 'svg': strip_overlay_svg(frame, strip, source_id, missing),
            'width': frame.width, 'height': frame.height}
