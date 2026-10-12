package app.ezpztac.android

import androidx.lifecycle.ViewModel
import app.ezpztac.android.frame.Panel
import app.ezpztac.android.frame.PanelCounts
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.BoundaryCorners
import app.ezpztac.data.BoundaryDraft
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.data.GraphicPlacement
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.MapDrag
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.MapFocus
import app.ezpztac.data.MaskState
import app.ezpztac.data.PointSelection
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.data.SlopeState
import app.ezpztac.data.SlopeVisibility
import app.ezpztac.data.WeatherService
import app.ezpztac.data.ThreatMasks
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.map.CornerHitTest
import app.ezpztac.map.DragHitTest
import app.ezpztac.map.DrawnPointSet
import app.ezpztac.map.GraphicHitTest
import app.ezpztac.map.LzScene
import app.ezpztac.map.MapProjection
import app.ezpztac.map.PointHitTest
import app.ezpztac.map.PointScene
import app.ezpztac.map.RouteHitTest
import app.ezpztac.map.RouteScene
import app.ezpztac.map.UnitFootprints
import app.ezpztac.map.ThreatHitTest
import app.ezpztac.map.ThreatMaskPicture
import app.ezpztac.map.ThreatScene
import app.ezpztac.map.SlopeImage
import app.ezpztac.map.SlopeToggleUi
import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.symbols.SymbolRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A diagram that was just opened: where the map goes, and the base map it was saved with. */
data class OpenedDiagram(val id: String, val at: LatLon?, val baseMap: String?)

