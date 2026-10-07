# Menu redesign (web)

**Status:** phases 1 to 5 are built on `feat/menu-redesign` (October 2026): the primitives, the save flows, the Library and top bar, the dock with its panels and the bottom sheet on narrow windows, and imports. Phase 6 (the Mission Packs screens) is in progress. Web only; the Android app can adopt the same flows later.

What was decided while building, beyond this document:

- **Narrow windows** (below 1100 px): the dock is a bottom sheet, as the Android app's is. The rail becomes a row of tabs on the sheet; the sheet rests at a peek, half or nearly full height (drag the handle, or tap it to step up); a phone starts at the peek. The phone's ☰ for the left panel moved into the top bar.
- **The map sits between the top bar and the dock** (`.shell-map`), so a target set by grid lands in the middle of what can be seen; `MapView` tells Leaflet when its box changes size.
- **Map overlays** are listed in the Import menu as not available yet: reading KMZ or GeoTIFF needs a new library (§9).
- **Imports default**: local points go to the Library (the open pack when there is one), mission files stay in the session; the person can change either in the review.
- The Library rows show no grid: the list API does not return one, and adding it changes the recorded responses the Android client is held to. Add `grid` to the LZ summary when the apps are next touched.
- The route plan editor's Winds button moved beside Get elevations: the mockup's row overflowed the 344 px panel.

**Design:** <https://claude.ai/artifact/4X3YxNxSYD4vggYkDf6m9A> (20 screens, private to Colin). An agent signed in as him can read a screen with the Artifact tool: `action: "read"`, `url` as above, `path: "project/<Screen>.dc.html"` (screen names are in the table at the end). The screens are inline-styled mockups, so treat them as a visual reference and rebuild them from the CSS variables in `frontend/src/App.css`, not by copying markup.

Related: the Mission Packs architecture proposal (Oct 5, 2026, by Colin; not in the repo yet). The pack screens in this redesign assume its recommendations.

---

## 1. What is wrong today

- **Save and open share one hidden place.** The floppy icon at the top right opens `HistoryModal`, which holds the LZ/PZ save form, the saved-routes list and the local-points panel. Saving an LZ/PZ from its own window sends you there to type a name and press Save again (`handleLayerSave` opens the modal when there is no `savedId`).
- **Routes save through browser dialogs.** `handleSaveSketches` and `handleSaveMissionGroup` use `window.confirm` ("Overwrite…? Cancel to save as new") then `window.prompt` for the name, then `alert("Routes saved.")`. Finishing a sketch asks for the route name with another `prompt` (`toggleRouteSketch`).
- **Two floating windows cover the map.** `ActiveLzWindow` (navy, its own palette) and `RoutesPanel` (graphite) overlap the imagery, and `RoutesPanel` also holds Threats, so a feature that is never saved sits inside the one that is.
- **Imports are scattered.** `.msnx` in the sidebar (`Controls.jsx`), `.LPS` inside the Save menu (`LocalPointsPanel`), `.ths` inside `ThreatsPanel`. No overlays yet.
- **Dialogs are inconsistent.** 70 `prompt`/`confirm`/`alert` calls across 27 files; the save-related ones are in `App.js` and `HistoryModal.jsx`.

## 2. Principles

1. **Save is an action on the thing you are working on; Open is a place you browse.** Never both in one menu.
2. **One save pattern everywhere.** First save asks for a name in a small dialog; every later save is silent and shows a toast. `Ctrl/⌘ S` saves the focused document.
3. **No browser dialogs** for saving, confirming or reporting success. Errors show inline or as a toast.
4. **The current workspace is always visible** (Library or a Mission Pack), because it decides where edits go.
5. **Say what is and is not stored.** Threats are never saved or shared; overlays stay in the session; a pack copy is separate from its Library original.
6. **Destructive choices are never the prominent button**, and every one names what it affects.

## 3. Layout

