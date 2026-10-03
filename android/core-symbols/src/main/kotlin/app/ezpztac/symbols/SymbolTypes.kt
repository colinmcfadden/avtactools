package app.ezpztac.symbols

import android.graphics.Bitmap

/**
 * What to draw: a MIL-STD-2525C [sidc], the two labels milsymbol puts round it (a unit's designation and the formation above it, both
 * blank for none), and the symbol's [size], which is what the web passes milsymbol: 32 for a marker on the map, 64 for the builder's preview.
 */
data class SymbolSpec(
    val sidc: String,
    val uniqueDesignation: String = "",
    val higherFormation: String = "",
    val size: Int = MAP_SIZE,
) {
    val hasLabels: Boolean get() = uniqueDesignation.isNotEmpty() || higherFormation.isNotEmpty()

    companion object {
        /** The size the web draws a unit marker at, and the one the presets are pre-rendered at. */
        const val MAP_SIZE = 32
    }
}

/**
 * A symbol as milsymbol draws it: an SVG whose frame is [width] by [height] (in the SVG's own units, which at the size asked for are the
 * pixels the web draws), and [anchorX], [anchorY] the point in it that sits on the map position (the middle of the symbol's frame, which is
 * not the middle of the picture when labels are drawn round it).
 */
data class SymbolSvg(val svg: String, val width: Double, val height: Double, val anchorX: Double, val anchorY: Double)

/** A symbol as pixels, with [anchorX], [anchorY] the pixel of [bitmap] that sits on the map position. */
class RenderedSymbol(val bitmap: Bitmap, val anchorX: Float, val anchorY: Float)

/** What asking for a symbol came to. */
sealed interface SymbolOutcome {
    data class Drawn(val symbol: RenderedSymbol) : SymbolOutcome

    /** The code is not a symbol milsymbol knows how to draw. Nothing else would draw it either. */
    data object Invalid : SymbolOutcome

    /** This device cannot draw it right now: it is not one of the pre-rendered presets and the JavaScript sandbox is not there. */
    data object Unavailable : SymbolOutcome
}

/** A way of getting a symbol's SVG: from the pre-rendered presets, or from milsymbol running in the sandbox. */
fun interface SymbolSvgSource {
    suspend fun svg(spec: SymbolSpec): SvgResult
}

sealed interface SvgResult {
    data class Found(val svg: SymbolSvg) : SvgResult

    /** The source drew it, and it is not a symbol. */
    data object Invalid : SvgResult

    /** The source cannot say (it does not have this one, or cannot run). Another source may. */
    data object NotHere : SvgResult
}
