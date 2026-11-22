import CoreJS
import CoreModel

/// What the route-plan form holds while the person is typing (the web's `RoutePlanSection`, the route-wide part): the numbers as
/// typed, and nothing applied until `check` says they are good. The numbers are text because "1." is not yet a number and must not be
/// turned into one under a finger.
///
/// **Not like the web:** the web's `num` turns an unreadable number into 0 (or 15 for the temperature) without a word. Here it is
/// refused with the field named, because a wind speed nobody typed is a wind nobody forecast. A wind direction outside 0 to 360 is
/// refused too, which the web's inputs only advise.
public struct PlanDraft: Equatable, Sendable {
    /// `YYYY-MM-DD`, or blank for today.
    public var date: String
    public var tempC: String
    public var fuelFlowLbHr: String
    public var airspeedValue: String
    public var airspeedType: String
    public var altitudeValue: String
    public var altitudeRef: String
    public var windDir: String
    public var windSpeed: String

    public init(
        date: String = "", tempC: String = "15", fuelFlowLbHr: String = "960", airspeedValue: String = "100",
        airspeedType: String = Airspeed.typeGround, altitudeValue: String = "50", altitudeRef: String = AltitudeSetting.refAgl,
        windDir: String = "0", windSpeed: String = "0"
    ) {
        self.date = date
        self.tempC = tempC
        self.fuelFlowLbHr = fuelFlowLbHr
        self.airspeedValue = airspeedValue
        self.airspeedType = airspeedType
        self.altitudeValue = altitudeValue
        self.altitudeRef = altitudeRef
        self.windDir = windDir
        self.windSpeed = windSpeed
    }

    // By hand, as a Kotlin data class compares its text: unit for unit, never Swift's canonical equivalence.
    public static func == (a: PlanDraft, b: PlanDraft) -> Bool {
        same(a.date, b.date) && same(a.tempC, b.tempC) && same(a.fuelFlowLbHr, b.fuelFlowLbHr) && same(a.airspeedValue, b.airspeedValue)
            && same(a.airspeedType, b.airspeedType) && same(a.altitudeValue, b.altitudeValue) && same(a.altitudeRef, b.altitudeRef)
            && same(a.windDir, b.windDir) && same(a.windSpeed, b.windSpeed)
    }

    /// What `check` found: the change to make, or a refusal in words.
    public enum Checked: Equatable, Sendable {
        case valid(PlanPatch)
        case refused(String)
    }

    public func check() -> Checked {
        do throws(Refusal) {
            if !Self.airspeedTypes.contains(where: { same($0, airspeedType) }) {
                throw Refusal("Airspeed reference must be ground, indicated or true.")
            }
            if !Self.altitudeRefs.contains(where: { same($0, altitudeRef) }) { throw Refusal("Altitude reference must be AGL or MSL.") }
            let day = kotlinTrim(date)
            if !day.isEmpty && !Self.isDate(day) { throw Refusal("The date must be written year-month-day, like 2026-10-03.") }
            return .valid(
                PlanPatch(
                    airspeed: Airspeed(try Self.number("Airspeed", airspeedValue, 0, 400), airspeedType),
                    altitude: AltitudeSetting(try Self.number("Altitude", altitudeValue, -2000, 30000), altitudeRef),
                    wind: Wind(
                        dirTrue: try Self.number("Wind direction", windDir, 0, 360),
                        speedKts: try Self.number("Wind speed", windSpeed, 0, 200)),
                    tempC: try Self.number("Temperature", tempC, -100, 100),
                    fuelFlowLbHr: try Self.number("Fuel flow", fuelFlowLbHr, 0, 20000),
                    date: day))
        } catch {
            return .refused(error.message)
        }
    }

