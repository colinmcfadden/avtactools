package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** The scene over py3dtiles's real tilesets: what it fetches and in what order, what it draws, what fails, and what it lets go of. */
class PointCloudSceneTest {
    private val frame = LocalFrame(34.591552, -84.128225)
    private val deg = PI / 180

    private fun core() = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)

    private fun ring() = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/context/tileset.json"), "context/tileset.json", frame)

    private fun points(tile: Tile) = PntsReader.read(Fixtures.bytes("viewer3d/tileset/${tile.content!!.path}"), tile.world, frame)

    /** Looking at [doc]'s middle as the web opens on it, from [range] metres (the web's, 2.2 radii, when not given), on a portrait phone at 2.75. */
    private fun looking(doc: TilesetDocument, range: Double? = null): ViewCamera {
        val sphere = Sphere(doc.root.volume.center, doc.root.volume.radius)
        val pose = if (range == null) CameraPose.framing(frame, sphere) else {
            val (p, h, pt) = lookAt(frame, sphere.center, -30 * deg, -35 * deg, range)
            CameraPose(p, h, pt)
        }
        return pose.camera(1080, 2200, 2.75, 0.5, 1e5)
    }

    /** Fetches everything frames ask for, as the loader would, until nothing more is asked for. */
    private fun PointCloudScene.settle(camera: ViewCamera) {
        repeat(100) {
            val f = frame(camera, 0)
            if (f.fetch.isEmpty()) return
            for (fetch in f.fetch) arrived(fetch.tile, points(fetch.tile))
        }
        error("still fetching after 100 frames")
    }

    private fun SceneFrame.points(source: String) = drawn.filter { it.tile.source == source }.sumOf { it.points.count.toLong() }

    @Test
    fun `the coarsest is asked for first, the preview drawn while the rest comes, then given way to`() {
        val doc = core()
        val scene = PointCloudScene().apply { add(doc, SceneLook.CORE_SCREEN_SPACE_ERROR) }
        val camera = looking(doc)
        val first = scene.frame(camera, 0)
        assertEquals(listOf("preview.pnts", "points/r.pnts"), first.fetch.take(2).map { it.path })
        assertEquals(PointCloudScene.MAX_FETCHES, first.fetch.size)
        assertTrue(first.fetch.drop(2).all { it.tile.depth == 2 })
        assertTrue(first.drawn.isEmpty())
        // Four out: nothing more is asked for, and nothing out is asked for twice.
        assertTrue(scene.frame(camera, 0).fetch.isEmpty())
        scene.arrived(doc.root, points(doc.root))
        val second = scene.frame(camera, 0)
        assertEquals(listOf(doc.root), second.drawn.map { it.tile })
        assertEquals(1, second.fetch.size)
        assertTrue(second.fetch.none { f -> first.fetch.any { it.tile === f.tile } })
        for (f in first.fetch.drop(1) + second.fetch) scene.arrived(f.tile, points(f.tile))
        scene.settle(camera)
        val settled = scene.frame(camera, 0)
        assertTrue(settled.drawn.isNotEmpty())
        assertTrue(settled.drawn.none { it.tile === doc.root || it.tile === doc.root.children.single() }, "both replaced")
    }

    @Test
    fun `no more are out at once than it is given`() {
        val doc = core()
        val scene = PointCloudScene(maxFetches = 2).apply { add(doc, 2.0) }
        val camera = looking(doc)
        val first = scene.frame(camera, 0)
        assertEquals(listOf("preview.pnts", "points/r.pnts"), first.fetch.map { it.path })
        assertTrue(scene.frame(camera, 0).fetch.isEmpty())
        scene.arrived(first.fetch[1].tile, points(first.fetch[1].tile))
        assertEquals(1, scene.frame(camera, 0).fetch.size)
    }

    @Test
    fun `a tile whose answer cannot be read is never asked for again, and the tile it would replace stays drawn`() {
        val doc = core()
        val octree = doc.root.children.single()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val camera = looking(doc)
        scene.frame(camera, 0)
        scene.arrived(doc.root, points(doc.root))
        assertNull(scene.failed(octree, again = false, atMs = 0))
        for (ms in listOf(0L, 60_000L, 3_600_000L)) {
            val f = scene.frame(camera, ms)
            assertTrue(f.fetch.none { it.tile === octree }, "at $ms")
            assertTrue(f.drawn.any { it.tile === doc.root }, "at $ms")
        }
    }

    @Test
    fun `a tile that met no connection is asked for again after a pause, twice, then not this session`() {
        val doc = core()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val camera = looking(doc)
        // Everything comes at once but the preview, which meets no connection whenever it is asked for. Whether it was asked for at [ms], and
        // when the scene said it would be asked for again:
        val again = ArrayList<Long?>()
        fun askedAt(ms: Long): Boolean {
            var asked = false
            for (fetch in scene.frame(camera, ms).fetch) {
                if (fetch.tile === doc.root) {
                    asked = true
                    again += scene.failed(fetch.tile, again = true, atMs = ms)
                } else {
                    scene.arrived(fetch.tile, points(fetch.tile))
                }
            }
            return asked
        }
        assertTrue(askedAt(0))
        assertFalse(askedAt(PointCloudScene.RETRY_MS - 1))
        assertTrue(askedAt(PointCloudScene.RETRY_MS))
        // The second pause is twice the first, counted from the second try.
        assertFalse(askedAt(PointCloudScene.RETRY_MS * 3 - 1))
        assertTrue(askedAt(PointCloudScene.RETRY_MS * 3))
        assertFalse(askedAt(10_000_000))
        assertEquals(listOf(PointCloudScene.RETRY_MS, PointCloudScene.RETRY_MS * 3, null), again)
    }

    @Test
    fun `the pause before a tile is asked for again runs from when its fetch failed, not from when it was asked for`() {
        val doc = core()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val camera = looking(doc)
        // Whether the preview was asked for at [ms]; everything else asked for comes.
        fun previewAskedAt(ms: Long): Boolean {
            val fetch = scene.frame(camera, ms).fetch
            for (f in fetch) if (f.tile !== doc.root) scene.arrived(f.tile, points(f.tile))
            return fetch.any { it.tile === doc.root }
        }
        assertTrue(previewAskedAt(0))
        // Its read times out 30 s after it was asked for.
        assertEquals(30_000 + PointCloudScene.RETRY_MS, scene.failed(doc.root, again = true, atMs = 30_000))
        assertFalse(previewAskedAt(30_000 + PointCloudScene.RETRY_MS - 1))
        assertTrue(previewAskedAt(30_000 + PointCloudScene.RETRY_MS))
    }

    @Test
    fun `the core is drawn first within the budget, and the ring with what it leaves, made coarser to fit`() {
        val coreDoc = core()
        val ringDoc = ring()
        val camera = looking(ringDoc, 3_000.0)
        val roomy = PointCloudScene().apply { add(coreDoc, SceneLook.CORE_SCREEN_SPACE_ERROR); add(ringDoc, SceneLook.CONTEXT_SCREEN_SPACE_ERROR) }
        roomy.settle(camera)
        val all = roomy.frame(camera, 0)
        val core = all.points("tileset.json")
        val ring = all.points("context/tileset.json")
        assertTrue(core > 0 && ring > 0)
        // The core's tiles before the ring's, and each tileset's apart, to be lit on its own.
        val sources = all.drawn.map { it.tile.source }
        assertEquals(sources.sortedBy { if (it == "tileset.json") 0 else 1 }, sources)
        assertEquals(2, all.byCloud.size)
        assertTrue(all.byCloud[0].all { it.tile.source == "tileset.json" } && all.byCloud[1].all { it.tile.source == "context/tileset.json" })
        assertEquals(all.drawn, all.byCloud.flatten())
        // Room for the core and little more: the core as it was, the ring coarser.
        val tight = PointCloudScene(drawBudget = core + 1).apply { add(coreDoc, SceneLook.CORE_SCREEN_SPACE_ERROR); add(ringDoc, SceneLook.CONTEXT_SCREEN_SPACE_ERROR) }
        tight.settle(camera)
        val fitted = tight.frame(camera, 0)
        assertEquals(core, fitted.points("tileset.json"))
        assertTrue(fitted.points("context/tileset.json") < ring, "${fitted.points("context/tileset.json")} of $ring")
    }

    /** The points drawn by each of [frames] frames for [camera], everything asked for arriving before the next; and how many tiles each asked for. */
    private fun PointCloudScene.run(camera: ViewCamera, frames: Int): List<Pair<Long, Int>> = List(frames) {
        val f = frame(camera, 0)
        for (fetch in f.fetch) arrived(fetch.tile, points(fetch.tile))
        f.drawn.sumOf { it.points.count.toLong() } to f.fetch.size
    }

    @Test
    fun `a still camera whose finest view is over the budgets settles on a coarser one, and asks for nothing more once it has`() {
        val doc = core()
        val camera = looking(doc, 30.0)
        val everything = PointCloudScene().apply { add(doc, 2.0) }
        everything.settle(camera)
        val finest = everything.frame(camera, 0).drawn.sumOf { it.points.count.toLong() }
        // What the review found: drawing half the finest view and holding no more than it, the scene asked for two tiles every other frame for ever,
        // the finest view's error fitting while its tiles were not in hand, and its tiles let go of once the coarser one was drawn.
        val scene = PointCloudScene(drawBudget = finest / 2, holdBudget = finest).apply { add(doc, 2.0) }
        val frames = scene.run(camera, 60)
        assertEquals(0, frames.drop(10).sumOf { it.second }, "$frames")
        assertTrue(frames.last().first in 1..finest / 2, "$frames")
        assertEquals(1, frames.drop(10).map { it.first }.distinct().size, "$frames")
    }

    @Test
    fun `what is let go of is what was in hand, each tile once, and the points held are counted right however many go in a frame`() {
        // Four hundred tiles of ten points, added to a root with none, all in view, then none: three hundred and ninety go in one frame. Removing
        // that many from the IdentityHashMap they are held in moves entries about its table, which a slip in reading them would show.
        val children = (0 until 400).joinToString(",") { i ->
            """{"geometricError":0,"boundingVolume":{"box":[${(i % 20) * 10 - 95},${(i / 20) * 10 - 95},0,5,0,0,0,5,0,0,0,5]},"content":{"uri":"c$i.pnts"}}"""
        }
        val m = frame.toEcef.toList().joinToString(",")
        val doc = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":10000,"root":{"transform":[$m],"geometricError":1000,"refine":"ADD",
                "boundingVolume":{"box":[0,0,0,100,0,0,0,100,0,0,0,10]},"children":[$children]}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        val (position, heading, pitch) = lookAt(frame, Vec3.ZERO, 0.0, -60 * deg, 400.0)
        val looking = ViewCamera(position, heading, pitch, 1080, 2200, 0.5, 1e5, pixelRatio = 2.75)
        val away = looking.copy(heading = heading + PI, pitch = 60 * deg)
        fun tenPoints(tile: Tile): ScenePoints = tile.volume.center.let { c ->
            ScenePoints(10, FloatArray(30) { (if (it % 3 == 0) c.x else if (it % 3 == 1) c.y else c.z).toFloat() }, ByteArray(30))
        }
        val scene = PointCloudScene(holdBudget = 100, maxFetches = 1_000).apply { add(doc, 2.0) }
        val inHand = java.util.IdentityHashMap<Tile, Int>()
        for (camera in List(3) { listOf(looking, away) }.flatten()) {
            repeat(3) {
                val f = scene.frame(camera, 0)
                for (gone in f.letGo) assertTrue(inHand.remove(gone) != null, "$gone let go of, not in hand")
                for (fetch in f.fetch) tenPoints(fetch.tile).let { scene.arrived(fetch.tile, it); inHand[fetch.tile] = it.count }
                assertEquals(inHand.values.sumOf { it.toLong() }, scene.heldPoints)
            }
            if (camera === looking) assertEquals(400, inHand.size, "all of it touched while in view, so none let go")
            if (camera === away) assertEquals(10, inHand.size)
        }
    }

    @Test
    fun `an error raised for the budget is lowered again only once the view has changed, as far as the budget allows`() {
        val doc = core()
        val near = looking(doc, 30.0)
        val far = looking(doc, 480.0)
        val unbudgeted = PointCloudScene().apply { add(doc, 2.0) }
        unbudgeted.settle(near)
        val finest = unbudgeted.frame(near, 0).drawn.sumOf { it.points.count.toLong() }
        unbudgeted.settle(far)
        val fromAfar = unbudgeted.frame(far, 0).drawn.map { it.tile }
        val scene = PointCloudScene(drawBudget = finest - 1).apply { add(doc, 2.0) }
        val close = scene.run(near, 20)
        assertTrue(close.last().first < finest, "raised close in: $close")
        // From afar the error raised close in draws less than the budget allows; it comes down a step a frame to what is drawn with no budget.
        val away = scene.run(far, 20)
        assertTrue(away.first().first < away.last().first, "$away")
        assertEquals(fromAfar, scene.frame(far, 0).drawn.map { it.tile })
        assertEquals(0, away.sumOf { it.second }, "every tile already in hand")
    }

    @Test
    fun `each tile drawn is sized by its own error, or by Cesium's guess for a leaf that has none`() {
        val doc = core()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val camera = looking(doc, 30.0)
        scene.settle(camera)
        val drawn = scene.frame(camera, 0).drawn
        assertTrue(drawn.any { it.tile.geometricError == 0.0 })
        for (d in drawn) {
            val expected = if (d.tile.geometricError > 0) d.tile.geometricError else d.points.estimatedError
            assertEquals(expected, d.geometricError, "${d.tile}")
            assertTrue(d.geometricError > 0, "${d.tile}")
        }
    }

    @Test
    fun `over what it may hold it lets go of what has not been used longest, never what the frame touched, and fetches it again when wanted`() {
        val doc = core()
        val near = looking(doc, 30.0)
        val everything = PointCloudScene().apply { add(doc, 2.0) }
        everything.settle(near)
        val held = everything.heldPoints
        val scene = PointCloudScene(holdBudget = held - 1).apply { add(doc, 2.0) }
        scene.settle(near)
        assertEquals(held, scene.heldPoints, "all of it touched while it is wanted, so none let go")
        // From far away only the octree's root is drawn: the leaves go, but no more of them than it takes.
        val far = scene.frame(looking(doc, 20_000.0), 0)
        assertEquals(listOf(doc.root.children.single()), far.drawn.map { it.tile })
        assertTrue(far.letGo.isNotEmpty())
        assertTrue(far.letGo.none { gone -> far.drawn.any { it.tile === gone } || gone === doc.root })
        assertTrue(scene.heldPoints <= held - 1)
        assertTrue(scene.heldPoints + far.letGo.last().let { gone -> points(gone).count } > held - 1, "one fewer would have done")
        val back = scene.frame(near, 0)
        assertTrue(back.fetch.any { f -> far.letGo.any { it === f.tile } })
    }

    @Test
    fun `a tileset in a tile is fetched and joined, its root asked for next, and one that cannot be read is a failure for good`() {
        val m = frame.toEcef.toList().joinToString(",")
        fun outer() = TilesetReader.read(
            """{"asset":{"version":"1.0"},"geometricError":1000,"root":{"transform":[$m],"geometricError":16,"refine":"ADD",
                "boundingVolume":{"box":[0,0,0,30,0,0,0,30,0,0,0,30]},"content":{"uri":"inner/tileset.json"}}}""".encodeToByteArray(),
            "tileset.json", frame,
        )
        val inner = """{"asset":{"version":"1.0"},"geometricError":16,"root":{"geometricError":0,"boundingVolume":{"box":[0,0,0,10,0,0,0,10,0,0,0,10]},
            "content":{"uri":"leaf.pnts"}}}""".encodeToByteArray()
        val (position, heading, pitch) = lookAt(frame, Vec3.ZERO, 0.0, -35 * deg, 100.0)
        val camera = ViewCamera(position, heading, pitch, 1080, 2200, 0.5, 1e5, pixelRatio = 2.75)

        val broken = PointCloudScene().apply { add(outer(), 2.0) }
        val asked = broken.frame(camera, 0).fetch.single()
        assertTrue(asked.isTileset)
        assertEquals("inner/tileset.json", asked.path)
        assertEquals("Not JSON", broken.joined(asked.tile, "{".encodeToByteArray(), frame)?.substringBefore(":"))
        assertTrue(broken.frame(camera, 1_000_000).fetch.isEmpty())

        val doc = outer()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val holder = scene.frame(camera, 0).fetch.single().tile
        assertNull(scene.joined(holder, inner, frame))
        assertEquals("inner/leaf.pnts", holder.children.single().content?.path)
        assertEquals(listOf("inner/leaf.pnts"), scene.frame(camera, 0).fetch.map { it.path })
        // Joined once: the same answer again is refused, and the tree is as it was.
        assertEquals("inner/tileset.json has joined already", scene.joined(holder, inner, frame))
        assertEquals(1, holder.children.size)
    }

    @Test
    fun `clearing lets go of everything, and what was out is asked for afresh`() {
        val doc = core()
        val scene = PointCloudScene().apply { add(doc, 2.0) }
        val camera = looking(doc)
        val first = scene.frame(camera, 0).fetch
        scene.arrived(first[0].tile, points(first[0].tile))
        scene.failed(first[1].tile, again = false, atMs = 0)
        scene.clear()
        assertEquals(0, scene.heldPoints)
        assertEquals(first.map { it.tile }, scene.frame(camera, 0).fetch.map { it.tile })
    }
}
