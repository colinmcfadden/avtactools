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
 *   the ring with what it leaves. The error so raised is kept from frame to frame, and lowered a step a frame only once the view has changed
 *   since it was last raised, the tiles it draws are all in hand, and a step lower would still fit (it draws about [TileSelector.RAISE] squared
 *   as many points): a still camera whose finest view is over the budget would otherwise fetch that view, find it too much, let it go and fetch
 *   it again for ever. **Not like Cesium**, which raises its memory-adjusted error by 2% a frame while its cache is over its bytes and lowers
 *   it 2% a frame while under, whatever the camera does.
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
    private class Cloud(val document: TilesetDocument, val selector: TileSelector) {
        // The raises of the error allowed for the budget, kept from frame to frame, and the view they were last raised for.
        var raises = 0
        var raisedFor: ViewCamera? = null
    }

    private class Held(val points: ScenePoints, var lastFrame: Long)
    private class Failure(val tries: Int, val againAtMs: Long?)

    private val clouds = ArrayList<Cloud>()
    private val held = IdentityHashMap<Tile, Held>()
    private val loading: MutableSet<Tile> = Collections.newSetFromMap(IdentityHashMap())
    private val failures = IdentityHashMap<Tile, Failure>()
    // Let go of by [trim] since the last frame, for the next to say.
    private val trimmed = ArrayList<Tile>()
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
            val room = budget.coerceAtLeast(0)
            val fitted = cloud.selector.withinBudget(cloud.document, camera, ::statusOf, room, ::pointsOf, cloud.raises)
            val selection = fitted.selection
            val points = selection.selected.sumOf(::pointsOf)
            cloud.raises = when {
                fitted.raises > cloud.raises -> fitted.raises.also { cloud.raisedFor = camera }
                fitted.raises > 0 && !sameView(cloud.raisedFor, camera) && complete(selection) && points * LOWERED_DRAWS < room -> fitted.raises - 1
                else -> fitted.raises
            }
            val drawn = ArrayList<DrawnTile>()
            for (tile in selection.selected) {
                val inHand = held[tile] ?: continue
                drawn += DrawnTile(tile, inHand.points, PointShading.geometricError(tile, inHand.points.estimatedError))
            }
            byCloud += drawn
            budget -= points
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
        val gone = letGo() + trimmed
        trimmed.clear()
        return SceneFrame(byCloud.flatten(), fetch, gone, byCloud)
    }

    /**
     * Lets go of every tile in hand that the last frame did not touch (the app went to the back: the less it holds, the less likely the system is to
     * end it), keeping what that frame drew, so the view comes back as it was; the next frame says what went, for its GPU copy to go too.
     */
    @Synchronized
    public fun trim() {
        val unused = held.entries.mapNotNull { (tile, h) -> if (h.lastFrame < frameNumber) tile to h else null }
        for ((tile, h) in unused) {
            held.remove(tile)
            heldPoints -= h.points.count
            trimmed += tile
        }
    }

    /**
     * What [trim] let go of that no frame has said yet, taken now (for the GPU copies to be freed before the GL thread pauses, while its context
     * is current: a paused thread draws no frame to free them in); the next frame does not say it again.
     */
    @Synchronized
    public fun takeTrimmed(): List<Tile> = ArrayList(trimmed).also { trimmed.clear() }

    /** [tile]'s points came, and are in hand. */
    @Synchronized
    public fun arrived(tile: Tile, points: ScenePoints) {
        loading -= tile
        failures.remove(tile)
        val before = held.put(tile, Held(points, frameNumber))
        heldPoints += points.count - (before?.points?.count ?: 0)
    }

    /**
     * [tile]'s tileset came as [bytes]: read into the scene's [frame] on the caller's thread, then joined under it under the lock the traversal
     * takes. One that cannot be read is a failure for good, and the reason is returned (null when it joined).
     */
    public fun joined(tile: Tile, bytes: ByteArray, frame: LocalFrame): String? {
        val path = (tile.content as? TilesetContent)?.path
        val read = try {
            if (path == null) null else TilesetReader.readApart(bytes, path, frame, parent = tile)
        } catch (e: TilesetException) {
            return refused(tile, e.message ?: "unreadable")
        }
        synchronized(this) {
            if (read == null) return refused(tile, "not a tileset")
            loading -= tile
            return try {
                TilesetReader.join(tile, read)
                failures.remove(tile)
                null
            } catch (e: TilesetException) {
                failures[tile] = Failure(MAX_TRIES, null)
                e.message ?: "unreadable"
            }
        }
    }

    @Synchronized
    private fun refused(tile: Tile, reason: String): String {
        loading -= tile
        failures[tile] = Failure(MAX_TRIES, null)
        return reason
    }

    /**
     * Fetching [tile] failed at [atMs], on the frames' clock: for good when its answer could not be read ([again] false), else it is asked for
     * again after a pause from then, twice. When it may be asked for again (a frame must be drawn then for it to be), or null for never.
     */
    @Synchronized
    public fun failed(tile: Tile, again: Boolean, atMs: Long): Long? {
        loading -= tile
        val tries = (failures[tile]?.tries ?: 0) + 1
        val at = if (again && tries < MAX_TRIES) atMs + RETRY_MS * tries else null
        failures[tile] = Failure(tries, at)
        return at
    }

    /** Lets go of everything in hand (the GL context is gone, or the view closed): what was out comes back to nothing. */
    @Synchronized
    public fun clear() {
        held.clear()
        loading.clear()
        failures.clear()
        trimmed.clear()
        heldPoints = 0
    }

    private fun pointsOf(tile: Tile): Long = held[tile]?.points?.count?.toLong() ?: 0

    // Nothing the selection wants is still to come: what it draws is what it will draw.
    private fun complete(selection: Selection): Boolean = selection.requested.isEmpty() && selection.touched.none { it in loading }

    private fun statusOf(tile: Tile): TileStatus = when {
        tile.content is TilesetContent && tile.children.isNotEmpty() -> TileStatus.READY
        held.containsKey(tile) -> TileStatus.READY
        tile in loading -> TileStatus.LOADING
        else -> failures[tile]?.let { f -> if (f.againAtMs != null && nowMs >= f.againAtMs) TileStatus.UNLOADED else TileStatus.FAILED } ?: TileStatus.UNLOADED
    }

    // Over the budget, the tiles longest unused go first; none the last frame touched. An IdentityHashMap's entries read from its table as it is
    // when they are read, so they are copied out before any is removed: read after, they give another tile's points, or none.
    private fun letGo(): List<Tile> {
        if (heldPoints <= holdBudget) return emptyList()
        val gone = ArrayList<Tile>()
        val unused = held.entries.mapNotNull { (tile, h) -> if (h.lastFrame < frameNumber) tile to h else null }
        for ((tile, h) in unused.sortedBy { it.second.lastFrame }) {
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

        // How many times the points a step lower draws, about: a surface's tiles go as the square of the error allowed.
        private const val LOWERED_DRAWS = TileSelector.RAISE * TileSelector.RAISE

        private fun sameView(a: ViewCamera?, b: ViewCamera): Boolean =
            a != null && a.position == b.position && a.heading == b.heading && a.pitch == b.pitch && a.widthPx == b.widthPx && a.heightPx == b.heightPx &&
                a.pixelRatio == b.pixelRatio && a.fov == b.fov
    }
}
