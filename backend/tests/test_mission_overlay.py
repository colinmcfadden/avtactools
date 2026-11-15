"""A mission overlay (`POST /api/overlay-image`, `overlay_image.py`): the captured PNG made into MBTiles and GeoTIFF."""

import io
import math
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from PIL import Image  # noqa: E402

import overlay_image as oi  # noqa: E402
from auth_harness import NativeAuthCase  # noqa: E402
from routes import overlay_image_routes  # noqa: E402
from routes.overlay_image_routes import overlay_image_bp  # noqa: E402

BOUNDS = {'north': 34.7, 'south': 34.5, 'east': -84.1, 'west': -84.3}
WIDTH = 512
RED = (220, 30, 40, 255)
# The square drawn: columns 100 to 139, rows 200 to 239.
SQUARE = (100, 200, 140, 240)


def _height(bounds=BOUNDS, width=WIDTH):
    dx = oi.mercator_x(bounds['east']) - oi.mercator_x(bounds['west'])
    dy = oi.mercator_y(bounds['north']) - oi.mercator_y(bounds['south'])
    return round(width * dy / dx)


def _png(width=WIDTH, height=None, draw=True):
    image = Image.new('RGBA', (width, height or _height(width=width)), (0, 0, 0, 0))
    if draw:
        image.paste(RED, SQUARE)
    out = io.BytesIO()
    image.save(out, 'PNG')
    return out.getvalue()


def _image_point_to_lonlat(col, row, bounds=BOUNDS, width=WIDTH):
    """Where an image pixel's middle is, by the image's own Mercator grid."""
    height = _height(bounds, width)
    x0, x1 = oi.mercator_x(bounds['west']), oi.mercator_x(bounds['east'])
    y0, y1 = oi.mercator_y(bounds['north']), oi.mercator_y(bounds['south'])
    x = x0 + (col + 0.5) / width * (x1 - x0)
    y = y0 + (row + 0.5) / height * (y1 - y0)
    lon = math.degrees(x / oi.EARTH_RADIUS_M)
    lat = math.degrees(2 * math.atan(math.exp(y / oi.EARTH_RADIUS_M)) - math.pi / 2)
    return lon, lat


def _open_mbtiles(data):
    handle, path = tempfile.mkstemp(suffix='.mbtiles')
    os.close(handle)
    with open(path, 'wb') as out:
        out.write(data)
    db = sqlite3.connect(path)

    def close():
        db.close()
        os.unlink(path)
    return db, close


class Bounds(unittest.TestCase):
    def test_a_world_copy_is_brought_back(self):
        self.assertEqual(oi.read_bounds({'north': 1, 'south': 0, 'east': 300, 'west': 290}),
                         {'north': 1.0, 'south': 0.0, 'east': -60.0, 'west': -70.0})
        self.assertEqual(oi.read_bounds({'north': 1, 'south': 0, 'east': -400, 'west': -410})['west'], -50.0)

    def test_corners_that_are_no_area_are_refused(self):
        for bad in ({'north': 1, 'south': 1, 'east': 2, 'west': 1},
                    {'north': 1, 'south': 0, 'east': 1, 'west': 2},
                    {'north': 89, 'south': 0, 'east': 2, 'west': 1},
                    {'north': 'nan', 'south': 0, 'east': 2, 'west': 1},
                    {'north': 1, 'south': 0, 'east': 2},
                    {'north': 1, 'south': 0, 'east': 400, 'west': 1}):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.read_bounds(bad)
            self.assertEqual(caught.exception.code, 'bad_bounds', bad)


