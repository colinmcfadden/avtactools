import Foundation

// What the Kotlin of android/feature-map leans on that Swift does differently by itself. CoreGeo has the same few helpers, internal to
// it, so CoreMap keeps its own: Java's `Math.min` and `max` (NaN wins, where `Swift.min` keeps whichever came first), Kotlin's
// `coerceIn` and `toInt`, `toRadians`, and text compared and searched by UTF-16 units, as Kotlin's String is, never by Swift's
// grapheme clusters (a `.` followed by a combining mark is one Character, so `hasPrefix("pk.")` would miss it).

/// Java's `Math.min`: NaN if either is NaN, and -0 below 0.
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

/// Kotlin's `Iterable<Double>.minOf`, which folds with `Math.min`, so a NaN anywhere is the answer.
func javaMinOf(_ values: [Double]) -> Double { values.dropFirst().reduce(values[0], javaMin) }

/// Kotlin's `Iterable<Double>.maxOf`.
func javaMaxOf(_ values: [Double]) -> Double { values.dropFirst().reduce(values[0], javaMax) }

/// Kotlin's `Double.coerceIn`: NaN stays NaN.
func coerceIn(_ value: Double, _ low: Double, _ high: Double) -> Double {
    if value < low { return low }
    if value > high { return high }
    return value
}

/// Kotlin's `Double.toInt()`: toward zero, NaN is 0, and past an `Int`'s 32 bits the end of the range (Swift's `Int(_:)` traps).
func kotlinInt(_ value: Double) -> Int {
    if value.isNaN { return 0 }
    if value >= Double(Int32.max) { return Int(Int32.max) }
    if value <= Double(Int32.min) { return Int(Int32.min) }
    return Int(value)
}

/// Kotlin's 32-bit `Int` arithmetic wraps where Swift's `+` traps.
func kotlinAdd(_ a: Int, _ b: Int) -> Int { Int(Int32(truncatingIfNeeded: a) &+ Int32(truncatingIfNeeded: b)) }

/// Java's `Math.toRadians` multiplies by this constant, which is what pi / 180 rounds to; `toDegrees` by 180 / pi.
func toRadians(_ degrees: Double) -> Double { degrees * (Double.pi / 180) }
func toDegrees(_ radians: Double) -> Double { radians * (180 / Double.pi) }

/// `text.startsWith(prefix)` by UTF-16 units.
func startsWith(_ text: String, _ prefix: String) -> Bool { text.utf16.starts(with: prefix.utf16) }

/// `needle in text`, by UTF-16 units.
func containsText(_ text: String, _ needle: String) -> Bool {
    let haystack = Array(text.utf16)
    let wanted = Array(needle.utf16)
    if wanted.isEmpty { return true }
    if wanted.count > haystack.count { return false }
    for start in 0...(haystack.count - wanted.count) where haystack[start..<(start + wanted.count)].elementsEqual(wanted) {
        return true
    }
    return false
}

/// Kotlin's `take(n)`: the first `n` UTF-16 units. (A surrogate pair cut in two is U+FFFD here; Kotlin keeps the half. The texts it
/// is used on are ASCII.)
func take(_ text: String, _ count: Int) -> String { String(decoding: text.utf16.prefix(count), as: UTF16.self) }

/// Two texts the same by UTF-16 units, as Kotlin's `==` compares them (Swift's `==` is canonical equivalence).
func sameText(_ a: String, _ b: String) -> Bool { a.utf16.elementsEqual(b.utf16) }

/// Kotlin's `isBlank()`: every character `Char.isWhitespace`, which is Java's `isWhitespace` or `isSpaceChar`: the space, line and
/// paragraph separators (the no-break spaces among them), the controls 9 to 13 and 0x1C to 0x1F. Not U+FEFF, which JavaScript's trim
/// takes. A surrogate is never white space, and no white space is outside the first plane, so scalars give the same answer as chars.
func kotlinIsBlank(_ text: String) -> Bool {
    text.unicodeScalars.allSatisfy { scalar in
        switch scalar.value {
        case 9...13, 0x1C...0x1F: return true
        default:
            switch scalar.properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}

/// Kotlin's `sortedBy`, which is stable; Swift's `sorted` makes no promise that equal elements keep their order.
func stableSorted<T>(_ values: [T], by key: (T) -> Double) -> [T] {
    values.enumerated().sorted { a, b in
        let ka = key(a.element)
        let kb = key(b.element)
        // Kotlin's sortedBy compares with compareValues: Double's compareTo, where NaN is above everything and equal to itself, and
        // -0 is below 0.
        if ka.isNaN || kb.isNaN {
            if ka.isNaN && kb.isNaN { return a.offset < b.offset }
            return kb.isNaN
        }
        if ka == 0, kb == 0, ka.sign != kb.sign { return ka.sign == .minus }
        return ka != kb ? ka < kb : a.offset < b.offset
    }.map(\.element)
}
