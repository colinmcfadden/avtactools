plugins {
    id("ezpz.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// The sync engine (docs/NATIVE_APPS_PLAN.md, "Sync and conflicts"): an outbox of local changes pushed in order,
// a pull by cursor, and conflicts kept side by side. It talks to a SyncStore (Room, in the app) and to the
// server through core-network, and has no Android code, so every rule is tested here, including against the
// real server (backend/tests/live_server.py).
dependencies {
    api(project(":core-model"))
    api(project(":core-network"))
    api(libs.kotlinx.coroutines.core)

    // What the stores and the servers are tried with, shared with core-data: the scenarios as plain functions (no test
    // framework needed to run them), an in-memory server with the real one's rules, and devices to drive them.
    testFixturesApi(project(":core-network"))
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)

    testImplementation(project(":core-testing"))
    testImplementation(testFixtures(project(":core-network")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The live-server tests run the real Flask routes, so editing the server re-runs them.
tasks.test {
    inputs.files(
        fileTree(rootProject.layout.projectDirectory.dir("../backend")) {
            include("**/*.py", "*.ths")
            exclude("**/__pycache__/**", "venv/**", ".venv/**")
        },
    ).withPropertyName("backendSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("livePython", providers.environmentVariable("EZPZ_LIVE_PYTHON").orElse(""))
}
