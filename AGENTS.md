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
- **Native apps:** Android (Kotlin) first, then iOS and iPadOS (Swift). Started:
  the pure-Kotlin core modules and the golden fixtures exist (§17). Read
  `docs/NATIVE_APPS_PLAN.md` before any mobile work.

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
| **Threats** | `.ths` import/export, terrain-masking viewshed, KMZ, QR | `feature/threats/` | `routes/threat_routes.py`, `ths_export.py`, `threat_download_store.py`, `threat_template.ths` |
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
│  ├─ ths_export.py          Writes an AMPS .ths from the template (stdlib only, so fixtures can import it)
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
├─ android/                  Gradle project for the Android app (§17); pure-Kotlin core modules so far
├─ contracts/                Golden fixtures the web, backend and native apps are all tested against (§17)
├─ tools/                    Operator CLIs for LiDAR (find_lidar.py, build_lz.py)
├─ docs/                     USER_GUIDE.md; plans: INVITE_ONLY_LOGIN_PLAN.md, 3D_PLANNING_GRAPHICS_PLAN.md, NATIVE_APPS_PLAN.md
├─ .github/workflows/        release.yaml (semantic-release); android.yaml, contracts.yaml (path-filtered tests)
├─ AUTHENTICATION.md         Auth design, Resend setup, security posture
└─ backend/TERRAIN_DATA.md, backend/lidar/SERVER_SETUP.md
```

**Conventions.** Frontend features are self-contained folders with a
`use<Feature>.js` hook owning the state; `App.js` composes them. Backend areas
are Flask blueprints under `routes/`, each registered in `app.py`. Comments
explain *why*, not what — match the surrounding density.

---

## 6. API surface

All `/api/*` routes need a bearer JWT except the public auth flows and
`GET /api/config`. A `before_request` gate returns `403 affiliation_required` for
signed-in users who have not cleared the `.mil`/approval check.

The native apps send `X-EZPZ-Client: android/1.4.0 (212)` on every request
(`client_header.py`). Only a header of exactly that shape is kept, and it is
recorded on sign-in (`LoginEvent.client`, shown in the admin login history), so
the owner can see which app versions are in the field before changing an
endpoint. **Routes the apps call are additive-only** — a removed or retyped field
breaks an installed app nobody can update — and are described in
`contracts/openapi.yaml`, which `backend/tests/test_openapi_contract.py` holds the
responses to. A route not in that file is not yet something an app may rely on.

| Area | Routes |
|---|---|
| auth | `POST /api/auth/{login,register,google,verify-email,resend-verification,forgot-password,reset-password,mil/request,mil/verify}`, `GET /api/auth/me` |
| auth, native apps | `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/sessions`, `DELETE /api/auth/sessions/<id>`, `DELETE /api/auth/me` (account deletion) — design in `AUTHENTICATION.md` |
| terrain | `POST /api/analyze-field` (SAM), `POST /api/terrain-analysis` (slope), `POST /api/elevations` (planner/AMPS ground, Terrarium), `GET /api/terrain/heightmap/<level>/<x>/<y>`, `POST /api/terrain/heights` (3D ground + geoid, local DEMs) |
| location | `POST /api/convert-grid` (MGRS → lat/lon), `POST /api/convert-to-mgrs` (no longer called by the SPA, which converts lat/lon → MGRS itself with `utils/mgrs.js`) |
| weather | `GET /api/weather`, `POST /api/route-winds` |
| export | `POST /api/generate-excel`, `POST /api/export-package` |
| saved data | `/api/lz`, `/api/routes` (+ `/<id>/file`), `/api/pointsets` — CRUD, now with sync (below) |
| sync | `GET /api/sync/changes?since=&limit=` — everything of the caller's after a cursor, deletions included (`lz`, `route`, `pointset`, `aircraft`) |
| aircraft | `/api/aircraft-profiles` CRUD, `/<id>/template`. A user's own profiles sync like saved records; the master list does not |
| threats | `POST /api/threat-mask`, `POST /api/threats-ths`, `GET/POST /api/threats-kmz`, `POST /api/threats-kmz-link` |
| route share | `POST /api/route-share`, public `GET /r/<token>`, `/r/<token>/route.<kind>` |
| lidar | `POST /api/lidar/resolve` (reports `canBuild`), `POST /api/lidar/build`, `GET /api/lidar/build/<key>` (polling keeps it alive), `DELETE /api/lidar/build/<key>` (stop waiting), `GET /api/lidar/tilesets[/<key>[/<path>]]` — coordinates only ever in POST bodies; progress is read by opaque key |
| admin | `/admin/*` — session cookie, not JWT |
| app config | `GET /api/config` — **public**, cached 60 s: `minAppVersion` per platform, `maintenance`, which optional `services` are up, and the Mapbox public token (`app_config.py`, `routes/config_routes.py`) |
| health | `GET /` → JSON status (or redirect to `/admin/login` on the admin host) |

Regenerate this from the source of truth with `app.url_map` if it drifts.

---

## 7. Data model

`models.py`: `User`, `LocalCredential`, `AccountToken` (verification and reset
tokens, stored as SHA-256), `LoginEvent`, `AircraftProfile`, `SavedRoute`,
`SavedPointSet`, `SavedLZ`.

**Saved records sync across devices** (`sync_support.py`; rules in
`docs/NATIVE_APPS_PLAN.md`, "Sync and conflicts"). `SavedLZ`, `SavedRoute` and
`SavedPointSet` — and a user's *own* `AircraftProfile`s, not the admin's master list —
carry `client_uuid` (an identity the creating device chooses, unique
per user), `revision` (bumped by every change), `deleted_at`, `change_seq` and
`last_idem_key`, and a per-user `SyncCounter` hands out the order. Four things to
keep true when touching them:
- **A delete is a tombstone, not a `DELETE`.** The row stays so devices that were
  offline learn of it; its content is blanked at once. So **every query over these
  models must filter `deleted_at IS NULL`** (the route files and the admin counts and
  aircraft pages do), or a deleted LZ comes back. Only deleting the whole account
  removes rows — and an admin deleting a *master* aircraft profile, which nobody syncs.
- **An admin's edit of a user's data is a change too.** The dashboard edits, retires
  and deletes users' custom aircraft profiles, so `admin_routes` stamps them
  (`_commit_profile_change`, `sync.tombstone_aircraft`); otherwise a device would
  overwrite the admin's edit, or never hear of a deletion. A new admin action on
  synced data needs the same.
- **The cursor is a counter, not a timestamp**, taken under a row lock
  (`next_seq`), so a lower number never commits after a higher one and a device
  cannot miss a change. Don't "simplify" it to `updated_at`.
- **Everything is additive for the web.** A request with no `If-Match`,
  `Idempotency-Key` or `client_uuid` behaves as it always did (last writer wins);
  `tests/test_sync.py::WebStillWorksTests` and `tests/test_aircraft_sync.py::WebStillWorksTests` pin that.

**There is no migration framework.** `db.create_all()` creates tables;
new columns on existing tables are added by guarded `ALTER TABLE` statements in
`app.py`, and `schema_sync.sync_table_columns()` diffs `AircraftProfile`,
`LoginEvent`, `AccountToken` and the three saved-record models against the live
schema (`LoginEvent.client`, the refresh-token columns and the sync columns were
added that way, each with a test against a pre-existing table).
`schema_sync.ensure_unique_index` adds the `client_uuid` indexes the same way,
because `create_all` never adds an index to an existing table. When you add a column, add it one of these
ways — and quote `"user"`, which is reserved in Postgres.

JWTs carry an `sv` (session version) claim; a password reset bumps it, which
revokes older tokens. Tokens live 24 h in `localStorage` (`auth_token`). A native
app's token also carries `sid` (its device session) and is refused the moment that
session is signed out: `token_revocation.is_revoked` is the one check, used by
`app.py` and the tests alike.

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
| **Mapbox** | Basemaps, LZ-card imagery, point-cloud colour | `mapStyles.js`, `export_service.py`, `lidar/mapbox_imagery.xml` | public `pk.` token — **hardcoded in all three**; restrict it by URL in Mapbox. `/api/config` also serves it to the native apps (`MAPBOX_PUBLIC_TOKEN`, else the export one), `pk.` only. **A URL-restricted token may refuse a native app**, which sends no web referrer: confirm with the owner before relying on it, and give the apps their own token via `MAPBOX_PUBLIC_TOKEN` if it does |
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
| `GOOGLE_CLIENT_IDS` | Comma-separated extra Google client IDs the server accepts as an ID token's audience: the Android and iOS apps each have their own. Combined with `GOOGLE_CLIENT_ID`. A native sign-in fails with `invalid_google_token` until its client ID is here. |
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
| `MIN_APP_VERSION_ANDROID`, `MIN_APP_VERSION_IOS` | Oldest supported native app version, `X.Y.Z`, served by `/api/config`; the app shows "Update required" below it. Unset = no minimum. A malformed value is ignored and logged, never served. |
| `MAINTENANCE_MESSAGE` | Non-empty turns maintenance on in `/api/config` and is the notice the apps show (500 chars max). |
| `MAPBOX_PUBLIC_TOKEN` | The Mapbox token `/api/config` hands the native apps, so it can rotate without an app release. Must start `pk.`; anything else is dropped. Default: the token in `export_service.py`. |

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
cd android;  .\gradlew.bat test               # Kotlin core modules, against ../contracts
python contracts/scripts/mgrs_fixtures.py check   # MGRS fixtures vs PyGeodesy (~20 s)
```

- `tests/test_threat_qr_export.py` **fails and predates current work**
  (SQLAlchemy app registration). Report it; don't "fix" it as a side effect.
- The geoid test skips without `PROJ_NETWORK=ON` — PROJ returns heights
  unchanged, and *reports success*, when grids are missing.
- **CI runs only two test sets:** `android.yaml` (the Kotlin modules) and
  `contracts.yaml` (the fixtures, on the web and PyGeodesy sides), each only when
  its paths change. Backend pytest and the rest of the web's Jest suite still run
  nowhere but on your machine.
- `frontend/package-lock.json` is out of sync under npm 10 (`npm ci` reports
  `Missing: yaml@2.9.1`), so install with `npm install` and restore the lockfile
  (`git checkout frontend/package-lock.json`) if you only needed `node_modules`.
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
  semantic-release: it reads `feat` (minor) and `fix`/`perf` (patch) commits —
  `refactor`, `docs`, `chore` and `test` release nothing — and a major only from
  a `BREAKING CHANGE:` footer. **A `!` alone (`feat!: …`) releases nothing at all**
  under this config (checked against the installed commit-analyzer; six `feat!:`
  commits in history did not produce a 2.0). It bumps
  `frontend/package.json` and `backend/version.py`, writes `CHANGELOG.md`,
  tags, publishes a GitHub release, and commits
  `chore(release): X.Y.Z [skip ci]` so Vercel skips a second build.
- Vercel deploys `main` to production and builds a preview for every PR. The
  Fly backend is deployed **by hand**; Coolify deploys itself on push.
- **App commits carry a scope:** `feat(android): …`, `fix(ios): …`, and
  `feat(contracts): …` for the fixtures. `.releaserc.json` gives `android` and
  `ios` `release: false`, so app work never bumps the web version; `contracts`
  and `backend` still release because they change what the server or web ships.
  A commit that touches both (`feat(backend,android): …`) releases. commitlint
  lists these scopes and *warns* on an unknown one rather than failing.
- **Vercel previews for app-only PRs** are skipped by an *Ignored Build Step* set
  in the Vercel project settings, not in the repo: a command that exits 0 when
  nothing under `frontend/` changed, e.g.
  `git diff --quiet HEAD^ HEAD -- .` (owner to apply; check it against a
  multi-commit PR before relying on it).
- Agents do not push store builds. Signing keys never enter the repo (`*.jks`,
  `*.keystore`, `*.p12` are gitignored).

---

## 13. Domain knowledge

**Coordinates.** Crews work in MGRS. Lat/lon → MGRS runs in the browser
(`utils/mgrs.js`: Krüger-series transverse Mercator, matched against the
backend's PyGeodesy at 5,649 points worldwide — `contracts/fixtures/mgrs`, run
by Jest — every one identical) — the cursor readout converts on every mouse
move, so never call the API for it.
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
- **Imagery colour needs the points in Web Mercator.** PDAL's
  `filters.colorization` looks each point up at its own coordinates and does
  no reprojection. AWS data is already Web Mercator; the downloaded collection
  is Albers, and Albers coordinates for Georgia read as Web Mercator are in
  Nigeria — the first collection build came out savanna-tan.
  `pipeline.build` now moves non-Mercator points over first. Test the
  collection path with real compound WKT (AWS points re-saved as Albers LAS
  1.4 reproduce it), not only AWS builds.
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
  `ModuleNotFoundError` while every local test passed. Behind it was a second
  failure of the same kind: SQLAlchemy was not pinned, a fresh build pulled
  2.1, and 2.1 makes a bare `postgresql://` use psycopg 3, which the image
  does not ship. Pin what the code depends on (SQLAlchemy is now pinned, and
  `database_url.py` names the driver). Before a release, build the image and
  boot it against a Postgres container — local runs use SQLite and cannot
  catch either failure.
- **Never `import` Cesium through webpack.** Its source reads `import.meta`,
  which CRA's webpack leaves in a non-module chunk: a SyntaxError that broke
  every production 3D view in v1.7 while the dev server worked. Cesium loads
  from its prebuilt bundle in `public/cesium` (`viewer3d/cesiumSetup.js`).
  Check 3D changes against a production build (`npm run build`, serve
  `build/`), not only `npm start` — and a build warning that mentions
  `import.meta` is a failure, not noise.
- **Coolify rewrites Dockerfiles.** It inserts `ARG` lines after every line
  starting with `FROM` — case-insensitively — so a Python `from x import y` at
  the start of a line inside a multi-line `RUN` gets ARGs spliced into the
  command ("unknown instruction"). A plain `docker build` passes; only Coolify
  fails. `tests/test_dockerfiles.py` guards it.
- **A Kotlin `@Test` that returns a value is never run.** `fun x() = runBlocking { …; assertThrows<E> { … } }`
  returns the exception, and JUnit answers with a *warning* ("must not return a value. It will not be
  executed.") and moves on: the test is counted and never runs. Two live-server tests were dead for a while
  before a mutation that should have failed them survived. Write `= runBlocking<Unit> { … }`. The convention
  plugin now sets `junit.platform.discovery.issue.severity.critical=WARNING`, so one fails the build.
- **Mutation-testing a Python file: kill the bytecode.** `.pyc` files are validated by the source's mtime (to
  the second) and size. A harness that copies a file, writes a same-length mutation within the same second,
  runs the tests and moves the copy back leaves the *mutated* bytecode looking valid for the restored file. Tests
  then fail for no visible reason, and later "killed" results are not trustworthy. Run mutations with
  `python -B` / `PYTHONDONTWRITEBYTECODE=1` and delete `__pycache__` after.
- **Auth-gated responses are `Cache-Control: private`.** Flask's `max_age` alone
  emits `public`, which lets Cloudflare cache one user's response for another.

---

## 15. Known issues and debt

- `README.md`'s deployment section and project tree are stale (it describes
  Hugging Face Spaces and a `backend/src/` layout). This file is current.
- `backend/.env.example` is missing (§10).
- `tests/test_threat_qr_export.py` fails (pre-existing).
- CI runs only the Kotlin modules and the fixtures (§11); backend pytest and
  most of the web's Jest suite run nowhere but locally, and `pytest` isn't a
  declared dependency.
- Web behaviour the native ports reproduce on purpose, because the web is the
  reference and the fixtures pin it. Each is a candidate to fix on the web
  *first* (then regenerate the fixtures): `looksLikeCoordinateText("34S")` is
  false, since its "no other letters" test also matches `E` and `S`;
  `normalizeProfile` gives an empty profile the `generic` icon but no profile the
  UH-60L icon, and would turn a `null` number into 0 (unreachable: the API's
  columns are NOT NULL); `feat!:` commits release nothing (§12).
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
  `backend/TERRAIN_DATA.md`, `backend/lidar/SERVER_SETUP.md`, `docs/USER_GUIDE.md`,
  `docs/NATIVE_APPS_PLAN.md`, `android/README.md`, `contracts/README.md`.

---

## 17. Native apps

The plan, decisions and phases are in `docs/NATIVE_APPS_PLAN.md`; this is what
exists and the rules for working on it.

**Layout.** `android/` is a Gradle project (Kotlin 2.4, Gradle 9.8, Android Gradle
Plugin 9.4, JDK 17 toolchain); `contracts/` holds the golden fixtures and the
design tokens. iOS is not started.

| Module | What it is | State |
|---|---|---|
| `core-model` | Domain types in the web's saved-JSON shape (`LatLon`, `Mgrs`, `AircraftProfile`, route plan and result, and the saved LZ `Diagram` with its normalizer and `Workspace`) | done for these. Graphics stay opaque JSON, so a field a newer web release adds survives |
| `core-geo` | MGRS both ways, free-text coordinate parser, great-circle distance and course | done |
| `core-planning` | Aircraft geometry, capacity, separation, profile lookup, route planner, plan defaults and migration | done |
| `core-formats` | Reads an AMPS `.msnx` into a `Mission`, an `.LPS` into a `LocalPointSet` and a `.ths` into `Threat`s, as the web's `parseMsnx` / `parseLps` / `parseThs` do, with a small read-only SQLite reader of its own (`SqliteReader`). `ThsExport` gives the rows of a `.ths` export. **Not ported**: writing or mutating a `.msnx` (`createMsnx`, `mutateMsnx`), and *writing* a `.ths` file itself, which is the platform's job (copy `threat_template.ths`, insert `ThsExport`'s rows) | readers and export rows done |
| `core-network` | The API client over OkHttp: one transport (`ApiClient`) with the session behind it, typed calls for the routes in `contracts/openapi.yaml`, DTOs, the request-priority gate, and the "update required" check. See *The API client* below | client, auth, refresh, sign-up, verification, password reset and the `.mil` gate done; the web-share routes are not yet typed |
| `core-sync` | The sync engine: local edits into an outbox, a push in order, a pull by cursor, conflicts kept side by side. Pure logic over a `SyncStore` interface (`RoomSyncStore` in `core-data` implements it for the app; `InMemorySyncStore` here). LZs and custom aircraft profiles sync; routes and point sets join when their API is typed (the engine passes over their changes, and the cursor must be reset to 0 when it learns them). Its `testFixtures` (the scenarios as plain functions, the fake server, a `Device`) are shared with `core-data`. See *The sync engine* below | engine done |
| `core-data` | Android library: the Room database (`EzpzDatabase`, version 1), `RoomSyncStore`, `AccountScope` (whose plans are on the device) and the encrypted session store (`EncryptedSessionStore` over a `SecretBox`; `KeystoreSecretBox` is the Android Keystore one), plus the Hilt module. One generic `record` table keyed by (kind, uuid) instead of the plan's table per domain, because the engine treats every kind alike; add a column when a screen needs one. Schemas are exported to `core-data/schemas/` and **committed** (a migration test reads them) | store done and held to the same scenarios as the in-memory one; repositories for the screens, routes and point sets not yet |
| `core-testing` | Reads `contracts/fixtures`; JSON comparison with a tolerance. Test support only, not in the plan's module list | done |
| `core-designsystem` | Android library: the theme (dark, light and the red-shifted **night** palette), type, and `Tokens`, which is **generated** from `contracts/tokens/tokens.json` by `contracts/scripts/tokens.py` (CI checks it is current). A test holds every palette to WCAG contrast, because nothing in an agent session can look at a screen | theme and tokens; shared components (sheet, inspector, readout pill) join as screens need them |
| `feature-auth` | The sign-in screens: sign in, register, "check your inbox", verify and reset (from an emailed link), forgot/resend, and the `.mil` gate (`AffiliationHost`). Stateless screens (`AuthContent`, `AffiliationContent`) over two view models; talks to the server through an `AuthApi` seam. Mirrors the web's `AUTH_MODES` | done; Google sign-in is the app's (below) |
| `feature-map` | The 2D map: MapLibre Native (the SDK directly, not `maplibre-compose`, which is 0.x and would hide the handles a planning map needs), the three base maps under the web's ids (`satellite`, `topo`, `vfr-sectional`), the crosshair readout (MGRS first, computed on the device), search by grid or coordinate, GPS from the platform's own receiver, and `MapViewModel`. The logic that decides *what* to ask the map (styles, readout, search, GPS state, where the camera goes) is plain code and tested; the MapLibre glue (`MapHost`, `EzpzMap`, `GpsOverlay`) needs a GPU and is compile-verified only | base map, readout, search, GPS; no graphics yet |
| `app` | The application: Hilt, Compose, the manifest and its security settings, and the shell: `Gate`/`gateFor` (what stands between the person and the app), `AppViewModel`, WorkManager sync (`SyncScheduler`, `SyncWorker`). See *The app module* below | the shell, the auth flow, the Google sign-in glue and the map as the root (with a bottom sheet that holds only the version and sign-out so far) |
| everything else in the plan (`core-data`, `core-symbols`, `core-packs`, `feature-*` …) | later work | **not started** |

Package root is `app.ezpztac.*` (the reverse of `ezpztac.app`). The Android
`applicationId` is not chosen and is permanent once published: ask the owner.

**The web is the reference; the fixtures are the contract.** A planning formula
changes on the web first (or in the same PR), then `contracts/` is regenerated
and the diff reviewed, then every client follows. Never hand-edit a fixture and
never "fix" a number in a client alone. `contracts/README.md` has the commands.

**Porting rules that bit once already** (each is in a test):
- JavaScript `Math.round` rounds halves *up*; Kotlin's `round` goes to even.
  `RouteCalc.jsRound`. The fixtures include 0.5, 2.5 and 60.5 s for this.
- JavaScript `toFixed` rounds the exact binary value; Java's `%.nf` rounds the
  shortest decimal (`1.005` → `"1.01"` in Java, `"1.00"` in JS).
  `CoordinateParser.toFixed`.
- JavaScript's `\s` and `trim()` include no-break and Unicode spaces and the BOM;
  Java's `\s` does not. Don't use `String.format` for user-visible digits (device
  locale can emit non-ASCII digits).
- A regex character class that reads as four characters may be a range:
  the web's `[‐-―−]` is U+2010–U+2015 plus U+2212. Take code points from the
  source, never retype them.
- PyGeodesy's `Mgrs.toStr(prec=…)` counts digits *beyond* 1 m; its inverse
  returns the square's *centre*, folds longitude into ±180, and accepts any even
  digit count.
- A saved diagram is loosely typed JSON the web has written for many releases, and
  `normalizeLzDiagram` leans on `??`, truthiness and `Number()`. `JsValue` ports
  those; strict types would reject documents the web opens. Quirks kept on
  purpose: a blank-but-present `mgrs` (`"  "`) stays untrimmed; a present but
  non-array `pzMarkers` is empty and does *not* fall back to `pzMarker`.
- **`.LPS` and `.ths` files are read by our own SQLite reader, not the platform's.** The plan
  (`docs/NATIVE_APPS_PLAN.md`) says platform SQLite; the pure reader won because it is the same
  code path as the web's (so the same files read the same), it runs in JVM tests, it copies
  nothing to a temp file, and it never touches a virtual table (the `.ths` template has one,
  `@INFO_SCHEMA_COLUMNS`). The price is that it is ours to keep correct, so it is held to **SQLite
  itself**: `contracts/fixtures/sqlite/tables.json` is every table as Python's `sqlite3` reads it, and
  both the web reader and `SqliteReader` must return it. That caught a real bug in the web's reader
  that its one real sample file never hit: any negative integer stored in 1–8 bytes was read 256 (or
  65536, …) too small (`readBigIntBE` subtracted the sign twice). A whole-number REAL such as a
  longitude of -86.0 or an elevation of -12 ft is stored as an integer, so it was wrong. Fixed in the
  web in the same change. A reader needs a reference that is not itself.
- `SqliteReader` also takes a file it does not trust: pages and cells are bounds-checked, a page
  can be reached once (a b-tree that loops is refused, not followed), nesting is limited, and a
  damaged file ends in `SqliteException`. `SqliteHostileFileTest` cuts and corrupts the real
  fixtures and builds hostile ones (`MiniSqlite`, a test-only writer whose output SQLite itself
  verified). A refusal test asserts the *reason*: a fault caught only by a later check would
  hide a missing earlier one (mutation runs showed seven such survivors).
- Where the Kotlin readers deliberately differ from the web (each has a test, none is in a web
  fixture): a threat with a NULL or non-numeric position is skipped (the web puts it at 0°, 0°);
  a radar value that is not a number takes the radar type's default (the web carries NaN); a point
  with NaN coordinates is skipped; only an `INTEGER PRIMARY KEY` column is filled from the row id
  (the web fills any NULL first column); column names are matched without regard to case;
  UTF-16 databases read (the web assumes UTF-8). These are web bugs or gaps, listed for the owner.
- The `.ths` exporter writes the colours of a radar's bands with a quirk worth knowing: with fewer
  than three bands the missing colours are `[1, 3, 5]` appended *after* the ones given, so two bands
  leave the third colour as 1, not 5 (`backend/ths_export.py`). The web always sends three bands, so
  it never shows. `ThsExport` reproduces it, because the fixture is what the backend writes today.
- Text is cut to the AMPS column widths by *characters* (code points), not UTF-16 units, so an emoji
  at the boundary is never split.
- A `.msnx` comes from whoever sent it, so `MsnxReader` treats it as hostile: it
  reads only the parts it needs, bounds each one's inflated size (an archive that
  expands past the limit is refused, not held in memory), and rejects any
  `<!DOCTYPE` or `<!ENTITY` by *text*. The parser feature flags are best effort —
  Android's XML runtime does not know all of them — so the text check is the one
  that must hold, and `MsnxWithoutParserHardeningTest` runs it with the flags off.
  A refusal test must use an otherwise well-formed document: a DOCTYPE placed
  before the XML declaration is refused for being malformed, and the test then
  passes whether the check exists or not (this one did, until a mutation showed it).
- AMPS values keep the web's arithmetic: metres to feet with 3.28084 (not
  3.280839895 — the half-foot cases land differently) then `Math.round`; the
  `.msnx` per-point plan values mean "to this point" (§13).
- `-Xjdk-release=17` is set so a JDK 21-only API fails to compile instead of
  failing on CI or a device.
- Fixtures must not depend on the clock or a random id (`contracts/README.md`).
  One did once — a case without an id baked a random one in — and only the
  Kotlin side noticed. A fixture build now throws if it reaches for either.

**The API client** (`core-network`). The server *spends* a refresh token each time it is used, so the
client is built around not losing one:
- **Persist before use.** The new refresh token is written to the `SessionStore` before the refused call is
  repeated with the new access token. A crash after the response and before the write would still lose it,
  which is why the server forgives a repeat for 30 s (`REUSE_GRACE`) and why the client never repeats later.
- **One refresh for many calls.** A mutex plus "did someone already refresh?" (the stored access token is no
  longer the one that was refused). Ten calls refused at once make one refresh.
- **A started refresh is finished** (`NonCancellable`), even if the screen that asked has gone: a response
  with nowhere to be stored has spent the token for nothing.
- **A refresh whose answer may have been lost is repeated with the same token**, with short pauses, inside
  20 s, and each attempt gives up on a silent server after 8 s. The grace period runs from when the server
  spent the token, so waiting out a 60 s read timeout before asking again would arrive too late and sign the
  device out. A failure *before* the request was sent (no route, refused connection) is not repeated: it cannot
  have changed anything.
- **A 401 with a `code` is the server's own answer, not a refused token.** Token refusals come from the JWT
  library with a bare `{"msg": …}`; the server's own 401s (`invalid_credentials`, `reauthentication_required`)
  carry a `code`. Treating every 401 as "refresh" would have signed a user out for mistyping the password when
  deleting their account. (Found by a test against the recorded real response.)
- **Heavy work first** (`PriorityGate`, the web's `PRIORITY_PATHS`): background calls (sync pulls) wait while an
  analysis, viewshed or export is in flight, because the server runs on one interpreter. Heavy calls get a
  190 s read timeout (the server's own is 180 s).
- **Tried against the real server.** `LiveServerTest` runs the client against the real Flask routes in a child
  process (`backend/tests/live_server.py`: the production blueprints, JWT revocation and affiliation gate over a
  throwaway SQLite database, access tokens that live 2 s so a lapse and its refresh are real, and test-only
  routes to make an account and to move the clock past the 30 s grace period). It proves what the mock tests
  cannot: a lost refresh answer repeated with the same token is accepted, one repeated after the grace period
  signs the device out, signing a device out from another ends it at once, two devices editing one LZ get a
  conflict. It runs when `EZPZ_LIVE_PYTHON` names a Python with the server's packages (CI sets it) and is
  skipped otherwise; the Gradle test task lists `backend/**/*.py` as an input, so editing the server re-runs it.
- **Tolerant on the way in, strict in the tests.** The client ignores a field a newer server adds (store
  builds stay in the field for months); `DtoFixtureTest` decodes every recorded real response with
  *unknown fields forbidden*, so a type cannot fall behind the server. The recordings are written by
  `backend/tests/test_network_fixtures.py` (`UPDATE_CONTRACTS=1`), from the server's own code.
- **Account flows** (`register`, `verifyEmail`, `resendVerification`, `forgotPassword`, `resetPassword`, `requestMilCode`,
  `verifyMilCode`, `refreshUser`): the pre-sign-in ones carry no token; the `.mil` ones do (the gate lets them through). The
  server's answer to sign-up, resend and forgot-password is deliberately the same whether or not the address has an account, so
  a screen may say no more than "if the address is eligible…". `verifyMilCode` and `refreshUser` **update the stored user and
  `state`** (under the refresh lock, and never resurrecting a signed-out session), because the app gates on `user.accessOk`
  and an admin can approve access at any time. `LiveAccountTest` runs the whole journey against the real routes: the live
  server (`backend/tests/live_server.py`) replaces only the mail call, keeping what it was asked to send, and serves it at
  `/__test__/email`.
- **Every recorded response of a route in `contracts/openapi.yaml` is checked against it** while the fixtures are recorded
  (`test_network_fixtures.py`), including that its status is documented: a status or a body the spec does not cover fails there
  first. The JWT library's own refusals (`{"msg": …}` 401/422) and the affiliation gate's 403 are cross-cutting and described
  once, not per route.
- Failures are typed: `NetworkException` (with `requestMayHaveBeenSent`), `SessionEndedException`,
  `AffiliationRequiredException`, `RevisionConflictException` (carries the server's copy),
  `RateLimitedException`, `ApiException`.

**The sync engine** (`core-sync`; rules in the plan's "Sync and conflicts", server side in `backend/sync_support.py`):
- **Nothing is overwritten, ever.** An edit is sent with the revision it was based on; a server that has moved on answers 409
  with its copy, the record *becomes* the server's copy, and what was changed here is kept as a record of its own — "NAME (from
  this device, 14:32)" — which is also uploaded, so the work is not only on one phone. The user then picks keep mine, keep theirs
  or keep both (`resolve`). Deleted here and edited there: the edit wins (the record comes back). Edited here and deleted there:
  the work is kept and re-created under a *new* uuid, because the server holds the old one as a deletion.
- **A send is a snapshot until it is answered** (`Attempt`: key, base revision, content). A send whose answer was lost may have
  been applied, and the only safe move is the *same* request under the same idempotency key, which the server answers instead of
  refusing. Sending the record's newer content under a new key on the old revision conflicted with the device's *own* earlier
  write (found by a test, now the `lost update answer` tests, on the fake and on the real server). So the old write is settled
  first and the newer content goes on top as a fresh write. A `delete` keeps an unconfirmed `UPDATE` queued for the same reason,
  and keeps a `CREATE` that may have reached the server (otherwise the record comes back at the next pull).
- **A pull never overwrites a record that has changes of its own**; the push finds out (409) and keeps both. The page and the
  cursor are applied in one store transaction.
- **A failure that proves the request was never applied** (no route, a 401, a 429, the gate) is not counted as an attempt.
- **Both the fake and the real server run the same scenarios** (`SyncScenarios`: two devices, conflicts, restore/recreate, a fresh
  device pulling everything, for both LZs and aircraft profiles). The fake (`FakeServer`) exists for failures the real one will
  not produce on demand; running the scenarios on the real server is what keeps the fake honest.
- **Two stores, one set of scenarios.** `ScenarioBook` (in `core-sync`'s `testFixtures`) holds the scenarios as plain suspend
  functions, so JUnit 5 runs them against the fake and the real server (`ScenarioSuite`) and JUnit 4 under Robolectric runs them
  with every device's records in Room (`RoomScenarioTest`). `RoomSyncStoreTest` says *why* they agree: ordering, rollback,
  never reusing an outbox number, a document coming back byte for byte. Mutation runs found the first draft's gaps (two tests
  used uuids that happened to sort the same way as insertion order).
- **Room traps.** `@Insert(onConflict = REPLACE)` deletes and re-inserts, which moves an edited record to the end of the list:
  use `@Upsert`. The Room KSP argument is `room.schemaLocation` (`room.schemaDirectory` is the *Gradle plugin's* name and is
  ignored here, with only a warning).
- Not done: WorkManager scheduling (`RetryPolicy.delayMillis` says how long to wait), routes and point sets, the conflict
  screen, and the 14-day "sign in again" rule.

**The app module and the Android build.**
- **Convention plugins** in `android/build-logic`: `ezpz.kotlin-library` (pure modules), and for Android
  `ezpz.android-library` / `ezpz.android-application` (both apply `ezpz.android-common`), plus `ezpz.android-compose` and
  `ezpz.android-hilt`. A module applies these and never names AGP, KSP or Hilt itself — build-logic owns those on its
  classpath, and naming them again with a version fails. **Lint and Kotlin warnings are errors**, so a deprecation fails
  the build (that is how the old `createComposeRule` was caught: use `androidx.compose.ui.test.junit4.v2`).
- **AGP 9 compiles Kotlin itself**: no `org.jetbrains.kotlin.android` plugin. It needs Gradle 9.1+, and Gradle's own
  Kotlin plugin still builds the pure modules under the same Kotlin version.
- **`compileSdk` is 37**, not 36: Compose 1.12 (the BOM) requires it, and the AAR metadata check fails the build
  otherwise. `targetSdk` stays 36 until 37 behaviour is tested. `minSdk` 29.
- **`versionName` is the product's version**, read from `frontend/package.json` (semantic-release writes it, and
  `android`-scoped commits never bump it). `versionCode` is `GITHUB_RUN_NUMBER`, or 1 locally.
- **`applicationId` is a placeholder** (`app.ezpztac.unreleased`, `.debug` for debug builds). A release build is refused
  *before any work* unless `-Pezpz.applicationId=<id>` is passed, because the id is permanent once published and the owner
  has not chosen it. R8 release builds work (verified with an example id).
- **The server URL** is `-Pezpz.apiUrl=<root>`, default the self-hosted backend. It is the server **root**: the typed calls
  carry their own `/api` paths, unlike the web's `REACT_APP_API_URL`, which ends in `/api`.
- **Security settings in the manifest** (plan, "Security, privacy and distribution"): HTTPS only with the platform's
  certificate checks and no pinning (`network_security_config.xml`; debug builds also allow cleartext to the emulator's
  host loopback `10.0.2.2`), `allowBackup=false` and extraction rules that exclude everything, so LZ locations never reach
  a consumer cloud.
- **Robolectric** runs the Android modules' unit tests on the JVM, so no device or emulator is needed. JDK 17+ closes
  packages it reaches into, so the common plugin passes `--add-opens` for them; a "FileDescriptor internals" error means
  a package is missing from that list.
- **Looking at a screen.** There is no emulator, but Roborazzi draws Compose on the JVM under Robolectric: run a screenshot test with
  `./gradlew :<module>:testDebugUnitTest -Pezpz.screenshots` and the pictures land in `<module>/build/screenshots/*.png` — open them
  with the Read tool. (`-Pezpz.screenshots` also forces the tests to run rather than come from the cache.) The first picture of the
  shared components found a blue "Retry" on an amber banner and a busy button that turned grey and hid its own spinner. What cannot be
  seen this way: OpenGL (the 3D view), MapLibre, animation, and the system's own windows (the Google account sheet).
- **The shell's rules** (`Gate.kt`, `AppViewModel`; each has a test, and mutation runs killed every one of them): an app below the
  server's minimum version is stopped *before* anything else, even sign-in; **maintenance is a banner, never a block**, because planning is
  local; no config at launch (no signal) blocks nothing; a device that has not heard from the server for **14 days** (`OfflineGrace`) must
  sign in again, and its plans stay; **the plans on a device belong to one account** (`AccountScope`): a different person signing in is
  shown nothing of them, nothing is uploaded under their account, and they choose between clearing them (told how many changes never
  reached the server) and signing out. The plan did not say this, and it matters: without it a second user on a shared device would see
  and sync the first user's LZs.
- **Links from emails** (`AuthLinks`): the server's emails open `https://<site>/?auth=verify|reset&token=…`. The app takes only
  **https links to the site's own hosts** (`ezpztac.app`, `www.ezpztac.app`): any app on the phone can start the activity with any
  address, and a "reset" link from elsewhere would put the person on a screen that takes a token an attacker chose. The manifest's
  `autoVerify` filter opens the app for those links only once the site serves `/.well-known/assetlinks.json` for the package and its
  signing certificate — **an owner step** (it needs the applicationId and the signing key); until then the browser handles them.
  A link outranks every screen except "update required", and is dropped once the person is back at the sign-in.
- **Google sign-in** (`CredentialManagerGoogleSignIn`): Credential Manager, offered only when the build has
  `-Pezpz.googleClientId=<web client ID>` (the audience the server lists in `GOOGLE_CLIENT_IDS`); the app's own Android OAuth
  client (package + signing certificate) must exist in the same Google Cloud project. No test reaches it (it needs Play services and
  an account on a device).
- **The map** (`feature-map`): MapLibre's zoom is Leaflet's minus one (512-point tiles), so `metersPerPixel` is
  `78271.517 · cos(lat) / 2^zoom`; a saved diagram stores a base map *id*, never a zoom. Mapbox's `@2x` tiles are 512-point,
  1024-pixel images (`tileSize: 512`); the FAA chart is 256-point with native levels 8–12, and the map stretches what it has outside
  that. The public Mapbox token comes from `/api/config` and is **remembered** (`MapPreferences`, a plain preference: it is a public
  `pk.` token, and anything not starting `pk.` is never stored) so a launch with no signal still draws satellite; with none at all only
  the FAA chart is offered and a diagram keeps remembering *satellite* (`chosenStyleId`). **A URL-restricted token may refuse a
  native app** (no web referrer): confirm with the owner. Labels over the map (distances, headings) are meant to be **Compose overlays
  projected from lat/lon**, not map text, because a raster-only style has no glyphs; shapes are MapLibre layers and unit symbols
  bitmaps. GPS uses only the platform's `LocationManager` (no Play services, no third party) and nothing about a position is stored or
  sent; the last *camera* (where the person was looking) is kept in a preference, on the device only.
- **What cannot be seen here:** the map itself (no GPU: only the overlays are drawn, over a stand-in), and whether R8 strips something
  the libraries need at run time (a release build links and an unsigned APK is made, but nothing runs it). An early device run of a
  release build is the first thing to do.
- **Strings are inline English** in the composables, not yet in `strings.xml`; move them when a second language is wanted.
- **A destructive choice is never the prominent one**: on "plans from another account", *Sign out* is the primary button and
  *Clear them and continue* the secondary, with the cost stated above both.
- **Not verifiable here:** `KeystoreSecretBox` (Robolectric has no AndroidKeyStore; the box around it, `AesGcmBox`, and the store are
  tested), Google's account sheet, and WorkManager's real scheduling (the worker and the outcome mapping are tested). Both need a device run.
- **Building in an agent sandbox.** The cloud environment's network policy must allow `dl.google.com` (AGP, AndroidX,
  Compose, the SDK) and `jitpack.io`. The Android SDK is not in the image: download the command-line tools from
  `dl.google.com/android/repository`, accept licences, install `platforms;android-37.0`, `build-tools;37.0.0`, and put
  `sdk.dir=<path>` in `android/local.properties` (gitignored). The new session after a policy change is the one that gets
  it; a running session keeps the policy it started with. Maven Central also throttles a shared egress IP with 429s: Gradle
  caches what it fetched, so re-run, and give Gradle retries in `~/.gradle/gradle.properties` (not in the repo). There
  is no emulator (no KVM): what looks right on a screen cannot be checked, so ask for a screenshot. Never commit a build
  that was not compiled: say so instead.

**Rules that carry over unchanged:** unclassified only; threats are never sent
to the server; no secret in the app binary; no new dependency beyond what the
plan's owner-approved list covers (ask first for anything else).
