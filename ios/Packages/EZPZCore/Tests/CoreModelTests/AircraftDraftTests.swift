import CoreJS
import CoreTesting
import Testing

@testable import CoreModel

/// The aircraft form, held to what the server takes (`contracts/fixtures/aircraft/limits.json`, probed from `_apply_fields`): a
/// profile made with no signal must be one the server will accept, because a refused create stays in the outbox for good. Android's
/// AircraftDraftTest.
struct AircraftDraftTests {
    let limits: JSONObject
    let numbers: JSONObject

    init() throws {
        limits = try Fixtures.load("aircraft/limits.json")
        numbers = limits["numbers"]!.objectValue!
    }

    func draft(name: String = "MH-47G Chinook", designation: String = "MH-47G") -> AircraftDraft {
        AircraftDraft(name: name, designation: designation)
    }

    func valid(_ draft: AircraftDraft, _ existing: JSONObject? = nil, sourceLocation: SourceLocation = #_sourceLocation) -> (
        name: String, data: JSONObject
    ) {
        guard case .valid(let name, let data) = draft.check(existing) else {
            Issue.record("refused: \(draft.check(existing))", sourceLocation: sourceLocation)
            return ("", JSONObject())
        }
        return (name, data)
    }

    func refused(_ draft: AircraftDraft, _ existing: JSONObject? = nil) -> String? {
        if case .refused(let message) = draft.check(existing) { message } else { nil }
    }

    func number(_ data: JSONObject, _ key: String) -> Double? { data[key]?.numberValue?.value }

    /// The form's numbers, each with its label and the setter that puts text in it.
    let fields: [(key: String, label: String, set: (inout AircraftDraft, String) -> Void)] = [
        ("rotor_diameter_m", "Rotor diameter", { $0.rotorDiameterM = $1 }),
        ("rotor_tip_clearance_m", "Tip clearance", { $0.rotorTipClearanceM = $1 }),
        ("default_airspeed_kts", "Cruise airspeed", { $0.defaultAirspeedKts = $1 }),
        ("max_indicated_kts", "Max indicated airspeed", { $0.maxIndicatedKts = $1 }),
        ("default_altitude_ft", "Default altitude", { $0.defaultAltitudeFt = $1 }),
        ("default_fuel_flow_lb_hr", "Fuel flow", { $0.defaultFuelFlowLbHr = $1 }),
        ("default_gross_weight_lb", "Gross weight", { $0.defaultGrossWeightLb = $1 }),
    ]

    func with(_ change: (inout AircraftDraft) -> Void) -> AircraftDraft {
        var d = draft()
        change(&d)
        return d
    }

    // MARK: The blank form

    @Test func aBlankFormCarriesTheWebsDefaultsAndMakesACustomProfile() {
        let result = valid(draft())
        #expect(result.name == "MH-47G Chinook")
        let data = result.data
        #expect(data["designation"]?.stringValue == "MH-47G")
        #expect(data["icon_key"]?.stringValue == "generic")
        #expect(number(data, "rotor_diameter_m") == 16.36)
        #expect(number(data, "rotor_tip_clearance_m") == 60)
        #expect(number(data, "default_airspeed_kts") == 100)
        #expect(data["default_airspeed_type"]?.stringValue == "ground")
        #expect(number(data, "max_indicated_kts") == 160)
        #expect(number(data, "default_altitude_ft") == 50)
        #expect(data["default_altitude_ref"]?.stringValue == "agl")
        #expect(number(data, "default_fuel_flow_lb_hr") == 960)
        #expect(number(data, "default_gross_weight_lb") == 16000)
        #expect(data["perf_source"]?.stringValue == "custom")
    }

    @Test func aNewProfileIsNotGivenAltitudeLimitsTheServersOwnAreLeftToApply() {
        let data = valid(draft()).data
        #expect(!data.has("min_altitude_ft_msl"))
        #expect(!data.has("max_altitude_ft_msl"))
    }

    // MARK: Name and designation

    @Test func nameAndDesignationAreBothNeededAndBlanksDoNotCount() {
        let required = "Name and designation are required."
        #expect(refused(draft(name: "")) == required)
        #expect(refused(draft(designation: "")) == required)
        #expect(refused(draft(name: "   ", designation: "MH-47G")) == required)
        #expect(refused(draft(name: "x", designation: "\t \n")) == required)
    }

    @Test func nameAndDesignationAreSavedTrimmed() {
        let result = valid(draft(name: "  MH-47G Chinook ", designation: " MH-47G  "))
        #expect(result.name == "MH-47G Chinook")
        #expect(result.data["designation"]?.stringValue == "MH-47G")
    }

