package app.ezpztac.missionpacks

import app.ezpztac.model.DiagramNormalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** What the tests of the pack fixtures (shared.json, describe.json, edit.json) share. */
internal object PackFixtures {
    /** The pack the fixtures' shapes were made in; the shape does not depend on it. */
    const val PACK = "6f1c2a9e-3b7d-4c55-9a10-2d8e5f7b4c31"

    /** An environment that never reads the clock and never makes an id: the shapes must not need either. */
    fun env(now: String = "2026-10-05T13:00:00.000Z"): DiagramNormalizer.Environment =
        DiagramNormalizer.Environment(now = { now }, newId = { error("a pack item's diagram is never given a random id") })

    fun name(case: JsonObject): String = case.getValue("name").jsonPrimitive.content

    fun webBug(case: JsonObject): Boolean = (case["webBug"] as? JsonPrimitive)?.booleanOrNull == true

    /** Text, or Kotlin's null for a key that is missing or null: how the web reads `name` and `actor` (`name || default`). */
    fun text(value: JsonElement?): String? = when {
        value == null || value is JsonNull -> null
        value is JsonPrimitive && value.isString -> value.content
        else -> error("not text: $value")
    }

    /** An item as a case gives one: `{uuid, kind, name, data}`, data missing being no data. */
    fun item(json: JsonElement): PackItemView {
        val item = json.jsonObject
        return PackItemView(
            uuid = item.getValue("uuid").jsonPrimitive.content,
            kind = item.getValue("kind").jsonPrimitive.content,
            name = item.getValue("name").jsonPrimitive.content,
            data = item["data"] ?: JsonNull,
        )
    }

    fun localId(item: PackItemView): String = PackRef.localId(PACK, item.uuid)

    private val LZ_ANALYSIS_NAMES = listOf("customLZ", "detectedLZ", "terrainData", "results", "analysisResults", "gridElevation", "latLong")
    private val LZ_GRAPHICS_NAMES = listOf(
        "doghouses", "helicopters", "pzMarkers", "pzMarker", "sectorsOfFire", "goArounds", "goAround", "units", "measurements", "exportBox",
    )

    /**
     * The names the web's normaliser reads at each level an LZ/PZ's shape is built at, as shared.json's description lists them (kept
     * apart from the port's own lists, which they check): every other name there is one a newer version wrote.
     */
    val lzReadNames: Map<String, List<String>> = mapOf(
        "top" to listOf(
            "schemaVersion", "status", "target", "targetLocation", "gridInput", "mapData", "flightData", "analysis", "graphics",
            "id", "savedId", "dirty", "createdAt", "updatedAt", "view", "name", "created_at", "updated_at",
        ) + LZ_ANALYSIS_NAMES + LZ_GRAPHICS_NAMES + listOf("mapStyle", "showLZOutline", "showHeatmap"),
        "target" to listOf("lat", "lon", "mgrs", "latitude", "lng", "longitude"),
        "analysis" to LZ_ANALYSIS_NAMES,
        "graphics" to LZ_GRAPHICS_NAMES,
    )

    /** [expected] and [actual] are the same JSON value (key order aside; null is not absent; 34 is 34.0). */
    fun assertValue(expected: JsonElement?, actual: JsonElement?, message: String) {
        val differences = StrictJson.valueDifferences(expected, actual)
        assertTrue(differences.isEmpty(), "$message:\n" + differences.joinToString("\n"))
    }

    /** Applies [ops] to item data [data] of [kind] as the server would, each one well formed and applied. */
    fun applyAll(kind: String, data: JsonElement, ops: List<JsonObject>): JsonElement {
        var items = mapOf("it" to PackItemState(kind, "IT", data, deleted = false))
        ops.forEachIndexed { i, op ->
            val addressed = JsonObject(op + ("item" to JsonPrimitive("it")))
            assertEquals(null, PackOps.validate(addressed), "op $i is well formed: $op")
            val result = PackOps.apply(items, addressed)
            assertEquals(OpStatus.APPLIED, result.status, "op $i applies: $op (${result.reason})")
            items = result.items
        }
        return items.getValue("it").data
    }

    /** Whether [text] holds half a character outside the basic plane: what the server cannot store. */
    fun hasLoneSurrogate(text: String): Boolean = text.indices.any { i ->
        val c = text[i]
        (Character.isHighSurrogate(c) && (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1]))) ||
            (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text[i - 1])))
    }
}
