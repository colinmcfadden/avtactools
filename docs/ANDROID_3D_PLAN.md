# Android 3D LZ view — plan

Status (2026-10-20): **V0, V1, V3 and V2 done** (branch `claude/android-3d-view-e38b76`; V3 was built before V1, since the build flow's
probe uses its tiling); V4 next.
The owner chose the native renderer (A) and the full screen's details on 2026-10-20 (§9). Each step is built as the screens
and the brief were: implemented with its tests, reviewed independently (parity with the web, lifecycle and concurrency, the screens), its
findings fixed with tests, committed only with the full build green and AGENTS.md and `docs/HANDOFF.md` updated in the same commit. **Where
this plan disagrees with the code, the code wins** (AGENTS.md §16).

Sources: AGENTS.md §4 (*3D LZ view*), §9 (*3D loading speed*), §13 (*Geodesy*, *LiDAR*), §14; `docs/NATIVE_APPS_PLAN.md` (item 20, the
*3D view* row of *Technology decisions*, *3D LiDAR view*, the 3D memory budget, the renderer tests); `docs/3D_PLANNING_GRAPHICS_PLAN.md`;
the design boards that show 3D (`docs/native-design/outlines/AP-LzPlan.md`, `AP-SaveLz.md`, `AP-PackLive.md`, `AT-Finished.md`; there is
no board of the 3D view itself); the web's `frontend/src/feature/viewer3d/` and `feature/shell/LzPanel.jsx`; the server's
`routes/lidar_routes.py`, `lidar_builder.py`, `terrain_tiles.py`, `routes/terrain_routes.py`.

---

## 1. What it is

