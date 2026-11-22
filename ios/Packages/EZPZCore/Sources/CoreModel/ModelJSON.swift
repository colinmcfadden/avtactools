import CoreJS

/// How the model types are read from JSON and written to it: the rules kotlinx.serialization applies to the Android port's
/// `@Serializable` classes, written out by hand, because what a route set keeps and refuses is decided by them (a route the reader
/// refuses is kept apart, as it came, and written back after the others) and Codable decides otherwise.
///
/// The rules, as kotlinx's tree decoder has them (`TreeJsonDecoder.kt`):
/// - a property with no default must be there, and a null for one that cannot be null is refused;
/// - a property with a default takes it when absent, and, with `coerceInputValues`, when it is null and cannot be;
/// - a number is read from its text with Java's grammar, so `"34.5"` quoted is 34.5 too; NaN and the infinities are refused;
/// - text must be quoted text (a number for a name is refused); a flag is `true` or `false`, quoted or not;
/// - a key the type does not have is refused unless `ignoreUnknownKeys`.
///
/// Writing, every property is written (kotlinx's `encodeDefaults`), in the order the type declares them, and an absent optional as
/// null, or not at all without `explicitNulls`. A number is written as JavaScript writes it (`34`, where Kotlin wrote `34.0`: the
/// same number to every reader, and the web's own text).
public struct ModelJSON: Sendable {
    public var ignoreUnknownKeys: Bool
    public var coerceInputValues: Bool
    public var explicitNulls: Bool

    /// kotlinx's defaults: unknown keys refused, nulls not coerced, absent values written as null.
    public init(ignoreUnknownKeys: Bool = false, coerceInputValues: Bool = false, explicitNulls: Bool = true) {
        self.ignoreUnknownKeys = ignoreUnknownKeys
        self.coerceInputValues = coerceInputValues
        self.explicitNulls = explicitNulls
    }

    /// What `RouteSets` reads and writes a saved route set with: tolerant on the way in, nothing absent written as null.
    public static let routeSets = ModelJSON(ignoreUnknownKeys: true, coerceInputValues: true, explicitNulls: false)

    // MARK: Whole values

    public func decode<T: ModelJSONDecodable>(_ type: T.Type, from value: JSONTree) throws(ModelJSONError) -> T {
        guard case .object(let object) = value else { throw ModelJSONError("Expected an object for \(T.self), got \(kind(value))") }
        if !ignoreUnknownKeys {
            let known = Set(T.serialNames.map(UTF16Key.init))
            if let unknown = object.keys.first(where: { !known.contains(UTF16Key($0)) }) {
                throw ModelJSONError("Encountered an unknown key '\(unknown)' in \(T.self)")
            }
        }
        return try T(from: ObjectReader(object: object, json: self))
    }

    public func decodeList<T: ModelJSONDecodable>(_ type: T.Type, from value: JSONTree) throws(ModelJSONError) -> [T] {
        try list(value) { (element) throws(ModelJSONError) in try decode(T.self, from: element) }
    }

    public func encode<T: ModelJSONEncodable>(_ value: T) -> JSONTree {
        var writer = ObjectWriter(json: self)
        value.encode(to: &writer)
        return .object(JSONObject(writer.entries))
    }

    public func encodeObject<T: ModelJSONEncodable>(_ value: T) -> JSONObject {
        var writer = ObjectWriter(json: self)
        value.encode(to: &writer)
        return JSONObject(writer.entries)
    }

    // MARK: Values, as kotlinx's tree decoder reads them

    /// A primitive's text (`JsonPrimitive.content`): a number's literal, text as it is, `true`, `null`. Nil for a list or an object.
    static func content(_ value: JSONTree) -> String? {
        switch value {
        case .null: "null"
        case .bool(let b): b ? "true" : "false"
        case .number(let n): n.text
        case .string(let s): s
        case .array, .object: nil
        }
    }

    /// A double: any primitive whose text Java reads as a finite number (quoted text included).
    public func double(_ value: JSONTree) throws(ModelJSONError) -> Double {
        guard let text = Self.content(value) else { throw ModelJSONError("Expected a number, got \(kind(value))") }
        guard let number = Kotlin.toDoubleOrNull(text) else { throw ModelJSONError("Failed to parse literal '\(text)' as a double") }
        guard number.isFinite else { throw ModelJSONError("Unexpected special floating-point value \(text)") }
        return number
    }

