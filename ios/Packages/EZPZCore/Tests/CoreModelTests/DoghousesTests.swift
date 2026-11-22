import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// Held to contracts/fixtures/workspace/doghouses.json: what a doghouse shows, how a typed value is written into it, and how the
/// standard doghouses give the flight data its headings, as the web does them. Android's DoghousesFixtureTest.
struct DoghousesFixtureTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("workspace/doghouses.json") }

    /// A number as the fixture writes it: a number, or one of the words for what JSON cannot hold.
    func number(_ element: JSONTree) -> Double {
        switch element {
        case .number(let n): return n.value
        case .string("NaN"): return .nan
        case .string("Infinity"): return .infinity
        case .string("-Infinity"): return -.infinity
        default: fatalError("unexpected number \(element)")
        }
    }

    func expectSameNumber(_ expected: Double, _ actual: Double, _ label: String, sourceLocation: SourceLocation = #_sourceLocation) {
        if expected.isNaN {
            #expect(actual.isNaN, "\(label): expected NaN but was \(actual)", sourceLocation: sourceLocation)
        } else {
            // -0 and 0 are equal here, as in the JSON.
            #expect(expected == actual, "\(label)", sourceLocation: sourceLocation)
        }
    }

    @Test func parseIntAndParseFloatReadWhatJavaScriptReads() {
        let all = cases(fixture, "jsParse")
        #expect(all.count > 40)
        for c in all {
            let input = c["input"]!.stringValue!
            expectSameNumber(number(c["parseInt"]!), JsNumber.parseInt(input), "parseInt of \(JSONTree.string(input).serialized)")
            expectSameNumber(number(c["parseFloat"]!), JsNumber.parseFloat(input), "parseFloat of \(JSONTree.string(input).serialized)")
        }
    }

    @Test func theDefaultDoghousesAreTheOnesTheWebMakes() {
        for c in cases(fixture, "defaults") {
            let made = DiagramOps.defaultDoghouses(c["target"], c["namespace"])
            check(name(c), c["expected"]!, .array(made))
        }
    }

    @Test func aStoredHeadingTurnsTheBoxAsTheWebTurnsIt() {
        for c in cases(fixture, "rotation") {
            let doghouse = JSONObject([("heading", c["heading"]!)])
            expectSameNumber(number(c["expected"]!), Doghouses.rotation(doghouse), "heading \(c["heading"]!.serialized)")
        }
    }

    @Test func theLabelAndFourRowsShowWhatTheWebShows() {
        for c in cases(fixture, "display") {
            let dh = c["dh"]!.objectValue!
            let rotation = number(c["rotation"]!)
            let label = name(c)
            #expect(Doghouses.rotation(dh) == rotation, "\(label)")
            let shown = Doghouses.display(dh, rotation)
            let expected = c["expected"]!.objectValue!
            #expect(shown.id == content(expected["id"]), "\(label): id")
            #expect(shown.heading == expected["heading"]!.stringValue!, "\(label): heading")
            #expect(shown.minutes == expected["minutes"]!.stringValue!, "\(label): minutes")
            #expect(shown.seconds == expected["seconds"]!.stringValue!, "\(label): seconds")
            expectSameNumber(number(expected["distance"]!), shown.distance, "\(label): distance")
            expectSameNumber(number(expected["airspeed"]!), shown.airspeed, "\(label): airspeed")
        }
    }

    @Test func theHeadingABoxIsTurnedToIsAsManyDegreesAsTheWebRoundsTo() {
        for c in cases(fixture, "displayRotations") {
            let shown = Doghouses.display(JSONObject([("id_val", .string("[X]"))]), number(c["rotation"]!))
            #expect(shown.heading == c["expected"]!.stringValue!, "rotation \(c["rotation"]!.serialized)")
        }
    }

    @Test func aTypedHeadingIsWrappedOnceAndStoredWithThreeDigitsAndADegreeSign() {
        let all = cases(fixture, "headings")
        #expect(all.count > 15)
        for c in all {
            let input = c["input"]!.stringValue!
            let degrees = Doghouses.headingDegrees(input)
            expectSameNumber(number(c["degrees"]!), degrees, "degrees of \(input)")
            #expect(Doghouses.headingText(degrees) == c["stored"]!.stringValue!, "stored for \(input)")
        }
    }

    @Test func degreesAreWrittenWithThreeDigits() {
        for c in cases(fixture, "headingText") {
            #expect(Doghouses.headingText(number(c["degrees"]!)) == c["expected"]!.stringValue!, "degrees \(c["degrees"]!.serialized)")
        }
    }

    @Test func aTypedValueIsWrittenIntoTheFieldAsTheWebWritesIt() {
        for c in cases(fixture, "fields") {
            let time = c["time"]?.objectValue
            let written = Doghouses.fieldUpdates(
                c["type"]!.stringValue!, c["value"]!.stringValue!,
                minutes: content(time?["minutes"]) ?? "", seconds: content(time?["seconds"]) ?? ""
            )
            check("\(c["type"]!.serialized) \(c["value"]!.serialized)", c["expected"]!, .object(written))
        }
    }

    @Test func theFlightDataTakesItsHeadingsFromTheDoghousesAsTheWebsEffectGivesThem() {
        for c in cases(fixture, "flightHeadings") {
            // What is not a list changes nothing.
            let list = c["doghouses"]?.arrayValue?.elements ?? []
            let result = Doghouses.flightHeadings(list, c["flightData"]!.objectValue!)
            check(name(c), c["expected"]!, .object(result))
        }
    }

    @Test func settlingADiagramBringsItsFlightDataInLineOnlyWhenItsDoghousesChanged() {
        let withDoghouses = DiagramOps.defaultDoghouses(34.5, -84.1, "d")
        let base = Diagram(
            id: "d", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z", status: .analyzed,
            target: DiagramTarget(lat: 34.5, lon: -84.1, mgrs: "16S")
        )
        var after = base
        after.graphics.doghouses = withDoghouses

        let settled = Doghouses.settle(base, after)
        #expect(settled.flightData["landing_hdg"]?.stringValue == "000°")
        #expect(settled.flightData["takeoff_hdg"]?.stringValue == "000°")

        // Doghouses that did not change leave a flight data the person set by hand alone.
        var edited = settled
        edited.flightData = settled.flightData.setting("landing_hdg", .string("123°"))
        #expect(Doghouses.settle(settled, edited) == edited)
        #expect(Doghouses.settle(base, base).flightData["landing_hdg"] == nil)
    }
}

