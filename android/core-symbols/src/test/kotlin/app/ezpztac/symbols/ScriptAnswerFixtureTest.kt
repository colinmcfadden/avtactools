package app.ezpztac.symbols

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Held to contracts/fixtures/symbols/script.json: the strings the sandbox script really gives back (run under node against milsymbol), and what is evaluated to get them. */
class ScriptAnswerFixtureTest {
    private val fixture = Fixtures.load("symbols/script.json")

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }

    private fun spec(c: JsonObject): SymbolSpec {
        val options = c.getValue("options").jsonObject
        return SymbolSpec(
            sidc = c.getValue("sidc").jsonPrimitive.content,
            uniqueDesignation = options["uniqueDesignation"]?.jsonPrimitive?.content.orEmpty(),
            higherFormation = options["higherFormation"]?.jsonPrimitive?.content.orEmpty(),
            size = options.getValue("size").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `every answer the script gives is read, a symbol as its SVG with its size and anchor, and a refusal as invalid`() {
        val all = cases("answers")
        assertTrue(all.size >= 15)
        var drawn = 0
        for (c in all) {
            val answer = c.getValue("answer").jsonPrimitive.content
            val parsed = ScriptAnswer.parse(answer)
            val raw = kotlinx.serialization.json.Json.parseToJsonElement(answer).jsonObject
            if (raw.getValue("valid").jsonPrimitive.content == "true") {
                drawn++
                val found = (parsed as? SvgResult.Found)?.svg ?: error("not read: ${c.getValue("sidc")}")
                assertEquals(raw.getValue("svg").jsonPrimitive.content, found.svg)
                assertEquals(raw.getValue("width").jsonPrimitive.double, found.width, 0.0)
                assertEquals(raw.getValue("height").jsonPrimitive.double, found.height, 0.0)
                assertEquals(raw.getValue("anchorX").jsonPrimitive.double, found.anchorX, 0.0)
                assertEquals(raw.getValue("anchorY").jsonPrimitive.double, found.anchorY, 0.0)
            } else {
                assertEquals(SvgResult.Invalid, parsed)
            }
        }
        assertTrue("some cases draw and some do not", drawn in 10 until all.size)
    }

    @Test
    fun `what is not an answer the script gives is no answer at all`() {
        for (text in fixture.getValue("malformed").jsonArray.map { it.jsonPrimitive.content }) {
            assertNull("\"$text\"", ScriptAnswer.parse(text))
        }
    }

    @Test
    fun `a symbol with labels draws a picture larger than its frame, and says where the frame is`() {
        val labelled = cases("answers").first { it.getValue("options").jsonObject.containsKey("uniqueDesignation") && it.getValue("sidc").jsonPrimitive.content == "SFGPUCI--------" }
        val found = (ScriptAnswer.parse(labelled.getValue("answer").jsonPrimitive.content) as SvgResult.Found).svg
        assertNotNull(found)
        assertTrue(found.anchorX > 0 && found.anchorY > 0)
        assertTrue("the anchor is inside the picture", found.anchorX < found.width && found.anchorY < found.height)
    }

    @Test
    fun `the expression evaluated in the sandbox is the one the web's test evaluates`() {
        val all = cases("calls")
        assertTrue(all.size >= 15)
        for (c in all) {
            assertEquals("${c.getValue("sidc")} ${c.getValue("options")}", c.getValue("expression").jsonPrimitive.content, JavaScriptSymbolSource.expression(spec(c)))
        }
    }
}
