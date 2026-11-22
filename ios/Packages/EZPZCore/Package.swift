// swift-tools-version: 6.2
// The pure modules of the iOS app (docs/IOS_PLAN.md §5): Foundation only, so they build for macOS too and their tests run with
// `swift test` on the Mac, held to the same contracts/fixtures as the web and Android.

import PackageDescription

let package = Package(
    name: "EZPZCore",
    platforms: [.iOS("26.0"), .macOS("26.0")],
    products: [
        .library(name: "CoreJS", targets: ["CoreJS"]),
        .library(name: "CoreTesting", targets: ["CoreTesting"]),
    ],
    targets: [
        // What JavaScript does that every port has to do the same way: JSON as the web keeps it, its numbers' text, `Number()`,
        // `trim`, `Math.round`, `toFixed`, the order of an object's keys.
        .target(name: "CoreJS"),
        // Reads contracts/fixtures and compares JSON with a tolerance. Test support only.
        .target(name: "CoreTesting", dependencies: ["CoreJS"]),
        .testTarget(name: "CoreJSTests", dependencies: ["CoreJS", "CoreTesting"]),
    ],
    swiftLanguageModes: [.v6]
)
