package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import app.ezpztac.data.HandoffFormat
import app.ezpztac.data.RouteSketching
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.LatLon
import app.ezpztac.model.LocalPointMatch
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.SyncEngine

/** What the Routes panel and the route's plan page can ask for. The crosshair is not in here: the host supplies it, so the screens stay a function of their state. */
class RoutesActions(
    val importMission: () -> Unit = {},
    val showPage: (RoutesPage) -> Unit = {},
    /** Opens the Library on its Routes tab (every set saved, and the conflicts to settle). */
    val browse: () -> Unit = {},
    val openSet: (String) -> Unit = {},
    /** Takes a set out of the session (it stays in the Library). */
    val close: (String) -> Unit = {},
    val renameSet: (String, String) -> Unit = { _, _ -> },
    val deleteSet: (String) -> Unit = {},
    /** Opens the naming sheet for the open set. */
    val save: () -> Unit = {},
    val resolve: (String, SyncEngine.Resolution) -> Unit = { _, _ -> },
    val dismissError: () -> Unit = {},
    val openPlan: (String) -> Unit = {},
    val closePlan: () -> Unit = {},
    val toggleVisible: (String) -> Unit = {},
    val renameRoute: (String, String) -> Unit = { _, _ -> },
    val deleteRoute: (String) -> Unit = {},
    val undo: () -> Unit = {},
    val redo: () -> Unit = {},
    /** The panel's *Sketch a route*: into the open set of sketched routes, or a new one. */
    val startSketch: () -> Unit = {},
    val addAtCrosshair: () -> Unit = {},
    val undoPoint: () -> Unit = {},
    val finishDrawing: () -> Unit = {},
    val cancelDrawing: () -> Unit = {},
    /** Apply what was typed in the route-wide plan (route, what was typed, what the form showed, the key that makes a field's typing one undo step): null when applied, else the words for the field. */
    val applyPlan: (routeId: String, typed: PlanDraft, before: PlanDraft, coalesce: Any?) -> String? = { _, _, _, _ -> null },
    /** Apply what was typed for a point (route, point, what was typed, what was shown, whether it is the first, the undo key): null when applied, else the words for the field. */
    val applyPoint: (routeId: String, pointId: String, typed: PointDraft, before: PointDraft, first: Boolean, coalesce: Any?) -> String? = { _, _, _, _, _, _ -> null },
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
    /** Build the open set for AMPS; [withThreats] puts the threat picture beside it as a `.ths`. */
    val exportSet: (withThreats: Boolean) -> Unit = {},
    val exportRoute: (routeId: String, withThreats: Boolean) -> Unit = { _, _ -> },
    val shareRoute: (routeId: String, format: HandoffFormat) -> Unit = { _, _ -> },
    val dismissExportWarning: () -> Unit = {},
    /** Whether a mission exported takes the threats with it: the set card's box. */
    val includeThreats: (Boolean) -> Unit = {},
    /** The local point a name typed on a route point would put it on, or null when it names none. */
    val localPointNamed: (typed: String) -> LocalPointMatch? = { null },
    /** Asks which Mission Pack a copy of the open set ([uuid], [name]) goes into; none: not offered (no packs here, or one is open). */
    val addToPack: ((uuid: String, name: String) -> Unit)? = null,
)

internal fun actionsOf(
    viewModel: RoutesViewModel,
    crosshair: LatLon? = null,
    importMission: () -> Unit = {},
    save: () -> Unit = {},
    browse: () -> Unit = {},
    addToPack: ((uuid: String, name: String) -> Unit)? = null,
) = RoutesActions(
    importMission = importMission, showPage = viewModel::showPage, browse = browse, openSet = viewModel::openSet, close = viewModel::close, renameSet = viewModel::renameSet,
    deleteSet = viewModel::deleteSet, save = save, resolve = viewModel::resolve, dismissError = viewModel::dismissError, openPlan = viewModel::openPlan,
    closePlan = viewModel::closePlan, toggleVisible = viewModel::toggleVisible, renameRoute = viewModel::renameRoute, deleteRoute = viewModel::deleteRoute,
    undo = viewModel::undo, redo = viewModel::redo, startSketch = viewModel::startSketch, addAtCrosshair = { viewModel.addAtCrosshair(crosshair) },
    undoPoint = viewModel::undoPoint, finishDrawing = { viewModel.finishDrawing() }, cancelDrawing = viewModel::cancelDrawing, applyPlan = viewModel::applyPlan,
    applyPoint = viewModel::applyPoint, selectPoint = viewModel::selectPoint, renamePoint = viewModel::renamePoint, setPointType = viewModel::setPointType,
    makeShaping = viewModel::makeShaping, makeNamed = viewModel::makeNamed, nudgePoint = viewModel::nudgePoint,
    pointToCrosshair = { routeId, pointId -> viewModel.pointToCrosshair(routeId, pointId, crosshair) }, pointToText = viewModel::pointToText,
    addShapingPoint = { routeId -> viewModel.addShapingPoint(routeId, crosshair) }, fetchWinds = viewModel::fetchWinds, fetchElevations = viewModel::fetchElevations,
    dismissNote = viewModel::dismissNote, exportSet = { viewModel.exportSet(it) }, exportRoute = viewModel::exportRoute, shareRoute = viewModel::shareRoute,
    dismissExportWarning = viewModel::dismissExportWarning, includeThreats = viewModel::includeThreats, localPointNamed = viewModel::localPointNamed,
    addToPack = addToPack,
)

