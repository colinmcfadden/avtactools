import CoreJS
import Foundation

/// Reads a latitude/longitude out of free text, in whatever shape it arrives: Google Maps, ForeFlight, a flight plan, a spreadsheet
/// cell, a text message.
///
///     34.545678, -84.123456          decimal degrees
///     N34.545678 W084.123456         decimal with hemispheres
///     34°32.740'N 084°07.407'W       degrees + decimal minutes (aviation)
///     34°32'44.4"N 84°07'24.4"W      degrees/minutes/seconds
///     34 32 44.4 N, 84 07 24.4 W     the same, unpunctuated
///     3432.740N 08407.407W           packed DDMM.mmm (flight plan)
///     343244N 0840724W               packed DDMMSS
///
/// A port of `frontend/src/utils/coordParse.js`, branch for branch (by way of Android's CoordinateParser), held to
/// `contracts/fixtures/coords/parse.json`. Anything that is a valid MGRS grid is deliberately refused, so typing a grid is never
/// reinterpreted as a coordinate.
///
/// The web's regular expressions are scanned by hand over Unicode scalars: their classes are spelled here with the code points the
/// Kotlin and JavaScript sources hold, ASCII digits only, and both cases of each letter (never a case-folding match, which takes `ſ`
/// for `s` and the Kelvin sign for `k`, as JavaScript's `/i` does not).
public enum CoordinateParser {
    public struct Parsed: Hashable, Sendable {
        public let lat: Double
        public let lon: Double
        public let format: String
        public let label: String

        public init(lat: Double, lon: Double, format: String, label: String) {
            self.lat = lat
            self.lon = lon
            self.format = format
            self.label = label
        }
    }

    /// What `read` makes of a text: `parsed` when it is a pair, with `signed` true when the text said which side of the meridian the
    /// longitude is on (a sign on its first number, or its own hemisphere letter); else the `problem` that stops it, for the target
    /// field to say in words. Problems, as the web's `readCoordinate` names them: "empty", "grid", "unreadable", "incomplete", "pair",
    /// "minutes", "latitude", "longitude", "same-axis".
    public struct Reading: Hashable, Sendable {
        public let parsed: Parsed?
        public let signed: Bool
        public let problem: String?

        public init(parsed: Parsed? = nil, signed: Bool = false, problem: String? = nil) {
            self.parsed = parsed
            self.signed = signed
            self.problem = problem
        }
    }

    // Band letters run C-X skipping I and O; 100 km square letters skip I and O too. Matching the real grammar (rather than [A-Z])
    // keeps "34N 084W" from being mistaken for a grid. [0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z]{2}[0-9]{0,10}
    private static let mgrsPattern = ScalarPattern((.digits, 1, 2), (.bands, 1, 1), (.squares, 2, 2), (.digits, 0, 10))

