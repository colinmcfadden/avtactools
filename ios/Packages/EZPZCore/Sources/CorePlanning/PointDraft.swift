import CoreModel

/// What a route point's planning row holds while the person is typing (the web's per-point row in `RoutePlanSection`): the altitude,
/// speed and wind flown *to* this point (the leg that arrives at it) and the clock time to be there, as typed. What shows is the value
/// in force, the point's own override or else the route's; what is *applied* is only what the person changed (`check`), so looking at
/// a point and pressing Apply does not turn the route's values into the point's own.
///
/// The first point has no arriving leg, so it has no speed or wind: only its altitude (the start altitude) and a clock.
public struct PointDraft: Equatable, Sendable {
    public var altitudeValue: String
    public var altitudeRef: String
    public var speedValue: String
    public var speedType: String
    public var windDir: String
    public var windSpeed: String
    /// The time of day to be at this point (the TOT), `HH:MM` or `HH:MM:SS`; blank for none.
    public var clock: String

    public init(
        altitudeValue: String, altitudeRef: String, speedValue: String, speedType: String, windDir: String, windSpeed: String,
        clock: String = ""
    ) {
        self.altitudeValue = altitudeValue
        self.altitudeRef = altitudeRef
        self.speedValue = speedValue
        self.speedType = speedType
        self.windDir = windDir
        self.windSpeed = windSpeed
        self.clock = clock
    }

    // By hand, as a Kotlin data class compares its text: unit for unit, never Swift's canonical equivalence.
    public static func == (a: PointDraft, b: PointDraft) -> Bool {
        same(a.altitudeValue, b.altitudeValue) && same(a.altitudeRef, b.altitudeRef) && same(a.speedValue, b.speedValue)
            && same(a.speedType, b.speedType) && same(a.windDir, b.windDir) && same(a.windSpeed, b.windSpeed) && same(a.clock, b.clock)
    }

    /// What `check` found: the override to merge into the point's, and, when the clock was changed, what it was changed to (nil when
    /// it was not; blank clears it).
    public enum Checked: Equatable, Sendable {
        case valid(Valid)
        case refused(String)
    }

    public struct Valid: Equatable, Sendable {
        public var patch: OverridePatch
        public var clock: String?

        public init(_ patch: OverridePatch, clock: String?) {
            self.patch = patch
            self.clock = clock
        }

        /// Nothing was changed.
        public var isEmpty: Bool { patch.altitude == nil && patch.airspeed == nil && patch.wind == nil && clock == nil }

        public static func == (a: Valid, b: Valid) -> Bool { a.patch == b.patch && same(a.clock, b.clock) }
    }

    /// Checks what was typed against `before` (what the row showed when it was opened). Only a group that was changed is part of the
    /// patch: the altitude and its reference together, the speed and its reference together, the direction and speed of the wind
    /// together, as the web writes them. `first` leaves the speed and wind out.
    public func check(before: PointDraft, first: Bool) -> Checked {
        do throws(PlanDraft.Refusal) {
            var altitude: AltitudeSetting?
            if !(same(altitudeValue, before.altitudeValue) && same(altitudeRef, before.altitudeRef)) {
                if !PlanDraft.altitudeRefs.contains(where: { same($0, altitudeRef) }) {
                    throw PlanDraft.Refusal("Altitude reference must be AGL or MSL.")
                }
                altitude = AltitudeSetting(try PlanDraft.number("Altitude", altitudeValue, -2000, 30000), altitudeRef)
            }
            var speed: Airspeed?
            if !(first || (same(speedValue, before.speedValue) && same(speedType, before.speedType))) {
                if !PlanDraft.airspeedTypes.contains(where: { same($0, speedType) }) {
                    throw PlanDraft.Refusal("Airspeed reference must be ground, indicated or true.")
                }
                speed = Airspeed(try PlanDraft.number("Airspeed", speedValue, 0, 400), speedType)
            }
            var wind: Wind?
            if !(first || (same(windDir, before.windDir) && same(windSpeed, before.windSpeed))) {
                wind = Wind(
                    dirTrue: try PlanDraft.number("Wind direction", windDir, 0, 360),
                    speedKts: try PlanDraft.number("Wind speed", windSpeed, 0, 200))
            }
            let time = kotlinTrim(clock)
            if !time.isEmpty && !Self.isClock(time) {
                throw PlanDraft.Refusal("The time must be written hours:minutes, like 09:15 or 09:15:30.")
            }
            let patch = OverridePatch(altitude: altitude, airspeed: speed, wind: wind)
            return .valid(Valid(patch, clock: same(time, kotlinTrim(before.clock)) ? nil : time))
        } catch {
            return .refused(error.message)
        }
    }

    /// The whole of `(?:[01]?[0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?`, in ASCII digits as the JVM's `\d` reads them.
    private static func isClock(_ text: String) -> Bool {
        let parts = text.utf16.split(separator: 0x3A, omittingEmptySubsequences: false).map(Array.init)
        guard parts.count == 2 || parts.count == 3 else { return false }
        func digit(_ u: UInt16) -> Bool { u >= 0x30 && u <= 0x39 }
        let hours = parts[0]
        let hoursOk =
            switch hours.count {
            case 1: digit(hours[0])
            case 2:
                ((hours[0] == 0x30 || hours[0] == 0x31) && digit(hours[1])) || (hours[0] == 0x32 && hours[1] >= 0x30 && hours[1] <= 0x33)
            default: false
            }
        guard hoursOk else { return false }
        return parts.dropFirst().allSatisfy { $0.count == 2 && $0[0] >= 0x30 && $0[0] <= 0x35 && digit($0[1]) }
    }

    /// The row for the point `pointId` of a route planned as `plan`: the values in force there.
    public static func of(_ plan: RoutePlan, _ pointId: String) -> PointDraft {
        let own = plan.perPoint[pointId]
        let altitude = own?.altitude ?? plan.altitude
        let speed = own?.airspeed ?? plan.airspeed
        let wind = own?.wind ?? plan.wind ?? Wind()
        return PointDraft(
            altitudeValue: PlanDraft.typed(altitude.value), altitudeRef: altitude.ref, speedValue: PlanDraft.typed(speed.value),
            speedType: speed.type, windDir: PlanDraft.typed(wind.dirTrue), windSpeed: PlanDraft.typed(wind.speedKts),
            clock: own?.clock ?? "")
    }
}
