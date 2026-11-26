package app.ezpztac.planning

import app.ezpztac.model.JsNumber
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** What a leg's doghouse says: the point flown to in its roof, then the true course, the time as minutes and seconds, the length in km and the airspeed. */
public data class LegDoghouseBox(
    val id: String,
    val heading: String,
    val minutes: String,
    val seconds: String,
    val distance: String,
    val airspeed: String,
)

/** Where a leg's doghouse stands from its line, in dp in the box's own frame (turned to the leg's course): [x] across to the right of the way flown, [y] back along it. */
public data class LegOffset(val x: Double, val y: Double)

/**
 * A doghouse on one leg of a route: its place ([lat], [lon]), which way it is turned ([rotation], the leg's true course, 0 with none), how far it stands
 * from the line ([offset]), the leg's length on the screen at Leaflet's zoom 0 in dp ([lengthPx0], doubled each zoom), and what it says ([box]).
 */
public data class LegDoghouse(
    val key: String,
    val lat: Double,
    val lon: Double,
    val rotation: Double,
    val offset: LegOffset,
    val lengthPx0: Double,
    val box: LegDoghouseBox,
)

/**
 * A doghouse on each leg of a route, between two of its named points (a checkpoint, the SP or RP, the target), as a crew draws them on a strip map:
 * the web's `feature/routeDoghouses/routeDoghouses.js`, held to `contracts/fixtures/routes/doghouses.json`. What it says is the route's plan
 * ([RouteCalc.computeRoutePlan]), never stored. It stands at the middle of the leg's longest straight stretch, clear of the line on the right of the
 * way it is flown, turned to that way; where the leg is too short on the screen to hold it, it is drawn smaller, down to a floor ([scaleAt]).
 */
public object RouteDoghouses {
    /** The leg on the screen, in dp, that holds its doghouse at full size. */
    public const val FULL_LEG_PX: Double = 160.0

    /** The smallest it is drawn on the screen. */
    public const val MIN_SCALE: Double = 0.4

    /** The smallest it is drawn in a mission overlay (the web's; no overlay is made here). */
    public const val MIN_EXPORT_SCALE: Double = 0.75

    /** The box's half-length along the leg and half-width across it, and the gap kept between it and the line, in dp. */
    public const val HALF_LENGTH_PX: Double = 58.0
    public const val HALF_WIDTH_PX: Double = 30.0
    public const val GAP_PX: Double = 14.0

    private const val NM_TO_KM = 1.852
    private const val MAX_LAT = 85.0511287798

    private class Pixel(val x: Double, val y: Double)

