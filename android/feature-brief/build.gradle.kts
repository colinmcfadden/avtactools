plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.briefs"
}

// The mission brief's screens on a tablet (docs/MISSION_BRIEF.md, B8 and B9): the setup in three steps, the review over the real PDF, Share and Save. They lay out what
// core-brief compiles and core-data keeps (BriefSession). Core modules only.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-data"))

    implementation(libs.androidx.activity.compose)               // the system's create-document picker: Save to device
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(project(":core-testing"))
}
