package app.ezpztac.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Heavy server work goes first. The server runs on one Python interpreter, so while an analysis,
 * a viewshed or an export is in flight, background requests (sync pulls, tile and pack
 * prefetching) wait and the heavy work gets the server. The same rule the web applies
 * (`requestPriority.js`), for the same measured reason: an LZ analysis took 31.7 s instead of
 * 11.6 s with the 3D view streaming tiles beside it.
 */
public class PriorityGate {
    private val active = MutableStateFlow(0)

    /** True while heavy work is in flight. */
    public val isBusy: Boolean get() = active.value > 0

    /** Marks heavy work as started. Close the ticket when it ends; closing twice does nothing. */
    public fun begin(): Ticket {
        active.update { it + 1 }
        return Ticket()
    }

    public inner class Ticket internal constructor() : AutoCloseable {
        private var ended = false

        override fun close() {
            synchronized(this) {
                if (ended) return
                ended = true
            }
            active.update { it - 1 }
        }
    }

    /** Returns at once when nothing heavy is running, otherwise when it ends. */
    public suspend fun whenIdle() {
        active.first { it == 0 }
    }
}

/** The paths that make the server work hard enough to notice competition (the web's `PRIORITY_PATHS`). */
public object PriorityPaths {
    private val HEAVY = listOf(
        "/analyze-field",       // SAM
        "/terrain-analysis",    // slope from the DEMs
        "/threat-mask",         // viewshed
        "/export-package",
        "/generate-excel",
    )

    /** Whether a request to [url] (a path, or a whole URL) is heavy. A query string is ignored. */
    public fun isHeavy(url: String): Boolean {
        val path = url.substringBefore('?')
        return HEAVY.any { path.endsWith(it) }
    }
}
