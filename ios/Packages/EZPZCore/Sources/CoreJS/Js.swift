// What JavaScript does that the ports have to do the same way: the web is the reference and JavaScript wrote every fixture, so
// where Swift's own answer differs (whether `1` is `1.0`, what `trim` takes, how `Math.round` rounds a half, how long text is)
// the port asks here. The Kotlin port's `Js` (core-missionpacks) and JsValue (core-model), function by function.
//
// Swift's `nil` is JavaScript's `undefined`, a key that is not there; `.null` is JSON's null.

public enum Js {
    // MARK: Keys

    private static let maxArrayIndex: UInt64 = 4_294_967_294  // ECMAScript's largest array index, 2^32 - 2

    /// Whether `key` is an array index: the canonical text of a whole number below 2^32 - 1 (so not `01`, `-0` or `1.0`).
    public static func isArrayIndex(_ key: String) -> Bool {
        let units = key.utf8
        guard !units.isEmpty, units.count <= 10, units.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return false }
        if units.count > 1, units.first == 0x30 { return false }
        return UInt64(key)! <= maxArrayIndex
    }

    /// `keys` in the order JavaScript enumerates an object's own keys: the array indexes first, smallest first, then the rest in the
    /// order given. A JSONObject is already in this order; this is for keys from elsewhere.
    public static func orderedKeys(_ keys: some Collection<String>) -> [String] {
        let indexes = keys.filter(isArrayIndex)
        if indexes.isEmpty { return Array(keys) }
        return indexes.sorted { UInt64($0)! < UInt64($1)! } + keys.filter { !isArrayIndex($0) }
    }

    // MARK: Types and truth

    /// The number `value` is if JavaScript's `typeof` would say `number`, else nil.
    public static func numberOf(_ value: JSONTree?) -> Double? {
        if case .number(let n)? = value { n.value } else { nil }
    }

    public static func isString(_ value: JSONTree?) -> Bool {
        if case .string? = value { true } else { false }
    }

    /// Whether JavaScript takes `value` as true (`if (value)`, `value || other`): everything but a missing key, null, false, 0, -0,
    /// NaN and "". An empty object or list is true.
    public static func truthy(_ value: JSONTree?) -> Bool {
        switch value {
        case nil, .null?: false
        case .bool(let b)?: b
        case .number(let n)?: n.value != 0 && !n.value.isNaN
        case .string(let s)?: !s.isEmpty
        case .array?, .object?: true
        }
    }

    /// An element's id: text or a finite number. The number 1 is not the text "1".
    public static func isId(_ value: JSONTree?) -> Bool {
        isString(value) || numberOf(value)?.isFinite == true
    }

    // MARK: Equality

    /// JavaScript's `===` on JSON. Numbers by value, so `1` is `1.0` and `-0` is `0` (and NaN is not even itself); text by its UTF-16
    /// units, and never a number, so `"1"` is not `1`; an object or a list only ever itself, as JavaScript compares them by reference.
    public static func strictEquals(_ a: JSONTree?, _ b: JSONTree?) -> Bool {
        switch (a, b) {
        case (nil, nil): true
        case (.null?, .null?): true
        case (.bool(let x)?, .bool(let y)?): x == y
        case (.number(let x)?, .number(let y)?): x.value == y.value
        case (.string(let x)?, .string(let y)?): x.utf16.elementsEqual(y.utf16)
        case (.array(let x)?, .array(let y)?): x === y
        case (.object(let x)?, .object(let y)?): x === y
        default: false
        }
    }

    /// `a === b` between two versions of a document: `strictEquals` on values, but an object or a list is never the same as anything
    /// (the web compares versions it built apart, so they share no object; here they may share an instance no edit touched).
    public static func sameValue(_ a: JSONTree?, _ b: JSONTree?) -> Bool {
        switch (a, b) {
        case (.array?, _), (.object?, _), (_, .array?), (_, .object?): false
        default: strictEquals(a, b)
        }
    }

    /// A key that tells ids apart the way `strictEquals` does, for a dictionary or a set of them: the web's `` `${typeof id}:${id}` ``,
    /// so `"1"` and `1` differ and `1.0` and `1` do not. Nil for anything that is not an id. A `UTF16Key`, never a bare String: a
    /// String key would take `"é"` and `"e\u{301}"` for one id.
    public static func idKey(_ id: JSONTree) -> UTF16Key? {
        switch id {
        case .string(let s): UTF16Key("string:" + s)
        case .number(let n) where n.isFinite: UTF16Key("number:" + JsNumber.toText(n.value))
        default: nil
        }
    }

    // MARK: Values as JavaScript reads them

    /// `Math.round`: a half goes up, toward positive infinity, so 2.5 is 3 and -2.5 is -2 (Swift's `rounded()` takes a half away from
    /// zero, so -2.5 would be -3). `-0.4` is `-0`, as JavaScript's is.
    public static func round(_ value: Double) -> Double {
        guard value.isFinite else { return value }
        let floor = value.rounded(.down)
        let result = value - floor >= 0.5 ? floor + 1 : floor
        // JavaScript keeps the sign of a value that rounds to zero from below (-0.4 is -0).
        return result == 0 && value.sign == .minus ? -0.0 : result
    }

    /// `String(value)`, as a template literal writes a value: text as it is, a number as JavaScript writes it, `null`, `true`, a list's
    /// elements joined by commas (null as nothing), `[object Object]`, and `undefined` for a key that is not there.
    public static func text(_ value: JSONTree?) -> String {
        switch value {
        case nil: "undefined"
        case .null?: "null"
        case .bool(let b)?: b ? "true" : "false"
        case .number(let n)?: JsNumber.toText(n.value)
        case .string(let s)?: s
        case .array(let a)?: JsNumber.joined(a)
        case .object?: "[object Object]"
        }
    }

    /// `Number(value)` (`JsNumber.of`).
    public static func toNumber(_ value: JSONTree?) -> Double { JsNumber.of(value) }

    // MARK: Text

    /// What `String.prototype.trim` takes from each end, and `parseInt` and `parseFloat` skip: JavaScript's white space and line
    /// terminators. The no-break spaces, U+3000 and the byte order mark are among them; a zero-width space is not, and nor are U+001C
    /// to U+001F or U+0085, which other trims take.
    public static func isSpace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF: true
        case 0x2000...0x200A: true
        default: false
        }
    }

    /// `text.trim()` as JavaScript trims. Swift's `whitespacesAndNewlines` is neither JavaScript's set nor Kotlin's.
    public static func trim(_ text: String) -> String {
        let scalars = text.unicodeScalars
        guard let first = scalars.firstIndex(where: { !isSpace($0) }) else { return "" }
        let last = scalars.lastIndex(where: { !isSpace($0) })!
        return String(scalars[first...last])
    }

    /// The text's length as JavaScript counts it (`.length`): UTF-16 units, not Swift's characters.
    public static func length(_ text: String) -> Int { text.utf16.count }

    /// `text` cut to `max` UTF-16 units, as the web's `.slice(0, max)` counts, except that a character is never split: where the cut
    /// would fall between the two halves of a character outside the basic plane (an emoji), the whole character goes. The web keeps
    /// its first half, a lone surrogate the server cannot store (AGENTS.md §15); the fixtures mark those cases `webBug`.
    public static func cut(_ text: String, _ max: Int) -> String {
        let units = Array(text.utf16)
        guard units.count > max else { return text }
        var end = Swift.max(max, 0)
        if end > 0, UTF16.isLeadSurrogate(units[end - 1]), UTF16.isTrailSurrogate(units[end]) { end -= 1 }
        return String(decoding: units[0..<end], as: UTF16.self)
    }

    // MARK: Reading a field

    /// `value?.[key]` for JSON: an object's own field (never one from JavaScript's prototype), a list's element at an index or its
    /// `length`, a text's UTF-16 unit at an index or its `length`; nil (`undefined`) for anything else.
    public static func prop(_ value: JSONTree?, _ key: String) -> JSONTree? {
        switch value {
        case .object(let o)?:
            return o[key]
        case .array(let a)?:
            if key == "length" { return .number(Double(a.count)) }
            if isArrayIndex(key), let i = Int(key), i < a.count { return a[i] }
            return nil
        case .string(let s)?:
            let units = Array(s.utf16)
            if key == "length" { return .number(Double(units.count)) }
            if isArrayIndex(key), let i = Int(key), i < units.count { return .string(String(decoding: [units[i]], as: UTF16.self)) }
            return nil
        default:
            return nil
        }
    }

    /// `Object.keys(value)`: an object's keys in JavaScript's order, a list's or a text's indexes; none for anything else.
    public static func keys(_ value: JSONTree?) -> [String] {
        switch value {
        case .object(let o)?: o.keys
        case .array(let a)?: a.indices.map(String.init)
        case .string(let s)?: (0..<s.utf16.count).map(String.init)
        default: []
        }
    }

    /// `Object.values(value)`, in `keys`' order.
    public static func values(_ value: JSONTree?) -> [JSONTree] {
        keys(value).compactMap { prop(value, $0) }
    }
}
