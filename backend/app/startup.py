"""Work done once when the app starts."""

import os

from app.database.migrations import apply_schema_updates
from app.models import AircraftProfile
from app.services import lidar_client
from app.services.aircraft.seed import seed_aircraft_profiles
from app.services.terrain import field_detection
from app.services.terrain import tiles as terrain_tiles


def seed_master_data(db):
    # Master aircraft profiles. Fills in missing slugs only — admin edits and
    # user-created profiles are never touched.
    try:
        seed_aircraft_profiles(db, AircraftProfile)
    except Exception:
        db.session.rollback()


def bootstrap_database(db):
    """Create and update tables, then fill in master data. Idempotent."""
    apply_schema_updates(db)
    seed_master_data(db)


def start_background_tasks(app):
    """Warm caches, load the model, and say what the 3D view will lack.

    Kept apart from create_app so that only the process that serves requests
    does it: under the dev server's reloader the code also runs in a process
    that merely watches files.
    """
    # Compute the coarse terrain tiles ahead of the first 3D view, in the
    # background; see app/services/terrain/tiles.py. A no-op without
    # TERRAIN_DATA_DIR.
    terrain_tiles.start_warming()
    # Load the SAM model in the background, so the first LZ analysis does not
    # wait for it. It used to load before the app could start at all.
    field_detection.preload_async()
    # Say at startup what the 3D view will lack, rather than leave it to be
    # found later as a "can't build" panel, or as terrain sitting 30 m off the
    # point cloud. Both have happened after a restart lost its settings.
    if not lidar_client.configured():
        app.logger.warning("3D: point-cloud builds are off (LIDAR_BUILDER_URL is not set)")
    if os.environ.get("TERRAIN_DATA_DIR") and not terrain_tiles.geoid_grids_available():
        app.logger.warning("3D: geoid grids unavailable, so terrain will sit ~30 m off the "
                           "LiDAR. Set PROJ_NETWORK=ON or install the grids (projsync).")
