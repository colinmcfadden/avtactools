package app.ezpztac.geo

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Reads a latitude/longitude out of free text, in whatever shape it arrives:
 * Google Maps, ForeFlight, a flight plan, a spreadsheet cell, a text message.
 *
 *     34.545678, -84.123456          decimal degrees
 *     N34.545678 W084.123456         decimal with hemispheres
 *     34°32.740'N 084°07.407'W       degrees + decimal minutes (aviation)
 *     34°32'44.4"N 84°07'24.4"W      degrees/minutes/seconds
 *     34 32 44.4 N, 84 07 24.4 W     the same, unpunctuated
 *     3432.740N 08407.407W           packed DDMM.mmm (flight plan)
 *     343244N 0840724W               packed DDMMSS
 *
 * A port of `frontend/src/utils/coordParse.js`, branch for branch, held to
 * `contracts/fixtures/coords/parse.json`. Anything that is a valid MGRS grid is
 * deliberately refused, so typing a grid is never reinterpreted as a coordinate.
 */
public object CoordinateParser {
    public data class Parsed(val lat: Double, val lon: Double, val format: String, val label: String)

    /**
     * What [read] makes of a text: [parsed] when it is a pair, with [signed] true when the text said which side of the meridian the
     * longitude is on (a sign on its first number, or its own hemisphere letter); else the [problem] that stops it, for the target field to say in words.
     * Problems, as the web's `readCoordinate` names them: "empty", "grid", "unreadable", "incomplete", "pair", "minutes", "latitude",
     * "longitude", "same-axis".
     */
    public data class Reading(val parsed: Parsed? = null, val signed: Boolean = false, val problem: String? = null)

    // JavaScript's \s (and String.trim) is wider than Java's: it includes the
    // no-break space, the Unicode spaces and the byte-order mark.
    private const val JS_SPACE = """\t\n\u000B\f\r    -     　﻿"""
    private val SPACES = Regex("[$JS_SPACE]+")
    private val EDGE_SPACES = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")

    // Band letters run C-X skipping I and O; 100 km square letters skip I and O
    // too. Matching the real grammar (rather than [A-Z]) keeps "34N 084W" from
    // being mistaken for a grid.
    private val MGRS_PATTERN = Regex("""[0-9]{1,2}[C-HJ-NP-Xc-hj-np-x][A-HJ-NP-Za-hj-np-z]{2}[0-9]{0,10}""")

    // Punctuation that appears in coordinates and never in a grid.
    private val COORDINATE_PUNCTUATION = Regex(
        """[.,;/°º˚∘′‵'´`″‶"“”+\-‐-―−\t\n]""",
    )

    // Both cases spelled out, never IGNORE_CASE: Kotlin's folds letters JavaScript's /i does not (ſ is s, the Kelvin sign is k).
    private val ANY_HEMISPHERE = Regex("[NSEWnsew]")
    // Any letter but a hemisphere's (the web's OTHER_LETTER), for looksLikeCoordinateText.
    private val OTHER_LETTER = Regex("[A-DF-MOPQRT-VXYZa-df-mopqrt-vxyz]")
    // The class parse uses to refuse words, applied once the hemisphere letters are taken out (so it may include E and S).
    private val NON_HEMISPHERE_LETTER = Regex("[A-MOP-VXYZa-mop-vxyz]")
    private val ANY_LETTER = Regex("[A-Za-z]")
    private val DIGIT_THEN_SPACE = Regex("[0-9][$JS_SPACE]")
    // [0-9], never \d: Android's \d (ICU) takes other scripts' digits, which JavaScript's does not (§17). A number may start with
    // its point (".5", "-.12"), as people write them.
    private val TOKEN = Regex("""([NSEWnsew])|([+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+))""")

    private enum class Axis(val max: Double) { LAT(90.0), LON(180.0) }
    private class Hemisphere(val axis: Axis, val sign: Int)

    private val HEMISPHERES = mapOf(
        'N' to Hemisphere(Axis.LAT, 1),
        'S' to Hemisphere(Axis.LAT, -1),
        'E' to Hemisphere(Axis.LON, 1),
        'W' to Hemisphere(Axis.LON, -1),
    )

