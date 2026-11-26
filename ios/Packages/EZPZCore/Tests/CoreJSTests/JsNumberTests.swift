import CoreTesting
import Foundation
import Testing

@testable import CoreJS

/// Held to contracts/fixtures/formats/number_text.json: what JavaScript's `String(n)` writes for each number.
struct JsNumberTextTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("formats/number_text.json") }

    @Test func everyNumberIsWrittenAsJavaScriptWritesIt() {
        let cases = fixture["numbers"]!.arrayValue!
        #expect(cases.count >= 60)
        for c in cases {
            let value = c["value"]!.numberValue!.value
            #expect(JsNumber.toText(value) == c["text"]!.stringValue!, "the number \(c["value"]!.serialized)")
        }
    }

    @Test func theWords() {
        for c in fixture["words"]!.arrayValue! {
            let value: Double =
                switch c["value"]!.stringValue! {
                case "NaN": .nan
                case "Infinity": .infinity
                default: -.infinity
                }
            #expect(JsNumber.toText(value) == c["text"]!.stringValue!)
        }
    }

    @Test func negativeZeroIsWrittenAsZero() {
        #expect(JsNumber.toText(-0.0) == fixture["negativeZero"]!.stringValue!)
        #expect(JsNumber.toText(0) == "0")
    }

    @Test func aWholeNumberHasNoPointAndSwiftsOwnTextIsNotUsed() {
        #expect(JsNumber.toText(100) == "100")  // Swift: 100.0
        #expect(JsNumber.toText(1e20) == "100000000000000000000")  // Swift: 1e+20
        #expect(JsNumber.toText(1e21) == "1e+21")
        #expect(JsNumber.toText(1e-7) == "1e-7")  // Swift: 1e-07
        #expect(JsNumber.toText(1e-6) == "0.000001")
        #expect(JsNumber.toText(-1.5e-7) == "-1.5e-7")
    }

    @Test func randomDoublesReadBackAsThemselves() {
        // What JavaScript writes is the shortest text that reads back: whatever the digits, they must read back.
        var generator = SystemRandomNumberGenerator()
        for _ in 0..<20_000 {
            let value = Double(bitPattern: generator.next())
            guard value.isFinite else { continue }
            let text = JsNumber.toText(value)
            #expect(Double(text) == (value == 0 ? 0 : value), "\(value) wrote \(text)")
        }
    }

    /// Against Node itself, on the owner's machine: `EZPZ_NUMBER_TEXT_SAMPLE` names a JSON list of `[bits, text]` pairs that Node
    /// wrote (`String(n)` for random doubles; the script is in ios/README.md). Skipped when it is not set.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["EZPZ_NUMBER_TEXT_SAMPLE"] != nil))
    func everyNumberNodeWroteIsWrittenTheSame() throws {
        let path = ProcessInfo.processInfo.environment["EZPZ_NUMBER_TEXT_SAMPLE"]!
        let sample = try JSONTree.parse(utf8: try Data(contentsOf: URL(fileURLWithPath: path))).arrayValue!
        var mismatches: [String] = []
        for pair in sample {
            let bits = UInt64(pair[0]!.stringValue!, radix: 16)!
            let expected = pair[1]!.stringValue!
            let actual = JsNumber.toText(Double(bitPattern: bits))
            if actual != expected { mismatches.append("\(pair[0]!.stringValue!): Node \(expected), here \(actual)") }
        }
        #expect(sample.count > 0)
        #expect(mismatches.isEmpty, "\(mismatches.prefix(20))")
    }
}

