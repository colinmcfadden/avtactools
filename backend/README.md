# EZ/PZ backend

Flask API for EZ/PZ. Python 3.11. Deployed as a container (`Dockerfile`) behind
gunicorn: `gunicorn wsgi:app`, one worker with eight threads (the reasons are in
the repository's `AGENTS.md`, section 9).

## Layout

```
backend/
├─ wsgi.py              Entry point: `python wsgi.py` locally, `wsgi:app` under gunicorn
├─ app/                 The application package
│  ├─ __init__.py       create_app(): builds and returns a configured Flask app
│  ├─ config.py         Reads settings from the environment (and refuses a bad production setup)
│  ├─ extensions.py     db, jwt, cors — created once, attached in create_app
│  ├─ hooks.py          Per-request rules: token revocation, the .mil affiliation gate
│  ├─ startup.py        Seeding, schema updates, and background work (terrain warm-up, SAM preload)
│  ├─ paths.py          Where bundled files live, independent of the working directory
│  ├─ models/           SQLAlchemy models (user, aircraft, saved items)
│  ├─ database/         URL handling, start-up schema updates (no migration framework)
│  ├─ security/         Settings checks, rate limiting, feature entitlements
│  ├─ routes/           HTTP only: one blueprint per area; parse, call a service, return JSON
│  ├─ services/         The actual logic, with no Flask in it (terrain, threats, aircraft, weather, mail ...)
│  ├─ stores/           Short-lived in-process stores (route shares, threat download links)
│  ├─ assets/           Bundled files: LZ card Excel template, threat .ths template
│  ├─ templates/admin/  Server-rendered admin dashboard
│  └─ version.py        Written by semantic-release — do not edit
├─ lidar/               A separate deployable: the point-cloud build service (its own Dockerfile)
├─ tests/               Mirrors app/: routes/, services/, security/, database/; lidar/ for the build service
└─ docs/                Notes about running the backend (TERRAIN_DATA.md)
```

The rule of thumb: a **route** handles a request, a **service** does the work,
a **model** is a table. A service never imports Flask, so it can be tested with
plain function calls.

## Run it

PowerShell:

```powershell
cd backend
python -m venv venv; .\venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
Copy-Item .env.example .env        # then edit it
$env:TERRAIN_DATA_DIR="C:\_dev\avtactools\topo"; $env:PROJ_NETWORK="ON"; python wsgi.py
```

The API is then on http://127.0.0.1:5000. Without `DATABASE_URL` it uses a
local SQLite file (`ezpz.db`, git-ignored). Every setting is listed in
`.env.example`.

The SAM model (about 350 MB) downloads the first time it is needed and loads in
the background after start-up; the app answers requests while it loads.

## Test it

```powershell
cd backend
python -m pytest -q
```

Run it from any directory — `pyproject.toml` tells pytest where the code is. A
`conftest.py` clears the settings the app reads from the environment before
each test, so your shell's variables cannot change a result.

## Adding things

- **A route area:** create `app/routes/<area>.py` with a blueprint, add it to
  `app/routes/__init__.py`. Put anything beyond parsing and responding in a
  service.
- **A service:** a module under `app/services/`. Import Flask only if you truly
  need the request; take plain arguments and return plain values.
- **A model:** a module under `app/models/`, exported from
  `app/models/__init__.py`. There is no migration framework: a new column on an
  *existing* table also needs a guarded `ALTER TABLE` in
  `app/database/migrations.py` (quote `"user"` — it is reserved in Postgres).
- **A setting:** read it in `app/config.py` (or the module that owns it), add it
  to `.env.example`, and to the table in `AGENTS.md` section 10.
- **Heavy work at import time:** don't. Importing a module must not load a
  model, open a connection or start a thread; do that in `app/startup.py`.
