package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteHitTestTest {
    private val here = LatLon(34.78, -84.08)

    /** Zoom 18 is about a quarter of a metre a pixel here, so 10 m is about 40 pixels. */
    private fun view(zoom: Double = 18.0, bearing: Double = 0.0) = MapProjection(CameraState(here, zoom, bearing), 1000.0, 2000.0)

    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    private fun pin(id: String?, at: LatLon, amps: Boolean = true) = RoutePin(id, at, name = "", amps = amps, selected = false)

    private fun route(id: String, selected: Boolean, vararg pins: RoutePin) =
        RouteLine(id, "R$id", "#FF453A", pins.map { it.at }, pins.toList(), selected)

    private fun pick(scene: RouteScene, tap: LatLon, touchPx: Double = 24.0, view: MapProjection = view()) = RouteHitTest.pick(scene, view, tap, touchPx)

    @Test
    fun `a tap on a named point picks it and its route`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", here), pin("a2", at(100.0)))))
        assertEquals(RouteHit("a", "a1"), pick(scene, here))
        assertEquals(RouteHit("a", "a2"), pick(scene, at(100.0, 2.0)))
    }

    @Test
    fun `the nearest point wins`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", at(0.0, -2.0)), pin("a2", at(0.0, 2.0)))))        // 8 px either side of the middle
        assertEquals(RouteHit("a", "a2"), pick(scene, at(0.0, 1.0)))
        assertEquals(RouteHit("a", "a1"), pick(scene, at(0.0, -1.0)))
    }

    @Test
    fun `a shaping point can be picked only on the route being worked on`() {
        val shaping = pin("s", here, amps = false)
        val other = route("o", false, pin("o1", at(100.0)), shaping, pin("o2", at(200.0)))
        assertEquals(RouteHit("o", null), pick(RouteScene(listOf(other)), here))                                     // not drawn, so not a point: but the line is
        val chosen = route("o", true, pin("o1", at(100.0)), shaping, pin("o2", at(200.0)))
        assertEquals(RouteHit("o", "s"), pick(RouteScene(listOf(chosen)), here))
    }

    @Test
    fun `a named point beats a shaping point at the same place`() {
        val chosen = route("o", true, pin("shape", here, amps = false), pin("named", here))
        assertEquals(RouteHit("o", "named"), pick(RouteScene(listOf(chosen)), here))
        val reversed = route("o", true, pin("named", here), pin("shape", here, amps = false))
        assertEquals(RouteHit("o", "named"), pick(RouteScene(listOf(reversed)), here))
    }

    @Test
    fun `a tap on the line between two points picks the route and no point`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", at(-100.0)), pin("a2", at(100.0)))))
        assertEquals(RouteHit("a", null), pick(scene, here))                                                          // the middle of the leg, 400 px from either end
        assertEquals(RouteHit("a", null), pick(scene, at(0.0, 3.0)))                                                  // 12 px off the line: within the finger
        assertNull(pick(scene, at(0.0, 10.0)))                                                                        // 40 px off: not
    }

    @Test
    fun `a point wins over the line it is on, and the nearest line wins over a farther one`() {
        val scene = RouteScene(listOf(
            route("far", false, pin("f1", at(-100.0, 5.0)), pin("f2", at(100.0, 5.0))),
            route("near", false, pin("n1", at(-100.0, 1.0)), pin("n2", at(100.0, 1.0))),
        ))
        assertEquals(RouteHit("near", null), pick(scene, here))
        val withPoint = RouteScene(listOf(route("a", false, pin("a1", here), pin("a2", at(100.0)))))
        assertEquals(RouteHit("a", "a1"), pick(withPoint, at(0.5)))
    }

    @Test
    fun `a tap beyond the finger's reach picks nothing, and an empty scene picks nothing`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", at(0.0, 20.0)), pin("a2", at(100.0, 20.0)))))
        assertNull(pick(scene, here))
        assertNull(pick(RouteScene.EMPTY, here))
    }

    @Test
    fun `a point with no id picks its route but cannot be held`() {
        val scene = RouteScene(listOf(route("a", false, pin(null, here), pin(null, at(100.0)))))
        assertEquals(RouteHit("a", null), pick(scene, here))
    }

    @Test
    fun `a route of one point has no line to hit`() {
        val lone = RouteScene(listOf(RouteLine("l", "L", "#FF453A", listOf(here), listOf(pin("l1", here)), false)))
        assertEquals(RouteHit("l", "l1"), pick(lone, here))
        assertNull(pick(lone, at(0.0, 20.0)))
    }

    @Test
    fun `zoomed out the reach is the same number of pixels, so it covers more ground`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", here), pin("a2", at(5000.0)))))
        val far = view(zoom = 14.0)                                                                                   // about 3.9 m a pixel
        assertEquals(RouteHit("a", "a1"), pick(scene, at(0.0, 20 * 3.9), view = far))
        assertNull(pick(scene, at(0.0, 40 * 3.9), view = far))
    }

    @Test
    fun `a tap past the end of a leg is not on it, however close it is to the line the leg lies along`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", at(0.0)), pin("a2", at(100.0)))))
        assertNull(pick(scene, at(100.0 + 30 * 0.25, 0.5)))                              // 30 px beyond the far end, 2 px beside the line it is on
        assertNull(pick(scene, at(-30 * 0.25, 0.5)))                                     // and beyond the near end
        assertEquals(RouteHit("a", "a2"), pick(scene, at(100.0 + 10 * 0.25, 0.5)))       // 10 px beyond is within the finger's reach of the end point
    }
}
