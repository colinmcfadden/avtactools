# AGENTS.md — EZ/PZ (avtactools)

Context for AI coding agents. Read it before starting work; it is loaded
automatically by Codex and Claude Code from the repo root.

**Keep it current.** Update this file in the same PR as the change it
describes. Record facts and the reasons behind them, not a changelog — git
history already has that. Last full review: 2026-09-28.

---

## 1. What this is

**EZ/PZ** is a web app for military helicopter crews (built around the UH-60) to
plan landing zones (LZs), pickup zones (PZs), routes, and threats, and to
produce the planning products they carry: LZ cards, AMPS mission files,
ForeFlight/ATAK exports.

A planner sets a target by MGRS grid, runs terrain analysis to find and measure
a usable landing area, lays out tactical graphics (aircraft positions, sectors,
go-arounds, units), and exports the result. Routes are planned with airspeed,
altitude, wind, fuel and time-on-target, and round-trip with AMPS (the Army's
mission planning system).

- **Users:** Army aviators. Access requires a verified `.mil` email or admin
  approval.
- **Status:** public beta. Version lives in `frontend/package.json` and
  `backend/version.py`, both written by semantic-release.
- **Repo:** `github.com/colinmcfadden/avtactools` · default branch `develop`.

---

## 2. Non-negotiables

These come from the owner. Do not work around them.

| Rule | Why / how |
|---|---|
| **Unclassified only. No CUI.** | Hosting is Vercel/a home server. Nothing here is an authorized boundary. |
| **Threats are never persisted.** | They may be sensitive. Threats live in React state and are exported, never saved server-side. The QR flow puts the payload in the URL for this reason. |
| **Super-admin is untouchable.** | The account in `SUPER_ADMIN_EMAIL` is always an active admin and cannot be demoted, suspended or deleted. |
| **Any `.mil` address clears the affiliation gate.** | `POST /api/auth/mil/request` + `/verify`. |
| **Agents do not deploy.** | Never push to `main`, run `fly deploy`, or trigger production. Supply the commands; the owner runs them. |
| **No new major dependencies without asking.** | Say what it buys and ask first. |
| **Secrets stay server-side.** | Nothing secret in the React bundle. Any secret exposed in a chat or log gets rotated. |
| **Don't commit data.** | `.env*`, `*.db`, `*.pt` (SAM weights), `/topo/`, `/data/tiles/`, LiDAR, generated tilesets are all gitignored. |

---

## 3. Architecture

```
Browser — React 19 SPA (CRA), hosted on Vercel
 ├─ Leaflet 2D map ───────────── Mapbox satellite/outdoors, FAA VFR sectional tiles
 ├─ Cesium 3D view (in progress) ─ /api/lidar/*, /api/terrain/heightmap/*, Mapbox imagery
 └─ axios + bearer JWT ──────────► Flask API — gunicorn, 1 worker
                                    ├─ Postgres (Supabase)        [SQLite ezpz.db locally]
                                    ├─ SAM model (ultralytics)    LZ boundary detection
                                    ├─ local DEMs                 slope analysis, 3D terrain
                                    ├─ LiDAR tilesets  ◄── shared /data/tiles ──┐
                                    ├─ Resend (email), Google (OAuth token check)│
                                    ├─ keyless public APIs   AWC, FAA NOTAM,     │
                                    │                        Terrarium, Esri ... │
                                    └─ build requests ──► LiDAR build service ───┘
                                                          (backend/lidar image: PDAL +
                                                           py3dtiles, internal only)
```

The build service is a separate container. The API forwards build requests to
it and relays progress; it never runs the LiDAR toolchain or touches Docker.

The same Flask app serves a server-rendered admin dashboard at `/admin`,
reached on `admin.ezpztac.app` (the host check in `app.py` redirects `/` there).

---

## 4. Features

