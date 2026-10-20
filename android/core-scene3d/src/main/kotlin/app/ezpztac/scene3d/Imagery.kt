package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One tile of the satellite imagery: its level and place in the Web Mercator grid, x from 180° W and y from the north. */
public data class ImageryTileId(val level: Int, val x: Int, val y: Int) {
    public val parent: ImageryTileId? get() = if (level == 0) null else ImageryTileId(level - 1, x / 2, y / 2)
}

/**
 * The satellite imagery's tiling, as the web's Cesium layer has it (`MAPBOX_IMAGERY`: Mapbox `satellite-v9`, 512-point tiles at `@2x`, so 1024
 * pixels, to level 20, in Web Mercator), and how it is laid on the terrain: Cesium's choice of imagery level for a terrain tile
 * (`ImageryLayer.getLevelWithMaximumTexelSpacing`: the level whose pixels are as far apart as the terrain tile's error, at the tile's latitude
 * nearest the equator), the imagery tiles that cover it (leaving out one it would touch by less than a 512th of its width, as Cesium does), and
 * where a terrain vertex falls in one of them.
 */
public object ImageryTiling {
    public const val TILE_PIXELS: Int = 1024
    public const val MAX_LEVEL: Int = 20

    /** Web Mercator's edge, where the map is square. */
    public const val MAX_LATITUDE: Double = 85.05112877980659

    /** The fraction of the way across the world from 180° W. */
    public fun mercatorX(lon: Double): Double = (lon + 180.0) / 360.0

    /** The fraction of the way down the world from Web Mercator's north edge. */
    public fun mercatorY(lat: Double): Double = (1.0 - asinh(tan(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))) / PI) / 2.0

    /** The latitude at [y] of the way down. */
    public fun latitudeAt(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /** The imagery level for [tile], Cesium's way (the result of a JavaScript `Math.round`, then between 0 and [MAX_LEVEL]). */
    public fun levelFor(tile: TerrainTileId): Int {
        val b = TerrainTiling.bounds(tile)
        val nearestEquator = when {
            b.south > 0 -> b.south
            b.north < 0 -> b.north
            else -> 0.0
        }
        val levelZeroSpacing = Wgs84.A * 2 * PI * cos(Math.toRadians(nearestEquator)) / TILE_PIXELS
        val level = ln(levelZeroSpacing / TerrainLook.levelError(tile.level)) / ln(2.0)
        return floor(level + 0.5).toInt().coerceIn(0, MAX_LEVEL)
    }

    /** The imagery tiles at [level] that cover [tile], Cesium's way (`_createTileImagerySkeletons`). */
    public fun covering(tile: TerrainTileId, level: Int): List<ImageryTileId> {
        val b = TerrainTiling.bounds(tile)
        val n = 1 shl level
        fun column(lon: Double) = floor(mercatorX(lon) * n).toInt().coerceIn(0, n - 1)
        fun row(lat: Double) = floor(mercatorY(lat) * n).toInt().coerceIn(0, n - 1)
        // Past Web Mercator's edge there is no imagery; a tile wholly past it has none.
        if (b.south >= MAX_LATITUDE || b.north <= -MAX_LATITUDE) return emptyList()
        var west = column(b.west)
        var north = row(b.north)
        var east = column(b.east)
        var south = row(b.south)
        // "Very close" is within a 512th of the terrain tile's width or height.
        val closeX = (b.east - b.west) / 512.0
        val closeY = (b.north - b.south) / 512.0
        fun lonOf(x: Int) = x * 360.0 / n - 180.0
        if (abs(latitudeAt((north + 1).toDouble() / n) - b.north) < closeY && north < south) north++
        if (abs(lonOf(west + 1) - b.west) < closeX && west < east) west++
        if (abs(latitudeAt(south.toDouble() / n) - b.south) < closeY && south > north) south--
        if (abs(lonOf(east) - b.east) < closeX && east > west) east--
        return buildList { for (y in north..south) for (x in west..east) add(ImageryTileId(level, x, y)) }
    }

    /**
     * Where a vertex of [tile] falls in [imagery], from its place in the tile's own Mercator rectangle ([TerrainMeshes.vertices]' u and v):
     * `uv = place * scale + offset`, as four floats, scale then offset, each for u then v. Worked in doubles, so a tile at level 20 is placed
     * to the pixel though floats could not hold its place in the world.
     */
    public fun uvTransform(tile: TerrainTileId, imagery: ImageryTileId): FloatArray {
        val b = TerrainTiling.bounds(tile)
        val n = (1 shl imagery.level).toDouble()
        val x0 = mercatorX(b.west)
        val x1 = mercatorX(b.east)
        val y0 = mercatorY(b.north)
        val y1 = mercatorY(b.south)
        return floatArrayOf(((x1 - x0) * n).toFloat(), ((y1 - y0) * n).toFloat(), (x0 * n - imagery.x).toFloat(), (y0 * n - imagery.y).toFloat())
    }
}

/** An imagery tile to lay on a terrain tile, with where the terrain's vertices fall in it ([ImageryTiling.uvTransform]). */
public class ImageryDraw(public val imagery: ImageryTileId, public val uv: FloatArray)

/**
 * One frame's imagery: for each terrain tile drawn, what to lay on it (coarse first, the finer laid over; [complete] false when part of it has
 * none yet, which is then drawn in the ground's own colour first), what to fetch, and what was let go of (its texture goes too).
 */
