package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.PlaceTool
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ListRow
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.SectionLabel
import app.ezpztac.designsystem.StateChip
import app.ezpztac.designsystem.StatusTone
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft
import app.ezpztac.sync.SyncEngine
import kotlinx.coroutines.delay

/**
 * What the LZ/PZ panel can ask for (docs/native-design: Home, LzPlan, SaveLz). The crosshair is not in here: the host supplies it, so the panel stays a
 * function of its state.
 */
class LzPanelActions(
    val open: (String) -> Unit = {},
    val newHere: () -> Unit = {},
    val close: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    /** Opens the naming sheet for the open LZ/PZ. */
    val save: () -> Unit = {},
    val showPage: (LzPage) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
    val analyze: () -> Unit = {},
    val stopAnalysis: () -> Unit = {},
    val dismissAnalysis: () -> Unit = {},
    val selectAircraft: (String) -> Unit = {},
    val refreshWeather: () -> Unit = {},
    val toggleSlope: () -> Unit = {},
    /** A planning tool's mode: map taps place its graphic until Done or Cancel. */
    val beginTool: (PlaceTool) -> Unit = {},
    val drawBoundary: () -> Unit = {},
    val dismissBoundaryError: () -> Unit = {},
    /** Adds a unit made in the unit builder at the crosshair. */
    val addUnit: (UnitDraft) -> Unit = {},
)

/**
 * The LZ/PZ panel: whichever of its pages is showing (the open LZ/PZs, the open one's plan and tools, every LZ/PZ, the aircraft), and the sheet that names
 * one. [crosshair] is where a new LZ/PZ starts and where the tools add at the crosshair; [crosshairGrid] is the same place in words.
 */
@Composable
fun LzPanelHost(
    crosshair: LatLon?,
    crosshairGrid: String?,
    canMakeAircraft: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DiagramsViewModel = hiltViewModel(),
    graphics: GraphicsViewModel = hiltViewModel(),
    boundary: BoundaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graphicsState by graphics.state.collectAsStateWithLifecycle()
    val boundaryState by boundary.state.collectAsStateWithLifecycle()
    val placing by graphics.placing.collectAsStateWithLifecycle()
    // How old the weather is is measured to this, moved on a minute at a time (an endless timer in the view model would never let a test settle).
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    var naming by rememberSaveable { mutableStateOf(false) }
    val actions = LzPanelActions(
        open = viewModel::open, newHere = { viewModel.newAt(crosshair) }, close = viewModel::close, rename = viewModel::rename, delete = viewModel::delete,
        save = { naming = true }, showPage = viewModel::showPage, resolve = viewModel::resolve, dismissError = viewModel::dismissError,
        analyze = viewModel::analyze, stopAnalysis = viewModel::stopAnalysis, dismissAnalysis = viewModel::dismissAnalysisError,
        selectAircraft = viewModel::selectAircraft, refreshWeather = viewModel::refreshWeather, toggleSlope = viewModel::toggleSlope,
        beginTool = { tool -> graphics.beginPlacing(tool) }, drawBoundary = boundary::start, dismissBoundaryError = boundary::dismissError,
        addUnit = { draft -> graphics.addUnit(draft, crosshair) },
    )
    val graphicsActions = GraphicsActions(
        select = graphics::select, deselect = graphics::deselect, nudge = graphics::nudge, moveToCrosshair = { graphics.moveToCrosshair(crosshair) },
        moveToText = graphics::moveToText, rotateBy = graphics::rotateBy, setRotation = graphics::setRotation, changeReach = graphics::changeReach,
        tipToCrosshair = { graphics.tipToCrosshair(crosshair) }, setDirection = graphics::setDirection, updateUnit = graphics::updateUnit,
        setDoghouseLabel = graphics::setDoghouseLabel, setDoghouseTime = graphics::setDoghouseTime, setDoghouseDistance = graphics::setDoghouseDistance,
        setDoghouseAirspeed = graphics::setDoghouseAirspeed, delete = graphics::delete, undo = graphics::undo, redo = graphics::redo,
        dismissError = graphics::dismissError,
    )
    when (state.page) {
        LzPage.HOME -> LzHomeContent(state, actions, crosshairGrid, modifier)
        LzPage.PLAN -> state.current?.let {
            LzPlanContent(it, graphicsState, boundaryState, placing, actions, graphicsActions, crosshairGrid, modifier, nowMillis = now)
        }
        LzPage.LIBRARY -> LzLibraryContent(state, diagramsActionsOf(actions), modifier)
        LzPage.AIRCRAFT -> AircraftHost(canMake = canMakeAircraft, modifier = modifier)
    }
    if (naming) {
        val current = state.current
        if (current == null) {
            naming = false
        } else {
            SaveLzSheet(current, state.otherNames, onSave = { name -> viewModel.saveAs(name); naming = false }, onDismiss = { naming = false })
        }
    }
}

