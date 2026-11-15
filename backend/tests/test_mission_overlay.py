"""A mission overlay (`POST /api/overlay-image`, `overlay_image.py`, `usgs_topo.py`): the pieces the web draws made into
MBTiles and GeoTIFF files, with or without USGS's topo map under them."""

import io
import math
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import numpy as np  # noqa: E402
import requests  # noqa: E402
from PIL import Image  # noqa: E402

import overlay_image as oi  # noqa: E402
import usgs_topo  # noqa: E402
from auth_harness import NativeAuthCase  # noqa: E402
from routes import overlay_image_routes  # noqa: E402
from routes.overlay_image_routes import overlay_image_bp  # noqa: E402

RED = (220, 30, 40, 255)
BLUE = (30, 60, 220, 255)
# Near the Ellijay test LZ.
LAT, LON = 34.62, -84.38


def _global(zoom, lat, lon):
    """Where a place is in a zoom's global pixels, as Leaflet projects it."""
    world = 256 * 2 ** zoom
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * world, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * world


def _area(zoom, width, height, lat=LAT, lon=LON, snap=1):
    """An area of this size round a place, its corner on a multiple of `snap`."""
    gx, gy = _global(zoom, lat, lon)
    return oi.Area(zoom, round((gx - width / 2) / snap) * snap, round((gy - height / 2) / snap) * snap, width, height)


def _png(image):
    out = io.BytesIO()
    image.save(out, 'PNG')
    return out.getvalue()


def _pieces(area, marks=()):
    """The area's pieces as the web sends them, with squares drawn: marks are (global x, global y, half side, colour)."""
    pieces = {}
    for i in range(area.piece_columns):
        for j in range(area.piece_rows):
            image = Image.new('RGBA', (oi.PIECE, oi.PIECE), (0, 0, 0, 0))
            ox, oy = area.left + i * oi.PIECE, area.top + j * oi.PIECE
            for gx, gy, half, colour in marks:
                box = [round(gx - half - ox), round(gy - half - oy), round(gx + half - ox), round(gy + half - oy)]
                box = [min(oi.PIECE, max(0, v)) for v in box]
                if box[0] < box[2] and box[1] < box[3]:
                    image.paste(colour, tuple(box))
            pieces[(i, j)] = _png(image)
    return pieces


def _mark(area, lat, lon, half=6, colour=RED):
    gx, gy = _global(area.zoom, lat, lon)
    return (gx, gy, half, colour)


def _open_mbtiles(path):
    db = sqlite3.connect(path)

    def close():
        db.close()
        os.unlink(path)
    return db, close


