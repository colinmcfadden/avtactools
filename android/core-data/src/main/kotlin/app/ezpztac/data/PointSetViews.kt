package app.ezpztac.data

import app.ezpztac.planning.RouteColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** How a set of points is shown on this device: the colour it is drawn in and whether it is on the map. Neither is saved to the server. */
data class PointSetView(val color: String, val visible: Boolean = true)

/** Where [PointSetViews] keeps what it knows, between launches. */
interface PointSetViewStore {
    fun load(): Map<String, PointSetView>

    fun save(views: Map<String, PointSetView>)
}

/**
 * The colour and visibility of each set of points, for this device. The server has no field for them (`/api/pointsets` keeps a name and the points), so
 * each device keeps its own, as the web keeps them for a session. A set takes the first palette colour no other set is using, as a new route does, and
 * keeps it; one that is not known yet is drawn in the colour it would take.
 */
@Singleton
class PointSetViews @Inject constructor(private val store: PointSetViewStore) {
    private val _views = MutableStateFlow(store.load())

    /** The views by the set's uuid. A set with no entry is shown, in the colour [viewOf] would give it. */
    val views: StateFlow<Map<String, PointSetView>> = _views.asStateFlow()

    /** The view [uuid] has, or the one it would take among the sets already shown (never stored by asking). */
    fun viewOf(uuid: String, views: Map<String, PointSetView> = _views.value): PointSetView =
        views[uuid] ?: PointSetView(RouteColors.next(views.values.map { it.color }))

    /**
     * Brings the views in line with the sets there are, in this order: each set without one gets one of its own now (so a colour does not change when
     * another set is deleted or arrives), and a view of a set that is gone is dropped (so its colour is free for the next). Nothing changes when they
     * already agree.
     */
    fun settle(uuids: List<String>) {
        _views.update { current ->
            var next = current.filterKeys { it in uuids }
            for (uuid in uuids) if (uuid !in next) next = next + (uuid to viewOf(uuid, next))
            if (next == current) current else next.also(store::save)
        }
    }

    /** Shows or hides a set. */
    fun toggle(uuid: String) = change(uuid) { it.copy(visible = !it.visible) }

    /** Draws a set in [color]. */
    fun recolor(uuid: String, color: String) = change(uuid) { it.copy(color = color) }

    private fun change(uuid: String, edit: (PointSetView) -> PointSetView) {
        _views.update { current -> (current + (uuid to edit(viewOf(uuid, current)))).also(store::save) }
    }
}
