package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.model.RouteSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A route set's FLOTs and phase lines are its own fields (`flots`, `phaseLines`, kept in [RouteSet.extras]), and a change to them reaches the pack as one
 * to its routes does ([RoutePackKind.docOf]): the pack's control measures are a route set with no routes, every change to them a change of its lines.
 */
class PackRouteLinesTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val measures = PackItemView(
        "rt-1", "route", "CONTROL MEASURES",
        json("""{"version": 1, "flots": [{"id": "flot-1", "points": [{"lat": 34.5, "lon": -84.1}, {"lat": 34.5, "lon": -84.0}]}], "routes": []}"""),
    )

    private val local = localId(measures)

    private fun opened(item: PackItemView = measures): RouteSet = RoutePackKind.fromItem(localId(item), item, null)

    private fun lines(set: RouteSet, key: String, value: String?): RouteSet =
        set.copy(extras = JsonObject(if (value == null) set.extras - key else set.extras + (key to Json.parseToJsonElement(value))))

    private fun sent(item: PackItemView, mine: RouteSet): List<JsonObject>? =
        PackItemSync.flushPlan(RoutePackKind, localId(item), PackItemSync.settled(RoutePackKind, localId(item), item), item, mine, "Sam B.")?.ops?.map { JsonObject(it - "summary") }

    @Test
    fun `a FLOT flipped is sent, and the pack's lines are then what the device has`() {
        val flipped = lines(opened(), "flots", """[{"id": "flot-1", "points": [{"lat": 34.5, "lon": -84.1}, {"lat": 34.5, "lon": -84.0}], "flipped": true}]""")
        val ops = checkNotNull(sent(measures, flipped)) { "nothing was sent" }
        var items = mapOf("rt-1" to PackItemState("route", measures.name, measures.data, false))
        for (op in ops) items = PackOps.apply(items, JsonObject(op + ("item" to JsonPrimitive("rt-1")))).also { assertEquals(OpStatus.APPLIED, it.status, it.reason) }.items
        assertValue(flipped.extras.getValue("flots"), items.getValue("rt-1").data.jsonObject["flots"], "the FLOT flipped, in the pack")
    }

    @Test
    fun `a phase line added goes after the FLOTs and before the routes, as the web writes it, and nothing is sent for lines that did not change`() {
        val added = lines(opened(), "phaseLines", """[{"id": "pl-1", "name": "RED", "points": [{"lat": 34.6, "lon": -84.1}, {"lat": 34.6, "lon": -84.0}]}]""")
        val doc = RoutePackKind.docOf(added, measures.data).jsonObject
        assertEquals(listOf("version", "flots", "phaseLines", "routes"), doc.keys.toList())
        assertNull(sent(measures, opened()), "the set as it came sends nothing")
    }

    @Test
    fun `the last line removed leaves its key out, sent as null as a key that went always is`() {
        val none = lines(opened(), "flots", null)
        val doc = RoutePackKind.docOf(none, measures.data).jsonObject
        assertEquals(listOf("version", "routes"), doc.keys.toList())
        val op = checkNotNull(sent(measures, none)).single()
        assertValue(JsonObject(mapOf("flots" to JsonNull)), op["value"], "the FLOTs gone")
        assertValue(JsonArray(emptyList()), op["path"], "a patch of the set")
    }

    @Test
    fun `a FLOT added to a set that has only phase lines goes before them`() {
        val item = measures.copy(data = json("""{"version": 1, "phaseLines": [{"id": "pl-1", "name": "RED", "points": []}], "routes": []}"""))
        val added = lines(opened(item), "flots", """[{"id": "flot-2", "points": []}]""")
        assertEquals(listOf("version", "flots", "phaseLines", "routes"), RoutePackKind.docOf(added, item.data).jsonObject.keys.toList())
        assertEquals(local, localId(measures))
    }
}
