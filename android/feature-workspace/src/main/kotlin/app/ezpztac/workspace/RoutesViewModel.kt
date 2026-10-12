package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.designsystem.EzpzIcons
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
import app.ezpztac.planning.PlanPatch
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One saved set in a list: the Library's, or the session's "also open". */
data class RouteSetRow(
    val uuid: String,
    val name: String,
    val routeCount: Int,
    val sync: SyncStatus,
    /** The set this is the user's kept version of, when another device changed it too. */
    val conflictOf: String?,
    /** The set on the map (the open one, [RoutesUiState.open]). */
    val isOpen: Boolean,
    /** An imported AMPS mission, kept with its file, not a set drawn here. */
    val isMission: Boolean = false,
    /** Every point of its routes; null for a mission, whose file is not read to draw a list. */
    val pointCount: Int? = null,
    /** Whether the server has it, as the design's chips say it ([routeSetChips]). */
    val chips: List<LzChip> = emptyList(),
) {
    /** `2 routes · 91 points`, or `2 routes` where the points are not known. */
    val summary: String get() = listOfNotNull(countOf(routeCount, "route"), pointCount?.let { countOf(it, "point") }).joinToString(" · ")
}

/**
 * The pages of the Routes panel: today only the route sets open now (every set saved is the Library's, a page of its own since S7, and a route's plan is a page
 * of its own over the frame). Kept as a page so a panel page can join it as the panel grows.
 */
enum class RoutesPage { HOME }

/**
 * The chips of a set of routes (the web's state chip, with the sync state where the web says whether it is saved: every set here is saved as it changes,
 * docs/ANDROID_SCREENS_PLAN.md): *Imported* for a mission brought in from a file, then whether the server has it.
 */
internal fun routeSetChips(sync: SyncStatus?, conflictOf: String?, isMission: Boolean): List<LzChip> = listOfNotNull(
    LzChip("Imported", ChipTone.INFO, EzpzIcons.download).takeIf { isMission },
    when {
        conflictOf != null || sync == SyncStatus.CONFLICT -> LzChip("Conflict copy", ChipTone.ERROR)
        sync == SyncStatus.SYNCED -> LzChip("Synced", ChipTone.SUCCESS)
        else -> LzChip("Waiting to sync", ChipTone.WARNING)                                // null too: made a moment ago, the list not caught up yet
    },
)

/** `1 route`, `4 points`. */
internal fun countOf(n: Int, one: String): String = if (n == 1) "1 $one" else "$n ${one}s"

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
    /** `12.3 nm · 8:15 · 640 lb` (the fuel when it can be said), once the route has two named points to run between. */
    val summary: String?,
    val selected: Boolean,
) {
    /** What its row says under its name: the totals, or how many points it has until it has a leg. */
    val meta: String get() = summary ?: countOf(pointCount, "point")
}

/** The open set, with what can be done to it from here. */
data class OpenSetUi(
    val uuid: String,
    val name: String,
    val routes: List<RouteRowUi>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val sync: SyncStatus?,
    val conflictOf: String?,
    /** An imported AMPS mission: its routes are part of its file, so they cannot be drawn, renamed or removed here, and what is changed is written back into the file. */
    val isMission: Boolean = false,
    /** Still called by its made-up name (`SKETCHED ROUTES`): *Save* names it. */
    val madeUpName: Boolean = false,
    val chips: List<LzChip> = emptyList(),
    /** What naming it keeps, in the save sheet's words: its routes counted, and their plans. */
    val saveContents: List<String> = emptyList(),
) {
    /** Every point of its routes, shaping points included. */
    val pointCount: Int get() = routes.sumOf { it.pointCount }

    /** `1 route · 4 points`. */
    val summary: String get() = "${countOf(routes.size, "route")} · ${countOf(pointCount, "point")}"

    /** *Save* names a set of sketched routes that still has its made-up name, once it has a route to save. */
    val canSave: Boolean get() = madeUpName && !isMission && routes.isNotEmpty() && conflictOf == null
}

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
    /** `12.3 nm · 8:15 · 640 lb` (the fuel `--` when it cannot be said), once there are two named points. */
    val totals: String?,
    /** What the planner says is wrong with the route, in its words. */
    val warnings: List<String>,
    /** Ground elevations have been fetched for this route. */
    val hasElevations: Boolean,
    /** The route is part of an imported mission: a point's type is fixed by the file, and a point put on the line is a named one that splits a leg. */
    val isMission: Boolean = false,
    /** `#RRGGBB`: the route's colour, for its points' symbols. */
    val color: String = "",
)

