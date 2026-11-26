import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// What a saved route set keeps through a read and a write: everything the web wrote, and what this version does not know. Android's
/// RouteSetTest.
struct RouteSetTests {
    let fixtureRoutes: JSONObject

    init() throws { fixtureRoutes = try Fixtures.load("routes/sketch.json")["routes"]!.objectValue! }

    func doc(_ routes: [JSONTree], extra: [(String, JSONTree)] = []) -> JSONObject {
        JSONObject([("version", .number(1)), ("routes", .array(routes))] + extra)
    }

    func read(_ data: JSONObject) -> RouteSet { RouteSets.parse(id: "uuid-1", savedId: nil, name: "SET", data: data) }

    func route(_ key: String) -> JSONObject { fixtureRoutes[key]!.objectValue! }

    /// The fixture's routes all say `sketch-1`; a set needs each its own.
    func named(_ id: String, _ route: JSONTree) -> JSONTree { .object(route.objectValue!.setting("id", .string(id))) }

    @Test func aSetsFlotsWhichThisVersionDoesNotDrawYetComeThroughAReadAndAWrite() {
        let flots = json(#"[{"id": "flot-1", "flipped": true, "points": [{"lat": 34.7, "lon": -84.1}, {"lat": 34.8, "lon": -84.0}]}]"#)
        let data = doc([], extra: [("flots", flots)])
        #expect(RouteSets.serialize(read(data))["flots"] == flots)
    }

    @Test func aSetsPhaseLinesWhichThisVersionDoesNotDrawYetComeThroughAReadAndAWrite() {
        let lines = json(#"[{"id": "pl-1", "name": "RED", "points": [{"lat": 34.7, "lon": -84.1}, {"lat": 34.8, "lon": -84.0}]}]"#)
        let data = doc([], extra: [("phaseLines", lines)])
        #expect(RouteSets.serialize(read(data))["phaseLines"] == lines)
    }

    @Test func everyRouteTheWebsSketchFixtureMakesIsReadAndWrittenBackAsItWas() {
        #expect(fixtureRoutes.count >= 7)
        for entry in fixtureRoutes.entries {
            let data = doc([entry.value])
            let set = read(data)
            #expect(set.routes.count == 1, "\(entry.key) was read")
            #expect(set.unreadable.isEmpty, "\(entry.key)")
            check(entry.key, .object(data), .object(RouteSets.serialize(set)))
        }
    }

    @Test func aSetWithSeveralRoutesKeepsTheirOrder() {
        let data = doc(fixtureRoutes.entries.enumerated().map { named("sketch-\($0.offset)", $0.element.value) })
        let set = read(data)
        #expect(set.routes.count == fixtureRoutes.count)
        #expect(set.routes.map(\.id) == (0..<fixtureRoutes.count).map { "sketch-\($0)" })
        check("all routes together", .object(data), .object(RouteSets.serialize(set)))
    }

    @Test func aFieldANewerReleaseAddsSurvivesAtEveryLevelItCan() {
        let route = route("planned")
        let points = route["points"]!.arrayValue!.elements.enumerated().map {
            JSONTree.object($0.element.objectValue!.setting("newPointField", .string("point \($0.offset)")))
        }
        let plan = route["plan"]!.objectValue!.setting("newPlanField", json(#"{"a": 1}"#))
        let newer = route.setting("points", .array(points)).setting("plan", .object(plan)).setting("newRouteField", .bool(true))
        let data = doc([.object(newer)], extra: [("newDocumentField", .string("x"))])

        let set = read(data)
        #expect(set.routes.count == 1)
        #expect(set.routes[0].extras["newRouteField"] == .bool(true))
        #expect(set.routes[0].points[2].extras["newPointField"] == .string("point 2"))
        #expect(set.routes[0].plan.extras["newPlanField"] != nil)
        #expect(set.extras["newDocumentField"] == .string("x"))
        check("a newer document", .object(data), .object(RouteSets.serialize(set)))
    }

    @Test func anEditDoesNotLoseWhatThePointWasCarryingAndWhatIsEditedIsWhatIsWritten() {
        let route = route("planned")
        let points = route["points"]!.arrayValue!.elements.map { JSONTree.object($0.objectValue!.setting("keepMe", .number(1))) }
        let set = read(doc([.object(route.setting("points", .array(points)))]))

        let moved = set.mapRoute("sketch-1") { r in
            var r = r
            r.points = r.points.map { p in
                var p = p
                if p.id == "p2" { p.lat = 12.5 }
                return p
            }
            return r
        }
        let written = RouteSets.serialize(moved)["routes"]![0]!["points"]!.arrayValue!.elements
        let p2 = written.first { $0["id"] == .string("p2") }
        #expect(p2?["lat"] == .number(12.5))
        #expect(p2?["keepMe"] == .number(1))
    }

    @Test func aFieldThisVersionKnowsIsNeverOverwrittenByAStaleExtra() {
        var clashing = RouteSets.parse(id: "u", savedId: nil, name: "S", data: doc([.object(route("planned"))])).routes[0]
        clashing.name = "RENAMED"
        clashing.extras = JSONObject([("name", .string("OLD"))])
        let written = RouteSets.serialize(RouteSet(id: "u", name: "S", routes: [clashing]))["routes"]![0]!
        #expect(written["name"] == .string("RENAMED"))
    }

    @Test func aRouteThatCannotBeReadIsKeptAsItWasAfterTheOthersAndDoesNotFailTheSet() {
        let broken = json(#"{"id": "sketch-broken", "name": "no points, no colour", "color": 7}"#)
        let notEvenAnObject = JSONTree.string("junk")
        let set = read(doc([broken, .object(route("twoAmps")), notEvenAnObject]))

        #expect(set.routes.count == 1)
        #expect(set.unreadable == [broken, notEvenAnObject])
        let written = RouteSets.serialize(set)["routes"]!.arrayValue!
        #expect(written.count == 3)
        #expect(written[1]["id"] == .string("sketch-broken"))
        #expect(written[2] == notEvenAnObject)
    }

    @Test func aGroundElevationOrAPointsValuesThatAreNullAreNoneAsTheWebReadsThemAndTheRouteIsRead() {
        // What a mission pack's operations leave: a key that went is sent as null (no operation deletes one), so a point moved off the
        // ground fetched for it, or a clock moved to another point, leaves `{"p2": null}`. The web reads it as none.
        let route = route("planned")
        let plan = route["plan"]!.objectValue!
        let perPoint = plan["perPoint"]!.objectValue!.setting("p4", .null)
        let withNulls = route.setting("elevations", json(#"{"p1": 1200.0, "p2": null, "p4": 1250}"#))
            .setting("plan", .object(plan.setting("perPoint", .object(perPoint))))
        let set = read(doc([.object(withNulls)]))

        #expect(set.unreadable.isEmpty, "the route was read")
        let read = set.routes[0]
        #expect(read.elevations == ["p1": 1200, "p4": 1250])
        #expect(read.plan.perPoint.keys == ["p2", "p3"])
        #expect(read.plan.perPoint["p3"] == PointOverride(wind: Wind(dirTrue: 270, speedKts: 15)))
        // Written back, a null is no value: nothing else of the route changes.
        let written = RouteSets.serialize(set)["routes"]![0]!.objectValue!
        check("the route written back", .object(withNulls.removing("visible")), .object(written.removing("visible")))
        #expect(written["elevations"]?.objectValue?.has("p2") == false)
    }

    @Test func aDocumentWithNoRoutesIsAnEmptySetAndAPointWithNoPositionMakesItsRouteUnreadable() {
        #expect(read(JSONObject()).routes.isEmpty)
        #expect(read(JSONObject([("routes", .null)])).routes.isEmpty)

        let route = route("twoAmps")
        let points = route["points"]!.arrayValue!.elements.map { JSONTree.object($0.objectValue!.removing("lat")) }
        let set = read(doc([.object(route.setting("points", .array(points)))]))
        #expect(set.routes.isEmpty)
        #expect(set.unreadable.count == 1)
    }

    @Test func theVersionIsKeptAsFoundAndADocumentWithNoneIsWrittenAs1() {
        #expect(RouteSets.serialize(read(JSONObject([("version", .number(2)), ("routes", .array([]))])))["version"] == .number(2))
        #expect(RouteSets.serialize(read(JSONObject()))["version"] == .number(1))
        #expect(RouteSets.serialize(RouteSet(id: "u", name: "S", routes: []))["version"] == .number(1))
    }

    @Test func theSavedIdAndTheNameBelongToTheRecordNotTheDocument() {
        let set = RouteSets.parse(id: "uuid-9", savedId: 42, name: "NAME", data: doc([.object(route("twoAmps"))]))
        #expect(set.id == "uuid-9")
        #expect(set.savedId == 42)
        #expect(set.name == "NAME")
        #expect(!RouteSets.serialize(set).has("name"))
        #expect(!RouteSets.serialize(set).has("id"))
    }

    @Test func aRouteIsFoundChangedAddedAndRemovedByItsId() {
        let set = read(doc([named("sketch-a", .object(route("twoAmps"))), named("sketch-b", .object(route("threeAmps")))]))
        let first = set.routes[0].id
        let second = set.routes[1].id
        func renamed(_ r: SketchRoute) -> SketchRoute {
            var r = r
            r.name = "X"
            return r
        }

        #expect(set.route(second)?.id == second)
        #expect(set.route("nope") == nil)

        #expect(set.mapRoute(first, renamed).route(first)?.name == "X")
        #expect(set.mapRoute(first, renamed).route(second) == set.routes[1])
        #expect(set.mapRoute("nope", renamed) == set)
        #expect(set.mapRoute(first) { $0 } == set)

        #expect(set.without(second).routes.map(\.id) == [first])
        #expect(set.without("nope") == set)
        var copy = set.routes[0]
        copy.id = "new"
        #expect(set.plus(copy).routes.map(\.id) == [first, second, "new"])
    }

    /// Not like Kotlin's LinkedHashMap, which kept a document's keys as added: CoreJS's objects are in JavaScript's order, the order
    /// every reader (the web's `JSON.parse` too) sees them in.
    @Test func aDocumentsKeysComeOutInJavaScriptsOrder() {
        let data = JSONObject([("b", .number(1)), ("7", .number(2)), ("routes", .array([]))])
        #expect(RouteSets.serialize(read(data)).keys == ["7", "version", "b", "routes"])
    }

    @Test func aMissionsColoursAreTakenOnlyWhenTheyAreRRGGBB() {
        let summary = RouteSets.missionSummary([
            SketchRoute(id: "a", name: "A", color: "#12ab9F", points: []), SketchRoute(id: "b", name: "B", color: "red", points: []),
        ])
        #expect(summary["version"] == .number(1))
        #expect(RouteSets.missionColors(summary) == ["#12ab9F", nil])
        #expect(
            RouteSets.missionColors(object(##"{"routes": [{"color": "#12345"}, {"color": "#1234567"}, 3, {"color": "#12345g"}]}"##)) == [
                nil, nil, nil, nil,
            ])
        #expect(RouteSets.missionColors(JSONObject()) == [])
    }
}

/// The reading rules of the model's types are kotlinx's, which decide what a route set keeps apart as unreadable: a quoted number
/// is a number, a number is not text, a required field must be there, and a null takes a default only where nulls are coerced.
struct ModelJSONTests {
    @Test func aNumberMayBeQuotedButTextMayNotBeANumber() throws {
        let point = try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lat": "34.5", "lon": -84}"#))
        #expect(point.lat == 34.5)
        #expect(point.lon == -84)
        #expect(throws: ModelJSONError.self) {
            try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lat": 1, "lon": 2, "id": 5}"#))
        }
        #expect(throws: ModelJSONError.self) { try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lat": "x", "lon": 2}"#)) }
        #expect(throws: ModelJSONError.self) { try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lat": "NaN", "lon": 2}"#)) }
        #expect(throws: ModelJSONError.self) { try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lon": 2}"#)) }
        #expect(throws: ModelJSONError.self) { try ModelJSON.routeSets.decode(RoutePoint.self, from: json(#"{"lat": null, "lon": 2}"#)) }
    }

    @Test func aNullTakesTheDefaultOnlyWhereNullsAreCoerced() throws {
        let route = json(##"{"id": "r", "name": "R", "color": "#000000", "points": [], "visible": null, "plan": null}"##)
        let read = try ModelJSON.routeSets.decode(SketchRoute.self, from: route)
        #expect(read.visible)
        #expect(read.plan == RoutePlan())
        #expect(throws: ModelJSONError.self) { try ModelJSON(ignoreUnknownKeys: true).decode(SketchRoute.self, from: route) }
        // A plan's wind may be null, and then it is none rather than the default calm.
        let plan = try ModelJSON.routeSets.decode(RoutePlan.self, from: json(#"{"wind": null}"#))
        #expect(plan.wind == nil)
        #expect(try ModelJSON.routeSets.decode(RoutePlan.self, from: json("{}")).wind == Wind())
    }

    @Test func aFlagIsTrueOrFalseQuotedOrNot() throws {
        let decode = { (text: String) throws(ModelJSONError) in try ModelJSON.routeSets.decode(Wind.self, from: json(text)) }
        _ = try decode("{}")
        let band = { (v: String) throws(ModelJSONError) -> Bool in
            try ModelJSON().decode(
                RadarBand.self, from: json(##"{"altFt": 1, "color": "#fff", "alpha": 1, "colorIndex": 1, "viewable": \##(v)}"##)
            )
            .viewable
        }
        #expect(try band(#""true""#))
        #expect(try !band("false"))
        #expect(throws: ModelJSONError.self) { try band("1") }
        #expect(throws: ModelJSONError.self) { try band(#""TRUE""#) }
    }

    @Test func aWholeNumberMayHaveAnExponentButNoFraction() throws {
        func colorIndex(_ v: String) throws(ModelJSONError) -> Int {
            try ModelJSON().decode(
                RadarBand.self, from: json(##"{"altFt": 1, "color": "#fff", "alpha": 1, "colorIndex": \##(v), "viewable": true}"##)
            )
            .colorIndex
        }
        #expect(try colorIndex("3") == 3)
        #expect(try colorIndex("3e1") == 30)
        #expect(try colorIndex(#""4""#) == 4)
        #expect(throws: ModelJSONError.self) { try colorIndex("3.5") }
        #expect(throws: ModelJSONError.self) { try colorIndex("3.0") }
        #expect(throws: ModelJSONError.self) { try colorIndex("3000000000") }
    }

    @Test func aKeyTheTypeDoesNotHaveIsRefusedUnlessUnknownKeysAreIgnored() {
        #expect(throws: ModelJSONError.self) { try ModelJSON().decode(Wind.self, from: json(#"{"gust": 1}"#)) }
        #expect((try? ModelJSON(ignoreUnknownKeys: true).decode(Wind.self, from: json(#"{"gust": 1}"#))) == Wind())
    }

    @Test func writingLeavesOutANullOnlyWithoutExplicitNulls() {
        let override = PointOverride(clock: "12:00")
        #expect(ModelJSON().encode(override) == json(#"{"altitude": null, "airspeed": null, "wind": null, "clock": "12:00"}"#))
        #expect(ModelJSON.routeSets.encode(override) == json(#"{"clock": "12:00"}"#))
    }

    /// Not like Kotlin, which wrote a double as `34.0`: a number made here has JavaScript's text, the web's own.
    @Test func aNumberIsWrittenAsJavaScriptWritesIt() {
        #expect(ModelJSON().encode(Wind(dirTrue: 270, speedKts: 15.5)).serialized == #"{"dirTrue":270,"speedKts":15.5}"#)
    }

    @Test func aRequiredFieldThatMayBeNullMustBeThereUnlessNullsAreLeftOut() throws {
        let totals = json(#"{"distNm": 3}"#)
        #expect(throws: ModelJSONError.self) { try ModelJSON().decode(PlanTotals.self, from: totals) }
        let read = try ModelJSON(explicitNulls: false).decode(PlanTotals.self, from: totals)
        #expect(read == PlanTotals(distNm: 3, timeSec: nil, fuelLb: nil))
    }
}
