package app.ezpztac.planning

import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Wind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One named point of a route as a wind is asked for it: where, and the wall-clock time it is wanted for (null for now). */
public data class WindQuery(val id: String, val lat: Double, val lon: Double, val localTime: LocalDateTime?)

/** What is asked of the weather service for a route, and the named points the answers belong to. */
public data class WindRequest(val points: List<WindQuery>, val amps: List<RoutePoint>)

/** What the weather service found for a point: the wind, and the temperature of the nearest observation. */
public data class PointWind(val dirTrue: Double, val speedKts: Double, val tempC: Double? = null)

/**
 * Forecast winds for a route, ported from the web's `routeWinds.js` (`windRequest` and `mergeWindsIntoPlan`, the pure parts of `fetchForecastWinds`) and held to
 * `contracts/fixtures/routes/winds.json`. The server picks the station and chooses between the latest observation and the forecast; what is the app's to get right
 * is which instant each point asks for, and what it does with the answer.
 */
public object RouteWinds {
    /**
     * The question for [route]: one entry for each named point, wanted at the time it will be there when a point carries a clock (the TOT), else at local
     * midday on the plan's date, else for now. Null when the route has no named point at all (the web says "Route has no points."). [today] is the day a
     * clock falls on when the plan names none.
     *
     * A time is a wall-clock time, not an instant: which instant it is depends on the zone of the device ([instantText]). A route that runs across the night a
     * clock changes is timed in wall-clock seconds (as the rest of the planner is), which is an hour out for the points after the change.
     */
    public fun request(route: SketchRoute, today: LocalDate = LocalDate.now()): WindRequest? {
        val amps = RouteCalc.planPoints(route.points)
        if (amps.isEmpty()) return null
        val plan = RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations, today)
        val midday = route.plan.date.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it).atTime(12, 0) }.getOrNull() }
        val points = amps.mapIndexed { i, p -> WindQuery(requireNotNull(p.id), p.lat, p.lon, plan.points.getOrNull(i)?.clockTime ?: midday) }
        return WindRequest(points, amps)
    }

    private val INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    /** [time] on this wall clock in [zone], as the instant the server is asked about: UTC, to the millisecond, as JavaScript's `toISOString` writes it. */
    public fun instantText(time: LocalDateTime, zone: ZoneId): String = time.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(INSTANT)

    /**
     * [plan] with the [winds] found merged in as each point's "to" wind, keeping what else the point carries (altitude, airspeed, clock). The plan's temperature
     * becomes the first point's that has one. A point with no answer is left alone, and an answer for a point that is not in [amps] is ignored.
     */
    public fun merge(plan: RoutePlan, amps: List<RoutePoint>, winds: Map<String, PointWind>): RoutePlan {
        val perPoint = LinkedHashMap(plan.perPoint)
        var firstTemp: Double? = null
        for (point in amps) {
            val id = point.id ?: continue
            val found = winds[id] ?: continue
            perPoint[id] = (perPoint[id] ?: PointOverride()).copy(wind = Wind(found.dirTrue, found.speedKts))
            if (firstTemp == null && found.tempC != null) firstTemp = found.tempC
        }
        return plan.copy(perPoint = perPoint, tempC = firstTemp ?: plan.tempC)
    }
}

/**
 * Ground elevations for a route's points. The web asks for the named points only and keeps an elevation only where the server gave a number (`fetchPointElevationsFt`):
 * a point with no answer has none, and planning goes on without it.
 */
public object RouteElevations {
    /** The points to ask about: the named ones, in order. */
    public fun points(route: SketchRoute): List<RoutePoint> = RouteCalc.planPoints(route.points)

    /** Feet by point id, from [feet] answered in the order of [amps]; a null (or a missing answer) leaves the point out. */
    public fun byPoint(amps: List<RoutePoint>, feet: List<Double?>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        amps.forEachIndexed { i, p ->
            val id = p.id ?: return@forEachIndexed
            feet.getOrNull(i)?.let { out[id] = it }
        }
        return out
    }
}
