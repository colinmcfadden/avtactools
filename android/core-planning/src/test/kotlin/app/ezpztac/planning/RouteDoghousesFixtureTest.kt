package app.ezpztac.planning

import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSets
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** A doghouse on each leg of a route, and the legs that stand in for an LZ/PZ's doghouses (the web's routeDoghouses.js and lzLegs.js), held to `routes/doghouses.json`. */
class RouteDoghousesFixtureTest {
    private val fixture = Fixtures.load("routes/doghouses.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private val today = LocalDate.of(2026, 11, 26)

    private companion object {
        val READ_AS_NUMBERS = setOf("aPointThatIsNoPlace")
    }

    // The routes as the app reads a saved set: one it cannot read (a point whose latitude is text) is kept aside, as the web draws nothing of it.
    private fun routes(element: JsonElement) =
        RouteSets.parse("set", null, "SET", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to element))).routes

    private fun text(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.content

    @Test
    fun `each leg's doghouse stands where the web puts it, turned and offset as it is, and says what it says`() {
        for (case in cases("legs")) {
            val name = text(case, "name")
            val expected = case.getValue("doghouses").jsonArray.map { it.jsonObject }
            val read = routes(case.getValue("routes"))
            if (name in READ_AS_NUMBERS) {
                // Not like the web: a place written as text is read as its number here (RouteSets), so the route is whole and has its doghouse,
                // where the web's guard leaves a route with such a point without one. Nothing here can hand the planner a place that is not a number.
                assertEquals(0, expected.size, "$name: the web draws none")
                assertEquals(1, RouteDoghouses.of(read, today).size, "$name: read as its number")
                continue
            }
            val actual = RouteDoghouses.of(read, today)
            assertEquals(expected.size, actual.size, "$name: doghouses")
            for ((e, a) in expected.zip(actual)) {
                val label = "$name ${text(e, "key")}"
                assertEquals(text(e, "key"), a.key, label)
                assertEquals(e.getValue("lat").jsonPrimitive.double, a.lat, 1e-8, "$label lat")
                assertEquals(e.getValue("lon").jsonPrimitive.double, a.lon, 1e-8, "$label lon")
                assertEquals(e.getValue("rotation").jsonPrimitive.double, a.rotation, 1e-8, "$label rotation")
                val offset = e.getValue("offset").jsonObject
                assertEquals(offset.getValue("x").jsonPrimitive.double, a.offset.x, 1e-9, "$label x")
                assertEquals(offset.getValue("y").jsonPrimitive.double, a.offset.y, 1e-9, "$label y")
                assertEquals(e.getValue("lengthPx0").jsonPrimitive.double, a.lengthPx0, 1e-8, "$label length")
                val box = e.getValue("box").jsonObject
                assertEquals(
                    LegDoghouseBox(text(box, "id"), text(box, "heading"), text(box, "minutes"), text(box, "seconds"), text(box, "distance"), text(box, "airspeed")),
                    a.box,
                    label,
                )
            }
        }
    }

    @Test
    fun `the box stands clear of the line on its right, and is drawn smaller where its leg is short`() {
        for (case in cases("offsets")) {
            val course = case.getValue("course").jsonPrimitive.double
            val stretch = case.getValue("stretch").jsonPrimitive.double
            val offset = case.getValue("offset").jsonObject
            assertEquals(LegOffset(offset.getValue("x").jsonPrimitive.double, offset.getValue("y").jsonPrimitive.double), RouteDoghouses.sideOffset(course, stretch), "$course/$stretch")
        }
        for (case in cases("scales")) {
            val zoom = case.getValue("zoom").jsonPrimitive.doubleOrNull ?: Double.NaN
            val scale = RouteDoghouses.scaleAt(case.getValue("lengthPx0").jsonPrimitive.double, zoom, case.getValue("min").jsonPrimitive.double)
            assertEquals(case.getValue("scale").jsonPrimitive.double, scale, "zoom $zoom")
        }
    }

    @Test
    fun `times, courses and names are written as the web writes them`() {
        val texts = fixture.getValue("texts").jsonObject
        for (case in texts.getValue("legTime").jsonArray.map { it.jsonObject }) {
            assertEquals(text(case, "minutes") to text(case, "seconds"), RouteDoghouses.legTime(case.getValue("time").jsonPrimitive.doubleOrNull), case.toString())
        }
        for (case in texts.getValue("courseText").jsonArray.map { it.jsonObject }) {
            assertEquals(text(case, "text"), RouteDoghouses.courseText(case.getValue("degrees").jsonPrimitive.doubleOrNull), case.toString())
        }
        for (case in texts.getValue("roofName").jsonArray.map { it.jsonObject }) {
            assertEquals(text(case, "text"), RouteDoghouses.roofName(case.getValue("name").jsonPrimitive.contentOrNull), case.toString())
        }
    }

    private fun target(element: JsonElement?): LatLon? = when (element) {
        null, JsonNull -> null
        is JsonArray -> LatLon(element[0].jsonPrimitive.double, element[1].jsonPrimitive.double)
        else -> element.jsonObject.let { LatLon(it.getValue("lat").jsonPrimitive.double, it.getValue("lon").jsonPrimitive.double) }
    }

    private fun assertLeg(expected: JsonElement?, actual: LzLeg?, label: String) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual, label)
            return
        }
        val e = expected.jsonObject
        requireNotNull(actual) { "$label: no leg" }
        assertEquals(e.getValue("routeId").jsonPrimitive.contentOrNull, actual.routeId, "$label route")
        assertEquals(text(e, "routeName"), actual.routeName, "$label name")
        assertEquals(text(e, "from"), actual.from, "$label from")
        assertEquals(text(e, "to"), actual.to, "$label to")
        assertEquals(e.getValue("courseDeg").jsonPrimitive.double, actual.courseDeg, 1e-8, "$label course")
        assertEquals(text(e, "heading"), actual.heading, "$label heading")
        assertEquals(e.getValue("metres").jsonPrimitive.double, actual.metres, 1e-3, "$label metres")
    }

    @Test
    fun `the legs into and out of an LZ stand in for its doghouses, and give the slope its headings`() {
        for (case in cases("lz")) {
            val name = text(case, "name")
            val legs = LzLegs.of(target(case["target"]), routes(case.getValue("routes")), today)
            val expected = case.getValue("legs").jsonObject
            assertLeg(expected["landing"], legs.landing, "$name landing")
            assertLeg(expected["takeoff"], legs.takeoff, "$name takeoff")
            assertEquals(case.getValue("judged").jsonObject, legs.judged(case.getValue("flightData").jsonObject), "$name judged")
            val shown = legs.doghousesBeside(case.getValue("doghouses").jsonArray).map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(case.getValue("shownDoghouses").jsonArray.map { it.jsonPrimitive.content }, shown, "$name shown")
            assertEquals(case.getValue("words").jsonPrimitive.contentOrNull, legs.landing?.words, "$name words")
        }
    }
}
