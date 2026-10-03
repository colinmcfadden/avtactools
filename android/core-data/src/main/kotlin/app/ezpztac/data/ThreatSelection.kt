package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The threat a crew is holding on the map and in the sheet. It is deliberately separate from [ThreatStore]: selection is a view choice,
 * so it is neither sealed into the short-lived threat file nor exported to AMPS.
 */
@Singleton
public class ThreatSelection @Inject constructor() {
    private val _held = MutableStateFlow<String?>(null)
    public val held: StateFlow<String?> = _held.asStateFlow()

    public fun select(id: String) {
        _held.value = id
    }

    /** Holds [id], or puts it down when it was already held. */
    public fun toggle(id: String) {
        _held.value = if (_held.value == id) null else id
    }

    public fun clear() {
        _held.value = null
    }
}
