"""The golden fixtures in contracts/ that PyGeodesy is the reference for.

The web (Jest) and the native apps (JUnit, Swift Testing) are checked against
these files; this is the check that the files still say what the server's own
library says. The full regeneration check is
`python contracts/scripts/mgrs_fixtures.py check` (about 20 s).
"""

import importlib.util
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[2] / "contracts" / "scripts"
SCRIPT = SCRIPTS / "mgrs_fixtures.py"


def load_script(path=SCRIPT):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MgrsFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = load_script()

    def test_committed_cases_are_what_pygeodesy_answers(self):
        checked = self.fixtures.verify_sample(step=12)
        # Forward and inverse each hold thousands of cases; a sample that
        # shrinks to nothing would pass this test while proving nothing.
        self.assertGreater(checked, 500)

    def test_fixture_files_exist_with_their_cases(self):
        for name, minimum in (("forward.json", 5000), ("inverse.json", 3000)):
            path = self.fixtures.FIXTURES / name
            self.assertTrue(path.exists(), f"{path} is missing")
            self.assertGreater(path.read_text(encoding="utf-8").count("\n"), minimum)


class SqliteFixtureTests(unittest.TestCase):
    """The `.LPS` and `.ths` fixtures: SQLite's own reading of each file, and the backend's `.ths` exporter."""

    @classmethod
    def setUpClass(cls):
        cls.fixtures = load_script(SCRIPTS / "sqlite_fixtures.py")

    def test_committed_files_hold_what_sqlite_says_and_the_sources_still_produce_it(self):
        self.fixtures.check()                      # raises on any drift

    def test_the_exporter_writes_the_rows_the_native_exporters_are_held_to(self):
        import json
        import sqlite3
        import tempfile
        from pathlib import Path as P

        import ths_export

        export = json.loads((self.fixtures.THREAT_FIXTURES / "export.json").read_text(encoding="utf-8"))
        tables = json.loads((self.fixtures.FIXTURES / "tables.json").read_text(encoding="utf-8"))["threats.ths"]
        data = ths_export.build_ths_bytes(export["threats"], now=self.fixtures.NOW)
        with tempfile.TemporaryDirectory() as folder:
            path = P(folder) / "x.ths"
            path.write_bytes(data)
            con = sqlite3.connect(path)
            for table in ("THREATS", "THREATRADAR", "SYSTEM"):
                columns = [r[1] for r in con.execute(f'PRAGMA table_info("{table}")')]
                self.assertEqual(columns, tables[table]["columns"])
                rows = [list(r) for r in con.execute(f'SELECT * FROM "{table}" ORDER BY rowid')]
                self.assertEqual(rows, tables[table]["rows"], table)
            con.close()
        self.assertGreater(len(tables["THREATS"]["rows"]), 5)


class ThsExportTests(unittest.TestCase):
    """`build_ths_bytes` moved out of the routes so it can be tested without Flask or numpy."""

    @classmethod
    def setUpClass(cls):
        import ths_export
        cls.ths_export = ths_export

    def test_the_date_is_fixed_when_asked_and_current_otherwise(self):
        from datetime import datetime
        self.assertEqual(self.ths_export._amps_dtg(datetime(2026, 10, 2, 12, 34, 56)), "02123456102026")
        self.assertEqual(len(self.ths_export._amps_dtg()), 14)

    def test_the_same_inputs_and_date_give_the_same_rows(self):
        import sqlite3
        import tempfile
        from datetime import datetime
        from pathlib import Path as P
        threats = [{"name": "A", "lat": 1, "lon": 2, "radars": [{"type": 0, "rangeNmi": 5, "bands": []}]}]
        dumps = []
        for _ in range(2):
            data = self.ths_export.build_ths_bytes(threats, now=datetime(2026, 1, 2, 3, 4, 5))
            with tempfile.TemporaryDirectory() as folder:
                path = P(folder) / "x.ths"
                path.write_bytes(data)
                con = sqlite3.connect(path)
                dumps.append(list(con.execute("SELECT DATE_TIME, OFFICIAL_NAME FROM THREATS")))
                con.close()
        self.assertEqual(dumps[0], dumps[1])
        self.assertEqual(dumps[0], [("02030405012026", "A")])

    def test_no_threats_leaves_the_schema_and_no_rows(self):
        import sqlite3
        import tempfile
        from pathlib import Path as P
        data = self.ths_export.build_ths_bytes([])
        with tempfile.TemporaryDirectory() as folder:
            path = P(folder) / "x.ths"
            path.write_bytes(data)
            con = sqlite3.connect(path)
            for table in ("THREATS", "THREATRADAR", "SYSTEM", "LINKS"):
                self.assertEqual(con.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0], 0)
            con.close()

    def test_the_template_is_found_beside_the_module(self):
        from pathlib import Path as P
        self.assertTrue(P(self.ths_export.TEMPLATE_PATH).is_file())


if __name__ == "__main__":
    unittest.main()
