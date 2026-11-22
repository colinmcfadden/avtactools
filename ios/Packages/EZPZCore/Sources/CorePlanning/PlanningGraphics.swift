import CoreGeo
import CoreJS
import CoreModel
import Foundation

/// The planning graphics' geometry and placement: where a new aircraft, PZ marker, sector of fire or go-around goes, which aircraft
/// are too close and what is said about it, and the lines between rotor edges. Ported from `useHelicopters.js`, `usePzMarker.js`,
/// `useSectorsOfFire.js`, `useGoAround.js` and `utils/Helpers.js`, and held to `contracts/fixtures/planning/graphics.json`.
///
/// Graphics are saved as the web's JSON objects, which carry fields this code does not know, so what is made here is a `JSONObject`
/// and an existing graphic is read through a small view (`Aircraft`) rather than converted: an edit goes back through `DiagramOps`,
/// which keeps every other field as it was.
public enum PlanningGraphics {
    // MARK: Geometry

    /// The line between two aircraft's rotor tips, and the gap along it. `edgeDistFt` is 0 when the rotors touch or overlap.
    public struct RotorEdge: Hashable, Sendable {
        public var start: LatLon
        public var end: LatLon
        public var edgeDistFt: Double

        public init(start: LatLon, end: LatLon, edgeDistFt: Double) {
            self.start = start
            self.end = end
            self.edgeDistFt = edgeDistFt
        }
    }

    /// Each end of the line from one aircraft to the other is pulled in by *that* aircraft's own rotor radius, so a Chinook beside a
    /// Little Bird is measured tip to tip. Radii default to the UH-60's, and the second to the first. Overlapping discs give no gap and
    /// a line that is not inverted (`getRotorEdgeCoords`).
    public static func rotorEdge(_ from: LatLon, _ to: LatLon, _ radius1Ft: Double? = nil, _ radius2Ft: Double? = nil) -> RotorEdge {
        let first = radius1Ft ?? AircraftGeometry.rotorRadiusFt(.fallback)
        let second = radius2Ft ?? first
        let centerDist = GreatCircle.distanceFeet(from.lat, from.lon, to.lat, to.lon)
        if centerDist <= 0 { return RotorEdge(start: from, end: to, edgeDistFt: 0) }

        let fraction1 = first / centerDist
        let fraction2 = second / centerDist
        if fraction1 + fraction2 >= 1 { return RotorEdge(start: from, end: to, edgeDistFt: 0) }

        return RotorEdge(
            start: LatLon(lat: from.lat + fraction1 * (to.lat - from.lat), lon: from.lon + fraction1 * (to.lon - from.lon)),
            end: LatLon(lat: to.lat - fraction2 * (to.lat - from.lat), lon: to.lon - fraction2 * (to.lon - from.lon)),
            edgeDistFt: centerDist - (first + second))
    }

    /// The heading, in degrees, a graphic at the centre is turned to when its rotate handle is at the pointer (`calculateAngle`): 0 is
    /// up the screen.
    public static func angleDeg(_ center: LatLon, _ pointer: LatLon) -> Double {
        atan2(pointer.lat - center.lat, pointer.lon - center.lon) * (180 / Double.pi) + 90
    }

    /// Where a graphic's rotate handle sits, 0.0001 degrees from its centre in the direction it is turned (`calculateHandlePos`).
    public static func handlePosition(_ center: LatLon, _ rotationDeg: Double) -> LatLon {
        let offset = 0.0001
        let angle = (rotationDeg - 90) * (Double.pi / 180)
        return LatLon(lat: center.lat - offset * sin(angle), lon: center.lon + offset * cos(angle))
    }

    // MARK: Aircraft

    /// A placed aircraft as the geometry sees it. `id` is the saved id as it is (the web's are numbers made from the clock, or text).
    public struct Aircraft: Equatable, Sendable {
        public var id: JSONTree
        public var at: LatLon
        public var profileRef: String?

        public init(id: JSONTree, at: LatLon, profileRef: String?) {
            self.id = id
            self.at = at
            self.profileRef = profileRef
        }

        /// Reads a saved helicopter. One with no position cannot be measured and is left out.
        public static func of(_ saved: JSONTree) -> Aircraft? {
            guard let o = saved.objectValue, let lat = finiteNumber(o["lat"]), let lon = finiteNumber(o["lon"]) else { return nil }
            return Aircraft(id: o["id"] ?? .null, at: LatLon(lat: lat, lon: lon), profileRef: primitiveText(o["profileId"]))
        }
    }

