plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.auth"
}

// Sign-in, sign-up, verification, password reset and the .mil gate, mirroring the web's auth screen modes
// (frontend/src/feature/auth). Depends on core modules only.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
}
