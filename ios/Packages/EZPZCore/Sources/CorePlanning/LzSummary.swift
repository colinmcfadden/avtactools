import CoreJS
import CoreModel
import Foundation

/// What the mission summary says about a landing zone: its area, how many aircraft fit, and the call the slope tile makes. Ported from
/// the web's `MissionSummary.jsx` (`lzAreaAndCapacity`), `feature/terrain/slopeCall.js` (`slopeCallFor`) and `getPolygonArea` in
/// `utils/Helpers.js`, held to `contracts/fixtures/planning/summary.json`.
public enum LzSummary {
    /// The Earth's radius in feet, as the web uses it for areas.
    private static let earthRadiusFt = 20_902_231.0

    /// The area of a polygon in square feet, by spherical excess (`getPolygonArea`). Fewer than three points is no area. Kept exactly
    /// as the web has it, quirks included: a polygon that crosses the antimeridian is not unwrapped, so its longitude jump is taken at
    /// face value.
    public static func polygonAreaSqFt(_ polygon: [LatLon]?) -> Double {
        guard let polygon, polygon.count >= 3 else { return 0 }
        let toRadians = Double.pi / 180  // multiplied, as the web does, not divided (the last bit differs)
        var area = 0.0
        for i in polygon.indices {
            let j = (i + 1) % polygon.count
            let lat1 = polygon[i].lat * toRadians
            let lat2 = polygon[j].lat * toRadians
            let lon1 = polygon[i].lon * toRadians
            let lon2 = polygon[j].lon * toRadians
            area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
        }
        area = (area * earthRadiusFt * earthRadiusFt) / 2.0
        return abs(area)
    }

    /// `areaSqFt` is rounded the way JavaScript's `Math.round` does (halves up), as the tile shows it.
    public struct AreaAndCapacity: Hashable, Sendable {
        public var areaSqFt: Int64
        public var capacity: Int

        public init(areaSqFt: Int64, capacity: Int) {
            self.areaSqFt = areaSqFt
            self.capacity = capacity
        }
    }

    /// The area and the number of `profile` aircraft that fit (a conservative square-grid estimate using that aircraft's own spacing,
    /// so a Chinook LZ holds fewer than a Black Hawk one). Capacity is worked out from the unrounded area, as the web does.
    public static func areaAndCapacity(_ polygon: [LatLon]?, _ profile: AircraftProfile = .fallback) -> AreaAndCapacity {
        guard let polygon else { return AreaAndCapacity(areaSqFt: 0, capacity: 0) }
        let area = polygonAreaSqFt(polygon)
        return AreaAndCapacity(areaSqFt: kotlinLong(floor(area + 0.5)), capacity: max(0, AircraftGeometry.capacityForArea(area, profile)))
    }

    public enum SlopeLevel: String, Hashable, Sendable { case safe, warning, danger }

    /// The UH-60's three slope limits, in the order a tie between them is named, with the words the tile uses for each.
    public enum SlopeLimit: Int, CaseIterable, Hashable, Sendable {
        case noseUp, noseDown, cross

        public var words: String {
            switch self {
            case .noseUp: "nose up"
            case .noseDown: "nose down"
            case .cross: "cross slope"
            }
        }
    }

    /// The steepest slope at one whole `heading` as the server measures it (an entry of `byHeading`), in degrees: nose up (the ground
    /// rising ahead, the server's `noseHighMaxDeg`), nose down (`noseLowMaxDeg`) and across (`crossSlopeMaxDeg`). A value that is not a
    /// number (NaN) judges nothing.
    public struct HeadingSlope: Hashable, Sendable {
        public var heading: Int
        public var noseUpDeg: Double
        public var noseDownDeg: Double
        public var crossDeg: Double

        public init(_ heading: Int, _ noseUpDeg: Double, _ noseDownDeg: Double, _ crossDeg: Double) {
            self.heading = heading
            self.noseUpDeg = noseUpDeg
            self.noseDownDeg = noseDownDeg
            self.crossDeg = crossDeg
        }
    }

    /// The wind the limits are judged for: the steady speed in knots (a gust is not counted), the direction it blows from in degrees
    /// true (METAR winds are true), 0..359 or nil, and whether it is variable.
    public struct SlopeWind: Hashable, Sendable {
        public var speedKts: Double
        public var fromDeg: Int?
        public var variable: Bool

        public init(_ speedKts: Double, _ fromDeg: Int?, _ variable: Bool) {
            self.speedKts = speedKts
            self.fromDeg = fromDeg
            self.variable = variable
        }
    }

    /// The limits in whole degrees for a wind, how much each was reduced by, and whether a nose-down landing is not allowed at all.
    public struct SlopeLimits: Hashable, Sendable {
        public var noseUp: Int
        public var noseDown: Int
        public var cross: Int
        public var reductionDeg: Int
        public var noseDownProhibited: Bool

        public init(noseUp: Int, noseDown: Int, cross: Int, reductionDeg: Int, noseDownProhibited: Bool) {
            self.noseUp = noseUp
            self.noseDown = noseDown
            self.cross = cross
            self.reductionDeg = reductionDeg
            self.noseDownProhibited = noseDownProhibited
        }

