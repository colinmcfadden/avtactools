package app.ezpztac.android.frame

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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import app.ezpztac.map.MapChrome
import app.ezpztac.map.MapPadding
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the frame tells the map screen it draws: the camera's [padding] (the crosshair marks the middle of what it leaves), where the readout
 * pill and the floating tools stand ([controlsBottom]: the sheet's edge, never higher than half, so a sheet raised further slides over them),
 * and the [sheet] to draw over the map's controls.
 */
@Stable
class MapFrame internal constructor(
    val padding: MapPadding,
    val controlsBottom: () -> Float?,
    val sheet: @Composable () -> Unit,
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

/** The bar of panels is Material's navigation bar, 80 dp above the system's own buttons. */
private val BAR_HEIGHT = 80.dp

/**
 * The phone's frame (docs/native-design, the AP screens): the map and its controls, the panel over them in a sheet that rests lowered, at half
 * or at full height, the bar of panels along the bottom, and the toasts. [mapScreen] draws the map and its controls as [MapFrame] says.
 *
 * Every panel stays composed and only [current] is laid out: each keeps its scroll, its file pickers and what it listens for (an export being
 * built goes to the share sheet whichever panel shows; the threat panel keeps the window out of screenshots while threats are on the map).
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
    mapScreen: @Composable (MapFrame) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val bar = BAR_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The keyboard rises over the bar too: what is consumed here is not padded again by the sheet's content.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().consumeWindowInsets(PaddingValues(bottom = bar))) {
            // The map's middle follows the height the sheet rests at (or is settling at); above half the sheet slides over it instead.
            val anchors = sheet.anchors
            val resting = sheet.heading.let { if (it == SheetDetent.Full) SheetDetent.Half else it }
            val bottom: Dp = anchors?.let { with(density) { it.heightOf(resting).toDp() } } ?: PanelSheetDefaults.LoweredHeight
            val frame = MapFrame(
                padding = MapPadding(top = top + MapChrome.SEARCH_BOTTOM, bottom = bottom),
                controlsBottom = { edgeOf(sheet) },
                sheet = {
                    PanelSheet(
                        state = sheet,
                        topLimit = top + MapChrome.SHEET_TOP_LIMIT,
                        raiseLabel = "Raise the ${current.label} panel",
                        lowerLabel = "Lower the ${current.label} panel",
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
                    ) {
                        Box(Modifier.fillMaxSize().imePadding()) {
                            panels.forEach { each ->
                                key(each) {
                                    Box(Modifier.fillMaxSize().then(if (each == current) Modifier else Modifier.hidden())) { panel(each) }
                                }
                            }
                        }
                    }
                },
            )
            mapScreen(frame)
            ToastHost(snackbars, Modifier.fillMaxSize().padding(horizontal = Tokens.Spacing.lg.dp).aboveTheControls(sheet, floatingPx))
        }
        PanelBar(panels, current, counts, onPick)
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
 * Kept in composition (its scroll, its pickers, what it listens for, its window guard) but neither measured nor placed, and out of the
 * semantics: not drawn, not touched, not read by a screen reader.
 */
private fun Modifier.hidden(): Modifier = clearAndSetSemantics {}.layout { _, _ -> layout(0, 0) {} }

/**
 * Places the toasts above the readout pill and whatever floats above it while the sheet is at half or lower, and at the foot of the space when
 * the sheet is at full height (the map is covered then). Read when placing, so they ride on the sheet as it moves.
 */
private fun Modifier.aboveTheControls(sheet: PanelSheetState, floatingPx: () -> Int): Modifier = layout { measurable, constraints ->
    val toast = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val gap = MapChrome.PILL_GAP.toPx()
        val edge = edgeOf(sheet)
        val bottom = if (edge == null || sheet.heading == SheetDetent.Full) {
            constraints.maxHeight - gap
        } else {
            val floating = floatingPx()
            edge - gap - PILL_HEIGHT.toPx() - gap - (if (floating > 0) floating + gap else 0f)
        }
        toast.placeRelative((constraints.maxWidth - toast.width) / 2, (bottom - toast.height).roundToInt().coerceAtLeast(0))
    }
}

private val PILL_HEIGHT = 36.dp
