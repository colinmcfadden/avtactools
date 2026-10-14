# Hand-off — Android app build

For the next agent or person picking this up mid-stream. **`AGENTS.md` §17 is the detailed record** (what each module is, the
rules that bit once, what cannot be verified without a device); this file is the *where things stand and what to do next*.
Read both, then `docs/NATIVE_APPS_PLAN.md` for the plan and its phases. Refresh this file in the same commit as any change
that moves the "where things stand" or "next" sections.

Last refreshed: 2026-10-13 (the redesigned screens on `feat/android-screens`: S1 the design system, S2 the phone's frame and S3 the LZ/PZ
panel reviewed together, S4 the Routes panel and the route's plan, S5 the Threats panel and its form, S6 Import (the menu, the review, the Imports
panel) S7 the Library (with record times, database version 4) and S8 the map's long-press menu and the Saved toast's *Open Library*, each reviewed on its own,
S9 the tablet, P1 the workspace switcher, P2a the Pack panel, P2b the LZ/PZ and Routes panels in a pack, P2c a read-only pack, P2d a finished pack (its banner, Finish, Reopen, Duplicate), P2e the presence pointer, P3a New pack, P3b Members, P3c Teams, P4a copies into a pack, P4b updating a copy and copies to the Library, P4c the edits a pack would not take, every review's fixes in: S1–S9 and P1–P4 are built, and the owner's device run is next. The owner tries it on a device once S7–S9 and P1–P4 are done. The owner decided threats may be saved and shared: AGENTS.md §2, and step *T* of
`docs/ANDROID_SCREENS_PLAN.md`; the screenshot guard and the 48-hour wipe went in S5). Since then, outside the plan: the web's MGRS grid on the Android
map, from the Layers menu (AGENTS.md §17, *The MGRS grid*), its code ported and held to `contracts/fixtures/mgrs/grid.json`; the fix for what the owner's first
two-device run found, a pack's route written twice (§1); the audit of every setting in a pack that followed it (§1); and the owner's changes to the phone's frame
from the same day's device run (§1): the search, the workspace, Import and the Library on one row, a tap on the map that hides the frame, and how an open pack
stands said on its chip.

---

## 0. Handed over part way: the heading-aware slope tile (2026-10-13)

Stopped on the owner's word part way through, to be finished on another PC. **Branch `worktree-agent-abeb769fa4f7bc5a6`** (the worktree
`.claude/worktrees/agent-abeb769fa4f7bc5a6`), on `feat/android-screens` at `c949890`; not pushed. Its commits, in order: `291ed2a` the server's limits,
`11f3cdf` the Android type for the new field, `1f0685c` the server's table by heading, `cb8a356` the web's tile and its fixture, then this note.

**The owner's rules** (a UH-60 pilot, 2026-10-13; to go into AGENTS.md §13 as his):
- Slope landing limits: **6° nose down, 15° nose up, 15° cross slope.** (The server, the web and Android all had nose high 6° and nose low 15°, the wrong
  way round; "nose high" is the ground rising ahead, the nose up.)
- The limits are reduced by **2° for every full 5 knots** of wind (0–4 kt none, 5–9 kt −2°, 10–14 kt −4°, 15–19 kt −6° …), never below 0°.
- **No nose-down landing when the wind is above 15 kt** (nose-down limit 0 then).
- The wind is the **steady** wind of the app's weather fetch for that LZ (the report the weather tiles show); **gusts are ignored**. With no report (no
  station, not fetched, offline with nothing kept) the limits are not reduced, **and the tile says so in words**.
- The tile shows the slope and its status as before and, under it in smaller text, a recommended heading that keeps the aircraft within limits.

