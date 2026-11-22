import CoreGeo
import CoreJS
import CoreModel

/// A backstop (the owner's, 2026-11-08): a reference for a crew that has overflown an LZ/PZ. A dashed line sketched wherever the terrain
/// or a landmark makes it plain, and an arrow from it back to the LZ/PZ, labelled with the azimuth and the distance in km.
///
/// Kept as `{id, points: [{lat, lon}, …], arrow?: {tail: {lat, lon}, tip?: {lat, lon}}}` in the diagram's `graphics.backstops`, opaque
/// JSON as every graphic is, so what a newer web writes survives. The line stays where it was sketched and the arrow is what moves. With
/// no `arrow` it runs from the line's middle to the target, and a tip with no place of its own is on the target and follows it. The label
/// is always worked out from where the arrow is now. The web's `feature/backstops/backstops.js`, held to
/// contracts/fixtures/planning/backstops.json. Android carries backstops but does not draw them (AGENTS.md §15); iOS follows the web.
public enum Backstops {
    static let feetPerKm = 3280.84

    /// A tip dropped this near the target (screen points) goes back onto it, and an arrow is never made shorter than this, so it can
    /// still be picked up.
    public static let targetSnapPoints: Double = 20
    public static let minArrowPoints: Double = 24

    /// Where the arrow runs now. `onTarget` when it has no tip of its own and follows the target.
    public struct Arrow: Equatable, Sendable {
        public let tail: LatLon
        public let tip: LatLon
        public let onTarget: Bool
    }

    /// The arrow's azimuth (whole degrees true, 0 to 359), its length, and the label's words (`186°T · 1.8 km`).
    public struct Reading: Equatable, Sendable {
        public let azimuth: Int
        public let distanceKm: Double
        public let text: String
    }

    /// What an aimed arrow keeps: its tail, and a tip of its own unless it went back onto the target.
    public struct Aimed: Equatable, Sendable {
        public let tail: LatLon
        public let tip: LatLon?
    }

    /// `Number.isFinite(point?.lat) && Number.isFinite(point?.lon)`: only JSON numbers, never text that reads as one.
    static func place(_ point: JSONTree?) -> LatLon? {
        guard case .number(let lat)? = Js.prop(point, "lat"), case .number(let lon)? = Js.prop(point, "lon"),
            lat.value.isFinite, lon.value.isFinite
        else { return nil }
        return LatLon(lat: lat.value, lon: lon.value)
    }

    /// The web's `placeOf(target)`: a diagram's `[lat, lon]`, read with `Number()`, or nothing.
    static func targetPlace(_ target: JSONTree?) -> LatLon? {
        guard Js.truthy(target) else { return nil }
        let lat = JsNumber.of(Js.prop(target, "0"))
        let lon = JsNumber.of(Js.prop(target, "1"))
        return lat.isFinite && lon.isFinite ? LatLon(lat: lat, lon: lon) : nil
    }

    /// The drawn points that are places.
    public static func points(_ backstop: JSONTree?) -> [LatLon] {
        guard case .array(let list)? = Js.prop(backstop, "points") else { return [] }
        return list.compactMap(place)
    }

    /// A new backstop's document, `{id, points}`.
    public static func create(points: [LatLon], id: String) -> JSONTree {
        .object([
            "id": .string(id),
            "points": .array(points.map { .object(["lat": .number($0.lat), "lon": .number($0.lon)]) }),
        ])
    }

    /// The middle of the line, by distance along it: where its arrow starts until it is moved.
    public static func anchor(_ points: [LatLon]) -> LatLon? {
        guard let first = points.first else { return nil }
        let legs = zip(points, points.dropFirst()).map { GreatCircle.distanceFeet($0.lat, $0.lon, $1.lat, $1.lon) }
        // `reduce` from 0 in order, as the web's: the half is the same double.
        var toGo = legs.reduce(0, +) / 2
        for i in legs.indices {
            if legs[i] > 0, toGo <= legs[i] {
                let along = toGo / legs[i]
                let (a, b) = (points[i], points[i + 1])
                return LatLon(lat: a.lat + (b.lat - a.lat) * along, lon: a.lon + (b.lon - a.lon) * along)
            }
            toGo -= legs[i]
        }
        return first
    }

    /// Where the arrow runs now, for a target of `[lat, lon]`. Nil when it has nowhere to start or nothing to point at.
    public static func arrow(_ backstop: JSONTree?, target: JSONTree?) -> Arrow? {
        let saved = Js.prop(backstop, "arrow")
        guard let tail = place(Js.prop(saved, "tail")) ?? anchor(points(backstop)) else { return nil }
        let ownTip = place(Js.prop(saved, "tip"))
        guard let tip = ownTip ?? targetPlace(target) else { return nil }
        return Arrow(tail: tail, tip: tip, onTarget: ownTip == nil)
    }

    /// The arrow's azimuth and length, and the label's words. Nil for no arrow.
    public static func reading(_ arrow: Arrow?) -> Reading? {
        guard let arrow else { return nil }
        let (tail, tip) = (arrow.tail, arrow.tip)
        // `calculateBearing` rounds `(bearing + 360) % 360`, so 359.5 and up is 360; the web then takes 360 as 0.
        let azimuth = Int(Js.round(GreatCircle.trueCourseDeg(tail.lat, tail.lon, tip.lat, tip.lon))) % 360
        let distanceKm = GreatCircle.distanceFeet(tail.lat, tail.lon, tip.lat, tip.lon) / feetPerKm
        let digits = String(azimuth)
        let text = String(repeating: "0", count: max(0, 3 - digits.count)) + digits + "°T · " + JsNumber.toFixed(distanceKm, 1) + " km"
        return Reading(azimuth: azimuth, distanceKm: distanceKm, text: text)
    }

    /// The arrow moved whole, by degrees of latitude and longitude. Its tip goes with it, off the target if it was on it.
    public static func move(_ arrow: Arrow, dLat: Double, dLon: Double) -> (tail: LatLon, tip: LatLon) {
        func moved(_ p: LatLon) -> LatLon { LatLon(lat: p.lat + dLat, lon: p.lon + dLon) }
        return (moved(arrow.tail), moved(arrow.tip))
    }

    /// The arrow with its tip dragged to `at`, its tail staying. Nil when that would leave it too short to pick up again.
    /// `pointsTo(place)` is how far a place is from `at` on the screen.
    public static func aim(_ arrow: Arrow, at: LatLon, target: JSONTree?, pointsTo: (LatLon) -> Double) -> Aimed? {
        if pointsTo(arrow.tail) < minArrowPoints { return nil }
        if let onTarget = targetPlace(target), pointsTo(onTarget) <= targetSnapPoints { return Aimed(tail: arrow.tail, tip: nil) }
        return Aimed(tail: arrow.tail, tip: at)
    }
}
