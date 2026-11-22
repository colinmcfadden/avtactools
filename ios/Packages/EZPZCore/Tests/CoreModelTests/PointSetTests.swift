import CoreJS
import CoreTesting
import Foundation
import Testing

@testable import CoreModel

/// What a saved set of points keeps through a read and a write: everything the web wrote, and what this version does not know.
/// Android's PointSetTest.
struct PointSetTests {
    /// The points the web saved, as the real server returned them.
    let recorded: [JSONTree]

    init() throws {
        let responses = try Fixtures.load("network/responses.json")["responses"]!.arrayValue!.elements
        let get = responses.first { $0["name"]?.stringValue == "pointset: get" }!
        recorded = get["body"]!["points"]!.arrayValue!.elements
    }

    func doc(_ points: [JSONTree]) -> JSONObject { JSONObject([("points", .array(points))]) }

    func read(_ points: [JSONTree]) -> PointSet { PointSets.parse(id: "uuid-1", savedId: nil, name: "SET", data: doc(points)) }

    @Test func thePointsTheWebSavedAreReadAndWrittenBackAsTheyWere() {
        let set = read(recorded)
        #expect(set.points.count == 2)
        #expect(set.points[0].name == "BLUE 1")
        #expect(set.points[0].elevationFt == 1730)
        #expect(set.points[1].elevationFt == nil)
        #expect(set.points[1].id == "lps-1-ef34gh")
        #expect(set.points[1].at == LatLon(lat: 34.601, lon: -84.119))
        #expect(set.unreadable.isEmpty)
        check("the recorded set", .object(doc(recorded)), .object(PointSets.serialize(set)))
    }

