# Hand-off — Android app build

For the next agent or person picking this up mid-stream. **`AGENTS.md` §17 is the detailed record** (what each module is, the
rules that bit once, what cannot be verified without a device); this file is the *where things stand and what to do next*.
Read both, then `docs/NATIVE_APPS_PLAN.md` for the plan and its phases. Refresh this file in the same commit as any change
that moves the "where things stand" or "next" sections.

Last refreshed: 2026-10-23 (the owner moves to a new computer: every branch with work is pushed, and what is not in git is listed in *Moving to a new
computer*, below; the device-run work of 2026-10-21/22 is §0).
Before that, 2026-10-20 for the mission brief (§0a: B0 to B9 built on `feat/mission-brief`: the web and the server, then Android's core (B7), its setup, review, Share and Save on a tablet (B8), and its live sandtable (B9)).
Before that, 2026-10-19 (B8).
Before that, 2026-10-13 (the redesigned screens on `feat/android-screens`: S1 the design system, S2 the phone's frame and S3 the LZ/PZ
panel reviewed together, S4 the Routes panel and the route's plan, S5 the Threats panel and its form, S6 Import (the menu, the review, the Imports
panel) S7 the Library (with record times, database version 4) and S8 the map's long-press menu and the Saved toast's *Open Library*, each reviewed on its own,
S9 the tablet, P1 the workspace switcher, P2a the Pack panel, P2b the LZ/PZ and Routes panels in a pack, P2c a read-only pack, P2d a finished pack (its banner, Finish, Reopen, Duplicate), P2e the presence pointer, P3a New pack, P3b Members, P3c Teams, P4a copies into a pack, P4b updating a copy and copies to the Library, P4c the edits a pack would not take, every review's fixes in: S1–S9 and P1–P4 are built, and the owner's device run is next. The owner tries it on a device once S7–S9 and P1–P4 are done. The owner decided threats may be saved and shared: AGENTS.md §2, and step *T* of
`docs/ANDROID_SCREENS_PLAN.md`; the screenshot guard and the 48-hour wipe went in S5). Since then, outside the plan: the web's MGRS grid on the Android
map, from the Layers menu (AGENTS.md §17, *The MGRS grid*), its code ported and held to `contracts/fixtures/mgrs/grid.json`; the fix for what the owner's first
two-device run found, a pack's route written twice (§1); the audit of every setting in a pack that followed it (§1); and the owner's changes to the phone's frame
from the same day's device run (§1): the search, the workspace, Import and the Library on one row, a tap on the map that hides the frame, and how an open pack
stands said on its chip.

---

## Moving to a new computer (2026-10-23)

**Every branch with work is on GitHub** (pushed 2026-10-23); nothing was left uncommitted or stashed. Where each stands:

| Branch | What it holds | Where it stands |
|---|---|---|
| `feat/mission-brief` | The mission brief, web, server and Android B0–B8 (`docs/MISSION_BRIEF.md`) | PR #99 to `develop`, open, Auto-fix on. Staging has none of the brief's routes until it is merged (and so deployed there) |
| `claude/festive-dijkstra-79b32b` | B9, the brief as a live sandtable on an Android tablet, on top of `feat/mission-brief` | pushed, no PR: into `feat/mission-brief`, or its own PR after #99 |
| `feat/android-quick-edits` | The owner's device-run requests of 2026-10-21/22 (§0 below), from `develop` | pushed, no PR |
| `claude/android-3d-view-e38b76` | The 3D LZ view on Android (`docs/ANDROID_3D_PLAN.md`), from `develop` | pushed, no PR. Tried on the tablet merged with `feat/android-quick-edits`: they conflict in `DiagramsViewModel`, `LzPanel` and their tests, and both sides are kept (a 3D test then needs `FreshDiagrams()` before its `BuildOnSave`) |
| `claude/competent-noether-5cc4f7` | The MGRS grid fixture compared to a tolerance (a contracts test) | pushed, no PR |
| `fix/window-stacking` | The web's floating windows and alerts stacked under menus, dialogs and toasts | pushed, no PR |
| `fix/mobile-analyze-boundary` | The phone web's *Analyze* and *Draw boundary*, and capture and export above the sheet | PR #98 |
| `claude/admin-coverage-prebuild-efcef7` | Docs only: the plan for the admin's data coverage map, uploads, 3D streamed from USGS, and organizations with an area of operation (`docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md`; the owner's answers on its decisions page, https://claude.ai/artifact/RU8RPViDvj2qjpP7UGLT4R, recorded in the doc) | pushed, no PR. All 20 decisions answered on 2026-10-23, none open; D1 (MapLibre GL JS for the admin map) and D13 (several AOs per organization) differ from its recommendations. Nothing built: Phase 0 is next. It found that any signed-in user can list every place a 3D view was built, with its coordinates (`GET /api/lidar/tilesets` and each tileset's `area.json`); Phase 0 closes that |
| `test/quick-edits-3d` | A throwaway merge of quick edits and 3D, built for staging and put on the tablet | never committed or pushed: remake it from the two branches |

**Not in git: bring it, or make it again.**
- **The debug signing key** (`~/.android/debug.keystore`). A debug APK built with another key will not install over the tablet's or the phone's (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and
  uninstalling it first deletes what the app holds on the device (changes not yet synced, the threat picture, the session). Copy the file to the same place on the new
  machine. Google sign-in's Android OAuth client is tied to its SHA-1 too (§4).
- `android/local.properties` (`sdk.dir`), `frontend/.env` (from `.env.example`), the backend's venv, and `backend/ezpz.db` (local accounts: `dev_user.py` makes them again). SAM's
  `sam_b.pt` downloads itself on the backend's first start (358 MB). The setup for each is AGENTS.md §11 and §3 below.
- **The mission packet's sources**, outside the repo on purpose: `~/Downloads/ABOS Annex A AV Handbook 15July2024.pdf` (its cover is marked CUI, so it never goes into the repo,
  AGENTS.md §2), `~/Downloads/DOORKICKER_PACKET.pdf` (another unit's packet with real names: a layout reference only), and `2023 Mission packet - WILSON.pptx` in Keynote's iCloud
  folder, which iCloud brings along.
- Claude's notes for this project (`~/.claude/projects/<project>/memory/`) and the agent sessions' transcripts stay on the old Mac. What they held that matters is in this
  section and AGENTS.md. Each branch's worktree under `.claude/worktrees/` is local too; the branches are what matter, and they are pushed.

**Trying a build on a device from a Mac, against staging.**
- **For 3D, build for staging**: `./gradlew assembleDebug -Pezpz.apiUrl=https://dev-ezpz-api.mcfadd.in/`. A machine with no DEMs, LiDAR or build service answers only "Not generated"
  locally, while staging shares the build service and the tiles with production. It is HTTPS, so no `adb reverse`.
- **Staging's email does not arrive** (verification, password reset), and `dev_user.py` writes only a local SQLite database. A verified staging account is made in the staging
  Supabase project's SQL editor: an upsert into `"user"` (role, `is_active`, `access_approved`) and `local_credential` (a werkzeug scrypt hash made locally from a password asked
  with `getpass`, `email_verified_at`, status `active`, `session_version` moved on). Never paste staging's `DATABASE_URL`, or a password, into a chat (AGENTS.md §2: a secret
  shown in a chat is rotated).
- In an agent session, the Bash sandbox has no USB: run `adb` with the sandbox off. Each time the adb server starts again, the tablet asks again to allow USB debugging.

