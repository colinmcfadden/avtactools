package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * Which tiles are drawn and fetched: Cesium's base traversal, rule by rule, on small trees whose numbers are worked by hand, and on py3dtiles's
 * real tileset as the camera comes in.
 */
class TileSelectionTest {
    private val frame = LocalFrame(34.591552, -84.128225)
    private val deg = PI / 180

    /** A tile at [center] (scene metres) [half] metres each way, as JSON in the scene's frame (its transform is the frame's own). */
    private fun tile(center: Vec3, half: Double, error: Double, refine: String?, content: String?, children: List<String> = emptyList()): String {
        val r = refine?.let { "\"refine\":\"$it\"," } ?: ""
        val c = content?.let { ",\"content\":{\"uri\":\"$it\"}" } ?: ""
        val k = if (children.isEmpty()) "" else ",\"children\":[${children.joinToString(",")}]"
        return """{$r"geometricError":$error,"boundingVolume":{"box":[${center.x},${center.y},${center.z},$half,0,0,0,$half,0,0,0,$half]}$c$k}"""
    }

    private fun tileset(root: String, error: Double = 1000.0): TilesetDocument {
        val m = frame.toEcef.toList().joinToString(",")
        val withTransform = root.replaceFirst("{", "{\"transform\":[$m],")
        return TilesetReader.read("""{"asset":{"version":"1.0"},"geometricError":$error,"root":$withTransform}""".encodeToByteArray(), "tileset.json", frame)
    }

    /** A camera [range] metres south of the origin, looking north, level, 1000 pixels tall. */
    private fun camera(range: Double, heading: Double = 0.0, pitch: Double = 0.0) =
        ViewCamera(Vec3(0.0, -range, 0.0), heading, pitch, 1500, 1000, near = 0.1, far = 1e6)

    private fun Tile.walk(): Sequence<Tile> = sequenceOf(this) + children.asSequence().flatMap { it.walk() }

    private fun TilesetDocument.named(path: String): Tile = root.walk().first { it.content?.path == path }

    private fun sse(error: Double, distance: Double, c: ViewCamera) = error * c.heightPx / (distance * c.sseDenominator)

    /** A test's rule for what is in hand, as the selector's status: in hand is READY, anything else not asked for yet. */
    private fun ready(inHand: (Tile) -> Boolean): (Tile) -> TileStatus = { if (inHand(it)) TileStatus.READY else TileStatus.UNLOADED }

    private fun TileSelector.pick(doc: TilesetDocument, camera: ViewCamera, inHand: (Tile) -> Boolean): Selection = select(doc, camera, ready(inHand))

    // A REPLACE root with four ADD children around the origin, a 10 m box each, 30 m apart.
    private val quads = listOf(Vec3(-15.0, -15.0, 0.0), Vec3(15.0, -15.0, 0.0), Vec3(-15.0, 15.0, 0.0), Vec3(15.0, 15.0, 0.0))
    private fun replaceRoot() = tileset(
        tile(Vec3.ZERO, 30.0, 16.0, "REPLACE", "root.pnts", quads.mapIndexed { i, c -> tile(c, 10.0, 0.0, "ADD", "c$i.pnts") }),
    )

    @Test
    fun `a replaced tile is drawn until all of its children are in hand, which are fetched meanwhile`() {
        val doc = replaceRoot()
        val near = camera(100.0)
        val none = TileSelector(2.0).pick(doc, near) { it === doc.root }
        assertEquals(listOf(doc.root), none.selected)
        assertEquals(setOf("c0.pnts", "c1.pnts", "c2.pnts", "c3.pnts"), none.requested.map { it.content!!.path }.toSet())
        // Three of four in hand: still the parent, and (as Cesium has it) the added children already in hand with it.
        val three = TileSelector(2.0).pick(doc, near) { it.content?.path != "c3.pnts" }
        assertEquals(listOf("root.pnts", "c0.pnts", "c1.pnts", "c2.pnts"), three.selected.map { it.content!!.path }.let { it.take(1) + it.drop(1).sorted() })
        assertEquals(listOf("c3.pnts"), three.requested.map { it.content!!.path })
        // All in hand: the children instead, nearest first.
        val all = TileSelector(2.0).pick(doc, near) { true }
        assertEquals(listOf("c0.pnts", "c1.pnts", "c2.pnts", "c3.pnts").sorted(), all.selected.map { it.content!!.path }.sorted())
        assertTrue(all.selected.first().volume.center.y < 0, "the nearer pair first")
        assertTrue(all.requested.isEmpty())
    }

