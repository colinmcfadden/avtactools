package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import app.ezpztac.model.WeatherObservation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * What the mission summary says about a landing zone: its area, how many aircraft fit, and the call the slope tile makes. Ported from the
 * web's `MissionSummary.jsx` (`lzAreaAndCapacity`), `feature/terrain/slopeCall.js` (`slopeCallFor`) and `getPolygonArea` in
 * `utils/Helpers.js`, held to `contracts/fixtures/planning/summary.json`.
 */
public object LzSummary {
    /** The Earth's radius in feet, as the web uses it for areas. */
    private const val EARTH_RADIUS_FT = 20_902_231.0

    /**
     * The area of a polygon in square feet, by spherical excess (`getPolygonArea`). Fewer than three points is no area. Kept exactly as the
     * web has it, quirks included: a polygon that crosses the antimeridian is not unwrapped, so its longitude jump is taken at face value.
     */
    public fun polygonAreaSqFt(polygon: List<LatLon>?): Double {
        if (polygon == null || polygon.size < 3) return 0.0
        val toRadians = Math.PI / 180                                  // multiplied, as the web does, not divided (the last bit differs)
        var area = 0.0
        for (i in polygon.indices) {
            val j = (i + 1) % polygon.size
            val lat1 = polygon[i].lat * toRadians
            val lat2 = polygon[j].lat * toRadians
            val lon1 = polygon[i].lon * toRadians
            val lon2 = polygon[j].lon * toRadians
            area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
        }
        area = (area * EARTH_RADIUS_FT * EARTH_RADIUS_FT) / 2.0
        return abs(area)
    }

    /** [areaSqFt] is rounded the way JavaScript's `Math.round` does (halves up), as the tile shows it. */
    public data class AreaAndCapacity(val areaSqFt: Long, val capacity: Int)

    /**
     * The area and the number of [profile] aircraft that fit (a conservative square-grid estimate using that aircraft's own spacing, so a
     * Chinook LZ holds fewer than a Black Hawk one). Capacity is worked out from the unrounded area, as the web does.
     */
    public fun areaAndCapacity(polygon: List<LatLon>?, profile: AircraftProfile = AircraftProfile.FALLBACK): AreaAndCapacity {
        if (polygon == null) return AreaAndCapacity(0, 0)
        val area = polygonAreaSqFt(polygon)
        return AreaAndCapacity(floor(area + 0.5).toLong(), max(0, AircraftGeometry.capacityForArea(area, profile)))
    }

    public enum class SlopeLevel { SAFE, WARNING, DANGER }

    /** The UH-60's three slope limits, in the order a tie between them is named, with the words the tile uses for each. */
    public enum class SlopeLimit(public val words: String) { NOSE_UP("nose up"), NOSE_DOWN("nose down"), CROSS("cross slope") }

    /**
     * The steepest slope at one whole [heading] as the server measures it (an entry of `byHeading`), in degrees: nose up (the ground rising
     * ahead, the server's `noseHighMaxDeg`), nose down (`noseLowMaxDeg`) and across (`crossSlopeMaxDeg`). A value that is not a number (NaN)
     * judges nothing.
     */
    public data class HeadingSlope(val heading: Int, val noseUpDeg: Double, val noseDownDeg: Double, val crossDeg: Double)

    /**
     * The wind the limits are judged for: the steady speed in knots (a gust is not counted), the direction it blows from in degrees true
     * (METAR winds are true), 0..359 or null, and whether it is variable.
     */
    public data class SlopeWind(val speedKts: Double, val fromDeg: Int?, val variable: Boolean)

    /** The limits in whole degrees for a wind, how much each was reduced by, and whether a nose-down landing is not allowed at all. */
    public data class SlopeLimits(val noseUp: Int, val noseDown: Int, val cross: Int, val reductionDeg: Int, val noseDownProhibited: Boolean) {
        public fun of(limit: SlopeLimit): Int = when (limit) {
            SlopeLimit.NOSE_UP -> noseUp
            SlopeLimit.NOSE_DOWN -> noseDown
            SlopeLimit.CROSS -> cross
        }

        public companion object {
            /** The UH-60's slope landing limits with no wind (the owner's, a UH-60 pilot, 2026-10-13): 15° nose up, 6° nose down, 15° across. */
            public val CALM: SlopeLimits = SlopeLimits(noseUp = 15, noseDown = 6, cross = 15, reductionDeg = 0, noseDownProhibited = false)
        }
    }

