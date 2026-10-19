package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/** What compile.json cannot show: inputs no client writes, the port's own differences from the web, and that nothing reads the device. */
class CompileBriefTest {
    private val amb = BriefFormats.format("amb")!!
    private val cases = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private val route = """
        {"id": "r1", "name": "ONE", "color": "#FF453A", "points": [
          {"id": "p1", "name": ".SP", "ptType": "ip", "kind": "amps", "lat": 34.0, "lon": -84.0},
          {"id": "p2", "name": "LZ", "ptType": "target", "kind": "amps", "lat": 34.1, "lon": -84.1}],
         "plan": {"airspeed": {"value": 100, "type": "indicated"}, "date": "2026-10-16", "perPoint": {"p1": {"clock": "10:00:00"}, "p2": null}}}
    """.trimIndent()

    @Test
    fun `compiles whatever it is given without throwing`() {
        val plans = listOf(
            "null", "5", "\"text\"", "[]", "{}",
            """{"lzs": [null, 5, {"id": 1, "diagram": "x"}, {"id": null}, {"id": "a", "name": 5, "diagram": {"target": [34, -84]}}, {"name": "no id"}]}""",
            """{"routeSets": [null, {"id": "s"}, {"id": "s2", "data": {"routes": [null, {"id": "r", "points": [null]}, {"id": "q", "points": [{"lat": "34", "lon": -84}]},
               {"id": "x", "points": [], "plan": "nope"}, {"id": "y", "points": [{"id": "a", "lat": 34, "lon": -84}], "plan": {"airspeed": {"value": "fast"}}}]}}]}""",
            """{"threats": [null, {"lat": 34}, {"lat": 34, "lon": -84, "name": {"a": 1}}], "aircraftProfiles": "x", "missionAircraft": 7}""",
        )
        val documents = listOf(
            "null", "\"doc\"",
            """{"flow": [null, 5, {"ref": 5}, {"ref": "lz:a"}, {"ref": "nothing"}], "answers": {"1.b": {"value": 5}, "1.a": [1], "5.a.i": "custom"},
               "options": {"0": 1, "lzCards": "", "nested": {"2": 1.50, "b": [1.0]}}, "constants": [null, {"id": 5}, {"label": "  "}], "rallyPoints": [null, 5],
               "scripts": {"1": 5}, "include": {"threats": "false"}}""",
        )
        val contexts = listOf("null", "{}", """{"generatedAt": 5, "utcOffsetMinutes": "x", "weather": "rain", "notams": []}""")
        plans.forEach { plan -> documents.forEach { doc -> contexts.forEach { context ->
            val model = CompileBrief.compile(amb, json(doc), json(plan), json(context))
            assertEquals("ezpz-brief-model", model.text("format"))
        } } }
    }

    @Test
    fun `writes the document's options in JavaScript's order, and numbers as JavaScript writes them`() {
        val model = CompileBrief.compile(amb, json("""{"options": {"zebra": 1.50, "2": true, "lzCards": false, "0": {"b": 2.0, "1": 1e2}}}"""))
        assertEquals(
            """{"0":{"1":100,"b":2},"2":true,"script":true,"constants":true,"lzCards":false,"navLogs":true,"iimc":true,"writeIns":true,"zebra":1.5}""",
            model["options"].toString(),
        )
    }

    @Test
    fun `briefs an item the person's flow names twice, twice`() {
        val plan = json("""{"name": "T", "routeSets": [{"id": "s", "data": {"routes": [$route]}}], "lzs": [{"id": "lz", "diagram": {"name": "LZ BIRD", "target": {"lat": 34.1, "lon": -84.1}}}]}""")
        val doc = json("""{"flow": [{"ref": "route:s/r1"}, {"ref": "lz:lz"}, {"ref": "lz:lz", "role": "PZ"}]}""")
        val model = CompileBrief.compile(amb, doc, plan, json("""{"generatedAt": "2026-10-16T12:00:00Z"}"""))
        assertEquals(listOf("route", "stop", "stop"), model.list("flow").map { it.text("kind") })
        assertEquals(listOf("LZ", "PZ"), model.list("flow").drop(1).map { it.text("role") })
        // Each is briefed for its own role.
        assertTrue(model.list("steps").any { it.text("id") == "4.d@lz:lz" })
    }

