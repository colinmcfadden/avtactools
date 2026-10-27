# Plan: Planning Graphics in the 3D View

Status: Phase 1 (routes) built on `feat/3d-lz-route` as a preview, 2026-10-01;
the depth-test question below is settled. Phases 2–4 not started; Phase 4's
threats planned in full, 2026-10-27.
Builds on the 3D LZ view (`feat/3d-lz-route`); nothing here has to change for
that to ship first.

## Goal

Show the plan in the 3D window, over the real terrain and point cloud: routes
at their planned altitude with labelled SP/CP/RP/LZ points, and the LZ
graphics — LZ boundary, aircraft positions, go-arounds, sectors of fire,
doghouses, units, PZ markers.

The payoff is what 2D cannot show: whether a leg clears the ridge *between*
two waypoints, and how the approach sits against the actual treeline.

**In scope:** view-only; the active LZ's graphics plus every visible route.
**Out, for now:** editing in 3D, 3D aircraft models, threats (planned in
Phase 4), route flythrough (see Phase 4).

---

## Decisions (and why)

### 1. View-only. Plan in 2D, look in 3D.

Picking and dragging against terrain in 3D is a large piece of work for little
gain — precise placement already works in 2D. The 3D window shows what was
planned.

### 2. Read the same state the 2D map draws. Store nothing new.

`App.js` already hands every graphic to `MapView`. The 3D window takes the
same values, so with both open a change in 2D appears in 3D. Nothing is
copied or saved, so there is nothing to keep in sync. Threats are drawn from
the same state; what the server computes for them in 3D (the mask floor,
Phase 4) is runtime only and never saved (AGENTS.md §2).

### 3. One pure adapter does the thinking; `Viewer3D` only draws.

`feature/viewer3d/sceneGraphics.js` turns app state into a list of plain shape
descriptions — positions with final heights, labels, colours, which items
appear. `Viewer3D` turns that list into Cesium entities and nothing more.

The 3D picture cannot be unit tested and the in-app preview cannot composite
WebGL (AGENTS.md §14). The adapter can be tested in Jest, so all the logic
that can be wrong — heights, units, labels, `lng` vs `lon` — lives there.

### 4. Graphics never rebuild the viewer.

`Viewer3D`'s main effect re-creates the whole Cesium viewer (WebGL context,
tilesets, terrain) whenever one of its dependencies changes. Graphics must not
be one of them, or dragging a helicopter in 2D reloads the point cloud. They
go into a `Cesium.CustomDataSource` that a separate effect clears and refills.

### 5. Place route points at the planner's MSL — the number AMPS receives.

`createMsnx.js` and `mutateMsnx.js` write `computeRoutePlan`'s `mslFt` as
`CmdAlt`. For an AGL-planned point, that MSL was converted using the planner's
ground elevation from `/api/elevations` (Terrarium/SRTM). So the planner's MSL
is what will actually be flown, and the 3D view should show exactly that,
over the best ground we have (the USGS DEMs the 3D terrain is built from).

The consequence is deliberate: where the planner's ground is wrong, the 3D
view shows the *real* clearance, not the intended one. SRTM and the USGS DEMs
can disagree by tens of feet under forest.

Labels therefore carry both:

- the planned MSL, matching the route panel;
- the AGL measured against the 3D ground — flagged when it differs from the
  planned AGL by more than a set amount, because a crew should know the
  planner's ground was off there.

Fallback: an AGL point with no planned MSL (its elevation fetch failed) is
placed at 3D ground + AGL and labelled as such. Never invent a height for
missing data (AGENTS.md §14).

Altitudes come from the same call the route panel makes
(`RoutePlanSection.jsx`: `computeRoutePlan(route, plan, route.elevations || {})`),
so the 3D labels and the panel cannot disagree.

### 6. Apply the geoid correction on the server.

