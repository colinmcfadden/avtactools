package app.ezpztac.planning

import app.ezpztac.model.JsNumber
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor

/**
 * The slope profile: the ground along the landing heading through the LZ (the approach, the LZ and the departure) and across it, from the
 * lines the server measured through the LZ's middle (`profiles` of /api/terrain-analysis). The web's `feature/terrain/groundProfile.js`,
 * held to `contracts/fixtures/planning/profile.json`. Turning the landing doghouse picks another line, with no new request.
 */
public object SlopeProfile {
    private const val FEET_PER_METRE = 3.28084

    /** Under this many feet of rise or fall through the LZ, it is called level. */
    private const val LEVEL_FT = 1

    /**
     * One line the server measured, at [heading] (0..179): its heights in feet, null where the terrain has none, from behind the middle to
     * ahead of it, and where it crosses the boundary ([lz], metres from the middle, two numbers) or null.
     */
    public data class GroundLine(val heading: Int, val elevationsFt: List<Double?>, val lz: List<Double>?)

    /** The server's lines: heights every [stepM], one line every [headingStepDeg]. */
    public data class Ground(val stepM: Double, val halfLengthM: Double, val headingStepDeg: Int, val lines: List<GroundLine>)

    /**
     * The ground along one heading: [heading] (0..359, the line's own), [distancesFt] (behind the middle negative), [elevationsFt] (null where
     * there is none), [lz] in feet along it or null, and [changeFt] / [averageDeg]: how much the ground rises (positive) or falls through the LZ
     * and the slope that makes on average, null when an edge has no height. [words] is what it says in a sentence ([slopeProfileFor] sets it).
     */
    public data class Line(
        val heading: Int,
        val distancesFt: List<Double>,
        val elevationsFt: List<Double?>,
        val lz: List<Double>?,
        val changeFt: Double?,
        val averageDeg: Double?,
        val words: String = "",
    )

    /** The profile along the landing heading (approach to departure) and across it (left to right). */
    public data class Profile(val along: Line, val across: Line)

    private fun readable(ground: Ground?): Ground? {
        val g = ground ?: return null
        if (!g.stepM.isFinite() || g.stepM <= 0 || !g.halfLengthM.isFinite() || g.headingStepDeg <= 0 || g.lines.isEmpty()) return null
        return g
    }

    private fun heightAt(elevations: List<Double?>, stepM: Double, halfM: Double, atM: Double): Double? {
        val at = (atM + halfM) / stepM
        val i = floor(at).toInt()
        if (i < 0 || i >= elevations.size) return null
        if (i == elevations.size - 1) return elevations[i]
        val a = elevations[i] ?: return null
        val b = elevations[i + 1] ?: return null
        return a + (b - a) * (at - i)
    }

    /**
     * The ground along the line through the LZ's middle nearest [heading] (any whole number of degrees): the line `headingStepDeg` apart nearest
     * it (halves up; 180 or more is the opposite line, read backwards). Null when that line is not there or [ground] cannot be read.
     */
    public fun profileLine(ground: Ground?, heading: Int): Line? {
        val g = readable(ground) ?: return null
        val step = g.headingStepDeg
        val nearest = (((RouteCalc.jsRound(heading.toDouble() / step) * step).toInt() % 360) + 360) % 360
        val reversed = nearest >= 180
        val base = if (reversed) nearest - 180 else nearest
        val line = g.lines.firstOrNull { it.heading == base } ?: return null
        if (line.elevationsFt.size < 2) return null
        val heights = line.elevationsFt.map { h -> h?.takeIf { it.isFinite() } }
        val elevations = if (reversed) heights.reversed() else heights
        val half = g.stepM * (elevations.size - 1) / 2.0
        val distancesFt = elevations.indices.map { i -> (i * g.stepM - half) * FEET_PER_METRE }
        val span = line.lz?.takeIf { it.size == 2 && it.all(Double::isFinite) }
        val lzM = span?.let { if (reversed) listOf(-it[1], -it[0]) else listOf(it[0], it[1]) }
        var change: Double? = null
        var average: Double? = null
        if (lzM != null && lzM[1] > lzM[0]) {
            val entry = heightAt(elevations, g.stepM, half, lzM[0])
            val exit = heightAt(elevations, g.stepM, half, lzM[1])
            if (entry != null && exit != null) {
                change = exit - entry
                average = Math.toDegrees(atan2(abs(change), (lzM[1] - lzM[0]) * FEET_PER_METRE))
            }
        }
        return Line(nearest, distancesFt, elevations, lzM?.map { it * FEET_PER_METRE }, change, average)
    }

    private fun changeWords(line: Line): String {
        if (line.lz == null) return "the line misses the LZ"
        val change = line.changeFt ?: return "no height at an edge of the LZ"
        val feet = RouteCalc.jsRound(abs(change)).toLong()
        if (feet < LEVEL_FT) return "level through the LZ"
        return "${if (change > 0) "rises" else "falls"} $feet ft through the LZ, ${JsNumber.toFixed(line.averageDeg ?: 0.0, 1)}° on average"
    }

    private fun headingText(heading: Int) = "${heading.toString().padStart(3, '0')}°"

    /**
     * The slope profile for a landing heading ([heading], 0..359, `Doghouses.landingHeading`; null for none): along it (the approach behind the
     * middle, the departure ahead) and across it (the line 90° to its right, left to right), each with its sentence. Null with no heading, or
     * when either line is not there.
     */
    public fun slopeProfileFor(ground: Ground?, heading: Int?): Profile? {
        if (heading == null) return null
        val along = profileLine(ground, heading) ?: return null
        val across = profileLine(ground, heading + 90) ?: return null
        return Profile(
            along.copy(words = "Along ${headingText(along.heading)}: ${changeWords(along)}"),
            across.copy(words = "Across, left to right: ${changeWords(across)}"),
        )
    }
}
