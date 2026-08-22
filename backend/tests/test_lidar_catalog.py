"""Tileset naming, and the checks that keep a URL from reaching the disk."""

import sys
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from lidar import catalog  # noqa: E402

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
