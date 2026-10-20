package app.ezpztac.scene3d

import java.util.IdentityHashMap
import kotlin.math.max

/**
 * What to draw this frame, nearest first, and what to fetch, most wanted first; and every tile the traversal touched (Cesium's `touchTile`: those
 * it went through, and those it asked for), which a cache keeps over the rest, since a tile in hand but not drawn may be what lets its parent give way.
 */
public class Selection(public val selected: List<Tile>, public val requested: List<Tile>, public val touched: Set<Tile> = emptySet()) {
    public companion object {
        public val NONE: Selection = Selection(emptyList(), emptyList())
    }
}

/** A selection made within a point budget ([TileSelector.withinBudget]), and how many times the error allowed was raised to make it. */
public class FittedSelection(public val selection: Selection, public val raises: Int)

/**
 * Where a tile's content stands (Cesium's `Cesium3DTileContentState`, less what it needs for expiry): not asked for, on its way, in hand (its
 * points decoded, or for a tileset in a tile, its root joined), or failed. Only an [UNLOADED] tile is asked for; a [FAILED] one is never asked
 * for again, and a replaced tile waiting on it stays drawn in its place, as Cesium keeps it.
 */
public enum class TileStatus { UNLOADED, LOADING, READY, FAILED }

/**
 * Which tiles to draw and fetch for a camera: Cesium's base traversal (`Cesium3DTilesetBaseTraversal` and `Cesium3DTilesetTraversal`, as
 * Cesium 1.144 has them, which a tileset uses when `skipLevelOfDetail` is off, its default), so the cloud fills in as the web's does.
 *
 * - A tile is in view when its volume is not outside the frustum; its **screen-space error** is
 *   `geometricError × heightPx / (distance × sseDenominator) / pixelRatio` (`Cesium3DTile.getScreenSpaceError`), its error already scaled by its
 *   transform ([Tile.geometricError]), and its children are gone into while that is more than [maximumScreenSpaceError] (2 for the core, 8
 *   for the ring, as the web sets them). A tileset in a tile is always gone into, once it has joined.
 * - **ADD** tiles are drawn and fetched whenever they are reached. A child of an ADD tile whose parent's error, measured at the child, is
 *   already small enough is not in view at all (`meetsScreenSpaceErrorEarly`), unless its parent is a tileset in a tile.
 * - **REPLACE** tiles are fetched, and drawn in place of their children until every child (in view or not) has its points: refining before
 *   would leave holes. A child with no content of its own lets its parent give way at once, its children streaming in; a tileset in a tile,
 *   only once what stops the descent under it is in hand. A parent none of whose children is in view does not give way.
 * - The root, measured with the tileset's own error, too small to matter: nothing.
 *
 * Not ported: Cesium's dynamic screen-space error (a few pixels off a distant tile's error, tuned for a city seen from the street), the
 * request volumes and the children-within-parent culling hint (each only ever culls more), holding requests back while the camera moves fast
 * (`cullRequestsWhileMoving`) and the foveated deferral, and the memory-adjusted error, whose place a point budget takes ([withinBudget]).
 * Requests are ordered coarse first, then nearest, where Cesium has a priority of its own. A grandchild under an empty tile is measured with
 * this frame's camera where Cesium's `executeEmptyTraversal` reads the error its last frame left.
 */
public class TileSelector(public val maximumScreenSpaceError: Double) {
    private class Visit {
        var visible = false
        var distance = 0.0
        var sse = 0.0
        var refines = false
    }

    /** The selection for [camera] over [tileset]; [status] says where each tile's content stands. */
    public fun select(tileset: TilesetDocument, camera: ViewCamera, status: (Tile) -> TileStatus): Selection =
        Traversal(tileset, camera, status, maximumScreenSpaceError).run()

    /**
     * The selection for [camera], made coarser until the points drawn are within [budget]: from the error allowed after [from] raises, it is
     * raised half as much again ([RAISE]), to at most [MAX_RAISES] raises, while what is selected holds more than [budget] points ([points] of
     * a tile in hand). What Cesium does with its cache's bytes, for a phone's memory; when to lower it again is the caller's ([PointCloudScene]).
     */
    public fun withinBudget(
        tileset: TilesetDocument,
        camera: ViewCamera,
        status: (Tile) -> TileStatus,
        budget: Long,
        points: (Tile) -> Long,
        from: Int = 0,
    ): FittedSelection {
        var raises = from.coerceIn(0, MAX_RAISES)
        var selection = Traversal(tileset, camera, status, errorAfter(raises)).run()
        while (raises < MAX_RAISES && selection.selected.sumOf(points) > budget) {
            raises++
            selection = Traversal(tileset, camera, status, errorAfter(raises)).run()
        }
        return FittedSelection(selection, raises)
    }

    /** The screen-space error allowed after [raises] raises for a budget. */
    public fun errorAfter(raises: Int): Double {
        var error = maximumScreenSpaceError
        repeat(raises.coerceIn(0, MAX_RAISES)) { error *= RAISE }
        return error
    }

    public companion object {
        /** How much a raise for a budget multiplies the error allowed by. */
        public const val RAISE: Double = 1.5

        /** The most raises for a budget: the error allowed then about 26 times the tileset's own. */
        public const val MAX_RAISES: Int = 8
    }

    private class Traversal(val tileset: TilesetDocument, val camera: ViewCamera, val status: (Tile) -> TileStatus, val maximum: Double) {
        private val visits = IdentityHashMap<Tile, Visit>()
        private val selected = ArrayList<Tile>()
        private val requested = LinkedHashSet<Tile>()
        private val touched = java.util.Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())

