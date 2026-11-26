import CoreJS
import CoreModel

// What the Kotlin port of core-planning leans on that Swift does differently by itself: Kotlin's `toInt` and `toLong` (NaN is 0 and
// a value past the range is its end, where Swift's `Int(_:)` traps), `kotlin.math.max` (NaN wins), Kotlin's `trim()` and
// `isBlank()` (its own white space, neither JavaScript's nor Swift's), text compared unit for unit as Kotlin's `==` compares it, and
// kotlinx's reading of a JSON primitive (`content`, `doubleOrNull`). CoreModel and CoreGeo keep their own copies internal.

/// Kotlin's `Double.toInt()`: toward zero, NaN is 0, and past an `Int`'s 32 bits the end of the range.
func kotlinInt(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= Double(Int32.max) { return Int(Int32.max) }
    if value <= Double(Int32.min) { return Int(Int32.min) }
    return Int(value)
}

/// Kotlin's `Double.toLong()`: toward zero, NaN is 0, and past 64 bits the end of the range.
func kotlinLong(_ value: Double) -> Int64 {
    if value.isNaN { return 0 }
    if value >= 9_223_372_036_854_775_807.0 { return .max }
    if value <= -9_223_372_036_854_775_808.0 { return .min }
    return Int64(value)
}

/// `kotlin.math.max` on doubles (Java's `Math.max`): NaN if either is NaN, and 0 above -0. `Swift.max` keeps whichever came first.
func javaMax(_ a: Double, _ b: Double) -> Double {
    if a.isNaN || b.isNaN { return .nan }
    if a == 0, b == 0 { return a.sign == .plus || b.sign == .plus ? 0.0 : -0.0 }
    return a >= b ? a : b
}

/// Whether two texts are the same to Kotlin's `==`: unit for unit, never Swift's canonical equivalence.
func same(_ a: String?, _ b: String?) -> Bool {
    switch (a, b) {
    case (nil, nil): true
    case (let x?, let y?): x.utf16.elementsEqual(y.utf16)
    default: false
    }
}

/// `Char.isWhitespace()`: Java's white space (the C0 separators U+001C to U+001F among it) or a Unicode space separator. Not the
/// byte order mark, not U+0085.
private func isKotlinWhitespace(_ scalar: Unicode.Scalar) -> Bool {
    switch scalar.value {
    case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: true
    default: false
    }
}

/// `text.trim()` as Kotlin trims.
func kotlinTrim(_ text: String) -> String {
    let scalars = text.unicodeScalars
    guard let first = scalars.firstIndex(where: { !isKotlinWhitespace($0) }) else { return "" }
    let last = scalars.lastIndex(where: { !isKotlinWhitespace($0) })!
    return String(scalars[first...last])
}

/// `text.isBlank()`: empty, or nothing but Kotlin's white space.
func kotlinIsBlank(_ text: String) -> Bool { text.unicodeScalars.allSatisfy(isKotlinWhitespace) }

/// `text.padStart(width, '0')`, counted in UTF-16 units as Kotlin counts.
func padded(_ text: String, _ width: Int) -> String {
    let count = text.utf16.count
    return count >= width ? text : String(repeating: "0", count: width - count) + text
}

/// A primitive's text as kotlinx gives it (`JsonPrimitive.content`): text as it is, a number's literal, `true`; nil for JSON null
/// (`contentOrNull`), a list, an object or a key that is not there.
func primitiveText(_ value: JSONTree?) -> String? {
    switch value {
    case .bool(let b)?: b ? "true" : "false"
    case .number(let n)?: n.text
    case .string(let s)?: s
    default: nil
    }
}

/// kotlinx's `doubleOrNull`, kept only when finite: a primitive whose text Java reads as a number, so `"34.5"` quoted is 34.5 too.
/// ModelJSON's `double` is exactly that rule, refusing what is not finite.
func finiteNumber(_ value: JSONTree?) -> Double? {
    guard let value else { return nil }
    return try? ModelJSON().double(value)
}
