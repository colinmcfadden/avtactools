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
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** The local points tab as the screens see it: importing, how each set is shown, and what the held point can do for the route being worked on. */
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

    private class Rig(scope: TestScope) {
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        val server = FakeServer()
        val device = Device("A", server)
        val store = device.store as InMemorySyncStore
        val sets = PointSetRepository(device.repository, store, RecordingScheduler())
        val views = PointSetViews(Memory())
        val local = LocalPoints(sets, views)
        val selection = PointSelection()
        val routeRepository = RouteRepository(device.repository, store, RecordingScheduler())
        val routeSession = RouteSession(routeRepository, scope.backgroundScope)
        val routeSelection = RouteSelection()
        val model = PointsViewModel(sets, local, views, selection, routeSession, routeSelection, ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) })
            .also { it.worker = StandardTestDispatcher(scope.testScheduler) }
        val state get() = model.state.value
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    private fun TestScope.settle() = advanceUntilIdle()

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    private suspend fun Rig.imported(name: String = "NORTH GA.LPS") = (sets.import(lps, name) as ImportOutcome.Imported).set

    private fun route(id: String, vararg names: String) = SketchRoute(
        id = id, name = id.uppercase(), color = "#FF453A",
        points = names.mapIndexed { i, n -> RoutePoint(id = "$id-p$i", lat = 34.7 + i * 0.01, lon = -84.1, kind = RoutePoint.KIND_AMPS, ptType = "turn", name = n) },
        plan = RoutePlan(),
    )

    private suspend fun Rig.openRoutes(vararg routes: SketchRoute) {
        val made = routeRepository.create("MISSION", routes.toList())
        routeSession.open(made.id)
    }

    // -- Importing ------------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing saved there is nothing to show`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(PointsUiState(), r.state)
    }

    @Test
    fun `importing a file makes a set in the first colour, shown, and says what it did`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile(lps, "NORTH GA.LPS")
        settle()                                                                              // (the work is done off the main thread; the busy flag is the screen's test)
        assertFalse(r.state.importing)
        assertEquals("Imported NORTH GA: 9 points.", r.state.note)
        assertNull(r.state.error)
        val row = r.state.sets.single()
        assertEquals("NORTH GA", row.name)
        assertEquals(9, row.pointCount)
        assertEquals(RouteColors.PALETTE[0], row.color)
        assertTrue(row.visible)
        assertEquals(SyncStatus.PENDING, row.sync)
    }

    @Test
    fun `a file that is not local points is refused in words and no set is made`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile("not a database".toByteArray(), "junk.lps")
        settle()
        assertEquals("This doesn't look like an .LPS local points file.", r.state.error)
        assertNull(r.state.note)
        assertFalse(r.state.importing)
        assertTrue(r.state.sets.isEmpty())
    }

    @Test
    fun `a failure reading the file from the picker is shown, and clears what was shown before`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile(lps, "A.lps"); settle()
        assertNotNull(r.state.note)
        r.model.importFailed("That file could not be read."); settle()
        assertEquals("That file could not be read.", r.state.error)
        assertNull(r.state.note)
    }

    @Test
    fun `starting another import clears the old message, and a message can be dismissed`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile("junk".toByteArray(), "junk.lps"); settle()
        assertNotNull(r.state.error)
        r.model.dismissError(); settle()
        assertNull(r.state.error)
        r.model.importFile(lps, "A.lps"); settle()
        assertNotNull(r.state.note)
        r.model.dismissNote(); settle()
        assertNull(r.state.note)

        r.model.importFile(lps, "B.lps"); settle()
        assertNotNull(r.state.note)
        r.model.importFile("junk".toByteArray(), "junk.lps"); settle()
        assertNull(r.state.note)                                                              // what the last one said is gone once the next one has been
        assertNotNull(r.state.error)
    }

    @Test
    fun `a set with one point says one point, and the next set takes the next colour`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile(lps, "ONE.lps"); settle()
        r.model.importFile(lps, "TWO.lps"); settle()
        assertEquals(listOf("ONE", "TWO"), r.state.sets.map { it.name })
        assertEquals(RouteColors.PALETTE.take(2), r.state.sets.map { it.color })
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
        assertEquals("Added ${point.name} to R1.", r.state.note)

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
        assertNotNull(r.state.note)
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