def _tile_at(db, zoom, lat, lon):
    """(tile image, pixel in it) where a place falls at a zoom, or (None, None) where the file has no tile."""
    gx, gy = _global(zoom, lat, lon)
    col, row = int(gx // 256), int(gy // 256)
    found = db.execute('SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?',
                       (zoom, col % 2 ** zoom, 2 ** zoom - 1 - row)).fetchone()
    if found is None:
        return None, None
    return Image.open(io.BytesIO(found[0])), (int(gx % 256), int(gy % 256))


class FakeTopo:
    """USGS's topo map as one colour a zoom, recording what was fetched."""

    def __init__(self):
        self.fetched = []

    @staticmethod
    def colour(zoom):
        return (40 + 10 * zoom, 120, 60)

    def fetch(self, keys):
        self.fetched.extend(keys)

    def tile(self, z, x, y):
        return Image.new('RGB', (256, 256), self.colour(z))

    def jpeg(self, z, x, y):
        out = io.BytesIO()
        self.tile(z, x, y).save(out, 'JPEG', quality=95)
        return out.getvalue()


# ------------------------------------------------------------------------------------------------------- reading


class ReadingTheArea(unittest.TestCase):
    def form(self, **fields):
        return {'zoom': '15', 'left': '2048', 'top': '4096', 'width': '512', 'height': '256', **fields}

    def refused(self, form, kind='mbtiles', base='none'):
        with self.assertRaises(oi.OverlayError) as caught:
            oi.read_area(form, kind, base)
        return caught.exception.code

    def test_an_area_is_read_and_a_world_copy_brought_back(self):
        area = oi.read_area(self.form(left=str(2048 + 256 * 2 ** 15)), 'mbtiles', 'none')
        self.assertEqual(area, oi.Area(15, 2048, 4096, 512, 256))
        area = oi.read_area(self.form(zoom='15.3', left='-1000', width='999'), 'geotiff', 'none')
        self.assertEqual((area.zoom, area.left, area.width), (15.3, -1000, 999))
        bounds = area.bounds()
        self.assertTrue(-180 <= bounds['west'] < 180 and bounds['west'] < bounds['east'])

    def test_what_cannot_be_drawn_is_refused(self):
        self.assertEqual(self.refused(self.form(zoom='15.25'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(zoom='21'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(zoom='nan'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(left='12.5'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(width='0'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(top='-1'), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused(self.form(top=str(256 * 2 ** 15 - 100)), 'geotiff'), 'bad_area')
        self.assertEqual(self.refused({k: v for k, v in self.form().items() if k != 'top'}), 'bad_area')

    def test_mbtiles_is_drawn_on_its_tiles(self):
        self.assertEqual(self.refused(self.form(zoom='15.3')), 'bad_area')
        self.assertEqual(self.refused(self.form(left='2050')), 'bad_area')
        self.assertEqual(self.refused(self.form(width='500')), 'bad_area')

    def test_the_formats_limits(self):
        self.assertEqual(self.refused(self.form(width='10001'), 'geotiff'), 'too_large')
        oi.read_area(self.form(width='10000', height='10000'), 'geotiff', 'none')
        self.assertEqual(self.refused(self.form(width=str(256 * 41), height=str(256 * 40))), 'too_large')
        oi.read_area(self.form(width=str(256 * 40), height=str(256 * 40)), 'mbtiles', 'none')

    def test_the_topo_map_goes_no_finer_than_its_own_tiles(self):
        self.assertEqual(self.refused(self.form(zoom='17'), 'mbtiles', 'usgs-topo'), 'too_fine')
        self.assertEqual(self.refused(self.form(zoom='16.1'), 'geotiff', 'usgs-topo'), 'too_fine')
        oi.read_area(self.form(zoom='16'), 'mbtiles', 'usgs-topo')
        oi.read_area(self.form(zoom='17'), 'mbtiles', 'none')


class ReadingThePieces(unittest.TestCase):
    AREA = oi.Area(15, 0, 0, 2048 + 256, 256)  # two pieces across, one down

    def test_exactly_the_pieces_the_area_needs(self):
        self.assertEqual(set(oi.read_pieces({'piece-0-0': b'a', 'piece-1-0': b'b'}, self.AREA)), {(0, 0), (1, 0)})
        for files in ({'piece-0-0': b'a'}, {'piece-0-0': b'a', 'piece-1-0': b'b', 'piece-2-0': b'c'},
                      {'piece-0-0': b'a', 'piece-1-0': b'b', 'other': b'c'}):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.read_pieces(files, self.AREA)
            self.assertEqual(caught.exception.code, 'bad_pieces', files)

    def test_a_piece_is_refused_before_it_is_decoded_unless_it_is_a_piece_sized_png(self):
        wrong = _png(Image.new('RGBA', (oi.PIECE, oi.PIECE + 1)))
        with mock.patch.object(Image.Image, 'load', side_effect=AssertionError('decoded')):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.open_piece(wrong)
        self.assertEqual(caught.exception.code, 'bad_image')
        jpeg = io.BytesIO()
        Image.new('RGB', (oi.PIECE, oi.PIECE)).save(jpeg, 'JPEG')
        for data in (b'not an image', jpeg.getvalue()):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.open_piece(data)
            self.assertEqual(caught.exception.code, 'bad_image')


# ------------------------------------------------------------------------------------------------------- MBTiles


class MBTiles(unittest.TestCase):
    def build(self, area, marks, base='none', topo=None):
        db, close = _open_mbtiles(oi.build('mbtiles', area, _pieces(area, marks), base, 'OP DK', topo=topo))
        self.addCleanup(close)
        return db

    def test_metadata_says_what_ForeFlight_needs(self):
        area = _area(15, 768, 512, snap=256)
        db = self.build(area, [_mark(area, LAT, LON)])
        meta = dict(db.execute('SELECT name, value FROM metadata'))
        bounds = area.bounds()
        self.assertEqual((meta['name'], meta['format'], meta['type'], meta['scheme']), ('OP DK', 'png', 'overlay', 'tms'))
        self.assertEqual(meta['bounds'], f"{bounds['west']:.6f},{bounds['south']:.6f},"
                                         f"{bounds['east']:.6f},{bounds['north']:.6f}")
        # Three tiles across at 15: down to 13, where the whole area is in a tile or two.
        self.assertEqual((meta['minzoom'], meta['maxzoom']), ('13', '15'))
        zooms = [z for (z,) in db.execute('SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level')]
        self.assertEqual(zooms, [13, 14, 15])

    def test_what_is_drawn_is_where_it_was_drawn_at_every_zoom_and_nothing_else_is_kept(self):
        # Across two pieces, so the pieces are put side by side as they lie.
        area = _area(16, 2048 + 512, 512, snap=256)
        east = area.lon(area.left + 2048 + 200)
        db = self.build(area, [_mark(area, LAT, LON, half=24), _mark(area, LAT, east, half=24, colour=BLUE)])
        for zoom in (16, 15, 14):
            for lon, colour in ((LON, RED), (east, BLUE)):
                tile, pixel = _tile_at(db, zoom, LAT, lon)
                self.assertIsNotNone(tile, (zoom, lon))
                self.assertEqual(tile.convert('RGBA').getpixel(pixel), colour, (zoom, lon))
        for (data,) in db.execute('SELECT tile_data FROM tiles'):
            self.assertIsNotNone(Image.open(io.BytesIO(data)).getchannel('A').getbbox())
        # Two squares touch at most eight of the area's twenty tiles at 16; the empty ones are left out.
        self.assertLessEqual(db.execute('SELECT COUNT(*) FROM tiles WHERE zoom_level = 16').fetchone()[0], 8)

    def test_edges_keep_their_colour(self):
        # Blended premultiplied: a half-covered pixel is the square's red, less opaque, never darkened toward the
        # transparent black round it (within 8-bit rounding, which grows as the alpha falls).
        area = _area(15, 512, 512, snap=256)
        # Its edges on odd pixels, so halving leaves pixels half covered.
        db = self.build(area, [(area.left + 100, area.top + 202, 7, RED)])
        seen = 0
        for (data,) in db.execute('SELECT tile_data FROM tiles WHERE zoom_level < 15'):
            pixels = np.asarray(Image.open(io.BytesIO(data)).convert('RGBA')).reshape(-1, 4).astype(int)
            edge = pixels[(pixels[:, 3] >= 32) & (pixels[:, 3] < 255)]
            seen += len(edge)
            self.assertLessEqual(int(np.abs(edge[:, 0] - RED[0]).max(initial=0)), 16)
            self.assertLessEqual(int(np.abs(edge[:, 1] - RED[1]).max(initial=0)), 16)
        self.assertGreater(seen, 0)

    def test_an_area_across_the_antimeridian_keeps_its_columns_in_the_world(self):
        world = 256 * 2 ** 12
        area = oi.Area(12, world - 512, 1536 * 2, 1024, 256)
        db = self.build(area, [(world - 128, area.top + 128, 40, RED), (world + 128, area.top + 128, 40, BLUE)])
        columns = sorted(c for (c,) in db.execute('SELECT tile_column FROM tiles WHERE zoom_level = 12'))
        self.assertEqual(columns, [0, 4095])

    def test_with_the_topo_map_every_tile_is_the_map_and_what_is_drawn_is_laid_on_it(self):
        area = _area(15, 768, 512, snap=256)
        lat, lon = area.lat(area.top + 384), area.lon(area.left + 384)
        topo = FakeTopo()
        db = self.build(area, [(area.left + 384, area.top + 384, 24, RED)], base='usgs-topo', topo=topo)
        self.assertEqual(dict(db.execute('SELECT name, value FROM metadata'))['format'], 'jpg')
        self.assertEqual(db.execute('SELECT COUNT(*) FROM tiles WHERE zoom_level = 15').fetchone()[0], 6)
        # Each zoom's own topo tiles, not the finest shrunk.
        self.assertEqual({z for z, _, _ in topo.fetched}, {13, 14, 15})
        for zoom in (15, 14, 13):
            tile, pixel = _tile_at(db, zoom, lat, lon)
            self.assertEqual(tile.format, 'JPEG')
            r, g, b = tile.convert('RGB').getpixel(pixel)
            self.assertLess(abs(r - RED[0]) + abs(g - RED[1]) + abs(b - RED[2]), 30, zoom)
        # Where nothing is drawn, USGS's tile as it came.
        corner = (area.left // 256, area.top // 256)
        found = db.execute('SELECT tile_data FROM tiles WHERE zoom_level = 15 AND tile_column = ? AND tile_row = ?',
                           (corner[0], 2 ** 15 - 1 - corner[1])).fetchone()[0]
        self.assertEqual(bytes(found), topo.jpeg(15, *corner))

    def test_nothing_drawn_is_refused_without_a_base_map(self):
        area = _area(15, 512, 256, snap=256)
        with self.assertRaises(oi.OverlayError) as caught:
            oi.build('mbtiles', area, _pieces(area), 'none', 'X')
        self.assertEqual(caught.exception.code, 'empty')


# ------------------------------------------------------------------------------------------------------- GeoTIFF


class GeoTIFF(unittest.TestCase):
    def open(self, area, marks, base='none', topo=None):
        import rasterio

        path = oi.build('geotiff', area, _pieces(area, marks), base, 'OP DK', topo=topo)
        self.addCleanup(os.unlink, path)
        tiff = rasterio.open(path)
        self.addCleanup(tiff.close)
        return tiff

    def test_it_is_in_latitude_and_longitude_and_lies_where_the_area_does(self):
        from rasterio.enums import ColorInterp

        area = _area(15.3, 1500, 900)
        tiff = self.open(area, [_mark(area, LAT, LON)])
        self.assertEqual(tiff.crs.to_epsg(), 4326)
        self.assertEqual((tiff.width, tiff.height, tiff.count), (1500, 900, 4))
        self.assertEqual(tiff.colorinterp[3], ColorInterp.alpha)
        bounds = area.bounds()
        for got, want in zip(tiff.bounds, (bounds['west'], bounds['south'], bounds['east'], bounds['north'])):
            self.assertAlmostEqual(got, want, places=9)
        self.assertTrue(-84.5 < bounds['west'] < LON < bounds['east'] < -84.2)
        self.assertTrue(34.5 < bounds['south'] < LAT < bounds['north'] < 34.8)
        pixels = tiff.read()
        row, col = tiff.index(LON, LAT)
        self.assertEqual(tuple(int(v) for v in pixels[:, row, col]), RED)
        self.assertEqual(int(pixels[3, 0, 0]), 0)
        self.assertIn('OP DK', tiff.tags()['TIFFTAG_IMAGEDESCRIPTION'])

    def test_its_rows_are_moved_from_mercator_to_latitude(self):
        # A tall area, where a Mercator row and a row of latitude part by many pixels in the middle.
        area = _area(10, 400, 3000, lat=40)
        marks = [_mark(area, lat, -84.38 + 0.1, half=3) for lat in (38.5, 40, 41.5)]
        tiff = self.open(area, marks)
        pixels = tiff.read()
        moved = []
        for lat in (38.5, 40, 41.5):
            row, col = tiff.index(-84.28, lat)
            self.assertEqual(tuple(int(v) for v in pixels[:, row, col]), RED, lat)
            moved.append(abs(row - (_global(10, lat, 0)[1] - area.top)))
        self.assertGreater(max(moved), 10)

    def test_with_the_topo_map_it_is_opaque_and_what_is_drawn_is_on_it(self):
        area = _area(15.3, 1200, 700)
        topo = FakeTopo()
        tiff = self.open(area, [_mark(area, LAT, LON)], base='usgs-topo', topo=topo)
        self.assertEqual(tiff.count, 3)
        self.assertEqual({z for z, _, _ in topo.fetched}, {16})
        pixels = tiff.read()
        row, col = tiff.index(LON, LAT)
        self.assertEqual(tuple(int(v) for v in pixels[:, row, col]), RED[:3])
        for value, want in zip(pixels[:, 5, 5], FakeTopo.colour(16)):
            self.assertLessEqual(abs(int(value) - want), 1)

    def test_the_base_is_fetched_a_zoom_coarser_where_its_tiles_would_be_too_many(self):
        area = _area(15.3, 9000, 9000)
        self.assertEqual(oi._base_zoom(area), 15)
        small = _area(15.3, 2000, 2000)
        self.assertEqual(oi._base_zoom(small), 16)
        self.assertLessEqual(len(oi._base_keys(area, 15, 0, area.height)), oi.BASE_TILE_BUDGET)

    def test_nothing_drawn_is_refused_without_a_base_map_and_leaves_no_file(self):
        area = _area(14, 300, 200)
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(oi.tempfile, 'tempdir', folder):
            with self.assertRaises(oi.OverlayError) as caught:
                oi.build('geotiff', area, _pieces(area), 'none', 'X')
            self.assertEqual(os.listdir(folder), [])
        self.assertEqual(caught.exception.code, 'empty')


# ------------------------------------------------------------------------------------------------------- USGS


def _jpeg(colour, halves=None):
    image = Image.new('RGB', (256, 256), colour)
    if halves:
        image.paste(halves, (128, 0, 256, 256))
    out = io.BytesIO()
    image.save(out, 'JPEG', quality=95)
    return out.getvalue()


class FakeSession:
    def __init__(self, answer):
        self.answer = answer
        self.asked = []

    def get(self, url, timeout, allow_redirects):
        self.asked.append(url)
        z, y, x = (int(v) for v in url.rsplit('/', 3)[1:])
        answer = self.answer(z, x, y)
        if isinstance(answer, Exception):
            raise answer
        status, content = answer
        return SimpleNamespace(status_code=status, content=content)


class UsgsTopo(unittest.TestCase):
    def test_a_tile_over_water_is_drawn_from_its_parent(self):
        # USGS has no level-16 tile over open water, and a level-15 tile there: its left half blue, its right green.
        session = FakeSession(lambda z, x, y: (404, b'') if z == 16 else (200, _jpeg((20, 40, 200), (20, 200, 40))))
        topo = usgs_topo.UsgsTopo(session=session, sleep=lambda s: None)
        topo.fetch([(16, 100, 50), (16, 101, 50)])
        self.assertEqual(sorted(session.asked), sorted([
            usgs_topo.URL.format(z=16, x=100, y=50), usgs_topo.URL.format(z=16, x=101, y=50),
            usgs_topo.URL.format(z=15, x=50, y=25)]))
        left, right = topo.tile(16, 100, 50), topo.tile(16, 101, 50)
        self.assertLess(abs(left.getpixel((128, 128))[2] - 200), 10)
        self.assertLess(abs(right.getpixel((128, 128))[1] - 200), 10)
        self.assertIsNone(topo.jpeg(16, 100, 50))

    def test_a_tile_it_has_is_kept_as_it_came(self):
        data = _jpeg((1, 2, 3))
        topo = usgs_topo.UsgsTopo(session=FakeSession(lambda z, x, y: (200, data)), sleep=lambda s: None)
        topo.fetch([(14, 5, 6)])
        self.assertEqual(topo.jpeg(14, 5, 6), data)
        self.assertEqual(topo.tile(14, 5, 6).size, (256, 256))

    def test_a_failure_is_tried_again_and_then_refused_in_words(self):
        tries = []

        def flaky(z, x, y):
            tries.append(1)
            return requests.ConnectionError() if len(tries) < 3 else (200, _jpeg((9, 9, 9)))
        topo = usgs_topo.UsgsTopo(session=FakeSession(flaky), sleep=lambda s: None)
        topo.fetch([(10, 1, 1)])
        self.assertEqual(len(tries), 3)

        down = usgs_topo.UsgsTopo(session=FakeSession(lambda z, x, y: (503, b'')), sleep=lambda s: None)
        with self.assertRaises(usgs_topo.TopoError) as caught:
            down.fetch([(10, 1, 1)])
        self.assertEqual(caught.exception.code, 'base_unavailable')

    def test_a_place_it_does_not_cover_is_refused(self):
        topo = usgs_topo.UsgsTopo(session=FakeSession(lambda z, x, y: (404, b'')), sleep=lambda s: None)
        with self.assertRaises(usgs_topo.TopoError) as caught:
            topo.fetch([(16, 9, 9)])
        self.assertEqual(caught.exception.code, 'base_not_covered')


# ------------------------------------------------------------------------------------------------------- route


class Route(NativeAuthCase):
    extra_blueprints = (overlay_image_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}
        self.area = _area(15, 512, 512, snap=256)

    def post(self, fields=None, head=None, pieces=None):
        area = self.area
        form = {'format': 'mbtiles', 'base': 'none', 'name': 'OP DK / night', 'zoom': str(area.zoom),
                'left': str(area.left), 'top': str(area.top), 'width': str(area.width), 'height': str(area.height),
                **(fields or {})}
        for (i, j), data in (pieces if pieces is not None else _pieces(area, [_mark(area, LAT, LON)])).items():
            form[f'piece-{i}-{j}'] = (io.BytesIO(data), f'{i}-{j}.png')
        return self.client.post('/api/overlay-image', headers=self.head if head is None else head,
                                data=form, content_type='multipart/form-data')

    def test_mbtiles_and_geotiff_are_answered_as_files_named_for_the_layer_and_nothing_is_kept(self):
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(oi.tempfile, 'tempdir', folder):
            for kind, extension in (('mbtiles', 'mbtiles'), ('geotiff', 'tif')):
                response = self.post({'format': kind})
                self.assertEqual(response.status_code, 200, response.data[:300])
                self.assertEqual(response.headers['Cache-Control'], 'no-store')
                self.assertIn(f'filename="OP DK night.{extension}"', response.headers['Content-Disposition'])
                self.assertGreater(len(response.data), 100)
                response.close()
            self.assertEqual(os.listdir(folder), [])

    def test_the_topo_map_is_fetched_under_it(self):
        with mock.patch.object(oi, 'UsgsTopo', FakeTopo):
            response = self.post({'base': 'usgs-topo'})
        self.assertEqual(response.status_code, 200, response.data[:300])

    def test_signed_out_is_refused(self):
        self.assertEqual(self.post(head={}).status_code, 401)

    def test_a_refusal_says_why(self):
        for fields, pieces, status, code in (
                ({'format': 'png'}, None, 400, 'bad_format'),
                ({'base': 'satellite'}, None, 400, 'bad_base'),
                ({'zoom': ''}, None, 400, 'bad_area'),
                ({'width': str(256 * 41), 'height': str(256 * 40)}, None, 413, 'too_large'),
                ({}, {}, 400, 'bad_pieces'),
                ({}, _pieces(self.area), 400, 'empty'),
        ):
            response = self.post(fields, pieces=pieces)
            self.assertEqual((response.status_code, response.get_json()['code']), (status, code), fields)

    def test_usgs_not_answering_is_a_bad_gateway(self):
        class Down(FakeTopo):
            def fetch(self, keys):
                raise usgs_topo.TopoError('base_unavailable', 'The USGS topo map could not be fetched.')
        with mock.patch.object(oi, 'UsgsTopo', Down):
            response = self.post({'base': 'usgs-topo'})
        self.assertEqual((response.status_code, response.get_json()['code']), (502, 'base_unavailable'))

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
