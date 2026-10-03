package app.ezpztac.symbols

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The symbols that ship pre-rendered: every unit preset in each of the four affiliations and the threat presets, drawn by the web's milsymbol
 * at size 32 (`contracts/fixtures/symbols`, copied into the app's assets at build). They need no JavaScript, so the common symbols always draw,
 * with or without the sandbox. A symbol with labels is never one of them: the labels are part of milsymbol's picture.
 */
class PresetSymbols(private val assets: AssetManager) : SymbolSvgSource {
    private class Entry(val file: String, val width: Double, val height: Double, val anchorX: Double, val anchorY: Double)

    private val index: Map<String, Entry> by lazy { readIndex() }

    private fun readIndex(): Map<String, Entry> = runCatching {
        val text = assets.open("$DIRECTORY/presets.json").use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonObject.getValue("symbols").jsonArray.associate { item ->
            val o = item.jsonObject
            o.text("sidc") to Entry(o.text("file"), o.number("width"), o.number("height"), o.number("anchorX"), o.number("anchorY"))
        }
    }.getOrDefault(emptyMap())

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.doubleOrNull ?: error("$key is not a number")

    /** Whether [sidc] is one of the presets. */
    fun has(sidc: String): Boolean = sidc in index

    /** Every preset SIDC, in the order the web lists them. */
    val sidcs: List<String> get() = index.keys.toList()

    /**
     * The preset's SVG scaled to [SymbolSpec.size] (a vector, so it scales as milsymbol would to within a hair), or [SvgResult.NotHere] for
     * anything that is not a preset or carries labels.
     */
    override suspend fun svg(spec: SymbolSpec): SvgResult {
        if (spec.hasLabels) return SvgResult.NotHere
        val entry = index[spec.sidc] ?: return SvgResult.NotHere
        val svg = runCatching { assets.open("$DIRECTORY/${entry.file}").use { it.readBytes().decodeToString().trim() } }.getOrNull() ?: return SvgResult.NotHere
        val scale = spec.size.toDouble() / SymbolSpec.MAP_SIZE
        return SvgResult.Found(SymbolSvg(svg, entry.width * scale, entry.height * scale, entry.anchorX * scale, entry.anchorY * scale))
    }

    private companion object {
        const val DIRECTORY = "symbols"
    }
}
