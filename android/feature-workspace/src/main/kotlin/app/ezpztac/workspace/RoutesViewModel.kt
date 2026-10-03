package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSetSummary
import app.ezpztac.data.RouteSketching
import app.ezpztac.data.SketchFinish
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteCalc
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One saved set in the list. */
data class RouteSetRow(
    val uuid: String,
    val name: String,
    val routeCount: Int,
    val sync: SyncStatus,
    /** The set this is the user's kept version of, when another device changed it too. */
    val conflictOf: String?,
    val isOpen: Boolean,
)

/** One route of the open set. */
data class RouteRowUi(
    val id: String,
    val name: String,
    /** `#RRGGBB`. */
    val color: String,
    val visible: Boolean,
    /** Every point, shaping points included. */
    val pointCount: Int,
    /** The named points legs run between. */
    val routePointCount: Int,
    /** `12.3 nm · 8:15`, once the route has two named points to run between. */
    val summary: String?,
    val selected: Boolean,
)

/** The open set, with what can be done to it from here. */
data class OpenSetUi(
    val uuid: String,
    val name: String,
    val routes: List<RouteRowUi>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val sync: SyncStatus?,
    val conflictOf: String?,
)

/** A route being drawn. */
data class RouteDrawingUi(val points: Int, val canFinish: Boolean)

data class RoutesUiState(
    val sets: List<RouteSetRow> = emptyList(),
    val open: OpenSetUi? = null,
    val drawing: RouteDrawingUi? = null,
    /** The form for a new set is open. */
    val creating: Boolean = false,
    val error: String? = null,
)

/**
 * The routes tab: the saved sets and the routes of the open one. The open set is the [RouteSession]'s, the route held is [RouteSelection]'s and a route
 * being drawn is [RouteSketching]'s; this is what a screen shows of them and how it asks for changes. Every change to the open set is one step of its
 * own undo. Opening a set is what makes the map show it (the app watches the session), so this screen does not know about the map.
 */
