package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * How the web's Cesium globe measures and refines the server's heightmap terrain (`CustomHeightmapTerrainProvider` over Cesium's geographic
 * tiling, 65 heights a side): a tile's geometric error is Cesium's estimate for a heightmap (`TerrainProvider.getEstimatedLevelZeroGeometricErrorForAHeightmap`,
 * the earth's circumference times the heightmap quality, 0.25, over the samples across the two tiles of level 0), halved at every level; the
 * globe refines a tile while its error on the screen is more than 2 pixels (`Globe.maximumScreenSpaceError`); and a tile's skirts hang down four
 * times its error, at most a kilometre (`HeightmapTerrainData`), to hide the cracks between tiles of two levels.
 */
public object TerrainLook {
    public const val MAXIMUM_SCREEN_SPACE_ERROR: Double = 2.0

    private const val QUALITY = 0.25

    /** A level-0 tile's error, about 77 km. */
    public val LEVEL_ZERO_ERROR: Double = Wgs84.A * 2 * PI * QUALITY / (TerrainTiling.SAMPLES * 2)

    public fun levelError(level: Int): Double = LEVEL_ZERO_ERROR / (1L shl level)

    public fun skirtHeight(level: Int): Double = min(levelError(level) * 4.0, 1000.0)
}

/**
 * A terrain tile's mesh, as the renderer puts it on the GPU: the server's 65 × 65 heights (metres above the ellipsoid, rows from north to south)
 * placed as Cesium places them, the first and last of each row on the tile's edges, so a row is 64 even steps from west to east (the server
 * samples cell centres instead: AGENTS.md §15, the web draws Cesium's way and so does this); and a skirt along each edge, hanging
 * [TerrainLook.skirtHeight] below it. Each vertex is five floats: where it is in the scene's frame, then where it is in the tile's own
 * rectangle of Web Mercator (0 to 1 from its north-west corner), which the imagery is laid on by. Every tile has the same triangles
 * ([INDICES]), so one index buffer serves them all.
 */
public object TerrainMeshes {
    private const val N = TerrainTiling.SAMPLES

    /** Each vertex's floats: x, y, z, then u, v. */
    public const val FLOATS: Int = 5

    /** The grid's vertices, then a skirt of [N] under each of the four edges. */
    public const val VERTICES: Int = N * N + 4 * N

    /** The triangles: the grid's two to a cell, then each skirt's two to a step. */
    public val INDICES: ShortArray = buildIndices()

    /** The heights' range, low and high, in metres; nothing for no heights. */
    public fun range(heights: ShortArray): Pair<Double, Double> {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (h in heights) {
            lo = min(lo, h.toInt())
            hi = max(hi, h.toInt())
        }
        return lo.toDouble() to hi.toDouble()
    }

    /** The vertices of [tile] with [heights] (65 × 65, rows from north to south), in [frame]: [FLOATS] floats each, [VERTICES] of them. */
    public fun vertices(tile: TerrainTileId, heights: ShortArray, frame: LocalFrame): FloatArray {
        require(heights.size == N * N) { "A terrain tile of ${heights.size} heights" }
        val b = TerrainTiling.bounds(tile)
        val x0 = ImageryTiling.mercatorX(b.west)
        val x1 = ImageryTiling.mercatorX(b.east)
        val y0 = ImageryTiling.mercatorY(b.north)
        val y1 = ImageryTiling.mercatorY(b.south)
        val out = FloatArray(VERTICES * FLOATS)
        fun put(index: Int, row: Int, column: Int, height: Double) {
            val lat = b.north - (b.north - b.south) * row / (N - 1)
            val lon = b.west + (b.east - b.west) * column / (N - 1)
            val p = frame.fromGeodetic(lat, lon, height)
            val o = index * FLOATS
            out[o] = p.x.toFloat()
            out[o + 1] = p.y.toFloat()
            out[o + 2] = p.z.toFloat()
            out[o + 3] = ((ImageryTiling.mercatorX(lon) - x0) / (x1 - x0)).toFloat()
            out[o + 4] = ((ImageryTiling.mercatorY(lat) - y0) / (y1 - y0)).toFloat()
        }
        for (row in 0 until N) for (column in 0 until N) put(row * N + column, row, column, heights[row * N + column].toDouble())
        val skirt = TerrainLook.skirtHeight(tile.level)
        for ((edge, cells) in edges().withIndex()) {
            for ((k, cell) in cells.withIndex()) put(N * N + edge * N + k, cell / N, cell % N, heights[cell].toDouble() - skirt)
        }
        return out
    }

    /**
     * A box in [frame] that holds [tile] between [low] and [high] metres (its skirts included when [low] is taken below them), for culling and
     * measuring before or after its heights are in hand.
     */
    public fun volume(tile: TerrainTileId, low: Double, high: Double, frame: LocalFrame): OrientedBox {
        val b = TerrainTiling.bounds(tile)
        return regionBox(Math.toRadians(b.west), Math.toRadians(b.south), Math.toRadians(b.east), Math.toRadians(b.north), low, high, frame)
    }

    // The grid's cells along each edge, in order: north (west to east), east (north to south), south (west to east), west (north to south).
    private fun edges(): List<IntArray> = listOf(
        IntArray(N) { it },
        IntArray(N) { it * N + N - 1 },
        IntArray(N) { (N - 1) * N + it },
        IntArray(N) { it * N },
    )

    private fun buildIndices(): ShortArray {
        val out = ArrayList<Int>((N - 1) * (N - 1) * 6 + 4 * (N - 1) * 6)
        for (row in 0 until N - 1) for (column in 0 until N - 1) {
            val nw = row * N + column
            val ne = nw + 1
            val sw = nw + N
            val se = sw + 1
            out += listOf(nw, sw, ne, ne, sw, se)
        }
        for ((edge, cells) in edges().withIndex()) {
            for (k in 0 until N - 1) {
                val top = cells[k]
                val nextTop = cells[k + 1]
                val bottom = N * N + edge * N + k
                val nextBottom = bottom + 1
                out += listOf(top, bottom, nextTop, nextTop, bottom, nextBottom)
            }
        }
        check(N * N + 4 * N <= 65_536) { "Vertices past an unsigned short" }
        return ShortArray(out.size) { out[it].toShort() }
    }
}

/** A terrain tile to draw: its place and the server's heights, from which the renderer builds its mesh once ([TerrainMeshes.vertices]). */
public class TerrainDraw(public val tile: TerrainTileId, public val heights: ShortArray)

/** One frame's terrain: the tiles to draw, nearest first, the tiles to fetch, and those let go of (their GPU copy goes too). */
public class TerrainFrame(public val drawn: List<TerrainDraw>, public val fetch: List<TerrainTileId>, public val letGo: List<TerrainTileId>)

/**
 * The ground under the point cloud as Cesium's globe chooses it (`QuadtreePrimitive`): from the two tiles of level 0 down, a tile in view is
 * drawn when its error on the screen is small enough, or while its children in view are not all in hand, which are then fetched (one out of
 * view counts as ready and is not, as Cesium's culled tile); when they are, its children stand in its place. **None is drawn where the server has none** (a 204: nothing there or under it), where the web lays the globe
 * flat at sea level (AGENTS.md §14: never invent a height); a tile that is only partly covered comes filled by the server, which spreads its
 * neighbouring heights. At most [maxFetches] out at once, coarse first and then nearest; [holdTiles] in hand at most, the longest unused let
 * go of first, never one the frame needed. A tile that met no connection is asked for again after a pause, twice; one whose answer could
 * not be read, never. Every call takes its lock.
 *
 * **Not ported**: Cesium's fog, which lowers the error of far tiles, its horizon culling, and the ancestor's heights shown upsampled while
 * a tile's own come. A tile more than [CULL_ALWAYS_DEGREES] across is never culled: its box is too coarse to trust.
 */
public class TerrainScene(
    public val maxFetches: Int = MAX_FETCHES,
    public val holdTiles: Int = HOLD_TILES,
    public val maximumScreenSpaceError: Double = TerrainLook.MAXIMUM_SCREEN_SPACE_ERROR,
) {
    private class Held(val heights: ShortArray?, val low: Double, val high: Double, val volume: OrientedBox?, var lastFrame: Long)
    private class Failure(val tries: Int, val againAtMs: Long?)

    private val held = HashMap<TerrainTileId, Held>()
    private val loading = HashSet<TerrainTileId>()
    private val failures = HashMap<TerrainTileId, Failure>()
    private var frameNumber = 0L
    private var nowMs = 0L

    /** The tiles in hand, with heights or known to have none. */
    @get:Synchronized
    public val heldTiles: Int get() = held.size

    @Synchronized
    public fun frame(camera: ViewCamera, frame: LocalFrame, atMs: Long): TerrainFrame {
        frameNumber++
        nowMs = atMs
        val drawn = ArrayList<Pair<TerrainDraw, Double>>()
        val wanted = ArrayList<Pair<TerrainTileId, Double>>()
        fun want(tile: TerrainTileId, distance: Double) {
            if (status(tile) == TileStatus.UNLOADED) wanted += tile to distance
        }

        fun visit(tile: TerrainTileId, low: Double, high: Double) {
            val h = held[tile]
            h?.lastFrame = frameNumber
            val volume = h?.volume ?: TerrainMeshes.volume(tile, low, high, frame)
            if (!inView(tile, volume, camera)) return
            val distance = volume.distanceTo(camera.position)
            if (h == null) return want(tile, distance)
            val heights = h.heights ?: return
            val sse = TerrainLook.levelError(tile.level) * camera.heightPx / (max(distance, 1e-7) * camera.sseDenominator) / camera.pixelRatio
            if (sse <= maximumScreenSpaceError || tile.level >= TerrainTiling.MAX_LEVEL) {
                drawn += TerrainDraw(tile, heights) to distance
                return
            }
            val children = childrenOf(tile)
            for (child in children) held[child]?.lastFrame = frameNumber
            // As Cesium's quadtree: this tile gives way once every child in view is in hand, fetched together; one out of view counts as ready
            // and is not fetched (`visitIfVisible`: a culled tile is renderable, and loaded only at level 0). One that comes into view later has
            // this tile drawn in its place again until it comes.
            val missing = children.filter { it !in held && inView(it, TerrainMeshes.volume(it, h.low, h.high, frame), camera) }
            if (missing.isNotEmpty()) {
                drawn += TerrainDraw(tile, heights) to distance
                for (child in missing) want(child, distance)
                return
            }
            for (child in children) visit(child, h.low, h.high)
        }

        for (root in ROOTS) visit(root, LOWEST, HIGHEST)
        val fetch = ArrayList<TerrainTileId>()
        for ((tile, _) in wanted.sortedWith(compareBy<Pair<TerrainTileId, Double>> { it.first.level }.thenBy { it.second })) {
            if (loading.size >= maxFetches) break
            if (tile in loading) continue
            loading += tile
            fetch += tile
        }
        return TerrainFrame(drawn.sortedBy { it.second }.map { it.first }, fetch, letGo())
    }

    /** [tile]'s heights came ([heights], read in [frame]), or the server has none there (null). */
    @Synchronized
    public fun arrived(tile: TerrainTileId, heights: ShortArray?, frame: LocalFrame) {
        loading -= tile
        failures.remove(tile)
        if (heights == null) {
            held[tile] = Held(null, 0.0, 0.0, null, frameNumber)
            return
        }
        val (low, high) = TerrainMeshes.range(heights)
        val volume = TerrainMeshes.volume(tile, low - TerrainLook.skirtHeight(tile.level), high, frame)
        held[tile] = Held(heights, low, high, volume, frameNumber)
    }

    /**
     * Fetching [tile] failed at [atMs], on the frames' clock: for good when its answer could not be read ([again] false), else it is asked for
     * again after a pause from then, twice. When it may be asked for again, or null for never.
     */
    @Synchronized
    public fun failed(tile: TerrainTileId, again: Boolean, atMs: Long): Long? {
        loading -= tile
        val tries = (failures[tile]?.tries ?: 0) + 1
        val at = if (again && tries < MAX_TRIES) atMs + RETRY_MS * tries else null
        failures[tile] = Failure(tries, at)
        return at
    }

    /** Lets go of everything (the view closed). */
    @Synchronized
    public fun clear() {
        held.clear()
        loading.clear()
        failures.clear()
    }

    /**
     * The ground at [lat], [lon] from the finest tile in hand that holds it, between its four nearest heights as Cesium places them, with that
     * tile's level; null with none in hand there (never a height made up).
     */
    @Synchronized
    public fun heightAt(lat: Double, lon: Double): Pair<Int, Double>? {
        for (level in TerrainTiling.MAX_LEVEL downTo 0) {
            val id = TerrainTiling.tileAt(lat, lon, level)
            val heights = held[id]?.heights ?: continue
            val b = TerrainTiling.bounds(id)
            val last = TerrainTiling.SAMPLES - 1
            val fx = ((lon - b.west) / (b.east - b.west) * last).coerceIn(0.0, last.toDouble())
            val fy = ((b.north - lat) / (b.north - b.south) * last).coerceIn(0.0, last.toDouble())
            val column = min(fx.toInt(), last - 1)
            val row = min(fy.toInt(), last - 1)
            val tx = fx - column
            val ty = fy - row
            fun at(r: Int, c: Int) = heights[r * TerrainTiling.SAMPLES + c].toDouble()
            val top = at(row, column) * (1 - tx) + at(row, column + 1) * tx
            val bottom = at(row + 1, column) * (1 - tx) + at(row + 1, column + 1) * tx
            return level to top * (1 - ty) + bottom * ty
        }
        return null
    }

    private fun status(tile: TerrainTileId): TileStatus = when {
        tile in held -> TileStatus.READY
        tile in loading -> TileStatus.LOADING
        else -> failures[tile]?.let { f -> if (f.againAtMs != null && nowMs >= f.againAtMs) TileStatus.UNLOADED else TileStatus.FAILED } ?: TileStatus.UNLOADED
    }

    private fun inView(tile: TerrainTileId, volume: OrientedBox, camera: ViewCamera): Boolean {
        val b = TerrainTiling.bounds(tile)
        if (b.east - b.west > CULL_ALWAYS_DEGREES) return true
        return camera.frustum.visibility(volume) != Visibility.OUTSIDE
    }

    private fun letGo(): List<TerrainTileId> {
        if (held.size <= holdTiles) return emptyList()
        val gone = ArrayList<TerrainTileId>()
        for ((tile, _) in held.entries.filter { it.value.lastFrame < frameNumber }.sortedBy { it.value.lastFrame }) {
            if (held.size <= holdTiles) break
            held.remove(tile)
            gone += tile
        }
        return gone
    }

    public companion object {
        public const val MAX_FETCHES: Int = 6

        /** About 2 MB of heights in hand. */
        public const val HOLD_TILES: Int = 300

        public const val RETRY_MS: Long = 5_000

        /** A tile wider than this is never culled: its box, from a few samples of the curved earth, is too coarse to trust. */
        public const val CULL_ALWAYS_DEGREES: Double = 6.0

        private const val MAX_TRIES = 3

        /** The heights a tile is assumed to lie between before any of its own or its parent's are known: below the Dead Sea, above Everest. */
        private const val LOWEST = -500.0
        private const val HIGHEST = 9_000.0

        private val ROOTS = listOf(TerrainTileId(0, 0, 0), TerrainTileId(0, 1, 0))

        public fun childrenOf(tile: TerrainTileId): List<TerrainTileId> = listOf(
            TerrainTileId(tile.level + 1, 2 * tile.x, 2 * tile.y),
            TerrainTileId(tile.level + 1, 2 * tile.x + 1, 2 * tile.y),
            TerrainTileId(tile.level + 1, 2 * tile.x, 2 * tile.y + 1),
            TerrainTileId(tile.level + 1, 2 * tile.x + 1, 2 * tile.y + 1),
        )
    }
}
