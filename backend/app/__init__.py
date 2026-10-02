"""EZ/PZ backend: a Flask application assembled by ``create_app``."""

import os

from flask import Flask


def create_app(config=None, *, environ=None, background_tasks=True):
    """Build the application.

    ``environ`` is where settings are read from (the process environment by
    default); ``config`` overrides individual Flask settings, which is how
    tests point the app at a throwaway database. ``background_tasks=False``
    skips cache warming and start-up notices.

    Imports are inside the function so that ``import app.models`` or
    ``import app.routes.auth`` does not assemble the whole application.
    """
    from app.config import cors_origins, load_config
    from app.extensions import db, init_extensions
    from app.hooks import register_hooks
    from app.routes import register_blueprints
    from app.startup import bootstrap_database, start_background_tasks

    environ = os.environ if environ is None else environ

    app = Flask(__name__)
    app.config.update(load_config(environ))
    if config:
        app.config.update(config)

    init_extensions(app, cors_origins(environ))
    register_hooks(app)
    register_blueprints(app)

    with app.app_context():
        bootstrap_database(db)

    if background_tasks:
        start_background_tasks(app)
    return app