    // Punctuation that appears in coordinates and never in a grid: [.,;/°º˚∘′‵'´`″‶"“”+\-‐-―−\t\n].
    private static func isCoordinatePunctuation(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x2E, 0x2C, 0x3B, 0x2F, 0xB0, 0xBA, 0x2DA, 0x2218, 0x2032, 0x2035, 0x27, 0xB4, 0x60, 0x2033, 0x2036, 0x22, 0x201C, 0x201D,
            0x2B, 0x2D, 0x2010...0x2015, 0x2212, 0x09, 0x0A:
            true
        default: false
        }
    }

    // Both cases spelled out: [NSEWnsew].
    private static let hemisphereLetters = AsciiSet("NSEWnsew")
    // Any letter but a hemisphere's (the web's OTHER_LETTER), for looksLikeCoordinateText: [A-DF-MOPQRT-VXYZa-df-mopqrt-vxyz].
    private static let otherLetters = AsciiSet("ABCDFGHIJKLMOPQRTUVXYZabcdfghijklmopqrtuvxyz")
    // The class parse uses to refuse words, applied once the hemisphere letters are taken out (so it may include E and S):
    // [A-MOP-VXYZa-mop-vxyz].
    private static let nonHemisphereLetters = AsciiSet("ABCDEFGHIJKLMOPQRSTUVXYZabcdefghijklmopqrstuvxyz")

    private enum Axis {
        case lat, lon
        var max: Double { self == .lat ? 90.0 : 180.0 }
    }

    private struct Hemisphere {
        let axis: Axis
        let sign: Double
    }

    private static func hemisphere(_ letter: Character) -> Hemisphere? {
        switch letter {
        case "N": Hemisphere(axis: .lat, sign: 1)
        case "S": Hemisphere(axis: .lat, sign: -1)
        case "E": Hemisphere(axis: .lon, sign: 1)
        case "W": Hemisphere(axis: .lon, sign: -1)
        default: nil
        }
    }

    private static let formatLabels = [
        "decimal": "decimal degrees",
        "ddm": "degrees/decimal minutes",
        "dms": "degrees/minutes/seconds",
        "packed-ddm": "packed DDMM.mmm",
        "packed-dms": "packed DDMMSS",
    ]

    private static func scalars(_ text: String) -> [Unicode.Scalar] { Array(text.unicodeScalars) }
    private static func string(_ scalars: some Sequence<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    /// True when the text is (or is becoming) an MGRS grid rather than a coordinate.
    public static func looksLikeMgrs(_ text: String?) -> Bool {
        mgrsPattern.match((text ?? "").unicodeScalars.filter { !Js.isSpace($0) }, whole: true) != nil
    }

    /// A lenient, keystroke-by-keystroke check used to decide whether the target input is being given a coordinate rather than a grid.
    ///
    /// Deliberately looser than `parse`: it has to say "yes" to half-typed text like "34." or "34.5, -" so the field stops
    /// reformatting mid entry.
    ///
    /// Hemisphere letters and no other letter ("34N 84E", "34S") are a coordinate, and so is a number followed by a space with no
    /// letter at all ("34 ", "34 84"): a grid's digits come after its letters, and taking the space away would make "34 84" impossible
    /// to type.
    public static func looksLikeCoordinateText(_ text: String?) -> Bool {
        let raw = text ?? ""
        if Js.trim(raw).isEmpty { return false }
        if looksLikeMgrs(raw) { return false }
        let s = scalars(raw)
        if s.contains(where: isCoordinatePunctuation) { return true }
        let digitThenSpace = s.indices.dropLast().contains { AsciiSet.digits.contains(s[$0]) && Js.isSpace(s[$0 + 1]) }
        if digitThenSpace && !s.contains(where: AsciiSet.letters.contains) { return true }
        return s.contains(where: hemisphereLetters.contains) && !s.contains(where: otherLetters.contains)
    }

    /// Folds the many ways coordinate punctuation gets typed or pasted down to a plain ASCII form: degree/minute/second marks become
    /// spaces, exotic dashes become hyphens, and separators become " , ": a break the tokenizer notes, so "34.5, 84.1W" can be split
    /// where the comma was (see group).
    private static func normalize(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var inSeparators = false
        for s in text.unicodeScalars {
            let v = s.value
            // [,;/|\t\n\r]+ is one " , ".
            if v == 0x2C || v == 0x3B || v == 0x2F || v == 0x7C || v == 0x09 || v == 0x0A || v == 0x0D {
                if !inSeparators { out.append(contentsOf: " , ".unicodeScalars) }
                inSeparators = true
                continue
            }
            inSeparators = false
            switch v {
            case 0xB0, 0xBA, 0x2DA, 0x2218: out.append(" ")  // [°º˚∘]
            case 0x2032, 0x2035, 0x27, 0xB4, 0x60: out.append(" ")  // [′‵'´`]
            case 0x2033, 0x2036, 0x22, 0x201C, 0x201D: out.append(" ")  // [″‶"“”]
            case 0x2010...0x2015, 0x2212: out.append("-")  // [‐-―−]
            default: out.append(s)
            }
        }
        return Js.trim(String(out))
    }

    private enum Token {
        case number(NumberToken)
        case hemi(HemiToken)
    }

    private struct NumberToken {
        let raw: String
        let value: Double
        let negative: Bool
        let afterBreak: Bool
    }

    private struct HemiToken {
        let letter: Character
        let suffixLike: Bool
        let prefixLike: Bool
    }

    private static func isDigit(_ s: Unicode.Scalar) -> Bool { AsciiSet.digits.contains(s) }

    /// Where the number `[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)` that starts at `at` ends, as the regex takes it; nil for none.
    private static func numberEnd(_ s: [Unicode.Scalar], _ at: Int) -> Int? {
        func unsigned(_ from: Int) -> Int? {
            var i = from
            while i < s.count, isDigit(s[i]) { i += 1 }
            if i > from {
                if i + 1 < s.count, s[i] == ".", isDigit(s[i + 1]) {
                    i += 1
                    while i < s.count, isDigit(s[i]) { i += 1 }
                }
                return i
            }
            guard from + 1 < s.count, s[from] == ".", isDigit(s[from + 1]) else { return nil }
            i = from + 1
            while i < s.count, isDigit(s[i]) { i += 1 }
            return i
        }
        if at < s.count, s[at] == "+" || s[at] == "-", let end = unsigned(at + 1) { return end }
        return unsigned(at)
    }

    /// Splits normalized text into ordered number and hemisphere tokens, as the web's `/([NSEWnsew])|([+-]?…)/g` finds them.
    ///
    /// Numbers keep their raw text: leading zeros are what distinguish a packed "0840724" (DDDMMSS) from a plain 840724.
    private static func tokenize(_ text: String) -> [Token]? {
        let s = scalars(text)
        var tokens: [Token] = []
        var lastEnd = 0
        var i = 0
        while i < s.count {
            let isLetter = hemisphereLetters.contains(s[i])
            let end: Int
            if isLetter {
                end = i + 1
            } else if let numberEnd = numberEnd(s, i) {
                end = numberEnd
            } else {
                i += 1
                continue
            }
            // A separator (a comma, slash, tab…) between this token and the last.
            let afterBreak = !tokens.isEmpty && s[lastEnd..<i].contains(",")
            lastEnd = end
            if isLetter {
                // Whether the letter is glued to the number before it ("34.5N") or the one after it ("W084") is the only thing
                // separating a suffix from a prefix, and it decides which value the hemisphere applies to.
                let before = i > 0 ? s[i - 1] : nil
                let after = i + 1 < s.count ? s[i + 1] : nil
                tokens.append(
                    .hemi(
                        HemiToken(
                            letter: Character(s[i]).uppercased().first!,
                            suffixLike: before.map(isDigit) ?? false,
                            prefixLike: after.map { isDigit($0) || $0 == "+" || $0 == "-" || $0 == "." } ?? false
                        )))
            } else {
                let raw = string(s[i..<end])
                let value = JsNumber.parseFloat(raw)
                if !value.isFinite { return nil }
                tokens.append(.number(NumberToken(raw: raw, value: value, negative: raw.hasPrefix("-"), afterBreak: afterBreak)))
            }
            i = end
        }
        return tokens
    }

    private struct Group {
        var numbers: [NumberToken] = []
        var hemi: Character? = nil
        var prefix = false
    }

    /// Collects tokens into two coordinate groups.
    ///
    /// A hemisphere letter closes the group it belongs to, whether it was written in front (`N34 30`) or behind (`34 30 N`). With no
    /// hemisphere letters at all the numbers are simply split down the middle. A letter on only one value of a pair written with a
    /// separator ("34.5, 84.1W", "N34.5, -84.1") leaves one group holding both: it is split where the separator was, the letter going
    /// with the value it is written on.
    private static func group(_ tokens: [Token]) -> [Group]? {
        let hasLetter = tokens.contains { if case .hemi = $0 { true } else { false } }
        if !hasLetter {
            let numbers = tokens.compactMap { if case .number(let n) = $0 { n } else { nil } }
            // 2 = D/D, 4 = D M/D M, 6 = D M S/D M S. Anything else is not a pair.
            guard [2, 4, 6].contains(numbers.count) else { return nil }
            let half = numbers.count / 2
            return [Group(numbers: Array(numbers[0..<half])), Group(numbers: Array(numbers[half...]))]
        }

        var groups: [Group] = []
        var current = Group()
        var pending: Character? = nil

        for token in tokens {
            switch token {
            case .number(let number):
                if current.numbers.isEmpty, let letter = pending {
                    current.hemi = letter
                    current.prefix = true
                    pending = nil
                }
                current.numbers.append(number)
            case .hemi(let hemi):
                // A letter written in front of its number ("W084"), or one arriving when this group is already labelled
                // ("N34 32.74 W084 07.41"), belongs to the group that follows: it must not close the group it interrupted.
                let startsNextGroup = current.hemi != nil || (hemi.prefixLike && !hemi.suffixLike)
                if current.numbers.isEmpty {
                    pending = hemi.letter  // prefix on the very first value
                } else if startsNextGroup {
                    groups.append(current)
                    current = Group()
                    pending = hemi.letter
                } else {
                    current.hemi = hemi.letter  // suffix: "34 30 N"
                    groups.append(current)
                    current = Group()
                }
            }
        }
        if !current.numbers.isEmpty { groups.append(current) }

        if groups.count == 1 {
            let only = groups[0]
            let breaks = only.numbers.indices.filter { $0 > 0 && only.numbers[$0].afterBreak }
            if breaks.count == 1 {
                let at = breaks[0]
                return [
                    Group(numbers: Array(only.numbers[0..<at]), hemi: only.prefix ? only.hemi : nil),
                    Group(numbers: Array(only.numbers[at...]), hemi: only.prefix ? nil : only.hemi),
                ]
            }
        }

        return groups.count == 2 ? groups : nil
    }

    private struct Degrees {
        var degrees = 0.0
        var format = ""
        var problem: String? = nil
    }

    /// Splits a packed DDMM[SS] integer into parts; a `problem` when it can't be one: "minutes" for minutes or seconds of 60 or more,
    /// "range" for a width no packed value has.
    private static func unpack(_ token: NumberToken) -> Degrees {
        var digits = Substring(token.raw)
        if digits.hasPrefix("+") { digits = digits.dropFirst() }
        if digits.hasPrefix("-") { digits = digits.dropFirst() }
        let parts = digits.split(separator: ".", omittingEmptySubsequences: false)
        let intPart = Array(parts[0])
        let decimals = parts.count > 1 ? String(parts[1]) : ""
        let fraction = decimals.isEmpty ? 0.0 : JsNumber.parseFloat("0." + decimals)

        // Width tells us where the degrees stop: DDMM / DDDMM / DDMMSS / DDDMMSS.
        let degWidth: Int
        let hasSeconds: Bool
        switch intPart.count {
        case 4: (degWidth, hasSeconds) = (2, false)
        case 5: (degWidth, hasSeconds) = (3, false)
        case 6: (degWidth, hasSeconds) = (2, true)
        case 7: (degWidth, hasSeconds) = (3, true)
        default: return Degrees(problem: "range")
        }

        func number(_ range: Range<Int>) -> Double { Double(String(intPart[range]))! }
        let degrees = number(0..<degWidth)
        let minutes = number(degWidth..<(degWidth + 2))
        let seconds = hasSeconds ? number((degWidth + 2)..<intPart.count) + fraction : 0.0
        let minuteFraction = hasSeconds ? 0.0 : fraction

        if minutes >= 60 || seconds >= 60 { return Degrees(problem: "minutes") }
        return Degrees(
            degrees: degrees + (minutes + minuteFraction) / 60 + seconds / 3600,
            format: hasSeconds ? "packed-dms" : "packed-ddm"
        )
    }

    /// Converts one group's numbers to absolute decimal degrees, or a `problem` saying why they are not a value: "pair" (more than
    /// degrees, minutes and seconds), "minutes", or "range".
    private static func toDegrees(_ numbers: [NumberToken], _ axisMax: Double) -> Degrees {
        if numbers.count > 3 { return Degrees(problem: "pair") }

        if numbers.count >= 2 {
            let d = abs(numbers[0].value)
            let m = abs(numbers[1].value)
            let s = numbers.count > 2 ? abs(numbers[2].value) : nil
            if m >= 60 || (s.map { $0 >= 60 } ?? false) { return Degrees(problem: "minutes") }
            return Degrees(degrees: d + m / 60 + (s ?? 0.0) / 3600, format: numbers.count == 3 ? "dms" : "ddm")
        }

        let token = numbers[0]
        let magnitude = abs(token.value)

        // A plain reading wins whenever it's in range. Packed forms are recognised only when the number can't be degrees at all
        // (3432.740 as a latitude, 08407.407 as a longitude), which is what makes "0034" read as 34 degrees rather than 00 degrees 34
        // minutes. The trade-off is that a zero-padded packed value small enough to also be valid degrees ("0032.740N" near the
        // equator) reads as plain degrees: two honest readings, and the common one wins.
        if magnitude <= axisMax { return Degrees(degrees: magnitude, format: "decimal") }

        return unpack(token)
    }

    private struct Resolved {
        let axis: Axis?
        let value: Double
        let format: String
        let sided: Bool
    }

    /// Reads free text as a coordinate pair, as the web's `readCoordinate` does, branch for branch: see `Reading`. Held to
    /// `contracts/fixtures/coords/parse.json` (`read`).
    public static func read(_ text: String?) -> Reading {
        let raw = Js.trim(text ?? "")
        if raw.isEmpty { return Reading(problem: "empty") }
        // A grid is a grid. Never reinterpret one as a coordinate.
        if looksLikeMgrs(raw) { return Reading(problem: "grid") }

        let normalized = normalize(raw)
        // Letters other than N/S/E/W mean this is something else entirely (a place name, a partial grid); refuse rather than parse
        // the digits out of it.
        let withoutHemispheres = normalized.unicodeScalars.filter { !hemisphereLetters.contains($0) }
        if withoutHemispheres.contains(where: nonHemisphereLetters.contains) { return Reading(problem: "unreadable") }

        guard let tokens = tokenize(normalized) else { return Reading(problem: "unreadable") }
        let numbers = tokens.compactMap { if case .number(let n) = $0 { n } else { nil } }
        if numbers.isEmpty { return Reading(problem: "unreadable") }

        // Fewer than six numbers may be a pair still being typed ("34 32 44" on the way to degrees, minutes and seconds); six or more
        // that make no pair never will.
        guard let groups = group(tokens) else { return Reading(problem: numbers.count < 6 ? "incomplete" : "pair") }

        // Which axis each value is meant for, as the ordering rules below decide it: a hemisphere names it outright, one labelled
        // value fixes the other, and with none latitude comes first. Used only to say which value is wrong.
        let axes = groups.map { g in g.hemi.flatMap(hemisphere)?.axis }
        func axisOf(_ i: Int) -> Axis {
            if let axis = axes[i] { return axis }
            if let other = axes[1 - i] { return other == .lat ? .lon : .lat }
            return i == 0 ? .lat : .lon
        }

        var resolved: [Resolved] = []
        for (i, g) in groups.enumerated() {
            let info = g.hemi.flatMap(hemisphere)
            // Use the axis limit we know about; without a hemisphere assume the wider longitude limit so a valid longitude in first
            // position still parses and gets sorted out by the ordering rules below.
            let axisMax = info?.axis.max ?? Axis.lon.max
            let converted = toDegrees(g.numbers, axisMax)
            if let problem = converted.problem {
                let named = problem == "range" ? (axisOf(i) == .lat ? "latitude" : "longitude") : problem
                // "34 32 44 84" is degrees, minutes and seconds on the way, read for now as two degrees and minutes: its "minutes" of
                // 44 84… are not wrong yet.
                if named == "minutes" && groups.allSatisfy({ $0.hemi == nil }) && numbers.count < 6 {
                    return Reading(problem: "incomplete")
                }
                return Reading(problem: named)
            }
            let signedByText: Double = g.numbers[0].negative ? -1 : 1
            // Whether the value says its own side: a hemisphere letter, or a sign on its first number (a dash between minutes and
            // seconds is not one).
            let sided = info != nil || g.numbers[0].raw.hasPrefix("+") || g.numbers[0].raw.hasPrefix("-")
            resolved.append(
                Resolved(axis: info?.axis, value: converted.degrees * (info?.sign ?? signedByText), format: converted.format, sided: sided))
        }
        let first = resolved[0]
        let second = resolved[1]

        let lat: Double
        let lon: Double
        let lonValue: Resolved
        if let a = first.axis, let b = second.axis, a != b {
            // Hemispheres name the axes outright, in either order ("W084 N34").
            if a == .lat {
                (lat, lon, lonValue) = (first.value, second.value, second)
            } else {
                (lat, lon, lonValue) = (second.value, first.value, first)
            }
        } else if first.axis == second.axis && first.axis != nil {
            return Reading(problem: "same-axis")  // "34N 84N": not a coordinate pair.
        } else if first.axis == .lon || second.axis == .lat {
            // Only one side was labelled, but that's enough to fix both.
            (lat, lon, lonValue) = (second.value, first.value, first)
        } else {
            // Nothing labelled: latitude first, as every mapping tool writes it.
            //
            // Deliberately no cleverness here. Guessing from magnitudes (treating a first value beyond +-90 as a longitude) would let
            // "91.0, -84.1" quietly become a point in Antarctica instead of reporting the out-of-range latitude it almost certainly
            // is. Anyone who genuinely means longitude first can say so with a hemisphere letter.
            (lat, lon, lonValue) = (first.value, second.value, second)
        }

        if !lat.isFinite || !lon.isFinite { return Reading(problem: "unreadable") }
        if abs(lat) > 90 { return Reading(problem: "latitude") }
        if abs(lon) > 180 { return Reading(problem: "longitude") }

        let format = first.format == second.format ? first.format : "mixed"
        return Reading(
            parsed: Parsed(lat: lat, lon: lon, format: format, label: formatLabels[format] ?? "lat/long"),
            signed: lonValue.sided
        )
    }

    /// Parses free text into a latitude/longitude, or nil when the text isn't a coordinate (including when it's a valid MGRS grid).
    public static func parse(_ text: String?) -> Parsed? { read(text).parsed }

    /// Compact display of a parsed pair, for confirming what was recognised. Rounds as JavaScript's `toFixed` does (the exact binary
    /// value, ties up), not as a formatter does (the shortest decimal, which can round 1.005 up).
    public static func formatDecimal(_ lat: Double, _ lon: Double, places: Int = 5) -> String {
        "\(JsNumber.toFixed(lat, places)), \(JsNumber.toFixed(lon, places))"
    }

    /// The degrees-minutes-seconds text the web keeps as a diagram's `latLong` after analysis (`convertToLatLongString` in
    /// `utils/Helpers.js`): `34° 47' 1.74" N  84° 4' 55.88" W`. Seconds are rounded to two places and **never carried** into the
    /// minute, so 34.999999 reads `34° 59' 60.00" N`; the web does it and the LZ card shows it, so the apps do too.
    public static func formatLatLongDms(_ lat: Double, _ lon: Double) -> String { "\(dms(lat, true))  \(dms(lon, false))" }

    private static func dms(_ coordinate: Double, _ isLatitude: Bool) -> String {
        let absolute = abs(coordinate)
        let degrees = floor(absolute)
        let minutesNotTruncated = (absolute - degrees) * 60
        let minutes = floor(minutesNotTruncated)
        let seconds = JsNumber.toFixed((minutesNotTruncated - minutes) * 60, 2)
        let direction = isLatitude ? (coordinate >= 0 ? "N" : "S") : (coordinate >= 0 ? "E" : "W")
        return "\(kotlinLong(degrees))° \(kotlinLong(minutes))' \(seconds)\" \(direction)"
    }
}
