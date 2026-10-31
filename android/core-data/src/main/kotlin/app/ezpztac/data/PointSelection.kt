package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The local point the person is holding: a point id is only unique within its set, so both name it. */
data class PointHeld(val setId: String, val pointId: String)

/**
 * Which local point is being held: the map draws it larger with a ring and the sheet shows what is known of it. It belongs to no screen, because the
 * map and the sheet both read it. A selection that names a point no longer there (its set deleted, or hidden) simply shows nothing; whoever shows it
 * checks that it is still there.
 */
@Singleton
class PointSelection @Inject constructor() {
    private val _held = MutableStateFlow<PointHeld?>(null)
    val held: StateFlow<PointHeld?> = _held.asStateFlow()

    fun select(setId: String, pointId: String) {
        _held.value = PointHeld(setId, pointId)
    }

    /** Holds the point again, or puts it down when it is the one already held. */
    fun toggle(setId: String, pointId: String) {
        _held.value = if (_held.value == PointHeld(setId, pointId)) null else PointHeld(setId, pointId)
    }

    fun clear() {
        _held.value = null
    }
}
