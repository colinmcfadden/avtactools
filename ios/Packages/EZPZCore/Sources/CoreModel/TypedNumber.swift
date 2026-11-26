import CoreJS

/// What a form does with a number a person typed, and with one it is putting back in a field. Shared by the forms so they agree on
/// what a number is.
enum TypedNumber {
    /// A typed number: digits with an optional point and sign, and nothing else ("12 kt", "1e3" and "1,5" are not numbers here).
    /// Trimmed as Kotlin trims; only ASCII digits count.
    static func parse(_ text: String) -> Double? {
        let trimmed = Kotlin.trim(text)
        let units = Array(trimmed.utf16)
        var i = 0
        if i < units.count, units[i] == 0x2B || units[i] == 0x2D { i += 1 }
        func isDigit(_ j: Int) -> Bool { j < units.count && units[j] >= 0x30 && units[j] <= 0x39 }
        let start = i
        while isDigit(i) { i += 1 }
        if i > start {
            if i < units.count, units[i] == 0x2E {
                i += 1
                while isDigit(i) { i += 1 }
            }
        } else {
            guard i < units.count, units[i] == 0x2E, isDigit(i + 1) else { return nil }
            i += 1
            while isDigit(i) { i += 1 }
        }
        guard i == units.count else { return nil }
        return Double(trimmed)
    }

    /// A number as it is typed back into a field: no trailing ".0", no exponent, no locale (`BigDecimal.valueOf(value)
    /// .stripTrailingZeros().toPlainString()`: the shortest digits, laid out plainly). NaN and the infinities, which Kotlin's
    /// BigDecimal throws on and no field holds, are their words.
    static func plain(_ value: Double) -> String {
        guard value.isFinite else { return JsNumber.toText(value) }
        if value == 0 { return "0" }
        let text = JsNumber.toText(abs(value))
        let sign = value < 0 ? "-" : ""
        guard let e = text.firstIndex(of: "e") else { return sign + text }
        let mantissa = text[..<e]
        let exponent = Int(text[text.index(after: e)...])!
        let digits = mantissa.filter { $0 != "." }
        let point = (mantissa.firstIndex(of: ".").map { mantissa.distance(from: mantissa.startIndex, to: $0) } ?? mantissa.count) + exponent
        if point <= 0 { return sign + "0." + String(repeating: "0", count: -point) + digits }
        if point >= digits.count { return sign + digits + String(repeating: "0", count: point - digits.count) }
        return sign + String(digits.prefix(point)) + "." + String(digits.dropFirst(point))
    }

    /// At most `max` characters, counted as the file formats count them (code points), so an emoji at the end is never split.
    static func cut(_ text: String, _ max: Int) -> String {
        text.unicodeScalars.count <= max ? text : String(String.UnicodeScalarView(text.unicodeScalars.prefix(max)))
    }
}
