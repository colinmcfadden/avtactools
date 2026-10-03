package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names that go beside the points on the map, worked out for a camera. */
class PinLabelsTest {
    private val here = LatLon(34.78, -84.08)

    private fun view(zoom: Double = 16.0) = MapProjection(CameraState(here, zoom), 1000.0, 2000.0)

    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    private fun pin(name: String, at: LatLon, selected: Boolean = false, id: String = name) = PointPin("s", id, at, name, "#fff", selected)

    private fun points(pins: List<PointPin>, zoom: Double = 16.0) = PinLabels.points(PointScene(pins), view(zoom), zoom, density = 1.0)

    // -- Local points ------------------------------------------------------------------------------------------

    @Test
    fun `a point on the screen is named, beside its dot`() {
        val labels = points(listOf(pin("BLUE 1", here)))
        assertEquals(listOf("BLUE 1"), labels.map { it.text })
        assertEquals(500.0, labels.single().at.x, 0.5)                                         // at the dot; the chip is drawn beside it
        assertFalse(labels.single().emphasised)
        assertEquals("Local point BLUE 1", labels.single().description)
    }

    @Test
    fun `too far out, no point is named, but the one being held still is`() {
        val pins = listOf(pin("A", here), pin("B", at(3000.0), selected = true))                    // far enough apart, at this zoom, that their names do not meet
        assertEquals(listOf("B"), points(pins, zoom = PinLabels.MIN_POINT_ZOOM - 1).map { it.text })
        assertEquals(setOf("A", "B"), points(pins, zoom = PinLabels.MIN_POINT_ZOOM).map { it.text }.toSet())
    }

    @Test
    fun `a point off the screen is not named, one just off its edge is kept so it does not pop in`() {
        // At zoom 16 a pixel is about a metre here, and the view is 1000 px wide with the middle at 500.
        val onScreen = pin("ON", at(0.0, 400.0))
        val justOff = pin("JUST OFF", at(0.0, 520.0))                                           // about 530 px from the middle: past the edge by less than the margin
        val gone = pin("GONE", at(0.0, 700.0))
        val far = pin("FAR", at(5000.0))
        val names = points(listOf(onScreen, justOff, gone, far)).map { it.text }
        assertEquals(setOf("ON", "JUST OFF"), names.toSet())
    }

    @Test
    fun `two names that would sit on each other are not both drawn, the one nearer the middle wins`() {
        val near = pin("NEAR", at(1.0))
        val onIt = pin("ON IT", at(2.0))
        assertEquals(listOf("NEAR"), points(listOf(onIt, near)).map { it.text })
    }

    @Test
    fun `names far enough apart are all drawn`() {
        val pins = listOf(pin("ONE", here), pin("TWO", at(50.0)), pin("THREE", at(0.0, 50.0)))
        assertEquals(setOf("ONE", "TWO", "THREE"), points(pins).map { it.text }.toSet())
    }

    @Test
    fun `at most forty are named, and the held one is never left out for the rest`() {
        val grid = (0 until 20).flatMap { r -> (0 until 10).map { c -> pin("P$r-$c", at(r * 30.0 - 300.0, c * 30.0 - 150.0), id = "$r-$c") } }       // 200 points, none on another
        val held = pin("HELD", at(280.0, 140.0), selected = true)
        val labels = points(grid + held)
        assertTrue("at most 41, got ${labels.size}", labels.size <= PinLabels.MAX_POINT_LABELS + 1)
        assertTrue(labels.any { it.text == "HELD" && it.emphasised })
        assertEquals("HELD", labels.first().text)                                               // placed first
    }

    @Test
    fun `a point with no name is not named, unless it is held, and then it says so`() {
        assertTrue(points(listOf(pin("", here))).isEmpty())
        assertEquals(listOf("(unnamed)"), points(listOf(pin("", here, selected = true))).map { it.text })
    }

    @Test
    fun `no points, no names`() {
        assertTrue(points(emptyList()).isEmpty())
    }

    // -- Routes ----------------------------------------------------------------------------------------------------

    private fun route(vararg pins: RoutePin) = RouteScene(listOf(RouteLine("r1", "ROUTE 1", "#FF453A", pins.map { it.at }, pins.toList(), selected = false)))

    @Test
    fun `a route's named points are named, its shaping points are not`() {
        val scene = route(
            RoutePin("p1", here, ".TGT", amps = true, selected = false),
            RoutePin("p2", at(100.0), "", amps = false, selected = false),
            RoutePin("p3", at(200.0), ".RP", amps = true, selected = true),
        )
        val labels = PinLabels.routes(scene, view())
        assertEquals(listOf(".TGT", ".RP"), labels.map { it.text })
        assertEquals(listOf(false, true), labels.map { it.emphasised })
        assertEquals("ROUTE 1: .TGT", labels.first().description)
    }

    @Test
    fun `a named point with a blank name, and one off the screen, are not named`() {
        val scene = route(RoutePin("p1", here, "  ", amps = true, selected = false), RoutePin("p2", at(5000.0), ".FAR", amps = true, selected = false))
        assertTrue(PinLabels.routes(scene, view()).isEmpty())
    }

    @Test
    fun `a route is named at every zoom`() {
        val scene = route(RoutePin("p1", here, ".TGT", amps = true, selected = false))
        assertEquals(1, PinLabels.routes(scene, view(zoom = 8.0)).size)
    }
}
