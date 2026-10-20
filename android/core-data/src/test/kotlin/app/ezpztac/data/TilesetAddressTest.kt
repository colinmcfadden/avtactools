package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A lookup's URL read as the key and the root's path a tileset's files are asked for by, and every URL that is not one refused. */
class TilesetAddressTest {
    private val key = "0123456789abcdef"

    @Test
    fun `the core and its ring are read from the URLs the server gives`() {
        assertEquals(TilesetAddress(key, "tileset.json"), TilesetAddress.of("/lidar/tilesets/$key/tileset.json"))
        assertEquals(TilesetAddress(key, "context/tileset.json"), TilesetAddress.of("/lidar/tilesets/$key/context/tileset.json"))
    }

    @Test
    fun `a URL of any other shape, or one that would leave the store, is no tileset`() {
        for (url in listOf(
            null, "", "tileset.json", "/api/lidar/tilesets/$key/tileset.json", "https://elsewhere.example/lidar/tilesets/$key/tileset.json",
            "/lidar/tilesets/0123456789ABCDEF/tileset.json", "/lidar/tilesets/0123/tileset.json", "/lidar/tilesets/$key/",
            "/lidar/tilesets/$key/../$key/tileset.json", "/lidar/tilesets/$key/./tileset.json", "/lidar/tilesets/$key/.hidden/tileset.json",
            "/lidar/tilesets/$key/a//tileset.json", "/lidar/tilesets/$key/tileset.json?x=1", "/lidar/tilesets/$key/tile%2fset.json",
            "/lidar/tilesets/$key/a\\tileset.json",
        )) {
            assertNull(url, TilesetAddress.of(url))
        }
    }
}
