package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** The pictures drawn for real (Robolectric's native graphics), so the pixels can be checked and, with `-Pezpz.screenshots`, looked at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AircraftIconRendererTest {
    private val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    private fun render(key: String?, state: IconState, sizePx: Int) = AircraftIconRenderer.render(assets, key, state, sizePx)

    /** The pixel of [key]'s picture at ([x], [y]) in the units its picture is drawn in, through its frame. */
    private fun at(bitmap: Bitmap, key: String, x: Double, y: Double): Int {
        val frame = AircraftIcons.frame(key)
        val scale = bitmap.width / frame.side
        return bitmap.getPixel(((x - frame.cx) * scale + bitmap.width / 2.0).toInt(), ((y - frame.cy) * scale + bitmap.height / 2.0).toInt())
    }

    private val green = IconState.NORMAL.colour(IconColor.ACCENT)
    private val red = IconState.VIOLATION.colour(IconColor.ACCENT)
    private val black = IconState.NORMAL.colour(IconColor.DARK)

    @Test
    fun `the bitmap is square, transparent outside the rotor, and the hub is the middle of the picture`() {
        for (key in listOf("uh60", "generic")) {
            val b = render(key, IconState.NORMAL, 200)
            assertEquals(200, b.width)
            assertEquals(200, b.height)
            assertEquals(key, 0, Color.alpha(b.getPixel(4, 4)))                                  // a corner is clear
            assertEquals(key, green, b.getPixel(100, 100))                                        // the hub, green, in the middle
        }
        assertEquals(green, at(render("generic", IconState.NORMAL, 200), "generic", 50.0, 42.0))  // which is the mast
    }

    @Test
    fun `the body is black, and a violation turns the green red and nothing else`() {
        for (key in listOf("uh60", "generic")) {
            val normal = render(key, IconState.NORMAL, 200)
            val violation = render(key, IconState.VIOLATION, 200)
            // On the fuselage behind the mast, between the blades; and on the rotor's ring ahead of the nose, clear of the blades too.
            val body = if (key == "uh60") 0.0 to 30.0 else 50.0 to 52.0
            val ring = if (key == "uh60") 0.0 to -81.8 else 50.0 to -4.0
            assertEquals(key, black, at(normal, key, body.first, body.second))
            assertEquals(key, black, at(violation, key, body.first, body.second))
            assertEquals(key, green, at(normal, key, ring.first, ring.second))
            assertEquals(key, red, at(violation, key, ring.first, ring.second))
        }
    }

    @Test
    fun `an unknown icon key is drawn as the generic one`() {
        assertTrue(render("nope", IconState.NORMAL, 100).sameAs(render("generic", IconState.NORMAL, 100)))
    }

    @Test
    fun `the Chinook has two hubs`() {
        val b = render("ch47", IconState.NORMAL, 200)
        assertEquals(green, at(b, "ch47", 50.0, 26.0))
        assertEquals(green, at(b, "ch47", 50.0, 74.0))
    }

    @Test
    fun `every icon in every state draws something`() {
        for (key in AircraftIcons.KEYS) for (state in IconState.entries) {
            val b = render(key, state, 64)
            val any = (0 until 64).any { y -> (0 until 64).any { x -> Color.alpha(b.getPixel(x, y)) > 0 } }
            assertTrue("$key $state", any)
        }
    }

    // -- Pictures to look at (written only with -Pezpz.screenshots) ---------------------------------------------------------------------------------

    /** Grass, a pale map and bare ground: what the aircraft are drawn over (satellite imagery, the topographic map and the VFR chart, a field). */
    private val grounds = listOf(0xFF3B5B3A.toInt(), 0xFFEDEBE3.toInt(), 0xFFB89F78.toInt())

    /** Draws [b] into [box] whatever the two bitmaps' densities say, so a picture is the size it is asked to be. */
    private fun Canvas.place(b: Bitmap, box: RectF) = drawBitmap(b, Rect(0, 0, b.width, b.height), box, Paint(Paint.FILTER_BITMAP_FLAG))

    @Test
    fun `contact sheet`() {
        // Every airframe in both states, each picture filling its cell, over each ground.
        val cell = 200
        val sheet = createBitmap(cell * AircraftIcons.KEYS.size, cell * IconState.entries.size * grounds.size)
        val canvas = Canvas(sheet)
        grounds.forEachIndexed { g, ground ->
            IconState.entries.forEachIndexed { s, state ->
                val top = (g * IconState.entries.size + s) * cell.toFloat()
                canvas.drawRect(0f, top, sheet.width.toFloat(), top + cell, Paint().apply { color = ground })
                AircraftIcons.KEYS.forEachIndexed { col, key ->
                    canvas.place(render(key, state, cell), RectF(col * cell.toFloat(), top, (col + 1) * cell.toFloat(), top + cell))
                }
            }
        }
        sheet.captureRoboImage("build/screenshots/aircraft-icons.png")
    }

    @Test
    fun `drawn to scale`() {
        // Every airframe at its seeded rotor diameter (a tandem's is its span), 12 pixels to the metre, with a thin magenta line where the rotor's edge
        // is on the ground: it should run down the middle of each green ring.
        val pxPerM = 12.0
        val rotors = listOf("uh60" to 16.357, "ah64" to 14.63, "ch47" to 30.14, "uh72" to 11.0, "mh6" to 8.38, "generic" to 12.0)
        val widths = rotors.map { (key, d) -> d * AircraftIcons.frame(key).widthPerRotorDiameter * pxPerM }
        val sheet = createBitmap(widths.sum().roundToInt() + 20 * rotors.size, widths.max().roundToInt() + 20)
        val canvas = Canvas(sheet)
        canvas.drawColor(grounds[0])
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFFFF00FF.toInt() }
        var left = 10.0
        rotors.forEachIndexed { i, (key, d) ->
            val w = widths[i]
            val top = (sheet.height - w) / 2
            canvas.place(render(key, IconState.NORMAL, w.roundToInt()), RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + w).toFloat()))
            val frame = AircraftIcons.frame(key)
            val unit = w / frame.side
            val rings = if (key == "ch47") listOf(26.0, 74.0).map { it to 25.0 } else listOf((if (key == "uh60") 0.0 else 42.0) to frame.rotorSpan / 2)
            for ((cy, r) in rings) canvas.drawCircle((left + w / 2).toFloat(), (top + w / 2 + (cy - frame.cy) * unit).toFloat(), (r * unit).toFloat(), line)
            left += w + 20
        }
        sheet.captureRoboImage("build/screenshots/aircraft-to-scale.png")
    }

    @Test
    fun `the UH-60 against its published dimensions`() {
        // The UH-60 at 30 pixels to the metre, normal and in a violation, with magenta marks at its published dimensions: the rotor's 53 ft 8 in, the
        // nose 5.25 m ahead of the mast, the stabilator's 14 ft 4 1/2 in 9.5 m behind it, and the tail rotor's tip 19.76 m from the main rotor's.
        val pxPerM = 30.0
        val w = (16.36 * AircraftIcons.UH60_FRAME.widthPerRotorDiameter * pxPerM).roundToInt()
        val sheet = createBitmap(2 * w + 60, w + 40)
        val canvas = Canvas(sheet)
        canvas.drawColor(grounds[1])
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0xFFFF00FF.toInt() }
        IconState.entries.forEachIndexed { i, state ->
            val left = 20f + i * (w + 20f)
            val top = 20f
            canvas.place(render("uh60", state, w), RectF(left, top, left + w, top + w))
            val cx = left + w / 2f
            val cy = top + w / 2f
            val m = pxPerM.toFloat()
            canvas.drawCircle(cx, cy, 8.18f * m, mark)
            canvas.drawLine(cx - 1.5f * m, cy - 5.25f * m, cx + 1.5f * m, cy - 5.25f * m, mark)                 // the nose
            canvas.drawLine(cx - 2.19f * m, cy + 9.5f * m + 0.6f * m, cx + 2.19f * m, cy + 9.5f * m + 0.6f * m, mark)  // the stabilator's span, just behind it
            canvas.drawLine(cx - 1.5f * m, cy + 11.58f * m, cx + 1.5f * m, cy + 11.58f * m, mark)               // the tail rotor's tip
        }
        sheet.captureRoboImage("build/screenshots/aircraft-uh60.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GraphicsImagesTest {
    @Test
    fun `the go-around arrows are yellow, differ by direction, and have room for their tag`() {
        val left = GraphicsImages.goAround(right = false)
        val right = GraphicsImages.goAround(right = true)
        assertEquals(left.width, left.height)
        assertTrue(!left.sameAs(right))
        val yellow = 0xFFFFC107.toInt()
        assertTrue((0 until left.width).any { x -> (0 until left.height).any { y -> left.getPixel(x, y) == yellow } })
    }

    @Test
    fun `the PZ arrow head points up`() {
        val head = GraphicsImages.pzArrowHead(100)
        assertTrue(Color.alpha(head.getPixel(50, 20)) > 0)                                    // the tip is near the top, in the middle
        assertEquals(0, Color.alpha(head.getPixel(10, 20)))                                   // and the top corners are clear
        assertTrue(Color.alpha(head.getPixel(30, 88)) > 0)                                    // the base is wide
    }

    @Test
    fun `the path data reads as the web wrote it`() {
        // A unit square, as data: M0,0 L10,0 L10,10 L0,10 Z scaled by 2 spans 20 by 20.
        val bounds = android.graphics.RectF()
        GraphicsImages.parse("M0,0 L10,0 L10,10 L0,10 Z", 2f).computeBounds(bounds, true)
        assertEquals(20f, bounds.width(), 0.01f)
        assertEquals(20f, bounds.height(), 0.01f)
        val curve = android.graphics.RectF()
        GraphicsImages.parse("M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z", 1f).computeBounds(curve, true)
        assertTrue(curve.left <= 10f && curve.right >= 95f)
    }

    @Test
    fun `contact sheet`() {
        val sheet = createBitmap(480, 160)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(0xFF3B5B3A.toInt())
        canvas.drawBitmap(GraphicsImages.goAround(false), 0f, 0f, null)
        canvas.drawBitmap(GraphicsImages.goAround(true), 160f, 0f, null)
        canvas.drawBitmap(GraphicsImages.pzArrowHead(96), 340f, 30f, null)
        sheet.captureRoboImage("build/screenshots/graphics-images.png")
    }
}
