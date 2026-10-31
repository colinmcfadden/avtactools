package app.ezpztac.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens
import kotlin.math.roundToInt

/**
 * The phone's search (the owner's decision of 2026-10-13): folded into a round button at the start of the row over the map, and open across the whole row to
 * type a grid or a coordinate. Held by the screen, so the frame can fold it: a tap on the map, the frame hiding. [text] is what was typed, kept for the next
 * opening. A tablet's search bar is always open and keeps its words here too.
 */
@Stable
class MapSearchState(expanded: Boolean = false, text: String = "") {
    var expanded by mutableStateOf(expanded)
        private set

    var text by mutableStateOf(text)

    fun open() {
        expanded = true
    }

    fun close() {
        expanded = false
    }

    /** A tap on the map: with the search open it folds it and does nothing else (true); with it folded the tap is the map's (false). */
    fun foldOnTap(): Boolean = expanded.also { if (it) close() }

    companion object {
        val Saver = listSaver<MapSearchState, Any>(
            save = { listOf(it.expanded, it.text) },
            restore = { MapSearchState(it[0] as Boolean, it[1] as String) },
        )
    }
}

/** The search, kept through a turn of the phone (open or folded, and what was typed). */
@Composable
fun rememberMapSearchState(): MapSearchState = rememberSaveable(saver = MapSearchState.Saver) { MapSearchState() }

/**
 * The phone's row over the map (the owner's decision of 2026-10-13): the folded search, [chips] (the workspace, Import and the Library) and [account] at the end,
 * each on a surface of its own with the map showing between them, one height, lined up. The search opens across the whole row, over the chips and the account,
 * with its field focused and the keyboard up: a back arrow at its start folds it, the search action at its end (and the keyboard's) looks for what was typed,
 * and folds it when [onSearch] found the place (the map flies there). A place that was not found leaves it open, [error] said under the row by the screen.
 * System Back folds it too, and folding it lets go of the error.
 */
@Composable
internal fun MapTopRow(
    search: MapSearchState,
    error: String?,
    onSearch: (String) -> Boolean,
    onClearError: () -> Unit,
    account: @Composable () -> Unit,
    chips: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = animateFloatAsState(if (search.expanded) 1f else 0f, tween(SEARCH_MILLIS, easing = FastOutSlowInEasing), label = "search")
    // Composed only while it is open, or folding: what was typed stays in the state either way.
    val drawn by remember { derivedStateOf { progress.value > 0f } }
    val said by rememberUpdatedState(error)
    // Folded, the search says nothing more: an error left from the last try would sit under a row that no longer asks anything.
    LaunchedEffect(search.expanded) { if (!search.expanded && said != null) onClearError() }
    // Composed while open, so it is the newest Back and is asked first.
    if (search.expanded) BackHandler { search.close() }
    Box(modifier.fillMaxWidth().height(MapChrome.ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
        // Under the open search: faded out, and neither read nor touched (the open search's surface takes every touch over it).
        Row(
            Modifier.fillMaxSize()
                .then(if (search.expanded) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = 1f - progress.value },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            SearchButton(onClick = search::open)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), content = chips)
            // A touch target wide, so its middle is the middle of the map's buttons under it.
            Box(Modifier.size(MapChrome.BUTTON), contentAlignment = Alignment.Center) { account() }
        }
        if (search.expanded || drawn) OpenSearch(search, error, onSearch, onClearError, progress)
    }
}

/** The folded search: a round button with the magnifier, as tall as the chips beside it (a 48 dp touch target, as every clickable surface is). */
@Composable
private fun SearchButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(MapChrome.CHIPS_HEIGHT).semantics { contentDescription = "Search a grid or lat/long" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = EzpzTheme.status.accent,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(EzpzIcons.search), contentDescription = null, modifier = Modifier.size(Tokens.Size.iconSmall.dp)) }
    }
}

/**
 * The open search, across the row: it grows from the folded button to the row's width ([progress], 0 folded to 1 open, read as it is laid out and drawn), its
 * contents laid out at the whole width and shown as it widens, the back arrow where the magnifier was.
 */
@Composable
private fun OpenSearch(search: MapSearchState, error: String?, onSearch: (String) -> Boolean, onClearError: () -> Unit, progress: State<Float>) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    // The cursor at the end of what was typed before, so it can be added to or taken back.
    var field by remember { mutableStateOf(TextFieldValue(search.text, TextRange(search.text.length))) }
    // The row's width, which the bar grows to: the contents are laid out at it whatever the bar's width now.
    val whole = remember { IntArray(1) }
    LaunchedEffect(search.expanded) {
        if (search.expanded) {
            focus.requestFocus()
            keyboard?.show()
        } else {
            if (focused) focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val submit = { if (onSearch(field.text)) search.close() }
    Surface(
        modifier = Modifier.layout { measurable, constraints ->
            val height = MapChrome.CHIPS_HEIGHT.roundToPx()
            whole[0] = constraints.maxWidth
            val width = (height + (constraints.maxWidth - height) * progress.value).roundToInt().coerceIn(minOf(height, constraints.maxWidth), constraints.maxWidth)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        },
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(maxOf(whole[0], constraints.maxWidth), constraints.maxHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .graphicsLayer { alpha = progress.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = search::close, modifier = Modifier.size(MapChrome.CHIPS_HEIGHT)) {
                Icon(painterResource(EzpzIcons.arrowBack), contentDescription = "Close the search", modifier = Modifier.size(Tokens.Size.iconSmall.dp))
            }
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.32.sp, color = MaterialTheme.colorScheme.onSurface,
            )
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    search.text = it.text
                    if (error != null) onClearError()
                },
                modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Grids are capitals; a coordinate is digits, which the setting leaves alone.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) {
                            Text(
                                "MGRS grid or lat/long", style = style.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(onClick = submit, modifier = Modifier.size(MapChrome.CHIPS_HEIGHT)) {
                Icon(painterResource(EzpzIcons.search), contentDescription = "Search", tint = EzpzTheme.status.accent, modifier = Modifier.size(Tokens.Size.iconSmall.dp))
            }
        }
    }
}

/** How long the search takes to open or fold (standard easing). */
internal const val SEARCH_MILLIS = 200
