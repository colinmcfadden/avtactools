package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.Doghouses
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import app.ezpztac.model.PlanLeg
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * A leg of a route that flies into or out of an LZ/PZ: the route it is on ([routeId], [routeName]), the names of its ends as the route's doghouses
 * write them ([from], [to]), its true course ([courseDeg]) and that course as the flight data keeps a heading ([heading], `"060°"`), and how far its
 * end at the LZ/PZ is from the target ([metres]).
 */
public data class LzLeg(
    val routeId: String?,
    val routeName: String,
    val from: String,
    val to: String,
    val courseDeg: Double,
    val heading: String,
    val metres: Double,
) {
    /** What the slope tile says of a landing heading this leg gives: `"From ROUTE 2, RP to TGT"`. */
    val words: String
        get() = "From ${routeName.ifEmpty { "the route" }}${if (from.isNotEmpty() && to.isNotEmpty()) ", $from to $to" else ""}"
}

/** A route shown, as [LzLegs] reads it: its named points and the planner's legs between them, one for one. */
public class LegRoute(public val id: String?, public val name: String, public val named: List<RoutePoint>, public val legs: List<PlanLeg>)

/**
 * The legs of the routes shown that fly into ([landing]) and out of ([takeoff]) an LZ/PZ, and what they do to its own doghouses (the owner,
 * 2026-11-26): the web's `feature/routeDoghouses/lzLegs.js`, held to `contracts/fixtures/routes/doghouses.json`. A route's doghouse on the leg that
 * arrives at the target stands in for the LZ/PZ's landing doghouse, and one on the leg that leaves it for its takeoff doghouse: while one does, the
 * LZ/PZ's own is not drawn ([doghousesBeside]) and the heading it would give the flight data is the leg's true course instead ([judged]), so the
 * slope is judged on the course the route flies in, and the brief says it. Nothing is written into the LZ/PZ.
 *
 * A leg reaches the target where its named point there is within [REACH_M] of it (the brief's rule for a route that reaches a stop): it arrives when
 * that is the end it flies to and leaves when it is the end it flies from, whichever end is the nearer (the one it flies to, of two as near), so a short
 * last leg from a checkpoint 300 m out is an arrival and nothing more. Of several, the one whose end is nearest wins, the first of equal ones. Only
 * routes shown count: a route hidden on the map stands in for nothing.
 */
public data class LzLegs(val landing: LzLeg? = null, val takeoff: LzLeg? = null) {
    /** Whether a leg stands in for either doghouse. */
    val any: Boolean get() = landing != null || takeoff != null

    /** [flightData] with the headings of the legs that stand in for its doghouses; the same object when none does. */
    public fun judged(flightData: JsonObject): JsonObject {
        if (!any) return flightData
        val out = LinkedHashMap(flightData)
        landing?.let { out["landing_hdg"] = JsonPrimitive(it.heading) }
        takeoff?.let { out["takeoff_hdg"] = JsonPrimitive(it.heading) }
        return JsonObject(out)
    }

    /** [doghouses] less those a leg stands in for (the first landing and the first takeoff doghouse, as give the flight data its headings); the same list when neither is. */
    public fun doghousesBeside(doghouses: List<JsonElement>): List<JsonElement> {
        val landingAt = if (landing != null) doghouses.indexOfFirst { it is JsonObject && Doghouses.isLanding(it) } else -1
        val takeoffAt = if (takeoff != null) doghouses.indexOfFirst { it is JsonObject && Doghouses.isTakeoff(it) } else -1
        if (landingAt < 0 && takeoffAt < 0) return doghouses
        return doghouses.filterIndexed { i, _ -> i != landingAt && i != takeoffAt }
    }

    public companion object {
        /** How near the target a route's named point must be for its leg to reach the LZ/PZ, in metres. */
        public const val REACH_M: Double = 500.0
        private const val METRES_PER_NM = 1852.0

        /** No leg stands in for anything. */
        public val NONE: LzLegs = LzLegs()

        /** A true course as a heading the flight data keeps: whole degrees, 0 to 359, three digits and a degree sign (`"060°"`). */
        public fun headingText(courseDeg: Double): String = Doghouses.headingText(((RouteCalc.jsRound(courseDeg) % 360) + 360) % 360)

        /** The legs of [routes] that arrive at [target] and leave it; [NONE] with no target. [today] is the planner's (clock times only). */
        public fun of(target: LatLon?, routes: List<SketchRoute>, today: LocalDate = LocalDate.now()): LzLegs {
            if (target == null) return NONE
            val planned = routes.mapNotNull { route ->
                if (!route.visible || !route.points.all { it.lat.isFinite() && it.lon.isFinite() }) return@mapNotNull null
                val named = RouteCalc.planPoints(route.points)
                if (named.size < 2) return@mapNotNull null
                LegRoute(route.id, route.name, named, RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations, today).legs)
            }
            return ofPlanned(target, planned)
        }

        /** The legs of routes already planned ([LegRoute]: the brief has planned them for its own day) that arrive at [target] and leave it. */
        public fun ofPlanned(target: LatLon?, routes: List<LegRoute>): LzLegs {
            if (target == null || !target.lat.isFinite() || !target.lon.isFinite()) return NONE
            fun metresTo(p: RoutePoint) = GreatCircle.distanceNm(p.lat, p.lon, target.lat, target.lon) * METRES_PER_NM
            var landing: LzLeg? = null
            var takeoff: LzLeg? = null
            for (route in routes) {
                route.legs.forEachIndexed { i, leg ->
                    if (!leg.courseTrueDeg.isFinite() || !(leg.distNm > 0)) return@forEachIndexed
                    val from = route.named.getOrNull(i) ?: return@forEachIndexed
                    val to = route.named.getOrNull(i + 1) ?: return@forEachIndexed
                    fun legAt(metres: Double) = LzLeg(
                        routeId = route.id,
                        routeName = JsNumber.trimJs(route.name),
                        from = RouteDoghouses.roofName(from.name).ifEmpty { RouteDoghouses.roofName(leg.fromName) },
                        to = RouteDoghouses.roofName(to.name).ifEmpty { RouteDoghouses.roofName(leg.toName) },
                        courseDeg = leg.courseTrueDeg,
                        heading = headingText(leg.courseTrueDeg),
                        metres = metres,
                    )
                    // A leg is an arrival or a departure by its end nearer the target (the one it flies to, of two as near), never both.
                    val arriving = metresTo(to)
                    val leaving = metresTo(from)
                    if (arriving <= leaving) {
                        if (arriving <= REACH_M && (landing == null || arriving < landing.metres)) landing = legAt(arriving)
                    } else if (leaving <= REACH_M && (takeoff == null || leaving < takeoff.metres)) {
                        takeoff = legAt(leaving)
                    }
                }
            }
            return if (landing == null && takeoff == null) NONE else LzLegs(landing, takeoff)
        }
    }
}
