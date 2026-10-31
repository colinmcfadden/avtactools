package app.ezpztac.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.SectionLabel
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon
import app.ezpztac.model.RadarDraft
import app.ezpztac.model.SymbolPresets
import app.ezpztac.model.ThreatDraft
import app.ezpztac.symbols.SymbolOutcome
import app.ezpztac.symbols.SymbolSpec
import app.ezpztac.symbols.rememberSymbol
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
    val toggleVisible: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    /** Removes the threat the form is for, and closes the form. */
    val removeEditing: () -> Unit = {},
    /** Move the threat in the form [northFt] feet north and [eastFt] east (negative: south, west); applied by Save. */
    val formNudge: (northFt: Double, eastFt: Double) -> Unit = { _, _ -> },
    val formToCrosshair: () -> Unit = {},
    /** Put the threat in the form at a grid typed: null when it was moved, else the words for the field. */
    val formToText: (String) -> String? = { null },
    /** Ask the server what this threat's radars can see over the terrain (the one thing that sends a threat anywhere). */
    val showMask: (String) -> Unit = {},
    val hideMask: (String) -> Unit = {},
    val removeAll: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val dismissNote: () -> Unit = {},
)

/**
 * The Threats panel (docs/native-design: Threats) and the form for one threat, a full-screen dialog over everything. Files come in through the import review
 * ([onImport] opens the picker for it), as every file does, and leave only through the system share sheet ([onExport]).
 */
@Composable
fun ThreatsHost(
    onExport: (ExportFile) -> Unit,
    onImport: () -> Unit,
    crosshair: LatLon?,
    modifier: Modifier = Modifier,
    crosshairGrid: String? = null,
    viewModel: ThreatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share by rememberUpdatedState(onExport)
    LaunchedEffect(viewModel) { viewModel.exports.collect { share(it) } }
    val actions = ThreatsActions(
        importFile = onImport, export = viewModel::export, beginAdd = { viewModel.beginAdd(crosshair) },
        beginEdit = viewModel::beginEdit, updateDraft = viewModel::updateDraft, saveEdit = viewModel::saveEdit, cancelEdit = viewModel::cancelEdit,
        select = viewModel::select, toggleVisible = viewModel::toggleVisible, remove = viewModel::remove, removeEditing = viewModel::removeEditing,
        formNudge = viewModel::formNudge, formToCrosshair = { viewModel.formToCrosshair(crosshair) }, formToText = viewModel::formToText,
        showMask = viewModel::showMask, hideMask = viewModel::hideMask, removeAll = viewModel::removeAll, dismissError = viewModel::dismissError,
        dismissNote = viewModel::dismissNote,
    )
    ThreatsContent(state, actions, modifier, crosshairGrid)
    state.editing?.let { ThreatFormDialog(it, actions, crosshairGrid) }
}

// -- The panel ----------------------------------------------------------------------------------------------------------------------

/**
 * The Threats panel (the design's): where threats are kept, *Add threat* at the crosshair and *Import .ths*, the threats on the map with their reach and terrain
 * mask, each hidden, changed or removed from its row, the `.ths` for AMPS, and *Remove all*, asked about first.
 */
