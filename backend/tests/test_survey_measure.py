"""The LZ survey's snapshot and its comparison with the field survey (docs/LZ_SURVEYS.md §2, §3.3).

The terrain is a synthetic GeoTIFF where LZ DONOVAN is (a plane tilted a known amount), so every number has an answer worked
out by hand: no SAM, no network, no DEM download.
"""

import json
import math
import os
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from rasterio.transform import from_origin  # noqa: E402

from survey import compare, geometry, measure  # noqa: E402
from survey.fields import FIELD_KEYS, clean_field  # noqa: E402
from terrain_provider import LocalRasterCatalog  # noqa: E402

CONTRACTS = BACKEND_DIR.parent / 'contracts' / 'fixtures'
DONOVAN = json.loads((CONTRACTS / 'surveys' / 'donovan.json').read_text(encoding='utf-8'))

# LZ DONOVAN's touchdown point: the book's grid (16S GD 6341 3199), its 10 m square's centre.
TARGET = (34.59601685, -84.12779028)
CELL_DEG = 1 / 10800                      # about 10 m north-south
WEST, NORTH, SIZE = -84.1420, 34.6100, 300
BASE_M = 600.0
EAST_M_PER_DEG = 111320.0 * math.cos(math.radians(TARGET[0]))


def local(east_m, north_m):
    """A point ``east_m`` and ``north_m`` from the target, as [lat, lon]."""
    return [TARGET[0] + north_m / 111320.0, TARGET[1] + east_m / EAST_M_PER_DEG]


def square(half_m=50.0):
    return [local(-half_m, half_m), local(half_m, half_m), local(half_m, -half_m), local(-half_m, -half_m)]


def diagram(boundary=None, target=TARGET):
    doc = {'schemaVersion': 2, 'id': 'survey-test', 'analysis': {'detectedLZ': boundary, 'customLZ': None}}
    if target is not None:
        doc['target'] = {'lat': target[0], 'lon': target[1], 'mgrs': ''}
    return doc


class Terrain:
    """A plane over DONOVAN rising east at ``east_deg`` and north at ``north_deg``, as a local DEM (TERRAIN_SOURCE=local)."""

    def __init__(self, east_deg=5.0, north_deg=0.0, datum='NAVD88', empty=False):
        self.east_deg, self.north_deg, self.datum, self.empty = east_deg, north_deg, datum, empty

    def height_m(self, lat, lon):
        col = (lon - WEST) / CELL_DEG - 0.5
        row = (NORTH - lat) / CELL_DEG - 0.5
        return (BASE_M + math.tan(math.radians(self.east_deg)) * col * CELL_DEG * EAST_M_PER_DEG
                - math.tan(math.radians(self.north_deg)) * row * CELL_DEG * 111320.0)

    def __enter__(self):
        self.directory = tempfile.TemporaryDirectory()
        if not self.empty:
            rows, cols = np.mgrid[0:SIZE, 0:SIZE].astype(np.float64)
            dem = (BASE_M + math.tan(math.radians(self.east_deg)) * cols * CELL_DEG * EAST_M_PER_DEG
                   - math.tan(math.radians(self.north_deg)) * rows * CELL_DEG * 111320.0).astype(np.float32)
            with rasterio.open(Path(self.directory.name) / 'donovan.tif', 'w', driver='GTiff', height=SIZE, width=SIZE, count=1,
                               dtype='float32', crs='EPSG:4326', transform=from_origin(WEST, NORTH, CELL_DEG, CELL_DEG),
                               nodata=-9999) as out:
                out.write(dem, 1)
                out.update_tags(VERT_DATUM=self.datum)
        self.patches = [
            patch.dict(os.environ, {'TERRAIN_DATA_DIR': self.directory.name, 'TERRAIN_SOURCE': 'local'}, clear=False),
            patch('terrain_provider.LOCAL_CATALOG', LocalRasterCatalog()),
        ]
        for p in self.patches:
            p.start()
        return self

    def __exit__(self, *exc):
        for p in reversed(self.patches):
            p.stop()
        self.directory.cleanup()