    @Test
    fun `a replaced tile waits for a child out of view too, which is fetched though it is not drawn`() {
        val doc = replaceRoot()
        // Close in, looking at the north-west child only: the others are out of the frustum.
        val c = ViewCamera(Vec3(-15.0, 0.0, 0.0), 0.0, 0.0, 1500, 1000, near = 0.1, far = 1e6, fov = 20 * deg)
        val selection = TileSelector(2.0).pick(doc, c) { it.content?.path in setOf("root.pnts", "c2.pnts") }
        assertEquals(setOf("root.pnts", "c2.pnts"), selection.selected.map { it.content!!.path }.toSet())
        assertTrue(selection.requested.any { it.content?.path == "c0.pnts" }, "the hidden children are wanted before the parent can give way")
        val ready = TileSelector(2.0).pick(doc, c) { true }
        assertEquals(listOf("c2.pnts"), ready.selected.map { it.content!!.path })
        // In hand, the hidden ones are touched though not drawn, so a cache keeps them: let go of, the root would be drawn again in their place.
        assertTrue(ready.touched.mapNotNull { it.content?.path }.containsAll(listOf("c0.pnts", "c1.pnts", "c3.pnts")), "${ready.touched}")
    }

    @Test
    fun `from far enough away the tile's own error is small enough, and its children are not gone into`() {
        val doc = replaceRoot()
        // Refined while 16 m of error is more than 2 pixels at the box's distance.
        val c = camera(100.0)
        val threshold = 16.0 * c.heightPx / (2.0 * c.sseDenominator)
        val far = camera(threshold + 30.0 + 50.0)
        val selection = TileSelector(2.0).pick(doc, far) { true }
        assertEquals(listOf(doc.root), selection.selected)
        assertTrue(sse(16.0, far.position.length - 30.0, far) <= 2.0)
    }

    @Test
    fun `from further still even the root is too small to matter, and nothing is drawn or fetched`() {
        val doc = replaceRoot()
        val c = camera(100.0)
        // The root is measured with the tileset's own error (1000 m here) for this.
        val range = 1000.0 * c.heightPx / (2.0 * c.sseDenominator) + 100.0
        val selection = TileSelector(2.0).pick(doc, camera(range)) { true }
        assertTrue(selection.selected.isEmpty())
        assertTrue(selection.requested.isEmpty())
    }

    @Test
    fun `looking away, nothing`() {
        val selection = TileSelector(2.0).pick(replaceRoot(), camera(100.0, heading = PI)) { true }
        assertTrue(selection.selected.isEmpty() && selection.requested.isEmpty())
    }

    @Test
    fun `added tiles are drawn with their parent, and a child whose parent's error is already small enough is not gone into`() {
        val doc = tileset(
            tile(Vec3.ZERO, 30.0, 16.0, "ADD", "root.pnts", listOf(
                tile(Vec3(0.0, 0.0, 0.0), 10.0, 4.0, "ADD", "near.pnts"),
                tile(Vec3(0.0, 2000.0, 0.0), 10.0, 4.0, "ADD", "far.pnts"),
            )),
        )
        val c = camera(100.0)
        // The far child: the root's 16 m of error at its 2,090 m is under two pixels on a small screen; the near one's is not.
        val small = ViewCamera(c.position, 0.0, 0.0, 150, 100, near = 0.1, far = 1e6)
        val atFar = sse(16.0, 2090.0, small)
        assertTrue(atFar < 2.0, "the far child's parent error is $atFar px")
        assertTrue(sse(16.0, 90.0, small) > 2.0)
        val selection = TileSelector(2.0).pick(doc, small) { true }
        assertEquals(setOf("root.pnts", "near.pnts"), selection.selected.map { it.content!!.path }.toSet())
        // In hand or not, an added tile is fetched when it is reached, its parent drawn meanwhile.
        val fetching = TileSelector(2.0).pick(doc, small) { it === doc.root }
        assertEquals(listOf(doc.root), fetching.selected)
        assertEquals(listOf("near.pnts"), fetching.requested.map { it.content!!.path })
    }

