package app.ezpztac.map

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The pictures the map is given for the planning graphics that are not aircraft: the go-around arrow (as the web draws it, yellow with a
 * black outline and a "GA" tag) and the head of a PZ marker's arrow. Drawn here, not shipped as files, so they follow the shapes in the
 * web's SVG and cost nothing in the app's size.
 */
object GraphicsImages {
    const val GO_AROUND_LEFT = "ga-left"
    const val GO_AROUND_RIGHT = "ga-right"
    const val PZ_ARROW_HEAD = "pz-arrow-head"

    /** The web's arrow outlines in its 100-unit box (`GoAround.jsx`); the right-hand one turns the other way. */
    private const val RIGHT_ARROW = "M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z"
    private const val LEFT_ARROW = "M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z"
    private const val ARROW_YELLOW = 0xFFFFC107.toInt()

    fun goAround(right: Boolean, sizePx: Int = 160): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val scale = sizePx / 120f                                      // the arrow's 100-unit box with room for the tag above it
        canvas.translate(10f * scale, 18f * scale)
        val path = parse(if (right) RIGHT_ARROW else LEFT_ARROW, scale)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ARROW_YELLOW; style = Paint.Style.FILL })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * scale; strokeJoin = Paint.Join.ROUND })
        // The tag, over the arrow's upper edge.
        val tag = RectF(30f * scale, -16f * scale, 70f * scale, 4f * scale)
        canvas.drawRect(tag, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ARROW_YELLOW; style = Paint.Style.FILL })
        canvas.drawRect(tag, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * scale })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); textSize = 14f * scale; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        canvas.drawText("GA", 50f * scale, 0f * scale, text)
        return bitmap
    }

    /** A triangle pointing up, translucent blue with a black edge like the web's PZ arrow; the map turns it to the marker's bearing. */
    fun pzArrowHead(sizePx: Int = 48): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val s = sizePx / 100f
        val path = Path().apply { moveTo(50f * s, 6f * s); lineTo(92f * s, 92f * s); lineTo(8f * s, 92f * s); close() }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x730000FF; style = Paint.Style.FILL })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f * s; strokeJoin = Paint.Join.ROUND })
        return bitmap
    }

    /** The subset of SVG path data these two outlines use: M, L, Q and Z with absolute coordinates, scaled. */
    internal fun parse(data: String, scale: Float): Path {
        val path = Path()
        val tokens = Regex("[MLQZ]|-?\\d+(\\.\\d+)?").findAll(data).map { it.value }.toList()
        var i = 0
        fun number() = tokens[i++].toFloat() * scale
        while (i < tokens.size) {
            when (tokens[i++]) {
                "M" -> path.moveTo(number(), number())
                "L" -> path.lineTo(number(), number())
                "Q" -> { val x1 = number(); val y1 = number(); val x = number(); val y = number(); path.quadTo(x1, y1, x, y) }
                "Z" -> path.close()
            }
        }
        return path
    }
}