@Composable
fun ThreatsContent(state: ThreatsUiState, actions: ThreatsActions, modifier: Modifier = Modifier, crosshairGrid: String? = null) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        KeptNotice()
        Row(Modifier.padding(top = Tokens.Spacing.xs.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            PillButton("Add threat", onClick = actions.beginAdd, icon = EzpzIcons.addLocationAlt, modifier = Modifier.weight(1f))
            PillButton("Import .ths", onClick = actions.importFile, icon = EzpzIcons.fileOpen, style = PillStyle.Tonal, modifier = Modifier.weight(1f))
        }
        Text(
            if (crosshairGrid != null) "Add threat puts one at the crosshair, $crosshairGrid." else "Add threat puts one at the crosshair.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        state.note?.let { Banner(it, BannerKind.Info, actionLabel = "Dismiss", onAction = actions.dismissNote) }
        if (state.threats.isEmpty()) {
            Text(
                "No threats on the map. Add one at the crosshair, or import an AMPS .ths file.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
            )
        } else {
            SectionLabel("On the map · ${state.threats.size}", Modifier.padding(top = Tokens.Spacing.sm.dp))
            state.threats.forEach { row -> ThreatRow(row, actions, onRemove = { confirmRemove = row.id }) }
            SectionLabel("Export", Modifier.padding(top = Tokens.Spacing.sm.dp))
            PillButton(
                if (state.exporting) "Building .ths…" else ".ths for AMPS", onClick = actions.export, icon = EzpzIcons.share, style = PillStyle.Tonal,
                enabled = !state.exporting, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = Tokens.Spacing.xs.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                Icon(painterResource(EzpzIcons.info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    "The .ths file is built on this device. Showing a terrain mask sends a threat's position and radars to the server once. Nothing is kept.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = Tokens.Spacing.sm.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { confirmClear = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(painterResource(EzpzIcons.deleteSweep), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Tokens.Spacing.sm.dp))
                    Text("Remove all threats")
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Remove all threats?",
            text = "${countOf(state.threats.size, "threat")} ${if (state.threats.size == 1) "comes" else "come"} off the map. They are not saved anywhere else, so they cannot be brought back.",
            confirm = "Remove all",
            onConfirm = { confirmClear = false; actions.removeAll() },
            onCancel = { confirmClear = false },
        )
    }
    confirmRemove?.let { id ->
        val row = state.threats.firstOrNull { it.id == id }
        if (row == null) {
            confirmRemove = null
        } else {
            ConfirmDialog(
                title = "Remove ${row.name}?",
                text = "It comes off the map. It is not saved anywhere else, so it cannot be brought back.",
                confirm = "Remove",
                onConfirm = { confirmRemove = null; actions.remove(id) },
                onCancel = { confirmRemove = null },
            )
        }
    }
}

/** Where the threats are kept, said first (the design's notice, with the owner's decision of 2026-10-12: no expiry until they are saved with the account, step *T*). */
@Composable
private fun KeptNotice() {
    val warning = EzpzTheme.status.warning
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = warning.copy(alpha = 0.10f), border = BorderStroke(1.dp, warning.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = Tokens.Spacing.md.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.md.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Icon(painterResource(EzpzIcons.lockFill), contentDescription = null, tint = warning, modifier = Modifier.size(20.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Threats stay on this device only") }
                    append(", in an encrypted file that is cleared when you sign out. They are not saved to your account or shared with a Mission Pack.")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A choice that cannot be taken back, asked about first (the design's Confirm). Cancel and the choice are both plain: neither is made the easy one. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * One threat (the design's row): its symbol, name, reach and terrain mask, then hide, change and remove. A tap holds it on the map (the map marks it, and the
 * held bar has the rest), if it is shown.
 */
@Composable
private fun ThreatRow(row: ThreatRowUi, actions: ThreatsActions, onRemove: () -> Unit) {
    val dim = if (row.visible) Modifier else Modifier.alpha(0.6f)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(if (row.held) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(Tokens.Radius.md.dp))
            // A hidden threat is not drawn, so it cannot be held: its row is not a target.
            .clickable(enabled = row.visible, onClickLabel = if (row.held) "Put down" else "Hold on the map", role = Role.Button) { actions.select(row.id) }
            .semantics { selected = row.held }
            .padding(vertical = Tokens.Spacing.sm.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(dim.padding(end = Tokens.Spacing.md.dp)) { ThreatSymbol(row.sidc) }
            Column(dim.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The reach is what a crew reads the row for: it wraps rather than being cut.
                Text(row.ranges + if (row.visible) "" else " · hidden", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The buttons side by side, as the design draws them: 48 dp each, no gap between.
            RowIcon(if (row.visible) EzpzIcons.visibility else EzpzIcons.visibilityOff, if (row.visible) "Hide ${row.name}" else "Show ${row.name}") { actions.toggleVisible(row.id) }
            RowIcon(EzpzIcons.edit, "Edit ${row.name}") { actions.beginEdit(row.id) }
            RowIcon(EzpzIcons.delete, "Remove ${row.name}", onClick = onRemove)
        }
        // Under the name, with the row's width to itself: beside three buttons on a 360 dp phone with large text it would lose its words.
        MaskChip(row.mask, dim.padding(start = 52.dp, top = 6.dp))
    }
}

/** What a threat's terrain mask is, in a chip: *Terrain mask on* while it is drawn, otherwise what is in the way, or *Rings only*. */
@Composable
private fun MaskChip(status: ThreatMaskUi.Status, modifier: Modifier = Modifier) {
    when (status) {
        ThreatMaskUi.Status.SHOWING -> StateChip("Terrain mask on", StatusTone.Info, modifier, icon = EzpzIcons.landscape)
        ThreatMaskUi.Status.WORKING -> StateChip("Working out the mask…", StatusTone.Info, modifier, icon = EzpzIcons.landscape)
        ThreatMaskUi.Status.FAILED -> StateChip("Mask failed", StatusTone.Error, modifier)
        ThreatMaskUi.Status.OUT_OF_DATE -> StateChip("Mask out of date", StatusTone.Warning, modifier)
        ThreatMaskUi.Status.NOTHING_VISIBLE -> StateChip("Mask: nothing in sight", StatusTone.Neutral, modifier)
        ThreatMaskUi.Status.OFF -> StateChip("Rings only", StatusTone.Neutral, modifier)
    }
}

/**
 * A threat's MIL-STD symbol in the design's tinted circle, as the map draws it, or the design's red diamond while it is drawn, or where it cannot be (a code
 * milsymbol refuses, or no JavaScript sandbox on this device).
 */
@Composable
private fun ThreatSymbol(sidc: String) {
    val outcome by rememberSymbol(SymbolSpec(sidc, size = 26))
    val red = MaterialTheme.colorScheme.error
    Box(Modifier.size(40.dp).background(red.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        when (val drawn = outcome) {
            is SymbolOutcome.Drawn -> Image(drawn.symbol.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(26.dp))
            else -> Canvas(Modifier.size(22.dp)) {
                val u = size.width / 22f
                val diamond = Path().apply { moveTo(11f * u, 2f * u); lineTo(20f * u, 11f * u); lineTo(11f * u, 20f * u); lineTo(2f * u, 11f * u); close() }
                drawPath(diamond, red)
                drawPath(diamond, Color.White, style = Stroke(1.4f * u))
                drawLine(Color.White, Offset(11f * u, 6.5f * u), Offset(11f * u, 15.5f * u), 1.3f * u)
                drawLine(Color.White, Offset(6.5f * u, 11f * u), Offset(15.5f * u, 11f * u), 1.3f * u)
            }
        }
    }
}

// -- The form -----------------------------------------------------------------------------------------------------------------------

/**
 * The form for one threat, as a full-screen dialog over everything (the plan's S5): Close, its name, Save. Back asks first when something was typed or
 * moved, since Save is the only way it is kept.
 */
@Composable
fun ThreatFormDialog(editing: ThreatEditUi, actions: ThreatsActions, crosshairGrid: String?) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = { if (editing.changed) confirmLeave = true else actions.cancelEdit() }
    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ThreatFormContent(editing, actions, crosshairGrid, onClose = leave)
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (editing.isNew) "Discard this threat?" else "Discard your changes?") },
            text = { Text(if (editing.isNew) "It is not on the map until you add it." else "What you changed to ${editing.startedDraft.name.ifBlank { "this threat" }} is not kept.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; actions.cancelEdit() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Discard") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/**
 * What the form holds (a page of its own, so it can be tested without a window): where the threat is and how to move it, its symbol, name, source and notes,
 * each radar, its terrain mask (a threat already on the map), and removing it. A refusal is said under the bar, where it is in sight however far the form is
 * scrolled.
 */
@Composable
fun ThreatFormContent(editing: ThreatEditUi, actions: ThreatsActions, crosshairGrid: String?, modifier: Modifier = Modifier, onClose: () -> Unit = actions.cancelEdit) {
    val draft = editing.draft
    val title = if (editing.isNew) "Add threat" else "Edit threat"
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize().semantics { paneTitle = title }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(painterResource(EzpzIcons.close), contentDescription = "Close without saving") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp).semantics { heading() })
                TextButton(onClick = actions.saveEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editing.isNew) "Add" else "Save", fontWeight = FontWeight.SemiBold) }
            }
            editing.error?.let { Box(Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp)) { Banner(it, BannerKind.Error) } }
            Column(
                Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.xl.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                FormSection("What it is")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                    ThreatSymbol(draft.milstdId)
                    Column(Modifier.weight(1f)) {
                        Text(SymbolPresets.threats.firstOrNull { it.sidc == draft.milstdId }?.label ?: "Custom symbol", style = MaterialTheme.typography.bodyLarge)
                        PresetChooser { actions.updateDraft(draft.copy(milstdId = it)) }
                    }
                }
                EzpzTextField(draft.name, { actions.updateDraft(draft.copy(name = it)) }, "Name")
                EzpzTextField(draft.milstdId, { actions.updateDraft(draft.copy(milstdId = it.uppercase())) }, "MIL-STD symbol ID", capitalization = KeyboardCapitalization.Characters)
                EzpzTextField(draft.source, { actions.updateDraft(draft.copy(source = it)) }, "Source")
                EzpzTextField(draft.information, { actions.updateDraft(draft.copy(information = it)) }, "Information", singleLine = false)

                FormSection("Where it is")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        editing.grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), modifier = Modifier.weight(1f)
                            .semantics { contentDescription = "Threat position ${editing.grid}" },
                    )
                    TextButton(
                        onClick = { moving = !moving },
                        modifier = Modifier.semantics { contentDescription = if (moving) "Hide the controls to move this threat" else "Show controls to move this threat" },
                    ) { Text(if (moving) "Done moving" else "Move") }
                }
                if (moving) FormPosition(actions, crosshairGrid)

                draft.radars.forEachIndexed { index, radar ->
                    RadarEditor(radar) { changed -> actions.updateDraft(draft.copy(radars = draft.radars.mapIndexed { i, old -> if (i == index) changed else old })) }
                }

                editing.mask?.let { mask ->
                    FormSection("Terrain mask")
                    TerrainMask(editing.id.orEmpty(), mask, actions)
                }
                if (!editing.isNew) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { confirmRemove = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(painterResource(EzpzIcons.delete), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Tokens.Spacing.sm.dp))
                        Text("Remove this threat")
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${editing.startedDraft.name.ifBlank { "this threat" }}?",
            text = "It comes off the map. It is not saved anywhere else, so it cannot be brought back.",
            confirm = "Remove",
            onConfirm = { confirmRemove = false; actions.removeEditing() },
            onCancel = { confirmRemove = false },
        )
    }
}

