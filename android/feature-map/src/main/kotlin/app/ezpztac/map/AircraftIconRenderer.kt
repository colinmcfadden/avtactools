package app.ezpztac.map

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Draws an aircraft's [AircraftIcons.shapes] into a bitmap. The shapes are tested as data; this only turns them into pixels. */
object AircraftIconRenderer {
    /** The most pixels a picture is drawn with: enough for the sharpest screens at the size the map is given, without a picture costing megabytes. */
    const val MAX_PX = 1024

    /**
     * [iconKey]'s picture for the map, [LzScene.AIRCRAFT_ICON_PT] points across, for a screen of [screenDensity] pixels to the point. MapLibre takes an
     * image's pixel ratio from its bitmap's density (`density / 160`, in `Style.toImage`), and a bitmap is made with the screen's density, so a picture
     * of so many pixels counted as that many over the screen's density in points: every aircraft was drawn that many times too small on a device. It is
     * drawn here with the pixels the screen has for it ([IconRaster]) and its density set to say exactly how many points those are.
     */
    fun forMap(iconKey: String, state: IconState, screenDensity: Float): Bitmap {
        val raster = IconRaster.of(LzScene.AIRCRAFT_ICON_PT, screenDensity.toDouble(), MAX_PX)
        return render(iconKey, state, raster.px).apply { density = raster.densityDpi }
    }

    /** A square bitmap, [sizePx] a side, of the picture its [AircraftIcons.frame] cuts from the box: the rotor in the middle. Transparent where nothing is drawn. */
    fun render(iconKey: String?, state: IconState, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val frame = AircraftIcons.frame(iconKey)
        val scale = (sizePx / frame.side).toFloat()
        // The shapes are drawn in their box's units: the frame's middle goes to the bitmap's, and the frame fills it, strokes and all.
        canvas.translate(sizePx / 2f, sizePx / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-frame.cx.toFloat(), -frame.cy.toFloat())
        val palette = state.palette
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

        fun paintFill(colour: IconColor?): Paint? = colour?.let { fill.apply { color = palette.getValue(it) } }
        fun paintStroke(colour: IconColor?, width: Double): Paint? =
            colour?.takeIf { width > 0 }?.let { stroke.apply { color = palette.getValue(it); strokeWidth = width.toFloat() } }

        for (shape in AircraftIcons.shapes(iconKey)) {
            when (shape) {
                is IconShape.Circle -> {
                    paintFill(shape.fill)?.let { canvas.drawCircle(shape.cx.toFloat(), shape.cy.toFloat(), shape.r.toFloat(), it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawCircle(shape.cx.toFloat(), shape.cy.toFloat(), shape.r.toFloat(), it) }
                }
                is IconShape.Ellipse -> {
                    val box = RectF((shape.cx - shape.rx).toFloat(), (shape.cy - shape.ry).toFloat(), (shape.cx + shape.rx).toFloat(), (shape.cy + shape.ry).toFloat())
                    paintFill(shape.fill)?.let { canvas.drawOval(box, it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawOval(box, it) }
                }
                is IconShape.Rect -> {
                    val box = RectF(shape.x.toFloat(), shape.y.toFloat(), (shape.x + shape.w).toFloat(), (shape.y + shape.h).toFloat())
                    val corner = shape.rx.toFloat()
                    paintFill(shape.fill)?.let { canvas.drawRoundRect(box, corner, corner, it) }
                    paintStroke(shape.stroke, shape.strokeWidth)?.let { canvas.drawRoundRect(box, corner, corner, it) }
                }
                is IconShape.Line -> paintStroke(shape.stroke, shape.width)?.let {
                    canvas.drawLine(shape.x1.toFloat(), shape.y1.toFloat(), shape.x2.toFloat(), shape.y2.toFloat(), it)
                }
                is IconShape.Path -> {
                    val path = Path()
                    for (command in shape.commands) when (command) {
                        is PathCommand.Move -> path.moveTo(command.x.toFloat(), command.y.toFloat())
                        is PathCommand.Line -> path.lineTo(command.x.toFloat(), command.y.toFloat())
                        is PathCommand.Cubic -> path.cubicTo(
                            command.x1.toFloat(), command.y1.toFloat(), command.x2.toFloat(), command.y2.toFloat(), command.x.toFloat(), command.y.toFloat(),
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
