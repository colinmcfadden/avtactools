package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.ExportResult
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.Units
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.data.PlanningOutcome
import app.ezpztac.data.HandoffFormat
import app.ezpztac.data.RouteExport
import app.ezpztac.data.RouteHandoffExport
import app.ezpztac.data.RoutePlanning
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSetSummary
import app.ezpztac.data.RouteSketching
import app.ezpztac.data.SketchFinish
import app.ezpztac.model.LatLon
import app.ezpztac.model.LocalPointMatch
import app.ezpztac.model.LocalPointNames
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.planning.PointSpec
import app.ezpztac.planning.RouteCalc
import app.ezpztac.planning.SketchOps
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

/** One named point of the held route, as the nav log shows it: what it is, when and how far, and what can be changed about it. */
data class PlanPointUi(
    val id: String,
    val name: String,
    /** `target`, `ip` or `turn` (anything else a mission file carries is shown as it is). */
    val ptType: String?,
    val first: Boolean,
    /** The values in force at this point, as the row's form starts: the point's own override, else the route's. */
    val values: PointDraft,
    /** `12:30:00`, or `--:--:--` when no point of the route carries a clock. */
    val clock: String,
    /** This point holds the clock the others are timed from (the TOT). */
    val hasClock: Boolean,
    /** `START`, or `3.1 nm · 045°T · 98 kt · 1320' MSL`: the leg that arrives here, the web's line. */
    val facts: String,
    /** `1:52`: the time since the first point. */
    val elapsed: String,
    /** The point the person is holding (on the map, or by tapping its row): its form is open. */
    val held: Boolean,
    /** Where it is, as the grid crews read it (or its degrees where there is no grid). */
    val grid: String,
)

/** A held point that only shapes the line: it can be made a named point. */
data class ShapingPointUi(val id: String, val held: Boolean, val grid: String = "")

/** The route the person is working on: its plan, its nav log, and what is wrong with it. */
data class RouteDetailUi(
    val routeId: String,
    val name: String,
    val aircraft: String,
    /** The route-wide values as the plan form starts. */
    val plan: PlanDraft,
    val points: List<PlanPointUi>,
    /** How many points only shape the line. */
    val shapingPoints: Int,
    /** The held point, when it is one that only shapes the line. */
    val heldShaping: ShapingPointUi?,
    /** `Total 12.3 nm · 8:15 · 640 lb`, once there are two named points. */
    val totals: String?,
    /** What the planner says is wrong with the route, in its words. */
    val warnings: List<String>,
    /** Ground elevations have been fetched for this route. */
    val hasElevations: Boolean,
)

/** A mission built for AMPS, ready to be handed to another app: the screen that has a context shares it. */
class ExportFile(val fileName: String, val bytes: ByteArray)

/** What a route can ask the server for. */
enum class PlanningKind { WINDS, ELEVATIONS }

/** What fetching came to, for a line under the buttons: what was found, or why not. */
data class PlanningNote(val text: String, val failed: Boolean)

