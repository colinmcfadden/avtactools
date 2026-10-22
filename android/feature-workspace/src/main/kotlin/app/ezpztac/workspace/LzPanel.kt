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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.PlaceTool
import app.ezpztac.data.SlopeOpacity
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
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft
import app.ezpztac.sync.SyncEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    /** Opens the Library on its LZ/PZ tab (every LZ/PZ saved, and the conflicts to settle). */
    val browse: () -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
    val analyze: () -> Unit = {},
    val stopAnalysis: () -> Unit = {},
    val dismissAnalysis: () -> Unit = {},
    val selectAircraft: (String) -> Unit = {},
    val refreshWeather: () -> Unit = {},
    val toggleSlope: () -> Unit = {},
    /** How opaque the slope heat map is drawn, a percentage of its own (the slider under its switch). */
    val setSlopeOpacity: (Int) -> Unit = {},
    /** Draws or hides the open LZ/PZ's analysis boundary (*LZ box*). */
    val showOutline: (Boolean) -> Unit = {},
    /** A planning tool's mode: map taps place its graphic until Done or Cancel. */
    val beginTool: (PlaceTool) -> Unit = {},
    val drawBoundary: () -> Unit = {},
    val dismissBoundaryError: () -> Unit = {},
    /** Adds a unit made in the unit builder at the crosshair. */
    val addUnit: (UnitDraft) -> Unit = {},
    /** Asks which Mission Pack a copy of the open LZ/PZ ([uuid], [name]) goes into; none: not offered (no packs here, or one is open). */
    val addToPack: ((uuid: String, name: String) -> Unit)? = null,
    /** Asks what to call a copy of the open pack's LZ/PZ ([uuid], the editor's id) saved to this person's Library; none: not offered. */
    val saveCopy: ((uuid: String) -> Unit)? = null,
    /** The eye on an open LZ/PZ's row: hidden from the map, or drawn again ([uuid]); none: no eye. */
    val toggleOnMap: ((uuid: String) -> Unit)? = null,
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
    /** Which part of the panel this draws: all of it (the phone), or the tablet's list and its supporting pane apart ([LzPane]). */
    pane: LzPane = LzPane.WHOLE,
    /** Opens the Library on its LZ/PZ tab. */
    onBrowseLibrary: () -> Unit = {},
    /** Asks which Mission Pack a copy of the open LZ/PZ goes into: given while packs run here and none is open. */
    onAddToPack: ((uuid: String, name: String) -> Unit)? = null,
    /** Asks what to call a copy of a pack's LZ/PZ open here, saved to the Library: given while a pack is open. */
    onSaveCopy: ((uuid: String) -> Unit)? = null,
    /** The open LZ/PZs hidden from the map ([LzHomeContent]'s eyes), and the eye's toggle; none: no eyes. */
    hiddenOnMap: Set<String> = emptySet(),
    onToggleOnMap: ((uuid: String) -> Unit)? = null,
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
        save = { naming = true }, showPage = viewModel::showPage, browse = onBrowseLibrary, resolve = viewModel::resolve, dismissError = viewModel::dismissError,
        analyze = viewModel::analyze, stopAnalysis = viewModel::stopAnalysis, dismissAnalysis = viewModel::dismissAnalysisError,
        selectAircraft = viewModel::selectAircraft, refreshWeather = viewModel::refreshWeather, toggleSlope = viewModel::toggleSlope,
        setSlopeOpacity = viewModel::setSlopeOpacity, showOutline = viewModel::showOutline,
        beginTool = { tool -> graphics.beginPlacing(tool) }, drawBoundary = boundary::start, dismissBoundaryError = boundary::dismissError,
        addUnit = { draft -> graphics.addUnit(draft, crosshair) }, addToPack = onAddToPack, saveCopy = onSaveCopy, toggleOnMap = onToggleOnMap,
    )
    val graphicsActions = GraphicsActions(
        select = graphics::select, deselect = graphics::deselect, nudge = graphics::nudge, moveToCrosshair = { graphics.moveToCrosshair(crosshair) },
        moveToText = graphics::moveToText, rotateBy = graphics::rotateBy, setRotation = graphics::setRotation, changeReach = graphics::changeReach,
        tipToCrosshair = { graphics.tipToCrosshair(crosshair) }, setDirection = graphics::setDirection, updateUnit = graphics::updateUnit,
        setDoghouseLabel = graphics::setDoghouseLabel, setDoghouseTime = graphics::setDoghouseTime, setDoghouseDistance = graphics::setDoghouseDistance,
        setDoghouseAirspeed = graphics::setDoghouseAirspeed, delete = graphics::delete, undo = graphics::undo, redo = graphics::redo,
        dismissError = graphics::dismissError,
    )
    when (pane.shows(state.page)) {
        LzPage.HOME -> LzHomeContent(
            state, actions, crosshairGrid, modifier, hidden = hiddenOnMap,
            // The open LZ/PZ's tools under its card, unless its plan, which has them, stands beside the list (a tablet's pane); none in a read-only pack.
            tools = state.current?.takeIf { it.locked == null && (pane == LzPane.WHOLE || state.page != LzPage.PLAN) }?.let { current ->
                { LzToolsSection(current, graphicsState, boundaryState, placing, actions, crosshairGrid) }
            },
        )
        LzPage.PLAN -> state.current?.let {
            LzPlanContent(it, graphicsState, boundaryState, placing, actions, graphicsActions, crosshairGrid, modifier, nowMillis = now)
        }
        LzPage.AIRCRAFT -> AircraftHost(canMake = canMakeAircraft, modifier = modifier)
        null -> Unit
    }
    if (naming) {
        val current = state.current
        if (current == null) {
            naming = false
        } else {
            SaveLzSheet(
                current, state.rows.filter { it.uuid != current.uuid }.map { it.uuid to it.name },
                onSave = { name, replacing -> viewModel.saveAs(name, replacing); naming = false }, onDismiss = { naming = false },
            )
        }
    }
}

