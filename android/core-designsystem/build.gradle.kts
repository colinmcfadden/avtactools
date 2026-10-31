plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
}

android {
    namespace = "app.ezpztac.designsystem"
}

// Tokens, type, colour and the shared pieces every screen uses (docs/NATIVE_APPS_PLAN.md, "App architecture").
// It depends on nothing of ours, so any feature module may use it.
dependencies {
    implementation(libs.androidx.core.ktx)
}
