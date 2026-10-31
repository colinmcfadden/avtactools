package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/** Held to contracts/fixtures/planning/graphics.json: the web's geometry, placement and separation alerts for the planning graphics. */
class PlanningGraphicsFixtureTest {
    private val fixture = Fixtures.load("planning/graphics.json")
    private val tolerance = fixture.getValue("tolerance").jsonPrimitive.double
    private val profiles: Map<String, AircraftProfile> =
        fixture.getValue("profiles").jsonArray.associate { it.jsonObject.getValue("key").jsonPrimitive.content to AircraftProfile.normalize(it.jsonObject.getValue("profile").jsonObject) }

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun close(a: Double, b: Double) = abs(a - b) <= tolerance * max(1.0, abs(a))
    private fun num(c: JsonObject, key: String) = c.getValue(key).jsonPrimitive.double
    private fun nullableNum(c: JsonObject, key: String) = c.getValue(key).takeIf { it !is JsonNull }?.jsonPrimitive?.double
    private fun point(e: JsonElement) = LatLon(e.jsonArray[0].jsonPrimitive.double, e.jsonArray[1].jsonPrimitive.double)

    // -- Geometry --------------------------------------------------------------------------------------------------

    @Test
    fun `distances in feet are the web's`() {
        val all = cases("distanceFeet")
        assertTrue(all.size >= 10)
        for (c in all) {
            val actual = GreatCircle.distanceFeet(num(c, "lat1"), num(c, "lon1"), num(c, "lat2"), num(c, "lon2"))
            assertTrue(close(num(c, "expected"), actual), "$c: ${num(c, "expected")} vs $actual")
        }
    }

    @Test
    fun `the line between two rotor edges and the gap on it are the web's`() {
        for (c in cases("rotorEdge")) {
            val edge = PlanningGraphics.rotorEdge(LatLon(num(c, "lat1"), num(c, "lon1")), LatLon(num(c, "lat2"), num(c, "lon2")), nullableNum(c, "radius1Ft"), nullableNum(c, "radius2Ft"))
            val expected = c.getValue("expected").jsonObject
            val label = c.getValue("name").jsonPrimitive.content
            assertTrue(close(point(expected.getValue("start")).lat, edge.start.lat) && close(point(expected.getValue("start")).lon, edge.start.lon), "$label start")
            assertTrue(close(point(expected.getValue("end")).lat, edge.end.lat) && close(point(expected.getValue("end")).lon, edge.end.lon), "$label end")
            assertTrue(close(num(expected, "edgeDist"), edge.edgeDistFt), "$label gap ${num(expected, "edgeDist")} vs ${edge.edgeDistFt}")
        }
    }

    @Test
    fun `the angle of a rotate handle and where the handle sits are the web's`() {
        for (c in cases("angle")) {
            val actual = PlanningGraphics.angleDeg(LatLon(num(c, "cLat"), num(c, "cLon")), LatLon(num(c, "mLat"), num(c, "mLon")))
            assertTrue(close(num(c, "expected"), actual), "$c")
        }
        for (c in cases("handle")) {
            val actual = PlanningGraphics.handlePosition(LatLon(num(c, "lat"), num(c, "lon")), num(c, "rotation"))
            val expected = point(c.getValue("expected"))
            assertTrue(close(expected.lat, actual.lat) && close(expected.lon, actual.lon), "$c")
        }
    }

    // -- Aircraft --------------------------------------------------------------------------------------------------

    private fun aircraft(list: JsonElement) = list.jsonArray.mapNotNull(PlanningGraphics.Aircraft::of)

    private fun resolver(active: AircraftProfile) = { a: PlanningGraphics.Aircraft -> AircraftLookup.profileForAsset(a.profileRef, profiles.values.toList(), active) }

