package app.ezpztac.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The heights a [PanelSheet] rests at (docs/native-design: "a partial-expanded peek, half and full"). */
enum class SheetDetent {
    /** Down to its header: the map has the screen. */
    Lowered,

    /** About half the space: the panel and the map side by side. */
    Half,

    /** Up to just below the map's controls at the top, which stay in view. */
    Full,
}

/**
 * Where a [PanelSheet]'s top edge rests at each [SheetDetent], in pixels from the top of the space it is laid out in. Plain arithmetic, so
 * it is tried without a screen.
 */
data class SheetAnchors(val loweredPx: Float, val halfPx: Float, val fullPx: Float, val spacePx: Float) {
    fun offsetOf(detent: SheetDetent): Float = when (detent) {
        SheetDetent.Lowered -> loweredPx
        SheetDetent.Half -> halfPx
        SheetDetent.Full -> fullPx
    }

    /** How much of the space the sheet covers at [detent], from its edge to the bottom. */
    fun heightOf(detent: SheetDetent): Float = spacePx - offsetOf(detent)

    /** The detent whose edge is nearest [offsetPx]. */
    fun nearest(offsetPx: Float): SheetDetent = SheetDetent.entries.minBy { abs(offsetOf(it) - offsetPx) }

    /**
     * Where a sheet let go at [offsetPx], moving at [velocityPx] pixels a second (down is positive), comes to rest. A flick (at least [flickPx])
     * goes on to the next detent the way it was moving; a slower release goes on if it has come half way, and back if not. This is foundation's
     * own rule for a drag let go, kept here because a fling the sheet's content hands over (a list already at its top) must land the same way.
     */
    fun settleAt(offsetPx: Float, velocityPx: Float, flickPx: Float): SheetDetent {
        if (velocityPx == 0f) return nearest(offsetPx)
        val down = velocityPx > 0
        val positions = SheetDetent.entries.map { it to offsetOf(it) }
        val ahead = positions.filter { (_, at) -> if (down) at >= offsetPx else at <= offsetPx }.minByOrNull { (_, at) -> abs(at - offsetPx) }
        val behind = positions.filter { (_, at) -> if (down) at <= offsetPx else at >= offsetPx }.minByOrNull { (_, at) -> abs(at - offsetPx) }
        return when {
            ahead == null -> behind?.first ?: nearest(offsetPx)
            behind == null || abs(velocityPx) >= flickPx -> ahead.first
            abs(offsetPx - behind.second) >= abs(ahead.second - behind.second) / 2 -> ahead.first
            else -> behind.first
        }
    }

    companion object {
        /**
         * The anchors for a space [heightPx] tall. [topLimitPx] at its top is what must stay in view (the search bar and chips over the map), so
         * the sheet rises no higher; lowered, [loweredHeightPx] of it shows; at half, [halfFraction] of the space. On a space too small for all
         * three they close up rather than cross: never above the limit, never below the lowered height.
         */
        fun of(heightPx: Float, topLimitPx: Float, loweredHeightPx: Float, halfFraction: Float = PanelSheetDefaults.HalfFraction): SheetAnchors {
            val full = topLimitPx.coerceIn(0f, heightPx)
            val lowered = (heightPx - loweredHeightPx).coerceIn(full, heightPx)
            val half = (heightPx * (1 - halfFraction)).coerceIn(full, lowered)
            return SheetAnchors(loweredPx = lowered, halfPx = half, fullPx = full, spacePx = heightPx)
        }
    }
}

object PanelSheetDefaults {
    /** Lowered, the handle and the header show: 28 dp of handle, the 48 dp header, and the 4 dp above a panel's first card, none of the card. */
    val LoweredHeight: Dp = 80.dp

    /** At half the sheet takes this much of its space: the design's sheet over a phone's map leaves the top third to the map. */
    const val HalfFraction: Float = 0.6f

    /** How long the sheet takes to settle at a detent, and so how long whatever follows it (the map's middle) takes too. */
    const val SettleMillis: Int = 300

