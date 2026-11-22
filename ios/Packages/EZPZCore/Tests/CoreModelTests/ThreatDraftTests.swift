import CoreJS
import Testing

@testable import CoreModel

/// The threat form: what a person may type, what each refusal says, and that a threat opened and applied comes back as it was.
/// Android's ThreatDraftTest.
struct ThreatDraftTests {
    let here = LatLon(lat: 34.783817, lon: -84.08219)

    func valid(_ draft: ThreatDraft, base: Threat? = nil, sourceLocation: SourceLocation = #_sourceLocation) -> Threat {
        guard case .valid(let threat) = draft.check(at: here, base: base) else {
            Issue.record("refused: \(draft.check(at: here, base: base))", sourceLocation: sourceLocation)
            return Threat(name: "", milstdId: "", lat: 0, lon: 0, information: "", source: "", radars: [])
        }
        return threat
    }

    func refused(_ draft: ThreatDraft) -> String? {
        if case .refused(let message) = draft.check(at: here) { message } else { nil }
    }

    func withRadar(_ index: Int, _ change: (inout RadarDraft) -> Void) -> ThreatDraft {
        var draft = ThreatDraft()
        change(&draft.radars[index])
        return draft
    }

    func draft(_ change: (inout ThreatDraft) -> Void) -> ThreatDraft {
        var draft = ThreatDraft()
        change(&draft)
        return draft
    }

    // MARK: A threat with the web's defaults

    @Test func theDefaultFormMakesTheThreatTheWebMakesADetectionAndAnEngagementRadarAtThePlaceGiven() {
        let t = valid(ThreatDraft())
        #expect(t.name == "Threat")
        #expect(t.milstdId == Radars.defaultMilstdId)
        #expect(t.lat == here.lat)
        #expect(t.lon == here.lon)
        #expect(t.source == "SOF")
        #expect(t.information == "")
        #expect(t.showThreat)
        #expect(t.radars == Radars.defaultPair())
    }

    @Test func aNewThreatIsNamedForHowManyThereAre() {
        #expect(ThreatDraft.forNew(0).name == "Threat 1")
        #expect(ThreatDraft.forNew(3).name == "Threat 4")
    }

    @Test func aThreatOpenedToBeChangedAndAppliedUnchangedComesBackAsItWasColoursAndAll() {
        var radar = Radars.default(Radars.engagement)
        radar.rangeNmi = 13.5
        radar.antennaHeightFt = 40
        radar.aglNotMsl = false
        radar.showMask = false
        radar.showRangeRings = false
        let odd = Threat(
            name: "SA-6", milstdId: "SHGPEWRR------", lat: 34.5, lon: -84.5, information: "Moved at night", source: "HUMINT",
            showThreat: false, radars: [radar]
        )
        var back = valid(ThreatDraft.of(odd), base: odd)
        back.lat = 34.5
        back.lon = -84.5
        #expect(back == odd)
        // Visibility on the map is the picture's; showThreat is the file's, and stays.
        #expect(!valid(ThreatDraft.of(odd), base: odd).showThreat)
    }

    // MARK: Identity

    @Test func aThreatNeedsAName() {
        #expect(refused(draft { $0.name = "   " }) == "A threat needs a name.")
        #expect(refused(draft { $0.name = "" }) == "A threat needs a name.")
    }

    @Test func aNameIsTrimmedAndCutToWhatAMPSHoldsByCharacters() {
        #expect(valid(draft { $0.name = "  SA-6  " }).name == "SA-6")
        // 49 letters and two emoji: 51 characters, 53 UTF-16 units. The emoji at the end is kept whole, the next is dropped.
        let long = String(repeating: "A", count: 49) + "🚀🚀"
        #expect(valid(draft { $0.name = long }).name == String(repeating: "A", count: 49) + "🚀")
    }

    @Test func informationAndSourceAreCutAndABlankSourceIsTheWebsDefault() {
        #expect(valid(draft { $0.information = String(repeating: "x", count: 300) }).information.utf16.count == 255)
        #expect(valid(draft { $0.source = "  " }).source == "SOF")
        #expect(valid(draft { $0.source = String(repeating: "S", count: 40) }).source == String(repeating: "S", count: 32))
        #expect(valid(draft { $0.information = "  note " }).information == "note")
    }

    @Test func theSymbolCodeIsUpperCase1To15OfLettersDigitsHyphensAndAsterisks() {
        #expect(valid(draft { $0.milstdId = " shgpewrr------ " }).milstdId == "SHGPEWRR------")
        #expect(valid(draft { $0.milstdId = "S*G*-" }).milstdId == "S*G*-")
        let message = "The symbol code must be 1 to 15 letters, digits, hyphens or asterisks."
        #expect(refused(draft { $0.milstdId = "" }) == message)
        #expect(refused(draft { $0.milstdId = String(repeating: "S", count: 16) }) == message)
        #expect(refused(draft { $0.milstdId = "SHG PEW" }) == message)
        #expect(refused(draft { $0.milstdId = "SHG;DROP" }) == message)
        #expect(valid(draft { $0.milstdId = String(repeating: "S", count: 15) }).milstdId.utf16.count == 15)
    }

    // MARK: A radar's numbers

