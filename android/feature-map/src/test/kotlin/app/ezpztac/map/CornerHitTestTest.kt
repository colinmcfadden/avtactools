package app.ezpztac.map

import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which corner of the boundary a tap holds: the nearest within a finger, in either ring, and only of a polygon. */
class CornerHitTestTest {
    private val here = LatLon(34.78, -84.08)
    private val view = MapProjection(CameraState(here, 18.0, 0.0), 1000.0, 2000.0)
    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)
    private fun scene(boundary: List<LatLon> = emptyList(), drawn: List<LatLon> = emptyList(), held: BoundaryCornerRef? = null) =
        LzScene(null, boundary, drawn, null, heldCorner = held)

    private val triangle = listOf(at(0.0, 0.0), at(10.0, 0.0), at(0.0, 10.0))

    @Test
    fun `the nearest corner within the finger is held in the ring it is in`() {
        assertEquals(BoundaryCornerRef(false, 1), CornerHitTest.pick(scene(boundary = triangle), view, at(10.2, 0.2), 24.0))
        assertEquals(BoundaryCornerRef(true, 2), CornerHitTest.pick(scene(drawn = triangle), view, at(0.2, 9.8), 24.0))
    }

    @Test
    fun `a tap away from every corner, or on a line being drawn, holds nothing`() {
        assertNull(CornerHitTest.pick(scene(boundary = triangle), view, at(5.0, 5.0), 24.0))
        assertNull(CornerHitTest.pick(scene(drawn = triangle.take(2)), view, at(0.0, 0.0), 24.0))
    }

    @Test
    fun `where two corners are in reach the nearer wins`() {
        val close = listOf(at(0.0, 0.0), at(0.0, 2.0), at(30.0, 0.0))
        assertEquals(BoundaryCornerRef(false, 1), CornerHitTest.pick(scene(boundary = close), view, at(0.0, 1.5), 24.0))
        assertEquals(BoundaryCornerRef(false, 0), CornerHitTest.pick(scene(boundary = close), view, at(0.0, 0.5), 24.0))
    }

    @Test
    fun `the scene has a dot at each corner of a polygon and marks the held one`() {
        val json = scene(boundary = triangle, held = BoundaryCornerRef(false, 2)).geoJson()
        assertEquals(3, Regex("\"corner\"").findAll(json).count())
        assertEquals(1, Regex("\"held\":true").findAll(json).count())
        assertEquals(0, Regex("\"corner\"").findAll(scene(drawn = triangle.take(2)).geoJson()).count())
    }
}
