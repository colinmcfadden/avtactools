// What the Kotlin port leans on that Swift does differently by itself: Java's `Math.min` and `Math.max` (NaN wins, and -0 is less
// than 0, where `Swift.min` keeps whichever came first), Kotlin's `toInt` and `toLong` (NaN is 0 and a value past the range is its
// end, where Swift's `Int(_:)` traps), `coerceIn`, and the few ASCII patterns the regular expressions of core-geo match.

/// Java's `Math.min`: NaN if either is NaN, and -0 below 0. The grid's boxes carry a NaN through, as Kotlin's do.
func javaMin(_ a: Double, _ b: Double) -> Double {
    if a.isNaN || b.isNaN { return .nan }
    if a == 0, b == 0 { return a.sign == .minus || b.sign == .minus ? -0.0 : 0.0 }
    return a <= b ? a : b
}

/// Java's `Math.max`: NaN if either is NaN, and 0 above -0.
func javaMax(_ a: Double, _ b: Double) -> Double {
    if a.isNaN || b.isNaN { return .nan }
    if a == 0, b == 0 { return a.sign == .plus || b.sign == .plus ? 0.0 : -0.0 }
    return a >= b ? a : b
}

/// Kotlin's `Double.coerceIn`: NaN stays NaN.
func coerceIn(_ value: Double, _ low: Double, _ high: Double) -> Double {
    if value < low { return low }
    if value > high { return high }
    return value
}

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

/// `text.padStart(width, '0')`.
func padded(_ text: String, _ width: Int) -> String {
    let count = text.utf16.count
    return count >= width ? text : String(repeating: "0", count: width - count) + text
}

/// A set of ASCII characters, for the character classes core-geo's regular expressions spell out. Only ASCII is ever in one: `[0-9]`,
/// never `\d`, which in Swift Regex (as in Android's ICU) takes every script's digits.
struct AsciiSet: Sendable {
    private let low: UInt64
    private let high: UInt64

    init(_ characters: String) {
        var low: UInt64 = 0
        var high: UInt64 = 0
        for scalar in characters.unicodeScalars {
            precondition(scalar.isASCII, "an AsciiSet holds ASCII only")
            if scalar.value < 64 { low |= 1 << UInt64(scalar.value) } else { high |= 1 << UInt64(scalar.value - 64) }
        }
        self.low = low
        self.high = high
    }

    func contains(_ scalar: Unicode.Scalar) -> Bool {
        let v = scalar.value
        if v < 64 { return low & (1 << UInt64(v)) != 0 }
        if v < 128 { return high & (1 << UInt64(v - 64)) != 0 }
        return false
    }

    static let digits = AsciiSet("0123456789")
    static let letters = AsciiSet("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
    /// A latitude band, C to X without I and O.
    static let bandsUpper = AsciiSet("CDEFGHJKLMNPQRSTUVWX")
    static let bands = AsciiSet("CDEFGHJKLMNPQRSTUVWXcdefghjklmnpqrstuvwx")
    /// A 100 km square's letter, A to Z without I and O.
    static let squaresUpper = AsciiSet("ABCDEFGHJKLMNPQRSTUVWXYZ")
    static let squares = AsciiSet("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjklmnpqrstuvwxyz")
}

/// A regular expression that is a run of character classes, each repeated a number of times (`[0-9]{1,2}[C-X][A-Z]{2}[0-9]*`),
/// matched as a regex matches it: each class takes as many as it can, then gives them back one at a time until the rest matches.
struct ScalarPattern: Sendable {
    struct Part: Sendable {
        let set: AsciiSet
        let min: Int
        let max: Int
    }

    let parts: [Part]

    init(_ parts: (AsciiSet, Int, Int)...) {
        self.parts = parts.map { Part(set: $0.0, min: $0.1, max: $0.2) }
    }

    /// How many characters each part took, matching from the start of `text`; `whole` asks that the match end where the text does
    /// (Kotlin's `matches`, `matchEntire`), else it may stop anywhere (an anchored `^…` search). Nil when it does not match.
    func match(_ text: [Unicode.Scalar], whole: Bool) -> [Int]? {
        var counts = [Int](repeating: 0, count: parts.count)
        func from(_ i: Int, _ at: Int) -> Bool {
            if i == parts.count { return !whole || at == text.count }
            let part = parts[i]
            var run = 0
            while run < part.max, at + run < text.count, part.set.contains(text[at + run]) { run += 1 }
            guard run >= part.min else { return false }
            for taken in stride(from: run, through: part.min, by: -1) {
                counts[i] = taken
                if from(i + 1, at + taken) { return true }
            }
            return false
        }
        return from(0, 0) ? counts : nil
    }

    func matches(_ text: String) -> Bool { match(Array(text.unicodeScalars), whole: true) != nil }
}
