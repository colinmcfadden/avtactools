import CoreModel

/// A corner put down while sketching, with what the person said it is (from the draw-mode menu, or a local point added to the route),
/// if anything.
public struct DraftPoint: Equatable, Sendable {
    public var lat: Double
    public var lon: Double
    public var designation: Designation?

    public init(_ lat: Double, _ lon: Double, _ designation: Designation? = nil) {
        self.lat = lat
        self.lon = lon
        self.designation = designation
    }
}

/// What a point drawn as a named AMPS point says it is. An empty type or name reads as none, as the web's `||` does.
public struct Designation: Equatable, Sendable {
    public var ptType: String?
    public var name: String?
    public var chartElevationFt: Double?

    public init(ptType: String? = nil, name: String? = nil, chartElevationFt: Double? = nil) {
        self.ptType = ptType
        self.name = name
        self.chartElevationFt = chartElevationFt
    }
}

/// What a point is being changed to: `kind` is `amps` or `shaping`. A nil `ptType` or `name` says nothing about it (the point keeps
/// its own).
public struct PointSpec: Equatable, Sendable {
    public var kind: String
    public var ptType: String?
    public var name: String?

    public init(_ kind: String, ptType: String? = nil, name: String? = nil) {
        self.kind = kind
        self.ptType = ptType
        self.name = name
    }
}

/// What the standard attack profile makes a point of a finished sketch.
public struct AutoDesignation: Equatable, Sendable {
    public var ptType: String
    public var name: String

    public init(ptType: String, name: String) {
        self.ptType = ptType
        self.name = name
    }
}

/// The plan settings a screen changes; a nil field is not changed. (The web merges a patch's keys over the plan, and its screens only
/// ever set values.)
public struct PlanPatch: Equatable, Sendable {
    public var aircraft: String?
    public var aircraftProfile: String?
    public var airspeed: Airspeed?
    public var altitude: AltitudeSetting?
    public var wind: Wind?
    public var tempC: Double?
    public var fuelFlowLbHr: Double?
    public var date: String?

    public init(
        aircraft: String? = nil, aircraftProfile: String? = nil, airspeed: Airspeed? = nil, altitude: AltitudeSetting? = nil,
        wind: Wind? = nil, tempC: Double? = nil, fuelFlowLbHr: Double? = nil, date: String? = nil
    ) {
        self.aircraft = aircraft
        self.aircraftProfile = aircraftProfile
        self.airspeed = airspeed
        self.altitude = altitude
        self.wind = wind
        self.tempC = tempC
        self.fuelFlowLbHr = fuelFlowLbHr
        self.date = date
    }

    // By hand, as a Kotlin data class compares its text: unit for unit, never Swift's canonical equivalence.
    public static func == (a: PlanPatch, b: PlanPatch) -> Bool {
        same(a.aircraft, b.aircraft) && same(a.aircraftProfile, b.aircraftProfile) && a.airspeed == b.airspeed && a.altitude == b.altitude
            && a.wind == b.wind && a.tempC == b.tempC && a.fuelFlowLbHr == b.fuelFlowLbHr && same(a.date, b.date)
    }
}

/// The "to this point" values a screen changes; a nil field is not changed.
public struct OverridePatch: Equatable, Sendable {
    public var altitude: AltitudeSetting?
    public var airspeed: Airspeed?
    public var wind: Wind?

    public init(altitude: AltitudeSetting? = nil, airspeed: Airspeed? = nil, wind: Wind? = nil) {
        self.altitude = altitude
        self.airspeed = airspeed
        self.wind = wind
    }
}

/// What can be done to a sketched route, ported from the web's `sketchOps.js` (the bodies of `useRouteSketch`'s state updaters) and
/// held to `contracts/fixtures/routes/sketch.json`. Each takes a route and returns the route it becomes: the same one when nothing
/// changes. Ids and colours are the caller's, so the results are the same on every device.
public enum SketchOps {
    /// The standard attack profile of a finished sketch: the first two points are Target then IP and the last two RP (IP) then
    /// Target, and the rest are shaping (nil). With only two points the first is the target and the last (which wins) is a target too.
    public static func autoDesignation(_ index: Int, _ lastIndex: Int) -> AutoDesignation? {
        if index == 0 { return AutoDesignation(ptType: "target", name: ".TGT") }
        if index == lastIndex { return AutoDesignation(ptType: "target", name: ".TGT") }
        if index == 1 { return AutoDesignation(ptType: "ip", name: ".SP") }
        if index == lastIndex - 1 { return AutoDesignation(ptType: "ip", name: ".RP") }
        return nil
    }