/**
 * A mission built for AMPS, or a route for another app, ready to be handed over: the screen that has a context shares it. [withThreats]: the person asked for
 * the threat picture to go beside it as a `.ths` (an AMPS mission only; a GPX or a flight plan never takes one).
 */
class ExportFile(val fileName: String, val bytes: ByteArray, val withThreats: Boolean = false)

/** What a route can ask the server for. */
enum class PlanningKind { WINDS, ELEVATIONS }

/** What fetching [kind] came to, for a line under its button: what was found, or why not. */
data class PlanningNote(val text: String, val failed: Boolean, val kind: PlanningKind = PlanningKind.WINDS)

data class RoutesUiState(
    /** Every set in the Library, the open one included. */
    val sets: List<RouteSetRow> = emptyList(),
    /** The set on the map. */
    val open: OpenSetUi? = null,
    /** The other sets in this session, in the order they were opened ("also open"). */
    val alsoOpen: List<RouteSetRow> = emptyList(),
    /** How many sets are open, the one on the map included. */
    val openCount: Int = 0,
    val page: RoutesPage = RoutesPage.HOME,
    /** The held route's plan is showing, as a page of its own over the map ([detail] is what it shows). */
    val planOpen: Boolean = false,
    /** The names of the sets other than the open one, so naming it can say when a name is taken. */
    val otherNames: List<String> = emptyList(),
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
    /** An AMPS export takes the threat picture beside it as a `.ths` (the set card's box; on until the person says otherwise). */
    val includeThreats: Boolean = true,
    val error: String? = null,
)