The web's 3D LZ view on Android: the LiDAR point cloud of one LZ/PZ over the server's DEM terrain and satellite imagery, the routes drawn
on the 2D map at their planned MSL with curtains to the ground and labels, a compass that turns and tilts with the camera, and the
camera's *LZ* and *Routes* framings. Opening it on an LZ/PZ with no point cloud asks the server to build one and says how the build is
going; a saved LZ/PZ's build finishes even if the view closes; and an LZ/PZ that becomes saved asks for its build in the background, so it
is ready when someone opens it (the web's `useBuildOnSave`).

**The owner's requirement (2026-10-20):** going 3D takes the whole screen, phone and tablet alike, over the app's frame, with a bar at the
top (its title and a close button). Closing it, by the bar's close button or system Back, returns to the map exactly as it was: the
camera, the sheet or the panes, the panel and its page, what is held, what was typed.

**Not in this plan**, as on the web today: editing in 3D; the LZ/PZ's own graphics in 3D (`3D_PLANNING_GRAPHICS_PLAN.md`, Phase 2);
clearance colouring (Phase 3); threats in 3D (Phase 4); and 3D offline, which needs built tilesets in a map pack (NATIVE_APPS_PLAN.md,
backend change 9, with the map packs of P3/P4).

---

## 2. What the web does (the reference)

- **Where it opens** (`LzPanel.jsx`): a *3D* button on the open LZ/PZ's card and on each other open LZ/PZ's compact card, disabled while
  it has no target. One on another card makes that LZ/PZ the open one first: the window always shows the open one. The design boards put
  it in the same places on Android (`[Save] [3D]` on the card and the plan page; a 3D icon button on each *Also open* row and each pack
  row; `[3D] [Save a copy]` in a finished pack). Android's card has *Plan* where the boards have *3D* (ANDROID_SCREENS_PLAN.md S3).
- **Finding or building the point cloud** (`useLidarTileset.js`): `POST /api/lidar/resolve {lat, lon}`; available → `url` and an optional
  `contextUrl` (a thinned ring round the core); not built and `canBuild` → `POST /api/lidar/build {lat, lon, keep, watcher}`, then
  `GET /api/lidar/build/<key>?watcher=…[&keep=1]` every 3 s (stage, place in the queue, seconds elapsed) until it is `done` or `failed`;
  five failed polls in a row end it; a 404 or `cancelled` asks again, twice at most; `DELETE /api/lidar/build/<key>?watcher=…` when the
  window closes. No build service → *Not generated*, with the command an operator would run and *Check again*. Coordinates only ever in
  POST bodies; progress by an opaque key. The watcher is one opaque id per browser tab, and the builds a tab was waiting on are released
  when the page loads again (`releaseAbandonedBuilds`).
- **Build on save** (`useBuildOnSave.js`): an LZ/PZ saved in this session (not one that arrived saved) asks for a kept build, with no
  watcher. A pack's LZ/PZ is never "saved" in this sense (`packLz.js` gives it `savedId: null`), so it is neither kept nor built on save.
- **The scene** (`Viewer3D.jsx`): background `#05080d`; Mapbox satellite imagery (`satellite-v9`, 1024-pixel tiles, to level 20) on
  terrain from `GET /api/terrain/heightmap/<level>/<x>/<y>` (65 × 65 little-endian Int16 metres above the ellipsoid, Cesium's geographic
  tiling, two tiles at level 0; 204 where there is no DEM); depth testing against terrain **off** (points sit on the surface, and a
  route at 50 ft AGL hides under coarse distant terrain: `3D_PLANNING_GRAPHICS_PLAN.md`, the depth-test result); the core tileset at
  screen-space error 2, the ring at 8; point-cloud shading with attenuation, eye-dome lighting at strength 0.5 and radius 0.5 pixel, the
  largest point the size the person chose (2 to 16, default 5, remembered); the camera opens at heading −30° (it looks north-north-west),
  pitch −35°, at 2.2 times the core's bounding-sphere radius, then is free.
- **Routes** (`routeScene.js`, `routeEntities.js`, `useRouteScene.js`): every route the 2D map draws, sampled every 50 m (coarser past
  4,500 samples), with ground and geoid from `POST /api/terrain/heights` asked 400 ms after the routes last changed, the last placed
  routes kept while the next answer is on its way. A named point is at the planner's MSL plus the geoid (the AMPS `CmdAlt`); a target at
  either end is on the ground; an AGL point with no planned MSL is at the ground plus its AGL; one whose height cannot be known is left
  out, never guessed. The line is 3 pixels in the route's colour, dashed at 60% where the point cloud hides it; a curtain to the ground at
  20%; a white drop line; a white dot ringed in the route's colour (11 pixels for a target, 8 otherwise), always in front; and the label
  `NAME` / `1,850' MSL · 310' AGL` (amber, with `planned 300' AGL`, where the AGL measured disagrees by more than 50 ft;
  `On the ground · 1,234' MSL` at a touchdown; `310' AGL · no planned MSL`), always in front and smaller with distance (full size at
  1.5 km, 0.55 at 60 km). *LZ* frames the core, *Routes* the routes, each in 1.2 s.
- **The compass** (`compass.js`, `Compass3D.jsx`): the dial turned so its N points north and tilted with the view up to 62°, the heading
  under it as `330°T`; a tap turns the view to face north about the ground at the middle of the screen in 0.6 s.
- **Notices**: *No terrain here* when the target's level-12 heightmap tile is empty, because without terrain the points float over
  nothing, which looks like a rendering fault.

---

## 3. The renderer: two ways (the owner's choice, §9)

**A. Native OpenGL ES** (the native plan's choice, `NATIVE_APPS_PLAN.md`, *Technology decisions*). A renderer of our own in one
east-north-up frame on the LZ: it streams today's `.pnts` tilesets (py3dtiles 12.1.1's layout, unchanged), meshes the heightmap tiles,
drapes the imagery and draws the routes, all with the platform's OpenGL ES 3.0 (no library). Cesium's rules are ported where the look
depends on them: the screen-space error that picks tiles (`Cesium3DTile.getScreenSpaceError`), the attenuation that sizes points
(`PointCloudStylingStageVS.glsl`: geometric error over depth times the buffer height over the frustum's SSE denominator, at most the
chosen size times the pixel ratio), and the eye-dome lighting (`PointCloudEyeDomeLighting.glsl`: four neighbours at the radius, the mean
of the log₂ depth differences, shade `exp(−response × 300 × strength)`). What it buys: no dependency, memory held by a point budget (the
plan's 600 MB), offline packs read straight from disk later, iOS's Metal port follows the same design. What it costs: the larger build
(V3 to V7 below), and a look that is close to the web's rather than identical, tuned from the owner's screenshots, since nothing GL can
be seen from an agent session.

**B. Cesium in a WebView.** The web's own engine and drawing code (`Viewer3D`, `routeEntities`, the compass) in an embedded browser,
the same Cesium version as the web (1.144), bundled with the app. What it buys: the web's look exactly, fewer steps, and iOS could reuse
it. What it costs: a new major dependency (Cesium, Apache 2.0) and about 8 MB more to download (22 MB installed, before pruning); memory
harder to hold to the budget (a browser engine plus Cesium's caches, its tileset cache alone defaulting to 512 MB until set lower); a
JavaScript build step in the Android build (Cesium copied from `frontend/node_modules`, so CI's Android job needs Node); and offline
later through request interception. The token would stay out of the page: every request the page makes is answered by the app's own
HTTP client (`shouldInterceptRequest`).

**Rejected again:** Cesium Native (a C++ tile loader with no renderer: the GL work is still ours) and a general 3D engine such as
Filament (meshes and lighting come free, but point attenuation, eye-dome lighting, tile streaming and terrain are still ours, for a large
dependency).

The steps below are written for **A**. For **B**, V3 to V6 become three: bundle Cesium and the page; the bridge (the scene's inputs in,
the camera's pose out, requests through the app's client); the routes and compass in the page. V0's route-scene and compass fixtures are
then not needed (the page runs the web's code), and V7 is the same.

---

## 4. The shape of it on Android (A)

- **`core-scene3d`** (new, pure Kotlin, JUnit 5): the local frame (WGS84 geodetic ↔ ECEF ↔ east-north-up at the LZ, in doubles; vertex
  buffers in floats relative to it), the camera (pose, projection, frustum, the opening pose and a sphere's framing, gestures as camera
  moves), `TilesetReader` (`tileset.json`: boxes, spheres and regions, transforms, ADD and REPLACE, external tilesets) and `PntsReader`
  (`.pnts`: `POSITION`, `POSITION_QUANTIZED`, `RGB`, `RGBA`, `RGB565`, `RTC_CENTER`; a file is hostile input, every length bounded, as
  `SqliteReader` is), `TileSelection` (the traversal, frustum culling, a point budget, what to fetch first, what to drop), the terrain's
  tiling, its meshes and the imagery's texture coordinates, `RouteScene3D` (`routeScene.js`) and `CompassPose` (`compass.js`).
- **`core-network`**: typed calls for the six routes (§5, V1).
- **`core-data`**: `LidarBuilds` (`useLidarTileset.js` as a plain state machine: the watcher, keep, polling, releasing), `BuildOnSave`,
  and `SceneHeights` (`useRouteScene.js`: the debounced heights request, the last answer kept).
- **`feature-viewer3d`** (new, Android library): `Viewer3DPage` (the full-screen page: the bar, the states, the notices, the compass, the
  *LZ* and *Routes* buttons, the point size), `Viewer3DViewModel`, and the GL glue (a `GLSurfaceView` rendering only when something
  changed; the point, eye-dome, terrain, route and label passes), compile-only.
- **`app`** and **`feature-workspace`**: the page over both frames (`MapHome`, a small change: work on `feat/mission-brief` touches it and
  `TabletFrame` too), the *3D* buttons in `LzPanel.kt`, build on save from `DiagramsViewModel`.

---

## 5. Steps

| Step | What it delivers | How it is checked | Depends on |
|---|---|---|---|
| **V0** | **Done.** **The contract.** `contracts/openapi.yaml` describes the six routes the app will call: `POST /api/lidar/resolve`, `POST /api/lidar/build`, `GET` and `DELETE /api/lidar/build/<key>`, `GET /api/lidar/tilesets/<key>/<path>`, `GET /api/terrain/heightmap/<level>/<x>/<y>`, `POST /api/terrain/heights` (additive: no route changes). Their responses are recorded by `backend/tests/test_network_fixtures.py` from the server's own code (the tile store a temp directory and the build service stubbed, as `test_lidar_api.py` does; terrain over a synthetic GeoTIFF, as the terrain recordings are) and held by `test_openapi_contract.py`. The web writes the fixtures the ports are held to (`contracts/fixtures/viewer3d/`, from `frontend/src/contracts/viewer3dFixtures.test.js`): the route scene (`prepareRoutes` and `placeRoutes` for every case its test covers: hidden routes, fewer than two named points, touchdowns, AGL with no MSL, the 50 ft mismatch, missing ground, the sample cap, the labels' text), the compass pose, the terrain tile addressing (the probe's tile, a tile's bounds, which the server's `tile_bounds` agrees with) and the build flow (`useLidarTileset` driven through every answer the server can give, state by state). A small tileset made by py3dtiles 12.1.1 itself (the build image's version) from a synthetic point cloud, by a script, never edited by hand | backend pytest (the new recordings, the contract test); Jest (the new fixture test, which fails if a fixture is stale); the backend CI list gains any new import | — |
| **V1** | **Done.** *As built*: the live-server test runs the LiDAR routes and the build service's real queue in the server's process (a build there is the fixture tileset put in place); the terrain routes are held to their recordings only, since they need DEMs. The builds the last launch waited on are released as `LidarBuilds` is made, at the app's start, and a lookup waits for that at most 3 s. Every read is bounded (a tileset file 32 MB, a heightmap 8,450 bytes, an error page 64 KB), and the server's text for a failure is never shown. **The calls and the build flow, no screen.** `core-network`'s typed calls and DTOs (decoded strictly against the recordings in `DtoFixtureTest`; the heightmap as bytes, 204 as none; tileset files as bytes; heights with nulls kept), terrain requests behind the priority gate (they wait while an analysis, viewshed or export runs, as the web's `whenIdle`), tileset files not. `core-data`'s `LidarBuilds`, held to the build-flow fixture scenario by scenario (one watcher id per install, kept with the keys being waited on, so the next launch releases what the last one was waiting on), and `BuildOnSave`: an LZ/PZ that becomes saved in this session (named and analysed, the moment *Save* shows *Saved*), outside a pack, asks for a kept build; one opened already saved does not | JUnit and Robolectric; a live-server test against the real Flask routes with the builder stubbed | V0 |
| **V2** | **Done.** *As built*: the view model of the LZ/PZ panel holds which LZ/PZ is in 3D (always the open one; another opened, or this one closed, lets it go), and `MapHome` draws the page last over either frame, which it leaves composed and out of reach; the page takes every touch and Back, draws the frame's toasts over itself and closes when the Library opens. *Reviewed*: Back went to a tablet's pane made again under the page (a turn, a foldable), a keyboard reached the frame under it, its lookup went on after the map left (a sign-out), a failed build was not said to a screen reader, and 3D asked for during a slow open was not withdrawn by a close; each fixed with a test, and a test records every state so the view never shows another LZ/PZ even for a frame. **The full-screen page and its ways in.** `feature-viewer3d`'s page over both frames (the bar with the title and the close button; Back; the system bars as §9 decides), drawn on top of a map left composed and untouched, and the states in the web's words: *Looking up*, *Building point cloud* (the stage in words, the place in the queue, the clock, what closing does, saved or not), *Not generated* (the server cannot build: **not like the web**, no operator's command, which a crew cannot run from a phone; *Check again*), *Build failed* / *Lookup failed* with *Retry*, and *No terrain here*. *3D* on the open LZ/PZ's card beside *Plan*, on its plan page beside *Save*, on each *Also open* row and each pack row (an icon button), disabled with no target; one on a row opens that LZ/PZ first. The scene's area says the point cloud is ready until V4 draws it | Robolectric: open and close leave the camera, sheet, panel, page, selection and typed text as they were; Back closes it first; a closed or deleted LZ/PZ, another account or a pack left closes it. Roborazzi pictures of every state on a 360 and a 412 dp phone and a tablet, in the three palettes and at 1.3 and 2.0 text | V1 |
| **V3** | **Done** (before V1). *As built*: Cesium's base traversal, never skipping levels; the dynamic screen-space error, request volumes and the memory-adjusted error are not ported, and a point budget stands for the last (`withinBudget`: the error raised by half again, up to eight times). The earth's frame is held to PROJ (`contracts/fixtures/viewer3d/geodesy.json`, by `contracts/scripts/geodesy_fixtures.py`). **Moved to V4**: the LRU of decoded tiles and dropping what leaves the view, which go with the loader that fills them and the GL buffers they hold. *Reviewed*: tile errors were not scaled by
their frames (Cesium's `updateGeometricErrorScale`; py3dtiles scales an octree's frame by 10 or 100, so the ring and the cores stopped several levels short of the
web), 10 KB of nested brackets overflowed the JSON parser's stack in both readers, the camera's heading looking straight down was the arcseconds between two
frames' ups, a failed tile was asked for every frame, an empty tile was not walked to fetch what is under it, and seven rules had no test that failed without
them; each fixed with a test, and a mutation run of 32 undoes every fix and fails a test for each. **Reading and choosing tiles.** `core-scene3d`: the local frame, `TilesetReader`, `PntsReader`, `TileSelection` (Cesium's screen-space error, ADD and REPLACE refinement, the core at 2 and the ring at 8, the camera's frustum, a point budget of about 3 million on a phone, an LRU of decoded tiles held to a byte budget, the nearest and coarsest fetched first, what leaves the view dropped) | JUnit over V0's tileset and hostile files cut and corrupted (as `SqliteHostileFileTest`); Cesium's rules each cited and tested on cases worked by hand; mutation-checked | V0 |
| **V4** | **Points on the screen.** The GL renderer: points sized by attenuation, eye-dome lighting, `#05080d`, rendering only when the camera moves or a tile arrives; the camera's opening pose and the *LZ* framing; gestures as Cesium's touch controls are (one finger pans, a pinch zooms, two fingers up or down tilt, a twist turns); the compass (`CompassPose`, held to V0's fixture; drawn in Compose; a tap faces north about the middle in 0.6 s); the point size (2 to 16, remembered on the device); a lost GL context rebuilt; a device with no OpenGL ES 3.0 told so in words | the pure parts in JUnit (camera, gestures as camera moves, the compass); the GL compile-only. **Owner device run 1**: the cloud's look beside the web's on the same LZ (screenshots) | V2, V3 |
| **V5** | **Terrain and imagery.** Heightmap tiles chosen by screen-space error round the camera, meshed in the local frame with skirts against cracks, **none drawn where the server has none** (no sea-level fill: AGENTS.md §14); Mapbox satellite (the 2D map's token) draped by each vertex's place in the imagery's tiles, to level 20; a private HTTP cache for both | JUnit: tile addressing (V0's fixture), a mesh's vertices from a heightmap, gaps left as gaps, texture coordinates. **Owner device run 2**: ground, imagery and points agree (the points sit on the ground, as the shared geoid correction should make them) | V4 |
| **V6** | **Routes in 3D.** `RouteScene3D` held to V0's fixture; `SceneHeights` (400 ms after the routes last changed, the last answer kept); the lines, their dashed hidden parts (the point cloud's depth), the curtains, the drop lines, the dots and the labels (drawn in GL from text the platform lays out, so they stay on their points), smaller with distance; *Routes* framing. Which routes: those the 2D map draws (the open set's visible routes, a pack's included) | JUnit for the geometry and the labels' text. **Owner device run 3**: a route at its planned MSL with its curtain meeting the ground, its labels matching the route's plan | V5 |
| **V7** | **Held together.** The GL surface paused in the back and freed when 3D closes; MapLibre not drawing while it is covered; the point budget by the device's memory class; tiles cached in memory to a budget and on disk privately; an LZ/PZ closed, deleted or replaced under it, a sign-out or another account, a pack left or finished: 3D closes or follows, never shows another account's place; the build released on close, and the last launch's builds at the next. Three independent reviews, their findings fixed | JUnit and Robolectric for all of it. **Owner device run 4**: memory in Android Studio's profiler under 600 MB, the frame rate, a long session | V6 |

The web and the backend lead (V0); Android follows. V1 and V3 can run side by side once V0 is in. The step after this plan, should
the owner want it: the LZ/PZ's graphics in 3D (`3D_PLANNING_GRAPHICS_PLAN.md`, Phase 2), web first.

---

## 6. Things to keep true

- **Coordinates only in POST bodies, progress by an opaque key**, nothing that logs where an LZ is. The token never in a URL.
- **Never invent a height.** A heightmap tile the server has none for is not drawn; a route sample with no height is a gap; a label with
  no ground says `AGL n/a`. Heights are ellipsoidal end to end: MSL plus the server's `geoidM`, never a constant −30.35.
- **The web is the reference and the fixtures are the contract**: every pure port is held to a fixture the web writes, and a change to
  the look or a rule is made on the web first.
- **Numbers by hand** (`1,850'`, `330°T`), no locale (AGENTS.md §17).
- **Heavy work first**: terrain requests wait while an analysis, viewshed or export runs; tileset files do not (static, cheap).
- **Battery and memory**: nothing renders unless something changed, nothing at all while 3D is closed or the app is in the back, and the
  GL resources are freed when 3D closes. Under 600 MB in 3D, held by the point budget.
- **The map is left as it was**: 3D never moves the map's camera, the sheet, the panel or what is held, and closing it puts nothing back
  because nothing was taken.

---

## 7. What cannot be verified in an agent session

Anything OpenGL draws (there is no GPU here, and Roborazzi draws no GL): the point cloud's look, the terrain and imagery, the routes and
their labels in 3D, frame rate and GPU memory. Gestures on a real screen. The build service end to end (it runs on the VM). Mapbox
imagery with the app's token on a device. Each wants the owner's device run, named in its step.

---

## 8. Cost

Large for A: of the order of the mission-pack screens. Most of the risk is in V4 and V5 (a renderer written without seeing it), which is
why each ends with a device run and why everything that decides what is drawn is plain, tested data. B is about half, with its cost in
memory and size rather than time.

---

## 9. The owner's decisions

Asked and answered 2026-10-20:

1. **The renderer: native OpenGL ES (A).** Our own renderer, no new dependency; Cesium in a WebView is not taken.
2. **The system bars stay, see-through.** The scene is drawn under the status and navigation bars, edge to edge, and the bar's title and
   close button sit below the status bar. Hiding them would change the window's insets, and the map under 3D would shift and settle back
   as 3D closed.
3. **The title is `3D · NAME`**: the LZ/PZ's name as the LZ/PZ panel shows it, on one line, cut with an ellipsis if it is too long, no
   grid.

Decisions made for this plan (the owner may overturn any): *3D* where the web and the design boards put it; opening 3D from a row opens
that LZ/PZ first, as on the web; a pack's LZ/PZ is not kept or built on save, as on the web; no operator's build command on Android.