    @Test func nameAndDesignationAreCutWhereTheServerCutsThemByCharactersAndNotInTheMiddleOfOne() {
        let maxName = Int(limits["maxName"]!.numberValue!.value)
        let maxDesignation = Int(limits["maxDesignation"]!.numberValue!.value)
        let long = valid(draft(name: String(repeating: "n", count: maxName + 30), designation: String(repeating: "d", count: maxDesignation + 30)))
        #expect(long.name.utf16.count == maxName)
        #expect(long.data["designation"]?.stringValue?.utf16.count == maxDesignation)

        // An emoji is two UTF-16 units: one that straddles the limit goes whole, or not at all.
        let emoji = "🚁"
        let straddling = valid(draft(name: String(repeating: "n", count: maxName - 1) + emoji + "tail"))
        #expect(straddling.name == String(repeating: "n", count: maxName - 1) + emoji)
        #expect(straddling.name.unicodeScalars.count == maxName)
    }

    @Test func aNameAtTheLimitIsKeptWhole() {
        let maxName = Int(limits["maxName"]!.numberValue!.value)
        #expect(valid(draft(name: String(repeating: "n", count: maxName))).name == String(repeating: "n", count: maxName))
    }

    @Test func anIconIsKeptAndAnEmptyOneIsTheGenericSilhouette() {
        #expect(valid(with { $0.iconKey = "ch47" }).data["icon_key"]?.stringValue == "ch47")
        #expect(valid(with { $0.iconKey = "  " }).data["icon_key"]?.stringValue == "generic")
        let maxIcon = Int(limits["maxIconKey"]!.numberValue!.value)
        #expect(valid(with { $0.iconKey = String(repeating: "i", count: maxIcon + 5) }).data["icon_key"]?.stringValue?.count == maxIcon)
    }

    @Test func theIconsOfferedAreTheSilhouettesTheWebDrawsGenericLast() {
        #expect(AircraftDraft.iconKeys == ["uh60", "ah64", "ch47", "uh72", "mh6", "generic"])
    }

    // MARK: The server's limits

    @Test func everyNumberTheFormSetsHasALimitInTheServersFileAndNoLimitIsMissingFromTheForm() {
        // The form does not show the altitude limits.
        let settable = Set(numbers.keys).subtracting(["min_altitude_ft_msl", "max_altitude_ft_msl"])
        #expect(settable == Set(fields.map(\.key)))
    }

    @Test func eachNumberIsAcceptedAtTheServersLimitsAndRefusedJustBeyondThem() {
        for field in fields {
            let low = numbers[field.key]!["min"]!.numberValue!.value
            let high = numbers[field.key]!["max"]!.numberValue!.value
            #expect(number(valid(with { field.set(&$0, AircraftDraft.plain(low)) }).data, field.key) == low, "\(field.key) at its minimum")
            #expect(number(valid(with { field.set(&$0, AircraftDraft.plain(high)) }).data, field.key) == high, "\(field.key) at its maximum")
            for beyond in [low - 0.01, high + 0.01] {
                let message = refused(with { field.set(&$0, TypedNumber.plain(beyond)) })
                let expected = "\(field.label) must be between \(AircraftDraft.plain(low)) and \(AircraftDraft.plain(high))."
                #expect(message == expected, "\(field.key) at \(beyond)")
            }
        }
    }

    @Test func theAirspeedReferencesAndAltitudeReferencesAreTheServers() {
        #expect(limits["airspeedTypes"]!.arrayValue!.elements.map { $0.stringValue! } == AircraftDraft.airspeedTypes)
        #expect(limits["altitudeRefs"]!.arrayValue!.elements.map { $0.stringValue! } == AircraftDraft.altitudeRefs)
        for type in AircraftDraft.airspeedTypes {
            #expect(valid(with { $0.defaultAirspeedType = type }).data["default_airspeed_type"]?.stringValue == type)
        }
        for ref in AircraftDraft.altitudeRefs {
            #expect(valid(with { $0.defaultAltitudeRef = ref }).data["default_altitude_ref"]?.stringValue == ref)
        }
    }

    @Test func anAirspeedOrAltitudeReferenceTheServerDoesNotKnowIsRefused() {
        #expect(refused(with { $0.defaultAirspeedType = "calibrated" }) == "Airspeed reference must be ground, indicated or true.")
        #expect(refused(with { $0.defaultAltitudeRef = "hat" }) == "Altitude reference must be AGL or MSL.")
    }

    // MARK: What counts as a number

    @Test func aBlankNumberIsRefusedWithTheFieldNamedNotTurnedIntoTheDefault() {
        #expect(refused(with { $0.rotorDiameterM = "" }) == "Rotor diameter needs a number.")
        #expect(refused(with { $0.defaultFuelFlowLbHr = "   " }) == "Fuel flow needs a number.")
    }

