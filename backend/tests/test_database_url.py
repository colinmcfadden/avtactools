"""The database URL production connects with.

A bare postgresql:// lets SQLAlchemy pick the driver, and SQLAlchemy 2.1 picks
psycopg 3, which the image does not ship: production crash-looped on it.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from database_url import database_uri, is_postgres  # noqa: E402

SQLITE = "sqlite:////code/ezpz.db"


class DatabaseUriTests(unittest.TestCase):
    def test_postgres_urls_name_the_psycopg2_driver(self):
        for given in ("postgresql://u:p@db.example:5432/app",
                      "postgres://u:p@db.example:5432/app"):
            with self.subTest(given=given):
                self.assertEqual(database_uri({"DATABASE_URL": given}, SQLITE),
                                 "postgresql+psycopg2://u:p@db.example:5432/app")

    def test_query_strings_survive(self):
        url = database_uri({"DATABASE_URL": "postgresql://u:p@h:6543/app?sslmode=require"}, SQLITE)
        self.assertEqual(url, "postgresql+psycopg2://u:p@h:6543/app?sslmode=require")

    def test_a_url_that_already_names_a_driver_is_left_alone(self):
        given = "postgresql+psycopg2://u:p@h/app"
        self.assertEqual(database_uri({"DATABASE_URL": given}, SQLITE), given)

    def test_no_database_url_means_local_sqlite(self):
        self.assertEqual(database_uri({}, SQLITE), SQLITE)
        self.assertEqual(database_uri({"DATABASE_URL": "  "}, SQLITE), SQLITE)

    def test_postgres_is_recognised_for_the_connection_pool(self):
        self.assertTrue(is_postgres("postgresql+psycopg2://u:p@h/app"))
        self.assertFalse(is_postgres(SQLITE))


if __name__ == "__main__":
    unittest.main()
