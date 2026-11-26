import CoreModel
import Testing

@testable import CorePlanning

struct PlanDraftTests {
    func patch(_ checked: PlanDraft.Checked) -> PlanPatch? { if case .valid(let p) = checked { p } else { nil } }
    func message(_ checked: PlanDraft.Checked) -> String? { if case .refused(let m) = checked { m } else { nil } }
    func valid(_ d: PlanDraft) -> PlanPatch? { patch(d.check()) }
    func refused(_ d: PlanDraft) -> String? { message(d.check()) }

    @Test func aPlanIsShownAsItIsInPlainNumbers() {
        let plan = RoutePlan(
            airspeed: Airspeed(120.5, Airspeed.typeIndicated), altitude: AltitudeSetting(1500, AltitudeSetting.refMsl),
            wind: Wind(dirTrue: 270, speedKts: 15), tempC: -5.5, fuelFlowLbHr: 2400, date: "2026-10-03")
        #expect(
            PlanDraft.of(plan)
                == PlanDraft(
                    date: "2026-10-03", tempC: "-5.5", fuelFlowLbHr: "2400", airspeedValue: "120.5", airspeedType: "indicated",
                    altitudeValue: "1500", altitudeRef: "msl", windDir: "270", windSpeed: "15"))
    }

    @Test func aValueThePlanDoesNotHoldTakesTheWebsDefault() {
        let draft = PlanDraft.of(RoutePlan(wind: nil, tempC: nil, fuelFlowLbHr: nil))
        #expect(draft.tempC == "15")
        #expect(draft.fuelFlowLbHr == "960")
        #expect(draft.windDir == "0")
        #expect(draft.windSpeed == "0")
    }

    @Test func aPlanShownAndAppliedUnchangedIsTheSamePlan() throws {
        let plan = RoutePlan(
            airspeed: Airspeed(98.7, Airspeed.typeTrue), altitude: AltitudeSetting(-120, AltitudeSetting.refMsl),
            wind: Wind(dirTrue: 45, speedKts: 3.5), tempC: 12.25, fuelFlowLbHr: 1111, date: "2027-01-02")
        let p = try #require(valid(PlanDraft.of(plan)))
        #expect(p.airspeed == plan.airspeed)
        #expect(p.altitude == plan.altitude)
        #expect(p.wind == plan.wind)
        #expect(p.tempC == plan.tempC)
        #expect(p.fuelFlowLbHr == plan.fuelFlowLbHr)
        #expect(p.date == plan.date)
    }

    @Test func typedNumbersAreReadWithASignAndAPointAndNothingElse() throws {
        let p = try #require(valid(PlanDraft(tempC: "-3", airspeedValue: " 120. ", altitudeValue: "+300", windDir: ".5")))
        #expect(p.airspeed?.value == 120)
        #expect(p.altitude?.value == 300)
        #expect(p.wind?.dirTrue == 0.5)
        #expect(p.tempC == -3)
        // The last is an Arabic three: the JVM's `\d` is ASCII, and so is the reading here.
        for bad in ["12 kt", "1e3", "1,5", "--1", "0x10", "٣"] {
            #expect(refused(PlanDraft(airspeedValue: bad)) == "Airspeed is not a number.", "\(bad)")
        }
        #expect(refused(PlanDraft(airspeedValue: "")) == "Airspeed needs a number.")
        #expect(refused(PlanDraft(airspeedValue: "   ")) == "Airspeed needs a number.")
    }

    @Test func eachNumberIsHeldToItsRangeAtBothEnds() {
        #expect(refused(PlanDraft(airspeedValue: "-1")) == "Airspeed must be between 0 and 400.")
        #expect(refused(PlanDraft(airspeedValue: "400.1")) == "Airspeed must be between 0 and 400.")
        #expect(valid(PlanDraft(airspeedValue: "0"))?.airspeed?.value == 0)
        #expect(valid(PlanDraft(airspeedValue: "400"))?.airspeed?.value == 400)
        #expect(refused(PlanDraft(altitudeValue: "30001")) == "Altitude must be between -2000 and 30000.")
        #expect(refused(PlanDraft(altitudeValue: "-2001")) == "Altitude must be between -2000 and 30000.")
        #expect(refused(PlanDraft(windDir: "361")) == "Wind direction must be between 0 and 360.")
        #expect(refused(PlanDraft(windDir: "-1")) == "Wind direction must be between 0 and 360.")
        #expect(valid(PlanDraft(windDir: "360"))?.wind?.dirTrue == 360)
        #expect(refused(PlanDraft(windSpeed: "-0.5")) == "Wind speed must be between 0 and 200.")
        #expect(refused(PlanDraft(tempC: "101")) == "Temperature must be between -100 and 100.")
        #expect(refused(PlanDraft(fuelFlowLbHr: "20001")) == "Fuel flow must be between 0 and 20000.")
        #expect(valid(PlanDraft(fuelFlowLbHr: "0"))?.fuelFlowLbHr == 0)
    }

    @Test func theFirstFieldThatIsWrongIsTheOneNamedInTheOrderTheFormIsRead() {
        #expect(refused(PlanDraft(airspeedValue: "x", altitudeValue: "y", windDir: "z")) == "Airspeed is not a number.")
        #expect(refused(PlanDraft(altitudeValue: "y", windDir: "z")) == "Altitude is not a number.")
        #expect(refused(PlanDraft(tempC: "t", windDir: "z", windSpeed: "q")) == "Wind direction is not a number.")
        #expect(refused(PlanDraft(tempC: "t", windSpeed: "q")) == "Wind speed is not a number.")
        #expect(refused(PlanDraft(tempC: "t", fuelFlowLbHr: "f")) == "Temperature is not a number.")
        #expect(refused(PlanDraft(fuelFlowLbHr: "f")) == "Fuel flow is not a number.")
    }

    @Test func theReferencesMustBeOnesThePlannerKnows() {
        #expect(refused(PlanDraft(airspeedType: "mach")) == "Airspeed reference must be ground, indicated or true.")
        #expect(refused(PlanDraft(altitudeRef: "feet")) == "Altitude reference must be AGL or MSL.")
        for type in PlanDraft.airspeedTypes { #expect(valid(PlanDraft(airspeedType: type))?.airspeed?.type == type) }
        for ref in PlanDraft.altitudeRefs { #expect(valid(PlanDraft(altitudeRef: ref))?.altitude?.ref == ref) }
    }

    @Test func theDateIsADayOrNothing() {
        #expect(valid(PlanDraft(date: ""))?.date == "")
        #expect(valid(PlanDraft(date: "   "))?.date == "")
        #expect(valid(PlanDraft(date: " 2026-10-03 "))?.date == "2026-10-03")
        for bad in ["2026-13-01", "2026-02-30", "26-10-03", "2026/10/03", "next tuesday", "2026-1-3"] {
            #expect(refused(PlanDraft(date: bad)) == "The date must be written year-month-day, like 2026-10-03.", "\(bad)")
        }
        // A leap day is a day.
        #expect(valid(PlanDraft(date: "2028-02-29"))?.date == "2028-02-29")
        // A year that is no leap year has none.
        #expect(refused(PlanDraft(date: "2100-02-29")) != nil)
    }

    @Test func nothingIsAppliedWhenAnythingIsRefused() {
        #expect(refused(PlanDraft(windSpeed: "x")) != nil)
        #expect(valid(PlanDraft(windSpeed: "x")) == nil)
    }

    @Test func anOverrideThatAPlanHasMakesNoDifferenceToWhatTheRouteWideFormShows() {
        let plan = RoutePlan(perPoint: ["p2": PointOverride(altitude: AltitudeSetting(999, AltitudeSetting.refMsl))])
        #expect(PlanDraft.of(plan) == PlanDraft.of(RoutePlan()))
    }

    // MARK: Only what was changed

    func changed(_ typed: PlanDraft, _ before: PlanDraft) -> PlanPatch? { patch(typed.check(before: before)) }

    func with(_ draft: PlanDraft, _ change: (inout PlanDraft) -> Void) -> PlanDraft {
        var copy = draft
        change(&copy)
        return copy
    }

    @Test func nothingTypedIsNothingToApply() {
        let before = PlanDraft.of(RoutePlan())
        #expect(changed(before, before) == PlanPatch())
    }

    @Test func aTemperatureTypedChangesTheTemperatureAloneSoAPlanWithNoWindDoesNotGainOne() {
        let before = PlanDraft.of(RoutePlan(wind: nil))
        #expect(changed(with(before) { $0.tempC = "-4" }, before) == PlanPatch(tempC: -4))
    }

    @Test func theFuelFlowAndTheDateAreEachTheirOwnField() {
        let before = PlanDraft.of(RoutePlan())
        #expect(changed(with(before) { $0.fuelFlowLbHr = "1200" }, before) == PlanPatch(fuelFlowLbHr: 1200))
        #expect(changed(with(before) { $0.date = " 2026-10-12 " }, before) == PlanPatch(date: "2026-10-12"))
        // Blank is today: a date taken away is a change too.
        let dated = PlanDraft.of(RoutePlan(date: "2026-10-12"))
        #expect(changed(with(dated) { $0.date = "" }, dated) == PlanPatch(date: ""))
    }

    @Test func theAirspeedAltitudeAndWindGoWithTheirReferencesAsTheWebWritesThem() {
        let before = PlanDraft.of(RoutePlan())
        #expect(
            changed(with(before) { $0.airspeedType = Airspeed.typeTrue }, before) == PlanPatch(airspeed: Airspeed(100, Airspeed.typeTrue)))
        #expect(
            changed(with(before) { $0.altitudeRef = AltitudeSetting.refMsl }, before)
                == PlanPatch(altitude: AltitudeSetting(50, AltitudeSetting.refMsl)))
        #expect(changed(with(before) { $0.windSpeed = "12" }, before) == PlanPatch(wind: Wind(dirTrue: 0, speedKts: 12)))
    }

    @Test func whatIsRefusedIsNamedAndAFieldThatWasNotTouchedIsNeverChecked() {
        // A value the form could never send, left alone.
        let before = with(PlanDraft.of(RoutePlan())) { $0.windDir = "not a number" }
        #expect(message(with(before) { $0.tempC = "warm" }.check(before: before)) == "Temperature is not a number.")
        #expect(message(with(before) { $0.fuelFlowLbHr = "-1" }.check(before: before)) == "Fuel flow must be between 0 and 20000.")
        #expect(
            message(with(before) { $0.date = "10/12/2026" }.check(before: before))
                == "The date must be written year-month-day, like 2026-10-03.")
        #expect(changed(with(before) { $0.tempC = "20" }, before) == PlanPatch(tempC: 20))
    }

    /// The text is compared as Kotlin compares it, unit for unit: a date typed with a decomposed character in it is a change from one
    /// with the composed one, though Swift's `==` would call them equal.
    @Test func aFieldIsChangedWhenItsTextIsUnitForUnitAnother() {
        let before = PlanDraft(tempC: "caf\u{E9}")
        #expect(message(PlanDraft(tempC: "cafe\u{301}").check(before: before)) == "Temperature is not a number.")
        #expect(PlanDraft(tempC: "cafe\u{301}") != before)
    }
}
