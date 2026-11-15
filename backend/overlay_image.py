"""
A mission overlay: the map's planning layers, captured by the web as one transparent PNG, made into a file
ForeFlight imports as a map layer (docs/USER_GUIDE.md, *Mission overlay*).

The web draws the image in Leaflet's Web Mercator (EPSG:3857), so it is linear in Mercator metres between the
corners it was drawn for, and those corners are all the server is told. Two files are made from it:

- an **MBTiles** file (PNG tiles, transparent ones left out, rows in TMS order as the format says), which ForeFlight
  recommends and imports on every subscription;
- a **GeoTIFF** in EPSG:3857, the image's own grid, so no pixel is resampled. ForeFlight imports GeoTIFFs only with
  Military Flight Bag and only up to 10,000 pixels a side, and AirDrop sends one to Photos instead (share it by Files
  or email). No ForeFlight page names the projection it wants; GDAL reads this one.

Nothing is kept: the file is built in memory (MBTiles in a temporary file, which SQLite needs) and handed back.
The base map is never in the image: the overlay is drawn over ForeFlight's own charts, and Mapbox's terms do not
allow its imagery to be exported as tiles for another app.
"""

import io
import math
import os
import sqlite3
import tempfile

import numpy as np
from PIL import Image

EARTH_RADIUS_M = 6378137.0
HALF_WORLD_M = math.pi * EARTH_RADIUS_M
MAX_LAT = 85.05112878  # Web Mercator's edge
TILE = 256
# Metres a pixel at zoom 0 on 256-pixel tiles.
ZOOM0_MPP = 2 * HALF_WORLD_M / TILE
MAX_SIDE_PX = 10_000   # ForeFlight's GeoTIFF limit; the web sends at most 6,400 (High detail)
MAX_ZOOM = 22
MAX_LEVELS = 8         # zoom levels below the image's own in an MBTiles pyramid


class OverlayError(ValueError):
    """What is wrong with a request, in words a person can be shown, and a code for the client."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def mercator_x(lon):
    return math.radians(lon) * EARTH_RADIUS_M


def mercator_y(lat):
    return math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)) * EARTH_RADIUS_M


def read_bounds(raw):
    """{north, south, east, west} in degrees, checked, with west brought into [-180, 180)."""
    try:
        north, south, east, west = (float(raw[k]) for k in ('north', 'south', 'east', 'west'))
    except (KeyError, TypeError, ValueError):
        raise OverlayError('bad_bounds', 'The overlay’s corners are missing.') from None
    if not all(math.isfinite(v) for v in (north, south, east, west)):
        raise OverlayError('bad_bounds', 'The overlay’s corners are not numbers.')
    if not (-MAX_LAT <= south < north <= MAX_LAT):
        raise OverlayError('bad_bounds', 'The overlay must lie between 85° N and 85° S.')
    if not (west < east and east - west < 360):
        raise OverlayError('bad_bounds', 'The overlay’s west edge must be west of its east edge.')
    # Leaflet goes on past ±180 as the world repeats: the same place, a turn of the world away.
    turns = math.floor((west + 180) / 360)
    west -= turns * 360
    east -= turns * 360
    return {'north': north, 'south': south, 'east': east, 'west': west}


def open_image(data):
    """The PNG as premultiplied RGBA, refused before it is decoded if it is too large."""
    try:
        image = Image.open(io.BytesIO(data))
    except Exception:  # noqa: BLE001 — Pillow raises many kinds for a file it cannot read
        raise OverlayError('bad_image', 'The overlay image could not be read.') from None
    if image.format != 'PNG':
        raise OverlayError('bad_image', 'The overlay image must be a PNG.')
    width, height = image.size
    if width < 1 or height < 1 or max(width, height) > MAX_SIDE_PX:
        raise OverlayError('too_large', f'The overlay image must be at most {MAX_SIDE_PX:,} pixels a side.')
    try:
        image.load()
    except Exception:  # noqa: BLE001
        raise OverlayError('bad_image', 'The overlay image could not be read.') from None
    return image.convert('RGBA').convert('RGBa')


def _check_shape(image, bounds):
    """The image must have the shape its corners give it in Mercator, or it would be stretched."""
    dx = mercator_x(bounds['east']) - mercator_x(bounds['west'])
    dy = mercator_y(bounds['north']) - mercator_y(bounds['south'])
    width, height = image.size
    expected = dx / dy
    actual = width / height
    # A pixel's worth of rounding either way on the smaller side, and a little more.
    allowed = 2.0 / min(width, height) + 0.01
    if abs(actual / expected - 1) > allowed:
        raise OverlayError('bad_bounds', 'The overlay image does not match the area it was drawn for.')
    return dx, dy


def _description(name):
    return f'{name} — mission overlay from EZ/PZ. Unclassified.'


def build_geotiff(data, raw_bounds, name):
    """A GeoTIFF of the image in EPSG:3857, its alpha kept. Returns the file's bytes."""
    import rasterio
    from rasterio.transform import from_bounds

    bounds = read_bounds(raw_bounds)
    image = open_image(data)
    _check_shape(image, bounds)
    if image.getchannel(3).getbbox() is None:
        raise OverlayError('empty', 'Nothing is drawn in the overlay’s area.')
    width, height = image.size
    rgba = image.convert('RGBA')
    transform = from_bounds(mercator_x(bounds['west']), mercator_y(bounds['south']),
                            mercator_x(bounds['east']), mercator_y(bounds['north']), width, height)
    pixels = np.asarray(rgba, dtype=np.uint8).transpose(2, 0, 1)
    profile = {
        'driver': 'GTiff', 'width': width, 'height': height, 'count': 4, 'dtype': 'uint8',
        'crs': 'EPSG:3857', 'transform': transform, 'photometric': 'RGB', 'alpha': 'YES',
        'compress': 'deflate', 'predictor': 2, 'tiled': True, 'blockxsize': 512, 'blockysize': 512,
    }
    with rasterio.MemoryFile() as memory:
        with memory.open(**profile) as tiff:
            tiff.write(pixels)
            tiff.update_tags(TIFFTAG_IMAGEDESCRIPTION=_description(name), TIFFTAG_SOFTWARE='EZ/PZ')
        return memory.read()


