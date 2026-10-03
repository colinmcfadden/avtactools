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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.planning.LzSummary
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.delay

class DiagramsActions(
    val startCreating: () -> Unit = {},
    val cancelCreating: () -> Unit = {},
    val create: (name: String, target: String) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
    val analyze: () -> Unit = {},
    val stopAnalysis: () -> Unit = {},
    val dismissAnalysis: () -> Unit = {},
    val selectAircraft: (String) -> Unit = {},
    val refreshWeather: () -> Unit = {},
)

/**
 * The Diagrams tab of the sheet. [suggestedTarget] is the grid under the map's crosshair, offered as the new diagram's target: the usual way
 * to make one is to pan to the landing zone and tap New.
 */
@Composable
fun DiagramsHost(
    suggestedTarget: String?,
    modifier: Modifier = Modifier,
    viewModel: DiagramsViewModel = hiltViewModel(),
    openDiagramExtras: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // How old the weather is is measured to this, moved on a minute at a time: the view model does not tick (an endless timer would never let a test settle).
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    DiagramsContent(
        state = state, suggestedTarget = suggestedTarget, modifier = modifier, openDiagramExtras = openDiagramExtras,
        actions = DiagramsActions(
            startCreating = viewModel::startCreating, cancelCreating = viewModel::cancelCreating, create = viewModel::create, open = viewModel::open,
            rename = viewModel::rename, delete = viewModel::delete, resolve = viewModel::resolve, dismissError = viewModel::dismissError,
            analyze = viewModel::analyze, stopAnalysis = viewModel::stopAnalysis, dismissAnalysis = viewModel::dismissAnalysisError,
            selectAircraft = viewModel::selectAircraft, refreshWeather = viewModel::refreshWeather,
        ),
        nowMillis = now,
    )
}

