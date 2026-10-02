"""A small real server for the native apps' integration tests.

The Android and iOS API clients are tested against recorded responses and a mock server. This is
the other half: the real routes, the real database logic and the real token rotation, behind a real
socket, so a client is tried against what it will meet rather than against a copy of it.

It wires the production blueprints the way ``app.py`` does (JWT with the production revocation
check, the affiliation gate) over a throwaway SQLite database, without the parts that need model
weights, terrain data or the network. Nothing here ships.

    python tests/live_server.py            # prints "READY <port>" when it is listening

Settings, by environment variable:

    EZPZ_ACCESS_TOKEN_SECONDS   how long an access token lives (default 86400). The integration tests
                                set 1, so a token lapses and the refresh flow runs for real.

Test-only routes, under ``/__test__`` (they exist only here):

    POST /__test__/account      {email, password, approved?} makes a verified account
    POST /__test__/age-refresh  {seconds, user_id?} moves every spent refresh token that far into the
                                past, standing in for time passing (the 30 s grace period)
    POST /__test__/stop         ends the process
"""

import os
import sys
import tempfile
import threading
import uuid
from datetime import datetime, timedelta
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from flask import Flask, jsonify, request  # noqa: E402
from flask_jwt_extended import JWTManager  # noqa: E402
from werkzeug.security import generate_password_hash  # noqa: E402
from werkzeug.serving import make_server  # noqa: E402

from affiliation_gate import enforce_affiliation_gate  # noqa: E402
from models import AccountToken, LocalCredential, User, db  # noqa: E402
from routes.aircraft_routes import aircraft_bp  # noqa: E402
from routes.auth import auth_bp  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402
from routes.lz_routes import lz_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from token_revocation import is_revoked  # noqa: E402


def create_app():
    app = Flask(__name__)
    folder = tempfile.mkdtemp(prefix="ezpz-live-")
    app.config.update(
        SQLALCHEMY_DATABASE_URI="sqlite:///" + os.path.join(folder, "live.db"),
        SQLALCHEMY_TRACK_MODIFICATIONS=False,
        JWT_SECRET_KEY="live-server-secret-that-is-over-32-bytes-long",
        JWT_ACCESS_TOKEN_EXPIRES=timedelta(seconds=int(os.environ.get("EZPZ_ACCESS_TOKEN_SECONDS", "86400"))),
        GOOGLE_CLIENT_ID="live-test-client-id",
    )
    db.init_app(app)
    jwt = JWTManager(app)

    @jwt.token_in_blocklist_loader
    def revoked(_header, payload):
        return is_revoked(payload)

    app.before_request(enforce_affiliation_gate)
    for blueprint in (auth_bp, config_bp, lz_bp, sync_bp, aircraft_bp):
        app.register_blueprint(blueprint)

    @app.post("/__test__/account")
    def make_account():
        body = request.get_json()
        user = User(email=body["email"].lower(), name=body.get("name", "Test Pilot"), role="user",
                    google_id=f"local:{uuid.uuid4()}", access_approved=bool(body.get("approved", True)))
        db.session.add(user)
        db.session.flush()
        db.session.add(LocalCredential(
            user_id=user.id, password_hash=generate_password_hash(body["password"]),
            email_verified_at=datetime.utcnow(), status="active", session_version=0,
        ))
        db.session.commit()
        return jsonify({"id": user.id})

    @app.post("/__test__/age-refresh")
    def age_refresh():
        seconds = float(request.get_json()["seconds"])
        rows = AccountToken.query.filter(AccountToken.purpose == "refresh", AccountToken.used_at.isnot(None)).all()
        for row in rows:
            row.used_at = row.used_at - timedelta(seconds=seconds)
        db.session.commit()
        return jsonify({"moved": len(rows)})

    @app.post("/__test__/stop")
    def stop():
        threading.Thread(target=lambda: os._exit(0), daemon=True).start()
        return jsonify({"status": "stopping"})

    with app.app_context():
        db.create_all()
    return app


def main():
    app = create_app()
    server = make_server("127.0.0.1", 0, app, threaded=True)
    print(f"READY {server.server_port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
