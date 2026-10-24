package app.ezpztac.viewer3d

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a finger on the scene's sides pans it rather than going Back: the middle of each edge, as much as Android lets an app take. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SceneEdgesTest {
    @Test
    fun `the middle 200 points of each side are the scene's, 40 points in`() {
        val d = 2.75f
        val bands = edgeBands(1080, 2400, d)
        assertEquals(listOf(Rect(0, 925, 110, 1475), Rect(970, 925, 1080, 1475)), bands)
        assertTrue(bands.all { it.height() == (200 * d).toInt() })
    }

    @Test
    fun `a short scene gives its whole height, a narrow one no more than half its width, and none is no band`() {
        assertEquals(listOf(Rect(0, 0, 80, 300), Rect(1840, 0, 1920, 300)), edgeBands(1920, 300, 2f))
        assertEquals(listOf(Rect(0, 0, 50, 300), Rect(50, 0, 100, 300)), edgeBands(100, 300, 2f))
        assertEquals(emptyList<Rect>(), edgeBands(0, 300, 2f))
    }
}
