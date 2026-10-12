package app.ezpztac.workspace

import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.ImportOutcome
import app.ezpztac.data.InMemoryOpenDiagramStore
import app.ezpztac.data.InMemoryOpenRouteSetStore
import app.ezpztac.data.OpenDiagrams
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetView
import app.ezpztac.data.PointSetViewStore
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.RouteSets
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.FilePart
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStatus
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Library as the screens see it: every saved item by kind, how it is found and ordered, what is open now, and *Duplicate*. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Memory : PointSetViewStore {
        var kept = emptyMap<String, PointSetView>()
        override fun load() = kept
        override fun save(views: Map<String, PointSetView>) { kept = views }
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val store = device.store as InMemorySyncStore
        val diagrams = DiagramRepository(device.repository, store, RecordingScheduler())
        val diagramSession = DiagramSession(diagrams, scope.backgroundScope)
        val routes = RouteRepository(device.repository, store, RecordingScheduler(), StandardTestDispatcher(scope.testScheduler))
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val points = PointSetRepository(device.repository, store, RecordingScheduler())
        val views = PointSetViews(Memory())
        val openDiagrams = OpenDiagrams(InMemoryOpenDiagramStore())
        val openRouteSets = OpenRouteSets(InMemoryOpenRouteSetStore())
        val model = LibraryViewModel(diagrams, routes, points, diagramSession, routeSession, openDiagrams, openRouteSets, views)
        val said = mutableListOf<String>()
        val state get() = model.state.value

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { said += it } }
        }

        /** Puts a record straight in the store, with the time it says it last changed. */
        suspend fun put(kind: RecordKind, uuid: String, name: String, updatedAt: String?, data: JsonObject = RouteSets.serialize(app.ezpztac.model.RouteSet(uuid, name = name, routes = emptyList()))) =
            store.transaction { put(LocalRecord(kind, uuid, null, null, name, data, updatedAt = updatedAt)) }
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    @Test
    fun `each tab lists its kind, counted whatever the search, with what each is and when it changed`() = runTest(dispatcher) {
        val r = rig()
        r.diagrams.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK")
        r.put(RecordKind.ROUTE, "r1", "NIGHT INFIL", "2026-10-12T09:00:00Z")
        r.device.repository.create(RecordKind.MISSION, "GOAT", RouteSets.missionSummary(emptyList()), uuid = "m1", file = FilePart("GOAT_SUCKER.msnx", byteArrayOf(1, 2, 3)))
        r.points.import(lps, "NORTH GA.LPS")
        advanceUntilIdle()
        assertEquals(mapOf(LibraryTab.LZ to 1, LibraryTab.ROUTES to 2, LibraryTab.POINTS to 1), r.state.counts)
        val lz = r.state.rows.single()
        assertEquals("16S GD 66993 52949", lz.grid)
        assertTrue(lz.updated!!.startsWith("Updated "))
        assertEquals("1 saved LZ/PZ", r.state.total)

        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        val byName = r.state.rows.associateBy { it.name }
        assertEquals("Sketched routes · 0 routes", byName.getValue("NIGHT INFIL").detail)
        assertEquals("Mission file · GOAT_SUCKER.msnx", byName.getValue("GOAT").detail)
        assertTrue(byName.getValue("GOAT").isMission)
        assertEquals("2 saved route sets", r.state.total)

        r.model.showTab(LibraryTab.POINTS); advanceUntilIdle()
        assertEquals("9 points", r.state.rows.single().detail)
    }

    @Test
    fun `what is open in the session, or a set of points on the map, says so`() = runTest(dispatcher) {
        val r = rig()
        val one = r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GD 1 1"), "ONE")
        r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GD 1 1"), "TWO")
        r.openDiagrams.add(one.id)
        val set = (r.points.import(lps, "NORTH GA.LPS") as ImportOutcome.Imported).set
        advanceUntilIdle()
        assertEquals(mapOf("ONE" to true, "TWO" to false), r.state.rows.associate { it.name to it.inSession })
        r.model.showTab(LibraryTab.POINTS); advanceUntilIdle()
        assertTrue(r.state.rows.single().inSession)                                           // shown on the map unless hidden
        r.views.toggle(set.id); advanceUntilIdle()
        assertFalse(r.state.rows.single().inSession)
    }

    @Test
    fun `search finds by name without regard to case, an LZ-PZ by its grid too, and a new tab starts with none`() = runTest(dispatcher) {
        val r = rig()
        r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GD 66993 52949"), "LZ HAWK")
        r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GC 11111 22222"), "PZ OAK")
        advanceUntilIdle()
        r.model.search("hawk"); advanceUntilIdle()
        assertEquals(listOf("LZ HAWK"), r.state.rows.map { it.name })
        r.model.search("gc1111"); advanceUntilIdle()                                           // spaces do not matter in a grid
        assertEquals(listOf("PZ OAK"), r.state.rows.map { it.name })
        assertEquals(2, r.state.counts[LibraryTab.LZ])
        r.model.showTab(LibraryTab.LZ); advanceUntilIdle()                                     // the same tab again (the page opened anew, the phone turned)
        assertEquals("gc1111", r.state.query)
        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        assertEquals("", r.state.query)
    }

    @Test
    fun `the newest change comes first, an item never noted last, and by name when asked`() = runTest(dispatcher) {
        val r = rig()
        r.put(RecordKind.ROUTE, "a", "ALPHA", "2026-10-10T09:00:00Z")
        r.put(RecordKind.ROUTE, "b", "BRAVO", null)
        r.put(RecordKind.ROUTE, "c", "CHARLIE", "2026-10-12T09:00:00.123456")                  // the server's: no offset, which is UTC
        r.put(RecordKind.ROUTE, "d", "delta", "2026-10-11T09:00:00+00:00")
        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        assertEquals(listOf("CHARLIE", "delta", "ALPHA", "BRAVO"), r.state.rows.map { it.name })
        assertNull(r.state.rows.last().updated)                                                // no time made up
        r.model.sortBy(LibrarySort.NAME); advanceUntilIdle()
        assertEquals(listOf("ALPHA", "BRAVO", "CHARLIE", "delta"), r.state.rows.map { it.name })
    }

    @Test
    fun `an LZ-PZ whose document says no time and was never noted says none, rather than now`() = runTest(dispatcher) {
        val r = rig()
        val bare = kotlinx.serialization.json.buildJsonObject {                                    // as a pack's refused edits are kept: no createdAt, no updatedAt
            put("schema", JsonPrimitive(2)); put("name", JsonPrimitive("LZ BARE"))
            put("target", kotlinx.serialization.json.buildJsonObject { put("lat", JsonPrimitive(34.7)); put("lon", JsonPrimitive(-84.1)) })
        }
        r.put(RecordKind.LZ, "bare", "LZ BARE", null, bare)
        r.put(RecordKind.LZ, "noted", "LZ NOTED", "2026-10-12T09:00:00Z", bare)
        advanceUntilIdle()
        val rows = r.state.rows.associateBy { it.uuid }
        assertNull(rows.getValue("bare").updated)
        assertTrue(rows.getValue("noted").updated!!.startsWith("Updated Oct 12"))
        assertEquals(listOf("noted", "bare"), r.state.rows.map { it.uuid })                     // the one never noted last
    }

    @Test
    fun `each opening of the Library starts fresh, while moving between tabs keeps the order`() = runTest(dispatcher) {
        val r = rig()
        r.model.search("hawk"); r.model.sortBy(LibrarySort.NAME); advanceUntilIdle()
        r.model.open(LibraryTab.LZ); advanceUntilIdle()                                        // the same tab, a new opening (Review, Browse all)
        assertEquals("", r.state.query)
        assertEquals(LibrarySort.UPDATED, r.state.sort)
        assertEquals(LibraryTab.LZ, r.state.tab)
        r.model.sortBy(LibrarySort.NAME); r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        assertEquals(LibrarySort.NAME, r.state.sort)
    }

    @Test
    fun `a time is read with or without its offset, without as UTC, and nothing else`() {
        assertEquals("2026-10-12T09:00:00Z", LzNames.instantOf("2026-10-12T09:00:00Z").toString())
        assertEquals("2026-10-12T09:00:00.123456Z", LzNames.instantOf("2026-10-12T09:00:00.123456").toString())
        assertEquals("2026-10-12T07:00:00Z", LzNames.instantOf("2026-10-12T09:00:00+02:00").toString())
        assertNull(LzNames.instantOf("yesterday"))
        assertNull(LzNames.instantOf(""))
        assertNull(LzNames.instantOf(null))
    }

    @Test
    fun `duplicating an LZ-PZ copies all of it under the next free name, and says so`() = runTest(dispatcher) {
        val r = rig()
        val made = r.diagrams.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK")
        r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GD 1 1"), "LZ HAWK 2")
        advanceUntilIdle()
        r.model.duplicate(r.state.rows.single { it.uuid == made.id }); advanceUntilIdle()
        val copy = r.state.rows.single { it.name == "LZ HAWK 3" }
        assertNotEquals(made.id, copy.uuid)
        assertEquals("16S GD 66993 52949", copy.grid)
        val copied = r.diagrams.open(copy.uuid)!!
        assertEquals(copy.uuid, copied.id)
        assertEquals("LZ HAWK 3", copied.name)
        assertEquals(made.target, copied.target)
        assertEquals(listOf("Made “LZ HAWK 3”, a copy of “LZ HAWK”"), r.said)
        assertFalse(r.state.busy)
    }

    @Test
    fun `an LZ-PZ open now is saved first, so its copy has what is on the map`() = runTest(dispatcher) {
        val r = rig()
        val made = r.diagrams.create(DiagramTarget(34.7, -84.1, "16S GD 1 1"), "LZ HAWK")
        r.diagramSession.open(made.id)
        advanceUntilIdle()
        // Not yet written (the pause before a save has not passed), and copied at once: only saving it first puts the change in the copy.
        r.diagramSession.edit("Move") { it.copy(target = DiagramTarget(35.0, -85.0, "16S XX 2 2")) }
        r.model.duplicate(r.state.rows.single()); advanceUntilIdle()
        val copy = r.state.rows.single { it.name == "LZ HAWK 2" }
        assertEquals("16S XX 2 2", r.diagrams.open(copy.uuid)!!.target?.mgrs)
    }

    @Test
    fun `duplicating a mission copies its file, and a set of points its points`() = runTest(dispatcher) {
        val r = rig()
        val bytes = byteArrayOf(9, 8, 7, 6)
        r.device.repository.create(RecordKind.MISSION, "GOAT", RouteSets.missionSummary(emptyList()), uuid = "m1", file = FilePart("GOAT_SUCKER.msnx", bytes))
        val set = (r.points.import(lps, "NORTH GA.LPS") as ImportOutcome.Imported).set
        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        r.model.duplicate(r.state.rows.single()); advanceUntilIdle()
        val copy = r.state.rows.single { it.name == "GOAT 2" }
        assertTrue(copy.isMission)
        assertTrue(bytes.contentEquals(r.routes.missionFile(copy.uuid)!!))
        assertEquals("Mission file · GOAT_SUCKER.msnx", copy.detail)

        r.model.showTab(LibraryTab.POINTS); advanceUntilIdle()
        r.model.duplicate(r.state.rows.single()); advanceUntilIdle()
        val pointsCopy = r.state.rows.single { it.name == "NORTH GA 2" }
        assertEquals(set.points, r.points.open(pointsCopy.uuid)!!.points)
    }

    @Test
    fun `an item gone by the time it is copied says so, and a second press while one is made makes one copy`() = runTest(dispatcher) {
        val r = rig()
        r.put(RecordKind.ROUTE, "r1", "NIGHT INFIL", "2026-10-12T09:00:00Z")
        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        val row = r.state.rows.single()
        r.model.duplicate(row)
        r.model.duplicate(row)
        advanceUntilIdle()
        assertEquals(2, r.state.counts[LibraryTab.ROUTES])
        r.routes.delete("r1"); advanceUntilIdle()
        r.model.duplicate(row); advanceUntilIdle()
        assertEquals("“NIGHT INFIL” is no longer in your Library.", r.said.last())
    }

    @Test
    fun `a conflict copy is listed with what it is a copy of`() = runTest(dispatcher) {
        val r = rig()
        r.store.transaction {
            put(LocalRecord(RecordKind.ROUTE, "copy", null, null, "ONE (from this device)", RouteSets.serialize(app.ezpztac.model.RouteSet("copy", name = "x", routes = emptyList())), conflictOf = "one"))
        }
        r.model.showTab(LibraryTab.ROUTES); advanceUntilIdle()
        val row = r.state.rows.single()
        assertEquals("one", row.conflictOf)
        assertEquals(SyncStatus.CONFLICT, row.sync)
    }
}
