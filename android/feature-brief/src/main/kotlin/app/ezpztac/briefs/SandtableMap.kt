package app.ezpztac.briefs

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.ezpztac.brief.SandtableSteps
import app.ezpztac.map.CameraState
import app.ezpztac.map.MapHost
import app.ezpztac.map.MapProjection
import app.ezpztac.map.ScreenPoint
import app.ezpztac.model.LatLon
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Draws a [SandtableScene]'s shapes on the map: each line on its own width, colour and opacity (a route over its casing), the LZ/PZ in focus's boundary, and the
 * dots (route points, aircraft, threats), each in the order the scene gives. Thin on purpose, like the app's other overlays: *what* is drawn is the scene, which is
 * tested; this only hands it to MapLibre, which needs a GPU and cannot be tried here.
 */
class SandtableOverlay {
    private var source: GeoJsonSource? = null
    private var latest: SandtableScene = SandtableScene.EMPTY

    /** Adds the layers to a freshly loaded style. A style that replaces another has none of them, so this runs for each. */
    fun install(style: Style) {
        // Ours from an earlier install on this style (a layer made again over the same map) go first: a source added twice would end the app.
        listOf(DOTS, AREA_LINE, AREA_FILL, LINES).forEach { style.removeLayer(it) }
        style.removeSource(SOURCE)
        val geoJson = GeoJsonSource(SOURCE)
        source = geoJson
        style.addSource(geoJson)
        val role = { value: String -> Expression.eq(Expression.get("role"), Expression.literal(value)) }
        fun number(key: String) = Expression.toNumber(Expression.get(key))
        fun colour(key: String) = Expression.toColor(Expression.get(key))
        style.addLayer(
            LineLayer(LINES, SOURCE).withFilter(role("line")).withProperties(
                PropertyFactory.lineColor(colour("colour")), PropertyFactory.lineWidth(number("width")), PropertyFactory.lineOpacity(number("opacity")),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineSortKey(number("order")),
            ),
        )
        style.addLayer(
            FillLayer(AREA_FILL, SOURCE).withFilter(role("area")).withProperties(PropertyFactory.fillColor(colour("colour")), PropertyFactory.fillOpacity(number("fillOpacity"))),
        )
        style.addLayer(
            LineLayer(AREA_LINE, SOURCE).withFilter(role("area")).withProperties(
                PropertyFactory.lineColor(colour("colour")), PropertyFactory.lineWidth(number("stroke")), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(DOTS, SOURCE).withFilter(role("dot")).withProperties(
                // MapLibre draws a ring outside the radius, an SVG circle's on it: the fill is half a ring smaller, so the ring's middle is the scene's radius.
                PropertyFactory.circleRadius(number("fillRadius")),
                PropertyFactory.circleColor(colour("fill")), PropertyFactory.circleOpacity(number("fillOpacity")),
                PropertyFactory.circleStrokeColor(colour("colour")), PropertyFactory.circleStrokeWidth(number("stroke")), PropertyFactory.circleStrokeOpacity(number("opacity")),
                PropertyFactory.circleSortKey(number("order")),
            ),
        )
        show(latest)
    }

    /** Draws [scene]; an empty one takes the figure off the map. */
    fun show(scene: SandtableScene) {
        latest = scene
        source?.setGeoJson(scene.geoJson())
    }

    private companion object {
        const val SOURCE = "sandtable"
        const val LINES = "sandtable-lines"
        const val AREA_FILL = "sandtable-area-fill"
        const val AREA_LINE = "sandtable-area-line"
        const val DOTS = "sandtable-dots"
    }
}

/**
 * Draws a step of the live sandtable on [host]'s map: its shapes ([SandtableOverlay]). Composed for as long as the map is (an empty scene draws nothing), so its layers
 * are added to each style once, above the planning layers composed before it.
 */
@Composable
fun SandtableLayer(host: MapHost, scene: SandtableScene) {
    val overlay = remember(host) { SandtableOverlay().also { host.onStyle(it::install) } }
    LaunchedEffect(overlay, scene) { overlay.show(scene) }
}

/** A mark of the figure where it falls on the screen. */
data class PlacedMark(val mark: FigureMark, val at: ScreenPoint)

/** A label of the figure where its point falls on the screen. */
data class PlacedLabel(val label: FigureLabel, val at: ScreenPoint)

/**
 * Where the figure's marks and words fall on the screen of [view], in the order they are drawn, as Leaflet stacks them: a marker's z-index is its place down the
 * screen plus its offset ([FigureMark.rank]: the landing arrow −100, an aircraft's 0, a square 100, a rally point 200), in the web's pixels (dp here), so the
 * one lower on the screen is over the one above it, and a square over an arrow on its point; the words over all of the marks, the same way. What is well off the
 * screen is left out ([MARGIN_DP]: an arrow reaches 96 dp from its point).
 */
object SandtableMarks {
    const val MARGIN_DP = 120.0

