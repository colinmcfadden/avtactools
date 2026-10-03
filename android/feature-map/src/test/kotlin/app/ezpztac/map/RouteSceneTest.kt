package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSceneTest {
    private fun amps(id: String, lat: Double, lon: Double, name: String) = RoutePoint(id = id, lat = lat, lon = lon, kind = RoutePoint.KIND_AMPS, ptType = "turn", name = name)
    private fun shaping(id: String, lat: Double, lon: Double) = RoutePoint(id = id, lat = lat, lon = lon, kind = RoutePoint.KIND_SHAPING, name = "")

    private fun route(id: String, color: String, visible: Boolean = true, vararg points: RoutePoint) =
        SketchRoute(id = id, name = "R-$id", color = color, visible = visible, points = points.toList())

    private val a = route("a", "#FF453A", true, amps("a1", 34.0, -84.0, ".SP"), shaping("a2", 34.1, -84.1), amps("a3", 34.2, -84.2, ".TGT"))
    private val b = route("b", "#0A84FF", true, amps("b1", 35.0, -85.0, ".SP"), amps("b2", 35.1, -85.1, ".TGT"))
    private val hidden = route("h", "#32D74B", false, amps("h1", 36.0, -86.0, ".SP"), amps("h2", 36.1, -86.1, ".TGT"))

    private fun set(vararg routes: SketchRoute) = RouteSet(id = "s", name = "SET", routes = routes.toList())
    private fun features(scene: RouteScene) = Json.parseToJsonElement(scene.geoJson()).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
    private fun role(f: JsonObject) = f.getValue("properties").jsonObject.getValue("role").jsonPrimitive.content
    private fun prop(f: JsonObject, key: String) = f.getValue("properties").jsonObject.getValue(key).jsonPrimitive

    @Test
    fun `nothing open draws nothing`() {
        assertTrue(RouteScene.of(null).isEmpty)
        assertEquals(emptyList<JsonObject>(), features(RouteScene.EMPTY))
        assertTrue(RouteScene.of(set()).isEmpty)
    }

    @Test
    fun `a route is one line through every point, longitude first, in the route's colour`() {
        val scene = RouteScene.of(set(a))
        val line = features(scene).single { role(it) == "route" }
        assertEquals("#FF453A", prop(line, "color").content)
        val coordinates = line.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(3, coordinates.size)                                                     // shaping points bend the line, so they are in it
        assertEquals(-84.1, coordinates[1].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(34.1, coordinates[1].jsonArray[1].jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `a hidden route is not drawn, and the others still are`() {
        val scene = RouteScene.of(set(a, hidden, b))
        assertEquals(listOf("a", "b"), scene.routes.map { it.id })
        assertEquals(2, features(scene).count { role(it) == "route" })
    }

    @Test
    fun `named points are dots on every route, shaping points only on the route being worked on`() {
        val none = features(RouteScene.of(set(a, b)))
        assertEquals(4, none.count { role(it) == "pin" })
        assertEquals(0, none.count { role(it) == "shape" })

        val chosen = features(RouteScene.of(set(a, b), selectedRouteId = "a"))
        assertEquals(4, chosen.count { role(it) == "pin" })
        assertEquals(1, chosen.count { role(it) == "shape" })
        assertTrue(prop(chosen.single { role(it) == "route" && prop(it, "color").content == "#FF453A" }, "selected").boolean)
        assertFalse(prop(chosen.single { role(it) == "route" && prop(it, "color").content == "#0A84FF" }, "selected").boolean)
    }

    @Test
    fun `the held point is marked only on the route it belongs to`() {
        val scene = RouteScene.of(set(a, b), selectedRouteId = "a", selectedPointId = "a3")
        assertEquals(listOf("a3"), scene.routes.flatMap { it.pins }.filter { it.selected }.map { it.id })
        val other = RouteScene.of(set(a, b), selectedRouteId = "b", selectedPointId = "a3")        // a3 is not on b
        assertTrue(other.routes.flatMap { it.pins }.none { it.selected })
        val noRoute = RouteScene.of(set(a, b), selectedRouteId = null, selectedPointId = "a3")
        assertTrue(noRoute.routes.flatMap { it.pins }.none { it.selected })
    }

    @Test
    fun `a route being drawn is a dashed line with a dot at every point`() {
        val draft = listOf(LatLon(34.0, -84.0), LatLon(34.1, -84.1), LatLon(34.2, -84.2))
        val scene = RouteScene.of(null, draft = draft)
        assertFalse(scene.isEmpty)
        val f = features(scene)
        assertEquals(1, f.count { role(it) == "draft" })
        assertEquals(3, f.count { role(it) == "draft-vertex" })
        assertTrue(features(RouteScene(draft = listOf(LatLon(1.0, 2.0)))).none { role(it) == "draft" })     // one point is a dot, not a line
        assertEquals(1, features(RouteScene(draft = listOf(LatLon(1.0, 2.0)))).count { role(it) == "draft-vertex" })
    }

    @Test
    fun `a point with no position is left off the map and the rest is drawn`() {
        val odd = route("o", "#FFD60A", true, amps("o1", 34.0, -84.0, ".SP"), amps("o2", Double.NaN, -84.0, ".X"), amps("o3", 34.2, -84.2, ".TGT"))
        val scene = RouteScene.of(set(odd))
        assertEquals(2, scene.routes.single().pins.size)
        assertEquals(2, scene.routes.single().line.size)
    }

    @Test
    fun `labels are the named points of every drawn route`() {
        val scene = RouteScene.of(set(a, hidden, b))
        assertEquals(listOf(".SP", ".TGT", ".SP", ".TGT"), scene.labelled.map { it.second.name })
        assertEquals(listOf("a", "a", "b", "b"), scene.labelled.map { it.first.id })
    }

    @Test
    fun `a point's name that is missing reads as empty`() {
        val unnamed = route("u", "#FFD60A", true, RoutePoint(id = "u1", lat = 1.0, lon = 2.0, kind = RoutePoint.KIND_AMPS), RoutePoint(id = "u2", lat = 1.1, lon = 2.1, kind = RoutePoint.KIND_AMPS))
        assertEquals(listOf("", ""), RouteScene.of(set(unnamed)).routes.single().pins.map { it.name })
    }

    @Test
    fun `a route of one point is a dot and no line`() {
        val lone = route("l", "#FFD60A", true, amps("l1", 34.0, -84.0, ".SP"))
        val f = features(RouteScene.of(set(lone)))
        assertEquals(0, f.count { role(it) == "route" })
        assertEquals(1, f.count { role(it) == "pin" })
    }

    @Test
    fun `a point is placed longitude first, like the line through it`() {
        val pin = features(RouteScene.of(set(b))).first { role(it) == "pin" }
        val at = pin.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(-85.0, at[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(35.0, at[1].jsonPrimitive.content.toDouble(), 0.0)
        val vertex = features(RouteScene(draft = listOf(LatLon(1.5, 2.5)))).single { role(it) == "draft-vertex" }
        val v = vertex.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(2.5, v[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1.5, v[1].jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `a point with no id is never the held one, even when no point is held`() {
        val nameless = route("n", "#FFD60A", true, RoutePoint(lat = 1.0, lon = 2.0, kind = RoutePoint.KIND_AMPS, name = "A"), RoutePoint(lat = 1.1, lon = 2.1, kind = RoutePoint.KIND_AMPS, name = "B"))
        val scene = RouteScene.of(set(nameless), selectedRouteId = "n", selectedPointId = null)
        assertTrue(scene.routes.single().pins.none { it.selected })
    }
}
