package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.ImportOutcome
import app.ezpztac.data.LoadedPointSet
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.PointSelection
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.RouteColors
import app.ezpztac.planning.SketchOps
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** One saved set of local points in the list: how it is shown on this device and where it stands with the server. */
data class PointSetRowUi(
    val uuid: String,
    val name: String,
    val pointCount: Int,
    /** `#RRGGBB`: the colour its points are drawn in. */
    val color: String,
    /** Whether its points are on the map. */
    val visible: Boolean,
    val sync: SyncStatus,
    /** The set this is the user's kept version of, when another device changed it too. */
    val conflictOf: String?,
)

/** The local point being held (tapped on the map): what is known of it, and what can be done with it for the route being worked on. */
data class HeldPointUi(
    val setId: String,
    val setName: String,
    val pointId: String,
    /** The point's name as the file has it; empty when it has none. */
    val rawName: String,
    val at: LatLon,
    val elevationFt: Double?,
    val description: String,
    val group: String,
    val grid: String,
    val latLon: String,
    /** The route point that "Use for" would move onto this one, in words, or null when no route point is held. */
    val useFor: String?,
    /** The route this would be added to the end of, in words, or null when there is none to add to. */
    val addTo: String?,
) {
    /** What to call the point in words. */
    val title: String get() = rawName.ifBlank { "(unnamed)" }

    /** `1,730 ft`, or null when the file gave none. */
    val elevation: String? get() = elevationFt?.let { "${withCommas(Math.round(it))} ft" }
}

data class PointsUiState(
    val sets: List<PointSetRowUi> = emptyList(),
    val held: HeldPointUi? = null,
    /** A file is being read. */
    val importing: Boolean = false,
    val error: String? = null,
    /** What the last import or use of a point did, until dismissed. */
    val note: String? = null,
)

/**
 * The local points tab: the saved sets, importing an AMPS `.LPS`, showing and hiding each on the map, the colour it is drawn in, and the point being
 * held and what can be done with it for the route being worked on. The sets and how they are shown are [LocalPoints]'; the held point is
 * [PointSelection]'s, which the map draws too, so this screen does not know about the map.
 */