    fun marks(scene: SandtableScene, view: MapProjection, density: Double): List<PlacedMark> =
        scene.marks.map { PlacedMark(it, view.toScreen(it.at)) }.filter { onScreen(it.at, view, density) }.sortedBy { Math.round(it.at.y / density) + it.mark.rank }

    fun labels(scene: SandtableScene, view: MapProjection, density: Double): List<PlacedLabel> =
        scene.labels.map { PlacedLabel(it, view.toScreen(it.at)) }.filter { onScreen(it.at, view, density) }.sortedBy { it.at.y }

    private fun onScreen(at: ScreenPoint, view: MapProjection, density: Double): Boolean {
        val margin = MARGIN_DP * density
        return at.x >= -margin && at.x <= view.widthPx + margin && at.y >= -margin && at.y <= view.heightPx + margin
    }
}

/**
 * Draws the figure's marks and words over the map ([SandtableMarks]), as the web's sandtable lays its icons and labels over Leaflet's: a raster style has no glyphs
 * or symbols, so they are Compose, put where [MapProjection] says their points are. They take no touches and say nothing to a screen reader (the step's card says
 * what the figure shows), as the web's panes are hidden from one.
 */
@Composable
fun SandtableMarksLayer(host: MapHost, scene: SandtableScene, modifier: Modifier = Modifier) {
    val camera = host.camera
    var size by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize().onSizeChanged { size = it }.clearAndSetSemantics {}) {
        if (camera == null || size == IntSize.Zero || (scene.marks.isEmpty() && scene.labels.isEmpty())) return@Canvas
        drawFigure(scene, camera, measurer)
    }
}

/** The figure's marks and words for [camera] on this canvas: the drawing [SandtableMarksLayer] does, apart so a test can draw it without a map. */
internal fun DrawScope.drawFigure(scene: SandtableScene, camera: CameraState, measurer: TextMeasurer) {
    val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density.toDouble())
    SandtableMarks.marks(scene, view, density.toDouble()).forEach { drawMark(it, camera.bearingDegrees) }
    SandtableMarks.labels(scene, view, density.toDouble()).forEach { drawLabel(it, measurer) }
}

private fun DrawScope.drawMark(placed: PlacedMark, bearing: Double) {
    fun Float.dp(): Float = this * density
    fun Double.dp(): Float = (this * density).toFloat()
    val at = Offset(placed.at.x.toFloat(), placed.at.y.toFloat())
    when (val mark = placed.mark) {
        // A dark square edged inside in the role's colour (the web's 3 px border, box-sizing border-box).
        is FigureMark.Square -> {
            val side = mark.size.dp()
            val edge = 3f.dp()
            drawRect(CASING, topLeft = at - Offset(side / 2, side / 2), size = Size(side, side))
            drawRect(colourOf(mark.colour), topLeft = at - Offset(side / 2 - edge / 2, side / 2 - edge / 2), size = Size(side - edge, side - edge), style = Stroke(edge))
        }
        // The web's 26 by 24 triangle, its point 16 above the place's foot (iconAnchor 13, 16).
        is FigureMark.Rally -> translate(at.x - 13f.dp(), at.y - 16f.dp()) {
            val path = Path().apply {
                moveTo(13f.dp(), 2f.dp())
                lineTo(24f.dp(), 22f.dp())
                lineTo(2f.dp(), 22f.dp())
                close()
            }
            drawPath(path, CASING)
            drawPath(path, colourOf(SandtableSteps.Colours.RALLY), style = Stroke(3f.dp(), join = StrokeJoin.Round))
        }
        // Drawn pointing north, 22 dp wide, then turned about the end that sits on the place: its tail (an aircraft's heading) or its tip (arriving along the
        // landing heading). Turned with the map, should the presenter turn it.
        is FigureMark.Arrow -> {
            val length = mark.length.dp()
            val half = 11f.dp()
            val origin = if (mark.fromTip) 0f else length
            val colour = colourOf(mark.colour)
            translate(at.x - half, at.y - origin) {
                rotate((mark.heading - bearing).toFloat(), pivot = Offset(half, origin)) {
                    drawLine(CASING, Offset(half, length), Offset(half, 9f.dp()), 7f.dp(), StrokeCap.Round)
                    drawLine(colour, Offset(half, length), Offset(half, 9f.dp()), 3.5f.dp(), StrokeCap.Round)
                    val head = Path().apply {
                        moveTo(half, 0f)
                        lineTo(20f.dp(), 14f.dp())
                        lineTo(2f.dp(), 14f.dp())
                        close()
                    }
                    drawPath(head, colour)
                    drawPath(head, CASING, style = Stroke(1.5f.dp()))
                }
            }
        }
    }
}

