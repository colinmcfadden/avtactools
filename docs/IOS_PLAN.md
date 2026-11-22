# Plan: EZ/PZ for iPhone and iPad (Swift)

Status: **proposed; I0 built** (2026-11-21), **I1 built** (2026-11-22). Written after reading `AGENTS.md` and every plan in `docs/`. It turns the iOS half of
`docs/NATIVE_APPS_PLAN.md` into a build plan. It reflects what Android has built since that plan was written (AGENTS.md §17, `docs/HANDOFF.md`)
and gives the ForeFlight hand-off a section of its own (§3). Nothing in this file is decided until the owner says so: §15 lists the decisions.

**Refreshed the same day against `develop` at `011100b4`** (release 1.11 and after): backstops, the FLOT, phase lines and control measures,
graphics that shrink as the map zooms out, a doghouse on each route leg, undo across documents, route sets arranged, routes and overlays
imported from `.fpl`, `.gpx`, `.kmz` and `.kml`, local points made by hand, threats saved in named sets and shared in packs, the place search,
the FAA's own VFR charts, the USGS topo map and the web's mission overlay for ForeFlight. The owner asked (2026-11-21) that **the backstops,
FLOT and phase lines are on iOS as they are on the web** (§1.4).

When this plan changes, update this file in the same PR, as for `AGENTS.md`.

---

## 0. Summary

- **One app for iPhone and iPad, in Swift 6 and SwiftUI**, with UIKit where SwiftUI falls short: the map view, the Metal 3D view, Pencil and
  hover input, the document picker. It has the same features as the web and Android: the LZ/PZ workspace and analysis, planning graphics (backstops, the FLOT and phase lines included, as on the web: §1.4),
  MIL-STD symbols, aircraft, routes and AMPS, local points, threats, weather, the Library and sync, mission packs, the mission brief and
  sandtable, the 3D LiDAR view, notifications and the MGRS grid. LZ surveys and the admin dashboard stay on the web (the owner, 2026-10-26).
- **The web is the reference and the fixtures are the contract**, as for Android. Every pure module is a line-by-line port of the web's code
  (often by way of the Kotlin port, which has already found the traps), held to the same `contracts/fixtures`. No code is shared at run time.
- **Sending to ForeFlight is a first-class feature on iOS.** ForeFlight is where Army aviators fly with their plans. On iOS a route, a
  threat picture, the LZ/PZs, the MGRS grid, local points and the mission's documents all reach ForeFlight in one or two taps:
  - a route through ForeFlight's URL scheme, or as a GPX or FPL file;
  - everything at once as a **ForeFlight content pack** built on the device: KML layers, user waypoints, and PDFs attached to waypoints.
  - §3 sets out the whole thing.
- **The iPad comes first among the iOS devices; the iPhone follows in the same steps.** Crews fly with iPad EFBs. Every screen exists on both,
  except that the brief's setup and the sandtable are iPad only at first, as Android's are tablet only.
- **Minimum iOS and iPadOS 26 is proposed** (the native plan said 17). The design is drawn in Liquid Glass (`docs/native-design/`), whose APIs
  begin at 26. iOS 27 is current, and Xcode 27.2 with Swift 6.4 is on the owner's Mac. This is the owner's decision (§15).
- **Agents can verify far more here than on Android.** The iOS Simulator runs on the owner's Mac and an agent can drive it and take screenshots,
  and the simulator draws Metal: the map, the 3D view and every screen can be looked at. The pure modules run as plain `swift test` on macOS, as
  Android's run on the JVM. What still needs a device: ForeFlight (it cannot be installed in the simulator), GPS, Pencil, and performance.
- **Rough size: 10 to 14 weeks of agent-assisted work to parity, ±30%.** The ForeFlight route hand-off lands by about week 5 and the content pack
  by about week 8 (§11). For scale, Android is about 82,600 lines of Kotlin and 92,600 of tests, built from 2026-10-02 to now, with the backend and
  the fixtures built along the way. iOS reuses all of that.

---

## 1. Features: web, Android and iOS

"Android" means what is built as of 2026-11-21 (AGENTS.md §17, HANDOFF). "iOS" is this plan. Where iOS differs from Android, the last column
says why.

### 1.1 Planning

