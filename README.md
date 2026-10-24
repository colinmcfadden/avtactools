# EZ/PZ

**EZ/PZ** is a planning tool for military helicopter crews, built around the UH-60. A planner picks a
target by MGRS grid. The app finds and measures a usable landing area in the imagery, judges the slope
against the aircraft's limits, and lays out the tactical graphics a crew briefs from: aircraft positions,
PZ markers, sectors of fire, go-arounds, doghouses and units. The plan then becomes the products crews
carry: LZ cards, AMPS mission files, ForeFlight and ATAK hand-offs, threat files and a mission brief.

- **Who it is for:** Army aviators. Signing in needs a verified `.mil` address or an admin's approval.
- **Where it runs:** a React web app at [ezpztac.app](https://ezpztac.app), a Flask API, and an Android
  app for phones and tablets (in development). iOS is planned.
- **Status:** public beta. The version is in `frontend/package.json` and `backend/version.py`, both
  written by semantic-release.
- **Unclassified only.** Nothing here is an authorized boundary for CUI or classified data, and no
  controlled data goes into the repository.

> **Working on the code?** Read [`AGENTS.md`](AGENTS.md) first. It is the detailed, current record of the
> architecture, the rules and the lessons learned, and it wins wherever this README is shorter.
> [`docs/HANDOFF.md`](docs/HANDOFF.md) says where the Android work stands and what is next.

---

## What it does

| Area | What a planner gets |
|---|---|
| **LZ/PZ workspace** | Several LZ/PZ diagrams open at once, each targeted by MGRS. The cursor readout converts to MGRS in the browser as the mouse moves |
| **Terrain analysis** | SAM finds the landing area's boundary in satellite imagery, or the planner draws it. A slope map from elevation data, with contour lines, a slope profile along and across the landing heading, and a call against the UH-60's limits for that heading and the reported wind |
| **Planning graphics** | Aircraft to scale with keep-out discs and separation alerts, PZ markers, sectors of fire, go-arounds, doghouses (which set the landing and takeoff headings), MIL-STD-2525C unit symbols |
| **Routes** | Sketch routes on the map, or import them from AMPS (`.msnx`). Plan airspeed, altitude, wind (fetched aloft), fuel and time on target, with a nav log. Export back to AMPS, or hand off as GPX or Garmin FPL |
| **Threats** | Import and export AMPS `.ths` files, range rings, and a terrain-masking viewshed per altitude band. KMZ for ForeFlight, QR for a phone |
| **Local points** | AMPS `.LPS` point files on the map. Typing a known name on a route point snaps it there |
| **Weather** | METAR, NOTAMs within 10 nm, and winds aloft for routes |
| **3D LZ view** | The USGS LiDAR point cloud of the LZ over DEM terrain and imagery, with routes drawn at their planned altitude (web in Cesium, Android natively) |
| **Exports** | LZ card image and Excel card, AMPS mission package, threat files, mission brief PDF and live sandtable |
| **Library and sync** | Saved LZs, routes, point sets and aircraft profiles, synced across devices with conflicts kept side by side, never overwritten |
| **Mission packs** | A shared container for one operation: teams, invitations, roles, a live stream of edits, history, finish and reopen. Currently enabled for admins and testers only |
| **Admin** | A server-rendered dashboard for users, access approval, feature entitlements and the master aircraft list |

---

## Architecture

```
 Browser: React 19 SPA (Vercel)            Android app (Kotlin, Compose, MapLibre)
   Leaflet 2D map, Cesium 3D view             native map, native 3D renderer
        │                                           │
        └──────────── HTTPS + bearer JWT ───────────┘
                              │
                 Flask API (gunicorn, 1 worker × 8 threads)
                  ├─ Postgres (Supabase; SQLite locally)
                  ├─ SAM model (Ultralytics)                LZ boundary detection
                  ├─ local DEMs (USGS 3DEP, DTED2)          slope, 3D terrain heights
                  ├─ public tile and data services          Esri imagery, AWS Terrarium,
                  │                                         aviationweather.gov, FAA NOTAMs
                  ├─ tileset store /data/tiles ◄──────────┐
                  └─ build requests ──► LiDAR build service (PDAL + py3dtiles,
                                         its own container, internal only)
 Mission packs ◄── WebSocket ── live service (backend/realtime): listens on Postgres,
                                 asks the API who may see each pack
```

- **Frontend** (`frontend/`): a Create React App SPA. Each feature lives in its own folder under
  `src/feature/<name>/`, with a `use<Name>.js` hook that owns its state; `App.js` composes them. The menu
  redesign gives it a top bar (workspace, Import, Library, account), a right dock of panels (LZ/PZ,
  Routes, Threats, Imports), and a bottom sheet below 1100 px wide.
- **Backend** (`backend/`): Flask with one blueprint per area under `routes/`. Auth is a JWT with a
  session version (a password reset revokes older tokens); the native apps get refresh tokens and device
  sessions. Saved records sync through a per-user change feed ordered by a counter, not a timestamp, and
  a deletion is a tombstone. The admin dashboard is Jinja templates under `templates/admin/`.
- **Android** (`android/`): a Gradle project of pure-Kotlin core modules (geometry, planning, file
  formats, network, sync, mission packs) and Android libraries (data on Room, design system, symbols,
  map, screens). It works offline-first: plans live on the device and sync when there is a signal. See
  [`android/README.md`](android/README.md) and [`docs/NATIVE_APPS_PLAN.md`](docs/NATIVE_APPS_PLAN.md).
- **Contracts** (`contracts/`): golden fixtures the web, the backend and the Android app are all tested
  against, plus the API description (`openapi.yaml`) and the design tokens. **The web is the reference:**
  a formula changes on the web first, the fixtures are regenerated, and the other clients follow. See
  [`contracts/README.md`](contracts/README.md).
- **Mission packs**: members edit one pack through a server-ordered stream of operations addressed by id,
  every change is logged, and a finished pack is read-only for everyone. Design and rules are in
  [`docs/MISSION_PACKS.md`](docs/MISSION_PACKS.md).

---

## Terrain and point cloud data

Terrain drives three things: the **slope analysis** of a landing area, **ground heights** for routes and
the 3D view, and the **LiDAR point cloud** that shows what stands on the ground. All of it is processed
on the server. The browser and the apps receive only results (an overlay, statistics, heights, a tileset),
never source files.

### Sources

| Source | Resolution | Used for | How it is reached |
|---|---|---|---|
| **USGS 3DEP 1/3″ DEMs** (GeoTIFF/COG) | ~10 m | Slope analysis, 3D terrain, route ground heights | Downloaded and mounted read-only at `TERRAIN_DATA_DIR`; never in git |
| **DTED Level 2** (`.dt2`) | ~30 m | Slope analysis where no 3DEP is mounted | Mounted the same way |
| **AWS Terrarium tiles** | zoom 9–13 (~19 m at 13) | Fallback for slope; route elevations; threat viewshed | Keyless public tiles, decoded from PNG |
| **OpenTopoData SRTM30m** | 30 m | The point elevation shown with a field analysis | Keyless public API |
| **Esri World Imagery** | zoom 14 tile | The image SAM segments | Keyless |
| **USGS 3DEP LiDAR** | ~2.75–3.5 pts/m² (modern surveys) | The 3D point cloud | AWS Entwine indexes (default), or downloaded LAZ tiles |
| **Mapbox satellite** | to level 20 (point colours from 18) | Basemaps, 3D imagery, point colours, LZ card imagery | Public `pk.` token |

### Slope analysis (`POST /api/terrain-analysis`, `backend/terrain_provider.py`)

1. **Pick the elevation.** In `auto` mode (`TERRAIN_SOURCE`), local data comes first: a mounted GeoTIFF or
   COG at 15 m or finer, then DTED2, then Terrarium. `LocalRasterCatalog` walks `TERRAIN_DATA_DIR`, reads
   each raster's footprint, resolution and vertical datum, and rescans every 5 minutes, so a newly
   mounted DEM is picked up without a restart. The LZ's bounds plus 40 m of padding are read into a
   metric grid (UTM), mosaicking across files where needed.
2. **Compute gradients.** A Horn 3×3 operator gives the slope in degrees and the east and north gradients
   for every cell. Cells with a gap anywhere in their 3×3 neighbourhood stay empty; no value is invented.
3. **Clip and summarise.** The LZ polygon is rasterised as a mask. The response carries a clipped,
   colour-banded PNG (bands at 3, 6, 10 and 15°), its bounds, the source, resolution and vertical datum,
   and statistics.
4. **Judge every heading.** `byHeading` lists the steepest nose-up, nose-down and cross slope at each whole
   heading. Only cells on the convex hull of the gradients can be a heading's steepest, so this costs a few
   dozen cells per heading. The clients judge from that table against the UH-60's limits (6° nose down, 15°
   nose up, 15° cross slope, each cut by 2° for every full 5 kt of steady wind, and no nose-down landing
   above 15 kt). Turning the landing doghouse or a new weather report therefore needs no new request. The
   rules are in `feature/terrain/slopeCall.js`, mirrored on Android and held to
   `contracts/fixtures/planning/summary.json`.