def _zooms(width_m, width_px):
    """The finest zoom (the image's own resolution, rounded up) and the coarsest worth making."""
    image_mpp = width_m / width_px
    own = math.log2(ZOOM0_MPP / image_mpp)
    top = max(0, min(MAX_ZOOM, math.ceil(own - 0.05)))
    bottom = top
    # Down until the whole image fits in a tile, or as far as is worth it.
    while bottom > 0 and top - bottom < MAX_LEVELS and width_m / (ZOOM0_MPP / 2 ** bottom) > TILE:
        bottom -= 1
    return top, bottom


def _tiles(image, bounds, dx, dy, zoom):
    """(column, row from the top, PNG bytes) for each tile at a zoom with anything drawn in it."""
    width, height = image.size
    mpp = ZOOM0_MPP / 2 ** zoom
    # The image's place and size in the zoom's global pixels.
    left = (mercator_x(bounds['west']) + HALF_WORLD_M) / mpp
    top = (HALF_WORLD_M - mercator_y(bounds['north'])) / mpp
    wide = dx / mpp
    tall = dy / mpp
    # Shrunk first where the zoom is coarser than the image, so the tiles are averaged rather than sampled: by area
    # (BOX), which has none of Lanczos's ringing, whose overshoot shifts a line's colour at its edges.
    level = image
    if wide < width:
        level = image.resize((max(1, round(wide)), max(1, round(tall))), Image.Resampling.BOX)
    sx = level.size[0] / wide
    sy = level.size[1] / tall
    count = 2 ** zoom
    first_col, last_col = math.floor(left / TILE), math.floor((left + wide - 1e-9) / TILE)
    first_row, last_row = max(0, math.floor(top / TILE)), min(count - 1, math.floor((top + tall - 1e-9) / TILE))
    for col in range(first_col, last_col + 1):
        for row in range(first_row, last_row + 1):
            x0 = (col * TILE - left) * sx
            y0 = (row * TILE - top) * sy
            tile = level.transform((TILE, TILE), Image.Transform.EXTENT,
                                   (x0, y0, x0 + TILE * sx, y0 + TILE * sy), Image.Resampling.BILINEAR)
            if tile.getchannel(3).getbbox() is None:
                continue  # nothing drawn here
            out = io.BytesIO()
            tile.convert('RGBA').save(out, 'PNG', compress_level=6)
            yield col % count, row, out.getvalue()


def build_mbtiles(data, raw_bounds, name):
    """An MBTiles file of PNG tiles from the image's own zoom down. Returns the file's bytes."""
    bounds = read_bounds(raw_bounds)
    image = open_image(data)
    dx, dy = _check_shape(image, bounds)
    top, bottom = _zooms(dx, image.size[0])

    handle, path = tempfile.mkstemp(suffix='.mbtiles')
    os.close(handle)
    try:
        db = sqlite3.connect(path)
        try:
            db.executescript(
                'CREATE TABLE metadata (name TEXT, value TEXT);'
                'CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);'
                'CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);')
            count = 0
            for zoom in range(top, bottom - 1, -1):
                rows = [(zoom, col, 2 ** zoom - 1 - row, sqlite3.Binary(png))
                        for col, row, png in _tiles(image, bounds, dx, dy, zoom)]
                db.executemany('INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)', rows)
                count += len(rows)
            if count == 0:
                raise OverlayError('empty', 'Nothing is drawn in the overlay’s area.')
            west = max(-180.0, bounds['west'])
            east = min(180.0, bounds['east'])
            middle_lat = (bounds['north'] + bounds['south']) / 2
            metadata = {
                'name': name,
                'type': 'overlay',
                'version': '1.0',
                'description': _description(name),
                'format': 'png',
                'bounds': f"{west:.6f},{bounds['south']:.6f},{east:.6f},{bounds['north']:.6f}",
                'center': f"{(west + east) / 2:.6f},{middle_lat:.6f},{max(bottom, top - 2)}",
                'minzoom': str(bottom),
                'maxzoom': str(top),
                'scheme': 'tms',
            }
            db.executemany('INSERT INTO metadata VALUES (?, ?)', metadata.items())
            db.commit()
        finally:
            db.close()
        with open(path, 'rb') as built:
            return built.read()
    finally:
        os.unlink(path)