    /// A route from the points drawn so far, or nil with fewer than two. A designation set while drawing always wins over the
    /// automatic one, and an empty type or name in it is a turn point, named for its place (`.SP` for the first, `.CPn` for the n-th).
    public static func build(
        _ draft: [DraftPoint], name: String, id: String, color: String, plan: RoutePlan, newPointId: () -> String
    ) -> SketchRoute? {
        if draft.count < 2 { return nil }
        let lastIndex = draft.count - 1
        let points = draft.enumerated().map { i, p -> RoutePoint in
            var point = RoutePoint(id: newPointId(), lat: p.lat, lon: p.lon, ele: nil, role: i == 0 ? "start" : "waypoint")
            if let designation = p.designation {
                point.kind = RoutePoint.kindAmps
                point.ptType = orEmptyToNil(designation.ptType) ?? "turn"
                point.name = orEmptyToNil(designation.name) ?? (i == 0 ? ".SP" : ".CP\(i)")
                point.chartElevationFt = designation.chartElevationFt
            } else if let auto = autoDesignation(i, lastIndex) {
                point.kind = RoutePoint.kindAmps
                point.ptType = auto.ptType
                point.name = auto.name
            } else {
                point.kind = RoutePoint.kindShaping
                point.ptType = nil
                point.name = ""
            }
            return point
        }
        return SketchRoute(id: id, name: name, color: color, visible: true, points: points, plan: plan, elevations: [:])
    }

    /// Changes a point's designation. Demoting one of a route's two remaining AMPS points to shaping is refused (legs need at least two
    /// endpoints): the same route comes back. A point nobody knows changes nothing.
    public static func designate(_ route: SketchRoute, _ pointId: String, _ spec: PointSpec) -> SketchRoute {
        let shaping = same(spec.kind, RoutePoint.kindShaping)
        if shaping {
            let ampsCount = route.points.count { same($0.kind, RoutePoint.kindAmps) }
            let target = route.points.first { same($0.id, pointId) }
            if same(target?.kind, RoutePoint.kindAmps) && ampsCount <= 2 { return route }
        }
        var changed = route
        changed.points = route.points.map { p in
            guard same(p.id, pointId) else { return p }
            var point = p
            if shaping {
                point.kind = RoutePoint.kindShaping
                point.ptType = nil
                point.name = ""
                point.role = "waypoint"
            } else {
                point.kind = RoutePoint.kindAmps
                point.ptType = spec.ptType ?? p.ptType ?? "turn"
                point.name = spec.name ?? orEmptyToNil(p.name) ?? ".CP"
                point.role = same(p.role, "start") ? "start" : "waypoint"
            }
            return point
        }
        return changed
    }

    /// Moves a point. `chartElevationFt` is set when a drag snaps onto a local point (its charted elevation) and nil on any normal
    /// drag, so the point goes back to the ground elevation when it is moved off a known point.
    public static func move(
        _ route: SketchRoute, _ pointId: String, _ lat: Double, _ lon: Double, _ chartElevationFt: Double?
    ) -> SketchRoute {
        var changed = route
        changed.points = route.points.map { p in
            guard same(p.id, pointId) else { return p }
            var point = p
            point.lat = lat
            point.lon = lon
            point.chartElevationFt = chartElevationFt
            return point
        }
        return changed
    }

    /// A shaping point on the line, between the nearest pair of consecutive points (a route with fewer than two points has no leg to
    /// split).
    public static func insertShaping(_ route: SketchRoute, _ lat: Double, _ lon: Double, newPointId: () -> String) -> SketchRoute {
        let index = nearestAdjacentIndex(route.points, lat, lon)
        if index == -1 { return route }
        let inserted = RoutePoint(
            id: newPointId(), lat: lat, lon: lon, kind: RoutePoint.kindShaping, ptType: nil, name: "", ele: nil, role: "waypoint")
        var changed = route
        changed.points.insert(inserted, at: index + 1)
        return changed
    }

    /// What a point put on the line of an imported mission is called, as the web names it (`.NEWPT`); the mission writer gives the
    /// file's point the same name.
    public static let missionNewPointName = ".NEWPT"

    /// A point put on the line of an **imported mission**, between the nearest pair of consecutive points. Unlike `insertShaping` it is
    /// a named turn point (`.NEWPT`), with the ground elevation of the point it follows, as the web's `insertPointOnRoute` makes it:
    /// the file has a leg to split there, and a file cannot be given a shaping point.
    public static func insertMissionPoint(_ route: SketchRoute, _ lat: Double, _ lon: Double, newPointId: () -> String) -> SketchRoute {
        let index = nearestAdjacentIndex(route.points, lat, lon)
        if index == -1 { return route }
        let anchor = route.points[index]
        let inserted = RoutePoint(
            id: newPointId(), lat: lat, lon: lon, kind: RoutePoint.kindAmps, ptType: "turn", name: missionNewPointName, ele: anchor.ele,
            role: "waypoint")
        var changed = route
        changed.points.insert(inserted, at: index + 1)
        return changed
    }

    /// A designated AMPS point at the end of a route: how the line is snapped onto a named local point, carrying its charted elevation.
    public static func appendAmps(
        _ route: SketchRoute, _ lat: Double, _ lon: Double, name: String = "", ptType: String = "turn", chartElevationFt: Double? = nil,
        newPointId: () -> String
    ) -> SketchRoute {
        var changed = route
        changed.points.append(
            RoutePoint(
                id: newPointId(), lat: lat, lon: lon, kind: RoutePoint.kindAmps, ptType: ptType, name: name,
                chartElevationFt: chartElevationFt, ele: nil, role: "waypoint"))
        return changed
    }

