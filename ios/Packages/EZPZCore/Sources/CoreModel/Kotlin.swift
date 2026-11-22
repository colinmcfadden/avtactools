import CoreJS

// What Kotlin on the JVM does that the Android port leans on and Swift does otherwise: `trim()` and `isBlank()` (Kotlin's white
// space, which is neither JavaScript's nor Swift's), `String.toDouble()` (Java's grammar: a `d` suffix, hexadecimal with a binary
// exponent, spaces round it), `toLongOrNull()` (any script's decimal digits), and text cut, padded and split by UTF-16 units, as a
// Kotlin String counts. Where the Kotlin port means JavaScript's rule it uses CoreJS instead; these are for where it does not.

enum Kotlin {
    // MARK: White space

    /// `Char.isWhitespace()`: Java's white space (the C0 separators U+001C to U+001F among it) or a Unicode space separator (the
    /// no-break spaces among them). Not the byte order mark, not U+0085.
    static func isWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
        default: false
        }
    }

    /// `text.trim()` as Kotlin trims.
    static func trim(_ text: String) -> String {
        let scalars = text.unicodeScalars
        guard let first = scalars.firstIndex(where: { !isWhitespace($0) }) else { return "" }
        let last = scalars.lastIndex(where: { !isWhitespace($0) })!
        return String(scalars[first...last])
    }

    /// `text.isBlank()`: empty, or nothing but Kotlin's white space.
    static func isBlank(_ text: String) -> Bool { text.unicodeScalars.allSatisfy(isWhitespace) }

    // MARK: Numbers

    /// `text.toDoubleOrNull()` on the JVM: Java's `Double.parseDouble` grammar, or nil. Characters up to U+0020 are taken from each
    /// end; then a sign, and `NaN`, `Infinity`, a decimal (`1.`, `.5`, `1e3`) or a hexadecimal with its binary exponent (`0x1.8p1`),
    /// either with an `f`, `F`, `d` or `D` after it. Only ASCII digits count.
    static func toDoubleOrNull(_ text: String) -> Double? {
        var units = Array(text.utf16)
        while let first = units.first, first <= 0x20 { units.removeFirst() }
        while let last = units.last, last <= 0x20 { units.removeLast() }
        guard !units.isEmpty, units.allSatisfy({ $0 < 0x80 }) else { return nil }
        let bytes = units.map { UInt8($0) }
        var i = 0
        var negative = false
        if bytes[i] == 0x2B || bytes[i] == 0x2D {
            negative = bytes[i] == 0x2D
            i += 1
        }
        let rest = String(decoding: bytes[i...], as: UTF8.self)
        if rest == "NaN" { return .nan }
        if rest == "Infinity" { return negative ? -.infinity : .infinity }
        func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }
        func isHex(_ b: UInt8) -> Bool { isDigit(b) || (b | 0x20 >= 0x61 && b | 0x20 <= 0x66) }
        let body = Array(bytes[i...])
        var j = 0
        var floatSuffix = false
        // What Swift's Double(String) is handed: only text this grammar has accepted, which it reads the same way.
        var accepted = ""
        let hexadecimal = body.count > 1 && body[0] == 0x30 && body[1] | 0x20 == 0x78
        if hexadecimal {
            j = 2
            let intStart = j
            while j < body.count, isHex(body[j]) { j += 1 }
            var digits = j - intStart
            if j < body.count, body[j] == 0x2E {
                j += 1
                let fracStart = j
                while j < body.count, isHex(body[j]) { j += 1 }
                digits += j - fracStart
            }
            guard digits > 0, j < body.count, body[j] | 0x20 == 0x70 else { return nil }
            j += 1
            if j < body.count, body[j] == 0x2B || body[j] == 0x2D { j += 1 }
            let expStart = j
            while j < body.count, isDigit(body[j]) { j += 1 }
            guard j > expStart else { return nil }
            accepted = String(decoding: body[0..<j], as: UTF8.self)
        } else {
            let intStart = j
            while j < body.count, isDigit(body[j]) { j += 1 }
            var digits = j - intStart
            if j < body.count, body[j] == 0x2E {
                j += 1
                let fracStart = j
                while j < body.count, isDigit(body[j]) { j += 1 }
                digits += j - fracStart
            }
            guard digits > 0 else { return nil }
            if j < body.count, body[j] | 0x20 == 0x65 {
                j += 1
                if j < body.count, body[j] == 0x2B || body[j] == 0x2D { j += 1 }
                let expStart = j
                while j < body.count, isDigit(body[j]) { j += 1 }
                guard j > expStart else { return nil }
            }
            accepted = String(decoding: body[0..<j], as: UTF8.self)
        }
        if j < body.count, [0x66, 0x46, 0x64, 0x44].contains(body[j]) {
            floatSuffix = body[j] | 0x20 == 0x66
            j += 1
        }
        guard j == body.count, var value = Double(accepted) else { return nil }
        // Java rounds a hexadecimal with a float suffix to a float; a decimal's suffix is only skipped.
        if floatSuffix, hexadecimal {
            value = Double(Float(value))
        }
        return negative ? -value : value
    }

    /// `text.toLongOrNull()`: a sign and decimal digits, which in Kotlin may be any script's (`Character.digit` takes `٤١` as 41),
    /// within a Long; nil otherwise.
    static func toLongOrNull(_ text: String) -> Int64? {
        let units = Array(text.utf16)
        guard !units.isEmpty else { return nil }
        var i = 0
        var negative = false
        if units[0] == 0x2B || units[0] == 0x2D {
            negative = units[0] == 0x2D
            i = 1
            guard units.count > 1 else { return nil }
        }
        var magnitude: UInt64 = 0
        while i < units.count {
            // A UTF-16 unit at a time, as Kotlin reads a String: a digit outside the basic plane is two units and neither is a digit.
            guard let scalar = Unicode.Scalar(units[i]), scalar.properties.numericType == .decimal,
                let value = scalar.properties.numericValue
            else { return nil }
            let (times, overflow) = magnitude.multipliedReportingOverflow(by: 10)
            let (sum, overflow2) = times.addingReportingOverflow(UInt64(value))
            guard !overflow, !overflow2 else { return nil }
            magnitude = sum
            i += 1
        }
        if negative { return magnitude <= UInt64(Int64.max) + 1 ? Int64(truncatingIfNeeded: 0 &- magnitude) : nil }
        return magnitude <= UInt64(Int64.max) ? Int64(magnitude) : nil
    }

    /// A whole number's exact digits, as `BigDecimal(value).toBigInteger().toString()` writes them (a fraction cut off toward zero),
    /// from 1e21 as Java's `Double.toString` (`1.0E21`); `-0` is "0".
    static func integerText(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value.isInfinite { return value > 0 ? "Infinity" : "-Infinity" }
        if value == 0 { return "0" }
        guard abs(value) < 1e21 else { return javaScientific(value) }
        let whole = value.rounded(.towardZero)
        if whole == 0 { return "0" }
        // Below 1e21 a double is under 2^70, so its exact value fits a UInt128.
        return (whole < 0 ? "-" : "") + String(UInt128(abs(whole)))
    }

    /// Java's `Double.toString` for a number written with an exponent: the shortest digits as `1.5E300`, `1.0E21`, `1.0E-7`.
    static func javaScientific(_ value: Double) -> String {
        let text = JsNumber.toText(value)
        guard let e = text.firstIndex(of: "e") else { return text }
        var mantissa = String(text[..<e])
        if !mantissa.contains(".") { mantissa += ".0" }
        var exponent = String(text[text.index(after: e)...])
        if exponent.hasPrefix("+") { exponent.removeFirst() }
        return mantissa + "E" + exponent
    }

    // MARK: Text by UTF-16 units

    /// Whether two texts are the same to Kotlin's `==`: unit for unit, never canonical equivalence.
    static func same(_ a: String, _ b: String) -> Bool { a.utf16.elementsEqual(b.utf16) }

    static func same(_ a: String?, _ b: String?) -> Bool {
        switch (a, b) {
        case (nil, nil): true
        case (let x?, let y?): same(x, y)
        default: false
        }
    }

    /// `text.substring(from, to)` in UTF-16 units.
    static func substring(_ text: String, _ from: Int, _ to: Int) -> String {
        let units = Array(text.utf16)
        let start = max(0, min(from, units.count))
        let end = max(start, min(to, units.count))
        return String(decoding: units[start..<end], as: UTF16.self)
    }

    /// `text.take(n)`.
    static func take(_ text: String, _ n: Int) -> String { substring(text, 0, n) }

    /// `text.padStart(length, pad)`: `pad` in front until the text is `length` UTF-16 units.
    static func padStart(_ text: String, _ length: Int, _ pad: Character) -> String {
        let missing = length - text.utf16.count
        return missing > 0 ? String(repeating: pad, count: missing) + text : text
    }

    /// `text.padEnd(length, pad)`.
    static func padEnd(_ text: String, _ length: Int, _ pad: Character) -> String {
        let missing = length - text.utf16.count
        return missing > 0 ? text + String(repeating: pad, count: missing) : text
    }

    /// `text.split(separator)` for a one-unit separator: every piece kept, the empty ones too (`"+".split("+")` is two).
    static func split(_ text: String, on separator: UInt16) -> [String] {
        var pieces: [String] = []
        var current: [UInt16] = []
        for unit in text.utf16 {
            if unit == separator {
                pieces.append(String(decoding: current, as: UTF16.self))
                current = []
            } else {
                current.append(unit)
            }
        }
        pieces.append(String(decoding: current, as: UTF16.self))
        return pieces
    }

    /// `text.uppercase()`, Locale.ROOT's full mapping (`ß` is `SS`), which Swift's `uppercased()` is too.
    static func uppercase(_ text: String) -> String { text.uppercased() }

    /// Whether every UTF-16 unit of `text` is an ASCII digit and there are `min` to `max` of them.
    static func asciiDigits(_ text: some Collection<UInt16>, _ range: ClosedRange<Int>) -> Bool {
        range.contains(text.count) && text.allSatisfy { $0 >= 0x30 && $0 <= 0x39 }
    }
}
