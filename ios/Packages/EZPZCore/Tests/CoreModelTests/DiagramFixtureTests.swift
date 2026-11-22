import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// Held to contracts/fixtures/workspace/diagram.json, which records what the web's useLzWorkspace.js makes of each saved shape. The
/// native apps must open every diagram the web has ever saved, and write ones the web opens. Android's DiagramFixtureTest.
struct DiagramFixtureTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("workspace/diagram.json") }

    // The fixtures always supply ids and timestamps; a normalizer that reaches for the clock or a random id here is doing something
    // the web would do differently.
    let env = DiagramNormalizer.Environment(
        now: { fatalError("the fixture should supply timestamps") },
        newId: { fatalError("the fixture should supply an id") }
    )

    func options(_ element: JSONTree?) -> DiagramNormalizer.Options {
        guard let o = element?.objectValue else { return DiagramNormalizer.Options() }
        return DiagramNormalizer.Options(
            id: content(o["id"]),
            savedId: o["savedId"],
            name: content(o["name"]),
            dirty: o["dirty"]?.boolValue,
            createdAt: content(o["createdAt"]),
            updatedAt: content(o["updatedAt"])
        )
    }

    @Test func everySavedShapeNormalizesTheWayTheWebDoes() {
        let all = cases(fixture, "normalize")
        #expect(all.count >= 20, "the fixture shrank to \(all.count)")
        for c in all {
            let actual = DiagramNormalizer.normalize(c["source"], options: options(c["options"]), env: env)
            check(name(c), c["expected"]!, encoded(actual))
        }
    }

    @Test func aLegacySnapshotOrApiRecordBecomesOneDiagram() {
        for c in cases(fixture, "legacySnapshot") {
            let actual = DiagramNormalizer.normalizeLegacySnapshot(c["snapshot"], options: options(c["options"]), env: env)
            check(name(c), c["expected"]!, encoded(actual))
        }
    }

    @Test func aBlankDiagramBoundToATarget() {
        for c in cases(fixture, "fromTarget") {
            let a = c["args"]!.objectValue!
            let actual = DiagramNormalizer.fromTarget(
                a["target"],
                mgrs: content(a["mgrs"]) ?? "",
                id: content(a["id"]),
                name: content(a["name"]) ?? "",
                savedId: a["savedId"],
                mapData: a["mapData"]?.objectValue ?? JSONObject(),
                view: a["view"]?.objectValue,
                createdAt: content(a["createdAt"]),
                env: env
            )
            let expected = c["expected"]!
            if expected.isNull {
                #expect(actual == nil, "\(name(c))")
            } else {
                #expect(actual != nil, "\(name(c))")
                if let actual { check(name(c), expected, encoded(actual)) }
            }
        }
    }

    @Test func savingStripsTheTerrainRasterAndTheDirtyFlag() {
        for c in cases(fixture, "serialize") {
            let input = c["diagram"]!
            let includeTerrain = c["options"]?.objectValue?["includeTerrainData"]?.boolValue ?? false
            let expected = c["expected"]!
            if input.isNull {
                #expect(expected.isNull, "\(name(c))")
            } else {
                let diagram = DiagramNormalizer.normalize(input, env: env)
                check(name(c), expected, encoded(DiagramNormalizer.serialize(diagram, includeTerrainData: includeTerrain)))
            }
        }
    }

    @Test func aTargetIsReadFromAnArrayOrAnObjectAndABlankGridStaysAsGiven() {
        for c in cases(fixture, "target") {
            let actual = DiagramNormalizer.normalizeTarget(c["target"], mgrs: c["mgrs"])
            let expected = c["expected"]!
            let label = "\(c["target"]?.serialized ?? "-") / \(c["mgrs"]?.serialized ?? "-")"
            if expected.isNull {
                #expect(actual == nil, "\(label)")
            } else {
                #expect(actual != nil, "\(label)")
                if let actual { check(label, expected, encoded(actual)) }
            }
        }
    }

    @Test func whatADiagramMayBeUsedForFollowsItsTargetAndStatus() {
        for c in cases(fixture, "capabilities") {
            let d = c["diagram"]!
            guard let o = d.objectValue else {
                #expect(c["canAnalyze"]!.boolValue == false, "\(name(c))")
                continue
            }
            let diagram = DiagramNormalizer.normalize(
                .object(
                    JSONObject([("id", .string("x")), ("createdAt", .string("t")), ("target", o["target"]!), ("status", o["status"]!)])),
                env: env
            )
            // normalize derives status from the target and analysis, so for these inputs (no analysis) "analyzed" stands only
            // because it was asked for.
            #expect(c["canAnalyze"]!.boolValue == diagram.canAnalyze, "\(name(c))")
            #expect(c["canEditGraphics"]!.boolValue == diagram.canEditGraphics, "\(name(c))")
        }
    }

    @Test func aSessionStartsFromNothingAListAWorkspaceOrASingleSnapshot() {
        let all = cases(fixture, "workspace")
        #expect(all.count >= 10)
        for c in all {
            let source = c["source"].flatMap { $0.isNull ? nil : $0 }
            check(name(c), c["expected"]!, encoded(DiagramNormalizer.createWorkspace(source, env: env)))
        }
    }

    @Test func aDiagramSurvivesTheTripThroughTheModelUnchanged() {
        // The web must be able to open what the app saves: encode a normalized diagram and normalize it again, and nothing may move.
        for c in cases(fixture, "normalize") {
            let first = DiagramNormalizer.normalize(c["source"], options: options(c["options"]), env: env)
            let again = DiagramNormalizer.normalize(encoded(first), env: env)
            #expect(first == again, "\(name(c))")
        }
    }

    @Test func fieldsANewerWebReleaseAddsToAGraphicAreKept() throws {
        let c = try #require(cases(fixture, "normalize").first { name($0).hasPrefix("a full current diagram") })
        let diagram = DiagramNormalizer.normalize(c["source"], env: env)
        let helicopter = try #require(diagram.graphics.helicopters.first?.objectValue)
        #expect(diagram.graphics.helicopters.count == 1)
        #expect(helicopter.has("futureField"), "an unknown field on a helicopter was dropped")
        let reencoded = encoded(diagram)["graphics"]?["helicopters"]?[0]
        #expect(reencoded == .object(helicopter))
    }
}

/// Backstops are drawn on the web alone so far; a save here must neither lose one nor add the field to a diagram that has none.
struct DiagramBackstopsTests {
    let backstops = json(#"[{"id": "bs-1", "points": [{"lat": 34.8, "lon": -84.09}, {"lat": 34.8, "lon": -84.07}], "later": 1}]"#)

    func doc(_ graphics: String) -> JSONTree { json(#"{"id": "d", "graphics": \#(graphics)}"#) }

    func graphicsAfterASave(_ source: JSONTree) -> JSONObject {
        DiagramJson.encode(DiagramNormalizer.serialize(DiagramNormalizer.normalize(source)))["graphics"]!.objectValue!
    }

    @Test func aBackstopTheWebDrewComesThroughASaveWhole() {
        #expect(graphicsAfterASave(doc(#"{"backstops": \#(backstops.serialized)}"#))["backstops"] == backstops)
    }

    @Test func aDiagramWithNoneHasNoSuchField() {
        #expect(!graphicsAfterASave(doc("{}")).has("backstops"))
    }

    @Test func anOperationCanNameThemOnADiagramThatHasNoneYet() {
        #expect(DiagramOps.graphicCollections.contains("backstops"))
    }
}
