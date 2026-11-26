// swift-tools-version: 6.2
// The iOS app's screens (docs/IOS_PLAN.md §5): SwiftUI, UIKit and MapLibre over EZPZCore's pure modules. iOS only. A feature never
// imports another feature; the app joins them.

import PackageDescription

let package = Package(
    name: "EZPZFeatures",
    platforms: [.iOS("26.0")],
    products: [
        .library(name: "DesignSystem", targets: ["DesignSystem"]),
        .library(name: "FeatureMap", targets: ["FeatureMap"]),
    ],
    dependencies: [
        .package(path: "../EZPZCore"),
        // MapLibre Native for iOS, on the plan's owner-approved list (docs/IOS_PLAN.md §4): the SDK as a binary xcframework, one exact
        // version, as Android pins MapLibre Android.
        .package(url: "https://github.com/maplibre/maplibre-gl-native-distribution", exact: "7.0.0"),
    ],
    targets: [
        // The palettes as SwiftUI colours, the Liquid Glass controls and the parts the panels repeat.
        .target(name: "DesignSystem", dependencies: [.product(name: "DesignTokens", package: "EZPZCore")]),
        // The 2D map: MapLibre, the base maps, the crosshair and its readout, the search, GPS, the MGRS grid, the long-press menu.
        .target(
            name: "FeatureMap",
            dependencies: [
                "DesignSystem",
                .product(name: "MapLibre", package: "maplibre-gl-native-distribution"),
                .product(name: "CoreMap", package: "EZPZCore"),
                .product(name: "CoreGeo", package: "EZPZCore"),
                .product(name: "CoreModel", package: "EZPZCore"),
                .product(name: "CoreJS", package: "EZPZCore"),
            ]),
    ]
)
