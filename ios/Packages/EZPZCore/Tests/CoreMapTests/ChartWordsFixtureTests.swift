import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CoreMap

/// Android's ChartWordsFixtureTest: every case of `contracts/fixtures/charts/words.json`, over the same recorded `coverage.json` the
/// web's are held to.
struct ChartWordsFixtureTests {
    let fixture: JSONObject
    let coverage: ChartCoverage
    let cases: [JSONObject]
    let live: VfrLive

    init() throws {
        fixture = try Fixtures.load("charts/words.json")
        coverage = try #require(ChartCoverage.of(.object(try Fixtures.load("charts/coverage.json"))))
        cases = fixture["cases"]!.array.map(\.object)
        let recorded = fixture["live"]!.object
        live = VfrLive(
            build: recorded["build"]!.string, tiles: "", coverage: "", tileSize: 512, minZoom: 5, maxZoom: 14,
            effective: recorded["effective"]!.string, expires: recorded["expires"]!.string,
            bounds: try #require(ChartBox.of(recorded["bounds"]!.array.map(\.double))),
            fallback: try recorded["fallback"]!.array.map { box in try #require(ChartBox.of(box.array.map(\.double))) })
    }

    func said(_ c: JSONObject) throws -> ChartLines {
        let view = c["view"]!.object
        let center = c["center"]!.object
        return ChartWords.of(
            live: c["live"]!.bool ? live : nil,
            coverage: c["coverage"]!.bool ? coverage : nil,
            view: ChartBox(view["west"]!.double, view["south"]!.double, view["east"]!.double, view["north"]!.double),
            center: LatLon(lat: center["lat"]!.double, lon: center["lon"]!.double),
            zoom: c["zoom"]!.double,
            now: try #require(chartInstant(c["now"]!.string)),
            finer: c["finer"]?.int ?? 0)
    }

    func expected(_ c: JSONObject) -> ChartLines {
        let words = c["expected"]!.object
        return ChartLines(
            lines: words["lines"]!.array.map(\.object).map { ChartLine($0["text"]!.string, $0["expired"]!.bool) },
            enlarged: words["enlarged"]!.stringValue)
    }

    @Test func theWordsAreTheWebsInEveryCase() throws {
        #expect(cases.count >= 29)
        for c in cases {
            let got = try said(c)
            let want = expected(c)
            // Compared by UTF-16 units, as the fixture's text is JavaScript's.
            #expect(got.lines.map { Array($0.text.utf16) } == want.lines.map { Array($0.text.utf16) }, "\(c["name"]!.string)")
            #expect(got.lines.map(\.expired) == want.lines.map(\.expired), "\(c["name"]!.string)")
            #expect(got.enlarged.map { Array($0.utf16) } == want.enlarged.map { Array($0.utf16) }, "\(c["name"]!.string)")
        }
    }

    @Test func everyChartOfTheRecordedCoverageIsRead() throws {
        #expect(coverage.charts.count == (try Fixtures.load("charts/coverage.json"))["charts"]!.array.count)
        #expect(!coverage.charts.isEmpty)
    }

    @Test func theNextExpiryIsTheSoonestStillToComeAndNoneOnceAllHavePassed() throws {
        let first = coverage.charts.map(\.edition.expiresAt).min()!
        let last = max(coverage.charts.map(\.edition.expiresAt).max()!, try #require(chartInstant(live.expires)))
        #expect(ChartWords.nextExpiry(live: live, coverage: coverage, now: first - 1) == first)
        #expect(ChartWords.nextExpiry(live: live, coverage: coverage, now: last) == nil)
        #expect(ChartWords.nextExpiry(live: nil, coverage: nil, now: 0) == nil)
    }
}