        fun run(): Selection {
            val root = tileset.root
            val visit = update(root)
            if (!visit.visible) return Selection.NONE
            if (screenSpaceError(root, visit.distance, useParent = true) <= maximum) return Selection.NONE
            traverse(root)
            val byWant = requested.sortedWith(compareBy<Tile> { it.depth }.thenBy { visits[it]?.distance ?: Double.MAX_VALUE })
            return Selection(selected.sortedBy { visits[it]?.distance ?: 0.0 }, byWant, touched)
        }

        private fun visitOf(tile: Tile): Visit = visits.getOrPut(tile) { Visit() }

        /** `updateTileVisibility`: in view, how far, how much error. */
        private fun update(tile: Tile): Visit {
            val visit = visitOf(tile)
            visit.distance = tile.volume.distanceTo(camera.position)
            visit.sse = screenSpaceError(tile, visit.distance, useParent = false)
            visit.visible = camera.frustum.visibility(tile.volume) != Visibility.OUTSIDE
            if (!visit.visible) return visit
            if (tile.hasTilesetContent && tile.children.isNotEmpty()) {
                // A tileset in a tile is as visible as its root.
                visit.visible = update(tile.children[0]).visible
                return visit
            }
            if (meetsScreenSpaceErrorEarly(tile, visit)) visit.visible = false
            return visit
        }

        private fun meetsScreenSpaceErrorEarly(tile: Tile, visit: Visit): Boolean {
            val parent = tile.parent ?: return false
            if (parent.hasTilesetContent || parent.refine != Refine.ADD) return false
            return screenSpaceError(tile, visit.distance, useParent = true) <= maximum
        }

        private fun screenSpaceError(tile: Tile, distance: Double, useParent: Boolean): Double {
            val error = if (useParent) tile.parent?.geometricError ?: tileset.geometricError else tile.geometricError
            if (error == 0.0) return 0.0
            return error * camera.heightPx / (max(distance, 1e-7) * camera.sseDenominator) / camera.pixelRatio
        }

        private fun canTraverse(tile: Tile): Boolean {
            if (tile.children.isEmpty()) return false
            if (tile.hasTilesetContent) return true
            return visitOf(tile).sse > maximum
        }

        /** Asks for a tile's content if it has some to fetch that has not been asked for: Cesium's `loadTile`. */
        private fun load(tile: Tile) {
            touched += tile
            when (tile.content) {
                null, is OtherContent -> return
                is PointsContent, is TilesetContent -> if (status(tile) == TileStatus.UNLOADED) requested += tile
            }
        }

        /** Cesium's `contentAvailable`: points in hand. An empty tile, a tileset in a tile and a format not drawn have none. */
        private fun contentAvailable(tile: Tile): Boolean = tile.hasRenderableContent && status(tile) == TileStatus.READY

        private fun select(tile: Tile) {
            if (contentAvailable(tile)) selected += tile
        }

        private fun traverse(root: Tile) {
            val stack = ArrayDeque<Tile>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val tile = stack.removeLast()
                touched += tile
                val visit = visitOf(tile)
                val parentRefines = tile.parent?.let { visits[it]?.refines } ?: true
                visit.refines = if (canTraverse(tile)) pushChildren(tile, stack) && parentRefines else false
                val stoppedRefining = !visit.refines && parentRefines
                when {
                    !tile.hasRenderableContent -> load(tile)
                    tile.refine == Refine.ADD -> {
                        select(tile)
                        load(tile)
                    }
                    else -> {
                        load(tile)
                        if (stoppedRefining) select(tile)
                    }
                }
            }
        }

        /** `updateAndPushChildren`: the children in view onto the stack, nearest last so it is gone into first; whether the tile may refine. */
        private fun pushChildren(tile: Tile, stack: ArrayDeque<Tile>): Boolean {
            val replace = tile.refine == Refine.REPLACE
            for (child in tile.children) update(child)
            val children = tile.children.sortedByDescending { visitOf(it).distance }
            val checkRefines = replace && tile.hasRenderableContent
            var refines = true
            var anyVisible = false
            for (child in children) {
                if (visitOf(child).visible) {
                    stack.addLast(child)
                    anyVisible = true
                } else if (checkRefines) {
                    // Out of view, but needed before the parent can give way to its children.
                    load(child)
                }
                if (checkRefines) {
                    val childRefines = if (!child.hasRenderableContent) emptyTraversal(child) else contentAvailable(child)
                    refines = refines && childRefines
                }
            }
            if (!anyVisible) refines = false
            return refines
        }

        /**
         * `executeEmptyTraversal`: whether everything under a child with no points of its own that stops the descent has its content, fetching
         * what is out of view on the way. A child with no content at all lets its parent give way whatever that says (`root.hasEmptyContent`); a
         * tileset in a tile waits for it.
         */
        private fun emptyTraversal(root: Tile): Boolean {
            var allLoaded = true
            val stack = ArrayDeque<Tile>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val tile = stack.removeLast()
                touched += tile
                val traverse = !tile.hasRenderableContent && canTraverseEmpty(tile)
                if (!traverse && !contentAvailable(tile)) allLoaded = false
                val visit = update(tile)
                if (!visit.visible) load(tile)
                if (traverse) tile.children.forEach(stack::addLast)
            }
            return root.content == null || allLoaded
        }

        // canTraverse needs the tile's error, which update() has not set yet for a tile reached first here.
        private fun canTraverseEmpty(tile: Tile): Boolean {
            if (tile.children.isEmpty()) return false
            if (tile.hasTilesetContent) return true
            val visit = visitOf(tile)
            return screenSpaceError(tile, tile.volume.distanceTo(camera.position), useParent = false).also { visit.sse = it } > maximum
        }
    }
}