@Composable
private fun FormSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = EzpzTheme.status.accent, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp).semantics { heading() })
}

/**
 * Moving the threat in the form: a pad of arrows with a step, the crosshair (the form covers the map, so its grid is said), or a grid typed in. Applied by Save
 * with the rest, so Close leaves the threat where it was.
 */
@Composable
private fun FormPosition(actions: ThreatsActions, crosshairGrid: String?) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val feet = NUDGE_STEPS_FT[step.coerceIn(NUDGE_STEPS_FT.indices)].toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        NudgeSteps(step) { step = it }
        NudgePad(feet) { north, east -> actions.formNudge(north, east) }
        Text(
            if (crosshairGrid != null) "Crosshair: $crosshairGrid" else "The crosshair is where the map was left.",
            style = if (crosshairGrid != null) EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize) else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PillButton(
            "Put at the crosshair", onClick = actions.formToCrosshair, icon = EzpzIcons.myLocation, style = PillStyle.Outlined,
            modifier = Modifier.semantics { contentDescription = "Put this threat at the crosshair" + (crosshairGrid?.let { ", $it" } ?: "") },
        )
        EntryRow("Move to a grid", "16S GD 66993 52949", "Go", KeyboardType.Ascii, onSubmit = actions.formToText)
    }
}

