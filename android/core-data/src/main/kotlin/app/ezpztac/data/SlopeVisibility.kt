package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the slope heat map is drawn over the open diagram. It is a view choice, like the base map: it changes what is drawn and nothing about the diagram (the slope is still
 * measured, and the summary tiles still say it). On at launch; it is not saved with the diagram, which the web would not understand.
 */
@Singleton
class SlopeVisibility @Inject constructor() {
    private val _shown = MutableStateFlow(true)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun toggle() = _shown.update { !it }
}
