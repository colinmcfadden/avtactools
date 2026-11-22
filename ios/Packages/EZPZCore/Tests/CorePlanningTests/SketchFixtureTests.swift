import CoreJS
import CoreModel
import CoreTesting
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/routes/sketch.json: what the web's `sketchOps` make of a sketched route, case by case.
struct SketchFixtureTests {
    let fixture: JSONObject
    let json = ModelJSON(ignoreUnknownKeys: true, coerceInputValues: true)

    init() throws { fixture = try Fixtures.load("routes/sketch.json") }

    func cases(_ key: String) -> [JSONTree] { Array(fixture[key]!.arrayValue!) }
    func label(_ c: JSONTree) -> String { (c["label"] ?? c["name"])!.stringValue! }

    // A route read as the app reads one (RouteSets): a value a mission pack left null where a key went (a point's overrides, a
    // clock, the ground of a point moved off it) is none, as the web reads it; read strictly, such a route could not be read at all.
    func route(_ element: JSONTree) -> SketchRoute {
        let routes = RouteSets.parse(id: "set", savedId: nil, name: "", data: JSONObject([("routes", .array([element]))])).routes
        #expect(routes.count == 1)
        return routes[0]
    }

    func encoded(_ route: SketchRoute?) -> JSONTree { route.map { json.encode($0) } ?? .null }

    func counter(_ prefix: String) -> () -> String {
        var n = 0
        return {
            n += 1
            return "\(prefix)\(n)"
        }
    }

    func expectSame(_ expected: JSONTree, _ actual: JSONTree, _ what: String) {
        let differences = JSONCompare.differences(expected, actual)
        #expect(differences.isEmpty, "\(what): \(differences.prefix(8))")
    }

    func double(_ o: JSONTree, _ key: String) -> Double { o[key]!.numberValue!.value }
    func doubleOrNil(_ o: JSONTree, _ key: String) -> Double? { finiteNumber(o[key]) }
    func text(_ o: JSONTree, _ key: String) -> String? { primitiveText(o[key]) }

    func airspeed(_ o: JSONTree) -> Airspeed { Airspeed(double(o, "value"), text(o, "type")!) }
    func altitude(_ o: JSONTree) -> AltitudeSetting { AltitudeSetting(double(o, "value"), text(o, "ref")!) }
    func wind(_ o: JSONTree) -> Wind { Wind(dirTrue: double(o, "dirTrue"), speedKts: double(o, "speedKts")) }
    func object(_ o: JSONTree?) -> JSONTree? { o?.objectValue == nil ? nil : o }

    @Test func theStandardAttackProfileIsTheSameForEveryLengthOfRoute() {
        let all = cases("auto")
        #expect(all.count >= 13)
        for c in all {
            let index = Int(c["index"]!.numberValue!.value)
            let last = Int(c["lastIndex"]!.numberValue!.value)
            let expected = c["expected"]!
            let actual = SketchOps.autoDesignation(index, last)
            if expected.isNull {
                #expect(actual == nil, "index \(index) of \(last)")
            } else {
                #expect(text(expected, "ptType") == actual?.ptType, "type at \(index) of \(last)")
                #expect(text(expected, "name") == actual?.name, "name at \(index) of \(last)")
            }
        }
    }

    @Test func aFinishedSketchIsTheRouteTheWebMakes() {
        let all = cases("build")
        #expect(all.count >= 10)
        for c in all {
            let draft = c["draft"]!.arrayValue!.map { p in
                DraftPoint(
                    double(p, "lat"), double(p, "lon"),
                    object(p["designation"]).map {
                        Designation(
                            ptType: text($0, "ptType"), name: text($0, "name"), chartElevationFt: doubleOrNil($0, "chartElevationFt"))
                    })
            }
            let profile = c["plan"]?.objectValue.map(AircraftProfile.normalize)
            let built = SketchOps.build(
                draft, name: "ROUTE 1", id: "sketch-1", color: "#FF453A", plan: RoutePlans.default(profile), newPointId: counter("p"))
            expectSame(c["expected"]!, encoded(built), label(c))
        }
    }

