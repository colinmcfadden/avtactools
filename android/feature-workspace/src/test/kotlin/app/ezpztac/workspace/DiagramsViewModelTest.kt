package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.InMemoryAircraftChoice
import app.ezpztac.data.InMemoryMasterProfileStore
import app.ezpztac.data.InMemoryOpenDiagramStore
import app.ezpztac.data.InMemorySlopeOpacity
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.PackFacts
import app.ezpztac.data.SlopeVisibility
import app.ezpztac.data.TerrainApi
import app.ezpztac.data.WeatherApi
import app.ezpztac.data.WeatherCache
import app.ezpztac.data.WeatherService
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.model.WeatherSnapshot
import app.ezpztac.network.ApiException
import app.ezpztac.network.DirectionalSlope
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.HeadingSlope
import app.ezpztac.network.ProfileLine
import app.ezpztac.network.SlopeProfiles
import app.ezpztac.network.SlopeStats
import app.ezpztac.network.SlopeThresholds
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.network.Uh60Limits
import app.ezpztac.network.WeatherReportDto
import app.ezpztac.planning.LzSummary
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagramsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val grid = "16S GD 66993 52949"

    private companion object {
        /** The table by heading of a plane of [deg] rising toward [toward], as the server rounds it. */
        fun plane(deg: Double, toward: Int): List<HeadingSlope> = (0 until 360).map { h ->
            val along = deg * Math.cos(Math.toRadians((h - toward).toDouble()))
            fun tenth(v: Double) = Math.round(v * 10) / 10.0
            HeadingSlope(h, tenth(maxOf(along, 0.0)), tenth(maxOf(-along, 0.0)), tenth(Math.abs(deg * Math.sin(Math.toRadians((h - toward).toDouble())))))
        }

        /** The ground along a line every 2° through the LZ's middle, as the server sends it: a plane of [deg] rising toward [toward], a 200 m square LZ. */
        fun profiles(deg: Double, toward: Int) = SlopeProfiles(
            center = listOf(34.7, -84.1), stepM = 30.0, halfLengthM = 300.0, headingStepDeg = 2,
            lines = (0 until 180 step 2).map { h ->
                val r = 100 / maxOf(Math.abs(Math.cos(Math.toRadians(h.toDouble()))), Math.abs(Math.sin(Math.toRadians(h.toDouble()))))
                ProfileLine(
                    h,
                    (0..20).map { i -> (1200 + (i * 30 - 300) * Math.cos(Math.toRadians((h - toward).toDouble())) * Math.tan(Math.toRadians(deg))) * 3.28084 },
                    listOf(-r, r),
                )
            },
        )

        fun slope(maxDeg: Double, directional: DirectionalSlope? = null, byHeading: List<HeadingSlope>? = null, profiles: SlopeProfiles? = null) = TerrainAnalysis(
            status = "success", overlay = "data:image/png;base64,AA==", bounds = listOf(listOf(34.7, -84.1), listOf(34.8, -84.0)),
            source = "local_highres_cog", resolutionM = 10.2, verticalDatum = "NAVD88",
            stats = SlopeStats(maxDeg, maxDeg - 1, 0.0, 0.0, 0.0, 900, 94_000.0), directional = directional, byHeading = byHeading, profiles = profiles,
            thresholds = SlopeThresholds(listOf(3.0, 6.0, 10.0, 15.0), Uh60Limits(6.0, 15.0, 15.0)),
        )
    }

    /** A terrain server whose answers a test chooses, and which can be held back until the test lets it go. */
    private class Terrain : TerrainApi {
        var find: suspend (LatLon) -> FieldAnalysis = { FieldAnalysis("success", listOf(listOf(34.71, -84.09), listOf(34.71, -84.01), listOf(34.79, -84.01), listOf(34.79, -84.09)), "4050", "Field detected") }
        var measure: suspend (List<LatLon>) -> TerrainAnalysis = { slope(4.2) }
        var measured = 0
        override suspend fun analyzeField(at: LatLon): FieldAnalysis = find(at)
        override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis {
            measured++
            return measure(polygon)
        }
    }

    /** A weather service whose answers a test chooses; `answer` throws to make a failure. */
    private class Weather : WeatherApi {
        val calls = mutableListOf<LatLon>()
        var answer: suspend (LatLon) -> WeatherReportDto = {
            WeatherReportDto(
                "KRYY", "Cobb County Airport", distanceMiles = JsonPrimitive(0.7), windDir = JsonPrimitive(310), windSpeedKts = JsonPrimitive(12),
                windGustKts = JsonPrimitive(20), tempC = JsonPrimitive(18.5), pressure = JsonPrimitive(29.92), flightCategory = "VFR", notams = buildJsonObject {},
            )
        }
        override suspend fun weather(at: LatLon): WeatherReportDto {
            calls += at
            return answer(at)
        }
    }

    private class MemoryWeatherCache : WeatherCache {
        var kept: Map<String, WeatherSnapshot> = emptyMap()
        override fun load() = kept
        override fun save(snapshots: Map<String, WeatherSnapshot>) { kept = snapshots }
    }

    /** One device with the real repository, session and engine over an in-memory store; only the servers are stand-ins. */
    private class Rig(
        scope: TestScope, label: String = "A", val server: FakeServer = FakeServer(), realEngine: Boolean = false, resolver: ConflictResolver? = null,
        masterList: List<AircraftProfile> = emptyList(),
        val pack: FakeOpenPack = FakeOpenPack(),
    ) {
        val terrain = Terrain()
        val device = Device(label, server)
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = DiagramSession(repository, scope.backgroundScope, pack.lzStore)
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        // Not backgroundScope: advanceUntilIdle leaves a background scope's work alone, and an analysis is work a test waits for.
        val analysis = AnalysisService(
            terrain, session, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler),
        )
        val choice = InMemoryAircraftChoice()
        val master = InMemoryMasterProfileStore(masterList)
        val aircraft = AircraftProfiles(device.store as InMemorySyncStore, master, { emptyList() }, choice, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, scheduler)
        val weatherApi = Weather()
        var clock = 1_000_000L
        val weather = WeatherService(weatherApi, MemoryWeatherCache(), CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))) { clock }
        val openStore = InMemoryOpenDiagramStore()
        val open = OpenDiagrams(openStore)
        val slopeVisibility = SlopeVisibility(InMemorySlopeOpacity())
        val fresh = app.ezpztac.data.FreshDiagrams()
        val model = DiagramsViewModel(
            repository, session,
            resolver ?: if (realEngine) device.engine else ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) },
            analysis, aircraft, weather, open, slopeVisibility, pack, fresh,
        )
        val rows get() = model.state.value.rows

        init {
            pack.opener = { session.open(it) }
            pack.closer = { session.closeIfOpen(it) }
        }
    }

    private fun TestScope.rig(resolver: ConflictResolver? = null) = Rig(this, resolver = resolver).also { advanceUntilIdle() }

    // -- The list ------------------------------------------------------------------------------------------

    @Test
    fun `with nothing made the list is empty`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertFalse(r.model.state.value.creating)
        assertNull(r.model.state.value.error)
    }

    @Test
    fun `a diagram made elsewhere appears without asking`() = runTest(dispatcher) {
        val r = rig()
        r.repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
        advanceUntilIdle()
        assertEquals(listOf("LZ HAWK"), r.rows.map { it.name })
        assertEquals(grid, r.rows.single().grid)
        assertEquals(SyncStatus.PENDING, r.rows.single().sync)
    }

    // -- Making one ----------------------------------------------------------------------------------------

    @Test
    fun `a grid makes a diagram, named for the grid, and opens it`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        r.model.create("", grid)
        advanceUntilIdle()
        val row = r.rows.single()
        assertEquals("LZ $grid", row.name)
        assertEquals(grid, row.grid)
        assertEquals(DiagramStatus.TARGETED, row.status)
        assertTrue(row.isActive)
        assertEquals(row.uuid, r.session.active.value?.id)
        assertFalse(r.model.state.value.creating)                                           // the form closes once it worked
        assertTrue(r.scheduler.requested > 0)                                               // and the server is to be told
    }

    @Test
    fun `a typed name is kept, trimmed`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("  LZ HAWK  ", grid)
        advanceUntilIdle()
        assertEquals("LZ HAWK", r.rows.single().name)
    }

    @Test
    fun `a latitude and longitude work as well as a grid, and give the grid`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("", "34.783817, -84.08219")
        advanceUntilIdle()
        assertEquals(grid, r.rows.single().grid)
        assertEquals(34.783817, r.session.active.value!!.target!!.lat, 1e-5)
    }

    @Test
    fun `a target that is not understood says so and makes nothing`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        r.model.create("LZ X", "somewhere nice")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        assertTrue(r.model.state.value.creating)                                            // the form stays, with what was typed
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertNull(r.session.active.value)
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `pressing create twice makes one`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        r.model.create("two", grid)                                                         // a double tap, before the first finished
        advanceUntilIdle()
        assertEquals(listOf("one"), r.rows.map { it.name })
        assertFalse(r.model.state.value.busy)
    }

    @Test
    fun `starting, cancelling and dismissing are only about the form and the message`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        advanceUntilIdle()
        assertTrue(r.model.state.value.creating)
        r.model.create("", "nonsense")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        r.model.dismissError()
        advanceUntilIdle()
        assertNull(r.model.state.value.error)
        assertTrue(r.model.state.value.creating)
        r.model.cancelCreating()
        advanceUntilIdle()
        assertFalse(r.model.state.value.creating)
    }

    // -- Opening -------------------------------------------------------------------------------------------

    @Test
    fun `opening another diagram makes it the active one`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val first = r.rows.single().uuid
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        val second = r.rows.first { it.name == "two" }.uuid
        assertEquals(second, r.session.active.value?.id)
        r.model.open(first)
        advanceUntilIdle()
        assertEquals(first, r.session.active.value?.id)
        assertEquals(listOf(first), r.rows.filter { it.isActive }.map { it.uuid })          // exactly one is marked
    }

    @Test
    fun `opening the open diagram does not drop its unsaved edits`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.session.edit("Rename") { it.copy(name = "typed on the map") }
        r.model.open(uuid)
        advanceUntilIdle()
        assertEquals("typed on the map", r.session.active.value?.name)
        assertEquals(1, r.session.undoDepth.value)                                          // not reopened: what can be undone still can
    }

    @Test
    fun `a diagram that is gone says so`() = runTest(dispatcher) {
        val r = rig()
        r.model.open("never-here")
        advanceUntilIdle()
        assertEquals("That diagram is no longer here.", r.model.state.value.error)
        assertNull(r.session.active.value)
    }

    // -- Renaming ------------------------------------------------------------------------------------------

    @Test
    fun `renaming a diagram that is not open changes its record`() = runTest(dispatcher) {
        val r = rig()
        r.repository.create(DiagramTarget(34.783817, -84.08219, grid), "old")
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.model.rename(uuid, "  new  ")
        advanceUntilIdle()
        assertEquals("new", r.rows.single().name)
        assertEquals("new", r.repository.open(uuid)!!.name)
    }

    @Test
    fun `renaming the open diagram goes through the session, so unsaved edits are not lost`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("old", grid)
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.session.edit("Move") { it.copy(mapData = JsonObject(mapOf("zoom" to JsonPrimitive(15)))) }
        r.model.rename(uuid, "new")
        advanceUntilIdle()
        val saved = r.repository.open(uuid)!!
        assertEquals("new", saved.name)
        assertEquals(JsonPrimitive(15), saved.mapData["zoom"])                              // the earlier, unsaved edit was written with it
        assertEquals("new", r.session.active.value?.name)
        assertEquals(2, r.session.undoDepth.value)                                          // and both can still be undone
    }

    @Test
    fun `a blank name is refused and nothing changes`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("keep", grid)
        advanceUntilIdle()
        r.model.rename(r.rows.single().uuid, "   ")
        advanceUntilIdle()
        assertEquals("A diagram needs a name.", r.model.state.value.error)
        assertEquals("keep", r.rows.single().name)
    }

    // -- Deleting ------------------------------------------------------------------------------------------

    @Test
    fun `deleting the open diagram closes it`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        r.model.delete(r.rows.single().uuid)
        advanceUntilIdle()
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertNull(r.session.active.value)
    }

    @Test
    fun `deleting another diagram leaves the open one open`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val one = r.rows.single().uuid
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        r.model.delete(one)
        advanceUntilIdle()
        assertEquals(listOf("two"), r.rows.map { it.name })
        assertEquals("two", r.session.active.value?.name)
    }

    @Test
    fun `a delete is told to the server, not only forgotten here`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        r.device.sync()
        val before = r.scheduler.requested
        r.model.delete(r.rows.single().uuid)
        advanceUntilIdle()
        assertTrue(r.scheduler.requested > before)
        r.device.sync()
        assertEquals(0, r.server.live(RecordKind.LZ).size)
    }

    // -- Conflicts -----------------------------------------------------------------------------------------

    /** A conflict made the real way: two devices change the one diagram, and the second to sync is the one that finds out. */
    private suspend fun TestScope.conflicted(realEngine: Boolean = false): Rig {
        val server = FakeServer()
        val a = Rig(this, "A", server)
        advanceUntilIdle()
        val made = a.repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
        a.device.sync()
        val b = Rig(this, "B", server, realEngine)
        b.device.sync()
        a.repository.rename(made.id, "LZ CROW")
        b.repository.rename(made.id, "LZ EAGLE")
        a.device.sync()
        b.device.sync()
        advanceUntilIdle()
        return b
    }

    @Test
    fun `a conflict copy is marked, so the screen can offer the choice`() = runTest(dispatcher) {
        val b = conflicted()
        val copy = b.rows.single { it.conflictOf != null }
        val original = b.rows.single { it.conflictOf == null }
        assertEquals("LZ CROW", original.name)                                              // the other device's, as the server has it
        assertTrue(copy.name.startsWith("LZ EAGLE"))                                        // this device's, kept
        assertEquals(original.uuid, copy.conflictOf)
    }

    @Test
    fun `settling a conflict asks the resolver for exactly that copy and choice`() = runTest(dispatcher) {
        val b = conflicted()
        val copy = b.rows.single { it.conflictOf != null }
        b.model.resolve(copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)
        advanceUntilIdle()
        assertEquals(listOf(Triple(RecordKind.LZ, copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)), b.resolutions)
    }

    @Test
    fun `keeping theirs through the real engine removes the copy`() = runTest(dispatcher) {
        val b = conflicted(realEngine = true)
        val copy = b.rows.single { it.conflictOf != null }
        b.model.resolve(copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)
        advanceUntilIdle()
        assertEquals(listOf("LZ CROW"), b.rows.map { it.name })
    }

    // -- Failures ------------------------------------------------------------------------------------------

    @Test
    fun `a failure shows its own message, and the screen is usable again`() = runTest(dispatcher) {
        val r = rig(resolver = ConflictResolver { _, _, _ -> error("the server said no") })
        r.model.resolve("x", SyncEngine.Resolution.KEEP_MINE)
        advanceUntilIdle()
        assertEquals("the server said no", r.model.state.value.error)
        assertFalse(r.model.state.value.busy)
    }

    @Test
    fun `a failure with no message gets one in words`() = runTest(dispatcher) {
        val r = rig(resolver = ConflictResolver { _, _, _ -> throw IllegalStateException() })
        r.model.resolve("x", SyncEngine.Resolution.KEEP_MINE)
        advanceUntilIdle()
        assertEquals("The conflict could not be settled.", r.model.state.value.error)
    }

    @Test
    fun `a new action clears the last error`() = runTest(dispatcher) {
        val r = rig()
        r.model.open("never-here")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        r.model.create("fine", grid)
        advanceUntilIdle()
        assertNull(r.model.state.value.error)
    }

    // -- Analysis --------------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing open there is no open diagram to show`() = runTest(dispatcher) {
        val r = rig()
        assertNull(r.model.state.value.current)
    }

    @Test
    fun `the open diagram is offered for analysis, with no summary yet`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        val current = r.model.state.value.current!!
        assertEquals("LZ HAWK", current.name)
        assertEquals(grid, current.grid)
        assertEquals(DiagramStatus.TARGETED, current.status)
        assertTrue(current.canAnalyze)
        assertEquals(AnalysisUi.Idle, current.analysis)
        assertNull(current.summary)
    }

    @Test
    fun `analysing shows each stage, then the summary`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slopeGate = CompletableDeferred<Unit>()
        val r = rig()
        r.terrain.find = { gate.await(); FieldAnalysis("success", listOf(listOf(34.7800, -84.0900), listOf(34.7800, -84.0866), listOf(34.7777, -84.0866), listOf(34.7777, -84.0900)), "4050", "Field detected") }
        r.terrain.measure = { slopeGate.await(); slope(4.2) }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()

        r.model.analyze()
        advanceUntilIdle()
        assertEquals(AnalysisUi.Running(AnalysisStatus.Running.Stage.FINDING_AREA), r.model.state.value.current!!.analysis)

        gate.complete(Unit)
        advanceUntilIdle()
        val measuring = r.model.state.value.current!!
        assertEquals(AnalysisUi.Running(AnalysisStatus.Running.Stage.MEASURING_SLOPE), measuring.analysis)
        assertEquals(DiagramStatus.ANALYZED, measuring.status)
        val tiles = measuring.summary!!                                                       // the tiles that need no server are already there
        assertEquals(SlopeTileUi.Measuring, tiles.slope)
        assertEquals(13, tiles.capacity)
        assertEquals(854_831L, tiles.areaSqFt)
        assertEquals("UH-60L", tiles.aircraft)
        assertEquals("4050", tiles.elevation)

        slopeGate.complete(Unit)
        advanceUntilIdle()
        val done = r.model.state.value.current!!
        assertEquals(AnalysisUi.Idle, done.analysis)
        val heading = "Heading limits not checked (server not updated)"                       // slope(4.2) is an older server's: no table by heading
        assertEquals(SlopeTileUi.Measured(LzSummary.SlopeCall(LzSummary.SlopeLevel.SAFE, "LANDING", 4.2, lines = listOf(heading)), "local_highres_cog", 10.2), done.summary!!.slope)
    }

    @Test
    fun `the slope profile follows the landing heading, along and across it`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { slope(5.0, byHeading = plane(5.0, toward = 90), profiles = profiles(5.0, toward = 90)) }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        fun profile() = r.model.state.value.current!!.summary!!.profile
        // Landing at 000°, the standard doghouse's: across the slope, which rises to the right (east).
        assertEquals("Along 000°: level through the LZ", profile()!!.along.words)
        assertEquals("Across, left to right: rises 57 ft through the LZ, 5.0° on average", profile()!!.across.words)
        val asked = r.terrain.measured
        r.session.edit("Heading") { it.copy(flightData = JsonObject(it.flightData + ("landing_hdg" to JsonPrimitive("270°")))) }
        advanceUntilIdle()
        assertEquals("Along 270°: falls 57 ft through the LZ, 5.0° on average", profile()!!.along.words)
        assertEquals(asked, r.terrain.measured)                                                 // no new request
        r.session.edit("Heading") { it.copy(flightData = JsonObject(it.flightData + ("landing_hdg" to JsonPrimitive("RWY")))) }
        advanceUntilIdle()
        assertNull(profile())                                                                   // no landing heading, no profile
    }

    @Test
    fun `a server that sends no profile shows none`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { slope(5.0, byHeading = plane(5.0, toward = 90)) }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        assertNull(r.model.state.value.current!!.summary!!.profile)
    }

    @Test
    fun `an older server's steep slope asks for a heading, and says no heading was judged`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { slope(16.0) }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val call = (r.model.state.value.current!!.summary!!.slope as SlopeTileUi.Measured).call
        assertEquals("HEADING REQUIRED", call.label)
        assertEquals(LzSummary.SlopeLevel.WARNING, call.level)
        assertEquals(listOf(LzSummary.HEADINGS_NOT_CHECKED), call.lines)
    }

    @Test
    fun `the slope is judged at the landing heading in the wind the weather tiles show, again as either changes`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { slope(8.0, byHeading = plane(8.0, toward = 90)) }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        fun call() = (r.model.state.value.current!!.summary!!.slope as SlopeTileUi.Measured).call
        // The analysis makes the standard doghouses, landing at 000°: across an 8° slope, within 15°. No report yet, so no wind is counted.
        assertEquals("LANDING", call().label)
        assertEquals(0, call().heading)
        assertEquals(listOf("Landing 000°: within limits", "No wind report: limits not reduced for wind"), call().lines)

        // Turned to land down the slope: 8° nose down is over the 6° limit. Judged at once, with no request.
        val asked = r.terrain.measured
        r.session.edit("Heading") { it.copy(flightData = JsonObject(it.flightData + ("landing_hdg" to JsonPrimitive("270°")))) }
        advanceUntilIdle()
        assertEquals("LIMIT EXCEEDED", call().label)
        assertEquals(LzSummary.SlopeLevel.DANGER, call().level)
        assertEquals(270, call().heading)
        assertEquals("Landing 270°: nose down 8.0°, limit 6°", call().lines.first())
        assertEquals(asked, r.terrain.measured)

        // A report comes: 12 kt from 310 takes 4° off each limit, 2° left nose down.
        r.model.refreshWeather()
        advanceUntilIdle()
        assertEquals(listOf("Landing 270°: nose down 8.0°, limit 2°", "Wind 12 kt: limits \u22124°"), call().lines)
        assertEquals(LzSummary.SlopeLimits(11, 2, 11, 4, false), call().limits)
        assertEquals(asked, r.terrain.measured)
    }

    @Test
    fun `a slope that cannot be measured is said in words, and the rest of the summary stands`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { throw ApiException(502, null, "No configured terrain source covers this LZ") }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val summary = r.model.state.value.current!!.summary!!
        assertEquals(SlopeTileUi.Unavailable("No terrain data covers this landing zone."), summary.slope)
        assertTrue(summary.capacity > 0)
    }

    @Test
    fun `a failure is shown on the diagram it was for, in words`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.find = { throw ApiException(400, null, "No distinct field found at this point") }
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val current = r.model.state.value.current!!
        assertEquals(AnalysisUi.Failed("No distinct landing area was found at this point. Try a different target."), current.analysis)
        assertEquals(DiagramStatus.TARGETED, current.status)
        assertNull(current.summary)

        r.model.dismissAnalysisError()
        advanceUntilIdle()
        assertEquals(AnalysisUi.Idle, r.model.state.value.current!!.analysis)
    }

    @Test
    fun `an analysis of one diagram is not shown on another`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val r = rig()
        r.terrain.find = { gate.await(); FieldAnalysis("success", listOf(listOf(34.71, -84.09), listOf(34.71, -84.01), listOf(34.79, -84.01)), "4050", "Field detected") }
        r.model.create("one", grid)
        advanceUntilIdle()
        val first = r.rows.single().uuid
        r.model.analyze()
        advanceUntilIdle()
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        assertEquals("two", r.model.state.value.current!!.name)
        assertEquals(AnalysisUi.Idle, r.model.state.value.current!!.analysis)             // the server is busy with "one", not with this one
        r.model.open(first)
        advanceUntilIdle()
        assertEquals(AnalysisUi.Running(AnalysisStatus.Running.Stage.FINDING_AREA), r.model.state.value.current!!.analysis)
        r.model.stopAnalysis()
        advanceUntilIdle()
        assertEquals(AnalysisUi.Idle, r.model.state.value.current!!.analysis)
    }

    @Test
    fun `a slope measured for another boundary is not shown for this one`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        assertTrue((r.model.state.value.current!!.summary!!.slope) is SlopeTileUi.Measured)

        val moved = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0), listOf(34.8, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.session.edit("Boundary") { it.copy(analysis = it.analysis.copy(detectedLZ = moved)) }
        advanceUntilIdle()
        assertEquals(SlopeTileUi.Measuring, r.model.state.value.current!!.summary!!.slope)      // the old numbers would be about ground that is no longer outlined
    }

    @Test
    fun `a diagram marked analysed with no boundary has no summary to show`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.session.edit("Odd") { it.copy(status = DiagramStatus.ANALYZED) }
        advanceUntilIdle()
        assertNull(r.model.state.value.current!!.summary)
    }

    @Test
    fun `a boundary on a diagram that is not analysed is not a summary`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        val drawn = JsonArray(listOf(listOf(34.7, -84.1), listOf(34.7, -84.0), listOf(34.8, -84.0)).map { p -> JsonArray(p.map(::JsonPrimitive)) })
        r.session.edit("Boundary") { it.copy(analysis = it.analysis.copy(detectedLZ = drawn)) }      // status is still targeted
        advanceUntilIdle()
        assertEquals(DiagramStatus.TARGETED, r.model.state.value.current!!.status)
        assertNull(r.model.state.value.current!!.summary)
    }

    @Test
    fun `a failure of one diagram's analysis is not shown on the next one opened`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.find = { throw ApiException(500, null, "boom") }
        r.model.create("one", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        assertTrue(r.model.state.value.current!!.analysis is AnalysisUi.Failed)
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        assertEquals(AnalysisUi.Idle, r.model.state.value.current!!.analysis)
    }
