# Android screens: the plan they are built from

Status (2026-10-11): **S1, S2 and S3 built** (on `feat/android-screens`) and reviewed together, the review's findings fixed; to be tried on a device; S4 next. The redesigned shell first (S1–S9), then the mission-pack screens in it (P1–P4). Each step is one
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
  (a mission built while another panel shows still reaches the share sheet) and the threat panel's `FLAG_SECURE`.
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

**S4 — Routes.** The panel (`Routes`: sets in the session, Save as naming with `SaveRoutes`, *Export .msnx* with "Include threats as a
.ths", each route's hand-off, hide and plan), and the plan page (`RoutePlan`: today's `RoutePlanScreen` in the design's cards).

**S5 — Threats.** The panel (`Threats`: the device-only notice, *Add threat* and *Import .ths*, the list with hide, edit and remove, the
export, *Remove all*), over today's `ThreatsViewModel`; the form as a full-screen dialog.

**S6 — Import.** The Import menu (`Import`, a modal bottom sheet: `.msnx`, `.LPS`, `.ths`), the import review (`ImportReview`: each
file, where it goes, the choices locked where a kind has only one place), and the Imports panel (`Imports`: what was brought in, grouped
by kind, with visibility and *Open in Routes* / *Open in Threats*). One way in for a file however it arrives, as on the web; today's
`IncomingHost` dialog becomes the review.

**S7 — The Library.** A full page (`Library`: LZ/PZ, Routes and Local points tabs with counts, search by name or grid, sort, *Open* into
the session, and Rename, Duplicate, Delete; *Add to Mission Pack…* waits for P4), and the confirmations (`Confirm`: close with unsaved
changes does not arise under autosave; delete and remove-all keep their asks; name taken offers *Keep both*, the default, or *Replace*).

**S8 — The map's long press and the toasts.** A long press on nothing opens the design's menu at the press, headed by the grid under the
finger (`MapMenu`: New LZ/PZ here, Add threat here, Add route point here, Copy grid); a long press on something still picks it up. The
*Saved* toast (`Saved`) and the others through `ToastHost`.

**S9 — The tablet.** The `NavigationRail` (workspace button, Import, the destinations with badges, Library and account at the bottom)
beside material3-adaptive's scaffolds: the chosen panel in the list pane (344 dp), the map, and the supporting pane (320 dp: the open
LZ/PZ's plan, or a route's nav log). Library and import review as large dialogs. The medium width (600–840 dp) was not drawn: it takes the
phone's frame.

**P1 — The workspace switcher.** The workspace chip opens `Switcher`: pending invitations (Accept, Decline), the Library ("Personal"),
the packs with their state, *New pack*, the teams line. Switching parks the Library's open session and restores it on the way back
(*Deferred* 3), remembers the open pack (`LastPack`), and turns the frame violet with the pack's name, who is here now and *Live*.
Opening the pack an invitation joined (*Deferred* 9) lands here.

**P2 — Working in a pack.** The Pack panel (`PackItems`, `PackHistory`: the description, members and *Invite*, Items and History with
"new since you looked", *Add from Library*, *Finish pack*), the panels' pack rows (`PackLive`: *Synced*, who is editing, "From Library ·
Original changed"), the finished banner (`Finished`), read-only gating of the map and the sheet (*Deferred* 8), and the presence pointer
(*Deferred* 7).

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