class AreaAndFitTests(unittest.TestCase):
    """The web's lzAreaAndCapacity, ported, against every case the web recorded."""

    def test_every_area_case_of_the_web(self):
        profiles = {p['key']: p['profile'] for p in json.loads((CONTRACTS / 'planning' / 'aircraft.json').read_text())['profiles']}
        cases = json.loads((CONTRACTS / 'planning' / 'summary.json').read_text())['area']
        self.assertGreater(len(cases), 40)
        for case in cases:
            with self.subTest(case=case['name'], profile=case['profile']):
                profile = profiles[case['profile']] if case['profile'] else None
                self.assertEqual(geometry.area_and_capacity(case['polygon'], profile), case['expected'])

    def test_the_fallback_is_the_web_s_uh60l(self):
        fallback = json.loads((CONTRACTS / 'planning' / 'aircraft.json').read_text())['fallbackProfile']
        for key in ('designation', 'rotor_diameter_m', 'rotor_tip_clearance_m'):
            self.assertEqual(geometry.UH60L[key], fallback[key])


class RectangleTests(unittest.TestCase):
    def rectangle(self, length_m, width_m, axis_deg):
        ux, uy = math.sin(math.radians(axis_deg)), math.cos(math.radians(axis_deg))
        corners = []
        for a, b in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            x = a * length_m / 2 * ux + b * width_m / 2 * uy
            y = a * length_m / 2 * uy - b * width_m / 2 * ux
            corners.append(local(x, y))
        return corners

    def test_a_turned_rectangle_is_measured_along_its_long_side(self):
        for axis in (0.0, 30.0, 90.0, 135.0):
            with self.subTest(axis=axis):
                rect = geometry.smallest_rectangle(self.rectangle(200, 80, axis))
                self.assertAlmostEqual(rect['lengthM'], 200, delta=0.5)
                self.assertAlmostEqual(rect['widthM'], 80, delta=0.5)
                self.assertAlmostEqual(rect['axisDeg'], axis % 180, delta=0.2)

    def test_an_axis_has_two_ends_so_it_is_under_180(self):
        rect = geometry.smallest_rectangle(self.rectangle(200, 80, 210))
        self.assertAlmostEqual(rect['axisDeg'], 30, delta=0.2)

    def test_a_shape_with_a_corner_inside_is_measured_by_its_outline(self):
        points = self.rectangle(100, 100, 0)
        points.insert(2, local(0, 0))                    # a notch: the hull does not see it
        rect = geometry.smallest_rectangle(points)
        self.assertAlmostEqual(rect['lengthM'], 100, delta=0.5)

    def test_no_area_is_no_rectangle(self):
        self.assertIsNone(geometry.smallest_rectangle([local(0, 0), local(10, 0), local(20, 0)]))
        self.assertIsNone(geometry.smallest_rectangle([local(0, 0), local(10, 0)]))
        self.assertIsNone(geometry.smallest_rectangle(None))


class WordsTests(unittest.TestCase):
    def test_grid_and_lat_long_as_the_book_writes_them(self):
        lat, lon = 34.592983333333336, -84.12336666666667
        self.assertEqual(measure.lat_long_text(lat, lon), 'N34 35.579 W084 07.402')
        self.assertEqual(measure.lat_long_text(-0.5, 5.25), 'S00 30.000 E005 15.000')
        self.assertEqual(measure.lat_long_text(34.99999999, -84.0), 'N35 00.000 W084 00.000')   # 59.9999' carries
        self.assertEqual(measure.grid_text(lat, lon), '16S GD 63830 31670')

    def test_headings_as_typed(self):
        for text, expected in (('360', 0.0), ('045°', 45.0), ('270 deg', 270.0), (' 90', 90.0), ('', None), ('north', None),
                               ('400', None), (None, None)):
            with self.subTest(text=text):
                self.assertEqual(measure.heading_of(text), expected)

    def test_the_boundary_is_the_analysis_s_else_the_one_drawn(self):
        drawn = square(30)
        self.assertEqual(measure.boundary_of({'analysis': {'detectedLZ': square(), 'customLZ': drawn}}), square())
        self.assertEqual(measure.boundary_of({'analysis': {'detectedLZ': None, 'customLZ': drawn}}), drawn)
        self.assertIsNone(measure.boundary_of({'analysis': {'detectedLZ': [[1, 2], [3, 4]]}}))
        self.assertIsNone(measure.boundary_of({'analysis': {'detectedLZ': [[1, 'x'], [3, 4], [5, 6]]}}))
        closed = square() + [square()[0]]
        self.assertEqual(measure.boundary_of({'analysis': {'detectedLZ': closed}}), square())


