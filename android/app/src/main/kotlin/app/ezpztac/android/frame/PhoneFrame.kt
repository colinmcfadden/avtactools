package app.ezpztac.android.frame

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PanelAction
import app.ezpztac.designsystem.PanelHeader
import app.ezpztac.designsystem.PanelSheet
import app.ezpztac.designsystem.PanelSheetDefaults
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.keptAway
import app.ezpztac.map.FrameHiding
import app.ezpztac.map.MapChrome
import app.ezpztac.map.MapPadding
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the frame tells the map screen it draws: the camera's [padding] (the crosshair marks the middle of what it leaves), where the readout pill and the
 * floating tools stand ([controlsBottom]: the sheet's edge, never higher than half, so a sheet raised further slides over them; the foot of the map on a tablet,
 * and on a phone whose frame is hidden), and how far the phone's frame has gone ([hiding]: the map screen's row goes with it).
 */
@Stable
class MapFrame internal constructor(
    val padding: MapPadding,
    val controlsBottom: () -> Float?,
    val hiding: FrameHiding = FrameHiding.SHOWN,
)

/**
 * The top of the panel showing: its [title] and [subtitle] ([subtitleMono] for a grid), and, on a page inside the panel (an LZ/PZ's plan), [back] with
 * what it goes back to in [backDescription].
 */
data class PanelHead(
    val title: String,
    val subtitle: String? = null,
    val subtitleMono: Boolean = false,
    val back: (() -> Unit)? = null,
    val backDescription: String = "Back",
)

/** The bar of panels is Material's navigation bar, 80 dp above the system's own buttons (until it has been measured: its words grow with the person's text). */
private val BAR_HEIGHT = 80.dp

/**
 * The phone's frame (docs/native-design, the AP screens): the map and its controls over the whole screen, the panel over them in a sheet that rests lowered, at
 * half or at full height, the bar of panels along the bottom, and the toasts. [mapScreen] draws the map and its controls as [MapFrame] says.
 *
 * Every panel stays composed and only [current] is laid out: each keeps its scroll, its file pickers and what it listens for (an export being
 * built goes to the share sheet whichever panel shows; a threat's form opened from the held bar over another panel).
 *
 * [hidden] (the owner's decision of 2026-10-13: a tap on the map hides it, `FrameState`): the row along the top slides up off the screen and the sheet and the bar
 * down (fading as they go, over the sheet's own time, which the map's padding eases over too), and while hidden none of them is drawn, touched or read by a screen
 * reader. They stay composed and come back as they were, the sheet at its height. The readout pill, the map's buttons, the crosshair, the toasts and what floats
 * above the pill stay, the pill and what floats above it riding down to the foot of the screen; the crosshair goes to the middle of the whole map, the camera's
 * padding being just the system's bars. Hidden, Back shows the frame ([onShow]) before anything else is asked, unless [backShows] is false (a page over the frame
 * that has Back for itself), and nothing in the frame keeps the keyboard.
 *
 * [closed] (the header's close button, [onClose]): the sheet slides down behind the bar of panels and is neither drawn, touched nor read there, kept composed as
 * when hidden; the bar stays, and the map's middle, the readout and the toasts go down to the bar's top. Picking a panel on the bar brings it back (`pick`).
 */
