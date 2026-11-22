package app.ezpztac.planning

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A backstop's arrow and label (the web's feature/backstops/backstops.js), held to `planning/backstops.json`. */
class BackstopsFixtureTest {
    private val fixture = Fixtures.load("planning/backstops.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private val target = fixture.getValue("target").jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }

    private fun place(o: JsonObject) = LatLon(o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double)
    private fun backstop(c: JsonObject) = JsonObject(buildMap {
        put("id", JsonPrimitive("b"))
        put("points", c.getValue("points"))
        c["arrow"]?.let { put("arrow", it) }
    })

    private fun assertNear(expected: LatLon, actual: LatLon, name: String) {
        assertEquals(expected.lat, actual.lat, 1e-8, name)
        assertEquals(expected.lon, actual.lon, 1e-8, name)
    }

    @Test
    fun `the arrow runs from the line's middle, or its own tail, to the target or its own tip, and says so`() {
        for (c in cases("readings")) {
            val name = c.getValue("name").jsonPrimitive.content
            val arrow = Backstops.arrow(backstop(c), target)!!
            assertNear(place(c.getValue("tail").jsonObject), arrow.tail, name)
            assertNear(place(c.getValue("tip").jsonObject), arrow.tip, name)
            assertEquals(c.getValue("onTarget").jsonPrimitive.boolean, arrow.onTarget, name)
            val reading = Backstops.reading(arrow)
            assertEquals(c.getValue("azimuth").jsonPrimitive.int, reading.azimuth, name)
            assertEquals(c.getValue("distanceKm").jsonPrimitive.double, reading.distanceKm, 1e-6, name)
            assertEquals(c.getValue("text").jsonPrimitive.content, reading.text, name)
        }
    }

    @Test
    fun `a move takes both ends, and leaves a tip of its own`() {
        for (c in cases("moves")) {
            val arrow = Backstops.arrow(backstop(c), target)!!
            val moved = Backstops.moved(arrow, c.getValue("dLat").jsonPrimitive.double, c.getValue("dLon").jsonPrimitive.double)
            val result = c.getValue("result").jsonObject
            assertNear(place(result.getValue("tail").jsonObject), place(moved.getValue("tail").jsonObject), "tail")
            assertNear(place(result.getValue("tip").jsonObject), place(moved.getValue("tip").jsonObject), "tip")
        }
    }

    @Test
    fun `a tip dropped near the target goes back onto it, and one too near the tail is refused`() {
        val arrow = BackstopArrow(LatLon(34.8, -84.08), LatLon(34.79, -84.08), onTarget = false)
        val at = LatLon(34.785, -84.081)
        // Far from the tail, far from the target: the tip is where it was dropped.
        val far = Backstops.aimed(arrow, at, target) { if (it == arrow.tail) 100.0 else 50.0 }!!
        assertEquals(listOf("tail", "tip"), far.keys.toList())
        assertNear(at, place(far.getValue("tip").jsonObject), "dropped")
        // Within 20 px of the target: back on it, with no tip of its own.
        assertEquals(listOf("tail"), Backstops.aimed(arrow, at, target) { if (it == arrow.tail) 100.0 else 20.0 }!!.keys.toList())
        // Under 24 px from the tail: refused, though the target is near.
        assertNull(Backstops.aimed(arrow, at, target) { if (it == arrow.tail) 23.9 else 5.0 })
        // With no target nothing is snapped to.
        assertEquals(listOf("tail", "tip"), Backstops.aimed(arrow, at, null) { 100.0 }!!.keys.toList())
    }

    @Test
    fun `a tail of its own is kept as it is, what rides along with it too`() {
        val tail = JsonObject(mapOf("lat" to JsonPrimitive(34.8), "lon" to JsonPrimitive(-84.08), "note" to JsonPrimitive("kept")))
        val b = JsonObject(mapOf("id" to JsonPrimitive("b"), "points" to SetLines.plain(listOf(LatLon(34.8, -84.09))), "arrow" to JsonObject(mapOf("tail" to tail))))
        val arrow = Backstops.arrow(b, target)!!
        assertEquals(tail, Backstops.aimed(arrow, LatLon(34.0, -84.0), target) { 100.0 }!!.getValue("tail"))
    }

    @Test
    fun `nothing to start from or point at is no arrow`() {
        assertNull(Backstops.arrow(JsonObject(mapOf("id" to JsonPrimitive("b"), "points" to SetLines.plain(emptyList()))), target))
        assertNull(Backstops.arrow(Backstops.create(listOf(LatLon(34.8, -84.09)), "b"), null))
        assertNull(Backstops.anchor(emptyList()))
    }

    @Test
    fun `north reads 000 and due south 180`() {
        assertEquals("000°T · 1.1 km", Backstops.reading(BackstopArrow(LatLon(34.79, -84.08), LatLon(34.8, -84.08), true)).text)
        assertEquals(180, Backstops.reading(BackstopArrow(LatLon(34.8, -84.08), LatLon(34.79, -84.08), true)).azimuth)
    }
}
