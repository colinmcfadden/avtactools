# Plan: Data on the server, prebuilt 3D areas, and organizations

Status: proposed 2026-10-22. Waiting on the owner's decisions (§9). Nothing is built yet.

The request, in the owner's words: an admin page with a CONUS map showing where the server holds terrain and LiDAR
itself (not what comes from outside services); drag-and-drop uploads into the server's volumes; areas chosen on the
map and built ahead, so 3D opens at once there instead of after minutes; prebuilt areas sold as an upgrade; and a way
to release it to individual and organization accounts, with an organization's prebuilt access held to its area of
operation (AO).

**The earlier discussion.** The owner says this was discussed before. It is not on this Mac: no Claude desktop session,
Claude Code transcript or Codex thread here mentions a coverage map, prebuilding or areas of operation, and nothing in
the repo or on any branch does. It was probably on a Windows PC or in a claude.ai chat. This plan starts from the words
above. If something was decided there that differs, it wins and this document changes.

Earlier decisions that bear on it, all in the repo:
- AGENTS.md §9's 3D table. Pre-generating every terrain tile was rejected (D) because fine tiles cost about 50 ms each
  on demand. Cesium ion was rejected: commercial pricing, and its terrain need not match our DEMs and LiDAR.
- `docs/NATIVE_APPS_PLAN.md`'s map packs. Offline areas built on the server from public-domain data (NAIP imagery,
  USGS DEMs), with "3D for LZs whose LiDAR was packed". A prebuilt area is the natural source for that later.

---

## 1. What exists today

