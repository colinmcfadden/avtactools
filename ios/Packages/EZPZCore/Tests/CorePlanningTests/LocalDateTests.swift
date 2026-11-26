import CoreModel
import Foundation
import Testing

@testable import CorePlanning

/// The pieces of java.time the planner uses, counted by hand on the proleptic Gregorian calendar (LocalDate.swift): held here to
/// what java.time answers, since no fixture writes them.
struct LocalDateTests {
    @Test func aDateIsReadAsISOLocalDateReadsItStrictly() {
        #expect(LocalDate.parse("2026-10-03") == LocalDate(year: 2026, month: 10, day: 3))
        #expect(LocalDate.parse("2028-02-29") == LocalDate(year: 2028, month: 2, day: 29))
        #expect(LocalDate.parse("2000-02-29") != nil)
        #expect(LocalDate.parse("+10000-01-01") == LocalDate(year: 10000, month: 1, day: 1))
        #expect(LocalDate.parse("-0001-01-01") == LocalDate(year: -1, month: 1, day: 1))
        for bad in [
            "2100-02-29", "2026-02-30", "2026-04-31", "2026-13-01", "2026-00-10", "2026-10-00", "26-10-03", "2026-1-03", "+2026-10-03",
            "10000-01-01", "2026/10/03", " 2026-10-03", "２０２６-10-03", "",
        ] {
            #expect(LocalDate.parse(bad) == nil, "\(bad)")
        }
    }

    @Test func aDayIsCountedFromTheEpochBothWays() {
        #expect(LocalDate(year: 1970, month: 1, day: 1).epochDay == 0)
        #expect(LocalDate(year: 1969, month: 12, day: 31).epochDay == -1)
        #expect(LocalDate(year: 2026, month: 10, day: 3).epochDay == 20_729)
        // Before 1582 too: the proleptic Gregorian calendar, as java.time counts, not Foundation's Julian one.
        #expect(LocalDate(year: 1000, month: 3, day: 1).epochDay == -354_226)
        for day in stride(from: Int64(-800_000), through: 800_000, by: 997) {
            #expect(LocalDate(epochDay: day).epochDay == day)
        }
    }

    @Test func aWallClockTimeMovesOnAsJavaTimeMovesIt() {
        let late = LocalDateTime(year: 2026, month: 12, day: 31, hour: 23, minute: 59, second: 30)
        #expect(late.plusSeconds(45) == LocalDateTime(year: 2027, month: 1, day: 1, hour: 0, minute: 0, second: 15))
        #expect(late.plusMinutes(-1440) == LocalDateTime(year: 2026, month: 12, day: 30, hour: 23, minute: 59, second: 30))
        #expect(late.plusHours(24 * 60) == LocalDateTime(year: 2027, month: 3, day: 1, hour: 23, minute: 59, second: 30))
        #expect(
            LocalDateTime(year: 2026, month: 3, day: 1).plusNanos(-1)
                == LocalDateTime(year: 2026, month: 2, day: 28, hour: 23, minute: 59, second: 59, nanosecond: 999_999_999))
        #expect(LocalDate(year: 2026, month: 3, day: 4).atTime(12, 30) == LocalDateTime(year: 2026, month: 3, day: 4, hour: 12, minute: 30))
        // No zone: a night the clocks change is 24 hours of wall clock, in every zone the tests run in.
        #expect(
            LocalDateTime(year: 2026, month: 3, day: 8, hour: 1).plusHours(2) == LocalDateTime(year: 2026, month: 3, day: 8, hour: 3))
    }

    @Test func aDateIsWrittenAsJavaTimeWritesIt() {
        #expect(LocalDate(year: 2026, month: 3, day: 4).description == "2026-03-04")
        #expect(LocalDate(year: 12, month: 1, day: 2).description == "0012-01-02")
        #expect(LocalDate(year: -5, month: 1, day: 2).description == "-0005-01-02")
        #expect(LocalDate(year: 10000, month: 1, day: 2).description == "+10000-01-02")
    }

    @Test func todayIsTheWallClocksDayInTheZoneNamed() {
        let calendar = { (zone: TimeZone) -> Calendar in
            var c = Calendar(identifier: .gregorian)
            c.timeZone = zone
            return c
        }
        for name in ["Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC"] {
            let zone = TimeZone(identifier: name)!
            let today = LocalDate.today(in: zone)
            let parts = calendar(zone).dateComponents([.year, .month, .day], from: Date())
            // Either side of a midnight that falls between the two looks.
            let yesterday = LocalDate(epochDay: LocalDate(year: parts.year!, month: parts.month!, day: parts.day!).epochDay - 1)
            #expect(today == LocalDate(year: parts.year!, month: parts.month!, day: parts.day!) || today == yesterday, "\(name)")
        }
    }
}
