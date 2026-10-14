package app.ezpztac.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens
import app.ezpztac.planning.SlopeProfile
import kotlin.math.roundToInt

/** Flat ground is not drawn as a cliff: the height axis spans at least this many feet. */
private const val MIN_SPAN_FT = 30.0

/** What the height axis of a graph spans, in feet: the ground's lowest to highest, widened about its middle to [MIN_SPAN_FT]. */
internal fun profileSpan(line: SlopeProfile.Line): Pair<Double, Double>? {
    val known = line.elevationsFt.filterNotNull()
    if (known.size < 2) return null
    val low = known.min()
    val high = known.max()
    val span = maxOf(high - low, MIN_SPAN_FT)
    val middle = (low + high) / 2
    return (middle - span / 2) to (middle + span / 2)
}

/**
 * The helicopter over each graph: a UH-60's outline in side view (nose to the right, the way the approach flies) and from behind (the crew's
 * left on the left), in units of its own box. The web's (`HELI_SIDE`, `HELI_REAR` in `feature/terrain/SlopeProfile.jsx`), path for path.
 */
internal data class HeliArt(val width: Float, val height: Float, val paths: List<String>)

internal object HeliOutlines {
    val SIDE = HeliArt(
        28f, 16f,
        listOf(
            "M2 1.2H26V2.6H2Z",
            "M13.3 2.6H14.7V5H13.3Z",
            "M9 5H19.5C23 5 25.5 7.2 26 10C25.6 12 24 13 21 13H10C8.6 13 8 12 8 10.8V6C8 5.4 8.4 5 9 5Z",
            "M1 7.6L8.2 8L8.2 10.2L1 9.4Z",
            "M0.6 4.6L2.4 4.6L2.6 9.6L0.6 9.6Z",
            "M9.5 14.4H23V15.4H9.5Z",
            "M12 13H13V14.4H12Z",
            "M19.5 13H20.5V14.4H19.5Z",
        ),
    )
    val REAR = HeliArt(
        26f, 16f,
        listOf(
            "M1 1.2H25V2.6H1Z",
            "M12.3 2.6H13.7V5H12.3Z",
            "M9.5 5H16.5C17.9 5 19 6.1 19 7.5V11C19 12.4 17.9 13.5 16.5 13.5H9.5C8.1 13.5 7 12.4 7 11V7.5C7 6.1 8.1 5 9.5 5Z",
            "M8.6 12.6L9.6 13.4L6.4 15.6L5.4 14.8Z",
            "M17.4 12.6L16.4 13.4L19.6 15.6L20.6 14.8Z",
        ),
    )

    /** The outlines are drawn this much larger than their own units (in dp), as on the web (in px). */
    const val SCALE = 1.25f
}

/** The graph keeps this much room above the ground for the helicopter; the helicopter keeps this far above the ground under it. */
internal val GRAPH_HEADROOM = 24.dp
private const val HELI_CLEAR_DP = 2f

/** The approach's helicopter is over the first part of the line, kept clear of the highest ground there. */
private const val APPROACH_SHARE = 0.15

/**
 * Where a helicopter goes over a graph [widthPx] wide whose ground runs from [topPx] (its highest, [span]'s top) to [bottomPx]: its top-left
 * corner, in pixels. Along the landing heading ([side]) it is on the approach end, [insetPx] in from the left; across it, over the middle of
 * the LZ. Either way just clear ([clearPx]) of the highest ground under it, never above the graph. The web's `heliPlacement`.
 */
internal fun heliTopLeft(
    line: SlopeProfile.Line, side: Boolean, span: Pair<Double, Double>, widthPx: Float, topPx: Float, bottomPx: Float,
    artWidthPx: Float, artHeightPx: Float, clearPx: Float, insetPx: Float,
): Offset {
    val first = line.distancesFt.first()
    val last = line.distancesFt.last()
    fun x(d: Double) = ((d - first) / (last - first) * widthPx).toFloat()
    fun y(h: Double) = (bottomPx - (h - span.first) / (span.second - span.first) * (bottomPx - topPx)).toFloat()
    fun highestY(from: Double, to: Double): Float {
        val within = line.elevationsFt.indices.mapNotNull { i -> line.elevationsFt[i]?.takeIf { line.distancesFt[i] in from..to } }
        val known = within.ifEmpty { line.elevationsFt.filterNotNull() }
        return known.minOf { y(it) }
    }
    if (side) {
        val ground = highestY(first, first + (last - first) * APPROACH_SHARE)
        return Offset(insetPx, maxOf(0f, ground - artHeightPx - clearPx))
    }
    val (from, to) = line.lz?.let { it[0] to it[1] } ?: (-(last - first) * 0.05 to (last - first) * 0.05)
    val ground = highestY(from, to)
    return Offset(x((from + to) / 2) - artWidthPx / 2, maxOf(0f, ground - artHeightPx - clearPx))
}

/** The ground as runs of points that have heights: a gap where the terrain has none is a gap in the line, never a guess. */
internal fun profileRuns(line: SlopeProfile.Line): List<List<Pair<Double, Double>>> {
    val runs = mutableListOf<List<Pair<Double, Double>>>()
    var run = mutableListOf<Pair<Double, Double>>()
    line.elevationsFt.forEachIndexed { i, h ->
        if (h == null) {
            if (run.size > 1) runs += run
            run = mutableListOf()
        } else {
            run += line.distancesFt[i] to h
        }
    }
    if (run.size > 1) runs += run
    return runs
}

