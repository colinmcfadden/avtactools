package app.ezpztac.scene3d

import app.ezpztac.model.JsNumber
import app.ezpztac.model.PlanResult
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteCalc
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A place the server is asked the ground and the geoid at: six decimals, about a tenth of a metre, as the web rounds it. */
public data class GroundSample(val lat: Double, val lon: Double)

/** A named (AMPS) point of a route: its sample, how far along the route it is, and what the planner says of its altitude. */
public data class RouteAnchor(
    val sample: Int,
    val distance: Double,
    val name: String?,
    val ptType: String?,
    val mslFt: Double?,
    val aglFt: Double?,
    /** A target at either end: the pickup or landing zone, where the aircraft is on the ground. */
    val touchdown: Boolean,
)

/** A route ready to be placed once the heights come: its samples' run in [PreparedRoutes.samples] and how far along each is. */
public data class PreparedRoute(
    val id: String,
    val name: String,
    val color: String,
    val first: Int,
    val count: Int,
    val distances: List<Double>,
    val anchors: List<RouteAnchor>,
)

/** The routes to draw, and every place along them to ask the heights at (the request's body). */
public data class PreparedRoutes(val routes: List<PreparedRoute>, val samples: List<GroundSample>)

/** The server's answer for [PreparedRoutes.samples]: the ground (metres above the ellipsoid, none where no DEM is) and the geoid, place by place. */
public data class RouteHeights(val groundM: List<Double?>, val geoidM: List<Double?>?)

/** A place along a route, in metres above the ellipsoid. */
public data class RoutePosition(val lon: Double, val lat: Double, val heightM: Double)

/** A curtain from the route down to the ground, where both are known. */
public data class RouteWall(val positions: List<RoutePosition>, val groundM: List<Double>)

/** A named point drawn: where it is, the ground under it, and its label (its lines, and whether the AGL measured disagrees with the plan's). */
public data class RouteMarker(
    val lon: Double,
    val lat: Double,
    val heightM: Double,
    val groundM: Double?,
    val ptType: String?,
    val label: String,
    val measuredAglFt: Double?,
    val mismatch: Boolean,
)

/** One route's shapes: its lines (runs with heights), its curtains and its markers. */
public data class PlacedRoute(
    val id: String,
    val name: String,
    val color: String,
    val lines: List<List<RoutePosition>>,
    val walls: List<RouteWall>,
    val markers: List<RouteMarker>,
)

/**
 * The routes in 3D, the web's `routeScene.js` held to `contracts/fixtures/viewer3d/routeScene.json`: [prepare] says where to ask the ground
 * along each visible route (every 50 m, coarser past 4,500 places), and [place] makes the shapes once the heights come. A named point is at the
 * planner's MSL plus the geoid (the AMPS `CmdAlt`, the altitude flown); a target at either end is on the ground; an AGL point with no planned
 * MSL is at the ground plus its AGL; a height that cannot be known leaves a gap, never a guess. The distances use fdlibm (`StrictMath`), as
 * JavaScript's `Math` does, so the places asked are the web's to the bit.
 */
public object RouteScene3D {
    public const val FT_TO_M: Double = 0.3048
    public const val SAMPLE_STEP_M: Double = 50.0
    public const val MAX_SAMPLES: Int = 4500
    public const val AGL_MISMATCH_FT: Double = 50.0

    private const val EARTH_RADIUS_M = 6371008.8

