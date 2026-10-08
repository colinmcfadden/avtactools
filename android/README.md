# android/

The EZ/PZ Android app, in Kotlin. The plan, the decisions behind it and the
phases are in [`docs/NATIVE_APPS_PLAN.md`](../docs/NATIVE_APPS_PLAN.md); the rules
for working here are in [`AGENTS.md`](../AGENTS.md) §17.

## What is here

The domain modules are plain Kotlin that runs on a JVM, which is what the plan
requires of the domain layer. The modules that need the Android framework sit beside them.

```
android/
├─ core-model/      LatLon, Mgrs, AircraftProfile and the aircraft form's draft, route plan and result — in the web's saved-JSON shape
├─ core-geo/        MGRS both ways, coordinate parsing, great-circle distance and course
├─ core-planning/   aircraft geometry, capacity, separation, the route planner
├─ core-formats/    reads AMPS .msnx, .LPS and .ths files; the rows of a .ths export
├─ core-network/    the API client: transport, sign-in, token refresh, typed routes, request priority
├─ core-sync/       the sync engine: outbox, pull by cursor, conflicts kept side by side
├─ core-testing/    reads ../contracts/fixtures (test support, not shipped)
├─ core-data/       Room/sync repositories plus the encrypted short-lived threat picture and native .ths transfer
├─ core-designsystem/ theme (dark, light, night), type, tokens generated from ../contracts/tokens
├─ core-symbols/    MIL-STD-2525C symbols as bitmaps: pre-rendered presets, milsymbol in the system JS sandbox, AndroidSVG
├─ feature-map/     the 2D map: MapLibre, diagrams, routes, local points, threat symbols and range rings
├─ feature-auth/    sign-in, sign-up, verify, reset and the .mil gate: screens and their view models
├─ feature-workspace/ the planning sheet: diagrams, graphics, aircraft, boundaries, routes, points, weather and local threats
├─ app/             the application: Hilt, Compose, manifest, the shell and the map with its sheet
└─ build-logic/     the convention plugins every module uses
```

Not here yet: `core-packs`, offline threat viewsheds, KMZ/QR export, full LZ-card exports and 3D.

Every module is tested against the golden fixtures in [`../contracts`](../contracts/README.md),
the same files the web app's tests read, so the app cannot quietly disagree with
the web about a grid, a capacity or a leg time.

## Build and test

Needs a JDK 21 or newer to run (the code compiles to 17, but Robolectric's sandbox for Android SDK 36 refuses an
older Java); Android Studio's bundled JDK will do (`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`).
On the owner's machine (PowerShell):

```powershell
cd android
.\gradlew.bat test
```

On Linux or macOS, `./gradlew test`. The first run downloads Gradle and the
dependencies. `-Xjdk-release=17` is set, so code that needs a newer JDK fails to
compile here rather than on CI or a device.

Tests read `../contracts/fixtures` and Gradle re-runs them when a fixture
changes. To see one fail, edit a number in a fixture and run the tests.

### Against a backend on your own PC

A local backend has its own empty database, so sign in with an account made there: `python backend/dev_user.py you@example.com`
(see `docs/HANDOFF.md` §4 for the whole procedure, `adb reverse` and the emulator address). Debug builds show the server they talk to and
the last failure on the sign-in screen.

## The Android modules

Needs the Android SDK (`platforms;android-37.0`, `build-tools;37.0.0`) and Gradle 9, which the
wrapper fetches. Tell Gradle where the SDK is, once, in `android/local.properties` (gitignored):

```powershell
"sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk" | Out-File -Encoding ascii local.properties   # forward slashes: a backslash is an escape in .properties
.\gradlew.bat test lintDebug assembleDebug     # what CI runs
.\gradlew.bat :app:installDebug                # to a connected device or emulator
```

A debug build is `app.ezpztac.unreleased.debug`. To talk to a Flask server on your machine from the
emulator: `.\gradlew.bat :app:installDebug -Pezpz.apiUrl=http://10.0.2.2:5000/` (debug builds allow
cleartext to that address only). `-Pezpz.googleClientId=<web client ID>` turns the Google button on.

A release build is refused until the owner has chosen the `applicationId`
(`-Pezpz.applicationId=<id>`): it is permanent once published.

To add a module: `include(":name")` in `settings.gradle.kts`, apply `ezpz.android-library` (and
`ezpz.android-compose`, `ezpz.android-hilt` if it needs them) in its `build.gradle.kts`, and add its
libraries to `gradle/libs.versions.toml`. Features never depend on each other; `app` wires them.
The plan approves the libraries on its list; anything else needs the owner's approval first.

## Not decided

- **`applicationId`.** Permanent once the app is on Play; the owner decides. The
  Kotlin packages are `app.ezpztac.*`.
