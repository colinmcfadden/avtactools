package app.ezpztac.viewer3d

import android.graphics.Bitmap
import android.opengl.GLES30
import app.ezpztac.data.TilesetAddress
import app.ezpztac.model.LatLon
import app.ezpztac.scene3d.RouteHeights
import app.ezpztac.scene3d.RouteScene3D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteOrder

/**
 * The renderer's whole run of GL calls, held to what Android's binding checks before it calls the driver ([GlBinding20], [GlBinding30]): the
 * scene with no routes (on the owner's devices it failed, its empty route buffers refused), with routes, with the ground and its imagery, a
 * fault in the routes, a bitmap the binding will not take, and a context lost and made again. What the driver draws is not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], shadows = [GlBinding20::class, GlBinding30::class])
class SceneRendererTest {
    private val here = LatLon(34.591552, -84.128225)

    @Before
    fun forget() = GlCalls.reset()

    private class Scene(val test: TestScope, sources: SceneSources) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val parent = CoroutineScope(SupervisorJob() + dispatcher)
        val session = SceneSession(
            LatLon(34.591552, -84.128225), TilesetAddress(FakeLidar.KEY, "tileset.json"), null, sources, SceneBudgets.DEFAULT, parent, 5,
            io = dispatcher, decode = dispatcher, clock = { test.testScheduler.currentTime },
        )
        private val ask: () -> Unit = {}
        val renderer = SceneRenderer(session, 2.75, ask)

        init {
            session.listener = ask
        }

        /** A new context, and a surface on it the size of an upright phone. */
        fun open() {
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 1080, 2200)
        }

        fun frames(n: Int) = repeat(n) {
            renderer.onDrawFrame(null)
            test.advanceUntilIdle()
        }

        fun close() {
            session.close()
            parent.cancel()
        }
    }

    private fun TestScope.scene(sources: SceneSources = SceneSources(FixtureFiles())) = Scene(this, sources).also {
        it.open()
        advanceUntilIdle()
    }

    private fun Scene.withRoute() {
        val prepared = RouteScene3D.prepare(listOf(lzRoute()))
        session.setRoutes(RouteScene3D.place(prepared, RouteHeights(prepared.samples.map { 340.0 }, prepared.samples.map { -30.35 })))
    }

    private val lit get() = GlCalls.arrays.count { it == GLES30.GL_TRIANGLES to 3 }
    private val lines get() = GlCalls.elements.count { it == GLES30.GL_UNSIGNED_INT }

    @Test
    fun `a buffer goes up as long as what it holds, and one that holds nothing does not go up`() {
        assertEquals(0, BufferData.floats(emptyList()).remaining())
        assertEquals(0, BufferData.ints(emptyList()).remaining())
        assertEquals(0, BufferData.shorts(ShortArray(0)).remaining())
        val two = BufferData.floats(listOf(1.5f, -2f))
        assertEquals(8, two.remaining())
        assertEquals(ByteOrder.nativeOrder(), two.order())
        assertEquals(-2f, two.getFloat(4))
        assertEquals(12, BufferData.ints(listOf(1, 2, 3)).remaining())
        assertEquals(6, BufferData.shorts(shortArrayOf(1, 2, 3)).remaining())
        BufferData.upload(GLES30.GL_ARRAY_BUFFER, BufferData.floats(emptyList()))
        BufferData.upload(GLES30.GL_ARRAY_BUFFER, two)
        assertEquals(listOf(GlCalls.Upload(GLES30.GL_ARRAY_BUFFER, 8, 8)), GlCalls.uploads)
        // As a device's binding refuses what the routes once asked of it: an empty list's buffer, sized by its capacity.
        val refused = assertThrows(IllegalArgumentException::class.java) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4, BufferData.floats(emptyList()), GLES30.GL_STATIC_DRAW)
        }
        assertEquals("remaining() < size < needed", refused.message)
    }

    @Test
    fun `an LZ_PZ with no routes is drawn, every buffer asked for no more than it holds`() = runTest {
        val s = scene()
        s.frames(30)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertTrue("points drawn", GlCalls.arrays.any { it.first == GLES30.GL_POINTS && it.second > 100 })
        assertTrue("and lit onto the screen", lit > 0)
        assertTrue(GlCalls.uploads.isNotEmpty())
        assertTrue(GlCalls.uploads.all { it.size == it.remaining && it.size > 0 })
        assertEquals("no route drawn", 0, lines)
        s.close()
    }

    @Test
    fun `routes are put on the GPU and drawn over the points, their labels too`() = runTest {
        val s = scene()
        s.withRoute()
        s.frames(30)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertTrue("the lines drawn, shown and hidden", lines >= 2)
        assertTrue("the labels drawn", GlCalls.arrays.any { it == GLES30.GL_TRIANGLE_STRIP to 4 })
        assertTrue(GlCalls.uploads.all { it.size == it.remaining && it.size > 0 })
        // The routes taken away: their empty buffers go up as nothing, and the scene goes on.
        s.session.setRoutes(emptyList())
        val drawn = lines
        s.frames(5)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertEquals(drawn, lines)
        s.close()
    }

    @Test
    fun `a fault in the routes costs the routes on that context, never the scene, and the state they set is put back`() = runTest {
        val s = scene()
        s.withRoute()
        s.frames(10)
        assertTrue(lines > 0)
        // Fails the hidden lines' draw, after blending is on and depth writes off.
        var drawsThisFrame = 0
        GlCalls.fault = { it == "glDrawElements:${GLES30.GL_UNSIGNED_INT}" && ++drawsThisFrame == 2 }
        s.frames(1)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertFalse("blending off again", GlCalls.blend)
        assertTrue("depth written again", GlCalls.depthMask)
        GlCalls.fault = null
        val drawn = lines
        val litBefore = lit
        s.frames(5)
        assertEquals("not drawn again on this context", drawn, lines)
        assertTrue("the scene still is", lit > litBefore)
        // A new context tries them afresh.
        s.open()
        s.frames(5)
        assertTrue(lines > drawn)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        s.close()
    }

    @Test
    fun `the ground and its imagery go up as the binding takes them`() = runTest {
        val decoder = BitmapDecoderOf { Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565) }
        val s = scene(SceneSources(FixtureFiles(), FakeHeights(), FakeImagery(), decoder))
        s.frames(150)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertTrue("the ground drawn", GlCalls.elements.any { it == GLES30.GL_UNSIGNED_SHORT })
        assertTrue("imagery decoded", decoder.made > 0)
        assertTrue(GlCalls.uploads.all { it.size == it.remaining && it.size > 0 })
        s.close()
    }

    @Test
    fun `imagery the binding will not take costs its tile, never the scene`() = runTest {
        val decoder = BitmapDecoderOf { Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565).also(Bitmap::recycle) }
        val s = scene(SceneSources(FixtureFiles(), FakeHeights(), FakeImagery(), decoder))
        s.frames(150)
        assertTrue("imagery decoded", decoder.made > 0)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertTrue("the ground still drawn", GlCalls.elements.any { it == GLES30.GL_UNSIGNED_SHORT })
        s.close()
    }

    @Test
    fun `a surface changed, the app in the back and a context lost go through the binding as a device takes them`() = runTest {
        val s = scene(SceneSources(FixtureFiles(), FakeHeights(), FakeImagery(), BitmapDecoderOf { Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565) }))
        s.withRoute()
        s.frames(40)
        s.renderer.onSurfaceChanged(null, 2200, 1080)
        s.frames(5)
        s.session.trim()
        s.renderer.releaseTrimmed()
        s.frames(5)
        s.open()
        s.frames(20)
        assertEquals(SceneStatus.Drawing, s.session.status.value)
        assertTrue(lines > 0)
        s.close()
    }

    /** Decodes every tile as [make] makes a bitmap, counting them. */
    private class BitmapDecoderOf(private val make: () -> Bitmap) : ImageryDecoder {
        var made = 0

        override fun decode(bytes: ByteArray): ImageryImage = BitmapImage(make()).also { made++ }
    }
}
