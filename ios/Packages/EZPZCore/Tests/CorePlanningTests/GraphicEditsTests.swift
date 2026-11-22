import CoreJS
import CoreModel
import Foundation
import Testing

@testable import CorePlanning

struct GraphicEditsTests {
    struct Missing: Error {}

    /// `#require` for a value inside a larger expression, where the macro cannot be nested.
    func some<T>(_ value: T?) throws -> T {
        guard let value else { throw Missing() }
        return value
    }

    func obj(_ pairs: (String, JSONTree)...) -> JSONObject { JSONObject(pairs) }
    func merged(_ a: JSONObject, _ b: JSONObject) -> JSONObject { b.reduce(a) { $0.setting($1.key, $1.value) } }
    func d(_ o: JSONObject, _ key: String) -> Double { o[key]!.numberValue!.value }
    func text(_ o: JSONObject, _ key: String) -> String? { o[key]?.stringValue }
    func keys(_ o: JSONObject) -> Set<String> { Set(o.keys) }

    func toDegrees(_ r: Double) -> Double { r * 180 / .pi }
    func toRadians(_ d: Double) -> Double { d / 180 * .pi }

    var helo: JSONObject {
        obj(
            ("id", .number(1)), ("lat", .number(34.5)), ("lon", .number(-84.1)), ("rotation", .number(90)), ("type", .string("helo")),
            ("profileId", .string("uh60l")), ("futureField", .string("keep me")))
    }
    var pz: JSONObject {
        obj(
            ("id", .string("pz-1")), ("lat", .number(34.5)), ("lon", .number(-84.1)), ("tipLat", .number(34.5)),
            ("tipLon", .number(-84.102)))
    }
    var doghouse: JSONObject {
        obj(
            ("id", .string("d-sp1")), ("role", .string("takeoff")), ("lat", .number(34.5)), ("lon", .number(-84.103)),
            ("id_val", .string("[SP1]")), ("heading", .string("090°")), ("time", .string("01+57")))
    }

    func sector(_ points: (Double, Double)...) -> JSONObject {
        obj(("id", .string("sec-1")), ("points", .array(points.map { .object(obj(("lat", .number($0.0)), ("lng", .number($0.1)))) })))
    }

    func within(_ expected: Double, _ actual: Double, _ delta: Double) -> Bool { abs(expected - actual) <= delta }

    // MARK: Distances

    @Test func anOffsetOfMetresIsMetresOnTheGroundEastShrinkingWithLatitude() {
        let p = GraphicEdits.offset(LatLon(lat: 34.5, lon: -84.1), northM: 100, eastM: 0)
        #expect(within(34.5 + toDegrees(100 / 6_378_137.0), p.lat, 1e-12))
        #expect(p.lon == -84.1)
        let east = GraphicEdits.offset(LatLon(lat: 60, lon: 10), northM: 0, eastM: 100)
        #expect(within(toDegrees(100 / (6_378_137.0 * cos(toRadians(60)))), east.lon - 10, 1e-12))
        // Twice as many degrees at 60 degrees north.
        #expect(east.lon - 10 > toDegrees(100 / 6_378_137.0) * 1.9)
    }

    @Test func metresBetweenIsTheInverseOfTheOffset() {
        let from = LatLon(lat: 34.5, lon: -84.1)
        let (n, e) = GraphicEdits.metresBetween(from, GraphicEdits.offset(from, northM: 37, eastM: -81))
        #expect(within(37, n, 1e-6))
        #expect(within(-81, e, 1e-6))
    }

    // MARK: Position

