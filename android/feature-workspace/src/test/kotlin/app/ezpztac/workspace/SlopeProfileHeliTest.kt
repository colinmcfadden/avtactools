package app.ezpztac.workspace

import app.ezpztac.planning.SlopeProfile
import app.ezpztac.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** The helicopters over the slope profile: the web's outlines, and where each sits over the ground. */
class SlopeProfileHeliTest {
    private val web = String(Fixtures.repoBytes("frontend/src/feature/terrain/SlopeProfile.jsx"))

    /** The web's outline named [name] (`HELI_SIDE`, `HELI_REAR`): its width, height and paths, read from its source. */
    private fun webArt(name: String): HeliArt {
        val block = Regex("export const $name = \\{(.*?)\\n\\};", RegexOption.DOT_MATCHES_ALL).find(web)?.groupValues?.get(1) ?: error("no $name in the web's file")
        val width = Regex("width: ([0-9.]+)").find(block)!!.groupValues[1].toFloat()
        val height = Regex("height: ([0-9.]+)").find(block)!!.groupValues[1].toFloat()
        val paths = Regex("\"(M[^\"]*)\"").findAll(block).map { it.groupValues[1] }.toList()
        return HeliArt(width, height, paths)
    }

    @Test
    fun `the outlines are the web's, path for path, at the web's scale`() {
        assertEquals(webArt("HELI_SIDE"), HeliOutlines.SIDE)
        assertEquals(webArt("HELI_REAR"), HeliOutlines.REAR)
        val scale = Regex("export const HELI_SCALE = ([0-9.]+);").find(web)!!.groupValues[1].toFloat()
        assertEquals(scale, HeliOutlines.SCALE)
    }

    // A line 600 ft long through the middle, rising 60 ft from behind to ahead, the LZ from -100 to +100 ft; a graph 300 px wide, ground from 24 to 82.
    private val rising = SlopeProfile.Line(
        heading = 90,
        distancesFt = (0..20).map { it * 30.0 - 300 },
        elevationsFt = (0..20).map { 1200.0 + it * 3.0 },
        lz = listOf(-100.0, 100.0),
        changeFt = 20.0, averageDeg = 5.7,
    )
    private val span = 1200.0 to 1260.0

    private fun at(line: SlopeProfile.Line, side: Boolean) =
        heliTopLeft(line, side, span, widthPx = 300f, topPx = 24f, bottomPx = 82f, artWidthPx = 35f, artHeightPx = 20f, clearPx = 2f, insetPx = 2f)

    private fun y(h: Double) = (82 - (h - 1200) / 60 * 58).toFloat()

    @Test
    fun `along the heading it sits on the approach end, just clear of the highest ground there`() {
        val spot = at(rising, side = true)
        assertEquals(2f, spot.x, 0f)
        // The approach is the first 15% of the line: heights 1200 to 1209 ft, the highest at 90 ft along.
        assertEquals(y(1209.0) - 20f - 2f, spot.y, 0.01f)
    }

    @Test
    fun `across the heading it sits over the middle of the lz, clear of the highest ground in it`() {
        val spot = at(rising, side = false)
        assertEquals(150f - 35f / 2, spot.x, 0.01f)                                  // the LZ's middle is the line's
        assertEquals(y(1239.0) - 22f, spot.y, 0.01f)                                // its highest ground: +90 ft along, 1239 ft
        val offCentre = rising.copy(lz = listOf(0.0, 200.0))
        assertEquals(200f - 35f / 2, at(offCentre, side = false).x, 0.01f)
    }

    @Test
    fun `it never leaves the top of the graph`() {
        val falling = rising.copy(elevationsFt = rising.elevationsFt.reversed())          // the approach is the highest ground
        // With the room above the ground this graph has, it sits 2 px from the top over the highest ground...
        assertEquals(2f, at(falling, side = true).y, 0.01f)
        // ...and on a graph with less, at the top rather than above it.
        val tight = heliTopLeft(falling, true, span, widthPx = 300f, topPx = 10f, bottomPx = 82f, artWidthPx = 35f, artHeightPx = 20f, clearPx = 2f, insetPx = 2f)
        assertEquals(0f, tight.y, 0f)
    }

    @Test
    fun `where the ground under it has no height, it keeps clear of the highest it has`() {
        val gap = rising.copy(elevationsFt = rising.elevationsFt.mapIndexed { i, h -> if (i < 4) null else h })
        val spot = at(gap, side = true)
        assertEquals(y(rising.elevationsFt.filterNotNull().drop(4).max()) - 22f, spot.y, 0.01f)
    }
}
