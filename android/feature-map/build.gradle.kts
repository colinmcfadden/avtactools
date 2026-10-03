plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.map"
}

// The 2D map: MapLibre Native, with the same Mapbox raster styles the web uses plus the FAA VFR sectional
// (docs/NATIVE_APPS_PLAN.md, "Technology decisions"). MapLibre has no telemetry, which matters for unit locations.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-model"))
    api(project(":core-geo"))
    api(project(":core-planning"))
    api(libs.maplibre.android)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(project(":core-testing"))
}
