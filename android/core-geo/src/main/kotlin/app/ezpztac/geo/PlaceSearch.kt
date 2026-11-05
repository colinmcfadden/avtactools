package app.ezpztac.geo

import app.ezpztac.model.LatLon
import kotlin.math.abs

/** What typing a place into a search or target field means. */
public sealed interface PlaceResult {
    /** A position, with how it was read, so the person can see the app understood them. */
    public data class Found(val at: LatLon, val description: String) : PlaceResult

    public data class NotUnderstood(val message: String) : PlaceResult
}

/**
 * One place a text could be: the point, its grid as a target keeps it, its lat/long in words ([PlaceSearch.formatLatLong]) and how the
 * text was read ("MGRS grid, to 1 m", "Read as decimal degrees").
 */
public data class PlaceChoice(val lat: Double, val lon: Double, val mgrs: String, val latLong: String, val how: String) {
    val at: LatLon get() = LatLon(lat, lon)

    /** True for a grid that was typed, false for a lat/long read to a grid. */
    val isGrid: Boolean get() = how.startsWith("MGRS")
}

/**
 * What a text in the search could mean, as it is typed: the [choices] (none, one, or an unsigned pair's readings) and a [message] in
 * words when there is not exactly one (why not yet, or why two). Nothing typed is no choices and no message.
 */
public data class PlaceReading(val choices: List<PlaceChoice>, val message: String?)

/**
 * Reads a place the way the web's target field does: an MGRS grid (any even digit count, spaces and case ignored), or a coordinate in
 * any format [CoordinateParser] knows. A grid is tried first, and a coordinate never reinterprets one (the parser refuses grids), so
 * typing a grid is never read as something else.
 *
 * [read] is the web's `readTarget` (`frontend/src/utils/targetReading.js`), held to `contracts/fixtures/coords/reading.json`, with
 * [nearestFirst] and [formatLatLong]. Everything is computed on the device.
 */
public object PlaceSearch {
    public object Messages {
        public const val INCOMPLETE: String = "Keep typing: a grid, or a latitude and a longitude."
        public const val UNREADABLE: String =
            "Type a grid, such as 16S GC 28864 55349, or a latitude and longitude, such as 34.5457, -84.1234."
        public const val PAIR: String =
            "That is not a latitude and a longitude: give two values, each in degrees, degrees and minutes, or degrees, minutes and seconds."
        public const val MINUTES: String = "Minutes and seconds are under 60."
        public const val LATITUDE: String = "A latitude is at most 90°, north or south."
        public const val LONGITUDE: String = "A longitude is at most 180°, east or west."
        public const val SAME_AXIS: String = "Both values say the same axis: give one latitude (N or S) and one longitude (E or W)."
        public const val GRID_SQUARE: String = "Keep typing the grid: its 100 km square is two letters."
        public const val GRID_DIGITS: String = "Keep typing: add the grid's easting and northing."
        public const val GRID_ODD: String = "Keep typing: a grid has as many northing digits as easting digits."
        public const val GRID_OFF: String = "That grid is not on the map."
        public const val GRID_LONG: String = "A grid has at most ten digits: five for the easting and five for the northing."
        public const val GRID_BAND: String = "That grid's band letter does not hold that square: check the letter after the zone."
        public const val BEYOND: String = "That point is beyond the MGRS grid: north of 84° N or south of 80° S."
        public const val EAST_OR_WEST: String = "The longitude has no sign or E/W, so it could be east or west. Choose one."
        /** Android's own, for a field with no list to choose from ([resolve]): the web has none. */
        public const val EAST_OR_WEST_TYPED: String =
            "The longitude has no sign or E/W, so it could be east or west: add W (or a minus sign) for west, or E for east."
        public const val EMPTY: String = "Enter a grid or a coordinate."
    }

    private val PROBLEMS = mapOf(
        "incomplete" to Messages.INCOMPLETE,
        "unreadable" to Messages.UNREADABLE,
        "pair" to Messages.PAIR,
        "minutes" to Messages.MINUTES,
        "latitude" to Messages.LATITUDE,
        "longitude" to Messages.LONGITUDE,
        "same-axis" to Messages.SAME_AXIS,
    )

