package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.ezpztac.geo.GridBox
import app.ezpztac.geo.GridLabel
import app.ezpztac.geo.GridResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.roundToInt

/**
 * What floats over the map, by name, in the window's pixels: the map's screen reports each piece where it lays it out ([chromeBox]), and the
 * grid's labels keep off them, as the web's keep off the map's controls.
 */
class MapChromeBoxes {
    internal val boxes = mutableStateMapOf<String, Rect>()

    /** The boxes with a size, in the window's pixels. */
    fun shown(): List<Rect> = boxes.values.filter { it.width > 0f && it.height > 0f }
}

/**
 * Reports where this piece of what floats over the map is to [boxes] under [key], and takes it away when it goes, or while it is not [shown] (the phone's row while
 * its frame is hidden: what is not laid out is not told by being placed, so it is taken away here).
 */
@Composable
fun Modifier.chromeBox(boxes: MapChromeBoxes?, key: String, shown: Boolean = true): Modifier {
    if (boxes == null) return this
    DisposableEffect(boxes, key, shown) {
        if (!shown) boxes.boxes.remove(key)
        onDispose { boxes.boxes.remove(key) }
    }
    return if (shown) onGloballyPositioned { boxes.boxes[key] = it.boundsInRoot() } else this
}

/**
 * Hands the grid's lines to MapLibre: halos, then lines, finest first and zone boundaries last, all directly above the base map, so every
 * planning graphic and its labels are drawn over the grid (the web's panes sit between the tiles and the overlays). Light lines with a thin
 * dark halo, seen on imagery, topo and the VFR chart alike; 100 km lines heavier; zone and band boundaries heaviest, white dashes. Not a colour:
 * routes, the LZ boundary and threat rings take colours, and a coloured grid line could be read as one of them (the web's mgrsGrid.css).
 */
class MgrsGridOverlay {
    private var source: GeoJsonSource? = null
    private var latest = MgrsGridScene.geoJson(GridResult.EMPTY)

    fun install(style: Style) {
        val geoJson = GeoJsonSource(SOURCE)
        source = geoJson
        style.addSource(geoJson)
        var below = BASE_LAYER
        fun add(layer: LineLayer) {
            if (style.getLayer(below) != null) style.addLayerAbove(layer, below) else style.addLayerAt(layer, 0)
            below = layer.id
        }
        for (kind in KINDS) {
            add(line("$HALO-$kind", kind).withProperties(
                PropertyFactory.lineColor(Color.Black.argb(if (kind == "zone") 0.6f else 0.45f)),
                PropertyFactory.lineWidth(HALO_WIDTHS.getValue(kind)),
            ))
        }
        for (kind in KINDS) {
            val properties = listOfNotNull(
                PropertyFactory.lineColor(Color.White.argb(LINE_ALPHA.getValue(kind))),
                PropertyFactory.lineWidth(LINE_WIDTHS.getValue(kind)),
                // MapLibre measures dashes in line widths: the web's 12 px dash and 6 px gap at 2.5 px wide.
                if (kind == "zone") PropertyFactory.lineDasharray(arrayOf(12f / 2.5f, 6f / 2.5f)) else null,
            )
            add(line("$LINE-$kind", kind).withProperties(*properties.toTypedArray()))
        }
        show(latest)
    }

    /** Shows the lines of [geoJson] ([MgrsGridScene.geoJson], built off the main thread: a grid at its caps is tens of thousands of points). */
    fun show(geoJson: String) {
        latest = geoJson
        source?.setGeoJson(geoJson)
    }

    private fun line(id: String, kind: String) = LineLayer(id, SOURCE)
        .withFilter(Expression.eq(Expression.get("kind"), Expression.literal(kind)))
        .withProperties(PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_BUTT))

    private fun Color.argb(alpha: Float): String =
        "rgba(${(red * 255).roundToInt()}, ${(green * 255).roundToInt()}, ${(blue * 255).roundToInt()}, $alpha)"

    private companion object {
        const val SOURCE = "mgrs-grid"
        const val HALO = "mgrs-grid-halo"
        const val LINE = "mgrs-grid-line"
        const val BASE_LAYER = "base"
        val KINDS = listOf("minor", "major", "zone")
        val HALO_WIDTHS = mapOf("minor" to 3f, "major" to 4f, "zone" to 5f)
        val LINE_WIDTHS = mapOf("minor" to 1f, "major" to 1.75f, "zone" to 2.5f)
        val LINE_ALPHA = mapOf("minor" to 0.85f, "major" to 0.92f, "zone" to 1f)
    }
}

