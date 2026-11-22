import CoreJS
import CoreModel
import CoreTesting
import Foundation
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/routes/winds.json: which instant each point asks the weather service about, and how the answer is
/// merged into the plan.
struct RouteWindsFixtureTests {
    let fixture: JSONObject
    let json = ModelJSON(ignoreUnknownKeys: true, coerceInputValues: true)

    init() throws { fixture = try Fixtures.load("routes/winds.json") }

    func cases(_ key: String) -> [JSONTree] { Array(fixture[key]!.arrayValue!) }
    func route(_ c: JSONTree) throws -> SketchRoute { try json.decode(SketchRoute.self, from: c["route"]!) }

    /// The fixture writes every time to the millisecond (`yyyy-MM-dd'T'HH:mm:ss.SSS`).
    func wall(_ t: LocalDateTime) -> String {
        "\(t.date)T\(padded(String(t.hour), 2)):\(padded(String(t.minute), 2)):\(padded(String(t.second), 2))."
            + padded(String(t.nanosecond / 1_000_000), 3)
    }

    @Test func eachRouteAsksForTheWindsTheWebAsksFor() throws {
        let all = cases("request")
        #expect(all.count >= 11)
        for c in all {
            let label = c["label"]!.stringValue!
            let expected = c["expected"]!
            // Every case that has a clock names its date, so the day it falls on never depends on today.
            let actual = RouteWinds.request(try route(c), today: LocalDate(year: 2000, month: 1, day: 1))
            if expected["error"] != nil {
                #expect(actual == nil, "\(label)")
                continue
            }
            let request = try #require(actual, "\(label)")
            #expect(expected["ampIds"]!.arrayValue!.map { $0.stringValue! } == request.amps.map(\.id), "\(label): points")
            let wanted = expected["points"]!.arrayValue!
            #expect(wanted.count == request.points.count, "\(label)")
            for (i, (want, got)) in zip(wanted, request.points).enumerated() {
                #expect(want["id"]!.stringValue! == got.id, "\(label) #\(i) id")
                #expect(try ModelJSON().double(want["lat"]!) == got.lat, "\(label) #\(i) lat")
                #expect(try ModelJSON().double(want["lon"]!) == got.lon, "\(label) #\(i) lon")
                #expect(primitiveText(want["localTime"]) == got.localTime.map(wall), "\(label) #\(i) time")
            }
        }
    }

    /// What the web's `typeof tempC === "number"` accepts: a number. Anything else (null, text) is no temperature.
    func temperature(_ e: JSONTree?) -> Double? { e?.numberValue?.value }

    @Test func eachAnswerIsMergedIntoThePlanAsTheWebMergesIt() throws {
        let all = cases("merge")
        #expect(all.count >= 9)
        for c in all {
            let label = c["label"]!.stringValue!
            let plan = try json.decode(RoutePlan.self, from: c["plan"]!)
            let amps = c["ampIds"]!.arrayValue!.map { RoutePoint(id: $0.stringValue!, lat: 0, lon: 0) }
            var winds = OrderedMap<PointWind>()
            for entry in c["winds"]!.objectValue! {
                let o = entry.value
                winds[entry.key] = PointWind(
                    dirTrue: try ModelJSON().double(o["dirTrue"]!), speedKts: try ModelJSON().double(o["speedKts"]!),
                    tempC: temperature(o["tempC"]))
            }
            let merged = json.encode(RouteWinds.merge(plan, amps, winds))
            let differences = JSONCompare.differences(c["expected"]!, merged)
            #expect(differences.isEmpty, "\(label): \(differences.prefix(8))")
        }
    }

    @Test func aClockWithNoDateFallsOnTheDayItIsAskedOn() throws {
        var undated = try route(cases("request")[1])
        undated.plan.date = ""
        let asked = try #require(RouteWinds.request(undated, today: LocalDate(year: 2026, month: 3, day: 4)))
        #expect(asked.points[2].localTime!.date == LocalDate(year: 2026, month: 3, day: 4))
        #expect(asked.points[2].localTime == LocalDateTime(year: 2026, month: 3, day: 4, hour: 12, minute: 30))
        // The other points follow the anchor on the same day.
        #expect(asked.points[0].localTime!.date == LocalDate(year: 2026, month: 3, day: 4))
    }