**The design.** `/api/terrain-analysis` sends `byHeading`: the steepest nose-high, nose-low and cross slope for each whole heading 0–359 (360 entries, the
arithmetic of `directional`, to 0.1°). The clients do the rest, so turning the landing doghouse or a new weather report judges again with no request: the
limits for the wind; the status at the flight data's `landing_hdg` (the landing doghouse's heading, read by its leading integer as the box reads it, but an
unreadable one is *no* heading, not 000): a slope is over its limit only when **greater** than it at 0.1° (0.0 is never nose down; a nose-down limit of 0 allows
none), any one over is LIMIT EXCEEDED, else CAUTION above 10° and LANDING; with no landing heading the recommended heading is judged. The recommended heading is a
multiple of 5°: of those within limits, the closest to **into the wind** (speed above 0, not variable, with a direction), else the closest to the **landing
heading**, else the one with the **most margin**; with none within, the one whose worst excess is least, named with its limit and by how much. Closeness is the
short way round, ties go to the smaller heading. **HEADING REQUIRED only when the server sent no `byHeading`** (a server from before it: staging and
production until this is deployed), with the line "Heading limits not checked (server not updated)".

**What the tile says under the slope** (the web's words, pinned in `contracts/fixtures/planning/summary.json`): first line `Recommended 270° · into wind`,
`Recommended 190° · 80° off the wind`, `Recommended 090° · landing heading`, `Recommended 225° · 45° off landing heading`, `Recommended 000° · most margin`, or
`No heading keeps the whole LZ within limits: best 045°, nose up 3.4° over`; second line `No wind report: limits not reduced for wind`, `Wind calm: limits not
reduced`, `Wind 4 kt: limits not reduced`, `Wind 12 kt: limits −4°` (U+2212), `Wind variable 7 kt: limits −2°`, `Wind 16 kt: limits −6°, nose down prohibited above 15 kt`.

**Done:**
- Server (`291ed2a`, `1f0685c`): `UH60_LIMITS_DEG` fixed (`backend/terrain_provider.py`); `byHeading` from `_slope_by_heading`, which runs
  `_directional_summary`'s own arithmetic (`_along_and_across`, `_steepest`) over only the cells on the edge of the gradients' convex hull
  (`_steepest_candidates`: a linear function is greatest at a hull corner; a margin keeps cells that tie to rounding), so 360 headings cost a few dozen cells;
  tests in `backend/tests/test_terrain_provider.py` (the limits, which failed before the fix; the table equal to `_directional_summary` at every heading on rough,
  hilly and whole-metre ground; a tilted plane's components; level ground with no -0.0; the analysis end to end); `contracts/openapi.yaml`; the recorded
  responses re-recorded (`contracts/fixtures/network/responses.json`: only the two terrain-analysis lines changed); `backend/TERRAIN_DATA.md`.
- Android (`11f3cdf`, `291ed2a`): `HeadingSlope` and `TerrainAnalysis.byHeading` (null from an older server) in `android/core-network/.../Dto.kt`;
  `TerrainCallsTest` expects the corrected limits. The analysis already keeps the whole `TerrainAnalysis` in memory (`AnalysisService`'s `SlopeState.Ready`), so
  the table rides with the slope state, never in the document.
- Web (the last commit): `frontend/src/feature/terrain/slopeCall.js` (the pure helpers: `slopeWindOf`, `slopeLimitsFor`, `landingHeadingOf`,
  `recommendHeading`, `slopeCallFor`; `slopeStatusFor` is gone from `MissionSummary.jsx`); the tile's lines (`MissionSummary.jsx`, `.slope-advice` in `App.css`);
  the landing heading passed down (`App.js` → `Controls.jsx` → `MissionSummary`); the weather (`feature/weather/useWeather.js`): each report keeps where it is
  for and its steady wind, an answer that comes after a later request is dropped, and `App.js` fetches the weather of the analysed LZ/PZ on the map when it
  changes (`ensureWeather`: once per place), so the tiles and the slope's wind are that LZ/PZ's, never another's (`slopeWindFor`). Tests:
  `slopeCall.test.js`, `MissionSummary.test.jsx` (all six failed against the old tile: 8° nose down was LANDING), `useWeather.test.js`; the fixture
  `contracts/fixtures/planning/summary.json` regenerated from `frontend/src/contracts/webFixtures.test.js` (sections `windLimits`, `landingHeading`, `ground`, `slope`).

**Left, in order:**
1. **Android** (the fixture is ahead of it): **`:core-planning:test` fails** (`LzSummaryFixtureTest`, the slope test: the cases now name a `ground` and carry
   `landingHdg` and `wind`) until the port. Port `slopeCall.js` into `LzSummary` (core-planning; the limits, the judging, the recommendation and the words), held to
   every section of `summary.json`; the landing heading read in core-model's `Doghouses` (it can use the internal `JsValue.parseInt`; core-planning cannot), the
   wind from `WeatherObservation` (`windSpeedKt`, `windFromDegrees` normalised 360 → 0, `windVariable`; no observation or no speed is no report); then
   `DiagramsViewModel.summaryOf` passes `slope.analysis.byHeading`, `diagram.flightData` and the open diagram's `WeatherState` observation; `SlopeTileUi.Measured`
   carries the lines and `DiagramsScreen.kt`'s `SlopeTile` shows them under the value (smaller, `bodySmall`), with them in the tile's content description. Update
   `DiagramsScreenTest`, `DiagramsViewModelTest` and `DiagramsScreenshotTest` (it builds `LzSummary.SlopeCall` directly), add `TerrainCallsTest` assertions on
   the recorded `byHeading`, and take the Roborazzi pictures (dark and night, font scale 1.0 and 2.0).
2. **Docs:** AGENTS.md §13 (the owner's limits and wind rule, as his), §17 (*The diagrams*: the summary tiles, whose `slopeStatusFor` is now `slopeCallFor` in
   `feature/terrain/slopeCall.js`; take "the heading-aware slope" out of *Not done*), and §15 (the two notes under *True or magnetic*, below).
3. **Checks still to run:** the full Android build (`./gradlew test testDebugUnitTest lintDebug assembleDebug --continue`), `npm run build` (not run: the
   owner's call to stop), the whole web Jest suite, and the backend contract list in `.github/workflows/backend.yaml` (only part of it ran, below).
4. **The server must reach staging (`develop`) before a device shows any of it**: until then every analysis has no `byHeading`, and the apps say HEADING
   REQUIRED at 15° and "Heading limits not checked".

**True or magnetic.** Both are **true** as the app models them, so no conversion was added: METAR wind directions from aviationweather.gov are degrees true
(`WeatherObservation.windFromDegrees` says so too), and the doghouse box is turned from true north on a north-up map, as `landingHeading` is documented
("Degrees true", `contracts/openapi.yaml`). Two notes for the owner (§15): a crew that types a *magnetic* heading into the landing doghouse gets a box drawn, and
a slope judged, off by the declination (about 5° in north Georgia); and the table's headings are the terrain grid's, true north over Terrarium but the **UTM
grid's north over a local DEM**, turned from true by the grid convergence (about 1.7° at the edge of a zone in north Georgia). `directional` has always been so;
nothing corrects either.

**Checks that ran:** backend `tests/test_terrain_provider.py` (15 passed), `test_network_fixtures.py`, `test_openapi_contract.py` and
`test_contract_fixtures.py` (46 passed together), each with the venv Python on this machine; a mutation of the hull's selection and of its first cut each failed
the table tests; the web's `webFixtures.test.js` (8), `MissionSummary.test.jsx`, `slopeCall.test.js`, `useWeather.test.js` and `Controls.test.jsx` (29 passed);
ESLint on the changed web files (no errors in the app's code; `MissionSummary.test.jsx` uses `container.querySelector`, as `Controls.test.jsx` and
`shell.test.jsx` do, which the testing-library rules flag; `npm run build` does not lint tests). Android, `./gradlew :core-network:test :core-planning:test`
on JDK 21 with `EZPZ_LIVE_PYTHON` set: core-network 362 passed (`DtoFixtureTest` decodes the new `byHeading` strictly; the live-server tests ran);
core-planning 101 run, **1 failed as expected**: `LzSummaryFixtureTest` > *the slope tile makes the same call as the web* ("Key terrainData is missing in the
map": the fixture's new cases, item 1 above). **Not run:** `npm run build`, the full Jest suite, the full Android build and lint, the rest of the backend list,
and nothing was tried on a device or in a browser.

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

**Still open:**

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

- **Unclassified only.** Threats are never synced or persisted server-side; on the device they live in memory plus a sealed
  no-backup file for at most 48 h after the last change, wiped at sign-out. **The only Android threat network call is the terrain mask, made
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
