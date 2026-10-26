package app.ezpztac.brief

import app.ezpztac.brief.BriefText.durationText
import app.ezpztac.brief.BriefText.headingText
import app.ezpztac.brief.BriefText.nmText
import app.ezpztac.brief.BriefText.plural
import app.ezpztac.brief.BriefText.pointName
import app.ezpztac.model.PlanPoint
import app.ezpztac.model.RoutePoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The sphere the web's compile.js measures on (routeCalc's `distanceNm` and `trueCourseDeg`, 3,440.065 nm), with [StrictMath]'s trigonometry
 * and V8's own `Math.hypot`. StrictMath is fdlibm, the same bits on the JVM and on every Android device (java.lang.Math is each platform's own);
 * V8's sin, cos, asin, acos and atan2 differ from it in the last bit for about one argument in a hundred on an arm64 Mac, so the web's own last
 * bits may differ between machines. [hypot] is V8's algorithm and matches it bit for bit. None of this shows: a strip's numbers are rounded
 * (places to 1e-7°, a course to 0.01°), and a cut, a 500 m match or a compass point turns on it only within about 1e-12 of its boundary.
 */
internal object Sphere {
    private const val EARTH_RADIUS_NM = 3440.065
    private const val METRES_PER_NM = 1852.0

    private fun toRad(deg: Double) = deg * Math.PI / 180
    private fun toDeg(rad: Double) = rad * 180 / Math.PI

    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val a = StrictMath.sin(dLat / 2).let { it * it } +
            StrictMath.cos(toRad(lat1)) * StrictMath.cos(toRad(lat2)) * StrictMath.sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_NM * StrictMath.asin(StrictMath.sqrt(a))
    }

    fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = distanceNm(lat1, lon1, lat2, lon2) * METRES_PER_NM

    fun trueCourseDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = toRad(lon2 - lon1)
        val y = StrictMath.sin(dLon) * StrictMath.cos(toRad(lat2))
        val x = StrictMath.cos(toRad(lat1)) * StrictMath.sin(toRad(lat2)) - StrictMath.sin(toRad(lat1)) * StrictMath.cos(toRad(lat2)) * StrictMath.cos(dLon)
        return (toDeg(StrictMath.atan2(y, x)) + 360) % 360
    }

    /** `Math.hypot(a, b)` as V8 works it out (its MathHypot: scaled by the larger, Kahan-summed), which is not fdlibm's. */
    fun hypot(a: Double, b: Double): Double {
        val x = Math.abs(a)
        val y = Math.abs(b)
        if (x == Double.POSITIVE_INFINITY || y == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        if (x.isNaN() || y.isNaN()) return Double.NaN
        val max = maxOf(x, y)
        if (max == 0.0) return 0.0
        var sum = 0.0
        var compensation = 0.0
        for (v in doubleArrayOf(x, y)) {
            val n = v / max
            val summand = n * n - compensation
            val preliminary = sum + summand
            compensation = (preliminary - sum) - summand
            sum = preliminary
        }
        return Math.sqrt(sum) * max
    }
}

/**
 * Strip charts (docs/MISSION_BRIEF.md §2.6): each route of the flow along its course, cut into strips a kneeboard holds, as data the server draws
 * (it only projects, turns and writes). The web's compile.js strips section, function by function, held to `compile.json`'s strips exactly: the
 * web rounds places to 1e-7° and a course to 0.01° so that a port's last bits of trigonometry never show. The MGRS grid
 * is not here: it is added when the PDF is asked for (the web's stripGrid.js, `strip-grid.json`; Android's is B8's).
 */
internal object StripCharts {
    /** A strip as printed: 5.5 by 21.5 cm, three to a page, the direction of flight up. */
    const val WIDTH_CM: Double = 5.5
    const val LENGTH_CM: Double = 21.5

    /** The scales a strip is drawn at; the first is used unless the brief says another. */
    val SCALES: List<Double> = listOf(100000.0, 250000.0)

    /** More strips than this are not made: a brief is a packet, not an atlas. */
    const val MAX_STRIPS: Int = 60

    /** A strip carries on into the next leg only when that leg keeps within this many degrees of the strip's course. */
    private const val MAX_CARRY_TURN = 20.0

    private const val R_METRES = 3440.065 * 1852
    private const val RAD = Math.PI / 180

    private fun round7(v: Double) = Js.round(v * 1e7) / 1e7

    private class Dims(val length: Double, val width: Double, val margin: Double, val usable: Double, val maxCross: Double)

    /** Ground sizes of a strip at a scale: its length and width, the margin kept at each end, and how far off its axis the route may run. */
    private fun dims(scale: Double): Dims {
        val length = (LENGTH_CM / 100) * scale
        val width = (WIDTH_CM / 100) * scale
        val margin = length * 0.05
        return Dims(length, width, margin, length - 2 * margin, width * 0.3)
    }

