package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Doghouses
import app.ezpztac.model.LatLon
import app.ezpztac.model.WeatherObservation
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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

    private fun wind(element: JsonElement?): LzSummary.SlopeWind? = (element as? JsonObject)?.let {
        LzSummary.SlopeWind(
            it.getValue("speedKts").jsonPrimitive.double,
            it["fromDeg"]?.jsonPrimitive?.intOrNull,
            it.getValue("variable").jsonPrimitive.boolean,
        )
    }

    private fun limits(expected: JsonObject) = LzSummary.SlopeLimits(
        expected.getValue("noseUp").jsonPrimitive.int, expected.getValue("noseDown").jsonPrimitive.int, expected.getValue("cross").jsonPrimitive.int,
        expected.getValue("reductionDeg").jsonPrimitive.int, expected.getValue("noseDownProhibited").jsonPrimitive.boolean,
    )

    // What the server sent as a number, or NaN: a slope sent as text is not one (the web's typeof check).
    private fun number(element: JsonElement?): Double = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: Double.NaN

    // A heading that is not a whole number is no heading (-1, which the table refuses as the web's Number.isInteger does).
    private fun headingOf(element: JsonElement?): Int =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it == Math.floor(it) && it in -1e9..1e9 }?.toInt() ?: -1

    /** A `ground` table: each entry `[heading, noseHighMaxDeg, noseLowMaxDeg, crossSlopeMaxDeg]`. */
    private val grounds: Map<String, Pair<Double, List<LzSummary.HeadingSlope>>> = fixture.getValue("ground").jsonArray.associate { g ->
        val o = g.jsonObject
        o.getValue("name").jsonPrimitive.content to (
            o.getValue("maxDeg").jsonPrimitive.double to o.getValue("byHeading").jsonArray.map { e ->
                val a = e.jsonArray
                LzSummary.HeadingSlope(headingOf(a[0]), number(a[1]), number(a[2]), number(a[3]))
            }
        )
    }

    @Test
    fun `the limits for a wind are the web's`() {
        val cases = fixture.getValue("windLimits").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 15)
        for (c in cases) assertEquals(limits(c.getValue("expected").jsonObject), LzSummary.limitsFor(wind(c["wind"])), c.toString())
    }

    @Test
    fun `the landing heading is read as the web reads it`() {
        val cases = fixture.getValue("landingHeading").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (c in cases) assertEquals(c.getValue("expected").jsonPrimitive.intOrNull, Doghouses.landingHeadingOf(c["landingHdg"]), c.toString())
    }

    @Test
    fun `the slope tile makes the same call as the web`() {
        val cases = fixture.getValue("slope").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 40)
        for (c in cases) {
            val label = c.getValue("name").jsonPrimitive.content
            val (maxDeg, byHeading) = c["ground"]?.let { grounds.getValue(it.jsonPrimitive.content) } ?: run {
                val data = c["terrainData"] as? JsonObject
                val stats = data?.get("stats") as? JsonObject
                val table = (data?.get("byHeading") as? JsonArray)?.map { e ->
                    val o = e.jsonObject
                    LzSummary.HeadingSlope(headingOf(o["heading"]), number(o["noseHighMaxDeg"]), number(o["noseLowMaxDeg"]), number(o["crossSlopeMaxDeg"]))
                }
                stats?.get("maxDeg")?.jsonPrimitive?.doubleOrNull to table
            }
            val call = LzSummary.slopeCall(maxDeg, byHeading, Doghouses.landingHeadingOf(c["landingHdg"]), wind(c["wind"]))
            val expected = c.getValue("expected").jsonObject
            assertEquals(expected.getValue("label").jsonPrimitive.content, call.label, label)
            assertEquals(expected.getValue("className").jsonPrimitive.content, "status-" + call.level.name.lowercase(), label)
            assertEquals(expected.getValue("max").jsonPrimitive.double, call.maxDeg, 0.0, label)
            assertEquals(expected.getValue("heading").jsonPrimitive.intOrNull, call.heading, label)
            assertEquals(limits(expected.getValue("limits").jsonObject), call.limits, label)
            assertEquals(expected.getValue("lines").jsonArray.map { it.jsonPrimitive.content }, call.lines, label)
            val r = expected["recommendation"] as? JsonObject
            if (r == null) {
                assertEquals(null, call.recommendation, label)
                continue
            }
            val made = call.recommendation ?: error("$label: no recommendation")
            assertEquals(r.getValue("heading").jsonPrimitive.int, made.heading, label)
            assertEquals(r.getValue("within").jsonPrimitive.boolean, made.within, label)
            if (made.within) {
                assertEquals(r.getValue("reason").jsonPrimitive.content, made.reason?.name?.lowercase(), label)
                assertEquals(r.getValue("offDeg").jsonPrimitive.intOrNull, made.offDeg, label)
            } else {
                assertEquals(r.getValue("limit").jsonPrimitive.content, made.limit?.let { WEB_LIMIT.getValue(it) }, label)
                assertEquals(r.getValue("overDeg").jsonPrimitive.double, (made.overTenths ?: 0) / 10.0, 1e-9, label)
            }
        }
    }

    @Test
    fun `a wind is taken from the weather report the tiles show`() {
        fun report(speed: Double?, from: Int?, variable: Boolean = false) = WeatherObservation(
            stationId = "KRYY", stationName = "Cartersville", distanceMiles = 4.0, windFromDegrees = from, windVariable = variable, windSpeedKt = speed,
            windGustKt = 25.0, tempC = null, dewpointC = null, altimeterInHg = null, visibility = null, flightCategory = null, rawReport = null,
        )
        assertEquals(null, LzSummary.slopeWindOf(null))
        assertEquals(null, LzSummary.slopeWindOf(report(null, 270)), "no speed is no report")
        assertEquals(null, LzSummary.slopeWindOf(report(Double.NaN, 270)))
        assertEquals(null, LzSummary.slopeWindOf(report(-1.0, 270)))
        assertEquals(LzSummary.SlopeWind(12.0, 270, false), LzSummary.slopeWindOf(report(12.0, 270)), "the steady wind, not the gust")
        assertEquals(LzSummary.SlopeWind(12.0, 0, false), LzSummary.slopeWindOf(report(12.0, 360)), "360 is north")
        assertEquals(LzSummary.SlopeWind(7.0, null, true), LzSummary.slopeWindOf(report(7.0, null, variable = true)))
    }

    @Test
    fun `a heading off the compass is brought back onto it`() {
        val plane = grounds.getValue("a plane of 8° rising east").second
        val call = LzSummary.slopeCall(8.0, plane, 450, null)
        assertEquals(90, call.heading)
        assertEquals(LzSummary.slopeCall(8.0, plane, 90, null), call)
        assertEquals(LzSummary.slopeCall(8.0, plane, 0, LzSummary.SlopeWind(10.0, 0, false)), LzSummary.slopeCall(8.0, plane, -360, LzSummary.SlopeWind(10.0, 720, false)))
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

    private companion object {
        val WEB_LIMIT = mapOf(LzSummary.SlopeLimit.NOSE_UP to "noseUp", LzSummary.SlopeLimit.NOSE_DOWN to "noseDown", LzSummary.SlopeLimit.CROSS to "cross")
    }
}
