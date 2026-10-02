"""Tileset naming, and the checks that keep a URL from reaching the disk."""

import shutil
import tempfile
import unittest
from pathlib import Path

from lidar import catalog

TARGET = (34.591552, -84.128225)


class KeyTests(unittest.TestCase):
    def key(self, lat=TARGET[0], lon=TARGET[1], radius=250):
        return catalog.key_for(lat, lon, radius_m=radius)

    def test_the_same_target_always_resolves_to_the_same_tileset(self):
        self.assertEqual(self.key(), self.key())

    def test_the_key_does_not_disclose_the_location(self):
        """A readable key would put an LZ's coordinates in every request log."""
        key = self.key()
        for fragment in ("34", "84", "591", "128", "-"):
            self.assertNotIn(fragment, key)

    def test_distinct_targets_get_distinct_tilesets(self):
        self.assertNotEqual(self.key(), self.key(lat=34.60))

    def test_radius_is_part_of_the_identity(self):
        """Same point, wider area, different tileset — not a stale hit."""
        self.assertNotEqual(self.key(radius=250), self.key(radius=500))

    def test_targets_within_a_cell_share_a_tileset(self):
        # Both round to 34.5916. Values straddling a cell boundary would not
        # share a key — the guarantee is a grid, not proximity.
        self.assertEqual(self.key(lat=34.591601), self.key(lat=34.591649))

    def test_keys_are_a_fixed_opaque_shape(self):
        self.assertTrue(catalog.is_valid_key(self.key()))
        self.assertEqual(len(self.key()), 16)


class PathSafetyTests(unittest.TestCase):
    def test_traversal_attempts_are_refused(self):
        """A key arrives from a URL; '..' is how a file server leaks a disk."""
        for hostile in ("../../etc/passwd", "..", "a/../../b", "/etc", ""):
            self.assertFalse(catalog.is_valid_key(hostile), hostile)
            self.assertIsNone(catalog.path_for(hostile))

    def test_near_miss_keys_are_refused(self):
        for wrong in ("ABCDEF0123456789", "fb7cf0387999b48", "fb7cf0387999b4833",
                      "fb7cf0387999b48z"):
            self.assertFalse(catalog.is_valid_key(wrong), wrong)

    def test_a_valid_key_resolves_under_the_store(self):
        root = Path("/srv/tiles")
        self.assertEqual(catalog.path_for("fb7cf0387999b483", root=root),
                         root / "fb7cf0387999b483")


class AvailabilityTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.root = Path(tempfile.mkdtemp(prefix="tiles-"))
        self.addCleanup(__import__("shutil").rmtree, self.root, True)

    def test_a_directory_without_a_tileset_is_not_available(self):
        """A half-written build must not be served as if it were finished."""
        (self.root / "fb7cf0387999b483").mkdir()
        self.assertFalse(catalog.exists("fb7cf0387999b483", root=self.root))
        self.assertEqual(catalog.available(root=self.root), [])

    def test_a_complete_tileset_is_listed(self):
        directory = self.root / "fb7cf0387999b483"
        directory.mkdir()
        (directory / "tileset.json").write_text("{}")
        self.assertTrue(catalog.exists("fb7cf0387999b483", root=self.root))
        self.assertEqual(catalog.available(root=self.root), ["fb7cf0387999b483"])

    def test_stray_directories_are_ignored(self):
        (self.root / "notes").mkdir()
        (self.root / "notes" / "tileset.json").write_text("{}")
        self.assertEqual(catalog.available(root=self.root), [])

    def test_a_missing_store_is_empty_rather_than_an_error(self):
        self.assertEqual(catalog.available(root=self.root / "absent"), [])


if __name__ == "__main__":
    unittest.main()


