package app.ezpztac.workspace

import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.BoundaryCorners
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.TerrainApi
import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The bar that follows *Finish* (the owner's request, 2026-10-21): a boundary just drawn is offered its analysis over the map, which says what it is doing, says a
 * failure in words, and goes once the analysis has taken the boundary as its own, is dismissed, or the LZ/PZ is no longer the open one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeOfferTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")
    private val a = LatLon(34.78, -84.09)
    private val b = LatLon(34.79, -84.08)
    private val c = LatLon(34.78, -84.07)
    private val d = LatLon(34.77, -84.08)

    /** A terrain server whose answer for the area is given by the test; the slope, a nicety the analysis stands without, it refuses. */
    private class GatedTerrain : TerrainApi {
        var find = CompletableDeferred<FieldAnalysis>()
        override suspend fun analyzeField(at: LatLon): FieldAnalysis = find.await()
        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis = throw ApiException(500, null, "no slope here")
    }

    private val found = FieldAnalysis("success", listOf(listOf(34.71, -84.09), listOf(34.71, -84.01), listOf(34.79, -84.01)), "4050", "Field detected")

    private class Rig(scope: TestScope, val pack: FakeOpenPack = FakeOpenPack()) {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope, pack.lzStore)
        val terrain = GatedTerrain()
        val analysis = AnalysisService(terrain, session, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler))
        val drawing = BoundaryDrawing(session)
        val corners = BoundaryCorners(session)
        val model = BoundaryViewModel(drawing, session, analysis, corners, pack)
        val offer get() = model.state.value.analyzeOffer
    }

    private suspend fun Rig.open(name: String = "LZ HAWK", at: DiagramTarget = target): String {
        val made = repository.create(at, name)
        session.open(made.id)
        return made.id
    }

    private fun TestScope.draw(r: Rig, vararg corners: LatLon) {
        r.model.start()
        advanceUntilIdle()
        corners.forEach { r.model.addAtCrosshair(it) }
        advanceUntilIdle()
        r.model.finish()
        advanceUntilIdle()
    }

    @Test
    fun `nothing is offered while drawing, and finishing offers the analysis with the corners drawn`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        r.model.start()
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.addAtCrosshair(c)
        advanceUntilIdle()
        assertNull("the drawing toolbar is over the map, not the offer", r.offer)
        r.model.finish()
        advanceUntilIdle()
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
    }

    @Test
    fun `the offer analyses the boundary drawn, says what it is doing, and goes once it is analysed`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        draw(r, a, b, c, d)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        assertEquals(AnalyzeOfferUi(points = 4, stage = "Finding the landing area…"), r.offer)
        r.terrain.find.complete(found)
        advanceUntilIdle()
        assertNull("the analysis took the drawn boundary as its own", r.offer)
        val diagram = checkNotNull(r.session.active.value)
        assertEquals(DiagramStatus.ANALYZED, diagram.status)
        assertEquals(0, r.model.state.value.drawnPoints)
    }

    @Test
    fun `stopping puts the offer back as it was, and a failure is said with the boundary still offered`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        draw(r, a, b, c)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        r.model.stopAnalysis()
        advanceUntilIdle()
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        r.terrain.find.completeExceptionally(ApiException(400, null, "No field found"))
        advanceUntilIdle()
        val failed = r.analysis.status.value as AnalysisStatus.Failed
        assertEquals(AnalyzeOfferUi(points = 3, failed = failed.message), r.offer)
        // Try again: the same action, with the server answering this time.
        r.terrain.find = CompletableDeferred()
        r.model.analyzeDrawn()
        advanceUntilIdle()
        assertEquals("Finding the landing area…", r.offer?.stage)
        r.terrain.find.complete(found)
        advanceUntilIdle()
        assertNull(r.offer)
    }

    @Test
    fun `not now puts the offer away, and a boundary drawn again is offered again`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        draw(r, a, b, c)
        r.model.dismissOffer()
        advanceUntilIdle()
        assertNull(r.offer)
        assertEquals("the boundary waits in the panel", 3, r.model.state.value.drawnPoints)
        draw(r, a, b, c, d)
        assertEquals(AnalyzeOfferUi(points = 4), r.offer)
    }

    @Test
    fun `the offer belongs to the LZ-PZ it was drawn on`() = runTest(dispatcher) {
        val r = Rig(this)
        val hawk = r.open()
        draw(r, a, b, c)
        r.open("LZ CROW", DiagramTarget(35.0, -85.0, "16S GD 1 1"))
        advanceUntilIdle()
        assertNull("another LZ/PZ is open", r.offer)
        r.session.open(hawk)
        advanceUntilIdle()
        assertEquals("its boundary still waits for an analysis", AnalyzeOfferUi(points = 3), r.offer)
    }

    @Test
    fun `a pack that turns read-only takes the offer away, and analysing from it is refused in words`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        advanceUntilIdle()
        val item = FakeOpenPack.lzOf("lz-1", checkNotNull(r.session.active.value).copy(id = "pack:p:lz-1"))
        r.pack.state.value = FakeOpenPack.open(listOf(item), session = FakeOpenPack.session())
        r.session.open("pack:p:lz-1")
        advanceUntilIdle()
        draw(r, a, b, c)
        assertNotNull(r.offer)
        r.pack.state.value = FakeOpenPack.open(listOf(item), session = FakeOpenPack.finished())
        advanceUntilIdle()
        assertNull(r.offer)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        assertEquals("OP DK is finished: nothing in it can be changed.", r.model.state.value.error)
        assertEquals(AnalysisStatus.Idle, r.analysis.status.value)
    }

    @Test
    fun `once analysed, a later analysis of the same LZ-PZ does not bring the offer back`() = runTest(dispatcher) {
        val r = Rig(this)
        val id = r.open()
        draw(r, a, b, c)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        r.terrain.find.complete(found)
        advanceUntilIdle()
        assertNull(r.offer)
        r.terrain.find = CompletableDeferred()
        r.analysis.analyze(id)                                                                   // Re-analyze, from the panel
        advanceUntilIdle()
        assertNull(r.offer)
        r.terrain.find.completeExceptionally(ApiException(400, null, "No field found"))
        advanceUntilIdle()
        assertNull("its failure is the panel's to say", r.offer)
    }

    @Test
    fun `an earlier analysis's failure is not said on a boundary drawn after it`() = runTest(dispatcher) {
        val r = Rig(this)
        val id = r.open()
        r.analysis.analyze(id)
        advanceUntilIdle()
        r.terrain.find.completeExceptionally(ApiException(400, null, "No field found"))
        advanceUntilIdle()
        draw(r, a, b, c)
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
    }

    @Test
    fun `analysing from the offer while another LZ-PZ is being analysed says so`() = runTest(dispatcher) {
        val r = Rig(this)
        val crow = r.open("LZ CROW", DiagramTarget(35.0, -85.0, "16S GD 1 1"))
        r.analysis.analyze(crow)
        advanceUntilIdle()
        r.open()
        draw(r, a, b, c)
        r.model.analyzeDrawn()
        advanceUntilIdle()
        assertEquals("Another LZ/PZ is being analysed. Wait for it to finish, or stop it in its panel.", r.model.state.value.error)
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
    }

    @Test
    fun `a corner deleted from the boundary waiting is counted`() = runTest(dispatcher) {
        val r = Rig(this)
        r.open()
        draw(r, a, b, c, d)
        r.corners.select(BoundaryCornerRef(true, 1))
        r.model.deleteCorner()
        advanceUntilIdle()
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
    }

    @Test
    fun `drawing on another LZ-PZ keeps this one's offer until a boundary is finished there`() = runTest(dispatcher) {
        val r = Rig(this)
        val hawk = r.open()
        draw(r, a, b, c)
        r.open("LZ CROW", DiagramTarget(34.78, -84.08, "16S GD 2 2"))
        r.model.start()
        r.model.cancel()
        r.session.open(hawk)
        advanceUntilIdle()
        assertEquals(AnalyzeOfferUi(points = 3), r.offer)
    }
}
