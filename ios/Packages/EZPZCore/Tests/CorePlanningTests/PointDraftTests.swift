import CoreModel
import Testing

@testable import CorePlanning

struct PointDraftTests {
    let plan = RoutePlan(
        airspeed: Airspeed(100, Airspeed.typeGround), altitude: AltitudeSetting(50, AltitudeSetting.refAgl),
        wind: Wind(dirTrue: 90, speedKts: 10),
        perPoint: [
            "p2": PointOverride(altitude: AltitudeSetting(200, AltitudeSetting.refMsl), airspeed: Airspeed(90, Airspeed.typeIndicated)),
            "p3": PointOverride(wind: Wind(dirTrue: 270, speedKts: 15), clock: "12:30:00"),
        ])

    func valid(_ d: PointDraft, _ before: PointDraft, first: Bool = false) -> PointDraft.Valid? {
        if case .valid(let v) = d.check(before: before, first: first) { v } else { nil }
    }

    func refused(_ d: PointDraft, _ before: PointDraft, first: Bool = false) -> String? {
        if case .refused(let m) = d.check(before: before, first: first) { m } else { nil }
    }

    func with(_ draft: PointDraft, _ change: (inout PointDraft) -> Void) -> PointDraft {
        var copy = draft
        change(&copy)
        return copy
    }

    func draft(_ a: String, _ ar: String, _ s: String, _ st: String, _ wd: String, _ ws: String, clock: String = "") -> PointDraft {
        PointDraft(altitudeValue: a, altitudeRef: ar, speedValue: s, speedType: st, windDir: wd, windSpeed: ws, clock: clock)
    }

    @Test func aPointWithNoOverrideShowsTheRoutesValuesAndOneWithSomeShowsItsOwn() {
        #expect(PointDraft.of(plan, "p1") == draft("50", "agl", "100", "ground", "90", "10"))
        // Altitude and speed its own, the wind the route's.
        #expect(PointDraft.of(plan, "p2") == draft("200", "msl", "90", "indicated", "90", "10"))
        #expect(PointDraft.of(plan, "p3") == draft("50", "agl", "100", "ground", "270", "15", clock: "12:30:00"))
        #expect(PointDraft.of(plan, "unknown") == PointDraft.of(plan, "p1"))
    }

    @Test func aPlanWithNoWindShowsNoWind() {
        var still = plan
        still.wind = nil
        let none = PointDraft.of(still, "p1")
        #expect(none.windDir == "0")
        #expect(none.windSpeed == "0")
    }

    @Test func lookingAtAPointAndApplyingChangesNothing() {
        for id in ["p1", "p2", "p3"] {
            let shown = PointDraft.of(plan, id)
            #expect(valid(shown, shown)?.isEmpty == true, "\(id)")
        }
    }

    @Test func onlyTheGroupThatWasChangedIsAppliedAndItsOtherHalfGoesWithIt() throws {
        let before = PointDraft.of(plan, "p1")
        let altitude = try #require(valid(with(before) { $0.altitudeValue = "300" }, before))
        // The reference as it was shown.
        #expect(altitude.patch.altitude == AltitudeSetting(300, AltitudeSetting.refAgl))
        #expect(altitude.patch.airspeed == nil)
        #expect(altitude.patch.wind == nil)
        #expect(altitude.clock == nil)

        let reference = try #require(valid(with(before) { $0.altitudeRef = "msl" }, before))
        #expect(reference.patch.altitude == AltitudeSetting(50, AltitudeSetting.refMsl))

        let speed = try #require(valid(with(before) { $0.speedType = "true" }, before))
        #expect(speed.patch.airspeed == Airspeed(100, "true"))
        #expect(speed.patch.altitude == nil)

        let wind = try #require(valid(with(before) { $0.windSpeed = "20" }, before))
        // The direction as it was shown.
        #expect(wind.patch.wind == Wind(dirTrue: 90, speedKts: 20))
        #expect(wind.patch.altitude == nil)
    }

