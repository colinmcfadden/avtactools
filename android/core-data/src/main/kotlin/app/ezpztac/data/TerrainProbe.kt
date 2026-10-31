package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiClient
import app.ezpztac.network.terrainHeightmap
import app.ezpztac.scene3d.TerrainTileId
import app.ezpztac.scene3d.TerrainTiling
import kotlinx.coroutines.CancellationException

/**
 * Whether the server has any ground near a place (the web's `probeTerrain`): its heightmap tile at [TerrainTiling.PROBE_LEVEL]. Worth asking up
 * front, as the web does: with no terrain the point cloud has nothing under it, which reads as a fault in the drawing rather than a server
 * started without its DEMs or a place outside them.
 */
public fun interface TerrainProbe {
    /** True when there is ground, false when the server has none there (204), null when it could not be asked. */
    public suspend fun hasGround(at: LatLon): Boolean?
}

/** [TerrainProbe] over the real client. */
public class ApiClientTerrainProbe(private val client: ApiClient) : TerrainProbe {
    override suspend fun hasGround(at: LatLon): Boolean? {
        val tile = TerrainTiling.tileAt(at.lat, at.lon, TerrainTiling.PROBE_LEVEL)
        if (!TerrainTiling.exists(tile)) return false
        return try {
            client.terrainHeightmap(tile.level, tile.x, tile.y) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The terrain's heights, a tile at a time (Cesium's geographic tiling): its 65 × 65 heights, rows from the north, metres above the ellipsoid; or
 * null where the server has no DEM (204), which is never read as ground. [ApiClient] in the app: background work, held while an analysis, a
 * viewshed or an export runs.
 */
public fun interface TerrainHeights {
    public suspend fun tile(tile: TerrainTileId): ShortArray?
}

/** [TerrainHeights] over the real client. */
public class ApiClientTerrainHeights(private val client: ApiClient) : TerrainHeights {
    override suspend fun tile(tile: TerrainTileId): ShortArray? = client.terrainHeightmap(tile.level, tile.x, tile.y)
}
