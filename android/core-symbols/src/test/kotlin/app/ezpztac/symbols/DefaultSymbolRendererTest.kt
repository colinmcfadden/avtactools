package app.ezpztac.symbols

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** How the renderer chooses a source, what it keeps, and what it does when a source cannot say or fails. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DefaultSymbolRendererTest {
    private val square = SymbolSvg(
        """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10" viewBox="0 0 20 10"><rect width="20" height="10" fill="red"/></svg>""",
        width = 20.0, height = 10.0, anchorX = 10.0, anchorY = 5.0,
    )

    /** A source that answers as told and counts what it was asked. */
    private class Source(var answer: (SymbolSpec) -> SvgResult) : SymbolSvgSource {
        val asked = mutableListOf<SymbolSpec>()
        override suspend fun svg(spec: SymbolSpec): SvgResult {
            asked += spec
            return answer(spec)
        }
    }

    private val spec = SymbolSpec("SFGPUCI--------")

    private fun drawn(outcome: SymbolOutcome) = (outcome as? SymbolOutcome.Drawn)?.symbol ?: error("not drawn: $outcome")

    @Test
    fun `a source that has the symbol is the end of the asking`() = runBlocking {
        val first = Source { SvgResult.Found(square) }
        val second = Source { fail("not asked"); SvgResult.NotHere }
        val symbol = drawn(DefaultSymbolRenderer(listOf(first, second)).render(spec, 1f))
        assertEquals(20, symbol.bitmap.width)
        assertEquals(10, symbol.bitmap.height)
        assertEquals(1, first.asked.size)
        assertEquals(0, second.asked.size)
    }

    @Test
    fun `a source that does not have it passes it to the next`() = runBlocking {
        val presets = Source { SvgResult.NotHere }
        val script = Source { SvgResult.Found(square) }
        drawn(DefaultSymbolRenderer(listOf(presets, script)).render(spec, 1f))
        assertEquals(1, presets.asked.size)
        assertEquals(1, script.asked.size)
    }

    @Test
    fun `a source that says it is not a symbol ends the asking, and the answer is remembered`() = runBlocking {
        val first = Source { SvgResult.Invalid }
        val second = Source { fail("not asked"); SvgResult.NotHere }
        val renderer = DefaultSymbolRenderer(listOf(first, second))
        assertEquals(SymbolOutcome.Invalid, renderer.render(SymbolSpec("XXXXXXXXXXXXXXX"), 1f))
        assertEquals(SymbolOutcome.Invalid, renderer.render(SymbolSpec("XXXXXXXXXXXXXXX"), 1f))
        assertEquals(1, first.asked.size)                                                  // it will not become a symbol: never asked twice
    }

    @Test
    fun `when nothing here can draw it the answer is unavailable, and it is asked again next time`() = runBlocking {
        val script = Source { SvgResult.NotHere }
        val renderer = DefaultSymbolRenderer(listOf(Source { SvgResult.NotHere }, script))
        assertEquals(SymbolOutcome.Unavailable, renderer.render(spec, 1f))
        assertEquals(SymbolOutcome.Unavailable, renderer.render(spec, 1f))
        assertEquals(2, script.asked.size)

        script.answer = { SvgResult.Found(square) }                                         // the sandbox came up
        drawn(renderer.render(spec, 1f))
        assertEquals(3, script.asked.size)
    }

    @Test
    fun `a symbol that was drawn is kept, so a map redrawn many times asks once`() = runBlocking {
        val source = Source { SvgResult.Found(square) }
        val renderer = DefaultSymbolRenderer(listOf(source))
        val first = drawn(renderer.render(spec, 2f))
        repeat(20) { assertSame(first, drawn(renderer.render(spec, 2f))) }
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `a different scale, label, size or code is a different symbol`() = runBlocking {
        val source = Source { SvgResult.Found(square) }
        val renderer = DefaultSymbolRenderer(listOf(source))
        val base = drawn(renderer.render(spec, 1f))
        assertNotSame(base, drawn(renderer.render(spec, 2f)))
        assertNotSame(base, drawn(renderer.render(spec.copy(uniqueDesignation = "A/1-171"), 1f)))
        assertNotSame(base, drawn(renderer.render(spec.copy(higherFormation = "2-101"), 1f)))
        assertNotSame(base, drawn(renderer.render(spec.copy(size = 64), 1f)))
        assertNotSame(base, drawn(renderer.render(SymbolSpec("SHGPUCI--------"), 1f)))
        assertEquals(6, source.asked.size)
    }

    @Test
    fun `the picture is as many pixels as the scale asks, with the anchor moved the same way`() = runBlocking {
        val symbol = drawn(DefaultSymbolRenderer(listOf(Source { SvgResult.Found(square) })).render(spec, 2.5f))
        assertEquals(50, symbol.bitmap.width)
        assertEquals(25, symbol.bitmap.height)
        assertEquals(25f, symbol.anchorX, 1e-4f)
        assertEquals(12.5f, symbol.anchorY, 1e-4f)
    }

    @Test
    fun `an SVG that cannot be read is not a symbol`() = runBlocking {
        val renderer = DefaultSymbolRenderer(listOf(Source { SvgResult.Found(square.copy(svg = "this is not svg")) }))
        assertEquals(SymbolOutcome.Invalid, renderer.render(spec, 1f))
    }

    @Test
    fun `the oldest symbols are let go when the cache is full`() = runBlocking {
        val source = Source { SvgResult.Found(square) }
        val renderer = DefaultSymbolRenderer(listOf(source), maxEntries = 2)
        val a = SymbolSpec("SFGPUCI--------")
        val b = SymbolSpec("SHGPUCI--------")
        val c = SymbolSpec("SNGPUCI--------")
        drawn(renderer.render(a, 1f)); drawn(renderer.render(b, 1f)); drawn(renderer.render(c, 1f))
        assertEquals(3, source.asked.size)
        drawn(renderer.render(c, 1f)); drawn(renderer.render(b, 1f))
        assertEquals(3, source.asked.size)                                                  // the two newest are still there
        drawn(renderer.render(a, 1f))
        assertEquals(4, source.asked.size)                                                  // the oldest had gone
    }

    @Test
    fun `two asks for the same symbol at once are one drawing`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = object : SymbolSvgSource {
            var calls = 0
            override suspend fun svg(spec: SymbolSpec): SvgResult {
                calls++
                gate.await()
                return SvgResult.Found(square)
            }
        }
        val renderer = DefaultSymbolRenderer(listOf(source))
        val one = async { renderer.render(spec, 1f) }
        val two = async { renderer.render(spec, 1f) }
        repeat(5) { yield() }
        gate.complete(Unit)
        assertSame(drawn(one.await()), drawn(two.await()))
        assertEquals(1, source.calls)
    }

    @Test
    fun `a source that fails fails the ask, and does not leave the symbol stuck`() = runBlocking {
        var broken = true
        val source = Source { if (broken) throw IllegalStateException("the sandbox died") else SvgResult.Found(square) }
        val renderer = DefaultSymbolRenderer(listOf(source))
        try {
            renderer.render(spec, 1f)
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals("the sandbox died", e.message)
        }
        broken = false
        assertNotNull(drawn(renderer.render(spec, 1f)))                                    // asked afresh, not waiting on the one that failed
        assertTrue(source.asked.size == 2)
    }
}