    @Test func typingTheSameNumberDifferentlyIsAChangeOnlyWhenTheTextDiffers() {
        let before = PointDraft.of(plan, "p1")
        // Typed again, so it is applied.
        #expect(valid(with(before) { $0.altitudeValue = "50.0" }, before)?.patch.altitude?.value == 50)
        #expect(valid(with(before) { $0.altitudeValue = "50" }, before)?.patch.altitude == nil)
    }

    @Test func theFirstPointHasNoArrivingLegSoItsSpeedAndWindAreNotRead() throws {
        let before = PointDraft.of(plan, "p1")
        let typed = with(before) {
            $0.altitudeValue = "100"
            $0.speedValue = "banana"
            $0.windDir = "banana"
            $0.windSpeed = "-9"
        }
        let checked = try #require(valid(typed, before, first: true))
        #expect(checked.patch.altitude == AltitudeSetting(100, AltitudeSetting.refAgl))
        #expect(checked.patch.airspeed == nil)
        #expect(checked.patch.wind == nil)
    }

    @Test func aNumberThatCannotBeReadOrIsOutOfRangeIsRefusedWithItsNameAndNothingElseIsApplied() {
        let before = PointDraft.of(plan, "p2")
        #expect(refused(with(before) { $0.altitudeValue = "high" }, before) == "Altitude is not a number.")
        #expect(refused(with(before) { $0.altitudeValue = "99999" }, before) == "Altitude must be between -2000 and 30000.")
        #expect(refused(with(before) { $0.speedValue = "fast" }, before) == "Airspeed is not a number.")
        #expect(refused(with(before) { $0.speedValue = "-5" }, before) == "Airspeed must be between 0 and 400.")
        #expect(refused(with(before) { $0.windDir = "400" }, before) == "Wind direction must be between 0 and 360.")
        #expect(refused(with(before) { $0.windSpeed = "" }, with(before) { $0.windSpeed = "1" }) == "Wind speed needs a number.")
        #expect(refused(with(before) { $0.altitudeRef = "x" }, before) == "Altitude reference must be AGL or MSL.")
        #expect(refused(with(before) { $0.speedType = "x" }, before) == "Airspeed reference must be ground, indicated or true.")
    }

    @Test func aClockIsATimeOfDayAndBlankClearsIt() {
        let before = PointDraft.of(plan, "p1")
        for good in ["09:15", "9:15", "09:15:30", "23:59:59", "0:00", "00:00:00", "20:00", "2:05"] {
            #expect(valid(with(before) { $0.clock = good }, before)?.clock == good, "\(good)")
        }
        // Trimmed.
        #expect(valid(with(before) { $0.clock = "  12:30 " }, before)?.clock == "12:30")
        for bad in ["24:00", "12:60", "12:5", "1230", "12:30:60", "noon", "12.30", "-1:00", "012:00", "12:30:", ":30", "١٢:٣٠"] {
            #expect(
                refused(with(before) { $0.clock = bad }, before) == "The time must be written hours:minutes, like 09:15 or 09:15:30.",
                "\(bad)")
        }
        let clocked = PointDraft.of(plan, "p3")
        // Cleared: blank is the change.
        #expect(valid(with(clocked) { $0.clock = "" }, clocked)?.clock == "")
        // The same time, with spaces: no change.
        #expect(valid(with(clocked) { $0.clock = " 12:30:00 " }, clocked)?.clock == nil)
        #expect(valid(with(clocked) { $0.clock = "13:00" }, clocked)?.isEmpty == false)
    }

    @Test func aClockCanBeSetTogetherWithTheValuesOfTheSamePoint() {
        let before = PointDraft.of(plan, "p1")
        let checked = valid(
            with(before) {
                $0.altitudeValue = "75"
                $0.clock = "10:00"
            }, before)
        #expect(checked?.patch.altitude?.value == 75)
        #expect(checked?.clock == "10:00")
    }
}
