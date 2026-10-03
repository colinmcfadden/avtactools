package app.ezpztac.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.LatLon
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Mapbox token comes from: the server's `/api/config`, remembered so a start with no signal can still draw satellite imagery. */
interface MapTokenSource {
    val token: StateFlow<String?>
}

/** Where the camera was when the app was last used, kept on the device so the next launch starts where the person left off. */
interface CameraMemory {
    fun last(): CameraState?
    fun save(camera: CameraState)
}

/** What the GPS button shows, and what is going on behind it. */
sealed interface GpsState {
    data object Off : GpsState

    /** The person has not allowed location yet; the screen asks and reports back with [MapViewModel.permissionResult]. */
    data object NeedsPermission : GpsState

    /** The receiver is on and has no fix yet. */
    data object Searching : GpsState

    data class Tracking(val fix: UserLocation) : GpsState

    /** Location is off on the device, or the receiver gave up. */
    data class Unavailable(val message: String) : GpsState
}

data class MapUiState(
    val styles: List<MapBaseStyle>,
    val style: MapBaseStyle,
    val readout: Readout? = null,
    val searchError: String? = null,
    val gps: GpsState = GpsState.Off,
    /** What the crosshair is over, as a position: where a graphic is placed. Null until the map has reported where it is looking. */
    val center: LatLon? = null,
)

/** Things the map has to do that are not state: look somewhere, turn north. */
sealed interface MapCommand {
    data class FlyTo(val at: LatLon, val zoom: Double?) : MapCommand
    data object FaceNorth : MapCommand
}

/**
 * What the map screen shows and does: which base map, what the crosshair reads, what a search found, where the GPS is. The map view itself
 * is told through [commands]; everything that decides *what* to tell it is here, and tried without a map.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val tokens: MapTokenSource,
    private val location: LocationSource,
    private val memory: CameraMemory,
) : ViewModel() {
    private val _state = MutableStateFlow(stylesState(tokens.token.value, null))
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<MapCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: SharedFlow<MapCommand> = _commands.asSharedFlow()

    private var chosenStyleId: String? = null
    private var lastCamera: CameraState? = null
    private var tracking: Job? = null
    private var saving: Job? = null

    /** Where the first frame looks: where the person left off, or the whole country the first time. */
    val initialCamera: CameraState = memory.last() ?: FIRST_LAUNCH

    init {
        // The token arrives after the screen does (the config is fetched at launch): the styles are worked out again when it does.
        viewModelScope.launch { tokens.token.collect { restyle() } }
    }

    private fun stylesState(token: String?, wanted: String?): MapUiState {
        val styles = MapStyles.available(token)
        return MapUiState(styles = styles, style = MapStyles.byId(wanted, token))
    }

    private fun restyle() = _state.update { current ->
        val token = tokens.token.value
        current.copy(styles = MapStyles.available(token), style = MapStyles.byId(chosenStyleId ?: MapStyles.DEFAULT_ID, token))
    }

    // -- The crosshair ---------------------------------------------------------------------------------

    fun onCamera(camera: CameraState) {
        if (lastCamera?.sameView(camera) == true) return
        lastCamera = camera
        _state.update { it.copy(readout = Readout.of(camera.center), center = camera.center) }
        // Written once the camera has been still for a moment, not on every frame of a pan.
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(SAVE_AFTER_STILL_MS)
            memory.save(camera)
        }
    }

    // -- Search ------------------------------------------------------------------------------------------

    fun search(text: String) {
        when (val result = PlaceSearch.resolve(text)) {
            is PlaceResult.Found -> {
                _state.update { it.copy(searchError = null) }
                _commands.tryEmit(MapCommand.FlyTo(result.at, SEARCH_ZOOM))
            }
            is PlaceResult.NotUnderstood -> _state.update { it.copy(searchError = result.message) }
        }
    }

    fun clearSearchError() = _state.update { it.copy(searchError = null) }

    // -- Base map ------------------------------------------------------------------------------------------

    /** The base map a diagram was saved with, when it is opened. An id this version does not know is kept (and saved back), but not drawn. */
    fun restoreStyle(id: String?) {
        chosenStyleId = id
        restyle()
    }

    /** A diagram was opened: its saved base map comes back, and the map goes to its target (a diagram with none leaves the camera where it is). */
    fun showDiagram(at: LatLon?, baseMapId: String?) {
        if (baseMapId != null) restoreStyle(baseMapId)
        if (at != null) _commands.tryEmit(MapCommand.FlyTo(at, DIAGRAM_ZOOM))
    }

    fun selectStyle(id: String) {
        chosenStyleId = id
        _state.update { it.copy(style = MapStyles.byId(id, tokens.token.value)) }
    }

    /** The id to save with a diagram: what the person chose, not what a missing token fell back to. */
    fun chosenStyleId(): String = chosenStyleId ?: MapStyles.DEFAULT_ID

    fun faceNorth() {
        _commands.tryEmit(MapCommand.FaceNorth)
    }

    // -- GPS ------------------------------------------------------------------------------------------------

    /** The GPS button: on if it is off (asking for permission first), off if it is on. */
    fun toggleGps() {
        if (_state.value.gps != GpsState.Off && _state.value.gps != GpsState.NeedsPermission && _state.value.gps !is GpsState.Unavailable) {
            stopTracking()
            return
        }
        if (!location.hasPermission()) {
            _state.update { it.copy(gps = GpsState.NeedsPermission) }
            return
        }
        startTracking()
    }

    /** The answer to the permission request the screen made after [GpsState.NeedsPermission]. */
    fun permissionResult(granted: Boolean) {
        if (granted) startTracking()
        else _state.update { it.copy(gps = GpsState.Unavailable("Location is not allowed. Turn it on for this app in the system settings.")) }
    }

    /** Centres the map on the last fix, if there is one. */
    fun locateMe() {
        (_state.value.gps as? GpsState.Tracking)?.let { _commands.tryEmit(MapCommand.FlyTo(it.fix.at, LOCATE_ZOOM)) }
    }

    private fun startTracking() {
        tracking?.cancel()
        _state.update { it.copy(gps = GpsState.Searching) }
        var centred = false
        tracking = viewModelScope.launch {
            var received = false
            location.updates()
                .catch { _state.update { s -> s.copy(gps = GpsState.Unavailable("The GPS stopped reporting.")) } }
                .collect { fix ->
                    received = true
                    _state.update { it.copy(gps = GpsState.Tracking(fix)) }
                    // The first fix takes the map there; after that the person drives the camera.
                    if (!centred) { centred = true; _commands.tryEmit(MapCommand.FlyTo(fix.at, LOCATE_ZOOM)) }
                }
            // The flow ended with no fix: location is switched off on the device (or was switched off).
            if (!received) _state.update { it.copy(gps = GpsState.Unavailable("Location is switched off on this device.")) }
        }
    }

    private fun stopTracking() {
        tracking?.cancel()
        tracking = null
        _state.update { it.copy(gps = GpsState.Off) }
    }

    override fun onCleared() {
        tracking?.cancel()
    }

    companion object {
        const val SAVE_AFTER_STILL_MS = 1_000L

        /** Where a first launch looks: the middle of the continental US, zoomed out, until the person searches or turns the GPS on. */
        val FIRST_LAUNCH = CameraState(LatLon(39.5, -98.35), 4.0)

        /** About a rotor diameter per few points: close enough to see a landing zone. */
        const val SEARCH_ZOOM = 16.0
        const val LOCATE_ZOOM = 16.0

        /** Close enough to place an aircraft by eye: a landing zone fills the screen. */
        const val DIAGRAM_ZOOM = 17.0
    }
}
