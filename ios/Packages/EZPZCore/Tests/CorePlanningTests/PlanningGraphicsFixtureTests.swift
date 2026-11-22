import CoreGeo
import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/graphics.json: the web's geometry, placement and separation alerts for the planning graphics.
struct PlanningGraphicsFixtureTests {
    let fixture: JSONObject
    let tolerance: Double
    let profiles: [AircraftProfile]
    let profileKeys: [String]

    init() throws {
        fixture = try Fixtures.load("planning/graphics.json")
        tolerance = fixture["tolerance"]!.numberValue!.value
        let entries = fixture["profiles"]!.arrayValue!
        profileKeys = entries.map { $0["key"]!.stringValue! }
        profiles = entries.map { AircraftProfile.normalize($0["profile"]!.objectValue!) }
    }

    func profile(_ key: String) -> AircraftProfile { profiles[profileKeys.firstIndex { same($0, key) }!] }
    func cases(_ key: String) -> [JSONTree] { Array(fixture[key]!.arrayValue!) }
    func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) <= tolerance * max(1, abs(a)) }
    func num(_ c: JSONTree, _ key: String) -> Double { c[key]!.numberValue!.value }
    func nullableNum(_ c: JSONTree, _ key: String) -> Double? { c[key]!.numberValue?.value }
    func point(_ e: JSONTree) -> LatLon { LatLon(lat: e[0]!.numberValue!.value, lon: e[1]!.numberValue!.value) }

    // MARK: Geometry

    @Test func distancesInFeetAreTheWebs() {
        let all = cases("distanceFeet")
        #expect(all.count >= 10)
        for c in all {
            let actual = GreatCircle.distanceFeet(num(c, "lat1"), num(c, "lon1"), num(c, "lat2"), num(c, "lon2"))
            #expect(close(num(c, "expected"), actual), "\(c): \(num(c, "expected")) vs \(actual)")
        }
    }

    @Test func theLineBetweenTwoRotorEdgesAndTheGapOnItAreTheWebs() {
        for c in cases("rotorEdge") {
            let edge = PlanningGraphics.rotorEdge(
                LatLon(lat: num(c, "lat1"), lon: num(c, "lon1")), LatLon(lat: num(c, "lat2"), lon: num(c, "lon2")),
                nullableNum(c, "radius1Ft"),
                nullableNum(c, "radius2Ft"))
            let expected = c["expected"]!
            let label = c["name"]!.stringValue!
            let start = point(expected["start"]!)
            let end = point(expected["end"]!)
            #expect(close(start.lat, edge.start.lat) && close(start.lon, edge.start.lon), "\(label) start")
            #expect(close(end.lat, edge.end.lat) && close(end.lon, edge.end.lon), "\(label) end")
            #expect(close(num(expected, "edgeDist"), edge.edgeDistFt), "\(label) gap \(num(expected, "edgeDist")) vs \(edge.edgeDistFt)")
        }
    }

    @Test func theAngleOfARotateHandleAndWhereTheHandleSitsAreTheWebs() {
        for c in cases("angle") {
            let actual = PlanningGraphics.angleDeg(
                LatLon(lat: num(c, "cLat"), lon: num(c, "cLon")), LatLon(lat: num(c, "mLat"), lon: num(c, "mLon")))
            #expect(close(num(c, "expected"), actual), "\(c)")
        }
        for c in cases("handle") {
            let actual = PlanningGraphics.handlePosition(LatLon(lat: num(c, "lat"), lon: num(c, "lon")), num(c, "rotation"))
            let expected = point(c["expected"]!)
            #expect(close(expected.lat, actual.lat) && close(expected.lon, actual.lon), "\(c)")
        }
    }

    // MARK: Aircraft

    func aircraft(_ list: JSONTree) -> [PlanningGraphics.Aircraft] { list.arrayValue!.compactMap(PlanningGraphics.Aircraft.of) }

    func resolver(_ active: AircraftProfile) -> (PlanningGraphics.Aircraft) -> AircraftProfile {
        { [profiles] a in AircraftLookup.profileForAsset(a.profileRef, profiles, active) }
    }

    @Test func aNewAircraftIsPlacedAsTheWebPlacesIt() {
        let all = cases("place")
        #expect(all.count >= 8)
        for c in all {
            let active = profile(c["active"]!.stringValue!)
            let placed = PlanningGraphics.placeHelicopter(
                point(c["target"]!), aircraft(c["helicopters"]!), active, resolver(active), id: c["id"]!)
            let differences = JSONCompare.differences(c["expected"]!, .object(placed), tolerance: tolerance)
            #expect(differences.isEmpty, "\(c["name"]!.stringValue!): \(differences)")
        }
    }

    @Test func theAircraftThatAreTooCloseRaiseTheWebsAlertsInItsOrderAndWords() {
        for c in cases("alerts") {
            let active = profile(c["active"]!.stringValue!)
            let alerts = PlanningGraphics.separationAlerts(aircraft(c["helicopters"]!), resolver(active))
            let expected = c["expected"]!.arrayValue!
            let label = c["name"]!.stringValue!
            #expect(expected.map { $0["id"]!.stringValue! } == alerts.map(\.id), "\(label)")
            #expect(expected.map { $0["message"]!.stringValue! } == alerts.map(\.message), "\(label)")
        }
    }

    // MARK: The other graphics

    /// JavaScript's `parseFloat(x)`, for the shapes the cases use: null and absent are NaN.
    func jsParseFloat(_ e: JSONTree?) -> Double {
        switch e {
        case .string(let s)?: JsNumber.parseFloat(s)
        case .number(let n)?: n.value
        default: .nan
        }
    }

    @Test func aPZMarkerStartsAsTheWebStartsItAndIsRefusedWhereTheWebWouldMakeOneOfNaN() {
        for c in cases("pzMarker") {
            let target = c["target"]!
            let made = PlanningGraphics.createPzMarker(jsParseFloat(target[0]), jsParseFloat(target[1]), id: c["id"]!.stringValue!)
            let expected = c["expected"]!
            if expected.objectValue!.contains(where: { $0.value.isNull }) {
                #expect(made == nil, "\(target)")
            } else if let made {
                let differences = JSONCompare.differences(expected, .object(made), tolerance: tolerance)
                #expect(differences.isEmpty, "\(target): \(differences)")
            } else {
                Issue.record("\(target): no marker")
            }
        }
    }

    @Test func aSectorOfFireStartsAsTheWebStartsIt() {
        for c in cases("sectorOfFire") {
            let target = c["target"]!
            // JavaScript's `Number(x)`: an absent value is NaN, null is 0, text is read whole.
            let made = PlanningGraphics.createSectorOfFire(JsNumber.of(target[0]), JsNumber.of(target[1]), id: c["id"]!.stringValue!)
            expectMade(c["expected"]!, made, "\(target)")
        }
    }

    @Test func aGoAroundStartsAsTheWebStartsItSouthUnlessItWasSpelledN() {
        for c in cases("goAround") {
            let target = c["target"]!
            let direction = c["direction"]!.stringValue
            let made = PlanningGraphics.createGoAround(JsNumber.of(target[0]), JsNumber.of(target[1]), direction, id: c["id"]!.stringValue!)
            expectMade(c["expected"]!, made, "\(String(describing: direction)) \(target)")
        }
    }

    func expectMade(_ expected: JSONTree, _ made: JSONObject?, _ label: String) {
        if expected.isNull {
            #expect(made == nil, "\(label)")
        } else if let made {
            let differences = JSONCompare.differences(expected, .object(made), tolerance: tolerance)
            #expect(differences.isEmpty, "\(label): \(differences)")
        } else {
            Issue.record("\(label): nothing made")
        }
    }

    @Test func aSavedAircraftWithNoPositionIsLeftOutOfTheMeasuring() {
        #expect(PlanningGraphics.Aircraft.of(.object(JSONObject([("id", .number(1))]))) == nil)
        #expect(PlanningGraphics.Aircraft.of(.string("x")) == nil)
        #expect(PlanningGraphics.Aircraft.of(.array([])) == nil)
    }
}