    @Test func aFieldANewerReleaseAddsToAPointSurvivesAnEditOfThePoint() {
        let newer = recorded.map { JSONTree.object($0.objectValue!.setting("aFieldFromANewerRelease", json(#"{"n": 1}"#))) }
        let set = read(newer)
        check("a newer document", .object(doc(newer)), .object(PointSets.serialize(set)))

        var moved = set
        moved.points = set.points.map { p in
            var p = p
            if p.id == "lps-0-ab12cd" { p.lat = 12.5 }
            return p
        }
        let written = PointSets.serialize(moved)["points"]!.arrayValue!
        #expect(written[0]["lat"] == .number(12.5))
        #expect(written[0]["aFieldFromANewerRelease"] == json(#"{"n": 1}"#))
    }

    @Test func aFieldThisVersionKnowsIsNeverOverwrittenByAStaleExtra() {
        let stale = SetPoint(
            id: "a", name: "NEW", description: "", group: "", icon: "", elevationFt: nil, lat: 1, lon: 2,
            extras: JSONObject([("name", .string("OLD")), ("other", .number(1))])
        )
        let written = PointSets.serialize(PointSet(id: "u", name: "S", points: [stale]))["points"]![0]!
        #expect(written["name"] == .string("NEW"))
        #expect(written["other"] == .number(1))
    }

    @Test func anEntryWithNoPositionIsKeptAsItWasAfterTheOthersAndCounted() {
        let noLat = json(#"{"id": "x", "name": "NO LAT", "lon": -84.0}"#)
        // A number written as text is not a position.
        let aString = json(#"{"id": "y", "name": "STRING", "lat": "34.5", "lon": "-84.0"}"#)
        let notAnObject = JSONTree.string("junk")
        let set = read([noLat, recorded[0], aString, notAnObject])

        #expect(set.points.count == 1)
        #expect(set.unreadable == [noLat, aString, notAnObject])
        #expect(set.pointCount == 4)
        let written = PointSets.serialize(set)["points"]!.arrayValue!
        #expect(written.count == 4)
        #expect(written[1] == noLat)
        #expect(written[3] == notAnObject)
    }

    @Test func textThatIsMissingOrNotTextIsEmptyAnElevationThatIsNotANumberIsNoneAndAPointWithNoIdIsNamedByItsPlace() {
        let odd = json(#"{"name": 7, "lat": 1.0, "lon": 2.0, "elevationFt": "high", "group": null}"#)
        let p = read([recorded[0], odd]).points[1]
        #expect(p.id == "pt-1")
        #expect(p.name == "")
        #expect(p.group == "")
        #expect(p.description == "")
        #expect(p.elevationFt == nil)
    }

    @Test func aDocumentWithNoPointsIsAnEmptySetAndASetWithNoneWritesAnEmptyList() {
        #expect(PointSets.parse(id: "u", savedId: nil, name: "S", data: JSONObject()).points.isEmpty)
        #expect(PointSets.parse(id: "u", savedId: nil, name: "S", data: JSONObject([("points", .null)])).points.isEmpty)
        #expect(PointSets.serialize(PointSet(id: "u", name: "S", points: []))["points"] == .array([]))
    }

    @Test func theSavedIdAndTheNameBelongToTheRecordNotTheDocument() {
        let set = PointSets.parse(id: "uuid-9", savedId: 42, name: "NAME", data: doc(recorded))
        #expect(set.id == "uuid-9")
        #expect(set.savedId == 42)
        #expect(set.name == "NAME")
        #expect(PointSets.serialize(set).keys == ["points"])
    }

    @Test func aSetMadeFromAnLpsImportTakesTheFilesPointsInOrderWithAnIdForEachFromItsPlace() {
        let parsed = LocalPointSet(
            name: "NORTH GA",
            points: [
                LocalPoint(
                    name: "BLUE 1", description: "Landing zone", group: "LZ", icon: "lz", elevationFt: 1730, lat: 34.5123, lon: -84.2231),
                LocalPoint(name: "FARP", description: "", group: "Default", icon: "", elevationFt: nil, lat: 34.601, lon: -84.119),
            ]
        )
        let set = PointSets.fromLps(id: "uuid-1", parsed: parsed) { "lps-\($0)" }
        #expect(set.name == "NORTH GA")
        #expect(set.points.map(\.id) == ["lps-0", "lps-1"])
        #expect(set.points[0].group == "LZ")
        #expect(set.points[1].elevationFt == nil)
        // and it reads back as itself
        #expect(PointSets.parse(id: "uuid-1", savedId: nil, name: "NORTH GA", data: PointSets.serialize(set)) == set)
    }

    @Test func aPointIsFoundByItsId() {
        let set = read(recorded)
        #expect(set.point("lps-1-ef34gh")?.name == "FARP")
        #expect(set.point("nope") == nil)
    }
}

/// Typing a name on a route point puts it on the local point it names: the web's rule, held to the cases it was generated over.
/// Android's LocalPointNamesTest.
struct LocalPointNamesTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("localpoints/match.json") }

    func number(_ value: JSONTree?) -> Double? { content(value).flatMap { Double($0) } }

    func pointsOf(_ source: JSONObject) -> [SetPoint] {
        source["points"]!.arrayValue!.elements.enumerated().map { i, p in
            SetPoint(
                id: "p\(i)", name: p["name"]!.stringValue!, description: "", group: "", icon: "",
                elevationFt: p["elevationFt"]?.numberValue?.value, lat: number(p["lat"])!, lon: number(p["lon"])!
            )
        }
    }

    @Test func everyCaseTheWebWasRunOverComesOutTheSame() {
        let names = LocalPointNames(pointsOf(fixture))
        let all = fixture["cases"]!.arrayValue!.elements
        #expect(all.count >= 20)
        for c in all {
            let typed = c["typed"]!.stringValue!
            let got = names.match(typed)
            #expect(got.name == c["name"]!.stringValue!, "name for '\(typed)'")
            #expect(got.at?.lat == number(c["lat"]), "lat for '\(typed)'")
            #expect(got.at?.lon == number(c["lon"]), "lon for '\(typed)'")
            #expect(got.chartElevationFt == number(c["chartElevationFt"]), "elevation for '\(typed)'")
        }
    }

    @Test func noPointsNoMatchAndTheNameIsStillPutInCapitals() {
        let got = LocalPointNames([SetPoint]()).match("blue 1")
        #expect(got.name == "BLUE 1")
        #expect(got.at == nil)
        #expect(got.chartElevationFt == nil)
    }

    @Test func onlyOneLeadingDotOfWhatIsTypedIsIgnored() {
        let names = LocalPointNames([
            SetPoint(id: "a", name: "ALPHA", description: "", group: "", icon: "", elevationFt: 5, lat: 1, lon: 2),
            SetPoint(id: "b", name: ".BRAVO", description: "", group: "", icon: "", elevationFt: nil, lat: 3, lon: 4),
        ])
        #expect(names.match(".alpha").at == LatLon(lat: 1, lon: 2))
        #expect(names.match("..alpha").at == nil)
        #expect(names.match(".bravo").at == nil)  // the dot is stripped from what is typed, never from a point's own name
        #expect(names.match("..bravo").at == LatLon(lat: 3, lon: 4))
    }

    @Test func anElevationOfZeroIsAnElevation() {
        let names = LocalPointNames([SetPoint(id: "a", name: "SEA", description: "", group: "", icon: "", elevationFt: 0, lat: 1, lon: 2)])
        #expect(names.match("sea").chartElevationFt == 0)
    }

    /// Names are told apart by their UTF-16 units, as Kotlin's map tells them: a precomposed é is not an e with a combining accent,
    /// though Swift's `==` says it is.
    @Test func aNameIsMatchedUnitForUnitNotByCanonicalEquivalence() {
        let names = LocalPointNames([
            SetPoint(id: "a", name: "CAF\u{C9}", description: "", group: "", icon: "", elevationFt: nil, lat: 1, lon: 2)
        ])
        #expect(names.match("caf\u{E9}").at != nil)
        #expect(names.match("cafe\u{301}").at == nil)
    }
}
