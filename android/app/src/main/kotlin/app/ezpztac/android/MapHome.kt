package app.ezpztac.android

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.android.export.ShareExport
import app.ezpztac.android.frame.AccountButton
import app.ezpztac.android.frame.Panel
import app.ezpztac.android.frame.PanelHead
import app.ezpztac.android.frame.PhoneFrame
import app.ezpztac.android.frame.panelsFor
import app.ezpztac.android.frame.pick
import app.ezpztac.android.frame.shownPanel
import app.ezpztac.android.packs.InviteNotices
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.rememberPanelSheetState
import app.ezpztac.map.DiagramLayer
import app.ezpztac.map.EzpzMap
import app.ezpztac.map.GpsLayer
import app.ezpztac.map.GraphicLabelsLayer
import app.ezpztac.map.MapChip
import app.ezpztac.map.MapCommand
import app.ezpztac.map.MapProjection
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapViewModel
import app.ezpztac.map.PinLabelsLayer
import app.ezpztac.map.PointLayer
import app.ezpztac.map.RouteLayer
import app.ezpztac.map.ThreatLabelsLayer
import app.ezpztac.map.ThreatLayer
import app.ezpztac.map.rememberMapHost
import app.ezpztac.missionpacks.InviteState
import app.ezpztac.network.ApiUser
import app.ezpztac.symbols.LocalSymbolRenderer
import app.ezpztac.workspace.BoundaryToolbarHost
import app.ezpztac.workspace.DiagramsUiState
import app.ezpztac.workspace.DiagramsViewModel
import app.ezpztac.workspace.LzPage
import app.ezpztac.workspace.LzPanelHost
import app.ezpztac.workspace.PlacementToolbarHost
import app.ezpztac.workspace.HeldObjectBarHost
import app.ezpztac.workspace.IncomingHost
import app.ezpztac.workspace.PointsHost
import app.ezpztac.workspace.RoutePlanHost
import app.ezpztac.workspace.RouteToolbarHost
import app.ezpztac.workspace.RoutesFooterHost
import app.ezpztac.workspace.RoutesHost
import app.ezpztac.workspace.RoutesPage
import app.ezpztac.workspace.RoutesUiState
import app.ezpztac.workspace.RoutesViewModel
import app.ezpztac.workspace.ExportFile
import app.ezpztac.workspace.ThreatsHost
import app.ezpztac.workspace.ThreatWindowGuard
import app.ezpztac.workspace.ThreatsViewModel
import app.ezpztac.workspace.rememberFileImport
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** How far from a graphic's point a finger still counts as on it: the platform's minimum touch target is 48 dp across, so 24 dp each way. */
private const val TOUCH_RADIUS_DP = 24.0

/** Why nothing is being sent: the person can still plan, and needs to know their work is waiting, not lost, and how to end the wait. */
private const val TOO_OLD = "This version of the app is no longer supported by the server. Until you update it, your changes stay on this phone and nothing is sent."

/** The bar for what is held does not stretch across a tablet. */
private val HELD_BAR_MAX_WIDTH = 460.dp

/**
 * The signed-in app: the map at the root with the redesign's frame over it (docs/native-design, the phone's screens; docs/ANDROID_SCREENS_PLAN.md,
 * S2): the search bar with the account button, the chips, the map's buttons, the crosshair and its readout, the panel in a sheet that rests lowered,
 * at half or at full height, and the bar of panels. Each panel holds today's sections until its own screen is built (S3 to S6).
 */
