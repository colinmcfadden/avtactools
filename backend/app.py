from flask import Flask, redirect, request
from flask_cors import CORS
from flask_jwt_extended import JWTManager
from dotenv import load_dotenv
from datetime import timedelta
import os

load_dotenv()

try:
    from version import __version__
except ImportError:
    __version__ = "0.0.0-dev"

from models import User, db
from entitlements import account_active
from security_config import resolve_jwt_secret, validate_email_configuration

# Import your Blueprints
from routes.terrain_routes import terrain_bp
from routes.location_routes import location_bp
from routes.weather_routes import weather_bp
from routes.export_routes import export_bp
from routes.auth import auth_bp
from routes.lz_routes import lz_bp
from routes.saved_routes import saved_routes_bp
from routes.point_sets import point_sets_bp
from routes.threat_routes import threat_bp
from routes.route_share_routes import route_share_bp
from routes.admin_routes import admin_bp

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
    allow_headers=['Authorization', 'Content-Type'],
    methods=['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
)

basedir = os.path.abspath(os.path.dirname(__file__))

# Use a managed database when DATABASE_URL is set (e.g. Neon/Supabase Postgres
# in production — the Space's own disk is ephemeral); fall back to a local
# SQLite file for development. Some providers hand out postgres:// URLs, but
# SQLAlchemy expects postgresql://.
database_url = os.environ.get('DATABASE_URL', 'sqlite:///' + os.path.join(basedir, 'ezpz.db'))
if database_url.startswith('postgres://'):
    database_url = database_url.replace('postgres://', 'postgresql://', 1)
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
if database_url.startswith('postgresql://'):
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
    # Only require HTTPS for the cookie in the deployed (Fly) environment so
    # local http://localhost admin testing still works.
    SESSION_COOKIE_SECURE=bool(os.environ.get('FLY_APP_NAME')),
)

db.init_app(app)
jwt = JWTManager(app)


@jwt.token_in_blocklist_loader
def token_is_revoked(_jwt_header, jwt_payload):
    """Reject deleted/suspended users and JWTs predating a password reset."""

    try:
        user = db.session.get(User, int(jwt_payload.get('sub')))
    except (TypeError, ValueError):
        return True
    if user is None:
        return True
    # An admin-set suspension (is_active = False) revokes access immediately for
    # any auth method — the super-admin is exempt (account_active handles that).
    if not account_active(user):
        return True
    credential = user.local_credential
    if credential is not None and credential.status == 'suspended':
        return True
    expected_version = credential.session_version if credential else 0
    # Tokens issued before session versioning had no ``sv`` claim and could
    # otherwise retain the old 30-day lifetime after deployment.
    return 'sv' not in jwt_payload or jwt_payload.get('sv') != expected_version

# Register Blueprints
app.register_blueprint(export_bp)
app.register_blueprint(terrain_bp)
app.register_blueprint(location_bp)
app.register_blueprint(weather_bp)
app.register_blueprint(auth_bp)
app.register_blueprint(lz_bp)
app.register_blueprint(saved_routes_bp)
app.register_blueprint(point_sets_bp)
app.register_blueprint(threat_bp)
app.register_blueprint(route_share_bp)
app.register_blueprint(admin_bp)

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
    ):
        try:
            db.session.execute(text(_ddl))
            db.session.commit()
        except Exception:
            db.session.rollback()  # column already exists

if __name__ == '__main__':
    app.run(debug=True, port=5000)
