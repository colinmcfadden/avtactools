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
        stages = pipeline.build("https://example.com/survey/ept.json", "/out.laz",
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


class MultipleSourceTests(unittest.TestCase):
    """Reading a downloaded collection, where an area can span several tiles."""

    BBOX = (1077000, 1348000, 1078000, 1349000)
    TILES = ["/data/lidar/tile_00.laz", "/data/lidar/tile_10.laz"]

    def stages_for(self, sources, **kwargs):
        return pipeline.build(sources, "/out.las", bbox=self.BBOX,
                              source_srs=SOURCE_SRS, **kwargs)

    def test_every_tile_gets_its_own_reader(self):
        stages = self.stages_for(self.TILES)
        readers = [s for s in stages if s["type"].startswith("readers.")]
        self.assertEqual([r["filename"] for r in readers], self.TILES)

    def test_readers_are_merged_before_anything_downstream(self):
        """Without a merge, PDAL runs the tail once per reader and one wins.

        The output would then hold a single tile's share of the area, which
        looks like a valid tileset with a straight edge through it.
        """
        stages = self.stages_for(self.TILES)
        types = [s["type"] for s in stages]
        self.assertIn("filters.merge", types)
        self.assertEqual(types.index("filters.merge"), len(self.TILES))
        self.assertLess(types.index("filters.merge"), types.index("filters.crop"))

    def test_a_single_tile_needs_no_merge(self):
        types = [s["type"] for s in self.stages_for([self.TILES[0]])]
        self.assertNotIn("filters.merge", types)

    def test_a_lone_string_still_works(self):
        types = [s["type"] for s in self.stages_for(self.TILES[0])]
        self.assertEqual(types[0], "readers.las")
        self.assertNotIn("filters.merge", types)

    def test_unindexed_tiles_are_cropped_once_rather_than_per_reader(self):
        crops = [s for s in self.stages_for(self.TILES)
                 if s["type"] == "filters.crop"]
        self.assertEqual(len(crops), 1)

    def test_an_empty_source_list_is_refused(self):
        """Better than writing an empty tileset for a target with no coverage."""
        with self.assertRaises(ValueError):
            self.stages_for([])


class ColorizationTests(unittest.TestCase):
    BBOX = (1077000, 1348000, 1078000, 1349000)

    def stages(self, **kwargs):
        return pipeline.build("/data/tile.laz", "/out.las", bbox=self.BBOX,
                              source_srs=SOURCE_SRS, **kwargs)

    def test_each_kept_class_is_painted(self):
        assign = [s for s in self.stages() if s["type"] == "filters.assign"]
        self.assertEqual(len(assign), 1)
        # Three channels per class.
        self.assertEqual(len(assign[0]["value"]),
                         3 * len(pipeline.OBSTRUCTION_CLASSES))

    def test_ground_and_canopy_get_different_colours(self):
        """The whole point: a grey cloud cannot show where the trees are."""
        self.assertNotEqual(pipeline.CLASSIFICATION_COLORS[pipeline.GROUND],
                            pipeline.CLASSIFICATION_COLORS[pipeline.HIGH_VEGETATION])

    def test_colours_are_assigned_before_reprojection(self):
        """Reprojection rewrites coordinates; classification must still exist."""
        types = [s["type"] for s in self.stages()]
        self.assertLess(types.index("filters.assign"),
                        types.index("filters.reprojection"))

    def test_the_writer_uses_a_format_that_carries_rgb(self):
        """The default LAS point format has nowhere to put colour."""
        self.assertEqual(self.stages()[-1]["dataformat_id"], 3)

    def test_colouring_can_be_turned_off_for_measurement_output(self):
        stages = self.stages(color_by=None)
        self.assertNotIn("filters.assign", [s["type"] for s in stages])
        self.assertNotIn("dataformat_id", stages[-1])

    def test_an_unknown_colour_mode_is_refused(self):
        with self.assertRaises(ValueError):
            self.stages(color_by="elevation")

    def test_eight_bit_channels_are_widened_to_the_field_las_defines(self):
        """8-bit values in a 16-bit field read as near-black to a correct reader."""
        self.assertEqual(pipeline.to_16_bit(0), 0)
        self.assertEqual(pipeline.to_16_bit(255), 65535)
        assign = next(s for s in self.stages() if s["type"] == "filters.assign")
        self.assertIn("Red = 35466 WHERE Classification == 2", assign["value"])


class HeightColorizationTests(unittest.TestCase):
    """Colouring by height above ground, for surveys that classify only ground."""

    BBOX = (1077000, 1348000, 1078000, 1349000)

    def stages(self, **kwargs):
        kwargs.setdefault("color_by", pipeline.COLOR_BY_HEIGHT)
        kwargs.setdefault("classes", pipeline.SURFACE_CLASSES)
        return pipeline.build("/data/tile.laz", "/out.las", bbox=self.BBOX,
                              source_srs=SOURCE_SRS, **kwargs)

    def test_height_above_ground_is_derived_first(self):
        types = [s["type"] for s in self.stages()]
        self.assertIn("filters.hag_nn", types)
        self.assertLess(types.index("filters.hag_nn"),
                        types.index("filters.assign"))

    def test_ground_is_measured_before_thinning_removes_it(self):
        """Sampling first would decimate the surface the heights measure against."""
        types = [s["type"] for s in self.stages(thin_spacing_m=1.0)]
        self.assertLess(types.index("filters.hag_nn"),
                        types.index("filters.sample"))

    def test_every_band_is_painted_and_the_last_is_open_ended(self):
        assign = next(s for s in self.stages() if s["type"] == "filters.assign")
        self.assertEqual(len(assign["value"]), 3 * len(pipeline.HEIGHT_BANDS))
        self.assertTrue(any(v.endswith(">= 15.0") for v in assign["value"]))

    def test_bands_do_not_overlap_or_leave_a_gap(self):
        """A point in a gap keeps whatever colour it already had."""
        bounds = [upper for upper, _rgb in pipeline.HEIGHT_BANDS]
        self.assertIsNone(bounds[-1])
        finite = bounds[:-1]
        self.assertEqual(finite, sorted(finite))
        self.assertEqual(len(set(finite)), len(finite))

    def test_the_tallest_band_is_visually_distinct(self):
        """Above 15 m is a hazard on short final, not more canopy."""
        canopy = pipeline.HEIGHT_BANDS[-2][1]
        hazard = pipeline.HEIGHT_BANDS[-1][1]
        self.assertGreater(hazard[0], canopy[0] + 100)

    def test_surface_classes_keep_the_unlabelled_returns(self):
        """ARRA-era surveys put the trees in classes 0 and 1."""
        self.assertIn(pipeline.CREATED, pipeline.SURFACE_CLASSES)
        self.assertIn(pipeline.UNCLASSIFIED, pipeline.SURFACE_CLASSES)
        self.assertIn(pipeline.GROUND, pipeline.SURFACE_CLASSES)
        self.assertNotIn(pipeline.NOISE, pipeline.SURFACE_CLASSES)

    def test_classification_colouring_does_not_derive_height(self):
        types = [s["type"] for s in self.stages(
            color_by=pipeline.COLOR_BY_CLASSIFICATION)]
        self.assertNotIn("filters.hag_nn", types)


if __name__ == "__main__":
    unittest.main()
