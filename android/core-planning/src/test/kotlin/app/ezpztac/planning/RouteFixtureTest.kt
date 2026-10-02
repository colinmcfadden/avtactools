package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.PlanResult
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max

/**
 * Held to contracts/fixtures/planning/route.json, which records what the web's
 * routeCalc.js answers. The plan this produces is exported to AMPS, so these are
 * the numbers a crew flies.
 */
class RouteFixtureTest {
    private val fixture = Fixtures.load("planning/route.json")
    private val aircraft = Fixtures.load("planning/aircraft.json")
    private val tolerance = fixture["tolerance"]!!.jsonPrimitive.double
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profiles: Map<String, AircraftProfile> = aircraft["profiles"]!!.jsonArray.associate {
        val o = it.jsonObject
        o["key"]!!.jsonPrimitive.content to AircraftProfile.normalize(o["profile"]!!.jsonObject)
    }

    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= tolerance * max(1.0, abs(expected))

    @Test
    fun `great-circle distance and course match the web`() {
        fixture["greatCircle"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            fun n(key: String) = c[key]!!.jsonPrimitive.double
            val distance = GreatCircle.distanceNm(n("lat1"), n("lon1"), n("lat2"), n("lon2"))
            val course = GreatCircle.trueCourseDeg(n("lat1"), n("lon1"), n("lat2"), n("lon2"))
            assertTrue(close(n("distanceNm"), distance), "distance of $c: $distance")
            assertTrue(close(n("trueCourseDeg"), course), "course of $c: $course")
        }
    }

    @Test
    fun `the wind triangle matches the web, including the winds that cannot be flown`() {
        val cases = fixture["windTriangle"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.any { it["expected"] is JsonNull }, "the fixture lost its unflyable case")
        cases.forEach { c ->
            fun n(key: String) = c[key]!!.jsonPrimitive.double
            val actual = RouteCalc.solveWindTriangle(n("tasKts"), n("courseDeg"), n("windFromDeg"), n("windKts"))
            val expected = c["expected"]!!
            if (expected is JsonNull) {
                assertEquals(null, actual, "$c")
            } else {
                val e = expected.jsonObject
                requireNotNull(actual) { "$c should be flyable" }
                assertTrue(close(e["gsKts"]!!.jsonPrimitive.double, actual.gsKts), "gs $c: ${actual.gsKts}")
                assertTrue(close(e["windCorrectionDeg"]!!.jsonPrimitive.double, actual.windCorrectionDeg), "wca $c")
                assertTrue(close(e["headwindKts"]!!.jsonPrimitive.double, actual.headwindKts), "headwind $c")
            }
        }
    }

    @Test
    fun `indicated to true airspeed matches the web`() {
        fixture["iasToTas"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            fun n(key: String) = c[key]!!.jsonPrimitive.double
            val actual = RouteCalc.iasToTas(n("iasKts"), n("pressAltFt"), n("oatC"))
            assertTrue(close(n("expected"), actual), "$c: $actual")
        }
    }

    @Test
    fun `durations round halves up, as JavaScript does`() {
        fixture["formatDuration"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val sec = c["sec"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.double }
            assertEquals(c["expected"]!!.jsonPrimitive.content, RouteCalc.formatDuration(sec), "formatDuration($sec)")
        }
        // Halves go up. Kotlin's round() goes to even and would say 1:00 for 60.5 s.
        assertEquals("1:01", RouteCalc.formatDuration(60.5))
        assertEquals("0:01", RouteCalc.formatDuration(0.5))
    }

    @Test
    fun `clocks print as HH MM SS`() {
        fixture["formatClock"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val (h, m, s) = c["time"]!!.jsonPrimitive.content.split(":").map { it.toInt() }
            assertEquals(c["expected"]!!.jsonPrimitive.content, RouteCalc.formatClock(LocalDateTime.of(2026, 7, 15, h, m, s)))
        }
        assertEquals("--:--:--", RouteCalc.formatClock(null))
    }

    @Test
    fun `a fresh plan is seeded from the aircraft profile`() {
        fixture["defaultPlan"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val profile = c["profile"]!!.jsonPrimitive.contentOrNull?.let { profiles.getValue(it) }
            val expected = json.decodeFromJsonElement<RoutePlan>(c["expected"]!!)
            assertEquals(expected, RoutePlans.default(profile), "default plan for ${c["profile"]}")
        }
    }

    @Test
    fun `an older save is backfilled and its TOT migrated`() {
        fixture["ensurePlan"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val route = c["route"]!!.jsonObject
            val points = json.decodeFromJsonElement<List<RoutePoint>>(route["points"]!!)
            val actual = RoutePlans.ensure(points, route["plan"] as? JsonObject)
            val differences = JsonCompare.differences(c["expected"]!!, json.encodeToJsonElement(actual), tolerance)
            assertEquals(emptyList<String>(), differences, c["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `whole routes plan to the web's numbers`() {
        val cases = fixture["plans"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 12, "the fixture shrank to ${cases.size}")
        cases.forEach { c ->
            val name = c["name"]!!.jsonPrimitive.content
            val points = json.decodeFromJsonElement<List<RoutePoint>>(c["route"]!!)
            val plan = json.decodeFromJsonElement<RoutePlan>(c["plan"]!!)
            val elevations = c["elevationsFt"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }

            val result: PlanResult = RouteCalc.computeRoutePlan(points, plan, elevations, today = LocalDate.of(2026, 7, 15))

            val differences = JsonCompare.differences(c["expected"]!!, json.encodeToJsonElement(result), tolerance)
            assertEquals(emptyList<String>(), differences.take(12), name)
        }
    }
}
