package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Wind

/** A corner put down while sketching, with what the person said it is (from the draw-mode menu, or a local point added to the route), if anything. */
public data class DraftPoint(val lat: Double, val lon: Double, val designation: Designation? = null)

/** What a point drawn as a named AMPS point says it is. An empty type or name reads as none, as the web's `||` does. */
public data class Designation(val ptType: String? = null, val name: String? = null, val chartElevationFt: Double? = null)

/** What a point is being changed to: `kind` is `amps` or `shaping`. A null [ptType] or [name] says nothing about it (the point keeps its own). */
public data class PointSpec(val kind: String, val ptType: String? = null, val name: String? = null)

/** What the standard attack profile makes a point of a finished sketch. */
public data class AutoDesignation(val ptType: String, val name: String)

/** The plan settings a screen changes; a null field is not changed. (The web merges a patch's keys over the plan, and its screens only ever set values.) */
public data class PlanPatch(
    val aircraft: String? = null,
    val aircraftProfile: String? = null,
    val airspeed: Airspeed? = null,
    val altitude: AltitudeSetting? = null,
    val wind: Wind? = null,
    val tempC: Double? = null,
    val fuelFlowLbHr: Double? = null,
    val date: String? = null,
)

/** The "to this point" values a screen changes; a null field is not changed. */
public data class OverridePatch(val altitude: AltitudeSetting? = null, val airspeed: Airspeed? = null, val wind: Wind? = null)

/**
 * What can be done to a sketched route, ported from the web's `sketchOps.js` (the bodies of `useRouteSketch`'s state updaters) and held to
 * `contracts/fixtures/routes/sketch.json`. Each takes a route and returns the route it becomes: the same one when nothing changes. Ids and colours are
 * the caller's, so the results are the same on every device.
 */
public object SketchOps {
    /**
     * The standard attack profile of a finished sketch: the first two points are Target then IP and the last two RP (IP) then Target, and the
     * rest are shaping (null). With only two points the first is the target and the last (which wins) is a target too.
     */
    public fun autoDesignation(index: Int, lastIndex: Int): AutoDesignation? = when {
        index == 0 -> AutoDesignation("target", ".TGT")
        index == lastIndex -> AutoDesignation("target", ".TGT")
        index == 1 -> AutoDesignation("ip", ".SP")
        index == lastIndex - 1 -> AutoDesignation("ip", ".RP")
        else -> null
    }

    /**
     * A route from the points drawn so far, or null with fewer than two. A designation set while drawing always wins over the automatic one, and
     * an empty type or name in it is a turn point, named for its place (`.SP` for the first, `.CPn` for the n-th).
     */
    public fun build(draft: List<DraftPoint>, name: String, id: String, color: String, plan: RoutePlan, newPointId: () -> String): SketchRoute? {
        if (draft.size < 2) return null
        val lastIndex = draft.lastIndex
        val points = draft.mapIndexed { i, p ->
            val base = RoutePoint(id = newPointId(), lat = p.lat, lon = p.lon, ele = null, role = if (i == 0) "start" else "waypoint")
            val designation = p.designation
            val auto = autoDesignation(i, lastIndex)
            when {
                designation != null -> base.copy(
                    kind = RoutePoint.KIND_AMPS,
                    ptType = designation.ptType.orEmptyToNull() ?: "turn",
                    name = designation.name.orEmptyToNull() ?: if (i == 0) ".SP" else ".CP$i",
                    chartElevationFt = designation.chartElevationFt,
                )
                auto != null -> base.copy(kind = RoutePoint.KIND_AMPS, ptType = auto.ptType, name = auto.name)
                else -> base.copy(kind = RoutePoint.KIND_SHAPING, ptType = null, name = "")
            }
        }
        return SketchRoute(id = id, name = name, color = color, visible = true, points = points, plan = plan, elevations = emptyMap())
    }

    /**
     * Changes a point's designation. Demoting one of a route's two remaining AMPS points to shaping is refused (legs need at least two
     * endpoints): the same route comes back. A point nobody knows changes nothing.
     */
    public fun designate(route: SketchRoute, pointId: String, spec: PointSpec): SketchRoute {
        if (spec.kind == RoutePoint.KIND_SHAPING) {
            val ampsCount = route.points.count { it.kind == RoutePoint.KIND_AMPS }
            val target = route.points.firstOrNull { it.id == pointId }
            if (target?.kind == RoutePoint.KIND_AMPS && ampsCount <= 2) return route
        }
        return route.copy(
            points = route.points.map { p ->
                when {
                    p.id != pointId -> p
                    spec.kind == RoutePoint.KIND_SHAPING -> p.copy(kind = RoutePoint.KIND_SHAPING, ptType = null, name = "", role = "waypoint")
                    else -> p.copy(
                        kind = RoutePoint.KIND_AMPS,
                        ptType = spec.ptType ?: p.ptType ?: "turn",
                        name = spec.name ?: p.name.orEmptyToNull() ?: ".CP",
                        role = if (p.role == "start") "start" else "waypoint",
                    )
                }
            },
        )
    }

    /**
     * Moves a point. [chartElevationFt] is set when a drag snaps onto a local point (its charted elevation) and null on any normal drag, so the
     * point goes back to the ground elevation when it is moved off a known point.
     */
    public fun move(route: SketchRoute, pointId: String, lat: Double, lon: Double, chartElevationFt: Double?): SketchRoute =
        route.copy(points = route.points.map { if (it.id == pointId) it.copy(lat = lat, lon = lon, chartElevationFt = chartElevationFt) else it })

