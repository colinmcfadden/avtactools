plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)

    // The Android convention plugins configure these, so they have to be on this build's classpath.
    // Modules apply the convention plugins and never name these plugins themselves.
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.compose.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
}
