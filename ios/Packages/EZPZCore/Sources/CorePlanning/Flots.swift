import CoreJS
import CoreModel
import Foundation

/// A point on the screen, in points with y down (the web's CSS pixels).
public struct ScreenPoint: Equatable, Sendable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

/// A box on the screen, `minX…maxX` by `minY…maxY`.
public struct ScreenBox: Equatable, Sendable {
    public var minX: Double
    public var minY: Double
    public var maxX: Double
    public var maxY: Double

    public init(minX: Double, minY: Double, maxX: Double, maxY: Double) {
        (self.minX, self.minY, self.maxX, self.maxY) = (minX, minY, maxX, maxY)
    }
}

/// The FLOT, the forward line of own troops (the owner's, 2026-11-08): a line of semicircles drawn across the map, kept with a set of
/// routes beside its routes (`flots` of the set's document) as `{id, points: [{lat, lon}, …]}`, with `flipped: true` when its scallops
/// face the other way. Scallops are the size of the screen's, not the ground's, so they are worked out for the zoom in screen points;
/// by default they are to the right of the direction the line was drawn in. The web's `feature/flot/flots.js`, held to
/// contracts/fixtures/planning/controlMeasures.json.
public enum Flots {
    /// Across one scallop, in screen points.
    public static let scallopPoints: Double = 18
    static let arcSteps = 8

    /// A new FLOT's document.
    public static func create(points: [LatLon], id: String) -> JSONObject {
        JSONObject([("id", .string(id)), ("points", SetLines.plainPoints(points))])
    }

    /// The FLOT with its scallops on the other side. `flipped` is left out unless it is true, so an unflipped FLOT keeps one shape.
    public static func flip(_ flot: JSONObject) -> JSONObject {
        let rest = flot.removing("flipped")
        return Js.truthy(flot["flipped"]) ? rest : rest.setting("flipped", .bool(true))
    }

    static func legs(_ pixels: [ScreenPoint]) -> [Double] {
        zip(pixels, pixels.dropFirst()).map { hypot($1.x - $0.x, $1.y - $0.y) }
    }

    /// A semicircle from `a` to `b`, without its first point. Screen y runs downward, so (-dy, dx) is to the right of a to b.
    static func arc(after a: ScreenPoint, to b: ScreenPoint, flipped: Bool) -> [ScreenPoint] {
        let (cx, cy) = ((a.x + b.x) / 2, (a.y + b.y) / 2)
        let (rx, ry) = (a.x - cx, a.y - cy)
        let side: Double = flipped ? -1 : 1
        let (nx, ny) = (side * ry, side * -rx)
        return (1...arcSteps).map { i in
            let t = (Double(i) / Double(arcSteps)) * Double.pi
            return ScreenPoint(x: cx + rx * cos(t) + nx * sin(t), y: cy + ry * cos(t) + ny * sin(t))
        }
    }

    /// The scallops along a line given in screen points: lists of points, one for each unbroken run. A whole number of scallops as near
    /// `diameter` as fits, each between two places on the line, so they follow it round a corner and the last ends where it ends. With
    /// `within`, a scallop off that box is left out: at a close zoom a long FLOT is tens of thousands of them.
    public static func scallopRuns(
        _ pixels: [ScreenPoint], diameter: Double = scallopPoints, flipped: Bool = false, within: ScreenBox? = nil
    ) -> [[ScreenPoint]] {
        guard pixels.count >= 2 else { return [] }
        let legs = legs(pixels)
        let total = legs.reduce(0, +)
        guard total > 0 else { return [] }
        let count = max(1, Int(Js.round(total / diameter)))
        let chord = total / Double(count)

        var leg = 0
        var start = 0.0
        func place(at distance: Double) -> ScreenPoint {
            while leg < legs.count - 1, distance > start + legs[leg] {
                start += legs[leg]
                leg += 1
            }
            let along = legs[leg] > 0 ? (distance - start) / legs[leg] : 0
            let (a, b) = (pixels[leg], pixels[leg + 1])
            return ScreenPoint(x: a.x + (b.x - a.x) * along, y: a.y + (b.y - a.y) * along)
        }
        func outside(_ p: ScreenPoint) -> Bool {
            guard let box = within else { return false }
            return p.x < box.minX || p.x > box.maxX || p.y < box.minY || p.y > box.maxY
        }

        var runs: [[ScreenPoint]] = []
        var inRun = false
        var from = pixels[0]
        for i in 1...count {
            let to = i == count ? pixels[pixels.count - 1] : place(at: Double(i) * chord)
            if outside(ScreenPoint(x: (from.x + to.x) / 2, y: (from.y + to.y) / 2)) {
                inRun = false
            } else {
                if !inRun {
                    runs.append([from])
                    inRun = true
                }
                runs[runs.count - 1].append(contentsOf: arc(after: from, to: to, flipped: flipped))
            }
            from = to
        }
        return runs
    }

    /// Where the label goes: the middle of the line by distance along it, and the unit vector away from the side its scallops are on.
    public struct LabelSpot: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public let awayX: Double
        public let awayY: Double
    }

    public static func labelSpot(_ pixels: [ScreenPoint], flipped: Bool = false) -> LabelSpot? {
        guard pixels.count >= 2 else { return nil }
        let legs = legs(pixels)
        var toGo = legs.reduce(0, +) / 2
        for i in legs.indices {
            if legs[i] > 0, toGo <= legs[i] {
                let (a, b) = (pixels[i], pixels[i + 1])
                let along = toGo / legs[i]
                let (ux, uy) = ((b.x - a.x) / legs[i], (b.y - a.y) / legs[i])
                let side: Double = flipped ? -1 : 1
                return LabelSpot(x: a.x + (b.x - a.x) * along, y: a.y + (b.y - a.y) * along, awayX: side * uy, awayY: side * -ux)
            }
            toGo -= legs[i]
        }
        return nil
    }
}
