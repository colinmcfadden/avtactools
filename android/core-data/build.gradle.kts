plugins {
    id("ezpz.android-library")
    id("ezpz.android-room")
    id("ezpz.android-hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.ezpztac.data"
}

// The local database (docs/NATIVE_APPS_PLAN.md, "Data model and local storage"): the source of truth the screens read
// and the sync engine works through. The rules live in core-sync; this is where they are kept.
dependencies {
    api(project(":core-model"))
    api(project(":core-sync"))
    api(project(":core-planning"))                               // a route being drawn is made by its SketchOps
    api(project(":core-missionpacks"))                           // mission packs: their engine, kept here in Room
    implementation(project(":core-formats"))                     // a set of routes is exported as an AMPS mission
    implementation(project(":core-geo"))
    implementation(libs.androidx.core.ktx)                       // the platform SQLite helpers a .ths is written with
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core-sync")))
    testImplementation(testFixtures(project(":core-missionpacks")))
    testImplementation(project(":core-testing"))
}