**Designed, not built: the mission packet export** (started 2026-10-21). The owner's kneeboard packet (ABOS Part Nine, *Kneeboard Products*) as an EZ/PZ-styled PDF with
clickable navigation. The design canvas, in the owner's claude.ai account: https://claude.ai/artifact/Phz2jDxwu5wcUejiqaYGq3 (eleven Letter sheets: a cover with the inventory,
crew and commo, timeline, EXCHECK, concept sketch, PZ, TDH, LZ, FARP, approach plate, IIMC). Decided so far, the owner's to change: Letter portrait, one sheet a page, a
right-hand tab rail with IIMC pinned last in red (as the owner's packets have it), the EZ/PZ light palette, Roboto Flex with Roboto Mono for numbers, UNCLASSIFIED top and
bottom, a "V2 · n of N" footer, and fictional sample data. Next, the owner said: the screens of the dialog that builds the packet. It overlaps the mission brief's setup and
PDF, so it reuses their compile and print path rather than making a second one. The ABOS handbook's item lists and field names are put in the app's own words, never its text.

## 0. Analyze after Finish, objects edited from their bar, the slope map's opacity, the LZ box, every open LZ/PZ on the map, route points by type, helicopter names and the tools on the map's edge (2026-10-21/22)

Branch `feat/android-quick-edits` (from `develop`). The owner's requests from a phone run (AGENTS.md §17, *Boundary drawing* and *Dragging and turning on the map*):
- **Finish offers the analysis there and then**: the drawing toolbar gives way to *Analyze LZ/PZ* over the map (then its stage with *Stop*, then *Try again* on a failure, and
  *Not now*), so a boundary just drawn is not three steps from its analysis (`BoundaryViewModel.analyzeOffer`, `AnalyzeOfferBar`; `AnalyzeOfferTest`).
- **A long press holds what it is on, as a tap does**: its bar over the map and nothing else (no panel), and still picks it up to drag; a press that does not move leaves it where
  it was. A read-only pack's object is held, not picked up, so its bar answers the press rather than the map's menu (`HomeViewModel.dragStarted`).
- **A doghouse's fields are in its bar**: label, heading, time, distance and airspeed, with the turn buttons; *More options* is an icon beside *Done*. What a field takes lands on the
  doghouse it was typed for (`GraphicOf`), never on one held or an LZ/PZ opened since; the bar scrolls past 60% of the window, and what floats over the map stands on the
  keyboard's top when that is higher than the sheet (`MapScreen.standingBottom`).

An independent review's findings are fixed, each with a test (mutation-checked): a field with focus wrote its old value back over a turn or an undo made meanwhile; the
offer came back for a later *Re-analyze* and showed an earlier failure; it lay over the held bar or a route's toolbar; the doghouse's bar could reach above the screen over a
sheet at half (what floats is now measured into the room under the row); and a finger's roll after a long press moved what it only held (now an 8 dp slop, and no edit at all
when the finger never passed it).

A new LZ/PZ (the map's long press *New LZ/PZ here*, or the panel's) is offered its first step in the same bar: *Analyze* or *Draw boundary*, and *Draw boundary* again where no
area was found (`FreshDiagrams`).

