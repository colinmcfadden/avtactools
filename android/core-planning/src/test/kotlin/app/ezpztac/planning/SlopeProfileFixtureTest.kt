package app.ezpztac.planning

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/planning/profile.json: how the web reads the server's lines through an LZ as a slope profile. */
class SlopeProfileFixtureTest {
    private val fixture = Fixtures.load("planning/profile.json")

    private fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun ground(o: JsonObject) = SlopeProfile.Ground(
        stepM = number(o["stepM"]) ?: Double.NaN,
        halfLengthM = number(o["halfLengthM"]) ?: Double.NaN,
        headingStepDeg = o.getValue("headingStepDeg").jsonPrimitive.int,
        lines = o.getValue("lines").jsonArray.map { l ->
            val line = l.jsonObject
            SlopeProfile.GroundLine(
                heading = line.getValue("heading").jsonPrimitive.int,
                elevationsFt = line.getValue("elevationsFt").jsonArray.map(::number),
                lz = (line["lz"] as? JsonArray)?.map { number(it) ?: Double.NaN },
            )
        },
    )

    private val grounds: Map<String, SlopeProfile.Ground> = fixture.getValue("ground").jsonObject.mapValues { (_, v) -> ground(v.jsonObject) }

    private fun <T> present(value: T?, label: String): T {
        assertNotNull(value, label)
        return value!!
    }

    private fun numbers(e: JsonElement): List<Double?> = e.jsonArray.map(::number)

    private fun assertClose(expected: List<Double?>, actual: List<Double?>, label: String) {
        assertEquals(expected.size, actual.size, label)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            if (e == null) assertNull(a, "$label [$i]") else assertEquals(e, present(a, "$label [$i]"), 1e-6, "$label [$i]")
        }
    }

    private fun assertLine(expected: JsonObject, actual: SlopeProfile.Line, label: String) {
        assertEquals(expected.getValue("heading").jsonPrimitive.int, actual.heading, label)
        assertClose(numbers(expected.getValue("distancesFt")), actual.distancesFt, "$label distances")
        assertClose(numbers(expected.getValue("elevationsFt")), actual.elevationsFt, "$label heights")
        val lz = expected["lz"]
        if (lz == null || lz is JsonNull) assertNull(actual.lz, label) else assertClose(numbers(lz), present(actual.lz, label), "$label lz")
        assertClose(listOf(number(expected["changeFt"]), number(expected["averageDeg"])), listOf(actual.changeFt, actual.averageDeg), "$label change")
        expected["words"]?.let { assertEquals(it.jsonPrimitive.content, actual.words, label) }
    }

    @Test
    fun `the slope profile is the web's for every heading and every kind of ground`() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val label = c.getValue("name").jsonPrimitive.content
            val made = SlopeProfile.slopeProfileFor(grounds.getValue(c.getValue("ground").jsonPrimitive.content), c["heading"]?.jsonPrimitive?.intOrNull)
            val expected = c["expected"]
            if (expected == null || expected is JsonNull) {
                assertNull(made, label)
                continue
            }
            val profile = present(made, label)
            assertLine(expected.jsonObject.getValue("along").jsonObject, profile.along, "$label along")
            assertLine(expected.jsonObject.getValue("across").jsonObject, profile.across, "$label across")
        }
    }

    @Test
    fun `a heading past 359 is brought round, as the web brings it`() {
        for (c in fixture.getValue("line").jsonArray.map { it.jsonObject }) {
            val heading = c.getValue("heading").jsonPrimitive.int
            val made = SlopeProfile.profileLine(grounds.getValue(c.getValue("ground").jsonPrimitive.content), heading)
            assertLine(c.getValue("expected").jsonObject, present(made, "heading $heading"), "heading $heading")
        }
    }

    @Test
    fun `a line whose crossing is not two numbers misses the lz`() {
        val east = grounds.getValue("a plane of 5° rising east")
        val odd = east.copy(lines = east.lines.map { if (it.heading == 90) it.copy(lz = listOf(-100.0, Double.NaN)) else it })
        assertEquals("Along 090°: the line misses the LZ", SlopeProfile.slopeProfileFor(odd, 90)!!.along.words)
        assertTrue(fixture.getValue("cases").jsonArray.any { it.jsonObject["expected"].let { e -> e is JsonObject && e["along"]!!.jsonObject.getValue("lz") !is JsonNull } })
    }
}