| Feature | What it does | Frontend | Backend |
|---|---|---|---|
| **Auth & access** | Google or email/password; email verification; `.mil` affiliation gate; per-user feature entitlements | `feature/auth/` | `routes/auth.py`, `entitlements.py`, `security_config.py`, `auth_rate_limit.py`, `email_service.py` |
| **Admin dashboard** | Users, roles, entitlements, access approval, password resets, master aircraft list | — (Jinja) | `routes/admin_routes.py`, `templates/admin/` |
| **LZ/PZ workspace** | Several LZ diagrams per session; target by MGRS; switch between them | `feature/lzWorkspace/`, `components/MapView.jsx`, `App.js` | — |
| **Terrain analysis** | SAM finds the LZ boundary in imagery; slope map from DEM | `feature/terrain/` | `routes/terrain_routes.py`, `terrain_provider.py` |
| **Planning graphics** | Helicopters, PZ markers, sectors of fire, go-arounds, doghouses, units, LZ box | `feature/{helicopters,pzMarker,sectorsOfFire,goAround,doghouses,unit}/` | — |
| **MIL-STD symbology** | MIL-STD-2525C symbols for units and threats (milsymbol) | `feature/symbols/` | — |
| **LZ card export** | Card image and Excel card | `feature/export/` | `routes/export_routes.py`, `export_service.py`, `lz_template.xlsx` |
| **Cloud save** | LZs, routes, point sets | `feature/savedMaps/`, `msnxImport/useSavedRoutes.js` | `routes/{lz_routes,saved_routes,point_sets}.py` |
| **Routes & AMPS** | Sketch and plan routes (speed/alt/wind/fuel/TOT); import/export `.msnx`; ForeFlight share | `feature/msnxImport/` | `routes/route_share_routes.py`, `route_share_store.py`, `/api/route-winds` |
| **Local points** | Import AMPS `.LPS` point files | `feature/localPoints/` | — |
| **Threats** | `.ths` import/export, terrain-masking viewshed, KMZ, QR | `feature/threats/` | `routes/threat_routes.py`, `threat_download_store.py`, `threat_template.ths` |
| **Weather** | METAR, NOTAMs, winds aloft | `feature/weather/` | `routes/weather_routes.py` |
| **Aircraft profiles** | Airframe drives map icon, separation, LZ capacity, planning defaults | `feature/aircraft/` | `routes/aircraft_routes.py`, `aircraft_seed.py`, `amps_package.py` |
| **3D LZ view** *(in progress, branch `feat/3d-lz-route`)* | LiDAR point cloud over DEM terrain and imagery, in Cesium. Opening it on an unbuilt LZ builds one automatically and shows progress. Visible routes draw at their planned MSL with curtains and labels (`docs/3D_PLANNING_GRAPHICS_PLAN.md`). A compass turns and tilts with the camera (heading in degrees true; click to face north) | `feature/viewer3d/` | `routes/lidar_routes.py`, `lidar_builder.py`, `terrain_tiles.py`, `backend/lidar/` (incl. `worker.py`), `tools/` |

Entitlement keys (`entitlements.FEATURES`): `lz_pz_tools`, `routes`,
`msnx_import`, `threats`, `cloud_save`, `exports`, `aircraft_profiles`. A
missing key means **enabled**, so new features default on.

---

## 5. Codebase map

```
avtactools/
├─ backend/                  Flask API (Python 3.11)
│  ├─ app.py                 App factory, CORS, JWT, affiliation gate, blueprints, ad hoc migrations
│  ├─ models.py              SQLAlchemy models (§7)
│  ├─ routes/                One blueprint per area (§6)
│  ├─ templates/admin/       Admin dashboard (server-rendered)
│  ├─ terrain_provider.py    DEM catalog (TERRAIN_DATA_DIR) + Terrarium fallback; slope analysis
│  ├─ terrain_tiles.py       Per-tile heightmaps for the Cesium terrain provider
│  ├─ lidar/                 Offline point-cloud pipeline — runs in its own Docker image (§12)
│  ├─ tests/                 pytest
│  ├─ Dockerfile, fly.toml   Production container and Fly config
│  └─ version.py             Written by semantic-release
├─ frontend/                 React 19 SPA (Create React App)
│  ├─ src/App.js             Top-level state and layout — large; most features hook in here
│  ├─ src/components/        Shared UI (MapView, Controls, MissionSummary, mobile inputs)
│  ├─ src/feature/<name>/    One folder per feature: components + a use<Name> hook
│  ├─ src/utils/             Coordinates, MGRS (`mgrs.js`), LZ dictionary, helicopter capacity
│  ├─ public/                msnx_template.msnx, static assets; public/cesium/ is copied at build
│  └─ scripts/copy-cesium.js prestart/prebuild: copies Cesium's static build into public/
├─ tools/                    Operator CLIs for LiDAR (find_lidar.py, build_lz.py)
├─ docs/                     USER_GUIDE.md; plans: INVITE_ONLY_LOGIN_PLAN.md, 3D_PLANNING_GRAPHICS_PLAN.md
├─ .github/workflows/        release.yaml (semantic-release only)
├─ AUTHENTICATION.md         Auth design, Resend setup, security posture
└─ backend/TERRAIN_DATA.md, backend/lidar/SERVER_SETUP.md
```

