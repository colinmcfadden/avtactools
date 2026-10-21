package app.ezpztac.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.CHIP_LINE_GAP
import app.ezpztac.designsystem.ChipLine
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetDefaults
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.keptAway
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the map's own controls sit, from the top of the screen, below the status bar. **A phone** (the owner's decision of 2026-10-13, departing from
 * docs/native-design's search bar over a row of chips): one row [ROW_TOP] down and [ROW_HEIGHT] tall, the folded search, the workspace, Import, the Library and
 * the account on it, each drawn [CHIPS_HEIGHT] tall; the map's buttons [BUTTONS_GAP] under the row ([BUTTONS_TOP]); a sheet over the map rises no higher than
 * [SHEET_TOP_LIMIT], so the row always shows. **A tablet**: its search bar [SEARCH_TOP] down and [SEARCH_HEIGHT] tall, and the map's buttons at its foot.
 */
object MapChrome {
    val SEARCH_TOP: Dp = 8.dp
    val SEARCH_HEIGHT: Dp = 56.dp

    /** The bottom of a tablet's search bar: the top of the part of the map the crosshair marks the middle of. */
    val SEARCH_BOTTOM: Dp = SEARCH_TOP + SEARCH_HEIGHT

    val ROW_TOP: Dp = 8.dp

    /** As tall as a touch target (crews may wear gloves): what is drawn on it is [CHIPS_HEIGHT] tall, each touch target 48 dp. */
    val ROW_HEIGHT: Dp = 48.dp

    /** The bottom of the phone's row: the top of the part of the map the crosshair marks the middle of. */
    val ROW_BOTTOM: Dp = ROW_TOP + ROW_HEIGHT

    /** The height every control on the phone's row is drawn at: the folded search, the chips and the account (and the open search). */
    val CHIPS_HEIGHT: Dp = 40.dp

    /** The gap between the phone's row and a sheet at full height. */
    val SHEET_GAP: Dp = 12.dp
    val BUTTONS_GAP: Dp = 16.dp

    /** The bottom of the row, plus the gap left between it and a sheet at full height. */
    val SHEET_TOP_LIMIT: Dp = ROW_BOTTOM + SHEET_GAP

    /** The top of the phone's map buttons, at the end under the row. */
    val BUTTONS_TOP: Dp = ROW_BOTTOM + BUTTONS_GAP

    /** The gap between the readout pill and the sheet's edge, and between the pill and a tool floating above it. */
    val PILL_GAP: Dp = 12.dp

    /** The map's buttons: this square, at the end. */
    val BUTTON: Dp = 48.dp

    /** A tablet's map buttons stand this far above the map's foot (the design's AT screens). */
    val FOOT_GAP: Dp = 24.dp
}

/**
 * How far the phone's frame has gone (a tap on the map hides it, the owner's decision of 2026-10-13): [hidden] once it is going or gone, [away] once it is all the
 * way off the screen, and [fraction] (0 shown, 1 hidden) read as it is drawn, so a frame of the slide is not a composition. A tablet's frame never hides.
 */
@Stable
class FrameHiding(val hidden: Boolean = false, val away: Boolean = false, val fraction: () -> Float = { 0f }) {
    companion object {
        /** The frame shown, as a tablet's always is. */
        val SHOWN = FrameHiding()
    }
}

/**
 * The map with what sits over it. On a phone (the owner's decision of 2026-10-13): one row along the top, the folded search ([search]), [chips] and [account]
 * at its end, with [belowChips] under it; the map's buttons at the end under the row; the crosshair at the middle of the map with the grid under it in a pill;
 * and a [sheet] over all of that but the row. On a tablet ([buttonsAtFoot]): the search bar along the top, always open, and the map's buttons at its foot. [map]
 * is where the map view goes, so this layout is tried without one (a GPU is not available to a test).
 *
 * [padding] is the part of the map whose middle the crosshair marks: the camera's padding (`EzpzMap(padding = …)`), so the readout names the
 * place under the crosshair. When it changes the crosshair glides to the new middle over the same time as the map. [controlsBottom] is where
 * the readout pill and the [overlay] (a tool that is there only while it is in use) stand, in pixels from the top of this screen, null for
 * its bottom: it is read as the sheet moves, so they ride on its edge without the screen being composed again.
 *
 * [onSearch] looks for what was typed and answers whether it found the place (the map then flies there, and the phone's search folds).
 */
