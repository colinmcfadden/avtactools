plugins {
    id("ezpz.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

// The API client, auth session and token refresh (docs/NATIVE_APPS_PLAN.md, "App architecture").
// OkHttp and kotlinx.serialization are on the plan's approved list; nothing here touches
// Android, so the whole of it runs in JVM tests against a mock server.
dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(project(":core-testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// The live-server tests run the real Flask routes (backend/tests/live_server.py), so editing the server has to
// re-run them, and so does pointing them at a different Python (or at none, which skips them).
tasks.test {
    inputs.files(
        fileTree(rootProject.layout.projectDirectory.dir("../backend")) {
            include("**/*.py", "*.ths")
            exclude("**/__pycache__/**", "venv/**", ".venv/**")
        },
    ).withPropertyName("backendSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("livePython", providers.environmentVariable("EZPZ_LIVE_PYTHON").orElse(""))
}
