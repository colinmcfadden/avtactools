package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import app.ezpztac.model.Doghouses
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft

/** A draft in what survives the screen being turned: its five parts as text. */
private val UnitDraftSaver = listSaver<UnitDraft, String>(
    save = { listOf(it.affiliation, it.functionId, it.echelon, it.uniqueDesignation, it.higherFormation) },
    restore = { UnitDraft(it[0], it[1], it[2], it[3], it[4]) },
)

/** What the panel can ask for. The crosshair is not in here: the host supplies it, so the panel stays a function of its state. */
class GraphicsActions(
    val place: (GraphicKind) -> Unit = {},
    val select: (GraphicRef) -> Unit = {},
    val deselect: () -> Unit = {},
    val nudge: (northFt: Double, eastFt: Double) -> Unit = { _, _ -> },
    val moveToCrosshair: () -> Unit = {},
    val moveToText: (String) -> Unit = {},
    val rotateBy: (Double) -> Unit = {},
    val setRotation: (Double) -> Unit = {},
    val changeReach: (Double) -> Unit = {},
    val tipToCrosshair: () -> Unit = {},
    val setDirection: (String) -> Unit = {},
    /** Adds a unit made in the unit builder at the crosshair; applies the builder to the held unit. */
    val addUnit: (UnitDraft) -> Unit = {},
    val updateUnit: (UnitDraft) -> Unit = {},
    /** A doghouse's fields: each takes what was typed and answers with what is wrong with it, or null when it was taken. */
    val setDoghouseLabel: (String) -> String? = { null },
    val setDoghouseTime: (String) -> String? = { null },
    val setDoghouseDistance: (String) -> String? = { null },
    val setDoghouseAirspeed: (String) -> String? = { null },
    val delete: () -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

/**
 * The planning graphics of the open diagram. [crosshair] is where the map is looking, which is where a new graphic is put and where a held one
 * is brought to; [crosshairGrid] is the same place in words, so the person can see what "the crosshair" means before tapping.
 */
@Composable
fun GraphicsHost(crosshair: LatLon?, crosshairGrid: String?, modifier: Modifier = Modifier, viewModel: GraphicsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GraphicsContent(
        state = state, crosshairGrid = crosshairGrid, modifier = modifier,
        actions = GraphicsActions(
            place = { kind -> viewModel.place(kind, crosshair) }, select = viewModel::select, deselect = viewModel::deselect, nudge = viewModel::nudge,
            moveToCrosshair = { viewModel.moveToCrosshair(crosshair) }, moveToText = viewModel::moveToText, rotateBy = viewModel::rotateBy,
            setRotation = viewModel::setRotation, changeReach = viewModel::changeReach, tipToCrosshair = { viewModel.tipToCrosshair(crosshair) },
            setDirection = viewModel::setDirection, addUnit = { draft -> viewModel.addUnit(draft, crosshair) }, updateUnit = viewModel::updateUnit,
            setDoghouseLabel = viewModel::setDoghouseLabel, setDoghouseTime = viewModel::setDoghouseTime,
            setDoghouseDistance = viewModel::setDoghouseDistance, setDoghouseAirspeed = viewModel::setDoghouseAirspeed,
            delete = viewModel::delete, undo = viewModel::undo, redo = viewModel::redo, dismissError = viewModel::dismissError,
        ),
    )
}

@Composable
fun GraphicsContent(state: GraphicsUiState, crosshairGrid: String?, actions: GraphicsActions, modifier: Modifier = Modifier) {
    var addingUnit by rememberSaveable { mutableStateOf(false) }
    var newUnit by rememberSaveable(stateSaver = UnitDraftSaver) { mutableStateOf(UnitDraft()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Planning graphics", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (state.canEdit || state.undoDepth > 0 || state.redoDepth > 0) {
                Row {
                    TextAction("Undo", onClick = actions.undo, enabled = state.undoDepth > 0)
                    TextAction("Redo", onClick = actions.redo, enabled = state.redoDepth > 0)
                }
            }
        }
        if (!state.canEdit) {
            Text(
                "Analyze the landing zone first: aircraft, PZ markers, sectors and go-arounds are placed in it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        state.alerts.forEach { Banner(it, BannerKind.Warning) }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }

        PlaceButtons(crosshairGrid, actions.place, onUnit = { addingUnit = !addingUnit })
        if (addingUnit) {
            Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                    Text("New unit", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    UnitBuilder(newUnit, { newUnit = it }, "Add at the crosshair", onConfirm = { actions.addUnit(newUnit); addingUnit = false }, onCancel = { addingUnit = false })
                }
            }
        }
        if (state.rows.isEmpty()) {
            Text("Nothing placed yet. Move the map, then tap what to put at the crosshair.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.rows.forEach { row -> GraphicRow(row, actions.select) }
        state.inspector?.let { Inspector(it, actions) }
    }
}

// -- Placing ---------------------------------------------------------------------------------------------------------

@Composable
private fun PlaceButtons(crosshairGrid: String?, place: (GraphicKind) -> Unit, onUnit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text(
            if (crosshairGrid != null) "Place at the crosshair · $crosshairGrid" else "Place at the crosshair",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GraphicKind.entries.filter { it.placeable }.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                pair.forEach { kind ->
                    SecondaryButton(
                        kind.label, onClick = { place(kind) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Place ${kind.label.lowercase()} at the crosshair" },
                    )
                }
            }
        }
        // A unit is made in the unit builder first, so its button opens that rather than placing at once.
        SecondaryButton("Unit…", onClick = onUnit, modifier = Modifier.semantics { contentDescription = "Make a unit to place at the crosshair" })
    }
}

/** One placed graphic. Tapping holds it; a second tap puts it down. Colour is never the only sign of a warning: the words say it. */
@Composable
private fun GraphicRow(row: GraphicRowUi, select: (GraphicRef) -> Unit) {
    val warning = row.warning
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (row.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            role = Role.Button
            selected = row.selected
        }.clickable { select(row.ref) },
    ) {
        Column(Modifier.heightIn(min = Tokens.Size.touchTarget.dp).padding(horizontal = Tokens.Spacing.md.dp, vertical = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (warning) Text("TOO CLOSE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = EzpzTheme.status.warning)
            }
            val line = listOfNotNull(row.grid, row.detail)
            if (line.isNotEmpty()) {
                Text(line.joinToString("  ·  "), style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -- The one that is held --------------------------------------------------------------------------------------------

/** How far a nudge goes, in feet: a rotor-disc's worth of change, a fine one, and a coarse one. */
internal val NUDGE_STEPS_FT = listOf(10, 50, 200)

@Composable
private fun Inspector(inspector: InspectorUi, actions: GraphicsActions) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val feet = NUDGE_STEPS_FT[step.coerceIn(NUDGE_STEPS_FT.indices)].toDouble()
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Held", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(inspector.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    inspector.grid?.let { Text(it, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    inspector.latLon?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextAction("Done", onClick = actions.deselect)
            }

            // Move: a pad of four arrows and a step, because dragging a symbol with gloves on a bouncing phone is not a plan.
            Text("Move", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NudgeSteps(step) { step = it }
            NudgePad(feet, actions.nudge)
            SecondaryButton("Put at the crosshair", onClick = actions.moveToCrosshair)
            EntryRow("Move to a grid", "16S GD 66993 52949", "Go", KeyboardType.Ascii, onSubmit = { actions.moveToText(it); null })

            inspector.rotation?.let { heading -> HeadingControls(heading, actions) }
            inspector.doghouse?.let { doghouse -> DoghouseControls(doghouse, actions) }
            inspector.unit?.let { unit -> UnitControls(inspector.ref, unit, actions) }
            inspector.reachFt?.let { reach -> ReachControls(reach, actions) }
            inspector.direction?.let { direction -> DirectionControls(direction, actions.setDirection) }

            SecondaryButton("Delete this ${inspector.kind.label.lowercase()}", onClick = actions.delete)
        }
    }
}

/** The choice of how far a nudge goes: [chosen] is an index into [NUDGE_STEPS_FT]. */
@Composable
internal fun NudgeSteps(chosen: Int, choose: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        NUDGE_STEPS_FT.forEachIndexed { i, ft ->
            val label = "$ft ft"
            if (i == chosen) PrimaryButton(label, onClick = { choose(i) }, modifier = Modifier.weight(1f).semantics { selected = true })
            else SecondaryButton(label, onClick = { choose(i) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun NudgePad(feet: Double, nudge: (Double, Double) -> Unit) {
    val steps = feet.toLong()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        PadButton("↑", "Move north $steps feet") { nudge(feet, 0.0) }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl.dp)) {
            PadButton("←", "Move west $steps feet") { nudge(0.0, -feet) }
            PadButton("→", "Move east $steps feet") { nudge(0.0, feet) }
        }
        PadButton("↓", "Move south $steps feet") { nudge(-feet, 0.0) }
    }
}

/** A square button of the smallest comfortable size, with its meaning in words for a screen reader. */
@Composable
internal fun PadButton(label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(Tokens.Radius.md.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier.size(width = Tokens.Size.touchTarget.dp + 16.dp, height = Tokens.Size.touchTarget.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun HeadingControls(heading: Double, actions: GraphicsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Heading ${heading.toLong()}°", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            listOf(-15.0 to "−15°", -1.0 to "−1°", 1.0 to "+1°", 15.0 to "+15°").forEach { (delta, label) ->
                PadButton(label, "Turn ${if (delta < 0) "left" else "right"} ${kotlin.math.abs(delta).toLong()} degrees", Modifier.weight(1f)) { actions.rotateBy(delta) }
            }
        }
        EntryRow("Set heading (°)", "0 to 359", "Set", KeyboardType.Number, onSubmit = { text ->
            val degrees = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            if (degrees == null) "Enter degrees, such as 270." else { actions.setRotation(degrees); null }
        })
    }
}

/** The held unit in the unit builder: nothing changes until Apply, and Apply is there only when something differs. */
@Composable
private fun UnitControls(ref: GraphicRef, held: UnitDraft, actions: GraphicsActions) {
    var draft by remember(ref, held) { mutableStateOf(held) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        UnitBuilder(draft, { draft = it }, "Apply", onConfirm = { actions.updateUnit(draft) }, confirmEnabled = draft != held)
    }
}

/** What a doghouse says besides its heading, each field to be typed over with a unit and an example, and said in words when it is not that. */
@Composable
private fun DoghouseControls(doghouse: DoghouseUi, actions: GraphicsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Doghouse", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        doghouse.feeds?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        EntryRow("Label · ${doghouse.label}", "Up to ${Doghouses.MAX_LABEL} characters", "Set label", KeyboardType.Text, onSubmit = actions.setDoghouseLabel)
        EntryRow("Time · ${doghouse.time}", "Minutes+seconds, such as 03+20", "Set time", KeyboardType.Ascii, onSubmit = actions.setDoghouseTime)
        EntryRow("Distance · ${doghouse.distanceKm} km", "Kilometres, such as 3.1", "Set distance", KeyboardType.Decimal, onSubmit = actions.setDoghouseDistance)
        EntryRow("Airspeed · ${doghouse.airspeedKts} kts", "Knots, such as 60", "Set airspeed", KeyboardType.Number, onSubmit = actions.setDoghouseAirspeed)
    }
}

@Composable
private fun ReachControls(reachFt: Long, actions: GraphicsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Reach ${withCommas(reachFt)} ft", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            listOf(-100.0 to "−100 ft", -25.0 to "−25 ft", 25.0 to "+25 ft", 100.0 to "+100 ft").forEach { (delta, label) ->
                PadButton(label, "${if (delta < 0) "Shorten" else "Lengthen"} the PZ by ${kotlin.math.abs(delta).toLong()} feet", Modifier.weight(1f)) { actions.changeReach(delta) }
            }
        }
        SecondaryButton("Put the tip at the crosshair", onClick = actions.tipToCrosshair)
    }
}

@Composable
private fun DirectionControls(direction: String, setDirection: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Text("Pattern", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            listOf("left" to "Left", "right" to "Right").forEach { (value, label) ->
                val chosen = direction == value
                if (chosen) PrimaryButton(label, onClick = {}, modifier = Modifier.weight(1f).semantics { selected = true })
                else SecondaryButton(label, onClick = { setDirection(value) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A line to type into and a button to send it. [onSubmit] answers with what is wrong with the entry, or null when it was taken. */
@Composable
internal fun EntryRow(label: String, hint: String, action: String, keyboardType: KeyboardType, onSubmit: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val submit = {
        problem = onSubmit(text)
        if (problem == null) text = ""
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Box(Modifier.weight(1f)) {
            EzpzTextField(
                value = text, onValueChange = { text = it; problem = null }, label = label, hint = hint, error = problem,
                keyboardType = keyboardType, imeAction = ImeAction.Go, onImeAction = { submit() },
            )
        }
        TextAction(action, onClick = submit, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp), enabled = text.isNotBlank())
    }
}
