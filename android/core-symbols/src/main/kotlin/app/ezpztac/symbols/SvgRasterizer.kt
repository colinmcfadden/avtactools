package app.ezpztac.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import kotlin.math.ceil
import kotlin.math.max

/** Draws an SVG to a bitmap with AndroidSVG. */
object SvgRasterizer {
    /**
     * [symbol]'s SVG as pixels, [scale] pixels to each of its units (the screen's density, to draw at the size the web does). The anchor is
     * scaled with it. Null if the SVG cannot be read, which for milsymbol's own output should not happen.
     */
    fun rasterize(symbol: SymbolSvg, scale: Float): RenderedSymbol? {
        val width = max(1, ceil(symbol.width * scale).toInt())
        val height = max(1, ceil(symbol.height * scale).toInt())
        val svg = try {
            SVG.getFromString(symbol.svg)
        } catch (_: SVGParseException) {
            return null
        }
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return RenderedSymbol(bitmap, (symbol.anchorX * scale).toFloat(), (symbol.anchorY * scale).toFloat())
    }
}
