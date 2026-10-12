package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.LoadedPointSet
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.PointSelection
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.SessionPointSets
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.data.ThreatFileLog
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/** One set of local points in the list: how it is shown on this device and where it is kept (the Library, or this session only). */
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
    /** Held for this session only: not saved, gone at sign-out (*Save to Library* keeps it). */
    val inSession: Boolean = false,
) {
    /** `1,234 points`, and `hidden` when it is not on the map. */
    val meta: String get() = (if (pointCount == 1) "1 point" else "${withCommas(pointCount.toLong())} points") + if (visible) "" else " · hidden"
}

/** An AMPS mission kept in the Library with its file (the Imports panel's *Mission files*): opened in the Routes panel. */
data class MissionFileUi(val uuid: String, val name: String, val fileName: String, val routeCount: Int, val conflictOf: String?) {
    /** `2 routes`. */
    val meta: String get() = countOf(routeCount, "route")
}

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
    /** The missions kept in the Library. */
    val missions: List<MissionFileUi> = emptyList(),
    /** The threat files brought into the picture since it was last cleared; none when the account has no threats. */
    val threatFiles: List<ThreatFileImport> = emptyList(),
) {
    /** How many things the panel lists: its badge and its header say it. */
    val itemCount: Int get() = sets.size + missions.size + threatFiles.size
}

/**
 * The Imports panel (docs/native-design: Imports): the sets of local points, saved or held for this session ([SessionPointSets]), showing and hiding each on the
 * map, the colour it is drawn in, naming, saving a session's set to the Library, deleting; the missions kept with their files; the threat files brought in; and
 * the point being held and what can be done with it for the route being worked on. Files come in through the import review ([IncomingViewModel]), not here.
 * The sets and how they are shown are [LocalPoints]'; the held point is [PointSelection]'s, which the map draws too, so this screen does not know about the map.
 * What an action came to is said in the frame's toasts ([notices]).
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
    private val sessionPoints: SessionPointSets,
    routeSets: RouteRepository,
    threatFiles: ThreatFileLog,
) : ViewModel() {
    /** Where a new route point's id comes from. A test sets its own. */
    internal var newPointId: () -> String = { UUID.randomUUID().toString() }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** What an action came to, in words, for the frame's toasts. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Whether the account has threats ([allowThreats]): its threat files are listed only then, as its Threats panel is offered only then. */
    private val threatsAllowed = MutableStateFlow(false)

    /** Says whether the signed-in account has threats. */
    fun allowThreats(allowed: Boolean) {
        threatsAllowed.value = allowed
    }

    private val missions = routeSets.observe().map { list ->
        list.filter { it.isMission }.map { MissionFileUi(it.uuid, it.name, it.fileName ?: "${it.name}.msnx", it.routeCount, it.conflictOf) }
    }

    private val files = combine(threatFiles.files, threatsAllowed) { files, allowed -> if (allowed) files else emptyList() }

    private val held = combine(selection.held, routes.active, routeSelection.held) { held, open, heldRoute -> Triple(held, open, heldRoute) }

    val state: StateFlow<PointsUiState> = combine(localPoints.sets, held, missions, files) { sets, (held, open, heldRoute), missions, files ->
        PointsUiState(
            sets = sets.map { it.toRow() },
            held = held?.let { h -> heldOf(sets.firstOrNull { it.set.id == h.setId }, h.pointId, open, heldRoute) },
            missions = missions, threatFiles = files,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PointsUiState())

    // -- The sets ---------------------------------------------------------------------------------------------------

    fun toggleVisible(uuid: String) = views.toggle(uuid)

    /** Draws a set in [color], one of the palette (anything else is not a colour this screen offers, and is ignored). */
    fun recolor(uuid: String, color: String) {
        if (color in RouteColors.PALETTE) views.recolor(uuid, color)
    }

    /** Renames a set, saved or held for this session. Null when it is done, else the words for the field (the server refuses a set with no name). */
    fun rename(uuid: String, name: String): String? {
        if (name.isBlank()) return "A set needs a name."
        if (sessionPoints.holds(uuid)) {
            sessionPoints.rename(uuid, name)
            return null
        }
        viewModelScope.launch { quietly("The set could not be renamed.") { repository.rename(uuid, name) } }
        return null
    }

    /** Deletes a saved set (asked about first, on the screen). */
    fun delete(uuid: String) {
        if (selection.held.value?.setId == uuid) selection.clear()
        viewModelScope.launch { quietly("The set could not be deleted.") { repository.delete(uuid) } }
    }

    /** Lets go of a set held for this session: nothing was saved, so nothing is deleted. */
    fun removeFromSession(uuid: String) {
        if (selection.held.value?.setId == uuid) selection.clear()
        sessionPoints.remove(uuid)?.let { _notices.tryEmit("Removed ${it.name} from this session.") }
    }

    /** Saves a set held for this session to the Library, under its own id (so its colour and a held point stay), and it syncs with the account from then on. */
    fun saveToLibrary(uuid: String) {
        val set = sessionPoints.sets.value.firstOrNull { it.id == uuid } ?: return
        viewModelScope.launch {
            quietly("The set could not be saved.") {
                val saved = repository.save(set)
                // Let go of the session's copy only once the Library lists the set: Room lists a new record a moment after the save returns, and in between the
                // set would be in neither list, so settling the views ([LocalPoints]) would drop its colour and whether it is shown.
                withTimeoutOrNull(LISTED_WITHIN_MS) { repository.observeSets().first { stored -> stored.any { it.set.id == uuid } } }
                sessionPoints.remove(uuid)
                _notices.tryEmit("Saved ${saved.name} to your Library.")
            }
        }
    }

    /** Runs [block], saying [failed] in a toast when it fails: a failure in the database is the app's to word, and never ends the app. */
    private suspend fun quietly(failed: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _notices.tryEmit(failed)
        }
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
        _notices.tryEmit("Moved the route point onto ${point.title} (${point.setName}).")
    }

    /** Adds the held local point to the end of the route being worked on, as a turn point carrying its name and charted elevation, and holds it. */
    fun addToHeldRoute() {
        val point = state.value.held ?: return
        val open = routes.active.value?.takeIf { it.mission == null } ?: return                 // an imported mission's points are its file's: none is added to the end
        val route = routeToAddTo(open, routeSelection.held.value) ?: return
        val id = newPointId()
        routes.edit("Add local point") { set ->
            set.mapRoute(route.id) {
                SketchOps.appendAmps(it, point.at.lat, point.at.lon, name = point.rawName.uppercase(), ptType = "turn", chartElevationFt = point.elevationFt, newPointId = { id })
            }
        }
        routeSelection.select(route.id, id)
        _notices.tryEmit("Added ${point.title} to ${route.name}.")
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
            addTo = open?.takeIf { it.mission == null }?.let { routeToAddTo(it, heldRoute) }?.name,
        )
    }

    private fun LoadedPointSet.toRow() = PointSetRowUi(set.id, set.name, set.pointCount, color, visible, sync, conflictOf, inSession)

    private companion object {
        /** How long a set saved from the session is waited for in the Library's list before the session lets go of it anyway. */
        const val LISTED_WITHIN_MS = 10_000L
    }
}