    val Motion: AnimationSpec<Float> = tween(SettleMillis, easing = FastOutSlowInEasing)

    /** A release faster than this goes on to the next detent: foundation's own threshold for an anchored drag. */
    val Flick: Dp = 125.dp
}

/**
 * The state of a [PanelSheet]: which detent it rests at, and where its edge is while it moves. Kept across a turn of the phone and the process
 * being ended (its detent: where it is in between is not worth keeping).
 */
@Stable
class PanelSheetState(initial: SheetDetent) {
    internal val draggable = AnchoredDraggableState(initial)

    /** The detent it rests at, or last rested at while it moves. */
    val settled: SheetDetent get() = draggable.settledValue

    /** The detent it is at or is heading for. */
    val target: SheetDetent get() = draggable.targetValue

    /**
     * The detent it rests at or is settling at (let go, or sent somewhere by a tap): not one a finger is only dragging it across. What follows the
     * sheet's height (the map's middle) follows this, so it moves as the sheet settles and not back and forth under a drag.
     */
    val heading: SheetDetent get() = if (draggable.isAnimationRunning) draggable.targetValue else draggable.settledValue

    /** Its top edge now, in pixels from the top of its space; NaN until it has been laid out. */
    val offsetPx: Float get() = draggable.offset

    /** Where each detent is, once the sheet has been laid out. */
    var anchors: SheetAnchors? by mutableStateOf(null)
        private set

    /** Called as the sheet is laid out: the space it is given decides where its detents are. Keeps the detent it was heading for. */
    internal fun updateAnchors(new: SheetAnchors) {
        if (new == anchors) return
        anchors = new
        draggable.updateAnchors(
            DraggableAnchors {
                SheetDetent.Lowered at new.loweredPx
                SheetDetent.Half at new.halfPx
                SheetDetent.Full at new.fullPx
            },
            draggable.targetValue,
        )
    }

    suspend fun animateTo(detent: SheetDetent) = draggable.animateTo(detent, PanelSheetDefaults.Motion)

    suspend fun snapTo(detent: SheetDetent) = draggable.snapTo(detent)

    /** What a tap on the handle does: one step up, and from the top back down to the header (the web's sheet does the same). */
    fun stepUp(): SheetDetent = when (settled) {
        SheetDetent.Lowered -> SheetDetent.Half
        SheetDetent.Half -> SheetDetent.Full
        SheetDetent.Full -> SheetDetent.Lowered
    }

    /** What the header's button does: open to full height, and from full height back to half. */
    fun fullOrHalf(): SheetDetent = if (settled == SheetDetent.Full) SheetDetent.Half else SheetDetent.Full

    companion object {
        val Saver: Saver<PanelSheetState, String> = Saver(save = { it.settled.name }, restore = { PanelSheetState(SheetDetent.valueOf(it)) })
    }
}

@Composable
fun rememberPanelSheetState(initial: SheetDetent = SheetDetent.Lowered): PanelSheetState =
    rememberSaveable(saver = PanelSheetState.Saver) { PanelSheetState(initial) }

/**
 * The redesign's panel over the map (docs/native-design, "The panel"): a standard sheet that rests lowered to its header, at half, or at full
 * height below the map's controls, with a handle, a [header], the [content] and, pinned under it, an optional [footer]. It fills the space it
 * is given and draws itself at its edge; only what shows is laid out, so a footer stays in sight at every height.
 *
 * Dragging the sheet moves it, and so does scrolling its content: a list scrolled up raises the sheet first, and one already at its top lowers
 * it, as Material's sheet does. A tap on the handle steps it up; [raiseLabel] and [lowerLabel] name the same moves for a screen reader.
 * [topLimit] is how far below the top of the space the sheet may rise.
 */
