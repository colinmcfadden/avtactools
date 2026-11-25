package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a person does to a pack's items besides editing them: the pure part of the web's `packActions.js`. */
public object PackActions {
    /**
     * A version of an item in the form the library keeps each kind (`libraryData`, held to `shared.json`): an LZ/PZ's document, a set's points
     * and a set of threats as they are; a route set's sketched routes as `{version: 1, routes}` with its `flots` and `phaseLines` beside them when it has any, any other
     * field of the set and any other version not kept, and routes that are not a list none.
     *
     * A document has no such form, so it is null here: the Library is given a pack's document only by the server's own route, which
     * copies the files it checked (docs/DOCUMENTS.md §7), never from data a client sends. The web's `libraryData` would make it an
     * empty set of routes, but no web caller asks it for a document.
     */
    public fun libraryData(kind: String, data: JsonElement?): JsonElement? {
        if (kind == "document") return null
        if (kind == "lz" || kind == "pointset" || kind == "threatset") return data
        val routes = (data as? JsonObject)?.get("routes") as? JsonArray ?: JsonArray(emptyList())
        val saved = linkedMapOf<String, JsonElement>("version" to JsonPrimitive(1))
        // The lines kept beside the routes, FLOTs first: whatever is not an object with an id is none, and a key with none is left out.
        for (key in listOf("flots", "phaseLines")) {
            val lines = ((data as? JsonObject)?.get(key) as? JsonArray).orEmpty().filter { it is JsonObject && "id" in it }
            if (lines.isNotEmpty()) saved[key] = JsonArray(lines)
        }
        saved["routes"] = routes
        return JsonObject(saved)
    }
}
