package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a person does to a pack's items besides editing them: the pure part of the web's `packActions.js`. */
public object PackActions {
    /**
     * A version of an item in the form the library keeps each kind (`libraryData`, held to `shared.json`): an LZ/PZ's document, a set's points
     * and a set of threats as they are; a route set's sketched routes as `{version: 1, routes}` with its `flots` beside them when it has any, any other
     * field of the set and any other version not kept, and routes that are not a list none.
     */
    public fun libraryData(kind: String, data: JsonElement?): JsonElement? {
        if (kind == "lz" || kind == "pointset" || kind == "threatset") return data
        val routes = (data as? JsonObject)?.get("routes") as? JsonArray ?: JsonArray(emptyList())
        val flots = ((data as? JsonObject)?.get("flots") as? JsonArray).orEmpty().filter { it is JsonObject && "id" in it }
        val saved = linkedMapOf<String, JsonElement>("version" to JsonPrimitive(1))
        if (flots.isNotEmpty()) saved["flots"] = JsonArray(flots)
        saved["routes"] = routes
        return JsonObject(saved)
    }
}
