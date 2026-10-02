package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Held to contracts/fixtures/planning/summary.json: what the web's mission summary says of a polygon and of a slope. */
class LzSummaryFixtureTest {
    private val fixture = Fixtures.load("planning/summary.json")
    private val profiles: Map<String, AircraftProfile> =
        Fixtures.load("planning/aircraft.json").getValue("profiles").jsonArray.associate {
            it.jsonObject.getValue("key").jsonPrimitive.content to AircraftProfile.normalize(it.jsonObject.getValue("profile").jsonObject)
        }

    private fun polygon(element: JsonElement): List<LatLon>? =
        (element as? JsonArray)?.map { p -> LatLon(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) }

    @Test
    fun `area and capacity are what the web computes`() {
        val cases = fixture.getValue("area").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 40)
        for (c in cases) {
            val key = c.getValue("profile").takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val profile = if (key == null) AircraftProfile.FALLBACK else profiles.getValue(key)
            val expected = c.getValue("expected").jsonObject
            val actual = LzSummary.areaAndCapacity(polygon(c.getValue("polygon")), profile)
            val label = "${c.getValue("name").jsonPrimitive.content} / ${key ?: "default"}"
            // The area's last digit can differ where the platform's sine differs in its last bit: a square foot in a million is not a disagreement.
            val expectedArea = expected.getValue("area").jsonPrimitive.double
            assertEquals(expectedArea, actual.areaSqFt.toDouble(), maxOf(1.0, expectedArea * 1e-9), label)
            assertEquals(expected.getValue("heloCount").jsonPrimitive.int, actual.capacity, label)
        }
    }

    @Test
    fun `the slope tile makes the same call as the web`() {
        val cases = fixture.getValue("slope").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val data = c.getValue("terrainData") as? JsonObject
            val stats = data?.get("stats") as? JsonObject
            val directional = (data?.get("directional") as? JsonObject)?.let {
                LzSummary.Directional(
                    it.getValue("noseHighMaxDeg").jsonPrimitive.double, it.getValue("noseLowMaxDeg").jsonPrimitive.double,
                    it.getValue("crossSlopeMaxDeg").jsonPrimitive.double,
                )
            }
            val call = LzSummary.slopeCall(stats?.get("maxDeg")?.jsonPrimitive?.doubleOrNull, directional)
            val expected = c.getValue("expected").jsonObject
            val label = c.getValue("name").jsonPrimitive.content
            assertEquals(expected.getValue("label").jsonPrimitive.content, call.label, label)
            assertEquals(expected.getValue("className").jsonPrimitive.content, "status-" + call.level.name.lowercase(), label)
            assertEquals(expected.getValue("max").jsonPrimitive.double, call.maxDeg, 0.0, label)
        }
    }

    @Test
    fun `capacity is counted from the area as it is, not as it is shown`() {
        // The tile shows the area rounded to a square foot; the web counts aircraft from the unrounded one. Find an aircraft whose spot size
        // puts the unrounded area just under a whole number of spots and the rounded one over it (or the reverse), so the two readings differ.
        val polygon = listOf(LatLon(34.7800, -84.0900), LatLon(34.7800, -84.0866), LatLon(34.7777, -84.0866), LatLon(34.7777, -84.0900))
        val exact = LzSummary.polygonAreaSqFt(polygon)
        val shown = Math.floor(exact + 0.5)
        assertTrue(shown != exact, "this polygon's area is not already a whole number")
        val k = 3
        val spot = (exact + (shown - exact) / 2) / k
        val diameter = Math.sqrt(spot) / app.ezpztac.model.Units.METERS_TO_FEET
        val aircraft = AircraftProfile.FALLBACK.copy(rotorDiameterM = diameter, rotorTipClearanceM = 0.0)
        // Rounded up, the shown area reaches k spots but the real one falls just short of them; rounded down, the real one holds k and the shown one does not.
        val expected = if (shown > exact) k - 1 else k
        assertEquals(expected, LzSummary.areaAndCapacity(polygon, aircraft).capacity, "from the unrounded area, ${if (shown > exact) "just short of" else "just over"} $k spots")
    }
}