@Composable
fun PanelSheet(
    state: PanelSheetState,
    topLimit: Dp,
    modifier: Modifier = Modifier,
    loweredHeight: Dp = PanelSheetDefaults.LoweredHeight,
    halfFraction: Float = PanelSheetDefaults.HalfFraction,
    raiseLabel: String = "Raise the panel",
    lowerLabel: String = "Lower the panel",
    header: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flickPx = with(LocalDensity.current) { PanelSheetDefaults.Flick.toPx() }
    val scroll = remember(state, flickPx) { SheetScrollConnection(state, flickPx) { scope.launch { state.animateTo(it) } } }
    val fling = AnchoredDraggableDefaults.flingBehavior(state.draggable, positionalThreshold = { it / 2 }, animationSpec = PanelSheetDefaults.Motion)
    Surface(
        modifier = modifier
            // Fills the space it is given and lays out only the part that shows, from its edge down.
            .layout { measurable, constraints ->
                val height = constraints.maxHeight
                val anchors = SheetAnchors.of(height.toFloat(), topLimit.toPx(), loweredHeight.toPx(), halfFraction)
                state.updateAnchors(anchors)
                val edge = state.offsetPx.takeUnless { it.isNaN() } ?: anchors.offsetOf(state.settled)
                val top = edge.roundToInt().coerceIn(0, height)
                val sheet = measurable.measure(Constraints.fixed(constraints.maxWidth, height - top))
                layout(constraints.maxWidth, height) { sheet.placeRelative(0, top) }
            }
            .nestedScroll(scroll)
            .anchoredDraggable(state.draggable, Orientation.Vertical, flingBehavior = fling),
        shape = RoundedCornerShape(topStart = Tokens.Radius.sheet.dp, topEnd = Tokens.Radius.sheet.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = if (state.stepUp() == SheetDetent.Lowered) lowerLabel else raiseLabel) {
                        scope.launch { state.animateTo(state.stepUp()) }
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(raiseLabel) { scope.launch { state.animateTo(if (state.settled == SheetDetent.Lowered) SheetDetent.Half else SheetDetent.Full) }; true }
                                .takeIf { state.settled != SheetDetent.Full },
                            CustomAccessibilityAction(lowerLabel) { scope.launch { state.animateTo(if (state.settled == SheetDetent.Full) SheetDetent.Half else SheetDetent.Lowered) }; true }
                                .takeIf { state.settled != SheetDetent.Lowered },
                        )
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier.padding(top = 14.dp, bottom = 10.dp).size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
            }
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            footer?.invoke()
        }
    }
}

/**
 * The top of a panel: its [title] (a heading for a screen reader), an optional [subtitle] under it, and [actions] at the end (48 dp each, as
 * [PanelAction] draws them).
 */
@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** An icon button in a panel's header or a row: 48 dp, the icon in the secondary text colour, and [description] for a screen reader. */
@Composable
fun PanelAction(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Scrolling the sheet's content moves the sheet first (Material's rule): up raises it until it is at full height, then the content scrolls; down
 * scrolls the content to its top, then lowers the sheet. A fling ends where [SheetAnchors.settleAt] says.
 */
private class SheetScrollConnection(
    private val state: PanelSheetState,
    private val flickPx: Float,
    private val settle: (SheetDetent) -> Unit,
) : NestedScrollConnection {
    // Not before the sheet has been laid out: until it has anchors it has no edge to move.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0 && source == NestedScrollSource.UserInput && !state.offsetPx.isNaN()) Offset(0f, state.draggable.dispatchRawDelta(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && !state.offsetPx.isNaN()) Offset(0f, state.draggable.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val anchors = state.anchors ?: return Velocity.Zero
        val offset = state.offsetPx
        // Flung up while not yet at full height: the sheet takes the fling (and all of it, so the content does not scroll under it as it rises).
        return if (available.y < 0 && !offset.isNaN() && offset > anchors.fullPx) {
            settle(anchors.settleAt(offset, available.y, flickPx))
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val anchors = state.anchors ?: return Velocity.Zero
        val offset = state.offsetPx
        if (offset.isNaN()) return Velocity.Zero
        settle(anchors.settleAt(offset, available.y, flickPx))
        return available
    }
}
