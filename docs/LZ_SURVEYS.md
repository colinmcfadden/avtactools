# LZ surveys — architecture and plan

Status: **planned, nothing built** (2026-10-26). The owner's answers of 2026-10-26 are in §10, and the decisions taken on the same day are
in §11; nothing is open. This file is
the plan for building the feature; where it and the code disagree once it is built, the code wins (AGENTS.md §16).

---

## 1. What it is

The unit (GA ARNG aviation, AASF 1) keeps an **HLZ survey book**: one page per landing zone, 44 of them, last surveyed October 2023,
each with the LZ's grid and lat/long, best approach and exit, maximum aircraft day and night, elevation, surface, slope felt on
landing, PPR contact, hazards, a warning, a small survey profile (an obstacle's height and distance on the approach), two aerial
photos (north, and down the best approach) and who surveyed it when.

EZ/PZ is to make that book: **admins and super-admins create, edit, sign off and publish a survey for every LZ**, built from what
the field survey recorded and what EZ/PZ measures (the corrected grid, elevation, size, slope at the best approach, contour lines,
the slope map, the profiles along and across, a 3D view, and from the LiDAR what stands on and around the LZ), and print it as a
**one-page PDF per LZ** and as the whole book. The owner's draft of LZ DONOVAN (GA 034, page 5 of the book) is the layout to build
to, with changes to come.

**Web only.** Not on Android or iOS (the owner, 2026-10-26). **Readers are every aviator in the GA ARNG**; admins and super-admins
can always reach every survey, draft or published.

---

## 2. Field values and computed values

A survey joins two sources that disagree, and the page must never hide which it printed:

| | Field (the unit's survey) | Computed (EZ/PZ) |
|---|---|---|
| Measured quantities | grid, lat/long, elevation, slope felt, size, the survey profile's obstacle | the same, from the DEM, the boundary and the LiDAR |
| Judgements | max A/C day and night, best approach and exit, surface, hazards, remarks, PPR | — |
| Media | the two photos, the survey date, the surveyor | the plan view, the profiles, the 3D capture |

**Where both exist, the computed value is printed** (the owner, 2026-10-26). The editor still shows the field value beside it, says
how far apart they are, and lets an admin override either way with a reason; the revision keeps all three. A judgement has no computed
counterpart and is the admin's to edit (§11, R3). The LZ DONOVAN draft shows why this matters:

- **Elevation:** the book says 1850 ft; the draft prints 2093 ft, and its own profile tops out at 2067 ft. The 2093 ft is the point
  elevation `POST /api/analyze-field` returns, from OpenTopoData's SRTM30 (`routes/terrain_routes.py`), a 30 m model read at one
  point, while the profile and slope come from the local 10 m DEM. A survey's elevation is the height of **the touchdown point (the
  target) in the same DEM the slope and profiles use**, with that DEM named on the page.
- **Slope:** the hazard line says a 16.6° cross slope exceeds the 15° limit while the table says 0.4° across on average. 16.6° is the
  steepest single cell; a survey's slope call uses the **95th percentile** (§3.3).
- **Grid:** the book's lat/long for DONOVAN is about 460 m from its own grid. The import (§6) flags every page whose two disagree by
  more than 50 m; the computed grid is the LZ's own (its touchdown point), and its lat/long is worked out from it.

---

## 3. What a survey holds

### 3.1 Records (Postgres, new tables; web only, so none of them syncs)

| Table | Holds |
|---|---|
| `survey_book` | The one book (the owner: one for all the unit's LZs): its name ("AASF 1 HLZ SURVEY BOOK"), the unit line, the page order, the cover's text |
| `lz_survey` | One LZ: `uuid`, HLZ ID (`GA 034`), name, `status` (§3.4), `revision` (bumped by every save, for `If-Match`), the **field** values as JSON, the **overrides** as JSON (value, reason, who, when), an embedded **LZ/PZ diagram** (the web's schema-2 document: target, boundary, doghouses for approach and exit), the current **snapshot** (§3.3), its asset ids, who made and last changed it, `archived_at` |
| `lz_survey_revision` | Every published revision, never changed: the field values, overrides, diagram, snapshot and asset ids as they were, the sign-off (§3.4), the CHANGES line, and the page's PDF (§5) |
| `lz_survey_asset` | A photo or a 3D capture: its kind (`photo_north`, `photo_approach`, `photo_other`, `view_3d`), caption, the heading it looks down, the date taken, the camera pose for a capture (§5), size, SHA-256, and where its bytes are (§3.2) |

A survey is never deleted, only archived (a published revision may be in a printed book). Two admins editing one survey get a 409 on the
older revision, as saved records do; the editor shows what changed and keeps what was typed.

### 3.2 Where photos and PDFs are kept

On the VM's disk for now, an S3 bucket later (the owner, 2026-10-26), behind one small interface (`AssetStore`: `put`, `get`, `delete`,
by key), so the move is a new class and a copy, not a change to the routes.

- `LocalAssetStore` under `SURVEY_ASSETS_DIR`, a persistent mount on the backend's Coolify app. **Staging and production each have their
  own directory** (`/data/surveys/staging`, `/data/surveys/prod`): they share the VM and its `/data` (AGENTS.md §9), and their databases
  are separate, so a shared directory would mix two sets of ids.
- Keys are the asset's SHA-256, so a file is written once and never changed in place; a file nothing refers to is removed by a sweep.
- Photos are taken as JPEG or PNG up to 20 MB, re-encoded by Pillow (already a dependency) to at most 2400 px on the long side, with the
  orientation applied and **every EXIF field dropped**. A capture is stored as sent (PNG).
- An S3 store needs `boto3`: a new dependency, to be asked for when the move comes (AGENTS.md §2).
- The VM's disk is not backed up today, and a full disk took Coolify down on 2026-10-26, so `SURVEY_ASSETS_DIR` is backed up off the VM
  and the disk watched (§11, R6): a photo cannot be measured again.

### 3.3 The snapshot: what EZ/PZ measured, frozen

Measuring again is never silent: **Measure** in the editor (and the import) works it out anew and shows what changed before it replaces
the survey's snapshot, and a published revision keeps the snapshot it was published with, so a reprint matches what was signed.

| Value | How |
|---|---|
| Grid, lat/long | The diagram's target (the touchdown point), MGRS by `utils/mgrs.js`'s rules on the server's PyGeodesy |
| Elevation | The target's height in the DEM `terrain_provider.load_best_terrain` chooses for the LZ (not SRTM30), feet MSL, with the DEM's name, resolution and vertical datum |
| Size, LZ axis, area | The boundary's smallest rotated rectangle: length × width in feet along its long axis, the axis in degrees true, the area in acres (the web's spherical-excess formula, `lzAreaAndCapacity`, ported to Python) |
| Fit | How many UH-60Ls fit by geometry (`lzAreaAndCapacity`, ported); printed as a note, never as Max A/C (a judgement) |
| Slope at the best approach | **New:** the 95th percentile, inside the boundary, of the nose-up slope, the nose-down slope and the cross slope at the **best approach heading** (the owner, 2026-10-26), from the same Horn gradients as `byHeading` (`terrain_provider._along_and_across`), judged against the UH-60 limits of AGENTS.md §13 with no wind (a survey is not for a day's weather). The average and the steepest are kept beside it for the table |
| Profiles | `profiles` of `/api/terrain-analysis`, the line nearest the best approach and the one across it, as the web's `groundProfile.js` picks them |
| Contours and slope raster | `contours` and the slope PNG of the same answer |
| Obstacles | §4 |

The 95th-percentile call is the survey's alone. The LZ/PZ panel's slope tile keeps judging the steepest slope at the landing doghouse;
the two stay apart for now (§11, R7).

### 3.4 Status, sign-off and publishing

`draft` → `in_review` → `published`, and back to `draft` by any edit to a published survey (its last published revision stays the one
readers see until the next is published). **Publishing records a sign-off** (the owner: someone will sign off): the approver's name,
rank, title and date, entered by the admin who publishes, printed on the page and kept in the revision. The approver need not have an
account (§11, R2). Each publish makes a revision (REV n), generates its CHANGES line from the difference with the last published revision
("Grid refined; elevation, size and slope from LZ/PZ; new photos"), and prints its PDF once (§5).

---

## 4. The LiDAR: what stands on and around the LZ (version 1)

Every streamed point already carries its **height above ground** (`lidar/pieces.py`, `heights_above_ground`, measured from the survey's
ground returns), and the pieces are cached on the server (`LIDAR_STREAM_CACHE_DIR`). From them, for the survey's LZ:

- **The approach and departure clearance profile** (replacing the field survey's hand-drawn box, "100 ft at 200 m"): along the best
  approach and the best exit, a corridor 30 m each side of the line out to 1,000 m (§11, R5), the tallest point above ground in each 10 m along it, drawn as a
  treetop line over the ground profile, with the angle from the LZ's edge that clears the tallest obstacle with no margin, and that obstacle's height and distance (no margin
  until doctrine gives one).
- **Obstacles in and round the LZ:** the tallest point inside the boundary and within 100 m of it, with bearing, distance and height;
  points standing more than 2 m inside the boundary counted and drawn, and trees found one by one where there are some
  (`filters.litree`, tried on Ellijay hardwood: AGENTS.md §13). This is how "tall dead trees in centre of LZ" can be measured rather
  than typed.
- **On the editor's map**, a tree-height layer (the tallest point above ground in each 2 m cell), like the slope map, for the admin to
  read; not on the page in version 1.

Rules for every number from the LiDAR, printed with it:

- **It names its survey and year** (`GA_Statewide_B3_2018`): trees have grown since and dead ones fall. The field survey's own profile is
  kept in the editor for comparison.
- **Wires, poles and towers are not reliably in it** (about 3 points per m², and 3DEP rarely classifies wires): the page never says
  "no obstacles", only "tallest obstacle found in the LiDAR".
- **Treetops read low** by up to a metre (the laser seldom hits the very top).
- A survey from before about 2015 classifies ground only; there, and where no survey covers the LZ, the page says so and prints no
  obstacle numbers (AGENTS.md §14: never invent a value for no data).

**New server code:** read the finest cached pieces over a box (or fetch them as the 3D view does), keep each point's position and height
above ground, and work out the three products above. It runs in the API as a heavy request (`requestPriority`), never at page load.

---

## 5. The page and the book

Printed as the mission brief is (`backend/brief/`): the server compiles a **survey model** from the survey (or a revision), draws the
figures, lays the page out as HTML and has Gotenberg print it (`GOTENBERG_URL`, AGENTS.md §9). One page, US Letter, to the draft's layout:

- **Header:** HLZ ID and book, the date, the page number in the book, DRAFT (until published) or the revision, the LZ's name; UNCLASSIFIED
  top and bottom.
- **Facts:** grid, lat/long, PPR; best approach, best exit, LZ axis, max A/C, elevation; hazards (the computed slope call first, then the
  field's); surface, slope at the best approach, altimeter source; the numbered notes; a compass rose with the boundary's shape, its size
  and area.
- **Plan:** satellite tiles stitched as the brief's figures are (`brief/figures.base_image`), the slope map at the person's opacity, the
  contours with their labels, the boundary dashed, the approach and exit arrows, the name, lat/long ticks and a scale in metres.
- **3D:** the admin's capture (below), captioned with the heading it looks down.
- **Photos:** the field's north and best-approach photos with their dates.
- **Profiles:** along the best approach and across it, as the web's `SlopeProfile.jsx` draws them, with the LiDAR's treetop line (§4).
- **Tables:** the terrain table (average, across, steepest, 95th percentile, rise through the LZ, the DEM and its resolution) and the
  approach clearance (§4).
- **Footer:** the CHANGES line, the EZ/PZ version and date, the sign-off.

The figures are drawn on the server from the snapshot, never screenshots of the editor, so the page is the same whoever prints it. The
profile is drawn in Python to the rules `contracts/fixtures/planning/profile.json` already pins, so it picks the same line the web does.

**The 3D capture:** the server has no GPU. In the editor, *Open 3D* opens the existing 3D window (`feature/viewer3d`) framed down
the best approach; *Capture* renders one frame at 1600 × 1000 and uploads it (`view_3d`), with its camera's heading, tilt and range and the
tileset's survey and year. Cesium must draw with `preserveDrawingBuffer` for the capture, and its imagery is fetched with CORS (Mapbox
allows it), or the canvas cannot be read. A server-side render of the point cloud, so the book can be regenerated unattended, is later.

**The book:** a cover (the book's name, the unit, the date, UNCLASSIFIED), an index of the LZs with their pages and an overview map, then
every published survey's latest revision in page order, numbered "n OF N". Printed on request and kept until a survey in it changes.

---

## 6. Importing the existing book

A script run once on the owner's Mac, `tools/import_survey_book.py`, reads the 44-page PDF with poppler (`pdftotext -layout` per page for
the labelled fields, `pdfimages` for the two photos), and writes a bundle: one JSON per page and its photos. **No PDF library is added to
the backend.** The editor's *Import* takes the bundle and makes a draft per page, with every field it could not read listed for review
and the grid and lat/long checked against each other (§2). For each draft the server then measures in the background, one at a time:
the boundary from the model at the grid (SAM, as *Analyze* does), the snapshot, and the obstacles. A boundary the model finds poorly is
the admin's to draw. 44 analyses take about 10 minutes.

---

## 7. Who may do what

| | Read published surveys and the book | Read drafts | Create, edit, measure, import, publish |
|---|---|---|---|
| Admins and super-admins | always | always | yes |
| GA ARNG aviators: accounts with `lz_surveys` ticked (§11, R1) | yes | no | no |
| Everyone else | no | no | no |

Every route checks on the server (`entitlements.is_admin` for an admin; for a reader, the feature key `lz_surveys`, in
`entitlements.DEFAULT_OFF` and ticked by an admin, with a bulk tick in the admin dashboard); the web hides what a person may not do, and
nothing else. Once organizations exist, membership of a GA ARNG organization replaces the tick.

---

## 8. Code layout

| | |
|---|---|
| Server | `backend/models.py` (four tables), `backend/routes/survey_routes.py` (the API below), `backend/survey/` (`assets.py` the store, `measure.py` the snapshot, `obstacles.py` §4, `compile.py` the model, `figures.py`, `pages.py`, `templates/survey.html`, `book.py`) |
| API | `GET /api/surveys` (readers: published; admins: all), `GET/PATCH/POST /api/surveys[/<uuid>]`, `POST …/measure`, `POST …/assets`, `GET …/assets/<id>` (`Cache-Control: private`), `POST …/submit`, `POST …/publish`, `GET …/pdf[?rev=]`, `GET/PATCH /api/survey-book`, `GET /api/survey-book/pdf`, `POST /api/survey-book/import`. Web only: **not in `contracts/openapi.yaml`**, so the API version does not move |
| Web | `feature/surveys/`: `SurveyBookPage` (the list), `SurveyEditor` (the form, the map in survey mode reusing the LZ/PZ editors and terrain layers, the 3D window with *Capture*, the PDF preview, the disagreements panel, revisions and publish), `SurveyImport`, `useSurveys` |
| Tools | `tools/import_survey_book.py` |
| Tests | the model held to a fixture made from DONOVAN (`contracts/fixtures/surveys/`), the 95th-percentile call, the obstacle products over a recorded piece, the routes' permissions, the asset store's EXIF stripping and keys, the importer over a generated two-page book |

---

## 9. Steps

Each step is built, reviewed independently and committed with its tests, as the brief's and the Android screens' were.

| Step | What | Ends in |
|---|---|---|
| L1 | Tables, the asset store, the routes with their permissions, the snapshot (elevation from the DEM, size, axis, the 95th percentile at the best approach), the book list and the editor (form, map, measure, disagreements, photos) | An admin makes and edits a survey on the web |
| L2 | The import script and *Import* | The 44 pages are drafts with their photos and measurements |
| L3 | The 3D capture | A survey has its 3D view |
| L4 | The obstacles (§4) | The clearance profile and obstacles in the editor |
| L5 | The page's PDF, the preview, sign-off, publishing and revisions; readers' access (`lz_surveys`, the bulk tick) | A signed page readers can open |
| L6 | The book's PDF | The whole book printed |
| Later | S3; a server-side 3D render; wider readers once organizations exist (`docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md`, Part D) | |

---

## 10. The owner's answers (2026-10-26)

1. Readers: all aviators in the GA ARNG.
2. The survey book, its photos, PPR numbers and names are approved for EZ/PZ (unclassified, no CUI).
3. Where field and computed values differ, the computed value is printed.
4. The slope call uses the 95th percentile.
5. Slope is judged at the best approach heading.
6. Photos and files on the VM's disk for now, an S3 bucket later.
7. The 3D image is captured by hand for now.
8. Someone will most likely sign off. One book for all the unit's LZs.
9. Import the existing book once, by a script.
10. LiDAR obstacles are in version 1.
11. Not on Android (or iOS): web only for now, always reachable by admins and super-admins.

---

## 11. Decided (2026-10-26)

The owner took the recommended answer to each (2026-10-26).

| # | Question | Decided |
|---|---|---|
| R1 | How EZ/PZ knows who is a GA ARNG aviator (accounts are cleared by any `.mil` address, which does not say which unit) | A feature key `lz_surveys`, off by default (`entitlements.DEFAULT_OFF`), ticked by an admin, with a bulk tick in the admin dashboard; replaced by membership of a GA ARNG organization once organizations exist |
| R2 | The sign-off | Recorded by the publishing admin: approver's name, rank, title and date, printed; the approver need not have an account, and the publishing admin may be the approver |
| R3 | Which values "computed" covers | The measured ones only (grid, lat/long, elevation, size, axis, slope, obstacles); max A/C, best approach and exit, surface, hazards and remarks stay the admin's, from the field |
| R4 | Elevation | The touchdown point's height in the slope's DEM, not SRTM30 (§2) |
| R5 | The approach corridor and the clearance | 30 m each side of the approach line, out to 1,000 m; print the angle that clears the tallest obstacle with no margin and the obstacle's height and distance, until doctrine gives a margin (AGENTS.md §13: do not invent separation values) |
| R6 | Backups | Back up `SURVEY_ASSETS_DIR` off the VM (photos cannot be measured again), and watch the disk |
| R7 | The LZ/PZ panel's slope tile judges the steepest slope; the survey the 95th percentile | Keep them apart for now; bring the panel to the 95th percentile on the web first if wanted |
