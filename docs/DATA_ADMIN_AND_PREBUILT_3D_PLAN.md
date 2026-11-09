# Plan: 3D in seconds, data on the server, prebuilt areas and organizations

Status: proposed 2026-10-22, and reworked the same day around streaming 3D from USGS's own octree (Part C), which
measured about fifty times faster than building. **Decided 2026-10-23:** the owner answered all 20 decisions on the
decisions page, with no notes, so none is open (§9). Eighteen follow the recommendation; two do not: D1 (MapLibre GL JS
for the admin map) and D13 (several AOs per organization). **2026-10-26:** Phase 0 built (its own PR); Phase 1 next, with
the owner's answers of that day: existing tilesets keep their old keys (no rename), the streamed `resolve` and its
`frame` go only to a client that asks for them in the request (so an installed app that frames on the root sphere keeps
the build flow), and tests build a tiny octree themselves, with a live AWS test behind an environment variable and no
USGS data committed. **Phase 1 built for the backend, the web and Android the same day** (*Phase 1 as built*, below).
**2026-11-04:** the admin's Data section (the map of what the server holds, and adding DEMs and LiDAR to it) planned again
against the code and TNM's API as they are, in §10, which replaces Parts A and B for it. Its questions are the owner's (§10.6).

The request, in the owner's words: an admin page with a CONUS map showing where the server holds terrain and LiDAR
itself (not what comes from outside services); drag-and-drop uploads into the server's volumes; areas chosen on the
map and built ahead, so 3D opens at once there instead of after minutes; prebuilt areas sold as an upgrade; and a way
to release it to individual and organization accounts, with an organization's prebuilt access held to its area of
operation (AO). Then: are there faster ways than building at all? There are, and this version is built on one.

**The earlier discussion.** The owner says this was discussed before. It is not on this Mac: no Claude desktop session,
Claude Code transcript or Codex thread here mentions a coverage map, prebuilding or areas of operation, and nothing in
the repo or on any branch does. It was probably on a Windows PC or in a claude.ai chat. This plan starts from the words
above. If something was decided there that differs, it wins and this document changes.

Earlier decisions that bear on it, all in the repo:
- AGENTS.md §9's 3D table. Pre-generating every terrain tile was rejected (D) because fine tiles cost about 50 ms each
  on demand. Cesium ion was rejected: commercial pricing, and its terrain need not match our DEMs and LiDAR.
- `docs/NATIVE_APPS_PLAN.md`'s map packs. Offline areas built on the server from public-domain data (NAIP imagery,
  USGS DEMs), with "3D for LZs whose LiDAR was packed". A warmed area (Part C) is the natural source for that later.

---

## 1. What exists today

| Piece | Where | What matters here |
|---|---|---|
| DEM catalog | `terrain_provider.LocalRasterCatalog` | Walks `TERRAIN_DATA_DIR` every 5 min, opening every `.tif`, `.tiff`, `.cog` and `.dt2` it finds. Each entry has its bounds, resolution and a vertical datum read from a `VERT_DATUM` tag. Slope analysis takes COGs of 15 m or finer, then DTED2. |
| 3D terrain tiles | `terrain_tiles` | Every DEM in the catalog is sampled, DTED included, and **every one is treated as NAVD88** (the geoid offset is EPSG:5703's). Tiles are cached under one fingerprint of *all* DEMs. Levels 0–9 over the whole coverage are warmed at startup. |
| USGS's LiDAR on AWS | `lidar/coverage.py`, `lidar/build.py` | Every 3DEP survey is published as **Entwine Point Tiles (EPT)**: an octree of LAZ pieces by level of detail, with its index split into files. Builds read it with PDAL (`readers.ept`). `coverage.py` picks the survey for a target, newest first, from an 8 MB index of their footprints. |
| Downloaded LiDAR | `lidar/collection.py` on the build service | `/data/lidar/tile_index.json` holds each LAZ tile's bounds in its own CRS, its point count and its CRS. Plain tiles, no levels of detail. The scan reads **only the top folder**. |
| Tileset store | `lidar/catalog.py`, `/data/tiles/<key>/` | One tileset per LZ, with an `area.json` (centre, radius, survey). `resolve` finds the nearest whose square holds the target within 75 % of its radius (`find_covering`), reading every manifest on every call. |
| Build service | `lidar/worker.py`, `backend/lidar` image | One build at a time, at most 8 queued or running, all in memory. Builds someone is watching go first; `keep` builds (a saved LZ) always finish. The radius is capped at 1 km. Each build: PDAL reads, filters the classes, works out height above ground, colours every point from Mapbox imagery, reprojects; then py3dtiles sorts the points into a new octree. |
| Mounts (`backend/lidar/SERVER_SETUP.md`) | Coolify | `/data/topo` → API. `/data/tiles` → both (the builder writes, the API reads). `/data/lidar` and `/data/cache` → builder only. **Staging shares all of them with production** (AGENTS.md §9). |
| Admin | `routes/admin_routes.py`, `templates/admin/` | Jinja, a session cookie and CSRF tokens, no JavaScript libraries. One page posts a file (an AMPS template, 32 MB at most). |
| Entitlements | `entitlements.py` | Per-user feature map. A missing key means on, except keys in `DEFAULT_OFF` (not launched yet: off but for admins and testers). There are no plans, prices or organizations. |
| Teams | `routes/team_routes.py` | Made by users for mission packs and name search. Any user can create one. |

Measured costs (north Georgia):
- **A build** of 1 km² (500 m radius) takes **137 s** from AWS with level-18 imagery (AGENTS.md §13).
- **Streaming the same data** (Part C, measured 2026-10-22): all 26 EPT pieces under a 782 m cube at the Ellijay test
  LZ came down in **2.4 s** and converted in **0.31 s**.
- **Disk:** about 15 bytes a point as built today, 9 with quantized positions.
- **Terrain tiles:** about 50 ms each from level 9 down, 8,450 bytes each (AGENTS.md §9).

## 2. Found while reading: fix these first

1. **Any signed-in user can list every place a 3D view was ever built, with its coordinates.** Tried against the real
   blueprint (a throwaway test with the `test_lidar_api` harness):
   - `GET /api/lidar/tilesets` returns every key.
   - `GET /api/lidar/tilesets/<key>/area.json` returns that place's latitude, longitude and radius, because the file
     route serves anything in the folder.
   - Even without those, a key is an unkeyed SHA-256 of the coordinates rounded to 4 decimals and the radius. CONUS is
     about 1.5 × 10¹¹ such cells, which one GPU hashes in seconds and a laptop within the hour. So a key can be turned
     back into its place.

   The brief cites an AGENTS.md §15 note about this; no such note existed on `develop` or any branch, so this plan added
   one. The web never calls the list route, nor does the Android 3D branch. Streaming stops new tilesets being made, but
   the existing ones stay on disk, so the fix stands (Phase 0):
   - serve only tile files (`.json` but not `area.json`, `.pnts`, `.b3dm`, `.glb`, `.subtree`);
   - make the list admin-only;
   - derive any new key with an HMAC under a server secret.
2. **Adding any DEM throws away the whole terrain cache.** The cache key is a fingerprint of every DEM's path, size and
   time, so one upload in Texas recomputes Georgia's warmed tiles. Uploads and warming areas both need the key per tile:
   only the DEMs that reach that tile.
3. **The LiDAR index is written in place** (`save_index` is a plain `write_text`). A reader catching it mid-write fails.
   Once the API also writes it (after uploads), it must be written to a temporary file and renamed. The scan must also
   look in subfolders, so each survey can have a folder of its own.
4. **The 3D view frames the whole tileset** (`frameSphere(tileset.boundingSphere)` in `Viewer3D.jsx`; Android's branch
   does the same). A streamed survey is a whole state's octree, so it would open on the state, not the LZ. `resolve`
   must say where to look, and both viewers must use it.
5. **`find_covering` reads every `area.json` on every call.** Streaming freezes the number of per-LZ tilesets, so this
   matters less; an index in memory, refreshed when the folder changes, is still cheap to add.

---

## 3. Part A: the coverage map

*Replaced for the admin's Data section by §10 (2026-11-04).*

A **Data** tab in the admin (`/admin/data`): a CONUS map with layers, and beside it a panel of numbers.

**Layers** (each can be switched off):

| Layer | From | Drawn as |
|---|---|---|
| DEMs | `LOCAL_CATALOG.entries()` | Rectangles, coloured by resolution: 1 m, about 3 m, 1/3″ (about 10 m), DTED2 (about 30 m). Clicking one shows its file, resolution, datum and size. |
| Downloaded LiDAR | `tile_index.json`, each tile's bounds turned into WGS84 with pyproj (once per CRS group, cached against the index's modification time) | Tiles coloured by the survey's year: 2015 or later classifies vegetation. Above about 20,000 tiles they are merged into 0.05° cells until zoomed in. Marked when they have been indexed for streaming (D24). |
| Warmed areas | the database (Part C) | Outlines coloured by state (queued, warming, ready, failed), with progress. |
| Streamed views and old per-LZ tilesets | the stream cache's index; `area.json` of each tileset | **Counts in 10 km cells, not exact places** (D16). Exact squares would show the admin where crews plan, which the admin otherwise never sees: the Packs page deliberately never shows what a pack holds. |
| Organizations' AOs | the database (Part D) | Outlines, once Part D exists. |
| AWS 3DEP surveys (off by default) | `usgs_ept_coverage.geojson` | Faint footprints, by year: where streaming has data. Outside data, so off unless asked for. |

