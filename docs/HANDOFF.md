# Hand-off — Android app build

For the next agent or person picking this up mid-stream. **`AGENTS.md` §17 is the detailed record** (what each module is, the
rules that bit once, what cannot be verified without a device); this file is the *where things stand and what to do next*.
Read both, then `docs/NATIVE_APPS_PLAN.md` for the plan and its phases. Refresh this file in the same commit as any change
that moves the "where things stand" or "next" sections.

Last refreshed: 2026-10-04 (after saved missions and drag on the map; the initial build, P0–P2, is complete apart from the ATAK package).

---

## 1. Where things stand

- **Branch:** `claude/jolly-wozniak-i7rlrq` (all Android work). Push with `git push -u origin claude/jolly-wozniak-i7rlrq`.
  `develop` is the PR target; **no PR has been opened** (the owner has not asked). Codex's threat UI branch
  `feat/android-threat-ui` (`bd43777`) is already merged into this branch.
- **What exists** — the plan's P0 and P1 (shell, auth, map, diagrams, analysis, graphics, units, aircraft, boundary) and P2 (routes: sketch, plan, nav log, winds, elevations, `.msnx`
  export, GPX/FPL hand-off; local points; weather; the local-only threat picture; files opened from Files, mail and the share sheet; a mission's routes brought in as a copy). Per-module
  state is in AGENTS.md §17's table. **Not in P2 yet:** an ATAK data package. (Saved missions, which keep the `.msnx` as the document and write edits back into it, and dragging and turning on the map are built: AGENTS.md §17, *Saved missions* and *Dragging and turning on the map*.)
- **First device run, 2026-10-04 (emulator):** sign-in, the map, the sheet, the system picker, importing a real AMPS mission (GOAT SUCKER.msnx), tapping and long-press-dragging a route point
  all worked, the upgrade of an existing database to version 2 kept its data, and the move reached the mission file on the server (one point of 91 changed, every other part byte for byte).
  It also found a bug no JVM test could: Android's XML parser throws on `setXIncludeAware(false)`, so **every mission import had been refused on a device**. Fixed (AGENTS.md §14).
- **Everything else is still compiled and unit-tested on a JVM only.** The map layers (MapLibre),
  the share sheet, the system picker, the JavaScript symbol sandbox, Keystore, Google sign-in, WorkManager, R8 output,
  `FLAG_SECURE` and Android's own SQLite opening the `.ths` template are **compile-verified only** (each is flagged
  "not verifiable here" in AGENTS.md). The first device run is the first real test of all of them.
- **Last full verification** (`./gradlew test testDebugUnitTest lintDebug assembleDebug`, §3) was **green on the pushed head**
  (the commit that added this note and the one before it). Re-run it after any change before pushing.

### Working tree

