import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// JSON from text, for a test's own inputs.
func json(_ text: String) -> JSONTree {
    do { return try JSONTree.parse(text) } catch { fatalError("\(error) in \(text)") }
}

func object(_ text: String) -> JSONObject { json(text).objectValue! }

/// A fixture's list of cases under `key`, each an object.
func cases(_ fixture: JSONObject, _ key: String) -> [JSONObject] { fixture[key]!.arrayValue!.elements.map { $0.objectValue! } }

func name(_ c: JSONObject) -> String { c["name"]?.stringValue ?? "?" }

/// The first ten places `actual` differs from `expected` (absent and null alike), recorded against `label`.
func check(_ label: String, _ expected: JSONTree, _ actual: JSONTree, sourceLocation: SourceLocation = #_sourceLocation) {
    let differences = Array(JSONCompare.differences(expected, actual).prefix(10))
    #expect(differences == [], "\(label)", sourceLocation: sourceLocation)
}

/// A model type as kotlinx's `Json { encodeDefaults = true }` writes it: every field, nulls included.
func encoded<T: ModelJSONEncodable>(_ value: T) -> JSONTree { ModelJSON().encode(value) }

/// The text of a primitive, as kotlinx's `content` gives it; nil for null, a list or an object.
func content(_ value: JSONTree?) -> String? {
    guard let value, !value.isNull else { return nil }
    return ModelJSON.content(value)
}
