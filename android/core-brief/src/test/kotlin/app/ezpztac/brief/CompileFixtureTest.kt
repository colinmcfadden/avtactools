package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * contracts/fixtures/brief/compile.json: what compileBrief makes of a plan, a format and the person's answers, written by the web
 * (briefCompileFixtures.test.js over briefCases.js). Every case is held strictly: null kept apart from absent, numbers as JavaScript
 * writes them, every object's keys in JavaScript's order. Then the rules the web's test asks of the cases directly.
 */
class CompileFixtureTest {
    private val cases = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray.map { it.jsonObject }

    private fun compile(c: JsonObject): JsonObject {
        val format = BriefFormats.format(c["format"]!!.jsonPrimitive.content)!!
        return CompileBrief.compile(format, c["document"], c["plan"], c["context"])
    }

    @TestFactory
    fun `every case of compile json`(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val differences = StrictJson.differences(c["expected"]!!, compile(c))
            assertEquals(emptyList<String>(), differences.take(40))
        }
    }

    private val amb by lazy { compile(cases[0]) }
    private val hasty by lazy { compile(cases[1]) }

    private fun JsonObject.steps() = this["steps"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.step(id: String) = steps().first { it["id"]!!.jsonPrimitive.content == id }
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.items() = this["items"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.item(id: String) = items().first { it.text("id") == id }
    private fun script(step: JsonObject) = CompileBrief.scriptText(step).joinToString(" ")

    @Test
    fun `briefs the routes and the LZ-PZs in the order the flight meets them`() {
        assertEquals(
            listOf(
                "1", "2", "3", "4.a", "4.b",
                "4.c@route:set-opdk/r-alderaan", "4.e@lz:lz-bluebird", "4.e@lz:lz-hawk",
                "4.c@route:set-opdk/r-bespin", "4.d@lz:pz-ibis",
                "4.f@lz:farp-bucees", "4.e@lz:lz-decoy",
                "5.a", "5.b", "6", "7", "8", "9",
            ),
            amb.steps().map { it.text("id") },
        )
    }

    @Test
    fun `gives every step a page, the first the cover, then the appendices`() {
        assertEquals(amb.steps().indices.map { (it + 1).toString() }, amb.steps().map { it.text("page") })
        val pages = amb["pages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("cover", pages[0].text("kind"))
        assertEquals(5, pages.count { it.text("kind") == "lzCard" })
        assertEquals(2, pages.count { it.text("kind") == "navLog" })
        assertEquals("iimc", pages.last().text("kind"))
        // The packet by section, never by page: Chromium decides where pages break.
        val roll = script(amb.step("1"))
        assertTrue(roll.contains("The packet holds the brief (18 steps), 5 LZ/PZ cards, 2 nav logs, 12 strip charts and the IIMC card"), roll)
        assertFalse(Regex("[0-9]+–[0-9]+").containsMatchIn(roll))
    }

    @Test
    fun `finds what the plan holds`() {
        val hawk = amb.step("4.e@lz:lz-hawk")
        fun fact(key: String) = hawk["facts"]!!.jsonArray.map { it.jsonObject }.first { it.text("key") == key }.text("value")
        assertEquals("203°", fact("stop.landingHeading"))
        assertEquals("2,230 ft MSL", fact("stop.elevation"))
        assertEquals("right", fact("stop.goAround"))
        assertEquals("16S GD 59833 44327", hawk.item("4.e.i").text("text"))
        assertEquals("11:31", amb.step("4.b").item("4.b.i").text("text"))
        val constant = amb["constants"]!!.jsonArray.map { it.jsonObject }.first { it.text("id") == "spOutboundAirspeed" }
        assertEquals("60 KIAS", constant.text("value"))
        assertEquals("standard", constant.text("source"))
    }

    @Test
    fun `prints a blank as a write-in, in the item and in the script`() {
        val hawk = amb.step("4.e@lz:lz-hawk")
        val blank = hawk.item("4.e.iii")
        assertEquals(JsonPrimitive(true), blank["writeIn"])
        assertEquals(kotlinx.serialization.json.JsonNull, blank["text"])
        assertTrue(hawk["script"]!!.jsonArray.any { s -> s.jsonArray.any { (it.jsonObject["writeIn"] as? JsonPrimitive)?.content == "iii" } })
    }

    @Test
    fun `writes a standard procedure with the person's fields, and their own words for a custom one`() {
        val contingencies = script(amb.step("5.a"))
        listOf(
            "Base altitude 4,000 ft MSL, adjusted 500 feet",
            "Lead calls it; old lead takes trail.",
            "Rally points are ACP2 (inbound), RP5 (outbound).",
            "Bump plan: ________",
        ).forEach { assertTrue(contingencies.contains(it), it) }
    }

    @Test
    fun `keeps an edited script as the person wrote it`() {
        val step = amb.step("3")
        assertEquals(JsonPrimitive(true), step["scriptEdited"])
        assertEquals(listOf("Insert the teams, extract them, everyone home."), CompileBrief.scriptText(step))
    }

    @Test
    fun `briefs sections that share a step together, once per stop`() {
        val lz = hasty.steps().filter { it["sections"]!!.jsonArray.joinToString(",") { s -> s.jsonPrimitive.content } == "12,13" }
        assertEquals(listOf("lz:lz-bluebird"), lz.map { it.text("ref") })
        assertNotNull(hasty.steps().firstOrNull { it.text("id") == "laager@lz:lz-hawk" })
        assertNotNull(hasty.steps().firstOrNull { it.text("id") == "false@lz:lz-decoy" })
    }

    @Test
    fun `says what it could not brief`() {
        val gone = compile(cases[2])
        assertEquals(JsonObject(mapOf("ref" to JsonPrimitive("lz:gone-lz"), "gone" to JsonPrimitive(true))), gone["flow"]!!.jsonArray[1])
        assertTrue(gone["problems"]!!.jsonArray.any { it.jsonObject.text("ref") == "lz:gone-lz" })
        val short = compile(cases[4])["problems"]!!.jsonArray.joinToString(" ") { it.jsonObject.text("message") }
        assertTrue(short.contains("ONE needs at least two route points"), short)
        assertTrue(Regex("LZ LONE, so it has no arrival time|reaches LZ LONE").containsMatchIn(short), short)
    }

    @Test
    fun `takes nothing from the device's clock or zone`() {
        val empty = compile(cases[3])
        assertEquals("15 Oct 2026", empty["mission"]!!.jsonObject.text("date"))
        assertEquals(JsonPrimitive(true), empty.step("2").item("2.c")["writeIn"])
        val parts = JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("a "))), JsonObject(mapOf("writeIn" to JsonPrimitive("x")))))
        assertEquals("a ________", CompileBrief.partsText(parts))
    }
}
