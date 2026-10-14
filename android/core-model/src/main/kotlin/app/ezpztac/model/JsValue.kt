package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * JavaScript's loose rules, for reading saved documents the way the web reads them.
 *
 * Saved LZ documents are loosely typed JSON the web has written over many releases.
 * `normalizeLzDiagram` leans on `??`, truthiness and `Number()`, so a faithful port
 * has to as well; reading them with strict types would reject documents the web opens.
 */
internal object JsValue {
    /** `value ?? undefined`: null and absent are both "nothing". */
    fun present(value: JsonElement?): JsonElement? = value?.takeIf { it !is JsonNull }

    /** First of the arguments that is neither null nor absent (a chain of `??`). */
    fun firstPresent(vararg values: JsonElement?): JsonElement? = values.firstNotNullOfOrNull { present(it) }

    /** JavaScript truthiness of a parsed JSON value. An empty array or object is truthy. */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, is JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
        }
    }

    /**
     * `Number(value)`, exactly as JavaScript reads it ([JsNumber.of]): hexadecimal text, a one-element list and text padded with a
     * byte order mark are numbers there, so they are here.
     */
    fun number(value: JsonElement?): Double = JsNumber.of(value)

    /**
     * `text.trim()` as JavaScript trims: the no-break spaces and the byte order mark are taken, U+001C to U+001F are not (Kotlin's
     * `trim` does both the other way).
     */
    fun trim(text: String): String = JsNumber.trimJs(text)

    /** `String(value)` for a primitive: 12 and 12.0 are both "12", as JavaScript prints them. */
    fun string(value: JsonElement?): String? = when (value) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString || value.booleanOrNull != null) {
            value.content
        } else {
            val d = value.doubleOrNull
            if (d != null && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else value.content
        }
        else -> null
    }

    /** What JavaScript counts as white space and line breaks at the start of the text `parseInt` and `parseFloat` read. */
    private fun isSpace(c: Char) = c in " \t\n\u000B\u000C\r\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"

    private fun digit(c: Char, radix: Int): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }.takeIf { it < radix } ?: -1

    /**
     * `parseInt(text)`: skips leading white space, takes an optional sign, reads a `0x` prefix as hexadecimal, and reads the digits it can,
     * ignoring what follows ("270°" is 270, "12.7" is 12). NaN when there are no digits. Only the ASCII digits count. A very long run of digits
     * is rounded to the nearest double, as the engines do.
     */
    fun parseInt(text: String): Double {
        var i = 0
        while (i < text.length && isSpace(text[i])) i++
        var negative = false
        if (i < text.length && (text[i] == '+' || text[i] == '-')) {
            negative = text[i] == '-'
            i++
        }
        var radix = 10
        if (i + 1 < text.length && text[i] == '0' && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
            radix = 16
            i += 2
        }
        val start = i
        while (i < text.length && digit(text[i], radix) >= 0) i++
        if (i == start) return Double.NaN
        val magnitude = java.math.BigInteger(text.substring(start, i), radix).toDouble()
        return if (negative) -magnitude else magnitude
    }

    private val FLOAT_PREFIX = Regex("""[+-]?(?:Infinity|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""")

    /**
     * `parseFloat(text)`: skips leading white space and reads the longest decimal number at the start ("3.13km" is 3.13, "1e" is 1, ".5" is
     * 0.5, "-Infinity" is minus infinity); NaN when there is none. A hexadecimal prefix is not read ("0x1F" is 0).
     */
    fun parseFloat(text: String): Double {
        var i = 0
        while (i < text.length && isSpace(text[i])) i++
        val match = FLOAT_PREFIX.matchAt(text, i) ?: return Double.NaN
        return match.value.toDouble()
    }
}
