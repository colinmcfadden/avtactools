import CoreJS
import Foundation

/// The slope profile: the ground along the landing heading through the LZ (the approach, the LZ and the departure) and across it, from
/// the lines the server measured through the LZ's middle (`profiles` of /api/terrain-analysis). The web's
/// `feature/terrain/groundProfile.js`, held to `contracts/fixtures/planning/profile.json`. Turning the landing doghouse picks another
/// line, with no new request.
public enum SlopeProfile {
    private static let feetPerMetre = 3.28084

    /// Under this many feet of rise or fall through the LZ, it is called level.
    private static let levelFt: Int64 = 1

    /// One line the server measured, at `heading` (0..179): its heights in feet, nil where the terrain has none, from behind the
    /// middle to ahead of it, and where it crosses the boundary (`lz`, metres from the middle, two numbers) or nil.
    public struct GroundLine: Equatable, Sendable {
        public var heading: Int
        public var elevationsFt: [Double?]
        public var lz: [Double]?

        public init(heading: Int, elevationsFt: [Double?], lz: [Double]?) {
            self.heading = heading
            self.elevationsFt = elevationsFt
            self.lz = lz
        }
    }

    /// The server's lines: heights every `stepM`, one line every `headingStepDeg`.
    public struct Ground: Equatable, Sendable {
        public var stepM: Double
        public var halfLengthM: Double
        public var headingStepDeg: Int
        public var lines: [GroundLine]

        public init(stepM: Double, halfLengthM: Double, headingStepDeg: Int, lines: [GroundLine]) {
            self.stepM = stepM
            self.halfLengthM = halfLengthM
            self.headingStepDeg = headingStepDeg
            self.lines = lines
        }
    }

    /// The ground along one heading: `heading` (0..359, the line's own), `distancesFt` (behind the middle negative), `elevationsFt`
    /// (nil where there is none), `lz` in feet along it or nil, and `changeFt` / `averageDeg`: how much the ground rises (positive) or
    /// falls through the LZ and the slope that makes on average, nil when an edge has no height. `words` is what it says in a
    /// sentence (`slopeProfileFor` sets it).
    public struct Line: Equatable, Sendable {
        public var heading: Int
        public var distancesFt: [Double]
        public var elevationsFt: [Double?]
        public var lz: [Double]?
        public var changeFt: Double?
        public var averageDeg: Double?
        public var words: String

        public init(
            heading: Int, distancesFt: [Double], elevationsFt: [Double?], lz: [Double]?, changeFt: Double?, averageDeg: Double?,
            words: String = ""
        ) {
            self.heading = heading
            self.distancesFt = distancesFt
            self.elevationsFt = elevationsFt
            self.lz = lz
            self.changeFt = changeFt
            self.averageDeg = averageDeg
            self.words = words
        }

        // By hand, as a Kotlin data class compares its text: unit for unit.
        public static func == (a: Line, b: Line) -> Bool {
            a.heading == b.heading && a.distancesFt == b.distancesFt && a.elevationsFt == b.elevationsFt && a.lz == b.lz
                && a.changeFt == b.changeFt && a.averageDeg == b.averageDeg && same(a.words, b.words)
        }
    }

    /// The profile along the landing heading (approach to departure) and across it (left to right).
    public struct Profile: Equatable, Sendable {
        public var along: Line
        public var across: Line

        public init(_ along: Line, _ across: Line) {
            self.along = along
            self.across = across
        }
    }

    private static func readable(_ ground: Ground?) -> Ground? {
        guard let g = ground else { return nil }
        if !g.stepM.isFinite || g.stepM <= 0 || !g.halfLengthM.isFinite || g.headingStepDeg <= 0 || g.lines.isEmpty { return nil }
        return g
    }

    private static func heightAt(_ elevations: [Double?], _ stepM: Double, _ halfM: Double, _ atM: Double) -> Double? {
        let at = (atM + halfM) / stepM
        let i = kotlinInt(floor(at))
        if i < 0 || i >= elevations.count { return nil }
        if i == elevations.count - 1 { return elevations[i] }
        guard let a = elevations[i], let b = elevations[i + 1] else { return nil }
        return a + (b - a) * (at - Double(i))
    }