    private val FORMAT_LABELS = mapOf(
        "decimal" to "decimal degrees",
        "ddm" to "degrees/decimal minutes",
        "dms" to "degrees/minutes/seconds",
        "packed-ddm" to "packed DDMM.mmm",
        "packed-dms" to "packed DDMMSS",
    )

    internal fun jsTrim(text: String): String = text.replace(EDGE_SPACES, "")

    /** True when the text is (or is becoming) an MGRS grid rather than a coordinate. */
    public fun looksLikeMgrs(text: String?): Boolean = MGRS_PATTERN.matches((text ?: "").replace(SPACES, ""))

    /**
     * A lenient, keystroke-by-keystroke check used to decide whether the target
     * input is being given a coordinate rather than a grid.
     *
     * Deliberately looser than [parse]: it has to say "yes" to half-typed text
     * like "34." or "34.5, -" so the field stops reformatting mid entry.
     *
     * Hemisphere letters and no other letter ("34N 84E", "34S") are a
     * coordinate, and so is a number followed by a space with no letter at all
     * ("34 ", "34 84"): a grid's digits come after its letters, and taking the
     * space away would make "34 84" impossible to type.
     */
    public fun looksLikeCoordinateText(text: String?): Boolean {
        val raw = text ?: ""
        if (jsTrim(raw).isEmpty()) return false
        if (looksLikeMgrs(raw)) return false
        if (COORDINATE_PUNCTUATION.containsMatchIn(raw)) return true
        if (DIGIT_THEN_SPACE.containsMatchIn(raw) && !ANY_LETTER.containsMatchIn(raw)) return true
        return ANY_HEMISPHERE.containsMatchIn(raw) && !OTHER_LETTER.containsMatchIn(raw)
    }

    /**
     * Folds the many ways coordinate punctuation gets typed or pasted down to a
     * plain ASCII form: degree/minute/second marks become spaces, exotic dashes
     * become hyphens, and separators become " , ": a break the tokenizer notes, so
     * "34.5, 84.1W" can be split where the comma was (see group).
     */
    private fun normalize(text: String): String = jsTrim(
        text
            .replace(Regex("""[°º˚∘]"""), " ")
            .replace(Regex("""[′‵'´`]"""), " ")
            .replace(Regex("""[″‶"“”]"""), " ")
            .replace(Regex("""[‐-―−]"""), "-")
            .replace(Regex("""[,;/|\t\n\r]+"""), " , "),
    )

    private sealed interface Token
    private class NumberToken(val raw: String, val value: Double, val negative: Boolean, val afterBreak: Boolean) : Token
    private class HemiToken(val letter: Char, val suffixLike: Boolean, val prefixLike: Boolean) : Token

    /**
     * Splits normalized text into ordered number and hemisphere tokens.
     *
     * Numbers keep their raw text: leading zeros are what distinguish a packed
     * "0840724" (DDDMMSS) from a plain 840724.
     */
    private fun tokenize(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var lastEnd = 0
        for (match in TOKEN.findAll(text)) {
            // A separator (a comma, slash, tab…) between this token and the last.
            val afterBreak = tokens.isNotEmpty() && text.substring(lastEnd, match.range.first).contains(',')
            lastEnd = match.range.last + 1
            val letter = match.groups[1]
            if (letter != null) {
                // Whether the letter is glued to the number before it ("34.5N") or
                // the one after it ("W084") is the only thing separating a suffix
                // from a prefix, and it decides which value the hemisphere applies to.
                val index = match.range.first
                val before = text.getOrNull(index - 1)
                val after = text.getOrNull(index + 1)
                tokens += HemiToken(
                    letter = letter.value[0].uppercaseChar(),
                    suffixLike = before != null && before in '0'..'9',
                    prefixLike = after != null && (after in '0'..'9' || after == '+' || after == '-' || after == '.'),
                )
            } else {
                val raw = match.groups[2]!!.value
                val value = raw.toDouble()
                if (!value.isFinite()) return null
                tokens += NumberToken(raw, value, raw.startsWith("-"), afterBreak)
            }
        }
        return tokens
    }

