import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// A set of threats as the Library keeps it: the web's document, read and written the same way, held to what the web itself does
/// with each case (`contracts/fixtures/threats/sets.json`). Android's ThreatSetsTest.
struct ThreatSetsTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("threats/sets.json") }

    func threatOf(_ element: JSONTree) -> Threat {
        ThreatSets.read(JSONObject([("threats", .array([element]))])).threats[0].entry.threat
    }

    /// The written document with each id the reader chose put back to the fixture's `<new>`.
    func markNew(_ written: JSONObject, _ chosen: Set<String>) -> JSONObject {
        guard let threats = written["threats"]?.arrayValue else { return written }
        let marked = threats.elements.map { element -> JSONTree in
            guard let threat = element.objectValue, let id = content(threat["id"]), chosen.contains(id) else { return element }
            return .object(threat.setting("id", .string("<new>")))
        }
        return written.setting("threats", .array(marked))
    }

    @Test func everyDocumentTheWebWasRunOverReadsAndWritesTheSame() {
        let documents = cases(fixture, "documents")
        #expect(documents.count >= 25)
        for c in documents {
            let label = name(c)
            let used = Set(c["usedIds"]!.arrayValue!.elements.map { content($0)! })
            let read = ThreatSets.read(c["data"]!.objectValue!, usedIds: used, freshPrefix: "fresh")
            let shown = c["shown"]!.arrayValue!.elements.map { $0.objectValue! }
            #expect(read.threats.count == shown.count, "\(label): how many are read")
            #expect(read.unreadable.count == Int(c["unreadable"]!.numberValue!.value), "\(label): how many cannot be read")
            var chosen = Set<String>()
            for (want, got) in zip(shown, read.threats) {
                let id = content(want["id"])!
                if id == "<new>" {
                    #expect(!used.contains(got.entry.id), "\(label): a new id is not one another set holds")
                    chosen.insert(got.entry.id)
                } else {
                    #expect(got.entry.id == id, "\(label): the id")
                }
                #expect(got.entry.visible == want["visible"]!.boolValue!, "\(label): shown or not")
                #expect((got.mask != nil) == want["masked"]!.boolValue!, "\(label): its mask")
            }
            #expect(Set(read.threats.map(\.entry.id)).count == read.threats.count, "\(label): no two share an id")
            let written = ThreatSets.write(read)
            #expect(
                ThreatSets.sameValue(c["written"]!, .object(markNew(written, chosen))),
                "\(label): what is written back: \(JSONTree.object(written).serialized)")
        }
    }

    @Test func aDocumentReadAgainGivesAThreatWithNoIdTheSameOne() {
        let data = object(#"{"threats":[{"lat":1,"lon":2,"radars":[]},{"lat":3,"lon":4,"radars":[]}]}"#)
        let first = ThreatSets.read(data, freshPrefix: "x").threats.map(\.entry.id)
        #expect(ThreatSets.read(data, freshPrefix: "x").threats.map(\.entry.id) == first)
        #expect(first == ["x-0", "x-1"])
    }

    @Test func whatAMaskDependsOnIsWhatTheWebSays() {
        for c in cases(fixture, "subjects") {
            #expect(ThreatSets.sameValue(c["subject"]!, .object(ThreatSets.subjectOf(threatOf(c["threat"]!)))))
        }
    }

    @Test func whetherAMaskStillFitsIsWhatTheWebSays() {
        for c in cases(fixture, "masks") {
            #expect(ThreatSets.maskFits(threatOf(c["subject"]!), c["mask"]?.objectValue) == c["fits"]!.boolValue!, "\(name(c))")
        }
    }

    @Test func twoVersionsDifferingOnlyInTheirMasksAreTheSameAsTheWebSays() {
        for c in cases(fixture, "sameApartFromMasks") {
            #expect(
                ThreatSets.sameApartFromMasks(c["a"]!.objectValue!, c["b"]!.objectValue!) == c["same"]!.boolValue!, "\(name(c))")
        }
    }

    @Test func aFileIsNamedAsTheWebNamesIt() {
        for c in cases(fixture, "names") {
            #expect(ThreatSets.nameFromFile(content(c["file"])!) == content(c["name"]), "\(c["file"]!.serialized)")
        }
    }

    // MARK: What the web's fixture cannot say

    func threat(lat: Double = 34.5, radars: [Radar] = [Radars.default(Radars.detection)]) -> Threat {
        Threat(
            name: "SA-6", milstdId: "SHAPMFS-------", lat: lat, lon: -84.25, information: "", source: "SOF", showThreat: true,
            radars: radars)
    }

    func stored(_ id: String, lat: Double = 34.5, mask: JSONObject? = nil, visible: Bool = true) -> StoredThreat {
        StoredThreat(entry: ThreatEntry(id: id, threat: threat(lat: lat), visible: visible), mask: mask)
    }

    func maskFor(_ t: Threat, padding: Int = 10) -> JSONObject {
        JSONObject([("png", .string(String(repeating: "x", count: padding))), ("for", .object(ThreatSets.subjectOf(t)))])
    }

    @Test func aThreatThisVersionCannotHoldIsWrittenBackAsItCameAfterTheOthers() {
        let odd = json(#"{"id":"odd","lat":1,"lon":2,"radars":[{"type":0,"bands":[]}]}"#)
        var read = ThreatSets.read(JSONObject([("threats", .array([odd]))]))
        #expect(read.unreadable == [odd])
        read.threats = [stored("a")]
        let written = ThreatSets.write(read)["threats"]!.arrayValue!
        #expect(content(written[0]["id"]) == "a")
        #expect(written[safe: 1] == odd)
    }

    @Test func aNameSymbolNoteOrSourceThatIsMissingTakesTheDefaultAndOneThatIsNotTextCannotBeRead() {
        let bare = ThreatSets.read(object(#"{"threats":[{"lat":1,"lon":2,"radars":[]}]}"#)).threats[0].entry.threat
        #expect(bare.name == Radars.defaultName)
        #expect(bare.milstdId == Radars.defaultMilstdId)
        #expect(bare.information == "")
        #expect(bare.source == Radars.defaultSource)
        #expect(ThreatSets.read(object(#"{"threats":[{"name":4,"lat":1,"lon":2,"radars":[]}]}"#)).unreadable.count == 1)
    }

    @Test func numbersAreWrittenAsJavaScriptWritesThem() {
        let written = JSONTree.object(ThreatSets.write(ThreatSetDocument(threats: [stored("a")]))).serialized
        #expect(written.contains(#""rangeNmi":25,"#), "\(written)")
        #expect(written.contains(#""altFt":50,"#), "\(written)")
        #expect(written.contains(#""type":0,"#), "\(written)")
    }

    @Test func aRadarsUnknownFieldGoesBackUnchangedUntilTheRadarIsChanged() {
        let data = object(
            #"{"threats":[{"id":"a","lat":1,"lon":2,"radars":[{"type":0,"rangeNmi":25,"antennaHeightFt":20,"aglNotMsl":true,"showMask":true,"showRangeRings":true,"bands":[],"sector":[1,2]}]}]}"#
        )
        var read = ThreatSets.read(data)
        #expect(JSONTree.object(ThreatSets.write(read)).serialized.contains("sector"))
        for i in read.threats[0].entry.threat.radars.indices { read.threats[0].entry.threat.radars[i].rangeNmi = 30 }
        #expect(!JSONTree.object(ThreatSets.write(read)).serialized.contains("sector"))
    }

    @Test func aMaskIsLeftOutOnceTheSetsMasksComeToMoreThanTheBudgetAndTheRestStillGo() {
        let big = threat()
        let nearly = maskFor(big, padding: ThreatSets.maskBudget - 2_000)
        let small = maskFor(big, padding: 100)
        let written = ThreatSets.write(
            ThreatSetDocument(threats: [
                StoredThreat(entry: ThreatEntry(id: "a", threat: big), mask: nearly),
                StoredThreat(entry: ThreatEntry(id: "b", threat: big), mask: nearly),
                StoredThreat(entry: ThreatEntry(id: "c", threat: big), mask: small),
            ]))
        let threats = written["threats"]!.arrayValue!.elements.map { $0.objectValue! }
        #expect(threats[0].has("mask"))
        #expect(!threats[1].has("mask"))
        #expect(
            threats[2].has("mask")
                || JSONTree.object(nearly).serialized.utf16.count + JSONTree.object(small).serialized.utf16.count > ThreatSets.maskBudget)
    }

    @Test func aMaskGoneStaleIsNotWritten() {
        let mask = maskFor(threat(lat: 1))
        let written = ThreatSets.write(
            ThreatSetDocument(threats: [StoredThreat(entry: ThreatEntry(id: "a", threat: threat(lat: 2)), mask: mask)]))
        #expect(!written["threats"]!.arrayValue![0].objectValue!.has("mask"))
    }

    @Test func aSetNameIsCleanedAsTheWebCleansItAndNeverCutThroughAnEmoji() {
        #expect(ThreatSets.cleanName("  op dk ") == "OP DK")
        let long = String(repeating: "a", count: 99) + "😀b"
        #expect(ThreatSets.cleanName(long) == String(repeating: "A", count: 99) + "😀")
    }

    @Test func sameValueTellsNumbersByValueKeysByNameAndNullFromNothing() {
        #expect(ThreatSets.sameValue(json(#"{"a":1.50,"b":[2]}"#), json(#"{"b":[2.0],"a":1.5}"#)))
        #expect(!ThreatSets.sameValue(json(#"{"a":null}"#), json("{}")))
        #expect(!ThreatSets.sameValue(json(#"{"a":"1"}"#), json(#"{"a":1}"#)))
        #expect(!ThreatSets.sameValue(.null, json("0")))
        #expect(ThreatSets.sameValue(.null, .null))
    }

    // MARK: Laying someone else's change over this device's

    func ids(_ list: [StoredThreat]) -> [String] { list.map(\.entry.id) }

    @Test func aThreatChangedOnlyThereTakesTheirVersion() {
        let out = ThreatSets.merge(base: [stored("a")], mine: [stored("a")], theirs: [stored("a", lat: 2)])
        #expect(out.map(\.entry.threat.lat) == [2])
    }

    @Test func aThreatChangedOnlyHereKeepsThisDevicesVersion() {
        let out = ThreatSets.merge(base: [stored("a")], mine: [stored("a", lat: 3)], theirs: [stored("a")])
        #expect(out.map(\.entry.threat.lat) == [3])
    }

    @Test func aThreatChangedOnBothKeepsThisDevices() {
        let out = ThreatSets.merge(base: [stored("a")], mine: [stored("a", lat: 3)], theirs: [stored("a", lat: 2)])
        #expect(out.map(\.entry.threat.lat) == [3])
    }

    @Test func oneAddedOnEachSideIsKeptOnBothTheirsFirst() {
        #expect(
            ids(ThreatSets.merge(base: [stored("a")], mine: [stored("a"), stored("c")], theirs: [stored("a"), stored("b")]))
                == ["a", "b", "c"])
    }

    @Test func oneRemovedHereThatTheyLeftAloneGoesAndOneRemovedThereThatThisDeviceLeftAloneGoes() {
        #expect(ids(ThreatSets.merge(base: [stored("a"), stored("b")], mine: [stored("b")], theirs: [stored("a"), stored("b")])) == ["b"])
        #expect(ids(ThreatSets.merge(base: [stored("a"), stored("b")], mine: [stored("a"), stored("b")], theirs: [stored("b")])) == ["b"])
    }

    @Test func oneEditedThereAndRemovedHereStaysAsEditedAndOneEditedHereAndRemovedThereStays() {
        #expect(ThreatSets.merge(base: [stored("a")], mine: [], theirs: [stored("a", lat: 2)]).map(\.entry.threat.lat) == [2])
        #expect(ThreatSets.merge(base: [stored("a")], mine: [stored("a", lat: 3)], theirs: []).map(\.entry.threat.lat) == [3])
    }

    @Test func aMaskIsNeverAReasonToKeepAnOlderThreatAndIsKeptWhenNothingElseChanged() {
        let mask = JSONObject([("for", .string("x"))])
        #expect(
            ThreatSets.merge(base: [stored("a")], mine: [stored("a", mask: mask)], theirs: [stored("a", lat: 2)]).map(\.entry.threat.lat)
                == [2])
        let kept = ThreatSets.merge(base: [stored("a")], mine: [stored("a", mask: mask)], theirs: [stored("a")])
        #expect(kept.count == 1 && kept[0].mask.map { JSONTree.object($0) } == .object(mask))
        let theirs = ThreatSets.merge(base: [stored("a")], mine: [stored("a")], theirs: [stored("a", mask: mask)])
        #expect(theirs.count == 1 && theirs[0].mask.map { JSONTree.object($0) } == .object(mask))
        let none = ThreatSets.merge(base: [stored("a")], mine: [stored("a")], theirs: [stored("a")])
        #expect(none.count == 1 && none[0].mask == nil)
    }

    @Test func whetherAThreatIsShownIsPartOfThePlan() {
        let out = ThreatSets.merge(base: [stored("a")], mine: [stored("a")], theirs: [stored("a", visible: false)])
        #expect(out.map(\.entry.visible) == [false])
    }
}
