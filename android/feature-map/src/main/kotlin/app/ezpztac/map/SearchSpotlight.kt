package app.ezpztac.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens
import app.ezpztac.geo.PlaceChoice
import app.ezpztac.geo.PlaceSearch
import kotlin.math.roundToInt

/**
 * The map's search, open (the owner's request of 2026-11-05: "the main thing you are interacting with"): a large field in the middle of the screen above the
 * keyboard, with the places what is typed could be under it ([PlaceList]), over everything else, which a scrim dims. It grows out of where the search was
 * ([MapSearchState.origin]: the phone's round button, a tablet's bar) and goes back into it as it folds. Its look is the web's top-bar search open: lit from
 * above inside, the accent round its edge, lifted by its shadow.
 *
 * It folds on its back arrow, system Back, Escape (once the list has gone), a tap on the scrim, and once a place is found or chosen. Drawn by whoever draws over the
 * whole frame (the app's `MapHome`), or by [MapScreen] itself where nothing does.
 */
@Composable
fun MapSearchSpotlight(
    search: MapSearchState,
    state: MapUiState,
    onSearch: (String) -> Boolean,
    onClearSearchError: () -> Unit,
    onChoosePlace: (PlaceChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = animateFloatAsState(if (search.expanded) 1f else 0f, tween(SPOTLIGHT_MILLIS, easing = FastOutSlowInEasing), label = "spotlight")
    // Composed only while it is open, or folding: what was typed stays in the state either way.
    val drawn by remember { derivedStateOf { progress.value > 0f } }
    val error = state.searchError
    val said by rememberUpdatedState(error)
    // Folded, the search says nothing more: an error left from the last try would wait for a search nobody is making.
    LaunchedEffect(search.expanded) { if (!search.expanded && said != null) onClearSearchError() }

    // What is typed, read as it is typed (the web's target field), the rows it lists and what a screen reader is told of them.
    val reading = remember(search.text) { PlaceSearch.read(search.text) }
    val rows = remember(reading, state.center) { placeRows(reading, state.center) }
    // A search refused is said in its banner, in place of the list, which says the same sentence; but readings to choose from stay listed (their sentence
    // says why there are two), or there would be nothing to choose.
    val listing = search.listed && (rows.isNotEmpty() || reading.message != null) && (error == null || rows.isNotEmpty())
    // Each search refused, so the same sentence is said again (the web's `asked`).
    var refusals by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val find: (String) -> Boolean = { typed ->
        onSearch(typed).also { found ->
            // Found: the field says the grid gone to, as the web's does, and the search folds. Refused: readings to choose from, or why not, listed again.
            if (found) {
                search.settle(PlaceSearch.read(typed).choices.singleOrNull()?.mgrs)
                search.close()
            } else {
                search.show()
                refusals++
            }
        }
    }
    val choose: (PlaceChoice) -> Unit = { choice ->
        onClearSearchError()
        onChoosePlace(choice)
        search.settle(choice.mgrs)
        search.close()
        focusManager.clearFocus()
    }

    Box(modifier.fillMaxSize()) {
        // What the list holds, said politely; composed whether the search is open or not, since a live region made with its words is never heard.
        val announced = when {
            !search.expanded -> ""
            listing -> placeSummary(reading, rows)
            else -> error ?: ""
        }
        PlaceAnnouncer(if (announced.isEmpty()) "" else announced + if (refusals % 2 == 1) "​" else "")
        if (!search.expanded && !drawn) return@Box

        // Composed while open, so it is the newest Back and is asked first.
        if (search.expanded) BackHandler { search.close() }
        // The scrim: the map and the frame dimmed behind the search; a tap on it folds the search and does nothing else.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .then(if (search.expanded) Modifier.pointerInput(Unit) { detectTapGestures { search.close() } } else Modifier)
                .clearAndSetSemantics {},
        )
        // The open field's size, which its contents are laid out at whatever its size as it grows (set as the layout measures it).
        val whole = remember { IntArray(2) }
        SpotlightLayout(search, progress, whole) {
            SpotlightField(search, error, find, onClearSearchError, progress, whole)
            Column(
                Modifier.verticalScroll(rememberScrollState()).graphicsLayer { alpha = progress.value },
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                when {
                    listing -> PlaceList(reading, rows, choose, Modifier.fillMaxWidth())
                    // On its own surface: red text straight on imagery cannot be read.
                    error != null -> Banner(error, BannerKind.Error)
                    search.text.isBlank() -> SpotlightHint()
                }
            }
        }
    }
}