    private class Group(val numbers: MutableList<NumberToken> = mutableListOf(), var hemi: Char? = null, var prefix: Boolean = false)

    /**
     * Collects tokens into two coordinate groups.
     *
     * A hemisphere letter closes the group it belongs to, whether it was written
     * in front (`N34 30`) or behind (`34 30 N`). With no hemisphere letters at all
     * the numbers are simply split down the middle. A letter on only one value of a
     * pair written with a separator ("34.5, 84.1W", "N34.5, -84.1") leaves one group
     * holding both: it is split where the separator was, the letter going with the
     * value it is written on.
     */
    private fun group(tokens: List<Token>): List<Group>? {
        if (tokens.none { it is HemiToken }) {
            val numbers = tokens.filterIsInstance<NumberToken>()
            // 2 = D/D, 4 = D M/D M, 6 = D M S/D M S. Anything else is not a pair.
            if (numbers.size !in listOf(2, 4, 6)) return null
            val half = numbers.size / 2
            return listOf(
                Group(numbers.subList(0, half).toMutableList()),
                Group(numbers.subList(half, numbers.size).toMutableList()),
            )
        }

        val groups = mutableListOf<Group>()
        var current = Group()
        var pending: Char? = null

        for (token in tokens) {
            if (token is NumberToken) {
                if (current.numbers.isEmpty() && pending != null) {
                    current.hemi = pending
                    current.prefix = true
                    pending = null
                }
                current.numbers += token
                continue
            }
            token as HemiToken
            // A letter written in front of its number ("W084"), or one arriving when
            // this group is already labelled ("N34 32.74 W084 07.41"), belongs to the
            // group that follows: it must not close the group it interrupted.
            val startsNextGroup = current.hemi != null || (token.prefixLike && !token.suffixLike)

            if (current.numbers.isEmpty()) {
                pending = token.letter                       // prefix on the very first value
            } else if (startsNextGroup) {
                groups += current
                current = Group()
                pending = token.letter
            } else {
                current.hemi = token.letter                  // suffix: "34 30 N"
                groups += current
                current = Group()
            }
        }
        if (current.numbers.isNotEmpty()) groups += current

        if (groups.size == 1) {
            val only = groups[0]
            val breaks = only.numbers.indices.filter { it > 0 && only.numbers[it].afterBreak }
            if (breaks.size == 1) {
                val at = breaks[0]
                return listOf(
                    Group(only.numbers.subList(0, at).toMutableList(), if (only.prefix) only.hemi else null),
                    Group(only.numbers.subList(at, only.numbers.size).toMutableList(), if (only.prefix) null else only.hemi),
                )
            }
        }

        return if (groups.size == 2) groups else null
    }

    private class Degrees(val degrees: Double = 0.0, val format: String = "", val problem: String? = null)

    /**
     * Splits a packed DDMM[SS] integer into parts; a [Degrees.problem] when it can't be one: "minutes" for minutes or seconds of 60 or
     * more, "range" for a width no packed value has.
     */
    private fun unpack(token: NumberToken): Degrees {
        val digits = token.raw.removePrefix("+").removePrefix("-")
        val parts = digits.split(".")
        val intPart = parts[0]
        val decimals = parts.getOrElse(1) { "" }
        val fraction = if (decimals.isNotEmpty()) "0.$decimals".toDouble() else 0.0

        // Width tells us where the degrees stop: DDMM / DDDMM / DDMMSS / DDDMMSS.
        val (degWidth, hasSeconds) = when (intPart.length) {
            4 -> 2 to false
            5 -> 3 to false
            6 -> 2 to true
            7 -> 3 to true
            else -> return Degrees(problem = "range")
        }

        val degrees = intPart.substring(0, degWidth).toDouble()
        val minutes = intPart.substring(degWidth, degWidth + 2).toDouble()
        val seconds = if (hasSeconds) intPart.substring(degWidth + 2).toDouble() + fraction else 0.0
        val minuteFraction = if (hasSeconds) 0.0 else fraction

        if (minutes >= 60 || seconds >= 60) return Degrees(problem = "minutes")
        return Degrees(
            degrees + (minutes + minuteFraction) / 60 + seconds / 3600,
            if (hasSeconds) "packed-dms" else "packed-ddm",
        )
    }

