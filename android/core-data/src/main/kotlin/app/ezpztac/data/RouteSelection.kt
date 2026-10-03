package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The route the person is working on in the open set, and the point of it they are holding, if any. */
data class RouteHeld(val routeId: String, val pointId: String? = null)

/**
 * Which route of the open set, and which of its points, the person is working on: the map draws that route heavier and marks the point, and the
 * route screen edits them. It belongs to no screen, because the map and the sheet both read it. It does not outlive the set: whoever opens or
 * closes one clears it.
 */
@Singleton
class RouteSelection @Inject constructor() {
    private val _held = MutableStateFlow<RouteHeld?>(null)
    val held: StateFlow<RouteHeld?> = _held.asStateFlow()

    /** Works on [routeId], holding [pointId] of it if given. */
    fun select(routeId: String, pointId: String? = null) {
        _held.value = RouteHeld(routeId, pointId)
    }

    /** Holds [pointId] of the route already being worked on; nothing happens when no route is. */
    fun holdPoint(pointId: String?) {
        val current = _held.value ?: return
        _held.value = current.copy(pointId = pointId)
    }

    /** Puts down the point but keeps the route. */
    fun releasePoint() = holdPoint(null)

    fun clear() {
        _held.value = null
    }
}
