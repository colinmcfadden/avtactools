import com.android.build.api.dsl.LibraryExtension

// Shared setup for the modules that need the Android framework: UI, the database, the app itself. The domain
// lives in the pure Kotlin modules (ezpz.kotlin-library); a module here may depend on those, never the reverse.
// AGP 9 compiles Kotlin itself, so there is no Kotlin plugin to apply.

plugins {
    id("com.android.library")
    id("ezpz.android-common")
}

extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig {
        // API 29: scoped storage and current Keystore (docs/NATIVE_APPS_PLAN.md, "Technology decisions").
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Robolectric reads merged resources and the manifest; without this a Context has no theme or strings.
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
    }
}
