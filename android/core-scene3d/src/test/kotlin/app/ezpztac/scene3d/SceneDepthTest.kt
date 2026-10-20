package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.log2

/** The view's logarithmic depth and what the eye-dome lighting reads from it; the level ground the camera holds to before there is terrain. */
class SceneDepthTest {
    @Test
    fun `the depth runs from the near plane to the far one by octaves`() {
        assertEquals(0.0, SceneDepth.depthOf(SceneDepth.NEAR))
        assertEquals(0.0, SceneDepth.depthOf(0.01), "nearer than the near plane is on it")
        assertEquals(1.0, SceneDepth.depthOf(SceneDepth.FAR), 1e-12)
        assertTrue(SceneDepth.depthOf(2 * SceneDepth.FAR) > 1.0)
        assertEquals(log2(SceneDepth.FAR / SceneDepth.NEAR), SceneDepth.logRange, 1e-12)
    }

    @Test
    fun `the response is how many octaves nearer a neighbour is, and nothing for one further away`() {
        assertEquals(1.0, SceneDepth.response(SceneDepth.depthOf(100.0), SceneDepth.depthOf(50.0)), 1e-9)
        assertEquals(log2(1000.0 / 999.0), SceneDepth.response(SceneDepth.depthOf(1000.0), SceneDepth.depthOf(999.0)), 1e-9)
        assertEquals(0.0, SceneDepth.response(SceneDepth.depthOf(50.0), SceneDepth.depthOf(100.0)))
    }

    @Test
    fun `a 24-bit depth holds about a millionth of any distance`() {
        // One step of the buffer is 2^-24 of the depth: d(depth)/d(distance) is 1 / (distance ln 2 logRange), so the step is that much of the distance.
        val relative = ln(2.0) * SceneDepth.logRange / (1 shl 24)
        assertTrue(relative < 1e-6, "$relative")
        for (distance in listOf(1.0, 30.0, 1_000.0, 20_000.0)) {
            val step = SceneDepth.depthOf(distance * (1 + relative)) - SceneDepth.depthOf(distance)
            assertEquals(1.0 / (1 shl 24), step, 1e-12)
        }
    }

    private fun points(vararg xyz: Double) = ScenePoints(xyz.size / 3, FloatArray(xyz.size) { xyz[it].toFloat() }, ByteArray(xyz.size))

    @Test
    fun `the ground is the middle height of the points round the LZ, else of all of them, and none with no points`() {
        // Twenty-one points round the LZ at 340 to 360 m, and a tree well away from it.
        val round = (0..20).flatMap { listOf(it.toDouble(), 0.0, 340.0 + it) } + listOf(100.0, 100.0, 400.0)
        assertEquals(350.0, SceneGround.of(points(*round.toDoubleArray())))
        // Too few near it: every point counts, and an even count takes the middle two.
        assertEquals(355.0, SceneGround.of(points(0.0, 0.0, 340.0, 100.0, 0.0, 350.0, 0.0, 200.0, 360.0, 300.0, 0.0, 400.0)))
        assertNull(SceneGround.of(points()))
    }
}
