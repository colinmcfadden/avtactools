package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.ActiveAircraftChoice
import app.ezpztac.data.MasterProfileStore
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The routes tab as the screens see it: the sets and the routes of the open one, drawing, and the words when something cannot be done. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val a = LatLon(34.70, -84.10)
    private val b = LatLon(34.71, -84.09)
    private val c = LatLon(34.72, -84.08)

    private class Store : MasterProfileStore {
        override fun read() = emptyList<AircraftProfile>()
        override fun write(profiles: List<AircraftProfile>) {}
    }

    private class Choice : ActiveAircraftChoice {
        override fun slug(): String? = null
        override fun choose(slug: String) {}
    }

    private class Rig(scope: TestScope, resolver: ConflictResolver?) {
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        val server = FakeServer()
        val device = Device("A", server)
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = RouteSession(repository, scope.backgroundScope)
        val selection = RouteSelection()
        val profiles = AircraftProfiles(
            device.store as InMemorySyncStore, Store(), { emptyList() }, Choice(),
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler(),
        )
        val sketching = RouteSketching(session, profiles)
        val model = RoutesViewModel(repository, session, selection, sketching, resolver ?: ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) })
        val state get() = model.state.value
    }

    private fun TestScope.rig(resolver: ConflictResolver? = null) = Rig(this, resolver).also { advanceUntilIdle() }

    private fun TestScope.settle() = advanceUntilIdle()

    /** A set with two routes, open. */
    private fun TestScope.openWithRoutes(r: Rig, name: String = "") {
        r.model.createSet(name); settle()
        repeat(2) {
            r.model.startDrawing()
            r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
            r.model.finishDrawing()
            settle()
        }
    }

    // -- Sets --------------------------------------------------------------------------------------------------

    @Test
    fun `with nothing saved the tab is empty`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(RoutesUiState(), r.state)
    }

    @Test
    fun `a new set is named MISSION and a number, or what the person typed in capitals, and is opened`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating(); settle()
        assertTrue(r.state.creating)
        r.model.createSet(""); settle()
        assertFalse(r.state.creating)
        assertEquals("MISSION 1", r.state.open!!.name)
        r.model.createSet("  night run "); settle()
        assertEquals("NIGHT RUN", r.state.open!!.name)
        assertEquals(listOf("MISSION 1", "NIGHT RUN"), r.state.sets.map { it.name }.sorted())
        assertEquals(listOf(false, true), r.state.sets.sortedBy { it.name }.map { it.isOpen })                // MISSION 1 is put away, NIGHT RUN is open
    }

    @Test
    fun `cancelling the form closes it`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating(); settle()
        r.model.cancelCreating(); settle()
        assertFalse(r.state.creating)
    }

    @Test
    fun `opening another set shows its routes, and a set already open is left alone`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        r.model.createSet("TWO"); settle()
        assertEquals(0, r.state.open!!.routes.size)
        val one = r.state.sets.single { it.name == "ONE" }.uuid
        r.model.openSet(one); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertEquals(2, r.state.open!!.routes.size)
        r.model.openSet(one); settle()
        assertNull(r.state.error)
    }

    @Test
    fun `choosing the set that is already open does not reopen it, so what can be undone stays`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        val uuid = r.state.open!!.uuid
        r.model.toggleVisible(r.state.open!!.routes[0].id); settle()
        assertTrue(r.state.open!!.canUndo)
        r.model.openSet(uuid); settle()
        assertTrue(r.state.open!!.canUndo)
    }

    @Test
    fun `a set that is gone cannot be opened, and says so`() = runTest(dispatcher) {
        val r = rig()
        r.model.openSet("nope"); settle()
        assertEquals("That set is no longer here.", r.state.error)
        assertNull(r.state.open)
        r.model.dismissError(); settle()
        assertNull(r.state.error)
    }

    @Test
    fun `closing puts the set away but keeps it in the list`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        r.model.closeSet(); settle()
        assertNull(r.state.open)
        assertEquals(1, r.state.sets.size)
        assertEquals(2, r.state.sets.single().routeCount)
    }

    @Test
    fun `renaming the open set is saved with its edits, and a blank name is refused`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        val uuid = r.state.open!!.uuid
        r.model.renameSet(uuid, "  strike "); settle()
        assertEquals("STRIKE", r.state.open!!.name)
        assertEquals("STRIKE", r.repository.open(uuid)!!.name)
        assertEquals(2, r.repository.open(uuid)!!.routes.size)                              // the routes drawn a moment ago went with it
        r.model.renameSet(uuid, "   "); settle()
        assertEquals("A set needs a name.", r.state.error)
        assertEquals("STRIKE", r.state.open!!.name)
    }

    @Test
    fun `renaming a set that is not open changes the record`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        val one = r.state.open!!.uuid
        r.model.createSet("TWO"); settle()
        r.model.renameSet(one, "FIRST"); settle()
        assertEquals("FIRST", r.state.sets.single { it.uuid == one }.name)
    }

    @Test
    fun `deleting the open set closes it first, and removes it`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val uuid = r.state.open!!.uuid
        r.model.deleteSet(uuid); settle()
        assertNull(r.state.open)
        assertEquals(emptyList<RouteSetRow>(), r.state.sets)
        assertNull(r.repository.open(uuid))
    }

    @Test
    fun `a conflict is settled through the resolver, for routes`() = runTest(dispatcher) {
        val r = rig()
        r.model.resolve("copy-1", SyncEngine.Resolution.KEEP_BOTH); settle()
        assertEquals(listOf(Triple(RecordKind.ROUTE, "copy-1", SyncEngine.Resolution.KEEP_BOTH)), r.resolutions)
        val failing = rig(ConflictResolver { _, _, _ -> error("no") })
        failing.model.resolve("copy-1", SyncEngine.Resolution.KEEP_MINE); settle()
        assertEquals("The conflict could not be settled.", failing.state.error)
    }

    // -- Routes of the open set --------------------------------------------------------------------------------

    @Test
    fun `a drawn route is in the list with its facts, and is the one held`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val routes = r.state.open!!.routes
        assertEquals(listOf("ROUTE 1", "ROUTE 2"), routes.map { it.name })
        assertEquals(listOf("#FF453A", "#0A84FF"), routes.map { it.color })
        assertEquals(2, routes[0].pointCount)
        assertEquals(2, routes[0].routePointCount)
        assertTrue(routes[0].visible)
        assertNotNull(routes[0].summary)
        assertTrue(routes[0].summary!!.contains(" nm · "))
        assertEquals(listOf(false, true), routes.map { it.selected })                        // the one just finished is the one open
    }

    @Test
    fun `a route with fewer than two route points has no summary`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.addAtCrosshair(a); r.model.finishDrawing(); settle()
        val id = r.state.open!!.routes.single().id
        r.session.edit("Shape") { set -> set.mapRoute(id) { route -> route.copy(points = route.points.map { it.copy(kind = "shaping") }) } }
        settle()
        assertNull(r.state.open!!.routes.single().summary)
        assertEquals(0, r.state.open!!.routes.single().routePointCount)
    }

    @Test
    fun `choosing a route holds it, and choosing it again puts it down`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val first = r.state.open!!.routes[0].id
        r.model.selectRoute(first); settle()
        assertEquals(listOf(true, false), r.state.open!!.routes.map { it.selected })
        r.model.selectRoute(first); settle()
        assertEquals(listOf(false, false), r.state.open!!.routes.map { it.selected })
        r.model.selectRoute("nope"); settle()
        assertNull(r.selection.held.value)
    }

    @Test
    fun `hiding and showing a route is an edit that can be undone`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val first = r.state.open!!.routes[0].id
        assertFalse(r.state.open!!.canRedo)
        r.model.toggleVisible(first); settle()
        assertFalse(r.state.open!!.routes[0].visible)
        assertTrue(r.state.open!!.routes[1].visible)
        assertTrue(r.state.open!!.canUndo)
        assertFalse(r.state.open!!.canRedo)
        r.model.undo(); settle()
        assertTrue(r.state.open!!.routes[0].visible)
        assertTrue(r.state.open!!.canRedo)
        r.model.redo(); settle()
        assertFalse(r.state.open!!.routes[0].visible)
    }

    @Test
    fun `a route is renamed in capitals, and a blank name is refused`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val first = r.state.open!!.routes[0].id
        r.model.renameRoute(first, " ingress "); settle()
        assertEquals("INGRESS", r.state.open!!.routes[0].name)
        r.model.renameRoute(first, ""); settle()
        assertEquals("A route needs a name.", r.state.error)
        assertEquals("INGRESS", r.state.open!!.routes[0].name)
    }

    @Test
    fun `deleting a route removes it, puts it down if it was held, and can be undone`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val second = r.state.open!!.routes[1].id                                              // held: it was just drawn
        r.model.deleteRoute(second); settle()
        assertEquals(listOf("ROUTE 1"), r.state.open!!.routes.map { it.name })
        assertNull(r.selection.held.value)
        r.model.undo(); settle()
        assertEquals(2, r.state.open!!.routes.size)
    }

    @Test
    fun `undoing the drawing of the route that is held puts it down, rather than holding what is not there`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        assertNotNull(r.selection.held.value)
        r.model.undo(); settle()
        assertEquals(1, r.state.open!!.routes.size)
        assertNull(r.selection.held.value)
        assertTrue(r.state.open!!.routes.none { it.selected })
    }

    @Test
    fun `a route held on another route stays held when something else is undone`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val first = r.state.open!!.routes[0].id
        r.model.toggleVisible(first); settle()
        r.model.selectRoute(first); settle()
        r.model.undo(); settle()
        assertEquals(first, r.selection.held.value!!.routeId)
    }

    // -- Drawing -----------------------------------------------------------------------------------------------

    @Test
    fun `with no set open there is nothing to draw on, and trying says so`() = runTest(dispatcher) {
        val r = rig()
        r.model.startDrawing(); settle()
        assertEquals("Open a set of routes first.", r.state.error)
        assertNull(r.state.drawing)
    }

    @Test
    fun `drawing counts the points, can be undone point by point, and finishes at two`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing(); settle()
        assertEquals(RouteDrawingUi(0, false), r.state.drawing)
        r.model.addAtCrosshair(a); settle()
        assertEquals(RouteDrawingUi(1, false), r.state.drawing)
        r.model.finishDrawing(); settle()
        assertEquals("A route needs at least 2 points.", r.state.error)
        r.model.addAtCrosshair(b); r.model.addAtCrosshair(c); settle()
        assertEquals(RouteDrawingUi(3, true), r.state.drawing)
        assertNull(r.state.error)                                                             // a point added clears the last complaint
        r.model.undoPoint(); settle()
        assertEquals(RouteDrawingUi(2, true), r.state.drawing)
        r.model.finishDrawing(); settle()
        assertNull(r.state.drawing)
        assertEquals(1, r.state.open!!.routes.size)
        assertEquals(3 - 1, r.state.open!!.routes.single().pointCount)
    }

    @Test
    fun `adding at the crosshair with no map yet says to move the map`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing()
        r.model.addAtCrosshair(null); settle()
        assertEquals("Move the map to where the point should go first.", r.state.error)
        assertEquals(0, r.state.drawing!!.points)
    }

    @Test
    fun `a point that is not a position is refused`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing()
        r.model.addAtCrosshair(LatLon(95.0, 0.0)); settle()
        assertEquals("That point could not be added.", r.state.error)
    }

    @Test
    fun `cancelling forgets the drawing and any complaint`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.finishDrawing(); settle()
        assertNotNull(r.state.error)
        r.model.cancelDrawing(); settle()
        assertNull(r.state.drawing)
        assertNull(r.state.error)
        assertEquals(0, r.state.open!!.routes.size)
    }

    @Test
    fun `a draft that belongs to another set is not shown on this one`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); settle()
        assertNotNull(r.state.drawing)
        r.model.createSet("TWO"); settle()
        assertNull(r.state.drawing)
    }

    @Test
    fun `the sync state of the open set shows on its card`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        assertEquals(SyncStatus.PENDING, r.state.open!!.sync)
        assertNull(r.state.open!!.conflictOf)
        r.device.sync(); settle()
        assertEquals(SyncStatus.SYNCED, r.state.open!!.sync)
        assertEquals(SyncStatus.SYNCED, r.state.sets.single().sync)
    }
}
