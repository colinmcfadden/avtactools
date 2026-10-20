plugins {
    id("ezpz.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

// The mission brief (docs/MISSION_BRIEF.md): the formats as data, compileBrief (everything a brief says) and the light data, ported
// from the web's feature/brief and held to contracts/fixtures/brief. No Android code: the setup, the PDF's review and the sandtable
// (feature-brief) only lay out what this decides.
dependencies {
    // Diagrams, routes and their plans are read with the ports the planner already has; the model is JSON in and out.
    api(project(":core-model"))
    api(project(":core-geo"))
    api(project(":core-planning"))
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core-testing"))
}

// The formats are contracts/briefs, bundled unchanged as resources (the web bundles byte-for-byte copies; here the build copies them, and a
// test holds what is on the classpath to the files).
val bundleBriefFormats = tasks.register<Sync>("bundleBriefFormats") {
    from(rootProject.layout.projectDirectory.dir("../contracts/briefs")) {
        include("*.json")
        into("app/ezpztac/brief/formats")
    }
    into(layout.buildDirectory.dir("generated/briefFormats"))
}
sourceSets.named("main") { resources.srcDir(bundleBriefFormats) }

// The tests read contracts/briefs too (that the bundled files are those bytes, and the format's rules).
tasks.test {
    systemProperty("ezpz.briefs", rootProject.layout.projectDirectory.dir("../contracts/briefs").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("../contracts/briefs")).withPropertyName("briefFormats")
}
