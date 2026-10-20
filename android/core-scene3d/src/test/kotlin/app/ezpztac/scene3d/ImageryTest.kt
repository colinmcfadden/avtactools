package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln

/** The satellite imagery's tiling as Cesium lays the web's Mapbox layer on the globe: its level for a terrain tile, the tiles covering it, and where a vertex falls. */
class ImageryTest {
    private val lat = 34.591552
    private val lon = -84.128225

    @Test
    fun `Web Mercator runs from its north edge to its south, the equator half way`() {
        assertEquals(0.5, ImageryTiling.mercatorY(0.0), 1e-15)
        assertEquals(0.0, ImageryTiling.mercatorY(ImageryTiling.MAX_LATITUDE), 1e-12)
        assertEquals(1.0, ImageryTiling.mercatorY(-ImageryTiling.MAX_LATITUDE), 1e-12)
        assertEquals(0.0, ImageryTiling.mercatorY(89.9), 1e-12)
        assertEquals(lat, ImageryTiling.latitudeAt(ImageryTiling.mercatorY(lat)), 1e-12)
        assertEquals(0.5, ImageryTiling.mercatorX(0.0))
        assertEquals(0.0, ImageryTiling.mercatorX(-180.0))
    }

    /** Cesium's getLevelWithMaximumTexelSpacing, worked by hand. */
    private fun cesiumLevel(terrainLevel: Int, nearestEquator: Double): Int {
        val spacing = 6378137.0 * 2 * PI * cos(nearestEquator * PI / 180) / 1024
        return floor(ln(spacing / TerrainLook.levelError(terrainLevel)) / ln(2.0) + 0.5).toInt().coerceIn(0, 20)
    }

    @Test
    fun `the imagery's level is Cesium's, its pixels as far apart as the terrain tile's error, at its latitude nearest the equator`() {
        for (level in 0..22) {
            val tile = TerrainTiling.tileAt(lat, lon, level)
            val b = TerrainTiling.bounds(tile)
            val nearest = if (b.south > 0) b.south else if (b.north < 0) b.north else 0.0
            assertEquals(cesiumLevel(level, nearest), ImageryTiling.levelFor(tile), "level $level")
        }
        // At the LZ's latitude a level-14 terrain tile takes level-13 imagery; never past Mapbox's 20.
        assertEquals(13, ImageryTiling.levelFor(TerrainTiling.tileAt(lat, lon, 14)))
        assertEquals(20, ImageryTiling.levelFor(TerrainTiling.tileAt(lat, lon, 22)))
        assertEquals(0, ImageryTiling.levelFor(TerrainTileId(0, 0, 0)))
    }

    @Test
    fun `the tiles covering a terrain tile are those it overlaps by more than a 512th of itself`() {
        for (level in listOf(3, 8, 12, 14, 17)) {
            val tile = TerrainTiling.tileAt(lat, lon, level)
            val m = ImageryTiling.levelFor(tile)
            val b = TerrainTiling.bounds(tile)
            val n = 1 shl m
            // By brute force: every imagery tile whose rectangle overlaps the terrain tile's by more than the margin each way.
            val closeX = (b.east - b.west) / 512
            val closeY = (b.north - b.south) / 512
            val expected = buildList {
                for (y in 0 until n) for (x in 0 until n) {
                    if (n > 4096 && (x < ImageryTiling.mercatorX(b.west) * n - 2 || x > ImageryTiling.mercatorX(b.east) * n + 2)) continue
                    if (n > 4096 && (y < ImageryTiling.mercatorY(b.north) * n - 2 || y > ImageryTiling.mercatorY(b.south) * n + 2)) continue
                    val west = x * 360.0 / n - 180
                    val east = (x + 1) * 360.0 / n - 180
                    val north = ImageryTiling.latitudeAt(y.toDouble() / n)
                    val south = ImageryTiling.latitudeAt((y + 1.0) / n)
                    val overlapX = minOf(east, b.east) - maxOf(west, b.west)
                    val overlapY = minOf(north, b.north) - maxOf(south, b.south)
                    if (overlapX >= closeX && overlapY >= closeY) add(ImageryTileId(m, x, y))
                }
            }
            assertEquals(expected, ImageryTiling.covering(tile, m), "level $level")
            assertTrue(expected.isNotEmpty())
        }
        // A terrain tile whose edges lie on an imagery tile's edges takes no sliver of the next: level 1's north-western quarter of the west
        // ends at 0° east and at the equator, as imagery level 1's north-western tile does.
        assertEquals(listOf(ImageryTileId(1, 0, 0)), ImageryTiling.covering(TerrainTileId(1, 1, 0), 1))
        assertEquals(listOf(ImageryTileId(0, 0, 0)), ImageryTiling.covering(TerrainTileId(0, 0, 0), 0))
    }

