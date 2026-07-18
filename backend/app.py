from flask import Flask
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

from models import db

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

app = Flask(__name__)
CORS(app)

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
app.config['JWT_SECRET_KEY'] = os.environ.get('JWT_SECRET_KEY', 'dev-secret-change-me')
# Default is 15 minutes, which silently invalidates sessions mid-use.
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(days=30)

db.init_app(app)
jwt = JWTManager(app)

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

@app.route('/')
def health_check():
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

if __name__ == '__main__':
    app.run(debug=True, port=5000)