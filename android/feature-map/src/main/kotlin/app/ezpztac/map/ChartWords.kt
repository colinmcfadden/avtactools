package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.planning.RouteCalc
import java.time.Instant
import kotlin.math.max

/** A line of what the map says of its charts; [expired] is drawn in the error colour. */
data class ChartLine(val text: String, val expired: Boolean)

/** What the map says of the charts under it: [lines], naming what is in view with its edition, and [enlarged], the quiet line about the chart at the crosshair. */
data class ChartLines(val lines: List<ChartLine>, val enlarged: String?)

/**
 * The web's `chartWords` (`frontend/src/feature/vfrCharts/vfrCharts.js`), function by function, held to the same `contracts/fixtures/charts/words.json`
 * over the same `coverage.json`. The zoom it takes is the web's (Leaflet's): MapLibre's plus one.
 */
object ChartWords {
    /** What the layers from ArcGIS's service are called: it publishes no edition. */
    const val ARCGIS = "FAA, via ArcGIS"

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val NAMED = 2         // charts of one edition named before "and n more"
    private const val SAMPLES = 5       // points a side of the view tried against a chart's outline
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun of(live: VfrLive?, coverage: ChartCoverage?, view: ChartBox, center: LatLon, zoom: Double, now: Long): ChartLines {
        if (live == null) return ChartLines(listOf(ChartLine(ARCGIS, false)), null)
        val views = viewsOf(view)
        val lines = if (coverage != null) linesOf(chartsInView(coverage, views, levelAt(zoom, coverage)), now) else listOf(buildLine(live, now))
        val overArcgis = live.fallback.any { box -> views.any { it.west < box.east && box.west < it.east && it.south < box.north && box.south < it.north } }
        return ChartLines(if (overArcgis) lines + ChartLine(ARCGIS, false) else lines, coverage?.let { enlargedWords(it, center, zoom) })
    }

    /** The next instant a chart's edition runs out, after [now]; null when none will. */
    fun nextExpiry(live: VfrLive?, coverage: ChartCoverage?, now: Long): Long? =
        (listOfNotNull(live?.expires?.let(::chartInstant)) + coverage?.charts.orEmpty().map { it.edition.expiresAt }).filter { it > now }.minOrNull()

    private fun day(text: String, withYear: Boolean = true): String {
        val (year, month, date) = text.split("-")
        return "${date.toInt()} ${MONTHS[month.toInt() - 1]}" + if (withYear) " $year" else ""
    }

    private fun span(edition: ChartEdition) = "${day(edition.start, edition.start.take(4) != edition.end.take(4))} – ${day(edition.end)}"

    private fun insideRing(ring: List<ChartPoint>, lon: Double, lat: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > lat) != (b.lat > lat) && lon < ((b.lon - a.lon) * (lat - a.lat)) / (b.lat - a.lat) + a.lon) inside = !inside
            j = i
        }
        return inside
    }

    private fun wrapped(lon: Double) = (((lon + 180) % 360 + 360) % 360) - 180

    // The view with its west within ±180°, and once more a whole turn west where it crosses the date line: a map panned round
    // the world gives longitudes past 180°, and the charts' never are.
    private fun viewsOf(view: ChartBox): List<ChartBox> {
        val shift = wrapped(view.west) - view.west
        val shifted = view.copy(west = view.west + shift, east = view.east + shift)
        return if (shifted.east > 180) listOf(shifted, shifted.copy(west = shifted.west - 360, east = shifted.east - 360)) else listOf(shifted)
    }

    private fun samplesOf(view: ChartBox): List<ChartPoint> = buildList {
        for (i in 0 until SAMPLES) {
            for (j in 0 until SAMPLES) {
                add(ChartPoint(view.west + (view.east - view.west) * i / (SAMPLES - 1), view.south + (view.north - view.south) * j / (SAMPLES - 1)))
            }
        }
    }

    private fun touches(ring: List<ChartPoint>, view: ChartBox) =
        ring.any { it.lon >= view.west && it.lon <= view.east && it.lat >= view.south && it.lat <= view.north } ||
            samplesOf(view).any { insideRing(ring, it.lon, it.lat) }

    private fun covers(ring: List<ChartPoint>, view: ChartBox) = samplesOf(view).all { insideRing(ring, it.lon, it.lat) }

    private fun finestFirst(charts: List<CoverageChart>) = charts.sortedBy { it.scale }

    // The level whose tiles the map is drawn from: the web's map rounds its zoom, and shrinks the first level's tiles below it.
    private fun levelAt(zoom: Double, coverage: ChartCoverage) = max(RouteCalc.jsRound(zoom).toInt() - 1, coverage.minLevel)

    // The charts drawn in the view, finest first: those shown at this level whose outlines reach it, less any wholly under a finer one.
    private fun chartsInView(coverage: ChartCoverage, views: List<ChartBox>, level: Int): List<CoverageChart> {
        val reach = finestFirst(coverage.charts.filter { it.shownFrom <= level }).filter { chart -> views.any { touches(chart.ring, it) } }
        return reach.filter { chart -> reach.none { finer -> finer.scale < chart.scale && views.any { covers(finer.ring, it) } } }
    }

    private fun lineOf(titles: List<String>, edition: ChartEdition, now: Long): ChartLine {
        val more = titles.size - NAMED
        val names = titles.take(NAMED).joinToString(", ") + if (more > 0) " and $more more" else ""
        val expired = now >= edition.expiresAt
        return ChartLine(if (expired) "FAA $names · Expired ${day(edition.end)} — not current" else "FAA $names, ${span(edition)}", expired)
    }

    // One line for each edition in view (a chart held back keeps its own), so a set of charts of one cycle is one line.
    private fun linesOf(charts: List<CoverageChart>, now: Long): List<ChartLine> {
        val lines = charts.groupBy { it.edition.start to it.edition.expires }.values.map { group -> lineOf(group.map { it.title }, group.first().edition, now) }
        return lines.filter { it.expired } + lines.filterNot { it.expired }
    }

    // Before coverage.json has come, the build's own edition: its expiry instant is 0901Z the day after its printed end date.
    private fun buildLine(live: VfrLive, now: Long): ChartLine {
        val expiresAt = chartInstant(live.expires) ?: return ChartLine("FAA VFR charts", false)
        val end = Instant.ofEpochMilli(expiresAt - DAY_MS).toString().take(10)
        return lineOf(listOf("VFR charts"), ChartEdition(live.effective.take(10), end, live.expires, expiresAt), now)
    }

    private fun tierWords(tier: String) = when (tier) {
        "sectional" -> "Sectional"
        "tac" -> "TAC"
        "helicopter" -> "Helicopter chart"
        "inset" -> "Inset"
        else -> "Chart"
    }

    // Past the chart's own detail, by the whole power of two nearest: a line that changed with every tenth of a zoom would not be quiet.
    private fun enlargedWords(coverage: ChartCoverage, center: LatLon, zoom: Double): String? {
        val level = levelAt(zoom, coverage)
        val lon = wrapped(center.lon)
        val chart = finestFirst(coverage.charts).firstOrNull { it.shownFrom <= level && insideRing(it.ring, lon, center.lat) } ?: return null
        val power = RouteCalc.jsRound(zoom - 1 - chart.nativeLevel).toInt()
        if (power < 1) return null
        return "${tierWords(chart.tier)} enlarged ${1L shl power}× — no finer chart here"
    }
}
