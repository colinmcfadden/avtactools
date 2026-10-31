package app.ezpztac.symbols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity

/**
 * The renderer the screens below this point draw symbols with. The app provides the real one; a screen that is tried without one (or a test)
 * gets none, and every symbol answers [SymbolOutcome.Unavailable], which a screen already has to show.
 */
val LocalSymbolRenderer = compositionLocalOf<SymbolRenderer?> { null }

/**
 * [spec] as it is drawn at the screen's density, so it is as large as the web draws it; null while it is being drawn. A symbol that is already
 * in the renderer's cache is there on the first frame (no flash when a map redraws).
 */
@Composable
fun rememberSymbol(spec: SymbolSpec): State<SymbolOutcome?> {
    val renderer = LocalSymbolRenderer.current
    val density = LocalDensity.current.density
    return produceState<SymbolOutcome?>(initialValue = null, spec, renderer, density) {
        value = renderer?.render(spec, density) ?: SymbolOutcome.Unavailable
    }
}