**The slope map's opacity and the LZ box** (the owner's request from the tablet run; AGENTS.md §17, *How the slope map and the LZ box are drawn*): in the plan's analysis
section, under *Slope map*, an *Opacity* slider while it is drawn (10 to 100% in steps of 5, from the web's 55% where Android drew 85%; this device's, kept between launches,
never saved with a plan), then an *LZ box* switch that hides the analysis boundary, its fill and its corner dots (the diagram's own `view.showLZOutline`, the web's *LZ Box*;
a corner held keeps its corners drawn, a boundary drawn by hand is drawn as ever). Both offered in a read-only pack. Looked at as Roborazzi pictures in dark, night and light,
and on a 360 dp phone at 1.3 and 2.0. The web has the same slider, in the sidebar under *Slope Map*, remembered per browser (AGENTS.md §13, *The slope map's opacity*).

**Every open LZ/PZ stays on the map** (the owner's request from the tablet run; AGENTS.md §17, *The open LZ/PZs stay on the map*): a new LZ/PZ no longer takes the last one
off the map. Every open one but the one being worked on is drawn faded under it (target and name, boundary, aircraft, PZ arrows, sectors, go-arounds; not its slope map,
doghouses, units or separation lines), the person's own open set in the Library and the pack's LZ/PZs in a pack. Each row of *Also open* (and of a pack's list) has an eye that
hides it from the map and shows it again (this device's own, never sent); a tap on a faded one holds it, its bar offering *Open* and *Hide*. The web draws the open one alone
(AGENTS.md §15). Only the open ones' records are read for it (a Room query of their uuids), each again only when it changed, off the main thread. Looked at as Roborazzi
pictures (the eyes and the bar, at 412 dp and on a 360 dp phone at 1.3).

**Route points are drawn by their type** (the owner's rule: an RP a square, a target a triangle, a checkpoint a circle; AGENTS.md §17, *A named point's shape is its
AMPS type*): as the web draws them, an IP point (the SP and the RP) a square, a target a triangle pointing up, a turn point a circle, shaping points small dots as before.
Bitmaps in the route's colour sized in points (`RoutePinImages`), drawn for real in `RoutePinImagesTest` (its contact sheet looked at over grass, the topographic map and
the chart).

**A route point's type from its bar** (the owner's requests; AGENTS.md §17, *A route point's type is in its bar*): a held point's bar has a chip for each of *Checkpoint*,
*SP*, *RP*, *TGT* and *Shaping*, the one it is selected, and a tap on another makes it that, by the route's plan's rules (`SketchOps.designate`, one undo step). The first
version opened a dialog of the five; the owner asked on 2026-10-22 for the choices in the bar itself. The SP and the RP take their names (`.SP`, `.RP`: AMPS calls both IP
points); a checkpoint or the target keeps a name of the person's own. Refused in words: a mission's points (the file's), one of the last two named points as a shaping point,
a read-only pack. The web's menu offers "RP / IP" once and asks for a name (AGENTS.md §15). Looked at as Roborazzi pictures (the bar, a mission's bar, a route of two named
points, in dark, night and light, and on a 360 dp phone at 1.3).

**A helicopter's name** (the owner's request of 2026-10-22; AGENTS.md §17, *A helicopter has a name*): a chalk or tail number, set in the inspector and in the held bar,
kept in capitals as the aircraft's `label` (20 characters at most), first in its title and written under it on the map. The web keeps the field but does not show it yet
(AGENTS.md §15).

**The LZ/PZ tools on the map's edge** (the owner's request of 2026-10-22; AGENTS.md §17, *And on the map's right edge*): while the LZ/PZ panel is chosen, *Draw LZ*, the four
placing modes and a unit stand as a column of buttons under the map's own; where not all fit, as many as do and a *More* button for the rest, the tool in use always
shown; kept above a held object's or a tool's bar. Looked at in the phone's frame (sheet lowered and at half, a held helicopter at night, a 360 dp phone).

An independent review of these three found, each now fixed with a test (mutation-checked): *Set name* in the options panel did nothing in the app (the panel built its
own actions and left the name out; one builder now serves every screen); a refused *Draw LZ* from the map's edge was silent and later showed in the panel; its question
could come back by itself after a pack was finished and reopened, or apply to another LZ/PZ; the column collapsed to one button as soon as a tool's bar came up; a name
typed for one helicopter carried over to the next; Go on an empty name field took the name away; a tablet's search error covered the first tool; the name sat on a
north-facing UH-60's tail; and the feet between two aircraft, like the new name pill, swallowed a tap meant for the map.

**Not verifiable here:** the keyboard lifting the bar on a device, the bar over a real map, TalkBack on the new fields, the slope raster at each opacity and the
outline hidden on the real map, the faded LZ/PZs over imagery with twenty open, the point shapes on the GL map, the type chips and the tools on the map's edge under a glove,
and the names under the aircraft over imagery. A device run on the owner's phone is next.

## 0a. The mission brief (2026-10-16 to 20)

Branch **`feat/mission-brief`**, on `develop` (which has `feat/android-panel-close-and-tools`, merged as PR #97). **Not pushed; no PR yet.** The plan, the
owner's decisions of 2026-10-16 and each step's state are in **`docs/MISSION_BRIEF.md`** (read it first); AGENTS.md §4, §6, §9 and §10 say what exists.

**What it is:** the air movement and hasty multi-ship briefs (Aviation Branch Handbook formats; the owner confirmed their item lists and standard values
releasable, the handbook itself is CUI-marked and never committed) generated from a mission pack or the session: a PDF and a live sandtable.
Web and Android; Android tablet size and up only for now.

**Built (each with its tests, the commit named):**
- **B0** `contracts/briefs/`: the two formats as data (templates, bindings, constants, standards), held by `briefTemplates.test.js`; the web bundles
  byte-for-byte copies in `frontend/src/feature/brief/templates/` (a test fails if a copy differs: change `contracts/briefs`, then copy).
- **B1** `compileBrief` (`frontend/src/feature/brief/compile.js`, pure: no clock, zone or network) held to `contracts/fixtures/brief/compile.json`, and
  on-device light data (`light.js`) held to Skyfield (`contracts/scripts/light_fixtures.py`, `light.json`; Skyfield is a dev-only reference the owner
  approved, never shipped).
- **B2** (built in a side session, reviewed and merged here): `SavedBrief` and `/api/briefs` synced like an LZ, the pack item kind `brief` open to every
  member in every state of the pack (`pack_routes._only_briefs`), `/api/weather?at=`, the `mission_brief` entitlement (default off).
- **B3** the web's Brief menu and three-step setup (`BriefSetup` over `useBrief`): Library record outside a pack, the pack's brief item inside one; the
  web's pack session takes brief edits in a read-only pack, in batches of their own (`packs/session-brief.json`).
- **B4** the PDF: `POST /api/brief/pdf` draws the figures from the model's `geometry` (tiles + SVG, `backend/brief/figures.py`), lays out the pages
  (`pages.py`, `templates/`) and prints them through **Gotenberg** (`GOTENBERG_URL`). Tried end to end on the owner's Mac against Gotenberg 8.21.1 run
  locally with JavaScript off and the network denied, and through the real Download PDF button. **A brief says no page numbers** (Chromium may break a
  long step onto two pages): the packet is said by section, the footer numbers pages.
- **B5** the web's live sandtable: *Present live sandtable* in the Brief menu and *Present sandtable* at the review. The brief's steps walked
  on the live map (`Sandtable.jsx`, `SandtableLayer.jsx`), each flown to and drawn from the model's `geometry` by the PDF's own rules
  (`sandtableSteps.js`, held to the new `contracts/fixtures/brief/sandtable.json`, which B9 follows); the planning frame put away and nothing
  taking an edit while it runs; a step is *Step n of N*, never a page. On the way, a departure figure now names the route it leaves on
  (`compile.json` regenerated), so the PDF lights it too. Reviewed independently on four counts (state and keys, the map, editing off, the
  contract), each finding checked by a skeptic: thirteen were real (two others were refuted) and are fixed with tests (each fix undone once to see its test fail), among
  them planning windows left open over the brief, a sign-out mid-brief throwing, a pack that went leaving the map on its old area, and
  `useBrief`'s account reset running after its read (it now runs first, and a read for the account before is dropped). Tried in the browser
  against a local backend with OP DK seeded as a pack: every step, the narrow layout, keys with the map focused, a right-click, an aircraft
  window put away, an LZ/PZ removed and then the whole pack deleted mid-brief, End brief and Escape.

- **B6** strip charts in the PDF's appendix (MISSION_BRIEF.md §2.6, *As built*): `compileBrief` cuts each route into 5.5 × 21.5 cm strips
  (`model.strips`, a `strips` page kind three a page; 1:100,000 or 1:250,000; topo unless asked), the MGRS 1 km grid is added only when printing
  (`stripGrid.js`, held to the new `strip-grid.json`), and the server turns the map course up and draws the strip (`figures.draw_strip`). Printed
  through Gotenberg 8.21.1 on this Mac with real Mapbox tiles: OP DK's 12 strips on 4 pages in about 2 s with tiles cached, and the 1:250,000
  dogleg case across the zone 16/17 boundary; pages looked at as images. `compile.json` changed (every case's options, the strips, the packet sentence,
  four strip cases): B7's port must follow it, and compare the strips' numbers within a tolerance. An independent review then found, and this fixed:
  strips cut with stubs, north-up nothing pieces and a wrong course across the antimeridian, no bound on a long route (now 60 strips, said in setup)
  and nothing past 84° of latitude; the server taking the strip's pixel size from the model, trusting its field types, caching decoded tiles, laying a
  strip out twice and leaving grid lines through the scale bar's and arrow's corners unnumbered (now every line is numbered on an edge or on itself,
  swept at every course and both scales); the squares beside the route unnamed; and the cover counting pages Chromium decides.
- **B7** (built in a side session on its own branch, `claude/android-core-brief-2ffe1e`, and merged here): **7a** the new pure module
  `android/core-brief`: `CompileBrief` (compile.js ported, held strictly to every case of `compile.json`), `Light` (light.js, held to Skyfield's
  `light.json`) and `BriefFormats` (`contracts/briefs` bundled by the build, held byte for byte and to the format's rules). AGENTS.md §17 has the row.
  **7b** the brief's Library record: `RecordKind.BRIEF` over `/api/briefs` (core-network's calls, core-sync's gateway and change feed), through every per-kind
  sync scenario on the fake server, the real Flask server (the live test server now registers the briefs blueprint) and Room.
  **7c** the pack kind: `PackOps` takes `brief` (`ops-brief.json` folded into `PackOpsFixtureTest`), `PackSessions` takes brief edits in a read-only pack,
  sends them in batches of their own and keeps them when the pack turns read-only (`session-brief.json`), `PackLock` lets brief items through and nothing else,
  `PackItemCounts` takes `brief`, a refused brief is kept in the Library as a brief; a pack story holds it on the fake server, the real one and Room.
  Then B6 merged in and followed: the strip charts in `CompileBrief` (`StripCharts`), every case of the new `compile.json` exact, strip cases included;
  `strip-grid.json` (the grid added at print time) is B8's, with the PDF request.
- **B8** Android's brief on a tablet (AGENTS.md §17, *The brief on a tablet*): the rail's *Brief* opens the setup's three steps (`feature-brief`, a dialog
  nearly the window), the review over the real PDF (drawn by `PdfRenderer`), *Save to device* and *Share PDF*. Under it core-data's `BriefKeeper`
  (the Library record or the pack's item; a change kept as a transform and laid over the version it is written or sent to), `BriefSources` (the
  pack's items, or this session's open LZ/PZs with their measured slope and route sets) and `BriefSession` (compiled off the main thread with the weather
  at the planned times, `weatherAt`; printed with the strips' grid, core-brief's `StripGrid`); core-brief's `BriefEdits` are the setup's edits. Three
  independent reviews (parity with the web, concurrency and lifecycle, the screens) found real faults, all fixed with tests and checked by mutation:
  pack edits that could go out of order or crash the app, a Library brief never read again (the next change wrote over a newer version), a paused
  pack's changes dropped, the session left open for the next account, a field's words carried to another answer (no row was keyed), the script reset
  undone, the slope call never briefed outside a pack, the PDF renderer's thread lock and leftover files, a narrow window's layout. On the way, two
  changes outside Android: `compile.json` gained a slope case (the web's slope call, now held on both sides) and the web keeps its weather when a
  refresh gets no answer, as the plan always said.

- **B9** Android's live sandtable on a tablet (AGENTS.md §17, *The sandtable on a tablet*): the setup's *Present sandtable* (on every step) closes the setup and
  draws feature-brief's `SandtableFrame` over the map where the tablet's frame has it (`TabletFrame`'s `presentation`: the map never moves; the rail, panes and band
  put away, kept composed): the bar, the steps, the constants and the script card. Each step is core-brief's `SandtableSteps` (the web's `sandtableSteps.js`, held strictly to every case of
  `sandtable.json`, from compile.json's model and from the brief compiled here), drawn from the model's `geometry` (`SandtableScene`: GeoJSON to MapLibre,
  `SandtableOverlay`, and the marks and words in Compose, `SandtableMarksLayer`) and flown to as the web's Leaflet frames it (`SandtableCamera`, clear of what
  lies over the map), held to Leaflet's own answers in the new `brief/sandtable-fit.json` (with the web's `fitInsets`). Editing is off while it runs, and it ends with its workspace, the account, End brief, Back or Escape. Its three independent
  reviews (parity with the web, lifecycle and concurrency, the screens) found, among others: a sandtable ending with its pack could leave the map on the pack's view (it now ends in the same composition, and a flight waits for the view to be given
  back); a flight asked before MapLibre has made the map was undone by its first camera (`MapHost.attach` now sets the camera first); an opening queued behind a
  close was never dropped; the review's foot cut its buttons at large text; TalkBack would have said "not selected" on every other step; each was fixed with a test. Rechecking them after the commit closed
  three more: the keyboard's focus goes back to the rail's *Brief* when the sandtable ends (the web's goes back to its Brief button), the bar names the open
  pack while its brief is still read and after a rename, and the card says "← → keys" while a keyboard is attached. The toasts' timing and their not being
  counted as covers are the web's own, and the labels' collisions are too. The brief's design boards were not to hand: the layout
  follows the web's, and the rail's *Brief* still opens the setup (AGENTS.md §15).

**Checks that ran (2026-10-19, the owner's Mac):** for B4, the backend's CI list (517 passed, 1 skipped), the web's contract and brief tests (all but
`mgrsGridFixtures`, a last-digit float difference on this Mac that predates the brief), `npm run build` with no new warnings, Android
`:core-network:test` and `:core-missionpacks:test`. For B5 (web only, no server code changed): the web's whole Jest suite (1,068 passed; only
`mgrsGridFixtures` fails, as before), `npm run build` with no new warnings, and the backend's `test_brief_pdf.py` and `test_contract_fixtures.py`
against the regenerated `compile.json`. For B6: the web's whole Jest suite (1,098 passed; only `mgrsGridFixtures`), `npm run build` with no new
warnings, and the backend's CI list (531 passed, 2 skipped: the live service needs Postgres); OP DK at 1:100,000 and the dogleg at 1:250,000
printed again through Gotenberg after the review's fixes and looked at. For B8: Android's full `./gradlew test lintDebug assembleDebug` (5,633 tests,
3 skipped, none failed; lint clean), the screens drawn by Roborazzi at 1,280 and 840 dp and looked at, the web's brief and contract tests (165 and 14
in `useBrief`) and `npm run build` with no new warnings. For B9 (2026-10-20): Android's full `./gradlew test lintDebug assembleDebug` (5,769 tests with the recheck's, 3 skipped, none
failed; lint clean), the web's contract tests (275 of 276; only `mgrsGridFixtures`, as before) with `sandtable-fit.json` written by Leaflet 1.9.4 itself, the
sandtable drawn by Roborazzi (1,280 × 800 in the three palettes, 845 dp with the steps over the map, large text on a short window) and looked at, and
14 mutations of the step's fixes (the flight, the session's order, the screens), every one killed.

