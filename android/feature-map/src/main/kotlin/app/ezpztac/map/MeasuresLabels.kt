package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.model.LatLon
import app.ezpztac.planning.Flots
import app.ezpztac.planning.PhaseLines
import app.ezpztac.planning.Pixel
import app.ezpztac.planning.PixelBox
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A backstop's arrow as it is drawn on the screen (the web's `arrowOnScreen`), in the screen's pixels: from its [tail] to its [head], the shaft stopping inside the
 * head at [shaftEnd], its [middle] where the move handle is, [ux] and [uy] the way it points, [angleDeg] that way as an angle clockwise from east.
 */
data class ArrowOnScreen(val tail: Pixel, val head: Pixel, val shaftEnd: Pixel, val middle: Pixel, val length: Double, val ux: Double, val uy: Double, val angleDeg: Double)

/** A FLOT on the screen: its scallops, run by run, and where its label goes, already moved off the line. */
data class FlotOnScreen(val runs: List<List<Pixel>>, val label: Pixel?)

/** A phase line's label at one end: centred at [x], [y], then moved by [shiftX] and [shiftY] of its own size (the web's `labelShift`, a fraction of half of it). */
data class PhaseLabelOnScreen(val x: Double, val y: Double, val shiftX: Double, val shiftY: Double)

/**
 * What the map draws of the measures in pixels, worked out as the web works it out in Leaflet's CSS pixels: every length the web gives in CSS pixels is a dp here,
 * so it is multiplied by [MapProjection.density]. Pure, so it is tested and shared with [MeasuresHitTest].
 */
object MeasuresOnScreen {
    /** How far short of the target an arrow on it stops, how far back from its point the shaft ends, the label beyond its tail, and a FLOT's label off its line. */
    const val TARGET_GAP_DP = 16.0
    const val SHAFT_STOP_DP = 10.0
    const val BACKSTOP_LABEL_DP = 24.0
    const val FLOT_LABEL_DP = 15.0
    const val PHASE_LABEL_GAP_DP = 5.0

    /** How far past the view a FLOT's scallops are worked out, so a pan shows them before they are worked out again. */
    const val VIEW_MARGIN_DP = 500.0

    fun pixel(view: MapProjection, at: LatLon): Pixel = view.toScreen(at).let { Pixel(it.x, it.y) }

    fun arrow(backstop: BackstopDrawn, target: LatLon?, view: MapProjection): ArrowOnScreen? {
        val arrow = backstop.arrow ?: return null
        val start = pixel(view, arrow.tail)
        val end = pixel(view, arrow.tip)
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length == 0.0 || !length.isFinite()) return null
        val ux = dx / length
        val uy = dy / length
        val d = view.density
        // Less of a gap the further the tip is from the target, so an arrow does not jump as it is picked up from there.
        val fromTarget = target?.let { pixel(view, it) }?.let { hypot(end.x - it.x, end.y - it.y) } ?: Double.POSITIVE_INFINITY
        val gap = min(max(0.0, TARGET_GAP_DP * d - fromTarget), length / 2)
        val drawn = length - gap
        val along = { px: Double -> Pixel(start.x + ux * px, start.y + uy * px) }
        return ArrowOnScreen(
            tail = start, head = along(drawn), shaftEnd = along(max(0.0, drawn - SHAFT_STOP_DP * d)), middle = along(drawn / 2), length = drawn,
            ux = ux, uy = uy, angleDeg = Math.toDegrees(atan2(dy, dx)),
        )
    }

    fun flot(line: SetLineDrawn, view: MapProjection): FlotOnScreen {
        val pixels = line.points.map { pixel(view, it) }
        val margin = VIEW_MARGIN_DP * view.density
        val within = PixelBox(-margin, -margin, view.widthPx + margin, view.heightPx + margin)
        val runs = Flots.scallopRuns(pixels, Flots.SCALLOP_PX * view.density, line.flipped, within)
        val label = Flots.labelSpot(pixels, line.flipped)?.let { Pixel(it.x + it.awayX * FLOT_LABEL_DP * view.density, it.y + it.awayY * FLOT_LABEL_DP * view.density) }
        return FlotOnScreen(runs, label)
    }

    fun phaseLabels(line: SetLineDrawn, view: MapProjection): List<PhaseLabelOnScreen> {
        val ends = PhaseLines.ends(line.points.map { pixel(view, it) })
        if (ends.size != 2) return emptyList()
        val gap = PHASE_LABEL_GAP_DP * view.density
        return ends.map { end ->
            val (fx, fy) = PhaseLines.labelShift(end.ux, end.uy)
            PhaseLabelOnScreen(end.x + end.ux * gap, end.y + end.uy * gap, fx, fy)
        }
    }

    /** Whether a point is near enough the view to be worth drawing a label or a handle at. */
    fun near(view: MapProjection, p: Pixel, margin: Double = 200.0 * view.density): Boolean =
        p.x >= -margin && p.y >= -margin && p.x <= view.widthPx + margin && p.y <= view.heightPx + margin
}

