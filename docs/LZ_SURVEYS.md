# LZ surveys — architecture and plan

Status: **every step built** (2026-10-26: a survey is made, edited, measured and photographed on the web, then sent for review,
signed off and published as a one-page PDF that readers open; the old book's 44 pages are imported as drafts and measured in the
background; the whole book prints as one PDF; what stands on and round each LZ is measured from the LiDAR; and the page's 3D view is
captured in the editor; §12 to §17 say what each settled that the plan did not). **Books are many** (the owner, 2026-10-26, §11 R8): a
book may be a team's, its owner and admins edit it and its members read it. What is later is §9's last row. The owner's answers of 2026-10-26 are in §10, and the decisions taken on the same day are in §11; nothing is open. This file is
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
- **Grid:** the book's lat/long for DONOVAN is 527 m from its own grid. The import (§6) flags every page whose two disagree by
  more than 50 m; the computed grid is the LZ's own (its touchdown point), and its lat/long is worked out from it.

---

## 3. What a survey holds

### 3.1 Records (Postgres, new tables; web only, so none of them syncs)

| Table | Holds |
|---|---|
| `survey_book` | A book (one per unit or team, §11 R8): its name ("AASF 1 HLZ SURVEY BOOK"), the unit line, the team it is for (or none), the page order, the cover's text; archived, never deleted |
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
- Keys are the asset's SHA-256, so a file is written once and never changed in place; a file nothing refers to is removed by a sweep
  (not built). A book's PDF is its book's alone, and is let go when the book is printed again (§15).
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

- **The approach clearance profile** (replacing the field survey's hand-drawn box, "100 ft at 200 m"): along the best approach, from
  the touchdown point, a lane 10 m each side of the track out to 1,000 m (§11, R5), the highest thing in the way in each 10 m along it
  drawn over the track's ground, with the shallowest angle that clears everything in the way with no margin (no margin until doctrine
  gives one) and what sets it; the approach planned at 30° where that clears it, else at the shallowest angle and said to be steeper,
  with where its descent begins from the survey's own height above the highest obstacle (100 ft where it gives none; the owner's,
  2026-10-27; §16). The editor may type the approach's angle in place of the plan, with a reason: the page prints it as the editor's.
  The exit's angle is neither measured nor shown (the owner, 2026-10-27); the best exit itself, the departure heading, stays the
  field's (R3), typed in the form and printed in the facts and on the plan (§12, §16).
- **Obstacles in and round the LZ:** the tallest point inside the boundary and within 100 m of it, with bearing, distance and height;
  points standing more than 2 m inside the boundary counted and drawn, and trees found one by one where there are some
  (built as a local-maximum finder over the canopy's 1 m grid, §16; `filters.litree` was tried on Ellijay hardwood: AGENTS.md §13). This
  is how "tall dead trees in centre of LZ" can be measured rather than typed.
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
above ground, and work out the three products above. It runs in the API as a heavy request, never at page load: the web marks Measure
heavy where it calls it (`surveyApi.heavy`), not in `requestPriority`'s list, which the native apps are held to and a web-only route has
no place in.

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
the best approach; *Capture* renders one frame at the page's shape (1600 × 887, §17) and uploads it (`view_3d`), with its camera's heading, tilt and range and the
tileset's survey and year. Cesium must draw with `preserveDrawingBuffer` for the capture, and its imagery is fetched with CORS (Mapbox
allows it), or the canvas cannot be read. A server-side render of the point cloud, so the book can be regenerated unattended, is later.

