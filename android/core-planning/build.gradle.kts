plugins {
    id("ezpz.kotlin-library")
}

dependencies {
    api(project(":core-model"))
    api(project(":core-geo"))
    testImplementation(project(":core-testing"))
}
