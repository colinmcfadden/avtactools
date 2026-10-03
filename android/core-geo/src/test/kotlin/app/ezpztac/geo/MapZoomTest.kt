package app.ezpztac.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapZoomTest {
    @Test
    fun `a span that fills the view at a zoom comes back as that zoom`() {
        // At zoom 12 and 34 degrees north a point is 78271.5 * cos(34) / 4096 m across; 320 of them is the span that fills the view.
        val metersPerPoint = 78_271.516964 * Math.cos(Math.toRadians(34.0)) / 4096
        assertEquals(12.0, MapZoom.fitting(34.0, metersPerPoint * MapZoom.VIEW_POINTS), 1e-9)
    }

    @Test
    fun `a longer span is a smaller zoom, a halving of the span one whole zoom more`() {
        val wide = MapZoom.fitting(34.0, 40_000.0)
        val half = MapZoom.fitting(34.0, 20_000.0)
        assertEquals(1.0, half - wide, 1e-9)
    }

    @Test
    fun `nearer the pole the same span needs a closer zoom, because a degree is shorter`() {
        assertTrue(MapZoom.fitting(60.0, 10_000.0) < MapZoom.fitting(0.0, 10_000.0))
    }

    @Test
    fun `it stays between the lowest zoom and a diagram's`() {
        assertEquals(MapZoom.MIN, MapZoom.fitting(0.0, 40_000_000.0))
        assertEquals(MapZoom.MAX, MapZoom.fitting(34.0, 5.0))
    }

    @Test
    fun `a span with no length, or not a number, is the closest zoom`() {
        assertEquals(MapZoom.MAX, MapZoom.fitting(34.0, 0.0))
        assertEquals(MapZoom.MAX, MapZoom.fitting(34.0, -3.0))
        assertEquals(MapZoom.MAX, MapZoom.fitting(34.0, Double.NaN))
        assertEquals(MapZoom.MAX, MapZoom.fitting(34.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a latitude at or past the pole does not break it`() {
        assertTrue(MapZoom.fitting(90.0, 10_000.0).isFinite())
        assertTrue(MapZoom.fitting(-90.0, 10_000.0).isFinite())
    }
}
