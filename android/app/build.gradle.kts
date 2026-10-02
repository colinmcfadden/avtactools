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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
}