/**
 * The MGRS grid over [host]'s map while [shown]: its lines on the map (installed with every base map) and its labels over it, keeping off
 * what [chrome] reports. Worked out again once the view has settled, never during a pan or a zoom, off the main thread; the lines cover a
 * quarter of the view round it, so a pan shows no bare map before then, and the labels move with the map meanwhile. Hidden from a screen
 * reader: it is a picture of what the readout under the crosshair says in words.
 */
@Composable
fun MgrsGridLayer(host: MapHost, shown: Boolean, modifier: Modifier = Modifier, chrome: MapChromeBoxes? = null) {
    val overlay = remember(host) { MgrsGridOverlay().also { host.onStyle(it::install) } }
    MgrsGridLabels({ host.camera }, shown, chrome, modifier, onGrid = { _, geoJson -> overlay.show(geoJson) })
}

/** The grid for the [camera] the map has now, worked out once it settles: handed to [onGrid] with its lines' GeoJSON (the map's), and its labels drawn here. */
@OptIn(FlowPreview::class)
@Composable
internal fun MgrsGridLabels(camera: () -> CameraState?, shown: Boolean, chrome: MapChromeBoxes?, modifier: Modifier = Modifier, onGrid: (GridResult, String) -> Unit) {
    var grid by remember { mutableStateOf(GridResult.EMPTY) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density.toDouble()

    LaunchedEffect(shown, size, density) {
        if (!shown || size == IntSize.Zero) {
            grid = GridResult.EMPTY
            onGrid(GridResult.EMPTY, MgrsGridScene.geoJson(GridResult.EMPTY))
            return@LaunchedEffect
        }
        snapshotFlow { camera() to chrome?.shown().orEmpty() }
            .debounce(SETTLE_MS)
            .collectLatest { (camera, boxes) ->
                camera ?: return@collectLatest
                val avoid = boxes.map { GridBox((it.left - origin.x).toDouble(), (it.top - origin.y).toDouble(), (it.right - origin.x).toDouble(), (it.bottom - origin.y).toDouble()) }
                val view = MgrsGridScene.viewOf(camera, size.width.toDouble(), size.height.toDouble(), density, avoidPx = avoid) ?: return@collectLatest
                val (next, geoJson) = withContext(Dispatchers.Default) { MgrsGridScene.gridFor(view).let { it to MgrsGridScene.geoJson(it) } }
                grid = next
                onGrid(next, geoJson)
            }
    }

    Box(
        modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clearAndSetSemantics {},
    ) {
        val now = camera()
        if (!shown || now == null || size == IntSize.Zero) return@Box
        val view = MapProjection(now, size.width.toDouble(), size.height.toDouble(), density)
        // Keyed by what each is, so a pill keeps its own measured size when the grid is worked out again.
        grid.labels.forEach { label -> key(label.kind, label.edge, label.text, label.lat, label.lon) { GridPill(label, view) } }
    }
}

// A label: an 11 sp pill on a translucent dark ground, monospace, its anchor on the screen where the map is now (the web's mgrsGrid.css).
@Composable
private fun GridPill(label: GridLabel, view: MapProjection) {
    var pill by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { EDGE_GAP.toPx().toDouble() }
    // The grid keeps its labels apart by the web's 11 px pill (MgrsGrid.CHAR_PX and the rest), so the pill keeps that size whatever the
    // person's text size: the readout under the crosshair is the grid's words, and it grows with their text.
    val fontSize = with(density) { 11.dp.toSp() }
    val anchor = view.toScreen(app.ezpztac.model.LatLon(label.lat, label.lon))
    val at = MgrsGridScene.pillTopLeft(label, anchor, pill.width.toDouble(), pill.height.toDouble(), gap)
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .onSizeChanged { pill = it }
            .background(PILL, shape)
            .then(if (label.kind == "square") Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape) else Modifier)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Text(
            label.text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.SemiBold),
            color = if (label.kind == "gzd") Color.White else TEXT,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val SETTLE_MS = 200L
private val EDGE_GAP = 4.dp
private val PILL = Color(red = 12, green = 16, blue = 22, alpha = (0.62f * 255).roundToInt())
private val TEXT = Color(0xFFE7EAEE)
