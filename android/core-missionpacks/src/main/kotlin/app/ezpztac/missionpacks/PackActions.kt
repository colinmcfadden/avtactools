package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a person does to a pack's items besides editing them: the pure part of the web's `packActions.js`. */
public object PackActions {
    /**
     * A version of an item in the form the library keeps each kind (`libraryData`, held to `shared.json`): an LZ/PZ's document and a
     * set's points as they are; a route set's sketched routes as `{version: 1, routes}`, any other field of the set and any other version
     * not kept, and routes that are not a list none.
     */
    public fun libraryData(kind: String, data: JsonElement?): JsonElement? {
        if (kind == "lz" || kind == "pointset") return data
        val routes = (data as? JsonObject)?.get("routes") as? JsonArray ?: JsonArray(emptyList())
        return JsonObject(linkedMapOf("version" to JsonPrimitive(1), "routes" to routes))
    }
}
