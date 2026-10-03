package app.ezpztac.android

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.InMemoryAircraftChoice
import app.ezpztac.data.InMemoryMasterProfileStore
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.data.TerrainApi
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.map.CameraState
import app.ezpztac.map.LzScene
import app.ezpztac.map.MapProjection
import app.ezpztac.map.SlopeImage
import app.ezpztac.network.NetworkException
import app.ezpztac.network.SlopeStats
import app.ezpztac.network.SlopeThresholds
import app.ezpztac.network.Uh60Limits
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.symbols.SymbolOutcome
import app.ezpztac.symbols.SymbolRenderer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Flaky(private val inner: InMemorySyncStore = InMemorySyncStore()) : SyncStore, RecordFeed by inner {
        var failing = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) error("the database was busy")
            return inner.transaction(block)
        }
    }

    /** A server that measures every slope the same and keeps count; with [reachable] false it is a phone with no signal. */
    private class SlopeServer(var reachable: Boolean = true) : TerrainApi {
        val slopeCalls = mutableListOf<List<LatLon>>()
        override suspend fun analyzeField(at: LatLon): FieldAnalysis = throw NetworkException("not asked in these tests", null, requestMayHaveBeenSent = false)
        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis {
            slopeCalls += polygon
            if (!reachable) throw NetworkException("no signal", null, requestMayHaveBeenSent = false)
            return TerrainAnalysis(
                status = "success", overlay = "data:image/png;base64,AA==", bounds = listOf(listOf(34.70, -84.10), listOf(34.80, -84.00)),
                source = "local_highres_cog", resolutionM = 10.0, verticalDatum = "NAVD88",
                stats = SlopeStats(4.0, 3.0, 0.0, 0.0, 0.0, 100, 9_000.0), directional = null,
                thresholds = SlopeThresholds(listOf(3.0, 6.0, 10.0, 15.0), Uh60Limits(6.0, 15.0, 15.0)),
            )
        }
    }

    private class FakeLastDiagram(var stored: String? = null) : LastDiagram {
        override fun id() = stored

        override fun remember(id: String) {
            stored = id
        }
    }

    private class Rig(scope: TestScope, val server: SlopeServer = SlopeServer(), listening: Boolean = true) {
        val store = Flaky()
        val sync = SyncRepository(store, SequentialIds("t"))
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(sync, store, scheduler)
        val session = DiagramSession(repository, scope.backgroundScope)
        // Not backgroundScope: advanceUntilIdle leaves a background scope's work alone, and a slope being measured is work a test waits for.
        val analysis = AnalysisService(server, session, repository, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler))
        val selection = GraphicSelection()
        val aircraft = AircraftProfiles(
            store, InMemoryMasterProfileStore(listOf(AircraftProfile(), AircraftProfile(id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29))),
            { emptyList() }, InMemoryAircraftChoice(), CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), sync, scheduler,
        )
        val drawing = BoundaryDrawing(session)
        val last = FakeLastDiagram()
        val model = HomeViewModel(session, analysis, selection, aircraft, drawing, last, symbols = SymbolRenderer { _, _ -> SymbolOutcome.Unavailable })
        val seen = mutableListOf<OpenedDiagram>()
        private val testScope = scope

        init {
            if (listening) listen()
        }

        /** The map starts listening for the diagrams that open. Unconfined, so the collector is subscribed the moment it starts and nothing emitted after is missed. */
        fun listen() {
            testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) { model.opened.collect { seen += it } }
        }
    }

    // -- Coming back to the diagram that was open --------------------------------------------------------------------------

    @Test
    fun `the diagram that was open is open again at launch, and the map is taken to it`() = runTest(dispatcher) {
        val r = Rig(this)
        val made = r.repository.create(target, "LZ HAWK")
        r.last.stored = made.id
        advanceUntilIdle()
        assertEquals(made.id, r.session.active.value?.id)
        assertEquals(listOf(OpenedDiagram(made.id, LatLon(34.783817, -84.08219), "satellite")), r.seen)
    }

    @Test
    fun `it waits until the map is listening, so the event that takes the map there is not lost`() = runTest(dispatcher) {
        val r = Rig(this, listening = false)
        val made = r.repository.create(target, "LZ HAWK")
        r.last.stored = made.id
        advanceUntilIdle()
        assertEquals(null, r.session.active.value)                                          // nobody to tell yet: nothing opened
        r.listen()
        advanceUntilIdle()
        assertEquals(made.id, r.session.active.value?.id)
        assertEquals(1, r.seen.size)
    }

    @Test
    fun `nothing remembered opens nothing`() = runTest(dispatcher) {
        val r = Rig(this)
        r.repository.create(target, "LZ HAWK")
        advanceUntilIdle()
        assertEquals(null, r.session.active.value)
        assertEquals(emptyList<OpenedDiagram>(), r.seen)
    }

    @Test
    fun `a diagram that is gone is not opened, and is not an error`() = runTest(dispatcher) {
        val r = Rig(this)
        val made = r.repository.create(target, "LZ HAWK")
        r.repository.delete(made.id)
        r.last.stored = made.id
        val never = Rig(this)
        never.last.stored = "never-existed"                                                 // an id this account never had, as after another signs in
        advanceUntilIdle()
        assertEquals(null, r.session.active.value)
        assertEquals(null, never.session.active.value)
        assertEquals(emptyList<OpenedDiagram>(), r.seen + never.seen)
    }

    @Test
    fun `a diagram that will not open leaves the app running, with nothing open`() = runTest(dispatcher) {
        val r = Rig(this)
        val made = r.repository.create(target, "LZ HAWK")
        r.last.stored = made.id
        r.store.failing = true                                                              // the database is busy at launch
        advanceUntilIdle()                                                                  // an uncaught failure here would fail the test
        assertEquals(null, r.session.active.value)
        r.store.failing = false
        r.session.open(made.id)                                                             // and the person can open it from the list afterwards
        advanceUntilIdle()
        assertEquals(made.id, r.session.active.value?.id)
    }

    @Test
    fun `a diagram opened in the meantime is not replaced by the remembered one`() = runTest(dispatcher) {
        val r = Rig(this)
        val old = r.repository.create(target, "LZ HAWK")
        val chosen = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.last.stored = old.id
        r.session.open(chosen.id)
        advanceUntilIdle()
        assertEquals(chosen.id, r.session.active.value?.id)
    }

    @Test
    fun `opening a diagram remembers it, and closing the session does not forget it`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        assertEquals(made.id, r.last.stored)
        r.session.close()                                                                   // as at sign-out: the same account is back next time
        advanceUntilIdle()
        assertEquals(made.id, r.last.stored)
    }

    @Test
    fun `opening a diagram says where the map should go and which base map it had`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        assertEquals(listOf(OpenedDiagram(made.id, LatLon(34.783817, -84.08219), "satellite")), r.seen)
    }

    @Test
    fun `editing the open diagram does not send the map back to it`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.model.baseMapChosen("topo")
        advanceUntilIdle()
        assertEquals(1, r.seen.size)
    }

    @Test
    fun `opening a second diagram says so`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val a = r.repository.create(target, "A")
        val b = r.repository.create(DiagramTarget(35.0, -85.0, "16S FD 00000 00000"), "B")
        r.session.open(a.id)
        advanceUntilIdle()
        r.session.open(b.id)
        advanceUntilIdle()
        assertEquals(listOf(a.id, b.id), r.seen.map { it.id })
        assertEquals(LatLon(35.0, -85.0), r.seen.last().at)
    }

    @Test
    fun `a screen that is built later is not told about a diagram that was already open`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        val late = mutableListOf<OpenedDiagram>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.opened.collect { late += it } }   // a turn of the phone
        advanceUntilIdle()
        assertEquals(emptyList<OpenedDiagram>(), late)
    }

    @Test
    fun `the base map the person chooses is saved with the diagram`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.model.baseMapChosen("topo")
        r.session.flush()
        assertEquals("topo", r.repository.open(made.id)!!.view.mapStyle)
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `choosing a base map with nothing open does nothing`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.model.baseMapChosen("topo")
        advanceUntilIdle()
        assertEquals(emptyList<OpenedDiagram>(), r.seen)
    }

    @Test
    fun `going to the background writes what is unsaved at once`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.model.appStopped()
        advanceUntilIdle()                                                                    // far less than the save pause
        assertEquals("LZ CROW", r.repository.open(made.id)!!.name)
    }

    @Test
    fun `a write that fails on the way to the background does not end the app`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.store.failing = true
        r.model.appStopped()
        advanceUntilIdle()
        assertEquals(true, r.session.saveFailed.value)
        assertEquals("LZ CROW", r.session.active.value!!.name)                               // kept, and owed
    }

    // -- What the map draws -------------------------------------------------------------------------------------------

    private fun boundary(vararg points: Pair<Double, Double>) = JsonArray(points.map { (a, b) -> JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(b))) })
    private val lz = boundary(34.71 to -84.09, 34.71 to -84.01, 34.79 to -84.01, 34.79 to -84.09)

    /** A diagram that was analysed (its boundary is [lz]), made the way an analysis makes one. */
    private suspend fun Rig.analysed(): String {
        val id = repository.create(target, "LZ HAWK").id
        session.update(id) { DiagramOps.afterAnalysis(it, kotlinx.serialization.json.JsonObject(mapOf("detectedLZ" to lz))) }
        return id
    }

    @Test
    fun `with nothing open the map draws nothing`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        assertTrue(r.model.scene.value.isEmpty)
    }

    @Test
    fun `an open diagram is drawn with its target and boundary`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val id = r.analysed()
        r.session.open(id)
        advanceUntilIdle()
        val scene = r.model.scene.value
        assertEquals(LatLon(34.783817, -84.08219), scene.target)
        assertEquals(4, scene.boundary.size)
    }

    @Test
    fun `opening an analysed diagram measures its slope once, and the raster is laid where the server said`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val id = r.analysed()
        r.session.open(id)
        advanceUntilIdle()
        assertEquals(1, r.server.slopeCalls.size)
        assertEquals(SlopeImage(south = 34.70, west = -84.10, north = 34.80, east = -84.00, dataUri = "data:image/png;base64,AA=="), r.model.scene.value.slope)

        r.session.edit("Rename") { it.copy(name = "renamed") }                                // an edit that leaves the boundary alone
        advanceUntilIdle()
        assertEquals(1, r.server.slopeCalls.size)
    }

    @Test
    fun `a raster is not drawn over a boundary it was not measured for`() = runTest(dispatcher) {
        val r = Rig(this, SlopeServer())
        advanceUntilIdle()
        val id = r.analysed()
        r.session.open(id)
        advanceUntilIdle()
        assertTrue(r.model.scene.value.slope != null)

        r.server.reachable = false                                                           // the next measurement will fail
        r.session.edit("Boundary") { it.copy(analysis = it.analysis.copy(detectedLZ = boundary(34.7 to -84.1, 34.7 to -84.0, 34.8 to -84.0))) }
        advanceUntilIdle()
        assertEquals(null, r.model.scene.value.slope)                                        // the old picture is gone, not left lying
        assertEquals(3, r.model.scene.value.boundary.size)
    }

    @Test
    fun `with no signal the diagram is drawn without a raster, and a diagram not yet analysed asks for none`() = runTest(dispatcher) {
        val r = Rig(this, SlopeServer(reachable = false))
        advanceUntilIdle()
        val id = r.analysed()
        r.session.open(id)
        advanceUntilIdle()
        assertEquals(null, r.model.scene.value.slope)
        assertEquals(LatLon(34.783817, -84.08219), r.model.scene.value.target)

        val plain = r.repository.create(target, "targeted only").id
        r.session.open(plain)
        advanceUntilIdle()
        assertEquals(1, r.server.slopeCalls.size)                                            // only the first diagram's
        assertEquals(DiagramStatus.TARGETED, r.session.active.value!!.status)
        assertEquals(LzScene.of(r.session.active.value).copy(), r.model.scene.value)
    }

    // -- Planning graphics on the map ----------------------------------------------------------------------------------

    private fun pzMarker(id: String) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "lat" to JsonPrimitive(34.7838), "lon" to JsonPrimitive(-84.0822),
            "tipLat" to JsonPrimitive(34.7838), "tipLon" to JsonPrimitive(-84.0832),
        ),
    )

    @Test
    fun `graphics placed on the open diagram are drawn, and the one that is held has a halo`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val id = r.analysed()
        r.session.open(id)
        advanceUntilIdle()
        // An analysis makes the standard SP and RP doghouses, so the scene is not bare; nothing has been placed yet.
        assertEquals(2, r.model.scene.value.graphics.doghouses.size)
        assertTrue(r.model.scene.value.graphics.pzMarkers.isEmpty() && r.model.scene.value.graphics.aircraft.isEmpty())

        r.session.edit("Place PZ marker") { DiagramOps.upsertGraphic(it, "pzMarkers", pzMarker("pz-1")) }
        advanceUntilIdle()
        assertEquals(1, r.model.scene.value.graphics.pzMarkers.size)
        assertEquals(null, r.model.scene.value.graphics.selectedAt)

        r.selection.select(GraphicRef("pzMarkers", "pz-1"))
        advanceUntilIdle()
        assertEquals(LatLon(34.7838, -84.0822), r.model.scene.value.graphics.selectedAt)

        r.selection.clear()
        advanceUntilIdle()
        assertEquals(null, r.model.scene.value.graphics.selectedAt)
    }

    @Test
    fun `opening another diagram lets go of what was held in the first`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val first = r.analysed()
        r.session.open(first)
        advanceUntilIdle()                                                                    // opening is heard before the person can hold anything
        r.session.edit("Place PZ marker") { DiagramOps.upsertGraphic(it, "pzMarkers", pzMarker("pz-1")) }
        r.selection.select(GraphicRef("pzMarkers", "pz-1"))
        advanceUntilIdle()
        assertEquals(GraphicRef("pzMarkers", "pz-1"), r.selection.selected.value)

        val second = r.repository.create(DiagramTarget(35.0, -85.0, "16S FD 00000 00000"), "B").id
        r.session.open(second)
        advanceUntilIdle()
        assertEquals(null, r.selection.selected.value)
    }

    @Test
    fun `editing the open diagram keeps what is held`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        r.session.edit("Place PZ marker") { DiagramOps.upsertGraphic(it, "pzMarkers", pzMarker("pz-1")) }
        r.selection.select(GraphicRef("pzMarkers", "pz-1"))
        advanceUntilIdle()
        r.session.edit("Move") { DiagramOps.patchGraphic(it, "pzMarkers", JsonPrimitive("pz-1"), JsonObject(mapOf("lat" to JsonPrimitive(34.79)))) }
        advanceUntilIdle()
        assertEquals(GraphicRef("pzMarkers", "pz-1"), r.selection.selected.value)
    }

    // -- Tapping the map -----------------------------------------------------------------------------------------------

    private fun view() = MapProjection(CameraState(LatLon(34.7838, -84.0822), 18.0), 0.0, 0.0, 1.0)

    @Test
    fun `a tap on a graphic holds it, a tap on another changes to it, and a tap on nothing puts it down`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        r.session.edit("Place PZ marker") { DiagramOps.upsertGraphic(it, "pzMarkers", pzMarker("pz-1")) }
        r.session.edit("Place PZ marker") {
            DiagramOps.upsertGraphic(it, "pzMarkers", JsonObject(pzMarker("pz-2") + mapOf("lat" to JsonPrimitive(34.7848), "tipLat" to JsonPrimitive(34.7848))))
        }
        advanceUntilIdle()

        r.model.mapTapped(LatLon(34.7838, -84.0822), view(), touchRadiusPx = 24.0)
        assertEquals(GraphicRef("pzMarkers", "pz-1"), r.selection.selected.value)

        r.model.mapTapped(LatLon(34.7848, -84.0822), view(), touchRadiusPx = 24.0)               // the other, about 110 m north
        assertEquals(GraphicRef("pzMarkers", "pz-2"), r.selection.selected.value)

        r.model.mapTapped(LatLon(34.7800, -84.0900), view(), touchRadiusPx = 24.0)               // empty ground
        assertEquals(null, r.selection.selected.value)
    }

    @Test
    fun `tapping the map with nothing drawn changes nothing`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.model.mapTapped(LatLon(34.78, -84.08), view(), touchRadiusPx = 24.0)
        assertEquals(null, r.selection.selected.value)
    }
    // -- Drawing a boundary -----------------------------------------------------------------------------------------------

    @Test
    fun `while a boundary is being drawn a tap is a corner of it, wherever it falls`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        r.drawing.start()
        r.model.mapTapped(LatLon(34.7800, -84.0900), view(), touchRadiusPx = 24.0)
        r.model.mapTapped(LatLon(34.7900, -84.0700), view(), touchRadiusPx = 24.0)
        assertEquals(listOf(LatLon(34.78, -84.09), LatLon(34.79, -84.07)), r.drawing.draft.value!!.points)
    }

    @Test
    fun `a tap on a graphic while drawing is a corner and does not hold the graphic`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        r.session.edit("Place PZ marker") { DiagramOps.upsertGraphic(it, "pzMarkers", pzMarker("pz-1")) }
        advanceUntilIdle()
        r.drawing.start()
        r.model.mapTapped(LatLon(34.7838, -84.0822), view(), touchRadiusPx = 24.0)                  // right on the PZ marker
        assertEquals(null, r.selection.selected.value)
        assertEquals(1, r.drawing.draft.value!!.points.size)
        r.drawing.cancel()
        r.model.mapTapped(LatLon(34.7838, -84.0822), view(), touchRadiusPx = 24.0)                  // and once drawing is over, it is a tap on a graphic again
        assertEquals(GraphicRef("pzMarkers", "pz-1"), r.selection.selected.value)
    }

    @Test
    fun `the map draws the corners put down on the diagram they belong to`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        assertEquals(emptyList<LatLon>(), r.model.scene.value.draft)
        r.drawing.start()
        r.drawing.addPoint(LatLon(34.78, -84.09))
        advanceUntilIdle()
        assertEquals(listOf(LatLon(34.78, -84.09)), r.model.scene.value.draft)
    }

    @Test
    fun `the sheet is told when drawing starts and stops`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        assertEquals(false, r.model.isDrawing.value)
        r.drawing.start()
        advanceUntilIdle()
        assertEquals(true, r.model.isDrawing.value)
        r.drawing.cancel()
        advanceUntilIdle()
        assertEquals(false, r.model.isDrawing.value)
    }

    @Test
    fun `the map never draws one diagram's corners on another, not even for the moment before they are dropped`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        r.drawing.start()
        r.drawing.addPoint(LatLon(34.78, -84.09))
        advanceUntilIdle()
        val seen = mutableListOf<LzScene>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.scene.collect { seen += it } }
        val other = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.session.open(other.id)
        advanceUntilIdle()
        val onOther = seen.filter { it.target == LatLon(35.0, -85.0) }
        assertTrue("the other diagram was shown", onOther.isNotEmpty())
        assertTrue("no corner of the first was drawn on it", onOther.all { it.draft.isEmpty() })
    }

    @Test
    fun `opening another diagram drops a boundary half drawn`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val first = r.analysed()
        r.session.open(first)
        advanceUntilIdle()
        r.drawing.start()
        r.drawing.addPoint(LatLon(34.78, -84.09))
        advanceUntilIdle()
        val other = r.repository.create(DiagramTarget(35.0, -85.0, "16S GD 1 1"), "LZ CROW")
        r.session.open(other.id)
        advanceUntilIdle()
        assertEquals(null, r.drawing.draft.value)
        assertEquals(emptyList<LatLon>(), r.model.scene.value.draft)
    }