    /** A place on the route: its position along the route's own points ([pos]: whole at a point, between at a cut). */
    private class Place(val lat: Double, val lon: Double, val pos: Double)

    private class Local(val x: Double, val y: Double)

    /** A place as metres east and north of an origin, on a plane fitted at their middle latitude. */
    private fun localOf(origin: Place, lat: Double, lon: Double): Local {
        val mid = ((origin.lat + lat) / 2) * RAD
        return Local((lon - origin.lon) * RAD * R_METRES * StrictMath.cos(mid), (lat - origin.lat) * RAD * R_METRES)
    }

    /** The place `x` metres east and `y` north of an origin (the inverse of [localOf]). */
    private fun placeOf(origin: Place, x: Double, y: Double): Pair<Double, Double> {
        val lat = origin.lat + y / R_METRES / RAD
        val mid = ((origin.lat + lat) / 2) * RAD
        return lat to origin.lon + x / (R_METRES * maxOf(1e-9, StrictMath.cos(mid))) / RAD
    }

    private class Axis(val ux: Double, val uy: Double, val chord: Double, val none: Boolean)

    /**
     * The direction from a piece's first point to its last, as a unit vector; its first real movement while it has no length; and, for a piece
     * that has not moved a metre yet, `none` (pointing north, which nothing then relies on).
     */
    private fun axisOf(local: List<Local>): Axis {
        val end = local.last()
        val chord = Sphere.hypot(end.x, end.y)
        val ahead = if (chord >= 1) end else local.firstOrNull { Sphere.hypot(it.x, it.y) >= 1 }
            ?: return Axis(0.0, 1.0, chord, none = true)
        val len = Sphere.hypot(ahead.x, ahead.y)
        return Axis(ahead.x / len, ahead.y / len, chord, none = false)
    }

    private fun metres(a: Place, b: Place) = Sphere.metresBetween(a.lat, a.lon, b.lat, b.lon)

    /**
     * Whether a run of the route fits one strip: no longer than the strip less its margins, never more than `maxCross` off the line from its start
     * to its end (labels need the room either side), and never going back along it (the direction of flight stays up the strip).
     */
    private fun fits(piece: List<Place>, dims: Dims): Boolean {
        val origin = piece[0]
        val local = piece.map { localOf(origin, it.lat, it.lon) }
        val axis = axisOf(local)
        if (axis.chord > dims.usable) return false
        var before = 0.0
        for (q in local) {
            val along = q.x * axis.ux + q.y * axis.uy
            val cross = q.x * axis.uy - q.y * axis.ux
            if (!(Math.abs(cross) <= dims.maxCross && along >= before - 1 && along <= dims.usable)) return false
            before = along
        }
        return true
    }

    private fun between(a: Place, b: Place, t: Double) = Place(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, a.pos + (b.pos - a.pos) * t)

    /** How far, in degrees, the leg from a piece's end to [next] turns off the piece's own course; none for a piece with no course yet. */
    private fun turnAt(piece: List<Place>, next: Place): Double {
        val origin = piece[0]
        val axis = axisOf(piece.map { localOf(origin, it.lat, it.lon) })
        if (axis.none) return 0.0
        val from = piece.last()
        val leg = localOf(from, next.lat, next.lon)
        val len = Sphere.hypot(leg.x, leg.y)
        if (len < 1) return 0.0
        return StrictMath.acos(maxOf(-1.0, minOf(1.0, (leg.x * axis.ux + leg.y * axis.uy) / len))) / RAD
    }

    private class Cut(val pieces: List<MutableList<Place>>, val more: Boolean)