    /**
     * What the slope tile says: [label] is its own word and [maxDeg] the steepest slope (0 when nothing was measured); [heading] is the heading
     * judged, null when none was; [limits] are those for the wind; [lines] are what it says under the slope, in smaller text: what was
     * judged at the landing heading, and the wind the limits are for.
     */
    public data class SlopeCall(
        val level: SlopeLevel,
        val label: String,
        val maxDeg: Double,
        val heading: Int? = null,
        val limits: SlopeLimits = SlopeLimits.CALM,
        val lines: List<String> = emptyList(),
    )

    /** Every full five knots of steady wind takes two degrees off each limit. */
    private const val KT_PER_STEP = 5
    private const val DEG_PER_STEP = 2

    /** Above this steady wind, in knots, a UH-60 may not land nose down at all. */
    public const val NOSE_DOWN_PROHIBITED_ABOVE_KT: Int = 15

    /** What an older server's analysis (no `byHeading`) says under the slope: no heading was judged. */
    public const val HEADINGS_NOT_CHECKED: String = "Heading limits not checked (server not updated)"

    /** What an analysis with no landing heading says under the slope: there is no heading to judge. */
    public const val NO_LANDING_HEADING: String = "No landing heading: limits not checked"

    /**
     * The wind for the limits from a weather report ([observation], the one the weather tiles show for this LZ/PZ): null when there is nothing
     * to go by (no report, or one with no speed), so the limits are not reduced and the tile says why.
     */
    public fun slopeWindOf(observation: WeatherObservation?): SlopeWind? {
        val speed = observation?.windSpeedKt ?: return null
        if (!speed.isFinite() || speed < 0) return null
        return SlopeWind(speed, observation.windFromDegrees?.let { ((it % 360) + 360) % 360 }, observation.windVariable)
    }

    /**
     * The limits for [wind]: each 2° less for every full 5 knots of steady wind and never below 0, and no nose down at all above 15 knots. With
     * no wind report (null) they are the calm-wind limits, not reduced.
     */
    public fun limitsFor(wind: SlopeWind?): SlopeLimits {
        val speed = wind?.speedKts
        if (speed == null || !(speed >= 0)) return SlopeLimits.CALM
        val reduction = DEG_PER_STEP * floor(speed / KT_PER_STEP).toInt()
        val prohibited = speed > NOSE_DOWN_PROHIBITED_ABOVE_KT
        fun reduced(limit: Int) = max(0, limit - reduction)
        return SlopeLimits(
            noseUp = reduced(SlopeLimits.CALM.noseUp),
            noseDown = if (prohibited) 0 else reduced(SlopeLimits.CALM.noseDown),
            cross = reduced(SlopeLimits.CALM.cross),
            reductionDeg = reduction,
            noseDownProhibited = prohibited,
        )
    }

    /**
     * `byHeading` as one entry per whole heading 0..359, each limit's slope in tenths of a degree (the server rounds to 0.1), in [SlopeLimit]'s
     * order. Null when it is not there (a server from before it) or not whole: a missing heading, or a slope that is not a number, judges
     * nothing rather than guess. A heading given twice counts once, the first.
     */
    private fun tableOf(byHeading: List<HeadingSlope>?): List<IntArray>? {
        if (byHeading == null) return null
        val table = arrayOfNulls<IntArray>(360)
        for (entry in byHeading) {
            if (entry.heading !in 0..359 || table[entry.heading] != null) continue
            val values = listOf(entry.noseUpDeg, entry.noseDownDeg, entry.crossDeg)
            if (!values.all { it.isFinite() && it >= 0 }) continue
            table[entry.heading] = IntArray(values.size) { RouteCalc.jsRound(values[it] * 10).toInt() }
        }
        return table.toList().takeIf { all -> all.all { it != null } }?.map { it!! }
    }

    private data class Excess(val limit: SlopeLimit, val tenths: Int)

    /** Each limit's excess at [slopes] in tenths of a degree (over it when above 0), and the worst of them, the first in [SlopeLimit] on a tie. */
    private fun worstOf(slopes: IntArray, limits: SlopeLimits): Excess = SlopeLimit.entries
        .map { Excess(it, slopes[it.ordinal] - limits.of(it) * 10) }
        .reduce { worst, next -> if (next.tenths > worst.tenths) next else worst }

    /** A slope is outside its limit only when it is greater than it, at the 0.1° the server reports: level ground (0.0) is never nose down. */
    private fun withinLimits(slopes: IntArray, limits: SlopeLimits) = worstOf(slopes, limits).tenths <= 0

