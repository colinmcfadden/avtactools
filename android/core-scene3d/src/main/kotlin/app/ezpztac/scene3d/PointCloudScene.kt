package app.ezpztac.scene3d

import java.util.Collections
import java.util.IdentityHashMap

/** A tile to fetch: its content's [path] in the tileset's store, and whether it is a tileset to join rather than points. */
public class TileFetch(public val tile: Tile, public val path: String, public val isTileset: Boolean)

/** A tile drawn this frame: its points, and the geometric error they are sized by ([PointShading.geometricError]). */
public class DrawnTile(public val tile: Tile, public val points: ScenePoints, public val geometricError: Double)

/**
 * One frame's work: what to draw ([drawn], the core's first, each tileset's nearest first; [byCloud], the same by tileset in the order they were
 * added, since Cesium lights each tileset with the eye-dome lighting on its own), what to fetch, and what was let go of (its GPU copy goes too).
 */
public class SceneFrame(
    public val drawn: List<DrawnTile>,
    public val fetch: List<TileFetch>,
    public val letGo: List<Tile>,
    public val byCloud: List<List<DrawnTile>> = listOf(drawn),
)

/**
 * The point clouds of one LZ's 3D view as plain state: the core tileset and the ring round it ([add]), which of their tiles to draw for a camera
 * and which to fetch next ([frame]), the tiles in hand ([arrived], [joined], [failed]) and which to let go of to stay within [holdBudget] points.
 * Every call takes its lock, so the renderer's thread and the fetches' can call it; the fetches are made elsewhere.
 *
 * - **Drawn within [drawBudget] points**: each tileset's selection is made coarser until it fits ([TileSelector.withinBudget]), the core first and
 *   the ring with what it leaves.
 * - **At most [maxFetches] out at once**, the core's first, coarse before fine, near before far. One out, or in hand, is not asked for again.
 * - **A failed tile**: one whose answer could not be read is never asked for again, as Cesium marks it failed, and the tile it would have replaced
 *   stays drawn; one that met no connection is asked again after a pause ([RETRY_MS]), twice, then not this session. Cesium has no second try;
 *   a phone loses its signal more often than a browser.
 * - **Let go of** least recently used first, never one the last frame touched (drawn, gone through, or in hand for a parent waiting on it).
 */
