plugins {
    id("ezpz.android-application")
    id("ezpz.android-compose")
    id("ezpz.android-hilt")
}

android {
    namespace = "app.ezpztac.android"

    defaultConfig {
        // The server's root, without /api: the typed calls carry their own /api paths. The default is the
        // self-hosted backend in AGENTS.md §9; a build for another server passes -Pezpz.apiUrl=<root>.
        val apiUrl = providers.gradleProperty("ezpz.apiUrl").orElse("https://prod-ezpz-api.mcfadd.in/").get()
        buildConfigField("String", "API_URL", "\"$apiUrl\"")

        // The Google OAuth *web* client ID the server accepts as an ID token's audience (its GOOGLE_CLIENT_IDS). It is public, and
        // without it the Google button is not offered. -Pezpz.googleClientId=<id>
        val googleClientId = providers.gradleProperty("ezpz.googleClientId").orElse("").get()
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // A device build against the local Flask server (http://10.0.2.2:5000/) is allowed cleartext by
            // src/debug/res/xml/network_security_config.xml; release never is.
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(project(":core-designsystem"))
    implementation(project(":core-network"))
    implementation(project(":core-data"))
    implementation(project(":feature-auth"))
    implementation(project(":feature-map"))
    implementation(project(":feature-workspace"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.work.testing)
    testImplementation(testFixtures(project(":core-sync")))
}