// The web's label: 10 px right of its point (13 for the LZ/PZ in focus), its middle on the point's, bold, in a soft dark glow (its text-shadow, `0 0 3px` twice and
// `0 0 2px`). Compose draws one shadow a pass, so the words are drawn once for each, then over a thin dark edge (one more than the web) that keeps them off light
// ground, then plain.
private fun DrawScope.drawLabel(placed: PlacedLabel, measurer: TextMeasurer) {
    fun Float.dp(): Float = this * density
    val label = placed.label
    val (size, weight, gap) = when (label.size) {
        LabelSize.SMALL -> Triple(11.sp, FontWeight.Bold, 10f)
        LabelSize.NORMAL -> Triple(12.sp, FontWeight.ExtraBold, 10f)
        LabelSize.LARGE -> Triple(15.sp, FontWeight.ExtraBold, 13f)
    }
    val style = TextStyle(fontSize = size, fontWeight = weight)
    val layout = measurer.measure(label.text, style, maxLines = 1, softWrap = false)
    val topLeft = Offset(placed.at.x.toFloat() + gap.dp(), placed.at.y.toFloat() - layout.size.height / 2f)
    val colour = colourOf(label.colour)
    listOf(3f, 3f, 2f).forEach { blur -> drawText(layout, color = colour, topLeft = topLeft, shadow = Shadow(CASING, Offset.Zero, blur.dp())) }
    drawText(layout, color = CASING, topLeft = topLeft, drawStyle = Stroke(1f.dp(), join = StrokeJoin.Round))
    drawText(layout, color = colour, topLeft = topLeft)
}

private val CASING = Color(0xFF101317)

// "#rrggbb", the scene's colours (all the web's, or a route's checked as such); white for anything else.
private fun colourOf(hex: String): Color = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.White

/**
 * Where what lies over the sandtable's map is, in the window's pixels: the map's own box ([map]) and each box laid over it ([covers]: the script card, the constants
 * box, the steps where they lie over the map, the navigation bar). A figure is fitted clear of them ([SandtableSteps.fitInsets]), as the web measures its card and
 * box when it flies.
 */
@Stable
class SandtableBoxes {
    var map: Rect? by mutableStateOf(null)
    internal val covers = mutableStateMapOf<String, Rect>()

    /** The boxes over the map with a size. */
    fun covering(): List<Rect> = covers.values.filter { it.width > 0f && it.height > 0f }
}

/** Reports this piece's box to [boxes] under [key] while it is laid out, and takes it away when it goes. */
@Composable
fun Modifier.coversMap(boxes: SandtableBoxes, key: String): Modifier {
    DisposableEffect(boxes, key) { onDispose { boxes.covers.remove(key) } }
    return onGloballyPositioned { boxes.covers[key] = it.boundsInRoot() }
}

/** Reports the map's own box to [boxes]. */
fun Modifier.isSandtableMap(boxes: SandtableBoxes): Modifier = onGloballyPositioned { boxes.map = it.boundsInRoot() }

/**
 * The map flown to each step (the web's SandtableLayer): once a step is shown, and again only if a step that had no area to show gets one, not at every change to
 * the plan under it (the presenter may have moved the map to point at something). Fitted clear of what lies over the map ([SandtableBoxes]), north up, over 0.9 s
 * as the web's, or at once when the device's animations are off (the web's reduced motion). The view the map had is given back when the sandtable ends (this leaves
 * the screen), without a flight, which also ends one under way; then [handover] lets a flight that waited for it go.
 */
