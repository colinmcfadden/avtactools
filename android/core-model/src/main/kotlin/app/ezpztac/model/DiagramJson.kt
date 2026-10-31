package app.ezpztac.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A diagram as the JSON the web saves: every field written, nulls included, as `serializeLzDiagram` writes them. The library's
 * records and a mission pack's items are both made from it, so the two cannot drift apart.
 */
public object DiagramJson {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    /** [diagram] as JSON, exactly as it is: to save one clean, pass it through [DiagramNormalizer.serialize] first. */
    public fun encode(diagram: Diagram): JsonObject = json.encodeToJsonElement(Diagram.serializer(), diagram).jsonObject

    /** A target as JSON: `{lat, lon, mgrs}`. */
    public fun encode(target: DiagramTarget): JsonObject = json.encodeToJsonElement(DiagramTarget.serializer(), target).jsonObject
}
