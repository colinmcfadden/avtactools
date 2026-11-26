import CoreJS
import DesignSystem
import FeatureMap
import SwiftUI

/// The composition root (docs/IOS_PLAN.md §5): the map and the frames over it. Sign-in, the documents and the panels' contents join
/// it from I4; for now the map and its frames are the app.
@main
struct EZPZApp: App {
    @State private var map = MapStore()
    @State private var display = DisplaySettings()

    var body: some Scene {
        WindowGroup {
            RootView(map: map, display: display)
                .appTheme(night: display.night)
                .task { await ServerConfig.load(into: map) }
        }
    }
}

/// Night mode, a device choice (Android's `DisplaySettings`), never saved with a plan or synced.
@MainActor @Observable
final class DisplaySettings {
    var night: Bool {
        didSet { UserDefaults.standard.set(night, forKey: "ezpz.night") }
    }

    init() { night = UserDefaults.standard.bool(forKey: "ezpz.night") }
}

/// What this build is, from its Info.plist, which Config/Version.xcconfig fills.
enum AppInfo {
    static var version: String { info("CFBundleShortVersionString") ?? "0.0.0" }
    static var build: String { info("CFBundleVersion") ?? "0" }

    /// The API majors this build talks, `EZPZ_API_MAJORS`.
    static var apiMajors: [Int] {
        (info("EZPZApiMajors") ?? "").split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Int($0) }
    }

    /// The server's root, `EZPZ_API_URL` (its own routes carry `/api`, as Android's `ezpz.apiUrl`).
    static var apiRoot: URL {
        info("EZPZApiURL").flatMap { URL(string: $0) } ?? URL(string: "https://prod-ezpz-api.mcfadd.in/")!
    }

    private static func info(_ key: String) -> String? {
        Bundle.main.object(forInfoDictionaryKey: key) as? String
    }
}