class MeasureTests(unittest.TestCase):
    NOW = datetime(2026, 10, 26, 12, 0, 0)

    def snapshot(self, field=None, **kwargs):
        return measure.measure(diagram(**kwargs), field if field is not None else {'approachDeg': '360'}, 'Admin', self.NOW)

    def test_the_elevation_is_the_target_s_height_in_the_slope_s_dem(self):
        with Terrain() as terrain:
            snap = self.snapshot(boundary=square())
        expected_m = terrain.height_m(*TARGET)
        self.assertAlmostEqual(snap['elevation']['m'], expected_m, delta=0.3)
        self.assertEqual(snap['elevation']['ft'], int(round(snap['elevation']['m'] * 3.28084)))
        self.assertEqual(snap['elevation']['dem'], ['donovan.tif'])
        self.assertEqual(snap['elevation']['verticalDatum'], 'NAVD88')
        self.assertEqual(snap['elevation']['source'], 'local_highres_cog')
        self.assertGreater(snap['elevation']['resolutionM'], 0)

    def test_where_and_who(self):
        with Terrain():
            snap = self.snapshot(boundary=square())
        self.assertEqual(snap['grid'], measure.grid_text(*TARGET))
        self.assertTrue(snap['grid'].startswith('16S GD 6341'))
        self.assertEqual(snap['latLong'], measure.lat_long_text(*TARGET))
        self.assertEqual((snap['measuredAt'], snap['measuredBy'], snap['version']), ('2026-10-26T12:00:00Z', 'Admin', 2))

    def test_size_area_and_fit(self):
        with Terrain():
            snap = self.snapshot(boundary=square(50))
        self.assertAlmostEqual(snap['size']['lengthFt'], 328, delta=2)
        self.assertAlmostEqual(snap['size']['widthFt'], 328, delta=2)
        self.assertEqual(snap['size']['areaSqFt'], geometry.area_and_capacity(square(50))['area'])
        self.assertAlmostEqual(snap['size']['areaAcres'], 10000 * 10.7639 / 43560, delta=0.05)
        self.assertEqual(snap['fit'], {'aircraft': 'UH-60L', 'count': geometry.capacity_for_area(geometry.polygon_area_sq_ft(square(50)))})

    def test_the_slope_is_judged_along_the_best_approach(self):
        # Ground rising east at 5°: flown north it is all cross slope; flown east, all nose up.
        with Terrain(east_deg=5.0):
            north = self.snapshot(boundary=square())
            east = self.snapshot(field={'approachDeg': '090'}, boundary=square())
        slope = north['slope']
        self.assertEqual(slope['headingDeg'], 0.0)
        # The DEM is warped to UTM, whose north is turned ~1.6° from true here (AGENTS.md §15), so along takes a little of the tilt;
        # and GDAL's warper follows the projection piecewise, which puts a few tenths of a degree of ripple on a perfect plane.
        self.assertAlmostEqual(slope['cross']['mean'], 5.0, delta=0.1)
        self.assertAlmostEqual(slope['cross']['p95'], 5.0, delta=0.4)
        self.assertLess(slope['noseUp']['p95'], 0.4)
        self.assertLess(slope['noseDown']['p95'], 0.4)
        self.assertAlmostEqual(slope['overall']['p95'], 5.0, delta=0.4)
        self.assertEqual((slope['call'], slope['exceeded']), ('within', []))
        self.assertIn('Within limits at 000°', slope['words'])
        self.assertAlmostEqual(east['slope']['noseUp']['p95'], 5.0, delta=0.4)
        self.assertLess(east['slope']['cross']['p95'], 0.4)
        self.assertEqual(slope['limits'], {'noseUp': 15.0, 'noseDown': 6.0, 'cross': 15.0})
        self.assertGreater(slope['sampleCount'], 50)

    def test_a_limit_exceeded_is_named(self):
        with Terrain(east_deg=8.0):
            downhill = self.snapshot(field={'approachDeg': '270'}, boundary=square())      # 8° nose down: over the 6° limit
            across = self.snapshot(field={'approachDeg': '360'}, boundary=square())
        self.assertEqual((downhill['slope']['call'], downhill['slope']['exceeded']), ('exceeded', ['noseDown']))
        self.assertAlmostEqual(downhill['slope']['noseDown']['p95'], 8.0, delta=0.4)
        self.assertIn(f"nose down {downhill['slope']['noseDown']['p95']:.1f}°, limit 6°", downhill['slope']['words'])
        self.assertEqual(across['slope']['call'], 'within')
        with Terrain(east_deg=18.0):
            steep = self.snapshot(field={'approachDeg': '360'}, boundary=square())
        self.assertEqual(steep['slope']['exceeded'], ['cross'])

    def test_the_95th_percentile_not_the_steepest_cell(self):
        # A plane with one steep spike: the steepest cell is far over, the 95th percentile is the plane's.
        with Terrain(east_deg=3.0):
            path = Path(os.environ['TERRAIN_DATA_DIR']) / 'donovan.tif'
            with rasterio.open(path, 'r+') as dem:
                band = dem.read(1)
                row = int(round((NORTH - TARGET[0]) / CELL_DEG - 0.5))
                col = int(round((TARGET[1] - WEST) / CELL_DEG - 0.5))
                band[row, col] += 80.0
                dem.write(band, 1)
            snap = self.snapshot(boundary=square(120))
        self.assertGreater(snap['slope']['cross']['max'], 15.0)
        self.assertAlmostEqual(snap['slope']['cross']['p95'], 3.0, delta=0.5)
        self.assertEqual(snap['slope']['call'], 'within')

    def test_with_no_best_approach_the_slope_is_not_judged(self):
        with Terrain():
            snap = self.snapshot(field={}, boundary=square())
        self.assertEqual(snap['slope']['call'], 'heading_required')
        self.assertIsNone(snap['slope']['cross'])
        self.assertIsNotNone(snap['slope']['overall'])
        self.assertIn('The best approach is not set, so the slope is not judged at it.', snap['problems'])

    def test_the_slope_map_contours_and_profiles_are_kept(self):
        with Terrain():
            snap = self.snapshot(boundary=square())
        terrain = snap['terrain']
        self.assertTrue(terrain['overlay'].startswith('data:image/png;base64,'))
        self.assertEqual(len(terrain['bounds']), 2)
        self.assertIn('lines', terrain['contours'])
        self.assertTrue(terrain['profiles']['lines'])
        self.assertNotIn('byHeading', terrain)

    def test_with_no_boundary_only_the_point_is_measured(self):
        with Terrain():
            snap = self.snapshot(boundary=None)
        self.assertIsNotNone(snap['elevation'])
        self.assertIsNone(snap['size'])
        self.assertIsNone(snap['slope'])
        self.assertIsNone(snap['terrain'])
        self.assertTrue(any('no boundary' in p for p in snap['problems']))

    def test_with_no_terrain_nothing_is_made_up(self):
        with Terrain(empty=True):
            snap = self.snapshot(boundary=square())
        self.assertIsNone(snap['elevation'])
        self.assertIsNone(snap['slope'])
        self.assertIsNone(snap['terrain'])
        self.assertIsNotNone(snap['size'])         # the boundary's own geometry needs no terrain
        self.assertTrue(any('No terrain covers' in p for p in snap['problems']))

    def test_with_no_target_there_is_nothing_to_measure(self):
        with self.assertRaises(measure.CannotMeasure) as refused:
            measure.measure(diagram(boundary=square(), target=None), {}, 'Admin')
        self.assertEqual(refused.exception.code, 'no_target')

    def test_a_boundary_no_lz_could_have_is_not_measured(self):
        # A slip of the mouse, or a county: it would read (or fetch) terrain over all of it. Refused before any is loaded.
        with patch('terrain_provider.load_best_terrain') as load:
            with self.assertRaises(measure.CannotMeasure) as refused:
                measure.measure(diagram(boundary=square(1600)), {'approachDeg': '360'}, 'Admin')
            self.assertEqual(refused.exception.code, 'too_large_to_measure')
            far = diagram(boundary=square(50), target=tuple(local(0, 3500)))       # a target far from its boundary
            with self.assertRaises(measure.CannotMeasure):
                measure.measure(far, {}, 'Admin')
        load.assert_not_called()

    def test_a_new_snapshot_says_what_it_changes(self):
        with Terrain(east_deg=5.0):
            first = self.snapshot(boundary=square(50))
            again = self.snapshot(boundary=square(50))
            wider = self.snapshot(boundary=square(80))
        self.assertEqual(measure.differences(first, again), [])
        labels = {change['label'] for change in measure.differences(first, wider)}
        self.assertIn('Length (ft)', labels)
        self.assertIn('Area (acres)', labels)
        self.assertNotIn('Grid', labels)
        everything = measure.differences(None, first)
        self.assertTrue(all(change['before'] is None for change in everything))
        self.assertIn('DEM', {change['label'] for change in everything})