Clean and pushed at the time of writing (check `git status` and `git log origin/claude/jolly-wozniak-i7rlrq..HEAD`).
The threat slice (task #51) is committed: Codex's UI (`bd43777`), the ring/marker fix (`6404151`) and the held-threat card,
remove-all and mission-with-threats export on top of them.

---

## 2. Next work, in order

1. **Optionally mutation-check the threat slice** (task #51 is landed): `HomeViewModel.mapTapped` ordering, `ThreatStore`,
   `ShareExport` retention and `ThreatsViewModel`, the way the weather code was (surviving mutants become tests).
2. **Open files from outside the app** (#52): **built** — `.LPS` and `.ths` from Files, mail and the share sheet, asked about before anything is
   imported (AGENTS.md §17, *Files from other apps*). Still to do on a device: confirm Files/Gmail/the share sheet offer the app and hand over a
   readable address, and what media type real senders use (the manifest declares octet-stream and the two SQLite types, nothing broader).
3. **Mission import and saved missions** (#53, #54): **built.** An `.msnx` opened from outside, or chosen with the Routes tab's *Import mission*, is offered and **kept as the saved document**
   (`kind: mission`, with its file, synced); the person's changes are written back into the file (AGENTS.md §17, *Saved missions*). Tried against a real AMPS mission on an emulator. Still to
   do: the sheet and the file picker on the *tablet* with real missions of other shapes (one with a point moved, inserted and renamed, then opened in AMPS to confirm it opens), and what an
   open mission does when a pull replaces its record while it is open (it is overwritten by the next save, as for sets).
4. **Route hand-off**: **GPX and Garmin FPL built** (a route shared from its card; AGENTS.md §17, *Sharing a route with another app*). Not built: an ATAK data package (a zip with a
   manifest) or KML/KMZ, and threats to ATAK; both want the owner's say on what ATAK crews expect. **Dragging on the map is built** (a long press picks up a graphic, a threat or a route
   point; a tap holds it and shows the bar with the turn buttons); boundary corners are not draggable yet. The admin-attached AMPS template (`resolveExportTemplate`) is not built.
5. **Later phases** (plan): P3 offline packs / on-device viewshed / the remaining exports; P4 the 3D view. Online viewshed, KMZ and
   QR calls exist on the backend but **no Android call is made**; the owner approved a transient send *only when the person
   explicitly asks* and the server must not retain it.
6. **When the initial build is done: the dev environment / device login session** the owner asked for (§4).

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

- `LiveServerTest` / `LiveAccountTest` run the real Flask routes in a child process and need `EZPZ_LIVE_PYTHON`; without it
  they are skipped (CI sets it). One of them (`the public config needs no token`) timed out once on a cold start while
  other builds ran, and passed on rerun.
- The SDK is not in the image: `android/local.properties` (gitignored) holds `sdk.dir`; the network policy must allow
  `dl.google.com` and `jitpack.io`; Maven Central sometimes throttles with 429 — re-run (Gradle caches what it fetched).
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

**A debug build says what it is doing.** The sign-in screen shows `Server: <url>` (so a build that did not pick up `-Pezpz.apiUrl` is
obvious) and, after a failure, `Last failure: HTTP 401 · invalid_credentials · "Invalid email or password."`, or what the network said
(`ConnectException: Failed to connect to /127.0.0.1:5000`), or the exception's type. A release build passes nothing and draws nothing
(`AuthScreensTest` holds both).

Other things that stop a sign-in, in the order they usually bite:
- **The default server** (`https://prod-ezpz-api.mcfadd.in/`) sits behind Cloudflare; an *Access* policy or a down tunnel answers
  with HTML, which the client reports as an unreadable answer (`Last failure: HTTP 200 · unreadable_response`, or a 403).
- **The native-auth backend** (`refresh_tokens.py`, `config_routes.py`, the `X-EZPZ-Client` header, `/api/auth/refresh`) exists **only on this
  branch**. A server run from `develop`/`main` signs in but sends no refresh token (the session then lapses after 24 h) and has no
  `/api/config`. Run the backend from this branch locally; a deployed one is **the owner's** to deploy.
- **Too many attempts** (429) after repeated failures for one address: the screen says so; wait or restart the local backend.
- **Google sign-in** needs `-Pezpz.googleClientId`, an Android OAuth client for `app.ezpztac.unreleased.debug` + the debug SHA-1 in the
  same Google Cloud project, and that client ID in the backend's `GOOGLE_CLIENT_IDS`.

---

## 5. Owner decisions still open

- **`applicationId`** — permanent once published; release builds are refused until `-Pezpz.applicationId=<id>` is passed.
- **Mapbox token** — if it is URL-restricted, a native app (no web referrer) may be refused; give the apps their own via
  `MAPBOX_PUBLIC_TOKEN`.
- `assetlinks.json` on `ezpztac.app` (needs the applicationId and the signing key) so emailed links open the app.
- Real `.LPS`, `.ths` and AMPS `.msnx` samples beyond the fixtures, for the readers and writers.
- Vercel *Ignored Build Step* so app-only PRs skip a web preview.
- Whether the 48-hour threat retention should run from the last change (as built) or the first.
- Whether and when to open a PR to `develop`.

---

## 6. Rules that will bite if forgotten

Full list in AGENTS.md; the ones that matter most for a fresh agent:

- **Unclassified only.** Threats are never synced or persisted server-side; on the device they live in memory plus a sealed
  no-backup file for at most 48 h after the last change, wiped at sign-out. **No Android threat network call exists** — do not add
  one without the person asking for it.
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
