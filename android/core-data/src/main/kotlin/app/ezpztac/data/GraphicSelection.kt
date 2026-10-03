package app.ezpztac.data

import app.ezpztac.model.GraphicRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which graphic of the open diagram the person is holding, if any: the map draws a halo on it and the inspector edits it. It belongs to
 * no screen, because the map and the sheet both read it. It does not outlive the diagram: whoever opens or closes one clears it.
 */
@Singleton
public class GraphicSelection @Inject constructor() {
    private val _selected = MutableStateFlow<GraphicRef?>(null)
    public val selected: StateFlow<GraphicRef?> = _selected.asStateFlow()

    public fun select(ref: GraphicRef) {
        _selected.value = ref
    }

    /** Selecting what is already selected lets go of it, so a second tap on a graphic is the way to put it down. */
    public fun toggle(ref: GraphicRef) {
        _selected.value = if (_selected.value == ref) null else ref
    }

    public fun clear() {
        _selected.value = null
    }
}
