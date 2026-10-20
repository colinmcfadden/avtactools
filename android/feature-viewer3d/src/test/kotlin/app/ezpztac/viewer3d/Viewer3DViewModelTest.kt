package app.ezpztac.viewer3d

import app.ezpztac.data.LidarPhase
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.scene3d.SceneLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 3D view's state over core-data's lookup: what an opening starts, what the same LZ/PZ shown again keeps, and what closing lets go of. */
@OptIn(ExperimentalCoroutinesApi::class)
class Viewer3DViewModelTest {
    private val main = StandardTestDispatcher()
    private val hawk = Viewer3DTarget("lz-1", "LZ HAWK", 34.591552, -84.128225, saved = false)

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(api: FakeLidar = FakeLidar(), probe: FakeProbe = FakeProbe(), sizes: PointSizeMemory = InMemoryPointSizes()) =
        viewModel(api, backgroundScope, probe, scenes(dispatcher = main, clock = { testScheduler.currentTime }), sizes)

    @Test
    fun `shown, it looks up the point cloud and asks after the ground, and closed it lets both go`() = runTest(main) {
        val api = FakeLidar()
        val probe = FakeProbe()
        val model = model(api, probe)
        assertNull(model.state.value)
        model.show(hawk)
        advanceUntilIdle()
        val ui = model.state.value!!
        assertEquals("3D · LZ HAWK", ui.title)
        assertEquals(LidarPhase.AVAILABLE, ui.phase)
        assertEquals("/lidar/tilesets/${FakeLidar.KEY}/tileset.json", ui.tileset)
        assertFalse(ui.noGround)
        assertEquals(listOf("resolve 34.591552,-84.128225"), api.calls)
        assertEquals(1, probe.asked)
        model.close()
        advanceUntilIdle()
        assertNull(model.state.value)
    }

    // A build in progress is polled every 3 s for as long as it is shown, so these move the clock by hand: advanceUntilIdle would never finish.

    @Test
    fun `the same LZ-PZ shown again goes on as it was, its name followed, and being saved keeps its build`() = runTest(main) {
        val api = FakeLidar(built = false)
        val model = model(api)
        model.show(hawk)
        runCurrent()
        assertEquals(LidarPhase.BUILDING, model.state.value!!.phase)
        assertTrue("an unsaved LZ/PZ's build is not kept", "build keep=false" in api.calls)
        model.show(hawk.copy(name = "LZ HAWK (ROCK)", saved = true))
        runCurrent()
        assertEquals(1, api.calls.count { it.startsWith("resolve") })                               // no second lookup
        assertEquals("3D · LZ HAWK (ROCK)", model.state.value!!.title)
        assertTrue(model.state.value!!.saved)
        // Its progress is asked after as kept now (the service then finishes it whoever waits).
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals("status keep=true", api.calls.last())
        model.close()
        advanceUntilIdle()
    }

    @Test
    fun `moved, it looks again at the new place, and lets go of the build it was waiting on`() = runTest(main) {
        val api = FakeLidar(built = false)
        val model = model(api)
        model.show(hawk)
        runCurrent()
        model.show(hawk.copy(lat = 34.6))
        runCurrent()
        assertEquals(listOf("resolve 34.591552,-84.128225", "resolve 34.6,-84.128225"), api.calls.filter { it.startsWith("resolve") })
        assertTrue("the first build let go of", "release ${FakeLidar.KEY}" in api.calls)
        model.close()
        advanceUntilIdle()
    }

    @Test
    fun `no ground there is said only once there is a cloud to stand on it`() = runTest(main) {
        val built = model(FakeLidar(built = true), FakeProbe(ground = false))
        built.show(hawk)
        runCurrent()
        assertTrue(built.state.value!!.noGround)
        val building = model(FakeLidar(built = false), FakeProbe(ground = false))
        building.show(hawk)
        runCurrent()
        assertFalse(building.state.value!!.noGround)
        // A probe that could not ask says nothing either way.
        val unknown = model(FakeLidar(built = true), FakeProbe(ground = null))
        unknown.show(hawk)
        runCurrent()
        assertFalse(unknown.state.value!!.noGround)
        listOf(built, building, unknown).forEach { it.close() }
        advanceUntilIdle()
    }

    @Test
    fun `the cloud in hand, its scene is made for where the lookup found it, kept while the same LZ-PZ is shown, and let go with it`() = runTest(main) {
        val model = model()
        model.show(hawk)
        advanceUntilIdle()
        val scene = model.session.value!!
        assertEquals(SceneStatus.Drawing, scene.status.value)
        assertEquals(hawk.lat, scene.frame.lat, 0.0)
        model.show(hawk.copy(name = "LZ HAWK (ROCK)", saved = true))
        advanceUntilIdle()
        assertTrue("the same scene", scene === model.session.value)
        model.close()
        advanceUntilIdle()
        assertNull(model.session.value)
        assertNull("closed: it draws nothing more", scene.listener)
    }

    @Test
    fun `while the cloud is built there is no scene yet`() = runTest(main) {
        val model = model(FakeLidar(built = false))
        model.show(hawk)
        runCurrent()
        assertNull(model.session.value)
        model.close()
        advanceUntilIdle()
    }

    @Test
    fun `the point size is the person's, kept for next time and given to the scene`() = runTest(main) {
        val sizes = InMemoryPointSizes(9)
        val model = model(sizes = sizes)
        assertEquals(9, model.pointSize.value)
        model.show(hawk)
        advanceUntilIdle()
        assertEquals(9, model.session.value!!.pointSize)
        model.setPointSize(12)
        assertEquals(12, model.pointSize.value)
        assertEquals(12, sizes.get())
        assertEquals(12, model.session.value!!.pointSize)
        model.close()
        advanceUntilIdle()
        // One a later version kept out of this one's range is the first size.
        assertEquals(SceneLook.DEFAULT_POINT_SIZE, model(sizes = InMemoryPointSizes(40)).pointSize.value)
    }
}
