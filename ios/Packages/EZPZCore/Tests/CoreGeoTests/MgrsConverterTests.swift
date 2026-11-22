import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CoreGeo

/// Held to contracts/fixtures/mgrs, computed by PyGeodesy: the library behind the server's /api/convert-to-mgrs and
/// /api/convert-grid, which this replaces on the device. Android's MgrsConverterTest, case for case.
struct MgrsConverterTests {
    let forward: JSONObject
    let inverse: JSONObject

    init() throws {
        forward = try Fixtures.load("mgrs/forward.json")
        inverse = try Fixtures.load("mgrs/inverse.json")
    }

    @Test func forwardMatchesPyGeodesyAtEveryPoint() {
        let cases = forward["cases"]!.array
        #expect(cases.count > 5000, "the fixture shrank to \(cases.count)")
        let misses = cases.compactMap { c -> String? in
            let actual = MgrsConverter.toMgrs(c[0]!.double, c[1]!.double)?.format()
            return actual == c[2]!.string ? nil : "\(c[0]!.double), \(c[1]!.double): \(actual ?? "nil") (PyGeodesy \(c[2]!.string))"
        }
        #expect(misses.isEmpty, "\(misses.count) of \(cases.count) differ: \(misses.prefix(10))")
    }

    @Test func forwardMatchesTheNamedEdgeCases() {
        for n in forward.cases("named") {
            let actual = MgrsConverter.toMgrs(n["lat"]!.double, n["lon"]!.double)
            #expect(actual?.format() == n["mgrs"]!.string, "\(n["name"]!.string)")
        }
    }

    @Test func polarCapsHaveNoAnswer() {
        for p in forward["noAnswer"]!.array {
            #expect(MgrsConverter.toMgrs(p[0]!.double, p[1]!.double) == nil, "\(p[0]!.double), \(p[1]!.double)")
        }
    }

    @Test func aMissingOrNonFiniteCoordinateHasNoAnswer() {
        #expect(MgrsConverter.toMgrs(.nan, -84.0) == nil)
        #expect(MgrsConverter.toMgrs(34.5, .infinity) == nil)
    }

    @Test func coarserGridsTruncateRatherThanRound() {
        // 66993 / 52949 m name the 10 m square 6699 / 5294, not 6700 / 5295.
        #expect(MgrsConverter.toMgrs(34.783817, -84.08219, digits: 4)?.format() == "16S GD 6699 5294")
    }

    @Test func aLongitudeCarriedPastTheAntimeridianWraps() {
        #expect(MgrsConverter.toMgrs(52.0, -179.9999) == MgrsConverter.toMgrs(52.0, 180.0001))
        #expect(MgrsConverter.toMgrs(34.783817, -84.08219 + 360)?.format() == "16S GD 66993 52949")
    }

    @Test func inverseMatchesPyGeodesyAtEveryGrid() {
        let tolerance = inverse["toleranceDeg"]!.double
        let cases = inverse["cases"]!.array
        #expect(cases.count > 3000, "the fixture shrank to \(cases.count)")
        let misses = cases.compactMap { c -> String? in
            let grid = c[0]!.string
            guard let actual = MgrsConverter.toLatLon(grid) else { return "\(grid): no answer" }
            if abs(actual.lat - c[1]!.double) > tolerance || abs(actual.lon - c[2]!.double) > tolerance {
                return "\(grid): \(actual.lat), \(actual.lon) (PyGeodesy \(c[1]!.double), \(c[2]!.double))"
            }
            return nil
        }
        #expect(misses.isEmpty, "\(misses.count) of \(cases.count) differ: \(misses.prefix(10))")
    }

    @Test func gridsTheServerRefusesHaveNoAnswer() {
        for grid in inverse["invalid"]!.array.map(\.string) {
            #expect(MgrsConverter.toLatLon(grid) == nil, "'\(grid)' should be refused")
        }
    }

    @Test func aPointSurvivesTheRoundTripToTheMetre() throws {
        // forward truncates to the square's corner and inverse returns its centre, so a 1 m grid comes back within the half-diagonal
        // of that square.
        let grid = try #require(MgrsConverter.toMgrs(34.783817, -84.08219))
        let back = try #require(MgrsConverter.toLatLon(grid), "no answer for \(grid)")
        #expect(abs(back.lat - 34.783817) < 0.00001 && abs(back.lon - -84.08219) < 0.00001)
        #expect(MgrsConverter.toMgrs(back.lat, back.lon) == grid)
    }

    @Test func parseStripsKotlinsWhitespaceAndReadsOnlyAsciiDigits() {
        // Kotlin's isWhitespace takes U+001C and the no-break space, not the byte order mark; the digits are ASCII alone.
        #expect(MgrsConverter.parse("16S\u{1C}GD\u{A0}6699352949")?.format() == "16S GD 66993 52949")
        #expect(MgrsConverter.parse("16S GD\u{FEFF}6699352949") == nil)
        #expect(MgrsConverter.parse("\u{0661}\u{0666}SGD6699352949") == nil)
        #expect(MgrsConverter.parse("16sgd 669 529")?.format() == "16S GD 669 529")
        #expect(MgrsConverter.parse("16S GD 669 52") == nil)
        #expect(MgrsConverter.parse("61S GD 66 52") == nil)
    }
}