@Composable
fun MapScreen(
    state: MapUiState,
    camera: CameraState?,
    gps: GpsState,
    onSearch: (String) -> Boolean,
    onClearSearchError: () -> Unit,
    onSelectStyle: (String) -> Unit,
    onToggleGps: () -> Unit,
    onGpsPermissionResult: (Boolean) -> Unit,
    onLocateMe: () -> Unit,
    onFaceNorth: () -> Unit,
    modifier: Modifier = Modifier,
    /** The slope heat map's button, present only while the open diagram has a slope measured. */
    slope: SlopeToggleUi? = null,
    onToggleSlope: () -> Unit = {},
    /** A tap on the readout pill, with what it says: the grid under the crosshair (or its degrees where there is no grid), to copy. */
    onCopyReadout: (String) -> Unit = {},
    padding: MapPadding = MapPadding(),
    controlsBottom: () -> Float? = { null },
    account: @Composable () -> Unit = {},
    chips: @Composable RowScope.() -> Unit = {},
    sheet: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    /** A tablet's (the design's AT screens): the search bar along the top, and the map's buttons at its foot, at the end, where no sheet rises. */
    buttonsAtFoot: Boolean = false,
    /** Under the phone's row, and a tablet's search bar: how an open Mission Pack stands. */
    belowChips: @Composable () -> Unit = {},
    /** Turns the MGRS grid on or off, from the Layers menu; null: not offered. */
    onToggleGrid: (() -> Unit)? = null,
    /** Where what floats over the map is told, so the grid's labels keep off it ([MgrsGridLayer]). */
    chrome: MapChromeBoxes? = null,
    /** The search: folded or open on a phone, and what was typed. Held by the frame, which folds it (a tap on the map). */
    search: MapSearchState = rememberMapSearchState(),
    /** The phone's frame hiding (a tap on the map): the row, what is said under it and the search go with it, up off the screen; the rest stays. */
    hiding: FrameHiding = FrameHiding.SHOWN,
    map: @Composable () -> Unit,
) {
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    // The search goes with the frame (and the keyboard with it).
    LaunchedEffect(hiding.hidden) { if (hiding.hidden) search.close() }
    Box(modifier.fillMaxSize()) {
        map()

        // The crosshair is the middle of the map that shows: between the row and the sheet, moving with the map when the sheet comes to rest.
        val crossTop by animateDpAsState(padding.top, tween(PanelSheetDefaults.SettleMillis), label = "crosshair top")
        val crossBottom by animateDpAsState(padding.bottom, tween(PanelSheetDefaults.SettleMillis), label = "crosshair bottom")
        Box(Modifier.fillMaxSize().padding(top = crossTop, bottom = crossBottom), contentAlignment = Alignment.Center) { Crosshair() }

        val buttons = if (buttonsAtFoot) {
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                .padding(bottom = MapChrome.FOOT_GAP, end = Tokens.Spacing.lg.dp)
        } else {
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(top).padding(top = MapChrome.BUTTONS_TOP, end = Tokens.Spacing.lg.dp)
        }
        Column(buttons.chromeBox(chrome, "buttons"), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            LayersButton(state, onSelectStyle, onToggleGrid)
            GpsButton(gps, onToggleGps, onGpsPermissionResult, onLocateMe)
            slope?.let {
                MapButton(
                    EzpzIcons.terrain, if (it.shown) "Slope heat map is on. Tap to hide it" else "Slope heat map is off. Tap to show it",
                    active = it.shown, onClick = onToggleSlope,
                )
            }
            // Only once the map is turned: facing north it says nothing, and the design's map shows no compass.
            camera?.takeIf { abs(it.bearingDegrees) >= 0.5 }?.let { CompassButton(it.bearingDegrees, onFaceNorth) }
        }

        // The pill and whatever tool floats above it ride on the sheet's edge, or on the keyboard's top where that is higher (a field in the held bar typed into), and are
        // never taller than the room under the row (a tablet's search bar): what floats gives way, and scrolls, before the pill does.
        val keyboard = WindowInsets.ime
        val statusBar = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
        val chromeBottom = if (buttonsAtFoot) MapChrome.SEARCH_BOTTOM else MapChrome.ROW_BOTTOM
        Column(
            Modifier.fillMaxSize().standingOn(controlsBottom, MapChrome.PILL_GAP, keyboard) {
                statusBar.getTop(this) + chromeBottom.toPx() * (1f - hiding.fraction()) + MapChrome.PILL_GAP.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MapChrome.PILL_GAP),
        ) {
            Box(Modifier.weight(1f, fill = false).chromeBox(chrome, "overlay")) { overlay() }
            state.readout?.let { Box(Modifier.chromeBox(chrome, "readout")) { ReadoutPill(it, onCopyReadout) } }
        }

        sheet()

        // The navigation bar at the map's foot covers it too (a tablet's, where no sheet rises), and the status bar at its top: only told, never drawn.
        if (chrome != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.safeDrawing).chromeBox(chrome, "navigation"))
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).chromeBox(chrome, "status"))
        }

        // Reported from the top of the window: the status bar over the map is as covered as the row (the map is drawn edge to edge).
        if (buttonsAtFoot) {
            Column(Modifier.fillMaxWidth().chromeBox(chrome, "search").windowInsetsPadding(top).padding(top = MapChrome.SEARCH_TOP, start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp)) {
                SearchBar(search, state.searchError, onSearch, onClearSearchError, account)
                // On its own surface: red text straight on imagery cannot be read.
                state.searchError?.let { Banner(it, BannerKind.Error, Modifier.padding(top = Tokens.Spacing.sm.dp)) }
                Box(Modifier.padding(top = Tokens.Spacing.sm.dp)) { belowChips() }
            }
        } else {
            // Hidden with the frame: up off the screen and fading as it goes, then not laid out at all, so what floats over the map reports nothing for it.
            Column(
                Modifier.fillMaxWidth().chromeBox(chrome, "search", shown = !hiding.away)
                    .keptAway(hiding.hidden, hiding.away)
                    .graphicsLayer {
                        val gone = hiding.fraction()
                        translationY = -gone * size.height
                        alpha = 1f - gone
                    }
                    .windowInsetsPadding(top).padding(top = MapChrome.ROW_TOP, start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp),
            ) {
                MapTopRow(search, state.searchError, onSearch, onClearSearchError, account, chips)
                // What is said under the row stops short of the map's buttons, which stand at the end below it.
                val clear = Modifier.padding(top = Tokens.Spacing.sm.dp, end = MapChrome.BUTTON + Tokens.Spacing.sm.dp)
                // On its own surface: red text straight on imagery cannot be read.
                state.searchError?.let { Banner(it, BannerKind.Error, clear) }
                Box(clear) { belowChips() }
            }
        }
    }
}