@HiltViewModel
class RoutesViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val session: RouteSession,
    private val selection: RouteSelection,
    private val sketching: RouteSketching,
    private val conflicts: ConflictResolver,
) : ViewModel() {
    private data class Local(val creating: Boolean = false, val error: String? = null)

    private val local = MutableStateFlow(Local())
    private val depths = combine(session.undoDepth, session.redoDepth) { undo, redo -> undo to redo }

    val state: StateFlow<RoutesUiState> = combine(repository.observe(), session.active, selection.held, sketching.draft, local) { summaries, set, held, draft, local ->
        RoutesUiState(
            sets = summaries.map { it.toRow(isOpen = it.uuid == set?.id) },
            open = set?.let { openOf(it, held?.routeId, summaries.firstOrNull { s -> s.uuid == it.id }) },
            drawing = draft?.takeIf { it.setId == set?.id }?.let { RouteDrawingUi(it.points.size, it.canFinish) },
            creating = local.creating,
            error = local.error,
        )
    }.combine(depths) { state, (undo, redo) ->
        state.copy(open = state.open?.copy(canUndo = undo > 0, canRedo = redo > 0))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutesUiState())

    private fun RouteSetSummary.toRow(isOpen: Boolean) = RouteSetRow(uuid, name, routeCount, sync, conflictOf, isOpen)

    private fun openOf(set: RouteSet, heldRouteId: String?, listed: RouteSetSummary?) = OpenSetUi(
        uuid = set.id, name = set.name, routes = set.routes.map { rowOf(it, selected = it.id == heldRouteId) },
        canUndo = false, canRedo = false, sync = listed?.sync, conflictOf = listed?.conflictOf,
    )

    private fun rowOf(route: SketchRoute, selected: Boolean) = RouteRowUi(
        id = route.id, name = route.name, color = route.color, visible = route.visible, pointCount = route.points.size,
        routePointCount = route.points.count { it.kind != RoutePoint.KIND_SHAPING && !it.id.isNullOrEmpty() },
        summary = summaryOf(route), selected = selected,
    )

    // -- Sets ----------------------------------------------------------------------------------------------------

    fun startCreating() = local.update { it.copy(creating = true, error = null) }

    fun cancelCreating() = local.update { it.copy(creating = false, error = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    /** Makes an empty set and opens it. A blank [name] is `MISSION n`, n counting the sets there are, upper-cased as the web names routes. */
    fun createSet(name: String) {
        val title = name.trim().uppercase().ifEmpty { "MISSION ${state.value.sets.size + 1}" }
        run("The set could not be made.") {
            val made = repository.create(title)
            session.open(made.id)
            local.update { it.copy(creating = false) }
        }
    }

    fun openSet(uuid: String) {
        if (session.active.value?.id == uuid) return
        run("The set could not be opened.") {
            if (!session.open(uuid)) local.update { it.copy(error = "That set is no longer here.") }
        }
    }

    /** Puts the open set away (it is saved first). */
    fun closeSet() = run("The set could not be closed.") { session.close() }

    fun renameSet(uuid: String, name: String) {
        val title = name.trim().uppercase()
        if (title.isEmpty()) return fail("A set needs a name.")
        run("The set could not be renamed.") {
            // The open set is the one with unsaved edits in memory, so it is renamed there and saved with them.
            if (session.active.value?.id == uuid) {
                session.edit("Rename set") { it.copy(name = title) }
                session.flush()
            } else {
                repository.rename(uuid, title)
            }
        }
    }

    fun deleteSet(uuid: String) = run("The set could not be deleted.") {
        if (session.active.value?.id == uuid) session.close()
        repository.delete(uuid)
    }

    /** Settles a conflict the sync kept beside a set: the same three choices a diagram has. */
    fun resolve(copyUuid: String, resolution: SyncEngine.Resolution) =
        run("The conflict could not be settled.") { conflicts.resolve(RecordKind.ROUTE, copyUuid, resolution) }

    // -- Routes of the open set --------------------------------------------------------------------------------

    /** Works on [routeId]: the map draws it heavier. Choosing the route already chosen puts it down. */
    fun selectRoute(routeId: String) {
        if (session.active.value?.route(routeId) == null) return
        if (selection.held.value?.routeId == routeId) selection.clear() else selection.select(routeId)
    }

    fun toggleVisible(routeId: String) = session.edit("Show or hide route") { set -> set.mapRoute(routeId) { it.copy(visible = !it.visible) } }

    fun renameRoute(routeId: String, name: String) {
        val title = name.trim().uppercase()
        if (title.isEmpty()) return fail("A route needs a name.")
        session.edit("Rename route") { set -> set.mapRoute(routeId) { it.copy(name = title) } }
    }

    fun deleteRoute(routeId: String) {
        if (selection.held.value?.routeId == routeId) selection.clear()
        session.edit("Delete route") { it.without(routeId) }
    }

    fun undo() {
        session.undo()
        dropStaleSelection()
    }

    fun redo() {
        session.redo()
        dropStaleSelection()
    }

    /** What is held may have been undone out of existence: a route that is not there is not held. */
    private fun dropStaleSelection() {
        val held = selection.held.value ?: return
        if (session.active.value?.route(held.routeId) == null) selection.clear()
    }

    // -- Drawing ---------------------------------------------------------------------------------------------

    fun startDrawing() = fail(sketching.start())

    /** Puts a point down at the crosshair, [at]. */
    fun addAtCrosshair(at: LatLon?) {
        if (at == null) return fail("Move the map to where the point should go first.")
        if (!sketching.addPoint(at)) fail("That point could not be added.") else local.update { it.copy(error = null) }
    }

    fun undoPoint() {
        local.update { it.copy(error = null) }
        sketching.removeLastPoint()
    }

    /** Makes the drawn line a route of the open set and holds it, so its row is the one open. */
    fun finishDrawing(name: String = "") {
        when (val result = sketching.finish(name)) {
            is SketchFinish.Done -> {
                local.update { it.copy(error = null) }
                selection.select(result.routeId)
            }
            is SketchFinish.Refused -> fail(result.reason)
        }
    }

    fun cancelDrawing() {
        local.update { it.copy(error = null) }
        sketching.cancel()
    }

    private fun fail(message: String?) = local.update { it.copy(error = message) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(error = fallback) }
            }
        }
    }

    private fun summaryOf(route: SketchRoute): String? {
        val totals = RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations).totals ?: return null
        return "${oneDecimal(totals.distNm)} nm · ${RouteCalc.formatDuration(totals.timeSec)}"
    }
}
