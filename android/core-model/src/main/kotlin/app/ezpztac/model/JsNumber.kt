package app.ezpztac.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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

    public fun finiteOr(value: JsonElement?, fallback: Double): Double {
        val primitive = value as? JsonPrimitive ?: return fallback
        if (primitive.content == "null" && !primitive.isString) return fallback
        val text = primitive.content
        if (primitive.isString && !PLAIN_DECIMAL.matches(text)) return fallback
        val number = text.trim().toDoubleOrNull() ?: return fallback
        return if (number.isFinite()) number else fallback
    }
}
