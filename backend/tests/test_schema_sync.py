"""Tests for adding model columns to a table that predates them.

The failure this guards against: ``db.create_all()`` never alters an existing
table, so a database whose ``aircraft_profile`` was created before the model
gained ``perf_source`` 500s on the first query that names it.
"""

import sys
import unittest
from pathlib import Path

from flask import Flask
from sqlalchemy import inspect as sa_inspect, text


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from aircraft_seed import seed_aircraft_profiles  # noqa: E402
from models import AircraftProfile, db  # noqa: E402
from schema_sync import sync_table_columns  # noqa: E402


# The shape aircraft_profile had before perf_source and the template_* columns
# were added — i.e. what a database created mid-change actually holds.
LEGACY_TABLE = """
CREATE TABLE aircraft_profile (
    id INTEGER NOT NULL PRIMARY KEY,
    user_id INTEGER,
    slug VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    designation VARCHAR(40) NOT NULL,
    icon_key VARCHAR(40) NOT NULL DEFAULT 'generic',
    rotor_diameter_m FLOAT NOT NULL DEFAULT 16.357,
    rotor_tip_clearance_m FLOAT NOT NULL DEFAULT 60.0,
    default_airspeed_kts FLOAT NOT NULL DEFAULT 100,
    default_airspeed_type VARCHAR(20) NOT NULL DEFAULT 'ground',
    max_indicated_kts FLOAT NOT NULL DEFAULT 193,
    default_altitude_ft FLOAT NOT NULL DEFAULT 50,
    default_altitude_ref VARCHAR(10) NOT NULL DEFAULT 'agl',
    min_altitude_ft_msl FLOAT NOT NULL DEFAULT -2000,
    max_altitude_ft_msl FLOAT NOT NULL DEFAULT 20000,
    default_fuel_flow_lb_hr FLOAT NOT NULL DEFAULT 960,
    default_gross_weight_lb FLOAT NOT NULL DEFAULT 16000,
    amps_vehicle_description VARCHAR(200),
    vidx_file BLOB,
    vidx_name VARCHAR(120),
    is_active BOOLEAN NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 100,
    created_at DATETIME,
    updated_at DATETIME
)
"""

NEW_COLUMNS = {"perf_source", "template_file", "template_name", "template_kind"}


class SchemaSyncTests(unittest.TestCase):
    def setUp(self):
        self.app = Flask(__name__)
        self.app.config.update(
            SQLALCHEMY_DATABASE_URI="sqlite:///:memory:",
            SQLALCHEMY_TRACK_MODIFICATIONS=False,
        )
        db.init_app(self.app)
        self.ctx = self.app.app_context()
        self.ctx.push()

    def tearDown(self):
        db.session.remove()
        self.ctx.pop()

    def _columns(self):
        return {c["name"] for c in sa_inspect(db.engine).get_columns("aircraft_profile")}

    def _create_legacy(self):
        db.session.execute(text(LEGACY_TABLE))
        db.session.commit()

    def test_adds_columns_the_model_gained(self):
        self._create_legacy()
        self.assertFalse(NEW_COLUMNS & self._columns())

        added = sync_table_columns(db, AircraftProfile)

        self.assertEqual(NEW_COLUMNS, set(added))
        self.assertTrue(NEW_COLUMNS <= self._columns())

    def test_the_query_that_used_to_500_now_works(self):
        self._create_legacy()
        sync_table_columns(db, AircraftProfile)
        seed_aircraft_profiles(db, AircraftProfile)

        # Mirrors the admin aircraft list.
        rows = (
            AircraftProfile.query.filter(AircraftProfile.user_id.is_(None))
            .order_by(AircraftProfile.sort_order, AircraftProfile.name)
            .all()
        )
        self.assertGreater(len(rows), 0)
        uh60l = next(p for p in rows if p.slug == "uh60l")
        self.assertEqual("vidx", uh60l.perf_source)
        self.assertFalse(uh60l.has_template)
        self.assertIn("perf_source", uh60l.to_dict())

    def test_backfills_scalar_defaults_on_existing_rows(self):
        self._create_legacy()
        db.session.execute(text(
            "INSERT INTO aircraft_profile (slug, name, designation) "
            "VALUES ('legacy', 'Legacy', 'LEG')"
        ))
        db.session.commit()

        sync_table_columns(db, AircraftProfile)

        # perf_source carries a model default, so the pre-existing row must not
        # come back NULL and break code that reads it.
        row = AircraftProfile.query.filter_by(slug="legacy").one()
        self.assertEqual("custom", row.perf_source)

    def test_is_idempotent(self):
        self._create_legacy()
        sync_table_columns(db, AircraftProfile)
        self.assertEqual([], sync_table_columns(db, AircraftProfile))

    def test_leaves_columns_the_model_dropped_alone(self):
        # Dropping data automatically is never worth it; the stale vidx_*
        # columns from the rename just sit unused.
        self._create_legacy()
        sync_table_columns(db, AircraftProfile)
        self.assertIn("vidx_file", self._columns())

    def test_no_op_when_the_table_does_not_exist_yet(self):
        # create_all handles a fresh database; sync must not fail on it.
        self.assertEqual([], sync_table_columns(db, AircraftProfile))

    def test_create_all_then_sync_needs_no_changes(self):
        db.create_all()
        self.assertEqual([], sync_table_columns(db, AircraftProfile))
        self.assertTrue(NEW_COLUMNS <= self._columns())


if __name__ == "__main__":
    unittest.main()