    /// Where a new aircraft goes: the target, nudged east in steps of 0.0001 degrees until it is clear of every aircraft already down
    /// by the separation each pair actually requires, giving up after 50 nudges. It records `active`'s slug as its profile, so it
    /// keeps its identity across databases (`placeHelicopter`).
    public static func placeHelicopter(
        _ target: LatLon, _ existing: [Aircraft], _ active: AircraftProfile, _ resolve: (Aircraft) -> AircraftProfile, id: JSONTree
    ) -> JSONObject {
        let lat = target.lat
        var lon = target.lon
        var clear = false
        var attempts = 0
        let step = 0.0001
        while !clear && attempts < maxNudges {
            clear = true
            for other in existing {
                let distance = GreatCircle.distanceFeet(lat, lon, other.at.lat, other.at.lon)
                if distance < AircraftGeometry.pairSeparation(active, resolve(other)).minCenterDistanceFt {
                    clear = false
                    lon += step
                    break
                }
            }
            attempts += 1
        }
        return JSONObject([
            ("id", id), ("lat", .number(lat)), ("lon", .number(lon)), ("rotation", .number(0)), ("type", .string("helo")),
            ("profileId", .string(active.slug.isEmpty ? AircraftProfile.fallback.slug : active.slug)),
        ])
    }

    private static let maxNudges = 50

    /// Two aircraft whose rotor tips are closer than the stricter of their clearances. `id` is the pair's, as the web names it.
    public struct SeparationAlert: Equatable, Sendable {
        public var id: String
        public var first: JSONTree
        public var second: JSONTree
        public var gapFt: Double
        public var requiredFt: Double
        public var message: String

        public init(id: String, first: JSONTree, second: JSONTree, gapFt: Double, requiredFt: Double, message: String) {
            self.id = id
            self.first = first
            self.second = second
            self.gapFt = gapFt
            self.requiredFt = requiredFt
            self.message = message
        }
    }

    /// Every such pair, in the order the pairs are met (each aircraft against those after it), with the words shown for it.
    public static func separationAlerts(_ aircraft: [Aircraft], _ resolve: (Aircraft) -> AircraftProfile) -> [SeparationAlert] {
        var alerts: [SeparationAlert] = []
        for i in aircraft.indices {
            for j in aircraft.indices where j > i {
                let a = aircraft[i]
                let b = aircraft[j]
                let profileA = resolve(a)
                let profileB = resolve(b)
                let centerDistance = GreatCircle.distanceFeet(a.at.lat, a.at.lon, b.at.lat, b.at.lon)
                let gap = AircraftGeometry.edgeGapFt(centerDistance, profileA, profileB)
                let required = AircraftGeometry.pairSeparation(profileA, profileB).requiredClearanceFt
                if gap < required {
                    let shown = kotlinLong(javaMax(0, RouteCalc.jsRound(gap)))
                    let pair = same(profileA.slug, profileB.slug) ? profileA.designation : "\(profileA.designation)/\(profileB.designation)"
                    let metres = kotlinLong(RouteCalc.jsRound(javaMax(profileA.rotorTipClearanceM, profileB.rotorTipClearanceM)))
                    let minimum = kotlinLong(RouteCalc.jsRound(required))
                    alerts.append(
                        SeparationAlert(
                            id: "\(DiagramOps.idText(a.id))-\(DiagramOps.idText(b.id))", first: a.id, second: b.id, gapFt: gap,
                            requiredFt: required,
                            message:
                                "Separation Alert (\(pair)): Rotor edges are only \(shown) ft apart (Min: \(minimum) ft / \(metres) m)."))
                }
            }
        }
        return alerts
    }

    // MARK: The other graphics

    /// A PZ marker at the target, with its tip 0.002 degrees (about 160 m) west: the marker is a line from the centre to the tip, and
    /// its length and direction are what the crew drags (`createPzMarker`). Nil for a target that is not a position, where the web
    /// would make a marker whose fields are NaN.
    public static func createPzMarker(_ lat: Double, _ lon: Double, id: String) -> JSONObject? {
        if !lat.isFinite || !lon.isFinite { return nil }
        return JSONObject([
            ("id", .string(id)), ("lat", .number(lat)), ("lon", .number(lon)), ("tipLat", .number(lat)), ("tipLon", .number(lon - 0.002)),
        ])
    }

    /// A sector of fire: a triangle about 110 m (0.001 degrees) across, pointing north from the target (`createSectorOfFire`). Points
    /// are `{lat, lng}`, as the web saves them.
    public static func createSectorOfFire(_ lat: Double, _ lon: Double, id: String) -> JSONObject? {
        if !lat.isFinite || !lon.isFinite { return nil }
        let offset = 0.001
        func point(_ la: Double, _ lo: Double) -> JSONTree { .object(JSONObject([("lat", .number(la)), ("lng", .number(lo))])) }
        return JSONObject([
            ("id", .string(id)),
            ("points", .array([point(lat + offset, lon), point(lat - offset, lon + offset), point(lat - offset, lon - offset)])),
        ])
    }

    /// A go-around arrow 0.001 degrees (about 110 m) from the target (`createGoAround`). **Quirk kept on purpose:** the web puts it
    /// north only for a direction spelled "N", which nothing sends: the two it does send, "left" and "right", both start south.
    public static func createGoAround(_ lat: Double, _ lon: Double, _ direction: String?, id: String) -> JSONObject? {
        if !lat.isFinite || !lon.isFinite { return nil }
        let offset = 0.001
        return JSONObject([
            ("id", .string(id)), ("lat", .number(same(direction, "N") ? lat + offset : lat - offset)), ("lon", .number(lon)),
            ("direction", direction.map(JSONTree.string) ?? .null), ("rotation", .number(0)),
        ])
    }
}