/**
 * Lays the field out where it is going (centred across, a third of the way down the room between the status bar and the keyboard, as wide as [SPOTLIGHT_WIDTH] or
 * the screen allows) and, as [progress] runs, from where it came from ([MapSearchState.origin]); what is under the field (its list) goes under where the field
 * ends, as wide, in the room left above the keyboard.
 */
@Composable
private fun SpotlightLayout(search: MapSearchState, progress: State<Float>, whole: IntArray, content: @Composable () -> Unit) {
    val ime = WindowInsets.ime
    val safe = WindowInsets.safeDrawing
    // Where this layout is in the window, so the origin (reported in the window's terms) can be read in its own.
    val at = remember { floatArrayOf(0f, 0f) }
    Layout(
        content = content,
        modifier = Modifier.fillMaxSize().onGloballyPositioned { at[0] = it.positionInWindow().x; at[1] = it.positionInWindow().y }
            // A keyboard's Tab and arrows stay in the open search (the frame under it is dimmed and not read); folding it lets the focus go.
            .focusProperties { onExit = { if (search.expanded) cancelFocusChange() } }
            .focusGroup(),
    ) { measurables, constraints ->
        val p = progress.value
        val target = spotlightBox(
            constraints.maxWidth, constraints.maxHeight,
            top = safe.getTop(this), bottom = maxOf(ime.getBottom(this), safe.getBottom(this)),
            start = safe.getLeft(this, LayoutDirection.Ltr), end = safe.getRight(this, LayoutDirection.Ltr), density = this,
        )
        val origin = search.origin?.translate(Offset(-at[0], -at[1]))
            // Nothing to grow out of (a test, a first frame): from a little smaller, in place.
            ?: target.field.deflate(minOf(target.field.width, target.field.height) * 0.05f)
        val left = lerp(origin.left, target.field.left, p)
        val top = lerp(origin.top, target.field.top, p)
        val width = lerp(origin.width, target.field.width, p).roundToInt().coerceAtLeast(1)
        val height = lerp(origin.height, target.field.height, p).roundToInt().coerceAtLeast(1)
        whole[0] = target.field.width.roundToInt()
        whole[1] = target.field.height.roundToInt()
        val field = measurables[0].measure(Constraints.fixed(width, height))
        val below = measurables[1].measure(
            Constraints(minWidth = 0, maxWidth = target.field.width.roundToInt(), maxHeight = target.belowRoom.roundToInt().coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            field.place(left.roundToInt(), top.roundToInt())
            below.place(target.field.left.roundToInt(), target.belowTop.roundToInt())
        }
    }
}

/** Where the open field goes in a screen [width] by [height] with these insets (pixels), and the room under it for its list. */
internal class SpotlightBox(val field: Rect, val belowTop: Float, val belowRoom: Float)

internal fun spotlightBox(width: Int, height: Int, top: Int, bottom: Int, start: Int, end: Int, density: Density): SpotlightBox = with(density) {
    val side = Tokens.Spacing.lg.dp.toPx()
    val gap = Tokens.Spacing.sm.dp.toPx()
    val fieldWidth = minOf(width - start - end - 2 * side, SPOTLIGHT_WIDTH.toPx()).coerceAtLeast(1f)
    val fieldHeight = SPOTLIGHT_HEIGHT.toPx()
    val roomTop = top + side
    val roomBottom = height - bottom - side
    // A third of the way down the room: the eye's place for it, with the list below in sight above the keyboard.
    val fieldTop = roomTop + ((roomBottom - roomTop - fieldHeight) * SPOTLIGHT_AT).coerceAtLeast(0f)
    val left = start + (width - start - end - fieldWidth) / 2
    val field = Rect(left, fieldTop, left + fieldWidth, fieldTop + fieldHeight)
    SpotlightBox(field, field.bottom + gap, roomBottom - field.bottom - gap)
}

/**
 * The field: a back arrow that folds the search, what is typed, and the search action, laid out at the open field's whole size (whatever the field's size as it
 * grows) and shown as it opens. Its surface turns from the round button's shape into the open field's corners as it grows.
 */
@Composable
private fun SpotlightField(
    search: MapSearchState, error: String?, onSearch: (String) -> Boolean, onClearError: () -> Unit, progress: State<Float>, whole: IntArray,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // The cursor at the end of what was typed before, so it can be added to or taken back.
    var field by remember { mutableStateOf(TextFieldValue(search.text, TextRange(search.text.length))) }
    // What the search says changed from outside the field (a place found, a link): the field says it too.
    LaunchedEffect(search.text) { if (search.text != field.text) field = TextFieldValue(search.text, TextRange(search.text.length)) }
    LaunchedEffect(search.expanded) {
        if (search.expanded) {
            focus.requestFocus()
            keyboard?.show()
        } else {
            if (focused) focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val submit = { onSearch(field.text); Unit }
    val accent = EzpzTheme.status.accent
    val lit = MaterialTheme.colorScheme.onSurface
    val shape = remember(progress) { SpotlightShape(progress) }
    Surface(
        modifier = Modifier.semantics { paneTitle = "Search" },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Box(
            // Lit from above inside, and a little darker at its foot (in the palette's own colours, so night stays red).
            Modifier.fillMaxSize().background(Brush.verticalGradient(0f to lit.copy(alpha = 0.08f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.14f))),
        ) {
            Row(
                Modifier
                    .layout { measurable, constraints ->
                        // At the open field's size, its start at the field's start (placeRelative: the right in a right-to-left language), centred down.
                        val placeable = measurable.measure(Constraints.fixed(whole[0].coerceAtLeast(1), whole[1].coerceAtLeast(1)))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, (constraints.maxHeight - placeable.height) / 2) }
                    }
                    .graphicsLayer { alpha = progress.value },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = search::close, modifier = Modifier.padding(start = Tokens.Spacing.xs.dp).size(48.dp)) {
                    Icon(painterResource(EzpzIcons.arrowBack), contentDescription = "Close the search", modifier = Modifier.size(Tokens.Size.icon.dp))
                }
                val style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 19.sp, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                BasicTextField(
                    value = field,
                    onValueChange = {
                        // The cursor moving changes nothing typed, and lists nothing again.
                        if (it.text != field.text) {
                            search.edit(it.text)
                            if (error != null) onClearError()
                        }
                        field = it
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                        // Escape lets the list go and keeps what was typed; with no list it folds the search (a keyboard on a tablet).
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                                if (search.listed) search.hide() else search.close()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(accent),
                    // Grids are capitals; a coordinate is digits, which the setting leaves alone.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (field.text.isEmpty()) {
                                Text(
                                    PLACEHOLDER, style = style.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 17.sp, letterSpacing = 0.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = submit, modifier = Modifier.padding(end = Tokens.Spacing.xs.dp).size(48.dp)) {
                    Icon(painterResource(EzpzIcons.search), contentDescription = "Search", tint = accent, modifier = Modifier.size(Tokens.Size.icon.dp))
                }
            }
        }
    }
}

/** What the open field takes, shown under it while nothing is typed: a phone's field has no room for an example in its placeholder. */
@Composable
private fun SpotlightHint() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("A grid at any precision, or a lat/long in any notation", style = MaterialTheme.typography.bodySmall)
            for (example in EXAMPLES) {
                Text(example, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The round button's shape (half its height round) turning into the open field's corners as [progress] runs. */
private class SpotlightShape(private val progress: State<Float>) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = lerp(size.minDimension / 2, with(density) { SPOTLIGHT_CORNER.toPx() }, progress.value).coerceAtMost(size.minDimension / 2)
        return RoundedCornerShape(radius).createOutline(size, layoutDirection, density)
    }
}

private const val PLACEHOLDER = "Search by grid or lat/long"
private val EXAMPLES = listOf("16S GC 28864 55349", "34.5457, -84.1234", "34°32'44\"N 84°07'24\"W")
private const val SCRIM_ALPHA = 0.5f
/** How far down the room between the status bar and the keyboard the open field sits, as a share of what it leaves. */
private const val SPOTLIGHT_AT = 0.3f
internal val SPOTLIGHT_WIDTH = 600.dp
internal val SPOTLIGHT_HEIGHT = 64.dp
private val SPOTLIGHT_CORNER = 20.dp

/** How long the search takes to open or fold (standard easing). */
internal const val SPOTLIGHT_MILLIS = 260
