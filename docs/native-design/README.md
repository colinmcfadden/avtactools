# Native screens (Android and iOS): design handoff

The native-app version of the web menu redesign (`docs/MENU_REDESIGN.md`): the same functions as its 20 web screens, laid out with each platform's own components. 82 screens: Android phone and tablet, iPhone and iPad. Dark appearance throughout.

Exported from the Design canvas "EZ/PZ Native Screens" (<https://claude.ai/artifact/Dh8HVPHygVeK4VuaxY2vRa>, private to Colin), version `1791467994-f1f1`, on 2026-10-08. The canvas is the editable original; this folder is a snapshot for implementation.

**Only the text is in git**: this README, `outlines/` and `tokens.json`. The screenshots (`png/`) and their sources (`screens/`) are gitignored (about 29 MB). A fresh checkout has neither: ask the owner for the export, or export the canvas again, and drop the two folders in here.

## How to use this folder (for an agent implementing the UI)

For a screen, read in this order:

1. **`outlines/<id>.md`**: the screen's text, controls and accessibility labels in reading order. Cheap to read; start here.
2. **`png/<id>.png`**: what it looks like, at 1x (dp or pt = px). Read it as an image.
3. **`screens/<id>.dc.html`**: the exact source. Inline-styled HTML, so every size, colour, radius and string is literal. Use it for values, not as code to port.
4. **`tokens.json`**: the colour roles, type scale and component sizes both platforms were drawn with.

The mockups are pictures of the UI, not an implementation. Build them with the platform components named below (Jetpack Compose with Material 3; SwiftUI), not by reproducing the HTML. Where a mockup approximates something the platform does natively (Liquid Glass, sheet detents, the map), use the real thing.

Ids are `<form>-<screen>`: `AP` Android phone (412 x 915 dp), `AT` Android tablet (1280 x 800 dp, landscape), `IP` iPhone (393 x 852 pt), `IT` iPad (1210 x 834 pt, landscape). `Confirm` and `PackDialogs` on phones are specimen boards: four phone screens side by side, one dialog each.

## Decisions to settle before building

- **Save model (Android).** The mockups follow the web's save pattern: an LZ/PZ shows "Unsaved changes" until its first Save names it into the Library, later saves are silent with a toast. The Android app today saves every diagram as a synced record automatically (`DocumentSession`, 600 ms after the last edit). Decide whether native keeps autosave (then "Save" becomes "name it and keep it in the Library", and "Unsaved changes" means "not in the Library yet") or adopts session drafts like the web. The screens work either way, but the chip wording depends on it.
- **Palette.** The screens use the redesign's neutral avionics palette (`tokens.json`), not the navy in `contracts/tokens/tokens.json` that `core-designsystem` is generated from. The redesign retires the navy on the web; adopting these screens means changing the contract tokens (then `contracts/scripts/tokens.py write`). The red-shifted night palette was not redesigned.
- **Threat count.** Threats screens list two threats (badge 2); every other screen shows 1. Inherited from the web mockups; harmless, but pick one if screenshots are compared.
- **Mission Packs are not in the Android app yet** (packs exist on the web and backend; the Android plan is `docs/MISSION_PACKS.md` §8 step 4). The pack screens are designs for that work. Not to be confused with `core-mappacks` in the native plan, which is the offline *map* pack.
- **Map overlays** (KMZ, GeoTIFF, image plus world file) appear in Import and Imports as in the web design; parsing them needs a new library (AGENTS.md: ask first).

## Platform architecture

### Android phone (compact window, < 600 dp)

Map at the root, full bleed (MapLibre, `feature-map`). Over it:

| Element | Compose |
|---|---|
| MGRS target field with account avatar | M3 `SearchBar` (collapsed), 56 dp, top 44 |
| Workspace, Import, Library | Row of `AssistChip`s under the search bar; the workspace chip opens the switcher. In a pack it turns violet "OP DK" with presence avatars and Live |
| Map layers, My location | `SmallFloatingActionButton`s at the right |
| Crosshair and grid readout pill | Fixed overlay at the centre of the visible map; pill above the sheet, tap to copy |
| The panel | `BottomSheetScaffold` standard sheet with a partial-expanded peek, half and full |
| LZ/PZ, Routes, Threats, Imports (Pack first in a pack) | `NavigationBar` with `BadgedBox` counts |
| Full pages (route plan, Library, members, new pack) | Navigation Compose destinations with a `TopAppBar`, or full-screen dialogs for forms |
| Dialogs, menus, toasts | `AlertDialog`, `ModalBottomSheet`, `DropdownMenu`, `Snackbar` |

### Android tablet (expanded window, >= 840 dp)

`NavigationRail` (96 dp: workspace button, Import FAB, destinations with badges, Library and account at the bottom) beside a material3-adaptive `ListDetailPaneScaffold` / `SupportingPaneScaffold`: list pane (344 dp, the selected panel), the map, and a supporting pane (320 dp: the open LZ/PZ's plan, or the route nav log). Dialogs centred, up to about 880 dp wide for Library and Import review. The medium class (600 to 840 dp, "sheet beside the map" in NATIVE_APPS_PLAN.md) was not drawn.

### iPhone

Map full bleed under the top safe area. Floating Liquid Glass controls: workspace capsule (top left), an Import and Library capsule plus account circle (top right), a Map layers and My location capsule (right edge), the crosshair and a glass readout pill. The panel is a sheet with detents (`.presentationDetents`, `.presentationBackgroundInteraction(.enabled(upThrough: .medium))`, `.interactiveDismissDisabled()`), floating inset at partial heights. In the LZ/PZ tab the sheet starts with the MGRS search field, as in Maps.

The mockups float a glass tab bar (LZ/PZ, Routes, Threats, Imports; Pack first in a pack) over the bottom of the sheet. A system `TabView` cannot sit above a presented sheet, so build it as a glass control in the sheet's bottom safe-area inset (`GlassEffectContainer` plus buttons), or host the sheet content in the tab view and keep the map behind it.

Full pages (route plan, Library, members) are large-detent sheets or pushed views with a Large Title. Forms are sheets with a Cancel / Save nav row. Confirmations are `.alert` and `.confirmationDialog` (the name-taken case uses the Files pattern: Keep Both, Replace, Cancel).

### iPad

Map full bleed. A floating glass sidebar (340 pt; iPadOS 26 `NavigationSplitView` sidebar) holding the workspace pull-down, a segmented tab picker (LZ/PZ, Routes, Threats, Imports; Pack first in a pack) and the selected panel. A trailing `.inspector` (320 pt) for the open LZ/PZ's plan or the route nav log; closed when a screen does not need it. Toolbar over the map: the MGRS search field, Import and Library (icon buttons, `⌘I`, `⌘L`), account. Menus and the workspace switcher are popovers; forms are form sheets (`.presentationSizing(.form)`); files can be dropped onto the map.

## Behaviour carried over from the web design

- **Save pattern:** first save asks for a name in a small dialog that says what is and is not saved; later saves are silent with a toast ("Saved “NAME” to your Library", Open Library); a clean document shows a disabled "Saved"; an un-analyzed LZ/PZ cannot be saved ("Analyze to save"). Name taken: Keep both (saves "NAME 2", the default) or Replace.
- **Workspace always visible:** Library (personal) or a Mission Pack. In a pack there is no Save button: items are "Synced", the top shows "Live · all changes synced", new items go into the pack.
- **Threats:** never saved to the account or shared with a pack. Native wording: "Threats stay on this device only, in an encrypted file that is wiped 48 hours after your last change and when you sign out." The `.ths` is built on the device; "Showing a terrain mask or building a KMZ sends threat positions to the server once. Nothing is kept." (matches `ThreatStore` and AGENTS.md §2).
- **Imports:** a file's type comes from its content; the review says where each goes. Local points: session, Library or the open pack. Mission files: session or Library, never a pack. Threats: this device only. Overlays: this session only.
- **Packs:** owner, editors and viewers; presence reads "here now"; a pack copy of a Library item shows "From Library" and "Original changed · Update"; History marks "New since you looked" and explains skipped edits; a finished pack is read-only for everyone (Reopen, Save a copy to Library, Duplicate as new pack).
- **Destructive choices** are never the prominent button and always name what they affect.

## Native adaptations (differences from the web, on purpose)

- Right-click on the map becomes a long-press: a menu headed by the grid under the finger (New LZ/PZ here, Add threat here, Add route point here, Copy grid).
- The cursor readout becomes a fixed crosshair with a readout pill (grid under the crosshair, tap to copy).
- Dropping files on the map: phones use Import and "open the file in another app and choose EZ/PZ"; tablets also accept drag and drop.
- Ctrl S: none on phones; iPad menus show ⌘S.
- Hand-off: iOS opens a route in ForeFlight and shares threats as KMZ (ForeFlight, ATAK, Aero). Android has no ForeFlight: routes go out as GPX or FPL through the share sheet (ATAK, Garmin Pilot), threats to ATAK as KMZ.
- Map tools (Draw LZ, Helo, PZ, Sector, Unit, L-GA, R-GA) are explicit modes that place at the crosshair or a tap, with Done and Cancel.
- Glove sizing: controls at least 48 dp / 44 pt, primary actions about 56.

## Screens

Web numbers are the 20 screens of the web redesign (`docs/MENU_REDESIGN.md` §11). Every screen exists for all four forms unless marked "phone only" (tablets show it as part of another screen).

| Screen | Web | What it shows | Android | iOS |
|---|---|---|---|---|
| `Home` | 01 | LZ/PZ panel: open LZ/PZs with state chips, Recent in Library. Tablets add the plan (aircraft, analysis, tools, export) in the supporting pane / inspector | Standard sheet; tablet panes | Sheet with search field; iPad sidebar + inspector |
| `LzPlan` (phone only) | 01 | The open LZ/PZ's plan: aircraft, analysis switches, tool grid, export, sketch a route | Fully expanded sheet | Large-detent sheet |
| `MapMenu` (phone only) | 01 | Long-press menu headed by the grid | `DropdownMenu` at the press point | Glass menu with a dropped pin |
| `Routes` | 02 | Route sets with state, Save, Export .msnx (+ threats as .ths), route rows with hand-off; tablets add the nav log | Sheet; tablet supporting pane | Sheet; iPad inspector |
| `RoutePlan` (phone only) | 02 | Nav log editor: route-wide values, one card per point, legs, totals | Pushed page, cards | Pushed page, inset-grouped sections |
| `Threats` | 03 | Session-only notice, add/import, threat list, export, Remove all; tablets also show the long-press menu | Sheet | Sheet; iPad sidebar |
| `SaveLz` | 04 | First save of an LZ/PZ | Modal bottom sheet; tablet dialog | Form sheet |
| `SaveRoutes` | 05 | First save of a route set | Modal bottom sheet; tablet dialog | Form sheet |
| `Saved` | 19 | After saving: Saved chip and toast | `Snackbar` | Glass toast |
| `SaveFlow` | 20 | Clickable prototype of the save flow (state in the source's script) | | |
| `Library` | 06 | Tabs LZ/PZ, Routes, Local points; search, sort, Open, more | Full-screen page; tablet large dialog | Large sheet; iPad page sheet |
| `Confirm` | 18 | Name taken, close with unsaved changes, delete, remove all threats | `AlertDialog`s (radio choice for name taken) | `.alert` / `.confirmationDialog` |
| `Import` (phone only) | 07 | Import menu: .msnx, .LPS, .ths, overlay | Modal bottom sheet | Pull-down menu |
| `Imports` | 09 (+07 on tablets) | Imports panel: grouped files, visibility, location chips, overlay opacity | Sheet; tablet list pane + rail FAB menu | Sheet; iPad sidebar + popover |
| `ImportReview` | 08 | Import review inside a pack: destination per file, locked choices, unsupported file | Full-screen dialog; tablet dialog | Large sheet; iPad form sheet |
| `Switcher` | 11 | Workspace switcher: invitation, Library, packs, teams | Modal bottom sheet; tablet popup | Sheet; iPad popover |
| `NewPack` | 12 | New Mission Pack form | Full-screen dialog; tablet dialog | Form sheet |
| `PackLive` | 10 (+13 on tablets) | Working in a pack: Synced items, presence on the map | Sheet; tablet panes | Sheet; iPad sidebar |
| `PackItems` (phone only) | 13 | Pack panel, Items | Sheet | Sheet |
| `PackHistory` | 14 | Pack panel, History | Sheet; tablet list pane | Sheet; iPad sidebar |
| `Members` | 15 | Members and invites | Pushed page; tablet dialog | Large sheet; iPad form sheet |
| `Finished` | 16 | Finished pack, read-only | Banner card in the panel | Banner in the panel |
| `PackDialogs` | 17 | Add a copy to a pack, update from original, finish, save a copy | `AlertDialog`s | Sheets (A, D) and alerts (B, C) |

## Where this lands in the Android code (as of AGENTS.md §17)

- The map, crosshair and readout exist in `feature-map` (`MapScreen`, `EzpzMap`, `MapProjection`; the readout pill and crosshair centring are already there). The overlays for LZ graphics, routes, points and threats exist.
- Today's shell (`app`, `MapHome`) is a bottom sheet with Diagrams, Aircraft, Routes, Local points and Threats sections. The redesign replaces that with the LZ/PZ, Routes, Threats, Imports (and Pack) navigation, the workspace chip, an Import entry and a Library screen.
- Existing pieces to reuse: `DiagramsViewModel` (open list, rename, delete, conflicts), `GraphicsViewModel` (tools, inspector), `AircraftViewModel` (aircraft picker), `RoutesViewModel` and `RoutePlanScreen.kt` (route sets, nav log), `ThreatsViewModel` (threats, `.ths` import and export), `PointsViewModel` and `IncomingHost` (imports), `ConflictPanel`.
- New: the Library screen over the synced records, the import review across file kinds, the Imports panel, the save dialog (see "Save model" above), and everything for Mission Packs.
- `core-designsystem` takes its colours from `contracts/tokens/tokens.json`; see "Palette" above.
- iOS is not started.

## Rendering the screens again

The sources need `screens/support.js` (the Design tool's runtime, from the user's own web design export) and network access for React (cdn.jsdelivr.net) and Google Fonts. `map.svg` is the web design's map image. To re-render one screen with headless Chrome, give the window 240 px of extra height and crop to the screen size (headless Chrome's viewport is shorter than its window):

```bash
chrome --headless=new --user-data-dir=<throwaway folder> --hide-scrollbars --force-device-scale-factor=1 --window-size=412,1155 --virtual-time-budget=15000 --screenshot=out.png file:///C:/_DEV/avtactools/docs/native-design/screens/AP-Home.dc.html
```

Opening a `.dc.html` file directly in a browser also shows it (the `SaveFlow` boards are clickable there).

## Contents

| Path | What |
|---|---|
| `README.md` | This handoff |
| `tokens.json` | Colour roles, type scale and component sizes for both platforms |
| `outlines/` | 82 text outlines, one per screen |
| `png/` | 82 screenshots, 1x (about 25 MB). Not in git |
| `screens/` | 82 `.dc.html` sources, `map.svg`, `support.js`. Not in git |
