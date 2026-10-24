package app.ezpztac.data

import app.ezpztac.network.ApiClient
import app.ezpztac.network.lidarTilesetFile
import app.ezpztac.scene3d.TilesetPaths

/** The files of a built point cloud: by its opaque key and a path inside its store. [ApiClient] in the app; a stand-in in tests. */
public fun interface TilesetFiles {
    public suspend fun file(key: String, path: String): ByteArray
}

/** [TilesetFiles] over the real client. */
public class ApiClientTilesetFiles(private val client: ApiClient) : TilesetFiles {
    override suspend fun file(key: String, path: String): ByteArray = client.lidarTilesetFile(key, path)
}

/**
 * Where a tileset is in the server's store, read from the URL a lookup gave ([LidarLookup.url], [LidarLookup.contextUrl]:
 * `/lidar/tilesets/<key>/<path>`, relative to the API's root): its key, and its root file's path inside the store. The URL is the server's, so
 * one of any other shape, or whose path is not a plain one inside the store ([TilesetPaths]), is no tileset.
 */
public data class TilesetAddress(val key: String, val path: String) {
    public companion object {
        private val URL = Regex("/lidar/tilesets/([0-9a-f]{16})/([^?#]+)")

        public fun of(url: String?): TilesetAddress? {
            val match = URL.matchEntire(url ?: return null) ?: return null
            val path = match.groupValues[2]
            // Resolved from the store's top, a plain path comes back as it went; anything that climbs, escapes or names a hidden file does not.
            if (TilesetPaths.resolve("tileset.json", path) != path) return null
            return TilesetAddress(match.groupValues[1], path)
        }
    }
}