/**
 * The Routes panel (docs/native-design: Routes, RoutePlan, SaveRoutes): the route sets open now ([OpenRouteSets]), the routes of the one on the map, and every
 * set in the Library. The open set is the [RouteSession]'s, the route held is [RouteSelection]'s and a route being drawn is [RouteSketching]'s; this is what a
 * screen shows of them and how it asks for changes. Every change to the open set is one step of its own undo. Opening a set is what makes the map show it (the
 * app watches the session), so this screen does not know about the map.
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
    private val openSets: OpenRouteSets,
) : ViewModel() {
    private data class Local(
        val error: String? = null, val fetching: PlanningKind? = null, val note: PlanningNote? = null, val noteFor: String? = null,
        val exporting: Boolean = false, val exportWarning: String? = null, val page: RoutesPage = RoutesPage.HOME, val includeThreats: Boolean = true,
        /** The route whose plan page is open, while it is. */
        val planFor: String? = null,
    )

    /** Where a mission is built: building one takes a moment of work on the whole template, so it is not done on the main thread. A test sets its own. */
    internal var worker: CoroutineDispatcher = Dispatchers.Default

    private val _exports = MutableSharedFlow<ExportFile>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A mission that has been built, once for each: the screen shares it. */
    val exports: SharedFlow<ExportFile> = _exports.asSharedFlow()

    private val local = MutableStateFlow(Local())
    private val depths = combine(session.undoDepth, session.redoDepth) { undo, redo -> undo to redo }
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** What the panel tells the person in a toast once it has happened: Saved "NIGHT INFIL" to your Library. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * The set made to sketch a route in, and the set that was on the map before it (or null), until that sketch ends. A sketch that ends without a route (cancelled,
     * or dropped because another set was opened) takes the set away again if it is still empty and still called by its made-up name, and puts the set before it back.
     */
    private var madeForSketch: Pair<String, String?>? = null

    /** One thing the panel asks for at a time, as the LZ/PZ panel does: a second tap while a set is being made or opened is not a second set. */
    private var busy = false

    private val listed = combine(repository.observe(), openSets.ids) { summaries, open -> summaries to open }

    val state: StateFlow<RoutesUiState> = combine(listed, session.active, selection.held, sketching.draft, local) { (summaries, inSession), set, held, draft, local ->
        val byId = summaries.associateBy { it.uuid }
        val also = inSession.filter { it != set?.id }.mapNotNull { id -> byId[id]?.toRow(isOpen = false) }
        val detail = set?.let { detailOf(it, held) }
        RoutesUiState(
            sets = summaries.map { it.toRow(isOpen = it.uuid == set?.id) },
            open = set?.let { openOf(it, held?.routeId, byId[it.id]) },
            alsoOpen = also,
            openCount = also.size + (if (set != null) 1 else 0),
            page = local.page,
            // The page is the held route's: a route undone out of existence, or put down, takes its page with it.
            planOpen = local.planFor != null && detail?.routeId == local.planFor,
            otherNames = summaries.filter { it.uuid != set?.id }.map { it.name },
            drawing = draft?.takeIf { it.setId == set?.id }?.let { RouteDrawingUi(it.points.size, it.canFinish) },
            detail = detail,
            fetching = local.fetching.takeIf { local.noteFor == held?.routeId },
            // A line about one route's winds is not shown under another route.
            note = local.note.takeIf { local.noteFor == held?.routeId },
            exporting = local.exporting,
            exportWarning = local.exportWarning,
            includeThreats = local.includeThreats,
            error = local.error,
        )
    }.combine(depths) { state, (undo, redo) ->
        state.copy(open = state.open?.copy(canUndo = undo > 0, canRedo = redo > 0))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutesUiState())

    init {
        // What is on the map is open: however it was opened (here, a launch putting back the last one, a mission brought in), it joins the session. A set closed
        // from outside (a sign-out, a mission pack) takes the plan page with it.
        viewModelScope.launch {
            var shown: String? = null
            session.active.collect { set ->
                if (set != null) openSets.add(set.id)
                if (set?.id != shown) local.update { it.copy(planFor = null) }                  // another set, or none: the page was the last one's
                shown = set?.id
            }
        }
        // The plan page is the held route's: when the route held changes (undone out of existence, put down, another held), the page is not opened again by holding it later.
        viewModelScope.launch {
            selection.held.collect { held -> if (held?.routeId != local.value.planFor) local.update { it.copy(planFor = null) } }
        }
        // A sketch that ends without a route takes the set made for it away again (cancelled, or dropped as another set opened).
        viewModelScope.launch {
            sketching.draft.map { it?.setId }.distinctUntilChanged().collect { drawingOn ->
                val (made, before) = madeForSketch ?: return@collect
                if (drawingOn == made) return@collect
                madeForSketch = null
                abandon(made, before)
            }
        }
    }

    private fun RouteSetSummary.toRow(isOpen: Boolean) = RouteSetRow(
        uuid, name, routeCount, sync, conflictOf, isOpen, isMission, pointCount = pointCount, chips = routeSetChips(sync, conflictOf, isMission),
    )

    private fun openOf(set: RouteSet, heldRouteId: String?, listed: RouteSetSummary?) = OpenSetUi(
        uuid = set.id, name = set.name, routes = set.routes.map { rowOf(it, selected = it.id == heldRouteId) },
        canUndo = false, canRedo = false, sync = listed?.sync, conflictOf = listed?.conflictOf, isMission = set.mission != null,
        madeUpName = set.mission == null && RouteSetNames.isMadeUp(set.name), chips = routeSetChips(listed?.sync, listed?.conflictOf, set.mission != null),
        saveContents = saveContentsOf(set),
    )

    /** What naming a set keeps, as the save sheet lists it (the web's `contentsFor`): its routes and points counted, with their distance; then their plans. */
    private fun saveContentsOf(set: RouteSet): List<String> {
        val points = set.routes.sumOf { it.points.size }
        val nm = set.routes.sumOf { RouteCalc.computeRoutePlan(it.points, it.plan, it.elevations).totals?.distNm ?: 0.0 }
        val counted = "${countOf(set.routes.size, "route")}, ${countOf(points, "point")}" + (if (nm > 0) ", ${oneDecimal(nm)} nm" else "")
        return listOf(counted, "Speeds, altitudes, winds, fuel flow and the start time")
    }

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
            "${oneDecimal(t.distNm)} nm · ${RouteCalc.formatDuration(t.timeSec)} · ${t.fuelLb?.let { "${RouteCalc.jsRound(it).toLong()} lb" } ?: "-- lb"}"
        }
        return RouteDetailUi(
            routeId = route.id, name = route.name, aircraft = route.plan.aircraft, plan = PlanDraft.of(route.plan), points = rows,
            shapingPoints = shaping.size, heldShaping = shaping.firstOrNull { it.id != null && it.id == held.pointId }?.let { ShapingPointUi(it.id.orEmpty(), held = true, grid = gridOf(it.lat, it.lon)) },
            totals = totals, warnings = result.warnings, hasElevations = route.elevations.isNotEmpty(), isMission = set.mission != null, color = route.color,
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

    // -- The panel's pages ----------------------------------------------------------------------------------------

    /** Goes to [to]: the sets open now, or every set in the Library. */
    fun showPage(to: RoutesPage) = local.update { it.copy(page = to) }

    /**
     * Opens the plan of [routeId], a route of the open set, as a page of its own: the route is held (the map draws it heavier), and a point already held on it
     * stays held, so its card is the one open.
     */
    fun openPlan(routeId: String) {
        if (session.active.value?.route(routeId) == null) return
        if (selection.held.value?.routeId != routeId) selection.select(routeId)                  // before the page: holding another route closes it
        local.update { it.copy(planFor = routeId, error = null) }
    }

    /** Opens the plan of the route held on the map (the held bar's options for one of its points). */
    fun openHeldPlan() {
        selection.held.value?.routeId?.let(::openPlan)
    }

    /** Back from the plan page to the panel. What is held stays held, so the map shows what was being worked on. */
    fun closePlan() = local.update { it.copy(planFor = null) }

    // -- Sets ----------------------------------------------------------------------------------------------------

    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissNote() = local.update { it.copy(note = null) }

    fun dismissExportWarning() = local.update { it.copy(exportWarning = null) }

    /** Whether an AMPS export takes the threats with it (the box on the set's card). */
    fun includeThreats(on: Boolean) = local.update { it.copy(includeThreats = on) }

    // -- Export for AMPS ---------------------------------------------------------------------------------------------

    /**
     * Builds the AMPS mission of every route in the open set. For an imported mission that is **its own file**, with the changes saved so far written into it (the name gets
     * `_edited`, as the web names it): the file is the mission, so nothing is rebuilt from a template.
     */
    fun exportSet(withThreats: Boolean = false) = if (session.active.value?.mission != null) exportMissionFile(withThreats) else exportRoutes(routeId = null, withThreats)

    private fun exportMissionFile(withThreats: Boolean) {
        val link = session.active.value?.mission ?: return
        if (local.value.exporting) return
        local.update { it.copy(exporting = true, error = null, exportWarning = null) }
        viewModelScope.launch {
            val bytes = try {
                session.flush()                                                    // the changes still in memory are in the file first
                session.active.value?.id?.let { repository.missionFile(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (bytes == null) {
                local.update { it.copy(exporting = false, error = "The mission could not be saved to send.") }
                return@launch
            }
            local.update { it.copy(exporting = false) }
            _exports.emit(ExportFile(link.fileName.replace(Regex("""\.msnx$""", RegexOption.IGNORE_CASE), "") + "_edited.msnx", bytes, withThreats))
        }
    }

    /** Builds the AMPS mission of one route of the open set. */
    fun exportRoute(routeId: String, withThreats: Boolean = false) = exportRoutes(routeId, withThreats)

    private fun exportRoutes(routeId: String?, withThreats: Boolean) {
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
                    _exports.emit(ExportFile(result.fileName, result.bytes, withThreats))
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

    /** Makes an empty set and opens it. A blank [name] is the next made-up one (`SKETCHED ROUTES`), upper-cased as the web names routes. */
    fun createSet(name: String) {
        val title = RouteSetNames.clean(name).ifEmpty { RouteSetNames.next(state.value.sets.map { it.name }) }
        run("The set could not be made.") { make(title) }
    }

    /**
     * A new set called [title], on the map and in the session. A set that is made but cannot be opened (the set on the map before it would not save) is taken away
     * again rather than left empty in the Library, and the failure is the caller's.
     */
    private suspend fun make(title: String): String {
        val made = repository.create(title)
        val opened = try {
            session.open(made.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.delete(made.id)
            throw e
        }
        if (!opened) {
            repository.delete(made.id)
            error("The new set could not be opened.")
        }
        openSets.add(made.id)
        local.update { it.copy(page = RoutesPage.HOME) }
        return made.id
    }

    /** Opens [uuid] on the map (it joins the session if it was not in it). */
    fun openSet(uuid: String) {
        if (session.active.value?.id == uuid) return
        run("The set could not be opened.") {
            if (session.open(uuid)) openSets.add(uuid) else local.update { it.copy(error = "That set is no longer here.") }
        }
    }

    /** Takes the set on the map out of the session ([close]). */
    fun closeSet() {
        session.active.value?.id?.let(::close)
    }

    /**
     * Takes [uuid] out of the sets open now. It is not deleted: the Library still has it. If it was the one on the map, it is saved and the one opened before it (the
     * last still open) takes its place, or none.
     */
    fun close(uuid: String) = run("The set could not be closed.") {
        openSets.remove(uuid)
        if (session.active.value?.id == uuid) {
            closeKeepingNothingNew()
            openNext()
        }
    }

    /** Opens the last set still in the session that is still here; an id whose set has gone is let go on the way. */
    private suspend fun openNext() {
        for (id in openSets.ids.value.reversed()) {
            val opened = try {
                session.open(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false                                                                // a mission whose file will not read is not a reason to stop looking
            }
            if (opened) return
            openSets.remove(id)
        }
    }

    // -- Naming ------------------------------------------------------------------------------------------------------

    /**
     * Names the open set [name] (the design's *Save*: everything is already saved and synced as it changes, so what saving adds is the name the Library finds it
     * by). A name another set already has is kept apart as `NAME 2` (the web's *Keep both*), and the toast says the name it got.
     */
    fun saveAs(name: String, replacing: String? = null) {
        val title = RouteSetNames.clean(name)
        if (title.isEmpty()) return fail("Give the routes a name.")
        val open = session.active.value ?: return
        if (replacing == open.id) return
        // With *Replace* ([replacing], the set that has the name) that set is deleted and this one takes its name; otherwise both are kept.
        val chosen = if (replacing != null) title else RouteSetNames.free(title, state.value.otherNames)
        if (RouteSetNames.isMadeUp(chosen)) return fail(RouteSetNames.MADE_UP_REFUSED)
        run("The routes could not be saved.") {
            if (session.active.value?.id != open.id) return@run
            replacing?.let { deleteNow(it) }
            session.edit("Name") { it.copy(name = chosen) }
            session.flush()
            _notices.tryEmit(savedToLibrary(chosen, replaced = replacing != null))
        }
    }

    /** Renames [uuid] [name]; with [replacing], the set that has the name is deleted first (the Library's *Replace*). */
    fun renameSet(uuid: String, name: String, replacing: String? = null) {
        val title = RouteSetNames.clean(name)
        if (title.isEmpty()) return fail("A set needs a name.")
        // A mission is named for its file and never offers Save, so no name of its own is "made up".
        val mission = state.value.sets.firstOrNull { it.uuid == uuid }?.isMission == true
        if (!mission && RouteSetNames.isMadeUp(title)) return fail(RouteSetNames.MADE_UP_REFUSED)
        if (replacing == uuid) return
        run("The set could not be renamed.") {
            replacing?.let { deleteNow(it) }
            // The open set is the one with unsaved edits in memory, so it is renamed there and saved with them.
            if (session.active.value?.id == uuid) {
                session.edit("Rename set") { it.copy(name = title) }
                session.flush()
            } else {
                repository.rename(uuid, title)
            }
        }
    }

    fun deleteSet(uuid: String) = run("The set could not be deleted.") { deleteNow(uuid) }

    /** Deletes [uuid], closing it first if it is the one on the map and opening the one before it. */
    private suspend fun deleteNow(uuid: String) {
        val wasOpen = session.active.value?.id == uuid
        if (wasOpen) closeKeepingNothingNew()
        repository.delete(uuid)
        openSets.remove(uuid)
        if (wasOpen) openNext()
    }

    /**
     * Closes the set on the map. One whose record went while it was open (deleted elsewhere) is kept under a new id by the save that closing makes: that id joins the
     * session as it is made, and is taken out again, so it is not opened as the one before (it stays in the Library).
     */
    private suspend fun closeKeepingNothingNew() {
        val before = openSets.ids.value.toSet()
        try {
            session.close()
        } finally {
            openSets.ids.value.filterNot { it in before }.forEach(openSets::remove)
        }
    }

    /** Settles a conflict the sync kept beside a set: the same three choices a diagram has. */
    fun resolve(copyUuid: String, resolution: SyncEngine.Resolution) {
        val kind = if (state.value.sets.firstOrNull { it.uuid == copyUuid }?.isMission == true) RecordKind.MISSION else RecordKind.ROUTE
        run("The conflict could not be settled.") { conflicts.resolve(kind, copyUuid, resolution) }
    }

    // -- Routes of the open set --------------------------------------------------------------------------------

    /** Works on [routeId]: the map draws it heavier. Choosing the route already chosen puts it down. */
    fun selectRoute(routeId: String) {
        if (session.active.value?.route(routeId) == null) return
        if (selection.held.value?.routeId == routeId) selection.clear() else selection.select(routeId)
    }

    fun toggleVisible(routeId: String) = session.edit("Show or hide route") { set -> set.mapRoute(routeId) { it.copy(visible = !it.visible) } }

    fun renameRoute(routeId: String, name: String) {
        if (isMission()) return fail(MISSION_ROUTES_ARE_THE_FILES)
        val title = name.trim().uppercase()
        if (title.isEmpty()) return fail("A route needs a name.")
        session.edit("Rename route") { set -> set.mapRoute(routeId) { it.copy(name = title) } }
    }

    fun deleteRoute(routeId: String) {
        if (isMission()) return fail(MISSION_ROUTES_ARE_THE_FILES)
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
                    is PlanningOutcome.Done -> PlanningNote(outcome.message, failed = false, kind)
                    is PlanningOutcome.Failed -> PlanningNote(outcome.message, failed = true, kind)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PlanningNote("That could not be fetched.", failed = true, kind)
            }
            local.update { it.copy(fetching = null, note = note) }
        }
    }

    // -- The held route's plan and points ------------------------------------------------------------------------

    /**
     * Applies what was typed in the route-wide plan ([typed]) to [routeId]: only what differs from [before], what the form showed. Null when it is applied (or
     * nothing was changed); else why not, in words for the field itself, because a banner at the top of the page can be scrolled out of sight while typing.
     * Changes made under one [coalesce] key one after another (a field typed into) are one step to undo.
     */
    fun applyPlan(routeId: String, typed: PlanDraft, before: PlanDraft, coalesce: Any? = null): String? {
        if (session.active.value?.route(routeId) == null) return "That route is no longer here."
        return when (val checked = typed.check(before)) {
            is PlanDraft.Checked.Refused -> checked.message
            is PlanDraft.Checked.Valid -> {
                if (checked.patch != PlanPatch()) session.edit("Change plan", coalesce) { set -> set.mapRoute(routeId) { SketchOps.withPlan(it, checked.patch) } }
                null
            }
        }
    }

    /**
     * Applies what was typed for one point (altitude, speed and wind to it, and its clock) to [routeId]: only what was changed from [before], in one undo step
     * (one for everything typed under one [coalesce] key in a row). Null when it is applied (or nothing was changed); else why not, in words for the field.
     */
    fun applyPoint(routeId: String, pointId: String, typed: PointDraft, before: PointDraft, first: Boolean, coalesce: Any? = null): String? {
        if (session.active.value?.route(routeId) == null) return "That route is no longer here."
        return when (val checked = typed.check(before, first)) {
            is PointDraft.Checked.Refused -> checked.message
            is PointDraft.Checked.Valid -> {
                if (!checked.isEmpty) {
                    session.edit("Change point", coalesce) { set ->
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
        if (isMission()) return fail(MISSION_POINT_TYPES_ARE_THE_FILES)
        session.edit("Change point type") { set -> set.mapRoute(routeId) { SketchOps.designate(it, pointId, PointSpec(RoutePoint.KIND_AMPS, ptType = ptType)) } }
    }

    /** Makes a named point only shape the line. Refused (in words) for one of the last two named points: a leg needs two ends. */
    fun makeShaping(routeId: String, pointId: String) {
        if (isMission()) return fail(MISSION_POINT_TYPES_ARE_THE_FILES)
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

    /**
     * Adds a point that only shapes the line at the crosshair, [at], in the leg it is nearest to, and holds it so it can be moved into place. In an imported mission the
     * file has no shaping point to give: the point is a **named** one (`.NEWPT`, as the web makes it) that splits the leg it is on.
     */
    fun addShapingPoint(routeId: String, at: LatLon?) {
        if (at == null) return fail("Move the map to where the point should go first.")
        val route = session.active.value?.route(routeId) ?: return
        val id = newPointId()
        if (isMission()) {
            if (SketchOps.insertMissionPoint(route, at.lat, at.lon) { id } == route) return fail("A route needs two points before another can be put on it.")
            fail(null)
            session.edit("Add point") { set -> set.mapRoute(routeId) { SketchOps.insertMissionPoint(it, at.lat, at.lon) { id } } }
            selection.holdPoint(id)
            return
        }
        if (SketchOps.insertShaping(route, at.lat, at.lon) { id } == route) return fail("A route needs two points before the line can be bent.")
        fail(null)
        session.edit("Add shaping point") { set -> set.mapRoute(routeId) { SketchOps.insertShaping(it, at.lat, at.lon) { id } } }
        selection.holdPoint(id)
    }

    /** Makes a point that only shaped the line a named one: a turn point, called `.CP` until it is named. */
    fun makeNamed(routeId: String, pointId: String) {
        if (isMission()) return fail(MISSION_POINT_TYPES_ARE_THE_FILES)
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

    fun startDrawing() = fail(if (isMission()) MISSION_ROUTES_ARE_THE_FILES else sketching.start())

    /**
     * The panel's *Sketch a route*: drawn into the set on the map when it is a set of sketched routes; otherwise (none open, or an imported mission, whose routes
     * are its file's) into a new one, called by the next made-up name until it is saved. Refused in words, before any set is made, when something else has the
     * map's taps.
     */
    fun startSketch(first: LatLon? = null) {
        val open = session.active.value
        if (open != null && open.mission == null) {
            val refused = sketching.start()
            if (refused == null && first != null) sketching.addPoint(first)
            return fail(refused)
        }
        sketching.refusal()?.let { return fail(it) }
        run("The routes could not be made.") {
            val made = make(RouteSetNames.next(state.value.sets.map { it.name }))
            when (val refused = sketching.start()) {
                null -> {
                    madeForSketch = made to open?.id
                    first?.let(sketching::addPoint)
                }
                else -> {
                    fail(refused)                                                           // something took the map's taps meanwhile: the set goes again
                    abandon(made, open?.id)
                }
            }
        }
    }

    /**
     * Takes away [made], a set made to sketch a route in, if it is still empty and still called by its made-up name (the person may have named it meanwhile, or it may
     * have a route: then it stays). If it was on the map, [before] (the set that was there before it) comes back, or the last set still open.
     */
    private fun abandon(made: String, before: String?) {
        viewModelScope.launch {
            try {
                val onMap = session.active.value?.takeIf { it.id == made }
                val set = onMap ?: repository.open(made) ?: return@launch
                if (set.routes.isNotEmpty() || set.unreadable.isNotEmpty() || !RouteSetNames.isMadeUp(set.name)) return@launch
                if (onMap != null) closeKeepingNothingNew()
                repository.delete(made)
                openSets.remove(made)
                if (onMap != null && session.active.value == null) {
                    if (before == null || !session.open(before)) openNext()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(error = "The empty set could not be taken away.") }
            }
        }
    }

    // -- The map's long press: *Add route point here* ----------------------------------------------------------------------------------

    /** What the map menu's *Add route point here* would do now. */
    fun pointAction(): MapPointAction {
        val open = session.active.value ?: return MapPointAction.StartRoute
        if (open.mission != null) return MapPointAction.Mission
        val route = selection.held.value?.routeId?.let(open::route) ?: open.routes.singleOrNull()
        return when {
            route != null -> MapPointAction.AddTo(route.name)
            open.routes.isEmpty() -> MapPointAction.StartRoute
            else -> MapPointAction.ChooseRoute
        }
    }

    /**
     * The map menu's *Add route point here*, at [at]: a turn point at the end of the route being worked on (the one held, else the set's only route), held so it can be
     * named or moved, as a local point is added; with no route to add to, a new route is started there (*Sketch a route* with its first point down). Not in an
     * imported mission, whose routes are its file's, nor with routes to choose between and none held.
     */
    fun addPointAt(at: LatLon) {
        when (val action = pointAction()) {
            is MapPointAction.AddTo -> {
                val open = session.active.value ?: return
                val route = selection.held.value?.routeId?.let(open::route) ?: open.routes.singleOrNull() ?: return
                val id = newPointId()
                fail(null)
                session.edit("Add point") { set -> set.mapRoute(route.id) { SketchOps.appendAmps(it, at.lat, at.lon, name = ".CP", ptType = "turn", newPointId = { id }) } }
                selection.select(route.id, id)
                _notices.tryEmit("Added a point to the end of ${action.routeName}.")
            }
            MapPointAction.StartRoute -> startSketch(first = at)
            MapPointAction.ChooseRoute -> fail(CHOOSE_A_ROUTE)
            MapPointAction.Mission -> fail(MISSION_ROUTES_ARE_THE_FILES)
        }
    }

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
                madeForSketch = null                                                       // it has a route: it is a set like any other
                local.update { it.copy(error = null) }
                selection.select(result.routeId)
            }
            is SketchFinish.Refused -> fail(result.reason)
        }
    }

    /** Stops drawing and forgets the points. A set made for this sketch that still has no route goes again, and the set before it comes back ([abandon]). */
    fun cancelDrawing() {
        local.update { it.copy(error = null) }
        sketching.cancel()
    }

    private fun fail(message: String?) = local.update { it.copy(error = message) }

    /** The open set is an imported mission: its routes and the kind of each point are the file's. */
    private fun isMission() = session.active.value?.mission != null

    private companion object {
        const val MISSION_ROUTES_ARE_THE_FILES = "An AMPS mission's routes are part of its file: they can't be drawn, renamed or removed here. Make a new set to draw a route."
        const val MISSION_POINT_TYPES_ARE_THE_FILES = "What kind of point this is is fixed in the mission's file."
        const val CHOOSE_A_ROUTE = "Tap the route to add the point to first: this set has more than one."
    }

    private fun run(fallback: String, block: suspend () -> Unit) {
        // Said, not dropped: a rename or a delete asked from the Library while a set is still opening would otherwise vanish without a word.
        if (busy) return fail(STILL_WORKING)
        busy = true
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(error = fallback) }
            } finally {
                busy = false
            }
        }
    }

    /** The web's line for a route: distance and time, and the fuel when it can be said. */
    private fun summaryOf(route: SketchRoute): String? {
        val totals = RouteCalc.computeRoutePlan(route.points, route.plan, route.elevations).totals ?: return null
        val fuel = totals.fuelLb?.let { " · ${RouteCalc.jsRound(it).toLong()} lb" }.orEmpty()
        return "${oneDecimal(totals.distNm)} nm · ${RouteCalc.formatDuration(totals.timeSec)}$fuel"
    }
}

/** What an action asked for while the last one is still under way is told: it is not done, and can be asked for again. */
/** What the map menu's *Add route point here* does now ([RoutesViewModel.pointAction]). */
sealed interface MapPointAction {
    /** A turn point at the end of [routeName]. */
    data class AddTo(val routeName: String) : MapPointAction

    /** No route to add to: a new one is started there. */
    data object StartRoute : MapPointAction

    /** Routes to choose between, and none held: refused. */
    data object ChooseRoute : MapPointAction

    /** An imported mission is open: its routes are its file's. */
    data object Mission : MapPointAction
}

internal const val STILL_WORKING = "Still finishing the last change. Try again in a moment."