    public fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val toRad = Math.PI / 180
        val dLat = (bLat - aLat) * toRad
        val dLon = (bLon - aLon) * toRad
        val sLat = StrictMath.sin(dLat / 2)
        val sLon = StrictMath.sin(dLon / 2)
        val h = sLat * sLat + StrictMath.cos(aLat * toRad) * StrictMath.cos(bLat * toRad) * (sLon * sLon)
        return 2 * EARTH_RADIUS_M * StrictMath.asin(min(1.0, StrictMath.sqrt(h)))
    }

    private class Candidate(val route: SketchRoute, val points: List<RoutePoint>, val plan: PlanResult, val along: DoubleArray)

    /** The visible routes, and every place along them to ask the heights at. [today] is the planner's (clock times only). */
    public fun prepare(routes: List<SketchRoute>, today: LocalDate = LocalDate.now()): PreparedRoutes {
        val candidates = ArrayList<Candidate>()
        for (route in routes) {
            if (!route.visible) continue
            val points = route.points
            if (points.size < 2 || !points.all { it.lat.isFinite() && it.lon.isFinite() }) continue
            val plan = RouteCalc.computeRoutePlan(points, route.plan, route.elevations, today)
            if (plan.points.size < 2) continue
            val along = DoubleArray(points.size)
            for (i in 1 until points.size) along[i] = along[i - 1] + distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            candidates += Candidate(route, points, plan, along)
        }
        val vertexCount = candidates.sumOf { it.points.size }
        val totalM = candidates.sumOf { it.along.last() }
        val step = max(SAMPLE_STEP_M, totalM / max(1, MAX_SAMPLES - vertexCount))

        val samples = ArrayList<GroundSample>()
        val prepared = ArrayList<PreparedRoute>()
        for (c in candidates) {
            val first = samples.size
            val distances = ArrayList<Double>()
            val vertexSample = IntArray(c.points.size)
            for ((i, point) in c.points.withIndex()) {
                if (i > 0) {
                    val prev = c.points[i - 1]
                    val legM = c.along[i] - c.along[i - 1]
                    var d = step
                    while (d < legM) {
                        val t = d / legM
                        samples += GroundSample(prev.lat + (point.lat - prev.lat) * t, prev.lon + (point.lon - prev.lon) * t)
                        distances += c.along[i - 1] + d
                        d += step
                    }
                }
                vertexSample[i] = samples.size
                samples += GroundSample(point.lat, point.lon)
                distances += c.along[i]
            }
            // AMPS points carry the planned altitudes; shaping points only shape the line, their heights from the AMPS points either side.
            val amps = RouteCalc.planPoints(c.points)
            val anchors = amps.mapIndexed { i, point ->
                val index = c.points.indexOfFirst { it === point }
                val planned = c.plan.points[i]
                RouteAnchor(
                    vertexSample[index], c.along[index], point.name, point.ptType, planned.mslFt, planned.aglFt,
                    touchdown = point.ptType == "target" && (i == 0 || i == amps.size - 1),
                )
            }
            prepared += PreparedRoute(c.route.id, c.route.name, c.route.color, first, samples.size - first, distances, anchors)
        }
        // Rounded to about 0.1 m: finer than any DEM, and it halves the request.
        val rounded = samples.map { GroundSample(JsNumber.toFixed(it.lat, 6).toDouble(), JsNumber.toFixed(it.lon, 6).toDouble()) }
        return PreparedRoutes(prepared, rounded)
    }

    private class Placed(val anchor: RouteAnchor, val heightM: Double?, val basis: String?, val groundM: Double?, val geoidM: Double?)

    /** The shapes, given the server's answer for [prepared]'s samples; none for an answer that is not for them. */
    public fun place(prepared: PreparedRoutes, heights: RouteHeights?): List<PlacedRoute> {
        val ground = heights?.groundM ?: return emptyList()
        if (ground.size != prepared.samples.size) return emptyList()
        val geoid = heights.geoidM
        return prepared.routes.map { route ->
            fun groundAt(i: Int): Double? = ground[route.first + i]
            val anchors = route.anchors.map { anchor ->
                val g = ground[anchor.sample]
                val q = geoid?.getOrNull(anchor.sample)
                val (heightM, basis) = when {
                    anchor.touchdown && g != null -> g to "ground"
                    anchor.mslFt != null -> anchor.mslFt * FT_TO_M + (q ?: 0.0) to "msl"
                    anchor.aglFt != null && g != null -> g + anchor.aglFt * FT_TO_M to "agl"
                    else -> null to null
                }
                Placed(anchor, heightM, basis, g, q)
            }
            val heightsM = route.distances.map { heightAt(anchors, it) }
            fun position(i: Int): RoutePosition {
                val s = prepared.samples[route.first + i]
                return RoutePosition(s.lon, s.lat, heightsM[i]!!)
            }
            val lines = runs(route.count) { heightsM[it] != null }.map { (start, end) -> (start until end).map(::position) }
            val walls = runs(route.count) { heightsM[it] != null && groundAt(it) != null }.map { (start, end) ->
                RouteWall((start until end).map(::position), (start until end).map { groundAt(it)!! })
            }
            val markers = anchors.filter { it.heightM != null }.map { p ->
                val s = prepared.samples[p.anchor.sample]
                val (label, measured, mismatch) = labelFor(p)
                RouteMarker(s.lon, s.lat, p.heightM!!, p.groundM, p.anchor.ptType, label, measured, mismatch)
            }
            PlacedRoute(route.id, route.name, route.color, lines, walls, markers)
        }
    }

    private fun labelFor(p: Placed): Triple<String, Double?, Boolean> {
        val name = (p.anchor.name ?: "").removePrefix(".").ifEmpty { "Point" }
        val measured = p.groundM?.let { (p.heightM!! - it) / FT_TO_M }
        val lines = mutableListOf(name)
        if (p.basis == "ground") {
            // The zone's own elevation, from the ellipsoid back to MSL: the number a crew briefs for a landing zone.
            lines += "On the ground · ${formatFt((p.groundM!! - (p.geoidM ?: 0.0)) / FT_TO_M)} MSL"
            return Triple(lines.joinToString("\n"), 0.0, false)
        }
        if (p.basis == "agl") {
            lines += "${formatFt(p.anchor.aglFt!!)} AGL · no planned MSL"
            return Triple(lines.joinToString("\n"), measured, false)
        }
        lines += "${formatFt(p.anchor.mslFt!!)} MSL · " + if (measured != null) "${formatFt(measured)} AGL" else "AGL n/a"
        // The planner converts AGL with its own ground: where that is off, the route will not fly at the AGL the planner showed.
        val planned = p.anchor.aglFt
        val mismatch = planned != null && measured != null && abs(measured - planned) > AGL_MISMATCH_FT
        if (mismatch) lines += "planned ${formatFt(planned)} AGL"
        return Triple(lines.joinToString("\n"), measured, mismatch)
    }

    // Height at a distance along the route, straight between AMPS points.
    private fun heightAt(anchors: List<Placed>, distance: Double): Double? {
        if (anchors.isEmpty()) return null
        if (distance <= anchors[0].anchor.distance) return anchors[0].heightM
        val last = anchors.last()
        if (distance >= last.anchor.distance) return last.heightM
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (distance < a.anchor.distance || distance > b.anchor.distance) continue
            if (a.heightM == null || b.heightM == null) return null
            val span = b.anchor.distance - a.anchor.distance
            if (span <= 0) return b.heightM
            return a.heightM + (b.heightM - a.heightM) * ((distance - a.anchor.distance) / span)
        }
        return null
    }

    // Consecutive indices for which [keep] holds, as [start, end) runs of two or more.
    private fun runs(count: Int, keep: (Int) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in 0..count) {
            if (i < count && keep(i)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= 2) out += start to i
                start = -1
            }
        }
        return out
    }

    /**
     * Feet as the web writes them (`${Math.round(ft).toLocaleString("en-US")}'`): rounded halves up, thousands with commas, by hand (no locale);
     * and as JavaScript does, a value that rounds to zero from below is `-0` (its `Math.round` keeps the sign, and `toLocaleString` shows it).
     */
    public fun formatFt(ft: Double): String {
        if (ft < 0 && ft >= -0.5 || (ft == 0.0 && 1.0 / ft < 0)) return "-0'"
        val rounded = floor(ft + 0.5).toLong()
        val digits = abs(rounded).toString()
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return (if (rounded < 0) "-" else "") + grouped + "'"
    }
}