| Piece | Where | What matters here |
|---|---|---|
| DEM catalog | `terrain_provider.LocalRasterCatalog` | Walks `TERRAIN_DATA_DIR` every 5 min, opening every `.tif`, `.tiff`, `.cog` and `.dt2` it finds. Each entry has its bounds, resolution and a vertical datum read from a `VERT_DATUM` tag. Slope analysis takes COGs of 15 m or finer, then DTED2. |
| 3D terrain tiles | `terrain_tiles` | Every DEM in the catalog is sampled, DTED included, and **every one is treated as NAVD88** (the geoid offset is EPSG:5703's). Tiles are cached under one fingerprint of *all* DEMs. Levels 0–9 over the whole coverage are warmed at startup. |
| Downloaded LiDAR | `lidar/collection.py` on the build service | `/data/lidar/tile_index.json` holds each LAZ tile's bounds in its own CRS, its point count and its CRS. The scan reads **only the top folder**. A build reads the index; `--reindex` rescans it. |
| USGS survey index | `lidar/coverage.py` on the build service | `/data/cache/usgs_ept_coverage.geojson` (about 8 MB): every 3DEP survey on AWS. This is the outside fallback, not local data. |
| Tileset store | `lidar/catalog.py`, `/data/tiles/<key>/` | Each tileset has an `area.json` (centre, radius, survey). `resolve` finds the nearest tileset whose square holds the target within 75 % of its radius (`find_covering`), reading every manifest on every call. |
| Build service | `lidar/worker.py`, `backend/lidar` image | One build at a time, at most 8 queued or running, all in memory. Builds someone is watching go first. `keep` builds (a saved LZ) always finish. The radius is capped at 1 km. Staged under `.staging-<key>`, then renamed into place. |
| Mounts (`backend/lidar/SERVER_SETUP.md`) | Coolify | `/data/topo` → API. `/data/tiles` → both (the builder writes, the API reads). `/data/lidar` and `/data/cache` → builder only. **Staging shares all of them with production** (AGENTS.md §9). |
| Admin | `routes/admin_routes.py`, `templates/admin/` | Jinja, a session cookie and CSRF tokens, no JavaScript libraries. One page posts a file (an AMPS template, 32 MB at most). |
| Entitlements | `entitlements.py` | Per-user feature map. A missing key means on, except keys in `DEFAULT_OFF` (not launched yet: off but for admins and testers). There are no plans, prices or organizations. |
| Teams | `routes/team_routes.py` | Made by users for mission packs and name search. Any user can create one. |

Measured costs, from AGENTS.md §9 and §13 (north Georgia):
- **Builds:** a 1 km² build (500 m radius) takes **137 s** from AWS with level-18 imagery. Surveys hold 2.75–3.5 points/m².
- **Disk:** about 15 bytes a point, so **41–52 MB per km²**.
- **Terrain tiles:** about 50 ms each from level 9 down, 8,450 bytes each.

## 2. Found while reading: fix these first

1. **Any signed-in user can list every place a 3D view was ever built, with its coordinates.** Tried against the real
   blueprint (a throwaway test with the `test_lidar_api` harness):
   - `GET /api/lidar/tilesets` returns every key.
   - `GET /api/lidar/tilesets/<key>/area.json` returns that place's latitude, longitude and radius, because the file
     route serves anything in the folder.
   - Even without those, a key is an unkeyed SHA-256 of the coordinates rounded to 4 decimals and the radius. CONUS is
     about 1.5 × 10¹¹ such cells, which one GPU hashes in seconds and a laptop within the hour. So a key can be turned
     back into its place.

   The brief cites an AGENTS.md §15 note about this; no such note exists on `develop` or any branch, so this plan adds
   one. The web never calls the list route, nor does the Android 3D branch. It matters more with organizations: one
   unit could read where another plans.
   Fix (Phase 0):
   - serve only tile files (`.json` but not `area.json`, `.pnts`, `.b3dm`, `.glb`, `.subtree`);
   - make the list admin-only;
   - derive new keys with an HMAC under a server secret both containers hold.
2. **Adding any DEM throws away the whole terrain cache.** The cache key is a fingerprint of every DEM's path, size and
   time, so one upload in Texas recomputes Georgia's warmed tiles. Uploads and warming areas both need the key per tile:
   only the DEMs that reach that tile.
3. **The LiDAR index is written in place** (`save_index` is a plain `write_text`). A build reading it mid-write fails.
   Once the API also writes it (after uploads), it must be written to a temporary file and renamed. The scan must also
   look in subfolders, so each survey can have a folder of its own.
4. **The 3D view frames the whole tileset** (`frameSphere(tileset.boundingSphere)` in `Viewer3D.jsx`; Android's branch
   does the same). A prebuilt area tens of kilometres across would open on the area, not the LZ. `resolve` must say
   where to look, and both viewers must use it.
5. **`find_covering` reads every `area.json` on every call.** That is fine at dozens of tilesets and slow at thousands.
   Keep an index in memory, refreshed when the folder's modification time changes.

---

## 3. Part A: the coverage map

A **Data** tab in the admin (`/admin/data`): a CONUS map with layers, and beside it a panel of numbers.

**Layers** (each can be switched off):

| Layer | From | Drawn as |
|---|---|---|
| DEMs | `LOCAL_CATALOG.entries()` | Rectangles, coloured by resolution: 1 m, about 3 m, 1/3″ (about 10 m), DTED2 (about 30 m). Clicking one shows its file, resolution, datum and size. |
| Downloaded LiDAR | `tile_index.json`, each tile's bounds turned into WGS84 with pyproj (once per CRS group, cached against the index's modification time) | Tiles coloured by the survey's year: 2015 or later classifies vegetation. Above about 20,000 tiles they are merged into 0.05° cells until zoomed in. |
| Prebuilt areas | the database (Part C) and their manifests | Outlines coloured by state (queued, building, ready, failed), with progress. |
| Per-LZ tilesets | `area.json` of each | **Counts in 10 km cells, not points** (decision D16). Exact squares would show the admin where crews plan, which the admin otherwise never sees: the Packs page deliberately never shows what a pack holds. |
| Organizations' AOs | the database (Part D) | Outlines, once Part D exists. |
| AWS 3DEP surveys (off by default) | `usgs_ept_coverage.geojson` | Faint footprints: where a build can fall back to AWS. Outside data, so off unless asked for. |

