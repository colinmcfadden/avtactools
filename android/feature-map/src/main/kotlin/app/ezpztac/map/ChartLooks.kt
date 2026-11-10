package app.ezpztac.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.math.min

/**
 * When the VFR map asks which build is live (docs/VFR_CHARTS.md §4.5, §8), the web's `useVfrCharts`: every 5 minutes while it shows, just after
 * the live build runs out, and after a chart tile fails, at most once a minute. A look that is under way is never cut short by the build it
 * brings, and when the last look was made is kept for the next time the map shows. On the main thread, as [run] is called.
 */
class ChartLooks(private val source: VfrChartSource, private val now: () -> Long = System::currentTimeMillis) {
    private var lookedAt = 0L
    private var asking = false

    /** For as long as the VFR map shows and the app is in front. */
    suspend fun run(): Unit = coroutineScope {
        fun look() {
            if (asking) return
            asking = true
            lookedAt = now()
            // On completion, not in a finally: a coroutine cancelled before it starts runs none of its body.
            launch {
                try {
                    source.look()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Nothing about asking for a chart may end the app: what was known stands.
                }
            }.invokeOnCompletion { asking = false }
        }

        // Back on the VFR map after a while on another, or without the coverage an earlier look had no reason to fetch.
        val known = source.charts.value
        if (now() - lookedAt >= LOOK_EVERY_MS || (known.live != null && known.coverage?.build != known.live.build)) look()
        launch { source.tileFailures.collect { if (now() - lookedAt >= AFTER_A_FAILED_TILE_MS) look() } }

        source.charts.map { charts -> charts.live?.let { chartInstant(it.expires) } }.distinctUntilChanged().collectLatest { expires ->
            while (true) {
                val toExpiry = expires?.let { it + PAST_EXPIRY_MS - now() }
                delay(if (toExpiry != null && toExpiry > 0) min(LOOK_EVERY_MS, toExpiry) else LOOK_EVERY_MS)
                look()
            }
        }
    }

    companion object {
        const val LOOK_EVERY_MS = 5L * 60 * 1000
        const val AFTER_A_FAILED_TILE_MS = 60L * 1000

        /** A look at the very moment a build runs out would find the server's answer of the minute before (it keeps it for 60 s). */
        const val PAST_EXPIRY_MS = 61L * 1000
    }
}

/**
 * Tells when a chart tile fails, from MapLibre's own requests. MapLibre's tile listener cannot be used: it counts a 404 as a tile with nothing in it, and a 404 is exactly
 * what a build taken away answers. Only this server's chart tiles are watched, never ArcGIS's, and a request MapLibre gave up on as the map moved is no failure.
 */
class ChartTileWatch : Interceptor {
    private val _failed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val failed: SharedFlow<Unit> = _failed.asSharedFlow()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith(CHART_PATH)) return chain.proceed(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!chain.call().isCanceled()) _failed.tryEmit(Unit)
            throw e
        }
        if (response.code >= 400) _failed.tryEmit(Unit)
        return response
    }

    companion object {
        private const val CHART_PATH = "/charts/vfr/"

        /** The one MapLibre's requests go through, there being one MapLibre. */
        val shared: ChartTileWatch = ChartTileWatch()
    }
}
