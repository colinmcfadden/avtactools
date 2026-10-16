# Mission brief — architecture and plan

Status: **B0 and B1 done; B2 (backend) under way, B3 next** (branch `feat/mission-brief`, 2026-10-16). The screens are the design canvas's section 6 (*Mission brief*:
seven web boards, three Android tablet boards). This file is the plan for building them on the web and Android. The owner's decisions of
2026-10-16 are in §9.

---

## 1. What it is

The planner turns a plan into the brief they give the flight:

- **Generate brief…** walks through three steps of setup: the mission flow and constants, the brief's details, then review. It produces a
  **PDF**: a cover, one page per step of the brief, appendices (LZ/PZ cards, nav logs, an IIMC card), with every page marked UNCLASSIFIED,
  a band of mission constants, a figure drawn from the plan, a table of the facts and a script to read.
- **Present live sandtable** walks through the same steps on the live map: the map moves to each step and lights the current leg, the
  script is on screen with who briefs it, and the constants stay in a box. Only the presenter's map moves.
- **Two formats** are chosen when a brief is made: the **air movement brief** (the non-tactical multi-ship brief, nine paragraphs) and the
  **hasty multi-ship brief** (the tactical one, about thirty numbered items). Both come from the Aviation Branch Handbook (15 July 2024).
- **Where its content comes from:** a brief is built from a mission pack (everything in it) or, outside a pack, from the LZ/PZs and route
  sets open in the session.

What a brief knows already, from the plan: the LZ/PZs (grid, elevation, landing and takeoff headings from the doghouses, formation from the
aircraft placed, go-around, slope call, PZ markers, units), the routes (points, times, airspeeds, altitudes, fuel, legs, from the route
planner), the aircraft profile, local points, weather and NOTAMs (`/api/weather`), and threats (once step *T* is built). What the person
adds: the flow's roles and order, the constants, and the answers the plan does not hold (mission, intent, frequencies, abort criteria,
contingencies...). Anything left blank prints as a line to write on.

---

## 2. The shape of it: one model, two outputs, two platforms

```
             plan (LZ/PZs, route sets + planner results, threats, aircraft, points)
                  │                 weather/NOTAMs (server)     light data (on device)
                  ▼                        ▼                           ▼
  brief document ──► compileBrief(template, document, inputs) ──► BriefModel
  (person's answers)       pure, no clock, no network             (steps, pages, facts,
                           web: feature/brief/compile.js           scripts, figure specs,
                           Android: core-brief, held to             write-in lines)
                           contracts/fixtures/brief                      │
                                                       ┌─────────────────┴──────────────────┐
                                                       ▼                                    ▼
                         POST /api/brief/pdf  (BriefModel as JSON)                    sandtable
                         server: figures (tiles stitched, vectors as SVG)             (per platform, on the live map,
                                 + pages (Jinja HTML, print CSS)                       works offline)
                                 ──► Gotenberg (Chromium, internal only) ──► PDF
```

The rule that keeps the two platforms, and the PDF and the sandtable, saying the same thing: **everything a brief says is decided once, in
`compileBrief`, and held to fixtures.** The renderers only lay it out. This is the pattern the planner, the slope call and the pack
operations already follow (the web is the reference, `contracts/` is the contract, Kotlin ports and is held to it).

### 2.1 Templates are data

A format (air movement, hasty) is a **template**: a JSON document listing its paragraphs and items in order, who briefs each, the fields
each holds, where each field's value comes from in the plan (a binding such as `flow.lz.landingHeading` or `light.sunset`), the
standard/custom/blank choices a contingency offers, and the script sentences with placeholders. The compiler knows how to bind and
write; it knows nothing about either format. So:

- the hasty brief costs a template, not a second compiler;
- a later format (the full AOPORD/AMB, an air assault annex) is the same;
- the handbook's cover is marked CUI; **the owner confirmed on 2026-10-16 that the formats' item lists and the standard procedure values
  are releasable** (they also appear in public doctrine), so the templates carry them, in the app's own words. The handbook itself is never
  committed.

