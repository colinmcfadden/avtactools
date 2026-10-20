package app.ezpztac.scene3d

import app.ezpztac.model.RouteSets
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random

/** The routes as the renderer draws them, Cesium's sphere round them for *Routes*, and the labels' size with distance. */
class RouteGeometryTest {
    private val frame = LocalFrame(34.591552, -84.128225)

    @Test
    fun `Cesium's sphere round points holds them all, the smaller of Ritter's and the box's`() {
        assertNull(BoundingSpheres.fromPoints(emptyList()))
        val two = BoundingSpheres.fromPoints(listOf(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0)))!!
        assertEquals(Vec3(5.0, 0.0, 0.0), two.center)
        assertEquals(5.0, two.radius, 1e-12)
        // A cube's corners: the box's sphere, half its diagonal.
        val corners = (0..7).map { Vec3((it and 1) * 2.0, (it shr 1 and 1) * 2.0, (it shr 2 and 1) * 2.0) }
        val cube = BoundingSpheres.fromPoints(corners)!!
        assertEquals(Vec3(1.0, 1.0, 1.0), cube.center)
        assertEquals(sqrt(3.0), cube.radius, 1e-12)
        val random = Random(7)
        repeat(20) {
            val cloud = List(50) { Vec3(random.nextDouble(-500.0, 500.0), random.nextDouble(-300.0, 900.0), random.nextDouble(0.0, 80.0)) }
            for (sphere in listOf(BoundingSpheres.fromPoints(cloud)!!, BoundingSpheres.fromPoints(cloud, frame)!!)) {
                for (p in cloud) assertTrue((p - sphere.center).length <= sphere.radius + 1e-6, "$p outside $sphere")
            }
        }
    }

    private fun placed(caseName: String, ground: Double = 300.0): List<PlacedRoute> {
        val case = Fixtures.load("viewer3d/routeScene.json").getValue("cases").jsonArray.map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == caseName }
        val set = RouteSets.parse("set", null, "SET", JsonObject(mapOf("version" to JsonPrimitive(1), "routes" to case.getValue("routes"))))
        val prepared = RouteScene3D.prepare(set.routes, LocalDate.of(2026, 10, 20))
        return RouteScene3D.place(prepared, RouteHeights(List(prepared.samples.size) { ground }, List(prepared.samples.size) { -30.35 }))
    }

    @Test
    fun `lines, curtains, drop lines, dots and labels are where the placed routes say, in the scene's frame`() {
        val routes = placed("a route at MSL with a shaping point")
        val g = RouteGeometry.of(routes, frame)
        val route = routes.single()
        assertEquals(route.lines.size, g.lines.size)
        val first = route.lines[0][0]
        assertEquals(frame.fromGeodetic(first.lat, first.lon, first.heightM), g.lines[0].points[0])
        assertArrayEquals(floatArrayOf(0x22 / 255f, 0xc5 / 255f, 0x5e / 255f, 1f), g.lines[0].color)
        // Curtains hang from the line to the ground under it, at a fifth of the colour.
        val wall = route.walls[0]
        assertEquals(frame.fromGeodetic(wall.positions[3].lat, wall.positions[3].lon, 300.0), g.curtains[0].bottoms[3])
        assertEquals(frame.fromGeodetic(wall.positions[3].lat, wall.positions[3].lon, wall.positions[3].heightM), g.curtains[0].tops[3])
        assertEquals(0.2f, g.curtains[0].color[3])
        // Every named point is above the ground here: a drop line each, white dots ringed in the route's colour, a label each.
        assertEquals(route.markers.size, g.drops.size)
        assertEquals(route.markers.map { it.label }, g.labels.map { it.text })
        assertTrue(g.dots.all { it.size == RouteGeometry.DOT })
        assertEquals(g.lines.flatMap { it.points }, g.positions)
    }

    @Test
    fun `a zone on the ground has no drop line, and a target's dot is larger`() {
        val routes = placed("from a pickup zone to a landing zone")
        val g = RouteGeometry.of(routes, frame)
        val markers = routes.single().markers
        val touchdowns = markers.count { m -> m.groundM?.let { m.heightM - it <= 1 } == true }
        assertTrue(touchdowns >= 2)
        assertEquals(markers.size - touchdowns, g.drops.size)
        assertEquals(markers.map { if (it.ptType == "target") 11.0 else 8.0 }, g.dots.map { it.size })
    }

    @Test
    fun `a colour that is not one is the web's own blue`() {
        assertArrayEquals(RouteColors3D.rgba("#38bdf8"), RouteColors3D.rgba("teal"))
        assertArrayEquals(RouteColors3D.rgba("#38bdf8"), RouteColors3D.rgba(null))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0.6f), RouteColors3D.rgba("#FF0000", 0.6))
    }

    @Test
    fun `labels are full size to a kilometre and a half, then smaller to 55 percent at 60 km`() {
        assertEquals(1.0, RouteGeometry.labelScale(0.0))
        assertEquals(1.0, RouteGeometry.labelScale(1_500.0))
        assertEquals(0.775, RouteGeometry.labelScale(30_750.0), 1e-12)
        assertEquals(0.55, RouteGeometry.labelScale(60_000.0), 1e-12)
        assertEquals(0.55, RouteGeometry.labelScale(500_000.0), 1e-12)
    }
}
