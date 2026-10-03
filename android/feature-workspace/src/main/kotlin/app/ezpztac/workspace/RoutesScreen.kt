package app.ezpztac.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.RouteSketching
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon
import app.ezpztac.model.LocalPointMatch
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.SyncEngine

class RoutesActions(
    val startCreating: () -> Unit = {},
    val cancelCreating: () -> Unit = {},
    val createSet: (name: String) -> Unit = {},
    val openSet: (String) -> Unit = {},
    val closeSet: () -> Unit = {},
    val renameSet: (String, String) -> Unit = { _, _ -> },
    val deleteSet: (String) -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
    val selectRoute: (String) -> Unit = {},
    val toggleVisible: (String) -> Unit = {},
    val renameRoute: (String, String) -> Unit = { _, _ -> },
    val deleteRoute: (String) -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
    val startDrawing: () -> Unit = {},
    val addAtCrosshair: () -> Unit = {},
    val undoPoint: () -> Unit = {},
    val finishDrawing: () -> Unit = {},
    val cancelDrawing: () -> Unit = {},
    /** Apply the route-wide plan form: null when it is applied, else the words for the form. */
    val applyPlan: (routeId: String, PlanDraft) -> String? = { _, _ -> null },
    /** Apply a point's form (route, point, what was typed, what was shown, whether it is the first point): null when applied, else the words for the row. */
    val applyPoint: (routeId: String, pointId: String, typed: PointDraft, before: PointDraft, first: Boolean) -> String? = { _, _, _, _, _ -> null },
    val selectPoint: (String) -> Unit = {},
    val renamePoint: (routeId: String, pointId: String, name: String) -> Unit = { _, _, _ -> },
    val setPointType: (routeId: String, pointId: String, ptType: String) -> Unit = { _, _, _ -> },
    val makeShaping: (routeId: String, pointId: String) -> Unit = { _, _ -> },
    val makeNamed: (routeId: String, pointId: String) -> Unit = { _, _ -> },
    /** Move a point [northFt] feet north and [eastFt] east (negative: south, west). */
    val nudgePoint: (routeId: String, pointId: String, northFt: Double, eastFt: Double) -> Unit = { _, _, _, _ -> },
    val pointToCrosshair: (routeId: String, pointId: String) -> Unit = { _, _ -> },
    /** Put a point at a grid or coordinate that was typed: null when it was moved, else the words for the field. */
    val pointToText: (routeId: String, pointId: String, text: String) -> String? = { _, _, _ -> null },
    /** Add a point that only shapes the line, at the crosshair. */
    val addShapingPoint: (routeId: String) -> Unit = {},
    val fetchWinds: () -> Unit = {},
    val fetchElevations: () -> Unit = {},
    val dismissNote: () -> Unit = {},
    val exportSet: () -> Unit = {},
    val exportRoute: (routeId: String) -> Unit = {},
    val dismissExportWarning: () -> Unit = {},
    /** The local point a name typed on a route point would put it on, or null when it names none. */
    val localPointNamed: (typed: String) -> LocalPointMatch? = { null },
)

private fun actionsOf(viewModel: RoutesViewModel, crosshair: LatLon? = null) = RoutesActions(
    startCreating = viewModel::startCreating, cancelCreating = viewModel::cancelCreating, createSet = viewModel::createSet, openSet = viewModel::openSet,
    closeSet = viewModel::closeSet, renameSet = viewModel::renameSet, deleteSet = viewModel::deleteSet, resolve = viewModel::resolve,
    dismissError = viewModel::dismissError, selectRoute = viewModel::selectRoute, toggleVisible = viewModel::toggleVisible, renameRoute = viewModel::renameRoute,
    deleteRoute = viewModel::deleteRoute, undo = viewModel::undo, redo = viewModel::redo, startDrawing = viewModel::startDrawing,
    addAtCrosshair = { viewModel.addAtCrosshair(crosshair) }, undoPoint = viewModel::undoPoint, finishDrawing = { viewModel.finishDrawing() },
    cancelDrawing = viewModel::cancelDrawing, applyPlan = viewModel::applyPlan, applyPoint = viewModel::applyPoint, selectPoint = viewModel::selectPoint,
    renamePoint = viewModel::renamePoint, setPointType = viewModel::setPointType, makeShaping = viewModel::makeShaping, makeNamed = viewModel::makeNamed,
    nudgePoint = viewModel::nudgePoint, pointToCrosshair = { routeId, pointId -> viewModel.pointToCrosshair(routeId, pointId, crosshair) },
    pointToText = viewModel::pointToText, addShapingPoint = { routeId -> viewModel.addShapingPoint(routeId, crosshair) },
    fetchWinds = viewModel::fetchWinds, fetchElevations = viewModel::fetchElevations, dismissNote = viewModel::dismissNote,
    exportSet = viewModel::exportSet, exportRoute = viewModel::exportRoute, dismissExportWarning = viewModel::dismissExportWarning,
    localPointNamed = viewModel::localPointNamed,
)

