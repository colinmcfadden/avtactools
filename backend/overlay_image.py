"""
A mission overlay: the map's planning layers, drawn by the web in pieces, made into a file ForeFlight imports as a
map layer (docs/USER_GUIDE.md, *Mission overlay*), with or without USGS's topo map under them.

The web draws the area in Leaflet's Web Mercator at one zoom, in square pieces of PIECE device pixels, and says where
the area lies in that zoom's global pixels (`Area`). Each piece is a transparent PNG; together they tile the area from
its top left corner, the last row and column reaching past it. Two files are made from them:

- an **MBTiles** file (rows in TMS order, as the format says), at a whole zoom whose tiles the pieces line up with,
  then each zoom below made from the one above, as far as the whole area in a tile or MAX_LEVELS. Without a base map
  its tiles are PNGs and transparent ones are left out; with one, each tile is the overlay laid on USGS's own tile for
  that zoom (its labels drawn for that zoom, not shrunk from a finer one), as a JPEG. ForeFlight imports MBTiles on
  every subscription.
- a **GeoTIFF** in plain latitude and longitude (EPSG:4326), at most GEOTIFF_SIDE pixels a side (ForeFlight's limit;
  it takes GeoTIFFs only with Military Flight Bag). It was written in Web Mercator until 2026-11-15, and ForeFlight
  then showed the whole world with the overlay nowhere: it read the file's Mercator metres as degrees, it seems. A
  column of the Mercator image is already a line of longitude, so only its rows are moved, each output row taken from
  the Mercator rows at its latitude, between two of them. Without a base map it keeps its transparency; with one it is
  opaque RGB.

Only the pieces with something in them need be sent: a piece not sent is transparent. Made in the background
(`overlay_jobs.py`), an MBTiles file may be as large as MBTILES_BACKGROUND_TILES tiles at its top zoom, the topo map's
own level 16 over a whole operation, and the topo map's tiles are streamed from USGS as they are written
(`UsgsTopo.stream`); asked for while the client waits (`/api/overlay-image`), it keeps to MBTILES_TILES, which
Cloudflare's 100 s allow.

The overlay is blended in premultiplied alpha throughout, so a line's edge keeps its colour. Nothing is kept: the file
is written to a temporary file the caller hands back and deletes. Satellite imagery and Mapbox's topo map are never a
base: Mapbox's terms do not allow its imagery to be exported for another app.
"""

import contextlib
import io
import itertools
import math
import os
import re
import sqlite3
import tempfile
import zlib
from dataclasses import dataclass

import numpy as np
from PIL import Image

from usgs_topo import MAX_ZOOM as USGS_MAX_ZOOM
from usgs_topo import TopoError, UsgsTopo

TILE = 256
PIECE = 2048                # device pixels a side of each piece the web draws: 8 tiles, and under any phone's canvas limit
MAX_ZOOM = 20               # past this a pixel is about 10 cm and shows nothing more
GEOTIFF_SIDE = 10_000       # ForeFlight's limit for a GeoTIFF
MBTILES_TILES = 1_600       # the most tiles at an MBTiles file's top zoom: about 100 million pixels, as a GeoTIFF's most
# The most at the top zoom of one made in the background: about 110 by 140 km at level 16 in the lower 48, some 85,000
# tiles with the zooms below, on the topo map about 1.3 GB and twenty minutes' fetching.
MBTILES_BACKGROUND_TILES = 64_000
MAX_LEVELS = 8              # zooms below the top in an MBTiles pyramid
BASE_TILE_BUDGET = 2_000    # USGS tiles a GeoTIFF's base is drawn from at most: about half a minute's fetching
BACKGROUND_BASE_BUDGET = 6_000  # and in the background, where no request waits: its level 16 for a whole GeoTIFF
MAX_PIECE_BYTES = 24 * 1024 * 1024
JPEG_QUALITY = 85
CHUNK = 256                 # Mercator rows a GeoTIFF is made from at a time
STRIP = 512                 # GeoTIFF rows written at a time, one block high

