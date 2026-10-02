from flask import Flask, redirect, request, jsonify
from flask_cors import CORS
from flask_jwt_extended import JWTManager, verify_jwt_in_request, get_jwt_identity
from dotenv import load_dotenv
from datetime import timedelta
import os

load_dotenv()

try:
    from version import __version__
except ImportError:
    __version__ = "0.0.0-dev"

from database_url import database_uri, is_postgres
from models import AccountToken, AircraftProfile, LoginEvent, SavedLZ, SavedPointSet, SavedRoute, User, db
from entitlements import affiliation_ok
from token_revocation import is_revoked
from aircraft_seed import seed_aircraft_profiles
from schema_sync import ensure_unique_index, sync_table_columns
from security_config import (
    resolve_jwt_secret,
    session_cookie_secure,
    validate_email_configuration,
)

# Import your Blueprints
import lidar_builder
import terrain_tiles
from routes.terrain_routes import terrain_bp
from routes.lidar_routes import lidar_bp
from routes.location_routes import location_bp
from routes.weather_routes import weather_bp
from routes.export_routes import export_bp
from routes.auth import auth_bp
from routes.lz_routes import lz_bp
from routes.saved_routes import saved_routes_bp
from routes.point_sets import point_sets_bp
from routes.threat_routes import threat_bp
from routes.route_share_routes import route_share_bp
from routes.aircraft_routes import aircraft_bp
from routes.admin_routes import admin_bp
from routes.config_routes import config_bp
from routes.sync_routes import sync_bp
from client_header import CLIENT_HEADER

app = Flask(__name__)
cors_origins = [
    origin.strip()
    for origin in os.environ.get(
        'CORS_ORIGINS', 'http://localhost:3000'
    ).split(',')
    if origin.strip()
]
CORS(
    app,
    resources={r'/api/*': {'origins': cors_origins}},
    # The native apps (and, later, the web) identify themselves with X-EZPZ-Client.
    allow_headers=['Authorization', 'Content-Type', CLIENT_HEADER],
    methods=['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
)

basedir = os.path.abspath(os.path.dirname(__file__))

# Use a managed database when DATABASE_URL is set (e.g. Supabase Postgres in
# production); fall back to a local SQLite file for development. See
# database_url.py for why the Postgres driver is named explicitly.
database_url = database_uri(os.environ, 'sqlite:///' + os.path.join(basedir, 'ezpz.db'))
app.config['SQLALCHEMY_DATABASE_URI'] = database_url
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

# Managed Postgres (Neon/Supabase) and the Fly VM both drop idle connections —
# Neon autosuspends its compute after a few minutes. Without validation, the
# pool hands out a dead socket on the next request and the query raises
# OperationalError ("server closed the connection unexpectedly"), which surfaces
# to the browser as an intermittent 500 on the first call after an idle period
# (typically GET /auth/me on page load). pool_pre_ping runs a cheap liveness
# check and transparently reconnects; pool_recycle proactively retires
# connections before the server's own idle timeout can. Only meaningful for a
# real connection pool, so scope it to Postgres and leave SQLite dev untouched.
if is_postgres(database_url):
    app.config['SQLALCHEMY_ENGINE_OPTIONS'] = {
        'pool_pre_ping': True,
        'pool_recycle': 280,
    }
app.config['JWT_SECRET_KEY'] = resolve_jwt_secret(os.environ)
validate_email_configuration(os.environ)
# A one-day session balances an operational planning workflow with reasonable
# exposure if a bearer token is lost. Password resets revoke older tokens via
# the per-account session-version claim below.
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=24)

# Server-side session for the admin dashboard (separate from the SPA's JWT).
# Falls back to the JWT secret so a single strong secret is enough to configure.
app.config['SECRET_KEY'] = os.environ.get('ADMIN_SESSION_SECRET') or app.config['JWT_SECRET_KEY']
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax',
    # Secure wherever an HTTPS edge is declared, not only on Fly — this was
    # keyed to FLY_APP_NAME, so moving the app anywhere else silently dropped
    # the flag and sent admin session cookies in the clear.
    SESSION_COOKIE_SECURE=session_cookie_secure(os.environ),
)

db.init_app(app)
jwt = JWTManager(app)


@jwt.token_in_blocklist_loader
def token_is_revoked(_jwt_header, jwt_payload):
    """Reject deleted/suspended users, JWTs predating a password reset, and
    native device sessions that were signed out (see token_revocation)."""

    return is_revoked(jwt_payload)


