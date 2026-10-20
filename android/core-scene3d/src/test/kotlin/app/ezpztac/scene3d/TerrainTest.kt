package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** The ground as Cesium's globe measures, meshes and chooses it, over the server's heightmaps, with no ground drawn where the server has none. */
class TerrainTest {
    private val lz = LatLonDeg(34.591552, -84.128225)
    private val frame = LocalFrame(lz.lat, lz.lon)
    private val deg = PI / 180

    private data class LatLonDeg(val lat: Double, val lon: Double)

    @Test
    fun `a tile's error is Cesium's estimate for a heightmap, halved at every level, and its skirt four times that, at most a kilometre`() {
        assertEquals(6378137.0 * 2 * PI * 0.25 / 130, TerrainLook.LEVEL_ZERO_ERROR, 1e-9)
        assertEquals(TerrainLook.LEVEL_ZERO_ERROR / 16384, TerrainLook.levelError(14), 1e-12)
        assertEquals(1000.0, TerrainLook.skirtHeight(0))
        assertEquals(4 * TerrainLook.levelError(14), TerrainLook.skirtHeight(14), 1e-12)
        assertEquals(2.0, TerrainLook.MAXIMUM_SCREEN_SPACE_ERROR)
    }

    private fun ramp(): ShortArray = ShortArray(65 * 65) { i -> (100 + i / 65 + 2 * (i % 65)).toShort() }

    @Test
    fun `a mesh puts the heights where Cesium does, the first and last of a row on the tile's edges, rows from the north`() {
        val tile = TerrainTiling.tileAt(lz.lat, lz.lon, 14)
        val b = TerrainTiling.bounds(tile)
        val heights = ramp()
        val v = TerrainMeshes.vertices(tile, heights, frame)
        assertEquals(TerrainMeshes.VERTICES * TerrainMeshes.FLOATS, v.size)
        fun at(index: Int) = Vec3(v[index * 5].toDouble(), v[index * 5 + 1].toDouble(), v[index * 5 + 2].toDouble())
        fun near(expected: Vec3, actual: Vec3) {
            assertEquals(expected.x, actual.x, 1e-2)
            assertEquals(expected.y, actual.y, 1e-2)
            assertEquals(expected.z, actual.z, 1e-2)
        }
        near(frame.fromGeodetic(b.north, b.west, 100.0), at(0))
        near(frame.fromGeodetic(b.north, b.east, 100.0 + 128), at(64))
        near(frame.fromGeodetic(b.south, b.west, 100.0 + 64), at(64 * 65))
        near(frame.fromGeodetic(b.south, b.east, 100.0 + 64 + 128), at(65 * 65 - 1))
        near(frame.fromGeodetic((b.north + b.south) / 2, (b.west + b.east) / 2, 100.0 + 32 + 64), at(32 * 65 + 32))
        // Each skirt is its edge, hanging four times the level's error below it.
        val skirt = TerrainLook.skirtHeight(14)
        near(frame.fromGeodetic(b.north, b.west + (b.east - b.west) * 10 / 64, 100.0 + 20 - skirt), at(65 * 65 + 10))
        near(frame.fromGeodetic(b.north - (b.north - b.south) * 7 / 64, b.east, 100.0 + 7 + 128 - skirt), at(65 * 65 + 65 + 7))
        near(frame.fromGeodetic(b.south, b.west + (b.east - b.west) * 3 / 64, 100.0 + 64 + 6 - skirt), at(65 * 65 + 2 * 65 + 3))
        near(frame.fromGeodetic(b.north - (b.north - b.south) * 50 / 64, b.west, 100.0 + 50 - skirt), at(65 * 65 + 3 * 65 + 50))
    }

