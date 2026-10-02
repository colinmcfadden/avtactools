import com.android.build.api.dsl.ApplicationExtension

// The app module. Version name comes from the product's version, which semantic-release writes to
// frontend/package.json; the build number is the CI run number, so every CI build is a distinct upload to a store.

plugins {
    id("com.android.application")
    id("ezpz.android-common")
}

val productVersion: String = run {
    val manifest = rootProject.layout.projectDirectory.file("../frontend/package.json").asFile
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifest.readText())?.groupValues?.get(1)
        ?: error("no \"version\" in ${manifest.path}")
}

// The applicationId is permanent once an app is on Play, and the owner has not chosen it (AGENTS.md §17). A
// development build gets a clearly temporary one; a release build refuses to be made without a deliberate choice.
val placeholderApplicationId = "app.ezpztac.unreleased"
val chosenApplicationId = providers.gradleProperty("ezpz.applicationId")

extensions.configure<ApplicationExtension> {
    compileSdk = 37
    defaultConfig {
        applicationId = chosenApplicationId.orElse(placeholderApplicationId).get()
        minSdk = 29
        targetSdk = 36
        versionName = productVersion
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.orElse(1).get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

// Checked as soon as the tasks to run are known, so a release build is refused before any of its work, not after.
val hasChosenApplicationId = chosenApplicationId.isPresent
val thisProject = project

gradle.taskGraph.whenReady {
    val releaseWork = allTasks.any { it.project == thisProject && it.name.matches(Regex("(assemble|bundle|package)Release")) }
    if (releaseWork && !hasChosenApplicationId) {
        throw GradleException(
            "Refusing to build a release with the placeholder applicationId '$placeholderApplicationId'. It is permanent once " +
                "published: the owner chooses it, then pass -Pezpz.applicationId=<id>.",
        )
    }
}