/// `toFixed`, which the hand-off files write every coordinate with. The values are what JavaScript answers.
struct JsNumberToFixedTests {
    @Test func itRoundsTheExactBinaryValueNotTheShortestDecimal() {
        #expect(JsNumber.toFixed(1.005, 2) == "1.00")
        #expect(JsNumber.toFixed(2.675, 2) == "2.67")
        #expect(JsNumber.toFixed(34.1234565, 6) == "34.123457")
        #expect(JsNumber.toFixed(1.0000005, 6) == "1.000001")
        #expect(JsNumber.toFixed(0.5, 0) == "1")  // exactly a half: up
        #expect(JsNumber.toFixed(1.45, 1) == "1.4")  // 1.45 is 1.4499999999999999556 in binary
        #expect(JsNumber.toFixed(-84.08219, 6) == "-84.082190")
    }

    @Test func aNegativeThatRoundsToZeroKeepsItsSignAndNegativeZeroHasNone() {
        #expect(JsNumber.toFixed(-1e-7, 6) == "-0.000000")
        #expect(JsNumber.toFixed(-0.0, 6) == "0.000000")
        #expect(JsNumber.toFixed(0, 6) == "0.000000")
    }

    @Test func wholeNumbersArePaddedAndNoPlacesHaveNoPoint() {
        #expect(JsNumber.toFixed(34, 6) == "34.000000")
        #expect(JsNumber.toFixed(34.5, 0) == "35")
        #expect(JsNumber.toFixed(-0.5, 0) == "-1")  // a half goes away from zero on the magnitude
        #expect(JsNumber.toFixed(123_456_789_012_345_680, 2) == "123456789012345680.00")  // (123456789012345678).toFixed(2): that double
    }

    @Test func manyPlacesShowTheExactBinaryValue() {
        // (0.1).toFixed(20) and (5e-324).toFixed(100), as Node writes them.
        #expect(JsNumber.toFixed(0.1, 20) == "0.10000000000000000555")
        #expect(JsNumber.toFixed(5e-324, 100) == "0." + String(repeating: "0", count: 100))
        #expect(JsNumber.toFixed(1e20, 3) == "100000000000000000000.000")
    }

    @Test func nanTheInfinitiesAndNumbersPast1e21AreAsJavaScriptWritesThem() {
        #expect(JsNumber.toFixed(.nan, 6) == "NaN")
        #expect(JsNumber.toFixed(.infinity, 6) == "Infinity")
        #expect(JsNumber.toFixed(-.infinity, 6) == "-Infinity")
        #expect(JsNumber.toFixed(1e21, 6) == "1e+21")
        #expect(JsNumber.toFixed(-1.5e21, 2) == "-1.5e+21")
    }
}

