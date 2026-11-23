import Testing

@testable import CoreMap

/// The decisions Android's ChartLooksTest holds through its coroutine, held here as the plain functions they are.
struct ChartLooksTests {
    let live = VfrLive(
        build: "b", tiles: "", coverage: "", tileSize: 512, minZoom: 5, maxZoom: 14, effective: "2026-10-29T09:01Z",
        expires: "2026-12-24T09:01Z", bounds: ChartBox(-125.0, 24.0, -66.0, 50.0), fallback: [])

    @Test func theFirstTimeItShowsItLooksAndBackWithinFiveMinutesItDoesNot() {
        #expect(ChartLooks.looksOnShow(lookedAt: 0, now: ChartLooks.lookEveryMs, charts: VfrCharts()))
        #expect(!ChartLooks.looksOnShow(lookedAt: 1_000, now: 1_000 + ChartLooks.lookEveryMs - 1, charts: VfrCharts()))
        #expect(ChartLooks.looksOnShow(lookedAt: 1_000, now: 1_000 + ChartLooks.lookEveryMs, charts: VfrCharts()))
    }

    @Test func backOnTheMapWithNoCoverageForThisBuildItLooksAtOnce() {
        let other = ChartCoverage(build: "a", minLevel: 5, charts: [])
        let same = ChartCoverage(build: "b", minLevel: 5, charts: [])
        #expect(ChartLooks.looksOnShow(lookedAt: 1_000, now: 2_000, charts: VfrCharts(live: live)))
        #expect(ChartLooks.looksOnShow(lookedAt: 1_000, now: 2_000, charts: VfrCharts(live: live, coverage: other)))
        #expect(!ChartLooks.looksOnShow(lookedAt: 1_000, now: 2_000, charts: VfrCharts(live: live, coverage: same)))
    }

    @Test func aFailedTileLooksOnceAMinuteAtMost() {
        #expect(!ChartLooks.looksAfterFailedTile(lookedAt: 1_000, now: 1_000 + ChartLooks.afterAFailedTileMs - 1))
        #expect(ChartLooks.looksAfterFailedTile(lookedAt: 1_000, now: 1_000 + ChartLooks.afterAFailedTileMs))
    }

    @Test func itLooksJustAfterTheLiveBuildRunsOutAndNotAtThatVeryMoment() {
        #expect(ChartLooks.nextLookIn(expires: nil, now: 0) == ChartLooks.lookEveryMs)
        #expect(ChartLooks.nextLookIn(expires: 60_000, now: 0) == 60_000 + ChartLooks.pastExpiryMs)
        #expect(ChartLooks.nextLookIn(expires: 10 * 60_000, now: 0) == ChartLooks.lookEveryMs)
    }

    @Test func aBuildAlreadyRunOutDoesNotHurryItEveryFiveMinutesAsBefore() {
        #expect(ChartLooks.nextLookIn(expires: 0, now: ChartLooks.pastExpiryMs) == ChartLooks.lookEveryMs)
        #expect(ChartLooks.nextLookIn(expires: 0, now: ChartLooks.pastExpiryMs - 1) == 1)
    }
}