/**
 * Lays its content out at its natural size, centred across, with its bottom [gap] above [edge] (pixels from the top; the bottom of the space
 * when null). The edge is read only when placing, so a sheet being dragged moves what stands on it without measuring it again.
 */
private fun Modifier.standingOn(edge: () -> Float?, gap: Dp, keyboard: WindowInsets, ceiling: Density.() -> Float): Modifier = layout { measurable, constraints ->
    val bottom = standingBottom(edge(), constraints.maxHeight, keyboard.getBottom(this))
    val foot = (bottom - gap.toPx()).roundToInt()
    // Measured into the room between the ceiling and where it stands, so what does not fit is cut to it (and scrolls) rather than pushed off the top of the screen.
    val room = (foot - ceiling().roundToInt()).coerceIn(0, constraints.maxHeight)
    val content = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = room))
    layout(constraints.maxWidth, constraints.maxHeight) {
        content.placeRelative((constraints.maxWidth - content.width) / 2, foot - content.height)
    }
}

/**
 * Where what floats over the map stands, in pixels from the top of a view [height] tall: on the sheet's [edge] (the foot of the view with none), or on the
 * keyboard's top where the keyboard, [keyboard] pixels up from the foot (the map is drawn to the window's foot), comes higher, so a field typed into is not under it.
 */
