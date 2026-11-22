import CoreGeo
import CoreModel

/// Bringing the routes of an AMPS mission into the app's own route sets.
///
/// The routes become **sketched routes**, a copy: the points, the plan the mission recorded (altitudes, airspeeds, winds, clock times)
/// and the ground elevations come across, and the file itself is not kept. What only AMPS knows (the vehicle model, anything in the
/// mission that is not a route) stays in the file, and exporting a set builds a new mission from its routes. The web keeps an imported
/// mission as the file and writes edits back into it; that round trip is a separate, larger piece of work, and this is not a pretence
/// of it.
public enum MissionRoutes {
    /// Where a set of routes lies: its middle and how far across it is, for taking the map there.
    public struct Extent: Equatable, Sendable {
        public var center: LatLon
        public var spanMeters: Double

        public init(_ center: LatLon, _ spanMeters: Double) {
            self.center = center
            self.spanMeters = spanMeters
        }
    }

    /// The mission's routes as sketched routes, each in the first palette colour not already taken (by `takenColors` or by one made
    /// here). A route with no points has nothing to draw and is left out; a blank name becomes `ROUTE n` by its place in the mission.
    /// `newId` names each route (the web's `sketch-…`).
    public static func toSketchRoutes(_ mission: Mission, newId: () -> String, takenColors: [String] = []) -> [SketchRoute] {
        var taken = takenColors
        var routes: [SketchRoute] = []
        for (index, route) in mission.routes.enumerated() where !route.points.isEmpty {
            let color = RouteColors.next(taken)
            taken.append(color)
            routes.append(
                SketchRoute(
                    id: newId(), name: nameOf(route, index), color: color, points: route.points, plan: route.plan,
                    elevations: route.elevations))
        }
        return routes
    }

    /// The routes of a **saved mission**, which keeps the file: every route, in the file's order, with its segment, because a change
    /// to one is found in the file by its place and its segment (so a route with no points is kept, though there is nothing to draw).
    /// A colour is the one the mission's summary kept for that place, else the first of the palette not taken. A blank name is
    /// `ROUTE n`.
    public static func toMissionSketchRoutes(_ mission: Mission, colors: [String?], newId: () -> String) -> [SketchRoute] {
        var taken: [String] = []
        return mission.routes.enumerated().map { index, route in
            let color = (index < colors.count ? colors[index] : nil) ?? RouteColors.next(taken)
            taken.append(color)
            return SketchRoute(
                id: newId(), name: nameOf(route, index), color: color, points: route.points, plan: route.plan,
                elevations: route.elevations, segmentId: route.segmentId)
        }
    }

    private static func nameOf(_ route: MissionRoute, _ index: Int) -> String {
        let name = kotlinTrim(route.name)
        return name.isEmpty ? "ROUTE \(index + 1)" : name
    }

    /// What a mission's file is told about its routes: each route as it stands. The name is the file's own and is not written back.
    public static func toMissionRoutes(_ routes: [SketchRoute]) -> [MissionRoute] {
        routes.map {
            MissionRoute(name: $0.name, segmentId: $0.segmentId, points: $0.points, plan: $0.plan, elevations: $0.elevations)
        }
    }

    /// True when two lists of routes would write the same file: a name, a colour or a hidden route is not in it.
    public static func sameFileContent(_ a: [MissionRoute], _ b: [MissionRoute]) -> Bool {
        a.count == b.count
            && a.indices.allSatisfy { i in
                same(a[i].segmentId, b[i].segmentId) && a[i].points == b[i].points && a[i].plan == b[i].plan
                    && a[i].elevations == b[i].elevations
            }
    }

    /// How many designated (named) route points the routes have in all, which is what a crew counts: shaping points only bend the
    /// line.
    public static func namedPoints(_ routes: [SketchRoute]) -> Int {
        routes.reduce(0) { sum, r in sum + r.points.count { same($0.kind, RoutePoint.kindAmps) } }
    }

    /// The middle of every point of `routes` and the longer side of the box round them, or nil when there is no point.
    public static func extentOf(_ routes: [SketchRoute]) -> Extent? {
        let points = routes.flatMap(\.points)
        guard let first = points.first else { return nil }
        // Kotlin's `minOf` and `maxOf` over doubles are Java's `Math.min` and `max`, in which NaN wins.
        var minLat = first.lat
        var maxLat = first.lat
        var minLon = first.lon
        var maxLon = first.lon
        for p in points.dropFirst() {
            minLat = -javaMax(-minLat, -p.lat)
            maxLat = javaMax(maxLat, p.lat)
            minLon = -javaMax(-minLon, -p.lon)
            maxLon = javaMax(maxLon, p.lon)
        }
        let north = GreatCircle.distanceFeet(minLat, minLon, maxLat, minLon)
        let east = GreatCircle.distanceFeet((minLat + maxLat) / 2, minLon, (minLat + maxLat) / 2, maxLon)
        return Extent(LatLon(lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2), javaMax(north, east) / Units.metersToFeet)
    }
}