    @Test(arguments: ["abc", "12 kt", "1,5", "1e3", "--5", "1.2.3", ".", "+", "NaN", "Infinity", "0x10", "٣"])
    func textThatIsNotAPlainNumberIsRefused(_ text: String) {
        #expect(refused(with { $0.defaultAirspeedKts = text }) == "Cruise airspeed is not a number.", "'\(text)'")
    }

    @Test func numbersAreReadAsTypedWithASignALeadingOrTrailingPointAndSpacesRoundThem() {
        #expect(number(valid(with { $0.rotorDiameterM = " 16.5 " }).data, "rotor_diameter_m") == 16.5)
        #expect(number(valid(with { $0.rotorTipClearanceM = ".5" }).data, "rotor_tip_clearance_m") == 0.5)
        #expect(number(valid(with { $0.rotorDiameterM = "12." }).data, "rotor_diameter_m") == 12)
        #expect(number(valid(with { $0.defaultAltitudeFt = "-100" }).data, "default_altitude_ft") == -100)
        #expect(number(valid(with { $0.defaultAirspeedKts = "+30" }).data, "default_airspeed_kts") == 30)
    }

    @Test func theFirstRefusalIsTheFirstFieldInTheForm() {
        let twoBad = with {
            $0.rotorDiameterM = "abc"
            $0.defaultGrossWeightLb = "abc"
        }
        #expect(refused(twoBad) == "Rotor diameter is not a number.")
    }

    // MARK: Spacing

    @Test func theSpacingIsTheRotorAndItsClearanceAndNothingWhileEitherIsNotANumber() throws {
        #expect(abs(try #require(AircraftDraft().spacingM) - 76.36) < 1e-9)
        #expect(abs(try #require(AircraftDraft(rotorDiameterM: "4.5", rotorTipClearanceM: "6").spacingM) - 10.5) < 1e-9)
        #expect(AircraftDraft(rotorDiameterM: "").spacingM == nil)
        // "6." is a number being typed.
        #expect(abs(try #require(AircraftDraft(rotorDiameterM: "4", rotorTipClearanceM: "6.").spacingM) - 10) < 1e-9)
        #expect(AircraftDraft(rotorTipClearanceM: "x").spacingM == nil)
    }

    // MARK: Changing and copying a profile

    func existing(_ extra: [(String, JSONTree)] = []) -> JSONObject {
        JSONObject(
            [
                ("id", .number(41)), ("slug", .string("mh-47g")), ("min_altitude_ft_msl", .number(-500)),
                ("max_altitude_ft_msl", .number(15000)), ("rotor_diameter_m", .number(18.3)),
            ] + extra
        )
    }

    @Test func changingAProfileKeepsWhatTheFormDoesNotShowAndReplacesWhatItDoes() {
        let data = valid(with { $0.rotorDiameterM = "19" }, existing()).data
        #expect(number(data, "id") == 41)
        #expect(data["slug"]?.stringValue == "mh-47g")
        #expect(number(data, "min_altitude_ft_msl") == -500)
        #expect(number(data, "max_altitude_ft_msl") == 15000)
        #expect(number(data, "rotor_diameter_m") == 19)
        // A key the profile has keeps its place, as a LinkedHashMap's put keeps it.
        #expect(data.keys.prefix(5) == ["id", "slug", "min_altitude_ft_msl", "max_altitude_ft_msl", "rotor_diameter_m"])
    }

    @Test func aProfileThatCameFromTheServerKeepsTheSourceTheServerGaveItAndOneWithNoneIsCustom() {
        #expect(valid(draft(), existing([("perf_source", .string("published"))])).data["perf_source"]?.stringValue == "published")
        #expect(valid(draft(), existing()).data["perf_source"]?.stringValue == "custom")
    }

    @Test func aDraftOfASavedProfileReadsBackAsTheSameProfile() {
        var profile = AircraftProfile.fallback
        profile.name = "Mine"
        profile.designation = "X-1"
        profile.iconKey = "ah64"
        profile.rotorDiameterM = 16.357
        profile.defaultAirspeedType = "indicated"
        profile.defaultAltitudeRef = "msl"
        profile.defaultAltitudeFt = -1500
        profile.defaultFuelFlowLbHr = 1234.5
        let draft = AircraftDraft.of(profile)
        #expect(draft.rotorDiameterM == "16.357")
        #expect(draft.rotorTipClearanceM == "60")
        #expect(draft.defaultAltitudeFt == "-1500")
        #expect(draft.defaultFuelFlowLbHr == "1234.5")
        let again = AircraftProfile.normalize(valid(draft).data.setting("name", .string("Mine")))
        #expect(again.name == "Mine")
        #expect(again.designation == "X-1")
        #expect(again.iconKey == "ah64")
        #expect(again.rotorDiameterM == 16.357)
        #expect(again.rotorTipClearanceM == 60)
        #expect(again.defaultAirspeedType == "indicated")
        #expect(again.defaultAltitudeRef == "msl")
        #expect(again.defaultAltitudeFt == -1500)
        #expect(again.defaultFuelFlowLbHr == 1234.5)
        #expect(again.perfSource == "custom")
    }

