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

/** `toFixed`, which the hand-off files write every coordinate with. The values are what JavaScript answers. */
class JsNumberToFixedTest {
    @org.junit.jupiter.api.Test
    fun `it rounds the exact binary value, not the shortest decimal`() {
        org.junit.jupiter.api.Assertions.assertEquals("1.00", JsNumber.toFixed(1.005, 2))
        org.junit.jupiter.api.Assertions.assertEquals("2.67", JsNumber.toFixed(2.675, 2))
        org.junit.jupiter.api.Assertions.assertEquals("34.123457", JsNumber.toFixed(34.1234565, 6))
        org.junit.jupiter.api.Assertions.assertEquals("1.000001", JsNumber.toFixed(1.0000005, 6))
    }

    @org.junit.jupiter.api.Test
    fun `a negative that rounds to zero keeps its sign, and negative zero has none`() {
        org.junit.jupiter.api.Assertions.assertEquals("-0.000000", JsNumber.toFixed(-1e-7, 6))
        org.junit.jupiter.api.Assertions.assertEquals("0.000000", JsNumber.toFixed(-0.0, 6))
        org.junit.jupiter.api.Assertions.assertEquals("0.000000", JsNumber.toFixed(0.0, 6))
    }

    @org.junit.jupiter.api.Test
    fun `whole numbers are padded, and a place count of zero has no point`() {
        org.junit.jupiter.api.Assertions.assertEquals("34.000000", JsNumber.toFixed(34.0, 6))
        org.junit.jupiter.api.Assertions.assertEquals("35", JsNumber.toFixed(34.5, 0))
        org.junit.jupiter.api.Assertions.assertEquals("-1", JsNumber.toFixed(-0.5, 0))        // halves go away from zero: n is the larger of the two nearest, applied to the magnitude
    }

    @org.junit.jupiter.api.Test
    fun `NaN, the infinities, and numbers past 1e21 are as JavaScript writes them`() {
        org.junit.jupiter.api.Assertions.assertEquals("NaN", JsNumber.toFixed(Double.NaN, 6))
        org.junit.jupiter.api.Assertions.assertEquals("Infinity", JsNumber.toFixed(Double.POSITIVE_INFINITY, 6))
        org.junit.jupiter.api.Assertions.assertEquals("-Infinity", JsNumber.toFixed(Double.NEGATIVE_INFINITY, 6))
        org.junit.jupiter.api.Assertions.assertEquals("1e+21", JsNumber.toFixed(1e21, 6))
        org.junit.jupiter.api.Assertions.assertEquals("-1.5e+21", JsNumber.toFixed(-1.5e21, 2))
    }
}
