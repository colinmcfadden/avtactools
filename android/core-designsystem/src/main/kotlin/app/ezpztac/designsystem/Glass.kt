package app.ezpztac.designsystem

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The glass of what floats over the map (the owner's request of 2026-11-22: "slight glass, blurred, transparent elements, to feel close to the map"). A
 * window cannot blur what MapLibre draws into its own surface, so it is translucency, not a blur: a surface lets a little of the map through, more the
 * more of the map it covers (the panel at full height), with a hairline of light along its top edge, as glass catches it. What is read on it stays on
 * opaque cards ([SectionCard], the tiles), so the words keep their contrast whatever map is under them, the VFR chart's white included.
 *
 * A translucent surface casts no elevation shadow: the platform draws the shadow under the whole shape, and it would show through as a grey cast.
 */
object Glass {
    /** The panel's opacity while it is lowered or at half: it covers little of the map, and its rows are read on it. */
    const val SHEET_RESTING = 0.97f

    /** The panel's opacity at its top edge at full height, where the map is seen through it. */
    const val SHEET_OPEN_TOP = 0.82f

    /** The panel's opacity at its foot at full height, over the bar of panels. */
    const val SHEET_OPEN_BOTTOM = 0.93f

    /** A bar floating over the map (the held object's, a drawing's, a tool's). */
    const val FLOATING = 0.9f

    /** The highlight along a glass edge: the palette's text colour, faint (white on the dark palette, red at night, ink on the light). */
    const val EDGE_ALPHA = 0.16f

    /**
     * How open the panel is, 0 resting at half or lower and 1 at full height, from where its edge is ([edgePx], from the top of its space) and where half
     * and full put it.
     */
    fun openness(edgePx: Float, halfPx: Float, fullPx: Float): Float {
        if (edgePx.isNaN() || halfPx <= fullPx) return 0f
        return ((halfPx - edgePx) / (halfPx - fullPx)).coerceIn(0f, 1f)
    }

    /** The panel's opacity at its top and foot for an [openness]. */
    fun sheetAlpha(openness: Float): Pair<Float, Float> {
        val t = openness.coerceIn(0f, 1f)
        return (SHEET_RESTING + (SHEET_OPEN_TOP - SHEET_RESTING) * t) to (SHEET_RESTING + (SHEET_OPEN_BOTTOM - SHEET_RESTING) * t)
    }
}

/** The hairline of light along a glass edge, brightest at the top and gone by the foot. */
@Composable
fun glassEdge(): BorderStroke {
    val light = MaterialTheme.colorScheme.onSurface.copy(alpha = Glass.EDGE_ALPHA)
    return BorderStroke(1.dp, Brush.verticalGradient(listOf(light, light.copy(alpha = Glass.EDGE_ALPHA / 3f), Color.Transparent)))
}

/**
 * Draws [color] behind what it modifies as glass: at [alpha] (from [alphaAt], read when drawing, so a panel being dragged draws again without being composed
 * again), the second of the pair at its foot. Drawn inside [shape].
 */
fun Modifier.glassBackground(shape: Shape, color: Color, alphaAt: () -> Pair<Float, Float>): Modifier = drawBehind {
    val (top, bottom) = alphaAt()
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, Brush.verticalGradient(listOf(color.copy(alpha = top), color.copy(alpha = bottom))))
}