@Composable
fun PhoneFrame(
    panels: List<Panel>,
    current: Panel,
    counts: PanelCounts,
    onPick: (Panel) -> Unit,
    sheet: PanelSheetState,
    snackbars: SnackbarHostState,
    panel: @Composable (Panel) -> Unit,
    modifier: Modifier = Modifier,
    head: PanelHead = PanelHead(current.label),
    headerActions: @Composable RowScope.() -> Unit = {},
    /** How tall whatever floats above the readout is now, in pixels: the toasts go above it. */
    floatingPx: () -> Int = { 0 },
    /** What is pinned under the panel showing (the Routes panel's *Sketch a route*), or null for nothing. */
    footer: (@Composable () -> Unit)? = null,
    hidden: Boolean = false,
    onShow: () -> Unit = {},
    backShows: Boolean = true,
    closed: Boolean = false,
    onClose: () -> Unit = {},
    mapScreen: @Composable (MapFrame) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // How tall the pinned footer is (none: 0), in pixels: at full height the toasts go above it, not over its buttons.
    var footerPx by remember { mutableIntStateOf(0) }
    if (footer == null) footerPx = 0
    // How tall the bar is drawn, and the whole frame: the sheet's space is what the bar leaves, and a hidden frame's controls stand at the foot of the whole.
    var barPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bar: Dp = if (barPx > 0) with(density) { barPx.toDp() } else BAR_HEIGHT + navigation
    // 0 shown, 1 hidden: read as the frame is drawn and laid out, never as it is composed, so a frame of the slide is not a composition.
    val hiding = animateFloatAsState(if (hidden) 1f else 0f, PanelSheetDefaults.Motion, label = "frame hidden")
    val gone by remember { derivedStateOf { hiding.value >= 1f } }
    val away = hidden && gone
    // 0 open, 1 closed: the sheet going down behind the bar, read as it is drawn and laid out, as the frame's hiding is.
    val closing = animateFloatAsState(if (closed) 1f else 0f, PanelSheetDefaults.Motion, label = "panel closed")
    val shut by remember { derivedStateOf { closing.value >= 1f } }
    // The sheet's edge as the panel closes: from where it rests down to the bar's top (the foot of its space).
    val sheetEdge: () -> Float? = { edgeOf(sheet)?.let { edge -> closedEdge(edge, sheet.anchors?.spacePx, closing.value) } }
    // Hidden or closed, nothing in the frame keeps the keyboard up or takes what is typed.
    LaunchedEffect(hidden, closed) { if (hidden || closed) focus.clearFocus() }
    // Composed as the frame hides, so it is the newest Back and is asked before every other one.
    if (hidden) BackHandler(enabled = backShows, onBack = onShow)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onSizeChanged { heightPx = it.height }) {
        // The map's middle follows the height the sheet rests at (or is settling at), and the bar under it; above half the sheet slides over it instead. Hidden,
        // it is the middle of the whole map but the system's bars.
        val anchors = sheet.anchors
        val resting = sheet.heading.let { if (it == SheetDetent.Full) SheetDetent.Half else it }
        val sheetHeight: Dp = anchors?.let { with(density) { it.heightOf(resting).toDp() } } ?: PanelSheetDefaults.LoweredHeight
        val padding = when {
            hidden -> MapPadding(top = top, bottom = navigation)
            closed -> MapPadding(top = top + MapChrome.ROW_BOTTOM, bottom = bar)
            else -> MapPadding(top = top + MapChrome.ROW_BOTTOM, bottom = sheetHeight + bar)
        }
        val footPx = { heightPx - with(density) { navigation.toPx() } }
        val frame = MapFrame(
            padding = padding,
            controlsBottom = { controlsEdge(sheetEdge(), hiding.value, footPx(), heightPx > 0) },
            hiding = FrameHiding(hidden, away) { hiding.value },
        )
        // The map over the whole screen, under the sheet and the bar, so they slide away over it and it is never resized as they go. Drawn here, not by the
        // map screen, so the panels are never inside the map's content: the map and the panels are each moved between the phone's and the tablet's frames whole
        // (MapHome), and one moved inside another breaks Compose. The sheet stops below the row at its full height, so the row is always seen.
        mapScreen(frame)
        Column(Modifier.fillMaxSize()) {
            // The keyboard rises over the bar too: what is consumed here is not padded again by the sheet's content.
            Box(
                Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(PaddingValues(bottom = bar))
                    .keptAway(hidden, away)
                    // Closed: never read; once behind the bar not laid out either, though its room is kept, so the bar stays at the foot.
                    .then(if (closed) Modifier.clearAndSetSemantics {}.then(if (shut) KeepsRoomEmpty else Modifier) else Modifier)
                    // Down off the screen, its edge going to the screen's foot, and fading; closed, down behind the bar (drawn after it, over it).
                    .graphicsLayer {
                        val slid = hiding.value
                        val edge = sheet.offsetPx.takeUnless { it.isNaN() } ?: size.height
                        translationY = maxOf(slid * (size.height + barPx - edge), closing.value * (size.height - edge)).coerceAtLeast(0f)
                        alpha = 1f - slid
                    },
            ) {
                PanelSheet(
                    state = sheet,
                    topLimit = top + MapChrome.SHEET_TOP_LIMIT,
                    raiseLabel = "Raise the ${current.label} panel",
                    lowerLabel = "Lower the ${current.label} panel",
                    inert = hidden || closed,
                    header = {
                        PanelHeader(head.title, subtitle = head.subtitle, subtitleMono = head.subtitleMono, onBack = head.back, backDescription = head.backDescription) {
                            headerActions()
                            val full = sheet.settled == SheetDetent.Full
                            PanelAction(
                                if (full) EzpzIcons.expandMore else EzpzIcons.expandLess,
                                if (full) "Lower the ${current.label} panel" else "Open the ${current.label} panel full height",
                                onClick = { scope.launch { sheet.animateTo(sheet.fullOrHalf()) } },
                            )
                            PanelAction(EzpzIcons.close, "Close the ${current.label} panel", onClick = onClose)
                        }
                    },
                    footer = footer?.let { foot -> { Box(Modifier.onSizeChanged { footerPx = it.height }) { foot() } } },
                ) {
                    Box(Modifier.fillMaxSize().imePadding()) {
                        panels.forEach { each ->
                            key(each) {
                                Box(Modifier.fillMaxSize().then(if (each == current) Modifier else Modifier.hidden())) { panel(each) }
                            }
                        }
                    }
                }
            }
            PanelBar(
                panels, current, counts, onPick,
                Modifier.keptAway(hidden, away)
                    .graphicsLayer {
                        val slid = hiding.value
                        translationY = slid * size.height
                        alpha = 1f - slid
                    }
                    // Not once it is away (nothing measured): the sheet's space and the map's padding keep the bar's height for its return.
                    .onSizeChanged { if (it.height > 0) barPx = it.height },
            )
        }
        ToastHost(
            snackbars,
            Modifier.fillMaxSize().padding(horizontal = Tokens.Spacing.lg.dp)
                .aboveTheControls(
                    edge = sheetEdge, atFull = { sheet.heading == SheetDetent.Full && closing.value <= 0f }, hiding = { hiding.value }, barPx = { barPx },
                    footPx = { footPx().takeIf { heightPx > 0 } }, floatingPx = floatingPx,
                ) { if (closed) 0 else footerPx },
        )
    }
}