class FieldTests(unittest.TestCase):
    def test_donovan_s_fields_are_the_survey_s(self):
        self.assertEqual(set(DONOVAN['field']), set(FIELD_KEYS))
        self.assertEqual(clean_field(DONOVAN['field']), DONOVAN['field'])

    def test_a_value_the_survey_does_not_have_is_refused(self):
        for bad in ({'colour': 'red'}, {'grid': 12}, {'grid': 'x' * 2001}, ['grid']):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                clean_field(bad)
        self.assertEqual(clean_field({'warning': None}), {'warning': ''})


class CompareTests(unittest.TestCase):
    def snapshot(self, **changes):
        snap = {
            'target': {'lat': TARGET[0], 'lon': TARGET[1]},
            'grid': measure.grid_text(*TARGET),
            'latLong': measure.lat_long_text(*TARGET),
            'elevation': {'ft': 2093},
            'size': {'lengthFt': 600, 'widthFt': 400},
            'slope': {'noseUp': {'p95': 1.0}, 'noseDown': {'p95': 0.5}, 'cross': {'p95': 4.0}},
        }
        snap.update(changes)
        return snap

    def rows(self, field=None, snapshot=None, overrides=None):
        return {row['key']: row for row in compare.compare(DONOVAN['field'] if field is None else field,
                                                           self.snapshot() if snapshot is None else snapshot, overrides or {})}

    def test_donovan_s_grid_agrees_and_its_lat_long_does_not(self):
        rows = self.rows()
        self.assertFalse(rows['grid']['disagrees'])
        self.assertTrue(rows['latLong']['disagrees'])
        metres = float(rows['latLong']['apart'].split()[0].replace(',', ''))
        self.assertTrue(DONOVAN['expected']['gridToLatLongMetres']['min'] <= metres <= DONOVAN['expected']['gridToLatLongMetres']['max'])

    def test_elevation_slope_and_size(self):
        rows = self.rows()
        self.assertEqual((rows['elevation']['apart'], rows['elevation']['disagrees']), ('243 ft apart', True))
        # "-10° roll left side low" is a cross slope of 10°.
        self.assertEqual(rows['slope']['computed'], 'cross 4.0° (95th percentile)')
        self.assertEqual((rows['slope']['apart'], rows['slope']['disagrees']), ('6.0° apart', True))
        # No size in the book: nothing to disagree with, and the computed one is printed.
        self.assertEqual((rows['size']['disagrees'], rows['size']['printed']), (False, 'computed'))
        close = self.rows(field={**DONOVAN['field'], 'elevationFt': '2080', 'size': '620 x 390 ft', 'slope': '4 deg cross'})
        self.assertFalse(close['elevation']['disagrees'])
        self.assertFalse(close['size']['disagrees'])
        self.assertFalse(close['slope']['disagrees'])
        metres = self.rows(field={**DONOVAN['field'], 'size': '183 m x 122 m'})
        self.assertFalse(metres['size']['disagrees'])

    def test_the_computed_value_is_printed_where_both_exist(self):
        rows = self.rows()
        self.assertTrue(all(row['printed'] == 'computed' for row in rows.values() if row['computed']))
        nothing = self.rows(snapshot={})
        self.assertEqual(nothing['grid']['printed'], 'field')
        self.assertFalse(any(row['open'] for row in nothing.values()))

    def test_a_field_that_cannot_be_read_is_a_disagreement(self):
        rows = self.rows(field={**DONOVAN['field'], 'grid': '16S GD 634'})
        self.assertEqual((rows['grid']['apart'], rows['grid']['disagrees']), ('cannot be read', True))

    def test_an_override_settles_a_disagreement_until_a_value_changes(self):
        snap = self.snapshot()
        overrides = compare.clean_overrides({'elevation': {'choice': 'field', 'reason': 'Surveyed by GPS on the pad'}},
                                            {}, DONOVAN['field'], snap, 'Admin', datetime(2026, 10, 26))
        entry = overrides['elevation']
        self.assertEqual((entry['by'], entry['at'], entry['field'], entry['computed']), ('Admin', '2026-10-26T00:00:00Z', '1850', '2093 ft MSL'))
        rows = self.rows(snapshot=snap, overrides=overrides)
        self.assertEqual((rows['elevation']['open'], rows['elevation']['printed']), (False, 'field'))
        self.assertEqual(compare.open_count(DONOVAN['field'], snap, overrides), compare.open_count(DONOVAN['field'], snap, {}) - 1)
        # Measured again to another height: the choice was made on other values, and stands no longer.
        later = self.rows(snapshot=self.snapshot(elevation={'ft': 2101}), overrides=overrides)
        self.assertEqual((later['elevation']['overrideStands'], later['elevation']['open'], later['elevation']['printed']),
                         (False, True, 'computed'))

    def test_accepting_needs_no_reason_and_overriding_does(self):
        snap = self.snapshot()
        accepted = compare.clean_overrides({'latLong': {'choice': 'computed'}}, {}, DONOVAN['field'], snap, 'Admin')
        self.assertEqual(self.rows(overrides=accepted)['latLong']['open'], False)
        self.assertEqual(self.rows(overrides=accepted)['latLong']['printed'], 'computed')
        for bad in ({'latLong': {'choice': 'field', 'reason': '  '}}, {'latLong': {'choice': 'mine'}},
                    {'surface': {'choice': 'computed'}}, {'latLong': 'computed'}, []):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                compare.clean_overrides(bad, {}, DONOVAN['field'], snap, 'Admin')

    def test_an_unchanged_choice_keeps_who_made_it(self):
        snap = self.snapshot()
        first = compare.clean_overrides({'elevation': {'choice': 'field', 'reason': 'GPS'}}, {}, DONOVAN['field'], snap, 'Admin A',
                                        datetime(2026, 10, 1))
        sent_back = {'elevation': {**first['elevation']}}
        again = compare.clean_overrides(sent_back, first, DONOVAN['field'], snap, 'Admin B', datetime(2026, 10, 26))
        self.assertEqual(again, first)
        changed = compare.clean_overrides({'elevation': {'choice': 'field', 'reason': 'GPS, checked'}}, first, DONOVAN['field'], snap,
                                          'Admin B', datetime(2026, 10, 26))
        self.assertEqual(changed['elevation']['by'], 'Admin B')

    def test_lat_long_as_people_write_it(self):
        for text in ('N 34 35.579 W084 07.402', 'N34 35.579 W084 07.402', '34°35\'34.74"N 84°07\'24.12"W', '34.592983, -84.123367',
                     'W084 07.402 N34 35.579'):
            with self.subTest(text=text):
                lat, lon = compare.parse_lat_long(text)
                self.assertAlmostEqual(lat, 34.592983, places=5)
                self.assertAlmostEqual(lon, -84.123367, places=5)
        for text in ('', 'north', 'N34 61 W84', 'N34 35 N84 07', '1 2 3'):
            with self.subTest(text=text):
                self.assertIsNone(compare.parse_lat_long(text))


if __name__ == '__main__':
    unittest.main()
