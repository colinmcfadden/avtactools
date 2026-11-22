import Foundation
import Testing

@testable import CoreGeo

/// Android's MapZoomTest, case for case.
struct MapZoomTests {
    @Test func aSpanThatFillsTheViewAtAZoomComesBackAsThatZoom() {
        // At zoom 12 and 34 degrees north a point is 78271.5 * cos(34) / 4096 m across; 320 of them is the span that fills the view.
        let metersPerPoint = 78_271.516964 * cos(34.0 * Double.pi / 180) / 4096
        #expect(abs(MapZoom.fitting(34.0, metersPerPoint * MapZoom.viewPoints) - 12.0) <= 1e-9)
    }

    @Test func aLongerSpanIsASmallerZoomAHalvingOfTheSpanOneWholeZoomMore() {
        let wide = MapZoom.fitting(34.0, 40_000.0)
        let half = MapZoom.fitting(34.0, 20_000.0)
        #expect(abs(half - wide - 1.0) <= 1e-9)
    }

    @Test func nearerThePoleTheSameSpanNeedsACloserZoomBecauseADegreeIsShorter() {
        #expect(MapZoom.fitting(60.0, 10_000.0) < MapZoom.fitting(0.0, 10_000.0))
    }

    @Test func itStaysBetweenTheLowestZoomAndADiagrams() {
        #expect(MapZoom.fitting(0.0, 40_000_000.0) == MapZoom.min)
        #expect(MapZoom.fitting(34.0, 5.0) == MapZoom.max)
    }

    @Test func aSpanWithNoLengthOrNotANumberIsTheClosestZoom() {
        #expect(MapZoom.fitting(34.0, 0.0) == MapZoom.max)
        #expect(MapZoom.fitting(34.0, -3.0) == MapZoom.max)
        #expect(MapZoom.fitting(34.0, .nan) == MapZoom.max)
        #expect(MapZoom.fitting(34.0, .infinity) == MapZoom.max)
    }

    @Test func aLatitudeAtOrPastThePoleDoesNotBreakIt() {
        #expect(MapZoom.fitting(90.0, 10_000.0).isFinite)
        #expect(MapZoom.fitting(-90.0, 10_000.0).isFinite)
    }
}