@HiltViewModel
class PointsViewModel @Inject constructor(
    private val repository: PointSetRepository,
    localPoints: LocalPoints,
    private val views: PointSetViews,
    private val selection: PointSelection,
    private val routes: RouteSession,
    private val routeSelection: RouteSelection,
    private val conflicts: ConflictResolver,
) : ViewModel() {
    private data class Local(val importing: Boolean = false, val error: String? = null, val note: String? = null)

    /** Where a file is read into a set: a big one is thousands of points, so it is not done on the main thread. A test sets its own. */
    internal var worker: CoroutineDispatcher = Dispatchers.Default

    /** Where a new route point's id comes from. A test sets its own. */
    internal var newPointId: () -> String = { UUID.randomUUID().toString() }

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PointsUiState> = combine(localPoints.sets, selection.held, routes.active, routeSelection.held, local) { sets, held, open, heldRoute, local ->
        PointsUiState(
            sets = sets.map { it.toRow() },
            held = held?.let { h -> heldOf(sets.firstOrNull { it.set.id == h.setId }, h.pointId, open, heldRoute) },
            importing = local.importing, error = local.error, note = local.note,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PointsUiState())

    // -- Importing -----------------------------------------------------------------------------------------------

    /** Reads [bytes], the `.LPS` file called [fileName], into a new set. A file that is not local points is refused in words, and nothing is saved. */
    fun importFile(bytes: ByteArray, fileName: String) {
        local.update { it.copy(importing = true, error = null, note = null) }
        viewModelScope.launch {
            val outcome = try {
                withContext(worker) { repository.import(bytes, fileName) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failure in the database or the reader is the app's to word: nothing from inside it is shown to a person.
                ImportOutcome.Refused("That file could not be read.")
            }
            local.update {
                when (outcome) {
                    is ImportOutcome.Imported -> it.copy(importing = false, note = "Imported ${outcome.set.name}: ${pointsIn(outcome.set.points.size)}.")
                    is ImportOutcome.Refused -> it.copy(importing = false, error = outcome.message)
                }
            }
        }
    }

    /** The file could not be got from the picker (it vanished, was too big, was not readable): [message] says why, in words. */
    fun importFailed(message: String) = local.update { it.copy(importing = false, error = message, note = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissNote() = local.update { it.copy(note = null) }

    // -- The sets ---------------------------------------------------------------------------------------------------

    fun toggleVisible(uuid: String) = views.toggle(uuid)

    /** Draws a set in [color], one of the palette (anything else is not a colour this screen offers, and is ignored). */
    fun recolor(uuid: String, color: String) {
        if (color in RouteColors.PALETTE) views.recolor(uuid, color)
    }

    /** Renames a set. Null when it is done, else the words for the field (the server refuses a set with no name). */
    fun rename(uuid: String, name: String): String? {
        if (name.isBlank()) return "A set needs a name."
        viewModelScope.launch { repository.rename(uuid, name) }
        return null
    }

    fun delete(uuid: String) {
        if (selection.held.value?.setId == uuid) selection.clear()
        viewModelScope.launch { repository.delete(uuid) }
    }

    fun resolve(copyUuid: String, how: SyncEngine.Resolution) {
        viewModelScope.launch { conflicts.resolve(RecordKind.POINT_SET, copyUuid, how) }
    }

    // -- The held point -------------------------------------------------------------------------------------------

    fun select(setId: String, pointId: String) = selection.select(setId, pointId)

    fun deselect() = selection.clear()

    /**
     * Moves the held route point onto the held local point, with its name and charted elevation, as typing its name does on the web. Nothing happens when
     * no route point is held (the screen does not offer it then).
     */
    fun useForHeldRoutePoint() {
        val point = state.value.held ?: return
        val heldRoute = routeSelection.held.value ?: return
        val pointId = heldRoute.pointId ?: return
        if (routes.active.value?.route(heldRoute.routeId)?.points?.none { it.id == pointId } != false) return
        routes.edit("Use local point") { set ->
            set.mapRoute(heldRoute.routeId) {
                SketchOps.rename(it, pointId, point.rawName.uppercase(), snapTo = point.at.lat to point.at.lon, chartElevationFt = point.elevationFt)
            }
        }
        local.update { it.copy(error = null, note = "Moved the route point onto ${point.title} (${point.setName}).") }
    }

    /** Adds the held local point to the end of the route being worked on, as a turn point carrying its name and charted elevation, and holds it. */
    fun addToHeldRoute() {
        val point = state.value.held ?: return
        val open = routes.active.value ?: return
        val route = routeToAddTo(open, routeSelection.held.value) ?: return
        val id = newPointId()
        routes.edit("Add local point") { set ->
            set.mapRoute(route.id) {
                SketchOps.appendAmps(it, point.at.lat, point.at.lon, name = point.rawName.uppercase(), ptType = "turn", chartElevationFt = point.elevationFt, newPointId = { id })
            }
        }
        routeSelection.select(route.id, id)
        local.update { it.copy(error = null, note = "Added ${point.title} to ${route.name}.") }
    }

    // -- What the screen is told ----------------------------------------------------------------------------------------

    /** The route a point is added to: the one being worked on, or the only one there is. */
    private fun routeToAddTo(open: RouteSet, held: RouteHeld?) = held?.routeId?.let(open::route) ?: open.routes.singleOrNull()

    private fun heldOf(set: LoadedPointSet?, pointId: String, open: RouteSet?, heldRoute: RouteHeld?): HeldPointUi? {
        val point = set?.set?.point(pointId) ?: return null
        val route = heldRoute?.routeId?.let { open?.route(it) }
        val routePoint = heldRoute?.pointId?.let { id -> route?.points?.firstOrNull { it.id == id } }
        return HeldPointUi(
            setId = set.set.id, setName = set.set.name, pointId = point.id, rawName = point.name, at = point.at, elevationFt = point.elevationFt,
            description = point.description, group = point.group,
            grid = MgrsConverter.toMgrs(point.lat, point.lon)?.format() ?: "${oneDecimal(point.lat)}, ${oneDecimal(point.lon)}",
            latLon = "%.5f, %.5f".format(java.util.Locale.ROOT, point.lat, point.lon),
            useFor = if (route != null && routePoint != null) "${route.name}: ${routePoint.name?.takeIf { it.isNotBlank() } ?: "unnamed point"}" else null,
            addTo = open?.let { routeToAddTo(it, heldRoute) }?.name,
        )
    }

    private fun LoadedPointSet.toRow() = PointSetRowUi(set.id, set.name, set.pointCount, color, visible, sync, conflictOf)

    private fun pointsIn(n: Int) = if (n == 1) "1 point" else "$n points"
}
