package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pictures of a route's named points, drawn for real (Robolectric's native graphics): a square, a triangle pointing up and a circle, each in the route's
 * colour, the same size in points on every screen. What MapLibre does with them cannot be seen here (it needs a GPU); what it is handed can.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxhdpi")
class RoutePinImagesTest {
    private val blue = Color.rgb(0x0A, 0x84, 0xFF)

    /** Whether the pixel [dx], [dy] points from the middle of a 32-point picture drawn at [unit] pixels to the point is the shape's own colour. */
    private fun Bitmap.fillAt(dx: Float, dy: Float, unit: Float): Boolean {
        val pixel = getPixel((width / 2f + dx * unit).roundToInt(), (height / 2f + dy * unit).roundToInt())
        return Color.alpha(pixel) > 250 && abs(Color.red(pixel) - Color.red(blue)) < 8 && abs(Color.green(pixel) - Color.green(blue)) < 8 && abs(Color.blue(pixel) - Color.blue(blue)) < 8
    }

    private fun Bitmap.clearAt(dx: Float, dy: Float, unit: Float): Boolean =
        Color.alpha(getPixel((width / 2f + dx * unit).roundToInt(), (height / 2f + dy * unit).roundToInt())) == 0

    private fun abs(i: Int) = kotlin.math.abs(i)

    @Test
    fun `each picture is as many points across as the map is told, on any screen`() {
        for (screen in listOf(1f, 2f, 2.625f, 3f, 3.5f, 4f)) for (shape in PinShape.entries) for (held in listOf(false, true)) {
            val b = RoutePinImages.forMap(shape, "#0A84FF", held, screen)
            assertEquals(b.width, b.height)
            assertEquals("$shape at $screen: ${b.width} px at ${b.density}", b.width * 160, b.density * RoutePinImages.CANVAS_PT)
        }
        assertEquals(112, RoutePinImages.forMap(PinShape.SQUARE, "#0A84FF", false, 3.5f).width)                      // 32 points at 3.5 pixels each
    }

    @Test
    fun `a square fills its corners, a circle does not, and a triangle points up`() {
        val unit = 4f
        val size = (RoutePinImages.CANVAS_PT * unit).toInt()
        val square = RoutePinImages.render(PinShape.SQUARE, blue, held = false, sizePx = size)
        val circle = RoutePinImages.render(PinShape.CIRCLE, blue, held = false, sizePx = size)
        val triangle = RoutePinImages.render(PinShape.TRIANGLE, blue, held = false, sizePx = size)
        val corner = RoutePinImages.SQUARE_PT / 2 - 1.5f                                       // inside the square's corner, outside a circle its width
        assertTrue(square.fillAt(corner, corner, unit) && square.fillAt(-corner, -corner, unit))
        assertTrue(circle.fillAt(0f, 0f, unit))
        assertTrue("a circle's box corner is clear", circle.clearAt(RoutePinImages.CIRCLE_PT / 2, RoutePinImages.CIRCLE_PT / 2, unit))
        // The triangle: broad at its foot and narrow at its top, its box in the middle of the picture.
        val height = RoutePinImages.TRIANGLE_PT * sqrt(3f) / 2
        val nearFoot = height / 2 - 1.5f
        val nearTop = -height / 2 + 3f
        assertTrue(triangle.fillAt(RoutePinImages.TRIANGLE_PT / 2 - 3f, nearFoot, unit) && triangle.fillAt(-(RoutePinImages.TRIANGLE_PT / 2 - 3f), nearFoot, unit))
        assertTrue(triangle.fillAt(0f, nearTop, unit))
        assertTrue("no colour at the top corners of its box", triangle.clearAt(RoutePinImages.TRIANGLE_PT / 2 - 1f, -height / 2, unit) && triangle.clearAt(-(RoutePinImages.TRIANGLE_PT / 2 - 1f), -height / 2, unit))
        // An upside-down triangle would be the other way round.
        assertTrue(triangle.clearAt(RoutePinImages.TRIANGLE_PT / 2 - 3f, nearTop, unit))
    }

    @Test
    fun `the held one is larger, with an amber edge`() {
        val unit = 4f
        val size = (RoutePinImages.CANVAS_PT * unit).toInt()
        val plain = RoutePinImages.render(PinShape.CIRCLE, blue, held = false, sizePx = size)
        val held = RoutePinImages.render(PinShape.CIRCLE, blue, held = true, sizePx = size)
        val past = RoutePinImages.CIRCLE_PT / 2 + 2f                                          // beyond the plain circle and its edge, within the held one
        assertTrue(plain.clearAt(past + 1f, 0f, unit))
        assertTrue(held.fillAt(past - 1f, 0f, unit))
        // Just outside the colour, on the edge.
        val edge = held.getPixel((size / 2f + (RoutePinImages.CIRCLE_PT * RoutePinImages.HELD_SCALE / 2 + 0.9f) * unit).roundToInt(), size / 2)
        assertTrue("amber: ${Integer.toHexString(edge)}", Color.red(edge) > 200 && Color.green(edge) in 150..220 && Color.blue(edge) < 60)
    }

    // -- A picture to look at (written only with -Pezpz.screenshots) -----------------------------------------------------------------------------

    @Test
    fun `contact sheet`() {
        // Over grass, the pale topographic map and the VFR chart's tan: each shape, plain then held, in four of the route palette's colours, at a phone's size.
        val grounds = listOf(0xFF3B5B3A.toInt(), 0xFFEDEBE3.toInt(), 0xFFB89F78.toInt())
        val colors = listOf("#FF453A", "#0A84FF", "#32D74B", "#FFD60A")
        val screen = 3f
        val cell = (RoutePinImages.CANVAS_PT * screen).toInt()
        val columns = PinShape.entries.size * 2
        val sheet = createBitmap(cell * columns, cell * colors.size * grounds.size)
        val canvas = Canvas(sheet)
        grounds.forEachIndexed { g, ground ->
            colors.forEachIndexed { c, color ->
                val top = (g * colors.size + c) * cell.toFloat()
                canvas.drawRect(0f, top, sheet.width.toFloat(), top + cell, Paint().apply { this.color = ground })
                PinShape.entries.forEachIndexed { i, shape ->
                    listOf(false, true).forEachIndexed { h, held ->
                        canvas.drawBitmap(RoutePinImages.render(shape, RoutePinImages.colorOf(color), held, cell), ((i * 2 + h) * cell).toFloat(), top, null)
                    }
                }
            }
        }
        sheet.captureRoboImage("build/screenshots/route-pins.png")
    }

    @Test
    fun `a route's colour is read as the web writes it, and one that cannot be read is grey, never nothing`() {
        assertEquals(blue, RoutePinImages.colorOf("#0A84FF"))
        assertEquals(blue, RoutePinImages.colorOf(" #0a84ff "))
        assertEquals(Color.rgb(0xFF, 0x44, 0x00), RoutePinImages.colorOf("#F40"))
        assertEquals(Color.rgb(0xFF, 0, 0), RoutePinImages.colorOf("red"))
        assertEquals("opaque, whatever was written", Color.rgb(0x0A, 0x84, 0xFF), RoutePinImages.colorOf("#800A84FF"))
        assertEquals(RoutePinImages.UNREADABLE_COLOR, RoutePinImages.colorOf("not a colour"))
        assertEquals(RoutePinImages.UNREADABLE_COLOR, RoutePinImages.colorOf(""))
    }
}