    /** Leaflet's projection at zoom 0 (the web's `project0`). */
    private fun project0(lat: Double, lon: Double): Pixel {
        val s = sin(max(-MAX_LAT, min(MAX_LAT, lat)) * PI / 180)
        return Pixel(((lon + 180) / 360) * 256, (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * 256)
    }

    private fun latOf(y: Double): Double = atan(sinh(PI * (1 - (2 * y) / 256))) * 180 / PI

    private fun lonOf(x: Double): Double = (x / 256) * 360 - 180

    /** `"CP3"` for `".CP3"`: a point's name as its label on the map has it (one leading dot taken off, then JavaScript's trim). */
    public fun roofName(name: String?): String = JsNumber.trimJs((name ?: "").removePrefix("."))

    private fun wholeText(value: Double): String = RouteCalc.jsRound(value).toLong().toString()

    /** A leg's time as the doghouse's two halves, `"07"` and `"05"` for 425 s; `"--"` for each with no time. */
    public fun legTime(seconds: Double?): Pair<String, String> {
        if (seconds == null || !seconds.isFinite()) return "--" to "--"
        val total = RouteCalc.jsRound(seconds).toLong()
        return floor(total / 60.0).toLong().toString().padStart(2, '0') to (total % 60).toString().padStart(2, '0')
    }

    /** A true course as the route panel writes it: `"045"`; `"---"` with none. */
    public fun courseText(degrees: Double?): String = if (degrees != null && degrees.isFinite()) wholeText(degrees).padStart(3, '0') else "---"

    /**
     * Where the box's middle stands from the line, in dp in the box's own frame: on the right of the stretch it stands beside, far enough that the
     * box, turned to the course, clears the line however far the stretch runs from the course.
     */
    public fun sideOffset(courseDeg: Double, stretchDeg: Double): LegOffset {
        val off = (stretchDeg - courseDeg) * PI / 180
        val reach = HALF_LENGTH_PX * abs(sin(off)) + HALF_WIDTH_PX * abs(cos(off)) + GAP_PX
        fun round(value: Double) = RouteCalc.jsRound(value * 10) / 10 + 0.0
        return LegOffset(round(reach * cos(off)), round(reach * sin(off)))
    }

    /**
     * How large a leg's doghouse is drawn at a Leaflet [zoom] (MapLibre's plus one), from 1 (its leg holds it) down to [floor], to a hundredth so a
     * pan does not set it again. A zoom that is no number draws it whole.
     */
    public fun scaleAt(lengthPx0: Double, zoom: Double, floor: Double = MIN_SCALE): Double {
        if (!zoom.isFinite()) return 1.0
        val room = lengthPx0 * 2.0.pow(zoom) / FULL_LEG_PX
        return RouteCalc.jsRound(min(1.0, max(floor, room)) * 100) / 100
    }

    /** Each leg's doghouse for [routes] (a route hidden on the map has none). [today] is the planner's (clock times only). */
    public fun of(routes: List<SketchRoute>, today: LocalDate = LocalDate.now()): List<LegDoghouse> {
        val doghouses = ArrayList<LegDoghouse>()
        for (route in routes) {
            if (!route.visible) continue
            val points = route.points
            if (!points.all { it.lat.isFinite() && it.lon.isFinite() }) continue
            val named = points.indices.filter { points[it].kind != RoutePoint.KIND_SHAPING && !points[it].id.isNullOrEmpty() }
            if (named.size < 2) continue
            val plan = RouteCalc.computeRoutePlan(points, route.plan, route.elevations, today)
            plan.legs.forEachIndexed { i, leg ->
                if (i + 1 >= named.size) return@forEachIndexed
                val from = named[i]
                val to = named[i + 1]
                val pixels = (from..to).map { project0(points[it].lat, points[it].lon) }
                var length = 0.0
                var longest = -1.0
                var atX = pixels[0].x
                var atY = pixels[0].y
                var bearing = 0.0
                for (k in 1 until pixels.size) {
                    val a = pixels[k - 1]
                    val b = pixels[k]
                    val stretch = hypot(b.x - a.x, b.y - a.y)
                    length += stretch
                    if (stretch > longest) {
                        longest = stretch
                        atX = (a.x + b.x) / 2
                        atY = (a.y + b.y) / 2
                        bearing = atan2(b.x - a.x, a.y - b.y) * 180 / PI
                    }
                }
                if (!(length > 0)) return@forEachIndexed
                val rotation = if (leg.courseTrueDeg.isFinite()) leg.courseTrueDeg else 0.0
                val (minutes, seconds) = legTime(leg.timeSec)
                val speed = leg.airspeed.value
                doghouses += LegDoghouse(
                    key = "${route.id}:$i:${leg.fromId}:${leg.toId}",
                    lat = latOf(atY),
                    lon = lonOf(atX),
                    rotation = rotation,
                    offset = sideOffset(rotation, bearing),
                    lengthPx0 = length,
                    box = LegDoghouseBox(
                        id = roofName(points[to].name).ifEmpty { roofName(leg.toName) },
                        heading = courseText(leg.courseTrueDeg),
                        minutes = minutes,
                        seconds = seconds,
                        distance = if (leg.distNm.isFinite()) JsNumber.toFixed(leg.distNm * NM_TO_KM, 1) else "--",
                        airspeed = if (speed.isFinite()) wholeText(speed) else "--",
                    ),
                )
            }
        }
        return doghouses
    }
}