/// `Number()`, `parseInt` and `parseFloat`.
struct JsNumberReadTests {
    @Test func textIsReadAsJavaScriptsNumberReadsIt() {
        let numbers: [(String, Double)] = [
            ("0x5A", 90), ("0o17", 15), ("0b11", 3), ("0XFF", 255), (" ", 0), ("", 0), ("\u{A0} 42 \u{FEFF}", 42), ("1e2", 100),
            ("+90", 90), (".5", 0.5), ("5.", 5), ("Infinity", .infinity), ("-Infinity", -.infinity), ("\u{2028}\t7\n", 7),
            ("0x22", 34), ("0b1011010", 90), ("0o132", 90),
        ]
        for (text, number) in numbers { #expect(JsNumber.of(.string(text)) == number, "Number(\(JSONTree.string(text).serialized))") }
        for text in [
            "-0x5A", "1_000", "270°", "\u{200B}90", "\u{1C}90", "0x", "0o19", "abc", ".", "e5", "infinity", "1e", "0x1p3", "٣٤", "３４",
        ] {
            #expect(JsNumber.of(.string(text)).isNaN, "Number(\(JSONTree.string(text).serialized)) is NaN")
        }
        #expect(JsNumber.of(json("true")) == 1)
        #expect(JsNumber.of(json("false")) == 0)
        #expect(JsNumber.of(json("null")) == 0)
        #expect(JsNumber.of(json("[]")) == 0)
        #expect(JsNumber.of(json("[7]")) == 7)
        #expect(JsNumber.of(json("[[7]]")) == 7)
        #expect(JsNumber.of(json(#"["8"]"#)) == 8)
        #expect(JsNumber.of(json("[null]")) == 0)
        #expect(JsNumber.of(json("[34.783817]")) == 34.783817)
        #expect(JsNumber.of(json("[1, 2]")).isNaN)
        #expect(JsNumber.of(json("{}")).isNaN)
        #expect(JsNumber.of(nil).isNaN)
        #expect(JsNumber.of(json("-1.25e1")) == -12.5)
        // Past 2^53 the digits are rounded to the nearest double, a half to even, as JavaScript reads them.
        #expect(JsNumber.of(.string("0x20000000000001")) == 9.007199254740992e15)
        #expect(JsNumber.of(.string("0x20000000000003")) == 9.007199254740996e15)
        #expect(JsNumber.of(.string("0x" + String(repeating: "F", count: 300))) == .infinity)
    }

    @Test func parseIntAndParseFloatAreHeldToTheirJavaScriptAnswers() throws {
        // contracts/fixtures/workspace/doghouses.json's `jsParse`, what Node answered for each.
        let cases = try Fixtures.load("workspace/doghouses.json")["jsParse"]!.arrayValue!
        #expect(cases.count > 40)
        func expected(_ value: JSONTree) -> Double {
            switch value.stringValue {
            case "NaN": .nan
            case "Infinity": .infinity
            case "-Infinity": -.infinity
            default: value.numberValue!.value
            }
        }
        func same(_ a: Double, _ b: Double) -> Bool { a.isNaN ? b.isNaN : a == b }
        for c in cases {
            let input = c["input"]!.stringValue!
            #expect(same(JsNumber.parseInt(input), expected(c["parseInt"]!)), "parseInt(\(c["input"]!.serialized))")
            #expect(same(JsNumber.parseFloat(input), expected(c["parseFloat"]!)), "parseFloat(\(c["input"]!.serialized))")
        }
    }
}

/// The arithmetic under `toFixed` and the reading of long digits.
struct BigUIntTests {
    @Test func decimalTextAndShifts() {
        var n = BigUInt(1)
        n.multiply(byPowerOfTen: 30)
        #expect(n.decimalText == "1" + String(repeating: "0", count: 30))
        #expect(BigUInt(1).shiftedLeft(by: 100).decimalText == "1267650600228229401496703205376")
        #expect(BigUInt(1).shiftedLeft(by: 100).shiftedRight(by: 99).decimalText == "2")
        #expect(BigUInt(0).decimalText == "0")
        #expect(BigUInt(UInt64.max).decimalText == "18446744073709551615")
    }

    @Test func theNearestDoubleRoundsAHalfToEven() {
        // 2^53 + 1 is a half between two doubles: to the even one, 2^53. 2^53 + 3 goes up to 2^53 + 4.
        #expect(BigUInt(digits: "9007199254740993".unicodeScalars, radix: 10)!.nearestDouble == 9_007_199_254_740_992)
        #expect(BigUInt(digits: "9007199254740995".unicodeScalars, radix: 10)!.nearestDouble == 9_007_199_254_740_996)
        // Past 64 bits: 2^70 + 1 is 2^70; 2^70 + 2^17 (exactly half of the gap) to even; one more and it goes up.
        let twoTo70 = BigUInt(1).shiftedLeft(by: 70)
        var plusOne = twoTo70
        plusOne.add(BigUInt(1))
        #expect(plusOne.nearestDouble == 0x1p70)
        var half = twoTo70
        half.add(BigUInt(1).shiftedLeft(by: 17))
        #expect(half.nearestDouble == 0x1p70)
        half.add(BigUInt(1))
        #expect(half.nearestDouble == 0x1p70 + 0x1p18)
        #expect(BigUInt(digits: "12z".unicodeScalars, radix: 10) == nil)
        #expect(BigUInt(digits: "".unicodeScalars, radix: 10) == nil)
    }
}