**Conventions.** Frontend features are self-contained folders with a
`use<Feature>.js` hook owning the state; `App.js` composes them. Backend areas
are Flask blueprints under `routes/`, each registered in `app.py`. Comments
explain *why*, not what — match the surrounding density.

---

## 6. API surface

All `/api/*` routes need a bearer JWT except the public auth flows. A
`before_request` gate returns `403 affiliation_required` for signed-in users
who have not cleared the `.mil`/approval check.

| Area | Routes |
|---|---|
| auth | `POST /api/auth/{login,register,google,verify-email,resend-verification,forgot-password,reset-password,mil/request,mil/verify}`, `GET /api/auth/me` |
| terrain | `POST /api/analyze-field` (SAM), `POST /api/terrain-analysis` (slope), `POST /api/elevations` (planner/AMPS ground, Terrarium), `GET /api/terrain/heightmap/<level>/<x>/<y>`, `POST /api/terrain/heights` (3D ground + geoid, local DEMs) |
| location | `POST /api/convert-grid` (MGRS → lat/lon), `POST /api/convert-to-mgrs` (no longer called by the SPA, which converts lat/lon → MGRS itself with `utils/mgrs.js`) |
| weather | `GET /api/weather`, `POST /api/route-winds` |
| export | `POST /api/generate-excel`, `POST /api/export-package` |
| saved data | `/api/lz`, `/api/routes` (+ `/<id>/file`), `/api/pointsets` — CRUD |
| aircraft | `/api/aircraft-profiles` CRUD, `/<id>/template` |
| threats | `POST /api/threat-mask`, `POST /api/threats-ths`, `GET/POST /api/threats-kmz`, `POST /api/threats-kmz-link` |
| route share | `POST /api/route-share`, public `GET /r/<token>`, `/r/<token>/route.<kind>` |
| lidar | `POST /api/lidar/resolve` (reports `canBuild`), `POST /api/lidar/build`, `GET /api/lidar/build/<key>` (polling keeps it alive), `DELETE /api/lidar/build/<key>` (stop waiting), `GET /api/lidar/tilesets[/<key>[/<path>]]` — coordinates only ever in POST bodies; progress is read by opaque key |
| admin | `/admin/*` — session cookie, not JWT |
| health | `GET /` → JSON status (or redirect to `/admin/login` on the admin host) |

Regenerate this from the source of truth with `app.url_map` if it drifts.

---

## 7. Data model

`models.py`: `User`, `LocalCredential`, `AccountToken` (verification and reset
tokens, stored as SHA-256), `LoginEvent`, `AircraftProfile`, `SavedRoute`,
`SavedPointSet`, `SavedLZ`.

**There is no migration framework.** `db.create_all()` creates tables;
new columns on existing tables are added by guarded `ALTER TABLE` statements in
`app.py`, and `schema_sync.sync_table_columns()` diffs `AircraftProfile`
against the live schema. When you add a column, add it the same way — and
quote `"user"`, which is reserved in Postgres.

JWTs carry an `sv` (session version) claim; a password reset bumps it, which
revokes older tokens. Tokens live 24 h in `localStorage` (`auth_token`).

---

## 8. Third-party services

| Service | Used for | Where | Credential |
|---|---|---|---|
| **Vercel** | Frontend hosting, PR preview deploys | project settings | Vercel account |
| **Coolify** on a home Proxmox VM | Backend hosting; auto-deploys from GitHub | self-hosted | GitHub App |
| **Cloudflare** | DNS; Tunnel to the home server; Access policies | dashboard | — |
| **Supabase** | Postgres | `DATABASE_URL` | secret |
| **Resend** | Transactional email | `email_service.py` | `RESEND_API_KEY` |
| **Google Cloud** | OAuth sign-in | `routes/auth.py`, `GoogleLoginButton.jsx` | client ID (public) |
| **Mapbox** | Basemaps, LZ-card imagery, point-cloud colour | `mapStyles.js`, `export_service.py`, `lidar/mapbox_imagery.xml` | public `pk.` token — **hardcoded in all three**; restrict it by URL in Mapbox |
| **Esri World Imagery** | Imagery fed to SAM | `terrain_routes.py` | keyless |
| **FAA VFR sectional** (ArcGIS-hosted) | VFR basemap | `mapStyles.js` | keyless |
| **AWS Terrarium tiles** | Elevations, threat viewshed, terrain fallback | `terrain_routes.py`, `threat_routes.py`, `terrain_provider.py` | keyless |
| **OpenTopoData** (SRTM30m) | Point elevation in field analysis | `terrain_routes.py` | keyless |
| **aviationweather.gov** | METAR, winds aloft | `weather_routes.py` | keyless |
| **FAA NOTAM search** | NOTAMs | `weather_routes.py` | keyless |
| **USGS 3DEP LiDAR** | Point clouds | `backend/lidar/`, `tools/` | keyless (§12) |
| **USGS 1/3″ DEMs** | Local terrain | mounted at `TERRAIN_DATA_DIR` | downloaded |
| **Ultralytics SAM** | LZ detection model | `sam_b.pt`, auto-downloaded on first load | — |
| **GitHub Actions** | Semantic release | `.github/workflows/release.yaml` | `GITHUB_TOKEN` |

