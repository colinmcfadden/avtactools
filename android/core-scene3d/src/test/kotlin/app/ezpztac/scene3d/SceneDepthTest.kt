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
    fun `the ground is the lowest tenth of the points round the LZ, and none from a tile that does not surround it`() {
        // Forty points round the LZ, ten on each side, at 340 to 379 m, and a tree well away from it: the fifth lowest of the forty, 343 m.
        val round = (0 until 40).flatMap { i ->
            val (sx, sy) = listOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0)[i % 4]
            listOf(sx * (1 + i % 7), sy * (2 + i % 5), 340.0 + i)
        } + listOf(100.0, 100.0, 300.0)
        val estimate = SceneGround.of(points(*round.toDoubleArray()))!!
        assertEquals(343.0, estimate.z)
        assertEquals(40, estimate.points)
        // The same with fewer of them south-west of the LZ: none (a quarter of an octree's tile), or under a tenth, says nothing; nor do too few.
        fun withSouthWest(kept: Int) = points(*round.chunked(3).filterIndexed { i, _ -> i >= 40 || i % 4 != 2 || i / 4 < kept }.flatten().toDoubleArray())
        assertNull(SceneGround.of(withSouthWest(0)))
        assertNull(SceneGround.of(withSouthWest(3)), "3 of 33 is under a tenth")
        assertEquals(34, SceneGround.of(withSouthWest(4))?.points, "4 of 34 is not")
        assertNull(SceneGround.of(points(*round.take(3 * (SceneGround.ENOUGH - 1)).toDoubleArray())))
        assertNull(SceneGround.of(points()))
    }

    @Test
    fun `in the fixture the preview and the octree's root say where the ground is, under the trees, and a quarter of the octree says nothing`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val doc = TilesetReader.read(app.ezpztac.testing.Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        fun of(path: String): SceneGround.Estimate? {
            val tile = generateSequence(listOf(doc.root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().first { it.content?.path == path }
            return SceneGround.of(PntsReader.read(app.ezpztac.testing.Fixtures.bytes("viewer3d/tileset/$path"), tile.world, frame))
        }
        // The finest tile at the LZ, every point of it ground (368.6 to 369.5 m), and the coarse ones' lowest tenth a metre under it; their middle is in the trees.
        val preview = of("preview.pnts")!!
        val octree = of("points/r.pnts")!!
        assertEquals(368.35, preview.z, 0.01)
        assertEquals(368.02, octree.z, 0.01)
        assertTrue(octree.points > preview.points)
        // Two quarters of the octree: one all canopy over the LZ (its lowest tenth 379.5 m), one ground to one side of it, lower (366.3 m), a few
        // of its points over the lines either side.
        assertNull(of("points/r4.pnts"))
        assertNull(of("points/r2.pnts"))
    }
}
