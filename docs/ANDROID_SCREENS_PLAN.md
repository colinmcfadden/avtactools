# Android screens: the plan they are built from

Status (2026-10-12): **S1, S2 and S3 built** (on `feat/android-screens`) and reviewed together, the review's findings fixed; **S4 built and reviewed**, its review's
findings fixed; **S5 built and reviewed**, its findings fixed; **S6 built and reviewed**, its findings fixed; **S7 built and reviewed**, its findings fixed; **S8 built and reviewed**, its findings fixed; **S9 built and reviewed**, its findings fixed; **P1 built and reviewed**, its findings fixed; **P2a built and reviewed**, its findings fixed; **P2b built and reviewed**, its findings fixed; P2c next. The owner tries the app on a device once
the whole build (S7–S9, P1–P4) is done (2026-10-12). **Threats may now be saved and shared** (the owner, 2026-10-12): a step
of its own, *T*, below. The redesigned shell first (S1–S9), then the mission-pack screens in it (P1–P4). Each step is one
commit (or a small stack), built the way the mission-pack engine was (`docs/ANDROID_MISSION_PACKS_PLAN.md`): implemented with its tests,
reviewed independently, fixed, and committed only with the full build green. **Where this plan disagrees with the code, the code wins**
(AGENTS.md §16), and AGENTS.md §17 says what each module holds.

## Sources

- **The design:** `docs/native-design/` (read its README first). `outlines/` (text and controls of every screen, committed), `tokens.json`
  (colour roles, type, component sizes), and `screens/*.dc.html` (the exact sources: every size, colour and string is literal; gitignored,
  fetched from the design canvas on 2026-10-10 with the Artifact tool, 81 screens). **`AP-Home` is not in the canvas**: the phone's main
  screen is built from `outlines/AP-Home.md`, with `AT-Home` and `IP-Home` for its look. To see a screen, open its `.dc.html` in the
  browser pane and take a screenshot (it renders without the design tool's script, which is downloaded code and is not run).
- **The web it adapts:** `docs/MENU_REDESIGN.md` and `frontend/src/feature/shell/`, `feature/library/`, `feature/imports/`,
  `feature/missionPacks/ui/` (the reference for behaviour, as everywhere).
- **What the screens owe the engine:** `docs/ANDROID_MISSION_PACKS_PLAN.md`, *Deferred to the screens*.

## The owner's decisions (2026-10-10)

1. **Save model: keep today's autosave.** Every change is saved on the device about 600 ms after it is made and synced at once, so work is
   never lost. In the redesigned screens *Save* names an item, and the state chips say whether it has synced rather than "Unsaved
   changes". Saving before syncing may come later, once the owner has tried this.
2. **Map overlays (KMZ, GeoTIFF, image with world file) are not in the initial build.** Import and Imports offer `.msnx`, `.LPS` and `.ths`.
3. **Palette: the redesign's neutral avionics palette**, taken as recommended when the owner cleared the screens to start with it the one
   question left open (2026-10-10). It is one file (`contracts/tokens/tokens.json`), so it is easy to turn back.

## Decisions made for this plan (the owner may overturn any)

- **The palette in the contract.** `contracts/tokens/tokens.json` grows from 16 colours a palette to Material 3's roles (the surface
  containers, `outlineVariant`, `secondaryContainer`, `tertiary` for packs, the inverse roles), plus the state colours the design uses
  (`saved`, `unsaved`, `pack`). **Dark** takes `docs/native-design/tokens.json`'s values. **Light** keeps today's light values and fills
  the new roles in the same family (the design has no light screens). **Night** keeps today's red-shifted values and fills the new roles
  in red, never bright (`ThemeTest` and `TokenContrastTest` hold every palette to WCAG and night to "no bright surface"). The toast
  follows the design (`inverseSurface`) in the day palettes and stays dark red at night.
- **Icons:** Material Symbols Rounded, as vector drawables in `core-designsystem` for exactly the icons the screens use (about 40),
  fetched from Google's icon set (Apache 2.0) and checked in. Not `material-icons-extended`: Google no longer maintains it, and it is huge.
- **Type:** the design's scale (`titleLarge` 22/28 and the rest of `tokens.json`'s `type`) on the system's Roboto. Roboto Flex is not
  bundled (a font file the owner has not asked for); grids stay in the monospace face they use today.
- **The session.** The design's "this session" is the LZ/PZs and route sets the person has open now, one of each active on the map, the
  rest "Also open". On Android it is a working set kept on the device per account (a preference, like `LastDiagram` today), so it survives
  a restart. Opening from the Library adds to it; *Close* takes an item out of it (never deletes); the Library holds everything.
- **Save, with autosave.** An item that still has its made-up name (`LZ/PZ 3`, `SKETCHED ROUTES`) shows *Save*, which opens the design's
  name dialog (`SaveLz`, `SaveRoutes`: what is and is not saved) and names it; one that has been named shows a disabled *Saved*. The state
  chip is the sync state: *Synced*, *Waiting to sync* (amber, as the design's "Unsaved changes"), or *Conflict* (the existing panel).
  "Analyze to save" stays: an LZ/PZ cannot be named before it is analysed, as on the web.
- **Navigation.** Phone: a `NavigationBar` (LZ/PZ, Routes, Threats, Imports; Pack first in a pack) under one `BottomSheetScaffold`
  holding the chosen panel; full pages (Library, route plan, members, new pack) as Navigation Compose destinations (already in the build).
  Tablet: a `NavigationRail` and material3-adaptive's list-detail and supporting-pane scaffolds (`docs/NATIVE_APPS_PLAN.md` lists
  "Material 3 adaptive" among the approved choices; the library is added in S9).