/**
 * The Routes panel (docs/native-design: Routes): the route sets open now, or every set in the Library, and the sheet that names the open set. [crosshair]
 * is where a held point is brought to; [threatCount] is how many threats an AMPS export can take beside it. What is built here is handed over by whoever
 * collects [RoutesViewModel.exports] (the app's frame, which is there whether or not this panel is offered).
 */
@Composable
fun RoutesHost(
    modifier: Modifier = Modifier,
    crosshair: LatLon? = null,
    threatCount: Int = 0,
    /** Opens the Library on its Routes tab. */
    onBrowseLibrary: () -> Unit = {},
    /** Asks which Mission Pack a copy of the open set goes into: given while packs run here and none is open. */
    onAddToPack: ((uuid: String, name: String) -> Unit)? = null,
    viewModel: RoutesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var naming by rememberSaveable { mutableStateOf(false) }
    val actions = actionsOf(viewModel, crosshair, save = { naming = true }, browse = onBrowseLibrary, addToPack = onAddToPack)
    when (state.page) {
        RoutesPage.HOME -> RoutesHomeContent(state, actions, modifier, threatCount)
    }
    if (naming) {
        val open = state.open
        if (open == null || !open.canSave) {
            naming = false
        } else {
            SaveRoutesSheet(
                open, state.sets.filter { it.uuid != open.uuid }.map { it.uuid to it.name },
                onSave = { name, replacing -> viewModel.saveAs(name, replacing); naming = false }, onDismiss = { naming = false },
            )
        }
    }
}

/** The panel's foot (the design's): *Sketch a route* and *Import .msnx*, or, while a route is being drawn, *Cancel* and *Finish*. [onImport] opens the file picker. */
@Composable
fun RoutesFooterHost(onImport: () -> Unit, crosshair: LatLon?, viewModel: RoutesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RoutesFooter(state, actionsOf(viewModel, crosshair, importMission = onImport))
}

/** The held route's plan, as a page of its own over the frame (the design's RoutePlan), while it is open. */
@Composable
fun RoutePlanHost(
    crosshair: LatLon?,
    crosshairGrid: String?,
    threatCount: Int,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    /** A tablet's: in the pane beside the map, put away with a close button ([RoutePlanPage]). */
    inPane: Boolean = false,
    viewModel: RoutesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var naming by rememberSaveable { mutableStateOf(false) }
    if (!state.planOpen) return
    RoutePlanPage(state, actionsOf(viewModel, crosshair, save = { naming = true }), crosshairGrid, threatCount, modifier, snackbars, inPane = inPane)
    if (naming) {
        val open = state.open
        if (open == null || !open.canSave) {
            naming = false
        } else {
            SaveRoutesSheet(
                open, state.sets.filter { it.uuid != open.uuid }.map { it.uuid to it.name },
                onSave = { name, replacing -> viewModel.saveAs(name, replacing); naming = false }, onDismiss = { naming = false },
            )
        }
    }
}

/** The toolbar over the map, shown only while a route is being drawn. Over the sheet's peek, so it is reachable with the sheet down. */
@Composable
fun RouteToolbarHost(crosshair: LatLon?, modifier: Modifier = Modifier, viewModel: RoutesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RouteToolbarWhileDrawing(state, actionsOf(viewModel, crosshair), modifier)
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

