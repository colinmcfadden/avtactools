import CoreJS
import CoreTesting
import Testing

@testable import CoreGeo

/// Held to contracts/fixtures/coords/share.json, which records what the web's utils/gridShare.js makes of a shared grid. Android's
/// GridShareFixtureTest, case for case, but for the labels that hold a lone surrogate (below).
struct GridShareFixtureTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("coords/share.json") }

    /// A label written with a lone surrogate (`"half \ud83d emoji"`): JavaScript drops it, but a Swift String cannot hold one, so the
    /// fixture's text arrives here with U+FFFD in its place and there is nothing left to drop. Such a label cannot reach the port, and
    /// the case is left out; `theLoneSurrogateCasesAreTheOnesLeftOut` counts them, so the allowance cannot grow unseen.
    private static func holdsALoneSurrogate(_ label: String?) -> Bool { label?.unicodeScalars.contains("\u{FFFD}") ?? false }

    private func place(_ c: JSONObject) -> JSONObject { c["place"]!.object }

    @Test func writesASharedGridsLinesAsTheWebDoes() {
        let all = fixture.cases("shareText")
        #expect(all.count >= 8)
        for c in all where !Self.holdsALoneSurrogate(place(c)["label"]?.optionalString) {
            let p = place(c)
            let text = GridShare.shareText(p["mgrs"]!.string, p["lat"]!.double, p["lon"]!.double, label: p["label"]?.optionalString)
            #expect(text == c["expected"]!.optionalString, "\(p["mgrs"]!.string) \(p["label"]?.optionalString ?? "nil")")
        }
    }

    @Test func makesTheLinkAndCleansALabelAsTheWebDoes() {
        for c in fixture.cases("gridLink") where !Self.holdsALoneSurrogate(c["label"]?.optionalString) {
            #expect(GridShare.gridLink(c["mgrs"]!.string, label: c["label"]?.optionalString) == c["expected"]!.optionalString)
        }
        for c in fixture.cases("cleanLabel") where !Self.holdsALoneSurrogate(c["label"]?.optionalString) {
            #expect(GridShare.cleanLabel(c["label"]?.optionalString) == c["expected"]!.optionalString)
        }
    }

    @Test func theLoneSurrogateCasesAreTheOnesLeftOut() {
        let left =
            fixture.cases("shareText").filter { Self.holdsALoneSurrogate(place($0)["label"]?.optionalString) }.count
            + fixture.cases("gridLink").filter { Self.holdsALoneSurrogate($0["label"]?.optionalString) }.count
            + fixture.cases("cleanLabel").filter { Self.holdsALoneSurrogate($0["label"]?.optionalString) }.count
        #expect(left == 3)
        // What the port does with the U+FFFD it is given instead: keeps it, as the web keeps a real one.
        #expect(GridShare.cleanLabel("half \u{FFFD} emoji") == "half \u{FFFD} emoji")
    }

    @Test func readsALinksFragmentAsTheWebDoes() throws {
        let all = fixture.cases("readGridLink")
        #expect(all.count >= 15)
        for c in all {
            let hash = c["hash"]?.optionalString
            let read = GridShare.readGridLink(hash)
            let expected = c["expected"]!
            if expected.isNull {
                #expect(read == nil, "\(hash ?? "nil")")
            } else {
                let e = expected.object
                let link = try #require(read, "\(hash ?? "nil")")
                #expect(link.choice.mgrs == e["mgrs"]!.string, "\(hash ?? "nil")")
                #expect(abs(link.choice.lat - e["lat"]!.double) <= 1e-9)
                #expect(abs(link.choice.lon - e["lon"]!.double) <= 1e-9)
                #expect(link.name == e["name"]!.string, "\(hash ?? "nil")")
            }
        }
    }

    @Test func decodesMalformedUtf8AsWhatwgDoesOneReplacementForEachMaximalPart() {
        #expect(GridShare.utf8Decode([0xED, 0xA0, 0x80, 0x78]) == "\u{FFFD}\u{FFFD}\u{FFFD}x")
        #expect(GridShare.utf8Decode([0xF0, 0x9F, 0x98, 0x78]) == "\u{FFFD}x")
        #expect(GridShare.utf8Decode([0xE0, 0xA4]) == "\u{FFFD}")
        #expect(GridShare.utf8Decode(Array("é\u{1F681}".utf8)) == "é\u{1F681}")
    }

    @Test func encodesAsJavaScriptsEncodeUriComponentDoes() {
        #expect(GridShare.encodeUriComponent("a b-_.!~*'()&/#?=+%") == "a%20b-_.!~*'()%26%2F%23%3F%3D%2B%25")
        #expect(GridShare.encodeUriComponent("é🚁") == "%C3%A9%F0%9F%9A%81")
    }

    @Test func aMarkJoinedToAnAmpersandOrAnEqualsSignStillSplitsTheFragment() {
        // A String's Characters would join the combining acute accent to "&" and "=", and find neither.
        let link = GridShare.readGridLink("#grid=16SGC2886455349&\u{301}x=1&name=\u{301}A")
        #expect(link?.choice.mgrs == "16S GC 28864 55349")
        #expect(link?.name == "\u{301}A")
    }
}
