# Plan: Planning Graphics in the 3D View

Status: Phase 1 (routes) built on `feat/3d-lz-route` as a preview, 2026-10-01;
the depth-test question below is settled. Phases 2–4 not started.
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
**Out, for now:** editing in 3D, 3D aircraft models, threats, route flythrough
(see Phase 4).

---

## Decisions (and why)

### 1. View-only. Plan in 2D, look in 3D.

Picking and dragging against terrain in 3D is a large piece of work for little
gain — precise placement already works in 2D. The 3D window shows what was
planned.

### 2. Read the same state the 2D map draws. Store nothing new.

`App.js` already hands every graphic to `MapView`. The 3D window takes the
same values, so with both open a change in 2D appears in 3D. Nothing is
copied or saved, so there is nothing to keep in sync — and threats, if they
are ever added, stay in memory only (AGENTS.md §2).

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

`POST /api/terrain/heights` in `backend/app/routes/terrain.py`.

```json
// request — coordinates only in the body, as for the LiDAR routes
{ "points": [{ "lat": 34.5964, "lon": -84.1281 }, ...] }

// response — metres; null where there is no data
{ "groundM": [412.7, ...],   // ellipsoidal: on the surface the viewer draws
  "geoidM":  [-30.35, ...] } // add to an MSL height to get Cesium's height
```

- Ground comes from the same DEM catalogue as `services/terrain/tiles.py`, converted
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

- **Backend:** the heights endpoint, with a helper in `services/terrain/tiles.py`
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

- **Threats:** range as a translucent dome; each altitude band's viewshed
  mask as a polygon at that band's height. Draw only from React state and
  the `/api/threat-mask` results already there. Send no threat data to any
  new endpoint.
- **Fly the route:** a camera following the route at planned speed
  (Cesium `SampledPositionProperty` and the clock).
- **3D aircraft model:** a glTF asset rather than a dependency, but it needs a
  model with a licence we can use.
- **Editing in 3D**, if Phases 1–3 make the case for it.

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

---

## Files

| File | Change |
|---|---|
| `backend/app/routes/terrain.py` | `POST /api/terrain/heights` |
| `backend/app/services/terrain/tiles.py` | Height sampling beside `geoid_offset` |
| `backend/tests/test_terrain_heights.py` | New |
| `frontend/src/feature/viewer3d/sceneGraphics.js` (+ test) | New — state to shapes |
| `frontend/src/feature/viewer3d/useSceneHeights.js` (+ test) | New — fetch and cache heights |
| `frontend/src/feature/viewer3d/Viewer3D.jsx` | `graphics` prop, `CustomDataSource`, camera modes, depth-test change |
| `frontend/src/feature/viewer3d/Lz3DWindow.jsx` | Pass `graphics` through |
| `frontend/src/App.js` | Pass routes and the active LZ's graphics |
| Phase 3: `backend/lidar/pipeline.py`, `build.py` | Canopy-top raster per tileset |
| `AGENTS.md` | §4 and §6 when it ships |
