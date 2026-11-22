// JSON as the web keeps it. The documents EZ/PZ shares (an LZ/PZ, a route set, a mission pack's items, a brief) are written by the
// web and edited on every client, and three things about them must survive whole, which Foundation's JSONSerialization and Codable
// lose (docs/IOS_PLAN.md §6):
//
// - the order of an object's keys, which is JavaScript's (array indexes first, then the order they were added): a pack's diff walks
//   an object in that order and the fixtures pin the operations it makes;
// - a number's literal text (`2.50` stays `2.50`; a whole number past 2^63 is not rounded), as kotlinx's JsonElement keeps it;
// - null apart from absent: the pack engine sends `"after": null` and `{field: null}` on purpose.
//
// Objects and lists are immutable final classes, as kotlinx's are, so a value nobody changed keeps its instance and `===` tells
// it apart from a new one. The stores and the pack engine tell what changed by instance, because an item can be megabytes.

/// A JSON value. Swift's `nil` stands for JavaScript's `undefined`, a key that is not there; `.null` is JSON's null.
public enum JSONTree: Sendable {
    case null
    case bool(Bool)
    case number(JSONNumber)
    case string(String)
    case array(JSONArray)
    case object(JSONObject)

    /// A number as JavaScript holds it. NaN and the infinities are kept (the web holds them in memory) and written as `null`, as
    /// `JSON.stringify` writes them.
    public static func number(_ value: Double) -> JSONTree { .number(JSONNumber(value)) }

    public static func array(_ elements: [JSONTree]) -> JSONTree { .array(JSONArray(elements)) }

    public static func object(_ entries: KeyValuePairs<String, JSONTree>) -> JSONTree {
        .object(JSONObject(entries.map { ($0.key, $0.value) }))
    }

    public var objectValue: JSONObject? { if case .object(let o) = self { o } else { nil } }
    public var arrayValue: JSONArray? { if case .array(let a) = self { a } else { nil } }
    public var stringValue: String? { if case .string(let s) = self { s } else { nil } }
    public var numberValue: JSONNumber? { if case .number(let n) = self { n } else { nil } }
    public var boolValue: Bool? { if case .bool(let b) = self { b } else { nil } }
    public var isNull: Bool { if case .null = self { true } else { false } }

    /// An object's field, or nil (`undefined`) when this is not an object or has no such key.
    public subscript(key: String) -> JSONTree? { objectValue?[key] }

    /// A list's element, or nil (`undefined`) when this is not a list or the index is outside it.
    public subscript(index: Int) -> JSONTree? { arrayValue?[safe: index] }
}

/// A JSON number: the text it was written with, and its value as a double (correctly rounded; past the largest double, infinity).
public struct JSONNumber: Sendable, Equatable, CustomStringConvertible {
    /// The literal text, as it came (`2.50`, `1E3`, `12345678901234567890`), or as JavaScript writes a number made here.
    public let text: String
    public let value: Double

    /// A number made from a double: its text is JavaScript's `String(value)`.
    public init(_ value: Double) {
        self.value = value
        self.text = JsNumber.toText(value)
    }

    /// A number with the literal text a parser read. `text` must be a JSON number.
    init(literal text: String) {
        self.text = text
        // JSON's number grammar is a subset of what Double(String) reads, which rounds correctly and gives infinity past the range.
        self.value = Double(text) ?? .nan
    }

    public var isFinite: Bool { value.isFinite }

    /// A NaN or an infinity made here (`JSONNumber(.nan)`), which JSON has no text for. A literal past the largest double (`1e400`)
    /// reads as infinity but keeps its text.
    var isMadeNonFinite: Bool { text == "NaN" || text == "Infinity" || text == "-Infinity" }

    /// Equal when the text is the same, as kotlinx compares literals: `1` and `1.0` are two literals of one number. Compare values
    /// with `Js.strictEquals`.
    public static func == (a: JSONNumber, b: JSONNumber) -> Bool { a.text == b.text }

    public var description: String { text }
}

/// A JSON list. Immutable: a change makes a new one, so an untouched list keeps its instance.
public final class JSONArray: Sendable, RandomAccessCollection {
    public let elements: [JSONTree]

    public init(_ elements: [JSONTree] = []) { self.elements = elements }

    public var startIndex: Int { 0 }
    public var endIndex: Int { elements.count }
    public subscript(position: Int) -> JSONTree { elements[position] }
    public subscript(safe index: Int) -> JSONTree? { elements.indices.contains(index) ? elements[index] : nil }
}

