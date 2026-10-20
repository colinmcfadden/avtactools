plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.viewer3d"
}

// The 3D LZ view (docs/ANDROID_3D_PLAN.md): the page that takes the whole screen, the point cloud's lookup and build over core-data, and the
// OpenGL ES renderer that draws core-scene3d's scene. What is drawn is decided in core-scene3d, where it is tested; the GL here is compile-only.
dependencies {
    implementation(project(":core-designsystem"))
    api(project(":core-data"))
    implementation(project(":feature-map"))                         // the 2D map's Mapbox token, which the imagery is fetched with

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(project(":core-testing"))
    testImplementation(libs.okhttp.mockwebserver)
}
