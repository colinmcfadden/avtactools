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
- **Status:** public beta. Three kinds of version, each with its own number (§12):
  the **product's** (web and backend, released together) in `frontend/package.json`
  and `backend/version.py`, written by semantic-release; **each app's own**
  (`ezpz.versionName` in `android/gradle.properties`, 0.x until the store; iOS
  0.x until its first release); and the **API's** (`contracts/openapi.yaml`'s
  `info.version`), whose major decides which app builds can talk to which server.
- **Repo:** `github.com/colinmcfadden/avtactools` · default branch `develop`.
- **Native apps:** Android (Kotlin) first, then iOS and iPadOS (Swift). Android
  has the plan's P0–P2 (§17; where it stands and what is next: `docs/HANDOFF.md`);
  iOS is not started. Read `docs/NATIVE_APPS_PLAN.md` before any mobile work.

---

## 2. Non-negotiables

These come from the owner. Do not work around them.

| Rule | Why / how |
|---|---|
| **Unclassified only. No CUI.** | Hosting is Vercel/a home server. Nothing here is an authorized boundary. |
| **Threats may be saved and shared** (the owner, 2026-10-12; before that they were never persisted or synced). | Saved like local points and route sets, as a Library item of their own that can be opened and moved as wanted, and shared in mission packs, where every member can edit them. The window need not be kept out of screenshots and nothing expires them (both dropped in S5). **Not built yet** (`docs/ANDROID_SCREENS_PLAN.md`, *T — Threats saved and shared*: the web and the server first, then Android). Until it is, the web keeps them in React state and Android in its encrypted, no-backup device file, wiped at sign-out, and nothing sends them to the server but an explicit viewshed/KMZ/QR action, which the server does not retain. |
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
| **App frame** (menu redesign, `docs/MENU_REDESIGN.md`) | Top bar (workspace, Import, Library, account); a right dock of panels (LZ/PZ, Routes, Threats, Imports) picked from an icon rail, a bottom sheet below 1100 px; the shared dialogs, menus and toasts. No browser `prompt`/`confirm`/`alert` on these paths. At 850 px and below the left sidebar is a full-screen menu behind the top bar's ☰, so the map's tool rail (`MobileQuickAccess`) offers Analyze and Draw LZ itself, in words: without them a phone had no visible way to analyse a target (the owner, 2026-10-19) | `feature/shell/`, `feature/ui/`, `components/MobileQuickAccess.jsx` | — |
| **LZ/PZ workspace** | Several LZ diagrams per session; target by MGRS; switch between them in the LZ/PZ panel | `feature/lzWorkspace/`, `feature/shell/LzPanel.jsx`, `components/MapView.jsx`, `App.js` | — |
| **Terrain analysis** | SAM finds the LZ boundary in imagery; slope map from DEM, with contour lines of the ground in and round the LZ, and a slope profile along and across the landing heading; how opaque the slope map is drawn is the person's (§13) | `feature/terrain/` | `routes/terrain_routes.py`, `terrain_provider.py` |
| **Planning graphics** | Helicopters, PZ markers, sectors of fire, go-arounds, doghouses, units, LZ box | `feature/{helicopters,pzMarker,sectorsOfFire,goAround,doghouses,unit}/` | — |
| **MIL-STD symbology** | MIL-STD-2525C symbols for units and threats (milsymbol) | `feature/symbols/` | — |
| **LZ card export** | Card image and Excel card | `feature/export/` | `routes/export_routes.py`, `export_service.py`, `lz_template.xlsx` |
| **Cloud save** | LZs, routes, point sets. First save names it (Save dialog), later saves are silent; Ctrl/⌘ S; the Library opens, renames, duplicates and deletes. Routes have no dirty flag of their own: a set is compared with what was last saved (`useRouteSaves`) | `feature/saveDialog/`, `feature/library/`, `feature/savedMaps/useSavedMaps.js`, `msnxImport/useSavedRoutes.js` | `routes/{lz_routes,saved_routes,point_sets}.py` |
| **Routes & AMPS** | Sketch and plan routes (speed/alt/wind/fuel/TOT); import/export `.msnx`; ForeFlight share | `feature/msnxImport/` | `routes/route_share_routes.py`, `route_share_store.py`, `/api/route-winds` |
| **Local points** | Import AMPS `.LPS` point files | `feature/localPoints/` | — |
| **Imports** | One way in for `.msnx`, `.LPS` and `.ths`: the Import menu, the Imports panel, or a drop on the map. A file's type is read from its content (zip = mission; SQLite with `Points` or `THREATS`), then a review dialog says where each goes (Library, this session, the open pack; threats only ever this session) | `feature/imports/` | — |
| **Threats** | `.ths` import/export, terrain-masking viewshed, KMZ, QR | `feature/threats/` | `routes/threat_routes.py`, `ths_export.py`, `threat_download_store.py`, `threat_template.ths` |
| **Weather** | METAR, NOTAMs, winds aloft | `feature/weather/` | `routes/weather_routes.py` |
| **Map layers** | The base map (satellite, topo, VFR sectional) and an **MGRS grid** over any of them, from the Layers control: zone and band boundaries (white dashes), 100 km squares, and lines at 10 km, 1 km or 100 m as the zoom allows (about 70 px apart), labelled with the zone, the square and the lines' principal digits; a label whose edge is covered (a phone's search bar) goes on the opposite edge. On or off is remembered per browser (`ezpz.mgrsGrid`), never saved with a plan. **Screen only**: the owner's call, 2026-10-08; it is left off LZ card exports. Android draws the same grid from the same code, ported and held to `contracts/fixtures/mgrs/grid.json` (§17, *The MGRS grid*) | `feature/mapStyles/`, `feature/mgrsGrid/`, `feature/export/captureFilter.js` | — |
| **Aircraft profiles** | Airframe drives map icon, separation, LZ capacity, planning defaults | `feature/aircraft/` | `routes/aircraft_routes.py`, `aircraft_seed.py`, `amps_package.py` |
| **Mission packs** | A shared container of LZs, sketched route sets and point sets a team plans one operation in. Members edit it through a server-ordered stream of id-addressed operations, every change is logged, and the owner can finish it (read-only for everyone). Teams, email invites, and name search limited to teammates. Edits reach everyone through a live stream (a separate service), or by polling where it is not running. Design and rules: `docs/MISSION_PACKS.md`. On Android the engine runs with the app, takes invitation links and has the workspace switcher and the Pack panel (§17, *Mission packs on Android*, *The workspace switcher*, *The Pack panel*); the other panels in a pack and the rest of its screens are next | `feature/missionPacks/` (the sync client `useMissionPack`; `packApi` for every route; invitation links; the editors kept in step with an open pack, `usePackLz` / `usePackRoutes` / `usePackPoints` on `usePackItemSync`: `docs/MISSION_PACKS.md` §5a; the screens in `feature/missionPacks/ui/`: the workspace switcher, the Pack panel with Items and History, New pack, Members, Teams, Add to pack, the finished banner and other people's pointers, joined to the app by `usePackWorkspace`) | `routes/pack_routes.py`, `routes/team_routes.py`, `pack_ops.py`, `pack_support.py`, `realtime/service.py` |
| **Mission brief** *(B0 to B9 built; plan, the owner's decisions and what is next in `docs/MISSION_BRIEF.md`)* | The air movement and hasty multi-ship briefs from a pack or the session: a PDF and a live sandtable. So far the formats as data (`contracts/briefs/`, copied byte for byte into the web) and the pure core: `compileBrief` (everything a brief says, held to `contracts/fixtures/brief/compile.json`) and on-device light data (held to Skyfield, `light.json`). The server keeps briefs in the Library (`/api/briefs`, synced) and as pack items open to every member, and answers the weather at a planned time (`/api/weather?at=`). The web's Brief menu and three-step setup (`BriefSetup` over `useBrief`) for accounts with `mission_brief` (off for all but admins and ticked testers); a pack's session takes brief edits even when read-only, in batches of their own (`packSession.js`, `packs/session-brief.json`). The PDF: the client sends the compiled model to `POST /api/brief/pdf`, the server draws each figure (tiles stitched with Pillow, the plan as SVG from the model's `geometry`), lays the pages out as HTML (`brief/templates/`) and Gotenberg prints them; nothing is kept. **A brief never says a page number**: Chromium decides where a long step breaks, so the packet is said by section and the footer numbers the pages. The live sandtable (*Present live sandtable* in the Brief menu, *Present sandtable* at the review): the brief's steps walked on the live map, the planning frame put away (kept mounted) and nothing taking an edit, each step flown to and drawn from `geometry` by the PDF's own rules (`sandtableSteps.js`, held to `contracts/fixtures/brief/sandtable.json`; `SandtableLayer.jsx`), a step called *Step n of N*, never by a page. Strip charts in the PDF's appendix: each route cut by `compileBrief` into 5.5 × 21.5 cm strips (three a page, 1:100,000 or 1:250,000, topo unless asked), the 1 km MGRS grid added only when printing (`stripGrid.js`), and the server turning the map so the course is up (`figures.draw_strip`). On Android, tablets only (the rail's *Brief*): the setup's three steps, the review over the real PDF, Share and Save (`feature-brief` over core-data's `BriefSession`, §17 *The brief on a tablet*), and the live sandtable from the setup's *Present sandtable* (`SandtableFrame` over the tablet's map, core-brief's `SandtableSteps` held to `sandtable.json`, the fit to `sandtable-fit.json`; §17 *The sandtable on a tablet*) | `feature/brief/` | `routes/brief_routes.py`, `brief/` (`figures.py`, `pages.py`, `gotenberg.py`, `templates/`), `pack_routes._only_briefs`, `routes/weather_routes.py` |
| **3D LZ view** *(in progress; on Android too, `docs/ANDROID_3D_PLAN.md`)* | LiDAR point cloud over DEM terrain and imagery, in Cesium. The web streams the place's whole USGS survey from its octree on AWS, a piece at a time, converted and cached by the API on first ask (`lidar/stream.py`; first points in about 2 s, the LZ at full density in about 12 s cold, against a 137 s build), and opens on `resolve`'s `frame`, on the web and Android alike; where nothing streams, opening it on an unbuilt LZ builds one automatically and shows progress. Visible routes draw at their planned MSL with curtains and labels (`docs/3D_PLANNING_GRAPHICS_PLAN.md`). A compass turns and tilts with the camera (heading in degrees true; click to face north) | `feature/viewer3d/` | `routes/lidar_routes.py`, `lidar_builder.py`, `terrain_tiles.py`, `backend/lidar/` (incl. `worker.py`, and `ept.py`, `names.py`, `pieces.py`, `stream.py` for streaming), `tools/` |
| **LZ surveys** *(L1, L5, L2, L6 and L4 built; plan, the owner's answers and what is next in `docs/LZ_SURVEYS.md`)* | HLZ survey books, one page per LZ, web only. **Books are many**, each for a team or for no team (`survey/access.py`, §13 of the plan): site admins do everything; with `lz_surveys` (not launched: `DEFAULT_OFF`, ticked one by one or in bulk from the dashboard's users list), a team's owner and admins make and edit its books and their surveys, its members read their published pages, and everyone with the key reads a book for no team; a draft is its editors' alone. A survey is the field survey's values (the old book's sections, its photos), a target and boundary set on a map of its own, and **Measure** (`survey/measure.py`), shown before the survey takes it: the target's grid and lat/long, its elevation **in the DEM the slope is read in** (named, with its resolution and datum; never the SRTM30 point of `/api/analyze-field`), size and LZ axis from the boundary's smallest rotated rectangle, area by the web's own formula, the UH-60L fit as a note (never Max A/C), and the **95th percentile** inside the boundary of the nose-up, nose-down and cross slope at the field's best approach, judged against §13's limits with no wind, with the slope map, contours and profiles. Field against computed (`survey/compare.py`): where both exist the computed value is printed unless an editor overrides it with a reason. A measured draft is sent for review and **published** as REV n with a sign-off (the approver need not have an account) and a CHANGES line it suggests; the revision keeps all it was made from, and its **one-page PDF** (the owner's DONOVAN draft's layout: `survey/compile.py` the page as data, `figures.py`, `pages.py`, `templates/survey.html`) is printed once by Gotenberg and kept; the page is also served as HTML (`…/page`) for a preview where there is no Gotenberg. **What stands on and round the LZ is measured from the USGS LiDAR** (`survey/obstacles.py`, part of Measure): the survey the 3D view streams for the place, read as USGS publishes it (LAZ, kept beside the streamed pieces: `Streamer.laz`) over the LZ and 100 m round it and a lane 10 m each side of the best approach and exit from the touchdown point out to 1,000 m; **the shallowest angle from the touchdown point that clears everything in the lane with no margin** (what stands 5 m or more above its ground, 2 m for what is built, and the ground where it rises: brush, grass and a return's scatter set no angle), what sets it, **where that path is 100 ft above the ground** (the approach's descent from 100 ft AGL begins there: the owner's, 2026-10-27, after the first angle, from the LZ's edge across 60 m, read 87-90° on every LZ of the old book) and what stands more than 100 ft above the track's ground past it, flagged in the page's figure and notes (in the book's north Georgia timber, on every LZ); the tallest in the LZ and within 100 m; what stands over 2 m in it and its trees; a picture of every height round it for the editor's map; a treetop line on the profile. A lone return (a bird) is not something standing, a wire's is; a survey from before about 2015 gives no numbers, only why. A reading is bounded (6 million returns, 60 s, its first failure). The page prints it in place of the field's survey profile, which an editor may choose instead, and **an editor may type the approach's angle over the LiDAR's with a reason**, printed as theirs with the LiDAR's beside it (`compare`'s `approach`, the `typed` choice; `docs/LZ_SURVEYS.md` §16). A survey measured before its target, boundary, best approach or best exit changed is measured again before review or publishing (`measure.stale`). **The old book is imported** (`survey/importing.py`): `tools/import_survey_book.py` reads its PDF with poppler's `pdftohtml -xml` into a bundle on the owner's machine, *Import the old book* makes a draft per page with its photos, the target at the book's grid (a lat/long more than 50 m from it is flagged), and each draft is then measured on the server in the background, one at a time (SAM's boundary at the grid, then the snapshot), its state and the page's notes on its import record. **The whole book prints as one PDF** (`survey/book.py`, `templates/book.html`): its cover with a map of every LZ, its index, then every published page numbered in the book, printed in the background on request (anyone who reads the book may) and kept, by a fingerprint of what it holds, until a page in it changes. Archived, never deleted. The 3D capture is L3 | `feature/surveys/` (`useSurveys`; `SurveyBook` the window, `BooksPage`, `SurveyBookPage`, `SurveyEditor`, `SurveyReader`, `Publishing`, `PagePreview`, `SurveyImport`, `SurveyMap`, `Measurements`, `Obstacles`, `Disagreements`, `SurveyPhotos`) | `routes/survey_routes.py`, `survey/` (`access.py`, `assets.py`, `measure.py`, `compare.py`, `geometry.py`, `fields.py`, `compile.py`, `figures.py`, `pages.py`, `profile.py`, `changes.py`, `importing.py`, `book.py`, `obstacles.py`, `templates/`), `lidar/stream.py`'s `laz`, `tools/import_survey_book.py` |

Entitlement keys (`entitlements.FEATURES`): `lz_pz_tools`, `routes`,
`msnx_import`, `threats`, `cloud_save`, `exports`, `aircraft_profiles`,
`mission_packs`, `mission_brief`, `lz_surveys`. A missing key means **enabled**, so new features default on,
except a feature in `entitlements.DEFAULT_OFF` (not launched yet: today
`mission_packs`, which ships with the menu redesign, `mission_brief`, being built:
`docs/MISSION_BRIEF.md`, and `lz_surveys`, the survey books' readers' key: `docs/LZ_SURVEYS.md` §7). Those are off for everyone
but admins and the testers an admin ticks them for, and only a tick is ever stored,
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
│  ├─ survey/                LZ surveys: the asset store, the measurement (snapshot) and its LiDAR obstacles, field against computed
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
├─ android/                  Gradle project for the Android app (§17): pure-Kotlin core modules, Android libraries, the app
├─ contracts/                Golden fixtures the web, backend and native apps are all tested against (§17); briefs/: the mission brief's formats as data
├─ tools/                    Operator CLIs for LiDAR (find_lidar.py, build_lz.py); import_survey_book.py (the old HLZ survey book into a bundle)
├─ docs/                     USER_GUIDE.md; plans: INVITE_ONLY_LOGIN_PLAN.md, 3D_PLANNING_GRAPHICS_PLAN.md, NATIVE_APPS_PLAN.md, MISSION_PACKS.md, ANDROID_MISSION_PACKS_PLAN.md, DATA_ADMIN_AND_PREBUILT_3D_PLAN.md; HANDOFF.md (where the Android build stands, what is next); native-design/ (the menu redesign for Android and iOS, phone and tablet: screenshots, outlines, sources, tokens; read its README before native UI work)
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
endpoint. **Routes the apps call are additive-only within an API major** — a removed or retyped field
breaks an installed app nobody can update — and are described in
`contracts/openapi.yaml`, which `backend/tests/test_openapi_contract.py` holds the
responses to. A route not in that file is not yet something an app may rely on.

**The API version** is that file's `info.version`, apart from the product's. Its major is the promise to
installed apps; `contracts/scripts/api_version.py check` (CI, `api.yaml`) compares the spec with the branch
it merges into, classifies every difference (a route, field or status gone, a type changed, a value that may
now be null, a new required input, an enum widened in a response: breaking; a new route or optional field:
additive), and fails on a break whose major did not move. `bump` writes the version a diff calls for. It
never bumps by itself: a new major tells every app that does not support it to update, which is the owner's
call, and the better fix is usually to keep the old shape. `backend/api_version.py` must agree with the spec
and lists the majors the server still serves (`API_MAJORS_SERVED`: keep an old one, with its routes, while
apps on it are in the field); `/api/config` sends `apiVersion` and `apiMajors`. **Each client build lists
the majors it supports** (`ezpz.apiMajors` in `android/gradle.properties`, `frontend/src/apiSupport.json`),
and the check fails while a client supports none the server serves. At run time Android treats a server past
its majors as *Update required* and one behind them as a banner, sending nothing either way (`ApiSupport`,
`holdsBack`, beside the minimum version); the web offers a reload (`feature/apiCompat`). A server from before
the fields serves API 1.

| Area | Routes |
|---|---|
| auth | `POST /api/auth/{login,register,google,verify-email,resend-verification,forgot-password,reset-password,mil/request,mil/verify}`, `GET /api/auth/me` |
| auth, native apps | `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/sessions`, `DELETE /api/auth/sessions/<id>`, `DELETE /api/auth/me` (account deletion) — design in `AUTHENTICATION.md` |
| terrain | `POST /api/analyze-field` (SAM), `POST /api/terrain-analysis` (slope), `POST /api/elevations` (planner/AMPS ground, Terrarium), `GET /api/terrain/heightmap/<level>/<x>/<y>`, `POST /api/terrain/heights` (3D ground + geoid, local DEMs) |
| location | `POST /api/convert-grid` (MGRS → lat/lon), `POST /api/convert-to-mgrs` (no longer called by the SPA, which converts lat/lon → MGRS itself with `utils/mgrs.js`) |
| weather | `GET /api/weather` (optional `at`, a planned time: the TAF period covering it, `kind: "forecast"`, or the METAR, `kind: "observation"`; without `at` unchanged), `POST /api/route-winds` |
| export | `POST /api/generate-excel`, `POST /api/export-package` |
| saved data | `/api/lz`, `/api/routes` (+ `/<id>/file`), `/api/pointsets`, `/api/briefs` (needs `mission_brief`) — CRUD, now with sync (below) |
| brief | `POST /api/brief/pdf` (needs `mission_brief`; a heavy request): the compiled brief printed by Gotenberg, 503 `pdf_unavailable` without `GOTENBERG_URL` |
| sync | `GET /api/sync/changes?since=&limit=` — everything of the caller's after a cursor, deletions included (`lz`, `route`, `pointset`, `aircraft`, `brief`) |
| aircraft | `/api/aircraft-profiles` CRUD, `/<id>/template`. A user's own profiles sync like saved records; the master list does not |
| threats | `POST /api/threat-mask`, `POST /api/threats-ths`, `GET/POST /api/threats-kmz`, `POST /api/threats-kmz-link` |
| route share | `POST /api/route-share`, public `GET /r/<token>`, `/r/<token>/route.<kind>` |
| lidar | `POST /api/lidar/resolve` (reports `canBuild`), `POST /api/lidar/build`, `GET /api/lidar/build/<key>` (polling keeps it alive), `DELETE /api/lidar/build/<key>` (stop waiting), `GET /api/lidar/tilesets[/<key>[/<path>]]` — coordinates only ever in POST bodies; progress is read by opaque key. The bare list is admins' only (403 `admin_required`), and a tileset's folder serves only tile files (`.json` but never `area.json`, `.pnts`, `.b3dm`, `.glb`, `.subtree`; nothing under a dot), anything else a JSON 404. `resolve` with `stream: true` answers a streamed survey (`streamed`, `frame`) where nothing is built and the server streams (`LIDAR_STREAM_CACHE_DIR`); its files (`tileset.json`, `<16 hex>.json`, `<16 hex>.pnts`) come from the same file route, 502 `upstream_unavailable` or 503 `geoid_unavailable` when they cannot be made. What the apps call of these and the two 3D terrain routes is in `contracts/openapi.yaml` (the 3D view's section), recorded with a real py3dtiles tileset (`contracts/fixtures/viewer3d/tileset`) |
| mission packs | `/api/packs` (+ `/<uuid>`, `/finish`, `/reopen`, `/duplicate`, `/ops`, `/events`, `/seen`, `/items…`, `/members…`, `/invites…`) — `docs/MISSION_PACKS.md` §4. A batch of `/ops` all on `brief` items is taken from viewers and in a finished pack |
| teams, invites, search | `/api/teams` (+ `/<id>`, `/members/<user_id>`, `/invites`), `/api/invites` (+ `/<id>/accept`, `/<id>/decline`, `/accept`), `GET /api/users/search?q=` (teammates only) |
| LZ surveys | `GET/POST /api/survey-books`, `GET/PATCH /api/survey-books/<uuid>`, `POST …/archive`, `…/restore`, `…/import` (multipart `bundle`, 100 MB; the drafts are measured in the background), `GET/POST …/print` (how the book's PDF stands; POST prints it in the background), `GET …/pdf` (the book's PDF, 409 `not_printed` until it is printed as the book stands); `POST /api/surveys` (into a book), `GET/PATCH /api/surveys/<uuid>`, `POST …/archive`, `…/restore`, `…/measure` (`{apply}`; a heavy request), `…/submit`, `…/draft`, `…/publish` (a sign-off; prints the PDF where it can), `GET …/pdf[?rev=]` (503 `pdf_unavailable` without Gotenberg), `GET …/page[?rev=]` (the page as HTML, no Gotenberg needed), `POST …/assets` (multipart, 20 MB), `GET/PATCH/DELETE …/assets/<id>`, `POST …/import/checked` — `lz_surveys` or a site admin (403 `feature_disabled`), then what a person may do in each book (`survey/access.py`; a book or survey they cannot read is a 404, an editor's action a 403 `not_editor`), every save with `If-Match` (428 without, 409 `revision_conflict` with the server's copy), `Cache-Control: private` throughout. Web only: not in `contracts/openapi.yaml`, so the API version does not move (`docs/LZ_SURVEYS.md` §8) |
| admin | `/admin/*` — session cookie, not JWT. `/admin/packs` and `/admin/teams` are read only and never show what a pack holds |
| app config | `GET /api/config` — **public**, cached 60 s: `minAppVersion` per platform, `maintenance`, which optional `services` are up, and the Mapbox public token (`app_config.py`, `routes/config_routes.py`) |
| health | `GET /` → JSON status (or redirect to `/admin/login` on the admin host) |

Regenerate this from the source of truth with `app.url_map` if it drifts.

---

## 7. Data model

`models.py`: `User`, `LocalCredential`, `AccountToken` (verification and reset
tokens, stored as SHA-256), `LoginEvent`, `AircraftProfile`, `SavedRoute`,
`SavedPointSet`, `SavedLZ`, `SavedBrief` (a mission brief's document, `docs/MISSION_BRIEF.md` §3), and the mission-pack tables `Team`, `TeamMember`,
`MissionPack`, `MissionPackMember`, `MissionPackInvite`, `MissionPackItem`,
`MissionPackEvent`, and the LZ survey tables `SurveyBook`, `LzSurvey`, `LzSurveyRevision`, `LzSurveyAsset`, `SurveyBookPrint` (a book's PDF, kept by the fingerprint of what it holds), `LzSurveyImport` (where an imported
survey came from, its notes and how its measuring stands, kept apart so that state is written without moving the survey's revision).

**LZ surveys are web only and never sync** (`docs/LZ_SURVEYS.md` §3). Books are many, each for a team or none (`survey_book.team_id`, with
**no foreign key** on purpose: `pack_support.delete_team` archives a deleted team's books, and a book whose team is gone is no one's but the
site admins', never everyone's). A survey belongs to one book and holds the field values as typed (text,
`survey/fields.py`, the web's `surveyFields.js` held with it to `contracts/fixtures/surveys/donovan.json`), the admin's overrides
(each stamped with who, when and the two values it was chosen on), an embedded schema-2 LZ/PZ diagram (its landing and takeoff
doghouses are the best approach and exit) and the last snapshot taken in. Nothing is deleted: a survey is `archived_at`, a photo
`removed_at` with its bytes kept, because a published revision (L5) may show it. A photo's bytes are in the asset store under
their SHA-256 (`survey/assets.py`: `LocalAssetStore` under `SURVEY_ASSETS_DIR`, re-encoded by Pillow with **no metadata**, a phone's
GPS position included), never in the database. A survey's `revision` is what If-Match names and also SQLAlchemy's version
counter (`version_id_generator=False`): every write is `WHERE revision = <the one read>`, so a request that measures or prints for
seconds cannot undo a save made meanwhile (StaleDataError, answered 409); set the next revision yourself (`_stamp`). A snapshot keeps
the target, boundary and best approach it was measured for, and one the survey has moved on from (`measure.stale`) is measured again
before review or publishing.

**Mission packs are stored apart from the library** (`docs/MISSION_PACKS.md`). Items
hold the library's JSON unchanged, but in their own table, so nothing about packs
touches the saved-record queries or the per-user sync feed. Three things to keep true:
every write to a pack takes its row first (`pack_support.lock`) and numbers its
events under it, so the log has one order; a finished pack refuses every edit
with 423, the owner's included, and a viewer's with 403, **except a batch whose every
operation is on a `brief` item**, which is open to every member in every state of the
pack (the owner, 2026-10-16; `pack_routes._only_briefs`; a batch with anything else in it
is refused whole, so clients send brief edits in batches of their own); and deleting an account calls
`pack_support.release_account` first (both deletion paths do), because
`mission_pack.owner_id` deliberately has no `ON DELETE`.

**Saved records sync across devices** (`sync_support.py`; rules in
`docs/NATIVE_APPS_PLAN.md`, "Sync and conflicts"). `SavedLZ`, `SavedRoute`,
`SavedPointSet` and `SavedBrief` — and a user's *own* `AircraftProfile`s, not the admin's master list —
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
`LoginEvent`, `AccountToken` and the four saved-record models against the live
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
| **USGS 3DEP LiDAR** | Point clouds: built from, and streamed by the API from its octree on AWS (`lidar/stream.py`; the browser never talks to AWS) | `backend/lidar/`, `tools/` | keyless (§12) |
| **USGS The National Map, NAIP** | Photographic colour for streamed pieces, fetched in the background (`USGSNAIPImagery` `exportImage`) | `backend/lidar/photo.py` | keyless; credit "USDA NAIP, from USGS The National Map" |
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
| Backend (staging) | Coolify VM, project EZPZ, environment `staging`, `dev-ezpz-api.mcfadd.in` via the same tunnel; its own Supabase project (free tier: pauses after 7 days unused, restore it from the dashboard) | Automatically on push to **`develop`**. Its own `JWT_SECRET_KEY`; shares the build service and `/data/{topo,tiles,cache}` with production. `CORS_ORIGINS` takes the Vercel previews by a regex (flask-cors treats an origin with `^`, `$`, `*` … as one); the variable is marked **literal** in Coolify and writes `[.]`, not `\.`, because otherwise Coolify mangles the `$` and the regex silently matches nothing. Vercel's *Preview* `REACT_APP_API_URL` points at it, so PR previews never touch production data |
| LiDAR build service | Coolify VM, beside the backend — **no public domain** | Automatically on push (separate Coolify app, base dir `/backend/lidar`) |
| Gotenberg (the brief's PDF) | Coolify VM, beside the backend — **no public domain** | Not yet deployed. A Coolify app from the published image `gotenberg/gotenberg:8.21.1` (tried with it), started as `gotenberg --chromium-disable-javascript=true --chromium-allow-list=^file:///tmp/.* --chromium-deny-list=^(?!file:///tmp/).* --api-timeout=140s` so a brief's text can make Chromium run nothing and fetch nothing, and a whole LZ survey book (§4) has the time it takes (the default ends a request at 30 s; the API waits 150 s); set `GOTENBERG_URL` (e.g. `http://<internal host>:3000`) on the API. About 1 GB of image; a 26-page brief prints in about half a second once its tiles are fetched. Without it the brief's Download PDF says it is not available |
| Live service (mission packs) | Coolify VM, beside the backend; **needs a public hostname** through the Cloudflare Tunnel (WebSockets) | Not yet deployed. A separate Coolify app, base dir `/backend/realtime`, port 8091; set `DATABASE_URL` and `REALTIME_API_URL` on it and `REALTIME_PUBLIC_URL` on the API. Without it clients poll |
| Admin | `admin.ezpztac.app` → the backend's `/admin` | with the backend |
| Database | Supabase Postgres, **Session-mode pooler** | — |

- **Which backend is live is decided by `REACT_APP_API_URL` in Vercel.**
- The container runs **one gunicorn worker with 8 threads** and a 180 s
  timeout. One worker because SAM holds about 1 GB resident and the rate
  limiter, QR store and route-share store are in-process, and so are the LZ survey
  import's measurer (`survey/importing.py`) and book printer (`survey/book.py`): a
  second worker would measure the same surveys, or print the same book, again after its
  `resume` — do not add workers
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
| H | Stream USGS's own octree instead of building (plan Part C, D20) | **Done for the web** — `lidar/stream.py`, `resolve`'s `stream` | Measured 2026-10-26 from the owner's Mac, cold cache, at the Ellijay test LZ: first points 1.4–2.9 s; every piece over the 1 km square at every depth (168, 52 MB) in 10–13 s; the whole opening view at error 2 on a 1600 × 900 view (370 pieces, 147 MB, the landscape included) in 18–21 s; warm, all of it off the disk at once. A build of the same LZ: 137 s. Not measured: the VM's link to AWS, and the tunnel to a viewer. |

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
| `LIDAR_KEY_SECRET` | **Required on every deployment, and the same on the API, staging's API and the build service** (they share `/data/tiles`). New tileset keys are an HMAC under it (`lidar/catalog.key_for`). Both refuse to start without one of 32 characters or more wherever `JWT_SECRET_KEY` is required, or `LIDAR_BUILDER_TOKEN` is set (`catalog.key_secret`); a bare local run uses a public development value. `openssl rand -hex 32`. |
| `LIDAR_CACHE_DIR` | Where the USGS coverage index is cached. |
| `LIDAR_STREAM_CACHE_DIR` | Streams 3D from USGS's octree (plan Part C) when set: where the survey indexes and converted pieces are cached, and the LZ surveys' obstacles read USGS's own pieces from (kept beside them, `laz/`; without it a survey says this server reads no LiDAR). A persistent, writable mount in production (e.g. `/data/cache/lidar`); safe to delete. Unset: no streaming, and the 3D view builds as before. The coverage index (8 MB) is downloaded into it on first use. |
| `LIDAR_STREAM_CACHE_MAX_GB` | The pieces' cap (default 200): past it the pieces used longest ago are let go. |
| `LIDAR_STREAM_PHOTO` | `off` turns off the background pass that recolours cached pieces from USGS's NAIP service (`lidar/photo.py`); on by default wherever streaming is. |
| `MIN_APP_VERSION_ANDROID`, `MIN_APP_VERSION_IOS` | Oldest supported native app version, `X.Y.Z`, served by `/api/config`; the app shows "Update required" below it. Unset = no minimum. A malformed value is ignored and logged, never served. |
| `MAINTENANCE_MESSAGE` | Non-empty turns maintenance on in `/api/config` and is the notice the apps show (500 chars max). |
| `REALTIME_PUBLIC_URL` | Where clients open the mission packs' live stream (`ws://` or `wss://`), handed out as every pack's `live_url`. Unset: clients poll. The service's own settings are at the top of `backend/realtime/service.py`. |
| `SURVEY_ASSETS_DIR` | Where the LZ surveys' photos, their pages' PDFs and their books' PDFs are kept, by SHA-256 (`survey/assets.py`). **A persistent mount, backed up off the VM** (a photo cannot be taken again; `docs/LZ_SURVEYS.md` §11 R6), and **one directory per deployment**: `/data/surveys/prod` and `/data/surveys/staging`, since their databases are separate. Unset: the system's temp directory, lost on redeploy, and production logs a warning at startup. |
| `GOTENBERG_URL` | The internal Gotenberg that prints a brief's PDF (§9). Unset: `POST /api/brief/pdf` answers 503 `pdf_unavailable`. Never public: the API is its only caller. |
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
Copy-Item .env.example .env                   # once; without it sign-in silently fails (below)
npm install
npm start                                     # copies Cesium into public/cesium first
```

`.claude/launch.json` defines the `frontend` preview server.

**What trips a fresh machine** (found setting up a Windows laptop with only Python 3.14, October 2026):
- **`frontend/.env` must exist.** `api.js` takes its base URL from `REACT_APP_API_URL` with no fallback and there is no dev proxy, so without it every call
  goes to the dev server itself and sign-in fails with no clear error. Copy `.env.example` (it points at `http://127.0.0.1:5000/api`) and restart `npm start`.
- **Open `http://localhost:3000`, not `127.0.0.1:3000`**: `CORS_ORIGINS` defaults to `http://localhost:3000`, and emailed links use `FRONTEND_URL`'s same default.
- **Python 3.14 works** (torch, ultralytics, rasterio and the rest have wheels). `psycopg2-binary` is pinned at 2.9.11 because 2.9.10 had no 3.14 wheel and its
  source build stops at `pg_config`, failing the whole `pip install -r requirements.txt`; 2.9.11 also has the image's 3.11 wheels, and
  `tests/test_realtime_live.py` passed with it against Postgres 18.
- **PowerShell may refuse `Activate.ps1`** (execution policy): call the venv's interpreter directly, `.\venv\Scripts\python.exe app.py`. Plain `python` is
  then the system one, which may have some of the packages and fail on the first missing import.
- `python app.py` runs Flask's reloader: two processes of about 1.1 GB each (SAM). The first start downloads `sam_b.pt` (358 MB) into `backend/`.

**Testing mission packs locally** (they are off for non-admins until launch, §4):
- `dev_user.py` takes `--password` for a non-interactive run (on Windows `getpass` reads the console, so a piped password hangs) and `--name` (give each test
  account its own: the default is the same for all). `--admin` gives every feature, `mission_packs` included, and pack roles still behave normally for an
  admin. To test as a non-admin, sign in at `http://127.0.0.1:5000/admin` as the admin, open the user, tick *Mission packs* under Feature access, and have
  them reload the app: features are read when the page loads. Accepting an invitation needs the feature too, so tick it before they open the link.
- Two people means **two browser profiles**, not two tabs: tabs in one profile share `localStorage` (the session token and the remembered open pack,
  `ezpz.openPack`). Keep both windows visible: polling (every 3 s) pauses while a tab is hidden.
- On SQLite there is no live service, so nobody shows as "here now" and edits arrive by polling. That is correct. `tests/test_realtime_live.py` runs
  the whole live chain against a local Postgres (its docstring has a `docker run` line; with Docker in Windows-containers mode, a throwaway cluster from
  an installed PostgreSQL's own `initdb` / `pg_ctl` on another port does the same).
- **Running `npm install` at the repo root installs husky's commit hook by setting `core.hooksPath` in the repository's git config**, which every worktree
  shares; a checkout without the root `node_modules` then fails every commit at the hook. To get commitlint in a worktree without that, use
  `npm ci --ignore-scripts` and run `npx --no -- commitlint --from <base> --to HEAD` yourself.

**An app signing in against this backend needs an account in *this* database.** The local SQLite file (`backend/ezpz.db`) holds none of the
production accounts, and the login route answers "Invalid email or password." for a wrong password, an unknown address and an unverified one alike
(on purpose). `python dev_user.py you@example.com [--admin]` makes a verified, `.mil`-cleared account there; it refuses anything but a local SQLite
database. The Android procedure is in `docs/HANDOFF.md` §4. Run it from `backend/` with the Python the backend runs under; **the owner's venv
differs between machines** (on one it is not at `backend/venv`, on another it is), so give them a command that does not assume a path, or ask.

### Tests

```powershell
cd backend;  python -m pytest tests -q
cd frontend; $env:CI="true"; npm test         # CI=true, or Jest waits in watch mode
cd frontend; npm run build                    # catches lint errors the tests miss
cd android;  .\gradlew.bat test               # Kotlin core modules, against ../contracts
python contracts/scripts/mgrs_fixtures.py check   # MGRS fixtures vs PyGeodesy (~20 s)
python contracts/scripts/light_fixtures.py check  # brief light data vs Skyfield (pip install skyfield==1.55; downloads DE421 once)
```

- `tests/test_threat_qr_export.py` **fails and predates current work**
  (SQLAlchemy app registration). Report it; don't "fix" it as a side effect.
- The geoid test skips without `PROJ_NETWORK=ON` — PROJ returns heights
  unchanged, and *reports success*, when grids are missing.
- **CI runs four test sets:** `android.yaml` (the Kotlin modules),
  `contracts.yaml` (the fixtures, on the web and PyGeodesy sides),
  `backend.yaml` (the backend's contract tests: a named list of pytest files that
  need no SAM weights, terrain data or GPU) and `api.yaml` (the API version, §6),
  each only when its paths change. The
  rest of backend pytest and of the web's Jest suite runs nowhere but on your machine.
  `backend.yaml` installs only the packages those files import, named in its Install
  step and pinned from `requirements.txt`: a new import in code they reach goes on that
  list too, or CI fails with `ModuleNotFoundError` while every local run passes (OpenCV
  once, contourpy for the slope contours since). `android.yaml` has a list of its own, for the Python the live test
  server (`backend/tests/live_server.py`) runs under, and code that server imports needs it there too (pyproj, for the
  3D view's LiDAR routes, was once on one list only). To check, run the list in a clean venv.
- **On a Windows checkout** (`core.autocrlf=true`), a text file compared byte for
  byte needs an `eol=lf` rule in `.gitattributes`, or the checkout's CRLF fails
  the comparison while CI passes. And Windows and Linux maths libraries can differ
  in a double's last digit, so a recorded number is compared with a tolerance
  (`test_network_fixtures.settle_floats`), never exactly.
- **So can Node on x86-64 and on an Apple-silicon Mac**: an arm64 build of V8 evaluates
  its Math routines (fdlibm) with fused multiply-adds, which round once where x86-64
  rounds twice (`Math.sin` differs at about 2% of arguments, `Math.exp` at 0.05%). The web's
  fixtures are written and checked on x86-64 (CI), so one compared exactly can fail on a Mac
  alone: `mgrs/grid.json` did, its searches turning a last digit into a millimetre. Its test
  settles numbers to a tolerance and labels as a set (`settled` in `mgrsGridFixtures.test.js`),
  and keeps the committed digits when regenerating, so a regenerated file changes only where
  the grid did.
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
  `refactor`, `docs`, `chore` and `test` release nothing — and a major from a
  `!` (`feat!: …`) or a `BREAKING CHANGE:` footer. That takes the
  `conventionalcommits` preset (`.releaserc.json`, a direct dev dependency): the
  default angular one ignores a `!`, which is why six `feat!:` commits in history
  never produced a 2.0. Checked against the installed commit-analyzer: `feat!:`,
  `fix(backend)!:` and a footer are major; `feat(android)!:` still releases
  nothing. A product major is the product's (the web's or the server's
  behaviour); it says nothing about the API, which has its own version (§6). It bumps
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
move, so never call the API for it. The browser has the inverse too (`fromUtm`,
`mgrsToLatLon`, and `toUtmInZone` for a point in a zone it is not in; ported from
the Android `MgrsConverter`), held to `contracts/fixtures/mgrs/inverse.json`
(`contracts/mgrsInverseFixtures.test.js`). The MGRS grid overlay computes every line
in its own zone with them, so a point on a line reads as that line's round value in
the cursor readout (checked to the metre against PyGeodesy on the live map).
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

**Slope landing limits** (the owner's, a UH-60 pilot, 2026-10-13). **6° nose down, 15° nose up, 15° cross slope**
("nose high" is the ground rising ahead, the nose up; every client and the server once had the first two the wrong way round).
Each is reduced by **2° for every full 5 knots** of the **steady** wind (gusts ignored), never below 0, and **no nose-down landing
above 15 kt**. The wind is the weather fetch's report for that LZ/PZ, the one its weather tiles show; with none the limits are not
reduced, and the slope tile says so in words. `/api/terrain-analysis` sends `byHeading`, the steepest nose-high, nose-low and cross
slope at every whole heading; the clients judge from it (`feature/terrain/slopeCall.js`, Android's `LzSummary.slopeCall`, both held
to `contracts/fixtures/planning/summary.json`), so turning the landing doghouse or a new weather report judges again with no request.
A slope is over its limit only when greater than it at 0.1°. The tile is LIMIT EXCEEDED at the landing heading (the flight data's
`landing_hdg`, read by its leading integer; an unreadable one is no heading, not 000), else CAUTION above 10°, else LANDING, and under it
in smaller text what was judged there (`Landing 270°: nose down 8.0°, limit 6°`, or `within limits`) and the wind the limits were cut for.
With no heading to judge (no landing heading, or a server that sends no `byHeading`) it calls the steepest slope alone, HEADING REQUIRED
at 15° or more, and says why. **No heading is recommended** (the owner, 2026-10-14: a rule cannot see the terrain round an LZ or what
crews know of it); the crew reads the ground from the slope map, its contour lines and the slope profile.

**Contour lines** (`contours` of `/api/terrain-analysis`, `terrain_provider._slope_contours`; drawn by the web's `feature/terrain/SlopeLayer.jsx` and
Android's `DiagramOverlay` and `ContourLabelsLayer`). Lines of equal height in feet over the LZ and a 40 m ring round it, from the slope's own
grid, lightly smoothed (2 m) so a DEM's noise draws no specks, at the finest of 1, 2, 5, 10, 20, 25, 50 or 100 ft that keeps the ground to
about a dozen lines; every fifth is major, specks a few metres round are left out, each piece is simplified (a real LZ is tens of KB), and
each height's label goes on its longest piece inside the LZ where it keeps 40 m from the others, majors first. They show and hide with the
slope map. They are drawn near-black over a light halo, the other way about from the MGRS grid, and the ring outside the LZ faded. A
server from before them sends none, and the slope map is the raster alone.

**The slope map's opacity** (the owner, 2026-10-21; the web's `feature/terrain/slopeOpacity.js` and `SlopeOpacityControl.jsx`, Android's `SlopeOpacity`
and `SlopeVisibility`, the same steps). A slider under the Slope Map switch while the slope map is drawn: 10 to 100% of the raster's own alpha (its
bands are 145 to 185 of 255) in steps of 5, from 55. It is the person's, how they like the slope map over the imagery: remembered per browser
(`ezpz.slopeOpacity`) and on Android per device, never saved with a plan or synced, so not a field of the diagram's `view` (both normalizers keep it to
three). It is the raster's alone; the contour lines keep theirs. A new value is set on the raster drawn (`attachSlope`'s `setOpacity`, Android's
`DiagramOverlay.setSlopeOpacity`), never a reason to draw the slope map again. The LZ card shows it as the screen does.

**The slope profile** (`profiles` of `/api/terrain-analysis`, `terrain_provider._slope_profiles`; the web's `feature/terrain/groundProfile.js`
and `SlopeProfile.jsx`, Android's `SlopeProfile` in core-planning and `SlopeProfileTile`; held to `contracts/fixtures/planning/profile.json`).
Under the slope tile, two small graphs of the ground: along the landing doghouse's heading (approach to departure) and across it (left to
right), each with the LZ shaded and a sentence of how much the ground rises or falls through it. The server measures a straight line
through the LZ's area centroid every 2° of heading (a line serves its opposite heading read backwards), 300 m past the LZ's farthest corner
each way, heights read between cells (null where there is none, never made up) and where each crosses the boundary; the clients pick the
line nearest the heading (halves up), so turning the doghouse redraws with no request. It is loaded wider than the slope's grid, and a
local DEM must cover it whole (else, in `auto`, Terrarium answers it; with `TERRAIN_SOURCE=local`, there is no profile). About 70 KB an
analysis. The height axis spans at least 30 ft, so gentle ground is not drawn as steep, and its top and bottom heights sit in a gutter
beside the plot. **A helicopter says which way the aircraft comes and faces** (the owner's sketch, 2026-10-14): a UH-60 outline in side view
on the approach end of the line along the heading, nose to the LZ, and from behind over the middle of the LZ on the line across it, each
just clear of the highest ground under it in room kept above the plot. The outlines are the web's (`HELI_SIDE`, `HELI_REAR`), drawn on
Android from the same path strings (`HeliOutlines`, held to the web's file by `SlopeProfileHeliTest`). **Not on the map**: it is a picture
beside the tile.
It opens and closes (open until closed) from a button drawn as each app's dropdowns are, not as a tile (the owner, 2026-10-14: a tile's
label did not read as something to press): on the web the top bar's dropdown buttons (bordered, the redesign's `chevronDown`/`chevronUp`),
on Android `Choice`'s outlined button with the expand chevron at its end. Remembered in the browser on the web (`ezpz.slopeProfile`), and
while the app runs on Android (`rememberSaveable`); how the person likes the card, never saved with a plan.**Headings are true** (METAR winds are true; the doghouse box is turned from true north); see §15.

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
- Tilesets are addressed by an opaque key so URLs don't reveal locations,
  but **found by coverage**: each has an `area.json` manifest (never served)
  and `resolve` returns the nearest one whose area contains the target. The key
  is an HMAC of the rounded target under `LIDAR_KEY_SECRET`: an unkeyed hash of
  it could be hashed back into its place by trying every rounded place in a
  region. Tilesets from before the HMAC keep their old names (the owner's call,
  2026-10-26: no rename); since lookups go by coverage, both kinds are found.
- Pipeline: PDAL crops, classifies, colours from Mapbox imagery, reprojects to
  ECEF → py3dtiles writes `.pnts`. It runs in the `backend/lidar` Docker image
  (`docker build -t avtac-lidar:dev backend/lidar`), whose default command is
  the build service. `lidar/build.py` is the single build path; the service
  (`lidar/worker.py`), the CLI (`python -m lidar`) and `tools/build_lz.py` all
  go through it.
- **Streamed instead, where the server streams** (`lidar/stream.py`, plan Part C): `resolve` with `stream: true`
  answers the place's survey (`coverage`'s choice) as one tileset written from USGS's own Entwine index
  (`lidar/ept.py`: four levels a file, the next joined as external tilesets, regions from each cube with
  the geoid added, error the cube's width over its span, `ADD`), and each piece a viewer asks for is
  fetched from AWS, converted (`lidar/pieces.py`: noise dropped, the build's class rules by survey year,
  coloured as the place looks, the geoid read at its centre, `POSITION_QUANTIZED`)
  and cached. Names in URLs are opaque (`lidar/names.py`): a survey's key is an HMAC of its name, a
  piece's name its octree address through a keyed 64-bit permutation, so the server reads it back without
  keeping a table. A built tileset still wins (photographic colour). Only a client that frames on
  `frame` may ask: the tileset is a state, and its own sphere opens on the state. Every AWS read is
  tried three times over one pooled session (Cesium never asks again for a tile that failed); without
  the geoid grids nothing is served (503), as builds refuse.
- **A streamed point is coloured as the place looks** (`pieces.NATURAL_STOPS`, the owner's request of 2026-10-26): the ground
  tan and brown, what stands on it greens that darken with its height above the piece's nearest ground return (a piece with no
  ground: what its class says of its height), shaded by its LiDAR intensity within the survey's range, which is taken once from
  the survey's root piece (a sample across the whole survey) and kept (`stats.json` beside it), so pieces side by side are shaded
  alike. The build's bands came out orange over nearly every crown, since north Georgia's hardwoods stand over their 15 m. USGS's
  copies carry no colour (GA_Statewide_B3_2018's schema has intensity and classification, no RGB). **A new look is a new survey
  key** (`pieces.LOOK`, part of `names.survey_key`): browsers and the app keep a piece a week under its URL, so the old colours
  would otherwise stay. Measured with it, cold at Ellijay: first points 1.3–3.0 s, the LZ's square 11–14 s, the whole view 18 s;
  a piece converts in 25–33 ms, as before.
- **Then each cached piece is made photographic in the background** (`lidar/photo.py`, the owner's choice of 2026-10-26: USGS's
  free NAIP image service): its own queue and two worker threads, apart from gunicorn's 8, fetch NAIP over the piece's box
  (`USGSNAIPImagery` `exportImage`, 0.6 m, or half the piece's spacing for a coarse one) and rewrite only its colour bytes, then
  mark it (`<name>.photo`). A view never waits for it; nothing is fetched while an analysis runs; a failed fetch keeps the palette
  and is offered again when next served, after 10 minutes. A palette piece is served `max-age=3600`, a photographic one the week,
  so a browser or the app picks the photograph up on a later view. Credited in the web's view ("Point colour: USDA NAIP, from USGS
  The National Map"). Measured at Ellijay: the first view as fast as without it; the LZ's 168 pieces photographic in about 8.6
  minutes (median 3.3 s an image, 40–60 ms of CPU a piece). NAIP's licence for imagery after 2019 is unconfirmed: confirm with USDA's
  APFO before selling photographic colour (the plan, *Photographic colour, in the background*).
- **Builds happen on first view, or in the background when an LZ is saved**, where nothing streams. The
  web asks `resolve` before a build on save and builds nothing where the place streams.
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
- **A Compose list of fields is keyed by what each row holds** (`key(...)`, as React keys them). Unkeyed, a row's slot is reused for whatever comes to its place, and a
  text field's typed words, its focus and its pending commit go with the slot: tapping a paragraph row does not take the focus from a field, so words typed in the brief's
  *Minimum weather* landed in *Commander's intent* when another paragraph was shown before the pause (B8's review; `BriefSetupTest` changes the paragraph under a field).
- **Clean up on `Throwable` when a platform call may throw an `Error`.** Robolectric's `PdfRenderer` throws `NoSuchMethodError`, which a `catch (e: Exception)` around
  the open let past and left the brief's PDF in the cache; on a device an out-of-memory error would do the same (`RendererBriefPdfsTest`).
- **Two files whose names differ only in case are one file on macOS and Windows.** `feature/brief/Sandtable.jsx` and a `sandtable.js`
  beside it made `import … from "./Sandtable"` resolve to the wrong one on the owner's Mac (Jest: "useSandtable is not a function"), while
  a Linux CI would have resolved both. Give a component and its pure module different names (`sandtableSteps.js`).
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
  first call made from a screen (the threat mask) failed with `NetworkOnMainThreadException` while every JVM test passed. The answer is now read whole on OkHttp's own threads, inside the
  call (`exchange` in `ApiClient.kt`), and `ApiClientThreadTest` fails without that (it records the thread that reads the body). Calls from a worker or a service scope never showed it.
- **A cancelled call must stop reading, and a mock server must not outsleep its own shutdown.** A blocked read does not look at the coroutine, so a heavy call cancelled once its headers
  had come read on until the whole answer was in (or a read timed out, 190 s), holding the priority gate; reading inside the cancellable call fixed it, and `ApiClientCallsTest` cancels at
  both moments. What that read throws must be handed to the waiting coroutine: OkHttp only logs an IOException thrown from `onResponse`, so the call would wait for ever.
  `ApiClientCallsTest` cuts an answer off part way to hold that, and every test that reaches a failed read has a timeout, so a regression fails instead of hanging the run.
  The test that should have shown it was flaky instead: MockWebServer's `setBodyDelay` is a `Thread.sleep` that closing the socket does not end, and `shutdown` gives its
  threads 5 s, so a 5 s delay cancelled just after the request arrived failed now and then with "Gave up waiting for queue to shut down". Hold a request with `SocketPolicy.NO_RESPONSE`
  (or a latch released before the server closes) and trickle an answer with `throttleBody`, which stops when the socket closes.
- **Android's XML parser throws where the JVM's does not.** `DocumentBuilderFactory.setXIncludeAware(false)` is `UnsupportedOperationException` on Android, so the mission reader refused *every*
  mission on a device while every JVM test passed; nothing had run on a device until a real file was tried. Any parser setting is best effort (`try`), the text check is what holds, and a
  feature that reads a file needs one trial on a device, not only under Robolectric.
- **A `GestureDetector` built from a `SimpleOnGestureListener` tracks double taps, and while it does, the second touch's long press is never cancelled however far the finger
  moves.** MapLibre's quick zoom is that gesture (tap, then touch and drag up or down), so a slow zoom fired the long press part way through: it picked up whatever was under the
  finger, and from S8 opened the map's menu and cut the zoom off. `LongPressTouches` sets no double-tap listener (`setOnDoubleTapListener(null)`), and a test drives the gesture through
  the real detector under Robolectric. A detector that only wants long presses should do the same.
- **One movable content moved inside another, at the same time, breaks Compose's runtime** (`ArrayIndexOutOfBoundsException` in `collectNodesFrom`). The phone's panels were
  in the sheet the map screen drew, so moving the map and the panels between the phone's and the tablet's frames together crashed; a test that swaps the frames found it
  (`TabletFrameTest`), and the phone frame now draws its sheet itself. Keep movable contents side by side, never one inside another while both move.
- **A coroutine whose call failed just before it was cancelled gets the failure, not a `CancellationException`.** kotlinx.coroutines looks for cancellation, as it
  resumes a coroutine, only when the result is not an exception (`DispatchedTask.run`: the exception "dominates cancellation"), so a call that fails as its screen
  goes away runs the `catch` as if nobody had left: the 3D view's lookup showed a failure, and resubmitted a build, for a view already closed. A `catch` that does
  anything asks `currentCoroutineContext().ensureActive()` first (`LidarBuilds`' `stillWanted`), and a test fails a call held until its scope is cancelled
  (`LidarBuildsTest`).
- **A small file can overflow a parser that recurses.** kotlinx.serialization's JSON parser recurses into arrays, so five thousand `[` (ten kilobytes) end in a
  `StackOverflowError` on a thread's 1 MB stack: an `Error`, which `catch (e: Exception)` does not see, so the app ends. The 3D view's readers look at the nesting
  first (`JsonNesting`, in core-scene3d) and refuse what is deeper than they read. Any other JSON a person or a server other than ours could make deep (a mission
  pack's items, which any member writes) has the same exposure until it does the same.
- **A live region made with its words is never heard.** Compose tells a screen reader of a change only to a semantics node that was there before it (a node
  that is new is a change to its parent's structure, and its parent is no live region), so a `when` that gives each state its words in a live region of its own
  announces none of them: the 3D page's states, and its notice of no terrain, were silent until one node that stays had its words changed (`PackAttentionLine`
  does the same). Test that the node's id survives the change, not only that the property is set.
- **A lifecycle call's name says less than its body.** MapLibre 13.6.1's view stops drawing in `onStop` (`SurfaceViewMapRenderer.onStop` pauses its surface), and
  its `onPause` does nothing to it (the base renderer's empty one), so a map "paused" under the 3D view drew on; a state machine tested alone
  (`MapRenderingTest`) could not tell. Read the library's bytecode (`javap -c`) before trusting which call does what.
- **Android's GL binding checks what its C call would not, and Robolectric's GL checks nothing.** The JNI of `GLES20` and `GLES30` refuses, before
  it calls the driver, a buffer holding less than the size asked (`glBufferData`: `IllegalArgumentException: remaining() < size < needed`) and an
  array shorter than its offset and count, and `GLUtils.texImage2D` a recycled bitmap or one it has no format for; Robolectric runs every GL call as
  a no-op. So every JVM test passed while the 3D view failed on both of the owner's devices the moment its point cloud came (2026-10-21): an LZ/PZ
  with no routes had empty route buffers sized by their capacity (4 bytes, nothing in them), and the routes' fault was taken as the scene's. Every
  buffer now goes up through one call sized by what it holds (`BufferData.upload`), a route fault costs the routes and a refused bitmap its tile,
  and `SceneRendererTest` runs the renderer's whole sequence of calls against shadows that check what the binding checks (`GlBinding`, read from
  the instrumented `android-all` jar with `javap`). A new GL call wants its binding's check in those shadows.
- **Auth-gated responses are `Cache-Control: private`.** Flask's `max_age` alone
  emits `public`, which lets Cloudflare cache one user's response for another.
- **A child's z-index counts only inside its parent's stacking context.** The phone's Capture and Export were `position: fixed; z-index: 9999`
  inside the map's tool rail (`z-index: 1000`), so the bottom sheet (1050) covered them at every height and nobody could export an LZ card
  from a phone. What floats on the phone's map goes in `.shell-map`, positioned from its edges (as the Layers button is): the map ends where
  the sheet begins, so it is never under the sheet. `MobileQuickAccess.test.jsx` holds the pills out of the rail.
- **A queued test dispatcher hides what the main thread does.** A view model's coroutines run on `Dispatchers.Main.immediate`, which runs
  work resumed on the main thread at once, inside whatever resumed it; `StandardTestDispatcher` as Main runs each in turn. The shell's pack
  gate passed every test so, and an independent review then proved four ordering bugs with `UnconfinedTestDispatcher` as Main and writes that
  suspend (as Room's do): packs stopped before a sign-out had written the documents, a new shell stopped them, ON_STOP's writes were cancelled
  with the view model, and a quick return left them in the back. Test two collectors of one state, or work launched from a lifecycle event,
  that way (`AppViewModelTest`), and remember a view model is cleared right after ON_STOP when the activity is finishing.
- **A layout that stops being laid out does not take back what it told by being placed.** `onGloballyPositioned` is called when a node is placed, not when a modifier
  in front of it stops measuring it (`layout(0, 0)`, as a hidden frame's row does), so the box the MGRS grid kept off stayed where the row had been. Take it away where it
  goes (`chromeBox(shown)`), and test what is told after a hide, not only before.
- **A scroll inside Material's `DropdownMenu` throws.** The menu lays its content out in a column it scrolls itself, measured with no height, so a `verticalScroll` inside
  it fails on its first measure ("measured with an infinity maximum height"): the tablet's workspace switcher crashed as it opened while the phone's sheet worked. Let the
  host scroll shared content, and try it inside the menu (`SwitcherTest`).
- **Android lint refuses a byte order mark inside a source file** (`ByteOrderMark`, an error): a character literal meant as U+FEFF must be written `'\uFEFF'`,
  never the character itself, which some editors put in place of the escape. Only the Android modules are linted, so the pure ones hold such characters
  without complaint; leave those as they are.
- **A pack's state changes many times a second.** The engine publishes a new `PackState` for every presence frame (people pointing, up to ten a second each), so a collector of
  `PackEngine.open` that does work for each one (a network call, a rebuild of a long list) does it at that rate: the Pack panel's history asked again for a failed page each time. Map
  the state to what the collector uses and `distinctUntilChanged` before doing anything. This person's own pointer comes back too (the live service sends everyone's
  presence to everyone, the sender included), so even someone alone in a pack makes it change while they drag: the editors' join looked, and serialised the open LZ/PZ, ten
  times a second until it narrowed (`sameForALook`).
- **Dialog windows stack in the order they are made, not the order they are drawn.** Compose shows a `Dialog`'s window when it enters the composition, so one
  made earlier stays under one made later: the copy dialog, asked from the Library, lay under the Library's own window once that window was made again (a font
  change recreating the activity, a foldable opened into the tablet's frame). Draw a dialog that may be asked from another window after it, and make it again
  with that window (`key(...)`, as `MapHome` does for `AddToPackHost`).
- **Material's `Surface` takes the touches on it, even with no `onClick`** (it ends in an empty `pointerInput`, so nothing under it is hit). The feet between two
  aircraft, written over the map on one, swallowed a tap meant for what was under them, though their layer said it took no touches; a label over the map is drawn on a
  `Box` with a background, as the contours' pills always were (`GraphicLabelsTouchTest`, which a pill with any pointer input fails).
- **A test cannot hide a measured-but-unplaced copy.** `clearAndSetSemantics` on a subcomposition measured off screen still left its text for `onNodeWithText` to find;
  measure words as text (`rememberTextMeasurer`) instead of composing them twice (`ChipsThatFit`). And Robolectric reads a method's `@Config(fontScale = 1.0f)` as unset,
  so a class's 1.3 stands: put the larger scales on the methods.
- **A reader stricter than what is written turns an edit into a copy.** A mission pack sends a key that went as null, and the typed reader of a route refused a null in a
  map, so the route became "one this version cannot read", the editor's own copy of it looked new, and the whole-list fallback wrote the route a second time; the two
  copies then drifted, each device's moves landing on a different one (the owner's first two-device run, 2026-10-13; §17, *Mission packs in the editors*). Every test
  passed because each device was tried alone, or a move was checked in what was sent rather than in what the other device then showed. Read the shared data as loosely as
  the web reads it, never let a fallback add what is already there, and check a change between two devices end to end (`RoutePackTwoDevicesTest`, the route scenario).
- **MapLibre reads an image's size from its bitmap's density.** `Style.addImage` takes the image's pixel ratio from `Bitmap.getDensity() / 160` (`Style.toImage`,
  MapLibre 13.6.1), and `Bitmap.createBitmap` gives a bitmap the screen's density, so the aircraft pictures, 192 pixels meant as 192 points, counted as 192 over the
  screen's density in points (about 55 on a 560-dpi phone): every aircraft, sized to the metre by its `k0`, was drawn density-times too small on every device. No JVM
  test could see it, since Robolectric's default screen is one pixel to the point. Give a bitmap whose size in points matters the density that says so (`IconRaster`,
  `AircraftIconRenderer.forMap`), and test it under a dense qualifier (`AircraftMapImageTest` runs as `xxhdpi`, where the old bitmap fails).
- **What waits for a view must not be undone by the view's own start.** `MapHost` queued what was asked before MapLibre had made the map, then on `attach` ran the
  queue and *then* jumped to the first camera, so a flight asked meanwhile (the sandtable's to its step once the activity was made again, an LZ/PZ opened at
  launch) was undone, and the sandtable had noted the step as flown. Its review found it by reading the order; nothing on the JVM could. The first camera goes
  first now, and a step is noted as flown only once its flight is asked for.
- **A colour made translucent is no theme role.** `contentColorFor` matches the scheme's roles exactly, so a `Surface` whose colour is a role with its alpha
  changed (the sandtable's card and constants box, `surfaceContainer` at 96%) finds no content colour, and its text takes whatever is round it: near-black on
  the card in the dark and night palettes until a picture of the night palette showed it. Give such a surface its `contentColor`.
- **The pack applies a patch field by field, so a value that is one to a person must go whole.** Each field of a patch stands alone, the later on each: a place sent as the
  one coordinate a nudge changed, an altitude as its number alone, an analysis as the fields that happened to differ, met someone else's change to the other part and stood as
  half of each, where nobody put it; and two people giving one point its first values at once lost one, because a new entry goes as one object. Tests that made one change on
  one device never showed it; the same setting, and two settings of one thing, made on two devices at once did (`LzPackSettingsTest`, `RoutePackSettingsTest`, 2026-10-13).
  Say for each setting what is one value (`PackKind.keepWhole`), and try both on two devices.
- **A form that keeps what it opened with puts back what changed under it.** The route's clock form and the unit builder took their values when they opened and applied them
  all on *Set* or *Apply*, so someone else's change in a pack, or an undo, made while one was open was undone without a word. A field follows what changes under it unless
  what is typed already says the same, and only what the person changed is applied (`PointDraft.check(before, …)`, `UnitDraft.patchOver`).
- **What follows a list must follow what it holds, not which list it is.** The web's flight data followed the doghouses through an effect keyed on the list's instance, and
  applying someone else's change to a diagram (`APPLY_REMOTE_DIAGRAM`, through `normalizeLzDiagram`) makes every list anew, so the effect put the doghouses' headings back over the
  flight data that change brought and the pack took it as this person's own: a landing heading typed on another device was undone, and, with the live stream, a doghouse turned
  elsewhere came back as two changes in the receiver's name, the first putting the old heading back; in a pack that takes nothing (finished, a viewer), an LZ/PZ without the
  headings its doghouses give went round for ever, the headings followed and put back in turn ("Maximum update depth exceeded"). `useDoghouses` follows the doghouses' content
  and the diagram on the map now (`diagramId`); `usePackLz.test.js` hears a turn an event at a time. And a pack logs operations while people read changes: one change of several operations (a doghouse
  turned is two) showed as that many identical lines of the history until the history read a run of them as one (`PackHistory`, §17, *The Pack panel*).

---

## 15. Known issues and debt

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
  columns are NOT NULL).
- Mission-pack web behaviour the fixtures pin and the web should fix first (it changes behaviour), then
  regenerate. The first two are bugs, their cases marked `webBug: true` in `contracts/fixtures/packs/` so a
  native port can find them and not copy them. **A sentence or name cut through an emoji**: the history's sentences are cut to 300 and
  "NAME (my edits)" to 100 UTF-16 units (`.slice`), which can leave a lone surrogate; the server cannot
  store one (UTF-8 encoding fails, so `POST …/ops` answers 500, and a batch that met a 5xx is sent again
  unchanged, so every later edit to the pack waits behind it), and Kotlin's encoder would send `?` in its
  place. Cut so that no character is split (and consider dropping unpaired surrogates in
  `pack_support.clean_summary` and the name check). **`packDiff.js` reads fields through the prototype**:
  a field named `constructor`, `toString`, `valueOf` and the like that one version lacks makes `diffData`
  throw and `sameData` call null and missing different, and a changed `__proto__` key is dropped from its
  patch; read only own fields (`has(obj, key)`) and keep `__proto__` as data. **A 422 batch is dropped**:
  `packSession.batchFailed` drops a batch refused with 422 (the JWT library's answer to a token it cannot
  read) as `http_422`, where a 401 waits for the person to sign in again; decide whether 422 should wait
  too (`packs/session.json` pins today's behaviour).
- Mission-pack web behaviour the Android port found and does not copy (`docs/ANDROID_MISSION_PACKS_PLAN.md`, the W1 amendments and *Deferred* 11),
  each a candidate to change on the web first, through the fixtures: a reload while an edit is sent or acked (a reload into a pack finished
  since drops an edit the pack has, as never sent: `packs/session.json`'s second known exception); a 403 or 404 during catch-up shuts the pack
  down and leaves its pending edits unkept (`packClient.js`, the catch-up's catch); the live stream's 4403 is taken as the pack gone
  (`packClient.js`, `onClose`), but the server sends it only when Mission Packs are off for the account or it has not cleared the `.mil` gate,
  so a feature turned off mid-session should pause rather than drop; `normalizeLzDiagram` drops fields a newer version wrote, so the first change
  sends null for them; a route set's own fields and version are nulled and made 1 by the reshape; an id-less (or number, text, true, list) route
  makes `usePackRoutes` send the set again without end once anything changes it; and a null route, or `routes` that is an object, makes the set
  fail to open (`restoreSketchRoute` reads `route.id` of null; `routes.map` of an object).
- A route's ground elevations are kept by point id with nothing saying where they were fetched (`elevations: {pointId: ft}`), so a point moved on one device while another
  device's fetch for it was out, whose move reaches that device only after the answer, can still end with the old place's ground in the pack: the device that fetched
  could not know (Android keeps fetched data only for a point still where it was asked, *Winds and ground elevations*, §17; the web keeps any answer). Closing it needs the
  place a ground was fetched for kept with it, a change to the shared shape (owner to decide, web first).
- The web reads the server's saved-record times (`updated_at`, `isoformat()` of a naive `utcnow()`, with no offset) as the browser's local time, so the
  Library's *Updated* is off by the person's offset from UTC. Android reads them as UTC (*The Library*, §17); fix on the web by sending an offset from the server or reading
  as UTC.
- Accepting an invitation link is not repeatable by the account that accepted it: a second accept answers 410 `invite_gone`. So an answer lost
  on the way (a read timeout after the server took it) is tried again and reads "already used" to someone who has just joined, on the web and
  Android alike. The server could answer a repeat by the same account with what it joined (owner to decide).
- **The web sends only the part of a plan value that changed, and a point's first values as one object** (`packDiff.js`): an altitude's number typed on the web
  while its reference is changed on another device can still end as the one's number and the other's reference, and a point given an altitude on the web and a wind elsewhere at once
  can lose one. Android sends each whole, and each first value apart (`RoutePackKind.keepWhole`, §17), which holds only while the other device is Android too. The same goes for a
  PZ marker, whose tip the web sends alone when it is dragged. The web would need the same in its `usePackItemSync` flush, with a fixture for both: web first.
- **Two people setting the clock at two points at once leave both with a clock**: the clock is kept on the point (`plan.perPoint[id].clock`), and each person's setting clears only the
  clocks their device knew of. The planner then times the route from whichever of the two comes first on the route, not from the one set later. Keeping it one value needs it
  out of the points (owner to decide, web first).
- **The web's clock form and unit builder keep what they opened with** (`RoutePlanSection.jsx`'s `clockEdit`, `UnitBuilder.jsx`'s initial parts): someone else's clock or unit part
  changed under them is put back by *Set* or *Apply*. Android's follow what changes under them (§17, *The held route's plan*, *Units*).
- **An aircraft placed as a person's own airframe carries only its slug** (`profileId`, the web's and Android's alike), and own airframes are per account: on every other
  member's device it is drawn and its separation measured as that device's mission aircraft (else the UH-60L), not as the airframe it was placed as. Carrying the airframe's
  geometry with the aircraft (rotor diameter, tip clearance, picture) would change the shared shape: owner to decide, web first.
- **Two devices that open at once an LZ/PZ an older client wrote without the headings its doghouses give each send them** (`LzPackSettingsTest`): two lines of the
  history, in two names, for one heading.
- **A helicopter's name (`label`) is Android's alone so far** (§17, *A helicopter has a name*): the web keeps the field with the aircraft but neither shows nor sets
  it (its map, the inspector, the LZ card), and a pack's history names an aircraft by its place in the list (`Chalk 2`, `packLz.js`'s `helicopters`), not its name, on
  both platforms. Showing and setting it on the web, and naming it in the history, is web first, the history through `contracts/fixtures/packs/describe.json`.
- The Android manifest's verified link filter names only `ezpztac.app`, while `AuthLinks.SITE_HOSTS` also takes `www.ezpztac.app`: a `www.`
  link opens the browser, not the app. Left so (the owner's call, 2026-10-10); add the host to the filter (and to the site's
  `assetlinks.json`) if emails or shared links ever use it.
- On Android the go-around arrows and a PZ marker's arrow head (`GraphicsImages`) are bitmaps of so many pixels handed to MapLibre with the screen's density
  (§14), so they are drawn smaller on a denser screen (on a 3.5-density phone, four-sevenths of their size on a 2-density tablet). Give them a size in points (`IconRaster`) once
  someone has judged on a device how big they should be. The aircraft are not affected (§17, *An aircraft is drawn to scale*).
- **True or magnetic** (the heading-aware slope, §13): every heading the slope tile uses is true. A crew that types a *magnetic* heading
  into the landing doghouse gets a box drawn, and a slope judged, off by the declination (about 5° in north Georgia); and `byHeading`'s
  (and `directional`'s) headings are the terrain grid's, true north over Terrarium but the **UTM grid's north over a local DEM**, turned
  from true by the grid convergence (about 1.7° at a zone's edge in north Georgia). Nothing corrects either (owner to decide).
- Until the server with `byHeading` reaches staging and production, every analysis there has no table: the apps say HEADING REQUIRED at
  15° and "Heading limits not checked (server not updated)".
- **The heightmap's samples are not where Cesium draws them** (`terrain_tiles.sample_tile`): the server fills a tile's 65 × 65 grid with `from_bounds`, so each
  sample is the centre of one of 65 cells across the tile, while Cesium's heightmap terrain puts its first and last samples on the tile's edges (64 intervals; the
  comment above `TILE_SAMPLES` means that too). The ground is drawn stretched by 65/64 about each tile's middle, up to 0.77% of a tile's width off at its edges
  (about 8 m at level 14 in north Georgia, more on coarser tiles), and two tiles' shared edge is read 1.5% of a tile apart. The web draws it so, and Android
  will draw what Cesium draws, to match; the fix is the server's and changes every cached tile (owner to decide).
- **A tileset built before the keyed names can still be turned back into its place**: its key is the first 16 hex digits of an unsalted SHA-256 of the target
  rounded to 4 decimals, so whoever has one (it is in URLs and the server's logs) can find the place to about 11 m by trying every rounded place in a region. New keys
  are an HMAC under `LIDAR_KEY_SECRET` (§13); the old tilesets were left under their names (the owner's call, 2026-10-26), and streaming (plan Part C) makes no new
  per-LZ ones.
- **Android streams too, untried on a device**: the whole survey's octree through `TileSelector` within the heap's point budget, and the
  first view's network cost below on a phone's link. Its reading of the streamed files is held by the readers' own tests, not a device run.
- **A streamed view fetches the landscape at full detail**: at error 2 every piece in the frustum is refined as far as its distance
  allows, so a wide first view at the Ellijay LZ was 370 pieces, 147 MB, where the LZ's own square is 52 MB. Heavy for a phone on a
  cellular link, and each piece holds one of the API's 8 threads for the half second AWS takes. Measure on the VM, then weigh a coarser
  error for the landscape, Cesium's dynamic screen-space error, or more threads (I/O-bound).
- Streamed pieces are not held back while an analysis runs, as terrain tiles are (`requestPriority`); AWS does the waiting, and a piece's
  conversion is about 13 ms.
- The web shows a failed build's `error` text (`useLidarTileset.js`), which can hold a tool's output with the place's coordinates in it; Android does not (§17,
  *The 3D view*).
- **The web's 3D view shows Cesium's default sky, sun and atmosphere, and fog on far ground** (`Viewer3D.jsx` turns none of `CesiumWidget`'s defaults off): a
  daylight sky above the horizon, where Android's scene (and the web's own `backgroundColor`, which the sky hides) is a flat `#05080d`, the dark the 3D plan
  means. Turn the web's off, or draw a sky and haze on Android: owner to decide, web first.
- **The slope map's switch differs between the apps**: the web's is the diagram's `view.showHeatmap`, off by default and saved with it; Android's is a device
  choice, drawn at launch (`SlopeVisibility`). Its opacity is the same on both (§13, *The slope map's opacity*). Which the switch should be is the owner's to say.
- **The web draws only the open LZ/PZ** (`MapView`: the active diagram's target, boundary and graphics). Android draws every other open one faded under it, with an
  eye on each row of the LZ/PZ panel to hide one from the map, and a tap on one holds it with *Open* and *Hide* (the owner's request of 2026-10-21; §17, *The open
  LZ/PZs stay on the map*). To give the web the same (web first, owner to decide): draw the workspace's other diagrams faded under the active one, and keep which are
  hidden per browser (`localStorage`, as `ezpz.mgrsGrid`), never in a diagram's `view`, which every client saves with the plan.
- **The web's point menu offers the IP type once and keeps the point's name** (`App.js`, "Designate point as:": Checkpoint, "RP / IP", "LZ / PZ (Target)", Shaping point; then
  "Name the point", filled with the point's own name, else `.CP`, `.RP` or `.LZ`). Android's bar offers the SP and the RP apart, both AMPS IP points, and gives an SP or an RP
  its name, a target `.TGT` in place of a name that only said what the point was, with nothing to type (the owner's request of 2026-10-21; §17, *A route point's type is in its
  bar*). Web first, owner to decide: the same five choices and names on the web, or a name asked for on Android as the web asks for it.
- **A point's type changed on two devices at once can mix in a Mission Pack**: a point's `kind`, `ptType` and `name` go as fields of their own (`PackDiff`), so making the
  SP the RP (its name alone) as someone else makes it the target leaves a target named `.RP`, and a point made a shaping point can keep the name another device gave it.
  Each field is valid and the later stands; the route's plan has always sent them so, the bar's dialog only makes it quicker. Keeping them whole (`RoutePackKind.keepWhole`)
  would also send a rename's type, so a rename could undo someone else's type change: the owner's call, web first.
- **Mission briefs on Android are the setup, review, Share and Save and the live sandtable, on a tablet** (B8, B9, `docs/MISSION_BRIEF.md`); the phone
  offers none. The brief's design boards (the canvas's section 6) were not to hand for B9: the sandtable follows the web's layout, and is reached from the
  setup's *Present sandtable* (on every step), the rail's *Brief* still opening the setup as B8 built it; the web's Brief menu presents in one tap from the
  top bar (owner's call whether the rail's *Brief* becomes that menu). An app from before B7, still installed, refuses a pack's `brief` item as malformed (`bad_kind`): its copy of the pack lacks the
  brief and skips every edit to it, and what its Pack panel does with such a pack is untried on a device. The app takes `item_counts.brief`
  (`PackItemCounts.brief`, 0 when absent), so the server may count briefs; it does not yet (the web's `COUNT_KEYS` and `pack_support.item_counts`,
  owner's call, web and server first).
- **A brief kept in the Library names the session's LZ/PZs differently on the web and Android**: the web by the diagram document's `id`, Android by
  the record's uuid, and the web saves an LZ/PZ with no `client_uuid`, so for one made on the web the two differ. A Library brief set up on one
  platform then shows the other's LZ/PZs as gone in its flow, and its answers for them do not show (they are kept). On the web a route set's key and
  its routes' ids are also made anew at every load (`openSavedRoutes`, `restoreSketchRoute`), so its own flow's route entries show as gone after a
  reload. In a pack both use the item's uuid and agree. One identity for a session's LZ/PZ and route in a brief is the owner's call, web first.
- **Two members starting a pack's brief at once make two brief items** (each's first change makes one), and only the first is shown, on the web and
  Android alike: the second's answers are in the pack, unseen. Owner's call, web first.
- **The weather at a planned time is asked an hour off across a change of daylight saving** between the brief's opening and its departure: both
  clients compile once at the opening's offset to learn where and when to ask (the web's `bare`), so the places' times carry that offset. The
  brief itself is then compiled at the departure's (the light data's). Web first.
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
  `docs/NATIVE_APPS_PLAN.md`, `docs/MISSION_PACKS.md`, `docs/ANDROID_MISSION_PACKS_PLAN.md`, `docs/ANDROID_SCREENS_PLAN.md`, `docs/ANDROID_3D_PLAN.md` (the 3D view on Android), `docs/MISSION_BRIEF.md` (the mission brief: B0 to B9 done), `docs/native-design/README.md`, `docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md` (3D streamed from USGS's octree, the admin's data map, uploads, warmed areas, organizations; decided 2026-10-23, not built yet), `docs/LZ_SURVEYS.md` (LZ surveys and the HLZ survey books: editors make, sign off and print them, readers open them, web only; L1, L5, L2, L6 and L4 built, L3 next), `android/README.md`, `contracts/README.md`. `docs/HANDOFF.md` is the
  "where things stand, what is next" note for whoever picks the Android work up; refresh it with each commit that moves either.

---

## 17. Native apps

The plan, decisions and phases are in `docs/NATIVE_APPS_PLAN.md`; this is what
exists and the rules for working on it.

**Layout.** `android/` is a Gradle project (Kotlin 2.4, Gradle 9.8, Android Gradle
Plugin 9.4; compiles to JDK 17, and builds and tests on JDK 21 or later, which Robolectric's SDK 36 sandbox needs); `contracts/` holds the golden fixtures and the
design tokens. iOS is not started.

| Module | What it is | State |
|---|---|---|
| `core-model` | Domain types in the web's saved-JSON shape (`LatLon`, `Mgrs`, `AircraftProfile`, route plan and result, and the saved LZ `Diagram` with its normalizer and `Workspace`), `DiagramGeometry` (a saved boundary `[[lat, lon], …]` read as points: a bad point is skipped, never guessed at) and `DiagramOps`: what can be done to one diagram (analyse, edit graphics, view, flight data, name; the standard doghouses), ported from the web's reducer and held to `contracts/fixtures/workspace/ops.json`, `Sidc` / `SymbolPresets` / `UnitMarkers` / `UnitDraft` (the unit builder's logic: SIDC parts, the preset lists and a new unit, held to `symbols/sidc.json`), and `Doghouses` (what a doghouse box says, how typed values are stored, and how the two standard doghouses give the flight data its landing and takeoff headings: held to `workspace/doghouses.json`, with `JsValue.parseInt`/`parseFloat` held to the same file's JavaScript reference cases), and `AircraftDraft` (the aircraft form: what is typed, the server's limits for it, and the document a valid draft makes: held to `aircraft/limits.json`), and `SketchRoute` (a route sketched on the map, in the web's saved shape), `PointSet` / `SetPoint` / `PointSets` (a saved set of local points, below), `ThreatEntry` / `ThreatDraft` (a threat in the picture, and its form; *Threats*, below) and `LocalPointNames` (what typing a name on a route point does with the loaded local points, held to `localpoints/match.json`) and `Weather` (a station's report, the NOTAMs around a target, and how old a fetch is; below), and `DiagramJson` (a diagram as the JSON the web saves, which the library's records and a mission pack's items are both made from) | done for these. Graphics stay opaque JSON, so a field a newer web release adds survives |
| `core-geo` | MGRS both ways, free-text coordinate parser, great-circle distance and course, `PlaceSearch` (what a person typed into a search or target field: a grid, or a coordinate in any format, resolved to a position or to a message in words) and `formatLatLongDms` (the degrees-minutes-seconds text the web keeps as a diagram's `latLong`) and `MapZoom` (the zoom that fits a stretch of ground on MapLibre's 512-point tiles) | done |
| `core-planning` | Aircraft geometry, capacity, separation, profile lookup, route planner, plan defaults and migration, `RouteWinds` / `RouteElevations` (what a route asks the weather and elevation services, and what it does with the answers: `routes/winds.json`), `LzSummary` (a polygon's area by the web's spherical-excess formula, capacity from the *unrounded* area, and the slope tile's call; `planning/summary.json`) and `PlanningGraphics` (rotor-edge lines, where a new aircraft / PZ marker / sector / go-around goes, separation alerts and their words; `planning/graphics.json`) and `SketchOps` (what can be done to a sketched route: the automatic attack profile of a finished sketch, designating, moving, inserting and appending points, plan and per-point values, the one clock anchor; held to `routes/sketch.json`) and `MissionRoutes` (an AMPS mission's routes as sketched routes, a copy, and where they lie: *Files from other apps*) | done |
| `core-formats` | Reads an AMPS `.msnx` into a `Mission`, an `.LPS` into a `LocalPointSet` and a `.ths` into `Threat`s (and `FileKinds` says which of the three a file is, by its content),  as the web's `parseMsnx` / `parseLps` / `parseThs` do, with a small read-only SQLite reader of its own (`SqliteReader`). `ThsExport` gives the rows of a `.ths` export. `RouteHandoff` writes a route as a Garmin FPL or a GPX (`foreflight.js`, held to `routes/handoff.json`). `MsnxWriter` builds an AMPS mission from sketched routes (`createMsnx`, held to the web's own exports) with `AmpsFormats` (the value formats AMPS keeps) over a small DOM toolkit (`MsnxXml`). `MsnxMutator` writes an edit to an imported mission back **into the file it came from** (`mutateMsnx`, held to the web's own edits: `contracts/fixtures/msnx/edits.json`), with `LegsText` editing the huge `legs.xml` as text (below). **Not ported**: *writing* a `.ths` file itself, which is the platform's job (copy `threat_template.ths`, insert `ThsExport`'s rows) | readers, export rows, the sketch exporter and the mission mutator done |
| `core-scene3d` | What the 3D view draws, as plain code (`docs/ANDROID_3D_PLAN.md`): the WGS84 frame (`Wgs84`, `geodeticToEcef` / `ecefToGeodetic`, and `LocalFrame`, east-north-up at the LZ in doubles, which every vertex is relative to; held to PROJ's own transform in `contracts/fixtures/viewer3d/geodesy.json`), `Vec3` and `Mat4`, the bounding volumes and the frustum (`OrientedBox`, `Sphere`, `Frustum`, with Cesium's distance to a box), `TilesetReader` (a `tileset.json`: boxes, spheres and regions, transforms, ADD and REPLACE by Cesium's rule, external tilesets, every content path kept inside the tileset's store by `TilesetPaths`) and `PntsReader` (a `.pnts`: `POSITION`, `POSITION_QUANTIZED`, `RGB`, `RGBA`, `RGB565`, `CONSTANT_RGBA`, `RTC_CENTER`, into floats in the local frame), both hostile input with every length bounded, the camera (`ViewCamera`: Cesium's field of view, projection and screen-space-error denominator; `lookAt`, the web's opening pose), `TileSelector` (Cesium's base traversal, rule by rule, with a point budget) and `TerrainTiling` (the heightmap's geographic tiling, held to `viewer3d/terrain.json`); and the points' scene (`PointCloudScene`: the tilesets' tiles fetched as frames ask, held within a budget and let go of by use; `SceneLook` and `PointShading`, the web's settings and Cesium's attenuation and eye-dome lighting, held to `viewer3d/scene.json`, with `Twister`, the MT19937 Cesium samples a tile with; `SceneDepth`, the logarithmic depth; `SceneGround`), the camera's moves (`CameraMoves`, `CameraFlight`, and `TouchGestures`, the fingers read as moves) and the compass (`CompassPose`, held to `viewer3d/compass.json`, and its dial's perspective); and the ground (`TerrainLook`, Cesium's heightmap error and skirts; `TerrainMeshes`, a tile's vertices from the server's heights; `TerrainScene`, Cesium's quadtree; `ImageryTiling` and `ImageryScene`, Mapbox's satellite tiles laid on it as Cesium lays them); and the routes (`RouteScene3D`, the web's `routeScene.js` held to `viewer3d/routeScene.json` case by case: where to ask the ground along each visible route and the shapes and labels once it answers; `RouteGeometry`, those shapes in the scene's frame as the web's Cesium entities draw them, and `BoundingSpheres`, Cesium's sphere round them for *Routes*). Pure, JUnit 5 | the frame, the readers, the camera, the choice of tiles, the points' scene, the moves, the compass, the ground with its imagery and the routes done |
| `core-network` | The API client over OkHttp: one transport (`ApiClient`) with the session behind it, typed calls for the routes in `contracts/openapi.yaml`, DTOs, the request-priority gate, and the "update required" check. See *The API client* below | client, auth, refresh, sign-up, verification, password reset, the `.mil` gate and terrain analysis (`analyzeField`, `terrainAnalysis`) the saved routes (`listRoutes`, `getRoute`, `createRoute`, `updateRoute`, `deleteRoute`: a multipart form, `Call.form`) and route planning (`elevations`, `routeWinds`) and the weather at a target (`weather`) done, and so are the saved mission briefs (`listBriefs`, `getBrief`, `createBrief`, `updateBrief`, `deleteBrief`: JSON, `{name, data, client_uuid}`) and the brief's own two calls (`weatherAt`, the weather at a planned time kept whole as JSON, the fields the compiler reads held to the recorded answers by `DtoFixtureTest`; `printBrief`, the PDF's bytes, a heavy call), and so is every mission-pack, team, invitation and people-search route (`PackEndpoints.kt`: typed calls for the screens, and three untyped JSON calls for the pack engine so a `null` and a field a newer server adds survive; an id that could reach another route is refused before anything is sent, a refusal keeps the server's whole answer in `ApiException.body`, and a call made for one account, `asUser`, is never sent under another's session: `OtherAccountException`), and a pack's live stream (`PackLive.kt`, `openPackLive`: OkHttp's own WebSocket, the hello first, every refreshed token passed on and never another account's), and the 3D view's (`Viewer3dEndpoints.kt`: the LiDAR lookup, a build, its progress and letting go of it, a tileset's files as bounded bytes, the heightmap tiles and the heights under a route; *The 3D view*, below); the web-share, threat and export routes are not yet typed |
| `core-sync` | The sync engine: local edits into an outbox, a push in order, a pull by cursor, conflicts kept side by side. Pure logic over a `SyncStore` interface (`RoomSyncStore` in `core-data` implements it for the app; `InMemorySyncStore` here). LZs, custom aircraft profiles, saved routes, point sets and mission briefs sync (`RecordKind.ROUTE` is a *set* of sketched routes, the server's `kind: sketch`; a `kind: mission` record, which carries an imported `.msnx`, is passed over for now; `RecordKind.POINT_SET` is the points of one `.LPS` import, below; `RecordKind.BRIEF` a brief kept in the Library, below). Beside it: `RecordFeed` (a list that updates itself, which a screen reads; the store implements it, a test double need not; `observe(kind, uuids)` follows a few records without the rest, Room's reading only their rows), `SyncScheduler` and `ConflictResolver` (the seams the app and the screens use without knowing about WorkManager or the engine). Its `testFixtures` (the scenarios as plain functions, the fake server, a `Device`, `RecordingScheduler`) are shared with `core-data`. See *The sync engine* below | engine done |
| `core-missionpacks` | Mission packs (`docs/MISSION_PACKS.md`; **not map packs**, which are the plan's `core-mappacks`, `/api/map-packs` and `AppConfig.Services.packs`): the web's `feature/missionPacks` ported file by file and held to `contracts/fixtures/packs`, then the client that sends an open pack's edits and follows everyone else's. Pure, JUnit 5. So far `PackOps` (what one operation does to a pack's items, as `packOps.js` and the server's `pack_ops.py` do it: `apply` and `validate`, held to every case of `packs/ops.json` and `packs/ops-brief.json` (the `brief` kind, docs/MISSION_BRIEF.md §3, which holds an object) strictly: status, reason, null kept apart from absent, and every object's keys in JavaScript's order; only the objects and lists on the edited path are new, everything else keeps its instance), `PackDiff` (the operations an editor's change to an item is sent as, `packDiff.js`: `sameData`, `diffData`, `diffItem`, held to every case of `packs/diff.json` in order, each rebuilt with `PackOps`; an object is walked in JavaScript's key order, which a fixture written by JavaScript cannot catch, so a test with keys in another order does; the operations keep their nulls as `JsonNull` (`"after": null`, a patch's `{field: null}`), so they are never written through a serializer that drops nulls. **Not like the web**, the `webBug` cases: only an object's own fields are read, so a field named `constructor` or `toString` that is null on one side and missing on the other is no change and one that went is sent as null, and a changed `__proto__` is sent as data), `PackRef` (an editor's id for a pack item, `pack:<pack>:<item>`; `packRef.js`), `PackSessions` (one open pack as the device holds it, `packSession.js` function by function, its `droppedVersions` included: confirmed items, the edits not yet confirmed and what is seen, as JSON trees, never the server's types written out again; held to every scenario of `packs/session.json` and `packs/session-brief.json` step by step, the whole session, what is visible and each answer after every step, null kept apart from absent and every key in JavaScript's order. A brief is open to every member in every state of the pack (docs/MISSION_BRIEF.md §3): an edit whose every operation is on a brief is taken in a read-only pack, brief edits go in batches of their own (the server takes them in a read-only pack only alone), and a pack that turns read-only keeps the brief edits still queued; `FakePackServer` has the server's exception (`_only_briefs`), and a `PackScenarioBook` story holds it end to end on the fake, the real Flask server and Room. Like the web's, it makes new objects only where it changes something, so an item no event touched keeps its instance: `PackSessionIdentityTest`, because the editors and the store tell what changed by instance and an item can be megabytes. Android's own, since only the device keeps a session: `hasSentOrAcked`, asked of a session read back from the device before anything else (when a batch was out or an edit taken, the engine catches up from the stored `seq` with `receive` before any reload, because a reload into a pack finished since would drop an edit the pack has as never sent: session.json's second known exception, which here would follow every restart with a batch out), `restored`, which puts the batch that was out when the app stopped back to be sent again under the same names, and is applied only just before that resend, so a reload in between still holds the batch out, `withoutDropped`, which lets go of dropped edits once kept, and `withView`, the view worked out again from the rest, which is all a store that does not keep the view may do to a session it reads back. Inputs the server never sends are narrowed, not copied, each with a test (`PackSessionTest`): an event without a number is passed over, a count or revision that is not a number is taken as missing (and a missing revision as 0, where the web's would be NaN), a result whose `seq` is not a whole number acks its edit with none, to wait for its event, and a body without a list of items is refused), `PackFailure` (the web's `failureOf`, from core-network's exceptions, with what a refusal's body says, `taken` and a `finished_by` of null among it, kept apart from what it does not say; an answer that could not be read is status 0, sent again and answered from the log; `pauses` is a failure about the account, 401, `other_account`, `affiliation_required` or `feature_disabled`, which the engine asks before the session, since the web's `batchFailed` reads such a 403 as the pack gone) and the internal `Js` (what JavaScript does that the ports must do the same way: an object's keys in the order JavaScript enumerates them, array indexes first; `===` on ids, so `1` is `1.0` and never `"1"`; `idKey`; truthiness; `Number()`, `Math.round`, `String(v)`, `trim`, a spread and a field read as JavaScript reads them, own fields only), then what of an item is each person's own and how a change is told: `PackLz`, `PackRoutes` and `PackPoints` (`packLz.js`, `packRoutes.js` and `usePackPoints.js`: the shared part of an item, today's shape of one, points made ready for a pack, and the history's sentence for a change, held to every case of `packs/shared.json` and `describe.json`), `PackSentences` (`packSentences.js`: who did it, a new item's id, name, operation and sentence, rename, removal, copy, update from the original, the kept name), `PackEdit` (`packEdit.js`: what one change is sent as, rename first, then the reshape only with a content change; `packs/edit.json`), `PackHistory` (`packHistory.js`: a pack's log read as the history's lines, one for each change however many operations it went as; `packs/history.json`), `PackActions.libraryData`, `PackMessages` (the app's own words for a refusal and a joined invitation), and `PackKind` with `LzPackKind` and `RoutePackKind` (an item as the editor's `Diagram` or `RouteSet` and back, keeping each person's own fields on the device: the view, the hidden routes; an item's shape is the shape of the document made from it, so opening one sends nothing). **Not like the web**, the `webBug` cases, each with a test: an LZ/PZ keeps the fields a newer version wrote at the top, in its target, analysis and graphics (`PackLz.carryUnknown`, never a name the web reads nor a slope raster), where the web's first change would send null for them; a route set keeps its own fields, its version and every route that is not an object with an id, where it stands; and no sentence or kept name is cut through an emoji (`Js.cut`). A `RouteSet`'s routes are typed, and the types cannot hold everything a pack's set can (a null in a plan, a colour that is a number), so a route set's document is the item's JSON with the editor's own changes laid over it as `PackDiff` operations, and only what changed is written from the types: a change inside a plan value the JSON has as null (read as the default) goes as the editor's whole value of it, and routes two of which share an id go whole, each one not changed as it came and each the types cannot read in its place; a route is never written beside another with its id (one the types cannot read that the editor still has a version of is measured as the pack has it), and a route point's place is sent whole, both coordinates, as are an LZ/PZ graphic's and the target (`PackKind.keepWhole`; *Mission packs in the editors*); then the client that keeps one pack in step with the server (`PackClient`, internal: `packClient.js` with the device's copy behind it, over `PackApi` / `ApiPackApi`, the three untyped engine calls and the live stream, `PackStore` / `InMemoryPackStore`, the device's copy, and `PackKeeper`, the library; the screens see `PackState`, `PackStatus` and `PackNotice`; of these, `Refused` and `ItemRemoved` are said by core-data's editors, never the engine). It loads, sends one batch at a time with the web's pauses (1, 2, 5, 10, 30 s), catches up page by page, polls every 3 s only in the front and without a stream, follows the stream (every message and close code) and, stopped, drains. Every change to the session is on the device before anyone sees it: an edit returns once it is written, a batch is written as sent before it goes, and a copy read back after the app was ended is shown at once (`OFFLINE`) and can be edited; a batch it had out, or edits the server took, first wait for a catch-up from its `seq`, whose events confirm them, before anything is loaded or sent. So a store gives a session back as it was written (`PackStore.load`: the batch out still sent, taken edits still acked with their seq, never put back in the queue or settled), and the client puts that batch back in the queue only once doing so is written: a write that fails (a full disk) leaves it to be tried again, never out for good. **Not like the web**, by the owner's decisions: a failure about the account pauses with every edit kept (the live service's 4403 is asked about, never taken as the pack gone); a 403 or 404 on load or catch-up drops what waits as gone; what a pack refused is kept in the library as `NAME (my edits)` whenever the pack is not open (at the end of a drain, and when it is gone); and a 429's Retry-After holds every send until it is over. Then the engine (`PackEngine`): every pack the device is working on, for the account `enable`d, with one client per pack, ever, so a pack has one batch in flight whichever holds it: the open one (one at a time, as on the web), those closed with edits still on their way (they send them, keep what the pack refused, and stop), and those `enable` or the background sync (`drainAll`) finds with edits on the device. A pack opened again while it still sends is the same client, opened again (`PackClient.reopen`: a drain that never took the pack from the server takes it then); one paused for the account's sake, or stopping, is replaced, which asks again. `open` is set as the open client sets its own state, never after: a collector would lag one dispatch behind, and an editor reading it once its edit returned would take its own change back. `disable` stops everything at once and gives nothing up; another account's `enable` stops the first's clients and never sends, keeps or prunes what is theirs; pruning waits for an enable with nothing running, since a client writes only what changed (Room's store rewrites a row only for a new instance) and a pack pruned under one would come back in part. A pack is noted as opened (pruning keeps the ten opened last) once the device holds it, not when it is asked for: one opened for the first time is stored only when its first load is written, and noted before that it would keep no time and be the first forgotten. What a pack refused is kept in the library whenever the pack is not open (at its close, at `enable`, in a drain), and a sync is asked for so the library's new record goes up. `drainAll` sends what waits in every pack but the open one (its client sends by itself), waits for a client already sending and for a pack closed meanwhile, but not for one waiting to try again after a try that got no answer or met the server busy or down (`PackClient.stalled`, set while its retry timer runs: the run says `RETRY` at once and the next run tries again, where waiting out the client's pauses would hold a run with no way through for its whole minute, and the library's sync behind it), and says `RETRY`, `PAUSED` or `DONE`; in a process the app did not start (nobody enabled) its clients stop when it returns, leaving what they had not sent for the next try. `PackItemSync` is the decision half of `usePackItemSync.js` for the editors: their baselines, `reconcile` branch for branch, `flushPlan` through `PackEdit`, `takeTheirs`, which lays only the other person's change over each version of a document, so undo keeps this person's steps and theirs, and `putBack`, which undoes a change the pack will not take, in every version (`takeTheirs` would keep it, and it can never be sent; the web replaces the document whole for both, and does not tell `PUT_BACK` apart). It is held to the tests of usePackLz, usePackRoutes and usePackPoints case for case, over an editor harness with fakePack.js's rules (`PackItemSyncTest`), and an LZ/PZ a newer web wrote keeps its fields through changes both ways. `InviteAcceptance` is `useInviteLink.js` without React: one request per link, run in the scope it is given rather than the caller's, so a caller that goes away does not end it, and an answer kept for the next run, since the server refuses a link accepted twice. A run with no link changes nothing, as the web's effect returns at once: the answer stays until it is put away, though the caller forgets the link as soon as it is done with. It is for one account at a time (a run for another, or `forget`, starts from nothing; an answer that comes once its account has gone is never said), counts its tries (`attempts`, so the same answer to a new try can be told again), and, **not like the web**, keeps the link through a refusal about the account (`PackFailure.pauses`) and says it waits. `FakePackServer` (the server's pack, events and ops routes, their refusals with `taken`, a 413 rolling the batch back, lost and held answers), `FakeLive`, `RecordingKeeper`, `PackStoreContract` (what every `PackStore` must do, as plain suspend functions: Room's store is to be run through them under Robolectric, as the in-memory one is under JUnit 5) and `PackScenarioBook` (how packs behave between people and devices, as plain suspend functions in real time over `PackEnv`, `PackDevice` and `Network`: two people on one LZ/PZ, the same field, a finish while a device was offline, a viewer, a removal, an answer lost, a batch out when the app was ended, a long time offline across restarts, the background drain keeping what was refused, an edit to something deleted, an edit too large for the pack, a catch-up of more than a page, a route's checkpoint moved on both devices, and an aircraft, through an editor's decisions (`PackRouteEditor`, `PackLzEditor`, over `PackItemEditor`); `FakePackEnv` runs them on the fake, and `LivePackScenarios` on the real Flask routes, every account with Mission Packs ticked and every member let in by an emailed invitation) are its test fixtures | operations, the diff, the client session, each person's own fields, the shapes, the sentences, the client of one pack and the engine done; the device's store is core-data's (`RoomPackStore`) and so is the editors' join (`PackWorkspace`); the app runs them (A13) and takes invitation links (A14); the workspace switcher opens and leaves them (P1, `feature-packs`), and the other screens are not started |
| `core-brief` | The mission brief's core (`docs/MISSION_BRIEF.md`, B7), pure, JUnit 5: the formats as data (`BriefFormats`: `contracts/briefs` bundled unchanged as resources by the build's `bundleBriefFormats`, held byte for byte to the files and to the format's rules by `BriefTemplatesTest`, the web's briefTemplates.test.js ported with its broken-template cases), `CompileBrief` (the web's `compile.js` function by function: a format, the brief document, the plan and the context in, the BriefModel out, all JSON as the web passes them, because the inputs are the documents the app holds and the model is what `POST /api/brief/pdf` takes; the plan is read with the web's loose rules (the module's own `Js`), its LZ/PZs and routes through `DiagramNormalizer`, `RoutePlans` and `RouteCalc`, `Doghouses.landingHeadingOf`, `LzSummary.slopeCall` and `MgrsConverter`; held to every case of `contracts/fixtures/brief/compile.json` strictly: null kept apart from absent, every number in JavaScript's digits (`JsonUnquotedLiteral` of `JsNumber.toText`, so 1 is never `1.0`), every object's keys in JavaScript's order, and the same model in any time zone) and `Light` (`light.js`: BMNT, sunrise, sunset, EENT, moonrise, moonset and illumination, its trigonometry `StrictMath`'s; held to Skyfield in `light.json`). The strip charts (B6, `StripCharts`: each route cut into kneeboard strips, `model.strips` and the `strips` pages) measure on the web's sphere with `StrictMath`'s fdlibm (the same bits on the JVM and every device; V8's trigonometry differs from it in the last bit now and then, which the web's rounding hides) and V8's own `Math.hypot` algorithm, bit for bit (`Sphere`): every number of `compile.json`'s strip cases is exact, and an independent run of the web beside the port over about 7,300 generated routes found every model byte for byte the same; the compiler's own distances (an LZ/PZ on a route, a threat's bearing) use `Sphere` too. The strips' MGRS grid is not the compiler's: it is added when the PDF is asked for (`StripGrid`, the web's `stripGrid.js`, held to `strip-grid.json` within `mgrs/grid.json`'s tolerances). `BriefEdits` are the setup's edits (the web's FlowStep, DetailsStep, ReviewStep and BriefSetup handlers) as pure transforms of the brief document, so the keeper can lay one again over a newer version; **not like the web**, the flow is edited by reference from the document's own flow when it is the one shown (give or take what left the plan), so two edits made before the brief is compiled again do not undo each other. An independent run of the web's `compile.js` beside the port over about 2,900 varied inputs found it faithful; what it found apart came from the shared ports and is fixed there (an airspeed with no `type`, a plan date past its month's end, a two-digit year, a clock's trim, a heading in a list) or in the port. **Not like the web**, each in the `CompileBrief` doc: a route with a point that is not an object with a numeric `lat` and `lon`, or a plan the planner's types cannot read, is not briefed (a flow naming it says it is gone); a plan value given as text is read as its number; a point whose id has empty text is no named point; and on a day the clocks change the web's planner counts clock times through the device's zone (an hour off in the hour skipped or repeated, a web bug), where here they are counted as they are | compiler (strip charts included), light data, the strips' print-time grid and the setup's edits done, and the sandtable's steps (B9: `SandtableSteps`, the web's `sandtableSteps.js` function by function, held strictly to every case of `contracts/fixtures/brief/sandtable.json`, from compile.json's model and from the brief compiled here; typed readers `listed`, `look` for the screens) |
| `core-data` | Android library: the Room database (`EzpzDatabase`, version 4: a record can carry a file (2), below, the mission-pack tables (3), and when a record last changed (4), *The Library*), `RoomSyncStore`, `AccountScope` (whose plans are on the device; it counts and wipes mission-pack edits too), `RoomPackStore` (every mission pack held on the device, in four tables of their own: `pack`, `pack_item`, `pack_op_outbox`, `pack_own`; held to `PackStoreContract` and the pack scenarios, and rewrites only what a session says changed) and `LibraryPackKeeper` (what a pack refused, saved to the library as `NAME (my edits)` under a uuid its key names; a brief as a `BRIEF` record, its document whole, and only for an account with `mission_brief` (`/api/briefs` refuses one without, and the record would be blocked for good), **not like the web**, whose `saveVersionToLibrary` would save it as an empty set of routes) and the encrypted session store (`EncryptedSessionStore` over a `SecretBox`; `KeystoreSecretBox` is the Android Keystore one), plus `DiagramRepository` / `DiagramSession` / `AnalysisService` (the LZ diagrams and their online analysis, below), `AircraftProfiles` (the airframes: the admin's master list, the user's own and the mission aircraft; see *Aircraft profiles*), `BoundaryDrawing` (a landing-zone boundary drawn by hand; see *Boundary drawing*), `RouteRepository` / `RouteSession` / `RouteSketching` (saved sets of routes; see *Routes*), `PointSetRepository` / `PointSetViews` / `LocalPoints` (saved sets of local points; see *Local points*), `SessionPointSets` and `ThreatFileLog` (the sets of points held for this session only, and the threat files brought in; see *Import*), `ThreatStore` / `EncryptedThreatVault` / `ThreatTransfer` / `ThsWriter` (the threat picture and its `.ths` files; see *Threats*), `RouteHandoffExport` (a route as a GPX or FPL file), `IncomingFiles` / `FileInspector` / `MissionImporter` / `MapFocus` (files other apps hand over, held until the person answers, and what accepting a mission does; see *Files from other apps*), `DocumentSession` (what the open diagram and the open route set share), `OpenDiagrams` and `OpenRouteSets` (the LZ/PZs and the route sets open now, the redesign's "this session"), `OtherDiagrams` and `DiagramsOnMap` (the open LZ/PZs not being worked on, drawn faded, and which of them are hidden: *The open LZ/PZs stay on the map*) and `GraphicPlacement` (a planning tool as a mode), `PackItemStore` / `PackEditorSync` / `PackWorkspace` (a mission pack's LZ/PZs and route sets edited in those same sessions; see *Mission packs in the editors*), `OpenPack` / `PackGlance` / `PackFacts` / `PackLock` (what the LZ/PZ and Routes panels read of the open pack, and its words; why nothing in a read-only one can be changed; see *The panels in a pack* and *A read-only pack*), `LibraryToPack` (a Library record copied into a pack, its last change written and uploaded first; see *Copies into a pack*), `BriefKeeper` / `BriefSources` / `BriefSession` / `BriefPlans` / `BriefWords` (the brief being set up: where it is kept, what it is built from, compiled with the weather at its planned times; `RouteRepository.read` reads a mission's routes for it without holding the file; see *The brief on a tablet*), `LidarBuilds` / `BuildOnSave` / `TerrainProbe` / `TilesetFiles` (the 3D view's point cloud found or built, a saved LZ/PZ's asked for in the background, whether the server has ground there and its heights, `TerrainHeights`, and the cloud's files, by the key and path its URL gives, `TilesetAddress`, kept on the device for a week, `CachedTilesetFiles`; the ground under its routes, `SceneHeights` over `GroundUnder`, and how they stand, `RoutesPlacing`; see *The 3D view*) and the Hilt module. One generic `record` table keyed by (kind, uuid) instead of the plan's table per domain, because the engine treats every kind alike; add a column when a screen needs one. Schemas are exported to `core-data/schemas/` and **committed** (a migration test reads them) | store done and held to the same scenarios as the in-memory one; `DiagramRepository`, `DiagramSession`, `AircraftProfiles`, `BoundaryDrawing` the route-set classes, the point-set classes and `WeatherService` (below) done; `RoomPackStore`, `LibraryPackKeeper`, the pack engine's Hilt providers and the editors' join to a mission pack done, the sessions handed their pack stores (`DataModule`: `lzItems`, `routeItems`); packs run with the app (A13), and the workspace switcher opens one (P1) |
| `core-testing` | Reads `contracts/fixtures`; JSON comparison with a tolerance. Test support only, not in the plan's module list | done |
| `core-designsystem` | Android library: the theme (dark, light and the red-shifted **night** palette), type, shapes and `Tokens`, which is **generated** from `contracts/tokens/tokens.json` by `contracts/scripts/tokens.py` (CI checks it is current). The palettes are the menu redesign's neutral avionics colours (`docs/native-design/tokens.json`; the navy is retired) as Material 3's full set of colour roles, every one set so nothing falls back to Material's stock lavender; light fills the roles in the web's light family and night in red, never bright. Tests hold every pair a screen draws to WCAG (`TokenContrastTest`; the state chips and notices tinted with their colour over each surface container too, in the palettes the app draws, dark and night: night's text and status colours were raised 7% for them, and light's would need its status colours darkened about a quarter) and every night surface darker than a day card (`ThemeTest`), because nothing in an agent session can look at a screen. `EzpzIcons` are the Material Symbols Rounded the screens draw, as vector drawables listed in `symbols.txt` and written by `symbols.py` (Apache 2.0; not `material-icons-extended`, which Google no longer maintains). `Banner` is the redesign's tinted notice; `ToastHost` draws toasts in the palette's inverse roles (dark ones at night) | theme, tokens, icons, and the panel over the map: `PanelSheet` (three heights, lowered to its header, half and full, on foundation's anchored drag, because Material's sheet has two; Material's nested scrolling; only what shows is laid out, so a footer can be pinned; lowered grows with a header taller than the design's, the person's text being larger; the handle is a named button), `PanelHeader`, `PanelAction`, and its arithmetic in `SheetAnchors` (tested without a screen); the parts the panels repeat (`PanelParts.kt`: `StateChip`, `SectionLabel`, `ListRow`, `PillButton`), `initialsOf` (a person's letters, by whole characters: the account button and a pack's faces), `ChipLine` (what a chip on the phone's row holds, and what of it gives way before its name is cut: *The phone's row*, below), and `keptAway` with `PanelSheet(inert)` (what the phone's frame does to what it hides: *The phone's frame hidden*, below). Other shared components join as screens need them (`docs/ANDROID_SCREENS_PLAN.md`) |
| `feature-auth` | The sign-in screens: sign in, register, "check your inbox", verify and reset (from an emailed link), forgot/resend, and the `.mil` gate (`AffiliationHost`). Stateless screens (`AuthContent`, `AffiliationContent`) over two view models; talks to the server through an `AuthApi` seam. Mirrors the web's `AUTH_MODES`. The site's links are read here: `AuthLinks` (the emailed sign-in links) and `InviteLinks` (an invitation's token), through one site check | done; Google sign-in is the app's (below) |
| `feature-map` | The 2D map: MapLibre Native (the SDK directly, not `maplibre-compose`, which is 0.x and would hide the handles a planning map needs), the three base maps under the web's ids (`satellite`, `topo`, `vfr-sectional`), the crosshair readout (MGRS first, computed on the device), search by grid or coordinate, GPS from the platform's own receiver, and `MapViewModel`. `MapScreen` lays the controls over the map: on a phone one row along the top (`MapTopRow`: the search folded into a round button that opens across the row, `MapSearchState`; the chips, `MapChip`, `ChipsThatFit` keeping them on one line; an account slot at its end; *The phone's row*, below) and a slot under it (an open pack's status), on a tablet the search bar along the top, always open (`FrameHiding`: how far the phone's frame has gone, the row going with it); the map's buttons, the crosshair at the middle of the camera's padding (`MapPadding`) and the readout pill (the grid alone; a tap copies it), with slots for the sheet and the tools that float above the pill; `MapChrome` says where each sits. The logic that decides *what* to ask the map (styles, readout, search, GPS state, where the camera goes) is plain code and tested; the MapLibre glue (`MapHost`, `EzpzMap`, `GpsOverlay`, `DiagramOverlay`) needs a GPU and is compile-verified only. `LzScene` is what is drawn for the open diagram (target, boundary, a boundary being drawn as an open dashed line with a dot at each corner, the slope raster, and the planning graphics as `GraphicsScene`) as plain data with its GeoJSON, so *what* is drawn is tested. `MapProjection` is the screen↔lat/lon arithmetic (labels, and later tap and drag), `AircraftIcons`/`AircraftIconRenderer` draw each airframe's top view (the UH-60 from `assets/aircraft/uh60.svg`, the original helicopter at its published dimensions, with AndroidSVG), `MapLabels` works out the separation labels for a camera, `GraphicHitTest` which graphic a tap was on, `DoghouseBox` the doghouse's label box, `UnitMarker` a unit's symbol or its plain stand-in. `OtherLzScene`, `OtherLzOverlay` and `OtherLzHitTest` are the open LZ/PZs not being worked on, drawn faded under the open one (*The open LZ/PZs stay on the map*). Threats: `ThreatScene` (a threat's marker and its detection / engagement rings as plain data with GeoJSON; a ring is a geodesic circle of 96 points that stays continuous across the antimeridian), `ThreatOverlay` (the ring layers: dashed amber detection, solid red engagement), `ThreatLabels` (`ThreatLabelsLayer`: the MIL-STD symbol over the map as a Compose overlay, a diamond in the affiliation's colour while the symbol loads, is refused or has no sandbox) and `ThreatHitTest` | base map, readout, search, GPS, the diagram's target, boundary and slope raster, and the planning graphics (aircraft with keep-out discs, separation lines and their labels, PZ markers, sectors, go-arounds, a halo on the held one; doghouse boxes; units' symbols; a tap on the map holds a graphic, via `GraphicHitTest`; threat markers and rings; a tap holds a threat, after a graphic and before a route); the MGRS grid over any base map, from the Layers menu (`MgrsGridScene`, `MgrsGridLayer`: *The MGRS grid*, below); drag and rotate on the map are not built |
| `feature-workspace` | The LZ/PZ panel (`LzPanel.kt`, S3 of `docs/ANDROID_SCREENS_PLAN.md`): its pages (the open LZ/PZs, the open one's plan and tools, the aircraft; every LZ/PZ saved is the Library's, S7), naming (`SaveLzSheet`), the tools' modes over the map (`PlacementToolbar`) and made-up names (`LzNames`), over the view models below. Before S3 it was the Diagrams tab of the sheet: the list (name, grid, status, whether the server has it), making one from a target (the grid under the crosshair is offered), opening, renaming, deleting (asks first) and settling a conflict (keep both is the primary choice; keep mine / keep theirs say what they discard). `DiagramsViewModel` over `DiagramRepository`, `DiagramSession` and a `ConflictResolver`; the screen (`DiagramsContent`) is stateless and scrolls only if its host does | list, create, open, rename, delete, conflicts, and the open diagram's card: Analyze / Stop, the summary tiles (capacity, area, elevation, slope call with its source) and rename/delete. The list below it is the *other* diagrams (an open conflict copy keeps its row for its choices). `GraphicsViewModel` / `GraphicsContent` are the planning graphics inside that card: place a helicopter, PZ marker, sector or go-around at the crosshair, a list of them with their grid, an inspector (nudge in feet with a 10/50/200 ft step, put at the crosshair or at a typed grid, turn, set a heading, a PZ's reach and tip, a go-around's side, a doghouse's label, time, distance and airspeed, delete), the unit builder (add a unit at the crosshair, or apply the builder to the held one), undo/redo and the separation alerts. Every change is one `DiagramSession.edit` step. `AircraftViewModel` / `AircraftContent` are the aircraft section of the sheet: the mission aircraft chosen from the admin's list and the user's own, and making, copying, changing and deleting the user's own (the form mirrors the web's `AircraftProfileModal`); `AircraftPicker` in the diagram's card chooses the mission aircraft and says what its numbers are. `BoundaryViewModel` / `BoundarySection` (the sheet's "Draw boundary") and `BoundaryToolbar` (over the map while drawing) are the hand-drawn boundary. `WeatherSection` is the weather tiles of an analysed diagram's card. `ThreatsViewModel` / `ThreatsScreen` are the Threats panel (S5, below): the threats with their reach and mask, the export, and each threat's form as a full-screen dialog. `RoutesPanel.kt` and `RoutePlanPage.kt` are the Routes panel and the route's plan (S4, below). `ImportSheet.kt` (the Import menu), `ImportReview.kt` (the review of every file brought in, over `IncomingViewModel`) and `ImportsPanel.kt` (the Imports panel, over `PointsViewModel`) are S6 (*Import*, below). `LibraryPage.kt` over `LibraryViewModel` is the Library (S7, *The Library*, below), and `NameTakenChoice` its *Keep both* / *Replace*, which the Save sheets share. `LargeDialog` is a tablet's large dialog (S9) |
| `feature-packs` | Android library: the mission-pack screens (`docs/ANDROID_SCREENS_PLAN.md`, P1–P4). So far the workspace switcher (P1): `WorkspaceViewModel` over its seams, `PackDoor` (the engine's open pack and the switch itself: the app's `EnginePackDoor` over `PackEngine` and `PackWorkspace`), `PacksApi` (the switcher's lists and answering an invitation), `Parking` and `LastPack` (the app's `SessionParking` and `LastPackPreferences`) and `LibraryTally`; `PackWords` (the web's words for all of it), `SwitcherSheet` / `SwitcherContent`, `PackChip` with its `Faces` and how the pack stands (`PackStanding`), and `PackStatusLine` (on a phone only for what wants attention: `rememberPackAttention`, `PackAttentionLine`). See *The workspace switcher* below. Then the Pack panel (P2a): `PackPanelViewModel` over `PackDoor` and `PackPanelDoor` (the history, the seen marker, and the items through `PackWorkspace`: `EnginePackPanelDoor`), `PackPanelWords`, `PackPanelContent` / `PackPanelHost`. See *The Pack panel* below | the switcher, the chip, the status line and the Pack panel done, and the LZ/PZ and Routes panels show the open pack's items (P2b, in `feature-workspace`: *The panels in a pack*), and nothing in a read-only pack is offered to be changed (P2c: *A read-only pack*), and a finished pack has its banner, with Finish, Reopen and Duplicate (P2d: *A finished pack*), and everyone else's pointers are on the map (P2e: *Pointers in a pack*), and a new pack is made from the switcher (P3a: `NewPackViewModel` over `PeopleApi`, `NewPack.kt`; *New pack*), and its members are managed (P3b: `MembersViewModel`, `Members.kt`; *Members*), and the person's teams (P3c: `TeamsViewModel` over `TeamsApi`, `Teams.kt`; *Teams*), and copies of the person's own items go into a pack (P4a: `PackCopyViewModel` over core-data's `LibraryCopier`, `Copies.kt`; *Copies into a pack*), and a copy is updated from its original and any item saved to the Library (P4b: `PackItemCopyViewModel` over `PackItemCalls`, `ItemDialogs.kt`, `UpdateWords`; *Updating a copy, and copies to the Library*), and the edits a pack would not take are kept or discarded from the Pack panel (P4c: *The edits a pack would not take*) |
| `feature-brief` | Android library: the mission brief's screens on a tablet (`docs/MISSION_BRIEF.md`, B8 and B9): `BriefViewModel` over `BriefDoor` (core-data's `BriefSession`), `BriefPdfs` (`RendererBriefPdfs`, the platform's `PdfRenderer`) and `BriefFileWriter`; `BriefSetupHost` / `BriefSetupContent` (a dialog nearly the window), its steps `FlowStep`, `DetailsStep`, `ReviewStep`, and `DraftField`, `SetupParts`, `BriefReading`; the live sandtable (B9): `SandtableFrame` (the bar, steps, constants and card), `SandtableScene` (what the map shows for a step, plain data with GeoJSON), `SandtableOverlay` / `SandtableLayer` (its GL layers, compile-only), `SandtableMarksLayer` (its marks and words in Compose), `SandtableCamera` / `SandtableFit` / `SandtableFlights` (the map flown to each step). See *The brief on a tablet* and *The sandtable on a tablet* below | setup, review, Share and Save done (B8); the live sandtable done (B9) |
| `feature-viewer3d` | Android library: the 3D view over the whole screen (`Viewer3DHost`, `Viewer3DPage`: a bar with `3D · NAME` and its close button below the status bar, the scene under the system bars, and how the point cloud stands in the web's words, `Viewer3DWords`, a failure in the app's own; it takes every touch and Back, and draws the frame's toasts over itself), over `Viewer3DViewModel` (core-data's `LidarBuilds` for one LZ/PZ at a time, and `TerrainProbe` for *No terrain here*), and the scene once the cloud is in hand (`SceneSession`: the tiles fetched and read off the GL thread, the camera and its flights, the point size; `SceneRenderer`, `SceneSurface` and `SceneGl`, OpenGL ES 3.0, its calls held to the platform binding's own checks in a JVM test (`SceneRendererTest`), what it draws unseen; `SceneControls`, the compass, *LZ*, *Routes* and the point size in Compose; `PointSizeMemory`; `MapboxImagery`, the satellite tiles with the 2D map's token and a disk cache of their own; `RouteGl`, the routes' lines, curtains, dots and labels, drawn as unseen), the routes the map shows (`ShownRoutes`) placed on the ground by core-data's `SceneHeights`. See *The 3D view* below | the page, its states, its ways in (the LZ/PZ panel's *3D*, hosted over both frames by `MapHome`), the point cloud drawn (V4), the ground with its imagery (V5) and the routes (V6) |
| `app` | The application: Hilt, Compose, the manifest and its security settings, and the shell: `Gate`/`gateFor` (what stands between the person and the app), `AppViewModel`, WorkManager sync (`SyncScheduler`, `SyncWorker`), and mission packs run with the app (`packs/`: `PackRuntime`, `NetworkWatcher`, `PackInvites`; *Mission packs run with the app* and *Links from emails*, below). See *The app module* below | the shell, the auth flow, the Google sign-in glue and the map as the root in the redesign's phone frame (`frame/`: `PhoneFrame`, `PanelBar` and the tab rule `pick`, `AccountButton`, `WorkspaceChips`; S2 of `docs/ANDROID_SCREENS_PLAN.md`): the row along the top (the folded search, the workspace, Import and the Library, and the account menu: who is signed in, night mode, the version, sign-out; the owner's decision of 2026-10-13, *The phone's row*, below), the frame hidden by a tap on the map (`FrameState`, *The phone's frame hidden*, below), the panel sheet, and the bar of panels with their counts, the LZ/PZ panel (S3: its pages are `feature-workspace`'s `LzPanel.kt`; the open LZ/PZs are kept by `OpenDiagramPreferences`), the Routes panel (S4: `RoutesPanel.kt` with its foot, and the route's plan as a page over the frame, `RoutePlanPage.kt`; the open route sets are kept by `OpenRouteSetPreferences`), the Threats panel (S5) and the Imports panel (S6, with the Import menu the Import chip and the panel's header open, and the review every file goes through) theirs, and the Library (S7: the Library chip, a page over the frame) its own, and the map's long-press menu (S8: `MapMenu`); on a window at least 840 dp wide and 480 tall the tablet's frame instead (S9: `frame/TabletFrame.kt`, *The tablet*, below); every panel stays composed and only the chosen one is laid out, so each keeps its scroll, pickers, export listener and the dialogs it opens; night mode is `DisplaySettings`, a device preference, with the map dimmed under it; `HomeViewModel` joins the map and the documents (where the map goes, the scene it draws, measuring the slope of an analysed diagram when it is opened, the routes, local points, weather and local threat picture, and the order a tap on the map is tried in, a planning tool's mode taking every tap while it is on, and whether a tap was the frame's); on a tablet the rail's *Brief* opens the brief's setup for an account with `mission_brief` (`feature-brief`; `LastBriefPreferences`, one slot for each account; its PDF handed to the share sheet as `application/pdf` and cleared at sign-out with the exports), and the setup's *Present sandtable* draws the live sandtable over the map where the tablet's frame has it (`TabletFrame`'s `presentation`: the map never moves; the rail, panes and band put away, kept composed); the app also bundles the AMPS mission and threat templates for explicit sharing (`ShareExport`) and reads files other apps open with it (`incoming/`) |
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
  `Number()` is ported whole (`JsNumber.of`): `"0x22"` is 34, `[34.7]` is 34.7,
  `true` 1 and `[]` 0, and a grid is blank or not by JavaScript's trim (a byte
  order mark alone is blank, U+001C is not). A partial port read those targets
  as "not a position" until `packs/shared.json`'s `lzShape` cases caught it.
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
- **Spell ASCII digits `[0-9]` in a regular expression.** On Android `java.util.regex` is ICU's, whose `\d` may take every script's digits where
  JavaScript's and the JVM's take ASCII alone; a JVM test cannot tell. `JsNumber.of` matched Arabic-digit text with `\d` and then threw in
  `toDouble`, which would have ended a brief's compile on a device (found by the brief's review; `JsNumber`, `JsValue.parseFloat` and the route
  planner's clock now spell `[0-9]`, and the brief's own regular expressions do too).
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
- **A pack's live stream** (`openPackLive`, docs/MISSION_PACKS.md §6) is OkHttp's own WebSocket on the calls' connections, with a
  25 s ping so a dead connection is noticed. The token goes in the hello (the first frame), never a header. The service checks access
  again with the last token it was given, so every new one is passed on; the session announces a change (`accessTokens`) and the
  stream reads the store, which a refresh writes first, so an older token never goes out. `accessTokens` is a SharedFlow on purpose:
  a StateFlow drops a sign-out in a process that never announced a token (null, then null); it replays the last announcement, so a
  refresh between reading the hello's token and listening is not missed. A stream belongs to the account it was opened for (`asUser`,
  required): once that account is not the one signed in, it ends (4401 `signed_out`), is not reopened by itself, and nobody else's
  token goes on it. **4403 is not "the pack is gone" here, unlike the web**: the service sends it when the API's `/access` check
  answers 403, which that route gives only for the account (`feature_disabled`, `affiliation_required`), so the client asks the API
  and pauses instead of dropping queued edits. Every other close but 4400 and a gone pack (4404, 4410) is reopened later, as on the
  web. At most 1,000 messages wait for the collector; one more ends it (1013), as the service ends a socket that far behind.
  `PackLiveTest` holds the app's half against MockWebServer's WebSocket; the service itself needs Postgres
  (`tests/test_realtime_live.py`), so the whole chain is not tried here.
- Failures are typed: `NetworkException` (with `requestMayHaveBeenSent`), `SessionEndedException`,
  `AffiliationRequiredException`, `RevisionConflictException` (carries the server's copy),
  `RateLimitedException`, `ApiException`.
- **The stored session is read once per process** (`restore`; after that it returns the state as it is). The shell and a background
  sync both ask, in either order or at once, and a second read would put back what the first found over what has happened since: a
  session the server or the 14-day rule ended, a refresh's newer user. What it read is set only if nothing else set the state while it
  read (a compare-and-set; everything else writes the store first). It takes no lock: a launch must not wait behind a refresh a sync
  has in flight, which can take 20 s on a poor signal.
- **A session the server ended leaves a note in its place** (`SessionStore.end`, `EndedSession`, with the server's `code`): the
  file holds the session or the note, never both, so ending one is one atomic rename. A sync WorkManager runs with the app closed
  may be what finds the session ended, and that process is gone by the time anyone opens the app: the note is how the launch still
  says why ("Your session expired", or the 14 days) instead of showing a plain sign-in. A call that finds no session reads it too,
  so calls refused together do not turn "ended" into "not signed in". Signing in or out removes it.

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
  ignored here, with only a warning). **A row bigger than the 2 MB cursor window cannot be read at all** (`SQLiteBlobTooBigException`;
  Robolectric's native SQLite enforces it too, so a test finds it): a mission-pack item may be 5 MB, a library document as large, a
  mission's file larger. So a query reads such a column only when it is short, with its length beside it, and a longer one is read in
  parts with `substr` in the same transaction (`LongValues`; `RoomPackStore`, and `RoomSyncStore` for a record's document, a send's
  and a file; their tests keep 3–5 MB values). A new query on one of those columns must do the same. And `Json.encodeToString` writes a
  number again (`2.50` comes back `2.5`, an integer past a `Long`'s range rounded); `JsonElement.toString()` keeps each literal's text,
  which the pack store relies on (the library's store still encodes, harmlessly for the values it holds).
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
- **A brief record is the brief document, whole** (`RecordKind.BRIEF`; docs/MISSION_BRIEF.md §2.2, §3). `/api/briefs` takes JSON (`name`, `data`, `client_uuid`), the
  rules of `/api/lz`, and does not look inside the document; the change feed's `brief` carries it as `data`, a conflict's copy as `server.data`. Every route needs the
  `mission_brief` entitlement (off for all but admins and ticked testers until launch). A write without it is refused 403 `feature_disabled` and its entry is
  blocked in the outbox for good (nothing calls `retryBlocked` yet), so nothing makes a brief record for an account without it: the pack keeper keeps a refused
  brief only with it (`LibraryPackKeeper.keepsBriefs`). The live scenarios tick it for their accounts, and the live test server registers the briefs blueprint
  (`backend/tests/live_server.py`; the Android workflow installs OpenCV for it, which the terrain code the blueprint imports needs, and contourpy beside it). Recorded from the real server (`brief: …` in
  `responses.json`, held to `openapi.yaml`) and run through all eleven per-kind scenarios on the fake, the real Flask server and Room. Room keeps a record's kind by its
  name, so the kind needed no migration; a device that synced before it has its cursor past the briefs it holds on the server (none before B8 makes them here). The
  screens are B8; the weather at a planned time (`weather: at …`) is typed with them.
- Built since: WorkManager scheduling (`SyncScheduler`: a sync on demand and every 6 hours; not yet seen on a device),
  conflict choices in the Diagrams, Routes and Points sections (`ConflictPanel`), and the 14-day "sign in again" rule
  (`OfflineGrace`).
- **A scheduled sync can run in a process WorkManager started, with no activity and so no shell** (`EngineSyncRunner`). Only `AppViewModel` used to
  read the stored session, so such a sync found nobody signed in and did nothing. In a process nobody has read it in (`AuthState.Unknown`) the
  runner now does what the shell does at launch: `restore`, then the 14-day rule (`OfflineGrace`) before any call, because a refresh would stamp
  the session as confirmed and the person would never be asked to sign in again. A process already launched is left alone (the rule is applied
  at launch, never to someone in the middle of their work). It syncs only when the device's plans are the signed-in account's
  (`Ownership.Yours`): another account's stay unsent, and an unclaimed device is the shell's to claim.
- **Mission packs run with the app** (`app/packs/`: `PackRuntime` over the engine and `PackWorkspace`, `NetworkWatcher`; `AppViewModel`,
  `EngineSyncRunner`). They run only for a signed-in account past the gate, with `mission_packs` on, whose plans the device holds (`packGateFor`),
  from a collector of its own, because the one that works out ownership never hears an admin turn a feature off or on. Running them joins the
  editors to whichever pack is open (`PackWorkspace.start`); **stopping them, whatever the reason, goes through `PackWorkspace.stop`**, which
  writes and closes whatever of a pack is open in the editors before the engine stops (after, its change could only be kept as the person's
  own, `PackWorkspaceTest`), and leaves the person's own documents open. **Sign-out is not the gate's**: the shell writes and closes
  the open documents first and only then stops packs, so a pack item's last change reaches its queue; the gate leaves a signed-out state alone,
  and also **an owner not known yet**: every launch and every new shell starts there, and each change of session begins by forgetting it, which a
  sign-out's collector can do before the gate's has heard of the sign-out (a stop then came before the documents were written, and a new shell
  stopped packs running for its own account). ON_START brings packs to the front and wakes them; ON_STOP (in `AppRoot`, which is always composed)
  sends them to the back at once, then writes the open diagram *and* set of routes (the routes were never written early before) and asks for a sync
  while pack edits wait, in work the shell cannot cut short (`NonCancellable`): an activity that is finishing clears its view model right after
  ON_STOP. A sync asked for while one runs is not queued again (`KEEP`), and that one leaves the open pack to its own client, so what is left goes
  at the next launch or periodic sync. Every pack call in the shell is guarded: a broken database never ends the app, nor keeps a sign-out's
  wipes from running. Below the server's minimum version (as last heard, `MinimumVersion`) they stop (`packGateFor`'s `tooOld`), without waiting
  for a launch's look at the config (the owner's call); the background sync looks for itself (*The shell's rules*). The background sync drains packs before the library (what a pack refused is kept as a library record, which then goes up in
  the same run): RETRY from packs, or a drain that throws, makes the run RETRY with the library still synced; PAUSED does not.

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
- **`versionName` is the Android app's own**, `ezpz.versionName` in `android/gradle.properties`: 0.x until the store, then
  1.0.0, bumped by hand at each store release (the owner's call, 2026-10-24; it once followed `frontend/package.json`, so a
  web-only release made a "new" Android version). It is what `X-EZPZ-Client` and `MIN_APP_VERSION_ANDROID` compare, so a server's
  minimum must be an Android version, not the product's. `ezpz.apiMajors` beside it is the API majors the build talks (§6).
  `versionCode` is `GITHUB_RUN_NUMBER`, or 1 locally.
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
  server's minimum version is stopped *before* anything else, even sign-in, and **sends nothing while it is** (owner, 2026-10-10: mission packs
  stop, no invitation is accepted, and the library's sync does nothing; what waits stays and goes once the app is updated). Every look at the
  config is remembered first (`MinimumVersion`, one live value in the process) and that is what they all go by: the shell looks at launch and
  each time the app comes to the front, and the background sync looks before it sends (so a device nobody opens hears a raised minimum; the
  config is public and confirms no session). Nothing waits for a launch's look (the owner's call: the first launch after a raise sends until
  its config answers). With no config to show the update screen (no signal), planning opens and a banner says why nothing is sent, with
  *Update* (`Gate.Ready.tooOld`, from the minimum last heard); **maintenance is a banner, never a block**, because planning is
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
  **Invitation links** (`?invite=<token>`, a mission-pack or team invitation; `InviteLinks`, which shares `AuthLinks`' site check) are never the
  sign-in screens': the shell keeps the token in its saved state (through a sign-in, a turn of the phone and the process being ended, as the web
  keeps it for the tab) and accepts it once the gate is `Ready` (signed in, past the gate, the plans here theirs) with Mission Packs on; while
  they are off it waits (`InviteState.Waiting`). Only a token of the server's shape (`^[A-Za-z0-9_-]{16,200}$`) is kept, anything else is
  dropped unsent; it is not accepted while the app may not send (below the server's minimum as last heard: accepting joins a shared pack);
  one request at a time per link however often it is looked at (`InviteAcceptance`: a second accept would be refused as used),
  and only the link still kept is asked about; it is let go once accepted or refused for good, and kept for a retry after no connection, a 429,
  a 5xx or a refusal about the account (signed out, the gate, Mission Packs off: **not like the web**, which drops it, as owner decision 4 keeps
  edits). Opening the kept link again asks again. **What became of a link is the account's own**: the shell outlives a sign-out, where the web's
  hook goes with its screen, so `InviteAcceptance` is for one account at a time (a sign-out `forget`s it, an answer that comes after its account
  has gone is never said, and the accept is made `asUser`); the link itself waits for whoever signs in next, as the web keeps it for the tab
  (the owner's call, 2026-10-10).
  The shell exposes `inviteState`, `inviteAttempt`, `retryInvite` and `dismissInvite`, and **a toast above the sheet says what became of it**
  (`InviteNotices`: accepting, joined, waiting, or failed with *Try again* when it may pass; the web toasts a join or a failure), keyed on the
  attempt too, so a retry that fails as the last one did is told again. The frame puts toasts above the grid readout and
  whatever floats above it, a drawing toolbar or the held object's bar, measured (`PhoneFrame`), whose buttons a toast that stays would otherwise
  cover, and at the foot of the screen when the sheet is at full height; on a small phone with a tall bar up it can reach the crosshair. Opening the pack joined waits for the
  pack screens.
- **Google sign-in** (`CredentialManagerGoogleSignIn`): Credential Manager, offered only when the build has
  `-Pezpz.googleClientId=<web client ID>` (the audience the server lists in `GOOGLE_CLIENT_IDS`); the app's own Android OAuth
  client (package + signing certificate) must exist in the same Google Cloud project. No test reaches it (it needs Play services and
  an account on a device).
- **The MGRS grid** (`core-geo`: `MgrsGrid`, and `MgrsConverter`'s `toUtmInZone`, `fromUtm`, `squareLetters`, `zoneFor`, `bandFor`, `centralMeridian`; `feature-map`:
  `MgrsGridScene`, `MgrsGridLayer` (`MgrsGridOverlay`, `MapChromeBoxes`), the Layers menu's *MGRS grid* switch, `GridMemory`; `app`: `MapPreferences`, `MapHome`). The web's
  grid over the map (§4, *Map layers*): zone and band boundaries, 100 km squares and lines at 10 km, 1 km or 100 m as the zoom allows, labelled. Things to keep true:
  - **One grid, the web's**: `MgrsGrid` is `feature/mgrsGrid/mgrsGrid.js` function by function, held to `contracts/fixtures/mgrs/grid.json` (written by
    `frontend/src/contracts/mgrsGridFixtures.test.js`: every spacing, Norway and Svalbard, the equator, the antimeridian, world copies, covered edges, the caps).
    Change the web first, regenerate, then follow. `MgrsGridTest` allows only what two maths libraries may decide differently in a double's last digit: a polyline
    sampled with another count of points (within a hundredth of a pixel), a line's end found a millimetre off, a label's box to a thousandth of a pixel, and the
    100 km squares labelled in another order where mirrored squares tie (compared as a set).
  - **A grid point is a screen point (dp)**, MapLibre's unit, so the web's 70-pixel line gap and its 11 px pills keep their sizes; the scale is taken at the
    bounds' Mercator middle, as the grid computes it. The pills keep that size at any text size (the grid places them by it); the readout is what grows.
  - **Its view is the map from its top to the sheet** (the camera's bottom padding), its longitudes not folded into 180. What floats over the map (the phone's row
    or a tablet's search bar, reported from the window's top since the map is drawn under the status bar, which is told on its own too; the map's buttons; the readout
    pill; a tool; the navigation bar) reports its box (`chromeBox`), and the labels keep 4 dp off it: so eastings fall to the bottom edge under a phone's row, as on the web. **Turned off
    north** the lines cover the box round the turned view, and the labels come from a north-up square inside the circle the view leaves (any bearing keeps it on
    the screen), with what floats over the map carried into its frame; no line values are labelled along edges, which would not be the screen's: face north to read them.
  - **Nothing the grid asks for may end the app**: `MgrsConverter.bandFor` gives the end band past 80 S or 84 N (a label's text is made before the label is known
    to fit, where the web gets `undefined`), and a projection with no answer is NaN, which places nothing. The GeoJSON is built off the main thread with the grid.
  - **Lines are MapLibre layers directly above the base map**, under every planning graphic (halos, then lines, finest first, zone boundaries white dashes over a
    dark halo: the web's `mgrsGrid.css`); labels are Compose pills, hidden from a screen reader (the readout says the same in words). Worked out again 200 ms after
    the camera settles, off the main thread; the lines cover a quarter of the view round it, and the pills follow the map meanwhile.
  - **Off until turned on, then remembered on the device** (`GridMemory`, the `mgrsGrid` preference), never saved with a plan or synced, as the web's `ezpz.mgrsGrid`.
  - **Not verifiable here:** the lines themselves (GL; the screenshot test draws the same lines with a canvas to look at where the labels sit), and how they read on
    each base map in sunlight.
- **The map** (`feature-map`): MapLibre's zoom is Leaflet's minus one (512-point tiles), so `metersPerPixel` is
  `78271.517 · cos(lat) / 2^zoom`; a saved diagram stores a base map *id*, never a zoom. Mapbox's `@2x` tiles are 512-point,
  1024-pixel images (`tileSize: 512`); the FAA chart is 256-point with native levels 8–12, and the map stretches what it has outside
  that. The public Mapbox token comes from `/api/config` and is **remembered** (`MapPreferences`, a plain preference: it is a public
  `pk.` token, and anything not starting `pk.` is never stored) so a launch with no signal still draws satellite; with none at all only
  the FAA chart is offered and a diagram keeps remembering *satellite* (`chosenStyleId`). **A URL-restricted token may refuse a
  native app** (no web referrer): confirm with the owner. Labels over the map (distances, headings) are meant to be **Compose overlays
  projected from lat/lon**, not map text, because a raster-only style has no glyphs; shapes are MapLibre layers and unit symbols
  bitmaps (`MapLabels` / `GraphicLabelsLayer` do the separation labels, from `MapProjection`). **The crosshair is the middle of the map that shows,
  not of the whole view**: `EzpzMap(padding = MapPadding(top, bottom))` reserves the phone's row (a tablet's search bar) and the sheet with a MapLibre camera padding, so
  `camera.center` — the readout, `MapUiState.center`, where a graphic is put — is the point the crosshair is drawn on. On a phone the map is drawn under the
  whole frame, the bar of panels included, so the bottom is the bar's height and the height the sheet rests at, or is settling at, never more than half's (a full
  sheet slides over the map rather than moving it); with the frame hidden (a tap on the map) the padding is just the system's bars (the status bar at the top, the
  navigation bar at the bottom), so the crosshair marks the middle of the whole map. When the padding changes the map
  eases to the new middle (300 ms) and the crosshair glides with it, so what was under it stays under it, and a fly-to or a turn back to north under way
  is folded into the new padding rather than cut short (`MapHost.setPadding`). `CameraState` carries the camera's own padding as it moves, and `MapProjection`
  reads it from there, so labels stay on their points while it glides. Before the padding the crosshair was drawn half the peek above the
  camera's centre (about 60 m at zoom 17 on a phone), so the grid shown was not the grid under the icon. That MapLibre reports the *padded* centre as the target is its documented behaviour but is **not verifiable here**: check on a
  device that the readout changes as the map pans under the crosshair and a graphic placed lands on it. **What is asked of the map before it is made waits, and is
  done after its first camera** (`MapHost.attach(map, initial)`, since B9): before, the first camera came after and undid it (a flight to an LZ/PZ opened at
  launch, the sandtable's flight to its step after the activity was made again). GPS uses only the platform's `LocationManager` (no Play services, no third party) and nothing about a position is stored or
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
    leaves the analysis standing (`SlopeState.Unavailable`). Like the web, no landing heading is sent to `/api/terrain-analysis` (so
    `directional` is null): the answer's `byHeading` (every whole heading) is what the tile judges, with the landing heading and wind it
    has now (§13, *Slope landing limits*), and it rides with `SlopeState.Ready`, never in the document. The slope
    raster (`terrainData`) is **runtime only**, kept in the service by diagram id, never in the document. A hand-drawn boundary of three
    points or more is promoted to the analysis boundary and the drawn one cleared, as the web does. Error text shown to a person is the
    app's own words: the server's internals (a Python exception on a 500) are never shown.
  - **`advanceUntilIdle` does not run a test's `backgroundScope`.** A service launched in one never moves; give it a scope on the
    test's scheduler (see `AnalysisServiceTest`).
  - **The summary tiles are the web's `MissionSummary`**, whose maths was extracted there into pure exported helpers
    (`lzAreaAndCapacity`; the slope tile's call is `slopeCallFor` in `feature/terrain/slopeCall.js`) so the fixture could pin them. The
    slope tile (`LzSummary.slopeCall`, from `DiagramsViewModel.summaryOf`) takes the landing heading from the flight data (`Doghouses.landingHeading`)
    and the wind from the weather report the open LZ/PZ's tiles show (`LzSummary.slopeWindOf`), and shows its two lines under the value and
    in its spoken description (`slopeSpoken`: the degree and minus signs in words). Capacity is for the mission aircraft
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
    - **An aircraft is drawn to scale, on its point** (`LzScene`, `AircraftIcons.frame`, `IconRaster`, `AircraftIconRenderer.forMap`): the rotor drawn is the profile's
      `rotor_diameter_m` on the ground at every zoom and latitude, as the web's `calculateSizePx` means it to be (`AircraftScaleTest`, and `AircraftMapImageTest` measures
      it in the pixels). The layer's size is `k0 * 2^zoom` times the picture's `AIRCRAFT_ICON_PT` points, `k0` being the picture's width on the ground over the ground a
      point covers at zoom 0. A picture is wider than its rotor, and its frame says by how much (a tandem's rotor is its overall fore-to-aft span, which is what its
      profile gives); the frame is centred on the rotor, because the map centres a picture on the aircraft's point and turns it about that point, where the keep-out
      ring, the separation lines and a tap's reach are measured from. The bitmap is drawn with the screen's pixels for its points (at most 1,024) and its density says
      how many points those are (§14). **Not like the web**, whose sprite is its rotor's diameter across with the disc 92% of it and a little ahead of the point, and
      never under 15 px: here a far zoom draws an aircraft as small as it is.
    - **The aircraft are the original EZ/PZ helicopter, in its colours** (`AircraftIcons.picture`, `AircraftIconRenderer`; the owner, 2026-10-13: the white airframes
      were hard to see): black, with the outlines, the rotor's ring and blades and the hub in `#64F338` on a black edge, which reads over imagery, the charts and the
      topographic map alike, and `#FF3B30` in its place for an aircraft too close to another (`IconState`). **The UH-60 is that helicopter**
      (`frontend/public/icons/helicopter.png`) redrawn to the UH-60L/M's published dimensions as `feature-map/src/main/assets/aircraft/uh60.svg`, in decimetres with the
      mast at 0, 0; its header lists them and `Uh60SvgTest` holds them (rotor 53 ft 8 in, fuselage 50 ft 0¾ in by 7 ft 9 in, stabilator 14 ft 4½ in, the tail loop
      ending where the turning tail rotor does, 64 ft 10 in from the main rotor's tip). AndroidSVG draws it with its green swapped for the state's (`renderSvg`), so
      the file may hold no colour but black and that green. Its frame is its viewBox, 236.6 square round the mast, its rotor 163.6 across: the profile's
      `rotor_diameter_m` scales the whole picture, so 16.36 m draws it at its published size. The outer half of the tail loop's outline is the picture's edge, the
      least margin that keeps every stroke whole (`Uh60SvgTest`; at 231.6 it was cut flat). **The other airframes keep the web's silhouettes**
      (`aircraftIcons.js`) in those colours and at the UH-60's line weights for the size of their rotors; four blades are turned into an X, as the original's
      are, because one up the nose and its twin down the tail would hide the fuselage under lines that bold (an odd count keeps one up the nose, as on the
      web). **Not like the web**, which still draws white silhouettes.
    - The list runs aircraft first, then PZ markers, sectors, go-arounds, doghouses, units, whatever was placed first.
    - **A helicopter has a name** (the owner's request of 2026-10-22; `core-model`: `AircraftName`; `GraphicsViewModel.setAircraftName`; `feature-map`:
      `MapLabels.aircraftNames`): a chalk or tail number, typed in the inspector (*Set name*, and *Remove name* for one it has) or in its held bar (a field taken on Done or
      as it is left, as a doghouse's are, and put on the helicopter it was typed for, `GraphicOf`). Kept in capitals as the aircraft's `label`, at most 20 characters
      (counted as characters, an emoji one), refused in words past that or for anything but a helicopter; blank takes it away as JSON null, as a pack sends a field that
      went; the name it has already (none for none) is no change and no step to undo. The inspector's field is keyed by the helicopter held, so what was typed for one is
      never set on the next, and Go with nothing typed sets nothing. The actions every screen of the graphics is given are built in one place (`graphicsActionsOf`): the
      LZ/PZ panel's plan built its own and left the name out, so *Set name* did nothing in the app while every test of the screen passed (the review's). It comes first
      in the helicopter's title (`CHALK 1 · UH-60L`) and is written under the aircraft on the map, centred just under the whole of its picture (half its frame, as far
      as the tail reaches) whichever way it faces or the map is turned, on a pill that takes no touches (§14). **The web keeps the field** (it copies an aircraft whole:
      `cloneArray`, and `{...helicopter, ...newProps}` for a change) and a pack carries it as any field of a graphic (held on two devices, `LzPackSettingsTest`), but
      **the web neither shows nor sets it yet** (§15).
    - **Doghouses** (`Doghouses`, `DoghouseBox`, the `DOGHOUSE` kind). The web's maths was extracted from `Doghouse.jsx` / `useDoghouses.js` into
      `feature/doghouses/doghouseFields.js` (no behaviour change) and pinned in `contracts/fixtures/workspace/doghouses.json`. Things that are
      easy to get wrong:
      - **The flight data follows the doghouses**, as the web's `useDoghouses` *effect* does (not its reducer, so `ops.json` does not cover it):
        whenever a diagram's doghouses change, `landing_hdg` / `takeoff_hdg` are taken from the first landing / takeoff doghouse (`role`, else the
        older ids `dh1`/`dh2` or labels `[SP1]`/`[RP1]`). `DiagramSession` does it (`Doghouses.settle`) on open, edit, quiet change and update,
        to every version in the undo history, so undo takes the heading back too; a diagram's doghouses that did not change leave a flight data the
        person set by hand alone. Before this, an analysis made the doghouses and left the headings empty. The web's effect keeps the same rule since
        2026-10-13 (`useDoghouses` follows what the doghouses hold and which diagram is on the map, `diagramId`, never a new list of the same ones: §14).
        **In a mission pack the turn and the heading it gives are one change**, sent by the device that turned it as two operations; a device that takes
        someone else's turn sends nothing back, open, closed, polling or on the live stream an event at a time (`LzPackTwoDevicesTest`, *A doghouse turned*).
        Its sentence names the doghouse and the heading it was turned to, so turns made a second apart are separate lines of the history (*The Pack panel*).
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
    - **The inspector is on the LZ/PZ panel's plan page**: a graphic held from the map shows its halo and the held bar, whose options open that panel at
      full height on the open LZ/PZ's plan, where the inspector is brought into view once a graphic is held (`HomeViewModel.heldPanel` picks the panel for
      what is held: a threat's, a route point's, a local point's).
  - **The diagram that was open comes back at launch** (`HomeViewModel`, `LastDiagram`): its id (a uuid, nothing about the place) is kept in a plain preference
    each time a diagram opens, and is *not* forgotten when the session closes at sign-out, so the same account finds it again. The restore waits until the map
    is listening to `opened` (a `SharedFlow` with no replay: an event nobody hears is lost, and it is what takes the map to the diagram and restores its base
    map), does nothing if the person has already opened something, and treats a diagram that is gone (deleted, another account's) or will not open as
    "start from the list". It comes back only if it is still among the open LZ/PZs (`OpenDiagrams`): the last one closed stays closed.
  - **Not done:** moving a corner of a boundary once drawn (draw it again). **Deleting a corner is built** (`BoundaryCorners`, `CornerHitTest`): a tap holds a corner of the analysis or the drawn boundary (after graphics, routes and their points, before local points), the held-object bar offers *Delete corner*, it is one undo step, and a boundary keeps three corners (the button is disabled and says so). Deleting from the analysis boundary makes the slope be measured again. **The slope heat map has a button** (`SlopeVisibility`, `SlopeToggleUi`): it only hides the raster, the slope is still measured and the tiles still say it; it is a view choice, on at each launch and not saved with the diagram (its opacity and the LZ box: *How the slope map and the LZ box are drawn*, below). Neither is seen on a device yet. (Dragging and turning on the map are *Dragging and
    turning on the map*, below; the inspector still does all of it and works with gloves and a screen reader.)
- **The LZ/PZ panel** (S3; `feature-workspace`: `LzPanel.kt`, `SaveLzSheet`, `PlacementToolbar`, `LzNames`; `core-data`: `OpenDiagrams`,
  `GraphicPlacement`; the steps and their amendments in `docs/ANDROID_SCREENS_PLAN.md`). Things to keep true:
  - **The open LZ/PZs are a working set of record ids** (`OpenDiagrams`, a plain preference, at most 20): leaving it deletes nothing, a mission
    pack's item is never in it, and it is not cleared at sign-out, because an id names no place and another account's are never found (as
    `LastDiagram`). Whatever opens an LZ/PZ adds it, at the end; closing or deleting the one on the map opens the one at the end that is still here
    (one being reopened keeps its place, so that is not always the one opened before it). Closing one whose record was deleted elsewhere keeps the
    work under a new id (*The diagrams*) and closes that too, rather than opening it again.
  - ***Save* names an item; it does not save it** (owner decision 1: autosave stays). An LZ/PZ keeps its made-up name, `LZ/PZ n`, until it is
    named; a name the Library already has becomes `NAME 2` (the web's *Keep both*), and the toast says the name it got. **A made-up name is not a
    name**: saving or renaming to one is refused in words at the field (`LzNames.MADE_UP_REFUSED`), because "named" is read from the name and one saved
    as `LZ/PZ 1` would go on offering *Save* (the web keeps that apart in its Library). And **nothing is saved before it is analysed**, named or not, as
    on the web: an LZ/PZ renamed first says *Analyze to save* (a disabled *Save*) until its analysis.
  - **The search bar only moves the map.** An LZ/PZ starts from the panel's *New LZ/PZ at the crosshair*: under autosave a search bar that started
    one would leave a Library record for every grid looked up (a decision the owner may overturn).
  - **A planning tool is a mode** (`GraphicPlacement`): while it is on, every tap on the map places its graphic (`HomeViewModel.mapTapped` asks it
    first), a long press picks nothing up, and the held object's bar hides. What a mode places in a row is one undo step (`DocumentSession.edit`'s
    coalescing key; an edit in between starts another). *Cancel all* takes back **everything** the mode placed as if it had never been placed
    (`DocumentSession.retract`): its steps leave the history, undone or not, and its graphics are taken out of every other version, so neither undo nor
    redo brings one back, while what was done in between stays. (The first version dropped only the last step: an edit between two placements kept the
    first, and an undo left them to redo; the review of S1–S3 proved both.) It holds the `DrawingMode` (`PLACEMENT`), so it never runs beside a boundary
    or a route being drawn, and it ends when another LZ/PZ opens or the open one closes. Each new tool takes the sheet down again (`HomeViewModel.drawingWhat`).
  - **A page of the panel belongs to the LZ/PZ open**: one closed from outside (a sign-out, a mission pack) takes its plan page with it, so the next one
    opened starts from the open LZ/PZs.
  - **The tools are under the open LZ/PZ's card too** (the owner's request of 2026-10-15; `LzToolsSection`, `LzHomeContent(tools)`): the panel's first page has the plan's
    tool grid, with its unit builder and its question before a boundary clears an analysis, so a tool is a tap away whenever the panel shows an LZ/PZ. Each place keeps its
    own builder and question. Not on a tablet while the LZ/PZ's plan stands in its pane beside the list (the plan has them), and not in a read-only pack.
  - **And on the map's right edge** (the owner's request of 2026-10-22: no going into the panel and its plan to add an object; `feature-workspace`: `LzToolRail`,
    `railTools`, `LzToolRailHost`; `feature-map`: `MapScreen(tools)`; `app`: `MapHome`): while the LZ/PZ panel is the one chosen, its tools stand in a column of 48 dp
    buttons on the map's edge, a group of their own apart from the map's buttons (24 dp under them on a phone; on a tablet from under the search bar down to its buttons
    at the foot), each the tool grid's symbol, named for what it does: *Draw LZ* (asking first where it would clear an analysis), the four placing modes, and a unit,
    which is friendly infantry at the crosshair, held, to be changed from its options, as the map menu's *Add object* makes one. Things to keep true:
    - **The grid's rules** (`railTools`, tested without a screen): no rail where no tool could be used (no LZ/PZ open, a read-only pack's); the placing tools and the unit
      off until the LZ/PZ is analysed; *Draw LZ* off while a boundary is drawn; what is off says why to a screen reader. The tool whose mode is on is marked, and
      **tapped again finishes it**, keeping what it placed (the placing bar's *Done*; the grid's tile does nothing then); another starts its own mode.
    - **A refusal is said where the person is** (`LzToolRailContent`, a toast): a placing tool's or the unit's, and *Draw LZ*'s, which `BoundaryViewModel.start` now
      returns, and which the rail puts away from the panel's banner, where, out of sight, it would show later for no reason (*Draw LZ* while a tool's mode is on). The
      question before a boundary clears an analysis is the open LZ/PZ's (`BoundaryUiState.diagramId`) and goes when drawing can no longer start there (a pack finished,
      an analysis begun), never to come back by itself, nor to clear another LZ/PZ's analysis (§17, *A read-only pack*: hidden is not enough).
    - **What fits** (`LzToolRail`, `railFits`, `railSplit`): the column where the room it is given holds all seven (a phone with its sheet lowered); where it does not,
      as many as fit, the last place a *More* button that lists the rest, **never the tool whose mode is on**, which takes the last place shown (ticked in the list
      where only the list fits); nothing where not even one button fits. The room ends 16 dp above the sheet's edge, or the keyboard's top, and above what floats over
      the readout (a held object's bar, a drawing's or a tool's buttons) **only where that reaches the tools' edge**: it is centred across, so the readout alone stands
      beside the column even on a 360 dp phone (14 dp short of it), while a bar the width of the screen cuts it short. What floats is measured as it lays out
      (`onSizeChanged`), so the rail follows a frame late when it changes size. On a tablet it starts below what is said under the search bar (a search refused, how a
      pack stands). It stays when the frame is hidden by a tap on the map, as the map's buttons do; under a sheet at full height it is covered, as the readout is.
    - **Not verifiable here:** the column over the real map and under a glove, and TalkBack on it (each button is named for what it does, the one on is selected).
  - **How the slope map and the LZ box are drawn** (the owner's request of 2026-10-21; `core-data`: `SlopeOpacity`, `SlopeVisibility`, `SlopeOpacityMemory`; `app`:
    `MapPreferences`; `feature-map`: `LzScene.boundaryShown`, `CornerHitTest`, `DiagramOverlay.setSlopeOpacity`; `feature-workspace`: the plan's `AnalysisSection`). Under the
    plan's *Slope map* switch, while the heat map is drawn, an *Opacity* slider (the design's overlay opacity, docs/native-design Imports), and under that the *LZ box* switch,
    which the design's LzPlan and AT-Home put beside *Slope map*, as the web's sidebar does. Not in the Layers menu: that is the map's, the same over every LZ/PZ (base maps,
    the grid), and the LZ box is the open LZ/PZ's own. Things to keep true:
    - **The opacity is this device's, not the diagram's**: a whole percentage of the raster's own alpha, 10 to 100 in steps of 5 (`SlopeOpacity.of`, the web's
      `slopeOpacityOf`: §13, *The slope map's opacity*), kept in a preference (`MapPreferences`, `slopeOpacity`) and the same for every LZ/PZ, never saved with a plan
      or synced, as the web keeps it per browser. It starts at the web's 0.55; Android drew 0.85 before. It is the raster's alone (the contour lines keep theirs), and the map is told it apart from the scene
      (`DiagramLayer(slopeOpacity)`), so a slider being dragged sets one property of the raster layer, at once (no transition), and never adds the bitmap or the vector
      scene again. The slider is said as "Slope map opacity" with its value in words (`stateDescription`): read as a share of its range, 55 would be said as 50 percent.
      It stops at every 5 but draws no tick for each (nineteen dots were noise under the number).
    - **The LZ box is the diagram's own view, `view.showLZOutline`, as on the web** (its *LZ Box*, on by default): a quiet change (`setQuietly`, as the base map is), saved
      with the LZ/PZ so the web and the person's other devices show it as they left it, and never a step to undo; in a Mission Pack each person's own (`PackLz`'s view
      names), so it is offered in a read-only one. Hidden, the analysis boundary's fill, outline and corner dots are not drawn, and a tap does not hold a corner that is not
      drawn (`LzScene.boundaryCornersShown`); while one of its corners is held they are drawn, and in reach, until it is put down. A boundary drawn by hand, and one being
      drawn, are drawn whatever it says, as on the web, and it stays as set through a new analysis (a boundary drawn and then analysed is hidden too while it is off).
      Nothing measured or kept changes: capacity, area and the slope are the boundary's, shown or not.
    - **Not verifiable here:** the raster at each opacity over real imagery and in sunlight, the slider under a glove, and the boundary's layers gone from the GL map.
  - **The open LZ/PZs stay on the map** (the owner's request of 2026-10-21; `core-sync`: `RecordFeed.observe(kind, uuids)`; `core-data`: `OtherDiagrams`, `DiagramsOnMap`,
    `HiddenDiagramStore`, `DiagramRepository.records`; `feature-map`: `OtherLzScene`, `OtherLzOverlay`, `OtherLzHitTest`, `PinLabels.others`; `feature-workspace`: the eye on
    each row, `AlsoOpenUi` in the held bar; `app`: `HomeViewModel.others`, `HiddenDiagramPreferences`). A new LZ/PZ no longer takes the others off the map: every open LZ/PZ
    but the one being worked on is drawn faded under it (the web draws the open one alone: §15), each row of *Also open* (and of a pack's list) has an eye that hides it from
    the map and draws it again, and a tap on one holds it, its bar offering *Open* and *Hide*. Things to keep true:
    - **What is drawn of one**: its target with its name beside it, its boundary (the analysis's as its own view says, and one drawn by hand), and where things stand
      (aircraft to scale and never red, PZ arrows, sectors, go-arounds), at about half strength and under every layer of the open one (its overlay is installed first).
      Not its slope map, separation lines or keep-out rings (alerts are for the one being worked on), nor its doghouses and units, which are Compose drawn one by one
      over the map and would crowd it.
    - **Which are drawn, and the cost**: the person's own open set (`OpenDiagrams`, at most 20) less the one being worked on, from their records as each was left (opening
      another writes the one before). Only their rows are read (`RecordFeed.observe(kind, uuids)`: a Room query of those uuids, so the Library is not read again for it), and
      each is read as a diagram again only when its record changed, off the main thread (`OtherDiagrams`, which passes a list on only when one of them is a new document).
      In a Mission Pack, the pack's other LZ/PZs as the pack has them (one read again only when its data's instance or its name changed, never for a pointer moving), and
      the person's own set aside, as the panels set them aside.
    - **Hidden is this device's own** (`DiagramsOnMap.hidden`, the `hidden_diagrams` preference): ids only (a pack's item by its local id), at most 50, never saved with a
      plan, synced or sent in a pack. The one being worked on is always drawn and has no eye; opening one shows it again (it was looked at), so it stays drawn when the
      next is opened.
    - **A tap on one** (its target, its name, an aircraft anywhere on its rotor, a PZ arrow, a go-around; not inside its boundary or a sector, so a tap there on a phone
      still hides the frame, as inside the open one) holds it, last of all: after the open one's graphics, a threat, a route, a corner and a local point. A long press holds
      it too, and opens no menu. Its bar says where it is open (*Also open*, or *In OP DK*) and its grid, and offers *Open* (it becomes the one worked on: the map goes to
      it, and the LZ/PZ panel shows it) and *Hide*. A tap on it again, or on nothing, puts it down; one hidden or closed while held is not counted as held.
    - **Not verifiable here:** how the faded layers read over imagery and the charts (GL), and the draw cost with twenty open on a device.
  - **Not offered, because Android does not have them yet:** the LZ card export and its capture area, *Sketch a route* from the plan. *3D* is
    *The 3D view*'s, below.
- **The Routes panel** (S4; `feature-workspace`: `RoutesPanel.kt`, `RoutePlanPage.kt`, `SaveRoutesSheet`, `RouteSetNames`, `PlanDates`, over `RoutesViewModel`; `core-data`: `OpenRouteSets`;
  `app`: `OpenRouteSetPreferences`, `MapHome`; the steps and their amendments in `docs/ANDROID_SCREENS_PLAN.md`). Things to keep true:
  - **The open route sets are a working set** (`OpenRouteSets`, kept by `OpenRouteSetPreferences`) under the open LZ/PZs' rules: record ids only, at most 20, never a mission pack's set, not
    cleared at sign-out. One is on the map (the `RouteSession`'s; a card with its routes), the rest *Also open*; *Close* takes one out and opens the one at the end that is still here; a
    record kept under a new id by the save a close or a delete makes (its own was deleted elsewhere) is taken out of the session too, never opened as "the one before".
  - ***Save* names a set of sketched routes; it does not save it** (autosave stays). A set is `SKETCHED ROUTES` (then `… 2`, the web's title) until it is named; a made-up name is refused
    in words at the field; a name another set has becomes `NAME 2`; a set with no route offers no *Save*. The sheet offers the set's only route's name when it has one, as the design
    shows, else the made-up one to type over. An imported mission is named for its file and says *Saved*.
  - ***Sketch a route*** draws into the set on the map when it is a set of sketched routes, otherwise into a new one (`startSketch`; refused in words before any set is made while
    something else has the map's taps). A sketch that ends without a route (cancelled, or dropped as another set opened) takes the set made for it away again, and puts back the set that
    was on the map before it, **but only while that set is still empty and still called by its made-up name**: a set the person named, or one with a route, is never taken away. The marker
    goes with the sketch it was made for.
  - **The panel asks for one thing at a time** (`run`'s `busy`, as the LZ/PZ panel's): a second tap while a set is being made or opened is not a second set.
  - **An export carries the threats only when asked** (`ExportFile.withThreats`), and **what is built is collected by `MapHome`**, not the panel: the plan page can build a file whether or not
    the panel is offered, and a file nobody collects is lost without a word.
  - **The route's plan is a page over the frame** (the design's pushed page), not a Navigation destination: a destination would dispose the map and the panels kept composed (their pickers,
    what they listen for). While it is up the frame is out of the semantics tree, focus is taken off whatever field it had, the page names itself (`paneTitle`), Back closes it (its handler
    is declared after the panels'), and it draws the frame's toasts itself. It belongs to the held route: holding another, or the route going (an undo), closes it, and holding that route
    later does not open it again. Opened from the held bar's options only for an account offered routes.
  - **Laid out for a 360 dp phone and large text**: below 340 dp of row the design's rows (drawn at 412) are stacked (a point's wind on a row of its own, the date on its own row), and past a
    font scale of 1.3 a point's clock goes under its name. The references (AGL/MSL, GS/IAS/TAS) are the design's own segmented control: Material's leaves no room for the words at the
    design's widths. No edited field carries a content description (a screen reader would read it in place of the value).
  - **Not verifiable here:** the keyboard shortening the page on a device (the scroll takes `imePadding` outside it), TalkBack announcing the page, the date picker, and the toasts over the
    real map.
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
    because the sheet's Finish button would be out of reach with the sheet down; starting drawing lowers the sheet to its header.
  - **Drawing cannot start while an analysis of that diagram is running** (it would finish over the new boundary); one running for another diagram does not
    hold it back.
  - **Finish offers the analysis there and then** (the owner's request, 2026-10-21; `BoundaryViewModel.analyzeOffer`, `AnalyzeOfferBar`): the drawing toolbar gives way to a bar
    over the map, *Analyze LZ/PZ*, then the stage in words with *Stop*, then a failure in the app's words with *Try again*; *Not now* puts it away (*Analyze* is still in the panel).
    It is the boundary finished last's (open another LZ/PZ and it is not shown; back, and it is, while that boundary still waits; drawing elsewhere does not end it, finishing
    there does), is spent once an analysis of that LZ/PZ has run and taken the drawn boundary as its own (a later *Re-analyze* in the panel is not its: `Finished.ran`), counts
    the corners there now, starts with an earlier failure of that LZ/PZ's analysis put away, says so in words when another LZ/PZ's analysis is under way (one runs at a time), gives
    way to whatever else floats over the map (the held object's bar, a route drawn, a tool's mode: they would lie over each other), and is never shown in a read-only pack, where
    analysing is refused in words as the panel's is. **A new LZ/PZ is offered its first step the same way** (the owner's request, 2026-10-21; `FreshDiagrams`, noted by the panel's
    `newAt` and `create`, so the map's long press *New LZ/PZ here* and the panel's alike): *Analyze* or *Draw boundary*, and where no area was found *Try again* or *Draw boundary*,
    until it is analysed, a boundary is begun (then *Finish* offers that boundary's analysis), it is put off, or another LZ/PZ is open.
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
      leaves for "no charted elevation"; fixtures compare an absent key and a null as equal. Reading, a null in a route's `elevations` or `plan.perPoint` is none,
      as the web reads it (`RouteSets` takes it out before the types read the route): a mission pack leaves one for every key that went, and read strictly it made
      the whole route unreadable (*Mission packs in the editors*). A type that cannot hold a null where the web writes one is a bug, not a strictness.
    - **`DocumentSession` is the open-document machinery** (the 600 ms pause before a write, undo 100 deep, a record deleted elsewhere kept as a new one,
      a failed save kept owed) that `DiagramSession` used to hold; `DiagramSession` is now a thin subclass that only adds the doghouse-follows-flight-data
      step (`tidy`), and `RouteSession` adds nothing. Its tests are `DiagramSessionTest`'s; `RouteSessionTest` holds only what routes need of it.
    - **A new route takes the first palette colour its set is not using** (`RouteColors`), not the web's session-long counter, which on a phone that is opened
      and closed all day would give two routes one colour as often as not. A route is `ROUTE n` (n counting the set's routes, +1) unless named, and a name is
      trimmed and upper-cased as the web does; its id is `sketch-<millis>-<random>` (the prefix is how the web tells a sketch from an imported route).
    - **Drawing is a draft held outside the set** (`RouteSketching`, like `BoundaryDrawing`): abandoning it leaves no trace, a draft belongs to the set it was
      started on and is refused (and dropped) if another is open when it is finished, and finishing is one undo step. A route is planned for the mission aircraft.
    - **The map draws a route as one line through every point**, a named (AMPS) point as its type's shape, and the shaping points only on the route being worked on
      (`RouteScene`); names are Compose overlays, as the separation labels are, because the style has no glyphs. `RouteOverlay` is GL and compile-only.
    - **A named point's shape is its AMPS type, as the web draws it** (`MsnxRouteLayer`'s `createIcon`; the owner's rule of 2026-10-21: an RP a square, a target a
      triangle, a checkpoint a circle; `PinShape`, `RoutePinImages`): an IP point (the SP and the RP are both IP points, told apart by name) a square, a target a
      triangle pointing up, a turn point (a checkpoint), or a type this version does not know, a circle; a shaping point stays a small dot. Each is a bitmap in the
      route's colour with a white edge over a thin dark one (amber, and larger, while held), 32 points square with the shape's box in its middle as the web centres
      its icons, its density saying how many points it is (§14), made once for each shape, colour and whether it is held; a colour that cannot be read is grey. The
      triangle stays upright however the map turns. The route's plan draws the same shapes beside each point's name (`PointGlyph`). **Not like the web**, which draws
      an imported point whose file gives it no type by the old name rules (a release point a triangle, a target a diamond): here such a point is drawn as it always
      was, as a shaping point, since those rules would draw an RP as a triangle. **Not verifiable here:** the shapes on the GL map (`RoutePinImagesTest` draws the
      bitmaps for real and its contact sheet can be looked at; `route-pins.png` with `-Pezpz.screenshots`).
    - **What a tap on the map does** (`HomeViewModel.mapTapped`, in this order): a boundary being drawn takes it as a corner; a route being drawn takes it as a
      point; a planning graphic is held if the tap is on one; else a route or one of its points (`RouteHitTest`, plain geometry on `MapProjection`: a named
      point within a finger's reach, the nearest wins and a named point beats a shaping point at the same distance; shaping points only on the route being
      worked on, because only those are drawn; else the nearest leg within reach, which picks the route and no point); a tap on nothing puts down the graphic and
      the route's point but keeps the route, so drawing and editing carry on. `RouteSelection` (route and point held) is a singleton the map and the sheet both read, like
      `GraphicSelection`; opening another set, or closing it, clears it and drops a route half drawn (`RouteSketching.dropDraftNotOn`, which leaves a draft begun
      on the set that has just opened).
    - **A boundary and a route are never drawn at once** (`DrawingMode`, shared by `BoundaryDrawing` and `RouteSketching`): both are drawn with taps, so the second to start is
      refused in words ("Finish or cancel the boundary first."), and every way a draft ends (finish, cancel, its document closed) lets go of the mode.
    - **The Routes panel** is *The Routes panel*, below (S4). A new route, once finished, is the one held. Names are upper-cased as the web does.
    - **The set that was open comes back at launch** (`HomeViewModel`, `LastRouteSet`), as the diagram does (`LastDiagram`): its id (a uuid, nothing about the routes) is kept in a plain
      preference each time a set opens and is *not* forgotten when the session closes at sign-out; a set that is gone (deleted, another account's) or will not open is "start from the
      list". Unlike the diagram it does **not** wait for the map to listen: opening a set takes the map nowhere, so there is no event to lose. One opened in the meantime stays. Only a
      set still among the open route sets comes back (`OpenRouteSets`, S4), so the last one closed stays closed.
    - **Signing out closes the open set** as it closes the open diagram (`AppViewModel.closeOpenDocuments`); one that will not save does not keep the other from closing.
  - **The held route's plan** (`feature-workspace`: `RoutePlanPage`, `RoutesViewModel.detail`; `core-planning`: `PlanDraft`, `PointDraft`). As the web's redesigned `RoutePlanSection`: the
    whole route's date, temperature and fuel flow, and a card for each named point (name, type, clock, the altitude, speed and wind flown *to* it, the leg that arrives there and the time since
    the start); the route-wide airspeed, altitude and wind are no longer edited as such (the web's redesign dropped them too: a point that has none of its own takes the route's). Things that
    are easy to get wrong:
    - **What is typed is applied as it is typed, when it is good,** and a refusal is *words for the field* (`applyPlan` and `applyPoint` return them), never the page's banner. **Not like the
      web:** the web's `num` turns an unreadable number into 0 (15 for the temperature) silently; here it is refused with the field named, and a wind direction outside 0–360 is refused too.
      The ranges are the planner's, not the web's. A field left blank shows its value again.
    - **Only what was changed is applied** (`PointDraft.check(before, first)`, `PlanDraft.check(before)`): the altitude and its reference together, the speed and its reference together, the
      wind's direction and speed together, as the web writes them, and the temperature, fuel flow and date each alone, and only what differs from what the field showed. So a plan the web saved
      with no wind gains none when the temperature is typed, and a mission whose date is set does not have its altitudes written back into its file. The first point has no arriving leg, so its
      speed and wind are never read.
    - **One visit to a field is one step to undo** (`rememberVisit`): every keystroke from the field taking focus to leaving it goes under one coalescing key, and the next visit a new one,
      even after the card or the page was shut in between (a counter that starts again with the composition would merge two visits). A reference chosen and a calendar date are steps of
      their own.
    - **The nav log lists only named points and only once there are two** (it is the planner's `points`, empty until then): a route with fewer shows the planner's warning instead.
    - **A field shows a value changed under it** (an undo, a fetched wind) unless what is typed already says the same number (`050` stays as typed): what is typed is never applied to a plan
      it was not typed against, since each keystroke is checked against what the plan is then. **So does the clock's own form**, which is applied only on *Set*: a clock changed under it
      (someone else's in a mission pack, an undo, the clock moved to another point) is shown unless what is typed already says it, and *Set* applies only what differs from the clock the
      point has then. It once kept the clock it opened with, and *Set* put that back over someone else's.
    - **Dates and times are typed on a text keyboard**, and so are altitudes and temperatures: Android's number pads have no hyphen, colon or minus sign.
    - **Winds and ground elevations** (`core-data`: `RoutePlanning`) are *fetched data*: they land in the route they were asked for through `RouteSession.update` (so, like an analysis, they
      are not an undo step and are applied to that route's record even if another set has been opened while the server answered), one fetch at a time. A failure is the app's words,
      never the server's (the 500 text names a Python type). A time is asked for as an instant in the device's zone (`RouteWinds.instantText`); the question and the merge are held to
      `routes/winds.json`, whose times are wall-clock to the millisecond so the fixture does not depend on the zone it was made in (a Date holds milliseconds, and the request carries them).
      **What was fetched for a place lands only on a point still there**: an answer takes seconds, and a point moved meanwhile (here, or by someone else in a mission pack, once this
      device has their move) or taken away gets nothing from it, in each version of the set (an undo step where the point was elsewhere keeps what it had), and the person is told how
      many. Winds too: a wind is the nearest station's for that place, and once it is a plan value nothing marks it as fetched, so one that landed where it was not asked for could
      never be told from one typed there, nor dropped when the point moves on. **Not like the web**, which keeps whatever answers under the point's id.
  - **Moving and adding points from the plan** (`RoutesViewModel`: `nudgePoint`, `pointToCrosshair`, `pointToText`, `addShapingPoint`; `RoutePlanPage`: `PointPosition`). The same controls a held
    graphic has (`NudgeSteps`/`NudgePad`/`EntryRow` are shared with `GraphicsScreen`), because dragging a point in gloves is not a plan: a pad of four arrows with a 10/50/200 ft step, *put at the
    crosshair*, and a grid or coordinate typed in (`PlaceSearch`, so the words for a refusal are the search field's). A named point's controls are folded behind *Move this point*; a held shaping
    point, which exists to be placed, shows them at once. *Add a shaping point at the crosshair* is `SketchOps.insertShaping` (the nearest pair of consecutive drawn points) and holds the new
    point so it can be moved into place. Each move is one undo step ("Move point"), and the position text beside the arrows is the point's grid, computed on the device.
    - **Not like the web:** moving a point **drops the ground elevation fetched for it**. Elevations are keyed by point id and the web leaves a point's old one in place after a drag, so AGL
      altitudes would be measured from the wrong ground; here the point has none until the next fetch (the others keep theirs), and undo brings it back. A *wind* fetched for the point is not
      dropped: winds are saved as plan values with nothing to say they were fetched, so a typed one could not be told apart. Fetch again after moving a point far.
    - The crosshair is `state.center` (the middle of the map above the sheet, §17 *The map*), passed down as `RoutePlanHost(crosshair, crosshairGrid)`; the complaint "Move the map to where the point
      should go first." is cleared when the next one succeeds. **The plan page covers the map**, so the crosshair's grid is written above the buttons that use it ("Crosshair: …"): it is where
      the map was left, and Back shows the map to move it.
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
  other options); a **long press holds it the same way and picks it up**, and the finger moves it until it lifts. Things worth knowing:
  - **The touch is taken from the map, not fought for.** `DragMapView` is MapLibre's view handing every touch to `LongPressTouches` (a gesture detector, apart from the view so a
    test can drive it): a long press is asked about (`HomeViewModel.dragStarted` → `DragHitTest`, the same things in the same order a tap holds); if something is there the map is sent a
    cancel (so it neither pans nor flings) and the rest of that touch is the drag's. A press on nothing opens the map's menu (*The map's long press*, below); while a boundary or a route is
    being drawn (a press is a corner then) it is the map's as it always was. **A double tap is never a long press** (§14).
  - **One drag is one undo step.** `DocumentSession.edit(label, coalesce)` merges edits made with the same key, one after another, into a single step; each says where the thing now *is* (start plus the
    ground distance the finger has travelled, so it does not jump to the finger, and a PZ marker keeps its tip and a sector its shape: `GraphicEdits.nudge`), and one that brings it back to the start
    takes the step away. The document is changed at once, so the map and what depends on it (aircraft separation) follow the finger, and it is **saved once**, after the pause.
  - **A route point forgets the ground elevation fetched for the old place** (as a nudge does). In an imported mission its twin in another route moves with it.
  - **A threat is shown at once but written to its sealed file when the finger lifts** (`ThreatStore.previewMove`/`settle`): that file is rewritten whole for every change.
  - **A long press holds what it is on, as a tap does, and opens nothing else** (the owner's request, 2026-10-21: not the panel, to turn it or type its values): its bar shows at once,
    and a press that does not move leaves it where it was, held: **a finger's roll under the slop (8 dp) moves nothing, and lifting there makes no edit at all** (`MapDrag.abandon`:
    a route point keeps its fetched ground, a threat its terrain mask, no step is left to undo). One of a read-only pack's LZ/PZ or set of routes is held and not picked up (the
    finger moves nothing), so its bar, not the map's menu, answers the press; what can be moved under it (the person's own threat) is picked up first. A press on a unit is tested against where its **picture** is drawn, not only its point: a hostile unit
    stands on the end of a staff under its frame, so the frame a person presses is well away from the point (`UnitFootprints`, reported by the Compose layer that draws each symbol, in pixels). The end of
    a PZ marker's **arrow is its own handle** (a blue ring on the tip): a long press there drags the tip alone, so the marker's reach and bearing change and its anchor stays
    (`DragTarget.PzTip`, `GraphicEdits.setPzTip`); a press anywhere else on the marker moves it whole.
  - **Turning** is the bar's: −15°, −1°, +1°, +15° for what turns (an aircraft, a go-around, a doghouse, a PZ marker); the sheet's inspector has the typed heading. **A doghouse's fields are
    in its bar** (the owner's request, 2026-10-21): label, heading, time, distance and airspeed, taken on the keyboard's Done or as a field is left, as the inspector takes them, a refusal said
    under them (its own words, put away when that field is done); a field follows a value changed under it (an undo, a turn from the bar's buttons, someone else's change) until the
    person types in it, even with the keyboard up, and only what was typed is ever taken, never what the field showed when it was entered (§14, a form that keeps what it opened with). What a field takes is put on the doghouse **it was typed for** (`GraphicOf`: its LZ/PZ and graphic), because a
    field is left as another graphic is held or another LZ/PZ opened, and the standard doghouses have the same ids on every LZ/PZ: written to whatever was held then, it would land on the
    wrong one. **What floats over the map stands on the keyboard's top** where that is higher than the sheet's edge (`standingBottom` in `MapScreen`), so a field of the bar is never under
    the keyboard, and is **measured into the room under the row** (a tablet's search bar; the status bar alone with the frame hidden), the bar scrolling before the readout pill
    gives way: a doghouse's bar over a sheet at half would otherwise reach above the screen, its *Done* with it. Local points are imported data and
    are not dragged. Tried on an emulator against a real mission: a long press and a drag moved a route point (its grid changed, the map did not pan) and the move reached the file on the server. A
    boundary's corners are still not draggable.
  - **A route point's type is in its bar** (the owner's requests of 2026-10-21 and 2026-10-22; `feature-workspace`: `PointRole`, `PointRoles`, `PointTypeUi`, `PointTypeChips`,
    `RoutesViewModel.setPointRole`): a held point's bar has a chip for each of *Checkpoint*, *SP*, *RP*, *TGT* and *Shaping*, each with its symbol, the one it is selected (an IP
    point named neither SP nor RP has none selected, and the bar says *an IP point*), and a tap on another makes it that there and then. No dialog: the owner's of 2026-10-22,
    in place of the first version's dialog of the five. A screen reader hears them as one choice of five (`Role.RadioButton` over the filter chip's check box), each with
    what it does to the point's name (`SP: Start point, named .SP`), which the dialog said and a tap now changes at once. Things to keep true:
    - **The route's plan's rules, and no others**: `SketchOps.designate`, held to `routes/sketch.json`, in one undo step; what it is already changes nothing. The SP and the
      RP are both AMPS IP points, told apart by nothing but their names, so they take theirs (`.SP`, `.RP`); a checkpoint or the target keeps a name of the person's own (a
      place's, a local point's) and takes its own (`.CP`, `.TGT`) in place of none, or of a name that only said what the point was (`.SP`, `.LZ`, `.CP3`...; a checkpoint
      keeps a checkpoint's). **Not like the web**, whose right-click menu offers the IP type once and asks for the name (§15).
    - **Refused in words, where the person is**: a mission's points keep the types their file gives them (the chips are off, the reason under them); one of a route's last
      two named points cannot only bend the line (*Shaping* is off, the reason under the chips); a read-only pack's cannot be changed (the chips are off, the bar's foot says
      why), and a choice refused as the pack is finished is said under the chips. The chips are keyed by the point held, so a refusal said for one is never shown for another.
    - **Not verifiable here:** the chips on a device and under a glove, and TalkBack reading them.
- **Mission packs in the editors** (`core-data`: `PackItemStore`, `PackEditorSync`, `PackWorkspace`, `DocumentSession.document`/`owe`/`closeIfOpen`; docs/MISSION_PACKS.md §5a and
  `docs/ANDROID_MISSION_PACKS_PLAN.md`, *Joining the editors to a pack*). A pack's LZ/PZ opens in the one `DiagramSession` and its route set in the one `RouteSession`, under `pack:<pack>:<item>`
  (`PackRef`), so every editor (graphics, drag, boundary, sketching, analysis, route planning) works on it unchanged; the decisions are `PackItemSync`'s, the web's `usePackItemSync`. Things worth keeping true:
  - **The sessions send a pack id to the pack's store, never the library's** (`PackRoutedStore`): the library's save of an id it does not hold would keep the pack's work as a new record of this
    person's, and closing a pack's set must not let go of an open mission's file (`RouteRepository.release`). `DataModule` hands each session its
    `PackItemStore` (`lzItems`, `routeItems`, on `Dispatchers.Main.immediate`); a session built with none (the tests') opens nothing for a pack id.
  - **A change is measured from nothing newer than its document.** A document remembers the version of the item it was made from: its first look settles on that version (someone else's change
    that arrived while it opened is then taken, never undone), and so does a save made before that, or of an item that is not open (an analysis that came back once another LZ/PZ was
    opened, `DocumentSession.update`). After that a save sends the change since the editor's baseline (`PackItemSync.flushPlan`), with one sentence; the session's 600 ms pause stands for the
    web's 400, so a drag is one change. It gives back the same document, so nothing of the library's (a new identity, `follow`) ever happens to a pack's item, and it runs whole
    (`NonCancellable`) whatever cancels the session's save meanwhile (a flush, a later edit): cut off between the engine taking its operations and the baseline moving on, it would send them
    again. Each person's own fields (an LZ/PZ's view, the routes they hid) go to `PackStore.putOwn` and are never sent, and only while the pack is open with the item.
  - **Someone else's change goes in through `setQuietly`, with the baseline moved on first** (`PackItemSync.takeTheirs`): it is laid over every undo step, so undo takes back only this person's own
    steps, and the save the quiet change brings finds nothing to send back. If it cannot be laid over, the baseline goes back (and `setQuietly` changes no step), or the next save would undo
    their change. A change waiting here goes first (the session is flushed), and the look after that save takes theirs.
  - **`PackEditorSync` looks** when the open pack's state changes, when another document is opened, when a save is over and when whose packs they are changes; never per drag frame; on the main
    thread, one look at a time, never at a document being saved. Headings an LZ/PZ's doghouses give that its item lacks are owed (`DocumentSession.owe`) and sent, as the web's doghouse
    effect sends them. It compares nothing while the pack's client reads the device's copy (a client replaced after a pause shows no items: not a removal) or while the pack is paused.
  - **A change the pack will never take is put back** (read-only, malformed: `PackItemSync.putBack`, every version) and the person told (`PackNotice.Refused`); what came with opening a finished
    pack's item (doghouse headings) is put back without a word. **One it cannot take now is held back, never put back** (owner decision 4): paused for the account's sake, the client loading, the
    pack closed or gone, the item removed. It stays in the document and goes when it can; if the document leaves the editor first (closed, replaced, or closed by the editor because the
    item was removed or its pack is gone or no longer the open one), its last version is kept in the library as "NAME (my edits)" (`PackNotice.ItemRemoved` or `PackNotice.Kept`; **not like the
    web**, which loses it). Only into its owner's library: while nobody's packs are on it waits, and once another account's are it is dropped. So the app must close both sessions before
    `PackEngine.disable()` for any reason (sign-out, Mission Packs off, the gate): then the change reaches the engine's queue for the same account; after it, it can only be kept once the
    account is back. The editors' notices come with the engine's in `PackWorkspace.notices`.
  - **What this person has open is said again when the pack's status changes**, so a client that replaced the one told (after a pause) hears it.
  - **A route set's route is never written twice, and a point's place goes whole** (the owner's first two-device run, 2026-10-13: a checkpoint moved on one phone did not move on the other, or
    showed elsewhere, and the web then drew the route twice, the RP where each person had put it). What happened: a key that went is sent as null (no operation deletes one), so a point moved
    off its fetched ground left `elevations: {"pt": null}` (a clock moved to another point does the same in `plan.perPoint`); `RouteSets` could not read a route with a null there, so the
    editor's next look took its own copy of the route for a new one, the insert met the route already there, and `RoutePackKind` sent the set's routes whole with it twice. From then on
    an operation addressed by the route's id reached the first copy, which this device could not read, so the other device's moves never showed here. Now: `RouteSets` reads a null
    ground or per-point entry as none, as the web does; `RoutePackKind.docOf` measures a route the types cannot read as the pack has it when the editor still holds a version of it, and
    sends a set whose routes share an id whole, so a change lands on the copy the editor shows and no copy is added; and a point moved one way only (each arrow of the nudge pad
    changes one coordinate) is sent with both coordinates (`PackKind.keepWhole`, also applied to someone else's change laid over older undo steps), because each field of a patch
    stands alone in the pack and a move north here plus a typed grid there would leave the point at half of each. The web drags points, which changes both, so it needs none of this.
    Every move goes through one function (`RouteSet.withPointMoved`: the Routes panel's nudge, crosshair and typed grid, and `MapDrag`). **A set already holding a route twice** shows
    both copies, as the web does; deleting the route (either platform) takes every copy, and drawing it again repairs the set.
  - **An LZ/PZ graphic's place goes whole too, and so does the target** (`LzPackKind.keepWhole`; both kinds keep their values whole through `keptWhole` over a list of `WholeValue`s): an
    aircraft, a go-around, a doghouse or a unit nudged one way only is sent with both coordinates, the target with its grid, both ways as for a route point. **A PZ marker's anchor and
    tip are one value, not two**: its tip is a place, put where its reach and bearing from the anchor say, so a turn or a reach (the tip alone) and a move (anchor and tip) made at once by
    two people stand as one person's arrow, the later; kept apart they would leave an arrow from the moved anchor to a tip set for the old one, a length and direction nobody gave it. A
    sector's corners and a boundary's are lists without ids, which `PackDiff` sends whole already. The web drags graphics, which changes both coordinates, and turns a PZ marker by its tip
    alone (`LzPackTwoDevicesTest`, `PackLzWholeTest`).
  - **What an analysis found goes whole** (`LzPackKind.analysisWhole`): its boundary, results, grid elevation and position in words, in one patch of the analysis. `PackDiff`
    sends only the fields that changed (and follows the results into their object), so two analyses at once, of two boundaries, left the later one's boundary with whatever of the
    earlier one's results it happened not to change. A corner deleted from that boundary sends the rest of what was found with it; a boundary drawn by hand (`customLZ`) goes alone.
  - **Every setting of an LZ/PZ is held on two devices** (`LzPackSettingsTest`, the owner's rule of 2026-10-13: every setting of a planning graphic or a route syncs on change, and
    only each person's view stays theirs): each graphic placed, moved (nudge, crosshair, typed grid, drag), turned and deleted, a PZ marker's reach and tip, a sector's shape, a
    go-around's side, a doghouse's label, time, distance, airspeed and heading (and the flight data it gives), a unit's symbol and labels, an aircraft's airframe, the target, a
    boundary drawn and a corner deleted, an analysis come back (a quiet change, sent though another LZ/PZ is open by then), the name: each found identical on the other device's open
    LZ/PZ and in the pack, on its copy while it is closed and once opened there, undone as a change of its own, and made by two people at once (two settings of one graphic both stand;
    one setting stands whole as the later made it). What stays each person's: the base map, the LZ outline and the slope map (an LZ/PZ's `view`), and on Android the slope switch
    and its opacity, device choices. **An aircraft carries only its airframe's slug** (`profileId`), so one placed as a person's own airframe is drawn and measured as each device's mission aircraft
    where that profile is not (§15).
  - **A route's plan values go whole, and a point's first values go apart** (`RoutePackKind.keepWhole`): each altitude (its number and what it is measured from), speed (its number
    and kind) and wind (its direction and speed), the route's and each point's, is sent whole, so 500 typed here and MSL chosen there never make 500 MSL; and a point given its first
    values of its own (an entry of `plan.perPoint` it did not have) gets each as a `set` of its own (`pointValuesApart`), which makes the entry where the pack has none and leaves the rest
    of one it has: sent as one object, the later of two people's first values for one point (an altitude here, a wind there) replaced the other's. **Not like the web**, whose diff
    sends only the part of a value that changed and a new entry whole (§15). Every route setting is held on two devices as the LZ/PZ's are (`RoutePackSettingsTest`): a route drawn
    (its colour with it), points added, put on the line, renamed (and onto a local point), designated and re-designated, moved every way, the date, temperature and fuel flow, a point's
    altitude, speed and wind, the clock set, moved and cleared, winds and ground fetched (quiet, and for a set no longer open), a route's name and deletion, the set's name. What stays
    each person's: the routes they hid. `PackScenarioBook` holds a point's first values and its altitude changed in part on the fake, in Room and on the real Flask server.
  - **What the web writes for each setting is pinned** (`contracts/fixtures/packs/settings.json`, made by the web's own hooks and sketch operations as `App.js` and
    `useRouteSketch` apply them): Android reads each item before and after the setting and writes it back unchanged, finds the web's operations for it, and what it sends
    instead rebuilds the same item (`PackSettingsFixtureTest`, which also names the settings where Android sends more than the web: a PZ marker's tip, part of a plan value,
    a point's first values).
  - `PackWorkspace` opens and closes packs (closing whatever of the pack being left is open in the editors first, so its last change reaches it while it still takes it, and both editors
    and the switch even when a save fails, which it then throws), opens an item in its editor, and makes a new LZ/PZ or route set in the open pack (`createLz`, `createRouteSet`: the web's names,
    `LZ/PZ n` and `ROUTES n`, and creation sentences).
  - **Tests** (`PackEditorRig`): a real engine over the fake pack server and the device's copy in memory, on one test dispatcher. A runaway editor and pack go on at one virtual moment for ever,
    which would hang the build rather than fail it (runTest's timeout cannot fire on a busy thread), so the rig refuses batches and copy writes past a number, and each test class has a
    JUnit timeout rule as well. `rig.other(COLIN)` is a second device on the same server and clock, with its own engine, copy and sessions (`RoutePackTwoDevicesTest`,
    `LzPackTwoDevicesTest`); a move between devices is checked on the other device's open document and in the server's data, never only in what was sent. `PackScenarioBook`'s route and
    aircraft scenarios do the same through `PackRouteEditor` and `PackLzEditor` (the editor's decisions without the app) on the fake, in Room and on the real Flask server.
  - **Not verifiable here:** taking someone else's change patches up to 100 undo steps on the main thread (each a `docOf` and a `withShared`): measure it on a device with a large LZ/PZ.
- **Mission packs on Android, as a whole** (A1–A14; the design, the owner's decisions and each step in `docs/ANDROID_MISSION_PACKS_PLAN.md`; the server
  and the web in `docs/MISSION_PACKS.md`). Built, held to the web's fixtures, each step reviewed independently; the workspace switcher (P1, *The workspace switcher*) opens a
  pack, and its panels are next.
  - **Where it is.** `core-network`: a typed call for every pack, team, invitation and people-search route, three raw JSON calls for the engine, and
    the live stream (`PackEndpoints`, `PackLive`). `core-missionpacks`: the web's `feature/missionPacks` ported file by file (operations, diff, session,
    each person's own fields, sentences, edits), `PackClient` (one pack in step with the server), `PackEngine` (every pack the device works on) and
    `InviteAcceptance`. `core-data`: the device's copy (`RoomPackStore`, four tables in database version 3), the library's keeper of refused edits
    (`LibraryPackKeeper`), and the editors' join (`PackItemStore`, `PackEditorSync`, `PackWorkspace`). `app`: running them with the app (`PackRuntime`,
    the gate `packGateFor`, `NetworkWatcher`, the background drain in `EngineSyncRunner`), invitation links (`InviteLinks`, `PackInvites`) and their
    toast (`InviteNotices`). Each is described where it is listed above.
  - **The rules that bit**, each now in a test: an item is a JSON tree end to end and never the server's types written out again (null kept apart
    from absent, every object's keys in JavaScript's order, a number's literal text kept: `JsonElement.toString()`, never `encodeToString`); an item
    no event touched keeps its instance, because the store and the editors tell what changed by instance and an item can be megabytes; a row past
    Android's 2 MB cursor window is read in parts (`LongValues`); every write is on the device before anyone sees it, and a batch is written as sent
    before it goes; one client per pack, ever, so a pack has one batch in flight whichever holds it; a call made for one account is never sent under
    another's session (`asUser`, the engine's calls and the invitation accept alike); and a queued test dispatcher hides what the main thread does
    (§14), which is how four of A13's ordering bugs got past its tests.
  - **Not like the web, on purpose** (the owner's decisions; each has a test, and the web's side is listed in §15): a refusal about the account (401,
    `other_account`, `affiliation_required`, `feature_disabled`, and the live stream's 4403, which the server sends only for those) pauses with every
    edit kept, where the web drops them as the pack gone; a 403 or 404 while loading or catching up drops what waits as gone, kept in the library; what
    a pack refused is kept as `NAME (my edits)` whenever the pack is not open; a 429's Retry-After holds every send; a sign-out keeps unsent edits for
    the same account's next sign-in, never sent as anyone else; the `webBug` cases are not copied (only own fields read, fields a newer web wrote kept,
    no cut through an emoji); what became of an invitation is the account's own; and below the server's minimum version nothing is sent.
  - **Not verifiable here:** the live service end to end (it needs Postgres: `backend/tests/test_realtime_live.py`, which the Kotlin build does not run;
    `PackLiveTest` holds the app's half), WorkManager's real scheduling and a drain in a process it started, how the toast sits over the real map, and
    how long taking someone else's change takes over 100 undo steps of a large LZ/PZ on a phone. Each wants a device run.
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
  - **The sets are listed in the Imports panel** (S6, *Import* below): each with its colour, count and where it is kept (*Library*, or *This session* for a set held in memory only), hide/show,
    and under *More* Rename (blank refused in words at the field), Colour (the route palette; anything else is ignored, since a colour reaches the map as a style value), and *Delete* (asked first)
    or, for a session's set, *Save to Library* and *Remove from this session*. Conflict copies are settled with the same three choices as a diagram. **A file comes through the system's picker**
    (`OpenMultipleDocuments`, `*/*`: an `.LPS` has no media type of its own and the reader says what a file is), so there is no storage permission; `PickedFile` reads it bounded (32 MB), names it
    from the picker's display name, and **catches anything** a provider throws, because a provider is someone else's code and reading a chosen file must not end the app.
  - **A held local point** shows what the file said of it (grid, degrees, elevation in whole feet with a comma, group, description; none of what the file left out) and offers two things for the route being
    worked on: *Add to <route>* appends it as a named `turn` point with its charted elevation and holds it (the held route, else the only one; with two and none held it offers nothing rather than guess), and
    *Use for <route>: <point>* moves the held route point onto it, name and elevation too, exactly as typing its name does. Each is one undo step. The card is at the top of the Imports panel, which the held bar's options open.
    A point of a session's set is held and used like any.
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
  `ThreatScene`, `ThreatOverlay`, `ThreatLabels`, `ThreatHitTest`; `feature-workspace`: `ThreatsViewModel`, `ThreatsScreen`; `app`: `HomeViewModel`, `AppViewModel`). **On the device only, for
  now:** threats are not yet synced or saved to the server (the owner's decision of 2026-10-12 lets them be, as step *T*, §2). The owner approved sending threat coordinates transiently to the
  existing backend only when the person explicitly asks for an online viewshed, KMZ or QR flow; those calls must not retain the request or response as a threat record. Android's one threat call is
  the terrain mask, below.
  - **Memory first, one sealed file behind it** (`ThreatStore`, `ThreatVault`): the picture is a `StateFlow` the screens and the map read, also kept in `noBackupFilesDir/threats.bin`, so a phone
    that ends a backgrounded app does not lose a crew's picture mid-plan, sealed by its own Keystore key (`EncryptedThreatVault`, the session store's pattern: written whole through a temporary
    file and renamed, anything unreadable is removed and is no picture), and **wiped at sign-out**. **It no longer expires** (the owner, 2026-10-12: the 48-hour wipe went in S5), so a picture
    from any earlier launch comes back. `ThreatStore.wipes` counts wipes, so what was being done to the picture (a form open, a threat held) is let go by a sign-out or *Remove all*.
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
  - **The Threats panel** (S5, the design's Threats) is the editor for that store: where threats are kept, said first; *Add threat* at the crosshair and *Import .ths* (the picker, straight into the import review: *Import*, below);
    the threats on the map, a row each (its symbol, name, reach as `Detect 25 nm · Engage 15 nm`, its terrain mask as a chip, then hide, edit and remove); *.ths for AMPS*; and *Remove all threats*.
    Removing one or all is asked about in the design's dialog (`AlertDialog`, Cancel and the choice both plain). **The form is a full-screen dialog** (`ThreatFormDialog` / `ThreatFormContent`):
    the symbol (a common one or a code typed), name, source, information, where it is (folded behind *Move*: the pad of arrows with the 10/50/200 ft step, `NudgeSteps`/`NudgePad`/`EntryRow` shared
    with `GraphicsScreen`, *Put at the crosshair* with the crosshair's grid written above it since the form covers the map, and a typed grid or coordinate), each radar (range, antenna and AGL/MSL,
    the range ring, the mask flag and the three altitude bands), the terrain mask of a threat already on the map, and *Remove this threat*. The held bar's options for a threat open it. Things that
    are easy to get wrong:
    - **Where the threat is moves with the form, not the map**, until Save (`ThreatEditUi.at`, `formNudge` / `formToCrosshair` / `formToText`): Close leaves the threat where it was. Close and
      Back ask first when anything was typed or moved.
    - **A refusal is words under the form's bar**, in sight however far it is scrolled (`ThreatEditUi.error`, cleared by the next keystroke). A threat that was removed (another screen, a drag
      elsewhere) while its form was open closes the form and says "That threat is no longer here." in a toast rather than saving it back.
    - **What the form did is said in a toast** (`ThreatsViewModel.notices`, shown by the frame), not the panel's banner: opened from the held bar it sits over another panel.
    - **A hidden threat is never held** (its row is not a target, and Edit and Save do not hold it): the map does not draw it and the held bar would not show it, while the bar's options would
      still find it (`HomeViewModel.heldPanel` goes in the bar's own order and asks for a threat that is drawn).
    - **A wipe lets go of the form**: a sign-out (or *Remove all*) clears it and the selection, so a form left open never comes back over the next account's map with its threat's name and notes.
    - **A threat's ring is built from the geodesic circle** (`ThreatScene.circle`: 96 points, starting north and going clockwise, distances correct on four continents), with **continuous
      longitudes** so a ring that crosses the antimeridian is one line, not a line across the world. Until its symbol has rendered, is refused or the JavaScript sandbox is not there, a marker is a
      diamond in the affiliation's colour (`null`, `Invalid` and `Unavailable` all draw it), so a threat is never blank.
    - **Sharing a mission offers its threats.** `ThreatsViewModel.shareWithThreats` is what an AMPS export calls when the set card's box (*Include threats (n) as a .ths file*, ticked until the
      person clears it) asked for them (`ExportFile.withThreats`; a GPX or FPL never takes them): no threats means the mission alone; any threats means the `.msnx` and a `.ths`
      named for it (`MISSION 1.msnx` + `MISSION 1.ths`) in one `ACTION_SEND_MULTIPLE` (`ShareExport.prepare` takes a list; one file is a plain `ACTION_SEND`), as AMPS needs them side by side. If the
      `.ths` cannot be built the mission is shared alone with a banner saying so: a threat problem never costs the mission. The Threats panel's own share is the `.ths` alone.
    - **A shared `.ths` is cleared sooner than a mission**: files in the export cache live a day, a `.ths` an hour (`ShareExport`), and `ExportCleaner.clear` (run at sign-out, `AppViewModel`) removes
      them all, so a crew's threats do not stay in the cache for the next account.
  - **On the map**, every visible entry gets its local MIL-STD symbol and label. Detection is a dashed amber geodesic ring, engagement a solid red one; each ring is omitted when its radar says not
    to show it. A tap is tried in this order (`HomeViewModel.mapTapped`): a boundary being drawn, a route being drawn, a planning graphic, **a threat**, a route or its point, a corner of the boundary, a local point, an open LZ/PZ not being worked on (*The open LZ/PZs stay on the map*). A threat
    beats a route or local point under the finger because it is the thing a crew least wants to miss; tapping a threat releases a held route point and local point (the route stays held), and
    tapping the held threat, or nothing, puts it down. `ThreatSelection` is view state and is never retained or exported. Not verifiable here: the rings (`ThreatOverlay` is GL and compile-only).
  - **The window is not kept out of screenshots** (the owner, 2026-10-12): `ThreatWindowGuard` and its `FLAG_SECURE` went in S5.
  - **An account without threats has none on the map**: its Threats panel is not offered (as on the web), so a picture kept on the device from before (until sign-out) is neither drawn, held nor
    counted (`HomeViewModel.allowThreats`) and is kept for if they are turned on again; a `.ths` file is refused in words (`IncomingViewModel.allowThreats`), as the web greys it out of Import.
  - **The terrain mask** (`core-network`: `threatMask`; `core-data`: `ThreatMasks`; `feature-map`: `ThreatScene.masks`, `ThreatOverlay`; `feature-workspace`: `TerrainMaskControls`): where a threat's
    radars can see over the terrain, as on the web, from `POST /api/threat-mask` (described in `contracts/openapi.yaml` and recorded from the real route). **It is the one thing that sends a threat
    anywhere, and only when the person presses *Show terrain mask* in a threat's form**: the position and the radars (nothing else: not the name or the notes) go up once for that answer and
    the server keeps nothing. Nothing asks by itself: not when a threat is made, moved, edited or at launch, and the form says what is sent beside the button; it is worked out for the threat as Save has kept it (the form says to save first). A mask is a picture held **in memory
    only** (not in the sealed file), gone when the threat is removed or at sign-out; a threat moved or whose radars were changed after its mask was made has a mask that is **out of date and not
    drawn** (`ThreatMask.isFor`), and the form says so and offers *Update mask*; the row's chip says where the mask is (*Terrain mask on*, *Rings only*, a radar set to
    rings only being *Rings only* rather than a failure, as on the web). A failure is the app's own words (the server's text, which can be a Python exception's, is never shown). The
    server returns a coloured PNG per radar over one box, the bands' colours and transparency already in it; the app lays it under the range rings. An on-device viewshed (offline) is
    not built.
  - **Not built yet:** KMZ and QR (so the design's *Send KMZ to ATAK* is not offered), an on-device (offline) terrain mask, and the Threats panel's own sharing beside a *saved* mission (the Routes export offers the matching `.ths` for a sketch,
    not for an imported mission).
- **Files from other apps** (`core-formats`: `FileKinds`; `core-data`: `IncomingFiles`, `FileInspector`; `feature-workspace`: `IncomingViewModel`, `ImportReview`;
  `app`: `incoming/IncomingIntents`, `IncomingIntake`, `MainActivity`). An `.LPS` or `.ths` opened with the app from Files or a mail, or shared to it, is read, shown to the person, and
  imported only if they say yes. The manifest declares VIEW (content scheme) and SEND / SEND_MULTIPLE for `application/octet-stream`, `application/x-sqlite3` and `application/vnd.sqlite3`
  and **nothing broader**: these formats have no media type of their own, and `*/*` would offer the app for every file on the phone (`IncomingManifestTest` holds the filters to that).
  Things that are easy to get wrong:
  - **The intent is hostile input.** Any app can start the activity with any address, so only a `content://` address is read: a `file://` one could name the app's own private files, and
    the app's own `FileProvider` authority is never read back in. At most 5 files are taken, each at most 32 MB, and everything refused is *counted and told to the person* (a file that
    vanishes without a word looks like a bug). An emailed link is also a VIEW, with an https address: it is the auth flow's (`AuthLinks`), neither taken nor refused here.
  - **Read at once, while the grant lasts.** The sender's permission to read a content address ends with the task that was given it, so `MainActivity` reads in `onCreate`/`onNewIntent`
    (`IncomingIntake`, off the main thread) and holds the bytes; the intent is then replaced by a plain launch so recreating the activity does not offer the file again. An intent a task is
    started again with from the recent apps (flagged `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`: the task's first intent, after Back on Android 10 and 11 or a reboot) is not read at all
    (`isNews`), for a file or a link: it would offer the file again, or accept an invitation a second time and be told it was used.
  - **Nothing is imported on arrival.** The bytes wait in `IncomingFiles` (memory only, bounded to 8 entries and 64 MB, cleared at sign-out like the threat picture and the shared exports),
    `FileInspector` says what each is without importing it, and the import review (*Import*, below) lists every file waiting with where it goes; nothing is brought in until the person presses
    *Import*. Back and the close button bring nothing in. Every way in (another app, Import, a panel's own *Import .msnx* / *.ths*) goes through the same review and the same code, so a file means
    the same however it arrived.
  - **What a file is comes from what is in it** (`FileKinds`): the zip header is a mission, a SQLite file is asked for its `THREATS` / `Points` tables; the extension decides only when the
    content cannot (a file cut short), so the right reader refuses it in its own words. A database with both tables goes by its name. A damaged file is never an exception.
  - **A mission is brought in as a copy** (`FilePreview.Mission`, `MissionRoutes`, `MissionImporter`): *Bring in 2 routes?* makes a **new set of routes** (sketched routes, saved and synced like any set
    the person draws) from the mission's routes, with their points, the plan AMPS recorded and the ground elevations, opens it, and takes the map to it (`MapFocus` → `MapViewModel.showArea`, at the
    zoom `MapZoom.fitting` works out for the routes' extent). **The file is kept as the saved document** and the review says so (*Saved missions*, below): the mission is its file, exactly as on the web. `.msnx` arrives as octet-stream
    from most senders, so it reaches the review by the same filters as the other two.
  - **Not verifiable here:** that a real Files app, Gmail or the share sheet offers the app and passes a readable address, how the review looks on a device, and that
    `application/octet-stream` is what AMPS-adjacent senders actually label these files. If a sender uses another type the app is simply not offered for it: add the type to the manifest and to
    `IncomingManifestTest`.
- **Import** (S6; `feature-workspace`: `ImportSheet.kt`, `ImportReview.kt` over `IncomingViewModel`, `ImportsPanel.kt` over `PointsViewModel`; `core-data`: `SessionPointSets`,
  `ThreatFileLog`, `PointSetRepository.read`/`save`; `app`: `MapHome`, `AppViewModel`). The design's Import menu (a modal bottom sheet from the frame's Import chip and the Imports panel's header:
  a mission, local points, threats only for an account with them; each opens the system picker), the import review (a full-screen dialog of every file waiting, from the picker or another app)
  and the Imports panel (the sets of points, the missions kept with their files with *Open in Routes*, the threat files brought in with *Open in Threats*, and the held point's card). Things to keep true:
  - **Where each kind goes**: local points to the Library (saved and synced, under the name in the field) or **This session** (`SessionPointSets`: in memory, at most 10, never saved, gone at
    sign-out; drawn and used for a route point's name like any set, and *Save to Library* keeps one under its own id); threats to this device only (until *T*), listed as a threat file
    (`ThreatFileLog`, which goes with the picture: a wipe clears it); a mission to the Library with its file (a mission held only for a session would need a session route set, which does
    not exist). **Not offered:** map overlays (owner decision 2: a new library, later) and the open pack (P-steps).
  - **What lands after a wait asks first whether nobody signed out meanwhile** (`IncomingFiles.generation`, which a sign-out's `clear` moves on, and which `AppViewModel` does first of all
    on a sign-out, before anything there waits): a session's set read across a sign-out is not held, a mission kept across one is not opened on the next person's map
    (`MissionImporter.import(stillWanted)`), and nothing said about the files is said to the next person. The review's own work is on the main thread, as the clear is, so a check
    and what it guards are never split.
  - **What each file came to is said in a toast once the review has closed** (`IncomingViewModel.notices` waits for it): a toast under the full-screen dialog would time out unseen.
  - **A cancelled review takes nothing more of the pick it came from** (`pickStarted`): files from a cloud provider are read one by one, and each would open the review again.
  - **The Library lists a save a moment after it returns** (Room re-reads after the transaction), so *Save to Library* lets go of the session's copy only once the Library lists the set
    (`LocalPoints` shows one of the two meanwhile): in between it would be in neither, and settling the views would drop its colour and whether it is shown.
  - **The name typed in the review is not saveable state**: files are numbered from 1 in each process, so a saved name could come back for another file after the system ended the app;
    the view model keeps it while the file waits.
  - **Opening another panel takes the focus off a field left open** (*Open in Routes* / *Open in Threats*): the Imports panel stays composed out of sight, and a focused rename would take
    what is typed.
  - **Laid out for a 360 dp phone at the largest text** (`ImportsNarrowTest`, at 1.3 and 2.0): a row's words and its button, or a place and its reason, sit side by side while the words keep
    168 dp and one under the other below that (`SideBySide`); a file's name and its kind, and a set's count and its place, wrap rather than squeeze (`FlowRow`); tags and state chips grow
    with the text (`heightIn`, `StateChip` included), and the segmented choice's labels take two lines rather than being cut (shared with the route's plan).
  - **Not verifiable here:** the system picker and a slow provider, the bottom sheet over the real map, the keyboard in the review, and TalkBack.
- **The Library** (S7; `feature-workspace`: `LibraryPage.kt`, `LibraryViewModel`, `NameTakenChoice`; `core-sync`: `LocalRecord.updatedAt`, `cutToCharacters`, `MAX_NAME`;
  `core-data`: the repositories' `duplicate`, database version 4; `app`: `MapHome`). Everything saved, a page over the frame (as the route's plan is, so the map and the panels stay
  composed under it): the LZ/PZ, Routes and Local points tabs with their counts (only those of the panels the account is offered), search by name (an LZ/PZ by its grid too, spaces
  ignored), *Recently updated* or *Name A to Z*, and each item with what it is, when it last changed, whether it is open now (a set of points: on the map), *Open*, and under *More*
  Rename, Duplicate and Delete (asked first); a conflict copy has its three choices under it. It opens from the Library chip (at the panel showing's tab), from the LZ/PZ panel's
  *Browse all* and from either panel's conflict *Review*; the panels' own every-item pages went. Things to keep true:
  - **A record notes when it last changed** (`LocalRecord.updatedAt`, a column of its own since version 4): this device's clock for a change made here (`SyncRepository`, and what a pack
    refused when it is kept, `LibraryPackKeeper`), the server's `updated_at` for one a pull or a conflict brings, and this device's again for *Keep mine*. **No time is ever made up**: a
    record from before version 4 says none (and sorts last) until it changes; an LZ/PZ falls back to its own document's time, read from the JSON, never the normalised diagram's (which is
    "now" for a document with none). **The server writes UTC with no offset** (Python's `isoformat()` of `utcnow()`), so a time is read with or without one, none meaning UTC
    (`LzNames.instantOf`), and sorted by instant, never as text. **Not like the web**, whose `new Date()` reads such a time as the browser's local time.
  - **Open, rename, delete and settling a conflict are the panels' own** (the open one's unsaved edits are where they are renamed, and a delete closes it first): the Library hands them over,
    and **what a panel says of them is said in the Library's toasts while it is open** (`MapHome`), since its banner is under the page. An action asked for while the panel is still busy is
    said not to be done (`STILL_WORKING`), never dropped without a word. *Open* opens the item, shuts the Library and shows its panel, as the web's does.
  - ***Duplicate*** makes `NAME 2` (the next free) under an identity of its own: an LZ/PZ whole, a set's routes, a mission **with its file**, a set's points. One open now is saved first,
    so the copy has what is on the map.
  - **A name taken offers *Keep both* (the default) or *Replace*** (the design's Confirm A), in the Library's rename and in the Save sheets: *Replace* deletes the other item (closing it if it
    is open) and this one takes the name. **Not like the web**, which writes this version over the other's record: here the other is deleted and this one renamed, so its identity is this
    one's (a mission pack's copy of the other is not linked to it; packs have no screens yet).
  - **Every name made here fits the server's 100 characters** (`MAX_NAME`; the columns are `String(100)`): a longer one is refused with a 500, and a send that meets a 5xx holds up everything
    queued behind it. So `LzNames.free` (*Keep both*, *Duplicate*) cuts the name and never the number, the sync engine cuts a conflict copy's "NAME (from this device, 14:32)" the same way,
    by characters (no emoji split); a field never takes a longer name, but always lets one that is too long be made shorter.
  - **Each opening starts fresh** (no search, the newest first), as the web's: an old search must not hide the conflict *Review* was opened for. A turn of the phone keeps it.
  - **A text field in a dialog window never settles under the test harness** (Robolectric: "Compose did not get idle"), whatever the dialog: the rename dialog's content
    (`LibraryRenameContent`) is tried on its own, as the other dialogs' are, and the page holds which item is being renamed so it can be tried without the window.
  - **Laid out for a 360 dp phone at large text**: a tab's label takes a second line before its count is pushed off; a row's *Open* and *More* go under its words before a grid is split.
  - *Add to Mission Pack…* (and *Add to pack* while a pack is open) is *Copies into a pack*'s. **Not offered yet:** the design's *Working with a team? Open a
    Mission Pack* (P1).
- **The map's long press** (S8; `feature-map`: `LongPressTouches`, `DragMapView`, `EzpzMap(onPress)`; `app`: `MapMenu`, `HomeViewModel.pressed`, `MapHome`;
  `feature-workspace`: `RoutesViewModel.pointAction` / `addPointAt`, `savedToLibrary` / `offersLibrary`). A long press on nothing that can be picked up opens the design's map menu
  (`MapMenu`): headed by the grid under the finger, *New LZ/PZ here* (`DiagramsViewModel.newAt`), *Add threat here* (the threat's form at that place), *Add route point here* and *Copy
  grid*, each only for an account offered its panel (*Copy grid* always). And the *Saved* toast offers *Open Library*. Things to keep true:
  - **No menu while taps are a boundary's corners, a route's points or a tool's placements** (`pressed` is null then, as `dragStarted` picks nothing up). A press on nothing takes the rest
    of that touch from the map (a cancel, as a drag does), so the map does not pan away from the menu while the finger is still down. A longitude past ±180 (the map repeats the world
    across the date line) is brought back: it is the same place.
  - **It is a popup of its own, not Material's `DropdownMenu`**, which opens with its corner under the fingertip: it stands above and to the right of the press as the design draws it
    (`mapMenuPosition`: else to the left, else below, never off the window), with the design's ring at the press, and is placed from the press in window pixels, so a right-to-left
    language does not mirror it to the wrong side of the screen (a zero-size anchor and `Modifier.offset` did). A touch elsewhere or Back closes it.
  - **The grid is never split** between its easting and northing: the menu widens for it (large text) up to the window, and past that the grid's text is made smaller (`TextAutoSize`).
  - ***Add route point here*** puts a named turn point (`.CP`) at the end of the route being worked on (the one held, else the set's only route) and holds it, one undo step; with no
    route to add to it starts a new route there (*Sketch a route* with that point down: `startSketch(first)`). **Greyed out, with the reason**, in an imported mission (its routes are its
    file's) and with routes to choose between and none held. What it would do is read when the menu opens (`pointAction`) and again when chosen.
  - ***Open Library* is offered only on the Saved toast** (`savedToLibrary` is its one wording, in all three panels; `offersLibrary` recognises it) and only for a tab the account has
    when the toast shows (`panelToast`, reading the tabs as they are then: the collectors outlive a change of features). Opening the Library closes a route's plan page, which would
    otherwise stay under it and come back over the panel the Library's *Open* chose.
  - ***Add object here*** (the owner's request of 2026-10-15; `AddObjectChoice`, `MapObject`, `placeObject`, `addObjectOff`): while an LZ/PZ is open, for an account offered the
    LZ/PZ panel, whichever panel shows. It turns the menu into the objects (helicopter, PZ marker, sector of fire, left and right go-around, unit, with the tool grid's symbols,
    `ToolSymbol` / `ToolGlyph`) with a row back to the choices, in the same popup rather than a second one over it. Each puts one at the press and holds it
    (`GraphicsViewModel.place` / `addUnit`, one undo step), as the tools' *Add here* does at the crosshair; a unit is friendly infantry, changed from its options. **Greyed
    out, with the reason**, before the LZ/PZ is analysed and in a read-only pack. Draw LZ is not in it: a boundary is not a place.
  - **Not verifiable here:** the long press and the quick zoom on a real screen, the haptic, how the popup sits over the real map, and TalkBack (the menu names itself with `paneTitle`; the
    long press itself has no non-gesture counterpart, but every choice is also in a panel, at the crosshair).
- **The phone's row** (the owner's decision of 2026-10-13, departing on purpose from docs/native-design's search bar over a row of chips: on a phone space is tight;
  `feature-map`: `MapTopRow`, `MapSearchState`, `MapChrome`; `core-designsystem`: `ChipLine`; `app`: `WorkspaceChips`, `AccountButton(floating)`, `MapHome`). One row of controls
  along the top of the phone's map, each on a surface of its own with the map showing between them: the search folded into a round button, the workspace chip, Import and the
  Library, and the account at the end. A tablet keeps its search bar and its rail. Things to keep true:
  - **One height, lined up, every target 48 dp** (crews may wear gloves): each control is drawn 40 dp tall (`MapChrome.CHIPS_HEIGHT`) on a 48 dp row (`ROW_HEIGHT`), each
    clickable surface a 48 dp touch target (Material's minimum interactive size), and the account's middle over the middle of the map's buttons (it stands in a 48 dp box). The
    camera's top padding is the row's bottom (`ROW_BOTTOM`), a sheet at full height stops 12 dp under it (`SHEET_TOP_LIMIT`), and the map's buttons stand 16 dp under it at the
    end (`BUTTONS_TOP`); a tablet's search bar keeps `SEARCH_BOTTOM`.
  - **The search opens across the row** (`MapSearchState`, held by `MapHome` so the frame can fold it): over the chips and the account, its field focused and the keyboard up, a
    back arrow at its start (where the magnifier was) and the search action at its end, and the keyboard's search key; 200 ms to open or fold. It folds on its back arrow, on
    system Back (its handler is composed while it is open, so it is asked first), after a search that found the place (`MapViewModel.search` answers whether it did; the map
    flies there), and on a tap on the map, which then does nothing else (`foldOnTap`). A refused search stays open with its error under the row; folding lets the error go.
    What was typed is kept for the next opening, the cursor at its end. The row under the open search says nothing to a screen reader, and the open search's surface takes
    every touch over it.
  - **What gives way, first to last** (`ChipsThatFit`, then `ChipLine`): Import and the Library give up their words, then a pack's faces (three, one, none), then a chip's arrow,
    then a finished pack's group icon (its lock says what it is; "· Finished" first gives way to that lock), and only then is the name cut, with an ellipsis. At 412 dp Import
    and the Library are icons beside "Personal"; in a pack with a long name the name keeps about 90 dp at 360 dp. What gives way is composed and not placed, so a test asks
    whether a face is drawn by `layoutInfo.isPlaced`, never by finding its text.
  - **Not verifiable here:** the keyboard coming up as the search opens, and how the row reads over real imagery in sunlight.
- **The phone's frame hidden** (the owner's decision of 2026-10-13: on a phone space is tight; `app`: `FrameState`, `PhoneFrame(hidden)`, `rememberTouchExploration`,
  `HomeViewModel.mapTapped`, `MapHome`; `feature-map`: `FrameHiding`; `core-designsystem`: `keptAway`, `TakesNoTouch`, `PanelSheet(inert)`). A tap on the map that is the
  frame's hides the row along the top, what is said under it, the sheet (at whatever height) and the bar of panels, and the next shows them again; the readout pill, the map's
  buttons, the crosshair, the toasts, other people's pointers and what floats above the pill stay. A tablet's frame never hides. Things to keep true:
  - **Which tap is the frame's** (`mapTapped` answers): one with no boundary or route being drawn and no tool placing, on nothing, that put nothing down either (no graphic,
    drawn threat, route point, shaping point, local point or boundary corner was held). A tap with something held only puts it down; the next hides. A route held alone has no
    bar, stays held, and does not keep the tap from being the frame's. With the search open a tap only folds it. MapLibre tells a tap only from its detector's
    `onSingleTapConfirmed` (`MapGestureDetector`, read from 13.6.1's classes: its listener is the platform's double-tap listener too), so a double-tap zoom and a quick zoom
    never count, and `LongPressTouches` passes every touch on to the map unchanged but the ones a long press takes (which it cancels for the map).
  - **Out of reach while hidden, and composed** (`keptAway`): the row slides up off the screen and the sheet and the bar down, fading, over the sheet's own 300 ms (the map's
    padding eases over the same); from the moment it hides nothing of it is read by a screen reader or takes a touch (`TakesNoTouch`; on the sheet only the part that shows,
    `PanelSheet(inert)`, never the map above it), and once it is off the screen it is not laid out. It stays composed (the panels' scroll, pickers, listeners and dialogs) and
    comes back as it was, the sheet at its height. The map is drawn under the whole frame, so nothing resizes MapLibre's view as it goes, and the pill, what floats above it
    and the toasts ride down to the foot of the screen with the sheet (`controlsEdge`, the toasts' placement). Hiding takes the keyboard down (focus cleared) and folds the
    search; the row tells the grid's labels nothing (`chromeBox(shown = false)`), so they may use the edge it left, and the status bar is told on its own.
  - **What brings it back**: the frame's tap again; system Back (a handler composed as it hides, so it is asked before every other, unless a page over the frame has Back for
    itself: the route's plan, the Library, Members, Teams, New pack); anything that chooses a panel, by the person or in code, even the one already chosen (`FrameState.choose`:
    the Library's *Open*, the held bar's options for a panel, the map menu's *New LZ/PZ here* and *Add route point here*, an item opened from the Pack panel or the Imports
    panel, a pack opened, picked or joined); and the sheet moved in code (`raisePack`, the held bar's options). A dialog or page over the frame (a threat's form, the Library,
    the import review, a route's plan, the switcher) leaves it as it was, and so does a sheet lowered for drawing.
  - **Never while an accessibility service explores by touch** (`rememberTouchExploration`, followed while the app runs): a tap does not hide it then, and one hidden comes
    back. Kept through a turn of the phone (`rememberSaveable`), never across launches. While hidden the Pack panel is not being looked at (its seen marker does not move) and
    a sheet at full height does not cover the map for the pointer.
  - **Not verifiable here:** the slide over the real map, the crosshair's glide with MapLibre's padding, MapLibre's single tap on a device, and TalkBack's touch exploration.
- **The phone's panel closed** (the owner's request of 2026-10-15; `app`: `FrameState.closed` / `close` / `open`, `PhoneFrame(closed, onClose)`, `closedEdge`, `pick(closed)`). The
  sheet's header has a close button beside its full-height arrow: the sheet slides down behind the bar of panels (300 ms, the sheet's own) and the map has the screen above the bar.
  A tablet's list has its own collapse button already, so this is the phone's. Things to keep true:
  - **The bar stays, and brings it back**: picking any tab, the one that was showing too, opens it at half (`pick(closed = true)`), and so does anything that chooses a panel
    (`FrameState.choose`) or raises the sheet in code (`open`: `raisePack`, the held bar's options). Hiding the frame and showing it again does not open it (`show` is the frame's).
  - **Closed, it keeps its room and lays nothing out in it** (`KeepsRoomEmpty`): laid out at no size, the bar would jump to the top of the screen. It stays composed, as hidden,
    and is not read by a screen reader; the camera's bottom padding is the bar alone, and the readout, what floats above it and the toasts go down to the bar's top with the
    sheet's edge (`closedEdge`). The keyboard goes down with it. While closed the Pack panel is not being looked at, and a sheet left at full height covers nothing.
  - Kept through a turn of the phone (`FrameState.Saver`), never across launches; a window that turns into a tablet's opens it.
  - **Not verifiable here:** the slide behind the bar over the real map, and the map's padding easing to the bar.
- **The tablet** (S9; `app`: `frame/TabletFrame.kt` (`TabletFrame`, `TabletFrameState`, `tabletPanes`, `usesTabletFrame`), `MapHome`; `feature-workspace`: `LzPane`,
  `LargeDialog`, `RoutePlanPage(inPane)`; `feature-map`: `MapScreen(buttonsAtFoot)`). A window at least 840 dp wide (Material's expanded width) **and 480 dp tall** has the
  tablet's frame (the design's AT screens): a rail (the workspace, Import with its menu at the tile, the panels with their counts, the Library, the account), the panel showing
  in a 344 dp pane beside the map, which collapses, the map with its buttons at its foot, and supporting panes on its right (320 dp): the open LZ/PZ's plan, aircraft, analysis and
  tools (`LzPane.PLAN`; on a tablet `LzPage.PLAN` is this pane, opened when an LZ/PZ is opened, beside whichever panel shows) and the held route's plan (the phone's page, with a
  close button). The Library and the import review are large dialogs (up to 880 dp). A phone on its side is wide enough and not tall enough: it keeps the phone's frame. Things
  to keep true:
  - **The map, the panels and the route's plan are movable content** (`movableContentOf` in `MapHome`), placed by whichever frame shows: crossing 840 dp (a tablet turned, a
    foldable opened, split screen resized) moves them rather than building them again. A map built again on the same `MapHost` added its MapLibre sources a second time and
    crashed, flew back to the launch's camera, and asked for the location permission again; the panels lost what was typed in them. **The phone's sheet is drawn by `PhoneFrame`,
    not by the map screen** (`MapFrame` has no sheet), because one movable content moved inside another breaks Compose (§14). Not kept across the line: the LZ/PZ plan pane's
    own state (it is another host than the phone's plan page) and the Library's open dialogs.
  - **The panes' state is the screen's** (`TabletFrameState`, saved): a panel picked brings its list forward; a supporting pane that is new, or asked for again (`planAsked`,
    counted by both view models each time a plan is asked for, open or not), stands; one that comes back after going (the same LZ/PZ's plan when LZ/PZ is picked again) does not
    take the list's place. Where the rail, both panes and 400 dp of map do not fit, the one opened last stands and the other waits, composed. What the phone does by raising its
    sheet (a held object's options, the Library's *Open*, the aircraft page) brings the list forward (`showList`). Back puts away the supporting pane that stands, never one
    waiting out of sight. A field left focused in a list that goes out of sight gives up the keyboard.
  - **The rail scrolls** where the window is short, so the Library and the account are never pushed off; its tiles are one named button each, and the Library is a button,
    not a fifth tab.
  - **A large dialog's scrim is no node** (a tap handler, not `clickable`): a clickable merges what it holds, and a page's pane title cannot be merged (it threw); the content's
    close button and Back close it. The tablet's Library is in a dialog window of its own, so the keyboard's focus and a screen reader stay in it.
  - **The route's plan fits a 320 dp pane**: the total keeps its words (the elevations button goes under it) and a point's values take two lines rather than lose the wind.
  - **Not built:** the design's nav-log table for a route on a tablet (the phone's point cards fill the pane), and the Save sheets as tablet dialogs (they stay bottom sheets).
  - **Not verifiable here:** the real map in its pane, a foldable's hinge, a keyboard cover, and TalkBack on a tablet.
- **The workspace switcher** (P1; `feature-packs`: `WorkspaceViewModel`, `Switcher.kt`, `PackChip.kt`, `Workspace.kt`, `PacksModule`; `app`: `packs/Workspaces.kt`, `MapHome`,
  `HomeViewModel`; `feature-map`: `ChipsThatFit`; `core-designsystem`: `initialsOf`). Where edits go: the Library (the person's own) or one Mission Pack, as on the web. The workspace
  chip (how the pack stands, the pack's name in its colour, who is here, its arrow) opens the switcher: a bottom sheet on a phone, a menu at the rail's tile on a tablet, with the
  invitations waiting (*Accept*, *Decline*), the Library, the packs (those worked on, then the finished ones; the open one says how it stands) and the teams line. On a tablet, under
  its search bar, how the open pack stands (*Live · all changes synced*). Things to keep true:
  - **On a phone the chip says how the pack stands** (the owner's decision of 2026-10-13, folding the line that stood under the chips into the chip; `PackStanding`, `PackChip`,
    `rememberPackAttention`, `PackAttentionLine`, `WorkspaceLine`): its leading icon, where the group icon was (so the chip is no wider), is a shape of its own for each state,
    never colour alone: synced (live or polling) `cloud_done` in the success colour; changes on their way `cloud_upload`, offline (with changes waiting or not) `cloud_off`,
    opening `hourglass_top` and paused a filled `pause` (the outlined one reads as two zeroes), all in the warning colour; cannot reach the pack and no longer available `error`
    in the error colour; finished as before, the outlined group icon and "· Finished" (the lock at large text). Read in `PackWords.status`'s order, so the icon and the words
    always agree (a finished pack that cannot be reached shows the error). A screen reader hears it on the chip: "Workspace: Mission Pack OP DK, live, all changes synced. 2
    people here now: …" (`PackWords.spokenStatus`); the tablet rail's tile keeps its words, its line saying the rest. The words are one tap away: the switcher's row for the open pack
    says them beside the same icon (`PackRowUi.standing`, `status`; not for a finished pack, whose line says so).
  - **Under a phone's row the words show only when they want attention** (`PackStanding.attention`: offline, both forms; paused; cannot reach; no longer available), each once
    its state has lasted 2 s (`ATTENTION_AFTER_MS`), so a launch's moment on the device's copy or a reconnect blip says nothing; never for synced, saving (it changes with every
    edit, and a line coming and going would move what is under it), opening (the panels say *Opening the pack…*) or finished (the chip and every panel's banner say it). A line
    already said stays as the pack goes from one such state to another, in the new words; another pack starts again. It keeps `PackStatusLine`'s look (tone, icon), stops short of
    the map's buttons, is not drawn under a sheet at full height (timed all the same, so it is there again at once when the sheet comes down) and hides with the frame; it
    appears in a polite live region that is there before it (`PackAttentionLine`), so a screen reader hears it come. A tablet's line is as it was: every state, at once.
  - **Switches are made one at a time, in order, and the latest asked for wins over one still waiting** (a conflated channel): the person's choice, an invitation link's join
    and the way out of a pack that went never drop each other without a word, and two never run at once.
  - **Opening a pack from the Library sets the person's own LZ/PZ and route set aside** (`SessionParking`), **both written first**: a write that fails keeps both open and owed,
    the pack is not opened, and the toast says so (`DocumentSession.close` closes whatever happens, which a switch must not). Once the pack is open, anything of the person's own
    that opened meanwhile (the launch's restore) is set aside too. Back to the Library puts the last ones back, each on its own; a pack that did not open puts them back at once.
  - **At launch the pack remembered for the account opens at once** (`LastPack`: one slot, its uuid and the account, so another person is never put in it), once per account
    in a process, never over what the person did since; it does not wait for the switcher's lists, and `HomeViewModel`'s own restores yield to an open pack. A pack an invitation
    link let them in to before packs ran opens instead, once they do.
  - **The lists are one account's**: cleared when the account changes; only the latest load is shown, and only for the account it was asked for; *No packs yet* only once a load
    has answered; the open pack has its row even with no list (offline, or just joined).
  - **A pack that goes** (`GONE`: deleted, or no longer theirs to see) is left at once, told why, and only if it is still the one open.
  - **An answer to an invitation that did not go through is said in the switcher** (a toast would be under the phone's sheet); one that let the person in closes it, opens the
    pack and then says what was joined. An invitation link's join opens the pack once per invitation, however often `MapHome` is composed again before its toast goes.
  - **Dates are the person's** (the server's time without an offset is UTC), with the year when it is not this one; **initials are the account button's** (`initialsOf`).
  - **The switcher's content does not scroll itself**: its host does (the sheet), and Material's `DropdownMenu` already scrolls what it holds, where a second scroll is
    measured with no height and throws (§14).
  - **The chips stay on one line at 360 dp and large text** (`ChipsThatFit`): Import and the Library give up their words (measured as text, so only the chips shown are composed),
    then the pack's faces and arrow (`ChipLine`, *The phone's row*), before the pack's name is cut (how it stands never), and one face shows past 1.3× text. A line under the
    row or a tablet's search bar wraps, stops short of the map's buttons and is not drawn under a sheet at full height. The
    switcher's open flags go with packs and with the frame, so it never opens by itself later.
  - *New pack* beside the *Mission Packs* heading opens New Mission Pack (P3a, *New pack* below), and *Manage teams* on the teams line opens Teams (P3c), the
    switcher closed first. **Not verifiable here:** the sheet and the
    menu on a device, TalkBack, and the faces' colours in sunlight.
- **The Pack panel** (P2a; `feature-packs`: `PackPanelViewModel`, `PackPanel.kt` (its models and `PackPanelWords`), `PackPanelScreen.kt`; `core-data`: `PackWorkspace.renameItem` /
  `removeItem` / `closeItem` / `openItems`; `app`: `Panel.Pack`, `lookingAtPack`, `MapHome`). While a pack is open the bar (and a tablet's rail) has a Pack tab first, and a pack that opens
  shows it: how the pack stands, what it is for, who is in it and who is here now, then its items by kind (LZ/PZ, Routes, Point sets, in the order they were added) or its history,
  newest first. Things to keep true:
  - **A pack that opens shows its panel once** (`packShownFor`, saved, so a recreated activity does not take the person back to it), **raised only when it was picked in the switcher**
    (the one already open is shown at once); the bookkeeping is done before the sheet moves, so a raise a finger cuts short leaves nothing stale.
  - **An item's row** says who changed it last and when (`whenText`: "4 min ago", "13:21" today, else its date: **not like the web**, whose clock time runs a day and can be
    yesterday's), what it holds, where it came from (*From Library*, *Original changed*), who else has it open (presence's `focus.item`: "has it open", not the web's "editing",
    which needs a `holding` nobody sends) and a dot when someone else changed it since this person looked. A tap shows it in its own panel (`PackWorkspace.openItem`: an LZ/PZ in
    LZ/PZ, a route set in Routes); a point set says it is not drawn on the map yet (*Deferred* 4). Rows, groups and events are keyed, so an open menu stays with its item.
  - **The menu**: *Open* / *Close here* ("It stays in the pack"), and for an editor *Rename* (for everyone, in capitals, at most 100 characters counted as characters, the web's
    words) and *Remove from pack* (asked first, the web's words). **A removal the pack would refuse is refused before the item is closed** (no session, paused, gone, read-only):
    closing first would set it aside and, paused, keep its last change as the person's own, for a removal that never happens.
  - **"New since you looked"** is `usePackSeen` ported (`Seen.next`): the marker moves 1.5 s after the person starts looking, only while they look (`lookingAtPack`: the Pack panel
    showing, the phone's sheet not lowered or the tablet's list standing, nothing over it, the app in front), and "new" is measured from where they were when they started. **The
    marker counts only once the server has answered** (`POLLING`/`LIVE`): the device's copy of a pack, shown first at launch, carries the marker of its last load (**not like the
    web**, whose session only ever comes from the server).
  - **The history** is asked for only while its tab shows: the newest page first, and after any page that says there is more, the newest 500 again (never the oldest); then what came
    since. Leaving the tab lets it go. **It is woken by the tab, the pack, its head and the account, never by who is here**: a pack's state is published for every presence frame,
    many a second, so anything combining `PackState` must narrow to what it uses (a failed page was asked again for each). Its words are the web's (the skipped edit's reasons,
    *Members* and *Pack* for the pack's own events); a filter whose person or item has gone keeps nothing back; a failure is said above what is still shown.
  - **A line of the history is a change, not an event** (`core-missionpacks`: `PackHistory`, the web's `packHistory.js`, held to `packs/history.json`). A change goes to the pack
    as one operation or several, each with the change's one sentence, and the server logs every operation as an event: a doghouse turned (the landing or takeoff heading it
    gives, then its own heading) read twice, and an analysis five times (the owner, 2026-10-13: "duplicate history entries"). Events one after another in the log (no number
    missing), by one person, on one item, with one sentence and outcome, are one line, as the server counts a copy's changes (`pack_changes`); a line never holds events either
    side of where new starts, is dated by its newest event, keyed by its first, and named, for an item since removed, by the last name an operation of it gave the item. So the same
    sentence made again straight after is the same line (three moves of a doghouse a second apart are one), and "New since you looked · N" counts lines. **A doghouse's turn
    names it and its heading as its box shows them** ("Colin M. turned doghouse RP1 to 105° on LZ HAWK.": its `label`, else `id_val` less its brackets; `PackLz`, the web's
    `packLz.js`, held to `packs/describe.json`), so turns a second apart are a line each, and one turn, two events, is one.
  - **The pack's notices are said** (`PackWorkspace.notices`, which nothing showed before): the web's words for a change refused and edits lost, Android's for what it keeps
    (`NAME (my edits)`); an item that left the editor because its pack went or closed is not said to be removed, only what was kept of it. One action at a time: another asked meanwhile
    is said not to be done, and an open that cannot first save what is open says so (it never takes the app down).
  - The members row opens Members, and its owner's *Invite* opens it at the invite field (P3b, *Members* below). *Add from Library* is its foot's (*Copies into a pack*); *Update* and *Save a copy to Library…* are *Updating a copy, and copies to the Library*'s; *Finish* and *Reopen* are *A finished pack*'s. **Not verifiable here:** the
    sheet over the real map, TalkBack, and how often presence rebuilds the rows on a device.
- **The panels in a pack** (P2b; `core-data`: `OpenPack`, `PackGlance`, `PackFacts`, `MissionImporter`; `feature-workspace`: `DiagramsViewModel`, `RoutesViewModel`, `LzPanel.kt`,
  `RoutesPanel.kt`). While a pack is open the LZ/PZ and Routes panels are the pack's: its LZ/PZs and route sets in the order they were added, under "In OP DK · n" (the open one first,
  when it is the pack's), each with how far along it is beside its grid, what holds of it in the pack under that (*Read-only*, *Original changed*, *From Library*, *Waiting to send*;
  never *Synced*, which most would say) and who else has it open; the head says how the pack stands (`live`, `live · view only`, `finished · read-only`, `paused`). The open one's card
  and plan have no *Save*, and say "Saved to OP DK as you work. 4 members can see it." and "Sam B. and 2 more are also here" (the web's). A new LZ/PZ goes into the pack. Things to keep true:
  - **The panels read a `PackGlance`, never `PackState`** (`OpenPack.glance`): a presence frame that only moves a pointer (up to ten a second for each person) is the same glance and is
    not passed on, and items are compared by their data's instance, never their contents. A pack's LZ/PZ is read as a diagram once while its data is the same, and that cache is made
    again from the pack's items each time, so one removed, or another pack's, is let go.
  - **A pack's item is told by its id** (`PackRef`), not by being listed: one open that the state does not list for a moment (a client replaced after a pause reads the device's copy
    first; an item removed before the editor has closed it) stays the pack's, never a Library record with a *Save*. *Save* refuses a pack id, and a pack's item takes any name from its
    card, its own `LZ/PZ n` included.
  - **The person's own are set aside**: none is opened while a pack is open (the Library's *Open*, an import's *Open in Routes*: "switch to Personal"); closing or deleting one left on the
    map brings none of the others in; a mission brought in is kept in the Library and not opened, and the Routes foot offers no *Import .msnx* (as on the web); the Library's conflicts and
    *Every route set* are not shown. They come back when the pack is left (P1's parking).
  - **A sketch in a pack** draws into the pack's set last on the map, else its first (the web's `packRouteSetFor`). Only a pack with no set gets one, made as the sketch starts and taken
    out again (`OpenPack.removeItem`) if the sketch ends with no route while the set is still empty and still has the name it was made with. **Not like the web**, which makes the set when
    the route is finished (here a route is drawn into an open set); the history keeps that it was added and taken out. A set the pack would not make is said by the pack's toast alone.
  - **Before the device holds the pack** (opening it the first time, or out of reach with no copy) the panels say so in place of its items ("Opening the pack…"), never an empty pack's
    invitation to add to it.
  - **A row's grid breaks only between its halves** (`gridBreakingBetweenHalves`: no-break spaces inside `16S GC` and inside `31204 58811`), never mid-digit, and its state goes under
    it, whole and with no separator to start its line, where both do not fit (`DotJoined`); a screen reader reads the grid as it is written.
  - **Not built:** the Imports panel in a pack (a pack's point sets, *Deferred* 4); the person's own go in from the Library (*Copies into a pack*). **Not
    verifiable here:** the panels over the real map in a pack, TalkBack, and presence at its real rate.
- **A read-only pack** (P2c; `core-data`: `PackLock`, `OpenPack.lock` / `lockNow`, `PackFacts.lockedLine` / `lockedHint` / `NOTHING_NEW`; `feature-workspace`: the view
  models' refusals, `GraphicsUiState.listed` / `locked`, `BoundaryUiState.locked`, `HeldObjectUi.locked`, `LocalPlanReadOnly`, `MapPointAction.Locked`; `app`: `HomeViewModel`'s
  lock collectors, `MapMenuChoices.newLzOff`; `feature-map`: `LzScene.locked`). While the open pack is read-only (finished by its owner, or this person a viewer) nothing that
  would change one of its items is offered, and what is asked anyway is refused in words before anything is changed, sent or asked of the server. Things to keep true:
  - **One lock, read two ways**: the screens draw from `OpenPack.lock` (a flow that a pointer moving never changes), and every refusal asks `lockNow()` as the person acts, so
    neither is a step behind. A lock is a pack's: `reasonFor(id)` is non-null only for an item of that pack, never the person's own documents, threats or local points, and never
    for one of its briefs (`PackLock.briefs`: a brief is open to every member in every state of the pack, docs/MISSION_BRIEF.md §3; a test holds that an LZ/PZ or a
    route set of the same pack is still refused). Before
    the device holds the pack (a client being replaced after a pause) nothing is known and nothing is locked; the engine holds edits then, never loses them.
  - **Refuse before, never after**: a refusal comes before the edit, the selection change or the server call (analysis, winds, elevations). A typed value that changes nothing
    is no change and is never refused (a field set to what the pack says, someone else's change arriving, would otherwise show an error under it).
  - **Looking is not changing**: holding, the plan and inspector (as words), *Close here*, sharing and exporting stay; so do each person's own fields (hiding a route, the base
    map) and device choices (the mission aircraft, the slope map, the weather). Undo and redo do not: the history kept from before the lock would be a change.
  - **What was under way ends** when the lock arrives (`HomeViewModel`): a tool's mode is **ended, not cancelled** (cancelling takes its placements back, itself a change),
    a boundary or route draft is not kept, a drag is **ended where it is** (`MapDrag.abandon`: moving it back is an edit too, refused and put back), an analysis of any of the
    pack's LZ/PZs stops (it runs on for the one it was asked for after another is opened, so the open one is not the only one to look at), and one toast says what stopped
    ("OP DK was finished: placing stopped."). Winds or elevations on their way to its set are not applied (`RoutesViewModel` keeps the fetch's job; the note says why). A
    threat drag is never ended. A change the pack had not taken yet is put back by the engine's own rule, with its toast.
  - **Hidden is not enough**: what was begun on a screen (a name typed, a value refused, a half-typed date, the calendar, the clock, the move pad, a route's rename or delete)
    is let go when the lock arrives (`LaunchedEffect` on it), or a reopen brings it back by itself. A field reset to the plan's value is never applied back to it.
  - **The route's plan is a disabled fieldset done Compose's way** (`LocalPlanReadOnly`): fields `readOnly`, never `enabled = false`, so they are still read and heard.
  - *Save a copy to Library* stays, since it changes nothing in the pack (*Updating a copy, and copies to the Library*); the finished banner is *A finished pack*'s. **Not verifiable here:** the PZ tip's handle layer (GL),
    TalkBack on the disabled controls, and a lock arriving mid-gesture on a real screen.
- **A finished pack** (P2d; `feature-packs`: `Finished.kt` (`FinishedBanner`, `FinishedBand`, `PackPanelFoot`, `PackHeaderMenu`, `PackDialogs`), `WorkspaceViewModel`
  (`PackDialog`, `askFinish` / `finish`, `askReopen` / `reopen`, `askDuplicate` / `duplicate`), `PackWords.finished`, `OpenPackUi.owner`; `core-data`: `PackWorkspace.flushOpen`;
  `app`: `TabletFrame`'s `band`, `RailWorkspace.finished`, `MapHome`). The banner on a phone (the top of every panel), the band on a tablet (across the top beside the rail),
  *Finish* in the owner's Pack panel foot, *Reopen* for the owner, *Duplicate* for anyone in the pack (the banner, and the Pack panel header's menu). Things to keep true:
  - **One dialog, wherever it was asked for** (`WorkspaceViewModel.dialog`, drawn once by `PackDialogsHost`): it belongs to its pack and its account, so another pack opened,
    the Library, or another account puts it away; a copy being made keeps its dialog (it cannot be put away then) until its answer.
  - **A finish puts this person's changes first**: the editors' last change is written (`flushOpen`, not left to the session's pause) and their pending edits are waited for
    (10 s), else it is refused in words; a finished pack would refuse them. Then `PackEngine.refresh` (the finish is an event in the pack's log; the lock follows from it) and
    the switcher's lists. One finish or reopen at a time; what it came to is said only to the account it was made for.
  - **A copy is not repeatable** (the server makes a new uuid each time): a lost answer says the copy may have been made rather than inviting a second one, and the lists are
    asked for again so it can be found. Its name is the server's rule (1 to 100 characters, counted as characters; no control characters), refused in the dialog before anything is sent.
  - **A finish's wait counts only while the same pack is open and held** (a client being replaced holds nothing yet), with none of this person's edits pending: leaving it is
    not their having arrived (a closed pack's client still drains them), so the finish is not sent then, in words. A copy whose answer could not be read was made (said so,
    not to be made again); one lost behind the proxy (502, 504, 520 to 524) may have been.
  - **The band takes the window's top inset itself**; the panes and the map under it start below it (`consumeWindowInsets`), so the search bar is not pushed down twice. Its
    buttons go under its words where the words would keep less than 320 dp, and it takes at most 40% of the window's height.
  - **Not verifiable here:** the band on a real tablet's status bar, TalkBack reading the banner's live region, and the dialogs over the real map.
- **Pointers in a pack** (P2e; `core-missionpacks`: `PackEngine.setFocusItem` / `setPointer`; `feature-map`: `Pointers.kt` (`MapPointer`, `PersonColors`, `PointersLayer`);
  `feature-packs`: `PackDoor.point`; `app`: `HomeViewModel.cameraAt` / `pointers` / `pointersOf`, `MapHome`'s `PackPointers`). Everyone else in the open pack pointing somewhere
  is an arrow in their colour with their short name; this person's pointer (the crosshair, or the finger while it moves something) goes to them with their presence.
  Things to keep true:
  - **The focus has two parts, kept apart and said together** (the web's `{item, at}`): the open LZ/PZ (`PackEditorSync`) and the pointer (the map). Either one alone would
    wipe the other, since a client's focus is replaced whole. The item is said only to its own pack; a client the engine follows next hears both at once.
  - **The pointer is rounded to 1e-5 degrees** (the web's), and the same place is not said again, so a camera moving a pixel at a time sends nothing new.
  - **Only where the person put the map by hand is shared** (`CameraState.byHand`): never a GPS fix, a search or the view put back at launch (that would be the device's own
    position), nor the crosshair while the map is covered, nor in a pack just opened until the next pan (the engine clears the pointer on `openPack`).
  - **After a drag the pointer goes back to the crosshair, on purpose** (assessed 2026-10-13, asked whether the jump looks like a stray point): the crosshair is where a
    phone's pointer rests and where its at-the-crosshair actions happen, so a pointer left at the drop point would show the person somewhere their next action is not. The
    web's stays at the drop point only because a mouse stays where it was let go, and jumps as far when the mouse is moved quickly. The owner's to change.
  - **A presence frame is no reason for the editors to look** (`sameForALook`): the person's own pointer comes back to them from the live service ten times a second.
  - **The pointers are collected where they are drawn** (`PackPointers`): a pack's people change many times a second, and nothing else is built again as they move.
  - **A person is the web's colour for them** (`PersonColors.of(user_id ?: name)`, Avatar.jsx's hash over code points, each counted by its first UTF-16 unit); every session is
    a pointer of its own; this account's are never drawn.
  - **Not verifiable here:** the live stream end to end (pointers need the live service), how the pointers move on a real map, and TalkBack over them.
- **New pack** (P3a; `feature-packs`: `NewPackViewModel`, `NewPack.kt` (`NewPackHost`, `NewPackContent`), `People.kt` (`PeopleApi`, the calls about people the P3 screens
  make; `ApiPeopleApi` in `PacksModule`), the switcher's *New pack*; `core-network`: `asUser` on `createPack`, `updatePack`, `addPackMember`, `inviteToPack`, `searchPeople`,
  `listTeams`; `app`: `MapHome`). New Mission Pack: a full-screen dialog on a phone, 560 dp on a tablet; once made it opens, raised. Things to keep true:
  - **One form, one uuid** (chosen when the form opens and kept through every try of it; the next form takes another): a try whose answer was lost is the same pack when
    made again, and one that finds it (`created` false) sets on it what the form changed since. `uuid_taken` takes another.
  - **The form is one account's**: every call names it, another account (or packs stopping) puts the form away, and a pack made meanwhile is neither opened nor said.
  - **Nothing a person typed is sent in another's words**: the name is upper-cased as typed and counted in characters (100), the address lower-cased, and what is looked for
    trimmed as JavaScript trims it (a byte order mark and Unicode spaces too); a refusal is the app's words (`PackMessages`), never the server's text.
  - **Each person is brought in once the pack is made, one at a time**, and one who cannot be stops nobody after them; what became of them is said once the pack opens.
  - **What was shown when Create was pressed is the choice**: teams that come later never change it, so a try again is made as the first was asked. A try that finds the pack
    an earlier one made sends only what differs (`PackChange.between`; every field sent is a line in each member's history), and one whose change fails keeps the form.
  - **Nothing typed in the person field is dropped at Create**: a whole address is invited, a name not chosen from the list is refused at the field. What the field finds is
    brought into sight above the keyboard. Why a pack was not made is a banner in sight whatever the scroll; only a name refused is said at (and focuses) the name.
  - **The form's content is tested without its window** (`NewPackContent`), as the threat form's is: a text field in a dialog window never settles under Robolectric.
  - ***Start with items from my Library*** (the design's switch, taken as Create is pressed) opens the Library on the pack once it is made and open, adding to it
    (`NewPackViewModel.withLibrary`, then `MapHome`'s `libraryAfter`: another pack opened first lets it go).
  - **Not verifiable here:** the dialog windows and the keyboard on a device, the dropdown menus over a dialog, and TalkBack.
- **Members** (P3b; `feature-packs`: `MembersViewModel` (`MembersUi`, `MembersConfirm`, `MembersWords`), `Members.kt` (`MembersHost`, `MembersContent`, `MembersQuestion`),
  `PeopleApi.people` / `changeMember` / `removeMember` / `resendInvite` / `revokeInvite`, the Pack panel's members row and *Invite*; `core-network`: `asUser` on the member and
  invitation calls and `pack`; `app`: `MapHome`). Members of the open pack: a page on a phone, 840 dp on a tablet. Things to keep true:
  - **The page is the open pack's, for one account**: another pack opened (or none), or another account, puts it away; every call names the account; what a change came to
    is said only to it, in the page's own toasts while it is up (the frame's are under it), else the frame's.
  - **One change at a time**, then the people read again (only the latest answer, for the page as it is) and the open pack asked for what is new (a member or a share is an
    event in its log). Who is here comes from the open pack's presence, narrowed to the accounts, since it changes many times a second.
  - **The invite field holds who is chosen** (the web's choosing mode): a teammate picked shows their name, typing again lets them go, and a whole address typed is who it
    holds; a name typed and not chosen is refused in words. A refusal is said under the field, what was typed kept.
  - **What cannot be taken back is asked first**: handing over, taking off, withdrawing an invitation and no longer sharing (the last two the web does not ask).
  - **A role chip is read once**, by what it holds and that it changes (`MenuChip`, `clearAndSetSemantics`), so its word is never a second node.
  - **Not verifiable here:** the windows and the keyboard on a device, the question over the page's window, TalkBack, and presence at its real rate.
- **Teams** (P3c; `feature-packs`: `TeamsViewModel` (`TeamsApi`, `TeamPage`, `TeamsUi`, `TeamsConfirm`, `TeamNaming`, `TeamsWords`), `Teams.kt` (`TeamsHost`, `TeamsContent`,
  `TeamNameContent`, `TeamsQuestion`), `ApiTeamsApi` in `PacksModule`, the switcher's *Manage teams*; `core-network`: `asUser` on every team call; `app`: `MapHome`). No
  native design: the web's TeamsDialog, laid out as Members is. Things to keep true:
  - **What each role may do is the server's** (`team_routes.py`): the owner renames, deletes, changes roles (handing over makes them an admin) and takes off anyone else;
    an admin renames, invites members and takes off members; everyone but the owner can leave. `TeamsUi.removable` is that rule, and nothing else is offered.
  - **A team just made stays chosen until a list has it** (`made`): an older list without it is not its going. A lost answer to *New team* may have made it, so it is
    said so, never made again by itself (the route takes no uuid).
  - **Members' rules hold**: one change at a time in the view model (a new team and a rename too), landing only on the page it was asked from; every call for the
    page's account; after each change, whatever it came to, the list and the team are read again; an answer that could not be read (a 2xx) is a change made.
  - **What is begun belongs to the team it was begun on** (`TeamsUi.showing`): a question, a rename, an address typed and its role go when another team is shown, a
    question names its team (`TeamsConfirm.teamId`) and is done only while it is still this person's to do (`allows`), and a change answered once another team is shown
    is said as a toast, never put on that team's page. Right after choosing, the last team's page is not the new one's (`team` is null until it comes).
  - **The frame's toasts are drawn by Members and Teams over themselves**, and a tablet's frame stops drawing its own meanwhile (`MapHome`), or each showed twice, one
    under the dialog's scrim.
  - **`BesideOrUnder` (Finished.kt) takes a width with no bound** (a menu measuring its content): beside, as wide as the two are. Before, the tablet's switcher menu
    crashed on its teams line.
  - **Not verifiable here:** the windows and the keyboard on a device, the name dialog and the question over the page, and TalkBack.
- **Copies into a pack** (P4a; `core-data`: `LibraryToPack` (`LibraryCopier`, `SessionWrites`, `LibraryUpload`, `CopyIn`); `app`: `EngineSyncRunner.upload`,
  `MapHome`; `feature-packs`: `PackCopyViewModel`, `Copies.kt` (`AddToPackHost`, `AddToPackContent`), `PackPanelFoot`'s *Add from Library*; `feature-workspace`:
  `LibraryPage`'s *Add to Mission Pack…* and *Add to pack*, the LZ/PZ and Routes panels' menus; `core-network`: `asUser` on the four item calls). One of the
  person's own Library items copied into a pack (the design's PackDialogs A). Things to keep true:
  - **The server copies the version it holds, so what the person sees goes first**: the open document is written (`SessionWrites`), and a record with changes
    the server has not had is uploaded (`LibraryUpload`: `SyncEngine.send`, the outbox sent and nothing pulled, on `Dispatchers.Default`, under a sync's own
    rules asked again before every try, the account signed in, its plans on the device, the app not too old; no pack drained; one already running is waited for,
    20 s at most) and looked at again, under the uuid the upload may have moved it to. One that did not go up is not copied in an older version, and the dialog
    says why; one another device changed too (`Uploaded.conflicts`) is never copied, since the record is then that device's version, and one the server refused
    for good (`Uploaded.blocked`) says so rather than to try again. **Not like the web**, which copies the last saved version.
  - **One item id per copy, kept through every try** (`AddToPackUi.item`; into the open pack, per record until it is in): the server answers a repeat of the same
    item, source and creator with the first copy (200), so a try after a lost answer never makes a second. **Not like the web**, whose every press is a new id.
    `item_exists` (that id taken: a copy since removed) takes a new one. A record seen moving to a new uuid is remembered (`LibraryCopier.currentId`), so a try
    made again finds it and keeps its item.
  - **A copy is asked for by the record's own identity** (`PackSource.ClientUuid`), never the server's id, which a record made here has not got yet. The server
    writes the history's sentence.
  - **One copy at a time, for the account it was asked for**: another account ends the copy under way; the dialog can be closed while the copy is made (it goes
    on, and its toast says what it came to), its answer lands only on it, and nothing is said to another account. A copy into a pack not open offers *Open pack*
    in its toast; one into the open pack asks it for what is new. The dialog is drawn after the Library, and made again with its window, so it is on top.
  - **Offered only where it can go, by one rule** (`CopyRules`): outside a pack, for an account with packs here; never an imported mission or a conflict copy;
    analysed or not, with routes or none; *Add to pack* and *Add from Library* only while the open pack is one this person can change (a viewer is known from the
    switcher's list first); a pack still opening says so.
  - **Not verifiable here:** the dialog's window over the Library page on a device, the upload's wait against a real WorkManager sync, and TalkBack.
- **Updating a copy, and copies to the Library** (P4b; `feature-packs`: `PackItemCopyViewModel` (`PackItemCalls`, `UpdateUi`, `SaveCopyUi`), `ItemDialogs.kt`
  (`ItemDialogsHost`, `UpdateContent`, `SaveCopyContent`), `UpdateWords` (the web's `lastChangeWords`), `ApiPackItemCalls` in `PacksModule`, the Pack panel's row and
  menu, `Finished.kt`'s *Save a copy to Library*; `core-data`: `LibraryCopier.readyOriginal`, `PackWorkspace.seenData`, `LibraryPackKeeper`'s `KeptVersion.asIs`;
  `feature-workspace`: the LZ/PZ and Routes panels' menus in a pack; `app`: `CopyRules.bannerCopy` / `packItemOf` / `libraryTabOf`, `MapHome`). A copy whose original
  changed is updated from it (the design's PackDialogs B), and any item is saved to the person's own Library (D). Things to keep true:
  - **An update cannot be made twice** (the server replaces the copy each time), so this person's last change is written and their pending edits waited for, and
    the original's last change written and uploaded (`readyOriginal`, P4a's rules), before it is sent; each refusal is in the update's own words, and a lost answer
    (none came, a proxy's 502, 504, 520 to 524) says it may have been made. A failure of the device's own store is said, never the app's end.
  - **A copy to the Library is the device's** (`PackKeeper.keep`, a key of its own each time, then a sync asked for), **not like the web**, which has the server copy
    its version: it is the item as this person sees it (`seenData`: the editor's document while it is open in one, a change held back while the pack is paused
    included), kept as it is (`asIs`: a route set's own fields and version, as the server's save keeps them). What a pack refused is still reshaped as the web does.
  - **The dialogs are the pack's and the account's**: another account ends what is under way and puts them away; another pack opened puts an idle one away and
    leaves one on its way until its answer, then said in a toast. They follow a change of pack (`map { uuid }.distinctUntilChanged()`), never a presence frame.
    An answer about an item lands only on the ask it was for (`UpdateUi.ask`), and the dialog knows who asked (`UpdateUi.me`: "your last").
  - **The row's *Update* goes under its words** where beside them they would keep less than 168 dp (`ROW_WORDS_MIN`), measured as text.
  - **Not verifiable here:** the dialog windows over the real map and the Library on a device, the keyboard in the name field, and TalkBack.
- **The edits a pack would not take** (P4c; `core-missionpacks`: `PackClient.keepDropped` / `discardDropped`, `PackEngine`'s; `core-data`: `LibraryPackKeeper.keep`;
  `feature-packs`: `PackPanelViewModel.keepDropped` / `discardDropped` / `kept`, `PackPanelWords.dropped` / `keptYours`, `PackPanelScreen`'s `DroppedNotice` and
  `DiscardDialog`; `app`: `MapHome`). The Pack panel's notice for edits the open pack refused while it was open (the web's), with *Save my version to Library*
  and *Discard…* (Android's own, asked first). Things to keep true:
  - **A keep is answered, not told** (`keepDropped`): null is *nothing done* (the offer stays), an empty list *nothing left to keep* (let go, what they changed
    gone), and the rest what was saved; the screen that asked says it, and the engine asks for the library's sync itself. The automatic keeps (a close, a
    drain, `enable`) still tell everyone.
  - **The keeper stops at the first failure and answers what it kept** (`PackKeeper`'s contract): those edits go, the rest wait to be offered again.
  - **A discard takes only the ids the question was asked about**, and says how many went: a drop that came since is never discarded unseen.
  - **Why is said truly**: the web's sentence only for `pack_finished` and `read_only`; another reason's own words (`LOST_WORDS`), else *The pack would not take it*.
  - **Not verifiable here:** the notice over the real map, and TalkBack reading its live region.
- **The 3D view** (`docs/ANDROID_3D_PLAN.md`: the steps V0–V7 and the owner's decisions of 2026-10-20, a native OpenGL ES renderer of our own, the whole screen
  over the frame with the system bars left see-through, the title `3D · NAME`; `core-scene3d`; `core-network`: `Viewer3dEndpoints.kt`; `core-data`: `LidarBuilds`,
  `BuildOnSave`, `TerrainProbe`, `Viewer3DTarget`; `feature-viewer3d`; `feature-workspace`: `DiagramsViewModel.view3d`, the LZ/PZ panel's *3D*; `app`: `MapHome`,
  `LidarWaitingPreferences`). Built: the calls, the build flow, build on save, the scene's maths, the page with its ways in, and the scene drawn: the point cloud
  (V4), the ground with its imagery (V5), the routes (V6), held together (V7); each step reviewed independently, and the whole by three final reviews (parity,
  lifecycle, the screens). Things to keep true:
  - **The view always shows the open LZ/PZ** (`DiagramsViewModel.view3d`): *3D* on another row opens that one first (the map goes to it and
    what was held is put down, as opening it does, and closing 3D shows that); another opened, or this one closed (a delete, a sign-out, a pack left, which close it),
    lets the view go, and it does not come back by itself, not even for a frame (a test records every state); one with no target has nothing to look at and its *3D*
    is off. One asked for while its LZ/PZ opens is withdrawn by a close or another ask meanwhile. Its title and whether it is saved follow the LZ/PZ while it is
    shown, and a target moved under it (someone else in a pack) looks again. **Not like the web, on purpose** (the owner's decision of 2026-10-20): the web's
    window stays open on whichever LZ becomes active and comes back by itself for one with a target (`App.js`); here another LZ/PZ can be opened only in code
    under the page (a delete, a pack's removal synced from another device), and that lets the view go.
  - **The whole screen, over everything** (the owner's, 2026-10-20): `MapHome` draws it last, over either frame; the frame stays composed and untouched under it, out
    of a screen reader's reach, of the keyboard's (`underPage`: a focus search into it goes to the page, whose focus does not leave it) and of touch (the page is a
    `Surface`, which takes what nothing on it takes), so closing shows the sheet, the panel, the page, what is held and what was typed as they were under it
    (`Viewer3DOverFrameTest`). Its Back is asked first, and a frame made again under it (a tablet turned, a foldable opened: a handler made later is asked first)
    gives way (`TabletFrame(backEnabled)`, `PhoneFrame(backShows)`); the frame's toasts are drawn over it; the Library opening (a toast's *Open Library*) closes it.
    The host leaving with a target still set (a sign-out, an emailed link, *Update required* take the map away) lets its lookup go, or its build would be asked
    after under whoever signs in next; **an activity made again for a new configuration is not the host leaving** (a font size, a keyboard attached: what the
    manifest's `configChanges` leaves out), so the view goes on as it was, its build not let go of and its camera where it was (`Viewer3DRecreateTest`,
    `Viewer3DActivityRecreateTest`). The system bars stay, see-through, and the bar sits below the status bar. Drawn in the dark palette, the night one at night
    (red, never bright: the bar's words, the routes' labels and dots too).
  - **For TalkBack, a keyboard and gloves**: each state is said as it comes, a failure at once (as the web's `role="alert"`), **in one live region that stays**, its
    words changed (Compose tells a screen reader of a change only to a node that was there before it: one made anew with its words is never announced), and so
    is the notice of no terrain; the scene is read after the bar and the controls over it (`traversalIndex`), its actions step the camera, and after each it says
    where the camera then looks (its heading, how far down, how far off the ground is). The keyboard's focus goes round the page: past its last control to the
    first, back past the first to the lowest (a focus request takes no direction along the order). Every control is at least 48 dp, the point size's slider too.
    A finger on the scene's side edges pans it rather than going Back, which would close it (`edgeBands`: the middle 200 dp of each, as much as Android lets an
    app take). The toasts are drawn above the foot, not over the point size, and sideways (wider than tall) the foot stops short of the controls at the top end.
  - **The build flow is the web's `useLidarTileset`, held to `contracts/fixtures/viewer3d/lidarFlow.json` scenario by scenario** (`LidarBuildsFixtureTest`: every answer
    the server can give, the poll every 3 s, five failed polls, two resubmits after a 404 or `cancelled`, and JavaScript's truthiness, so an empty `url` or `stage` is
    none: `orNull`). The requests each scenario makes are compared too, so a `watcher` or a `keep=1` left out fails. **It asks for the place streamed**
    (`stream = true` from `Viewer3DViewModel`; docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C), held to `lidarFlowStream.json` the same way, what is
    shown of a streamed answer (`LidarLookup.streamed`, `frame`) included; a streamed survey's scene opens, and *LZ* flies, on `frame`
    (`lzSphere`, the web's: a sphere of its radius at its ground height in the scene's frame, the core's own sphere where it has no height),
    never on the root's sphere, which is a state. A save asks `resolve` with `stream` before a build, and builds nothing where the place streams.
    A streamed survey's points are credited at the scene's foot under Mapbox's ("Point colour: USDA NAIP, from USGS The National Map",
    `Viewer3DWords.NAIP_CREDIT`), as the web's view credits them: the server lays NAIP on them in the background (backend `lidar/photo.py`).
  - **One watcher per install, and what it waited on is kept** (`LidarWaitingPreferences`: `android-` and 24 URL-safe characters, made once; the keys being waited on,
    16 hex each). A lookup let go of (the view closed, its scope cancelled, *Retry*) releases its build at once; the next launch releases what the last one was
    waiting on before its first lookup asks (waiting at most 3 s), as the web's `releaseAbandonedBuilds` does at a page's load. A saved LZ/PZ's build is `keep`,
    which the service finishes whoever waits.
  - **A catch asks whether it is still wanted before it says anything** (`stillWanted`, `ensureActive`): a failure already delivered beats the coroutine's
    cancellation (§14), so a view closed as a call failed would show the failure, or resubmit a build nobody waits for.
  - **Not like the web**: the server's text for a failure is never shown (a failed build's `error` carries up to 2,000 characters of PDAL's or py3dtiles' output,
    with the place in it); the app says what went wrong in its own words, by kind (`LidarProblem`). *Not generated* offers no operator's build command, which a
    crew cannot run from a phone, and names no file of the repository. An unsaved LZ/PZ is not told to "save the LZ/PZ to keep it building", which cannot be
    followed from a page that covers *Save* (closing to reach it stops the build), but that a saved one is built in the background; a mission pack's is told
    neither (the web tells its members to save, though a pack's LZ is never saved).
  - **Build on save** (`BuildOnSave`, from `DiagramsViewModel`): an LZ/PZ that becomes saved in this session (named, analysed, not a conflict copy, outside a pack:
    `LzNames.isSaved`, the moment *Save* shows *Saved*) asks once per process for a kept build with no watcher; one opened already saved asks nothing, as the
    web's `useBuildOnSave`. A pack's LZ/PZ is never saved in this sense (the web's `packLz` gives it no `savedId`).
  - **Every read is bounded** (`Call.maxBytes`: the declared length first, then the body read only to the limit and one byte more): a tileset file 32 MB, a
    heightmap exactly 8,450 bytes, an error page 64 KB. Paths are checked before anything is sent: a key is 16 hex, a file at most 16 parts of
    `[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}`, never `..`; a tileset's own content paths are resolved inside its store (`TilesetPaths`). A `tileset.json` and a `.pnts`
    are hostile input, read as `SqliteReader` reads a file, and cut and corrupted in their tests: nested no deeper than a tree needs, looked at before parsing
    (`JsonNesting`, §14), a tileset at most 2 MB and a tile a million points (py3dtiles writes 20,000 a tile and splits its JSON at about 100 KB), every
    volume, frame and float finite once composed, and a tileset joined only to the tile that holds it, once, never under one of its own, its tiles counted with the
    whole tree's.
  - **Terrain waits behind heavy work; tileset files do not** (the web's `whenIdle`): the heightmap calls are background calls through the priority gate; the
    heights under the routes are not gated, as the web's are not.
    A heightmap tile the server has none for (204) is no ground, never sea level; the probe for *No terrain here* asks the target's level-12 tile, as the web's.
  - **Tiles are chosen as Cesium chooses them** (`TileSelector`, Cesium 1.144's `Cesium3DTilesetBaseTraversal` rule by rule, each cited in its tests: the screen-space
    error, ADD and REPLACE, a REPLACE tile refined only once every child, seen or not, can be drawn and never when none is in view, an empty child giving way at once
    while what is under it out of view is fetched, an external tileset as its root and always gone into), with the screen's density as Cesium's pixel ratio, so an
    error and a point size are in dp as the web's are in CSS pixels. **A tile's error is scaled by its frame** (`Cesium3DTile.updateGeometricErrorScale`):
    py3dtiles writes an octree ten or a hundred times smaller with a transform that scales it back, and read unscaled the ring and the cores stopped several levels
    above where the web draws (the review of V3). **A failed tile is never asked for again** (`TileStatus`: only an unloaded one is), and the tile it would replace
    stays drawn, as Cesium keeps it. **Not ported**: the dynamic screen-space error, skipping levels, request volumes, holding requests back while the camera moves
    fast, and the memory-adjusted error; a point budget stands for the last (`withinBudget`). **The error raised for the budget is kept from frame to frame**
    (`PointCloudScene`, per tileset) and lowered a step a frame only once the view has changed since it was last raised, every tile it draws is in hand, and a step
    lower would still fit (about 1.5² as many points): started afresh each frame, a still camera whose finest view was over the budget fetched that view, found it
    too much, let it go and fetched it again for ever (the review of V4). **Not like Cesium**, which raises and lowers its error 2% a frame by its cache's bytes.
  - **The scene is drawn when something changes, never on a clock** (`SceneSurface`, `RENDERMODE_WHEN_DIRTY`): a finger, a tile that came, a flight under way, the
    point size, and the end of a failed tile's pause (`PointCloudScene.failed` says when; a frame must be drawn then, or nothing asks again). Each tileset's points go
    into an offscreen target and are lit by the eye-dome lighting onto the screen at their own depth, the core and then the ring, as Cesium lights each tileset on
    its own. **Depth is logarithmic** (`SceneDepth`, written in the vertex shader, exact for a point), so a 24-bit buffer holds about a millionth of any distance
    and the lighting reads no relief that is not there. Points are sized by Cesium's attenuation (`PointShading`), at least one dp across: the web's Cesium draws
    at the browser's own resolution (`useBrowserRecommendedResolution`, a pixel ratio of 1), so its smallest point, a pixel, is a CSS pixel; a point one of the
    screen's pixels across was a third of that on a dense phone, and far points all but vanished. **Not like the web**: a point with no neighbour at the radius
    keeps its colour, where Cesium's lighting divides nothing by nothing and draws it black.
  - **Everything is the session's, nothing the GL view's** (`SceneSession`, in the view model): the GL view and its context come and go (a pause, a lost context)
    and the tiles go onto the next context again from the points in hand; the points held stay within a quarter of the app's heap (`SceneBudgets.forHeap`, at 15
    bytes a point, drawn half that), never more than three million drawn and six held. A frame's fault is the view's to say in words (`GL_FAILED`), never the
    app's end: an exception on the GL thread has nothing above it, so every call in from it (a context made, a surface changed, the trim before a pause) is
    guarded; and once said it stands, whatever the tileset does after. A tile the GPU has no room for is
    left undrawn without a word, and tried again once something is let go of. **A frame goes only to the view that is the session's** (`frame(…, view)`): a GL
    view going as its activity is made again draws on its own thread a while, and took the tiles let go of (whose buffers it alone then freed) and the imagery
    meant for the new view's GPU. A device whose EGL lists no OpenGL ES 3.0 surface is told so before the view is made (`SceneConfigChooser.onDevice`):
    GLSurfaceView can only throw on its thread, which ends the app.
  - **Its surface is above the map's, under the window** (`setZOrderMediaOverlay`): MapLibre draws into a surface of its own, and two plain ones have no order.
    Only a device that says it has OpenGL ES 3.0 makes the view (`canDraw3D`); one without is told so in words.
  - **The fingers move it as Cesium's touch controls move the web's** (`TouchGestures` over `CameraMoves`): one finger drags the ground under it, a pinch goes
    toward what is between the fingers, a twist turns about the middle, two fingers up or down tilt at Cesium's rate (`TILT_PER_HEIGHT`, about 0.44π for the
    view's height: its pinch reads the fingers' height at a quarter), never past level or below the ground, and a finger on a flight ends it where it got to. A
    screen reader has the same as the scene's actions, a step at a time about the middle (`SceneStep`: 15° a turn, 10° a tilt, half as near again), and hears
    the scene said again as it is drawn and after each step (a polite live region). A pinch never pulls a camera that is further off than the 30 km limit in to
    it (*Routes* frames routes tens of kilometres across from further, and Cesium has no limit, `maximumZoomDistance`): it may only bring it nearer. The ground
    the moves hold to is level: the lowest tenth of the points within 30 m of the LZ, from the tile with most of them that has a tenth of them on every side of
    it (`SceneGround`: their middle was in the trees round the clearing, ten metres up in the fixture's; a tile of py3dtiles' octree near the LZ holds one side
    of it, or only the canopy over it), the cloud's middle until one does, the terrain's height once it comes. **The opening view, *LZ* and *Routes* frame their
    sphere across the narrower side of the view** (the owner's decision of 2026-10-20; `SceneLook.framingRange`, the web's `framing.js`, held to `scene.json`'s
    `framing`): 2.2 radii back on a view at least as wide as tall, as the web always framed it, and on one narrower than tall (a phone held upright), where 2.2
    radii cut the LZ's sides off, further back until the sphere fits across the width with the same margin (Cesium's field of view runs across the wider side).
    The opening view is framed at the first frame, for that frame's shape, and a turn of the phone moves no camera, as a resize moves none on the web. *LZ*
    flies back to it, framed for the shape the view has then, in 1.2 s, the compass to north about the middle in 0.6 s, eased as Cesium eases a flight that does
    not come down from high up (`QUINTIC_IN_OUT`). **Not like the web**: the ground under a finger is that level, not the picked surface; a pinch also drags
    what is between the fingers, which Cesium's does not, and zooms by the spread (the distance over the spread's ratio), where Cesium's moves five times the
    distance by a quarter of the spread's change over the view's height, at most a tenth a frame (`ScreenSpaceCameraController`): the same where the fingers are
    about four-fifths of the view's height apart, faster nearer together (four times at 150 points); a twist and a tilt turn about the ground at the middle of
    the view, where Cesium's turn about the ground under where the pinch began, and a gesture is a tilt or a pinch, never both at once as Cesium's can be;
    nothing drifts on after the fingers lift (Cesium's inertia); four tiles are fetched at a time (Cesium's scheduler, 18); and a tile that met no connection is
    asked for again 5 s and 10 s after it failed (Cesium never asks again; a phone loses its signal more often than a browser).
  - **The ground is Cesium's globe over the server's heightmaps** (`TerrainScene`, `TerrainMeshes`, V5): from level 0's two tiles down, a tile drawn while its error on the
    screen is under 2 pixels or its children in view are not all in hand (fetched together, coarse first; one out of view counts as ready and is not fetched, as
    Cesium's culled tile, so the ground at the view's edges asks the server nothing it will not draw); heights placed as Cesium places them, a row's first and last on
    the tile's edges (§15); skirts four times the level's error, at most a kilometre. **None is drawn where the server has none** (a 204: nothing there or under it),
    where the web lays the globe flat at sea level (§14). It is drawn first, then its depth is cleared and the points are drawn over it, as the web's Cesium does with
    `depthTestAgainstTerrain` off (the ground's own returns would sink into it). Its depth is logarithmic too, written per fragment. The heights in hand are kept
    (8 KB a tile) and each tile's mesh built on the GL thread when first drawn, a few a frame. **Not ported**: Cesium's fog, horizon culling, and an ancestor's
    heights upsampled while a tile's come; a tile wider than 6° is never culled (its box is too coarse). **Not like the web**: above the horizon the web shows
    Cesium's default sky, sun and atmosphere, and fog on far ground (`CesiumWidget`'s defaults, which `Viewer3D.jsx` turns none of off, its `backgroundColor`
    hidden behind them), where this scene is the flat `#05080d` and looks no flatter than 2° under level (§15: owner to decide); and a probe for ground that
    cannot be asked (no signal) says nothing, where the web takes any failure as no terrain.
  - **The imagery is the web's Mapbox satellite, laid on as Cesium lays it** (`ImageryTiling`, `ImageryScene`, `MapboxImagery`): Cesium's level for a terrain tile
    (pixels as far apart as its error, at its latitude nearest the equator: about a level coarser than the terrain), the tiles covering it but a sliver, each vertex's
    place worked in doubles so level 20 lands to the pixel, and the nearest coarser tile in hand laid on until a tile's own comes; where none is, the ground is a dark
    grey-green (**not like the web**, whose globe is plain blue). The 2D map's public token (`MapTokenSource`), in the URL as Mapbox wants it; a refusal (no token,
    401, 403, 404) is for good, a 429, a 5xx or no connection is asked again after a pause. Decoded off the GL thread as RGB 565, mipmapped, at most three put on the
    GPU a frame, 112 MB of textures held; a lost context fetches them again from the disk cache (`viewer3d-imagery` in the app's cache, 256 MB, as long as Mapbox
    says; like MapLibre's own tile cache it is not cleared at sign-out). The moves' level ground is the terrain's height at the LZ once a tile there is in hand.
    **Credited as the web's Cesium credits it** ("© Mapbox", its link to Mapbox's page about its maps, under the point size), as Mapbox's terms ask and the 2D map
    keeps its own on. **Not like the web** (cosmetic, for a device run to judge): the imagery is decoded as RGB 565 where Cesium keeps 8 bits a channel, and
    nothing is multisampled where Cesium asks WebGL 2 for 4×.
  - **The routes in 3D are the ones the 2D map draws** (`ShownRoutes`: the open set's, a pack's included), placed as the web's `useRouteScene` places them
    (`SceneHeights`, core-data): where to ask is worked out at once (`RouteScene3D.prepare`, every 50 m, coarser past 4,500 places, at six decimals, held to
    `viewer3d/routeScene.json` case by case, each distance in fdlibm as JavaScript's `Math` is, so the places asked are the web's to the bit), the server is asked
    (`POST /api/terrain/heights`, the places in its body) 400 ms after the routes last changed, and the shapes are placed when it answers; the routes last placed
    stay while the next answer comes, one that fails changes nothing, and the same routes ask nothing. **Not like the web**, which asks once: an ask that met no
    connection, or the server busy or down, is made again 5 s and then 10 s after it failed, as the tiles are; after that, or a failure that will not pass, the
    routes stand failed (`RoutesPlacing`) until they change or *Routes* is pressed. A route hidden on the map is not drawn. A named point is at its planned MSL plus the geoid (the altitude flown), a target at either end on the ground, an AGL point with no planned MSL at
    the ground plus it, and a height that cannot be known leaves a gap, never a guess. **Drawn as the web's entities** (`RouteGeometry`, `RouteGl`): each line 3
    dp wide in its colour, and again dashed at 60% where the point cloud hides it (a leg flown through the canopy shows dashed across it; the ground's depth is
    cleared before, as the web's `depthTestAgainstTerrain` off; the dash runs along the line's angle on the screen to the nearest 45°, as Cesium's
    `PolylineCommon` quantizes it); its curtain to the ground at a fifth of the colour; a white drop line from the ground to a named
    point more than a metre up; a white dot ringed in the route's colour (11 dp for a target, 8 otherwise, the ring 3 dp each side) and the label (its name,
    its MSL and the AGL measured, amber where that is more than 50 ft off the plan's), both in front of everything, the labels smaller past 1.5 km along
    Cesium's curve (`czm_nearFarScalar`: quickly at first, 90% at 2 km, 78% at 10 km, 55% at 60 km and beyond). Labels are painted by the platform (semibold 12
    points at the person's text size, on a dark box with square corners as Cesium's background has) and laid on as textures, so they stay on their points; a
    label is at most four lines, each cut to 320 points with an ellipsis, so its picture fits any GPU however long a name (a member of a pack can write any),
    a few are painted a frame, and one there is no memory for, or the GPU will not take, is left undrawn (a refused texture draws black). A route's colour is
    read as Cesium reads the web's (`RouteColors3D.read`: CSS names, `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `hsl()`, held to `routeScene.json`'s
    `colors`, recorded from Cesium itself), as the 2D map reads it. *Routes* frames every place the lines pass as *LZ* frames the core (Cesium's
    `BoundingSphere.fromPoints`, Ritter's or the box's, worked out in geocentric metres as Cesium does, held to the sphere Cesium itself gives for each case
    of `routeScene.json`; a sphere with no size from 100 m), and is there with none to frame, unavailable and saying why: the web's words with no route to
    draw, the app's own while the routes are placed, when they could not be or have no heights under them (where the web says "No visible routes to show"
    whatever the reason); a tap says it in a toast, where the web shows its title on hover, and asks again for routes that could not be placed. It is drawn
    in capitals, as the web's camera buttons are, and heard as the word. **Not like the web**: a dot is a hard-edged disc (Cesium smooths its edge over a pixel
    or so), and a line's segments meet without joins; at night the labels, dots and drop lines are the night palette's (red and its amber), never white; the
    curtains are flat, where the web's wall is lit as it faces the sun (0.5 to 1.5 of its colour), and are drawn before the hidden lines, so a hidden line is
    drawn over a curtain nearer the eye rather than behind it; a colour Cesium cannot read is the web's blue, where the web stops drawing its routes; and
    routes whose programs will not build, or that fail as they go on the GPU or are drawn, cost the routes on that context (what they had there let go
    of, the state they set put back; a new context tries them afresh), never the scene: on the owner's first device runs a route fault was the scene's.
  - **Held together** (V7): **the 2D map stops drawing while the 3D view covers it** (`EzpzMap(covered)`, `MapRendering`: MapLibre's view stopped as if its
    screen were in the back, told once each way with the lifecycle, and just as it was when the view closes; its stop, since its pause does nothing to its
    surface: MapLibre 13.6.1's `SurfaceViewMapRenderer.onPause` is the base renderer's empty one, and its `onStop` pauses the surface). **A point cloud's files are kept on the device**
    (`CachedTilesetFiles`, `viewer3d-tiles` in the app's cache) for the server's week (`Cache-Control: private, max-age` on the tileset route), at most 256 MB,
    the longest unused let go of first, each written whole and renamed into place, every key and path checked as the API client checks them; anything wrong
    with the cache is a fetch, never a failure; and **cleared at sign-out** with the shared exports (`TilesetCacheCleaner`, `AppViewModel`): a tileset is a
    place, and a file fetched across the clear is not kept, nor the folders named for its key, which go with their last file. **Going to the back** (the
    activity pausing) the scene lets go of the points and textures the last frame did not draw (`SceneSession.trim`), and the GL thread frees their GPU copies
    in an event queued ahead of its pause, its context still current (`SceneRenderer.releaseTrimmed`: a paused thread draws no frame to free them in), so the
    system is less likely to end the app, and the view comes back as it was. A tile, a heightmap or an image there is no memory to read is left undrawn, and a
    frame there is no memory to draw is the view's failure, never the app's end. What closes it or follows is V2's: it
    always shows the open LZ/PZ, so closing, deleting or opening another, a sign-out and a pack left close it; a target moved (someone else's change in a pack,
    an undo) looks again at the new place, a new name or its being saved is followed, a target taken away closes it, and a finished pack's stays open.
  - **What the renderer asks of Android's GL binding is held in a JVM test** (`SceneRendererTest`, over `GlBinding`'s shadows of `GLES20` and `GLES30`,
    which refuse what the binding's JNI refuses: a scene with no routes, with routes, with the ground and imagery, a route fault, a bitmap refused, a
    context lost); what the driver makes of it is not.
  - **Not verifiable here:** anything GL draws (the labels' night colours and size, the dashes, the credit over the imagery), gestures on a real screen and the
    edge bands, the build service end to end (`LiveViewer3dTest` runs the real routes and the real queue with a stand-in build), Mapbox imagery with the app's
    token on a device, that MapLibre's view keeps (or makes again) its context through its stop, TalkBack's live regions, and the memory used.
- **The brief on a tablet** (B8; `core-brief`: `BriefEdits`, `StripGrid`; `core-data`: `BriefKeeper`, `BriefSources`, `BriefSession`, `BriefPlans`, `BriefWords`;
  `feature-brief`; `app`: `MapHome`, `TabletFrame`'s rail, `LastBriefPreferences`, `AppViewModel`). The design's Android tablet boards *brief setup* and *review and
  share*: the rail's *Brief* (a tablet's frame, `mission_brief` only) opens a dialog nearly the window over the plan, the mission flow and constants, the details a
  paragraph at a time with the script of the step being filled, and the review over the PDF the server prints, with *Save to device* (the system's create-document
  picker) and *Share PDF*. What is briefed is core-brief's `CompileBrief`, from the open pack's LZ/PZs and route sets or, outside one, the LZ/PZs and route sets open in
  this session, as on the web. Things to keep true, most of them found by the step's independent reviews:
  - **A change is kept as what it does, not as the document it made** (`BriefKeeper`: a transform, core-brief's `BriefEdits`), and laid over the brief as it is when
    it is written or sent: a version another device or member made meanwhile is kept, and only this person's change goes over it. What the person sees meanwhile is
    the brief with their changes laid over it. A change that throws changes nothing (the screens make their changes on the main thread).
  - **In a pack** changes go in the order they were made, as one batch worked out against the pack's brief then (`PackDiff`, the brief's own batches), never by which
    send ran first, and `flush` sends what waits itself. A change the pack's rules call no change sends nothing (a read-only pack refuses an empty batch). What the pack
    cannot take now (still loading, paused for the account's sake) waits, shown, and goes when it can, the pause said once (owner decision 4, as the editors hold back a
    change); what it never will (closed, gone) is kept in the Library as "NAME (my edits)" and said; one the device cannot keep (the disk full) is said once and tried
    again with the next change. **Not like the web**, which drops all of these.
  - **In the Library** the brief is read again each time the setup opens (a version a sync brought is the one shown; nothing is shown while it is read), and a save
    lays its waiting changes over the record as it is then. A record not on the device (deleted elsewhere, or not synced here yet) is started again under a new
    identity at the next save; the one remembered stays remembered (a shared tablet's pull may bring it). One slot for each account (`LastBriefPreferences`).
  - **The session closes itself when the account changes** (`BriefSession.opened`; the view model lets its PDF go with it), forgets the weather, and drops a change made
    after that (a field written as the setup leaves the screen is never the next person's). It compiles only while open, off the main thread; a compile that throws is
    said (`failed`), never the end of the app; nothing is shown while the brief or the pack is being read. The weather is keyed by the places the brief asks about (a
    refresh asks for the same ones), and a refresh with no answer at all keeps what was there with its age (the web the same).
  - **What it is built from** (`BriefSources`): a pack narrowed to its items' documents by instance (presence frames compile nothing); outside a pack the open documents'
    edits not yet written, each LZ/PZ with the slope measured over it in this process (`AnalysisService.slopes`) while its boundary is the one it was measured for,
    as the web's runtime `terrainData` (a pack's items carry none, there as here; `compile.json`'s slope case holds the call), and a mission's routes read from its file,
    kept while its file and colours are the same and only while it is open.
  - **The screens**: every row keyed by what it holds (a step, an answer, a procedure's field, a constant, a flow entry, a rally point; §14). `DraftField` commits the
    text as it is now on a pause (600 ms), on leaving it, on Done, as it leaves the screen and as the app goes to the back; a field the person did not type in commits
    nothing; a script reset discards what was typed in its editor. Narrower than 1,000 dp (a tablet split, a foldable at 840) the script goes under the answers, the
    side columns narrow and the format goes under the title. The PDF is made once the brief has been still for 300 ms (3 s at most), so a field left as the review
    shows is in it; Share and Save make it again when the brief changed since.
  - **The PDF on the device**: written to `cache/brief/` for the platform's renderer, deleted when it is closed, any left by an ended process deleted when the next opens,
    all at sign-out (`RendererBriefPdfs.clear`); cleaned up whatever fails, an `Error` included (§14). One page is drawn at a time behind a coroutine lock, closing
    waits its turn on its own thread, never the main one, and a page it cannot draw is none. Thumbnails are drawn as they scroll into sight. *Share PDF* writes the
    file off the main thread; *Save to device* with no PDF to write deletes the empty file the picker made and says so.
  - **The shell writes the brief's waiting change** as the app goes to the back and before a sign-out stops packs (`BriefFlush`), as it writes the open documents.
  - **Not verifiable here:** the platform's renderer drawing a real PDF (Robolectric's cannot open one: `RendererBriefPdfsTest` skips that case), the share sheet and
    the picker, a print from the real Gotenberg on a device, the dialog over the real map, and TalkBack. Each wants a device run.
- **The sandtable on a tablet** (B9; `core-brief`: `SandtableSteps`; `feature-brief`: `SandtableFrame`, `SandtableScene`, `SandtableOverlay`, `SandtableMarksLayer`,
  `SandtableCamera`, `SandtableFlights`, `SandtableHandover`, `BriefViewModel`'s `present` and its steps; `feature-map`: `MapScreen(controls)`,
  `MapHost.flyTo(at, zoom, bearing, durationMs)`, `MapHost.attach(map, initial)`; `core-designsystem`: `ToastHost(actions)`; `app`: `TabletFrame`'s `presentation`,
  `MapHome`). The web's live sandtable (docs/MISSION_BRIEF.md §2.8, `Sandtable.jsx`, `SandtableLayer.jsx`): *Present sandtable* closes the setup and walks the brief's
  steps on the tablet's map, a bar across the top in the frame's place (Presenting, what is briefed, UNCLASSIFIED, *Step n of N*, *End brief*), the steps down the side (over the map, and folded at
  the start, narrower than 850 dp, as the web's below 850 px), the mission constants at the map's top right and the script card at its foot (who briefs it, its
  planned time, the script with its write-ins, its facts, ← and → 56 dp across, a progress bar). Things to keep true, most of them found by the step's reviews:
  - **Where it starts**: *Present sandtable* in the setup's foot on every step. **Not like the web**, whose setup offers it at the review only: the web's Brief menu
    presents without the setup, and Android's rail opens the setup itself (B8), so presenting never waits on the review's PDF (a server print).
  - **One brief, one session** (`BriefSession`): the setup and the sandtable share it. Presenting keeps it open (the brief compiles as the plan changes under it);
    ending closes it; an open waits for a close still writing (`afterClosing`), and is dropped if a close comes after it (`generation`: the window became a
    phone's, the account went). A PDF that finishes opening as it is let go (the sandtable begun) is closed, not left in the cache.
  - **The map never moves for it**: the sandtable is drawn over the map where the tablet's frame has it (`TabletFrame`'s `presentation`), the rail, the panes and
    the band put away around it so it has the window. Moving it (as movable content) would take MapLibre's surface off the screen and put it back at every start
    and end, and a map made again would add its sources twice and end the app. The bar and the steps lie over the map as covers, so what is under them is never
    seen and each figure is fitted clear of them; where the map shows, a touch goes through to it.
  - **The steps are the web's** (`SandtableSteps`, held strictly to `sandtable.json`), **followed by id**: a step that goes from the brief (someone else's edit)
    leaves the presenter at its place, on the step now there, which is then followed (`follow`). Nothing is shown or flown to while the brief is read.
  - **What the map shows is plain data** (`SandtableScene`, the web's `attachSandtable`, which draws as the PDF does): the lines, the LZ/PZ in focus's boundary and the
    dots go to MapLibre as GeoJSON (`SandtableOverlay`, compile-only: lines by sort key, then the boundary, then the dots, each feature carrying its own style); the
    squares, rally triangles and arrows and the words are Compose over the map (`SandtableMarksLayer`, the style having no glyphs), stacked as Leaflet stacks them
    (place plus offset), hidden from a screen reader (the card says the step), in the web's soft dark glow (one shadow a pass, and a thin edge). Sizes are the web's
    pixels as dp; MapLibre draws a dot's ring outside its radius, so the fill is half a ring smaller (`fillRadius`). **Not like the web**: a route colour that is not
    `#RRGGBB` is the figures' route colour (the PDF's rule), and every line is under the boundary and every dot over both, where the web interleaves them route by route.
  - **The flight** (`SandtableCamera.fit`): Leaflet's `flyToBounds` with the insets as padding, worked out as Leaflet does and held to Leaflet's own answer
    (`sandtable-fit.json`, 108 views: the largest zoom on a tenth of a level, the web's `zoomSnap`; MapLibre's zoom is Leaflet's less one, so the web's `maxZoom: 17`
    is 16, and never below MapLibre's 0), clear of the bar, the steps, the card, the constants box and the navigation bar (`SandtableBoxes`, `fitInsets` in dp,
    held to the same file), north up, over 0.9 s, or at once when the device's animations are off, once the map is made. Once a step, and again only if a step
    with no area gets one (the presenter may have moved the map to point at something); a step is noted as flown once its flight is asked for, so one cut short
    (its area gone and back while it waited) is flown again. The map has no padding while it runs (`MapScreen(controls = false)`: no crosshair, buttons, readout or
    tools). The view it had is given back at the end with no flight (`jumpTo` ends a flight under way, so the frame's padding coming back does not carry it on), kept
    through the activity being made again (`rememberSaveable`: the text size or the language changed). A turn of the tablet, a keyboard or a presenter's clicker
    coming, going or sleeping do not make it again (`configChanges` has `keyboard` and `navigation` since B9, `ManifestConfigTest`).
  - **It ends with its workspace in the same composition** (`MapHome`: presented only while the open pack is the one it was begun in, `SandtableUi.pack`), as the
    web's does, and a flight asked for once it has ended while its frame is still going (the person's own LZ/PZ opening as a pack is left) waits for the view it
    gives back, then goes (`SandtableHandover`): the map ends on the new workspace's LZ/PZ. It also ends with *End brief*, Back or Escape, when the account changes
    (the session closes itself), and when the window becomes a phone's (said in a toast) or the feature goes (`close`).
  - **Nothing takes an edit**: the rail, the panes and the band are put away (`Modifier.hidden`, composed, as they were), the planning layers are drawn empty and
    their labels, the others' pointers and the GPS dot are not drawn (all come back as they were), a tap, a long press and a drag do nothing, an LZ/PZ opened or a
    place found meanwhile is not flown to, then or after (the web's paused `MapUpdater`), and the map counts as covered (this person's pointer leaves the others' maps;
    the Pack panel is not being looked at). Toasts go to the top of the map and offer no action (`ToastHost(actions = false)`): their actions open planning flows.
    **Not like the web**, which ignores a file dropped on the map then: a file another app opens with this one waits for the import review until it ends.
  - **The screens**: the window is laid out left to right whatever the language, as the map is; the steps list is 56 dp rows, only the step shown selected (a
    "selected" of false on every other is read as "not selected" on each), brought into sight by as little as it takes; in a narrow window the steps fold when it
    narrows and Back folds them before it ends the brief; narrower than 850 dp or at large text the bar keeps to its essentials (*Steps* its icon, the step's place
    in the card's way); the card is the web's height (48% of the map, 400 dp at most) but never under 220 dp, everything but its arrows scrolls, with a fade while
    there is more, and each step starts at its top (**not like the web**, whose card keeps the last step's scroll); the progress bar is in the palette's colours, the
    step shown taller; the card and the constants box are given their content colour (§14, *A colour made translucent*).
  - **Keys** (a presenter's clicker sends Page Up and Page Down): the arrows and those step, Home and End go to the ends, the space bar and Enter step unless a focused
    button takes them, Escape ends; a key with Ctrl, Alt or Meta does nothing. The frame is a focus target (no stop of its own for a screen reader) and takes the
    focus as it begins. When it ends the focus goes back to the rail's *Brief* (`TabletFrame`), as the web's goes back to its Brief button (the setup it was begun
    from is gone by then); a button takes the focus only from someone using keys, so after a touch nothing is focused. The card says the web's "← → keys" only
    while a keyboard is attached and open (`KeysHint`; a clicker has no arrows, a bare touchscreen no keys), and not in a narrow window.
  - **The bar names the open pack** as the web's does (`packs.meta?.name`): `MapHome` tells the view model the open pack's name with its uuid (`workspaceIs`), so the
    name shows while the pack's brief is still read and follows a rename, which does not end the sandtable. The chips and labels keep the app's sentence case
    (*Presenting*, *Steps · 18*, *Mission constants*), where the web's CSS upper-cases them.
  - **Not verifiable here:** the flights and the figure on a real map (GL; the pictures, `SandtableScreenTest`, draw the scene over a stand-in as MapLibre would), a
    presenter's clicker, TalkBack, and the frame on a real tablet's insets. Each wants a device run.
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
  - Deleting an own profile that is the mission aircraft falls back to the UH-60L; diagrams placed with it then draw and measure its aircraft as the
    mission aircraft, since an aircraft carries only its airframe's slug (§15).
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
    changes its symbol and labels and nothing else** (an older image unit keeps its `path`), **and of those only what the person changed** (`UnitDraft.patchOver`,
    from what the builder showed): a part changed under the open builder (someone else's change in a mission pack, an undo) is shown, and what was typed into
    another part is kept (`UnitDraft.following`). As on the web, the builder knows five parts (affiliation, function, echelon, the two labels): a unit made
    with another dimension or status is shown as ground and present once opened, and is made so when a part of its symbol is changed; **not like the web**,
    whose builder writes all three, a label changed alone keeps its symbol code. A function with no name is kept and shown as `Other (code)`. The web's **threat presets are 14 characters**, one short of a SIDC, and milsymbol draws them anyway; they are kept
    as they are because a threat's SIDC is what an AMPS `.ths` carries.
  - **A unit that is an old image** (no `sidc`) is listed as "Unit (image)" and drawn as the plain marker; it is not offered a symbol until the builder is applied.
- **What cannot be seen here:** the map itself (no GPU: only the overlays are drawn, over a stand-in), and whether R8 strips something
  the libraries need at run time (a release build links and an unsigned APK is made, but nothing runs it). An early device run of a
  release build is the first thing to do.
- **A clickable row merges its text** (`ListRow`, and anything `clickable`): `onNodeWithText` finds the whole row, so a test that measures one line of it (its bounds, its
  `TextLayoutResult`) asks with `useUnmergedTree = true`, or it measures the row (two lines measured "the same" until it did).
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

**Rules that carry over unchanged:** unclassified only; threats are saved and synced like other records once *T* is built (§2; until then they stay on the device, and an explicit online viewshed/KMZ/QR action may send coordinates transiently); no secret in
the app binary; no new dependency beyond what the plan's owner-approved list covers (ask first for anything else).
