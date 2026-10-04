package app.ezpztac.workspace

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon
import app.ezpztac.model.RadarDraft
import app.ezpztac.model.SymbolPresets
import app.ezpztac.model.ThreatDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreatsActions(
    val importFile: () -> Unit = {},
    val export: () -> Unit = {},
    val beginAdd: () -> Unit = {},
    val beginEdit: (String) -> Unit = {},
    val updateDraft: (ThreatDraft) -> Unit = {},
    val saveEdit: () -> Unit = {},
    val cancelEdit: () -> Unit = {},
    val select: (String) -> Unit = {},
    val deselect: () -> Unit = {},
    val toggleVisible: (String) -> Unit = {},
    val moveToCrosshair: (String) -> Unit = {},
    val nudge: (id: String, northFt: Double, eastFt: Double) -> Unit = { _, _, _ -> },
    /** Null when the threat is moved, else the words for the field. */
    val moveToText: (id: String, text: String) -> String? = { _, _ -> null },
    val remove: (String) -> Unit = {},
    val removeAll: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val dismissNote: () -> Unit = {},
)

/** The local-only threat picture. Files enter through Android's picker and leave only through the system share sheet. */
@Composable
fun ThreatsHost(
    onExport: (ExportFile) -> Unit,
    crosshair: LatLon?,
    modifier: Modifier = Modifier,
    crosshairGrid: String? = null,
    viewModel: ThreatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share by rememberUpdatedState(onExport)
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    SecureThreatWindow(context.findActivity(), state.threats.isNotEmpty() || state.editing != null)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val picked = withContext(Dispatchers.IO) { PickedFile.read(resolver, uri) }) {
                is PickedFile.Result.Read -> viewModel.importFile(picked.bytes)
                is PickedFile.Result.Failed -> viewModel.importFailed(
                    if (picked.message == PickedFile.TOO_BIG) "That file is too large to be a threat picture." else picked.message,
                )
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.exports.collect { share(it) } }
    ThreatsContent(
        state,
        ThreatsActions(
            importFile = { picker.launch(arrayOf("*/*")) }, export = viewModel::export, beginAdd = { viewModel.beginAdd(crosshair) },
            beginEdit = viewModel::beginEdit, updateDraft = viewModel::updateDraft, saveEdit = viewModel::saveEdit, cancelEdit = viewModel::cancelEdit,
            select = viewModel::select, deselect = viewModel::deselect, toggleVisible = viewModel::toggleVisible,
            moveToCrosshair = { viewModel.moveToCrosshair(it, crosshair) }, nudge = viewModel::nudge, moveToText = viewModel::moveToText,
            remove = viewModel::remove, removeAll = viewModel::removeAll, dismissError = viewModel::dismissError, dismissNote = viewModel::dismissNote,
        ),
        modifier, crosshairGrid,
    )
}

/** Threat rows can be sensitive even though they are unclassified, so keep their window out of screenshots and the app switcher. */
@Composable
private fun SecureThreatWindow(activity: Activity?, secure: Boolean) {
    DisposableEffect(activity, secure) {
        if (activity == null || !secure) return@DisposableEffect onDispose {}
        val alreadySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (!alreadySecure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ThreatsContent(state: ThreatsUiState, actions: ThreatsActions, modifier: Modifier = Modifier, crosshairGrid: String? = null) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Threats", style = MaterialTheme.typography.titleLarge)
                Text("Local only · ${state.threats.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(if (state.importing) "Reading…" else "Import .ths", onClick = actions.importFile, enabled = !state.importing)
                TextAction("Add", onClick = actions.beginAdd)
            }
        }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        state.note?.let { Banner(it, BannerKind.Info, actionLabel = "Dismiss", onAction = actions.dismissNote) }
        state.editing?.let { ThreatEditor(it, actions) }
        if (state.threats.isEmpty() && state.editing == null) {
            Text("No threats in the picture. Move the map centre to a position and add one, or import an AMPS .ths file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.held?.let { HeldThreatCard(it, crosshairGrid, actions) }
        state.threats.forEach { ThreatCard(it, actions) }
        if (state.threats.isNotEmpty()) PrimaryButton("Share .ths", onClick = actions.export, busy = state.exporting, busyText = "Building .ths…")
        if (state.threats.isNotEmpty()) {
            if (confirmClear) {
                Banner("Remove all ${state.threats.size} threats from this device? There is no undo.", BannerKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    SecondaryButton("Remove all", onClick = { actions.removeAll(); confirmClear = false }, modifier = Modifier.weight(1f))
                    PrimaryButton("Keep them", onClick = { confirmClear = false }, modifier = Modifier.weight(1f))             // the safe answer is the prominent one
                }
            } else {
                TextAction("Remove all threats", onClick = { confirmClear = true })
            }
        }
        Text("Threats are never synced or saved to the server. The sealed device copy is wiped at sign-out and 48 hours after its last change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One threat in the list: what it is, how far it reaches and where it is. The held one is outlined and has its own card, with what can be done to it. */
@Composable
private fun ThreatCard(row: ThreatRowUi, actions: ThreatsActions) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { actions.select(row.id) }.semantics { contentDescription = "Threat ${row.name}${if (row.held) ", held" else ""}${if (row.visible) "" else ", hidden"}" },
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (row.held) 2.dp else 1.dp, if (row.held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Text(row.name, fontWeight = FontWeight.SemiBold, color = if (row.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.symbol + if (row.visible) "" else " · hidden", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.ranges, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.grid, style = EzpzText.grid, color = MaterialTheme.colorScheme.onSurface)
            }
            Switch(
                checked = row.visible, onCheckedChange = { actions.toggleVisible(row.id) },
                modifier = Modifier.semantics { contentDescription = if (row.visible) "Hide ${row.name} on the map" else "Show ${row.name} on the map" },
            )
        }
    }
}

/** The held threat: all it says of itself, and what can be done with it (change it, move it by feet, to the crosshair or to a typed grid, remove it, with an ask first). */
@Composable
private fun HeldThreatCard(held: HeldThreatUi, crosshairGrid: String?, actions: ThreatsActions) {
    var confirmRemove by rememberSaveable(held.id) { mutableStateOf(false) }
    var moving by rememberSaveable(held.id) { mutableStateOf(false) }
    var step by rememberSaveable(held.id) { mutableIntStateOf(1) }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Held", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(held.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(held.symbol, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextAction("Done", onClick = actions.deselect)
            }
            Text(
                held.grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "Threat position ${held.grid}" },
            )
            Text(held.latLon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            held.radars.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            Text("Source: ${held.source}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (held.information.isNotBlank()) Text(held.information, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Edit", onClick = { actions.beginEdit(held.id) })
                TextAction(if (moving) "Done moving" else "Move", onClick = { moving = !moving })
                TextAction("Remove", onClick = { confirmRemove = true })
            }
            if (moving) {
                val feet = NUDGE_STEPS_FT[step.coerceIn(NUDGE_STEPS_FT.indices)].toDouble()
                NudgeSteps(step) { step = it }
                NudgePad(feet) { north, east -> actions.nudge(held.id, north, east) }
                SecondaryButton(
                    if (crosshairGrid != null) "Put at the crosshair · $crosshairGrid" else "Put at the crosshair",
                    onClick = { actions.moveToCrosshair(held.id) }, modifier = Modifier.semantics { contentDescription = "Put this threat at the crosshair" },
                )
                EntryRow("Move to a grid", "16S GD 66993 52949", "Go", KeyboardType.Ascii, onSubmit = { actions.moveToText(held.id, it) })
            }
            if (confirmRemove) {
                Banner("Remove ${held.name} from this threat picture? There is no undo.", BannerKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    SecondaryButton("Remove threat", onClick = { actions.remove(held.id); confirmRemove = false }, modifier = Modifier.weight(1f))
                    PrimaryButton("Keep it", onClick = { confirmRemove = false }, modifier = Modifier.weight(1f))                    // the safe answer is the prominent one
                }
            }
        }
    }
}

