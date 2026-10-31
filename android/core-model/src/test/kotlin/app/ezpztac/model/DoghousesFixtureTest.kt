package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Held to contracts/fixtures/workspace/doghouses.json: what a doghouse shows, how a typed value is written into it, and how the standard
 * doghouses give the flight data its headings, as the web does them. Beside those, JavaScript's own `parseInt` and `parseFloat` over the
 * strings they are applied to, since the Kotlin versions are a small parser of their own.
 */
class DoghousesFixtureTest {
    private val fixture = Fixtures.load("workspace/doghouses.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun name(c: JsonObject) = c.getValue("name").jsonPrimitive.content

    /** A number as the fixture writes it: a number, or one of the words for what JSON cannot hold. */
    private fun number(element: JsonElement): Double {
        val p = element.jsonPrimitive
        return if (p.isString) when (p.content) {
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> error("unexpected number word ${p.content}")
        } else p.double
    }

    private fun assertSameNumber(expected: Double, actual: Double, label: String) {
        if (expected.isNaN()) assertEquals(true, actual.isNaN(), "$label: expected NaN but was $actual")
        else assertEquals(expected, actual, 0.0, label)                                            // -0.0 and 0.0 are equal here, as in the JSON
    }

    @Test
    fun `parseInt and parseFloat read what JavaScript reads`() {
        val all = cases("jsParse")
        assertEquals(true, all.size > 40)
        for (c in all) {
            val input = c.getValue("input").jsonPrimitive.content
            assertSameNumber(number(c.getValue("parseInt")), JsValue.parseInt(input), "parseInt of ${JsonPrimitive(input)}")
            assertSameNumber(number(c.getValue("parseFloat")), JsValue.parseFloat(input), "parseFloat of ${JsonPrimitive(input)}")
        }
    }

    @Test
    fun `the default doghouses are the ones the web makes`() {
        for (c in cases("defaults")) {
            val target = c.getValue("target")
            val made = DiagramOps.defaultDoghouses(target, c.getValue("namespace"))
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("expected"), JsonArray(made)).take(5))
        }
    }

    @Test
    fun `a stored heading turns the box as the web turns it`() {
        for (c in cases("rotation")) {
            val doghouse = JsonObject(mapOf("heading" to c.getValue("heading")))
            assertSameNumber(c.getValue("expected").jsonPrimitive.double, Doghouses.rotation(doghouse), "heading ${c.getValue("heading")}")
        }
    }

    @Test
    fun `the label and four rows show what the web shows`() {
        for (c in cases("display")) {
            val dh = c.getValue("dh").jsonObject
            val rotation = c.getValue("rotation").jsonPrimitive.double
            assertEquals(rotation, Doghouses.rotation(dh), name(c))
            val shown = Doghouses.display(dh, rotation)
            val expected = c.getValue("expected").jsonObject
            val label = name(c)
            assertEquals(expected["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, shown.id, "$label: id")
            assertEquals(expected.getValue("heading").jsonPrimitive.content, shown.heading, "$label: heading")
            assertEquals(expected.getValue("minutes").jsonPrimitive.content, shown.minutes, "$label: minutes")
            assertEquals(expected.getValue("seconds").jsonPrimitive.content, shown.seconds, "$label: seconds")
            assertSameNumber(expected.getValue("distance").jsonPrimitive.double, shown.distance, "$label: distance")
            assertSameNumber(expected.getValue("airspeed").jsonPrimitive.double, shown.airspeed, "$label: airspeed")
        }
    }

    @Test
    fun `the heading a box is turned to is as many degrees as the web rounds to`() {
        for (c in cases("displayRotations")) {
            val shown = Doghouses.display(JsonObject(mapOf("id_val" to JsonPrimitive("[X]"))), c.getValue("rotation").jsonPrimitive.double)
            assertEquals(c.getValue("expected").jsonPrimitive.content, shown.heading, "rotation ${c.getValue("rotation")}")
        }
    }

    @Test
    fun `a typed heading is wrapped once and stored with three digits and a degree sign`() {
        val all = cases("headings")
        assertEquals(true, all.size > 15)
        for (c in all) {
            val input = c.getValue("input").jsonPrimitive.content
            val degrees = Doghouses.headingDegrees(input)
            assertSameNumber(number(c.getValue("degrees")), degrees, "degrees of ${JsonPrimitive(input)}")
            assertEquals(c.getValue("stored").jsonPrimitive.content, Doghouses.headingText(degrees), "stored for ${JsonPrimitive(input)}")
        }
    }

    @Test
    fun `degrees are written with three digits`() {
        for (c in cases("headingText")) {
            assertEquals(c.getValue("expected").jsonPrimitive.content, Doghouses.headingText(c.getValue("degrees").jsonPrimitive.double), "degrees ${c.getValue("degrees")}")
        }
    }

    @Test
    fun `a typed value is written into the field as the web writes it`() {
        for (c in cases("fields")) {
            val time = c["time"]?.takeIf { it !is JsonNull }?.jsonObject
            val written = Doghouses.fieldUpdates(
                c.getValue("type").jsonPrimitive.content, c.getValue("value").jsonPrimitive.content,
                time?.get("minutes")?.jsonPrimitive?.content ?: "", time?.get("seconds")?.jsonPrimitive?.content ?: "",
            )
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("expected"), written).take(5), "${c.getValue("type")} ${c.getValue("value")}")
        }
    }

    @Test
    fun `the flight data takes its headings from the doghouses as the web's effect gives them`() {
        for (c in cases("flightHeadings")) {
            val doghouses = c.getValue("doghouses")
            val list = (doghouses as? JsonArray)?.toList() ?: emptyList()                           // what is not a list changes nothing
            val flightData = c.getValue("flightData").jsonObject
            val result = Doghouses.flightHeadings(list, flightData)
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("expected"), result).take(5), name(c))
        }
    }

    @Test
    fun `settling a diagram brings its flight data in line only when its doghouses changed`() {
        val withDoghouses = DiagramOps.defaultDoghouses(34.5, -84.1, "d")
        val base = Diagram(id = "d", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z", target = DiagramTarget(34.5, -84.1, "16S"), status = DiagramStatus.ANALYZED)
        val after = base.copy(graphics = base.graphics.copy(doghouses = withDoghouses))

        val settled = Doghouses.settle(base, after)
        assertEquals("000°", settled.flightData["landing_hdg"]?.jsonPrimitive?.content)
        assertEquals("000°", settled.flightData["takeoff_hdg"]?.jsonPrimitive?.content)

        // Doghouses that did not change leave a flight data the person set by hand alone.
        val edited = settled.copy(flightData = JsonObject(settled.flightData + ("landing_hdg" to JsonPrimitive("123°"))))
        assertEquals(edited, Doghouses.settle(settled, edited))
        assertNull(Doghouses.settle(base, base).flightData["landing_hdg"])
    }
}
