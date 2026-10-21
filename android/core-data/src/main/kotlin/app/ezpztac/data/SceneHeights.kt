package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.SketchRoute
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.terrainHeights
import app.ezpztac.scene3d.PlacedRoute
import app.ezpztac.scene3d.PreparedRoutes
import app.ezpztac.scene3d.RouteHeights
import app.ezpztac.scene3d.RouteScene3D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ground and the geoid under places, for the routes in 3D: `POST /api/terrain/heights` in the app, the places in its body and never a URL.
 * A stand-in in tests.
 */
public fun interface GroundUnder {
    public suspend fun at(points: List<LatLon>): RouteHeights
}

/** [GroundUnder] over the real client. */
public class ApiClientGroundUnder(private val client: ApiClient) : GroundUnder {
    override suspend fun at(points: List<LatLon>): RouteHeights = client.terrainHeights(points).let { RouteHeights(it.groundM, it.geoidM) }
}

/**
 * How the routes in 3D stand, for *Routes* to say why it has nothing to frame when it has not: none to draw ([NONE]); the ground under them asked
 * for, or asked again after a failure that may pass ([PLACING]); placed ([PLACED], which still draws nothing where the server has no ground under
 * them); or the ground could not be had ([FAILED]), whatever was placed before still drawn.
 */
public enum class RoutesPlacing { NONE, PLACING, PLACED, FAILED }

/**
 * The routes in the 3D view placed on the ground, the web's `useRouteScene`: the routes the 2D map draws ([routes]) are prepared at once
 * ([RouteScene3D.prepare]); the ground and the geoid under them are asked for [pauseMs] after they last changed (a point dragged changes them
 * many times a second; one ask once it settles is enough); and the shapes are placed when the answer comes. The routes last placed stay while
 * a newer answer is on its way, so a route changed (someone else's, in a pack) moves in 3D rather than blinking out, and an ask that fails
 * leaves them as they were. Routes that are the same as last time ask nothing, unless their ground could not be had.
 *
 * **Not like the web**, which asks once: an ask that met no connection, or the server busy or down, is made again [retryMs] after it failed and
 * twice that after a second failure, as the point cloud's tiles are, since a phone loses its signal more often than a browser; after that, or a
 * failure that will not pass, the routes stand [RoutesPlacing.FAILED] until they change or [retry] is asked.
 */
public class SceneHeights(
    private val ground: GroundUnder,
    private val scope: CoroutineScope,
    private val pauseMs: Long = PAUSE_MS,
    private val retryMs: Long = RETRY_MS,
) {
    private val _placed = MutableStateFlow<List<PlacedRoute>>(emptyList())
    private val _standing = MutableStateFlow(RoutesPlacing.NONE)

    /** The routes as last placed. */
    public val placed: StateFlow<List<PlacedRoute>> = _placed.asStateFlow()

    /** How they stand. */
    public val standing: StateFlow<RoutesPlacing> = _standing.asStateFlow()

    private var asked: PreparedRoutes? = null
    private var job: Job? = null

    /** The routes to draw now (the open set's; the hidden ones are left out by [RouteScene3D.prepare]). */
    public fun routes(routes: List<SketchRoute>) {
        val prepared = RouteScene3D.prepare(routes)
        if (prepared == asked && _standing.value != RoutesPlacing.FAILED) return
        ask(prepared)
    }

    /** The routes whose ground could not be had, asked for again (*Routes* pressed with nothing to frame); anything else is left as it is. */
    public fun retry() {
        if (_standing.value == RoutesPlacing.FAILED) asked?.let(::ask)
    }

    private fun ask(prepared: PreparedRoutes) {
        asked = prepared
        job?.cancel()
        if (prepared.samples.isEmpty()) {
            _placed.value = emptyList()
            _standing.value = RoutesPlacing.NONE
            return
        }
        _standing.value = RoutesPlacing.PLACING
        job = scope.launch {
            delay(pauseMs)
            var tries = 0
            while (true) {
                tries++
                val answer = try {
                    ground.at(prepared.samples.map { LatLon(it.lat, it.lon) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (mayPass(e) && tries < MAX_TRIES) {
                        delay(retryMs * tries)
                        continue
                    }
                    _standing.value = RoutesPlacing.FAILED
                    return@launch
                }
                _placed.value = RouteScene3D.place(prepared, answer)
                _standing.value = RoutesPlacing.PLACED
                return@launch
            }
        }
    }

    /** The view closed: nothing more is asked, and nothing is placed. */
    public fun close() {
        job?.cancel()
        job = null
        asked = null
        _placed.value = emptyList()
        _standing.value = RoutesPlacing.NONE
    }

    public companion object {
        public const val PAUSE_MS: Long = 400

        /** The pause before an ask that may pass is made again, times the tries so far: 5 s, then 10 s (the point cloud's tiles' own). */
        public const val RETRY_MS: Long = 5_000

        private const val MAX_TRIES = 3

        // A failure that may pass: no connection, the server busy or down. One that will not (refused, an answer that cannot be read) is not
        // asked again by itself.
        private fun mayPass(e: Exception): Boolean = when (e) {
            is NetworkException, is RateLimitedException -> true
            is ApiException -> e.status >= 500 || e.status == 408
            else -> false
        }
    }
}
