plugins {
    id("ezpz.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// Mission packs (docs/MISSION_PACKS.md): the web's feature/missionPacks ported file by file and held to
// contracts/fixtures/packs, then the client that sends an open pack's edits and follows everyone else's. No Android
// code, so every rule is tested here, including against the real server (backend/tests/live_server.py). Not map
// packs: those are the plan's core-mappacks.
dependencies {
    api(project(":core-model"))
    api(project(":core-network"))
    api(libs.kotlinx.coroutines.core)
    // Operations, events and a pack's items are JSON trees in the public signatures.
    api(libs.kotlinx.serialization.json)

    // What the stores and the servers are tried with, shared with core-data: the scenarios as plain functions, an
    // in-memory pack server with the real one's rules, and devices to drive them.
    testFixturesApi(project(":core-network"))
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)

    testImplementation(project(":core-testing"))
    testImplementation(testFixtures(project(":core-network")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
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
