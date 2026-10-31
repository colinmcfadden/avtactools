package app.ezpztac.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the unit builder, or a preset, hands over to make a unit. Every part is optional, as on the web. */
public data class UnitConfig(
    val id: String? = null,
    val path: String? = null,
    val sidc: String? = null,
    val uniqueDesignation: String? = null,
    val higherFormation: String? = null,
)

/** A unit on the map: a MIL-STD symbol with a designation and the formation above it, kept as the web keeps it (`units` of a diagram). */
public object UnitMarkers {
    /**
     * A new unit (`createUnit`): it sits [offset] degrees north and east of [center], so it does not land on the target itself. Parts that
     * were not given are left out, as `JSON.stringify` leaves out what is undefined; `type` is the preset's id.
     */
    public fun create(config: UnitConfig, center: LatLon, offset: Double, id: String): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["id"] = JsonPrimitive(id)
        config.id?.let { fields["type"] = JsonPrimitive(it) }
        config.path?.let { fields["path"] = JsonPrimitive(it) }
        config.sidc?.let { fields["sidc"] = JsonPrimitive(it) }
        config.uniqueDesignation?.let { fields["uniqueDesignation"] = JsonPrimitive(it) }
        config.higherFormation?.let { fields["higherFormation"] = JsonPrimitive(it) }
        fields["lat"] = JsonPrimitive(center.lat + offset)
        fields["lon"] = JsonPrimitive(center.lon + offset)
        return JsonObject(fields)
    }
}
