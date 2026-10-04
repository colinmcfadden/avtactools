package app.ezpztac.map

import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a long press picks up: the same things, in the same order, as a tap holds, but only where there is a point to move. */
class DragHitTestTest {
    private val here = LatLon(34.78, -84.08)
    private val view = MapProjection(CameraState(here, 18.0, 0.0), 1000.0, 2000.0)

    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    private fun heli(id: String, at: LatLon) =
        SceneAircraft(GraphicRef("helicopters", id), at, 0.0, 16.4, 15.0, "uh60", "UH-60L", false, false)

    private fun pin(id: String?, at: LatLon, amps: Boolean = true) = RoutePin(id, at, name = "", amps = amps, selected = false)

    private fun route(id: String, selected: Boolean, vararg pins: RoutePin) = RouteLine(id, "R$id", "#FF453A", pins.map { it.at }, pins.toList(), selected)

    private fun pick(
        graphics: GraphicsScene = GraphicsScene(),
        threats: ThreatScene = ThreatScene(),
        routes: RouteScene = RouteScene(emptyList()),
        at: LatLon = here,
    ) = DragHitTest.pick(graphics, threats, routes, view, at, 24.0)

    @Test
    fun `a graphic is picked up, as a tap would hold it`() {
        assertEquals(DragTarget.Graphic(GraphicRef("helicopters", "1")), pick(graphics = GraphicsScene(aircraft = listOf(heli("1", here)))))
    }

    @Test
    fun `a threat is picked up`() {
        val threats = ThreatScene(pins = listOf(ThreatPin("t1", "SA-6", "SHGPEWRR------", here, false)))
        assertEquals(DragTarget.Threat("t1"), pick(threats = threats))
    }

    @Test
    fun `a point of a route is picked up, named or shaping on the route being worked on`() {
        val scene = RouteScene(listOf(route("a", true, pin("a1", here), pin("shape", at(0.0, 3.0), amps = false), pin("a2", at(100.0)))))
        assertEquals(DragTarget.RoutePoint("a", "a1"), pick(routes = scene))
        assertEquals(DragTarget.RoutePoint("a", "shape"), pick(routes = scene, at = at(0.0, 3.0)))
    }

    @Test
    fun `a press on the line between two points picks nothing up, so the map pans`() {
        val scene = RouteScene(listOf(route("a", false, pin("a1", at(-100.0)), pin("a2", at(100.0)))))
        assertNull(pick(routes = scene))
    }

    @Test
    fun `nothing there picks nothing up`() {
        assertNull(pick())
        assertNull(pick(routes = RouteScene(listOf(route("a", false, pin("a1", at(500.0)), pin("a2", at(600.0))))), at = at(-300.0)))
    }

    @Test
    fun `a graphic is picked over a threat, and a threat over a route, as a tap does`() {
        val graphics = GraphicsScene(aircraft = listOf(heli("1", here)))
        val threats = ThreatScene(pins = listOf(ThreatPin("t1", "SA-6", "SHGPEWRR------", here, false)))
        val routes = RouteScene(listOf(route("a", false, pin("a1", here), pin("a2", at(100.0)))))
        assertEquals(DragTarget.Graphic(GraphicRef("helicopters", "1")), pick(graphics, threats, routes))
        assertEquals(DragTarget.Threat("t1"), pick(threats = threats, routes = routes))
        assertEquals(DragTarget.RoutePoint("a", "a1"), pick(routes = routes))
    }

    @Test
    fun `a point with no id cannot be dragged, since nothing could say which it was`() {
        val scene = RouteScene(listOf(route("a", false, pin(null, here), pin(null, at(100.0)))))
        assertNull(pick(routes = scene))
    }
}
