import CoreJS
import Foundation

/// The golden fixtures in `contracts/fixtures`, the files the web, the backend and Android are tested against too. Found from this
/// file's own path, so `swift test` and Xcode find them without setup; `EZPZ_CONTRACTS` names another folder.
public enum Fixtures {
    public struct Missing: Error, CustomStringConvertible {
        public let path: String
        public var description: String { "Fixture missing at \(path)" }
    }

    /// The repository's root: this file is ios/Packages/EZPZCore/Sources/CoreTesting/Fixtures.swift.
    public static let repositoryRoot: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { url.deleteLastPathComponent() }
        return url
    }()

    public static let directory: URL = {
        if let path = ProcessInfo.processInfo.environment["EZPZ_CONTRACTS"], !path.isEmpty {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        return repositoryRoot.appending(path: "contracts/fixtures", directoryHint: .isDirectory)
    }()

    /// A fixture's bytes, by its path under contracts/fixtures (`formats/number_text.json`).
    public static func bytes(_ name: String) throws -> Data {
        let url = directory.appending(path: name)
        guard let data = FileManager.default.contents(atPath: url.path) else { throw Missing(path: url.path) }
        return data
    }

    public static func text(_ name: String) throws -> String {
        String(decoding: try bytes(name), as: UTF8.self)
    }

    /// A JSON fixture, read with CoreJS's own parser, so its key order, its numbers' text and its nulls are as written.
    public static func load(_ name: String) throws -> JSONObject {
        let tree = try JSONTree.parse(utf8: try bytes(name))
        guard case .object(let object) = tree else { throw Missing(path: name + " (not an object)") }
        return object
    }

    /// A file elsewhere in the repository, by its path from the root (the mission template the writer is built from lives with the
    /// web app).
    public static func repositoryBytes(_ relative: String) throws -> Data {
        let url = repositoryRoot.appending(path: relative)
        guard let data = FileManager.default.contents(atPath: url.path) else { throw Missing(path: url.path) }
        return data
    }

    /// Every JSON fixture's path under contracts/fixtures, sorted.
    public static func allJSON() -> [String] {
        let base = directory.standardizedFileURL.path
        guard let walker = FileManager.default.enumerator(atPath: base) else { return [] }
        return walker.compactMap { $0 as? String }.filter { $0.hasSuffix(".json") }.sorted()
    }
}
