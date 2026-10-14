package app.ezpztac.data

import app.ezpztac.model.PointSet
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** A saved set of points as this device shows it: its points, the colour it is drawn in and whether it is on the map. */
data class LoadedPointSet(
    val set: PointSet,
    val color: String,
    val visible: Boolean,
    /** Whether the server has this version, is yet to be told, or the record is the copy kept beside a conflict. */
    val sync: SyncStatus,
    /** The record this is a conflict copy of, if it is one. */
    val conflictOf: String?,
    /** Held for this session only ([SessionPointSets]): not saved or synced ([sync] means nothing for it). */
    val inSession: Boolean = false,
)

/**
 * Every set of points with how it is shown, the saved ones and those held for this session ([SessionPointSets]): what the map draws, and what a route
 * point's name is looked up in. Every set counts for a name, hidden or not (the web looks a name up in all the sets that are loaded), but only a visible one
 * is drawn.
 */
@Singleton
class LocalPoints @Inject constructor(
    private val repository: PointSetRepository,
    private val views: PointSetViews,
    private val session: SessionPointSets = SessionPointSets(),
) {
    /**
     * The sets, in the order the device holds them, with their views. Collecting it gives each set a view of its own, so its colour stays when another
     * goes; that is a change to the views, heard once, and a second look finds nothing more to do.
     */
    val sets: Flow<List<LoadedPointSet>> = combine(repository.observeSets(), session.sets, views.views) { stored, held, shown ->
        // A colour is chosen among the sets already placed, so a list read from the server at once does not give every set the same one.
        var placed = shown
        fun loaded(set: PointSet, sync: SyncStatus, conflictOf: String?, inSession: Boolean): LoadedPointSet {
            val view = views.viewOf(set.id, placed)
            placed = placed + (set.id to view)
            return LoadedPointSet(set, view.color, view.visible, sync, conflictOf, inSession)
        }
        // The saved sets, then the ones held for this session (shown and looked up in the same way). One just saved to the Library is the saved one, under the
        // same id, for the moment both lists have it.
        val saved = stored.mapTo(HashSet()) { it.set.id }
        stored.map { s -> loaded(s.set, s.sync, s.conflictOf, inSession = false) } +
            held.filter { it.id !in saved }.map { set -> loaded(set, SyncStatus.PENDING, null, inSession = true) }
    }.onEach { loaded -> views.settle(loaded.map { it.set.id }) }
}