/**
 * What joins the map and the diagrams. The map does not know about diagrams and the diagrams do not know about the map; this tells the
 * first when the second opens one, and writes down the base map the person chose.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: DiagramSession,
    private val analysis: AnalysisService,
    private val selection: GraphicSelection,
    private val aircraft: AircraftProfiles,
    private val drawing: BoundaryDrawing,
    private val routeSession: RouteSession,
    private val routeSelection: RouteSelection,
    private val sketching: RouteSketching,
    private val last: LastDiagram,
    private val openSet: OpenDiagrams,
    private val lastSet: LastRouteSet,
    private val openRouteSets: OpenRouteSets,
    private val localPoints: LocalPoints,
    private val pointSelection: PointSelection,
    private val threats: ThreatStore,
    private val threatSelection: ThreatSelection,
    private val weather: WeatherService,
    private val mapDrag: MapDrag,
    private val slopeVisibility: SlopeVisibility,
    private val boundaryCorners: BoundaryCorners,
    private val threatMasks: ThreatMasks,
    private val placement: GraphicPlacement,
    mapFocus: MapFocus,
    /** What draws a unit's symbol; handed to the composition under the map and the sheet. */
    val symbols: SymbolRenderer,
) : ViewModel() {
    private val _opened = MutableSharedFlow<OpenedDiagram>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * One event for each diagram opened while the screen is up — not for each edit of it, and not again when the screen is rebuilt (a
     * turn of the phone must not throw the person's view back to the diagram's target).
     */
    val opened: SharedFlow<OpenedDiagram> = _opened.asSharedFlow()

    /** Where each unit's picture is on the screen, which the map's drawing reports and a tap or a long press is tested against. */
    val unitFootprints = UnitFootprints()

    /** Somewhere the person has just brought in (a mission's routes) that the map should go to. */
    val focus: SharedFlow<MapFocus.Request> = mapFocus.requests

    /**
     * What the map draws for the open diagram: its target, boundary, planning graphics (the one being held with a halo) and, once measured,
     * the slope raster. The raster is shown only while the boundary is still the one it was measured for. Each aircraft is drawn as the
     * airframe it was placed as, the same as the sheet measures it; one placed with a profile that is not known here is drawn as the chosen one.
     */
    val scene: StateFlow<LzScene> = combine(
        session.active, analysis.slopes,
        combine(selection.selected, drawing.draft, slopeVisibility.shown, boundaryCorners.held) { held, draft, shown, corner -> HeldView(held, draft, shown, corner) },
        aircraft.profiles, aircraft.active,
    ) { diagram, slopes, view, profiles, active ->
        val (selected, draft, slopeShown, heldCorner) = view
        val measured = diagram?.let { d -> (slopes[d.id] as? SlopeState.Ready)?.takeIf { it.boundaryKey == analysis.boundaryKey(d) } }
        // A boundary being drawn belongs to the diagram it was started on: on another one it is not drawn (and is dropped when that one opens).
        val corners = draft?.takeIf { it.diagramId == diagram?.id }?.points.orEmpty()
        // The heat map is drawn only while the person has it on; it is measured either way.
        LzScene.of(diagram, measured?.analysis?.toSlopeImage()?.takeIf { slopeShown }, profiles = profiles, active = active, selected = selected, draft = corners, heldCorner = heldCorner)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LzScene.EMPTY)

    /** What the map's scene is built from besides the diagram: what is held, what is being drawn, whether the heat map is on, and the boundary corner held. */
    private data class HeldView(val selected: GraphicRef?, val draft: BoundaryDraft?, val slopeShown: Boolean, val corner: BoundaryCornerRef?)

    /** The button for the slope heat map: there only while the open diagram has a slope measured, saying whether it is drawn. */
    val slopeToggle: StateFlow<SlopeToggleUi?> = combine(session.active, analysis.slopes, slopeVisibility.shown) { diagram, slopes, shown ->
        val ready = diagram?.let { d -> (slopes[d.id] as? SlopeState.Ready)?.takeIf { it.boundaryKey == analysis.boundaryKey(d) } }
        if (ready?.analysis?.toSlopeImage() == null) null else SlopeToggleUi(shown)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The person pressed the slope button: the heat map goes on or off. */
    fun toggleSlope() = slopeVisibility.toggle()

    /**
     * What the map draws for the open set of routes: each visible route as a line with its named points, the one being worked on heavier, and a route
     * being drawn. A selection that names a route no longer there is not drawn, and a draft belongs to the set it was started on.
     */
    val routes: StateFlow<RouteScene> = combine(routeSession.active, routeSelection.held, sketching.draft) { set, held, draft ->
        val chosen = held?.routeId?.takeIf { set?.route(it) != null }
        val points = draft?.takeIf { it.setId == set?.id }?.points.orEmpty().map { LatLon(it.lat, it.lon) }
        RouteScene.of(set, selectedRouteId = chosen, selectedPointId = held?.takeIf { it.routeId == chosen }?.pointId, draft = points)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RouteScene.EMPTY)

    /**
     * What the map draws for the local points: those of every set that is shown, each in its set's colour, the held one marked. A set that is hidden
     * is not drawn, and neither is a held point that is in one.
     */
    val points: StateFlow<PointScene> = combine(localPoints.sets, pointSelection.held) { sets, held ->
        PointScene.of(sets.filter { it.visible }.map { DrawnPointSet(it.set.id, it.color, it.set.points) }, held?.setId, held?.pointId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PointScene.EMPTY)

    /**
     * Whether the signed-in account has threats (its `threats` feature, which the screen knows from the user: [allowThreats]). Until it says so they are not
     * drawn, held or counted. An account an administrator has turned threats off for may still have a picture kept on this device from before (until
     * sign-out), and with no Threats panel to show it in, it is not put on the map either: the web, whose threats are never kept, never has one to draw.
     */
    private val threatsAllowed = MutableStateFlow(false)

    /** Says whether the signed-in account has threats ([threatsAllowed]). Turned off, a threat held is put down. */
    fun allowThreats(allowed: Boolean) {
        threatsAllowed.value = allowed
        if (!allowed) threatSelection.clear()
    }

    /** The local-only threat picture: visible markers and their range rings, with the held threat marked. Nothing while the account has no threats. */
    val threatScene: StateFlow<ThreatScene> = combine(threats.entries, threatSelection.held, threatMasks.states, threatsAllowed) { entries, held, masks, allowed ->
        if (!allowed) return@combine ThreatScene.EMPTY
        // A terrain mask is drawn only for a threat the person asked for one of, and only while it is still that threat's: one moved since is not what it shows.
        val pictures = entries.associate { entry ->
            val mask = (masks[entry.id] as? MaskState.Ready)?.mask?.takeIf { it.isFor(entry.threat) }
            entry.id to mask?.images.orEmpty().map { ThreatMaskPicture("${entry.id}-${it.type}", mask!!.south, mask.west, mask.north, mask.east, it.dataUri) }
        }
        ThreatScene.of(entries, held, pictures)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThreatScene.EMPTY)

    /** What the Threats tab carries: the threats in the picture on this device. The Imports tab's count is its panel's own ([PanelCounts.imports]). */
    val counts: StateFlow<PanelCounts> = combine(threats.entries, threatsAllowed) { entries, allowed -> PanelCounts(threats = if (allowed) entries.size else 0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PanelCounts())

    /**
     * The panel whose card has the rest of what can be done to the thing held on the map (the held bar's options): the LZ/PZ's for a planning
     * graphic or a corner of its boundary, the threat's, the route's for one of its points, the local points' for one of theirs. Null when nothing is held.
     * **In the bar's own order** (`heldObjectOf`: a graphic, a threat that is drawn, a point of a route, a corner), so the options are for what the bar shows: a
     * threat that is hidden or gone is not what is held.
     */
    fun heldPanel(): Panel? = when {
        selection.selected.value != null -> Panel.Lz
        threatSelection.held.value?.let { id -> threats.entries.value.any { it.id == id && it.visible } } == true -> Panel.Threats
        routeSelection.held.value?.pointId != null -> Panel.Routes
        boundaryCorners.held.value != null -> Panel.Lz
        pointSelection.held.value != null -> Panel.Imports
        else -> null
    }

    /**
     * Whether the map's taps are taken: a boundary or a route being drawn, or a planning tool's mode placing its graphic. The sheet goes down to its header
     * so the map is there to tap, and nothing on the map is held meanwhile.
     */
    val isDrawing: StateFlow<Boolean> = combine(drawing.draft, sketching.draft, placement.placing) { boundary, route, placing ->
        boundary != null || route != null || placing != null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * What has the map's taps ([isDrawing]): a boundary, a route, or a planning tool by its tool, so that each start takes the sheet down again, the person
     * having raised it meanwhile. One tool's mode is finished as another begins without the taps ever being let go, so [isDrawing] does not change then.
     */
    val drawingWhat: StateFlow<Any?> = combine(drawing.draft, sketching.draft, placement.placing) { boundary, route, placing ->
        when {
            boundary != null -> "boundary"
            route != null -> "route"
            else -> placing?.tool
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The master list of airframes is the admin's and changes now and then: asked for when the map comes up, and kept for when there is no signal.
        viewModelScope.launch { aircraft.refreshQuietly() }
        // An analysed diagram that is opened (or whose boundary changes) has its slope measured, if it has not been already.
        viewModelScope.launch {
            session.active.filterNotNull().distinctUntilChangedBy { it.id to it.analysis.detectedLZ }.collect(analysis::ensureSlope)
        }
        // The weather at an analysed diagram's target is fetched when it opens, and when it is analysed or its target moves, as the web does when the analysis
        // finishes; only if what is held is missing, old or for somewhere else (the service decides), so reopening a diagram does not ask again and again.
        viewModelScope.launch {
            session.active.filterNotNull()
                .distinctUntilChangedBy { d -> Triple(d.id, d.canEditGraphics, d.target?.let { it.lat to it.lon }) }
                .collect { d ->
                    val target = d.target ?: return@collect
                    if (d.canEditGraphics) weather.ensureFresh(d.id, LatLon(target.lat, target.lon))
                }
        }
        // The diagram the person had open comes back at launch. It waits until the map is listening, because opening one is what takes the map to it
        // (and restores its base map), and an event nobody is listening to is lost.
        viewModelScope.launch {
            _opened.subscriptionCount.first { it > 0 }
            if (session.active.value != null) return@launch                              // something was opened in the meantime: it stays
            // Only if it is still open: one the person closed (the last open one, so nothing took its place) stays closed.
            val id = last.id()?.takeIf { it in openSet.ids.value } ?: return@launch
            try {
                session.open(id)                                                         // false when it is gone (deleted, or another account's)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A diagram that will not open is not a reason to stop the app: the person starts from the list.
            }
        }
        // The set of routes the person had open comes back too. Nothing waits on the map here: a set takes the map nowhere, so there is no event to lose.
        viewModelScope.launch {
            if (routeSession.active.value != null) return@launch                          // something was opened in the meantime: it stays
            // Only if it is still open, as for the LZ/PZ: the last set closed stays closed.
            val id = lastSet.id()?.takeIf { it in openRouteSets.ids.value } ?: return@launch
            try {
                routeSession.open(id)                                                     // false when it is gone (deleted, or another account's)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A set that will not open is not a reason to stop the app: the person starts from the list.
            }
        }
        viewModelScope.launch {
            routeSession.active.filterNotNull().distinctUntilChangedBy { it.id }.collect { lastSet.remember(it.id) }    // kept when the session closes (sign-out), as the diagram is
        }
        // An LZ/PZ closed (and none opened in its place) takes a tool's mode with it.
        viewModelScope.launch {
            session.active.collect { diagram -> if (diagram == null) placement.end() }
        }
        // What was held on a set of routes, and a route half drawn, belonged to the set before: another set, or none, starts clean.
        viewModelScope.launch {
            routeSession.active.distinctUntilChangedBy { it?.id }.collect { set ->
                routeSelection.clear()
                sketching.dropDraftNotOn(set?.id)                                         // not one begun on this very set while this was being heard
            }
        }
        viewModelScope.launch {
            session.active.filterNotNull().distinctUntilChangedBy { it.id }.collect { diagram ->
                last.remember(diagram.id)                                                // not forgotten when the session closes (sign-out): the same account is back
                selection.clear()                                                          // what was held belonged to the diagram before
                boundaryCorners.clear()
                drawing.cancel()                                                           // and so did a boundary half drawn
                if (placement.placing.value?.diagramId != diagram.id) placement.end()     // and a tool's mode (what it placed stays)
                _opened.tryEmit(OpenedDiagram(diagram.id, diagram.target?.let { LatLon(it.lat, it.lon) }, diagram.view.mapStyle))
            }
        }
    }

    /**
     * A tap on the map at [at], seen through [view]: the graphic under the finger is held, and a tap on nothing puts the held one down.
     * [touchRadiusPx] is how far from a graphic's point a finger still counts as on it. While a boundary is being drawn a tap is a corner of it,
     * and while a route is being drawn a point of it, wherever it falls: nothing else on the map can be held until the person finishes or cancels.
     * Otherwise a planning graphic is held first; failing that a route or one of its points; failing that a local point (tapping the held one puts it
     * down); a tap on nothing puts down the graphic, the route's point and the local point (the route being worked on stays, so drawing and editing it carry on).
     */
    fun mapTapped(at: LatLon, view: MapProjection, touchRadiusPx: Double) {
        if (drawing.draft.value != null) {
            drawing.addPoint(at)
            return
        }
        if (sketching.draft.value != null) {
            sketching.addPoint(at)
            return
        }
        // A planning tool's mode puts its graphic where the map is tapped, wherever that is: nothing is held until Done or Cancel.
        if (placement.placing.value != null) {
            placement.placeAt(at)
            return
        }
        val hit = GraphicHitTest.pick(scene.value.graphics, view, at, touchRadiusPx, unitFootprints)
        if (hit != null) {
            boundaryCorners.clear()
            pointSelection.clear()
            threatSelection.clear()
            selection.select(hit)
            return
        }
        selection.clear()
        val threat = ThreatHitTest.pick(threatScene.value, view, at, touchRadiusPx)
        if (threat != null) {
            boundaryCorners.clear()
            pointSelection.clear()
            routeSelection.releasePoint()
            threatSelection.toggle(threat)
            return
        }
        threatSelection.clear()
        val route = RouteHitTest.pick(routes.value, view, at, touchRadiusPx)
        if (route != null) {
            boundaryCorners.clear()
            pointSelection.clear()
            routeSelection.select(route.routeId, route.pointId)
            return
        }
        routeSelection.releasePoint()
        // A corner of the boundary is the lowest thing on the map: held only when nothing above it was.
        val corner = CornerHitTest.pick(scene.value, view, at, touchRadiusPx)
        if (corner != null) {
            pointSelection.clear()
            boundaryCorners.select(corner)
            return
        }
        boundaryCorners.clear()
        // A local point is under the routes (a route point snapped onto one is the route's), and over nothing: a tap on nothing puts it down.
        val point = PointHitTest.pick(points.value, view, at, touchRadiusPx)
        if (point != null) pointSelection.toggle(point.setId, point.pointId) else pointSelection.clear()
    }

    // -- Picking something up with a long press and dragging it ---------------------------------------------------------------

    /**
     * A long press at [at]: if there is something there that can be moved (a planning graphic, the tip of a PZ marker's arrow, a threat, a point of a route: what
     * [DragHitTest] finds), it is picked up, and this answers true so the finger drags it and the map does not pan. **Nothing is held or opened by a long press**: the bar with
     * the object's information is for a tap. False for anything else (the line between two points, the ground, a local point), and while a boundary or a route is being drawn:
     * taps are corners then.
     */
    fun dragStarted(at: LatLon, view: MapProjection, touchRadiusPx: Double): Boolean {
        if (drawing.draft.value != null || sketching.draft.value != null || placement.placing.value != null) return false
        val target = DragHitTest.pick(scene.value.graphics, threatScene.value, routes.value, view, at, touchRadiusPx, unitFootprints) ?: return false
        return mapDrag.start(target, at)
    }

    /** Puts down whatever is held on the map (the bar's Done): a graphic, a threat, a point of a route, a local point. A route being worked on stays the route. */
    fun releaseHeld() {
        boundaryCorners.clear()
        selection.clear()
        threatSelection.clear()
        pointSelection.clear()
        routeSelection.releasePoint()
    }

    /** The finger holding what was picked up is now at [at]. */
    fun dragMoved(at: LatLon) = mapDrag.move(at)

    /** The finger lifted at [at]: what was picked up is put down there. */
    fun dragEnded(at: LatLon) = mapDrag.end(at)

    /** The touch was taken from the drag: what was picked up goes back where it was. */
    fun dragCancelled() = mapDrag.cancel()

    /** The person chose a base map: the open diagram keeps it, so it comes back the next time the diagram is opened. */
    fun baseMapChosen(id: String) = session.setQuietly { it.copy(view = it.view.copy(mapStyle = id)) }
}

private fun TerrainAnalysis.toSlopeImage(): SlopeImage? {
    val southWest = bounds.getOrNull(0) ?: return null
    val northEast = bounds.getOrNull(1) ?: return null
    if (southWest.size < 2 || northEast.size < 2) return null
    return SlopeImage(south = southWest[0], west = southWest[1], north = northEast[0], east = northEast[1], dataUri = overlay)
}
