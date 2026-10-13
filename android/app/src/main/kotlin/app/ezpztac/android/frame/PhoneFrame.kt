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
    // Hidden, nothing in the frame keeps the keyboard up or takes what is typed.
    LaunchedEffect(hidden) { if (hidden) focus.clearFocus() }
    // Composed as the frame hides, so it is the newest Back and is asked before every other one.
    if (hidden) BackHandler(enabled = backShows, onBack = onShow)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onSizeChanged { heightPx = it.height }) {
        // The map's middle follows the height the sheet rests at (or is settling at), and the bar under it; above half the sheet slides over it instead. Hidden,
        // it is the middle of the whole map but the system's bars.
        val anchors = sheet.anchors
        val resting = sheet.heading.let { if (it == SheetDetent.Full) SheetDetent.Half else it }
        val sheetHeight: Dp = anchors?.let { with(density) { it.heightOf(resting).toDp() } } ?: PanelSheetDefaults.LoweredHeight
        val padding = if (hidden) MapPadding(top = top, bottom = navigation) else MapPadding(top = top + MapChrome.ROW_BOTTOM, bottom = sheetHeight + bar)
        val footPx = { heightPx - with(density) { navigation.toPx() } }
        val frame = MapFrame(
            padding = padding,
            controlsBottom = { controlsEdge(edgeOf(sheet), hiding.value, footPx(), heightPx > 0) },
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
                    // Down off the screen, its edge going to the screen's foot, and fading.
                    .graphicsLayer {
                        val slid = hiding.value
                        val edge = sheet.offsetPx.takeUnless { it.isNaN() } ?: size.height
                        translationY = slid * (size.height + barPx - edge).coerceAtLeast(0f)
                        alpha = 1f - slid
                    },
            ) {
                PanelSheet(
                    state = sheet,
                    topLimit = top + MapChrome.SHEET_TOP_LIMIT,
                    raiseLabel = "Raise the ${current.label} panel",
                    lowerLabel = "Lower the ${current.label} panel",
                    inert = hidden,
                    header = {
                        PanelHeader(head.title, subtitle = head.subtitle, subtitleMono = head.subtitleMono, onBack = head.back, backDescription = head.backDescription) {
                            headerActions()
                            val full = sheet.settled == SheetDetent.Full
                            PanelAction(
                                if (full) EzpzIcons.expandMore else EzpzIcons.expandLess,
                                if (full) "Lower the ${current.label} panel" else "Open the ${current.label} panel full height",
                                onClick = { scope.launch { sheet.animateTo(sheet.fullOrHalf()) } },
                            )
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
                .aboveTheControls(sheet, hiding = { hiding.value }, barPx = { barPx }, footPx = { footPx().takeIf { heightPx > 0 } }, floatingPx = floatingPx) { footerPx },
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

/**
 * Places the toasts above the readout pill and whatever floats above it while the sheet is at half or lower, and at the foot of the sheet's space when it is at
 * full height (the map is covered then), above the panel's pinned footer ([footerPx]) if it has one. As the frame hides ([hiding]) they go to the foot of the
 * screen ([footPx]) with the pill. Read when placing, so they ride on the sheet as it moves.
 */
private fun Modifier.aboveTheControls(
    sheet: PanelSheetState,
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
        val edge = edgeOf(sheet)
        val shown = if (edge == null || sheet.heading == SheetDetent.Full) constraints.maxHeight - barPx() - gap - footerPx() else edge - lift
        val slid = hiding()
        val foot = footPx()
        val bottom = if (slid <= 0f || foot == null) shown else shown + (foot - lift - shown) * slid
        toast.placeRelative((constraints.maxWidth - toast.width) / 2, (bottom - toast.height).roundToInt().coerceAtLeast(0))
    }
}

private val PILL_HEIGHT = 36.dp
