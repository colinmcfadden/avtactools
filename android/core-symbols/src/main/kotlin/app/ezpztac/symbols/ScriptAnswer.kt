package app.ezpztac.symbols

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `ezpzRenderSymbol` (assets/ezpz-render.js) answers: one JSON string, `{valid: false}` or `{valid: true, svg, width, height, anchorX,
 * anchorY}`. The web's contract test holds the script to milsymbol's own answer, and `contracts/fixtures/symbols/script.json` carries real answers
 * for this to be read against.
 */
internal object ScriptAnswer {
    private val json = Json { ignoreUnknownKeys = true }

    /** The SVG the script drew, [SvgResult.Invalid] when it said so, or null when the answer is not one the script gives. */
    fun parse(answer: String): SvgResult? {
        val o = runCatching { json.parseToJsonElement(answer).jsonObject }.getOrNull() ?: return null
        if (o["valid"]?.jsonPrimitive?.booleanOrNull != true) return if (o["valid"]?.jsonPrimitive?.booleanOrNull == false) SvgResult.Invalid else null
        val svg = o.text("svg") ?: return null
        val width = o.number("width") ?: return null
        val height = o.number("height") ?: return null
        val anchorX = o.number("anchorX") ?: return null
        val anchorY = o.number("anchorY") ?: return null
        if (svg.isBlank() || width <= 0 || height <= 0) return null
        return SvgResult.Found(SymbolSvg(svg, width, height, anchorX, anchorY))
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
}
