import Testing

@testable import CoreModel

/// Android's WeatherTest.
struct WeatherTests {
    let minute: Int64 = 60_000
    var hour: Int64 { 60 * minute }

    @Test func anAgeIsInWordsByTheLargestUnitThatFits() {
        #expect(WeatherAge.words(0) == "just now")
        #expect(WeatherAge.words(59_999) == "just now")
        #expect(WeatherAge.words(minute) == "1 min ago")
        #expect(WeatherAge.words(59 * minute + 59_999) == "59 min ago")
        #expect(WeatherAge.words(hour) == "1 h ago")
        #expect(WeatherAge.words(3 * hour + 40 * minute) == "3 h ago")
        #expect(WeatherAge.words(47 * hour + 59 * minute) == "47 h ago")
        #expect(WeatherAge.words(48 * hour) == "2 days ago")
        #expect(WeatherAge.words(10 * 24 * hour + 5 * hour) == "10 days ago")
    }

    @Test func aClockThatWentBackwardsIsJustNowNotANegativeAge() {
        #expect(WeatherAge.words(-5 * minute) == "just now")
        #expect(WeatherAge.words(Int64.min / 2) == "just now")
    }

    @Test func aReportIsStaleAfterNinetyMinutesAndNotBefore() {
        let snapshot = WeatherSnapshot(at: LatLon(lat: 34, lon: -84), fetchedAtMillis: 1_000_000, observation: nil, notams: .clear)
        #expect(!snapshot.isStale(1_000_000 + 90 * minute))
        #expect(snapshot.isStale(1_000_000 + 90 * minute + 1))
        // From the future is not stale.
        #expect(!snapshot.isStale(1_000_000 - hour))
        #expect(snapshot.age(1_000_000 + 12 * minute) == "12 min ago")
    }

    @Test func listedNotamsAreCountedAcrossTheirGroups() {
        let listed = Notams.listed([NotamGroup(title: "Obstruction", texts: ["a", "b"]), NotamGroup(title: "Airspace", texts: ["c"])])
        #expect(listed.count == 3)
        #expect(Notams.listed([]).count == 0)
    }
}
