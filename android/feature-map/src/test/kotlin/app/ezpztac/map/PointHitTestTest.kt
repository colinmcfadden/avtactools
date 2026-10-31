package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointHitTestTest {
    private val here = LatLon(34.78, -84.08)

    /** Zoom 18 is about a quarter of a metre a pixel here, so 10 m is about 40 pixels. */
    private val view = MapProjection(CameraState(here, 18.0), 1000.0, 2000.0)

    private fun at(north: Double, east: Double = 0.0) = GraphicEdits.offset(here, north, east)

    private fun pin(set: String, id: String, at: LatLon) = PointPin(set, id, at, name = id, color = "#fff", selected = false)

    private fun pick(scene: PointScene, tap: LatLon, touchPx: Double = 24.0) = PointHitTest.pick(scene, view, tap, touchPx)

    @Test
    fun `a tap on a point picks it and its set`() {
        val scene = PointScene(listOf(pin("s1", "a", here), pin("s1", "b", at(100.0))))
        assertEquals(PointHit("s1", "a"), pick(scene, at(0.0, 2.0)))
        assertEquals(PointHit("s1", "b"), pick(scene, at(100.0)))
    }

    @Test
    fun `the nearest wins`() {
        val scene = PointScene(listOf(pin("s1", "a", at(0.0, -2.0)), pin("s1", "b", at(0.0, 2.0))))                  // 8 px either side of the middle
        assertEquals(PointHit("s1", "b"), pick(scene, at(0.0, 1.0)))
        assertEquals(PointHit("s1", "a"), pick(scene, at(0.0, -1.0)))
    }

    @Test
    fun `nothing within the finger's reach is nothing`() {
        val scene = PointScene(listOf(pin("s1", "a", here)))
        assertNull(pick(scene, at(10.0)))                                                                            // about 40 px away
        assertNull(pick(PointScene.EMPTY, here))
    }

    @Test
    fun `the reach is the one given`() {
        val scene = PointScene(listOf(pin("s1", "a", here)))
        assertEquals(PointHit("s1", "a"), pick(scene, at(10.0), touchPx = 60.0))
        assertNull(pick(scene, at(2.0), touchPx = 4.0))                                                              // about 8 px away
    }

    @Test
    fun `of two on one place the later one is drawn over the earlier, so it is the one hit`() {
        val scene = PointScene(listOf(pin("s1", "under", here), pin("s2", "over", here)))
        assertEquals(PointHit("s2", "over"), pick(scene, here))
    }

    @Test
    fun `a point id in two sets names the one that was hit`() {
        val scene = PointScene(listOf(pin("s1", "a", here), pin("s2", "a", at(100.0))))
        assertEquals(PointHit("s2", "a"), pick(scene, at(100.0)))
    }
}