data class RoutesUiState(
    val sets: List<RouteSetRow> = emptyList(),
    val open: OpenSetUi? = null,
    val drawing: RouteDrawingUi? = null,
    /** The route being worked on, when the open set has one held. */
    val detail: RouteDetailUi? = null,
    /** Winds or elevations are being fetched for the held route (one at a time). */
    val fetching: PlanningKind? = null,
    /** What the last fetch came to, until it is dismissed or the route held changes. */
    val note: PlanningNote? = null,
    /** A mission is being built for AMPS. */
    val exporting: Boolean = false,
    /** Something to know about the file just made before it is sent (it will open as another airframe than the one planned), until dismissed. */
    val exportWarning: String? = null,
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
    private val planning: RoutePlanning,
    private val export: RouteExport,
    private val handoff: RouteHandoffExport,
    localPoints: LocalPoints,
) : ViewModel() {
    private data class Local(
        val creating: Boolean = false, val error: String? = null, val fetching: PlanningKind? = null, val note: PlanningNote? = null, val noteFor: String? = null,
        val exporting: Boolean = false, val exportWarning: String? = null,
    )

    /** Where a mission is built: building one takes a moment of work on the whole template, so it is not done on the main thread. A test sets its own. */
    internal var worker: CoroutineDispatcher = Dispatchers.Default

    private val _exports = MutableSharedFlow<ExportFile>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A mission that has been built, once for each: the screen shares it. */
    val exports: SharedFlow<ExportFile> = _exports.asSharedFlow()

    private val local = MutableStateFlow(Local())
    private val depths = combine(session.undoDepth, session.redoDepth) { undo, redo -> undo to redo }

    val state: StateFlow<RoutesUiState> = combine(repository.observe(), session.active, selection.held, sketching.draft, local) { summaries, set, held, draft, local ->
        RoutesUiState(
            sets = summaries.map { it.toRow(isOpen = it.uuid == set?.id) },
            open = set?.let { openOf(it, held?.routeId, summaries.firstOrNull { s -> s.uuid == it.id }) },
            drawing = draft?.takeIf { it.setId == set?.id }?.let { RouteDrawingUi(it.points.size, it.canFinish) },
            detail = set?.let { detailOf(it, held) },
            fetching = local.fetching.takeIf { local.noteFor == held?.routeId },
            // A line about one route's winds is not shown under another route.
            note = local.note.takeIf { local.noteFor == held?.routeId },
            exporting = local.exporting,
            exportWarning = local.exportWarning,
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

    private fun detailOf(set: RouteSet, held: RouteHeld?): RouteDetailUi? {
        val route = held?.routeId?.let(set::route) ?: return null
        val result = RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations)
        val rows = result.points.mapIndexed { i, p ->
            val id = p.id.orEmpty()
            PlanPointUi(
                id = id, name = p.name.orEmpty(), ptType = p.ptType, first = i == 0, values = PointDraft.of(route.plan, id),
                clock = RouteCalc.formatClock(p.clockTime), hasClock = p.hasClock,
                facts = if (i == 0) "START" else legFacts(p), elapsed = RouteCalc.formatDuration(p.elapsedSec), held = id == held.pointId, grid = gridOf(p.lat, p.lon),
            )
        }
        val shaping = route.points.filter { it.kind == RoutePoint.KIND_SHAPING }
        val totals = result.totals?.let { t ->
            "Total ${oneDecimal(t.distNm)} nm · ${RouteCalc.formatDuration(t.timeSec)} · ${t.fuelLb?.let { "${RouteCalc.jsRound(it).toLong()} lb" } ?: "-- lb"}"
        }
        return RouteDetailUi(
            routeId = route.id, name = route.name, aircraft = route.plan.aircraft, plan = PlanDraft.of(route.plan), points = rows,
            shapingPoints = shaping.size, heldShaping = shaping.firstOrNull { it.id != null && it.id == held.pointId }?.let { ShapingPointUi(it.id.orEmpty(), held = true, grid = gridOf(it.lat, it.lon)) },
            totals = totals, warnings = result.warnings, hasElevations = route.elevations.isNotEmpty(),
        )
    }

    /** The MGRS grid of a position, computed on the device, or its degrees where there is none (the poles). */
    private fun gridOf(lat: Double, lon: Double): String = MgrsConverter.toMgrs(lat, lon)?.format() ?: "${oneDecimal(lat)}, ${oneDecimal(lon)}"

    /** The web's line for the leg that arrives at [p]: distance, course, ground speed and altitude, `--` where it cannot say. */
    private fun legFacts(p: app.ezpztac.model.PlanPoint): String {
        val distance = p.legDistNm?.let(::oneDecimal) ?: "--"
        val course = p.legCourseTrueDeg?.let { RouteCalc.jsRound(it).toLong().toString().padStart(3, '0') } ?: "---"
        val speed = p.legGsKts?.let { RouteCalc.jsRound(it).toLong().toString() } ?: "--"
        val altitude = p.mslFt?.let { " · ${RouteCalc.jsRound(it).toLong()}' MSL" }.orEmpty()
        return "$distance nm · $course°T · $speed kt$altitude"
    }

    private fun rowOf(route: SketchRoute, selected: Boolean) = RouteRowUi(
        id = route.id, name = route.name, color = route.color, visible = route.visible, pointCount = route.points.size,
        routePointCount = route.points.count { it.kind != RoutePoint.KIND_SHAPING && !it.id.isNullOrEmpty() },
        summary = summaryOf(route), selected = selected,
    )

    // -- Sets ----------------------------------------------------------------------------------------------------

    fun startCreating() = local.update { it.copy(creating = true, error = null) }

    fun cancelCreating() = local.update { it.copy(creating = false, error = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissNote() = local.update { it.copy(note = null) }

    fun dismissExportWarning() = local.update { it.copy(exportWarning = null) }

    // -- Export for AMPS ---------------------------------------------------------------------------------------------

    /** Builds the AMPS mission of every route in the open set. */
    fun exportSet() = exportRoutes(routeId = null)

    /** Builds the AMPS mission of one route of the open set. */
    fun exportRoute(routeId: String) = exportRoutes(routeId)

    private fun exportRoutes(routeId: String?) {
        val set = session.active.value ?: return
        if (local.value.exporting) return
        val routes = export.routesOf(set, routeId)
        local.update { it.copy(exporting = true, error = null, exportWarning = null) }
        viewModelScope.launch {
            val result = try {
                withContext(worker) { export.build(routes, LocalDate.now()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ExportResult.Refused("The mission could not be built.")
            }
            when (result) {
                is ExportResult.Ready -> {
                    local.update { it.copy(exporting = false, exportWarning = result.warning) }
                    _exports.emit(ExportFile(result.fileName, result.bytes))
                }
                is ExportResult.Refused -> local.update { it.copy(exporting = false, error = result.message) }
            }
        }
    }

    /**
     * Hands one route of the open set to another app as a GPX or a Garmin flight plan (every point, shaping points too). Built here, shared by the screen like a mission;
     * nothing is sent by the app. A route that has gone from the set (deleted on another device, say) has nothing to share and is said so.
     */
    fun shareRoute(routeId: String, format: HandoffFormat) {
        val set = session.active.value ?: return
        if (local.value.exporting) return
        val route = set.route(routeId)
        if (route == null) {
            local.update { it.copy(error = "That route is no longer in this set.") }
            return
        }
        local.update { it.copy(exporting = true, error = null, exportWarning = null) }
        viewModelScope.launch {
            val result = try {
                withContext(worker) { handoff.build(route, format) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ExportResult.Refused("The route could not be written.")
            }
            when (result) {
                is ExportResult.Ready -> {
                    local.update { it.copy(exporting = false) }
                    _exports.emit(ExportFile(result.fileName, result.bytes))
                }
                is ExportResult.Refused -> local.update { it.copy(exporting = false, error = result.message) }
            }
        }
    }

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

    // -- Winds and ground elevations ---------------------------------------------------------------------------------

    /** Fetches the wind at each named point of the held route and merges it into the plan. */
    fun fetchWinds() = fetch(PlanningKind.WINDS) { set, route -> planning.fetchWinds(set, route) }

    /** Fetches the ground elevation at each named point of the held route, so altitudes can be read AGL and MSL. */
    fun fetchElevations() = fetch(PlanningKind.ELEVATIONS) { set, route -> planning.fetchElevations(set, route) }

    private fun fetch(kind: PlanningKind, ask: suspend (setId: String, routeId: String) -> PlanningOutcome) {
        val set = session.active.value ?: return
        val routeId = selection.held.value?.routeId?.takeIf { set.route(it) != null } ?: return
        if (local.value.fetching != null) return                                          // one at a time: the server's answers would otherwise cross
        local.update { it.copy(fetching = kind, note = null, noteFor = routeId) }
        viewModelScope.launch {
            val note = try {
                when (val outcome = ask(set.id, routeId)) {
                    is PlanningOutcome.Done -> PlanningNote(outcome.message, failed = false)
                    is PlanningOutcome.Failed -> PlanningNote(outcome.message, failed = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlanningNote("That could not be fetched.", failed = true)
            }
            local.update { it.copy(fetching = null, note = note) }
        }
    }

    // -- The held route's plan and points ------------------------------------------------------------------------

    /**
     * Applies the route-wide plan form to [routeId]. Null when it is applied (one undo step); else why not, in words for the form itself: the screen
     * shows it beside the fields, because a banner at the top of the sheet can be scrolled out of sight while typing.
     */
    fun applyPlan(routeId: String, draft: PlanDraft): String? {
        if (session.active.value?.route(routeId) == null) return "That route is no longer here."
        return when (val checked = draft.check()) {
            is PlanDraft.Checked.Refused -> checked.message
            is PlanDraft.Checked.Valid -> {
                session.edit("Change plan") { set -> set.mapRoute(routeId) { SketchOps.withPlan(it, checked.patch) } }
                null
            }
        }
    }

    /**
     * Applies what was typed for one point (altitude, speed and wind to it, and its clock) to [routeId]: only what was changed from [before], in one undo step.
     * Null when it is applied (or nothing was changed); else why not, in words for the row.
     */
    fun applyPoint(routeId: String, pointId: String, typed: PointDraft, before: PointDraft, first: Boolean): String? {
        if (session.active.value?.route(routeId) == null) return "That route is no longer here."
        return when (val checked = typed.check(before, first)) {
            is PointDraft.Checked.Refused -> checked.message
            is PointDraft.Checked.Valid -> {
                if (!checked.isEmpty) {
                    session.edit("Change point") { set ->
                        set.mapRoute(routeId) { route ->
                            var next = route
                            val patch = checked.patch
                            if (patch.altitude != null || patch.airspeed != null || patch.wind != null) next = SketchOps.withOverride(next, pointId, patch)
                            if (checked.clock != null) next = SketchOps.withClock(next, pointId, checked.clock)
                            next
                        }
                    }
                }
                null
            }
        }
    }

    /** Holds [pointId] of the held route (its form opens, and the map marks it), or puts it down when it is held already. */
    fun selectPoint(pointId: String) {
        val held = selection.held.value ?: return
        selection.holdPoint(if (held.pointId == pointId) null else pointId)
    }

    /** Every point of every set of local points, hidden or not, for a name typed on a route point to be looked up in (as the web looks it up in all that are loaded). */
    private val localNames: StateFlow<LocalPointNames> = localPoints.sets.map { sets -> LocalPointNames(sets.flatMap { it.set.points }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalPointNames(emptyList()))

    /** The local point [typed] would name, or null when it names none: what the name field says before the name is saved. */
    fun localPointNamed(typed: String): LocalPointMatch? = localNames.value.match(typed).takeIf { it.at != null }

    /**
     * Renames a point, in capitals as the web does. An empty name is allowed (the point is then known by its place in the route). A name that is a local
     * point's puts the point on it, with its charted elevation, as the web does (a local point with no elevation leaves the point with none).
     */
    fun renamePoint(routeId: String, pointId: String, name: String) {
        val match = localNames.value.match(name)
        session.edit("Rename point") { set ->
            set.mapRoute(routeId) { SketchOps.rename(it, pointId, match.name, snapTo = match.at?.let { at -> at.lat to at.lon }, chartElevationFt = match.chartElevationFt) }
        }
    }

    /** Changes what a named point is: `target`, `ip` or `turn`. */
    fun setPointType(routeId: String, pointId: String, ptType: String) {
        session.edit("Change point type") { set -> set.mapRoute(routeId) { SketchOps.designate(it, pointId, PointSpec(RoutePoint.KIND_AMPS, ptType = ptType)) } }
    }

    /** Makes a named point only shape the line. Refused (in words) for one of the last two named points: a leg needs two ends. */
    fun makeShaping(routeId: String, pointId: String) {
        val route = session.active.value?.route(routeId) ?: return
        if (SketchOps.designate(route, pointId, PointSpec(RoutePoint.KIND_SHAPING)) == route) {
            return fail("A route needs at least two named points.")
        }
        session.edit("Make shaping point") { set -> set.mapRoute(routeId) { SketchOps.designate(it, pointId, PointSpec(RoutePoint.KIND_SHAPING)) } }
    }

    /** Moves a point of the held route [northFt] feet north and [eastFt] east (negative: south, west), as a drag would: it forgets a charted elevation it had snapped to. */
    fun nudgePoint(routeId: String, pointId: String, northFt: Double, eastFt: Double) = movePoint(routeId, pointId) { at ->
        GraphicEdits.offset(at, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET)
    }

    /** Puts a point at the crosshair, [at]. */
    fun pointToCrosshair(routeId: String, pointId: String, at: LatLon?) {
        if (at == null) return fail("Move the map to where the point should go first.")
        fail(null)
        movePoint(routeId, pointId) { at }
    }

    /** Puts a point at a grid or coordinate the person typed. Null when it is moved, else the words for the field. */
    fun pointToText(routeId: String, pointId: String, text: String): String? = when (val place = PlaceSearch.resolve(text)) {
        is PlaceResult.Found -> { movePoint(routeId, pointId) { place.at }; null }
        is PlaceResult.NotUnderstood -> place.message
    }

    private fun movePoint(routeId: String, pointId: String, to: (LatLon) -> LatLon) {
        val route = session.active.value?.route(routeId) ?: return
        if (route.points.none { it.id == pointId }) return
        session.edit("Move point") { set ->
            set.mapRoute(routeId) { r ->
                val p = r.points.firstOrNull { it.id == pointId } ?: return@mapRoute r
                val moved = to(LatLon(p.lat, p.lon))
                // The ground elevation fetched for the old place is not this place's: AGL would be measured from the wrong ground. The web keeps it (a
                // drag leaves `elevations` alone); here it is dropped until the next fetch, and the altitudes say they have no ground to go by.
                SketchOps.move(r, pointId, moved.lat, moved.lon, null).let { m -> if (pointId in m.elevations) m.copy(elevations = m.elevations - pointId) else m }
            }
        }
    }

    /** Where a new point's id comes from. A test sets its own. */
    internal var newPointId: () -> String = { java.util.UUID.randomUUID().toString() }

    /** Adds a point that only shapes the line at the crosshair, [at], in the leg it is nearest to, and holds it so it can be moved into place. */
    fun addShapingPoint(routeId: String, at: LatLon?) {
        if (at == null) return fail("Move the map to where the point should go first.")
        val route = session.active.value?.route(routeId) ?: return
        val id = newPointId()
        if (SketchOps.insertShaping(route, at.lat, at.lon) { id } == route) return fail("A route needs two points before the line can be bent.")
        fail(null)
        session.edit("Add shaping point") { set -> set.mapRoute(routeId) { SketchOps.insertShaping(it, at.lat, at.lon) { id } } }
        selection.holdPoint(id)
    }

    /** Makes a point that only shaped the line a named one: a turn point, called `.CP` until it is named. */
    fun makeNamed(routeId: String, pointId: String) {
        session.edit("Make route point") { set -> set.mapRoute(routeId) { SketchOps.designate(it, pointId, PointSpec(RoutePoint.KIND_AMPS, ptType = "turn")) } }
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