**The panel:**
- counts and areas per layer;
- free and used space on each volume (`shutil.disk_usage`);
- the measured MB per km² of the tilesets already built, used to calibrate the estimates in Part C;
- the build service's queue (`/health` already reports it).

**Where the data comes from.** The API can read `/data/topo` and `/data/tiles` today. It also needs `/data/lidar`, read
only for the map and writable for uploads (Part B). The alternative is to ask the build service for a summary, which
keeps the mounts as they are but puts a new endpoint on the builder for every layer. Mounting is simpler, and the paths
in `tile_index.json` are absolute, so **both containers must mount it at the same path**.

**The library (decision D1).** A map in the admin needs a map library, which is a new dependency for the backend.
Proposed:
- **Leaflet 1.9.4**, the version the web app already ships (BSD-2), copied into `backend/static/vendor/leaflet/`
  (about 150 KB) so the admin needs no CDN.
- No drawing plugin: the rectangle and polygon tools Part C needs are about 150 lines of plain JavaScript.
- **Base map: the USGS National Map** (topo and imagery). Public domain, keyless, CONUS-wide. The Mapbox token is
  URL-restricted and may refuse the admin host.

**Endpoints.** All are admin session plus CSRF, JSON, `Cache-Control: private, no-store`:
- `GET /admin/data/coverage.json?layers=…&bbox=…` (the bbox for zoomed-in LiDAR tiles);
- `GET /admin/data/volumes.json`.

**Fly.** The Fly backend has no data volumes, so the page says so and offers nothing there.

---

## 4. Part B: uploads into the volumes

### What goes where

| File | Accepted | Lands in | Then |
|---|---|---|---|
| DEM | `.tif`, `.tiff` (GeoTIFF or COG), `.dt2` | `/data/topo/<folder>/<name>` | The catalog is refreshed at once and tiles under the new DEM are warmed (levels 0–9). |
| LiDAR | `.laz`, `.las` | `/data/lidar/<survey folder>/<name>` | The tile is added to `tile_index.json`, written to a temporary file and renamed. The build service reads the index on each build, so it sees the tile on its next build. |

`<folder>` is chosen in the upload panel: an existing folder, or a new name such as `GA_Statewide_2018`.
File names are made safe by the server, and an existing name is never overwritten: replacing a file is an explicit
second step.

### Getting big files through Cloudflare

- **The limit.** Cloudflare refuses a request body over **100 MB** on the Free and Pro plans (200 MB Business, 500 MB
  Enterprise). It answers an origin that takes more than 100 s with a 524.
- **The files.** A 1/3″ DEM tile is about 400 MB, a 1 m DEM tile about 400 MB, and a LAZ tile 50–500 MB.
- **So uploads are chunked and resumable** (decision D3): a protocol written here (in the spirit of tus), with no
  library on either side.

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
- `write` adds uploads and prebuilds, and is set **only on the production backend**;
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
  into a `.aux.xml` beside the file (the tag the catalog already reads). Any other stated datum is refused (decision D5);
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

### Fetching from USGS instead (B2, decision D4)

Dragging a survey through the admin's laptop takes USGS → laptop → Cloudflare → home. For public 3DEP data the server
can fetch it itself, and the tools exist already:
- `tools/find_lidar.py` lists an area's LAZ tiles through the National Map API, best survey first;
- `lidar/fetch.py` downloads resumably, checking each file against `Content-Length`.

**Fetch from USGS** on the map: draw an area, see the tile list and its size, and confirm. The build service (it mounts
`/data/lidar` and has the network) downloads into `.uploads`, then validates and moves each tile as above. The same API
lists 1 m and 1/3″ DEMs, so DEMs can come the same way.

