package app.ezpztac.map

import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.planning.GraphicEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GraphicHitTestTest {
    private val here = LatLon(34.78, -84.08)

    /** Zoom 18 is about a quarter of a metre a pixel here, so 10 m is about 40 pixels. */
    private fun view(zoom: Double = 18.0, bearing: Double = 0.0) = MapProjection(CameraState(here, zoom, bearing), 1000.0, 2000.0)

    private fun north(m: Double, east: Double = 0.0, from: LatLon = here) = GraphicEdits.offset(from, m, east)

    private fun heli(id: String, at: LatLon, diameterM: Double = 16.4) =
        SceneAircraft(GraphicRef("helicopters", id), at, 0.0, diameterM, 15.0, "uh60", "UH-60L", false, false)

    private fun pz(id: String, anchor: LatLon, tip: LatLon) = ScenePz(GraphicRef("pzMarkers", id), anchor, tip, false)

    private fun goAround(id: String, at: LatLon) = SceneGoAround(GraphicRef("goArounds", id), at, 0.0, "left", false)

    private fun sector(id: String, halfSideM: Double, around: LatLon = here) = SceneSector(
        GraphicRef("sectorsOfFire", id),
        listOf(north(halfSideM, -halfSideM, around), north(halfSideM, halfSideM, around), north(-halfSideM, halfSideM, around), north(-halfSideM, -halfSideM, around)),
        false,
    )

    private fun pick(graphics: GraphicsScene, tap: LatLon, view: MapProjection = view(), touchPx: Double = 24.0) = GraphicHitTest.pick(graphics, view, tap, touchPx)

    private fun ref(collection: String, id: String) = GraphicRef(collection, id)

    // -- Aircraft --------------------------------------------------------------------------------------------------

    @Test
    fun `a tap on an aircraft picks it, and anywhere on its rotor disc`() {
        val scene = GraphicsScene(aircraft = listOf(heli("1", here)))
        assertEquals(ref("helicopters", "1"), pick(scene, here))
        assertEquals(ref("helicopters", "1"), pick(scene, north(6.0)))                      // 6 m is inside an 8.2 m rotor radius
        assertEquals(ref("helicopters", "1"), pick(scene, north(0.0, -6.0)))
    }

    @Test
    fun `a tap beyond the disc and the finger's reach picks nothing`() {
        val scene = GraphicsScene(aircraft = listOf(heli("1", here)))
        assertNull(pick(scene, north(25.0)))                                                // far past the rotor (33 px) and the 24 px finger
    }

    @Test
    fun `zoomed far out an aircraft is a dot, and the finger's reach is what it can be hit by`() {
        val scene = GraphicsScene(aircraft = listOf(heli("1", here)))
        val far = view(zoom = 14.0)                                                         // the whole rotor is about two pixels
        val twentyPx = north(20 * 3.9)                                                      // ~3.9 m a pixel at zoom 14
        assertEquals(ref("helicopters", "1"), pick(scene, twentyPx, far))
        assertNull(pick(scene, north(40 * 3.9), far))
    }

    @Test
    fun `where two discs overlap the aircraft whose middle is nearer the finger wins`() {
        val a = heli("a", north(0.0, -6.0))
        val b = heli("b", north(0.0, 6.0))
        val scene = GraphicsScene(aircraft = listOf(a, b))
        assertEquals(ref("helicopters", "a"), pick(scene, north(0.0, -2.0)))
        assertEquals(ref("helicopters", "b"), pick(scene, north(0.0, 2.0)))
        assertEquals(ref("helicopters", "b"), pick(scene, north(1.0, 3.0)))
    }

    // -- PZ markers, go-arounds ------------------------------------------------------------------------------------

    @Test
    fun `a PZ marker is picked along its arrow, not only at its ends`() {
        val scene = GraphicsScene(pzMarkers = listOf(pz("p", north(0.0), north(0.0, 80.0))))
        assertEquals(ref("pzMarkers", "p"), pick(scene, north(0.0, 40.0)))                  // the middle of the arrow
        assertEquals(ref("pzMarkers", "p"), pick(scene, north(3.0, 40.0)))                  // a little off it
        assertEquals(ref("pzMarkers", "p"), pick(scene, north(0.0, 83.0)))                  // just past the tip
        assertNull(pick(scene, north(30.0, 40.0)))                                          // well off to the side
        assertNull(pick(scene, north(0.0, 130.0)))                                          // well past the tip
    }

    @Test
    fun `a PZ marker whose tip is on its anchor is still picked there`() {
        val scene = GraphicsScene(pzMarkers = listOf(pz("p", here, here)))
        assertEquals(ref("pzMarkers", "p"), pick(scene, north(2.0)))
        assertNull(pick(scene, north(40.0)))
    }

    @Test
    fun `a go-around is picked within the finger's reach of its position`() {
        val scene = GraphicsScene(goArounds = listOf(goAround("g", here)))
        assertEquals(ref("goArounds", "g"), pick(scene, north(3.0)))
        assertNull(pick(scene, north(20.0)))
    }

    @Test
    fun `of an aircraft and a PZ marker the one the finger is nearer is picked`() {
        val scene = GraphicsScene(aircraft = listOf(heli("h", here)), pzMarkers = listOf(pz("p", north(0.0, 12.0), north(0.0, 60.0))))
        assertEquals(ref("helicopters", "h"), pick(scene, north(0.0, 3.0)))
        assertEquals(ref("pzMarkers", "p"), pick(scene, north(0.0, 11.0)))
    }

    // -- Sectors ---------------------------------------------------------------------------------------------------

    @Test
    fun `a tap inside a sector picks it, and outside picks nothing`() {
        val scene = GraphicsScene(sectors = listOf(sector("s", 50.0)))
        assertEquals(ref("sectorsOfFire", "s"), pick(scene, north(20.0, -20.0)))
        assertNull(pick(scene, north(80.0)))
    }

    @Test
    fun `a sector does not swallow a tap meant for an aircraft standing in it`() {
        val scene = GraphicsScene(aircraft = listOf(heli("h", north(10.0))), sectors = listOf(sector("s", 50.0)))
        assertEquals(ref("helicopters", "h"), pick(scene, north(12.0)))
        assertEquals(ref("sectorsOfFire", "s"), pick(scene, north(-30.0)))                  // away from the aircraft, still in the sector
    }

    @Test
    fun `where sectors overlap the smaller one is picked`() {
        val scene = GraphicsScene(sectors = listOf(sector("big", 100.0), sector("small", 30.0)))
        assertEquals(ref("sectorsOfFire", "small"), pick(scene, north(5.0)))
        assertEquals(ref("sectorsOfFire", "big"), pick(scene, north(60.0)))
    }

    // -- The camera ------------------------------------------------------------------------------------------------

    @Test
    fun `a turned map picks the same things`() {
        val scene = GraphicsScene(
            aircraft = listOf(heli("h", here)), pzMarkers = listOf(pz("p", north(0.0, 40.0), north(0.0, 120.0))), sectors = listOf(sector("s", 200.0, north(300.0))),
        )
        for (bearing in listOf(0.0, 37.0, 90.0, 180.0, 271.0)) {
            val v = view(bearing = bearing)
            assertEquals("aircraft at $bearing", ref("helicopters", "h"), pick(scene, north(2.0), v))
            assertEquals("PZ at $bearing", ref("pzMarkers", "p"), pick(scene, north(0.0, 100.0), v))
            assertEquals("sector at $bearing", ref("sectorsOfFire", "s"), pick(scene, north(300.0), v))
            assertNull("nothing at $bearing", pick(scene, north(-150.0), v))
        }
    }

    @Test
    fun `with nothing placed nothing is picked`() {
        assertNull(pick(GraphicsScene.EMPTY, here))
    }


    // -- Doghouses -------------------------------------------------------------------------------------------------

    private fun box(id: String, at: LatLon) = SceneDoghouse(GraphicRef("doghouses", id), at, 0.0, "[SP1]", "000", "01", "57", "3.13", "60", false)

    @Test
    fun `a doghouse is a box, so a finger a little further from its middle than a point's reach still holds it`() {
        val scene = GraphicsScene(doghouses = listOf(box("d", here)))
        assertEquals(ref("doghouses", "d"), pick(scene, north(4.0)))                         // 16 px: inside the finger's reach
        assertEquals(ref("doghouses", "d"), pick(scene, north(8.0)))                         // 33 px: past a point's 24 px, inside a box's 36
        assertNull(pick(scene, north(12.0)))                                                // 49 px: out
    }

    @Test
    fun `a doghouse standing in a sector is held ahead of the sector`() {
        val scene = GraphicsScene(doghouses = listOf(box("d", north(10.0))), sectors = listOf(sector("s", 50.0)))
        assertEquals(ref("doghouses", "d"), pick(scene, north(12.0)))
        assertEquals(ref("sectorsOfFire", "s"), pick(scene, north(-30.0)))
    }
}
