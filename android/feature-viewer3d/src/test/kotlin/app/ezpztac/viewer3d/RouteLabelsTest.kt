package app.ezpztac.viewer3d

import android.graphics.Bitmap
import android.graphics.Color
import app.ezpztac.designsystem.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A route's label as it is painted for the GPU: bounded however long its name, its box square, red at night, and at the person's text size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RouteLabelsTest {
    private val density = 2.75f

    @Test
    fun `a label keeps at most four lines, each cut to 200 characters without splitting one`() {
        assertEquals(listOf(".SP", "1,800' MSL", "416' AGL"), LabelPainter.lines(".SP\n1,800' MSL\n416' AGL"))
        assertEquals(LabelPainter.MAX_LINES, LabelPainter.lines("a\nb\nc\nd\ne\nf").size)
        val emoji = "🚁".repeat(300)
        val cut = LabelPainter.lines(emoji).single()
        assertEquals(200, cut.codePointCount(0, cut.length))
        assertFalse("no half of a pair", Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `however long a name, its picture fits a GPU's textures`() {
        val name = "N".repeat(100_000)
        val picture = LabelPainter.paint("$name\n1,800' MSL\n416' AGL", mismatch = false, density = density)!!
        val padding = 2 * 6 * density + 2 * density
        assertTrue("${picture.width} wide", picture.width <= LabelPainter.MAX_LINE_DP * density + padding + 1)
        assertTrue(picture.width <= 2_048 && picture.height <= 2_048)
        val tall = LabelPainter.paint((1..50).joinToString("\n") { "LINE $it" }, mismatch = false, density = density)!!
        val four = LabelPainter.paint((1..4).joinToString("\n") { "LINE $it" }, mismatch = false, density = density)!!
        assertEquals(four.height, tall.height)
    }

    @Test
    fun `its box is square at the corners, as Cesium's label background is`() {
        val picture = LabelPainter.paint(".SP", mismatch = false, density = density)!!
        assertTrue("the corner is the box's", Color.alpha(picture.getPixel(0, 0)) > 0)
        assertTrue(Color.alpha(picture.getPixel(picture.width - 1, picture.height - 1)) > 0)
    }

    @Test
    fun `by day its words are white and a mismatch amber, at night the night palette's, never white`() {
        val day = LabelPainter.paint("MMMM", mismatch = false, density = density)!!
        assertTrue(day.has { r, g, b -> r > 240 && g > 240 && b > 240 })
        val night = LabelPainter.paint("MMMM", mismatch = false, density = density, look = RouteLook(night = true))!!
        assertFalse("no white at night", night.has { r, g, b -> r > 200 && g > 200 && b > 200 })
        val red = Tokens.Colors.Night.onSurface.toInt()
        assertTrue(night.has { r, g, b -> r == Color.red(red) && g == Color.green(red) && b == Color.blue(red) })
        val amber = Tokens.Colors.Night.warning.toInt()
        val mismatch = LabelPainter.paint("MMMM", mismatch = true, density = density, look = RouteLook(night = true))!!
        assertTrue(mismatch.has { r, g, b -> r == Color.red(amber) && g == Color.green(amber) && b == Color.blue(amber) })
        assertEquals(listOf(1f, 1f, 1f), RouteLook().mark.toList())
        assertEquals(listOf(Color.red(red) / 255f, Color.green(red) / 255f, Color.blue(red) / 255f), RouteLook(night = true).mark.toList())
    }

    @Test
    fun `its words are as tall as the person's text size makes 12 points`() {
        val plain = LabelPainter.paint(".SP", mismatch = false, density = density, look = RouteLook(textPx = 12 * density))!!
        val unsaid = LabelPainter.paint(".SP", mismatch = false, density = density)!!
        assertEquals(plain.height, unsaid.height)
        val large = LabelPainter.paint(".SP", mismatch = false, density = density, look = RouteLook(textPx = 24 * density))!!
        assertTrue("${large.height} against ${plain.height}", large.height > plain.height * 1.6)
    }

    private fun Bitmap.has(test: (Int, Int, Int) -> Boolean): Boolean {
        for (y in 0 until height) for (x in 0 until width) {
            val c = getPixel(x, y)
            if (Color.alpha(c) == 255 && test(Color.red(c), Color.green(c), Color.blue(c))) return true
        }
        return false
    }
}
