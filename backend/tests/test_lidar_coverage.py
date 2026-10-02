"""Choosing which USGS survey to read for a landing point.

The ranking is the point of this module. Lake Lanier is covered by both
ARRA-GA_LakeLanier_2010 (ground classified only, too sparse to resolve trees)
and GA_Statewide_B3_2018 (full ASPRS classification). Reading the older one
produces a bare terrain sheet with no obstruction data, so "covered" is not
the same as "usable".
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lidar import coverage  # noqa: E402


def square(west, south, east, north):
    return {"type": "Polygon", "coordinates": [[
        [west, south], [east, south], [east, north], [west, north], [west, south],
    ]]}


def feature(name, geometry, count=1000):
    return {"type": "Feature", "geometry": geometry, "properties": {
        "name": name, "count": count,
        "url": f"https://example.com/{name}/ept.json",
    }}


def write_index(features):
    handle = tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False,
                                         encoding="utf-8")
    json.dump({"type": "FeatureCollection", "features": features}, handle)
    handle.close()
    return Path(handle.name)


# Roughly the real situation at Lake Lanier, with both surveys overlapping.
LANIER = (34.64815, -83.86130)
GEORGIA = square(-85.6, 30.3, -80.8, 35.0)
LANIER_AREA = square(-84.2, 34.1, -83.5, 34.9)


class ContainmentTests(unittest.TestCase):
    def test_a_point_inside_a_footprint_is_covered(self):
        self.assertTrue(coverage.contains(GEORGIA, LANIER[1], LANIER[0]))

    def test_a_point_outside_is_not(self):
        self.assertFalse(coverage.contains(GEORGIA, -100.0, 40.0))

    def test_a_multipolygon_is_searched_in_full(self):
        """Survey footprints are routinely split into disjoint blocks."""
        multi = {"type": "MultiPolygon", "coordinates": [
            square(-90.0, 30.0, -89.0, 31.0)["coordinates"],
            GEORGIA["coordinates"],
        ]}
        self.assertTrue(coverage.contains(multi, LANIER[1], LANIER[0]))

    def test_an_unsupported_geometry_is_not_covered(self):
        self.assertFalse(coverage.contains({"type": "Point",
                                            "coordinates": [0, 0]}, 0.0, 0.0))

    def test_a_missing_geometry_does_not_raise(self):
        self.assertFalse(coverage.contains(None, 0.0, 0.0))


class RankingTests(unittest.TestCase):
    def setUp(self):
        self.path = write_index([
            feature("ARRA-GA_LakeLanier_2010", LANIER_AREA, count=12_898_552_218),
            feature("GA_Statewide_B3_2018", GEORGIA, count=43_103_638_101),
            feature("FL_Panhandle_2019", square(-87.0, 29.0, -84.0, 31.0)),
        ])
        self.addCleanup(self.path.unlink)
        self.index = coverage.load_index(self.path, download=False)

    def test_only_covering_surveys_are_returned(self):
        names = [s.name for s in coverage.surveys_for(*LANIER, index=self.index)]
        self.assertEqual(sorted(names),
                         ["ARRA-GA_LakeLanier_2010", "GA_Statewide_B3_2018"])

    def test_the_newer_survey_wins_even_though_it_is_not_the_local_one(self):
        """Vintage beats specificity: 2010 has no vegetation classes at all."""
        best = coverage.best_survey(*LANIER, index=self.index)
        self.assertEqual(best.name, "GA_Statewide_B3_2018")

    def test_the_year_is_parsed_out_of_the_project_name(self):
        """There is no year field in the index; the name is all there is."""
        best = coverage.best_survey(*LANIER, index=self.index)
        self.assertEqual(best.year, 2018)

    def test_a_post_spec_survey_is_expected_to_carry_vegetation(self):
        self.assertTrue(coverage.best_survey(*LANIER, index=self.index)
                        .likely_classified)

    def test_a_pre_spec_survey_is_not(self):
        older = next(s for s in coverage.surveys_for(*LANIER, index=self.index)
                     if s.year == 2010)
        self.assertFalse(older.likely_classified)

    def test_an_undated_survey_is_treated_conservatively(self):
        """KY_FullState carries no year, and its classification is unknown."""
        path = write_index([feature("KY_FullState", GEORGIA, count=10 ** 12)])
        self.addCleanup(path.unlink)
        survey = coverage.best_survey(*LANIER,
                                      index=coverage.load_index(path, download=False))
        self.assertIsNone(survey.year)
        self.assertFalse(survey.likely_classified)

    def test_size_breaks_ties_within_a_year(self):
        path = write_index([
            feature("GA_Small_2018", GEORGIA, count=1_000),
            feature("GA_Large_2018", GEORGIA, count=9_000_000),
        ])
        self.addCleanup(path.unlink)
        best = coverage.best_survey(*LANIER,
                                    index=coverage.load_index(path, download=False))
        self.assertEqual(best.name, "GA_Large_2018")

    def test_a_target_with_no_coverage_is_an_error_not_an_empty_build(self):
        with self.assertRaises(coverage.CoverageError):
            coverage.best_survey(48.0, -120.0, index=self.index)

    def test_the_survey_carries_the_url_to_read(self):
        self.assertTrue(coverage.best_survey(*LANIER, index=self.index)
                        .url.endswith("/ept.json"))


class IndexTests(unittest.TestCase):
    def test_a_missing_index_is_refused_when_downloading_is_off(self):
        with self.assertRaises(coverage.CoverageError):
            coverage.load_index(Path("does-not-exist.geojson"), download=False)

    def test_an_index_with_no_surveys_is_refused(self):
        path = write_index([])
        self.addCleanup(path.unlink)
        with self.assertRaises(coverage.CoverageError):
            coverage.load_index(path, download=False)

    def test_features_without_a_name_are_skipped(self):
        path = write_index([{"type": "Feature", "geometry": GEORGIA,
                             "properties": {"count": 1}},
                            feature("GA_Statewide_B3_2018", GEORGIA)])
        self.addCleanup(path.unlink)
        self.assertEqual(len(coverage.load_index(path, download=False)), 1)


if __name__ == "__main__":
    unittest.main()