---

## 9. Infrastructure and deployment

| Piece | Where | How it deploys |
|---|---|---|
| Frontend | Vercel, `ezpztac.app` | Automatically: `main` → production, PRs → previews |
| Backend (Fly) | `backend-brisk-acorn-4800.fly.dev` | **Manually:** `fly deploy` from `backend/` |
| Backend (self-hosted) | Coolify VM, `prod-ezpz-api.mcfadd.in` via Cloudflare Tunnel | Automatically on push, via Coolify's GitHub App |
| LiDAR build service | Coolify VM, beside the backend — **no public domain** | Automatically on push (separate Coolify app, base dir `/backend/lidar`) |
| Admin | `admin.ezpztac.app` → the backend's `/admin` | with the backend |
| Database | Supabase Postgres, **Session-mode pooler** | — |

- **Which backend is live is decided by `REACT_APP_API_URL` in Vercel.**
- The container runs **one gunicorn worker with 8 threads** and a 180 s
  timeout. One worker because SAM holds about 1 GB resident and the rate
  limiter, QR store and route-share store are in-process — do not add workers
  or machines without moving them to Redis. Threads so terrain tiles and
  point-cloud files load side by side; SAM runs one analysis at a time behind
  `_sam_lock` (its predictor keeps state between calls). Anything else
  module-level that is mutated per request needs a lock too.
- **SAM weights are not in git.** `SAM('sam_b.pt')` runs at import and
  downloads ~350 MB when the file is missing, so a container built from GitHub
  pays that on first start.
- **Supabase's direct host is IPv6-only.** Use the Session pooler URL, or
  containers without IPv6 fail with `Network is unreachable`.
- **Behind Cloudflare, set `TRUSTED_PROXY=cloudflare`**, or client IPs (rate
  limiting, audit) come from the tunnel and the startup secret and email
  checks (§10) don't run. Fly is detected automatically.
- **Coolify:** set the domain scheme to `http://` (Cloudflare terminates TLS;
  Force HTTPS causes a redirect loop). Mount `/data/tiles` and `/data/topo`
  into the container.
- The home VM holds the LiDAR (`/data/lidar`), tilesets (`/data/tiles`), the
  coverage index (`/data/cache`) and DEMs (`/data/topo`). The build service
  and the backend share `/data/tiles` — see `backend/lidar/SERVER_SETUP.md`.
- The Fly backend has no build service, so on Fly the 3D window falls back to
  printing a build command for an operator.

### 3D loading speed — what is in place

Options weighed in October 2026 for how fast the 3D view fills in. Measured on
the owner's workstation against the north Georgia DEMs (73 × 1/3″, 30–35°N).

| | Option | Status | Effect |
|---|---|---|---|
| A | gunicorn threads | **Done** — `--threads 8` in `backend/Dockerfile`; SAM and the DEM catalog refresh behind locks | Terrain tiles and point-cloud files no longer queue behind each other or behind an LZ analysis. Production only: the Flask dev server was already threaded. |
| B | Disk cache of terrain tiles | **Done** — `terrain_tiles.tile_bytes`, `TERRAIN_TILE_CACHE_DIR` | Each tile is computed once. A cached LZ path (levels 0–16) loads in ~40 ms. Keyed by a fingerprint of the DEMs, the tile code and whether the geoid grids are present, so none of those changing can serve stale heights. |
| C | Low-resolution layer for coarse tiles | **Replaced by warming** — `terrain_tiles.warm`, `TERRAIN_WARM_LEVEL` | Levels 0–8 were the slow ones (0.3–3.3 s per tile; ~12 s down one path); from level 9 every tile is ~50 ms. Warming computes levels 0–9 over the DEM coverage at startup instead: 380 tiles, 58 s, 3.3 MB, once. A first-ever LZ view then costs ~350 ms of terrain. |
| D | Pre-generate every tile | **Not done** | Pointless after B and C: fine tiles are already ~50 ms on demand, and the whole pyramid is hours and gigabytes. |
| E | Build the point cloud on save | **Done** — `viewer3d/useBuildOnSave.js` | Saving an LZ asks for a kept build in the background. The build service runs builds someone is watching first, so this never delays the 3D window. LZs loaded already saved are not built. |
| F | Build from the downloaded collection | **Owner config** — set `LIDAR_COLLECTION=/data/lidar` on the VM's build service | Skips reading the survey from AWS. Not measured since the imagery fix below made builds 5× faster; expect a further cut. |
| G | Coarser point-cloud streaming (`maximumScreenSpaceError` 2 → 4) | **Not done** | Trades the fidelity the 3D view exists for. |

