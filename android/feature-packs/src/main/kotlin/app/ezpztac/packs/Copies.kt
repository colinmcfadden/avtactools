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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens

/** What the Add to a Mission Pack dialog does, over [PackCopyViewModel]. */
class AddToPackActions(
    val choose: (String) -> Unit = {},
    val add: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

fun addToPackActionsOf(viewModel: PackCopyViewModel) = AddToPackActions(viewModel::choose, viewModel::addCopy, viewModel::loadPacks, viewModel::dismiss)

/**
 * The dialog choosing the pack a copy goes into (the design's PackDialogs A), while it is asked: 540 dp at most, 24 dp from a phone's edges. It can be closed while
 * the copy is made (the copy goes on, and what it came to is said in a toast).
 */
@Composable
fun AddToPackHost(viewModel: PackCopyViewModel, tablet: Boolean = false) {
    val state by viewModel.add.collectAsStateWithLifecycle()
    val asked = state ?: return
    val actions = remember(viewModel) { addToPackActionsOf(viewModel) }
    Dialog(onDismissRequest = actions.dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl.dp), contentAlignment = Alignment.Center) {
            AddToPackContent(asked, actions, Modifier.widthIn(max = DIALOG_WIDTH), tablet = tablet)
        }
    }
}

/**
 * What the dialog holds (tested without its window): the item being added, the packs to choose from (one that is finished, or that this person can only view,
 * cannot take it), the design's note, what went wrong, and *Add copy*. A [tablet] draws the design's AT rows (a tile at the row's end, the chosen row tinted).
 */
@Composable
fun AddToPackContent(state: AddToPackUi, actions: AddToPackActions, modifier: Modifier = Modifier, tablet: Boolean = false) {
    val title = "Add to a Mission Pack"
    Surface(modifier.semantics { paneTitle = title }, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = if (tablet) 16.dp else 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Adding “${state.name}”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (tablet) Tokens.Spacing.xs.dp else Tokens.Spacing.lg.dp),
            )
            val packs = state.packs
            when {
                packs == null && state.listError != null -> Banner(
                    state.listError, BannerKind.Error, Modifier.padding(top = Tokens.Spacing.lg.dp), actionLabel = "Try again", onAction = actions.retry,
                )
                packs == null -> Text(
                    "Loading your packs…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.Spacing.lg.dp),
                )
                packs.isEmpty() -> Text(PackCopyViewModel.NO_PACKS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Spacing.lg.dp))
                // The design's radio group, named for a screen reader.
                else -> Column(Modifier.padding(top = Tokens.Spacing.md.dp).semantics { contentDescription = "Mission Pack" }.selectableGroup()) {
                    packs.forEach { pack -> ChoiceRow(pack, chosen = pack.uuid == state.chosen, enabled = !state.sending, tablet = tablet, onChoose = { actions.choose(pack.uuid) }) }
                }
            }
            Text(
                PackCopyViewModel.NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Spacing.lg.dp),
            )
            state.error?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Tokens.Spacing.md.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = if (tablet) Tokens.Spacing.lg.dp else Tokens.Spacing.xl.dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End),
            ) {
                // While the copy is made the dialog can still be closed: the copy goes on, and a toast says what it came to.
                TextButton(onClick = actions.dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.sending) "Close" else "Cancel") }
                TextButton(onClick = actions.add, enabled = !state.sending && state.chosen != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.sending) "Adding…" else "Add copy")
                }
            }
        }
    }
}

/**
 * A pack to choose: a radio, its name after the groups mark, and what it is to this person (a finished one's after a lock). On a [tablet] the design's AT row:
 * no mark before the name, a tile at the end (the groups mark, or a lock for a finished pack), the chosen row tinted.
 */
@Composable
private fun ChoiceRow(pack: PackChoice, chosen: Boolean, enabled: Boolean, tablet: Boolean, onChoose: () -> Unit) {
    val usable = pack.usable && enabled
    val mark = if (pack.usable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tablet) 60.dp else if (pack.finished) 72.dp else 64.dp)
            .then(if (tablet && chosen) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp)) else Modifier)
            .selectable(selected = chosen, enabled = usable, role = Role.RadioButton, onClick = onChoose),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, onClick = null, enabled = usable, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                if (!tablet) Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, modifier = Modifier.size(18.dp), tint = mark)
                Text(
                    pack.name, style = if (tablet) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.bodyLarge,
                    color = if (pack.usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                if (pack.finished && !tablet) Icon(painterResource(EzpzIcons.lock), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pack.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tablet) {
            Box(
                Modifier.padding(start = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp).size(40.dp)
                    .background(if (pack.finished) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (pack.finished) EzpzIcons.lock else EzpzIcons.groupsFill), contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (pack.finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

private val DIALOG_WIDTH = 540.dp
