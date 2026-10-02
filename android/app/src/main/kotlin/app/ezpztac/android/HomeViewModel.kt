package app.ezpztac.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.model.LatLon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
class HomeViewModel @Inject constructor(private val session: DiagramSession) : ViewModel() {
    private val _opened = MutableSharedFlow<OpenedDiagram>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * One event for each diagram opened while the screen is up — not for each edit of it, and not again when the screen is rebuilt (a
     * turn of the phone must not throw the person's view back to the diagram's target).
     */
    val opened: SharedFlow<OpenedDiagram> = _opened.asSharedFlow()

    init {
        viewModelScope.launch {
            session.active.filterNotNull().distinctUntilChangedBy { it.id }.collect { diagram ->
                _opened.tryEmit(OpenedDiagram(diagram.id, diagram.target?.let { LatLon(it.lat, it.lon) }, diagram.view.mapStyle))
            }
        }
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