/**
 * A bar or card floating over the map, as glass ([Glass]): [color] at [alpha], the light along its top edge, no shadow. It takes the touches on it, as a
 * [Surface] does, so nothing goes through to the map.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Tokens.Radius.md.dp),
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    alpha: Float = Glass.FLOATING,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.glassBackground(shape, color) { alpha to (alpha + (1f - alpha) / 2f) },
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassEdge(),
        content = content,
    )
}

/**
 * A section of a long panel page (the LZ/PZ's plan, a threat's form), as a card that stands out from the panel behind it: a heading with an [icon], what
 * it holds said in a line while it is folded ([summary]), an [action] at its end (Refresh, Undo), and its [content]. With a [foldKey] its heading folds
 * and unfolds it, kept per page while the app runs; it starts open, so nothing is hidden until the person folds it.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    summary: String? = null,
    foldKey: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(foldKey) { mutableStateOf(true) }
    val foldable = foldKey != null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
        // Two steps above the panel, edged: a card is told from the panel behind it, and the tiles on it a step above it again.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            // The whole heading folds the card (a wide target, for gloves); an action in it (Refresh, Undo) is a button of its own, taken before the heading.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(
                        if (foldable) {
                            Modifier.clickable(onClickLabel = if (open) "Fold $title" else "Unfold $title", role = Role.Button) { open = !open }
                                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = Tokens.Spacing.md.dp, end = Tokens.Spacing.sm.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                if (icon != null) {
                    Surface(shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(vertical = Tokens.Spacing.xs.dp)) {
                    Text(
                        title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!open && summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (action != null && open) action()
                if (foldable) {
                    Icon(
                        painterResource(if (open) EzpzIcons.expandLess else EzpzIcons.expandMore), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = Tokens.Spacing.md.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.md.dp),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * A small figure in a section (the design's tile, made compact so a page of them is read at a glance): its [label] over its [value] and [unit], on a tile
 * one step lighter than the card it is on. [emphasis] draws the value in the primary colour (the answer the page is for: capacity). [description] is what a
 * screen reader hears in place of the words, when they say less than it should.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    emphasis: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = Tokens.Spacing.sm.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                leading?.invoke()
                Text(
                    value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (emphasis) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (emphasis) MaterialTheme.colorScheme.primary else valueColor,
                )
                if (unit != null) {
                    Text(
                        unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * [cells] in rows of as many as fit at [minCell] each (wider with larger text), all of a row as tall as its tallest: three of the LZ/PZ's figures to a row
 * on a phone, two or one at large text, so a figure never wraps a digit.
 */
@Composable
fun TileGrid(cells: List<@Composable (Modifier) -> Unit>, modifier: Modifier = Modifier, minCell: Dp = 96.dp, gap: Dp = Tokens.Spacing.sm.dp) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = tileColumns(maxWidth, minCell * scale, gap, cells.size)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { cell -> cell(Modifier.weight(1f).fillMaxHeight()) }
                    // A short last row keeps the others' widths.
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How many tiles of at least [minCell] fit across [width] with [gap] between them: one at least, never more than there are. */
fun tileColumns(width: Dp, minCell: Dp, gap: Dp, count: Int): Int {
    if (count <= 0) return 1
    val fits = ((width + gap) / (minCell + gap)).toInt()
    return fits.coerceIn(1, count)
}

/**
 * Blurs what is behind the window this is composed in (a modal sheet's, a dialog's) on Android 12 and later, where the device allows blurs across windows
 * (a cheap phone, or one saving its battery, may not: then nothing changes). Behind the window is the map, which a window can blur though it cannot draw
 * over it, so a menu that opens over the map feels set on it. Not verifiable without a device: Robolectric draws no window blur.
 */
@Composable
fun BlurBehindWindow(radius: Dp = 18.dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, px) {
        val window = dialogWindowOf(view)
        val manager = view.context.getSystemService(WindowManager::class.java)
        if (window != null && manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val apply: (Boolean) -> Unit = { enabled ->
                window.attributes = window.attributes.also { it.blurBehindRadius = if (enabled) px else 0 }
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            apply(manager.isCrossWindowBlurEnabled)
            val listener = java.util.function.Consumer<Boolean> { apply(it) }
            manager.addCrossWindowBlurEnabledListener(listener)
            onDispose { manager.removeCrossWindowBlurEnabledListener(listener) }
        } else {
            onDispose { }
        }
    }
}

/** The window of the dialog [view] is drawn in, if it is in one (a modal sheet and a platform dialog are). */
private fun dialogWindowOf(view: View): android.view.Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

