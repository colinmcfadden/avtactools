package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A backstop's arrow is one value ([LzPackKind.arrowWhole]): its tail and its tip, or its tail alone while it points at the target. A change to part of it goes as
 * the whole arrow, so one person's move and another's aim never leave a tail of one and a tip of the other. Android only (the web sends the part that changed).
 */
class PackBackstopWholeTest {
    private val kind = LzPackKind(PackFixtures.env())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val hawk = PackItemView(
        "lz-1", "lz", "LZ HAWK",
        json(
            """
            {"schemaVersion": 2, "status": "analyzed",
             "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"}, "flightData": {},
             "analysis": {"detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "results": {"area": 1}, "gridElevation": "1200", "latLong": ""},
             "graphics": {
               "helicopters": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "doghouses": [], "units": [], "measurements": [], "exportBox": null,
               "backstops": [
                 {"id": "bs-1", "points": [{"lat": 34.52, "lon": -84.11}, {"lat": 34.52, "lon": -84.09}], "arrow": {"tail": {"lat": 34.519, "lon": -84.1}, "tip": {"lat": 34.505, "lon": -84.1}}},
                 {"id": "bs-2", "points": [{"lat": 34.48, "lon": -84.11}, {"lat": 34.48, "lon": -84.09}]}
               ]}}
            """,
        ),
    )

    private val local = localId(hawk)

    private fun opened(): Diagram = kind.fromItem(local, hawk, null)

    private fun withBackstop(doc: Diagram, id: String, item: String): Diagram = DiagramOps.replaceGraphic(doc, "backstops", JsonPrimitive(id), json(item))

    private fun sent(mine: Diagram): List<JsonObject> {
        val plan = checkNotNull(PackItemSync.flushPlan(kind, local, PackItemSync.settled(kind, local, hawk), hawk, mine, "Sam B.")) { "nothing was sent" }
        return plan.ops.map { JsonObject(it - "summary") }
    }

    private fun backstopPath(id: String) = JsonArray(listOf(JsonPrimitive("graphics"), JsonPrimitive("backstops"), JsonObject(mapOf("id" to JsonPrimitive(id)))))

    @Test
    fun `a tip aimed elsewhere is sent with the arrow's tail, as one patch of the backstop`() {
        val aimed = withBackstop(
            opened(), "bs-1",
            """{"id": "bs-1", "points": [{"lat": 34.52, "lon": -84.11}, {"lat": 34.52, "lon": -84.09}], "arrow": {"tail": {"lat": 34.519, "lon": -84.1}, "tip": {"lat": 34.505, "lon": -84.097}}}""",
        )
        val op = sent(aimed).single()
        assertEquals("patch", (op["type"] as JsonPrimitive).content)
        assertValue(backstopPath("bs-1"), op["path"], "the backstop itself")
        assertValue(json("""{"arrow": {"tail": {"lat": 34.519, "lon": -84.1}, "tip": {"lat": 34.505, "lon": -84.097}}}"""), op["value"], "the whole arrow")
    }

    @Test
    fun `an arrow put back on the target goes as its tail alone, the tip taken away with it`() {
        val back = withBackstop(
            opened(), "bs-1",
            """{"id": "bs-1", "points": [{"lat": 34.52, "lon": -84.11}, {"lat": 34.52, "lon": -84.09}], "arrow": {"tail": {"lat": 34.519, "lon": -84.1}}}""",
        )
        val op = sent(back).single()
        assertValue(backstopPath("bs-1"), op["path"], "the backstop")
        assertValue(json("""{"arrow": {"tail": {"lat": 34.519, "lon": -84.1}}}"""), op["value"], "the arrow with no tip")
        // What it makes in the pack: the arrow as the device has it, no tip left over from before.
        val applied = PackOps.apply(mapOf("lz-1" to PackItemState("lz", "LZ HAWK", hawk.data, false)), JsonObject(op + ("item" to JsonPrimitive("lz-1"))))
        assertEquals(OpStatus.APPLIED, applied.status, applied.reason)
        val arrow = applied.items.getValue("lz-1").data.jsonObject.getValue("graphics").jsonObject.getValue("backstops").jsonArray[0].jsonObject.getValue("arrow")
        assertValue(json("""{"tail": {"lat": 34.519, "lon": -84.1}}"""), arrow, "in the pack")
    }

    @Test
    fun `a backstop given its first arrow sends it whole`() {
        val moved = withBackstop(
            opened(), "bs-2",
            """{"id": "bs-2", "points": [{"lat": 34.48, "lon": -84.11}, {"lat": 34.48, "lon": -84.09}], "arrow": {"tail": {"lat": 34.481, "lon": -84.1}}}""",
        )
        val op = sent(moved).single()
        assertValue(backstopPath("bs-2"), op["path"], "the backstop")
        assertValue(json("""{"arrow": {"tail": {"lat": 34.481, "lon": -84.1}}}"""), op["value"], "its arrow")
    }

    @Test
    fun `only the arrow is made whole, and the rest of a change to backstops goes as it would`() {
        val ops = listOf(
            json("""{"type": "set", "path": ["graphics", "backstops", {"id": "bs-1"}, "points"], "value": []}"""),
            json("""{"type": "set", "path": ["graphics", "backstops", {"id": "bs-1"}, "arrow", "tip", "lat"], "value": 34.5}"""),
            json("""{"type": "set", "path": ["graphics", "backstops", {"id": "bs-1"}, "arrow", "tail", "lon"], "value": -84.2}"""),
        )
        val after = json("""{"graphics": {"backstops": [{"id": "bs-1", "points": [], "arrow": {"tail": {"lat": 1, "lon": -84.2}, "tip": {"lat": 34.5, "lon": 2}}}]}}""")
        val whole = kind.keepWhole(ops, after)
        assertEquals(2, whole.size, "the points as they were, and one patch for both parts of the arrow")
        assertEquals(ops[0], whole[0])
        assertValue(json("""{"arrow": {"tail": {"lat": 1, "lon": -84.2}, "tip": {"lat": 34.5, "lon": 2}}}"""), whole[1]["value"], "the arrow, once")
        assertTrue(kind.keepWhole(listOf(ops[0]), after) == listOf(ops[0]), "nothing about an arrow: unchanged")
    }

    @Test
    fun `an arrow that went is sent as null, and a patch of the backstop keeps what else it changed`() {
        val ops = listOf(json("""{"type": "patch", "path": ["graphics", "backstops", {"id": "bs-1"}], "value": {"arrow": null, "note": "x"}}"""))
        val after = json("""{"graphics": {"backstops": [{"id": "bs-1", "points": [], "note": "x"}]}}""")
        val op = kind.keepWhole(ops, after).single()
        assertValue(JsonObject(mapOf("arrow" to JsonNull, "note" to JsonPrimitive("x"))), op["value"], "the patch, its arrow null")
    }
}
