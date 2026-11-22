import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/summary.json: what the web's mission summary says of a polygon and of a slope.
struct LzSummaryFixtureTests {
    let fixture: JSONObject
    let profiles: [(key: String, profile: AircraftProfile)]
    /// A `ground` table: each entry `[heading, noseHighMaxDeg, noseLowMaxDeg, crossSlopeMaxDeg]`.
    let grounds: [(name: String, maxDeg: Double, byHeading: [LzSummary.HeadingSlope])]

    init() throws {
        fixture = try Fixtures.load("planning/summary.json")
        profiles = try Fixtures.load("planning/aircraft.json")["profiles"]!.arrayValue!.map {
            ($0["key"]!.stringValue!, AircraftProfile.normalize($0["profile"]!.objectValue!))
        }
        grounds = fixture["ground"]!.arrayValue!.map { g in
            let table = g["byHeading"]!.arrayValue!.map { e in
                LzSummary.HeadingSlope(Self.headingOf(e[0]), Self.number(e[1]), Self.number(e[2]), Self.number(e[3]))
            }
            return (g["name"]!.stringValue!, g["maxDeg"]!.numberValue!.value, table)
        }
    }

    func ground(_ name: String) -> (maxDeg: Double, byHeading: [LzSummary.HeadingSlope]) {
        let g = grounds.first { same($0.name, name) }!
        return (g.maxDeg, g.byHeading)
    }

    func polygon(_ element: JSONTree) -> [LatLon]? {
        element.arrayValue?.map { p in LatLon(lat: p[0]!.numberValue!.value, lon: p[1]!.numberValue!.value) }
    }

    /// kotlinx's `intOrNull`: a primitive whose text is a whole number.
    static func intOf(_ element: JSONTree?) -> Int? { primitiveText(element).flatMap { Int($0) } }

    // What the server sent as a number, or NaN: a slope sent as text is not one (the web's typeof check).
    static func number(_ element: JSONTree?) -> Double { element?.numberValue?.value ?? .nan }

    // A heading that is not a whole number is no heading (-1, which the table refuses as the web's Number.isInteger does).
    static func headingOf(_ element: JSONTree?) -> Int {
        guard let value = element?.numberValue?.value, value == value.rounded(.down), (-1e9...1e9).contains(value) else { return -1 }
        return Int(value)
    }

    func wind(_ element: JSONTree?) -> LzSummary.SlopeWind? {
        guard let o = element, o.objectValue != nil else { return nil }
        return LzSummary.SlopeWind(o["speedKts"]!.numberValue!.value, Self.intOf(o["fromDeg"]), o["variable"]!.boolValue!)
    }

    func limits(_ expected: JSONTree) -> LzSummary.SlopeLimits {
        LzSummary.SlopeLimits(
            noseUp: Self.intOf(expected["noseUp"])!, noseDown: Self.intOf(expected["noseDown"])!, cross: Self.intOf(expected["cross"])!,
            reductionDeg: Self.intOf(expected["reductionDeg"])!, noseDownProhibited: expected["noseDownProhibited"]!.boolValue!)
    }

    @Test func areaAndCapacityAreWhatTheWebComputes() {
        let cases = fixture["area"]!.arrayValue!
        #expect(cases.count >= 40)
        for c in cases {
            let key = c["profile"]!.stringValue
            let profile = key.map { k in profiles.first { same($0.key, k) }!.profile } ?? .fallback
            let expected = c["expected"]!
            let actual = LzSummary.areaAndCapacity(polygon(c["polygon"]!), profile)
            let label = "\(c["name"]!.stringValue!) / \(key ?? "default")"
            // The area's last digit can differ where the platform's sine differs in its last bit: a square foot in a million is not a
            // disagreement.
            let expectedArea = expected["area"]!.numberValue!.value
            #expect(abs(expectedArea - Double(actual.areaSqFt)) <= max(1, expectedArea * 1e-9), "\(label)")
            #expect(Self.intOf(expected["heloCount"]) == actual.capacity, "\(label)")
        }
    }

