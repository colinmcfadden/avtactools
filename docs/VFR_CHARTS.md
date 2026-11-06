# VFR charts — plan

Status: **decided, not built** (2026-11-05). The owner finds the VFR base map too soft next to SkyVector and ForeFlight. This file
proposes replacing it with chart tiles EZ/PZ builds itself from the FAA's free digital charts: the sectional everywhere, then the
Terminal Area Chart (TAC) and the Helicopter Route Chart stacked over it where they exist, on the web and Android.

**2026-11-05, later:** the owner asked for the charts to be part of the admin's Data tab (PR #143): *"Let's do the self-hosted faa chart
tiles, and incorporate them in to the data tab in the admin we just built."* Each cycle is therefore built, switched to and rolled
back from that tab, with its jobs, disk rules and map (§5).

**The owner answered §10's questions the same day**, and the plan follows the answers: CONUS only, every step built in one go
(Android included), and each cycle built and switched **automatically** on production (§5.7). Every number here was measured on 2026-11-05
against the FAA's 10-29-2026 edition and today's ArcGIS service, unless it says it is an estimate. Where this plan and the code disagree
once it is built, the code wins (AGENTS.md §16).

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
than the sectional exists there. What would be sharp at those zooms is vector airspace and airports (§11). The owner chose to build it
anyway (§10, 1). Wheeler AAF and Fort Wainwright are outside CONUS, which is all that is built (§10, 2): they keep today's ArcGIS map.

---

## 4. The design

### 4.1 One stacked tileset, one map layer

Each tile is put together at build time from the charts under it. The finest chart covering a pixel wins, from the tile level where
it is finer than the one below:

| Tier | Charts | Shown from (web zoom) | Stored up to (tile level, web zoom) |
|---|---|---|---|
| Sectional | the 37 CONUS sectionals | 6 | 11 (web 12) |
| TAC | the CONUS TAC sheets (Alaska's, Honolulu's and Puerto Rico's left out), the Grand Canyon General Aviation chart, the Eastern Long Island helicopter chart | **12** | 12 (web 13) |
| Helicopter | 11 sheets at 1:125,000 | **13** | 13 (web 14) |
| Helicopter insets | 5 at 1:50,000–1:90,000 | **14** | 14 (web 15) |

**Why these zooms.** A finer chart starts at the first whole zoom where it is no longer shrunk on a 2× screen, that is where one of its
pixels fills at least one device pixel (a web zoom below its native one: TAC 11.3–11.8, helicopter 12.4–12.7, insets 12.9–13.8).
Shrunk further, its small text would alias below legibility. A tier is stored to its native zoom rounded up:
- that keeps every pixel of the source;
- a stored level beyond it would be the source enlarged, at four times the tiles, which the client enlarges as well, for free.
The brief for this work proposed *native plus one*; the owner chose rounded up (§10, 4).

**Left out:**
- the US Gulf Coast helicopter chart and the two Caribbean charts: 1:1,000,000, coarser than the sectional;
- everything outside CONUS: the Alaska and Hawaii sectionals, their TACs and insets, Puerto Rico, the Pacific insets (§10, 2);
- the Flyway Planning charts (§10, 3);
- the Grand Canyon Air Tour Operators chart, which covers the same ground as the General Aviation one;
- every file with no georeferencing.

**One layer, composited on the server, rather than three on the client.** The alternative is one map layer per tier, each with its
own native zoom. It was weighed and not taken:

| | One composited tileset (recommended) | Three layers on the client |
|---|---|---|
| Clients | one URL template, as today (web, Android, LZ card capture); the brief's figures read the same tiles on the server (§8) | three sources each; the LZ card and brief figures composite them |
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
checks each one over an overview image before it is committed. It is about 84 charts (37 sectionals, about 29 TACs, Grand Canyon,
Eastern Long Island, 11 helicopter sheets and 5 insets), plus the insets' holes, done once. There are more polygons than zips (§5.2)
because some zips hold more than one chart.

**Each cycle**, the pipeline compares each chart with the one its polygon was made from: dimensions, transform, and the neatline's
pixels along the polygon.
- A chart that has changed beyond a tolerance is **not built in the new form**, and the Data tab says so until its polygon is made
  again. Tiles are composites of several charts, so a chart cannot be held back by keeping old tiles. What is kept is the chart
  itself: each build keeps the FAA's zip of every chart it used under `builds/<build>/sources/` (the palette files, about 3.5 GB a
  CONUS build; a prepared RGB copy would be about three times that). A chart held back is prepared again from the zip in the newest
  build in `state.json` whose `sources/` holds it, with the polygon it was checked against. It is then composited into the new build
  with its old edition dates in `coverage.json`.
- **When there is no older copy** (a first build, or a chart never built before), what happens depends on the tier:
  - a TAC or helicopter chart that fails is left out, and the sectional shows on its ground;
  - a sectional has nothing under it, and a hole would show nothing at all. So **a build stops if a CONUS sectional fails with no older
    copy to stand in for it** (§5.3, steps 3 and 7). The server goes on serving what it served before: the live build, or ArcGIS before the first.
- A chart may never be built unclipped: its legend would cover its neighbours.

Community projects have made clip shapes of their own. We have not checked their licences, so none is copied.

### 4.3 The archive: MBTiles, one file per build

| | z/x/y folder | **MBTiles (recommended)** | PMTiles |
|---|---|---|---|
| Files on disk | ~90,000 per cycle | 1 | 1 |
| Switch over, keep the last one, roll back | many files, not atomic | one file per build; `state.json` names which is live (§5.5) | the same |
| Server code | none (static) | a short route over stdlib `sqlite3` | a reader library (**a new dependency**) |
| Clients | unchanged | unchanged (the server answers z/x/y) | unchanged if the server reads it; a new dependency in every client if they read it themselves |
| Later offline packs on Android (`core-mappacks`) | — | Android reads SQLite natively | a reader on Android |
| Overzoom from an ancestor | needs code anyway | same query | same |

MBTiles needs no new dependency anywhere: Python's `sqlite3` writes and reads it. It is one file per build to keep or delete, and it is
the format Android's offline map packs would want. Two things to watch:
- MBTiles numbers rows from the south (TMS). The route flips `y`, and a test pins a known tile.
- Open it read-only and immutable (`file:…?mode=ro&immutable=1`). That is safe only because a build is never rewritten under its name
  (§4.5). How the connections are held and let go of is §7.
- One process writes a build's file: the build's tiler workers hand their tiles to the build's main process, which writes them in
  batches. Validation (§5.3, step 7) opens the file as the tile route does, read-only.

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
- It is still a lossy encoding of a source that was itself resampled. The owner chose it (§10, 5).

### 4.5 Edition, expiry, and "no finer chart"

**A build, not a cycle, in every URL:** `/charts/vfr/<build>/{z}/{x}/{y}.webp`, where `<build>` is the cycle's effective date and a
build number, `2026-10-29.1`. A build's tiles never change after it is made, so they are served `public, max-age=31536000, immutable`.
Building a cycle again (a chart fixed, a neatline remade) makes `2026-10-29.2` under a new URL. Reusing the cycle's URL would leave
browsers, Android's tile cache and Cloudflare holding the old tiles for a year, and a server that had the old file open `immutable`
would not notice it change (§4.3).

**A build number is never used twice.** It comes from a counter that only grows: `state.json` keeps the last number given for each
cycle (`numbers`, §5.5), taken under the state's lock when a build job is created. It is never worked out from the folders on disk:
a build deleted and then made again would otherwise get the deleted one's number, and its URL would answer with the deleted build's
tiles from every cache that holds them. A test deletes a build, makes the cycle again and checks the number moved on.

**`/api/config` names the live build.** It is public, and the server caches it for 60 seconds. A new optional field:

```
charts: { vfr: { build, cycle, tiles, tileSize: 512, minZoom, maxZoom, effective, expires, coverage, bounds, fallback } }
```

