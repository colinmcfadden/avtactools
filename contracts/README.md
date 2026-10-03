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
├─ fixtures/        golden inputs and expected outputs, one JSON file per domain
│  ├─ mgrs/         forward.json, inverse.json      reference: PyGeodesy
│  ├─ coords/       parse.json                      reference: the web app
│  ├─ planning/     aircraft.json, route.json, summary.json, graphics.json   reference: the web app
│  ├─ workspace/    diagram.json, ops.json, doghouses.json   reference: the web app
│  ├─ routes/       sketch.json                     reference: the web app (sketchOps)
│  ├─ formats/      number_text.json                reference: the web app (JavaScript's `String(n)`)
│  ├─ aircraft/     limits.json                     reference: the backend (`_apply_fields`, probed)
│  ├─ symbols/      sidc.json, presets.json, script.json, svg/*.svg   reference: the web app (milsymbol)
│  ├─ msnx/         template.msnx, parse.json, ...  reference: the web app
│  ├─ sqlite/       .LPS / .ths files + tables.json reference: SQLite itself (Python's sqlite3)
│  ├─ localpoints/  parse.json                      reference: the web app
│  ├─ threats/      parse.json, export.json         reference: the web app (read), the backend (write)
│  └─ network/      responses.json, priority.json   reference: the server's own responses; the web's priority rule
└─ scripts/         generators: the fixtures PyGeodesy owns, and tokens.py
```

## Who is the reference

| Fixture | Reference | Why |
|---|---|---|
| `mgrs/*` | **PyGeodesy**, as the backend calls it (`routes/location_routes.py`) | The server's answer is the one crews have been given. The web's `utils/mgrs.js` matches it at every point here (checked in Jest). |
| `coords/*`, `planning/*` | **The web app** | The web is the reference implementation; a change to a formula lands there first (or in the same PR). |

If a fixture and a client disagree, the client is wrong until someone decides the
reference is.

## Who checks them

| Fixture | Web (Jest) | Backend (pytest) | Android (JUnit) |
|---|---|---|---|
| `mgrs/forward.json` | `frontend/src/contracts/mgrsFixtures.test.js` | `backend/tests/test_contract_fixtures.py` | `core-geo` |
| `mgrs/inverse.json` | — (the web asks the server) | same | `core-geo` |
| `coords/parse.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-geo` |
| `planning/*.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-planning` (`summary.json`: `LzSummary`; `graphics.json`: `PlanningGraphics`) |
| `workspace/diagram.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-model` |
| `workspace/ops.json` | `frontend/src/contracts/workspaceOpsFixtures.test.js` | — | `core-model` (`DiagramOps`) |
| `workspace/doghouses.json` | `frontend/src/contracts/doghouseFixtures.test.js` | — | `core-model` (`Doghouses`, `JsValue.parseInt` / `parseFloat`) |
| `formats/number_text.json` | `frontend/src/contracts/numberTextFixtures.test.js` | — | `core-model` (`JsNumber.toText`) |
| `routes/sketch.json` | `frontend/src/contracts/sketchFixtures.test.js` | — | `core-planning` (`SketchOps`) |
| `aircraft/limits.json` | — | `backend/tests/test_contract_fixtures.py` (`AircraftLimitsTests`; `UPDATE_CONTRACTS=1` regenerates) | `core-model` (`AircraftDraft`) |
| `symbols/*` | `frontend/src/contracts/symbolFixtures.test.js` | — | `core-model` (`Sidc`, `SymbolPresets`, `UnitMarkers`), `core-symbols` (`ScriptAnswer`, `PresetSymbols`; the `svg/` files are the app's assets) |
| `msnx/*` | `frontend/src/contracts/msnxFixtures.test.js` | — | `core-formats` (`MsnxReader`; `MsnxWriter` for the `sketch-*.msnx` the web exports, all seven parts it changes, compared as parsed documents) |
| `sqlite/tables.json` | `frontend/src/contracts/sqliteFixtures.test.js` | `backend/tests/test_contract_fixtures.py` | `core-formats` |
| `localpoints/parse.json` | `frontend/src/contracts/sqliteFixtures.test.js` | — | `core-formats` |
| `threats/parse.json` | `frontend/src/contracts/sqliteFixtures.test.js` | — | `core-formats` |
| `network/responses.json` | — (it is *written* from the server) | `backend/tests/test_network_fixtures.py` | `core-network` |
| `network/priority.json` | `frontend/src/contracts/networkFixtures.test.js` | — | `core-network` |
| `threats/export.json` | — | `backend/tests/test_contract_fixtures.py` (the rows the exporter writes) | `core-formats` (`ThsExport`) |

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
  a time as each arrives (`GET /api/config` so far). `backend/tests/test_openapi_contract.py`
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