    @Test
    fun `a vertex's place in the tile's Mercator rectangle runs evenly west to east and by Mercator north to south`() {
        val tile = TerrainTiling.tileAt(lz.lat, lz.lon, 12)
        val b = TerrainTiling.bounds(tile)
        val v = TerrainMeshes.vertices(tile, ramp(), frame)
        fun uv(index: Int) = v[index * 5 + 3].toDouble() to v[index * 5 + 4].toDouble()
        assertEquals(0.0 to 0.0, uv(0))
        assertEquals(1.0, uv(65 * 65 - 1).first, 1e-6)
        assertEquals(1.0, uv(65 * 65 - 1).second, 1e-6)
        assertEquals(16 / 64.0, uv(40 * 65 + 16).first, 1e-6)
        val lat = b.north - (b.north - b.south) * 40 / 64
        val y0 = ImageryTiling.mercatorY(b.north)
        val y1 = ImageryTiling.mercatorY(b.south)
        assertEquals((ImageryTiling.mercatorY(lat) - y0) / (y1 - y0), uv(40 * 65 + 16).second, 1e-6)
        assertFalse(uv(32 * 65).second == 0.5, "Mercator, not even")
        // A skirt vertex is laid on as its edge vertex is.
        assertEquals(uv(64 * 65 + 9), uv(65 * 65 + 2 * 65 + 9))
    }

    @Test
    fun `every tile has the same triangles, two a cell and two a skirt step, all within the vertices`() {
        val i = TerrainMeshes.INDICES
        assertEquals(64 * 64 * 6 + 4 * 64 * 6, i.size)
        assertTrue(i.all { (it.toInt() and 0xFFFF) < TerrainMeshes.VERTICES })
        assertEquals(listOf(0, 65, 1, 1, 65, 66), i.take(6).map { it.toInt() })
        // The first skirt triangle hangs from the north edge's first step.
        assertEquals(listOf(0, 65 * 65, 1, 1, 65 * 65, 65 * 65 + 1), i.drop(64 * 64 * 6).take(6).map { it.toInt() })
    }

    // -- The globe's choice of tiles ---------------------------------------------------------------------------------------------------------

    /** A server with DEMs for a square of [half] degrees round the LZ, flat at [ground] m: every other tile is a 204. */
    private class Server(val center: LatLonDeg, val half: Double = 0.5, val ground: Short = 340) {
        val asked = mutableListOf<TerrainTileId>()

        fun covers(tile: TerrainTileId): Boolean {
            val b = TerrainTiling.bounds(tile)
            return b.west < center.lon + half && b.east > center.lon - half && b.south < center.lat + half && b.north > center.lat - half
        }

        fun answer(tile: TerrainTileId): ShortArray? {
            asked += tile
            return if (covers(tile)) ShortArray(65 * 65) { ground } else null
        }
    }

    private fun camera(range: Double, pitchDeg: Double = -35.0): ViewCamera {
        val (p, h, pt) = lookAt(frame, frame.fromGeodetic(lz.lat, lz.lon, 340.0), -30 * deg, pitchDeg * deg, range)
        return ViewCamera(p, h, pt, 1080, 2200, SceneDepth.NEAR, SceneDepth.FAR, pixelRatio = 2.75)
    }

    /** Frames until nothing more is asked for, answering every fetch. */
    private fun TerrainScene.settle(server: Server, camera: ViewCamera): TerrainFrame {
        repeat(500) {
            val f = frame(camera, this@TerrainTest.frame, 0)
            if (f.fetch.isEmpty()) return f
            assertTrue(f.fetch.size <= maxFetches)
            for (tile in f.fetch) arrived(tile, server.answer(tile), this@TerrainTest.frame)
        }
        error("still fetching")
    }

    private fun isAncestor(a: TerrainTileId, b: TerrainTileId): Boolean {
        if (a.level >= b.level) return false
        val shift = b.level - a.level
        return b.x shr shift == a.x && b.y shr shift == a.y
    }