Uploads stay for data that is not on USGS (a state DEM, a file handed over). **rsync to the VM** remains the way to move
terabytes.

---

## 5. Part C: prebuilt areas

### What a prebuilt area is (decision D6)

One **region tileset**, made of square **blocks**:
- each block is 2 km on a side (a 1,000 m radius, inside the builder's limit) on a grid in CONUS Albers (EPSG:5070,
  metric and equal-area);
- each block is built at full density, as an ordinary build;
- the blocks are joined under one root `tileset.json` that refers to each block's tileset as an **external tileset**.
  Cesium supports those, and so does the Android 3D branch's `TilesetReader`;
- the root's bounding regions come from each block's extent.

```
/data/tiles/<region key>/            key: 16 random hex digits (the same pattern, so every route and both apps serve it)
  tileset.json                       root; children are the blocks
  area.json                          {"kind": "region", "bbox": [w, s, e, n], "blocks": [[w, s, e, n], ...]}
  blocks/<i>_<j>/tileset.json, *.pnts
```

Why blocks and not one build:
- an area of hundreds of km² is hours of work, and blocks make it resumable after a restart;
- they give progress ("block 31 of 100");
- a failure costs 9 minutes, not 15 hours;
- each block can give way to a person who is waiting (below).

Why one region and not a grid of ordinary per-LZ tilesets:
- an LZ near the edge of an ordinary tileset has data on one side only;
- to keep 500 m around every LZ, the grid would have to build every km² four times over.

Why the region is visible only once complete:
- `resolve` cannot hand out a region with holes in it;
- assembled under `.region-<key>`, it appears in one rename, as builds do today.

A very large AO is better as several regions: each becomes usable as it completes.

### Relation to per-LZ tilesets and to `resolve`

- **Both kinds stay.** Per-LZ tilesets are still built on demand and on save, as today, for everyone.
- **`resolve` and `build` look at regions first, but only regions the caller may use** (Part D):
  - a region answers when the target's 500 m square lies inside its built blocks;
  - otherwise the per-LZ lookup runs as now;
  - the per-LZ lookup ignores region manifests.
- **A region's answer says where to look:** `frame: {lat, lon, radius_m}` (additive). Both viewers frame on it instead
  of the tileset's sphere (§2.4). A region has no `contextUrl`, because it is its own context.
- **A caller who may not use the region** gets today's behaviour: the per-LZ tileset if there is one, else a build that
  takes minutes (decision D10). That is what makes prebuilt an upgrade. It also spends build time on ground already
  built, which is the price.
- **Building per-LZ tilesets on save** (`useBuildOnSave`) is skipped inside a region the person may use.

### The build service's side

New, in `lidar/worker.py`, beside today's queue:
- `POST /regions` (key, blocks, imagery, classes), `GET /regions/<key>` (progress), `DELETE /regions/<key>` (cancel).
- **The queue lives on the shared volume** (`/data/tiles/.regions/<key>/plan.json`, progress in `progress.json`, both
  written to a temporary file and renamed). A restart of either container loses nothing, and the API reads progress
  straight from the volume.
- **Order:**
  1. builds someone is watching;
  2. `keep` builds (saved LZs);
  3. region blocks, oldest region first.

  A block that is running is **stopped and queued again from the start** when someone opens a 3D window (decision D8):
  9 minutes of a block weigh less than a person waiting. An optional night window (`LIDAR_REGION_HOURS=22-06`) is
  offered too.
- **A block with no LiDAR** (water, a gap between surveys) is marked empty and left out. Blocks covered only by a survey
  from before 2015 are listed in the estimate: ground only, no vegetation.