    @Test func whereAGraphicIs() throws {
        #expect(GraphicEdits.position("helicopters", helo) == LatLon(lat: 34.5, lon: -84.1))
        #expect(GraphicEdits.position("pzMarkers", pz) == LatLon(lat: 34.5, lon: -84.1))
        let s = sector((34.501, -84.1), (34.499, -84.099), (34.499, -84.101))
        let middle = try #require(GraphicEdits.position("sectorsOfFire", s))
        #expect(within((34.501 + 34.499 + 34.499) / 3, middle.lat, 1e-12))
        #expect(within(-84.1, middle.lon, 1e-12))
        #expect(GraphicEdits.position("helicopters", obj(("id", .number(1)))) == nil)
        #expect(GraphicEdits.position("sectorsOfFire", sector((1, 2), (3, 4))) == nil)
        #expect(GraphicEdits.position("measurements", helo) == nil)
    }

    // MARK: Moving

    @Test func movingAnAircraftChangesOnlyItsPosition() throws {
        let patch = try #require(GraphicEdits.moveTo("helicopters", helo, LatLon(lat: 34.6, lon: -84.2)))
        #expect(keys(patch) == ["lat", "lon"])
        #expect(d(patch, "lat") == 34.6)
        #expect(d(patch, "lon") == -84.2)
    }

    @Test func aNudgeNorthIsNorthAndANudgeEastIsEast() throws {
        let north = try #require(GraphicEdits.nudge("helicopters", helo, northM: 25, eastM: 0))
        #expect(d(north, "lat") > 34.5)
        #expect(within(-84.1, d(north, "lon"), 1e-12))
        let east = try #require(GraphicEdits.nudge("helicopters", helo, northM: 0, eastM: 25))
        #expect(d(east, "lon") > -84.1)
        #expect(within(34.5, d(east, "lat"), 1e-12))
        let (n, e) = GraphicEdits.metresBetween(LatLon(lat: 34.5, lon: -84.1), LatLon(lat: d(east, "lat"), lon: d(east, "lon")))
        #expect(within(0, n, 1e-6))
        #expect(within(25, e, 1e-6))
    }

    @Test func aPZMarkersTipGoesWithItsAnchorKeepingItsReachAndBearing() throws {
        let before = try #require(GraphicEdits.pzReachM(pz))
        let bearing = try #require(GraphicEdits.rotation("pzMarkers", pz))
        let moved = try #require(GraphicEdits.moveTo("pzMarkers", pz, LatLon(lat: 34.51, lon: -84.09)))
        let after = merged(pz, moved)
        #expect(keys(moved) == ["lat", "lon", "tipLat", "tipLon"])
        // The tip keeps its offset in degrees, as dragging does on the web, so its length in metres moves by the change in the width
        // of a degree (0.01% here).
        #expect(within(before, try some(GraphicEdits.pzReachM(after)), 0.1))
        #expect(within(bearing, try some(GraphicEdits.rotation("pzMarkers", after)), 0.01))
    }

    @Test func aPZMarkerWithNoTipMovesItsTipFromWhereTheWebDrawsIt() throws {
        let noTip = obj(("id", .string("pz-2")), ("lat", .number(34.5)), ("lon", .number(-84.1)))
        let moved = try #require(GraphicEdits.moveTo("pzMarkers", noTip, LatLon(lat: 34.5, lon: -84.0)))
        // 0.001 west of the new anchor.
        #expect(within(-84.001, d(moved, "tipLon"), 1e-12))
    }

    @Test func aSectorKeepsItsShapeAndItsMiddleGoesWhereItIsSent() throws {
        let s = sector((34.501, -84.1), (34.499, -84.099), (34.499, -84.101))
        let patch = try #require(GraphicEdits.moveTo("sectorsOfFire", s, LatLon(lat: 34.6, lon: -84.2)))
        #expect(keys(patch) == ["points"])
        let middle = try #require(GraphicEdits.position("sectorsOfFire", merged(s, patch)))
        #expect(within(34.6, middle.lat, 1e-12))
        #expect(within(-84.2, middle.lon, 1e-12))
        let points = patch["points"]!.arrayValue!.map { $0.objectValue! }
        // The same triangle.
        #expect(within(0.002, d(points[0], "lat") - d(points[1], "lat"), 1e-12))
    }

