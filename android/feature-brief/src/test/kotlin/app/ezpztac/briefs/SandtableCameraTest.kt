package app.ezpztac.briefs

import androidx.compose.ui.geometry.Rect
import app.ezpztac.brief.SandtableSteps
import app.ezpztac.brief.SandtableSteps.Bounds
import app.ezpztac.brief.SandtableSteps.Insets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/**
 * Where the map is flown for a step (the web's `flyToBounds` with the insets as padding and `maxZoom: 17`, worked out as Leaflet works it out): the largest zoom, on a
 * tenth of a level, at which the area fits the map less the insets, and the area's middle in the middle of what the insets leave.
 */
class SandtableCameraTest {
    private val opdk = SandtableSteps.boundsOf(SandtableSteps.overviewBounds(BriefFixtures.model))!!
    private val margins = Insets(16.0, 16.0, 16.0, 16.0)

    /** The area's corners on the screen of a map [width] by [height] flown to [view]: its west, north, east and south edges, from the map's top left. */
    private fun onScreen(bounds: Bounds, view: SandtableView, width: Double, height: Double): DoubleArray {
        val (cx, cy) = SandtableCamera.project(view.center.lat, view.center.lon, view.zoom)
        val (wx, ny) = SandtableCamera.project(bounds.north, bounds.west, view.zoom)
        val (ex, sy) = SandtableCamera.project(bounds.south, bounds.east, view.zoom)
        return doubleArrayOf(wx - cx + width / 2, ny - cy + height / 2, ex - cx + width / 2, sy - cy + height / 2)
    }

    private fun fits(bounds: Bounds, zoom: Double, freeX: Double, freeY: Double): Boolean {
        val (wx, ny) = SandtableCamera.project(bounds.north, bounds.west, zoom)
        val (ex, sy) = SandtableCamera.project(bounds.south, bounds.east, zoom)
        return ex - wx <= freeX + 1e-6 && sy - ny <= freeY + 1e-6
    }

    @Test
    fun `fits the area at the largest tenth of a level that holds it`() {
        val view = SandtableCamera.fit(opdk, 1000.0, 700.0, margins)!!
        assertEquals(0.0, abs(view.zoom * 10 - Math.round(view.zoom * 10)), 1e-9)
        assertTrue(fits(opdk, view.zoom, 968.0, 668.0))
        assertTrue("a tenth closer would not hold it", !fits(opdk, view.zoom + 0.1, 968.0, 668.0))
        // OP DK's area is about 0.88° tall and 0.57° wide at 34°N: the height decides, at level 8.78 on 512-point tiles, snapped down to 8.7 (the web's 9.7).
        assertEquals(8.7, view.zoom, 1e-9)
    }

    @Test
    fun `puts the area's middle in the middle of what the insets leave`() {
        val insets = Insets(top = 16.0, right = 298.0, bottom = 416.0, left = 16.0)
        val view = SandtableCamera.fit(opdk, 1140.0, 844.0, insets)!!
        val (w, n, e, s) = onScreen(opdk, view, 1140.0, 844.0).toList()
        val freeMiddleX = (insets.left + 1140.0 - insets.right) / 2
        val freeMiddleY = (insets.top + 844.0 - insets.bottom) / 2
        assertEquals(freeMiddleX, (w + e) / 2, 0.5)
        assertEquals(freeMiddleY, (n + s) / 2, 0.5)
        // And inside it.
        assertTrue(w >= insets.left - 0.5 && e <= 1140.0 - insets.right + 0.5 && n >= insets.top - 0.5 && s <= 844.0 - insets.bottom + 0.5)
    }

    @Test
    fun `is never closer than the web's 17, and an area with no size is shown at that`() {
        val point = Bounds(34.5501, -84.1102, 34.5501, -84.1102)
        val view = SandtableCamera.fit(point, 1000.0, 700.0, margins)!!
        assertEquals(SandtableCamera.MAX_ZOOM, view.zoom, 0.0)
        assertEquals(34.5501, view.center.lat, 1e-9)
        assertEquals(-84.1102, view.center.lon, 1e-9)
        val tiny = Bounds(34.5500, -84.1103, 34.5502, -84.1101)
        assertEquals(16.0, SandtableCamera.fit(tiny, 1000.0, 700.0, margins)!!.zoom, 0.0)
    }

    @Test
    fun `takes the corners in either order`() {
        val swapped = Bounds(opdk.north, opdk.east, opdk.south, opdk.west)
        assertEquals(SandtableCamera.fit(opdk, 1000.0, 700.0, margins), SandtableCamera.fit(swapped, 1000.0, 700.0, margins))
    }

    @Test
    fun `flies nowhere on a map with no room left`() {
        assertNull(SandtableCamera.fit(opdk, 0.0, 0.0, margins))
        assertNull(SandtableCamera.fit(opdk, 100.0, 700.0, Insets(16.0, 60.0, 16.0, 60.0)))
        assertNotNull(SandtableCamera.fit(opdk, 200.0, 200.0, margins))
    }

    @Test
    fun `an area wider than the world is shown far out, its middle wrapped onto the world`() {
        // The web's overview of a brief across the antimeridian, whose figures run on past 180° each way.
        val across = Bounds(51.873, -215.94, 51.957, 215.94)
        val view = SandtableCamera.fit(across, 1000.0, 700.0, margins)!!
        // 431.88° of longitude is 614 points at level 0: 968 of map shows it at level 0.66, snapped down to 0.6.
        assertEquals(0.6, view.zoom, 1e-9)
        assertTrue(view.center.lon >= -180 && view.center.lon < 180)
        val east = Bounds(51.873, 179.5, 51.957, 180.5)
        assertEquals(-180.0, SandtableCamera.fit(east, 1000.0, 700.0, margins)!!.center.lon, 1e-9)
    }

    @Test
    fun `wraps a longitude onto the world`() {
        assertEquals(-170.0, SandtableCamera.wrap(190.0), 1e-9)
        assertEquals(170.0, SandtableCamera.wrap(-190.0), 1e-9)
        assertEquals(-180.0, SandtableCamera.wrap(180.0), 1e-9)
        assertEquals(-180.0, SandtableCamera.wrap(-180.0), 1e-9)
        assertEquals(-84.0, SandtableCamera.wrap(-84.0), 0.0)
        assertEquals(10.0, SandtableCamera.wrap(730.0), 1e-9)
    }

    @Test
    fun `fits clear of the card and the constants box, measured in the window's pixels`() {
        // A tablet at 2x: the map from x 600 to 2560, y 128 to 1600 pixels; the card along its foot, the constants at its top right.
        val density = 2f
        val map = Rect(600f, 128f, 2560f, 1600f)
        val card = Rect(800f, 1000f, 2360f, 1568f)
        val constants = Rect(1996f, 152f, 2536f, 600f)
        val view = SandtableFit.of(opdk, map, listOf(card, constants), density)!!
        val insets = SandtableSteps.fitInsets(
            SandtableSteps.Box(300.0, 64.0, 1280.0, 800.0),
            listOf(SandtableSteps.Box(400.0, 500.0, 1180.0, 784.0), SandtableSteps.Box(998.0, 76.0, 1268.0, 300.0)),
        )
        assertEquals(SandtableCamera.fit(opdk, 980.0, 736.0, insets), view)
        assertEquals(800.0 - 500 + 16, insets.bottom, 0.0)
        assertEquals(floor(view.zoom * 10) / 10, view.zoom, 1e-9)
    }
}
