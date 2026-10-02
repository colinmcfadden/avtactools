# contracts/

What the clients agree on. The web app (`frontend/`), the Android app
(`android/`) and the future iOS app each compute the same planning numbers on
the device, so none of them is allowed to be "close". This folder holds the
golden answers they are all tested against. See `docs/NATIVE_APPS_PLAN.md`
(Testing, CI/CD and release).

```
contracts/
├─ openapi.yaml     the API routes the apps call (checked against the backend's responses)
├─ fixtures/        golden inputs and expected outputs, one JSON file per domain
│  ├─ mgrs/         forward.json, inverse.json      reference: PyGeodesy
│  ├─ coords/       parse.json                      reference: the web app
│  ├─ planning/     aircraft.json, route.json       reference: the web app
│  └─ workspace/    diagram.json                    reference: the web app
└─ scripts/         generators for the fixtures PyGeodesy owns
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
| `planning/*.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-planning` |
| `workspace/diagram.json` | `frontend/src/contracts/webFixtures.test.js` | — | `core-model` |

iOS joins this table when it starts; it reads the same files.

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
