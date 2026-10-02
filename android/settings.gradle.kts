pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // When the Android modules arrive, add Google's Maven repository here,
        // restricted to what only it hosts:
        //   google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }
    }
}

rootProject.name = "ezpz-android"

// Pure Kotlin, no Android imports: these run on a plain JVM and are what the
// golden fixtures in ../contracts exercise (docs/NATIVE_APPS_PLAN.md,
// "App architecture"). Module names match the planned iOS Swift packages.
include(":core-model")
include(":core-geo")
include(":core-planning")

// Test support: reads ../contracts/fixtures. Not part of the shipped app.
include(":core-testing")