Cesium heights are WGS84 ellipsoidal; route altitudes are MSL. Passed through
unchanged, every MSL altitude draws **30.35 m (~100 ft) too high** in north
Georgia — the same trap as the LiDAR work (AGENTS.md §13). The browser has no
geoid model. The backend has one (`terrain_tiles.geoid_offset`, using the
PROJ grids the terrain tiles already depend on), so route heights and the
rendered terrain always use the same correction.

### 6a. Pickup and landing zones are drawn on the ground.

A target at either end of a route is where the aircraft sits on the ground.
Its planned altitude describes the leg flown in, so drawn literally the LZ pin
hung 50 ft over the trees. Route-end targets go on the 3D ground instead,
labelled with the zone's elevation MSL, and the legs either side become the
climb out and the descent in. Display only — the plan and the AMPS export keep
the planned value. A target in the middle of a route is overflown and keeps
its altitude; where the ground is unknown, the planned altitude is used.

### 7. A new heights endpoint rather than changing `/api/elevations`.

`/api/elevations` feeds the planner and the AMPS export. Switching it to the
local DEMs would change exported numbers; that is a separate decision (open
question 2). The new endpoint serves the 3D view only.

---

## The heights endpoint

`POST /api/terrain/heights` in `backend/routes/terrain_routes.py`.

```json
// request — coordinates only in the body, as for the LiDAR routes
{ "points": [{ "lat": 34.5964, "lon": -84.1281 }, ...] }

// response — metres; null where there is no data
{ "groundM": [412.7, ...],   // ellipsoidal: on the surface the viewer draws
  "geoidM":  [-30.35, ...] } // add to an MSL height to get Cesium's height
```

- Ground comes from the same DEM catalogue as `terrain_tiles.py`, converted
  with the same offset, so a point sits on the surface the viewer renders.
  Coarse terrain levels are decimated, so a point exactly on the ground can
  look slightly off when zoomed far out; it matches up close.
- Sample with rasterio's `sample()` grouped per DEM file — not a windowed read
  per point.
- Cap the request (around 5,000 points); a densified 40 km route at 30 m steps
  is about 1,300.
- `Cache-Control: private` — it is behind auth (AGENTS.md §14).

The client converts once: `heightM = mslFt × 0.3048 + geoidM`.

---

## How each graphic maps

| Graphic | Source (`App.js`) | In 3D |
|---|---|---|
| **Routes** | `importedRoutes`, `sketchedRoutes` (`visible`, `color`, `points`) | Polyline at absolute height (Cesium polylines have no height reference) plus a translucent `wall` down to the ground in the route's colour. The wall is what makes height readable in 3D. |
| **Route points** (SP, CPs, RP, TGT) | `points` where `kind !== "shaping"` | Point, label and a drop line to the ground. Label: `.CP3 · 1,850' MSL · 310' AGL`. `disableDepthTestDistance` keeps labels readable behind ridges; `scaleByDistance` and `distanceDisplayCondition` declutter. |
| Shaping points | `kind === "shaping"` | Not labelled. Altitude interpolated by distance between the AMPS points either side. |
| **LZ name** | `activeDiagram.flightData.lz_label` / `lz_name` | Label above the target. |
| **LZ boundary** | `detectedLZ`, `customLZ` | Ground-clamped outline. |
| **Helicopters** | `helicopters` (`lat`, `lon`, `rotation`, `profileId`) + profile `rotor_diameter_m` | Ground-clamped disc at true rotor radius, plus the icon rotated to heading. Separation, not the airframe, is the point. |
| **Go-arounds** | `goAround` (`lat`, `lon`, `direction`, `rotation`) | The existing arrow SVG as an image on a ground-draped `rectangle`, rotated from state. |
| **Sectors of fire** | `sectorsOfFire` — points use **`lng`**, not `lon` | Ground-clamped translucent polygon. |
| **PZ markers** | `pzMarker` | Billboard and label. |
| **Doghouses** | `doghouses` (`id_val`, `heading`, `time`, `dist`, `airspeed`) | A card drawn on a canvas (a box and four lines of text) as a billboard that faces the camera; the heading is in the text. No html2canvas. |
| **Units** | `units` | `feature/symbols/milsym.js` already builds the milsymbol symbol; its SVG becomes the billboard image. |

