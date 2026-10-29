package app.ezpztac.viewer3d

import app.ezpztac.data.TerrainHeights
import app.ezpztac.data.TilesetAddress
import app.ezpztac.data.TilesetFiles
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.LidarFrameDto
import app.ezpztac.network.NetworkException
import app.ezpztac.scene3d.BoundingSpheres
import app.ezpztac.scene3d.CameraGesture
import app.ezpztac.scene3d.CameraPose
import app.ezpztac.scene3d.ImageryTileId
import app.ezpztac.scene3d.LocalFrame
import app.ezpztac.scene3d.PntsReader
import app.ezpztac.scene3d.PointCloudScene
import app.ezpztac.scene3d.RouteGeometry
import app.ezpztac.scene3d.RouteHeights
import app.ezpztac.scene3d.RouteScene3D
import app.ezpztac.scene3d.SceneGround
import app.ezpztac.scene3d.SceneLook
import app.ezpztac.scene3d.ScreenPoint
import app.ezpztac.scene3d.Sphere
import app.ezpztac.scene3d.TerrainTileId
import app.ezpztac.scene3d.TerrainTiling
import app.ezpztac.scene3d.TilesetReader
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val view: () -> Unit = { asked++ }

        init {
            session.listener = view
        }

        fun close() {
            session.close()
            parent.cancel()
        }
    }

    private fun TestScope.rig(
        files: TilesetFiles = FixtureFiles(),
        withRing: Boolean = false,
        address: TilesetAddress? = core,
        size: Int = 5,
        sources: SceneSources = SceneSources(files),
        io: CoroutineDispatcher? = null,
        lz: LidarFrameDto? = null,
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = CoroutineScope(SupervisorJob() + dispatcher)
        val session = SceneSession(
            here, address, if (withRing) ring else null, sources, SceneBudgets.DEFAULT, parent, size,
            io = io ?: dispatcher, decode = dispatcher, clock = { testScheduler.currentTime }, lzFrame = lz,
        )
        return Rig(this, session, parent)
    }

    /** The fixture's files, each [delayMs] of its path in coming (or failing, per [failing]), what was asked for and when on [now]. */
    private class SlowFiles(val delayMs: (String) -> Long, val failing: (String) -> Exception? = { null }) : TilesetFiles {
        val asked: MutableList<Pair<Long, String>> = Collections.synchronizedList(mutableListOf())
        var now: () -> Long = { 0 }

        override suspend fun file(key: String, path: String): ByteArray {
            asked += now() to path
            delay(delayMs(path))
            failing(path)?.let { throw it }
            return Fixtures.bytes("viewer3d/tileset/$path")
        }
    }

    private fun estimate(path: String): SceneGround.Estimate {
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val tile = generateSequence(listOf(doc.root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().first { it.content?.path == path }
        return SceneGround.of(PntsReader.read(Fixtures.bytes("viewer3d/tileset/$path"), tile.world, frame))!!
    }

    private fun Rig.frame() = session.frame(1080, 2200, 2.75, view)

    // The rig's view, a phone held upright: narrower than tall, so a sphere is framed from further back than 2.2 radii.
    private val upright = 1080.0 / 2200

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
        val opening = CameraPose.framing(frame, Sphere(doc.root.volume.center, doc.root.volume.radius), upright)
        val camera = r.frame()!!.camera
        assertEquals(opening.position.x, camera.position.x, 1e-9)
        assertEquals(opening.position.y, camera.position.y, 1e-9)
        assertEquals(opening.position.z, camera.position.z, 1e-9)
        assertEquals(opening.heading, camera.heading, 1e-12)
        assertEquals(opening.pitch, camera.pitch, 1e-12)
        r.close()
    }

    @Test
    fun `a streamed survey opens on the server's frame and LZ flies back to it, never the tileset's own sphere`() = runTest {
        // A streamed survey's root is a state; frame is where the LZ is, 400 m up from the ellipsoid here.
        val lz = LidarFrameDto(here.lat, here.lon, radiusM = 250.0, heightM = 400.0)
        val r = rig(lz = lz)
        advanceUntilIdle()
        val sphere = Sphere(frame.fromGeodetic(lz.lat, lz.lon, 400.0), 250.0)
        assertEquals(400.0, sphere.center.z, 1e-6)
        val opening = CameraPose.framing(frame, sphere, upright)
        val camera = r.frame()!!.camera
        assertEquals(opening.position.x, camera.position.x, 1e-9)
        assertEquals(opening.position.y, camera.position.y, 1e-9)
        assertEquals(opening.position.z, camera.position.z, 1e-9)
        assertEquals(SceneLook.framingRange(250.0, upright), (camera.position - sphere.center).length, 1e-6)
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(70.0))))
        r.frame()
        r.session.frameLz()
        advanceTimeBy(SceneLook.FRAME_MS + 1)
        val back = r.frame()!!.camera
        assertEquals(opening.position.x, back.position.x, 1e-6)
        assertEquals(opening.position.z, back.position.z, 1e-6)
        r.close()
    }

    @Test
    fun `a frame whose ground nobody knows opens on the tileset's sphere, rather than on a height made up`() = runTest {
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val root = Sphere(doc.root.volume.center, doc.root.volume.radius)
        for (lz in listOf(null, LidarFrameDto(here.lat, here.lon, 250.0, null), LidarFrameDto(here.lat, here.lon, 0.0, 400.0),
                          LidarFrameDto(Double.NaN, here.lon, 250.0, 400.0))) {
            val sphere = lzSphere(frame, lz, root)
            assertEquals("$lz", root.center, sphere.center)
            assertEquals("$lz", root.radius, sphere.radius, 0.0)
        }
    }

    @Test
    fun `the opening view and LZ are framed for the view's shape, 2_2 radii wider than tall, further back upright so the sphere fits across`() = runTest {
        val r = rig()
        advanceUntilIdle()                                                                       // the core in hand before any frame
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val sphere = Sphere(doc.root.volume.center, doc.root.volume.radius)
        // The first frame is a tablet's, wider than tall: it opens 2.2 radii back, as the web's view always has.
        val wide = r.session.frame(2200, 1080, 2.0, r.view)!!.camera
        assertEquals(sphere.radius * SceneLook.CAMERA_RANGE_FACTOR, (wide.position - sphere.center).length, 1e-6)
        // Turned upright, LZ frames it for that shape: further back, the sphere across the narrower width.
        r.session.frame(1080, 2200, 2.75, r.view)
        r.session.frameLz()
        advanceTimeBy(SceneLook.FRAME_MS + 1)
        val upright = r.frame()!!.camera
        val range = (upright.position - sphere.center).length
        assertEquals(SceneLook.framingRange(sphere.radius, 1080.0 / 2200), range, 1e-6)
        assertTrue(range > sphere.radius * SceneLook.CAMERA_RANGE_FACTOR * 1.5)
        r.close()
    }

    @Test
    fun `a view drawn before the cloud comes has its opening view framed for its shape once the cloud does`() = runTest {
        val r = rig(SlowFiles({ if (it == "tileset.json") 1_000 else 10 }))
        runCurrent()
        assertNull("nothing to look at before the cloud", r.frame())
        advanceUntilIdle()
        val doc = TilesetReader.read(Fixtures.bytes("viewer3d/tileset/tileset.json"), "tileset.json", frame)
        val sphere = Sphere(doc.root.volume.center, doc.root.volume.radius)
        val opening = CameraPose.framing(frame, sphere, upright)
        val camera = r.frame()!!.camera
        assertEquals(opening.position.x, camera.position.x, 1e-9)
        assertEquals(opening.position.y, camera.position.y, 1e-9)
        assertEquals(opening.position.z, camera.position.z, 1e-9)
        r.close()
    }

    @Test
    fun `tiles are fetched as the frames ask, read off the GL thread and drawn, and the one with most points round the LZ says where the ground is`() = runTest {
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
        // The ground the camera holds to is the octree root's (more of its points round the LZ than the preview's), no longer the cloud's middle.
        assertEquals(estimate("points/r.pnts").z, r.session.groundZ!!, 1e-9)
        assertTrue(estimate("points/r.pnts").points > estimate("preview.pnts").points)
        assertNotEquals(before, r.session.groundZ!!, 1e-6)
        r.close()
    }

    @Test
    fun `the ground does not hang on which tile comes first, and a quarter of the octree come before the rest says nothing of it`() = runTest {
        // The preview and the octree's root come late; a quarter of the octree, all canopy over the LZ, comes first.
        val files = SlowFiles({ if (it == "preview.pnts" || it == "points/r.pnts") 500 else 10 })
        val r = rig(files)
        advanceUntilIdle()
        val middle = r.session.groundZ!!
        r.frame()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(files.asked.toString(), files.asked.any { it.second == "points/r4.pnts" })
        assertEquals("the cloud's middle still", middle, r.session.groundZ!!, 1e-9)
        advanceUntilIdle()
        assertEquals(estimate("points/r.pnts").z, r.session.groundZ!!, 1e-9)
        r.close()
        // The preview first, then the octree's root: the root's, from more points.
        val inTurn = rig(SlowFiles({ if (it == "points/r.pnts") 500 else 10 }))
        advanceUntilIdle()
        inTurn.frame()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(estimate("preview.pnts").z, inTurn.session.groundZ!!, 1e-9)
        advanceUntilIdle()
        assertEquals(estimate("points/r.pnts").z, inTurn.session.groundZ!!, 1e-9)
        inTurn.close()
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
    fun `a GL view that cannot draw is said over whatever the tileset does after, come before it or while it comes`() = runTest {
        // The page makes the GL view as soon as the session is there: on a device without OpenGL ES 3 it says so at once, before the core comes.
        val early = rig(SlowFiles({ if (it == "tileset.json") 300 else 0 }))
        early.session.cannotDraw(SceneProblem.NO_GL)
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.NO_GL), early.session.status.value)
        early.close()
        // OpenGL failing to set up while the core comes.
        val meanwhile = rig(SlowFiles({ if (it == "tileset.json") 300 else 0 }))
        advanceTimeBy(50)
        meanwhile.session.cannotDraw(SceneProblem.GL_FAILED)
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.GL_FAILED), meanwhile.session.status.value)
        meanwhile.close()
        // And over a core that could not be had: Retry, which cannot help it, is not offered.
        val down = rig(SlowFiles({ 300 }, { NetworkException("no route", requestMayHaveBeenSent = false) }))
        advanceTimeBy(50)
        down.session.cannotDraw(SceneProblem.GL_FAILED)
        advanceUntilIdle()
        assertEquals(SceneStatus.Failed(SceneProblem.GL_FAILED), down.session.status.value)
        down.close()
    }

    @Test
    fun `a core whose fetch failed as the view closed says nothing of it`() = runTest {
        // The failure is handed to the session's work just before it is cancelled: it comes as the failure, not as the cancellation (AGENTS.md §14).
        val answer = CompletableDeferred<ByteArray>()
        val r = rig(object : TilesetFiles {
            override suspend fun file(key: String, path: String): ByteArray = answer.await()
        }, io = UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        answer.completeExceptionally(NetworkException("no route", requestMayHaveBeenSent = false))
        r.session.close()
        advanceUntilIdle()
        assertEquals(SceneStatus.Loading, r.session.status.value)
        r.parent.cancel()
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
    fun `the pause before a tile is asked for again runs from when it failed, a read that timed out after 30 s waiting 5 s more`() = runTest {
        var down = true
        val files = SlowFiles(
            { if (it == "preview.pnts" && down) 30_000 else 0 },
            { if (it == "preview.pnts" && down) NetworkException("timeout", requestMayHaveBeenSent = true) else null },
        )
        files.now = { testScheduler.currentTime }
        val r = rig(files)
        advanceUntilIdle()
        r.frame()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        down = false
        advanceTimeBy(PointCloudScene.RETRY_MS - 1)
        runCurrent()
        r.frame()
        runCurrent()
        assertEquals("not at once", 1, files.asked.count { it.second == "preview.pnts" })
        // Nothing comes from here to ask for a frame: the pause's end must.
        val asked = r.asked
        advanceTimeBy(2)
        runCurrent()
        assertTrue("a frame asked for when the pause is over", r.asked > asked)
        r.frame()
        runCurrent()
        assertEquals(listOf(0L, 30_000L + PointCloudScene.RETRY_MS + 1), files.asked.filter { it.second == "preview.pnts" }.map { it.first })
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
    fun `a screen reader's steps turn, tilt and zoom about the middle of the view as the fingers would`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.session.step(SceneStep.TURN_LEFT)                                                       // no view yet: nothing to step about
        val opening = r.frame()!!.camera
        r.session.step(SceneStep.TURN_LEFT)
        val left = r.frame()!!.camera
        assertSameHeading(opening.heading - Math.toRadians(SceneSession.STEP_TURN_DEG), left.heading)
        r.session.step(SceneStep.TURN_RIGHT)
        assertSameHeading(opening.heading, r.frame()!!.camera.heading)
        r.session.step(SceneStep.TILT_UP)
        assertEquals(opening.pitch + Math.toRadians(SceneSession.STEP_TILT_DEG), r.frame()!!.camera.pitch, 1e-9)
        r.session.step(SceneStep.TILT_DOWN)
        assertEquals(opening.pitch, r.frame()!!.camera.pitch, 1e-9)
        val moves = app.ezpztac.scene3d.CameraMoves(r.session.groundZ!!)
        val middle = ScreenPoint(540.0, 1100.0)
        fun range(c: app.ezpztac.scene3d.ViewCamera) = (c.position - moves.ground(c, middle)!!).length
        val far = range(r.frame()!!.camera)
        r.session.step(SceneStep.ZOOM_IN)
        assertEquals(far / SceneSession.STEP_ZOOM, range(r.frame()!!.camera), 1e-6)
        r.session.step(SceneStep.ZOOM_OUT)
        assertEquals(far, range(r.frame()!!.camera), 1e-6)
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
    fun `routes placed are drawn from the next frame, Routes frames them as LZ frames the core, and with none there is nothing to frame`() = runTest {
        val r = rig()
        advanceUntilIdle()
        val opening = r.frame()!!.camera
        assertFalse(r.session.hasRoutes.value)
        r.session.frameRoutes()
        assertFalse("nothing to frame", r.frame()!!.animating)
        val prepared = RouteScene3D.prepare(listOf(lzRoute()))
        val placed = RouteScene3D.place(prepared, RouteHeights(prepared.samples.map { 340.0 }, prepared.samples.map { -30.35 }))
        val asked = r.asked
        r.session.setRoutes(placed)
        assertTrue("a frame asked for", r.asked > asked)
        assertTrue(r.session.hasRoutes.value)
        val work = r.frame()!!
        assertEquals(1, work.routesVersion)
        assertEquals(RouteGeometry.of(placed, frame).positions, work.routes.positions)
        assertTrue(work.routes.positions.isNotEmpty())
        r.session.frameRoutes()
        assertTrue(r.frame()!!.animating)
        advanceTimeBy(SceneLook.FRAME_MS)
        val framed = r.frame()!!.camera
        val expected = CameraPose.framing(frame, BoundingSpheres.fromPoints(work.routes.positions, frame)!!, upright)
        assertEquals(expected.position.x, framed.position.x, 1e-6)
        assertEquals(expected.position.y, framed.position.y, 1e-6)
        assertEquals(expected.position.z, framed.position.z, 1e-6)
        assertSameHeading(expected.heading, framed.heading)
        assertNotEquals(opening.position.x, framed.position.x, 1e-3)
        // No routes now: none drawn, and nothing to frame.
        r.session.setRoutes(emptyList())
        assertFalse(r.session.hasRoutes.value)
        val none = r.frame()!!
        assertEquals(2, none.routesVersion)
        assertTrue(none.routes.lines.isEmpty())
        r.close()
    }

    @Test
    fun `LZ flies there in 1,2 s, as the web's`() = runTest {
        val r = rig()
        advanceUntilIdle()
        r.frame()
        r.session.gesture(listOf(CameraGesture.Turn(Math.toRadians(70.0))))
        r.frame()
        r.session.frameLz()
        advanceTimeBy(SceneLook.FRAME_MS - 1)
        assertTrue(r.frame()!!.animating)
        advanceTimeBy(1)
        assertFalse(r.frame()!!.animating)
        assertEquals(1_200L, SceneLook.FRAME_MS)
        r.close()
    }

    @Test
    fun `the budgets are a quarter of the heap held at 15 bytes a point and half that drawn, within a million and the scene's own`() {
        assertEquals(SceneBudgets(2_236_962, 4_473_924), SceneBudgets.forHeap(256))
        assertEquals(SceneBudgets(500_000, 1_000_000), SceneBudgets.forHeap(32))
        assertEquals(SceneBudgets(PointCloudScene.DRAW_BUDGET, PointCloudScene.HOLD_BUDGET), SceneBudgets.forHeap(2_048))
    }

    @Test
    fun `in the back it lets go of what the last frame did not draw, and the next frame says what went`() = runTest {
        val r = rig()
        advanceUntilIdle()
        settle(r)
        r.session.gesture(listOf(CameraGesture.Zoom(ScreenPoint(540.0, 1100.0), 0.01)))   // far off: little drawn
        val drawn = r.frame()!!.clouds.single().map { it.tile }
        val before = r.session.heldPoints
        r.session.trim()
        assertTrue(r.session.heldPoints < before)
        val next = r.frame()!!
        assertEquals(drawn, next.clouds.single().map { it.tile })
        assertTrue(next.letGo.isNotEmpty())
        r.close()
    }

    @Test
    fun `imagery a view that is not the session's says it put on its GPU is fetched again for the session's`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        settleGround(r)
        // Every texture to fetch again, as after a lost context: the next uploads are of imagery the settled view wants.
        r.session.texturesLost(r.view)
        var w: SceneWork? = null
        repeat(100) {
            if (w?.uploads?.isNotEmpty() != true) {
                w = r.frame()
                advanceUntilIdle()
            }
        }
        val uploads = w!!.uploads
        val (id, image) = uploads.first()
        val asked = g.imagery.asked.count { it == id }
        val stale: () -> Unit = {}
        r.session.uploaded(id, image.bytesOnGpu, stale)
        image.release()
        for ((other, picture) in uploads.drop(1)) {
            r.session.uploaded(other, picture.bytesOnGpu, r.view)
            picture.release()
        }
        repeat(50) {
            r.render()
            advanceUntilIdle()
        }
        assertTrue("asked again", g.imagery.asked.count { it == id } > asked)
        r.close()
    }

    @Test
    fun `what went in the back is taken by the session's view to free before it pauses, never by a view that is not the session's`() = runTest {
        val r = rig()
        advanceUntilIdle()
        settle(r)
        r.session.gesture(listOf(CameraGesture.Zoom(ScreenPoint(540.0, 1100.0), 0.01)))
        r.frame()
        r.session.trim()
        val stale: () -> Unit = {}
        assertNull(r.session.takeTrimmed(stale))
        val taken = r.session.takeTrimmed(r.session.listener!!)!!
        assertTrue(taken.tiles.isNotEmpty())
        assertTrue("not said again", r.frame()!!.letGo.isEmpty())
        r.close()
    }

    @Test
    fun `closing lets go of the points in hand`() = runTest {
        val r = rig()
        advanceUntilIdle()
        settle(r)
        assertTrue(r.frame()!!.clouds.single().isNotEmpty())
        assertTrue(r.session.heldPoints > 0)
        r.session.close()
        assertEquals(0L, r.session.heldPoints)
        r.parent.cancel()
    }

    @Test
    fun `a view that is no longer the scene's gets no frame, and takes nothing the new one is to be handed`() = runTest {
        val g = Ground()
        val r = rig(sources = g.sources)
        advanceUntilIdle()
        settleGround(r)
        // The activity made again: a new GL view takes the scene while the old one's thread still draws.
        val old = r.view
        val new: () -> Unit = {}
        r.session.listener = new
        r.session.texturesLost(old)
        assertNull(r.session.frame(1080, 2200, 2.75, old))
        assertTrue("its textures are not the scene's", r.session.frame(1080, 2200, 2.75, new)!!.uploads.isEmpty())
        // The new view's context lost: its imagery is fetched again and handed to it alone.
        r.session.texturesLost(new)
        r.session.frame(1080, 2200, 2.75, new)
        advanceUntilIdle()
        assertNull(r.session.frame(1080, 2200, 2.75, old))
        assertTrue(r.session.frame(1080, 2200, 2.75, new)!!.uploads.isNotEmpty())
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
        assertNull("no view is the scene's: it draws nothing", r.frame())
        assertEquals(0L, r.session.heldPoints)
        r.parent.cancel()
    }

    // -- The ground and its imagery -----------------------------------------------------------------------------------------------------

    /** A server with DEMs for half a degree round the LZ, flat at [height] m; a 204 everywhere else. */
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
            session.uploaded(id, image.bytesOnGpu, view)
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
        r.session.texturesLost(r.view)
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
        r.session.texturesLost(r.view)
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