    @Test func theLimitsForAWindAreTheWebs() {
        let cases = fixture["windLimits"]!.arrayValue!
        #expect(cases.count >= 15)
        for c in cases { #expect(limits(c["expected"]!) == LzSummary.limitsFor(wind(c["wind"])), "\(c)") }
    }

    @Test func theLandingHeadingIsReadAsTheWebReadsIt() {
        let cases = fixture["landingHeading"]!.arrayValue!
        #expect(cases.count >= 20)
        for c in cases { #expect(Self.intOf(c["expected"]) == Doghouses.landingHeadingOf(c["landingHdg"]), "\(c)") }
    }

    @Test func theSlopeTileMakesTheSameCallAsTheWeb() {
        let cases = fixture["slope"]!.arrayValue!
        #expect(cases.count >= 40)
        for c in cases {
            let label = c["name"]!.stringValue!
            let maxDeg: Double?
            let byHeading: [LzSummary.HeadingSlope]?
            if let name = c["ground"]?.stringValue {
                (maxDeg, byHeading) = ground(name)
            } else {
                let data = c["terrainData"]?.objectValue
                let stats = data?["stats"]?.objectValue
                byHeading = data?["byHeading"]?.arrayValue?.map { o in
                    LzSummary.HeadingSlope(
                        Self.headingOf(o["heading"]), Self.number(o["noseHighMaxDeg"]), Self.number(o["noseLowMaxDeg"]),
                        Self.number(o["crossSlopeMaxDeg"]))
                }
                maxDeg = finiteNumber(stats?["maxDeg"])
            }
            let call = LzSummary.slopeCall(maxDeg, byHeading, Doghouses.landingHeadingOf(c["landingHdg"]), wind(c["wind"]))
            let expected = c["expected"]!
            #expect(expected["label"]!.stringValue! == call.label, "\(label)")
            #expect(expected["className"]!.stringValue! == "status-" + call.level.rawValue, "\(label)")
            #expect(expected["max"]!.numberValue!.value == call.maxDeg, "\(label)")
            #expect(Self.intOf(expected["heading"]) == call.heading, "\(label)")
            #expect(limits(expected["limits"]!) == call.limits, "\(label)")
            #expect(expected["lines"]!.arrayValue!.map { $0.stringValue! } == call.lines, "\(label)")
        }
    }

    @Test func aWindIsTakenFromTheWeatherReportTheTilesShow() {
        func report(_ speed: Double?, _ from: Int?, variable: Bool = false) -> WeatherObservation {
            WeatherObservation(
                stationId: "KRYY", stationName: "Cartersville", distanceMiles: 4, windFromDegrees: from, windVariable: variable,
                windSpeedKt: speed, windGustKt: 25, tempC: nil, dewpointC: nil, altimeterInHg: nil, visibility: nil, flightCategory: nil,
                rawReport: nil)
        }
        #expect(LzSummary.slopeWindOf(nil) == nil)
        #expect(LzSummary.slopeWindOf(report(nil, 270)) == nil, "no speed is no report")
        #expect(LzSummary.slopeWindOf(report(.nan, 270)) == nil)
        #expect(LzSummary.slopeWindOf(report(-1, 270)) == nil)
        #expect(LzSummary.slopeWindOf(report(12, 270)) == LzSummary.SlopeWind(12, 270, false), "the steady wind, not the gust")
        #expect(LzSummary.slopeWindOf(report(12, 360)) == LzSummary.SlopeWind(12, 0, false), "360 is north")
        #expect(LzSummary.slopeWindOf(report(7, nil, variable: true)) == LzSummary.SlopeWind(7, nil, true))
    }

    @Test func aHeadingOffTheCompassIsBroughtBackOntoIt() {
        let plane = ground("a plane of 8° rising east").byHeading
        let call = LzSummary.slopeCall(8, plane, 450, nil)
        #expect(call.heading == 90)
        #expect(LzSummary.slopeCall(8, plane, 90, nil) == call)
        #expect(
            LzSummary.slopeCall(8, plane, 0, LzSummary.SlopeWind(10, 0, false))
                == LzSummary.slopeCall(8, plane, -360, LzSummary.SlopeWind(10, 720, false)))
    }

    @Test func capacityIsCountedFromTheAreaAsItIsNotAsItIsShown() {
        // The tile shows the area rounded to a square foot; the web counts aircraft from the unrounded one. Find an aircraft whose spot
        // size puts the unrounded area just under a whole number of spots and the rounded one over it (or the reverse), so the two
        // readings differ.
        let polygon = [
            LatLon(lat: 34.7800, lon: -84.0900), LatLon(lat: 34.7800, lon: -84.0866), LatLon(lat: 34.7777, lon: -84.0866),
            LatLon(lat: 34.7777, lon: -84.0900),
        ]
        let exact = LzSummary.polygonAreaSqFt(polygon)
        let shown = (exact + 0.5).rounded(.down)
        #expect(shown != exact, "this polygon's area is not already a whole number")
        let k = 3
        let spot = (exact + (shown - exact) / 2) / Double(k)
        var aircraft = AircraftProfile.fallback
        aircraft.rotorDiameterM = spot.squareRoot() / Units.metersToFeet
        aircraft.rotorTipClearanceM = 0
        // Rounded up, the shown area reaches k spots but the real one falls just short of them; rounded down, the real one holds k and
        // the shown one does not.
        let expected = shown > exact ? k - 1 : k
        #expect(LzSummary.areaAndCapacity(polygon, aircraft).capacity == expected)
    }
}