    /**
     * Converts one group's numbers to absolute decimal degrees, or a [Degrees.problem] saying why they are not a value: "pair" (more than
     * degrees, minutes and seconds), "minutes", or "range".
     */
    private fun toDegrees(numbers: List<NumberToken>, axisMax: Double): Degrees {
        if (numbers.size > 3) return Degrees(problem = "pair")

        if (numbers.size >= 2) {
            val d = abs(numbers[0].value)
            val m = abs(numbers[1].value)
            val s = numbers.getOrNull(2)?.let { abs(it.value) }
            if (m >= 60 || (s != null && s >= 60)) return Degrees(problem = "minutes")
            return Degrees(d + m / 60 + (s ?: 0.0) / 3600, if (numbers.size == 3) "dms" else "ddm")
        }

        val token = numbers[0]
        val magnitude = abs(token.value)

        // A plain reading wins whenever it's in range. Packed forms are recognised
        // only when the number can't be degrees at all (3432.740 as a latitude,
        // 08407.407 as a longitude), which is what makes "0034" read as 34 degrees
        // rather than 00 degrees 34 minutes. The trade-off is that a zero-padded
        // packed value small enough to also be valid degrees ("0032.740N" near the
        // equator) reads as plain degrees: two honest readings, and the common one wins.
        if (magnitude <= axisMax) return Degrees(magnitude, "decimal")

        return unpack(token)
    }

    private class Resolved(val axis: Axis?, val value: Double, val format: String, val sided: Boolean)

    /**
     * Reads free text as a coordinate pair, as the web's `readCoordinate` does, branch for branch: see [Reading]. Held to
     * `contracts/fixtures/coords/parse.json` (`read`).
     */
    public fun read(text: String?): Reading {
        val raw = jsTrim(text ?: "")
        if (raw.isEmpty()) return Reading(problem = "empty")
        // A grid is a grid. Never reinterpret one as a coordinate.
        if (looksLikeMgrs(raw)) return Reading(problem = "grid")

        val normalized = normalize(raw)
        // Letters other than N/S/E/W mean this is something else entirely (a place
        // name, a partial grid); refuse rather than parse the digits out of it.
        if (NON_HEMISPHERE_LETTER.containsMatchIn(normalized.replace(ANY_HEMISPHERE, ""))) return Reading(problem = "unreadable")

        val tokens = tokenize(normalized) ?: return Reading(problem = "unreadable")
        val numbers = tokens.filterIsInstance<NumberToken>()
        if (numbers.isEmpty()) return Reading(problem = "unreadable")

        // Fewer than six numbers may be a pair still being typed ("34 32 44" on the
        // way to degrees, minutes and seconds); six or more that make no pair never will.
        val groups = group(tokens) ?: return Reading(problem = if (numbers.size < 6) "incomplete" else "pair")

        // Which axis each value is meant for, as the ordering rules below decide it:
        // a hemisphere names it outright, one labelled value fixes the other, and
        // with none latitude comes first. Used only to say which value is wrong.
        val axes = groups.map { g -> g.hemi?.let { HEMISPHERES.getValue(it).axis } }
        fun axisOf(i: Int): Axis = axes[i]
            ?: axes[1 - i]?.let { if (it == Axis.LAT) Axis.LON else Axis.LAT }
            ?: if (i == 0) Axis.LAT else Axis.LON

        val resolved = groups.mapIndexed { i, g ->
            val info = g.hemi?.let { HEMISPHERES[it] }
            // Use the axis limit we know about; without a hemisphere assume the wider
            // longitude limit so a valid longitude in first position still parses and
            // gets sorted out by the ordering rules below.
            val axisMax = info?.axis?.max ?: Axis.LON.max
            val converted = toDegrees(g.numbers, axisMax)
            converted.problem?.let { problem ->
                val named = if (problem == "range") (if (axisOf(i) == Axis.LAT) "latitude" else "longitude") else problem
                // "34 32 44 84" is degrees, minutes and seconds on the way, read for now as
                // two degrees and minutes: its "minutes" of 44 84… are not wrong yet.
                if (named == "minutes" && groups.all { it.hemi == null } && numbers.size < 6) return Reading(problem = "incomplete")
                return Reading(problem = named)
            }
            val signedByText = if (g.numbers[0].negative) -1 else 1
            // Whether the value says its own side: a hemisphere letter, or a sign on its first number (a dash between minutes and
            // seconds is not one).
            val sided = info != null || g.numbers[0].raw.startsWith("+") || g.numbers[0].raw.startsWith("-")
            Resolved(info?.axis, converted.degrees * (info?.sign ?: signedByText), converted.format, sided)
        }
        val (first, second) = resolved

        val lat: Double
        val lon: Double
        val lonValue: Resolved
        if (first.axis != null && second.axis != null && first.axis != second.axis) {
            // Hemispheres name the axes outright, in either order ("W084 N34").
            if (first.axis == Axis.LAT) { lat = first.value; lon = second.value; lonValue = second }
            else { lat = second.value; lon = first.value; lonValue = first }
        } else if (first.axis == second.axis && first.axis != null) {
            return Reading(problem = "same-axis")             // "34N 84N": not a coordinate pair.
        } else if (first.axis == Axis.LON || second.axis == Axis.LAT) {
            // Only one side was labelled, but that's enough to fix both.
            lat = second.value
            lon = first.value
            lonValue = first
        } else {
            // Nothing labelled: latitude first, as every mapping tool writes it.
            //
            // Deliberately no cleverness here. Guessing from magnitudes (treating a
            // first value beyond +-90 as a longitude) would let "91.0, -84.1" quietly
            // become a point in Antarctica instead of reporting the out-of-range
            // latitude it almost certainly is. Anyone who genuinely means longitude
            // first can say so with a hemisphere letter.
            lat = first.value
            lon = second.value
            lonValue = second
        }

        if (!lat.isFinite() || !lon.isFinite()) return Reading(problem = "unreadable")
        if (abs(lat) > 90) return Reading(problem = "latitude")
        if (abs(lon) > 180) return Reading(problem = "longitude")

        val format = if (first.format == second.format) first.format else "mixed"
        return Reading(
            parsed = Parsed(lat, lon, format, FORMAT_LABELS[format] ?: "lat/long"),
            signed = lonValue.sided,
        )
    }

