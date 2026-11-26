import CoreJS
import CoreModel
import Foundation

/// What a person does to a planning graphic with the inspector: nudge it, put it at the crosshair, turn it, set how far a PZ marker
/// reaches. (On the web this is dragging; on a phone in a cockpit, with gloves, it is the more reliable way, and the map's own drag
/// joins it later.)
///
/// Each edit answers with a *patch*: only the fields it changed, as JSON, to merge into the saved graphic through
/// `DiagramOps.patchGraphic`, so every other field of the graphic (one a newer web release added, say) is left exactly as it was. Nil
/// means the edit does not apply: the graphic has no position, or that kind of graphic has no such thing (a sector has no heading).
///
/// Distances are on the sphere the map draws on (radius 6,378,137 m), so a nudge of 10 m is 10 m on the screen.
public enum GraphicEdits {
    private static let earthRadiusM = 6_378_137.0

    // Java's Math.toDegrees and toRadians multiply by these constants, which are 180/π and π/180 to the nearest double.
    private static func toDegrees(_ radians: Double) -> Double { radians * (180 / Double.pi) }
    private static func toRadians(_ degrees: Double) -> Double { degrees * (Double.pi / 180) }

    /// The point `northM` metres north and `eastM` metres east of `at`.
    public static func offset(_ at: LatLon, northM: Double, eastM: Double) -> LatLon {
        LatLon(
            lat: at.lat + toDegrees(northM / earthRadiusM),
            lon: at.lon + toDegrees(eastM / (earthRadiusM * cos(toRadians(at.lat)))))
    }

    /// Metres north and east from `from` to `to`, on the same flat approximation as `offset` (good over the few hundred metres a
    /// diagram spans).
    public static func metresBetween(_ from: LatLon, _ to: LatLon) -> (north: Double, east: Double) {
        (toRadians(to.lat - from.lat) * earthRadiusM, toRadians(to.lon - from.lon) * earthRadiusM * cos(toRadians(from.lat)))
    }

    private static func num(_ value: Double) -> JSONTree { .number(value) }

    /// Where a graphic is: its position, or for a sector the middle of its points. Nil when it has none.
    public static func position(_ collection: String, _ graphic: JSONObject) -> LatLon? {
        switch collection {
        case "sectorsOfFire":
            let p = sectorPoints(graphic)
            guard p.count >= 3 else { return nil }
            return LatLon(lat: p.reduce(0) { $0 + $1.lat } / Double(p.count), lon: p.reduce(0) { $0 + $1.lon } / Double(p.count))
        case "helicopters", "pzMarkers", "goArounds", "doghouses", "units":
            guard let lat = finiteNumber(graphic["lat"]), let lon = finiteNumber(graphic["lon"]) else { return nil }
            return LatLon(lat: lat, lon: lon)
        default:
            return nil
        }
    }

    private static func sectorPoints(_ graphic: JSONObject) -> [LatLon] {
        (graphic["points"]?.arrayValue?.elements ?? []).compactMap { p in
            guard let o = p.objectValue, let lat = finiteNumber(o["lat"]), let lon = finiteNumber(o["lng"]) else { return nil }
            return LatLon(lat: lat, lon: lon)
        }
    }

    /// The patch that puts a graphic at `to`. A PZ marker's tip and a sector's points go with it, keeping their shape.
    public static func moveTo(_ collection: String, _ graphic: JSONObject, _ to: LatLon) -> JSONObject? {
        guard let from = position(collection, graphic) else { return nil }
        let dLat = to.lat - from.lat
        let dLon = to.lon - from.lon
        switch collection {
        case "pzMarkers":
            let tipLat = finiteNumber(graphic["tipLat"]) ?? from.lat
            let tipLon = finiteNumber(graphic["tipLon"]) ?? (from.lon - 0.001)  // where the web draws a marker that has no tip
            return JSONObject([("lat", num(to.lat)), ("lon", num(to.lon)), ("tipLat", num(tipLat + dLat)), ("tipLon", num(tipLon + dLon))])
        case "sectorsOfFire":
            let moved = sectorPoints(graphic).map { p in
                JSONTree.object(JSONObject([("lat", num(p.lat + dLat)), ("lng", num(p.lon + dLon))]))
            }
            return JSONObject([("points", .array(moved))])
        default:
            return JSONObject([("lat", num(to.lat)), ("lon", num(to.lon))])
        }
    }

    /// The patch that moves a graphic `northM` metres north and `eastM` east.
    public static func nudge(_ collection: String, _ graphic: JSONObject, northM: Double, eastM: Double) -> JSONObject? {
        guard let from = position(collection, graphic) else { return nil }
        return moveTo(collection, graphic, offset(from, northM: northM, eastM: eastM))
    }

    // MARK: Turning

