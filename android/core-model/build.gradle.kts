plugins {
    id("ezpz.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Documents are stored as the same JSON the web saves (field names and all),
    // so the model types are serializable.
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core-testing"))
}