| Region | Today | Redesign |
|---|---|---|
| Left sidebar | Target, aircraft, analysis, tools, export, routes | Kept. "Import MSNX" moves to the Imports menu. In a finished pack the tools are disabled. |
| Top bar (new, 48 px, right of the sidebar) | Floppy icon, user name, Log out | **Workspace switcher** (left); **Import** menu, **Library** button, account menu (right). In a pack: presence avatars and sync status. |
| Floating windows | `ActiveLzWindow`, `RoutesPanel` | One **right dock**: a 344 px panel plus a 56 px icon rail on the right edge. Rail: **Pack** (pack mode only), **LZ/PZ**, **Routes**, **Threats**, **Imports**. Clicking the active rail icon collapses the panel. |
| Save menu modal | Save form + history + local points | Split into the **Save dialog** (§4) and the **Library** modal (§5). |

Notes for the build:

- The mockups use a 288 px sidebar to fit 1440 px. The real `.sidebar` is 320 px, which leaves about 720 px of map beside a 400 px dock; the existing collapse toggle (`SidebarCollapseToggle`) and the dock collapse cover smaller laptops. Below roughly 1100 px the dock should become a bottom sheet. That is **not designed yet**.
- Rail items carry a count, or a dot for state: amber = unsaved changes, violet = changed in the pack since you looked.
- Move `UserMenu` into the top bar. Log out goes inside its menu.

### Tokens

Use the existing "neutral avionics" variables in `App.css` (`--ff-bg-dark`, `--surface-1..3`, `--hairline`, `--ff-accent`, `--ff-success`, `--ff-warning`, `--ff-danger`). The old `ActiveLzWindow` navy palette goes away. Two additions:

- `--pack: #a89ae6`. Identity colour for Mission Packs only (same value as the existing `--object-control-color` violet). Never use it for state.
- Primary button fill `#3f6fa3` with white text (5.2:1). White on `--ff-accent` is about 2.6:1 and fails.

Keep text at 11 px or more, real `<button>`/`<input>`/`<label>` elements, `aria-label` on icon-only buttons, and dialogs with `role="dialog"`, `aria-modal`, `aria-labelledby`, a focus trap, Esc to close and focus returned to the opener.

## 4. The save pattern

**Document header states** (chip on the LZ/PZ card and on each route group):

| State | Chip | Button |
|---|---|---|
| Never saved | "Not saved yet" (hollow dot) | **Save** (primary) |
| Changed since last save | "Unsaved changes" (amber dot) | **Save** (primary) |
| Saved and clean | "Saved · 14:02" (green check) | "Saved", disabled |
| LZ/PZ not analyzed | "Analyze to save" | Save disabled, reason shown |

Replaces the shouting `UNSAVED` badge. LZ/PZ already tracks `dirty` and `savedId` in the workspace. Routes appear to keep only a link (`routeSaveLinks`), not a dirty flag, so "Unsaved changes" needs a comparison against the last saved snapshot. Check this before promising it.

**Save dialog** (one component, used for LZ/PZ, route sets, "Save as copy" and "Save a copy to Library"):

- Title and one-line subtitle; **Name** field pre-filled and fully selected, focused; **What is saved** list (includes and excludes: threats and routes are not part of an LZ/PZ); footer says where it saves.
- Enter saves, Esc cancels. Empty name is refused inline. No `alert`.
- **Name already used:** inline warning with two radios, *Keep both* (saves "NAME 2", default) and *Replace the saved one*. The primary button reads "Save as new" or "Replace".
- After saving: dialog closes, chip turns green, toast "Saved “NAME” to your Library" with an *Open Library* action.
- Later saves of a linked record update in place with the same toast. This replaces `confirmOverwrite`; *Save as…* in the ⋯ menu is the explicit way to make a copy.
- ⋯ menu on a document: Rename, Save as…, Add to Mission Pack…, Export, Close.