// -- The mission aircraft ----------------------------------------------------------------------------------------------

    private val blackHawk = AircraftProfile(id = 1, slug = "uh60l", name = "UH-60L Black Hawk", designation = "UH-60L")
    private val chinook = AircraftProfile(id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0, defaultAirspeedKts = 130.0, perfSource = "published")
    private val littleBird = AircraftProfile(id = 3, slug = "mh6", name = "MH-6 Little Bird", designation = "MH-6", iconKey = "mh6", rotorDiameterM = 8.33, rotorTipClearanceM = 30.0, defaultAirspeedKts = 110.0)

    private fun TestScope.rigWithAircraft() = Rig(this, masterList = listOf(blackHawk, chinook, littleBird)).also { advanceUntilIdle() }

    @Test
    fun `before the list of airframes has loaded the UH-60L is the mission aircraft`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        val aircraft = r.model.state.value.current!!.aircraft
        assertEquals(AircraftUi.UH60L, aircraft)
        assertEquals(listOf("uh60l"), aircraft.options.map { it.slug })
    }

    @Test
    fun `the picker offers every airframe that can be chosen, as the web words them, and says which is the mission aircraft`() = runTest(dispatcher) {
        val r = rigWithAircraft()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        val aircraft = r.model.state.value.current!!.aircraft
        assertEquals(listOf("UH-60L — UH-60L Black Hawk", "CH-47F — CH-47F Chinook", "MH-6 — MH-6 Little Bird"), aircraft.options.map { it.label })
        assertEquals("uh60l", aircraft.activeSlug)
        assertEquals(76L, aircraft.spacingM)                                                // 16.357 + 60 m, rounded
        assertEquals(100L, aircraft.cruiseKts)
        assertFalse(aircraft.unverified)
    }

    @Test
    fun `choosing an airframe changes the mission aircraft, its numbers and how many fit`() = runTest(dispatcher) {
        val r = rigWithAircraft()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val blackHawkFit = r.model.state.value.current!!.summary!!.capacity
        assertEquals("UH-60L", r.model.state.value.current!!.summary!!.aircraft)

        r.model.selectAircraft("ch47f")
        advanceUntilIdle()
        val current = r.model.state.value.current!!
        assertEquals("ch47f", current.aircraft.activeSlug)
        assertEquals(93L, current.aircraft.spacingM)                                        // 18.29 + 75 m
        assertEquals(130L, current.aircraft.cruiseKts)
        assertEquals("CH-47F", current.summary!!.aircraft)
        assertTrue("a bigger aircraft with more clearance fits fewer: $blackHawkFit then ${current.summary.capacity}", current.summary.capacity < blackHawkFit)

        r.model.selectAircraft("mh6")
        advanceUntilIdle()
        assertTrue(r.model.state.value.current!!.summary!!.capacity > blackHawkFit)         // a smaller one fits more
    }

    @Test
    fun `an airframe whose numbers are only a spec sheet's is flagged while it is the mission aircraft`() = runTest(dispatcher) {
        val r = rigWithAircraft()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.selectAircraft("ch47f")
        advanceUntilIdle()
        assertTrue(r.model.state.value.current!!.aircraft.unverified)
        r.model.selectAircraft("mh6")
        advanceUntilIdle()
        assertFalse(r.model.state.value.current!!.aircraft.unverified)
    }

    @Test
    fun `an airframe nobody knows is not chosen`() = runTest(dispatcher) {
        val r = rigWithAircraft()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.selectAircraft("nonesuch")
        advanceUntilIdle()
        assertEquals("uh60l", r.model.state.value.current!!.aircraft.activeSlug)
    }

    @Test
    fun `the user's own airframes are in the list marked as theirs, and one not yet named by the server waits`() = runTest(dispatcher) {
        val r = rigWithAircraft()
        r.model.create("LZ HAWK", grid)
        r.device.repository.create(RecordKind.AIRCRAFT, "Scout", JsonObject(mapOf("designation" to JsonPrimitive("SCT"), "slug" to JsonPrimitive("sct"), "icon_key" to JsonPrimitive("generic"))))
        r.device.repository.create(RecordKind.AIRCRAFT, "Draft", JsonObject(mapOf("designation" to JsonPrimitive("DFT"))))
        advanceUntilIdle()
        val aircraft = r.model.state.value.current!!.aircraft
        assertEquals("SCT — Scout (yours)", aircraft.options.last().label)
        assertEquals(4, aircraft.options.size)
        assertEquals(listOf("Draft"), aircraft.waiting)
    }

    // -- The weather ---------------------------------------------------------------------------------------------------------

    private fun TestScope.analysed(r: Rig = rig()): Rig {
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        return r
    }

    @Test
    fun `a diagram that is not analysed shows no weather`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("LZ HAWK", grid)
        advanceUntilIdle()
        r.model.refreshWeather()
        advanceUntilIdle()
        assertNull(r.model.state.value.current!!.weather)           // the web shows the weather once there is an analysis
    }

    @Test
    fun `an analysed diagram with nothing fetched yet has no report and no age`() = runTest(dispatcher) {
        val r = analysed()
        val weather = r.model.state.value.current!!.weather!!
        assertFalse(weather.hasReport)
        assertNull(weather.fetchedAtMillis)
        assertEquals("--", weather.windSpeed)
        assertNull(weather.notams)
    }

    @Test
    fun `refreshing asks for the weather at the target and shows the report as a station would say it`() = runTest(dispatcher) {
        val r = analysed()
        r.model.refreshWeather()
        advanceUntilIdle()
        val at = r.weatherApi.calls.single()
        assertEquals(34.783, at.lat, 0.01)
        val weather = r.model.state.value.current!!.weather!!
        assertTrue(weather.hasReport)
        assertEquals("12", weather.windSpeed)
        assertEquals("G20", weather.windGust)
        assertEquals(310, weather.windFrom)
        assertEquals("18.5", weather.temp)
        assertEquals("29.92", weather.altimeter)
        assertEquals("KRYY · Cobb County Airport · 0.7 mi", weather.station)
        assertEquals("VFR", weather.category)
        assertEquals(1_000_000L, weather.fetchedAtMillis)
        assertFalse(weather.fetching)
        assertNull(weather.failure)
    }

    @Test
    fun `a failed refresh keeps the old report and says why in the app's words`() = runTest(dispatcher) {
        val r = analysed()
        r.model.refreshWeather()
        advanceUntilIdle()
        r.clock += 3_600_000L
        r.weatherApi.answer = { throw ApiException(500, null, "Traceback: KeyError 'metar'") }
        r.model.refreshWeather()
        advanceUntilIdle()
        val weather = r.model.state.value.current!!.weather!!
        assertTrue(weather.hasReport)                                   // what was known is not taken away
        assertEquals(1_000_000L, weather.fetchedAtMillis)               // and keeps its own time, so it reads as old
        val failure = weather.failure!!
        assertFalse(failure.contains("KeyError"))                       // the server's internals are never shown
        assertFalse(failure.contains("Traceback"))
    }

    @Test
    fun `a refresh asked for while one is running makes one call`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val r = analysed()
        r.weatherApi.answer = { gate.await(); WeatherReportDto("KRYY", "Cobb", notams = buildJsonObject {}) }
        r.model.refreshWeather()
        advanceUntilIdle()
        assertTrue(r.model.state.value.current!!.weather!!.fetching)
        r.model.refreshWeather()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, r.weatherApi.calls.size)
        assertFalse(r.model.state.value.current!!.weather!!.fetching)
    }

    @Test
    fun `another diagram's weather is not shown on this one`() = runTest(dispatcher) {
        val r = analysed()
        r.model.refreshWeather()
        advanceUntilIdle()
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        assertNull(r.model.state.value.current!!.weather)               // the new diagram is not analysed
        assertEquals(1, r.weatherApi.calls.size)
    }

    // -- The LZ/PZ panel: the open LZ/PZs, naming, closing (S3) ---------------------------------------------------------------

    private val here = LatLon(34.783817, -84.08219)

    @Test
    fun `an LZ-PZ just made is noted, so the bar over the map offers its first step`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)                                                                      // the map's long press, New LZ/PZ here; or the panel's
        advanceUntilIdle()
        val made = checkNotNull(r.session.active.value).id
        assertEquals(made, r.fresh.made.value)
        r.model.create("", "16S GD 66993 52949")                                                 // the form with a typed target
        advanceUntilIdle()
        assertEquals(checkNotNull(r.session.active.value).id, r.fresh.made.value)
    }

    // -- In a Mission Pack (P2b) ------------------------------------------------------------------------------------------------

    @Test
    fun `in a pack the panel is the pack's LZ-PZs, each with what it says, and the person's own are set aside`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)                                                                      // the person's own, open before the pack
        advanceUntilIdle()
        val hawk = FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))
        val crow = FakeOpenPack.lz("lz-2", "LZ CROW", DiagramTarget(34.79, -84.09, grid), source = buildJsonObject { put("original", "changed") })
        r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow), people = listOf(FakeOpenPack.here(2, "Sam Bell", "lz-1")))
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("OP DK · 2 items · live", s.pack!!.head)
        assertEquals(listOf("LZ HAWK", "LZ CROW"), s.pack.rows.map { it.name })
        assertEquals(listOf("pack:p:lz-1", "pack:p:lz-2"), s.pack.rows.map { it.uuid })
        assertEquals(listOf("Target set"), s.pack.rows[0].chips.map { it.text })                 // "Synced" is what most rows would say: left unsaid
        assertEquals(listOf("Target set", "Original changed"), s.pack.rows[1].chips.map { it.text })
        assertEquals("Sam B. has it open", s.pack.rows[0].presence)
        assertTrue("the person's own are set aside", s.open.isEmpty() && s.recent.isEmpty())
        r.pack.state.value = null                                                                 // back to the Library
        advanceUntilIdle()
        assertNull(r.model.state.value.pack)
    }

    @Test
    fun `in a pack a new LZ-PZ goes into the pack, not the Library`() = runTest(dispatcher) {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.newAt(here)
        advanceUntilIdle()
        assertEquals(listOf("createLz $grid"), r.pack.log)
        assertTrue(r.rows.isEmpty())
        assertNull(r.model.state.value.error)
    }

    @Test
    fun `a pack's LZ-PZ is opened and closed in the pack, and is removed from the Pack panel, never deleted here`() = runTest(dispatcher) {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))))
        advanceUntilIdle()
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        r.model.close("pack:p:lz-1")
        advanceUntilIdle()
        assertEquals(listOf("open lz-1", "close lz-1"), r.pack.log)
        r.model.delete("pack:p:lz-1")
        advanceUntilIdle()
        assertEquals(DiagramsViewModel.REMOVE_IN_PACK_PANEL, r.model.state.value.error)
        r.pack.opens = false
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        assertEquals("That LZ/PZ is no longer in the pack.", r.model.state.value.error)
        r.model.open("pack:q:lz-1")                                                               // another pack's: not this pack's to open
        advanceUntilIdle()
        assertEquals(listOf("open lz-1", "close lz-1", "open lz-1"), r.pack.log)
    }

    @Test
    fun `an open pack LZ-PZ is the pack's on its card, with no Save, and is not listed again under it`() = runTest(dispatcher) {
        val r = rig()
        val hawk = FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))
        val crow = FakeOpenPack.lz("lz-2", "LZ CROW", DiagramTarget(34.79, -84.09, grid))
        r.pack.state.value = FakeOpenPack.open(
            listOf(hawk, crow),
            people = listOf(FakeOpenPack.here(2, "Sam Bell", "lz-1"), FakeOpenPack.here(3, "Jess Reed", "lz-1"), FakeOpenPack.here(1, "Colin McFadden", "lz-1")),
        )
        advanceUntilIdle()
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        val s = r.model.state.value
        val current = checkNotNull(s.current)
        assertEquals("pack:p:lz-1", current.uuid)
        assertEquals(listOf("Target set", "Synced"), current.chips.map { it.text })
        assertFalse(current.canSave)
        assertFalse(current.madeUpName)
        assertNull(current.sync)
        assertEquals(InPackUi("OP DK", "Saved to OP DK as you work. 4 members can see it.", "Sam B. and 1 more are also here", false), current.inPack)
        assertEquals(listOf("pack:p:lz-2"), s.pack!!.rows.map { it.uuid })
        assertEquals(2, s.pack.count)
        assertTrue("a pack's is never one of the person's open LZ/PZs", r.open.ids.value.isEmpty())
    }

    @Test
    fun `an open pack LZ-PZ the pack's state does not list for a moment is still the pack's, never saved to the Library`() = runTest(dispatcher) {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.lz("lz-1", "LZ/PZ 1", DiagramTarget(34.78, -84.08, grid))))
        advanceUntilIdle()
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(emptyList())                                        // a client replaced after a pause, reading the device's copy
        advanceUntilIdle()
        val current = checkNotNull(r.model.state.value.current)
        assertEquals(InPackUi("OP DK", "Saved to OP DK as you work. 4 members can see it.", null, false), current.inPack)
        assertFalse(current.canSave)
        assertFalse("a made-up name is the pack's, not one to save under", current.madeUpName)
        assertEquals(listOf("Target set"), current.chips.map { it.text })
        r.pack.state.value = null                                                                 // the pack closing round it
        advanceUntilIdle()
        assertEquals("the pack", r.model.state.value.current?.inPack?.pack)
        r.model.saveAs("LZ HAWK")
        advanceUntilIdle()
        assertEquals(DiagramsViewModel.SAVED_TO_PACK, r.model.state.value.error)
        assertTrue("nothing named into the Library", r.rows.isEmpty())
        assertEquals("LZ/PZ 1", r.session.active.value?.name)
    }

    @Test
    fun `closing or deleting one of the person's own while a pack is open opens nothing else of theirs in its place`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.newAt(LatLon(34.79, -84.09))
        advanceUntilIdle()
        val (first, second) = r.open.ids.value
        assertEquals(second, r.session.active.value?.id)
        r.pack.state.value = FakeOpenPack.open(emptyList())                                        // the second left on the map in the pack (a park that failed)
        advanceUntilIdle()
        r.model.close(second)
        advanceUntilIdle()
        assertNull("the first is not brought into the pack", r.session.active.value)
        assertEquals("the first stays open, for when the pack is left", listOf(first), r.open.ids.value)
        r.pack.state.value = null
        advanceUntilIdle()
        r.model.open(first)
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.newAt(LatLon(34.80, -84.10))                                                     // in a pack: the pack's, not another of the person's
        advanceUntilIdle()
        r.model.delete(first)
        advanceUntilIdle()
        assertNull(r.session.active.value)
    }

    @Test
    fun `while a pack is open the person's own LZ-PZs are not opened, and a pack's takes any name, its own LZ-PZ n included`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val own = checkNotNull(r.session.active.value).id
        r.model.close(own)
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))))
        advanceUntilIdle()
        r.model.open(own)
        advanceUntilIdle()
        assertEquals(DiagramsViewModel.OWN_IN_PACK, r.model.state.value.error)
        assertNull(r.session.active.value)
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        r.model.rename("pack:p:lz-1", "LZ/PZ 4")
        advanceUntilIdle()
        assertNull(r.model.state.value.error)
        assertEquals("LZ/PZ 4", r.session.active.value?.name)
    }

    @Test
    fun `a pack opening or out of reach says so in place of its LZ-PZs, and a paused one is never called live`() = runTest(dispatcher) {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList(), session = null, status = PackStatus.LOADING)
        advanceUntilIdle()
        r.model.state.value.pack!!.let {
            assertEquals("Opening…", it.head)
            assertEquals("Opening the pack…", it.opening)
            assertEquals(0, it.count)
        }
        r.pack.state.value = FakeOpenPack.open(emptyList(), session = null, status = PackStatus.ERROR)
        advanceUntilIdle()
        r.model.state.value.pack!!.let {
            assertEquals("Cannot reach the pack", it.head)
            assertEquals(DiagramsViewModel.PACK_UNREACHABLE, it.opening)
        }
        r.pack.state.value = FakeOpenPack.open(emptyList(), status = PackStatus.PAUSED)
        advanceUntilIdle()
        assertEquals("OP DK · 0 items · paused", r.model.state.value.pack!!.head)
        assertNull(r.model.state.value.pack!!.opening)
    }

    @Test
    fun `a pack's LZ-PZ is read once while its data is the same, not for a pointer moving, and let go once it leaves`() = runTest(dispatcher) {
        val r = rig()
        val hawk = FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))
        val crow = FakeOpenPack.lz("lz-2", "LZ CROW", DiagramTarget(34.79, -84.09, grid))
        r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow))
        advanceUntilIdle()
        assertEquals(2, r.model.packReads)
        r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow.copy(name = "LZ RAVEN")), people = listOf(FakeOpenPack.here(2, "Sam Bell", "lz-1", at = 34.1)))
        advanceUntilIdle()
        assertEquals("a rename and someone opening it are no reason to read either again", 2, r.model.packReads)
        assertEquals(listOf("LZ HAWK", "LZ RAVEN"), r.model.state.value.pack!!.rows.map { it.name })
        repeat(5) { i ->
            r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow.copy(name = "LZ RAVEN")), people = listOf(FakeOpenPack.here(2, "Sam Bell", "lz-1", at = 34.2 + i)))
            advanceUntilIdle()
        }
        assertEquals(2, r.model.packReads)
        r.pack.state.value = FakeOpenPack.open(listOf(crow))                                       // HAWK removed: let go
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow))
        advanceUntilIdle()
        assertEquals("HAWK came back, and is read again: nothing kept it meanwhile", 3, r.model.packReads)
    }

    @Test
    fun `in a finished pack nothing new is made, its LZ-PZ is not renamed or analysed, and the panel says why`() = runTest(dispatcher) {
        val r = rig()
        val hawk = FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))
        val crow = FakeOpenPack.lz("lz-2", "LZ CROW", DiagramTarget(34.79, -84.09, grid), source = buildJsonObject { put("original", "changed") })
        r.pack.state.value = FakeOpenPack.open(listOf(hawk, crow), session = FakeOpenPack.finished())
        advanceUntilIdle()
        r.model.newAt(here)
        advanceUntilIdle()
        assertEquals(PackFacts.NOTHING_NEW, r.model.state.value.error)
        assertTrue("nothing asked of the pack", r.pack.log.isEmpty())
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        val s = r.model.state.value
        val current = checkNotNull(s.current)
        assertEquals("OP DK is finished: nothing in it can be changed.", current.locked)
        assertEquals("Off in a finished pack", current.inPack?.short)
        assertTrue(current.inPack!!.savedLine.startsWith("Finished by Sam B. on Oct "))
        assertEquals(listOf("Target set", "Read-only"), current.chips.map { it.text })
        val pack = checkNotNull(s.pack)
        assertTrue(pack.readOnly)
        assertEquals("Off in a finished pack", pack.off)
        assertEquals("This pack is finished: you can look at everything in it and export its routes, but nothing can be changed.", pack.hint)
        assertEquals("OP DK · 2 items · finished · read-only", pack.head)
        assertEquals("the other rows say what holds of them, not read-only: the card and the head say that", listOf("Target set", "Original changed"), pack.rows.single().chips.map { it.text })
        r.model.rename("pack:p:lz-1", "LZ RAVEN")
        advanceUntilIdle()
        assertEquals(current.locked, r.model.state.value.error)
        assertEquals("LZ HAWK", r.session.active.value?.name)
        var asked = 0
        r.terrain.find = { asked++; error("not to be asked") }
        r.model.analyze()
        advanceUntilIdle()
        assertEquals(current.locked, r.model.state.value.error)
        assertEquals("nothing asked of the server", 0, asked)
    }

    @Test
    fun `a viewer's pack is locked too, in a viewer's words, and the lock lifts when they are made an editor`() = runTest(dispatcher) {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))), session = FakeOpenPack.viewing())
        advanceUntilIdle()
        r.model.open("pack:p:lz-1")
        advanceUntilIdle()
        assertEquals("You can view OP DK but not change it.", r.model.state.value.current?.locked)
        assertEquals("In OP DK. You can view it but not change it.", r.model.state.value.current?.inPack?.savedLine)
        assertEquals("OP DK · 1 item · live · view only", r.model.state.value.pack?.head)
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.lz("lz-1", "LZ HAWK", DiagramTarget(34.78, -84.08, grid))))
        advanceUntilIdle()
        assertNull(r.model.state.value.current?.locked)
        assertFalse(r.model.state.value.pack!!.readOnly)
        r.model.rename("pack:p:lz-1", "LZ RAVEN")
        advanceUntilIdle()
        assertEquals("LZ RAVEN", r.session.active.value?.name)
    }

    @Test
    fun `a pack's LZ-PZ says what holds first, read-only, the original changed, from the Library, waiting, synced`() {
        fun chip(readOnly: Boolean = false, original: String? = null, fromLibrary: Boolean = false, waiting: Boolean = false) =
            packLzChips(DiagramStatus.ANALYZED, readOnly, original, fromLibrary, waiting).map { it.text }
        assertEquals(listOf("Analyzed", "Read-only"), chip(readOnly = true, original = "changed", fromLibrary = true, waiting = true))
        assertEquals(listOf("Analyzed", "Original changed"), chip(original = "changed", fromLibrary = true, waiting = true))
        assertEquals(listOf("Analyzed", "From Library"), chip(fromLibrary = true, waiting = true))
        assertEquals(listOf("Analyzed", "Waiting to send"), chip(waiting = true))
        assertEquals(listOf("Analyzed", "Synced"), chip())
    }

    @Test
    fun `a new LZ-PZ at the crosshair has the next made-up name, opens, and is kept open`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val first = r.model.state.value.current!!
        assertEquals("LZ/PZ 1", first.name)
        assertTrue(first.madeUpName)
        assertEquals(grid, first.grid)
        assertEquals(listOf(first.uuid), r.open.ids.value)
        assertEquals(listOf(first.uuid), r.openStore.kept)                                       // kept on the device for the next launch
        r.model.newAt(LatLon(34.79, -84.08))
        advanceUntilIdle()
        val state = r.model.state.value
        assertEquals("LZ/PZ 2", state.current!!.name)
        assertEquals(listOf("LZ/PZ 1"), state.open.map { it.name })                               // the first is "also open"
        assertEquals(2, state.openCount)
    }

    @Test
    fun `with no crosshair yet nothing is made, and it says why`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(null)
        advanceUntilIdle()
        assertTrue(r.rows.isEmpty())
        assertEquals("Move the map to where the LZ/PZ should be first.", r.model.state.value.error)
    }

    @Test
    fun `whatever opens an LZ-PZ puts it among the open ones`() = runTest(dispatcher) {
        val r = rig()
        val made = r.repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
        r.session.open(made.id)                                                                   // as a launch putting back the last one does
        advanceUntilIdle()
        assertEquals(listOf(made.id), r.open.ids.value)
    }

    @Test
    fun `closing the one on the map opens the one before it, and the Library keeps both`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val first = r.model.state.value.current!!.uuid
        r.model.newAt(LatLon(34.79, -84.08))
        advanceUntilIdle()
        val second = r.model.state.value.current!!.uuid
        r.model.close(second)
        advanceUntilIdle()
        assertEquals(first, r.session.active.value?.id)
        assertEquals(listOf(first), r.open.ids.value)
        assertEquals(setOf(first, second), r.rows.map { it.uuid }.toSet())                      // closing is not deleting
        r.model.close(first)
        advanceUntilIdle()
        assertNull(r.session.active.value)
        assertEquals(0, r.model.state.value.openCount)
    }

    @Test
    fun `closing one whose record went meanwhile keeps the work in the Library, and does not open it again`() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))                           // collectors run at once, as on the main thread (AGENTS.md §14)
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val id = r.model.state.value.current!!.uuid
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }                                   // not saved yet
        r.repository.delete(id)                                                                 // gone, as if deleted on another device and synced
        r.model.close(id)
        advanceUntilIdle()
        assertNull(r.session.active.value)
        assertEquals(emptyList<String>(), r.open.ids.value)
        assertEquals(listOf("LZ CROW"), r.rows.map { it.name })                                  // the work is kept, under a new id
    }

    @Test
    fun `an LZ-PZ closed from outside takes its plan page with it`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.showPage(LzPage.PLAN)
        advanceUntilIdle()
        r.session.close()                                                                       // a sign-out closes it
        advanceUntilIdle()
        r.model.open(r.rows.single().uuid)                                                      // from Recent, after signing in again
        advanceUntilIdle()
        assertEquals(LzPage.HOME, r.model.state.value.page)
    }

    @Test
    fun `closing one that is only also open leaves the one on the map`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val first = r.model.state.value.current!!.uuid
        r.model.newAt(LatLon(34.79, -84.08))
        advanceUntilIdle()
        val second = r.model.state.value.current!!.uuid
        r.model.close(first)
        advanceUntilIdle()
        assertEquals(second, r.session.active.value?.id)
        assertEquals(listOf(second), r.open.ids.value)
    }

    @Test
    fun `deleting the one on the map opens the one before it`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        val first = r.model.state.value.current!!.uuid
        r.model.newAt(LatLon(34.79, -84.08))
        advanceUntilIdle()
        val second = r.model.state.value.current!!.uuid
        r.model.delete(second)
        advanceUntilIdle()
        assertEquals(first, r.session.active.value?.id)
        assertEquals(listOf(first), r.rows.map { it.uuid })
        assertEquals(listOf(first), r.open.ids.value)
    }

    @Test
    fun `recent in Library is the three most recently changed that are not open`() = runTest(dispatcher) {
        val r = rig()
        val names = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO")
        for (name in names) {
            r.repository.create(DiagramTarget(34.783817, -84.08219, grid), name)
            Thread.sleep(3)                                                                       // the documents' times are the clock's, to the millisecond
        }
        advanceUntilIdle()
        assertEquals(listOf("ECHO", "DELTA", "CHARLIE"), r.model.state.value.recent.map { it.name })
        r.model.open(r.rows.first { it.name == "ECHO" }.uuid)
        advanceUntilIdle()
        assertEquals(listOf("DELTA", "CHARLIE", "BRAVO"), r.model.state.value.recent.map { it.name })   // what is open is not offered again
        assertTrue(r.model.state.value.recent.all { it.updated != null })
    }

    @Test
    fun `an LZ-PZ can be named only once it is analysed, and naming it says so in a toast`() = runTest(dispatcher) {
        val r = rig()
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.notices.collect { notices += it } }
        r.model.newAt(here)
        advanceUntilIdle()
        assertFalse(r.model.state.value.current!!.canSave)
        assertEquals(LzChip("Analyze to save", ChipTone.INFO), r.model.state.value.current!!.chips.last())
        r.model.analyze()
        advanceUntilIdle()
        val analysed = r.model.state.value.current!!
        assertTrue(analysed.canSave)
        assertEquals(listOf("Boundary, target $grid and slope analysis", "Doghouses and flight data"), analysed.saveContents)
        r.model.saveAs("  LZ HAWK  ")
        advanceUntilIdle()
        val named = r.model.state.value.current!!
        assertEquals("LZ HAWK", named.name)
        assertFalse(named.madeUpName)
        assertFalse(named.canSave)
        assertEquals("LZ HAWK", r.rows.single().name)                                             // written to the record, to be synced
        assertEquals(listOf("Saved \u201cLZ HAWK\u201d to your Library"), notices)
    }

    @Test
    fun `a name another LZ-PZ has is kept apart, and a blank one is refused`() = runTest(dispatcher) {
        val r = rig()
        r.repository.create(DiagramTarget(34.79, -84.08, "16S GD 66993 53949"), "LZ HAWK")
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        r.model.saveAs("   ")
        advanceUntilIdle()
        assertEquals("Give the LZ/PZ a name.", r.model.state.value.error)
        r.model.saveAs("lz hawk")
        advanceUntilIdle()
        assertEquals("lz hawk 2", r.model.state.value.current!!.name)
    }

    @Test
    fun `replacing the LZ-PZ that has the name deletes it, takes it out of the open ones, and this one takes the name`() = runTest(dispatcher) {
        val r = rig()
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.notices.collect { notices += it } }
        val other = r.repository.create(DiagramTarget(34.79, -84.08, "16S GD 66993 53949"), "LZ HAWK")
        r.open.add(other.id)
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val mine = r.model.state.value.current!!.uuid
        r.model.saveAs("LZ HAWK", replacing = other.id)
        advanceUntilIdle()
        assertEquals("LZ HAWK", r.model.state.value.current!!.name)                                 // its own name, not LZ HAWK 2
        assertEquals(listOf(mine), r.rows.map { it.uuid })                                          // the other is gone
        assertFalse(other.id in r.open.ids.value)
        assertEquals("Saved \u201cLZ HAWK\u201d to your Library, in place of the one there before", notices.last())
    }

    @Test
    fun `a rename from the Library can replace the LZ-PZ that has the name, and never itself`() = runTest(dispatcher) {
        val r = rig()
        val one = r.repository.create(DiagramTarget(34.79, -84.08, "16S GD 66993 53949"), "ONE")
        val two = r.repository.create(DiagramTarget(34.79, -84.08, "16S GD 66993 53949"), "TWO")
        advanceUntilIdle()
        r.model.rename(one.id, "TWO", replacing = one.id)                                           // itself: nothing happens
        advanceUntilIdle()
        assertEquals(setOf("ONE", "TWO"), r.rows.map { it.name }.toSet())
        r.model.rename(one.id, "TWO", replacing = two.id)
        advanceUntilIdle()
        assertEquals(listOf(one.id to "TWO"), r.rows.map { it.uuid to it.name })
    }

    @Test
    fun `an action asked for while the last one runs is said not to be done, rather than dropped`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        r.model.newAt(here)                                                                         // before the first is made
        advanceUntilIdle()
        assertEquals(STILL_WORKING, r.model.state.value.error)
        assertEquals(1, r.rows.size)
    }

    @Test
    fun `a made-up name is not a name, so saving or renaming to one is refused and nothing is said to be saved`() = runTest(dispatcher) {
        val r = rig()
        val notices = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.notices.collect { notices += it } }
        r.repository.create(DiagramTarget(34.79, -84.08, "16S GD 66993 53949"), "LZ/PZ")      // a name; kept apart from it, another would be LZ/PZ 2
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        r.model.saveAs(r.model.state.value.current!!.name)                                        // the made-up name the sheet offers
        advanceUntilIdle()
        assertEquals(LzNames.MADE_UP_REFUSED, r.model.state.value.error)
        r.model.dismissError()
        r.model.saveAs("LZ/PZ")                                                                    // taken: kept apart, it would be LZ/PZ 2
        advanceUntilIdle()
        assertEquals(LzNames.MADE_UP_REFUSED, r.model.state.value.error)
        assertTrue("still to be named", r.model.state.value.current!!.canSave)
        assertEquals(emptyList<String>(), notices)
        r.model.saveAs("LZ HAWK")
        advanceUntilIdle()
        r.model.rename(r.model.state.value.current!!.uuid, "LZ/PZ 7")
        advanceUntilIdle()
        assertEquals(LzNames.MADE_UP_REFUSED, r.model.state.value.error)
        assertEquals("LZ HAWK", r.model.state.value.current!!.name)
    }

    @Test
    fun `an LZ-PZ named before it is analysed is not saved until it is`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.rename(r.model.state.value.current!!.uuid, "LZ HAWK")
        advanceUntilIdle()
        val named = r.model.state.value.current!!
        assertFalse(named.madeUpName)
        assertFalse(named.canSave)
        assertEquals(LzChip("Analyze to save", ChipTone.INFO), named.chips.last())
        r.model.analyze()
        advanceUntilIdle()
        val analysed = r.model.state.value.current!!
        assertEquals(LzChip("Analyzed", ChipTone.SUCCESS), analysed.chips.first())
        assertFalse(LzChip("Analyze to save", ChipTone.INFO) in analysed.chips)
        assertFalse("named already: nothing to save", analysed.canSave)
    }

    @Test
    fun `the plan is a page only while an LZ-PZ is open`() = runTest(dispatcher) {
        val r = rig()
        r.model.showPage(LzPage.PLAN)
        assertEquals(LzPage.HOME, r.model.state.value.page)
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.showPage(LzPage.PLAN)
        advanceUntilIdle()
        assertEquals(LzPage.PLAN, r.model.state.value.page)
        r.model.close(r.model.state.value.current!!.uuid)
        advanceUntilIdle()
        assertEquals(LzPage.HOME, r.model.state.value.page)
    }

    @Test
    fun `the slope map switch is there once a slope is measured, and turns the heat map off and on`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        assertNull(r.model.state.value.current!!.slopeShown)
        r.model.analyze()
        advanceUntilIdle()
        assertEquals(true, r.model.state.value.current!!.slopeShown)
        r.model.toggleSlope()
        advanceUntilIdle()
        assertEquals(false, r.model.state.value.current!!.slopeShown)
    }

    @Test
    fun `the slope map's opacity is this device's, set from the plan, kept on its steps, and the same for every LZ-PZ`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        assertEquals(55, r.model.state.value.current!!.slopeOpacity)                                 // the web's 0.55
        r.model.setSlopeOpacity(32)
        advanceUntilIdle()
        assertEquals(30, r.model.state.value.current!!.slopeOpacity)
        assertEquals(30, r.slopeVisibility.opacity.value)                                            // what the map draws it at
        val first = r.session.active.value!!.id
        r.model.newAt(LatLon(34.70, -84.20))
        advanceUntilIdle()
        assertNotEquals(first, r.session.active.value!!.id)
        assertEquals(30, r.model.state.value.current!!.slopeOpacity)
    }

    @Test
    fun `the LZ box switch is there once there is a boundary, and hides it as the LZ-PZ's own view, changing nothing it measures or keeps`() = runTest(dispatcher) {
        val r = rig()
        r.model.newAt(here)
        advanceUntilIdle()
        assertNull("no boundary to hide", r.model.state.value.current!!.outlineShown)
        r.model.analyze()
        advanceUntilIdle()
        val before = r.model.state.value.current!!
        val diagram = r.session.active.value!!
        assertEquals(true, before.outlineShown)
        r.session.edit("Rename") { it.copy(name = "LZ HAWK") }                                          // something to undo
        r.model.showOutline(false)
        advanceUntilIdle()
        val after = r.model.state.value.current!!
        assertEquals(false, after.outlineShown)
        assertEquals(false, r.session.active.value!!.view.showLZOutline)
        assertEquals("what it measures", before.summary, after.summary)
        assertEquals("what it keeps", diagram.analysis, r.session.active.value!!.analysis)
        assertEquals("no step of its own", 1, r.session.undoDepth.value)
        r.session.undo()
        advanceUntilIdle()
        assertEquals("an undo does not bring it back", false, r.model.state.value.current!!.outlineShown)
        // Saved with the LZ/PZ as its base map is, so it is hidden the next time it is opened, here or on the web.
        r.session.flush()
        assertEquals(false, r.repository.open(diagram.id)!!.view.showLZOutline)
        r.model.showOutline(true)
        advanceUntilIdle()
        assertEquals(true, r.model.state.value.current!!.outlineShown)
    }

    @Test
    fun `a slope that could not be measured leaves the LZ box switch, with no slope switch`() = runTest(dispatcher) {
        val r = rig()
        r.terrain.measure = { throw ApiException(502, null, "No configured terrain source covers this LZ") }
        r.model.newAt(here)
        advanceUntilIdle()
        r.model.analyze()
        advanceUntilIdle()
        val current = r.model.state.value.current!!
        assertNull(current.slopeShown)
        assertEquals(true, current.outlineShown)
    }
}