**Not verifiable here, or not tried:** a brief built from real LZ/PZs and routes in the browser (only the fixture's OP DK, in Jest and the PDF test,
and seeded as a pack for the sandtable); the sandtable's animated flights (the agent's browser reports reduced motion, so only the unanimated fit was
seen) and on a projector or a presenter's clicker; the weather `at` answers from aviationweather.gov live; Gotenberg on the Coolify VM.

**Not verifiable here (B8):** the platform's renderer drawing a real PDF (Robolectric's cannot open one), the share sheet and the picker, a real
Gotenberg print on a device, the setup over the real map, and TalkBack: the owner's device run.

**Not verifiable here (B9):** the figure MapLibre draws (its GL layers are compile-only: the Roborazzi pictures draw the same scene over a stand-in map, and
the marks and words are the app's own Compose), the flights on a real map and the view given back, a presenter's clicker on a real tablet, TalkBack, and the
frame on a tablet's real insets and cut-outs: the owner's device run. **To try first on a device:** present OP DK's brief from the setup, step through it
with the arrows and a clicker (Page Down), pan the map mid-step and step on (it flies again), turn the tablet, then End brief (the map comes back where it
was); in a pack, have another member change an LZ/PZ while presenting.

**Next, in order** (docs/MISSION_BRIEF.md §5): the owner's device run of B8 and B9 on a tablet; then §5's *later*. Owner's call from B9 (AGENTS.md §15):
whether the rail's *Brief* becomes the web's Brief menu, with *Present live sandtable* one tap away. Owner's calls the B8 reviews raised
(AGENTS.md §15): one identity for a session's LZ/PZ and route in a Library brief (the web and Android name them differently), two brief items made
when two members start a pack's brief at once, and the weather asked an hour off across a change of daylight saving; each web first. **Owner steps:** deploy Gotenberg on the VM (AGENTS.md §9 has the command line) and set `GOTENBERG_URL`; check Mapbox's
terms for printed figures before the PDF ships; tick *Mission brief* for testers in the admin dashboard.

**Local setup left on the Mac:** `frontend/.env` (gitignored) points at a backend on **port 5055**, because macOS's AirPlay receiver holds 5000; run the
backend with `cd backend && venv/bin/python -m flask --app app run --port 5055` (add `GOTENBERG_URL=http://127.0.0.1:3091` and
`docker run --rm -p 3091:3000 gotenberg/gotenberg:8.21.1 gotenberg --chromium-disable-javascript=true` for the PDF). A test account
`brief.check@local.ezpz.test` (admin) is in the local SQLite database; its password is not recorded in the repo: make another with `dev_user.py` if needed.

---

## 0. The slope tile, contour lines and the slope profile (2026-10-13/14)

Branch `feat/slope-landing-heading` (on `feat/android-screens`). The owner's rules (UH-60 limits 6° nose down, 15° nose up, 15° across, less
2° per full 5 kt of steady wind, no nose down above 15 kt) and how the tile judges are in AGENTS.md §13, *Slope landing
limits*; the open questions (true or magnetic, a server without `byHeading`) in §15.

**Done:** the server sends `byHeading` (`backend/terrain_provider.py`, `contracts/openapi.yaml`, the recorded responses); the web's
`feature/terrain/slopeCall.js` and the tile's lines; Android's `LzSummary.slopeCall` (core-planning) held to every section of
`contracts/fixtures/planning/summary.json`, the landing heading read by `Doghouses.landingHeading` (core-model), the wind from the open
LZ/PZ's weather report (`LzSummary.slopeWindOf`), the tile's lines under the value and in its spoken description (`DiagramsScreen.kt`,
`slopeSpoken`), looked at as Roborazzi pictures in dark and night at text 1.0 and 2.0.

