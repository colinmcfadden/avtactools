package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How opaque the slope map's raster is drawn, as a whole percentage of the raster's own: the PNG's bands carry their own alpha (145 to 185 of 255, rising
 * with steepness so steep ground stands out), and this multiplies it, as the web's `SLOPE_OVERLAY_OPACITY` does. Set with a slider in steps of [STEP], from
 * [MIN], still to be seen (the slope map's switch is what hides it), to [MAX], the raster as the server drew it.
 */
object SlopeOpacity {
    /** The web's 0.55: its middle band near 36% and the range about 31 to 40%, light enough to read the imagery through. */
    const val DEFAULT = 55
    const val MIN = 10
    const val MAX = 100
    const val STEP = 5

    /** The slider's stops between its two ends (Material's `steps`). */
    const val STOPS_BETWEEN = (MAX - MIN) / STEP - 1

    /** [percent] as the slider keeps it: brought within [MIN] to [MAX], then onto the nearest step. */
    fun of(percent: Int): Int = (percent.coerceIn(MIN, MAX) + STEP / 2) / STEP * STEP
}

/**
 * Where the slope map's opacity is kept between launches: on this device, how this person likes the slope map over the imagery. Never saved with an
 * LZ/PZ or synced: the web has no such setting (its raster is drawn at a fixed 0.55), and a field it does not know in a diagram's view is dropped the
 * first time the web saves the diagram. A seam: the app keeps it in a preference, a test in memory.
 */
interface SlopeOpacityMemory {
    /** The opacity kept, a percentage; null when none has been. */
    fun slopeOpacity(): Int?

    fun setSlopeOpacity(percent: Int)
}

/** A [SlopeOpacityMemory] held in memory, for tests and previews. */
class InMemorySlopeOpacity(var kept: Int? = null) : SlopeOpacityMemory {
    override fun slopeOpacity(): Int? = kept

    override fun setSlopeOpacity(percent: Int) {
        kept = percent
    }
}

/**
 * The slope heat map over the open diagram: whether it is drawn, and how opaque. View choices, like the base map: they change what is drawn and nothing
 * about the diagram (the slope is still measured, and the summary tiles still say it). Drawn at launch, whatever it was before; not saved with the diagram,
 * which the web would not understand. Its opacity is this device's own and kept on it ([SlopeOpacityMemory]), for every LZ/PZ alike.
 */
@Singleton
class SlopeVisibility @Inject constructor(private val memory: SlopeOpacityMemory) {
    private val _shown = MutableStateFlow(true)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    // What was kept is read as the slider keeps it: a value off its steps or out of its range (another version's) is brought onto them.
    private val _opacity = MutableStateFlow(memory.slopeOpacity()?.let(SlopeOpacity::of) ?: SlopeOpacity.DEFAULT)

    /** How opaque the raster is drawn: a percentage of its own ([SlopeOpacity]). */
    val opacity: StateFlow<Int> = _opacity.asStateFlow()

    fun toggle() = _shown.update { !it }

    /** Draws the raster at [percent] (on the nearest step) from now on, and keeps it on the device. The same again keeps nothing. */
    fun setOpacity(percent: Int) {
        val kept = SlopeOpacity.of(percent)
        if (kept == _opacity.value) return
        _opacity.value = kept
        memory.setSlopeOpacity(kept)
    }
}
