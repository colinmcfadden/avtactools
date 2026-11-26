import CoreGeo
import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/route.json, which records what the web's routeCalc.js answers. The plan this produces is
/// exported to AMPS, so these are the numbers a crew flies.
struct RouteFixtureTests {
    let fixture: JSONObject
    let tolerance: Double
    let profiles: [(key: String, profile: AircraftProfile)]
    let json = ModelJSON(ignoreUnknownKeys: true)

    init() throws {
        fixture = try Fixtures.load("planning/route.json")
        tolerance = fixture["tolerance"]!.numberValue!.value
        let aircraft = try Fixtures.load("planning/aircraft.json")
        profiles = aircraft["profiles"]!.arrayValue!.map {
            ($0["key"]!.stringValue!, AircraftProfile.normalize($0["profile"]!.objectValue!))
        }
    }

    func profile(_ key: String) -> AircraftProfile { profiles.first { same($0.key, key) }!.profile }

    func close(_ expected: Double, _ actual: Double) -> Bool { abs(expected - actual) <= tolerance * max(1, abs(expected)) }

    @Test func greatCircleDistanceAndCourseMatchTheWeb() {
        for c in fixture["greatCircle"]!.arrayValue! {
            func n(_ key: String) -> Double { c[key]!.numberValue!.value }
            let distance = GreatCircle.distanceNm(n("lat1"), n("lon1"), n("lat2"), n("lon2"))
            let course = GreatCircle.trueCourseDeg(n("lat1"), n("lon1"), n("lat2"), n("lon2"))
            #expect(close(n("distanceNm"), distance), "distance of \(c): \(distance)")
            #expect(close(n("trueCourseDeg"), course), "course of \(c): \(course)")
        }
    }

    @Test func theWindTriangleMatchesTheWebIncludingTheWindsThatCannotBeFlown() throws {
        let cases = fixture["windTriangle"]!.arrayValue!
        #expect(cases.contains { $0["expected"]!.isNull }, "the fixture lost its unflyable case")
        for c in cases {
            func n(_ key: String) -> Double { c[key]!.numberValue!.value }
            let actual = RouteCalc.solveWindTriangle(n("tasKts"), n("courseDeg"), n("windFromDeg"), n("windKts"))
            let expected = c["expected"]!
            if expected.isNull {
                #expect(actual == nil, "\(c)")
            } else {
                let a = try #require(actual, "\(c) should be flyable")
                #expect(close(expected["gsKts"]!.numberValue!.value, a.gsKts), "gs \(c): \(a.gsKts)")
                #expect(close(expected["windCorrectionDeg"]!.numberValue!.value, a.windCorrectionDeg), "wca \(c)")
                #expect(close(expected["headwindKts"]!.numberValue!.value, a.headwindKts), "headwind \(c)")
            }
        }
    }

    @Test func indicatedToTrueAirspeedMatchesTheWeb() {
        for c in fixture["iasToTas"]!.arrayValue! {
            func n(_ key: String) -> Double { c[key]!.numberValue!.value }
            let actual = RouteCalc.iasToTas(n("iasKts"), n("pressAltFt"), n("oatC"))
            #expect(close(n("expected"), actual), "\(c): \(actual)")
        }
    }

    @Test func durationsRoundHalvesUpAsJavaScriptDoes() {
        for c in fixture["formatDuration"]!.arrayValue! {
            let sec = c["sec"]!.numberValue?.value
            #expect(c["expected"]!.stringValue! == RouteCalc.formatDuration(sec), "formatDuration(\(String(describing: sec)))")
        }
        // Halves go up. Kotlin's round() and Swift's `rounded(.toNearestOrEven)` go to even and would say 1:00 for 60.5 s.
        #expect(RouteCalc.formatDuration(60.5) == "1:01")
        #expect(RouteCalc.formatDuration(0.5) == "0:01")
    }