Also: point colour from Mapbox level 18 instead of 20 (§13) took a 500 m build
from 707 s to 137 s.

**Heavy work goes first.** Terrain tiles not yet cached are computed per
request, and with the 3D window streaming ~20 at once an LZ analysis took
31.7 s instead of ~9–12 s. More threads do not help: tile work and SAM share one
Python interpreter (the GIL), and the browser was not the bottleneck (0.7 s
queued). So while an analysis, viewshed or export is in flight
(`feature/auth/requestPriority.js`, marked by an axios interceptor), the
terrain provider requests no new tiles, and the server's warm-up pauses
(`terrain_tiles.pauses_warming` on those routes). Measured after: 0 terrain
requests during the analysis, SAM 8.9 s. Point-cloud files still load; they
are static files and cost the server almost nothing.

**Cesium ion instead of self-hosted terrain** was considered and not taken. It
would be faster on a first view (pre-built tiles on a CDN), but its free tier
is non-commercial only (Commercial starts at $149/month as of October 2026),
and its terrain is a blend of sources that need not match the USGS DEMs and
the LiDAR to the metre — self-hosting from the same DEMs, with the same geoid
correction, is what keeps the point cloud sitting on the ground.

**On the VM:** mount a persistent directory and set
`TERRAIN_TILE_CACHE_DIR` to it (e.g. `/data/cache/terrain`); without it the
cache lives in the container's temp directory and is lost on every redeploy,
which costs one warm-up (~1 min) and the first view of each area again.

---

## 10. Configuration

### Backend

| Variable | Purpose |
|---|---|
| `JWT_SECRET_KEY` | **Required — set it on every deployment.** Signs JWTs. Startup refuses a missing or <32-char value in production — whenever `FLY_APP_NAME`, `TRUSTED_PROXY` or `APP_ENV=production` is set (`security_config.is_production`). Only a bare local run falls back to the public string `dev-secret-change-me`; there is deliberately no override to allow that fallback in production. |
| `DATABASE_URL` | Postgres URL (Supabase pooler). Unset → SQLite `backend/ezpz.db`. `postgres://` is rewritten. |
| `CORS_ORIGINS` | Comma-separated allowed origins. **Plural** — `CORS_ORIGIN` is silently ignored. Default `http://localhost:3000`. |
| `GOOGLE_CLIENT_ID` | Google OAuth client; same value as the frontend's. |
| `RESEND_API_KEY`, `EMAIL_FROM` | Email. Production (same test as `JWT_SECRET_KEY`) refuses to start without them, or with Resend's test sender. A bare local run needs neither. |
| `EMAIL_DELIVERY_MODE` | `console` locally — links print to the Flask log. Never in production. |
| `FRONTEND_URL` | Base for links in emails. |
| `NEW_ACCOUNT_NOTIFY_EMAIL` | Optional admin notification on sign-up. |
| `SUPER_ADMIN_EMAIL` | The protected super-admin account. |
| `ADMIN_SESSION_SECRET` | Admin dashboard cookie; falls back to the JWT secret. |
| `TRUSTED_PROXY` | `fly` or `cloudflare` — which header holds the client IP. Any value also marks the process as production, turning on the startup checks. |
| `APP_ENV` | `production` turns on the startup checks for a host with neither `FLY_APP_NAME` nor `TRUSTED_PROXY`. |
| `SESSION_COOKIE_SECURE` | Override; defaults on when a trusted proxy is set. |
| `TERRAIN_DATA_DIR` | DEM directory, `os.pathsep`-separated. |
| `TERRAIN_SOURCE` | `auto` (local, then Terrarium) · `local` · `remote`/`terrarium`. |
| `TERRAIN_*` | Tuning: `ANALYSIS_PADDING_M`, `CATALOG_REFRESH_SECONDS`, `LOCAL_HIGHRES_MAX_M`, `LOCAL_MIN_RESOLUTION_M`, `MAX_GRID_SIZE`, `TERRARIUM_SLOPE_ZOOM`, `REQUIRE_GEOID`. |
| `TERRAIN_TILE_CACHE_DIR` | Disk cache for 3D terrain tiles (§9). Default `<temp>/ezpz-terrain-cache`; `none` disables. Point it at a persistent mount in production. Safe to delete. |
| `TERRAIN_WARM_LEVEL` | Pre-compute terrain levels 0..N over the DEMs at startup (§9). Default `9`; `-1` disables. |
| `LIDAR_TILES_DIR` | Tileset store the API serves. Default `/data/tiles`. |
| `LIDAR_BUILDER_URL` | The build service, e.g. `http://<internal host>:8090`. Unset → no on-demand builds. |
| `LIDAR_BUILDER_TOKEN` | Shared secret with the build service. |
| `LIDAR_CACHE_DIR` | Where the USGS coverage index is cached. |

