# contracts/

What the clients agree on. The web app (`frontend/`), the Android app
(`android/`) and the future iOS app each compute the same planning numbers on
the device, so none of them is allowed to be "close". This folder holds the
golden answers they are all tested against. See `docs/NATIVE_APPS_PLAN.md`
(Testing, CI/CD and release).

```
contracts/
├─ openapi.yaml     the API routes the apps call (checked against the backend's responses)
├─ tokens/          design tokens (colours for dark, light and night, spacing, type) — tokens.json
├─ briefs/          the mission brief's formats as data (templates, bindings, constants, standards) — see briefs/README.md
├─ fixtures/        golden inputs and expected outputs, one JSON file per domain
│  ├─ mgrs/         forward.json, inverse.json      reference: PyGeodesy; grid.json: the web
│  ├─ coords/       parse.json                      reference: the web app
│  ├─ planning/     aircraft.json, route.json, summary.json, graphics.json   reference: the web app
│  ├─ workspace/    diagram.json, ops.json, doghouses.json   reference: the web app
│  ├─ routes/       sketch.json, winds.json, handoff.json   reference: the web app (sketchOps, routeWinds, foreflight.js)
│  ├─ formats/      number_text.json                reference: the web app (JavaScript's `String(n)`)
│  ├─ aircraft/     limits.json                     reference: the backend (`_apply_fields`, probed)
│  ├─ symbols/      sidc.json, presets.json, script.json, svg/*.svg   reference: the web app (milsymbol)
│  ├─ msnx/         template.msnx, parse.json, ...  reference: the web app
│  ├─ sqlite/       .LPS / .ths files + tables.json reference: SQLite itself (Python's sqlite3)
│  ├─ localpoints/  parse.json                      reference: the web app
│  ├─ threats/      parse.json, export.json         reference: the web app (read), the backend (write)
│  ├─ brief/        compile.json (reference: the web, feature/brief/compile.js), light.json (reference: Skyfield, scripts/light_fixtures.py),
│  │                sandtable.json (reference: the web, feature/brief/sandtableSteps.js, read from compile.json's models),
│  │                strip-grid.json (reference: the web, feature/brief/stripGrid.js over mgrsGrid.js, the strips' MGRS grid)
│  ├─ packs/        ops.json, ops-brief.json, diff.json, edit.json, shared.json, describe.json, session.json, session-brief.json, settings.json, history.json
│  │                reference: the web app (feature/missionPacks/)
│  └─ network/      responses.json, priority.json   reference: the server's own responses; the web's priority rule
└─ scripts/         generators: the fixtures PyGeodesy owns, and tokens.py
```

## Who is the reference

| Fixture | Reference | Why |
|---|---|---|
| `mgrs/*` | **PyGeodesy**, as the backend calls it (`routes/location_routes.py`) | The server's answer is the one crews have been given. The web's `utils/mgrs.js` matches it at every point here (checked in Jest). |
| `mgrs/grid.json` | **The web app** (`feature/mgrsGrid/mgrsGrid.js`) | The map's MGRS grid has no server side: the web's is the one drawn. |
| `coords/*`, `planning/*` | **The web app** | The web is the reference implementation; a change to a formula lands there first (or in the same PR). |

If a fixture and a client disagree, the client is wrong until someone decides the
reference is.

## Who checks them

