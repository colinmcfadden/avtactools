package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon

class BoundaryActions(
    val start: () -> Unit = {},
    val addAtCrosshair: () -> Unit = {},
    val undoPoint: () -> Unit = {},
    val finish: () -> Unit = {},
    val cancel: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

private fun actionsOf(viewModel: BoundaryViewModel, crosshair: LatLon?) = BoundaryActions(
    start = viewModel::start, addAtCrosshair = { viewModel.addAtCrosshair(crosshair) }, undoPoint = viewModel::undoPoint,
    finish = viewModel::finish, cancel = viewModel::cancel, dismissError = viewModel::dismissError,
)

/** The sheet's part: how to start drawing. While drawing, the toolbar over the map is where the corners are added; this says so. */
@Composable
fun BoundaryHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: BoundaryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoundarySection(state, actionsOf(viewModel, crosshair), modifier)
}

/** The toolbar over the map, shown only while a boundary is being drawn. */
@Composable
fun BoundaryToolbarHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: BoundaryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BoundaryToolbarWhileDrawing(state, actionsOf(viewModel, crosshair), modifier)
}

/** The toolbar, only while a boundary is being drawn: with nothing to draw it takes no room and no taps. */
@Composable
fun BoundaryToolbarWhileDrawing(state: BoundaryUiState, actions: BoundaryActions, modifier: Modifier = Modifier) {
    if (state.drawing) BoundaryToolbar(state, actions, modifier)
}

@Composable
fun BoundarySection(state: BoundaryUiState, actions: BoundaryActions, modifier: Modifier = Modifier) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Boundary", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        when {
            state.drawing -> Text(
                "Drawing a boundary: ${pointsText(state.points)}. Tap the map to add a corner, then Finish. The buttons are over the map.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    if (state.drawnPoints > 0) "Drawn boundary: ${pointsText(state.drawnPoints)}. Analyze to measure it."
                    else "Draw the landing area yourself instead of letting the analysis find it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.cannotStart?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (confirm && state.canStart) {
                    Banner("Drawing a new boundary clears this analysis. The aircraft and other graphics stay.", BannerKind.Warning)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        TextAction("Draw", onClick = { confirm = false; actions.start() })
                        TextAction("Keep it", onClick = { confirm = false })
                    }
                } else {
                    SecondaryButton(
                        if (state.drawnPoints > 0) "Draw it again" else "Draw boundary", enabled = state.canStart,
                        onClick = { if (state.clears) confirm = true else actions.start() },
                    )
                }
            }
        }
    }
}

/** What is over the map while drawing: how many corners, and the four things to do. Over the sheet's peek, so it is reachable with the sheet down. */
@Composable
fun BoundaryToolbar(state: BoundaryUiState, actions: BoundaryActions, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = "Drawing a boundary" },
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Boundary: ${pointsText(state.points)}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (state.canFinish) "Tap the map for another corner, or Finish." else "Tap the map to add a corner. A boundary needs ${BoundaryDrawing.MIN_POINTS}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextAction("Cancel", onClick = actions.cancel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                SecondaryButton("Add here", onClick = actions.addAtCrosshair, modifier = Modifier.weight(1f))
                SecondaryButton("Undo", onClick = actions.undoPoint, enabled = state.points > 0, modifier = Modifier.weight(1f))
                PrimaryButton("Finish", onClick = actions.finish, enabled = state.canFinish, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun pointsText(count: Int) = if (count == 1) "1 point" else "$count points"