@Composable
fun MapHome(
    user: ApiUser,
    version: String,
    build: Int,
    maintenance: String?,
    onSignOut: () -> Unit,
    /** What became of an invitation link the app was opened with ([InviteNotices]), and the try it answers. */
    invite: InviteState = InviteState.None,
    inviteAttempt: Int = 0,
    onRetryInvite: () -> Unit = {},
    onDismissInvite: () -> Unit = {},
    /** This version is below the server's minimum as last heard, with no config to show the update screen: nothing is sent ([Gate.Ready.tooOld]). */
    tooOld: Boolean = false,
    viewModel: MapViewModel = hiltViewModel(),
    home: HomeViewModel = hiltViewModel(),
    display: DisplayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scene by home.scene.collectAsStateWithLifecycle()
    val routeScene by home.routes.collectAsStateWithLifecycle()
    val pointScene by home.points.collectAsStateWithLifecycle()
    val threatScene by home.threatScene.collectAsStateWithLifecycle()
    val counts by home.counts.collectAsStateWithLifecycle()
    val night by display.night.collectAsStateWithLifecycle()
    val threatsModel: ThreatsViewModel = hiltViewModel()                  // the threats panel and the mission export share one
    val drawing by home.isDrawing.collectAsStateWithLifecycle()
    val drawingWhat by home.drawingWhat.collectAsStateWithLifecycle()
    val slopeToggle by home.slopeToggle.collectAsStateWithLifecycle()
    val host = rememberMapHost()
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { androidx.compose.material3.SnackbarHostState() }
    val importFiles = rememberFileImport()
    // The LZ/PZ panel's state: which of its pages is showing (for the header and Back), and its toasts.
    val diagrams: DiagramsViewModel = hiltViewModel()
    val lz by diagrams.state.collectAsStateWithLifecycle()
    // The Routes panel's state: its page (for the header and Back), the route plan page over the frame, and its toasts.
    val routesModel: RoutesViewModel = hiltViewModel()
    val routes by routesModel.state.collectAsStateWithLifecycle()
    // A phone starts with the sheet lowered, so the map is usable (the web's sheet does the same).
    val sheet = rememberPanelSheetState(SheetDetent.Lowered)
    var chosen by rememberSaveable { mutableStateOf(Panel.Lz) }
    val panels = remember(user.features) { panelsFor(user) }
    val current = shownPanel(chosen, panels)
    // Threats are drawn, held and taken in only while the account has them, which is while their panel is offered.
    val threatsOn = Panel.Threats in panels
    // How tall whatever floats above the readout is now (a drawing toolbar, the held object's bar), in pixels: the toasts go above it.
    var floating by remember { mutableIntStateOf(0) }

    LaunchedEffect(host) {
        viewModel.commands.collect { command ->
            when (command) {
                is MapCommand.FlyTo -> host.flyTo(command.at, command.zoom)
                MapCommand.FaceNorth -> host.faceNorth()
            }
        }
    }
    LaunchedEffect(host) { snapshotFlow { host.camera }.filterNotNull().collect(viewModel::onCamera) }
    // A boundary or a route is drawn, or a tool places, by tapping the map: the sheet goes down to its header, so the map is there, each time one starts.
    LaunchedEffect(drawingWhat) { if (drawingWhat != null) sheet.animateTo(SheetDetent.Lowered) }
    LaunchedEffect(threatsOn) { home.allowThreats(threatsOn) }
    // While there are threats on the map, the window is kept out of screenshots, screen recording and the app switcher, whichever panel shows.
    if (threatsOn) ThreatWindowGuard(threatsModel)
    LaunchedEffect(home) { home.opened.collect { viewModel.showDiagram(it.at, it.baseMap) } }
    LaunchedEffect(home) { home.focus.collect { viewModel.showArea(it.at, it.zoom) } }          // a mission just brought in: the map goes to its routes
    // A file another app opened with this one (Files, a mail, the share sheet, or Import) is put to the person here: nothing is imported until they accept.
    IncomingHost(threatsAllowed = threatsOn)
    // An invitation link: said above the sheet, in the frame's own toasts.
    InviteNotices(invite, inviteAttempt, snackbars, onRetry = onRetryInvite, onDismiss = onDismissInvite)
    // What the LZ/PZ panel says once it has happened (an LZ/PZ named), in the same toasts.
    LaunchedEffect(diagrams) { diagrams.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(routesModel) { routesModel.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // A page inside the LZ/PZ panel (an LZ/PZ's plan, every LZ/PZ, the aircraft): Back goes back up it, as its arrow does.
    BackHandler(enabled = current == Panel.Lz && lz.page != LzPage.HOME) { diagrams.showPage(lzBack(lz)) }
    BackHandler(enabled = current == Panel.Routes && routes.page != RoutesPage.HOME) { routesModel.showPage(RoutesPage.HOME) }
    // The route's plan is a page over the frame: Back goes back to the map and its panel, as its arrow does (declared after the panel's, so it is asked first).
    BackHandler(enabled = routes.planOpen) { routesModel.closePlan() }
    // A mission goes to AMPS with the threats it was planned against when the person asks for them (the set card's box): the .ths named for it travels beside it.
    // A route handed to another app (a GPX, a flight plan) never takes them.
    val shareExport: (ExportFile) -> Unit = { file ->
        if (file.withThreats) threatsModel.shareWithThreats(file) { files -> ShareExport.share(context, files) } else ShareExport.share(context, file)
    }
    // Collected here, not in the Routes panel: the route's plan page can build a file whether or not the panel is offered, and a file built must reach the share sheet.
    val share by rememberUpdatedState(shareExport)
    LaunchedEffect(routesModel) { routesModel.exports.collect { share(it) } }
    // The plan page covers the frame: a field left focused under it (a rename in the panel) would keep the keyboard and take what is typed.
    LaunchedEffect(routes.planOpen) { if (routes.planOpen) focus.clearFocus() }

    // Units' symbols are drawn the same way on the map and in the sheet's builder: one renderer, and its cache, for both.
    CompositionLocalProvider(LocalSymbolRenderer provides home.symbols) {
        Box(Modifier.fillMaxSize()) {
            PhoneFrame(
                panels = panels,
                current = current,
                counts = counts,
                onPick = { picked ->
                    val (panel, detent) = pick(current, sheet.settled, picked)
                    focus.clearFocus()                                                   // a field in the panel left behind keeps no keyboard up
                    chosen = panel
                    scope.launch { sheet.animateTo(detent) }
                },
                sheet = sheet,
                snackbars = snackbars,
                head = when (current) {
                    Panel.Lz -> lzHead(lz) { diagrams.showPage(it) }
                    Panel.Threats -> PanelHead(current.label, if (counts.threats == 0) "This device only" else "${counts.threats} · this device only")
                    Panel.Imports -> PanelHead(current.label, when (counts.imports) { 0 -> null; 1 -> "1 set of local points"; else -> "${counts.imports} sets of local points" })
                    Panel.Routes -> routesHead(routes) { routesModel.showPage(it) }
                },
                floatingPx = { floating },
                // The Routes panel's foot: Sketch a route and Import .msnx, pinned under the sets open now.
                footer = if (current == Panel.Routes && routes.page == RoutesPage.HOME) {
                    { RoutesFooterHost(onImport = importFiles, crosshair = state.center, viewModel = routesModel) }
                } else {
                    null
                },
                // Under the route's plan page the frame is still there (the map, the panels and what they listen for) but neither read nor touched.
                modifier = if (routes.planOpen) Modifier.clearAndSetSemantics {} else Modifier,
                panel = { panel ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                    ) {
                        if (maintenance != null) Banner(maintenance, BannerKind.Warning)
                        if (tooOld) Banner(TOO_OLD, BannerKind.Warning, actionLabel = "Update", onAction = { openStore(context) })
                        when (panel) {
                            // A new LZ/PZ starts at the crosshair, and the tools add there. An administrator can switch own aircraft off for an account (the
                            // server would refuse what it made).
                            Panel.Lz -> LzPanelHost(
                                crosshair = state.center, crosshairGrid = state.readout?.mgrs, canMakeAircraft = user.hasFeature("aircraft_profiles"), viewModel = diagrams,
                            )
                            Panel.Routes -> RoutesHost(crosshair = state.center, threatCount = counts.threats, viewModel = routesModel)
                            Panel.Threats -> ThreatsHost(onExport = { ShareExport.share(context, it) }, crosshair = state.center, crosshairGrid = state.readout?.mgrs, viewModel = threatsModel)
                            Panel.Imports -> PointsHost()
                        }
                        Spacer(Modifier.height(Tokens.Spacing.lg.dp))
                    }
                },
            ) { frame ->
                MapScreen(
                    state = state, camera = host.camera, gps = state.gps,
                    onSearch = viewModel::search, onClearSearchError = viewModel::clearSearchError, onSelectStyle = { viewModel.selectStyle(it); home.baseMapChosen(it) },
                    onToggleGps = viewModel::toggleGps, onGpsPermissionResult = viewModel::permissionResult, onLocateMe = viewModel::locateMe,
                    onFaceNorth = viewModel::faceNorth,
                    slope = slopeToggle, onToggleSlope = home::toggleSlope,
                    onCopyReadout = { text ->
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Grid", text))) }
                        // Android 13 and later say so themselves; before that the app does.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbars.showSnackbar("Copied $text") }
                    },
                    padding = frame.padding,
                    controlsBottom = frame.controlsBottom,
                    account = {
                        AccountButton(
                            name = user.name, email = user.email, version = stringResource(R.string.home_version, version, build),
                            night = night, onNight = display::setNight, onSignOut = onSignOut, notice = stringResource(R.string.classification_notice),
                        )
                    },
                    chips = {
                        // The workspace is the person's own until mission packs have their switcher (P1); then the chip opens it.
                        MapChip(EzpzIcons.person, "Personal", description = "Workspace: Personal")
                        MapChip(EzpzIcons.fileOpen, "Import", description = "Import a mission, local points or threats", onClick = importFiles)
                    },
                    sheet = frame.sheet,
                    // Over the readout, above the sheet: only there while a boundary or a route is being drawn (never both), or something on the map is held.
                    overlay = {
                        // One place for what floats there, measured, so the toasts can go above it (nothing there measures nothing).
                        Box(Modifier.onSizeChanged { floating = it.height }) {
                            BoundaryToolbarHost(crosshair = state.center)
                            RouteToolbarHost(crosshair = state.center)
                            PlacementToolbarHost(crosshair = state.center)
                            // What a tap holds (a graphic, a threat, a point of a route): its name, how to turn it, and a way to the rest of its options. Not while drawing, where taps are corners.
                            if (!drawing) {
                                HeldObjectBarHost(
                                    onOptions = {
                                        val panel = home.heldPanel()
                                        if (panel == Panel.Routes) {
                                            // A point of a route: its plan, a page of its own, opened at that point; only for an account offered routes, as on the web.
                                            if (Panel.Routes in panels) routesModel.openHeldPlan()
                                        } else {
                                            panel?.let {
                                                chosen = it
                                                if (it == Panel.Lz) diagrams.showPage(LzPage.PLAN)     // a graphic's controls are on its LZ/PZ's plan
                                            }
                                            scope.launch { sheet.animateTo(SheetDetent.Full) }
                                        }
                                    },
                                    onDone = home::releaseHeld,
                                    modifier = Modifier.widthIn(max = HELD_BAR_MAX_WIDTH).fillMaxWidth(),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // The search bar and the sheet are reserved from the map, so the camera's centre is the crosshair, which is drawn between them.
                    EzpzMap(
                        host = host, style = state.style, initial = viewModel.initialCamera, padding = frame.padding, modifier = Modifier.fillMaxSize(),
                        onTap = { at ->
                            // Distances between points on the screen do not depend on where its middle is, so the size of the view is not needed here.
                            host.camera?.let { camera -> home.mapTapped(at, MapProjection(camera, 0.0, 0.0, density.density.toDouble()), TOUCH_RADIUS_DP * density.density) }
                        },
                        // A long press on a graphic, a threat or a point of a route picks it up; the finger then moves it, and lifting puts it down.
                        onPickUp = { at ->
                            host.camera?.let { camera -> home.dragStarted(at, MapProjection(camera, 0.0, 0.0, density.density.toDouble()), TOUCH_RADIUS_DP * density.density) } ?: false
                        },
                        onDrag = home::dragMoved, onDrop = home::dragEnded, onDragCancelled = home::dragCancelled,
                    )
                    DiagramLayer(host, scene)                       // under the routes and the GPS dot, which stays on top
                    PointLayer(host, pointScene)                    // local points are under the routes: a route point snapped onto one is the route's
                    RouteLayer(host, routeScene)
                    ThreatLayer(host, threatScene)                  // range rings; symbols and names are Compose below
                    GpsLayer(host, state.gps)
                    GraphicLabelsLayer(host, scene.graphics, footprints = home.unitFootprints)
                    PinLabelsLayer(host, routeScene, pointScene)
                    ThreatLabelsLayer(host, threatScene)
                    // At night the imagery is dimmed with the rest of the screen (docs/NATIVE_APPS_PLAN.md, "Night mode"); a tap goes through to the map.
                    if (night) NightDim()
                }
            }
            // The held route's plan (the design's RoutePlan): a page of its own over the frame, with the frame's toasts.
            RoutePlanHost(crosshair = state.center, crosshairGrid = state.readout?.mgrs, threatCount = counts.threats, snackbars = snackbars, viewModel = routesModel)
        }
    }
}

/** The night palette's darkened map: black over the imagery, the drawn layers and their labels. Nothing in it takes a touch. */
@Composable
private fun NightDim() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = NIGHT_DIM)))
}

/** How much the night palette darkens the map: enough that satellite imagery does not light a cockpit, not so much that it cannot be read. */
private const val NIGHT_DIM = 0.45f

/** The LZ/PZ panel's header for the page it is on: its open LZ/PZs; an LZ/PZ's plan, with its grid; every LZ/PZ; the aircraft. [go] moves between them. */
private fun lzHead(lz: DiagramsUiState, go: (LzPage) -> Unit): PanelHead = when (lz.page) {
    LzPage.HOME -> PanelHead("LZ/PZ", if (lz.openCount == 0) "Nothing open" else "${lz.openCount} open · this session")
    LzPage.PLAN -> PanelHead(
        lz.current?.name ?: "LZ/PZ", lz.current?.grid ?: "No target yet", subtitleMono = lz.current?.grid != null,
        back = { go(LzPage.HOME) }, backDescription = if (lz.openCount == 1) "Back to the open LZ/PZ" else "Back to the open LZ/PZs, ${lz.openCount} open",
    )
    LzPage.LIBRARY -> PanelHead(
        "All LZ/PZs", if (lz.rows.size == 1) "1 in your Library" else "${lz.rows.size} in your Library", back = { go(LzPage.HOME) },
        backDescription = "Back to the open LZ/PZs",
    )
    LzPage.AIRCRAFT -> PanelHead("Aircraft", "The mission aircraft and your own", back = { go(lzBack(lz)) }, backDescription = "Back")
}

/** The Routes panel's header for the page it is on: the sets open now, or every set, with Back. */
private fun routesHead(routes: RoutesUiState, go: (RoutesPage) -> Unit): PanelHead = when (routes.page) {
    RoutesPage.HOME -> PanelHead("Routes", when (routes.openCount) { 0 -> "Nothing open"; 1 -> "1 route set in this session"; else -> "${routes.openCount} route sets in this session" })
    RoutesPage.LIBRARY -> PanelHead(
        "All route sets", if (routes.sets.size == 1) "1 in your Library" else "${routes.sets.size} in your Library", back = { go(RoutesPage.HOME) },
        backDescription = "Back to the route sets open now",
    )
}

/** Where Back goes from the LZ/PZ panel's page: the aircraft back to the plan they were opened from, the rest to the open LZ/PZs. */
private fun lzBack(lz: DiagramsUiState): LzPage = if (lz.page == LzPage.AIRCRAFT && lz.current != null) LzPage.PLAN else LzPage.HOME
