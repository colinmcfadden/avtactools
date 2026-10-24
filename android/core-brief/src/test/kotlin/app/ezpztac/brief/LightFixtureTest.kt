package app.ezpztac.brief

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * contracts/fixtures/brief/light.json is Skyfield over JPL DE421 (contracts/scripts/light_fixtures.py): an independent reference for the
 * brief's light data, which the web's light.js is held to too (briefLightFixtures.test.js, whose checks these are).
 */
class LightFixtureTest {
    private val fixture = Fixtures.load("brief/light.json")
    private val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }
    private val tolerance = fixture["tolerance"]!!.jsonObject
    private val sun = listOf("bmnt", "sunrise", "sunset", "eent")
    private val moon = listOf("moonrise", "moonset")

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    private fun of(c: JsonObject, at: Double? = Instant.parse(c.text("at")).toEpochMilli().toDouble()): Light.Data =
        Light.of(c.number("lat"), c.number("lon"), c.text("date"), c.number("utcOffsetMinutes"), at)!!

    private fun eventOf(data: Light.Data, key: String): Long? = when (key) {
        "bmnt" -> data.bmnt
        "sunrise" -> data.sunrise
        "sunset" -> data.sunset
        "eent" -> data.eent
        "moonrise" -> data.moonrise
        else -> data.moonset
    }

    @Test
    fun `has the cases, with a missing event among them`() {
        assertTrue(cases.size >= 20)
        assertTrue(cases.sumOf { c -> c["expected"]!!.jsonObject.values.count { it is JsonNull } } > 5)
    }

    @Test
    fun `agrees with Skyfield at every case within the fixture's tolerance`() {
        val misses = cases.flatMap { c ->
            val actual = of(c)
            val expected = c["expected"]!!.jsonObject
            val out = ArrayList<String>()
            (sun + moon).forEach { key ->
                val want = (expected[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val got = eventOf(actual, key)
                val seconds = tolerance[if (key in sun) "sunSeconds" else "moonSeconds"]!!.jsonPrimitive.int
                if (want == null || got == null) {
                    if (want != null || got != null) out += "${c.text("name")}: $key ${got?.let(Instant::ofEpochMilli) ?: "none"}, Skyfield ${want ?: "none"}"
                } else if (kotlin.math.abs(got - Instant.parse(want).toEpochMilli()) > seconds * 1000L) {
                    out += "${c.text("name")}: $key ${Instant.ofEpochMilli(got)}, Skyfield $want"
                }
            }
            val illumination = expected.number("illumination")
            if (kotlin.math.abs(actual.illumination - illumination) > tolerance.number("illumination")) {
                out += "${c.text("name")}: illumination ${actual.illumination}, Skyfield $illumination"
            }
            out
        }
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun `puts every event inside the local day it was asked for`() {
        val outside = cases.flatMap { c ->
            val start = Light.localMidnight(c.text("date"), c.number("utcOffsetMinutes"))!!
            val actual = of(c, at = null)
            (sun + moon).mapNotNull { key ->
                val t = eventOf(actual, key) ?: return@mapNotNull null
                if (t < start || t >= start + 86_400_000L) "${c.text("name")}: $key" else null
            }
        }
        assertEquals(emptyList<String>(), outside)
    }

    @Test
    fun `answers nothing for a date or place it cannot read, rather than guess`() {
        assertNull(Light.of(34.0, -84.0, "16 Oct 2026", -240.0))
        assertNull(Light.of(95.0, -84.0, "2026-10-16", -240.0))
        assertNull(Light.of(Double.NaN, -84.0, "2026-10-16", -240.0))
        assertNull(Light.of(34.0, -84.0, "2026-10-16", Double.NaN))
        assertNull(Light.of(34.0, -84.0, null, -240.0))
    }

    @Test
    fun `takes illumination at local noon when no instant is given`() {
        val c = cases[0]
        val noon = Light.localMidnight(c.text("date"), c.number("utcOffsetMinutes"))!! + 43_200_000L
        assertEquals(Light.moonIllumination(noon.toDouble()), of(c, at = null).illumination)
    }

    @Test
    fun `reads a date's parts as JavaScript's Date-UTC does`() {
        // The month and day roll over, and a two-digit year is in the 1900s.
        assertEquals(Instant.parse("2026-03-02T00:00:00Z").toEpochMilli(), Light.localMidnight("2026-02-30", 0.0))
        assertEquals(Instant.parse("1926-01-01T04:00:00Z").toEpochMilli(), Light.localMidnight("0026-01-01", -240.0))
        assertNull(Light.localMidnight("2026-1-01", 0.0))
    }
}
