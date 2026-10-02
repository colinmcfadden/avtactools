"""Shared helpers for tests that need a small Flask app and a signed-in user."""

from flask import Flask
from flask_jwt_extended import JWTManager, create_access_token

SECRET = "test-only-secret-over-32-bytes-long"


def make_app(*blueprints):
    """A bare app with JWT and the given blueprints, and headers for user 1.

    Deliberately not the real application: these tests are about one blueprint
    and should not need a database, a Postgres driver or the SAM model.
    """
    app = Flask(__name__)
    app.config.update(TESTING=True, JWT_SECRET_KEY=SECRET)
    JWTManager(app)
    for blueprint in blueprints:
        app.register_blueprint(blueprint)
    with app.app_context():
        token = create_access_token(identity="1")
    return app, {"Authorization": f"Bearer {token}"}