/**
 * The slope profile, under the slope tile: the ground along the landing heading (approach to departure, the LZ shaded) and across it
 * (left to right), each with its sentence. It opens and closes ([open], [onOpenChange]) from a button drawn as the app's dropdowns are
 * (`Choice`: an outlined button across the panel, a chevron at its end that points down while closed and up while open); open, the graphs
 * sit in a box under it, and a screen reader hears the two sentences with the button, the graphs being pictures of them.
 */
@Composable
internal fun SlopeProfileTile(profile: SlopeProfile.Profile, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        OutlinedButton(
            onClick = { onOpenChange(!open) },
            shape = RoundedCornerShape(Tokens.Radius.md.dp),
            contentPadding = PaddingValues(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.md.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.Size.touchTarget.dp)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = if (open) "Slope profile, heights in feet. ${profile.along.words}. ${profile.across.words}." else "Slope profile, heights in feet"
                    stateDescription = if (open) "Open" else "Closed"
                    onClick(label = if (open) "Close" else "Open") { onOpenChange(!open); true }
                },
        ) {
            Text("Slope profile", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("ft", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Tokens.Spacing.sm.dp))
            Icon(painterResource(if (open) EzpzIcons.expandLess else EzpzIcons.expandMore), contentDescription = null)
        }
        if (open) {
            Surface(
                Modifier.fillMaxWidth().clearAndSetSemantics {},
                shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    ProfileGraph(profile.along, "Approach", "Departure", dashed = false, heli = HeliOutlines.SIDE)
                    ProfileGraph(profile.across, "Left", "Right", dashed = true, heli = HeliOutlines.REAR)
                }
            }
        }
    }
}

/**
 * One profile: its sentence; the heights of its top and bottom in a gutter to its left; the ground as a line over a shaded fill, the LZ as a
 * band, room above for the helicopter ([heli]: side view on the approach, from behind over the LZ) that says which way the aircraft comes and
 * faces; and its two ends named under it.
 */
@Composable
private fun ProfileGraph(line: SlopeProfile.Line, start: String, end: String, dashed: Boolean, heli: HeliArt) {
    val span = profileSpan(line) ?: return
    val ground = MaterialTheme.colorScheme.onSurface
    val lz = EzpzTheme.status.warning
    val accent = EzpzTheme.status.accent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // Tall enough that its two height labels stay well apart at any text size, with the helicopter's room above the ground.
    val labelLine = with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
    val graphHeight = maxOf(86.dp, GRAPH_HEADROOM + labelLine * 3)
    val outlines = remember(heli) { heli.paths.map { PathParser().parsePathString(it).toPath() } }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text(line.words, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            // The gutter: the heights the graph spans, level with the lines they name, as wide as the wider of them.
            Box(Modifier.height(graphHeight)) {
                Text(
                    span.second.roundToInt().toString(), Modifier.align(Alignment.TopEnd).offset(y = GRAPH_HEADROOM - labelLine / 2),
                    style = MaterialTheme.typography.labelSmall, color = muted,
                )
                Text(span.first.roundToInt().toString(), Modifier.align(Alignment.BottomEnd), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Canvas(Modifier.fillMaxWidth().height(graphHeight)) {
                    val top = GRAPH_HEADROOM.toPx()
                    val bottom = size.height
                    val first = line.distancesFt.first()
                    val last = line.distancesFt.last()
                    fun x(d: Double) = ((d - first) / (last - first) * size.width).toFloat()
                    fun y(h: Double) = (bottom - (h - span.first) / (span.second - span.first) * (bottom - top)).toFloat()
                    line.lz?.let { (a, b) ->
                        val left = x(a)
                        drawRect(lz.copy(alpha = 0.18f), Offset(left, top), Size(x(b) - left, bottom - top))
                        val edge = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                        drawRect(lz.copy(alpha = 0.7f), Offset(left, top), Size(x(b) - left, bottom - top), style = edge)
                    }
                    for (run in profileRuns(line)) {
                        val fill = Path().apply {
                            moveTo(x(run.first().first), bottom)
                            run.forEach { (d, h) -> lineTo(x(d), y(h)) }
                            lineTo(x(run.last().first), bottom)
                            close()
                        }
                        drawPath(fill, ground.copy(alpha = 0.16f))
                        val path = Path().apply {
                            run.forEachIndexed { i, (d, h) -> if (i == 0) moveTo(x(d), y(h)) else lineTo(x(d), y(h)) }
                        }
                        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())) else null
                        drawPath(path, ground, style = Stroke(width = (if (dashed) 1.25f else 1.75f).dp.toPx(), join = StrokeJoin.Round, pathEffect = effect))
                    }
                    // The helicopter: the accent with a dark edge, so it reads over the ground's fill and the LZ's band alike.
                    val unit = HeliOutlines.SCALE * density.density
                    val at = heliTopLeft(
                        line, side = heli === HeliOutlines.SIDE, span = span, widthPx = size.width, topPx = top, bottomPx = bottom,
                        artWidthPx = heli.width * unit, artHeightPx = heli.height * unit, clearPx = HELI_CLEAR_DP * density.density, insetPx = 2.dp.toPx(),
                    )
                    withTransform({
                        translate(at.x, at.y)
                        scale(unit, unit, pivot = Offset.Zero)
                    }) {
                        for (outline in outlines) {
                            drawPath(outline, accent)
                            drawPath(outline, Color.Black.copy(alpha = 0.75f), style = Stroke(width = 0.5f, join = StrokeJoin.Round))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(start, style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(end, style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}
