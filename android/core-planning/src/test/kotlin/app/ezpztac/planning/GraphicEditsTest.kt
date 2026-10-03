package app.ezpztac.planning

import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class GraphicEditsTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to (if (v is JsonElement) v else if (v == null) JsonNull else if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) })
    private fun JsonObject.d(key: String) = getValue(key).jsonPrimitive.doubleOrNull!!

    private val helo = obj("id" to 1, "lat" to 34.5, "lon" to -84.1, "rotation" to 90, "type" to "helo", "profileId" to "uh60l", "futureField" to "keep me")
    private val pz = obj("id" to "pz-1", "lat" to 34.5, "lon" to -84.1, "tipLat" to 34.5, "tipLon" to -84.102)

    private fun sector(vararg points: Pair<Double, Double>) = obj(
        "id" to "sec-1", "points" to JsonArray(points.map { (a, b) -> obj("lat" to a, "lng" to b) }),
    )

    // -- Distances -------------------------------------------------------------------------------------------------------

    @Test
    fun `an offset of metres is metres on the ground, east shrinking with latitude`() {
        val p = GraphicEdits.offset(LatLon(34.5, -84.1), northM = 100.0, eastM = 0.0)
        assertEquals(34.5 + Math.toDegrees(100.0 / 6_378_137.0), p.lat, 1e-12)
        assertEquals(-84.1, p.lon, 0.0)
        val east = GraphicEdits.offset(LatLon(60.0, 10.0), 0.0, 100.0)
        assertEquals(Math.toDegrees(100.0 / (6_378_137.0 * Math.cos(Math.toRadians(60.0)))), east.lon - 10.0, 1e-12)
        assertTrue(east.lon - 10.0 > Math.toDegrees(100.0 / 6_378_137.0) * 1.9)         // twice as many degrees at 60 degrees north
    }

    @Test
    fun `metres between is the inverse of the offset`() {
        val from = LatLon(34.5, -84.1)
        val (n, e) = GraphicEdits.metresBetween(from, GraphicEdits.offset(from, 37.0, -81.0))
        assertEquals(37.0, n, 1e-6)
        assertEquals(-81.0, e, 1e-6)
    }

    // -- Position --------------------------------------------------------------------------------------------------------

    @Test
    fun `where a graphic is`() {
        assertEquals(LatLon(34.5, -84.1), GraphicEdits.position("helicopters", helo))
        assertEquals(LatLon(34.5, -84.1), GraphicEdits.position("pzMarkers", pz))
        val s = sector(34.501 to -84.1, 34.499 to -84.099, 34.499 to -84.101)
        val middle = GraphicEdits.position("sectorsOfFire", s)!!
        assertEquals((34.501 + 34.499 + 34.499) / 3, middle.lat, 1e-12)
        assertEquals(-84.1, middle.lon, 1e-12)
        assertNull(GraphicEdits.position("helicopters", obj("id" to 1)))
        assertNull(GraphicEdits.position("sectorsOfFire", sector(1.0 to 2.0, 3.0 to 4.0)))
        assertNull(GraphicEdits.position("measurements", helo))
    }

    // -- Moving ----------------------------------------------------------------------------------------------------------

    @Test
    fun `moving an aircraft changes only its position`() {
        val patch = GraphicEdits.moveTo("helicopters", helo, LatLon(34.6, -84.2))!!
        assertEquals(setOf("lat", "lon"), patch.keys)
        assertEquals(34.6, patch.d("lat"), 0.0)
        assertEquals(-84.2, patch.d("lon"), 0.0)
    }

    @Test
    fun `a nudge north is north and a nudge east is east`() {
        val north = GraphicEdits.nudge("helicopters", helo, 25.0, 0.0)!!
        assertTrue(north.d("lat") > 34.5)
        assertEquals(-84.1, north.d("lon"), 1e-12)
        val east = GraphicEdits.nudge("helicopters", helo, 0.0, 25.0)!!
        assertTrue(east.d("lon") > -84.1)
        assertEquals(34.5, east.d("lat"), 1e-12)
        val (n, e) = GraphicEdits.metresBetween(LatLon(34.5, -84.1), LatLon(east.d("lat"), east.d("lon")))
        assertEquals(0.0, n, 1e-6)
        assertEquals(25.0, e, 1e-6)
    }

    @Test
    fun `a PZ marker's tip goes with its anchor, keeping its reach and bearing`() {
        val before = GraphicEdits.pzReachM(pz)!!
        val bearing = GraphicEdits.rotation("pzMarkers", pz)!!
        val moved = GraphicEdits.moveTo("pzMarkers", pz, LatLon(34.51, -84.09))!!
        val after = JsonObject(pz + moved)
        assertEquals(setOf("lat", "lon", "tipLat", "tipLon"), moved.keys)
        // The tip keeps its offset in degrees, as dragging does on the web, so its length in metres moves by the change in the width of a degree (0.01% here).
        assertEquals(before, GraphicEdits.pzReachM(after)!!, 0.1)
        assertEquals(bearing, GraphicEdits.rotation("pzMarkers", after)!!, 0.01)
    }

    @Test
    fun `a PZ marker with no tip moves its tip from where the web draws it`() {
        val noTip = obj("id" to "pz-2", "lat" to 34.5, "lon" to -84.1)
        val moved = GraphicEdits.moveTo("pzMarkers", noTip, LatLon(34.5, -84.0))!!
        assertEquals(-84.001, moved.d("tipLon"), 1e-12)                                      // 0.001 west of the new anchor
    }

    @Test
    fun `a sector keeps its shape and its middle goes where it is sent`() {
        val s = sector(34.501 to -84.1, 34.499 to -84.099, 34.499 to -84.101)
        val patch = GraphicEdits.moveTo("sectorsOfFire", s, LatLon(34.6, -84.2))!!
        assertEquals(setOf("points"), patch.keys)
        val after = JsonObject(s + patch)
        val middle = GraphicEdits.position("sectorsOfFire", after)!!
        assertEquals(34.6, middle.lat, 1e-12)
        assertEquals(-84.2, middle.lon, 1e-12)
        val points = patch.getValue("points").jsonArray.map { it.jsonObject }
        assertEquals(0.002, points[0].d("lat") - points[1].d("lat"), 1e-12)                  // the same triangle
    }

    @Test
    fun `nothing without a position can be moved`() {
        assertNull(GraphicEdits.moveTo("helicopters", obj("id" to 1), LatLon(1.0, 2.0)))
        assertNull(GraphicEdits.nudge("helicopters", obj("id" to 1), 1.0, 1.0))
    }

    // -- Turning ---------------------------------------------------------------------------------------------------------

    @Test
    fun `degrees wrap into zero to three hundred and sixty`() {
        assertEquals(350.0, GraphicEdits.normalizeDegrees(-10.0), 1e-9)
        assertEquals(5.0, GraphicEdits.normalizeDegrees(725.0), 1e-9)
        assertEquals(0.0, GraphicEdits.normalizeDegrees(360.0), 1e-9)
        assertEquals(0.0, GraphicEdits.normalizeDegrees(0.0), 0.0)
    }

    @Test
    fun `an aircraft and a go-around are turned by their rotation`() {
        assertEquals(90.0, GraphicEdits.rotation("helicopters", helo)!!, 0.0)
        assertEquals(0.0, GraphicEdits.rotation("helicopters", obj("id" to 1, "lat" to 1, "lon" to 2))!!, 0.0)         // none saved is north
        assertEquals(135.0, GraphicEdits.rotateBy("helicopters", helo, 45.0)!!.d("rotation"), 1e-9)
        assertEquals(350.0, GraphicEdits.rotateBy("helicopters", helo, -100.0)!!.d("rotation"), 1e-9)
        assertEquals(45.0, GraphicEdits.setRotation("goArounds", obj("id" to "g", "lat" to 1, "lon" to 2), 405.0)!!.d("rotation"), 1e-9)
        assertEquals(setOf("rotation"), GraphicEdits.setRotation("helicopters", helo, 10.0)!!.keys)
    }

    private val doghouse = obj("id" to "d-sp1", "role" to "takeoff", "lat" to 34.5, "lon" to -84.103, "id_val" to "[SP1]", "heading" to "090°", "time" to "01+57")

    @Test
    fun `a doghouse is turned by its heading, which it keeps as the web writes it`() {
        assertEquals(90.0, GraphicEdits.rotation("doghouses", doghouse)!!, 0.0)
        assertEquals(0.0, GraphicEdits.rotation("doghouses", obj("id" to "d", "lat" to 1, "lon" to 2))!!, 0.0)          // none saved is north
        assertEquals("350°", GraphicEdits.rotateBy("doghouses", doghouse, -100.0)!!.getValue("heading").jsonPrimitive.content)
        assertEquals("090°", GraphicEdits.setRotation("doghouses", doghouse, 450.0)!!.getValue("heading").jsonPrimitive.content)
        assertEquals("005°", GraphicEdits.setRotation("doghouses", doghouse, 5.0)!!.getValue("heading").jsonPrimitive.content)   // three digits, as "000°"
        assertEquals("000°", GraphicEdits.setRotation("doghouses", doghouse, 359.6)!!.getValue("heading").jsonPrimitive.content)  // to the whole degree, wrapped
        assertEquals("270°", GraphicEdits.setRotation("doghouses", obj("id" to "d", "lat" to 1, "lon" to 2, "heading" to "-90°"), 270.0)!!.getValue("heading").jsonPrimitive.content)
        assertEquals(setOf("heading"), GraphicEdits.setRotation("doghouses", doghouse, 10.0)!!.keys)                   // nothing else is touched
    }

    @Test
    fun `a doghouse is moved like an aircraft`() {
        val moved = GraphicEdits.nudge("doghouses", doghouse, 10.0, 0.0)!!
        assertEquals(setOf("lat", "lon"), moved.keys)
        assertEquals(10.0, GraphicEdits.metresBetween(GraphicEdits.position("doghouses", doghouse)!!, LatLon(moved.d("lat"), moved.d("lon"))).first, 0.01)
        assertEquals(34.5, GraphicEdits.moveTo("doghouses", doghouse, LatLon(34.5, -84.0))!!.d("lat"), 0.0)
    }

    @Test
    fun `a PZ marker's rotation is the bearing of its tip, and turning it swings the tip keeping its reach`() {
        assertEquals(270.0, GraphicEdits.rotation("pzMarkers", pz)!!, 0.01)                   // the tip is due west
        val reach = GraphicEdits.pzReachM(pz)!!
        val turned = JsonObject(pz + GraphicEdits.setRotation("pzMarkers", pz, 0.0)!!)
        assertEquals(0.0, GraphicEdits.rotation("pzMarkers", turned)!!.let { if (it > 180) it - 360 else it }, 0.01)
        assertEquals(reach, GraphicEdits.pzReachM(turned)!!, 1e-6)
        assertEquals(34.5 + Math.toDegrees(reach / 6_378_137.0), turned.d("tipLat"), 1e-9)   // now due north of the anchor
        assertEquals(-84.1, turned.d("tipLon"), 1e-9)
    }

    @Test
    fun `what does not turn has no rotation`() {
        assertNull(GraphicEdits.rotation("sectorsOfFire", sector(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0)))
        assertNull(GraphicEdits.setRotation("sectorsOfFire", sector(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0), 10.0))
        assertNull(GraphicEdits.rotateBy("units", obj("id" to "u", "lat" to 1, "lon" to 2), 10.0))                    // a unit symbol stays upright
        assertNull(GraphicEdits.rotation("somethingElse", obj("id" to "u", "lat" to 1, "lon" to 2)))
    }

    // -- A PZ marker's reach ---------------------------------------------------------------------------------------------

    @Test
    fun `a PZ marker's reach is metres from anchor to tip, and can be set, keeping its bearing`() {
        val reach = GraphicEdits.pzReachM(pz)!!
        assertEquals(Math.toRadians(0.002) * 6_378_137.0 * Math.cos(Math.toRadians(34.5)), reach, 1e-6)
        val longer = JsonObject(pz + GraphicEdits.setPzReach(pz, 300.0)!!)
        assertEquals(300.0, GraphicEdits.pzReachM(longer)!!, 1e-6)
        assertEquals(270.0, GraphicEdits.rotation("pzMarkers", longer)!!, 0.01)
        assertEquals(0.0, GraphicEdits.pzReachM(JsonObject(pz + GraphicEdits.setPzReach(pz, -50.0)!!))!!, 1e-6)          // never negative
    }

    @Test
    fun `a marker whose tip is on its anchor points north when it is given a reach`() {
        val flat = obj("id" to "pz", "lat" to 34.5, "lon" to -84.1, "tipLat" to 34.5, "tipLon" to -84.1)
        val patched = JsonObject(flat + GraphicEdits.setPzReach(flat, 100.0)!!)
        assertEquals(0.0, GraphicEdits.rotation("pzMarkers", patched)!!.let { if (it > 180) it - 360 else it }, 1e-6)
        assertEquals(100.0, GraphicEdits.pzReachM(patched)!!, 1e-6)
    }

    @Test
    fun `the tip can be set to a place, such as the crosshair`() {
        val patch = GraphicEdits.setPzTip(pz, LatLon(34.505, -84.095))!!
        assertEquals(setOf("tipLat", "tipLon"), patch.keys)
        assertEquals(34.505, patch.d("tipLat"), 0.0)
        assertNull(GraphicEdits.setPzTip(obj("id" to "x"), LatLon(1.0, 2.0)))
    }

    @Test
    fun `a go-around goes left or right and nothing else`() {
        assertEquals("left", GraphicEdits.setGoAroundDirection(obj("id" to "g"), "left")!!.getValue("direction").jsonPrimitive.content)
        assertEquals("right", GraphicEdits.setGoAroundDirection(obj("id" to "g"), "right")!!.getValue("direction").jsonPrimitive.content)
        assertNull(GraphicEdits.setGoAroundDirection(obj("id" to "g"), "N"))
        assertTrue(hypot(1.0, 1.0) > 1)
    }
}
