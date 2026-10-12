plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.packs"
}

// The mission-pack screens (docs/ANDROID_SCREENS_PLAN.md, P1-P4): the workspace switcher, the pack's panel, its people and its dialogs, over the
// engine core-data runs (PackWorkspace) and the typed pack calls (core-network). Core modules only.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(project(":core-testing"))
}