@Composable
private fun PresetChooser(onChoose: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Choose a common threat symbol" }) {
            Text("Common threat symbols")
            Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SymbolPresets.threats.forEach { preset -> DropdownMenuItem(text = { Text(preset.label) }, onClick = { open = false; onChoose(preset.sidc) }) }
        }
    }
}

@Composable
private fun RadarEditor(radar: RadarDraft, onChange: (RadarDraft) -> Unit) {
    val label = radar.label
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Text("$label radar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                EzpzTextField(radar.rangeNmi, { onChange(radar.copy(rangeNmi = it)) }, "Range (nmi)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                EzpzTextField(radar.antennaHeightFt, { onChange(radar.copy(antennaHeightFt = it)) }, "Antenna (ft)", Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
            }
            ToggleRow("Antenna height is AGL", radar.aglNotMsl) { onChange(radar.copy(aglNotMsl = it)) }
            ToggleRow("Show the range ring", radar.showRangeRings) { onChange(radar.copy(showRangeRings = it)) }
            ToggleRow("Show the terrain mask when it is worked out", radar.showMask) { onChange(radar.copy(showMask = it)) }
            Text("Aircraft altitude bands", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            radar.bandAltitudesFt.forEachIndexed { index, altitude ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Checkbox(
                        checked = radar.bandsViewable.getOrElse(index) { true },
                        onCheckedChange = { value -> onChange(radar.copy(bandsViewable = radar.bandsViewable.mapIndexed { i, old -> if (i == index) value else old })) },
                        modifier = Modifier.semantics { contentDescription = "Show band ${index + 1} on the mask" },
                    )
                    EzpzTextField(
                        altitude, { value -> onChange(radar.copy(bandAltitudesFt = radar.bandAltitudesFt.mapIndexed { i, old -> if (i == index) value else old })) },
                        "Band ${index + 1} altitude (ft)", modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
    }
}

/** A switch as one row a person can tap anywhere on, said with its words. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The terrain mask of a threat on the map: where its radars can see over the terrain, as the web draws it. It is worked out by the server, so it is asked for with
 * a button and the form says what is sent. It is worked out for the threat as it is on the map (what Save has kept), not as it is being typed.
 */
@Composable
private fun TerrainMask(id: String, mask: ThreatMaskUi, actions: ThreatsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        when (mask.status) {
            ThreatMaskUi.Status.OFF, ThreatMaskUi.Status.FAILED -> {
                mask.message?.let { Banner(it, BannerKind.Warning) }
                PillButton(
                    if (mask.status == ThreatMaskUi.Status.FAILED) "Try the terrain mask again" else "Show terrain mask", onClick = { actions.showMask(id) },
                    icon = EzpzIcons.landscape, style = PillStyle.Tonal,
                )
            }
            ThreatMaskUi.Status.WORKING -> Text("Working out the mask…", style = MaterialTheme.typography.bodyMedium)
            ThreatMaskUi.Status.SHOWING -> {
                Text("Shown on the map: where an aircraft at each altitude set is in sight of the radar over the terrain.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Hide mask", onClick = { actions.hideMask(id) })
                    TextAction("Update", onClick = { actions.showMask(id) })
                }
            }
            ThreatMaskUi.Status.OUT_OF_DATE, ThreatMaskUi.Status.NOTHING_VISIBLE -> {
                mask.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    TextAction("Update mask", onClick = { actions.showMask(id) })
                    TextAction("Hide", onClick = { actions.hideMask(id) })
                }
            }
        }
        Text(
            "It is worked out for the threat as it is on the map: save first to include what you changed here. Showing it sends this threat's position and radar settings to the " +
                "server once. The server keeps nothing.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