| Feature | Web | Android (built) | iOS (planned) | iOS differs because |
|---|---|---|---|---|
| Sign-in | Google, email and password, verification, reset, the `.mil` gate | Same, with Credential Manager for Google; refresh tokens; device sessions; account deletion | Same, with GoogleSignIn-iOS, and **Sign in with Apple** | App Store guideline 4.8 expects Apple's sign-in beside Google's. It needs `POST /api/auth/apple` (backend change 4, not built) |
| Links from emails | `?auth=verify/reset`, `?invite=`, `#grid=` | App Links, which wait on `assetlinks.json` (an owner step) | **Universal Links** through `apple-app-site-association` on `ezpztac.app` | Needs the Team ID and the bundle id (owner) |
| Map | Leaflet; satellite, USGS topo, Mapbox topo, **the FAA's own VFR charts** (`/api/config`'s live build, ArcGIS where it has none); opens at the person's location | MapLibre Native 13.6.1; satellite, Mapbox topo, the FAA's VFR charts; crosshair and readout pill; GPS | MapLibre Native iOS (`MLNMapView`) behind a SwiftUI wrapper; the web's four base maps, the FAA charts by the same `/api/config` rules | — |
| Place search | FAA NASR and USGS GNIS places in the target field (`POST /api/places/search`, `place_search`), held to `places/*` | Planned | The web's, held to `places/*`; a place to ForeFlight as its identifier | — |
| Mission overlay | Routes, control measures, threats, local points and imported overlays as **one map layer for ForeFlight** (MBTiles on every subscription, GeoTIFF on Military Flight Bag), drawn in pieces and made by a server job; never an LZ/PZ | Not built | **The same, from `MLNMapSnapshotter` pieces to the same job routes** (§3.2) | ForeFlight is on iOS |
| MGRS and coordinates | `mgrs.js`, `coordParse.js`, `targetReading.js` | `core-geo`, held to `mgrs/*`, `coords/*` | `CoreGeo`, held to the same | — |
| MGRS grid overlay | Built (`mgrsGrid.js`) | Built (`MgrsGrid`, held to `mgrs/grid.json`) | Same port | Can also go to ForeFlight as KML (§3) |
| Grid sharing | Web Share, QR, `#grid=` link | Share sheet, QR (ZXing), link | `ShareLink` / the share sheet (AirDrop, Messages); QR from Core Image; *Show in ForeFlight* | ForeFlight's search takes `MGRS@…` (to confirm, §3.9) |
| LZ/PZ workspace | Several diagrams; the Library | `OpenDiagrams`, autosave every 600 ms, *Save* names an item, the other open LZ/PZs drawn faded | Same | — |
| Terrain analysis | SAM boundary, slope by heading, contours, profile, slope opacity | Same (`AnalysisService`, `byHeading`, contours, profile, opacity, LZ box) | Same | — |
| Planning graphics | Helicopters, PZ markers, sectors, go-arounds, doghouses, units, measurement line, **backstops**; drawn smaller as the map zooms out (`graphicScale`) | All but the measurement line and backstops, with drag, turn, the held bar, the tool rail and long-press *Add object*; keeps `graphics.measurements` and `graphics.backstops` through a save and a pack but draws neither; no scaling | **All of these, the measurement line and backstops included, with the web's scaling** (§1.4) | The owner, 2026-11-21: every graphic the web draws |
| MIL-STD-2525C symbols | milsymbol | milsymbol in `androidx.javascriptengine`, presets pre-rendered | milsymbol in **JavaScriptCore**, the same presets, SVG drawn by SwiftDraw | JavaScriptCore is in-process and synchronous: no sandbox to fail, no binder limit, no WebView to update |
| Aircraft profiles | Master list and own profiles | Same, synced, the form held to `aircraft/limits.json` | Same | — |
| Boundary drawing | Draw mode | Draw mode, delete a corner | Same, plus **Apple Pencil** drawing on iPad | Pencil |
| Routes | Sketch, plan, nav log, winds, elevations; sets made, moved between and combined, a route's colour; **a doghouse on each leg**; points added and deleted from the plan | Sketch, plan, nav log, winds, elevations; route points shaped by AMPS type | Same as the web, plus the iPad nav-log **table** (leg doghouses: §1.4) | Android has not built its tablet table, the leg doghouses or the arranging of sets |
| FLOT, phase lines, control measures | Built: drawn, edited, flipped, named; kept as a set of their own with no routes (§1.4) | Keeps a set's `flots` and `phaseLines` through a save, a pack and the history; draws none | **Drawn and edited as on the web** (§1.4); to ForeFlight as KML and in the mission overlay, never to AMPS, GPX or FPL | The owner, 2026-11-21 |
| Undo and redo | One undo across the LZ/PZ and the open route sets (`seq`), a route drawn a point at a time, a move between sets one step in two histories | Per document, 100 deep; a drag or a field's visit one step | **The web's, across documents**, on ⌘Z and ⇧⌘Z and the system's shake and three-finger gestures | The web is the newer design |
| AMPS `.msnx` | Import, export, edit in place | `MsnxReader`, `MsnxWriter`, `MsnxMutator` | Same ports, held to `msnx/*` | — |
| Local points | `.LPS` read in pure JS; **made by hand** (`PointDialog`); exported as `.LPS` or GPX; found by name in the target field | `.LPS` read by our own `SqliteReader`, held to `sqlite/tables.json`; not made by hand | Same as the web: the reader port, made by hand, `.LPS` and GPX out | — |
| Imports | `.msnx`, `.LPS`, `.ths`, **`.fpl`, `.gpx`, `.kmz`, `.kml`** (a route set, local points or an overlay), by the Import menu, the review, the Imports panel or a drop on the map | `.msnx`, `.LPS`, `.ths`; files opened from other apps | Same as the web: Files, AirDrop, Mail's *Open in*, **drag and drop on iPad**; a KMZ or KML from ForeFlight comes back in as an overlay | Drag and drop is native on iPad |
| Threats | — | **KMZ, or the same KML unzipped**: a `Folder` per set and per threat, terrain-mask polygons per band, range rings, a marker (the server's `build_threats_kmz`, which takes `sets` and `format: "kml"` since 2026-11-16); every open set or one | `layers/<MISSION> Threats.kml` |
| Weather | METAR, NOTAMs, winds aloft | Same, with its age and the cache file | Same | — |
| LZ card | Image and Excel card | Not built | Card image from `MLNMapSnapshotter`, `.xlsx` from the server, a PDF of the card on the device | ForeFlight takes PDFs; it can be attached to the LZ's waypoint (§3) |

### 1.2 Working together and briefing

| Feature | Web | Android (built) | iOS (planned) | iOS differs because |
|---|---|---|---|---|
| The Library and sync | Library, `/api/sync/changes` | Sync engine, outbox, conflicts kept side by side, Room v4 | The same engine over GRDB, run through the same scenarios | — |
| Mission packs | Built | Engine A1–A15 and screens P1–P4 | Engine ported; the same screens in SwiftUI | — |
| Live stream | WebSocket | OkHttp WebSocket | `URLSessionWebSocketTask` | — |
| Mission brief | Setup, PDF, live sandtable, strip charts | Tablet only: setup, review over the real PDF (`PdfRenderer`), Share, Save, sandtable | **iPad**: the same, with **PDFKit** review. **iPhone**: review and share a brief set up elsewhere (§7) | PDFKit is a full viewer. A brief's PDF can go to ForeFlight's Documents |
| Sandtable on another screen | — | — | iPad: the sandtable on an **external display or AirPlay** while the presenter keeps the controls (§7.4) | A secondary `UIScene` on an external display |
| Notifications | — (N3 not built) | Polled every 15 min by WorkManager (N1) | **APNs "look now" push**, with polling at launch and on coming to the front | iOS gives no reliable 15-minute background run (§9) |

### 1.3 3D and the rest

| Feature | Web | Android (built) | iOS (planned) | iOS differs because |
|---|---|---|---|---|
| 3D LiDAR view | Cesium | OpenGL ES 3.0 renderer over `core-scene3d`: points, terrain, imagery, routes, streaming | **Metal** renderer over `CoreScene3D` (a port), the same scene rules | Metal; the simulator can show it, so it can be looked at |
| Offline map packs | — | Not built (P3) | Not built; follows the shared P3 (`NATIVE_APPS_PLAN.md`) | — |
| ATAK | — | GPX and FPL by share sheet; no KMZ or data package yet | **iTAK** gets KMZ and GPX through the share sheet (to confirm, §3.10) | ATAK's iOS twin is iTAK |
| Platform extras | — | — | Keyboard shortcuts, Pencil hover readout, App Intents (*Send mission to ForeFlight*), a TOT Live Activity later | iPadOS |
| LZ surveys, admin | Web | No | No | The owner, 2026-10-26: web only |


### 1.4 Backstops, the FLOT, phase lines and the other graphics (the owner, 2026-11-21)

**Every graphic the web draws is on iOS, drawn and edited as the web draws it**, including the ones Android only carries. Android keeps each of
them through a save, a mission pack and a pack's history (AGENTS.md §15), so a plan made on the web or the iPad loses nothing on Android, but
Android shows none of them yet. iOS does not follow Android here; it follows the web.

| Graphic | Kept as | What the web does | iOS |
|---|---|---|---|
| Backstop | An LZ/PZ's `graphics.backstops`: `{id, points: [{lat, lon}…], arrow?: {tail, tip?}}`, only once there is one | A reference for a crew overflying an LZ/PZ: a dashed line sketched point by point, and an arrow back to the target labelled with its azimuth and length (`185°T · 2.4 km`), red outlined in white. The arrow moves, never the line: whole, or by its tip, which goes back onto the target within 20 px. With no `arrow` it runs from the line's middle; the label is worked out, never saved (`backstops.js`) | The same, held to `planning/backstops.json`; the arrow dragged on the map (a long press, as other graphics), moved and aimed from the held bar too; drawn with Pencil on iPad |
| FLOT | `flots` of a control-measures set: `{id, points, flipped?: true}` | A scalloped blue line labelled FLOT, scallops 18 px on the screen at any zoom, clipped to the view (`scallopRuns`), starting on the right of the direction drawn; *Flip*; a vertex to drag at each point (`flots.js`, `Flot.jsx`) | The same: the scallops worked out from the camera as it moves (a MapLibre line source rebuilt off the main actor), the vertices draggable |
| Phase line | `phaseLines`: `{id, name, points}` | Black (`#0b0f14`, 3.5 px) on a white casing (8 px), `PL <NAME>` on a white pill beyond each end (`phaseLineEnds`, `labelShift`). Named in the app's dialog in capitals, a `PL` typed in front dropped, at most 24 characters (`cleanPhaseLineName`); an unused phonetic name offered (`nextPhaseLineName`); renamed from its row or its menu | The same; the label is text, never markup, since any pack member types it |
| Control measures | A route set's document with no routes, `{version: 1, flots?, phaseLines?, routes: []}`: in `/api/routes` (kind `sketch`) and as a pack `route` item named `CONTROL MEASURES` | FLOTs and phase lines belong to no route set. The first line drawn makes a set of them, listed under *Control measures* in the Routes panel, saved, closed and added to a pack on its own. New routes never go into it. Known by content (`isMeasuresSet`). Lines saved inside a route set before 2026-11-15 stay there, with *Keep on their own* (`moveLinesOf`) | The same rules, from the same documents; one line drawn at a time, the others refused in words |
| Graphics drawn smaller | The view only | From the zoom at which the LZ's longer side is 100 px (`referenceZoom`, 15 to 18), doghouses, go-arounds, units, the LZ's length and width and the feet between aircraft shrink as the square root of the map's scale, and are put away two levels out; one held or dragged is drawn whole (`scaleRule.js`) | The same rule on MapLibre's zoom (Leaflet's less one) |
| Leg doghouses | Worked out from a route's plan, never saved; on or off in the Layers control | A doghouse on each leg between named points: the point flown to, the true course, the time, the length in km and the planned airspeed, at the middle of the leg's longest straight stretch, on the right of the way flown | The same, from `CorePlanning`'s route plan |
| Measurement line | An LZ/PZ's `graphics.measurements` | The LZ's orange length and width, moved and turned | The same |

Rules:

- **The web first, a fixture for each** (as for every pure module). `planning/backstops.json`, `planning/graphicScale.json` (`referenceZoom`,
  `graphicScale`) and `planning/controlMeasures.json` (`scallopRuns`, `flotLabelSpot`, `phaseLineEnds`, `labelShift`, `cleanPhaseLineName`,
  `nextPhaseLineName`, `isMeasuresSet`, `setsWithLines`, `lineLengthKm`, the lines read from a set's data) exist, the last two recorded by the
  web in I1, and `CorePlanning` is held to all three. Before I5 the web records the leg doghouses' placement and words. Their history sentences
  are already in `packs/describe.json`. Android can draw them from the same fixtures later.
- **In a mission pack** each line is sent on its own (`flots` and `phaseLines` are lists with ids); a line's `points` are a list without ids,
  which the diff sends whole, so two people moving one line's vertices stand as one person's line.
- **Not under undo** for the FLOT and phase lines, and not on the brief, the sandtable or in 3D, as on the web. A backstop is a graphic, and is.
- **Out of every AMPS, GPX and FPL export**, as on the web. **To ForeFlight** they go as KML in the content pack and on their own (§3.2), and
  drawn into the mission overlay.

---

## 2. Where iOS is built differently from Android, on purpose

- **ForeFlight.** Android has no ForeFlight, so its hand-off is GPX and FPL by share sheet. On iOS, ForeFlight is the main place a plan goes
  (§3).
- **JavaScriptCore** runs milsymbol in the process. Android's sandbox can be missing or out of date, and its isolate has a binder limit.
  `JSContext` has neither: symbols always render, and there is no `Unavailable` state to design for.
  - Keep the pre-rendered presets anyway: they cost a JavaScript start.
- **PDFKit** shows the brief and the LZ card as real documents (search, zoom, thumbnails). Android draws page bitmaps with `PdfRenderer` and
  manages a cache folder for it.
- **Metal** for the 3D view (the native plan's choice). The renderer's rules, the tile choice and the camera are already pure Kotlin in
  `core-scene3d`, so the Swift port is a port, and only the GPU half is new.
- **Background work is the system's to schedule.** `BGAppRefreshTask` runs when iOS chooses, often hours apart. Notifications therefore need a
  content-free APNs push (§9).
- **What the agent can see.** The simulator draws SwiftUI, MapLibre and Metal, so screens are checked by an agent with screenshots, not only by
  pictures drawn off-screen as on Android (Roborazzi).
- **No Hilt.** Dependencies are handed in by initialisers from one `AppEnvironment`, the native plan's choice.
- **JSON is a tree of our own, not `Codable`, wherever the web's document must survive whole** (§6). `JSONSerialization` loses key order, a
  number's literal text and the difference between null and absent. The Kotlin port relies on all three.
- **One window first.** iPadOS allows several windows of one app. The editors, like Android's, hold one open document of each kind per process.
  So the main window is one scene, and only read-only views go to a second scene: the sandtable, and later a 3D view or a nav log (§7.4).

---

## 3. Sending to ForeFlight

### 3.1 What ForeFlight takes

The facts come from ForeFlight's own pages (fetched 2026-11-21) unless marked *to confirm*, which means search snippets or inference.
`support.foreflight.com` refused automated fetches.

| Path into ForeFlight | What it carries | Fact |
|---|---|---|
| `foreflightmobile://maps/search?q=<string>` | Anything ForeFlight's search takes: a route of identifiers, or lat/long tokens joined by `+` or spaces; speed, fuel and altitude after them | The only documented URL action (foreflight.com/support/app-urls). No length limit is stated |
| Lat/long in a route | `32.7/-80.8`, `3244.92N/08045.95W`, `N324455/W0804557` | Third-party source (iPad Pilot News), and what the web's link already sends: `lat.toFixed(6)/lon.toFixed(6)` |
| `MGRS@15RTN50008000` in search | One MGRS point | *To confirm* (ForeFlight article 220213388, snippet) |
| GPX 1.1 with an `<rte>` | A route, which lands in the FPL Editor and on the map | ForeFlight 18.4, May 2026. `<wpt>` or `<trk>` alone is not taken |
| Garmin `.fpl` | A route; altitudes and speeds are not imported | Documented for Finder only; from the share sheet *to confirm* |
| KML and KMZ ("User Map Shapes", "Custom Map Layers") | Point, LineString, LinearRing, Polygon, MultiGeometry, Style, StyleMap (normal style only), LineStyle, PolyStyle, IconStyle, `gx:LabelVisibility`; KMZ adds custom icons and rich descriptions | foreflight.com/support/user-content. Anything else is ignored. **No GroundOverlay.** Under 200 MB. A layer is named after its file, without the extension. A file of points alone asks whether to make it a layer or user waypoints |
| Content pack (a `.zip`) | `manifest.json`; `layers/` (KML, GeoJSON, raster MBTiles, geospatial PDF); `navdata/` (waypoints as KML or CSV, and PDF or TXT documents attached to a waypoint by its name); `byop/` (plates) | foreflight.com/support/content-packs. Arrives by AirDrop, Mail, the share sheet from Files, or `https://foreflight.com/content?downloadURL=`. Replaced or deleted whole, never in parts. Depends on the plan: individual subscribers, and Business or Military Flight Bag at Performance level and above |
| Raster | MBTiles (raster only), geospatial PDF; GeoTIFF on Military Flight Bag only | — |
| PDF | Documents | *To confirm* from the share sheet |

**Two facts this repo already knows about ForeFlight.**

- It **hangs on a polygon ring that crosses or touches itself**, where Google Earth draws it (`backend/mask_rings.py`, found 2026-10-28). So
  every ring EZ/PZ writes is simple, and is checked so.
- It **does not draw raster overlays from a KMZ.** That is why the threat masks go out as vector polygons.

### 3.2 What goes, and how

Each item has a **fastest path** (one tap, no file) where one exists, and a **file path** (the share sheet, with ForeFlight among its apps).
Everything can also go **together in one content pack** (§3.3).

| Item | Fastest path | File path | In the content pack |
|---|---|---|---|
| A route | *Open in ForeFlight*: the URL with the route's lat/long string (the web's and Android's `RouteHandoff.foreFlightUrl`, held to `routes/handoff.json`) | GPX 1.1 `<rte>` (preferred: the FPL Editor takes it, with names) or Garmin FPL (`RouteHandoff`, the same fixture) | The route as a coloured line with its named points labelled (`layers/`); its named points as user waypoints (`navdata/`) |
| A set of routes | — | **One GPX with a `<rte>` for each route**, in the set's order: the web's `buildSetGpxXml`, held to `routes/handoff-set.json` (a set of one route is that route's file byte for byte; hidden routes go too; no FLOT or phase line) | All its routes |
| FLOTs and phase lines | — | KML (`<MISSION> Control measures.kml`): a FLOT as a blue LineString with its scallops drawn as a second LineString of half-circles at a fixed ground size (KML has no screen-sized pattern), labelled FLOT; a phase line as a black LineString over a wider white one, with `PL <NAME>` as a labelled point beyond each end | `layers/<MISSION> Control measures.kml` |
| Backstops | — | In the LZ/PZs' KML: the dashed line (`gx:` dashes are not KML; drawn as short segments) and the arrow with its two-segment head, red over white, labelled `185°T · 2.4 km` | With the LZ/PZs |
| Threats | — | **KMZ**: a folder per threat, terrain-mask polygons per band, range rings, a marker (the server's `build_threats_kmz` format) | `layers/<MISSION> Threats.kml` |
| LZ/PZs | *Show in ForeFlight*: the target as a lat/long search | KML: boundary, target, aircraft rotor discs with their names, PZ arrows, sectors, go-arounds, the landing and takeoff headings, units as named points (icons in a KMZ) | `layers/<MISSION> LZs.kml`; each target as a user waypoint with its LZ card attached |
| MGRS grid | *Show in ForeFlight*: `MGRS@…` (*to confirm*) | KML of grid lines over the mission area (10 km and 1 km; 100 m only round an LZ), with labels as points | `layers/MGRS <zone>.kml` |
| One grid (map menu, *Share grid…*) | `MGRS@` or lat/long search | A one-point KML (ForeFlight asks: layer or waypoint) | — |
| Local points (`.LPS`) | — | KML of points, which ForeFlight offers to make user waypoints | `navdata/` waypoints |
| LZ card | — | PDF | Attached to the LZ's waypoint (`navdata/<NAME><display name>.PDF`) |
| Mission brief | — | PDF (`POST /api/brief/pdf`) | Attached to the mission's first waypoint, or left out (*to decide on a device*) |
| Slope map | — | — | Later: raster MBTiles over the LZ (§3.5) |
| **Everything but the LZ/PZs, as one picture** | — | **The web's mission overlay**: routes, FLOTs, phase lines, threats, local points and imported overlays drawn as pieces of 2,048 px with no base map (iOS: `MLNMapSnapshotter` over a style of the plan's layers alone), sent to the server's overlay job (`overlay_jobs.py`), which writes **MBTiles** (every ForeFlight subscription) or a **GeoTIFF in EPSG:4326** (Military Flight Bag), see-through or over USGS topo; never satellite or Mapbox (their terms), never an LZ/PZ (the owner, 2026-11-16) | Instead of the KML layers where a person chooses a picture, or alongside them |

### 3.3 The EZ/PZ content pack

One tap from the workspace (*Send to ForeFlight…*, §3.6) builds a zip on the device:

```
<MISSION>.zip
├─ manifest.json        {"name": "EZ/PZ <MISSION>", "abbreviation": "EZPZ", "version": <n>,
│                        "organizationName": "EZ/PZ", "effectiveDate": "<UTC now>"}
├─ layers/
│  ├─ <MISSION> Routes.kml     one Folder per route: a LineString in its colour; named points labelled
│  ├─ <MISSION> LZs.kml        one Folder per LZ/PZ: boundary, target, graphics, backstops
│  ├─ <MISSION> Control measures.kml   FLOTs and phase lines, one Folder per set
│  ├─ <MISSION> Threats.kml    one Folder per set and per threat: mask bands, rings, marker   (only when chosen)
│  ├─ <MISSION> overlay.mbtiles   the mission overlay, from the server's job       (only when chosen)
│  └─ MGRS <zones>.kml         grid lines and labels over the mission's extent    (only when chosen)
└─ navdata/
   ├─ user_waypoints.csv       WAYPOINT_NAME,Description,Lat,Lon (decimal degrees, west negative)
   └─ <WAYPOINT><display name>.PDF   e.g. "EZHAWKLZ card.PDF" attached to EZHAWK
```

Rules:

- **"Mission" is the workspace**: the open mission pack (its LZ/PZs, route sets and point sets), or outside a pack the open LZ/PZs and route sets
  (the brief's own rule for what is briefed).
  - Threats go in only when ticked, as the open threat sets; the export sheet says what leaves the app.
  - The mission's control measures go in with its routes: the open sets of them, or a pack's `CONTROL MEASURES` item.
- **Names are stable and the version goes up.** The pack's name is the mission's. `version` is a counter kept on the device for that name, so a
  pack sent again is a newer version of the same pack. Whether ForeFlight replaces it or keeps both is *to confirm* (§3.9).
- **Waypoint names follow ForeFlight's rule**:
  - at least three characters, capitals, one word, at least one letter;
  - a waypoint with the same name **overwrites** one already there.
  - So every EZ/PZ name carries a short prefix (`EZ`, or the pack's abbreviation), and is made from the point's name by the FPL identifier rule
    (`[A-Z0-9]`, numbered on a clash).
  - The length limit is *to confirm*; until then the FPL rule's 10 is used.
- **Nothing but the plan.** No account, no email, no server id, no device position. The KML's `<description>`s carry only the item's own text
  (a threat's information, a route point's type).
- **Built on the device, from the editors' documents as the person sees them**: unsaved edits included, as the brief compiles them. Nothing is
  sent to the server but the threats' KMZ request and, when the mission overlay is chosen, its pieces (both transient, as on the web).
- **Bounded.** The pack stays well under ForeFlight's 200 MB. A threat mask is simplified as `mask_rings.py` does. An MGRS grid at 1 km is drawn
  only over an extent of up to 100 × 100 km (else 10 km only), and at 100 m only within 2 km of an LZ.

### 3.4 KML rules

- **Every ring is simple and closed**, checked before writing (`isSimple`, the port of `mask_rings.py`'s check). A ring that cannot be made
  simple is left out and the sheet says so, rather than hanging ForeFlight.
- **Colours are KML's `aabbggrr`**, from the same palette the map draws: a route's colour; amber for detection and red for engagement (`#fbbf24`,
  `#ef4444`, as the server's KMZ); a band's own colour and alpha.
- **Labels** use `gx:LabelVisibility` on LineStrings, and on points that carry a name. Whether ForeFlight labels a polygon is *to confirm*. If
  it does not, a label point goes at the polygon's centroid, the way the open-source ForeFlightKML package works around it.
- **Coordinates to 6 decimals**, `lon,lat,0`. Text is escaped as the server's `_xml_escape` escapes it. One `<Document>` per file, one `<Folder>`
  per item, a `<Style>` inline per placemark (no shared styles: StyleMap uses only its normal style).
- **Circles** (rotor discs, range rings) are 72-segment geodesic rings, as the server's `_circle_coords`. **Arrows** are a LineString with a two-segment head.

### 3.5 Later, not in the first ForeFlight steps

- **The slope map as raster MBTiles** over each analysed LZ (`layers/<LZ> slope.mbtiles`), so ForeFlight shows the slope colours. The raster is
  ours, so it has no licence question. It needs tiles cut from the slope image on the device, and an MBTiles writer, which is SQLite via GRDB.
- **Imagery** in a pack only from public-domain sources (NAIP, as the offline packs plan). **Mapbox tiles are never exported**: their terms
  forbid keeping them outside Mapbox's SDK.
- **ForeFlight TAK** (Military Flight Bag One, 18.7): streams TAK positions. Whether it takes CoT drawings is unknown (§3.9).

### 3.6 How it looks

- ***Send to ForeFlight*** is in four places:
  - a route row's menu, and the route's plan;
  - the LZ/PZ panel's menu;
  - the Threats panel;
  - the workspace menu (the whole mission), and the map's long press (the grid under the finger).
  - On iPad it is also in the toolbar, with `⌘⇧F` for the mission.
- **One sheet**: what to send, with checkboxes for Routes, LZ/PZs, Threats, MGRS grid, Local points, LZ cards and the Brief, each saying what it
  will carry ("3 routes, 41 waypoints"). The choices are remembered on the device. Then one button, **Send**, which builds the pack and opens the
  share sheet with ForeFlight in it. The sheet carries the classification notice ("Not authorised for CUI or classified information").
- **The route's fastest path skips the sheet**: *Open in ForeFlight* opens the URL at once. The app declares `foreflightmobile` in
  `LSApplicationQueriesSchemes` and asks `canOpenURL` first. Without ForeFlight, the files go to the share sheet instead (Files, AirDrop to the
  ForeFlight iPad beside it).
- **On iPad, drag a route or the mission onto ForeFlight** beside EZ/PZ in Split View or Stage Manager. The drag carries the file as an
  `NSItemProvider`, so ForeFlight takes it if it takes drops (*to confirm*). A drop it does not take changes nothing.
- **App Intents**: *Send mission to ForeFlight* and *Open route in ForeFlight* in Shortcuts and Spotlight, for the open workspace.
- **iOS cannot choose the app for the person.** The share sheet lists ForeFlight among the apps, and puts the apps used last first, so after the
  first time ForeFlight is one tap.

### 3.7 Web first, fixtures for all

The KML, CSV and content-pack writers are **formats**, and the web is the reference for formats. So:

- **What the web has already** (since 2026-11-14 to 16): the route's and the route set's GPX (`foreflight.js`, `routes/handoff*.json`), the
  threats' KMZ and KML by set, and the mission overlay (`feature/missionOverlay/`, the server's `overlay_jobs.py`). iOS ports the first, calls
  the second and third, and draws the overlay's pieces itself.
- **On the web**, add `feature/foreflight/` (`kmlExport.js`, `contentPack.js`): pure functions from the documents to the files' text and bytes
  (the LZ/PZs with their backstops, the control measures, the grid, the waypoints, the manifest), with JSZip, which the web already has. The web gains the same *Send to ForeFlight* (a download, to AirDrop from a Mac). It is small, and it
  keeps the three clients agreeing.
- **Fixtures** in `contracts/fixtures/foreflight/`: KML per kind (routes, LZs with backstops, control measures, threats from fixed mask rings, grid), `user_waypoints.csv`, the
  manifest, the names (the waypoint rule, its clashes), the simple-ring check (bow-tie, a corner touch, a one-pixel neck, as
  `test_mask_rings.py`), byte for byte, with a fixed clock.
- **The threats' mask polygons on the device**: the server's KMZ traces the mask raster (`cv2.findContours`, then `approxPolyDP` until the ring
  is simple). Two ways to get the polygons:
  - **I6:** call `POST /api/threats-kmz` (transient and explicit, as today) and lay its polygons into our KML.
  - **Later:** port the tracing, so the device makes the polygons from `/api/threat-mask`'s raster, and later from an offline viewshed (the native
    plan's P3).
- **A fixture for the server's KMZ**: `threats/kmz.json`, which does not exist yet, written by backend pytest.
- **Android follows the same fixtures** whenever it gets KML for ATAK (HANDOFF lists KML and ATAK as waiting on the owner).

### 3.8 The spec first

`contracts/openapi.yaml` (API 2.1.0) does not describe `/api/route-share`, `/api/threats-kmz`, `/api/threats-kmz-link`, `/api/threats-ths`,
`/api/generate-excel`, the mission overlay's job routes or `/api/places/search`. By AGENTS.md §6, no app may rely on a route that is not in it. Add each, recorded in `network/responses.json`, before iOS
calls it. That is an additive change; the API version's minor moves.

Found in passing, not to fix here: `POST /api/threats-kmz` checks no `threats` feature and returns `str(e)` on an error
(`threat_routes.py`, `threats_kmz`, still so at `011100b4`).

### 3.9 To try on a device first (ForeFlight cannot run in the simulator)

1. Do the URL's lat/long tokens (`34.123456/-84.123456`) and `MGRS@…` work inside `q=`? How long can the URL be?
2. Does a GPX or an FPL handed over by the share sheet land in the FPL Editor? Does a GPX with several `<rte>` bring every route?
   Does the mission overlay's MBTiles open over the chart where it belongs (the web's EPSG:3857 GeoTIFF once opened on the whole world)?
   How do a FLOT's scallops and a phase line's casing read in ForeFlight's KML?
3. Is a content pack sent again under the same name and a higher `version` replaced or kept twice? And a KML?
4. Can several KML layers be shown at once? Are polygons labelled?
5. Does a PDF attached to a waypoint show on it? Does a PDF from the share sheet go to Documents?
6. Does ForeFlight take a drag and drop from another app?
7. Which ForeFlight plan do the crews have? Content packs need Performance or above on Military Flight Bag.
8. Does ForeFlight TAK take CoT shapes?

The answers settle §3.2's defaults. Until they are in, *Send* offers every path that is documented, and marks the ones not yet tried.

### 3.10 Other apps

The same files reach the other apps through the same share sheet:

- **Garmin Pilot**: FPL and GPX.
- **iTAK**: KMZ and GPX; a TAK data package later, to confirm.
- **Aero App**, Google Earth and Files: KMZ.

---

## 4. Technology and libraries

The native plan's choices stand. The owner approved its library list. Versions are current as of 2026-11-21.

| Concern | Android (as built) | iOS | New dependency? |
|---|---|---|---|
| Language | Kotlin 2.4, coroutines, Flow | Swift 6.4, strict concurrency, async/await, actors, `@Observable` | — |
| UI | Compose, Material 3, own sheet | SwiftUI (Liquid Glass, sheets with detents, `NavigationSplitView`, `.inspector`), UIKit where needed | — |
| Dependency injection | Hilt | Initialisers from one `AppEnvironment` | — |
| HTTP | OkHttp 4.12 | `URLSession`; `URLSessionWebSocketTask` for the live stream | — |
| JSON (typed) | kotlinx.serialization | `Codable` for the server's DTOs | — |
| JSON (documents) | `JsonElement` trees | Our own `JSONTree` (ordered keys, literal number text, null kept apart from absent, bounded nesting) | — |
| Database | Room 2.8 | **GRDB.swift 7** (7.12) | Approved |
| Map | MapLibre Android 13.6.1 | **MapLibre Native iOS 7** (SPM, `maplibre-gl-native-distribution`), `MLNMapSnapshotter` for the LZ card | Approved |
| 3D | OpenGL ES 3.0 | Metal, MetalKit | — |
| Google sign-in | Credential Manager | **GoogleSignIn-iOS 10** (SPM) | Approved |
| Apple sign-in | — | AuthenticationServices | — |
| Secrets | Keystore, `EncryptedSessionStore` | Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) | — |
| Threat file | Keystore AES-GCM | CryptoKit AES-GCM, key in the Keychain (`WhenUnlockedThisDeviceOnly`), file protection `.complete`, excluded from backup | — |
| Zip (`.msnx`, KMZ, content pack, `.xlsx`) | `java.util.zip` | **ZIPFoundation** (0.9.21) | Approved |
| XML | DOM, own `MsnxXml` | `XMLParser` into our own small DOM (`MsnxXml`); `LegsText` as text, as on Android | — |
| SQLite files (`.LPS`, `.ths` read) | Our `SqliteReader` | The same reader, ported | — |
| `.ths` write | Platform SQLite (`NO_LOCALIZED_COLLATORS`) | The system's SQLite through GRDB's raw connection | — |
| Symbols | `androidx.javascriptengine` | JavaScriptCore | — |
| SVG | AndroidSVG | **SwiftDraw** (0.30) | Approved |
| QR | ZXing (UTF-8 with an ECI header, level M) | Core Image `CIQRCodeGenerator`, read back in a test with Vision | — (§14: no ECI header) |
| QR scan | — | VisionKit `DataScannerViewController` | — |
| PDF | `PdfRenderer` | PDFKit; `UIGraphicsPDFRenderer` for the LZ card | — |
| Background | WorkManager | `BGTaskScheduler`, background `URLSession`, APNs | — |
| Location | `LocationManager` | Core Location, when in use only | — |
| Tests | JUnit 5, Robolectric, Roborazzi | Swift Testing; XCTest and XCUITest; **swift-snapshot-testing** (1.19) | Approved |
| Format and lint | Android lint, warnings as errors | **swift-format**, which ships with the toolchain (`swift format lint --strict`, `ios/.swift-format`), and Swift warnings as errors; SwiftLint is not used, since it would be a tool to install and pin | — |
| Release | Gradle | Xcode, fastlane (or Xcode Cloud) | Approved |

**Not taken:**

- **SwiftData and Core Data**: no shared schema with Room, and the sync store is a contract.
- **The ForeFlightKML package**: MIT, but our KML must agree with the web's fixtures, and the format is a page of code.
- **SceneKit and RealityKit** (the native plan's reasons).
- **Any analytics SDK.**

---

## 5. Architecture and modules

The native plan's layers and module names, mirrored from Android. Swift packages under `ios/`:

```
ios/
├─ EZPZ.xcodeproj          the app target, its Info.plist, entitlements, asset catalog
├─ App/                    composition root, scenes, deep links, the frames (phone, iPad)
└─ Packages/
   ├─ EZPZCore/            pure Swift, iOS 26 and macOS 26, run by `swift test` on the Mac
   │   CoreJS (built in I0: JSONTree, Js, JsNumber, what every port reads JSON and numbers with)
   │   CoreModel  CoreGeo  CorePlanning (built in I1)  DesignTokens (I1: Tokens.swift, held to WCAG on the Mac)
   │   CoreFormats  CoreBrief  CoreScene3D
   │   CoreSync   CoreMissionPacks  CoreTesting  ForeFlight
   ├─ EZPZData/            CoreNetwork  CoreData (GRDB)  CoreSymbols (JavaScriptCore, SwiftDraw)
   └─ EZPZFeatures/        DesignSystem  FeatureAuth  FeatureMap  FeatureWorkspace  FeaturePacks
                           FeatureBrief  FeatureViewer3D  FeatureExports
```

**Rules**

- **The pure modules import Foundation only**: no UIKit, SwiftUI or MapLibre. They build for macOS too, so their tests run with `swift test` in
  seconds without a simulator. They are the code the fixtures hold. `CoreTesting` reads `../contracts/fixtures` by a path from `#filePath`.
- **A feature never imports another feature**; `App` joins them. State flows one way, as on Android.
- **Stores are `@MainActor @Observable` classes**, one per screen family, like Android's view models.
  - Repositories, the sync engine, the pack engine and the document sessions are **actors**.
  - Planning maths runs in tasks off the main actor.
  - A store never holds a session's lock across an `await` that a user action can interleave with: §6's ordering trap.
- **Each Android class has a Swift twin of the same name** where the logic is the same (`DocumentSession`, `PackEngine`, `RouteHandoff`,
  `SketchOps`…). An agent can then port a file and its test together, and a later fix found on one platform is easy to find on the other.
- **The live-server tests** (`LiveServerTest`, the sync and pack scenarios against the real Flask routes) run from the macOS test host, which can
  start `backend/tests/live_server.py` as a child process. The simulator cannot start one. They run when `EZPZ_LIVE_PYTHON` is set, as on Android.

---

## 6. Porting rules for Swift

These traps are recorded in AGENTS.md §14 and §17. Each applies to Swift, often worse. Each gets a test that the port fails without it.

**Strings**

- **Swift's `String` counts grapheme clusters, and its `==` is Unicode canonical equivalence.**
  - JavaScript counts UTF-16 units and compares them exactly. So `"é"` precomposed equals `"é"` in Swift and not in JavaScript.
  - Ids, names and pack operations compare `.utf16` (or `.unicodeScalars`), never `String ==`.
  - Cuts are explicit:
    - AMPS widths and name limits by code points (`unicodeScalars`);
    - "NAME (my edits)" by UTF-16 units, never through a surrogate pair;
    - the web's `.length` by `utf16.count`.
- **JavaScript's `trim` and `\s`** include U+FEFF and Unicode spaces, but not U+001C. Port `Js.trim` with its exact code point list. Swift's
  `whitespacesAndNewlines` is neither.

**Regular expressions**

- **`\d` matches every script's digits** in Swift Regex and in `NSRegularExpression` (ICU), as it did on Android. Spell `[0-9]`.
- **Case-insensitive matching folds `ſ` and the Kelvin sign.** Spell both cases, as Kotlin's port does.
- **Split text on `unicodeScalars`, never `Character`s.** A `Character` is a grapheme cluster, so a `#`, `&` or `=` followed by a combining mark
  is one `Character` and a split on it misses (`GridShare`'s link fragments, found in I1). CoreGeo's patterns are small hand-written matchers over
  ASCII classes (`ScalarPattern`) rather than `Regex`, so no class can widen.

**Numbers**

- **JavaScript's `Math.round`** rounds halves up. Swift's `.rounded()` rounds halves away from zero, which differs for negatives (−2.5).
  Port `jsRound`.
- **`toFixed` rounds the exact binary value.** Port `toFixed`, never `String(format:)`, which is locale-sensitive and rounds otherwise.
- **JavaScript's number text** (`1`, `100000000000000000000`, `1e-7`) is `JsNumber.toText`, held to `formats/number_text.json`.
  Swift's `description` writes `1.0`, `1e+20` and `1e-07`. Its *digits* are JavaScript's (the shortest that read back, the closest of
  them), so `toText` lays Swift's digits out JavaScript's way; 461,755 doubles were checked against Node (I0, `ios/Scripts`).
- **Java's `Math.min` and `max` let NaN through, and Swift's do not**: `Swift.min(.infinity, .nan)` is infinity. The MGRS grid's boxes rely on the
  NaN going through, so CoreGeo has `javaMin` and `javaMax` (I1). And **Swift's `Int(_:)` traps** on NaN or a value out of range where Kotlin's
  `toInt()` gives 0 or saturates, and `+` traps where Kotlin's `Int` wraps: the ports saturate or wrap where Kotlin did (`limitsFor`'s wind
  reduction, `heading + 90` in the slope profile).
- **Dates are java.time's, counted by hand.** Foundation's Gregorian calendar turns Julian before 1582 and has no `atZone`, so `LocalDate` and
  `LocalDateTime` count days on the proleptic Gregorian calendar, and `RouteWinds.instantText` follows java.time across a change of daylight
  saving (a skipped time moves on by the gap, a repeated one takes the earlier offset). The tests run in New York and Kolkata.
- **`Number()`**: `"0x22"` is 34, `[34.7]` is 34.7, `true` is 1. Swift's `Double("0x22")` is nil, and `Double("0x1p3")` is 8. Port
  `JsNumber.of`.
- **Darwin's `sin`, `cos`, `atan2` and `exp` are not fdlibm.** The brief's strip charts and the sandtable fit are compared **bit for bit**, so
  the Kotlin port used `StrictMath`, which is fdlibm. Swift needs a pure-Swift port of the few fdlibm functions `Sphere` uses, held to the same
  fixtures, and the V8 `Math.hypot` algorithm as Kotlin has it. Elsewhere, tolerances as `mgrs/grid.json` sets them: Apple silicon already
  differs from x86-64 in the last digit.

**JSON**

- **Key order is JavaScript's**: array-index keys first, ascending, then insertion order. Swift `Dictionary` has no order: `JSONObject`
  keeps its entries in JavaScript's order itself, so walking one is always right (Kotlin's ports ask `Js.orderedKeys` at each walk).
- **Swift's synthesised `==` compares text canonically.** A struct of ids or typed text the ports compare (the drafts, `SketchOps`' ids, the set of
  colours in use) has hand-written equality by UTF-16 units, and an ordered map keyed by UTF-16 (`OrderedMap`) stands for a JavaScript object of
  values keyed by point id.
- **A String key collapses text JavaScript keeps apart.** A `Dictionary` or `Set` keyed by `String` takes `"é"` and `"e\u{301}"` for one
  key, as `==` does. Keys that stand for JavaScript text (an object's keys, `Js.idKey`) are `UTF16Key`, compared by UTF-16 units.
- **A number keeps its literal text** (`2.50` stays `2.50`; an integer past `Int64` is not rounded), as `JsonElement.toString()` keeps it.
- **null is not absent.** The pack engine sends `"after": null` and `{field: null}` on purpose; a codec that drops nulls breaks it.
- **Five thousand `[` must not overflow the stack.** The parser keeps a stack of its own, and nesting is bounded (64; the deepest fixture is
  15), because the writer, `==` and the walkers recurse, and so does **freeing a tree**: Swift releases a nested object inside its parent's
  release, so a tree tens of thousands deep ends the app as it goes away, which Kotlin's garbage collector never did (found in I0). Secondary
  threads have 512 KB of stack; a test runs the writer and `==` at the bound on one.
- **An enum's payload taken out and appended to copies it.** Binding `case .array(var elements)` makes a second reference to the buffer, so
  every append copies the whole list: the parser read half a million numbers in minutes until each container became a struct mutated in
  place on its stack (I0). Mutate collections where they live.
- **Reference identity is load-bearing.** An item no event touched keeps its instance, and stores tell what changed by identity, because an item
  can be megabytes. `JSONObject` and `JSONArray` are immutable final classes, as kotlinx's are, so an untouched value keeps its instance and
  `===` tells it from a new one; `Js.strictEquals` compares them by reference, as JavaScript does.
- **A lone surrogate cannot be held.** JavaScript text can hold `\ud800` alone; a Swift String cannot, so the parser reads it as U+FFFD. The
  web writes one only by its known bug of cutting text through an emoji (AGENTS.md §15), whose fixture cases are marked `webBug`.

**Concurrency**

- **The order of the main thread is part of the logic.** Android's queued test dispatcher hid four ordering bugs at sign-out and at going to the
  back (§14).
  - In Swift: `@MainActor` work resumed after an `await` runs later, not at once.
  - Test the lifecycle with writes that suspend, and with two observers of one state.
  - A sign-out writes and closes the open documents before it stops packs.
  - Going to the back (`scenePhase` `.background`, `beginBackgroundTask`) writes the open documents and the brief's waiting change, in work that
    cannot be cut short.
- **A failure delivered just before cancellation looks like a failure.** Ask `Task.isCancelled` (or `try Task.checkCancellation()`) before a
  `catch` does anything, as `stillWanted` does.
- **Persist before you publish.** An edit returns once it is on disk. A refresh token is written to the Keychain before the refused call is
  repeated. A started refresh is finished even if the caller has gone, in a task that is not a child of the caller.
- **The pack state changes ten times a second.** Narrow to what a view uses and drop repeats, or views rebuild for every pointer.
- **A form follows what changes under it**, and applies only what the person changed (`PointDraft.check(before)`, `UnitDraft.patchOver`).

**Files and hostile input**

- **`.msnx`, `.LPS`, `.ths`, KMZ, tilesets and `.pnts` are hostile.** Bounded reads; DOCTYPE and ENTITY refused **by text** before
  `XMLParser` sees them (its entity flags are best effort); the cut and corrupted fixtures run against the Swift readers too.
- **Files whose names differ only in case are one file on macOS.** Name a component and its pure module differently.

**The map, accessibility and lists**

- **The map reads an image's size from its scale.** MapLibre Android read a bitmap's density, and every aircraft was drawn density-times too
  small. On iOS, give each `UIImage` handed to `MLNStyle.setImage` the scale that says its size in points, and test a picture's size on a 3×
  screen.
- **What waits for the map is done after its first camera**, not before (Android's `MapHost.attach` trap).
- **A live region must exist before its words change.** On iOS, post `AccessibilityNotification.Announcement` for state changes, or keep one
  element whose label changes and post `.layoutChanged`. Test that the element survives the change.
- **Rows keyed by what they hold.** `ForEach` uses stable ids, never indices, or typed text lands in another row's field (the brief's trap).

**Not like the web, on purpose**

- iOS honours every decision Android took against the web. The list is in AGENTS.md §15 and §17. It includes:
  - pausing a pack's edits on a refusal about the account;
  - keeping refused edits as "NAME (my edits)";
  - reading server times as UTC;
  - closing the 3D view when the open LZ/PZ changes;
  - every open LZ/PZ drawn faded;
  - route points shaped by AMPS type;
  - applying values as they are typed, with refusals in words.

---

## 7. Screens on iPhone and iPad

The design is `docs/native-design/` (82 screens, `IP-*` for iPhone, `IT-*` for iPad, `tokens.json`). Read its outline, then its picture, then
its source, as its README says. The owner's later changes to Android's screens apply to iOS where they are about behaviour rather than
Material:

- the frame hidden by a tap on the map;
- the pack's state in the workspace control;
- the tools on the map's edge;
- *Add object here*;
- a long press holds;
- the doghouse fields in the held bar;
- the slope opacity slider.

### 7.1 iPhone

- **The map at the root**, under the top safe area, with floating Liquid Glass controls:
  - the workspace capsule, top left, showing the pack's state as Android's chip does;
  - Import and Library, then the account, top right;
  - Layers and My location, at the right edge;
  - the crosshair, and the readout pill (tap to copy).
- **The panel is a sheet with detents** (`.presentationDetents([.height(peek), .medium, .large])`,
  `.presentationBackgroundInteraction(.enabled(upThrough: .medium))`, `.interactiveDismissDisabled()`). The tab control (LZ/PZ, Routes, Threats,
  Imports; Pack first in a pack) sits in the sheet's bottom safe-area inset as a `GlassEffectContainer`: a system `TabView` cannot sit over a
  presented sheet (the design README).
- **The camera's padding** is the sheet's height up to medium, so the crosshair stays at the middle of the map that shows (Android's
  `MapPadding` rule, on MapLibre iOS's `contentInset`).
- **The route's plan, the Library and Members** are large-detent sheets or pushed views. Forms are sheets with Cancel and Save. Confirmations
  are `.alert` and `.confirmationDialog`, with *Keep Both*, *Replace* and *Cancel* for a name taken.
- **The brief on iPhone**: read and share a brief's PDF from the Library or a pack. Its setup and the sandtable stay on iPad, as Android's are
  on tablets (the owner's decision there).

### 7.2 iPad

- **The map full bleed.** A floating glass sidebar (`NavigationSplitView`, 340 pt) holds the workspace pull-down, the segmented panel picker and
  the panel.
- **A trailing `.inspector`** (320 pt) holds the open LZ/PZ's plan, or the route's nav log as a full **table**, which Android's tablet lacks.
- **Over the map, a toolbar** with the MGRS search, Import (`⌘I`), Library (`⌘L`), *Send to ForeFlight* and the account. Menus and the switcher
  are popovers; forms are form sheets (`.presentationSizing(.form)`).
- **Files dropped on the map** open the import review.
- **Below about 840 pt wide** (Split View, Slide Over, Stage Manager narrow), the iPad uses the iPhone's frame. The map and the documents
  are the same objects in both, so nothing is rebuilt when the frame changes (Android's movable-content lesson: one map view, moved, never two).

### 7.3 Input

- **Explicit modes** with Done and Cancel; place at the crosshair or by a tap.
- **A long press** opens the map menu headed by the grid, or holds and picks up what is under the finger. A secondary click on a trackpad or
  mouse opens the menu too.
- **Apple Pencil** draws a boundary and sketches a route. Pencil hover (`UIHoverGestureRecognizer`) shows the grid under the tip in the readout.
- **Keyboard shortcuts** from `.commands` and `UIKeyCommand`:
  - ⌘N for a new LZ/PZ at the crosshair, ⌘S to save (name), ⌘Z and ⇧⌘Z to undo and redo;
  - ⌘F for the search, ⌘I, ⌘L and ⇧⌘F;
  - in the sandtable: arrows, Page Up and Page Down (a presenter's clicker), Home, End and Escape.
- **Targets of at least 44 pt, and 56 pt for primary actions** (gloves).

### 7.4 Windows and displays

- **One planning window.** The editors hold one open LZ/PZ and one route set per process, as on Android. A second planning window would need
  sessions per scene, and is not in this plan.
- **The sandtable on an external display or AirPlay**: a second `UIScene` with role `.windowExternalDisplayNonInteractive` shows the step's map
  full screen. The iPad keeps the steps, the script and the controls. Two map views draw then; only the presenter's moves (the brief's rule).
- **Later**: the 3D view or a nav log in a window of its own under Stage Manager, read-only.

### 7.5 Look

- **The palette** is the redesign's (`contracts/tokens/tokens.json`), generated into `Tokens.swift` by `contracts/scripts/tokens.py` (I1),
  beside Android's `Tokens.kt`. It is in `EZPZCore`'s pure `DesignTokens` target rather than `DesignSystem`, so Android's contrast and night tests
  run on the Mac with `swift test`; `DesignSystem` turns its numbers into SwiftUI colours.
- **Dark by default.** The red **night** palette is the same as Android's and never bright: the map dimmed, and the system's own sheets tinted so
  they never flash white.
- **Dynamic Type throughout.** Every screen is tried at the accessibility sizes, and the layouts that Android had to stack at large text are
  stacked here too.
- **VoiceOver**: the map has list views of everything on it, as on Android.

---

## 8. Data, security and privacy

- **The database** is GRDB over SQLite, with the same tables as Room's version 4:
  - `record` keyed by (kind, uuid), with `updated_at`;
  - `blob` keyed by SHA-256, for a mission's file;
  - `outbox`, `sync_state`;
  - the four pack tables, `pack`, `pack_item`, `pack_op_outbox` and `pack_own`.
  - The schema is migrated with GRDB's `DatabaseMigrator`. Its SQL is compared in a test with the schema Room exports
    (`android/core-data/schemas/`), so the two stores stay one design.
  - GRDB has no 2 MB cursor window, but rows of 5 MB are still kept in mind (read only the columns needed).
- **Files**:
  - `Application Support/` (database, imports) with file protection `.completeUntilFirstUserAuthentication`, so a background sync can run;
  - `isExcludedFromBackup` on all of it: LZ locations stay out of iCloud backups (the native plan);
  - caches in `Caches/`: tiles, symbols, tilesets (a week, at most 256 MB, cleared at sign-out), exports.
- **Exports** are written to a temporary folder for the share sheet, and cleared after a day; threat files, KMZ and content packs carrying
  threats after an hour; all of them at sign-out (Android's `ShareExport` rule).
- **The session** is in the Keychain, this device only, after first unlock. A session the server ended leaves a note in its place, so the next
  launch says why (`EndedSession`).
- **The plans on a device belong to one account** (`AccountScope`): another person signing in sees nothing of them until they clear them or sign
  out. A sign-out wipes the threat file, the exports, the weather cache, the tileset cache and the notifications.
- **Nothing leaves without the person**: GPS never; threats only through the terrain mask, the KMZ call or an export they press; no analytics.
  MetricKit only.
- **Info.plist**:
  - `NSLocationWhenInUseUsageDescription`;
  - `LSApplicationQueriesSchemes = [foreflightmobile]`;
  - the document types and UTTypes:
    - imported: `.msnx` (zip), `.ths` and `.lps` (SQLite), `.kml`, `.kmz` and `.gpx`;
    - exported: none of our own; we write others' formats;
  - `ITSAppUsesNonExemptEncryption = NO`;
  - associated domains for Universal Links.
- **Store obligations**, from the native plan: App Privacy labels, account deletion in the app, Sign in with Apple, a reviewer account past the
  `.mil` gate, and the privacy policy URL.

---

## 9. Background work and notifications

- **Sync**:
  - at launch, on coming to the front, after an edit (as Android's `SyncScheduler` asks), and on going to the back, in `beginBackgroundTask`'s
    time;
  - in a `BGAppRefreshTask` when iOS gives one;
  - a sync in a process the system started reads the session first (Android's A1).
- **Mission packs** run only in the front and for the open pack, as on Android. Their queued edits go in the background drain before the
  Library's sync.
- **Notifications** (the server's outbox, `docs/NOTIFICATIONS.md`): iOS will not run a 15-minute poll. So:
  - **iN1, polling** as the app comes to the front, and in background refreshes, with the same rules as Android's N1:
    - words made on the device from names only, shown on the lock screen (the owner's call);
    - a tap acted on only for the account signed in, as it was shown;
    - the toast instead while in front.
  - **iN2, an APNs push that says only "look now"** (NOTIFICATIONS §4.2's content-free design): no name, no pack, no type in the push. The app
    wakes, asks `/api/notifications`, and posts what it finds.
    - Server work: a device-token route, an APNs sender with its key kept server-side (`APNS_KEY_*`), and a push from `notify.add`'s
      transaction once it commits.
    - This is the owner's N4 brought forward for iOS, and the owner's call (§15).
- **The permission** is asked only where the person sees what for, as on Android: the gate's *Notify me*, and the switcher's line.

---

## 10. Backend and contracts changes iOS needs

| # | Change | Why | When |
|---|---|---|---|
| 1 | The iOS client id in `GOOGLE_CLIENT_IDS` | Google sign-in | I2 |
| 2 | `POST /api/auth/apple` (verify Apple's identity token against its keys; private-relay addresses go through the `.mil` code) | Guideline 4.8 | I2 |
| 3 | `/.well-known/apple-app-site-association` on `ezpztac.app` (Vercel `public/`), naming the bundle id and the links (`?auth=`, `?invite=`, `#grid=` reaches the app by its path) | Universal Links | I2 (owner: Team ID, bundle id) |
| 4 | `MIN_APP_VERSION_IOS` used (exists); `X-EZPZ-Client: ios/0.1.0 (n)` | Version gate, login history | I2 |
| 5 | `openapi.yaml`: `/api/route-share`, `/api/threats-kmz`, `/api/threats-kmz-link`, `/api/threats-ths`, `/api/generate-excel`, the overlay job's routes, `/api/places/search`, recorded in `responses.json` | Apps call only routes in the spec (§6 of AGENTS.md) | Before I6 |
| 6 | `contracts/fixtures/foreflight/*` and `threats/kmz.json`, with the web's `feature/foreflight/` | §3.7 | I7 |
| 7 | `tokens.py` writes `Tokens.swift` (done in I1; `ios.yaml` runs its check) | One palette | I1 |
| 8 | APNs: `POST /api/notifications/devices` (`{token, platform}`), the sender, and its settings | §9 | iN2 (owner) |
| 9 | `contracts/README.md` says which fixtures iOS reads (done in I0, a paragraph; a column when the table has more to say); `.github/workflows/ios.yaml` (`macos-26`, path-filtered: `ios/**`, `contracts/**`; done in I0, not yet run on GitHub) | CI | I0 |
| 10 | AGENTS.md: an iOS section in §17 (as Android's), the `ios` scope already exists in `.releaserc.json` (no web release) | Agents | Each step, in its PR |
| 11 | `planning/graphicScale.json` and `planning/controlMeasures.json` (done in I1), and the leg doghouses' fixture, written by the web's Vitest (§1.4) | Backstops, FLOT, phase lines, scaling and leg doghouses held as everything else is | I1; the leg doghouses before I5 |

---

## 11. Steps

Each step is a PR into `develop` (`feat(ios): …`), small enough to review, merged when its tests pass even though nobody installs it. The order
puts **routes and ForeFlight early**, since that is what the iPad adds, and leaves mission packs and 3D, whose engines are the largest ports,
until the app plans and exports on its own. Weeks are rough, for one person with agents.

| Step | Delivers | Held to / checked by | Weeks |
|---|---|---|---|
| **I0 Foundations** *(built 2026-11-21)* | `ios/` project (`EZPZ.xcodeproj`, its sources a synchronized `App` folder) and the `EZPZCore` package; `Config/Version.xcconfig` (0.1.0, `EZPZ_API_MAJORS`, which `api_version.py check` now reads); swift-format; `ios.yaml` CI; `CoreTesting` (fixtures found from its own path, JSON compared with a tolerance); `CoreJS`: `JSONTree` (JSON.parse's grammar, JavaScript's key order, literal number text, null apart from absent, immutable objects and lists, a bounded parser with its own stack), `Js` (`orderedKeys`, `strictEquals`, `idKey`, `truthy`, `round`, `text`, `trim`, `cut`, `prop`), `JsNumber` (`toText`, `toFixed`, `of`, `parseInt`, `parseFloat`); bundle id placeholder (`app.ezpztac.unreleased`, `.debug` for debug builds, as Android) | `formats/number_text.json`, `workspace/doghouses.json`'s `jsParse`, every JSON fixture read and written back; 461,755 doubles against Node; the app launched on the iPad simulator | 0.5 |
| **I1 Pure core, part 1** *(built 2026-11-22: 423 tests, in any time zone; `DesignTokens` too; the leg doghouses wait for their fixture, before I5)* | `CoreModel` (diagram and its normalizer, `DiagramOps`, doghouses, units and SIDC, aircraft draft, sketch routes, point sets, threats, weather), `CoreGeo` (MGRS both ways, the grid, parsing, the target reading, `GridShare`), `CorePlanning` (capacity, separation, graphics, backstops, graphic scale, route calc, winds and elevations, slope call, profile, leg doghouses), control measures (FLOT, phase lines, `isMeasuresSet`), threat sets, `Tokens.swift` | `mgrs/*`, `coords/*`, `workspace/*`, `planning/*` (`backstops.json`, `graphicScale.json`, `controlMeasures.json`), `threats/sets.json`, `routes/sketch.json`, `routes/winds.json`, `symbols/sidc.json`, `aircraft/limits.json`, `localpoints/match.json` | 1.5 |
| **I2 Network, sync, auth** | `CoreNetwork` (one transport, refresh with persist-before-use and the 30 s grace, priority gate, typed calls), `CoreSync` and GRDB's `RoomSyncStore` twin, the scenarios on the fake, the real Flask server and GRDB; `FeatureAuth` (email, Google, Apple, verify and reset by Universal Link, the `.mil` gate), the shell's gate rules (minimum version, account scope, ended session) | `network/responses.json` strict decode; `LiveServerTest`; the per-kind scenarios | 1.5 |
| **I3 Map and frames** | `FeatureMap` (MapLibre iOS, three base maps, readout, search with readings, GPS, the MGRS grid layer, `MapProjection`), the iPhone frame (glass controls, sheet, tab control, frame hidden by a tap) and the iPad frame (sidebar, inspector, toolbar); night palette; the long-press menu | Simulator screenshots in dark, light and night, both forms, large text; `MapProjection` tests | 1.5 |
| **I4 LZ/PZ** | Workspace (open set, autosave, Save as naming, Library records), analysis (SAM, slope by heading, contours, profile, opacity, LZ box), weather tiles, every graphic with drag, turn, the held bar and the tool rail, **backstops** (sketched, the arrow moved and aimed), the measurement line, graphics drawn smaller as the map zooms out, units with `CoreSymbols` (JavaScriptCore, presets, SwiftDraw), aircraft profiles, boundary drawing with Pencil | `workspace/*`, `planning/*`, `symbols/*` (`script.json` against JavaScriptCore); an LZ made on the iPad opens unchanged on the web, and the reverse | 2 |
| **I5 Routes and files** | Routes panel and plan (iPhone pages, iPad nav-log table), **the FLOT, phase lines and control measures** (drawn, flipped, named, their own sets), a doghouse on each leg, sets made, moved between and combined, undo across documents, `CoreFormats` (`MsnxReader`, `MsnxWriter`, `MsnxMutator`, `LegsText`, `SqliteReader`, `ThsReader`, `ThsExport`, `FileKinds`, `RouteHandoff`), local points (made by hand too; `.LPS` and GPX out), routes from `.fpl` and `.gpx` and overlays from `.kmz` and `.kml`, Import menu, review, Imports panel, documents from other apps and drag and drop, the Library page; **F1: *Open in ForeFlight* and GPX/FPL by share sheet, a whole set as one GPX** | `msnx/*`, `sqlite/*`, `threats/*`, `routes/handoff.json`, `routes/handoff-set.json`, `planning/controlMeasures.json`; a real AMPS mission round-trips | 2 |
| **I6 Threats** | Threats panel and form, **threat sets in the Library** (written as they change, masks kept with their threats; the sealed file only without `threats` or `cloud_save`), `.ths` written on the device, terrain mask; **F2: threats as KMZ** (the server's KMZ, by the explicit button); grid sharing with QR; the LZ card (snapshot, `.xlsx` from the server, PDF on the device) | `threats/export.json`, `sqlite/tables.json`'s `threats.ths`, `threats/kmz.json` | 1 |
| **I7 ForeFlight hub** | On the web first: `feature/foreflight/` and its fixtures. Then iOS: `ForeFlight` module (KML writers per kind, names, CSV, manifest, simple-ring check, content pack), the *Send to ForeFlight* sheet, App Intents, iPad drag out, the export cache rules; **F3–F6** (LZ/PZs with backstops, control measures, grid, local points, LZ card and brief PDFs as waypoint documents), **the mission overlay** (pieces from `MLNMapSnapshotter`, the server's job, MBTiles or GeoTIFF) | `foreflight/*`; **the owner's device run of §3.9** | 1 |
| **I8 Mission packs** | `CoreMissionPacks` (ops, diff, session, own fields, sentences, history, client, engine, invitations; every item kind, `threatset` and a `CONTROL MEASURES` route item included), GRDB pack store held to `PackStoreContract`, the editors' join, the live stream, and every pack screen (switcher, Pack panel, read-only, finished, pointers, new pack, members, teams, copies, update, kept edits) | `packs/*` strictly; `PackScenarioBook` on the fake, GRDB and the real server; two devices (an iPad and the web) end to end | 2.5 |
| **I9 Brief** | `CoreBrief` (compile, light, strips with the fdlibm port, strip grid, sandtable steps and fit, the setup's edits), the iPad setup, PDFKit review, Share and Save, the sandtable (and on an external display), iPhone reading; **the brief to ForeFlight Documents** | `brief/*` bit for bit; `contracts/briefs` byte for byte | 1.5 |
| **I10 3D** | `CoreScene3D` (frame, readers, camera, tile choice, budgets, terrain and imagery tiling, routes in 3D, compass), the Metal renderer (points with attenuation and eye-dome lighting, log depth, terrain, imagery, route lines, curtains, labels), streaming with `frame`, the tileset cache, build on save | `viewer3d/*`; Metal's validation layer on in tests; simulator screenshots against the web's view at the Ellijay LZ | 2 |
| **iN Notifications** | iN1 polling; iN2 APNs (if the owner agrees, with server change 8) | NOTIFICATIONS fixtures and the outbox's recorded answers | 0.5 |
| **Field and release** | TestFlight internal build; the field checklist (§12) on the owner's iPad and iPhone; fixes | Owner | 1 |

The total is about 18.5 weeks of work if each step runs to its estimate. Several steps are ports of code that has already been debugged, which
brings it down. Hence the 10 to 14 weeks, ±30%, given in §0.

**Later, shared with Android:**

- P3: offline map packs, the on-device viewshed and its mask tracing, the on-device `.xlsx`.
- The slope map as MBTiles in a content pack.
- On-device SAM (Core ML).
- A TOT Live Activity and widgets.
- A planning window per scene.

---

## 12. Testing and verification

- **Pure modules**: Swift Testing under `swift test` on macOS, every fixture a test, every refusal asserting its reason. Each port fix gets a
  mutation check, as Android's did. Run mutants with a clean build: no cached objects survive a run.
- **Stores and sessions**: Swift Testing with an in-memory GRDB, and the lifecycle orderings of §6.
- **Live server**: the scenarios against `backend/tests/live_server.py`, from the macOS host.
- **Screens**:
  - swift-snapshot-testing on the simulator, in dark, light and night, on iPhone and iPad, at the largest text;
  - **agents check screens on the simulator** with the iOS Simulator tool (build, launch, tap, screenshot), the map and 3D included;
  - XCUITest for the flows: sign in; target → analyze → graphics → save; sketch → export; a pack between two simulators.
- **Metal**: validation and GPU frame capture on in debug. The renderer's buffer sizes are checked as Android's GL shadows check them.
- **Performance**: `XCTApplicationLaunchMetric`, hitch ratio with 500 graphics, the native plan's budgets, on the floor devices (iPhone 13,
  iPad 10th generation, if iOS 26 is the minimum; §15).
- **Device only**:
  - ForeFlight (§3.9), iTAK and Garmin Pilot;
  - GPS and Pencil;
  - Universal Links (they need the site's file);
  - APNs, background refresh timing;
  - sunlight and night in a cockpit, gloves.
- **The field checklist** before a TestFlight external build: a mission planned in airplane mode (as far as the app is offline before P3), a
  mission sent to ForeFlight and flown in it on the ground, a pack edited between an iPad and the web, the night palette in a dark cockpit, the
  sandtable on a display.

---

## 13. Release

- **Versions**:
  - iOS's own, **0.x until its first App Store release** (HANDOFF §5), in `ios/Config/Version.xcconfig` (`MARKETING_VERSION`); the build number
    is CI's run number;
  - the API majors the build talks (`EZPZ_API_MAJORS`, the twin of `ezpz.apiMajors`), so `contracts/scripts/api_version.py check` holds it with
    the others;
  - `MIN_APP_VERSION_IOS` compares iOS's version, never the product's.
- **Commits** `feat(ios): …`. `.releaserc.json` already gives `ios` `release: false`.
- **Distribution**: TestFlight (internal, then external), then the public App Store, as decided. The owner uploads and submits; agents do not
  (AGENTS.md §2). Signing in an encrypted fastlane match repository or Xcode Cloud, the owner's choice.
- **MDM**: managed app configuration (server URL, features off) read at launch, so a unit can deploy it on managed iPads.

---

## 14. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| A third implementation drifts | A plan reads differently on the iPad | The same fixtures, strictly; web first; one PR for a formula on all three |
| Swift's `String` and `Double` semantics differ from JavaScript's | Ids that compare equal in Swift and not on the server; names cut through an emoji; numbers written `1.0` | §6, each with a test that fails without it |
| ForeFlight changes or does not take a path | A *Send* that does nothing | §3.9 on a device before defaults are set; several paths offered; content pack and plain files both |
| Content packs need a ForeFlight plan crews lack | The one-tap mission send is not open to them | Separate KML, GPX and PDF files, each sendable alone |
| iOS background limits | Notifications late; a sync waits for the next launch | APNs push (owner); sync on coming to the front and going to the back |
| `CIQRCodeGenerator` has no ECI header | A label in a non-Latin script reads wrongly on some scanners | Test with Vision and with ZXing's reader (test only); if it fails, a vendored generator (owner's approval, as Android's choice of ZXing was) |
| MapLibre iOS lags MapLibre Android | A layer or call missing | Pin 7.x; the GL glue is thin; the plain-data scenes (`LzScene`, `RouteScene`) are pure and tested |
| A minimum of iOS 26 drops older iPads | Some unit iPads cannot install | The owner decides (§15); iOS 18 is possible without Liquid Glass's APIs, at the cost of drawing the glass by hand |
| App Review and the `.mil` gate | A slipped release | A reviewer account past the gate, review notes |
| One person on three clients | Slower work everywhere | iOS after Android's main work; ports, not new designs |

---

## 15. Decisions needed from the owner

1. **Minimum iOS and iPadOS: 26 (proposed) or 18.** 26 brings the Liquid Glass APIs the design is drawn with, and drops iPads older than the
   8th generation.
2. **Bundle id, Team ID and the Apple developer account** (organisation with a D-U-N-S number, or individual). They are needed for Universal
   Links, Sign in with Apple, APNs and TestFlight.
3. **Sign in with Apple**, with the new `POST /api/auth/apple`. It is needed while Google sign-in is offered.
4. **APNs "look now" pushes for iOS notifications** (server work, a key kept server-side), or polling only.
5. **ForeFlight**:
   - Which ForeFlight subscription the crews have. Content packs need Performance or above on Military Flight Bag.
   - Whether the brief goes into the pack.
   - Whether *Open in ForeFlight* (the URL) or the GPX is the route's first choice. The device run of §3.9 informs this.
6. **The web's own *Send to ForeFlight*** (`feature/foreflight/`, the reference for the fixtures). It is the cheapest way to keep three clients
   agreeing.
7. **Threats in the content pack**: ticked by the person each time (proposed), or never.
8. **iPhone scope for the brief**: reading only (proposed), as Android's phone has none.
9. **The sandtable on an external display.**
10. **CI**: a self-hosted Mac mini, or GitHub's macOS runners (about ten times Linux minutes).
11. **The Mapbox token** for a native app, which sends no web referrer. This is the same open question as Android's.