- **The account button** (top right, the person's initials) opens a menu: who is signed in, Day / Night, the app's version, Sign out. The
  sheet's footer of today goes there.

## Steps

Each step names what it builds, what holds it, and its check. "Screens" means a Roborazzi picture of each new screen in the dark and night
palettes, looked at beside the design's own picture of it.

**S1 — The design system. Built.** The palette above in `contracts/tokens/tokens.json` (36 Material 3 roles a palette, plus warning,
success and accent; `contracts/scripts/tokens.py` writes line heights too), `Theme.kt` building every role of the colour scheme from it
(`surfaceTint` transparent: the redesign separates surfaces by their container colours), the redesign's type scale and corners, the 113
icons the AP and AT screens draw (`EzpzIcons`, `symbols.py`), `Banner` as the design's tinted notice, toasts in the inverse roles, and
pill buttons. Tests: `TokenContrastTest` (every text pair the redesign draws, on every surface container, in every palette),
`ThemeTest` (no role left to Material's stock values; night never bright), `IconGalleryTest` (every icon loads and draws), and the
galleries' pictures. The app keeps today's layout, in the new colours. **Amended while building:** the shared components the screens
repeat (the state chip, the badge, the panel header, the list row) are built with the first screen that uses them (S2, S3), not ahead of it.

**S2 — The frame (phone). Built.** `MapHome` is the design's frame (`app/frame/`: `PhoneFrame`, `Panels`, `AccountMenu`): the search bar
with the account button, the chips, the map's buttons at the right (icons now; the compass only once the map is turned), the crosshair
and readout pill (the grid alone, tap to copy), the sheet, and the `NavigationBar` with its counts (threats in the picture, alert colours;
sets of local points, plain). The LZ/PZ panel holds today's Diagrams and Aircraft sections, Routes and Threats theirs, Imports the local
points. The account menu takes today's footer: who is signed in, **Night mode** (a device preference, `DisplaySettings`; the map is
dimmed under it), the version and the notice, Sign out. **Amended while building:**
- **Three heights need a sheet of our own.** Material 3 (1.4) has two, so `core-designsystem`'s `PanelSheet` is foundation's anchored
  drag with three detents (lowered to its header, 80 dp; half, 60% of the space; full, below the chips), Material's nested scrolling
  (a list scrolled up raises the sheet first), the design's handle and `PanelHeader`. Only what shows is laid out, so a footer can be
  pinned at every height (S4's Routes has one). Picking a tab is the web's rule (`pick`: another panel opens to half if lowered; the
  one showing lowers, or rises to half); the header's button opens to full and lowers to half; a tap on the handle steps up.
- **The crosshair is the middle of the map that shows**: the camera's padding is the search bar at the top and, at the bottom, the
  height the sheet rests at (or is settling at), never more than half's, so a full sheet slides over the map instead of moving it.
  The crosshair glides with the map (300 ms, the sheet's own time) and what was under it stays under it; a fly-to under way is folded
  into the new padding, not cut short (`MapHost`). The pill and the floating tools ride on the sheet's edge, no higher than half;
  toasts go above them, or to the foot of the screen at full height. `CameraState` carries the camera's padding, which the labels'
  projection reads, so they stay on their points while it glides.
- **Every panel stays composed and only the chosen one is laid out**, so each keeps its scroll, its file pickers, its export listener
  (a mission built while another panel shows still reaches the share sheet) and the threat panel's `FLAG_SECURE` (which went in S5).
- **The workspace chip is a label** ("Personal") until P1 gives it the switcher, and **the Library chip waits for S7** (today each
  panel lists everything). **Import** opens the system picker for up to five files at a time, each put to the person in the one dialog
  files from other apps use (`rememberFileImport`), so a file means the same however it arrives.
- **Routes and Threats are offered only while their features are on**, as on the web (`panelsFor`).
Tests: the tab rule, which panels are offered, the initials, the counts in words (`PanelsTest`); which panel shows, the tabs, the counts,
the header's heights, the readout's edge and the camera's padding at each height, and a tap above the sheet reaching the map
(`PhoneFrameTest`); the account menu (`AccountMenuTest`); night mode kept (`DisplayPreferencesTest`); the sheet's arithmetic
(`SheetAnchorsTest`); the frame's pictures at each height and at night (`FrameScreenshotTest`, beside AP-Routes and AP-Imports).
**Not verifiable here:** the real map under the frame (the camera's padding gliding, a fly-to folded into it), the status bar's height
on a phone (the pictures have none), dragging the sheet by its content, and night mode's dimmed imagery.

**S3 — LZ/PZ. Built.** The panel's pages (`feature-workspace`: `LzPanel.kt`, `LzPage`), over `DiagramsViewModel`, `GraphicsViewModel`,
`AircraftViewModel`, `BoundaryViewModel` and `ConflictPanel`. **Home**: the open LZ/PZ's card (name, grid, the state chips, *Save* or a
disabled *Saved*, *Plan*, rename, and a menu with Close and Delete), *Also open*, *New LZ/PZ at the crosshair* (the row names the grid it
will take), *Recent in Library* with *Browse all*. **Plan**: the chips, Save and the menu, the mission aircraft as one row with *Manage*,
the analysis (Analyze, Re-analyze or Stop, the slope map's switch, the summary tiles and the weather), the tool grid, the unit builder, and
the planning graphics with the inspector. **Every LZ/PZ** (the Library's stand-in until S7) and **the aircraft** (today's section). The
header follows the page ("3 open · this session"; on the plan the LZ/PZ's name and grid, with Back, as system Back does). Naming is
`SaveLzSheet` (a modal bottom sheet: the made-up name selected to type over, what is and is not saved, *Saves to your Library*), and a
toast says the name it got. The working set is `OpenDiagrams` (`core-data`), kept by `OpenDiagramPreferences`. **Amended while building:**
- **The search bar stays "go to this grid", and an LZ/PZ is started from the panel** (*New LZ/PZ at the crosshair*), not by typing its
  grid in the search bar as on the web. Under autosave every LZ/PZ started is a Library record at once, so a search bar that started one
  would leave a record for every grid looked up. The owner may overturn this.
- **A made-up name is `LZ/PZ n`**, the first number no LZ/PZ has (`LzNames`). *Save* shows while an LZ/PZ still has one; a name the Library
  already has becomes the first free `NAME 2`, `NAME 3` (the web's *Keep both*, without asking: *Replace* comes with the Library, S7).
  Naming is a step of the LZ/PZ's own undo, as a rename is. A made-up name is not a name: the sheet and a rename refuse one in words at the field
  (the web takes `LZ/PZ 1` as a Library name; here "named" is read from the name). Nothing is saved before it is analysed, named or not: one
  renamed first says *Analyze to save* until its analysis.
- **The working set is record ids only**, at most 20 (the oldest go first), never a mission pack's item, and it is not cleared at sign-out:
  like `LastDiagram`, an id names no place, and another account's are never found. Whatever opens an LZ/PZ puts it in the set (the panel, a
  launch putting the last one back, a mission brought in), at the end. *Close* takes it out and opens the one at the end that is still here (one
  reopened keeps its place, so that is not always the one opened before it); *Delete* does the same once the record is gone. What comes back at
  launch is the last one on the map only if it is still open, so the last one closed stays closed.
- **The tools are modes** (`core-data`: `GraphicPlacement`, `PlaceTool`; `feature-workspace`: `PlacementToolbar`). Helo, PZ, Sector, L-GA and
  R-GA each turn the map's taps into placing their graphic, wherever a tap falls (on another graphic too), with *Add here* for the crosshair,
  *Done* to keep what was placed and *Cancel all* to take it back. What a mode places in a row is one undo step (its edits coalesce under one
  key; an edit in between starts another); *Cancel all* takes back everything it placed as if it had never been placed (`DocumentSession.retract`:
  its steps leave the history, its graphics leave every other version, so neither undo nor redo brings one back), and what was done in between
  stays. A mode is a
  `DrawingMode` (`PLACEMENT`), so it never runs beside a boundary or a route being drawn; another tool finishes the first and keeps what it
  placed (and takes the sheet down again); opening or closing an LZ/PZ ends it. While one is on, the sheet lowers, the held object's bar hides and a long press picks nothing
  up. *Unit* opens the unit builder (it adds at the crosshair), and *Draw LZ* is today's boundary drawing, asking first when it would clear
  an analysis.
- **Not offered, because Android does not have them yet:** *3D*, *Export LZ card* and *Set capture area*, *Sketch a route* (from the plan),
  and the *LZ box* switch; *Plan* stands where the card's *3D* is. The toast's *Open Library* waits for S7.
- **The sheet could stop taking taps** (found probing S2): new anchors set while the sheet is moving leave foundation's anchored drag a target
  that an untargeted drag never clears, so a change of space while it settled left the sheet "animating" for good, and a draggable that thinks
  it is animating takes every press: nothing in the sheet could be tapped. `PanelSheet` animates to its target whenever the anchors change
  mid-settle. And the camera's padding follows the height the sheet is settling at, not a finger holding it (`PanelSheetState.heading`).
**Reviewed with S1 and S2** (one independent review, 2026-10-11: eleven problems proved by its probe tests, five more reasoned; each fix has a test,
the main ones broken to see their test fail, except where only a device can show it: the turn to north, and the held bar's way to the plan in
`MapHome`): threats drawn with no `FLAG_SECURE` for an account whose `threats` feature was off (the guard lived in the Threats panel, which is then
not offered; it is the map frame's now, and such an account has no threats on the map and cannot take a `.ths` in, as on the web); *Cancel all*
keeping a placement when an edit came between two, and leaving an undone one to redo; *More options* on a held graphic not reaching its inspector
(it opens the plan, which brings the inspector into view); the sheet offering the made-up name as one to save under; an LZ/PZ closed last coming back
at launch; a rename naming one not analysed; night's tinted chips and notices at 4.3:1 (night's text and status colours raised 7%, and
`TokenContrastTest` now holds the tinted pairs); the slope switch and the sheet's handle with no name for a screen reader; a plan page that outlived an
LZ/PZ closed from outside; a new tool not taking the sheet down again; a close reopening the record it had just made anew; a turn to north stopped by
the sheet settling; and lowered cutting off a header in large text (it grows with the header now).
Tests: the pages, what they show and what their buttons ask for (`LzPanelScreenTest`); the working set, made-up names, naming and its toast,
close and delete opening the one before, *Recent in Library*, the pages and the slope switch (`DiagramsViewModelTest`); the modes: one undo
step, *Cancel all* with and without an edit between, the go-arounds' sides, refusals, one tool finishing another (`GraphicsViewModelTest`); the
map's taps and long presses while a mode is on, and a mode ending when the LZ/PZ changes (`HomeViewModelTest`); `LzNamesTest`,
`OpenDiagramPreferencesTest`, `PanelSheetTest`; and the pages' pictures in dark and night (`LzPanelScreenshotTest`, beside AP-Saved,
AP-LzPlan and AP-SaveLz). **Not verifiable here:** placing by tapping the real map, the sheet lowering as a mode starts, and the toast over
the real map.

**S4 — Routes. Built.** The panel (`feature-workspace`: `RoutesPanel.kt` over `RoutesViewModel`; `Routes`): the set on the map as the design's
card (its name with rename and a menu of undo, redo, close and delete; its chips, *Imported* for a mission and the sync state; *Save* or a disabled
*Saved*; *Export .msnx* with *Include threats (n) as a .ths file*; its routes, each with share as GPX or FPL, hide, more and the way into its plan),
the other sets in the session *Also open*, *Every route set* (the Library's stand-in until S7, with conflicts settled there), and the foot pinned
under the panel: *Sketch a route* and *Import .msnx*, or *Cancel* and *Finish* while a route is drawn. Naming is `SaveRoutesSheet` (the design's
SaveRoutes). The route's plan (`RoutePlanPage.kt`, the design's RoutePlan): the bar (Back, the route with its totals, share, a menu of undo, redo,
rename, an AMPS file of it alone and delete), the set's strip with *Save*, *Whole route* (date with a calendar, temperature, fuel flow, *Fetch
winds*), a card for each named point (shut: what it is, its clock, its leg, what is flown to it; open: its name, its clock, the altitude, speed and
wind flown to it with their references, its kind, moving it), and the total with *Fetch elevations*. The working set is `OpenRouteSets`
(`core-data`), kept by `OpenRouteSetPreferences`. **Amended while building:**
- **The route's plan is a page over the frame, not a Navigation destination**, as the design's pushed page looks: a destination would dispose the map
  and the panels kept composed (their pickers, what they listen for). It closes with Back and belongs to the held route.
- **What is typed in the plan is applied as it is typed** (the design has no Apply button, and the web applies on change), one visit to a field one
  step to undo, a refusal at the field; only what differs from what the field showed is applied (`PlanDraft.check(before)`, new).
- **The route-wide airspeed, altitude and wind are not edited as such**, as on the web's redesign: each point carries what is flown to it.
- **The made-up name is `SKETCHED ROUTES`** (the web's), numbered when taken; *Sketch a route* makes a set with it unless a set of sketched routes is on
  the map, and a sketch that ends without a route takes it away again while it is still empty and unnamed. There is no *New set* form any more.
- **The speed reference has three parts (GS, IAS, TAS)** where the design draws two: the planner has true airspeed.
- **Not drawn as the design:** the other sets are *Also open* rows rather than full cards (one set is on the map at a time; its card has the
  export), and the plan's menus hold undo and redo (the design has no place for them).
**Reviewed** (one independent review, 2026-10-12: 33 reported, 25 survived an adversarial check, about 15 distinct; each fix has a test, each broken to see
its test fail): the plan page coming back by itself when its route was held after an undo; separate visits to a field merging into one undo step (and a
date's two choices); deleting a set whose record went elsewhere opening the copy its save made; a double tap on *Sketch a route* making two sets; a
cancelled sketch deleting a set the person had named, leaving an abandoned one behind, or putting back the wrong set; exports from the plan page lost when
the panel is not offered; the keyboard covering the field typed in; focus left on the hidden frame; the page not announced to a screen reader;
toasts over the foot's buttons; labels, the date and the crosshair's grid cut off on a 360 dp phone or with large text; the fields' descriptions hiding
their values from TalkBack; Done on an unchanged name moving the point onto a local point; and a rename, delete or refusal from the bar shown out of sight.
Tests: `RoutesViewModelTest` (the session, sketching, naming, the plan page, the review's cases), `RoutesSessionOrderTest` (a record gone while open, Main
unconfined), `RoutesScreenTest`, `RoutePlanPageTest`, `RoutePlanNarrowTest` (360 dp), `RouteSetNamesTest`, `PlanDraftTest`, `HomeViewModelTest` (only a set
still open comes back), `OpenRouteSetPreferencesTest`, `PhoneFrameTest` (toasts above the foot), and the pictures (`RoutesScreenshotTest`, beside AP-Routes,
AP-RoutePlan and AP-SaveRoutes). **Not verifiable here:** the keyboard on a device, TalkBack, the date picker, the share sheet, and the toasts over the real map.

**S5 — Threats. Built.** The panel (`feature-workspace`: `ThreatsScreen.kt` over `ThreatsViewModel`; `Threats`): where threats are kept, said first;
*Add threat* at the crosshair (the row names its grid) and *Import .ths*; the threats on the map, a row each (the MIL-STD symbol in the design's tinted
circle, the name, `Detect 25 nm · Engage 15 nm`, the terrain mask as a chip, then hide, edit and remove); *.ths for AMPS* with what leaves the device; and
*Remove all threats*. Removing one or all is asked about in the design's dialog (`Confirm`, D). The form is a full-screen dialog (`ThreatFormDialog`):
what the threat is, where it is (folded behind *Move*, moved in the form until Save), each radar, its terrain mask, and removing it; Close and Back ask
before discarding. The held bar's options for a threat open it. **And the owner's decisions of 2026-10-12** that need no server: the window is no longer
kept out of screenshots (`ThreatWindowGuard` went) and the picture no longer expires after 48 hours (it is still wiped at sign-out until *T* saves it with
the account). **Amended while building:**
- **The held threat's card went**: its details and its controls are the form's, which the held bar opens; the terrain mask is asked for there.
- **The design's notice says what is true now**: kept on the device, wiped at sign-out, not saved to the account or shared (no 48 hours).
- **Not offered, because Android does not have it yet:** *Send KMZ to ATAK*; the design's *long-press the map and choose Add threat here* waits for S8.
- **The mask chip has its own line under the name and reach**, with the row's width to itself: beside the three buttons it lost its words on a 360 dp
  phone with large text.
**Reviewed** (one independent review, 2026-10-12: 20 reported, 15 survived an adversarial check, about 8 distinct; each fix has a test, each broken to see
its test fail): the held bar's options opening a hidden threat's form, or the wrong panel, because they went by another order than the bar's; a hidden threat
held from its row or its form; a form left open over a sign-out coming back over the next account's map; the form's outcome said in a panel out of sight; a
radar set to rings only called *Mask failed*; the mask chips cut on a narrow phone; and stale words about the 48 hours and the screenshot guard in the
code and the docs. Tests: `ThreatsViewModelTest` (the form's position, the held bar's way in, removing from the form, the mask in rows and the form, the
review's cases), `ThreatsScreenTest`, `ThreatsNarrowTest` (360 dp, large text), `ThreatStoreTest` (no expiry), `AppViewModelTest` (a picture kept however
long it was left), `HomeViewModelTest` (the held bar's options in its own order), and the pictures (`ThreatsScreenshotTest`, beside AP-Threats).
**Not verifiable here:** the full-screen dialog over the real map, the keyboard in it on a device, and TalkBack.

**T — Threats saved and shared** (the owner, 2026-10-12). Threats are saved like local points and route sets, as a Library item of their own that
can be opened and moved as wanted, and shared in mission packs, where every member can edit them. The web is the reference, so it goes web and server
first: a saved threat set (`/api/threatsets`, a sync kind, `contracts/openapi.yaml` and fixtures), a pack item kind (`pack_ops.py`, `packOps.js`,
`contracts/fixtures/packs`), the web's Threats panel saving to and opening from the Library and its pack rows; then Android: `ThreatStore` onto the
sync engine (the sealed device file becomes the open picture's working copy), `RecordKind.THREATS`, the pack item kind in `core-missionpacks`, and the
Library's tab (S7). To decide when it is planned: whether a saved set is the whole picture or a named group of threats (the web's `.ths` is a whole
picture); whether several can be open at once, as route sets are; and what the viewshed, KMZ and QR stores keep once threats are records. AGENTS.md §2
records the decision.

**S6 — Import. Built.** The Import menu (`ImportSheet.kt`, a modal bottom sheet from the frame's Import chip and the Imports panel's header: a mission, local points, threats for an
account with them; each opens the system picker), the import review (`ImportReview.kt` over `IncomingViewModel`: every file waiting, from the picker or another app, with what it is,
how much is in it and where it goes, the choice locked where a kind has one place; *Remove* on each, *Cancel* and *Import N files*), and the Imports panel (`ImportsPanel.kt` over
`PointsViewModel`: the sets of points with *Library* or *This session*, hide, and *More*; the missions kept with their files and *Open in Routes*; the threat files and *Open in
Threats*; the held point's card). The Threats panel's *Import .ths* and Routes' *Import .msnx* open the picker straight into the review. **Amended while building:**
- **Where each goes:** local points to the Library or *This session* (held in memory for the session, never saved; `SessionPointSets`); threats to this device only (until *T*); a mission to
  the Library with its file (*This session* would need a session route set, which does not exist). No overlays (owner decision 2), no pack destination (the P-steps).
- **Every file can be taken out of the review**, not only one that cannot be brought in, so a person can bring in two of three.
- **The panel lists every mission in the Library** (the Library page, S7, will too): an imported mission is its file, kept in the Library, and that is what the design's row says.
- **The design's notes on threats say what is true now** (kept on this device until sign-out, not saved or shared yet), not the 48 hours.
**Reviewed** (one independent review, 2026-10-12: 14 reported, 9 survived an adversarial check, 7 distinct; each fix has a test, each broken to see it fail): *Save to Library* losing a
set's colour and hidden state because the Library lists a save a moment late; a session's set read, or a mission brought in, across a sign-out reaching the next person; the review's name
coming back from saved state for another file after the process was ended; a cancelled review opening again for files of the same pick still being read; place tags and the
segmented choice cut at the largest text; outcome toasts timing out under the review; and a rename left focused under *Open in Routes* / *Open in Threats*. Tests: `IncomingViewModelTest`,
`PointsViewModelTest`, `SessionImportsTest`, `ImportReviewTest`, `ImportsPanelTest`, `ImportsNarrowTest` (360 dp at 1.3 and 2.0), `AppViewModelTest`, and the pictures
(`ImportsScreenshotTest`, beside AP-Import, AP-ImportReview and AP-Imports).
**Not verifiable here:** the picker and a slow provider, the sheet over the real map, the keyboard in the review, TalkBack.

**S7 — The Library. Built.** A page over the frame (`LibraryPage.kt` over `LibraryViewModel`; `Library`): the LZ/PZ, Routes and Local points tabs with counts,
search by name (an LZ/PZ by its grid too), *Recently updated* or *Name A to Z*, each item with what it is, when it changed and whether it is open, *Open* into the session, and
Rename, Duplicate, Delete; conflict copies with their three choices. From the Library chip, *Browse all* and the panels' conflict *Review* (the panels' every-item pages went).
And the confirmations (`Confirm`): close with unsaved changes does not arise under autosave; delete and remove-all keep their asks; a name taken offers *Keep both*, the
default, or *Replace*, in the Library's rename and the Save sheets (`NameTakenChoice`). **Amended while building:**
- **When an item last changed is noted on the record** (database version 4), since only an LZ/PZ's document carried a time: the server's for what a pull brings, this device's for a
  change made here; none is ever made up. The server's times have no offset and are read as UTC (the web reads them as local time: AGENTS.md §15).
- ***Open* shuts the Library and shows the item's panel**, as the web's does (the design's prototype leaves it open).
- ***Replace* deletes the other item and renames this one** (the web writes this version over the other's record).
- **Not offered yet:** *Add to Mission Pack…* (P4) and *Working with a team? Open a Mission Pack* (P1).
**Reviewed** (one independent review, 2026-10-12: 14 reported, 10 survived an adversarial check, about 8 distinct; each fix has a test, each broken to see it fail): an LZ/PZ with
no time of its own shown as changed "now"; what a pack refused kept with no time, and *Keep mine* keeping the other device's; names over the server's 100 characters from *Keep both*,
*Duplicate* and the engine's own conflict copies, which the server refuses with a 500 that holds up every upload behind it; the Library's rename, delete and conflict choices failing out of
sight (said in a panel banner under the page, a mission's rename refused as made up, an action while the panel was busy dropped); an old search hiding the conflict *Review* opened for;
a too-long name that could not be shortened; and a tab's count and a grid cut at 360 dp. Tests: `LibraryViewModelTest`, `LibraryPageTest`, `LibraryScreenshotTest` (360 dp at 1.3),
`RecordTimeTest`, `RoomMigrationTest` (3 to 4), `LibraryPackKeeperTest`, `LzNamesTest`, `DiagramsViewModelTest`, `RoutesViewModelTest`, `PointsViewModelTest`, `LzPanelScreenTest`,
`RoutesScreenTest`, `LibraryTabsTest`.
**Not verifiable here:** the rename dialog's window with the keyboard on a device, and TalkBack.

**S8 — The map's long press and the toasts. Built.** A long press on nothing opens the design's menu at the press, headed by the grid under the
finger (`MapMenu`: New LZ/PZ here, Add threat here, Add route point here, Copy grid, each for an account offered its panel); a long press on something still
picks it up. The *Saved* toast (`Saved`) offers *Open Library*; the others go through `ToastHost` as before. **Amended while building:**
- **The menu is a popup of its own**, above and to the right of the press with the design's ring at it, not Material's `DropdownMenu`, which opens under the
  fingertip (README.md's table says "DropdownMenu at the press point"; the drawn screen puts it beside).
- ***Add route point here*** adds a turn point to the end of the route being worked on (held, or the set's only one), and with no route starts one there; it is
  greyed out, with the reason, in an imported mission and with several routes and none held. The design does not say which route it means.
- **No menu while a boundary, a route or a tool takes the map's taps**, as a press picks nothing up then.
**Reviewed** (one independent review, 2026-10-12: 19 reported, 10 survived an adversarial check, 8 distinct; each fix has a test, each broken to see it fail): a slow
quick zoom (double tap, then drag) opened the menu part way through and cut the zoom off (the platform detector keeps a double tap's long press however far
the finger moves); the menu opened under the finger with nothing marking the place; a right-to-left language put it at the wrong side of the screen; the grid
split over two lines at large text; *Open Library* pressed on a route's plan page left the plan over the panel the Library then chose; the tabs it was offered
for were read once and went stale when the account's features changed; a press just past the date line opened nothing; and no test drew the real menu.
Tests: `LongPressTouchesTest`, `MapMenuTest` (the placement rule, the real popup at 1.0, 1.3 and 2.0, the toast), `HomeViewModelTest`, `RoutesViewModelTest`,
`LibraryViewModelTest`, `PointsViewModelTest`.
**Not verifiable here:** the long press and quick zoom on a real screen, the haptic, the popup over the real map, TalkBack.

**S9 — The tablet. Built.** The rail (workspace button, Import, the destinations with badges, Library and account at the bottom) beside the chosen
panel in the list pane (344 dp), the map, and the supporting panes (320 dp: the open LZ/PZ's plan, or a route's). Library and import review as large dialogs.
The medium width (600–840 dp) was not drawn: it takes the phone's frame. **Amended while building:**
- **No material3-adaptive.** The panes are fixed widths beside a map that never hides: a row of them, with `TabletFrameState` deciding which stand, is all the
  scaffolds would have given, and every rule of it is tried in a test. Nothing added to the build.
- **The tablet's frame needs 480 dp of height too** (Material's medium height): a phone on its side is 840 dp wide and keeps the phone's frame.
- **The route's plan in the pane is the phone's page** (its point cards, with a close button); the design's nav-log table is not built. The LZ/PZ's plan pane stays beside
  whichever panel shows, as the design keeps it beside Imports.
- **Import on the rail is a menu at its tile** (the design's); a panel's own Import still opens the phone's sheet. **The Save sheets stay bottom sheets** on a tablet (the
  design's are dialogs).
- **The map, the panels and the route's plan move between the frames** when the window crosses the line (movable content), and the phone's sheet is drawn by its frame.
**Reviewed** (one independent review, 2026-10-12: 34 reported, 30 survived an adversarial check, about 20 distinct; each fix has a test where one can hold it, and each
tested one was broken to see it fail): crossing 840 dp rebuilt the map and crashed on a duplicate MapLibre source, flew the map back to the launch's camera, asked for the
location permission again and lost what was typed in the panels; a phone on its side got the tablet's frame, whose rail lost the Library and the account; a pane waiting
behind the list could not be brought back, a rail pick of LZ/PZ showed its plan instead of the list, Back put away a pane nobody could see, and collapsing hid the result of
the held bar's options, the Library's *Open* and *Manage*; the LZ/PZ's plan pane was thrown away whenever another panel or a route's plan showed; the Import tile was two
screen-reader stops, the Library a fifth tab, the large dialog's scrim an unnamed full-screen node (and the Library's focus walked into the frame behind it); the route's plan
cut its total and the wind at 320 dp and kept the phone's back arrow; opening the Library closed the route's plan pane; the map's buttons and the panes' headers were not where
the design draws them; and headers cut large text. Tests: `TabletFrameTest` (the pane rule, the frame rule, the state's rules, the rail, Back, a short window, the frames moving
their content), `TabletPartsTest`, `RoutePlanPageTest`, `MapScreenTest`.
**Not verifiable here:** the real map in its pane, a foldable's hinge, a keyboard cover, TalkBack on a tablet, and a device crossing 840 dp.

**P1 — The workspace switcher. Built.** The workspace chip opens `Switcher`: pending invitations (Accept, Decline), the Library ("Personal"),
the packs with their state, the teams line. Switching parks the Library's open session and restores it on the way back
(*Deferred* 3), remembers the open pack (`LastPack`), and turns the chip violet with the pack's name, who is here now and *Live*.
Opening the pack an invitation joined (*Deferred* 9) lands here. **Amended while building:**
- **A new module, `feature-packs`**, for the pack screens (P1–P4), over seams the app fills (`PackDoor`, `PacksApi`, `Parking`, `LastPack`, `LibraryTally`), so the
  switcher's rules are tried without the engine or a server.
- ***New pack* comes with P3's page**; the switcher has no button for it until then.
- **On a tablet the switcher is a menu at the rail's workspace tile**, the same content as the phone's sheet. The *Live* line sits under the chips (on a tablet, under the
  search bar).
- **Switches are queued, the latest winning** over one waiting, rather than refused while one runs: an invitation link's join, a pack that went and the person's own choice
  can all ask at once.
**Reviewed** (one independent review, 2026-10-12: 26 reported, 23 survived an adversarial check, about 18 distinct; each fix has a test where one can hold it, and each tested
one was broken to see it fail): the switcher showed the previous account's packs, invitations and teams to the next one, and an old load could undo a newer one (an accepted
invitation came back); while a pack was open it could mark the Library current and say *No packs yet*; the remembered pack waited for the lists, so every launch into a pack
showed the person's own LZ/PZ, flew to it and closed it (or, the other way round, left it open inside the pack), and leaving a gone pack waited for them too; parking closed the
person's LZ/PZ even when its last change could not be written, losing it; a switch asked for while another ran (an invitation link's join) was dropped without a word, and a
link's join opened the pack again whenever the screen was composed again; on a tablet the switcher crashed as it opened (a scroll inside the menu's own); at 360 dp the chips
pushed the Library off the screen and the status line ran under the map's buttons; an invitation's answer that failed was said in a toast under the phone's sheet; dates were
UTC's and never named the year; initials could cut an emoji in half and differed from the account button's; the bold could land on an inviter named as the pack; the switcher
opened by itself when packs or the phone's frame came back; and the rail told a screen reader *Change workspace* on a tile that could not. Tests: `WorkspaceViewModelTest`,
`SwitcherTest` (the words, the content, inside a menu, screenshots), `WorkspacesTest` (parking with real sessions, the remembered pack), `WorkspaceChipsTest` (360 dp at 1.3 and
2.0), `HomeViewModelTest`, `TabletFrameTest`.
**Not verifiable here:** the sheet and the menu on a device, TalkBack, and the faces' colours in sunlight.

**P2 — Working in a pack.** The Pack panel (`PackItems`, `PackHistory`: the description, members and *Invite*, Items and History with
"new since you looked", *Add from Library*, *Finish pack*), the panels' pack rows (`PackLive`: *Synced*, who is editing, "From Library ·
Original changed"), the finished banner (`Finished`), read-only gating of the map and the sheet (*Deferred* 8), and the presence pointer
(*Deferred* 7). **Split in three while building**, each reviewed on its own: P2a the Pack panel, P2b the LZ/PZ, Routes and Imports panels in a pack, P2c the finished banner,
read-only and presence.

**P2a — The Pack panel. Built.** A Pack tab first while a pack is open; the pack's head, description and who is in it and here now; Items by kind with what changed since this person
looked, who changed it and when, where it came from and who else has it open; a tap shows an item in its panel; *Close here*, *Rename* and *Remove from pack* in each row's menu; History,
newest first, with "New since you looked", the day headings and the two filters; the seen marker; the pack's notices in toasts. **Amended while building:**
- ***Invite* and the members row as a button wait for P3**; *Add from Library* and *Finish pack* for P4 and P2c (no disabled buttons in the meantime).
- **A point set cannot be shown yet** (*Deferred* 4): its row says so.
- **Who else has an item open says "has it open"**, not the design's "editing" (nothing says someone is changing it), and **this person counts as here** (the design's "2 here now").
- **An item's time is its date when it is another day**, not the web's clock time for up to a day.
**Reviewed** (one independent review, 2026-10-12: 38 reported, 31 survived an adversarial check, about 23 distinct; each fix has a test where one can hold it, and each tested one was
broken to see it fail): opening an item could end the app when what was open could not be saved; a pack reopened from the device's copy started "new" from an old marker; a failed
history page was asked again for every presence frame, and the whole history was rebuilt for each with a parse that threw; the panel counted as looked at while a tablet's list waited out of
sight or a plan page covered it, and stayed so after it went; a pack that went said each open item was removed; picking the open pack did nothing and a raise cut short left stale bookkeeping;
a removal the pack would refuse closed the item first; busy taps were dropped without a word; rows were not keyed, so a menu could move to another item; the members count went stale without a
team, and the dots and the count disagreed; the history read each person's initials, its filters used other words and no role, a failure was hidden once events showed, a filter whose person
had gone kept filtering, and only the first page jumped to the newest; the rename field was wiped when someone else renamed the item; the device's copy never said it could not reach the pack;
the rail's Pack tab had the workspace's glyph and no name; the frame tests drew a Pack tab where no pack was open. Tests: `PackPanelViewModelTest`, `PackPanelScreenTest`, `PackWorkspaceTest`,
`PanelsTest`, `PhoneFrameTest`, `TabletFrameTest`.
**Not verifiable here:** the sheet over the real map, TalkBack, and presence at its real rate on a device.

**P2b — The LZ/PZ and Routes panels in a pack. Built.** While a pack is open the two panels are the pack's (the design's `PackLive`): its LZ/PZs and route sets under "In OP DK · n",
each with how far along it is beside its grid, what holds of it in the pack (*Read-only*, *Original changed*, *From Library*, *Waiting to send*) and who else has it open; the open one's
card and plan with no *Save*, where it is saved and who else is here; *Close here* (it stays in the pack) and no *Delete* (*Remove from pack* is the Pack panel's); a new LZ/PZ, and a
sketch, go into the pack. The panels read the open pack through `OpenPack` (core-data, the app's `PackWorkspace`), as a `PackGlance` that a pointer moving does not change.
**Amended while building and by the review:**
- **The Imports panel is not changed** (a pack's point sets wait, *Deferred* 4). A mission brought in while a pack is open is kept in the Library and not opened, and the Routes
  foot offers no *Import .msnx* in a pack, as on the web.
- **The person's own are not opened in a pack** (the Library's *Open*, *Open in Routes*: "switch to Personal"), until P4 adds a copy to the pack.
- **A sketch in a pack draws into the pack's set last on the map, else its first** (the web's `packRouteSetFor`). Only a pack with no set gets one, made as the sketch starts (the web
  makes it when the route is finished; here a route is drawn into an open set) and taken out again if the sketch ends with no route, while it is still empty and still has the name it
  was made with. The history keeps that it was added and taken out.
- **"Also here" is the web's**: "Sam B. and 2 more are also here". A pack's item takes any name from its card, its own "LZ/PZ n" included (no Library naming rule there).
- **A pack's rows leave *Synced* unsaid** (what most would say), as the web's do; the open one's card says it.
**Reviewed** (one independent review, 2026-10-12: 22 reported, all 22 survived an adversarial check, about 16 distinct; each fix has a test where one can hold it, and each tested one was
broken to see it fail): a sketch made an empty set in the pack at once and kept it whatever became of the sketch, and made another for every sketch with none open; the plan page and the
tablet's pane offered *Save* and "Saves to your Library" for a pack's LZ/PZ; closing or deleting one of the person's own left in a pack brought the next of theirs into it; a pack opening
or out of reach showed as empty and "live", inviting the person to add to it, and a paused one said "live"; "In OP DK · n" counted whatever card was on the map and sat under it; the pack's
rows said only *Synced*, never whether an LZ/PZ was analysed; an open pack item missing from the state for a moment became a Library record with *Save*; the Library's conflicts, *Every
route set* and *Import .msnx* stayed in the panels in a pack; the diagram cache kept every LZ/PZ ever seen; "also here" named everyone; renaming a pack's LZ/PZ refused "LZ/PZ n" in the
Library's words; the view models were never tried with a pack's item open; a row's grid was cut mid-digit at large text and the Routes rows' names clipped; every presence frame rebuilt
both panels; a set the pack refused was said twice. Tests: `PackFactsTest`, `DiagramsViewModelTest`, `RoutesViewModelTest`, `PanelsInPackTest` (with screenshots at 360 dp, 1.3 and 2.0),
`MissionImporterTest`, `IncomingViewModelTest`, `LzPanelScreenTest`. The full build also caught `RoutePackEditorTest` failing now and then, since A12: its rig gave
`RouteRepository` no dispatcher, so a mission's file work ran on a real thread under the test's virtual clock; it runs on the test's dispatcher now.
**Not verifiable here:** the panels over the real map in a pack, TalkBack, and presence at its real rate on a device.

**P3 — People.** *New pack* (`NewPack`), *Members* (`Members`: roles, invite by name or email, pending invites with resend and revoke,
share with a team), and the teams page.

**P4 — The pack dialogs.** `PackDialogs`: add a copy to a pack (from the Library's menu and the panels), update from the original, finish,
save a copy to the Library; the keep-or-discard offer for edits a pack dropped (*Deferred* 2).

## How each step is checked

- The logic (what a panel shows, what a button does) in view-model and Compose tests, as the app's are today; the shell's orderings with
  `UnconfinedTestDispatcher` as Main where two collectors meet (AGENTS.md §14).
- Each new screen's picture (Roborazzi, `-Pezpz.screenshots`) beside the design's (the `.dc.html` in the browser pane), looked at for
  layout, sizes and words. The map itself is a stand-in in both.
- The full build, then an independent review of the step, then its fixes, as for the engine.
- **Not verifiable here:** the real map under the frame, the system bars and gestures on a device, a tablet's real panes, and how the
  colours read in sunlight and at night. A device run after S2 and after S9.