The build service has its own settings (`LIDAR_COLLECTION`,
`LIDAR_BUILD_CONTEXT_M`, `LIDAR_BUILDER_QUEUE`, …) — documented at the top of
`backend/lidar/worker.py`.
| `PROJ_NETWORK=ON` | **Local dev only**, when PROJ geoid grids aren't installed (§13). |

There is **no `backend/.env.example`** — it was removed in `55aef5d` although
`AUTHENTICATION.md` still points to it. Use this table.

### Frontend (`frontend/.env`, and Vercel)

| Variable | Purpose |
|---|---|
| `REACT_APP_API_URL` | API base **including `/api`**, e.g. `http://127.0.0.1:5000/api`. |
| `REACT_APP_GOOGLE_CLIENT_ID` | Google OAuth client. |
| `REACT_APP_LIDAR_BUILD_CWD` | Where the 3D window tells operators to run builds. |
| `REACT_APP_LIDAR_COLLECTION` | Downloaded LiDAR directory, added to that command. |

CRA inlines these at **build** time — changing one in Vercel needs a redeploy.

---

## 11. Local development

The owner's shell is **Windows PowerShell 5.1**: no `&&`, no `VAR=x cmd`.
Give commands in PowerShell syntax.

```powershell
# Backend — http://127.0.0.1:5000
cd backend
python -m venv venv; .\venv\Scripts\Activate.ps1
pip install -r requirements.txt pytest        # pytest is not in requirements.txt
$env:TERRAIN_DATA_DIR="C:\_dev\avtactools\topo"; $env:PROJ_NETWORK="ON"; python app.py

# Frontend — http://localhost:3000
cd frontend
npm install
npm start                                     # copies Cesium into public/cesium first
```

`.claude/launch.json` defines the `frontend` preview server.

### Tests

```powershell
cd backend;  python -m pytest tests -q
cd frontend; $env:CI="true"; npm test         # CI=true, or Jest waits in watch mode
cd frontend; npm run build                    # catches lint errors the tests miss
```

- `tests/test_threat_qr_export.py` **fails and predates current work**
  (SQLAlchemy app registration). Report it; don't "fix" it as a side effect.
- The geoid test skips without `PROJ_NETWORK=ON` — PROJ returns heights
  unchanged, and *reports success*, when grids are missing.
- **Nothing runs tests in CI.** The only workflow is the release. Run them yourself.
- The build prints many pre-existing ESLint warnings; add none of your own.

---

## 12. Git workflow and CI/CD

- **Branches:** `develop` is integration and the PR target. `main` is
  production. Work on `feat/…` or `fix/…` (`feature/…` also appears). Release
  via `release/*` → `main`; hotfixes branch from `main`.
- **Conventional Commits are enforced** by commitlint in a husky `commit-msg`
  hook. The subject must not start with a capital: `feat: add LiDAR tiles`
  passes, `feat: LiDAR tiles` is rejected.
- **Release is automatic on merge to `main`.** `release.yaml` runs
  semantic-release: it reads `feat`/`fix`/`!` commits, bumps
  `frontend/package.json` and `backend/version.py`, writes `CHANGELOG.md`,
  tags, publishes a GitHub release, and commits
  `chore(release): X.Y.Z [skip ci]` so Vercel skips a second build.
- Vercel deploys `main` to production and builds a preview for every PR. The
  Fly backend is deployed **by hand**; Coolify deploys itself on push.

---

## 13. Domain knowledge

