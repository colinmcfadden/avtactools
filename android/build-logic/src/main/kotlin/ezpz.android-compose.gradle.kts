import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

// Jetpack Compose with Material 3. Applied on top of ezpz.android-library or ezpz.android-application.

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = versionCatalogs.named("libs")

// An application and a library have separate extension types, and only one of them is there.
extensions.findByType(LibraryExtension::class.java)?.buildFeatures?.compose = true
extensions.findByType(ApplicationExtension::class.java)?.buildFeatures?.compose = true

dependencies {
    "implementation"(platform(libs.findLibrary("compose-bom").get()))
    "androidTestImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-foundation").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
    "testImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
}