Templates live in `contracts/briefs/` (one file each, with a `version`; `README.md` there is the format), with `bindings.json` (every value
the compiler can find in the plan: the contract between a template and `compileBrief`), `constants.json` (the mission constants offered)
and `standards.json` (the standard procedures a contingency's *Standard* applies). They are bundled with both apps and validated on both
sides (`frontend/src/contracts/briefTemplates.test.js` first). A brief document records the template id and version it was made with; an older document is migrated forward by field id,
never by position.

### 2.2 The brief document

What the person has answered, kept so that generating again starts from their answers:

```json
{
  "version": 1,
  "template": {"id": "amb", "version": 1},
  "source": {"pack": "<uuid>|null", "lzs": ["<id>", ...], "routeSets": ["<id>", ...]},
  "flow": [{"ref": "lz:<id>", "role": "LZ"}, {"ref": "route:<set>/<route>"}, ...],
  "constants": [{"id": "enrouteIas", "value": "120 KIAS", "source": "routes"}, ...],
  "answers": {"2.b": {"value": "..."}, "5.a.iii": {"mode": "standard"}, "5.a.v": {"mode": "custom", "fields": {...}}},
  "scripts": {"5.a": "edited text, kept until reset"},
  "options": {"script": true, "constants": true, "appendices": ["lzCards", "navLogs", "iimc"], "figureMap": "satellite", "paper": "letter", "writeIns": true},
  "generated": [{"at": "...", "by": "...", "pages": 26}]
}
```

- **References, never copies**, of plan items: the brief always describes the plan as it is now, and an item that went is shown as gone in
  setup (never silently dropped from the flow).
- Values the compiler found in the plan are not stored; only what the person typed or chose. A constant shows its source ("From the
  routes · most legs") and becomes the person's own once edited.
- Each person's answers for a field are one value (the pack lesson in AGENTS.md §14: a value that is one thing to a person goes whole).

### 2.3 `compileBrief`

`compileBrief(template, document, inputs) → BriefModel`, pure and deterministic: no clock (the generation time is an input), no network,
no random ids. It:

1. orders the flow (the person's order, else route order), assigns roles (a stop's role decides where the format briefs it: 4.d, 4.e or 4.f in the air movement brief), and works out each
   stop's times from the route planner's results;
2. derives constants the plan agrees on (most legs at 120 KIAS, every LZ/PZ's go-around to the right) and says where each came from;
3. binds each template field to the plan, the weather, the light data or the person's answer, and marks what is left as a write-in;
4. writes each step's script from the template's sentences, unless the person edited it;
5. gives each step a **figure spec**: the area to show (bounds), the paper size, the base map, and the layers to draw (the whole route,
   the current leg lit, the LZ box, aircraft, go-arounds, threats' rings, rally points), all as plain data;
6. returns the pages (cover, contents, steps, appendices) and the sandtable's steps from the same list, so the sandtable's step 8 and the
   PDF's page 8 are the same thing.

Its fixtures (`contracts/fixtures/brief/*.json`, written by the web's tests as the others are) pin it for: both templates, a pack with
several LZ/PZs and two route sets (the design's OP DK), every role, standard/custom/blank for each contingency, blanks, edited scripts,
an item gone, a route with fewer than two named points, and the numbers' text (headings `203°`, `2,230 ft`, times), which follows the
existing rules (JavaScript's rounding and `toFixed`, no locale digits).

### 2.4 Light data, on the device

BMNT, sunrise, sunset, EENT, moonrise, moonset and the moon's illumination for the departure point and date, computed on the device (no
service, works offline): the sun by the NOAA algorithm, the moon by Meeus' low-precision series, nautical twilight at −12°. A pure module
on each side (`feature/brief/light.js`, Kotlin in `core-brief`), held to `contracts/fixtures/brief/light.json`, whose reference is an
independent library run by a fixture script (as PyGeodesy is for MGRS): a reader needs a reference that is not itself. Times to the
minute; a body that does not rise or set that day says so, never a made-up time.

### 2.5 Weather and NOTAMs

`/api/weather` answers for *now* at one point. A brief wants departure, enroute and arrival, at planned times. Two additive changes to the
server (routes the apps call are additive-only, AGENTS.md §6): an optional `at` (ISO time) that picks the TAF period for that time, saying
whether the answer is an observation or a forecast; and the stations named, so a brief can say "KMGE" rather than a distance. Enroute winds
already come from `/api/route-winds`. The brief keeps the answers it was generated with and how old they are, the way the weather tiles do,
and a stale report says so.

### 2.6 Figures, drawn from the plan, on the server

A figure is not a screenshot of the map: the compiler gives each step a figure spec (bounds, page size, base map, and every layer as
lat/lon geometry with its style), and the server only projects and draws it, so what a figure shows is decided by the fixtures, not the
server. The base map's tiles for those bounds are fetched and stitched into one image with Pillow (as `export_service.py` already does
for the LZ card's imagery, cached by tile), and the plan's layers (routes with the current leg lit, the LZ box, aircraft, go-arounds,
rally points, threats' rings, the MGRS grid) are drawn over it as **SVG**, so lines and labels stay sharp in print. A north arrow, a scale
and the tiles' attribution go on every figure. A tile that cannot be fetched leaves the vectors over a plain ground with a note, never a
blank page. Projection is one function (`brief_figures.project`) with a test of where a point lands for given bounds.

Why not the LZ card's way (`html-to-image` of the live Leaflet map): it captures one view at a time, waits for tiles, needs the map on
screen, and Android has no DOM; a brief has 20 to 30 figures.

**Strip charts** are figures too (the owner, 2026-10-16: in the brief). For each route, a strip along it turned so the direction of flight
is up, cut where it bends too far for one strip, on the topo (or sectional, or satellite) map, with the MGRS 1 km grid (its lines worked out by
the client with the grid code both apps already share, `mgrsGrid.js` / `MgrsGrid`, and sent in the figure spec: the server only draws), the checkpoints with their names, and each leg's heading, distance, time and altitude written beside
it. They are an appendix, sized to fold onto a kneeboard. Not the 1:50K map: our base maps are what crews see in the app.

### 2.7 The PDF: HTML on the server, printed by Gotenberg

The owner asked whether Gotenberg would do; it is the best fit, and the plan uses it:

- **One layout for both platforms.** The pages are Jinja templates and print CSS on the server (`backend/brief/templates/`): the cover,
  the contents, a step page (constants band, figure, facts table, script box), the appendices (LZ/PZ cards, nav logs, the IIMC card,
  strip charts). The web and Android both send the `BriefModel` to `POST /api/brief/pdf` and get the same PDF back. Rendering twice, by
  hand, with a PDF library on the web and `PdfDocument` on Android, would be two layouts to keep in step and twice the work.
- **Gotenberg** (`gotenberg/gotenberg`, MIT, a Chromium HTML-to-PDF API in a container) runs beside the API as a separate internal
  service, like the LiDAR build service: no public hostname, set by `GOTENBERG_URL`. The server sends it the page HTML and the figures as
  files of one request; Chromium runs with **JavaScript off and the network refused**, so nothing a person typed into the brief can make it
  fetch anything; Jinja escapes every value.
- **Speed:** one request per brief; the figures are the slow part (tiles), so they are fetched in parallel and cached by tile, and the
  request is a heavy one for `requestPriority` (terrain tiles wait, warming pauses: AGENTS.md §9). A brief of about 26 pages is expected in
  a few seconds once tiles are cached; to be measured on the VM.
- **Nothing is kept**: the request and the PDF are not stored, logged or cached on the server (the tile cache holds only map tiles).
- Every page: UNCLASSIFIED top and bottom, "Generated by EZ/PZ from the plan. Not an official form", page *n* of *N* (Chromium's header
  and footer templates).
- **The review step shows the real PDF**: the web in the browser's own viewer, Android through the platform's `PdfRenderer`. Downloaded on
  the web; on Android *Share PDF* (the share sheet, through the existing `FileProvider` export folder, cleared like the other exports) and
  *Save to device* (the system's create-document picker).
- **The price:** a PDF needs a connection (the sandtable does not), and Gotenberg is one more container for the owner to deploy (about
  1 GB of image; memory to be measured). Without `GOTENBERG_URL` set the route answers 503 and the apps say the PDF is not available on
  this server, as the 3D view does without its build service.

### 2.8 The sandtable

A presentation mode over the existing map, not a new map:

- **Web:** `feature/brief/Sandtable.jsx`, over `MapView`: the steps' list, the script card, the constants box (collapsible), arrows and
  ← → keys, *End brief*. Each step flies the map to its figure's bounds and draws its layers (the lit leg, rally points at the
  contingencies step) as a Leaflet layer of its own. Editing is off while it runs.
- **Android:** `feature-brief`'s `SandtableScreen` over the same `EzpzMap`, the camera moved with `MapHost`, 56 dp step buttons, a tablet
  layout (tablet only, §9).
- **Only the presenter's map moves** (the design's choice). Others following along through a pack's live stream is later (§9).

---

## 3. Where a brief is kept

- **Outside a pack:** a Library record of a new kind, `brief`, synced like the others: `SavedBrief` with the sync columns
  (`client_uuid`, `revision`, tombstones, `change_seq`), `/api/briefs` CRUD, in `/api/sync/changes` as `brief`, described in
  `contracts/openapi.yaml`, and `RecordKind.BRIEF` in `core-sync`. Every query filters `deleted_at IS NULL` (AGENTS.md §7).
- **In a pack:** a pack item of kind `brief` (`pack_ops.ITEM_KINDS`, `packOps.js`, `PackOps`, and the fixtures in `contracts/fixtures/packs`),
  edited through the same operations as an LZ/PZ, with history sentences of its own.
- **A brief item is open to every member, in every state of the pack** (the owner, 2026-10-16: briefing is for everyone, and a
  finished pack is still briefed). So the server's two refusals make an exception for operations on a `brief` item, and only for them:
  a finished pack's 423 and a viewer's 403 (`pack_routes`, under the pack's lock as every write is). Making, renaming and removing a
  brief item follow the same rule. The clients' read-only gate (`PackLock` on Android, the web's finished and viewer checks) lets brief
  items through and nothing else, and a test on each side holds that an LZ/PZ or route in the same pack is still refused. The
  history says who changed a finished pack's brief, like any change.
- **Generated PDFs are not kept**: "Last generated" in the menu is the document's `generated` entries; *PDF* there generates again from the
  plan as it is now, and says so if the plan changed since.

---

## 4. Code layout

**Web** (`frontend/src/feature/brief/`): `useBrief.js` (the open brief document, its saving and syncing, pack or Library),
`compile.js` + `templates.js` (the pure core, held to the fixtures), `light.js`, `briefApi.js` (the PDF request), `BriefMenu.jsx` (the top
bar's *Brief*), `BriefSetup.jsx` with `FlowStep.jsx`, `DetailsStep.jsx`, `ReviewStep.jsx`, and `Sandtable.jsx`. `App.js` only composes it.

**Backend:** `models.SavedBrief`, `routes/brief_routes.py` (the records and `POST /api/brief/pdf`), `brief/` (figures: tiles, projection,
SVG layers; the Jinja pages and print CSS; the Gotenberg client), `sync_support` for the new kind, `pack_ops.ITEM_KINDS += ('brief',)` and
the brief exception in `pack_routes`, the weather route's `at`, OpenAPI and recorded responses, the entitlement `mission_brief` in
`entitlements.DEFAULT_OFF` until launch, and `GOTENBERG_URL` (AGENTS.md §10). Gotenberg itself is the published image, configured in the
Coolify app (JavaScript off, network denied); no Dockerfile of ours.

**Android:** `core-brief` (pure Kotlin: templates, document, `compileBrief`, light data; held to `contracts/fixtures/brief`),
`feature-brief` (setup, review over `PdfRenderer`, Share and Save, the sandtable; Robolectric and Roborazzi tests), the brief's record in
`core-sync`/`core-data`, the pack kind and the brief exception in `core-missionpacks` and `PackLock`, and a *Brief* entry in the
**tablet's rail only** (the owner, 2026-10-16: tablet size and up for now; the phone's frame offers none).

---

## 5. Steps

Each step as the screens and packs were built: implemented, reviewed independently, fixed, committed with its tests and the full build
green, AGENTS.md and `docs/HANDOFF.md` updated in the same commit.

| Step | What | Depends on |
|---|---|---|
| **B0** | **Done.** `contracts/briefs/`: the template format, the two formats, the bindings, the constants and the standards, held by `briefTemplates.test.js` | — |
| **B1** | **Done.** Web: `compile.js`, `templates.js`, `light.js` and their fixtures (`contracts/fixtures/brief`: `compile.json` from the web, `light.json` from Skyfield via `contracts/scripts/light_fixtures.py`) | B0 |
| **B2** | Backend: `SavedBrief`, `/api/briefs`, sync kind, pack item kind `brief` and its exception, the weather route's `at`, OpenAPI, recorded responses, `mission_brief` | B0 |
| **B3** | Web: the Brief menu and the three-step setup over `useBrief` (Library and pack) | B1, B2 |
| **B4** | Backend + web: figures, the pages, Gotenberg, `POST /api/brief/pdf`; the review step and download | B3 |
| **B5** | Web: the live sandtable | B3 |
| **B6** | Backend + web: strip charts in the appendix | B4 |
| **B7** | Android: `core-brief` ported and held to every fixture; the record kind, the pack kind and its exception | B1, B2 |
| **B8** | Android: brief setup, review, Share and Save (tablet) | B4, B7 |
| **B9** | Android: the sandtable (tablet) | B7 |
| later | Threats in the brief (needs *T*), others following the sandtable, the AOPORD/AMB and air assault formats, a phone layout | — |

The web and the backend lead, as for everything else; Android follows once the fixtures exist. B2 can run beside B1. Rendering on the
server makes Android's PDF a request and a viewer, not a second layout.

---

## 6. Rules carried over

- **Unclassified only.** Every page says so, and the brief holds nothing the plan does not. The handbook's lists and standard values are
  in the templates by the owner's word (§9); the handbook itself is never committed or sent to a service.
- **Never invent a value for no data**: a field with no value is a write-in line, a missing tile is said, a body that does not set has no
  time.
- **Numbers formatted by hand**, no locale (AGENTS.md §17).
- **A brief reads the plan, it never changes it.** Nothing in setup edits an LZ/PZ or a route; a role or an order is the brief's own.
- **Gotenberg is the one new service** (the owner's suggestion). Anything else new (the light fixture script's reference library, which is
  a development tool and never shipped) is asked first.

---

## 7. What cannot be verified in an agent session

How a printed page looks on paper (ask for a print), the sandtable's flights on a real map, the PDF in the readers crews use (Adobe,
ForeFlight's documents, Android's viewers), the share sheet on a device, and Gotenberg's speed and memory on the VM.

---

## 8. Cost

Large: of the order of the mission-pack screens. Most of the risk is in B1 (the compiler's rules, which every later step reads) and B4
(figures and the page layout). The sandtable is comparatively small once the model exists.

---

## 9. The owner's decisions (2026-10-16)

1. **The formats' item lists and the standard procedure values are releasable** and go in the templates. (The handbook's cover is marked
   CUI; AGENTS.md §2 is unchanged for everything else.)
2. **A finished pack's brief is a pack item** that stays open to change (§3).
3. **Everyone in a pack may brief**, viewers included (§3).
4. **PDF: whatever renders best and fastest; Gotenberg suggested.** Taken: HTML on the server, printed by Gotenberg (§2.7).
5. **Light data: our own sun and moon code**, held to an independent reference (§2.4).
6. **Android: tablet size and up only**, for now.
7. **Flow roles: all of them**: PZ, LZ, FARP, holding area, laager and false insertion LZ (each with where it is briefed in each format).
8. **Only the presenter's map moves**; following through a pack is later.
9. **Weather at planned times**: `/api/weather` gains `at` (§2.5).
10. **Printing map tiles**: the terms are to be checked before the PDF ships (Mapbox's for printed figures; the FAA chart's and the topo's).
11. **Strip charts are in** (B6, §2.6): the design left them out only for want of the 1:50K map.

Still open: where the Gotenberg container runs (beside the API on the Coolify VM is the plan), and the Mapbox terms (10).
