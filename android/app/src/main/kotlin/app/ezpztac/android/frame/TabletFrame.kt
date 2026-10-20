package app.ezpztac.android.frame

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelAction
import app.ezpztac.designsystem.PanelHeader
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.MapChrome
import app.ezpztac.map.MapPadding
import kotlin.math.roundToInt

/**
 * What the tablet's rail holds besides the panels: Import (a menu at the tile, [importMenu], drawn while open with what closes it), the Library and the account. The
 * workspace is the person's own until the switcher (P1).
 */
@Stable
class TabletRail(
    val importMenu: @Composable (onDismiss: () -> Unit) -> Unit,
    val onLibrary: () -> Unit,
    val account: @Composable () -> Unit,
    /** The workspace tile: the Library's, or the open pack's in its colour, with the switcher at it when packs run here. */
    val workspace: RailWorkspace = RailWorkspace(),
)

/** What the rail's workspace tile says, and the switcher it opens ([menu], drawn while open with what closes it; none: a label, not a button). */
@Stable
class RailWorkspace(
    val label: String = "Personal",
    val description: String = "Workspace: Personal",
    val pack: Boolean = false,
    val menu: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    /** The pack is finished: the tile wears a lock (the design's AT Finished). */
    val finished: Boolean = false,
)

/**
 * A pane on the right of the map (the design's supporting pane, 320 dp): the open LZ/PZ's plan, or a route's. [title] and [subtitle] head it with a close button
 * ([closeLabel]); with no [title] the content has a bar of its own. [onClose] puts it away (the close button, and Back while it stands). [request] counts the times it
 * has been asked for: asked again, it comes forward even where it waits behind the panel's list.
 */
@Stable
class SupportingPane(
    val key: String,
    val title: String?,
    val subtitle: String? = null,
    val closeLabel: String = "Close",
    val onClose: () -> Unit = {},
    val request: Int = 0,
    val content: @Composable () -> Unit,
)

/** Which of the tablet's panes stand beside the map. */
data class TabletPanes(val list: Boolean, val support: Boolean)

/**
 * The panes beside the map in a window [width] wide: the panel's list unless [collapsed], and the supporting pane when there is one ([hasSupport]). When both and the
 * map's [MAP_MIN] do not fit, the one opened last ([listLast]) stands and the other waits, composed, until it is opened again.
 */
fun tabletPanes(width: Dp, collapsed: Boolean, hasSupport: Boolean, listLast: Boolean): TabletPanes {
    val list = !collapsed
    if (!list || !hasSupport) return TabletPanes(list, hasSupport)
    if (width - RAIL_WIDTH - LIST_WIDTH - SUPPORT_WIDTH >= MAP_MIN) return TabletPanes(list = true, support = true)
    return if (listLast) TabletPanes(list = true, support = false) else TabletPanes(list = false, support = true)
}

/**
 * Whether a window [width] by [height] has the tablet's frame: Material's expanded width, and the height its rail needs (Material's medium height and up). A phone on
 * its side is wide enough and not tall enough: it keeps the phone's frame, its sheet and its bar.
 */
fun usesTabletFrame(width: Dp, height: Dp): Boolean = width >= TABLET_MIN && height >= TABLET_MIN_HEIGHT

/**
 * What the tablet's frame remembers about its panes: whether the panel's list is collapsed, which of the list and the supporting pane was opened last (the one that
 * stands where both do not fit), and what it has seen, so a pane opened again comes forward. Held by the screen, so what the person does elsewhere (a held object's
 * options, the Library's *Open*, the aircraft page) brings the list forward as the phone raises its sheet.
 */
@Stable
class TabletFrameState internal constructor(collapsed: Boolean = false, listLast: Boolean = true, private var lastPanel: String? = null, private var lastSupport: String? = null, private var lastRequest: Int = 0) {
    var collapsed by mutableStateOf(collapsed)
        private set
    var listLast by mutableStateOf(listLast)
        private set

    /** Whether the panel's list stands now (not collapsed, nor waiting out of sight behind a supporting pane where both do not fit). */
    var listShown by mutableStateOf(!collapsed)
        internal set

    /** The panel's list is wanted (a panel picked, or something done that shows in it): it opens and stands. */
    fun showList() {
        collapsed = false
        listLast = true
    }

    fun collapse() {
        collapsed = true
    }

    /** The panel showing is [panel]: a new one brings the list forward. */
    fun panelShown(panel: Panel) {
        if (panel.name == lastPanel) return
        lastPanel = panel.name
        showList()
    }