    @Test
    fun `a vertex falls in an imagery tile where its own Mercator place does, to the pixel even at level 20`() {
        val frame = LocalFrame(lat, lon)
        for (level in listOf(10, 16, 21)) {
            val tile = TerrainTiling.tileAt(lat, lon, level)
            val vertices = TerrainMeshes.vertices(tile, ShortArray(65 * 65) { 340 }, frame)
            val b = TerrainTiling.bounds(tile)
            for (imagery in ImageryTiling.covering(tile, ImageryTiling.levelFor(tile))) {
                val t = ImageryTiling.uvTransform(tile, imagery)
                val n = (1 shl imagery.level).toDouble()
                for ((row, column) in listOf(0 to 0, 64 to 64, 40 to 16, 7 to 58)) {
                    val index = row * 65 + column
                    val u = vertices[index * 5 + 3] * t[0] + t[2]
                    val v = vertices[index * 5 + 4] * t[1] + t[3]
                    val vLat = b.north - (b.north - b.south) * row / 64
                    val vLon = b.west + (b.east - b.west) * column / 64
                    // A tenth of a pixel of a 1024-pixel tile.
                    assertEquals(ImageryTiling.mercatorX(vLon) * n - imagery.x, u.toDouble(), 1e-4, "$tile $imagery $row,$column")
                    assertEquals(ImageryTiling.mercatorY(vLat) * n - imagery.y, v.toDouble(), 1e-4, "$tile $imagery $row,$column")
                }
            }
        }
    }

    // -- What is laid on and fetched ------------------------------------------------------------------------------------------------------

    private val terrain = TerrainTiling.tileAt(lat, lon, 14)
    private val wanted = ImageryTiling.covering(terrain, ImageryTiling.levelFor(terrain))

    @Test
    fun `the tiles a terrain tile wants are fetched, coarse first, and laid on once they are in hand`() {
        val scene = ImageryScene()
        val first = scene.frame(listOf(terrain), 0)
        assertEquals(wanted, first.fetch)
        assertTrue(first.byTerrain.getValue(terrain).isEmpty())
        assertFalse(terrain in first.complete)
        assertTrue(scene.frame(listOf(terrain), 0).fetch.isEmpty(), "out already")
        for (id in first.fetch) scene.arrived(id, ImageryScene.TILE_BYTES)
        val drawn = scene.frame(listOf(terrain), 0)
        assertEquals(wanted, drawn.byTerrain.getValue(terrain).map { it.imagery })
        assertTrue(terrain in drawn.complete)
    }

    /**
     * A level-14 terrain tile near the LZ that two imagery tiles cover. Across, the two tilings' edges fall together (both halve 360°); down, Web
     * Mercator's do not fall on the terrain's even degrees, so one a few rows down straddles.
     */
    private val straddling by lazy {
        (0 until 16).map { TerrainTileId(14, terrain.x, terrain.y + it) }.first { ImageryTiling.covering(it, ImageryTiling.levelFor(it)).size >= 2 }
    }

    @Test
    fun `until its own tiles come a terrain tile is laid with the nearest coarser one in hand, and asks for its own`() {
        val terrain = straddling
        val wanted = ImageryTiling.covering(terrain, ImageryTiling.levelFor(terrain))
        val scene = ImageryScene()
        val ancestor = generateSequence(wanted.first()) { it.parent }.drop(2).first()
        scene.frame(emptyList(), 0)
        scene.arrived(ancestor, ImageryScene.TILE_BYTES)
        val f = scene.frame(listOf(terrain), 0)
        assertEquals(listOf(ancestor), f.byTerrain.getValue(terrain).map { it.imagery })
        assertTrue(wanted.containsAll(f.fetch) && f.fetch.isNotEmpty())
        // The ancestor covers the whole tile: nothing of it is bare.
        assertEquals(wanted.all { generateSequence(it) { p -> p.parent }.contains(ancestor) }, terrain in f.complete)
        // One own tile in hand: laid over the ancestor, the finer last.
        scene.arrived(wanted.first(), ImageryScene.TILE_BYTES)
        val g = scene.frame(listOf(terrain), 0)
        assertEquals(ancestor, g.byTerrain.getValue(terrain).first().imagery)
        assertEquals(wanted.first(), g.byTerrain.getValue(terrain).last().imagery)
    }

    @Test
    fun `a tile that could not be read is never asked for again, one that met no connection after a pause`() {
        val scene = ImageryScene()
        val f = scene.frame(listOf(terrain), 0)
        assertNull(scene.failed(f.fetch.first(), again = false))
        if (f.fetch.size > 1) assertEquals(ImageryScene.RETRY_MS, scene.failed(f.fetch[1], again = true))
        repeat(3) { assertTrue(f.fetch.first() !in scene.frame(listOf(terrain), 3_600_000L * it).fetch) }
    }

    @Test
    fun `over its bytes it lets go of the textures longest unused, never one the frame laid on, and a lost context loses them all`() {
        val scene = ImageryScene(holdBytes = ImageryScene.TILE_BYTES)
        val elsewhere = TerrainTiling.tileAt(lat + 1.0, lon + 1.0, 14)
        for (t in listOf(elsewhere, terrain)) {
            for (id in scene.frame(listOf(t), 0).fetch) scene.arrived(id, ImageryScene.TILE_BYTES)
        }
        val f = scene.frame(listOf(terrain), 0)
        assertTrue(f.letGo.isNotEmpty())
        assertTrue(f.letGo.none { gone -> f.byTerrain.getValue(terrain).any { it.imagery == gone } })
        assertEquals(wanted.size * ImageryScene.TILE_BYTES, scene.bytesHeld)
        scene.lostTextures()
        assertEquals(0L, scene.bytesHeld)
        assertEquals(wanted, scene.frame(listOf(terrain), 0).fetch)
    }
}