    @Test func aTimeIsWrittenAsTheInstantItIsInTheDevicesZoneToTheMillisecond() {
        let noon = LocalDateTime(year: 2026, month: 10, day: 3, hour: 12)
        let utc = TimeZone(identifier: "UTC")!
        let newYork = TimeZone(identifier: "America/New_York")!
        #expect(RouteWinds.instantText(noon, utc) == "2026-10-03T12:00:00.000Z")
        // Daylight time: four hours behind.
        #expect(RouteWinds.instantText(noon, newYork) == "2026-10-03T16:00:00.000Z")
        // Standard time: five.
        #expect(RouteWinds.instantText(LocalDateTime(year: 2026, month: 12, day: 3, hour: 12), newYork) == "2026-12-03T17:00:00.000Z")
        // East of Greenwich: the day before.
        #expect(RouteWinds.instantText(noon, TimeZone(identifier: "Pacific/Auckland")!) == "2026-10-02T23:00:00.000Z")
        #expect(
            RouteWinds.instantText(LocalDateTime(year: 2026, month: 10, day: 3, hour: 12, minute: 30, second: 5), utc)
                == "2026-10-03T12:30:05.000Z")
    }

    /// A wall-clock time the clocks skip or repeat is placed as java.time's `atZone` places it: in a gap, moved on by the gap's length
    /// (02:30 on the morning New York springs forward is 03:30 EDT); in an overlap, the earlier offset (01:30 the morning it falls
    /// back is still EDT). Foundation has no `atZone`, so `instantText` follows the rule itself.
    @Test func aTimeTheClocksSkipOrRepeatIsPlacedAsJavaTimePlacesIt() {
        let newYork = TimeZone(identifier: "America/New_York")!
        #expect(
            RouteWinds.instantText(LocalDateTime(year: 2026, month: 3, day: 8, hour: 2, minute: 30), newYork) == "2026-03-08T07:30:00.000Z")
        #expect(
            RouteWinds.instantText(LocalDateTime(year: 2026, month: 11, day: 1, hour: 1, minute: 30), newYork) == "2026-11-01T05:30:00.000Z"
        )
        // Milliseconds are kept and anything finer is dropped, as `toInstant().toString()` of a time to the millisecond writes it.
        #expect(
            RouteWinds.instantText(
                LocalDateTime(year: 2026, month: 10, day: 3, hour: 12, nanosecond: 250_999_999), TimeZone(identifier: "UTC")!)
                == "2026-10-03T12:00:00.250Z")
    }

    @Test func groundElevationsAreKeptForThePointsThatHaveOneById() throws {
        let route = try route(cases("request")[0])
        let amps = RouteElevations.points(route)
        // The shaping point is not asked about.
        #expect(amps.map(\.id) == ["p1", "p2", "p4", "p5"])
        #expect(RouteElevations.byPoint(amps, [1312, nil, 900, nil]) == ["p1": 1312, "p4": 900])
        // Fewer answers than points: the rest have none.
        #expect(RouteElevations.byPoint(amps, [5]) == ["p1": 5])
        #expect(RouteElevations.byPoint(amps, []) == [:])
        #expect(RouteElevations.byPoint([RoutePoint(id: nil, lat: 0, lon: 0)], [7]) == [:])
    }

    @Test func aPointWithNoOverrideIsGivenOneAndAPointWithNoAnswerIsNot() {
        let plan = RoutePlan()
        let amps = [RoutePoint(id: "a", lat: 0, lon: 0), RoutePoint(id: "b", lat: 0, lon: 0)]
        let merged = RouteWinds.merge(plan, amps, ["a": PointWind(dirTrue: 90, speedKts: 5)])
        #expect(merged.perPoint.keys == ["a"])
        // No temperature came back: the plan's own stays.
        #expect(merged.tempC == plan.tempC)
    }

    @Test func windsAreMergedIntoACopyAndThePlanThatWasGivenIsLeftAsItWas() {
        let plan = RoutePlan(perPoint: ["a": PointOverride(clock: "12:00")])
        let amps = [RoutePoint(id: "a", lat: 0, lon: 0)]
        let merged = RouteWinds.merge(plan, amps, ["a": PointWind(dirTrue: 10, speedKts: 2, tempC: 7)])
        #expect(merged.perPoint["a"]!.clock == "12:00")
        #expect(merged.perPoint["a"]!.wind!.dirTrue == 10)
        #expect(merged.tempC == 7)
        #expect(plan.perPoint["a"]!.wind == nil)
    }
}