    private fun onCompass(degrees: Int) = ((degrees % 360) + 360) % 360

    private fun headingText(heading: Int) = "${heading.toString().padStart(3, '0')}°"

    /** Tenths of a degree as the web's `toFixed(1)` writes them (never below 0 here). */
    private fun tenthsText(tenths: Int) = "${tenths / 10}.${tenths % 10}"

    /** A wind speed as a station gives it: whole knots without a decimal point, anything else to one place. */
    private fun knots(speed: Double) =
        if (speed == floor(speed) && speed < 1e15) speed.toLong().toString() else JsNumber.toFixed(speed, 1)

    /** What was judged at the landing heading: within limits, or the limit it is most over, with the slope there. */
    private fun landingWords(heading: Int, slopes: IntArray, limits: SlopeLimits): String {
        val worst = worstOf(slopes, limits)
        if (worst.tenths <= 0) return "Landing ${headingText(heading)}: within limits"
        return "Landing ${headingText(heading)}: ${worst.limit.words} ${tenthsText(slopes[worst.limit.ordinal])}°, limit ${limits.of(worst.limit)}°"
    }

    private fun windWords(wind: SlopeWind?, limits: SlopeLimits): String {
        if (wind == null) return "No wind report: limits not reduced for wind"
        if (wind.speedKts == 0.0) return "Wind calm: limits not reduced"
        val what = "Wind ${if (wind.variable) "variable " else ""}${knots(wind.speedKts)} kt"
        if (limits.reductionDeg == 0) return "$what: limits not reduced"
        val reduced = "$what: limits \u2212${limits.reductionDeg}°"
        return if (limits.noseDownProhibited) "$reduced, nose down prohibited above $NOSE_DOWN_PROHIBITED_ABOVE_KT kt" else reduced
    }

    /** The call from the steepest slope alone, when no heading can be judged: HEADING REQUIRED at 15° or more. */
    private fun byMaxOnly(maxDeg: Double): Pair<SlopeLevel, String> = when {
        maxDeg >= 15 -> SlopeLevel.WARNING to "HEADING REQUIRED"
        maxDeg > 10 -> SlopeLevel.WARNING to "CAUTION"
        else -> SlopeLevel.SAFE to "LANDING"
    }

    /**
     * The call the slope tile makes, the web's `slopeCallFor` (`feature/terrain/slopeCall.js`), from the analysis's steepest slope ([maxDeg],
     * null when nothing was measured) and its table by heading ([byHeading], null from a server before it), the flight data's landing heading
     * ([landingHeading], `Doghouses.landingHeading`) and the wind ([wind], [slopeWindOf]'s, null for no report):
     *
     * NO DATA with nothing measured; LIMIT EXCEEDED when a slope at the landing heading is over its limit for the wind; else CAUTION above 10°
     * of slope anywhere, and LANDING. With no heading to judge (a server that sends no table, or no landing heading) the steepest slope alone,
     * HEADING REQUIRED at 15° or more, and a line that says why. Everything is worked out from the table, so a turned doghouse or a new weather
     * report judges again with no request.
     */
    public fun slopeCall(maxDeg: Double?, byHeading: List<HeadingSlope>?, landingHeading: Int?, wind: SlopeWind?): SlopeCall {
        val limits = limitsFor(wind)
        if (maxDeg == null) return SlopeCall(SlopeLevel.SAFE, "NO DATA", 0.0, limits = limits)
        val table = tableOf(byHeading)
        if (table == null) {
            val (level, label) = byMaxOnly(maxDeg)
            return SlopeCall(level, label, maxDeg, limits = limits, lines = listOf(HEADINGS_NOT_CHECKED))
        }
        val heading = landingHeading?.let(::onCompass)
        if (heading == null) {
            val (level, label) = byMaxOnly(maxDeg)
            return SlopeCall(level, label, maxDeg, limits = limits, lines = listOf(NO_LANDING_HEADING))
        }
        val (level, label) = when {
            !withinLimits(table[heading], limits) -> SlopeLevel.DANGER to "LIMIT EXCEEDED"
            maxDeg > 10 -> SlopeLevel.WARNING to "CAUTION"
            else -> SlopeLevel.SAFE to "LANDING"
        }
        return SlopeCall(level, label, maxDeg, heading, limits, listOf(landingWords(heading, table[heading], limits), windWords(wind, limits)))
    }
}