**Checks that ran:** Android `test testDebugUnitTest lintDebug assembleDebug` (5,340 tests; the only failures were live-server read
timeouts while the web's Jest ran beside it, and `:core-missionpacks:test :core-network:test :core-sync:test` then passed alone); the web's
whole Jest suite (909 passed) and `npm run build`; the backend list of `.github/workflows/backend.yaml` (442 passed, 2 skipped: the live service needs a local Postgres).

**Changed 2026-10-14 (the owner):** the recommended heading went (web, Android, the fixture): a rule cannot see the terrain round an LZ.
The tile says what was judged at the landing heading instead. **Built since, on the owner's word:** contour lines on the slope map and a
slope profile along and across the landing heading (server, web, Android; AGENTS.md §13). Both were seen in the browser against the local
DEMs and as Roborazzi pictures on Android; neither on a device. **Next:** the owner's look at both, then a device run.

The server must reach staging (`develop`) before a device shows any of it: until then every analysis has no `byHeading`, and the apps say
HEADING REQUIRED at 15° and "Heading limits not checked (server not updated)".

---

## 1. Where things stand

- **Branch:** `feat/android-screens`, on `feat/android-mission-packs` (pushed; the engine is done, so a PR to `develop` is the owner's call, now or with the screens), which is
  `develop` plus the mission-pack work below. **The screens so far** (`docs/ANDROID_SCREENS_PLAN.md`): S1 the redesign's palette, type, corners and icons (`4c47435`); S2 the
  phone's frame: the search bar with the account menu (night mode there), the Personal and Import chips, the map's buttons, the crosshair at the middle of the map that shows
  with its readout pill (tap to copy), the panel in a sheet with three heights, and the bar of panels with their counts (`1e058d2`); S3 the LZ/PZ panel: the open LZ/PZs
  (a working set kept on the device), *New LZ/PZ at the crosshair*, *Save* as naming with its sheet, the plan page (aircraft, analysis and slope switch, tools, graphics),
  and the planning tools as modes over the map with *Add here*, *Done* and *Cancel all*; S4 the Routes panel: the route sets open now (a working set kept on the device),
  the set on the map as the design's card with *Save* as naming, *Export .msnx* with the threats when asked, each route's share, hide and plan, *Sketch a route* and *Import .msnx*
  pinned at its foot, and the route's plan as a page over the frame (the whole route's date, temperature and fuel flow; a card for each named point, applied as it is typed).
  S5 the Threats panel (the design's: the threats with their reach and mask, *.ths for AMPS*, *Remove all* asked about first) and each threat's form as a
  full-screen dialog, also opened from the held bar; the window is no longer kept out of screenshots and the picture no longer expires (it is wiped at sign-out).
  S6 Import: the Import menu (from the Import chip and the Imports panel's header), the review every file goes through (where each goes: points to the Library or this
  session, threats to the device, a mission to the Library; *Remove*, *Cancel*, *Import N files*), and the Imports panel (the sets of points, the missions with *Open in Routes*,
  the threat files with *Open in Threats*, the held point's card). S7 the Library: a page over the frame from the Library chip, *Browse all* and the conflict
  *Review*, with tabs, search, sort, *Open*, Rename, Duplicate (a mission with its file) and Delete; *Keep both* / *Replace* for a name taken, there and in the Save sheets;
  every record now notes when it last changed (database version 4). S8: a long press on nothing opens the map's menu above and to the right of the press (New LZ/PZ
  here, Add threat here, Add route point here, Copy grid), and a slow one-finger quick zoom no longer counts as a long press. S9: a window 840 dp wide and 480 tall has the
  tablet's frame (a rail, the panel in a pane beside the map, the open LZ/PZ's or route's plan in a pane on its right, the Library and the import review as large dialogs);
  turning a tablet or opening a foldable moves the map and the panels between the frames. P1: the workspace chip opens the switcher (invitations, the Library, the
  packs, the teams; a menu at the rail's tile on a tablet), opening a pack sets the person's own LZ/PZ and routes aside and the Library puts them back, the pack open last
  opens again at launch, an invitation link's pack opens once joined, and the chip shows the pack's name, who is here and how it stands (new module `feature-packs`). P2a: the Pack
  panel (a Pack tab first while a pack is open): the pack's items by kind with what changed since the person looked, its history, opening, closing, renaming and removing its items, and
  the pack's notices in toasts. P2b: in a pack the LZ/PZ and Routes panels are the pack's (its items with their state and who has each open, no *Save*, where it is saved), a new LZ/PZ
  and a sketch go into the pack, and the person's own stay set aside until the pack is left. P2c: in a finished pack (or as a viewer) nothing that would change its items is
  offered or done, and what was under way ends with a toast. P2d: a finished pack says so at the top of the panels (a band on a tablet), its owner finishes and
  reopens it, and anyone in it duplicates it. P2e: everyone else in the pack is a named pointer on the map, and this person's crosshair is theirs. P3a: the switcher's *New pack* makes one (a name, who can
  open it: just them, a team, or people found by name or invited by email) and opens it. P3b: the Pack panel's members row opens Members (roles, hand over, take off,
  invitations waiting, sharing with a team), its owner's *Invite* at the invite field. P3c: the switcher's *Manage teams* opens Teams (new team, rename, members and roles,
  inviting by email, leaving and deleting). P4a: the person's own LZ/PZs, sets of routes and sets of points go into a pack as copies (*Add to Mission Pack…* in the
  Library and the panels' menus; *Add to pack* in the Library and *Add from Library* in the Pack panel while one is open; New pack's *Start with items from my
  Library*), their last change uploaded first and each copy made once however often it is tried. P4b: a copy whose original changed is updated from it (*Update* on its
  row, after this person's own changes and the original's are in), and any item is saved to the person's own Library (*Save a copy to Library…* in the menus, and on a
  finished pack's banner), on the device, as they see it. P4c: the edits a pack would not take are offered at the top of the Pack panel, to keep in the Library
  or (asked first) discard.
  **To try on a device:** the crosshair and the map gliding together when the sheet settles, a fly-to while the sheet
  moves, dragging the sheet by its content, the status bar's icons, night mode's dimmed map, placing graphics by tapping the map while a tool's mode is on, and on the route's
  plan the keyboard, TalkBack and the date picker; none of that can be seen here. `develop` was fast-forwarded on 2026-10-08 over the stack that had been waiting (the Android branch
  `claude/jolly-wozniak-i7rlrq`, `feat/mission-packs`, the web menu redesign `feat/menu-redesign` and `fix/menu-redesign-ui`), then
  took the fixes that made CI green on it: the tests run on JDK 21 (`ed6ec02`), no machine's JDK in the shared Gradle settings
  (`7be70fe`), a lint error (`9d4d128`) and the terrain catalog's boot-time bug (`75f9450`). The backend restructure
  (`refactor/backend-structure`) is **not** in `develop` and nothing here depends on it.
- **Mission packs and the web menu redesign are built on the web and backend** (`docs/MISSION_PACKS.md`, `docs/MENU_REDESIGN.md`),
  still off for everyone but admins and ticked testers (`entitlements.DEFAULT_OFF`), and the live service is not deployed (clients
  poll). **Android has the pack engine built and no screens** (next bullet). The native screens for both are in
  `docs/native-design/` (read its README; the screenshots themselves are gitignored).
- **The Android mission-pack engine** is built to `docs/ANDROID_MISSION_PACKS_PLAN.md` (the design, the owner's decisions, the
  commit plan with each step marked done or to do); AGENTS.md §17's `core-missionpacks` and `core-network` rows say what each piece
  holds now. **Done:** A1 a background sync restores the session in a process the app did not start; A2 every pack, team, invitation
  and search response recorded with strict types; A3 a typed call for each route; A4 the live stream; A5–A8 the new pure module
  `core-missionpacks`: the operation rules, the diff, the client session, each person's own fields and the history sentences, all held
  to `contracts/fixtures/packs`; A9 the client that sends, catches up and follows one pack, offline included; A10 the engine that runs
  every pack and keeps what a pack refuses (`99bc493`). On the web and server, first: five web session bugs fixed (`67554b1`), a refused
  ops batch says which of its operations it already took (`taken`, `718ed9c`), and every pack write route reads the caller's role
  and the pack's deletion again under the pack's lock (`f204a8f`). Then, built side by side on two PCs and merged (`138bae3`): A11
  keeps packs and their unsent edits on the device (database version 3, `4b56144`, and `3a616ca` so a record past Android's 2 MB cursor
  window can still be read), and A12 lets the existing LZ and route editors edit a pack's items (`fb0600d`), handed their pack stores in
  the commit after the merge. A13 runs packs with the app (`0de2522`, and `e832c62` for what its review proved), merged after them and
  joined to A12's editors in the commit after that merge. A14 accepts an invitation link once the person can open packs (on
  `feat/android-mission-packs-a14`), and a toast says what became of it; its review proved one account's answer could be shown to the
  next account to sign in, and a retry that failed as the last one did left no toast and no way to try again, both fixed. Below the
  server's minimum version the app now sends nothing, packs, invitations or library, until it is updated, and says so in a banner when it
  cannot show the update screen (owner, 2026-10-10); a second review found the first version still accepted links and heard a raised
  minimum only at launch, both fixed. A15 wrote the whole down (AGENTS.md §17, *Mission packs on Android*; §15 lists the web's side of what
  the port does not copy). **Not built:** the screens. Apart from that toast and banner, nothing a person sees has changed yet: nothing opens
  or shows a pack until the screens.
