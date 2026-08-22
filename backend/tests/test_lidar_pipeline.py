"""Pipeline construction, and the vertical datum guard.

The guard exists because of a failure observed on real data: without a geoid
grid, PROJ returns NAVD88 orthometric heights unchanged and reports success. On
the sample tile near Ellijay the true separation is -30.35 m, so every point
would have been placed almost exactly 100 ft too high — in a tool whose purpose
is judging obstruction clearance.
"""

import json
import sys
import unittest.mock
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from pyproj import CRS  # noqa: E402

from lidar import pipeline  # noqa: E402
from lidar.crs import (  # noqa: E402
    ECEF,
    VerticalDatumError,
    assert_vertical_datum_applied,
    compound_crs,
    has_vertical_datum,
)

# The sample tile's CRS and a point at its centre, ground-median height.
SOURCE_SRS = "EPSG:6350+5703"          # NAD83(2011) Conus Albers + NAVD88
SAMPLE_POINT = (1077500.0, 1348500.0, 532.2)


class VerticalDatumTests(unittest.TestCase):
    def test_a_compound_crs_carries_height(self):
        self.assertTrue(has_vertical_datum(compound_crs("EPSG:6350")))

    def test_a_2d_crs_does_not(self):
        """A 2D source needs no geoid, and must not be rejected for lacking one."""
        self.assertFalse(has_vertical_datum(CRS.from_epsg(6350)))

    def test_a_2d_source_skips_the_check(self):
        self.assertEqual(
            assert_vertical_datum_applied(CRS.from_epsg(6350), SAMPLE_POINT), 0.0)

    def test_a_passthrough_height_is_rejected(self):
        """PROJ reports this as success, which is exactly the danger."""
        source = compound_crs("EPSG:6350")
        with unittest.mock.patch("lidar.crs.geoid_separation", return_value=0.0):
            with self.assertRaises(VerticalDatumError) as caught:
                assert_vertical_datum_applied(source, SAMPLE_POINT)
        self.assertIn("100 ft", str(caught.exception))

    def test_a_real_separation_is_accepted_and_returned(self):
        source = compound_crs("EPSG:6350")
        with unittest.mock.patch("lidar.crs.geoid_separation", return_value=-30.35):
            self.assertAlmostEqual(
                assert_vertical_datum_applied(source, SAMPLE_POINT), -30.35)


class PipelineTests(unittest.TestCase):
    BBOX = (1077000, 1348000, 1078000, 1349000)

    def build(self, **kw):
        return pipeline.build("/data/tile.laz", "/out/tile.laz",
                              source_srs=SOURCE_SRS, **kw)

    def test_bounds_use_pdals_paired_axis_syntax(self):
        self.assertEqual(pipeline.bounds_expression(self.BBOX),
                         "([1077000, 1078000], [1348000, 1349000])")

    def test_output_lands_in_the_frame_cesium_renders(self):
        stages = self.build()
        reprojection = next(s for s in stages if s["type"] == "filters.reprojection")
        self.assertEqual(reprojection["out_srs"], ECEF)
        self.assertEqual(reprojection["in_srs"], SOURCE_SRS)

    def test_noise_and_unclassified_are_dropped(self):
        """Noise drags the ground surface down and inflates obstruction height."""
        stages = self.build()
        limits = next(s for s in stages if s["type"] == "filters.range")["limits"]
        self.assertIn("Classification[2:2]", limits)
        self.assertIn("Classification[5:5]", limits)
        self.assertNotIn("Classification[7:7]", limits)   # noise
        self.assertNotIn("Classification[1:1]", limits)   # unclassified

    def test_filtering_precedes_reprojection(self):
        """Cheaper to discard points before transforming them."""
        types = [s["type"] for s in self.build()]
        self.assertLess(types.index("filters.range"),
                        types.index("filters.reprojection"))

    def test_thinning_is_opt_in(self):
        self.assertFalse(any(s["type"] == "filters.sample" for s in self.build()))
        thinned = self.build(thin_spacing_m=1.0)
        sample = next(s for s in thinned if s["type"] == "filters.sample")
        self.assertEqual(sample["radius"], 1.0)

    def test_bounds_are_omitted_when_no_area_is_given(self):
        self.assertNotIn("bounds", self.build()[0])
        self.assertFalse(
            any(s["type"] == "filters.crop" for s in self.build()))

    def test_an_indexed_reader_crops_at_read_time(self):
        """EPT and COPC have a spatial index, so only the extent is fetched."""
        stages = pipeline.build("ept://https://example.com/ept.json", "/out.laz",
                                bbox=self.BBOX, source_srs=SOURCE_SRS)
        self.assertIn("bounds", stages[0])
        self.assertFalse(any(s["type"] == "filters.crop" for s in stages))

    def test_plain_las_is_cropped_by_a_filter_instead(self):
        """readers.las has no index and errors outright on a bounds argument."""
        stages = self.build(bbox=self.BBOX)
        self.assertNotIn("bounds", stages[0])
        crop = next(s for s in stages if s["type"] == "filters.crop")
        self.assertEqual(crop["bounds"], pipeline.bounds_expression(self.BBOX))

    def test_cropping_precedes_classification_filtering(self):
        types = [s["type"] for s in self.build(bbox=self.BBOX)]
        self.assertLess(types.index("filters.crop"), types.index("filters.range"))

    def test_the_reader_matches_the_source_format(self):
        for source, expected in [
            ("/data/tile.laz", "readers.las"),
            ("/data/tile.copc.laz", "readers.copc"),
            ("https://example.com/survey/ept.json", "readers.ept"),
        ]:
            stages = pipeline.build(source, "/out.laz", source_srs=SOURCE_SRS)
            self.assertEqual(stages[0]["type"], expected, source)

    def test_ecef_coordinates_keep_millimetre_precision(self):
        """ECEF values are ~5e6 m; a coarse scale would quantise away canopy."""
        writer = next(s for s in self.build() if s["type"] == "writers.las")
        self.assertEqual(writer["scale_z"], 0.001)
        self.assertEqual(writer["offset_z"], "auto")

    def test_it_serialises_to_a_pipeline_pdal_accepts(self):
        parsed = json.loads(pipeline.to_json(self.build(bbox=self.BBOX)))
        self.assertIn("pipeline", parsed)
        self.assertEqual(parsed["pipeline"][0]["type"], "readers.las")


if __name__ == "__main__":
    import unittest.mock  # noqa: F401
    unittest.main()