/// What a person may type into a doghouse's fields, and what is stored: the web's formats (`03+20`, `3.5km`, `55 kts`), from input
/// the web never checked. Android's DoghouseInputTest.
struct DoghouseInputTests {
    func stored(_ field: String, _ value: String) -> JSONTree? { .object(JSONObject([(field, .string(value))])) }

    func tree(_ patch: JSONObject?) -> JSONTree? { patch.map(JSONTree.object) }

    @Test func aLabelIsKeptAsTypedTrimmed() {
        #expect(tree(Doghouses.labelPatch("  [SP2] ")) == stored("id_val", "[SP2]"))
        #expect(tree(Doghouses.labelPatch("IP 2")) == stored("id_val", "IP 2"))
        #expect(tree(Doghouses.labelPatch("[1234567890]")) == stored("id_val", "[1234567890]"))  // exactly as many as fit
    }

    @Test func aLabelThatIsBlankOrTooLongForTheTriangleIsRefused() {
        #expect(Doghouses.labelPatch("") == nil)
        #expect(Doghouses.labelPatch("   ") == nil)
        #expect(Doghouses.labelPatch("[12345678901]") == nil)
    }

    @Test func aTimeIsMinutesAndSecondsStoredWithTwoDigitsEach() {
        #expect(tree(Doghouses.timePatch("03+20")) == stored("time", "03+20"))
        #expect(tree(Doghouses.timePatch("3+20")) == stored("time", "03+20"))
        #expect(tree(Doghouses.timePatch("3:5")) == stored("time", "03+05"))
        #expect(tree(Doghouses.timePatch(" 3 + 05 ")) == stored("time", "03+05"))
        #expect(tree(Doghouses.timePatch("123+59")) == stored("time", "123+59"))
        #expect(tree(Doghouses.timePatch("0+0")) == stored("time", "00+00"))
    }