private val BACKSTOP_RED = Color(0xFFE60000)
private val FLOT_BLUE = Color(0xFF2F8FFF)
private val FLOT_CASING = Color(0xFF06121F)
private val PHASE_INK = Color(0xFF0B0F14)
private val HANDLE_FILL = Color(0xFF2F5BFF)

/**
 * The part of the measures the map cannot draw from GeoJSON: a backstop's arrow and its reading, a FLOT's scallops and label, a phase line's `PL <NAME>` at each end,
 * and the handles of what is held. It takes no touches (`HomeViewModel` hit tests with [MeasuresHitTest]) and is hidden from a screen reader: the held bar says it.
 * Drawn on a plain `Box`, never a `Surface`, which would take the touches meant for the map (§14).
 */
@Composable
fun MeasuresLabelsLayer(host: MapHost, scene: MeasuresScene, modifier: Modifier = Modifier) = MeasuresLabels(host.camera, scene, modifier)

/** [MeasuresLabelsLayer] for [camera]: apart from the map's host, so it can be drawn over a stand-in in a test. */
@Composable
internal fun MeasuresLabels(camera: CameraState?, scene: MeasuresScene, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density.toDouble()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }.clearAndSetSemantics { }) {
        if (camera == null || size == IntSize.Zero || scene.isEmpty) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density)
        val arrows = scene.backstops.mapNotNull { b -> MeasuresOnScreen.arrow(b, scene.target, view)?.let { b to it } }
        val flots = scene.lines.filter { it.ref.isFlot }.map { it to MeasuresOnScreen.flot(it, view) }
        Canvas(Modifier.fillMaxSize()) {
            flots.forEach { (_, drawn) -> drawn.runs.forEach { run -> drawRun(run) } }
            arrows.forEach { (_, arrow) -> drawArrow(arrow) }
            // The handles of what is held and may be picked up: the arrow's middle and its head, or each point of a line.
            scene.heldBackstop?.takeIf { it.movable }?.let { held ->
                arrows.firstOrNull { it.first.id == held.id }?.second?.let { arrow ->
                    drawHandle(arrow.middle, 9.dp.toPx())
                    drawHandle(arrow.head, 12.dp.toPx())
                }
            }
            scene.heldLine?.takeIf { it.movable }?.points?.forEach { p ->
                val at = MeasuresOnScreen.pixel(view, p)
                if (MeasuresOnScreen.near(view, at)) drawHandle(at, 9.dp.toPx())
            }
        }
        arrows.forEach { (backstop, arrow) ->
            val reading = backstop.reading ?: return@forEach
            val at = Pixel(arrow.tail.x - arrow.ux * MeasuresOnScreen.BACKSTOP_LABEL_DP * density, arrow.tail.y - arrow.uy * MeasuresOnScreen.BACKSTOP_LABEL_DP * density)
            if (MeasuresOnScreen.near(view, at)) MapTag(at, reading, Color.White, Color(0xD1000000), FontWeight.SemiBold, horizontal = 7, vertical = 2, radius = 4)
        }
        flots.forEach { (_, drawn) ->
            val at = drawn.label ?: return@forEach
            if (MeasuresOnScreen.near(view, at)) MapTag(at, "FLOT", Color(0xFF8CC4FF), Color(0xDB06121F), FontWeight.Bold, horizontal = 6, vertical = 1, radius = 4, spacing = 0.04)
        }
        scene.lines.filter { !it.ref.isFlot }.forEach { line ->
            val text = line.label ?: return@forEach
            MeasuresOnScreen.phaseLabels(line, view).forEach { label ->
                val at = Pixel(label.x, label.y)
                if (MeasuresOnScreen.near(view, at)) {
                    MapTag(at, text, PHASE_INK, Color(0xF0FFFFFF), FontWeight.ExtraBold, horizontal = 6, vertical = 1, radius = 3, spacing = 0.04, border = PHASE_INK, shiftX = label.shiftX, shiftY = label.shiftY)
                }
            }
        }
    }
}