**Coordinates.** Crews work in MGRS. Lat/lon → MGRS runs in the browser
(`utils/mgrs.js`: Krüger-series transverse Mercator, matched against the
backend's PyGeodesy at 5,255 points worldwide, every one identical) — the
cursor readout converts on every mouse move, so never call the API for it.
Digits are truncated, not rounded, and the zone is zero-padded (`05R`), as
PyGeodesy writes it. Heights are feet in the UI; the backend
computes in metres and converts at the edges (e.g. `/api/elevations` returns
`elevationsFt`). AMPS files store metres. Check units at every boundary.

**Geodesy — the trap that recurs.** USGS DEMs and LiDAR use NAVD88
(orthometric) heights; Cesium and 3D Tiles use WGS84 ellipsoidal heights. The
difference in north Georgia is **−30.35 m**. Both `terrain_tiles.py` and the
LiDAR pipeline apply it, and must agree. Without geoid grids, PROJ passes
heights through unchanged **and reports success** — `lidar/crs.py`
`assert_vertical_datum_applied` exists to catch that. The LiDAR Docker image
bakes grids in with `projsync --target-dir "$PROJ_DATA"`; PDAL does not read
pyproj's default grid location.

**AMPS formats** (reverse-engineered):
- **`.msnx`** — zip of XML documents. `public/msnx_template.msnx` carries
  `Vehicle Installations/UH60L.vidx`, a native performance model only AMPS
  produces. **An exported airframe cannot be faked** — other airframes need an
  admin to upload real AMPS bytes to the profile. Don't synthesise a `.vidx`.
- **Per-point plan values mean "to this point"** (the arriving leg). Altitude
  is read from `CmdAlt` (metres MSL), not `PlanAltitudeValue`. Value formats
  are in `feature/msnxImport/ampsFormats.js`.
- **`.LPS`** local points and **`.ths`** threats are SQLite/SpatiaLite,
  read in pure JS (`localPoints/sqliteReader.js`) and written from cleaned
  templates (`threat_template.ths`) so the exact schema survives.

**Aircraft data.** Only UH-60L performance comes from a real `.vidx`
(`perf_source: 'vidx'`). Every other airframe is published spec data, flagged
unverified in the UI. Tip clearances are seeded at 60 m pending the owner's
doctrinal values — don't invent separation distances.

**Threat masking.** Radial line-of-sight viewshed over Terrarium tiles with
earth curvature and refraction, one mask per altitude band. The DEM is
smoothed and the observer height taken over a 3×3 footprint, because a single
~60 m pixel beside a low antenna otherwise casts a false quadrant-wide shadow.
KMZ masks are vector polygons because ForeFlight won't render raster overlays.

**LiDAR (3D view).** Source is USGS 3DEP. The same survey is published two ways:

| | |
|---|---|
| AWS Entwine index `s3-us-west-2.amazonaws.com/usgs-lidar-public/<survey>/ept.json` | Spatially indexed — a build queries just its box; nothing downloads. Default. |
| USGS rockyweb LAZ tiles | Downloadable 1 km tiles; built from with `--collection`. |

- **Survey vintage decides usefulness.** Surveys from about 2015 on classify
  vegetation (classes 3/4/5); older ones (ARRA-era) classify ground only.
  Rank by the year in the survey *name* — publication dates are misleading
  (ARRA 2010 was republished in 2023). `lidar/coverage.py` and
  `tools/find_lidar.py` both do this.
- North Georgia's good survey is `GA_Statewide_B3_2018` (rockyweb project
  `GA_Statewide_2018_B18_DRRA`), ~2.75–3.5 points/m².
- A downloaded directory can hold **several surveys in different CRSs**
  (Albers vs UTM 17N). Tiles carry their own CRS; never assume the first
  speaks for all.
- Tilesets are addressed by an opaque hash key so URLs don't reveal locations,
  but **found by coverage**: each has an `area.json` manifest and `resolve`
  returns the nearest one whose area contains the target.
- Pipeline: PDAL crops, classifies, colours from Mapbox imagery, reprojects to
  ECEF → py3dtiles writes `.pnts`. It runs in the `backend/lidar` Docker image
  (`docker build -t avtac-lidar:dev backend/lidar`), whose default command is
  the build service. `lidar/build.py` is the single build path; the service
  (`lidar/worker.py`), the CLI (`python -m lidar`) and `tools/build_lz.py` all
  go through it.