internal fun standingBottom(edge: Float?, height: Int, keyboard: Int): Float = minOf(edge ?: height.toFloat(), (height - keyboard).toFloat())

/**
 * A chip on the phone's row (the design's workspace, Import and Library): [MapChrome.CHIPS_HEIGHT] tall (a 48 dp touch target), its icon in the accent,
 * [trailingIcon] after the words. With no [onClick] it is a label, not a button. [description] names it for a screen reader where the words alone do not.
 * Given less room than its words take, its trailing icon gives way before the words are cut ([ChipLine]).
 */
@Composable
fun MapChip(icon: Int, label: String, modifier: Modifier = Modifier, description: String? = null, trailingIcon: Int? = null, onClick: (() -> Unit)? = null) {
    // With no words it is its icon alone (where there is no room for them): [description] names it.
    val described = modifier.height(MapChrome.CHIPS_HEIGHT).then(if (label.isEmpty()) Modifier.widthIn(min = 40.dp) else Modifier)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
    val shape = RoundedCornerShape(Tokens.Radius.sm.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val content: @Composable () -> Unit = {
        ChipLine(
            lead = { Icon(painterResource(icon), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(CHIP_ICON)) },
            name = { if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            arrow = trailingIcon?.let { { Icon(painterResource(it), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) } },
            modifier = Modifier.padding(start = CHIP_START, end = if (trailingIcon != null || label.isEmpty()) Tokens.Spacing.sm.dp else CHIP_END),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = described, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    } else {
        Surface(modifier = described, shape = shape, color = color, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

private val CHIP_START = Tokens.Spacing.sm.dp
private val CHIP_ICON = 18.dp
private val CHIP_GAP = CHIP_LINE_GAP
private val CHIP_END = Tokens.Spacing.md.dp

/** What a chip with words and no trailing icon takes besides its words ([ChipsThatFit] measures with it). */
private val CHIP_FRAME = CHIP_START + CHIP_ICON + CHIP_GAP + CHIP_END

/** One of [ChipsThatFit]'s chips: a [MapChip] that does [onClick], named by [description] whether or not its [label] shows. */
class ChipAction(val icon: Int, val label: String, val description: String, val onClick: () -> Unit)

/**
 * The chips on the phone's row, on one line: [lead] (the workspace) first, then [chips] with their words when that leaves [lead] the room it would take,
 * else as their icons alone, and [lead] is given what is left (a pack's chip cuts its name). The words are measured as text, so only the chips shown are
 * ever composed (a copy measured off screen would still be in what a screen reader and a test see).
 */
@Composable
fun ChipsThatFit(lead: @Composable () -> Unit, chips: List<ChipAction>, modifier: Modifier = Modifier, gap: Dp = Tokens.Spacing.sm.dp) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    // What the chips take with their words: each label in a chip's frame (MapChip's padding, icon and the gap before the words), and the gaps between.
    val words = remember(chips.map { it.label }, style, density) {
        with(density) {
            chips.sumOf { CHIP_FRAME.roundToPx() + measurer.measure(it.label, style, maxLines = 1, softWrap = false, density = density).size.width } +
                gap.roundToPx() * (chips.size - 1).coerceAtLeast(0)
        }
    }
    SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val leads = subcompose("lead", lead)
        val wanted = leads.maxOfOrNull { it.maxIntrinsicWidth(if (height == Constraints.Infinity) 0 else height) } ?: 0
        val compact = wanted + gapPx + words > constraints.maxWidth
        // Keyed by the choice: the other form is not kept composed.
        val rest = subcompose(compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                chips.forEach { MapChip(it.icon, if (compact) "" else it.label, description = it.description, onClick = it.onClick) }
            }
        }.first().measure(Constraints(maxHeight = height))
        val room = (constraints.maxWidth - rest.width - gapPx).coerceAtLeast(0)
        val placed = leads.map { it.measure(Constraints(maxWidth = room, maxHeight = height)) }
        val leadWidth = placed.maxOfOrNull { it.width } ?: 0
        val tall = maxOf(rest.height, placed.maxOfOrNull { it.height } ?: 0)
        layout((leadWidth + gapPx + rest.width).coerceAtMost(constraints.maxWidth), tall) {
            placed.forEach { it.placeRelative(0, (tall - it.height) / 2) }
            rest.placeRelative(leadWidth + gapPx, (tall - rest.height) / 2)
        }
    }
}

/** The slope heat map's button: whether the raster is drawn over the diagram. */
data class SlopeToggleUi(val shown: Boolean)

/** The design's crosshair: four arms around a gap, a light line over a dark halo so it reads on any imagery. */
@Composable
private fun Crosshair() {
    val color = MaterialTheme.colorScheme.onBackground
    val halo = MaterialTheme.colorScheme.background
    Canvas(Modifier.size(44.dp).semantics { contentDescription = "Map centre" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = 20.dp.toPx()
        val inner = 8.dp.toPx()
        for ((width, paint) in listOf(4.dp.toPx() to halo, 2.dp.toPx() to color)) {
            drawLine(paint, Offset(c.x - outer, c.y), Offset(c.x - inner, c.y), width, StrokeCap.Round)
            drawLine(paint, Offset(c.x + inner, c.y), Offset(c.x + outer, c.y), width, StrokeCap.Round)
            drawLine(paint, Offset(c.x, c.y - outer), Offset(c.x, c.y - inner), width, StrokeCap.Round)
            drawLine(paint, Offset(c.x, c.y + inner), Offset(c.x, c.y + outer), width, StrokeCap.Round)
        }
    }
}

/** The grid under the crosshair (its degrees where there is no grid), in a pill that copies it when tapped. */
@Composable
private fun ReadoutPill(readout: Readout, onCopy: (String) -> Unit) {
    val text = readout.mgrs ?: readout.latLon
    Surface(
        onClick = { onCopy(text) },
        modifier = Modifier.height(36.dp).semantics { contentDescription = "Grid under the crosshair, $text. Tap to copy" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Icon(painterResource(EzpzIcons.add), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(18.dp))
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.28.sp)
        }
    }
}

/**
 * A tablet's search along the top, always open: a grid or a coordinate, flown to when the keyboard's search key or the search button is pressed. What is typed is
 * [search]'s, so it is the same words when the window turns into a phone's. [account] sits at its end (a tablet's is the rail's: none).
 */
@Composable
private fun SearchBar(search: MapSearchState, error: String?, onSearch: (String) -> Boolean, onClearError: () -> Unit, account: @Composable () -> Unit) {
    val text = search.text
    Surface(
        modifier = Modifier.fillMaxWidth().height(MapChrome.SEARCH_HEIGHT),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            IconButton(onClick = { onSearch(text) }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.search), contentDescription = "Search a grid or lat/long")
            }
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.32.sp, color = MaterialTheme.colorScheme.onSurface,
            )
            BasicTextField(
                value = text,
                onValueChange = { search.text = it; if (error != null) onClearError() },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Grids are capitals; a coordinate is digits, which the setting leaves alone.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("MGRS grid or lat/long", style = style.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, letterSpacing = 0.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    }
                },
            )
            account()
        }
    }
}