/**
 * The Routes part of the sheet: the saved sets, and the routes of the open one. [crosshair] is where the map is looking, which is where a new shaping
 * point is put and where a held point is brought to; [crosshairGrid] is the same place in words.
 */
@Composable
fun RoutesHost(
    onExport: (ExportFile) -> Unit,
    modifier: Modifier = Modifier,
    crosshair: LatLon? = null,
    crosshairGrid: String? = null,
    viewModel: RoutesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share by rememberUpdatedState(onExport)
    // A mission built is handed to the app that can send it (the system share sheet); this module knows nothing of that.
    LaunchedEffect(viewModel) { viewModel.exports.collect { share(it) } }
    RoutesContent(state, actionsOf(viewModel, crosshair), modifier, crosshairGrid)
}

/** The toolbar over the map, shown only while a route is being drawn. Over the sheet's peek, so it is reachable with the sheet down. */
@Composable
fun RouteToolbarHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: RoutesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RouteToolbarWhileDrawing(state, actionsOf(viewModel, crosshair), modifier)
}

@Composable
fun RoutesContent(state: RoutesUiState, actions: RoutesActions, modifier: Modifier = Modifier, crosshairGrid: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Routes", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!state.creating) TextAction("New set", onClick = actions.startCreating)
        }
        state.error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
        state.open?.let { OpenSetCard(it, state.drawing, state.exporting, actions) }
        state.exportWarning?.let { Banner(it, BannerKind.Warning, actionLabel = "Dismiss", onAction = actions.dismissExportWarning) }
        state.detail?.let { RouteDetailCard(it, state.fetching, state.note, state.exporting, actions, crosshairGrid = crosshairGrid) }
        if (state.creating) NewSetForm(actions)
        if (state.sets.isEmpty() && state.open == null && !state.creating) {
            Text(
                "No routes yet. Tap New set, then Draw a route and tap the map to lay it out.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The open set has its card, with rename and delete in it; the list is the others (and an open conflict copy, which keeps its choices).
        val others = if (state.open == null) state.sets else state.sets.filterNot { it.isOpen && it.conflictOf == null }
        if (state.open != null && others.isNotEmpty()) Text("Other sets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        others.forEach { SetListItem(it, actions) }
    }
}

// -- The open set ------------------------------------------------------------------------------------------------

@Composable
private fun OpenSetCard(open: OpenSetUi, drawing: RouteDrawingUi?, exporting: Boolean, actions: RoutesActions) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(open.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                }
                SyncChip(open.sync, open.conflictOf)
            }
            if (open.conflictOf != null) ConflictPanel("set of routes") { actions.resolve(open.uuid, it) }
            if (drawing != null) {
                Text(
                    "Drawing a route: ${pointsText(drawing.points)}. Tap the map to add a point, then Finish. The buttons are over the map.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PrimaryButton("Draw a route", onClick = actions.startDrawing)
            }
            if (open.routes.isEmpty()) {
                if (drawing == null) Text("This set has no routes yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                open.routes.forEach { RouteRow(it, actions) }
            }
            if (open.routes.isNotEmpty()) {
                if (exporting) PrimaryButton("Export for AMPS", onClick = {}, busy = true, busyText = "Building the mission…")
                else SecondaryButton("Export for AMPS", onClick = actions.exportSet)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Undo", onClick = actions.undo, enabled = open.canUndo)
                TextAction("Redo", onClick = actions.redo, enabled = open.canRedo)
            }
            SetManageControls(open.uuid, open.name, actions, extra = { TextAction("Close", onClick = actions.closeSet) })
        }
    }
}

