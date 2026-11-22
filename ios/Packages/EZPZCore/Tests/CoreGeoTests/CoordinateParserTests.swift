import CoreJS
import CoreTesting
import Foundation
import Testing

@testable import CoreGeo

/// Held to contracts/fixtures/coords/parse.json, which records what the web's coordParse.js answers. Android's CoordinateParserTest,
/// case for case.
struct CoordinateParserTests {
    let fixture: JSONObject
    let tolerance: Double

    init() throws {
        fixture = try Fixtures.load("coords/parse.json")
        tolerance = fixture["toleranceDeg"]!.double
    }

    @Test func parsesAndRefusesExactlyWhatTheWebDoes() {
        let cases = fixture.cases("parse")
        #expect(cases.count >= 50, "the fixture shrank to \(cases.count)")
        let misses = cases.compactMap { c -> String? in
            let text = c["text"]!.string
            let actual = CoordinateParser.parse(text)
            let expected = c["expected"]!
            if expected.isNull {
                return actual.map { "'\(text)' parsed as \($0.lat), \($0.lon) but the web refuses it" }
            }
            guard let actual else { return "'\(text)' was refused but the web reads it" }
            let e = expected.object
            var problems: [String] = []
            if abs(actual.lat - e["lat"]!.double) > tolerance { problems.append("lat \(actual.lat) vs \(e["lat"]!.double)") }
            if abs(actual.lon - e["lon"]!.double) > tolerance { problems.append("lon \(actual.lon) vs \(e["lon"]!.double)") }
            if actual.format != e["format"]!.string { problems.append("format \(actual.format) vs \(e["format"]!.string)") }
            if actual.label != e["label"]!.string { problems.append("label \(actual.label) vs \(e["label"]!.string)") }
            return problems.isEmpty ? nil : "'\(text)': \(problems.joined(separator: "; "))"
        }
        #expect(misses.isEmpty, "\(misses.count) of \(cases.count) differ: \(misses)")
    }

    @Test func saysWhyATextIsNotAPairAndWhetherItSaidASideAsTheWebDoes() {
        let cases = fixture.cases("read")
        #expect(cases.count >= 80, "the fixture shrank to \(cases.count)")
        let misses = cases.compactMap { c -> String? in
            let text = c["text"]!.string
            let reading = CoordinateParser.read(text)
            let problem = c["problem"]?.stringValue
            let signed = c["signed"]?.boolValue
            if let problem { return reading.problem == problem ? nil : "'\(text)': \(reading.problem ?? "nil") vs \(problem)" }
            if let refused = reading.problem { return "'\(text)': refused as \(refused), the web reads it" }
            if reading.signed != signed { return "'\(text)': signed \(reading.signed) vs \(signed.map(String.init) ?? "nil")" }
            return nil
        }
        #expect(misses.isEmpty, "\(misses)")
    }

    @Test func readsArabicIndicDigitsAsNothingAsJavaScriptDoes() {
        // Swift Regex's \d, like Android's ICU, would take them (AGENTS.md §17).
        #expect(CoordinateParser.parse("\u{0663}\u{0664}, -\u{0668}\u{0664}") == nil)
        #expect(CoordinateParser.read("\u{0663}\u{0664}").problem == "unreadable")
        #expect(!CoordinateParser.looksLikeMgrs("\u{0661}\u{0666}SGC12345678"))
    }

    @Test func foldsNoLetterJavaScriptsCaseDoesNot() {
        // The long s and the Kelvin sign are letters a case-folding match would take for s and k; JavaScript's /i does not, and
        // neither are hemispheres or the letters that refuse a word.
        #expect(!CoordinateParser.looksLikeMgrs("16\u{17F}GC12345678"))
        #expect(CoordinateParser.parse("34\u{17F} 84W") == nil)
        #expect(CoordinateParser.parse("34N 84W\u{212A}") != nil)
    }

    @Test func tellsAGridFromACoordinateTheWayTheWebDoes() {
        for c in fixture.cases("looksLikeMgrs") {
            #expect(CoordinateParser.looksLikeMgrs(c["text"]!.string) == c["expected"]!.bool, "looksLikeMgrs('\(c["text"]!.string)')")
        }
    }

    @Test func recognisesACoordinateBeingTypedTheWayTheWebDoes() {
        for c in fixture.cases("looksLikeCoordinateText") {
            let text = c["text"]!.string
            #expect(CoordinateParser.looksLikeCoordinateText(text) == c["expected"]!.bool, "looksLikeCoordinateText('\(text)')")
        }
    }

    @Test func formatsAPairAsToFixedDoes() {
        for c in fixture.cases("formatDecimal") {
            let lat = c["lat"]!.double
            let lon = c["lon"]!.double
            #expect(CoordinateParser.formatDecimal(lat, lon, places: c["places"]!.int) == c["expected"]!.string, "\(lat), \(lon)")
        }
    }

    @Test func writesDegreesMinutesAndSecondsAsTheWebKeepsThemOnAnAnalysedDiagram() {
        let all = fixture.cases("latLongString")
        #expect(all.count >= 10)
        for c in all {
            let lat = c["lat"]!.double
            let lon = c["lon"]!.double
            #expect(CoordinateParser.formatLatLongDms(lat, lon) == c["expected"]!.string, "\(lat), \(lon)")
        }
    }

    @Test func roundsFromTheExactBinaryValueLikeToFixed() {
        // 1.005 is stored as 1.00499999999999989..., so JavaScript gives "1.00"; String(format:) would give "1.01". The Kotlin port
        // has a toFixed of its own; this one is CoreJS's.
        #expect(CoordinateParser.formatDecimal(1.005, 1.0051, places: 2) == "1.00, 1.01")
        #expect(CoordinateParser.formatDecimal(0.125, -0.000001, places: 2) == "0.13, -0.00")  // an exact tie goes up
        #expect(CoordinateParser.formatDecimal(-0.000001, -0.0, places: 5) == "-0.00000, 0.00000")
    }

    @Test func aGridIsNeverReadAsACoordinate() {
        for text in ["16S GC 28864 55349", "18T WL 123 456", "4QFJ12345678"] {
            #expect(CoordinateParser.parse(text) == nil, "\(text)")
        }
    }

    @Test func nilAndBlankTextAreNotCoordinates() {
        #expect(CoordinateParser.parse(nil) == nil)
        #expect(CoordinateParser.parse("    ") == nil)
        #expect(!CoordinateParser.looksLikeCoordinateText(nil))
    }

    @Test func aNumberMayStartWithItsPoint() {
        #expect(
            CoordinateParser.parse(".5, -.25") == CoordinateParser.Parsed(lat: 0.5, lon: -0.25, format: "decimal", label: "decimal degrees")
        )
        #expect(
            CoordinateParser.parse("+.5 +1.25") == CoordinateParser.Parsed(lat: 0.5, lon: 1.25, format: "decimal", label: "decimal degrees")
        )
    }
}