        public func of(_ limit: SlopeLimit) -> Int {
            switch limit {
            case .noseUp: noseUp
            case .noseDown: noseDown
            case .cross: cross
            }
        }

        /// The UH-60's slope landing limits with no wind (the owner's, a UH-60 pilot, 2026-10-13): 15° nose up, 6° nose down, 15°
        /// across.
        public static let calm = SlopeLimits(noseUp: 15, noseDown: 6, cross: 15, reductionDeg: 0, noseDownProhibited: false)
    }

    /// What the slope tile says: `label` is its own word and `maxDeg` the steepest slope (0 when nothing was measured); `heading` is
    /// the heading judged, nil when none was; `limits` are those for the wind; `lines` are what it says under the slope, in smaller
    /// text: what was judged at the landing heading, and the wind the limits are for.
    public struct SlopeCall: Hashable, Sendable {
        public var level: SlopeLevel
        public var label: String
        public var maxDeg: Double
        public var heading: Int?
        public var limits: SlopeLimits
        public var lines: [String]

        public init(
            _ level: SlopeLevel, _ label: String, _ maxDeg: Double, heading: Int? = nil, limits: SlopeLimits = .calm, lines: [String] = []
        ) {
            self.level = level
            self.label = label
            self.maxDeg = maxDeg
            self.heading = heading
            self.limits = limits
            self.lines = lines
        }
    }

    /// Every full five knots of steady wind takes two degrees off each limit.
    private static let ktPerStep = 5.0
    private static let degPerStep = 2

    /// Above this steady wind, in knots, a UH-60 may not land nose down at all.
    public static let noseDownProhibitedAboveKt = 15

    /// What an older server's analysis (no `byHeading`) says under the slope: no heading was judged.
    public static let headingsNotChecked = "Heading limits not checked (server not updated)"

    /// What an analysis with no landing heading says under the slope: there is no heading to judge.
    public static let noLandingHeading = "No landing heading: limits not checked"

    /// The wind for the limits from a weather report (`observation`, the one the weather tiles show for this LZ/PZ): nil when there is
    /// nothing to go by (no report, or one with no speed), so the limits are not reduced and the tile says why.
    public static func slopeWindOf(_ observation: WeatherObservation?) -> SlopeWind? {
        guard let observation, let speed = observation.windSpeedKt else { return nil }
        if !speed.isFinite || speed < 0 { return nil }
        return SlopeWind(speed, observation.windFromDegrees.map { (($0 % 360) + 360) % 360 }, observation.windVariable)
    }

    /// The limits for `wind`: each 2° less for every full 5 knots of steady wind and never below 0, and no nose down at all above 15
    /// knots. With no wind report (nil) they are the calm-wind limits, not reduced.
    public static func limitsFor(_ wind: SlopeWind?) -> SlopeLimits {
        guard let speed = wind?.speedKts, speed >= 0 else { return .calm }
        // Kotlin's Int is 32 bits and its multiplication wraps: an infinite wind is Int.MAX_VALUE steps, and twice that is -2.
        let reduction = Int(Int32(truncatingIfNeeded: degPerStep * kotlinInt(floor(speed / ktPerStep))))
        let prohibited = speed > Double(noseDownProhibitedAboveKt)
        func reduced(_ limit: Int) -> Int { max(0, limit - reduction) }
        return SlopeLimits(
            noseUp: reduced(SlopeLimits.calm.noseUp),
            noseDown: prohibited ? 0 : reduced(SlopeLimits.calm.noseDown),
            cross: reduced(SlopeLimits.calm.cross),
            reductionDeg: reduction,
            noseDownProhibited: prohibited)
    }

    /// `byHeading` as one entry per whole heading 0..359, each limit's slope in tenths of a degree (the server rounds to 0.1), in
    /// `SlopeLimit`'s order. Nil when it is not there (a server from before it) or not whole: a missing heading, or a slope that is not
    /// a number, judges nothing rather than guess. A heading given twice counts once, the first.
    private static func tableOf(_ byHeading: [HeadingSlope]?) -> [[Int]]? {
        guard let byHeading else { return nil }
        var table = [[Int]?](repeating: nil, count: 360)
        for entry in byHeading {
            if !(0...359).contains(entry.heading) || table[entry.heading] != nil { continue }
            let values = [entry.noseUpDeg, entry.noseDownDeg, entry.crossDeg]
            if !values.allSatisfy({ $0.isFinite && $0 >= 0 }) { continue }
            table[entry.heading] = values.map { kotlinInt(RouteCalc.jsRound($0 * 10)) }
        }
        return table.allSatisfy { $0 != nil } ? table.map { $0! } : nil
    }

    private struct Excess {
        let limit: SlopeLimit
        let tenths: Int
    }