- **Point colour (decision D7).** Per-LZ builds take colour from Mapbox at level 18, about 63 imagery tiles per km².
  Storing Mapbox colour in tilesets that are then *sold* is a terms question: licence language commonly bars data
  derived from the tiles, and NATIVE_APPS_PLAN already notes that Mapbox forbids caching tiles outside its SDK.
  **Proposed: prebuilt regions take colour from USGS NAIP** (public domain, 0.6 m, CONUS), through a GDAL description
  like today's `mapbox_imagery.xml` pointed at USGS's public NAIP image service. Per-LZ builds keep Mapbox until the
  owner decides otherwise. To be tried on one block first: NAIP's colour, and its date per state.

### Terrain warming for a region

`terrain_tiles.warm_area(bounds, max_level)`:
- warms levels 10 to N over the region (0–9 are warmed already);
- **N is 16 by default** (decision D18), the deepest level an LZ's opening view asks for (§9: "levels 0–16");
- it runs in the API, which holds the DEMs and the cache, in the background, and pauses for analyses as warming does
  now;
- progress is tiles done out of tiles.

What it buys is not seconds of terrain (a first view costs about 350 ms of it now). It is that a warmed region never
computes tiles on request, so 3D there never competes with an analysis for the one interpreter (§9, "Heavy work goes
first"). It needs §2.2 first, or the next DEM upload anywhere throws it away.

### Estimates, before anything is queued

The admin draws a rectangle or polygon (or picks an organization's AO). The estimate:

| Quantity | How |
|---|---|
| Area | The polygon in EPSG:5070 (pyproj). |
| Blocks | The 2 km cells the polygon touches: `rasterio.features.rasterize(all_touched=True)`, no new library. |
| Source per block | The local collection where it covers 95 % of the block, else the best AWS survey (asked of the builder, which holds both indexes: `POST /estimate`). Old-survey blocks are counted. |
| Points | Per block, the collection's own density (points over tile area) where local, else 2.75 per m². |
| Disk | Points × 15 bytes, corrected by the MB per km² measured on the server (Part A). |
| Build time | Area × the seconds per km² of the last 20 blocks built (137 s per km² until there are any). |
| Imagery requests | About 63 per km² from Mapbox, or none from NAIP. |
| Terrain tiles | Counted exactly per level from the bounds: 8,450 bytes and about 50 ms each. |
| Room | Disk against free space. A prebuild that would leave less than the reserve is refused. |

Worked numbers at 34.5° N:

| Area | Blocks | Point clouds | Disk | Terrain to level 16 | Terrain to level 17 |
|---|---|---|---|---|---|
| 10 × 10 km (100 km²) | 25 + edges | about 3.8 h from AWS | 4.1–5.2 GB | about 1,750 tiles, 1.5 min, 15 MB | about 6,900 tiles, 6 min, 58 MB |
| 20 × 20 km (400 km², an installation's training area) | 100 + edges | about 15 h | 16–21 GB | about 7,000 tiles, 6 min, 60 MB | about 27,600 tiles, 23 min, 233 MB |

Builds from the local collection should be faster (not measured since the imagery fix: AGENTS.md §9, F).
A whole state is out of reach: Georgia is 154,000 km², about 240 days of building and 7 TB.

### Progress, and the admin's controls

- The Data tab's **Prebuild** panel lists each area: state, blocks done out of all, the estimate against what is
  measured so far, terrain tiles done.
- **Pause** and **cancel** are offered. A cancel keeps nothing, and deletes the hidden folder after a question.
- **Rebuild** builds to a new key and swaps it in when done, then removes the old one: a newer survey, or other imagery.
- **Retire** keeps the row and removes the files.

The page polls every 5 s. Region names and outlines live in the database only, never on the volume or in a log.

---

## 6. Part D: releasing it

### Accounts

- **Individual accounts** stay as they are: Google or email, and the `.mil` gate or an admin's approval.
- **An organization is not a login.** It is a group of individual accounts, made by the super-admin, with a plan,
  seats, members and one AO. Nobody shares an account.

**Organizations and Teams (decision D11): kept apart.**
- Teams are made by users, for mission packs and finding people.
- An organization is made by the admin, for entitlements, billing and the AO.
- Linking them (an org team that follows the org's members) would mean managed teams that members cannot leave or
  change. That changes `team_routes` and the Android Teams screens, which are contracts now.
- An org's admin can make an ordinary team whenever they want one. Linking can come later if units ask for it.

**One organization per person** (decision D12). An aviator in two units at once is rare. Leaving one (a PCS) and joining
another is a change of membership, and the AO rule stays unambiguous.

### Data model (new tables, `db.create_all()` makes them; nothing changes on existing tables)

| Table | Columns |
|---|---|
| `organization` | `id`, `name` (100), `status` (active, suspended), `plan`, `seats`, `plan_started_at`, `plan_ends_at`, `comped`, `billing_ref` (a provider's customer id, null until there is a provider), `notes` (admin only), `created_at`, `updated_at` |
| `organization_member` | `id`, `org_id` (cascade), `user_id` (cascade, **unique**: one org each), `role` (owner, admin, member), `added_by`, `added_at` |
| `area_of_operation` | `id`, `org_id` (cascade, **unique**: one AO each), `name`, `geometry` (GeoJSON Polygon or MultiPolygon, WGS84), `bbox`, `area_km2`, `updated_by`, `updated_at` |
| `prebuilt_area` | `id`, `key` (16 hex, unique), `name`, `geometry`, `bbox`, `area_km2`, `audience` (admins, catalogue, org), `org_id` (null unless audience is org), `status`, `blocks_total`, `blocks_done`, `bytes`, `imagery`, `terrain_level`, `terrain_done`, `created_by`, `created_at`, `built_at`, `retired_at` |
| `user_plan` | `user_id` (unique), `plan`, `status`, `ends_at`, `comped`, `provider`, `provider_ref`, `updated_at`: a person's own plan, apart from any org |

**Deleting an account:**
- removes its memberships;
- an org's last owner cannot be deleted until an admin names another owner, as `pack_support.release_account` does for
  packs. Both deletion paths call it.

### The AO, and where access is enforced (decision D13)

An AO is one polygon (or multipolygon), set by the super-admin in the org's page:
- drawn on the map, or loaded from a GeoJSON file;
- its size is capped by the plan;
- org admins can see it but not change it (v1).

AO outlines are training areas. A unit's AO for a real operation would be the kind of aggregation the "unclassified
only" rule forbids, so the org page says so. The outline is shown only to the super-admin and that org's admins, and is
never logged.

**Who may use a region:**
- admins: all regions;
- a person with a personal plan that includes prebuilt 3D: regions whose audience is `catalogue`;
- an org member with a plan that includes it: regions that intersect their org's AO;
- union of the above.

Enforced in three places (`lidar_access.py`, one module; each a test):

| Where | Check |
|---|---|
| `POST /api/lidar/resolve` | A region answers only if the caller may use it **and** the target lies inside the caller's AO (org members) or the region (catalogue). Otherwise the per-LZ lookup. |
| `GET /api/lidar/tilesets/<key>[/<path>]` | A region's files go only to those who may use it. Others get `403 region_not_entitled`. Per-LZ files stay open to any signed-in user: only someone who asked by coordinates is given a key, and Phase 0's HMAC keys cannot be computed. |
| `POST /api/lidar/build` (and its status) | A region the caller may use answers `done`. One they may not use is never returned. Building per-LZ tilesets stays open to all, anywhere. |

**Speed.** Every tile request is checked. So:
- a person's access (admin, plans, org, the AO prepared for point-in-polygon) is held in memory for 60 s;
- the regions' index is reloaded when the table changes;
- the API is one process (§9), so the caches agree;
- point-in-polygon uses matplotlib's `Path`, already a dependency.

### Entitlements and plans

- A new feature key, `prebuilt_3d`, "Prebuilt 3D areas".
- It is neither on by default nor `DEFAULT_OFF` (which means "not launched; becomes everyone's at launch"). It is a new
  kind, **`PAID`: on only when a plan grants it or an admin ticks it** (a tick is how testers and comped accounts get
  it).
- `resolve_features` becomes: what is stored or the default, as today, plus the features of the person's own plan and
  their org's plan, if active and in date. The user page in the admin shows where each feature comes from.
- `GET /api/auth/me` gains `plan: {name, source: org|personal, ends_at}` and `org: {name, role}`. These are additive
  (the apps' rule), and the apps hide what the plan does not include.

Plans (names and prices are the owner's; decision D14):

| Plan | Who | Adds |
|---|---|---|
| Free | everyone, as today | Everything there is now. 3D builds on demand in minutes. |
| Pro | a person | Prebuilt 3D in the **catalogue**: training areas the owner chooses to build, such as the large aviation installations. Their on-demand builds go ahead of free ones. |
| Organization | a unit, by seats | Prebuilt 3D across the **org's AO**, built for them. Their members' on-demand builds go ahead too. A size of AO in km² is included. |

During the beta, before anyone pays (decision D9), prebuilt areas could be:
- for admins and the testers an admin ticks (as `mission_packs` is now). **Recommended:** nobody loses something they
  had when plans start;
- or for everyone, as a preview.

### Billing (decision D15)

Any payment provider is a new dependency. Options:

| Option | Fits | Notes |
|---|---|---|
| **Manual first** (no provider) | The beta, and units that pay by invoice | The admin sets a plan, its end date and "comped"; payment is arranged outside the app. Nothing to integrate; it tests demand. **Recommended to start.** |
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
  - the list: name, plan and end date, seats used out of seats, AO in km², prebuilt areas ready and building;
  - **an org's page:**
    - members: add by email, set the role, remove; a person is in one org at most;
    - plan: plan, seats, dates, comped, the billing reference, notes;
    - **the AO**: the map, draw or load, area in km², and an estimate of prebuilding it;
    - its prebuilt areas, with "prebuild the AO" (it goes through Part C's estimate and confirm);
    - a history of every change, with who made it.
- **User page:** their org and role, their own plan, and where each feature comes from.
- **Data tab** (Parts A–C): prebuilt areas show their audience. A catalogue area can be marked `catalogue`.

### Phased rollout

Each phase is one PR or more, with its tests and its AGENTS.md update in the same commit (Conventional Commits). The
owner runs every deploy and every Coolify change.

| Phase | Builds | Owner steps |
|---|---|---|
| **0. Close the exposure** (small, on its own) | §2.1: tile files only, the list admin-only, HMAC keys for new builds (`LIDAR_KEY_SECRET` in both containers). AGENTS.md §13 and §15. | Set `LIDAR_KEY_SECRET` (`openssl rand -hex 32`) on the API and the build service, then deploy both. |
| **1. The coverage map** (read only) | §2.3 (the index written by rename; the recursive scan), §2.5 (the manifest index), Leaflet vendored, `/admin/data` with its layers and numbers, `DATA_ADMIN`. | Mount `/data/lidar` (read only for now) into the API at the same path. Set `LIDAR_COLLECTION=/data/lidar` and `DATA_ADMIN=read` on the API. |
| **2. Terrain cache per tile** | §2.2 (the key per tile, a sweep for orphans), `warm_area`. | None. The first start after it rewarms levels 0–9 once (about 1 minute). |
| **3. Uploads** | Part B: chunked uploads, validation, rename into place, refreshes, the sweep. | Mount `/data/topo` and `/data/lidar` writable into the production API. Set `DATA_ADMIN=write` there only. Upload one file of over 100 MB to confirm the tunnel takes 64 MB chunks. |
| **3b. Fetch from USGS** (if D4) | The builder downloads an area's LAZ or DEMs. | Redeploy the build service. |
| **4. Prebuilt areas** | Part C: `prebuilt_area`, the builder's region queue, its pre-emption and assembly, estimates, progress, the `frame` hint in both viewers, region warming. Regions for admins and ticked testers only. | Redeploy both. Prebuild one 10 × 10 km area and compare the estimate with what was measured. |
| **5. Organizations and AOs** | Part D's tables, admin screens, `PAID` and `prebuilt_3d`, the access module in its three places, `/api/auth/me`'s additions. | None beyond deploys. |
| **6. Plans and billing** | Manual plans first. A provider only if the owner approves one (a new dependency; asked then). | The provider's account and keys, if one is chosen. |
| **7. Launch** | Catalogue areas built, org onboarding, the user guide. | Pricing, the terms of sale, the stores' rules. |

---

## 7. What this does not do

- No prebuilding outside the geoid grids' box (CONUS) until both images bake more grids.
- No per-org copies of data. A region intersecting two AOs serves both orgs.
- No offline map packs. They are `NATIVE_APPS_PLAN`'s, and prebuilt regions can feed their 3D layer later.
- No change to slope analysis's sources, or to Terrarium as the planner's terrain.

## 8. Not verifiable from an agent session

- The tunnel's real limit with 64 MB chunks.
- Upload speed from the owner's network.
- Disk free on the VM.
- Build time from the local collection.
- NAIP's colour on a point cloud.
- How a region looks in Cesium and on Android. The browser preview cannot composite WebGL (AGENTS.md §14).

Each has a step above where the owner checks it.

---

## 9. Decisions for the owner

The recommendation comes first in each.

| # | Decision | Recommended | Other choices |
|---|---|---|---|
| D1 | Map library for the admin | Leaflet 1.9.4, the web's version, copied into `backend/static`; hand-written drawing tools; USGS National Map base | Leaflet from a CDN; MapLibre GL; Mapbox base (token restrictions) |
| D2 | How the API reaches `/data/lidar` | Mount it into the API at the same path (read only in Phase 1, writable in Phase 3, production only) | A summary endpoint and an upload relay on the build service |
| D3 | Upload protocol | Chunked and resumable, written here, no library | tus (`tus-js-client` and a server for it) |
| D4 | Fetching from USGS on the server too | Yes (Phase 3b) | Uploads and rsync only |
| D5 | DEM datum policy | NAVD88 only (stated or ticked), inside the geoid grids, checked against Terrarium; new DTED refused | Accept other datums and convert them per DEM in `terrain_tiles` (a change of its own) |
| D6 | What a prebuilt area is | One region tileset of 2 km blocks, visible when complete | A grid of ordinary per-LZ builds (four times the cost) |
| D7 | Point colour for regions | NAIP (public domain); per-LZ builds keep Mapbox until the terms are read | Mapbox everywhere (read its terms first) |
| D8 | Region builds and people | Blocks give way to anyone watching a build | Also a night window; or no pre-emption |
| D9 | Prebuilt during the beta | Admins and ticked testers | Everyone as a preview |
| D10 | Someone without access, inside a region | Today's per-LZ build, in minutes | Serve the region to them anyway |
| D11 | Organizations and Teams | Apart | An org team that follows the org |
| D12 | Organizations per person | One | Several |
| D13 | AO and access | One polygon per org set by the super-admin; targets inside the AO; region files to anyone whose AO it touches | Several AOs per org; org admins editing their AO |
| D14 | What individuals buy | Pro: the catalogue of training areas, and builds ahead of free ones | A personal AO; no individual plan |
| D15 | Billing | Manual plans first; ask about a provider at Phase 6 | Stripe now; a merchant of record |
| D16 | Per-LZ tilesets on the admin map | Counts per 10 km cell | Exact squares |
| D17 | Phase 0 first, on its own | Yes, before anything else | With Phase 1 |
| D18 | Terrain warming depth for regions | Level 16 | 17 (four times the tiles); 18 |
| D19 | Prebuilding an AO when it is set | Only after the admin confirms the estimate | Automatically |