class MBTiles(unittest.TestCase):
    def setUp(self):
        self.db, close = _open_mbtiles(oi.build_mbtiles(_png(), BOUNDS, 'OP DK'))
        self.addCleanup(close)

    def test_metadata_says_what_ForeFlight_needs(self):
        meta = dict(self.db.execute('SELECT name, value FROM metadata'))
        self.assertEqual(meta['name'], 'OP DK')
        self.assertEqual(meta['format'], 'png')
        self.assertEqual(meta['type'], 'overlay')
        self.assertEqual(meta['bounds'], '-84.300000,34.500000,-84.100000,34.700000')
        # 43 m a pixel is between zoom 11 and 12: 12, and down to where the image fits a tile.
        self.assertEqual((meta['minzoom'], meta['maxzoom']), ('10', '12'))
        zooms = [z for (z,) in self.db.execute('SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level')]
        self.assertEqual(zooms, [10, 11, 12])

    def test_only_tiles_with_something_drawn_are_kept(self):
        for (data,) in self.db.execute('SELECT tile_data FROM tiles'):
            tile = Image.open(io.BytesIO(data))
            self.assertEqual(tile.size, (256, 256))
            self.assertIsNotNone(tile.getchannel('A').getbbox())
        # The square is 40 pixels: a tile or a few at each zoom, never the image's every tile.
        self.assertLessEqual(self.db.execute('SELECT COUNT(*) FROM tiles WHERE zoom_level = 12').fetchone()[0], 4)

    def test_the_square_is_where_it_was_drawn_rows_counted_from_the_south(self):
        lon, lat = _image_point_to_lonlat(120, 220)
        zoom = 12
        mpp = oi.ZOOM0_MPP / 2 ** zoom
        gx = (oi.mercator_x(lon) + oi.HALF_WORLD_M) / mpp
        gy = (oi.HALF_WORLD_M - oi.mercator_y(lat)) / mpp
        col, row = int(gx // 256), int(gy // 256)
        found = self.db.execute('SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?',
                                (zoom, col, 2 ** zoom - 1 - row)).fetchone()
        self.assertIsNotNone(found)
        pixel = Image.open(io.BytesIO(found[0])).convert('RGBA').getpixel((int(gx % 256), int(gy % 256)))
        self.assertEqual(pixel, RED)

    def test_edges_keep_their_colour(self):
        # Premultiplied while resampled: a half-covered pixel is the square's red, less opaque, never darkened
        # toward the transparent black round it (within 8-bit rounding, which grows as the alpha falls).
        for (data,) in self.db.execute('SELECT tile_data FROM tiles'):
            tile = Image.open(io.BytesIO(data)).convert('RGBA')
            for x in range(256):
                for y in range(256):
                    r, g, b, a = tile.getpixel((x, y))
                    if a >= 32:
                        self.assertLessEqual(abs(r - RED[0]), 16, (x, y, a))
                        self.assertLessEqual(abs(g - RED[1]), 16, (x, y, a))


class GeoTIFF(unittest.TestCase):
    def test_the_image_is_kept_pixel_for_pixel_on_its_own_grid(self):
        import rasterio
        from rasterio.enums import ColorInterp

        data = oi.build_geotiff(_png(), BOUNDS, 'OP DK')
        with rasterio.MemoryFile(data) as memory, memory.open() as tiff:
            self.assertEqual(tiff.crs.to_epsg(), 3857)
            self.assertEqual((tiff.width, tiff.height), (WIDTH, _height()))
            self.assertEqual(tiff.colorinterp[3], ColorInterp.alpha)
            left, bottom, right, top = tiff.bounds
            self.assertAlmostEqual(left, oi.mercator_x(-84.3), places=3)
            self.assertAlmostEqual(top, oi.mercator_y(34.7), places=3)
            self.assertAlmostEqual(right, oi.mercator_x(-84.1), places=3)
            self.assertAlmostEqual(bottom, oi.mercator_y(34.5), places=3)
            pixels = tiff.read()
            self.assertEqual(tuple(int(v) for v in pixels[:, 220, 120]), RED)
            self.assertEqual(int(pixels[3, 0, 0]), 0)
            self.assertIn('OP DK', tiff.tags()['TIFFTAG_IMAGEDESCRIPTION'])


class Refusals(unittest.TestCase):
    def test_what_is_refused_and_why(self):
        cases = [
            (b'not a png', BOUNDS, 'bad_image'),
            (_png(height=_height() + 60), BOUNDS, 'bad_bounds'),  # stretched
            (_png(draw=False), BOUNDS, 'empty'),
        ]
        jpeg = io.BytesIO()
        Image.new('RGB', (WIDTH, _height())).save(jpeg, 'JPEG')
        cases.append((jpeg.getvalue(), BOUNDS, 'bad_image'))
        for build in (oi.build_mbtiles, oi.build_geotiff):
            for data, bounds, code in cases:
                with self.assertRaises(oi.OverlayError) as caught:
                    build(data, bounds, 'X')
                self.assertEqual(caught.exception.code, code, (build.__name__, code))

    def test_an_image_past_ten_thousand_pixels_is_refused_before_it_is_decoded(self):
        big = io.BytesIO()
        Image.new('RGBA', (10_001, 2), (0, 0, 0, 0)).save(big, 'PNG')
        with mock.patch.object(Image.Image, 'load', side_effect=AssertionError('decoded')):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.open_image(big.getvalue())
        self.assertEqual(caught.exception.code, 'too_large')


class Route(NativeAuthCase):
    extra_blueprints = (overlay_image_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}

    def post(self, fields=None, head=None, image=None):
        form = {**{k: str(v) for k, v in BOUNDS.items()}, 'format': 'mbtiles', 'name': 'OP DK / night', **(fields or {})}
        form['image'] = (io.BytesIO(_png() if image is None else image), 'overlay.png')
        return self.client.post('/api/overlay-image', headers=self.head if head is None else head,
                                data=form, content_type='multipart/form-data')

    def test_mbtiles_and_geotiff_are_answered_as_files_named_for_the_layer(self):
        for kind, extension in (('mbtiles', 'mbtiles'), ('geotiff', 'tif')):
            response = self.post({'format': kind})
            self.assertEqual(response.status_code, 200, response.data[:300])
            self.assertEqual(response.headers['Cache-Control'], 'no-store')
            self.assertIn(f'filename="OP DK night.{extension}"', response.headers['Content-Disposition'])
            self.assertGreater(len(response.data), 100)

    def test_signed_out_is_refused(self):
        self.assertEqual(self.post(head={}).status_code, 401)

    def test_a_refusal_says_why(self):
        response = self.post({'format': 'png'})
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'bad_format'))
        response = self.post(image=_png(height=_height() + 60))
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'bad_bounds'))
        response = self.client.post('/api/overlay-image', headers=self.head, data={'format': 'mbtiles'},
                                    content_type='multipart/form-data')
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'no_image'))

    def test_one_is_made_at_a_time(self):
        held = threading.Lock()
        held.acquire()
        with mock.patch.object(overlay_image_routes, '_build_lock', held), \
                mock.patch.object(overlay_image_routes, 'LOCK_WAIT_SECONDS', 0):
            response = self.post()
        self.assertEqual((response.status_code, response.get_json()['code']), (503, 'busy'))

    def test_a_layer_name_is_made_safe(self):
        self.assertEqual(overlay_image_routes._layer_name('  ../../etc/passwd  '), 'etc passwd')
        self.assertEqual(overlay_image_routes._layer_name(''), 'MISSION OVERLAY')
        self.assertEqual(overlay_image_routes._layer_name('A' * 90), 'A' * 60)
        self.assertEqual(overlay_image_routes._layer_name('OP DK (2) — night'), 'OP DK (2) night')


if __name__ == '__main__':
    unittest.main()
