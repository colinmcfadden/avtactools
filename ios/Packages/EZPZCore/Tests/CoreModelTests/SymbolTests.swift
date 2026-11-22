import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// Held to contracts/fixtures/symbols/sidc.json: the unit builder's SIDC logic, its presets, and how a new unit is made, as the web
/// does them. Android's SidcFixtureTest.
struct SidcFixtureTests {
    let fixture: JSONObject

    init() throws { fixture = try Fixtures.load("symbols/sidc.json") }

    @Test func aSIDCIsBuiltFromItsPartsFittedToFifteenCharactersAsTheWebBuildsIt() {
        let all = cases(fixture, "build")
        #expect(all.count >= 15)
        for c in all {
            let args = c["args"]?.objectValue ?? JSONObject()
            let built = Sidc.build(
                affiliation: content(args["affiliation"]) ?? "F", dimension: content(args["dimension"]) ?? "G",
                status: content(args["status"]) ?? "P", functionId: content(args["functionId"]) ?? "------",
                echelon: content(args["echelon"]) ?? "-"
            )
            #expect(built == content(c["expected"]), "build \(JSONTree.object(args).serialized)")
        }
    }

    @Test func theAffiliationIsChangedAtTheSecondPositionAndASIDCTooShortForOneIsLeftAlone() {
        for c in cases(fixture, "withAffiliation") {
            #expect(Sidc.withAffiliation(content(c["sidc"]), content(c["affiliation"])!) == content(c["expected"]), "\(c["sidc"]!)")
        }
    }

    @Test func theAffiliationIsReadFromTheSecondPositionAndUnknownWhenThereIsNone() {
        for c in cases(fixture, "affiliation") {
            #expect(Sidc.affiliation(content(c["sidc"])) == content(c["expected"]), "\(String(describing: c["sidc"]))")
        }
    }

    @Test func aSIDCIsSplitIntoThePartsTheBuilderEditsAndAShortOneHasNone() {
        for c in cases(fixture, "parse") {
            let expected = c["expected"]!.objectValue!
            let parts = Sidc.parse(content(c["sidc"]))
            if expected.isEmpty {
                #expect(parts == nil, "\(String(describing: c["sidc"]))")
            } else {
                let want = Sidc.Parts(
                    affiliation: content(expected["affiliation"])!, dimension: content(expected["dimension"])!,
                    status: content(expected["status"])!, functionId: content(expected["functionId"])!,
                    echelon: content(expected["echelon"])!
                )
                #expect(parts == want, "\(String(describing: c["sidc"]))")
            }
        }
    }

    func rows(_ name: String) -> [[String]] {
        fixture["presets"]![name]!.arrayValue!.elements.map { row in
            let keys = ["id", "label"] + (["color", "functionId", "code", "sidc"].filter { row.objectValue!.has($0) })
            return keys.map { content(row[$0])! }
        }
    }

    @Test func thePresetsAreTheWebsInTheWebsOrder() {
        #expect(rows("affiliations") == SymbolPresets.affiliations.map { [$0.id, $0.label, $0.color] })
        #expect(rows("unitFunctions") == SymbolPresets.unitFunctions.map { [$0.id, $0.label, $0.functionId] })
        #expect(rows("echelons") == SymbolPresets.echelons.map { [$0.id, $0.label, $0.code] })
        #expect(rows("threats") == SymbolPresets.threats.map { [$0.id, $0.label, $0.sidc] })
        #expect(rows("unitTypes") == SymbolPresets.unitTypes.map { [$0.id, $0.label, $0.sidc] })
    }

