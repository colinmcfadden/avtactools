package app.ezpztac.planning

import app.ezpztac.model.JsNumber
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One end of a phase line on the screen, and the unit vector leaving it, away from the line. */
public data class PhaseLineEnd(val x: Double, val y: Double, val ux: Double, val uy: Double)

/**
 * A phase line (the owner, 2026-11-14; the web's feature/phaseLines): a control measure, `{id, name, points: [{lat, lon}, ...]}` under `phaseLines` of a
 * set's document, its name without the PL, drawn as a plain line with `PL <NAME>` beyond each end. Held to `contracts/fixtures/planning/phaseLines.json`.
 */
public object PhaseLines {
    /** At most this many characters in a name. */
    public const val NAME_MAX: Int = 24

    private val PHONETIC = listOf(
        "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE",
        "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU",
    )

    // JavaScript's \s, spelt out: Java's is ASCII alone.
    private val JS_SPACES = Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    // `$` in Java also matches before a last line terminator JavaScript's does not know (U+0085), so the end is \z.
    private val LEADING_PL = Regex("^PL(?: |\\z)")

    /**
     * A name as a phase line keeps it (`cleanPhaseLineName`): trimmed as JavaScript trims, in capitals, its runs of spaces one space, a `PL` typed in
     * front dropped, at most [NAME_MAX] characters (code points, never through an emoji), and trimmed again.
     */
    public fun cleanName(text: String?): String {
        val name = JsNumber.trimJs(text.orEmpty()).uppercase().replace(JS_SPACES, " ").replaceFirst(LEADING_PL, "")
        val cut = if (name.codePointCount(0, name.length) <= NAME_MAX) name else name.substring(0, name.offsetByCodePoints(0, NAME_MAX))
        return JsNumber.trimJs(cut)
    }

    /** [cleanName] of a name as JSON holds it: `String(name ?? "")`, so a number is its text. */
    public fun cleanName(name: JsonElement?): String = cleanName(SetLines.jsString(name))

    /** What a line is labelled at each end (`phaseLineLabel`): `PL` and its name trimmed, or `PL` alone when that is blank or not text. */
    public fun label(line: JsonElement?): String {
        val name = ((line as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(JsNumber::trimJs).orEmpty()
        return if (name.isNotEmpty()) "PL $name" else "PL"
    }

    /** The name offered for a new line (`nextPhaseLineName`): the first word of the phonetic alphabet no line has, then the first number from 27. */
    public fun nextName(lines: List<JsonElement?>?): String {
        val taken = lines.orEmpty().map { cleanName((it as? JsonObject)?.get("name")) }.toSet()
        PHONETIC.firstOrNull { it !in taken }?.let { return it }
        var n = PHONETIC.size + 1
        while (n.toString() in taken) n += 1
        return n.toString()
    }

    /** The name a line is called by. */
    public fun name(line: JsonObject): String = (line["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** A new phase line of [points] (places only), named as [cleanName] makes [name] (`createPhaseLine`). */
    public fun create(points: List<LatLon>, id: String, name: String): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(cleanName(name)), "points" to SetLines.plain(points)))

    /** [line] renamed, or unchanged when the name is blank once cleaned (`renamePhaseLine`); every other field kept, in its place. */
    public fun rename(line: JsonObject, name: String): JsonObject {
        val next = cleanName(name)
        return if (next.isNotEmpty()) JsonObject(line + ("name" to JsonPrimitive(next))) else line
    }

    /**
     * Where the two labels' ends are (`phaseLineEnds`): at each end of the line on the screen, with the unit vector leaving it, away from the first
     * point that is not on it. None when either end has no such point.
     */
    public fun ends(pixels: List<Pixel>): List<PhaseLineEnd> {
        if (pixels.size < 2) return emptyList()
        val first = pixels.first()
        val last = pixels.last()
        val a = outwardFrom(first, pixels.drop(1)) ?: return emptyList()
        val b = outwardFrom(last, pixels.dropLast(1).asReversed()) ?: return emptyList()
        return listOf(PhaseLineEnd(first.x, first.y, a.first, a.second), PhaseLineEnd(last.x, last.y, b.first, b.second))
    }

    /**
     * How far a label's middle is from its end, in halves of its own width and height (`labelShift`), so the end is on the label's edge whichever
     * way the line leaves and the line is never under its name: the vector over its larger part.
     */
    public fun labelShift(ux: Double, uy: Double): Pair<Double, Double> {
        val reach = max(abs(ux), abs(uy))
        return if (reach > 0) (ux / reach) to (uy / reach) else 0.0 to 0.0
    }

    private fun outwardFrom(end: Pixel, inward: List<Pixel>): Pair<Double, Double>? {
        for (point in inward) {
            val dx = end.x - point.x
            val dy = end.y - point.y
            val length = hypot(dx, dy)
            if (length > 0) return (dx / length) to (dy / length)
        }
        return null
    }
}
