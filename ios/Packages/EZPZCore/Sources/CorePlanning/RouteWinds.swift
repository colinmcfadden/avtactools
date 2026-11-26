import CoreModel
import Foundation

/// One named point of a route as a wind is asked for it: where, and the wall-clock time it is wanted for (nil for now).
public struct WindQuery: Hashable, Sendable {
    public var id: String
    public var lat: Double
    public var lon: Double
    public var localTime: LocalDateTime?

    public init(id: String, lat: Double, lon: Double, localTime: LocalDateTime?) {
        self.id = id
        self.lat = lat
        self.lon = lon
        self.localTime = localTime
    }
}

/// What is asked of the weather service for a route, and the named points the answers belong to.
public struct WindRequest: Equatable, Sendable {
    public var points: [WindQuery]
    public var amps: [RoutePoint]

    public init(points: [WindQuery], amps: [RoutePoint]) {
        self.points = points
        self.amps = amps
    }
}

/// What the weather service found for a point: the wind, and the temperature of the nearest observation.
public struct PointWind: Hashable, Sendable {
    public var dirTrue: Double
    public var speedKts: Double
    public var tempC: Double?

    public init(dirTrue: Double, speedKts: Double, tempC: Double? = nil) {
        self.dirTrue = dirTrue
        self.speedKts = speedKts
        self.tempC = tempC
    }
}

/// Forecast winds for a route, ported from the web's `routeWinds.js` (`windRequest` and `mergeWindsIntoPlan`, the pure parts of
/// `fetchForecastWinds`) and held to `contracts/fixtures/routes/winds.json`. The server picks the station and chooses between the
/// latest observation and the forecast; what is the app's to get right is which instant each point asks for, and what it does with
/// the answer.
public enum RouteWinds {
    /// The question for `route`: one entry for each named point, wanted at the time it will be there when a point carries a clock
    /// (the TOT), else at local midday on the plan's date, else for now. Nil when the route has no named point at all (the web says
    /// "Route has no points."). `today` is the day a clock falls on when the plan names none.
    ///
    /// A time is a wall-clock time, not an instant: which instant it is depends on the zone of the device (`instantText`). A route that
    /// runs across the night a clock changes is timed in wall-clock seconds (as the rest of the planner is), which is an hour out for
    /// the points after the change.
    public static func request(_ route: SketchRoute, today: LocalDate = .today()) -> WindRequest? {
        let amps = RouteCalc.planPoints(route.points)
        if amps.isEmpty { return nil }
        let plan = RouteCalc.computeRoutePlan(route.points, route.plan, elevationsFt: route.elevations, today: today)
        let midday = route.plan.date.isEmpty ? nil : LocalDate.parse(route.plan.date)?.atTime(12, 0)
        let points = amps.enumerated().map { i, p in
            WindQuery(id: p.id!, lat: p.lat, lon: p.lon, localTime: (i < plan.points.count ? plan.points[i].clockTime : nil) ?? midday)
        }
        return WindRequest(points: points, amps: amps)
    }

    /// `time` on this wall clock in `zone`, as the instant the server is asked about: UTC, to the millisecond, as JavaScript's
    /// `toISOString` writes it.
    ///
    /// java.time's `atZone` with no preferred offset: a time the clocks skip (spring forward) is moved on by the gap, and one they
    /// repeat (fall back) is the earlier of the two. Both come to the wall time less the offset before the change. Foundation's own
    /// conversion is not leaned on for those, so the rule is java.time's in every zone.
    public static func instantText(_ time: LocalDateTime, _ zone: TimeZone) -> String {
        let wall = time.wallEpochSecond
        func offset(at second: Int64) -> Int64 { Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(second)))) }
        let before = offset(at: wall - 86_400)
        let after = offset(at: wall + 86_400)
        let used: Int64
        if offset(at: wall - before) == before {
            used = before
        } else if offset(at: wall - after) == after {
            used = after
        } else {
            used = before
        }
        let utc = LocalDateTime(year: 1970, month: 1, day: 1).plusSeconds(wall - used).plusNanos(Int64(time.nanosecond))
        let millis = utc.nanosecond / 1_000_000
        return "\(padded(String(utc.year), 4))-\(padded(String(utc.month), 2))-\(padded(String(utc.day), 2))T"
            + "\(padded(String(utc.hour), 2)):\(padded(String(utc.minute), 2)):\(padded(String(utc.second), 2)).\(padded(String(millis), 3))Z"
    }

    /// `plan` with the `winds` found merged in as each point's "to" wind, keeping what else the point carries (altitude, airspeed,
    /// clock). The plan's temperature becomes the first point's that has one. A point with no answer is left alone, and an answer for
    /// a point that is not in `amps` is ignored.
    public static func merge(_ plan: RoutePlan, _ amps: [RoutePoint], _ winds: OrderedMap<PointWind>) -> RoutePlan {
        var perPoint = plan.perPoint
        var firstTemp: Double?
        for point in amps {
            guard let id = point.id, let found = winds[id] else { continue }
            var override = perPoint[id] ?? PointOverride()
            override.wind = Wind(dirTrue: found.dirTrue, speedKts: found.speedKts)
            perPoint[id] = override
            if firstTemp == nil, let temp = found.tempC { firstTemp = temp }
        }
        var merged = plan
        merged.perPoint = perPoint
        merged.tempC = firstTemp ?? plan.tempC
        return merged
    }
}

/// Ground elevations for a route's points. The web asks for the named points only and keeps an elevation only where the server gave
/// a number (`fetchPointElevationsFt`): a point with no answer has none, and planning goes on without it.
public enum RouteElevations {
    /// The points to ask about: the named ones, in order.
    public static func points(_ route: SketchRoute) -> [RoutePoint] { RouteCalc.planPoints(route.points) }

    /// Feet by point id, from `feet` answered in the order of `amps`; a nil (or a missing answer) leaves the point out.
    public static func byPoint(_ amps: [RoutePoint], _ feet: [Double?]) -> OrderedMap<Double> {
        var out = OrderedMap<Double>()
        for (i, p) in amps.enumerated() {
            guard let id = p.id, i < feet.count, let value = feet[i] else { continue }
            out[id] = value
        }
        return out
    }
}