@Composable
fun SandtableFlights(host: MapHost, view: SandtableSteps.View?, boxes: SandtableBoxes, handover: SandtableHandover? = null) {
    val density = LocalDensity.current.density
    val flown = remember { FlownStep() }
    // Where the map was when the sandtable began, kept through the activity being made again (the text size or the language changed; a turn of the tablet does not
    // make it again): what it gives back is that, not where the map is then.
    val before = rememberSaveable(saver = CAMERA_SAVER) { mutableStateOf(host.camera) }
    DisposableEffect(host, handover) {
        handover?.up = true
        onDispose {
            flown.clear()
            before.value?.let(host::jumpTo)
            handover?.gone()
        }
    }
    val stepId = view?.stepId
    val bounds = view?.bounds
    LaunchedEffect(host, stepId, bounds != null) {
        if (view == null || !flown.wants(stepId, bounds != null)) return@LaunchedEffect
        if (bounds == null) {
            flown.flew(stepId, hasBounds = false)                       // a step with nothing to show is seen; it is flown to if it gets an area
            return@LaunchedEffect
        }
        // The screen laid out for this step (the card's height is its own), the map's box once it has one, and the map itself once it is made (after the activity
        // was made again, its first camera comes first).
        withFrameNanos { }
        val map = snapshotFlow { boxes.map?.takeIf { it.width > 0f && it.height > 0f } }.filterNotNull().first()
        snapshotFlow { host.camera }.filterNotNull().first()
        val target = SandtableFit.of(bounds, map, boxes.covering(), density) ?: return@LaunchedEffect
        host.flyTo(target.center, target.zoom, bearing = 0.0, durationMs = if (ValueAnimator.areAnimatorsEnabled()) FLIGHT_MS else 0L)
        // Noted once flown: a flight cut short (the step's area gone and back while it waited) is flown again.
        flown.flew(stepId, hasBounds = true)
    }
}

/**
 * The map handed back by the sandtable: a flight asked for while its frame is still up but it has ended (its workspace changed: the person's own LZ/PZ opening as a
 * pack is left) waits for the view the sandtable gives back, and goes after it, as the web's flight to the new workspace's LZ/PZ follows its view put back.
 */
@Stable
class SandtableHandover {
    internal var up = false
    private var waiting: (() -> Unit)? = null

    /** Runs [fly] now, or once the sandtable's frame has given the view back if it is still up (the last asked for then). */
    fun afterSandtable(fly: () -> Unit) {
        if (up) waiting = fly else fly()
    }

    internal fun gone() {
        up = false
        val next = waiting
        waiting = null
        next?.invoke()
    }
}

/** A camera as saved state: its place, zoom and bearing (what [MapHost.jumpTo] gives back), or none. */
private val CAMERA_SAVER = listSaver<MutableState<CameraState?>, Double>(
    save = { state -> state.value?.let { listOf(it.center.lat, it.center.lon, it.zoom, it.bearingDegrees) }.orEmpty() },
    restore = { saved -> mutableStateOf(if (saved.size == 4) CameraState(LatLon(saved[0], saved[1]), saved[2], saved[3]) else null) },
)

/** Which step the map was last flown for, and whether it had an area then: the web's `flown` ref, noted once a flight has been asked for, not before. */
internal class FlownStep {
    private var stepId: String? = null
    private var hadBounds = false
    private var any = false

    /** Whether to fly for [id] (which [hasBounds] or not): not for the step flown already, unless it had no area then and has one now. */
    fun wants(id: String?, hasBounds: Boolean): Boolean = !(any && stepId == id && (hadBounds || !hasBounds))

    /** The map was flown for [id] ([hasBounds]), or [id] was seen with no area to fly to. */
    fun flew(id: String?, hasBounds: Boolean) {
        any = true
        stepId = id
        hadBounds = hasBounds
    }

    fun clear() {
        any = false
        stepId = null
        hadBounds = false
    }
}

/** Where a step's area is flown on a map of [map]'s box with [covers] over it, all in the window's pixels at [density]: the web's measure and fit, in dp. */
object SandtableFit {
    fun of(bounds: SandtableSteps.Bounds, map: Rect, covers: List<Rect>, density: Float): SandtableView? {
        fun box(r: Rect) = SandtableSteps.Box(r.left / density.toDouble(), r.top / density.toDouble(), r.right / density.toDouble(), r.bottom / density.toDouble())
        val mapBox = box(map)
        val insets = SandtableSteps.fitInsets(mapBox, covers.map(::box))
        return SandtableCamera.fit(bounds, mapBox.right - mapBox.left, mapBox.bottom - mapBox.top, insets)
    }
}

/** How long a flight to a step takes: the web's 0.9 s. */
private const val FLIGHT_MS = 900L