/** The list of every LZ/PZ reuses the Diagrams list's rows, which take the older set of actions. */
private fun diagramsActionsOf(actions: LzPanelActions) = DiagramsActions(
    open = actions.open, rename = actions.rename, delete = actions.delete, resolve = actions.resolve, dismissError = actions.dismissError,
)

// -- Home: the open LZ/PZs ---------------------------------------------------------------------------------------------------

/**
 * The open LZ/PZs (the design's Home): the one on the map as a card, the others "Also open", a new one at the crosshair, and the most recent of the rest of
 * the Library. A conflict waiting for a choice is said at the top, because it is easy to miss on the list of everything.
 */
@Composable
fun LzHomeContent(state: DiagramsUiState, actions: LzPanelActions, crosshairGrid: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        val conflicts = state.rows.count { it.conflictOf != null }
        if (conflicts > 0) {
            Banner(
                if (conflicts == 1) "1 LZ/PZ needs a choice: another device changed it too." else "$conflicts LZ/PZs need a choice: another device changed them too.",
                BannerKind.Warning, actionLabel = "Review", onAction = { actions.showPage(LzPage.LIBRARY) },
            )
        }
        val current = state.current
        if (current != null) ActiveLzCard(current, actions)
        if (state.open.isNotEmpty()) {
            SectionLabel("Also open", Modifier.padding(top = Tokens.Spacing.sm.dp))
            state.open.forEach { row ->
                ListRow(
                    EzpzIcons.flightLand, row.name, emphasis = true, onClick = { actions.open(row.uuid) }, onClickLabel = "Open",
                    supporting = { RowState(row.grid, row.chips.lastOrNull()) },
                )
            }
        }
        ListRow(
            EzpzIcons.addLocationAlt, "New LZ/PZ at the crosshair", onClick = actions.newHere, onClickLabel = "Make",
            supporting = {
                Text(
                    crosshairGrid ?: "Move the map to where it should be",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (crosshairGrid != null) FontFamily.Monospace else FontFamily.Default),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (state.rows.isNotEmpty()) {
            SectionLabel("Recent in Library", Modifier.padding(top = Tokens.Spacing.sm.dp)) {
                TextButton(onClick = { actions.showPage(LzPage.LIBRARY) }) { Text("Browse all") }
            }
            if (state.recent.isEmpty()) {
                Text("Every LZ/PZ in your Library is open.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.recent.forEach { recent ->
                ListRow(
                    EzpzIcons.flightLand, recent.name,
                    supporting = recent.updated?.let { { Text("Updated $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                ) {
                    TextButton(onClick = { actions.open(recent.uuid) }, modifier = Modifier.semantics { contentDescription = "Open ${recent.name}" }) { Text("Open") }
                }
            }
        }
        Text(
            if (current == null && state.open.isEmpty()) "No LZ/PZ open. Move the map to a landing zone, or type its grid in the search bar, then tap New LZ/PZ."
            else "Move the map, or type a grid in the search bar, then tap New LZ/PZ to open another.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
        )
    }
}

/** A row's second line: its grid, then what its state chip says, in the chip's colour. */
@Composable
private fun RowState(grid: String?, chip: LzChip?) {
    val color = chip?.tone?.let { toneColor(it) }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(grid ?: "No target") }
            if (chip != null) {
                append(" · ")
                withStyle(SpanStyle(color = color ?: Color.Unspecified)) { append(chip.text) }
            }
        },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun toneColor(tone: ChipTone): Color = when (tone) {
    ChipTone.SUCCESS -> EzpzTheme.status.success
    ChipTone.WARNING -> EzpzTheme.status.warning
    ChipTone.ERROR -> MaterialTheme.colorScheme.error
    ChipTone.INFO -> EzpzTheme.status.accent
    ChipTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun ChipTone.status(): StatusTone = when (this) {
    ChipTone.SUCCESS -> StatusTone.Success
    ChipTone.WARNING -> StatusTone.Warning
    ChipTone.ERROR -> StatusTone.Error
    ChipTone.INFO -> StatusTone.Info
    ChipTone.NEUTRAL -> StatusTone.Neutral
}

@Composable
private fun Chips(chips: List<LzChip>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) { chips.forEach { StateChip(it.text, it.tone.status()) } }
}

/**
 * The open LZ/PZ (the design's active card): its name with rename and more, its grid, its chips, *Save* (which names it) or *Saved*, and the way into its
 * plan and tools. A delete is asked about first; a close is not (nothing is lost: the Library keeps it).
 */
@Composable
private fun ActiveLzCard(current: ActiveDiagramUi, actions: LzPanelActions) {
    var renaming by rememberSaveable(current.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(current.uuid) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SmallIconButton(EzpzIcons.edit, "Rename ${current.name}") { renaming = current.name }
                LzMoreMenu(current, actions, onRename = { renaming = current.name }, onDelete = { confirmDelete = true })
            }
            Text(
                current.grid ?: "No target yet", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chips(current.chips, Modifier.padding(top = 10.dp))
            Row(
                Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                SaveButton(current, actions.save)
                PillButton("Plan", onClick = { actions.showPage(LzPage.PLAN) }, icon = EzpzIcons.tune, style = PillStyle.Tonal)
                SavesToLibrary(Modifier.weight(1f))
            }
            renaming?.let { name ->
                RenameField(name, onChange = { renaming = it }, onDone = { actions.rename(current.uuid, it); renaming = null }, onCancel = { renaming = null })
            }
            if (confirmDelete) DeleteConfirm(current.name, onDelete = { confirmDelete = false; actions.delete(current.uuid) }, onKeep = { confirmDelete = false })
            if (current.conflictOf != null) {
                Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    ConflictPanel("LZ/PZ") { actions.resolve(current.uuid, it) }
                }
            }
        }
    }
}

/** *Save* while the LZ/PZ still has its made-up name and is analysed; disabled until it is analysed (its chip says so); *Saved* once it is named. */
@Composable
private fun SaveButton(current: ActiveDiagramUi, save: () -> Unit) {
    if (current.madeUpName) {
        PillButton("Save", onClick = save, icon = EzpzIcons.save, enabled = current.canSave)
    } else {
        PillButton("Saved", onClick = {}, icon = EzpzIcons.done, enabled = false)
    }
}

@Composable
private fun SavesToLibrary(modifier: Modifier = Modifier) {
    Text(
        "Saves to your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End, modifier = modifier,
    )
}

@Composable
private fun SmallIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** The open LZ/PZ's other actions: rename, close (it stays in the Library) and delete (asked about first, where it is asked). */
@Composable
private fun LzMoreMenu(current: ActiveDiagramUi, actions: LzPanelActions, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallIconButton(EzpzIcons.moreVert, "More actions for ${current.name}") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) })
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Close")
                        Text("Takes it out of the open LZ/PZs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = { open = false; actions.close(current.uuid) },
                leadingIcon = { Icon(painterResource(EzpzIcons.close), contentDescription = null) },
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() }, leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null) })
        }
    }
}