    /// Text: quoted text only.
    public func string(_ value: JSONTree) throws(ModelJSONError) -> String {
        guard case .string(let s) = value else { throw ModelJSONError("Expected a string, got \(kind(value))") }
        return s
    }

    /// A flag: `true` or `false`, quoted or not.
    public func bool(_ value: JSONTree) throws(ModelJSONError) -> Bool {
        switch Self.content(value) {
        case "true": return true
        case "false": return false
        default: throw ModelJSONError("Failed to parse a boolean from \(kind(value))")
        }
    }

    /// A whole number: an optional minus, ASCII digits and an optional exponent, the value whole and within a Long (kotlinx's
    /// `consumeNumericLiteral`, without the quoting and white space it also allows).
    public func int64(_ value: JSONTree) throws(ModelJSONError) -> Int64 {
        guard let text = Self.content(value), let number = Self.wholeLiteral(text) else {
            throw ModelJSONError("Failed to parse a whole number from \(kind(value))")
        }
        return number
    }

    public func int(_ value: JSONTree) throws(ModelJSONError) -> Int {
        let number = try int64(value)
        guard let fitted = Int32(exactly: number) else { throw ModelJSONError("\(number) is not an Int") }
        return Int(fitted)
    }

    public func object<T: ModelJSONDecodable>(_ value: JSONTree) throws(ModelJSONError) -> T {
        try decode(T.self, from: value)
    }

    public func list<T>(_ value: JSONTree, _ element: (JSONTree) throws(ModelJSONError) -> T) throws(ModelJSONError) -> [T] {
        guard case .array(let array) = value else { throw ModelJSONError("Expected a list, got \(kind(value))") }
        var out: [T] = []
        out.reserveCapacity(array.count)
        for item in array.elements { out.append(try element(item)) }
        return out
    }

    public func map<T>(_ value: JSONTree, _ element: (JSONTree) throws(ModelJSONError) -> T) throws(ModelJSONError) -> OrderedMap<T> {
        guard case .object(let object) = value else { throw ModelJSONError("Expected a map, got \(kind(value))") }
        var out = OrderedMap<T>()
        for entry in object.entries { out[entry.key] = try element(entry.value) }
        return out
    }

    static func wholeLiteral(_ text: String) -> Int64? {
        let bytes = Array(text.utf8)
        var i = 0
        var negative = false
        if i < bytes.count, bytes[i] == 0x2D {
            negative = true
            i += 1
        }
        let start = i
        var mantissa: Int64 = 0
        while i < bytes.count, bytes[i] >= 0x30, bytes[i] <= 0x39 {
            let (times, o1) = mantissa.multipliedReportingOverflow(by: 10)
            let (sum, o2) = times.addingReportingOverflow(Int64(bytes[i] - 0x30))
            guard !o1, !o2 else { return nil }
            mantissa = sum
            i += 1
        }
        guard i > start else { return nil }
        var exponent = 0
        var positive = true
        if i < bytes.count, bytes[i] | 0x20 == 0x65 {
            i += 1
            if i < bytes.count, bytes[i] == 0x2B || bytes[i] == 0x2D {
                positive = bytes[i] == 0x2B
                i += 1
            }
            let expStart = i
            while i < bytes.count, bytes[i] >= 0x30, bytes[i] <= 0x39, exponent < 1000 {
                exponent = exponent * 10 + Int(bytes[i] - 0x30)
                i += 1
            }
            guard i > expStart else { return nil }
        }
        guard i == bytes.count else { return nil }
        var value = mantissa
        for _ in 0..<exponent {
            if positive {
                let (times, overflow) = value.multipliedReportingOverflow(by: 10)
                guard !overflow else { return nil }
                value = times
            } else {
                guard value % 10 == 0 else { return nil }
                value /= 10
            }
        }
        return negative ? -value : value
    }

    func kind(_ value: JSONTree) -> String {
        switch value {
        case .null: "null"
        case .bool: "a boolean"
        case .number(let n): "the number \(n.text)"
        case .string: "text"
        case .array: "a list"
        case .object: "an object"
        }
    }

    // MARK: Reading and writing an object's properties

    /// An object being read as a model type: each property asked for by its name and the rule kotlinx applies to it.
    public struct ObjectReader {
        public let object: JSONObject
        public let json: ModelJSON