**The book:** a cover (the book's name, the unit, the date, UNCLASSIFIED), an index of the LZs with their pages and an overview map, then
every published survey's latest revision in page order, numbered "n OF N". Printed on request and kept until a survey in it changes
(§15).

---

## 6. Importing the existing book

A script run once on the owner's Mac, `tools/import_survey_book.py`, reads the 44-page PDF with poppler's `pdftohtml -xml` (every piece
of text and every picture, with its place on the page) and writes a bundle: `manifest.json` and the photos. **No PDF library is added to
the backend.** The book's *Import the old book* takes the bundle and makes a draft per page, with every field it could not read listed for
review and the grid and lat/long checked against each other (§2). For each draft the server then measures in the background, one at a
time: the boundary from the model at the grid (SAM, as *Analyze* does), then the snapshot; the obstacles join it with L4. A boundary the
model finds poorly is the admin's to draw. §14 says how it was built.

---

## 7. Who may do what

Books are many, and a book may be for a team (the owner, 2026-10-26, §11 R8). Everyone but a site admin needs `lz_surveys` (§11 R1).

| | Read a book's published pages | Read its drafts | Create, edit, measure, import, publish in it | Make a book |
|---|---|---|---|---|
| Site admins and the super-admin | every book | every book | every book | for any team, or for none |
| A team's owner and admins, with `lz_surveys` | their team's, and books for no team | their team's | their team's | for their team |
| A team's members, with `lz_surveys` | their team's, and books for no team | no | no | no |
| Anyone else with `lz_surveys` | books for no team | no | no | no |
| Without `lz_surveys` | no | no | no | no |

Only a site admin gives a book to another team, or to none (that changes who reads it), and restores an archived book. Deleting a team
archives its books: a book is never handed to everyone because its team went. Every route checks on the server (`survey/access.py`);
the web hides what a person may not do, and nothing else. Once organizations exist, an organization takes a team's place.

---

## 8. Code layout

| | |
|---|---|
| Server | `backend/models.py` (six tables: the four of §3.1, `lz_survey_import`, §14, and `survey_book_print`, §15), `backend/routes/survey_routes.py` (the API below), `backend/survey/` (`assets.py` the store, `measure.py` the snapshot, `obstacles.py` §4, `compile.py` the model, `figures.py`, `pages.py`, `templates/survey.html`, `importing.py` the import, `book.py` the book's PDF; the page's template in three parts, `survey_style.css`, `_survey_sheet.html` and `survey.html`, so `book.html` prints every sheet as it prints alone) |
| API | `GET/POST /api/survey-books`, `GET/PATCH /api/survey-books/<uuid>` (a reader sees the published pages), `POST …/archive`, `…/restore`, `GET/POST /api/survey-books/<uuid>/print` (how the book's PDF stands; print it), `GET …/pdf` (the book's PDF), `POST …/import` (the old book's bundle); `POST /api/surveys`, `GET/PATCH /api/surveys/<uuid>`, `POST …/archive`, `…/restore`, `…/measure`, `…/submit`, `…/draft`, `…/publish`, `GET …/pdf[?rev=]`, `GET …/page[?rev=]` (the page as HTML, without Gotenberg), `POST …/assets`, `GET/PATCH/DELETE …/assets/<id>`, `POST …/import/checked` (`Cache-Control: private`). Web only: **not in `contracts/openapi.yaml`**, so the API version does not move |
| Web | `feature/surveys/`: `SurveyBookPage` (the list), `SurveyEditor` (the form, the map in survey mode reusing the LZ/PZ editors and terrain layers, the 3D window with *Capture*, the PDF preview, the disagreements panel, revisions and publish), `SurveyImport`, `useSurveys` |
| Tools | `tools/import_survey_book.py` |
| Tests | the model held to a fixture made from DONOVAN (`contracts/fixtures/surveys/`), the 95th-percentile call, the obstacle products over a recorded piece, the routes' permissions, the asset store's EXIF stripping and keys, the importer over two of the real book's pages as poppler lays them out |

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
| R5 | The approach corridor and the clearance | 30 m each side of the approach line, out to 1,000 m; print the angle that clears the tallest obstacle with no margin and the obstacle's height and distance, until doctrine gives a margin (AGENTS.md §13: do not invent separation values). **Decided again 2026-10-27**, after the old book showed the edge's angle unusable (§16): from the touchdown point along a lane 10 m each side of the track, out to 1,000 m, what stands 5 m or more (2 m built) and the ground where it rises; print the shallowest angle that clears it with no margin and where a descent from 100 ft AGL must begin to fly it, flag what stands over 100 ft past that, and let the editor type the angle over the LiDAR's, with a reason, printed as theirs. **And again later that day**: the 100 ft over the highest obstacle, not the ground (in the old book's timber an approach at 100 ft AGL was at or under the canopy on every LZ); then each way planned at 30° where what stands allows it, else at the shallowest clear angle, said to be steeper, the shallowest shown beside it, descending from each survey's own profile height above the highest obstacle (75-150 ft in the old book; 100 ft where it gives none); the editor's angle is typed in place of the plan; and the exit's lane and angle neither measured nor shown (the best exit, the departure heading, stays the field's: R3) |
| R6 | Backups | Back up `SURVEY_ASSETS_DIR` off the VM (photos cannot be measured again), and watch the disk |
| R7 | The LZ/PZ panel's slope tile judges the steepest slope; the survey the 95th percentile | Keep them apart for now; bring the panel to the 95th percentile on the web first if wanted |
| R8 | One book, or many (asked while L5 was built) | **Many**: a book may be for a team (an organization's, once they exist). A team's owner and admins make, edit and publish its books; its members with `lz_surveys` read them; a book for no team is read by everyone with the key. Site admins do everything (§7) |
| R9 | The altimeter source on the page | Left off: it does not apply (the owner, 2026-10-26) |

---

## 12. L1 as built (2026-10-26)

What L1 had to decide that the plan did not, each with its reason. The code is `backend/survey/`, `backend/routes/survey_routes.py` and
`frontend/src/feature/surveys/`.

- **The editor's map is a map of its own**, not the planning map in a mode: a react-leaflet map in the survey window that reuses the
  planning map's slope layer (`SlopeLayer`, raster and contours, at the person's own opacity), its base maps and the slope profile
  (`SlopeProfile`). A survey's LZ is then never one of the session's LZ/PZs, so the planning tools' Save, Ctrl/⌘ S, the Library and
  Mission Packs can never take it. Its diagram is still the web's schema-2 document, so it opens in the LZ/PZ tools as it is.
- **Best approach and exit** are the field's (R3) and typed in the form; the map draws them as arrows (into the target along the
  approach, out along the exit), and the saved diagram carries them as its landing and takeoff doghouses, with the flight data's
  headings, so the document says the same. **Both headings stay** (the owner, 2026-10-27: the departure heading is needed): what
  went that day is the exit's LiDAR angle (§16), never the best exit.
- **The boundary** is found with the model at the target (`/api/analyze-field`, as Analyze does) or drawn a corner at a click; either
  becomes `analysis.detectedLZ`, the one measured.
- **Measure works on the saved survey**: the editor saves first. Without `apply` the server answers the new snapshot and what it changes
  and keeps nothing; with `apply` (and If-Match) it measures again and keeps it. Measuring twice costs a few seconds and keeps the server
  from ever storing a snapshot the client sent.