    @Test(arguments: ["", "320", "3+", "+20", "3+60", "3+100", "a+b", "3.5+20", "-3+20", "1234+20", "3+20+10", "٣+٢٠"])
    func aTimeThatIsNotMinutesAndSecondsIsRefused(_ typed: String) {
        #expect(Doghouses.timePatch(typed) == nil, "time \"\(typed)\"")
    }

    @Test func aDistanceIsKilometresStoredAsTheWebStoresIt() {
        #expect(tree(Doghouses.distancePatch("3.13")) == stored("dist", "3.13km"))
        #expect(tree(Doghouses.distancePatch(" 12 ")) == stored("dist", "12km"))
        #expect(tree(Doghouses.distancePatch("0")) == stored("dist", "0km"))
        #expect(tree(Doghouses.distancePatch("12.50")) == stored("dist", "12.50km"))
    }

    @Test(arguments: ["", "far", "3.", ".5", "-3", "3,5", "1e3", "3.1234", "12345", "3 km", "1.2.3"])
    func whatIsNotADistanceIsRefused(_ typed: String) {
        #expect(Doghouses.distancePatch(typed) == nil, "distance \"\(typed)\"")
    }

    @Test func anAirspeedIsKnotsStoredAsTheWebStoresIt() {
        #expect(tree(Doghouses.airspeedPatch("55")) == stored("airspeed", "55 kts"))
        #expect(tree(Doghouses.airspeedPatch(" 120")) == stored("airspeed", "120 kts"))
    }

    @Test(arguments: ["", "fast", "5.5", "-5", "1000", "55 kts"])
    func whatIsNotAnAirspeedIsRefused(_ typed: String) {
        #expect(Doghouses.airspeedPatch(typed) == nil, "airspeed \"\(typed)\"")
    }

    @Test func whatIsStoredReadsBackAsWhatWasTyped() {
        var entries: [(String, JSONTree)] = [("id_val", .string("[SP1]")), ("heading", .string("090°"))]
        for patch in [Doghouses.timePatch("3:20")!, Doghouses.distancePatch("3.5")!, Doghouses.airspeedPatch("55")!] {
            entries += patch.entries.map { ($0.key, $0.value) }
        }
        let doghouse = JSONObject(entries)
        let shown = Doghouses.display(doghouse, Doghouses.rotation(doghouse))
        #expect(shown.minutes == "03")
        #expect(shown.seconds == "20")
        #expect(shown.distance == 3.5)
        #expect(shown.airspeed == 55)
        #expect(shown.heading == "090")
    }

    @Test func aHeadingKeptInAListIsReadByItsTextAsJavaScriptReadsIt() {
        // parseInt(String([306])) is parseInt("306"): the web reads 306°, and an object has no heading.
        #expect(Doghouses.landingHeadingOf(json("[306]")) == 306)
        #expect(Doghouses.landingHeadingOf(json(#"{"hdg": 306}"#)) == nil)
        #expect(Doghouses.landingHeadingOf(json("[]")) == nil)
    }

    /// Not like Kotlin's, which printed a number as Java does (`1.0E-4`, `1.23456785E7`): the box's numbers are JavaScript's.
    @Test func theBoxsNumbersAreWrittenAsJavaScriptWritesThem() {
        let shown = Doghouses.Display(id: nil, heading: "000", minutes: "00", seconds: "00", distance: 0.0001, airspeed: 12_345_678.5)
        #expect(shown.distanceText == "0.0001")
        #expect(shown.airspeedText == "12345678.5")
        #expect(Doghouses.Display(id: nil, heading: "", minutes: "", seconds: "", distance: 5, airspeed: 90).distanceText == "5")
        // And a heading past 2^53 has JavaScript's digits, not the double's exact ones.
        #expect(Doghouses.headingText(36_893_488_147_419_103_232) == "36893488147419103000°")
    }
}
