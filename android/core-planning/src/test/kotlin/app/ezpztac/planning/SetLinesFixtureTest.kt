package app.ezpztac.planning

import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The lines kept with a set (the web's feature/setLines), held to `planning/flots.json`'s `data`, `lengths` and `sets`. */
class SetLinesFixtureTest {
    private val fixture = Fixtures.load("planning/flots.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun `lines are the entries that are objects with an id, and their points the places`() {
        for (c in cases("data")) {
            val name = c.getValue("name").jsonPrimitive.content
            val lines = SetLines.fromData(c["data"], SetLines.FLOTS)
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("lines"), JsonArray(lines), 1e-9), name)
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("points"), JsonArray(lines.map { JsonArray(SetLines.points(it)) }), 1e-9), name)
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("plain"), JsonArray(lines.map { SetLines.plain(SetLines.places(it)) }), 1e-9), name)
        }
    }

    @Test
    fun `a line is as long as its places along the web's sphere`() {
        for (c in cases("lengths")) {
            assertEquals(c.getValue("km").jsonPrimitive.double, SetLines.lengthKm(c["line"]), 1e-6, c.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `a set is control measures when it holds lines and no routes, or was made for them`() {
        for (c in cases("sets")) {
            val name = c.getValue("name").jsonPrimitive.content
            val made = (c["made"] as? JsonPrimitive)?.boolean ?: false
            assertEquals(c.getValue("count").jsonPrimitive.int, SetLines.count(c["set"]), name)
            assertEquals(c.getValue("measures").jsonPrimitive.boolean, SetLines.isMeasures(c["set"], made), name)
        }
    }

    @Test
    fun `a route set read as one says the same`() {
        val flot = Flots.create(listOf(LatLon(34.8, -84.09), LatLon(34.81, -84.07)), "flot-1")
        val lines = RouteSet("s", name = "CONTROL MEASURES", routes = emptyList(), extras = JsonObject(mapOf("version" to JsonPrimitive(1), "flots" to JsonArray(listOf(flot)))))
        assertTrue(SetLines.isMeasures(lines))
        assertEquals(1, SetLines.count(lines))
        assertFalse(SetLines.isMeasures(lines.copy(unreadable = listOf(JsonPrimitive(5)))), "a route this version cannot read is a route")
        assertFalse(SetLines.isMeasures(RouteSet("s", name = "X", routes = emptyList())))
        assertTrue(SetLines.isMeasures(RouteSet("s", name = "X", routes = emptyList()), made = true))
    }

    @Test
    fun `lines go in under their key, a phase line's after a FLOT's, and none leaves the key out`() {
        val flot = Flots.create(listOf(LatLon(1.0, 2.0)), "f")
        val line = PhaseLines.create(listOf(LatLon(1.0, 2.0)), "p", "red")
        val empty = RouteSet("s", name = "X", routes = emptyList(), extras = JsonObject(mapOf("version" to JsonPrimitive(1))))
        val withLine = SetLines.withLines(empty, SetLines.PHASE_LINES, listOf(line))
        val both = SetLines.withLines(withLine, SetLines.FLOTS, listOf(flot))
        assertEquals(listOf("version", "flots", "phaseLines"), both.extras.keys.toList())
        assertEquals(listOf("version", "phaseLines"), SetLines.withLines(both, SetLines.FLOTS, emptyList()).extras.keys.toList())
        assertEquals(empty, SetLines.withLines(empty, SetLines.FLOTS, emptyList()))
        assertEquals(listOf(flot), SetLines.of(both, SetLines.FLOTS))
    }

    @Test
    fun `text is never a number, so a point given as text is no place`() {
        val p = JsonObject(mapOf("lat" to JsonPrimitive("34.8"), "lon" to JsonPrimitive(-84.0)))
        assertFalse(SetLines.isPoint(p))
        assertFalse(SetLines.isPoint(JsonObject(mapOf("lat" to JsonPrimitive(true), "lon" to JsonPrimitive(1)))))
        assertFalse(SetLines.isPoint(JsonNull))
        assertTrue(SetLines.isPoint(JsonObject(mapOf("lat" to JsonPrimitive(0), "lon" to JsonPrimitive(0)))))
    }

    @Test
    fun `JavaScript's truthiness and String`() {
        val t = { e: JsonElement? -> SetLines.truthy(e) }
        assertFalse(t(null)); assertFalse(t(JsonNull)); assertFalse(t(JsonPrimitive(0))); assertFalse(t(JsonPrimitive(""))); assertFalse(t(JsonPrimitive(false)))
        assertTrue(t(JsonPrimitive(1))); assertTrue(t(JsonPrimitive("no"))); assertTrue(t(JsonArray(emptyList()))); assertTrue(t(JsonObject(emptyMap())))
        assertEquals("5", SetLines.jsString(JsonPrimitive(5.0)))
        assertEquals("1,,2", SetLines.jsString(JsonArray(listOf(JsonPrimitive(1), JsonNull, JsonPrimitive(2)))))
        assertEquals("[object Object]", SetLines.jsString(JsonObject(emptyMap())))
    }
}
