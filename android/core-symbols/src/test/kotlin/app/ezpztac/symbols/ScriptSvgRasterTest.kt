package app.ezpztac.symbols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The SVG milsymbol really gives for symbols that are not presets (labels, echelons, an air or sea symbol), drawn by AndroidSVG: the part of the
 * script path that can be tried here. Labels are SVG text, which is where a rasteriser is most likely to disagree with a browser.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScriptSvgRasterTest {
    private class Case(val label: String, val svg: SymbolSvg)

    private val cases: List<Case> = Fixtures.load("symbols/script.json").getValue("answers").jsonArray.mapNotNull { item ->
        val c = item.jsonObject
        val found = ScriptAnswer.parse(c.getValue("answer").jsonPrimitive.content) as? SvgResult.Found ?: return@mapNotNull null
        val options = c.getValue("options").jsonObject
        Case("${c.getValue("sidc").jsonPrimitive.content} ${options["uniqueDesignation"]?.jsonPrimitive?.content.orEmpty()} ${options["higherFormation"]?.jsonPrimitive?.content.orEmpty()}".trim(), found.svg)
    }

    private fun opaque(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.alpha(it) > 0 }
    }

    @Test
    fun `every symbol the script draws is read by the rasteriser and has something in it`() {
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val drawn = SvgRasterizer.rasterize(case.svg, 2f)
            assertNotNull(case.label, drawn)
            assertTrue("${case.label} has pixels", opaque(drawn!!.bitmap) > 50)
            assertTrue("${case.label}: the anchor is inside the picture", drawn.anchorX in 0f..drawn.bitmap.width.toFloat() && drawn.anchorY in 0f..drawn.bitmap.height.toFloat())
        }
    }

    @Test
    fun `labels make the picture bigger than the symbol's frame`() {
        val plain = cases.first { it.label == "SFGPUCI--------" }.svg
        val labelled = cases.first { it.label.startsWith("SFGPUCI-------- A/1-171") }.svg
        assertTrue(labelled.width > plain.width)
        assertEquals("the frame, not the picture, is what sits on the map position", true, labelled.anchorX > plain.anchorX)
    }

    @Test
    fun `the symbols drawn, for someone to look at`() {
        if (System.getProperty("roborazzi.test.record") != "true") return                 // only with -Pezpz.screenshots
        val tile = 190
        val columns = 5
        val rows = (cases.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * tile, rows * tile, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(0x55, 0x70, 0x3f))
        cases.forEachIndexed { i, case ->
            val drawn = SvgRasterizer.rasterize(case.svg, 1.5f)!!
            canvas.drawBitmap(drawn.bitmap, (i % columns) * tile + tile / 2f - drawn.anchorX, (i / columns) * tile + tile / 2f - drawn.anchorY, null)
        }
        File("build/screenshots").mkdirs()
        File("build/screenshots/symbols-script.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
