"""Flask extensions, created here and attached to the app in create_app."""

from flask_cors import CORS
from flask_jwt_extended import JWTManager
from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()
jwt = JWTManager()
cors = CORS()


def init_extensions(app, cors_origins):
    db.init_app(app)
    jwt.init_app(app)
    cors.init_app(
        app,
        resources={r'/api/*': {'origins': cors_origins}},
        allow_headers=['Authorization', 'Content-Type'],
        methods=['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    )