private fun DrawScope.drawRun(run: List<Pixel>) {
    if (run.size < 2) return
    val path = Path().apply {
        moveTo(run[0].x.toFloat(), run[0].y.toFloat())
        for (i in 1 until run.size) lineTo(run[i].x.toFloat(), run[i].y.toFloat())
    }
    drawPath(path, FLOT_CASING.copy(alpha = 0.85f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, FLOT_BLUE, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The arrow as the web's Backstop.jsx draws it: a red shaft on a white one, and its head (`20,10 2,2 6,10 2,18`, its point on the head) red over a white edge. */
private fun DrawScope.drawArrow(arrow: ArrowOnScreen) {
    val tail = Offset(arrow.tail.x.toFloat(), arrow.tail.y.toFloat())
    val shaftEnd = Offset(arrow.shaftEnd.x.toFloat(), arrow.shaftEnd.y.toFloat())
    drawLine(Color.White, tail, shaftEnd, 5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(BACKSTOP_RED, tail, shaftEnd, 3.dp.toPx(), cap = StrokeCap.Round)
    val unit = 1.dp.toPx()
    val angle = Math.toRadians(arrow.angleDeg)
    val c = cos(angle)
    val s = sin(angle)
    // The polygon's own corners less its anchor (20, 10), turned to the arrow and put on its head.
    val corner = { x: Double, y: Double ->
        val px = (x - 20) * unit
        val py = (y - 10) * unit
        Offset((arrow.head.x + px * c - py * s).toFloat(), (arrow.head.y + px * s + py * c).toFloat())
    }
    val head = Path().apply {
        corner(20.0, 10.0).let { moveTo(it.x, it.y) }
        listOf(2.0 to 2.0, 6.0 to 10.0, 2.0 to 18.0).forEach { (x, y) -> corner(x, y).let { lineTo(it.x, it.y) } }
        close()
    }
    drawPath(head, Color.White, style = Stroke(2.dp.toPx(), join = StrokeJoin.Miter))
    drawPath(head, BACKSTOP_RED)
}

private fun DrawScope.drawHandle(at: Pixel, radius: Float) {
    val centre = Offset(at.x.toFloat(), at.y.toFloat())
    drawCircle(HANDLE_FILL.copy(alpha = 0.55f), radius, centre)
    drawCircle(Color.White, radius, centre, style = Stroke(2.dp.toPx()))
}

/** A label over the map, centred on [at] and moved by [shiftX] and [shiftY] of half its own size. */
@Composable
private fun MapTag(
    at: Pixel,
    text: String,
    ink: Color,
    background: Color,
    weight: FontWeight,
    horizontal: Int,
    vertical: Int,
    radius: Int,
    spacing: Double = 0.0,
    border: Color? = null,
    shiftX: Double = 0.0,
    shiftY: Double = 0.0,
) {
    val shape = RoundedCornerShape(radius.dp)
    val framed = Modifier
        .layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                val w = placeable.width
                val h = placeable.height
                placeable.place(
                    (at.x - w / 2.0 + shiftX * w / 2.0).roundToInt(),
                    (at.y - h / 2.0 + shiftY * h / 2.0).roundToInt(),
                )
            }
        }
        .background(background, shape)
        .let { if (border != null) it.border(1.dp, border, shape) else it }
        .padding(horizontal = horizontal.dp, vertical = vertical.dp)
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = weight, letterSpacing = (12 * spacing).sp, lineHeight = (12 * 1.3).sp),
        modifier = framed,
    )
}