Ground-clamped shapes drape over the terrain, and the point cloud sits on that
terrain, so they show between the points. Whether they also tint the points
themselves is untested.

---

## The depth-test problem — settle this first

`Viewer3D.jsx` sets `scene.globe.depthTestAgainstTerrain = false` because
ground returns, sitting exactly on the surface, sank into the terrain. With it
off, every polyline and billboard draws *on top of* the terrain: a route
behind a ridge shows through the ridge, which hides the very thing this
feature is for.

Options:

- **a.** Turn it back on and check whether ground returns still sink, now that
  points and terrain come from the same DEMs with the same geoid correction.
- **b.** Bias the *rendered* terrain down by about 1 m, so the ground returns sit
  just above it. Render only — the heights endpoint stays true. One metre is
  invisible at route scale.
- **c.** Polyline `depthFailMaterial`, drawing the hidden part of a route dimmed
  or dashed. It needs depth testing on, so it pairs with (a) or (b).
- **d.** Leave it off and rely on computed clearance (Phase 3) to show
  conflicts by colour.

**Result (spike, 2026-10-01): keep it off, add (c), rely on (d).** With depth
testing on, the point cloud looked unchanged — but routes were hidden almost
everywhere. Distant terrain is drawn coarse (Cesium's level of detail), and a
route at 50 ft AGL sits below that coarse surface along most of its length,
so nearly every leg rendered as hidden and the curtains vanished. Occlusion
against terrain says more about the level of detail than about clearance.

With it off, routes are still depth-tested against the point cloud, so a leg
flown through the canopy draws dashed across it (the `depthFailMaterial`) —
the case that matters near the LZ. A route behind a ridge does show through
the ridge; clearance against terrain is Phase 3's job, in numbers.

---

## Phases

### Phase 0 — depth-test spike

The experiment above. Small; nothing else depends on its code, only on its
answer.

### Phase 1 — routes in 3D

Most of the value.

- **Backend:** the heights endpoint, with a helper in `terrain_tiles.py`
  beside `geoid_offset`.
- **Frontend:**
  - `sceneGraphics.js` — routes and points to shapes.
  - `useSceneHeights.js` — fetches heights for the visible route points and
    leg samples. Debounced (~500 ms) while points are dragged in 2D; caches by
    point in memory; never puts coordinates in a URL.
  - `Viewer3D` takes a `graphics` prop and draws it into a `CustomDataSource`.
  - `Lz3DWindow` passes `graphics` through; `App.js` passes the routes.
  - Camera: **LZ** (today's view) and **Route** buttons. Route frames the
    bounding sphere of the visible routes.
- **Done when:** a sketched route from a PZ to the LZ draws at its planned
  MSL; labels match the route panel; the wall meets the ground; and a
  deliberately low leg visibly meets the ridge.

### Phase 2 — LZ graphics

The rest of the table above: LZ boundary, helicopters, go-arounds, sectors,
PZ markers, units, doghouses. Mostly drawing; the heights are all "on the
ground".

### Phase 3 — clearance, the thing only 3D can do

- Densify each leg (about 30 m steps), compare planned height to ground, and
  colour the route by clearance. Merge runs of one colour into one segment
  rather than drawing every sample.
- A small list in the 3D window: minimum clearance per leg, and where.
- **Against the treetops near the LZ.** The LiDAR build already computes height
  above ground (`filters.hag_nn`). Write a canopy-top raster beside each
  tileset (highest return per ~2 m cell, ellipsoidal) and have the heights
  endpoint also return a `surfaceM` where one covers the point. Only the
  point-cloud area has this; everywhere else is bare-earth terrain.
- Thresholds come from the owner (open question 1). Don't invent them —
  the same rule as tip clearances (AGENTS.md §13).

### Phase 4 — maybe

- **Threats:** where each radar can see and where terrain hides an aircraft.
  Planned in full below (*Threats*), 2026-10-27; not started.
- **Fly the route:** a camera following the route at planned speed
  (Cesium `SampledPositionProperty` and the clock).
- **3D aircraft model:** a glTF asset rather than a dependency, but it needs a
  model with a licence we can use.
- **Editing in 3D**, if Phases 1–3 make the case for it.

#### Threats

What 3D can show that the 2D mask cannot is the *shape* of the hidden
airspace: how high above a valley an aircraft is still behind the ridge, and
whether a planned leg stays under that height. For each visible threat whose
mask the person asked for:

- **The ground shaded** by how low an aircraft must fly there to be hidden
  from each radar: seen at the lowest band, hidden below 50 ft, below 250 ft,
  below 500 ft. The bands and colours are the threat's own, as in 2D.
- **The mask floor**: a translucent surface at the lowest height the radar
  sees, drawn only where it stands clear of the ground. A leg under the sheet
  is hidden; a leg above it is seen.
- **Each radar's range**, as a dome cut wherever the floor is above it.
- **The stretches of each visible route a radar can see**, from the route's
  planned altitude against the floor.

##### The floor comes from the viewshed we already run

`viewshed()` in `backend/routes/threat_routes.py` walks rays out from the
antenna and keeps, along each ray, the steepest angle any earlier cell blocks
(`prev_max`, with the curvature-and-refraction drop already taken off the
terrain). A cell is marked seen at a band when
`ground + band − drop − antenna ≥ prev_max × d`. Moving the terms over, the
lowest height an aircraft is seen at that cell is

```
floor = antenna + prev_max × d + drop      # metres, in the DEM's own datum
```

so the floor is one more line in the ray loop, with no new geometry:

```python
floor_ray = radar_elev_m + prev_max * dd + drp      # −inf on a ray's first step
np.minimum.at(floor.reshape(-1), rr * W + cc, floor_ray)
```

- A cell several rays cross keeps the **lowest** floor, as `visible` today is
  the OR of every ray.
- A floor below the ground is clamped to the ground: seen down to the
  ground. A cell no ray reached (out of range, off the grid) is **no data**,
  never "seen" or "hidden" (AGENTS.md §14).
- **It is the 2D mask, exactly.** On the same DEM, `floor ≤ ground + band` is
  true at precisely the cells `viewshed(..., band)` marks seen, because
  `ground` is the same for every ray through a cell. That is the backend's
  main test, and it means one pass per radar replaces one pass per band. (The
  2D route could then be three times quicker; that is a separate change, and
  only if its PNGs stay byte-identical.)
- Ground range, not slant range, as the 2D mask and the rings measure it. At
  1,000 ft and 25 nmi the two differ by about 11 m.

##### A sibling route, not a new field

`POST /api/threat-floor`, beside `/api/threat-mask` in `threat_routes.py`,
rather than an optional field on the mask route:

- The 3D floor wants a different DEM and datum from the 2D mask (below). One
  answer holding a Terrarium PNG and a local-DEM floor would hold two masks
  that disagree at their edges.
- `/api/threat-mask` and the KMZ stay byte for byte what they are.
- A new route is additive: `contracts/scripts/api_version.py check` passes and
  `bump` makes it a minor (1.1.0 → 1.2.0). Describe it in
  `contracts/openapi.yaml` when it is built, since Android will call it.
- `@jwt_required`, `@require_feature('threats')`, `@terrain_tiles.pauses_warming`;
  added to `PRIORITY_PATHS` in `feature/auth/requestPriority.js`, so the 3D
  window's own terrain tiles wait while it runs.

```json
// request: what /api/threat-mask takes, and nothing else (no name, notes or routes)
{ "lat": 34.61, "lon": -84.22, "radars": [ { "type": 0, "rangeNmi": 25, "antennaHeightFt": 20,
  "aglNotMsl": true, "bands": [ { "altFt": 50 }, { "altFt": 250 }, { "altFt": 500 } ] } ] }

// response
{ "source": "local_highres_cog",      // or "terrarium": which ground the floor was computed over
  "resolutionM": 58.0,
  "bounds": [[south, west], [north, east]],
  "radars": [ {
    "type": 0,
    "rangeM": 46300,
    "antennaM": 451.7,                  // the antenna, metres above the ellipsoid: the dome's centre
    "floor": "data:image/png;base64,…"  // below
  } ] }
```

**The floor's encoding.** One RGBA PNG per radar over `bounds`, on the grid
the viewshed ran on:

- `R × 256 + G − 32768`: the floor in whole metres **above the ellipsoid**
  (Terrarium's layout without its fraction; a metre is about 3 ft, finer than
  any band). Cesium's frame, so it compares directly with the route heights
  `routeScene.js` already places.
- `B`: how many of the radar's bands an aircraft is hidden at there (0 = seen
  at every band, 3 = hidden at all three). This is the 2D mask's information,
  so the client colours the ground from it with the threat's own colours, and
  knows where the floor is clear of the ground without knowing the ground.
- `A`: 255 where a ray reached, 0 for no data. Nothing in between, because a
  browser premultiplies alpha and would change the other channels. Decode
  with `createImageBitmap(…, { premultiplyAlpha: "none",
  colorSpaceConversion: "none" })`; the decode itself is a pure function over
  the RGBA bytes, tested in Jest.

**Size and time.** The grid is the 2D mask's: about 1,600 cells across the
range's diameter, so about 58 m at 25 nmi, never finer than the DEM. Two
things to measure before the web side is built, on the owner's workstation
with the north Georgia DEMs, at the default 25 nmi detection and 15 nmi
engagement:

- **Time.** One viewshed pass per radar (today's mask makes six). The new
  cost is reading the local DEMs for a 93 km square; `_load_mosaic` warps
  every COG it touches, and reading them without overviews could take tens of
  seconds. Target: well under 30 s for two radars, far inside the 180 s
  gunicorn timeout, because the analysis holds the one interpreter and with
  it everyone's terrain tiles. If it is slower, read the COGs' overviews at
  the grid's resolution.
- **Size.** About 10 MB of RGBA before compression for the 25 nmi radar.
  Target ≤ 1.5 MB per radar on the wire; if over, halve the grid beyond about
  10 km from the LZ the 3D window is open on, where the camera never looks
  closely.

##### Which ground the mask is computed over

**The local USGS DEMs, in ellipsoidal heights, where they cover the range;
Terrarium otherwise, and the answer says which.**

Today's mask is Terrarium (about 60 m, then blurred 3 × 3) in MSL, while the
3D ground is the local DEMs plus the geoid. A Terrarium floor drawn over the
3D ground sits up to tens of metres under or above it, so a sheet that should
lie 50 ft over a valley floor dives through it, and the route comparison
measures against a ground nobody sees. So:

- Load the range's square through `terrain_provider.LOCAL_CATALOG` (as
  `load_best_terrain` does), sized to the grid above rather than its usual
  1,024-pixel cap. Keep `_prep_dem`'s void fill and 3 × 3 blur and the antenna
  on the 3 × 3 maximum, so the floor's bands are what the same DEM's 2D mask
  would give.
- Add the geoid (`terrain_tiles._geoid_transformer`) on a coarse grid
  (about 33 × 33) interpolated across the square: the geoid changes over tens
  of kilometres, not cells. Without the grids, answer 503 `geoid_unavailable`
  where `/api/terrain/heights` would refuse; never hand back MSL as
  ellipsoidal (AGENTS.md §13: PROJ reports success with no grids).
- Where the local DEMs cover less than `_load_mosaic`'s 99% of the square,
  use Terrarium (plus the geoid) for the **whole** grid, with `source:
  "terrarium"`. Never splice the two: a seam between DEMs that disagree by
  metres casts false shadows along it. The 3D window then says the mask was
  computed over 60 m open terrain data and may sit above or below the 3D
  ground.
- A side benefit: over the local DEMs, the threat's area is not fetched from
  AWS tile by tile.

The 2D mask and the KMZ stay on Terrarium unless the owner says otherwise
(question 7), so near a band's edge the 2D overlay and the 3D shading can
differ by a cell or two.

##### Trees and buildings: bare earth, unless the owner decides otherwise

Radar does not stop at foliage: how much a canopy hides depends on the band,
the season and the trees. Counting the canopy as cover errs in the dangerous
direction, showing an aircraft as hidden when it may be seen. So the floor is
bare earth. If the owner wants trees counted (question 6), it is an optional,
labelled "canopy as cover" view, only inside the point cloud's area, from
Phase 3's canopy-top raster, and the route comparison stays on bare earth.

##### Drawing it in Cesium

All of the thinking is in a pure adapter, `feature/viewer3d/threatScene.js`
(decode, colours, which triangles, the domes' vertices, route exposure),
tested in Jest; `Viewer3D` draws its output into a `threats` data source and
primitive collection, refilled by its own effect, never rebuilding the viewer
(Decision 4).

1. **Ground shading** — the floor's `B` channel coloured per cell with the
   threat's band colours on a canvas, laid on the globe as a
   `SingleTileImageryProvider` over `bounds`, engagement over detection.
   Imagery is part of the globe, so it has none of the depth problems below.
   Inside the point cloud the ground returns cover it, and it shows between
   the points; a screenshot will say whether that reads.
2. **The floor sheet** — a translucent `Primitive` built from the floor grid,
   decimated to at most 256 × 256 vertices, in the radar's colour at about
   25%. **Only triangles whose cells are all hidden at the lowest band**
   (`B ≥ 1`), so the sheet is always at least 50 ft above the ground it was
   computed over. This matters because `depthTestAgainstTerrain` is off (see
   the depth-test spike): a sheet allowed near or under the ground would draw
   through the terrain. Where an aircraft is seen at the lowest band, the
   ground shading says so instead. Opaque route lines draw before translucent
   primitives, so a leg under the sheet shows tinted through it and a leg
   above it shows clear: the picture of hidden and seen without a legend.
3. **Range domes** — a sphere of `rangeM` round `antennaM`, meshed by hand
   (2° of azimuth by 2° of elevation), a vertex kept only where it is above
   the floor at the ground point under it and below a **ceiling** (question
   9). A 25 nmi dome is 46 km high; drawn whole it dwarfs the LZ and puts the
   camera inside it. Cut at a ceiling, what remains is a translucent wall at
   the edge of range, open where terrain hides an aircraft. The ring on the
   ground (a clamped polyline, detection amber, engagement red, as 2D) is
   always drawn.
4. **Route exposure** — beside Phase 3's clearance. `placeRoutes` already
   gives every visible route as runs of `[lon, lat, heightM]` every 50 m, in
   ellipsoidal metres from the planned MSL. A sample is **seen** when it is
   within ground range of the radar and its height is at least the floor
   there, taking the **lowest** of the four floor cells round it, so a
   stretch is called hidden only if it is hidden from all of them. Out of
   the floor's data is "not known", never hidden. Seen stretches are drawn as
   a wider translucent sleeve in the radar's colour under the route line,
   leaving the line's own colour to Phase 3's clearance; and listed in the
   window: `ROUTE 1 · seen by THREAT 2 (engagement) for 1.8 km, .CP2 to .CP3`.
   A landing zone the radar sees on the ground is listed too. The rule is
   geometry only: whether a few seconds of exposure matter is doctrine, not
   ours to set (question 10).

##### When the 3D view asks, and threat privacy

AGENTS.md §2: a threat's position goes to the server only in an explicit
viewshed, KMZ or QR request, and the server keeps nothing.

- **The 3D window has a *Threats* switch, off when it opens.** Turned on, it
  asks `/api/threat-floor` once for each visible threat whose floor it does
  not have, or whose position or radars changed since (keyed on the request
  body). It does not ride on the 2D mask fetch, which runs on every save and
  move: people who never open 3D send nothing more than today.
- The request carries the position and radars only: no name, information,
  symbol or route. Routes never leave the browser for this; exposure is
  computed on the client.
- The answer lives in React state beside the threat (`floor3d`, as `mask`
  is), runtime only: never saved, synced, or put in a mission pack, and gone
  with the threat or at sign-out. When threats themselves are saved and
  shared (step *T*), their floors still are not.
- **The server keeps nothing, and keys nothing by place.** No cache of floors
  or of the DEM square read for one: a cache file named by its area would
  record where a threat is. The response is `Cache-Control: no-store`, as the
  KMZ's is. Errors are fixed words; the route never returns `str(e)` or prints
  a traceback with the body's values, as `/api/threat-mask` does today.

##### Android follows

The native renderer (`docs/ANDROID_3D_PLAN.md`) takes the same route and the
same encoding: `core-scene3d` decodes the PNG (pure, held to a fixture the web
writes), the renderer lays the shading over its terrain tiles' imagery, draws
the sheet and domes as meshes, and `RouteScene3D`'s placed routes give the
exposure. Android sends a threat only when the person presses a button, so
the 3D page's *Threats* switch is that press. It is a step after V7, planned
when the web's version has had a screenshot from the owner.

##### Steps

- **T0 — the 2D mask on the 3D ground (web only, no server change).** Drape
  each visible threat's existing `mask.png` over `mask.bounds` as imagery, with
  the range rings. Quick, and shows whether shading on the 3D ground reads at
  all; its shadows are Terrarium's.
- **T1 — the floor on the server.** `viewshed_floor` beside `viewshed`,
  `POST /api/threat-floor`, the local DEM with the geoid and the Terrarium
  fallback, the spec and the minor bump. Tests: the floor's bands equal
  `viewshed`'s at every band on the same DEM; the geoid applied (about −30 m
  in north Georgia; skips without grids); 503 without grids where required;
  `source` honest at the edge of the DEMs; no data out of range; the encoding
  round-trips; auth and the `threats` feature; `no-store`; nothing written to
  disk. Then the time and size measured above, written here.
- **T2 — shading and sheet in 3D.** The switch, the request, the decode, the
  shading and the sheet. Done when the owner's screenshot shows the sheet
  over a valley behind a ridge from the threat, clear of the ground, and a
  leg flown under it tinted.
- **T3 — range domes,** at the owner's ceiling.
- **T4 — route exposure,** with Phase 3 or after it.

---

## Performance

- **The Route camera streams terrain across tens of kilometres.** Coarse levels
  are slow from a cold server. The terrain speed work — gunicorn threads, a
  server-side tile cache, a low-resolution mosaic for coarse levels — should
  land before or with Phase 1's Route camera.
- **Entity counts are small**: tens to low hundreds. Clearance samples are
  computed, not drawn one by one.
- **One heights request per route change**, debounced.

---

## Testing

- **Backend (pytest):** the heights endpoint applies the geoid (around −30 m in
  north Georgia; skips without PROJ grids, like the existing geoid test);
  returns `null` where there is no data; enforces the point cap and auth;
  answers `private`.
- **Frontend (Jest):** the adapter — MSL placement (`mslFt × 0.3048 + geoidM`),
  the AGL fallback, shaping-point interpolation, `lng` sectors, labels equal
  to `computeRoutePlan`'s values, hidden routes left out.
- **Threats (Phase 4):** pytest holds the floor to `viewshed()` band by band
  on one DEM, the geoid, the Terrarium fallback, and that nothing is written;
  Jest holds the decode, the sheet's triangles (never one with a cell seen at
  the lowest band) and route exposure against synthetic floors.
