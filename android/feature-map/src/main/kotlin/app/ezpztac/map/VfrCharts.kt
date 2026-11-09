package app.ezpztac.map

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** `[west, south, east, north]` in degrees, as the config's `bounds` and `fallback` give a box. */
data class ChartBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    companion object {
        /** A box MapLibre can be handed as a source's bounds; null for anything else, which would refuse the whole style. */
        fun of(values: List<Double>?): ChartBox? {
            if (values == null || values.size != 4 || values.any { !it.isFinite() }) return null
            val (west, south, east, north) = values
            val inWorld = west >= -180.0 && east <= 180.0 && south >= -90.0 && north <= 90.0
            return if (inWorld && west < east && south < north) ChartBox(west, south, east, north) else null
        }
    }
}

/**
 * The live build of the FAA's charts, as `/api/config` names it (docs/VFR_CHARTS.md §4.5, §8): the web's `liveBuildOf`. A value the server
 * has never sent is no build, and ArcGIS draws the VFR map: it was remembered as text, and a bad box or zoom range would refuse the whole
 * style, leaving no map at all.
 */
data class VfrLive(
    val build: String,
    /** The tile address, whole. */
    val tiles: String,
    /** Where the build's coverage is, relative to the server's root: the app's own client asks for it. */
    val coverage: String,
    val tileSize: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val effective: String,
    val expires: String,
    val bounds: ChartBox,
    val fallback: List<ChartBox>,
) {
    companion object {
        private const val MAX_LEVEL = 24
        private const val MAX_TILE_SIZE = 4096

        /** The build named by the config's own fields, [root] being the server's; null where they cannot be drawn. */
        fun of(
            root: String,
            build: String,
            tiles: String,
            coverage: String,
            tileSize: Int,
            minZoom: Int,
            maxZoom: Int,
            effective: String,
            expires: String,
            bounds: List<Double>,
            fallback: List<List<Double>>,
        ): VfrLive? {
            val box = ChartBox.of(bounds) ?: return null
            val addressed = tiles.isOnThisServer() && "{z}" in tiles && "{x}" in tiles && "{y}" in tiles
            val sized = tileSize in 1..MAX_TILE_SIZE && minZoom in 0..MAX_LEVEL && maxZoom in minZoom..MAX_LEVEL
            if (build.isBlank() || !addressed || !coverage.isOnThisServer() || !sized) return null
            if (chartInstant(effective) == null || chartInstant(expires) == null) return null
            return VfrLive(
                build = build, tiles = root.trimEnd('/') + tiles, coverage = coverage, tileSize = tileSize, minZoom = minZoom, maxZoom = maxZoom,
                effective = effective, expires = expires, bounds = box, fallback = fallback.mapNotNull(ChartBox::of),
            )
        }

        private fun String.isOnThisServer() = startsWith("/") && !startsWith("//")
    }
}

/** A chart's edition: its printed dates and the instant it runs out. */
data class ChartEdition(val start: String, val end: String, val expires: String, val expiresAt: Long)

/** A place on a chart's outline. */
data class ChartPoint(val lon: Double, val lat: Double)

/** One chart of a build, as its `coverage.json` says. */
data class CoverageChart(
    val title: String,
    val tier: String,
    val scale: Double,
    val edition: ChartEdition,
    val shownFrom: Int,
    /** The level this chart's own pixels fall on: 10.58, say, so not a whole one. */
    val nativeLevel: Double,
    val ring: List<ChartPoint>,
)

/** What a build holds: the charts it draws from, which the map names (`coverage.json`, the web's `isCoverage`). */
data class ChartCoverage(val build: String, val minLevel: Int, val charts: List<CoverageChart>) {
    companion object {
        private val DATE = Regex("[0-9]{4}-(0[1-9]|1[0-2])-[0-9]{2}")

        /** Read from what came, or from the device's copy; null where it cannot be. A chart that cannot be read is left out of it. */
        fun parse(text: String): ChartCoverage? {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return of(root)
        }

        fun of(root: JsonElement): ChartCoverage? {
            val document = root as? JsonObject ?: return null
            val build = document["build"].text() ?: return null
            val minLevel = document["minLevel"].integer() ?: return null
            val charts = document["charts"] as? JsonArray ?: return null
            return ChartCoverage(build, minLevel, charts.mapNotNull(::chartOf))
        }

        private fun chartOf(element: JsonElement): CoverageChart? {
            val chart = element as? JsonObject ?: return null
            val edition = (chart["edition"] as? JsonObject)?.let(::editionOf) ?: return null
            val outline = chart["outline"] as? JsonObject ?: return null
            val ring = ((outline["coordinates"] as? JsonArray)?.firstOrNull() as? JsonArray)?.map { pointOf(it) ?: return null } ?: return null
            if (ring.size < 3) return null
            return CoverageChart(
                title = chart["title"].text() ?: return null,
                tier = chart["tier"].text() ?: return null,
                scale = chart["scale"].number() ?: return null,
                edition = edition,
                shownFrom = chart["shownFrom"].integer() ?: return null,
                nativeLevel = chart["nativeLevel"].number() ?: return null,
                ring = ring,
            )
        }

        private fun editionOf(edition: JsonObject): ChartEdition? {
            val start = edition["start"].text()?.takeIf(DATE::matches) ?: return null
            val end = edition["end"].text()?.takeIf(DATE::matches) ?: return null
            val expires = edition["expires"].text() ?: return null
            return ChartEdition(start, end, expires, chartInstant(expires) ?: return null)
        }

        private fun pointOf(element: JsonElement): ChartPoint? {
            val pair = element as? JsonArray ?: return null
            if (pair.size < 2) return null
            return ChartPoint(pair[0].number() ?: return null, pair[1].number() ?: return null)
        }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun JsonElement?.integer(): Int? = number()?.takeIf { it == Math.rint(it) && it in -1e9..1e9 }?.toInt()
    }
}

/** What the map knows of the FAA's charts: [live], or null for ArcGIS, and what that build holds once it has come. */
data class VfrCharts(val live: VfrLive? = null, val coverage: ChartCoverage? = null)

/**
 * Which build of the charts draws the VFR map (docs/VFR_CHARTS.md §4.5, §8). A seam, because knowing it takes the server and the device's
 * memory, which are the app's.
 */
interface VfrChartSource {
    val charts: StateFlow<VfrCharts>

    /** Each time a chart tile has failed: a build gone, or no network, and nothing can say which. */
    val tileFailures: Flow<Unit>

    /** Asks the server which build is live, past every cache, and for what that build holds. No answer leaves what was known. */
    suspend fun look()
}

/**
 * How many levels finer than its zoom a screen draws the charts from (the web's `levelsFiner`; docs/VFR_CHARTS.md §8, *A dense screen*): 1 where a point is more than about
 * one pixel across, else 0. A tile holds its size in pixels, no more (Mapbox's `@2x` hold twice that), so drawn over 512 points it is spread over 1,536 pixels on a phone of
 * density 3; the next level, over 256, gives each tile pixel half as many. From √2 that level is the nearer of the two to one for one.
 */
internal fun levelsFiner(density: Double): Int = if (density >= Math.sqrt(2.0)) 1 else 0

/** The instant of a time as the server writes it (`2026-12-24T09:01Z`, with no seconds, which `Instant.parse` refuses); null for anything else. */
internal fun chartInstant(text: String): Long? = try {
    OffsetDateTime.parse(text).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