/**
 * The part of the LZ/PZ panel a host draws. On a phone it is all of it, a page at a time ([WHOLE]). A tablet shows the open LZ/PZ's plan beside the map (the
 * design's supporting pane, [PLAN]) while its list ([LIST]) stays: the plan page is then the pane, and the list shows the open LZ/PZs under it.
 */
enum class LzPane {
    WHOLE, LIST, PLAN;

    /** The page drawn when [page] is the panel's: null for none. */
    fun shows(page: LzPage): LzPage? = when (this) {
        WHOLE -> page
        LIST -> if (page == LzPage.PLAN) LzPage.HOME else page
        PLAN -> LzPage.PLAN.takeIf { page == LzPage.PLAN }
    }
}

// -- Home: the open LZ/PZs ---------------------------------------------------------------------------------------------------

/**
 * The open LZ/PZs (the design's Home): the one on the map as a card with its [tools] under it, the others "Also open", a new one at the crosshair, and the most
 * recent of the rest of the Library. A conflict waiting for a choice is said at the top, because it is easy to miss on the list of everything. Every open one but
 * the one being worked on is drawn faded on the map, unless it is [hidden]: the eye at the end of its row says which, and changes it.
 */
@Composable
fun LzHomeContent(
    state: DiagramsUiState,
    actions: LzPanelActions,
    crosshairGrid: String?,
    modifier: Modifier = Modifier,
    /** The open LZ/PZs hidden from the map, by id: this device's own. */
    hidden: Set<String> = emptySet(),
    /** The open LZ/PZ's tools, drawn under its card (the host's [LzToolsSection]); none: not drawn. */
    tools: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        // A conflict is the Library's: in a pack it would read as if one of the pack's LZ/PZs had one.
        val conflicts = if (state.pack == null) state.rows.count { it.conflictOf != null } else 0
        if (conflicts > 0) {
            Banner(
                if (conflicts == 1) "1 LZ/PZ needs a choice: another device changed it too." else "$conflicts LZ/PZs need a choice: another device changed them too.",
                BannerKind.Warning, actionLabel = "Review", onAction = actions.browse,
            )
        }
        val current = state.current
        val pack = state.pack
        // In a pack: the pack's LZ/PZs, in place of the person's own (set aside until the pack is left), under a label that counts them all, the open one
        // first when it is the pack's.
        val packLabel: @Composable () -> Unit = { pack?.let { SectionLabel("In ${it.name} · ${it.count}") } }
        if (pack != null && pack.opening == null && current?.inPack != null) packLabel()
        if (current != null) ActiveLzCard(current, actions)
        if (current != null) tools?.invoke()
        if (pack != null) {
            when {
                pack.opening != null -> Text(pack.opening, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pack.rows.isNotEmpty() -> {
                    if (current?.inPack == null) packLabel()
                    pack.rows.forEach { row ->
                        ListRow(
                            EzpzIcons.flightLand, row.name, emphasis = true, onClick = { actions.open(row.uuid) }, onClickLabel = "Open",
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                                    // How far along it is beside its grid (the web's CompactCard); what holds of it in the pack, under them, when it is not just synced.
                                    RowState(row.grid, row.chips.firstOrNull())
                                    if (row.chips.size > 1) Chips(row.chips.drop(1))
                                    row.presence?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            },
                        ) { RowEye(row, row.uuid in hidden, actions) }
                    }
                }
                current?.inPack == null -> Text(
                    if (pack.readOnly) "Nothing in ${pack.name}." else "Nothing in ${pack.name} yet. Tap New LZ/PZ at the crosshair, or long-press the map, and the new LZ/PZ goes into the pack.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.open.isNotEmpty()) {
            SectionLabel("Also open", Modifier.padding(top = Tokens.Spacing.sm.dp))
            state.open.forEach { row ->
                ListRow(
                    EzpzIcons.flightLand, row.name, emphasis = true, onClick = { actions.open(row.uuid) }, onClickLabel = "Open",
                    supporting = { RowState(row.grid, row.chips.lastOrNull()) },
                ) { RowEye(row, row.uuid in hidden, actions) }
            }
        }
        // Nothing new goes into a read-only pack, so it is not offered (the web's foot says why).
        if (pack?.readOnly != true) ListRow(
            EzpzIcons.addLocationAlt, "New LZ/PZ at the crosshair", onClick = actions.newHere, onClickLabel = "Make",
            supporting = {
                Text(
                    crosshairGrid ?: "Move the map to where it should be",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (crosshairGrid != null) FontFamily.Monospace else FontFamily.Default),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (state.rows.isNotEmpty() && state.pack == null) {
            SectionLabel("Recent in Library", Modifier.padding(top = Tokens.Spacing.sm.dp)) {
                TextButton(onClick = actions.browse) { Text("Browse all") }
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
            when {
                state.pack?.hint != null -> state.pack.hint
                state.pack != null -> "New LZ/PZs you start while ${state.pack.name} is open are added to it."
                current == null && state.open.isEmpty() -> "No LZ/PZ open. Move the map to a landing zone, or type its grid in the search bar, then tap New LZ/PZ."
                else -> "Move the map, or type a grid in the search bar, then tap New LZ/PZ to open another."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Spacing.sm.dp),
        )
    }
}

/**
 * The eye at the end of an open LZ/PZ's row (the owner's request of 2026-10-21): the LZ/PZ is drawn faded on the map while another is worked on, or [hidden] from
 * it. The one being worked on has none: it is always drawn. Nothing when the host gives no toggle.
 */
@Composable
private fun RowEye(row: DiagramRow, hidden: Boolean, actions: LzPanelActions) {
    val toggle = actions.toggleOnMap ?: return
    IconButton(onClick = { toggle(row.uuid) }, modifier = Modifier.size(48.dp)) {
        Icon(
            painterResource(if (hidden) EzpzIcons.visibilityOff else EzpzIcons.visibility),
            contentDescription = (if (hidden) "Show " else "Hide ") + row.name + " on the map",
            tint = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else EzpzTheme.status.accent,
        )
    }
}

/**
 * A row's second line: its grid, then what its state chip says, in the chip's colour. Where both do not fit (a narrow phone, large text) the state goes
 * under the grid whole, with no separator to start its line; and a grid too wide for the row breaks only between its halves (`16S GC` / `31204 58811`),
 * never inside one and never cut off.
 */
@Composable
private fun RowState(grid: String?, chip: LzChip?) {
    val gridText: @Composable () -> Unit = {
        // What a screen reader (and a test) reads is the grid as it is written; only how it may break is changed.
        Box(Modifier.clearAndSetSemantics { text = AnnotatedString(grid ?: "No target") }) {
            Text(
                grid?.let(::gridBreakingBetweenHalves) ?: "No target",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (grid != null) FontFamily.Monospace else FontFamily.Default),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (chip == null) return gridText()
    DotJoined(gridText) { Text(chip.text, style = MaterialTheme.typography.bodyMedium, color = toneColor(chip.tone)) }
}

/** [grid] (`16S GC 31204 58811`) with its zone and square, and its easting and northing, each held together, so a line can break only between the two halves. */
internal fun gridBreakingBetweenHalves(grid: String): String {
    val parts = grid.trim().split(Regex("\\s+"))
    return if (parts.size == 4) "${parts[0]}\u00A0${parts[1]} ${parts[2]}\u00A0${parts[3]}" else grid
}

/**
 * [first], then " · " and [second] on its line when both fit; else [second] under [first], whole, with no separator to start its line (a narrow phone, large
 * text). The separator is not read out.
 */
@Composable
internal fun DotJoined(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val dot: @Composable () -> Unit = {
        Text(" · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
    }
    Layout(contents = listOf(first, dot, second)) { (a, d, b), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val one = a.first().measure(loose)
        val sep = d.first().measure(loose)
        val two = b.first().measure(loose)
        if (one.width + sep.width + two.width <= constraints.maxWidth) {
            layout(one.width + sep.width + two.width, maxOf(one.height, sep.height, two.height)) {
                one.place(0, 0)
                sep.place(one.width, 0)
                two.place(one.width + sep.width, 0)
            }
        } else {
            layout(maxOf(one.width, two.width), one.height + two.height) {
                one.place(0, 0)
                two.place(0, one.height)
            }
        }
    }
}

@Composable
internal fun toneColor(tone: ChipTone): Color = when (tone) {
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
internal fun Chips(chips: List<LzChip>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) { chips.forEach { StateChip(it.text, it.tone.status(), icon = it.icon) } }
}

/**
 * The open LZ/PZ (the design's active card): its name with rename and more, its grid, its chips, *Save* (which names it) or *Saved*, and the way into its
 * plan and tools. A delete is asked about first; a close is not (nothing is lost: the Library keeps it).
 */
@Composable
private fun ActiveLzCard(current: ActiveDiagramUi, actions: LzPanelActions) {
    var renaming by rememberSaveable(current.uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(current.uuid) { mutableStateOf(false) }
    // A pack finished while its name was being typed: the field goes, and does not come back by itself if the pack is reopened.
    LaunchedEffect(current.locked) { if (current.locked != null) renaming = null }
    Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (current.locked == null) SmallIconButton(EzpzIcons.edit, "Rename ${current.name}") { renaming = current.name }
                LzMoreMenu(current, actions, onRename = { renaming = current.name }, onDelete = { confirmDelete = true })
            }
            Text(
                current.grid ?: "No target yet", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chips(current.chips, Modifier.padding(top = 10.dp))
            current.inPack?.alsoHere?.let { AlsoHere(it, Modifier.padding(top = Tokens.Spacing.sm.dp)) }
            Row(
                Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                // A pack's LZ/PZ is saved to the pack as it changes: no Save of its own (the design's PackLive).
                if (current.inPack == null) SaveButton(current, actions.save)
                PillButton("Plan", onClick = { actions.showPage(LzPage.PLAN) }, icon = EzpzIcons.tune, style = PillStyle.Tonal)
                if (current.inPack == null) SavesToLibrary(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
            current.inPack?.let { SavedToPack(it.savedLine, Modifier.padding(top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.sm.dp), locked = it.reason != null) }
            renaming?.takeIf { current.locked == null }?.let { name ->
                RenameField(
                    name, current.name, onChange = { renaming = it }, onDone = { actions.rename(current.uuid, it); renaming = null }, onCancel = { renaming = null },
                    inPack = current.inPack != null,
                )
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

/**
 * *Save* while the LZ/PZ still has its made-up name and is analysed; disabled until it is analysed (its chip says so), named or not, since an LZ/PZ is
 * saved once it is analysed, as on the web; *Saved* once it is both.
 */
@Composable
private fun SaveButton(current: ActiveDiagramUi, save: () -> Unit) {
    if (current.madeUpName || current.status != DiagramStatus.ANALYZED) {
        PillButton("Save", onClick = save, icon = EzpzIcons.save, enabled = current.canSave)
    } else {
        PillButton("Saved", onClick = {}, icon = EzpzIcons.done, enabled = false)
    }
}

/**
 * "Saved to OP DK as you work. 4 members can see it." (the design's PackLive): a pack's item has no *Save*, and this says why; in a read-only pack ([locked])
 * why nothing is saved, with a lock: "Finished by Colin M. on Oct 5 at 14:20".
 */
@Composable
internal fun SavedToPack(line: String, modifier: Modifier = Modifier, locked: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(if (locked) EzpzIcons.lock else EzpzIcons.groups), contentDescription = null,
            tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp),
        )
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Sam B. is also here": who else has the open item of a pack open now. */
@Composable
internal fun AlsoHere(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

@Composable
internal fun SavesToLibrary(modifier: Modifier = Modifier) {
    Text(
        "Saves to your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End, modifier = modifier,
    )
}

@Composable
internal fun SmallIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/**
 * The open LZ/PZ's other actions: rename, close (it stays in the Library), *Add to Mission Pack…* (one of the person's own: a copy, analysed or not, as the
 * Library's) and delete (asked about first, where it is asked).
 */
@Composable
private fun LzMoreMenu(current: ActiveDiagramUi, actions: LzPanelActions, onRename: () -> Unit, onDelete: () -> Unit, outlined: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (outlined) {
            OutlinedIconButton(onClick = { open = true }, modifier = Modifier.size(48.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Icon(
                    painterResource(EzpzIcons.moreVert), contentDescription = "More actions for ${current.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        } else {
            SmallIconButton(EzpzIcons.moreVert, "More actions for ${current.name}") { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            if (current.locked == null) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() }, leadingIcon = { Icon(painterResource(EzpzIcons.edit), contentDescription = null) })
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text(if (current.inPack != null) "Close here" else "Close")
                        Text(
                            if (current.inPack != null) "It stays in the pack" else "Takes it out of the open LZ/PZs",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { open = false; actions.close(current.uuid) },
                leadingIcon = { Icon(painterResource(EzpzIcons.close), contentDescription = null) },
            )
            val addToPack = actions.addToPack
            if (current.inPack == null && addToPack != null) {
                DropdownMenuItem(
                    text = { Text("Add to Mission Pack…") }, onClick = { open = false; addToPack(current.uuid, current.name) },
                    leadingIcon = { Icon(painterResource(EzpzIcons.groups), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                )
            }
            // A pack's LZ/PZ, finished or not, can be kept as a copy of this person's own.
            val saveCopy = actions.saveCopy
            if (current.inPack != null && saveCopy != null) {
                DropdownMenuItem(
                    text = { Text("Save a copy to Library…") }, onClick = { open = false; saveCopy(current.uuid) },
                    leadingIcon = { Icon(painterResource(EzpzIcons.contentCopy), contentDescription = null) },
                )
            }
            // A pack's LZ/PZ is removed for everyone from the Pack panel, never deleted from here.
            if (current.inPack == null) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() }, leadingIcon = { Icon(painterResource(EzpzIcons.delete), contentDescription = null) })
            }
        }
    }
}

@Composable
private fun RenameField(name: String, was: String, onChange: (String) -> Unit, onDone: (String) -> Unit, onCancel: () -> Unit, inPack: Boolean = false) {
    // What is wrong with the name is said at the field, once Save is pressed: the panel's banner can be scrolled out of sight while typing.
    var refused by rememberSaveable { mutableStateOf<String?>(null) }
    val done: () -> Unit = {
        val title = name.trim()
        refused = when {
            title.isEmpty() -> "Give it a name."
            // A pack's own names are "LZ/PZ n", and nothing of a pack's is named to be saved: any name will do there.
            !inPack && title != was.trim() && LzNames.isMadeUp(title) -> LzNames.MADE_UP_REFUSED
            else -> null
        }
        when {
            refused != null -> {}
            title == was.trim() -> onCancel()                                               // nothing to change
            else -> onDone(title)
        }
    }
    Column(Modifier.padding(top = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        EzpzTextField(
            value = name, onValueChange = { refused = null; onChange(it) }, label = "Name", error = refused, imeAction = ImeAction.Done, onImeAction = done,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            TextAction("Save name", onClick = done)
            TextAction("Cancel", onClick = onCancel)
        }
    }
}

@Composable
internal fun DeleteConfirm(name: String, onDelete: () -> Unit, onKeep: () -> Unit) {
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
    // The pack finished (or this person made a viewer) while something was being started here: it goes, and does not come back by itself on a reopen.
    LaunchedEffect(current.locked) { if (current.locked != null) renaming = null }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Chips(current.chips)
        current.inPack?.alsoHere?.let { AlsoHere(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            // A pack's LZ/PZ is saved to the pack as it changes: no Save of its own, and where it is saved said in its place (the design's PackLive pane).
            if (current.inPack == null) SaveButton(current, actions.save)
            LzMoreMenu(current, actions, onRename = { renaming = current.name }, onDelete = { confirmDelete = true }, outlined = true)
            current.inPack?.let { SavedToPack(it.savedLine, Modifier.weight(1f), locked = it.reason != null) } ?: SavesToLibrary(Modifier.weight(1f))
        }
        renaming?.takeIf { current.locked == null }?.let { name ->
            RenameField(
                name, current.name, onChange = { renaming = it }, onDone = { actions.rename(current.uuid, it); renaming = null }, onCancel = { renaming = null },
                inPack = current.inPack != null,
            )
        }
        if (confirmDelete) DeleteConfirm(current.name, onDelete = { confirmDelete = false; actions.delete(current.uuid) }, onKeep = { confirmDelete = false })
        if (current.conflictOf != null) ConflictPanel("LZ/PZ") { actions.resolve(current.uuid, it) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AircraftRow(current.aircraft, actions.selectAircraft, onManage = { actions.showPage(LzPage.AIRCRAFT) })

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AnalysisSection(current, actions)
        current.summary?.let { SummaryTiles(it) }
        current.weather?.let { WeatherSection(it, nowMillis, actions.refreshWeather) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LzToolsSection(current, graphics, boundary, placing, actions, crosshairGrid)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        GraphicsContent(graphics, crosshairGrid, graphicsActions, placeButtons = false)
    }
}

/**
 * The LZ/PZ tools (the design's tool grid) with what they ask for: the unit builder, the question before a new boundary clears an analysis, and why a tool is off.
 * On the open LZ/PZ's plan, and under its card on the panel's first page (the owner's request of 2026-10-15), so a tool is a tap away whenever the LZ/PZ panel
 * shows one: each place keeps its own builder and question.
 */
@Composable
internal fun LzToolsSection(
    current: ActiveDiagramUi,
    graphics: GraphicsUiState,
    boundary: BoundaryUiState,
    placing: PlacementUi?,
    actions: LzPanelActions,
    crosshairGrid: String?,
    modifier: Modifier = Modifier,
) {
    var addingUnit by rememberSaveable(current.uuid) { mutableStateOf(false) }
    var newUnit by rememberSaveable(stateSaver = UnitDraftSaver) { mutableStateOf(UnitDraft()) }
    var confirmDraw by rememberSaveable(current.uuid) { mutableStateOf(false) }
    // The pack finished (or this person made a viewer) while something was being started here: it goes, and does not come back by itself on a reopen.
    LaunchedEffect(current.locked) {
        if (current.locked != null) {
            addingUnit = false
            confirmDraw = false
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        SectionLabel("LZ/PZ tools")
        // In a read-only pack every tool is off, and this says why (the design's "Off in a finished pack").
        current.inPack?.short?.let { off ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(off, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        boundary.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissBoundaryError) }
        ToolGrid(
            canPlace = graphics.canEdit, canDraw = boundary.canStart, drawing = boundary.drawing, placing = placing?.tool, addingUnit = addingUnit,
            onDraw = { if (boundary.clears) confirmDraw = true else actions.drawBoundary() },
            onTool = actions.beginTool,
            onUnit = { addingUnit = !addingUnit },
        )
        if (current.locked == null) {
            Text(
                when {
                    !graphics.canEdit -> "Analyze the LZ/PZ to place helicopters, PZs, sectors, units and go-arounds. Draw LZ draws its boundary yourself."
                    else -> "Tools place where you tap the map, or at the crosshair. Done and Cancel finish the mode."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundary.drawnPoints > 0 && !boundary.drawing) {
            Text(
                if (current.locked != null) "Drawn boundary: ${boundary.drawnPoints} points." else "Drawn boundary: ${boundary.drawnPoints} points. Analyze to measure it.",
                style = MaterialTheme.typography.bodyMedium,
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
                        Text(aircraft.activeLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${aircraft.spacingM} m spacing · ${aircraft.cruiseKts} kt", style = MaterialTheme.typography.bodyMedium,
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

/**
 * The analysis: where it stands in words, the one thing to do next (analyse, analyse again, stop), and how it is drawn (the design's LzPlan): the slope map's
 * switch once there is a slope, with its opacity while it is drawn, and the *LZ box* switch once there is a boundary.
 */
@Composable
private fun AnalysisSection(current: ActiveDiagramUi, actions: LzPanelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        if (current.analysis != AnalysisUi.Idle) SectionLabel("LZ/PZ analysis")
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
                if (current.canAnalyze && current.locked == null) PillButton("Try again", onClick = actions.analyze, icon = EzpzIcons.refresh)
            }
            AnalysisUi.Idle -> Row(
                Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                val analyzed = current.status == DiagramStatus.ANALYZED
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "LZ/PZ analysis", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (analyzed) Icon(painterResource(EzpzIcons.checkCircleFill), contentDescription = null, tint = EzpzTheme.status.success, modifier = Modifier.size(16.dp))
                        Text(
                            when {
                                !current.canAnalyze -> "Set a target to analyze this LZ/PZ."
                                analyzed && current.locked != null -> "Analyzed. Read-only here: you can look at it, not change it."
                                current.locked != null -> "Not analyzed. Nothing in this pack can be changed, so it cannot be analyzed here."
                                analyzed && current.inPack != null -> "Analyzed. Planning graphics are unlocked."      // a pack's has no Save to unlock
                                analyzed -> "Analyzed. Planning graphics and save are unlocked."
                                else -> "Not analyzed yet. Analyzing finds the landing area and measures its slope; it needs a connection."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (current.canAnalyze) {
                    // Off, not gone, in a read-only pack (the design's): the words beside it say why.
                    val can = current.locked == null
                    if (analyzed) PillButton("Re-analyze", onClick = actions.analyze, icon = EzpzIcons.refresh, style = PillStyle.Tonal, enabled = can)
                    else PillButton("Analyze", onClick = actions.analyze, enabled = can)
                }
            }
        }
        current.slopeShown?.let { shown ->
            ViewSwitch("Slope map", shown) { actions.toggleSlope() }
            if (shown) SlopeOpacitySlider(current.slopeOpacity, actions.setSlopeOpacity)
        }
        // Looking, not changing: offered in a read-only pack too, where the view is this person's own.
        current.outlineShown?.let { shown -> ViewSwitch("LZ box", shown, actions.showOutline) }
    }
}

/** One of the analysis's view switches. The whole row is the switch, so it is said with its name; the switch itself only shows the state and is not a second target. */
@Composable
private fun ViewSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * How opaque the slope heat map is drawn (the design's overlay opacity, docs/native-design Imports): its name and percentage over a slider that has the
 * row's whole width, so a value that grows a digit never moves the track under the finger. A percentage of the raster's own, in steps of
 * [SlopeOpacity.STEP]; this device's, for every LZ/PZ. Said once, as the slider: the words over it are its name and value.
 */
@Composable
private fun SlopeOpacitySlider(percent: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = SlopeOpacity.MIN.toFloat()..SlopeOpacity.MAX.toFloat(),
            steps = SlopeOpacity.STOPS_BETWEEN,
            // The stops are there (the thumb and a screen reader move by them); a dot for each of nineteen is only noise under the number that says it.
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Slope map opacity"
                // Its own words: read as a share of the slider's range, 55 would be said as 50 percent.
                stateDescription = "$percent percent"
            },
        )
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
        { m -> ToolTile("Draw LZ", "Draw the LZ/PZ boundary", ToolSymbol.Boundary, canDraw && !drawing, drawing, m, onDraw) },
        { m -> ToolTile("Helo", "Place helicopters", ToolSymbol.Helicopter, canPlace, placing == PlaceTool.HELICOPTER, m) { onTool(PlaceTool.HELICOPTER) } },
        { m -> ToolTile("PZ", "Place PZ markers", ToolSymbol.Pz, canPlace, placing == PlaceTool.PZ_MARKER, m) { onTool(PlaceTool.PZ_MARKER) } },
        { m -> ToolTile("Sector", "Place sectors of fire", ToolSymbol.Sector, canPlace, placing == PlaceTool.SECTOR_OF_FIRE, m) { onTool(PlaceTool.SECTOR_OF_FIRE) } },
        { m -> ToolTile("Unit", "Make a unit to place at the crosshair", ToolSymbol.Unit, canPlace, addingUnit, m, onUnit) },
        { m -> ToolTile("L-GA", "Place left go-arounds", ToolSymbol.GoAroundLeft, canPlace, placing == PlaceTool.GO_AROUND_LEFT, m) { onTool(PlaceTool.GO_AROUND_LEFT) } },
        { m -> ToolTile("R-GA", "Place right go-arounds", ToolSymbol.GoAroundRight, canPlace, placing == PlaceTool.GO_AROUND_RIGHT, m) { onTool(PlaceTool.GO_AROUND_RIGHT) } },
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

/** The tools' little map symbols: the LZ/PZ panel's tool grid, and the map menu's *Add object* list. */
enum class ToolSymbol { Boundary, Helicopter, Pz, Sector, Unit, GoAroundLeft, GoAroundRight }

@Composable
private fun ToolTile(label: String, description: String, glyph: ToolSymbol, enabled: Boolean, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
fun ToolGlyph(glyph: ToolSymbol, enabled: Boolean) {
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
            ToolSymbol.Boundary -> drawPath(polygon(u, 12f to 3f, 20f to 7.5f, 20f to 16.5f, 12f to 21f, 4f to 16.5f, 4f to 7.5f), warning, style = thin)
            ToolSymbol.Helicopter -> {
                drawCircle(success, radius = 8f * u, center = p(12f, 12f), style = thin)
                drawLine(success, p(7.5f, 7.5f), p(16.5f, 16.5f), 1.8f * u, StrokeCap.Round)
                drawLine(success, p(16.5f, 7.5f), p(7.5f, 16.5f), 1.8f * u, StrokeCap.Round)
            }
            ToolSymbol.Pz -> {
                drawLine(accent, p(3f, 12f), p(13f, 12f), 2f * u, StrokeCap.Round)
                drawLine(accent, p(3f, 12f), p(7f, 8.5f), 2f * u, StrokeCap.Round)
                drawLine(accent, p(3f, 12f), p(7f, 15.5f), 2f * u, StrokeCap.Round)
                drawCircle(error, radius = 3f * u, center = p(18f, 12f), style = thin)
            }
            ToolSymbol.Sector -> {
                val triangle = polygon(u, 12f to 4f, 20f to 19f, 4f to 19f)
                drawPath(triangle, error.copy(alpha = 0.28f * alpha), style = Fill)
                drawPath(triangle, error, style = Stroke(1.5f * u, join = StrokeJoin.Round))
            }
            ToolSymbol.Unit -> {
                drawRoundRect(accent, topLeft = p(3.5f, 6.5f), size = androidx.compose.ui.geometry.Size(17f * u, 11f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(u, u), style = thin)
                drawLine(accent, p(3.5f, 6.5f), p(20.5f, 17.5f), 1.5f * u)
                drawLine(accent, p(20.5f, 6.5f), p(3.5f, 17.5f), 1.5f * u)
            }
            ToolSymbol.GoAroundLeft -> goAround(u, warning, left = true)
            ToolSymbol.GoAroundRight -> goAround(u, warning, left = false)
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