- **What exists** — the plan's P0 and P1 (shell, auth, map, diagrams, analysis, graphics, units, aircraft, boundary) and P2 (routes: sketch, plan, nav log, winds, elevations, `.msnx`
  export, GPX/FPL hand-off; local points; weather; the local-only threat picture; files opened from Files, mail and the share sheet; a mission's routes brought in as a copy). Per-module
  state is in AGENTS.md §17's table. **Not in P2 yet:** an ATAK data package. (Saved missions, which keep the `.msnx` as the document and write edits back into it, and dragging and turning on the map are built: AGENTS.md §17, *Saved missions* and *Dragging and turning on the map*.)
- **First device run, 2026-10-04 (emulator):** sign-in, the map, the sheet, the system picker, importing a real AMPS mission (GOAT SUCKER.msnx), tapping and long-press-dragging a route point
  all worked, the upgrade of an existing database to version 2 kept its data, and the move reached the mission file on the server (one point of 91 changed, every other part byte for byte).
  It also found a bug no JVM test could: Android's XML parser throws on `setXIncludeAware(false)`, so **every mission import had been refused on a device**. Fixed (AGENTS.md §14).
- **The owner's first two-device run, 2026-10-13** (a phone and a tablet in one pack, against a local backend, so polling): a route checkpoint moved on one device did not move on
  the other, or showed elsewhere, and the web then drew the route twice. A point moved off its fetched ground (or a clock moved to another point) left a null the Android
  reader refused, so the editor wrote the route a second time; fixed, and a point's place now goes whole (AGENTS.md §17, *Mission packs in the editors*). **A pack touched
  before the fix may hold a route twice**: delete that route (on the web, or on Android with the fix; it takes every copy) and draw it again, or make the set again.
- **The settings audit, 2026-10-13** (the owner's rule: every setting of a planning graphic or a route syncs on change in a pack; only the base map, the LZ outline, the
  slope map and hidden routes stay each person's). Every setting is now tried between two devices (open there, closed there, undone, and made by two people at once;
  `LzPackSettingsTest`, `RoutePackSettingsTest`, and `PackScenarioBook` on the fake, in Room and on the real Flask server), and what the web writes for each is pinned
  (`contracts/fixtures/packs/settings.json`). What was fixed, each with a test that failed first: an LZ/PZ graphic's place, the target, what an analysis found, and a
  route's altitude, speed and wind went as the parts that changed, so two people's changes could stand half each; a point's first values went as one object, so one person's
  could replace another's; the route's clock form and the unit builder put back what changed under them; a ground elevation or a wind fetched for a point that moved meanwhile
  landed on it; the web's clock threw on a pack's null; and the UH-60 picture's tail loop was cut off at high zoom. What is left for the owner is in AGENTS.md §15 (an
  aircraft placed as a person's own airframe, two clocks set at once, and the web's own partial values and forms).
- **The phone's frame, the owner's changes of 2026-10-13** (from a run on a Samsung S26 Ultra; the Galaxy Tab Active5 in portrait has the phone's frame too, and its
  tablet frame in landscape is unchanged). Space on a phone is tight, so the search bar is folded into a round button at the start of **one row** with the workspace chip,
  Import, the Library and the account, and opens across the row when touched (AGENTS.md §17, *The phone's row*); a pack's faces and the chips' arrows give way before a
  name is cut. And **a tap on the map that holds and puts down nothing hides the frame** (the row, the sheet, the bar; the readout, the map's buttons and the crosshair
  stay) and the next shows it again, as do Back, choosing a panel and opening something (AGENTS.md §17, *The phone's frame hidden*). **The line that said how an open
  pack stands went into its chip**: the chip's leading icon is a shape of its own for each state (synced, saving, offline, opening, paused, cannot reach, gone; finished as
  before), and words show under the row only for what wants attention (offline, paused, cannot reach, gone) once it has lasted 2 s; the switcher's row for the open pack
  says it in words, and the tablet's line is unchanged (AGENTS.md §17, *The workspace switcher*). **To try on a device:** the keyboard
  coming up as the search opens, the row over real imagery, the frame sliding away over the real map with the crosshair gliding to the middle, that a double tap or a quick
  zoom never hides it, the chip's icons over imagery in sunlight, and TalkBack (the frame never hides while it explores by touch; the line under the row is announced).
- **Duplicate history lines for a doghouse turned, 2026-10-13** (the owner's report). A turn is one change sent as two operations (the landing heading it gives and its
  own heading), and the pack logs each operation as an event with the change's sentence, so every history showed it twice (an analysis five times). The web and Android
  now show a run of one person's events on one item with one sentence as one line (`packHistory.js` / `PackHistory`, held to `contracts/fixtures/packs/history.json`).
  And the web, taking someone else's change, no longer follows a fresh list of the same doghouses: it had put the doghouse's heading back over a landing heading typed
  elsewhere, on the live stream sent every turn back as two changes of its own, and looped for ever on a read-only pack's LZ/PZ without the headings. Android sent
  nothing extra (tried on two accounts and one, open, closed and live). Then the owner's wording, on the web and Android: a turn names the doghouse and its heading
  as the box shows them ("Colin M. turned doghouse RP1 to 105° on LZ HAWK.", not "turned a doghouse"), so turns a second apart are a line each; one turn is one line.
- **The owner's requests of 2026-10-15** (`feat/android-panel-close-and-tools`): the phone's panel closes from its header (the bar brings it back), the LZ/PZ tools are also
  under the open LZ/PZ's card, and the map's long-press menu has *Add object here* (a helicopter, PZ marker, sector, go-around or unit at the press). AGENTS.md §17, *The phone's
  panel closed*, *The LZ/PZ panel* and *The map's long press*; `docs/ANDROID_SCREENS_PLAN.md`, after P4c. To try on a device with the rest.
- **Everything else is still compiled and unit-tested on a JVM only.** The map layers (MapLibre),
  the share sheet, the system picker, the JavaScript symbol sandbox, Keystore, Google sign-in, WorkManager, R8 output
  and Android's own SQLite opening the `.ths` template are **compile-verified only** (each is flagged
  "not verifiable here" in AGENTS.md). The first device run is the first real test of all of them.
