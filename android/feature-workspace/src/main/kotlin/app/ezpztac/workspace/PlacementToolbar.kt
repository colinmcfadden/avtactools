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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon

/** The toolbar over the map while a planning tool's mode is on. [crosshair] is where *Add here* puts one. */
@Composable
fun PlacementToolbarHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: GraphicsViewModel = hiltViewModel()) {
    val placing by viewModel.placing.collectAsStateWithLifecycle()
    placing?.let {
        PlacementToolbar(
            it, onAddHere = { viewModel.placeAt(crosshair) }, onDone = viewModel::donePlacing, onCancel = viewModel::cancelPlacing,
            onDismissError = viewModel::dismissPlacementError, modifier = modifier,
        )
    }
}

/**
 * What is over the map while a tool's mode is on (docs/native-design: "Tools place at the crosshair or a tap, with Done and Cancel"): which tool, how many
 * it has placed, *Add here* at the crosshair, *Done* to keep them and *Cancel* to take them all back. Above the sheet, so it is reachable with the sheet down.
 */
@Composable
fun PlacementToolbar(
    placing: PlacementUi,
    onAddHere: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = "Placing ${placing.tool.plural}"
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = title },
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            placing.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = onDismissError) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        when (placing.placed) {
                            0 -> "Tap the map to place one, or add at the crosshair."
                            1 -> "1 placed. Tap the map for another, or Done."
                            else -> "${placing.placed} placed. Tap the map for another, or Done."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Cancel takes back what the mode placed: said in its name, so it is not mistaken for closing the toolbar.
                TextAction(if (placing.placed > 0) "Cancel all" else "Cancel", onClick = onCancel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                SecondaryButton("Add here", onClick = onAddHere, modifier = Modifier.weight(1f))
                PrimaryButton("Done", onClick = onDone, modifier = Modifier.weight(1f))
            }
        }
    }
}
