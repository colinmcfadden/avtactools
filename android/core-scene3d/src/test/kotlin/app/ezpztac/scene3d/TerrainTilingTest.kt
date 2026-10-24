package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which terrain tile holds a place, as the web's `terrainTileAt` says (contracts/fixtures/viewer3d/terrain.json), and each tile's extent. */
class TerrainTilingTest {
    private val fixture = Fixtures.load("viewer3d/terrain.json")

    @Test
    fun `the tiles are the web's, at every level and edge`() {
        val constants = fixture.getValue("constants").jsonObject
        assertEquals(constants.getValue("TERRAIN_SAMPLES").jsonPrimitive.int, TerrainTiling.SAMPLES)
        assertEquals(constants.getValue("TERRAIN_MAX_LEVEL").jsonPrimitive.int, TerrainTiling.MAX_LEVEL)
        assertEquals(constants.getValue("PROBE_LEVEL").jsonPrimitive.int, TerrainTiling.PROBE_LEVEL)
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 60)
        for (c in cases) {
            val lat = c.getValue("lat").jsonPrimitive.double
            val lon = c.getValue("lon").jsonPrimitive.double
            val level = c.getValue("level").jsonPrimitive.int
            val expected = c.getValue("expected").jsonObject
            val tile = TerrainTiling.tileAt(lat, lon, level)
            assertEquals(expected.getValue("x").jsonPrimitive.doubleOrNull!!.toInt(), tile.x, "x of $lat, $lon at $level")
            assertEquals(expected.getValue("y").jsonPrimitive.doubleOrNull!!.toInt(), tile.y, "y of $lat, $lon at $level")
        }
    }

    @Test
    fun `a tile's extent holds the places that fall in it, and the server's tile under the LZ is the recorded one`() {
        for ((lat, lon) in listOf(34.591552 to -84.128225, -33.8688 to 151.2093, 0.0 to 0.0, 64.8378 to -147.7164)) {
            for (level in listOf(0, 3, 9, 14, 22)) {
                val tile = TerrainTiling.tileAt(lat, lon, level)
                val b = TerrainTiling.bounds(tile)
                assertTrue(lon >= b.west && lon < b.east && lat <= b.north && lat > b.south, "$lat, $lon at $level in $b")
                assertTrue(TerrainTiling.exists(tile))
            }
        }
        // backend/tests/test_network_fixtures.py recorded /api/terrain/heightmap/14/8726/5043 as the tile under the LZ.
        assertEquals(TerrainTileId(14, 8726, 5043), TerrainTiling.tileAt(34.591552, -84.128225, 14))
        assertEquals(TerrainBounds(-180.0, -90.0, 0.0, 90.0), TerrainTiling.bounds(TerrainTileId(0, 0, 0)))
        assertTrue(!TerrainTiling.exists(TerrainTiling.tileAt(0.0, 180.0, 1)))                    // the web's tile east of the antimeridian is none
        assertTrue(!TerrainTiling.exists(TerrainTiling.tileAt(-90.0, 0.0, 1)))
    }
}
