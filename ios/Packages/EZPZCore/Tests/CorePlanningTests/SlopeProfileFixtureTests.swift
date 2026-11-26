import CoreJS
import CoreModel
import CoreTesting
import Testing

@testable import CorePlanning

/// Held to contracts/fixtures/planning/profile.json: how the web reads the server's lines through an LZ as a slope profile.
struct SlopeProfileFixtureTests {
    let fixture: JSONObject
    let grounds: [(name: String, ground: SlopeProfile.Ground)]

    init() throws {
        fixture = try Fixtures.load("planning/profile.json")
        grounds = fixture["ground"]!.objectValue!.map { ($0.key, Self.ground($0.value)) }
    }

    static func number(_ e: JSONTree?) -> Double? { e?.numberValue?.value }

    static func ground(_ o: JSONTree) -> SlopeProfile.Ground {
        SlopeProfile.Ground(
            stepM: number(o["stepM"]) ?? .nan, halfLengthM: number(o["halfLengthM"]) ?? .nan,
            headingStepDeg: Int(o["headingStepDeg"]!.numberValue!.value),
            lines: o["lines"]!.arrayValue!.map { line in
                SlopeProfile.GroundLine(
                    heading: Int(line["heading"]!.numberValue!.value), elevationsFt: line["elevationsFt"]!.arrayValue!.map(number),
                    lz: line["lz"]?.arrayValue?.map { number($0) ?? .nan })
            })
    }

    func named(_ name: String) -> SlopeProfile.Ground { grounds.first { same($0.name, name) }!.ground }

    func numbers(_ e: JSONTree) -> [Double?] { e.arrayValue!.map(Self.number) }

    func expectClose(_ expected: [Double?], _ actual: [Double?], _ label: String) {
        #expect(expected.count == actual.count, "\(label)")
        for (i, (e, a)) in zip(expected, actual).enumerated() {
            if let e {
                #expect(a.map { abs(e - $0) <= 1e-6 } == true, "\(label) [\(i)]: expected \(e), got \(String(describing: a))")
            } else {
                #expect(a == nil, "\(label) [\(i)]")
            }
        }
    }

    func expectLine(_ expected: JSONTree, _ actual: SlopeProfile.Line, _ label: String) {
        #expect(Int(expected["heading"]!.numberValue!.value) == actual.heading, "\(label)")
        expectClose(numbers(expected["distancesFt"]!), actual.distancesFt, "\(label) distances")
        expectClose(numbers(expected["elevationsFt"]!), actual.elevationsFt, "\(label) heights")
        if let lz = expected["lz"], !lz.isNull {
            if let actualLz = actual.lz { expectClose(numbers(lz), actualLz, "\(label) lz") } else { Issue.record("\(label): no lz") }
        } else {
            #expect(actual.lz == nil, "\(label)")
        }
        expectClose(
            [Self.number(expected["changeFt"]), Self.number(expected["averageDeg"])], [actual.changeFt, actual.averageDeg],
            "\(label) change")
        if let words = expected["words"] { #expect(primitiveText(words) == actual.words, "\(label)") }
    }

    @Test func theSlopeProfileIsTheWebsForEveryHeadingAndEveryKindOfGround() {
        let cases = fixture["cases"]!.arrayValue!
        #expect(cases.count >= 15)
        for c in cases {
            let label = c["name"]!.stringValue!
            let heading = primitiveText(c["heading"]).flatMap { Int($0) }
            let made = SlopeProfile.slopeProfileFor(named(c["ground"]!.stringValue!), heading)
            guard let expected = c["expected"], !expected.isNull else {
                #expect(made == nil, "\(label)")
                continue
            }
            guard let profile = made else {
                Issue.record("\(label): no profile")
                continue
            }
            expectLine(expected["along"]!, profile.along, "\(label) along")
            expectLine(expected["across"]!, profile.across, "\(label) across")
        }
    }

    @Test func aHeadingPast359IsBroughtRoundAsTheWebBringsIt() throws {
        for c in fixture["line"]!.arrayValue! {
            let heading = Int(c["heading"]!.numberValue!.value)
            let made = try #require(SlopeProfile.profileLine(named(c["ground"]!.stringValue!), heading), "heading \(heading)")
            expectLine(c["expected"]!, made, "heading \(heading)")
        }
    }

    @Test func aLineWhoseCrossingIsNotTwoNumbersMissesTheLz() throws {
        var odd = named("a plane of 5° rising east")
        odd.lines = odd.lines.map { line in
            var changed = line
            if line.heading == 90 { changed.lz = [-100, .nan] }
            return changed
        }
        #expect(try #require(SlopeProfile.slopeProfileFor(odd, 90)).along.words == "Along 090°: the line misses the LZ")
        #expect(
            fixture["cases"]!.arrayValue!.contains { c in
                guard let along = c["expected"]?["along"] else { return false }
                return along["lz"].map { !$0.isNull } ?? false
            })
    }
}
