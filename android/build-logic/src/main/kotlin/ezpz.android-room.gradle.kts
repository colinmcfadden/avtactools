import com.google.devtools.ksp.gradle.KspExtension

// Room, compiled by KSP. The schema of each version is exported next to the module and committed: it is what a
// migration test reads, and the record of what an installed app's database looks like.

plugins {
    id("com.google.devtools.ksp")
}

val libs = versionCatalogs.named("libs")

extensions.configure<KspExtension> {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
    arg("room.generateKotlin", "true")
}

dependencies {
    "implementation"(libs.findLibrary("room-runtime").get())
    "implementation"(libs.findLibrary("room-ktx").get())
    "ksp"(libs.findLibrary("room-compiler").get())
    "testImplementation"(libs.findLibrary("room-testing").get())
}