    @Test func aRangeIsBetweenATenthOfAMileAndFiveHundredAndTheWordsNameTheRadar() {
        #expect(valid(withRadar(0) { $0.rangeNmi = "0.1" }).radars[0].rangeNmi == 0.1)
        #expect(valid(withRadar(1) { $0.rangeNmi = "500" }).radars[1].rangeNmi == 500)
        #expect(refused(withRadar(0) { $0.rangeNmi = "0.09" }) == "Detection range must be between 0.1 and 500.")
        #expect(refused(withRadar(1) { $0.rangeNmi = "500.1" }) == "Engagement range must be between 0.1 and 500.")
        #expect(refused(withRadar(0) { $0.rangeNmi = "0" }) == "Detection range must be between 0.1 and 500.")
        #expect(refused(withRadar(0) { $0.rangeNmi = "-5" }) == "Detection range must be between 0.1 and 500.")
    }

    @Test func aBlankOrUnreadableNumberIsRefusedWithTheFieldNamedNeverTurnedIntoZero() {
        #expect(refused(withRadar(0) { $0.rangeNmi = "" }) == "Detection range needs a number.")
        #expect(refused(withRadar(0) { $0.rangeNmi = "12 nmi" }) == "Detection range is not a number.")
        #expect(refused(withRadar(1) { $0.antennaHeightFt = "1,5" }) == "Engagement antenna height is not a number.")
        #expect(refused(withRadar(1) { $0.antennaHeightFt = "  " }) == "Engagement antenna height needs a number.")
        #expect(refused(withRadar(0) { $0.rangeNmi = "1e2" }) == "Detection range is not a number.")
        #expect(refused(withRadar(0) { $0.rangeNmi = "NaN" }) == "Detection range is not a number.")
    }

    @Test func anAntennaIsBetweenNothingAndTenThousandFeet() {
        #expect(valid(withRadar(0) { $0.antennaHeightFt = "0" }).radars[0].antennaHeightFt == 0)
        #expect(valid(withRadar(0) { $0.antennaHeightFt = "10000" }).radars[0].antennaHeightFt == 10_000)
        #expect(refused(withRadar(0) { $0.antennaHeightFt = "10000.1" }) == "Detection antenna height must be between 0 and 10000.")
        #expect(refused(withRadar(0) { $0.antennaHeightFt = "-1" }) == "Detection antenna height must be between 0 and 10000.")
    }

    @Test func bandAltitudesAreWholeFeetFromTheGroundToFiftyThousand() {
        func set(_ text: String) -> ThreatDraft { withRadar(1) { $0.bandAltitudesFt = ["50", text, "500"] } }
        #expect(valid(set("0")).radars[1].bands[1].altFt == 0)
        #expect(valid(set("50000")).radars[1].bands[1].altFt == 50_000)
        #expect(refused(set("50001")) == "Engagement band 2 altitude must be between 0 and 50000.")
        #expect(refused(set("-1")) == "Engagement band 2 altitude must be between 0 and 50000.")
        // A .ths holds integers: it would be cut without a word.
        #expect(refused(set("250.5")) == "Engagement band 2 altitude must be a whole number of feet.")
        #expect(refused(set("")) == "Engagement band 2 altitude needs a number.")
        // A whole number written with a point is whole.
        #expect(valid(set("250.0")).radars[1].bands[1].altFt == 250)
    }

    @Test func theFirstThingWrongIsWhatIsSaidDetectionBeforeEngagementAndTheRangeBeforeTheBands() {
        var d = ThreatDraft()
        d.radars[0].bandAltitudesFt = ["x", "250", "500"]
        d.radars[1].rangeNmi = "x"
        #expect(refused(d) == "Detection band 1 altitude is not a number.")
        d.radars[0].rangeNmi = ""
        #expect(refused(d) == "Detection range needs a number.")
    }

    @Test func theTogglesAndTheBandsVisibilityAreCarriedAndTheBandsKeepTheirColours() {
        let t = valid(
            withRadar(0) {
                $0.aglNotMsl = false
                $0.showMask = false
                $0.showRangeRings = false
                $0.bandsViewable = [true, false, true]
            })
        let r = t.radars[0]
        #expect(!r.aglNotMsl)
        #expect(!r.showMask)
        #expect(!r.showRangeRings)
        #expect(r.bands.map(\.viewable) == [true, false, true])
        #expect(r.bands.map(\.color) == Radars.default(Radars.detection).bands.map(\.color))
        #expect(r.bands.map(\.colorIndex) == Radars.default(Radars.detection).bands.map(\.colorIndex))
    }

    @Test func aRadarWithFewerThanThreeBandsIsKeptAsItIsNotPadded() {
        var one = Radars.default(Radars.detection)
        one.bands = Array(one.bands.prefix(1))
        let t = Threat(name: "X", milstdId: "SHGPEWRR------", lat: 1, lon: 2, information: "", source: "SOF", radars: [one])
        let back = valid(ThreatDraft.of(t)).radars
        #expect(back.count == 1)
        #expect(back.first?.bands.count == 1)
    }

    @Test func numbersArePutBackInAFieldAsAPersonWouldTypeThem() {
        var radar = Radars.default(Radars.detection)
        radar.rangeNmi = 12.5
        radar.antennaHeightFt = 20
        let d = RadarDraft.of(radar)
        #expect(d.rangeNmi == "12.5")
        #expect(d.antennaHeightFt == "20")
        #expect(d.bandAltitudesFt == ["50", "250", "500"])
    }

    // MARK: Telling threats apart

    @Test func anIdIsNudgedPastAnyTakenSoTwoThreatsMadeInOneMillisecondAreTwo() {
        #expect(ThreatIds.next([String](), 100) == "threat-100-0")
        #expect(ThreatIds.next(["threat-100-0"], 100) == "threat-100-1")
        #expect(ThreatIds.next(["threat-100-0", "threat-100-1"], 100) == "threat-100-2")
        #expect(ThreatIds.next(["threat-100-0"], 200) == "threat-200-0")
    }
}