5. **Contours and profiles.** Contour lines of equal height (in feet, at the finest interval that keeps
   about a dozen lines) cover the LZ and a 40 m ring round it, lightly smoothed and simplified. Slope
   profiles are straight lines through the LZ's centroid every 2° of heading, run 300 m past the LZ each
   way. The clients pick the line nearest the landing heading and draw the ground along and across it.

The LZ boundary itself usually comes from `POST /api/analyze-field`: the Esri tile under the target is
segmented by SAM (`sam_b.pt`, prompted with the target's pixel), and the mask is turned back into a
lat/lon polygon. SAM keeps state between calls, so it runs one analysis at a time behind a lock.

### Ground heights and the 3D terrain (`backend/terrain_tiles.py`)

- **Route and point heights:** `POST /api/elevations` samples Terrarium (feet, for the planner and AMPS).
  `POST /api/terrain/heights` samples the local DEMs and returns ellipsoidal heights with the geoid
  separation, for placing things in 3D.
- **Terrain for the 3D view:** `GET /api/terrain/heightmap/<level>/<x>/<y>` returns a 65 × 65 grid of
  little-endian Int16 metres on Cesium's geographic tiling (two tiles at level 0), or 204 where no DEM
  covers it. Heightmaps were chosen over quantized-mesh on purpose. At the scale of one LZ a fixed grid is
  already finer than the 1/3″ source, and it needs no pre-generation step and no storage, and picks up
  newly mounted DEMs at once.
- **Speed:** a computed tile is cached on disk (`TERRAIN_TILE_CACHE_DIR`), keyed by a fingerprint of the
  DEMs, the tile code and the geoid. Levels 0–9 over the DEM coverage are computed at startup
  (`TERRAIN_WARM_LEVEL`), because the coarse levels are the slow ones. While an analysis, viewshed or
  export runs, clients stop asking for new tiles and the warm-up pauses, because tile work and SAM share
  one Python interpreter.

### The geodesy trap

USGS DEMs and LiDAR use **NAVD88 orthometric** heights; Cesium and 3D Tiles use **WGS84 ellipsoidal**
heights. In north Georgia the difference is about **−30 m**. Both the terrain tiles and the LiDAR pipeline
convert with PROJ's geoid grids (EPSG:5703 to EPSG:4979), and they must agree, or the point cloud floats
above or sinks below the ground. The catch: **without the geoid grids, PROJ passes heights through
unchanged and reports success.** So the code checks the separation it actually got
(`lidar/crs.py: assert_vertical_datum_applied`, `terrain_tiles.geoid_grids_available`). The LiDAR image
bakes the grids in and refuses to build without them. For local development, set `PROJ_NETWORK=ON`.

### The LiDAR point cloud (`backend/lidar/`, `backend/lidar_builder.py`, `routes/lidar_routes.py`)

**Finding a survey** (`lidar/coverage.py`). USGS publishes every 3DEP project as an Entwine (EPT) index on
AWS Open Data, with one GeoJSON of every project's footprint. Choosing a survey for a target is a
point-in-polygon test against it. Ground is often covered several times by surveys of very different
quality, so they are **ranked by the year in the survey's name** (publication dates mislead: an ARRA 2010
survey was republished in 2023). Surveys from about 2015 on classify vegetation; older ones classify ground
only. North Georgia's good one is `GA_Statewide_B3_2018`.

