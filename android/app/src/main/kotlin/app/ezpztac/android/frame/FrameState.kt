package app.ezpztac.android.frame

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * What the frame shows, kept through a turn of the phone and never across launches: the panel [chosen], and whether the phone's frame is [hidden] (the owner's
 * decision of 2026-10-13: a tap on the map hides the row, the sheet and the bar, and leaves the readout, the map's buttons and what floats over the map).
 * Choosing a panel, by the person or in code, always shows the frame, even the panel already chosen: whatever opens, raises or changes a panel means it to be seen.
 */
@Stable
class FrameState(chosen: Panel = Panel.Lz, hidden: Boolean = false) {
    var chosen by mutableStateOf(chosen)
        private set

    var hidden by mutableStateOf(hidden)
        private set

    /** Shows [panel], and the frame with it. */
    fun choose(panel: Panel) {
        chosen = panel
        hidden = false
    }

    fun show() {
        hidden = false
    }

    fun hide() {
        hidden = true
    }

    /**
     * A tap on the map that held and put down nothing (`HomeViewModel.mapTapped`): it hides the frame, or shows it again. Never while an accessibility service
     * explores by touch ([exploring]): nothing on the screen would say the frame went, or how to bring it back.
     */
    fun tapped(exploring: Boolean) {
        hidden = !hidden && !exploring
    }

    internal companion object {
        val Saver = listSaver<FrameState, Any>(
            save = { listOf(it.chosen.name, it.hidden) },
            restore = { FrameState(Panel.valueOf(it[0] as String), it[1] as Boolean) },
        )
    }
}

@Composable
fun rememberFrameState(): FrameState = rememberSaveable(saver = FrameState.Saver) { FrameState() }

/**
 * Whether an accessibility service explores the screen by touch (TalkBack's touch exploration), followed while the app runs: the phone's frame never hides then.
 */
@Composable
fun rememberTouchExploration(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val exploring = remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring.value = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        exploring.value = manager?.isTouchExplorationEnabled == true                              // what changed before the listener was there
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}
