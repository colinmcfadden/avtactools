package app.ezpztac.workspace

import app.ezpztac.data.LocalPoints
import app.ezpztac.data.PointHeld
import app.ezpztac.data.PointSelection
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetView
import app.ezpztac.data.PointSetViewStore
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.ImportOutcome
import app.ezpztac.data.SessionPointSets
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.data.ThreatFileLog
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.MapFocus
import app.ezpztac.data.MissionImporter
import app.ezpztac.formats.MsnxReader
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Imports panel as the screens see it: the sets of local points (saved, or held for this session) and how each is shown, the missions kept with their files,
 * the threat files brought in, and what the held point can do for the route being worked on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PointsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Memory : PointSetViewStore {
        override fun load() = emptyMap<String, PointSetView>()

        override fun save(views: Map<String, PointSetView>) {}
    }

    private class NoVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) {}
        override fun wipe() {}
    }

    /** A feed that hears of every change a moment late, as Room's does: it re-reads after its invalidation tracker runs, once the transaction is over. */
    private class LateFeed(private val inner: RecordFeed, private val lateMs: Long) : RecordFeed {
        override fun observe(kind: RecordKind) = inner.observe(kind).onEach { delay(lateMs) }
    }

    private class Rig(scope: TestScope, lateMs: Long = 0) {
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        val server = FakeServer()
        val device = Device("A", server)
        val store = device.store as InMemorySyncStore
        val sets = PointSetRepository(device.repository, if (lateMs > 0) LateFeed(store, lateMs) else store, RecordingScheduler())
        val views = PointSetViews(Memory())
        val session = SessionPointSets()
        val local = LocalPoints(sets, views, session)
        val selection = PointSelection()
        val routeRepository = RouteRepository(device.repository, store, RecordingScheduler(), StandardTestDispatcher(scope.testScheduler))
        val routeSession = RouteSession(routeRepository, scope.backgroundScope)
        val routeSelection = RouteSelection()
        val threats = ThreatStore(NoVault(), scope.backgroundScope, StandardTestDispatcher(scope.testScheduler)) { 1_000L }
        val threatFiles = ThreatFileLog(threats)
        val model = PointsViewModel(
            sets, local, views, selection, routeSession, routeSelection, ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) },
            session, routeRepository, threatFiles,
        )
        val said = mutableListOf<String>()
        val state get() = model.state.value

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { said += it } }
        }
    }

    private fun TestScope.rig(lateMs: Long = 0) = Rig(this, lateMs).also { advanceUntilIdle() }

    private fun TestScope.settle() = advanceUntilIdle()

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    private suspend fun Rig.imported(name: String = "NORTH GA.LPS") = (sets.import(lps, name) as ImportOutcome.Imported).set

    /** A set read from the file and held for this session, as the import review's *This session* holds one. */
    private fun Rig.heldForSession(name: String = "TAXI.LPS") = (sets.read(lps, name) as ImportOutcome.Imported).set.also { session.add(it) }

    private fun route(id: String, vararg names: String) = SketchRoute(
        id = id, name = id.uppercase(), color = "#FF453A",
        points = names.mapIndexed { i, n -> RoutePoint(id = "$id-p$i", lat = 34.7 + i * 0.01, lon = -84.1, kind = RoutePoint.KIND_AMPS, ptType = "turn", name = n) },
        plan = RoutePlan(),
    )

    private suspend fun Rig.openRoutes(vararg routes: SketchRoute) {
        val made = routeRepository.create("MISSION", routes.toList())
        routeSession.open(made.id)
    }

    // -- What is listed ----------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing saved there is nothing to show`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(PointsUiState(), r.state)
        assertEquals(0, r.state.itemCount)
    }

    @Test
    fun `saved sets come first in their colours, then the session's, each marked where it is kept`() = runTest(dispatcher) {
        val r = rig()
        r.imported("ONE.lps")
        r.heldForSession("TAXI.lps")
        r.imported("TWO.lps"); settle()
        assertEquals(listOf("ONE", "TWO", "TAXI"), r.state.sets.map { it.name })
        assertEquals(listOf(false, false, true), r.state.sets.map { it.inSession })
        assertEquals(RouteColors.PALETTE.take(3), r.state.sets.map { it.color })
        assertEquals(3, r.state.itemCount)
    }

    @Test
    fun `a set says how many points it holds, with a comma, and that it is hidden`() {
        val row = PointSetRowUi("a", "A", 1234, "#FF453A", visible = true, sync = SyncStatus.SYNCED, conflictOf = null)
        assertEquals("1,234 points", row.meta)
        assertEquals("1 point · hidden", row.copy(pointCount = 1, visible = false).meta)
    }

    @Test
    fun `missions kept with their files are listed by file name, sets drawn here are not`() = runTest(dispatcher) {
        val r = rig()
        val bytes = Fixtures.bytes("msnx/template.msnx")
        MissionImporter(r.routeRepository, r.routeSession, MapFocus()).import(bytes, MsnxReader.read(bytes), "GOAT_SUCKER.msnx")
        r.routeRepository.create("SKETCHED ROUTES"); settle()
        val mission = r.state.missions.single()
        assertEquals("GOAT_SUCKER.msnx", mission.fileName)
        assertEquals("GOAT_SUCKER", mission.name)
        assertEquals("1 route", mission.meta)
        assertEquals(1, r.state.itemCount)
    }

    @Test
    fun `threat files are listed only for an account with threats, and go with the picture`() = runTest(dispatcher) {
        val r = rig()
        r.threatFiles.record("SA-6 site.ths", 7); settle()
        assertTrue(r.state.threatFiles.isEmpty())                                             // no Threats panel to open them in
        r.model.allowThreats(true); settle()
        assertEquals(listOf(ThreatFileImport("SA-6 site.ths", 7)), r.state.threatFiles)
        assertEquals(1, r.state.itemCount)
        r.threats.wipe(); settle()                                                            // a sign-out, or Remove all: those threats are gone
        assertTrue(r.state.threatFiles.isEmpty())
    }

    // -- This session's sets ----------------------------------------------------------------------------------------

    @Test
    fun `a session's set is saved to the Library under its own id and colour, and is no longer the session's`() = runTest(dispatcher) {
        val r = rig()
        val set = r.heldForSession("TAXI.lps"); settle()
        val colour = r.state.sets.single().color
        r.model.saveToLibrary(set.id); settle()
        val row = r.state.sets.single()
        assertEquals(set.id, row.uuid)
        assertFalse(row.inSession)
        assertEquals(colour, row.color)
        assertTrue(r.session.sets.value.isEmpty())
        assertEquals(listOf("TAXI"), r.store.transaction { records(RecordKind.POINT_SET) }.map { it.name })
        assertEquals(listOf("Saved TAXI to your Library."), r.said)
    }

    @Test
    fun `a session's set saved to the Library keeps its colour and stays hidden, though the Library lists it a moment late`() = runTest(dispatcher) {
        val r = rig(lateMs = 200)
        r.imported("ONE.lps")
        r.imported("TWO.lps")
        val set = r.heldForSession("TAXI.lps"); settle()
        r.model.recolor(set.id, RouteColors.PALETTE[5])
        r.model.toggleVisible(set.id); settle()
        r.model.saveToLibrary(set.id); settle()
        val row = r.state.sets.single { it.uuid == set.id }
        assertFalse(row.inSession)
        assertEquals(RouteColors.PALETTE[5], row.color)                                       // not the first free colour a new set would take
        assertFalse(row.visible)                                                              // and still off the map, as the person left it
        assertEquals(listOf("Saved TAXI to your Library."), r.said)
    }

    @Test
    fun `a session's set is let go, not deleted, and its held point is put down`() = runTest(dispatcher) {
        val r = rig()
        val set = r.heldForSession("TAXI.lps"); settle()
        r.selection.select(set.id, set.points.first().id); settle()
        r.model.removeFromSession(set.id); settle()
        assertTrue(r.state.sets.isEmpty())
        assertNull(r.selection.held.value)
        assertTrue(r.store.transaction { records(RecordKind.POINT_SET) }.isEmpty())
        assertEquals(listOf("Removed TAXI from this session."), r.said)
    }

    @Test
    fun `a session's set is renamed where it is held, and nothing is saved`() = runTest(dispatcher) {
        val r = rig()
        val set = r.heldForSession("TAXI.lps"); settle()
        assertNull(r.model.rename(set.id, "  NIGHT TAXI  ")); settle()
        assertEquals("NIGHT TAXI", r.state.sets.single().name)
        assertTrue(r.state.sets.single().inSession)
        assertTrue(r.store.transaction { records(RecordKind.POINT_SET) }.isEmpty())
        assertEquals("A set needs a name.", r.model.rename(set.id, " "))
    }

    @Test
    fun `saving or letting go of a set that is not the session's does nothing`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        r.model.saveToLibrary(set.id); r.model.removeFromSession(set.id); settle()
        assertEquals(1, r.state.sets.size)
        assertEquals(1, r.store.transaction { records(RecordKind.POINT_SET) }.size)
        assertTrue(r.said.isEmpty())
    }

    @Test
    fun `a point of a session's set can be held and used like any`() = runTest(dispatcher) {
        val r = rig()
        val set = r.heldForSession("TAXI.lps")
        r.openRoutes(route("r1", ".SP", ".TGT")); settle()
        r.model.select(set.id, set.points.first().id); settle()
        assertEquals("TAXI", r.state.held!!.setName)
        assertEquals("R1", r.state.held!!.addTo)
    }

    // -- A set ----------------------------------------------------------------------------------------------------

    @Test
    fun `a set is hidden and shown, and recoloured only to a colour of the palette`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        r.model.toggleVisible(set.id); settle()
        assertFalse(r.state.sets.single().visible)
        r.model.toggleVisible(set.id); settle()
        assertTrue(r.state.sets.single().visible)

        r.model.recolor(set.id, RouteColors.PALETTE[3]); settle()
        assertEquals(RouteColors.PALETTE[3], r.state.sets.single().color)
        r.model.recolor(set.id, "#123456"); settle()                                         // not one this screen offers: ignored, not drawn on the map
        r.model.recolor(set.id, "red\"}); drop"); settle()
        assertEquals(RouteColors.PALETTE[3], r.state.sets.single().color)
    }

    @Test
    fun `a set is renamed, and a blank name is refused in words and changes nothing`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        assertNull(r.model.rename(set.id, "  NIGHT  ")); settle()
        assertEquals("NIGHT", r.state.sets.single().name)
        assertEquals("A set needs a name.", r.model.rename(set.id, "   ")); settle()
        assertEquals("NIGHT", r.state.sets.single().name)
    }

    @Test
    fun `a set is deleted, and the point held in it is put down`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        r.selection.select(set.id, set.points.first().id); settle()
        assertNotNull(r.state.held)
        r.model.delete(set.id); settle()
        assertTrue(r.state.sets.isEmpty())
        assertNull(r.selection.held.value)
        assertNull(r.state.held)
    }

    @Test
    fun `deleting a set that is not the one holding the point leaves the point held`() = runTest(dispatcher) {
        val r = rig()
        val one = r.imported("ONE.lps")
        val two = r.imported("TWO.lps"); settle()
        r.selection.select(two.id, two.points.first().id)
        r.model.delete(one.id); settle()
        assertEquals(PointHeld(two.id, two.points.first().id), r.selection.held.value)
    }

    @Test
    fun `a conflict is settled through the resolver, for point sets`() = runTest(dispatcher) {
        val r = rig()
        r.model.resolve("copy-1", SyncEngine.Resolution.KEEP_BOTH); settle()
        assertEquals(listOf(Triple(RecordKind.POINT_SET, "copy-1", SyncEngine.Resolution.KEEP_BOTH)), r.resolutions)
    }

    // -- The held point -------------------------------------------------------------------------------------------

    @Test
    fun `a held point is described from the file, and one that is not there is not shown`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        val first = set.points.first { it.name.isNotBlank() }
        r.model.select(set.id, first.id); settle()
        val held = r.state.held!!
        assertEquals(first.name, held.rawName)
        assertEquals(first.name, held.title)
        assertEquals("NORTH GA", held.setName)
        assertEquals(first.at, held.at)
        assertEquals(first.elevationFt, held.elevationFt)
        assertTrue(held.grid, held.grid.matches(Regex("""\d\d[A-Z] [A-Z]{2} \d{5} \d{5}""")))
        assertTrue(held.latLon, held.latLon.matches(Regex("""-?\d+\.\d{5}, -?\d+\.\d{5}""")))

        r.model.select(set.id, "nope"); settle()
        assertNull(r.state.held)
        r.model.select("no set", first.id); settle()
        assertNull(r.state.held)
        r.model.deselect(); settle()
        assertNull(r.selection.held.value)
    }

    @Test
    fun `a point with no name in the file is called so`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        val unnamed = set.points.first { it.name.isBlank() }
        r.model.select(set.id, unnamed.id); settle()
        assertEquals("(unnamed)", r.state.held!!.title)
        assertEquals("", r.state.held!!.rawName)
    }

    @Test
    fun `an elevation is shown in whole feet with a thousands comma, and none when the file had none`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        val withElevation = set.points.first { (it.elevationFt ?: 0.0) >= 1.0 }
        r.model.select(set.id, withElevation.id); settle()
        assertEquals(withCommas(Math.round(withElevation.elevationFt!!)) + " ft", r.state.held!!.elevation)
        val without = set.points.first { it.elevationFt == null }
        r.model.select(set.id, without.id); settle()
        assertNull(r.state.held!!.elevation)
    }

    @Test
    fun `with no routes open there is nothing to add the point to, and asking does nothing`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        r.model.select(set.id, set.points.first().id); settle()
        assertNull(r.state.held!!.addTo)
        assertNull(r.state.held!!.useFor)
        r.model.addToHeldRoute(); r.model.useForHeldRoutePoint(); settle()
        assertNull(r.routeSession.active.value)
    }

    @Test
    fun `a route that is the only one can be added to, and a held route is the one added to`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported()
        r.openRoutes(route("r1", ".SP", ".TGT")); settle()
        r.model.select(set.id, set.points.first().id); settle()
        assertEquals("R1", r.state.held!!.addTo)                                               // the only route there is
        r.routeSession.edit("second") { it.plus(route("r2", ".A", ".B")) }; settle()
        assertNull(r.state.held!!.addTo)                                                       // two, and none held: no guessing
        r.routeSelection.select("r2"); settle()
        assertEquals("R2", r.state.held!!.addTo)
    }

    @Test
    fun `adding the point appends a named turn point with its charted elevation, holds it, and is one undo step`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported()
        r.openRoutes(route("r1", ".SP", ".TGT")); settle()
        val point = set.points.first { (it.elevationFt ?: 0.0) >= 1.0 && it.name.isNotBlank() }
        r.model.newPointId = { "added-1" }
        r.model.select(set.id, point.id); settle()
        val depth = r.routeSession.undoDepth.value

        r.model.addToHeldRoute(); settle()
        val route = r.routeSession.active.value!!.route("r1")!!
        val added = route.points.last()
        assertEquals("added-1", added.id)
        assertEquals(point.name.uppercase(), added.name)
        assertEquals(RoutePoint.KIND_AMPS, added.kind)
        assertEquals("turn", added.ptType)
        assertEquals(point.lat, added.lat, 0.0)
        assertEquals(point.lon, added.lon, 0.0)
        assertEquals(point.elevationFt, added.chartElevationFt)
        assertEquals(3, route.points.size)
        assertEquals(RouteHeld("r1", "added-1"), r.routeSelection.held.value)
        assertEquals(depth + 1, r.routeSession.undoDepth.value)
        assertEquals(listOf("Added ${point.name} to R1."), r.said)

        r.routeSession.undo(); settle()
        assertEquals(2, r.routeSession.active.value!!.route("r1")!!.points.size)
    }

    @Test
    fun `a route point that is held is offered the point, and using it snaps the route point onto it`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported()
        r.openRoutes(route("r1", ".SP", ".TGT", ".RP")); settle()
        val point = set.points.first { (it.elevationFt ?: 0.0) >= 1.0 && it.name.isNotBlank() }
        r.routeSelection.select("r1", "r1-p1")
        r.model.select(set.id, point.id); settle()
        assertEquals("R1: .TGT", r.state.held!!.useFor)

        val depth = r.routeSession.undoDepth.value
        r.model.useForHeldRoutePoint(); settle()
        val route = r.routeSession.active.value!!.route("r1")!!
        val moved = route.points[1]
        assertEquals(point.name.uppercase(), moved.name)
        assertEquals(point.lat, moved.lat, 0.0)
        assertEquals(point.lon, moved.lon, 0.0)
        assertEquals(point.elevationFt, moved.chartElevationFt)
        assertEquals(listOf(".SP", ".RP"), listOf(route.points[0].name, route.points[2].name))          // the others are as they were
        assertEquals(3, route.points.size)
        assertEquals(depth + 1, r.routeSession.undoDepth.value)
        assertEquals(listOf("Moved the route point onto ${point.name} (NORTH GA)."), r.said)
    }

    @Test
    fun `with no route point held, using the point does nothing`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported()
        r.openRoutes(route("r1", ".SP", ".TGT")); settle()
        r.routeSelection.select("r1")                                                          // the route, but not a point of it
        r.model.select(set.id, set.points.first().id); settle()
        assertNull(r.state.held!!.useFor)
        val depth = r.routeSession.undoDepth.value
        r.model.useForHeldRoutePoint(); settle()
        assertEquals(depth, r.routeSession.undoDepth.value)
    }

    @Test
    fun `a route point held that is no longer in the route is not used`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported()
        r.openRoutes(route("r1", ".SP", ".TGT")); settle()
        r.routeSelection.select("r1", "gone")
        r.model.select(set.id, set.points.first().id); settle()
        assertNull(r.state.held!!.useFor)
        val depth = r.routeSession.undoDepth.value
        r.model.useForHeldRoutePoint(); settle()
        assertEquals(depth, r.routeSession.undoDepth.value)
    }

    @Test
    fun `a row is pending until the server has it, and a conflict copy is marked`() = runTest(dispatcher) {
        val r = rig()
        val set = r.imported(); settle()
        assertEquals(SyncStatus.PENDING, r.state.sets.single().sync)
        r.device.sync(); settle()
        assertEquals(SyncStatus.SYNCED, r.state.sets.single().sync)
        val original = r.device.record(RecordKind.POINT_SET, set.id)!!
        r.store.transaction { put(original.copy(uuid = "copy-1", conflictOf = set.id, name = "NORTH GA (copy)")) }; settle()
        val copy = r.state.sets.single { it.uuid == "copy-1" }
        assertEquals(SyncStatus.CONFLICT, copy.sync)
        assertEquals(set.id, copy.conflictOf)
    }
}