    /**
     * The supporting pane standing is [key], asked for [request] times: a new one, or one asked for again, stands. One that comes back after going (the same LZ/PZ's
     * plan when its panel is picked again) does not take the place of the list the person picked.
     */
    fun supportShown(key: String?, request: Int) {
        if (key == null) return
        if (key != lastSupport || request != lastRequest) listLast = false
        lastSupport = key
        lastRequest = request
    }

    fun panes(width: Dp, hasSupport: Boolean): TabletPanes = tabletPanes(width, collapsed, hasSupport, listLast)

    internal companion object {
        val Saver = listSaver<TabletFrameState, Any?>(
            save = { listOf(it.collapsed, it.listLast, it.lastPanel, it.lastSupport, it.lastRequest) },
            restore = { TabletFrameState(it[0] as Boolean, it[1] as Boolean, it[2] as String?, it[3] as String?, it[4] as Int) },
        )
    }
}

@Composable
fun rememberTabletFrameState(): TabletFrameState = rememberSaveable(saver = TabletFrameState.Saver) { TabletFrameState() }

/**
 * The tablet's frame (docs/native-design, the AT screens; a window that [usesTabletFrame]): the rail (Import, the panels with their counts, the Library and the
 * account), the panel showing in a pane beside the map ([LIST_WIDTH], which collapses), the map, and the supporting panes on its right ([SUPPORT_WIDTH]): every one
 * composed, the last of [supporting] the one that stands. The toasts stand over the map, above its readout. As on the phone, every panel stays composed and only
 * [current] is laid out.
 */
@Composable
fun TabletFrame(
    panels: List<Panel>,
    current: Panel,
    counts: PanelCounts,
    onPick: (Panel) -> Unit,
    snackbars: SnackbarHostState,
    rail: TabletRail,
    panel: @Composable (Panel) -> Unit,
    modifier: Modifier = Modifier,
    state: TabletFrameState = rememberTabletFrameState(),
    head: PanelHead = PanelHead(current.label),
    headerActions: @Composable RowScope.() -> Unit = {},
    /** How tall whatever floats above the readout is now, in pixels: the toasts go above it. */
    floatingPx: () -> Int = { 0 },
    /** What is pinned under the panel showing (the Routes panel's *Sketch a route*), or null for nothing. */
    footer: (@Composable () -> Unit)? = null,
    supporting: List<SupportingPane> = emptyList(),
    /** Whether the toasts are drawn over the map (not while something drawn over the frame draws them itself). */
    toasts: Boolean = true,
    /** Across the top beside the rail, over the panes and the map (a finished pack's, the design's AT Finished): it takes the window's top inset itself. */
    band: (@Composable () -> Unit)? = null,
    /**
     * Whether Back puts away the supporting pane that stands: not while a page is drawn over the frame, whose own Back comes first. The frame is made again
     * when the window crosses into a tablet's (a turn, a foldable opened), and a handler made later is asked first.
     */
    backEnabled: Boolean = true,
    mapScreen: @Composable (MapFrame) -> Unit,
) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val standing = supporting.lastOrNull()
    LaunchedEffect(current) { state.panelShown(current) }
    LaunchedEffect(standing?.key, standing?.request) { state.supportShown(standing?.key, standing?.request ?: 0) }
    // Under a band the panes and the map start below the window's top inset, which the band has taken.
    val top = if (band != null) 0.dp else WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var mapHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panes = state.panes(maxWidth, standing != null)
        val bandMax = maxHeight * BAND_MAX_SHARE
        // A field left focused in a list that goes out of sight (collapsed, or waiting behind a supporting pane) keeps no keyboard up and takes nothing typed.
        LaunchedEffect(panes.list) {
            state.listShown = panes.list
            if (!panes.list) focus.clearFocus()
        }
        // Back puts away the supporting pane that stands, as its close button does; one waiting out of sight is not taken away unseen.
        BackHandler(enabled = backEnabled && standing != null && panes.support) { standing?.onClose?.invoke() }
        Row(Modifier.fillMaxSize()) {
            Rail(panels, current, counts, rail = rail, onPick = { picked ->
                state.showList()
                onPick(picked)
            })
            Column(Modifier.weight(1f).fillMaxHeight()) {
            if (band != null) {
                Box(Modifier.heightIn(max = bandMax).verticalScroll(rememberScrollState())) { band() }
            }
            Row(Modifier.weight(1f).fillMaxWidth().then(if (band != null) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier)) {
            // The panel's list: always composed (its scroll, its pickers, what it listens for), laid out only while it stands. A surface, so what is drawn on it
            // takes the palette's colour for text, as in the phone's sheet.
            Surface(
                Modifier.then(if (panes.list) Modifier.width(LIST_WIDTH).fillMaxHeight() else Modifier.hidden()).edge(end = true, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))) {
                    PanelHeader(head.title, Modifier.heightIn(min = 64.dp), subtitle = head.subtitle, subtitleMono = head.subtitleMono, onBack = head.back, backDescription = head.backDescription) {
                        headerActions()
                        PanelAction(EzpzIcons.leftPanelClose, "Collapse the ${current.label} panel", onClick = { state.collapse() })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                        panels.forEach { each ->
                            key(each) {
                                Box(Modifier.fillMaxSize().then(if (each == current) Modifier else Modifier.hidden())) { panel(each) }
                            }
                        }
                    }
                    footer?.invoke()
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { mapHeight = it.height }) {
                val bottomPx = with(density) { bottomInset.toPx() }
                val frame = MapFrame(
                    padding = MapPadding(top = top + MapChrome.SEARCH_BOTTOM, bottom = bottomInset),
                    controlsBottom = { if (mapHeight > 0) mapHeight - bottomPx else null },
                )
                mapScreen(frame)
                if (toasts) {
                    ToastHost(
                        snackbars,
                        Modifier.fillMaxSize().padding(horizontal = Tokens.Spacing.lg.dp).aboveTheReadout(floatingPx) { bottomPx.roundToInt() },
                    )
                }
            }
            // Every supporting pane stays composed (what was typed in it, its scroll), and only the one that stands is laid out.
            supporting.forEach { pane ->
                key(pane.key) {
                    val shown = pane === standing && panes.support
                    Surface(
                        Modifier.then(if (shown) Modifier.width(SUPPORT_WIDTH).fillMaxHeight() else Modifier.hidden()).edge(end = false, MaterialTheme.colorScheme.outlineVariant)
                            .then(if (pane.title != null) Modifier.semantics { paneTitle = pane.title } else Modifier),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))) {
                            if (pane.title != null) {
                                PanelHeader(
                                    pane.title, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).heightIn(min = 64.dp), subtitle = pane.subtitle,
                                ) {
                                    PanelAction(EzpzIcons.close, pane.closeLabel, onClick = pane.onClose)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) { pane.content() }
                        }
                    }
                }
            }
            }
            }
        }
    }
}

