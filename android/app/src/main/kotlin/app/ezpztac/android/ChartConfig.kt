package app.ezpztac.android

import android.content.Context
import app.ezpztac.map.ChartCoverage
import app.ezpztac.map.ChartTileWatch
import app.ezpztac.map.VfrChartSource
import app.ezpztac.map.VfrCharts
import app.ezpztac.map.VfrLive
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.AppConfig
import app.ezpztac.network.chartCoverage
import app.ezpztac.network.config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app writes down which build of the FAA's charts the server named. A seam, so the shell is tried without Android. */
interface MapChartsSink {
    fun update(vfr: AppConfig.Charts.Vfr?)
}

/** What the server is asked about the charts. */
interface ChartCalls {
    /** The config, past every cache. */
    suspend fun config(): AppConfig

    /** A build's `coverage.json`, as text. */
    suspend fun coverage(path: String): String
}

/** The build last named, as the text it was kept in. */
interface ChartMemory {
    fun chartConfig(): String?
    fun setChartConfig(text: String?)
}

/** The device's copy of the live build's `coverage.json`, so a start with no signal can still name its charts. */
interface CoverageFile {
    fun read(): String?
    fun write(text: String)
}

/**
 * Which build of the FAA's charts draws the VFR map (docs/VFR_CHARTS.md §4.5, §8): remembered as the Mapbox token is, so a start with no signal still draws it, and taken
 * from every answer of the config. A build is public, and names no place anyone looked at, so nothing of it is cleared at sign-out. A look with no answer leaves what was
 * known; an answer with no build takes the VFR map back to ArcGIS. The same build changes nothing. On the main thread, as the shell and [app.ezpztac.map.ChartLooks] are.
 */
@Singleton
class ChartConfig internal constructor(
    private val calls: ChartCalls,
    private val memory: ChartMemory,
    private val file: CoverageFile,
    private val root: String,
    override val tileFailures: Flow<Unit>,
) : VfrChartSource, MapChartsSink {
    @Inject
    constructor(calls: ChartCalls, memory: ChartMemory, file: CoverageFile) : this(calls, memory, file, BuildConfig.API_URL, ChartTileWatch.shared.failed)

    private val _charts = MutableStateFlow(VfrCharts(live = remembered()))
    override val charts: StateFlow<VfrCharts> = _charts.asStateFlow()

    private var keptCoverageRead = false

    override fun update(vfr: AppConfig.Charts.Vfr?) {
        val live = vfr?.let(::liveOf)
        val before = _charts.value.live
        if (live != null && before != null && live.build == before.build) return
        memory.setChartConfig(vfr?.takeIf { live != null }?.let { CODEC.encodeToString(AppConfig.Charts.Vfr.serializer(), it) })
        _charts.update { current -> VfrCharts(live, current.coverage?.takeIf { it.build == live?.build }) }
    }

    override suspend fun look() {
        readKeptCoverage()
        val config = try {
            calls.config()
        } catch (_: ApiException) {
            return
        }
        update(config.charts?.vfr)
        val live = _charts.value.live ?: return
        if (_charts.value.coverage?.build == live.build) return
        val text = try {
            calls.coverage(live.coverage)
        } catch (_: ApiException) {
            return
        } catch (_: IllegalArgumentException) {
            return                                                       // the config named something that is not a build's coverage
        }
        val coverage = ChartCoverage.parse(text)?.takeIf { it.build == live.build } ?: return
        withContext(Dispatchers.IO) { file.write(text) }
        _charts.update { current -> if (current.live?.build == live.build) current.copy(coverage = coverage) else current }
    }

    // Once, and only when the VFR map first shows: most launches never draw it, and the file is read off the main thread.
    private suspend fun readKeptCoverage() {
        if (keptCoverageRead) return
        keptCoverageRead = true
        val kept = withContext(Dispatchers.IO) { file.read()?.let(ChartCoverage::parse) } ?: return
        _charts.update { current -> if (current.coverage == null && current.live?.build == kept.build) current.copy(coverage = kept) else current }
    }

    private fun remembered(): VfrLive? {
        val text = memory.chartConfig() ?: return null
        return try {
            liveOf(CODEC.decodeFromString(AppConfig.Charts.Vfr.serializer(), text))
        } catch (_: IllegalArgumentException) {
            null                                                         // not what was written: a preference is only ever text
        }
    }

    private fun liveOf(vfr: AppConfig.Charts.Vfr): VfrLive? = VfrLive.of(
        root = root, build = vfr.build, tiles = vfr.tiles, coverage = vfr.coverage, tileSize = vfr.tileSize, minZoom = vfr.minZoom, maxZoom = vfr.maxZoom,
        effective = vfr.effective, expires = vfr.expires, bounds = vfr.bounds, fallback = vfr.fallback,
    )

    private companion object {
        val CODEC = Json { ignoreUnknownKeys = true }
    }
}

@Singleton
class ApiChartCalls @Inject constructor(private val api: ApiClient) : ChartCalls {
    override suspend fun config(): AppConfig = api.config(fresh = true)
    override suspend fun coverage(path: String): String = api.chartCoverage(path)
}

/** Written whole through a temporary file, so a process ended part way leaves the last one; one that cannot be read or written is simply no copy. */
@Singleton
class CoverageFileStore @Inject constructor(@ApplicationContext context: Context) : CoverageFile {
    private val file = File(context.filesDir, "vfr-coverage.json")

    override fun read(): String? = try {
        if (file.isFile && file.length() <= MAX_BYTES) file.readText() else null
    } catch (_: IOException) {
        null
    }

    override fun write(text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(text)
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: IOException) {
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