    /// The ground along the line through the LZ's middle nearest `heading` (any whole number of degrees): the line `headingStepDeg`
    /// apart nearest it (halves up; 180 or more is the opposite line, read backwards). Nil when that line is not there or `ground`
    /// cannot be read.
    public static func profileLine(_ ground: Ground?, _ heading: Int) -> Line? {
        guard let g = readable(ground) else { return nil }
        let step = g.headingStepDeg
        let nearest = ((kotlinInt(RouteCalc.jsRound(Double(heading) / Double(step)) * Double(step)) % 360) + 360) % 360
        let reversed = nearest >= 180
        let base = reversed ? nearest - 180 : nearest
        guard let line = g.lines.first(where: { $0.heading == base }) else { return nil }
        if line.elevationsFt.count < 2 { return nil }
        let heights = line.elevationsFt.map { h in h.flatMap { $0.isFinite ? $0 : nil } }
        let elevations = reversed ? Array(heights.reversed()) : heights
        let half = g.stepM * Double(elevations.count - 1) / 2.0
        let distancesFt = elevations.indices.map { i in (Double(i) * g.stepM - half) * feetPerMetre }
        let span = line.lz.flatMap { $0.count == 2 && $0.allSatisfy(\.isFinite) ? $0 : nil }
        let lzM = span.map { reversed ? [-$0[1], -$0[0]] : [$0[0], $0[1]] }
        var change: Double?
        var average: Double?
        if let lzM, lzM[1] > lzM[0] {
            let entry = heightAt(elevations, g.stepM, half, lzM[0])
            let exit = heightAt(elevations, g.stepM, half, lzM[1])
            if let entry, let exit {
                let rise = exit - entry
                change = rise
                average = toDegrees(atan2(abs(rise), (lzM[1] - lzM[0]) * feetPerMetre))
            }
        }
        return Line(
            heading: nearest, distancesFt: distancesFt, elevationsFt: elevations, lz: lzM?.map { $0 * feetPerMetre }, changeFt: change,
            averageDeg: average)
    }

    // Java's Math.toDegrees multiplies by 180/π to the nearest double, which is what this division gives.
    private static func toDegrees(_ radians: Double) -> Double { radians * (180 / Double.pi) }

    private static func changeWords(_ line: Line) -> String {
        if line.lz == nil { return "the line misses the LZ" }
        guard let change = line.changeFt else { return "no height at an edge of the LZ" }
        let feet = kotlinLong(RouteCalc.jsRound(abs(change)))
        if feet < levelFt { return "level through the LZ" }
        return "\(change > 0 ? "rises" : "falls") \(feet) ft through the LZ, \(JsNumber.toFixed(line.averageDeg ?? 0, 1))° on average"
    }

    private static func headingText(_ heading: Int) -> String { "\(padded(String(heading), 3))°" }

    /// The slope profile for a landing heading (`heading`, 0..359, `Doghouses.landingHeading`; nil for none): along it (the approach
    /// behind the middle, the departure ahead) and across it (the line 90° to its right, left to right), each with its sentence. Nil
    /// with no heading, or when either line is not there.
    public static func slopeProfileFor(_ ground: Ground?, _ heading: Int?) -> Profile? {
        guard let heading else { return nil }
        guard let along = profileLine(ground, heading) else { return nil }
        // Kotlin's `heading + 90` is 32-bit and wraps; Swift's would trap past `Int.max`.
        guard let across = profileLine(ground, Int(Int32(truncatingIfNeeded: heading &+ 90))) else { return nil }
        var alongLine = along
        alongLine.words = "Along \(headingText(along.heading)): \(changeWords(along))"
        var acrossLine = across
        acrossLine.words = "Across, left to right: \(changeWords(across))"
        return Profile(alongLine, acrossLine)
    }
}
