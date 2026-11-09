"""A bare Flask app that opens the API's database, for a process that is not the API (docs/VFR_CHARTS.md §5.1).

The chart build runs in a process of its own, started by the Data tab's job. It must not import ``app.py``, which loads SAM (about a
gigabyte) at import, so it opens the database through this: the same URL and engine options as the API, and nothing else.
"""
from __future__ import annotations

import os

from flask import Flask

from database_url import database_uri, is_postgres

BASEDIR = os.path.abspath(os.path.dirname(__file__))


def engine_options(url: str) -> dict:
    """The API's engine options. Managed Postgres drops idle connections: ``pool_pre_ping`` checks one before it is handed out and
    ``pool_recycle`` retires it before the server's own idle timeout. Only for a real connection pool, so not for SQLite."""
    if is_postgres(url):
        return {"pool_pre_ping": True, "pool_recycle": 280}
    return {}


def database_url(environ=os.environ) -> str:
    return database_uri(environ, "sqlite:///" + os.path.join(BASEDIR, "ezpz.db"))


def make_app(environ=os.environ) -> Flask:
    from models import db

    app = Flask("ezpz-db")
    url = database_url(environ)
    app.config["SQLALCHEMY_DATABASE_URI"] = url
    app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
    options = engine_options(url)
    if options:
        app.config["SQLALCHEMY_ENGINE_OPTIONS"] = options
    db.init_app(app)
    return app
