"""How the approach to an LZ is planned (survey/flightpath.py; docs/LZ_SURVEYS.md §16): at 30° where what stands allows it, at the
LiDAR's shallowest clear angle where that is steeper, or at the angle the survey's editor typed; flown until its descent along the
ground at the survey's own height above the tallest thing in the lane, 100 ft where it gives none, never up and down over each tree."""

import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from survey import compare, flightpath  # noqa: E402

TAN_30 = math.tan(math.radians(30.0))


def lane(angle=8.5, start=1000.0, trees=None, ground=None):
    """A way's lane as survey/obstacles.py keeps it: open ground at ``start`` ft (or ``ground(k)`` in step ``k``), with a tree
    ``trees(k)`` ft tall standing in step ``k`` where that is not None."""
    bins = []
    for k in range(100):
        under = ground(k) if ground else start
        tree = trees(k) if trees else None
        bins.append({"groundFt": under, "topFt": under + (tree or 0.0), "tallestFt": tree})
    return {"headingDeg": 360.0, "bearingDeg": 180.0, "start": {"groundFt": start}, "binM": 10.0, "bins": bins, "covered": 100,
            "clearance": {"angleDeg": angle, "obstacle": None}, "missing": None}


def measured(approach=None):
    return {"obstacles": {"status": "measured", "approach": approach}}


class HeightTests(unittest.TestCase):
    def test_the_survey_s_own_height_or_100_ft(self):
        for typed, height in (("100", 100.0), ("140 ft", 140.0), ("75′", 75.0), ("about 125", 125.0), ("150.5", 150.5),
                              ("140 ft (43 m)", 140.0)):
            self.assertEqual(flightpath.height_of({"profileObstacleFt": typed}), height, typed)
        # Nothing to read, nothing over 0, a number nobody typed, no field: 100 ft.
        for typed in ("", "about", "0", "-140", "−140", "9" * 400, None, 140):
            self.assertEqual(flightpath.height_of({"profileObstacleFt": typed}), 100.0, typed)
        self.assertEqual(flightpath.height_of({}), 100.0)
        self.assertEqual(flightpath.height_of(None), 100.0)