    @Test func numbersAreWrittenBackWithoutATrailingPointZeroOrAnExponent() {
        #expect(AircraftDraft.plain(60) == "60")
        #expect(AircraftDraft.plain(0) == "0")
        #expect(AircraftDraft.plain(-0.0) == "0")
        #expect(AircraftDraft.plain(200_000) == "200000")
        #expect(AircraftDraft.plain(0.0001) == "0.0001")
        #expect(AircraftDraft.plain(-2000) == "-2000")
        #expect(AircraftDraft.plain(16.357) == "16.357")
        #expect(AircraftDraft.plain(1e21) == "1000000000000000000000")
        #expect(AircraftDraft.plain(1.5e-7) == "0.00000015")
        #expect(AircraftDraft.plain(-1.25e22) == "-12500000000000000000000")
    }

    @Test func aCopyIsANewProfileNamedAsACopyWithTheNumbersOfTheOneItCameFrom() {
        var master = AircraftProfile.fallback
        master.id = 3
        let copy = AircraftDraft.copyOf(master)
        #expect(copy.name == "UH-60L Black Hawk (copy)")
        #expect(copy.designation == "UH-60L")
        #expect(copy.iconKey == "uh60")
        #expect(copy.defaultAirspeedKts == "100")
        #expect(copy.maxIndicatedKts == "193")
        #expect(valid(copy).data.has("designation"))
    }
}

/// `normalizeProfile`, and the reading rules it shares with nothing else (Android's `JsNumber.finiteOr`).
struct AircraftProfileTests {
    @Test func noProfileIsTheUH60LAndOneWithNoIconIsGeneric() {
        #expect(AircraftProfile.normalize(nil) == .fallback)
        let bare = AircraftProfile.normalize(JSONObject())
        #expect(bare.iconKey == "generic")
        #expect(bare.slug == "uh60l")
    }

    @Test func aNumberIsAJsonNumberOrPlainDecimalTextAndAnythingElseIsTheDefault() {
        func rotor(_ value: String) -> Double { AircraftProfile.normalize(object(#"{"rotor_diameter_m": \#(value)}"#)).rotorDiameterM }
        #expect(rotor("12") == 12)
        #expect(rotor(#"" 12.5 ""#) == 12.5)
        #expect(rotor(#""1e1""#) == 10)
        #expect(rotor(#""-.5""#) == -0.5)
        #expect(rotor(#""0x10""#) == 16.357)
        #expect(rotor(#""12 m""#) == 16.357)
        #expect(rotor(#""1e""#) == 16.357)
        #expect(rotor(#""NaN""#) == 16.357)
        #expect(rotor("null") == 16.357)
        #expect(rotor("true") == 16.357)
        #expect(rotor("[12]") == 16.357)
        #expect(rotor("1e400") == 16.357)
    }

    @Test func anIdIsAWholeNumbersTextAndAFlagIsTrueOrFalseQuotedOrNot() {
        let profile = AircraftProfile.normalize(object(#"{"id": "41", "is_system": "false", "has_template": true}"#))
        #expect(profile.id == 41)
        #expect(!profile.isSystem)
        #expect(profile.hasTemplate)
        #expect(AircraftProfile.normalize(object(#"{"id": 41.0}"#)).id == nil)
        #expect(AircraftProfile.normalize(object(#"{"is_system": 0}"#)).isSystem)
    }

    @Test func optionalTextIsNoneForAPrimitiveThatIsNotTextAndTheDefaultForAListOrAnObject() {
        #expect(AircraftProfile.normalize(object(#"{"amps_vehicle_description": null}"#)).ampsVehicleDescription == nil)
        #expect(AircraftProfile.normalize(object(#"{"amps_vehicle_description": 3}"#)).ampsVehicleDescription == nil)
        #expect(AircraftProfile.normalize(object(#"{"amps_vehicle_description": [3]}"#)).ampsVehicleDescription == AircraftProfile.fallback.ampsVehicleDescription)
    }

    @Test func aProfileIsReadAndWrittenInTheServersSnakeCase() throws {
        let read = try ModelJSON(ignoreUnknownKeys: true).decode(AircraftProfile.self, from: json(#"{"id": 3, "slug": "ch47", "rotor_diameter_m": 18.3}"#))
        #expect(read.id == 3)
        #expect(read.rotorDiameterM == 18.3)
        #expect(read.name == "UH-60L Black Hawk")
        let written = ModelJSON().encodeObject(read)
        #expect(written.keys == AircraftProfile.serialNames)
        #expect(written["template_kind"] == .null)
    }
}
