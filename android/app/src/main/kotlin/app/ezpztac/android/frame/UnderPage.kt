package app.ezpztac.android.frame

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * The frame under a page drawn over it, left composed (the map, the panels and what they listen for, as they were): out of a screen reader's reach when
 * [unread]; and with [focusTo], out of the keyboard's too, a focus search that would go into it going to the page instead, so Tab and the arrows never
 * reach a control nobody can see (the 3D view's: a tablet's keyboard).
 */
internal fun Modifier.underPage(unread: Boolean, focusTo: FocusRequester? = null): Modifier =
    then(if (unread) Modifier.clearAndSetSemantics {} else Modifier)
        .then(
            if (focusTo == null) Modifier else Modifier.focusProperties {
                onEnter = { if (!focusTo.requestFocus(FocusDirection.Enter)) cancelFocusChange() }
            }.focusGroup(),
        )
