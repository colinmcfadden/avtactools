// swift-tools-version: 6.2
// The pure modules of the iOS app (docs/IOS_PLAN.md §5): Foundation only, so they build for macOS too and their tests run with
// `swift test` on the Mac, held to the same contracts/fixtures as the web and Android.

import PackageDescription

let package = Package(
    name: "EZPZCore",
    platforms: [.iOS("26.0"), .macOS("26.0")],
    products: [
        .library(name: "CoreJS", targets: ["CoreJS"]),
        .library(name: "CoreModel", targets: ["CoreModel"]),
        .library(name: "CoreGeo", targets: ["CoreGeo"]),
        .library(name: "CorePlanning", targets: ["CorePlanning"]),
        .library(name: "CoreTesting", targets: ["CoreTesting"]),
    ],
    targets: [
        // What JavaScript does that every port has to do the same way: JSON as the web keeps it, its numbers' text, `Number()`,
        // `trim`, `Math.round`, `toFixed`, the order of an object's keys.
        .target(name: "CoreJS"),
        // Android's core-model: the domain types in the web's saved-JSON shape and what can be done to them.
        .target(name: "CoreModel", dependencies: ["CoreJS"]),
        // Android's core-geo: MGRS both ways, the grid over the map, reading what is typed as a place, sharing a grid.
        .target(name: "CoreGeo", dependencies: ["CoreModel", "CoreJS"]),
        // Android's core-planning: aircraft geometry, capacity, separation, the graphics' placement, routes planned, backstops.
        .target(name: "CorePlanning", dependencies: ["CoreModel", "CoreGeo", "CoreJS"]),
        // Reads contracts/fixtures and compares JSON with a tolerance. Test support only.
        .target(name: "CoreTesting", dependencies: ["CoreJS"]),
        .testTarget(name: "CoreJSTests", dependencies: ["CoreJS", "CoreTesting"]),
        .testTarget(name: "CoreModelTests", dependencies: ["CoreModel", "CoreJS", "CoreTesting"]),
        .testTarget(name: "CoreGeoTests", dependencies: ["CoreGeo", "CoreModel", "CoreJS", "CoreTesting"]),
        .testTarget(name: "CorePlanningTests", dependencies: ["CorePlanning", "CoreGeo", "CoreModel", "CoreJS", "CoreTesting"]),
    ],
    swiftLanguageModes: [.v6]
)
