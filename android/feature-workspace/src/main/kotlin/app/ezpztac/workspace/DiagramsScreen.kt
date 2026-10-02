package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.DiagramStatus
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus

class DiagramsActions(
    val startCreating: () -> Unit = {},
    val cancelCreating: () -> Unit = {},
    val create: (name: String, target: String) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
)

/**
 * The Diagrams tab of the sheet. [suggestedTarget] is the grid under the map's crosshair, offered as the new diagram's target: the usual way
 * to make one is to pan to the landing zone and tap New.
 */
@Composable
fun DiagramsHost(suggestedTarget: String?, modifier: Modifier = Modifier, viewModel: DiagramsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagramsContent(
        state = state, suggestedTarget = suggestedTarget, modifier = modifier,
        actions = DiagramsActions(
            startCreating = viewModel::startCreating, cancelCreating = viewModel::cancelCreating, create = viewModel::create, open = viewModel::open,
            rename = viewModel::rename, delete = viewModel::delete, resolve = viewModel::resolve, dismissError = viewModel::dismissError,
        ),
    )
}

@Composable
fun DiagramsContent(state: DiagramsUiState, suggestedTarget: String?, actions: DiagramsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Diagrams", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!state.creating) TextAction("New diagram", onClick = actions.startCreating)
        }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        if (state.creating) NewDiagramForm(suggestedTarget, state.busy, actions)
        if (state.rows.isEmpty() && !state.creating) {
            Text(
                "No diagrams yet. Pan the map to your landing zone and tap New diagram, or type a grid.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.rows.forEach { row -> DiagramListItem(row, actions) }
    }
}

@Composable
private fun NewDiagramForm(suggestedTarget: String?, busy: Boolean, actions: DiagramsActions) {
    var name by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable(suggestedTarget) { mutableStateOf(suggestedTarget.orEmpty()) }
    Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            EzpzTextField(
                value = target, onValueChange = { target = it }, label = "Target (MGRS grid or lat/long)", enabled = !busy,
                hint = "Taken from the middle of the map. Change it to plan somewhere else.",
            )
            EzpzTextField(
                value = name, onValueChange = { name = it }, label = "Name (optional)", enabled = !busy, imeAction = ImeAction.Done,
                onImeAction = { actions.create(name, target) }, hint = "Left blank, it is named for the grid.",
            )
            PrimaryButton("Create diagram", onClick = { actions.create(name, target) }, busy = busy, busyText = "Creating…")
            SecondaryButton("Cancel", onClick = actions.cancelCreating)
        }
    }
}

private fun statusLabel(status: DiagramStatus) = when (status) {
    DiagramStatus.DRAFT -> "Draft"
    DiagramStatus.TARGETED -> "Targeted"
    DiagramStatus.ANALYZED -> "Analyzed"
}

private fun syncLabel(row: DiagramRow) = when {
    row.conflictOf != null -> "Conflict copy"
    row.sync == SyncStatus.SYNCED -> "Synced"
    else -> "Waiting to sync"
}

@Composable
private fun DiagramListItem(row: DiagramRow, actions: DiagramsActions) {
    var rename by rememberSaveable(row.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(row.uuid) { mutableStateOf(false) }
    val border = if (row.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (row.isActive) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).clickable { actions.open(row.uuid) }
                    .semantics { contentDescription = "${row.name}. ${statusLabel(row.status)}. ${syncLabel(row)}" },
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The grid on a line of its own: beside the sync chip it wrapped in the middle of a number.
                    if (row.grid != null) {
                        Text(
                            row.grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(statusLabel(row.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncChip(row)
            }
            if (row.conflictOf != null) ConflictChoices(row, actions)
            if (rename != null) {
                EzpzTextField(value = rename.orEmpty(), onValueChange = { rename = it }, label = "Name", imeAction = ImeAction.Done, onImeAction = { actions.rename(row.uuid, rename.orEmpty()); rename = null })
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Save name", onClick = { actions.rename(row.uuid, rename.orEmpty()); rename = null })
                    TextAction("Cancel", onClick = { rename = null })
                }
            } else if (confirmDelete) {
                Banner("Delete \"${row.name}\"? It is removed from this device and from your account.", BannerKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Delete", onClick = { confirmDelete = false; actions.delete(row.uuid) })
                    TextAction("Keep it", onClick = { confirmDelete = false })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Rename", onClick = { rename = row.name })
                    TextAction("Delete", onClick = { confirmDelete = true })
                }
            }
        }
    }
}

@Composable
private fun SyncChip(row: DiagramRow) {
    val (color, container) = when {
        row.conflictOf != null -> EzpzTheme.status.warning to MaterialTheme.colorScheme.background
        row.sync == SyncStatus.SYNCED -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.surface
    }
    Surface(shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = container, border = BorderStroke(1.dp, color)) {
        Text(syncLabel(row), Modifier.padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The three ways to settle a conflict, each with what it does, because losing a planning document is the worst thing this app can do. */
@Composable
private fun ConflictChoices(row: DiagramRow, actions: DiagramsActions) {
    Banner(
        "This is your version, kept because another device changed the same diagram. Nothing was overwritten.",
        BannerKind.Info,
    )
    PrimaryButton("Keep both", onClick = { actions.resolve(row.uuid, SyncEngine.Resolution.KEEP_BOTH) })
    SecondaryButton("Keep mine (replaces the other version)", onClick = { actions.resolve(row.uuid, SyncEngine.Resolution.KEEP_MINE) })
    SecondaryButton("Keep theirs (discard mine)", onClick = { actions.resolve(row.uuid, SyncEngine.Resolution.KEEP_THEIRS) })
}