- **Builds happen on first view, or in the background when an LZ is saved.**
  The service builds one at a time from a bounded queue — builds someone is
  watching first (polled in the last 15 s), then the rest oldest first — stages each under `.staging-<key>` and moves it into place
  only when complete. It prefers the downloaded collection when that covers
  ≥95% of the area and falls back to AWS otherwise, so an LZ near the edge of
  the download is never built with a side missing. Measured from AWS with
  level-18 imagery: 137 s at a 500 m radius.
- **Only wanted builds run.** A build nobody has polled for 90 s is dropped,
  queued or mid-run; closing the 3D window drops it at once, and a reloaded
  page releases the previous load's builds (ids in `sessionStorage`). Saved
  LZs pass `keep` and always finish. Each browser tab is one opaque watcher.
- **Point colour comes from Mapbox at level 18**, not 20: level 20 tiles are
  level 18 enlarged (measured at five places), and fetching 16× the tiles one
  at a time was most of a build — 500 m took 707 s at level 20, 137 s at 18,
  with identical colours.
- 3D Tiles stream by level of detail, so a large area costs build time and
  disk, not browser memory. `--context` adds a thinned landscape ring without
  thinning the landing area.
- Individual tree detection (`filters.litree`) has been validated on Ellijay
  closed-canopy hardwood: 1,082 crowns over 9 ha, median crown radius 4.4 m, no
  merged blobs. It finds the dominant canopy, not every stem. Run it on points
  thinned to 1–2 m — it is superlinear.

---

## 14. Lessons — things that have gone wrong here

- **Codex works this checkout in parallel.** A file that changed under you, or
  an edit whose target text vanished, is probably concurrent work. Re-read and
  verify; never revert what you didn't write.
- **Never invent a value for "no data".** Three separate 3D bugs came from
  filling gaps with sea level, `null`, or flat ground — each looked like a
  rendering fault. Spread real neighbouring data, or refuse.
- **Check the whole set, not a sample.** A 1,731-tile download was once
  declared worthless from its first two lines; it was 87% the good survey.
  Count before concluding.
- **Rewrite files by editing, not by overwriting.** `frontend/.env.example`
  was once replaced wholesale and lost `REACT_APP_GOOGLE_CLIENT_ID`.
- **The browser preview cannot composite WebGL.** Nothing about how the 3D view
  *looks* can be verified from an agent session — ask for a screenshot. In
  development, `window.__viewer3d.report()` dumps the scene's real state and
  `window.__viewer3d.tune({ size })` adjusts rendering live.
- **Retina tiles are 1024 px.** Mapbox `…/512/…@2x` returns 1024×1024; declare
  1024 in GDAL and Cesium or tiles silently fail.
- **LAS RGB is 16-bit.** Widen 8-bit colour by ×257 or points render black.
- **`.pnts` carry no Classification**, so Cesium styles on `${Classification}`
  match nothing. Colour is baked into RGB at build time.
- **API-relative URLs.** `REACT_APP_API_URL` already ends in `/api`; any URL the
  backend returns for the client must not repeat it.
- **Every backend import must be in `backend/requirements.txt`.** v1.7.0
  shipped importing `pyproj`, which the developer's machine had and the
  requirements file did not; production crash-looped on
  `ModuleNotFoundError` while every local test passed. Before a release, check
  new third-party imports against the file — or build the image
  (`docker build backend`), which is the only test that uses it.
- **Auth-gated responses are `Cache-Control: private`.** Flask's `max_age` alone
  emits `public`, which lets Cloudflare cache one user's response for another.

---

## 15. Known issues and debt

- `README.md`'s deployment section and project tree are stale (it describes
  Hugging Face Spaces and a `backend/src/` layout). This file is current.
- `backend/.env.example` is missing (§10).
- `tests/test_threat_qr_export.py` fails (pre-existing).
- No tests run in CI; `pytest` isn't a declared dependency.
- The Mapbox token is hardcoded in three places.
- Sessions are bearer tokens in `localStorage` with no server-side revocation;
  see `AUTHENTICATION.md` for the path to HttpOnly cookies.
- In-process stores block horizontal scaling (§9).
- `App.js` is large; new features should keep their state in a feature hook.

---

## 16. Maintaining this file

- Update it in the same PR as the change, when that change adds a feature,
  service, env var, route area, deployment step, or a lesson worth keeping.
- Record facts and why they hold. Leave out anything git history or the code
  already states plainly.
- If a section here disagrees with the code, the code wins — fix this file.
- Deeper detail belongs in the focused docs it links to: `AUTHENTICATION.md`,
  `backend/TERRAIN_DATA.md`, `backend/lidar/SERVER_SETUP.md`, `docs/USER_GUIDE.md`.