    @Test
    fun `reads a route whose per-point values a mission pack left null`() {
        val plan = json("""{"routeSets": [{"id": "s", "data": {"routes": [$route]}}]}""")
        val model = CompileBrief.compile(amb, null, plan, json("""{"generatedAt": "2026-10-16T12:00:00Z", "utcOffsetMinutes": -240}"""))
        val flow = model.list("flow").single()
        assertEquals("10:00:00", flow.text("start"))
        assertNotEquals(JsonNull, flow["end"])
    }

    @Test
    fun `does not brief a route with a point that has no position, so a flow naming it says it is gone`() {
        val bad = route.replace("\"lat\": 34.1", "\"lat\": \"34.1\"")
        val plan = json("""{"routeSets": [{"id": "s", "data": {"routes": [$bad]}}]}""")
        val model = CompileBrief.compile(amb, json("""{"flow": [{"ref": "route:s/r1"}]}"""), plan, json("{}"))
        assertEquals(JsonObject(mapOf("ref" to JsonPrimitive("route:s/r1"), "gone" to JsonPrimitive(true))), model.list("flow").single())
    }

    @Test
    fun `gives the same model in any time zone`() {
        val saved = TimeZone.getDefault()
        try {
            listOf("Asia/Kathmandu", "Pacific/Chatham", "America/Los_Angeles").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                cases.forEach { c ->
                    val model = CompileBrief.compile(BriefFormats.format(c.text("format"))!!, c["document"], c["plan"], c["context"])
                    assertEquals(emptyList<String>(), StrictJson.differences(c["expected"]!!, model), "${c.text("name")} in $zone")
                }
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `compiles the same brief the same way every time`() {
        val c = cases[0]
        val format = BriefFormats.format(c.text("format"))!!
        assertEquals(CompileBrief.compile(format, c["document"], c["plan"], c["context"]), CompileBrief.compile(format, c["document"], c["plan"], c["context"]))
    }

    @Test
    fun `says a write-in's purpose, and null where nothing names it`() {
        val parts = JsonArray(listOf(JsonObject(mapOf("writeIn" to JsonPrimitive("x"), "label" to JsonNull))))
        assertEquals(CompileBrief.WRITE_IN, CompileBrief.partsText(parts))
        val model = CompileBrief.compile(amb)
        val writeIns = model.list("steps").flatMap { s -> s["script"]!!.jsonArray.flatMap { it.jsonArray } }.map { it.jsonObject }.filter { "writeIn" in it }
        assertTrue(writeIns.isNotEmpty())
        writeIns.forEach { assertEquals(listOf("writeIn", "label"), it.keys.toList()) }
    }

    // -- What an independent run of the web's compile.js beside this port found apart, each now as the web says it (expected values from the web). --

    /** [tree] with the value at [path] (object keys, list indexes as text) put through [change]. */
    private fun edited(tree: JsonElement, path: List<String>, change: (JsonElement?) -> JsonElement?): JsonElement {
        if (path.isEmpty()) return change(tree) ?: JsonNull
        val key = path.first()
        return when (tree) {
            is JsonObject -> {
                val fields = LinkedHashMap(tree)
                val next = edited(tree[key] ?: JsonObject(emptyMap()), path.drop(1), change)
                fields[key] = next
                JsonObject(fields)
            }
            is JsonArray -> JsonArray(tree.mapIndexed { i, e -> if (i.toString() == key) edited(e, path.drop(1), change) else e })
            else -> error("no $key in $tree")
        }
    }

    private val opDk = cases[0]
    private val alderaanPlan = listOf("plan", "routeSets", "0", "data", "routes", "0", "plan")

    private fun compiled(plan: JsonElement): JsonObject = CompileBrief.compile(amb, opDk["document"], plan, opDk["context"])

    private fun alderaan(model: JsonObject) = model["geometry"]!!.jsonObject["routes"]!!.jsonObject["route:set-opdk/r-alderaan"]!!.jsonObject

    @Test
    fun `an airspeed with no type is planned as true airspeed and said as ground speed, as the web does`() {
        val plan = edited(opDk["plan"]!!, alderaanPlan.drop(1) + "airspeed") { json("""{"value": 120}""") }
        val first = alderaan(compiled(plan))["log"]!!.jsonArray[0].jsonObject
        assertEquals("0+07+44", first.text("time"))
        assertEquals("12:04:15", first.text("clock"))
        assertEquals("120 kt GS", first.text("airspeed"))
    }

    @Test
    fun `a plan date past its month's end rolls over, as the web's Date reads it`() {
        val model = compiled(edited(opDk["plan"]!!, alderaanPlan.drop(1) + "date") { JsonPrimitive("2026-02-30") })
        assertEquals("12:03:51", alderaan(model)["log"]!!.jsonArray[0].jsonObject.text("clock"))
        assertEquals("2026-03-02T15:56:32.000Z", model["places"]!!.jsonObject["departure"]!!.jsonObject.text("at"))
        assertEquals("30 Feb 2026", model["mission"]!!.jsonObject.text("date"))
    }

    @Test
    fun `a two-digit year is in the 1900s, as Date-UTC puts it`() {
        var plan = opDk["plan"]!!
        listOf("0", "1").forEach { i -> plan = edited(plan, listOf("routeSets", "0", "data", "routes", i, "plan", "date")) { JsonPrimitive("0026-10-16") } }
        val model = compiled(plan)
        assertEquals("1926-10-16T15:56:32.000Z", model["places"]!!.jsonObject["departure"]!!.jsonObject.text("at"))
        assertEquals("16 Oct 0026", model["mission"]!!.jsonObject.text("date"))
        val illumination = model.list("steps").flatMap { it.list("facts") }.first { it.text("key") == "light.illumination" }
        assertEquals("73%", illumination.text("value"))
    }

    @Test
    fun `an object as the mission aircraft is one airframe for every aircraft placed as it`() {
        var plan = opDk["plan"]!!
        plan.jsonObject["lzs"]!!.jsonArray.indices.forEach { i ->
            plan = edited(plan, listOf("lzs", i.toString(), "diagram", "graphics", "helicopters")) { list ->
                JsonArray(list!!.jsonArray.map { JsonObject(it.jsonObject - "profileId") })
            }
        }
        plan = edited(plan, listOf("missionAircraft")) { json("""{"slug": "x"}""") }
        assertTrue(compiled(plan)["mission"]!!.jsonObject.text("summary").startsWith("4 × [OBJECT OBJECT] from 3MILE"))
    }

    @Test
    fun `a diagram's name that is not text is said as JavaScript says it`() {
        val plan = edited(opDk["plan"]!!, listOf("lzs", "1", "diagram", "name")) { json("""{"a": 1}""") }
        assertEquals("[object Object]", compiled(plan).list("flow").single { it.text("ref") == "lz:lz-hawk" }.text("name"))
    }

    @Test
    fun `a part with no text says nothing, as join does`() {
        val parts = JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("a"))), JsonObject(emptyMap()), JsonObject(mapOf("text" to JsonNull))))
        assertEquals("a", CompileBrief.partsText(parts))
        assertEquals(CompileBrief.WRITE_IN, CompileBrief.partsText(JsonArray(listOf(JsonObject(mapOf("writeIn" to JsonNull))))))
    }

    @Test
    fun `a point whose id is empty text is no named point, and the others keep their own types`() {
        val plan = edited(opDk["plan"]!!, listOf("routeSets", "0", "data", "routes", "0", "points", "2", "id")) { json("[]") }
        val points = alderaan(compiled(plan))["points"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("3MILE", "S-EP", "ACP2", "RP1", "BLUEBIRD", "HAWK"), points.map { it.text("name") })
        assertEquals(listOf("ip", "turn", "turn", "ip", "target", "target"), points.map { it.text("type") })
    }
}