@Composable
private fun ThreatEditor(editing: ThreatEditUi, actions: ThreatsActions) {
    val draft = editing.draft
    Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text(if (editing.isNew) "Add threat" else "Edit threat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("%.5f, %.5f".format(java.util.Locale.ROOT, editing.at.lat, editing.at.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PresetChooser(draft) { actions.updateDraft(draft.copy(milstdId = it)) }
            EzpzTextField(draft.name, { actions.updateDraft(draft.copy(name = it)) }, "Name")
            EzpzTextField(draft.milstdId, { actions.updateDraft(draft.copy(milstdId = it.uppercase())) }, "MIL-STD symbol ID")
            EzpzTextField(draft.source, { actions.updateDraft(draft.copy(source = it)) }, "Source")
            EzpzTextField(draft.information, { actions.updateDraft(draft.copy(information = it)) }, "Information", singleLine = false)
            draft.radars.forEachIndexed { index, radar -> RadarEditor(radar) { changed ->
                actions.updateDraft(draft.copy(radars = draft.radars.mapIndexed { i, old -> if (i == index) changed else old }))
            } }
            editing.error?.let { Banner(it, BannerKind.Error) }                                                           // here, where Save is, not at the top of a long sheet
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                PrimaryButton(if (editing.isNew) "Add threat" else "Save threat", onClick = actions.saveEdit, modifier = Modifier.weight(1f))
                SecondaryButton("Cancel", onClick = actions.cancelEdit, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetChooser(draft: ThreatDraft, onChoose: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SecondaryButton("Common threat symbol", onClick = { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SymbolPresets.threats.forEach { preset -> DropdownMenuItem(text = { Text(preset.label) }, onClick = { open = false; onChoose(preset.sidc) }) }
    }
}

@Composable
private fun RadarEditor(radar: RadarDraft, onChange: (RadarDraft) -> Unit) {
    val label = radar.label
    Surface(shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Text("$label radar", fontWeight = FontWeight.SemiBold)
            EzpzTextField(radar.rangeNmi, { onChange(radar.copy(rangeNmi = it)) }, "$label range (nmi)", keyboardType = KeyboardType.Decimal)
            EzpzTextField(radar.antennaHeightFt, { onChange(radar.copy(antennaHeightFt = it)) }, "$label antenna height (ft)", keyboardType = KeyboardType.Decimal)
            ToggleRow("Antenna height is AGL", radar.aglNotMsl) { onChange(radar.copy(aglNotMsl = it)) }
            ToggleRow("Show range ring", radar.showRangeRings) { onChange(radar.copy(showRangeRings = it)) }
            ToggleRow("Show terrain mask when available", radar.showMask) { onChange(radar.copy(showMask = it)) }
            Text("Aircraft altitude bands", style = MaterialTheme.typography.labelLarge)
            radar.bandAltitudesFt.forEachIndexed { index, altitude ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Checkbox(checked = radar.bandsViewable.getOrElse(index) { true }, onCheckedChange = { value ->
                        onChange(radar.copy(bandsViewable = radar.bandsViewable.mapIndexed { i, old -> if (i == index) value else old }))
                    })
                    EzpzTextField(
                        altitude, { value -> onChange(radar.copy(bandAltitudesFt = radar.bandAltitudesFt.mapIndexed { i, old -> if (i == index) value else old })) },
                        "Band ${index + 1} altitude (ft)", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
