package app.ezpztac.map

import app.ezpztac.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLabelsTest {
    private val here = LatLon(34.78, -84.08)
    private fun view(zoom: Double = 18.0, inset: Double = 0.0) = MapProjection(CameraState(here, zoom), 1000.0, 2000.0, 1.0, inset)

    /** Two points [metres] apart east to west, centred on [at], and the line between them. */
    private fun line(metres: Double, at: LatLon = here, gapFt: Double = 120.0, violating: Boolean = false): SceneSeparation {
        val half = Math.toDegrees(metres / 2 / 6_378_137.0 / Math.cos(Math.toRadians(at.lat)))
        return SceneSeparation(LatLon(at.lat, at.lon - half), LatLon(at.lat, at.lon + half), gapFt, violating)
    }

    private fun scene(vararg lines: SceneSeparation) = GraphicsScene(separations = lines.toList())

    @Test
    fun `a separation says its feet at the middle of its line`() {
        val labels = MapLabels.separations(scene(line(80.0, gapFt = 119.6)), view())
        val label = labels.single()
        assertEquals("120 ft", label.text)
        assertEquals(500.0, label.at.x, 0.5)
        assertEquals(1000.0, label.at.y, 0.5)
        assertFalse(label.violating)
        assertEquals("Rotor edges 120 ft apart", label.description)
    }

    @Test
    fun `one that is too close says so in words as well as colour`() {
        val label = MapLabels.separations(scene(line(80.0, gapFt = 12.0, violating = true)), view()).single()
        assertTrue(label.violating)
        assertEquals("Rotor edges 12 ft apart: too close", label.description)
    }

    @Test
    fun `a gap below zero reads as zero feet`() {
        assertEquals("0 ft", MapLabels.separations(scene(line(80.0, gapFt = -14.0, violating = true)), view()).single().text)
    }

    @Test
    fun `a line too short on the screen to hold its words has no label`() {
        val zoomedOut = view(zoom = 14.0)                                                    // 80 m is a few pixels here
        assertTrue(MapLabels.separations(scene(line(80.0)), zoomedOut).isEmpty())
        assertEquals(1, MapLabels.separations(scene(line(80.0)), view(zoom = 18.0)).size)
    }

    @Test
    fun `a line off the screen has no label, and one just off it is kept`() {
        val far = LatLon(here.lat + 0.05, here.lon)                                           // kilometres north: off the top
        assertTrue(MapLabels.separations(scene(line(80.0, at = far)), view()).isEmpty())
        assertEquals(1, MapLabels.separations(scene(line(80.0)), view()).size)
    }

    /** A line whose middle is at ([x], [y]) on the screen of [view]. */
    private fun lineAt(view: MapProjection, x: Double, y: Double) = line(80.0, at = view.toLatLon(x, y))

    @Test
    fun `a label just off any edge is kept, so one sliding in is not missing for a frame, and one well off is not`() {
        val v = view()                                                                        // 1000 by 2000 pixels
        for ((x, y) in listOf(-30.0 to 800.0, 1030.0 to 800.0, 500.0 to -30.0, 500.0 to 2030.0)) {
            assertEquals("kept at ($x, $y)", 1, MapLabels.separations(scene(lineAt(v, x, y)), v).size)
        }
        for ((x, y) in listOf(-70.0 to 800.0, 1070.0 to 800.0, 500.0 to -70.0, 500.0 to 2070.0)) {
            assertTrue("dropped at ($x, $y)", MapLabels.separations(scene(lineAt(v, x, y)), v).isEmpty())
        }
    }

    @Test
    fun `labels follow the camera`() {
        val moved = MapProjection(CameraState(LatLon(here.lat, here.lon - 0.0002), 18.0), 1000.0, 2000.0)
        val label = MapLabels.separations(scene(line(80.0)), moved).single()
        assertTrue("the camera moved west, so the line is east of the middle", label.at.x > 500.0)
    }

    @Test
    fun `labels are placed for the view that is above the sheet`() {
        val label = MapLabels.separations(scene(line(80.0)), view(inset = 400.0)).single()
        assertEquals(800.0, label.at.y, 0.5)                                                  // (2000 - 400) / 2
    }

    @Test
    fun `there is nothing to say with no pairs`() {
        assertTrue(MapLabels.separations(GraphicsScene.EMPTY, view()).isEmpty())
    }
}