// -- Aircraft on the map -----------------------------------------------------------------------------------------------

    private fun helo(id: Int, profile: String) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "lat" to JsonPrimitive(34.7838), "lon" to JsonPrimitive(-84.0822), "rotation" to JsonPrimitive(0),
            "type" to JsonPrimitive("helo"), "profileId" to JsonPrimitive(profile),
        ),
    )

    @Test
    fun `each aircraft is drawn as the airframe it was placed as, and one this device does not know as the mission aircraft`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.session.open(r.analysed())
        advanceUntilIdle()
        r.session.edit("Place") { DiagramOps.upsertGraphic(it, "helicopters", helo(1, "ch47f")) }
        r.session.edit("Place") { DiagramOps.upsertGraphic(it, "helicopters", helo(2, "uh60l")) }
        r.session.edit("Place") { DiagramOps.upsertGraphic(it, "helicopters", helo(3, "not-in-the-list")) }
        advanceUntilIdle()
        assertEquals(listOf("CH-47F", "UH-60L", "UH-60L"), r.model.scene.value.graphics.aircraft.map { it.designation })   // the UH-60L is the mission aircraft
        assertEquals(18.29, r.model.scene.value.graphics.aircraft[0].diameterM, 1e-9)

        r.aircraft.select("ch47f")
        advanceUntilIdle()
        assertEquals(listOf("CH-47F", "UH-60L", "CH-47F"), r.model.scene.value.graphics.aircraft.map { it.designation })   // and follows the choice
    }
}
