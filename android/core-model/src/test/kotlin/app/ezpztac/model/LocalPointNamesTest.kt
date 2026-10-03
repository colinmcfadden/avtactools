package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Typing a name on a route point puts it on the local point it names: the web's rule, held to the cases it was generated over. */
class LocalPointNamesTest {
    private val fixture = Fixtures.load("localpoints/match.json")

    private fun pointsOf(source: JsonObject) = source.getValue("points").jsonArray.mapIndexed { i, element ->
        val p = element.jsonObject
        SetPoint(
            id = "p$i", name = p.getValue("name").jsonPrimitive.content, description = "", group = "", icon = "",
            elevationFt = p["elevationFt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull,
            lat = p.getValue("lat").jsonPrimitive.content.toDouble(), lon = p.getValue("lon").jsonPrimitive.content.toDouble(),
        )
    }

    @Test
    fun `every case the web was run over comes out the same`() {
        val names = LocalPointNames(pointsOf(fixture))
        val cases = fixture.getValue("cases").jsonArray
        assertTrue(cases.size >= 20)
        for (element in cases) {
            val case = element.jsonObject
            val typed = case.getValue("typed").jsonPrimitive.content
            val got = names.match(typed)
            assertEquals(case.getValue("name").jsonPrimitive.content, got.name, "name for '$typed'")
            val lat = case.getValue("lat").takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble()
            val lon = case.getValue("lon").takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble()
            assertEquals(lat, got.at?.lat, "lat for '$typed'")
            assertEquals(lon, got.at?.lon, "lon for '$typed'")
            val chart = case.getValue("chartElevationFt").takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble()
            assertEquals(chart, got.chartElevationFt, "elevation for '$typed'")
        }
    }

    @Test
    fun `no points, no match, and the name is still put in capitals`() {
        val got = LocalPointNames(emptyList()).match("blue 1")
        assertEquals("BLUE 1", got.name)
        assertNull(got.at)
        assertNull(got.chartElevationFt)
    }

    @Test
    fun `only one leading dot of what is typed is ignored`() {
        val names = LocalPointNames(listOf(SetPoint("a", "ALPHA", "", "", "", 5.0, 1.0, 2.0), SetPoint("b", ".BRAVO", "", "", "", null, 3.0, 4.0)))
        assertEquals(LatLon(1.0, 2.0), names.match(".alpha").at)
        assertNull(names.match("..alpha").at)
        assertNull(names.match(".bravo").at)                    // the dot is stripped from what is typed, never from a point's own name
        assertEquals(LatLon(3.0, 4.0), names.match("..bravo").at)
    }

    @Test
    fun `an elevation of zero is an elevation`() {
        val names = LocalPointNames(listOf(SetPoint("a", "SEA", "", "", "", 0.0, 1.0, 2.0)))
        assertEquals(0.0, names.match("sea").chartElevationFt)
    }
}
