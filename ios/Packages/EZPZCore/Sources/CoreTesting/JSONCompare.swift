import CoreJS

/// Structural comparison of JSON with a numeric tolerance, for holding a Swift result to a fixture the web produced (the Kotlin
/// port's JsonCompare).
///
/// An absent key and an explicit null are the same here: a JavaScript object leaves a key out where a Swift encoder may write null,
/// and neither is a disagreement about the plan. Where null and absent must be told apart (the pack engine), compare the trees with
/// `==` instead.
public enum JSONCompare {
    /// Every place `actual` differs from `expected`, as `path: reason`. Empty means they agree.
    public static func differences(
        _ expected: JSONTree,
        _ actual: JSONTree,
        tolerance: Double = 1e-9,
        path: String = "$"
    ) -> [String] {
        switch (expected, actual) {
        case (.object(let e), .object(let a)):
            let keys = Set(e.keys + a.keys).sorted()
            return keys.flatMap { key in
                differences(e[key] ?? .null, a[key] ?? .null, tolerance: tolerance, path: "\(path).\(key)")
            }
        case (.array(let e), .array(let a)):
            guard e.count == a.count else { return ["\(path): expected \(e.count) items, got \(a.count)"] }
            return e.indices.flatMap { differences(e[$0], a[$0], tolerance: tolerance, path: "\(path)[\($0)]") }
        case (.null, .null):
            return []
        case (.number(let e), .number(let a)):
            if e.value == a.value || abs(e.value - a.value) <= tolerance * max(1, abs(e.value)) { return [] }
            return ["\(path): expected \(e.text), got \(a.text)"]
        case (.string(let e), .string(let a)):
            return e.utf16.elementsEqual(a.utf16) ? [] : ["\(path): expected \(describe(expected)), got \(describe(actual))"]
        case (.bool(let e), .bool(let a)):
            return e == a ? [] : ["\(path): expected \(e), got \(a)"]
        default:
            return ["\(path): expected \(describe(expected)), got \(describe(actual))"]
        }
    }

    private static func describe(_ value: JSONTree) -> String {
        switch value {
        case .null: "null"
        case .bool(let b): String(b)
        case .number(let n): n.text
        case .string(let s): "\"\(s)\""
        case .array: "an array"
        case .object: "an object"
        }
    }
}
