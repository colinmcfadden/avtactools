import CoreTesting
import Foundation
import Testing

@testable import CoreJS

/// JSON as the web keeps it: JSON.parse's grammar, JavaScript's key order, a number's literal text, null apart from absent, an
/// untouched value keeping its instance, and a bound on nesting.
struct JSONTreeTests {
    @Test func everyFixtureReadsAndWritesBackAsTheSameDocument() throws {
        let names = Fixtures.allJSON()
        #expect(names.count > 50)
        for name in names {
            let tree = try JSONTree.parse(utf8: try Fixtures.bytes(name), maxDepth: 1_000)
            let again = try JSONTree.parse(tree.serialized, maxDepth: 1_000)
            #expect(again == tree, "\(name)")
            #expect(again.serialized == tree.serialized, "\(name)")
        }
    }

    @Test func aDocumentIsWrittenAsJSONStringifyWritesIt() throws {
        // JSON.stringify(JSON.parse(text)) for each, from Node; a number keeps its literal text, as kotlinx's JsonElement does.
        let cases: [(String, String)] = [
            (#" { "b" : 1 , "2" : [ true , false , null ] , "a" : "x" } "#, #"{"2":[true,false,null],"b":1,"a":"x"}"#),
            (#"{"a":1,"a":2,"b":3}"#, #"{"a":2,"b":3}"#),  // a key given twice: its first place, its last value
            (#"["\u0000\u001f\b\f\n\r\t\"\\\/"]"#, #"["\u0000\u001f\b\f\n\r\t\"\\/"]"#),
            (#""  \u007f""#, "\"\u{2028}\u{2029}\u{7F}\""),
            (#""🚁 é é""#, "\"🚁 é e\u{301}\""),
            ("[]", "[]"),
            ("{}", "{}"),
            ("-0", "-0"),
        ]
        for (text, written) in cases { #expect(try JSONTree.parse(text).serialized == written, "\(text)") }
    }

    @Test func aNumberKeepsTheTextItWasWrittenWith() throws {
        let tree = try JSONTree.parse(#"[2.50, 1E3, 12345678901234567890123, -0.0, 1e400, 0.1]"#)
        #expect(tree.serialized == "[2.50,1E3,12345678901234567890123,-0.0,1e400,0.1]")
        #expect(tree[0]!.numberValue!.value == 2.5)
        #expect(tree[2]!.numberValue!.value == 1.2345678901234568e22)
        #expect(tree[4]!.numberValue!.value == .infinity)
        // A number made here is written as JavaScript writes it, and the ones JSON has no text for as null.
        let made = JSONTree.array([.number(1), .number(0.1 + 0.2), .number(1e21), .number(.nan), .number(-.infinity)])
        #expect(made.serialized == "[1,0.30000000000000004,1e+21,null,null]")
    }

    @Test func nullIsNotAbsent() throws {
        let tree = try JSONTree.parse(#"{"after": null}"#)
        #expect(tree["after"] == .null)
        #expect(tree["before"] == nil)
        #expect(tree.objectValue!.has("after"))
        #expect(!tree.objectValue!.has("before"))
        #expect(tree.serialized == #"{"after":null}"#)
    }

    @Test func keysAreTheirUTF16UnitsNotCanonicallyEquivalentText() throws {
        // Two keys JavaScript keeps apart, which Swift's String == would take for one.
        let tree = try JSONTree.parse(#"{"é": 1, "é": 2}"#)
        #expect(tree.objectValue!.count == 2)
        #expect(tree["\u{E9}"] == .number(1))
        #expect(tree["e\u{301}"] == .number(2))
    }

    @Test func anObjectKeepsJavaScriptsOrderAsItChanges() {
        let object = JSONObject([("b", .number(1)), ("a", .number(2))])
        #expect(object.setting("1", .null).keys == ["1", "b", "a"])  // an index goes first
        #expect(object.setting("b", .number(9)).keys == ["b", "a"])  // a key that is there keeps its place
        #expect(object.setting("b", .number(9))["b"] == .number(9))
        #expect(object.setting("c", .null).keys == ["b", "a", "c"])
        #expect(object.removing("b").keys == ["a"])
        #expect(object.removing("zzz") === object)
    }

    @Test func anUntouchedValueKeepsItsInstance() throws {
        let tree = try JSONTree.parse(#"{"items": {"a": {"x": 1}}, "list": [1]}"#)
        let items = tree["items"]!.objectValue!
        let changed = tree.objectValue!.setting("list", .array([.number(2)]))
        #expect(changed["items"]!.objectValue! === items)  // what the change did not touch is the same object
        #expect(Js.strictEquals(changed["items"], tree["items"]))
    }

    @Test func structuralEqualityIgnoresKeyOrderButNotANumbersText() throws {
        #expect(try JSONTree.parse(#"{"a":1,"b":2}"#) == JSONTree.parse(#"{"b":2,"a":1}"#))
        #expect(try JSONTree.parse("[1]") != JSONTree.parse("[1.0]"))
        #expect(try JSONTree.parse(#"{"a":null}"#) != JSONTree.parse("{}"))
    }

    @Test func whatJSONParseRefusesIsRefused() {
        let refused: [String] = [
            "", " ", "[1,]", #"{"a":1,}"#, "{a:1}", "'a'", "01", "1.", ".5", "+1", "1e", "-", "NaN", "Infinity", "[1 2]", "tru", "nul",
            "\u{FEFF}1", "// c\n1", "[1]x", #""\x""#, #""\u12""#, "\"a\nb\"", "\"a\tb\"", #"{"a" 1}"#, "[", "{", "\"abc",
        ]
        for text in refused {
            #expect(throws: JSONError.self, "\(text.debugDescription)") { try JSONTree.parse(text) }
        }
        #expect(throws: JSONError.self) { try JSONTree.parse(utf8: [0x22, 0xFF, 0x22]) }  // not UTF-8
        #expect(throws: JSONError.self) { try JSONTree.parse(utf8: [0x22, 0xC3, 0x22]) }  // a sequence cut short
    }

    @Test func whiteSpaceIsOnlyJSONsFour() throws {
        #expect(try JSONTree.parse(" \t\r\n1 \t\r\n") == .number(JSONNumber(1)))
        #expect(throws: JSONError.self) { try JSONTree.parse("\u{A0}1") }
        #expect(throws: JSONError.self) { try JSONTree.parse("\u{0B}1") }
    }

    @Test func aLoneSurrogateIsReadAsTheReplacementCharacter() throws {
        // JavaScript keeps it; a Swift String cannot hold one (docs/IOS_PLAN.md §6).
        #expect(try JSONTree.parse(#""ab\ud83d""#) == .string("ab\u{FFFD}"))
    }

    @Test func nestingIsBoundedAndNeverEndsTheApp() throws {
        // Five thousand brackets: ten kilobytes that ended Kotlin's recursive parser with a StackOverflowError.
        let deep = String(repeating: "[", count: 5_000) + String(repeating: "]", count: 5_000)
        #expect(throws: JSONError(reason: .tooDeep(JSONTree.defaultMaxDepth), offset: JSONTree.defaultMaxDepth)) {
            try JSONTree.parse(deep)
        }
        let objects = String(repeating: #"{"a":"#, count: 300) + "1" + String(repeating: "}", count: 300)
        #expect(throws: JSONError.self) { try JSONTree.parse(objects) }
        // Up to the bound is fine.
        let atLimit = String(repeating: "[", count: 64) + String(repeating: "]", count: 64)
        #expect(try JSONTree.parse(atLimit).arrayValue != nil)
        #expect(throws: JSONError.self) { try JSONTree.parse("[[1]]", maxDepth: 1) }
        // Refusing a deep text costs no stack: the parser keeps its own.
        let veryDeep = String(repeating: "[", count: 200_000)
        #expect(throws: JSONError.self) { try JSONTree.parse(veryDeep) }
    }

    @Test func aDocumentAtTheBoundIsWrittenAndComparedOnASecondaryThreadsStack() async {
        // What recurses (the writer, ==, JSONCompare) must fit 512 KB, the stack of every thread but the main one, in a debug build.
        let text = String(repeating: #"{"a":["#, count: 32) + "1" + String(repeating: "]}", count: 32)
        let done: Bool = await withCheckedContinuation { continuation in
            let thread = Thread {
                let tree = try! JSONTree.parse(text)
                let again = try! JSONTree.parse(tree.serialized)
                continuation.resume(returning: tree == again && JSONCompare.differences(tree, again).isEmpty)
            }
            thread.stackSize = 512 * 1024
            thread.start()
        }
        #expect(done)
    }

    @Test(.timeLimit(.minutes(1)))
    func aLongListOrObjectIsReadInTimeProportionalToItsLength() throws {
        // A list's elements once copied at every append: half a million numbers took minutes.
        let count = 300_000
        let list = "[" + (0..<count).map(String.init).joined(separator: ",") + "]"
        #expect(try JSONTree.parse(list).arrayValue?.count == count)
        let object = "{" + (0..<count).map { "\"k\($0)\":[\($0)]" }.joined(separator: ",") + "}"
        let read = try JSONTree.parse(object).objectValue!
        #expect(read.count == count)
        #expect(read["k299999"] == .array([.number(JSONNumber(literal: "299999"))]))
    }

    @Test func anErrorSaysWhereAndWhat() {
        do {
            _ = try JSONTree.parse(#"{"a": tru}"#)
            Issue.record("no error")
        } catch {
            #expect(error.offset == 9)
            #expect(error.description.contains("byte 9"))
        }
    }
}

struct JSONCompareTests {
    @Test func absentAndNullAgreeAndNumbersWithinTheTolerance() throws {
        #expect(JSONCompare.differences(json(#"{"a":1,"b":null}"#), json(#"{"a":1.0000000001}"#)).isEmpty)
        #expect(JSONCompare.differences(json(#"{"a":[1,2]}"#), json(#"{"a":[1]}"#)) == ["$.a: expected 2 items, got 1"])
        #expect(JSONCompare.differences(json(#"{"a":"x"}"#), json(#"{"a":1}"#)) == [#"$.a: expected "x", got 1"#])
        #expect(JSONCompare.differences(json("[1]"), json("[1.1]"), tolerance: 0.2).isEmpty)
    }
}
