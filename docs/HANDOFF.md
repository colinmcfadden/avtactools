# Hand-off — Android app build

For the next agent or person picking this up mid-stream. **`AGENTS.md` §17 is the detailed record** (what each module is, the
rules that bit once, what cannot be verified without a device); this file is the *where things stand and what to do next*.
Read both, then `docs/NATIVE_APPS_PLAN.md` for the plan and its phases. Refresh this file in the same commit as any change
that moves the "where things stand" or "next" sections.

Last refreshed: 2026-10-03 (after the route hand-off slice; the initial build, P0–P2, is complete apart from the items above).

---

## 1. Where things stand

- **Branch:** `claude/jolly-wozniak-i7rlrq` (all Android work). Push with `git push -u origin claude/jolly-wozniak-i7rlrq`.
  `develop` is the PR target; **no PR has been opened** (the owner has not asked). Codex's threat UI branch
  `feat/android-threat-ui` (`bd43777`) is already merged into this branch.
- **What exists** — the plan's P0 and P1 (shell, auth, map, diagrams, analysis, graphics, units, aircraft, boundary) and P2 (routes: sketch, plan, nav log, winds, elevations, `.msnx`
  export, GPX/FPL hand-off; local points; weather; the local-only threat picture; files opened from Files, mail and the share sheet; a mission's routes brought in as a copy). Per-module
  state is in AGENTS.md §17's table. **Not in P2 yet:** the web's file-authoritative imported missions (task #54), an ATAK data package, drag/rotate on the map (#29).
- **Everything is compiled and unit-tested on a JVM; nothing has ever run on a device or emulator.** The map layers (MapLibre),
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
3. **Mission import**: **built as a copy** (task #53): an `.msnx` opened from outside, or chosen with the Routes tab's *Import mission*, is offered ("Bring in 2 routes?") and
   makes a new set of routes (points, recorded plan, elevations), opens it and takes the map there. The file is not kept or changed; exporting builds a new mission from the template.
   **Still to do — the web's file-authoritative round trip** (task #54): keep the `.msnx` as the saved document (`kind: mission`), which needs (a) the sync engine to carry a file part
   (outbox, push, pull, conflicts), (b) a memory-safe port of `mutateMsnx` (plan, point move/rename/insert written back into the XML parts; a real `legs.xml` is tens of MB, so not a DOM on a
   phone), and (c) real unclassified AMPS missions from the owner to test with (`contracts/fixtures/msnx/plan-edited.msnx` exists from the web's own tests).
4. **Route hand-off**: **GPX and Garmin FPL built** (a route shared from its card; AGENTS.md §17, *Sharing a route with another app*). Not built: an ATAK data package (a zip with a
   manifest) or KML/KMZ, and threats to ATAK; both want the owner's say on what ATAK crews expect. **Drag and rotate on the map** (#29, optional — the inspector already does
   everything), and the admin-attached AMPS template (`resolveExportTemplate`).
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

## 4. For the dev-environment / device-login session

The owner tried the debug build on a phone and **sign-in fails**. Known facts, from the code:

- The default server is `https://prod-ezpz-api.mcfadd.in/` (`-Pezpz.apiUrl=<root>`; the root, *not* ending in `/api`).
  That host sits behind Cloudflare; an **Access policy** or a tunnel that is down answers with an HTML page, which the client
  reads as a failed call. Ask for the exact on-screen message and `adb logcat` before guessing.
- A debug build allows cleartext only to `10.0.2.2`, `localhost` and `127.0.0.1`
  (`app/src/debug/res/xml/network_security_config.xml`). To use a backend on the owner's PC from a **real phone**:
  `adb reverse tcp:5000 tcp:5000` and build with `-Pezpz.apiUrl=http://127.0.0.1:5000/`.
- The native-auth backend (`refresh_tokens.py`, `config_routes.py`, the `X-EZPZ-Client` header, `/api/auth/refresh`) exists
  **only on this branch**. A server deployed from `develop`/`main` does not have it, so the app's refresh, sessions and
  `/api/config` calls fail against it. This is the most likely cause; it needs the backend side deployed (**the owner deploys**).
- Sign-in needs the response to carry `access_ok`, `is_active`, `is_admin`, `features` and `role` on the user; `refreshToken` is
  optional in the DTO. Google sign-in additionally needs `-Pezpz.googleClientId`, an Android OAuth client for
  `app.ezpztac.unreleased.debug` + the debug SHA-1 in the same Google Cloud project, and the client ID in the backend's
  `GOOGLE_CLIENT_IDS`.
- Offered, not built: a debug-only line on the sign-in screen saying which server the build targets, and a clearer message
  when the server answers HTML.

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