/**
 * The rail (the design's, 96 dp): the workspace and Import at the top, the panels with their counts, the Library and the account at the foot. It scrolls where the
 * window is not tall enough for all of it, so the Library and the account are never pushed off the screen.
 */
@Composable
private fun Rail(panels: List<Panel>, current: Panel, counts: PanelCounts, rail: TabletRail, onPick: (Panel) -> Unit) {
    Surface(Modifier.width(RAIL_WIDTH).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.md.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The workspace: the person's own, or the open pack's in its colour; the switcher at it while packs run here.
                    val workspace = rail.workspace
                    var switching by remember { mutableStateOf(false) }
                    // The switcher goes with packs: when they come back it is not open by itself.
                    val switchable = workspace.menu != null
                    LaunchedEffect(switchable) { if (!switchable) switching = false }
                    Box {
                        RailTile(
                            if (workspace.pack) EzpzIcons.groupsFill else EzpzIcons.personFill, workspace.label, workspace.description, tonal = false,
                            onClick = workspace.menu?.let { { switching = true } }, pack = workspace.pack, locked = workspace.finished,
                        )
                        if (switching) workspace.menu?.invoke { switching = false }
                    }
                    Spacer(Modifier.height(Tokens.Spacing.lg.dp))
                    var importing by remember { mutableStateOf(false) }
                    Box {
                        RailTile(EzpzIcons.fileOpen, "Import", "Import a mission, local points or threats", tonal = true, onClick = { importing = true })
                        if (importing) rail.importMenu { importing = false }
                    }
                    Spacer(Modifier.height(Tokens.Spacing.xl.dp))
                    Column(Modifier.selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                        panels.forEach { panel ->
                            val selected = panel == current
                            val spoken = spokenCount(panel, counts)
                            // The Pack tab names its pack, and has a glyph of its own beside the workspace tile's (the design's AT screens).
                            val pack = panel == Panel.Pack
                            val name = if (pack && rail.workspace.pack) "${panel.label}, ${rail.workspace.label}" else panel.label
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onPick(panel) },
                                modifier = if (spoken != null || name != panel.label) Modifier.semantics { contentDescription = listOfNotNull(name, spoken).joinToString(", ") } else Modifier,
                                icon = {
                                    BadgedBox(badge = { PanelBadge(panel, counts) }) {
                                        val icon = when {
                                            pack -> if (selected) EzpzIcons.inventory2Fill else EzpzIcons.inventory2
                                            selected -> panel.selectedIcon
                                            else -> panel.icon
                                        }
                                        Icon(painterResource(icon), contentDescription = null)
                                    }
                                },
                                label = { Text(panel.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
                            )
                        }
                    }
                }
                Column(Modifier.padding(top = Tokens.Spacing.lg.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The Library is a page, not one of the panels: a button, not a tab.
                    RailButton(EzpzIcons.collectionsBookmark, "Library", onClick = rail.onLibrary)
                    Spacer(Modifier.height(Tokens.Spacing.md.dp))
                    rail.account()
                }
            }
        }
    }
}

