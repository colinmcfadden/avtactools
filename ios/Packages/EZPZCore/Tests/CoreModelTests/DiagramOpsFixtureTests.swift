import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// Held to contracts/fixtures/workspace/ops.json: what the web's reducer does to one diagram when it is analysed, its graphics are
/// edited, or its view or name change, including every case where it refuses. `updatedAt` and the dirty flag are the web's clock and
/// bookkeeping, and the native stores do those differently, so they are not compared. Android's DiagramOpsFixtureTest.
struct DiagramOpsFixtureTests {
    let fixture: JSONObject
    let env = DiagramNormalizer.Environment(
        now: { fatalError("the fixture supplies timestamps") },
        newId: { fatalError("the fixture supplies an id") }
    )

    init() throws { fixture = try Fixtures.load("workspace/ops.json") }

    func read(_ diagram: JSONTree) -> Diagram { DiagramNormalizer.normalize(diagram, env: env) }

    /// The diagram as the fixture writes it: without the two fields the web stamps itself.
    func written(_ diagram: Diagram) -> JSONTree {
        .object(ModelJSON().encodeObject(diagram).removing("updatedAt").removing("dirty"))
    }

    func run(_ diagram: Diagram, _ op: String, _ args: JSONObject) -> Diagram {
        let collection = content(args["collection"]) ?? ""
        switch op {
        case "completeAnalysis": return DiagramOps.completeAnalysis(diagram, args["analysis"])
        case "resetAnalysis": return DiagramOps.resetAnalysis(diagram)
        case "setAnalysisDraft": return DiagramOps.setAnalysisDraft(diagram, args["analysis"])
        case "setRuntimeTerrainData": return DiagramOps.setRuntimeTerrainData(diagram, args["terrainData"] ?? .null)
        case "setGraphics": return DiagramOps.setGraphics(diagram, args["graphics"])
        case "setGraphicCollection": return DiagramOps.setGraphicCollection(diagram, collection, args["items"])
        case "upsertGraphic": return DiagramOps.upsertGraphic(diagram, collection, args["item"]!)
        case "patchGraphic": return DiagramOps.patchGraphic(diagram, collection, args["id"], args["patch"])
        case "removeGraphic": return DiagramOps.removeGraphic(diagram, collection, args["id"])
        case "setFlightData": return DiagramOps.setFlightData(diagram, args["flightData"])
        case "setView": return DiagramOps.setView(diagram, args["view"])
        case "setName": return DiagramOps.setName(diagram, content(args["name"]))
        default: fatalError("the fixture has an operation this test does not know: \(op)")
        }
    }

    @Test func everyOperationDoesWhatTheWebsReducerDoes() {
        let all = cases(fixture, "ops")
        #expect(all.count >= 40, "the fixture shrank to \(all.count)")
        for c in all {
            let start = read(c["diagram"]!)
            check("\(name(c)) (the starting diagram)", c["diagram"]!, written(start))
            let result = run(start, c["op"]!.stringValue!, c["args"]!.objectValue!)
            check(name(c), c["expected"]!, written(result))
        }
    }

    @Test func aRefusedOperationHandsBackTheVeryDiagramItWasGiven() {
        let refused = cases(fixture, "ops").filter { $0["diagram"] == $0["expected"] }
        #expect(refused.count >= 8)
        for c in refused {
            let start = read(c["diagram"]!)
            let result = run(start, c["op"]!.stringValue!, c["args"]!.objectValue!)
            #expect(start == result, "\(name(c))")
        }
    }

    @Test func theStandardDoghousesAreMadeAsTheWebMakesThem() {
        for c in cases(fixture, "defaultDoghouses") {
            let namespace = c["namespace"]!
            let actual = DiagramOps.defaultDoghouses(c["target"], namespace.isNull ? nil : namespace)
            check(name(c), c["expected"]!, .array(actual))
        }
    }

    @Test func analysisMakesTheDoghousesOnce() {
        for c in cases(fixture, "afterAnalysis") {
            let actual = DiagramOps.afterAnalysis(read(c["diagram"]!), c["analysis"])
            check(name(c), c["expected"]!, written(actual))
        }
    }

    @Test func theConvenienceFormOfTheDoghousesIsTheSameAsTheGeneralOne() {
        let general = DiagramOps.defaultDoghouses(.array([.number(34.5), .number(-84.1)]), .string("abc"))
        #expect(general == DiagramOps.defaultDoghouses(34.5, -84.1, "abc"))
        #expect(general.count == 2)
    }
}

