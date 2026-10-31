package app.ezpztac.symbols

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.model.Sidc
import app.ezpztac.model.SymbolPresets
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The pre-rendered presets as the app finds them in its assets: the web's pictures, each listed with the size and anchor milsymbol gave it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PresetSymbolsTest {
    private val presets = PresetSymbols(ApplicationProvider.getApplicationContext<android.content.Context>().assets)
    private val index = Fixtures.load("symbols/presets.json").getValue("symbols").jsonArray.map { it.jsonObject }

    private fun draw(spec: SymbolSpec) = runBlocking { presets.svg(spec) }

    @Test
    fun `every preset the web pre-rendered is there, unit types in each affiliation then the threats`() {
        val expected = SymbolPresets.unitTypes.flatMap { u -> SymbolPresets.affiliations.map { Sidc.withAffiliation(u.sidc, it.id)!! } } + SymbolPresets.threats.map { it.sidc }
        assertEquals(expected, presets.sidcs)
        assertEquals(28, presets.sidcs.size)
        assertEquals(index.map { it.getValue("sidc").jsonPrimitive.content }, presets.sidcs)
    }

    @Test
    fun `a preset is its web picture, with the size and anchor milsymbol gave it`() {
        for (entry in index) {
            val sidc = entry.getValue("sidc").jsonPrimitive.content
            val found = (draw(SymbolSpec(sidc)) as SvgResult.Found).svg
            assertEquals(entry.getValue("width").jsonPrimitive.double, found.width, 0.0)
            assertEquals(entry.getValue("height").jsonPrimitive.double, found.height, 0.0)
            assertEquals(entry.getValue("anchorX").jsonPrimitive.double, found.anchorX, 0.0)
            assertEquals(entry.getValue("anchorY").jsonPrimitive.double, found.anchorY, 0.0)
            assertTrue(sidc, found.svg.startsWith("<svg"))
        }
    }

    @Test
    fun `a bigger symbol is the same picture scaled, anchor and all`() {
        val small = (draw(SymbolSpec("SFGPUCI--------", size = 32)) as SvgResult.Found).svg
        val big = (draw(SymbolSpec("SFGPUCI--------", size = 64)) as SvgResult.Found).svg
        assertEquals(small.width * 2, big.width, 1e-9)
        assertEquals(small.height * 2, big.height, 1e-9)
        assertEquals(small.anchorX * 2, big.anchorX, 1e-9)
        assertEquals(small.svg, big.svg)
    }

    @Test
    fun `a symbol with labels, or one that is not a preset, is not here`() {
        assertEquals(SvgResult.NotHere, draw(SymbolSpec("SFGPUCI--------", uniqueDesignation = "A/1-171")))
        assertEquals(SvgResult.NotHere, draw(SymbolSpec("SFGPUCI--------", higherFormation = "2-101")))
        assertEquals(SvgResult.NotHere, draw(SymbolSpec("SFGPUCV--------")))
        assertEquals(SvgResult.NotHere, draw(SymbolSpec("")))
        assertFalse(presets.has("SFGPUCV--------"))
        assertTrue(presets.has("SFGPUCI--------"))
    }

    @Test
    fun `every preset rasterises to a picture with something in it, drawn at the scale asked for`() {
        for (sidc in presets.sidcs) {
            val found = (draw(SymbolSpec(sidc)) as SvgResult.Found).svg
            val drawn = SvgRasterizer.rasterize(found, 3f)!!
            assertEquals(Math.ceil(found.width * 3).toInt(), drawn.bitmap.width)
            assertEquals(Math.ceil(found.height * 3).toInt(), drawn.bitmap.height)
            assertEquals((found.anchorX * 3).toFloat(), drawn.anchorX, 1e-4f)
            assertTrue("$sidc has pixels", opaquePixels(drawn.bitmap) > drawn.bitmap.width * drawn.bitmap.height / 10)
        }
    }

    private fun opaquePixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.alpha(it) > 0 }
    }

    @Test
    fun `the presets drawn, for someone to look at`() {
        if (System.getProperty("roborazzi.test.record") != "true") return                 // only with -Pezpz.screenshots
        val tile = 120
        val columns = 8
        val rows = (presets.sidcs.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * tile, rows * tile, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(Color.rgb(0x55, 0x70, 0x3f))
        presets.sidcs.forEachIndexed { i, sidc ->
            val drawn = SvgRasterizer.rasterize((draw(SymbolSpec(sidc, size = 64)) as SvgResult.Found).svg, 1.5f)!!
            canvas.drawBitmap(drawn.bitmap, (i % columns) * tile + tile / 2f - drawn.anchorX, (i / columns) * tile + tile / 2f - drawn.anchorY, null)
        }
        File("build/screenshots").mkdirs()
        File("build/screenshots/symbols-presets.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
