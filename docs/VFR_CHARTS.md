# VFR charts — plan

Status: **proposed, not built** (2026-11-05). The owner finds the VFR base map too soft next to SkyVector and ForeFlight. This file
proposes replacing it with chart tiles EZ/PZ builds itself from the FAA's free digital charts: the sectional everywhere, then the
Terminal Area Chart (TAC) and the Helicopter Route Chart stacked over it where they exist, on the web and Android. **§10 holds the
questions for the owner**, which need answers before anything is built. Every number here was measured on 2026-11-05 against the
FAA's 10-29-2026 edition and today's ArcGIS service, unless it says it is an estimate. Where this plan and the code disagree once it is
built, the code wins (AGENTS.md §16).

**Zoom levels.** This file uses two kinds:
- **Web zoom** is Leaflet's, the zoom the web shows and the owner speaks of ("LZ planning at 15–17").
- **Tile level** is the level in a tileset of 512-pixel tiles. It is web zoom − 1, which is also MapLibre's zoom on Android (§17, *The map*).

---

## 1. Why the VFR map is soft today

The web (`frontend/src/feature/mapStyles/mapStyles.js`), Android (`feature-map`'s `MapStyles.kt`) and the brief's figures
(`backend/brief/figures.py`) all draw the FAA's ArcGIS service, `tiles.arcgis.com/.../VFR_Sectional/MapServer`. Its `?f=json` says:
- 256-pixel tiles, at 96 dpi, as `MIXED` (JPEG where there is no transparency) at quality 75;
- levels 8 to 12 only (`minScale` 1:2,311,162, `maxScale` 1:144,448), though its tile scheme lists 0 to 23.

The source is the problem, not the service. A sectional is printed at 1:500,000 and scanned at 300 dpi, so one of its pixels is
**42.3 m** of ground (measured, §2). The service's level 12 is 38.2 m a pixel in Web Mercator, about 31.7 m on the ground at 34°N: about
the chart's own detail. At web zoom 16, a pixel on the screen is 2.0 m of ground at 34°N. Today's tile is therefore enlarged 16 times,
and the chart's own detail 21 times (42 times on a 2× phone screen). The browser and MapLibre enlarge it smoothly, and that smoothness
is the blur.

Laid side by side at the same zoom (§3.1), the service's sectional is no blurrier than one we would build from the same file. It has
two faults of its own, both visible:
- **Jagged edges.** The FAA resamples its GeoTIFFs by nearest neighbour, and the service resamples again.
- **JPEG artefacts** at quality 75.

**SkyVector and ForeFlight show the same FAA rasters.** They look sharper for three reasons:
1. They stack the finer charts where they exist: the TAC (1:250,000) and, ForeFlight, the helicopter charts (1:125,000).
2. Their tiles are clean, with no JPEG and no second nearest-neighbour pass.
3. ForeFlight also draws vector airspace and airports over the raster. Vectors stay sharp at any zoom (§11).

---

## 2. What the FAA publishes

**Where.** The FAA's *VFR Raster Charts* page (`faa.gov/air_traffic/flight_info/aeronav/digital_products/vfr/`) links each chart's
GeoTIFF as a zip under `https://aeronav.faa.gov/visual/<MM-DD-YYYY>/`:
- `sectional-files/<Name>.zip`: 53 zips; 37 cover CONUS, 15 Alaska, and one Hawaii, with the Honolulu, Mariana and Samoan insets.
- `tac-files/<Name>_TAC.zip`: 30 zips.
- `heli_files/<Name>_Heli.zip`: 9 zips.
- `grand_canyon_files/Grand_Canyon.zip`, two `Caribbean/` charts, and one zip per kind under `All_Files/`.

The current edition is **10-29-2026**. Its metadata says it runs *20261029* to *20261223*, renewed *every 56 days*. Every zip, all
four checked, is a GeoTIFF with an FGDC `.htm` and a `.tfw`.

**Updates.**
- The next edition is due **12-24-2026**. Its folder answered 404 on 2026-11-05.
- This edition's files were posted on 8 October, **three weeks before** they took effect (`Last-Modified`).
- The last edition, **09-03-2026**, still downloads. Its Atlanta sectional and TAC have the same pixel sizes and dimensions as the current edition's.

**Sizes, 10-29-2026** (from `Content-Length`):

| Kind | Zips | Download |
|---|---|---|
| Sectional (all) | 53 | 3.29 GB (`All_Files/Sectional.zip`) |
| TAC (all) | 30 | 0.85 GB |
| Helicopter (all) | 9 | 0.29 GB |
| Grand Canyon | 1 | 38 MB |

**What each file is**, measured from every georeferenced file in the edition. The resolutions were read over HTTP from inside the zips;
the four files we unzipped (Atlanta sectional and TAC, Washington and Baltimore helicopter, US Gulf Coast helicopter) were read whole.

| Chart | Scale | Pixel (ground) | Native web zoom at the chart's middle |
|---|---|---|---|
| Sectional, CONUS | 1:500,000 | 42.33 m | 11.3 (49°N) – 11.7 (24°N) |
| Sectional, Alaska | 1:500,000 | 42.33 m | 10.3 – 10.9 |
| TAC, and Grand Canyon, and the Honolulu inset | 1:250,000 | 21.17 m | 12.3 – 12.8 |
| Helicopter route charts (11 sheets: Baltimore, Washington, Boston, Chicago, Dallas-Ft Worth, Detroit, Houston N and S, Los Angeles E and W, New York) | 1:125,000 | 10.58 m | 13.4 – 13.7 |
| Eastern Long Island helicopter chart | 1:250,000 | 21.17 m | 12.5 |
| Helicopter insets: Washington, O'Hare, Love Field, Downtown Manhattan, Boston Downtown | 1:90,000 – 1:50,000 | 4.23 – 7.62 m | 13.9 – 14.8 |
| US Gulf Coast helicopter, Caribbean 1 and 2 | 1:1,000,000 | 84.67 m | 10.7 |

*Native web zoom* is where one chart pixel is one CSS pixel. On a 2× screen, one chart pixel fills one device pixel a zoom higher.

**What the files hold**, from their own metadata and from reading them:
- Each file is one band of 8-bit palette colour (256 entries), LZW-compressed, in stripes, not tiled, in a Lambert conformal conic projection on NAD83.
- A file is **the whole printed sheet**: the legend, the margins and the insets as well as the chart. Its *Bounding_Coordinates* are those of the whole sheet, matching what we measured. **The FAA publishes no outline of the chart itself (the neatline).**
- The georeferencing is a second-order polynomial. Its stated error is **up to 2 pixels RMS**: about 85 m on a sectional, 42 m on a TAC, 21 m on a helicopter chart. Two charts may disagree by that much where they meet.
- The FAA says to disregard the georeferencing of insets, except in the Hawaiian Islands file.
- Some zips hold files that are not charts:
  - 20 TAC zips hold 21 **VFR Flyway Planning charts** (`… FLY.tif`), georeferenced and covering about their TAC's area, at 1:250,000.
  - Anchorage-Fairbanks holds `Anchorage Graphic.tif`, and New York `New York TAC VFR Planning Charts.tif`. Neither is georeferenced.
- The FAA asks to be acknowledged in derived products, and not to imply its endorsement or use its seal. The charts are US Government works.

---

## 3. What this will and will not fix

### 3.1 Side by side (measured)

We made 512-pixel tiles from the 10-29-2026 GeoTIFFs: palette expanded to RGB, warped to Web Mercator with Lanczos, encoded as WebP.
We laid them beside today's ArcGIS tiles for the same ground:
- **Sectional at the same zoom** (Atlanta, and the Ellijay survey area): the same detail, as §1 predicts. Ours has smooth edges where
  ArcGIS's are jagged, and no JPEG blocks. A cleaner sectional, not a more detailed one.
- **Atlanta TAC at web zoom 13**: twice the detail. Small text such as fix names stays legible at the zoom where the sectional's has blurred.
- **Washington helicopter chart at web zoom 14**: a different map. Helipads, hospitals, roads and route names are drawn where the sectional
  shows only Class B shading.

### 3.2 Where the finer charts are

The finer charts cover major airports and cities, **not most Army aviation posts**. Each post below was checked against each chart
file's bounding box; the neatlines (§4.2) are to confirm.

| Place | Finer than the sectional |
|---|---|
| Fort Belvoir (Davison AAF) | Washington helicopter chart, Baltimore-Washington TAC |
| Fort Carson (Butts AAF) | Colorado Springs TAC |
| JBLM (Gray AAF) | Seattle TAC |
| Dobbins ARB | Atlanta TAC |
| Wheeler AAF (Oahu) | Honolulu inset (1:250,000) |
| Fort Wainwright (Ladd AAF) | Fairbanks TAC |
| Fort Campbell, Fort Bragg, Fort Hood, Fort Rucker, Fort Bliss, Fort Drum, Hunter AAF, Fort Riley, Fort Polk, Fort Irwin, Fort Huachuca, Fort Benning, Ellijay | — (sectional only) |

**So, for most training areas, this plan gives a cleaner sectional, not a sharper one at web zooms 15–17.** No raster source finer
than the sectional exists there. What would be sharp at those zooms is vector airspace and airports (§11). Question 1 asks whether that
changes what the owner wants built first.

---

## 4. The design

### 4.1 One stacked tileset, one map layer

Each tile is put together at build time from the charts under it. The finest chart covering a pixel wins, from the tile level where
it is finer than the one below:

| Tier | Charts | Shown from (web zoom) | Stored up to (tile level, web zoom) |
|---|---|---|---|
| Sectional | 37 CONUS + Alaska + Hawaii (question 2) | 6 | 11 (web 12); Alaska 10 (web 11) |
| TAC | 32 TAC sheets in 30 zips (Puerto Rico's per question 2), the Grand Canyon General Aviation chart, the Honolulu inset, the Eastern Long Island helicopter chart | **12** | 12 (web 13) |
| Helicopter | 11 sheets at 1:125,000 | **13** | 13 (web 14) |
| Helicopter insets | 5 at 1:50,000–1:90,000 | **14** | 14 (web 15) |

**Why these zooms.** A finer chart starts at the first whole zoom where it is no longer shrunk on a 2× screen, that is where one of its
pixels fills at least one device pixel (a web zoom below its native one: TAC 11.3–11.8, helicopter 12.4–12.7, insets 12.9–13.8).
Shrunk further, its small text would alias below legibility. A tier is stored to its native zoom rounded up:
- that keeps every pixel of the source;
- a stored level beyond it would be the source enlarged, at four times the tiles, which the client enlarges as well, for free.
The brief for this work proposed *native plus one*; we recommend against it on these grounds (question 4).

**Left out:**
- the US Gulf Coast helicopter chart and the two Caribbean charts: 1:1,000,000, coarser than the sectional;
- the Flyway Planning charts (question 3);
- the Grand Canyon Air Tour Operators chart, which covers the same ground as the General Aviation one;
- every file with no georeferencing.

**One layer, composited on the server, rather than three on the client.** The alternative is one map layer per tier, each with its
own native zoom. It was weighed and not taken:

| | One composited tileset (recommended) | Three layers on the client |
|---|---|---|
| Clients | one URL template, as today (web, Android, brief figures, LZ card capture) | three sources each; the LZ card and brief figures composite them |
| Requests | one per tile | up to three; TAC and helicopter tiles asked for across all of CONUS, answered 404 outside their small coverage, unless every client also filters by coverage |
| Seams | composited per pixel at build | composited by the client, the same result |
| Server | must answer a tile above the stored level in a sectional-only area (below) | static files only |

**Overzoom on the server.** The single layer's top level is 14. In a sectional-only area, the clients will ask for levels 12–14,
which are not stored. The tile route answers such a tile from its nearest stored ancestor: cropped, enlarged with Lanczos, encoded and
cached (an in-process cache of a few hundred tiles, and Cloudflare's).

Storing those tiles instead would cost about 180,000 tiles more for level 12 across CONUS, and four times that again for level 13: about 14 GB
and 56 GB a cycle. Enlarging one tile costs tens of milliseconds of Pillow time, which mostly releases the GIL. Cloudflare answers all but
the first request for each tile.

**Overzoom on the clients.** Past the stored levels the client enlarges as it does today: Leaflet through the browser's smoothing,
MapLibre with `raster-resampling: linear`. Neither may be set to `pixelated` or `nearest`.

### 4.2 Clipping each chart to its neatline

The FAA publishes no neatlines (§2), so EZ/PZ keeps its own: one GeoJSON polygon per chart, in degrees on NAD83, committed with the
pipeline. Most outlines are simple: sectionals end along meridians and parallels, and a TAC or helicopter sheet is a few straight
edges. Insets that sit inside a chart's body are cut out as holes.

**Making them.** A tool reads a chart, finds its neatline (the black frame round the chart's body), and proposes the polygon. A person
checks each one over an overview image before it is committed. It is about 70 charts, done once.

**Each cycle**, the pipeline compares each chart with the one its polygon was made from: dimensions, transform, and the neatline's
pixels along the polygon.
- A chart that has changed beyond a tolerance is **not built in the new form**. The pipeline keeps the last edition's tiles for that
  chart's ground, and says so in the Data tab, until its polygon is made again.
- A chart may never be built unclipped: its legend would cover its neighbours.

Community projects have made clip shapes of their own. We have not checked their licences, so none is copied.

### 4.3 The archive: MBTiles, one file per cycle

| | z/x/y folder | **MBTiles (recommended)** | PMTiles |
|---|---|---|---|
| Files on disk | ~90,000 per cycle | 1 | 1 |
| Switch over, keep the last one, delete | many files, not atomic | rename one file | rename one file |
| Server code | none (static) | a short route over stdlib `sqlite3` | a reader library (**a new dependency**) |
| Clients | unchanged | unchanged (the server answers z/x/y) | unchanged if the server reads it; a new dependency in every client if they read it themselves |
| Later offline packs on Android (`core-mappacks`) | — | Android reads SQLite natively | a reader on Android |
| Overzoom from an ancestor | needs code anyway | same query | same |

MBTiles needs no new dependency anywhere: Python's `sqlite3` writes and reads it. It is one file to switch, keep or delete, and it is
the format Android's offline map packs would want. Two things to watch:
- MBTiles numbers rows from the south (TMS). The route flips `y`, and a test pins a known tile.
- Open it read-only and immutable (`file:…?mode=ro&immutable=1`), one connection per thread.

### 4.4 Tile format

Each tile is 512 pixels, RGB, with alpha only where the edge of the coverage needs it. Measured on 36-tile blocks: dense Atlanta and
sparse Ellijay at level 11, Atlanta TAC at 12, Washington helicopter at 13.

| Encoding | Average tile | CONUS, about 85,000 tiles |
|---|---|---|
| PNG, RGB | 390 – 450 KB | ~36 GB |
| WebP lossless | 330 – 390 KB | ~31 GB |
| PNG, 256 colours | 160 – 175 KB | ~14 GB |
| **WebP, quality 90** | **67 – 82 KB** | **~7 GB** |
| WebP, quality 80 | 40 – 57 KB | ~4.5 GB |

The charts are scanned images, with screened tints and dither, so lossless encodings stay large.

**Recommendation: WebP at quality 90.**
- Enlarged twice, it could not be told from lossless in our comparisons, and it is a fifth the size. Tiles download faster on a phone, and Android's tile cache holds five times as many.
- Every browser EZ/PZ supports reads WebP, and so do Android and Pillow.
- It is still a lossy encoding of a source that was itself resampled. Question 5 asks the owner.

### 4.5 Edition, expiry, and "no finer chart"

**The cycle in every URL:** `/charts/vfr/<cycle>/{z}/{x}/{y}.webp`, where `<cycle>` is the effective date, `2026-10-29`. Because each
cycle's tiles never change, they are served `public, max-age=31536000, immutable`.

**`/api/config` names the live cycle.** It is public and cached for 60 seconds, and the web and Android already fetch it. A new optional
field (an additive change: `contracts/openapi.yaml`, and an API minor version):

```
charts: { vfr: { cycle, tiles, tileSize: 512, minZoom, maxZoom, effective, expires, coverage } }
```

**`coverage.json` for each cycle** lists every chart built: its name, tier, scale, edition dates, a simplified outline, and its stored
top level. The clients use it in two ways:
- **Attribution names the charts in view**, with their edition: *FAA Atlanta Sectional, 29 Oct – 23 Dec 2026*. Past the end date it
  turns red: *Expired 23 Dec 2026 — not current*. That happens only if a new cycle was not built.
- **Past a chart's native zoom, the map says so**: a quiet line by the attribution, *Sectional enlarged 16× — no finer chart here*,
  from the coverage at the map's middle (the crosshair). It never blocks the map.

**When a cycle goes live.** A new cycle is built as soon as the FAA posts it, three weeks early, and served under its own URL. It goes
live in `/api/config` at **0901 UTC on its effective date**, the FAA's effective time for charts (to confirm in C5).

**What is kept.** The cycle before stays, so a client with a config up to 60 seconds old still draws. Older cycles are deleted.

### 4.6 Fallback

If the server has no live cycle, `/api/config` says so. Android, with no config remembered, draws today's ArcGIS service, so the VFR map
is never blank (question 8).

---

## 5. The pipeline

### 5.1 Where it runs

The pipeline needs GDAL, which the `backend/lidar` image already has (PDAL's conda environment). The API image does not have it.
A build is about an hour of CPU (§6). The API has one gunicorn worker, so the build must not run there. The LiDAR build service has a
one-at-a-time queue, so the build must not run there either: it would hold up builds someone is watching.

**Recommended: a second Coolify app from the same image** (base dir `/backend/lidar`), started as `python -m charts.worker`. It has no
public domain, and it mounts `/data/charts` read-write. The API mounts `/data/charts` read-only. The worker:
1. Looks once a day for a new edition: the next date on the 56-day rhythm, asked for with a `HEAD` of one of its zips. It cross-checks
   that date with the FAA page's links.
2. Builds a new edition at once, and a requested rebuild (the Data tab's button, §5.4) when asked.
3. Writes its state where the API reads it (`/data/charts/state.json`), so nothing calls the worker.

The chart code would live under `backend/lidar/charts/`, inside that image's build context, as an odd but honest neighbour. A
`backend/charts` image of its own is the alternative (question 6).

### 5.2 Steps of one build

Each step is resumable. Everything is staged under `/data/charts/.staging-<cycle>/` and moved into place only when complete, as LiDAR
builds are.

1. **Download** each wanted zip, one at a time, resumed with `Range`, and checked against `Content-Length`. Unzip it.
2. **Check** each chart (§4.2):
   - georeferenced, with a palette;
   - edition dates as expected;
   - layout unchanged against its polygon.
   A chart that fails keeps the last cycle's tiles for its ground, and the Data tab names it.
3. **Prepare** each chart: `gdal_translate -expand rgb` into a tiled, deflated GeoTIFF in its own projection.
   - Expanding comes before any resampling: palette indices cannot be averaged.
   - The neatline polygon becomes an alpha mask (`gdalwarp -cutline`, `-dstalpha`).
4. **Tile** in Python over GDAL, in parallel worker processes, tile by tile from the top level down to 11:
   - each tile warps the charts under it to Web Mercator with Lanczos;
   - lays them by tier, sectional first;
   - encodes WebP at quality 90;
   - writes the tile into the staging MBTiles.
   Only the tiles §4.1 stores are written. A tile with nothing in it is not written.
   A purpose-written tiler, rather than `gdal2tiles` or GDAL's MBTiles driver: both tile one raster, and stacking three tiers within one
   tile, only where each exists, is what this needs.
5. **Lower levels** (10 down to 5) are made from the level below: four tiles shrunk into one, with Lanczos.
6. **Validate**:
   - tile counts against `coverage.json`;
   - a known tile at each tier decoded and compared, within a tolerance, with a reference rendered from the source;
   - every chart in the coverage present.
7. **Publish**:
   - rename into `/data/charts/<cycle>/vfr.mbtiles` with its `coverage.json`;
   - write `state.json`;
   - delete the cycle before the last;
   - delete the downloads and prepared files.

### 5.3 The disk

The disk rules of the Data tab (PR #143, `data_admin/volumes.py`) apply as they are:
- **The mount check**: never write to the container's own disk.
- **The reserve**: `max(20 GB, 10 %)` free, checked before each file and as it grows.
- **Room for the streamed LiDAR cache** to reach its cap on the same disk.

The worker cannot import the API's module, since it runs in the other image. Its check is a copy, held to the same tests.
`/data/charts` joins the Data tab's volume panel as `CHART_TILES_DIR`.

Estimates, from §4.4's measurements and the chart sizes in §2:

| | CONUS | + Alaska (stored to level 10) and Hawaii |
|---|---|---|
| One cycle's tiles | ~7 GB (85,000 tiles) | +1.5 GB (19,000 tiles) |
| Downloads + unzipped charts, during a build | ~7 GB | +2 GB |
| Prepared RGB charts, during a build (estimate: 2–3× the palette files) | ~8 GB | +4 GB |
| **Peak during a build** (live + previous + new + work) | **~35 GB** | **~45 GB** |
| **Steady** (live + previous) | **~14 GB** | **~17 GB** |

Plus the reserve. A build that would break a rule stops, says why in the Data tab, and leaves the live cycle as it was.

### 5.4 The Data tab

A **Charts** section on `/admin/data`:
- the live cycle, the next (built, waiting for its date), and the one kept;
- each chart's edition, and any chart held back with its reason;
- the space each cycle takes;
- **Build now**, and **Rebuild this cycle**: the button writes a request file the worker picks up;
- a map of the coverage by tier, on the tab's existing MapLibre map.

Writing follows `DATA_ADMIN=write`. Staging's API reads the same `/data/charts` and offers no buttons.

---

## 6. Time to build (estimate)

On the owner's Mac, from data held in memory, a tile took **0.23–0.27 s**: warp with Lanczos, plus the six encodings §4.4 compared. One
WebP encode is a fraction of that, so expect about **0.1 s a tile on one core**:
- **about 2.5 core-hours** for CONUS's 85,000 tiles, or **about 40 minutes on four processes**;
- plus the download, about 4 GB from the FAA. Our test pulled 200 MB in seconds.
The VM's real figure is the first thing C1 measures.

---

## 7. Serving

**The route.** `GET /charts/vfr/<cycle>/<z>/<x>/<y>.webp` on the API, outside `/api`, like `/r/<token>`.
- **No sign-in.** The tiles are public FAA data, so they need no JWT, and the affiliation gate must not apply.
- `<cycle>` must be an ISO date that is live or kept, and `z`, `x`, `y` must be in range. Nothing from the URL reaches a path.
- Headers:
  - a tile: `Cache-Control: public, max-age=31536000, immutable` and `Access-Control-Allow-Origin: *`. The LZ card's canvas capture
    draws tiles with `crossOrigin="anonymous"`;
  - a tile outside the coverage: 404, `public, max-age=86400`.
- Overzoom is §4.1's.

**Cloudflare.** It caches proxied responses by `Cache-Control`. `.webp` is among the extensions it caches by default.
- **Owner step:** add a Cache Rule for `/charts/*` on the API's hostname (cache everything, respect origin headers).
- Check that Cloudflare's terms for the plan in use allow image tiles at this volume. The users are few, but check.

**Load.** Tiles are small reads from SQLite. With Cloudflare in front, the API sees each tile about once a cycle.

---

## 8. The clients

**Web** (`mapStyles.js`):
- `vfr-sectional` keeps its id and label. A diagram saves the id, so saved plans need nothing.
- Its URL comes from `/api/config`.
- `tileSize: 512` and `zoomOffset: -1`, as the Mapbox styles have; native zooms 6–15 (web zoom); `maxZoom` 20.
- The attribution is a small component that reads `coverage.json` (§4.5).
- The preview tile moves to one the archive has.
- The survey editor's map already leaves VFR out (`SurveyMap.jsx`).

**Android** (`MapStyles.kt`):
- The VFR style's `tiles`, `tileSize` 512, `minzoom` and `maxzoom` come from the config, which is remembered as the Mapbox token is.
- With none remembered, the ArcGIS service is drawn (§4.6).
- The attribution and the overzoom line are Compose, from `coverage.json`, kept with the config.
- MapLibre's own tile cache keeps tiles for offline use.

**The brief's figures** (`backend/brief/figures.py`) read the live MBTiles directly, with no HTTP, at 512 px up to level 14. Strip
charts at 1:100,000 and 1:250,000 will then show a TAC or helicopter chart where one exists, at its own symbol size.

**The 3D view** drapes Mapbox imagery, not the VFR map, so it does not change.

---

## 9. Steps

| Step | What | Done when |
|---|---|---|
| **C0** | The neatline tool, and the ~70 polygons, checked. A local prototype of §5.2 over three charts. | The Atlanta sectional and TAC and the Washington helicopter chart tile seamlessly, and the measurements of §4.4 and §6 hold. |
| **C1** | The chart worker (§5.1–5.3) and the MBTiles writer, in the `backend/lidar` image. | A whole CONUS cycle built on the VM, its time and size measured, the disk rules tested. |
| **C2** | The tile route with overzoom, `/api/config`'s `charts`, the Data tab's Charts section. | `contracts/openapi.yaml` and the API version moved; the route's tests (TMS flip, 404s, headers, a cycle that is not live). |
| **C3** | The web: the style from config, the attribution, the "no finer chart" line; the brief's figures. | The LZ card exports with VFR; an expired cycle shows red. |
| **C4** | Android: the style from config, the fallback, the attribution and the line. | Held to the same coverage fixtures as the web. |
| **C5** | Automatic cycles: the daily check, the switch at 0901 UTC, the cycle kept. | A dry run across a real cycle change (12-24-2026). |
| later | Vector airspace and airports (§11). | — |

AGENTS.md is updated in the same PRs: the §4 *Map layers* row, §8 (the FAA is a source), §9 (the chart worker), §10 (`CHART_TILES_DIR`),
and §17 *The map*.

---

## 10. Questions for the owner

1. **Is a cleaner sectional worth it where it is the only chart?** §3.2: most Army aviation posts have only the sectional. There, this
   gives smooth edges, no JPEG and the edition shown, but no more detail at web zoom 15–17. *Recommendation: yes. Build C0–C3 for CONUS,
   look at it, then decide on Android and on vectors (§11), which are what would be sharp at LZ zooms everywhere.*
2. **Alaska, Hawaii, Puerto Rico?** Army aviation flies at Fort Wainwright and Wheeler AAF. *Recommendation: CONUS, Alaska and Hawaii
   (about 1.5 GB more a cycle). Puerto Rico's TAC only if wanted.*
3. **The Flyway Planning charts** (21 of them, beside 20 TACs, at 1:250,000): leave them out of the base map? They are planning charts for flying
   under Class B, with their own symbols. *Recommendation: leave them out.*
4. **Store each tier to its native zoom rounded up (§4.1), not native plus one?** Plus one stores the chart enlarged, at four times the
   tiles, for nothing the client cannot do. *Recommendation: rounded up.*
5. **WebP at quality 90 (~7 GB a cycle), or lossless WebP (~31 GB)?** We could not tell them apart enlarged twice. *Recommendation: quality 90.*
6. **Run the build as a second Coolify app from the `backend/lidar` image** (§5.1), or from an image of its own (`backend/charts`, a
   GDAL base image, a new image to keep)? *Recommendation: the LiDAR image.*
7. **Build and switch with no one pressing anything** (built when posted, live at 0901 UTC on the effective date), with the Data tab to
   watch and rebuild? Or should an admin approve each cycle before it goes live? *Recommendation: automatic.*
8. **When the live cycle has expired** (a build failed for weeks): keep drawing it with a red *Expired* notice, or stop drawing VFR?
   And keep the ArcGIS service as Android's fallback? *Recommendation: keep drawing, red notice; keep the fallback.*
9. **Cloudflare:** may `/charts/*` be cached on the API's hostname, and is the plan's traffic allowance fine for image tiles?

---

## 11. Later: vectors

FAA NASR (the 28-day subscription) and the FAA's ArcGIS open data hold airspace, airports, navaids and obstacles as data:
- drawn as vectors, they stay sharp at any zoom, which no raster chart can be at web zoom 15–17;
- they can be shown over satellite or topo as well as over the chart.

This is a different project: it needs a vector style on the web and Android, its own cycle, and design for clutter. It is noted here as
the path to sharpness at LZ zooms, not planned.

---

## 12. What cannot be verified in an agent session

- How the tiles look on a real phone in sunlight. The comparisons here were looked at on screenshots.
- The VM's build time and Cloudflare's caching through the tunnel.
- That the `backend/lidar` image's GDAL has the WebP and MBTiles drivers. The GDAL in `rasterio` used here (3.10.3) has both. C1 checks
  the image.
