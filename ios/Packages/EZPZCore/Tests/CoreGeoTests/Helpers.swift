import CoreJS

// Reading the fixtures' JSON: a missing or mistyped field is a broken fixture, so these trap rather than answer nothing.
extension JSONTree {
    var double: Double { numberValue!.value }
    var string: String { stringValue! }
    var array: JSONArray { arrayValue! }
    var object: JSONObject { objectValue! }
    var int: Int { Int(numberValue!.value) }
    var bool: Bool { boolValue! }
    /// A field's text, nil for JSON null.
    var optionalString: String? { isNull ? nil : stringValue! }
}

extension JSONObject {
    func cases(_ key: String) -> [JSONObject] { self[key]!.array.map(\.object) }
}
