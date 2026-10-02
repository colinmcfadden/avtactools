"""The threat files a crew carries: the KMZ overlay and the AMPS .ths database.

Written before the builders moved out of the route module. The endpoints are
covered elsewhere only for an empty request; these check what is actually in
the files.
"""

import io
import sqlite3
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.routes import threats as threat_routes
from app.routes.threats import _amps_dtg, _xml_escape, build_ths_bytes, build_threats_kmz  # noqa: E402

KML = "{http://www.opengis.net/kml/2.2}"
SIZE, CENTER = 121, 60
META = {"radar_row": CENTER, "radar_col": CENTER, "mpp": 30.0,
        "north": 34.80, "south": 34.78, "west": -84.10, "east": -84.07}


def terrain():
    dem = np.full((SIZE, SIZE), 100.0, np.float32)
    dem[:, CENTER + 20:CENTER + 23] = 400.0
    return dem, dict(META)


def radar(**fields):
    return {"type": 0, "rangeNmi": 1, "antennaHeightFt": 30, "aglNotMsl": True,
            "bands": [{"altFt": 50, "color": "#fbbf24"}, {"altFt": 250, "color": "#f97316"}],
            **fields}


def kml_of(kmz_bytes):
    with zipfile.ZipFile(io.BytesIO(kmz_bytes)) as archive:
        assert archive.namelist() == ["doc.kml"]
        return ET.fromstring(archive.read("doc.kml"))


def placemark_names(root):
    return [p.find(f"{KML}name").text for p in root.iter(f"{KML}Placemark")]


class KmzTests(unittest.TestCase):
    def build(self, threats, dem=None):
        with patch.object(threat_routes, "fetch_dem", return_value=dem or terrain()):
            return kml_of(build_threats_kmz(threats))

    def test_a_threat_gets_a_folder_with_masks_then_a_range_ring_then_its_marker(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "SA-6", "radars": [radar()]}])
        folders = root.findall(f".//{KML}Folder")
        self.assertEqual(len(folders), 1)
        # Highest band first, so the more restrictive low band draws on top.
        self.assertEqual(placemark_names(root), [
            "SA-6 — Detection mask 250 ft",
            "SA-6 — Detection mask 50 ft",
            "SA-6 — Detection 1 nmi",
            "SA-6",
        ])

    def test_masks_are_vector_polygons_a_ring_is_a_closed_line_and_the_marker_a_point(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "SA-6", "radars": [radar()]}])
        marks = {p.find(f"{KML}name").text: p for p in root.iter(f"{KML}Placemark")}
        mask = marks["SA-6 — Detection mask 50 ft"]
        self.assertGreaterEqual(len(list(mask.iter(f"{KML}Polygon"))), 1)
        ring = marks["SA-6 — Detection 1 nmi"].find(f".//{KML}coordinates").text.split()
        self.assertEqual(len(ring), 73)
        first, last = (tuple(float(v) for v in point.split(",")[:2]) for point in (ring[0], ring[-1]))
        self.assertAlmostEqual(first[0], last[0], places=5)
        self.assertAlmostEqual(first[1], last[1], places=5)
        point = marks["SA-6"].find(f".//{KML}Point/{KML}coordinates").text
        self.assertEqual(point, "-84.085000,34.790000,0")

    def test_engagement_radars_are_labelled_and_coloured_apart_from_detection(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "T",
                            "radars": [radar(type=1, bands=[])]}])
        self.assertIn("T — Engagement 1 nmi", placemark_names(root))
        colour = root.find(f".//{KML}LineStyle/{KML}color").text
        self.assertEqual(colour, "ff4444ef")          # #ef4444, fully opaque

    def test_without_terrain_data_the_rings_and_marker_are_still_drawn(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "T", "radars": [radar()]}],
                          dem=(None, None))
        self.assertEqual(placemark_names(root), ["T — Detection 1 nmi", "T"])

    def test_a_radar_can_hide_its_mask_or_its_rings(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "T",
                            "radars": [radar(showMask=False, showRangeRings=False)]}])
        self.assertEqual(placemark_names(root), ["T"])

    def test_bands_marked_not_viewable_are_left_out(self):
        bands = [{"altFt": 50}, {"altFt": 250, "viewable": False}]
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "T", "radars": [radar(bands=bands)]}])
        self.assertIn("T — Detection mask 50 ft", placemark_names(root))
        self.assertNotIn("T — Detection mask 250 ft", placemark_names(root))

    def test_names_are_escaped_and_unnamed_threats_are_numbered(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": 'A & <B> "C"', "radars": []},
                           {"lat": 34.8, "lon": -84.09, "radars": []}])
        self.assertEqual(placemark_names(root), ['A & <B> "C"', "Threat 2"])
        self.assertEqual(_xml_escape("a&b<c>d\"e"), "a&amp;b&lt;c&gt;d&quot;e")

    def test_the_marker_description_carries_the_milstd_id_and_notes(self):
        root = self.build([{"lat": 34.79, "lon": -84.085, "name": "T", "radars": [],
                            "milstdId": "SHGPEWMAI------", "information": "S-300 <battery>"}])
        description = root.find(f".//{KML}Placemark/{KML}description").text
        self.assertEqual(description, "SHGPEWMAI------  S-300 <battery>")