/// Android's GraphicLookupTest.
struct GraphicLookupTests {
    @Test func anIdReadsAsJavaScriptWritesIt() {
        #expect(DiagramOps.idText(.number(12)) == "12")
        #expect(DiagramOps.idText(.number(12.5)) == "12.5")
        #expect(DiagramOps.idText(.number(1_700_000_000_000)) == "1700000000000")
        #expect(DiagramOps.idText(json("12.0")) == "12")
        #expect(DiagramOps.idText(.string("pz-1")) == "pz-1")
        #expect(DiagramOps.idText(.string("12")) == "12")  // text and number with the same digits read the same
        #expect(DiagramOps.idText(.bool(true)) == "true")
        #expect(DiagramOps.idText(.null) == "null")
        #expect(DiagramOps.idText(nil) == "undefined")
    }

    /// Not like Kotlin's approximation, which wrote a literal as it came and a list as JSON: `String(id)` for every value.
    @Test func anIdIsJavaScriptsStringOfItWhateverItIs() {
        #expect(DiagramOps.idText(json("1.50")) == "1.5")
        #expect(DiagramOps.idText(json("1e21")) == "1e+21")
        #expect(DiagramOps.idText(json("[1,2]")) == "1,2")
        #expect(DiagramOps.idText(json("{}")) == "[object Object]")
    }

    func diagram(_ helicopters: [JSONTree]) -> Diagram {
        Diagram(
            id: "d", createdAt: "t", updatedAt: "t", status: .analyzed, target: DiagramTarget(lat: 1, lon: 2),
            graphics: DiagramGraphics(helicopters: helicopters)
        )
    }

    @Test func aGraphicIsFoundByItsIdAsText() {
        let a = object(#"{"id": 1700000000000, "lat": 1}"#)
        let b = object(#"{"id": "pz-1"}"#)
        let d = diagram([.object(a), .object(b)])
        #expect(DiagramOps.graphic(d, "helicopters", "1700000000000").map(JSONTree.object) == .object(a))
        #expect(DiagramOps.graphic(d, "helicopters", "pz-1").map(JSONTree.object) == .object(b))
        #expect(DiagramOps.graphic(d, "helicopters", "nope") == nil)
        #expect(DiagramOps.graphic(d, "units", "pz-1") == nil)  // another collection
        #expect(DiagramOps.graphic(d, "no such collection", "pz-1") == nil)
    }

    @Test func itemsThatAreNotObjectsAreNeverFound() {
        let d = diagram([.number(7), .string("x"), .null])
        #expect(DiagramOps.graphic(d, "helicopters", "7") == nil)
        #expect(DiagramOps.graphic(d, "helicopters", "x") == nil)
    }
}

/// Android's DiagramGeometryTest.
struct DiagramGeometryTests {
    func diagram(detected: String? = nil, custom: String? = nil) -> Diagram {
        Diagram(
            id: "d", createdAt: "t", updatedAt: "t",
            analysis: DiagramAnalysis(customLZ: custom.map(json) ?? .null, detectedLZ: detected.map(json) ?? .null)
        )
    }

    @Test func pointsAreLatitudeThenLongitude() {
        #expect(DiagramGeometry.points(json("[[34.5, -84.1]]")) == [LatLon(lat: 34.5, lon: -84.1)])
    }

    @Test func whatIsNotAPairOfNumbersIsLeftOutNotGuessedAt() {
        let saved = json(#"[[1, 2], "x", [1], ["a", "b"], [null, 3], null, [5, 6]]"#)
        #expect(DiagramGeometry.points(saved) == [LatLon(lat: 1, lon: 2), LatLon(lat: 5, lon: 6)])
    }

    @Test func anythingThatIsNotAnArrayIsNoPoints() {
        #expect(DiagramGeometry.points(nil) == [])
        #expect(DiagramGeometry.points(.null) == [])
        #expect(DiagramGeometry.points(.string("nope")) == [])
    }

    @Test func numericTextIsReadAsTheNumberItSpellsButNaNAndInfinityAreNotPositions() {
        #expect(DiagramGeometry.points(json(#"[["34.5", "-84.1"]]"#)) == [LatLon(lat: 34.5, lon: -84.1)])
        #expect(DiagramGeometry.points(json(#"[["NaN", 1], ["Infinity", 1], [1, "-Infinity"]]"#)) == [])
    }

    @Test func aBoundaryNeedsThreePointsADrawnOneTwo() {
        let two = "[[1, 2], [3, 4]]"
        let three = "[[1, 2], [3, 4], [5, 6]]"
        #expect(DiagramGeometry.boundary(diagram(detected: two)) == [])
        #expect(DiagramGeometry.boundary(diagram(detected: three)).count == 3)
        #expect(DiagramGeometry.drawn(diagram(custom: two)).count == 2)
        #expect(DiagramGeometry.drawn(diagram(custom: "[[1, 2]]")) == [])
    }

    @Test func aBoundaryOfThreeEntriesOneOfWhichIsRubbishIsOnlyTwoPointsSoNoBoundary() {
        #expect(DiagramGeometry.polygon(json(#"[[1, 2], [3, 4], "junk"]"#)) == [])
    }
}
