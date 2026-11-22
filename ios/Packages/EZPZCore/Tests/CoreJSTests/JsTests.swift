import Testing

@testable import CoreJS

func json(_ text: String) -> JSONTree {
    do { return try JSONTree.parse(text) } catch { fatalError("\(error) in \(text)") }
}

/// What JavaScript does that the ports lean on, each against what JavaScript (Node) answers. Android's JsTest, case for case.
struct JsTests {
    @Test func keysGoInJavaScriptsOrder() {
        // Object.keys(JSON.parse('{"b":1,"10":2,"a":3,"2":4,"01":5,"-1":6,"1.5":7,"4294967294":8,"4294967295":9}'))
        let parsed = json(#"{"b":1,"10":2,"a":3,"2":4,"01":5,"-1":6,"1.5":7,"4294967294":8,"4294967295":9}"#)
        let expected = ["2", "10", "4294967294", "b", "a", "01", "-1", "1.5", "4294967295"]
        #expect(Js.keys(parsed) == expected)
        #expect(parsed.objectValue!.keys == expected)  // the object keeps JavaScript's order itself
        #expect(Js.orderedKeys(["b", "10", "a", "2"]) == ["2", "10", "b", "a"])
        #expect(Js.orderedKeys(["z", "y", "x"]) == ["z", "y", "x"])
        #expect(Js.orderedKeys(["z", "0"]) == ["0", "z"])
        #expect(Js.orderedKeys([String]()) == [])
    }

    @Test func anArrayIndexIsAWholeNumbersOwnText() {
        for key in ["0", "7", "10", "4294967294"] { #expect(Js.isArrayIndex(key), "\(key)") }
        for key in ["", "00", "01", "-0", "-1", "+1", "1.0", "1e3", " 1", "4294967295", "99999999999", "x", "٣"] {
            #expect(!Js.isArrayIndex(key), "\(key)")
        }
    }

    @Test func strictEqualityComparesNumbersByValueAndNeverTextWithANumber() {
        func same(_ a: String, _ b: String) -> Bool { Js.strictEquals(json(a), json(b)) }
        #expect(same("1", "1.0"))
        #expect(same("1e2", "100"))
        #expect(same("-0", "0"))
        #expect(same(#""1""#, #""1""#))
        #expect(!same(#""1""#, "1"))
        #expect(!same("1", #""1""#))
        #expect(!same(#""a""#, #""A""#))
        #expect(same("true", "true"))
        #expect(!same("true", "1"))
        #expect(!same("true", #""true""#))
        #expect(same("null", "null"))
        #expect(!same("null", "0"))
        #expect(!same("{}", "{}"))  // two objects, however alike
        #expect(!same("[1]", "[1]"))
        #expect(same("9007199254740992", "9007199254740993"))  // one double, so one number to JavaScript
    }

    @Test func textIsComparedByItsUTF16UnitsNotByCanonicalEquivalence() {
        // Swift's == says "é" precomposed is "e" and a combining accent; JavaScript's === does not.
        let precomposed = JSONTree.string("\u{E9}")
        let combined = JSONTree.string("e\u{301}")
        #expect(!Js.strictEquals(precomposed, combined))
        #expect(precomposed != combined)
        #expect(Js.idKey(precomposed) != Js.idKey(combined))
        #expect(Set([Js.idKey(precomposed)!, Js.idKey(combined)!]).count == 2)
    }

    @Test func undefinedIsOnlyItselfAnObjectIsItselfAndNaNIsNotEvenItself() {
        let object = JSONTree.object(JSONObject())
        let list = JSONTree.array(JSONArray())
        #expect(Js.strictEquals(nil, nil))
        #expect(!Js.strictEquals(nil, .null))
        #expect(!Js.strictEquals(.null, nil))
        #expect(Js.strictEquals(object, object))
        #expect(Js.strictEquals(list, list))
        let nan = JSONTree.number(.nan)
        #expect(!Js.strictEquals(nan, nan))
        #expect(Js.strictEquals(.number(.infinity), .number(.infinity)))
        #expect(Js.strictEquals(.number(1), .number(1.0)))
        #expect(Js.strictEquals(.number(-0.0), .number(0)))
        #expect(!Js.sameValue(object, object))  // versions built apart share no object
        #expect(Js.sameValue(.number(1), json("1.0")))
    }

    @Test func aNumberIsWhatJSONWritesAsOne() {
        #expect(Js.numberOf(json("1")) == 1)
        #expect(Js.numberOf(json("-1.25e1")) == -12.5)
        #expect(Js.numberOf(json("1e400")) == .infinity)  // JSON.parse("1e400") is Infinity
        #expect(Js.numberOf(json(#""1""#)) == nil)
        #expect(Js.numberOf(json("true")) == nil)
        #expect(Js.numberOf(.null) == nil)
        #expect(Js.numberOf(nil) == nil)
        #expect(Js.numberOf(json("[1]")) == nil)
    }

    @Test func anIdIsTextOrAFiniteNumber() {
        #expect(Js.isId(.string("h-1")))
        #expect(Js.isId(.string("")))
        #expect(Js.isId(.number(7)))
        #expect(Js.isId(.number(-0.5)))
        #expect(!Js.isId(json("1e400")))
        #expect(!Js.isId(.number(.nan)))
        #expect(!Js.isId(.bool(true)))
        #expect(!Js.isId(.null))
        #expect(!Js.isId(nil))
        #expect(!Js.isId(.object(JSONObject())))
    }

    @Test func anIdsKeyKeepsTextAndNumbersApart() {
        let ids = json(#"["1", 1, 1.0, 1e0, -0, 0, 0.1, 1e21, "number:1"]"#).arrayValue!
        #expect(
            ids.map { Js.idKey($0)?.string } == [
                "string:1", "number:1", "number:1", "number:1", "number:0", "number:0", "number:0.1", "number:1e+21", "string:number:1",
            ])
        #expect(Js.idKey(.bool(true)) == nil)
        #expect(Js.idKey(.null) == nil)
        #expect(Js.idKey(.object(JSONObject())) == nil)
    }

    @Test func truthinessIsJavaScripts() {
        let values = json(#"[false, 0, -0, 0.0, "", null, true, 1, -1, 0.5, " ", "0", "false", [], {}]"#).arrayValue!
        #expect(
            values.map { Js.truthy($0) } == [
                false, false, false, false, false, false, true, true, true, true, true, true, true, true, true,
            ])
        #expect(!Js.truthy(nil))
        #expect(!Js.truthy(.number(.nan)))
    }

    @Test func mathRoundTakesAHalfUpTowardPositiveInfinity() {
        // [44.5, 359.5, -0.5, -1.5, 2.5, -2.5, 0.49999999999999994, -2.6, 4503599627370497].map(Math.round)
        let cases: [(Double, Double)] = [
            (44.5, 45), (359.5, 360), (-0.5, 0), (-1.5, -1), (2.5, 3), (-2.5, -2), (0.49999999999999994, 0), (-2.6, -3),
            (4_503_599_627_370_497, 4_503_599_627_370_497),
        ]
        for (value, rounded) in cases { #expect(Js.round(value) == rounded, "Math.round(\(value))") }
        #expect(Js.round(.nan).isNaN)
        #expect(Js.round(.infinity) == .infinity)
        #expect(Js.round(-0.4).sign == .minus)  // Math.round(-0.4) is -0
        #expect(Js.text(.number(Js.round(-0.4))) == "0")
    }

    @Test func aValueIsWrittenAsATemplateLiteralWritesIt() {
        #expect(Js.text(.number(-0.0)) == "0")
        #expect(Js.text(json("1e21")) == "1e+21")
        #expect(Js.text(json("90.0")) == "90")
        #expect(Js.text(json("0.1")) == "0.1")
        #expect(Js.text(json(#""270°""#)) == "270°")
        #expect(Js.text(json("true")) == "true")
        #expect(Js.text(json("null")) == "null")
        #expect(Js.text(nil) == "undefined")
        #expect(Js.text(json(#"[1, null, "a", [2, 3]]"#)) == "1,,a,2,3")
        #expect(Js.text(json(#"{"a": 1}"#)) == "[object Object]")
        #expect(Js.text(json("[]")) == "")
    }

    @Test func textIsTrimmedAsJavaScriptTrimsIt() {
        #expect(Js.trim("\u{A0}\u{FEFF}LZ CROW\u{3000}\u{2028}") == "LZ CROW")
        #expect(Js.trim("  lz crow\t\n") == "lz crow")
        #expect(Js.trim("\u{200B}LZ ") == "\u{200B}LZ")  // a zero-width space is not a space
        #expect(Js.trim("\u{1C}") == "\u{1C}")
        #expect(Js.trim("\u{A0}\u{FEFF}\u{A0}") == "")
        #expect(Js.trim("") == "")
        #expect(Js.trim("\u{1680}\u{2000}\u{200A}\u{202F}\u{205F}LZ\u{2029}\u{0B}\u{0C}") == "LZ")
        for kept in ["\u{1FFF}", "\u{200B}", "\u{1C}", "\u{1F}", "\u{85}"] { #expect(Js.trim(kept) == kept) }
        // A combining mark after a trailing space is not a space, and the space before it stays (Swift counts the two as one character).
        #expect(Js.trim("A \u{301}") == "A \u{301}")
    }

    @Test func aCutNeverSplitsACharacter() {
        let heli = "🚁"
        #expect(Js.cut("abcdef", 3) == "abc")
        #expect(Js.cut("abcdef", 6) == "abcdef")
        #expect(Js.cut("ab" + heli, 3) == "ab")  // the web's slice leaves "ab\uD83D"
        #expect(Js.cut("ab" + heli + "c", 4) == "ab" + heli)
        #expect(Js.cut(heli, 1) == "")
        #expect(Js.cut("abc", 0) == "")
        // UTF-16 units, as the web counts: a flag is four, where Swift counts one character.
        #expect(Js.length("🇺🇸") == 4)
        #expect(Js.cut("🇺🇸X", 2) == "🇺")
    }

    @Test func aFieldIsReadAsJavaScriptReadsOneOwnFieldsOnly() {
        let object = json(#"{"a": 1, "n": null}"#)
        #expect(Js.prop(object, "a") == .number(1))
        #expect(Js.prop(object, "n") == .null)
        #expect(Js.prop(object, "missing") == nil)
        #expect(Js.prop(object, "constructor") == nil)
        let list = json(#"["x", "y"]"#)
        #expect(Js.prop(list, "1") == .string("y"))
        #expect(Js.prop(list, "length") == .number(2))
        #expect(Js.prop(list, "2") == nil)
        #expect(Js.prop(list, "01") == nil)
        #expect(Js.prop(.string("ab"), "1") == .string("b"))
        #expect(Js.prop(.string("ab"), "length") == .number(2))
        #expect(Js.prop(.number(5), "0") == nil)
        #expect(Js.prop(.null, "a") == nil)
        #expect(Js.prop(nil, "a") == nil)
    }

    @Test func keysAndValuesAreJavaScripts() {
        let object = json(#"{"b": 1, "2": 2, "a": 3}"#)
        #expect(Js.keys(object) == ["2", "b", "a"])
        #expect(Js.values(object) == [.number(2), .number(1), .number(3)])
        #expect(Js.keys(json("[1, 2]")) == ["0", "1"])
        #expect(Js.keys(.string("AB")) == ["0", "1"])
        #expect(Js.keys(.number(5)) == [])
    }
}