public class PointCloudScene(
    public val drawBudget: Long = DRAW_BUDGET,
    public val holdBudget: Long = HOLD_BUDGET,
    public val maxFetches: Int = MAX_FETCHES,
) {
    private class Cloud(val document: TilesetDocument, val selector: TileSelector)
    private class Held(val points: ScenePoints, var lastFrame: Long)
    private class Failure(val tries: Int, val againAtMs: Long?)

    private val clouds = ArrayList<Cloud>()
    private val held = IdentityHashMap<Tile, Held>()
    private val loading: MutableSet<Tile> = Collections.newSetFromMap(IdentityHashMap())
    private val failures = IdentityHashMap<Tile, Failure>()
    private var frameNumber = 0L
    private var nowMs = 0L

    /** The points in hand, in all. */
    public var heldPoints: Long = 0
        private set

    /** Draws [document] to [maximumScreenSpaceError] ([SceneLook.CORE_SCREEN_SPACE_ERROR] for the core, the ring's for the ring), after those added before. */
    @Synchronized
    public fun add(document: TilesetDocument, maximumScreenSpaceError: Double) {
        clouds += Cloud(document, TileSelector(maximumScreenSpaceError))
    }

    /** The tilesets added, in order. */
    @get:Synchronized
    public val documents: List<TilesetDocument> get() = clouds.map { it.document }

    /** What to draw for [camera] and what to fetch, at [atMs] on the caller's clock (for a failed tile's second try). */
    @Synchronized
    public fun frame(camera: ViewCamera, atMs: Long): SceneFrame {
        frameNumber++
        nowMs = atMs
        var budget = drawBudget
        val byCloud = ArrayList<List<DrawnTile>>()
        val wanted = ArrayList<Tile>()
        for (cloud in clouds) {
            val selection = cloud.selector.withinBudget(cloud.document, camera, ::statusOf, budget.coerceAtLeast(0), ::pointsOf)
            val drawn = ArrayList<DrawnTile>()
            for (tile in selection.selected) {
                val inHand = held[tile] ?: continue
                drawn += DrawnTile(tile, inHand.points, PointShading.geometricError(tile, inHand.points.estimatedError))
            }
            byCloud += drawn
            budget -= selection.selected.sumOf(::pointsOf)
            wanted += selection.requested
            for (tile in selection.touched) held[tile]?.lastFrame = frameNumber
        }
        val fetch = ArrayList<TileFetch>()
        for (tile in wanted) {
            if (loading.size >= maxFetches) break
            if (statusOf(tile) != TileStatus.UNLOADED) continue
            val content = tile.content ?: continue
            loading += tile
            fetch += TileFetch(tile, content.path, content is TilesetContent)
        }
        return SceneFrame(byCloud.flatten(), fetch, letGo(), byCloud)
    }

    /** [tile]'s points came, and are in hand. */
    @Synchronized
    public fun arrived(tile: Tile, points: ScenePoints) {
        loading -= tile
        failures.remove(tile)
        val before = held.put(tile, Held(points, frameNumber))
        heldPoints += points.count - (before?.points?.count ?: 0)
    }

    /**
     * [tile]'s tileset came as [bytes]: read into the scene's [frame] and joined under it, here, under the lock the traversal takes. One that cannot
     * be read is a failure for good, and the reason is returned (null when it joined).
     */
    @Synchronized
    public fun joined(tile: Tile, bytes: ByteArray, frame: LocalFrame): String? {
        loading -= tile
        val path = (tile.content as? TilesetContent)?.path ?: return "not a tileset"
        return try {
            TilesetReader.read(bytes, path, frame, parent = tile)
            failures.remove(tile)
            null
        } catch (e: TilesetException) {
            failures[tile] = Failure(MAX_TRIES, null)
            e.message ?: "unreadable"
        }
    }

    /**
     * Fetching [tile] failed: for good when its answer could not be read ([again] false), else it is asked for again after a pause, twice. When
     * it may be asked for again, on the frames' clock (a frame must be drawn then for it to be), or null for never.
     */
    @Synchronized
    public fun failed(tile: Tile, again: Boolean): Long? {
        loading -= tile
        val tries = (failures[tile]?.tries ?: 0) + 1
        val at = if (again && tries < MAX_TRIES) nowMs + RETRY_MS * tries else null
        failures[tile] = Failure(tries, at)
        return at
    }

    /** Lets go of everything in hand (the GL context is gone, or the view closed): what was out comes back to nothing. */
    @Synchronized
    public fun clear() {
        held.clear()
        loading.clear()
        failures.clear()
        heldPoints = 0
    }

    /** Every tile in hand, for a renderer putting them on a new GL context. */
    @Synchronized
    public fun inHand(): List<Pair<Tile, ScenePoints>> = held.map { (tile, h) -> tile to h.points }

    private fun pointsOf(tile: Tile): Long = held[tile]?.points?.count?.toLong() ?: 0

    private fun statusOf(tile: Tile): TileStatus = when {
        tile.content is TilesetContent && tile.children.isNotEmpty() -> TileStatus.READY
        held.containsKey(tile) -> TileStatus.READY
        tile in loading -> TileStatus.LOADING
        else -> failures[tile]?.let { f -> if (f.againAtMs != null && nowMs >= f.againAtMs) TileStatus.UNLOADED else TileStatus.FAILED } ?: TileStatus.UNLOADED
    }

    // Over the budget, the tiles longest unused go first; none the last frame touched.
    private fun letGo(): List<Tile> {
        if (heldPoints <= holdBudget) return emptyList()
        val gone = ArrayList<Tile>()
        for ((tile, h) in held.entries.filter { it.value.lastFrame < frameNumber }.sortedBy { it.value.lastFrame }) {
            if (heldPoints <= holdBudget) break
            held.remove(tile)
            heldPoints -= h.points.count
            gone += tile
        }
        return gone
    }

    public companion object {
        /** Points drawn at most in a frame: about three million on a phone (docs/ANDROID_3D_PLAN.md, V3). */
        public const val DRAW_BUDGET: Long = 3_000_000

        /** Points kept in hand at most: twice what is drawn, so turning round brings back what was there without a fetch. */
        public const val HOLD_BUDGET: Long = 6_000_000

        public const val MAX_FETCHES: Int = 4

        /** The pause before a tile that met no connection is asked for again, times its tries. */
        public const val RETRY_MS: Long = 5_000

        private const val MAX_TRIES = 3
    }
}
