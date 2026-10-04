package app.ezpztac.workspace

import app.ezpztac.data.AnalysisService
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.TerrainApi
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drawing a boundary as the screens see it: what may be started, the count of corners, and the words when something cannot be done. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")
    private val a = LatLon(34.70, -84.10)
    private val b = LatLon(34.80, -84.00)
    private val c = LatLon(34.80, -84.10)

    /** A terrain server that can be held back, to keep an analysis running. */
    private class HeldTerrain : TerrainApi {
        val release = CompletableDeferred<FieldAnalysis>()
        override suspend fun analyzeField(at: LatLon): FieldAnalysis = release.await()
        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis = error("not asked for")
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
        val terrain = HeldTerrain()
        val analysis = AnalysisService(terrain, session, repository, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler))
        val drawing = BoundaryDrawing(session)
        val corners = app.ezpztac.data.BoundaryCorners(session)
        val model = BoundaryViewModel(drawing, session, analysis, corners)
        val state get() = model.state.value
    }

    private suspend fun Rig.open(withTarget: Boolean = true): String {
        val made = repository.create(target, "LZ HAWK")
        session.open(made.id)
        if (!withTarget) session.edit("Clear target") { it.copy(target = null) }
        return made.id
    }

    private fun ring(vararg points: LatLon) = JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })

    @Test
    fun `with no diagram open nothing can be started, and trying says so`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        assertEquals(BoundaryUiState(), r.state)
        r.model.start()
        advanceUntilIdle()
        assertEquals("Open a diagram first.", r.state.error)
        assertFalse(r.state.drawing)
    }

    @Test
    fun `a diagram with a target can be drawn on, and has nothing to clear`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        assertTrue(r.state.canStart)
        assertFalse(r.state.clears)
        assertEquals(0, r.state.drawnPoints)
        assertNull(r.state.cannotStart)
    }

    @Test
    fun `a diagram with no target cannot, and says why`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open(withTarget = false)
        advanceUntilIdle()
        assertFalse(r.state.canStart)
        assertEquals("Set a target before drawing a boundary.", r.state.cannotStart)
    }

    @Test
    fun `starting over an analysis or an earlier drawing says it will clear something`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.session.edit("Analyze") { DiagramOps.completeAnalysis(it, JsonObject(mapOf("detectedLZ" to ring(a, b, c)))) }
        advanceUntilIdle()
        assertTrue(r.state.clears)

        val s = Rig(this)
        s.open()
        s.session.edit("Draw") { DiagramOps.setAnalysisDraft(it, JsonObject(mapOf("customLZ" to ring(a, b, c)))) }
        advanceUntilIdle()
        assertTrue(s.state.clears)
        assertEquals(3, s.state.drawnPoints)
    }

    @Test
    fun `an analysis in flight on the open diagram holds drawing back, and says why`() = runTest(dispatcher) {
        val r = Rig(this)
        val id = r.open()
        r.analysis.analyze(id)
        advanceUntilIdle()
        assertFalse(r.state.canStart)
        assertEquals("Wait for the analysis to finish, or stop it, before drawing a boundary.", r.state.cannotStart)
        r.analysis.cancel()
        advanceUntilIdle()
        assertTrue(r.state.canStart)
        assertNull(r.state.cannotStart)
    }

    @Test
    fun `an analysis in flight on another diagram does not hold this one back`() = runTest(dispatcher) {
        val r = Rig(this)
        val first = r.open()
        r.analysis.analyze(first)
        advanceUntilIdle()
        val other = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.session.open(other.id)
        advanceUntilIdle()
        assertTrue(r.state.canStart)
        assertNull(r.state.cannotStart)
    }

    @Test
    fun `drawing is a start, corners at the crosshair, and a finish that saves the boundary`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        r.model.start()
        advanceUntilIdle()
        assertTrue(r.state.drawing)
        assertFalse(r.state.canStart)
        assertEquals(0, r.state.points)
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
        advanceUntilIdle()
        assertEquals(2, r.state.points)
        assertFalse(r.state.canFinish)
        r.model.addAtCrosshair(c)
        advanceUntilIdle()
        assertTrue(r.state.canFinish)
        r.model.finish()
        advanceUntilIdle()
        assertFalse(r.state.drawing)
        assertNull(r.state.error)
        assertEquals(3, r.state.drawnPoints)
        assertTrue(r.state.canStart)
    }

    @Test
    fun `finishing too soon says how many are needed and keeps drawing`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.start()
        r.model.addAtCrosshair(a)
        r.model.finish()
        advanceUntilIdle()
        assertEquals("A boundary needs at least 3 points.", r.state.error)
        assertTrue(r.state.drawing)
        assertEquals(1, r.state.points)
        r.model.dismissError()
        advanceUntilIdle()
        assertNull(r.state.error)
    }

    @Test
    fun `with no crosshair position there is nowhere to put a corner`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.start()
        r.model.addAtCrosshair(null)
        advanceUntilIdle()
        assertEquals("Move the map to where the corner should go first.", r.state.error)
        assertEquals(0, r.state.points)
        r.model.addAtCrosshair(a)                                                          // and a good one puts the words away
        advanceUntilIdle()
        assertNull(r.state.error)
        assertEquals(1, r.state.points)
    }

    @Test
    fun `a corner that cannot be added says so`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.start()
        r.model.addAtCrosshair(LatLon(95.0, 0.0))
        advanceUntilIdle()
        assertEquals("That corner could not be added.", r.state.error)
    }

    @Test
    fun `undo takes a corner back and cancel stops, each putting away the last words`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.start()
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
        r.model.finish()                                                                   // too soon: an error is showing
        advanceUntilIdle()
        assertEquals(2, r.state.points)
        r.model.undoPoint()
        advanceUntilIdle()
        assertEquals(1, r.state.points)
        assertNull(r.state.error)
        r.model.finish()
        advanceUntilIdle()
        r.model.cancel()
        advanceUntilIdle()
        assertFalse(r.state.drawing)
        assertNull(r.state.error)
        assertEquals(0, r.state.drawnPoints)
    }

    @Test
    fun `a held corner is named, placed, and deleted from the view model as one step`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.session.setQuietly { DiagramOps.setAnalysisDraft(it, JsonObject(mapOf("customLZ" to ring(LatLon(34.0, -84.0), LatLon(34.0, -83.9), LatLon(34.1, -83.9), LatLon(34.1, -84.0))))) }
        r.corners.select(app.ezpztac.model.BoundaryCornerRef(true, 1))
        advanceUntilIdle()
        val held = r.state.heldCorner!!
        assertEquals("Boundary corner 2 of 4", held.title)
        assertTrue(held.canDelete)
        r.model.deleteCorner()
        advanceUntilIdle()
        assertNull(r.state.heldCorner)
        assertNull(r.state.error)
        assertEquals(3, r.state.drawnPoints)
    }

    @Test
    fun `the third corner cannot be deleted and the refusal is in words`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.session.setQuietly { DiagramOps.setAnalysisDraft(it, JsonObject(mapOf("customLZ" to ring(LatLon(34.0, -84.0), LatLon(34.0, -83.9), LatLon(34.1, -83.9))))) }
        r.corners.select(app.ezpztac.model.BoundaryCornerRef(true, 0))
        advanceUntilIdle()
        assertEquals(false, r.state.heldCorner!!.canDelete)
        r.model.deleteCorner()
        advanceUntilIdle()
        assertEquals("A boundary needs at least 3 corners.", r.state.error)
        assertEquals(3, r.state.drawnPoints)
    }
}
