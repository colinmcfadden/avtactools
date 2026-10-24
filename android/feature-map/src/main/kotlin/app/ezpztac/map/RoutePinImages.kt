package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import kotlin.math.sqrt

/**
 * The pictures of a route's named points ([PinShape]): a circle, a square or a triangle pointing up, in the route's colour with a white edge (amber and larger
 * while held) over a thin dark one, so it reads on imagery and on the pale charts, as the web's points do. Each is [CANVAS_PT] points square with the shape's
 * box in its middle, the point it stands for, as the web centres its icons. A bitmap's density says how many points it is (MapLibre reads it, AGENTS.md §14),
 * so it is the same size on every screen.
 */
object RoutePinImages {
    /** The picture's size on the map, in points: room for the held shape, its edge and the dark ring round it. */
    const val CANVAS_PT = 32

    /** The largest bitmap made, in pixels: 32 points on the densest screen. */
    private const val MAX_PX = 192

    /** How large each shape is, in points, unheld: about the same area each, so none looks heavier than the others. */
    internal const val CIRCLE_PT = 14f
    internal const val SQUARE_PT = 13f
    internal const val TRIANGLE_PT = 17f

    /** Held, the shape is this much larger, with an amber edge (as the map's other held things are). */
    internal const val HELD_SCALE = 18f / 14f
    private const val EDGE_PT = 2f
    private const val HELD_EDGE_PT = 3.5f
    private const val CASING_PT = 1f
    private const val HELD_COLOR = 0xFFFFC107.toInt()
    private const val CASING_COLOR = 0x8C000000.toInt()

    /** What the map shows when a route's colour cannot be read: a mid grey, never nothing. */
    internal const val UNREADABLE_COLOR = 0xFF9E9E9E.toInt()

    /** [shape] in [color] (a route's colour as written: `#RRGGBB`, `#RGB`, or a colour's name), held or not, for a screen of [screenDensity] pixels to the point. */
    fun forMap(shape: PinShape, color: String, held: Boolean, screenDensity: Float): Bitmap {
        val raster = IconRaster.of(CANVAS_PT, screenDensity.toDouble(), MAX_PX)
        return render(shape, colorOf(color), held, raster.px).apply { density = raster.densityDpi }
    }

    /** The picture, [sizePx] a side: its edge first (the dark ring, then white or amber), the shape's colour over it. */
    internal fun render(shape: PinShape, fill: Int, held: Boolean, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val unit = sizePx / CANVAS_PT.toFloat()
        val scale = if (held) HELD_SCALE else 1f
        val edge = (if (held) HELD_EDGE_PT else EDGE_PT) * unit
        val path = outline(shape, sizePx / 2f, sizePx / 2f, scale * unit)
        fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, stroke(CASING_COLOR, edge + 2 * CASING_PT * unit))
        canvas.drawPath(path, stroke(if (held) HELD_COLOR else 0xFFFFFFFF.toInt(), edge))
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL })
        return bitmap
    }

    /** The shape's outline round ([cx], [cy]), its box in the middle, [unit] pixels to the point. A triangle points up, as the web's does. */
    internal fun outline(shape: PinShape, cx: Float, cy: Float, unit: Float): Path = Path().apply {
        when (shape) {
            PinShape.CIRCLE -> addCircle(cx, cy, CIRCLE_PT / 2 * unit, Path.Direction.CW)
            PinShape.SQUARE -> {
                val half = SQUARE_PT / 2 * unit
                addRect(cx - half, cy - half, cx + half, cy + half, Path.Direction.CW)
            }
            PinShape.TRIANGLE -> {
                val half = TRIANGLE_PT / 2 * unit
                val height = TRIANGLE_PT * sqrt(3f) / 2 * unit
                moveTo(cx, cy - height / 2)
                lineTo(cx + half, cy + height / 2)
                lineTo(cx - half, cy + height / 2)
                close()
            }
        }
    }

    /** A route's colour as written: `#RRGGBB`, `#RGB`, `#AARRGGBB` or a colour's name; anything else is [UNREADABLE_COLOR]. Opaque, whatever was written. */
    internal fun colorOf(css: String): Int {
        val text = css.trim()
        Regex("#([0-9a-fA-F]{6})").matchEntire(text)?.let { return (0xFF000000L or it.groupValues[1].toLong(16)).toInt() }
        Regex("#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])").matchEntire(text)?.let { m ->
            val (r, g, b) = m.destructured
            return (0xFF000000L or "$r$r$g$g$b$b".toLong(16)).toInt()
        }
        return runCatching { text.toColorInt() or 0xFF000000.toInt() }.getOrDefault(UNREADABLE_COLOR)
    }
}