@Composable
private fun RenameField(name: String, onChange: (String) -> Unit, onDone: (String) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        EzpzTextField(value = name, onValueChange = onChange, label = "Name", imeAction = ImeAction.Done, onImeAction = { onDone(name) })
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            TextAction("Save name", onClick = { onDone(name) })
            TextAction("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun DeleteConfirm(name: String, onDelete: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Banner("Delete \"$name\"? It is removed from this device and from your account.", BannerKind.Warning)
        // Keeping it is the prominent answer: a delete cannot be taken back.
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            PillButton("Keep it", onClick = onKeep, style = PillStyle.Tonal)
            TextAction("Delete", onClick = onDelete)
        }
    }
}

// -- Plan: the open LZ/PZ's aircraft, analysis and tools ----------------------------------------------------------------------

/**
 * The open LZ/PZ's plan and tools (the design's LzPlan): its state and *Save*, the mission aircraft, its analysis with the summary and weather, the planning
 * tools as modes, and what has been placed, with the inspector for the one held.
 */
@Composable
fun LzPlanContent(
    current: ActiveDiagramUi,
    graphics: GraphicsUiState,
    boundary: BoundaryUiState,
    placing: PlacementUi?,
    actions: LzPanelActions,
    graphicsActions: GraphicsActions,
    crosshairGrid: String?,
    modifier: Modifier = Modifier,
    nowMillis: Long = 0L,
) {
    var confirmDelete by rememberSaveable(current.uuid) { mutableStateOf(false) }
    var renaming by rememberSaveable(current.uuid) { mutableStateOf<String?>(null) }
    var addingUnit by rememberSaveable { mutableStateOf(false) }
    var newUnit by rememberSaveable(stateSaver = UnitDraftSaver) { mutableStateOf(UnitDraft()) }
    var confirmDraw by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Chips(current.chips)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            SaveButton(current, actions.save)
            LzMoreMenu(current, actions, onRename = { renaming = current.name }, onDelete = { confirmDelete = true })
            SavesToLibrary(Modifier.weight(1f))
        }
        renaming?.let { name -> RenameField(name, onChange = { renaming = it }, onDone = { actions.rename(current.uuid, it); renaming = null }, onCancel = { renaming = null }) }
        if (confirmDelete) DeleteConfirm(current.name, onDelete = { confirmDelete = false; actions.delete(current.uuid) }, onKeep = { confirmDelete = false })
        if (current.conflictOf != null) ConflictPanel("LZ/PZ") { actions.resolve(current.uuid, it) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AircraftRow(current.aircraft, actions.selectAircraft, onManage = { actions.showPage(LzPage.AIRCRAFT) })

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AnalysisSection(current, actions)
        current.summary?.let { SummaryTiles(it) }
        current.weather?.let { WeatherSection(it, nowMillis, actions.refreshWeather) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("LZ/PZ tools")
        boundary.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissBoundaryError) }
        ToolGrid(
            canPlace = graphics.canEdit, canDraw = boundary.canStart, drawing = boundary.drawing, placing = placing?.tool, addingUnit = addingUnit,
            onDraw = { if (boundary.clears) confirmDraw = true else actions.drawBoundary() },
            onTool = actions.beginTool,
            onUnit = { addingUnit = !addingUnit },
        )
        Text(
            when {
                !graphics.canEdit -> "Analyze the LZ/PZ to place helicopters, PZs, sectors, units and go-arounds. Draw LZ draws its boundary yourself."
                else -> "Tools place where you tap the map, or at the crosshair. Done and Cancel finish the mode."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (boundary.drawnPoints > 0 && !boundary.drawing) {
            Text(
                "Drawn boundary: ${boundary.drawnPoints} points. Analyze to measure it.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        boundary.cannotStart?.takeIf { !boundary.drawing }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (confirmDraw && boundary.canStart) {
            Banner("Drawing a new boundary clears this analysis. The aircraft and other graphics stay.", BannerKind.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                PillButton("Keep it", onClick = { confirmDraw = false }, style = PillStyle.Tonal)
                TextAction("Draw", onClick = { confirmDraw = false; actions.drawBoundary() })
            }
        }
        if (addingUnit && graphics.canEdit) {
            Surface(
                shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, EzpzTheme.status.accent), modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                    Text("New unit", style = MaterialTheme.typography.titleMedium)
                    UnitBuilder(
                        newUnit, { newUnit = it }, if (crosshairGrid != null) "Add at $crosshairGrid" else "Add at the crosshair",
                        onConfirm = { actions.addUnit(newUnit); addingUnit = false }, onCancel = { addingUnit = false },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GraphicsContent(graphics, crosshairGrid, graphicsActions, placeButtons = false)
    }
}

/**
 * The mission aircraft as the design's one row (what it is and its numbers, a tap for the others) and *Manage* for the profiles. Unverified numbers
 * are said to be in words, not a tooltip: there is no hover on a phone.
 */
@Composable
private fun AircraftRow(aircraft: AircraftUi, onSelect: (String) -> Unit, onManage: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(onClickLabel = "Choose the mission aircraft", role = Role.Button) { choosing = true }
                        .semantics(mergeDescendants = true) { contentDescription = "Mission aircraft: ${aircraft.activeLabel}" }
                        .padding(vertical = Tokens.Spacing.sm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
                ) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(EzpzIcons.flight), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Aircraft", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(aircraft.activeLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${aircraft.spacingM} m spacing · ${aircraft.cruiseKts} kt", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(painterResource(EzpzIcons.arrowDropDown), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    aircraft.options.forEach { option ->
                        val chosen = option.slug == aircraft.activeSlug
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { choosing = false; if (!chosen) onSelect(option.slug) },
                            leadingIcon = { if (chosen) Icon(painterResource(EzpzIcons.check), null) else Spacer(Modifier.size(24.dp)) },
                            modifier = Modifier.semantics { selected = chosen },
                        )
                    }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.semantics { contentDescription = "Manage aircraft profiles" }) { Text("Manage") }
        }
        if (aircraft.unverified) {
            Banner("Unverified performance: these numbers are from published specifications, not from an AMPS vehicle model. Check them before planning fuel.", BannerKind.Warning)
        }
        if (aircraft.waiting.isNotEmpty()) {
            Text(
                "Waiting to sync before it can be chosen: ${aircraft.waiting.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The analysis: where it stands in words, the one thing to do next (analyse, analyse again, stop), and the slope map's switch once there is a slope. */
@Composable
private fun AnalysisSection(current: ActiveDiagramUi, actions: LzPanelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        SectionLabel("LZ/PZ analysis")
        when (val analysis = current.analysis) {
            is AnalysisUi.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    if (analysis.stage == AnalysisStatus.Running.Stage.FINDING_AREA) "Finding the landing area…" else "Measuring the slope…",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                PillButton("Stop", onClick = actions.stopAnalysis, style = PillStyle.Outlined)
            }
            is AnalysisUi.Failed -> {
                Banner(analysis.message, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissAnalysis)
                if (current.canAnalyze) PillButton("Try again", onClick = actions.analyze, icon = EzpzIcons.refresh)
            }
            AnalysisUi.Idle -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                val analyzed = current.status == DiagramStatus.ANALYZED
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    if (analyzed) Icon(painterResource(EzpzIcons.checkCircleFill), contentDescription = null, tint = EzpzTheme.status.success, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Text(
                        when {
                            !current.canAnalyze -> "Set a target to analyze this LZ/PZ."
                            analyzed -> "Analyzed. Planning graphics and save are unlocked."
                            else -> "Not analyzed yet. Analyzing finds the landing area and measures its slope; it needs a connection."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (current.canAnalyze) {
                    if (analyzed) PillButton("Re-analyze", onClick = actions.analyze, icon = EzpzIcons.refresh, style = PillStyle.Tonal)
                    else PillButton("Analyze", onClick = actions.analyze)
                }
            }
        }
        current.slopeShown?.let { shown ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { role = Role.Switch; stateDescription = if (shown) "On" else "Off" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Slope map", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = shown, onCheckedChange = { actions.toggleSlope() })
            }
        }
    }
}

/** The planning tools, four to a row as the design draws them. The one whose mode is on is marked. */
@Composable
private fun ToolGrid(
    canPlace: Boolean,
    canDraw: Boolean,
    drawing: Boolean,
    placing: PlaceTool?,
    addingUnit: Boolean,
    onDraw: () -> Unit,
    onTool: (PlaceTool) -> Unit,
    onUnit: () -> Unit,
) {
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> ToolTile("Draw LZ", "Draw the LZ/PZ boundary", Glyph.Boundary, canDraw && !drawing, drawing, m, onDraw) },
        { m -> ToolTile("Helo", "Place helicopters", Glyph.Helicopter, canPlace, placing == PlaceTool.HELICOPTER, m) { onTool(PlaceTool.HELICOPTER) } },
        { m -> ToolTile("PZ", "Place PZ markers", Glyph.Pz, canPlace, placing == PlaceTool.PZ_MARKER, m) { onTool(PlaceTool.PZ_MARKER) } },
        { m -> ToolTile("Sector", "Place sectors of fire", Glyph.Sector, canPlace, placing == PlaceTool.SECTOR_OF_FIRE, m) { onTool(PlaceTool.SECTOR_OF_FIRE) } },
        { m -> ToolTile("Unit", "Make a unit to place at the crosshair", Glyph.Unit, canPlace, addingUnit, m, onUnit) },
        { m -> ToolTile("L-GA", "Place left go-arounds", Glyph.GoAroundLeft, canPlace, placing == PlaceTool.GO_AROUND_LEFT, m) { onTool(PlaceTool.GO_AROUND_LEFT) } },
        { m -> ToolTile("R-GA", "Place right go-arounds", Glyph.GoAroundRight, canPlace, placing == PlaceTool.GO_AROUND_RIGHT, m) { onTool(PlaceTool.GO_AROUND_RIGHT) } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private enum class Glyph { Boundary, Helicopter, Pz, Sector, Unit, GoAroundLeft, GoAroundRight }

@Composable
private fun ToolTile(label: String, description: String, glyph: Glyph, enabled: Boolean, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp).semantics { contentDescription = description; selected = on },
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (on) BorderStroke(1.dp, EzpzTheme.status.accent) else null,
    ) {
        Column(
            Modifier.padding(vertical = Tokens.Spacing.xs.dp).let { if (enabled) it else it.semantics { } },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            ToolGlyph(glyph, enabled)
            Spacer(Modifier.height(Tokens.Spacing.xs.dp))
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** The tools' little map symbols, as the design draws them, in the palette's own colours so they dim with it at night. */
@Composable
private fun ToolGlyph(glyph: Glyph, enabled: Boolean) {
    val alpha = if (enabled) 1f else 0.4f
    val warning = EzpzTheme.status.warning.copy(alpha = alpha)
    val success = EzpzTheme.status.success.copy(alpha = alpha)
    val accent = EzpzTheme.status.accent.copy(alpha = alpha)
    val error = MaterialTheme.colorScheme.error.copy(alpha = alpha)
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val thin = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.Boundary -> drawPath(polygon(u, 12f to 3f, 20f to 7.5f, 20f to 16.5f, 12f to 21f, 4f to 16.5f, 4f to 7.5f), warning, style = thin)
            Glyph.Helicopter -> {
                drawCircle(success, radius = 8f * u, center = p(12f, 12f), style = thin)
                drawLine(success, p(7.5f, 7.5f), p(16.5f, 16.5f), 1.8f * u, StrokeCap.Round)
                drawLine(success, p(16.5f, 7.5f), p(7.5f, 16.5f), 1.8f * u, StrokeCap.Round)
            }
            Glyph.Pz -> {
                drawLine(accent, p(3f, 12f), p(13f, 12f), 2f * u, StrokeCap.Round)
                drawLine(accent, p(3f, 12f), p(7f, 8.5f), 2f * u, StrokeCap.Round)
                drawLine(accent, p(3f, 12f), p(7f, 15.5f), 2f * u, StrokeCap.Round)
                drawCircle(error, radius = 3f * u, center = p(18f, 12f), style = thin)
            }
            Glyph.Sector -> {
                val triangle = polygon(u, 12f to 4f, 20f to 19f, 4f to 19f)
                drawPath(triangle, error.copy(alpha = 0.28f * alpha), style = Fill)
                drawPath(triangle, error, style = Stroke(1.5f * u, join = StrokeJoin.Round))
            }
            Glyph.Unit -> {
                drawRoundRect(accent, topLeft = p(3.5f, 6.5f), size = androidx.compose.ui.geometry.Size(17f * u, 11f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(u, u), style = thin)
                drawLine(accent, p(3.5f, 6.5f), p(20.5f, 17.5f), 1.5f * u)
                drawLine(accent, p(20.5f, 6.5f), p(3.5f, 17.5f), 1.5f * u)
            }
            Glyph.GoAroundLeft -> goAround(u, warning, left = true)
            Glyph.GoAroundRight -> goAround(u, warning, left = false)
        }
    }
}

private fun polygon(u: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
    close()
}

/** A curved arrow turning back on itself (the design's go-around): to the left, or mirrored to the right. */
private fun DrawScope.goAround(u: Float, color: Color, left: Boolean) {
    fun x(v: Float) = (if (left) v else 24f - v) * u
    val arc = Path().apply {
        moveTo(x(5f), 17f * u)
        cubicTo(x(5.5f), 9f * u, x(12f), 5.5f * u, x(19f), 8f * u)
    }
    drawPath(arc, color, style = Stroke(3f * u, cap = StrokeCap.Round))
    drawLine(color, Offset(x(5f), 17f * u), Offset(x(3f), 13f * u), 2f * u, StrokeCap.Round)
    drawLine(color, Offset(x(5f), 17f * u), Offset(x(9f), 16f * u), 2f * u, StrokeCap.Round)
}

// -- Every LZ/PZ ----------------------------------------------------------------------------------------------------------------

/** Every LZ/PZ in the Library, to open, rename, delete, or settle a conflict: what the Library page will hold for all kinds (S7). */
@Composable
fun LzLibraryContent(state: DiagramsUiState, actions: DiagramsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        if (state.rows.isEmpty()) {
            Text("No LZ/PZs yet. Make one from the LZ/PZ panel.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.rows.forEach { row -> DiagramListItem(row, actions) }
        Spacer(Modifier.width(1.dp))
    }
}