    @Test
    fun `an empty tile under a replaced one lets it give way at once, its children coming in as they are fetched`() {
        val doc = tileset(
            tile(Vec3.ZERO, 30.0, 16.0, "REPLACE", "root.pnts", listOf(
                tile(Vec3.ZERO, 30.0, 8.0, null, null, listOf(tile(Vec3(0.0, 0.0, 0.0), 10.0, 0.0, "ADD", "leaf.pnts"))),
            )),
        )
        val c = camera(100.0)
        // Cesium exempts empty tiles from waiting: "it looks better if children stream in as they are loaded to fill the empty space".
        val waiting = TileSelector(2.0).pick(doc, c) { it === doc.root }
        assertTrue(waiting.selected.isEmpty())
        assertTrue(waiting.requested.any { it.content?.path == "leaf.pnts" })
        val ready = TileSelector(2.0).pick(doc, c) { true }
        assertEquals(listOf("leaf.pnts"), ready.selected.map { it.content!!.path })
    }

    @Test
    fun `a tileset in a tile is fetched, then gone into once its root has joined`() {
        val m = frame.toEcef.toList().joinToString(",")
        val outer = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":1000,"root":{"transform":[$m],"geometricError":100,"refine":"ADD",
                "boundingVolume":{"box":[0,0,0,30,0,0,0,30,0,0,0,30]},"content":{"uri":"inner/tileset.json"}}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        val before = TileSelector(2.0).pick(outer, camera(100.0)) { false }
        assertEquals(listOf(outer.root), before.requested)
        assertTrue(before.selected.isEmpty())
        TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":100,"root":{"geometricError":0,"boundingVolume":{"box":[0,0,0,10,0,0,0,10,0,0,0,10]},
                "content":{"uri":"leaf.pnts"}}}""".encodeToByteArray(),
            "inner/tileset.json", frame, parent = outer.root,
        )
        val after = TileSelector(2.0).pick(outer, camera(100.0)) { it === outer.root || it.content?.path == "inner/leaf.pnts" }
        assertEquals(listOf("inner/leaf.pnts"), after.selected.map { it.content!!.path })
    }

    @Test
    fun `the screen's density is taken out of the error, as Cesium takes its pixel ratio`() {
        val doc = replaceRoot()
        val c = camera(100.0)
        val threshold = 16.0 * c.heightPx / (2.0 * c.sseDenominator) + 30.0
        // Just inside the threshold refines at a ratio of one; at three the error is a third, and it does not.
        val oneX = TileSelector(2.0).pick(doc, camera(threshold - 10.0)) { true }
        assertEquals(4, oneX.selected.size)
        val threeX = TileSelector(2.0).pick(doc, camera(threshold - 10.0).copy(pixelRatio = 3.0)) { true }
        assertEquals(listOf(doc.root), threeX.selected)
    }

    @Test
    fun `over budget the error allowed is raised until what is drawn fits, from the raises it is given, and it says how many`() {
        val doc = replaceRoot()
        val points = { tile: Tile -> if (tile === doc.root) 1_000L else 2_000L }
        val selector = TileSelector(2.0)
        val all = selector.withinBudget(doc, camera(1000.0), ready { true }, budget = 1_000_000, points = points)
        assertEquals(4, all.selection.selected.size)
        assertEquals(0, all.raises)
        val tight = selector.withinBudget(doc, camera(1000.0), ready { true }, budget = 5_000, points = points)
        assertEquals(listOf(doc.root), tight.selection.selected)
        assertTrue(tight.raises > 0)
        assertEquals(tight.raises, generateSequence(0) { it + 1 }.first { selector.withinBudget(doc, camera(1000.0), ready { true }, 5_000, points, from = it).raises == it })
        // Started from more raises than it needs, it keeps them: lowering them again is the caller's to decide.
        val kept = selector.withinBudget(doc, camera(1000.0), ready { true }, budget = 1_000_000, points = points, from = tight.raises + 1)
        assertEquals(tight.raises + 1, kept.raises)
        assertEquals(listOf(doc.root), kept.selection.selected)
        // Never past the most: what is drawn then is over the budget.
        val over = selector.withinBudget(doc, camera(1000.0), ready { true }, budget = 10, points = points)
        assertEquals(TileSelector.MAX_RAISES, over.raises)
        assertEquals(TileSelector.MAX_RAISES, selector.withinBudget(doc, camera(1000.0), ready { true }, budget = 10, points = points, from = 99).raises)
        assertEquals(2.0 * 1.5 * 1.5, selector.errorAfter(2))
        assertEquals(2.0, selector.errorAfter(-1))
    }

    @Test
    fun `a failed tile is never asked for again, nor one on its way, and the tile it would replace stays drawn in its place`() {
        val doc = replaceRoot()
        val status = { tile: Tile ->
            when (tile.content?.path) {
                "c3.pnts" -> TileStatus.FAILED
                "c2.pnts" -> TileStatus.LOADING
                else -> TileStatus.READY
            }
        }
        val selection = TileSelector(2.0).select(doc, camera(100.0), status)
        // The root waits on c3 for good, as Cesium's does: drawn, with the added children in hand beside it.
        assertEquals(setOf("root.pnts", "c0.pnts", "c1.pnts"), selection.selected.map { it.content!!.path }.toSet())
        assertTrue(selection.requested.isEmpty())
    }

    @Test
    fun `a replaced tile none of whose children is in view does not give way, though they are all in hand`() {
        val doc = tileset(
            tile(Vec3.ZERO, 1000.0, 16.0, "REPLACE", "root.pnts", listOf(
                tile(Vec3(0.0, -3000.0, 0.0), 10.0, 0.0, "ADD", "behind0.pnts"),
                tile(Vec3(30.0, -3000.0, 0.0), 10.0, 0.0, "ADD", "behind1.pnts"),
            )),
        )
        val selection = TileSelector(2.0).pick(doc, camera(100.0)) { true }
        assertEquals(listOf(doc.root), selection.selected)
    }

    @Test
    fun `under a tile with nothing of its own, what is out of view is fetched before it is needed`() {
        val doc = tileset(
            tile(Vec3.ZERO, 30.0, 16.0, "REPLACE", "root.pnts", listOf(
                tile(Vec3.ZERO, 3000.0, 8.0, null, null, listOf(
                    tile(Vec3(0.0, 15.0, 0.0), 10.0, 0.0, "ADD", "ahead.pnts"),
                    tile(Vec3(0.0, -3000.0, 0.0), 10.0, 0.0, "ADD", "behind.pnts"),
                )),
            )),
        )
        val selection = TileSelector(2.0).pick(doc, camera(100.0)) { it === doc.root }
        // Cesium's executeEmptyTraversal walks the empty tile, asking for what is out of view; the main traversal asks for what is in it.
        assertEquals(setOf("ahead.pnts", "behind.pnts"), selection.requested.map { it.content!!.path }.toSet())
        assertTrue(selection.selected.isEmpty(), "the root gives way to the empty tile at once")
    }

    @Test
    fun `a tileset in a tile is gone into however small the error of the tile that holds it`() {
        val m = frame.toEcef.toList().joinToString(",")
        val outer = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":1000,"root":{"transform":[$m],"geometricError":0,"refine":"ADD",
                "boundingVolume":{"box":[0,0,0,30,0,0,0,30,0,0,0,30]},"content":{"uri":"inner/tileset.json"}}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":0,"root":{"geometricError":0,"boundingVolume":{"box":[0,0,0,10,0,0,0,10,0,0,0,10]},
                "content":{"uri":"leaf.pnts"}}}""".encodeToByteArray(),
            "inner/tileset.json", frame, parent = outer.root,
        )
        // Its own error of none would stop any other tile, and would cull a child of an added tile early: neither holds under a tileset's holder.
        val selection = TileSelector(2.0).pick(outer, camera(100.0)) { true }
        assertEquals(listOf("inner/leaf.pnts"), selection.selected.map { it.content!!.path })
    }

    @Test
    fun `a tileset in a tile whose root has nothing to draw keeps the tile above it drawn, as Cesium does`() {
        val m = frame.toEcef.toList().joinToString(",")
        val outer = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":1000,"root":{"transform":[$m],"geometricError":16,"refine":"REPLACE",
                "boundingVolume":{"box":[0,0,0,30,0,0,0,30,0,0,0,30]},"content":{"uri":"root.pnts"},
                "children":[{"geometricError":8,"boundingVolume":{"box":[0,0,0,30,0,0,0,30,0,0,0,30]},"content":{"uri":"inner/tileset.json"}}]}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        val holder = outer.root.children.single()
        TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":8,"root":{"geometricError":0,"boundingVolume":{"box":[0,0,0,10,0,0,0,10,0,0,0,10]}}}""".encodeToByteArray(),
            "inner/tileset.json", frame, parent = holder,
        )
        // The joined root stops the descent with no points: Cesium's contentAvailable is false for it, so the parent does not give way to a hole.
        val selection = TileSelector(2.0).pick(outer, camera(100.0)) { true }
        assertEquals(listOf(outer.root), selection.selected)
    }

    // -- py3dtiles's tileset ------------------------------------------------------------------------------------------------

    @Test
    fun `py3dtiles's tileset fills in from its preview to its leaves as the camera comes in`() {
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val octree = doc.named("points/r.pnts")
        val leaves = doc.root.walk().filter { it.children.isEmpty() }.toSet()
        val lookingAt = { range: Double ->
            val (position, heading, pitch) = lookAt(frame, doc.root.volume.center, -30 * deg, -35 * deg, range)
            ViewCamera(position, heading, pitch, 1080, 2200, near = 0.5, far = 1e5, pixelRatio = 2.75)
        }
        // Nothing in hand yet: the preview is what is fetched first.
        val first = TileSelector(2.0).pick(doc, lookingAt(2.2 * doc.root.volume.radius)) { false }
        assertTrue(first.selected.isEmpty())
        assertEquals(doc.root, first.requested.first())
        // The preview in hand, close up: drawn while the octree comes.
        val preview = TileSelector(2.0).pick(doc, lookingAt(2.2 * doc.root.volume.radius)) { it === doc.root }
        assertEquals(listOf(doc.root), preview.selected)
        assertTrue(octree in preview.requested)
        // Everything in hand: no preview and no octree root (both replaced), and every leaf in view drawn.
        val everything = TileSelector(2.0).pick(doc, lookingAt(30.0)) { true }
        assertTrue(doc.root !in everything.selected && octree !in everything.selected)
        assertTrue(everything.selected.any { it in leaves })
        assertTrue(everything.requested.isEmpty())
        // From far away the octree's root alone stands for the cloud (it replaces the preview, whose error is the tileset's); further still,
        // nothing at all.
        val far = TileSelector(2.0).pick(doc, lookingAt(20_000.0)) { true }
        assertEquals(listOf(octree), far.selected)
        val further = TileSelector(2.0).pick(doc, lookingAt(200_000.0).copy(far = 1e6)) { true }
        assertTrue(further.selected.isEmpty())
    }

    @Test
    fun `the ring refines past its octree's root from 2,9 km, its error scaled by its frame as Cesium scales it`() {
        // The review's case: the ring's octree holds error 11.2 in a frame ten times larger, which Cesium takes as 112. From 2.88 km, on a
        // 1080 by 2200 phone at 2.75, that is 28 pixels against the ring's 8, and the web goes into the octree's children; read unscaled it
        // would be 2.8, and stop at the octree's root.
        val ring = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/context/tileset.json"), "context/tileset.json", frame)
        val octree = ring.root.children.single()
        val (position, heading, pitch) = lookAt(frame, ring.root.volume.center, -30 * deg, -35 * deg, 2880.0)
        val c = ViewCamera(position, heading, pitch, 1080, 2200, near = 0.5, far = 1e5, pixelRatio = 2.75)
        val selection = TileSelector(8.0).pick(ring, c) { true }
        assertTrue(octree !in selection.selected && ring.root !in selection.selected)
        assertEquals(octree.children.toSet(), selection.selected.toSet())
    }
}
