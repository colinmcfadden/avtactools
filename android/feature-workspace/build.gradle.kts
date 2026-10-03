plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.workspace"
}

// The LZ/PZ workspace: the list of diagrams, making one from a target, opening, renaming, deleting, and settling conflicts
// (docs/NATIVE_APPS_PLAN.md, "Feature specs"). Core modules only.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-data"))
    api(project(":core-geo"))
    implementation(project(":core-planning"))
    implementation(project(":core-symbols"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(testFixtures(project(":core-sync")))
}
