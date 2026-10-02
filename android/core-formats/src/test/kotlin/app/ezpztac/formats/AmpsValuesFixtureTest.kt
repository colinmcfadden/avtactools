package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Held to contracts/fixtures/msnx/amps_values.json: what the web's AMPS value readers answer,
 * halves, the 12 o'clock boundaries and malformed text included.
 */
class AmpsValuesFixtureTest {
    private val fixture = Fixtures.load("msnx/amps_values.json")
    private val tolerance = fixture["tolerance"]!!.jsonPrimitive.double

    private fun cases(key: String): List<JsonObject> = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.input(): String? = (this["input"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double
    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected))

    @Test
    fun `airspeed matches the web, halves rounding up`() {
        cases("airspeed").forEach { c ->
            val actual = AmpsParse.airspeed(c.input())
            val expected = c["expected"]!!
            if (expected is JsonNull) assertNull(actual, "airspeed(${c.input()})")
            else {
                val e = expected.jsonObject
                requireNotNull(actual) { "airspeed(${c.input()})" }
                assertEquals(num(e["value"]), actual.value, "airspeed(${c.input()})")
                assertEquals(e["type"]!!.jsonPrimitive.content, actual.type, "airspeed(${c.input()})")
            }
        }
    }

    @Test
    fun `wind matches the web`() {
        cases("wind").forEach { c ->
            val actual = AmpsParse.wind(c.input())
            val expected = c["expected"]!!
            if (expected is JsonNull) assertNull(actual, "wind(${c.input()})")
            else {
                val e = expected.jsonObject
                requireNotNull(actual) { "wind(${c.input()})" }
                assertEquals(num(e["dirTrue"]), actual.dirTrue, "wind(${c.input()}).dir")
                assertEquals(num(e["speedKts"]), actual.speedKts, "wind(${c.input()}).speed")
            }
        }
    }

    @Test
    fun `metres take the leading number, wherever it is`() {
        cases("meters").forEach { c ->
            val actual = AmpsParse.meters(c.input())
            val expected = num(c["expected"])
            if (expected == null) assertNull(actual, "meters(${c.input()})")
            else assertTrue(actual != null && close(expected, actual), "meters(${c.input()}): $actual vs $expected")
        }
    }

    @Test
    fun `clocks convert twelve hour time without validating it`() {
        cases("clock").forEach { c ->
            val actual = AmpsParse.clock(c.input())
            val expected = c["expected"]!!
            if (expected is JsonNull) assertNull(actual, "clock(${c.input()})")
            else {
                requireNotNull(actual) { "clock(${c.input()})" }
                assertEquals(expected.jsonObject["date"]!!.jsonPrimitive.content, actual.date, "clock(${c.input()}).date")
                assertEquals(expected.jsonObject["time"]!!.jsonPrimitive.content, actual.time, "clock(${c.input()}).time")
            }
        }
    }

    @Test
    fun `parseFloat is emulated, prefix and all`() {
        cases("parseFloat").forEach { c ->
            val actual = AmpsParse.jsParseFloat(c.input())
            val expected = num(c["expected"])
            // The fixture writes NaN and Infinity as null.
            if (expected == null) assertTrue(!actual.isFinite(), "parseFloat(${c.input()}) = $actual")
            else assertTrue(close(expected, actual), "parseFloat(${c.input()}) = $actual, expected $expected")
        }
    }

    @Test
    fun `the airframe is read from the vehicles text`() {
        cases("aircraft").forEach { c ->
            val actual = MsnxReader.readAircraft(c.input())
            val expected = c["expected"]!!
            if (expected is JsonNull) assertNull(actual, "aircraft(${c.input()})")
            else {
                requireNotNull(actual) { "aircraft(${c.input()})" }
                assertEquals(expected.jsonObject["description"]!!.jsonPrimitive.content, actual.description)
                assertEquals(expected.jsonObject["designation"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, actual.designation)
            }
        }
    }

    @Test
    fun `leg plan values come from the legs text, first key winning`() {
        cases("legPlan").forEach { c ->
            val actual = MsnxReader.legPlanData(c.input() ?: "")
            val expected = c["expected"]!!.jsonObject
            assertEquals(expected.keys, actual.keys, "legs in ${c.input()}")
            expected.forEach { (id, e) ->
                val leg = actual.getValue(id)
                val o = e.jsonObject
                assertEquals(o["endpt"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, leg.endpt, "$id.endpt")
                assertEquals(o["airspeed"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, leg.airspeed, "$id.airspeed")
                assertEquals(o["wind"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, leg.wind, "$id.wind")
            }
        }
    }

    @Test
    fun `the feet factor sits where a half foot decides the answer`() {
        // The plan stores whole feet, and the fixtures' values never land near a half, so they cannot tell
        // the web's 3.28084 from the 3.280839895 aircraft geometry uses. This value can: just under
        // 1000.5 ft by one factor and just over by the other.
        val meters = 1000.5 / 3.28084 + 1e-9
        assertEquals(1001.0, AmpsParse.feetFromMeters(meters))
        assertEquals(1000.0, Math.floor(meters * 3.280839895 + 0.5))     // what the wrong factor would have said
    }

    @Test
    fun `a fixture that shrank is caught`() {
        listOf("airspeed", "wind", "meters", "clock", "parseFloat", "aircraft", "legPlan").forEach {
            assertTrue(cases(it).size >= 6, "$it has ${cases(it).size} cases")
        }
        assertTrue(fixture["airspeed"] is JsonArray)
    }
}