@app.before_request
def enforce_affiliation_gate():
    """Server-side military-affiliation gate.

    The frontend hides the app until a user clears the gate, but a signed-in
    user could otherwise call the API directly with their token. Refuse every
    ``/api/*`` request from an unverified, unapproved user — except the auth
    flows they still need (login, register, Google, /me, and .mil verification
    itself), which all live under ``/api/auth/``.
    """
    path = request.path
    if not path.startswith('/api/') or path.startswith('/api/auth/'):
        return None
    try:
        verify_jwt_in_request(optional=True)
        identity = get_jwt_identity()
    except Exception:  # noqa: BLE001 — bad/expired token: let the view's own guard answer
        return None
    if identity is None:
        return None  # anonymous request to a public endpoint — unchanged
    try:
        user = db.session.get(User, int(identity))
    except (TypeError, ValueError):
        return None
    if user is not None and not affiliation_ok(user):
        return jsonify({
            "error": "Military affiliation verification is required to use this feature.",
            "code": "affiliation_required",
        }), 403
    return None


# Register Blueprints
app.register_blueprint(export_bp)
app.register_blueprint(terrain_bp)
app.register_blueprint(lidar_bp)
app.register_blueprint(location_bp)
app.register_blueprint(weather_bp)
app.register_blueprint(auth_bp)
app.register_blueprint(lz_bp)
app.register_blueprint(saved_routes_bp)
app.register_blueprint(point_sets_bp)
app.register_blueprint(threat_bp)
app.register_blueprint(route_share_bp)
app.register_blueprint(aircraft_bp)
app.register_blueprint(admin_bp)
app.register_blueprint(config_bp)
app.register_blueprint(sync_bp)

# Compute the coarse terrain tiles ahead of the first 3D view, in the
# background; see terrain_tiles. A no-op without TERRAIN_DATA_DIR. Under
# `python app.py` the reloader runs this module twice — in a process that only
# watches files, then in the one that serves — so warm only in the latter.
if not (__name__ == "__main__" and os.environ.get("WERKZEUG_RUN_MAIN") != "true"):
    terrain_tiles.start_warming()
    # Say at startup what the 3D view will lack, rather than leave it to be
    # found later as a "can't build" panel, or as terrain sitting 30 m off the
    # point cloud. Both have happened after a restart lost its settings.
    if not lidar_builder.configured():
        app.logger.warning("3D: point-cloud builds are off (LIDAR_BUILDER_URL is not set)")
    if os.environ.get("TERRAIN_DATA_DIR") and not terrain_tiles.geoid_grids_available():
        app.logger.warning("3D: geoid grids unavailable, so terrain will sit ~30 m off the "
                           "LiDAR. Set PROJ_NETWORK=ON or install the grids (projsync).")

@app.route('/')
def health_check():
    # On the admin subdomain (admin.ezpztac.app) the root should land on the
    # admin sign-in; every other host (the API domain, the .fly.dev hostname,
    # and Fly's internal health checks) keeps the JSON status response.
    host = (request.host or '').split(':')[0].lower()
    if host.startswith('admin.'):
        return redirect('/admin/login')
    return {
        "status": "online",
        "service": "AvTacTools Backend",
        "version": __version__
    }

with app.app_context():
    db.create_all()
    # create_all doesn't alter existing tables; add columns introduced after
    # a database was first created.
    from sqlalchemy import text
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
    # login_event gained `client` (the X-EZPZ-Client app version) after launch.
    sync_table_columns(db, LoginEvent)
    # account_token gained the refresh-token columns (family, client, session_version).
    sync_table_columns(db, AccountToken)
    # Saved records (and a user's own aircraft profiles) gained the sync columns (client_uuid, revision, deleted_at,
    # change_seq, last_idem_key). The unique index that stops a retried create
    # duplicating has to be added by hand: create_all never alters an existing table.
    for _model in (SavedLZ, SavedRoute, SavedPointSet, AircraftProfile):
        sync_table_columns(db, _model)
        if not ensure_unique_index(
            db, _model, ('user_id', 'client_uuid'), f'ux_{_model.__tablename__}_user_client_uuid'
        ):
            app.logger.warning("sync: could not add the client_uuid index on %s", _model.__tablename__)

    # Master aircraft profiles. Fills in missing slugs only — admin edits and
    # user-created profiles are never touched.
    try:
        seed_aircraft_profiles(db, AircraftProfile)
    except Exception:
        db.session.rollback()

if __name__ == '__main__':
    app.run(debug=True, port=5000)
