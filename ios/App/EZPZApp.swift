import CoreJS
import SwiftUI

/// The composition root (docs/IOS_PLAN.md §5). In I0 it only shows that the app builds and links the pure modules; the frames, the
/// map and the stores join it from I2 and I3.
@main
struct EZPZApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("EZ/PZ")
                .font(.largeTitle.bold())
            Text("Version \(AppInfo.version) (\(AppInfo.build))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("API \(AppInfo.apiMajors.map { JsNumber.toText(Double($0)) }.joined(separator: ", "))")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("Not ready for use: the foundations only.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 8)
        }
        .padding()
    }
}

/// What this build is, from its Info.plist, which Config/Version.xcconfig fills.
enum AppInfo {
    static var version: String { info("CFBundleShortVersionString") ?? "0.0.0" }
    static var build: String { info("CFBundleVersion") ?? "0" }

    /// The API majors this build talks, `EZPZ_API_MAJORS`.
    static var apiMajors: [Int] {
        (info("EZPZApiMajors") ?? "").split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Int($0) }
    }

    private static func info(_ key: String) -> String? {
        Bundle.main.object(forInfoDictionaryKey: key) as? String
    }
}