/// A JSON object, its keys in the order JavaScript enumerates them (`Object.keys`, `JSON.stringify`, a spread): the array indexes
/// first, smallest first, then the other keys in the order they were added. Kotlin's JsonObject keeps the order of its text and its
/// ports ask `Js.orderedKeys` wherever the order shows; here the object keeps JavaScript's order itself, so walking it is always right.
///
/// Keys are compared by their UTF-16 units, as JavaScript compares them: Swift's `==` on String is canonical equivalence, which
/// takes `"é"` and `"e\u{301}"` for one key. Immutable: a change makes a new object.
public final class JSONObject: Sendable, RandomAccessCollection {
    public typealias Entry = (key: String, value: JSONTree)

    public let entries: [Entry]
    private let index: [UTF16Key: Int]

    /// Entries in the order they were added. A key given twice keeps its first place and its last value, as `JSON.parse` and an
    /// assignment to an existing key do.
    public init(_ added: [(String, JSONTree)] = []) {
        var indexes: [(String, JSONTree)] = []
        var others: [(String, JSONTree)] = []
        var seen: [UTF16Key: (isIndex: Bool, at: Int)] = [:]
        for (key, value) in added {
            let k = UTF16Key(key)
            if let place = seen[k] {
                if place.isIndex { indexes[place.at].1 = value } else { others[place.at].1 = value }
            } else if Js.isArrayIndex(key) {
                seen[k] = (true, indexes.count)
                indexes.append((key, value))
            } else {
                seen[k] = (false, others.count)
                others.append((key, value))
            }
        }
        indexes.sort { UInt64($0.0)! < UInt64($1.0)! }
        let all = indexes + others
        entries = all.map { (key: $0.0, value: $0.1) }
        var lookup: [UTF16Key: Int] = [:]
        lookup.reserveCapacity(all.count)
        for (i, entry) in all.enumerated() { lookup[UTF16Key(entry.0)] = i }
        index = lookup
    }

    public var startIndex: Int { 0 }
    public var endIndex: Int { entries.count }
    public subscript(position: Int) -> Entry { entries[position] }

    /// The value at `key`, or nil (`undefined`) when the object has no such key. A key whose value is JSON null gives `.null`.
    public subscript(key: String) -> JSONTree? {
        index[UTF16Key(key)].map { entries[$0].value }
    }

    public func has(_ key: String) -> Bool { index[UTF16Key(key)] != nil }

    /// The keys, in JavaScript's order.
    public var keys: [String] { entries.map(\.key) }

    /// A new object with `key` set: in its place if it was there, else added as JavaScript adds it.
    public func setting(_ key: String, _ value: JSONTree) -> JSONObject {
        JSONObject(entries.map { ($0.key, $0.value) } + [(key, value)])
    }

    /// A new object without `key` (this one, when it has no such key).
    public func removing(_ key: String) -> JSONObject {
        guard has(key) else { return self }
        let k = UTF16Key(key)
        return JSONObject(entries.filter { UTF16Key($0.key) != k }.map { ($0.key, $0.value) })
    }
}

/// A String compared and hashed by its UTF-16 units, as JavaScript compares text.
public struct UTF16Key: Hashable, Sendable {
    public let string: String

    public init(_ string: String) { self.string = string }

    public static func == (a: UTF16Key, b: UTF16Key) -> Bool { a.string.utf16.elementsEqual(b.string.utf16) }

    public func hash(into hasher: inout Hasher) {
        for unit in string.utf16 { hasher.combine(unit) }
        hasher.combine(string.utf16.count)
    }
}

extension JSONTree: Equatable {
    /// Structural equality, as kotlinx's: an object's keys in any order, text by its UTF-16 units, a number by its literal text
    /// (`1` is not `1.0`). JavaScript's `===` is `Js.strictEquals`.
    public static func == (a: JSONTree, b: JSONTree) -> Bool {
        switch (a, b) {
        case (.null, .null): return true
        case (.bool(let x), .bool(let y)): return x == y
        case (.number(let x), .number(let y)): return x == y
        case (.string(let x), .string(let y)): return x.utf16.elementsEqual(y.utf16)
        case (.array(let x), .array(let y)): return x === y || x.elements == y.elements
        case (.object(let x), .object(let y)):
            if x === y { return true }
            guard x.count == y.count else { return false }
            return x.entries.allSatisfy { entry in y[entry.key] == entry.value }
        default: return false
        }
    }
}