`tiles` is the URL template and `coverage` the URL of the build's `coverage.json`, both relative to the API's root, as the
`/charts/vfr/<build>/…` paths are. `bounds` is the build's extent, `[west, south, east, north]` in degrees, and `fallback` the
rectangles where the build has no charts and the clients draw ArcGIS instead (§4.6).

**`effective` and `expires` are instants in UTC, not dates.** A cycle takes effect at 0901Z on its date. It expires at the next
cycle's effective instant: 0901Z on the day after its printed end date, which is the same instant as a scheduled `next.at` (§5.5).
`state.json`, `coverage.json` (per chart, so a chart held back keeps its own edition's) and the config all use that instant. So does
everything judged from it: the red *Expired* notice, the tab's days left and red, and the expiry emails (§5.7). The printed end
date is what people are shown. Judging by it alone would turn the correct chart red every cycle, for the hours between midnight and
0901Z, before the next one is live.

This is an additive change to a contract the apps hold strictly:
- `Config` in `contracts/openapi.yaml` is `additionalProperties: false`, so the field goes into the spec first, and the API's minor
  version moves (`contracts/scripts/api_version.py bump`).
- The recorded answer in `contracts/fixtures/network/responses.json` gains the field, and Android's DTO with it: `DtoFixtureTest`
  decodes recordings with unknown fields forbidden.
- `build_config` takes only the environment today (`app_config.py`). The chart state (§5.5) is passed in.

**How a switch reaches the clients.** The server's answer changes within 60 seconds of a switch, with no restart. But the clients do
not ask for the config every minute: the web reads it at load (and again after 15 minutes hidden), Android at launch and when it
comes to the front. So:
- **The clients ask again while the VFR map is shown**: every 5 minutes, at the live build's `expires`, and after a chart tile fails.
  A new build in the answer changes the layer's URL; the same build changes nothing. This is part of C3 and C4.
- **A listed build answers every tile a client asks for** (§7). Ground with nothing to draw gets a shared transparent tile, not a 404.
  So a chart tile that fails means the build is gone or the network failed. A client cannot tell those apart: Leaflet's `tileerror`
  sees no status, and MapLibre's sees little more. So any failure of a chart-layer tile makes the client ask, **at most once a minute**,
  and past its HTTP cache (`cache: 'no-store'` on the web, a `Cache-Control: no-cache` request on Android). Otherwise the config's
  60-second `max-age` would hand back the old build. The ArcGIS fallback layers (§4.6) never trigger it.
- **A build a client still names keeps answering** until it is deleted, and deletion waits (§5.5): the live build, the previous one
  and a scheduled next one are never deleted, and anything older goes only a day after it stopped being one of them.
- **A build that is gone answers 404 with `no-store`** (§7), so no cache keeps the refusal, and the next tile error makes the
  client ask for the config again.

**`coverage.json` for each build**, served at `GET /charts/vfr/<build>/coverage.json` (public, immutable, CORS `*`, §7). It lists every
chart in the build: its name, tier, scale, edition dates, a simplified outline, its stored top level, and whether it is this cycle's or
was kept from the last (§4.2). Its schema goes into `contracts/openapi.yaml` with the tile route (C2), and a recorded `coverage.json`
from C0's prototype goes into `contracts/fixtures/charts/`, which the web (C3) and Android (C4) are held to. The clients use it in two
ways:
- **Attribution names the charts in view**, with their edition: *FAA Atlanta Sectional, 29 Oct – 23 Dec 2026*. From its expiry
  instant (0901Z the day after its end date) it turns red: *Expired 23 Dec 2026 — not current*.
- **Past a chart's native zoom, the map says so**: a quiet line by the attribution, *Sectional enlarged 16× — no finer chart here*,
  from the coverage at the map's middle (the crosshair). It never blocks the map.

The Data tab draws the same file (§5.6).

### 4.6 Fallback

If the server has no live build, `/api/config` has no `charts`. The web and Android then draw today's ArcGIS service, so the VFR map is
never blank: on a server that holds no charts (the Fly backend), before the first build, and on Android with no config remembered
(§10, 8).

**Outside CONUS**, only CONUS is built (§10, 2). There the clients draw the ArcGIS service, and only there. So the VFR map keeps what it
shows today in Alaska, Hawaii and the Pacific islands, and no ArcGIS tile is asked for over CONUS:
- The chart layer is drawn within the config's `bounds` (Leaflet's `bounds` option, MapLibre's source `bounds`), so no chart tile
  is asked for outside it.
- One ArcGIS layer per rectangle of the config's `fallback`, each within its own bounds, under the chart layer. The server takes the
  rectangles from a fixed table of the regions ArcGIS serves and the build does not. Each rectangle is measured from the service's
  tiles in C2, which pins the table in a test:
  - Alaska, split at the antimeridian, so two: the Western Aleutians reach about 172°E;
  - Hawaii's main islands;
  - the Mariana Islands (Guam, Saipan);
  - American Samoa.

  ArcGIS answered 200 over Guam, Saipan, Pago Pago, Adak and Shemya on 2026-11-05. A region added to the build later leaves the table
  by itself.
- **Puerto Rico and the Virgin Islands stay blank, as they are today.** ArcGIS answered 404 there at every level tried on 2026-11-05,
  and the Puerto Rico TAC is left out (§4.1).
- Where neither covers (the sea, Canada's interior), the VFR map is empty, as ArcGIS's is today.
- The attribution names *FAA, via ArcGIS* for the ArcGIS layers and gives no edition, as today.

**`CHART_TILES_DIR`** names the chart folder (`/data/charts` on the VM), and every path in this plan is under it. It has no default:
unset, the server holds no charts. `/api/config` then sends no `charts`, the tile route answers 404, and the Data tab's Charts card
says the setting is missing and offers nothing.

---

## 5. The Data tab's Charts section

The owner asked for the charts to be part of the admin's Data tab (PR #143, `/admin/data`; *"Let's do the self-hosted faa chart tiles,
and incorporate them in to the data tab in the admin we just built."*). So the charts are a section of that tab, built from its pieces:
- its map;
- its background jobs (`data_admin/jobs.py`, the `data_job` table);
- its disk rules (`data_admin/volumes.py`);
- its `DATA_ADMIN` switch.

They are not a worker of their own beside it. The first draft of this plan had a second Coolify app from the LiDAR image, a daily check of
its own, a request file and a copy of the disk check. All of that goes.

What the tab does for charts:
- **Shows** which charts and builds the server holds, each chart type's coverage on the map, and the current and next 56-day cycle with
  when each expires (§5.6).
- **Builds** a cycle: downloads it and makes its tiles, as a background job with progress, under the tab's space and mount guards
  (§5.2–5.4).
- **Switches** the served tiles to a build, keeps the one before, and **rolls back** to it (§5.5).
- **Does both by itself** on production, each cycle, once the FAA posts it (§5.7). The tab is where that is watched and overridden.

What the Data tab has today, and what charts change in it, from reading PR #143's code:

| Today | For charts |
|---|---|
| One runner thread, whose `run` is DEM-only. `DataJob.kind` exists but nothing reads it, and `resume` would run any queued row as a DEM download. | The runner dispatches on `kind`; `resume` too. |
| `product` is `String(10)`, not null, `13` or `1m`. | A chart build's `product` is `vfr`. New nullable columns, added through `schema_sync` (`sync_table_columns(db, DataJob)`, which `app.py` does not call for this table yet): `build` (`String(20)`), `stage` (`String(20)`), `work_done` / `work_total` (tiles, as `bytes_done` / `bytes_total` count bytes), and `token` (a random token the build process was started with, §5.1). |
| One job at a time, of any kind (the 409 under `_creating`). | Kept: a chart build and a DEM download wait for each other. `check_room` counts only the bytes one write adds, not another job's writes running beside it, so two at once could pass the reserve check together and break it. |
| A job resumes when the tab or its job list is next opened, never at start-up. | Every job left queued or running resumes, DEM downloads included, at the chart look 5 minutes after start (§5.7) or when an admin opens the tab, whichever comes first. A job left by a redeploy then never holds the tab, and the automatic cycle with it, until someone happens to open the page. |
| Downloads only from USGS's two hosts, no redirects. `_download` needs every file's size before the job starts, checks no host itself, unzips nothing, and its words and User-Agent name USGS. | `_download` takes the allow-list, the source's name for its words, and the User-Agent as arguments, and checks the host itself. Chart jobs allow `aeronav.faa.gov` alone, `https`, no redirects; it answers the zips directly with `Accept-Ranges: bytes` (checked 2026-11-05). The zips' sizes are asked by `HEAD` in the job's first stage, not in the request that creates it (§5.2), and unzipping is a step of its own (§5.3). |
| The job card's progress is bytes; the page stops polling after one failed poll. | A stage line and the tile count. The poll tries again after a failure, with a pause, because a build runs for an hour and a blip or a sign-in lapse would otherwise freeze the card. |
| The volumes panel lists five settings (`volumes.places()`). | `("VFR charts", "CHART_TILES_DIR", None)` is added, so the panel shows its space, mount and reserve. |
| The map's layers are GeoJSON only, each name one fetch, one source; `maxBounds` stops at 180°W. | `loadLayer`, `layers.data_layer` and the popups gain `vfr-*` branches, and the layer route takes an optional `build`, checked against `state.json`. The chart tiles are a raster source of their own (§5.6). `maxBounds` stays as it is: it holds every CONUS chart. |

**Owner steps.** C1 is tried on production, after its release: only production's mount is writable, and only production has
`DATA_ADMIN=write`. Before that:
- mount `/data/charts` into the API container, read-write on production and **read-only on staging**;
- set `CHART_TILES_DIR=/data/charts` on both;
- confirm the container's CPU and memory limits in Coolify (§5.1).

Before C2 is released:
- check that the Cloudflare plan's allowance suits image tiles before the Cache Rule goes on (§7, §10, 9);
- know that **with C2 on production, the automation builds by itself**. Its first look comes 5 minutes after the deploy (§5.7). It
  leaves alone a cycle that already has a build, whoever made it. A build made by hand, such as C1's trial build, is therefore never
  replaced, but nothing serves it until an admin switches to it from C2's tab. To make the first cycle's build by hand, set
  `CHART_AUTO=off` before deploying C2. Build and look at it with the tile preview, **switch it live**, and only then remove
  `CHART_AUTO=off`.

### 5.1 Where a build runs: a process the job starts

A build is CPU work: warping and WebP encoding for about 85,000 tiles (§6). The API is one gunicorn process (AGENTS.md §9). Its
eight threads share one Python interpreter with SAM and the terrain tiles, and that is why an analysis once took 31.7 s instead of 9–12
behind terrain work. A build on a thread in that process would slow every analysis for an hour.

**The Data tab's job starts a separate process in the same container and watches it** (§10, 6):
- **A fresh interpreter, not a fork.** The job runs `python -m charts.build <job id> <token>`. Forking would copy SAM's gigabyte.
  - It must not import `app.py`, which loads SAM at import. It opens the database through a small factory of its own (a `db_app.py`:
    a bare Flask app with `db.init_app`, `database_url.database_uri` and the API's engine options), and imports only `models` and the
    chart code. A test starts it and checks `torch` and `ultralytics` are not in `sys.modules`.
- **Two to three worker processes** inside it (`CHART_BUILD_PROCESSES`, default 2), at a lower priority (`nice` 10). Only the build's
  main process writes the MBTiles and the job's row.
- **In its own process group**, so a cancel or a failure ends all of it. This is `lidar/worker.py`'s `cancellable_runner` pattern.
- **Who writes what to the job's row.** Two writers on one row would overwrite each other: the tab's `_Progress.save` and `_finish`
  write the whole row as the thread last read it, which would put back progress the child had moved on. So:
  - **The child owns its progress**: `files`, `stage`, `work_done`, `work_total`, `bytes_done`, `bytes_total`. It writes them, and
    its own end state (`done`, `failed`, `cancelled`, with `error` and `finished_at`), on a row it has just read.
  - **The parent writes nothing while the child lives.** It reads `cancel_requested` by refreshing the row. If the child dies
    without an end state (killed, out of memory), the parent reads the row afresh and writes `failed` with only `state`, `error` and
    `finished_at` changed. It does so only for its own child, which it watched die, and only once the staging lock is free.
- **One build process, ever, judged by its lock, never by a pid.** A redeploy runs the old and new containers together for a moment,
  each with its own process numbers. A pid recorded in one means nothing in the other, and may name an unrelated process there. So:
  - The child takes an exclusive `flock` on `.staging-<build>/.lock` and holds it until it exits. Its workers inherit the
    descriptor, so the lock lasts until the whole group is gone. The volume is shared, so the lock holds across both containers.
  - A child that cannot take the lock exits at once **with a status of its own** ("already running") and writes nothing. Its parent
    takes that status as "busy, look again later". It writes no `failed`, deletes no staging, and the automation counts no try
    (§5.7).
  - The row records the `token` the child was started with. Every write the child makes to the row is conditional on the row still
    carrying that token (`UPDATE … WHERE token = …`).
  - **The child dies with its parent.** It checks every few seconds that its parent's pid is unchanged (`getppid`), and on Linux asks
    for `PR_SET_PDEATHSIG`. A child that ends that way **writes no end state**: its row stays `running` for the next `resume`. A
    redeploy during a build is therefore a restart, not a failure. The child reads `cancel_requested` itself as well, so a cancel
    still ends it if its parent stops watching.
  - **`resume` never adopts a child.** For a chart job left `running` it tries a non-blocking `flock` on the staging lock.
    - Held: the build still runs somewhere, perhaps in the old container of a redeploy. `resume` leaves the row as it is and looks
      again at the next poll or the next look.
    - Free: it lets the lock go, deletes any `.pause` left behind (below), and starts a new child under a new token. The new child
      carries on from its stage (§5.3).
  - A test kills the parent mid-build and runs `resume` twice: while the dying child still holds the lock, and after. It checks
    that the first run writes nothing and starts nothing, and that in the end there was one build process at a time, no `failed`,
    and one finished build.
- **Paused while crews wait, by asking, not by signal.** The pause is a file, `.staging-<build>/.pause`, which the parent keeps in
  step with its own state at every poll. The file is there, and touched, while an analysis, viewshed or export runs
  (`terrain_tiles._no_analysis`, which downloads already wait on), and gone otherwise. The child and its workers look for it between
  tiles and between download chunks. They wait while it is there and **less than 60 s old**. So a pause left behind by a parent that
  was killed holds nothing for more than a minute, and `resume` deletes any it finds before it starts a child. `SIGSTOP` would
  freeze the child wherever it was, holding a database transaction open (a row lock on Postgres, the whole file on local SQLite) or a
  half-written MBTiles batch. A C1 test checks that a `.pause` left by a killed parent does not hold the resumed build.
- **A cancel** sets `cancel_requested`. The child finds it within a tile or a chunk, deletes its staging and writes `cancelled`. A
  child that has not ended 30 s later has its group killed (`SIGKILL`), the parent deletes the staging and writes `cancelled` as above.
- **Nothing new to install, as far as can be checked here.** GDAL comes as the library inside `rasterio==1.4.4`'s wheel, which is
  already a requirement: warping with `rasterio.warp.reproject`, the neatline mask with `rasterio.features.rasterize`. WebP comes
  from Pillow. MBTiles is stdlib `sqlite3`. The API image has no GDAL command-line tools, and none are needed. That the Linux wheels have
  what the macOS ones do is C1's first check (§12).

**The cost.** The build shares the API container's CPU and memory. Its priority and the pause during analyses keep it behind the work
crews wait on, but terrain tiles computed in that hour are slower. Coolify's CPU and memory limits for the container are an owner step
to confirm.

**Not recommended:**
- **A thread in the API process**: the interpreter lock, above.
- **The LiDAR build service or its image.** It lacks rasterio and Pillow, and its one queue would hold up 3D builds someone is watching. It
  is due to retire once LiDAR streams from local data (the Data tab plan's D20 and D24). It has no database, so it would need a file
  channel back to the tab. It would also need a copy of the disk check.

### 5.2 A build is a Data tab job

`POST /admin/data/charts/builds`, guarded as the tab's other writes are:
- the CSRF check first, then `_writable()`, which needs `DATA_ADMIN=write`;
- refused with 409 while any job runs.

It takes a cycle date:
- **The current cycle or the next one.** The server works out each from the 56-day rhythm and checks it is posted with a `HEAD` of one
  of its zips (the answer the Charts card already holds, §5.5).
- **CONUS only** (§10, 2). The tiers are fixed; nothing about the charts comes from the page.
- The server makes the list of zips itself, never from a list the page sent, as the DEM job does.

It takes the build's number (§4.5), writes the job with its `files` (the CONUS zips, about 75, each with its own state, so a zip that fails
is named) and answers 202 at once; the page polls the job as it does a download. The zips' sizes are not asked here: 75 `HEAD`s in one
request could pass Cloudflare's 100 s. The job's first stage asks them, then checks the room for the whole build (§5.4). Its own
limits replace the DEM job's (100 files, 20 GB).

`Cancel` is the tab's: it sets `cancel_requested` (§5.1). A cancelled or failed build deletes its staging, as a failed download deletes
its `.part` files. Its number is not given again.

### 5.3 Steps of one build

Each step can resume. Everything is staged on the chart volume, under `/data/charts/.incoming/` and `/data/charts/.staging-<build>/`. The
tab's room checks therefore cover it, and a move into place is a rename on one file system.

0. **Ask the sizes** of the zips (`HEAD`), and check the room for the whole build (§5.4).
1. **Download** each zip, one at a time, into `.incoming/`:
   - resumed with `Range` from its `.part`;
   - checked against `Content-Length`.
   The tab's `_download` does this, with the chart host's allow-list (§5).
2. **Unzip** each zip into `.incoming/<chart>.unzipping/`, renamed to `.incoming/<chart>/` once whole, so a resume never takes half an
   unzip for a whole one. Only the members a chart needs (its `.tif`, `.tfw`, `.htm`), each name checked to stay inside the folder,
   and each bounded in size. A zip is kept until its chart is prepared.
3. **Check** each chart (§4.2):
   - georeferenced, with a palette;
   - the edition dates expected;
   - the layout unchanged against its polygon.
   A chart that fails is **held back**. Step 4 prepares it again from the zip in the newest build in `state.json` whose `sources/`
   holds it, with the polygon it was checked against (§4.2). If no build holds it, a TAC or helicopter chart is left out. **A
   sectional with no older copy stops the build here**, refused, before an hour of tiling: its ground would be a hole (§4.2). Either
   way the job names the chart with its reason, as a refused DEM file is.
4. **Prepare** each chart: palette expanded to RGB before any resampling (palette indices cannot be averaged), and its neatline polygon
   made an alpha mask. The result is a tiled, deflated GeoTIFF in the chart's own projection, in `.staging-<build>/work/`. The chart's
   zip moves to `.staging-<build>/sources/`, which stays with the build (§4.2); its unzipped files are deleted. A held-back chart's zip
   is **copied**, never moved, from the older build's `sources/`. Every build then stays complete for a roll back, and a chart held
   back again next cycle still has its zip.
5. **Tile**, in the worker processes, tile by tile from the top level down to 11:
   - each tile warps the charts under it to Web Mercator with Lanczos;
   - lays them by tier, sectional first;
   - encodes WebP at quality 90;
   - writes the tile into the staging MBTiles. A resumed build skips the tiles it already has.
   Only the tiles §4.1 stores are written. A tile with nothing in it is not written; the route answers it (§7).
   This is a purpose-written tiler, rather than `gdal2tiles` or GDAL's MBTiles driver: both tile one raster, and this needs three tiers
   stacked within one tile, each only where it exists.
   **Level 11 is rendered twice where a TAC-tier chart touches it.** The stored copy includes the TAC. A sectional-only copy goes into
   `work/` to seed step 6. That is a small share of CONUS's level-11 tiles.
6. **Lower levels** (10 down to 5) are made from the level below: four tiles shrunk into one, with Lanczos. They are seeded from
   level 11's sectional-only copies where those exist, and from the stored tile everywhere else. **Levels 10 to 5 hold the sectional
   tier only**, as §4.1 says. Shrunk from the stored level 11, they would carry every TAC down to web zoom 6, where its text aliases,
   with a seam round it.
7. **Validate**:
   - the tile counts against `coverage.json`;
   - a known tile at each tier decoded and compared, within a tolerance, with a reference rendered from the source;
   - a known level-10 tile inside a TAC area matches a sectional-only reference;
   - every chart in the coverage present;
   - **all 37 CONUS sectionals present**, this cycle's or kept from an older build. A build without one is refused (§4.2).
   It reports in the shape `validate.check_dem` uses: refused, with reasons in words, and notes that refuse nothing.
8. **Finish**:
   - the child deletes `work/`, renames the staging folder to `/data/charts/builds/<build>/`, holding `vfr.mbtiles`, `coverage.json`
     and `sources/`, and writes `done`;
   - the parent, seeing `done`, registers the build in `state.json` as built, under the state's lock (§5.5). The child never writes
     `state.json`, so the file has one writer at a time;
   - what is left in `.incoming/` is deleted.
   **Registering is idempotent and part of recovery.** If the parent dies between the child's `done` and the write (a redeploy at
   the end of a build), the next look or `resume` registers the build: a chart job whose row is `done`, whose `builds/<build>/`
   exists and which `state.json` does not list (§5.7, step 2). Otherwise the folder would be invisible on the tab, never deleted, and
   its cycle built again. A C1 test kills the parent between `done` and registration.
   **Finishing does not put it live.** Switching is its own step.

### 5.4 The disk

The Data tab's rules apply as they are, by calling `volumes.check_room` with `label="The chart folder"`:
- **The mount check.** The chart folder must not be on the container's own disk.
- **The reserve.** `DATA_ADMIN_RESERVE_GB`, else `max(20 GB, 10 % of the volume)`, must stay free.
- **Room for the streamed LiDAR cache.** If `/data/charts` shares a device with `LIDAR_STREAM_CACHE_DIR`, a write must also leave room
  for that cache to reach its cap. The cap is 200 GB by default, less what it already holds. On a 1 TB volume with an empty cache, a
  build that still has about 22 GB to write (below) needs about 322 GB free: 22, plus the 100 GB reserve, plus 200. Putting the
  charts on another volume avoids that, and so does a lower cap.
  - The cache's size comes from a walk of its folder, which can take a while. The parent works it out once when the job starts and
    hands it to the child; the child does not walk the cache again for each check.

`check_room` is told only the bytes a step is about to write; what is already written shows in the free space. So a build checks at the
start of each step for what is left of its peak, and keeps checking as it writes:
- once at the start (step 0), for the whole build;
- before each zip, then every 256 MB of it, as downloads do;
- before unzipping and before preparing, for their estimated size;
- every 1,000 tiles, for the tiles still to write at the build's measured average size.

A build that would break a rule stops and says why on its job card. The live build is untouched.

Estimates for CONUS (§10, 2), from §4.4's measurements and the chart sizes in §2:

| | |
|---|---|
| One build's tiles | ~7 GB (85,000 tiles) |
| One build's sources, the FAA's zips (§4.2) | ~3.5 GB |
| **One build kept** | **~10.5 GB** |
| Unzipped charts, during a build | ~3.5 GB |
| Prepared RGB charts, during a build (estimate: 2–3× the palette files) | ~8 GB |
| **To write during a build** (new build + work) | **~22 GB** |
| **Between a build and its switch** (live + previous + next) | **~31.5 GB** |
| **Peak during a build** (live + previous + to write) | **~43 GB** |
| **Peak when a cycle is built again while its next build is scheduled, or within a day of a switch** (live + previous + next or retired + to write) | **~53.5 GB** |

These are before the reserve. With the automatic cycle (§5.7), the chart folder holds:
- about 21 GB from a day after a switch until the next edition is built, about five weeks;
- about 31.5 GB for the three weeks from then until it goes live.

Each build that is made and never switched to adds about 10.5 GB until an admin deletes it. **Size the volume for the 53.5 GB case**,
not the 43.

### 5.5 Builds, switching and rolling back

**What is served is named in a file on the chart volume**, `/data/charts/state.json`. A file, not a database row, because staging mounts
the same volume with a database of its own: a row in production's database would leave staging's API unable to tell which build is live.
With the file, both serve the same build, and only production writes it (`DATA_ADMIN=write`; staging's mount is read-only, §5).

```
{ live:     "2026-10-29.1",
  previous: "2026-09-03.1",
  next:     { build: "2026-12-24.1", at: "2026-12-24T09:01:00Z" },   // or null
  numbers:  { "2026-10-29": 1, "2026-12-24": 1 },                   // the last build number given each cycle (§4.5)
  builds:   { "<build>": { cycle, effective, expires, made, by, bytes, tiles, held_back: [...], retired: null | "<time>" } },
  history:  [ { did: "switch", from, to, by: "<admin's name>", at } , … ],  // the last 50
  auto:     { "<cycle>": { … } }                                          // what the automatic cycle did for each cycle (§5.7)
}
```

**Writing it.** Every change is a read, a change and a write, and two at once (a switch pressed as a build finishes) would lose one.
So every writer takes an exclusive `flock` on `/data/charts/state.lock`, reads the file, changes it, writes it to a temporary file and
renames that over it, then lets go. Readers take no lock: the rename makes each read see one whole version. The build process never
writes the file: it only renames its own folder, and the API registers the build (§5.3, step 8).

**One rule for a scheduled switch, used by every reader.** Nothing runs at a scheduled time. Instead one function,
`effective(state, now)`, gives the state as it stands at that moment: once `next.at` has passed, `next.build` is live, the build that
was live is `previous`, and `next` is empty. Everything that asks which build is live calls it: `/api/config`, the tile route,
`coverage.json`'s readers, the brief's figures (`figures.py`), the map's coverage layers, `charts.json` and staging's tab. Each caches
the file in memory, read again when its inode, `mtime_ns` or size changes, and applies `effective` to it on each use. **A `GET` never
writes.** Every write action begins, under the lock, by writing down a switch whose time has passed (with its line in `history`, by
*schedule*, at `next.at`), then does what was asked.

**Actions**, each a `POST` under the CSRF check and `_writable()`, each answering at once (Cloudflare cuts a request at 100 s), each
confirmed in the page before it is sent, and each recorded in `history` with the admin's name and the time. Today the tab records who
started a job and nothing else.

| Action | What it does | Offered when |
|---|---|---|
| **Switch to this build** | `previous` ← `live`, `live` ← this build, and clears `next` (the page says so first when one is set). | a built build that is not live and whose cycle is already in effect. A build for a cycle not yet in effect is offered only the scheduled switch: switched early, crews would plan on charts not yet valid. |
| **Switch at its effective time** | Sets `next` to this build at its cycle's effective time, 0901 UTC (to confirm in C2). | a built build for a cycle not yet in effect |
| **Roll back** | `live` and `previous` change places, so a roll back can itself be undone. It **keeps `next`**: a roll back is about the cycle in effect, and the next cycle's scheduled switch still happens at its time. Cancelling that is its own action. | a `previous` exists |
| **Cancel the scheduled switch** | Clears `next`. Refused once `next.at` has passed: by then it has happened, and the way back is a roll back. | `next` is set and its time has not come |
| **Delete build** | Takes the build out of `state.json`, then removes its folder. | a build that is not `live`, `previous` or `next` |

**What is kept, and when a build is deleted.**
- **Never deleted:** the live build, the previous one (the roll-back target) and a scheduled next one.
- **Never deleted by itself:** a build made and never switched to. It stays until it is switched to or an admin deletes it, so a build
  can be looked at before it is served.
- **Deleted a day after it stopped being live or previous.** A switch that pushes the previous build out marks it `retired` with the
  time. When a scheduled switch is written down late, that time is `next.at`, not the time of the write. Each later write to
  `state.json` deletes, after writing the file, **every build retired more than a day ago that is not live, previous or next in
  `effective(state, now)`**. A roll back retires nothing (it only swaps the two). A client holding an older config therefore still
  draws for at least a day after a switch, and asks for the config again within 5 minutes (§4.5).
- **Every action that makes a build live, previous or next sets its `retired` back to null.** That covers Switch, Switch at its
  effective time, Roll back, and the automation's schedule or switch (§5.7). Otherwise a build switched back to within its day would
  be deleted while live.
- A deleted build's number is not given again (§4.5).

**When a cycle is posted.** The tab works out the next cycle from the 56-day rhythm, and checks with one `HEAD`, kept for 10 minutes as
the tab's layers are, whether the FAA has posted it. The FAA posts about three weeks early. On production the cycle is then built and
its switch scheduled with no one pressing anything (§5.7, the owner's answer to §10, 7). Should that fail and nobody step in, the live
build expires; the clients then draw it with the red *Expired* notice (§10, 8), and the tab shows the same in red.

**Tests.** The state file's lock, its `numbers` and registering a finished build are C1's (§9). The rest are C2's:
- two writers at once both land;
- a scheduled switch is seen by every reader once its time passes, with nothing written until the next write action;
- switch clears `next`, roll back keeps it;
- cancel after `next.at` is refused;
- an early switch is refused;
- a build retired a day ago is deleted at the next write, and one never switched to is not;
- a build switched back to within its day is still there, and still live, after another write a day later;
- delete then rebuild gives a new number.

### 5.6 The Charts section on the tab

**A Charts card** in the side panel, after *Downloads*, read from `GET /admin/data/charts.json` (read-gated, `private, no-store`, as
the tab's other answers are):
- **The cycles.**
  - *Current*: 10-29-2026, in effect 29 Oct – 23 Dec 2026, with days left.
  - *Next*: 12-24-2026, posted or not, and when it takes effect.
  - Each line says which build serves it, if any.
- **The builds.** Each with:
  - its cycle and build number;
  - its state: live, previous, scheduled next, or built;
  - its size and tile count, and when it was made;
  - the charts held back from its cycle, and why;
  - the actions of §5.5 that apply.
- **Not set up:** with no `CHART_TILES_DIR`, the card says so and offers nothing (§4.6).
- **The automatic cycle** (§5.7): on or off and why, its last and next look, and what it did or will do for each cycle. That includes
  *left to an admin's build*, *cancelled by an admin*, *waiting behind another job since …*, and *given up* in red with its reason.
  A given-up cycle has **Try again** (write mode only), which clears its tries.
- **Build now** (write mode only): the current or next cycle, with the space it needs against what is free, before it is started. The
  automation does this by itself (§5.7); the button is for building a cycle again, or when the automation is off. A build made with it
  is the admin's: the automation then leaves that cycle to them, switch included (§5.7). The job then shows in
  the tab's *Downloads* card, renamed *Jobs*, with its stage and tile count.
- **History**: the last switches and roll backs, by whom (an admin, or *Automatic*) and when.

**On the map**:

- **Three coverage layers**, *Sectional*, *TAC* and *Helicopter*, one checkbox each in the Layers card.
  - They are three names in the page's `LAYERS`, each fetched from `/admin/data/layers/vfr-<tier>.json`. One builder in `layers.py`
    reads `coverage.json`, so the page's toggle code (one name, one source, one fetch) is unchanged. `loadLayer`, `data_layer`
    and the popups each gain a `vfr-*` branch; the route takes an optional `?build=`, refused unless `state.json` lists it.
  - Each chart is its neatline, filled by its state against the current cycle: *this cycle*, *kept from the last cycle* (held back, in
    amber), *expired* (red). The outline is dashed where the scheduled next build's edition differs.
  - A click shows the chart's name, scale, edition dates, which builds hold it, and the zoom it is stored to.
  - Built from the live build, or from the build chosen in the Charts card.
- **The chart tiles themselves**, a *Chart tiles* checkbox apart from the `data-layer` ones (the page fetches every `data-layer` as
  GeoJSON). It adds a raster source of the chosen build's tiles under the overlays: `tileSize` 512, from the public tile route on the
  same host. This is how a build is looked at before it is switched to.
- **The map's bounds stay as they are.** The page's `maxBounds`, `[[-180, 5], [-40, 75]]`, holds every CONUS chart. Building Alaska
  later would change this: the Western Aleutians sectional crosses the antimeridian, so its outline would need continuous longitudes
  and `maxBounds` would need to reach about −195°.

Staging's tab shows all of this read-only (`DATA_ADMIN=read`), with no buttons.


### 5.7 The automatic cycle

The owner chose a fully automatic cycle (§10, 7), on production only (§10, 7a): each cycle is built when the FAA posts it and goes live
at its effective time with no one pressing anything. The tab is where it is watched, and where an admin cancels, switches or rolls back.
Everything here reuses §5.2–5.5: an automatic build is the same job as one an admin starts, and its switch the same scheduled switch.

**Where it runs.** A *look* (below) runs only where `DATA_ADMIN=write` and `CHART_TILES_DIR` is set. Staging (`read`) never looks: it
serves what production built. Whether a look may also *build* is `CHART_AUTO`:
- **On by default only where `security_config.is_production` holds.** Elsewhere it is off unless `CHART_AUTO=on`. A developer's C1 setup
  (a mounted folder in write mode) therefore starts no CONUS build by itself, and anyone trying the automation locally opts in.
  Production is the one place with `is_production`, `DATA_ADMIN=write` and `CHART_TILES_DIR` together. Staging has `is_production`
  too, but its `DATA_ADMIN` is `read`.
- **`CHART_AUTO=off`** turns it off on production too, for a first build by hand or while something is wrong.
- **Off, a look still runs.** It still does its housekeeping (steps 1–2) and still sends the expiry emails, which say the automation is
  off. It picks, starts and schedules nothing (steps 3–6). So a flag left set after a first build by hand is noticed before the
  charts expire.

The tab says which, and why.

**What starts it.** It is the first thing in the backend to start work by itself, so it is kept small:
- A daemon thread started beside the terrain warm-up in `app.py`, behind the same reloader guard, so `python app.py` starts one.
- It looks 5 minutes after start, then every 6 hours. After a look that found the tab busy, it looks again in 30 minutes.
- **One look at a time across processes.** A redeploy can run the old and new containers together for a moment. So each look first
  takes a non-blocking `flock` on `/data/charts/auto.lock`, and a process that cannot take it skips that look. The tab's one-job rule
  (409 under `_creating`) holds within one process only; this lock is what keeps two containers from starting two builds.
- Everything in a look is caught and logged: a failure there never reaches a request or ends the thread.

**What one look does**, in order, under the state's lock where it writes (§5.5):
1. **Writes down a switch whose time has passed, and deletes builds retired more than a day ago** (§5.5). The look is a write action, so
   this happens within 6 hours of a switch rather than waiting for an admin.
2. **Recovers what a stop left behind:**
   - It **registers** any chart job whose row is `done`, whose `builds/<build>/` exists and which `state.json` does not list (§5.3,
     step 8). If the automation started that build, it then applies step 6 to it.
   - It **resumes every job left queued or running**, DEM downloads included, through the tab's `resume`. A chart build is judged by its
     staging lock, so one still running in the other container of a redeploy is left alone (§5.1).
   - It **deletes leftovers**: `builds/*` and `.staging-*` folders that neither `state.json` nor a queued or running job names, and
     `.incoming/` when no chart job runs.
3. **Works out the cycles**: the current one (in effect now) and the next, from the 56-day rhythm, and whether each is posted. That is
   one `HEAD` each, kept for 10 minutes.
4. **Picks the first of [current, next] that wants a build.** A cycle wants one when all of these hold:
   - it is posted;
   - **no build of it exists, whoever made it**: none live, scheduled or built in `state.json`, and no job for it queued, running, or
     `done` but not yet registered;
   - the automation has not built it before, has not given it up, and no admin has cancelled its build.

   So a current cycle that is given up, or left to an admin, lets the next cycle be built as soon as it is posted.
   **An admin's choice is never undone.** It sticks for the rest of that cycle, and it is not a try. These count as an admin's choice:
   - **A build an admin made of the cycle**, switched or not. The automation records `admin_built` and does nothing more for that cycle:
     no build, no schedule, no switch. An admin who made a build to look at it keeps the decision to switch. If they forget, the 7-day
     email covers it.
   - **Deleting a build the automation made, or cancelling its schedule.** It is not made again.
   - **Cancelling a running automatic build.** The automation records `cancelled`, with the admin's name and the time, and shows it on
     the card. It is not started again, and no "given up" email is sent.

   The admin can build any such cycle from the tab, and that build is theirs.
5. **Starts the build.** If any job is queued or running, it does nothing and looks again in 30 minutes; nothing is counted.
   Otherwise, under the state's lock, in one write, it takes the build's number (§4.5) and records
   `auto.<cycle>` = `{ started: <build>, job_id, last_try: now, tries: +1 }`. It then creates the job through the same function as
   `POST /admin/data/charts/builds`, with `requested_by` empty and `requested_by_name` *Automatic*. From that write on, the cycle is
   taken (step 4), so no later look can start a second build of it, whatever happens to the parent.
6. **When a build it started is registered** (by its parent, or by step 2), the automation acts in the same write:
   - **schedules it** for its cycle's effective time (`next`), if `next` is empty; or
   - **switches to it now**, if its cycle is already in effect and the live build is of an older cycle, or there is none (the first
     build, or a cycle built late);
   - does neither if an admin has set `next` or made a build of this cycle meanwhile;
   - and records `built`.

   A build with charts held back (§4.2) is scheduled all the same, since each held-back chart is the last edition's, kept; the email
   names them. A build missing a sectional never gets this far: it is refused (§5.3, steps 3 and 7).

**When it fails.**
- **A cycle not yet posted is never a try.** It is waited for, however late the FAA is.
- **These are not tries either:** a waiting job; an "already running" lock (§5.1); a restart after a redeploy; a build an admin
  cancelled.
- **A download or validation failure is a try.** A cycle is tried at most three times, at least 6 hours apart, then given up
  (`gave_up`). The tab shows it in red with the last reason, and the owner is emailed once.
- **A refusal for room is not retried.** It gives the cycle up at once, with what the room check said, and is emailed, because room
  has to be made by someone. *Try again* on the Charts card clears `gave_up` and the tries, for this case and the one above.
- **A cycle that has waited more than 24 hours behind another job** is emailed once, naming the job.

**Being told.** Each event is emailed once (`told`), through the existing `email_service` (Resend), to `CHART_NOTIFY_EMAIL`, else
`SUPER_ADMIN_EMAIL`:
- a cycle built and scheduled, or switched to: when it switches, and which charts were held back, if any;
- a cycle given up, with the reason;
- a cycle waiting more than 24 hours behind another job;
- the live build 7 days from its expiry instant (§4.5) with nothing scheduled. The email says why: the FAA late, a build an admin made
  and has not switched, or the automation off;
- the live build past its expiry instant. This is judged on `effective(state, now)`, so a switch due at that instant counts first,
  and the correct chart going live at 0901Z is never reported as expired.

The expiry emails are sent whether the automation is on or off. Email is an addition to what the owner asked; it is how a fully
automatic job that fails is noticed before crews see a red line.

**On the tab** (§5.6), the Charts card shows:
- whether the automation is on, and why not;
- its last and next look;
- for the current and next cycle, what it did or will do (*12-24-2026: posted 3 Dec, built 3 Dec as 2026-12-24.1, switches 24 Dec
  0901Z*), with tries and the last error, or why it is leaving the cycle alone;
- in `history`, its actions by *Automatic*.

**Tests (C2).** These run on a fake clock with the FAA host faked, as `test_data_admin.py` fakes USGS's. They check that:
- a cycle posted is built and scheduled;
- a current cycle with nothing live is built and switched to;
- each cycle is built once, and a deleted build or a cancelled schedule is not remade;
- an admin's unswitched build of the current cycle makes the automation build nothing, schedule nothing and switch nothing;
- an admin's `next`, or an admin's live build of the cycle, is left alone;
- an automatic build cancelled mid-run is not restarted at the next look (the 30-minute busy look included), and its tries are
  unchanged;
- a current cycle not yet posted burns no try;
- a given-up current cycle still lets the posted next cycle be built and scheduled;
- another job running makes it wait;
- a DEM row left running by a stopped process is resumed by the look, with no page opened;
- three download failures give up, with one email, and a refusal for room gives up at once, with one email;
- *Try again* clears it;
- a parent killed between `done` and registration leaves a build the next look registers and schedules, with no second build started;
- `CHART_AUTO=off` starts no build but still sends the 7-day and expired emails;
- write mode with `CHART_TILES_DIR` set on a non-production environment starts nothing unless `CHART_AUTO=on`;
- `DATA_ADMIN=read` does nothing;
- the expired email is not sent at 0901Z when a switch is due then;
- two processes at once start one build;
- a look whose code throws leaves the thread running.

---

## 6. Time to build (estimate)

On the owner's Mac, from data held in memory, a tile took **0.23–0.27 s**: warp with Lanczos, plus the six encodings §4.4 compared. One
WebP encode is a fraction of that, so expect about **0.1 s a tile on one core**:
- **about 2.5 core-hours** for CONUS's 85,000 tiles: about **75 minutes on the default two processes**, 40 on four;
- longer while analyses pause it (§5.1);
- plus the download, about 3.5 GB from the FAA for CONUS. Our test pulled 200 MB in seconds.

The VM's real figure is the first thing C1 measures, sharing the API container's CPU.

---

## 7. Serving

**The routes.** `GET /charts/vfr/<build>/<z>/<x>/<y>.webp` and `GET /charts/vfr/<build>/coverage.json` on the API, outside `/api`,
like `/r/<token>`. Both go into `contracts/openapi.yaml` in C2, with `coverage.json`'s schema (§4.5).
- **The spec allows only `/api` today.** Its description says every route is under `/api`, and `test_every_documented_path_is_under_api`
  holds it to that. C2 changes both to allow the public `/charts/` prefix as well: unauthenticated, CORS `*`, outside `/api` on purpose.
  Moving the routes under `/api/charts` would put them behind the affiliation gate (`affiliation_gate.py` checks `/api/`) and
  flask-cors's `/api/*` rules. C2 also checks that `contracts/scripts/api_version.py check` reads the two new paths as additive.
- **No sign-in.** The tiles are public FAA data. The route takes no JWT, and the affiliation gate covers only `/api/`.
- **Which builds it serves.** `<build>` must be a build `state.json` lists, and `z`, `x`, `y` must be in range. Nothing from the URL
  reaches a path. Any build that is still there is served, not only the live one: a client holding an older config can still draw, and
  the Data tab can show a build before it is switched to.
- **Headers:**
  - a tile: `Cache-Control: public, max-age=31536000, immutable`, and `Access-Control-Allow-Origin: *`, set by the route itself
    (flask-cors covers only `/api/*`). The LZ card's canvas capture draws tiles with `crossOrigin="anonymous"`.
  - `coverage.json`: the same, as a build's coverage never changes either.
  - **ground with nothing to draw**, in a listed build, at levels 5–14, inside its `bounds`: the open sea, or past the coverage's edge.
    The answer is 200 with one shared, fully transparent 512-pixel WebP and the tile's own headers. It is what a tile with no stored
    tile and no stored ancestor gets (overzoom, §4.1). So a listed build answers every tile a client asks for, and a chart tile that
    fails tells the client something (§4.5).
  - outside the build's `bounds` or levels: 404, `public, max-age=86400`. Clients do not ask for these: the layer is bounded (§4.6).
  - a build `state.json` does not list (deleted, or never made): 404 with `Cache-Control: no-store` and `X-Chart-Build: gone`. No
    browser, tile cache or Cloudflare keeps the refusal. The header is for the tests and for whoever reads a trace. The clients do not
    need it: they ask for the config again on any chart tile that fails (§4.5).
- **Overzoom** is §4.1's.
- **The archive.** One read-only connection per build, opened with `mode=ro&immutable=1`, which is safe because a build never
  changes under its name. Connections are kept in one registry for the process (opened with `check_same_thread=False`, used under a
  lock per build: a tile read is a single indexed query), not one per thread, because a thread's connection cannot be closed from
  another thread. When `state.json` changes (its inode, `mtime_ns` or size), the connections to builds it no longer lists are closed;
  so is one not used for 10 minutes. Without that, staging, which never deletes, would hold deleted builds' files open and their
  space would not come back. A file that will not open is a `no-store` 404, logged, never a 500.
- **The admin host** (`admin.ezpztac.app`) is the same Flask app, so the route answers there too. The Data tab's map uses it on a
  relative path.

**Cloudflare.** It caches proxied responses by `Cache-Control`, and `.webp` is among the extensions it caches by default.
- **Owner step:** add a Cache Rule for `/charts/*` on the API's hostname (cache everything, respect origin headers).
- Check that Cloudflare's terms for the plan in use allow image tiles at this volume before the rule goes on (the owner's answer to
  §10, 9). The users are few, but check.

**Load.** Tiles are small reads from SQLite. With Cloudflare in front, the API sees each tile about once a build.

---

## 8. The clients

**Web** (`mapStyles.js`):
- `vfr-sectional` keeps its id and label. A diagram saves the id, so saved plans need nothing.
- Its URL comes from `/api/config`, and with none it is today's ArcGIS service (§4.6). While the VFR map is shown, the config is asked
  for again every 5 minutes, at `expires`, and after a chart tile fails. The last is at most once a minute and past the browser's
  cache (§4.5). A new build changes the layer's URL.
- `tileSize: 512` and `zoomOffset: -1`, as the Mapbox styles have; native zooms 6–15 (web zoom); `maxZoom` 20.
- The attribution is a small component that reads `coverage.json` (§4.5).
- The preview tile moves to one the archive has.
- The survey editor's map already leaves VFR out (`SurveyMap.jsx`).

**Android** (`MapStyles.kt`):
- The VFR style's `tiles`, `tileSize` 512, `minzoom` and `maxzoom` come from the config, which is remembered as the Mapbox token is.
- With none remembered, the ArcGIS service is drawn (§4.6).
- While the VFR style is shown, the config is asked for again every 5 minutes, at `expires`, and after a chart tile fails, as on the
  web. The ArcGIS fallback source never triggers it.
- The attribution and the overzoom line are Compose, from `coverage.json`, kept with the config.
- MapLibre's own tile cache keeps tiles for offline use.

**The brief's figures** (`backend/brief/figures.py`) choose **one source per figure**, never per tile, so one image never mixes tile
sizes or editions:
- **The live build** is the source where all of these hold:
  - `effective()` names a live build;
  - `CHART_TILES_DIR` is set;
  - the figure's or strip's area lies within the build's `bounds` and touches no `fallback` rectangle.

  It is read with no HTTP, each tile through **the tile route's own function**: the stored tile, else its nearest stored ancestor
  enlarged, else the transparent tile (§4.1, §7). The framing's top level (`choose_zoom`'s `max_zoom`) is the highest level stored
  for the charts under the area, from `coverage.json`, so a figure at a sectional-only post is framed as it is today.
- **Otherwise today's ArcGIS source** is used for the whole figure (256 px, up to level 12). That covers Alaska, Hawaii, the Fly backend,
  a server with no charts, and the time before the first build.
- **The figures' tile cache is keyed by build**, `(source_id, build, z, x, y)`, so a brief made across a switch never mixes two
  editions.
- **The attribution names the source used.**

Strip charts at 1:100,000 and 1:250,000 then show a TAC or helicopter chart where one exists, at its own symbol size.

**The 3D view** drapes Mapbox imagery, not the VFR map, so it does not change.

---

## 9. Steps

Each step is its own PR off `develop`, with AGENTS.md updated in it. The owner chose to build them all in turn, Android included, with
no stop after the web to decide (§10, 1).

| Step | What | Done when |
|---|---|---|
| **C0** | The neatline tool and the ~84 polygons (§4.2), checked. A local prototype of §5.3 over three charts. | The Atlanta sectional and TAC and the Washington helicopter chart tile seamlessly, the measurements of §4.4 and §6 hold, and the prototype's `coverage.json` is recorded in `contracts/fixtures/charts/`. |
| **C1** | The build as a Data tab job: the runner by `kind`, the `DataJob` columns, `charts.build` in its own process with its own database factory (§5.1), the parametrised downloads and the unzip step, the room checks, `CHART_TILES_DIR` in the volumes panel, the *Jobs* card's stage line and its polling after a failure. **`state.json` with its lock and `numbers`, and registering a finished build** (§4.5, §5.3 step 8), which a build needs to be numbered and kept. | Locally, against a mounted folder and a small cycle: a build made from the tab, and tested: refused on the container disk, refused below the reserve, a cancel that cleans up, a resume while the old child holds the lock that writes and starts nothing and one after that starts again (never two, never `failed`), a stale pause file not holding the build, the child's progress never overwritten, a parent killed between `done` and registration, a sectional with no older copy refusing the build, `torch` never imported. **Then, after C1 is released to production** (only production's mount is writable and has `DATA_ADMIN=write`), **by the owner:** a whole CONUS cycle built on the VM, its time and size measured. Nothing serves it until C2. |
| **C2** | What reads `state.json` and changes it after a build: `effective` and the actions of §5.5; the automatic cycle and its emails (§5.7); the tile and coverage routes with overzoom, the transparent tile and the connection registry; `/api/config`'s `charts` with `bounds` and `fallback`; the Charts card, the three coverage layers and the tile preview. Owner steps first (§5): Cloudflare, and `CHART_AUTO` if C1's build is to be switched to by hand. | `contracts/openapi.yaml` (config, both routes, the coverage schema), with its `/api`-only rule and its test widened for `/charts/` (§7), the fixtures and the API version moved; the routes' tests (TMS flip, the transparent tile, 404s and their headers, a build not listed, connections closed when a build goes); the fallback table pinned to the ArcGIS tiles measured; §5.5's and §5.7's tests; staging's read-only tab tried once production runs C2, since only production writes `state.json`. |
| **C3** | The web: the style from config with its fallback (none at all, and ArcGIS outside CONUS), the config asked again while VFR shows, the attribution, the "no finer chart" line; the brief's figures, by one source per figure (§8). | Held to the coverage fixture; the LZ card exports with VFR; an expired build shows red; a switch reaches an open page within 5 minutes. A brief figure and a strip chart on VFR print a chart, not grey squares, at a sectional-only CONUS post, in Hawaii, and on a server with no charts. |
| **C4** | Android: the style from config, the fallback (none at all, and ArcGIS outside CONUS), the config asked again while VFR shows, the attribution and the line. | Held to the same coverage fixture as the web. |
| **C5** | A real cycle change on production, by the automation: the next edition built when posted and its switch scheduled with no one pressing anything, the email received, the switch seen in both clients, and a roll back tried from the tab. | Seen by the owner on production. |
| later | Vector airspace and airports (§11). | — |

The tests of the chart routes and state go in a file CI runs (`backend.yaml`'s list), with their imports added to its install step.
`test_data_admin.py` itself runs only locally today.

AGENTS.md changes as each step lands:
- §4: the *Map layers* row, and the Data tab's entry, which gains charts;
- §6: the tile route and the Data tab's chart routes;
- §8: the FAA as a source;
- §9: the build's process, the automatic cycle's thread (the first work the backend starts by itself), and the owner steps;
- §10: `CHART_TILES_DIR`, `CHART_BUILD_PROCESSES`, `CHART_AUTO` and `CHART_NOTIFY_EMAIL`;
- §17: *The map*.

---

## 10. The owner's answers (2026-11-05)

| # | Question | Answer | What follows |
|---|---|---|---|
| 1 | Is a cleaner sectional worth it where it is the only chart (most Army aviation posts, §3.2)? | **Yes, all at once.** | C0 to C5 are built in turn, Android included, with no stop to look after the web (§9). |
| 2 | Alaska, Hawaii, Puerto Rico? | **CONUS only.** | Only the CONUS charts are built (§4.1). Outside CONUS the clients draw today's ArcGIS service, only there and where it has charts (§4.6). Puerto Rico stays blank, as it is today. |
| 3 | The 21 Flyway Planning charts? | **Left out.** | As recommended. |
| 4 | Store each tier to its native zoom rounded up, or native plus one? | **Rounded up.** | As recommended. |
| 5 | WebP at quality 90 or lossless? | **WebP, quality 90.** | As recommended. |
| 6 | Where a build runs. | **In the API container**, a child process at low priority, paused during analyses. | As recommended; the owner confirms Coolify's CPU and memory limits first (§5). |
| 7 | Who starts each cycle? | **Fully automatic.** | A cycle is built when the FAA posts it and its switch scheduled for its effective time, with no one pressing anything; the tab watches, and an admin can cancel, switch or roll back (§5.7). |
| 7a | Automatic where? | **Production only**. | It builds only where `is_production`, `DATA_ADMIN=write` and `CHART_TILES_DIR` hold together, or where `CHART_AUTO=on` opts in. Staging serves what production built (§5.7). |
| 8 | When the live build has expired? | **Keep drawing it with a red *Expired* notice; keep ArcGIS as the fallback** where the server has no charts. | As recommended. |
| 9 | Cloudflare caching of `/charts/*`. | **Yes, but check the plan's allowance** before production. | An owner step (§7). |
| 10 | One Data tab job at a time? | **Yes.** | An automatic build waits for its next look while a DEM download runs. A DEM download asked for during a chart build is refused (409) until the build ends (§5.7). |

The questions as they were put, with their recommendations, are in commit a87179c5 ("docs: build, switch and roll back VFR chart builds
from the admin Data tab"), which this file's PR carries. PR #145 merged an earlier draft, whose questions and recommendations for 6 and 7
differ.

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
- The VM's build time sharing the API container's CPU, Coolify's limits, and Cloudflare's caching through the tunnel.
- That the Linux wheels in the API image do what the macOS ones did here: rasterio 1.4.4's GDAL (3.10.3) reading and warping the FAA's
  GeoTIFFs, and Pillow 12.1.1 encoding WebP. C1 checks them in the image first.
- The FAA's effective time for charts (0901 UTC is the usual), and that next cycle's folder appears where this one's did. C2 and C5
  confirm them.
- The automatic cycle on production: its thread across a real redeploy (two containers at once), the staging lock held across them,
  and its emails through Resend. C5.
- The ArcGIS fallback's rectangles (§4.6): the islands it serves were probed tile by tile here, and their edges are C2's to measure.

The owner checks each in the step named.