- **Visual:** headless Chromium with SwiftShader renders WebGL well enough to
  check layout (it was used for the LiDAR work). The owner's screenshot on a
  real GPU is the final word on how it looks.

---

## Open questions (owner)

1. **Clearance thresholds** — what counts as green, amber and red, in AGL feet?
   Doctrinal values, not guesses.
2. **Should the route planner and AMPS export use the local DEMs where they
   exist**, instead of Terrarium? Planned AGL would then match the 3D view,
   but it changes exported numbers.
3. **Which routes appear in 3D** — every visible route, or only those passing
   within some distance of the LZ?
4. **Toggles** — does the 3D window need its own per-graphic toggles, or should
   it follow the 2D layer toggles?
5. **The depth-test spike's outcome** — needs a screenshot.

Threats in 3D (Phase 4):

6. **Do trees and buildings count as cover?** The plan is bare earth: radar
   does not simply stop at foliage, and counting the canopy shows an aircraft
   as hidden when it may be seen. If yes, only inside the point cloud's area,
   as a labelled option.
7. **Should the 2D mask and the KMZ move to the local DEMs too**, so 2D and 3D
   agree to the cell? It changes the shapes in exported KMZs, and would make
   the 2D mask one pass per radar instead of one per band.
8. **When the 3D view asks for the floor**: the plan is a *Threats* switch in
   the 3D window, off when it opens, so nothing more is sent for people who
   never use it. The alternative is to fetch it with every 2D mask.
