package app.ezpztac.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.PointSelection
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.data.SlopeState
import app.ezpztac.data.WeatherService
import app.ezpztac.map.DrawnPointSet
import app.ezpztac.map.GraphicHitTest
import app.ezpztac.map.LzScene
import app.ezpztac.map.MapProjection
import app.ezpztac.map.PointHitTest
import app.ezpztac.map.PointScene
import app.ezpztac.map.RouteHitTest
import app.ezpztac.map.RouteScene
import app.ezpztac.map.SlopeImage
import app.ezpztac.model.LatLon
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.symbols.SymbolRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val lastSet: LastRouteSet,
    private val localPoints: LocalPoints,
    private val pointSelection: PointSelection,
    private val weather: WeatherService,
    /** What draws a unit's symbol; handed to the composition under the map and the sheet. */
    val symbols: SymbolRenderer,
) : ViewModel() {
    private val _opened = MutableSharedFlow<OpenedDiagram>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * One event for each diagram opened while the screen is up — not for each edit of it, and not again when the screen is rebuilt (a
     * turn of the phone must not throw the person's view back to the diagram's target).
     */
    val opened: SharedFlow<OpenedDiagram> = _opened.asSharedFlow()

    /**
     * What the map draws for the open diagram: its target, boundary, planning graphics (the one being held with a halo) and, once measured,
     * the slope raster. The raster is shown only while the boundary is still the one it was measured for. Each aircraft is drawn as the
     * airframe it was placed as, the same as the sheet measures it; one placed with a profile that is not known here is drawn as the chosen one.
     */
    val scene: StateFlow<LzScene> = combine(session.active, analysis.slopes, combine(selection.selected, drawing.draft) { held, draft -> held to draft }, aircraft.profiles, aircraft.active) { diagram, slopes, (selected, draft), profiles, active ->
        val measured = diagram?.let { d -> (slopes[d.id] as? SlopeState.Ready)?.takeIf { it.boundaryKey == analysis.boundaryKey(d) } }
        // A boundary being drawn belongs to the diagram it was started on: on another one it is not drawn (and is dropped when that one opens).
        val corners = draft?.takeIf { it.diagramId == diagram?.id }?.points.orEmpty()
        LzScene.of(diagram, measured?.analysis?.toSlopeImage(), profiles = profiles, active = active, selected = selected, draft = corners)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LzScene.EMPTY)

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

    /** Whether a boundary or a route is being drawn: the sheet goes down to its peek so the map is there to tap. */
    val isDrawing: StateFlow<Boolean> = combine(drawing.draft, sketching.draft) { boundary, route -> boundary != null || route != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            val id = last.id() ?: return@launch
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
            val id = lastSet.id() ?: return@launch
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
                drawing.cancel()                                                           // and so did a boundary half drawn
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
        val hit = GraphicHitTest.pick(scene.value.graphics, view, at, touchRadiusPx)
        if (hit != null) {
            pointSelection.clear()
            selection.select(hit)
            return
        }
        selection.clear()
        val route = RouteHitTest.pick(routes.value, view, at, touchRadiusPx)
        if (route != null) {
            pointSelection.clear()
            routeSelection.select(route.routeId, route.pointId)
            return
        }
        routeSelection.releasePoint()
        // A local point is under the routes (a route point snapped onto one is the route's), and over nothing: a tap on nothing puts it down.
        val point = PointHitTest.pick(points.value, view, at, touchRadiusPx)
        if (point != null) pointSelection.toggle(point.setId, point.pointId) else pointSelection.clear()
    }

    /** The person chose a base map: the open diagram keeps it, so it comes back the next time the diagram is opened. */
    fun baseMapChosen(id: String) = session.setQuietly { it.copy(view = it.view.copy(mapStyle = id)) }

    /** The app is going out of sight: what has been changed is written now, because the system may end the process without warning. */
    fun appStopped() {
        viewModelScope.launch {
            try {
                session.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still owed: the session keeps the changes and says so ([DiagramSession.saveFailed]); the next save tries again.
            }
        }
    }
}

private fun TerrainAnalysis.toSlopeImage(): SlopeImage? {
    val southWest = bounds.getOrNull(0) ?: return null
    val northEast = bounds.getOrNull(1) ?: return null
    if (southWest.size < 2 || northEast.size < 2) return null
    return SlopeImage(south = southWest[0], west = southWest[1], north = northEast[0], east = northEast[1], dataUri = overlay)
}
