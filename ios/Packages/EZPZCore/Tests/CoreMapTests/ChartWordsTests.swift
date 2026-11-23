import CoreModel
import Foundation
import Testing

@testable import CoreMap

/// Android's ChartWordsTest: what `words.json` cannot say, its coverage being one edition and its views all in the lower 48: charts
/// of two editions, and a map panned past the date line. Then what the port has to do that Swift does otherwise by itself.
struct ChartWordsTests {
    let live = VfrLive(
        build: "b", tiles: "", coverage: "", tileSize: 512, minZoom: 5, maxZoom: 14, effective: "2026-10-29T09:01Z",
        expires: "2026-12-24T09:01Z", bounds: ChartBox(-125.0, 24.0, -66.0, 50.0), fallback: [])

    func edition(_ start: String, _ end: String, _ expires: String) -> ChartEdition {
        ChartEdition(start: start, end: end, expires: expires, expiresAt: chartInstant(expires)!)
    }

    var current: ChartEdition { edition("2026-10-29", "2026-12-23", "2026-12-24T09:01Z") }
    var heldBack: ChartEdition { edition("2026-09-03", "2026-10-28", "2026-10-29T09:01Z") }
    let now = chartInstant("2026-11-08T12:00Z")!

    func chart(
        _ title: String, _ scale: Double, _ edition: ChartEdition, _ west: Double, _ south: Double, _ east: Double, _ north: Double,
        tier: String = "sectional"
    ) -> CoverageChart {
        CoverageChart(
            title: title, tier: tier, scale: scale, edition: edition, shownFrom: 5, nativeLevel: 10.58,
            ring: [
                ChartPoint(lon: west, lat: south), ChartPoint(lon: east, lat: south), ChartPoint(lon: east, lat: north),
                ChartPoint(lon: west, lat: north), ChartPoint(lon: west, lat: south),
            ])
    }

    func coverage(_ charts: CoverageChart...) -> ChartCoverage { ChartCoverage(build: "b", minLevel: 5, charts: charts) }

    @Test func aChartRunOutIsSaidFirstFinerChartOrNot() {
        let words = ChartWords.of(
            live: live,
            coverage: coverage(
                chart("FINER", 250_000.0, current, -84.0, 33.0, -82.0, 35.0),
                chart("HELD BACK", 500_000.0, heldBack, -90.0, 30.0, -80.0, 38.0)),
            view: ChartBox(-85.0, 33.0, -83.0, 35.0), center: LatLon(lat: 34.0, lon: -84.0), zoom: 6.0, now: now)
        #expect(
            words.lines == [
                ChartLine("FAA HELD BACK · Expired 28 Oct 2026 — not current", true), ChartLine("FAA FINER, 29 Oct – 23 Dec 2026", false),
            ])
    }

    @Test func aViewAcrossTheDateLineReachesAChartOnItsFarSide() {
        let words = ChartWords.of(
            live: live, coverage: coverage(chart("ALEUTIAN", 500_000.0, current, -178.0, 50.0, -170.0, 54.0)),
            view: ChartBox(175.0, 51.0, 190.0, 53.0), center: LatLon(lat: 52.0, lon: 179.0), zoom: 6.0, now: now)
        #expect(words.lines == [ChartLine("FAA ALEUTIAN, 29 Oct – 23 Dec 2026", false)])
    }

    @Test func aMapPannedAWholeTurnRoundTheWorldSaysWhatItWouldHave() {
        let words = ChartWords.of(
            live: live, coverage: coverage(chart("ATLANTA", 500_000.0, current, -90.0, 30.0, -80.0, 38.0)),
            view: ChartBox(276.0, 33.0, 278.0, 35.0), center: LatLon(lat: 34.0, lon: 277.0), zoom: 15.0, now: now)
        #expect(words.lines == [ChartLine("FAA ATLANTA, 29 Oct – 23 Dec 2026", false)])
        #expect(words.enlarged == "Sectional enlarged 8× — no finer chart here")
    }

    /// Kotlin's `sortedBy` is stable, Swift's `sorted` makes no promise: charts of one scale are named in the order the coverage
    /// lists them, many enough that an unstable sort would show it.
    @Test func chartsOfOneScaleKeepTheCoveragesOrder() {
        let titles = (0..<40).map { "CHART \($0)" }
        let charts = titles.map { chart($0, 500_000.0, current, -90.0, 30.0, -80.0, 38.0) }
        let words = ChartWords.of(
            live: live, coverage: ChartCoverage(build: "b", minLevel: 5, charts: charts), view: ChartBox(-85.0, 33.0, -83.0, 35.0),
            center: LatLon(lat: 34.0, lon: -84.0), zoom: 6.0, now: now)
        #expect(words.lines == [ChartLine("FAA CHART 0, CHART 1 and 38 more, 29 Oct – 23 Dec 2026", false)])
    }

    @Test func beforeTheCoverageHasComeTheBuildsOwnEditionIsSaidItsEndTheDayBeforeItRunsOut() {
        let words = ChartWords.of(
            live: live, coverage: nil, view: ChartBox(-85.0, 33.0, -83.0, 35.0), center: LatLon(lat: 34.0, lon: -84.0), zoom: 6.0, now: now)
        #expect(words.lines == [ChartLine("FAA VFR charts, 29 Oct – 23 Dec 2026", false)])
        #expect(words.enlarged == nil)
        let late = ChartWords.of(
            live: live, coverage: nil, view: ChartBox(-85.0, 33.0, -83.0, 35.0), center: LatLon(lat: 34.0, lon: -84.0), zoom: 6.0,
            now: chartInstant("2026-12-24T09:01Z")!)
        #expect(late.lines == [ChartLine("FAA VFR charts · Expired 23 Dec 2026 — not current", true)])
    }

    @Test func anEditionAcrossTheNewYearNamesBothYears() {
        let words = ChartWords.of(
            live: live,
            coverage: coverage(
                chart("ATLANTA", 500_000.0, edition("2026-12-24", "2027-02-18", "2027-02-19T09:01Z"), -90.0, 30.0, -80.0, 38.0)),
            view: ChartBox(-85.0, 33.0, -83.0, 35.0), center: LatLon(lat: 34.0, lon: -84.0), zoom: 6.0, now: now)
        #expect(words.lines == [ChartLine("FAA ATLANTA, 24 Dec 2026 – 18 Feb 2027", false)])
    }

    /// Kotlin's `1L shl power` takes the count modulo 64, where Swift's `<<` past 63 gives 0: sixty-four levels past the chart's own
    /// is 1 << 0, and sixty-five 1 << 1.
    @Test func anEnlargementPastSixtyThreeLevelsWrapsAsKotlinsShiftDoes() {
        func enlarged(_ zoom: Double) -> String? {
            ChartWords.of(
                live: live, coverage: coverage(chart("ATLANTA", 500_000.0, current, -90.0, 30.0, -80.0, 38.0, tier: "tac")),
                view: ChartBox(-84.1, 33.9, -83.9, 34.1), center: LatLon(lat: 34.0, lon: -84.0), zoom: zoom, now: now
            ).enlarged
        }
        #expect(enlarged(74.58) == "TAC enlarged -9223372036854775808× — no finer chart here")  // Long.MIN_VALUE, as Kotlin writes it
        #expect(enlarged(75.58) == "TAC enlarged 1× — no finer chart here")
        #expect(enlarged(76.58) == "TAC enlarged 2× — no finer chart here")
    }
}
