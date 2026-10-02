import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared setup for every pure-Kotlin module: no Android imports, so the code
// runs in plain JVM tests and is what the golden fixtures exercise. Android
// Gradle Plugin modules get their own convention plugin beside this one.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Android's toolchain is JDK 17; compile to it even when a newer JDK runs the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Bytecode level alone would let code call a JDK 18+ method that only fails on
        // CI's JDK 17 or on a device; this limits the API to what JDK 17 has.
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors = true
    }
}

val libs = versionCatalogs.named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// The golden fixtures live in ../contracts, shared with the web, backend and iOS.
// Declared as an input so editing a fixture re-runs the tests that read it.
val contractsDir = rootProject.layout.projectDirectory.dir("../contracts/fixtures")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("ezpz.contracts", contractsDir.asFile.absolutePath)
    inputs.dir(contractsDir).withPropertyName("contractFixtures")
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
