package app.ezpztac.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * The slope profile tile, under the slope tile: the ground along the landing heading (approach to departure, the LZ shaded) and across it
 * (left to right), each with its sentence. Its header opens and closes it ([open], [onOpenChange]); open, a screen reader hears the two
 * sentences with it, the graphs being pictures of them.
 */
@Composable
internal fun SlopeProfileTile(profile: SlopeProfile.Profile, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = if (open) "Close" else "Open") { onOpenChange(!open) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = if (open) "Slope profile. ${profile.along.words}. ${profile.across.words}." else "Slope profile"
                        stateDescription = if (open) "Open" else "Closed"
                        onClick(label = if (open) "Close" else "Open") { onOpenChange(!open); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Slope profile (ft)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    painterResource(if (open) EzpzIcons.expandLess else EzpzIcons.expandMore), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                Column(
                    Modifier.padding(bottom = Tokens.Spacing.md.dp).clearAndSetSemantics {},
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
                ) {
                    ProfileGraph(profile.along, "Approach", "Departure", dashed = false)
                    ProfileGraph(profile.across, "Left", "Right", dashed = true)
                }
            }
        }
    }
}

@Composable
private fun ProfileGraph(line: SlopeProfile.Line, start: String, end: String, dashed: Boolean) {
    val span = profileSpan(line) ?: return
    val ground = MaterialTheme.colorScheme.onSurface
    val lz = EzpzTheme.status.warning
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Tall enough that its two height labels stay well apart at any text size.
    val labelLine = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
    val graphHeight = maxOf(72.dp, labelLine * 4)
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text(line.words, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        Box(Modifier.fillMaxWidth().height(graphHeight)) {
            Canvas(Modifier.matchParentSize()) {
                val first = line.distancesFt.first()
                val last = line.distancesFt.last()
                fun x(d: Double) = ((d - first) / (last - first) * size.width).toFloat()
                fun y(h: Double) = (size.height - (h - span.first) / (span.second - span.first) * size.height).toFloat()
                line.lz?.let { (a, b) ->
                    val left = x(a)
                    drawRect(lz.copy(alpha = 0.18f), Offset(left, 0f), Size(x(b) - left, size.height))
                    val edge = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                    drawRect(lz.copy(alpha = 0.7f), Offset(left, 0f), Size(x(b) - left, size.height), style = edge)
                }
                for (run in profileRuns(line)) {
                    val fill = Path().apply {
                        moveTo(x(run.first().first), size.height)
                        run.forEach { (d, h) -> lineTo(x(d), y(h)) }
                        lineTo(x(run.last().first), size.height)
                        close()
                    }
                    drawPath(fill, ground.copy(alpha = 0.16f))
                    val path = Path().apply {
                        run.forEachIndexed { i, (d, h) -> if (i == 0) moveTo(x(d), y(h)) else lineTo(x(d), y(h)) }
                    }
                    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())) else null
                    drawPath(path, ground, style = Stroke(width = (if (dashed) 1.25f else 1.75f).dp.toPx(), join = StrokeJoin.Round, pathEffect = effect))
                }
            }
            Text(span.second.roundToInt().toString(), Modifier.align(Alignment.TopStart).padding(start = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(span.first.roundToInt().toString(), Modifier.align(Alignment.BottomStart).padding(start = 2.dp), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(start, style = MaterialTheme.typography.labelSmall, color = muted)
            Text(end, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