def ths_rows(ths_bytes, table):
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "out.ths"
        path.write_bytes(ths_bytes)
        con = sqlite3.connect(path)
        con.row_factory = sqlite3.Row
        try:
            return [dict(row) for row in con.execute(f"SELECT * FROM {table} ORDER BY 1")]
        finally:
            con.close()


class ThsTests(unittest.TestCase):
    THREATS = [
        {"lat": 34.79, "lon": -84.085, "name": "X" * 80, "milstdId": "SHGPEWMAI------EXTRA",
         "information": "Y" * 300, "nickname": "N" * 80,
         "radars": [radar(bands=[{"altFt": 50, "colorIndex": 7}, {"altFt": 250}, {"altFt": 500}]),
                    {"type": 1, "rangeNmi": 5, "antennaHeightFt": 12, "showMask": False}]},
        {"lat": 35.0, "lon": -84.0, "radars": [{"type": 0, "rangeNmi": 3}]},
    ]

    def setUp(self):
        self.data = build_ths_bytes(self.THREATS)

    def test_each_threat_is_a_row_with_limits_and_defaults_applied(self):
        first, second = ths_rows(self.data, "THREATS")
        self.assertEqual((first["ID"], second["ID"]), (1, 2))
        self.assertEqual(first["OFFICIAL_NAME"], "X" * 50)
        self.assertEqual(first["APPROVED_NICKNAME"], "N" * 50)
        self.assertEqual(first["MILSTD_ID"], "SHGPEWMAI------")
        self.assertEqual(first["INFORMATION"], "Y" * 255)
        self.assertEqual((first["LATITUDE_DEG"], first["LONGITUDE_DEG"]), (34.79, -84.085))
        self.assertEqual(second["OFFICIAL_NAME"], "Threat 2")
        self.assertEqual(second["MILSTD_ID"], "SHGPEWMAI------")
        self.assertEqual(second["CORRELATION_CODE"], 2100000002)
        self.assertEqual((second["SHOW_THREAT"], second["SHOW_ELLIPSES"], second["ENABLE_EDIT"]), (1, 0, 1))
        self.assertEqual(second["SOURCE"], "SOF")

    def test_each_radar_keeps_its_bands_colours_and_flags(self):
        rows = ths_rows(self.data, "THREATRADAR")
        self.assertEqual([r["ID"] for r in rows], [1, 1, 2])
        detection, engagement, other = rows
        self.assertEqual((detection["ELEVATION1"], detection["ELEVATION2"], detection["ELEVATION3"]), (50, 250, 500))
        self.assertEqual((detection["COLOR1"], detection["COLOR2"], detection["COLOR3"]), (7, 3, 5))
        self.assertEqual((detection["SHOW_MASK"], detection["SHOW_RANGE_RINGS"], detection["AGL_NOT_MSL"]), (1, 1, 1))
        self.assertEqual((detection["RADAR_TYPE"], detection["RANGE_NMI"], detection["ANTENNAE_HEIGHT_FT"]), (0, 1.0, 30.0))
        self.assertEqual((engagement["RADAR_TYPE"], engagement["SHOW_MASK"], engagement["AGL_NOT_MSL"]), (1, 0, 0))
        # No bands given: three zero elevations, all masks viewable, stock colours.
        self.assertEqual((other["ELEVATION1"], other["ELEVATION2"], other["ELEVATION3"]), (0, 0, 0))
        self.assertEqual((other["COLOR1"], other["COLOR2"], other["COLOR3"]), (1, 3, 5))
        self.assertEqual((other["MASK1_VIEWABLE"], other["MASK2_VIEWABLE"], other["MASK3_VIEWABLE"]), (1, 1, 1))

    def test_a_system_row_says_whether_it_can_engage_and_detect(self):
        first, second = ths_rows(self.data, "SYSTEM")
        self.assertEqual((first["USE_ENGAGEMENT"], first["USE_DETECTION"]), (1, 1))
        self.assertEqual((second["USE_ENGAGEMENT"], second["USE_DETECTION"]), (0, 1))
        self.assertEqual(first["SYSTEM_NAME"], "X" * 50)

    def test_the_template_is_cleared_first_so_old_threats_never_leak_in(self):
        again = build_ths_bytes([{"lat": 1.0, "lon": 2.0, "name": "Only", "radars": []}])
        self.assertEqual([r["OFFICIAL_NAME"] for r in ths_rows(again, "THREATS")], ["Only"])
        self.assertEqual(ths_rows(again, "LINKS"), [])

    def test_the_date_stamp_is_day_hour_minute_second_month_year(self):
        self.assertEqual(_amps_dtg(datetime(2026, 10, 2, 14, 30, 5)), "02143005102026")


if __name__ == "__main__":
    unittest.main()