    @Test
    fun `from the two tiles of level 0 it comes down to tiles fine enough round the LZ, each drawn once, none an ancestor of another`() {
        val server = Server(lz)
        val scene = TerrainScene()
        val c = camera(500.0)
        val settled = scene.settle(server, c)
        val drawn = settled.drawn.map { it.tile }
        assertTrue(drawn.isNotEmpty())
        assertEquals(drawn.size, drawn.toSet().size)
        for (a in drawn) for (b in drawn) assertFalse(isAncestor(a, b), "$a over $b")
        // Near the LZ the tiles are fine enough for the screen: their error under two pixels.
        val under = drawn.first()
        val volume = TerrainMeshes.volume(under, 340.0 - TerrainLook.skirtHeight(under.level), 340.0, frame)
        val sse = TerrainLook.levelError(under.level) * c.heightPx / (volume.distanceTo(c.position) * c.sseDenominator) / c.pixelRatio
        assertTrue(sse <= 2.0, "$under at $sse")
        assertTrue(under.level >= 12, "$under")
        // Fetched coarse first: every tile's parent was asked for before it.
        val order = server.asked.withIndex().associate { (i, t) -> t to i }
        for (t in server.asked) if (t.level > 0) {
            val parent = TerrainTileId(t.level - 1, t.x / 2, t.y / 2)
            assertTrue(order.getValue(parent) < order.getValue(t), "$parent before $t")
        }
    }

    @Test
    fun `none is drawn where the server has none, and nothing under such a tile is asked for`() {
        val server = Server(lz)
        val scene = TerrainScene()
        val settled = scene.settle(server, camera(500.0))
        val empty = server.asked.filter { !server.covers(it) }.toSet()
        assertTrue(empty.isNotEmpty(), "the other hemisphere at least")
        assertTrue(TerrainTileId(0, 1, 0) in empty)
        assertTrue(settled.drawn.none { it.tile in empty })
        for (t in server.asked) for (e in empty) assertFalse(isAncestor(e, t), "$t asked for under $e")
    }

    @Test
    fun `a tile is drawn in its children's place only once all four are in hand, and all four are asked for together`() {
        val scene = TerrainScene(maxFetches = 6)
        val c = camera(500.0)
        val server = Server(lz)
        // Both roots: one with ground, one without.
        val first = scene.frame(c, frame, 0)
        assertEquals(listOf(TerrainTileId(0, 0, 0), TerrainTileId(0, 1, 0)), first.fetch)
        assertTrue(first.drawn.isEmpty())
        for (t in first.fetch) scene.arrived(t, server.answer(t), frame)
        val second = scene.frame(c, frame, 0)
        assertEquals(listOf(TerrainTileId(0, 0, 0)), second.drawn.map { it.tile })
        assertEquals(TerrainScene.childrenOf(TerrainTileId(0, 0, 0)).toSet(), second.fetch.toSet())
        // Three of the four come: the parent is still drawn.
        for (t in second.fetch.take(3)) scene.arrived(t, server.answer(t), frame)
        assertEquals(listOf(TerrainTileId(0, 0, 0)), scene.frame(c, frame, 0).drawn.map { it.tile })
        scene.arrived(second.fetch[3], server.answer(second.fetch[3]), frame)
        assertTrue(scene.frame(c, frame, 0).drawn.none { it.tile.level == 0 })
    }

    @Test
    fun `a tile whose answer could not be read is never asked for again, its parent drawn in its place, and one that met no connection is, after a pause`() {
        val scene = TerrainScene()
        val c = camera(500.0)
        val server = Server(lz)
        for (t in scene.frame(c, frame, 0).fetch) scene.arrived(t, server.answer(t), frame)
        val children = scene.frame(c, frame, 0).fetch
        assertNull(scene.failed(children[0], again = false))
        for (t in children.drop(1)) scene.arrived(t, server.answer(t), frame)
        repeat(3) {
            val f = scene.frame(c, frame, 3_600_000L * it)
            assertTrue(children[0] !in f.fetch)
            assertEquals(listOf(TerrainTileId(0, 0, 0)), f.drawn.map { d -> d.tile })
        }
        val retry = TerrainScene()
        for (t in retry.frame(c, frame, 0).fetch) {
            if (t.x == 0) assertEquals(TerrainScene.RETRY_MS, retry.failed(t, again = true)) else retry.arrived(t, null, frame)
        }
        assertTrue(retry.frame(c, frame, TerrainScene.RETRY_MS - 1).fetch.isEmpty())
        assertEquals(listOf(TerrainTileId(0, 0, 0)), retry.frame(c, frame, TerrainScene.RETRY_MS).fetch)
    }

