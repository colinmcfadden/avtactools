import CoreJS

// Reading the fixtures' and the style documents' JSON: a missing or mistyped field is a broken test, so these trap rather than
// answer nothing.
extension JSONTree {
    var double: Double { numberValue!.value }
    var string: String { stringValue! }
    var array: JSONArray { arrayValue! }
    var object: JSONObject { objectValue! }
    var int: Int { Int(numberValue!.value) }
    var bool: Bool { boolValue! }
}

func parsed(_ text: String) -> JSONObject { try! JSONTree.parse(text).objectValue! }

func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool { abs(a - b) <= tolerance }