    /// Merges plan settings into a route's plan.
    public static func withPlan(_ route: SketchRoute, _ patch: PlanPatch) -> SketchRoute {
        var changed = route
        changed.plan.aircraft = patch.aircraft ?? route.plan.aircraft
        changed.plan.aircraftProfile = patch.aircraftProfile ?? route.plan.aircraftProfile
        changed.plan.airspeed = patch.airspeed ?? route.plan.airspeed
        changed.plan.altitude = patch.altitude ?? route.plan.altitude
        changed.plan.wind = patch.wind ?? route.plan.wind
        changed.plan.tempC = patch.tempC ?? route.plan.tempC
        changed.plan.fuelFlowLbHr = patch.fuelFlowLbHr ?? route.plan.fuelFlowLbHr
        changed.plan.date = patch.date ?? route.plan.date
        return changed
    }

    /// Merges a per-point "to" override (altitude, airspeed, wind) into what the point already has.
    public static func withOverride(_ route: SketchRoute, _ pointId: String, _ patch: OverridePatch) -> SketchRoute {
        var merged = route.plan.perPoint[pointId] ?? PointOverride()
        merged.altitude = patch.altitude ?? merged.altitude
        merged.airspeed = patch.airspeed ?? merged.airspeed
        merged.wind = patch.wind ?? merged.wind
        var changed = route
        changed.plan.perPoint = route.plan.perPoint.setting(pointId, merged)
        return changed
    }

    /// Clears every override of a point, its clock too.
    public static func clearOverrides(_ route: SketchRoute, _ pointId: String) -> SketchRoute {
        var changed = route
        changed.plan.perPoint = route.plan.perPoint.removing(pointId)
        return changed
    }

    /// Sets (or clears, with a nil or empty `clock`) a point's clock/TOT time. Only one point anchors the clock at a time, so setting
    /// one clears the others; their other overrides stay, and a point left with nothing is dropped.
    public static func withClock(_ route: SketchRoute, _ pointId: String, _ clock: String?) -> SketchRoute {
        var stripped = OrderedMap<PointOverride>()
        for (id, over) in route.plan.perPoint {
            var rest = over
            rest.clock = nil
            if rest.altitude != nil || rest.airspeed != nil || rest.wind != nil { stripped[id] = rest }
        }
        if let clock, !clock.isEmpty {
            var anchored = stripped[pointId] ?? PointOverride()
            anchored.clock = clock
            stripped[pointId] = anchored
        }
        var changed = route
        changed.plan.perPoint = stripped
        return changed
    }

    /// Renames a point, optionally snapping it to a known local point's position and charted elevation.
    public static func rename(
        _ route: SketchRoute, _ pointId: String, _ name: String, snapTo: (lat: Double, lon: Double)? = nil, chartElevationFt: Double? = nil
    ) -> SketchRoute {
        var changed = route
        changed.points = route.points.map { p in
            guard same(p.id, pointId) else { return p }
            var point = p
            point.name = name
            if let snapTo {
                point.lat = snapTo.lat
                point.lon = snapTo.lon
                point.chartElevationFt = chartElevationFt
            }
            return point
        }
        return changed
    }

    /// The nearest pair of *consecutive, rendered* points to a click: it searches in drawing order, not across the whole route's legs,
    /// because a route with a serpentine can have distant legs that pass close to each other and a route-wide search would snap to the
    /// wrong one. -1 with fewer than two points. The distance is flat, in degrees, as the web measures it; the first of equal pairs
    /// wins.
    public static func nearestAdjacentIndex(_ points: [RoutePoint], _ lat: Double, _ lon: Double) -> Int {
        var bestIndex = -1
        var bestDistance = Double.infinity
        for i in points.indices.dropLast() {
            let a = points[i]
            let c = points[i + 1]
            let d = pointToSegmentDistanceSq(lon, lat, a.lon, a.lat, c.lon, c.lat)
            if d < bestDistance {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex
    }

    private static func pointToSegmentDistanceSq(
        _ px: Double, _ py: Double, _ ax: Double, _ ay: Double, _ bx: Double, _ by: Double
    ) -> Double {
        let dx = bx - ax
        let dy = by - ay
        let lengthSq = dx * dx + dy * dy
        if lengthSq == 0 { return (px - ax) * (px - ax) + (py - ay) * (py - ay) }
        // `coerceIn(0.0, 1.0)`: NaN stays NaN.
        var t = ((px - ax) * dx + (py - ay) * dy) / lengthSq
        if t < 0 { t = 0 } else if t > 1 { t = 1 }
        let projX = ax + t * dx
        let projY = ay + t * dy
        return (px - projX) * (px - projX) + (py - projY) * (py - projY)
    }

    /// JavaScript's `a || b` over text: an empty string is as good as none.
    private static func orEmptyToNil(_ text: String?) -> String? { (text ?? "").isEmpty ? nil : text }
}
