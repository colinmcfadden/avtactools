plugins {
    id("ezpz.kotlin-library")
}

dependencies {
    api(project(":core-model"))
    testImplementation(project(":core-testing"))
}
