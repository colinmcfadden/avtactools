package app.ezpztac.model

import kotlinx.serialization.json.JsonElement
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

    public fun finiteOr(value: JsonElement?, fallback: Double): Double {
        val primitive = value as? JsonPrimitive ?: return fallback
        if (primitive.content == "null" && !primitive.isString) return fallback
        val text = primitive.content
        if (primitive.isString && !PLAIN_DECIMAL.matches(text)) return fallback
        val number = text.trim().toDoubleOrNull() ?: return fallback
        return if (number.isFinite()) number else fallback
    }
}
