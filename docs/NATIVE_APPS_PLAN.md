# Plan: Native Android and iOS Apps

Status: P0 started 2026-10-02 — written as the plan for native apps (Kotlin for
Android, Swift for iOS and iPadOS) beside the web app. Android goes first. So far:
`contracts/` (golden fixtures and `openapi.yaml`), the pure-Kotlin `core-model`
(including the saved LZ diagram), `core-geo` and `core-planning` modules, the
release and CI configuration, and backend changes 1, 2, 3, 5, 6 and 7 (config, the
client header, several Google client IDs, refresh tokens with device sessions,
account deletion, and sync on saved LZs, routes and point sets; custom aircraft
profiles are not in the feed yet). Nothing that needs the Android Gradle Plugin
exists yet; see `android/README.md`.

This is the repository copy of the shared planning doc
(https://claude.ai/code/artifact/b5e194d4-db36-488f-8237-5c265d3977dc). The decisions checklist at
the end records what the owner has settled; anything still open there is not
decided. When the plan changes, update this file in the same PR, as for
`AGENTS.md`.

## Summary

We build two fully native apps: EZ/PZ for Android in Kotlin with Jetpack Compose, and EZ/PZ for iPhone and iPad in Swift with SwiftUI. Both reach feature parity with the web app, and both keep working with no signal once a mission area is downloaded. They talk to the existing Flask API, so there is no new backend stack. The admin dashboard, SAM, the LiDAR build service and DEM processing stay on the server.

**Why native, not a wrapped web app**

- **Planning happens where there is no signal.** At a TAC, a FARP or in the aircraft, the map, terrain, MGRS, route math and exports have to run on the device.
- **The iPad is already in the cockpit.** Crews fly with iPad EFBs running ForeFlight. A native iPad app gets Split View and Stage Manager, Apple Pencil markup, the Files app, external displays, and a share sheet straight into ForeFlight.
- **ATAK runs on Android.** A native Android app can hand data packages to ATAK through intents, and can later become an ATAK plugin.
- **Device hardware.** GPS own-ship position, compass heading, haptics, and the camera for QR threat transfer.
- **Speed.** GPU vector maps, background threads for terrain and viewshed math, and no browser memory ceiling for big route and threat sets.

**In scope:** every end-user feature in the web app today — auth and the `.mil` gate, the LZ/PZ workspace, terrain analysis, every planning graphic, MIL-STD-2525C symbology, LZ cards, cloud save, routes and AMPS round-trip, local points, threats, weather, aircraft profiles and the 3D LiDAR view — plus mobile-only work: offline mission packs, GPS, file-type handlers and widgets.

**Out of scope:** the admin dashboard, which stays web at `admin.ezpztac.app` and is linked from the app for admins. Also out: a shared cross-platform UI layer (no React Native, Flutter or Compose Multiplatform UI).

**Success criteria**

| Measure | Target |
| --- | --- |
| Cold start to a usable map | ≤ 1.5 s on an iPhone 13 or Pixel 7 |
| Map pan and zoom with 500 graphics on screen | 60 fps; 120 fps on ProMotion iPads |
| MGRS cursor readout | Every frame, computed on device |
| Offline coverage | A downloaded area supports every feature except SAM analysis, LiDAR builds and live weather |
| Export fidelity | `.msnx`, `.ths`, KMZ, LZ card and ForeFlight exports match web output, and `.LPS` imports read the same, checked by shared golden fixtures |
| Platforms | iOS and iPadOS 17+; Android 10 (API 29)+ |

The project's rules carry over unchanged: unclassified only and no CUI, threats never persisted server-side, secrets stay server-side, and no new major dependency without the owner's approval.

## Feature inventory and parity

All 22 capabilities below ship natively; only the admin dashboard stays on the web. Fifteen work fully offline once a mission pack is on the device, and saves queue until it reconnects. The rest need the server for first sign-in, model inference, LiDAR builds or live weather. "Pack" means a downloaded mission area (see Offline strategy).

| # | Feature | Web app today | Native treatment | Offline | Phase |
| --- | --- | --- | --- | --- | --- |
| 1 | Sign-in and access | Google or email/password, email verification, `.mil` gate, per-user entitlements | Native Google sign-in, Sign in with Apple, email/password; tokens in Keychain / Keystore; entitlements gate the UI as on web | Cached session with a grace period | P1 Core planning |
| 2 | LZ/PZ workspace | Several diagrams per session, switch between them, save state per diagram | Diagram list in a sheet (phone) or sidebar (iPad); each diagram a local record | Full | P1 Core planning |
| 3 | Coordinates and MGRS | Grid or pasted lat/long; cursor readout in the browser; grid to lat/long on the server | Port `utils/mgrs.js` and `coordParse.js` both ways, so nothing calls `/convert-grid`; centre crosshair readout | Full | P1 Core planning |
| 4 | GPS and heading (new) | None | Own-ship dot and heading cone; "target my position"; distance and bearing to the active LZ | Full | P1 Core planning |
| 5 | Base maps | Mapbox satellite and outdoors, FAA VFR sectional | Same three as raster sources in the native map engine | Pack | P1 Core planning |
| 6 | Slope analysis | Server DEM slope raster, directional UH-60 limits | Server when online; on-device Horn slope over the pack DEM when offline | Pack | P1 Core planning |
| 7 | Capacity, area, separation alerts | Client math from the aircraft profile | Direct port; alerts as a banner plus haptic | Full | P1 Core planning |
| 8 | Planning graphics | Helos, PZ markers, sectors of fire, go-arounds, doghouses, units, LZ box, measurements | Native map annotations with touch handles: drag to move, rotate handle, long-press for actions | Full | P1 Core planning |
| 9 | MIL-STD-2525C symbols | milsymbol in the browser; unit builder | milsymbol run in the platform JS engine, rasterised and cached per SIDC; native unit builder | Full | P1 Core planning |
| 10 | Aircraft profiles | Master list plus user profiles; drive icons, spacing, plan defaults | Synced and cached; AMPS templates downloaded for offline export | Full | P1 Core planning |
| 11 | LZ boundary detection (SAM) | SAM on the server, 256 px tile at zoom 14 | Server when online; on-device point-prompt segmentation as the offline fallback; manual draw always available | Manual draw; on-device model in P4 | P4 3D & extras |
| 12 | Route sketch and plan | Speed, altitude, wind, fuel, TOT, per-point overrides | Native editor and nav log; `routeCalc.js` ported | Full (elevations from pack) | P2 Routes & threats |
| 13 | AMPS `.msnx` import/export | Zip of XML, mutated in the browser | Native zip and XML; open from Files, AirDrop, Mail | Full | P2 Routes & threats |
| 14 | Local points `.LPS` | SQLite read in pure JS | Platform SQLite reads it directly | Full | P2 Routes & threats |
| 15 | ForeFlight and ATAK hand-off | Deep link, FPL/GPX through a hosted link or QR | Same-device hand-off: ForeFlight URL scheme and share sheet on iOS; ATAK intents and data packages on Android; hosted link only for another device | Full on the same device | P2 Routes & threats |
| 16 | Threats | In memory; `.ths` import/export, viewshed mask, KMZ, QR | In memory only; viewshed from the server, then on device in P3; `.ths` via native SQLite; KMZ built on device | Full in P3 | P2 Routes & threats |
| 17 | Weather | METAR, NOTAMs, forecast winds via the API | Same API; results cached with fetch time and shown as stale when old | Last fetch, marked stale | P2 Routes & threats |
| 18 | LZ card export | Leaflet canvas grab, server fills the Excel template | Native map snapshotter at exact bounds; server `.xlsx` online; on-device `.xlsx` and PDF offline; share sheet | Full in P3 | P3 Offline & exports |
| 19 | Cloud save | LZs, routes, point sets over REST | Local database is the source of truth; background sync queue | Edits queue and sync later | P1 Core planning |
| 20 | 3D LiDAR view | Cesium in a browser window; build on first view | Native 3D renderer for point cloud, terrain and routes (see Technology decisions) | Built tilesets saved into the pack | P4 3D & extras |
| 21 | Admin dashboard | Server-rendered at `/admin` | Link out from the account menu for admins | n/a | Stays web |
| 22 | Saved routes and point sets | REST CRUD | Same sync engine as LZs | Edits queue and sync later | P2 Routes & threats |

The web app stays the reference implementation. Any behaviour change lands on the web first, or in the same release, so the three clients never disagree on a plan.

## Technology decisions

Each platform gets its own native UI, and both use MapLibre Native for the 2D map. The 3D view gets a purpose-built local-scene renderer on Metal and OpenGL ES. No code is shared at runtime: shared contracts and golden test fixtures keep the web, Android and iOS clients in lock-step.

**Key decisions**

| Decision | Choice | Why | Considered and rejected |
| --- | --- | --- | --- |
| UI toolkit | Jetpack Compose with Material 3 adaptive layouts (Android). SwiftUI, with UIKit where SwiftUI falls short (iOS) | Current platform standard; adaptive list-detail and supporting-pane layouts come built in for tablets | XML Views; UIKit-first; any cross-platform UI |
| Shared logic | None at runtime. A shared `contracts/` folder holds the OpenAPI spec, JSON golden fixtures and design tokens | The owner wants Kotlin and Swift. Fixtures prove all three implementations give the same answers | Kotlin Multiplatform (puts Kotlin/Native inside the iOS build); a Rust core through UniFFI (a third language) |
| 2D map engine | MapLibre Native on both platforms | Open source (BSD), no per-user fees and no telemetry, which matters for unit locations (OPSEC). Metal and Vulkan backends. Reads local PMTiles files, so server-built mission packs drop straight in | Mapbox Maps SDK v11. It has offline Mapbox imagery and 3D terrain built in, but it is proprietary, billed per user past 25,000 MAU, and its offline data must come from Mapbox servers and cannot be redistributed |
| Online imagery | The same Mapbox raster styles as the web (`satellite-v9`, `outdoors-v12`), plus the FAA VFR sectional | Crews see the same picture as the web app and their LZ cards | — |
| Offline imagery | Server-built PMTiles packs from public-domain data: USGS NAIP imagery (CONUS), FAA sectional GeoTIFFs, USGS DEMs | Mapbox terms forbid caching its tiles outside its own SDK. Public-domain data can be built once and handed to every device | Caching Mapbox tiles inside MapLibre (a terms breach) |
| 3D view | Native local-scene renderer: Metal (iOS), OpenGL ES 3.2 (Android). One east-north-up frame centred on the LZ streams the existing `.pnts` point-cloud tiles, terrain grids, imagery and routes | An LZ view spans a few kilometres, so no globe engine is needed. It reuses today's LiDAR tilesets unchanged and adds no dependency | SceneKit (soft-deprecated at WWDC25); RealityKit (iOS-only, and a multi-million-point LOD cloud needs custom Metal anyway); Cesium in a WebView (not native); Cesium Native C++ (heavy integration); waiting for MapLibre 3D terrain (partially funded, no date) |
| MIL-STD symbols | milsymbol, the library the web uses, run in the system JS engine (JavaScriptCore on iOS, `androidx.javascriptengine` on Android). Its SVG is rasterised and cached per SIDC | Symbols match the web exactly and keep the 2525C SIDCs that AMPS `.ths` files carry | mil-sym-android (Apache 2.0, but it has retired 2525C and has no iOS twin) |
| Local database | Room (Android) and GRDB (iOS), both on SQLite with one shared schema | One schema and one set of migrations to reason about; suits the sync queue | SwiftData or Core Data (no shared schema) |
| Background work | WorkManager (Android); BGTaskScheduler and background `URLSession` (iOS) | The OS-sanctioned way to sync and download packs while the app is closed | Foreground-only downloads |
| On-device LZ detection (P4) | iOS: SAM 2.1 Tiny in Core ML (Apple's conversion, Apache 2.0). Android: MediaPipe Interactive Segmenter, or the same SAM 2.1 Tiny through LiteRT | The server prompts SAM at one point on one 256 px tile, which is small enough for a phone's neural engine | Shipping SAM ViT-B (~350 MB) |
| Minimum OS | iOS and iPadOS 17; Android 10 (API 29) | iOS 17 brings the Observation framework and mature `NavigationSplitView`; API 29 brings scoped storage and current Keystore | Older versions add testing cost for a small share of devices |

**Libraries by concern** — "Approve" marks a new third-party dependency the owner has approved (see Risks and decisions); "—" is part of the platform.

| Concern | Android | iOS | Approval |
| --- | --- | --- | --- |
| Language and concurrency | Kotlin 2.x, coroutines, Flow | Swift 6, async/await, actors, `@Observable` | — |
| Navigation | Navigation Compose; Material 3 adaptive scaffolds | `NavigationStack`, `NavigationSplitView`, sheets with detents | — |
| Dependency injection | Hilt | Plain initialiser injection | — |
| HTTP and JSON | OkHttp, Retrofit, kotlinx.serialization | `URLSession`, `Codable` | Approve |
| Database | Room | GRDB | Approve |
| Map | MapLibre Native Android, MapLibre Compose | MapLibre Native iOS behind a thin SwiftUI wrapper | Approve |
| 3D | OpenGL ES 3.2 | Metal, MetalKit | — |
| Google sign-in | Credential Manager with Sign in with Google | GoogleSignIn-iOS | Approve |
| Apple sign-in | Web flow through Credential Manager | AuthenticationServices | — |
| Secret storage | Android Keystore with DataStore | Keychain | — |
| Zip (`.msnx`, `.kmz`, `.xlsx`) | `java.util.zip` | ZIPFoundation | Approve |
| XML | `XmlPullParser` | `XMLParser` | — |
| SQLite files (`.LPS`, `.ths`) | `android.database.sqlite` | GRDB | — |
| QR create and scan | ZXing core; CameraX with ML Kit barcode | Core Image generator; VisionKit `DataScannerViewController` | Approve |
| JS engine (symbols) | `androidx.javascriptengine` | JavaScriptCore | — |
| SVG to bitmap | AndroidSVG | SwiftDraw | Approve |
| PDF | `PdfDocument` | `UIGraphicsPDFRenderer`, PDFKit | — |
| On-device ML (P4) | LiteRT or MediaPipe Tasks | Core ML, Vision | Approve |
| Crash and performance data | Play Console vitals, Macrobenchmark | MetricKit, Xcode Organizer | Approve |
| UI and screenshot tests | Compose UI tests, Roborazzi | XCUITest, swift-snapshot-testing | Approve |

Sources: [MapLibre Native roadmap](https://maplibre.org/roadmap/maplibre-native/) (Terrain3D "Partially Funded"; Metal Jan 2024, Vulkan Dec 2024) · [MapLibre Android PMTiles](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) (from 11.7.0, local `pmtiles://file://`) · [Mapbox offline concepts](https://docs.mapbox.com/android/maps/guides/offline/concepts/) (no redistribution; 750 tile-pack cap) · [Mapbox pricing](https://www.mapbox.com/pricing) (25,000 MAU free, then $4.00 per 1,000) · [WWDC25 session 288](https://developer.apple.com/videos/play/wwdc2025/288/) (SceneKit in maintenance mode) · [apple/coreml-sam2.1-tiny](https://huggingface.co/apple/coreml-sam2.1-tiny) · [MediaPipe Interactive Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter) (Android, web, Python; no iOS) · [mil-sym-android](https://github.com/missioncommand/mil-sym-android) (2525D/E; 2525C retired).

## App architecture

Both apps share one architecture, mirrored module for module: UI, then state, then pure domain code and data, with the local database as the single source of truth. Screens never call the network directly. Sync and build downloads are the only paths to the server.

```
App — same layers and module names on Android (Kotlin) and iOS (Swift)
├─ UI — Compose / SwiftUI
│    Screens (workspace, routes, threats, exports)
│    Map host (MapLibre layers, touch handles)
│    3D view (Metal / OpenGL ES; LiDAR and terrain)
│        ↓
├─ State — one store per screen (view models / stores) ─────────→ calls Domain
│    intent → domain → repository → new state; undo per diagram
│        ↓
├─ Data ───────────────────────────────────────────────────────→ calls Domain
│    Repositories (diagrams, routes, point sets, aircraft, weather, auth)
│    Local database — SQLite (Room / GRDB), one schema, THE SOURCE OF TRUTH
│    Sync engine (outbox, pull by cursor, conflicts kept side by side) ── sync ──→ Flask API
│    Packs and files (PMTiles maps, DEM crops, LiDAR tiles, imports) ── downloads ──→ Build services
└─ Domain — pure code, no platform APIs
     Geo (MGRS both ways, coordinate parsing)
     Planning (capacity, routes, slope, viewshed)
     Formats (.msnx .ths .LPS KMZ .xlsx PDF)
     Symbols (milsymbol in the system JS engine)

Server (the existing stack)
     Flask API — auth, saves, analysis, weather
     Build services — mission packs, LiDAR 3D tiles
```

Map tiles come from Mapbox and the FAA when online, and from pack files when not. With no connection, everything except the Server block keeps working.

**Modules** — Gradle modules on Android, local Swift packages on iOS, with the same names.

| Module | Contents | Depends on |
| --- | --- | --- |
| `app` | Composition root, navigation, deep links, adaptive layout | Everything |
| `core-model` | Domain types | — |
| `core-geo` | MGRS both ways, coordinate parsing, distance, bearing, geodesy | `core-model` |
| `core-planning` | Capacity, separation, route plan, slope, viewshed, elevation sampling | `core-model`, `core-geo` |
| `core-formats` | `.msnx`, `.ths`, `.LPS`, KMZ, `.xlsx`, PDF, FPL, GPX | `core-model` |
| `core-symbols` | `SymbolRenderer`: JS engine, SVG rasteriser, caches | `core-model` |
| `core-network` | API client, auth session, token refresh, request priority | `core-model` |
| `core-data` | Database, repositories, sync engine | `core-model`, `core-network` |
| `core-packs` | Pack download, verification, storage, PMTiles sources | `core-network` |
| `core-designsystem` | Tokens, type, colour, shared components (sheet, inspector, readout pill) | — |
| `feature-auth`, `-map`, `-workspace`, `-analysis`, `-graphics`, `-routes`, `-threats`, `-weather`, `-exports`, `-aircraft`, `-history`, `-packs`, `-viewer3d`, `-settings` | One screen family each, with its store | Core modules only |

**Rules**

- Features never depend on each other; `app` wires navigation between them.
- `core-geo`, `core-planning` and `core-formats` are pure: no Android or UIKit imports. They run in plain JVM and Swift tests, and they are the code the golden fixtures exercise.
- State flows one way: UI event → store intent → domain function → repository write → database emits → store state → screens and map layers redraw.
- Map layers subscribe to diagram state and swap their GeoJSON sources off the main thread.
- Errors become inline banners with a retry. The web's blocking `alert()` calls do not carry over.

**Concurrency**

- Android: `viewModelScope`; `Dispatchers.Default` for planning math and `Dispatchers.IO` for database and network; WorkManager for sync and packs.
- iOS: stores on the main actor; repositories and the sync engine as actors; planning math in detached tasks; BGTaskScheduler and background `URLSession`.

**Navigation and deep links**

- Phone: the map is the root. Sheet tabs are Plan, Diagrams, Routes, Threats and Saved; the nav log, LZ card form, 3D, packs and settings push full-screen.
- Tablet: three columns — sidebar, map, inspector.
- Links: verify and reset links open the auth screens. Route-share links (`/r/<token>`) can import straight into the app once their host serves the association files.

## Mobile UX

Every floating desktop window becomes a docked surface. On phones that is one bottom sheet with three detents over a full-bleed map, the pattern Apple Maps and Google Maps use. On iPads and Android tablets it is three columns: a collapsible sidebar, the map, and a trailing inspector. Modal sheets are kept for short, self-contained tasks.

```
Phone (portrait): map with a three-detent bottom sheet

       ┌──────────────────────────────┐
Full ─ │ [ MGRS grid or lat/long ]    │
       │               Layers [ ]     │
       │                  GPS [ ]     │
       │             +                │
       │    ( 16S GC 28864 55349 )    │
       │                              │
Half ─ ├──────────────────────────────┤
       │            ═══               │
       │ LZ HAWK · Analyzed           │
       │ Capacity · Area · Elevation  │
       │ Max slope · Wind · Altimeter │
Peek ─ ├──────────────────────────────┤
       │ Plan  Diagrams  Routes  …    │
       │ [      Analyze the LZ     ]  │
       │ Aircraft · UH-60L            │
       │ Slope map · LZ box           │
       └──────────────────────────────┘

Tablet (landscape): sidebar, map, inspector

┌─────────────┬────────────────────────┬────────────────────┐
│ Diagrams    │ [ MGRS or lat/long ]   │ [Routes | Threats] │
│ ▸ LZ HAWK   │                        │ ROUTE 1            │
│   LZ CROW   │                        │ Nav log · TOT      │
│   PZ OAK    │          +             │ Fuel · winds       │
│             │ ( 16S GC 28864 55349 ) │                    │
│ Tools       │                        │ Selected           │
│   Draw LZ   │                        │ Helo · UH-60L      │
│   Helo      │                        │ Heading · grid     │
│   PZ marker │                        │ Nudge · delete     │
│   Sector    │                        │                    │
│   Unit      │                        │                    │
│   Route     │                        │                    │
└─────────────┴────────────────────────┴────────────────────┘
    sidebar               map                  inspector

Android window size classes
  Compact  < 600 dp     → phone layout
  Medium   600–840 dp   → sheet beside the map
  Expanded ≥ 840 dp     → three columns
```

Labels in the sketch name fields, not data.

The sheet's Peek detent keeps the active diagram's key numbers in view while the map stays nearly full-screen. On tablets, the Active LZ window becomes the sidebar's Diagrams list and the routes window becomes the inspector.

**Where each web surface goes**

| Web today | Phone | Tablet |
| --- | --- | --- |
| Control sidebar (target, aircraft, analysis, tools, export, routes) | Search field above the map; the rest is the **Plan** tab of the bottom sheet | Leading sidebar that collapses to an icon rail, as the web rail does |
| Active LZ window (draggable diagram switcher) | Diagram chips across the top of the sheet; the full list in its **Diagrams** tab | **Diagrams** section of the sidebar, with status badges and swipe actions for save, remove and 3D |
| Routes and threats window (collapsible) | **Routes** and **Threats** tabs of the sheet | Trailing inspector, segmented Routes / Threats |
| Route plan table (nav log) | A pushed route screen: one card per leg in portrait, a full table in landscape | Full table in the inspector, or its own window under Stage Manager and Android multi-window |
| Right-click context menus | Long-press on the map opens a native context menu headed by the grid: Set as Target, Add Threat Here, Analyze, Insert Point | Same, plus secondary click from a trackpad or mouse |
| Modals (LZ card form, ForeFlight, threat dialog, unit builder, aircraft, history) | Full-height sheets with native forms | Form sheets or popovers |
| Map style switcher | Layers button: base map plus overlays (slope, LZ box, threats, local points, routes) | Same, as a popover |
| Cursor MGRS readout | A fixed crosshair at map centre with a readout pill: MGRS and ground elevation | The pill, plus a live readout under the pointer or Apple Pencil hover |
| Separation alerts | Banner under the status bar with a warning haptic | Same |
| 3D window | Full-screen cover with a 2D inset | Side by side with the 2D map, or a separate window |
| Loading overlay | Non-blocking progress in the sheet header, with Cancel | Same |

**Interaction rules**

- **Explicit modes.** Explore is the default. Draw LZ, Sketch Route, Place Graphic and Measure each show a mode chip with Done and Cancel, so a stray tap never drops a point.
- **Two ways to place.** Tap where it goes, or pan the map under the fixed crosshair and press Place. The crosshair suits gloves and aircraft vibration.
- **Precision editing.** Dragging a point shows a magnifier loupe and the live grid. Every selected object has nudge arrows and an editable MGRS field in the inspector.
- **Graphics on touch.** Tap selects and shows handles; drag moves with a light haptic; a rotate handle sets heading; tapping empty map deselects. Two-finger gestures always belong to the map.
- **Cockpit sizing.** Touch targets at least 56 pt / 56 dp, above the 44 pt and 48 dp platform minimums. Key numbers (capacity, slope, wind) stay readable at arm's length.
- **Night mode.** A dark theme by default, plus a dimmed night palette with a darkened map and red-shifted accents for night cockpit use. Brightness never jumps when a sheet opens.
- **Kneeboard mode (iPad and tablets).** Landscape, map beside the nav log, large type, screen kept awake, orientation locked.
- **iPad extras.** Apple Pencil draws LZ boundaries and sketches routes, and Pencil hover shows the grid under the tip. Keyboard shortcuts cover new diagram, analyze, save, undo and mode switching. Multiple windows show two diagrams side by side.
- **Android extras.** Material 3 window size classes drive layout: compact (< 600 dp) is the phone layout, medium (600–840 dp) puts the sheet beside the map, expanded (≥ 840 dp) is three columns. Foldables and Samsung DeX get the same breakpoints.
- **Undo everywhere.** Every map edit is undoable: shake or a three-finger swipe on iOS, a toolbar Undo on both platforms.
- **Accessibility.** Dynamic Type and font scaling throughout. A map is not readable by VoiceOver or TalkBack, so every diagram has a list view of its graphics with each one's grid and properties.

## Offline strategy

The apps are offline-first. The on-device database is the source of truth, every calculation runs locally, and a downloaded **mission pack** carries the maps and terrain for an area. The server is needed only for first sign-in, SAM detection (until the on-device model lands), LiDAR builds, live weather and links meant for another device.

**Three tiers**

| Tier | What works | What it needs |
| --- | --- | --- |
| 0 — Always | Launch, cached session, every saved diagram, route and point set; all planning math; MGRS; graphics editing; `.msnx`, `.LPS`, `.ths` and KMZ import and export; same-device ForeFlight and ATAK hand-off | Nothing. Base maps show whatever the ambient cache holds |
| 1 — Mission pack | Tier 0, plus offline base maps, slope analysis, route ground elevations, threat viewsheds, the LZ card with map image, METAR/TAF/NOTAM refresh, hosted ForeFlight and threat QR links, route-share links | A pack covering the area |
| 2 — 3 Dimensional | SAM boundary detection (on device from P4), LiDAR builds, admin, 3D for LZs whose LiDAR was packed | A connection |

**What a mission pack holds**

A pack is an area the user picks — a box, a radius around an LZ, or a corridor along a route — built on the server as a handful of files and downloaded in one go.

| Layer | Source | Zooms | Approx. size for 20 × 20 km |
| --- | --- | --- | --- |
| Imagery | USGS NAIP, public domain, ~0.6 m (CONUS) | 10–17 over the area, 18 over each LZ | 150–250 MB |
| VFR sectional | FAA GeoTIFFs, public domain, 56-day cycle | 8–12 (FAA's native range) | 10–30 MB |
| Topo | Vector basemap from OpenStreetMap (ODbL) plus contours drawn from the pack DEM | 6–15 | 20–40 MB |
| Planner terrain | Terrarium tiles, the same source `/api/elevations` and threat masks use today | 8–13, widened by the largest threat range | 5–20 MB |
| Analysis terrain | USGS 1/3″ DEM crop where covered, as Int16 metres | native ~10 m | 3–8 MB |
| 3D (optional) | Built LiDAR tilesets for chosen LZs, plus an ellipsoidal terrain grid | — | 50–300 MB per LZ |
| Reference | Aircraft profiles and AMPS templates, symbol cache, the user's saves in the area, last weather | — | < 5 MB |

Sizes are estimates to confirm in P3 against real builds.

The planner keeps its existing terrain source on purpose. Route elevations offline must match online exactly, because they feed exported AMPS altitudes. So packs carry Terrarium tiles for the planner and viewsheds, and USGS DEMs for slope and 3D, mirroring the server.

**Pack service rules**

- Built by a new pack-builder service beside the LiDAR build service, from data the home server already holds (`/data/topo`), plus NAIP and FAA charts.
- Requested like LiDAR builds: coordinates only in POST bodies, progress read by an opaque key, no coordinates in logs. Built packs expire on the server after 7 days.
- Downloaded with a background `URLSession` or WorkManager, resumable with HTTP range requests, checked against a per-file SHA-256 manifest.
- On the device, MapLibre reads each layer straight from its PMTiles file (`pmtiles://file://`). Mapbox tiles are never bulk-downloaded; the ambient cache only honours HTTP cache headers, as a browser does.
- If the server is down, a device can build a small pack itself from the same public endpoints. It is slower and capped at about 5 × 5 km.
- Pack screen: size estimate before download, layer picks, progress, then a list with size, age and expiry. VFR layers flag when a new chart cycle is out. The app offers a pack whenever an LZ or route is saved.

**Sync and conflicts**

- Every user record (LZ diagram, sketched-route bundle, mission file, point set, custom aircraft profile) carries a client UUID, the server id, a server revision, a dirty flag and a deleted flag.
- Changes go into an outbox and are sent in order when online, with backoff and an `Idempotency-Key`, so a retry never makes a duplicate.
- Pulls ask for everything changed since a cursor, deletions included (new endpoint, see Backend changes).
- Updates send `If-Match` with the revision they were based on. On a conflict, nothing is overwritten: the server copy stays, and the local one is kept beside it as "NAME (from iPad, 14:32)" for the user to resolve. Records are whole planning documents, so last-writer-wins would silently drop work.

**Threats on the device**

Threats are never synced or persisted server-side. In the app they live in memory. iOS and Android can end a backgrounded app at any time, which would lose a crew's threat picture mid-planning. So they are also held in one encrypted file that is excluded from device backups, wiped on sign-out and 48 hours after the last change. An explicit online viewshed, KMZ or QR action may send the needed threat coordinates to the existing backend transiently; the backend must not retain them. Otherwise they leave the device only by an explicit export. The owner approved this.

**Signing in while offline**

- Local data never needs a valid token. Only server calls do.
- A refresh token (new, see Backend changes) renews the session on reconnect without a sign-in.
- Entitlements are cached at their last known values.
- After 14 days without reaching the server, the app asks for a sign-in before it unlocks again. This bounds how long a suspended account keeps working.

## Performance plan

Four rules make the app fast. Draw from the local database first and never wait on the network to show something. Keep the main thread for input and rendering only. Render map content as GPU layers, not one view per marker. Measure every release against fixed budgets on the slowest supported devices.

**Budgets**

| Measure | Budget | Measured with |
| --- | --- | --- |
| Cold start to an interactive map | ≤ 1.5 s | Macrobenchmark `StartupTimingMetric`; `XCTApplicationLaunchMetric` |
| Warm start | ≤ 0.5 s | Same |
| Pan and zoom with 500 graphics, 3 routes of 300 points, 10 threat masks | 60 fps, < 1% janky frames | Macrobenchmark `FrameTimingMetric`; Instruments hitch ratio |
| MGRS conversion | < 0.5 ms, every frame | Unit benchmark |
| Route plan recompute, 300 points | < 10 ms, off the main thread | Unit benchmark |
| Slope analysis on device, 500 m LZ | < 300 ms | Unit benchmark over a fixture DEM |
| Threat viewshed on device, 15 nmi, 3 bands | < 2 s, cancellable, with progress | Unit benchmark |
| Diagram switch | < 100 ms | UI test timing |
| `.msnx` import, 1,000 points | < 0.5 s | Unit benchmark |
| LZ card export offline (snapshot, `.xlsx`, PDF) | < 3 s | UI test timing |
| Memory, 2D planning | < 300 MB on a phone | Android Studio profiler; Instruments Allocations |
| Memory, 3D view | < 600 MB, enforced by a point budget | Same |
| Install size | < 40 MB (Android, per device); < 60 MB (iOS). Packs and models download on demand | Play Console; App Store Connect size report |
| Battery, 1 h of active planning with GPS | ≤ 8% | Battery Historian; Instruments Energy Log |

**Startup**

- Nothing on the network is on the startup path. Open the database, restore the last viewport and active diagram, and draw the map from cache or pack; refresh profiles and entitlements in the background afterwards.
- Android: Baseline and startup profiles, R8 full mode, lazy initialisation of everything except the map.
- iOS: no work in initialisers or `App.init`. The JS engine for symbols starts only when a symbol not already cached is needed; common symbols ship pre-rendered.

**Map rendering**

- Each graphic collection is one GeoJSON source drawn by style layers (symbol, line, fill, circle), batched on the GPU. The web draws a React marker per route point, which is the main thing a native app does better.
- Only the selected object gets interactive handle views; everything else stays a layer.
- Aircraft icons and MIL-STD symbols live in a sprite atlas registered once with the style.
- Slope maps and threat masks are image sources: one textured quad per band.
- Route point labels hide below a zoom threshold. Large local-point sets (thousands of `.LPS` points) use source clustering.
- Source updates are built on a background thread and swapped in whole.

**Computation**

- Every planning formula is a pure function run off the main thread: `Dispatchers.Default` on Android, detached tasks and actors on iOS.
- The viewshed's azimuth rays are independent, so they are split across all cores. A Metal or Vulkan compute version is held in reserve if the CPU version misses budget.
- Terrain-dependent recomputes wait until a drag ends, not every frame.
- Database writes from drags are debounced (about 300 ms) and batched.

**Network**

- HTTP/2 with compression, and ETags on profiles and saved records.
- User-blocking requests (analysis, exports, viewsheds) go ahead of background ones (sync, pack downloads, tile prefetch), as `requestPriority.js` does on the web today.
- Requests belonging to a diagram are cancelled when the user switches away from it.

**3D**

- A point budget per device class (about 3 M on phones, 8 M on recent iPads), screen-space-error level of detail, frustum culling, and an LRU tile cache in memory and on disk.
- `.pnts` decoding happens off the render thread; GPU buffers are pooled and reused.
- Targets: 60 fps on iPads and recent flagships, never below 30 fps on the floor devices.

**Measurement**

- Floor devices: iPhone 13 and iPad (10th generation); Pixel 7 and a mid-range Samsung tablet. Two current flagships for the ceiling.
- Benchmarks run nightly on physical devices attached to the CI runner, and a regression of more than 10% against the stored baseline fails the build.
- In the field: MetricKit and Play Console vitals (launch time, hangs, ANRs, slow frames). No third-party analytics SDK.

## Feature specs

Each feature below names the screens it needs, the web code its logic is ported from, how it behaves offline, and how parity is proven. "Port" means a line-by-line translation into Kotlin and Swift, checked against golden fixtures generated from the web or Python original.

### Auth and access

- **Screens:** Welcome → Sign in (Google, Apple, email) → Register → Verify email → `.mil` affiliation (enter address, then the typable code) → Pending approval → the app.
- **Links in emails** (verify, reset) open the app through iOS Universal Links and Android App Links. That needs `apple-app-site-association` and `assetlinks.json` served from `ezpztac.app/.well-known/`.
- **Google:** Credential Manager (Android) and GoogleSignIn-iOS return an ID token for the existing `POST /api/auth/google`. The backend must accept the Android and iOS client IDs as audiences.
- **Apple:** App Store rule 4.8 expects Sign in with Apple beside Google sign-in. It needs a new `POST /api/auth/apple`. Apple's private-relay addresses never clear the `.mil` gate, so those users go through the `.mil` code flow as anyone else would.
- **Account deletion in the app** is an App Store requirement for apps that create accounts. New `DELETE /api/auth/me`; the super-admin account refuses it, as everywhere else.
- **Storage:** tokens in the Keychain (this device only, after first unlock) and in Keystore-encrypted DataStore. Optional Face ID or BiometricPrompt to reopen after inactivity, for shared devices.
- **Entitlements:** read from `/auth/me` into one `Entitlements` value; a missing key means enabled, the web rule.

### Map, coordinates and GPS

- **Base maps:** satellite, topo and VFR online; the same three from PMTiles when a pack covers the view. The layers menu toggles slope, LZ box, threats, local points and routes.
- **MGRS:** port `utils/mgrs.js` (truncate, never round; zero-padded zone) and add the inverse, so grid-to-lat/long no longer calls `/convert-grid`. Both directions are checked against PyGeodesy on the same 5,255-point fixture the web uses.
- **Coordinate entry:** port `utils/coordParse.js`, so any common lat/long notation is accepted and a valid grid is never misread as one.
- **Readout:** crosshair pill with grid and ground elevation; tap to copy. Optional MGRS grid lines at 1 km and 10 km.
- **GPS:** own-ship dot and heading cone, "Target my position", distance and bearing from own-ship. When-in-use permission only; position never leaves the device.
- **Measure:** distance and true bearing between any two points or graphics.

### LZ/PZ workspace

- **Port** `useLzWorkspace.js`: the draft → targeted → analyzed lifecycle, schema version 2, and `importLegacySnapshot`. The native apps must open every diagram the web has ever saved; a fixture set of real `lz_data` records proves it.
- **Saved records strip runtime terrain rasters,** exactly as `serializeDiagram` does today.
- **Actions:** new from target, switch, rename, duplicate (new), save, remove with "Discard" or "Save first".
- **Default doghouses** (SP and RP) are created once, after a diagram's first successful analysis.
- **Undo** is a per-diagram command stack.

### Terrain analysis

- **Online:** `POST /api/analyze-field` (SAM boundary and target elevation), then `/api/terrain-analysis` (slope). Progress is shown, the request can be cancelled, and the result is tagged with its diagram id, so a late answer never lands on another diagram.
- **Offline:** draw the boundary by hand, and slope runs on the device over the pack DEM. Port `build_slope_analysis` from `terrain_provider.py`: Horn gradients, polygon mask, percentiles, and the directional nose-high, nose-low and cross-slope summary for a landing heading.
- **Parity:** device results must match the Python output on fixture DEMs and polygons — max and P95 slope within 0.1°.
- **Mission summary tiles:** capacity, area, elevation, max slope with the UH-60 directional limits from `MissionSummary.jsx`, then wind, temperature and altimeter.
- **Elevation sources stay labelled.** Online target elevation is SRTM30 from the server; offline it comes from the pack DEM. The tile names the source, so the two are never silently mixed.

### Planning graphics

| Graphic | Port from | Native behaviour |
| --- | --- | --- |
| Helicopters | `useHelicopters.js`, `aircraftProfiles.js` | Profile icon, rotor disc and tip-clearance ring, rotate handle; separation alerts from `pairSeparation` and `edgeGapFt` |
| PZ markers | `usePzMarker.js` | Drag, rotate, delete |
| Sectors of fire | `useSectorsOfFire.js` | Polygon with draggable vertices; move whole |
| Go-arounds (L/R) | `useGoAround.js` | Arrow with rotation |
| Doghouses (SP/RP) | `useDoghouses.js` | Label boxes edited inline in the inspector (heading, time, distance, airspeed). They feed landing and takeoff headings into flight data, as on the web |
| Units | `useUnit.js`, `UnitBuilder.jsx` | MIL-STD symbol plus label; built or edited in the unit builder |
| LZ box and boundary | `App.js` draw mode | Draw mode with vertex handles; editing a boundary resets the analysis, as on the web |
| Measurements | `LZDimensions.jsx` | Dimension lines with labels |
| Capture area | `useExport.js` | Box locked to the card's 663 : 555 aspect |

### MIL-STD-2525C symbols

- **One `SymbolRenderer` service** with a memory LRU and a disk cache keyed by SIDC, size and options.
- **Common symbols ship pre-rendered** (the unit presets in `presets.js` across all four affiliations). Others go through milsymbol in the JS engine.
- **Unit builder:** affiliation, dimension, status, function, echelon, with a live preview, as `UnitBuilder.jsx` does.
- **Threat markers** use the same renderer and keep their 2525C SIDCs for `.ths` round-trips.

### Aircraft profiles

- **List, select, and manage** the master list plus the user's own profiles through `/api/aircraft-profiles`.
- **AMPS templates** (`/<id>/template`) download with the profile and are cached, so offline `.msnx` export works for every airframe that has one.
- **Unverified data stays flagged:** airframes without a real `.vidx` show their spec-sheet flag. Tip clearances stay at the seeded 60 m until the owner supplies doctrinal values. A `.vidx` is never synthesised.

### Routes and AMPS

- **Sketch mode:** tap adds a point; long-press designates it turn, RP/IP, LZ/PZ or shaping; long-press on a leg inserts a point; drag moves one. A local point can be added to the route, which carries its charted elevation.
- **Planning:** port `routeCalc.js` (distance, true course, IAS → TAS from density altitude, wind triangle, TOT clock anchor, fuel) with per-point "to" overrides. The nav log shows leg time, ground speed, heading, fuel and clock.
- **Forecast winds:** `/api/route-winds` results merged per point, as in `routeWinds.js`.
- **Ground elevations:** `/api/elevations` online. Offline, a port of `_sample_elevations_ft`: nearest pixel of the pack's Terrarium tiles at zoom 13, so exported altitudes match.
- **`.msnx`:** port `parseMsnx.js`, `createMsnx.js`, `mutateMsnx.js`, `aircraftTemplate.js` and `ampsFormats.js`. Bundle `msnx_template.msnx`. Unknown XML parts pass through byte for byte. Fixtures: web exports compared to native exports after XML canonicalisation.
- **Files:** declared document types (`.msnx`, `.ths`, `.LPS`, `.kmz`) open the app from Files, AirDrop, Mail and the Android share sheet. Exports go out through the share sheet, with the companion `.ths` when threats exist.

### Local points

- **Read** `.LPS` with the platform's SQLite (copied to a temp file, opened read-only). Port the SpatiaLite geometry parsing in `parseLps.js` and `sqliteReader.js`.
- **Point sets** each have a colour, a visibility toggle, cloud save (`/api/pointsets`) and "add to route". Large sets cluster on the map.

### ForeFlight, ATAK and other EFBs

- **iOS:** when ForeFlight is installed, short routes open straight through `foreflightmobile://maps/search`. Any route can also go out as a Garmin `.fpl` or a `.gpx` through the share sheet, and threats as KMZ.
- **Android:** ForeFlight is iOS-only, so Android hands off to ATAK. KMZ and KML go out by intent, or as an ATAK data package (zip with manifest). GPX and FPL go out through the share sheet for Garmin Pilot and others.
- **Another device:** the existing hosted links and QR codes (`/api/route-share`, `/api/threats-kmz-link`), unchanged.

### Threats

- **Dialog:** name, symbol (via the symbol builder), and detection and engagement radars, each with range, antenna height, AGL/MSL and three altitude bands (colour, opacity, visible). Dragging a threat recomputes its mask.
- **Viewshed:** `/api/threat-mask` online. On device in P3, a port of `viewshed()` in `threat_routes.py` over pack Terrarium tiles at the same zoom rule: radial line of sight, curvature with refraction k = 0.13, the 3 × 3 smoothing and the 3 × 3 observer footprint. Parity: band masks overlap the server's by at least 99% (intersection over union) on fixtures.
- **`.ths`:** import with platform SQLite (port `parseThs.js`); export by copying the bundled `threat_template.ths` and inserting rows (port `build_ths_bytes`).
- **KMZ:** the server builds it in P2. In P3 the device does: contour tracing and simplification of the masks into vector polygons (ForeFlight won't render rasters), with range rings and markers. Can be directly exported to foreflight on iOS.
- **Never synced or persisted server-side;** online viewshed/KMZ/QR processing is transient and user-initiated. Device retention is as set out in Offline strategy.

### Weather

- **`/api/weather`** fills the mission tiles and NOTAMs, and `/api/route-winds` feeds routes. Each result is stored with its fetch time and shows its age ("METAR 3 h old").
- **NOTAMs** get a readable, searchable list.

### LZ card export

- **Capture:** the capture-area box, then MapLibre's snapshotter renders the style offscreen at the box's exact bounds, at 1326 × 1110 px (2×). Graphics drawn as style layers appear in the snapshot with no screen grab. This replaces the web's resize-and-screenshot workaround in `ExportHandler.jsx`.
- **Form:** every field of `ExportModal.jsx` (type, name with bird and tree suggestions from `LZDictionary.js`, objective, grid, lat/long, elevation, call sign, frequency, directions, formations, spacing, weapons control and status, go-around, door, load, remarks), plus optional NOTAMs and separation alerts.
- **Outputs:** online, `POST /api/generate-excel` as today. Offline, the device fills the bundled `lz_template.xlsx` with the same cell map (`A1`, `B1`, `D2`–`D5`, `A23`–`J25`, `A26`, `A28`, `B28`, image at `A6`). A PDF of the same card is also produced for printing and kneeboards, plus the map JPG. Share sheet, Files, AirPrint and Android print.
- **Parity:** cell values and image placement compared with the server's workbook on fixtures.

### Cloud save and history

- **History screen:** LZs, routes and point sets, with search, sort, load into the workspace, and delete with confirmation.
- **Sync status** on every record (synced, pending, conflict), and a conflict screen offering keep mine, keep theirs, or keep both.

### 3D LiDAR view

- **Flow:** `POST /api/lidar/resolve`; if no tileset exists, `POST /api/lidar/build` and poll `GET /api/lidar/build/<key>` every 3 s, showing the service's stage names. `DELETE` when the view closes; saved LZs pass `keep`. Saving an LZ asks for a background build, as `useBuildOnSave.js` does.
- **Renderer:** one east-north-up frame at the LZ. It walks `tileset.json` (bounding volumes, geometric error, additive and replace refinement) and decodes `.pnts` (float or quantised positions, RGB, RTC centre). Points draw as GPU sprites with size attenuation.
- **Terrain and imagery:** terrain meshed from the 65 × 65 Int16 heightmap tiles, draped with Mapbox imagery online or pack imagery offline.
- **Routes:** lines at planned MSL, converted with the geoid from `/api/terrain/heights`, with curtains to the ground and labels. Ported from `routeScene.js`.
- **Compass and camera:** a compass shows heading and tilt and taps to face north. The camera opens oblique from the north-west at 35°, as on the web.
- **Geodesy rules carry over:** LiDAR and terrain are ellipsoidal (−30.35 m geoid in north Georgia). A missing terrain tile spreads neighbouring data or shows a gap; it is never filled with sea level.
- **Offline:** built tilesets download into the pack as one archive (new endpoint).
- **Verification:** renderer snapshot tests over a fixture tileset, plus owner screenshot review on real devices.

### Platform extras (P4)

- **TOT countdown** as an iOS Live Activity and an Android ongoing notification.
- **Home-screen widgets** for the active mission: LZ name, next TOT, pack status.
- **App Intents and Shortcuts** ("Open LZ HAWK"), and Handoff of the active diagram between iPhone and iPad.
- **ATAK plugin exploration:** a full plugin needs the ATAK SDK and TAK Product Center signing. Scoped as research, not a commitment.

## Backend changes

The Flask API stays the single backend for all three clients. It needs ten additions, nearly all additive, so the web app keeps working untouched. The largest are sync support on saved records and the new mission-pack service.

| # | Change | Why | Phase |
| --- | --- | --- | --- |
| 1 | `GET /api/config` (public): minimum app version per platform, maintenance message, which services are up (LiDAR builds, packs), current Mapbox public token | Store builds stay in the field for months. The server must be able to say "update required", and the token can rotate without an app release | P0 |
| 2 | Client header on every request (`X-EZPZ-Client: ios/1.4.0 (212)`), logged into `LoginEvent` and shown in admin | Tells the owner which app versions are live before changing an endpoint | P0 |
| 3 | Accept several Google client IDs (`GOOGLE_CLIENT_IDS`: web, Android, iOS) | Native ID tokens carry their own audience | P1 |
| 4 | `POST /api/auth/apple`, verifying Apple's identity token against Apple's published keys | App Store rule 4.8 | iOS P1 (week 14) |
| 5 | Refresh tokens: `POST /api/auth/refresh`, rotating, stored hashed as `AccountToken` purpose `refresh`, 30-day life, revoked by the existing `sv` bump | Crews stay signed in across days offline without keeping a long-lived access token | P1 |
| 6 | `DELETE /api/auth/me` (account deletion; the super-admin refuses), and a device-sessions list with revoke | Store requirement; also pays down the "no server-side revocation" debt | P1 |
| 7 | Sync on `SavedLZ`, `SavedRoute`, `SavedPointSet` and custom `AircraftProfile`: new `client_uuid`, `revision` and `deleted_at` columns, added by guarded `ALTER TABLE` (quoting `"user"`); `GET /api/sync/changes?since=`; `If-Match` returning 409 with the server copy; creates deduplicated by `client_uuid` | Offline edits, safe retries, and no silent overwrites between web and devices. Existing list endpoints skip deleted rows | P1 |
| 8 | Mission-pack service: `POST /api/packs`, `GET /api/packs/<key>` (progress and manifest), `GET /api/packs/<key>/<file>` with HTTP range support. A separate container beside the LiDAR build service, reading `/data/topo`, NAIP, FAA charts and an OpenStreetMap basemap extract | Offline maps and terrain (see Offline strategy) | P3 |
| 9 | `GET /api/lidar/tilesets/<key>/archive`: one streamed archive of a built tileset | Puts 3D into a mission pack | P4 |
| 10 | `/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` served from `ezpztac.app` (Vercel `public/`) | Email links open the app | P1 |

Push notifications arrive in P3: APNs and FCM for "pack ready", "3D ready" and "access approved". The push credentials stay server-side, and notifications carry no coordinates or plan content.

**What moves to the device and what stays**

| Runs on the device | Stays on the server |
| --- | --- |
| MGRS in both directions (drops `/convert-grid` from the native apps) | SAM boundary detection (on device from P4 as a fallback only) |
| Slope analysis over the pack DEM | LiDAR builds |
| Route ground elevations from pack Terrarium tiles | METAR, TAF, NOTAMs and forecast winds |
| Threat viewsheds from pack Terrarium tiles | Hosted share links and QR downloads |
| `.ths`, KMZ and LZ card `.xlsx` and PDF generation | Entitlements, admin, accounts |
| `.msnx` read and write (already client-side on the web) | Mission-pack and LiDAR builds |

When a pack covers the area, the device computes; otherwise it calls the server. Both run the same algorithm over the same source data, and the fixtures prove the answers agree, so either path gives the crew the same number.

**Rules that do not change**

- One gunicorn worker. Pack builds run in their own container, like LiDAR builds, so the API process never does heavy pack work.
- Coordinates travel only in POST bodies for packs and LiDAR, progress is read by opaque key, and nothing logs a requested area.
- Every new import goes into `backend/requirements.txt`, and the image is booted against a Postgres container before release.
- New server-side tools for the pack builder (GDAL, a PMTiles writer) are new dependencies and need the owner's approval.
- `contracts/openapi.yaml` describes every route the apps call, and a pytest contract check fails when a response drifts from it.

## Data model and local storage

Both apps use one SQLite schema: Room on Android, GRDB on iOS. Planning documents are stored as the same JSON the web saves, so a diagram made on an iPad opens on the web unchanged, and the reverse.

**Tables**

| Table | Holds | Synced |
| --- | --- | --- |
| `diagram` | id (client UUID), server id, revision, name, status, target lat/lon/MGRS, `document` (schema-v2 JSON: flight data, analysis without rasters, graphics, view), dirty, deleted, timestamps | Yes (`/api/lz`) |
| `diagram_runtime` | Slope raster file path and terrain stats per diagram | No — a cache, rebuilt on demand |
| `route_bundle` | id, server id, revision, kind (sketch or mission), name, `route_data` JSON, path to the original `.msnx`, file name, dirty, deleted | Yes (`/api/routes`) |
| `point_set` | id, server id, revision, name, points JSON, colour, visible, dirty, deleted | Yes (`/api/pointsets`) |
| `aircraft_profile` | id, server id, slug, system flag, profile JSON, template file path, updated time | Master: pulled. Custom: yes |
| `outbox` | sequence, entity, entity id, operation, base revision, idempotency key, attempts, last error | Drives sync |
| `sync_state` | Cursor per collection | — |
| `mission_pack` | Opaque key, user's name for it, bounds, layers, bytes, status, created and expiry times, manifest | No |
| `weather_snapshot` | Payload and fetch time per location | No |

Threats are not in the database (see Offline strategy).

**Compatibility rules**

- **JSON field names match the web exactly:** camelCase inside diagram documents, snake_case for profile fields from the API.
- **Unknown fields survive.** Each record keeps its raw JSON and merges edits into it, so a field a newer web release adds is never dropped by an older app.
- **Schema versions are explicit.** Documents carry `schemaVersion`, and the native migrations port `normalizeLzDiagram` and its legacy-snapshot handling.
- **IDs are client UUIDs** from creation, so a record has an identity before the server has seen it.

**Files on disk**

| Folder | Contents | Backed up to iCloud or Google |
| --- | --- | --- |
| `Database/` | The SQLite file | No — the server is the backup, and LZ locations stay out of consumer cloud backups |
| `Packs/<key>/` | PMTiles layers, DEM crops, manifest | No |
| `Lidar/<key>/` | Downloaded 3D tilesets | No |
| `Imports/` | Original `.msnx` and `.LPS` files, kept so exports preserve unknown parts byte for byte | No |
| `Caches/` | Symbol bitmaps, map tile cache, snapshots, export temp files | No (OS may purge) |

**Protection**

- **iOS:** the database uses data protection `completeUntilFirstUserAuthentication`, so background sync can run; the threat file uses `complete`.
- **Android:** everything sits in app-private storage under the OS's file-based encryption, with backup rules excluding all of it.

**Domain types** (Kotlin data classes, Swift structs; same names on both): `LatLon`, `Mgrs`, `Diagram`, `Target`, `Analysis`, `FlightData`, `Helicopter`, `PzMarker`, `SectorOfFire`, `GoAround`, `Doghouse`, `UnitMarker`, `Measurement`, `CaptureBox`, `Route`, `RoutePoint` (turn, IP, target, shaping), `RoutePlan`, `Threat`, `Radar`, `AltitudeBand`, `AircraftProfile`, `PointSet`, `LocalPoint`, `MissionPack`.

## Security, privacy and distribution

The apps hold the same posture as the web: unclassified only, no CUI, threats never on the server, no secrets in the client. Mobile adds three concerns: a lost device, location privacy (OPSEC), and store rules. Distribution starts on TestFlight and Play closed testing, then moves to public App Store and Play Store listings, with a signed APK for devices without Play.

**Handling rules**

- **Classification notice.** First launch, and every export sheet, state that the app is not authorised for CUI or classified information. The user acknowledges once.
- **No secrets in the binary.** Only public identifiers ship: Google client IDs and the Mapbox `pk.` token, which arrives from `/api/config` so it can rotate. Everything secret stays server-side.
- **Transport.** HTTPS only: App Transport Security on iOS, and cleartext disabled in Android's network security config. No certificate pinning, because Cloudflare rotates its intermediates and a pin failure would lock crews out. Platform TLS validation is the control.
- **Tokens.** Held in the Keychain and Keystore and never logged. Refresh tokens rotate on use. Sign-out wipes tokens, the threat file and caches.
- **Lost device.** App-private storage under OS encryption, an optional biometric lock, and remote revoke from the device-sessions list.
- **Location privacy.** GPS position never leaves the device. There is no third-party SDK with telemetry, and MapLibre has none. Online Mapbox tiles reveal the area being viewed to Mapbox, as the web does today; offline packs avoid it. Pack requests follow the LiDAR rules: coordinates in POST bodies, opaque keys, nothing logged, 7-day expiry.
- **Threats.** Memory, plus the approved encrypted file: never backed up, wiped on sign-out and 48 hours after the last change. Never synced or persisted server-side; explicit online viewshed/KMZ/QR requests are processed transiently. Android sets `FLAG_SECURE` on threat screens so they stay out of the app switcher and screenshots; iOS has no equivalent, so the app blurs its snapshot when backgrounded.
- **Supply chain.** Gradle dependency verification metadata and a committed `Package.resolved`. Renovate opens update PRs, and every release gets a CycloneDX SBOM.

**Store obligations**

| Item | iOS | Android |
| --- | --- | --- |
| Privacy disclosure | App Privacy labels: email, name, user content, diagnostics; no tracking, so no tracking prompt | Data safety form with the same answers |
| Account deletion | In-app, via `DELETE /api/auth/me` | Same, plus a web deletion page Play requires |
| Third-party sign-in | Sign in with Apple offered beside Google (rule 4.8) | — |
| Encryption export | Only OS-provided TLS, so `ITSAppUsesNonExemptEncryption = NO` | Standard declaration |
| Privacy policy | Public URL required, e.g. `ezpztac.app/privacy` | Same |
| Review access | A demo account that bypasses the `.mil` gate for the reviewer | Same |

**Distribution options**

| Channel | Who can install | Fit |
| --- | --- | --- |
| TestFlight | Up to 10,000 invited testers; each build lasts 90 days | Beta (P1–P3) |
| Unlisted App Store | Anyone with the link; not searchable; full App Review | Not used; the apps are listed publicly |
| Public App Store | Anyone | Chosen for general release; the `.mil` gate still controls use |
| Apple Business Manager custom app | Specific organisations only | Only if a unit or command wants managed distribution |
| Play closed testing | Testers by email list | Beta (P1–P3) |
| Play production | Anyone, or a managed private app through managed Google Play | General release |
| Signed APK from `ezpztac.app` | Devices without the Play Store | Offered when needed, for devices without the Play Store; needs its own update check |

MDM readiness: both apps read managed app configuration (server URL, disabled features), so a unit can deploy them on managed iPads and Android devices. Formal DoD app vetting is out of scope. The choices above — no third-party SDKs, no telemetry, an SBOM — keep that path open.

## Testing, CI/CD and release

Both apps live in this repository, beside the web app and backend they must agree with. Golden fixtures are the backbone of testing: one set of inputs and expected outputs that Jest, pytest, JUnit and Swift Testing all run. Store submission stays a manual step for the owner.

**Repository layout**

```
avtactools/
├─ android/      Gradle project (app + feature and core modules)
├─ ios/          Xcode project + local Swift packages per module
├─ contracts/
│  ├─ openapi.yaml
│  ├─ fixtures/  golden inputs/outputs, one folder per domain
│  ├─ tokens/    design tokens (colours, type, spacing) as JSON
│  └─ scripts/   regenerate fixtures from the web and Python code
├─ frontend/  backend/  docs/  tools/   (unchanged)
```

One repository means a change to a planning formula lands in the web, both apps and the fixtures in a single PR. `AGENTS.md` gains Android and iOS sections in that same PR.

**Branching**

The apps are built in short-lived feature branches off `develop`, one slice at a time, never in one long-lived app branch. A year-long branch would drift from `develop`, conflict with every backend and `AGENTS.md` change, collide with parallel agent work, and break the rule that a formula change lands in all three clients in one PR.

- **One branch per slice** (`feat/android-skeleton`, `feat/android-auth`, `feat/sync-endpoints`, `feat/android-lz-workspace`), each a PR into `develop`, small enough to review in one sitting.
- **Merge when tests pass, even if nobody can see the feature yet.** Nothing in production builds `android/` or `ios/`: Vercel builds `frontend/` and Coolify builds `backend/`. App code reaches users only when the owner uploads a store build.
- **Unfinished features hide behind flags** (`/api/config` and entitlements), not behind unmerged branches.
- **Backend additions stay additive** (new routes, new columns), so the web keeps working as they land.
- **CI path filters** run app checks only for app changes. Vercel's Ignored Build Step skips previews for PRs that touch only `android/`, `ios/` or `contracts/`.
- **Throwaway branches only for spikes** (such as the 3D test). Delete them afterwards and rebuild the result in small PRs.

**Agent task — do this in the first app PR:** app commits use a scope, `feat(android): …`, `fix(ios): …`, `feat(contracts): …`. Configure semantic-release in `.github/workflows/release.yaml` and its config so commits scoped `android` or `ios` do not bump the web version. Use the commit-analyzer `releaseRules` with `{ "scope": "android", "release": false }` and `{ "scope": "ios", "release": false }`. Keep `contracts` and `backend` scopes releasing, since they change what the server or web ships. Add the scopes to commitlint's allowed list, and document the convention in `AGENTS.md` §12 in the same PR.

**Test layers**

| Layer | What it covers | Tools |
| --- | --- | --- |
| Golden fixtures | MGRS (5,255 points, both directions), coordinate parsing, route plans, capacity and separation, slope, viewshed masks, Terrarium sampling, `.msnx` / `.ths` / `.LPS` / KMZ / `.xlsx` round-trips, diagram migrations | Generated by `contracts/scripts` from the web and Python code; run by every client's test runner |
| Unit | Domain functions, view models and stores, repositories on an in-memory database | JUnit 5, Turbine; Swift Testing |
| Sync engine | Offline edits, retries with idempotency, 409 conflicts, deletions, interrupted uploads | Fake server with scripted responses |
| API contract | App clients against the OpenAPI spec; backend responses checked against the same spec | Mock server; pytest |
| UI flows | Sign in; target → analyze → place graphics → save; sketch route → export `.msnx`; a whole mission in simulated airplane mode | Compose UI tests; XCUITest |
| Screenshots | Key screens in light, dark and night; phone and tablet; largest text size | Roborazzi; swift-snapshot-testing |
| Renderers | LZ card snapshot and 3D view drawn offscreen from fixtures, compared with reference images within a tolerance | Same screenshot tools |
| Performance | The budgets in Performance plan | Macrobenchmark; XCTest metrics |
| Field checklist | Airplane-mode mission, gloves, direct sunlight, night mode, iPad kneeboard, ForeFlight and ATAK hand-off | Manual, on real devices, before each store release |

Fixtures change only when someone reruns the generator scripts, and the diff is reviewed like code.

**CI**

- GitHub Actions with path filters, so a backend-only change does not build the apps.
- Android on Linux runners: lint, detekt, unit tests, screenshot tests, assemble.
- iOS on macOS runners: SwiftLint, build, unit and snapshot tests. macOS minutes cost about ten times Linux ones, so a self-hosted Mac mini beside the home server is worth it once iOS work is daily. It also hosts the physical test devices for nightly benchmarks.
- Today nothing runs tests in CI. The same workflows should run `pytest` and `npm test`, closing that gap for the web and backend too.

**Signing and delivery**

- iOS: an App Store Connect API key and an encrypted certificate repository (fastlane match), or Xcode Cloud. Android: Play App Signing, with the upload key in GitHub secrets.
- Lanes (fastlane, a new tool to approve): `beta` uploads to TestFlight internal testing and the Play internal track from `develop`; `release` prepares a store submission from a `release/*` branch.
- The owner submits for review, promotes Play tracks and releases. Agents never do, matching the "agents do not deploy" rule.
- Rollouts are staged: App Store phased release over 7 days, Play staged percentages.

**Versions**

- Apps carry the product's semantic version from semantic-release, so `1.9.0` means the same thing on all three clients. The build number is the CI run number. Not every version ships to the stores.
- `/api/config` sets the minimum supported version per platform, and the apps show "Update required" below it.
- Server-side flags and entitlements can switch off a broken feature without waiting for store review.
- Crash symbols: dSYMs stay in App Store Connect; R8 mapping files are uploaded to Play.

### Testing on a device against a local backend

The server address is fixed when the app is **built**, from the Gradle property `ezpz.apiUrl`. The default is the production server, which has none of your local accounts, so a build that never got the property answers every sign-in with "Invalid email or password". A debug build shows the address it was built with on the sign-in screen ("DEBUG BUILD / Server: ..."): check that first.

1. **Backend:** run it as in AGENTS.md section 11 (`python app.py`, port 5000).
2. **Account:** a local backend has its own empty SQLite database. From `backend/`, make a verified account with `python dev_user.py you@example.com --password "a few words as a passphrase"` (a phrase is easier to type on a device than a random string; it must satisfy the server's password rules). Run it again to reset the password.
3. **Reach the backend from the device:**
   - **Emulator:** `10.0.2.2` is the host machine. Build with `-Pezpz.apiUrl=http://10.0.2.2:5000/`.
   - **Physical phone or tablet:** tunnel the port over adb and build against loopback: `adb -s <serial> reverse tcp:5000 tcp:5000`, then `-Pezpz.apiUrl=http://127.0.0.1:5000/`. The debug network config already allows plain HTTP to `127.0.0.1`, `localhost` and `10.0.2.2`, and nothing else, so no config change is needed. For a wireless device, `adb devices -l` lists the serial (the `adb-...._adb-tls-connect._tcp` name); the tunnel lasts only while adb stays connected, so repeat the `reverse` after a reconnect or reboot.
4. **Build and install to that one device** (with an emulator and a device both attached, Gradle would install to both, and one of them cannot reach the address): `$env:ANDROID_SERIAL="<serial>"; .\gradlew.bat installDebug "-Pezpz.apiUrl=http://127.0.0.1:5000/"`.
5. **Android Studio** ignores the command line. To make its builds use the same address, add `ezpz.apiUrl=...` to your user-level `~/.gradle/gradle.properties` (not the repository's), or Studio goes back to production.

## Roadmap

Android reaches 1.0 about 32 weeks after kickoff, and iOS and iPadOS about 42, with native 3D on both by week 52. That assumes one engineer per platform and the owner on the backend, ±30%. Android leads: it builds on the owner's Windows machine, it is where ATAK runs, and Play testing turns builds around faster than App Review. iOS trails one phase and reuses the contracts, fixtures and backend work Android proved.

All figures are weeks from kickoff; iOS work starts at week 10.

| Lane | P0 Foundations | P1 Core | P2 Routes | P3 Offline | P4 3D |
| --- | --- | --- | --- | --- | --- |
| Android | 0–4 | 4–14 | 14–22 | 22–32 | 32–42 |
| iOS and iPadOS | 10–14 | 14–24 | 24–32 | 32–42 | 42–52 |

| Backend work | Weeks |
| --- | --- |
| Config (`/api/config`, client header, OpenAPI) | 0–4 |
| Auth + sync | 4–12 |
| Pack service | 14–28 |
| LiDAR tileset archive | 34–40 |

| Release gate | Week |
| --- | --- |
| Internal beta (Android) | 14 |
| External beta (Android) | 22 |
| **Android 1.0** | **32** |
| iOS 1.0, Android 3D | 42 |
| iOS 3D | 52 |

The pack service starts at week 14 so it is ready before offline work begins on the devices. Android 1.0 is the release that matters: it is the first build that plans a whole mission with no connection. Crews who only carry iPads can join the iOS internal beta around week 24, and ForeFlight hand-off arrives with iOS P2 by week 32.

**What each phase delivers and the gate that ends it**

| Phase | Scope | Exit gate |
| --- | --- | --- |
| P0 Foundations | Module skeletons and CI on both platforms; design tokens; `contracts/` with OpenAPI and the first fixtures (MGRS, coordinates, route plan, capacity); `/api/config`; client header | Fixtures pass in Jest, pytest, JUnit and Swift Testing; MapLibre shows Mapbox satellite on both platforms |
| P1 Core planning | Auth with Google, email, the .mil gate and refresh tokens (Sign in with Apple joins in the iOS phase); map, MGRS, GPS; LZ workspace; online analysis; every planning graphic; symbols; aircraft profiles; LZ sync; two-week 3D spike | Internal beta: an LZ planned on an Android tablet opens unchanged on the web, and the reverse |
| P2 Routes and threats | Route sketch and plan; `.msnx`; local points; ForeFlight and ATAK hand-off; weather; threats with server viewshed, KMZ and `.ths`; route and point-set sync | External beta: real AMPS missions round-trip; threats export to AMPS and ATAK (ForeFlight follows in the iOS phase) |
| P3 Offline and exports | Pack service and pack screen; on-device slope, elevations and viewshed; native `.ths`, KMZ, `.xlsx` and PDF; offline sign-in; 48-hour threat retention; push notifications | 1.0: a whole mission planned in airplane mode on the floor devices, within the performance budgets |
| P4 3D and extras | Native 3D renderer; LiDAR in packs; on-device LZ detection; Live Activity and widgets; Apple Pencil; ATAK plugin research | 3D matches the web view on the same LZs in owner review |

**Team shape**

Solo developer working with AI agents. Ship Android alone through 1.0 before starting iOS.

## Risks and decisions

The largest risk is three implementations of the same planning math drifting apart and putting a wrong number on a crew's plan; golden fixtures and the web-first rule exist for that. The largest unknowns are the 3D renderer's effort and offline imagery outside the US.

**Risks**

| Risk | Effect | Mitigation |
| --- | --- | --- |
| Web, Kotlin and Swift drift apart | A plan shows different numbers on different devices | Golden fixtures in every test suite; formula changes land in all three clients in one PR; the web stays the reference |
| Two native codebases roughly double client work | Slower delivery with a small team | Phase gates; Android first with iOS one phase behind; shared fixtures and design tokens cut rework |
| 3D renderer costs more than planned | P4 slips | A two-week spike in P1 renders one real tileset with OpenGL ES, then with Metal once iOS starts; if it fails, 3D links out to the web view until native is ready |
| No public-domain offline imagery outside CONUS (NAIP is US-only) | Offline packs for overseas training areas lack imagery | Owner picks a licensed source or Mapbox SDK offline for those areas; 10 m Sentinel-2 as a coarse fallback |
| Mobile raises Mapbox raster tile usage | Higher tile bill | Watch usage per platform; packs and HTTP caching cut repeat requests |
| Single gunicorn worker meets sync and pack traffic | Slow API for everyone | Pack and tileset bytes served by a static file server with signed, expiring URLs, not Flask; sync batched per request |
| On-device viewshed or slope too slow on floor devices | Offline threats feel broken | Benchmarks in CI; GPU compute fallback; server path whenever online |
| `androidx.javascriptengine` unsupported on an old WebView | Custom symbols fail to render | Check support at startup; pre-rendered presets always work; offer a WebView update prompt |
| AMPS formats are reverse-engineered | A native export AMPS rejects | Round-trip fixtures from real, unclassified mission files supplied by the owner; unknown parts passed through byte for byte |
| App Review rejects a gated app | Store release slips | Reviewer demo account past the `.mil` gate; review notes explaining the audience |
| iPads overheat in a sunlit cockpit | Device shuts down mid-mission | Map renders only on change; frame rate capped when idle; 3D pauses when hidden |

**Decisions needed from the owner**

- [x] Approve MapLibre Native as the 2D map engine, rather than Mapbox Maps SDK v11.
- [x] Choose the offline imagery source outside CONUS. (This is not required right now)
- [x] Approve on-device threat retention (encrypted, 12-hour, no backup) or keep threats memory-only. - I like encrypted, 48 hour store with no backup.
- [x] Set the offline grace period before sign-in is required again (proposed: 14 days). approved
- [x] Platform order: Android first, with iOS and iPadOS one phase behind.
- [x] Approve the third-party libraries marked "Ask" in Technology decisions, and fastlane, GDAL and a PMTiles writer.
- [x] Release reach: public App Store and Play Store listings, plus a signed APK when needed.
- [ ] Set up Apple and Google developer accounts as an organisation (needs a D-U-N-S number) or as an individual. This decides the publisher name crews see.
- [x] Publish a privacy policy page.
- [ ] Supply doctrinal tip-clearance values per airframe; they are still the seeded 60 m.
- [ ] Confirm the project's export-control position with whoever advises it, before any public store listing.
- [x] Push notifications: add in P3.
