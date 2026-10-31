package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens
import java.time.Instant
import java.time.ZoneId

/** What the update and save-copy dialogs do, over [PackItemCopyViewModel]. */
class ItemDialogActions(
    val confirmUpdate: () -> Unit = {},
    val dismissUpdate: () -> Unit = {},
    val saveName: (String) -> Unit = {},
    val confirmSave: () -> Unit = {},
    val dismissSave: () -> Unit = {},
)

fun itemDialogActionsOf(viewModel: PackItemCopyViewModel) = ItemDialogActions(
    viewModel::confirmUpdate, viewModel::dismissUpdate, viewModel::saveName, viewModel::confirmSave, viewModel::dismissSave,
)

/** The update and save-copy dialogs (the design's PackDialogs B and D), wherever they were asked from. Neither can be put away while it is sent. */
@Composable
fun ItemDialogsHost(viewModel: PackItemCopyViewModel, tablet: Boolean = false) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    val save by viewModel.save.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { itemDialogActionsOf(viewModel) }
    update?.let { asked ->
        Dialog(onDismissRequest = actions.dismissUpdate, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !asked.sending, dismissOnClickOutside = !asked.sending)) {
            Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl.dp), contentAlignment = Alignment.Center) {
                UpdateContent(asked, actions, Modifier.widthIn(max = DIALOG_WIDTH), tablet = tablet, me = asked.me)
            }
        }
    }
    save?.let { asked ->
        Dialog(onDismissRequest = actions.dismissSave, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !asked.sending, dismissOnClickOutside = !asked.sending)) {
            Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl.dp), contentAlignment = Alignment.Center) {
                SaveCopyContent(asked, actions, Modifier.widthIn(max = DIALOG_WIDTH), tablet = tablet)
            }
        }
    }
}

/**
 * Updating a copy from its original (tested without its window): when the Library version changed, what in the pack the update replaces (in a box the design draws,
 * the count in bold), and *Update from original* in the error colour, as the design draws it: it replaces work.
 */
@Composable
fun UpdateContent(
    state: UpdateUi,
    actions: ItemDialogActions,
    modifier: Modifier = Modifier,
    tablet: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
    me: Int? = null,
) {
    val title = UpdateWords.title(state.name)
    Surface(modifier.semantics { paneTitle = title }, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = if (tablet) 16.dp else 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                UpdateWords.text(state.source, zone, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Spacing.lg.dp),
            )
            UpdateWords.replaced(state.source, loading = state.loading, me = me)?.let { replaced ->
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.Spacing.lg.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    Icon(
                        painterResource(if (tablet) EzpzIcons.warning else EzpzIcons.history), contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = if (tablet) app.ezpztac.designsystem.EzpzTheme.status.warning else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        buildAnnotatedString {
                            if (replaced.bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(replaced.bold) }
                            append(replaced.rest)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                }
            }
            state.error?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Tokens.Spacing.md.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xl.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = actions.dismissUpdate, enabled = !state.sending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                TextButton(
                    onClick = actions.confirmUpdate, enabled = !state.sending, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (state.sending) "Updating…" else "Update from original") }
            }
        }
    }
}

/** Naming a copy saved to the Library (tested without its window): the design's D, a phone's field with a clear button, a tablet's foot with where it goes. */
@Composable
fun SaveCopyContent(state: SaveCopyUi, actions: ItemDialogActions, modifier: Modifier = Modifier, tablet: Boolean = false) {
    val title = "Save a copy to your Library"
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(modifier.semantics { paneTitle = title }, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = if (tablet) 16.dp else 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(top = if (tablet) Tokens.Spacing.xs.dp else Tokens.Spacing.lg.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!tablet) Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Text(
                    buildAnnotatedString {
                        append("From ")
                        withStyle(SpanStyle(color = if (tablet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (tablet) FontWeight.Medium else null)) {
                            append("${state.packName}.")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.name, onValueChange = actions.saveName, label = { Text("Name") }, singleLine = true, readOnly = state.sending,
                isError = state.nameError != null,
                trailingIcon = if (!tablet && state.name.isNotEmpty() && !state.sending) {
                    { IconButton(onClick = { actions.saveName("") }) { Icon(painterResource(EzpzIcons.cancel), contentDescription = "Clear name") } }
                } else {
                    null
                },
                supportingText = {
                    Text(
                        state.nameError ?: "Only you can see this copy. Later changes in the pack do not update it.",
                        if (state.nameError != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { actions.confirmSave() }),
                shape = RoundedCornerShape(Tokens.Radius.sm.dp), modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xl.dp).focusRequester(focus),
            )
            state.error?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Tokens.Spacing.sm.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val goes: @Composable (Modifier) -> Unit = { m ->
                Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Icon(painterResource(if (tablet) EzpzIcons.folder else EzpzIcons.collectionsBookmark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("Saves to your Library", style = if (tablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!tablet) goes(Modifier.padding(top = Tokens.Spacing.lg.dp))
            Row(Modifier.fillMaxWidth().padding(top = if (tablet) Tokens.Spacing.lg.dp else Tokens.Spacing.xl.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                if (tablet) goes(Modifier.weight(1f))
                TextButton(onClick = actions.dismissSave, enabled = !state.sending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                TextButton(onClick = actions.confirmSave, enabled = !state.sending, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.sending) "Saving…" else "Save copy") }
            }
        }
    }
}

private val DIALOG_WIDTH = 540.dp
