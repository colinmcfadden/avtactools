"""Entry point. gunicorn runs ``wsgi:app``; ``python wsgi.py`` is the dev server."""

import os

from dotenv import load_dotenv

# Settings first: create_app reads them when it is called.
load_dotenv()

from app import create_app  # noqa: E402

# Under `python wsgi.py` the dev server's reloader runs this file twice, once
# in a process that only watches files and once in the one that serves, so
# background work is started only in the latter.
app = create_app(
    background_tasks=not (__name__ == "__main__" and os.environ.get("WERKZEUG_RUN_MAIN") != "true"),
)

if __name__ == "__main__":
    app.run(debug=True, port=5000)
