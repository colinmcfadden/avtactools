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
| **Threats are never persisted server-side or synced.** | They may be sensitive. The web keeps them in React state. Native apps may keep the owner-approved encrypted, no-backup device file for 48 hours after the last change; it is wiped at sign-out. Explicit viewshed/KMZ/QR actions may send coordinates to the existing backend transiently, but the server must not retain them. |
| **Super-admin is untouchable.** | The account in `SUPER_ADMIN_EMAIL` is always an active admin and cannot be demoted, suspended or deleted. |
| **Any `.mil` address clears the affiliation gate.** | `POST /api/auth/mil/request` + `/verify`. |
| **Agents do not deploy.** | Never push to `main`, run `fly deploy`, or trigger production. Supply the commands; the owner runs them. |
| **No new major dependencies without asking.** | Say what it buys and ask first. |
| **Secrets stay server-side.** | Nothing secret in the React bundle. Any secret exposed in a chat or log gets rotated. |
| **Don't commit data.** | `.env*`, `*.db`, `*.pt` (SAM weights), `/topo/`, `/data/tiles/`, LiDAR, generated tilesets are all gitignored. The one exception is `contracts/fixtures/sqlite/*.db`: test fixtures the contract tests read, which a clean checkout (CI) needs (`.gitignore` un-ignores them; they once failed 29 tests on CI because the ignore rule had hidden them). |

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

 └─ WebSocket (mission packs) ───► live service (backend/realtime image)
                                    LISTENs on the API's Postgres; asks the API who may see a pack
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
| **App frame** (menu redesign, `docs/MENU_REDESIGN.md`) | Top bar (workspace, Import, Library, account); a right dock of panels (LZ/PZ, Routes, Threats, Imports) picked from an icon rail, a bottom sheet below 1100 px; the shared dialogs, menus and toasts. No browser `prompt`/`confirm`/`alert` on these paths | `feature/shell/`, `feature/ui/` | — |
| **LZ/PZ workspace** | Several LZ diagrams per session; target by MGRS; switch between them in the LZ/PZ panel | `feature/lzWorkspace/`, `feature/shell/LzPanel.jsx`, `components/MapView.jsx`, `App.js` | — |
| **Terrain analysis** | SAM finds the LZ boundary in imagery; slope map from DEM | `feature/terrain/` | `routes/terrain_routes.py`, `terrain_provider.py` |
| **Planning graphics** | Helicopters, PZ markers, sectors of fire, go-arounds, doghouses, units, LZ box | `feature/{helicopters,pzMarker,sectorsOfFire,goAround,doghouses,unit}/` | — |
| **MIL-STD symbology** | MIL-STD-2525C symbols for units and threats (milsymbol) | `feature/symbols/` | — |
| **LZ card export** | Card image and Excel card | `feature/export/` | `routes/export_routes.py`, `export_service.py`, `lz_template.xlsx` |
| **Cloud save** | LZs, routes, point sets. First save names it (Save dialog), later saves are silent; Ctrl/⌘ S; the Library opens, renames, duplicates and deletes. Routes have no dirty flag of their own: a set is compared with what was last saved (`useRouteSaves`) | `feature/saveDialog/`, `feature/library/`, `feature/savedMaps/useSavedMaps.js`, `msnxImport/useSavedRoutes.js` | `routes/{lz_routes,saved_routes,point_sets}.py` |
| **Routes & AMPS** | Sketch and plan routes (speed/alt/wind/fuel/TOT); import/export `.msnx`; ForeFlight share | `feature/msnxImport/` | `routes/route_share_routes.py`, `route_share_store.py`, `/api/route-winds` |
| **Local points** | Import AMPS `.LPS` point files | `feature/localPoints/` | — |
| **Imports** | One way in for `.msnx`, `.LPS` and `.ths`: the Import menu, the Imports panel, or a drop on the map. A file's type is read from its content (zip = mission; SQLite with `Points` or `THREATS`), then a review dialog says where each goes (Library, this session, the open pack; threats only ever this session) | `feature/imports/` | — |
| **Threats** | `.ths` import/export, terrain-masking viewshed, KMZ, QR | `feature/threats/` | `routes/threat_routes.py`, `ths_export.py`, `threat_download_store.py`, `threat_template.ths` |
| **Weather** | METAR, NOTAMs, winds aloft | `feature/weather/` | `routes/weather_routes.py` |
| **Aircraft profiles** | Airframe drives map icon, separation, LZ capacity, planning defaults | `feature/aircraft/` | `routes/aircraft_routes.py`, `aircraft_seed.py`, `amps_package.py` |
| **Mission packs** *(no screens yet)* | A shared container of LZs, sketched route sets and point sets a team plans one operation in. Members edit it through a server-ordered stream of id-addressed operations, every change is logged, and the owner can finish it (read-only for everyone). Teams, email invites, and name search limited to teammates. Edits reach everyone through a live stream (a separate service), or by polling where it is not running. Design and rules: `docs/MISSION_PACKS.md` | `feature/missionPacks/` (the sync client `useMissionPack`; `packApi` for every route; invitation links; the editors kept in step with an open pack, `usePackLz` / `usePackRoutes` / `usePackPoints` on `usePackItemSync`: `docs/MISSION_PACKS.md` §5a; screens wait for the menu redesign's dock) | `routes/pack_routes.py`, `routes/team_routes.py`, `pack_ops.py`, `pack_support.py`, `realtime/service.py` |
| **3D LZ view** *(in progress, branch `feat/3d-lz-route`)* | LiDAR point cloud over DEM terrain and imagery, in Cesium. Opening it on an unbuilt LZ builds one automatically and shows progress. Visible routes draw at their planned MSL with curtains and labels (`docs/3D_PLANNING_GRAPHICS_PLAN.md`). A compass turns and tilts with the camera (heading in degrees true; click to face north) | `feature/viewer3d/` | `routes/lidar_routes.py`, `lidar_builder.py`, `terrain_tiles.py`, `backend/lidar/` (incl. `worker.py`), `tools/` |

Entitlement keys (`entitlements.FEATURES`): `lz_pz_tools`, `routes`,
`msnx_import`, `threats`, `cloud_save`, `exports`, `aircraft_profiles`,
`mission_packs`. A missing key means **enabled**, so new features default on,
except a feature in `entitlements.DEFAULT_OFF` (not launched yet: today
`mission_packs`, which ships with the menu redesign). That one is off for everyone
but admins and the testers an admin ticks it for, and only a tick is ever stored,
never an "off" (`features_from_form`), so launching it is removing it from
`DEFAULT_OFF`: everyone then has it, people whose access an admin edited included.

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
│  ├─ pack_ops.py            What one mission-pack operation does (stdlib only; held to contracts/fixtures/packs)
│  ├─ pack_support.py        Mission packs: roles, the per-pack change order, shapes, account release
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
├─ docs/                     USER_GUIDE.md; plans: INVITE_ONLY_LOGIN_PLAN.md, 3D_PLANNING_GRAPHICS_PLAN.md, NATIVE_APPS_PLAN.md, MISSION_PACKS.md; HANDOFF.md (where the Android build stands, what is next)
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
| mission packs | `/api/packs` (+ `/<uuid>`, `/finish`, `/reopen`, `/duplicate`, `/ops`, `/events`, `/seen`, `/items…`, `/members…`, `/invites…`) — `docs/MISSION_PACKS.md` §4 |
| teams, invites, search | `/api/teams` (+ `/<id>`, `/members/<user_id>`, `/invites`), `/api/invites` (+ `/<id>/accept`, `/<id>/decline`, `/accept`), `GET /api/users/search?q=` (teammates only) |
| admin | `/admin/*` — session cookie, not JWT. `/admin/packs` and `/admin/teams` are read only and never show what a pack holds |
| app config | `GET /api/config` — **public**, cached 60 s: `minAppVersion` per platform, `maintenance`, which optional `services` are up, and the Mapbox public token (`app_config.py`, `routes/config_routes.py`) |
| health | `GET /` → JSON status (or redirect to `/admin/login` on the admin host) |

Regenerate this from the source of truth with `app.url_map` if it drifts.

---

## 7. Data model

`models.py`: `User`, `LocalCredential`, `AccountToken` (verification and reset
tokens, stored as SHA-256), `LoginEvent`, `AircraftProfile`, `SavedRoute`,
`SavedPointSet`, `SavedLZ`, and the mission-pack tables `Team`, `TeamMember`,
`MissionPack`, `MissionPackMember`, `MissionPackInvite`, `MissionPackItem`,
`MissionPackEvent`.

**Mission packs are stored apart from the library** (`docs/MISSION_PACKS.md`). Items
hold the library's JSON unchanged, but in their own table, so nothing about packs
touches the saved-record queries or the per-user sync feed. Three things to keep true:
every write to a pack takes its row first (`pack_support.lock`) and numbers its
events under it, so the log has one order; a finished pack refuses every edit
with 423, the owner's included; and deleting an account calls
`pack_support.release_account` first (both deletion paths do), because
`mission_pack.owner_id` deliberately has no `ON DELETE`.

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
| Live service (mission packs) | Coolify VM, beside the backend; **needs a public hostname** through the Cloudflare Tunnel (WebSockets) | Not yet deployed. A separate Coolify app, base dir `/backend/realtime`, port 8091; set `DATABASE_URL` and `REALTIME_API_URL` on it and `REALTIME_PUBLIC_URL` on the API. Without it clients poll |
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
| `REALTIME_PUBLIC_URL` | Where clients open the mission packs' live stream (`ws://` or `wss://`), handed out as every pack's `live_url`. Unset: clients poll. The service's own settings are at the top of `backend/realtime/service.py`. |
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

# Optional local login — prompts for a password and seeds only local SQLite.
# It refuses production signals and every non-SQLite database.
python dev_user.py pilot@local.ezpz.test

# Android emulator against that backend (10.0.2.2 is the host from the emulator).
cd ..\android; .\gradlew.bat installDebug "-Pezpz.apiUrl=http://10.0.2.2:5000/"

# Frontend — http://localhost:3000
cd frontend
npm install
npm start                                     # copies Cesium into public/cesium first
```

`.claude/launch.json` defines the `frontend` preview server.

**An app signing in against this backend needs an account in *this* database.** The local SQLite file (`backend/ezpz.db`) holds none of the
production accounts, and the login route answers "Invalid email or password." for a wrong password, an unknown address and an unverified one alike
(on purpose). `python dev_user.py you@example.com [--admin]` makes a verified, `.mil`-cleared account there; it refuses anything but a local SQLite
database. The Android procedure is in `docs/HANDOFF.md` §4. Run it from `backend/` with the Python the backend runs under; **the owner's venv is not
at `backend/venv`** (the block above is how to make one, not where it is), so give them a command that does not assume a path, or ask.

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
- **CI runs three test sets:** `android.yaml` (the Kotlin modules),
  `contracts.yaml` (the fixtures, on the web and PyGeodesy sides) and
  `backend.yaml` (the backend's contract tests: a named list of pytest files that
  need no SAM weights, terrain data or GPU), each only when its paths change. The
  rest of backend pytest and of the web's Jest suite runs nowhere but on your machine.
- **On a Windows checkout** (`core.autocrlf=true`), a text file compared byte for
  byte needs an `eol=lf` rule in `.gitattributes`, or the checkout's CRLF fails
  the comparison while CI passes. And Windows and Linux maths libraries can differ
  in a double's last digit, so a recorded number is compared with a tolerance
  (`test_network_fixtures.settle_floats`), never exactly.
- `frontend/package-lock.json` is out of sync under npm 10 (`npm ci` reports
  `Missing: yaml@2.9.1`), so install with `npm install` and restore the lockfile
  (`git checkout frontend/package-lock.json`) if you only needed `node_modules`.
- The build prints many pre-existing ESLint warnings; add none of your own.
- **Jest finds no tests in a Claude worktree** (`.claude/worktrees/<name>/`): CRA's `testMatch`
  globs skip paths with a dot directory. Pass the pattern yourself, e.g.
  `npx react-scripts test --watchAll=false --testMatch "**/.claude/**/src/contracts/*.test.js"`.
- `tests/test_realtime.py` needs `websockets` (`pip install -r realtime/requirements.txt`) and skips without it;
  `tests/test_realtime_live.py` also needs `psycopg2` and a local Postgres named by `EZPZ_LIVE_POSTGRES` (its docstring
  has the `docker run` line). `tests/live_server.py` takes `EZPZ_LIVE_DATABASE_URL` for that.
- `tests/test_network_fixtures.py` fails on the owner's Windows workstation before any change:
  the recorded `analyze-field` polygon differs there in the last digit or two of its floats.
  When regenerating `responses.json`, keep only the diff you meant.

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
- **After a mutation run, rebuild clean.** `org.gradle.caching` is on, and a restored source file can leave a *mutated* class in a
  module's outputs: a test then fails for no visible reason (one did: "expected NO DATA but was LANDING"). Touch the restored file
  (the harness does) and confirm with `./gradlew :<module>:clean :<module>:test --no-build-cache` before believing a failure.
- **A call resumes on the thread that made it, and Android forbids reading a response there.** `ApiClient` read the body after `await()`, on the main thread when a view model started the call, so the
  first call made from a screen (the threat mask) failed with `NetworkOnMainThreadException` while every JVM test passed. The whole exchange is now on `Dispatchers.IO` inside `send`, and
  `ApiClientThreadTest` fails without that (it records the thread that reads the body). Calls from a worker or a service scope never showed it.
- **Android's XML parser throws where the JVM's does not.** `DocumentBuilderFactory.setXIncludeAware(false)` is `UnsupportedOperationException` on Android, so the mission reader refused *every*
  mission on a device while every JVM test passed; nothing had run on a device until a real file was tried. Any parser setting is best effort (`try`), the text check is what holds, and a
  feature that reads a file needs one trial on a device, not only under Robolectric.
- **Auth-gated responses are `Cache-Control: private`.** Flask's `max_age` alone
  emits `public`, which lets Cloudflare cache one user's response for another.

---

## 15. Known issues and debt

- `README.md`'s deployment section and project tree are stale (it describes
  Hugging Face Spaces and a `backend/src/` layout). This file is current.
- `backend/.env.example` is missing (§10).
- `tests/test_threat_qr_export.py` fails (pre-existing).
- CI runs the Kotlin modules, the fixtures and the backend's contract tests
  (§11); the rest of backend pytest and most of the web's Jest suite run nowhere
  but locally, and `pytest` isn't a declared dependency.
- Web behaviour the native ports reproduce on purpose, because the web is the
  reference and the fixtures pin it. Each is a candidate to fix on the web
  *first* (then regenerate the fixtures): `looksLikeCoordinateText("34S")` is
  false, since its "no other letters" test also matches `E` and `S`;
  a new go-around starts *south* of the target unless its direction is spelled `"N"`, which nothing sends (`left` and `right` are what the
  UI sends; `createGoAround`); a PZ marker is made for a target that is not a position, with NaN fields (the native port refuses);
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
  `docs/NATIVE_APPS_PLAN.md`, `docs/MISSION_PACKS.md`, `android/README.md`, `contracts/README.md`. `docs/HANDOFF.md` is the
  "where things stand, what is next" note for whoever picks the Android work up; refresh it with each commit that moves either.

---

## 17. Native apps

The plan, decisions and phases are in `docs/NATIVE_APPS_PLAN.md`; this is what
exists and the rules for working on it.

**Layout.** `android/` is a Gradle project (Kotlin 2.4, Gradle 9.8, Android Gradle
Plugin 9.4, JDK 17 toolchain); `contracts/` holds the golden fixtures and the
design tokens. iOS is not started.

| Module | What it is | State |
|---|---|---|
| `core-model` | Domain types in the web's saved-JSON shape (`LatLon`, `Mgrs`, `AircraftProfile`, route plan and result, and the saved LZ `Diagram` with its normalizer and `Workspace`), `DiagramGeometry` (a saved boundary `[[lat, lon], …]` read as points: a bad point is skipped, never guessed at) and `DiagramOps`: what can be done to one diagram (analyse, edit graphics, view, flight data, name; the standard doghouses), ported from the web's reducer and held to `contracts/fixtures/workspace/ops.json`, `Sidc` / `SymbolPresets` / `UnitMarkers` / `UnitDraft` (the unit builder's logic: SIDC parts, the preset lists and a new unit, held to `symbols/sidc.json`), and `Doghouses` (what a doghouse box says, how typed values are stored, and how the two standard doghouses give the flight data its landing and takeoff headings: held to `workspace/doghouses.json`, with `JsValue.parseInt`/`parseFloat` held to the same file's JavaScript reference cases), and `AircraftDraft` (the aircraft form: what is typed, the server's limits for it, and the document a valid draft makes: held to `aircraft/limits.json`), and `SketchRoute` (a route sketched on the map, in the web's saved shape), `PointSet` / `SetPoint` / `PointSets` (a saved set of local points, below), `ThreatEntry` / `ThreatDraft` (a threat in the picture, and its form; *Threats*, below) and `LocalPointNames` (what typing a name on a route point does with the loaded local points, held to `localpoints/match.json`) and `Weather` (a station's report, the NOTAMs around a target, and how old a fetch is; below) | done for these. Graphics stay opaque JSON, so a field a newer web release adds survives |
| `core-geo` | MGRS both ways, free-text coordinate parser, great-circle distance and course, `PlaceSearch` (what a person typed into a search or target field: a grid, or a coordinate in any format, resolved to a position or to a message in words) and `formatLatLongDms` (the degrees-minutes-seconds text the web keeps as a diagram's `latLong`) and `MapZoom` (the zoom that fits a stretch of ground on MapLibre's 512-point tiles) | done |
| `core-planning` | Aircraft geometry, capacity, separation, profile lookup, route planner, plan defaults and migration, `RouteWinds` / `RouteElevations` (what a route asks the weather and elevation services, and what it does with the answers: `routes/winds.json`), `LzSummary` (a polygon's area by the web's spherical-excess formula, capacity from the *unrounded* area, and the slope tile's call; `planning/summary.json`) and `PlanningGraphics` (rotor-edge lines, where a new aircraft / PZ marker / sector / go-around goes, separation alerts and their words; `planning/graphics.json`) and `SketchOps` (what can be done to a sketched route: the automatic attack profile of a finished sketch, designating, moving, inserting and appending points, plan and per-point values, the one clock anchor; held to `routes/sketch.json`) and `MissionRoutes` (an AMPS mission's routes as sketched routes, a copy, and where they lie: *Files from other apps*) | done |
| `core-formats` | Reads an AMPS `.msnx` into a `Mission`, an `.LPS` into a `LocalPointSet` and a `.ths` into `Threat`s (and `FileKinds` says which of the three a file is, by its content),  as the web's `parseMsnx` / `parseLps` / `parseThs` do, with a small read-only SQLite reader of its own (`SqliteReader`). `ThsExport` gives the rows of a `.ths` export. `RouteHandoff` writes a route as a Garmin FPL or a GPX (`foreflight.js`, held to `routes/handoff.json`). `MsnxWriter` builds an AMPS mission from sketched routes (`createMsnx`, held to the web's own exports) with `AmpsFormats` (the value formats AMPS keeps) over a small DOM toolkit (`MsnxXml`). `MsnxMutator` writes an edit to an imported mission back **into the file it came from** (`mutateMsnx`, held to the web's own edits: `contracts/fixtures/msnx/edits.json`), with `LegsText` editing the huge `legs.xml` as text (below). **Not ported**: *writing* a `.ths` file itself, which is the platform's job (copy `threat_template.ths`, insert `ThsExport`'s rows) | readers, export rows, the sketch exporter and the mission mutator done |
| `core-network` | The API client over OkHttp: one transport (`ApiClient`) with the session behind it, typed calls for the routes in `contracts/openapi.yaml`, DTOs, the request-priority gate, and the "update required" check. See *The API client* below | client, auth, refresh, sign-up, verification, password reset, the `.mil` gate and terrain analysis (`analyzeField`, `terrainAnalysis`) the saved routes (`listRoutes`, `getRoute`, `createRoute`, `updateRoute`, `deleteRoute`: a multipart form, `Call.form`) and route planning (`elevations`, `routeWinds`) and the weather at a target (`weather`) done; the web-share, threat and export routes are not yet typed |
| `core-sync` | The sync engine: local edits into an outbox, a push in order, a pull by cursor, conflicts kept side by side. Pure logic over a `SyncStore` interface (`RoomSyncStore` in `core-data` implements it for the app; `InMemorySyncStore` here). LZs, custom aircraft profiles, saved routes and point sets sync (`RecordKind.ROUTE` is a *set* of sketched routes, the server's `kind: sketch`; a `kind: mission` record, which carries an imported `.msnx`, is passed over for now; `RecordKind.POINT_SET` is the points of one `.LPS` import, below). Beside it: `RecordFeed` (a list that updates itself, which a screen reads; the store implements it, a test double need not), `SyncScheduler` and `ConflictResolver` (the seams the app and the screens use without knowing about WorkManager or the engine). Its `testFixtures` (the scenarios as plain functions, the fake server, a `Device`, `RecordingScheduler`) are shared with `core-data`. See *The sync engine* below | engine done |
| `core-data` | Android library: the Room database (`EzpzDatabase`, version 2: a record can carry a file, below), `RoomSyncStore`, `AccountScope` (whose plans are on the device) and the encrypted session store (`EncryptedSessionStore` over a `SecretBox`; `KeystoreSecretBox` is the Android Keystore one), plus `DiagramRepository` / `DiagramSession` / `AnalysisService` (the LZ diagrams and their online analysis, below), `AircraftProfiles` (the airframes: the admin's master list, the user's own and the mission aircraft; see *Aircraft profiles*), `BoundaryDrawing` (a landing-zone boundary drawn by hand; see *Boundary drawing*), `RouteRepository` / `RouteSession` / `RouteSketching` (saved sets of routes; see *Routes*), `PointSetRepository` / `PointSetViews` / `LocalPoints` (saved sets of local points; see *Local points*), `ThreatStore` / `EncryptedThreatVault` / `ThreatTransfer` / `ThsWriter` (the threat picture and its `.ths` files; see *Threats*), `RouteHandoffExport` (a route as a GPX or FPL file), `IncomingFiles` / `FileInspector` / `MissionImporter` / `MapFocus` (files other apps hand over, held until the person answers, and what accepting a mission does; see *Files from other apps*), `DocumentSession` (what the open diagram and the open route set share) and the Hilt module. One generic `record` table keyed by (kind, uuid) instead of the plan's table per domain, because the engine treats every kind alike; add a column when a screen needs one. Schemas are exported to `core-data/schemas/` and **committed** (a migration test reads them) | store done and held to the same scenarios as the in-memory one; `DiagramRepository`, `DiagramSession`, `AircraftProfiles`, `BoundaryDrawing` the route-set classes, the point-set classes and `WeatherService` (below) done |
| `core-testing` | Reads `contracts/fixtures`; JSON comparison with a tolerance. Test support only, not in the plan's module list | done |
| `core-designsystem` | Android library: the theme (dark, light and the red-shifted **night** palette), type, and `Tokens`, which is **generated** from `contracts/tokens/tokens.json` by `contracts/scripts/tokens.py` (CI checks it is current). A test holds every palette to WCAG contrast, because nothing in an agent session can look at a screen | theme and tokens; shared components (sheet, inspector, readout pill) join as screens need them |
| `feature-auth` | The sign-in screens: sign in, register, "check your inbox", verify and reset (from an emailed link), forgot/resend, and the `.mil` gate (`AffiliationHost`). Stateless screens (`AuthContent`, `AffiliationContent`) over two view models; talks to the server through an `AuthApi` seam. Mirrors the web's `AUTH_MODES` | done; Google sign-in is the app's (below) |
| `feature-map` | The 2D map: MapLibre Native (the SDK directly, not `maplibre-compose`, which is 0.x and would hide the handles a planning map needs), the three base maps under the web's ids (`satellite`, `topo`, `vfr-sectional`), the crosshair readout (MGRS first, computed on the device), search by grid or coordinate, GPS from the platform's own receiver, and `MapViewModel`. The logic that decides *what* to ask the map (styles, readout, search, GPS state, where the camera goes) is plain code and tested; the MapLibre glue (`MapHost`, `EzpzMap`, `GpsOverlay`, `DiagramOverlay`) needs a GPU and is compile-verified only. `LzScene` is what is drawn for the open diagram (target, boundary, a boundary being drawn as an open dashed line with a dot at each corner, the slope raster, and the planning graphics as `GraphicsScene`) as plain data with its GeoJSON, so *what* is drawn is tested. `MapProjection` is the screen↔lat/lon arithmetic (labels, and later tap and drag), `AircraftIcons`/`AircraftIconRenderer` draw each airframe's top view, `MapLabels` works out the separation labels for a camera, `GraphicHitTest` which graphic a tap was on, `DoghouseBox` the doghouse's label box, `UnitMarker` a unit's symbol or its plain stand-in. Threats: `ThreatScene` (a threat's marker and its detection / engagement rings as plain data with GeoJSON; a ring is a geodesic circle of 96 points that stays continuous across the antimeridian), `ThreatOverlay` (the ring layers: dashed amber detection, solid red engagement), `ThreatLabels` (`ThreatLabelsLayer`: the MIL-STD symbol over the map as a Compose overlay, a diamond in the affiliation's colour while the symbol loads, is refused or has no sandbox) and `ThreatHitTest` | base map, readout, search, GPS, the diagram's target, boundary and slope raster, and the planning graphics (aircraft with keep-out discs, separation lines and their labels, PZ markers, sectors, go-arounds, a halo on the held one; doghouse boxes; units' symbols; a tap on the map holds a graphic, via `GraphicHitTest`; threat markers and rings; a tap holds a threat, after a graphic and before a route); drag and rotate on the map are not built |
| `feature-workspace` | The Diagrams tab of the sheet: the list (name, grid, status, whether the server has it), making one from a target (the grid under the crosshair is offered), opening, renaming, deleting (asks first) and settling a conflict (keep both is the primary choice; keep mine / keep theirs say what they discard). `DiagramsViewModel` over `DiagramRepository`, `DiagramSession` and a `ConflictResolver`; the screen (`DiagramsContent`) is stateless and scrolls only if its host does | list, create, open, rename, delete, conflicts, and the open diagram's card: Analyze / Stop, the summary tiles (capacity, area, elevation, slope call with its source) and rename/delete. The list below it is the *other* diagrams (an open conflict copy keeps its row for its choices). `GraphicsViewModel` / `GraphicsContent` are the planning graphics inside that card: place a helicopter, PZ marker, sector or go-around at the crosshair, a list of them with their grid, an inspector (nudge in feet with a 10/50/200 ft step, put at the crosshair or at a typed grid, turn, set a heading, a PZ's reach and tip, a go-around's side, a doghouse's label, time, distance and airspeed, delete), the unit builder (add a unit at the crosshair, or apply the builder to the held one), undo/redo and the separation alerts. Every change is one `DiagramSession.edit` step. `AircraftViewModel` / `AircraftContent` are the aircraft section of the sheet: the mission aircraft chosen from the admin's list and the user's own, and making, copying, changing and deleting the user's own (the form mirrors the web's `AircraftProfileModal`); `AircraftPicker` in the diagram's card chooses the mission aircraft and says what its numbers are. `BoundaryViewModel` / `BoundarySection` (the sheet's "Draw boundary") and `BoundaryToolbar` (over the map while drawing) are the hand-drawn boundary. `WeatherSection` is the weather tiles of an analysed diagram's card. `ThreatsViewModel` / `ThreatsScreen` are the Threats section (below): the list, the held threat's card (details, edit, move, remove) and the form. `IncomingViewModel` / `IncomingHost` ask the person about a file another app opened with the app (*Files from other apps*) |
| `app` | The application: Hilt, Compose, the manifest and its security settings, and the shell: `Gate`/`gateFor` (what stands between the person and the app), `AppViewModel`, WorkManager sync (`SyncScheduler`, `SyncWorker`). See *The app module* below | the shell, the auth flow, the Google sign-in glue and the map as the root, with a bottom sheet that holds the Diagrams, Aircraft, Routes, Local points and Threats sections, the version and sign-out; `HomeViewModel` joins the map and the documents (where the map goes, the scene it draws, measuring the slope of an analysed diagram when it is opened, the routes, local points, weather and local threat picture, and the order a tap on the map is tried in); the app also bundles the AMPS mission and threat templates for explicit sharing (`ShareExport`) and reads files other apps open with it (`incoming/`) |
| `core-symbols` | Android library: MIL-STD-2525C symbols as bitmaps. `PresetSymbols` (the unit presets in all four affiliations and the threat presets, pre-rendered by the web's milsymbol: `contracts/fixtures/symbols/svg`, copied into the app's assets at build, no JavaScript needed), `JavaScriptSymbolSource` (milsymbol itself, vendored in `assets/milsymbol.js` with its MIT licence, run in the system JavaScript sandbox, `androidx.javascriptengine`, for everything else), `SvgRasterizer` (AndroidSVG), `DefaultSymbolRenderer` (the sources in order, an LRU cache, concurrent asks for one symbol coalesced), and `LocalSymbolRenderer` / `rememberSymbol` for Compose. See *Symbols and units* below | the renderer, presets, rasteriser and cache are done and tried; **the sandbox itself is not verifiable here** |
| everything else in the plan (`core-mappacks`, the other `feature-*` …) | later work | **not started** |

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
- **The terrain routes are recorded with the model and the network stubbed, the rest real** (`test_network_fixtures.py::terrain`): SAM
  weights are 350 MB and the tile and elevation services are other people's, so `routes.terrain_routes.model` and its `requests.get` are
  stood in for, while the route code, `build_slope_analysis` over a synthetic GeoTIFF (a ramp with a hill) and the response are the
  server's own, held to `contracts/openapi.yaml`. They are *not* in the live-server process for the same reason. `analyze-field` answers
  `{status: "error", message}` (no `code`): 400 is "no area at this point", 500 is a service failure, and the client tells them by status.
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
- **A route record is the web's saved route, not one route.** `/api/routes` takes a multipart form (`name`, `kind`, `route_data` as JSON text, `client_uuid`,
  and for a mission an `.msnx` file); a `sketch` record's `route_data` is the *set* of routes drawn together, which is what the web saves, so a record here
  is that document whole (`SketchRoute` and the sketch ops know its routes). The change feed carries `kind`, `file_name`, `has_file` and `data` (the
  `route_data`); a `mission` record is not applied until the app can hold its file, because a record whose file part is dropped would be re-sent without it.
  The routes are recorded from the real server like the other routes (`test_network_fixtures.py`) and run through the live-server scenarios.
- **A point-set record is the server's list of points, held as `{"points": [...]}`.** `/api/pointsets` takes JSON (`name`, `points`, `client_uuid`) and does not look
  inside a point (`{id, name, description, group, icon, elevationFt, lat, lon}` as the web parses an `.LPS`; a field a newer release adds rides along inside it). The
  change feed carries the list *bare* as `data` (every other kind's is an object), so the engine wraps it on the way in (`pointSetDocument`) and the gateway unwraps it
  on the way out. Things to keep true:
  - **The server refuses a set with no points** (create is a 400; an update with none is ignored), so a set emptied here would stay in the outbox for good: a set with
    no points is deleted, never saved.
  - **The colour and whether a set is shown are not saved**: the server has no field for them, so each device keeps its own (as the web does for a session).
  - Recorded from the real server like the other routes (`pointset: …` in `responses.json`, held to `contracts/openapi.yaml`) and run through all eleven per-kind
    scenarios on the fake, the real Flask server and Room. A device that synced before the engine learned point sets has its cursor past them and would need it reset to 0;
    no app was released before that, so no migration exists.
- Built since: WorkManager scheduling (`SyncScheduler`: a sync on demand and every 6 hours; not yet seen on a device),
  conflict choices in the Diagrams, Routes and Points sections (`ConflictPanel`), and the 14-day "sign in again" rule
  (`OfflineGrace`).

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
  bitmaps (`MapLabels` / `GraphicLabelsLayer` do the separation labels, from `MapProjection`). **The crosshair is the middle of the map above the
  sheet, not of the whole view**: `EzpzMap(bottomInset = …)` reserves the sheet's peek with a MapLibre camera padding, so `camera.center` — the
  readout, `MapUiState.center`, where a graphic is put — is the point the crosshair is drawn on, and `MapProjection(bottomInsetPx)` agrees. Before
  this the crosshair was drawn half the peek above the camera's centre (about 60 m at zoom 17 on a phone), so the grid shown was not the grid
  under the icon. That MapLibre reports the *padded* centre as the target is its documented behaviour but is **not verifiable here**: check on a
  device that the readout changes as the map pans under the crosshair and a graphic placed lands on it. GPS uses only the platform's `LocationManager` (no Play services, no third party) and nothing about a position is stored or
  sent; the last *camera* (where the person was looking) is kept in a preference, on the device only.
- **The diagrams** (`core-data`: `DiagramRepository`, `DiagramSession`; `feature-workspace`; `app`: `HomeViewModel`). A diagram is a
  `LocalRecord` of kind LZ whose `data` is the web's schema-2 JSON (`DiagramNormalizer.serialize`), whole, so one made here opens on the web
  and back; graphics are carried as opaque JSON. **The record's uuid is the diagram's `id`**: `open` makes the document say so, whatever
  id it arrived with.
  - **Edits live in memory and are written once they have been still for 600 ms** (`DiagramSession`): a drag moves a graphic many times
    a second, and a row write each would be slow and a flood for sync (a test pins "fifty edits, one queued update"). `flush` writes now;
    the app flushes on `ON_STOP` because the system may end the process without warning. Undo is per open diagram, 100 deep, and lives
    only while it is open.
  - **A save never takes the app down.** The delayed write runs in a scope nothing sits above, so a failure there would be an uncaught
    exception: it is caught, the change stays owed (`saveFailed`), and the next flush or edit tries again. `open` of another diagram
    refuses (and keeps the first) if the first cannot be saved, rather than lose its edits; `close` always closes, because it runs at
    sign-out and a diagram that would not close would be on the map for the next account.
  - **A record deleted elsewhere is not a failed save.** If a diagram's record is gone when it is saved (a delete arrived by a pull), the
    work is kept as a new record under a new id and the session adopts it, undo history included — the engine's own rule for edited-here,
    deleted-there. Only `DiagramsViewModel.delete` removes a diagram on purpose, and it closes the session first so it cannot come back.
  - **Base map is a quiet change** (`setQuietly`): saved with the diagram, but applied to every undo step, so undoing a real edit never
    brings an old base map back. Opening a diagram takes the map to its target (zoom 17) and restores its base map; an id this version
    does not know is kept but not drawn. `HomeViewModel.opened` is a one-shot event, so a turn of the phone does not throw the view
    back to the target.
  - **An analysis belongs to the diagram it was asked for** (`AnalysisService`, a port of `useTerrain` and `handleAnalysisComplete`). The
    answer takes many seconds; it is applied through `DiagramSession.update(id)` to the diagram named when the request began — the open
    one if it still is, else its stored record, else dropped if it was deleted — never to whichever is open on arrival. `update` takes the
    same lock as `open`/`close`, so a switch cannot see an analysis half applied. It is **quiet** (not an undo step, and applied to the
    history too) and runs on the thread that edits. Only an area the model could not find fails it; a slope that cannot be measured
    leaves the analysis standing (`SlopeState.Unavailable`). **Not like the web:** nothing; like the web: no landing heading is sent to
    `/api/terrain-analysis` (the web never does), so `directional` is null and a slope of 15° or more reads "heading required". The slope
    raster (`terrainData`) is **runtime only**, kept in the service by diagram id, never in the document. A hand-drawn boundary of three
    points or more is promoted to the analysis boundary and the drawn one cleared, as the web does. Error text shown to a person is the
    app's own words: the server's internals (a Python exception on a 500) are never shown.
  - **`advanceUntilIdle` does not run a test's `backgroundScope`.** A service launched in one never moves; give it a scope on the
    test's scheduler (see `AnalysisServiceTest`).
  - **The summary tiles are the web's `MissionSummary`**, whose maths was extracted there into pure exported helpers
    (`lzAreaAndCapacity`, `slopeStatusFor`; no behaviour change) so the fixture could pin them. Capacity is for the mission aircraft
    (*Aircraft profiles*, below). The weather tiles are *Weather*, below. The area formula does not unwrap the antimeridian, on purpose.
  - **Numbers shown to a person are formatted by hand** (`withCommas`, `oneDecimal`: `toFixed`'s rounding, no locale), as §17's rule says.
  - **Planning graphics** (`core-model`: `GraphicRef`, `DiagramOps` graphic ops; `core-planning`: `PlanningGraphics`, `GraphicEdits`;
    `core-data`: `GraphicSelection`; `feature-workspace`: `GraphicsViewModel`; `feature-map`: `GraphicsScene`). The placement, separation
    and keep-out maths are ported from the web's hooks (extracted there as pure exports, no behaviour change) and held to
    `contracts/fixtures/planning/graphics.json`; the *edits* the web does by dragging (`GraphicEdits`: nudge, move to a point, turn, a PZ's
    reach and tip) have no web counterpart and are tested on their own. Rules worth keeping:
    - **Graphics are placed only on an analysed diagram** (`Diagram.canEditGraphics`), as on the web.
    - **An edit is a patch** (`DiagramOps.patchGraphic`): only the fields it changed are written, so a field a newer web release adds to a graphic
      survives an edit made here. A test pins it.
    - **Distances shown are feet** (`nudge`, a PZ's reach), because crews plan in feet; the metres are the model's and are converted at the edge
      with `Units.METERS_TO_FEET`.
    - **The held graphic is `GraphicSelection`**, one singleton the map (halo) and the sheet (inspector) both read; `HomeViewModel` clears it
      when a different diagram opens. A selection that names a graphic no longer there shows no controls and edits nothing.
    - **Ids**: an aircraft's is the clock's milliseconds (as on the web), the others `pz-`/`sec-`/`ga-` plus the same, each nudged past any
      taken, so two placed in one millisecond do not collide.
    - **An aircraft is measured as the airframe it was placed as** (its `profileRef`, looked up in `AircraftProfiles.profiles`, falling back to the
      mission aircraft and then the UH-60L), by both `GraphicsViewModel` (separation alerts) and `HomeViewModel` (the map's icon and keep-out disc),
      and one is placed as the mission aircraft. See *Aircraft profiles*.
    - The list runs aircraft first, then PZ markers, sectors, go-arounds, doghouses, units, whatever was placed first.
    - **Doghouses** (`Doghouses`, `DoghouseBox`, the `DOGHOUSE` kind). The web's maths was extracted from `Doghouse.jsx` / `useDoghouses.js` into
      `feature/doghouses/doghouseFields.js` (no behaviour change) and pinned in `contracts/fixtures/workspace/doghouses.json`. Things that are
      easy to get wrong:
      - **The flight data follows the doghouses**, as the web's `useDoghouses` *effect* does (not its reducer, so `ops.json` does not cover it):
        whenever a diagram's doghouses change, `landing_hdg` / `takeoff_hdg` are taken from the first landing / takeoff doghouse (`role`, else the
        older ids `dh1`/`dh2` or labels `[SP1]`/`[RP1]`). `DiagramSession` does it (`Doghouses.settle`) on open, edit, quiet change and update,
        to every version in the undo history, so undo takes the heading back too; a diagram's doghouses that did not change leave a flight data the
        person set by hand alone. Before this, an analysis made the doghouses and left the headings empty.
      - A heading is stored `"090°"` and wrapped **once**: `-90` is `270°` but `-400` stays `-40°`, as JavaScript's `%` does; the box is turned by
        the leading integer of the stored text, unwrapped. A small negative pads in front of its sign (`0-5`). All kept, and in the fixture.
      - **The web stores whatever is typed** (`3.5` is `"3.5km"` even for "far"); a phone asks what the field is for, so `labelPatch`, `timePatch`,
        `distancePatch` and `airspeedPatch` refuse what cannot be one and store what they accept as the web would (`03+20`, `12.5km`, `55 kts`).
        A refusal is returned as words for the field itself, not the panel's banner, which can be scrolled out of sight while typing.
      - **Doghouses are not placed**: the first analysis makes the SP and RP once, and the web has no way to add one.
      - The box is **black and white in every theme** (it is a printed symbol, as on an LZ card), turned by its heading less the map's bearing, with
        the app's accent round it when held. It is a Compose overlay placed with `MapProjection`, like the separation labels.
    - **A tap on the map holds the graphic under the finger** (`HomeViewModel.mapTapped`, from MapLibre's click listener; the hit-test is plain
      geometry on `MapProjection`, so it is tested without a GPU): anything with a point is hit within 24 dp of it (an aircraft anywhere on its
      rotor disc), the nearest wins, and a sector only when nothing with a point was, so a big sector never swallows a tap meant for an aircraft in
      it. A tap on nothing puts the held graphic down. The projection used for a tap has no size (`MapProjection(camera, 0, 0, density)`):
      distances do not depend on where the middle of the screen is.
    - **The inspector is in the sheet, which rests at its peek**, so a graphic held from the map shows its halo but its controls need the sheet
      pulled up (the sheet has no half-way stop). A held-graphic strip in the peek would fix it; not built.
  - **The diagram that was open comes back at launch** (`HomeViewModel`, `LastDiagram`): its id (a uuid, nothing about the place) is kept in a plain preference
    each time a diagram opens, and is *not* forgotten when the session closes at sign-out, so the same account finds it again. The restore waits until the map
    is listening to `opened` (a `SharedFlow` with no replay: an event nobody hears is lost, and it is what takes the map to the diagram and restores its base
    map), does nothing if the person has already opened something, and treats a diagram that is gone (deleted, another account's) or will not open as
    "start from the list".
  - **Not done:** moving a corner of a boundary once drawn (draw it again); the heading-aware slope. **Deleting a corner is built** (`BoundaryCorners`, `CornerHitTest`): a tap holds a corner of the analysis or the drawn boundary (after graphics, routes and their points, before local points), the held-object bar offers *Delete corner*, it is one undo step, and a boundary keeps three corners (the button is disabled and says so). Deleting from the analysis boundary makes the slope be measured again. **The slope heat map has a button** (`SlopeVisibility`, `SlopeToggleUi`): it only hides the raster, the slope is still measured and the tiles still say it; it is a view choice, on at each launch and not saved with the diagram. Neither is seen on a device yet. (Dragging and turning on the map are *Dragging and
    turning on the map*, below; the inspector still does all of it and works with gloves and a screen reader.)
- **Boundary drawing** (`core-data`: `BoundaryDrawing`; `feature-workspace`: `BoundaryViewModel`, `BoundaryScreen`; `feature-map`: `LzScene.draft`;
  `app`: `HomeViewModel`, `MapHome`). The web's "Draw LZ/PZ boundary" (`toggleDrawingMode` in `App.js`): the person puts the corners down one by
  one and a ring of three or more is saved as the diagram's `analysis.customLZ` (`[[lat, lon], …]`), which the next analysis takes as the boundary
  instead of the one the model finds (`AnalysisService`).
  - **The corners are a draft, not the diagram's**: they live in `BoundaryDrawing` (a singleton, like `GraphicSelection`) until *Finish*, so abandoning
    a half-drawn boundary leaves nothing behind. The draft belongs to one diagram; `HomeViewModel` drops it when another opens, and the scene draws it
    only on the diagram it was started on.
  - **Starting clears what is there**, as on the web: an earlier drawn boundary, and the analysis of an analysed diagram (its graphics stay but need an
    analysis again before they can be edited). The sheet asks first when that would discard something. **Not like the web:** starting is one step of the
    diagram's own undo, so it can be taken back; a boundary needs three corners and *Finish* says so rather than silently dropping the points.
  - **A tap on the map is a corner while drawing**, wherever it falls, so nothing else can be held until the person finishes or cancels. *Add here* puts
    one at the crosshair (the precise way, with the sheet down). The buttons float over the map (`BoundaryToolbar`, via `MapScreen`'s `overlay` slot),
    because the sheet rests at its peek and its Finish button would be out of reach; starting drawing puts the sheet down (`partialExpand`).
  - **Drawing cannot start while an analysis of that diagram is running** (it would finish over the new boundary); one running for another diagram does not
    hold it back.
  - **Not verifiable here:** the dots and dashed line on the map (`DiagramOverlay`'s vertex layer: compile-only, like every GL layer), the sheet going down
    when drawing starts, and that MapLibre's click listener fires for every tap while drawing (it already does for selecting graphics).
- **Routes** (started: `core-model`: `SketchRoute`, `RoutePlan`, `Mission`; `core-planning`: `SketchOps`, `RouteCalc`, `RoutePlans`; `core-formats`: `MsnxReader`).
  The web's route code is ported the way the graphics were: the pure part is extracted from the hook into an exported module with no behaviour change
  (`feature/msnxImport/sketchOps.js`, the bodies of `useRouteSketch`'s updaters, with ids and colours passed in), pinned by a fixture
  (`contracts/fixtures/routes/sketch.json`, written by `frontend/src/contracts/sketchFixtures.test.js`), and ported. Things that are easy to get wrong:
  - **Only designated ("amps") points are route points**; the rest are shaping geometry. A finished sketch gets the standard attack profile (the first two
    points Target then IP, the last two RP then Target, the middle shaping) unless a point was designated while drawing, which always wins. Demoting one of
    the last two AMPS points is refused. All in the fixture.
  - **JavaScript's `||` on text**: an empty type or name in a designation is as good as none (`Designation`), but an empty `name` in a re-designation is kept.
  - **`insertShaping` finds the nearest pair of *consecutive drawn* points**, by flat distance in degrees, first of equal pairs winning: a serpentine can
    pass close to a distant leg, and a route-wide search would snap to it. A leg with no length still counts (its distance is to its one point).
  - **Only one point holds the clock** (`withClock`): setting one clears the others' clocks and keeps their other overrides, and a point left with nothing is
    dropped. A plan patch and an override patch name only what they change; the web's screens only ever set values, never clear one.
  - **`MsnxWriter` is a DOM port of `createMsnx.js`**: it takes the bundled template apart for its prototypes (one route, segment, leg, AMPS point, serpentine
    point, GPX route point…), empties every list and rebuilds them from the sketches. It is held to the files the web itself exports
    (`contracts/fixtures/msnx/sketch-*.msnx`, all seven parts the writer changes), compared as *parsed documents*, not bytes: the two serializers need not agree on
    how to write the same document. Things that are easy to get wrong:
    - **The order of the calls to `newId` is the contract.** The web names every route, segment, leg and point with `crypto.randomUUID()` and its fixtures
      stub that with a counter; a counter in the same order gives the same file, so the comparison tests *content*. Do not reorder them.
    - **JavaScript's number text.** AMPS values are built by putting a computed number in a template literal (`raw${15.24} m Foot AGL`), so
      `JsNumber.toText` writes the shortest digits that read back (ECMAScript `Number::toString`, in plain form between 1e-6 and 1e21), held to
      `formats/number_text.json`. Java's `Double.toString` writes `100.0` and `1.0E21` and is not used.
    - **`PlanPoint.clockTime`** carries a clock's *date* too (a route that runs past midnight is on the next day), because AMPS records `M/D/YYYY h:mm:ss.0000 AM`.
      It is `@Transient`: the saved plan and its fixture hold only the `HH:MM:SS`.
    - **The serializer is by hand** (`MsnxXml.serialize`): a transformer sorts attributes and reflows text. One declaration per part, a byte order mark on the mission's
      XML parts and none on the GPX; every other entry of the zip (the vehicle model is 650 KB) is carried through as bytes, in order.
    - **The whole template is parsed**; a template with tens of megabytes of `legs.xml` would want its first `<leg>` cut out first (the bundled one is 1.1 MB).
    - A route needs two designated points to export; shaping points before the first and after the last are dropped (a leg is between two AMPS points).
  - **A saved set of routes** (`core-model`: `RouteSet`, `RouteSets`; `core-data`: `RouteRepository`, `RouteSession`, `RouteSketching`; `core-planning`:
    `RouteColors`; `feature-map`: `RouteScene`, `RouteOverlay`). One sync record (`RecordKind.ROUTE`) is the web's `route_data`, `{version: 1, routes: […]}`, the
    routes drawn together, whole. Things that are easy to get wrong:
    - **Nothing in the document is dropped.** `SketchRoute`, `RoutePoint` and `RoutePlan` carry a `@Transient extras` of the keys a newer web release wrote
      that this version does not know; `RouteSets.serialize` writes them back, with a field this version knows always winning over a stale extra. A route
      that cannot be read at all (`lat` missing, say) is kept as it was in `RouteSet.unreadable` and written after the others, so one odd route does not stop
      the set opening or get thrown away by the next save. The one place with no room is inside a per-point override and an airspeed, altitude or wind.
    - **The document's own `version` is kept as found**, and a document with none is written as 1. Nothing refuses a version: the web has only ever written 1.
    - **Absent, not null.** The codec omits a null (`explicitNulls = false`) as `JSON.stringify` omits `undefined`, which is what the web's own `movePoint`
      leaves for "no charted elevation"; fixtures compare an absent key and a null as equal.
    - **`DocumentSession` is the open-document machinery** (the 600 ms pause before a write, undo 100 deep, a record deleted elsewhere kept as a new one,
      a failed save kept owed) that `DiagramSession` used to hold; `DiagramSession` is now a thin subclass that only adds the doghouse-follows-flight-data
      step (`tidy`), and `RouteSession` adds nothing. Its tests are `DiagramSessionTest`'s; `RouteSessionTest` holds only what routes need of it.
    - **A new route takes the first palette colour its set is not using** (`RouteColors`), not the web's session-long counter, which on a phone that is opened
      and closed all day would give two routes one colour as often as not. A route is `ROUTE n` (n counting the set's routes, +1) unless named, and a name is
      trimmed and upper-cased as the web does; its id is `sketch-<millis>-<random>` (the prefix is how the web tells a sketch from an imported route).
    - **Drawing is a draft held outside the set** (`RouteSketching`, like `BoundaryDrawing`): abandoning it leaves no trace, a draft belongs to the set it was
      started on and is refused (and dropped) if another is open when it is finished, and finishing is one undo step. A route is planned for the mission aircraft.
    - **The map draws a route as one line through every point**, a named (AMPS) point as a dot, and the shaping points only on the route being worked on
      (`RouteScene`); names are Compose overlays, as the separation labels are, because the style has no glyphs. `RouteOverlay` is GL and compile-only.
    - **What a tap on the map does** (`HomeViewModel.mapTapped`, in this order): a boundary being drawn takes it as a corner; a route being drawn takes it as a
      point; a planning graphic is held if the tap is on one; else a route or one of its points (`RouteHitTest`, plain geometry on `MapProjection`: a named
      point within a finger's reach, the nearest wins and a named point beats a shaping point at the same distance; shaping points only on the route being
      worked on, because only those are drawn; else the nearest leg within reach, which picks the route and no point); a tap on nothing puts down the graphic and
      the route's point but keeps the route, so drawing and editing carry on. `RouteSelection` (route and point held) is a singleton the map and the sheet both read, like
      `GraphicSelection`; opening another set, or closing it, clears it and drops a route half drawn (`RouteSketching.dropDraftNotOn`, which leaves a draft begun
      on the set that has just opened).
    - **A boundary and a route are never drawn at once** (`DrawingMode`, shared by `BoundaryDrawing` and `RouteSketching`): both are drawn with taps, so the second to start is
      refused in words ("Finish or cancel the boundary first."), and every way a draft ends (finish, cancel, its document closed) lets go of the mode.
    - **The Routes tab** (`feature-workspace`: `RoutesViewModel`, `RoutesScreen`) is the Diagrams tab's shape: the open set's card (its routes, each with its colour, point
      counts and `12.3 nm · 8:15` once it has two named points; Hide/Show, Rename, Delete with its asks; undo and redo; rename, delete and close the set), the other
      sets, a new-set form (a blank name is `MISSION n`), and a toolbar over the map while drawing. Conflicts use the same panel as diagrams (`ConflictPanel`, also keep-both
      primary). A new route, once finished, is the one held. Names are upper-cased as the web does.
    - **The set that was open comes back at launch** (`HomeViewModel`, `LastRouteSet`), as the diagram does (`LastDiagram`): its id (a uuid, nothing about the routes) is kept in a plain
      preference each time a set opens and is *not* forgotten when the session closes at sign-out; a set that is gone (deleted, another account's) or will not open is "start from the
      list". Unlike the diagram it does **not** wait for the map to listen: opening a set takes the map nowhere, so there is no event to lose. One opened in the meantime stays.
    - **Signing out closes the open set** as it closes the open diagram (`AppViewModel.closeOpenDocuments`); one that will not save does not keep the other from closing.
  - **The held route's plan** (`feature-workspace`: `RoutePlanScreen`, `RoutesViewModel.detail`; `core-planning`: `PlanDraft`, `PointDraft`). The route-wide form (date, airspeed and
    its reference, altitude and its reference, wind, temperature, fuel flow) and a row for each named point (the nav log, the web's `RoutePlanSection`: name, type, the leg that
    arrives there, the clock time and the time since the start; the held point's row opens its form: altitude, speed and wind *to* it, its clock, make it shape the line). Things that
    are easy to get wrong:
    - **Typed text is a draft until it is checked**, and a refusal is *words for the form* (`applyPlan` and `applyPoint` return them), not the sheet's banner, which can be scrolled
      out of sight while typing. **Not like the web:** the web's `num` turns an unreadable number into 0 (15 for the temperature) silently; here it is refused with the field named,
      and a wind direction outside 0–360 is refused too. The ranges are the planner's, not the web's.
    - **Only what was changed is applied** (`PointDraft.check(before, first)`): the altitude and its reference together, the speed and its reference together, the wind's direction and
      speed together, as the web writes them, and only a group whose text changed. Looking at a point and pressing Apply does not turn the route's values into the point's own.
      The first point has no arriving leg, so its speed and wind are never read.
    - **The nav log lists only named points and only once there are two** (it is the planner's `points`, empty until then): a route with fewer shows the planner's warning instead.
    - **The plan form starts again whenever the plan changes under it** (an undo, a fetched wind): what is typed is never applied to a plan it was not typed against.
    - **Dates and times are typed on a text keyboard**, and so are altitudes and temperatures: Android's number pads have no hyphen, colon or minus sign.
    - **Winds and ground elevations** (`core-data`: `RoutePlanning`) are *fetched data*: they land in the route they were asked for through `RouteSession.update` (so, like an analysis, they
      are not an undo step and are applied to that route's record even if another set has been opened while the server answered), one fetch at a time. A failure is the app's words,
      never the server's (the 500 text names a Python type). A time is asked for as an instant in the device's zone (`RouteWinds.instantText`); the question and the merge are held to
      `routes/winds.json`, whose times are wall-clock to the millisecond so the fixture does not depend on the zone it was made in (a Date holds milliseconds, and the request carries them).
  - **Moving and adding points from the sheet** (`RoutesViewModel`: `nudgePoint`, `pointToCrosshair`, `pointToText`, `addShapingPoint`; `RoutePlanScreen`: `PointPosition`). The same controls a held
    graphic has (`NudgeSteps`/`NudgePad`/`EntryRow` are shared with `GraphicsScreen`), because dragging a point in gloves is not a plan: a pad of four arrows with a 10/50/200 ft step, *put at the
    crosshair*, and a grid or coordinate typed in (`PlaceSearch`, so the words for a refusal are the search field's). A named point's controls are folded behind *Move this point*; a held shaping
    point, which exists to be placed, shows them at once. *Add a shaping point at the crosshair* is `SketchOps.insertShaping` (the nearest pair of consecutive drawn points) and holds the new
    point so it can be moved into place. Each move is one undo step ("Move point"), and the position text beside the arrows is the point's grid, computed on the device.
    - **Not like the web:** moving a point **drops the ground elevation fetched for it**. Elevations are keyed by point id and the web leaves a point's old one in place after a drag, so AGL
      altitudes would be measured from the wrong ground; here the point has none until the next fetch (the others keep theirs), and undo brings it back. A *wind* fetched for the point is not
      dropped: winds are saved as plan values with nothing to say they were fetched, so a typed one could not be told apart. Fetch again after moving a point far.
    - The crosshair is `state.center` (the middle of the map above the sheet, §17 *The map*), passed down as `RoutesHost(crosshair, crosshairGrid)`; the complaint "Move the map to where the point
      should go first." is cleared when the next one succeeds.
  - **Export for AMPS** (`core-data`: `RouteExport`, `MissionTemplate`; `app`: `ShareExport`, `AssetMissionTemplate`). A set's routes, or one route, are built into a `.msnx` with `MsnxWriter` on the
    bundled template (the web's `frontend/public/msnx_template.msnx`, copied into the app's assets at build by `copyMissionTemplate`, not committed twice; a test holds the asset to the web's
    bytes) and handed to the system share sheet. The file is named for the routes (names joined with `_`, the web's rule) with anything a file system or a chat app trips over made an
    underscore. A route with fewer than two named points is refused *by name* before the writer is asked. **A route planned for another airframe still opens in AMPS as a UH-60L** (the
    vehicle model is AMPS's and cannot be faked); the person is told in a dismissible banner — the web's text, except that an airframe with no name reads "the selected aircraft", not "a the
    selected aircraft". An admin-attached package (`resolveExportTemplate`) is not used yet. Building runs off the main thread (`RoutesViewModel.worker`), once at a time.
    - **The share sheet** gets one file by a temporary read grant on one URI of a `FileProvider` that is not exported and may read only `cache/exports/` (`export_paths.xml`); files are cleared out
      after a day, and a name is cut to a plain file name again at the last step so a crafted route name cannot leave the folder. A test asks the provider for `exports/../secret.txt` and
      is refused. `FileProvider` keeps a static cache of its folders, so Robolectric tests clear it (`sCache`) between tests; a real process has one cache directory.
    - **Not verifiable here:** that the share sheet opens and hands the file to a real app, and that AMPS opens what is exported (the web's export is the reference; the writer is held to its files).
  - **Not yet built:** choosing the template for an aircraft (an admin-attached package), and an ATAK data package (a GPX goes to ATAK through the share sheet; the
    package, KML/KMZ and threats to ATAK are not built). A saved mission is *Saved missions*, below.
  - **Sharing a route with another app** (`core-formats`: `RouteHandoff`; `core-data`: `RouteHandoffExport`, `HandoffFormat`; `feature-workspace`: `RoutesViewModel.shareRoute`;
    `app`: `ShareExport`). The held route's *GPX* and *Garmin FPL* buttons hand every point of it (shaping points included, so the path is the one flown) to the share sheet: GPX for ATAK, Garmin
    Pilot and most map apps; FPL for Garmin Pilot (and ForeFlight on iOS, which Android has no counterpart of). `RouteHandoff` is a port of `foreflight.js` held to
    `contracts/fixtures/routes/handoff.json` byte for byte (the FPL's creation time is fixed in the fixture and written as `toISOString()`, always three digits of milliseconds). Easy to get wrong,
    each in the fixture: identifiers are uppercase letters and digits, ten at most, a point with none left is `WP<position>`, duplicates are numbered inside the ten (`ABCDEFGH10`);
    coordinates are JavaScript's `toFixed(6)` (`JsNumber.toFixed`: the exact binary value rounded half up, `-0.000000` for a tiny negative); only `&`, `<` and `>` are escaped; the route name is
    upper-cased and cut to 25. **Known difference, not in the fixture:** a route name whose 25th UTF-16 unit is half an emoji: the web's browser writes U+FFFD, a JVM encoder writes `?`. The GPX is
    told to the other app as `application/gpx+xml` and the FPL as `application/xml`, and kept for a day like a mission (`ShareExport`): their types are a guess at what receiving apps filter on,
    **not verifiable here**. Nothing is sent by the app; the share sheet is the person's.
- **Saved missions** (`core-formats`: `MsnxMutator`, `LegsText`; `core-sync`: `FileRef`, `RecordKind.MISSION`; `core-data`: `RouteRepository`, `MissionImporter`; `feature-workspace`: `RoutesViewModel`). An imported `.msnx` is
  kept **as the saved document**, as the web's `kind: mission` keeps it: the file is the record, and edits are written back into it, so everything AMPS holds that this app never reads (the vehicle
  models, the calculation blobs of every leg) comes through every save untouched. Things that are easy to get wrong:
  - **A file is carried by the sync engine, by content.** `LocalRecord.file` and `Attempt.file` are a `FileRef` (the SHA-256 of the bytes, and the name it is sent under); the bytes are in a store
    table keyed by that hash (`SyncTransaction.blob`/`putBlob`, Room's `blob`). The hash makes a file immutable, which is what lets a send in doubt (an `Attempt`, repeated exactly as it was) and the
    record refer to the same bytes without copying them. **A file nothing refers to is dropped when the transaction ends**, so a replaced file does not stay on the device. A pull fetches a mission's
    file **before** its transaction (a download is not held against the database), in groups of at most 24 MB, and a replay of a page is harmless: a change at its revision is neither fetched nor
    applied again. A conflict fetches the server's file first, so a record is never left holding the server's revision and our file. The shared per-kind scenarios now run for missions on the fake
    server, the real Flask server and Room (57 each), and `MissionSyncTest` has what they cannot say (lost answers, what is and is not downloaded).
  - **The database is version 2** (an `AutoMigration`; the schema is committed). `RoomMigrationTest` builds a real version 1 database from the exported schema and opens it as 2: a person's
    installed app updates in place.
  - **Opening reads the file, saving writes the difference.** `RouteRepository.open` reads the file into a `RouteSet` (each route keeps its AMPS `segmentId`; `RouteSet.mission` marks it) and holds
    the file while it is open; `save` hands the original file and the routes as they are now to `MsnxMutator.rewrite`, which **reads the original again and works out what changed**: a point that
    moved or was renamed is patched in `points.xml` and the GPX (a serpentine point in its leg's trackpoints too); a point that is new (an id not in the file) is spliced onto the leg it was put on,
    which is split in two, with a clone of a plain point; a route whose plan changed has its altitudes, elevations and clock times written into its points and its airspeeds and winds into its legs.
    Diffing against the file, not recording edits, means saving again and again always ends as "the original plus the difference", and undo needs nothing special.
  - **Not like the web:** the web writes every route's plan on every export. The plan is kept in feet and rounded, so writing it back unchanged would round every altitude of a file that was only
    opened: a plan is written only for a route whose plan, ground elevations or points changed, and a save that changes nothing in the file (a rename, a colour, hiding a route) writes none.
  - **`legs.xml` is edited as text** (`LegsText`): a real one is tens of MB, and a DOM of it is more than a phone can hold. It finds each `<leg>` once and changes only the text of the leg asked for
    (a value, a split, a trackpoint); the rest is written back as it was. The small parts go through the DOM. The mutator is held to files the web writes (`edits.json` and `edits/*.msnx`, from
    `frontend/src/contracts/msnxEditFixtures.test.js`, compared as parsed documents), and `a real mission survives being saved` runs it against a real AMPS file when `EZPZ_REAL_MSNX` names one (not in
    the repository). Tried on a real 1.5 MB mission on an emulator: one point moved, one point changed in 91, `legs.xml` the same size, every other part of the package byte for byte.
  - **What a mission set cannot do, and why:** routes cannot be drawn, renamed or removed, and a point's kind cannot be changed, because those change the file's structure and the web does not allow
    them either (the sheet says so in words). A point put on the line is a **named** one (`.NEWPT`, as the web makes it) that splits the leg; there is no shaping point to give a file. Local points can
    be put *onto* a route point (its name and position) but not added to the end of the route. A hand-off point (AMPS keeps one as two points sharing a name and a place) is moved together in both
    routes. **The routes' colours are kept in the record's summary** (`{version: 1, routes: [{name, color}]}`, what the server keeps for lists), nothing else is.
  - **Exporting a mission set sends its own file** (named `…_edited.msnx`, as the web names it), with the changes saved so far in it; exporting one *route* still builds from the template.
  - **Known limit, shared with sets and diagrams:** an open document is held in memory, so a pull that replaces its record while it is open is overwritten by the next save. A mission would be
    saved onto the file it was opened from.
  - **Test trap:** `RouteRepository` takes the dispatcher its file work runs on (the injected constructor gives `Dispatchers.Default`); a test on a virtual clock passes its own, or the test moves on
    before the work finishes. And never `runBlocking` inside such a test: it blocks the thread the work is waiting for.
- **Dragging and turning on the map** (`core-data`: `MapDrag`, `DocumentSession.edit(coalesce)`; `core-model`: `DragTarget`; `feature-map`: `DragHitTest`, `DragMapView`; `app`: `HomeViewModel`;
  `feature-workspace`: `HeldObjectBar`). A **tap holds** a graphic, a threat or a point of a route (its halo, and the bar over the map: its name, where it is, how to turn it, a way to the sheet's
  other options); a **long press picks it up** and the finger moves it until it lifts. Things worth knowing:
  - **The touch is taken from the map, not fought for.** `DragMapView` is MapLibre's view with a gesture detector: a long press is asked about (`HomeViewModel.dragStarted` → `DragHitTest`, the same
    things in the same order a tap holds); if something is there the map is sent a cancel (so it neither pans nor flings) and the rest of that touch is the drag's. A press on nothing, on the line
    between two points, or while a boundary or a route is being drawn (a press is a corner then) is the map's as it always was.
  - **One drag is one undo step.** `DocumentSession.edit(label, coalesce)` merges edits made with the same key, one after another, into a single step; each says where the thing now *is* (start plus the
    ground distance the finger has travelled, so it does not jump to the finger, and a PZ marker keeps its tip and a sector its shape: `GraphicEdits.nudge`), and one that brings it back to the start
    takes the step away. The document is changed at once, so the map and what depends on it (aircraft separation) follow the finger, and it is **saved once**, after the pause.
  - **A route point forgets the ground elevation fetched for the old place** (as a nudge does). In an imported mission its twin in another route moves with it.
  - **A threat is shown at once but written to its sealed file when the finger lifts** (`ThreatStore.previewMove`/`settle`): that file is rewritten whole for every change.
  - **A long press holds and opens nothing**: the bar with an object's information is for a tap. A press on a unit is tested against where its **picture** is drawn, not only its point: a hostile unit
    stands on the end of a staff under its frame, so the frame a person presses is well away from the point (`UnitFootprints`, reported by the Compose layer that draws each symbol, in pixels). The end of
    a PZ marker's **arrow is its own handle** (a blue ring on the tip): a long press there drags the tip alone, so the marker's reach and bearing change and its anchor stays
    (`DragTarget.PzTip`, `GraphicEdits.setPzTip`); a press anywhere else on the marker moves it whole.
  - **Turning** is the bar's: −15°, −1°, +1°, +15° for what turns (an aircraft, a go-around, a doghouse, a PZ marker); the sheet's inspector has the typed heading. Local points are imported data and
    are not dragged. Tried on an emulator against a real mission: a long press and a drag moved a route point (its grid changed, the map did not pan) and the move reached the file on the server. A
    boundary's corners are still not draggable.
- **Local points** (`core-model`: `PointSet`, `SetPoint`, `PointSets`, `LocalPointNames`; `core-data`: `PointSetRepository`, `PointSetViews`, `LocalPoints`). A set is what one AMPS `.LPS`
  import made, saved under a name and synced like any record (above). Things that are easy to get wrong:
  - **The document is the web's list of points, whole, and nothing in it is dropped.** `SetPoint` carries `extras` (the fields a newer release adds), and an entry that cannot be read as a
    point (no numeric position; a position written as text counts as none) is kept in `PointSet.unreadable` and written back after the others, so one odd entry does not stop a set
    opening or get thrown away by the next save. A point with no id is named by its place (`pt-<index>`); an `.LPS` import gives each `lps-<index>-<6 hex>` as the web does.
  - **Colour and visibility are this device's own** (`PointSetViews`, a plain preference through `PointSetViewStore`): the server has no field for them. A set takes the first palette colour no
    other set uses, keeps it (`settle`, run whenever `LocalPoints.sets` is collected, which also drops the views of sets that are gone so their colours are free), and a set not yet known is
    drawn in the colour it would take. Every set counts for a name, hidden or not (the web looks a name up in all the loaded sets); only a visible one is drawn.
  - **A route point's name snaps onto a local point** exactly as the web's does (`LocalPointNames`, ported from `localPointMatch.js`, extracted from `RoutePlanSection` with no behaviour change
    and held to `localpoints/match.json`): the name in capitals, **one** leading dot of what is typed ignored (never of a point's own name, so a point called `.DOTTED` is found by `..dotted`),
    no trimming, the later of two equal names wins, a point with no name cannot be found, and an elevation of 0 is an elevation. The route point takes the local point's position and its
    charted elevation (`SketchOps.rename(…, snapTo, chartElevationFt)`, which exists; no screen uses it yet).
  - **A set with no points is never saved** (the server refuses it): an import of a file the reader refuses (`FormatException`, in the web's words) is `ImportOutcome.Refused` and saves nothing,
    a blank name is refused rather than sent, and a file whose name is only `.lps` is called `LOCAL POINTS`.
  - **A big set is parsed once for as long as its document is the same** (`PointSetRepository.observeSets`): the list is rebuilt for every change to any record, and a set can be thousands of points.
  - **Test traps:** `SyncStore.transaction` is not re-entrant, so reading a record inside one deadlocks (the test then hangs rather than fails); read first, then write. And never `pkill`/`pgrep -f` a
    pattern your own command line contains: it kills the shell (use `[G]radleWorkerMain`).
  - **On the map** (`feature-map`: `PointScene`, `PointHitTest`, `PinLabels`, `PointOverlay`; `app`: `HomeViewModel.points`, `PointSetViewPreferences`). Every *shown* set is drawn as dots in its
    colour, the held one larger with an amber ring (`PointSelection`, a singleton the map and the sheet both read); a crowd gathers into one larger ring that breaks up past zoom 12 (MapLibre's own
    clustering, no count on it: the style has no glyphs). A tap holds a point only after a planning graphic and a route have had theirs (a route point snapped onto a local point is the route's), and a
    tap on the held one, or on nothing, puts it down. A point id is only unique within its set, so a selection names both. **Names are Compose labels** (`PinLabelsLayer`, which also gives a route's named
    points their names: nothing drew those before): a route's are always named; local points from zoom 12, at most 40, nearest the middle of the view first, one that would sit on a name already placed left
    out, and the held one always named. `PointOverlay` is GL and compile-only like every layer.
  - **The view preference is untrusted text**: a colour that is not `#RRGGBB` is dropped (it reaches the map as a style value), a damaged value is no views, and an unreadable entry costs only its colour.
  - **The Points section of the sheet** (`feature-workspace`: `PointsViewModel`, `PointsScreen`, `PickedFile`). The list (name, count, colour, hidden or not, sync state, conflict copies settled with the
    same three choices as a diagram), *Import .LPS*, Hide/Show, Colour (the route palette; anything else is ignored, since a colour reaches the map as a style value), Rename (blank refused in words
    at the field), Delete (asks first; **Keep it is the prominent answer**). **The file comes through the system's picker** (`OpenDocument`, `*/*`: an `.LPS` has no media type of its own and the reader
    says what a file is), so there is no storage permission; `PickedFile` reads it bounded (32 MB: past that it is not a set of local points and is not held in memory), names the set from the picker's
    display name, and **catches anything** a provider throws, because a provider is someone else's code and reading a chosen file must not end the app. The words shown are the app's own.
  - **A held local point** shows what the file said of it (grid, degrees, elevation in whole feet with a comma, group, description; none of what the file left out) and offers two things for the route being
    worked on: *Add to <route>* appends it as a named `turn` point with its charted elevation and holds it (the held route, else the only one; with two and none held it offers nothing rather than guess), and
    *Use for <route>: <point>* moves the held route point onto it, name and elevation too, exactly as typing its name does. Each is one undo step. The sheet rests at its peek, so the card needs the sheet pulled
    up, as the graphics inspector does.
  - **A name typed on a route point snaps it onto a local point** (`RoutesViewModel.renamePoint`): the field says so *before* it is saved ("Matches a local point: saving puts this point on it, at 1,730 ft."),
    and a local point with no elevation leaves the route point with none, as the web's rename does. Hidden sets count.
  - **Layout trap found by a test:** the design system's buttons are full width by default, so two in a `Row` push the second out of bounds, where it has no size and cannot be tapped (the delete
    confirmation's *Keep it* was invisible). Give each `Modifier.weight(1f)`. A Compose test whose content grows after a click needs a window to scroll in (`fillMaxSize().verticalScroll`).
  - **Not built yet:** a strip in the sheet's peek for the held point, and a repository `edit` of the points themselves (the
    web cannot edit them either).
- **Weather** (`core-network`: `ApiClient.weather`, `WeatherReportDto`; `core-model`: `Weather.kt`; `core-data`: `WeatherMapping`, `WeatherService`, `WeatherCodec`; `app`: `WeatherFileCache`;
  `feature-workspace`: `WeatherSection`, `WeatherUi`). The tiles of the web's `MissionSummary` (wind with its arrow and gusts, temperature, altimeter, the station and its flight category) and the
  NOTAMs within 10 nm of an analysed diagram's target, from `GET /api/weather` (always 200: a failure inside the server is a body full of defaults, recorded from the real route in
  `test_network_fixtures.py` with the two outside services stubbed, and held to `openapi.yaml`). Things that are easy to get wrong:
  - **The server's "nothing" is in the body, not the status.** No station answered: `station_id` is `TIMEOUT`, the numbers are null and `pressure` is the text `--`. The app reads that as *no report*
    (`WeatherMapping`), and shows "no station reported" rather than a station called TIMEOUT. A number is read only if it is a JSON number (the server sends `pressure` as a number or as `--`, an int
    or a float for the rest): the DTO takes them as `JsonElement`, because a typed double would refuse an int and `DtoFixtureTest` decodes every recorded response with unknown fields forbidden.
  - **NOTAMs have three answers, and "could not search" is not "none".** The server sends an object of lists by kind, `{"Clear": [...]}`, or a *sentence* when the FAA search failed. The model keeps
    `Notams.Clear`, `Listed` and `Unavailable` apart, and the card says "Do not take that to mean there are none: check them before flight" for the last. The NOTAM text list is searchable.
  - **Weather is fetched data, not part of the diagram.** It is not saved to the diagram or synced (the web does not save it either); `WeatherService` keeps one snapshot per diagram id with the time it was
    fetched, and writes them to an app-private file (`WeatherFileCache`: whole file replaced through a temporary one, no backup, and an unreadable file is no cache) so a launch with no signal still shows
    the last report **with its age**. **Not like the web:** the web shows the number and nothing about how old it is. Here a report older than 90 minutes reads "This report is old." in the warning colour.
  - **When it is fetched** (`ensureFresh`, from `HomeViewModel` whenever an analysed diagram opens, is analysed or has a new target): when there is nothing, what there is is older than 30 minutes, or the
    target has moved more than 1 km; one fetch at a time per diagram; a failure is not tried again for 60 s, so a screen that asks again and again cannot hammer a service that is down. *Refresh* always
    fetches. The weather is shown only for an analysed diagram, as on the web.
  - **A failed fetch never takes away what was known**: the old report stays, still carrying its own fetch time, with the failure beside it **in the app's words** (a 500's text names a Python type; it is
    never shown). A sign-out clears the lot, in memory and on disk (`AppViewModel`): where a person's landing zones are does not stay for the next account.
  - **A reading is shown as a station would say it**: whole numbers without a decimal point, the rest to one place, the altimeter to hundredths, `--` for a number that was not reported (never a zero), no
    locale (a phone's can write other digits). The wind has a spoken form for a screen reader ("Wind from 270 degrees at 12 knots, gusting 20"; "variable" with no direction).
  - **Test trap:** a service launched in `backgroundScope` never runs under `advanceUntilIdle`; the tests give it a scope on the test's scheduler (as `AnalysisService`'s do).
  - **Not verifiable here:** that the tiles read well on a real phone in sunlight (they were looked at as Roborazzi pictures in the three themes), and `WeatherFileCache` against a real file system
    (covered through `WeatherCodec` only).
  - **Not built yet:** winds aloft for the diagram (the route planner already fetches them for a route), the raw METAR text, visibility on a tile (it is in the model).
- **Threats** (`core-model`: `Threat`, `ThreatEntry`, `ThreatDraft`; `core-data`: `ThreatStore`, `EncryptedThreatVault`, `ThreatTransfer`, `ThsWriter`; `feature-map`:
  `ThreatScene`, `ThreatOverlay`, `ThreatLabels`, `ThreatHitTest`; `feature-workspace`: `ThreatsViewModel`, `ThreatsScreen`; `app`: `HomeViewModel`, `AppViewModel`). **Local only:**
  threats are never synced or persisted server-side. The owner approved sending threat coordinates transiently to the existing backend only when the person explicitly asks for an online viewshed,
  KMZ or QR flow; those calls must not retain the request or response as a threat record. No threat call exists in Android yet.
  - **Memory first, one sealed file behind it** (`ThreatStore`, `ThreatVault`): the picture is a `StateFlow` the screens and the map read. The owner approved one exception to "never persisted",
    so a phone that ends a backgrounded app does not lose a crew's picture mid-plan: the list is also kept in `noBackupFilesDir/threats.bin`, sealed by its own Keystore key
    (`EncryptedThreatVault`, the session store's pattern: written whole through a temporary file and renamed, anything unreadable is removed and is no picture), **wiped at sign-out** and
    **48 hours after the last change** (`ThreatStore.RETAIN_MS`). The 48 hours run from the last *change*, not the first threat (a picture being worked in stays, a forgotten one goes), are checked at
    launch and when the app comes to the front (`AppViewModel.appStarted`, ON_START), and a clock set back is never a reason to wipe.
  - **Rules in the store worth keeping:** a write queued before a wipe must not bring the picture back (the write saves what the picture is *when it runs*, under the lock a wipe takes, and an empty
    picture is removed, not saved); a file that cannot be written costs the next launch its picture and never throws at the editor; a vault that fails to wipe cannot stop the memory having been cleared.
    `ThreatStore` never touches Room, the sync engine or any network call.
  - **The form** (`ThreatDraft`, `RadarDraft`): numbers stay text while typed; a refusal is words for the form, naming the radar and field (**not like the web**, whose `num` turns a blank number into 0 and has
    the server refuse it later). The planner's ranges: a radar 0.1 to 500 nmi, an antenna 0 to 10,000 ft, a band altitude 0 to 50,000 ft and **whole feet** (a `.ths` holds integers; a fraction would be
    cut off in the file without a word). The symbol code is 1–15 of `A–Z 0–9 * -`. Text is cut to AMPS's widths by characters. A threat opened and applied unchanged comes back identical, colours and all.
  - **`.ths` out** (`ThsWriter`, `ThreatTransfer.export`): the bundled `backend/threat_template.ths` copied to a temporary file, its `THREATS`, `THREATRADAR`, `SYSTEM` and `LINKS` emptied and `ThsExport`'s rows
    inserted with the platform's SQLite, and the temporary file (and any journal) deleted whatever happens. **Opening the database must pass `NO_LOCALIZED_COLLATORS`**, or Android adds an
    `android_metadata` table to a file AMPS expects to be exactly its schema (a test pins the schema against the template, and a mutation run showed it catches this). Held to the backend's own file: every row
    of every table equals `contracts/fixtures/sqlite/threats.ths` read back by our own reader (`ThsWriterTest`). The template's `@INFO_SCHEMA_COLUMNS` virtual table is never touched.
    **Not verifiable here:** that Android's own SQLite (which differs from Robolectric's) opens a file with a virtual table of a module it does not have, and that AMPS opens the result.
  - **`.ths` in** (`ThreatTransfer.import`): `ThsReader`, which takes a hostile file, and the web's words for a refusal; **more than 1,000 threats in one file is refused** (an invented limit, to keep a picture
    that is held in memory and in the sealed file a picture). The export file is named for the mission it travels with (`MISSION 1.ths` beside `MISSION 1.msnx`), or `threats.ths`.
  - **The Threats section** is the editor for that store: add at the crosshair, edit name/SIDC/source/information, use a common threat preset, configure each radar's range, antenna AGL/MSL,
    range ring, mask flag and three visible altitude bands; hide/show, move and remove (each with its confirmation; *Keep it* / *Keep them* is the prominent answer), *Remove all threats*, and
    `.ths` import through the system picker. Things that are easy to get wrong:
    - **A refusal is words beside Save**, not the sheet's banner (`ThreatEditUi.error`, cleared by the next keystroke), because the banner can be scrolled out of sight while typing. A threat that
      was removed (a sign-out, *Remove all*, another screen) while its form was open closes the form with "That threat is no longer here." rather than saving it back.
    - **The held threat has a card** (`HeldThreatCard`: grid, degrees, symbol name, source, information, each radar's ranges, like "Detection 25 nm · Engagement 15 nm") with Edit, Move and Remove.
      Move is the held graphic's control set: a pad of arrows with the 10/50/200 ft step (`NudgeSteps`/`NudgePad`/`EntryRow`, shared with `GraphicsScreen`), *Put at the crosshair* and a typed grid or
      coordinate (`PlaceSearch`, words for a refusal in the field). A hidden threat shows no card: nothing on the map is held, so nothing should be offered to change.
    - **A threat's ring is built from the geodesic circle** (`ThreatScene.circle`: 96 points, starting north and going clockwise, distances correct on four continents), with **continuous
      longitudes** so a ring that crosses the antimeridian is one line, not a line across the world. Until its symbol has rendered, is refused or the JavaScript sandbox is not there, a marker is a
      diamond in the affiliation's colour (`null`, `Invalid` and `Unavailable` all draw it), so a threat is never blank.
    - **Sharing a mission offers its threats.** `ThreatsViewModel.shareWithThreats` is what the Routes tab's export calls: no threats means the mission alone; any threats means the `.msnx` and a `.ths`
      named for it (`MISSION 1.msnx` + `MISSION 1.ths`) in one `ACTION_SEND_MULTIPLE` (`ShareExport.prepare` takes a list; one file is a plain `ACTION_SEND`), as AMPS needs them side by side. If the
      `.ths` cannot be built the mission is shared alone with a banner saying so: a threat problem never costs the mission. The Threats section's own share is the `.ths` alone.
    - **A shared `.ths` is cleared sooner than a mission**: files in the export cache live a day, a `.ths` an hour (`ShareExport`), and `ExportCleaner.clear` (run at sign-out, `AppViewModel`) removes
      them all, so a crew's threats do not stay in the cache for the next account.
  - **On the map**, every visible entry gets its local MIL-STD symbol and label. Detection is a dashed amber geodesic ring, engagement a solid red one; each ring is omitted when its radar says not
    to show it. A tap is tried in this order (`HomeViewModel.mapTapped`): a boundary being drawn, a route being drawn, a planning graphic, **a threat**, a route or its point, a local point. A threat
    beats a route or local point under the finger because it is the thing a crew least wants to miss; tapping a threat releases a held route point and local point (the route stays held), and
    tapping the held threat, or nothing, puts it down. `ThreatSelection` is view state and is never retained or exported. Not verifiable here: the rings (`ThreatOverlay` is GL and compile-only).
  - While a threat picture or editor is present, `ThreatsHost` sets Android's `FLAG_SECURE` (so the recents screen, screenshots and screen recording show nothing of it); it restores the prior window
    state when the threat UI is empty or leaves composition. Not verifiable here: what a device does with it.
  - **The terrain mask** (`core-network`: `threatMask`; `core-data`: `ThreatMasks`; `feature-map`: `ThreatScene.masks`, `ThreatOverlay`; `feature-workspace`: `TerrainMaskControls`): where a threat's
    radars can see over the terrain, as on the web, from `POST /api/threat-mask` (described in `contracts/openapi.yaml` and recorded from the real route). **It is the one thing that sends a threat
    anywhere, and only when the person presses *Show terrain mask* on the held threat's card**: the position and the radars (nothing else: not the name or the notes) go up once for that answer and
    the server keeps nothing. Nothing asks by itself: not when a threat is made, moved, edited or at launch, and the card says what is sent beside the button. A mask is a picture held **in memory
    only** (not in the sealed file), gone when the threat is removed or at sign-out; a threat moved or whose radars were changed after its mask was made has a mask that is **out of date and not
    drawn** (`ThreatMask.isFor`), and the card says so and offers *Update mask*. A failure is the app's own words (the server's text, which can be a Python exception's, is never shown). The
    server returns a coloured PNG per radar over one box, the bands' colours and transparency already in it; the app lays it under the range rings. **Not verifiable by a screenshot:** the window
    is `FLAG_SECURE` while threats are on it, so `adb screencap` is black. On an emulator, `adb emu screenrecord screenshot <dir>` captures the framebuffer. An on-device viewshed (offline) is
    not built.
  - **Not built yet:** KMZ and QR, an on-device (offline) terrain mask, and the Threats section's own sharing beside a *saved* mission (the Routes export offers the matching `.ths` for a sketch,
    not for an imported mission).
- **Files from other apps** (`core-formats`: `FileKinds`; `core-data`: `IncomingFiles`, `FileInspector`; `feature-workspace`: `IncomingViewModel`, `IncomingHost`;
  `app`: `incoming/IncomingIntents`, `IncomingIntake`, `MainActivity`). An `.LPS` or `.ths` opened with the app from Files or a mail, or shared to it, is read, shown to the person, and
  imported only if they say yes. The manifest declares VIEW (content scheme) and SEND / SEND_MULTIPLE for `application/octet-stream`, `application/x-sqlite3` and `application/vnd.sqlite3`
  and **nothing broader**: these formats have no media type of their own, and `*/*` would offer the app for every file on the phone (`IncomingManifestTest` holds the filters to that).
  Things that are easy to get wrong:
  - **The intent is hostile input.** Any app can start the activity with any address, so only a `content://` address is read: a `file://` one could name the app's own private files, and
    the app's own `FileProvider` authority is never read back in. At most 5 files are taken, each at most 32 MB, and everything refused is *counted and told to the person* (a file that
    vanishes without a word looks like a bug). An emailed link is also a VIEW, with an https address: it is the auth flow's (`AuthLinks`), neither taken nor refused here.
  - **Read at once, while the grant lasts.** The sender's permission to read a content address ends with the task that was given it, so `MainActivity` reads in `onCreate`/`onNewIntent`
    (`IncomingIntake`, off the main thread) and holds the bytes; the intent is then replaced by a plain launch so recreating the activity does not offer the file again.
  - **Nothing is imported on arrival.** The bytes wait in `IncomingFiles` (memory only, bounded to 8 entries and 64 MB, cleared at sign-out like the threat picture and the shared exports),
    `FileInspector` says what each is without importing it, and `IncomingHost` (a dialog, because nothing else should be tapped meanwhile) asks: *Add 7 threats?* / *Save 1,234 points?*.
    A tap outside does not answer, so a stray touch cannot lose a file; Back means "not now". Accepting goes through the same code as the sheet's own import (`ThreatStore.addAll`,
    `PointSetRepository.import`), so a file means the same however it arrived. A threat file is told it stays on the device; a points file is told it syncs with the account.
  - **What a file is comes from what is in it** (`FileKinds`): the zip header is a mission, a SQLite file is asked for its `THREATS` / `Points` tables; the extension decides only when the
    content cannot (a file cut short), so the right reader refuses it in its own words. A database with both tables goes by its name. A damaged file is never an exception.
  - **A mission is brought in as a copy** (`FilePreview.Mission`, `MissionRoutes`, `MissionImporter`): *Bring in 2 routes?* makes a **new set of routes** (sketched routes, saved and synced like any set
    the person draws) from the mission's routes, with their points, the plan AMPS recorded and the ground elevations, opens it, and takes the map to it (`MapFocus` → `MapViewModel.showArea`, at the
    zoom `MapZoom.fitting` works out for the routes' extent). **The file is kept as the saved document** and the dialog says so (*Saved missions*, below): the mission is its file, exactly as on the web. The Routes tab's **Import mission** button opens the system picker and feeds the same dialog, so a file means the same however it arrives. `.msnx` arrives as octet-stream
    from most senders, so it reaches the dialog by the same filters as the other two.
  - **Not verifiable here:** that a real Files app, Gmail or the share sheet offers the app and passes a readable address, how the dialog looks over the real map, and that
    `application/octet-stream` is what AMPS-adjacent senders actually label these files. If a sender uses another type the app is simply not offered for it: add the type to the manifest and to
    `IncomingManifestTest`.
- **Aircraft profiles** (`core-data`: `AircraftProfiles`; `core-model`: `AircraftDraft`; `feature-workspace`: `AircraftViewModel`,
  `AircraftScreen`, `AircraftPicker`; `app`: `AircraftChoicePreferences`).
  - **Two sources, one list.** The admin's master list comes from `GET /api/aircraft-profiles` (`is_system`), is kept in a file
    (`aircraft-master.json`, `FileMasterProfileStore`) so the airframes are there with no signal, and is refreshed quietly at launch
    (`refreshQuietly`; a failure leaves what was kept; a server that sends no master airframes is a fault, not an empty list). The user's own
    profiles are sync records (`RecordKind.AIRCRAFT`), so they are made and changed offline and follow the user to another device.
  - **The mission aircraft** is chosen by *slug* (kept in a preference, as the web keeps it in `localStorage`), so it survives a database that
    renumbers ids; when it is not in the list (removed, or not loaded yet) the UH-60L stands in, as on the web, and the choice is not rewritten
    behind the person's back. It is what a new aircraft is placed as, what capacity is measured for, and what a route will start from; aircraft
    already on a diagram keep the airframe they were placed as.
  - **The server names a profile, not the app.** A profile made here has no slug until the server has seen it (it makes one from the designation),
    so it is listed ("Waiting to sync before it can be chosen") but cannot be chosen or put on a diagram: a diagram that named a slug nobody knew yet
    would name nothing once the real one arrived. `AircraftEntry.usable` / `waiting` say which.
  - **A conflict copy** (the user's version, kept beside what another device changed) is listed so it can be settled with the same three choices a
    diagram has, and is never chosen: it carries the same slug as the profile it is a copy of.
  - **The form is held to the server** (`contracts/fixtures/aircraft/limits.json`, probed from the backend's own `_apply_fields` by
    `backend/tests/test_contract_fixtures.py`), because a profile the server refuses stays in the outbox for good. **Not like the web:** the web's `num`
    turns a blank or unreadable number into the form's default, silently; here it is refused with the field named (a rotor diameter nobody typed is
    a footprint nobody chose). The altitude limits are not in the form (the web has none), so a profile being changed keeps its own.
  - **An account whose `aircraft_profiles` feature is off** (`ApiUser.hasFeature`) can choose from the admin's list but is not offered New, Copy or Edit:
    the server answers a create with 403 `feature_disabled`, which the outbox would hold as blocked.
  - Deleting an own profile that is the mission aircraft falls back to the UH-60L; diagrams placed with it keep the geometry they were placed with
    (it is in the diagram).
  - **Not done:** the map icon is chosen by name, not by its picture; a route does not yet start from the mission aircraft's defaults (there are no routes yet).
- **Symbols and units** (`core-symbols`, `core-model`: `Sidc`, `UnitDraft`; `feature-map`: `UnitMarker`; `feature-workspace`: `UnitBuilder`).
  - **One source of truth for what a symbol looks like: the web's milsymbol.** The plan's decision (milsymbol in the system JS engine, not a native
    2525 library) is why: the symbols match the web exactly and keep the 2525C SIDCs that `.ths` files carry. `android/core-symbols/src/main/assets/
    milsymbol.js` is the web's `node_modules/milsymbol/dist/milsymbol.js` **byte for byte** (a contract test fails if they differ); to upgrade
    milsymbol, copy that file over, run `UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/symbolFixtures` and review the diff.
  - **Three things are held by `frontend/src/contracts/symbolFixtures.test.js`**: the SIDC logic and preset lists (`symbols/sidc.json`); the
    pre-rendered preset SVGs, each with the size and anchor milsymbol gave it (`symbols/svg/*.svg`, `symbols/presets.json`; the app ships these files
    as its assets, so they are the web's own pictures); and `ezpz-render.js`, the script the app evaluates in the sandbox, which the test runs
    against the vendored milsymbol **in a vm context with no DOM** (as the sandbox is) and holds to the web's own answer for 19 cases. Real answers are
    in `symbols/script.json` for `ScriptAnswer` to be read against, with the exact expression the app evaluates.
  - **Order**: a preset with no labels draws from its pre-rendered SVG (any size, as a vector); anything else, and anything with a designation or a
    formation (the labels are part of milsymbol's picture), goes to the sandbox. If the sandbox is not there the answer is `Unavailable`, which is **not
    cached** (so the symbol appears when the sandbox does) and which the map shows as a plain marker in the affiliation's colour, so a unit is never
    missing; a code milsymbol refuses is `Invalid`, cached, shown the same way. The builder's preview says which.
  - **The anchor is milsymbol's, not the middle of the picture**: a headquarters symbol stands on the end of its staff, and labels widen the picture.
  - **Not verifiable here (flagged, compile-only):** `JavaScriptSymbolSource` — it needs a WebView with the JavaScript sandbox on a device. It
    sends milsymbol (about 860 KB) in one evaluation when the sandbox says it can go past the binder limit (`JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT`)
    and otherwise in 150 KB pieces joined and `eval`led in the isolate; both paths are untried. **The first thing to do on a device**: add a unit with a
    designation and see its symbol, then an airframe-less preview in the builder. It starts when first needed, retries a failed start after 60 s, and
    never lets a failure reach the person.
  - **Units** are `{id, type?, path?, sidc, uniqueDesignation, higherFormation, lat, lon}` as the web keeps them. They are added from the unit builder at the
    crosshair (the web drops them a random few metres off the target), only on an analysed diagram, like every graphic. **Applying the builder to a unit
    changes its symbol and labels and nothing else** (an older image unit keeps its `path`). As on the web, the builder knows five parts (affiliation,
    function, echelon, the two labels): a unit made with another dimension or status is shown as ground and present once opened, and a function with no name
    is kept and shown as `Other (code)`. The web's **threat presets are 14 characters**, one short of a SIDC, and milsymbol draws them anyway; they are kept
    as they are because a threat's SIDC is what an AMPS `.ths` carries.
  - **A unit that is an old image** (no `sidc`) is listed as "Unit (image)" and drawn as the plain marker; it is not offered a symbol until the builder is applied.
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

**Rules that carry over unchanged:** unclassified only; threats are never synced or persisted server-side (an explicit online viewshed/KMZ/QR action may send coordinates transiently); no secret in
the app binary; no new dependency beyond what the plan's owner-approved list covers (ask first for anything else).