| Fixture | Web (Jest) | Backend (pytest) | Android (JUnit) |
|---|---|---|---|
| `mgrs/forward.json` | `frontend/src/contracts/mgrsFixtures.test.js` | `backend/tests/test_contract_fixtures.py` | `core-geo` |
| `mgrs/inverse.json` | — (the web asks the server) | same | `core-geo` |
| `mgrs/grid.json` | `frontend/src/contracts/mgrsGridFixtures.test.js` | — | `core-geo` (`MgrsGrid`) |
| `coords/parse.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-geo` |
| `planning/*.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-planning` (`summary.json`: `LzSummary`; `graphics.json`: `PlanningGraphics`) |
| `workspace/diagram.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-model` |
| `brief/compile.json` | `frontend/src/contracts/briefCompileFixtures.test.js` | `backend/tests/test_brief_pdf.py` (prints its first model) | `core-brief` (`CompileBrief`: every case, strictly: null kept apart from absent, numbers in JavaScript's digits, keys in JavaScript's order, the strips' numbers and the slope calls too; `CompileFixtureTest`) |
| `brief/light.json` | `frontend/src/contracts/briefLightFixtures.test.js` | — (reference: Skyfield, `scripts/light_fixtures.py`) | `core-brief` (`Light`, within the file's tolerances; `LightFixtureTest`) |
| `brief/strip-grid.json` | `frontend/src/contracts/briefStripGridFixtures.test.js` | `backend/tests/test_brief_pdf.py` (draws OP DK's strips with it) | `core-brief` (`StripGrid`, `StripGridTest`): the MGRS 1 km grid Android adds to each strip of `compile.json` before printing, compared within `mgrs/grid.json`'s tolerances (a line sampled with other points, an end a millimetre off) |
| `brief/sandtable.json` | `frontend/src/contracts/briefSandtableFixtures.test.js` | — | the Android sandtable (B9): each step as the sandtable lists it and what the map shows for it, from `compile.json`'s models (both are made from the same cases, `briefCases.js`, so one `UPDATE_CONTRACTS=1` run writes both) |
| `workspace/ops.json` | `frontend/src/contracts/workspaceOpsFixtures.test.js` | — | `core-model` (`DiagramOps`) |
| `workspace/doghouses.json` | `frontend/src/contracts/doghouseFixtures.test.js` | — | `core-model` (`Doghouses`, `JsValue.parseInt` / `parseFloat`) |
| `formats/number_text.json` | `frontend/src/contracts/numberTextFixtures.test.js` | — | `core-model` (`JsNumber.toText`) |
| `routes/sketch.json` | `frontend/src/contracts/sketchFixtures.test.js` | — | `core-planning` (`SketchOps`) |
| `routes/winds.json` | `frontend/src/contracts/routeWindsFixtures.test.js` | — | `core-planning` (`RouteWinds`: the question a route asks for winds and how the answer is merged into the plan; times are wall-clock to the millisecond, so the fixture does not depend on the zone it was made in) |
| `routes/handoff.json` | `frontend/src/contracts/handoffFixtures.test.js` | — | `core-formats` (`RouteHandoff`: the Garmin FPL, the GPX, the ForeFlight route string and URL a route is handed to other apps as; byte for byte, with the FPL's clock fixed) |
| `aircraft/limits.json` | — | `backend/tests/test_contract_fixtures.py` (`AircraftLimitsTests`; `UPDATE_CONTRACTS=1` regenerates) | `core-model` (`AircraftDraft`) |
| `symbols/*` | `frontend/src/contracts/symbolFixtures.test.js` | — | `core-model` (`Sidc`, `SymbolPresets`, `UnitMarkers`), `core-symbols` (`ScriptAnswer`, `PresetSymbols`; the `svg/` files are the app's assets) |
| `msnx/edits.json`, `msnx/edits/*.msnx` | `frontend/src/contracts/msnxEditFixtures.test.js` | — | `core-formats` (`MsnxMutator`: what the web writes when an imported mission is edited, compared as parsed documents) |
| `msnx/*` | `frontend/src/contracts/msnxFixtures.test.js` | — | `core-formats` (`MsnxReader`; `MsnxWriter` for the `sketch-*.msnx` the web exports, all seven parts it changes, compared as parsed documents) |
| `sqlite/tables.json` | `frontend/src/contracts/sqliteFixtures.test.js` | `backend/tests/test_contract_fixtures.py` | `core-formats` |
| `localpoints/parse.json` | `frontend/src/contracts/sqliteFixtures.test.js` | — | `core-formats` |
| `threats/parse.json` | `frontend/src/contracts/sqliteFixtures.test.js` | — | `core-formats` |
| `network/responses.json` | — (it is *written* from the server) | `backend/tests/test_network_fixtures.py` | `core-network` |
| `network/priority.json` | `frontend/src/contracts/networkFixtures.test.js` | — | `core-network` |
| `threats/export.json` | — | `backend/tests/test_contract_fixtures.py` (the rows the exporter writes) | `core-formats` (`ThsExport`) |
| `packs/ops.json` | `frontend/src/contracts/packOpsFixtures.test.js` | `backend/tests/test_pack_ops.py` (the server applies every mission-pack edit with it) | `core-missionpacks` (`PackOps`: every case, strictly: status, reason, null kept apart from absent, keys in JavaScript's order; `PackOpsFixtureTest`) |
| `packs/ops-brief.json` | `frontend/src/contracts/packOpsFixtures.test.js` | `backend/tests/test_pack_ops.py` | `core-missionpacks` (`PackOpsFixtureTest`, its cases beside `ops.json`'s, as strictly) |
| `packs/session-brief.json` | `frontend/src/contracts/packSessionFixtures.test.js` | — | `core-missionpacks` (`PackSessionFixtureTest`, every scenario as `session.json`'s are played): brief edits taken in a read-only pack, sent in batches of their own, kept when the pack turns read-only |
| `packs/diff.json` | `frontend/src/contracts/packDiffFixtures.test.js` | — | `core-missionpacks` (`PackDiff`: the operations an editor's change is sent as, every case strictly and in order, each rebuilt with `PackOps`, and every `sameData` pair; the `webBug` cases and pairs are not copied and have tests of the right behaviour; `PackDiffFixtureTest`) |
| `packs/shared.json`, `packs/describe.json`, `packs/edit.json` | `frontend/src/contracts/packSharedFixtures.test.js` | — | `core-missionpacks` (`PackLz`, `PackRoutes`, `PackPoints`, `PackSentences`, `PackEdit`: what is each person's own, the history's sentences and new items' names, how one edit is put together; every case, the `webBug` ones not copied and tested for the right behaviour; `PackSharedFixtureTest`, `PackDescribeFixtureTest`, `PackEditFixtureTest`) |
| `packs/settings.json` | `frontend/src/contracts/packSettingsFixtures.test.js` | — | `core-missionpacks` (`LzPackKind`, `RoutePackKind`, `PackDiff`: each setting's item read and written back unchanged, the web's operations for it strictly and in order, and what the port sends instead, kept whole, rebuilding the same item; `PackSettingsFixtureTest`) |
| `packs/history.json` | `frontend/src/contracts/packHistoryFixtures.test.js` | — | `core-missionpacks` (`PackHistory`: a pack's log read as the history's lines, every case, its events read strictly as the server's; `PackHistoryFixtureTest`) |
| `packs/session.json` | `frontend/src/contracts/packSessionFixtures.test.js` | `backend/tests/test_openapi_contract.py` (`PackSessionFixtureTests`: every input is what `openapi.yaml` says the server sends) | `core-missionpacks` (`PackSessions`: every scenario step by step, with the whole session, what is visible, each answer and the `dropped_versions`, strictly: null kept apart from absent, keys in JavaScript's order; `PackSessionFixtureTest`) |

iOS joins this table when it starts; it reads the same files.

### The `msnx/` fixtures

`*.msnx` are real zip files (the web's template and four files the web's own export
produced, each reduced to the five parts the readers use) and `parse.json` holds what
the web's `parseMsnx` reads from each. `amps_values.json` is the text-to-number step on
its own: the airspeed, wind and metres fields AMPS writes as prose. The `.msnx` bytes
are fixtures too: the web suite fails if a rebuild differs from what is committed, so
the Kotlin reader is always tested on the files the web would produce today.
Everything inside is invented; no file from a real mission. A real, unclassified AMPS
export from the owner would be added as a further case, and is the better test.

### The `packs/` fixtures

Mission packs (`docs/MISSION_PACKS.md`) are edited through id-addressed operations, and the web's
`frontend/src/feature/missionPacks/` is the reference for every step a client takes. The native apps' pack
sync is held to these files:

| File | What it pins | Web code |
|---|---|---|
| `ops.json` | What one operation does to a pack's items (the server applies it the same way) | `packOps.js` |
| `ops-brief.json` | The same, for the `brief` kind | `packOps.js` |
| `session-brief.json` | `session.json`'s rules for brief edits: taken in a read-only pack, their own batches, kept when the pack turns read-only | `packSession.js` |
| `diff.json` | The operations an editor's change to an item is sent as, in order | `packDiff.js` |
| `edit.json` | One change as sent: the rename, the operations that bring an old item to today's shape, the change, one sentence on each | `packEdit.js` (`composeEdit`, from `usePackItemSync`) |
| `shared.json` | What of an item is each person's own and never sent; a set's points made ready; the name and library form of a person's own version of an item ("NAME (my edits)"); the shape an editor gives an LZ/PZ or route set it opens, which the reshape in `edit.json` brings an older item to | `packLz.js`, `packRoutes.js` (with `useLzWorkspace.normalizeLzDiagram` and `useRouteSketch.restoreSketchRoute`), `usePackPoints.js`, `packSentences.js`, `packActions.js` |
| `describe.json` | The history's sentences, who a person is in them, and how a new item is named and made | `packLz.js`, `packRoutes.js`, `usePackPoints.js`, `packSentences.js`, `packActions.js` |
| `session.json` | How an open pack is held: edits made here, batches, the server's events and refusals, reloads, what is shown, and what is kept when the pack will not take an edit | `packSession.js` |
| `settings.json` | Every setting of a planning graphic or a route the web makes in a pack, as the item before and after it and the operations sent for it, made by the web's own code | the graphics' hooks through `useLzWorkspace`'s reducer as `App.js` wires them, `sketchOps.js` and `routeWinds.js` as `useRouteSketch` applies them, `packDiff.js` |
| `history.json` | How a pack's log is read as its history's lines: one line for each change, however many operations it went to the pack as (a doghouse turned is two, an analysis five), never across where "new since you looked" starts | `packHistory.js` (the Pack panel's History, `ui/PackPanel.jsx`) |

- **`webBug: true`** marks a case that pins a known web bug, kept because the web is the reference until it
  is fixed and the file regenerated (`AGENTS.md` §15): a sentence or name cut through an emoji (a lone
  surrogate the server cannot store), fields read through JavaScript's prototype in `packDiff.js`, and in
  `shared.json`'s shapes a field a newer version wrote, which the web drops (a port keeps it), and a route
  the web's shape leaves out while its editor keeps it. A port's test finds them by the mark and does not
  copy them; the web suite fails if a mark no longer matches a bug, so each goes when the web is fixed.
- A key missing from a case is JavaScript's `undefined`. In `session.json` an absent key and a null one are
  different (the session builds some shapes narrower than the server's types); each file's `description`
  says how to read it.
- `session.json` is written with values up to 800 characters on one line and longer ones broken up, so a
  regenerated scenario diffs step by step.

```powershell
cd frontend
$env:UPDATE_CONTRACTS="1"; $env:CI="true"; npx react-scripts test --watchAll=false src/contracts/pack
Remove-Item Env:UPDATE_CONTRACTS; npx react-scripts test --watchAll=false src/contracts/pack src/feature/missionPacks
```

### The `network/` fixtures

`responses.json` is what the server really answered, recorded from its own code by
`backend/tests/test_network_fixtures.py`: sign-in (native and web), the refresh rotation (including a repeat
inside the grace period and a spent token after it), sign-up, verification, password reset and the `.mil` gate
(each with its refusals and a real 429), saved LZs, the conflict and error shapes, the change feed,
aircraft profiles and account deletion. Each response of a route that `openapi.yaml` describes is also checked
against it as it is recorded, so the spec cannot lag the server. Tokens, timestamps, generated ids and the server version are replaced by
placeholders, so the file is stable. The native clients decode every body with their own types (strictly: an
unknown field fails there) and replay the bodies against a mock server to test their auth and retry logic. A
change in what the server says fails the backend test until the file is regenerated, and then the clients'
tests show what they have to follow.

```powershell
cd backend; $env:UPDATE_CONTRACTS="1"; python -m pytest tests/test_network_fixtures.py
```

### The `sqlite/` fixtures

`.LPS` and `.ths` files are SQLite databases, and the web and the apps each read them with a small
reader of their own, so the reader needs a reference that is not itself: **SQLite**. `tables.json` is
every table of every file as Python's `sqlite3` reads it, and both readers are held to it, including
a table three b-tree levels deep, a value spread over overflow pages, and one value of every
storage class and integer width, positive and negative. `parse.json` files are then what the web's
parsers make of those files.

- `threats.ths` is written by the backend's own exporter (`backend/ths_export.py`), so the reader is
  tried on real exporter output and the native exporters are held to its rows (`threats/export.json`
  has the inputs; the rows are `tables.json["threats.ths"]`).
- The `.LPS` files are synthetic (a `Points` table with SpatiaLite POINT blobs). A real, unclassified
  `.LPS` and `.ths` from AMPS would be the better test and are added here when the owner has them.
- `check` compares *content*, never bytes: the bytes of a SQLite file carry the version of SQLite that
  wrote them, so they differ between machines while meaning the same.
- `utf16le.db` and `utf16be.db` are for the native readers only; the web's reader assumes UTF-8.

```powershell
python contracts/scripts/sqlite_fixtures.py write    # regenerate the databases and tables.json
python contracts/scripts/sqlite_fixtures.py check    # needs nothing but Python
```

## Changing a fixture

Fixtures change only when someone regenerates them, and the diff is reviewed like
code — a changed number is a changed plan on a crew's kneeboard.

```powershell
# MGRS (needs pygeodesy, as pinned in backend/requirements.txt)
python contracts/scripts/mgrs_fixtures.py write
python contracts/scripts/mgrs_fixtures.py check    # exact regeneration check, ~20 s

# Everything the web app is the reference for
cd frontend
$env:UPDATE_CONTRACTS="1"; $env:CI="true"; npx react-scripts test --watchAll=false src/contracts
```

Without `UPDATE_CONTRACTS`, the web suite *fails* if the committed fixture differs
from what the web code produces. So a formula change cannot reach `develop`
without the fixture diff in the same PR — and the Android tests then fail until
`core-planning` follows.

## Conventions

- **Format.** JSON, one case per line, so a regenerated file diffs case by case.
  Bulk tables are `columns` plus arrays; small ones are objects.
- **Tolerances** are stated inside each file (`tolerance`, `toleranceDeg`) and
  are the loosest the plan promises. Exact outputs (grid strings, formatted text,
  integer capacities) are compared exactly.
- **Not-a-number and absent values** are written `null`, so every language reads
  the same thing.
- **Clock times** are the local time-of-day on the plan's date, on dates without a
  daylight-saving change.
- **No clock, no randomness.** A case that omits a timestamp or an id would make the
  web read the clock or invent a random id, and the fixture could never be
  reproduced. While a fixture is built those throw, so give each case what it needs.
- **Never hand-edit a fixture.** Change the reference or the generator and
  regenerate.
- **No real data.** Everything here is invented or public. Nothing from a real
  mission, no CUI. Real, unclassified AMPS files for round-trip tests come from
  the owner and are added deliberately.
- `openapi.yaml` is the other half of this folder: the routes the apps call, one at
  a time as each arrives. The mission-pack routes are described ahead of any client and
  held by `backend/tests/test_packs.py`. `backend/tests/test_openapi_contract.py`
  holds the server's responses to it and fails on an added, removed or retyped
  field. Changes are additive: store builds stay in the field for months.

## Known limits

- **MGRS covers 80°S–84°N** (UTM). The polar caps use UPS, which no landing zone
  needs. The web, Android and iOS answer "no answer" there; the server's
  `/api/convert-grid` could parse a UPS grid, but the web never sends one (its
  grid pattern requires a zone number).
- The inverse accepts any even number of digits, as PyGeodesy does. Past ten
  digits the square is smaller than a metre.

## Design tokens

`tokens/tokens.json` is the one place a colour, a spacing step or a type size is decided. The clients keep the same
names, so "primary" or "touchTarget" means the same on every platform. `scripts/tokens.py write` generates the Android
`Tokens.kt` (the iOS file joins when iOS starts) and `tokens.py check` fails if the generated file is stale; CI runs it.

The palettes start from the web app's colours (the navy `#092137` app background, the blues, and its red, amber and
green). The **night** palette is dimmed and red-shifted for a night cockpit. Android's `TokenContrastTest` holds every
palette to WCAG contrast (4.5:1 for text, 3:1 for icons and large text), so a colour edit that would be hard to read
fails there; the first run of that test found two real problems in the first draft.

```powershell
python contracts/scripts/tokens.py write
python contracts/scripts/tokens.py check
```
