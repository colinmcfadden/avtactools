import CoreJS
import CoreModel
import CoreTesting
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/controlMeasures.json, which the web's setLines.js, flots.js and phaseLines.js write. Screen
/// numbers there are rounded to a billionth, so they are compared within two billionths: a sine may differ in its last digit between
/// machines (AGENTS.md §11), and a value that lands on a rounding edge can round either way.
struct ControlMeasuresTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("planning/controlMeasures.json") }

    static func points(_ tree: JSONTree?) -> [ScreenPoint] {
        (tree?.arrayValue ?? JSONArray()).map { ScreenPoint(x: $0["x"]!.numberValue!.value, y: $0["y"]!.numberValue!.value) }
    }

    static func near(_ a: Double, _ b: Double?) -> Bool { b.map { abs(a - $0) <= 2e-9 } ?? false }

    /// Text compared as JavaScript compares it, by UTF-16 units: a Swift String calls `é` and `e` + U+0301 the same.
    static func same(_ a: String, _ b: String?) -> Bool { b.map { a.utf16.elementsEqual($0.utf16) } ?? false }

    @Test func everyScallopRunIsTheWebs() throws {
        let cases = try #require(fixture["scallops"]?.arrayValue)
        #expect(cases.count == 15)
        for c in cases {
            let what = "\(c["line"]!.stringValue!) \(c["option"]!.stringValue!)"
            let options = c["options"]
            let within = options?["within"].map {
                ScreenBox(
                    minX: $0["minX"]!.numberValue!.value, minY: $0["minY"]!.numberValue!.value, maxX: $0["maxX"]!.numberValue!.value,
                    maxY: $0["maxY"]!.numberValue!.value)
            }
            let runs = Flots.scallopRuns(
                Self.points(c["pixels"]), diameter: options?["diameter"]?.numberValue?.value ?? Flots.scallopPoints,
                flipped: options?["flipped"]?.boolValue ?? false, within: within)
            let expected = (c["runs"]?.arrayValue ?? JSONArray()).map { Self.points($0) }
            #expect(runs.count == expected.count, "\(what): runs")
            for (run, want) in zip(runs, expected) {
                #expect(run.count == want.count, "\(what): points in a run")
                for (p, q) in zip(run, want) { #expect(Self.near(p.x, q.x) && Self.near(p.y, q.y), "\(what): \(p) for \(q)") }
            }
        }
    }

    @Test func everyLabelSpotIsTheWebs() throws {
        let lines = try #require(fixture["scallops"]?.arrayValue)
        let pixelsOf = Dictionary(lines.map { ($0["line"]!.stringValue!, Self.points($0["pixels"])) }, uniquingKeysWith: { a, _ in a })
        for c in try #require(fixture["labelSpots"]?.arrayValue) {
            let line = c["line"]!.stringValue!
            let spot = Flots.labelSpot(pixelsOf[line]!, flipped: c["flipped"]!.boolValue!)
            if c["spot"]!.isNull {
                #expect(spot == nil, "\(line)")
            } else {
                let s = try #require(spot, "\(line)")
                let want = c["spot"]!
                #expect(Self.near(s.x, want["x"]?.numberValue?.value) && Self.near(s.y, want["y"]?.numberValue?.value), "\(line)")
                #expect(Self.near(s.awayX, want["awayX"]?.numberValue?.value), "\(line)")
                #expect(Self.near(s.awayY, want["awayY"]?.numberValue?.value), "\(line)")
            }
        }
    }

    @Test func everyPhaseLinesEndsAndLabelShiftIsTheWebs() throws {
        let lines = try #require(fixture["scallops"]?.arrayValue)
        let pixelsOf = Dictionary(lines.map { ($0["line"]!.stringValue!, Self.points($0["pixels"])) }, uniquingKeysWith: { a, _ in a })
        for c in try #require(fixture["phaseLineEnds"]?.arrayValue) {
            let line = c["line"]!.stringValue!
            let ends = PhaseLines.ends(pixelsOf[line]!)
            let want = c["ends"]!.arrayValue!
            #expect(ends.count == want.count, "\(line)")
            for (e, w) in zip(ends, want) {
                #expect(Self.near(e.x, w["x"]?.numberValue?.value) && Self.near(e.y, w["y"]?.numberValue?.value), "\(line)")
                #expect(Self.near(e.ux, w["ux"]?.numberValue?.value) && Self.near(e.uy, w["uy"]?.numberValue?.value), "\(line)")
            }
        }
        for c in try #require(fixture["labelShifts"]?.arrayValue) {
            let shift = PhaseLines.labelShift(ux: c["ux"]!.numberValue!.value, uy: c["uy"]!.numberValue!.value)
            #expect(Self.near(shift.fx, c["shift"]?["fx"]?.numberValue?.value), "\(c.serialized)")
            #expect(Self.near(shift.fy, c["shift"]?["fy"]?.numberValue?.value), "\(c.serialized)")
        }
    }

    @Test func everyNameAndLabelIsTheWebs() throws {
        let names = try #require(fixture["names"]?.arrayValue)
        #expect(names.count == 23)
        for c in names {
            let text: JSONTree? = c["undefined"]?.boolValue == true ? nil : c["text"]
            let name = PhaseLines.cleanName(text)
            #expect(Self.same(name, c["name"]?.stringValue), "\(c["text"]!.serialized) is \(name)")
            #expect(Self.same(PhaseLines.label(.object(["name": .string(name)])), c["label"]?.stringValue), "\(c["text"]!.serialized)")
        }
        for c in try #require(fixture["labels"]?.arrayValue) {
            #expect(PhaseLines.label(c["line"]) == c["label"]?.stringValue, "\(c.serialized)")
        }
        for c in try #require(fixture["nextNames"]?.arrayValue) {
            #expect(PhaseLines.nextName(c["lines"]?.arrayValue.map(Array.init)) == c["next"]?.stringValue, "\(c["name"]!.stringValue!)")
        }
    }

    @Test func aPhaseLineIsMadeRenamedAndAFlotFlippedAsTheWebDoes() throws {
        let made = PhaseLines.create(
            points: [LatLon(lat: 34.8, lon: -84.09), LatLon(lat: 34.81, lon: -84.08)], id: "pl-1", name: .string("pl red"))
        #expect(JSONTree.object(made) == fixture["created"]?[0])
        let line = try #require(try JSONTree.parse(#"{"id":"p","name":"RED","points":[]}"#).objectValue)
        for c in try #require(fixture["renamed"]?.arrayValue) {
            let renamed = PhaseLines.rename(line, to: c["to"])
            #expect(renamed["name"]?.stringValue == c["name"]?.stringValue, "\(c.serialized)")
        }
        #expect(PhaseLines.rename(line, to: .string("  ")) === line)
        for c in try #require(fixture["flipped"]?.arrayValue) {
            #expect(JSONTree.object(Flots.flip(c["flot"]!.objectValue!)).serialized == c["result"]!.serialized, "\(c.serialized)")
        }
    }

    @Test func linesTheirPointsAndLengthsAreTheWebs() throws {
        for c in try #require(fixture["lines"]?.arrayValue) {
            let line = c["line"]
            #expect(SetLines.plainPoints(SetLines.linePoints(line)) == c["points"]!, "\(c["name"]!.stringValue!)")
            #expect(
                Self.near(SetLines.lineLengthKm(line), c["lengthKm"]?.numberValue?.value)
                    || abs(SetLines.lineLengthKm(line) - c["lengthKm"]!.numberValue!.value) <= 1e-6)
        }
        for c in try #require(fixture["linesFromData"]?.arrayValue) {
            let lines = SetLines.linesFromData(c["data"], key: c["key"]!.stringValue!)
            #expect(JSONTree.array(lines) == c["lines"]!, "\(c.serialized)")
        }
        for c in try #require(fixture["savedLines"]?.arrayValue) {
            #expect(JSONTree.array(SetLines.savedLines(c["lines"]?.arrayValue.map(Array.init))) == c["saved"]!)
        }
    }

    @Test func setsOfControlMeasuresAreKnownAsTheWebKnowsThem() throws {
        for c in try #require(fixture["measuresSets"]?.arrayValue) {
            let name = c["name"]!.stringValue!
            #expect(SetLines.lineCount(c["set"]) == c["lineCount"]?.numberValue?.value, "\(name)")
            #expect(SetLines.isMeasuresSet(c["set"], made: c["made"]!.boolValue!) == c["measures"]?.boolValue, "\(name)")
        }
        for c in try #require(fixture["measuresSetIds"]?.arrayValue) {
            let id: JSONTree? = c["undefined"]?.boolValue == true ? nil : c["setId"]
            #expect(SetLines.isMeasuresSetId(id) == c["measures"]?.boolValue, "\(c.serialized)")
        }
    }

    @Test func setsWithTheirLinesAreTheWebs() throws {
        for c in try #require(fixture["setsWithLines"]?.arrayValue) {
            let sets = c["sketchSets"]!.arrayValue!.map { $0.objectValue! }
            let lists = c["lists"]!.objectValue!.map { (name: $0.key, lines: Array($0.value.arrayValue!)) }
            let opened = c["opened"]!.arrayValue!
            let result = SetLines.setsWithLines(sets, lists: lists) { id in opened.contains { Js.strictEquals($0, id) } }
            #expect(JSONTree.array(result.map(JSONTree.object)).serialized == c["result"]!.serialized, "\(c["sketchSets"]!.serialized)")
        }
    }
}
