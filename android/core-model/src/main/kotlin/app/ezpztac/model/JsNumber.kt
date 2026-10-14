package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Reads a number out of JSON of uncertain shape the way the web app does for
 * aircraft profiles (`num()` in `aircraftProfiles.js`): what is there if it is a
 * finite number, otherwise the fallback. A plain numeric string counts, as it
 * does in JavaScript.
 *
 * A JSON `null` is treated as absent. The web's `Number(null)` is 0, but the
 * API cannot send one (its numeric columns are NOT NULL), so nothing relies on
 * it and the fixtures do not cover it.
 */
public object JsNumber {
    private val PLAIN_DECIMAL = Regex("""\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*""")

    /**
     * `value.toFixed(places)` as JavaScript writes it: the exact binary value rounded half up (so `1.005.toFixed(2)` is `1.00`, and Java's `%.2f`, which rounds the shortest
     * decimal, says `1.01`), a sign kept on a negative that rounds to zero (`(-1e-7).toFixed(6)` is `-0.000000`) and none on `-0`, the words for NaN and the infinities, and
     * past 1e21 the number's own text. Never uses the device's locale, whose digits may not be ASCII.
     */
    public fun toFixed(value: Double, places: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (kotlin.math.abs(value) >= 1e21) return toText(value)
        val body = java.math.BigDecimal(kotlin.math.abs(value)).setScale(places, java.math.RoundingMode.HALF_UP).toPlainString()
        return if (value < 0) "-$body" else body
    }

    /**
     * `String(value)` as JavaScript writes it (ECMAScript `Number::toString`): the shortest digits that read back as the same number, in plain form
     * from 1e-6 up to (not including) 1e21 and as `1e+21` / `1e-7` outside it; `-0` is `0`. A template literal is what the AMPS writers build their
     * values with (`raw${valueFt * 0.3048} m Foot AGL`), so a native writer must produce the same digits. Java's `Double.toString` writes `100.0` and
     * `1.0E21`, and its digits are not always the shortest, so it is not used.
     *
     * The shortest digits are found by rounding the exact value to 1, 2, 3 … digits until the result reads back as the same double: the closest decimal
     * of that length is the one JavaScript picks. Reading back is `Double.parseDouble`, which rounds correctly on every runtime.
     */
    public fun toText(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        val x = abs(value)
        val exact = BigDecimal(x)
        var shortest = exact
        for (digits in 1..17) {
            val rounded = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
            if (java.lang.Double.parseDouble(rounded.toString()) == x) {
                shortest = rounded
                break
            }
        }
        val stripped = shortest.stripTrailingZeros()
        val digits = stripped.unscaledValue().toString()
        val k = digits.length
        val n = k - stripped.scale()                                    // the decimal point sits after n digits: x = 0.digits * 10^n
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent)
            }
        }
        return if (value < 0) "-$body" else body
    }

    /**
     * JavaScript's `Number(value)` for a JSON value, exactly (ECMAScript ToNumber). Kotlin's null is `undefined`, which is NaN; JSON null
     * and false are 0 and true is 1; a list is read by its text, the elements joined by commas (so `[]` is 0, `[90]` is 90 and `[1, 2]`
     * is NaN), and an object is NaN. Text is trimmed of what JavaScript counts as white space (the no-break spaces and the byte order mark
     * too, not U+001C or a zero-width space), and then blank text is 0, decimal text its number, `Infinity` with or without a sign
     * infinity, and `0x`, `0o` and `0b` text (no sign) a whole number in that base; anything else, `1_000` and `-0x5A` among it, is NaN.
     *
     * A saved document's numbers are read with it where the web reads them with `Number()`, so hexadecimal text in a target is 34 here as
     * it is there, not "not a position".
     */
    public fun of(value: JsonElement?): Double = when (value) {
        null -> Double.NaN
        is JsonNull -> 0.0
        is JsonObject -> Double.NaN
        is JsonArray -> parse(joined(value))
        is JsonPrimitive -> when {
            value.isString -> parse(value.content)
            value.content == "true" -> 1.0
            value.content == "false" -> 0.0
            else -> value.content.toDoubleOrNull() ?: Double.NaN
        }
    }

    // `String(list)`: the elements joined by commas, null as nothing and a list within by its own text.
    private fun joined(list: JsonArray): String = list.joinToString(",") { element ->
        when (element) {
            is JsonNull -> ""
            is JsonArray -> joined(element)
            is JsonObject -> "[object Object]"
            is JsonPrimitive -> if (element.isString || element.content == "true" || element.content == "false") {
                element.content
            } else {
                element.content.toDoubleOrNull()?.let(::toText) ?: element.content
            }
        }
    }

    private val DECIMAL_TEXT = Regex("""[+-]?(?:Infinity|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""")
    private val BASE_TEXT = Regex("""0([xXoObB])([0-9A-Za-z]+)""")

    /** ECMAScript StringToNumber: [text] trimmed as JavaScript trims, then read whole or not at all. */
    private fun parse(text: String): Double {
        val trimmed = trimJs(text)
        if (trimmed.isEmpty()) return 0.0
        if (DECIMAL_TEXT.matches(trimmed)) {
            return when (trimmed) {
                "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                else -> trimmed.toDouble()
            }
        }
        val based = BASE_TEXT.matchEntire(trimmed) ?: return Double.NaN
        val radix = when (based.groupValues[1].lowercase()) {
            "x" -> 16
            "o" -> 8
            else -> 2
        }
        val digits = based.groupValues[2]
        if (digits.any { Character.digit(it, radix) < 0 }) return Double.NaN
        // BigInteger rounds a value past 2^53 to the nearest double, as JavaScript's reading of the digits does.
        return java.math.BigInteger(digits, radix).toDouble()
    }

    /** What `String.prototype.trim` takes from each end: JavaScript's white space and line terminators. */
    internal fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    /** `text.trim()` as JavaScript does it, which is not Kotlin's: it takes the byte order mark, and leaves U+001C to U+001F. */
    internal fun trimJs(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isJsSpace(text[start])) start++
        while (end > start && isJsSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    public fun finiteOr(value: JsonElement?, fallback: Double): Double {
        val primitive = value as? JsonPrimitive ?: return fallback
        if (primitive.content == "null" && !primitive.isString) return fallback
        val text = primitive.content
        if (primitive.isString && !PLAIN_DECIMAL.matches(text)) return fallback
        val number = text.trim().toDoubleOrNull() ?: return fallback
        return if (number.isFinite()) number else fallback
    }
}
