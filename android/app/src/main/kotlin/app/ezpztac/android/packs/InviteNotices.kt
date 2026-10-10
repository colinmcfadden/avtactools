package app.ezpztac.android.packs

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.ezpztac.missionpacks.InviteState

/**
 * What the person is told about an invitation link the app was opened with: its words, its one action, and whether it stays until it is
 * answered (being accepted, or a failure that may pass) or goes by itself after a while.
 */
data class InviteNotice(val text: String, val actionLabel: String?, val staysUntilAnswered: Boolean)

/** The notice for [state], or null when there is nothing to say. The words are the app's own (`PackMessages`), never the server's. */
fun inviteNoticeFor(state: InviteState): InviteNotice? = when (state) {
    InviteState.None -> null
    InviteState.Accepting -> InviteNotice("Accepting the invitation…", actionLabel = null, staysUntilAnswered = true)
    is InviteState.Waiting -> InviteNotice(state.message, actionLabel = null, staysUntilAnswered = false)
    is InviteState.Joined -> InviteNotice(state.message, actionLabel = null, staysUntilAnswered = false)
    is InviteState.Failed ->
        if (state.retryable) InviteNotice(state.message, actionLabel = "Try again", staysUntilAnswered = true)
        else InviteNotice(state.message, actionLabel = null, staysUntilAnswered = false)
}

/**
 * Tells the person what became of an invitation link, as a toast (the redesign's `Snackbar`, docs/native-design) above the sheet, where it is
 * not missed: the web said nothing, and the person had to open the Library to find out. Every one has a close button. "Try again" asks again
 * ([onRetry]); closing one, or its time running out, puts it away ([onDismiss]), but never while the invitation is still being accepted. It is
 * keyed on the state, so a newer one replaces it, and one not yet put away is shown again after a turn of the phone.
 */
@Composable
fun InviteNotices(state: InviteState, snackbars: SnackbarHostState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(state) {
        val notice = inviteNoticeFor(state) ?: return@LaunchedEffect
        val result = snackbars.showSnackbar(
            message = notice.text,
            actionLabel = notice.actionLabel,
            withDismissAction = true,
            duration = if (notice.staysUntilAnswered) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> onRetry()
            SnackbarResult.Dismissed -> if (state != InviteState.Accepting) onDismiss()
        }
    }
}