/** One of the map's buttons at the right: 48 dp, the icon in the accent; [active] while what it turns on is on. */
@Composable
private fun MapButton(icon: Int, description: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else EzpzTheme.status.accent,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) }
    }
}

/**
 * The map's layers: the base maps, and below them the MGRS grid's switch (the web's Layers control), which goes over any of them. The button
 * says the grid is on while it is, as the web's says *MGRS* on its collapsed control.
 */
@Composable
private fun LayersButton(state: MapUiState, onSelect: (String) -> Unit, onToggleGrid: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        val described = "Base map: ${state.style.label}" + if (onToggleGrid != null && state.grid) ". MGRS grid on" else ""
        MapButton(EzpzIcons.layers, described, active = onToggleGrid != null && state.grid) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (style in state.styles) {
                DropdownMenuItem(
                    text = { Text(if (style.id == state.style.id) "${style.label}  ✓" else style.label) },
                    onClick = { open = false; onSelect(style.id) },
                )
            }
            onToggleGrid?.let { toggle ->
                HorizontalDivider()
                // The menu stays open: turning the grid on is looked at with the menu, and turned off again from it.
                DropdownMenuItem(
                    text = { Text("MGRS grid") },
                    trailingIcon = { Switch(checked = state.grid, onCheckedChange = null) },
                    onClick = toggle,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        role = Role.Switch
                        toggleableState = if (state.grid) ToggleableState.On else ToggleableState.Off
                    },
                )
            }
        }
    }
}

