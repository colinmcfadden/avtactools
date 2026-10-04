package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreatHitTestTest {
    private val here = LatLon(34.78, -84.08)
    private val view = MapProjection(CameraState(here, 18.0, 0.0), 1000.0, 2000.0)
    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)
    private fun pin(id: String, at: LatLon) = ThreatPin(id, id, "SHGPEWRR------", at, false)

    @Test
    fun `the nearest threat inside the finger is picked`() {
        val scene = ThreatScene(pins = listOf(pin("west", at(0.0, -2.0)), pin("east", at(0.0, 2.0))))
        assertEquals("east", ThreatHitTest.pick(scene, view, at(0.0, 1.0), 24.0))
        assertEquals("west", ThreatHitTest.pick(scene, view, at(0.0, -1.0), 24.0))
        assertNull(ThreatHitTest.pick(scene, view, at(0.0, 20.0), 24.0))
    }

    @Test
    fun `the later marker wins when symbols overlap`() {
        val scene = ThreatScene(pins = listOf(pin("under", here), pin("over", here)))
        assertEquals("over", ThreatHitTest.pick(scene, view, here, 24.0))
    }
}
