package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The LZ/PZ just made, a target and nothing more, whose next step the bar over the map offers: find its landing area, or draw it (the owner's request, 2026-10-21:
 * a new LZ/PZ from the map's long press or the panel, prompted there and then). The panel that makes one says so ([made]); the bar lets it go once its first step is
 * taken or put off ([forget]). It belongs to no screen, because the one that makes it and the one that offers its next step are not the same.
 */
@Singleton
public class FreshDiagrams @Inject constructor() {
    private val _made = MutableStateFlow<String?>(null)

    /** The diagram id of the LZ/PZ made last, while its first step is still to be taken. */
    public val made: StateFlow<String?> = _made.asStateFlow()

    public fun made(id: String) {
        _made.value = id
    }

    /** Its first step taken or put off: lets go of [id], or of whatever is held with none. Another LZ/PZ's is left alone. */
    public fun forget(id: String? = null) {
        if (id == null || _made.value == id) _made.value = null
    }
}