    @Test func nothingWithoutAPositionCanBeMoved() {
        #expect(GraphicEdits.moveTo("helicopters", obj(("id", .number(1))), LatLon(lat: 1, lon: 2)) == nil)
        #expect(GraphicEdits.nudge("helicopters", obj(("id", .number(1))), northM: 1, eastM: 1) == nil)
    }

    // MARK: Turning

    @Test func degreesWrapIntoZeroToThreeHundredAndSixty() {
        #expect(within(350, GraphicEdits.normalizeDegrees(-10), 1e-9))
        #expect(within(5, GraphicEdits.normalizeDegrees(725), 1e-9))
        #expect(within(0, GraphicEdits.normalizeDegrees(360), 1e-9))
        #expect(GraphicEdits.normalizeDegrees(0) == 0)
    }

    @Test func anAircraftAndAGoAroundAreTurnedByTheirRotation() throws {
        #expect(GraphicEdits.rotation("helicopters", helo) == 90)
        // None saved is north.
        #expect(GraphicEdits.rotation("helicopters", obj(("id", .number(1)), ("lat", .number(1)), ("lon", .number(2)))) == 0)
        #expect(within(135, d(try some(GraphicEdits.rotateBy("helicopters", helo, 45)), "rotation"), 1e-9))
        #expect(within(350, d(try some(GraphicEdits.rotateBy("helicopters", helo, -100)), "rotation"), 1e-9))
        let goAround = obj(("id", .string("g")), ("lat", .number(1)), ("lon", .number(2)))
        #expect(within(45, d(try some(GraphicEdits.setRotation("goArounds", goAround, 405)), "rotation"), 1e-9))
        #expect(keys(try some(GraphicEdits.setRotation("helicopters", helo, 10))) == ["rotation"])
    }

    @Test func aDoghouseIsTurnedByItsHeadingWhichItKeepsAsTheWebWritesIt() throws {
        #expect(GraphicEdits.rotation("doghouses", doghouse) == 90)
        // None saved is north.
        #expect(GraphicEdits.rotation("doghouses", obj(("id", .string("d")), ("lat", .number(1)), ("lon", .number(2)))) == 0)
        #expect(text(try some(GraphicEdits.rotateBy("doghouses", doghouse, -100)), "heading") == "350°")
        #expect(text(try some(GraphicEdits.setRotation("doghouses", doghouse, 450)), "heading") == "090°")
        // Three digits, as "000°".
        #expect(text(try some(GraphicEdits.setRotation("doghouses", doghouse, 5)), "heading") == "005°")
        // To the whole degree, wrapped.
        #expect(text(try some(GraphicEdits.setRotation("doghouses", doghouse, 359.6)), "heading") == "000°")
        let negative = obj(("id", .string("d")), ("lat", .number(1)), ("lon", .number(2)), ("heading", .string("-90°")))
        #expect(text(try some(GraphicEdits.setRotation("doghouses", negative, 270)), "heading") == "270°")
        // Nothing else is touched.
        #expect(keys(try some(GraphicEdits.setRotation("doghouses", doghouse, 10))) == ["heading"])
    }

    @Test func aDoghouseIsMovedLikeAnAircraft() throws {
        let moved = try #require(GraphicEdits.nudge("doghouses", doghouse, northM: 10, eastM: 0))
        #expect(keys(moved) == ["lat", "lon"])
        let start = try #require(GraphicEdits.position("doghouses", doghouse))
        #expect(within(10, GraphicEdits.metresBetween(start, LatLon(lat: d(moved, "lat"), lon: d(moved, "lon"))).north, 0.01))
        #expect(d(try some(GraphicEdits.moveTo("doghouses", doghouse, LatLon(lat: 34.5, lon: -84.0))), "lat") == 34.5)
    }

    func signed(_ degrees: Double) -> Double { degrees > 180 ? degrees - 360 : degrees }