    /**
     * The route's path cut into strips: as far as each strip holds it, then the next from where that one ended, so a bend the strip cannot hold
     * starts a new one and a long leg runs on over several.
     */
    private fun cut(path: List<Place>, dims: Dims, budget: Int): Cut {
        val pieces = ArrayList<MutableList<Place>>()
        var piece = mutableListOf(path[0])
        var i = 1
        while (i < path.size) {
            // Past what a brief may hold (and what the person is told about), no more is cut: a far-flung point costs nothing.
            if (pieces.size >= budget) return Cut(pieces, more = true)
            val next = path[i]
            if (fits(piece + next, dims)) {
                piece.add(next)
                i += 1
                continue
            }
            val from = piece.last()
            if (piece.size == 1) {
                // A leg longer than a strip, on its own: cut into as few equal parts as the strip holds, so none is a stub.
                val parts = Math.ceil(metres(from, next) / (dims.usable * 0.995))
                if (parts <= 1) {
                    piece.add(next)
                    i += 1
                } else {
                    piece.add(between(from, next, 1 / parts))
                }
            } else if (turnAt(piece, next) <= MAX_CARRY_TURN) {
                // On along a leg that keeps the course, as far as still fits, unless that is next to nothing. A real turn ends the strip at the
                // turn point instead, so the next strip starts there.
                var lo = 0.0
                var hi = 1.0
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (fits(piece + between(from, next, mid), dims)) lo = mid else hi = mid
                }
                val leg = metres(from, next)
                var reach = leg * lo
                // Leave the rest of the leg at least a quarter of a strip, so the next strip is never a stub before a turn.
                if (reach < leg && leg - reach < dims.usable * 0.25) reach = leg - dims.usable * 0.25
                if (reach >= dims.usable * 0.05) piece.add(between(from, next, reach / leg))
            }
            pieces += piece
            piece = mutableListOf(piece.last())
        }
        if (piece.size >= 2) {
            // What is left past the last cut, if it is shorter than a strip's margin, is drawn in the margin of the strip before.
            val last = pieces.lastOrNull()
            val along = (1 until piece.size).fold(0.0) { sum, k -> sum + metres(piece[k - 1], piece[k]) }
            if (last != null && along < dims.margin) last.addAll(piece.drop(1)) else pieces += piece
        }
        return Cut(pieces, more = false)
    }

    /** Distance along the route's own points to a place on it ([pos]), and back. */
    private class Along(points: List<Place>) {
        private val sums = DoubleArray(points.size).also { s ->
            for (i in 1 until points.size) s[i] = s[i - 1] + metres(points[i - 1], points[i])
        }
        private val count = points.size

        fun at(pos: Double): Double {
            val i = minOf(count - 1, maxOf(0, Math.floor(pos).toInt()))
            if (i >= count - 1) return sums[count - 1]
            return sums[i] + (sums[i + 1] - sums[i]) * (pos - i)
        }

        fun posAt(metres: Double): Double {
            for (i in 1 until count) {
                if (metres <= sums[i] || i == count - 1) {
                    val span = sums[i] - sums[i - 1]
                    return i - 1 + (if (span > 0) minOf(1.0, maxOf(0.0, (metres - sums[i - 1]) / span)) else 0.0)
                }
            }
            return 0.0
        }
    }

    /** A longitude brought to within 180° of another, so places either side of the antimeridian are measured the short way. */
    private fun besideLon(lon: Double, near: Double) = lon + 360 * Js.round((near - lon) / 360)

    /** Whether a place lies on a strip: its rectangle from `margin` behind the start, along the axis, `width` across. */
    private fun onStrip(origin: Place, axis: Axis, dims: Dims, lat: Double, lon: Double): Boolean {
        val q = localOf(origin, lat, besideLon(lon, origin.lon))
        val along = q.x * axis.ux + q.y * axis.uy
        val cross = q.x * axis.uy - q.y * axis.ux
        return along >= -dims.margin && along <= dims.length - dims.margin && Math.abs(cross) <= dims.width / 2
    }

    /** A point of the route, as the strips read it. */
    private class RoutePlace(var lat: Double, var lon: Double, val pos: Int, val named: Boolean, val name: String?, val type: JsonElement?)

    /**
     * The strip charts of the routes in the flow ([routes]), with the LZ/PZs ([stops], those with a target) and [threats] that lie on each; a
     * route with fewer than two named points has none. [problem] is told what could not be drawn.
     */
    fun of(
        routes: List<RouteEntry>,
        stops: List<StopEntry>,
        threats: List<JsonObject>,
        scale: Double,
        problem: (String, String) -> Unit,
        altitudeOf: (PlanPoint) -> String?,
    ): List<JsonObject> {
        val dims = dims(scale)
        val strips = ArrayList<JsonObject>()
        routes.forEach { r ->
            val points = r.item.every.mapIndexed { pos, p ->
                val named = p.point.kind != RoutePoint.KIND_SHAPING && !p.point.id.isNullOrEmpty()
                RoutePlace(p.point.lat, p.point.lon, pos, named, p.point.name, p.raw["ptType"])
            }
            val namedAt = points.filter { it.named }
            val planned = r.item.result.points
            if (namedAt.size < 2 || planned.size < 2) return@forEach
            if (points.any { Math.abs(it.lat) > 84 || Math.abs(it.lon) > 720 }) {
                problem(r.ref, "${r.name} runs beyond 84° of latitude, past what the map and the MGRS grid show: it has no strip charts.")
                return@forEach
            }
            // Each place within 180° of longitude of the one before, so a route across the antimeridian is cut the short way.
            for (k in 1 until points.size) points[k].lon = besideLon(points[k].lon, points[k - 1].lon)
            val places = points.map { Place(it.lat, it.lon, it.pos.toDouble()) }
            val path = places.subList(namedAt.first().pos, namedAt.last().pos + 1)
            val flown = (1 until path.size).fold(0.0) { sum, k -> sum + metres(path[k - 1], path[k]) }
            if (flown < 1) return@forEach
            val allowed = MAX_STRIPS - strips.size
            val cut = cut(path, dims, allowed)
            val along = Along(places)
            val made = ArrayList<JsonObject>()
            cut.pieces.forEach { piece ->
                if (made.size >= allowed) return@forEach
                val origin = piece[0]
                val axis = axisOf(piece.map { localOf(origin, it.lat, it.lon) })
                val course = Js.round(((((StrictMath.atan2(axis.ux, axis.uy) / RAD) % 360) + 360) % 360) * 100) / 100 % 360
                val centreAlong = dims.length / 2 - dims.margin
                val centre = placeOf(origin, axis.ux * centreAlong, axis.uy * centreAlong)
                val first = piece.first().pos
                val last = piece.last().pos
                val legs = ArrayList<JsonObject>()
                for (k in 0 until namedAt.size - 1) {
                    val from = maxOf(first, namedAt[k].pos.toDouble())
                    val to = minOf(last, namedAt[k + 1].pos.toDouble())
                    if (!(to > from)) continue
                    val middle = along.posAt((along.at(from) + along.at(to)) / 2)
                    val i = minOf(places.size - 2, Math.floor(middle).toInt())
                    val at = between(places[i], places[i + 1], middle - i)
                    val leg = planned[k + 1]
                    legs += buildJsonObject {
                        put("from", pointName(namedAt[k].name, "PT"))
                        put("to", pointName(namedAt[k + 1].name, "PT"))
                        put("at", pair(round7(at.lat), round7(at.lon)))
                        put("course", headingText(leg.legCourseTrueDeg ?: Double.NaN))
                        put("distance", nmText(leg.legDistNm ?: Double.NaN))
                        put("time", leg.legTimeSec?.let(::durationText)?.let(::JsonPrimitive) ?: JsonNull)
                        put("altitude", altitudeOf(leg)?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
                made += buildJsonObject {
                    put("ref", r.ref)
                    put("name", r.name)
                    put("scale", Js.number(scale))
                    put("course", Js.number(course))
                    put("courseText", headingText(course))
                    put("centre", pair(round7(centre.first), round7(centre.second)))
                    put("length", Js.number(dims.length))
                    put("width", Js.number(dims.width))
                    put("path", JsonArray(piece.map { pair(round7(it.lat), round7(it.lon)) }))
                    put(
                        "points",
                        JsonArray(
                            namedAt.filter { it.pos >= first - 1e-9 && it.pos <= last + 1e-9 }.map { p ->
                                buildJsonObject {
                                    put("name", pointName(p.name, "PT"))
                                    put("type", Js.present(p.type)?.let(Js::normalized) ?: JsonNull)
                                    put("lat", Js.number(p.lat))
                                    put("lon", Js.number(p.lon))
                                }
                            },
                        ),
                    )
                    put("legs", JsonArray(legs))
                    put(
                        "stops",
                        JsonArray(
                            stops.filter { onStrip(origin, axis, dims, it.target!!.lat, it.target!!.lon) }.map { s ->
                                buildJsonObject {
                                    put("name", s.name)
                                    put("role", s.role)
                                    put("lat", Js.number(s.target!!.lat))
                                    put("lon", Js.number(besideLon(s.target!!.lon, origin.lon)))
                                }
                            },
                        ),
                    )
                    put(
                        "threats",
                        JsonArray(
                            threats.filter { onStrip(origin, axis, dims, Js.num(it["lat"])!!, Js.num(it["lon"])!!) }.map { t ->
                                buildJsonObject {
                                    put("name", Js.trim(Js.text(Js.present(t["name"]) ?: Js.present(t["label"]) ?: JsonPrimitive(""))).ifEmpty { "Threat" })
                                    put("lat", Js.number(Js.num(t["lat"])!!))
                                    put("lon", Js.number(besideLon(Js.num(t["lon"])!!, origin.lon)))
                                }
                            },
                        ),
                    )
                }
            }
            if (cut.more || made.size < cut.pieces.size) problem(r.ref, "Strip charts stop at $MAX_STRIPS strips: the rest of ${r.name} has none.")
            made.forEachIndexed { i, strip ->
                strips += JsonObject(
                    linkedMapOf<String, JsonElement>("id" to JsonPrimitive("${r.ref}#${i + 1}")) + strip +
                        mapOf("part" to JsonPrimitive(i + 1), "parts" to JsonPrimitive(made.size)),
                )
            }
        }
        return strips
    }

    /** What the packet's inventory says of the strips: "12 strip charts". */
    fun inventory(count: Int): String? = if (count > 0) plural(count, "strip chart", "strip charts") else null

    private fun pair(lat: Double, lon: Double): JsonArray = JsonArray(listOf(Js.number(lat), Js.number(lon)))
}