    @Test
    fun `over what it may hold it lets go of the tiles longest unused, never one the frame needed`() {
        val server = Server(lz)
        val near = camera(500.0)
        val all = TerrainScene()
        all.settle(server, near)
        val needed = all.heldTiles
        val scene = TerrainScene(holdTiles = needed - 5)
        scene.settle(server, near)
        // Everything the near view needs is kept while it is looked at.
        val again = scene.frame(near, frame, 0)
        assertTrue(again.letGo.isEmpty())
        // Seen from far away, the fine tiles round the LZ are not needed and go, the coarsest first kept.
        val far = scene.frame(camera(200_000.0), frame, 0)
        assertTrue(far.letGo.isNotEmpty())
        assertTrue(far.letGo.none { gone -> far.drawn.any { it.tile == gone } })
        assertTrue(scene.heldTiles <= needed - 5)
        // What the far view drew is still in hand: drawn again with nothing fetched.
        val farAgain = scene.frame(camera(200_000.0), frame, 0)
        assertEquals(far.drawn.map { it.tile }, farAgain.drawn.map { it.tile })
        assertTrue(farAgain.fetch.isEmpty())
    }

    @Test
    fun `a tile out of view is not drawn, unless it is wider than six degrees, whose box is too coarse to cull by`() {
        val scene = TerrainScene()
        val c = camera(500.0)
        val drawn = scene.settle(Server(lz, half = 3.0), c).drawn
        for (d in drawn) {
            val b = TerrainTiling.bounds(d.tile)
            if (b.east - b.west > TerrainScene.CULL_ALWAYS_DEGREES) continue
            val volume = TerrainMeshes.volume(d.tile, 340.0 - TerrainLook.skirtHeight(d.tile.level), 340.0, frame)
            assertTrue(c.frustum.visibility(volume) != Visibility.OUTSIDE, "${d.tile}")
        }
    }

    @Test
    fun `the ground at a point is the finest tile's in hand, between its four nearest heights, and none with no tile there`() {
        val scene = TerrainScene()
        assertNull(scene.heightAt(lz.lat, lz.lon))
        val coarse = TerrainTiling.tileAt(lz.lat, lz.lon, 10)
        scene.arrived(coarse, ShortArray(65 * 65) { 300 }, frame)
        assertEquals(10 to 300.0, scene.heightAt(lz.lat, lz.lon))
        // A finer tile, heights rising 2 m a column eastward and 1 m a row southward: read between the four round the point.
        val fine = TerrainTiling.tileAt(lz.lat, lz.lon, 15)
        scene.arrived(fine, ramp(), frame)
        val b = TerrainTiling.bounds(fine)
        val fx = (lz.lon - b.west) / (b.east - b.west) * 64
        val fy = (b.north - lz.lat) / (b.north - b.south) * 64
        val (level, height) = scene.heightAt(lz.lat, lz.lon)!!
        assertEquals(15, level)
        assertEquals(100 + fy + 2 * fx, height, 1e-9)
        // On a tile's edges and corners too.
        assertEquals(15 to 100.0, scene.heightAt(b.north, b.west))
        assertEquals(15 to 100.0 + 64 + 128, scene.heightAt(b.south + 1e-12, b.east - 1e-12)!!.let { it.first to Math.round(it.second).toDouble() })
        // A tile the server had none for gives none.
        val empty = TerrainScene()
        empty.arrived(coarse, null, frame)
        assertNull(empty.heightAt(lz.lat, lz.lon))
    }
}
