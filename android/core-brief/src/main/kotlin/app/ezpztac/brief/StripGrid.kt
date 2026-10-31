package app.ezpztac.brief

import app.ezpztac.geo.GridBounds
import app.ezpztac.geo.GridOptions
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.MgrsGrid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The MGRS 1 km grid on a brief's strip charts (docs/MISSION_BRIEF.md §2.6): the web's `feature/brief/stripGrid.js` function by function,
 * worked out with the grid code the map draws ([MgrsGrid], the web's `mgrsGrid.js`) and added to the compiled brief only when the PDF is
 * asked for ([withStripGrids]), so [CompileBrief] stays exact and cheap and the server only draws. Held to
 * `contracts/fixtures/brief/strip-grid.json` within the grid fixture's own tolerances: a line sampled with other points where a JVM's maths
 * library and a browser's decide a double's last digit differently, a line's end a millimetre off.
 */
public object StripGrid {
    private const val R_METRES = 3440.065 * 1852          // compileBrief's sphere
    private const val RAD = Math.PI / 180

    // Any ground scale between 100/70 and 1000/70 m a pixel makes the grid draw its 1 km lines; with this tolerance a line keeps within 2 m
    // of its true course.
    private const val GRID_MPP = 10.0
    private val GRID_OPTIONS = GridOptions(maxLines = 4000, maxPoints = 400_000, maxLabels = 0, tolerancePx = 0.2)

    // The squares a strip's route crosses are found from a point on it every this many metres.
    private const val SQUARE_STEP_M = 500.0

    private fun round7(v: Double) = Js.round(v * 1e7) / 1e7

    /** A strip's centre, course and ground size, read as the web reads them (a missing number is NaN there, which places nothing). */
    private class Frame(val lat0: Double, val lon0: Double, val course: Double, val length: Double, val width: Double)

    private fun frameOf(strip: JsonObject): Frame {
        val centre = strip["centre"] as? JsonArray
        fun at(i: Int) = Js.numberOf(centre?.getOrNull(i)) ?: Double.NaN
        return Frame(at(0), at(1), Js.numberOf(strip["course"]) ?: Double.NaN, Js.numberOf(strip["length"]) ?: Double.NaN, Js.numberOf(strip["width"]) ?: Double.NaN)
    }

    /** The place [along] metres up a strip's course and [across] to its right of its centre, as `[lat, lon]`. */
    private fun offsetFrom(f: Frame, along: Double, across: Double): DoubleArray {
        val c = f.course * RAD
        val x = along * sin(c) + across * cos(c)
        val y = along * cos(c) - across * sin(c)
        val lat = f.lat0 + y / R_METRES / RAD
        val mid = ((f.lat0 + lat) / 2) * RAD
        return doubleArrayOf(lat, f.lon0 + x / (R_METRES * max(1e-9, cos(mid))) / RAD)
    }

    /** A strip's four corners, bottom left first and round to the right, as `[lat, lon]`. */
    public fun stripCorners(strip: JsonObject): List<DoubleArray> {
        val f = frameOf(strip)
        val l = f.length / 2
        val w = f.width / 2
        return listOf(-l to -w, l to -w, l to w, -l to w).map { (along, across) -> offsetFrom(f, along, across) }
    }

    private fun metres(a: DoubleArray, b: DoubleArray): Double {
        val h = sin(((b[0] - a[0]) * RAD) / 2).let { it * it } +
            cos(a[0] * RAD) * cos(b[0] * RAD) * sin(((b[1] - a[1]) * RAD) / 2).let { it * it }
        return 2 * R_METRES * asin(sqrt(h))
    }

    /** The 100 km square a place is in (`16S GD`), or null where MGRS names none. */
    private fun squareAt(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        return MgrsConverter.toMgrs(lat, lon)?.format()?.split(" ")?.take(2)?.joinToString(" ")
    }

    private fun pointsOf(path: JsonElement?): List<DoubleArray> =
        (path as? JsonArray).orEmpty().map { p ->
            val pair = p as? JsonArray
            doubleArrayOf(Js.numberOf(pair?.getOrNull(0)) ?: Double.NaN, Js.numberOf(pair?.getOrNull(1)) ?: Double.NaN)
        }

    /** The 100 km squares (`16S GD`) a path crosses, in the order it crosses them. */
    public fun squaresAlong(path: List<DoubleArray>): List<String> {
        val squares = mutableListOf<String>()
        fun note(lat: Double, lon: Double) {
            val square = squareAt(lat, lon)
            if (square != null && squares.lastOrNull() != square) squares += square
        }
        path.forEachIndexed { i, p ->
            if (i > 0) {
                val from = path[i - 1]
                val distance = metres(from, p)
                // Math.floor of NaN is NaN, and a loop to NaN does not run.
                val steps = if (distance.isFinite()) floor(distance / SQUARE_STEP_M).toLong() else 0L
                for (k in 1..steps) {
                    val t = k.toDouble() / (steps + 1)
                    note(from[0] + (p[0] - from[0]) * t, from[1] + (p[1] - from[1]) * t)
                }
            }
            note(p[0], p[1])
        }
        return squares
    }

    /**
     * The 100 km squares on a strip that its route does not cross (a strip is 5.5 or 13.75 km wide, and may reach over a zone or square
     * line beside the route): the digits printed there belong to them, so the strip must name them too. Found from points over the strip,
     * a kilometre or so apart, in the order they are met from its bottom.
     */
    public fun squaresBeside(strip: JsonObject, along: List<String>): List<String> {
        val f = frameOf(strip)
        val seen = along.toMutableSet()
        val others = mutableListOf<String>()
        val steps = jsCeilAtLeast2(f.length / 1000)
        val across = jsCeilAtLeast2(f.width / 1000)
        for (i in 0..steps) {
            for (j in 0..across) {
                val place = offsetFrom(f, -f.length / 2 + (f.length * i) / steps, -f.width / 2 + (f.width * j) / across)
                val square = squareAt(place[0], place[1])
                if (square != null && seen.add(square)) others += square
            }
        }
        return others
    }

    // Math.max(2, Math.ceil(x)): NaN wins Math.max in JavaScript, and a loop to NaN does not run, so a strip with no size has no samples.
    private fun jsCeilAtLeast2(x: Double): Int = if (x.isNaN()) -1 else max(2.0, ceil(x)).toInt()

    /**
     * The 1 km grid over a strip: every easting and northing line crossing the box round its corners (the server clips them to the strip as
     * it turns it), with its principal digits (`66` for 766 000) and the zone it belongs to, the boundaries of grid zones as `zone` lines
     * with no digits, the squares its route crosses (`squares`) and any other square on it (`others`).
     */
    public fun stripGrid(strip: JsonObject): JsonObject {
        val corners = stripCorners(strip)
        val lats = corners.map { it[0] }
        val lons = corners.map { it[1] }
        val bounds = if ((lats + lons).all { it.isFinite() }) GridBounds(south = lats.min(), west = lons.min(), north = lats.max(), east = lons.max()) else null
        val grid = MgrsGrid.grid(bounds, GRID_MPP, GRID_OPTIONS)
        val lines = if (grid.spacing == 1000.0) {
            grid.lines.map { line ->
                buildJsonObject {
                    put("kind", JsonPrimitive(line.kind))
                    put("axis", JsonPrimitive(line.axis))
                    put("zone", line.zone?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("value", Js.number(line.value))
                    put("text", if (line.kind == "zone") JsonNull else JsonPrimitive(MgrsGrid.principalDigits(line.value, 1000.0)))
                    put("points", JsonArray(line.points.map { JsonArray(listOf(Js.number(round7(it.lat)), Js.number(round7(it.lon)))) }))
                }
            }
        } else {
            emptyList()
        }
        val squares = squaresAlong(pointsOf(strip["path"]))
        return buildJsonObject {
            put("lines", JsonArray(lines))
            put("squares", JsonArray(squares.map(::JsonPrimitive)))
            put("others", JsonArray(squaresBeside(strip, squares).map(::JsonPrimitive)))
        }
    }

    /** The compiled brief with its strips' grids added, for the PDF. Nothing else changes; a model with no strips comes back as it was. */
    public fun withStripGrids(model: JsonObject): JsonObject {
        val strips = model["strips"] as? JsonArray
        if (strips.isNullOrEmpty()) return model
        val gridded = JsonArray(strips.map { strip -> (strip as? JsonObject)?.let { JsonObject(it + ("grid" to stripGrid(it))) } ?: strip })
        return JsonObject(model + ("strips" to gridded))
    }
}