    /** A shaping point on the line, between the nearest pair of consecutive points (a route with fewer than two points has no leg to split). */
    public fun insertShaping(route: SketchRoute, lat: Double, lon: Double, newPointId: () -> String): SketchRoute {
        val index = nearestAdjacentIndex(route.points, lat, lon)
        if (index == -1) return route
        val inserted = RoutePoint(id = newPointId(), lat = lat, lon = lon, ele = null, kind = RoutePoint.KIND_SHAPING, ptType = null, name = "", role = "waypoint")
        return route.copy(points = route.points.take(index + 1) + inserted + route.points.drop(index + 1))
    }

    /** A designated AMPS point at the end of a route: how the line is snapped onto a named local point, carrying its charted elevation. */
    public fun appendAmps(route: SketchRoute, lat: Double, lon: Double, name: String = "", ptType: String = "turn", chartElevationFt: Double? = null, newPointId: () -> String): SketchRoute =
        route.copy(
            points = route.points + RoutePoint(
                id = newPointId(), lat = lat, lon = lon, ele = null, kind = RoutePoint.KIND_AMPS, ptType = ptType, name = name, role = "waypoint",
                chartElevationFt = chartElevationFt,
            ),
        )

    /** Merges plan settings into a route's plan. */
    public fun withPlan(route: SketchRoute, patch: PlanPatch): SketchRoute = route.copy(
        plan = route.plan.copy(
            aircraft = patch.aircraft ?: route.plan.aircraft,
            aircraftProfile = patch.aircraftProfile ?: route.plan.aircraftProfile,
            airspeed = patch.airspeed ?: route.plan.airspeed,
            altitude = patch.altitude ?: route.plan.altitude,
            wind = patch.wind ?: route.plan.wind,
            tempC = patch.tempC ?: route.plan.tempC,
            fuelFlowLbHr = patch.fuelFlowLbHr ?: route.plan.fuelFlowLbHr,
            date = patch.date ?: route.plan.date,
        ),
    )

    /** Merges a per-point "to" override (altitude, airspeed, wind) into what the point already has. */
    public fun withOverride(route: SketchRoute, pointId: String, patch: OverridePatch): SketchRoute {
        val existing = route.plan.perPoint[pointId] ?: PointOverride()
        val merged = existing.copy(
            altitude = patch.altitude ?: existing.altitude,
            airspeed = patch.airspeed ?: existing.airspeed,
            wind = patch.wind ?: existing.wind,
        )
        return route.copy(plan = route.plan.copy(perPoint = route.plan.perPoint + (pointId to merged)))
    }

    /** Clears every override of a point, its clock too. */
    public fun clearOverrides(route: SketchRoute, pointId: String): SketchRoute =
        route.copy(plan = route.plan.copy(perPoint = route.plan.perPoint - pointId))

    /**
     * Sets (or clears, with a null or empty [clock]) a point's clock/TOT time. Only one point anchors the clock at a time, so setting one clears
     * the others; their other overrides stay, and a point left with nothing is dropped.
     */
    public fun withClock(route: SketchRoute, pointId: String, clock: String?): SketchRoute {
        val stripped = LinkedHashMap<String, PointOverride>()
        for ((id, over) in route.plan.perPoint) {
            val rest = over.copy(clock = null)
            if (rest.altitude != null || rest.airspeed != null || rest.wind != null) stripped[id] = rest
        }
        if (!clock.isNullOrEmpty()) stripped[pointId] = (stripped[pointId] ?: PointOverride()).copy(clock = clock)
        return route.copy(plan = route.plan.copy(perPoint = stripped))
    }

    /** Renames a point, optionally snapping it to a known local point's position and charted elevation. */
    public fun rename(route: SketchRoute, pointId: String, name: String, snapTo: Pair<Double, Double>? = null, chartElevationFt: Double? = null): SketchRoute =
        route.copy(
            points = route.points.map { p ->
                when {
                    p.id != pointId -> p
                    snapTo != null -> p.copy(name = name, lat = snapTo.first, lon = snapTo.second, chartElevationFt = chartElevationFt)
                    else -> p.copy(name = name)
                }
            },
        )

    /**
     * The nearest pair of *consecutive, rendered* points to a click: it searches in drawing order, not across the whole route's legs, because a
     * route with a serpentine can have distant legs that pass close to each other and a route-wide search would snap to the wrong one. -1 with fewer
     * than two points. The distance is flat, in degrees, as the web measures it; the first of equal pairs wins.
     */
    public fun nearestAdjacentIndex(points: List<RoutePoint>, lat: Double, lon: Double): Int {
        var bestIndex = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val c = points[i + 1]
            val d = pointToSegmentDistanceSq(lon, lat, a.lon, a.lat, c.lon, c.lat)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun pointToSegmentDistanceSq(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return (px - ax) * (px - ax) + (py - ay) * (py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return (px - projX) * (px - projX) + (py - projY) * (py - projY)
    }

    /** JavaScript's `a || b` over text: an empty string is as good as none. */
    private fun String?.orEmptyToNull(): String? = this?.takeIf { it.isNotEmpty() }
}
