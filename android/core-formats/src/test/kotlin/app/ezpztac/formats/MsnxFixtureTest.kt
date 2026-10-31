package app.ezpztac.formats

import app.ezpztac.model.Mission
import app.ezpztac.model.RoutePoint
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Held to contracts/fixtures/msnx: real AMPS packages, including missions the web app
 * itself exported, and what the web's reader makes of each. The native app has to open
 * what the web writes, and what AMPS writes.
 */
class MsnxFixtureTest {
    private val fixture = Fixtures.load("msnx/parse.json")
    private val tolerance = fixture["tolerance"]!!.jsonPrimitive.double
    private val json = Json { encodeDefaults = true }
    private val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

    private fun case(name: String): JsonObject = cases.first { it["name"]!!.jsonPrimitive.content == name }
    private fun read(name: String): Mission = MsnxReader.read(Fixtures.bytes("msnx/${case(name)["file"]!!.jsonPrimitive.content}"))

    @Test
    fun `every mission reads the way the web reads it`() {
        assertTrue(cases.size >= 5, "the fixture shrank to ${cases.size}")
        cases.forEach { c ->
            val name = c["name"]!!.jsonPrimitive.content
            val mission = read(name)
            val differences = JsonCompare.differences(c["expected"]!!, json.encodeToJsonElement(mission), tolerance)
            assertEquals(emptyList<String>(), differences.take(12), name)
        }
    }

    @Test
    fun `the AMPS template is a UH-60L mission with one route`() {
        val mission = read("template")
        assertEquals("UH-60L", mission.aircraft?.designation)
        assertEquals(1, mission.routes.size)
        val route = mission.routes.single()
        assertEquals("NEPTUNE", route.name)
        assertEquals(27, route.points.size)
        assertEquals("start", route.points.first().role)
    }

    @Test
    fun `shaping points are told apart from route points by points xml, not by name`() {
        val route = read("template").routes.single()
        val shaping = route.points.filter { it.kind == RoutePoint.KIND_SHAPING }
        assertTrue(shaping.isNotEmpty())
        assertTrue(shaping.all { it.ptType == null })
        assertTrue(route.points.filter { it.kind == "amps" }.all { it.ptType != null })
    }

    @Test
    fun `points with no AMPS point behind them keep distinct identities and are not given invented ids`() {
        val points = read("anonymous-points").routes.flatMap { it.points }.filter { it.id == null }
        assertEquals(2, points.size)
        assertEquals(2, points.map { it.uiId }.toSet().size)
        assertTrue(points.all { it.kind == "shaping" })
    }

    @Test
    fun `a mission the web exported reads back as what was sketched`() {
        val route = read("sketch-export").routes.single()
        assertEquals("FIXTURE ROUTE", route.name)
        assertEquals(listOf(".SP", ".Serpentine 1", ".IP1", ".Serpentine 1", ".Serpentine 2", ".LZ1"), route.points.map { it.name })
        assertEquals(listOf("turn", null, "ip", null, null, "target"), route.points.map { it.ptType })
        // The plan the web wrote comes back: indicated airspeed, the first point's altitude, a winds change.
        assertEquals("indicated", route.plan.airspeed.type)
        assertEquals(110.0, route.plan.airspeed.value)
        assertEquals(300.0, route.plan.wind?.dirTrue)
        // A time on target was written, so the plan is anchored to a date.
        assertEquals("2026-07-15", route.plan.date)
        assertTrue(route.plan.perPoint.values.any { it.clock != null })
    }

    @Test
    fun `a plan edit written back by the web reads back within rounding`() {
        val route = read("plan-edited").routes.single()
        val arrival = route.plan.perPoint.values.first { it.airspeed?.value == 123.0 }
        assertEquals("ground", arrival.airspeed?.type)
        assertEquals(1500.0, arrival.altitude?.value)
        assertEquals("msl", arrival.altitude?.ref)
        assertTrue(kotlin.math.abs((arrival.wind?.dirTrue ?: 0.0) - 250) <= 1)
        assertTrue(kotlin.math.abs((arrival.wind?.speedKts ?: 0.0) - 20) <= 1)
    }

    @Test
    fun `two routes in one mission stay apart`() {
        val routes = read("sketch-two-routes").routes
        assertEquals(listOf("FIXTURE ROUTE", "SECOND ROUTE"), routes.map { it.name })
        assertEquals(setOf(6, 3), routes.map { it.points.size }.toSet())
        assertEquals(2, routes.map { it.segmentId }.toSet().size)
    }

    @Test
    fun `an unreadable file says so`() {
        assertEquals(
            "Couldn't open this file as a .msnx mission archive.",
            assertThrowsMessage { MsnxReader.read(ByteArray(0)) },
        )
        assertEquals(
            "Couldn't open this file as a .msnx mission archive.",
            assertThrowsMessage { MsnxReader.read("this is not a zip file at all".toByteArray()) },
        )
    }

    private fun assertThrowsMessage(block: () -> Unit): String? =
        try { block(); null } catch (e: MsnxException) { e.message }
}
