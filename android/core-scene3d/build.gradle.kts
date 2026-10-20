plugins {
    id("ezpz.kotlin-library")
}

// The 3D LZ view's scene as plain data (docs/ANDROID_3D_PLAN.md): one east-north-up frame on the LZ, the LiDAR tilesets read and
// their tiles chosen as Cesium chooses them, the terrain's tiles and meshes, the routes' shapes and the compass. Everything that
// decides what the renderer draws is here, where it is tested; the OpenGL that draws it is feature-viewer3d's, and compile-only.
dependencies {
    // A tileset.json is read as a JSON tree: the format asks a reader to pass over what it does not know.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core-testing"))
}
