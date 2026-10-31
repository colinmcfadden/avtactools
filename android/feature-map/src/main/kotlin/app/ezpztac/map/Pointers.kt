package app.ezpztac.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.model.LatLon
import kotlin.math.roundToInt

/** Someone else in the open Mission Pack pointing at [at] (their presence's `at`), named [label] and drawn in [color] (ARGB), keyed by their [key] (a session). */
data class MapPointer(val key: String, val at: LatLon, val label: String, val color: Long)

/**
 * The colours people are drawn in: the web's palette (Avatar.jsx), each person chosen by their id (else their name), so the same person is the same colour on the
 * web and here. Bright, to read on satellite imagery and the charts.
 */
object PersonColors {
    val PALETTE: List<Long> = listOf(0xFF5AA7D4, 0xFFE8A25C, 0xFF6FD0A8, 0xFFD98BC3, 0xFF9DB8E8, 0xFFE6B8A0, 0xFFC7D36F, 0xFFA89AE6)

    /** The web's `colorFor(key)`: over each character (a code point), 31 times the hash plus its first UTF-16 unit, kept to an unsigned 32-bit number. */
    fun of(key: String): Long {
        var hash = 0L
        var i = 0
        while (i < key.length) {
            hash = (hash * 31 + key[i].code) and 0xFFFFFFFFL
            i += Character.charCount(key.codePointAt(i))
        }
        return PALETTE[(hash % PALETTE.size).toInt()]
    }
}

/**
 * Where the pointers fall on the screen of [view]: those any of whose mark shows. A mark runs right and down from its tip (the arrow, then the name's pill beside
 * it), so a tip just left of or above the screen still shows its name, and one past its right or bottom edge shows nothing.
 */
object Pointers {
    /** How far right of its tip a mark reaches, in dp: the pill's place, its padding, and about this much for each character of the name at the person's text size. */
    private const val PILL_X = 12.0
    private const val PILL_PADDING = 20.0
    private const val CHAR = 8.0

    /** How far below its tip a mark reaches, in dp: the pill's place and its height at the person's text size. */
    private const val PILL_Y = 16.0
    private const val PILL_HEIGHT = 19.0

    fun placed(pointers: List<MapPointer>, view: MapProjection, density: Double = 1.0, fontScale: Double = 1.0): List<Pair<MapPointer, ScreenPoint>> =
        pointers.mapNotNull { pointer ->
            val at = view.toScreen(pointer.at)
            val reachRight = (PILL_X + PILL_PADDING + CHAR * pointer.label.length * fontScale) * density
            val reachDown = (PILL_Y + PILL_HEIGHT * fontScale) * density
            val shows = at.x >= -reachRight && at.x <= view.widthPx && at.y >= -reachDown && at.y <= view.heightPx
            if (shows) pointer to at else null
        }
}

/**
 * Everyone else's pointers over the map (the design's PackLive): an arrow in the person's colour with its tip on the place, and their name on a pill below and to its
 * right. It takes no touches: what is under a pointer is still tapped. Drawn under the night dimming, as the map is.
 */
@Composable
fun PointersLayer(host: MapHost, pointers: List<MapPointer>, modifier: Modifier = Modifier) {
    val camera = host.camera
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (camera == null || size == IntSize.Zero || pointers.isEmpty()) return@Box
        val view = MapProjection(camera, size.width.toDouble(), size.height.toDouble(), density.density.toDouble())
        PointerMarks(Pointers.placed(pointers, view, density.density.toDouble(), density.fontScale.toDouble()))
    }
}

/**
 * The marks at their places, left to right whatever the language: a place on the map is in pixels from its left edge, and a right-to-left layout would otherwise
 * put each mark at the mirror of its place, its name to the arrow's left.
 */
@Composable
internal fun PointerMarks(placed: List<Pair<MapPointer, ScreenPoint>>) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize()) {
            placed.forEach { (pointer, at) -> key(pointer.key) { PointerMark(pointer, at) } }
        }
    }
}

/** One person's pointer, its tip at [at] on the screen. */
@Composable
internal fun PointerMark(pointer: MapPointer, at: ScreenPoint) {
    val color = Color(pointer.color)
    Box(
        // One thing for a screen reader, in the design's words, not the name read again beside it.
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.clearAndSetSemantics { contentDescription = "${pointer.label}'s pointer" },
    ) {
        // The design's arrow, 12.6 by 20, its tip at the place, outlined so it reads on any ground.
        Canvas(Modifier.size(14.dp, 21.dp)) {
            val unit = 1.dp.toPx()
            val path = Path().apply {
                moveTo(0f, 0f)
                ARROW.forEach { (x, y) -> lineTo(x * unit, y * unit) }
                close()
            }
            drawPath(path, color)
            drawPath(path, OUTLINE, style = Stroke(width = 1.2f * unit, join = StrokeJoin.Round))
        }
        Box(
            Modifier.offset(12.dp, 16.dp).heightIn(min = 19.dp).background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(pointer.label, color = INK, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** The arrow's corners after its tip, in dp (the design's path). */
private val ARROW = listOf(0f to 17f, 4.4f to 13f, 7.4f to 20f, 10f to 18.9f, 7.1f to 12.2f, 12.6f to 12.2f)

/** The arrow's outline and the name's ink: the design's near-black, the same in every theme (the pill is the person's bright colour). */
private val OUTLINE = Color(0xFF0D1013)
private val INK = Color(0xFF0F1216)
