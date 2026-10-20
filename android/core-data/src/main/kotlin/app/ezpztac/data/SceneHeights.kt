package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.SketchRoute
import app.ezpztac.network.ApiClient
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
 * The routes in the 3D view placed on the ground, the web's `useRouteScene`: the routes the 2D map draws ([routes]) are prepared at once
 * ([RouteScene3D.prepare]); the ground and the geoid under them are asked for [pauseMs] after they last changed (a point dragged changes them
 * many times a second; one ask once it settles is enough); and the shapes are placed when the answer comes. The routes last placed stay while
 * a newer answer is on its way, so a route changed (someone else's, in a pack) moves in 3D rather than blinking out, and an ask that fails
 * leaves them as they were. Routes that are the same as last time ask nothing.
 */
public class SceneHeights(private val ground: GroundUnder, private val scope: CoroutineScope, private val pauseMs: Long = PAUSE_MS) {
    private val _placed = MutableStateFlow<List<PlacedRoute>>(emptyList())

    /** The routes as last placed. */
    public val placed: StateFlow<List<PlacedRoute>> = _placed.asStateFlow()

    private var asked: PreparedRoutes? = null
    private var job: Job? = null

    /** The routes to draw now (the open set's; the hidden ones are left out by [RouteScene3D.prepare]). */
    public fun routes(routes: List<SketchRoute>) {
        val prepared = RouteScene3D.prepare(routes)
        if (prepared == asked) return
        asked = prepared
        job?.cancel()
        if (prepared.samples.isEmpty()) {
            _placed.value = emptyList()
            return
        }
        job = scope.launch {
            delay(pauseMs)
            val answer = try {
                ground.at(prepared.samples.map { LatLon(it.lat, it.lon) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return@launch
            }
            _placed.value = RouteScene3D.place(prepared, answer)
        }
    }

    /** The view closed: nothing more is asked, and nothing is placed. */
    public fun close() {
        job?.cancel()
        job = null
        asked = null
        _placed.value = emptyList()
    }

    public companion object {
        public const val PAUSE_MS: Long = 400
    }
}
