"""Bring an existing database up to the current models, on every start.

There is no migration framework. ``create_all`` creates tables that are
missing but never alters one that exists, so columns added after a database was
first created are added here, by statements that do nothing when the column is
already there. Safe to run repeatedly.
"""

from sqlalchemy import text

from app.database.schema_sync import sync_table_columns
from app.models import AircraftProfile


def apply_schema_updates(db):
    db.create_all()
    # create_all doesn't alter existing tables; add columns introduced after
    # a database was first created.
    try:
        db.session.execute(text("ALTER TABLE user ADD COLUMN picture VARCHAR(500)"))
        db.session.commit()
    except Exception:
        db.session.rollback()  # column already exists
    try:
        db.session.execute(text(
            "ALTER TABLE local_credential "
            "ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0"
        ))
        db.session.commit()
    except Exception:
        db.session.rollback()  # column already exists

    # Admin/entitlement columns. "user" is quoted because it is a reserved word
    # in Postgres; each ALTER carries a DEFAULT so existing rows are backfilled.
    for _ddl in (
        'ALTER TABLE "user" ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT \'user\'',
        'ALTER TABLE "user" ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE',
        'ALTER TABLE "user" ADD COLUMN features JSON',
        'ALTER TABLE "user" ADD COLUMN mil_email VARCHAR(120)',
        'ALTER TABLE "user" ADD COLUMN mil_verified_at TIMESTAMP',
        # DEFAULT TRUE grandfathers everyone who existed before the affiliation
        # gate; new accounts insert access_approved=False via the model default.
        'ALTER TABLE "user" ADD COLUMN access_approved BOOLEAN NOT NULL DEFAULT TRUE',
    ):
        try:
            db.session.execute(text(_ddl))
            db.session.commit()
        except Exception:
            db.session.rollback()  # column already exists

    # aircraft_profile is young enough that deployments (and dev databases from
    # a reloader that caught the model mid-change) can hold an older shape of
    # the table. Rather than hand-maintain an ALTER per column the way the
    # tables above do, diff the model against the live schema and add whatever
    # is absent.
    sync_table_columns(db, AircraftProfile)
