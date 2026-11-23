import Foundation
import Testing

@testable import CoreMap

/// Android's VfrChartsTest, case for case: what `/api/config` and `coverage.json` are read as. Remembered as text on the device, and
/// a bad box would refuse the whole style: every field is checked.
struct VfrChartsTests {
    let good = [-88.0, 32.0, -72.0, 40.0]

    func live(
        root: String = "https://api.test",
        build: String = "2026-10-29.1",
        tiles: String = "/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp",
        coverage: String = "/charts/vfr/2026-10-29.1/coverage.json",
        tileSize: Int = 512,
        minZoom: Int = 5,
        maxZoom: Int = 14,
        effective: String = "2026-10-29T09:01Z",
        expires: String = "2026-12-24T09:01Z",
        bounds: [Double]? = nil,
        fallback: [[Double]] = []
    ) -> VfrLive? {
        VfrLive.of(
            root: root, build: build, tiles: tiles, coverage: coverage, tileSize: tileSize, minZoom: minZoom, maxZoom: maxZoom,
            effective: effective, expires: expires, bounds: bounds ?? good, fallback: fallback)
    }

    @Test func itsAddressesAreMadeWholeWithTheServersRootTrailingSlashOrNone() {
        #expect(live()?.tiles == "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp")
        #expect(live(root: "https://api.test/")?.tiles == "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp")
        #expect(live()?.coverage == "/charts/vfr/2026-10-29.1/coverage.json")  // the app's own client asks for this one
    }

    @Test func anAddressThatIsNotOnThisServerOrNamesNoTileIsNoBuild() {
        #expect(live(tiles: "https://elsewhere.test/{z}/{x}/{y}.webp") == nil)
        #expect(live(tiles: "//elsewhere.test/{z}/{x}/{y}.webp") == nil)
        #expect(live(tiles: "/charts/vfr/a/{z}/{x}.webp") == nil)
        #expect(live(coverage: "https://elsewhere.test/coverage.json") == nil)
    }

    @Test func sizesZoomsAndDatesTheServerNeverSendsAreNoBuild() {
        #expect(live(build: " ") == nil)
        #expect(live(tileSize: 0) == nil)
        #expect(live(minZoom: -1) == nil)
        #expect(live(minZoom: 15) == nil)  // above the highest
        #expect(live(maxZoom: 30) == nil)
        #expect(live(expires: "next week") == nil)
        #expect(live(effective: "") == nil)
    }

    /// Kotlin's `isBlank` is Java's white space and space characters: the no-break spaces and the information separators count,
    /// U+FEFF does not.
    @Test func aBuildNameIsBlankAsKotlinCountsIt() {
        #expect(live(build: "\u{00A0}\u{2007}\u{202F}\u{1C}\t\u{3000}\u{2028}") == nil)
        #expect(live(build: "") == nil)
        #expect(live(build: "\u{FEFF}") != nil)
        #expect(live(build: "\u{200B}") != nil)  // a zero-width space is a format character, not a space
    }

    /// Swift's `contains` and `hasPrefix` match Characters: a `{z}` followed by a combining mark, or a `/` with one, would be missed.
    /// Matched by UTF-16 units, as Kotlin matches them.
    @Test func theAddressIsMatchedByItsUtf16Units() {
        #expect(live(tiles: "/\u{301}charts/{z}\u{301}/{x}/{y}") != nil)
        #expect(live(root: "https://api.test//")?.tiles == "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp")
    }