@Composable
private fun GpsButton(gps: GpsState, onToggle: () -> Unit, onPermissionResult: (Boolean) -> Unit, onLocate: () -> Unit) {
    // Fine location is what a GPS fix needs; coarse is asked for beside it because Android only offers the precise choice that way.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        onPermissionResult(granted[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    LaunchedEffect(gps) {
        if (gps == GpsState.NeedsPermission) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val icon = when (gps) {
        GpsState.Off, GpsState.NeedsPermission, is GpsState.Tracking -> EzpzIcons.myLocation
        GpsState.Searching -> EzpzIcons.locationSearching
        is GpsState.Unavailable -> EzpzIcons.locationDisabled
    }
    val description = when (gps) {
        GpsState.Off, GpsState.NeedsPermission -> "Turn the GPS on"
        GpsState.Searching -> "GPS is looking for a fix. Tap to turn off"
        is GpsState.Tracking -> "GPS on. Tap to turn off"
        is GpsState.Unavailable -> gps.message
    }
    MapButton(icon, description, active = gps is GpsState.Tracking, onClick = if (gps is GpsState.Tracking) onLocate else onToggle)
}

@Composable
private fun CompassButton(bearingDegrees: Double, onFaceNorth: () -> Unit) {
    val needle = MaterialTheme.colorScheme.error
    val tail = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onFaceNorth,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Face north. The map is turned ${bearingDegrees.toInt()} degrees" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        // The button stays upright; the needle turns the opposite way to the map, so it always points to north.
        Canvas(Modifier.padding(Tokens.Spacing.md.dp).graphicsLayer { rotationZ = -bearingDegrees.toFloat() }) {
            val c = Offset(size.width / 2, size.height / 2)
            val half = size.height / 2
            val width = size.width * 0.18f
            drawPath(Path().apply { moveTo(c.x, c.y - half); lineTo(c.x + width, c.y); lineTo(c.x - width, c.y); close() }, needle)
            drawPath(Path().apply { moveTo(c.x, c.y + half); lineTo(c.x + width, c.y); lineTo(c.x - width, c.y); close() }, tail)
        }
    }
}