    // A grid's precision in words, by the digits in each half.
    private val PRECISION = mapOf(1 to "10 km", 2 to "1 km", 3 to "100 m", 4 to "10 m", 5 to "1 m")

    private val FORMAT_WORDS = mapOf(
        "decimal" to "decimal degrees",
        "ddm" to "degrees and decimal minutes",
        "dms" to "degrees, minutes and seconds",
        "packed-ddm" to "packed degrees and minutes (DDMM.mmm)",
        "packed-dms" to "packed degrees, minutes and seconds (DDMMSS)",
        "mixed" to "degrees, in two notations",
    )

    // JavaScript's \s, which the web's MGRS patterns ignore: wider than Kotlin's isWhitespace (the byte order mark).
    private val JS_SPACES = Regex("""[\t\n\u000B\f\r    -     　﻿]""")
    // Both cases spelled out, never IGNORE_CASE, which folds letters JavaScript's /i does not.
    private val GRID_PREFIX = Regex("""^[0-9]{1,2}[A-Za-z]{3}""")
    private val HALF_SQUARE = Regex("""[0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z]""")
    private val TOO_LONG = Regex("""[0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z]{2}[0-9]{11,}""")
    // Quotes, brackets and a sentence's last mark round a grid pasted from a message.
    private val WRAP_START = Regex("""^["'“”‘’(\[]+""")
    private val WRAP_END = Regex("""["'“”‘’)\].,;:]+$""")

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    // How far past its band's edge a square's centre may fall: a coarse square straddles the edge.
    private const val BAND_MARGIN_DEG = 0.5

    private fun unwrap(text: String) = CoordinateParser.jsTrim(text.replace(WRAP_START, "").replace(WRAP_END, ""))

    private fun squeeze(text: String) = text.replace(JS_SPACES, "")

    /** A lat/long in words: "34.54568° N, 84.12346° W". Five places, rounded as JavaScript's `toFixed`; a value that rounds to zero is N or E. */
    public fun formatLatLong(lat: Double, lon: Double): String = "${hemisphere(lat, "N", "S")}, ${hemisphere(lon, "E", "W")}"

    private fun hemisphere(value: Double, positive: String, negative: String): String {
        val text = CoordinateParser.toFixed(abs(value), 5)
        val letter = if (value < 0 && text.toDouble() != 0.0) negative else positive
        return "$text° $letter"
    }

    private fun choiceAt(lat: Double, lon: Double, mgrs: String, how: String) = PlaceChoice(lat, lon, mgrs, formatLatLong(lat, lon), how)

    private fun readGrid(typed: String): PlaceReading {
        val squeezed = squeeze(typed)
        val grid = MgrsConverter.parse(squeezed)
        if (grid == null) {
            val digits = squeezed.replace(GRID_PREFIX, "")
            val message = when {
                digits.isEmpty() -> Messages.GRID_DIGITS
                digits.length % 2 != 0 -> Messages.GRID_ODD
                else -> Messages.GRID_OFF
            }
            return PlaceReading(emptyList(), message)
        }
        val point = MgrsConverter.toLatLon(grid)
        // A square the zone has can still name a place past the grid's ends (16X GC is at 87° N): it is no target.
        // Nor is a zone Svalbard does not have (32X, 34X, 36X).
        if (point == null || MgrsConverter.toMgrs(point.lat, point.lon) == null) return PlaceReading(emptyList(), Messages.GRID_OFF)
        if (grid.band == 'X' && grid.zone in listOf(32, 34, 36)) return PlaceReading(emptyList(), Messages.GRID_OFF)
        // A band letter mistyped (16T for 16S) still names a square, 900 km from the one meant: refuse a square whose centre is not in or
        // beside the band typed.
        val south = BANDS.indexOf(grid.band) * 8.0 - 80
        val north = if (grid.band == 'X') 84.0 else south + 8
        if (point.lat < south - BAND_MARGIN_DEG || point.lat > north + BAND_MARGIN_DEG) return PlaceReading(emptyList(), Messages.GRID_BAND)
        val zone = grid.zone.toString().padStart(2, '0')
        val mgrs = "$zone${grid.band} ${grid.square} ${grid.easting} ${grid.northing}"
        val precision = PRECISION[grid.easting.length] ?: "under 1 m"
        return PlaceReading(listOf(choiceAt(point.lat, point.lon, mgrs, "MGRS grid, to $precision")), null)
    }