/**
 * A square tile at the top of the rail (the design's workspace button and Import), with its word under it: one button for a screen reader, named by [description]. With
 * no [onClick] it is a label, not a button.
 */
@Composable
private fun RailTile(icon: Int, label: String, description: String, tonal: Boolean, onClick: (() -> Unit)?, pack: Boolean = false, locked: Boolean = false) {
    val color = when {
        pack -> MaterialTheme.colorScheme.tertiaryContainer
        tonal -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val tint = when {
        pack -> MaterialTheme.colorScheme.onTertiaryContainer
        tonal -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> EzpzTheme.status.accent
    }
    val border = if (tonal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        Modifier.width(80.dp).then(click).semantics(mergeDescendants = true) { contentDescription = description }.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Surface(shape = MaterialTheme.shapes.large, color = color, border = border, shadowElevation = if (tonal) 1.dp else 0.dp) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, tint = tint) }
            }
            // A finished pack's lock, on the tile's corner (the design's): a 22 dp disc with a 14 dp lock, its edge 5 dp past the tile's, ringed outside in the
            // rail's colour so it stands off the tile.
            if (locked) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(7.dp, 7.dp).size(26.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(EzpzIcons.lockFill), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A button in the rail drawn as its destinations are (an icon over its word), for what is not one of them: the Library. */
@Composable
private fun RailButton(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(80.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = Tokens.Spacing.xs.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A hairline on one side of a pane, as the design separates the panes from the map: on the [end] side, or the start. Drawn over the pane's own background. */
private fun Modifier.edge(end: Boolean, color: Color): Modifier = drawWithContent {
    drawContent()
    val x = if (end) size.width - 0.5f else 0.5f
    drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
}

/** Places the toasts above the map's readout and whatever floats above it, the readout standing [bottomPx] above the foot of the map. */
private fun Modifier.aboveTheReadout(floatingPx: () -> Int, bottomPx: () -> Int): Modifier = layout { measurable, constraints ->
    val toast = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val gap = MapChrome.PILL_GAP.toPx()
        val floating = floatingPx()
        val bottom = constraints.maxHeight - bottomPx() - gap - PILL_HEIGHT.toPx() - gap - (if (floating > 0) floating + gap else 0f)
        toast.placeRelative((constraints.maxWidth - toast.width) / 2, (bottom - toast.height).roundToInt().coerceAtLeast(0))
    }
}

private val PILL_HEIGHT = 36.dp

/** The window width from which the tablet's frame is drawn (Material's expanded width); the medium width takes the phone's. */
val TABLET_MIN: Dp = 840.dp

/** The window height the tablet's frame needs as well (Material's medium height): its rail holds what the phone's search bar and chips do. */
val TABLET_MIN_HEIGHT: Dp = 480.dp
internal val RAIL_WIDTH: Dp = 96.dp
/** The most of the window's height a band across the top may take: the panes and the map keep the rest. */
private const val BAND_MAX_SHARE = 0.4f

internal val LIST_WIDTH: Dp = 344.dp
internal val SUPPORT_WIDTH: Dp = 320.dp

/** The narrowest the map is left beside the panes. */
internal val MAP_MIN: Dp = 400.dp
