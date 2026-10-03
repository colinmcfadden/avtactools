plugins {
    id("ezpz.android-library")
    id("ezpz.android-compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.ezpztac.symbols"
}

// MIL-STD-2525C symbols (docs/NATIVE_APPS_PLAN.md, "MIL-STD-2525C symbols"). The common ones ship pre-rendered by the web's milsymbol and
// are rasterised here; any other symbol is drawn by milsymbol itself in the system JavaScript sandbox, whose SVG is rasterised the same way.
dependencies {
    api(project(":core-model"))
    implementation(libs.androidsvg)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":core-testing"))
}

// The pre-rendered presets are contracts/fixtures/symbols: the web writes them (and a contract test keeps them current), so the app ships
// the web's own pictures. Only the pictures and their index go in, not the test data beside them.
val copyPresetSymbols = tasks.register<Copy>("copyPresetSymbols") {
    from(rootProject.layout.projectDirectory.dir("../contracts/fixtures/symbols")) {
        include("presets.json", "svg/*.svg")
    }
    into(layout.buildDirectory.dir("generated/presetAssets/symbols"))
}
extensions.getByType(com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/presetAssets").get().asFile.path)
}
tasks.matching { it.name.matches(Regex("(merge|generate|package|lint).*(Assets|Resources)")) || it.name.startsWith("process") }.configureEach {
    dependsOn(copyPresetSymbols)
}