**The panel:**
- counts and areas per layer;
- free and used space on each volume (`shutil.disk_usage`), and the stream cache's size against its cap;
- the build service's queue while it still runs (`/health` already reports it).

**Where the data comes from.** The API can read `/data/topo` and `/data/tiles` today. It also needs `/data/lidar`, read
only for the map and writable for uploads (Part B). The alternative is to ask the build service for a summary, which
keeps the mounts as they are but puts a new endpoint on the builder for every layer. Mounting is simpler, and the paths
in `tile_index.json` are absolute, so **both containers must mount it at the same path** (D2).

**The library (D1, decided: MapLibre GL JS).** A map in the admin needs a map library, a new dependency for the
backend. The owner chose MapLibre GL JS (BSD-3) over the recommended Leaflet; it is the web counterpart of the MapLibre
Native the Android app draws with.
- One exact version, copied with its CSS into `backend/static/vendor/maplibre-gl/` so the admin needs no CDN (about
  800 KB, loaded only by the admin's Data and Orgs pages). It needs WebGL, which every desktop browser has.
- No drawing plugin: the rectangle and polygon tools Part C needs are plain JavaScript over a GeoJSON source.
  (`mapbox-gl-draw` would be a second dependency, so it is not used.)
- **Base map: the USGS National Map** (topo and imagery) as raster sources. Public domain, keyless, CONUS-wide. The
  Mapbox token is URL-restricted and may refuse the admin host.
- The layers are GeoJSON sources drawn on the GPU, so thousands of LiDAR tiles draw easily; the 0.05° cells above
  are kept only to keep the download small.

**Endpoints.** All are admin session plus CSRF, JSON, `Cache-Control: private, no-store`:
- `GET /admin/data/coverage.json?layers=…&bbox=…` (the bbox for zoomed-in LiDAR tiles);
- `GET /admin/data/volumes.json`.

**Fly.** The Fly backend has no data volumes, so the page says so and offers nothing there.

---

## 4. Part B: uploads into the volumes

*Replaced for the admin's Data section by §10 (2026-11-04).*

Streaming makes AWS the source of LiDAR for 3D, so uploads matter most for **DEMs** (slope analysis and the 3D terrain)
and for LiDAR that is **not on AWS**: a state survey, a newer flight, a file handed over.

### What goes where

| File | Accepted | Lands in | Then |
|---|---|---|---|
| DEM | `.tif`, `.tiff` (GeoTIFF or COG), `.dt2` | `/data/topo/<folder>/<name>` | The catalog is refreshed at once and tiles under the new DEM are warmed (levels 0–9). |
| LiDAR | `.laz`, `.las` | `/data/lidar/<survey folder>/<name>` | The tile is added to `tile_index.json` (written to a temporary file and renamed), then to the local streaming index (D24). |

`<folder>` is chosen in the upload panel: an existing folder, or a new name such as `GA_Statewide_2018`.
File names are made safe by the server, and an existing name is never overwritten: replacing a file is an explicit
second step.

### Getting big files through Cloudflare

- **The limit.** Cloudflare refuses a request body over **100 MB** on the Free and Pro plans (200 MB Business, 500 MB
  Enterprise). It answers an origin that takes more than 100 s with a 524.
- **The files.** A 1/3″ DEM tile is about 400 MB, a 1 m DEM tile about 400 MB, and a LAZ tile 50–500 MB.
- **So uploads are chunked and resumable** (D3): a protocol written here (in the spirit of tus), with no library on
  either side.

1. `POST /admin/data/uploads` with the name, size, kind and target folder. The server checks:
   - the type is allowed;
   - the volume has room (size + 10 % + a 20 GB reserve);
   - writes are enabled on this server (`DATA_ADMIN=write`; see below).

   It makes `/data/<volume>/.uploads/<id>.part` on **the same filesystem** as the destination, so the final move is an
   atomic rename. It answers `{id, chunk_size: 64 MiB, received: 0}`.
2. `PUT /admin/data/uploads/<id>` once per chunk, with `Upload-Offset`, `Content-Length` and the chunk's SHA-256
   (`X-Chunk-SHA256`, from the browser's `crypto.subtle`, at most 64 MB in memory at a time).
   - The server writes at the offset as the body streams in.
   - It moves `received` on only if the hash matches.
   - It answers `409` with the true offset if the client is out of step.
   - Chunks go one at a time: the API runs one process of 8 threads, and terrain tiles share them.
   - The limit is per request (`request.max_content_length`).
3. `GET /admin/data/uploads/<id>` reports `received`, the state and the validation result.
   - If the connection drops, the page resumes from `received`.
   - After a reload, dropping the same file again (same name, size and modified time) resumes it. A browser cannot
     reopen a file without the person.
4. When the last chunk arrives the server answers at once and **validates in the background** (Cloudflare's 100 s).
   The page polls for the result.
5. A valid file is renamed into place and the catalog or index refreshed. An invalid one stays in `.uploads` with the
   reasons, for the admin to delete.
6. A sweep removes `.part` files with no progress for 48 hours.

**Admin only, production only.** Every route is behind `admin_required` and CSRF. `DATA_ADMIN` (`off` | `read` |
`write`) decides what the page may do:
- `read` shows the map;
- `write` adds uploads and warming, and is set **only on the production backend**;
- staging mounts the same volumes, so with `write` there a staging admin would be writing production's data.

Coordinates and file contents are never logged; upload ids are random.

### Validation: the geoid trap, and the others

The 3D terrain treats every DEM as NAVD88 (§1), so a DEM in any other datum is silently wrong in 3D. That is the trap
AGENTS.md §13 says recurs. Each check gives its reason in words.

**DEMs** (rasterio, in the API):
- it opens, has a CRS, and has a nodata value;
- band 1 is elevation;
- the bounds, in WGS84, lie inside **the geoid grids' box** (−125 to −66, 24 to 50). Both images bake only those
  grids, and outside them PROJ passes heights through and reports success;
- the resolution is 15 m or finer. A coarser GeoTIFF would be ignored by slope analysis, though not by 3D;
- **the vertical datum.** With `GTIFF_REPORT_COMPD_CS=YES`, a compound CRS must have NAVD88 (EPSG:5703) as its vertical
  part. With no vertical key the admin must tick "NAVD88 (USGS 3DEP)" for that upload, which writes `VERT_DATUM=NAVD88`
  into a `.aux.xml` beside the file (the tag the catalog already reads). Any other stated datum is refused (D5);
- **the vertical unit** is metres. State DEMs in US survey feet exist and would read 3.28 times too high;
- **plausibility against a reference.** Heights at 25 points are compared with Terrarium, built from USGS's NED over
  CONUS. The median difference must be within ±5 m. This catches ellipsoidal heights (about −30 m in Georgia), feet (a
  ratio of 3.28) and a wrong datum, none of which looks wrong until it is measured;
- a striped (untiled) GeoTIFF is rewritten as a tiled COG in the background before it is moved, because 3D tiles read
  small windows from it.

**DTED2:**
- DTED is EGM96 and the 3D terrain would treat it as NAVD88, which is a metre or two off in CONUS.
- Either refuse new DTED or teach `terrain_tiles` each entry's datum. The second is a change of its own, so for now:
  **refuse new DTED** (DTED already mounted stays as it is).

**LAZ and LAS** (stdlib plus pyproj, in the API):
- the header reads (`collection.read_header`) and the file holds points;
- a horizontal CRS is declared (`collection.read_crs`);
- the bounds, in WGS84, lie inside the geoid grids' box;
- **completeness.** For LAZ, the chunk-table offset at the start of the point data must lie inside the file. For LAS,
  the file must be at least the point data's offset plus the points times the record length. A truncated tile has a
  perfectly good header (`collection.scan` says so), so the header alone proves nothing;
- **the vertical datum.**
  - A declared one goes to PROJ as it is.
  - With none, the existing rule forces NAVD88, and the result says so.
  - Feet are fine here: the unit is part of the CRS and PDAL converts it.
- a file whose survey differs from its folder's (the WKT's name, or a different CRS) is flagged, not refused. Folders
  can hold several surveys (§13).

### Fetching DEMs from USGS (B2, D4)

LiDAR no longer needs downloading for 3D: it streams from AWS. DEMs do: the National Map API lists the 1 m and 1/3″
DEMs for an area (`tools/find_lidar.py` already pages through it for LiDAR), and `lidar/fetch.py` downloads resumably,
checking each file against `Content-Length`. **Fetch DEMs from USGS** on the map: draw an area, see the files and their
size, confirm. The download lands in `.uploads` and goes through the same validation and move as an upload.

**rsync to the VM** remains the way to move terabytes.

---

## 5. Part C: 3D in seconds, streamed from USGS's octree

### What was measured

On 2026-10-22, from this Mac over a home connection, with `GA_Statewide_B3_2018` (north Georgia's good survey):
- **USGS's copy is already an octree by level of detail.** Each piece holds 20–70 thousand points. The finest pieces
  here are 195 m cubes of about 350 KB. The index is split every 8 levels, and the root index file is 680 KB.
- **The whole test LZ came down fast.** All 26 pieces under the Ellijay test LZ (a 782 m cube at full density,
  1.25 million points, 7.2 MB) downloaded 8 at a time in **2.4 s**, 0.5–0.7 s each.
- **Converting them took 0.31 s** on one thread, 13 ms a piece: decoding (`lazrs`), keeping the obstruction classes,
  moving from Web Mercator and NAVD88 heights to Cesium's earth-centred frame, colouring.
- **A build of 1 km² from the same source takes 137 s.** The difference is everything a build does before it shows
  anything: height above ground and Mapbox colour for every point, then py3dtiles sorting the points into a new octree,
  which is the structure USGS already publishes.
- **No survey checked carries colour or states its vertical datum** (`GA_Statewide_B3_2018`, `KY_FullState`,
  `CO_CentralEasternPlains_1_2020`). All are Web Mercator with NAVD88 heights, which builds already assume.

Not measured: the VM's link to AWS, and the tunnel's speed to a viewer. Phase 1 measures both.

### How it works (D20)

1. **`resolve` answers at once.**
   - It picks the survey as builds do (`coverage.best_survey`, newest first; the local index first where it covers the
     target, D24).
   - It answers `available: true` with that survey's stream tileset, plus `frame: {lat, lon, radius_m, height_m}`
     (additive): where the view opens, with the ground's ellipsoidal height from the terrain tiles.
   - An LZ that already has a built tileset keeps it: those have photographic colour.
2. **The tileset is made from USGS's index.**
   - One small JSON for every 4 levels of the octree, joined as external tilesets. Cesium and the Android branch's
     `TilesetReader` both read those.
   - So a view loads a few small files, never the 30,000 entries of a statewide survey.
   - Bounding regions come from each piece's cube, with heights clamped to the survey's `boundsConforming`.
   - Geometric error comes from the piece's spacing: the cube's width over its span.
3. **A piece is fetched from AWS on first request, converted, cached and served.** The conversion keeps today's rules:
   - noise is dropped;
   - the obstruction classes are kept for surveys from 2015 on, the surface classes before (ranked by the year in the
     name, as `coverage.py` does);
   - the geoid separation comes from PROJ at the piece's centre (constant across a 195 m piece to the millimetre). The
     same guard as today refuses to serve when the grids are missing, rather than put the ground 30 m out;
   - written to a temporary name and renamed, then served with private caching, as tiles are now.
4. **Positions are quantized** (`POSITION_QUANTIZED`, 6 bytes, plus 3 for colour): 9 bytes a point instead of 15. Both
   the web's Cesium and the Android branch's `PntsReader` read them.
5. **Names in URLs are opaque.**
   - A survey's key is a keyed hash of its name; a piece's name is a keyed hash of its octree address.
   - The server keeps the table of the names it handed out.
   - The URL keeps today's shape, `/api/lidar/tilesets/<16 hex>/<path>`, which the Android branch's loader expects.
6. **Where it runs (D21): in the API.**
   - A piece is mostly waiting on AWS, and decoding is Rust and numpy, so a first view costs the interpreter about
     0.3 s of CPU.
   - Pieces asked for while an analysis runs wait, as terrain tiles do (`requestPriority` on the client).
   - The new dependencies are `laspy` (BSD) and `lazrs` (MIT and Apache), the decoder, approved with D21.
     py3dtiles' extra brings `laspy` to the build image but no decoder, so `lazrs` would have been new wherever this ran.
7. **The cache.**
   - `LIDAR_STREAM_CACHE_DIR` sits on a persistent, writable mount (e.g. `/data/cache/lidar`).
   - A survey is never republished under the same name, so nothing in it goes stale.
   - A size cap (200 GB by default) drops the least recently viewed pieces, never a warmed area's.
8. **The viewers frame on `frame`** (the opening view and the *LZ* button), not on the tileset's sphere (§2.4). This is
   in `Viewer3D.jsx` and in the Android branch's `SceneSession`. There is no context ring: the octree gives the
   landscape at coarse detail anyway.
9. **One survey per view**, as builds do now: a target near a survey's edge shows that edge. Adding the second-best
   survey as a second tileset is a later step.

AWS sees requests from our server only. The browser never talks to AWS for points, as with builds today.

**What it replaces.**
- On-demand builds and builds on save stop for new views. Existing per-LZ tilesets keep answering where they exist.
- The build service stays only until local LiDAR streams (D24), then it can be retired.
- The first version's region blocks, their assembly and their pre-emption are gone.

**Point colour (D22).** Today's builds colour every point from Mapbox imagery, which is most of what makes them slow,
and USGS's copy has no colour of its own.
- **Decided (D22):** colour every streamed piece at once by **height above ground**, using today's bands: surface, grass,
  brush, canopy, and above 15 m in orange. It is worked out from the piece's own ground points, as builds do
  (`filters.hag_nn`), and it is the obstruction reading the build's own code calls the more direct one for an LZ.
- **Changed 2026-10-26 (the owner):** the bands came out orange over nearly every crown at Ellijay (north Georgia's
  hardwoods stand over 15 m), so a streamed piece is now coloured as the place looks: the ground tan and brown, greens
  darkening with height above ground, shaded by LiDAR intensity within the survey's range (`pieces.NATURAL_STOPS`,
  `survey_intensity_range`). Height still decides the green, so how tall the obstructions stand still reads. Then a
  background pass is to recolour cached pieces from imagery (*Photographic colour, in the background*, below: checked, not built).
- **Photographic colour** is added only where an area is warmed (below). One imagery request per piece would about
  double a first view.
- It comes from USGS NAIP (public domain, 0.6 m, CONUS), not Mapbox: storing Mapbox colour in data that is then sold
  is a terms question (licence language commonly bars data derived from tiles).

### Photographic colour, in the background (the owner's request of 2026-10-26; built the same day on USGS's service)

Each cached piece recoloured from NAIP off the request path, so a later view of the same ground is photographic and the first
view never waits for it. **Checked 2026-10-26, before building; the source and the licence are the owner's to settle:**

| Source | Access | Terms as published | Measured from the owner's Mac |
|---|---|---|---|
| **USGS The National Map, `USGSNAIPImagery` ImageServer** (`exportImage`) | Free, keyless, HTTPS; up to 4,000 × 4,000 px a request, any box in EPSG:3857 | Credited "USGS, USDA, The National Map: Orthoimagery"; its metadata's use and licence fields are empty; no published rate limits | A piece's box (195 m at 0.6 m, 325 px) 0.3–7 s, median about 0.9 s, about 23 KB of JPEG; a 782 m box 2.2 s, 360 KB. At Ellijay it served `m_3408431_ne_16_060_20231008`: 0.6 m, October 2023, leaf-on |
| **AWS Open Data, `naip-visualization`** (3-band COG, JPEG q85) | **Requester Pays**: an AWS account, a key on the server (a secret), and billing | "Public Domain with Attribution" (cite "NAIP on AWS … registry.opendata.aws/naip"); 2010–2023 | Not tried (anonymous access is refused). Pennies: AWS egress is about $0.09 a GB, and a piece's window is tens of KB |
| Microsoft Planetary Computer | Azure (West Europe), SAS token | Its catalogue calls the licence "proprietary" and links to USDA FSA's policies | Not tried; not recommended |

**The licence.** NAIP is USDA FSA's. It has been public domain, and USGS publishes its 2012–2015 NAIP under the USA.gov public
domain label. In 2018 FSA weighed licensing it commercially, and the program was said to stay public domain for imagery taken
through 2019. USGS and AWS both serve 2020–2023 imagery openly (AWS as "Public Domain with Attribution"), and nothing found says a
licence was adopted, but FSA's own current statement for post-2019 imagery was not found. **Using it in the app, with credit, looks
clear; selling photographic colour (D23) is worth confirming with USDA's APFO (the office that runs NAIP) first.**

**What one piece costs**: one image fetch for its box (above) and 40–60 ms of CPU to turn its quantized positions back into Web
Mercator, sample the image bilinearly and write the piece again (measured on five cached pieces of 74–88 thousand points).
A coarse piece needs no 0.6 m: its image would be sized to its own spacing (a 3 km piece at about 6 m).

**The owner chose USGS's service** (2026-10-26; it held the newest imagery anyway: Georgia's is 2021–2023, nothing anywhere for 2024
or 2025 yet). **Built** as below (`lidar/photo.py`), except that a failed fetch is tried again after 10 minutes, and pieces are taken in
the order they were cached (coarse first, then the LZ's own) rather than nearest the LZ. **Measured** at Ellijay: a cold first view with
the pass running was as fast as without it (first points 1.5 s, the LZ's square 11.4 s, against 1.4 s and 10.0 s); the pass made 164
of the 168 pieces photographic in about 8.6 minutes (median 3.3 s an image, the slowest 47 s, 4 failures left in the palette until
tried again).

**The design**:
- A queue and two worker threads of its own in the API, apart from gunicorn's 8 request threads, so a view never waits on it;
  a piece is queued as it is first cached or served still in its palette, at most 5,000 waiting, and skipped when the cache has
  let it go. Nothing is fetched while an analysis runs (`terrain_tiles.pauses_warming`).
- The recoloured piece replaces the palette one atomically under the same name. **Caching**: a palette piece is served for an
  hour (`max-age=3600`), a photographic one for the week, so a browser or the app picks the photograph up on a later view without
  a new look or key. The mark is `<name>.photo` beside the piece, let go with it.
- The imagery's own vintage (2023, leaf-on) is not the LiDAR's (2018): a tree cut since shows as ground colour on the canopy's
  points and the reverse. Credited in the web's 3D view ("Point colour: USDA NAIP, from USGS The National Map"); Android's is to be.

### Warmed areas: what "prebuilt" becomes

Warming an area fetches and converts every piece over it to the finest level, and warms the terrain tiles over it to
level 16 (D18). An area so warmed:
- opens instantly;
- does not depend on AWS being up;
- carries photographic colour where that is paid for (D23);
- can include the organization's own uploaded or newer LiDAR (D24);
- later, can ship to the apps for offline use (NATIVE_APPS_PLAN's map packs).

`terrain_tiles.warm_area(bounds, max_level)` warms levels 10–16 over an area (0–9 are warmed already). It runs in the
API in the background and pauses for analyses, as warming does now. It needs §2.2 first, or the next DEM upload
anywhere throws it away.

**Estimates**, from the measurement. This survey holds about 2 points per m²; others reach 3.5, so scale up to 1.75
times.

| Area | From AWS | Download time | Cached | Colour (NAIP, warmed) | Terrain to level 16 |
|---|---|---|---|---|---|
| 1 km² | 12 MB | about 4 s | 15 MB | about 45 requests | about 18 tiles |
| 10 × 10 km | 1.2 GB | about 7 min | 1.5 GB | about 15 min | 1,750 tiles, 1.5 min, 15 MB |
| 20 × 20 km (an installation's training area) | 4.7 GB | about 26 min | 6 GB | about an hour | 7,000 tiles, 6 min, 60 MB |

Download times are at the measured 3 MB/s with 8 pieces at a time; more at once is faster, up to the VM's link.
The Data tab works them out for each area before it starts: it counts the pieces from USGS's index, uses each survey's
density, and corrects itself from what it has warmed. Warming against the first version's building, for 20 × 20 km:
about half an hour instead of about 15 hours.

**The admin's controls.**
- Draw a rectangle or polygon, or pick an organization's AO, then see the estimate and confirm (D19).
- Progress shows pieces fetched out of all, colour and terrain.
- **Pause** and **remove** are offered (removing unpins the pieces, and the cache's cap lets them go).
- Area names and outlines live in the database only, never on the volume or in a log.

### Local LiDAR (D24)

Plain LAZ tiles have no levels of detail, so they cannot stream as they are.
- **Decided (D24):** index the downloaded collection once with Entwine (`entwine build`, the tool USGS used; hours, in
  a Docker image of its own) into a local EPT in `/data/lidar/ept/`. It then streams exactly like AWS, from files
  instead of URLs.
- Uploaded LAZ is added to that index after validation (Entwine continues a build with new files).
- A target the local index covers streams from it first, as builds prefer the collection today; AWS otherwise.

### For the Android 3D branch

The Android 3D view (branch `claude/android-3d-view-e38b76`, not merged) reads tilesets as the web does, so streaming
reaches it with four things to keep true:
- **A streamed tileset is a whole survey's octree**, joined as external tilesets every 4 levels. Its `TilesetReader`
  already reads external tilesets.
- **URLs keep the shape its loader parses** (`TilesetAddress`): `/lidar/tilesets/<16 hex>/<path>`, with the survey's
  key and each piece's name opaque.
- **It must frame on `resolve`'s `frame`** for the opening view and the *LZ* button (`SceneSession`,
  `CameraPose.framing`), not on the root's sphere, or the view opens on the whole state.
- **Pieces come with quantized positions** (`POSITION_QUANTIZED`), which its `PntsReader` reads. A paid piece the
  account may not have answers `403 not_entitled`, and `resolve` never hands out such a URL.

---

## 6. Part D: releasing it

### Accounts

- **Individual accounts** stay as they are: Google or email, and the `.mil` gate or an admin's approval.
- **An organization is not a login.** It is a group of individual accounts, made by the super-admin, with a plan,
  seats, members and its AOs (D13: one or more). Nobody shares an account.

**Organizations and Teams (D11): kept apart.**
- Teams are made by users, for mission packs and finding people.
- An organization is made by the admin, for entitlements, billing and its AOs.
- Linking them (an org team that follows the org's members) would mean managed teams that members cannot leave or
  change. That changes `team_routes` and the Android Teams screens, which are contracts now.
- An org's admin can make an ordinary team whenever they want one. Linking can come later if units ask for it.

**One organization per person** (D12). An aviator in two units at once is rare. Leaving one (a PCS) and joining
another is a change of membership, and whose AOs a person's access comes from stays unambiguous.

### What is paid, now that 3D is fast for everyone (D23)

Streaming gives everyone 3D in seconds, so speed alone stops being the upgrade. Decided (D23):
- **Free:** streamed 3D anywhere USGS has LiDAR, at full density, coloured by height above ground.
- **Paid, in warmed areas:** photographic colour, the organization's own or newer LiDAR, and offline 3D in the apps
  later. For a unit, its AOs. For a person, a catalogue of training areas the owner warms (D14).

Warming also makes the free version of an area instant and independent of AWS, for everyone who views it. That is not
held back: refusing cached data to free accounts would only make 3D worse for them.

The alternative lever is density: free accounts would stream only to about 3 m spacing (the server refuses finer
pieces), paid to the leaves. It is the simplest to enforce, but it holds back obstruction detail from crews who do not
pay, which sits badly in a safety tool.

### Data model (new tables, `db.create_all()` makes them; nothing changes on existing tables)

| Table | Columns |
|---|---|
| `organization` | `id`, `name` (100), `status` (active, suspended), `plan`, `seats`, `plan_started_at`, `plan_ends_at`, `comped`, `billing_ref` (a provider's customer id, null until there is a provider), `notes` (admin only), `created_at`, `updated_at` |
| `organization_member` | `id`, `org_id` (cascade), `user_id` (cascade, **unique**: one org each), `role` (owner, admin, member), `added_by`, `added_at` |
| `area_of_operation` | `id`, `org_id` (cascade; several per org, D13), `name` (each AO named), `geometry` (GeoJSON Polygon or MultiPolygon, WGS84), `bbox`, `area_km2`, `updated_by`, `updated_at` |
| `warmed_area` | `id`, `name`, `geometry`, `bbox`, `area_km2`, `audience` (admins, catalogue, org), `org_id` (null unless audience is org), `status`, `pieces_total`, `pieces_done`, `colour_done`, `terrain_level`, `terrain_done`, `bytes`, `created_by`, `created_at`, `warmed_at`, `removed_at` |
| `user_plan` | `user_id` (unique), `plan`, `status`, `ends_at`, `comped`, `provider`, `provider_ref`, `updated_at`: a person's own plan, apart from any org |

**Deleting an account:**
- removes its memberships;
- an org's last owner cannot be deleted until an admin names another owner, as `pack_support.release_account` does for
  packs. Both deletion paths call it.

### The AOs, and where access is enforced (D13)

An organization has **one or more AOs** (D13, decided: several, where the recommendation was one). Each is a named
polygon (or multipolygon), set by the super-admin in the org's page:
- drawn on the map, or loaded from a GeoJSON file;
- the plan caps their total area, and so how much is warmed;
- org admins can see them but not change them (v1).

AO outlines are training areas. A unit's AO for a real operation would be the kind of aggregation the "unclassified
only" rule forbids, so the org page says so. The outline is shown only to the super-admin and that org's admins, and is
never logged.

**Who gets a warmed area's paid version of a piece:**
- admins: everywhere warmed;
- a person with a plan that includes it: pieces inside a `catalogue` area;
- an org member with a plan that includes it: pieces inside any of their org's AOs;
- union of the above.

Everyone else gets the free version of the same piece.

Enforced in `lidar_access.py`, one module, each case with a test:

| Where | Check |
|---|---|
| `POST /api/lidar/resolve` | Returns the tileset of the version the caller may have at that target: paid inside one of their AOs or a catalogue area, free elsewhere. |
| `GET /api/lidar/tilesets/<key>/<path>` | A paid piece goes only to those who may have it. Others get `403 not_entitled`, which never happens from a URL `resolve` gave them. Free pieces go to any signed-in user. |
| The org's own LiDAR (D24) | Served only inside its AOs, to its members. |

**Speed.** Every piece request is checked. So:
- a person's access (admin, plans, org, the org's AOs prepared for point-in-polygon) is held in memory for 60 s;
- the warmed areas' index is reloaded when its table changes;
- the API is one process (§9), so the caches agree;
- point-in-polygon uses matplotlib's `Path`, already a dependency.

### Entitlements and plans

- A new feature key, `prebuilt_3d`, "Prebuilt 3D areas" (the warmed areas' paid version).
- It is neither on by default nor `DEFAULT_OFF` (which means "not launched; becomes everyone's at launch"). It is a new
  kind, **`PAID`: on only when a plan grants it or an admin ticks it** (a tick is how testers and comped accounts get
  it).
- `resolve_features` becomes: what is stored or the default, as today, plus the features of the person's own plan and
  their org's plan, if active and in date. The user page in the admin shows where each feature comes from.
- `GET /api/auth/me` gains `plan: {name, source: org|personal, ends_at}` and `org: {name, role}`. These are additive
  (the apps' rule), and the apps hide what the plan does not include.

Plans (names and prices are the owner's; D14, D23):

| Plan | Who | Adds |
|---|---|---|
| Free | everyone | Everything there is now, and streamed 3D in seconds wherever USGS has LiDAR. |
| Pro | a person | Photographic colour in the warmed catalogue areas. |
| Organization | a unit, by seats | Its AOs warmed, with photographic colour and its own LiDAR; offline 3D for them later. A total AO area in km² is included. |

During the beta, before anyone pays, the paid version of warmed areas is for admins and the testers an admin ticks, as
`mission_packs` is now (D9, decided), so nobody loses something they had when plans start.

Streaming itself is for everyone from the day it ships.

### Billing (D15)

Any payment provider is a new dependency. Options:

| Option | Fits | Notes |
|---|---|---|
| **Manual first** (no provider) | The beta, and units that pay by invoice | The admin sets a plan, its end date and "comped"; payment is arranged outside the app. Nothing to integrate; it tests demand. **Decided to start (D15).** |
| Stripe (Checkout, Customer Portal, webhooks) | Individuals by card; units by Government Purchase Card or by Stripe invoice | `stripe` on the server only. Secret key and webhook secret stay server-side. Sales tax is the owner's to register and file, or Stripe Tax for a fee. |
| A merchant of record (Paddle, Lemon Squeezy) | A sole developer who does not want to handle sales tax | It is the seller and handles tax. Its fees are higher, and invoicing a government unit is less usual. |

Two things that hold whichever is chosen:
- **Sell on the web only.** The App Store and Google Play have their own rules for selling subscriptions inside an app,
  and they have been changing. The apps show what a plan includes and do not sell it. Check the stores' current rules
  before launch.
- **Selling to units** may need a SAM.gov registration (a UEI) and invoices, for purchases above the micro-purchase
  threshold. Worth asking a unit's resource manager before pricing.

### Admin screens

- **Orgs** tab:
  - the list: name, plan and end date, seats used out of seats, its AOs and their total km², their warmed areas' state;
  - **an org's page:**
    - members: add by email, set the role, remove; a person is in one org at most;
    - plan: plan, seats, dates, comped, the billing reference, notes;
    - **its AOs**: each named, drawn or loaded on the map, with its area in km² and the estimate of warming it;
    - their warmed areas, with "warm this AO" on each (Part C's estimate and confirm, D19);
    - a history of every change, with who made it.
- **User page:** their org and role, their own plan, and where each feature comes from.
- **Data tab** (Parts A–C): warmed areas show their audience. A catalogue area can be marked `catalogue`.

### Phased rollout

Each phase is one PR or more, with its tests and its AGENTS.md update in the same commit (Conventional Commits). The
owner runs every deploy and every Coolify change.

| Phase | Builds | Owner steps |
|---|---|---|
| **0. Close the exposure** (small, on its own). **Built 2026-10-26.** | §2.1: tile files only, the list admin-only, HMAC for any new key. AGENTS.md §13 and §15. Old tilesets keep their keys (the owner's call). | Set `LIDAR_KEY_SECRET` (`openssl rand -hex 32`) on the API and the build service, then deploy both. |
| **1. Streamed 3D** | Part C's streaming: the tileset from USGS's index, pieces converted and cached, opaque names, quantized positions, height colour, the `frame` hint in the web's viewer. Builds stop for new views. Tests hold the conversion to PROJ with grids, the geoid guard, the class rules by survey year and the names. | Set `LIDAR_STREAM_CACHE_DIR` on a persistent, writable mount, then deploy. Open 3D somewhere new and time it; the page logs its first picture's time. |
| **2. Terrain cache per tile** | §2.2 (the key per tile, a sweep for orphans), `warm_area`. | None. The first start after it rewarms levels 0–9 once (about 1 minute). |
| **3. The coverage map** (read only) | §2.3 (the index written by rename, the scan in subfolders), §2.5, MapLibre GL JS vendored, `/admin/data` with its layers and numbers, `DATA_ADMIN`. | Mount `/data/lidar` (read only for now) into the API at the same path. Set `LIDAR_COLLECTION=/data/lidar` and `DATA_ADMIN=read` on the API. |
| **4. Uploads** | Part B: chunked uploads, validation, the move into place, refreshes, the sweep. Fetching DEMs from USGS (D4). | Mount `/data/topo` and `/data/lidar` writable into the production API. Set `DATA_ADMIN=write` there only. Upload one file over 100 MB to confirm the tunnel takes 64 MB chunks. |
| **5. Warmed areas** | Part C's warming: areas drawn and estimated, pieces and terrain warmed, NAIP colour, progress, pinning in the cache. For admins and ticked testers. | Warm one 10 × 10 km area and compare the estimate with what it took. |
| **6. Local LiDAR streams** (D24) | The Entwine index of `/data/lidar`, uploads added to it, local first in `resolve`. The build service retired after. | Run the one-off Entwine build on the VM (commands supplied). |
| **7. Organizations and AOs** | Part D's tables, admin screens, `PAID` and `prebuilt_3d`, the access module, `/api/auth/me`'s additions. | Deploys only. |
| **8. Plans and billing** | Manual plans first. A provider only if the owner approves one (a new dependency; asked then). | The provider's account and keys, if one is chosen. |
| **9. Launch** | Catalogue areas warmed, org onboarding, the user guide. | Pricing, the terms of sale, the stores' rules. |

What the Android branch's 3D view needs when it is merged is in Part C, *For the Android 3D branch*.

### Phase 1 as built (2026-10-26)

**Built** (backend and web): `lidar/ept.py` (USGS's index read as 3D Tiles), `lidar/names.py` (opaque names), `lidar/pieces.py`
(a LAZ piece as `.pnts`), `lidar/stream.py` (the streamer and its cache), `resolve`'s `stream` and `frame` (API 1.1.0, additive),
the web asking for them and framing on `frame`, and builds on save skipped where a place streams. Tests build a small octree
themselves; `EZPZ_LIVE_AWS=1` streams the Ellijay LZ from the real survey.

**Not like the plan above**, on purpose:
- **A piece's name is its address through a keyed permutation**, not a keyed hash with a table of the names handed out: equally
  opaque without the secret, and the server reads the address back with nothing to keep or lose across restarts.
- **Streamed only to a client that asks** (`stream: true`; the owner's call, 2026-10-26), so an installed app that frames on the
  root sphere keeps the build flow rather than opening on the state.
- **AWS reads are tried three times over pooled connections**: Cesium never asks again for a tile that failed, and pooling halved a
  first view (a TLS handshake a piece).
- `frame.height_m` is the 3D terrain's DEM where it reaches, else Terrarium plus the geoid, else null (the viewer then falls back
  to the tileset's sphere rather than open on a height made up).

**Measured** (the owner's Mac, home connection, cold cache, Ellijay test LZ, GA_Statewide_B3_2018): first points 1.4–2.9 s; the
LZ's own 1 km square at every depth (168 pieces, 52 MB) in 10–13 s; the whole opening view at error 2 on a 1600 × 900 view (370
pieces, 147 MB) in 18–21 s; a build of the same LZ 137 s. Warm, it all comes off the disk at once.

**Left, in order:**
1. ~~Android: send `stream`, frame on `frame` (`SceneSession`, *LZ*), held to `contracts/fixtures/viewer3d/lidarFlowStream.json`.~~
   Built the same day (`feat/android-lidar-streaming`); a device run is the owner's.
2. The owner: set `LIDAR_STREAM_CACHE_DIR` on staging, open 3D somewhere new and time it (the VM's link to AWS is unmeasured).
3. Weigh the landscape's cost (147 MB for a wide view; §15 of AGENTS.md): a coarser error far off, Cesium's dynamic
   screen-space error, or more API threads, before phones stream.
4. Pieces held back while an analysis runs, as terrain tiles are (not done: AWS does the waiting).

---

## 7. What this does not do

- No streaming or warming outside the geoid grids' box (CONUS) until both images bake more grids.
- No per-org copies of USGS data. A warmed area that touches two AOs serves both orgs.
- No offline packs yet. They are `NATIVE_APPS_PLAN`'s, and warmed areas can feed their 3D layer later.
- No change to slope analysis's sources, or to Terrarium as the planner's terrain.

## 8. Not verifiable from an agent session

- The VM's link to AWS and the tunnel's speed to a viewer (measured here from a Mac on a home connection).
- How many pieces Cesium asks for in a typical first view of a statewide tileset.
- The tunnel's real limit with 64 MB chunks, and upload speed from the owner's network.
- Disk free on the VM.
- NAIP's colour on a point cloud, and how height colour reads to a crew.
- How streamed 3D looks in Cesium and on Android. The browser preview cannot composite WebGL (AGENTS.md §14).

Each has a step above where the owner checks it.

---

## 9. Decisions

**Decided by the owner on 2026-10-23**, on the decisions page (`https://claude.ai/artifact/RU8RPViDvj2qjpP7UGLT4R`,
which keeps each answer): all 20 answered, with no notes, so **none is open**. Eighteen follow the recommendation. Two
do not, and the parts above follow them:
- **D1:** MapLibre GL JS for the admin map, not Leaflet (Part A).
- **D13:** several AOs per organization, not one, still set by the super-admin (Part D's data model, access and screens).

Some choices carry an approval the house rules ask for: D21 approves `laspy` and `lazrs` in the API, D24 approves
Entwine (a Docker image run on the VM) for local LiDAR, and D1 approves MapLibre GL JS, copied into the backend. D15
leaves any payment provider to be asked about at Phase 8.

Reworking the plan around streaming retired D6, D7, D8 and D10 (region blocks, their colour, their scheduling, access
inside a region) before any were answered.

Still the owner's, outside these decisions: prices and plan names, the terms of sale, the app stores' current rules
before launch, a SAM.gov registration for selling to units, and the measurements only the VM can give (§8).

| # | Decision | Decided 2026-10-23 | Recommended | Other choices |
|---|---|---|---|---|
| D17 | Phase 0 first, on its own | As recommended | Yes, before anything else | With the streaming change |
| D20 | Stream 3D from USGS's octree | As recommended | Yes: every new view streams, existing per-LZ tilesets still answer, builds stop and the build service retires once local LiDAR streams | Stream, but keep builds as a fallback when AWS is down; or keep building, made faster (still tens of seconds) |
| D21 | Where streaming runs | As recommended | In the API, with `laspy` and `lazrs` (a small LAZ decoder written in Rust): new dependencies | In the build service, which the API asks for missing pieces (`lazrs` added there; an extra hop, and 3D then needs that service up) |
| D22 | Colour of streamed points | As recommended | Height above ground at once; photographic colour (NAIP) only in warmed areas | By class at once; or photographic for every piece as it streams (first views about twice as slow) |
| D23 | The paid 3D upgrade | As recommended | In warmed areas: photographic colour, the org's own LiDAR, offline later (warming makes an area instant for everyone) | Full density only for paid (free capped at about 3 m spacing); or no 3D upgrade, sell organization features only |
| D24 | Local LiDAR for streaming | As recommended | Index the downloaded collection once with Entwine, and add uploads to that index | AWS only (local LiDAR not used for 3D); or COPC per file (each file its own octree, seams between files) |
| D1 | Map library for the admin | **MapLibre GL JS** | Leaflet 1.9.4, the web's version, copied into `backend/static`; hand-written drawing tools; USGS National Map base | Leaflet from a CDN; MapLibre GL; Mapbox base (token restrictions) |
| D2 | How the API reaches `/data/lidar` | As recommended | Mount it into the API at the same path (read only for the map, writable for uploads, production only) | A summary endpoint and an upload relay on the build service |
| D16 | Streamed views and old per-LZ tilesets on the admin map | As recommended | Counts per 10 km cell | Exact places |
| D3 | Upload protocol | As recommended | Chunked and resumable, written here, no library | tus (`tus-js-client` and a server for it) |
| D4 | The server fetching DEMs from USGS | As recommended | Yes (LiDAR streams from AWS, so only DEMs need it) | Uploads and rsync only |
| D5 | DEM datum policy | As recommended | NAVD88 only (stated or ticked), inside the geoid grids, checked against Terrarium; new DTED refused | Accept other datums and convert them per DEM in `terrain_tiles` (a change of its own) |
| D18 | Terrain warming depth in warmed areas | As recommended | Level 16 | 17 (four times the tiles); 18 |
| D19 | Warming an AO when it is set | As recommended | Only after the admin confirms the estimate (about half an hour and 6 GB for 20 × 20 km) | Automatically |
| D9 | The paid version of warmed areas during the beta | As recommended | Admins and ticked testers | Everyone as a preview |
| D11 | Organizations and Teams | As recommended | Apart | An org team that follows the org |
| D12 | Organizations per person | As recommended | One | Several |
| D13 | AOs and access | **Several AOs per org**, set by the super-admin | One polygon per org, set by the super-admin; members get the paid version of pieces inside it | Several AOs per org; org admins editing their AO |
| D14 | What individuals buy | As recommended | Pro: warmed catalogue areas with photographic colour | A small AO of their own; no individual plan |
| D15 | Billing | As recommended | Manual plans first; ask about a provider at Phase 8 | Stripe now; a merchant of record |

---

## 10. The Data section, planned again (2026-11-04)

The owner's request, in their words: *"a section in the admin that allows you to view a map that overlays with the blocks of
USGS data that are available on the server. It will also allow me to upload or connect to USGS if possible to download data
into the volume."* This section replaces Parts A and B (§3, §4) for that request. Where it disagrees with them, it wins. The
owner's open questions are in §10.6. **Nothing here is built yet.**

### 10.1 The plan against the code, 2026-11-04 (develop at `67974631`, the same backend as release 1.8.0)

**Done since the plan was written.**
- §2.1, the tileset exposure (Phase 0):
  - the file route serves only tile files, never `area.json` and nothing under a dot (`lidar/catalog.is_served_file`);
  - `GET /api/lidar/tilesets` is for admins only;
  - new keys are an HMAC under `LIDAR_KEY_SECRET`.
- Part C, streaming (Phase 1), on the web and on Android:
  - `resolve` takes `stream` and answers with `frame` (§2.4);
  - the natural palette, and the NAIP photographic pass in the background (`lidar/photo.py`).
- LZ surveys read USGS's own LAZ for obstacles. They come from the stream cache (`Streamer.laz`), not from `/data/lidar`.

**Not done.**
- §2.2: one fingerprint still names *every* cached terrain tile (`terrain_tiles._fingerprint`: every DEM's path, size and mtime,
  plus the geoid). Two things follow from that:
  - Adding one DEM anywhere moves the whole cache to a new generation directory.
  - Old generations are **never removed**: only `*.tmp` leftovers are cleared.
- §2.3:
  - `collection.save_index` is a plain `write_text`;
  - `collection.scan` globs the top folder only.
- §2.5: `find_covering` still reads every `area.json` on each call.
- Phases 2 to 9.
- Nothing in the backend checks free disk space. `lidar/fetch.py` does not, and neither does the stream cache, whose only limit
  is its own cap (`LIDAR_STREAM_CACHE_MAX_GB`, 200 by default) with no look at what is left on the volume.

**Gone stale, now that streaming exists or the code has moved.**

1. **"Only DEMs need downloading" (D4).** The owner now asks for LiDAR downloads too. LAZ in `/data/lidar` feeds only two
   things today:
   - the build service's collection, and the build service builds only where nothing streams;
   - D24's local Entwine index, which is not built.

   Streaming and surveys read USGS's copy on AWS. So a LAZ download buys little until Phase 6. See Q3.
2. **The downloaded LiDAR layer reads `tile_index.json`**, which only the build service can see: the API mounts no
   `/data/lidar` and `LIDAR_COLLECTION` is read only by `worker.py`. The scan also misses a survey kept in a folder of its own
   (§2.3).
3. **The "streamed views" layer has no index to read.** The cache is files under `<LIDAR_STREAM_CACHE_DIR>/<survey key>/`, as
   below.

   | File | Name |
   |---|---|
   | `.pnts` pieces | opaque: a keyed Feistel permutation of the node |
   | kept LAZ | `laz/D-X-Y-Z.laz` |
   | hierarchy pages | `h/D-X-Y-Z.json` |

   Places come back from these with the secret (`names.piece_node`, `ept.Info.cube`). The cheap way is the hierarchy pages
   and the kept LAZ, whose names are in clear. It takes a walk of the folder, so it is done once and kept for 10 minutes.
4. **The DEM datum check would refuse every USGS DEM.** Checked 2026-11-04 by opening two of USGS's own files over HTTP with
   `GTIFF_REPORT_COMPD_CS=YES`:

   | File | CRS | Vertical part |
   |---|---|---|
   | `USGS_13_n35w085.tif` | EPSG:4269 | none |
   | `USGS_1M_16_x72y385_GA_Statewide_2018_B18_DRRA.tif` | EPSG:26916 | none |

   §4's rule ("a compound CRS must have NAVD88 as its vertical part") therefore needs a provenance path. A file fetched from
   TNM's 3DEP DEM products is NAVD88 in CONUS **by what it is**, and the server writes `VERT_DATUM=NAVD88` into a `.aux.xml`
   beside it.
   - GDAL folds a PAM `.aux.xml` into `tags()`, and the catalog reads that tag. Tried with rasterio on a test file.
   - An *upload* with no vertical part still needs the admin's tick.
   - The DEMs on the VM today almost certainly carry no tag either, so the catalog labels them "source metadata required". The
     3D terrain treats them as NAVD88 anyway, since `terrain_tiles` never reads the label. See Q5.
5. **The catalog walks every folder under `TERRAIN_DATA_DIR`** (`os.walk`, recursive) and opens every `.tif` it finds. So a
   download or upload staged inside `/data/topo` under its real name would be:
   - opened half-written;
   - folded into the tile fingerprint.

   Staging must use a `.part` suffix, which `_entry_kind` ignores, and the walk should skip dot folders.
6. **TNM lists every dated version of a 1/3″ cell.** For `n35w085` it lists six:
   - 2011-04-01, 2022-05-12, 2022-07-25, 2023-02-15 and 2026-04-29 (all about 475–481 MB);
   - and an item titled "USGS 13 arc-second n35w085 1 x 1 degree" of 465 KB, pointing at a 2017 file.

   All of them sit under `historical/`. The `current/USGS_13_n35w085.tif` URL is not listed, though it exists. Its size
   (481,116,556 bytes) and date (Last-Modified 2026-04-29) match the newest dated copy.

   So a search must keep **the newest version of each cell**, and must detect a cell already on the server under any name, by
   its bounds. Two overlapping DEMs both enter the mosaic.
7. **D1 (MapLibre GL JS) and Leaflet.** The brief for this work asked for "what the web already has (Leaflet)"; D1 chose
   MapLibre. See Q1.
8. **What a new DEM does to the 3D terrain cache, today.**
   - The next catalog scan comes up to 5 minutes later (`TERRAIN_CATALOG_REFRESH_SECONDS`), and there is no way to ask for one
     sooner.
   - That scan changes the fingerprint, so **every** cached tile is in the old generation and is computed again on first ask.
     That is about 50 ms a tile from level 9 down, and 0.3–3.3 s for levels 0–8.
   - Warming runs only at startup, so the coarse levels stay cold until the next deploy.
   - The old generation stays on disk for good.

   None of it is wrong (no stale heights are ever served), but each download costs a cold 3D everywhere and leaves its litter.
   See Q7.
9. **No `MAX_CONTENT_LENGTH`, no CSP on `/admin`, and no static folder in the backend.**
   - The size-guard precedent is `survey_routes`: check `request.content_length`, set `request.max_content_length`, catch
     `RequestEntityTooLarge`.
   - The background-job precedent is `survey/book.py`'s `Printer`: a daemon thread, a database row holding state and progress,
     and re-running what a restart left half done.
   - CSRF for `fetch` is the `X-CSRF` header, as `login.html` sends it.

### 10.2 What TNM Access offers (tried 2026-11-04)

Keyless, HTTPS, JSON, answered in under 2 s.

- `GET https://tnmaccess.nationalmap.gov/api/v1/datasets` lists the products. The ones that matter here:

  | `datasets=` value | Product |
  |---|---|
  | `National Elevation Dataset (NED) 1/3 arc-second` | 1/3″ DEM, 1° × 1° cells |
  | `Digital Elevation Model (DEM) 1 meter` | 1 m DEM, 10 × 10 km tiles in UTM, by project |
  | `Lidar Point Cloud (LPC)` | LAZ tiles, by project |

  `1/9 arc-second` returned nothing over Ellijay.
- `GET …/api/v1/products?datasets=<name>&bbox=w,s,e,n&max=<n>&offset=<n>&outputFormat=JSON` also takes `polygon=` and
  `prodFormats=`.
  - It answers `{total, items, errors, messages}`.
  - Each item carries `title`, `format`, `sizeInBytes`, `downloadURL`, `boundingBox {minX, maxX, minY, maxY}` in degrees,
    `publicationDate`, `lastUpdated` and `extent`.
- Measured over Ellijay and its 1° cell:

  | Product | Area | Items | Size | Host |
  |---|---|---|---|---|
  | 1/3″ | `n35w085` | 6 | about 480 MB each | `prd-tnm.s3.amazonaws.com` |
  | 1 m | 1° cell | 169 tiles | about 345 MB each (**about 58 GB**) | `prd-tnm.s3.amazonaws.com` |
  | LPC | a small box | 66 tiles | 13–20 MB each | `rockyweb.usgs.gov` |
- Each host answers a HEAD with `Content-Length` and `Accept-Ranges: bytes`, so a download can resume with `Range`.
- USGS's GeoTIFFs are already tiled (512 × 512) LZW COGs with overviews, float32, nodata −999999. No rewrite is needed.
- The National Map's raster base maps answer keyless, as `…/arcgis/rest/services/{USGSTopo, USGSImageryOnly,
  USGSShadedReliefOnly}/MapServer/tile/{z}/{y}/{x}` on `basemap.nationalmap.gov`.

### 10.3 The page

A **Data** tab (`/admin/data`) beside Users, Aircraft, Packs and Teams. It is behind `admin_required`, with a CSRF check on every
write and `Cache-Control: private, no-store` on every JSON answer. `DATA_ADMIN` (`off` | `read` | `write`, default `off`)
decides what it offers:
- `read` shows the map;
- `write` adds downloads and uploads.

Only the production API gets `write`, because staging mounts the same volumes. The Fly backend says it holds no data. Nothing
here is reachable with a JWT, so no app and no non-admin ever sees a place (AGENTS.md §15).

**The map** (library: Q1). Vendored with its CSS into `backend/static/vendor/`, one exact version, with no CDN and no build step.
The base map is the USGS National Map (topo, imagery, shaded relief).

| Layer | From | Drawn as; clicked shows |
|---|---|---|
| DEMs (on) | `LOCAL_CATALOG.entries()` | Footprints coloured by resolution (1 m, about 3 m, 1/3″, coarser), and hatched where the datum is not NAVD88 or not stated. A click shows the file (relative to its root), kind, resolution, datum, size and date, whether slope analysis uses it (15 m or finer), and the other DEMs it overlaps |
| Downloaded LiDAR (on, when mounted) | `tile_index.json` (Q10), bounds to WGS84 with pyproj, once per CRS, kept until the index changes | Tiles coloured by survey year (2015 on classifies vegetation), grouped by survey (the folder, else the project in a USGS file name). Above 20,000 tiles they are shown as 0.05° cells until zoomed in |
| Streamed cache (off) | the walk in 10.1 (3), kept 10 minutes | **Counts per 10 km cell** (D16), never pieces |
| Built tilesets (off) | `area.json` of each | Counts per 10 km cell (D16) |
| AWS 3DEP coverage (off) | the coverage index the streamer keeps | Faint survey footprints by year: where streaming has data |
| A USGS search (while one is open) | §10.4 | Each file's footprint: on the server already, new, or too big |

**The panel.**
- For each place data lives, its total, used and free space and the reserve (§10.4), from `shutil.disk_usage`:
  - `TERRAIN_DATA_DIR`'s roots;
  - `/data/lidar` if mounted;
  - `LIDAR_STREAM_CACHE_DIR` (against its cap);
  - `LIDAR_TILES_DIR`;
  - `TERRAIN_TILE_CACHE_DIR` (with its generations and their size).
- **A warning in red when a place is on the container's own disk** (§10.4's mount check).
- The jobs: running, queued, the last ten.

**Endpoints** (admin session, JSON):
- `GET /admin/data`, the page;
- `GET /admin/data/coverage.json?layer=…&bbox=…`;
- `GET /admin/data/volumes.json`;
- `POST /admin/data/usgs/search`;
- `POST /admin/data/jobs`, `GET /admin/data/jobs/<id>.json`, `POST /admin/data/jobs/<id>/cancel`;
- the upload routes of §4, for Phase U.

### 10.4 Adding data: the rules every way in shares

**The disk** (a module of its own, `data_volumes.py`, used by every write). Coolify once went down when the VM's root disk
filled, so:
- **The mount check.** A write is refused when the destination is on the same device as the container's `/` (`os.stat(dir).st_dev
  == os.stat("/").st_dev`). That is what a missing Coolify mount looks like: the folder exists in the container's overlay, which
  lives on the VM's root disk. It is refused in words ("/data/topo is not a mounted volume here"). `DATA_ADMIN_SAME_DISK=allow`
  exists for a local run only, and production refuses it.
- **The reserve.** Nothing is started unless what it adds still leaves `max(20 GB, 10 % of the volume)` free (Q6). The check is
  made again before each file, and every 256 MB while a file comes down. A job that crosses the reserve stops, keeps what is
  whole, deletes its `.part` and says why.
- **The stream cache.** The stream cache's cap is checked against the volume too. If cap plus reserve exceeds what is free, the
  panel says so. Pruning by cap alone could otherwise fill a volume that also holds DEMs.

**Staging.** Each volume has `.incoming/` at its top: the same filesystem, so the move into place is a rename. Everything there
is named `*.part`, and the catalog walk skips dot folders (10.1, 5). A `.part` with no progress for 48 hours is removed.

**Where the work runs (Q2): in the API, on a thread of its own.** A download is mostly waiting on the network, which a thread
does without holding the interpreter (as the NAIP pass does), so it never takes one of gunicorn's 8 request threads. The job
follows `survey/book.py`'s `Printer`:
- a `data_job` row holds the state, files, bytes and error;
- one job at a time;
- a restart picks up a job left running, resuming each `.part` with `Range`;
- the page polls the row every 2 s.

It pauses while an analysis runs (`terrain_tiles.analysis_running`), as warming does. The build service is the alternative, but
it mounts no `/data/topo`, has no database, and is to retire (D20, D24).

**Only USGS's hosts.** A job fetches only `https://` URLs on `prd-tnm.s3.amazonaws.com` and `rockyweb.usgs.gov`, whatever TNM's
answer says, and follows no redirect elsewhere. Each file is checked against TNM's `sizeInBytes` and the server's
`Content-Length`. No coordinates, bounding boxes or file names are logged; a job's id is random.

**After a DEM lands** (Q7), in order:
1. The catalog is refreshed at once (a new `LOCAL_CATALOG.refresh()`).
2. That moves the terrain tiles to a new generation.
3. A background warm of levels 0–9 starts (about a minute over north Georgia).
4. Once it finishes, the older generations are deleted.

Until then the 3D terrain is computed on demand, as on a first start. The per-tile key of §2.2 would spare all of that, and is
Phase 2 of the old plan.

### 10.5 The ways in, in the order proposed

**Download DEMs from USGS** (first: no tunnel limit, no client code beyond the map).
1. Draw a rectangle on the map and choose 1/3″ or 1 m (Q4).
2. The server asks TNM and answers the files:
   - the newest version of each cell or tile only;
   - each one's size;
   - each one marked *on the server* (same file name, or bounds that overlap an entry of the same resolution by 95 %) or
     *new*;
   - the total, and the free space after.
3. A search over the job limit (Q11) or the reserve is refused before anything is asked of USGS's file hosts.
4. Confirm. The job downloads each file into `.incoming`.
5. Each file is validated:
   - rasterio opens it; it has a CRS and a nodata value, one band, and units in metres;
   - its bounds lie inside the geoid grids' box (−125 to −66, 24 to 50);
   - it is 15 m or finer;
   - its median difference from Terrarium at 25 points is within ±5 m.
6. It is tagged NAVD88 by provenance (10.1, 4) and renamed into place:
   - 1/3″ into `<first TERRAIN_DATA_DIR root>/usgs/13/`;
   - 1 m into `<root>/usgs/1m/<project>/`.
7. 10.4's "after a DEM lands" runs.

A cell already on the server is skipped unless the admin ticks *replace* (Q8). A replaced file goes to `.trash/` for 7 days, so
a bad replace can be undone.

**Upload DEMs** (Part B's chunked, resumable protocol, D3, unchanged). The validation is the same, and with no vertical datum
stated the *NAVD88 (USGS 3DEP)* tick is required. DTED is refused (D5). It is the way in for a DEM that TNM does not serve. Q9
asks whether to build it in this round.

**LiDAR** (Q3), if wanted:
- the same search against `Lidar Point Cloud (LPC)`, files into `/data/lidar/<project>/`;
- validation as §4 (header, CRS, completeness, the box);
- then the index written by rename, with the scan recursing (§2.3);
- LAZ uploads through the same protocol.

This needs `/data/lidar` mounted writable into the production API at the same path (D2). Until D24, all it feeds is the build
service.

### 10.6 The owner's open questions

| # | Question | Recommended | Other choices |
|---|---|---|---|
| Q1 | The map library | **MapLibre GL JS, as D1 decided** (already approved), vendored into `backend/static` | Leaflet 1.9.4, the web's version, vendored: smaller, no WebGL, and plenty for these layers once aggregated |
| Q2 | Where downloads run | **In the API, on a background thread of its own** (10.4) | In the build service, which would need `/data/topo` and endpoints of its own |
| Q3 | LiDAR downloads | **Not in this round.** DEMs first. LAZ only matters to the build service until local streaming (D24) | Build it now, after DEMs |
| Q4 | DEM products offered | **1/3″ and 1 m**, 1 m chosen per search (about 58 GB a 1° cell) | 1/3″ only |
| Q5 | The DEMs on the VM with no datum tag | **A button that tags as NAVD88 the files whose names are USGS's** (`USGS_13_…`, `USGS_1M_…`), writing a `.aux.xml` beside each | Leave them labelled "source metadata required" |
| Q6 | The reserve kept free on a volume | **max(20 GB, 10 %)**, and the mount check refusing the container's own disk | Another figure. Also: how big is the `/data` volume? |
| Q7 | The terrain cache after a new DEM | **Rewarm, then delete old generations** (10.4) now; §2.2 later | Build §2.2's per-tile key first |
| Q8 | A newer version of a cell already held | **Skip unless *replace* is ticked**, the old one kept in `.trash/` for 7 days | Always take the newest |
| Q9 | Uploads in this round | **After downloads**, as their own phase | With downloads |
| Q10 | Mount `/data/lidar` read-only into the API for the map | **Yes, at the same path** (D2) | Leave the LiDAR layer out |
| Q11 | The largest job | **20 GB and 100 files**, more by several jobs | Another figure |

### 10.7 Phases, each its own PR off `develop`

| Phase | Builds | Owner steps |
|---|---|---|
| **D0. Guards** | `data_volumes.py` (the mount check, the reserve, `disk_usage`); `LOCAL_CATALOG.refresh()` and dot folders skipped in the walk; terrain cache generations listed, rewarmed and swept (Q7); `DATA_ADMIN`; the `data_job` table | None |
| **D1. The map, read only** | The Data tab with its layers and panel (10.3), and the map library vendored (Q1) | Set `DATA_ADMIN=read` on production and staging. Optionally mount `/data/lidar` read-only into the API (Q10) |
| **D2. DEMs from USGS** | The search, the job, validation, tagging, the move, the refresh (10.5); the tag button for existing DEMs (Q5) | Mount `/data/topo` writable into the **production** API only. Set `DATA_ADMIN=write` there. Run one small search and download (one 1/3″ cell, about 480 MB) |
| **D3. Uploads** (Q9) | Part B's chunked uploads into `.incoming`, the same validation | Upload one file over 100 MB to confirm the tunnel takes 64 MB chunks |
| **D4. LiDAR** (Q3) | The LPC search and job, LAZ uploads, §2.3 | Mount `/data/lidar` writable into the production API at the same path |

Each phase updates AGENTS.md (§4's features, §6, §10's settings) in the same commit. **Not verifiable from an agent session:**
- the VM's free space;
- whether Coolify's mounts are what the mount check expects;
- the VM's speed to USGS's hosts;
- how the map looks in the admin's browser.

The owner checks each in the phase's step.

### 10.8 The owner's answers, and D0 to D2 as built (2026-11-04)

**The owner's answer.** "Proceed with DEM downloads. If we can show on the map what LiDAR is cached, that's great too."
- **Q3:** no LiDAR downloads.
- **Q9:** no uploads in this round.
- **Every other question:** as recommended in §10.6.

**Built** (`backend/data_admin/`, `routes/admin_data.py`, `templates/admin/data.html`):
- **D0, the guards:**
  - `volumes.py`: the mount check, the reserve, and each place's mount read from `/proc/self/mountinfo`;
  - `LOCAL_CATALOG.refresh()`, with dot folders skipped in the walk;
  - `terrain_tiles.generations()`, `sweep_generations()` and `rewarm_after_change()`;
  - `DATA_ADMIN`, and the `data_job` table.
- **D1, the map:**
  - MapLibre GL JS 6.15.0, vendored (1.7 MB). It is ES modules only since version 6, so the page imports it as a module and it finds its worker beside itself;
  - the layers and the panel of §10.3;
  - the streamed cache, walked and placed by decoding each piece's name with the secret, counted in 0.1° cells. Pieces wider than 10 km count only in their survey's totals. A survey key of an older look is counted as "never served again";
  - the downloaded LiDAR layer, wherever `LIDAR_COLLECTION` is set on the API.
- **D2, DEMs from USGS:**
  - the search, then a job (searched again on the server when confirmed, so nothing the page sends is downloaded);
  - downloads resumed with `Range`, the reserve checked every 256 MB, nothing fetched while an analysis runs;
  - the checks, the NAVD88 tag, the move into place, the catalog and the 3D terrain after it;
  - *Replace*, which moves the old file to `.trash/` for 7 days. It applies only to **the same tile**: the same name, the
    same cell, or a footprint about the same both ways. A cell inside a larger DEM (a statewide mosaic) is marked *covered*,
    never fetched and never replaced, since moving the mosaic aside would take all its other ground with it (the review
    found that);
  - the button that tags the server's existing USGS-named DEMs (Q5).

**Found by the independent review, and built in:**
- **One bad file is that file's failure.** A 4xx, a redirect, or a size other than TNM's fails that file, and the job goes
  on. A busy host (429, 5xx) is tried again after a pause. A redirect is never followed.
- **A job always ends.** A database failure mid-job (the pooler restarting) still writes it failed, in a fresh session, so it
  is never left *running* to be resumed at every look.
- **Leftover jobs resume only where writes are on.** A cancel is always set on the job itself.
- **The terrain cache sweep deletes only this deployment's generations.** Each generation is marked with a hash of the
  database's host and name (`.owner`), since staging shares `/data/cache`. One another deployment made, or one from
  before the marks, is kept. A rewarm asked for while one runs goes round again rather than being dropped.
- **A download leaves the streamed LiDAR cache room to grow on the same disk:** its cap less what it holds. The cache
  prunes by its cap alone and never looks at what is free. The panel warns when cap and reserve exceed what is free.
- **The datum check reads the compound CRS with pyproj.** NAVD88 must be in metres: NAVD88 in US survey feet is refused.
- **The search has a 30 s deadline**, under Cloudflare's 100 s. The walks of the caches run one at a time and are kept 10
  minutes. The streamed layer is off until it is ticked.
- **1 m project names that could climb out of their folder, or start a dot folder, are left out.** The older
  `USGS_one_meter_…` names are read.
- **Messages name folders by what they are, never by the server's paths.**

**Not like §10, on purpose:**
- **The search runs in the request.** It is one to three pages from TNM, under 2 s each, with timeouts.
- **A file refused by its checks is deleted from `.incoming`, not kept there.** It is USGS's own file and can be fetched again, and 480 MB left for an admin to clear is how a disk fills.

**Tried:**
- a real search through the page over Ellijay (the newest of six versions chosen, the cell found on the server under another name);
- the checks on USGS's real 1/3″ and 1 m files, read over HTTP: both pass, +0.3 m and +1.0 m from Terrarium at the median;
- the page in the browser preview, over a local server with two test DEMs: the map, its layers, the volumes, a real 1/3″
  and a real 1 m search;
- everything else against stand-ins, in `tests/test_data_admin.py`.

**Not tried:**
- a whole download on the VM;
- the mount check under Coolify;
- the cache walks over a real stream cache.

**Owner steps:**
1. On the **production** API, set `DATA_ADMIN=write` and mount `/data/topo` writable.
2. On staging, set `DATA_ADMIN=read` (or leave it off).
3. Open `admin.ezpztac.app/admin/data` and check that the Volumes panel shows `/data/topo` as a mounted volume with the free space you expect.
4. Download one 1/3″ cell (about 480 MB) and watch it reach the map.