class PlanTests(unittest.TestCase):
    def test_at_30_degrees_where_the_trees_allow_it(self):
        planned = flightpath.plan(lane(8.5))
        self.assertEqual({k: planned[k] for k in ("angleDeg", "minimumDeg", "typed", "steep", "heightFt")},
                         {"angleDeg": 30.0, "minimumDeg": 8.5, "typed": False, "steep": False, "heightFt": 100.0})
        self.assertEqual(planned["descent"], {"fromTargetM": round(30.48 / TAN_30, 1), "groundFt": 1000.0})
        self.assertEqual((planned["tallestFt"], planned["aboveGroundFt"]), (None, 100.0))      # nothing stands: 100 ft over the ground
        exactly = flightpath.plan(lane(30.0))
        self.assertEqual((exactly["angleDeg"], exactly["steep"]), (30.0, False))
        nothing = flightpath.plan(lane(-2.0))                                       # nothing above the touchdown point: 0°
        self.assertEqual((nothing["angleDeg"], nothing["minimumDeg"]), (30.0, 0.0))

    def test_steeper_where_the_trees_will_not_let_it_be_30(self):
        planned = flightpath.plan(lane(41.0))
        self.assertEqual((planned["angleDeg"], planned["minimumDeg"], planned["steep"]), (41.0, 41.0, True))
        self.assertAlmostEqual(planned["descent"]["fromTargetM"], 30.48 / math.tan(math.radians(41.0)), delta=0.1)

    def test_from_the_survey_s_own_height_above_the_tallest_tree(self):
        canopy = lane(8.5, trees=lambda k: 98.4)                                     # 30 m of trees all the way out
        self.assertAlmostEqual(flightpath.plan(canopy, 140.0)["descent"]["fromTargetM"], (30.0 + 42.672) / TAN_30, delta=0.1)
        self.assertAlmostEqual(flightpath.plan(canopy)["descent"]["fromTargetM"], (30.0 + 30.48) / TAN_30, delta=0.1)
        planned = flightpath.plan(canopy, 140.0)
        self.assertEqual((planned["heightFt"], planned["tallestFt"], planned["aboveGroundFt"]), (140.0, 98.4, 238.4))

    def test_along_the_ground_not_up_and_down_over_each_tree(self):
        # The owner's (2026-10-27): one 98 ft tree 400 m out sets how high the approach is flown all the way in, 198 ft over the
        # ground, though nothing stands where it descends; trees of other heights, and gaps, change nothing.
        lone = flightpath.plan(lane(8.5, trees=lambda k: 98.4 if k == 40 else None))
        self.assertAlmostEqual(lone["descent"]["fromTargetM"], 198.4 * 0.3048 / TAN_30, delta=0.1)
        mixed = flightpath.plan(lane(8.5, trees=lambda k: (98.4, 40.0, None)[k % 3]))
        self.assertEqual((mixed["descent"], mixed["aboveGroundFt"]), (lone["descent"], 198.4))
        # The ground rising 1 m a step: the approach rises with it, 198 ft over it, so the path meets it further out.
        rising = flightpath.plan(lane(8.5, trees=lambda k: 98.4 if k == 40 else None, ground=lambda k: 1000.0 + k * 3.28084))
        self.assertAlmostEqual(rising["descent"]["fromTargetM"], (198.4 * 0.3048 - 0.5) / (TAN_30 - 0.1), delta=0.1)

    def test_at_the_angle_the_editor_typed(self):
        for least, typed in ((8.5, 12.0), (8.5, 35.0), (41.0, 12.0)):
            planned = flightpath.plan(lane(least), typed=typed)
            self.assertEqual((planned["angleDeg"], planned["minimumDeg"], planned["typed"], planned["steep"]),
                             (typed, least, True, False), (least, typed))
            self.assertAlmostEqual(planned["descent"]["fromTargetM"], 30.48 / math.tan(math.radians(typed)), delta=0.1)

    def test_a_lane_from_the_snapshot_is_read_as_loosely_as_it_is_kept(self):
        loose = lane(8.5)
        del loose["binM"]                                                            # 10 m steps
        loose["bins"][3] = None
        self.assertEqual(flightpath.plan(loose)["descent"]["fromTargetM"], round(30.48 / TAN_30, 1))

    def test_the_lane_s_own_steps(self):
        # The ground rising 1 m a step: over 10 m steps 1 m in 10, over 20 m steps 1 m in 20, so the path meets the approach sooner.
        rising = lane(8.5, ground=lambda k: 1000.0 + k * 3.28084)
        self.assertAlmostEqual(flightpath.plan(rising)["descent"]["fromTargetM"], (30.48 - 0.5) / (TAN_30 - 0.1), delta=0.1)
        rising["binM"] = 20.0
        self.assertAlmostEqual(flightpath.plan(rising)["descent"]["fromTargetM"], (30.48 - 0.5) / (TAN_30 - 0.05), delta=0.1)

    def test_no_plan_where_the_lidar_has_no_angle(self):
        earlier = lane()
        del earlier["start"]                                                         # from before version 5, from the LZ's edge
        for found in (None, "lane", earlier, {**lane(), "clearance": None}, {**lane(), "clearance": {"angleDeg": None}},
                      {**lane(), "clearance": {"angleDeg": float("nan")}}, {**lane(), "clearance": {"angleDeg": True}}):
            self.assertIsNone(flightpath.plan(found), found)


class ApproachPlanTests(unittest.TestCase):
    FIELD = {"profileObstacleFt": "140", "profileDistanceM": "200"}

    def test_from_the_survey_s_height(self):
        planned = flightpath.approach_plan(self.FIELD, measured(lane(8.5)), {})
        self.assertEqual((planned["heightFt"], planned["angleDeg"]), (140.0, 30.0))
        self.assertIsNone(flightpath.approach_plan(self.FIELD, measured(), {}))                       # no approach measured

    def test_at_the_angle_the_editor_typed_while_that_choice_stands(self):
        snapshot = measured(lane(8.5))
        row = next(r for r in compare.compare(self.FIELD, snapshot, {}) if r["key"] == "approach")
        choice = {"approach": {"choice": "typed", "value": 12.0, "reason": "Cut in 2024", "field": row["field"],
                               "computed": row["computed"]}}
        planned = flightpath.approach_plan(self.FIELD, snapshot, choice)
        self.assertEqual((planned["angleDeg"], planned["typed"]), (12.0, True))
        rows = compare.compare(self.FIELD, snapshot, choice)
        self.assertEqual(flightpath.typed_of(rows), 12.0)
        self.assertEqual(flightpath.approach_plan(self.FIELD, snapshot, {}, rows)["angleDeg"], 12.0)          # the rows given
        # Measured again to another angle, it no longer stands: 30° again.
        again = measured(lane(9.0))
        self.assertEqual(flightpath.approach_plan(self.FIELD, again, choice)["angleDeg"], 30.0)
        self.assertIsNone(flightpath.typed_of(compare.compare(self.FIELD, again, choice)))
        self.assertIsNone(flightpath.typed_of([]))

    def test_nothing_planned_without_the_lidar(self):
        for snapshot in (None, {}, {"obstacles": None}, {"obstacles": {"status": "unavailable", "approach": lane()}}):
            self.assertIsNone(flightpath.approach_plan(self.FIELD, snapshot, {}))


if __name__ == "__main__":
    unittest.main()