    /// Each limit's excess at `slopes` in tenths of a degree (over it when above 0), and the worst of them, the first in `SlopeLimit`
    /// on a tie.
    private static func worstOf(_ slopes: [Int], _ limits: SlopeLimits) -> Excess {
        // Kotlin's Int is 32 bits and its subtraction wraps; a slope read as Int.MIN_VALUE tenths would wrap past it.
        let excesses = SlopeLimit.allCases.map {
            Excess(limit: $0, tenths: Int(Int32(truncatingIfNeeded: slopes[$0.rawValue] - limits.of($0) * 10)))
        }
        return excesses.dropFirst().reduce(excesses[0]) { worst, next in next.tenths > worst.tenths ? next : worst }
    }

    /// A slope is outside its limit only when it is greater than it, at the 0.1° the server reports: level ground (0.0) is never nose
    /// down.
    private static func withinLimits(_ slopes: [Int], _ limits: SlopeLimits) -> Bool { worstOf(slopes, limits).tenths <= 0 }

    private static func onCompass(_ degrees: Int) -> Int { ((degrees % 360) + 360) % 360 }

    private static func headingText(_ heading: Int) -> String { padded(String(heading), 3) + "°" }

    /// Tenths of a degree as the web's `toFixed(1)` writes them (never below 0 here).
    private static func tenthsText(_ tenths: Int) -> String { "\(tenths / 10).\(tenths % 10)" }

    /// A wind speed as a station gives it: whole knots without a decimal point, anything else to one place.
    private static func knots(_ speed: Double) -> String {
        speed == floor(speed) && speed < 1e15 ? String(kotlinLong(speed)) : JsNumber.toFixed(speed, 1)
    }

    /// What was judged at the landing heading: within limits, or the limit it is most over, with the slope there.
    private static func landingWords(_ heading: Int, _ slopes: [Int], _ limits: SlopeLimits) -> String {
        let worst = worstOf(slopes, limits)
        if worst.tenths <= 0 { return "Landing \(headingText(heading)): within limits" }
        return
            "Landing \(headingText(heading)): \(worst.limit.words) \(tenthsText(slopes[worst.limit.rawValue]))°, limit \(limits.of(worst.limit))°"
    }

    private static func windWords(_ wind: SlopeWind?, _ limits: SlopeLimits) -> String {
        guard let wind else { return "No wind report: limits not reduced for wind" }
        if wind.speedKts == 0 { return "Wind calm: limits not reduced" }
        let what = "Wind \(wind.variable ? "variable " : "")\(knots(wind.speedKts)) kt"
        if limits.reductionDeg == 0 { return "\(what): limits not reduced" }
        let reduced = "\(what): limits \u{2212}\(limits.reductionDeg)°"
        return limits.noseDownProhibited ? "\(reduced), nose down prohibited above \(noseDownProhibitedAboveKt) kt" : reduced
    }

    /// The call from the steepest slope alone, when no heading can be judged: HEADING REQUIRED at 15° or more.
    private static func byMaxOnly(_ maxDeg: Double) -> (SlopeLevel, String) {
        if maxDeg >= 15 { return (.warning, "HEADING REQUIRED") }
        if maxDeg > 10 { return (.warning, "CAUTION") }
        return (.safe, "LANDING")
    }

    /// The call the slope tile makes, the web's `slopeCallFor` (`feature/terrain/slopeCall.js`), from the analysis's steepest slope
    /// (`maxDeg`, nil when nothing was measured) and its table by heading (`byHeading`, nil from a server before it), the flight data's
    /// landing heading (`landingHeading`, `Doghouses.landingHeading`) and the wind (`wind`, `slopeWindOf`'s, nil for no report):
    ///
    /// NO DATA with nothing measured; LIMIT EXCEEDED when a slope at the landing heading is over its limit for the wind; else CAUTION
    /// above 10° of slope anywhere, and LANDING. With no heading to judge (a server that sends no table, or no landing heading) the
    /// steepest slope alone, HEADING REQUIRED at 15° or more, and a line that says why. Everything is worked out from the table, so a
    /// turned doghouse or a new weather report judges again with no request.
    public static func slopeCall(_ maxDeg: Double?, _ byHeading: [HeadingSlope]?, _ landingHeading: Int?, _ wind: SlopeWind?) -> SlopeCall {
        let limits = limitsFor(wind)
        guard let maxDeg else { return SlopeCall(.safe, "NO DATA", 0, limits: limits) }
        guard let table = tableOf(byHeading) else {
            let (level, label) = byMaxOnly(maxDeg)
            return SlopeCall(level, label, maxDeg, limits: limits, lines: [headingsNotChecked])
        }
        guard let heading = landingHeading.map(onCompass) else {
            let (level, label) = byMaxOnly(maxDeg)
            return SlopeCall(level, label, maxDeg, limits: limits, lines: [noLandingHeading])
        }
        let (level, label): (SlopeLevel, String) =
            if !withinLimits(table[heading], limits) {
                (.danger, "LIMIT EXCEEDED")
            } else if maxDeg > 10 {
                (.warning, "CAUTION")
            } else {
                (.safe, "LANDING")
            }
        return SlopeCall(
            level, label, maxDeg, heading: heading, limits: limits,
            lines: [landingWords(heading, table[heading], limits), windWords(wind, limits)])
    }
}