        /// A property with no default: it must be there.
        public func required<T>(_ key: String, _ read: (JSONTree) throws(ModelJSONError) -> T) throws(ModelJSONError) -> T {
            guard let value = object[key] else { throw ModelJSONError("Field '\(key)' is required, but it was missing") }
            return try read(value)
        }

        /// A property that cannot be null, with a default: absent is the default, and so is null when nulls are coerced.
        public func optional<T>(
            _ key: String, default fallback: T, _ read: (JSONTree) throws(ModelJSONError) -> T
        ) throws(ModelJSONError) -> T {
            guard let value = object[key] else { return fallback }
            if value.isNull && json.coerceInputValues { return fallback }
            return try read(value)
        }

        /// A property that may be null, with a default: absent is the default, null is nil.
        public func nullable<T>(
            _ key: String, default fallback: T?, _ read: (JSONTree) throws(ModelJSONError) -> T
        ) throws(ModelJSONError) -> T? {
            guard let value = object[key] else { return fallback }
            if value.isNull { return nil }
            return try read(value)
        }

        /// A property that may be null and has no default: null is nil, and absent is nil too only without `explicitNulls`.
        public func requiredNullable<T>(_ key: String, _ read: (JSONTree) throws(ModelJSONError) -> T) throws(ModelJSONError) -> T? {
            guard let value = object[key] else {
                if json.explicitNulls { throw ModelJSONError("Field '\(key)' is required, but it was missing") }
                return nil
            }
            if value.isNull { return nil }
            return try read(value)
        }
    }

    /// An object being written from a model type, its properties in the order they are put.
    public struct ObjectWriter {
        public let json: ModelJSON
        public private(set) var entries: [(String, JSONTree)] = []

        init(json: ModelJSON) { self.json = json }

        public mutating func put(_ key: String, _ value: JSONTree) { entries.append((key, value)) }
        public mutating func put(_ key: String, _ value: String) { put(key, .string(value)) }
        public mutating func put(_ key: String, _ value: Double) { put(key, .number(value)) }
        public mutating func put(_ key: String, _ value: Bool) { put(key, .bool(value)) }
        public mutating func put(_ key: String, _ value: Int) { put(key, .number(Double(value))) }
        public mutating func put(_ key: String, _ value: Int64) { put(key, .number(JSONNumber(Double(value)))) }

        /// An optional value: nil is null, or left out without `explicitNulls`.
        public mutating func put(_ key: String, _ value: JSONTree?) {
            if let value { put(key, value) } else if json.explicitNulls { put(key, JSONTree.null) }
        }

        public mutating func put(_ key: String, _ value: String?) { put(key, value.map(JSONTree.string)) }
        public mutating func put(_ key: String, _ value: Double?) { put(key, value.map { JSONTree.number($0) }) }
        public mutating func put(_ key: String, _ value: Bool?) { put(key, value.map(JSONTree.bool)) }
        public mutating func put(_ key: String, _ value: Int?) { put(key, value.map { JSONTree.number(Double($0)) }) }
        public mutating func put(_ key: String, _ value: Int64?) { put(key, value.map { JSONTree.number(Double($0)) }) }

        public mutating func put<T: ModelJSONEncodable>(_ key: String, model value: T?) { put(key, value.map { json.encode($0) }) }

        public mutating func put<T: ModelJSONEncodable>(_ key: String, models values: [T]) {
            put(key, .array(values.map { json.encode($0) }))
        }

        public mutating func put(_ key: String, map values: OrderedMap<Double>) {
            put(key, .object(JSONObject(values.entries.map { ($0.key, .number($0.value)) })))
        }

        public mutating func put<T: ModelJSONEncodable>(_ key: String, models values: OrderedMap<T>) {
            put(key, .object(JSONObject(values.entries.map { ($0.key, json.encode($0.value)) })))
        }
    }
}

/// A model type read with `ModelJSON`.
public protocol ModelJSONDecodable {
    /// Its properties' names in JSON, as kotlinx's descriptor lists them: what a newer release's extra fields are told apart from.
    static var serialNames: [String] { get }
    init(from reader: ModelJSON.ObjectReader) throws(ModelJSONError)
}

/// A model type written with `ModelJSON`.
public protocol ModelJSONEncodable {
    func encode(to writer: inout ModelJSON.ObjectWriter)
}

public typealias ModelJSONCodable = ModelJSONDecodable & ModelJSONEncodable

/// Why a document could not be read as a model type (kotlinx's SerializationException).
public struct ModelJSONError: Error, Equatable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}
