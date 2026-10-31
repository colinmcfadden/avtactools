package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.WeatherSnapshot
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.WeatherReportDto
import app.ezpztac.network.weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

/** The one server call weather makes. [ApiClient] in the app; a stand-in in tests. */
public interface WeatherApi {
    public suspend fun weather(at: LatLon): WeatherReportDto
}

/** [WeatherApi] over the real client. */
public class ApiClientWeatherApi(private val client: ApiClient) : WeatherApi {
    override suspend fun weather(at: LatLon): WeatherReportDto = client.weather(at)
}

/** Where the last weather for each diagram is kept between launches. A device with no signal shows what it has, with its age. */
public interface WeatherCache {
    public fun load(): Map<String, WeatherSnapshot>

    public fun save(snapshots: Map<String, WeatherSnapshot>)
}

/**
 * What is known of the weather at a diagram's target: the last answer (which may be old), whether a new one is being fetched, and why the last attempt
 * failed, in words, if it did. A failed attempt never takes away what was known: the old report stays, marked with its age.
 */
public data class WeatherState(
    val snapshot: WeatherSnapshot? = null,
    val fetching: Boolean = false,
    val failure: String? = null,
)

/**
 * The weather at each diagram's target. *Fetched data*: it is not part of the diagram (the web does not save it, and it is not synced), it comes from
 * `/api/weather`, and the last answer for each diagram is kept with the time it was fetched, so a crew with no signal sees the old report marked with its
 * age instead of nothing (or, worse, a stale report with no age).
 *
 * [ensureFresh] is what opening a diagram calls: it fetches only when there is nothing yet, what there is is older than [REFETCH_AFTER_MS], or the target
 * has moved. One fetch runs at a time for a diagram, and a failure is not retried for [RETRY_AFTER_MS] so a screen that asks again and again does not hammer
 * a service that is down. What is shown of a failure is the app's words, never the server's (a 500's text names a Python type).
 */
public class WeatherService(
    private val api: WeatherApi,
    private val cache: WeatherCache,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _states = MutableStateFlow(cache.load().mapValues { (_, snapshot) -> WeatherState(snapshot) })

    /** The state for each diagram that has one, by the diagram's id. */
    public val states: StateFlow<Map<String, WeatherState>> = _states.asStateFlow()

    private val failedAt = ConcurrentHashMap<String, Long>()

    /** What is known for [diagramId]: nothing at all when it has never been asked. */
    public fun stateOf(diagramId: String): WeatherState = _states.value[diagramId] ?: WeatherState()

    /** Fetches now unless one is already running for this diagram. */
    public fun refresh(diagramId: String, at: LatLon) {
        if (!begin(diagramId)) return
        scope.launch { fetch(diagramId, at) }
    }

    /** Fetches only if what is held is missing, old, for somewhere else, or has not just failed (see the class). */
    public fun ensureFresh(diagramId: String, at: LatLon) {
        val state = stateOf(diagramId)
        if (state.fetching) return
        val now = clock()
        failedAt[diagramId]?.let { if (now - it in 0 until RETRY_AFTER_MS) return }
        val held = state.snapshot
        val current = held != null && now - held.fetchedAtMillis <= REFETCH_AFTER_MS && metresBetween(held.at, at) <= MOVED_METRES
        if (!current) refresh(diagramId, at)
    }

    /** Forgets everything, as at sign-out: where a landing zone is does not stay on a device for the next person. */
    public fun clear() {
        failedAt.clear()
        _states.value = emptyMap()
        cache.save(emptyMap())
    }

    /** Marks the fetch as begun; false when one is already going. */
    private fun begin(diagramId: String): Boolean {
        var started = false
        _states.update { current ->
            val state = current[diagramId] ?: WeatherState()
            if (state.fetching) current else { started = true; current + (diagramId to state.copy(fetching = true, failure = null)) }
        }
        return started
    }

    private suspend fun fetch(diagramId: String, at: LatLon) {
        val outcome: Result<WeatherSnapshot> = try {
            Result.success(WeatherMapping.snapshotOf(api.weather(at), at, clock()))
        } catch (e: CancellationException) {
            _states.update { it + (diagramId to (it[diagramId] ?: WeatherState()).copy(fetching = false)) }
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        outcome.fold(
            onSuccess = { snapshot ->
                failedAt.remove(diagramId)
                _states.update { it + (diagramId to WeatherState(snapshot)) }
                persist()
            },
            onFailure = { e ->
                failedAt[diagramId] = clock()
                _states.update { it + (diagramId to (it[diagramId] ?: WeatherState()).copy(fetching = false, failure = reason(e))) }
            },
        )
    }

    private fun persist() {
        // The newest few only: a device that has planned a hundred landing zones does not keep a hundred reports.
        val kept = _states.value.mapNotNull { (id, s) -> s.snapshot?.let { id to it } }.sortedByDescending { it.second.fetchedAtMillis }.take(MAX_KEPT).toMap()
        try {
            cache.save(kept)
        } catch (_: Exception) {
            // A cache that cannot be written costs the next launch its old report, not this one its new.
        }
    }

    private fun reason(e: Throwable): String = when (e) {
        is NetworkException -> "No connection, so the weather could not be updated."
        is SessionEndedException -> "Sign in again to update the weather."
        is RateLimitedException -> "Too many requests. Wait a moment and try again."
        is ApiException -> "The weather service could not answer. Try again in a moment."
        else -> "The weather could not be updated. Try again in a moment."
    }

    private fun metresBetween(a: LatLon, b: LatLon): Double {
        val north = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS_M
        val east = Math.toRadians(b.lon - a.lon) * EARTH_RADIUS_M * cos(Math.toRadians((a.lat + b.lat) / 2))
        return Math.hypot(north, east)
    }

    public companion object {
        /** A report older than this is fetched again when a diagram is opened (a METAR is issued hourly; half an hour is as fresh as a person can use). */
        public const val REFETCH_AFTER_MS: Long = 30 * 60_000L

        /** After a failure, opening the diagram again does not try again for this long. */
        public const val RETRY_AFTER_MS: Long = 60_000L

        /** A target that has moved this far is another place: its weather is fetched again. A station is miles away; this is about a city block. */
        public const val MOVED_METRES: Double = 1_000.0

        public const val MAX_KEPT: Int = 40

        private const val EARTH_RADIUS_M = 6_378_137.0
    }
}