    /// Checks only what differs from `before` (what the form showed), as `PointDraft.check` does: the airspeed and its reference
    /// together, the altitude and its reference together, the wind's direction and speed together, and the temperature, the fuel flow
    /// and the date each alone. A field typed as it was is left out of the patch, so a plan the web saved with no wind does not gain
    /// one because the temperature was changed, and an imported mission whose date was set does not have its altitudes written back
    /// into its file.
    public func check(before: PlanDraft) -> Checked {
        do throws(Refusal) {
            var airspeed: Airspeed?
            if !(same(airspeedValue, before.airspeedValue) && same(airspeedType, before.airspeedType)) {
                if !Self.airspeedTypes.contains(where: { same($0, airspeedType) }) {
                    throw Refusal("Airspeed reference must be ground, indicated or true.")
                }
                airspeed = Airspeed(try Self.number("Airspeed", airspeedValue, 0, 400), airspeedType)
            }
            var altitude: AltitudeSetting?
            if !(same(altitudeValue, before.altitudeValue) && same(altitudeRef, before.altitudeRef)) {
                if !Self.altitudeRefs.contains(where: { same($0, altitudeRef) }) { throw Refusal("Altitude reference must be AGL or MSL.") }
                altitude = AltitudeSetting(try Self.number("Altitude", altitudeValue, -2000, 30000), altitudeRef)
            }
            var wind: Wind?
            if !(same(windDir, before.windDir) && same(windSpeed, before.windSpeed)) {
                wind = Wind(
                    dirTrue: try Self.number("Wind direction", windDir, 0, 360), speedKts: try Self.number("Wind speed", windSpeed, 0, 200))
            }
            let temp = same(tempC, before.tempC) ? nil : try Self.number("Temperature", tempC, -100, 100)
            let fuel = same(fuelFlowLbHr, before.fuelFlowLbHr) ? nil : try Self.number("Fuel flow", fuelFlowLbHr, 0, 20000)
            let day = kotlinTrim(date)
            var newDate: String?
            if !same(day, kotlinTrim(before.date)) {
                if !day.isEmpty && !Self.isDate(day) { throw Refusal("The date must be written year-month-day, like 2026-10-03.") }
                newDate = day
            }
            return .valid(PlanPatch(airspeed: airspeed, altitude: altitude, wind: wind, tempC: temp, fuelFlowLbHr: fuel, date: newDate))
        } catch {
            return .refused(error.message)
        }
    }

    /// A field refused, in words. Kotlin throws an exception for it; here it is a typed error.
    struct Refusal: Error {
        let message: String

        init(_ message: String) { self.message = message }
    }

    public static let airspeedTypes: [String] = [Airspeed.typeGround, Airspeed.typeIndicated, Airspeed.typeTrue]
    public static let altitudeRefs: [String] = [AltitudeSetting.refAgl, AltitudeSetting.refMsl]

    private static func isAsciiDigit(_ u: UInt16) -> Bool { u >= 0x30 && u <= 0x39 }

    /// The whole of `[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)`. The Kotlin writes `\d`, which on the JVM is ASCII digits alone (the
    /// tests' platform; Android's ICU would take every script's and then fail to read them).
    private static func isNumberText(_ text: String) -> Bool {
        var units = Array(text.utf16)[...]
        if let first = units.first, first == 0x2B || first == 0x2D { units = units.dropFirst() }
        let leading = units.prefix(while: isAsciiDigit).count
        units = units.dropFirst(leading)
        if leading > 0 {
            if units.first == 0x2E { units = units.dropFirst() }
            return units.allSatisfy(isAsciiDigit)
        }
        guard units.first == 0x2E else { return false }
        units = units.dropFirst()
        return !units.isEmpty && units.allSatisfy(isAsciiDigit)
    }

    /// A typed number: digits with an optional point and sign, and nothing else ("12 kt", "1e3" and "1,5" are not numbers here).
    /// Read as JavaScript's `Number` reads it, which for these forms ("1.", ".5", "+7", "-0") is the double Java's `toDouble` gives.
    static func parseNumber(_ text: String) -> Double? {
        let trimmed = kotlinTrim(text)
        return isNumberText(trimmed) ? JsNumber.parse(trimmed) : nil
    }

    private static func isDate(_ text: String) -> Bool {
        let units = Array(text.utf16)
        guard units.count == 10, units[4] == 0x2D, units[7] == 0x2D else { return false }
        for i in [0, 1, 2, 3, 5, 6, 8, 9] where !isAsciiDigit(units[i]) { return false }
        return LocalDate.parse(text) != nil
    }

    /// `typed` as a number between `min` and `max`, or a `Refusal` naming `label`.
    static func number(_ label: String, _ typed: String, _ min: Double, _ max: Double) throws(Refusal) -> Double {
        guard let value = parseNumber(typed) else {
            throw Refusal(kotlinIsBlank(typed) ? "\(label) needs a number." : "\(label) is not a number.")
        }
        if value < min || value > max {
            throw Refusal("\(label) must be between \(JsNumber.toText(min)) and \(JsNumber.toText(max)).")
        }
        return value
    }

    /// A number as it is typed back into a field: no trailing ".0", no exponent, no locale.
    static func typed(_ value: Double) -> String { JsNumber.toText(value) }

    /// The draft for a plan, to change it. A value the plan does not hold takes the default, as the web's
    /// `{...defaultRoutePlan(), ...plan}` does.
    public static func of(_ plan: RoutePlan) -> PlanDraft {
        let wind = plan.wind ?? Wind()
        return PlanDraft(
            date: plan.date, tempC: typed(plan.tempC ?? 15), fuelFlowLbHr: typed(plan.fuelFlowLbHr ?? 960),
            airspeedValue: typed(plan.airspeed.value), airspeedType: plan.airspeed.type, altitudeValue: typed(plan.altitude.value),
            altitudeRef: plan.altitude.ref, windDir: typed(wind.dirTrue), windSpeed: typed(wind.speedKts))
    }
}