    @Test func everyPresetIsAWholeSIDC() {
        for preset in SymbolPresets.unitTypes { #expect(preset.sidc.utf16.count == 15, "\(preset.id)") }
        // The web's threat presets are 14 characters, one short of a whole 2525C SIDC (milsymbol draws them all the same). Kept as
        // they are: a threat's SIDC is what an AMPS .ths file carries, so it is not ours to lengthen.
        for preset in SymbolPresets.threats { #expect(preset.sidc.utf16.count == 14, "\(preset.id)") }
        for function in SymbolPresets.unitFunctions { #expect(function.functionId.utf16.count == 6, "\(function.id)") }
        // The builder's own SIDC for a preset's parts is the preset (so the preset list and the builder agree).
        let infantry = SymbolPresets.unitTypes.first { $0.id == "infantry" }!
        #expect(infantry.sidc == Sidc.build(functionId: SymbolPresets.unitFunctions.first { $0.id == "infantry" }!.functionId))
    }

    @Test func aNewUnitIsMadeAsTheWebMakesIt() {
        for c in cases(fixture, "createUnit") {
            let config = c["config"]!.objectValue!
            let center = c["center"]!.arrayValue!.elements.map { $0.numberValue!.value }
            let made = UnitMarkers.create(
                UnitConfig(
                    id: content(config["id"]), path: content(config["path"]), sidc: content(config["sidc"]),
                    uniqueDesignation: content(config["uniqueDesignation"]), higherFormation: content(config["higherFormation"])
                ),
                center: LatLon(lat: center[0], lon: center[1]), offset: c["offset"]!.numberValue!.value, id: content(c["id"])!
            )
            check(name(c), c["expected"]!, .object(made))
            #expect(c["expected"]!.objectValue!.keys == made.keys, "\(name(c)): the same keys, none left undefined")
        }
    }
}

/// The unit builder's draft: what it starts from, what a saved unit gives it, and what it makes and changes. Android's
/// UnitDraftTest.
struct UnitDraftTests {
    /// A unit whose every value is text, or null.
    func unit(_ pairs: (String, String?)...) -> JSONObject {
        JSONObject(pairs.map { ($0.0, $0.1.map(JSONTree.string) ?? .null) })
    }

    func patch(_ pairs: (String, String)...) -> JSONTree { .object(JSONObject(pairs.map { ($0.0, .string($0.1)) })) }

    @Test func aNewUnitStartsAsFriendlyInfantryWithNoEchelonAndNoLabels() {
        let draft = UnitDraft()
        #expect(draft.sidc == "SFGPUCI--------")
        #expect(draft == UnitDraft.of(nil))
        #expect(draft.config == UnitConfig(sidc: "SFGPUCI--------", uniqueDesignation: "", higherFormation: ""))
    }

    @Test func aSavedUnitGivesItsPartsBackAsTheBuilderShowsThem() {
        let draft = UnitDraft.of(unit(("sidc", "SHGPUCIL--D----"), ("uniqueDesignation", "A/1-171"), ("higherFormation", "2-101")))
        #expect(
            draft
                == UnitDraft(
                    affiliation: "H", functionId: "UCIL--", echelon: "D", uniqueDesignation: "A/1-171", higherFormation: "2-101"))
        #expect(draft.sidc == "SHGPUCIL--D----")
        #expect(draft.affiliationLabel == "Hostile")
        #expect(draft.functionLabel == "Infantry (Light)")
        #expect(draft.echelonLabel == "Platoon")
    }

    @Test func aMissingShortOrEmptyCodeFallsBackToTheDefaultsAndTheLabelsAreKept() {
        #expect(UnitDraft.of(unit(("path", "/units/tank.svg"), ("uniqueDesignation", "TANK"))) == UnitDraft(uniqueDesignation: "TANK"))
        // Fewer than eleven characters has no parts.
        #expect(UnitDraft.of(unit(("sidc", "SHGPUC"))) == UnitDraft())
        #expect(UnitDraft.of(unit(("sidc", ""))) == UnitDraft())
        #expect(UnitDraft.of(unit(("sidc", nil))) == UnitDraft())
    }

    @Test func aLabelThatIsNotTextIsReadAsTheTextItIsAndNullAsNothing() {
        #expect(UnitDraft.of(JSONObject([("uniqueDesignation", .number(5))])).uniqueDesignation == "5")
        #expect(UnitDraft.of(JSONObject([("higherFormation", .bool(true))])).higherFormation == "true")
        #expect(UnitDraft.of(JSONObject([("uniqueDesignation", .null)])).uniqueDesignation == "")
        #expect(UnitDraft.of(JSONObject()).higherFormation == "")
    }

    /// Not in Android's test: a number is written as JavaScript writes it, where the Kotlin port's `JsonPrimitive.content` kept the
    /// literal's text (the web's `String(5.0)` is `"5"`).
    @Test func aNumberLabelIsWrittenAsJavaScriptWritesIt() {
        #expect(UnitDraft.of(object(#"{"uniqueDesignation": 5.0}"#)).uniqueDesignation == "5")
        #expect(UnitDraft.of(object(#"{"uniqueDesignation": 1e3}"#)).uniqueDesignation == "1000")
    }

    @Test func aUnitOfAnotherDimensionOrStatusIsRebuiltAsGroundAndPresentAsTheWebsBuilderDoes() {
        let air = UnitDraft.of(unit(("sidc", "SFAPMFF--------")))
        #expect(air.affiliation == "F")
        // The A for air is not one of the builder's parts, nor is the A for anticipated.
        #expect(air.sidc == "SFGPMFF--------")
        #expect(UnitDraft.of(unit(("sidc", "SFGAUCI--------"))).sidc == "SFGPUCI--------")
    }

    @Test func aFunctionTheBuilderHasNoNameForIsKeptAndHasNoLabel() {
        let draft = UnitDraft.of(unit(("sidc", "SFGPUCV--------")))
        #expect(draft.functionId == "UCV---")
        #expect(draft.functionLabel == "Aviation")
        let custom = UnitDraft.of(unit(("sidc", "SFGPUXXXXX-------")))
        #expect(custom.functionLabel == nil)
        #expect(custom.functionId == "UXXXXX")
    }

    @Test func theEchelonDashHasNoName() {
        #expect(UnitDraft().echelonLabel == nil)
        #expect(UnitDraft(echelon: "F").echelonLabel == "Battalion")
        #expect(UnitDraft(echelon: "Z").echelonLabel == nil)
    }

    @Test func applyingADraftToAUnitChangesItsSymbolAndLabelsAndNothingElse() {
        let patch = UnitDraft(affiliation: "N", functionId: "UCA---", echelon: "E", uniqueDesignation: "B/2", higherFormation: "1-22")
            .patch
        #expect(Set(patch.keys) == ["sidc", "uniqueDesignation", "higherFormation"])
        #expect(patch["sidc"] == .string("SNGPUCA---E----"))
        #expect(patch["uniqueDesignation"] == .string("B/2"))
    }

    @Test func applyingADraftChangesOnlyWhatDiffersFromWhatItWasMadeFrom() {
        let now = UnitDraft(affiliation: "H", functionId: "UCA---", echelon: "D", uniqueDesignation: "A/1-171", higherFormation: "2-101")
        #expect(JSONTree.object(now.patchOver(now)) == patch())
        var designation = now
        designation.uniqueDesignation = "B/1-171"
        #expect(JSONTree.object(designation.patchOver(now)) == patch(("uniqueDesignation", "B/1-171")))
        var echelon = now
        echelon.echelon = "E"
        #expect(JSONTree.object(echelon.patchOver(now)) == patch(("sidc", "SHGPUCA---E----")))
        var two = now
        two.affiliation = "F"
        two.higherFormation = ""
        #expect(Set(two.patchOver(now).keys) == ["sidc", "higherFormation"])
        // A unit of another dimension keeps its code when only a label changes; one with no code (an older image) takes the draft's.
        let air = UnitDraft.of(unit(("sidc", "SFAPMFF--------"), ("uniqueDesignation", "DUSTOFF")))
        var renamed = air
        renamed.uniqueDesignation = "DUSTOFF 2"
        #expect(renamed.patchOver(air, hasCode: UnitDraft.hasCode(unit(("sidc", "SFAPMFF--------")))).keys == ["uniqueDesignation"])
        let image = unit(("path", "/units/tank.svg"))
        #expect(
            JSONTree.object(UnitDraft.of(image).patchOver(UnitDraft.of(image), hasCode: UnitDraft.hasCode(image)))
                == patch(("sidc", "SFGPUCI--------")))
    }

    @Test func aPartChangedUnderTheBuilderIsShownAndWhatThePersonMadeOfAnotherIsKept() {
        let start = UnitDraft(affiliation: "H", functionId: "UCA---", echelon: "D", uniqueDesignation: "A/1-171", higherFormation: "2-101")
        var typed = start
        typed.uniqueDesignation = "B/1-171"
        // Someone else made it a company meanwhile.
        var now = start
        now.echelon = "E"
        #expect(
            typed.following(start, now)
                == UnitDraft(affiliation: "H", functionId: "UCA---", echelon: "E", uniqueDesignation: "B/1-171", higherFormation: "2-101"))
        // The same part changed both ways: what the person made of it stays, to be applied over theirs.
        var mine = start
        mine.uniqueDesignation = "C/1-171"
        var theirs = start
        theirs.uniqueDesignation = "D/1-171"
        #expect(mine.following(start, theirs).uniqueDesignation == "C/1-171")
        #expect(start.following(start, now) == now)
    }

    @Test func everyFunctionAndEchelonTheBuilderOffersMakesAWholeSymbolCode() {
        for function in SymbolPresets.unitFunctions { #expect(UnitDraft(functionId: function.functionId).sidc.utf16.count == 15) }
        for echelon in SymbolPresets.echelons { #expect(UnitDraft(echelon: echelon.code).sidc.utf16.count == 15) }
        for affiliation in SymbolPresets.affiliations {
            #expect(Sidc.affiliation(UnitDraft(affiliation: affiliation.id).sidc) == affiliation.id)
        }
    }
}
