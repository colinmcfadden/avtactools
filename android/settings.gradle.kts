pluginManagement {
    includeBuild("build-logic")
    repositories {
        // The Android Gradle Plugin lives on Google's Maven, and a convention plugin from build-logic needs it
        // to resolve here too, not only in build-logic itself.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Google's Maven (dl.google.com) hosts AndroidX and the Android toolchain. It is restricted to those
        // groups so a name clash elsewhere can never be satisfied from here, or the other way round.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ezpz-android"

// Pure Kotlin, no Android imports: these run on a plain JVM and are what the
// golden fixtures in ../contracts exercise (docs/NATIVE_APPS_PLAN.md,
// "App architecture"). Module names match the planned iOS Swift packages.
include(":core-model")
include(":core-geo")
include(":core-planning")
include(":core-formats")

// The API client: OkHttp over a plain JVM, so the auth and retry logic is tested here
// (against a mock server) rather than only on a device.
include(":core-network")

// Keeping saved LZs and aircraft profiles in step with the server: the outbox, the pull, and conflicts kept
// side by side. Pure logic over a store interface (Room implements it in the app), so it is tested here.
include(":core-sync")

// Android Gradle Plugin modules. The domain stays in the core modules above; these hold what needs the Android
// framework (UI, the database, the app itself). Feature modules never depend on each other, `app` wires them.
include(":core-designsystem")

// Test support: reads ../contracts/fixtures. Not part of the shipped app.
include(":core-testing")

// The app. It composes the modules above and wires navigation; nothing depends on it.
include(":app")
