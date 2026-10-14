package app.ezpztac.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * What the phone's frame does to what it hides (a tap on the map hides the row, the sheet and the bar): while [hidden] nothing in it is read by a screen reader or
 * takes a touch, though it may still be drawn as it slides away; once [away] too (all the way off the screen) it is neither measured nor placed. It stays composed
 * throughout, so what it holds (a scroll, what was typed, the dialogs it opened, what it listens for) is there when it comes back.
 */
fun Modifier.keptAway(hidden: Boolean, away: Boolean): Modifier = when {
    hidden && away -> this.clearAndSetSemantics {}.layout { _, _ -> layout(0, 0) {} }
    hidden -> this.clearAndSetSemantics {}.then(TakesNoTouch)
    else -> this
}

/**
 * Takes every touch on what it is applied to before anything in it sees one, so nothing there answers (it is on its way off the screen): what is there asks for
 * touches nobody has taken, and these are taken.
 */
val TakesNoTouch: Modifier = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
