import CoreGeo
import CoreJS
import CoreModel
import Foundation

/// Route planning math: leg distances and courses along the sketched geometry, IAS to TAS conversion, wind-triangle ground speeds,
/// TOT-anchored clock times and fuel estimates. Pure and synchronous. Ground elevations (for AGL altitudes and density altitude) are
/// fetched separately and passed in.
///
/// A port of `frontend/src/feature/msnxImport/routeCalc.js`, held to `contracts/fixtures/planning/route.json`. The plan that comes out
/// is exported to AMPS, so the numbers must be the web's numbers: where the web does something subtle the comment says so.
public enum RouteCalc {
    /// JavaScript's `Math.round`: halves go up (Swift's `rounded()` takes a half away from zero, so -2.5 would be -3).
    ///
    /// The Kotlin port's is `floor(x + 0.5)`, which differs from `Math.round` only where adding the half rounds (0.49999999999999994
    /// is 1 there, 0 here) and in the sign of a zero; this is CoreJS's exact `Math.round`, as every Swift port uses.
    public static func jsRound(_ x: Double) -> Double { Js.round(x) }

    private static func toRad(_ deg: Double) -> Double { deg * Double.pi / 180 }
    private static func toDeg(_ rad: Double) -> Double { rad * 180 / Double.pi }

    /// ISA temperature (deg C) at a pressure altitude in feet.
    private static func isaTempC(_ altFt: Double) -> Double { 15 - 1.98 * (altFt / 1000) }

    /// IAS to TAS with the standard planning approximation: TAS grows about 2% per 1,000 ft of density altitude. DA from pressure
    /// altitude and OAT.
    public static func iasToTas(_ iasKts: Double, _ pressAltFt: Double, _ oatC: Double) -> Double {
        let da = pressAltFt + 118.8 * (oatC - isaTempC(pressAltFt))
        return iasKts * (1 + 0.02 * javaMax(0, da) / 1000)
    }

    public struct WindTriangle: Hashable, Sendable {
        public var gsKts: Double
        public var windCorrectionDeg: Double
        public var headwindKts: Double

        public init(gsKts: Double, windCorrectionDeg: Double, headwindKts: Double) {
            self.gsKts = gsKts
            self.windCorrectionDeg = windCorrectionDeg
            self.headwindKts = headwindKts
        }
    }

    /// Wind-triangle solve. Wind direction is where the wind blows FROM (degrees true), the aviation convention. Nil when the wind is
    /// too strong for this TAS to hold the course.
    public static func solveWindTriangle(_ tasKts: Double, _ courseDeg: Double, _ windFromDeg: Double, _ windKts: Double) -> WindTriangle? {
        if windKts <= 0 { return WindTriangle(gsKts: tasKts, windCorrectionDeg: 0, headwindKts: 0) }
        let relRad = toRad(windFromDeg - courseDeg)
        let headwind = windKts * cos(relRad)
        let crosswind = windKts * sin(relRad)
        if abs(crosswind) > tasKts { return nil }
        let wcaRad = asin(crosswind / tasKts)
        let gs = tasKts * cos(wcaRad) - headwind
        if gs <= 0 { return nil }
        return WindTriangle(gsKts: gs, windCorrectionDeg: toDeg(wcaRad), headwindKts: headwind)
    }

    private static func isPlanned(_ point: RoutePoint) -> Bool {
        !same(point.kind, RoutePoint.kindShaping) && !(point.id ?? "").isEmpty
    }

    /// AMPS-point list for planning: shaping points only shape leg geometry.
    ///
    /// A point with no AMPS id is excluded as well. Per-point plan overrides are keyed by point id, so an id-less point cannot hold or
    /// export one: every such row would read and write the same entry and move together.
    public static func planPoints(_ points: [RoutePoint]) -> [RoutePoint] { points.filter(isPlanned) }

    /// A `HH:MM` or `HH:MM:SS` clock on a `YYYY-MM-DD` date. An empty date means `today`. Hours or minutes past their range roll
    /// over, as JavaScript's `setHours` does. Nil for text that is not a clock or a date that is not one.
    ///
    /// The clock is `([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?` matched whole, after JavaScript's trim: ASCII digits only, as JavaScript's
    /// `\d` is (Swift's, like Android's ICU, takes every script's digits).
    static func parseClock(_ date: String, _ time: String?, _ today: LocalDate) -> LocalDateTime? {
        guard let time, !time.isEmpty else { return nil }
        let parts = Js.trim(time).split(separator: ":", omittingEmptySubsequences: false).map { Array($0.utf16) }
        func digits(_ part: [UInt16], _ counts: ClosedRange<Int>) -> Int64? {
            guard counts.contains(part.count), part.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            return part.reduce(0) { $0 * 10 + Int64($1 - 0x30) }
        }
        guard parts.count == 2 || parts.count == 3, let hh = digits(parts[0], 1...2), let mm = digits(parts[1], 2...2) else { return nil }
        var ss: Int64 = 0
        if parts.count == 3 {
            guard let seconds = digits(parts[2], 2...2) else { return nil }
            ss = seconds
        }
        let day: LocalDate
        if date.isEmpty {
            day = today
        } else {
            guard let parsed = LocalDate.parse(date) else { return nil }
            day = parsed
        }
        return day.atStartOfDay().plusHours(hh).plusMinutes(mm).plusSeconds(ss)
    }

