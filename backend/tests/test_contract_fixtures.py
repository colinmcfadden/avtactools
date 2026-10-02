"""The golden fixtures in contracts/ that PyGeodesy is the reference for.

The web (Jest) and the native apps (JUnit, Swift Testing) are checked against
these files; this is the check that the files still say what the server's own
library says. The full regeneration check is
`python contracts/scripts/mgrs_fixtures.py check` (about 20 s).
"""

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "contracts" / "scripts" / "mgrs_fixtures.py"


def load_script():
    spec = importlib.util.spec_from_file_location("mgrs_fixtures", SCRIPT)
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


if __name__ == "__main__":
    unittest.main()
