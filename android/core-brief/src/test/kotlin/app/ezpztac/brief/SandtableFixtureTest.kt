package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * contracts/fixtures/brief/sandtable.json: what the live sandtable makes of each brief in brief/compile.json (each step as it is listed, and what the map shows for
 * it), written by the web (briefSandtableFixtures.test.js over sandtableSteps.js). Every case is held strictly: null kept apart from absent, numbers as JavaScript
 * writes them, every object's keys in JavaScript's order. Each case's model is compile.json's, and the one [CompileBrief] makes of the same inputs (held to it by
 * [CompileFixtureTest]) gives the same. Then the rules the web's test asks of the cases directly.
 */
class SandtableFixtureTest {
    private val compiled = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private val fixture = Fixtures.load("brief/sandtable.json")
    private val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

    /** What the web's fixture holds for [model]: its overview, and each step as listed with its view, as `{ ...listed, view }`. */
    private fun sandtableCase(name: JsonElement, model: JsonObject): JsonObject = buildJsonObject {
        put("name", name)
        put("overview", SandtableSteps.overviewBounds(model))
        put(
            "steps",
            JsonArray(
                SandtableSteps.steps(model).mapIndexed { i, listed -> JsonObject((listed as JsonObject) + ("view" to (SandtableSteps.view(model, i) ?: JsonNull))) },
            ),
        )
    }

    private fun compile(c: JsonObject): JsonObject = CompileBrief.compile(BriefFormats.format(c["format"]!!.jsonPrimitive.content)!!, c["document"], c["plan"], c["context"])

    @Test
    fun `holds a case for every compiled brief, from compile json`() {
        assertEquals("brief/compile.json", fixture["from"]!!.jsonPrimitive.content)
        assertEquals(compiled.map { it["name"] }, cases.map { it["name"] })
    }

    @TestFactory
    fun `every case of sandtable json, from compile json's model`(): List<DynamicTest> = cases.mapIndexed { i, c ->
        DynamicTest.dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val actual = sandtableCase(compiled[i]["name"]!!, compiled[i]["expected"]!!.jsonObject)
            assertEquals(emptyList<String>(), StrictJson.differences(c, actual).take(40))
        }
    }

    @TestFactory
    fun `every case of sandtable json, from the brief compiled here`(): List<DynamicTest> = cases.mapIndexed { i, c ->
        DynamicTest.dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val actual = sandtableCase(compiled[i]["name"]!!, compile(compiled[i]))
            assertEquals(emptyList<String>(), StrictJson.differences(c, actual).take(40))
        }
    }

    // -- The web's own questions of the cases (briefSandtableFixtures.test.js) -----------------------------------------------------------------------------

    private val opdk = compiled[0]["expected"]!!.jsonObject
    private val listed = SandtableSteps.listed(opdk)
    private fun step(id: String) = listed.indexOfFirst { it.id == id }.let { i -> listed[i] to SandtableSteps.look(opdk, i)!! }

    @Test
    fun `calls a step by its place in the brief, never by a page`() {
        assertEquals("Step 1 of ${listed.size}", listed[0].position)
        compiled.forEach { c ->
            val made = sandtableCase(c["name"]!!, c["expected"]!!.jsonObject).toString()
            assertFalse(Regex("page", RegexOption.IGNORE_CASE).containsMatchIn(made), c["name"].toString())
        }
    }

    @Test
    fun `names what a step is about and when the plan has it there`() {
        val (hawk, _) = step("4.e@lz:lz-hawk")
        assertEquals("4.e", hawk.number)
        assertEquals("Landing sequence", hawk.section)
        assertEquals("LZ HAWK", hawk.title)
        assertEquals("Flight lead", hawk.who)
        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(hawk.time!!), hawk.time)
        val (roll, _) = step("1")
        assertEquals("Roll call", roll.section)
        assertEquals("Roll call", roll.title)
        assertEquals(null, roll.time)
    }

    @Test
    fun `lights the route in focus, dims the others on a route step, and lights the departure's route`() {
        val enroute = listed.indices.map { SandtableSteps.look(opdk, it)!! }.first { it.kind == "route" }
        assertEquals(listOf(enroute.focus), enroute.routes.filter { it.lit }.map { it.ref })
        assertTrue(enroute.routes.filter { !it.lit }.all { it.dimmed && !it.labelled })
        val departure = listed.indices.map { SandtableSteps.look(opdk, it)!! }.first { it.kind == "departure" }
        assertNotNull(departure.focus)
        assertEquals(departure.focus, departure.routes.firstOrNull { it.lit }?.ref)
    }

    @Test
    fun `shows the LZ-PZ in focus close, and the rally points only at the contingencies`() {
        val (_, hawk) = step("4.e@lz:lz-hawk")
        assertEquals(listOf("lz:lz-hawk"), hawk.stops.filter { it.close }.map { it.ref })
        assertEquals(listOf("rally"), listed.indices.map { SandtableSteps.look(opdk, it)!! }.filter { it.rally }.map { it.kind })
    }

    @Test
    fun `shows the whole plan, unlit, over the overview where a step has no figure`() {
        val none = listed.indices.map { SandtableSteps.look(opdk, it)!! }.first { it.kind == null }
        assertEquals(SandtableSteps.boundsOf(SandtableSteps.overviewBounds(opdk)), none.bounds)
        assertFalse(none.routes.any { it.lit || it.dimmed || it.labelled })
        assertFalse(none.stops.any { it.close })
    }

    @Test
    fun `has nowhere to fly for an empty plan`() {
        val empty = compiled.first { it["name"]!!.jsonPrimitive.content.contains("empty plan") }["expected"]!!.jsonObject
        assertEquals(JsonNull, SandtableSteps.overviewBounds(empty))
        val views = SandtableSteps.listed(empty).indices.map { SandtableSteps.look(empty, it)!! }
        assertTrue(views.isNotEmpty())
        assertTrue(views.all { it.bounds == null && it.routes.isEmpty() })
    }

    @Test
    fun `answers nothing for a step that is not there, nor for a model that is not one`() {
        assertEquals(null, SandtableSteps.view(opdk, listed.size))
        assertEquals(null, SandtableSteps.view(opdk, -1))
        assertEquals(null, SandtableSteps.view(null, 0))
        assertEquals(JsonArray(emptyList()), SandtableSteps.steps(JsonPrimitive("not a brief")))
        assertEquals(JsonNull, SandtableSteps.overviewBounds(null))
    }
}