    @Test
    fun `a new aircraft is placed as the web places it`() {
        val all = cases("place")
        assertTrue(all.size >= 8)
        for (c in all) {
            val active = profiles.getValue(c.getValue("active").jsonPrimitive.content)
            val placed = PlanningGraphics.placeHelicopter(point(c.getValue("target")), aircraft(c.getValue("helicopters")), active, resolver(active), c.getValue("id"))
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("expected"), placed, tolerance), c.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `the aircraft that are too close raise the web's alerts, in its order and words`() {
        for (c in cases("alerts")) {
            val active = profiles.getValue(c.getValue("active").jsonPrimitive.content)
            val alerts = PlanningGraphics.separationAlerts(aircraft(c.getValue("helicopters")), resolver(active))
            val expected = c.getValue("expected").jsonArray.map { it.jsonObject }
            val label = c.getValue("name").jsonPrimitive.content
            assertEquals(expected.map { it.getValue("id").jsonPrimitive.content }, alerts.map { it.id }, label)
            assertEquals(expected.map { it.getValue("message").jsonPrimitive.content }, alerts.map { it.message }, label)
        }
    }

    // -- The other graphics ----------------------------------------------------------------------------------------

    /** JavaScript's `Number(x)`: an absent value is NaN, null is 0, text is read whole. */
    private fun jsNumber(e: JsonElement?): Double = when {
        e == null -> Double.NaN
        e is JsonNull -> 0.0
        e is JsonPrimitive && e.isString -> e.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
        e is JsonPrimitive -> e.doubleOrNull ?: Double.NaN
        else -> Double.NaN
    }

    /** JavaScript's `parseFloat(x)`, for the shapes the cases use: null and absent are NaN. */
    private fun jsParseFloat(e: JsonElement?): Double = when {
        e == null || e is JsonNull -> Double.NaN
        e is JsonPrimitive && e.isString -> e.content.trim().toDoubleOrNull() ?: Double.NaN
        e is JsonPrimitive -> e.doubleOrNull ?: Double.NaN
        else -> Double.NaN
    }

    @Test
    fun `a PZ marker starts as the web starts it, and is refused where the web would make one of NaN`() {
        for (c in cases("pzMarker")) {
            val target = c.getValue("target").jsonArray
            val made = PlanningGraphics.createPzMarker(jsParseFloat(target.getOrNull(0)), jsParseFloat(target.getOrNull(1)), c.getValue("id").jsonPrimitive.content)
            val expected = c.getValue("expected").jsonObject
            if (expected.values.any { it is JsonNull }) assertNull(made, "$target") else assertEquals(emptyList<String>(), JsonCompare.differences(expected, made!!, tolerance), "$target")
        }
    }

    @Test
    fun `a sector of fire starts as the web starts it`() {
        for (c in cases("sectorOfFire")) {
            val target = c.getValue("target").jsonArray
            val made = PlanningGraphics.createSectorOfFire(jsNumber(target.getOrNull(0)), jsNumber(target.getOrNull(1)), c.getValue("id").jsonPrimitive.content)
            val expected = c.getValue("expected")
            if (expected is JsonNull) assertNull(made, "$target") else assertEquals(emptyList<String>(), JsonCompare.differences(expected, made!!, tolerance), "$target")
        }
    }

    @Test
    fun `a go-around starts as the web starts it, south unless it was spelled N`() {
        for (c in cases("goAround")) {
            val target = c.getValue("target").jsonArray
            val direction = c.getValue("direction").takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val made = PlanningGraphics.createGoAround(jsNumber(target.getOrNull(0)), jsNumber(target.getOrNull(1)), direction, c.getValue("id").jsonPrimitive.content)
            val expected = c.getValue("expected")
            if (expected is JsonNull) assertNull(made, "$target") else assertEquals(emptyList<String>(), JsonCompare.differences(expected, made!!, tolerance), "$direction $target")
        }
    }

    @Test
    fun `a saved aircraft with no position is left out of the measuring`() {
        assertNull(PlanningGraphics.Aircraft.of(JsonObject(mapOf("id" to JsonPrimitive(1)))))
        assertNull(PlanningGraphics.Aircraft.of(JsonPrimitive("x")))
        assertNull(PlanningGraphics.Aircraft.of(JsonArray(emptyList())))
    }
}
