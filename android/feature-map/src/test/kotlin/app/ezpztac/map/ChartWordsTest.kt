package app.ezpztac.map

import app.ezpztac.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

/** What `words.json` cannot say, its coverage being one edition and its views all in the lower 48: charts of two editions, and a map panned past the date line. */
class ChartWordsTest {
    private val live = VfrLive(
        build = "b", tiles = "", coverage = "", tileSize = 512, minZoom = 5, maxZoom = 14, effective = "2026-10-29T09:01Z", expires = "2026-12-24T09:01Z",
        bounds = ChartBox(-125.0, 24.0, -66.0, 50.0), fallback = emptyList(),
    )

    private fun edition(start: String, end: String, expires: String) = ChartEdition(start, end, expires, requireNotNull(chartInstant(expires)))

    private val current = edition("2026-10-29", "2026-12-23", "2026-12-24T09:01Z")
    private val heldBack = edition("2026-09-03", "2026-10-28", "2026-10-29T09:01Z")
    private val now = requireNotNull(chartInstant("2026-11-08T12:00Z"))

    private fun chart(title: String, scale: Double, edition: ChartEdition, west: Double, south: Double, east: Double, north: Double) = CoverageChart(
        title, "sectional", scale, edition, shownFrom = 5, nativeLevel = 10.58,
        ring = listOf(ChartPoint(west, south), ChartPoint(east, south), ChartPoint(east, north), ChartPoint(west, north), ChartPoint(west, south)),
    )

    private fun coverage(vararg charts: CoverageChart) = ChartCoverage("b", 5, charts.toList())

    @Test
    fun `a chart run out is said first, finer chart or not`() {
        val words = ChartWords.of(
            live, coverage(chart("FINER", 250_000.0, current, -84.0, 33.0, -82.0, 35.0), chart("HELD BACK", 500_000.0, heldBack, -90.0, 30.0, -80.0, 38.0)),
            ChartBox(-85.0, 33.0, -83.0, 35.0), LatLon(34.0, -84.0), zoom = 6.0, now,
        )
        assertEquals(
            listOf(ChartLine("FAA HELD BACK · Expired 28 Oct 2026 — not current", true), ChartLine("FAA FINER, 29 Oct – 23 Dec 2026", false)),
            words.lines,
        )
    }

    @Test
    fun `a view across the date line reaches a chart on its far side`() {
        val words = ChartWords.of(live, coverage(chart("ALEUTIAN", 500_000.0, current, -178.0, 50.0, -170.0, 54.0)), ChartBox(175.0, 51.0, 190.0, 53.0), LatLon(52.0, 179.0), zoom = 6.0, now)
        assertEquals(listOf(ChartLine("FAA ALEUTIAN, 29 Oct – 23 Dec 2026", false)), words.lines)
    }

    @Test
    fun `a map panned a whole turn round the world says what it would have`() {
        val words = ChartWords.of(live, coverage(chart("ATLANTA", 500_000.0, current, -90.0, 30.0, -80.0, 38.0)), ChartBox(276.0, 33.0, 278.0, 35.0), LatLon(34.0, 277.0), zoom = 15.0, now)
        assertEquals(listOf(ChartLine("FAA ATLANTA, 29 Oct – 23 Dec 2026", false)), words.lines)
        assertEquals("Sectional enlarged 8× — no finer chart here", words.enlarged)
    }
}