@Composable
private fun RouteRow(route: RouteRowUi, actions: RoutesActions) {
    var rename by rememberSaveable(route.id) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(route.id) { mutableStateOf(false) }
    val border = if (route.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(if (route.selected) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).clickable { actions.selectRoute(route.id) }.semantics {
                    contentDescription = "${route.name}. ${routeFacts(route)}. ${if (route.visible) "Shown on the map" else "Hidden"}"
                    selected = route.selected
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                // The colour is how the line is told apart on the map; it is never the only thing that names the route.
                Box(Modifier.size(16.dp).background(parseColor(route.color), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(route.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(pointFacts(route), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The distance and time on a line of their own: after the point counts they wrapped with a dot left hanging at the end of the line.
                    route.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextAction(if (route.visible) "Hide" else "Show", onClick = { actions.toggleVisible(route.id) })
            }
            if (route.selected) {
                if (rename != null) {
                    EzpzTextField(
                        value = rename.orEmpty(), onValueChange = { rename = it }, label = "Route name", imeAction = ImeAction.Done,
                        onImeAction = { actions.renameRoute(route.id, rename.orEmpty()); rename = null },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        TextAction("Save name", onClick = { actions.renameRoute(route.id, rename.orEmpty()); rename = null })
                        TextAction("Cancel", onClick = { rename = null })
                    }
                } else if (confirmDelete) {
                    Banner("Delete \"${route.name}\"? You can undo this until the set is closed.", BannerKind.Warning)
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        TextAction("Delete", onClick = { confirmDelete = false; actions.deleteRoute(route.id) })
                        TextAction("Keep it", onClick = { confirmDelete = false })
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                        TextAction("Rename", onClick = { rename = route.name })
                        TextAction("Delete", onClick = { confirmDelete = true })
                    }
                }
            }
        }
    }
}

/** `5 points · 3 route points`: what a route is made of. */
private fun pointFacts(route: RouteRowUi): String =
    "${pointsText(route.pointCount)} · ${route.routePointCount} route ${if (route.routePointCount == 1) "point" else "points"}"

/** Everything a screen reader is told of a route in one go: `5 points · 3 route points · 12.3 nm · 8:15`. */
private fun routeFacts(route: RouteRowUi): String = listOfNotNull(pointFacts(route), route.summary).joinToString(" · ")

private fun parseColor(hex: String): Color = runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.Gray)

// -- Sets --------------------------------------------------------------------------------------------------------

@Composable
private fun NewSetForm(actions: RoutesActions) {
    var name by rememberSaveable { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            EzpzTextField(
                value = name, onValueChange = { name = it }, label = "Name (optional)", imeAction = ImeAction.Done, onImeAction = { actions.createSet(name) },
                hint = "A set holds the routes of one mission. Left blank, it is named MISSION and a number.",
            )
            PrimaryButton("Create set", onClick = { actions.createSet(name) })
            SecondaryButton("Cancel", onClick = actions.cancelCreating)
        }
    }
}

/** Rename and Delete for one set, each with its small confirmation: a delete is asked about first, a rename has its own field. */
@Composable
private fun SetManageControls(uuid: String, name: String, actions: RoutesActions, extra: @Composable () -> Unit = {}) {
    var rename by rememberSaveable(uuid) { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(uuid) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        if (rename != null) {
            EzpzTextField(
                value = rename.orEmpty(), onValueChange = { rename = it }, label = "Name", imeAction = ImeAction.Done,
                onImeAction = { actions.renameSet(uuid, rename.orEmpty()); rename = null },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Save name", onClick = { actions.renameSet(uuid, rename.orEmpty()); rename = null })
                TextAction("Cancel", onClick = { rename = null })
            }
        } else if (confirmDelete) {
            Banner("Delete \"$name\" and its routes? It is removed from this device and from your account.", BannerKind.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Delete set", onClick = { confirmDelete = false; actions.deleteSet(uuid) })
                TextAction("Keep it", onClick = { confirmDelete = false })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                TextAction("Rename set", onClick = { rename = name })
                TextAction("Delete set", onClick = { confirmDelete = true })
                extra()
            }
        }
    }
}

@Composable
private fun SetListItem(row: RouteSetRow, actions: RoutesActions) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).clickable { actions.openSet(row.uuid) }
                    .semantics { contentDescription = "${row.name}. ${routesText(row.routeCount)}. ${syncLabel(row.sync, row.conflictOf)}" },
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(routesText(row.routeCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncChip(row.sync, row.conflictOf)
            }
            if (row.conflictOf != null) ConflictPanel("set of routes") { actions.resolve(row.uuid, it) }
            SetManageControls(row.uuid, row.name, actions)
        }
    }
}

// -- Over the map while drawing ----------------------------------------------------------------------------------

/** The toolbar, only while a route is being drawn: with nothing to draw it takes no room and no taps. */
@Composable
fun RouteToolbarWhileDrawing(state: RoutesUiState, actions: RoutesActions, modifier: Modifier = Modifier) {
    val drawing = state.drawing ?: return
    RouteToolbar(drawing, state.error, actions, modifier)
}

/** What is over the map while drawing: how many points, and the things to do. */
@Composable
fun RouteToolbar(drawing: RouteDrawingUi, error: String?, actions: RoutesActions, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = Tokens.Spacing.md.dp).semantics { contentDescription = "Drawing a route" },
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            error?.let { Banner(it, BannerKind.Error, actionLabel = "Dismiss", onAction = actions.dismissError) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Route: ${pointsText(drawing.points)}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (drawing.canFinish) "Tap the map for another point, or Finish." else "Tap the map to add a point. A route needs ${RouteSketching.MIN_POINTS}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextAction("Cancel", onClick = actions.cancelDrawing)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                SecondaryButton("Add here", onClick = actions.addAtCrosshair, modifier = Modifier.weight(1f))
                SecondaryButton("Undo", onClick = actions.undoPoint, enabled = drawing.points > 0, modifier = Modifier.weight(1f))
                PrimaryButton("Finish", onClick = actions.finishDrawing, enabled = drawing.canFinish, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun pointsText(count: Int) = if (count == 1) "1 point" else "$count points"

private fun routesText(count: Int) = if (count == 1) "1 route" else "$count routes"
