package app.ezpztac.map

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The silhouettes drawn for real (Robolectric's native graphics), so the pixels can be checked and, with `-Pezpz.screenshots`, looked at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AircraftIconRendererTest {
    private fun at(bitmap: Bitmap, x: Double, y: Double): Int {
        val scale = bitmap.width / 100.0
        return bitmap.getPixel((x * scale).toInt(), (y * scale).toInt())
    }

    @Test
    fun `the bitmap is square, transparent outside the rotor disc, and the hub is where the mast is`() {
        val b = AircraftIconRenderer.render("uh60", IconState.NORMAL, 200)
        assertEquals(200, b.width)
        assertEquals(200, b.height)
        assertEquals(0, Color.alpha(at(b, 2.0, 2.0)))                                        // a corner is clear
        assertEquals(IconState.NORMAL.palette.getValue(IconColor.EDGE), at(b, 50.0, 42.0))    // the hub, in the outline colour
    }

    @Test
    fun `the body is in the palette's body colour and a violation is a different colour`() {
        val normal = AircraftIconRenderer.render("uh60", IconState.NORMAL, 200)
        val violation = AircraftIconRenderer.render("uh60", IconState.VIOLATION, 200)
        // On the fuselage, clear of the cockpit glass and of the blades.
        assertEquals(IconState.NORMAL.palette.getValue(IconColor.BODY), at(normal, 57.0, 50.0))
        assertEquals(IconState.VIOLATION.palette.getValue(IconColor.BODY), at(violation, 57.0, 50.0))
    }

    @Test
    fun `an unknown icon key is drawn as the generic one`() {
        val a = AircraftIconRenderer.render("nope", IconState.NORMAL, 100)
        val b = AircraftIconRenderer.render("generic", IconState.NORMAL, 100)
        assertTrue(a.sameAs(b))
    }

    @Test
    fun `the Chinook has two hubs`() {
        val b = AircraftIconRenderer.render("ch47", IconState.NORMAL, 200)
        val edge = IconState.NORMAL.palette.getValue(IconColor.EDGE)
        assertEquals(edge, at(b, 50.0, 26.0))
        assertEquals(edge, at(b, 50.0, 74.0))
    }

    @Test
    fun `every icon in every state draws something`() {
        for (key in AircraftIcons.KEYS) for (state in IconState.entries) {
            val b = AircraftIconRenderer.render(key, state, 64)
            val any = (0 until 64).any { y -> (0 until 64).any { x -> Color.alpha(b.getPixel(x, y)) > 0 } }
            assertTrue("$key $state", any)
        }
    }

    @Test
    fun `contact sheet`() {
        // Every silhouette in every state side by side, for a person to look at (written only with -Pezpz.screenshots).
        val cell = 160
        val sheet = createBitmap(cell * AircraftIcons.KEYS.size, cell * IconState.entries.size)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(0xFF3B5B3A.toInt())                                                  // something like grass, which is what they are drawn over
        AircraftIcons.KEYS.forEachIndexed { col, key ->
            IconState.entries.forEachIndexed { row, state ->
                canvas.drawBitmap(AircraftIconRenderer.render(key, state, cell), (col * cell).toFloat(), (row * cell).toFloat(), null)
            }
        }
        sheet.captureRoboImage("build/screenshots/aircraft-icons.png")
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
