"""Dockerfiles as Coolify will see them.

Coolify rewrites a Dockerfile before building it: after every line that starts
with FROM — matched in any case — it inserts ARG lines for the app's settings.
A Python import at the start of a line inside a multi-line RUN therefore gets
ARG lines spliced into the middle of the command, and the build fails with
"unknown instruction". That broke the LiDAR build service's first deploy.
"""

import re
import unittest
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
DOCKERFILES = sorted(BACKEND.rglob("Dockerfile"))


class CoolifyRewriteTests(unittest.TestCase):
    def test_there_are_dockerfiles_to_check(self):
        self.assertTrue(DOCKERFILES)

    def test_only_real_from_instructions_start_with_from(self):
        """A FROM instruction is uppercase and not inside a continued command."""
        for dockerfile in DOCKERFILES:
            continued = False
            for number, line in enumerate(dockerfile.read_text().splitlines(), 1):
                starts_with_from = re.match(r"\s*from\b", line, re.IGNORECASE)
                if starts_with_from:
                    with self.subTest(file=str(dockerfile.relative_to(BACKEND)), line=number):
                        self.assertFalse(continued, f"line {number} continues a command but "
                                                    f"starts with FROM: {line.strip()!r}")
                        self.assertTrue(line.lstrip().startswith("FROM"),
                                        f"line {number} starts with {line.split()[0]!r}")
                stripped = line.rstrip()
                if not stripped.lstrip().startswith("#"):
                    continued = stripped.endswith("\\")


if __name__ == "__main__":
    unittest.main()
