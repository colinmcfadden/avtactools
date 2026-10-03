package app.ezpztac.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.data.SlopeState
import app.ezpztac.map.GraphicHitTest
import app.ezpztac.map.LzScene
import app.ezpztac.map.MapProjection
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
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
    val scene: StateFlow<LzScene> = combine(session.active, analysis.slopes, selection.selected, aircraft.profiles, aircraft.active) { diagram, slopes, selected, profiles, active ->
        val measured = diagram?.let { d -> (slopes[d.id] as? SlopeState.Ready)?.takeIf { it.boundaryKey == analysis.boundaryKey(d) } }
        LzScene.of(diagram, measured?.analysis?.toSlopeImage(), profiles = profiles, active = active, selected = selected)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LzScene.EMPTY)

    init {
        // The master list of airframes is the admin's and changes now and then: asked for when the map comes up, and kept for when there is no signal.
        viewModelScope.launch { aircraft.refreshQuietly() }
        // An analysed diagram that is opened (or whose boundary changes) has its slope measured, if it has not been already.
        viewModelScope.launch {
            session.active.filterNotNull().distinctUntilChangedBy { it.id to it.analysis.detectedLZ }.collect(analysis::ensureSlope)
        }
        viewModelScope.launch {
            session.active.filterNotNull().distinctUntilChangedBy { it.id }.collect { diagram ->
                selection.clear()                                                          // what was held belonged to the diagram before
                _opened.tryEmit(OpenedDiagram(diagram.id, diagram.target?.let { LatLon(it.lat, it.lon) }, diagram.view.mapStyle))
            }
        }
    }

    /**
     * A tap on the map at [at], seen through [view]: the graphic under the finger is held, and a tap on nothing puts the held one down.
     * [touchRadiusPx] is how far from a graphic's point a finger still counts as on it.
     */
    fun mapTapped(at: LatLon, view: MapProjection, touchRadiusPx: Double) {
        val hit = GraphicHitTest.pick(scene.value.graphics, view, at, touchRadiusPx)
        if (hit != null) selection.select(hit) else selection.clear()
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
