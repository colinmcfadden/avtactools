package app.ezpztac.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.workspace.MapPointAction
import kotlin.math.max

/** What the map menu offers, and what each choice does. A choice whose panel this account is not offered is null, and not shown. */
internal data class MapMenuChoices(
    val newLz: (() -> Unit)? = null,
    /** Why *New LZ/PZ here* is off: a read-only pack is open, which nothing new goes into ("Off in a finished pack"). */
    val newLzOff: String? = null,
    val addThreat: (() -> Unit)? = null,
    /** What *Add route point here* would do now, and doing it. */
    val routePoint: Pair<MapPointAction, () -> Unit>? = null,
    val copyGrid: () -> Unit = {},
)

/**
 * The design's map menu (`MapMenu`): a long press on nothing that can be picked up opens it beside the press ([at], in the map's own pixels, measured from its left
 * whatever the language's direction), headed by the grid under the finger, with the design's ring at the press. Choosing closes it ([onDismiss]) and then does the
 * choice; a touch elsewhere or Back closes it. Composed over the whole map, which is what [at] is measured in. It goes **above and to the right of the press**, as
 * the design draws it, clear of the finger and of the ring ([mapMenuPosition]); Material's menu opens under the fingertip, so this is a popup of its own.
 */
@Composable
internal fun MapMenu(expanded: Boolean, place: MapMenuPlace?, at: IntOffset, choices: MapMenuChoices, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!expanded || place == null) return
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        PressMarker(at)
        val provider = remember(at, density) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                    with(density) {
                        mapMenuPosition(
                            press = IntOffset(anchorBounds.left + at.x, anchorBounds.top + at.y), menu = popupContentSize, window = windowSize,
                            beside = MENU_BESIDE.roundToPx(), below = MENU_BELOW.roundToPx(), margin = MENU_MARGIN.roundToPx(),
                        )
                    }
            }
        }
        Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Surface(
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp,
                modifier = Modifier.width(menuWidth(place.grid)).semantics { paneTitle = "Map menu for ${place.grid}" },
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) { MapMenuItems(place, choices, onDismiss) }
            }
        }
    }
}

/**
 * Where the menu goes for a press at [press] (window pixels), [menu] big, in a [window] that size: to the right of the press by [beside] and with its foot [below]
 * under it, as the design draws it; to the left when there is no room on the right, and under the press when there is none above; never past [margin] from the edges.
 */
internal fun mapMenuPosition(press: IntOffset, menu: IntSize, window: IntSize, beside: Int, below: Int, margin: Int): IntOffset {
    val x = when {
        press.x + beside + menu.width <= window.width - margin -> press.x + beside
        press.x - beside - menu.width >= margin -> press.x - beside - menu.width
        else -> window.width - margin - menu.width
    }
    val y = when {
        press.y + below - menu.height >= margin -> press.y + below - menu.height
        else -> press.y - below
    }
    return IntOffset(x.coerceIn(margin, max(margin, window.width - margin - menu.width)), y.coerceIn(margin, max(margin, window.height - margin - menu.height)))
}

/** The grid's style: the design's 14 sp monospace, semi-bold, a little spaced. */
@Composable
private fun gridStyle(): TextStyle =
    EzpzText.grid.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em, color = MaterialTheme.colorScheme.onSurface)

/**
 * The design's 256 dp, or wider for a grid that would not fit on one line beside its pin (large text): the grid is never split between its easting and northing. No wider
 * than the window allows; past that the grid's text is made smaller to fit ([MapMenuItems]).
 */
@Composable
private fun menuWidth(grid: String): Dp {
    val density = LocalDensity.current
    val style = gridStyle()
    val measurer = rememberTextMeasurer()
    val gridWidth = remember(grid, style, density) { with(density) { measurer.measure(grid, style, softWrap = false, maxLines = 1).size.width.toDp() } }
    val window = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val widest = if (window > 0.dp) window - MENU_MARGIN * 2 else MENU_WIDTH
    return max(MENU_WIDTH, gridWidth + HEADER_CHROME).coerceAtMost(widest)
}

/** The menu's grid and choices, apart from the window it opens in, so they can be tried without one. */
@Composable
internal fun ColumnScope.MapMenuItems(place: MapMenuPlace, choices: MapMenuChoices, onDismiss: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).padding(start = 14.dp, end = 16.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(EzpzIcons.locationOnFill), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(20.dp))
        // One line, whatever the text size: smaller rather than split (the menu widens first, up to the window).
        val style = gridStyle()
        BasicText(place.grid, style = style, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp))
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    fun choose(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    choices.newLz?.let {
        val off = choices.newLzOff
        MenuChoice(EzpzIcons.addLocationAlt, "New LZ/PZ here", off ?: "Opens another LZ/PZ with this target", choose(it), accent = off == null, enabled = off == null)
    }
    choices.addThreat?.let { MenuChoice(EzpzIcons.crisisAlert, "Add threat here", null, choose(it)) }
    choices.routePoint?.let { (action, add) ->
        val (supporting, enabled) = when (action) {
            is MapPointAction.AddTo -> "Adds a turn point to the end of ${action.routeName}" to true
            MapPointAction.StartRoute -> "Starts a new route here" to true
            MapPointAction.ChooseRoute -> "Tap a route first, to choose which" to false
            MapPointAction.Mission -> "An imported mission's routes are its file's" to false
            is MapPointAction.Locked -> action.why to false
        }
        MenuChoice(EzpzIcons.route, "Add route point here", supporting, choose(add), enabled = enabled)
    }
    MenuChoice(EzpzIcons.contentCopy, "Copy grid", null, choose(choices.copyGrid))
}

@Composable
private fun MenuChoice(icon: Int, label: String, supporting: String?, onClick: () -> Unit, accent: Boolean = false, enabled: Boolean = true) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = {
            Icon(painterResource(icon), contentDescription = null, tint = if (accent && enabled) EzpzTheme.status.accent else LocalContentColor.current)
        },
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(start = 14.dp, end = 16.dp),
        modifier = Modifier.heightIn(min = if (supporting != null) 56.dp else 48.dp),
    )
}

/** The design's ring where the map was pressed, while its menu is open: what "here" is once the finger has lifted. Nothing in it takes a touch. */
@Composable
private fun PressMarker(at: IntOffset) {
    val accent = EzpzTheme.status.accent
    val halo = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(at.x.toFloat(), at.y.toFloat())
        drawCircle(accent.copy(alpha = 0.45f), radius = 42.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
        drawCircle(accent.copy(alpha = 0.22f), radius = 29.dp.toPx(), center = c)
        drawCircle(halo.copy(alpha = 0.55f), radius = 29.dp.toPx(), center = c, style = Stroke(5.dp.toPx()))
        drawCircle(accent, radius = 29.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
        drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
        drawCircle(halo, radius = 5.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
    }
}

private val MENU_WIDTH = 256.dp

/** The header row around the grid: its paddings, the pin and the gap after it. */
private val HEADER_CHROME = 14.dp + 20.dp + 10.dp + 16.dp

/** The design's menu stands 34 dp to the right of the press, its foot 8 dp below it: clear of the fingertip and the ring's middle. */
private val MENU_BESIDE = 34.dp
private val MENU_BELOW = 8.dp
private val MENU_MARGIN = 8.dp
