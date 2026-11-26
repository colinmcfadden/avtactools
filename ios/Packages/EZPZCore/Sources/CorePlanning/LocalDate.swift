import CoreModel
import Foundation

// The few pieces of java.time the Kotlin planner uses: a day (`LocalDate`), read strictly from `YYYY-MM-DD`, and a wall-clock
// time moved on by hours, minutes, seconds and nanoseconds (`LocalDateTime.plusHours` …), all on the proleptic Gregorian calendar
// with no zone, as java.time counts them. Foundation's `Date` is an instant and its Gregorian calendar turns Julian before 1582, so
// neither is used for the arithmetic: days are counted from the epoch by hand, which comes out the same in every time zone. Only
// `today` and `RouteWinds.instantText` ask about a zone, and they name it.

/// A day with no zone, as `java.time.LocalDate` holds one.
public struct LocalDate: Hashable, Sendable, Comparable, CustomStringConvertible {
    public var year: Int
    public var month: Int
    public var day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public static func < (a: LocalDate, b: LocalDate) -> Bool { (a.year, a.month, a.day) < (b.year, b.month, b.day) }

    /// The day it is now on the wall clock of `zone` (`LocalDate.now()`, the device's zone).
    public static func today(in zone: TimeZone = .current) -> LocalDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = calendar.dateComponents([.year, .month, .day], from: Date())
        return LocalDate(year: parts.year!, month: parts.month!, day: parts.day!)
    }

    /// `LocalDate.parse(text)` (ISO_LOCAL_DATE, strict): a year of four ASCII digits, or more with a sign (`+10000`, `-0001`), then
    /// `-MM-DD` with a month and a day that exist (no 30 February). Nil for anything else, as `runCatching { … }.getOrNull()` is.
    public static func parse(_ text: String) -> LocalDate? {
        let units = Array(text.utf16)
        func isDigit(_ u: UInt16) -> Bool { u >= 0x30 && u <= 0x39 }
        func digits(_ slice: ArraySlice<UInt16>) -> Int? {
            guard !slice.isEmpty, slice.allSatisfy(isDigit) else { return nil }
            return slice.reduce(0) { $0 * 10 + Int($1 - 0x30) }
        }
        // `-MM-DD` is the last six units; the year is what comes before.
        guard units.count >= 10 else { return nil }
        let tail = units.count - 6
        guard units[tail] == 0x2D, units[tail + 3] == 0x2D, let month = digits(units[(tail + 1)..<(tail + 3)]),
            let day = digits(units[(tail + 4)..<(tail + 6)])
        else { return nil }
        var yearUnits = units[0..<tail]
        var sign = 1
        let signed = yearUnits.first == 0x2B || yearUnits.first == 0x2D
        if signed {
            if yearUnits.first == 0x2D { sign = -1 }
            yearUnits = yearUnits.dropFirst()
        }
        // SignStyle.EXCEEDS_PAD: four digits take no sign, more than four must have one; at most nine.
        guard yearUnits.count >= 4, yearUnits.count <= 9, let magnitude = digits(yearUnits) else { return nil }
        if !signed && yearUnits.count != 4 { return nil }
        if signed && sign == 1 && yearUnits.count == 4 { return nil }
        let year = sign * magnitude
        guard (1...12).contains(month), day >= 1, day <= lengthOfMonth(year, month) else { return nil }
        return LocalDate(year: year, month: month, day: day)
    }

    static func isLeap(_ year: Int) -> Bool { year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) }

    static func lengthOfMonth(_ year: Int, _ month: Int) -> Int {
        switch month {
        case 2: isLeap(year) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    /// Days since 1970-01-01 (negative before it), on the proleptic Gregorian calendar.
    public var epochDay: Int64 {
        let y = Int64(month <= 2 ? year - 1 : year)
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let m = Int64(month)
        let dayOfYear = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + Int64(day) - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    public init(epochDay: Int64) {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let dayOfEra = z - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let mp = (5 * dayOfYear + 2) / 153
        let d = dayOfYear - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: Int(yearOfEra + era * 400 + (m <= 2 ? 1 : 0)), month: Int(m), day: Int(d))
    }

    /// `date.atStartOfDay()`.
    public func atStartOfDay() -> LocalDateTime { LocalDateTime(year: year, month: month, day: day) }

    /// `date.atTime(hour, minute, second)`.
    public func atTime(_ hour: Int, _ minute: Int, _ second: Int = 0) -> LocalDateTime {
        LocalDateTime(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
    }

    /// ISO-8601, as `LocalDate.toString` writes it: `2026-03-04`.
    public var description: String {
        let y = abs(year) < 1000 ? (year < 0 ? "-" : "") + padded(String(abs(year)), 4) : (year > 9999 ? "+" : "") + String(year)
        return "\(y)-\(padded(String(month), 2))-\(padded(String(day), 2))"
    }
}

extension LocalDateTime {
    private static let nanosPerSecond: Int64 = 1_000_000_000
    private static let nanosPerDay: Int64 = 86_400 * nanosPerSecond

    /// `time.toLocalDate()`.
    public var date: LocalDate { LocalDate(year: year, month: month, day: day) }

    /// Nanoseconds since the start of the day.
    var nanoOfDay: Int64 { Int64(hour * 3600 + minute * 60 + second) * Self.nanosPerSecond + Int64(nanosecond) }

    /// `time.plusNanos(n)`: on into the next days (or back into the earlier ones) as java.time counts them.
    public func plusNanos(_ n: Int64) -> LocalDateTime {
        // Days and the rest apart, as java.time does, so a large `n` cannot overflow the sum.
        let (days, rest) = floorDivMod(n, Self.nanosPerDay)
        return plus(days: days, nanos: rest)
    }

    public func plusSeconds(_ n: Int64) -> LocalDateTime {
        let (days, rest) = floorDivMod(n, 86_400)
        return plus(days: days, nanos: rest * Self.nanosPerSecond)
    }

    /// `days` whole days on, then `nanos` (less than a day) more.
    private func plus(days: Int64, nanos: Int64) -> LocalDateTime {
        let (carry, nanoOfDay) = floorDivMod(self.nanoOfDay + nanos, Self.nanosPerDay)
        let day = LocalDate(epochDay: date.epochDay + days + carry)
        let seconds = nanoOfDay / Self.nanosPerSecond
        return LocalDateTime(
            year: day.year, month: day.month, day: day.day, hour: Int(seconds / 3600), minute: Int(seconds / 60 % 60),
            second: Int(seconds % 60), nanosecond: Int(nanoOfDay % Self.nanosPerSecond))
    }

    public func plusMinutes(_ n: Int64) -> LocalDateTime { plusSeconds(n * 60) }

    public func plusHours(_ n: Int64) -> LocalDateTime { plusSeconds(n * 3600) }

    /// Seconds since 1970-01-01T00:00 on this wall clock, as if it were UTC.
    var wallEpochSecond: Int64 { date.epochDay * 86_400 + Int64(hour * 3600 + minute * 60 + second) }
}

/// `Math.floorDiv` and `Math.floorMod`.
private func floorDivMod(_ a: Int64, _ b: Int64) -> (Int64, Int64) {
    var q = a / b
    var r = a % b
    if r != 0, (r < 0) != (b < 0) {
        q -= 1
        r += b
    }
    return (q, r)
}