**Building** (`lidar/build.py`, `lidar/pipeline.py`, `lidar/tiles.py`). One build path serves the build
service, the CLI (`python -m lidar`) and `tools/build_lz.py`:

1. **Read.** From the AWS Entwine index, a spatial query for just the area with nothing downloaded first;
   or from a downloaded collection of LAZ tiles (`LIDAR_COLLECTION`) when it covers at least 95% of the
   area. A target near the edge of a download goes to AWS rather than being built with a side missing. A
   collection can mix surveys in different coordinate systems; each tile carries its own.
2. **Crop and filter** with PDAL. The area is a 500 m radius by default (1 km at most for a build). Points
   are kept by ASPRS class: ground and low, medium and high vegetation (2–5) from a modern survey, or
   created, unclassified and ground (0–2) from an older one, which labels nothing else. Noise is dropped.
3. **Colour.** Each point takes the colour of the Mapbox satellite imagery under it at level 18, sampled
   through a GDAL WMS raster (`lidar/mapbox_imagery.xml`). Level 18 rather than 20: level-20 tiles are
   level 18 enlarged, and fetching 16 times the tiles took a 500 m build from 137 s to 707 s for the same
   colours. Points must be in Web Mercator for the lookup, so a collection in Albers is reprojected first
   (without that, Georgia's points were coloured from imagery in Nigeria). With no imagery, points are
   coloured by class. Colour is baked into RGB because `.pnts` files carry no classification.
4. **Reproject** to WGS84 geocentric (EPSG:4978), the frame 3D Tiles uses, through the geoid correction.
5. **Tile** with py3dtiles into a Cesium 3D Tiles point-cloud tileset. Full density runs out to 1 km:
   3D Tiles streams by level of detail, so a large area costs build time and disk, not viewer memory (a
   688,375-point tileset loads about 2,670 points with the camera pulled back). An optional thinned
   context ring (`LIDAR_BUILD_CONTEXT_M`) adds landscape without thinning the landing area.

**The build service** (`backend/lidar/Dockerfile`, `lidar/worker.py`) is a separate, internal-only
container with PDAL, py3dtiles and the geoid grids. The API forwards build requests to it and relays
progress; the API never runs the toolchain. The service builds one area at a time from a bounded queue,
builds someone is watching first. A build nobody has polled for 90 s is dropped, and saved LZs build in
the background with `keep`. Each build is staged under `.staging-<key>` and moved into place only when
complete, so a half-built tileset is never served. Measured from AWS: about 137 s for a 500 m radius.

**Serving and privacy.** Tilesets live in a shared store (`/data/tiles`, `LIDAR_TILES_DIR`) under opaque
hash keys, so a URL never reveals a location. Each has an `area.json` manifest, and
`POST /api/lidar/resolve` returns the nearest tileset whose area contains the target. Coordinates only ever
travel in POST bodies; progress is read by key; the service logs keys and stage names, never coordinates.

**Viewing.** The web loads Cesium from its prebuilt bundle in `public/cesium` (importing it through
webpack breaks production builds). It shows the point cloud over the heightmap terrain and Mapbox
imagery, with eye-dome lighting, routes at their planned MSL with curtains to the ground, and a compass.
Depth testing against terrain is off, so points and low routes are not hidden by coarse distant terrain.
Android draws the same scene with its own native renderer
([`docs/ANDROID_3D_PLAN.md`](docs/ANDROID_3D_PLAN.md)).

Setup for the build service, and building by hand, are in
[`backend/lidar/SERVER_SETUP.md`](backend/lidar/SERVER_SETUP.md). Planned next is serving 3D straight from
USGS's octree and prebuilt areas managed from the admin dashboard
([`docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md`](docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md)).

### Threat masking

A radial line-of-sight viewshed over Terrarium tiles, with earth curvature and refraction, one mask per
altitude band. The DEM is smoothed and the observer's height taken over a 3×3 footprint, because one
~60 m pixel beside a low antenna otherwise casts a false shadow over a whole quadrant. KMZ masks are vector
polygons because ForeFlight will not render raster overlays.

---

## Repository layout

```
avtactools/
├─ frontend/            React SPA: src/feature/<name>/ per feature, src/utils/ (MGRS, coordinates),
│                       public/ (AMPS template, Cesium copied in at build)
├─ backend/             Flask API (Python 3.11)
│  ├─ app.py            app factory, CORS, JWT, the affiliation gate, blueprints, column migrations
│  ├─ routes/           one blueprint per area (auth, terrain, lidar, threats, weather, packs, admin…)
│  ├─ terrain_provider.py, terrain_tiles.py    DEM catalog, slope, contours, profiles, 3D heights
│  ├─ lidar/            the point-cloud pipeline and build service (its own Docker image)
│  ├─ realtime/         the mission packs' live WebSocket service (its own image)
│  ├─ templates/admin/  the admin dashboard
│  └─ tests/            pytest
├─ android/             Gradle project: core-* (pure Kotlin), core-data, feature-*, app
├─ contracts/           fixtures, openapi.yaml and design tokens the clients are tested against
├─ tools/               operator CLIs: find_lidar.py, build_lz.py
├─ docs/                user guide, plans, the hand-off, native design
└─ AGENTS.md            the detailed engineering record
```

---

## Running it locally

The backend needs Python 3.11 or later (3.14 works), and the frontend Node 22. The Android build needs JDK
21 and the Android SDK (platform 37, build-tools 37.0.0). The commands below are for macOS or Linux; the
Windows PowerShell equivalents, and what trips a fresh machine, are in [`AGENTS.md`](AGENTS.md) §11.

**Backend** at `http://127.0.0.1:5000`:

```bash
cd backend
python3.11 -m venv venv
./venv/bin/pip install -r requirements.txt pytest
# Optional: local DEMs, and PROJ's geoid grids fetched on demand (needed for correct 3D heights)
export TERRAIN_DATA_DIR=/path/to/dems PROJ_NETWORK=ON
./venv/bin/python app.py
```

The first start downloads the SAM weights (`sam_b.pt`, ~358 MB) into `backend/`. Locally the database is
SQLite (`backend/ezpz.db`) and email links print to the log. Make a verified local account with:

```bash
./venv/bin/python dev_user.py you@example.com --admin
```

**Frontend** at `http://localhost:3000` (use `localhost`, not `127.0.0.1`: that is the origin CORS allows):

```bash
cd frontend
cp .env.example .env     # without it every API call goes to the dev server and sign-in fails silently
npm install
npm start                # copies Cesium into public/cesium first
```

**Android** (from `android/`, with `sdk.dir` in `android/local.properties`):

```bash
./gradlew assembleDebug -Pezpz.apiUrl=http://10.0.2.2:5000/    # the emulator's view of this machine
```

A real phone on USB uses `http://127.0.0.1:5000/` with `adb reverse tcp:5000 tcp:5000`. Against staging,
which has the LiDAR build service and the tiles, use `-Pezpz.apiUrl=https://dev-ezpz-api.mcfadd.in/`.
[`docs/HANDOFF.md`](docs/HANDOFF.md) §4 covers signing in to a local backend from a device.

**The LiDAR build service** is optional locally. Without it the 3D window offers a command for an operator
to run. To build one:

```bash
docker build -t avtac-lidar:dev backend/lidar
```

Then follow [`backend/lidar/SERVER_SETUP.md`](backend/lidar/SERVER_SETUP.md).

### Tests

```bash
cd backend && ./venv/bin/python -m pytest tests -q
cd frontend && CI=true npm test && npm run build     # the build catches lint errors the tests miss
cd android && ./gradlew test testDebugUnitTest lintDebug assembleDebug
python contracts/scripts/mgrs_fixtures.py check     # MGRS fixtures against PyGeodesy
```

CI runs the Kotlin modules, the contract fixtures and the backend's contract tests, each only when its
paths change. The rest of the backend suite and most of the web's Jest suite run only locally.

---

## Configuration

Every backend environment variable is listed in [`AGENTS.md`](AGENTS.md) §10. The ones to know first:

| Variable | Purpose |
|---|---|
| `JWT_SECRET_KEY` | Required in production (32+ characters); startup refuses without it |
| `DATABASE_URL` | Postgres (Supabase session pooler); unset means SQLite |
| `CORS_ORIGINS` | Allowed origins, comma-separated (**plural**) |
| `TERRAIN_DATA_DIR`, `TERRAIN_SOURCE` | Local DEM directories; `auto`, `local` or `terrarium` |
| `TERRAIN_TILE_CACHE_DIR`, `TERRAIN_WARM_LEVEL` | The 3D terrain tile cache and its startup warm-up |
| `LIDAR_TILES_DIR`, `LIDAR_BUILDER_URL`, `LIDAR_BUILDER_TOKEN` | The tileset store and the build service |
| `RESEND_API_KEY`, `EMAIL_FROM` | Transactional email (production refuses to start without them) |
| `REALTIME_PUBLIC_URL` | The mission packs' live stream; unset means clients poll |

The frontend reads `REACT_APP_API_URL` (the API base **including** `/api`) and
`REACT_APP_GOOGLE_CLIENT_ID`, inlined at build time. Authentication and email setup are in
[`AUTHENTICATION.md`](AUTHENTICATION.md); terrain data handling is in
[`backend/TERRAIN_DATA.md`](backend/TERRAIN_DATA.md).

---

## Deployment

| Piece | Where | How it deploys |
|---|---|---|
| Frontend | Vercel, `ezpztac.app` | `main` to production, every PR to a preview |
| Backend | Coolify on a home server behind a Cloudflare Tunnel (`prod-ezpz-api.mcfadd.in`); also a Fly app | Coolify on push; Fly by hand (`fly deploy`) |
| Staging | The same Coolify host, `dev-ezpz-api.mcfadd.in`, with its own Supabase project | On push to `develop`; Vercel previews point at it |
| LiDAR build service | Beside the backend, no public address | On push |
| Database | Supabase Postgres, session-mode pooler | — |

The backend runs **one gunicorn worker with 8 threads**. SAM holds about 1 GB resident, and the rate
limiter and a few small stores are in-process, so more workers need those moved to Redis first. Details
and the traps (Cloudflare's `TRUSTED_PROXY`, Coolify's Dockerfile rewriting, Supabase's IPv6-only direct
host) are in [`AGENTS.md`](AGENTS.md) §9.

---

## Contributing

- **Branches:** `develop` is the integration branch and every PR's target; `main` is production. Work on
  `feat/…` or `fix/…`; release through `release/*` into `main`; hotfixes branch from `main`.
- **Commits** follow [Conventional Commits](https://www.conventionalcommits.org), enforced by commitlint,
  with a lowercase subject (`feat: add lidar tiles`). App-only work is scoped: `feat(android): …`.
- **Releases are automatic** when `main` moves. semantic-release reads `feat` (minor) and `fix`/`perf`
  (patch) commits; a major release needs a `BREAKING CHANGE:` footer (a `!` alone releases nothing under
  this configuration). It bumps both version files, writes `CHANGELOG.md`, tags, and publishes a GitHub
  release; `android` and `ios` scoped commits release nothing.
- **Don't commit data:** `.env*`, databases, `*.pt`, DEMs, LiDAR, tilesets and signing keys are ignored.
- **No new major dependency** without asking first.

## Documentation

| Document | What it holds |
|---|---|
| [`AGENTS.md`](AGENTS.md) | The engineering record: architecture, API, data model, configuration, domain knowledge, lessons |
| [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) | How to use the app |
| [`AUTHENTICATION.md`](AUTHENTICATION.md) | Sign-in, email, sessions and the security posture |
| [`backend/TERRAIN_DATA.md`](backend/TERRAIN_DATA.md) | Mounting and handling local terrain data |
| [`backend/lidar/SERVER_SETUP.md`](backend/lidar/SERVER_SETUP.md) | The LiDAR build service |
| [`docs/MISSION_PACKS.md`](docs/MISSION_PACKS.md) | Mission packs: design and rules |
| [`docs/NATIVE_APPS_PLAN.md`](docs/NATIVE_APPS_PLAN.md), [`docs/HANDOFF.md`](docs/HANDOFF.md) | The native apps' plan, and where the Android build stands |
| [`contracts/README.md`](contracts/README.md) | The shared fixtures and how to regenerate them |
