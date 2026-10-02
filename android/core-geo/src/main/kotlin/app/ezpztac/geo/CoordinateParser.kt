package app.ezpztac.geo

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

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

    // JavaScript's \s (and String.trim) is wider than Java's: it includes the
    // no-break space, the Unicode spaces and the byte-order mark.
    private const val JS_SPACE = """\t\n\u000B\f\r    -     　﻿"""
    private val SPACES = Regex("[$JS_SPACE]+")
    private val EDGE_SPACES = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")

    // Band letters run C-X skipping I and O; 100 km square letters skip I and O
    // too. Matching the real grammar (rather than [A-Z]) keeps "34N 084W" from
    // being mistaken for a grid.
    private val MGRS_PATTERN = Regex("""\d{1,2}[C-HJ-NP-X][A-HJ-NP-Z]{2}\d{0,10}""", RegexOption.IGNORE_CASE)

    // Punctuation that appears in coordinates and never in a grid.
    private val COORDINATE_PUNCTUATION = Regex(
        """[.,;/°º˚∘′‵'´`″‶"“”+\-‐-―−\t\n]""",
    )

    private val ANY_HEMISPHERE = Regex("[NSEW]", RegexOption.IGNORE_CASE)
    // Deliberately the web's class, which also matches E and S: see looksLikeCoordinateText.
    private val OTHER_LETTER = Regex("[A-MOP-VXYZ]", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("""([NSEW])|([+-]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

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

    private fun jsTrim(text: String) = text.replace(EDGE_SPACES, "")

    /** True when the text is (or is becoming) an MGRS grid rather than a coordinate. */
    public fun looksLikeMgrs(text: String?): Boolean = MGRS_PATTERN.matches((text ?: "").replace(SPACES, ""))

    /**
     * A lenient, keystroke-by-keystroke check used to decide whether the target
     * input is being given a coordinate rather than a grid.
     *
     * Deliberately looser than [parse]: it has to say "yes" to half-typed text
     * like "34." or "34.5, -" so the field stops reformatting mid entry.
     *
     * Ported as the web has it, which treats a bare "34S" as not a coordinate:
     * its "no other letters" test uses a class that includes E and S. Being wrong
     * is cheap (the value is only reformatted, never rejected), and the fixtures
     * pin the behaviour so the clients stay identical.
     */
    public fun looksLikeCoordinateText(text: String?): Boolean {
        val raw = text ?: ""
        if (jsTrim(raw).isEmpty()) return false
        if (looksLikeMgrs(raw)) return false
        if (COORDINATE_PUNCTUATION.containsMatchIn(raw)) return true
        return ANY_HEMISPHERE.containsMatchIn(raw) && !OTHER_LETTER.containsMatchIn(raw)
    }

    /**
     * Folds the many ways coordinate punctuation gets typed or pasted down to a
     * plain ASCII form: degree/minute/second marks become spaces, exotic dashes
     * become hyphens, and separators become spaces.
     */
    private fun normalize(text: String): String = jsTrim(
        text
            .replace(Regex("""[°º˚∘]"""), " ")
            .replace(Regex("""[′‵'´`]"""), " ")
            .replace(Regex("""[″‶"“”]"""), " ")
            .replace(Regex("""[‐-―−]"""), "-")
            .replace(Regex("""[,;/|\t\n\r]+"""), " "),
    )

    private sealed interface Token
    private class NumberToken(val raw: String, val value: Double, val negative: Boolean) : Token
    private class HemiToken(val letter: Char, val suffixLike: Boolean, val prefixLike: Boolean) : Token

    /**
     * Splits normalized text into ordered number and hemisphere tokens.
     *
     * Numbers keep their raw text: leading zeros are what distinguish a packed
     * "0840724" (DDDMMSS) from a plain 840724.
     */
    private fun tokenize(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        for (match in TOKEN.findAll(text)) {
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
                    prefixLike = after != null && (after in '0'..'9' || after == '+' || after == '-'),
                )
            } else {
                val raw = match.groups[2]!!.value
                val value = raw.toDouble()
                if (!value.isFinite()) return null
                tokens += NumberToken(raw, value, raw.startsWith("-"))
            }
        }
        return tokens
    }

    private class Group(val numbers: MutableList<NumberToken> = mutableListOf(), var hemi: Char? = null)

    /**
     * Collects tokens into two coordinate groups.
     *
     * A hemisphere letter closes the group it belongs to, whether it was written
     * in front (`N34 30`) or behind (`34 30 N`). With no hemisphere letters at all
     * the numbers are simply split down the middle.
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

        return if (groups.size == 2) groups else null
    }

    private class Degrees(val degrees: Double, val format: String)

    /** Splits a packed DDMM[SS] integer into parts, or null if it can't be one. */
    private fun unpack(token: NumberToken): Degrees? {
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
            else -> return null
        }

        val degrees = intPart.substring(0, degWidth).toDouble()
        val minutes = intPart.substring(degWidth, degWidth + 2).toDouble()
        val seconds = if (hasSeconds) intPart.substring(degWidth + 2).toDouble() + fraction else 0.0
        val minuteFraction = if (hasSeconds) 0.0 else fraction

        if (minutes >= 60 || seconds >= 60) return null
        return Degrees(
            degrees + (minutes + minuteFraction) / 60 + seconds / 3600,
            if (hasSeconds) "packed-dms" else "packed-ddm",
        )
    }

    /** Converts one group's numbers to absolute decimal degrees. */
    private fun toDegrees(numbers: List<NumberToken>, axisMax: Double): Degrees? {
        if (numbers.size > 3) return null

        if (numbers.size >= 2) {
            val d = abs(numbers[0].value)
            val m = abs(numbers[1].value)
            val s = numbers.getOrNull(2)?.let { abs(it.value) }
            if (m >= 60 || (s != null && s >= 60)) return null
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

    private class Resolved(val axis: Axis?, val value: Double, val format: String)

    /**
     * Parses free text into a latitude/longitude, or null when the text isn't a
     * coordinate (including when it's a valid MGRS grid).
     */
    public fun parse(text: String?): Parsed? {
        val raw = jsTrim(text ?: "")
        if (raw.isEmpty()) return null
        // A grid is a grid. Never reinterpret one as a coordinate.
        if (looksLikeMgrs(raw)) return null

        val normalized = normalize(raw)
        val tokens = tokenize(normalized)
        if (tokens.isNullOrEmpty()) return null

        // Letters other than N/S/E/W mean this is something else entirely (a place
        // name, a partial grid); refuse rather than parse the digits out of it.
        if (OTHER_LETTER.containsMatchIn(normalized.replace(ANY_HEMISPHERE, ""))) return null

        val groups = group(tokens) ?: return null

        val resolved = groups.map { g ->
            val info = g.hemi?.let { HEMISPHERES[it] }
            // Use the axis limit we know about; without a hemisphere assume the wider
            // longitude limit so a valid longitude in first position still parses and
            // gets sorted out by the ordering rules below.
            val axisMax = info?.axis?.max ?: Axis.LON.max
            val converted = toDegrees(g.numbers, axisMax) ?: return null
            val signedByText = if (g.numbers[0].negative) -1 else 1
            Resolved(info?.axis, converted.degrees * (info?.sign ?: signedByText), converted.format)
        }
        val (first, second) = resolved

        val lat: Double
        val lon: Double
        if (first.axis != null && second.axis != null && first.axis != second.axis) {
            // Hemispheres name the axes outright, in either order ("W084 N34").
            if (first.axis == Axis.LAT) { lat = first.value; lon = second.value }
            else { lat = second.value; lon = first.value }
        } else if (first.axis == second.axis && first.axis != null) {
            return null                                     // "34N 84N": not a coordinate pair.
        } else if (first.axis == Axis.LON || second.axis == Axis.LAT) {
            // Only one side was labelled, but that's enough to fix both.
            lat = second.value
            lon = first.value
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
        }

        if (!lat.isFinite() || !lon.isFinite()) return null
        if (abs(lat) > 90 || abs(lon) > 180) return null

        val format = if (first.format == second.format) first.format else "mixed"
        return Parsed(lat, lon, format, FORMAT_LABELS[format] ?: "lat/long")
    }

    /**
     * Compact display of a parsed pair, for confirming what was recognised.
     * Rounds as JavaScript's `toFixed` does (the exact binary value, ties up),
     * not as Java's formatter does (the shortest decimal, which can round 1.005 up).
     */
    public fun formatDecimal(lat: Double, lon: Double, places: Int = 5): String =
        "${toFixed(lat, places)}, ${toFixed(lon, places)}"

    internal fun toFixed(value: Double, places: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val body = BigDecimal(abs(value)).setScale(places, RoundingMode.HALF_UP).toPlainString()
        return if (value < 0) "-$body" else body
    }
}