    @Test func aPZMarkersRotationIsTheBearingOfItsTipAndTurningItSwingsTheTipKeepingItsReach() throws {
        // The tip is due west.
        #expect(within(270, try some(GraphicEdits.rotation("pzMarkers", pz)), 0.01))
        let reach = try #require(GraphicEdits.pzReachM(pz))
        let turned = merged(pz, try #require(GraphicEdits.setRotation("pzMarkers", pz, 0)))
        #expect(within(0, signed(try some(GraphicEdits.rotation("pzMarkers", turned))), 0.01))
        #expect(within(reach, try some(GraphicEdits.pzReachM(turned)), 1e-6))
        // Now due north of the anchor.
        #expect(within(34.5 + toDegrees(reach / 6_378_137.0), d(turned, "tipLat"), 1e-9))
        #expect(within(-84.1, d(turned, "tipLon"), 1e-9))
    }

    @Test func whatDoesNotTurnHasNoRotation() {
        #expect(GraphicEdits.rotation("sectorsOfFire", sector((1, 2), (3, 4), (5, 6))) == nil)
        #expect(GraphicEdits.setRotation("sectorsOfFire", sector((1, 2), (3, 4), (5, 6)), 10) == nil)
        // A unit symbol stays upright.
        #expect(GraphicEdits.rotateBy("units", obj(("id", .string("u")), ("lat", .number(1)), ("lon", .number(2))), 10) == nil)
        #expect(GraphicEdits.rotation("somethingElse", obj(("id", .string("u")), ("lat", .number(1)), ("lon", .number(2)))) == nil)
    }

    // MARK: A PZ marker's reach

    @Test func aPZMarkersReachIsMetresFromAnchorToTipAndCanBeSetKeepingItsBearing() throws {
        let reach = try #require(GraphicEdits.pzReachM(pz))
        #expect(within(toRadians(0.002) * 6_378_137.0 * cos(toRadians(34.5)), reach, 1e-6))
        let longer = merged(pz, try #require(GraphicEdits.setPzReach(pz, 300)))
        #expect(within(300, try some(GraphicEdits.pzReachM(longer)), 1e-6))
        #expect(within(270, try some(GraphicEdits.rotation("pzMarkers", longer)), 0.01))
        // Never negative.
        #expect(within(0, try some(GraphicEdits.pzReachM(merged(pz, try some(GraphicEdits.setPzReach(pz, -50))))), 1e-6))
    }

    @Test func aMarkerWhoseTipIsOnItsAnchorPointsNorthWhenItIsGivenAReach() throws {
        let flat = obj(
            ("id", .string("pz")), ("lat", .number(34.5)), ("lon", .number(-84.1)), ("tipLat", .number(34.5)), ("tipLon", .number(-84.1)))
        let patched = merged(flat, try #require(GraphicEdits.setPzReach(flat, 100)))
        #expect(within(0, signed(try some(GraphicEdits.rotation("pzMarkers", patched))), 1e-6))
        #expect(within(100, try some(GraphicEdits.pzReachM(patched)), 1e-6))
    }

    @Test func theTipCanBeSetToAPlaceSuchAsTheCrosshair() throws {
        let patch = try #require(GraphicEdits.setPzTip(pz, LatLon(lat: 34.505, lon: -84.095)))
        #expect(keys(patch) == ["tipLat", "tipLon"])
        #expect(d(patch, "tipLat") == 34.505)
        #expect(GraphicEdits.setPzTip(obj(("id", .string("x"))), LatLon(lat: 1, lon: 2)) == nil)
    }

    @Test func aGoAroundGoesLeftOrRightAndNothingElse() {
        #expect(GraphicEdits.setGoAroundDirection(obj(("id", .string("g"))), "left").flatMap { text($0, "direction") } == "left")
        #expect(GraphicEdits.setGoAroundDirection(obj(("id", .string("g"))), "right").flatMap { text($0, "direction") } == "right")
        #expect(GraphicEdits.setGoAroundDirection(obj(("id", .string("g"))), "N") == nil)
    }
}
