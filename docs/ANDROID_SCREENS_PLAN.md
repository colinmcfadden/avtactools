# Android screens: the plan they are built from

Status (2026-10-10): **S1 built** (on `feat/android-screens`); S2 next. The redesigned shell first (S1–S9), then the mission-pack screens in it (P1–P4). Each step is one
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

**S2 — The frame (phone).** `MapHome` becomes the design's frame: the search bar with the account button, the row of chips (workspace,
Import, Library), the map buttons at the right, the crosshair and readout pill (tap to copy), the sheet, and the `NavigationBar` with its
badges (threats on the map, imports). Each panel at first hosts today's section (Diagrams, Routes, Threats, Points), so nothing a person
can do is lost between steps. The account menu takes today's footer. Tests: which panel is shown, the badges' counts, the account menu.

**S3 — LZ/PZ.** The session (the working set above), the panel (`Home`: the active LZ/PZ's card, *Also open*, the chips, Save as naming
with `SaveLz`), the plan page (`LzPlan`: aircraft, analysis and its switches, the tool grid, export, *Sketch a route*), the tools as modes
with Done and Cancel. Reuses `DiagramsViewModel`, `GraphicsViewModel`, `AircraftViewModel`, `BoundaryViewModel` and `ConflictPanel`.

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
