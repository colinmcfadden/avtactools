"""The X-EZPZ-Client header: which app made a request."""

import sys
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from client_header import client_label, parse_client  # noqa: E402


class ParseClientTests(unittest.TestCase):
    def test_reads_platform_version_and_build(self):
        self.assertEqual(
            parse_client("android/1.4.0 (212)"),
            {"platform": "android", "version": "1.4.0", "build": 212},
        )
        self.assertEqual(
            parse_client("ios/2.0.1 (9)"),
            {"platform": "ios", "version": "2.0.1", "build": 9},
        )

    def test_the_build_number_is_optional(self):
        self.assertEqual(
            parse_client("web/1.7.7"),
            {"platform": "web", "version": "1.7.7", "build": None},
        )

    def test_a_prerelease_version_is_accepted(self):
        self.assertEqual(parse_client("android/1.5.0-beta.2 (300)")["version"], "1.5.0-beta.2")

    def test_the_platform_is_case_insensitive_and_comes_back_lower_case(self):
        self.assertEqual(parse_client("Android/1.4.0 (212)")["platform"], "android")
        self.assertEqual(parse_client("iOS/1.0.0")["platform"], "ios")

    def test_label_is_the_normalised_header(self):
        self.assertEqual(client_label("Android/1.4.0 (212)"), "android/1.4.0 (212)")
        self.assertEqual(client_label("ios/2.0.0"), "ios/2.0.0")

    def test_anything_else_is_dropped_not_stored(self):
        for raw in (
            None, "", "   ", 5, "android", "android/", "android/1.4", "android/1.4.0.1",
            "windows/1.0.0", "android/1.4.0 (abc)", "android/1.4.0 (212) extra",
            "android/1.4.0 (1234567890)",            # a build number this long is not real
            "<script>alert(1)</script>",
            "android/1.4.0\n(212)", "android/1.4.0 (212)\r\nX-Injected: 1",
            "android/" + "9" * 70, "a" * 200,
        ):
            with self.subTest(raw=raw):
                self.assertIsNone(parse_client(raw))
                self.assertIsNone(client_label(raw))

    def test_stored_label_always_fits_the_column(self):
        # LoginEvent.client is VARCHAR(80); the longest well-formed header must fit.
        longest = "android/9999.9999.9999-" + "a" * 20 + " (999999999)"
        self.assertIsNotNone(client_label(longest))
        self.assertLessEqual(len(client_label(longest)), 64)


if __name__ == "__main__":
    unittest.main()