public class ImageryFrame(
    public val byTerrain: Map<TerrainTileId, List<ImageryDraw>>,
    public val complete: Set<TerrainTileId>,
    public val fetch: List<ImageryTileId>,
    public val letGo: List<ImageryTileId>,
)

/**
 * The imagery on the terrain: for each terrain tile drawn, the imagery tiles at Cesium's level for it, fetched as they are wanted (at most
 * [maxFetches] out at once, coarse first), and meanwhile the nearest coarser tile in hand laid on in their place, as Cesium shows an
 * ancestor's imagery until a tile's own comes. Held within [holdBytes] of textures, the longest unused let go of first, never one this frame
 * used. A tile that met no connection is asked for again after a pause, twice; one whose answer could not be read, never. A lost GL context
 * takes every texture with it ([lostTextures]): they are fetched again (the HTTP cache has them). Every call takes its lock.
 */
public class ImageryScene(public val maxFetches: Int = MAX_FETCHES, public val holdBytes: Long = HOLD_BYTES) {
    private class Held(val bytes: Long, var lastFrame: Long)
    private class Failure(val tries: Int, val againAtMs: Long?)

    private val held = HashMap<ImageryTileId, Held>()
    private val loading = HashSet<ImageryTileId>()
    private val failures = HashMap<ImageryTileId, Failure>()
    private var frameNumber = 0L
    private var nowMs = 0L
    private var heldBytes = 0L

    @Synchronized
    public fun frame(drawn: List<TerrainTileId>, atMs: Long): ImageryFrame {
        frameNumber++
        nowMs = atMs
        val byTerrain = HashMap<TerrainTileId, List<ImageryDraw>>()
        val complete = HashSet<TerrainTileId>()
        val wanted = LinkedHashSet<ImageryTileId>()
        for (tile in drawn) {
            val draws = LinkedHashMap<ImageryTileId, ImageryDraw>()
            var whole = true
            for (cover in ImageryTiling.covering(tile, ImageryTiling.levelFor(tile))) {
                val inHand = generateSequence(cover) { it.parent }.firstOrNull { it in held }
                if (inHand != cover) {
                    if (status(cover) == TileStatus.UNLOADED) wanted += cover
                }
                if (inHand == null) {
                    whole = false
                    continue
                }
                held.getValue(inHand).lastFrame = frameNumber
                draws.getOrPut(inHand) { ImageryDraw(inHand, ImageryTiling.uvTransform(tile, inHand)) }
            }
            byTerrain[tile] = draws.values.sortedBy { it.imagery.level }
            if (whole && draws.isNotEmpty()) complete += tile
        }
        val fetch = ArrayList<ImageryTileId>()
        for (id in wanted.sortedBy { it.level }) {
            if (loading.size >= maxFetches) break
            loading += id
            fetch += id
        }
        return ImageryFrame(byTerrain, complete, fetch, letGo())
    }

    /** [id] is on the GPU, taking [bytes]. */
    @Synchronized
    public fun arrived(id: ImageryTileId, bytes: Long) {
        loading -= id
        failures.remove(id)
        held.put(id, Held(bytes, frameNumber))?.let { heldBytes -= it.bytes }
        heldBytes += bytes
    }

    /** Fetching or reading [id] failed: for good when [again] is false, else asked for again after a pause, twice. When, or null for never. */
    @Synchronized
    public fun failed(id: ImageryTileId, again: Boolean): Long? {
        loading -= id
        val tries = (failures[id]?.tries ?: 0) + 1
        val at = if (again && tries < MAX_TRIES) nowMs + RETRY_MS * tries else null
        failures[id] = Failure(tries, at)
        return at
    }

    /** The GL context went, and every texture with it: all of it is to be fetched again. */
    @Synchronized
    public fun lostTextures() {
        held.clear()
        heldBytes = 0
    }

    @Synchronized
    public fun clear() {
        held.clear()
        loading.clear()
        failures.clear()
        heldBytes = 0
    }

    @get:Synchronized
    public val bytesHeld: Long get() = heldBytes

    private fun status(id: ImageryTileId): TileStatus = when {
        id in held -> TileStatus.READY
        id in loading -> TileStatus.LOADING
        else -> failures[id]?.let { f -> if (f.againAtMs != null && nowMs >= f.againAtMs) TileStatus.UNLOADED else TileStatus.FAILED } ?: TileStatus.UNLOADED
    }

    private fun letGo(): List<ImageryTileId> {
        if (heldBytes <= holdBytes) return emptyList()
        val gone = ArrayList<ImageryTileId>()
        for ((id, h) in held.entries.filter { it.value.lastFrame < frameNumber }.sortedBy { it.value.lastFrame }) {
            if (heldBytes <= holdBytes) break
            held.remove(id)
            heldBytes -= h.bytes
            gone += id
        }
        return gone
    }

    public companion object {
        public const val MAX_FETCHES: Int = 6

        /** About forty of Mapbox's tiles, at two bytes a pixel and a third more for their smaller copies. */
        public const val HOLD_BYTES: Long = 112L * 1024 * 1024

        public const val RETRY_MS: Long = 5_000

        private const val MAX_TRIES = 3

        /** What a 1024-pixel tile takes on the GPU at two bytes a pixel, with its mipmaps. */
        public val TILE_BYTES: Long = ImageryTiling.TILE_PIXELS.toLong() * ImageryTiling.TILE_PIXELS * 2 * 4 / 3
    }
}