**Other confirmations** (all in `LibraryDialogs`): close with unsaved changes (Cancel / Don't save / **Save**), delete from Library, remove all threats. *Remove* on the LZ/PZ card becomes **Close**, inside ⋯, because it only removes the diagram from the session. `LzDiagramRemoveDialog` already does the close-with-unsaved job; fold it into the shared dialog.

## 5. Library (open)

Replaces `HistoryModal`. A modal opened from the **Library** button in the top bar: tabs LZ/PZ, Routes, Local points; search; sort; rows with name, grid, updated time, **Open** and a ⋯ menu (Rename, Duplicate, Add to Mission Pack…, Delete). A row already in the session shows "Open in session" and a disabled Open. Opening adds the item to the session; it never replaces unsaved work. The LZ/PZ panel also lists the three most recent items with a quick Open. Footer links to Mission Packs.

Local points stop being imported inside this modal; the tab only lists saved sets.

## 6. Imports

One **Import** menu in the top bar (Mission file `.msnx`, Local points `.LPS`, Threats `.ths`, Map or overlay), plus drag-and-drop anywhere on the map. All paths lead to the same **review dialog**: it detects each file's type from its content, shows counts, lets the person name it and choose where it lives, and rejects unsupported files with a reason.

| Type | Destinations | Notes |
|---|---|---|
| Local points | This session, Library, the open pack | Pack choice states how many members will see it. |
| Mission file | This session, Library | **Not into a pack** (v1 packs hold sketched routes only). |
| Threats | This session only | Locked, with the reason. Never saved or shared. |
| Map overlay | This session only | Assumption, see §9. |

The **Imports** panel is the index of what has been brought in: grouped by type, with a visibility eye, location chip (Session / Library / pack), overlay opacity, and links to Routes or Threats for files edited there. It does not duplicate editing.

Implementation: a `feature/imports/` folder with a `useImports` hook that owns detection and the review state, calling the existing `importMsnxFile`, `importLpsFile` and `importThsFile`. Remove the three scattered file inputs (`Controls.jsx`, `LocalPointsPanel.jsx`, `ThreatsPanel.jsx`) once it is in.

## 7. Routes and Threats panels

- **Routes:** one card per route set: "SKETCHED ROUTES" and each imported `.msnx`. Each has the state chip, **Save**, **Export .msnx** (with an *Include threats as .ths* checkbox), a route list and the plan editor. Delete moves into ⋯. "Sketch a route" and "Import .msnx" sit in the panel footer.
- **Threats:** its own tab. A calm notice: "Session only. Threats stay in this browser tab. They are never saved to your account or shared with a Mission Pack." Add, Import, list with terrain-mask chip, Export (.ths, ForeFlight/ATAK/Aero), Remove all (confirmed). Note under export that building the file sends positions to the server once and keeps nothing (matches AGENTS.md §2).

## 8. Mission Packs UI

Depends on the architecture proposal and its open decisions; build after the backend step 1 and 2 land. What the screens assume:

- **Workspace switcher** (top left): Library, then packs with member count, role, online count, finished packs muted, a pending-invite card with Accept/Decline, *New pack*, *Manage teams*. The Library row shows an amber note if it has unsaved work; switching never discards it.
- **In a pack, there is no Save button.** The card shows "Synced" and the top bar shows "Live · all changes synced" (or an offline/queued state). "Save a copy to Library" lives in ⋯. New LZ/PZs, routes and points go into the open pack.
- **Pack panel** (rail): description, members and Invite, segmented *Items | History*. Items grouped LZ/PZ, Routes, Point sets with a violet dot for "changed since you looked" and the author. A copied item shows *From Library* and, when the original has moved, *Original changed · Update* (confirm dialog lists what will be replaced). Footer: Add from Library, Finish pack (owner only).
- **History:** newest first, filter by person and item, a "New since you looked" divider, membership events, and skipped edits explained in plain words.
- **Members dialog:** invite by name (teammates only) or email, role select (Editor/Viewer), share with a team, pending invites with Resend/Revoke. States the aggregation rule: everyone sees every item; threats are never shared.
- **Finished:** banner "OP DK was finished by NAME on DATE. It is read-only for everyone." with Reopen (owner), Save a copy to Library, Duplicate as new pack. Tools and edit controls disabled; export and viewing still work.
- **Presence on the map:** named cursor flags and a ring on the aircraft someone else is moving.

Decisions the UI already assumes (proposal numbers): 3 copy with *Update from original*; 4 teams plus email invite; 5 one Finish, reopenable; 6 Editor and Viewer roles; 8 no `.msnx` in packs. If any changes, the matching screen changes.

## 9. Assumptions and gaps (need Colin)

- **Map overlays** (KMZ, GeoTIFF, image plus world file) are invented formats and session-only storage. Parsing them likely needs a new library, which AGENTS.md says to ask about first.
- Switching workspaces keeps the Library session open (§8). Confirm that is the wanted behaviour, including after a reload.
- Point-name prompts in `App.js` (`Point name:`) and the route-name prompt on finishing a sketch were not designed; use the Save dialog's visual language when they are done.
- Narrow screens and Android are not covered.

## 10. Suggested phases (each shippable, each its own PR)

1. **Primitives.** `Dialog` and `Toast` components, `SaveDialog`, tokens. No layout change. Replace the `prompt`/`confirm`/`alert` calls on the save paths.
2. **Save flows.** State chips, first-save dialog, silent later saves, `Ctrl/⌘ S`, close-with-unsaved, duplicate-name handling, routes dirty tracking if missing. Remove the save form from `HistoryModal`.
3. **Library and top bar.** New Library modal replaces `HistoryModal`; remove the floppy icon; add the top bar and move `UserMenu`.
4. **Dock.** Right dock and rail replace `ActiveLzWindow` and `RoutesPanel`; split Threats into its tab; retire the navy palette.
5. **Imports.** Menu, review dialog, panel, drag-and-drop; delete the old file inputs.
6. **Packs UI.** After the backend and the open decisions.

Phases 1 to 5 need no backend change. Notes for whoever builds them:

- Frontend tests run only on your machine: `cd frontend; $env:CI="true"; npm test`, then `npm run build` (it catches lint errors the tests miss). Update `ActiveLzWindow.test.jsx` and add tests for the dialog states.
- Keep new state in feature hooks; `App.js` is already about 1,500 lines. Suggested folders: `feature/saveDialog/`, `feature/library/`, `feature/dock/`, `feature/imports/`.
- Commit scopes follow AGENTS.md §12; a `feat` merged to `main` releases a minor version.
- Update AGENTS.md §4 (feature table) and `docs/USER_GUIDE.md` in the same PR as each phase.

## 11. Screens

| Row | Screen (`project/…dc.html`) | What it shows |
|---|---|---|
| 1 Panels | `Main` | LZ/PZ panel, unsaved |
| | `Routes` | Routes panel with plan editor |
| | `Threats` | Threats panel, session only |
| 2 Save and open | `SaveLz` | First-save dialog for an LZ/PZ |
| | `SaveRoutes` | Replaces the JS prompt |
| | `Library` | Open saved items |
| 3 Imports | `ImportsMenu` | Top-bar menu |
| | `ImportReview` | Review dialog, inside a pack |
| | `ImportsPanel` | Index of imported data |
| 4 Packs | `PackLive` | Working in a pack |
| | `Switcher` | Workspace switcher |
| | `NewPack` | Create dialog |
| | `PackItems`, `PackHistory` | Pack panel tabs |
| | `Members` | Members and invites |
| | `PackFinished` | Read-only state |
| | `PackDialogs` | Add copy, update from original, finish, save a copy |
| | `LibraryDialogs` | Name taken, unsaved, delete, remove threats |
| 5 States | `Saved` | After saving |
| | `Prototype` | Clickable save flow |