    /**
     * Parses free text into a latitude/longitude, or null when the text isn't a
     * coordinate (including when it's a valid MGRS grid).
     */
    public fun parse(text: String?): Parsed? = read(text).parsed

    /**
     * Compact display of a parsed pair, for confirming what was recognised.
     * Rounds as JavaScript's `toFixed` does (the exact binary value, ties up),
     * not as Java's formatter does (the shortest decimal, which can round 1.005 up).
     */
    public fun formatDecimal(lat: Double, lon: Double, places: Int = 5): String =
        "${toFixed(lat, places)}, ${toFixed(lon, places)}"

    /**
     * The degrees-minutes-seconds text the web keeps as a diagram's `latLong` after analysis (`convertToLatLongString` in
     * `utils/Helpers.js`): `34° 47' 1.74" N  84° 4' 55.88" W`. Seconds are rounded to two places and **never carried** into the minute, so
     * 34.999999 reads `34° 59' 60.00" N`; the web does it and the LZ card shows it, so the apps do too.
     */
    public fun formatLatLongDms(lat: Double, lon: Double): String = "${dms(lat, true)}  ${dms(lon, false)}"

    private fun dms(coordinate: Double, isLatitude: Boolean): String {
        val absolute = abs(coordinate)
        val degrees = floor(absolute)
        val minutesNotTruncated = (absolute - degrees) * 60
        val minutes = floor(minutesNotTruncated)
        val seconds = toFixed((minutesNotTruncated - minutes) * 60, 2)
        val direction = if (isLatitude) (if (coordinate >= 0) "N" else "S") else (if (coordinate >= 0) "E" else "W")
        return "${degrees.toLong()}° ${minutes.toLong()}' $seconds\" $direction"
    }

    internal fun toFixed(value: Double, places: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val body = BigDecimal(abs(value)).setScale(places, RoundingMode.HALF_UP).toPlainString()
        return if (value < 0) "-$body" else body
    }
}
