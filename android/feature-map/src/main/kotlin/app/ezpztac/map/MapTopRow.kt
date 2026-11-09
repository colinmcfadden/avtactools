package app.ezpztac.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens

/**
 * The map's search: folded (the phone's round button at the start of the row over the map, a tablet's bar along the top) or open ([MapSearchSpotlight], a large
 * field in the middle of the screen, the owner's request of 2026-11-05). Held by the screen, so the frame can fold it: a tap on the map, the frame hiding.
 * [text] is what was typed, kept for the next opening.
 */
@Stable
class MapSearchState(expanded: Boolean = false, text: String = "") {
    var expanded by mutableStateOf(expanded)
        private set

    var text by mutableStateOf(text)
        private set

    /**
     * Whether the places what is typed could be are listed under the field ([PlaceList]): from an edit until one is chosen, the search
     * finds its place, or the search folds. Not kept through a turn of the phone: the list comes back with the next keystroke.
     */
    var listed by mutableStateOf(false)
        private set

    /** What was typed, which lists its places again. */
    fun edit(typed: String) {
        text = typed
        listed = typed.isNotBlank()
    }

    /** A search refused: what was typed is listed again, so readings to choose from (or why not) are there. */
    fun show() {
        listed = text.isNotBlank()
    }

    /** The list goes, what was typed stays: Escape, or a tablet's field and list both left. */
    fun hide() {
        listed = false
    }

    /** A place was chosen, or found: its grid is what the field then says, and the list goes. */
    fun settle(grid: String?) {
        if (grid != null) text = grid
        listed = false
    }

    /** Where the folded search is, in the window: the open field grows out of it and goes back into it. Read as the open field is laid out, never composed. */
    internal var origin: Rect? = null

    fun open() {
        expanded = true
    }

    fun close() {
        expanded = false
        listed = false
    }

    /** A tap on the map: with the search open it folds it and does nothing else (true); with it folded the tap is the map's (false). */
    fun foldOnTap(): Boolean {
        listed = false
        return expanded.also { if (it) close() }
    }

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
 * each on a surface of its own with the map showing between them, one height, lined up. The search opens over everything ([MapSearchSpotlight]), growing out
 * of its round button, which is not drawn while it is open; the row under it is not read then.
 */
@Composable
internal fun MapTopRow(
    search: MapSearchState,
    account: @Composable () -> Unit,
    chips: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    // In step with the open field, so the button shows again only once the field has gone back into it.
    val progress = animateFloatAsState(if (search.expanded) 1f else 0f, tween(SPOTLIGHT_MILLIS, easing = FastOutSlowInEasing), label = "search")
    Row(
        modifier.fillMaxWidth().height(MapChrome.ROW_HEIGHT)
            .then(if (search.expanded) Modifier.clearAndSetSemantics {} else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
    ) {
        SearchButton(
            onClick = search::open,
            modifier = Modifier.onGloballyPositioned { search.origin = it.boundsInWindow() }.graphicsLayer { alpha = if (progress.value > 0f) 0f else 1f },
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), content = chips)
        // A touch target wide, so its middle is the middle of the map's buttons under it.
        Box(Modifier.size(MapChrome.BUTTON), contentAlignment = Alignment.Center) { account() }
    }
}

/** The folded search: a round button with the magnifier, as tall as the chips beside it (a 48 dp touch target, as every clickable surface is). */
@Composable
private fun SearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(MapChrome.CHIPS_HEIGHT).semantics { contentDescription = "Search a grid or lat/long" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = EzpzTheme.status.accent,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(EzpzIcons.search), contentDescription = null, modifier = Modifier.size(Tokens.Size.iconSmall.dp)) }
    }
}
