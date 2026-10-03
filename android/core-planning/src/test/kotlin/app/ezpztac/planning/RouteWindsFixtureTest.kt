package app.ezpztac.planning

import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Held to contracts/fixtures/routes/winds.json: which instant each point asks the weather service about, and how the answer is merged into the plan. */
class RouteWindsFixtureTest {
    private val fixture = Fixtures.load("routes/winds.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun route(c: JsonObject) = json.decodeFromJsonElement<SketchRoute>(c.getValue("route"))

    @Test
    fun `each route asks for the winds the web asks for`() {
        val all = cases("request")
        assertTrue(all.size >= 11)
        for (c in all) {
            val label = c.getValue("label").jsonPrimitive.content
            val expected = c.getValue("expected").jsonObject
            // Every case that has a clock names its date, so the day it falls on never depends on today.
            val actual = RouteWinds.request(route(c), today = LocalDate.of(2000, 1, 1))
            if (expected.containsKey("error")) {
                assertNull(actual, label)
                continue
            }
            actual!!
            assertEquals(expected.getValue("ampIds").jsonArray.map { it.jsonPrimitive.content }, actual.amps.map { it.id }, "$label: points")
            val wanted = expected.getValue("points").jsonArray.map { it.jsonObject }
            assertEquals(wanted.size, actual.points.size, label)
            wanted.zip(actual.points).forEachIndexed { i, (want, got) ->
                assertEquals(want.getValue("id").jsonPrimitive.content, got.id, "$label #$i id")
                assertEquals(want.getValue("lat").jsonPrimitive.double(), got.lat, 0.0, "$label #$i lat")
                assertEquals(want.getValue("lon").jsonPrimitive.double(), got.lon, 0.0, "$label #$i lon")
                assertEquals(want["localTime"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, got.localTime?.format(WALL), "$label #$i time")
            }
        }
    }

    private fun JsonPrimitive.double() = doubleOrNull ?: error("not a number: $this")

    /** The fixture writes every time to the millisecond (`LocalDateTime.toString` drops zero seconds and trailing zeros). */
    private val WALL = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    @Test
    fun `each answer is merged into the plan as the web merges it`() {
        val all = cases("merge")
        assertTrue(all.size >= 9)
        for (c in all) {
            val label = c.getValue("label").jsonPrimitive.content
            val plan = json.decodeFromJsonElement<RoutePlan>(c.getValue("plan"))
            val amps = c.getValue("ampIds").jsonArray.map { RoutePoint(id = it.jsonPrimitive.content, lat = 0.0, lon = 0.0) }
            val winds = c.getValue("winds").jsonObject.mapValues { (_, w) ->
                val o = w.jsonObject
                PointWind(o.getValue("dirTrue").jsonPrimitive.double(), o.getValue("speedKts").jsonPrimitive.double(), o["tempC"]?.let(::temperature))
            }
            val merged = json.encodeToJsonElement(RouteWinds.merge(plan, amps, winds))
            val differences = JsonCompare.differences(c.getValue("expected"), merged)
            assertTrue(differences.isEmpty(), "$label: ${differences.take(8)}")
        }
    }

    /** What the web's `typeof tempC === "number"` accepts: a number. Anything else (null, text) is no temperature. */
    private fun temperature(e: JsonElement): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    @Test
    fun `a clock with no date falls on the day it is asked on`() {
        val route = json.decodeFromJsonElement<SketchRoute>(cases("request")[1].getValue("route"))
        val undated = route.copy(plan = route.plan.copy(date = ""))
        val asked = RouteWinds.request(undated, today = LocalDate.of(2026, 3, 4))!!
        assertEquals(LocalDate.of(2026, 3, 4), asked.points[2].localTime!!.toLocalDate())
        assertEquals(LocalDateTime.of(2026, 3, 4, 12, 30), asked.points[2].localTime)
        assertEquals(LocalDate.of(2026, 3, 4), asked.points[0].localTime!!.toLocalDate())     // the other points follow the anchor on the same day
    }

    @Test
    fun `a time is written as the instant it is in the device's zone, to the millisecond`() {
        val noon = LocalDateTime.of(2026, 10, 3, 12, 0)
        assertEquals("2026-10-03T12:00:00.000Z", RouteWinds.instantText(noon, ZoneId.of("UTC")))
        assertEquals("2026-10-03T16:00:00.000Z", RouteWinds.instantText(noon, ZoneId.of("America/New_York")))      // daylight time: four hours behind
        assertEquals("2026-12-03T17:00:00.000Z", RouteWinds.instantText(LocalDateTime.of(2026, 12, 3, 12, 0), ZoneId.of("America/New_York")))   // standard time: five
        assertEquals("2026-10-02T23:00:00.000Z", RouteWinds.instantText(noon, ZoneId.of("Pacific/Auckland")))     // east of Greenwich: the day before
        assertEquals("2026-10-03T12:30:05.000Z", RouteWinds.instantText(LocalDateTime.of(2026, 10, 3, 12, 30, 5), ZoneId.of("UTC")))
    }

    @Test
    fun `ground elevations are kept for the points that have one, by id`() {
        val route = json.decodeFromJsonElement<SketchRoute>(cases("request")[0].getValue("route"))
        val amps = RouteElevations.points(route)
        assertEquals(listOf("p1", "p2", "p4", "p5"), amps.map { it.id })                   // the shaping point is not asked about
        assertEquals(mapOf("p1" to 1312.0, "p4" to 900.0), RouteElevations.byPoint(amps, listOf(1312.0, null, 900.0, null)))
        assertEquals(mapOf("p1" to 5.0), RouteElevations.byPoint(amps, listOf(5.0)))        // fewer answers than points: the rest have none
        assertEquals(emptyMap<String, Double>(), RouteElevations.byPoint(amps, emptyList()))
        assertEquals(emptyMap<String, Double>(), RouteElevations.byPoint(listOf(RoutePoint(id = null, lat = 0.0, lon = 0.0)), listOf(7.0)))
    }

    @Test
    fun `a point with no override is given one, and a point with no answer is not`() {
        val plan = RoutePlan()
        val amps = listOf(RoutePoint(id = "a", lat = 0.0, lon = 0.0), RoutePoint(id = "b", lat = 0.0, lon = 0.0))
        val merged = RouteWinds.merge(plan, amps, mapOf("a" to PointWind(90.0, 5.0)))
        assertEquals(setOf("a"), merged.perPoint.keys)
        assertEquals(plan.tempC, merged.tempC)                                              // no temperature came back: the plan's own stays
    }

    @Test
    fun `winds are merged into a copy and the plan that was given is left as it was`() {
        val plan = RoutePlan(perPoint = mapOf("a" to app.ezpztac.model.PointOverride(clock = "12:00")))
        val amps = listOf(RoutePoint(id = "a", lat = 0.0, lon = 0.0))
        val merged = RouteWinds.merge(plan, amps, mapOf("a" to PointWind(10.0, 2.0, 7.0)))
        assertEquals("12:00", merged.perPoint.getValue("a").clock)
        assertEquals(10.0, merged.perPoint.getValue("a").wind!!.dirTrue)
        assertEquals(7.0, merged.tempC)
        assertNull(plan.perPoint.getValue("a").wind)
    }
}
