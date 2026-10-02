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

    private val DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
    private val SPACE = Regex("^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$")

    /**
     * `Number(value)` for the shapes a saved target can hold: a number, a numeric
     * string (empty text is 0, as in JavaScript), a boolean, or null (0). Anything
     * else, objects and arrays included, is NaN.
     */
    fun number(value: JsonElement?): Double = when (value) {
        null -> Double.NaN                                  // undefined
        is JsonNull -> 0.0                                  // Number(null) is 0
        is JsonObject, is JsonArray -> Double.NaN
        is JsonPrimitive -> when {
            value.isString -> {
                val text = value.content.replace(SPACE, "")
                when {
                    text.isEmpty() -> 0.0
                    DECIMAL.matches(text) -> text.toDouble()
                    else -> Double.NaN
                }
            }
            value.booleanOrNull != null -> if (value.booleanOrNull == true) 1.0 else 0.0
            else -> value.doubleOrNull ?: Double.NaN
        }
    }

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
}
