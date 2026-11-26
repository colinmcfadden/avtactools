import CoreJS
import CoreModel
import CoreTesting
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/backstops.json, which the web's own backstops.js writes. Places are written there to a
/// billionth of a degree and distances to a millionth of a km, so the fixture does not depend on which machine's sine wrote it.
struct BackstopsTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("planning/backstops.json") }

    /// `Number(value.toFixed(digits))`, as the fixture rounds.
    func rounded(_ value: Double, _ digits: Int) -> Double { JsNumber.parse(JsNumber.toFixed(value, digits)) }

    func expectPlace(_ actual: LatLon, _ expected: JSONTree?, _ what: String) {
        #expect(rounded(actual.lat, 9) == expected?["lat"]?.numberValue?.value, "\(what) lat")
        #expect(rounded(actual.lon, 9) == expected?["lon"]?.numberValue?.value, "\(what) lon")
    }

    @Test func everyReadingIsTheWebs() throws {
        let target = fixture["target"]
        let readings = try #require(fixture["readings"]?.arrayValue)
        #expect(readings.count == 7)
        for c in readings {
            let name = c["name"]!.stringValue!
            let arrow = try #require(Backstops.arrow(c, target: target), "\(name)")
            expectPlace(arrow.tail, c["tail"], "\(name) tail")
            expectPlace(arrow.tip, c["tip"], "\(name) tip")
            #expect(arrow.onTarget == c["onTarget"]?.boolValue, "\(name)")
            let reading = try #require(Backstops.reading(arrow))
            #expect(Double(reading.azimuth) == c["azimuth"]?.numberValue?.value, "\(name)")
            #expect(rounded(reading.distanceKm, 6) == c["distanceKm"]?.numberValue?.value, "\(name)")
            #expect(reading.text == c["text"]?.stringValue, "\(name)")
        }
    }

    @Test func everyMoveIsTheWebs() throws {
        let target = fixture["target"]
        for c in try #require(fixture["moves"]?.arrayValue) {
            let arrow = try #require(Backstops.arrow(c, target: target))
            let moved = Backstops.move(arrow, dLat: c["dLat"]!.numberValue!.value, dLon: c["dLon"]!.numberValue!.value)
            expectPlace(moved.tail, c["result"]?["tail"], "tail")
            expectPlace(moved.tip, c["result"]?["tip"], "tip")
        }
    }

    @Test func aTipDroppedNearTheTargetGoesBackOntoItAndATooShortArrowIsRefused() {
        let target = JSONTree.array([.number(34.7838), .number(-84.0822)])
        let arrow = Backstops.Arrow(tail: LatLon(lat: 34.82, lon: -84.1), tip: LatLon(lat: 34.8, lon: -84.05), onTarget: false)
        let at = LatLon(lat: 34.79, lon: -84.08)
        let farFromBoth: (LatLon) -> Double = { _ in 100 }
        #expect(Backstops.aim(arrow, at: at, target: target, pointsTo: farFromBoth) == Backstops.Aimed(tail: arrow.tail, tip: at))
        // The target 20 points away is near enough; 20.5 is not.
        let nearTarget: (Double) -> (LatLon) -> Double = { d in { $0 == arrow.tail ? 100 : d } }
        #expect(Backstops.aim(arrow, at: at, target: target, pointsTo: nearTarget(20))?.tip == nil)
        #expect(Backstops.aim(arrow, at: at, target: target, pointsTo: nearTarget(20.5))?.tip == at)
        // The tail under 24 points away: refused, whatever the target.
        let shortArrow: (LatLon) -> Double = { $0 == arrow.tail ? 23.9 : 100 }
        #expect(Backstops.aim(arrow, at: at, target: target, pointsTo: shortArrow) == nil)
        #expect(Backstops.aim(arrow, at: at, target: target, pointsTo: { $0 == arrow.tail ? 24 : 100 }) != nil)
    }

    @Test func onlyNumbersArePlacesAndNoTargetMeansNoArrow() throws {
        let backstop = try JSONTree.parse(#"{"points": [{"lat": "34.8", "lon": -84.09}, {"lat": 34.8, "lon": -84.07}, null, 7]}"#)
        #expect(Backstops.points(backstop) == [LatLon(lat: 34.8, lon: -84.07)])
        // A target read with Number(): text that reads as a number is a place; an empty list, or text that does not, is none.
        #expect(Backstops.arrow(backstop, target: try JSONTree.parse(#"["34.78", "-84.08"]"#))?.tip == LatLon(lat: 34.78, lon: -84.08))
        #expect(Backstops.arrow(backstop, target: try JSONTree.parse("[]")) == nil)
        #expect(Backstops.arrow(backstop, target: try JSONTree.parse(#"["north", 1]"#)) == nil)
        #expect(Backstops.arrow(backstop, target: nil) == nil)
        #expect(Backstops.arrow(try JSONTree.parse(#"{"points": []}"#), target: try JSONTree.parse("[34, -84]")) == nil)
    }

    @Test func aNewBackstopIsItsIdAndItsPointsInTheWebsOrder() {
        let made = Backstops.create(points: [LatLon(lat: 34.8, lon: -84.09)], id: "bs-1")
        #expect(made.serialized == #"{"id":"bs-1","points":[{"lat":34.8,"lon":-84.09}]}"#)
    }

    @Test func anAzimuthThatRoundsTo360IsWrittenAs000() {
        // Due north by a hair less than a degree's half west: the bearing is 359.6, rounded 360, written 0.
        let arrow = Backstops.Arrow(tail: LatLon(lat: 34, lon: -84), tip: LatLon(lat: 35, lon: -84.007), onTarget: false)
        let reading = Backstops.reading(arrow)!
        #expect(reading.azimuth == 0)
        #expect(reading.text.hasPrefix("000°T · "))
    }
}