/**
 * Where the readout and the floating tools stand: the sheet's edge, but no higher on the screen than its half height (a larger y), so a sheet
 * raised further slides over them. Null before it is laid out.
 */
internal fun edgeOf(sheet: PanelSheetState): Float? {
    val anchors = sheet.anchors ?: return null
    val edge = sheet.offsetPx
    return if (edge.isNaN()) null else max(edge, anchors.halfPx)
}

/**
 * Where the sheet's edge ([edge], from [edgeOf]) is drawn as the panel closes ([closing], 0 open to 1 closed): down to the foot of its space ([spacePx], the bar's top),
 * where it is gone behind the bar. Before the sheet has been laid out ([spacePx] null) where it rests.
 */
internal fun closedEdge(edge: Float, spacePx: Float?, closing: Float): Float =
    if (spacePx == null || closing <= 0f) edge else edge + (spacePx - edge) * closing.coerceAtMost(1f)

/**
 * Where the readout and the floating tools stand as the frame hides ([hiding], 0 shown to 1 hidden): from the sheet's edge ([sheetEdge], [edgeOf]) down to the foot
 * of the screen ([footPx], above the system's buttons), with the sheet as it goes. Before the frame is laid out ([measured] false) the sheet's edge, as before.
 */
internal fun controlsEdge(sheetEdge: Float?, hiding: Float, footPx: Float, measured: Boolean): Float? = when {
    hiding <= 0f || !measured -> sheetEdge
    sheetEdge == null -> footPx
    else -> sheetEdge + (footPx - sheetEdge) * hiding
}

/**
 * Kept in composition (its scroll, its pickers, what it listens for, a dialog it opens) but neither measured nor placed, and out of the
 * semantics: not drawn, not touched, not read by a screen reader.
 */
internal fun Modifier.hidden(): Modifier = clearAndSetSemantics {}.layout { _, _ -> layout(0, 0) {} }

/** Takes all the room it is given and lays nothing out in it (what it holds stays composed): the closed sheet's space, which the bar stays under. */
private val KeepsRoomEmpty: Modifier = Modifier.layout { _, constraints -> layout(constraints.maxWidth, constraints.maxHeight) {} }

/**
 * Places the toasts above the readout pill and whatever floats above it while the sheet is at half or lower, and at the foot of the sheet's space when it is at
 * full height ([atFull]: the map is covered then), above the panel's pinned footer ([footerPx]) if it has one. As the frame hides ([hiding]) they go to the foot of the
 * screen ([footPx]) with the pill. [edge] is the sheet's edge as drawn (down to the bar as the panel closes). Read when placing, so they ride on the sheet as it moves.
 */
private fun Modifier.aboveTheControls(
    edge: () -> Float?,
    atFull: () -> Boolean,
    hiding: () -> Float,
    barPx: () -> Int,
    footPx: () -> Float?,
    floatingPx: () -> Int,
    footerPx: () -> Int,
): Modifier = layout { measurable, constraints ->
    val toast = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val gap = MapChrome.PILL_GAP.toPx()
        val floating = floatingPx()
        // From a pill's bottom: the pill, what floats above it, and the gaps between.
        val lift = gap + PILL_HEIGHT.toPx() + gap + (if (floating > 0) floating + gap else 0f)
        val edge = edge()
        val shown = if (edge == null || atFull()) constraints.maxHeight - barPx() - gap - footerPx() else edge - lift
        val slid = hiding()
        val foot = footPx()
        val bottom = if (slid <= 0f || foot == null) shown else shown + (foot - lift - shown) * slid
        toast.placeRelative((constraints.maxWidth - toast.width) / 2, (bottom - toast.height).roundToInt().coerceAtLeast(0))
    }
}

private val PILL_HEIGHT = 36.dp