@Composable
fun DiagramsContent(
    state: DiagramsUiState,
    suggestedTarget: String?,
    actions: DiagramsActions,
    modifier: Modifier = Modifier,
    openDiagramExtras: (@Composable () -> Unit)? = null,
    /** The time the weather's age is measured to. */
    nowMillis: Long = 0L,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Diagrams", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!state.creating) TextAction("New diagram", onClick = actions.startCreating)
        }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        state.current?.let { ActiveDiagramCard(it, actions, nowMillis, openDiagramExtras) }
        if (state.creating) NewDiagramForm(suggestedTarget, state.busy, actions)
        if (state.rows.isEmpty() && !state.creating) {
            Text(
                "No diagrams yet. Pan the map to your landing zone and tap New diagram, or type a grid.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The open diagram has its card, with rename and delete in it; the list is the others (and an open conflict copy, which keeps its choices).
        val others = if (state.current == null) state.rows else state.rows.filterNot { it.isActive && it.conflictOf == null }
        if (state.current != null && others.isNotEmpty()) Text("Other diagrams", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        others.forEach { row -> DiagramListItem(row, actions) }
    }
}

// -- The open diagram: analysis and its summary ------------------------------------------------------------------

/**
 * The open diagram, with the one thing worth doing next: analyse it. Once analysed, what the web's mission summary says about it, and
 * [extras] (the planning graphics, which live in this module's other screen but belong in this card, between the summary and rename/delete).
 */
@Composable
private fun ActiveDiagramCard(current: ActiveDiagramUi, actions: DiagramsActions, nowMillis: Long, extras: (@Composable () -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(current.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    if (current.grid != null) {
                        Text(current.grid, style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    Text(statusLabel(current.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SyncChip(current.sync, current.conflictOf)
                }
            }
            AnalysisControls(current, actions)
            AircraftPicker(current.aircraft, actions.selectAircraft)
            current.summary?.let { SummaryTiles(it) }
            current.weather?.let { WeatherSection(it, nowMillis, actions.refreshWeather) }
            extras?.invoke()
            ManageControls(current.uuid, current.name, actions)
        }
    }
}

@Composable
private fun AnalysisControls(current: ActiveDiagramUi, actions: DiagramsActions) {
    when (val analysis = current.analysis) {
        is AnalysisUi.Running -> {
            PrimaryButton(
                "Analyze", onClick = {}, busy = true,
                busyText = if (analysis.stage == AnalysisStatus.Running.Stage.FINDING_AREA) "Finding the landing area…" else "Measuring the slope…",
            )
            SecondaryButton("Stop", onClick = actions.stopAnalysis)
            Text(
                "Needs a connection, and can take a while. The diagram is safe on this device either way.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is AnalysisUi.Failed -> {
            Banner(analysis.message, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissAnalysis)
            if (current.canAnalyze) PrimaryButton("Try again", onClick = actions.analyze)
        }
        AnalysisUi.Idle -> when {
            !current.canAnalyze -> Text("Set a target to analyze this landing zone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            current.status == DiagramStatus.ANALYZED -> SecondaryButton("Analyze again", onClick = actions.analyze)
            else -> PrimaryButton("Analyze landing zone", onClick = actions.analyze)
        }
    }
}

@Composable
private fun SummaryTiles(summary: SummaryUi) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Tile("Capacity · ${summary.aircraft}", summary.capacity.toString(), Modifier.weight(1f), emphasis = true)
            Tile("Area (ft²)", withCommas(summary.areaSqFt), Modifier.weight(1f))
        }
        Tile("Elevation (MSL)", summary.elevation?.let { if (it.all(Char::isDigit) || it.startsWith("-")) "$it ft" else it } ?: "—", Modifier.fillMaxWidth())
        SlopeTile(summary.slope)
    }
}

@Composable
private fun Tile(
    label: String, value: String, modifier: Modifier = Modifier, emphasis: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = if (emphasis) FontWeight.Bold else FontWeight.SemiBold),
                color = if (emphasis) MaterialTheme.colorScheme.primary else valueColor,
            )
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The tile that says whether the ground is fit to land on. Colour is never the only signal: the word says it too. */
@Composable
private fun SlopeTile(slope: SlopeTileUi) {
    when (slope) {
        SlopeTileUi.Measuring -> Tile("Max terrain slope", "Measuring…", Modifier.fillMaxWidth())
        is SlopeTileUi.Unavailable -> Tile("Max terrain slope", "—", Modifier.fillMaxWidth(), caption = slope.message)
        is SlopeTileUi.Measured -> {
            val color = when (slope.call.level) {
                LzSummary.SlopeLevel.SAFE -> EzpzTheme.status.success
                LzSummary.SlopeLevel.WARNING -> EzpzTheme.status.warning
                LzSummary.SlopeLevel.DANGER -> MaterialTheme.colorScheme.error
            }
            Surface(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Maximum terrain slope ${oneDecimal(slope.call.maxDeg)} degrees. ${slope.call.label}." },
                shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(2.dp, color),
            ) {
                Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                    Text("Max terrain slope", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${oneDecimal(slope.call.maxDeg)}°", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                        Text(slope.call.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = color)
                    }
                    Text(
                        "${slope.source} · ${oneDecimal(slope.resolutionM)} m cells",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 4050 as "4,050", written here rather than by the platform's formatter, which can emit another locale's digits. */
internal fun withCommas(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** JavaScript's `toFixed(1)`: the exact value, ties up, no locale. */
internal fun oneDecimal(value: Double): String {
    val body = java.math.BigDecimal(kotlin.math.abs(value)).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
    return if (value < 0) "-$body" else body
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

internal fun syncLabel(sync: SyncStatus?, conflictOf: String?) = when {
    conflictOf != null -> "Conflict copy"
    sync == SyncStatus.SYNCED -> "Synced"
    else -> "Waiting to sync"
}

/** Rename and Delete for one diagram, each with its small confirmation: a delete is asked about first, a rename has its own field. */
@Composable
private fun ManageControls(uuid: String, name: String, actions: DiagramsActions) {
    var rename by rememberSaveable(uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(uuid) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        if (rename != null) {
            EzpzTextField(value = rename.orEmpty(), onValueChange = { rename = it }, label = "Name", imeAction = ImeAction.Done, onImeAction = { actions.rename(uuid, rename.orEmpty()); rename = null })
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Save name", onClick = { actions.rename(uuid, rename.orEmpty()); rename = null })
                TextAction("Cancel", onClick = { rename = null })
            }
        } else if (confirmDelete) {
            Banner("Delete \"$name\"? It is removed from this device and from your account.", BannerKind.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Delete", onClick = { confirmDelete = false; actions.delete(uuid) })
                TextAction("Keep it", onClick = { confirmDelete = false })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Rename", onClick = { rename = name })
                TextAction("Delete", onClick = { confirmDelete = true })
            }
        }
    }
}

@Composable
private fun DiagramListItem(row: DiagramRow, actions: DiagramsActions) {
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
                    .semantics { contentDescription = "${row.name}. ${statusLabel(row.status)}. ${syncLabel(row.sync, row.conflictOf)}" },
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
                SyncChip(row.sync, row.conflictOf)
            }
            if (row.conflictOf != null) ConflictChoices(row, actions)
            ManageControls(row.uuid, row.name, actions)
        }
    }
}

@Composable
internal fun SyncChip(sync: SyncStatus?, conflictOf: String?) {
    val (color, container) = when {
        conflictOf != null -> EzpzTheme.status.warning to MaterialTheme.colorScheme.background
        sync == SyncStatus.SYNCED -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.surface
    }
    Surface(shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = container, border = BorderStroke(1.dp, color)) {
        Text(syncLabel(sync, conflictOf), Modifier.padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The three ways to settle a conflict, each with what it does, because losing a planning document is the worst thing this app can do. */
@Composable
private fun ConflictChoices(row: DiagramRow, actions: DiagramsActions) =
    ConflictPanel("diagram") { actions.resolve(row.uuid, it) }

/**
 * What a conflict copy of a [noun] ("diagram", "set of routes") says and offers. Keeping both is the primary choice; the two that throw something away
 * say what they discard.
 */
@Composable
internal fun ConflictPanel(noun: String, resolve: (SyncEngine.Resolution) -> Unit) {
    Banner(
        "This is your version, kept because another device changed the same $noun. Nothing was overwritten.",
        BannerKind.Info,
    )
    PrimaryButton("Keep both", onClick = { resolve(SyncEngine.Resolution.KEEP_BOTH) })
    SecondaryButton("Keep mine (replaces the other version)", onClick = { resolve(SyncEngine.Resolution.KEEP_MINE) })
    SecondaryButton("Keep theirs (discard mine)", onClick = { resolve(SyncEngine.Resolution.KEEP_THEIRS) })
}