    /** What [text] could mean: see the object's head. */
    public fun read(text: String?): PlaceReading {
        val typed = CoordinateParser.jsTrim(text ?: "")
        if (typed.isEmpty()) return PlaceReading(emptyList(), null)

        val unwrapped = unwrap(typed)
        if (CoordinateParser.looksLikeMgrs(unwrapped)) return readGrid(unwrapped)
        // A zone, band and one square letter is a grid half typed ("16S G").
        if (HALF_SQUARE.matches(squeeze(typed))) return PlaceReading(emptyList(), Messages.GRID_SQUARE)
        if (TOO_LONG.matches(squeeze(typed))) return PlaceReading(emptyList(), Messages.GRID_LONG)

        val reading = CoordinateParser.read(typed)
        // "34S" is a band being typed as much as a latitude: "incomplete" says both.
        val parsed = reading.parsed ?: return PlaceReading(emptyList(), PROBLEMS[reading.problem] ?: Messages.UNREADABLE)

        val how = "Read as ${FORMAT_WORDS[parsed.format] ?: "degrees"}"
        fun at(lat: Double, lon: Double): PlaceChoice? =
            MgrsConverter.toMgrs(lat, lon)?.let { choiceAt(lat, lon, it.toString(), how) }
        val literal = at(parsed.lat, parsed.lon) ?: return PlaceReading(emptyList(), Messages.BEYOND)

        // With no sign or E/W on the longitude (whatever the latitude says), it is east as written, but crews west of Greenwich drop the
        // minus all the time: offer both rather than guess. Latitude first stays the rule (see CoordinateParser).
        if (!reading.signed && parsed.lon != 0.0) {
            val west = at(parsed.lat, -parsed.lon)
            // 180° east is 180° west, and a longitude that rounds to 0° reads the same both ways: one place to offer.
            if (west != null && west.latLong != literal.latLong && west.mgrs != literal.mgrs) {
                return PlaceReading(listOf(literal, west), Messages.EAST_OR_WEST)
            }
        }
        return PlaceReading(listOf(literal), null)
    }

    /**
     * The [choices] nearest [near] (where the map is) first, so the reading most likely meant is offered first. Stable: equals keep their
     * order. Flat distance in degrees, the longitude the short way round, is enough to rank an east and a west reading.
     */
    public fun nearestFirst(choices: List<PlaceChoice>, near: LatLon?): List<PlaceChoice> {
        if (near == null || !near.lat.isFinite() || !near.lon.isFinite() || choices.size < 2) return choices
        fun distance(c: PlaceChoice): Double {
            val dLon = abs(jsRemainder(c.lon - near.lon + 540, 360.0) - 180)
            return (c.lat - near.lat) * (c.lat - near.lat) + dLon * dLon
        }
        return choices.withIndex().sortedWith(compareBy({ distance(it.value) }, { it.index })).map { it.value }
    }

    // JavaScript's %, which keeps the dividend's sign (Kotlin's rem does too; named so it reads as the port it is).
    private fun jsRemainder(a: Double, b: Double) = a.rem(b)

    /**
     * The one place a typed grid or coordinate is, for a field with no list under it (a point moved to a typed grid): [PlaceResult.Found]
     * for exactly one choice, else what is wrong in words. An unsigned pair is not guessed at: the person is asked to say east or west.
     */
    public fun resolve(text: String): PlaceResult {
        val reading = read(text)
        val only = reading.choices.singleOrNull()
        if (only != null) return PlaceResult.Found(only.at, only.how)
        val message = when {
            reading.choices.size > 1 -> Messages.EAST_OR_WEST_TYPED
            else -> reading.message ?: Messages.EMPTY
        }
        return PlaceResult.NotUnderstood(message)
    }
}
