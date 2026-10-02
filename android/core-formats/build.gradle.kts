plugins {
    id("ezpz.kotlin-library")
}

// File formats: pure code over java.util.zip and the JDK's DOM parser, both of which
// Android also provides, so everything here runs in plain JVM tests.
dependencies {
    api(project(":core-model"))
    testImplementation(project(":core-testing"))
}
