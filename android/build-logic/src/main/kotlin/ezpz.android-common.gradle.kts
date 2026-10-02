import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// What every Android module shares, applied by ezpz.android-library and ezpz.android-application after the Android
// plugin: Kotlin settings, and unit tests that run on the JVM against Robolectric's Android (no device needed).

val libs = versionCatalogs.named("libs")

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    // JUnit 4, because that is what Robolectric and AndroidX Test run on; the pure modules use JUnit 6.
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("robolectric").get())
    "testImplementation"(libs.findLibrary("androidx-test-core").get())
    "testImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testImplementation"(libs.findLibrary("turbine").get())
}

// The golden fixtures live in ../contracts, shared with the web, backend and iOS.
val contractsDir = rootProject.layout.projectDirectory.dir("../contracts/fixtures")

// Robolectric reaches into the JDK to fake Android's file descriptors and class loading; JDK 17 and newer close those
// packages by default.
val robolectricOpens = listOf(
    "java.base/java.lang", "java.base/java.lang.reflect", "java.base/java.io", "java.base/java.net", "java.base/java.nio",
    "java.base/java.util", "java.base/java.util.concurrent", "java.base/java.text", "java.base/jdk.internal.access",
    "java.base/sun.nio.ch", "java.base/sun.security.util",
)

tasks.withType<Test>().configureEach {
    jvmArgs(robolectricOpens.map { "--add-opens=$it=ALL-UNNAMED" })
    systemProperty("ezpz.contracts", contractsDir.asFile.absolutePath)
    inputs.dir(contractsDir).withPropertyName("contractFixtures")
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