class CoverageLookupTests(unittest.TestCase):
    """Finding a tileset by position rather than by an exact coordinate match.

    Hashing rounded coordinates buckets targets into cells, and two points a
    tenth of a metre apart fall in different cells whenever they straddle a
    boundary. 34.64815 rounds to 34.6482; the same point arriving via an MGRS
    round-trip as 34.64814884 rounds to 34.6481. The tileset was there and the
    lookup said "not generated".
    """

    LAT, LON, RADIUS = 34.64815, -83.86130, 250.0

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root, ignore_errors=True))
        self.key = catalog.key_for(self.LAT, self.LON, radius_m=self.RADIUS)
        directory = self.root / self.key
        (directory).mkdir(parents=True)
        (directory / "tileset.json").write_text("{}", encoding="utf-8")
        catalog.write_manifest(directory, self.LAT, self.LON, radius_m=self.RADIUS)

    def test_the_exact_target_is_found(self):
        self.assertEqual(catalog.find_covering(self.LAT, self.LON, root=self.root),
                         self.key)

    def test_a_target_from_an_mgrs_round_trip_is_found(self):
        """The case that broke: same place, different rounding cell."""
        typed_lat, typed_lon = 34.64814884477028, -83.86130087665374
        self.assertNotEqual(catalog.key_for(typed_lat, typed_lon, radius_m=self.RADIUS),
                            self.key)
        self.assertEqual(catalog.find_covering(typed_lat, typed_lon, root=self.root),
                         self.key)

    def test_a_target_nudged_across_the_area_is_still_found(self):
        # 100 m north, well inside a 250 m radius.
        moved = self.LAT + 100.0 / catalog.METRES_PER_DEGREE_LAT
        self.assertEqual(catalog.find_covering(moved, self.LON, root=self.root),
                         self.key)

    def test_a_target_beyond_the_usable_area_is_not_found(self):
        """Matching to the very edge would leave the target with no context."""
        far = self.LAT + 240.0 / catalog.METRES_PER_DEGREE_LAT
        self.assertIsNone(catalog.find_covering(far, self.LON, root=self.root))

    def test_a_target_in_another_state_is_not_found(self):
        self.assertIsNone(catalog.find_covering(40.0, -100.0, root=self.root))

    def test_longitude_is_scaled_by_latitude(self):
        """A degree of longitude is ~820 m shorter here than a degree of latitude.

        Without the cosine term an east-west offset reads as smaller than it
        is, and a target outside the area would match anyway.
        """
        north, east = catalog.offset_m(self.LAT, self.LON + 0.001,
                                       self.LAT, self.LON)
        self.assertEqual(round(north), 0)
        self.assertLess(east, 111.32)   # would be 111.32 m unscaled
        self.assertGreater(east, 80.0)

    def test_the_nearest_area_wins_when_several_cover_a_target(self):
        neighbour_lat = self.LAT + 40.0 / catalog.METRES_PER_DEGREE_LAT
        other = catalog.key_for(neighbour_lat, self.LON, radius_m=self.RADIUS)
        directory = self.root / other
        directory.mkdir(parents=True)
        (directory / "tileset.json").write_text("{}", encoding="utf-8")
        catalog.write_manifest(directory, neighbour_lat, self.LON, radius_m=self.RADIUS)

        # Sitting 5 m from the neighbour's centre, 35 m from the original's.
        probe = neighbour_lat + 5.0 / catalog.METRES_PER_DEGREE_LAT
        self.assertEqual(catalog.find_covering(probe, self.LON, root=self.root), other)

    def test_a_tileset_without_a_manifest_is_skipped_not_fatal(self):
        """Tilesets built before manifests existed must not break the lookup."""
        legacy = "0" * 16
        (self.root / legacy).mkdir()
        (self.root / legacy / "tileset.json").write_text("{}", encoding="utf-8")
        self.assertEqual(catalog.find_covering(self.LAT, self.LON, root=self.root),
                         self.key)

    def test_a_corrupt_manifest_is_skipped(self):
        (self.root / self.key / catalog.MANIFEST_FILENAME).write_text(
            "not json", encoding="utf-8")
        self.assertIsNone(catalog.find_covering(self.LAT, self.LON, root=self.root))

    def test_a_manifest_round_trips(self):
        area = catalog.read_manifest(self.root / self.key)
        self.assertEqual(area, (self.LAT, self.LON, self.RADIUS))
