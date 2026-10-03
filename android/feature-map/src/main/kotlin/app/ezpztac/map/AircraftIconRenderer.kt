package app.ezpztac.map

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Draws an aircraft's [AircraftIcons.shapes] into a bitmap. The shapes are tested as data; this only turns them into pixels. */
object AircraftIconRenderer {
    /** A square bitmap, [sizePx] a side, with the 100-unit box scaled to fill it. Transparent where nothing is drawn. */
    fun render(iconKey: String?, state: IconState, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val scale = sizePx / 100f
        val palette = state.palette
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

        fun paintFill(colour: IconColor?): Paint? = colour?.let { fill.apply { color = palette.getValue(it) } }
        fun paintStroke(colour: IconColor?, width: Double): Paint? =
            colour?.takeIf { width > 0 }?.let { stroke.apply { color = palette.getValue(it); strokeWidth = (width * scale).toFloat() } }

        for (shape in AircraftIcons.shapes(iconKey)) {
            when (shape) {
                is IconShape.Circle -> {
                    paintFill(shape.fill)?.let { canvas.drawCircle((shape.cx * scale).toFloat(), (shape.cy * scale).toFloat(), (shape.r * scale).toFloat(), it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawCircle((shape.cx * scale).toFloat(), (shape.cy * scale).toFloat(), (shape.r * scale).toFloat(), it) }
                }
                is IconShape.Ellipse -> {
                    val box = RectF(((shape.cx - shape.rx) * scale).toFloat(), ((shape.cy - shape.ry) * scale).toFloat(), ((shape.cx + shape.rx) * scale).toFloat(), ((shape.cy + shape.ry) * scale).toFloat())
                    paintFill(shape.fill)?.let { canvas.drawOval(box, it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawOval(box, it) }
                }
                is IconShape.Rect -> {
                    val box = RectF((shape.x * scale).toFloat(), (shape.y * scale).toFloat(), ((shape.x + shape.w) * scale).toFloat(), ((shape.y + shape.h) * scale).toFloat())
                    val corner = (shape.rx * scale).toFloat()
                    paintFill(shape.fill)?.let { canvas.drawRoundRect(box, corner, corner, it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawRoundRect(box, corner, corner, it) }
                }
                is IconShape.Line -> paintStroke(shape.stroke, shape.width)?.let {
                    canvas.drawLine((shape.x1 * scale).toFloat(), (shape.y1 * scale).toFloat(), (shape.x2 * scale).toFloat(), (shape.y2 * scale).toFloat(), it)
                }
                is IconShape.Path -> {
                    val path = Path()
                    for (command in shape.commands) when (command) {
                        is PathCommand.Move -> path.moveTo((command.x * scale).toFloat(), (command.y * scale).toFloat())
                        is PathCommand.Line -> path.lineTo((command.x * scale).toFloat(), (command.y * scale).toFloat())
                        is PathCommand.Cubic -> path.cubicTo(
                            (command.x1 * scale).toFloat(), (command.y1 * scale).toFloat(), (command.x2 * scale).toFloat(), (command.y2 * scale).toFloat(),
                            (command.x * scale).toFloat(), (command.y * scale).toFloat(),
                        )
                        PathCommand.Close -> path.close()
                    }
                    paintFill(shape.fill)?.let { canvas.drawPath(path, it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawPath(path, it) }
                }
            }
        }
        return bitmap
    }
}