    @Test func changingWhatAPointIsGivesTheWebsRouteAndRefusesWhatItRefuses() {
        let all = cases("designate")
        #expect(all.count >= 11)
        for c in all {
            let args = c["args"]!
            let spec = args["spec"]!
            let result = SketchOps.designate(
                route(c["route"]!), text(args, "pointId")!,
                PointSpec(text(spec, "kind")!, ptType: text(spec, "ptType"), name: text(spec, "name")))
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    @Test func movingAPoint() {
        for c in cases("move") {
            let a = c["args"]!
            let result = SketchOps.move(
                route(c["route"]!), text(a, "pointId")!, double(a, "lat"), double(a, "lon"), doubleOrNil(a, "chartElevationFt"))
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    @Test func addingAShapingPointToTheNearestLeg() {
        let all = cases("insert")
        #expect(all.count >= 8)
        for c in all {
            let a = c["args"]!
            let result = SketchOps.insertShaping(route(c["route"]!), double(a, "lat"), double(a, "lon"), newPointId: counter("n"))
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    @Test func appendingADesignatedPoint() {
        for c in cases("append") {
            let a = c["args"]!
            let o = a["options"]!
            let start = route(c["route"]!)
            // Nothing said means the defaults the function itself has; only what the case gives is passed.
            let result =
                o.objectValue!.isEmpty
                ? SketchOps.appendAmps(start, double(a, "lat"), double(a, "lon"), newPointId: counter("n"))
                : SketchOps.appendAmps(
                    start, double(a, "lat"), double(a, "lon"), name: text(o, "name")!, ptType: text(o, "ptType")!,
                    chartElevationFt: doubleOrNil(o, "chartElevationFt"), newPointId: counter("n"))
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    @Test func planSettingsPerPointValuesAndTheClockAnchor() {
        let all = cases("plan")
        #expect(all.count >= 9)
        for c in all {
            let a = c["args"]!
            let start = route(c["route"]!)
            let result: SketchRoute
            if a["clock"] != nil {
                result = SketchOps.withClock(start, text(a, "pointId")!, text(a, "clock"))
            } else if a["patch"]?.isNull == true {
                result = SketchOps.clearOverrides(start, text(a, "pointId")!)
            } else if a["pointId"] != nil {
                let p = a["patch"]!
                result = SketchOps.withOverride(
                    start, text(a, "pointId")!,
                    OverridePatch(
                        altitude: object(p["altitude"]).map(altitude), airspeed: object(p["airspeed"]).map(airspeed),
                        wind: object(p["wind"]).map(wind)))
            } else {
                let p = a["patch"]!
                result = SketchOps.withPlan(
                    start,
                    PlanPatch(
                        airspeed: object(p["airspeed"]).map(airspeed), altitude: object(p["altitude"]).map(altitude),
                        wind: object(p["wind"]).map(wind),
                        tempC: doubleOrNil(p, "tempC"), fuelFlowLbHr: doubleOrNil(p, "fuelFlowLbHr"), date: text(p, "date")))
            }
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    @Test func renamingAPointAndSnappingItToALocalPoint() {
        for c in cases("rename") {
            let a = c["args"]!
            let coords = object(a["coords"])
            let result = SketchOps.rename(
                route(c["route"]!), text(a, "pointId")!, text(a, "name")!, snapTo: coords.map { (double($0, "lat"), double($0, "lon")) },
                chartElevationFt: doubleOrNil(a, "chartElevationFt"))
            expectSame(c["expected"]!, encoded(result), label(c))
        }
    }

    /// Ids are compared as Kotlin compares text, unit for unit: Swift's `==` would take a point named with a decomposed accent for the
    /// same point as one named with the composed one.
    @Test func aPointIsFoundByItsIdUnitForUnit() {
        let composed = RoutePoint(id: "caf\u{E9}", lat: 1, lon: 1, kind: RoutePoint.kindAmps, name: "A")
        let other = RoutePoint(id: "b", lat: 2, lon: 2, kind: RoutePoint.kindAmps, name: "B")
        let start = SketchRoute(id: "r", name: "R", color: "#FF453A", points: [composed, other])
        let renamed = SketchOps.rename(start, "cafe\u{301}", "X")
        #expect(renamed.points[0].name == "A")
        #expect(SketchOps.rename(start, "caf\u{E9}", "X").points[0].name == "X")
    }
}
