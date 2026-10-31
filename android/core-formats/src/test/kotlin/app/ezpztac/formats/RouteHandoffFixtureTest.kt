package app.ezpztac.formats

import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Held to `contracts/fixtures/routes/handoff.json`: what the web's `foreflight.js` makes of each route. A route is handed to other apps in the same words
 * and the same digits however it was made.
 */
class RouteHandoffFixtureTest {
    private val cases = Fixtures.load("routes/handoff.json")["cases"]!!.jsonArray.map { it.jsonObject }

    private fun route(c: JsonObject): SketchRoute {
        val r = c["route"]!!.jsonObject
        val points = r["points"]!!.jsonArray.map {
            val p = it.jsonObject
            RoutePoint(lat = p["lat"]!!.jsonPrimitive.double, lon = p["lon"]!!.jsonPrimitive.double, name = p["name"]?.takeIf { n -> n !is JsonNull }?.jsonPrimitive?.content)
        }
        return SketchRoute(id = "sketch-1", name = r["name"]!!.jsonPrimitive.content, color = "#FF453A", points = points)
    }

    private fun expected(c: JsonObject, key: String) = c["expected"]!!.jsonObject[key]!!.jsonPrimitive.content
    private fun name(c: JsonObject) = c["name"]!!.jsonPrimitive.content

    @Test
    fun `the fixture is not a few cases`() {
        assertTrue(cases.size >= 10, "the fixture shrank to ${cases.size}")
    }

    @Test
    fun `every Garmin flight plan is the web's, byte for byte`() {
        cases.forEach { c -> assertEquals(expected(c, "fpl"), RouteHandoff.fpl(route(c), Instant.parse(c["now"]!!.jsonPrimitive.content)), name(c)) }
    }

    @Test
    fun `every GPX route is the web's, byte for byte`() {
        cases.forEach { c -> assertEquals(expected(c, "gpx"), RouteHandoff.gpx(route(c)), name(c)) }
    }

    @Test
    fun `every ForeFlight route string and address is the web's`() {
        cases.forEach { c ->
            assertEquals(expected(c, "routeString"), RouteHandoff.routeString(route(c)), name(c))
            assertEquals(expected(c, "url"), RouteHandoff.foreFlightUrl(route(c)), name(c))
        }
    }

    @Test
    fun `identifiers are what the fixtures' own routes show`() {
        val c = cases.first { name(it).startsWith("duplicate identifiers") }
        assertEquals(
            listOf("CP", "CP2", "CP3", "ABCDEFGHIJ") + (2..9).map { "ABCDEFGHI$it" } + listOf("ABCDEFGH10", "ABCDEFGH11"),
            RouteHandoff.identifiers(RouteHandoff.points(route(c))),
        )
    }

    @Test
    fun `a time with no milliseconds is still written with three digits`() {
        val r = SketchRoute(id = "x", name = "N", color = "#FF453A", points = listOf(RoutePoint(lat = 1.0, lon = 2.0, name = "A")))
        assertTrue(RouteHandoff.fpl(r, Instant.parse("2026-01-01T00:00:00Z")).contains("<created>2026-01-01T00:00:00.000Z</created>"))
        assertTrue(RouteHandoff.fpl(r, Instant.parse("2026-01-01T00:00:00.007Z")).contains("<created>2026-01-01T00:00:00.007Z</created>"))
    }

    @Test
    fun `a file name keeps letters, digits, underscore and hyphen, and falls back to route`() {
        fun file(name: String, ext: String = "gpx") = RouteHandoff.fileName(SketchRoute(id = "x", name = name, color = "#FF453A", points = emptyList()), ext)
        assertEquals("NEPTUNE_RUN.gpx", file("NEPTUNE RUN"))
        assertEquals("A_B_C-D.fpl", file("A/B\\C-D", "fpl"))
        assertEquals("_.gpx", file("***"))                                    // the web: "***".replace(/[^\w-]+/g, "_") is "_", which is not empty
        assertEquals("route.gpx", file(""))
    }
}
