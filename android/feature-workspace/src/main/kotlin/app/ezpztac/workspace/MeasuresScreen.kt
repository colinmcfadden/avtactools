package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.MeasureKind
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.GlassSurface
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon
import app.ezpztac.planning.PhaseLines

/** What the measures' bar and dialog can do. */
class MeasuresActions(
    val addAtCrosshair: () -> Unit = {},
    val undoPoint: () -> Unit = {},
    val cancel: () -> Unit = {},
    val finish: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val confirmName: (String) -> Unit = {},
    val cancelNaming: () -> Unit = {},
)

internal fun measuresActionsOf(viewModel: MeasuresViewModel, crosshair: LatLon?) = MeasuresActions(
    addAtCrosshair = { viewModel.addAt(crosshair) }, undoPoint = viewModel::undoPoint, cancel = viewModel::cancel, finish = viewModel::finish,
    dismissError = viewModel::dismissError, confirmName = viewModel::confirmName, cancelNaming = viewModel::cancelNaming,
)

/**
 * The bar over the map while a backstop, a FLOT or a phase line is drawn (the web's `BackstopDrawBar`), and the name a phase line is finished under: absent while
 * nothing is drawn, so it takes no room and no taps.
 */
@Composable
fun MeasuresToolbarHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: MeasuresViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft ?: return
    val actions = measuresActionsOf(viewModel, crosshair)
    MeasuresToolbar(draft, state.error, actions, modifier)
    state.naming?.let { offered ->
        Dialog(onDismissRequest = actions.cancelNaming) { PhaseLineNameContent(offered, actions.confirmName, actions.cancelNaming) }
    }
}

/** What the web's draw bar says, its title and how to go on: a backstop is laid out beyond the LZ/PZ, a FLOT and a phase line along the line, in order. */
internal fun drawingWords(kind: MeasureKind): Pair<String, String> = when (kind) {
    MeasureKind.BACKSTOP -> "Drawing a backstop." to "Tap the map beyond the LZ/PZ to lay out the line."
    MeasureKind.FLOT -> "Drawing the FLOT." to "Tap the map along the line, in order."
    MeasureKind.PHASE_LINE -> "Drawing a phase line." to "Tap the map along the line, in order."
}

/** The points put down so far, as the web counts them: nothing for none. */
internal fun pointsSoFar(points: Int): String = when (points) {
    0 -> ""
    1 -> "1 point so far."
    else -> "$points points so far."
}

@Composable
fun MeasuresToolbar(draft: MeasureDraftUi, error: String?, actions: MeasuresActions, modifier: Modifier = Modifier) {
    val (title, hint) = drawingWords(draft.kind)
    GlassSurface(
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = title.removeSuffix(".") },
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        listOf(hint, pointsSoFar(draft.points)).filter { it.isNotEmpty() }.joinToString(" "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextAction("Cancel", onClick = actions.cancel)
            }
            // Two to a row, so the words keep to one line on a 360 dp phone at large text; *Finish* under them, as wide as the bar.
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                SecondaryButton("Add here", onClick = actions.addAtCrosshair, modifier = Modifier.weight(1f))
                SecondaryButton("Undo point", onClick = actions.undoPoint, enabled = draft.points > 0, modifier = Modifier.weight(1f))
            }
            PrimaryButton(if (draft.canFinish) "Finish" else "Add two points", onClick = actions.finish, enabled = draft.canFinish, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The name a phase line is finished under (the web's name dialog): what follows PL, in capitals, at most [PhaseLines.NAME_MAX] characters, the name offered chosen and
 * ready to type over. Cancelling keeps the line being drawn. Apart from its window, so it can be tried on its own (a text field in a dialog window never settles under the
 * test harness).
 */
@Composable
internal fun PhaseLineNameContent(offered: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var field by rememberSaveable(offered, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(offered, TextRange(0, offered.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { onConfirm(field.text) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semantics { paneTitle = "Name the phase line" }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text("Name the phase line", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "It is drawn with PL and this name at each end. You can rename it later.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = field,
                onValueChange = { typed ->
                    val upper = typed.text.uppercase()
                    if (upper.codePointCount(0, upper.length) <= PhaseLines.NAME_MAX + PL_ROOM || upper.length < field.text.length) field = typed.copy(text = upper)
                },
                label = { Text("Name") }, prefix = { Text("PL ") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), shape = RoundedCornerShape(Tokens.Radius.sm.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = submit) { Text("Finish phase line") }
            }
        }
    }
}

// A "PL " typed in front is dropped by the clean name, so it may be typed past the limit by that much.
private const val PL_ROOM = 3
