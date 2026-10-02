"""Coordinate systems stated as WKT, as the downloaded GA_Statewide tiles do.

A LAS 1.4 tile states its CRS as compound WKT named "NAD83(2011) / Conus Albers
+ NAVD88 height". The area-of-interest code split CRS strings at "+", which
assumed "EPSG:6350+5703" and cut WKT apart mid-name: every build from the
downloaded collection crashed at "locating survey". The older test used a
made-up WKT that nothing ever parsed, so it could not notice.
"""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from pyproj import CRS

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lidar import aoi, collection  # noqa: E402
from test_lidar_collection import write_tile  # noqa: E402

# Real WKT, the form PDAL and the USGS tiles use: its name contains "+".
COMPOUND_WKT = CRS.from_user_input("EPSG:6350+5703").to_wkt("WKT1_GDAL")
ALBERS_WKT = CRS.from_user_input("EPSG:6350").to_wkt("WKT1_GDAL")
LZ = (34.783817, -84.08219)


class WktCrsTests(unittest.TestCase):
    def test_the_fixture_is_the_troublesome_kind(self):
        self.assertIn(" + ", COMPOUND_WKT.split(",")[0])

    def test_the_horizontal_part_comes_out_of_compound_wkt(self):
        self.assertEqual(aoi._horizontal(COMPOUND_WKT).to_epsg(), 6350)

    def test_short_compound_codes_still_work(self):
        self.assertEqual(aoi._horizontal("EPSG:6350+5703").to_epsg(), 6350)
        self.assertEqual(aoi._horizontal(aoi.WEB_MERCATOR_NAVD88).to_epsg(), 3857)

    def test_a_box_in_wkt_terms_matches_the_same_box_by_code(self):
        by_wkt = aoi.bbox_for(*LZ, radius_m=500, source_srs=COMPOUND_WKT)
        by_code = aoi.bbox_for(*LZ, radius_m=500, source_srs="EPSG:6350+5703")
        for a, b in zip(by_wkt, by_code):
            self.assertAlmostEqual(a, b, places=3)

    def test_wkt_without_a_vertical_axis_gets_navd88_added_properly(self):
        crs = collection.TileCrs(horizontal=ALBERS_WKT, vertical_declared=False)
        combined = CRS.from_user_input(crs.compound())
        self.assertTrue(combined.is_compound)
        self.assertEqual([c.to_epsg() for c in combined.sub_crs_list], [6350, 5703])


class WktCollectionTests(unittest.TestCase):
    """A collection of 1.4 tiles with WKT, end to end through tile selection."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.dir, ignore_errors=True))
        x, y = aoi.bbox_for(*LZ, radius_m=0, source_srs="EPSG:6350+5703")[:2]
        self.tile = write_tile(self.dir, "ga_2018.laz", bounds=(x - 600, y - 600, x + 600, y + 600),
                               version=(1, 4), wkt=COMPOUND_WKT)

    def test_a_target_inside_a_wkt_tile_selects_it(self):
        crs = collection.read_crs(self.tile)
        self.assertTrue(crs.vertical_declared)
        bbox = aoi.bbox_for(*LZ, radius_m=250, source_srs=crs.compound())
        tiles = collection.tiles_for(collection.scan(self.dir), bbox)
        self.assertEqual([t.path.name for t in tiles], ["ga_2018.laz"])


if __name__ == "__main__":
    unittest.main()