BASES = ('none', 'usgs-topo')


class OverlayError(ValueError):
    """What is wrong with a request, in words a person can be shown, and a code for the client."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Area:
    """Where the overlay is: a zoom (device pixels, on a tenth), and a box in that zoom's global pixels."""

    zoom: float
    left: int
    top: int
    width: int
    height: int

    @property
    def world(self):
        return TILE * 2 ** self.zoom

    def lon(self, x):
        return x / self.world * 360 - 180

    def lat(self, y):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / self.world))))

    def y_of(self, lat):
        """The global pixel row of a latitude (a pixel's top edge at a whole number)."""
        s = math.sin(math.radians(lat))
        return (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * self.world

    def bounds(self):
        """{north, south, east, west} in degrees, west in [-180, 180): the map repeats past ±180."""
        west, east = self.lon(self.left), self.lon(self.left + self.width)
        turns = math.floor((west + 180) / 360)
        return {'north': self.lat(self.top), 'south': self.lat(self.top + self.height),
                'west': west - turns * 360, 'east': east - turns * 360}

    @property
    def piece_columns(self):
        return -(-self.width // PIECE)

    @property
    def piece_rows(self):
        return -(-self.height // PIECE)


_INTEGER = re.compile(r'-?\d{1,12}')


def _integer(form, key):
    value = str(form.get(key, '')).strip()
    if not _INTEGER.fullmatch(value):
        raise OverlayError('bad_area', 'Where the overlay lies was not sent.')
    return int(value)


def mbtiles_limit(background):
    """The most tiles at an MBTiles file's top zoom: more in the background, where the topo map is streamed and only
    the pieces with something in them are sent."""
    return MBTILES_BACKGROUND_TILES if background else MBTILES_TILES


def read_area(form, kind, base, background=False):
    """The overlay's place from the request's fields, checked against what the format and the base allow: more in the
    background (`overlay_jobs.py`), where no request waits for the file."""
    try:
        zoom = float(str(form.get('zoom', '')).strip())
    except ValueError:
        raise OverlayError('bad_area', 'Where the overlay lies was not sent.') from None
    if not math.isfinite(zoom) or not 0 <= zoom <= MAX_ZOOM or abs(zoom * 10 - round(zoom * 10)) > 1e-6:
        raise OverlayError('bad_area', 'The overlay’s zoom is not one the map draws at.')
    zoom = round(zoom * 10) / 10
    left, top, width, height = (_integer(form, key) for key in ('left', 'top', 'width', 'height'))
    if width < 1 or height < 1:
        raise OverlayError('bad_area', 'The overlay has no area.')
    if kind == 'mbtiles':
        if zoom != int(zoom) or any(value % TILE for value in (left, top, width, height)):
            raise OverlayError('bad_area', 'An MBTiles overlay must be drawn on its tiles.')
        zoom = int(zoom)
        left %= TILE * 2 ** zoom
    area = Area(zoom, left, top, width, height)
    if top < 0 or top + height > area.world + 1e-6 or width > area.world + 1e-6:
        raise OverlayError('bad_area', 'The overlay must lie between 85° N and 85° S.')
    if kind == 'geotiff' and max(width, height) > GEOTIFF_SIDE:
        raise OverlayError('too_large', f'A GeoTIFF overlay must be at most {GEOTIFF_SIDE:,} pixels a side.')
    limit = mbtiles_limit(background)
    if kind == 'mbtiles' and (width // TILE) * (height // TILE) > limit:
        raise OverlayError('too_large', f'An MBTiles overlay must be at most {limit:,} tiles at its finest.')
    if base == 'usgs-topo' and zoom > USGS_MAX_ZOOM + 1e-9:
        raise OverlayError('too_fine', 'The USGS topo map has no more detail than about 1:20,000. Choose less detail.')
    return area


_PIECE_NAME = re.compile(r'piece-(\d{1,3})-(\d{1,3})')


def piece_key(name, area):
    """(column, row) of a `piece-<column>-<row>` the area has, or None."""
    match = _PIECE_NAME.fullmatch(name)
    if not match:
        return None
    key = (int(match[1]), int(match[2]))
    return key if key[0] < area.piece_columns and key[1] < area.piece_rows else None


def read_pieces(files, area, every=True):
    """{(column, row): file} for the request's `piece-<column>-<row>` files: exactly the ones the area needs, or with
    `every` false any of them (a piece not sent is transparent)."""
    pieces = {}
    for key in files:
        at = piece_key(key, area)
        if at is None:
            raise OverlayError('bad_pieces', 'The overlay was sent with something else in it.')
        pieces[at] = files[key]
    wanted = {(i, j) for i in range(area.piece_columns) for j in range(area.piece_rows)}
    if every and set(pieces) != wanted:
        raise OverlayError('bad_pieces', 'Part of the overlay is missing. Make it again.')
    return pieces


def looks_like_piece(data):
    """Whether bytes start as a PNG of PIECE pixels a side, read from its header alone (it is decoded when drawn)."""
    return (len(data) > 24 and data[:8] == b'\x89PNG\r\n\x1a\n' and data[12:16] == b'IHDR'
            and int.from_bytes(data[16:20], 'big') == PIECE and int.from_bytes(data[20:24], 'big') == PIECE)


def _read(source):
    if isinstance(source, (bytes, bytearray)):
        return bytes(source)
    if isinstance(source, (str, os.PathLike)):
        with open(source, 'rb') as file:
            data = file.read(MAX_PIECE_BYTES + 1)
    else:
        data = source.read(MAX_PIECE_BYTES + 1)
    if len(data) > MAX_PIECE_BYTES:
        raise OverlayError('too_large', 'Part of the overlay is too large to send.')
    return data


def open_piece(source):
    """A piece as premultiplied RGBA pixels (rows, columns, 4), refused before it is decoded if it is not a piece."""
    try:
        image = Image.open(io.BytesIO(_read(source)))
    except OverlayError:
        raise
    except Exception:  # noqa: BLE001 — Pillow raises many kinds for a file it cannot read
        raise OverlayError('bad_image', 'Part of the overlay could not be read.') from None
    if image.format != 'PNG':
        raise OverlayError('bad_image', 'The overlay must be sent as PNG images.')
    if image.size != (PIECE, PIECE):
        raise OverlayError('bad_image', 'Part of the overlay is not the size it should be.')
    try:
        image.load()
    except Exception:  # noqa: BLE001
        raise OverlayError('bad_image', 'Part of the overlay could not be read.') from None
    return np.asarray(image.convert('RGBA').convert('RGBa'))


def _unpremultiply(pixels):
    """Straight RGBA from premultiplied (…, 4) uint8 pixels."""
    alpha = pixels[..., 3:4].astype(np.uint32)
    rgb = pixels[..., :3].astype(np.uint32)
    straight = np.where(alpha > 0, np.minimum(255, (rgb * 255 + alpha // 2) // np.maximum(alpha, 1)), 0)
    return np.concatenate([straight.astype(np.uint8), pixels[..., 3:4]], axis=-1)


def _over(base, overlay):
    """Premultiplied overlay pixels laid on opaque RGB base pixels of the same shape."""
    alpha = overlay[..., 3:4].astype(np.uint16)
    return ((base.astype(np.uint16) * (255 - alpha) + 127) // 255 + overlay[..., :3]).astype(np.uint8)


def _description(name):
    return f'{name} — mission overlay from EZ/PZ. Unclassified.'


def _temporary(suffix):
    handle, path = tempfile.mkstemp(suffix=suffix)
    os.close(handle)
    return path


# ---------------------------------------------------------------------------------------------------------- GeoTIFF


def _base_zoom(area, budget=BASE_TILE_BUDGET):
    """The USGS zoom a GeoTIFF's base is drawn from: the first at or finer than the overlay's, one coarser while the
    tiles it takes are more than the budget."""
    zoom = min(USGS_MAX_ZOOM, math.ceil(area.zoom - 1e-9))
    while zoom > 0 and len(_base_keys(area, zoom, 0, area.height)) > budget:
        zoom -= 1
    return zoom


def _base_keys(area, zoom, row0, row1):
    """The USGS tiles at `zoom` under the area's Mercator rows [row0, row1)."""
    f = 2 ** (zoom - area.zoom)
    count = 2 ** zoom
    x0 = math.floor(area.left * f / TILE)
    x1 = math.floor(((area.left + area.width) * f - 1e-9) / TILE)
    y0 = max(0, math.floor((area.top + row0) * f / TILE))
    y1 = min(count - 1, math.floor(((area.top + row1) * f - 1e-9) / TILE))
    return [(zoom, x % count, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def _base_rows(area, topo, zoom, row0, row1):
    """The topo map under the area's Mercator rows [row0, row1), at the overlay's pixels, as RGB."""
    f = 2 ** (zoom - area.zoom)
    count = 2 ** zoom
    gx0, gx1 = area.left * f, (area.left + area.width) * f
    gy0, gy1 = (area.top + row0) * f, (area.top + row1) * f
    x0, x1 = math.floor(gx0 / TILE), math.floor((gx1 - 1e-9) / TILE)
    y0, y1 = max(0, math.floor(gy0 / TILE)), min(count - 1, math.floor((gy1 - 1e-9) / TILE))
    mosaic = Image.new('RGB', ((x1 - x0 + 1) * TILE, (y1 - y0 + 1) * TILE))
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            mosaic.paste(topo.tile(zoom, x % count, y), ((x - x0) * TILE, (y - y0) * TILE))
    box = (gx0 - x0 * TILE, gy0 - y0 * TILE, gx1 - x0 * TILE, gy1 - y0 * TILE)
    return np.asarray(mosaic.resize((area.width, row1 - row0), Image.Resampling.LANCZOS, box=box))


class _MercatorRows:
    """The overlay's Mercator rows, made a CHUNK at a time from the pieces (and laid on the base), kept while needed."""

    def __init__(self, area, pieces, topo, base_zoom):
        self.area = area
        self.pieces = pieces
        self.topo = topo
        self.base_zoom = base_zoom
        self.chunks = {}
        self.piece_row = (None, None)   # (row, [pixels of each piece in it])
        self.drawn = False

    def _pieces_in_row(self, j):
        if self.piece_row[0] != j:
            self.piece_row = (None, None)   # let the last row go before the next is decoded
            self.piece_row = (j, [open_piece(self.pieces[(i, j)]) if (i, j) in self.pieces else _NOTHING
                                  for i in range(self.area.piece_columns)])
        return self.piece_row[1]

    def _chunk(self, k):
        area = self.area
        row0, row1 = k * CHUNK, min(area.height, (k + 1) * CHUNK)
        j = row0 // PIECE
        offset = row0 - j * PIECE
        overlay = np.concatenate([piece[offset:offset + row1 - row0] for piece in self._pieces_in_row(j)],
                                 axis=1)[:, :area.width]
        if overlay[..., 3].any():
            self.drawn = True
        if self.topo is None:
            return overlay
        return _over(_base_rows(area, self.topo, self.base_zoom, row0, row1), overlay)

    def rows(self, first, last):
        """Rows [first, last], inclusive, as one array; chunks before `first` are let go."""
        k0, k1 = first // CHUNK, last // CHUNK
        for k in [k for k in self.chunks if k < k0]:
            del self.chunks[k]
        for k in range(k0, k1 + 1):
            if k not in self.chunks:
                self.chunks[k] = self._chunk(k)
        joined = np.concatenate([self.chunks[k] for k in range(k0, k1 + 1)], axis=0)
        return joined[first - k0 * CHUNK:last - k0 * CHUNK + 1]


# A piece not sent: nothing is drawn in it.
_NOTHING = np.zeros((PIECE, PIECE, 4), dtype=np.uint8)
_NOTHING.setflags(write=False)


def _told(progress, stage):
    """A `progress(done, total)` for a stage, from the build's `progress(stage, done, total)` (or none)."""
    return None if progress is None else (lambda done, total: progress(stage, done, total))


def build_geotiff(area, pieces, base, name, topo=None, progress=None, background=False):
    """A GeoTIFF of the overlay in EPSG:4326. Returns the path of a temporary file the caller deletes.
    `progress(stage, done, total)` is told as it goes (`fetching` the base, then `writing`), and may raise to stop it."""
    import rasterio
    from rasterio.transform import from_bounds
    from rasterio.windows import Window

    bounds = area.bounds()
    width, height = area.width, area.height
    base_zoom = None
    if base == 'usgs-topo':
        topo = topo or UsgsTopo()
        base_zoom = _base_zoom(area, BACKGROUND_BASE_BUDGET if background else BASE_TILE_BUDGET)
        topo.fetch(_base_keys(area, base_zoom, 0, height), progress=_told(progress, 'fetching'))
    else:
        topo = None
    source = _MercatorRows(area, pieces, topo, base_zoom)
    bands = 3 if topo else 4

    profile = {
        'driver': 'GTiff', 'width': width, 'height': height, 'count': bands, 'dtype': 'uint8',
        'crs': 'EPSG:4326',
        'transform': from_bounds(bounds['west'], bounds['south'], bounds['east'], bounds['north'], width, height),
        'photometric': 'RGB', 'compress': 'deflate', 'predictor': 2,
        'tiled': True, 'blockxsize': STRIP, 'blockysize': STRIP,
    }
    if bands == 4:
        profile['alpha'] = 'YES'
    # Where each output row's middle falls among the Mercator rows' middles.
    step = (bounds['north'] - bounds['south']) / height
    path = _temporary('.tif')
    try:
        with rasterio.open(path, 'w', **profile) as tiff:
            for r0 in range(0, height, STRIP):
                r1 = min(height, r0 + STRIP)
                lats = bounds['north'] - (np.arange(r0, r1) + 0.5) * step
                sines = np.sin(np.radians(lats))
                at = (0.5 - np.log((1 + sines) / (1 - sines)) / (4 * math.pi)) * area.world - area.top - 0.5
                at = np.clip(at, 0, height - 1)
                below = np.floor(at).astype(np.int64)
                above = np.minimum(below + 1, height - 1)
                weight = np.round((at - below) * 256).astype(np.uint16)[:, None, None]
                first, last = int(below.min()), int(above.max())
                rows = source.rows(first, last)
                mixed = ((rows[below - first].astype(np.uint16) * (256 - weight)
                          + rows[above - first].astype(np.uint16) * weight + 128) >> 8).astype(np.uint8)
                if bands == 4:
                    mixed = _unpremultiply(mixed)
                tiff.write(np.ascontiguousarray(mixed.transpose(2, 0, 1)), window=Window(0, r0, width, r1 - r0))
                if progress is not None:
                    progress('writing', r1, height)
            tiff.update_tags(TIFFTAG_IMAGEDESCRIPTION=_description(name), TIFFTAG_SOFTWARE='EZ/PZ')
        if topo is None and not source.drawn:
            raise OverlayError('empty', 'Nothing is drawn in the overlay’s area.')
        return path
    except BaseException:
        os.unlink(path)
        raise


# ---------------------------------------------------------------------------------------------------------- MBTiles


def _bottom_zoom(top, columns, rows):
    """The coarsest zoom worth making: where the whole area is in a tile, or MAX_LEVELS below the top."""
    bottom = top
    while bottom > 0 and top - bottom < MAX_LEVELS and max(columns, rows) / 2 ** (top - bottom) > 1:
        bottom -= 1
    return bottom


def _pack(pixels):
    return zlib.compress(pixels.tobytes(), 1)


def _unpack(packed):
    return np.frombuffer(zlib.decompress(packed), dtype=np.uint8).reshape(TILE, TILE, 4)


def _halve(children):
    """A tile from its four children, {(dx, dy): premultiplied pixels}, averaged two by two (a box filter)."""
    joined = np.zeros((2 * TILE, 2 * TILE, 4), dtype=np.uint8)
    for (dx, dy), pixels in children.items():
        joined[dy * TILE:(dy + 1) * TILE, dx * TILE:(dx + 1) * TILE] = pixels
    return ((joined.reshape(TILE, 2, TILE, 2, 4).astype(np.uint16).sum(axis=(1, 3)) + 2) // 4).astype(np.uint8)


def _encode(image, kind):
    out = io.BytesIO()
    if kind == 'png':
        image.save(out, 'PNG', compress_level=6)
    else:
        image.save(out, 'JPEG', quality=JPEG_QUALITY, optimize=True)
    return out.getvalue()


def pyramid(area):
    """The zooms of an MBTiles file of the area, top first, each with its tiles as [(x, y)] (x not yet brought into
    the world)."""
    top = int(area.zoom)
    x0, y0 = area.left // TILE, area.top // TILE
    columns, rows = area.width // TILE, area.height // TILE
    bottom = _bottom_zoom(top, columns, rows)

    def tiles_at(zoom):
        shift = top - zoom
        return [(x, y) for y in range(y0 >> shift, ((y0 + rows - 1) >> shift) + 1)
                for x in range(x0 >> shift, ((x0 + columns - 1) >> shift) + 1)]

    return [(zoom, tiles_at(zoom)) for zoom in range(top, bottom - 1, -1)]


def build_mbtiles(area, pieces, base, name, topo=None, progress=None, background=False):
    """An MBTiles file of the overlay from its zoom down. Returns the path of a temporary file the caller deletes.
    `progress('writing', done, total)` is told as each tile is written, and may raise to stop it.

    With the topo map every tile of every zoom is USGS's own for it, streamed as it is written, so a file of tens of
    thousands of tiles holds no more than a few hundred at once; the overlay is laid on where something is drawn, and
    elsewhere USGS's JPEG is kept as it came. The overlay at each zoom below the top is made from the one above, and
    only where something is drawn, so it is kept for the zoom being made alone."""
    top = int(area.zoom)
    count = 2 ** top
    x0, y0 = area.left // TILE, area.top // TILE
    columns, rows = area.width // TILE, area.height // TILE
    per_piece = PIECE // TILE
    zooms = pyramid(area)
    bottom = zooms[-1][0]
    with_base = base == 'usgs-topo'
    if with_base:
        topo = topo or UsgsTopo()

    # The top zoom's tiles a piece at a time (each piece decoded once), and only those of pieces sent without a base.
    cells = [(i, j, u, v) for j in range(area.piece_rows) for i in range(area.piece_columns)
             for v in range(per_piece) for u in range(per_piece)
             if i * per_piece + u < columns and j * per_piece + v < rows]
    if not with_base:
        cells = [cell for cell in cells if (cell[0], cell[1]) in pieces]
    total = len(cells) + sum(len(keys) for _, keys in zooms[1:])
    done = 0

    def tick():
        nonlocal done
        done += 1
        if progress is not None:
            progress('writing', done, total)

    def output(zoom, x, y, overlay, data):
        """The file's tile at (zoom, x, y) for these overlay pixels (premultiplied, or None where nothing is drawn),
        on USGS's tile `data` as it was streamed."""
        if not with_base:
            return None if overlay is None else _encode(Image.fromarray(_unpremultiply(overlay), 'RGBA'), 'png')
        key = (zoom, x % 2 ** zoom, y)
        if overlay is None:
            kept = topo.jpeg_of(key, data)
            if kept is not None:
                return kept
        pixels = np.asarray(topo.image_of(key, data))
        if overlay is not None:
            pixels = _over(pixels, overlay)
        return _encode(Image.fromarray(pixels, 'RGB'), 'jpeg')

    def streamed(keys):
        """USGS's tiles for [(zoom, x, y)] in order as (key, bytes), or (None, None) for each without a base."""
        if not with_base:
            return contextlib.nullcontext(itertools.repeat((None, None), len(keys)))
        return contextlib.closing(topo.stream([(z, x % 2 ** z, y) for z, x, y in keys]))

    path = _temporary('.mbtiles')
    try:
        db = sqlite3.connect(path)
        try:
            db.executescript(
                'CREATE TABLE metadata (name TEXT, value TEXT);'
                'CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);'
                'CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);')

            def store(zoom, x, y, data):
                if data is not None:
                    db.execute('INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)',
                               (zoom, x % 2 ** zoom, 2 ** zoom - 1 - y, sqlite3.Binary(data)))

            level = {}   # (x, y) -> packed premultiplied pixels of the overlay at the zoom just made
            keys = [(top, x0 + i * per_piece + u, y0 + j * per_piece + v) for i, j, u, v in cells]
            with streamed(keys) as bases:
                at, pixels = None, None
                for (i, j, u, v), (_, data) in zip(cells, bases):
                    if at != (i, j):
                        at, pixels = (i, j), None   # let the last piece go before the next is decoded
                        if (i, j) in pieces:
                            pixels = open_piece(pieces[(i, j)])
                    tx, ty = x0 + i * per_piece + u, y0 + j * per_piece + v
                    overlay = None
                    if pixels is not None:
                        tile = pixels[v * TILE:(v + 1) * TILE, u * TILE:(u + 1) * TILE]
                        if tile[..., 3].any():
                            overlay = tile
                            level[(tx, ty)] = _pack(tile)
                    store(top, tx, ty, output(top, tx, ty, overlay, data))
                    tick()
                pixels = None
            if not level and not with_base:
                raise OverlayError('empty', 'Nothing is drawn in the overlay’s area.')
            for zoom, tiles in zooms[1:]:
                below, level = level, {}
                with streamed([(zoom, x, y) for x, y in tiles]) as bases:
                    for (x, y), (_, data) in zip(tiles, bases):
                        children = {(dx, dy): _unpack(below[(2 * x + dx, 2 * y + dy)])
                                    for dx in (0, 1) for dy in (0, 1) if (2 * x + dx, 2 * y + dy) in below}
                        overlay = _halve(children) if children else None
                        if overlay is not None and not overlay[..., 3].any():
                            overlay = None
                        if overlay is not None:
                            level[(x, y)] = _pack(overlay)
                        store(zoom, x, y, output(zoom, x, y, overlay, data))
                        tick()

            bounds = area.bounds()
            west, east = max(-180.0, bounds['west']), min(180.0, bounds['east'])
            metadata = {
                'name': name,
                'type': 'overlay',
                'version': '1.0',
                'description': _description(name),
                'format': 'jpg' if with_base else 'png',
                'bounds': f"{west:.6f},{bounds['south']:.6f},{east:.6f},{bounds['north']:.6f}",
                'center': f"{(west + east) / 2:.6f},{(bounds['north'] + bounds['south']) / 2:.6f},"
                          f"{max(bottom, top - 2)}",
                'minzoom': str(bottom),
                'maxzoom': str(top),
                'scheme': 'tms',
            }
            db.executemany('INSERT INTO metadata VALUES (?, ?)', metadata.items())
            db.commit()
        finally:
            db.close()
        return path
    except BaseException:
        os.unlink(path)
        raise


FORMATS = {'mbtiles': (build_mbtiles, 'mbtiles', 'application/octet-stream'),
           'geotiff': (build_geotiff, 'tif', 'image/tiff')}


def build(kind, area, pieces, base, name, topo=None, progress=None, background=False):
    """The file for a format: the path of a temporary file the caller deletes. `progress(stage, done, total)` is told
    as it goes and may raise to stop it; `background` allows what a request waiting for the file could not."""
    try:
        return FORMATS[kind][0](area, pieces, base, name, topo=topo, progress=progress, background=background)
    except TopoError as failed:
        raise OverlayError(failed.code, failed.message) from None
