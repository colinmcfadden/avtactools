package app.ezpztac.viewer3d

import app.ezpztac.data.TerrainHeights
import app.ezpztac.data.TilesetAddress
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.scene3d.CameraGesture
import app.ezpztac.scene3d.CameraPose
import app.ezpztac.scene3d.ImageryTileId
import app.ezpztac.scene3d.LocalFrame
import app.ezpztac.scene3d.PntsReader
import app.ezpztac.scene3d.PointCloudScene
import app.ezpztac.scene3d.SceneGround
import app.ezpztac.scene3d.SceneLook
import app.ezpztac.scene3d.ScreenPoint
import app.ezpztac.scene3d.Sphere
import app.ezpztac.scene3d.TerrainTileId
import app.ezpztac.scene3d.TerrainTiling
import app.ezpztac.scene3d.TilesetReader
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** One LZ's scene over the tileset py3dtiles made: how it opens, fetches and draws, what a failure does, the camera's moves and flights, and closing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneSessionTest {
    private val here = LatLon(34.591552, -84.128225)
    private val frame = LocalFrame(here.lat, here.lon)
    private val core = TilesetAddress(FakeLidar.KEY, "tileset.json")
    private val ringKey = "fedcba9876543210"
    private val ring = TilesetAddress(ringKey, "context/tileset.json")

    private class Rig(scope: TestScope, val session: SceneSession, val parent: CoroutineScope) {
        var asked = 0

        init {
            session.listener = { asked++ }
        }

        fun close() {
            session.close()
            parent.cancel()
        }
    }

    private fun TestScope.rig(
        files: FixtureFiles = FixtureFiles(),
        withRing: Boolean = false,
        address: TilesetAddress? = core,
        size: Int = 5,
        sources: SceneSources = SceneSources(files),
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = CoroutineScope(SupervisorJob() + dispatcher)
        val session = SceneSession(
            here, address, if (withRing) ring else null, sources, SceneBudgets.DEFAULT, parent, size,
            io = dispatcher, decode = dispatcher, clock = { testScheduler.currentTime },
        )
        return Rig(this, session, parent)
    }

    private fun Rig.frame() = session.frame(1080, 2200, 2.75)

    /** Headings the same way round (a flight may land a turn past north, 370° for 10°). */
    private fun assertSameHeading(expected: Double, actual: Double) = assertEquals(0.0, Math.IEEEremainder(actual - expected, 2 * Math.PI), 1e-9)

    /** Draws frames as a renderer would, fetching what each asks for, until none asks for more. */
    private fun TestScope.settle(r: Rig) {
        repeat(50) {
            r.frame()
            advanceUntilIdle()
        }
    }

    @Test
    fun `it opens framing the core's sphere, as the web's view does, once the core's tileset is in hand`() = runTest {
        val r = rig()
        assertEquals(SceneStatus.Loading, r.session.status.value)
        assertNull("nothing to draw before the tileset is in hand", r.frame())
        advanceUntilIdle()
        assertEquals(SceneStatus.Drawing, r.session.status.value)
        assertTrue("a frame is asked for", r.asked > 0)
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val opening = CameraPose.framing(frame, Sphere(doc.root.volume.center, doc.root.volume.radius))
        val camera = r.frame()!!.camera
        assertEquals(opening.position.x, camera.position.x, 1e-9)
        assertEquals(opening.position.y, camera.position.y, 1e-9)
        assertEquals(opening.position.z, camera.position.z, 1e-9)
        assertEquals(opening.heading, camera.heading, 1e-12)
        assertEquals(opening.pitch, camera.pitch, 1e-12)
        r.close()
    }

    @Test
    fun `tiles are fetched as the frames ask, read off the GL thread and drawn, and the first to come says where the ground is`() = runTest {
        val files = FixtureFiles()
        val r = rig(files)
        advanceUntilIdle()
        val before = r.session.groundZ!!
        val first = r.frame()!!
        assertTrue(first.clouds.single().isEmpty())
        advanceUntilIdle()
        assertTrue(files.asked.toString(), "${FakeLidar.KEY}/preview.pnts" in files.asked)
        val asked = r.asked
        settle(r)
        assertTrue("each tile that came asked for a frame", r.asked > asked)
        val drawn = r.frame()!!.clouds.single()
        assertTrue(drawn.isNotEmpty())
        assertTrue(drawn.none { it.tile.content?.path == "preview.pnts" })
        // The ground the camera holds to is the first tile's (the preview, fetched first), no longer the cloud's middle.
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val preview = PntsReader.read(Fixtures.bytes("viewer3d/tileset/preview.pnts"), doc.root.world, frame)
        assertEquals(SceneGround.of(preview)!!, r.session.groundZ!!, 1e-9)
        assertNotEquals(before, r.session.groundZ!!, 1e-6)
        r.close()
    }

    @Test
    fun `the ring is asked for after the core under its own key, and one that cannot be had is no ring`() = runTest {
        val files = FixtureFiles()
        val r = rig(files, withRing = true)
        advanceUntilIdle()
        assertEquals(listOf("${FakeLidar.KEY}/tileset.json", "$ringKey/context/tileset.json"), files.asked.take(2))
        settle(r)
        assertTrue(files.asked.filter { it.startsWith("$ringKey/") }.all { it.removePrefix("$ringKey/").startsWith("context/") })
        assertTrue(files.asked.filter { it.startsWith("${FakeLidar.KEY}/") }.none { it.removePrefix("${FakeLidar.KEY}/").startsWith("context/") })
        assertEquals(2, r.frame()!!.clouds.size)
        r.close()
        // No ring to be had: the core is drawn all the same.
        val without = rig(FixtureFiles(failing = { if (it.startsWith("context/")) NetworkException("no route", requestMayHaveBeenSent = false) else null }), withRing = true)
        advanceUntilIdle()
        assertEquals(SceneStatus.Drawing, without.session.status.value)
        assertEquals(1, without.frame()!!.clouds.size)
        without.close()
    }

    @Test
    fun `a core that cannot be fetched says so and Retry fetches it again, and one that cannot be read, or no address, says that`() = runTest {
        var down = true
        val files = FixtureFiles(failing = { if (down && it == "tileset.json") NetworkException("no route", requestMayHaveBeenSent = false) else null })
        val r = rig(files)
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.UNREACHABLE), r.session.status.value)
        down = false
        r.session.retry()
        assertEquals(SceneStatus.Loading, r.session.status.value)
        advanceUntilIdle()
        assertEquals(SceneStatus.Drawing, r.session.status.value)
        r.close()

        val refused = rig(FixtureFiles(failing = { ApiException(404, "not_found", "gone") }))
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.UNREADABLE), refused.session.status.value)
        refused.close()
        val busy = rig(FixtureFiles(failing = { ApiException(503, null, "down") }))
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.UNREACHABLE), busy.session.status.value)
        busy.close()
        // A place this version cannot read: said for good, since trying again cannot help.
        val nowhere = rig(address = null)
        assertEquals(SceneStatus.Failed(SceneProblem.NOT_A_TILESET), nowhere.session.status.value)
        assertFalse(SceneProblem.NOT_A_TILESET.retryable)
        nowhere.session.retry()
        assertEquals(SceneStatus.Failed(SceneProblem.NOT_A_TILESET), nowhere.session.status.value)
        nowhere.close()
    }

    @Test
    fun `a device that cannot draw it says so for good, and Retry does nothing then`() = runTest {
        val files = FixtureFiles()
        val r = rig(files)
        advanceUntilIdle()
        r.session.cannotDraw(SceneProblem.NO_GL)
        r.session.retry()
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.NO_GL), r.session.status.value)
        assertEquals(1, files.asked.count { it.endsWith("/tileset.json") })
        r.close()
    }

    @Test
    fun `a tile that met no connection is asked for again after the pause, and a frame is asked for when it is over`() = runTest {
        var down = true
        val files = FixtureFiles(failing = { if (down && it == "preview.pnts") NetworkException("no route", requestMayHaveBeenSent = false) else null })
        val r = rig(files)
        advanceUntilIdle()
        r.frame()
        runCurrent()
        assertEquals(1, files.asked.count { it.endsWith("/preview.pnts") })
        down = false
        val asked = r.asked
        advanceTimeBy(PointCloudScene.RETRY_MS - 10)
        r.frame()
        runCurrent()
        assertEquals("not before the pause is over", 1, files.asked.count { it.endsWith("/preview.pnts") })
        advanceTimeBy(20)
        runCurrent()
        assertTrue("a frame asked for when it is over", r.asked > asked)
        r.frame()
        advanceUntilIdle()
        assertEquals(2, files.asked.count { it.endsWith("/preview.pnts") })
        r.close()
    }

    @Test
    fun `a tile whose answer cannot be read is not asked for again`() = runTest {
        val files = FixtureFiles(failing = { if (it == "preview.pnts") ApiException(404, "not_found", "gone") else null })
        val r = rig(files)
        advanceUntilIdle()
        settle(r)
        advanceTimeBy(3_600_000)
        settle(r)
        assertEquals(1, files.asked.count { it.endsWith("/preview.pnts") })
        r.close()
    }

    @Test
    fun `the fingers move the camera, and a finger on it ends a flight where it has got to`() = runTest {
        val r = rig()
        advanceUntilIdle()
        val opening = r.frame()!!.camera
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(30.0))))
        val turned = r.frame()!!.camera
        assertSameHeading(opening.heading - Math.toRadians(30.0), turned.heading)
        // *LZ* flies back to the opening view; half way, a finger takes it.
        r.session.frameLz()
        assertTrue(r.frame()!!.animating)
        advanceTimeBy(SceneLook.FRAME_MS / 2)
        val halfWay = r.frame()!!.camera
        r.session.gesture(listOf(CameraGesture.Pan(ScreenPoint(540.0, 1100.0), ScreenPoint(540.0, 1100.0))))
        val held = r.frame()!!
        assertFalse(held.animating)
        assertSameHeading(halfWay.heading, held.camera.heading)
        advanceTimeBy(SceneLook.FRAME_MS)
        assertSameHeading(halfWay.heading, r.frame()!!.camera.heading)
        r.close()
    }

    @Test
    fun `LZ flies back to the view it opened on, and the compass turns it to face north about the middle`() = runTest {
        val r = rig()
        advanceUntilIdle()
        val opening = r.frame()!!.camera
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(70.0)), CameraGesture.Zoom(ScreenPoint(540.0, 1100.0), 2.0)))
        r.frame()
        r.session.frameLz()
        advanceTimeBy(SceneLook.FRAME_MS)
        val back = r.frame()!!
        assertFalse(back.animating)
        assertSameHeading(opening.heading, back.camera.heading)
        assertEquals(opening.position.x, back.camera.position.x, 1e-6)
        assertEquals(opening.position.z, back.camera.position.z, 1e-6)
        r.session.faceNorth()
        assertTrue(r.frame()!!.animating)
        advanceTimeBy(SceneLook.FACE_NORTH_MS)
        val north = r.frame()!!
        assertFalse(north.animating)
        assertSameHeading(0.0, north.camera.heading)
        assertEquals(opening.pitch, north.camera.pitch, 1e-9)
        assertEquals("000°T", r.session.compass.value!!.headingText)
        r.close()
    }

    @Test
    fun `the compass follows the camera, and a change under a tenth of a degree is not told`() = runTest {
        val r = rig()
        advanceUntilIdle()
        assertNull(r.session.compass.value)
        r.frame()
        val first = r.session.compass.value!!
        assertEquals("330°T", first.headingText)
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(0.05))))
        r.frame()
        assertTrue("the same pose, not a new one", first === r.session.compass.value)
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(1.0))))
        r.frame()
        assertNotEquals(first, r.session.compass.value)
        r.close()
    }

    @Test
    fun `the point size is kept within 2 to 16 and asks for a frame`() = runTest {
        val r = rig(size = 40)
        assertEquals(SceneLook.DEFAULT_POINT_SIZE, r.session.pointSize)
        advanceUntilIdle()
        val asked = r.asked
        r.session.setPointSize(9)
        assertEquals(9, r.session.pointSize)
        assertEquals(9, r.frame()!!.pointSize)
        assertTrue(r.asked > asked)
        r.session.setPointSize(1)
        assertEquals(SceneLook.DEFAULT_POINT_SIZE, r.session.pointSize)
        r.close()
    }

    @Test
    fun `closing stops every fetch and lets go of every point`() = runTest {
        val files = FixtureFiles()
        val r = rig(files)
        advanceUntilIdle()
        r.frame()
        r.session.close()
        val asked = files.asked.size
        advanceUntilIdle()
        repeat(3) { r.frame() }
        advanceUntilIdle()
        assertEquals(asked, files.asked.size)
        assertNull(r.session.listener)
        assertNotNull(r.frame())
        assertTrue(r.frame()!!.clouds.single().isEmpty())
        r.parent.cancel()
    }

    // -- The ground and its imagery -----------------------------------------------------------------------------------------------------

    /** A server with DEMs for half a degree round the LZ, flat at [height] m; a 204 everywhere else. */
    private class FakeHeights(val height: Short = 340) : TerrainHeights {
        val asked: MutableList<TerrainTileId> = Collections.synchronizedList(mutableListOf())
        var failing: (TerrainTileId) -> Exception? = { null }

        fun covers(tile: TerrainTileId): Boolean {
            val b = TerrainTiling.bounds(tile)
            return b.west < -83.6 && b.east > -84.6 && b.south < 35.1 && b.north > 34.1
        }

        override suspend fun tile(tile: TerrainTileId): ShortArray? {
            asked += tile
            failing(tile)?.let { throw it }
            return if (covers(tile)) ShortArray(65 * 65) { height } else null
        }
    }

    /** Mapbox, answering every tile with a byte for its level; [failing] says what to throw instead. */
    private class FakeImagery : ImageryTiles {
        val asked: MutableList<ImageryTileId> = Collections.synchronizedList(mutableListOf())
        var failing: (ImageryTileId) -> Exception? = { null }

        override suspend fun tile(id: ImageryTileId): ByteArray {
            asked += id
            failing(id)?.let { throw it }
            return byteArrayOf(id.level.toByte())
        }
    }

    private class FakeImage : ImageryImage {
        var released = false
        override val bytesOnGpu: Long = 1_000

        override fun release() {
            released = true
        }
    }

    private class FakeDecoder : ImageryDecoder {
        val made: MutableList<FakeImage> = Collections.synchronizedList(mutableListOf())

        override fun decode(bytes: ByteArray): ImageryImage? = if (bytes.isEmpty()) null else FakeImage().also { made += it }
    }

    private class Ground(val heights: FakeHeights = FakeHeights(), val imagery: FakeImagery = FakeImagery(), val decoder: FakeDecoder = FakeDecoder()) {
        val sources = SceneSources(FixtureFiles(), heights, imagery, decoder)
    }

    /** A frame as the renderer draws it: what is decoded put on the GPU, and said so. */
    private fun Rig.render(): SceneWork? = frame()?.also { w ->
        for ((id, image) in w.uploads) {
            session.uploaded(id, image.bytesOnGpu)
            image.release()
        }
    }

    private fun TestScope.settleGround(r: Rig): SceneWork {
        var last: SceneWork? = null
        repeat(400) {
            last = r.render()
            advanceUntilIdle()
        }
        return last!!
    }

    @Test
    fun `the ground is fetched coarse first round the LZ and drawn, none where the server has none`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        val w = settleGround(r)
        assertTrue(w.ground.isNotEmpty())
        assertTrue(w.ground.all { g.heights.covers(it.tile) })
        assertTrue(TerrainTileId(0, 1, 0) in g.heights.asked)
        assertTrue(w.ground.none { it.tile.level == 0 && it.tile.x == 1 })
        assertTrue("fine tiles at the LZ", w.ground.first().tile.level >= 14)
        val order = g.heights.asked.withIndex().associate { (i, t) -> t to i }
        for (t in g.heights.asked) if (t.level > 0) assertTrue(order.getValue(TerrainTileId(t.level - 1, t.x / 2, t.y / 2)) < order.getValue(t))
        r.close()
    }

    @Test
    fun `imagery is fetched for the ground drawn, decoded off the GL thread, put on the GPU a few a frame, and laid on`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        var mostAFrame = 0
        repeat(400) {
            val w = r.render()
            mostAFrame = maxOf(mostAFrame, w?.uploads?.size ?: 0)
            advanceUntilIdle()
        }
        assertTrue(mostAFrame in 1..3)
        val w = r.render()!!
        assertTrue(w.ground.isNotEmpty())
        for (d in w.ground) {
            assertTrue("${d.tile}", w.imagery.getValue(d.tile).isNotEmpty())
            assertTrue("${d.tile}", d.tile in w.imageryWhole)
        }
        r.close()
    }

    @Test
    fun `imagery Mapbox refuses is never asked for again, while one that met no connection is after a pause, the ground bare meanwhile`() = runTest {
        val g = Ground()
        g.imagery.failing = { ImageryRefused("401") }
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        val w = settleGround(r)
        assertTrue(w.ground.isNotEmpty())
        assertTrue(w.ground.all { it.tile !in w.imageryWhole })
        val asked = g.imagery.asked.size
        advanceTimeBy(3_600_000)
        settleGround(r)
        assertEquals("refused for good", asked, g.imagery.asked.size)
        r.close()

        // Each tile meets no connection the first time it is asked for, and comes the second, after the pause (the clock runs on through it).
        val h = Ground()
        val failedOnce = Collections.synchronizedSet(HashSet<ImageryTileId>())
        h.imagery.failing = { id -> if (failedOnce.add(id)) IOException("no route") else null }
        val s = rig(sources = h.sources)
        advanceUntilIdle()
        val after = settleGround(s)
        // Those still wanted after the pause were asked for again and came (a coarse one the ground had refined past by then is not wanted).
        assertTrue(failedOnce.any { id -> h.imagery.asked.count { it == id } == 2 })
        assertTrue(h.imagery.asked.none { id -> h.imagery.asked.count { it == id } > 2 })
        assertTrue(after.ground.all { it.tile in after.imageryWhole })
        s.close()
    }

    @Test
    fun `a lost GL context fetches the imagery again`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        settleGround(r)
        val asked = g.imagery.asked.size
        r.session.texturesLost()
        val w = settleGround(r)
        assertTrue(g.imagery.asked.size > asked)
        assertTrue(w.ground.all { it.tile in w.imageryWhole })
        r.close()
    }

    @Test
    fun `the moves hold to the terrain's height at the LZ once it comes, over the points' guess`() = runTest {
        val g = Ground(FakeHeights(height = 412))
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        settleGround(r)
        assertEquals(frame.fromGeodetic(here.lat, here.lon, 412.0).z, r.session.groundZ!!, 1e-6)
        r.close()
    }

    @Test
    fun `closing lets go of the imagery decoded and still waiting for the GPU, and the renderer of what it was handed`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        settleGround(r)
        // The textures lost: the next frame asks for the imagery again, which is decoded and waits for a frame that does not come; and
        // a frame whose renderer took what it was handed but never uploaded it.
        r.session.texturesLost()
        val handed = mutableListOf<ImageryImage>()
        r.frame()?.let { w -> handed += w.uploads.map { it.second } }
        advanceUntilIdle()
        r.frame()?.let { w -> handed += w.uploads.map { it.second } }
        advanceUntilIdle()
        val waiting = g.decoder.made.filter { made -> !made.released && handed.none { it === made } }
        assertTrue(waiting.isNotEmpty())
        r.close()
        assertTrue(waiting.all { it.released })
        assertTrue(handed.none { (it as FakeImage).released })
        r.parent.cancel()
    }
}
