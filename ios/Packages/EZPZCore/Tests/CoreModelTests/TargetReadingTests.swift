import CoreJS
import Testing

@testable import CoreModel

/// The diagram normaliser reads a target with JavaScript's `Number()` and trims a grid as JavaScript trims, as the web's
/// normalizeLzDiagram does. The parts of Android's JsNumberOfTest about the normaliser; `JsNumber.of` and `Js.trim` themselves are
/// CoreJS's, held by CoreJSTests.
struct TargetReadingTests {
    @Test func aTargetIsReadWithJavaScriptsNumber() {
        let hex = DiagramNormalizer.normalizeTarget(json(#"{"lat": "0x22", "lon": "-84.08219"}"#))
        #expect(hex?.lat == 34)
        let lists = DiagramNormalizer.normalizeTarget(json(#"[[34.783817], ["-84.08219"]]"#))
        #expect(lists == DiagramTarget(lat: 34.783817, lon: -84.08219, mgrs: ""))
        #expect(DiagramNormalizer.normalizeTarget(json(#"{"lat": true, "lon": []}"#)) == DiagramTarget(lat: 1, lon: 0, mgrs: ""))
        #expect(DiagramNormalizer.normalizeTarget(json(#"{"lat": "north", "lon": 1}"#)) == nil)
    }

    @Test func aGridIsBlankOrNotAsJavaScriptsTrimSays() {
        let target = json(#"{"lat": 34.5, "lon": -84.1, "mgrs": "own"}"#)
        // A byte order mark alone is blank to JavaScript, so the target's own grid is taken; U+001C alone is not, so it is kept.
        #expect(DiagramNormalizer.normalizeTarget(target, mgrs: .string("\u{FEFF}"))?.mgrs == "own")
        #expect(DiagramNormalizer.normalizeTarget(target, mgrs: .string("\u{1C}"))?.mgrs == "\u{1C}")
        #expect(DiagramNormalizer.normalizeTarget(target, mgrs: .string(" 16S "))?.mgrs == " 16S ")
    }

    @Test func digitsOfAnotherScriptAreNotANumberAndNeverEndTheRead() {
        #expect(JsNumber.of(.string("٣٤")).isNaN)
        #expect(JsNumber.of(.string("３４")).isNaN)
        #expect(DiagramNormalizer.normalizeTarget(json(#"{"lat": "٣٤", "lon": "-84"}"#)) == nil)
    }
}