- **Every save names its revision** (`If-Match` is required, 428 without; 409 with the server's copy on a stale one). The editor keeps
  what was typed and offers *Save mine over theirs* or *Load theirs*, naming what the other admin changed.
- **Field values are text**, as the book writes them; a number is read out of the text only to compare it. A field key the server does
  not know is refused, so the web's list (`surveyFields.js`) and the server's (`survey/fields.py`) cannot drift; both are held to
  `contracts/fixtures/surveys/donovan.json`.
- **Disagreements are flagged past** 50 m for the grid or lat/long against the target (the import's own check, §6), 25 ft of elevation,
  2° of slope (the field's "roll" or "cross" against the cross slope, "nose up/down" against those, else the steepest of the three) and
  10% of size. These are review flags, not limits. A field value that cannot be read is a disagreement. *Accept computed* needs no
  reason; *Print survey's* does. A choice is kept with the two values it was made on and stops settling the disagreement when either
  changes, so measuring again or retyping a value puts it back in front of an admin.
- **Photos do not move the survey's revision**: an upload, a caption or taking one off is its own request and never conflicts with a
  form being typed. A survey's asset ids are its asset rows not taken off (`removed_at`), in upload order.
- **The snapshot keeps the slope map, contours and profiles** (`terrain`, as `/api/terrain-analysis` answers them less the table by
  heading), so the editor draws them without measuring again; tens to a few hundred KB a survey.
- **Headings are the DEM grid's**: over a local DEM the 95th percentile is taken along the UTM grid's north, turned up to ~1.7° from true
  in north Georgia (AGENTS.md §15, *True or magnetic*); the same as `byHeading`.
- **Status** exists (`draft`, `in_review`, `published`) but L1 only ever makes a draft, and any change to a survey under review or
  published makes it a draft again, ready for L5.
- **The book** is made on first use, named `HLZ SURVEY BOOK` (renamed from the list), its page order the order surveys were made in.
- **The window** opens from the account menu for admins only; the server refuses everyone else (403 `admin_required`) on every route.

---

## 13. L5 as built (2026-10-26)

What L5 had to decide that the plan did not, each with its reason. The code is `backend/survey/` (`access.py`, `compile.py`, `figures.py`,
`pages.py`, `profile.py`, `changes.py`, `templates/survey.html`), `backend/routes/survey_routes.py`, and `frontend/src/feature/surveys/`
(`BooksPage`, `SurveyBookPage`, `SurveyReader`, `Publishing`, `PagePreview`).

- **The page is your LZ DONOVAN draft's layout** (§1), printed by Gotenberg as the brief is, one Letter page with its own UNCLASSIFIED
  lines, header and footer (Chromium's are not used). Each measured value says where it came from, a small **LZ/PZ** or **FIELD** tag
  beside its label (§2: the page never hides which it printed). A draft and a survey under review carry a DRAFT or IN REVIEW mark, in
  the header and faintly across the page.
- **The preview is the same page as HTML** (`GET …/page`), its pictures in it, shown in a frame that runs nothing (sandboxed, and a
  `Content-Security-Policy` of `default-src 'none'`). So the page can be read where the server has no Gotenberg yet; the PDF answers
  503 `pdf_unavailable` there, in words.
- **Publishing is from review**: a measured draft is sent for review (`submit`), and a survey under review is signed off and published
  (`publish`, If-Match): REV n keeps the field values, choices, diagram, snapshot, photo ids, the sign-off and the CHANGES line, and
  what the page said of itself (`page`: HLZ ID, name, book, page n of N, the date, the EZ/PZ version, the publisher's slope opacity), so a
  reprint matches what was signed. Its PDF is printed once, at publishing where Gotenberg is there, else when first asked for, and kept
  in the asset store under its SHA-256. An editor's PDF of the survey as it is now is printed each time and kept nowhere.
- **The CHANGES line is suggested** (`changes.py`): REV 1 against the old book (*New format. Grid refined; lat/long recomputed;
  elevation, size and slope from LZ/PZ.*), a later one against the revision before (*Max A/C and surface changed; new photos.*); the
  publisher can edit it.
- **The slope opacity** is the publisher's (the browser's `ezpz.slopeOpacity`), kept with the revision; a draft's preview takes the
  viewer's. **The profiles** are drawn in Python from `profile.py`, the web's `groundProfile.js` ported and held to
  `contracts/fixtures/planning/profile.json`; the slope table gives the 95th percentile, average and steepest cell of each way (nose up,
  nose down, cross, any way) at the best approach, with the UH-60 limits.
- **Maximum weight** is a field of its own (`maxWeight`): the old book has one, and the draft printed it as a note.
- **Readers** see, in each book open to them, the published surveys only, each on its latest revision's page (any revision can be
  chosen), and its PDF. A survey being edited after it was published is a draft its readers never see; the last REV stays theirs.
- **The admin dashboard's users list ticks people** and gives a feature to all of them, or takes it away (an "off" is never stored for a
  feature not launched yet). `lz_surveys` is never launched to everyone by taking it out of `DEFAULT_OFF`: that would open every book
  for no team to every account, and let every team's owner and admins edit and sign off its pages.

What L5's independent review found, and what now holds (each with a test, `backend/tests/test_survey_revisions.py`):

- **A snapshot says what it was measured for** (version 2: the target, the boundary and the best approach), and a survey whose target
  moved, boundary changed or approach turned since is **measured again before it is sent for review or published** (409
  `measure_again`, with the reasons). Until then its page says so, and its slope line, table and profiles are labelled with the heading
  they were measured at, never the approach typed since: measured at 090° and turned to 270°, the page once printed "Slope at 270°" over
  numbers from 090°, hiding an 8° nose-down slope over the 6° limit. A snapshot from L1 is measured again once.
- **A revision keeps its pictures as they were signed** (`page.assets`: each one's kind, caption, heading and date). A caption changed
  for the next revision never reaches a reader's REV n.
- **A photo added, changed or taken off a survey under review or published makes it a draft again**, with a new revision, as any other
  change does; its answer carries the survey's status and revision, which the editor takes only if it held the revision before.
- **A survey's revision is checked as it is written** (`LzSurvey`'s version counter), not only when a request begins: a save that lands
  while another request measures for seconds is not undone by it (the measurement answers 409 with the server's copy). A book's writers
  take its row first.
- **A reader is not told that a draft or an archived survey exists** (404, where an editor's route once answered 403), and a reader's
  book carries no revision or last change, which move with the drafts; it says when the book last published.

---

## 14. L2 as built (2026-10-26)

What the import settled that the plan did not. The code is `tools/import_survey_book.py`, `backend/survey/importing.py`, the two
routes in `backend/routes/survey_routes.py`, and `frontend/src/feature/surveys/SurveyImport.jsx`.

- **One poppler tool, `pdftohtml -xml`**, rather than `pdftotext -layout` and `pdfimages`: it gives every piece of text and every
  picture with its place on the page, which the form needs. Each line is rebuilt from its pieces (the book writes "OCT 202" and "3" in
  two fonts, and a raised "th" one point above its "5"), each value is the text after its label up to the next label (the footer's too,
  so a label the importer does not know is said, never read into the date before it), a number keeps its thousands ("1,850 ft msl" is
  1850), the photos are the first two of the page's pictures, top to bottom, of at least 40,000 square points (north first; the form's own
  small images are far smaller), and the approach photo looks down its "View 290°" caption, else the best approach. What a page does not
  have is named (`unread`, a survey profile with no title of its own included), a label with nothing after it is `blank`, and anything
  to check is a warning (a Max A/C that is not a number by day and night, kept as written: "**HOIST ONLY**", "N/A"). Nothing is made up.
  The tests run over two real pages saved as poppler writes them (`tools/fixtures/survey_book_pages.xml`), so they need no poppler.
- **The bundle** is one zip, `manifest.json` (format `ezpz-survey-import`, version 1) and `photos/`: 14 MB for the 44 pages. The server
  takes it as input from anyone: 100 MB at most (Cloudflare's limit, a request with no length held to it as it is read), 400 MB
  inflated, 2,000 files, 20 MB a photo, read without extracting anything, only the photos the manifest names, one at a time. Each page
  is held to the editor's rules (its values, its name and HLZ ID once in capitals, its photos' dates): what they would refuse, and a
  photo that is damaged in the bundle, is left out and said in the page's warnings, so one odd page never stops the book; only a bundle
  that is no bundle, and an entry that is no page, are refused. Everything slow (the upload, the bundle, every photo made ready) is done
  before the book is locked, so a large import holds nothing others wait on.
- **A draft per page, in page order**, with its photos re-encoded as any upload is (no metadata), its target at the book's grid (the
  centre of the square the grid names), and an LZ/PZ diagram that is the web's own document (`contracts/fixtures/surveys/imported_diagram.json`,
  held both sides: the web's `serializeLzDiagram` gives it back unchanged). A page of the same file imported into the book before, a
  page the bundle has twice, and a page whose HLZ ID the book has (archived or not) or the bundle has on an earlier page, are left as
  they are, so importing twice, or again after an answer that was lost, adds nothing.
- **Grid against lat/long** (§2): a page whose two are more than 50 m apart says so, and the target stays at the grid. The lat/long is
  read as the editor reads one (degrees and decimal minutes, degrees, minutes and seconds, or decimal degrees), and one that cannot be read
  is said, never passed over. Of the 44: LZ
  DONOVAN 527 m (not the 460 m first estimated), LZ RICO 97 m, LZ ROCK 77 m. A page with no grid to read is placed at its lat/long, with a
  note; with neither, it has no target, and says to set one.
- **Measuring in the background** (`Measurer`): one thread, one survey at a time, in the order imported: SAM's boundary at the target (the
  analysis's own code, `terrain_routes.find_lz_boundary`), then the snapshot, each written only if no editor saved the survey meanwhile
  (else the survey says it changed while measured, and the editor's work stands), in the name "EZ/PZ (import)", not the importer's, who
  did not save it then. A survey open in the editor while it is measured is read again every 5 s, and what the measuring wrote comes in
  under what was typed (`rebaseDraft`), so its next save is not refused as someone else's. The imagery the model reads and the elevation
  service give up after 30 s of silence, and the thread outlives a database that goes away (that survey waits for the book's next look).
  How each stands is on its import record
  (`lz_survey_import`: queued, finding, measuring, done, no target, no boundary, failed, changed), kept apart from the survey so writing it
  never moves the survey's revision; the book's list reads itself again every 5 s while any is waiting. A restart loses the thread, and the
  book's next look queues again what was waiting. Tried against a scratch server with the real model: three of the book's pages imported
  and measured within seconds (SAM about 0.4 s a page once loaded).
- **The notes stay with the survey** ("From the old book" in the editor, and a count on the book's list) until an editor marks them
  checked, which keeps them and stops counting them; it is not a change to the survey.

---

## 15. L6 as built (2026-10-26)

What the book's PDF settled that the plan did not. The code is `backend/survey/book.py`, `pages.build_book`, `figures.overview`,
`templates/book.html` (with the page's own sheet, `_survey_sheet.html`), the book's routes in `backend/routes/survey_routes.py`, and
`frontend/src/feature/surveys/SurveyBookPage.jsx`'s *The book's PDF*.

- **What it holds**: a cover (the book's name and unit line, how many LZ surveys, the date printed, the cover text, and a topographic map
  of every LZ with its page number beside it), the index (page, HLZ ID, name, grid, revision, when published and who signed it off, 34
  to a sheet, each sheet with its own UNCLASSIFIED lines), then every published survey's latest revision in the book's order, each page
  as it prints alone (its revision, sign-off and date as signed) but numbered *n OF N* in the book and named for the book as it is now
  (a survey moved from another book prints as this book's page). Drafts and archived surveys are never in it. A page's own PDF still
  says where it was in the book when it was published. An archived book is not printed.
- **Laid out to its limits**: the cover is a column between its top line and its foot, the map taking what the header and the text
  leave (3 in at least), the name and unit line kept to their lines, the cover text to seven lines (past them it is cut, and the cover
  says the rest is in the book's details); nothing is drawn under the foot or the bottom UNCLASSIFIED line. An index row is one line
  whatever it holds, so 34 to a sheet are always all of them. The sheet's own header (L5's) keeps a long name or HLZ ID to its lines, and
  the plan's name label to the picture.
- **Who prints it**: everyone who reads the book, as it holds only what is published. One request asks for it (`POST …/print`), and
  the server prints it in the background (`Printer`: one thread, one book at a time), because its pages fetch their maps' tiles and
  Chromium prints dozens of them, longer than a request may wait behind Cloudflare's 100 seconds. The page asks how it stands every 3 s
  while it prints, and says how far it has got.
- **Kept until a page changes**: a print is named by a fingerprint of what it holds (the cover's words, every page's survey and revision
  in order, the EZ/PZ version and the layout), so a page published again, moved, archived or added makes the PDF out of date (the page
  offers to print it again), and asking again for the book as it stands changes nothing. The PDF is kept in the asset store and sent
  from the disk; a newer print lets go of the book's older ones and their files. A print whose file has gone (a redeploy with no
  `SURVEY_ASSETS_DIR`) is printed again when asked, one printed meanwhile by a print asked for earlier is never printed twice, and a
  later failure never hides a print that is there.
- **Pictures are printed at the page's size**: a photo or 3D capture goes to the printer at most 1,200 px on its long side (300 dpi and
  more in its slot), where its kept 2,400 px passed through Chromium whole. Measured with 44 pages of the worst kind of picture for a
  JPEG: 22.6 MB and 4.3 s in Chrome, where it had been 64.6 MB and 17.6 s. Gotenberg ends a request after 30 s unless told otherwise,
  so it is started with `--api-timeout=140s` (AGENTS.md §9), under the API's 150 s wait.
- **Without Gotenberg** it says the book's PDF is not printed on this server; a restart that lost a print's thread queues it again at
  the book's next look. A print that fails in a way nobody foresaw is marked failed, never left printing, or every look (every 3 s while
  the page polls) would print the whole book again; one a restart left for a book that has moved on since is let go when the book next
  prints. Printed through a stand-in for Gotenberg built on this Mac's Chrome: a three-page book in about 8 seconds, five Letter pages and
  no blank one.
- **A signed page never says to measure it again**: a revision's snapshot may predate snapshots that say what they were measured for,
  and the stale note is for a page still being made, never one signed (found printing the scratch book).


## 16. L4 as built (2026-10-26)

What the LiDAR's obstacles settled that the plan did not. The code is `backend/survey/obstacles.py`, `lidar/stream.py`'s `laz`, the
snapshot's `obstacles` (`measure.py`), the page's `clearance` (`compile.py`, `figures.clearance`, the sheet's LIDAR box), the comparison's
`approach` (`compare.py`), and on the web `Obstacles.jsx`, the angle's dialog (`Disagreements.jsx`) and the survey map's height layer.

- **Part of Measure, not a step of its own**: the obstacles are measured with the rest of the snapshot (and by the import's background
  measuring), so one snapshot says what one target, boundary and best approach were measured for. The snapshot kept the best exit
  (version 3), was version 4 after the review's fixes below (a 3's angle from the edge could be a return's scatter), and is version 5
  since the angle is from the touchdown point (a 4's, from the edge, read 87-90° on every LZ of the old book): a survey measured before
  any of them is measured again before review or publishing, and its page meanwhile says it was measured by an earlier EZ/PZ rather than
  print an angle from the edge as one from the touchdown point. The approach's plan (below) is worked out from a 5's steps, so the plan
  needed no measuring again. **The exit's angle is neither measured nor shown** (the owner, 2026-10-27): its lane is not read, a
  snapshot keeps no best exit, and a survey whose best exit changes is not measured again for it; a 5 measured before keeps its
  exit's lane, which nothing reads. **The best exit itself stays** (the owner, the same day: the departure heading is needed): the
  field's, typed in the form, drawn on the editor's map and as the takeoff doghouse, and printed in the facts and on the plan.
- **Read from USGS's own pieces**: the streamed `.pnts` keep neither a point's class nor its height above the ground, so the survey the
  3D view streams for the place is read as USGS publishes it (LAZ), every piece of the octree that meets what is measured, at every
  level, eight at a time, each kept beside the streamed pieces (`<key>/laz/`, never served, let go of with them when the cache is
  pruned): Measure's preview and its keeping read the second time from the disk. Where the server streams nothing
  (`LIDAR_STREAM_CACHE_DIR` unset) the snapshot says so and the field's profile is printed. **A reading is bounded**: each piece is read
  as it comes and let go of; the first that cannot be fetched or read ends it (one that will not decode is let go of from the cache, and
  only bytes whose LAS header reads are ever kept); past `MAX_POINTS` (6 million returns, a 1 km LZ and its corridors) it is refused in
  words; past `FETCH_DEADLINE_S` (60 s) it is a measure to try again, what came being kept. An index USGS publishes unreadable is said so,
  not called too fine or offered again. A 1 km LZ's 6 million returns measure in about 5 s with half a gigabyte (the ground's triangles
  on its lowest return in each 2 m, asked in Z-order: a metre's took 14 s and a gigabyte).
- **Height above the ground** is the survey's own ground returns, the lowest in each 2 m, between them a triangle at a time (the
  nearest return past the hull, none past 15 m: never made up). What is not ground, noise, water or ignored ground counts as standing,
  unclassified returns included (a survey to 3DEP's base specification may classify no vegetation); one with no other within 3 m is a
  bird or a glint, not something standing (the test's bird at 80 m would otherwise set the approach at 13.2° for its 8.6°), unless the
  survey marks it a wire, a tower or a bridge, whose returns are often further apart. A survey whose name gives no year (KY_FullState, at
  Fort Campbell) is judged by its returns: measured where they hold vegetation or buildings, else said to classify only the ground.
- **An obstacle is what stands 2 m or more above its own ground, and the ground where it rises.** The review proved the first version's
  angles were set by noise: every return past the edge counted, so a ground return a few centimetres up and a centimetre out, or grass,
  read 80-89° on flat open ground. Now grass, a crop or a low bush is none; the ground counts in 10 m squares of the lane, each the
  median of its returns' rise (the triangles' ground under the return less theirs at the touchdown point on its own line across, so a
  cross-slope and the triangles' low lean take nothing from it), where that is more than 0.3 m. Checked: scatter to 15 cm with grass, 0°;
  ground rising 5%, a 20 m ridge, a 20% cross-slope, a wire, each as the arithmetic says. The trees in the LZ leave out buildings, wires,
  towers and bridges, and its tallest and its count of standing points are obstacles only.
- **Tried against the old book (2026-10-27): the first angle could not be used.** The 31 pages of the OCT 2023 book with a survey
  profile and a best approach were read (`tools/import_survey_book.py`), each profile's angle taken as atan(height / distance) from the HLZ
  (the profile draws the distance from there, the landing point), and each approach measured in GA_Statewide_B3_2018 from the book's
  grid. As first built, **from the LZ's edge across a corridor 60 m wide, every LZ read 87-90°**: real LZs are clearings in timber, and a
  corridor that wide whose obstacles are measured by their distance along the track takes the tree line beside the approach path as
  standing at the edge. From the landing point with that corridor: 3 of 31 within 5° of the field's. From the landing point along a lane
  10 m each side of the flight path, counting what stands 5 m or more: 10 of 31 within 5°, 16 within 10°, the median 9.7° apart; where
  the book's grid stands 6 m or more from a tree in the LiDAR (24 LZs), the median 7.3° apart and 4.3° steeper on average (2 m or more
  read 9.3°; 5 m each side, 8.3°). At seven LZs (BIG STAMP, HAWK MOUNTAIN, MERRILL, MOSBY, RICO, SIMMONS, SWING BRIDGE) the grid stands
  0.4-5.7 m from a tree 5 m or taller in the 2018 LiDAR, so any angle from it is near-vertical: cleared since, or the grid is not where
  the aircraft lands. The field's numbers read as estimates (15 of 31 say 100 ft; distances of 50, 75, 100 or 200 m).
- **The angle, as the owner chose it (2026-10-27; §11 R5)**: from the touchdown point along a lane 10 m each side of the track (a
  UH-60's rotor and a little), out to 1,000 m behind the touchdown point (the approach is flown towards it), everything in the lane
  counted, in the LZ or out of it. In the way is what stands 5 m or more above its own ground (the tree line, not the brush round an LZ,
  which the field's surveys leave out too), 2 m for what the survey marks as built (a building, a wire, a tower, a bridge), and the
  ground where it rises. **The LiDAR's angle is the shallowest that clears all of it with no margin**, each thing measured from the
  touchdown point's ground on its own line across the lane. A lane keeps what stands highest in each of its 10 m steps (`bins`: the
  track's ground, and over it the rise as the angle sees it), how many of them the survey has returns in, and why it has no angle where
  it has none (no returns; no ground at the touchdown point).
- **The approach is planned** (`survey/flightpath.py`; the owner's, 2026-10-27, the last of three answers that day): **at 30° where what
  stands allows it**, else at the LiDAR's shallowest clear angle, said to be steeper than 30°; the shallowest is shown beside it. Its
  descent begins **where the path at that angle is the survey's own height above the highest obstacle for good** (`descent_point`): the
  outermost place, going out along the track, where it stands no higher than that over what stands there (the treetops, or the ground
  where nothing stands), between the lane's 10 m steps, so a gap in the trees nearer in does not bring it in (past 1,000 m is said so).
  The height is the survey's profile height (its first number, read as the comparison reads it, over 0: the old book's are 75 to 150
  ft), 100 ft where it gives none. Before it, the descent began where the path at the shallowest angle was 100 ft above the ground (the
  owner's reading of the book's "100 ft at 200 m": 100 ft AGL at the point of initial descent), and what stood more than 100 ft above
  the track's ground past it was flagged: on every LZ of the book, since in its timber an approach at 100 ft AGL is at or under the
  canopy. So the 100 ft went over the treetops, and then the owner asked for each survey's own height and "a safe angle of ideally
  30-ish degrees". The plan is worked out from the lane's steps whenever the page or the editor asks (`approach_plan`, sent with the
  survey), never kept: a height retyped needs no measuring. A 5 measured before it also keeps the first answer's `descent` and `tall`,
  which nothing reads.
- **Run again over the book with the plan** (`flightpath.plan` over each LZ's lane from the book's grid, at each page's own height): of
  the 24 LZs whose grid is clear of trees, 17 are planned at 30°, their descents 50-130 m out (the median 77 m), and 7 steeper (FRYING
  PAN 33.8°, LITTLE SAL 41.0°, MATTISON 51.3°, ORANGE WEST 77.9°, RECOUNT 34.8°, ROCK 58.6°, WINDING STAIR 39.2°); the 7 grids at a tree
  read 82-90°. LZ DONOVAN: 30.0°, its descent from 100 ft above the highest obstacle 51 m out, the LiDAR's shallowest 8.6° (a 57 ft tree
  95 m out) against the book's 8.7°; DEERLICK 30.0° from its 140 ft, 106 m out; LITTLE SAL 41.0°, steeper, from its 125 ft, 57 m out.
  The book's profile ("H ft at D m") is the surveyor's sighting of an obstacle, not a descent point, so its angle is held against the
  LiDAR's shallowest, not the plan.
- **The editor's angle**: an editor may type the approach's angle in place of the plan, in degrees to a tenth (over 0, under 90), with a
  reason (`compare`'s `typed` choice on `approach`, kept with who, when and the two values it was typed against). The page prints it
  marked as the editor's, with the LiDAR's shallowest beside it (EDITOR'S · LIDAR MIN 8.5°), its path and descent from the survey's
  height drawn over the LiDAR's ground; never said to be steeper, which is the trees' to say, not the editor's. Only where the LiDAR has
  an angle (else the field's profile is typed), and once either value changes (the profile retyped, the LZ measured again) it stands no
  longer: the plan is printed and the row is open again, whether or not the two then disagree, since the page no longer prints what the
  editor typed. A choice kept on the approach's obstacle, before its angle, is let go when the editor's next save sends it back.
- **On the page**: the field's survey profile box becomes CLEARANCE · the survey's year · its name (the year first, before a long name
  is cut), its figure the approach across the box's full width, flown from the left to the LZ at the right: its track's ground (thicker
  over the LZ) from the touchdown point, the highest thing in the way in each 10 m (drawn only where something stands above the track's
  ground, never ground rising to one side), the path at the angle planned to its descent (a dot) and the survey's height over what
  stands past it (dashed), and what sets the LiDAR's shallowest angle (a triangle); its words over it, three lines (APCH 360° · 30.0°,
  or APCH 180° · 41.0° · STEEPER THAN 30°; DESCEND FROM 100′ AHO AT 53 M; MIN 8.5°: 98′ TREE AT 200 M, MIN 0.0°: NOTHING IN THE WAY or
  EDITOR'S · LIDAR MIN 8.5°) and, where the survey covers part of the lane, how much in its corner. Then the tallest in the LZ and
  within 100 m, and what the LiDAR cannot see. Something standing over 2 m in the LZ is a hazard line of its own. The profile along the
  best approach has its treetop line: the LiDAR's height above its own ground laid on the ground the profile draws, so a DEM a little
  off the LiDAR's moves no tree; within the LZ's span only what stands in the LZ. **The field's own profile is printed whenever there is
  no LiDAR angle along the approach** (no best approach, no ground at the touchdown point, a lane from an earlier EZ/PZ), with a line
  saying why, as when an editor chooses it, with a reason (`compare`'s `approach`: the field's "100 ft at 200 m" as its angle, 8.7°,
  against the LiDAR's; more than 5° apart is a disagreement). The page's figure prints its words at 5 to 6 pt, and the box ends inside
  its border.
- **In the editor**: the measurements' *Standing on and round the LZ*: the approach's plan (the angle planned, said to be steeper where
  the trees make it so, or *Yours*), where its descent from the survey's height above the highest obstacle begins and how high above the
  ground that is, the LiDAR's shallowest angle and what sets it; the tallest in the LZ and round it, the points over 2 m, the trees.
  With *Type the approach's angle…* (and, once typed, what the page prints and *Print the plan*), the same dialog from the approach's
  row of *Field against computed*, saying what the approach is planned at. The plan is the server's (`approach_plan`), from the survey
  as saved: a profile height typed and not yet saved says to save first. On the survey's map *What stands (LiDAR)*: the tallest in each
  2 m cell round the LZ in four bands, and the tallest in the LZ and within 100 m marked.
- **Tested** against a survey made by the tests (`test_survey_obstacles.py`): an EPT octree of a scene round the Ellijay LZ, its index
  in two pages, served from memory through the stream's own `Streamer`: a tree line 258 m out on the approach, a tower 459 m out the
  other way (164 ft, where the exit's lane was: nothing measured names it), a tree in the LZ, a building outside it, a bird and noise;
  against point clouds made by the tests for each of the review's findings (scatter and grass, rising, ridged and cross-sloping ground,
  a wire, a building in the LZ, a lake, a narrow LZ, a reading that fails, hangs, overflows or meets an unreadable index or piece) and
  for the lane (brush in no one's way, a shed in it from 2 m, a tree in the LZ in it, one beside the lane not, the descent over open
  ground, the canopy, a gap in it and from the survey's own height); the plan against lanes made by the tests
  (`test_survey_flightpath.py`: 30°, steeper, exactly 30°, the editor's, the survey's height as the comparison reads it, steps of
  another size); each test checked to fail without its fix. Every test runs with `LIDAR_STREAM_CACHE_DIR` unset (`tests/conftest.py`),
  so none reaches AWS through a development `.env`. Against the real survey: a cold reading round one of the book's LZs took 3-12 s from
  this Mac.

## 17. L3 as built (2026-10-26)

What the 3D capture settled that the plan did not. The code is `frontend/src/feature/surveys/Survey3D.jsx` (the editor's *3D view*
and its window), `capture3d.js` (the view down the approach, the frame, the camera), `Viewer3D`'s `view` and `capturable`, and on the
server the capture's `pose` (`survey_routes._pose`) and the page's caption (`compile._view3d`).

- **The 3D window is the planning one's** (`feature/viewer3d/Viewer3D`), opened on the LZ's target as the editor's map has it, streamed
  as the planning 3D view streams it, in a dialog at the page's shape. It opens **looking down the best approach**: facing the
  field's best approach, 20° down (the 3D view's own opening is 35° from the north-west), framed as the 3D view frames an LZ; *LZ* flies
  back to that look. Without a best approach it opens as the 3D view does. The admin turns it as they want it.
- **Capture draws one frame at 1,600 × 887, the page's 3D box's shape** (3.79 × 2.10 in, `survey_style.css`'s `.view3d`, which crops a
  picture of another shape to fill it: the first captures were 16:10 and lost an eighth of their height on the page). The middle of the
  view is cut to that shape, drawn finer than its window where the window is narrower (Cesium's resolution scale, at most four times)
  **with the detail to match**: Cesium judges a tile by its error over the pixel ratio, so a finer frame alone drew the window's tiles
  larger; the point cloud's and the globe's error are lowered by as much while the capture waits (8 s at most) for the finer tiles,
  then put back with the window's resolution. Read in the frame's own postRender (from a buffer kept for it, `preserveDrawingBuffer`,
  which the review found the read does not need but costs nothing), **with the camera as that frame was drawn** (a flight still under
  way was read a frame or more later). A frame that never comes (a lost WebGL context stops Cesium's drawing) is said after 15 s; the
  capture once waited for ever, with Cancel off.
- **The window closes while it captures**: Cancel and Escape give up the frame and the upload (aborted, and timed out after 2 minutes),
  and keep nothing. Escape never reaches the survey book under the window (`Dialog` keeps it to itself whether or not it closes: it once
  closed the book while a capture was kept). Capture is offered only while the viewer it would read stands: a new point cloud (the
  target moved) waits for its viewer. A failure is said in the frame's own words, or the server's, never as a lost connection.
- **Kept with the camera that took it**: a `view_3d` PNG (the server checks it is one and keeps it as sent), its heading as the
  asset's, and its `pose`: heading and pitch, its distance from the LZ's middle where the server gives the LZ's frame (a streamed survey's
  own sphere is a whole state, whose distance the server would refuse), and the LiDAR survey it shows, taken from the snapshot's
  obstacles **only where the window is streamed** (both then choose the newest survey covering the place; a built point cloud may come
  from a downloaded collection of several, so it names none). A pose is a capture's alone, checked as it comes and never changed after;
  capture again to change it.
- **One 3D view a page**: a new capture takes the place of the last, which is kept on the server (a published page may show it). Two at
  once go one after the other (each takes the survey's row first), and taking the 3D view off takes off every capture still live: two
  kept at once once left the older to come back. The page prints the newest, captioned the way it looks, rounded as the editor rounds
  it (every heading on the page now rounds halves up), and with its survey's year, which says how old its trees are, in a tag short
  enough over the picture: "3D · LOOKING 090°T · LIDAR 2018" (a survey with no year by its name, cut at a word's length). The picture
  carries no credit for its imagery or its points' colour (Cesium draws those over the canvas): whether the caption should is the
  owner's to say.
- **Checked by an independent review** (2026-10-27), which ran `captureFrame` against real Cesium 1.144 in headless Chrome through
  Playwright (already a dev dependency), on SwiftShader and on the Mac's GPU: the camera faces the approach, the frame is the one drawn
  after the resize, and the window's resolution and canvas are put back. So the frame's mechanics can be tried from an agent session
  after all; how real imagery looks still wants a person's eye: open a survey, *Open 3D*, Capture, and look at the page's preview. The
  fixes above are held by tests with the viewer and the frame stood in for (each checked to fail without its fix).
