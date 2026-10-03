package app.ezpztac.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.android.export.ShareExport
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.DiagramLayer
import app.ezpztac.map.EzpzMap
import app.ezpztac.map.GpsLayer
import app.ezpztac.map.GraphicLabelsLayer
import app.ezpztac.map.PinLabelsLayer
import app.ezpztac.map.PointLayer
import app.ezpztac.map.MapCommand
import app.ezpztac.map.MapProjection
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapViewModel
import app.ezpztac.map.RouteLayer
import app.ezpztac.map.rememberMapHost
import app.ezpztac.symbols.LocalSymbolRenderer
import app.ezpztac.workspace.AircraftHost
import app.ezpztac.workspace.BoundaryHost
import app.ezpztac.workspace.BoundaryToolbarHost
import app.ezpztac.workspace.DiagramsHost
import app.ezpztac.workspace.GraphicsHost
import app.ezpztac.workspace.PointsHost
import app.ezpztac.workspace.RouteToolbarHost
import app.ezpztac.workspace.RoutesHost
import kotlinx.coroutines.flow.filterNotNull

private val PEEK = 112.dp

/** The boundary toolbar sits this far above the sheet's peek, which clears the position readout beneath it. */
private val TOOLBAR_ABOVE_READOUT = 56.dp

/** How far from a graphic's point a finger still counts as on it: the platform's minimum touch target is 48 dp across, so 24 dp each way. */
private const val TOUCH_RADIUS_DP = 24.0

/**
 * The signed-in app: the map is the root, with a bottom sheet over it (docs/NATIVE_APPS_PLAN.md, "Mobile UX"). The sheet holds the
 * diagrams, the aircraft, with the version and a sign-out below; the planning tools go into it as they are built. Opening a diagram takes the map to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHome(
    version: String,
    build: Int,
    maintenance: String?,
    onSignOut: () -> Unit,
    canMakeAircraft: Boolean = true,
    viewModel: MapViewModel = hiltViewModel(),
    home: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scene by home.scene.collectAsStateWithLifecycle()
    val routeScene by home.routes.collectAsStateWithLifecycle()
    val pointScene by home.points.collectAsStateWithLifecycle()
    val drawing by home.isDrawing.collectAsStateWithLifecycle()
    val host = rememberMapHost()
    val density = LocalDensity.current
    val context = LocalContext.current
    val scaffold = rememberBottomSheetScaffoldState()

    LaunchedEffect(host) {
        viewModel.commands.collect { command ->
            when (command) {
                is MapCommand.FlyTo -> host.flyTo(command.at, command.zoom)
                MapCommand.FaceNorth -> host.faceNorth()
            }
        }
    }
    LaunchedEffect(host) { snapshotFlow { host.camera }.filterNotNull().collect(viewModel::onCamera) }
    // The buttons that start drawing a boundary are in the sheet and the corners are put down on the map: down to the peek, so the map is there.
    LaunchedEffect(drawing) { if (drawing) scaffold.bottomSheetState.partialExpand() }
    LaunchedEffect(home) { home.opened.collect { viewModel.showDiagram(it.at, it.baseMap) } }
    // The system may end the process once the app is out of sight, so what has been changed is written now rather than after the usual pause.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { home.appStopped() }

    // Units' symbols are drawn the same way on the map and in the sheet's builder: one renderer, and its cache, for both.
    CompositionLocalProvider(LocalSymbolRenderer provides home.symbols) {
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = PEEK,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Spacing.xl.dp, vertical = Tokens.Spacing.md.dp),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    if (maintenance != null) Banner(maintenance, BannerKind.Warning)
                    // A new diagram starts at the middle of the map: the grid under the crosshair, or its degrees where there is no grid.
                    DiagramsHost(
                        suggestedTarget = state.readout?.let { it.mgrs ?: it.latLon },
                        // The boundary and the planning graphics sit in the open diagram's card: the graphics are put at, and brought to, the crosshair.
                        openDiagramExtras = {
                            BoundaryHost(crosshair = state.center)
                            GraphicsHost(crosshair = state.center, crosshairGrid = state.readout?.mgrs)
                        },
                    )
                    RoutesHost(onExport = { ShareExport.share(context, it) }, crosshair = state.center, crosshairGrid = state.readout?.mgrs)
                    PointsHost()
                    AircraftHost(canMake = canMakeAircraft)
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.home_version, version, build), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.classification_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextAction("Sign out", onClick = onSignOut)
                }
            },
        ) { _ ->
            MapScreen(
                state = state, camera = host.camera, gps = state.gps,
                onSearch = viewModel::search, onClearSearchError = viewModel::clearSearchError, onSelectStyle = { viewModel.selectStyle(it); home.baseMapChosen(it) },
                onToggleGps = viewModel::toggleGps, onGpsPermissionResult = viewModel::permissionResult, onLocateMe = viewModel::locateMe,
                onFaceNorth = viewModel::faceNorth,
                bottomInset = PEEK,
                // Over the readout, above the sheet's peek: only there while a boundary or a route is being drawn (never both: drawing one refuses the other).
                overlay = {
                    val toolbar = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = PEEK + TOOLBAR_ABOVE_READOUT)
                    BoundaryToolbarHost(crosshair = state.center, modifier = toolbar)
                    RouteToolbarHost(crosshair = state.center, modifier = toolbar)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // The sheet's peek is reserved from the map, so the camera's centre is the crosshair, which is drawn above the sheet.
                EzpzMap(
                    host = host, style = state.style, initial = viewModel.initialCamera, bottomInset = PEEK, modifier = Modifier.fillMaxSize(),
                    onTap = { at ->
                        // Distances between points on the screen do not depend on where its middle is, so the size of the view is not needed here.
                        host.camera?.let { camera -> home.mapTapped(at, MapProjection(camera, 0.0, 0.0, density.density.toDouble()), TOUCH_RADIUS_DP * density.density) }
                    },
                )
                DiagramLayer(host, scene)                       // under the routes and the GPS dot, which stays on top
                PointLayer(host, pointScene)                    // local points are under the routes: a route point snapped onto one is the route's
                RouteLayer(host, routeScene)
                GpsLayer(host, state.gps)
                GraphicLabelsLayer(host, scene.graphics)
                PinLabelsLayer(host, routeScene, pointScene)
            }
        }
    }
}
