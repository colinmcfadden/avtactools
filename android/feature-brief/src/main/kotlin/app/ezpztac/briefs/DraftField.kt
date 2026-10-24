package app.ezpztac.briefs

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

/**
 * A text field for one of the brief's answers (the web's `DraftField`): what is typed is kept here and committed when the person stops typing ([delayMs]), leaves the
 * field or presses Done on a single line, so one sentence is one change (in a pack, one line of its history). A value changed under the field (someone else in the
 * pack, a reset) is shown unless the field has the focus, where it would move the text out from under the cursor, and once the field is left. Leaving the screen
 * commits what waits, and so does the app going to the back, where it may be ended before the pause is over; [discarded], asked as it leaves, lets it go instead
 * (a reset of what it holds). A field belongs to one answer: callers key it by the answer it is for, so a list that changes under it never moves its text.
 *
 * **Not like the web**, whose field commits whatever it shows when it is left: a field the person did not type in commits nothing, so leaving it never writes back
 * the words it showed over someone else's change that came while it had the focus.
 */
@Composable
internal fun DraftField(
    value: String,
    onCommit: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    multiline: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    delayMs: Long = DRAFT_DELAY_MS,
    discarded: () -> Boolean = { false },
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableIntStateOf(0) }                       // keystrokes since the last commit: the pause is counted from the last
    val latestValue by rememberUpdatedState(value)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestDiscarded by rememberUpdatedState(discarded)
    val pending = remember { booleanArrayOf(false) }

    // The text as it is now, not as the last composition drew it: a keystroke and a Done in one frame commit the keystroke too. Let go instead once it is
    // discarded, however the field is left (it loses the focus as it leaves the screen, before it is disposed).
    fun commit() {
        if (!pending[0]) return
        pending[0] = false
        if (!latestDiscarded() && text != latestValue) latestCommit(text)
    }

    LaunchedEffect(value) { if (!focused) text = value }
    LaunchedEffect(typed) {
        if (typed == 0) return@LaunchedEffect
        delay(delayMs)
        commit()
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { commit() }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            pending[0] = true
            typed++
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = !multiline,
        minLines = if (multiline) minLines else 1,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { state ->
            val was = focused
            focused = state.isFocused
            if (was && !state.isFocused) {
                // Left: what was typed goes; with nothing typed, what the brief holds now is shown (someone else's change that came while it had the focus).
                if (pending[0]) commit() else text = latestValue
            }
        },
    )
}

/** How long a field waits after the last keystroke before it commits (the web's 600 ms). */
internal const val DRAFT_DELAY_MS = 600L