- **Last full verification** (`./gradlew test testDebugUnitTest lintDebug assembleDebug`, §3): green for every engine commit,
  A1–A15 (A11 on the owner's other PC), and for the merge of A11 and A12, on the owner's workstation (the live-server tests ran, none skipped), and for each commit of
  the settings audit (2026-10-13). CI was green on `develop` at `ed6ec02` (Android on
  JDK 21, the backend's contract tests) and runs again on the PR. Re-run it after any change before pushing.
- **The owner's Windows workstation builds the app** since 2026-10-08 (§3 has the setup): a full build is about 7 minutes warm, 36
  minutes cold. The owner works from more than one PC; whichever builds needs §3's setup and its own `android/local.properties`.

---

## 2. Next work, in order

(The mission brief has its own next steps: §0a.)

0. **Two devices in one pack again**, after the settings audit: what only a device run shows is the live stream end to end, a drag and its pointer on the real map, and
   the clock form and the unit builder with someone else's change arriving while they are open. Then the owner's calls in AGENTS.md §15 (an aircraft's own airframe
   on other devices, two clocks, the web's partial values and forms, which want the web first).
1. **The screens**, S1–S9, P1, P2a–P2e, P3a–P3c and P4a–P4c done and reviewed, the fixes in (`docs/ANDROID_SCREENS_PLAN.md`, each step's *Reviewed*): the
   owner tries the app on a device now (the owner's order, 2026-10-12: no device testing before S7–S9 and P1–P4), then *T*
   (threats saved and shared: web and server first) beside S7, now that the mission-pack engine is built (A1–A15: `docs/ANDROID_MISSION_PACKS_PLAN.md`; what each piece holds is in
   AGENTS.md §17, *Mission packs on Android*). **The plan is `docs/ANDROID_SCREENS_PLAN.md`** (the owner's decisions, the steps S1–S9 and
   P1–P4, how each is checked), on `feat/android-screens`. First the redesigned shell (2), then the pack screens in it (3); both are drawn in
   `docs/native-design/`. **How the engine was built**, worth keeping for the screens: one commit per step, implemented, reviewed
   independently, fixed, and committed only with its module's tests and the full build green. A1–A10 had two reviewers each; A11–A14 one
   each (A14 a second for its fixes), and every one found something real (A12 a blocker: a change arriving while an item opened could be
   reverted for everyone; A13 four ordering bugs a queued test dispatcher hid, AGENTS.md §14; A14 one account's invitation shown to the next,
   and a too-old app still joining packs). Each fix was checked by breaking it and seeing its test fail. The plan's *Deferred to the
   screens* lists what the screens owe the engine (the keep-or-discard offer for dropped edits, the Library-or-pack workspace, opening a
   pack once an invitation is accepted, read-only gating, the presence pointer).
2. **The redesigned shell** to `docs/native-design/` (the AP and AT screens): the navigation bar (LZ/PZ, Routes, Threats, Imports), the
   workspace chip, Import, the Library page, the save dialog, the import review; the tablet layout (material3-adaptive). Settle §5's design
   decisions first. `HomeViewModel` (346 lines, 21 dependencies) has no notion of a workspace; the Library-or-pack workspace belongs here.
3. **The pack screens** in that shell: the switcher, New pack, the Pack panel (Items, History), Members, the finished banner, invitations.
4. **Optionally mutation-check the threat slice**: `HomeViewModel.mapTapped` ordering, `ThreatStore`,
   `ShareExport` retention and `ThreatsViewModel`, the way the weather code was (surviving mutants become tests).
5. **Open files from outside the app** (#52): **built** — `.LPS` and `.ths` from Files, mail and the share sheet, asked about before anything is
   imported (AGENTS.md §17, *Files from other apps*). Still to do on a device: confirm Files/Gmail/the share sheet offer the app and hand over a
   readable address, and what media type real senders use (the manifest declares octet-stream and the two SQLite types, nothing broader).
6. **Mission import and saved missions** (#53, #54): **built.** An `.msnx` opened from outside, or chosen with the Routes tab's *Import mission*, is offered and **kept as the saved document**
   (`kind: mission`, with its file, synced); the person's changes are written back into the file (AGENTS.md §17, *Saved missions*). Tried against a real AMPS mission on an emulator. Still to
   do: the sheet and the file picker on the *tablet* with real missions of other shapes (one with a point moved, inserted and renamed, then opened in AMPS to confirm it opens), and what an
   open mission does when a pull replaces its record while it is open (it is overwritten by the next save, as for sets).
7. **Route hand-off**: **GPX and Garmin FPL built** (a route shared from its card; AGENTS.md §17, *Sharing a route with another app*). Not built: an ATAK data package (a zip with a
   manifest) or KML/KMZ, and threats to ATAK; both want the owner's say on what ATAK crews expect. **Dragging on the map is built** (a long press picks up a graphic, a threat or a route
   point; a tap holds it and shows the bar with the turn buttons); boundary corners are not draggable yet. The admin-attached AMPS template (`resolveExportTemplate`) is not built.
8. **Later phases** (plan): P3 offline map packs / on-device viewshed / the remaining exports; P4 the 3D view. The online terrain mask is built (the held
   threat's *Show terrain mask*; AGENTS.md §17, *The terrain mask*); KMZ and QR calls exist on the backend but **no Android call is made**. The owner approved a transient send *only when the person
   explicitly asks* and the server must not retain it; an offline viewshed is not built.
9. **When the initial build is done: the dev environment / device login session** the owner asked for (§4).

---

## 3. How to build and test

The owner's shell is Windows PowerShell 5.1 (no `&&`); the agent sandbox is Linux. From `android/`:

```bash
# Everything (what CI-equivalent verification means here). Slow: run it in the background and wait for the exit line.
EZPZ_LIVE_PYTHON=<python with the backend's packages> ./gradlew test testDebugUnitTest lintDebug assembleDebug --continue --console=plain -q
# One module / one class
./gradlew :feature-workspace:testDebugUnitTest --tests '*ThreatsViewModelTest'
# Pictures of a screen (Roborazzi on the JVM): output in <module>/build/screenshots/*.png, view them
./gradlew :feature-workspace:testDebugUnitTest -Pezpz.screenshots
```

```powershell
cd android; .\gradlew.bat test testDebugUnitTest lintDebug assembleDebug --continue
python contracts/scripts/mgrs_fixtures.py check     # fixtures vs PyGeodesy
```

- **On the owner's Windows PCs:** Gradle needs JDK 21 or later to run the tests (Robolectric's Android SDK 36 sandbox); Android
  Studio's bundled one will do (`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` in PowerShell before
  `.\gradlew.bat`). `android/local.properties` (gitignored) holds `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk`, with
  `platforms;android-37.0` installed. The live tests want `EZPZ_LIVE_PYTHON` set to a Python with `backend/requirements.txt`
  (the backend's venv). Opening the project in Android Studio writes `android/gradle/gradle-daemon-jvm.properties`: gitignored, each
  machine's own. A JDK path committed in `android/gradle.properties` once broke every other machine and CI; keep machine paths out.
- **Pushing when Git Credential Manager gets in the way** (a *Select an account* window, or `/dev/tty` in an agent session with no
  terminal): push with the GitHub CLI's sign-in for that one command, in Git Bash:
  `git -c credential.helper= -c "credential.helper=!'/c/Program Files/GitHub CLI/gh.exe' auth git-credential" push origin <branch>`.
- `LiveServerTest` / `LiveAccountTest` run the real Flask routes in a child process and need `EZPZ_LIVE_PYTHON`; without it
  they are skipped (CI sets it). One of them (`the public config needs no token`) timed out once on a cold start while
  other builds ran, and passed on rerun.
- The SDK is not in the image: `android/local.properties` (gitignored) holds `sdk.dir`; the network policy must allow
  `dl.google.com` and `jitpack.io`; Maven Central sometimes throttles with 429 — re-run (Gradle caches what it fetched).
- **A live-server test can fail a full build on the owner's Windows workstation with `No buffer space available (maximum connections
  reached?)`**: with every module's tests running at once, Windows runs out of sockets for the live servers. Seen twice on 2026-10-12, in
  `core-sync`'s `LiveSyncScenarios`; it passes when that module's tests run alone (`./gradlew :core-sync:test`). Re-run the module, not the build.
- Warnings and lint are errors. Never commit a build that was not compiled.
- After a mutation run, rebuild clean (`:module:clean :module:test --no-build-cache`): the build cache can keep a mutated class.

---

## 4. Running the app against a backend on the owner's PC

**Why a local sign-in says "user/password incorrect".** A local backend uses its **own database** (`backend/ezpz.db`, SQLite, unless
`DATABASE_URL` is set), which does not hold the production accounts. The login route answers *"Invalid email or password."* (401
`invalid_credentials`) for a wrong password, for an address it has never seen, and for an account registered but not yet verified,
and deliberately says the same for all three so an address cannot be probed. So a refusal from a fresh local backend means the app
**reached it** (a wrong or unreachable server shows "There is no connection to the server" instead): the account is just not in that
database. Reproduced against the real auth routes (`backend/tests/live_server.py`): fresh database, any login → exactly that 401;
after the account exists → success with a refresh token.

Make an account in the local database (PowerShell; the password must be 15+ characters and not a common one):

```powershell
cd backend
# Use the Python you run the backend with. If that is a venv, call it directly (no activation needed); it may be in backend\venv, or at the
# repo root (venv\ and .venv\ are gitignored there). To find it:
#   Get-ChildItem C:\_dev\avtactools, C:\_dev\avtactools\backend -Directory -Force | Where-Object { Test-Path "$($_.FullName)\Scripts\python.exe" }
& "C:\_dev\avtactools\venv\Scripts\python.exe" dev_user.py you@example.com --admin     # asks for the password; --admin is optional
& "C:\_dev\avtactools\venv\Scripts\python.exe" app.py                                   # http://127.0.0.1:5000
```

`dev_user.py` makes the account verified, active and past the `.mil` gate, and signs out anything issued before if it already
existed. It refuses a non-SQLite `DATABASE_URL` or anything that looks like a deployment (`TRUSTED_PROXY`, `FLY_APP_NAME`,
`APP_ENV=production`), because it writes a password.

Point the build at it, and **rebuild** (the URL is a `BuildConfig` field, fixed at build time):

| Where the app runs | URL | Also |
|---|---|---|
| A real phone on USB | `http://127.0.0.1:5000/` | `adb reverse tcp:5000 tcp:5000` once per connection |
| The emulator | `http://10.0.2.2:5000/` | nothing |

Pass it as `-Pezpz.apiUrl=<url>` on the command line, or put `ezpz.apiUrl=<url>` in `%USERPROFILE%\.gradle\gradle.properties`
(what Android Studio's Run button needs), then sync and rebuild. A debug build allows cleartext **only** to `10.0.2.2`,
`localhost` and `127.0.0.1` (`app/src/debug/res/xml/network_security_config.xml`), so a LAN address will not work: use `adb reverse`.

**One command for all of this:** `.\android\run-local.ps1` checks the backend answers, opens the `adb reverse` tunnel (lost whenever the device reconnects), builds with `-Pezpz.apiUrl` set to the local backend, installs and launches. `-NoBuild` only re-opens the tunnel and launches; `-ClearData` starts the app clean; `-Device <serial>` picks one of several.

**A debug build says what it is doing.** The sign-in screen shows `Server: <url>` (so a build that did not pick up `-Pezpz.apiUrl` is
obvious) and, after a failure, `Last failure: HTTP 401 · invalid_credentials · "Invalid email or password."`, or what the network said
(`ConnectException: Failed to connect to /127.0.0.1:5000`), or the exception's type. A release build passes nothing and draws nothing
(`AuthScreensTest` holds both).

Other things that stop a sign-in, in the order they usually bite:
- **The default server** (`https://prod-ezpz-api.mcfadd.in/`) sits behind Cloudflare; an *Access* policy or a down tunnel answers
  with HTML, which the client reports as an unreadable answer (`Last failure: HTTP 200 · unreadable_response`, or a 403).
- **The native-auth backend** (`refresh_tokens.py`, `config_routes.py`, the `X-EZPZ-Client` header, `/api/auth/refresh`) is on `develop`
  but not on `main`. A server run from `main` (and so, likely, a deployed one) signs in but sends no refresh token (the session then lapses after 24 h) and has no
  `/api/config`. Run the backend from `develop` locally; a deployed one is **the owner's** to deploy.
- **Too many attempts** (429) after repeated failures for one address: the screen says so; wait or restart the local backend.
- **Google sign-in** needs `-Pezpz.googleClientId`, an Android OAuth client for `app.ezpztac.unreleased.debug` + the debug SHA-1 in the
  same Google Cloud project, and that client ID in the backend's `GOOGLE_CLIENT_IDS`.

---

## 5. Owner decisions

**Made on 2026-10-08, for mission packs** (in `docs/ANDROID_MISSION_PACKS_PLAN.md`): edits a pack refused are kept as "NAME (my
edits)", the web's name; signing out keeps unsent pack edits for that same account and sends them at its next sign-in, never under
another (a deliberate difference from the web, which drops them); refused edits go to the Library by themselves whenever their pack
is not open; `feature_disabled`, `affiliation_required` and an ended session pause the queue rather than drop it. Also approved and
done: the five web session fixes, the `taken` field, and the role re-read under the lock on every pack write route.

**Made on 2026-10-16, for the mission brief** (`docs/MISSION_BRIEF.md` §9): the formats' item lists and standard values are releasable; a finished
pack's brief is a pack item that stays open to change, and every member (viewers too) may brief; the PDF is rendered on the server by Gotenberg;
light data is our own sun and moon code; Android is tablet size and up only for now; every flow role (PZ, LZ, FARP, holding area, laager, false
insertion); only the presenter's map moves in the sandtable; the weather at planned times (`/api/weather?at=`); strip charts are in. And on
2026-10-19: Skyfield as the light fixtures' reference.

**Made on 2026-10-24, for versions** (AGENTS.md §1, §6, §12): the web and the backend share the product's version, which goes to
2.0 with the next release; Android has its own, 0.x until the store (`ezpz.versionName`, now 0.1.0), and iOS will start at 0.x; the
API has its own (`contracts/openapi.yaml`, 1.0.0), and each client build lists the API majors it supports. A breaking change to the spec
fails CI until its major moves. **Owner step:** Android's version dropped from the product's 1.7.x to 0.1.0, so a
`MIN_APP_VERSION_ANDROID` set on staging or production to a 1.x value would now lock every build out ("Update required"): unset it, or
set it to an Android version.

**Still open:**

- **Gotenberg** on the VM and `GOTENBERG_URL` (the brief's PDF); Mapbox's terms for printed figures.
- **`applicationId`** — permanent once published; release builds are refused until `-Pezpz.applicationId=<id>` is passed.
- **Mapbox token** — if it is URL-restricted, a native app (no web referrer) may be refused; give the apps their own via
  `MAPBOX_PUBLIC_TOKEN`.
- `assetlinks.json` on `ezpztac.app` (needs the applicationId and the signing key) so emailed links open the app.
- Real `.LPS`, `.ths` and AMPS `.msnx` samples beyond the fixtures, for the readers and writers.
- Vercel *Ignored Build Step* so app-only PRs skip a web preview.
- Step *T* (threats saved and shared): whether a saved set is the whole picture or a named group, whether several can be open at once, and what the viewshed, KMZ and QR stores keep once threats are records.
- **For the redesigned screens** (`docs/native-design/README.md`, "Decisions to settle"): the save model (recommended: keep autosave, so the
  chips mean "not in the Library yet" rather than "unsaved"; the app is offline-first and the system can end it at any time); the new
  neutral palette in `contracts/tokens/tokens.json` (nothing on the web reads that file) and what becomes of the red night palette, which
  was not redesigned; several LZ/PZs open at once ("3 open · this session"; the app opens one at a time); and what to do with what the
  screens show and the app does not have (3D, map overlays, which need a new library).

---

## 6. Rules that will bite if forgotten

Full list in AGENTS.md; the ones that matter most for a fresh agent:

- **Unclassified only.** The mission brief's handbook is CUI-marked: never commit it or send it to a service; the templates carry its item lists
  and standard values in the app's own words, by the owner's leave (2026-10-16). Threats are never synced or persisted server-side; on the device they
  live in memory plus a sealed no-backup file, wiped at sign-out. **The only Android threat network call is the terrain mask, made
  when the person presses its button**: do not add another, or make this one on its own, without the person asking for it.
- **Agents do not deploy.** Never push `main`, run `fly deploy`, or trigger production. No new major dependency without asking.
- **The web is the reference; `contracts/` fixtures are the contract.** Never hand-edit a fixture; change the web (or backend)
  first, regenerate, review the diff, then follow in the clients.
- **Commits:** Conventional Commits, lowercase subject, scoped (`feat(android): …`, `test(contracts): …`); app commits do not
  bump the web version. Each ends with the attribution trailers the session provides. Update `AGENTS.md` in the same commit.
- **Codex may be working this checkout in parallel.** A file that changed under you is probably their work: re-read, never revert
  what you did not write.
- **Never invent a value for "no data"**; refuse, or show the app's own words. A server's internals (a Python exception) are
  never shown to a person.
- **Test traps worth knowing:** `advanceUntilIdle` does not run a test's `backgroundScope`; a `@Test` that returns a value is
  never run (`= runBlocking<Unit>`); `SyncStore.transaction` is not re-entrant; design-system buttons are full width (use
  `weight(1f)` in rows); never `pgrep -f` a pattern your own command line contains.
