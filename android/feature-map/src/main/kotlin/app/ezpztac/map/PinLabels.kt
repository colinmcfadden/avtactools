package app.ezpztac.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Tokens
import kotlin.math.roundToInt

/** A name laid over the map beside a point, at a place on the screen; [dim] for an LZ/PZ that is open but not the one being worked on. */
data class PinLabel(val text: String, val at: ScreenPoint, val emphasised: Boolean, val description: String, val dim: Boolean = false)

/**
 * The names of the points on the map, worked out for a camera. A raster style has no glyphs, so a name is not map text: it is Compose, put where
 * [MapProjection] says the point is.
 *
 * A route's named points are always named (there are few, and the name is what the crew reads). Local points can be thousands, so they are named only
 * from [MIN_POINT_ZOOM] in, only [MAX_POINT_LABELS] at most, those nearest the middle of the view first, and one that would sit on a name already placed is
 * left out. The one being held is always named.
 */
object PinLabels {
    /** Below this zoom the map is too wide for a name to belong to one point. */
    const val MIN_POINT_ZOOM = 12.0
    const val MAX_POINT_LABELS = 40

    /** How far off the screen a label may be and still be kept, so one that is sliding in is not missing for a frame. */
    private const val MARGIN_PX = 48.0

    /** What a name takes, in dp, to keep two apart: a name is about this wide for each character, this tall, and starts this far from its dot. */
    private const val CHAR_DP = 7.5
    private const val PAD_DP = 12.0
    private const val HEIGHT_DP = 20.0

    /** The names of the named points of the visible routes, those on or near the screen. */
    fun routes(scene: RouteScene, view: MapProjection): List<PinLabel> = scene.labelled.mapNotNull { (route, pin) ->
        if (pin.name.isBlank()) return@mapNotNull null
        val at = view.toScreen(pin.at)
        if (!onScreen(at, view)) return@mapNotNull null
        PinLabel(pin.name, at, emphasised = pin.selected, description = "${route.name}: ${pin.name}")
    }

    /** The names of the open LZ/PZs not being worked on, at their targets: each always named (there are few, and the name says which is which). */
    fun others(scene: OtherLzScene, view: MapProjection): List<PinLabel> = scene.lzs.mapNotNull { lz ->
        val target = lz.target ?: return@mapNotNull null
        if (lz.name.isBlank()) return@mapNotNull null
        val at = view.toScreen(target)
        if (!onScreen(at, view)) return@mapNotNull null
        PinLabel(lz.name, at, emphasised = false, description = "Also open: ${lz.name}", dim = true)
    }

    /**
     * Whether [finger] is on the chip that names [text] beside the point at [at] (about as [PinChip] draws it, for a screen of [density] pixels to the point): a
     * tap on the name of an open LZ/PZ is a tap on it.
     */
    fun chipContains(at: ScreenPoint, text: String, density: Double, finger: ScreenPoint): Boolean {
        if (text.isBlank()) return false
        val left = at.x + 8 * density
        val top = at.y - HEIGHT_DP / 2 * density
        return finger.x in left..(left + (PAD_DP + CHAR_DP * text.length) * density) && finger.y in top..(top + HEIGHT_DP * density)
    }

    /** The names of the local points to show at [zoom] on a screen of [view], nearest the middle first, none on another. */
    fun points(scene: PointScene, view: MapProjection, zoom: Double, density: Double): List<PinLabel> {
        if (scene.isEmpty) return emptyList()
        val placed = ArrayList<Pair<PointPin, ScreenPoint>>()
        for (pin in scene.pins) {
            if (pin.name.isBlank() && !pin.selected) continue
            val at = view.toScreen(pin.at)
            if (onScreen(at, view)) placed += pin to at
        }
        val middleX = view.widthPx / 2
        val middleY = view.heightPx / 2
        // The held one first (it is always named), then those nearest the middle.
        placed.sortWith(compareBy({ !it.first.selected }, { Math.hypot(it.second.x - middleX, it.second.y - middleY) }))

        val taken = ArrayList<Box4>()
        val labels = ArrayList<PinLabel>()
        for ((pin, at) in placed) {
            if (!pin.selected && (zoom < MIN_POINT_ZOOM || labels.size >= MAX_POINT_LABELS)) continue
            val text = pin.name.ifBlank { "(unnamed)" }
            val box = Box4(at.x + 8 * density, at.y - HEIGHT_DP / 2 * density, (PAD_DP + CHAR_DP * text.length) * density, HEIGHT_DP * density)
            if (!pin.selected && taken.any { it.overlaps(box) }) continue
            taken += box
            labels += PinLabel(text, at, emphasised = pin.selected, description = "Local point $text")
        }
        return labels
    }

    private fun onScreen(at: ScreenPoint, view: MapProjection) = at.x >= -MARGIN_PX && at.x <= view.widthPx + MARGIN_PX && at.y >= -MARGIN_PX && at.y <= view.heightPx + MARGIN_PX

    private class Box4(val x: Double, val y: Double, val w: Double, val h: Double) {
        fun overlaps(o: Box4) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    }
}

/**
 * Draws the names of the route points and the local points over the map, and of the open LZ/PZs not being worked on ([others], dimmed, under the rest). It takes
 * no touches: what is under a name is still tapped (an LZ/PZ's name is tested as part of it, [OtherLzHitTest]). A name sits to the right of its dot on a dark
 * chip, readable on satellite and on the pale charts.
 */
@Composable
fun PinLabelsLayer(host: MapHost, routes: RouteScene, points: PointScene, modifier: Modifier = Modifier, others: OtherLzScene = OtherLzScene.EMPTY) {
    val camera = host.camera
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero || (routes.isEmpty && points.isEmpty && others.isEmpty)) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density)
        (PinLabels.others(others, view) + PinLabels.routes(routes, view) + PinLabels.points(points, view, camera.zoom, density)).forEach { PinChip(it) }
    }
}

@Composable
private fun PinChip(label: PinLabel) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        color = if (label.emphasised) scheme.primary else scheme.surface,
        contentColor = if (label.emphasised) scheme.onPrimary else if (label.dim) scheme.onSurfaceVariant else scheme.onSurface,
        modifier = Modifier
            .offset { IntOffset(label.at.x.roundToInt() + 8.dp.roundToPx(), label.at.y.roundToInt() - 10.dp.roundToPx()) }
            .alpha(if (label.dim) 0.8f else 0.92f)
            .semantics { contentDescription = label.description },
    ) {
        Text(label.text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = Tokens.Spacing.xs.dp, vertical = 2.dp), maxLines = 1)
    }
}