    @Test func clocksPrintAsHHMMSS() {
        for c in fixture["formatClock"]!.arrayValue! {
            let parts = c["time"]!.stringValue!.split(separator: ":").map { Int($0)! }
            let time = LocalDateTime(year: 2026, month: 7, day: 15, hour: parts[0], minute: parts[1], second: parts[2])
            #expect(c["expected"]!.stringValue! == RouteCalc.formatClock(time))
        }
        #expect(RouteCalc.formatClock(nil) == "--:--:--")
    }

    @Test func aFreshPlanIsSeededFromTheAircraftProfile() throws {
        for c in fixture["defaultPlan"]!.arrayValue! {
            let p = primitiveText(c["profile"]).map(profile)
            let expected = try json.decode(RoutePlan.self, from: c["expected"]!)
            #expect(expected == RoutePlans.default(p), "default plan for \(c["profile"]!)")
        }
    }

    @Test func anOlderSaveIsBackfilledAndItsTOTMigrated() throws {
        for c in fixture["ensurePlan"]!.arrayValue! {
            let route = c["route"]!
            let points = try json.decodeList(RoutePoint.self, from: route["points"]!)
            let actual = try RoutePlans.ensure(points, route["plan"]?.objectValue)
            let differences = JSONCompare.differences(c["expected"]!, json.encode(actual), tolerance: tolerance)
            #expect(differences.isEmpty, "\(c["name"]!.stringValue!): \(differences)")
        }
    }

    @Test func wholeRoutesPlanToTheWebsNumbers() throws {
        let cases = fixture["plans"]!.arrayValue!
        #expect(cases.count >= 12, "the fixture shrank to \(cases.count)")
        for c in cases {
            let name = c["name"]!.stringValue!
            let points = try json.decodeList(RoutePoint.self, from: c["route"]!)
            let plan = try json.decode(RoutePlan.self, from: c["plan"]!)
            var elevations = OrderedMap<Double>()
            for entry in c["elevationsFt"]!.objectValue! { elevations[entry.key] = entry.value.numberValue!.value }

            let result = RouteCalc.computeRoutePlan(points, plan, elevationsFt: elevations, today: LocalDate(year: 2026, month: 7, day: 15))

            let differences = JSONCompare.differences(c["expected"]!, json.encode(result), tolerance: tolerance)
            #expect(differences.isEmpty, "\(name): \(differences.prefix(12))")
        }
    }

    @Test func aClockIsTrimmedAsJavaScriptTrimsAndReadInASCIIDigitsOnly() {
        let day = LocalDate(year: 2026, month: 10, day: 16)
        #expect(
            RouteCalc.parseClock("2026-10-16", "\u{FEFF}11:56:32", day)
                == LocalDateTime(year: 2026, month: 10, day: 16, hour: 11, minute: 56, second: 32))
        #expect(RouteCalc.parseClock("2026-10-16", "\u{1C}11:56:32", day) == nil)
        #expect(RouteCalc.parseClock("2026-10-16", "١١:٥٦", day) == nil)  // Arabic digits are no clock to the web
    }

    /// The one place Swift differs from the Kotlin on purpose: `jsRound` is JavaScript's `Math.round` (CoreJS's `Js.round`), where
    /// the Kotlin writes `floor(x + 0.5)`. The two part at the largest double below a half, which the addition rounds up to 1, and in
    /// the sign of a zero: JavaScript's -0.4 rounds to -0, as here.
    @Test func roundingIsJavaScriptsEvenWhereFloorOfAHalfMoreIsNot() {
        #expect(RouteCalc.jsRound(0.49999999999999994) == 0)
        #expect(RouteCalc.jsRound(2.5) == 3)
        #expect(RouteCalc.jsRound(-2.5) == -2)
        #expect(RouteCalc.jsRound(-0.4).sign == .minus)
    }

    /// Kotlin's decoder throws an unchecked exception for a saved plan of the wrong kind; Swift's is typed, so `ensure` says it throws.
    @Test func aSavedPlanOfTheWrongKindIsRefusedAsKotlinsDecoderRefusesIt() throws {
        let plan = JSONObject([("airspeed", .string("fast"))])
        #expect(throws: ModelJSONError.self) { try RoutePlans.ensure([], plan) }
        #expect(try RoutePlans.ensure([], nil) == RoutePlans.default())
    }
}
