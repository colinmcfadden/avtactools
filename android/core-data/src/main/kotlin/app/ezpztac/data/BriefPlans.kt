package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.ThreatEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a brief is built from (docs/MISSION_BRIEF.md §1), in the shape core-brief's `CompileBrief` reads (the web's `feature/brief/briefPlan.js`): the LZ/PZs as
 * `{id, name, diagram}` and the route sets as `{id, name, data: {version, routes}}`, then the threats, the aircraft profiles and the mission aircraft. In a pack,
 * everything in it (the design: "Built from everything in OP DK"); outside one, the LZ/PZs and route sets open in this session. An id is what the brief's flow refers
 * to an item by (`lz:<id>`, `route:<id>/<route id>`), so it is the one the item keeps: a pack item's uuid, a Library record's uuid.
 */
object BriefPlans {
    /** One LZ/PZ or route set of the session: the record's uuid, its name and its document as it is now (the open one's with its edits not yet written). */
    data class SessionItem(val id: String, val name: String, val document: JsonObject)

    /** The plan from a pack's items: its LZ/PZs and route sets, by item uuid. An item with no document (one being made) is left out, as the web's `item.data` test does. */
    fun fromPack(name: String, items: List<PackItemView>): JsonObject {
        val live = items.filter { it.data is JsonObject }
        return plan(
            name,
            lzs = live.filter { it.kind == "lz" }.map { item -> buildJsonObject { put("id", item.uuid); put("name", item.name); put("diagram", item.data) } },
            routeSets = live.filter { it.kind == "route" }.map { item -> buildJsonObject { put("id", item.uuid); put("name", item.name); put("data", item.data) } },
        )
    }

    /**
     * The plan from this session: the LZ/PZs open, by record uuid, and the route sets open with any routes (the web's `routes.length > 0`), as `{version, routes}`. A
     * route set's document is the web's `route_data`; a mission's is its routes read from its file.
     */
    fun fromSession(lzs: List<SessionItem>, routeSets: List<SessionItem>): JsonObject = plan(
        name = "",
        lzs = lzs.map { item -> buildJsonObject { put("id", item.id); put("name", item.name); put("diagram", item.document) } },
        routeSets = routeSets
            .filter { (it.document["routes"] as? JsonArray)?.isNotEmpty() == true }
            .map { item ->
                buildJsonObject {
                    put("id", item.id)
                    put("name", item.name)
                    put("data", buildJsonObject { put("version", 1); put("routes", item.document["routes"] as JsonArray) })
                }
            },
    )

    private fun plan(name: String, lzs: List<JsonObject>, routeSets: List<JsonObject>): JsonObject = buildJsonObject {
        put("name", name)
        put("lzs", JsonArray(lzs))
        put("routeSets", JsonArray(routeSets))
    }

    private val profileJson = Json { encodeDefaults = true; explicitNulls = true }

    /**
     * [plan] with what the brief also reads beside the plan: the threats (each `{name, lat, lon}`, the only fields it briefs; none for an account without threats, as
     * the web passes none), the aircraft profiles (as the API gives them: what `findProfile` matches a placed aircraft's slug against) and the mission aircraft's slug.
     */
    fun withExtras(plan: JsonObject, threats: List<ThreatEntry>, profiles: List<AircraftProfile>, missionAircraft: String?): JsonObject =
        JsonObject(
            plan + mapOf(
                "threats" to JsonArray(threats.map { entry -> buildJsonObject { put("name", entry.threat.name); put("lat", entry.threat.lat); put("lon", entry.threat.lon) } }),
                "aircraftProfiles" to JsonArray(profiles.map { profileJson.encodeToJsonElement(AircraftProfile.serializer(), it) }),
                "missionAircraft" to (missionAircraft?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )

    /** "5 LZ/PZs, 2 route sets, 1 threat": what the brief is built from, as the menu says it. */
    fun summary(plan: JsonObject, threatCount: Int = 0): String {
        fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        val parts = mutableListOf(
            count(sizeOf(plan["lzs"]), "LZ/PZ", "LZ/PZs"),
            count(sizeOf(plan["routeSets"]), "route set", "route sets"),
        )
        if (threatCount > 0) parts += count(threatCount, "threat", "threats")
        return parts.joinToString(", ")
    }

    private fun sizeOf(value: JsonElement?): Int = (value as? JsonArray)?.size ?: 0
}