9. **The domes' ceiling.** A 25 nmi dome is 46 km high. Cut at a ceiling, it
   becomes a wall at the edge of range. What ceiling: the threat's highest
   band, a fixed altitude AGL (3,000 ft?), or no domes, only the rings?
10. **Route exposure**: is "any sample at or above the floor is seen" the right
    rule, or does doctrine allow a tolerance (seen for under N seconds, or
    within N ft of the floor)? Doctrinal values, not guesses.
11. **Colours**: the 3D shading uses each threat's own band colours, as 2D
    does. Should the sheet and domes use the radar's colour (detection amber,
    engagement red) or the band colours?

---

## Files

| File | Change |
|---|---|
| `backend/routes/terrain_routes.py` | `POST /api/terrain/heights` |
| `backend/terrain_tiles.py` | Height sampling beside `geoid_offset` |
| `backend/tests/test_terrain_heights.py` | New |
| `frontend/src/feature/viewer3d/sceneGraphics.js` (+ test) | New — state to shapes |
| `frontend/src/feature/viewer3d/useSceneHeights.js` (+ test) | New — fetch and cache heights |
| `frontend/src/feature/viewer3d/Viewer3D.jsx` | `graphics` prop, `CustomDataSource`, camera modes, depth-test change |
| `frontend/src/feature/viewer3d/Lz3DWindow.jsx` | Pass `graphics` through |
| `frontend/src/App.js` | Pass routes and the active LZ's graphics |
| Phase 3: `backend/lidar/pipeline.py`, `build.py` | Canopy-top raster per tileset |
| Phase 4: `backend/routes/threat_routes.py` | `viewshed_floor`, `POST /api/threat-floor` |
| Phase 4: `backend/terrain_provider.py`, `terrain_tiles.py` | The range's square from the local DEMs at a given grid; the geoid over a square |
| Phase 4: `backend/tests/test_threat_floor.py` | New |
| Phase 4: `contracts/openapi.yaml`, `backend/api_version.py` | The route; minor bump |
| Phase 4: `frontend/src/feature/viewer3d/threatScene.js` (+ test) | New — decode, shading, sheet, domes, route exposure |
| Phase 4: `frontend/src/feature/viewer3d/Viewer3D.jsx`, `Lz3DWindow.jsx` | The *Threats* switch, a `threats` data source and primitives |
| Phase 4: `frontend/src/feature/threats/useThreats.js` | `fetchFloor`, `floor3d` kept beside `mask` |
| Phase 4: `frontend/src/feature/auth/requestPriority.js` | `/threat-floor` in `PRIORITY_PATHS` |
| `AGENTS.md` | §4 and §6 when it ships |
