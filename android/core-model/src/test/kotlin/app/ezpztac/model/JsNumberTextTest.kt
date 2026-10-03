package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/formats/number_text.json: what JavaScript's `String(n)` writes for each number. */
class JsNumberTextTest {
    private val fixture = Fixtures.load("formats/number_text.json")

    @Test
    fun `every number is written as JavaScript writes it`() {
        val cases = fixture.getValue("numbers").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 60)
        for (c in cases) {
            val value = c.getValue("value").jsonPrimitive.double
            assertEquals(c.getValue("text").jsonPrimitive.content, JsNumber.toText(value), "the number ${c.getValue("value")}")
        }
    }

    @Test
    fun `the words`() {
        for (c in fixture.getValue("words").jsonArray.map { it.jsonObject }) {
            val value = when (c.getValue("value").jsonPrimitive.content) {
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                else -> Double.NEGATIVE_INFINITY
            }
            assertEquals(c.getValue("text").jsonPrimitive.content, JsNumber.toText(value))
        }
    }

    @Test
    fun `negative zero is written as zero`() {
        assertEquals(fixture.getValue("negativeZero").jsonPrimitive.content, JsNumber.toText(-0.0))
        assertEquals("0", JsNumber.toText(0.0))
    }

    @Test
    fun `a whole number has no point, and Java's own text is not used`() {
        assertEquals("100", JsNumber.toText(100.0))
        assertEquals("1e+21", JsNumber.toText(1e21))
        assertEquals("1e-7", JsNumber.toText(1e-7))
        assertEquals("0.000001", JsNumber.toText(1e-6))
    }
}
