# android/

The EZ/PZ Android app, in Kotlin. The plan, the decisions behind it and the
phases are in [`docs/NATIVE_APPS_PLAN.md`](../docs/NATIVE_APPS_PLAN.md); the rules
for working here are in [`AGENTS.md`](../AGENTS.md) §17.

## What is here

Only the modules with no Android dependency, so far. They are plain Kotlin that
runs on a JVM, which is what the plan requires of the domain layer.

```
android/
├─ core-model/      LatLon, Mgrs, AircraftProfile, route plan and result — in the web's saved-JSON shape
├─ core-geo/        MGRS both ways, coordinate parsing, great-circle distance and course
├─ core-planning/   aircraft geometry, capacity, separation, the route planner
├─ core-formats/    reads AMPS .msnx, .LPS and .ths files; the rows of a .ths export
├─ core-network/    the API client: transport, sign-in, token refresh, typed routes, request priority
├─ core-sync/       the sync engine: outbox, pull by cursor, conflicts kept side by side
├─ core-testing/    reads ../contracts/fixtures (test support, not shipped)
└─ build-logic/     the convention plugin every module uses
```

Not here yet, because they need the Android Gradle Plugin: `app`, `core-data`,
`core-network`, `core-designsystem`, the `feature-*` modules and the 2D map. See
"Adding the Android modules" below.

Every module is tested against the golden fixtures in [`../contracts`](../contracts/README.md),
the same files the web app's tests read, so the app cannot quietly disagree with
the web about a grid, a capacity or a leg time.

## Build and test

Needs a JDK 17 or newer. On the owner's machine (PowerShell):

```powershell
cd android
.\gradlew.bat test
```

On Linux or macOS, `./gradlew test`. The first run downloads Gradle and the
dependencies. `-Xjdk-release=17` is set, so code that needs a newer JDK fails to
compile here rather than on CI or a device.

Tests read `../contracts/fixtures` and Gradle re-runs them when a fixture
changes. To see one fail, edit a number in a fixture and run the tests.

## Adding the Android modules

1. Add Google's Maven repository to `settings.gradle.kts` (the commented block in
   `dependencyResolutionManagement`), restricted to what only it hosts. The build
   environment must be able to reach `dl.google.com`; the agent sandbox this was
   started in could not, which is why nothing here uses the Android plugin.
2. Add the Android Gradle Plugin, AndroidX, Compose, Room, Hilt and MapLibre to
   `gradle/libs.versions.toml`, at versions that are compatible with each other and
   with Kotlin 2.4. The plan approves these libraries; anything else needs
   the owner's approval first.
3. Add an `ezpz.android-library` convention plugin beside `ezpz.kotlin-library`.
4. Put the module list from the plan's "App architecture" in, in order:
   `core-designsystem`, `core-network`, `core-data`, then `app`, then the features.
   Features never depend on each other; `app` wires navigation.
5. Add the module to `.github/workflows/android.yaml` (it runs `./gradlew test`
   for everything today).

## Not decided

- **`applicationId`.** Permanent once the app is on Play; the owner decides. The
  Kotlin packages are `app.ezpztac.*`.
- **Versioning.** The plan has the app carry the product's semantic version.
  `.releaserc.json` gives `android`-scoped commits `release: false`, so they never
  bump it; how the app reads that version at build time is for the first `app`
  module to settle.
