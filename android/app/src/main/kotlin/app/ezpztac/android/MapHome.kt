package app.ezpztac.android

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.android.export.ShareExport
import app.ezpztac.android.frame.AccountButton
import app.ezpztac.android.frame.MapFrame
import app.ezpztac.android.frame.Panel
import app.ezpztac.android.frame.PanelHead
import app.ezpztac.android.frame.PhoneFrame
import app.ezpztac.android.frame.RailWorkspace
import app.ezpztac.android.frame.SupportingPane
import app.ezpztac.android.frame.TabletFrame
import app.ezpztac.android.frame.TabletRail
import app.ezpztac.android.frame.WorkspaceChips
import app.ezpztac.android.frame.WorkspaceLine
import app.ezpztac.android.frame.rememberFrameState
import app.ezpztac.android.frame.rememberTouchExploration
import app.ezpztac.android.frame.lookingAtPack
import app.ezpztac.android.frame.panelsFor
import app.ezpztac.android.frame.pick
import app.ezpztac.android.frame.rememberTabletFrameState
import app.ezpztac.android.frame.shownPanel
import app.ezpztac.android.frame.usesTabletFrame
import app.ezpztac.android.packs.InviteNotices
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.rememberPanelSheetState
import app.ezpztac.map.DiagramLayer
import app.ezpztac.map.OtherLzLayer
import app.ezpztac.map.EzpzMap
import app.ezpztac.map.GpsLayer
import app.ezpztac.map.ContourLabelsLayer
import app.ezpztac.map.GraphicLabelsLayer
import app.ezpztac.map.MapChromeBoxes
import app.ezpztac.map.MgrsGridLayer
import app.ezpztac.map.MapCommand
import app.ezpztac.map.MapProjection
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapViewModel
import app.ezpztac.map.PinLabelsLayer
import app.ezpztac.map.PointLayer
import app.ezpztac.map.PointersLayer
import app.ezpztac.map.RouteLayer
import app.ezpztac.map.ThreatLabelsLayer
import app.ezpztac.map.ThreatLayer
import app.ezpztac.map.rememberMapHost
import app.ezpztac.map.rememberMapSearchState
import app.ezpztac.missionpacks.InviteState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft
import app.ezpztac.network.ApiUser
import app.ezpztac.packs.FinishedBand
import app.ezpztac.packs.FinishedBanner
import app.ezpztac.packs.AddToPackHost
import app.ezpztac.packs.ItemDialogsHost
import app.ezpztac.packs.PackCopyViewModel
import app.ezpztac.packs.PackItemCopyViewModel
import app.ezpztac.packs.MembersHost
import app.ezpztac.packs.MembersViewModel
import app.ezpztac.packs.NewPackHost
import app.ezpztac.packs.NewPackViewModel
import app.ezpztac.packs.TeamsHost
import app.ezpztac.packs.TeamsViewModel
import app.ezpztac.packs.PackDialogsHost
import app.ezpztac.packs.PackHeaderMenu
import app.ezpztac.packs.PackPanelFoot
import app.ezpztac.packs.PackPanelHost
import app.ezpztac.packs.PackPanelViewModel
import app.ezpztac.packs.PackWords
import app.ezpztac.packs.SwitcherContent
import app.ezpztac.packs.SwitcherSheet
import app.ezpztac.packs.WorkspaceViewModel
import app.ezpztac.packs.actionsOf
import app.ezpztac.sync.RecordKind
import app.ezpztac.symbols.LocalSymbolRenderer
import app.ezpztac.workspace.BoundaryToolbarHost
import app.ezpztac.workspace.DiagramsUiState
import app.ezpztac.workspace.DiagramsViewModel
import app.ezpztac.workspace.ExportFile
import app.ezpztac.workspace.GraphicKind
import app.ezpztac.workspace.GraphicsViewModel
import app.ezpztac.workspace.HeldObjectBarHost
import app.ezpztac.workspace.LzToolRailHost
import app.ezpztac.workspace.ImportReviewHost
import app.ezpztac.workspace.ImportSheet
import app.ezpztac.workspace.ImportSheetContent
import app.ezpztac.workspace.ImportsHost
import app.ezpztac.workspace.IncomingViewModel
import app.ezpztac.workspace.LibraryActions
import app.ezpztac.workspace.LibraryHost
import app.ezpztac.workspace.LibraryTab
import app.ezpztac.workspace.LzPage
import app.ezpztac.workspace.LzPane
import app.ezpztac.workspace.LzPanelHost
import app.ezpztac.workspace.MapPointAction
import app.ezpztac.workspace.PlacementToolbarHost
import app.ezpztac.workspace.PointsViewModel
import app.ezpztac.workspace.RoutePlanHost
import app.ezpztac.workspace.RouteToolbarHost
import app.ezpztac.workspace.RoutesFooterHost
import app.ezpztac.workspace.RoutesHost
import app.ezpztac.workspace.RoutesPage
import app.ezpztac.workspace.RoutesUiState
import app.ezpztac.workspace.RoutesViewModel
import app.ezpztac.workspace.ThreatsHost
import app.ezpztac.workspace.ThreatsViewModel
import app.ezpztac.workspace.importsLine
import app.ezpztac.workspace.offersLibrary
import app.ezpztac.workspace.rememberFileImport
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
 * at half or at full height, and the bar of panels. Each panel holds today's sections until its own screen is built (S3 to S6). A window 840 dp wide or more has
 * the tablet's frame instead (S9, [TabletFrame]): the rail, the panel in a pane beside the map, and the open LZ/PZ's plan or a route's in a pane on its right.
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
    // The open LZ/PZs not being worked on, drawn faded; those hidden with the eye on their rows; and the one a tap holds.
    val others by home.others.collectAsStateWithLifecycle()
    val hiddenOnMap by home.hiddenOnMap.collectAsStateWithLifecycle()
    val heldOther by home.heldOther.collectAsStateWithLifecycle()
    val threatScene by home.threatScene.collectAsStateWithLifecycle()
    val threatCounts by home.counts.collectAsStateWithLifecycle()
    val night by display.night.collectAsStateWithLifecycle()
    val threatsModel: ThreatsViewModel = hiltViewModel()                  // the threats panel and the mission export share one
    val drawing by home.isDrawing.collectAsStateWithLifecycle()
    val drawingWhat by home.drawingWhat.collectAsStateWithLifecycle()
    val slopeToggle by home.slopeToggle.collectAsStateWithLifecycle()
    val slopeOpacity by home.slopeOpacity.collectAsStateWithLifecycle()
    val host = rememberMapHost()
    // The phone's search, folded into the row or open across it, and what was typed (a tablet's bar is always open): the frame folds it.
    val search = rememberMapSearchState()
    // What floats over the map, where the map's screen lays it out: the MGRS grid's labels keep off it.
    val chrome = remember { MapChromeBoxes() }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { androidx.compose.material3.SnackbarHostState() }
    // Every file comes in through one review, however it arrives: chosen here (the Import menu, the panels' own Import), or opened with the app elsewhere.
    val incoming: IncomingViewModel = hiltViewModel()
    val importFiles = rememberFileImport(incoming)
    var importMenu by rememberSaveable { mutableStateOf(false) }
    // The Imports panel's state: its count is the tab's badge and its header's line.
    val importsModel: PointsViewModel = hiltViewModel()
    val imports by importsModel.state.collectAsStateWithLifecycle()
    // The Library, a page over the frame while it is open, on the tab it was opened at (null: shut).
    var libraryTab by rememberSaveable { mutableStateOf<LibraryTab?>(null) }
    // The Pack panel, while a pack is open: its items and history, and what happens to the pack and its items open in the editors.
    val packPanel: PackPanelViewModel = hiltViewModel()
    val pack by packPanel.state.collectAsStateWithLifecycle()
    val counts = threatCounts.copy(imports = imports.itemCount, packNew = pack.newCount)
    // The LZ/PZ panel's state: which of its pages is showing (for the header and Back), and its toasts.
    val diagrams: DiagramsViewModel = hiltViewModel()
    val lz by diagrams.state.collectAsStateWithLifecycle()
    // The open LZ/PZ's planning graphics (the same view model the LZ/PZ panel draws): the map menu's *Add object* puts one at the press.
    val graphics: GraphicsViewModel = hiltViewModel()
    val graphicsState by graphics.state.collectAsStateWithLifecycle()
    // The Routes panel's state: its page (for the header and Back), the route plan page over the frame, and its toasts.
    val routesModel: RoutesViewModel = hiltViewModel()
    val routes by routesModel.state.collectAsStateWithLifecycle()
    // A phone starts with the sheet lowered, so the map is usable (the web's sheet does the same).
    val sheet = rememberPanelSheetState(SheetDetent.Lowered)
    // A window at least 840 dp wide and 480 dp tall (a tablet across, a foldable open) has the tablet's frame; a phone, on its side too, the phone's.
    val window = LocalWindowInfo.current.containerSize
    val tablet = with(density) { usesTabletFrame(window.width.toDp(), window.height.toDp()) }
    // The tablet's panes: whether the list is collapsed, which pane was opened last. What the person does elsewhere brings the list forward, as the phone raises its sheet.
    val tabletState = rememberTabletFrameState()
    // The workspace (the Library, or a Mission Pack) and its switcher: a sheet on a phone, a popup at the rail's tile on a tablet.
    val workspace: WorkspaceViewModel = hiltViewModel()
    val openPack by workspace.open.collectAsStateWithLifecycle()
    val packsHere by workspace.available.collectAsStateWithLifecycle()
    // The panel chosen, and whether the phone's frame is hidden (a tap on the map hides it): choosing a panel, by the person or in code, shows the frame.
    val frameState = rememberFrameState()
    val panels = remember(user.features, openPack != null) { panelsFor(user, packOpen = openPack != null) }
    val current = shownPanel(frameState.chosen, panels)
    // The frame never hides while an accessibility service explores by touch (nothing would say it went, or how to bring it back), nor on a tablet.
    val exploring by rememberTouchExploration()
    LaunchedEffect(exploring, tablet) { if (exploring || tablet) frameState.show() }
    // A tablet's list collapses instead (its own button): the phone's closed panel is not carried into it.
    LaunchedEffect(tablet) { if (tablet) frameState.open() }
    // Threats are drawn, held and taken in only while the account has them, which is while their panel is offered.
    val threatsOn = Panel.Threats in panels
    // How tall whatever floats above the readout is now (a drawing toolbar, the held object's bar), in pixels: the toasts go above it.
    var floating by remember { mutableIntStateOf(0) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    // A pack picked in the switcher shows its panel, raised, once it is open (at once if it is the one open); one opened otherwise (at launch, from an
    // invitation link) shows its panel where it was. The bookkeeping is done before the sheet moves: a raise cut short by a finger leaves nothing stale.
    var raiseFor by remember { mutableStateOf<String?>(null) }
    val raisePack: () -> Unit = {
        frameState.show()
        frameState.open()
        if (tablet) tabletState.showList() else scope.launch { if (sheet.settled == SheetDetent.Lowered) sheet.animateTo(SheetDetent.Half) }
    }
    val pickedPack: (String?) -> Unit = { uuid ->
        if (uuid != null && uuid == openPack?.uuid) {
            frameState.choose(Panel.Pack)
            raisePack()
        } else {
            raiseFor = uuid
        }
    }
    // New Mission Pack, from the switcher: once it is made it opens, raised as a pack picked in the switcher is.
    val newPack: NewPackViewModel = hiltViewModel()
    val newPackOpen by remember(newPack) { newPack.state.map { it.open }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    // Members of the open pack, from the Pack panel: a page (a dialog on a tablet) that draws the frame's toasts over itself.
    val members: MembersViewModel = hiltViewModel()
    val membersOpen by remember(members) { members.state.map { it.open }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    LaunchedEffect(members) { members.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // Teams, from the switcher's teams line: a page (a dialog on a tablet) drawing the frame's toasts over itself.
    val teams: TeamsViewModel = hiltViewModel()
    val teamsOpen by remember(teams) { teams.state.map { it.open }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    LaunchedEffect(teams) { teams.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // A copy of the person's own item into a Mission Pack (the design's PackDialogs A): from the Library's menu and the LZ/PZ and Routes panels while packs run
    // here and none is open, and the Library's *Add to pack* while one is open that this person can change. A copy into a pack not open offers to open it.
    val copies: PackCopyViewModel = hiltViewModel()
    LaunchedEffect(copies) {
        copies.notices.collect { notice ->
            val pack = notice.openPack
            if (pack == null) {
                snackbars.showSnackbar(notice.words, withDismissAction = true)
            } else if (snackbars.showSnackbar(notice.words, actionLabel = "Open pack", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                libraryTab = null
                pickedPack(pack)
                workspace.switchTo(pack)
            }
        }
    }
    // A pack's item kept as a copy of the person's own, and a copy updated from its Library original (the design's PackDialogs D and B): from the Pack panel, the
    // LZ/PZ and Routes panels' menus in a pack, and a finished pack's banner.
    val itemCopies: PackItemCopyViewModel = hiltViewModel()
    val saveCopy: ((String) -> Unit)? = if (openPack != null) ({ ref -> CopyRules.packItemOf(ref)?.let(itemCopies::askSave) }) else null
    val askCopy: ((RecordKind) -> (uuid: String, name: String) -> Unit)? =
        if (CopyRules.asksWhere(packsHere, openPack)) ({ kind -> { uuid, name -> copies.ask(kind, uuid, name) } }) else null
    // The pack a Library item is added to rather than opened: the one open, while this person can change it.
    val addingTo = CopyRules.addingTo(packsHere, openPack)
    val adding by copies.adding.collectAsStateWithLifecycle()
    // A page or dialog over the frame (the Library, Members, Teams, New pack): nobody is looking at the map or the Pack panel under it.
    val overFrame = libraryTab != null || membersOpen || teamsOpen || newPackOpen
    LaunchedEffect(newPack) {
        newPack.made.collect { uuid ->
            pickedPack(uuid)
            workspace.refresh()
            workspace.switchTo(uuid)
        }
    }
    LaunchedEffect(newPack) { newPack.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // A pack made to start with items from the Library: the Library opens on it once it is the pack open, adding to it. Another pack opened first lets it go.
    var libraryAfter by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(newPack) { newPack.withLibrary.collect { libraryAfter = it } }
    LaunchedEffect(openPack?.uuid) {
        val wanted = libraryAfter ?: return@LaunchedEffect
        when (CopyRules.libraryAfter(wanted, openPack?.uuid)) {
            CopyRules.LibraryAfter.WAIT -> Unit                                                    // still on its way
            CopyRules.LibraryAfter.OPEN_LIBRARY -> {
                libraryTab = libraryTabsFor(panels).firstOrNull() ?: LibraryTab.LZ
                libraryAfter = null
            }
            CopyRules.LibraryAfter.LET_GO -> libraryAfter = null
        }
    }
    if (switcherOpen && !tablet && packsHere) SwitcherSheet(workspace, onDismiss = { switcherOpen = false }, onPick = pickedPack, onNewPack = newPack::start, onTeams = teams::open)
    var packShownFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openPack?.uuid) {
        val uuid = openPack?.uuid
        val news = uuid != null && uuid != packShownFor
        val raise = news && raiseFor == uuid
        packShownFor = uuid
        raiseFor = null
        if (news) frameState.choose(Panel.Pack)                                                // a pack opened or joined shows the frame
        if (raise) raisePack()
    }
    // The map covered (the Library, a route's plan page, the phone's sheet at full height): nobody is pointing at it, for everyone else in a pack.
    val mapCovered = overFrame || (!tablet && (routes.planOpen || (sheet.settled == SheetDetent.Full && !frameState.hidden && !frameState.closed)))
    LaunchedEffect(mapCovered) { home.mapCovered(mapCovered) }
    LaunchedEffect(packPanel) { packPanel.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // An item opened from the Pack panel is shown in its own panel (an LZ/PZ in LZ/PZ, a route set in Routes), as the web's dock does.
    LaunchedEffect(packPanel) { packPanel.opened.collect { kind -> frameState.choose(if (kind == "route") Panel.Routes else Panel.Lz) } }
    // The sheet goes with what holds it (packs stopping, the frame turning into a tablet's): when they come back it is not open by itself.
    LaunchedEffect(tablet, packsHere) { if (tablet || !packsHere) switcherOpen = false }
    LaunchedEffect(workspace) { workspace.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // An invitation link that let the person in to a pack opens it, as the web's does: when the answer arrives, not from its toast, which may be put away;
    // once for each invitation, however often this is composed again before the toast goes.
    LaunchedEffect(invite) {
        val joined = (invite as? InviteState.Joined)?.answer ?: return@LaunchedEffect
        joined.pack?.uuid?.let { workspace.openJoined(joined.invite.id, it) }
    }
    // The map's menu (the design's MapMenu), open where a long press on nothing was; what Add route point here would do is taken then. It is kept while
    // the menu closes (menuOpen false), so it does not empty as it fades.
    var menu by remember { mutableStateOf<OpenMapMenu?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // On the phone's row it floats over the map on a disc of its own; on a tablet it is the rail's.
    val account: @Composable (floating: Boolean) -> Unit = { floating ->
        AccountButton(
            name = user.name, email = user.email, version = stringResource(R.string.home_version, version, build),
            night = night, onNight = display::setNight, onSignOut = onSignOut, notice = stringResource(R.string.classification_notice), floating = floating,
        )
    }
    // A tablet opens the open LZ/PZ's plan beside the map when one is opened (the design's supporting pane); its close button puts it away until another is opened.
    LaunchedEffect(lz.current?.uuid) { if (tablet && lz.current != null && lz.page == LzPage.HOME) diagrams.showPage(LzPage.PLAN) }
    // The aircraft page is in the list: on a tablet it brings the list forward (from the plan pane's Manage, the list may be collapsed or waiting).
    LaunchedEffect(lz.page) { if (tablet && lz.page == LzPage.AIRCRAFT) tabletState.showList() }
    // A toast from a panel; the Saved one offers its Library tab (the design's Saved: Open Library), if the account has it when the toast is shown: the collectors
    // below outlive a change of features, so they read the tabs as they are now.
    val libraryTabs by rememberUpdatedState(libraryTabsFor(panels))
    LaunchedEffect(packPanel) {
        packPanel.kept.collect { notice ->
            val tab = notice.library?.let(CopyRules::libraryTabOf)
            if (tab == null) snackbars.showSnackbar(notice.words, withDismissAction = true) else snackbars.panelToast(notice.words, tab, libraryTabs) { libraryTab = it }
        }
    }
    LaunchedEffect(itemCopies) {
        itemCopies.notices.collect { notice ->
            val tab = notice.library?.let(CopyRules::libraryTabOf)
            if (tab == null) snackbars.showSnackbar(notice.words, withDismissAction = true) else snackbars.panelToast(notice.words, tab, libraryTabs) { libraryTab = it }
        }
    }
    suspend fun toast(text: String, tab: LibraryTab) = snackbars.panelToast(text, tab, libraryTabs) { libraryTab = it }
    val copyGrid: (String) -> Unit = { text ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Grid", text))) }
        // Android 13 and later say so themselves; before that the app does.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbars.showSnackbar("Copied $text") }
    }

    LaunchedEffect(host) {
        viewModel.commands.collect { command ->
            when (command) {
                is MapCommand.FlyTo -> host.flyTo(command.at, command.zoom)
                MapCommand.FaceNorth -> host.faceNorth()
            }
        }
    }
    LaunchedEffect(host) {
        snapshotFlow { host.camera }.filterNotNull().collect { camera ->
            viewModel.onCamera(camera)
            home.cameraAt(camera.center, camera.byHand)                                          // this person's pointer, for everyone else in a pack
        }
    }
    // A boundary or a route is drawn, or a tool places, by tapping the map: the sheet goes down to its header, so the map is there, each time one starts.
    LaunchedEffect(drawingWhat) { if (drawingWhat != null && !tablet) sheet.animateTo(SheetDetent.Lowered) }
    LaunchedEffect(threatsOn) { home.allowThreats(threatsOn) }
    LaunchedEffect(home) { home.opened.collect { viewModel.showDiagram(it.at, it.baseMap) } }
    LaunchedEffect(home) { home.focus.collect { viewModel.showArea(it.at, it.zoom) } }          // a mission just brought in: the map goes to its routes
    // A file another app opened with this one (Files, a mail, the share sheet), or chosen with Import, is put to the person here: nothing is imported until they say so.
    ImportReviewHost(threatsAllowed = threatsOn, large = tablet, viewModel = incoming)
    if (importMenu) ImportSheet(threatsAllowed = threatsOn, onChoose = importFiles, onDismiss = { importMenu = false })            // the phone's, and a panel's own Import
    LaunchedEffect(incoming) { incoming.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(importsModel) { importsModel.notices.collect { toast(it, LibraryTab.POINTS) } }
    // An invitation link: said above the sheet, in the frame's own toasts.
    InviteNotices(invite, inviteAttempt, snackbars, onRetry = onRetryInvite, onDismiss = onDismissInvite)
    // What the LZ/PZ panel says once it has happened (an LZ/PZ named), in the same toasts.
    LaunchedEffect(diagrams) { diagrams.notices.collect { toast(it, LibraryTab.LZ) } }
    LaunchedEffect(routesModel) { routesModel.notices.collect { toast(it, LibraryTab.ROUTES) } }
    LaunchedEffect(threatsModel) { threatsModel.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(home) { home.notices.collect { snackbars.showSnackbar(it, withDismissAction = true) } }
    // A page inside the LZ/PZ panel (an LZ/PZ's plan, every LZ/PZ, the aircraft): Back goes back up it, as its arrow does.
    BackHandler(enabled = current == Panel.Lz && (if (tablet) lz.page == LzPage.AIRCRAFT else lz.page != LzPage.HOME)) { diagrams.showPage(lzBack(lz)) }
    BackHandler(enabled = current == Panel.Routes && routes.page != RoutesPage.HOME) { routesModel.showPage(RoutesPage.HOME) }
    // The route's plan is a page over the frame: Back goes back to the map and its panel, as its arrow does (declared after the panel's, so it is asked first).
    BackHandler(enabled = routes.planOpen && !tablet) { routesModel.closePlan() }
    // The Library's own Back is its page's (it is composed after these, so it is asked first while it is open).
    // What the LZ/PZ and Routes panels say of an action the Library handed them (a name refused, a write that failed, one asked while another ran) would be a banner
    // under the Library, out of sight: while it is open it is said in its toasts instead.
    val libraryOpen = libraryTab != null
    // The Library opens over the frame, and a route's plan page is a page over the frame too: a Saved toast's Open Library pressed on the plan page would leave the
    // plan under the Library, and come back over the panel the Library's Open then chose. Opening the Library closes it.
    LaunchedEffect(libraryOpen) { if (libraryOpen && !tablet) routesModel.closePlan() }
    LaunchedEffect(libraryOpen, lz.error) {
        val said = lz.error
        if (libraryOpen && said != null) {
            diagrams.dismissError()
            snackbars.showSnackbar(said, withDismissAction = true)
        }
    }
    LaunchedEffect(libraryOpen, routes.error) {
        val said = routes.error
        if (libraryOpen && said != null) {
            routesModel.dismissError()
            snackbars.showSnackbar(said, withDismissAction = true)
        }
    }
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
            // The panel showing, in the phone's sheet or the tablet's pane: today's sections and the notices above them.
            val panelContent: @Composable (Panel) -> Unit = { panel ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    if (maintenance != null) Banner(maintenance, BannerKind.Warning)
                    if (tooOld) Banner(TOO_OLD, BannerKind.Warning, actionLabel = "Update", onAction = { openStore(context) })
                    // A finished pack, said at the top of every panel on a phone (a tablet has its band across the top instead).
                    if (!tablet) {
                        openPack?.takeIf { it.finished }?.let {
                            FinishedBanner(it, onDuplicate = workspace::askDuplicate, onReopen = workspace::askReopen, onSaveCopy = CopyRules.bannerCopy(lz.current?.uuid, saveCopy))
                        }
                    }
                    when (panel) {
                        // Looking at it: the panel showing, in sight (not lowered on a phone, not collapsed on a tablet, not with the phone's frame hidden).
                        Panel.Pack -> PackPanelHost(
                            packPanel,
                            looking = lookingAtPack(
                                current, tablet, tabletState.listShown, sheet.settled, covered = overFrame || (!tablet && (routes.planOpen || frameState.hidden || frameState.closed)),
                            ),
                            onMembers = { invite -> members.open(openPack?.name.orEmpty(), invite) },
                            onUpdate = itemCopies::askUpdate, onSaveCopy = itemCopies::askSave,
                        )
                        // A new LZ/PZ starts at the crosshair, and the tools add there. An administrator can switch own aircraft off for an account (the
                        // server would refuse what it made).
                        Panel.Lz -> LzPanelHost(
                            crosshair = state.center, crosshairGrid = state.readout?.mgrs, canMakeAircraft = user.hasFeature("aircraft_profiles"),
                            onBrowseLibrary = { libraryTab = LibraryTab.LZ }, pane = if (tablet) LzPane.LIST else LzPane.WHOLE, viewModel = diagrams,
                            onAddToPack = askCopy?.invoke(RecordKind.LZ), onSaveCopy = saveCopy, hiddenOnMap = hiddenOnMap, onToggleOnMap = home::toggleOnMap,
                        )
                        Panel.Routes -> RoutesHost(
                            crosshair = state.center, threatCount = counts.threats, onBrowseLibrary = { libraryTab = LibraryTab.ROUTES }, viewModel = routesModel,
                            onAddToPack = askCopy?.invoke(RecordKind.ROUTE), onSaveCopy = saveCopy,
                        )
                        Panel.Threats -> ThreatsHost(
                            onExport = { ShareExport.share(context, it) }, onImport = importFiles, crosshair = state.center, crosshairGrid = state.readout?.mgrs,
                            viewModel = threatsModel,
                        )
                        // A mission opens in Routes and a threat file's threats are in Threats: each panel, shown at the height the sheet is.
                        Panel.Imports -> ImportsHost(
                            threatsAllowed = threatsOn,
                            onOpenMission = if (Panel.Routes in panels) { uuid -> routesModel.openSet(uuid); frameState.choose(Panel.Routes) } else null,
                            onOpenThreats = { frameState.choose(Panel.Threats) },
                            viewModel = importsModel,
                        )
                    }
                    Spacer(Modifier.height(Tokens.Spacing.lg.dp))
                }
            }
            // The map and its controls, inside whichever frame.
            val mapContent: @Composable (MapFrame) -> Unit = { frame ->
                MapScreen(
                    state = state, camera = host.camera, gps = state.gps,
                    onSearch = viewModel::search, onClearSearchError = viewModel::clearSearchError, onSelectStyle = { viewModel.selectStyle(it); home.baseMapChosen(it) },
                    onToggleGps = viewModel::toggleGps, onGpsPermissionResult = viewModel::permissionResult, onLocateMe = viewModel::locateMe,
                    onFaceNorth = viewModel::faceNorth,
                    onToggleGrid = viewModel::toggleGrid, chrome = chrome,
                    slope = slopeToggle, onToggleSlope = home::toggleSlope,
                    onCopyReadout = copyGrid,
                    padding = frame.padding,
                    controlsBottom = frame.controlsBottom,
                    // On a tablet the account, the workspace, Import and the Library are the rail's.
                    account = { if (!tablet) account(true) },
                    // How the open pack stands: a tablet's line always, a phone's only when it wants attention (its chip shows it), not under a full sheet.
                    belowChips = { WorkspaceLine(openPack, tablet, underFullSheet = !tablet && sheet.heading == SheetDetent.Full && !frameState.closed) },
                    chips = {
                        if (!tablet) {
                            // The Library opens on the tab of the panel showing, so what the person was looking at is what they find.
                            WorkspaceChips(
                                openPack, packsHere, onSwitcher = { switcherOpen = true }, onImport = { importMenu = true },
                                onLibrary = {
                                    focus.clearFocus()
                                    libraryTab = libraryTabFor(current)
                                },
                            )
                        }
                    },
                    buttonsAtFoot = tablet,
                    search = search,
                    hiding = frame.hiding,
                    // The LZ/PZ tools on the map's edge while its panel is the one chosen: no going into the panel's plan to add an object (the owner's, 2026-10-22).
                    tools = if (current == Panel.Lz) {
                        { LzToolRailHost(crosshair = state.center, onSaid = { said -> scope.launch { snackbars.showSnackbar(said, withDismissAction = true) } }) }
                    } else {
                        null
                    },
                    // Over the readout, above the sheet: only there while a boundary or a route is being drawn (never both), or something on the map is held.
                    overlay = {
                        // One place for what floats there, measured, so the toasts can go above it (nothing there measures nothing).
                        Box(Modifier.onSizeChanged { floating = it.height }) {
                            BoundaryToolbarHost(crosshair = state.center, offerAllowed = !drawing)
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
                                        } else if (panel == Panel.Threats) {
                                            threatsModel.editHeld()                                   // a threat: its form, over everything
                                        } else {
                                            panel?.let {
                                                frameState.choose(it)
                                                if (it == Panel.Lz) diagrams.showPage(LzPage.PLAN)     // a graphic's controls are on its LZ/PZ's plan
                                            }
                                            frameState.show()                                          // the sheet raised: the frame and the panel with it
                                            frameState.open()
                                            if (tablet) tabletState.showList() else scope.launch { sheet.animateTo(SheetDetent.Full) }
                                        }
                                    },
                                    onDone = home::releaseHeld,
                                    modifier = Modifier.widthIn(max = HELD_BAR_MAX_WIDTH).fillMaxWidth(),
                                    alsoOpen = heldOther,
                                    // Opened from the map: the LZ/PZ panel shows it, as opening it from its row does.
                                    onOpenOther = { id ->
                                        home.releaseHeld()
                                        diagrams.open(id)
                                        frameState.choose(Panel.Lz)
                                    },
                                    onHideOther = home::hideOther,
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
                            // With the phone's search open, a tap on the map folds it and does nothing else.
                            if (!search.foldOnTap()) {
                                // Distances between points on the screen do not depend on where its middle is, so the size of the view is not needed here.
                                host.camera?.let { camera ->
                                    val frames = home.mapTapped(at, MapProjection(camera, 0.0, 0.0, density.density.toDouble()), TOUCH_RADIUS_DP * density.density)
                                    // One that held and put down nothing hides the phone's frame, or shows it again (a tablet's never hides).
                                    if (frames && !tablet) frameState.tapped(exploring)
                                }
                            }
                        },
                        // A long press on a graphic, a threat or a point of a route holds it (its bar, nothing else) and picks it up; the finger then moves it, and lifting puts it down.
                        onPickUp = { at ->
                            host.camera?.let { camera -> home.dragStarted(at, MapProjection(camera, 0.0, 0.0, density.density.toDouble()), TOUCH_RADIUS_DP * density.density) } ?: false
                        },
                        onDrag = home::dragMoved, onDrop = home::dragEnded, onDragCancelled = home::dragCancelled,
                        // A long press on nothing that can be picked up opens the map's menu there (not while taps are corners, points or placements).
                        onPress = { at, px ->
                            val place = home.pressed(at)
                            if (place != null) {
                                menu = OpenMapMenu(place, IntOffset(px.x.roundToInt(), px.y.roundToInt()), routesModel.pointAction())
                                menuOpen = true
                            }
                            place != null
                        },
                    )
                    MgrsGridLayer(host, state.grid, chrome = chrome)        // its lines under everything planned, its labels under every other label
                    OtherLzLayer(host, others)                      // the open LZ/PZs not being worked on, faded, under the one that is
                    DiagramLayer(host, scene, slopeOpacity / 100f)  // under the routes and the GPS dot, which stays on top
                    PointLayer(host, pointScene)                    // local points are under the routes: a route point snapped onto one is the route's
                    RouteLayer(host, routeScene)
                    ThreatLayer(host, threatScene)                  // range rings; symbols and names are Compose below
                    GpsLayer(host, state.gps)
                    ContourLabelsLayer(host, scene.slope)          // the slope map's heights, under every other label
                    GraphicLabelsLayer(host, scene.graphics, footprints = home.unitFootprints)
                    PinLabelsLayer(host, routeScene, pointScene, others = others)
                    ThreatLabelsLayer(host, threatScene)
                    PackPointers(host, home)
                    // At night the imagery is dimmed with the rest of the screen (docs/NATIVE_APPS_PLAN.md, "Night mode"); a tap goes through to the map.
                    if (night) NightDim()
                    // Anchored at the press, in the map's own pixels (this box is the map's).
                    val open = menu
                    MapMenu(
                        expanded = menuOpen, place = open?.place, at = open?.at ?: IntOffset.Zero, onDismiss = { menuOpen = false },
                        choices = if (open == null) MapMenuChoices() else MapMenuChoices(
                            newLz = if (Panel.Lz in panels) ({ diagrams.newAt(open.place.at); frameState.choose(Panel.Lz) }) else null,
                            newLzOff = lz.pack?.off,
                            addThreat = if (threatsOn) ({ threatsModel.beginAdd(open.place.at) }) else null,
                            routePoint = if (Panel.Routes in panels) open.routePoint to { routesModel.addPointAt(open.place.at); frameState.choose(Panel.Routes) } else null,
                            addObject = lz.current?.takeIf { Panel.Lz in panels }?.let { current ->
                                AddObjectChoice(current.name, off = addObjectOff(graphicsState.canEdit, graphicsState.locked)) { kind -> placeObject(graphics, kind, open.place.at) }
                            },
                            copyGrid = { copyGrid(open.place.grid) },
                        ),
                    )
                }
            }
            val latestMap by rememberUpdatedState(mapContent)
            val latestPanel by rememberUpdatedState(panelContent)
            val movableMap = remember { movableContentOf { frame: MapFrame -> latestMap(frame) } }
            val movablePanels = remember { Panel.entries.associateWith { p -> movableContentOf { latestPanel(p) } } }
            val routePlan: @Composable (Boolean) -> Unit = { inPane ->
                RoutePlanHost(
                    crosshair = state.center, crosshairGrid = state.readout?.mgrs, threatCount = counts.threats, snackbars = if (inPane) null else snackbars,
                    inPane = inPane, viewModel = routesModel,
                )
            }
            val latestRoutePlan by rememberUpdatedState(routePlan)
            val movableRoutePlan = remember { movableContentOf { inPane: Boolean -> latestRoutePlan(inPane) } }
            val head = when (current) {
                Panel.Pack -> PanelHead(openPack?.name ?: pack.name.ifEmpty { "Pack" }, pack.subtitle)
                Panel.Lz -> lzHead(if (tablet) lz.copy(page = LzPane.LIST.shows(lz.page) ?: LzPage.HOME) else lz) { diagrams.showPage(it) }
                Panel.Threats -> PanelHead(current.label, if (counts.threats == 0) "This device only" else "${counts.threats} on the map · this device only")
                Panel.Imports -> PanelHead(current.label, importsLine(imports.itemCount))
                Panel.Routes -> routesHead(routes)
            }
            // The Imports panel's Import, beside its title (the design's): the same menu as the frame's chip.
            val headerActions: @Composable RowScope.() -> Unit = {
                if (current == Panel.Imports) PillButton("Import", onClick = { importMenu = true }, icon = EzpzIcons.fileOpen)
                // The pack's own menu (the web's ⋯): a copy of it, for anyone in it.
                if (current == Panel.Pack) openPack?.takeIf { it.status != PackStatus.GONE }?.let { PackHeaderMenu(it.name, onDuplicate = workspace::askDuplicate) }
            }
            // The Routes panel's foot: Sketch a route and Import .msnx, pinned under the sets open now.
            // None in a read-only pack, which nothing is drawn into (a sketch still under way keeps its Cancel).
            val footer: (@Composable () -> Unit)? = if (current == Panel.Routes && routes.page == RoutesPage.HOME && !(routes.pack?.readOnly == true && routes.drawing == null)) {
                { RoutesFooterHost(onImport = importFiles, crosshair = state.center, viewModel = routesModel) }
            } else if (current == Panel.Pack && CopyRules.footShown(pack.loaded, openPack, canAdd = addingTo != null)) {
                // The Pack panel's foot while it is not finished: *Add from Library* for anyone who can change it (the Library, adding to the pack), *Finish pack*
                // for its owner.
                {
                    PackPanelFoot(
                        onFinish = if (openPack?.owner == true) workspace::askFinish else null, tablet = tablet,
                        onAddFromLibrary = if (addingTo != null) ({ libraryTab = libraryTabs.firstOrNull() ?: LibraryTab.LZ }) else null,
                    )
                }
            } else {
                null
            }
            if (tablet) {
                TabletFrame(
                    panels = panels,
                    current = current,
                    counts = counts,
                    onPick = { picked ->
                        focus.clearFocus()                                                   // a field in the panel left behind keeps no keyboard up
                        frameState.choose(picked)
                    },
                    snackbars = snackbars,
                    rail = TabletRail(
                        importMenu = { dismiss ->
                            DropdownMenu(
                                expanded = true, onDismissRequest = dismiss, shape = MaterialTheme.shapes.large,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(IMPORT_MENU_WIDTH),
                            ) {
                                ImportSheetContent(threatsOn, onChoose = { dismiss(); importFiles() })
                            }
                        },
                        onLibrary = {
                            focus.clearFocus()
                            libraryTab = libraryTabFor(current)
                        },
                        account = { account(false) },
                        workspace = openPack.let { pack ->
                            RailWorkspace(
                                // Only a tile that opens the switcher says it changes the workspace.
                                label = pack?.name ?: "Personal", description = if (packsHere) PackWords.railDescription(pack) else "Workspace: Personal", pack = pack != null,
                                finished = pack?.finished == true,
                                menu = if (packsHere) {
                                    { dismiss ->
                                        val switcher by workspace.switcher.collectAsStateWithLifecycle()
                                        LaunchedEffect(Unit) { workspace.switcherOpened() }
                                        DropdownMenu(
                                            expanded = true, onDismissRequest = dismiss, shape = MaterialTheme.shapes.large,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(SWITCHER_WIDTH),
                                        ) { SwitcherContent(switcher, actionsOf(workspace, dismiss, pickedPack, newPack::start, teams::open)) }
                                    }
                                } else {
                                    null
                                },
                            )
                        },
                    ),
                    state = tabletState,
                    head = head,
                    headerActions = headerActions,
                    floatingPx = { floating },
                    footer = footer,
                    supporting = supportingPanes(
                        routes, lz, crosshair = state.center, crosshairGrid = state.readout?.mgrs, canMakeAircraft = user.hasFeature("aircraft_profiles"), diagrams = diagrams,
                        routesModel = routesModel, routePlan = movableRoutePlan, onAddToPack = askCopy?.invoke(RecordKind.LZ), onSaveCopy = saveCopy,
                    ),
                    // The Library, Members and Teams draw the toasts over themselves, as dialogs: the frame's under their scrim would be a second copy.
                    toasts = libraryTab == null && !membersOpen && !teamsOpen,
                    band = openPack?.takeIf { it.finished }?.let { finished ->
                        { FinishedBand(finished, onDuplicate = workspace::askDuplicate, onReopen = workspace::askReopen, onSaveCopy = CopyRules.bannerCopy(lz.current?.uuid, saveCopy)) }
                    },
                    modifier = if (libraryTab != null) Modifier.clearAndSetSemantics {} else Modifier,
                    panel = { p -> movablePanels.getValue(p)() },
                    mapScreen = movableMap,
                )
            } else {
                PhoneFrame(
                    panels = panels,
                    current = current,
                    counts = counts,
                    onPick = { picked ->
                        val (panel, detent) = pick(current, sheet.settled, picked, closed = frameState.closed)
                        focus.clearFocus()                                                   // a field in the panel left behind keeps no keyboard up
                        frameState.choose(panel)
                        scope.launch { sheet.animateTo(detent) }
                    },
                    sheet = sheet,
                    snackbars = snackbars,
                    head = head,
                    headerActions = headerActions,
                    floatingPx = { floating },
                    footer = footer,
                    // Under the route's plan page the frame is still there (the map, the panels and what they listen for) but neither read nor touched.
                    modifier = if (routes.planOpen || libraryTab != null) Modifier.clearAndSetSemantics {} else Modifier,
                    // A tap on the map hides it; Back shows it again, unless a page over the frame has Back for itself.
                    hidden = frameState.hidden,
                    onShow = frameState::show,
                    backShows = !routes.planOpen && !overFrame,
                    // Its close button: the sheet goes down behind the bar until a panel is picked again.
                    closed = frameState.closed,
                    onClose = {
                        focus.clearFocus()
                        frameState.close()
                    },
                    panel = { p -> movablePanels.getValue(p)() },
                    mapScreen = movableMap,
                )
            }
            // The held route's plan (the design's RoutePlan): a page of its own over the frame, with the frame's toasts.
            if (!tablet) movableRoutePlan(false)
            // Finishing, reopening or duplicating the open pack, asked about wherever it was asked for; and a new pack.
            PackDialogsHost(workspace)
            if (packsHere) NewPackHost(newPack, tablet)
            if (packsHere) MembersHost(members, tablet, snackbars)
            if (packsHere) TeamsHost(teams, tablet, snackbars)
            // The Library (the design's): opening an item from it shuts it and shows the panel the item is in, as the web's does; renaming, deleting and settling a
            // conflict are that panel's own, so an item open now is renamed and closed where its unsaved edits are.
            LibraryHost(
                tab = libraryTab,
                tabs = libraryTabsFor(panels),
                actions = LibraryActions(
                    close = { libraryTab = null },
                    open = { row ->
                        val panel = when (row.tab) {
                            LibraryTab.LZ -> Panel.Lz.also {
                                diagrams.open(row.uuid)
                                if (!tablet) diagrams.showPage(LzPage.HOME)                     // a tablet's list is under the plan pane already
                            }
                            LibraryTab.ROUTES -> Panel.Routes.also { routesModel.openSet(row.uuid) }
                            LibraryTab.POINTS -> Panel.Imports.also { importsModel.show(row.uuid) }
                        }
                        libraryTab = null
                        if (panel in panels) {
                            frameState.choose(panel)
                            if (tablet) tabletState.showList() else if (sheet.settled == SheetDetent.Lowered) scope.launch { sheet.animateTo(SheetDetent.Half) }
                        }
                    },
                    rename = { row, name, replacing ->
                        when (row.tab) {
                            LibraryTab.LZ -> diagrams.rename(row.uuid, name, replacing)
                            LibraryTab.ROUTES -> routesModel.renameSet(row.uuid, name, replacing)
                            LibraryTab.POINTS -> importsModel.rename(row.uuid, name, replacing)
                        }
                    },
                    delete = { row ->
                        when (row.tab) {
                            LibraryTab.LZ -> diagrams.delete(row.uuid)
                            LibraryTab.ROUTES -> routesModel.deleteSet(row.uuid)
                            LibraryTab.POINTS -> importsModel.delete(row.uuid)
                        }
                    },
                    resolve = { row, how ->
                        when (row.tab) {
                            LibraryTab.LZ -> diagrams.resolve(row.uuid, how)
                            LibraryTab.ROUTES -> routesModel.resolve(row.uuid, how)
                            LibraryTab.POINTS -> importsModel.resolve(row.uuid, how)
                        }
                    },
                    addToPack = askCopy?.let { ask -> { row -> ask(CopyRules.recordKindOf(row.tab))(row.uuid, row.name) } },
                    addToOpenPack = { row -> copies.addToOpen(CopyRules.recordKindOf(row.tab), row.uuid, row.name) },
                ),
                snackbars = snackbars,
                large = tablet,
                addingTo = addingTo,
                adding = adding,
            )
            // Adding a copy to a pack, asked from the Library among others: drawn after it, so its window is the one on top. A window shown earlier stays under
            // one made later, so it is made again when the Library's window is (it opens, or turns from a page into a tablet's dialog).
            if (packsHere) key(tablet, libraryTab != null) { AddToPackHost(copies, tablet) }
            if (packsHere) ItemDialogsHost(itemCopies, tablet)
        }
    }
}

/**
 * A panel's toast. The Saved one ("Saved “NAME” to your Library", the design's Saved) offers *Open Library*, which opens the Library at [tab], when the account has
 * that tab ([tabs]); every other toast is said as it is.
 */
internal suspend fun SnackbarHostState.panelToast(text: String, tab: LibraryTab, tabs: List<LibraryTab>, openLibrary: (LibraryTab) -> Unit) {
    if (!offersLibrary(text) || tab !in tabs) {
        showSnackbar(text, withDismissAction = true)
        return
    }
    if (showSnackbar(text, actionLabel = "Open Library", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) openLibrary(tab)
}

/** Everyone else's pointers in the open pack, collected where they are drawn: they move many times a second, and nothing else is built again as they do. */
@Composable
private fun PackPointers(host: app.ezpztac.map.MapHost, home: HomeViewModel) {
    val pointers by home.pointers.collectAsStateWithLifecycle()
    PointersLayer(host, pointers)
}

/** Why *Add object* is off, in its menu's words: the open LZ/PZ is a read-only pack's ([locked]), or not analysed; null when it can place ([canEdit]). */
internal fun addObjectOff(canEdit: Boolean, locked: String?): String? = when {
    canEdit -> null
    locked != null -> locked
    else -> "Analyze the LZ/PZ first: its objects go in its landing zone"
}

/** Puts [kind] at [at] on the open LZ/PZ and holds it, as the LZ/PZ tools' *Add here* does; a unit is a friendly infantry one, changed from its options. */
internal fun placeObject(graphics: GraphicsViewModel, kind: MapObject, at: LatLon) {
    when (kind) {
        MapObject.HELICOPTER -> graphics.place(GraphicKind.HELICOPTER, at)
        MapObject.PZ_MARKER -> graphics.place(GraphicKind.PZ_MARKER, at)
        MapObject.SECTOR_OF_FIRE -> graphics.place(GraphicKind.SECTOR_OF_FIRE, at)
        MapObject.GO_AROUND_LEFT -> graphics.place(GraphicKind.GO_AROUND, at, direction = "left")
        MapObject.GO_AROUND_RIGHT -> graphics.place(GraphicKind.GO_AROUND, at, direction = "right")
        MapObject.UNIT -> graphics.addUnit(UnitDraft(), at)
    }
}

/** The map's menu as it was opened: where (its grid, and the press in the map's pixels), and what *Add route point here* would do then. */
private data class OpenMapMenu(val place: MapMenuPlace, val at: IntOffset, val routePoint: MapPointAction)

/**
 * The tablet's supporting panes, the last the one that stands: the open LZ/PZ's plan, aircraft, analysis and tools while its plan has not been put away (`LzPage.PLAN`:
 * on a tablet the plan page is this pane), beside whichever panel shows, as the design keeps it; and the held route's plan while it is open ([routePlan], its own bar
 * heads it). Both stay composed while there, so what was being done in one is there when it comes back.
 */
private fun supportingPanes(
    routes: RoutesUiState, lz: DiagramsUiState, crosshair: LatLon?, crosshairGrid: String?, canMakeAircraft: Boolean, diagrams: DiagramsViewModel,
    routesModel: RoutesViewModel, routePlan: @Composable (Boolean) -> Unit,
    /** Asks which Mission Pack a copy of the open LZ/PZ goes into, as the phone's plan page does. */
    onAddToPack: ((uuid: String, name: String) -> Unit)? = null,
    /** Asks what to call a copy of the pack's LZ/PZ open, saved to the Library, as the phone's plan page does. */
    onSaveCopy: ((uuid: String) -> Unit)? = null,
): List<SupportingPane> {
    val open = lz.current
    val lzPane = if (lz.page == LzPage.PLAN && open != null) {
        SupportingPane(
            key = "lz:${open.uuid}", title = open.name, closeLabel = "Close the ${open.name} pane",
            // The design's "Read-only · OP DK is finished" in a read-only pack.
            subtitle = open.inPack?.lock?.pane ?: "Aircraft, analysis and tools",
            onClose = { diagrams.showPage(LzPage.HOME) }, request = lz.planAsked,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
            ) {
                LzPanelHost(
                    crosshair = crosshair, crosshairGrid = crosshairGrid, canMakeAircraft = canMakeAircraft, pane = LzPane.PLAN, viewModel = diagrams, onAddToPack = onAddToPack,
                    onSaveCopy = onSaveCopy,
                )
                Spacer(Modifier.height(Tokens.Spacing.lg.dp))
            }
        }
    } else {
        null
    }
    val routePane = if (routes.planOpen) {
        SupportingPane(key = "route:${routes.detail?.routeId}", title = null, onClose = routesModel::closePlan, request = routes.planAsked) { routePlan(true) }
    } else {
        null
    }
    return listOfNotNull(lzPane, routePane)
}

/** The tablet's workspace switcher, a popup at the rail's tile (the design's). */
private val SWITCHER_WIDTH = 400.dp

/** The tablet's Import menu, at the rail's tile (the design's). */
private val IMPORT_MENU_WIDTH = 360.dp

/** The night palette's darkened map: black over the imagery, the drawn layers and their labels. Nothing in it takes a touch. */
@Composable
private fun NightDim() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = NIGHT_DIM)))
}

/** How much the night palette darkens the map: enough that satellite imagery does not light a cockpit, not so much that it cannot be read. */
private const val NIGHT_DIM = 0.45f

/** The LZ/PZ panel's header for the page it is on: its open LZ/PZs; an LZ/PZ's plan, with its grid; the aircraft. [go] moves between them. */
private fun lzHead(lz: DiagramsUiState, go: (LzPage) -> Unit): PanelHead = when (lz.page) {
    LzPage.HOME -> PanelHead("LZ/PZ", lz.pack?.head ?: if (lz.openCount == 0) "Nothing open" else "${lz.openCount} open · this session")
    LzPage.PLAN -> PanelHead(
        lz.current?.name ?: "LZ/PZ", lz.current?.grid ?: "No target yet", subtitleMono = lz.current?.grid != null,
        back = { go(LzPage.HOME) }, backDescription = if (lz.openCount == 1) "Back to the open LZ/PZ" else "Back to the open LZ/PZs, ${lz.openCount} open",
    )
    LzPage.AIRCRAFT -> PanelHead("Aircraft", "The mission aircraft and your own", back = { go(lzBack(lz)) }, backDescription = "Back")
}

/** The Routes panel's header: the sets open now. */
private fun routesHead(routes: RoutesUiState): PanelHead = when (routes.page) {
    RoutesPage.HOME -> PanelHead("Routes", routes.pack?.head ?: when (routes.openCount) { 0 -> "Nothing open"; 1 -> "1 route set in this session"; else -> "${routes.openCount} route sets in this session" })
}

/** The Library tab a panel's items are on, which the Library chip opens at: the panel's own, the LZ/PZs otherwise. */
internal fun libraryTabFor(panel: Panel): LibraryTab = when (panel) {
    Panel.Routes -> LibraryTab.ROUTES
    Panel.Imports -> LibraryTab.POINTS
    else -> LibraryTab.LZ
}

/** The Library's tabs an account has: those of the panels it is offered (an administrator can turn routes off, as on the web). */
internal fun libraryTabsFor(panels: List<Panel>): List<LibraryTab> = listOfNotNull(
    LibraryTab.LZ.takeIf { Panel.Lz in panels },
    LibraryTab.ROUTES.takeIf { Panel.Routes in panels },
    LibraryTab.POINTS.takeIf { Panel.Imports in panels },
)

/** Where Back goes from the LZ/PZ panel's page: the aircraft back to the plan they were opened from, the rest to the open LZ/PZs. */
private fun lzBack(lz: DiagramsUiState): LzPage = if (lz.page == LzPage.AIRCRAFT && lz.current != null) LzPage.PLAN else LzPage.HOME
