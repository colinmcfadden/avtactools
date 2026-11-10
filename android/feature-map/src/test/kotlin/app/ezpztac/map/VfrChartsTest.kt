package app.ezpztac.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** What `/api/config` and `coverage.json` are read as. Remembered as text on the device, and a bad box would refuse the whole style: every field is checked. */
class VfrChartsTest {
    private val good = listOf(-88.0, 32.0, -72.0, 40.0)

    private fun live(
        root: String = "https://api.test",
        build: String = "2026-10-29.1",
        tiles: String = "/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp",
        coverage: String = "/charts/vfr/2026-10-29.1/coverage.json",
        tileSize: Int = 512,
        minZoom: Int = 5,
        maxZoom: Int = 14,
        effective: String = "2026-10-29T09:01Z",
        expires: String = "2026-12-24T09:01Z",
        bounds: List<Double> = good,
        fallback: List<List<Double>> = emptyList(),
    ) = VfrLive.of(root, build, tiles, coverage, tileSize, minZoom, maxZoom, effective, expires, bounds, fallback)

    @Test
    fun `its addresses are made whole with the server's root, trailing slash or none`() {
        assertEquals("https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp", live()?.tiles)
        assertEquals("https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp", live(root = "https://api.test/")?.tiles)
        assertEquals("/charts/vfr/2026-10-29.1/coverage.json", live()?.coverage)                 // the app's own client asks for this one
    }

    @Test
    fun `an address that is not on this server, or names no tile, is no build`() {
        assertNull(live(tiles = "https://elsewhere.test/{z}/{x}/{y}.webp"))
        assertNull(live(tiles = "//elsewhere.test/{z}/{x}/{y}.webp"))
        assertNull(live(tiles = "/charts/vfr/a/{z}/{x}.webp"))
        assertNull(live(coverage = "https://elsewhere.test/coverage.json"))
    }

    @Test
    fun `sizes, zooms and dates the server never sends are no build`() {
        assertNull(live(build = " "))
        assertNull(live(tileSize = 0))
        assertNull(live(minZoom = -1))
        assertNull(live(minZoom = 15))                                                            // above the highest
        assertNull(live(maxZoom = 30))
        assertNull(live(expires = "next week"))
        assertNull(live(effective = ""))
    }

    @Test
    fun `a box MapLibre would refuse is no build, and a region of that kind is left out`() {
        assertNull(live(bounds = listOf(-88.0, 32.0, -72.0)))
        assertNull(live(bounds = listOf(-72.0, 32.0, -88.0, 40.0)))                               // west past east
        assertNull(live(bounds = listOf(-88.0, 40.0, -72.0, 32.0)))                               // south past north
        assertNull(live(bounds = listOf(-88.0, -95.0, -72.0, 40.0)))
        assertNull(live(bounds = listOf(-188.0, 32.0, -72.0, 40.0)))
        assertNull(live(bounds = listOf(Double.NaN, 32.0, -72.0, 40.0)))
        assertEquals(listOf(ChartBox(165.9, 12.9, 180.0, 21.9)), live(fallback = listOf(listOf(1.0), listOf(165.9, 12.9, 180.0, 21.9), listOf(Double.POSITIVE_INFINITY, 0.0, 1.0, 1.0)))?.fallback)
    }

    private fun chart(edit: String = "\"start\":\"2026-10-29\",\"end\":\"2026-12-23\",\"expires\":\"2026-12-24T09:01Z\"", ring: String = "[[-88,32],[-72,32],[-72,40],[-88,32]]") =
        """{"title":"Atlanta Sectional","tier":"sectional","scale":500000,"edition":{$edit},"shownFrom":5,"nativeLevel":10.58,"outline":{"type":"Polygon","coordinates":[$ring]}}"""

    @Test
    fun `a coverage is read, and a chart that cannot be is left out of it`() {
        val text = """{"build":"b","minLevel":5,"charts":[${chart()},${chart(edit = "\"start\":\"soon\",\"end\":\"2026-12-23\",\"expires\":\"2026-12-24T09:01Z\"")},${chart(ring = "[[-88,32],\"x\"]")},${chart(ring = "[[-88,32],[-72,32]]")},7]}"""
        val coverage = assertNotNullAnd(ChartCoverage.parse(text))
        assertEquals(1, coverage.charts.size)
        assertEquals(ChartPoint(-72.0, 40.0), coverage.charts.single().ring[2])
        assertEquals(10.58, coverage.charts.single().nativeLevel, 0.0)
    }

    @Test
    fun `what cannot be a coverage is none`() {
        assertNull(ChartCoverage.parse("not json"))
        assertNull(ChartCoverage.parse("[]"))
        assertNull(ChartCoverage.parse("""{"build":"b","charts":[]}"""))
        assertNull(ChartCoverage.parse("""{"build":"b","minLevel":5.5,"charts":[]}"""))
        assertNull(ChartCoverage.parse("""{"build":"b","minLevel":"5","charts":[]}"""))
        assertNull(ChartCoverage.parse("""{"build":"b","minLevel":5,"charts":{}}"""))
        assertNull(ChartCoverage.parse("""{"minLevel":5,"charts":[]}"""))
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