    /// `degrees` in `[0, 360)`.
    public static func normalizeDegrees(_ degrees: Double) -> Double {
        (degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The way a graphic points: an aircraft's or a go-around's `rotation`, a doghouse's heading, or the bearing of a PZ marker's tip
    /// from its anchor. Nil for what does not turn.
    public static func rotation(_ collection: String, _ graphic: JSONObject) -> Double? {
        switch collection {
        case "helicopters", "goArounds": normalizeDegrees(finiteNumber(graphic["rotation"]) ?? 0)
        case "doghouses": normalizeDegrees(Doghouses.rotation(graphic))
        case "pzMarkers": pzVector(graphic).map { normalizeDegrees(toDegrees(atan2($0.east, $0.north))) }
        default: nil
        }
    }

    /// The patch that points a graphic at `degrees` (clockwise from north). A PZ marker keeps its reach and swings its tip round its
    /// anchor.
    public static func setRotation(_ collection: String, _ graphic: JSONObject, _ degrees: Double) -> JSONObject? {
        switch collection {
        case "helicopters", "goArounds":
            return JSONObject([("rotation", num(normalizeDegrees(degrees)))])
        case "doghouses":
            // A doghouse keeps its heading as the web writes it ("270°"), to the whole degree, so it is also the flight data's heading.
            let heading = RouteCalc.jsRound(normalizeDegrees(degrees)).truncatingRemainder(dividingBy: 360)
            return JSONObject([("heading", .string(Doghouses.headingText(heading)))])
        case "pzMarkers":
            guard let vector = pzVector(graphic) else { return nil }
            return setPz(graphic, hypot(vector.north, vector.east), degrees)
        default:
            return nil
        }
    }

    /// The patch that turns a graphic by `delta` degrees, clockwise.
    public static func rotateBy(_ collection: String, _ graphic: JSONObject, _ delta: Double) -> JSONObject? {
        rotation(collection, graphic).flatMap { setRotation(collection, graphic, $0 + delta) }
    }

    // MARK: A PZ marker's reach

    /// Metres from a PZ marker's anchor to its tip. Nil for anything else.
    public static func pzReachM(_ graphic: JSONObject) -> Double? { pzVector(graphic).map { hypot($0.north, $0.east) } }

    /// The patch that sets how far a PZ marker reaches, keeping its bearing. A marker whose tip is on its anchor has no bearing and
    /// points north.
    public static func setPzReach(_ graphic: JSONObject, _ meters: Double) -> JSONObject? {
        guard let vector = pzVector(graphic) else { return nil }
        let bearing = hypot(vector.north, vector.east) == 0 ? 0 : toDegrees(atan2(vector.east, vector.north))
        // `coerceAtLeast(0.0)`: NaN is not below 0, so it stays NaN.
        return setPz(graphic, meters < 0 ? 0 : meters, bearing)
    }

    /// The patch that puts a PZ marker's tip at `tip`, wherever that is (the crosshair, say), keeping its anchor.
    public static func setPzTip(_ graphic: JSONObject, _ tip: LatLon) -> JSONObject? {
        if position("pzMarkers", graphic) == nil { return nil }
        return JSONObject([("tipLat", num(tip.lat)), ("tipLon", num(tip.lon))])
    }

    /// Where a PZ marker's arrow ends (a marker with no tip is drawn pointing west of its anchor). Nil for anything that is not one.
    public static func pzTip(_ graphic: JSONObject) -> LatLon? {
        guard let anchor = position("pzMarkers", graphic) else { return nil }
        return LatLon(lat: finiteNumber(graphic["tipLat"]) ?? anchor.lat, lon: finiteNumber(graphic["tipLon"]) ?? (anchor.lon - 0.001))
    }

    /// The tip's offset from the anchor, metres north and east.
    private static func pzVector(_ graphic: JSONObject) -> (north: Double, east: Double)? {
        guard let anchor = position("pzMarkers", graphic) else { return nil }
        let tip = LatLon(lat: finiteNumber(graphic["tipLat"]) ?? anchor.lat, lon: finiteNumber(graphic["tipLon"]) ?? (anchor.lon - 0.001))
        return metresBetween(anchor, tip)
    }

    private static func setPz(_ graphic: JSONObject, _ reachM: Double, _ bearingDeg: Double) -> JSONObject? {
        guard let anchor = position("pzMarkers", graphic) else { return nil }
        let bearing = toRadians(normalizeDegrees(bearingDeg))
        let tip = offset(anchor, northM: reachM * cos(bearing), eastM: reachM * sin(bearing))
        return JSONObject([("tipLat", num(tip.lat)), ("tipLon", num(tip.lon))])
    }

    /// The patch that points a go-around the other way round the pattern: `"left"` or `"right"`.
    public static func setGoAroundDirection(_ graphic: JSONObject, _ direction: String) -> JSONObject? {
        same(direction, "left") || same(direction, "right") ? JSONObject([("direction", .string(direction))]) : nil
    }
}
