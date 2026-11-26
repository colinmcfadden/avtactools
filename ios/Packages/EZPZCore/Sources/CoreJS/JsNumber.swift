// JavaScript's numbers, as the web reads and writes them. The Kotlin port's JsNumber and JsValue, function by function; each
// differs from what Swift does by itself (docs/IOS_PLAN.md §6):
//
// - `String(n)` writes `1`, `100000000000000000000` and `1e-7`, where Swift's `description` writes `1.0`, `1e+20` and `1e-07`;
// - `toFixed` rounds the exact binary value (`1.005.toFixed(2)` is `1.00`), where `String(format:)` is locale-sensitive and rounds
//   otherwise;
// - `Number()` reads `"0x22"` as 34 and `[34.7]` as 34.7, where `Double("0x22")` is nil and `Double("0x1p3")` is 8;
// - `parseInt` and `parseFloat` read what they can from the start ("270°" is 270).
//
// Only ASCII digits are digits: Swift's `\d`, like Android's ICU, takes every script's, and JavaScript's does not.

public enum JsNumber {
    // MARK: Writing

    /// `String(value)` as JavaScript writes it (ECMAScript Number::toString): the shortest digits that read back as the same number,
    /// in plain form from 1e-6 up to (not including) 1e21 and as `1e+21` or `1e-7` outside it; `-0` is `0`.
    ///
    /// The digits are Swift's own shortest round-trip digits (`description`, which picks the closest of the shortest, as the
    /// ECMAScript algorithm does); only the layout is JavaScript's. Held to formats/number_text.json.
    public static func toText(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value > 0 ? "Infinity" : "-Infinity" }
        if value == 0 { return "0" }
        let (digits, n) = shortestDigits(abs(value))
        let k = digits.count
        let body: String
        if k <= n && n <= 21 {
            body = digits + String(repeating: "0", count: n - k)
        } else if 0 < n && n <= 21 {
            body = String(digits.prefix(n)) + "." + String(digits.dropFirst(n))
        } else if -6 < n && n <= 0 {
            body = "0." + String(repeating: "0", count: -n) + digits
        } else {
            let exponent = n - 1
            let mantissa = k == 1 ? digits : String(digits.prefix(1)) + "." + String(digits.dropFirst())
            body = mantissa + "e" + (exponent < 0 ? "-" : "+") + String(abs(exponent))
        }
        return value < 0 ? "-" + body : body
    }

    /// The shortest digits of a positive finite double, without leading or trailing zeros, and where the decimal point goes:
    /// the value is 0.`digits` × 10^`n`.
    static func shortestDigits(_ x: Double) -> (digits: String, n: Int) {
        // Swift writes "1.2345e-07", "0.001", "100.0", "1.2345678901234568e+20".
        let text = x.description
        let mantissa: Substring
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mantissa = text[..<e]
            exponent = Int(text[text.index(after: e)...])!
        } else {
            mantissa = Substring(text)
        }
        let point = mantissa.firstIndex(of: ".")
        let whole = point.map { mantissa[..<$0] } ?? mantissa
        let fraction = point.map { mantissa[mantissa.index(after: $0)...] } ?? ""
        var digits = Array(whole + fraction)
        var n = whole.count + exponent
        while digits.first == "0" {
            digits.removeFirst()
            n -= 1
        }
        while digits.last == "0" { digits.removeLast() }
        return (String(digits), n)
    }

    /// `value.toFixed(places)` as JavaScript writes it: the exact binary value rounded half up (so `1.005.toFixed(2)` is `1.00`), a
    /// sign kept on a negative that rounds to zero (`(-1e-7).toFixed(6)` is `-0.000000`) and none on `-0`, the words for NaN and the
    /// infinities, and from 1e21 the number's own text. `places` is 0 to 100, as JavaScript allows.
    public static func toFixed(_ value: Double, _ places: Int) -> String {
        precondition((0...100).contains(places), "toFixed takes 0 to 100 places")
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value > 0 ? "Infinity" : "-Infinity" }
        if abs(value) >= 1e21 { return toText(value) }
        let a = abs(value)
        // a = m × 2^e exactly.
        let m: UInt64
        let e: Int
        if a == 0 {
            m = 0
            e = 0
        } else if a.exponentBitPattern == 0 {
            m = a.significandBitPattern
            e = -1074
        } else {
            m = a.significandBitPattern | (1 << 52)
            e = Int(a.exponentBitPattern) - 1075
        }
        // n = round-half-up(a × 10^places), exactly.
        var scaled = BigUInt(m)
        scaled.multiply(byPowerOfTen: places)
        let n: BigUInt
        if e >= 0 {
            n = scaled.shiftedLeft(by: e)
        } else {
            var half = BigUInt(1).shiftedLeft(by: -e - 1)
            half.add(scaled)
            n = half.shiftedRight(by: -e)
        }
        var digits = n.decimalText
        if digits.count <= places { digits = String(repeating: "0", count: places + 1 - digits.count) + digits }
        let body = places == 0 ? digits : String(digits.dropLast(places)) + "." + String(digits.suffix(places))
        return value < 0 ? "-" + body : body
    }

    // MARK: Reading

    /// JavaScript's `Number(value)` for a JSON value, exactly (ECMAScript ToNumber). `nil` is `undefined`, which is NaN; null and
    /// false are 0 and true is 1; a list is read by its text, the elements joined by commas (so `[]` is 0, `[90]` is 90 and `[1, 2]`
    /// is NaN), and an object is NaN. Text is trimmed as JavaScript trims, then blank text is 0, decimal text its number, `Infinity`
    /// with or without a sign infinity, and `0x`, `0o` and `0b` text (no sign) a whole number in that base; anything else is NaN.
    public static func of(_ value: JSONTree?) -> Double {
        switch value {
        case nil: return .nan
        case .null: return 0
        case .bool(let b): return b ? 1 : 0
        case .number(let n): return n.value
        case .string(let s): return parse(s)
        case .array(let a): return parse(joined(a))
        case .object: return .nan
        }
    }

    /// `String(list)`: the elements joined by commas, null as nothing, a list within by its own text.
    static func joined(_ list: JSONArray) -> String {
        list.elements.map { element -> String in
            switch element {
            case .null: return ""
            case .array(let inner): return joined(inner)
            case .object: return "[object Object]"
            case .bool(let b): return b ? "true" : "false"
            case .string(let s): return s
            case .number(let n): return toText(n.value)
            }
        }.joined(separator: ",")
    }

    /// ECMAScript StringToNumber: `text` trimmed as JavaScript trims, then read whole or not at all.
    public static func parse(_ text: String) -> Double {
        let trimmed = Array(Js.trim(text).unicodeScalars)
        if trimmed.isEmpty { return 0 }
        if let end = decimalPrefix(trimmed, from: 0), end == trimmed.count {
            return decimalValue(trimmed[0..<end])
        }
        // 0x, 0o, 0b and their capitals, then at least one digit of that base; no sign.
        guard trimmed.count > 2, trimmed[0] == "0" else { return .nan }
        let radix: UInt32
        switch trimmed[1] {
        case "x", "X": radix = 16
        case "o", "O": radix = 8
        case "b", "B": radix = 2
        default: return .nan
        }
        guard let number = BigUInt(digits: trimmed[2...], radix: radix) else { return .nan }
        return number.nearestDouble
    }

    /// `parseInt(text)`: skips JavaScript's leading white space, takes a sign, reads a `0x` prefix as hexadecimal, and reads the digits
    /// it can, ignoring what follows ("270°" is 270, "12.7" is 12). NaN when there are none. A very long run of digits is rounded to
    /// the nearest double, as the engines do.
    public static func parseInt(_ text: String) -> Double {
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count, Js.isSpace(scalars[i]) { i += 1 }
        var negative = false
        if i < scalars.count, scalars[i] == "+" || scalars[i] == "-" {
            negative = scalars[i] == "-"
            i += 1
        }
        var radix: UInt32 = 10
        if i + 1 < scalars.count, scalars[i] == "0", scalars[i + 1] == "x" || scalars[i + 1] == "X" {
            radix = 16
            i += 2
        }
        let start = i
        while i < scalars.count, let d = BigUInt.digit(scalars[i]), d < radix { i += 1 }
        guard i > start, let magnitude = BigUInt(digits: scalars[start..<i], radix: radix)?.nearestDouble else { return .nan }
        return negative ? -magnitude : magnitude
    }

    /// `parseFloat(text)`: skips JavaScript's leading white space and reads the longest decimal number at the start ("3.13km" is 3.13,
    /// "1e" is 1, ".5" is 0.5, "-Infinity" is minus infinity); NaN when there is none. A hexadecimal prefix is not read ("0x1F" is 0).
    public static func parseFloat(_ text: String) -> Double {
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count, Js.isSpace(scalars[i]) { i += 1 }
        guard let end = decimalPrefix(scalars, from: i) else { return .nan }
        return decimalValue(scalars[i..<end])
    }

    /// Where the longest StrDecimalLiteral starting at `from` ends: `[+-]?(Infinity|[0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?`,
    /// the exponent only when it has digits. Nil when there is none.
    static func decimalPrefix(_ s: [Unicode.Scalar], from: Int) -> Int? {
        func isDigit(_ i: Int) -> Bool { i < s.count && s[i].value >= 0x30 && s[i].value <= 0x39 }
        var i = from
        if i < s.count, s[i] == "+" || s[i] == "-" { i += 1 }
        let infinity: [Unicode.Scalar] = Array("Infinity".unicodeScalars)
        if i + infinity.count <= s.count, Array(s[i..<(i + infinity.count)]) == infinity { return i + infinity.count }
        let digitsStart = i
        while isDigit(i) { i += 1 }
        var any = i > digitsStart
        if i < s.count, s[i] == "." {
            let afterPoint = i + 1
            var j = afterPoint
            while isDigit(j) { j += 1 }
            if any || j > afterPoint {
                any = true
                i = j
            }
        }
        guard any else { return nil }
        if i < s.count, s[i] == "e" || s[i] == "E" {
            var j = i + 1
            if j < s.count, s[j] == "+" || s[j] == "-" { j += 1 }
            let expStart = j
            while isDigit(j) { j += 1 }
            if j > expStart { i = j }
        }
        return i
    }

    /// The value of text `decimalPrefix` accepted whole.
    static func decimalValue(_ s: ArraySlice<Unicode.Scalar>) -> Double {
        var text = String(String.UnicodeScalarView(s))
        let negative = text.hasPrefix("-")
        if text.hasPrefix("+") || text.hasPrefix("-") { text.removeFirst() }
        let magnitude: Double = text == "Infinity" ? .infinity : (Double(text) ?? .nan)
        return negative ? -magnitude : magnitude
    }
}