    private struct PointAltitude {
        let ref: String
        let value: Double
        let mslFt: Double?
        let aglFt: Double?
        let groundFt: Double?
    }

    /// The text of a point's name, or nil when it has none or an empty one (JavaScript's `p.name || …`).
    private static func named(_ name: String?) -> String? { (name ?? "").isEmpty ? nil : name }

    private static func override(_ plan: RoutePlan, _ id: String?) -> PointOverride? { id.flatMap { plan.perPoint[$0] } }

    /// Computes the full plan for a sketched route.
    ///
    /// - Parameters:
    ///   - points: every point of the route in flight order, shaping points included
    ///   - elevationsFt: optional ground elevation per point id, for AGL altitudes and density-altitude TAS conversion
    ///   - today: the day clock times fall on when the plan names none
    public static func computeRoutePlan(
        _ points: [RoutePoint], _ plan: RoutePlan, elevationsFt: OrderedMap<Double> = [:], today: LocalDate = .today()
    ) -> PlanResult {
        var warnings: [String] = []
        // Index into `points` for each AMPS point: leg distance follows the shaping points between two of them, and the web finds
        // those by identity.
        let ampsIndex = points.indices.filter { isPlanned(points[$0]) }
        let amps = ampsIndex.map { points[$0] }
        if amps.count < 2 {
            return PlanResult(points: [], legs: [], totals: nil, warnings: ["Route needs at least two route points."])
        }

        // Point altitudes (per-point override, else route default). A charted elevation (from a snapped local point) is
        // authoritative over the DEM.
        let pointAlts = amps.map { p -> PointAltitude in
            let alt = override(plan, p.id)?.altitude ?? plan.altitude
            let groundFt = p.chartElevationFt ?? p.id.flatMap { elevationsFt[$0] }
            var mslFt: Double?
            var aglFt: Double?
            if same(alt.ref, AltitudeSetting.refMsl) {
                mslFt = alt.value
                if let groundFt { aglFt = alt.value - groundFt }
            } else {
                aglFt = alt.value
                if let groundFt { mslFt = groundFt + alt.value }
            }
            return PointAltitude(ref: alt.ref, value: alt.value, mslFt: mslFt, aglFt: aglFt, groundFt: groundFt)
        }

        // Legs between consecutive AMPS points; distance follows the shaping geometry the aircraft actually flies, course is
        // point-to-point. Airspeed and wind are "to" values: they come from the point being flown TO (the leg's arrival point).
        var legs: [PlanLeg] = []
        for i in 0..<(amps.count - 1) {
            let from = amps[i]
            let to = amps[i + 1]

            var dist = 0.0
            for j in ampsIndex[i]..<ampsIndex[i + 1] {
                let a = points[j]
                let b = points[j + 1]
                dist += GreatCircle.distanceNm(a.lat, a.lon, b.lat, b.lon)
            }
            let course = GreatCircle.trueCourseDeg(from.lat, from.lon, to.lat, to.lon)

            let arriving = override(plan, to.id)
            let airspeed = arriving?.airspeed ?? plan.airspeed
            let wind = arriving?.wind ?? plan.wind ?? Wind(dirTrue: 0, speedKts: 0)
            var gsKts: Double?
            var tasKts: Double?
            var windCorrectionDeg = 0.0

            if same(airspeed.type, Airspeed.typeGround) {
                gsKts = airspeed.value
            } else {
                let tas: Double
                if same(airspeed.type, Airspeed.typeIndicated) {
                    // Density altitude from the leg's average planned MSL altitude when known; otherwise convert at the raw
                    // altitude value (best effort).
                    let altFt = ((pointAlts[i].mslFt ?? pointAlts[i].value) + (pointAlts[i + 1].mslFt ?? pointAlts[i + 1].value)) / 2
                    tas = iasToTas(airspeed.value, altFt, plan.tempC ?? 15)
                } else {
                    tas = airspeed.value
                }
                tasKts = tas
                if let solved = solveWindTriangle(tas, course, wind.dirTrue, wind.speedKts) {
                    gsKts = solved.gsKts
                    windCorrectionDeg = solved.windCorrectionDeg
                } else {
                    // The web labels a leg's end by name, or by position when it has none.
                    let fromLabel = named(from.name) ?? String(i + 1)
                    let toLabel = named(to.name) ?? String(i + 2)
                    warnings.append("Leg \(fromLabel) → \(toLabel): wind exceeds what \(kotlinLong(jsRound(tas))) KTAS can correct for.")
                    gsKts = nil
                }
            }

            let timeSec = gsKts.flatMap { $0 > 0 ? dist / $0 * 3600 : nil }
            let fuelLb = timeSec.map { $0 / 3600 * (plan.fuelFlowLbHr ?? 0) }

            legs.append(
                PlanLeg(
                    fromId: from.id, toId: to.id, fromName: named(from.name) ?? "PT\(i + 1)", toName: named(to.name) ?? "PT\(i + 2)",
                    distNm: dist, courseTrueDeg: course, airspeed: airspeed, wind: wind, tasKts: tasKts, gsKts: gsKts,
                    windCorrectionDeg: windCorrectionDeg, timeSec: timeSec, fuelLb: fuelLb))
        }

        // Rolling ("stopwatch") elapsed time from the first point. Once a leg has no ground speed, nothing after it has a time:
        // unknown, never guessed.
        var cumSec: [Double?] = [0]
        var timingBroken = false
        for leg in legs {
            if leg.timeSec == nil { timingBroken = true }
            cumSec.append(timingBroken ? nil : cumSec.last!! + leg.timeSec!)
        }

        // Clock times, anchored at whichever point carries a clock (the TOT).
        var anchorIdx = amps.firstIndex { !(override(plan, $0.id)?.clock ?? "").isEmpty } ?? -1
        let anchorTime = anchorIdx >= 0 ? parseClock(plan.date, override(plan, amps[anchorIdx].id)?.clock, today) : nil
        if anchorTime == nil { anchorIdx = -1 }

        let planned = amps.enumerated().map { i, p -> PlanPoint in
            var clock: String?
            var clockTime: LocalDateTime?
            if let anchorTime, let at = cumSec[i], let anchor = cumSec[anchorIdx] {
                // JavaScript Dates hold whole milliseconds, and floor the sum. Kotlin's Long arithmetic wraps; so does `&*`.
                let millis = kotlinLong(floor((at - anchor) * 1000))
                clockTime = anchorTime.plusNanos(millis &* 1_000_000)
                clock = formatClock(clockTime)
            }
            // The leg arriving at this point supplies its "to" speed/wind (the first point has none).
            let legTo = i > 0 ? legs[i - 1] : nil
            let alt = pointAlts[i]
            return PlanPoint(
                id: p.id, uiId: p.uiId ?? p.id ?? "route-plan-point-\(i)", name: p.name, ptType: p.ptType, lat: p.lat, lon: p.lon,
                ref: alt.ref, value: alt.value, mslFt: alt.mslFt, aglFt: alt.aglFt, groundFt: alt.groundFt, airspeed: legTo?.airspeed,
                wind: legTo?.wind, legDistNm: legTo?.distNm, legCourseTrueDeg: legTo?.courseTrueDeg, legGsKts: legTo?.gsKts,
                legTimeSec: legTo?.timeSec, legFuelLb: legTo?.fuelLb, elapsedSec: cumSec[i], clock: clock,
                hasClock: !(override(plan, p.id)?.clock ?? "").isEmpty, isTotAnchor: i == anchorIdx, clockTime: clockTime)
        }

        let totalTimeSec = timingBroken ? nil : cumSec.last!
        let totals = PlanTotals(
            distNm: legs.reduce(0.0) { $0 + $1.distNm },
            timeSec: totalTimeSec,
            fuelLb: totalTimeSec.map { $0 / 3600 * (plan.fuelFlowLbHr ?? 0) })

        return PlanResult(points: planned, legs: legs, totals: totals, warnings: warnings)
    }

    /// `HH:MM:SS` for a time of day, or `--:--:--` for none.
    public static func formatClock(_ time: LocalDateTime?) -> String {
        guard let time else { return "--:--:--" }
        // Not String(format:): it follows the locale, and on a device set to Arabic or Persian could write the digits in that script.
        return "\(padded(String(time.hour), 2)):\(padded(String(time.minute), 2)):\(padded(String(time.second), 2))"
    }

    /// `H:MM:SS` (or `M:SS` under an hour) for a duration in seconds, or `--` for none.
    public static func formatDuration(_ sec: Double?) -> String {
        guard let sec else { return "--" }
        let s = kotlinLong(jsRound(sec))
        let h = s / 3600
        let m = (s % 3600) / 60
        let r = s % 60
        return h > 0 ? "\(h):\(padded(String(m), 2)):\(padded(String(r), 2))" : "\(m):\(padded(String(r), 2))"
    }
}
