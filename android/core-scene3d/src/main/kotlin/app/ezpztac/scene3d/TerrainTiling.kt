package app.ezpztac.scene3d

import kotlin.math.floor

/** One terrain tile: its level and place in the tiling. */
public data class TerrainTileId(val level: Int, val x: Int, val y: Int)

/** A tile's extent in degrees. */
public data class TerrainBounds(val west: Double, val south: Double, val east: Double, val north: Double)

/**
 * The terrain's tiling: Cesium's geographic one, which the server's heightmaps are cut in (`backend/terrain_tiles.py`, `tile_bounds`): two tiles
 * across at level 0, each 180° square, quartered at every level below; x counts from 180° W, y from 90° N. Held to the web's
 * `terrainTileAt` (contracts/fixtures/viewer3d/terrain.json).
 */
public object TerrainTiling {
    /** The heights in a tile, each way: the server's 65, edges shared with the neighbours. */
    public const val SAMPLES: Int = 65

    /** The deepest level the server serves. */
    public const val MAX_LEVEL: Int = 22

    /** The level the web asks at to find out whether there is any ground at all near the LZ. */
    public const val PROBE_LEVEL: Int = 12

    public fun across(level: Int): Int = 2 shl level

    public fun down(level: Int): Int = 1 shl level

    /** The tile holding [lat], [lon] at [level], the web's way: a place on an edge is the tile east or south of it. */
    public fun tileAt(lat: Double, lon: Double, level: Int): TerrainTileId =
        TerrainTileId(level, floor((lon + 180.0) / (360.0 / across(level))).toInt(), floor((90.0 - lat) / (180.0 / down(level))).toInt())

    /** The tile's extent, as the server's `tile_bounds` gives it. */
    public fun bounds(tile: TerrainTileId): TerrainBounds {
        val width = 360.0 / across(tile.level)
        val height = 180.0 / down(tile.level)
        val west = -180.0 + tile.x * width
        val north = 90.0 - tile.y * height
        return TerrainBounds(west, north - height, west + width, north)
    }

    /** Whether the tiling has the tile at all. */
    public fun exists(tile: TerrainTileId): Boolean =
        tile.level in 0..MAX_LEVEL && tile.x in 0 until across(tile.level) && tile.y in 0 until down(tile.level)
}