    @Test func aBoxMapLibreWouldRefuseIsNoBuildAndARegionOfThatKindIsLeftOut() {
        #expect(live(bounds: [-88.0, 32.0, -72.0]) == nil)
        #expect(live(bounds: [-72.0, 32.0, -88.0, 40.0]) == nil)  // west past east
        #expect(live(bounds: [-88.0, 40.0, -72.0, 32.0]) == nil)  // south past north
        #expect(live(bounds: [-88.0, -95.0, -72.0, 40.0]) == nil)
        #expect(live(bounds: [-188.0, 32.0, -72.0, 40.0]) == nil)
        #expect(live(bounds: [.nan, 32.0, -72.0, 40.0]) == nil)
        #expect(
            live(fallback: [[1.0], [165.9, 12.9, 180.0, 21.9], [.infinity, 0.0, 1.0, 1.0]])?.fallback
                == [ChartBox(165.9, 12.9, 180.0, 21.9)])
    }

    func chart(
        edit: String = "\"start\":\"2026-10-29\",\"end\":\"2026-12-23\",\"expires\":\"2026-12-24T09:01Z\"",
        ring: String = "[[-88,32],[-72,32],[-72,40],[-88,32]]"
    ) -> String {
        """
        {"title":"Atlanta Sectional","tier":"sectional","scale":500000,"edition":{\(edit)},"shownFrom":5,"nativeLevel":10.58,\
        "outline":{"type":"Polygon","coordinates":[\(ring)]}}
        """
    }

    @Test func aCoverageIsReadAndAChartThatCannotBeIsLeftOutOfIt() throws {
        let soon = chart(edit: "\"start\":\"soon\",\"end\":\"2026-12-23\",\"expires\":\"2026-12-24T09:01Z\"")
        let text =
            "{\"build\":\"b\",\"minLevel\":5,\"charts\":[\(chart()),\(soon),\(chart(ring: "[[-88,32],\"x\"]")),"
            + "\(chart(ring: "[[-88,32],[-72,32]]")),7]}"
        let coverage = try #require(ChartCoverage.parse(text))
        #expect(coverage.charts.count == 1)
        #expect(coverage.charts[0].ring[2] == ChartPoint(lon: -72.0, lat: 40.0))
        #expect(coverage.charts[0].nativeLevel == 10.58)
    }

    @Test func whatCannotBeACoverageIsNone() {
        #expect(ChartCoverage.parse("not json") == nil)
        #expect(ChartCoverage.parse("[]") == nil)
        #expect(ChartCoverage.parse("{\"build\":\"b\",\"charts\":[]}") == nil)
        #expect(ChartCoverage.parse("{\"build\":\"b\",\"minLevel\":5.5,\"charts\":[]}") == nil)
        #expect(ChartCoverage.parse("{\"build\":\"b\",\"minLevel\":\"5\",\"charts\":[]}") == nil)
        #expect(ChartCoverage.parse("{\"build\":\"b\",\"minLevel\":5,\"charts\":{}}") == nil)
        #expect(ChartCoverage.parse("{\"minLevel\":5,\"charts\":[]}") == nil)
    }

    /// A printed date is ASCII digits: `\d` in Swift Regex would take every script's.
    @Test func anEditionsDatesAreAsciiDigitsAndAMonthOfTheYear() {
        func edition(_ start: String) -> String {
            "{\"build\":\"b\",\"minLevel\":5,\"charts\":[\(chart(edit: "\"start\":\"\(start)\",\"end\":\"2026-12-23\",\"expires\":\"2026-12-24T09:01Z\""))]}"
        }
        #expect(ChartCoverage.parse(edition("2026-10-29"))?.charts.count == 1)
        #expect(ChartCoverage.parse(edition("2026-13-29"))?.charts.count == 0)
        #expect(ChartCoverage.parse(edition("2026-00-29"))?.charts.count == 0)
        #expect(ChartCoverage.parse(edition("٢٠٢٦-10-29"))?.charts.count == 0)
        #expect(ChartCoverage.parse(edition("2026-10-29 "))?.charts.count == 0)
    }

    // -- chartInstant, as Java's OffsetDateTime.parse reads a time ---------------------------------------------------------------

    /// The milliseconds java.time gives for each, worked out by hand from the epoch.
    @Test func aTimeAsTheServerWritesItIsItsInstant() {
        #expect(chartInstant("2026-12-24T09:01Z") == 1_798_102_860_000)
        #expect(chartInstant("1970-01-01T00:00Z") == 0)
        #expect(chartInstant("1970-01-01T00:00:00.001Z") == 1)
        #expect(chartInstant("1969-12-31T23:59:59.999Z") == -1)
        #expect(chartInstant("2026-12-24T09:01:30Z") == 1_798_102_890_000)
        #expect(chartInstant("2026-12-24T09:01:30.123456789Z") == 1_798_102_890_123)
        #expect(chartInstant("2026-12-24T10:01+01:00") == 1_798_102_860_000)
        #expect(chartInstant("2026-12-24T04:01-05:00") == 1_798_102_860_000)
        #expect(chartInstant("2026-12-24T10:01+01") == 1_798_102_860_000)  // the offset is read leniently
        #expect(chartInstant("2026-12-24T09:01:30+00:00:30") == 1_798_102_860_000)
        #expect(chartInstant("2026-12-24t09:01z") == 1_798_102_860_000)  // ISO_LOCAL_DATE_TIME is case-insensitive
        #expect(chartInstant("2026-12-24T09:01:00.Z") == 1_798_102_860_000)  // a decimal point with no digits
        #expect(chartInstant("2024-02-29T00:00Z") == 1_709_164_800_000)
        #expect(chartInstant("0001-01-01T00:00Z") == -62_135_596_800_000)  // proleptic Gregorian, never Julian
        #expect(chartInstant("+10000-01-01T00:00Z") == 253_402_300_800_000)
    }

    @Test func whatJavaRefusesIsNoInstant() {
        for text in [
            "", "next week", "2026-12-24", "2026-12-24T09:01", "2026-12-24T09:01:00", "2026-12-24 09:01Z", "2026-12-24T9:01Z",
            "2026-12-24T09:1Z", "2026-12-24T24:00Z", "2026-12-24T09:60Z", "2026-12-24T09:01:60Z", "2026-02-29T00:00Z",
            "2026-04-31T00:00Z", "2026-13-01T00:00Z", "2026-00-01T00:00Z", "2026-12-00T00:00Z", "26-12-24T09:01Z",
            "20260-12-24T09:01Z", "+2026-12-24T09:01Z", "-0000-12-24T09:01Z", "2026-12-24T09:01:00.1234567890Z",
            "2026-12-24T09:01+0100", "2026-12-24T09:01+1", "2026-12-24T09:01+19:00", "2026-12-24T09:01+18:01",
            "2026-12-24T09:01+01:60", "2026-12-24T09:01Z ", " 2026-12-24T09:01Z", "2026-12-24T09:01ZZ", "٢٠٢٦-12-24T09:01Z",
            "2026-12-24T09:01UTC", "2026-12-24T09:01+01:",
        ] {
            #expect(chartInstant(text) == nil, "\(text)")
        }
        #expect(chartInstant("2026-12-24T09:01+18:00") != nil)
        #expect(chartInstant("-0001-12-31T00:00Z") != nil)
    }

    /// **Not like Kotlin**, where `toEpochMilli` throws an ArithmeticException nothing catches: an instant past a 64-bit count of
    /// milliseconds is no instant.
    @Test func anInstantPastSixtyFourBitsOfMillisecondsIsNone() {
        #expect(chartInstant("+999999999-12-31T23:59Z") == nil)
        #expect(chartInstant("+1000000000-01-01T00:00Z") == nil)  // past YEAR's own range too
        #expect(chartInstant("+200000000-01-01T00:00Z") != nil)
    }
}
