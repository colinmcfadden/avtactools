package app.ezpztac.workspace

import app.ezpztac.data.ActiveAircraftChoice
import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.DrawingMode
import app.ezpztac.data.HandoffFormat
import app.ezpztac.data.InMemoryOpenRouteSetStore
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.MasterProfileStore
import app.ezpztac.data.MissionTemplate
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.PackFacts
import app.ezpztac.data.PlanningApi
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetView
import app.ezpztac.data.PointSetViewStore
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteExport
import app.ezpztac.data.RouteHandoffExport
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RoutePlanning
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.LatLon
import app.ezpztac.model.PointSet
import app.ezpztac.model.PointSets
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SetPoint
import app.ezpztac.model.Wind
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.WindQuestion
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.PlanDraft
import app.ezpztac.planning.PointDraft
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertSame
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

    private class FakeApi : PlanningApi {
        var elevationsAnswer: suspend (List<LatLon>) -> List<Double?> = { points -> points.map { 1000.0 } }
        var windsAnswer: suspend (List<WindQuestion>) -> Map<String, PointWindDto> = { emptyMap() }
        override suspend fun elevations(points: List<LatLon>) = elevationsAnswer(points)
        override suspend fun routeWinds(points: List<WindQuestion>) = windsAnswer(points)
    }

    private class Rig(scope: TestScope, resolver: ConflictResolver?, val pack: FakeOpenPack = FakeOpenPack()) {
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        val server = FakeServer()
        val device = Device("A", server)
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler(), StandardTestDispatcher(scope.testScheduler))
        val session = RouteSession(repository, scope.backgroundScope, pack.routeStore)
        val selection = RouteSelection()
        val profiles = AircraftProfiles(
            device.store as InMemorySyncStore, Store(), { emptyList() }, Choice(),
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler(),
        )
        val mode = DrawingMode()
        val sketching = RouteSketching(session, profiles, mode)
        val openStore = InMemoryOpenRouteSetStore()
        val openSets = OpenRouteSets(openStore)
        val api = FakeApi()
        val pointSets = PointSetRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val pointViews = PointSetViews(object : PointSetViewStore {
            override fun load() = emptyMap<String, PointSetView>()

            override fun save(views: Map<String, PointSetView>) {}
        })
        var template: () -> ByteArray = { Fixtures.repoBytes("frontend/public/msnx_template.msnx") }
        val model = RoutesViewModel(
            repository, session, selection, sketching, resolver ?: ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) },
            RoutePlanning(api, session), RouteExport { template() }, RouteHandoffExport(), LocalPoints(pointSets, pointViews), openSets, pack,
        ).also { it.worker = StandardTestDispatcher(scope.testScheduler) }
        val shared = mutableListOf<ExportFile>()
        val notices = mutableListOf<String>()
        init {
            pack.opener = { session.open(it) }
            pack.closer = { session.closeIfOpen(it) }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.exports.collect { shared += it } }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }
        val state get() = model.state.value
    }

    private fun TestScope.rig(resolver: ConflictResolver? = null) = Rig(this, resolver).also { advanceUntilIdle() }

    @Test
    fun `the panel's actions pass on the frame's way to add a copy to a pack, and offer none without it`() = runTest {
        val r = rig()
        val add: (String, String) -> Unit = { _, _ -> }
        assertSame(add, actionsOf(r.model, addToPack = add).addToPack)
        assertNull(actionsOf(r.model).addToPack)
    }

    // -- In a Mission Pack (P2b) ------------------------------------------------------------------------------------------------

    @Test
    fun `in a pack the panel is the pack's route sets, and the person's own are set aside`() = runTest {
        val r = rig()
        val own = r.repository.create("NIGHT INFIL")
        r.model.openSet(own.id)
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(
            listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 2), FakeOpenPack.routes("rt-2", "OP DK EGRESS", routes = 1)),
            people = listOf(FakeOpenPack.here(2, "Sam Bell", "rt-2")),
        )
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("OP DK · 2 route sets · live", s.pack!!.head)
        assertEquals(listOf("OP DK INGRESS" to "2 routes · 6 points", "OP DK EGRESS" to "1 route · 3 points"), s.pack.rows.map { it.name to it.summary })
        assertEquals("Sam B. has it open", s.pack.rows[1].presence)
        assertTrue("Synced is what most rows would say: left unsaid", s.pack.rows[0].chips.isEmpty())
        assertTrue(s.alsoOpen.isEmpty())
    }

    @Test
    fun `in an empty pack a sketch makes the pack a set, taken out of it again if the sketch ends with no route`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        assertEquals(listOf("createRouteSet"), r.pack.log)
        assertEquals("pack:p:rt-new", r.session.active.value?.id)
        assertNotNull(r.state.drawing)
        assertTrue("nothing made in the Library", r.model.state.value.sets.isEmpty())
        r.model.cancelDrawing()
        advanceUntilIdle()
        assertEquals(listOf("createRouteSet", "remove rt-new"), r.pack.log)
    }

    @Test
    fun `a set a sketch made in a pack stays once it has a route, and one someone renamed stays too`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        r.model.addAtCrosshair(LatLon(34.0, -84.0))
        r.model.addAtCrosshair(LatLon(34.01, -84.01))
        r.model.finishDrawing("RED 1")
        advanceUntilIdle()
        assertEquals(listOf("createRouteSet"), r.pack.log)
        // A second empty pack, a sketch, and the set renamed meanwhile (by whoever): it is not taken away.
        val s = rig()
        s.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        s.model.startSketch()
        advanceUntilIdle()
        s.session.edit("Rename set") { it.copy(name = "OP DK INGRESS") }
        s.model.cancelDrawing()
        advanceUntilIdle()
        assertEquals(listOf("createRouteSet"), s.pack.log)
    }

    @Test
    fun `in a pack with sets a sketch with none open is drawn into the set last on the map, else the first, and makes none`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1), FakeOpenPack.routes("rt-2", "OP DK EGRESS", routes = 1)))
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        assertEquals(listOf("open rt-1"), r.pack.log)
        assertEquals("pack:p:rt-1", r.session.active.value?.id)
        assertNotNull(r.state.drawing)
        r.model.cancelDrawing()
        advanceUntilIdle()
        r.model.openSet("pack:p:rt-2")
        advanceUntilIdle()
        r.model.close("pack:p:rt-2")
        advanceUntilIdle()
        r.pack.log.clear()
        r.model.startSketch()
        advanceUntilIdle()
        assertEquals("the one last on the map", listOf("open rt-2"), r.pack.log)
    }

    @Test
    fun `a set made for a sketch in a pack that something else took the map's taps from meanwhile is taken out again`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        r.pack.whileMaking = { r.mode.begin(app.ezpztac.data.DrawingMode.Kind.BOUNDARY) }
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        assertEquals("Finish or cancel the boundary first.", r.state.error)
        assertEquals(listOf("createRouteSet", "remove rt-new"), r.pack.log)
    }

    @Test
    fun `a set the pack would not make is said once, by the pack, never as the panel's banner too`() = runTest {
        val r = rig()
        r.pack.madeSet = null
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        r.model.createSet("")
        advanceUntilIdle()
        assertEquals(listOf("createRouteSet", "createRouteSet"), r.pack.log)
        assertNull(r.state.error)
        assertNull(r.state.drawing)
    }

    @Test
    fun `in a pack the person's own sets are not opened, and closing one of theirs opens nothing else of theirs`() = runTest {
        val r = rig()
        val a = r.repository.create("NIGHT INFIL")
        val b = r.repository.create("DAY EXFIL")
        r.model.openSet(a.id)
        advanceUntilIdle()
        r.model.openSet(b.id)
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        r.model.close(b.id)
        advanceUntilIdle()
        assertNull("NIGHT INFIL is not brought into the pack", r.session.active.value)
        r.model.openSet(a.id)
        advanceUntilIdle()
        assertEquals(RoutesViewModel.OWN_SETS_IN_PACK, r.state.error)
        assertNull(r.session.active.value)
    }

    @Test
    fun `an open pack set is the pack's on its card, never named into the Library, takes any name, and stays the pack's when unlisted`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)), people = listOf(FakeOpenPack.here(2, "Sam Bell", "rt-1")))
        advanceUntilIdle()
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        val open = checkNotNull(r.state.open)
        assertEquals(InPackUi("OP DK", "Saved to OP DK as you work. 4 members can see it.", "Sam B. is also here", false), open.inPack)
        assertFalse(open.canSave)
        assertEquals(listOf("Synced"), open.chips.map { it.text })
        assertEquals(1, r.state.pack!!.count)
        assertTrue(r.state.pack!!.rows.isEmpty())
        r.model.saveAs("NIGHT INFIL")
        advanceUntilIdle()
        assertEquals(RoutesViewModel.SET_SAVED_TO_PACK, r.state.error)
        r.model.renameSet("pack:p:rt-1", "SKETCHED ROUTES")
        advanceUntilIdle()
        assertEquals("SKETCHED ROUTES", r.session.active.value?.name)
        assertTrue("nothing named into the Library", r.state.sets.isEmpty())
        r.pack.state.value = FakeOpenPack.open(emptyList())
        advanceUntilIdle()
        assertNotNull(r.state.open?.inPack)
        assertFalse(r.state.open!!.canSave)
    }

    @Test
    fun `in a finished pack nothing is drawn or added, and its set is looked at, shared and hidden but never changed`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)), session = FakeOpenPack.finished())
        advanceUntilIdle()
        r.model.startSketch()
        advanceUntilIdle()
        assertEquals(PackFacts.NOTHING_NEW, r.state.error)
        assertTrue("nothing opened or made for the sketch", r.pack.log.isEmpty())
        r.model.createSet("")
        advanceUntilIdle()
        assertTrue(r.pack.log.isEmpty())
        assertEquals(MapPointAction.Locked("Off in a finished pack"), r.model.pointAction())
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        val open = checkNotNull(r.state.open)
        val locked = "OP DK is finished: nothing in it can be changed."
        assertEquals(locked, open.locked)
        assertTrue(open.inPack!!.savedLine.startsWith("Finished by Sam B. on Oct "))
        assertTrue(r.state.pack!!.readOnly)
        assertEquals("This pack is finished: you can look at everything in it and export its routes, but nothing can be changed.", r.state.pack!!.hint)
        val routeId = open.routes.single().id
        r.model.selectRoute(routeId)
        advanceUntilIdle()
        val before = r.session.active.value
        r.model.renameSet("pack:p:rt-1", "OP DK EGRESS")
        r.model.renameRoute(routeId, "BLUE 1")
        r.model.addShapingPoint(routeId, LatLon(34.0, -84.0))
        r.model.addPointAt(LatLon(34.0, -84.0))
        r.model.deleteRoute(routeId)
        advanceUntilIdle()
        assertEquals("nothing changed", before, r.session.active.value)
        assertEquals(locked, r.state.error)
        assertEquals(routeId, r.selection.held.value?.routeId)
        var asked = 0
        r.api.windsAnswer = { asked++; emptyMap() }
        r.model.fetchWinds()
        advanceUntilIdle()
        assertNull("asked of nobody", r.state.fetching)
        assertEquals(0, asked)
        assertFalse(r.state.open!!.canUndo)
        // A route hidden is the person's own: it stays theirs to do.
        r.model.toggleVisible(routeId)
        advanceUntilIdle()
        assertFalse(r.session.active.value!!.routes.single().visible)
    }

    @Test
    fun `in a finished pack a typed change to a route's plan is refused at its field, and one that changes nothing says nothing`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)), session = FakeOpenPack.finished())
        advanceUntilIdle()
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        val routeId = r.state.open!!.routes.single().id
        r.model.selectRoute(routeId)
        advanceUntilIdle()
        val plan = checkNotNull(r.state.detail).plan
        assertNull("the same values again: no change, no words", r.model.applyPlan(routeId, plan, plan))
        assertEquals("OP DK is finished: nothing in it can be changed.", r.model.applyPlan(routeId, plan.copy(tempC = "22"), plan))
    }

    @Test
    fun `in a finished pack a point is not moved, a step is not undone, and a route being drawn takes no more points`() = runTest {
        val r = rig()
        val route = app.ezpztac.model.SketchRoute(
            id = "r1", name = "RED 1", color = "#FF453A", plan = app.ezpztac.model.RoutePlan(),
            points = listOf(
                app.ezpztac.model.RoutePoint(id = "a", lat = 34.7, lon = -84.1, kind = app.ezpztac.model.RoutePoint.KIND_AMPS, ptType = "turn", name = ".SP"),
                app.ezpztac.model.RoutePoint(id = "b", lat = 34.8, lon = -84.1, kind = app.ezpztac.model.RoutePoint.KIND_AMPS, ptType = "turn", name = ".RP"),
            ),
        )
        val item = FakeOpenPack.routesOf("rt-1", app.ezpztac.model.RouteSet(id = "pack:p:rt-1", name = "OP DK INGRESS", routes = listOf(route)))
        r.pack.state.value = FakeOpenPack.open(listOf(item))
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        r.model.renameRoute("r1", "BLUE 1")                                                     // while it took changes: a step undo could take back
        r.model.startSketch()
        r.model.addAtCrosshair(LatLon(34.0, -84.0))
        advanceUntilIdle()
        r.pack.state.value = FakeOpenPack.open(listOf(item), session = FakeOpenPack.finished())
        advanceUntilIdle()
        val locked = "OP DK is finished: nothing in it can be changed."
        val before = r.session.active.value
        r.model.nudgePoint("r1", "a", 100.0, 0.0)
        r.model.pointToCrosshair("r1", "a", LatLon(34.5, -84.5))
        assertEquals(locked, r.model.pointToText("r1", "a", "16S GC 28864 55349"))
        r.model.renamePoint("r1", "a", ".TGT")
        r.model.undo()
        r.model.redo()
        advanceUntilIdle()
        assertEquals(before, r.session.active.value)
        assertEquals("BLUE 1", r.session.active.value!!.routes.single().name)
        assertEquals(locked, r.state.error)
        r.model.addAtCrosshair(LatLon(34.1, -84.1))
        r.model.finishDrawing("RED 2")
        advanceUntilIdle()
        assertEquals("the draft took no more", 1, r.sketching.draft.value?.points?.size)
        assertEquals(1, r.session.active.value!!.routes.size)
    }

    @Test
    fun `a pack opening says so in place of its route sets`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(emptyList(), session = null, status = app.ezpztac.missionpacks.PackStatus.LOADING)
        advanceUntilIdle()
        assertEquals("Opening…", r.state.pack!!.head)
        assertEquals("Opening the pack…", r.state.pack!!.opening)
    }

    @Test
    fun `a pack's set is opened and closed in the pack, and is removed from the Pack panel, never deleted here`() = runTest {
        val r = rig()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)))
        advanceUntilIdle()
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        r.model.close("pack:p:rt-1")
        advanceUntilIdle()
        assertEquals(listOf("open rt-1", "close rt-1"), r.pack.log)
        r.model.deleteSet("pack:p:rt-1")
        advanceUntilIdle()
        assertEquals("This set is the pack's: Remove from pack, in the Pack panel, takes it out for everyone.", r.model.state.value.error)
        r.pack.opens = false
        r.model.openSet("pack:p:rt-1")
        advanceUntilIdle()
        assertEquals("That set is no longer in the pack.", r.model.state.value.error)
    }

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
    fun `a new set with no name takes the made-up one, or what the person typed in capitals, and is opened`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        assertEquals("SKETCHED ROUTES", r.state.open!!.name)
        assertTrue(r.state.open!!.madeUpName)
        r.model.createSet("  night run "); settle()
        assertEquals("NIGHT RUN", r.state.open!!.name)
        assertFalse(r.state.open!!.madeUpName)
        assertEquals(listOf("NIGHT RUN", "SKETCHED ROUTES"), r.state.sets.map { it.name }.sorted())
        assertEquals(listOf(true, false), r.state.sets.sortedBy { it.name }.map { it.isOpen })                // NIGHT RUN is on the map, the other is also open
        r.model.createSet(""); settle()
        assertEquals("SKETCHED ROUTES 2", r.state.open!!.name)                                     // the first free made-up name
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

    // -- The held route: plan, nav log, points -----------------------------------------------------------------

    /** A set with one route of four points (target, ip, ip, target) that has been finished, so it is held, on a date of its own. */
    private fun TestScope.openWithPlannedRoute(r: Rig): String {
        r.model.createSet(""); settle()
        r.model.startDrawing()
        for (at in listOf(a, b, c, LatLon(34.73, -84.07))) r.model.addAtCrosshair(at)
        r.model.finishDrawing(); settle()
        val id = r.state.open!!.routes.single().id
        val plan = PlanDraft.of(r.session.active.value!!.route(id)!!.plan)
        r.model.applyPlan(id, plan.copy(date = "2026-10-03"), plan); settle()
        return id
    }

    private fun Rig.route(id: String) = session.active.value!!.route(id)!!

    @Test
    fun `no route held, no detail`() = runTest(dispatcher) {
        val r = rig()
        assertNull(r.state.detail)
        r.model.createSet(""); settle()
        assertNull(r.state.detail)
    }

    @Test
    fun `the held route has its plan, its nav log and its totals`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val detail = r.state.detail!!
        assertEquals(id, detail.routeId)
        assertEquals("ROUTE 1", detail.name)
        assertEquals("UH-60L Black Hawk", detail.aircraft)
        assertEquals(PlanDraft.of(r.route(id).plan), detail.plan)
        assertEquals("2026-10-03", detail.plan.date)
        assertEquals(listOf(".TGT", ".SP", ".RP", ".TGT"), detail.points.map { it.name })
        assertEquals(listOf(true, false, false, false), detail.points.map { it.first })
        assertEquals("START", detail.points[0].facts)
        assertTrue(detail.points[1].facts, detail.points[1].facts.matches(Regex("""\d+\.\d nm · \d{3}°T · \d+ kt.*""")))
        assertEquals("0:00", detail.points[0].elapsed)
        assertEquals(detail.points.map { it.clock }, List(4) { "--:--:--" })                  // no clock set anywhere
        assertFalse(detail.totals!!.startsWith("Total"))                                      // the page says "Total" itself
        assertTrue(detail.totals.contains(" nm · "))
        assertTrue(detail.totals.endsWith(" lb"))
        assertEquals(0, detail.shapingPoints)
        assertEquals(emptyList<String>(), detail.warnings)
        assertFalse(detail.hasElevations)
    }

    @Test
    fun `a route with one named point says what is wrong, and has no totals`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.session.edit("Shape") { set -> set.mapRoute(id) { route -> route.copy(points = route.points.mapIndexed { i, p -> if (i >= 1) p.copy(kind = "shaping") else p }) } }
        settle()
        assertNull(r.state.detail!!.totals)
        assertEquals(listOf("Route needs at least two route points."), r.state.detail!!.warnings)
        assertEquals(3, r.state.detail!!.shapingPoints)
        assertEquals(0, r.state.detail!!.points.size)                                          // the planner lists no points until there are two to run between
    }

    @Test
    fun `applying the plan changes the route, is one undo step, and says nothing when it is good`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val before = r.state.open!!.canUndo
        val draft = r.state.detail!!.plan.copy(airspeedValue = "120", airspeedType = "true", altitudeValue = "300", altitudeRef = "msl", windDir = "270", windSpeed = "15", tempC = "5", fuelFlowLbHr = "2000")
        assertNull(r.model.applyPlan(id, draft, r.state.detail!!.plan)); settle()
        val plan = r.route(id).plan
        assertEquals(Airspeed(120.0, "true"), plan.airspeed)
        assertEquals(AltitudeSetting(300.0, "msl"), plan.altitude)
        assertEquals(Wind(270.0, 15.0), plan.wind)
        assertEquals(5.0, plan.tempC)
        assertEquals(2000.0, plan.fuelFlowLbHr)
        assertEquals(draft, r.state.detail!!.plan)                                           // and the form comes back as it was typed
        r.model.undo(); settle()
        assertEquals(Airspeed(100.0, "ground"), r.route(id).plan.airspeed)
        assertTrue(before)
    }

    @Test
    fun `a plan that is not good is refused in words, and nothing changes`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val plan = r.route(id).plan
        assertEquals("Wind speed is not a number.", r.model.applyPlan(id, r.state.detail!!.plan.copy(windSpeed = "gale"), r.state.detail!!.plan)); settle()
        assertEquals(plan, r.route(id).plan)
        assertNull(r.state.error)                                                             // the words go to the form, not to the banner
        assertEquals("That route is no longer here.", r.model.applyPlan("nope", r.state.detail!!.plan, r.state.detail!!.plan))
    }

    @Test
    fun `typing a route's own clock anchors the times, and the nav log follows`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val target = r.state.detail!!.points[3]
        val typed = target.values.copy(clock = "12:30:00")
        assertNull(r.model.applyPoint(id, target.id, typed, target.values, first = false)); settle()
        val log = r.state.detail!!.points
        assertEquals("12:30:00", log[3].clock)
        assertTrue(log[3].hasClock)
        assertTrue(log[0].clock, log[0].clock.matches(Regex("""\d\d:\d\d:\d\d""")))         // the others are timed back from it
        assertFalse(log[0].hasClock)
        assertEquals("12:30:00", r.route(id).plan.perPoint.getValue(target.id).clock)
    }

    @Test
    fun `an altitude typed for a point is that point's own, and the others are not touched`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1]
        assertNull(r.model.applyPoint(id, second.id, second.values.copy(altitudeValue = "250", altitudeRef = "msl"), second.values, first = false)); settle()
        assertEquals(setOf(second.id), r.route(id).plan.perPoint.keys)
        assertEquals(AltitudeSetting(250.0, "msl"), r.route(id).plan.perPoint.getValue(second.id).altitude)
        assertNull(r.route(id).plan.perPoint.getValue(second.id).airspeed)                    // only what was changed
        assertEquals("250", r.state.detail!!.points[1].values.altitudeValue)
        assertEquals("50", r.state.detail!!.points[2].values.altitudeValue)
    }

    @Test
    fun `applying a point that was not changed is not an edit`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val depth = r.session.undoDepth.value
        val second = r.state.detail!!.points[1]
        assertNull(r.model.applyPoint(id, second.id, second.values, second.values, first = false)); settle()
        assertEquals(depth, r.session.undoDepth.value)
    }

    @Test
    fun `a point that is not good is refused in words and the route is left alone`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1]
        val plan = r.route(id).plan
        assertEquals("Airspeed is not a number.", r.model.applyPoint(id, second.id, second.values.copy(speedValue = "fast", clock = "12:30"), second.values, first = false))
        assertEquals("The time must be written hours:minutes, like 09:15 or 09:15:30.", r.model.applyPoint(id, second.id, second.values.copy(clock = "noon"), second.values, first = false))
        assertEquals(plan, r.route(id).plan)                                                  // not even the clock that was fine
        assertEquals("That route is no longer here.", r.model.applyPoint("nope", second.id, second.values, second.values, first = false))
    }

    @Test
    fun `a first point's speed and wind are not applied`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val first = r.state.detail!!.points[0]
        assertNull(r.model.applyPoint(id, first.id, first.values.copy(altitudeValue = "75", speedValue = "banana"), first.values, first = true)); settle()
        assertEquals(AltitudeSetting(75.0, "agl"), r.route(id).plan.perPoint.getValue(first.id).altitude)
        assertNull(r.route(id).plan.perPoint.getValue(first.id).airspeed)
    }

    @Test
    fun `tapping a point's row holds it, and tapping it again puts it down`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        r.model.selectPoint(second); settle()
        assertEquals(listOf(false, true, false, false), r.state.detail!!.points.map { it.held })
        assertEquals(RouteHeld(id, second), r.selection.held.value)
        r.model.selectPoint(second); settle()
        assertEquals(listOf(false, false, false, false), r.state.detail!!.points.map { it.held })
        r.selection.clear()
        r.model.selectPoint(second); settle()                                                   // with no route held there is nothing to hold it on
        assertNull(r.selection.held.value)
    }

    @Test
    fun `a point is renamed in capitals, and can be left with no name`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        r.model.renamePoint(id, second, ".ip one"); settle()
        assertEquals(".IP ONE", r.state.detail!!.points[1].name)
        r.model.renamePoint(id, second, ""); settle()
        assertEquals("", r.state.detail!!.points[1].name)
    }

    // -- Naming a route point after a local point ----------------------------------------------------------------------------

    /** Imports local points, then waits for the sets to reach the view model. */
    private suspend fun TestScope.withLocalPoints(r: Rig, points: List<SetPoint>, name: String = "LOCAL") {
        r.device.repository.create(RecordKind.POINT_SET, name, PointSets.serialize(PointSet("set-1", null, name, points)))
        settle()
    }

    private fun local(name: String, lat: Double, lon: Double, elevationFt: Double? = null) = SetPoint("id-$name", name, "", "", "", elevationFt, lat, lon)

    @Test
    fun `a name that is a local point's puts the route point on it, with its charted elevation`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        withLocalPoints(r, listOf(local("BLUE 1", 34.9, -84.9, 1730.0)))
        val second = r.state.detail!!.points[1].id
        val depth = r.session.undoDepth.value

        r.model.renamePoint(id, second, "blue 1"); settle()
        val moved = r.route(id).points.single { it.id == second }
        assertEquals("BLUE 1", moved.name)
        assertEquals(34.9, moved.lat, 0.0)
        assertEquals(-84.9, moved.lon, 0.0)
        assertEquals(1730.0, moved.chartElevationFt)
        assertEquals(depth + 1, r.session.undoDepth.value)                                   // the rename and the move are one step

        r.model.undo(); settle()
        val back = r.route(id).points.single { it.id == second }
        assertEquals(".SP", back.name)
        assertNull(back.chartElevationFt)
        assertTrue(back.lat != 34.9)
    }

    @Test
    fun `a leading dot of what is typed is ignored, but only one`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        withLocalPoints(r, listOf(local("BLUE 1", 34.9, -84.9, 100.0), local(".DOTTED", 34.8, -84.8, 200.0)))
        val second = r.state.detail!!.points[1].id

        r.model.renamePoint(id, second, ".blue 1"); settle()
        assertEquals(34.9, r.route(id).points.single { it.id == second }.lat, 0.0)
        assertEquals(".BLUE 1", r.route(id).points.single { it.id == second }.name)           // the name is kept as typed, in capitals

        r.model.renamePoint(id, second, ".DOTTED"); settle()                                  // the dot is stripped from what is typed, never from a point's own name
        assertEquals(".DOTTED", r.route(id).points.single { it.id == second }.name)
        assertEquals(34.9, r.route(id).points.single { it.id == second }.lat, 0.0)            // so it is not found: the point stays where it was
        r.model.renamePoint(id, second, "..DOTTED"); settle()
        assertEquals(34.8, r.route(id).points.single { it.id == second }.lat, 0.0)
    }

    @Test
    fun `a name that is no local point's only renames, and leaves the position and the elevation alone`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        withLocalPoints(r, listOf(local("BLUE 1", 34.9, -84.9, 100.0)))
        val second = r.state.detail!!.points[1]
        val before = r.route(id).points.single { it.id == second.id }
        r.model.renamePoint(id, second.id, "NOWHERE"); settle()
        val after = r.route(id).points.single { it.id == second.id }
        assertEquals("NOWHERE", after.name)
        assertEquals(before.lat, after.lat, 0.0)
        assertEquals(before.lon, after.lon, 0.0)
        assertEquals(before.chartElevationFt, after.chartElevationFt)
    }

    @Test
    fun `a local point with no elevation clears the elevation the route point had snapped to before`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        withLocalPoints(r, listOf(local("HIGH", 34.9, -84.9, 900.0), local("PLAIN", 34.8, -84.8, null)))
        val second = r.state.detail!!.points[1].id
        r.model.renamePoint(id, second, "high"); settle()
        assertEquals(900.0, r.route(id).points.single { it.id == second }.chartElevationFt)
        r.model.renamePoint(id, second, "plain"); settle()
        assertNull(r.route(id).points.single { it.id == second }.chartElevationFt)           // as the web's rename does: it passes no elevation, and the old one goes
    }

    @Test
    fun `a set that is hidden on the map still counts for a name`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        withLocalPoints(r, listOf(local("BLUE 1", 34.9, -84.9, 100.0)))
        for (row in r.pointSets.observe().first()) r.pointViews.toggle(row.uuid)               // hidden on the map
        settle()
        assertFalse(r.pointSets.observe().first().isEmpty())
        assertTrue(r.pointViews.views.value.values.none { it.visible })                       // really hidden: nothing is shown
        val second = r.state.detail!!.points[1].id
        r.model.renamePoint(id, second, "blue 1"); settle()
        assertEquals(34.9, r.route(id).points.single { it.id == second }.lat, 0.0)
    }

    @Test
    fun `with no local points a rename is only a rename, as it always was`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1]
        val before = r.route(id).points.single { it.id == second.id }
        r.model.renamePoint(id, second.id, ".ip one"); settle()
        val after = r.route(id).points.single { it.id == second.id }
        assertEquals(".IP ONE", after.name)
        assertEquals(before.lat, after.lat, 0.0)
    }

    @Test
    fun `what a name would do is told before it is saved, and not for a name that matches none`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        assertNull(r.model.localPointNamed("blue 1"))                                         // no local points yet
        withLocalPoints(r, listOf(local("BLUE 1", 34.9, -84.9, 1730.0), local("NOELEV", 34.8, -84.8, null)))
        val match = r.model.localPointNamed("blue 1")!!
        assertEquals(LatLon(34.9, -84.9), match.at)
        assertEquals(1730.0, match.chartElevationFt)
        assertEquals("BLUE 1", match.name)
        assertNull(r.model.localPointNamed("nope"))
        assertNull(r.model.localPointNamed(""))
        assertNull(r.model.localPointNamed("noelev")!!.chartElevationFt)
    }

    @Test
    fun `a named point changes type`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        r.model.setPointType(id, second, "turn"); settle()
        assertEquals("turn", r.state.detail!!.points[1].ptType)
        assertEquals(".SP", r.state.detail!!.points[1].name)                                    // its name is its own
    }

    @Test
    fun `a named point can be made to only shape the line, until two are left`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val log = r.state.detail!!.points
        r.model.makeShaping(id, log[1].id); settle()
        assertEquals(3, r.state.detail!!.points.size)
        assertEquals(1, r.state.detail!!.shapingPoints)
        r.model.makeShaping(id, log[2].id); settle()                                            // three left: one more can go
        assertEquals(2, r.state.detail!!.points.size)
        r.model.makeShaping(id, log[0].id); settle()                                            // two left: refused, and said
        assertEquals("A route needs at least two named points.", r.state.error)
        assertEquals(2, r.state.detail!!.points.size)
    }

    @Test
    fun `a point that only shaped the line becomes a named point`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val log = r.state.detail!!.points
        r.model.makeShaping(id, log[1].id); settle()
        r.model.makeNamed(id, log[1].id); settle()
        assertEquals(4, r.state.detail!!.points.size)
        assertEquals("turn", r.state.detail!!.points[1].ptType)
        assertEquals(0, r.state.detail!!.shapingPoints)
    }

    @Test
    fun `a held point that only shapes the line is offered as one`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val log = r.state.detail!!.points
        r.model.makeShaping(id, log[1].id); settle()
        assertNull(r.state.detail!!.heldShaping)
        r.selection.holdPoint(log[1].id); settle()
        val held = r.state.detail!!.heldShaping!!
        assertEquals(log[1].id, held.id)
        assertTrue(held.held)
        assertEquals(log[1].grid, held.grid)                                                    // it is where it was, whether it is named or not
        assertEquals(listOf(false, false, false), r.state.detail!!.points.map { it.held })      // a named row is not the held one
    }

    // -- Moving and adding points ------------------------------------------------------------------------------

    private fun Rig.where(routeId: String, pointId: String) = route(routeId).points.single { it.id == pointId }.let { LatLon(it.lat, it.lon) }

    @Test
    fun `a point is nudged in feet, north and east, each nudge one undo step`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        val start = r.where(id, second)
        val depth = r.session.undoDepth.value

        r.model.nudgePoint(id, second, 100.0, 0.0); settle()
        val (north, east) = GraphicEdits.metresBetween(start, r.where(id, second))
        assertEquals(30.48, north, 0.01)                                                        // 100 ft
        assertEquals(0.0, east, 0.01)

        r.model.nudgePoint(id, second, 0.0, -200.0); settle()
        val (north2, east2) = GraphicEdits.metresBetween(start, r.where(id, second))
        assertEquals(30.48, north2, 0.01)
        assertEquals(-60.96, east2, 0.02)                                                       // 200 ft west
        assertEquals(depth + 2, r.session.undoDepth.value)

        r.model.undo(); r.model.undo(); settle()
        assertEquals(start, r.where(id, second))
    }

    @Test
    fun `a nudge moves only that point and changes the grid it is read at`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val log = r.state.detail!!.points
        r.model.nudgePoint(id, log[1].id, 500.0, 500.0); settle()
        val after = r.state.detail!!.points
        assertEquals(log[0].grid, after[0].grid)
        assertEquals(log[2].grid, after[2].grid)
        assertTrue(after[1].grid != log[1].grid)
        assertTrue(after[1].grid, after[1].grid.matches(Regex("""\d\d[A-Z] [A-Z]{2} \d{5} \d{5}""")))
    }

    @Test
    fun `a nudge of a point that is not there, or in a route that is not, does nothing`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val depth = r.session.undoDepth.value
        r.model.nudgePoint(id, "nope", 100.0, 0.0)
        r.model.nudgePoint("no route", r.state.detail!!.points[0].id, 100.0, 0.0)
        settle()
        assertEquals(depth, r.session.undoDepth.value)
        assertNull(r.state.error)
    }

    @Test
    fun `a nudge forgets the ground elevation fetched for the old place, and no other`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val ids = r.state.detail!!.points.map { it.id }
        r.session.edit("Elevations") { set -> set.mapRoute(id) { it.copy(elevations = ids.associateWith { 1000.0 }) } }; settle()
        r.model.nudgePoint(id, ids[1], 50.0, 0.0); settle()
        assertEquals(ids.filter { it != ids[1] }.toSet(), r.route(id).elevations.keys)
        assertTrue(r.state.detail!!.hasElevations)                                              // the others still have theirs
        r.model.undo(); settle()
        assertEquals(ids.toSet(), r.route(id).elevations.keys)                                  // undo brings back what the move took away
    }

    @Test
    fun `a point is put at the crosshair, and with no crosshair the person is told to move the map`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        r.model.pointToCrosshair(id, second, null); settle()
        assertEquals("Move the map to where the point should go first.", r.state.error)

        val crosshair = LatLon(34.7512, -84.0512)
        r.model.pointToCrosshair(id, second, crosshair); settle()
        assertEquals(crosshair, r.where(id, second))
        assertTrue(r.state.detail!!.points[1].grid.startsWith("16S "))                          // the grid it is now read at is the crosshair's
        assertNull(r.state.error)
    }

    @Test
    fun `a point is moved to a grid or to a coordinate that was typed, and what is not understood is said and leaves it alone`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val second = r.state.detail!!.points[1].id
        val start = r.where(id, second)

        assertNull(r.model.pointToText(id, second, "34.75, -84.05")); settle()
        assertEquals(34.75, r.where(id, second).lat, 1e-9)
        assertEquals(-84.05, r.where(id, second).lon, 1e-9)

        val moved = r.where(id, second)
        val words = r.model.pointToText(id, second, "not a place at all"); settle()
        assertNotNull(words)
        assertTrue(words!!.isNotBlank())
        assertEquals(moved, r.where(id, second))
        assertTrue(start != moved)

        val grid = r.state.detail!!.points[1].grid
        assertNull(r.model.pointToText(id, second, grid)); settle()                              // its own grid, as it is shown: a place it can be sent back to
    }

    @Test
    fun `a shaping point is added at the crosshair on the nearest leg, and held`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val ids = r.state.detail!!.points.map { it.id }
        r.model.newPointId = { "shape-1" }
        r.model.addShapingPoint(id, LatLon(34.705, -84.0970)); settle()                         // beside the first leg (a to b)
        val points = r.route(id).points
        assertEquals(listOf(ids[0], "shape-1", ids[1], ids[2], ids[3]), points.map { it.id })
        assertEquals(RoutePoint.KIND_SHAPING, points[1].kind)
        assertEquals(RouteHeld(id, "shape-1"), r.selection.held.value)
        assertEquals("shape-1", r.state.detail!!.heldShaping!!.id)
        assertEquals(1, r.state.detail!!.shapingPoints)
        assertEquals(4, r.state.detail!!.points.size)                                           // the nav log is still the named ones

        r.model.undo(); settle()
        assertEquals(ids, r.route(id).points.map { it.id })
    }

    @Test
    fun `a shaping point needs a crosshair and a leg to bend`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val depth = r.session.undoDepth.value
        r.model.addShapingPoint(id, null); settle()
        assertEquals("Move the map to where the point should go first.", r.state.error)
        assertEquals(depth, r.session.undoDepth.value)

        r.model.addShapingPoint(id, LatLon(34.705, -84.0970)); settle()                          // a crosshair arrives: the complaint goes
        assertNull(r.state.error)
        val added = r.session.undoDepth.value
        assertEquals(depth + 1, added)

        r.model.addShapingPoint("no route", a); settle()                                         // a route that is not there: nothing to bend, nothing said
        assertEquals(added, r.session.undoDepth.value)
        assertNull(r.state.error)
    }

    @Test
    fun `a shaping point that is added can be moved, and named when it is made a route point`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.model.newPointId = { "shape-2" }
        r.model.addShapingPoint(id, LatLon(34.705, -84.0970)); settle()
        r.model.nudgePoint(id, "shape-2", 0.0, 100.0); settle()
        val east = GraphicEdits.metresBetween(LatLon(34.705, -84.0970), r.where(id, "shape-2")).second
        assertEquals(30.48, east, 0.02)
        r.model.makeNamed(id, "shape-2"); settle()
        assertEquals(5, r.state.detail!!.points.size)
        assertNull(r.state.detail!!.heldShaping)
    }

    @Test
    fun `an altitude typed above the planner's reach is refused, and an elevation changes the MSL shown`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val first = r.state.detail!!.points[0]
        assertEquals("Altitude must be between -2000 and 30000.", r.model.applyPoint(id, first.id, first.values.copy(altitudeValue = "40000"), first.values, first = true))
        val withoutGround = r.state.detail!!.points[1].facts
        r.session.edit("Elevations") { set -> set.mapRoute(id) { route -> route.copy(elevations = route.points.mapNotNull { it.id }.associateWith { 1000.0 }) } }; settle()
        assertTrue(r.state.detail!!.hasElevations)
        assertTrue(r.state.detail!!.points[1].facts, r.state.detail!!.points[1].facts.endsWith("' MSL"))
        assertFalse(withoutGround.endsWith("' MSL") && withoutGround == r.state.detail!!.points[1].facts)
    }

    // -- Winds and elevations ---------------------------------------------------------------------------------

    private fun wind(dir: Int, speed: Double, source: String) = PointWindDto(dir, speed, false, 15.0, "KXXX", source, 2.0)

    @Test
    fun `fetching winds shows it is busy, merges the answer, and says what was found`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val ids = r.state.detail!!.points.map { it.id }
        val held = CompletableDeferred<Map<String, PointWindDto>>()
        r.api.windsAnswer = { held.await() }
        r.model.fetchWinds(); settle()
        assertEquals(PlanningKind.WINDS, r.state.fetching)
        assertNull(r.state.note)
        held.complete(mapOf(ids[0] to wind(270, 12.0, "METAR"), ids[1] to wind(280, 14.0, "TAF"))); settle()
        assertNull(r.state.fetching)
        assertEquals(PlanningNote("2 points · 1 METAR, 1 TAF", failed = false), r.state.note)
        assertEquals(270.0, r.route(id).plan.perPoint.getValue(ids[0]).wind!!.dirTrue, 0.0)
    }

    @Test
    fun `winds or elevations on their way to a set whose pack is finished meanwhile are not applied, and the note says why`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val planned = r.session.active.value!!.copy(id = "pack:p:rt-1", name = "OP DK INGRESS")
        val item = FakeOpenPack.routesOf("rt-1", planned)
        r.pack.state.value = FakeOpenPack.open(listOf(item))
        r.model.openSet("pack:p:rt-1"); settle()
        r.selection.select(id); settle()                                                        // held, as it was in the Library's copy
        val ids = r.state.detail!!.points.map { it.id }
        val winds = CompletableDeferred<Map<String, PointWindDto>>()
        r.api.windsAnswer = { winds.await() }
        r.model.fetchWinds(); settle()
        assertEquals(PlanningKind.WINDS, r.state.fetching)
        val before = r.session.active.value
        r.pack.state.value = FakeOpenPack.open(listOf(item), session = FakeOpenPack.finished()); settle()
        assertNull(r.state.fetching)
        assertEquals(PlanningNote("OP DK is finished: nothing in it can be changed.", failed = true, PlanningKind.WINDS), r.state.note)
        winds.complete(mapOf(ids[0] to wind(270, 12.0, "METAR"))); settle()
        assertSame("nothing applied", before, r.session.active.value)
        assertEquals(PlanningNote("OP DK is finished: nothing in it can be changed.", failed = true, PlanningKind.WINDS), r.state.note)
        // Elevations, as a viewer.
        r.pack.state.value = FakeOpenPack.open(listOf(item)); settle()
        val ground = CompletableDeferred<List<Double?>>()
        r.api.elevationsAnswer = { ground.await() }
        r.model.fetchElevations(); settle()
        assertEquals(PlanningKind.ELEVATIONS, r.state.fetching)
        r.pack.state.value = FakeOpenPack.open(listOf(item), session = FakeOpenPack.viewing()); settle()
        ground.complete(List(4) { 1000.0 }); settle()
        assertNull(r.state.fetching)
        assertEquals(PlanningNote("You can view OP DK but not change it.", failed = true, PlanningKind.ELEVATIONS), r.state.note)
        assertFalse(r.state.detail!!.hasElevations)
    }

    @Test
    fun `a fetch for the person's own set is not ended by a pack being finished`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)))
        val ground = CompletableDeferred<List<Double?>>()
        r.api.elevationsAnswer = { ground.await() }
        r.model.fetchElevations(); settle()
        r.pack.state.value = FakeOpenPack.open(listOf(FakeOpenPack.routes("rt-1", "OP DK INGRESS", routes = 1)), session = FakeOpenPack.finished()); settle()
        assertEquals(PlanningKind.ELEVATIONS, r.state.fetching)
        ground.complete(List(4) { 1000.0 }); settle()
        assertEquals(PlanningNote("Ground elevations for 4 points.", failed = false, PlanningKind.ELEVATIONS), r.state.note)
    }

    @Test
    fun `fetching elevations marks the route as having them`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        assertFalse(r.state.detail!!.hasElevations)
        r.model.fetchElevations(); settle()
        assertEquals(PlanningNote("Ground elevations for 4 points.", failed = false, PlanningKind.ELEVATIONS), r.state.note)
        assertTrue(r.state.detail!!.hasElevations)
    }

    @Test
    fun `a fetch that fails says so in the app's words, and can be dismissed`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        r.api.windsAnswer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        r.model.fetchWinds(); settle()
        assertEquals(PlanningNote("There is no connection to the server, so the winds could not be fetched.", failed = true), r.state.note)
        r.model.dismissNote(); settle()
        assertNull(r.state.note)
    }

    @Test
    fun `only one thing is fetched at a time`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        val held = CompletableDeferred<Map<String, PointWindDto>>()
        var elevationAsks = 0
        r.api.windsAnswer = { held.await() }
        r.api.elevationsAnswer = { points -> elevationAsks++; points.map { 1.0 } }
        r.model.fetchWinds(); settle()
        r.model.fetchElevations(); settle()
        assertEquals(0, elevationAsks)
        assertEquals(PlanningKind.WINDS, r.state.fetching)
        held.complete(emptyMap()); settle()
        assertEquals("No reporting stations were found near this route.", r.state.note!!.text)
        r.model.fetchElevations(); settle()
        assertEquals(1, elevationAsks)
    }

    @Test
    fun `with no route held there is nothing to fetch for`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        var asked = 0
        r.api.windsAnswer = { asked++; emptyMap() }
        r.model.fetchWinds(); settle()
        r.model.fetchElevations(); settle()
        assertEquals(0, asked)
        assertNull(r.state.fetching)
        assertNull(r.state.note)
    }

    @Test
    fun `what a fetch found is shown under the route it was for, and not under another`() = runTest(dispatcher) {
        val r = rig()
        val first = openWithPlannedRoute(r)
        r.model.fetchElevations(); settle()
        assertNotNull(r.state.note)
        r.model.startDrawing()
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
        r.model.finishDrawing(); settle()                                                          // the new route is the one held
        assertNull(r.state.note)
        r.model.selectRoute(first); settle()
        assertNotNull(r.state.note)                                                                // and is still there for the first
    }

    @Test
    fun `a fetch that is not asked of a route that is gone is not applied`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val held = CompletableDeferred<List<Double?>>()
        r.api.elevationsAnswer = { held.await() }
        r.model.fetchElevations(); settle()
        r.model.deleteRoute(id); settle()
        held.complete(listOf(1.0, 2.0, 3.0, 4.0)); settle()
        assertEquals(0, r.state.open!!.routes.size)
        assertNull(r.state.fetching)
    }

    // -- Export for AMPS ----------------------------------------------------------------------------------------

    @Test
    fun `exporting the set builds a mission named for its routes and hands it over once`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        val work = kotlinx.coroutines.test.TestCoroutineScheduler()                          // the building waits here until it is let go
        r.model.worker = StandardTestDispatcher(work)
        r.model.exportSet()
        testScheduler.runCurrent()
        assertTrue(r.state.exporting)
        assertTrue(r.shared.isEmpty())
        work.advanceUntilIdle()
        settle()
        assertFalse(r.state.exporting)
        val file = r.shared.single()
        assertEquals("ROUTE 1.msnx", file.fileName)
        assertTrue(file.bytes.size > 1000)
        assertEquals('P'.code.toByte(), file.bytes[0])                                        // a zip: it starts "PK"
        assertEquals('K'.code.toByte(), file.bytes[1])
        assertNull(r.state.error)
        assertNull(r.state.exportWarning)
    }

    @Test
    fun `one route can be exported on its own, named for it`() = runTest(dispatcher) {
        val r = rig()
        val first = openWithPlannedRoute(r)
        r.model.startDrawing()
        for (at in listOf(a, b)) r.model.addAtCrosshair(at)
        r.model.finishDrawing(); settle()
        r.model.exportRoute(first); settle()
        assertEquals("ROUTE 1.msnx", r.shared.single().fileName)
        r.model.exportSet(); settle()
        assertEquals("ROUTE 1_ROUTE 2.msnx", r.shared.last().fileName)
    }

    // -- A route for another app -------------------------------------------------------------------------------

    @Test
    fun `one route is shared as a GPX of all its points, named for it`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.model.shareRoute(id, HandoffFormat.GPX); settle()
        val file = r.shared.single()
        assertEquals("ROUTE 1.gpx".replace(' ', '_'), file.fileName)
        val text = String(file.bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\""))
        val route = r.session.active.value!!.route(id)!!
        assertEquals(route.points.size, Regex("<rtept ").findAll(text).count())                  // every point, shaping points included
        assertNull(r.state.error)
        assertFalse(r.state.exporting)
    }

    @Test
    fun `a Garmin flight plan is made the same way, with its own extension`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.model.shareRoute(id, HandoffFormat.FPL); settle()
        val file = r.shared.single()
        assertEquals("ROUTE_1.fpl", file.fileName)
        assertTrue(String(file.bytes, Charsets.UTF_8).contains("<flight-plan xmlns=\"http://www8.garmin.com/xmlschemas/FlightPlan/v1\">"))
    }

    @Test
    fun `sharing waits on the work and takes no second tap meanwhile`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val work = kotlinx.coroutines.test.TestCoroutineScheduler()
        r.model.worker = StandardTestDispatcher(work)
        r.model.shareRoute(id, HandoffFormat.GPX)
        testScheduler.runCurrent()
        assertTrue(r.state.exporting)
        r.model.shareRoute(id, HandoffFormat.FPL)                                                // a second tap while one is being built does nothing
        r.model.exportRoute(id)
        testScheduler.runCurrent()                                                               // (so that a second job, if there were one, would have started and be waiting too)
        work.advanceUntilIdle(); settle()
        assertEquals(1, r.shared.size)
        assertFalse(r.state.exporting)
    }

    @Test
    fun `a route that is no longer in the set is said so, and nothing is shared`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        r.model.shareRoute("gone", HandoffFormat.GPX); settle()
        assertEquals("That route is no longer in this set.", r.state.error)
        assertTrue(r.shared.isEmpty())
        assertFalse(r.state.exporting)
    }

    @Test
    fun `with no set open there is nothing to share`() = runTest(dispatcher) {
        val r = rig()
        r.model.shareRoute("anything", HandoffFormat.GPX); settle()
        assertTrue(r.shared.isEmpty())
        assertNull(r.state.error)
    }

    @Test
    fun `a route with no points is refused by name`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.session.edit("Clear") { set -> set.mapRoute(id) { it.copy(points = emptyList()) } }
        r.model.shareRoute(id, HandoffFormat.GPX); settle()
        assertEquals("ROUTE 1 has no points to share.", r.state.error)
        assertTrue(r.shared.isEmpty())
    }

    @Test
    fun `a warning from an earlier mission export is not carried over, and sharing leaves the mission export working`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.session.edit("Airframe") { set -> set.mapRoute(id) { it.copy(plan = it.plan.copy(aircraft = "CH-47F Chinook", aircraftProfile = "ch47f")) } }
        r.model.exportSet(); settle()
        assertTrue(r.state.exportWarning != null)
        r.model.shareRoute(id, HandoffFormat.GPX); settle()
        assertNull(r.state.exportWarning)                                                          // that warning was about the mission, not this file
        r.model.exportSet(); settle()
        assertEquals("ROUTE 1.msnx", r.shared.last().fileName)
    }

    @Test
    fun `a route planned for another airframe is exported with a warning that can be dismissed`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.session.edit("Airframe") { set -> set.mapRoute(id) { it.copy(plan = it.plan.copy(aircraft = "CH-47F Chinook", aircraftProfile = "ch47f")) } }
        r.model.exportSet(); settle()
        assertTrue(r.state.exportWarning!!.startsWith("This mission will open in AMPS as a UH-60L, not a CH-47F Chinook."))
        assertEquals(1, r.shared.size)                                                         // the file is made all the same
        r.model.dismissExportWarning(); settle()
        assertNull(r.state.exportWarning)
    }

    @Test
    fun `a route with too few named points is refused by name, and nothing is handed over`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        r.session.edit("Shape") { set -> set.mapRoute(id) { route -> route.copy(points = route.points.mapIndexed { i, p -> if (i >= 1) p.copy(kind = "shaping") else p }) } }
        r.model.exportSet(); settle()
        assertEquals("ROUTE 1 needs at least two named points before it can be exported.", r.state.error)
        assertTrue(r.shared.isEmpty())
        assertFalse(r.state.exporting)
    }

    @Test
    fun `a template that will not open is a refusal and not a crash`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        r.template = { "not a mission".toByteArray() }
        r.model.exportSet(); settle()
        assertTrue(r.state.error!!.startsWith("The mission could not be built"))
        assertTrue(r.shared.isEmpty())
        r.template = { error("the asset is gone") }
        r.model.exportSet(); settle()
        assertEquals("The mission could not be built.", r.state.error)
        assertFalse(r.state.exporting)
    }

    @Test
    fun `with no set open, or no routes in it, there is nothing to export`() = runTest(dispatcher) {
        val r = rig()
        r.model.exportSet(); settle()
        assertTrue(r.shared.isEmpty())
        r.model.createSet(""); settle()
        r.model.exportSet(); settle()
        assertEquals("There is no route to export.", r.state.error)
        assertTrue(r.shared.isEmpty())
    }

    @Test
    fun `a second export while one is being built is ignored`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        val work = kotlinx.coroutines.test.TestCoroutineScheduler()
        r.model.worker = StandardTestDispatcher(work)
        r.model.exportSet()
        testScheduler.runCurrent()
        r.model.exportSet()                                                                    // while the first is still being built
        testScheduler.runCurrent()
        work.advanceUntilIdle()
        settle()
        assertEquals(1, r.shared.size)
    }

    // -- An imported mission ---------------------------------------------------------------------------------------

    private val missionBytes = Fixtures.bytes("msnx/template.msnx")

    /** The template mission, brought in and open: its file is the saved document. */
    private suspend fun TestScope.openMission(r: Rig): String {
        val importer = app.ezpztac.data.MissionImporter(r.repository, r.session, app.ezpztac.data.MapFocus())
        importer.import(missionBytes, app.ezpztac.formats.MsnxReader.read(missionBytes), "Template.msnx")
        settle()
        return r.state.open!!.uuid
    }

    @Test
    fun `an imported mission is listed and opened as a mission, and its route is held for planning`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        assertTrue(r.state.open!!.isMission)
        assertTrue(r.state.sets.single { it.uuid == id }.isMission)
        r.model.selectRoute(r.state.open!!.routes.single().id); settle()
        assertTrue(r.state.detail!!.isMission)
    }

    @Test
    fun `routes cannot be drawn, renamed or removed in a mission, and the person is told why`() = runTest(dispatcher) {
        val r = rig()
        openMission(r)
        val route = r.state.open!!.routes.single()
        r.model.startDrawing(); settle()
        assertTrue(r.state.error!!.contains("part of its file"))
        assertNull(r.state.drawing)
        r.model.dismissError()
        r.model.renameRoute(route.id, "OTHER"); settle()
        assertEquals(route.name, r.state.open!!.routes.single().name)
        assertTrue(r.state.error!!.contains("part of its file"))
        r.model.dismissError()
        r.model.deleteRoute(route.id); settle()
        assertEquals(1, r.state.open!!.routes.size)
        assertFalse(r.state.open!!.canUndo)                                    // none of that was an edit
    }

    @Test
    fun `a point's type is the file's, so changing it is refused`() = runTest(dispatcher) {
        val r = rig()
        openMission(r)
        val route = r.session.active.value!!.routes.single()
        val named = route.points.first { it.kind == RoutePoint.KIND_AMPS }
        r.model.setPointType(route.id, named.id!!, "target"); settle()
        assertTrue(r.state.error!!.contains("fixed in the mission"))
        r.model.dismissError()
        r.model.makeShaping(route.id, named.id!!); settle()
        assertTrue(r.state.error!!.contains("fixed in the mission"))
        assertEquals(route.points, r.session.active.value!!.routes.single().points)
    }

    @Test
    fun `moving and renaming a point of a mission are written into its file`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        val route = r.session.active.value!!.routes.single()
        val named = route.points.first { it.kind == RoutePoint.KIND_AMPS }
        r.model.nudgePoint(route.id, named.id!!, northFt = 300.0, eastFt = 0.0); settle()
        r.model.renamePoint(route.id, named.id!!, "BASE"); settle()
        r.session.flush()
        val back = app.ezpztac.formats.MsnxReader.read(r.repository.missionFile(id)!!).routes.single().points.first { it.id == named.id }
        assertTrue(back.lat > named.lat)
        assertEquals(".BASE".removePrefix("."), back.name!!.removePrefix("."))
    }

    @Test
    fun `adding a point puts a named point on the line, which is held, and the file gets a point and a leg`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        val route = r.session.active.value!!.routes.single()
        val at = LatLon((route.points[10].lat + route.points[11].lat) / 2, (route.points[10].lon + route.points[11].lon) / 2)
        r.model.newPointId = { "99999999-aaaa-4bbb-8ccc-dddddddddddd" }
        r.model.addShapingPoint(route.id, at); settle()
        val now = r.session.active.value!!.routes.single()
        assertEquals(route.points.size + 1, now.points.size)
        val added = now.points.single { it.id == "99999999-aaaa-4bbb-8ccc-dddddddddddd" }
        assertEquals(RoutePoint.KIND_AMPS, added.kind)
        assertEquals(".NEWPT", added.name)
        r.session.flush()
        val file = app.ezpztac.formats.MsnxReader.read(r.repository.missionFile(id)!!).routes.single()
        assertEquals(route.points.size + 1, file.points.size)
    }

    @Test
    fun `exporting a mission shares its own file with the changes in it, named as the web names it`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        val route = r.session.active.value!!.routes.single()
        val named = route.points.first { it.kind == RoutePoint.KIND_AMPS }
        r.model.nudgePoint(route.id, named.id!!, northFt = 500.0, eastFt = 0.0); settle()
        r.model.exportSet(); settle()
        assertNull(r.state.error)
        val sent = r.shared.single()
        assertEquals("Template_edited.msnx", sent.fileName)
        // The very file that is stored, with the move already in it: not one rebuilt from a template.
        assertTrue(r.repository.missionFile(id)!!.contentEquals(sent.bytes))
        val moved = app.ezpztac.formats.MsnxReader.read(sent.bytes).routes.single().points.first { it.id == named.id }
        assertTrue(moved.lat > named.lat)
    }

    @Test
    fun `a conflict on a mission is settled as a mission`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        r.model.resolve(id, SyncEngine.Resolution.KEEP_BOTH); settle()
        assertEquals(listOf(Triple(RecordKind.MISSION, id, SyncEngine.Resolution.KEEP_BOTH)), r.resolutions)
        r.model.createSet("A SET"); settle()
        val set = r.state.open!!.uuid
        r.model.resolve(set, SyncEngine.Resolution.KEEP_THEIRS); settle()
        assertEquals(RecordKind.ROUTE, r.resolutions.last().first)
    }

    @Test
    fun `renaming an open mission keeps its file as it is`() = runTest(dispatcher) {
        val r = rig()
        val id = openMission(r)
        r.model.renameSet(id, "goat run"); settle()
        assertEquals("GOAT RUN", r.state.open!!.name)
        assertTrue(r.repository.missionFile(id)!!.contentEquals(missionBytes))
    }

    // -- The session: the sets open now --------------------------------------------------------------------------------

    @Test
    fun `every set opened joins the session, and the others are also open in the order they were opened`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        r.model.createSet("TWO"); settle()
        r.model.createSet("THREE"); settle()
        assertEquals("THREE", r.state.open!!.name)
        assertEquals(listOf("ONE", "TWO"), r.state.alsoOpen.map { it.name })
        assertEquals(3, r.state.openCount)
        assertEquals(r.state.sets.map { it.uuid }.toSet(), r.openStore.kept.toSet())                // kept on the device
        val one = r.state.alsoOpen.first().uuid
        r.model.openSet(one); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertEquals(listOf("TWO", "THREE"), r.state.alsoOpen.map { it.name })                     // its place in the session is kept
    }

    @Test
    fun `closing the set on the map takes it out of the session, keeps it in the Library, and opens the one before`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        r.model.createSet("TWO"); settle()
        val two = r.state.open!!.uuid
        r.model.close(two); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertEquals(emptyList<RouteSetRow>(), r.state.alsoOpen)
        assertTrue(r.state.sets.any { it.uuid == two })                                          // nothing is deleted
        assertFalse(two in r.openSets.ids.value)
        r.model.closeSet(); settle()
        assertNull(r.state.open)
        assertEquals(0, r.state.openCount)
        assertEquals(2, r.state.sets.size)
    }

    @Test
    fun `closing a set that is only also open leaves the map alone`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        val one = r.state.open!!.uuid
        r.model.createSet("TWO"); settle()
        r.model.close(one); settle()
        assertEquals("TWO", r.state.open!!.name)
        assertEquals(emptyList<RouteSetRow>(), r.state.alsoOpen)
    }

    @Test
    fun `deleting the set on the map opens the one before, and it leaves the session`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        r.model.createSet("TWO"); settle()
        val two = r.state.open!!.uuid
        r.model.deleteSet(two); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertFalse(two in r.openSets.ids.value)
        assertEquals(listOf("ONE"), r.state.sets.map { it.name })
    }

    @Test
    fun `a set in the session that has gone is let go when the next one is looked for`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        val one = r.state.open!!.uuid
        r.model.createSet("TWO"); settle()
        r.repository.delete(one); settle()                                                       // deleted on another device, say
        r.model.closeSet(); settle()
        assertNull(r.state.open)
        assertEquals(emptyList<String>(), r.openSets.ids.value)
    }

    @Test
    fun `every set is listed, and one closed is put back on the map when it is opened again`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        val one = r.state.open!!.uuid
        r.model.close(one); settle()
        assertEquals(listOf("ONE"), r.state.sets.map { it.name })
        r.model.openSet(one); settle()
        assertEquals(RoutesPage.HOME, r.state.page)
        assertEquals("ONE", r.state.open!!.name)
        assertTrue(one in r.openSets.ids.value)                                                  // back in the session
    }

    @Test
    fun `a set's chips say whether the server has it, and an imported mission says so too`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        assertEquals(listOf(LzChip("Waiting to sync", ChipTone.WARNING)), r.state.open!!.chips)
        assertEquals(listOf(LzChip("Imported", ChipTone.INFO, app.ezpztac.designsystem.EzpzIcons.download), LzChip("Synced", ChipTone.SUCCESS)), routeSetChips(SyncStatus.SYNCED, null, isMission = true))
        assertEquals(listOf(LzChip("Conflict copy", ChipTone.ERROR)), routeSetChips(SyncStatus.PENDING, "other", isMission = false))
        assertEquals(listOf(LzChip("Waiting to sync", ChipTone.WARNING)), routeSetChips(null, null, isMission = false))
    }

    @Test
    fun `a set's line counts its routes and points, and a route's line has its fuel`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        assertEquals("2 routes · 4 points", r.state.open!!.summary)
        r.session.flush(); settle()                                                              // the list is the stored record's, written after a pause
        assertEquals("2 routes · 4 points", r.state.sets.single().summary)
        val route = r.state.open!!.routes.first()
        assertEquals(route.summary, route.meta)
        assertTrue(route.summary!!.endsWith(" lb"))                                              // the fuel, as the web's row says it
        r.model.createSet("TWO"); settle()
        assertEquals("0 routes · 0 points", r.state.open!!.summary)
    }

    // -- The map menu's Add route point here -------------------------------------------------------------------------------

    @Test
    fun `add route point here with nothing open starts a route there, in a new set, with that point down`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(MapPointAction.StartRoute, r.model.pointAction())
        r.model.addPointAt(a); settle()
        assertEquals("SKETCHED ROUTES", r.state.open!!.name)
        assertEquals(listOf(a), r.sketching.draft.value!!.points.map { LatLon(it.lat, it.lon) })
        r.model.addAtCrosshair(b)
        r.model.finishDrawing(); settle()
        assertEquals(listOf(a, b), r.session.active.value!!.routes.single().points.map { LatLon(it.lat, it.lon) })
    }

    @Test
    fun `add route point here in an empty set of sketched routes starts a route in it`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        assertEquals(MapPointAction.StartRoute, r.model.pointAction())
        r.model.addPointAt(a); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertEquals(1, r.state.sets.size)
        assertEquals(1, r.sketching.draft.value!!.points.size)
    }

    @Test
    fun `add route point here puts a named turn point at the end of the only route, or the one held, and holds it, as one step`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        r.selection.clear()
        val only = r.session.active.value!!.routes.single()
        assertEquals(MapPointAction.AddTo(only.name), r.model.pointAction())
        val depth = r.session.undoDepth.value
        r.model.newPointId = { "end-1" }
        val far = LatLon(34.80, -84.00)
        r.model.addPointAt(far); settle()
        val last = r.session.active.value!!.routes.single().points.last()
        assertEquals("end-1", last.id)
        assertEquals(far, LatLon(last.lat, last.lon))
        assertEquals(app.ezpztac.model.RoutePoint.KIND_AMPS, last.kind)
        assertEquals("turn", last.ptType)
        assertEquals(".CP", last.name)
        assertEquals(app.ezpztac.data.RouteHeld(only.id, "end-1"), r.selection.held.value)
        assertEquals(depth + 1, r.session.undoDepth.value)
        assertEquals("Added a point to the end of ${only.name}.", r.notices.last())

        // Two routes: the one held takes it; none held, the person is asked to choose.
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        val second = r.session.active.value!!.routes.last()
        r.selection.select(second.id)
        assertEquals(MapPointAction.AddTo(second.name), r.model.pointAction())
        r.selection.clear()
        assertEquals(MapPointAction.ChooseRoute, r.model.pointAction())
        val before = r.session.active.value
        r.model.addPointAt(far); settle()
        assertEquals(before, r.session.active.value)
        assertNotNull(r.state.error)
    }

    @Test
    fun `add route point here is refused in an imported mission, whose routes are its file's`() = runTest(dispatcher) {
        val r = rig()
        openWithMission(r)
        assertEquals(MapPointAction.Mission, r.model.pointAction())
        val before = r.session.active.value
        r.model.addPointAt(a); settle()
        assertEquals(before, r.session.active.value)
        assertNull(r.sketching.draft.value)
        assertNotNull(r.state.error)
    }

    // -- Sketching from the panel ---------------------------------------------------------------------------------------

    @Test
    fun `sketching with nothing open makes a set with the made-up name, in the session, and drawing starts`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch(); settle()
        assertEquals("SKETCHED ROUTES", r.state.open!!.name)
        assertNotNull(r.state.drawing)
        assertTrue(r.state.open!!.uuid in r.openSets.ids.value)
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
        r.model.finishDrawing(); settle()
        assertEquals(1, r.state.open!!.routes.size)
        assertTrue(r.state.open!!.canSave)
    }

    @Test
    fun `cancelling the first sketch in a set made for it takes the empty set away, and the set before comes back`() = runTest(dispatcher) {
        val r = rig()
        openWithMission(r)
        val mission = r.state.open!!.uuid
        r.model.startSketch(); settle()                                                         // a mission's routes are its file's: a new set
        val made = r.state.open!!.uuid
        assertFalse(made == mission)
        r.model.addAtCrosshair(a)
        r.model.cancelDrawing(); settle()
        assertEquals(mission, r.state.open!!.uuid)
        assertFalse(r.state.sets.any { it.uuid == made })
        assertFalse(made in r.openSets.ids.value)
        assertNull(r.state.drawing)
    }

    @Test
    fun `a set made for a sketch is kept once a route is finished in it, even if the next sketch is cancelled`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch(); settle()
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b)
        r.model.finishDrawing(); settle()
        val made = r.state.open!!.uuid
        r.model.startSketch(); settle()
        assertEquals(made, r.state.open!!.uuid)                                                 // into the set on the map
        r.model.cancelDrawing(); settle()
        assertEquals(made, r.state.open!!.uuid)
        assertEquals(1, r.state.open!!.routes.size)
    }

    @Test
    fun `sketching with a set of sketched routes open draws into it`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r, "ONE")
        r.model.startSketch(); settle()
        assertEquals("ONE", r.state.open!!.name)
        assertEquals(1, r.state.sets.size)
        assertNotNull(r.state.drawing)
        r.model.cancelDrawing(); settle()
        assertEquals(1, r.state.sets.size)                                                        // a set the person made is never taken away
    }

    @Test
    fun `a sketch refused because a boundary is being drawn makes no set`() = runTest(dispatcher) {
        val r = rig()
        assertNull(r.mode.begin(DrawingMode.Kind.BOUNDARY))
        r.model.startSketch(); settle()
        assertEquals("Finish or cancel the boundary first.", r.state.error)
        assertNull(r.state.open)
        assertEquals(emptyList<RouteSetRow>(), r.state.sets)
    }

    private suspend fun TestScope.openWithMission(r: Rig) = openMission(r)

    // -- Naming --------------------------------------------------------------------------------------------------------

    @Test
    fun `saving names the set in capitals, tells the person, and it is saved from then on`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch(); settle()
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        val uuid = r.state.open!!.uuid
        r.model.saveAs("  night infil "); settle()
        assertEquals("NIGHT INFIL", r.state.open!!.name)
        assertEquals("NIGHT INFIL", r.repository.open(uuid)!!.name)
        assertEquals(listOf("Saved “NIGHT INFIL” to your Library"), r.notices)
        assertFalse(r.state.open!!.madeUpName)
        assertFalse(r.state.open!!.canSave)
        r.model.undo(); settle()
        assertEquals("SKETCHED ROUTES", r.state.open!!.name)                                     // naming is a step of the set's own undo
    }

    @Test
    fun `a name another set has is kept apart, and the toast says the name it got`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("NIGHT INFIL"); settle()
        r.model.createSet(""); settle()
        r.model.saveAs("night infil"); settle()
        assertEquals("NIGHT INFIL 2", r.state.open!!.name)
        assertEquals("Saved “NIGHT INFIL 2” to your Library", r.notices.last())
    }

    @Test
    fun `replacing the set that has the name deletes it, and this one takes the name`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("NIGHT INFIL"); settle()
        val other = r.state.open!!.uuid
        r.model.createSet(""); settle()
        val mine = r.state.open!!.uuid
        r.model.saveAs("night infil", replacing = other); settle()
        assertEquals("NIGHT INFIL", r.state.open!!.name)
        assertEquals(listOf(mine), r.state.sets.map { it.uuid })
        assertFalse(other in r.state.alsoOpen.map { it.uuid })
        assertEquals("Saved \u201cNIGHT INFIL\u201d to your Library, in place of the one there before", r.notices.last())
    }

    @Test
    fun `a rename from the Library can replace the set that has the name, the one on the map included`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE"); settle()
        val one = r.state.open!!.uuid
        r.model.createSet("TWO"); settle()
        val two = r.state.open!!.uuid                                                               // on the map
        r.model.renameSet(one, "two", replacing = two); settle()
        assertEquals(listOf(one to "TWO"), r.state.sets.map { it.uuid to it.name })
        assertFalse(r.state.open?.uuid == two)                                                     // closed before it was deleted, so it cannot be saved back
    }

    @Test
    fun `an action asked for while the last one runs is said not to be done, rather than dropped`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("ONE")
        r.model.createSet("TWO")                                                                    // before the first is made
        settle()
        assertEquals(STILL_WORKING, r.state.error)
        assertEquals(listOf("ONE"), r.state.sets.map { it.name })
    }

    @Test
    fun `a mission is named for its file, so no name of its own is refused as made up`() = runTest(dispatcher) {
        val r = rig()
        r.device.repository.create(RecordKind.MISSION, "GOAT", app.ezpztac.model.RouteSets.missionSummary(emptyList()), uuid = "m1", file = app.ezpztac.sync.FilePart("GOAT.msnx", byteArrayOf(1)))
        settle()
        r.model.renameSet("m1", "sketched routes"); settle()
        assertNull(r.state.error)
        assertEquals("SKETCHED ROUTES", r.state.sets.single { it.uuid == "m1" }.name)
    }

    @Test
    fun `a made-up or blank name is refused in words, and nothing is named`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.saveAs("sketched routes 4"); settle()
        assertEquals(RouteSetNames.MADE_UP_REFUSED, r.state.error)
        r.model.saveAs("   "); settle()
        assertEquals("Give the routes a name.", r.state.error)
        assertEquals("SKETCHED ROUTES", r.state.open!!.name)
        assertEquals(emptyList<String>(), r.notices)
        val uuid = r.state.open!!.uuid
        r.model.renameSet(uuid, "Sketched Routes"); settle()
        assertEquals(RouteSetNames.MADE_UP_REFUSED, r.state.error)
    }

    @Test
    fun `Save is offered only for a set of sketched routes with its made-up name and a route to save`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        assertTrue(r.state.open!!.madeUpName)
        assertFalse(r.state.open!!.canSave)                                                     // nothing to save yet
        openMission(r)
        assertFalse(r.state.open!!.madeUpName)
        assertFalse(r.state.open!!.canSave)                                                     // a mission is named for its file
    }

    @Test
    fun `what saving keeps is counted in the sheet's words`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val lines = r.state.open!!.saveContents
        assertTrue(lines[0], lines[0].startsWith("2 routes, 4 points, ") && lines[0].endsWith(" nm"))
        assertEquals("Speeds, altitudes, winds, fuel flow and the start time", lines[1])
    }

    // -- The plan page ------------------------------------------------------------------------------------------------------

    @Test
    fun `opening a route's plan holds it, and Back leaves it held`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val route = r.state.open!!.routes[1].id
        r.model.openPlan(route); settle()
        assertTrue(r.state.planOpen)
        assertEquals(route, r.state.detail!!.routeId)
        r.model.closePlan(); settle()
        assertFalse(r.state.planOpen)
        assertEquals(route, r.selection.held.value!!.routeId)
        r.model.openPlan("nope"); settle()
        assertFalse(r.state.planOpen)
    }

    @Test
    fun `a point held on the map stays held when its route's plan opens from the held bar`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val point = r.state.detail!!.points[2].id
        r.selection.select(id, point); settle()
        r.model.openHeldPlan(); settle()
        assertTrue(r.state.planOpen)
        assertTrue(r.state.detail!!.points.single { it.id == point }.held)
    }

    @Test
    fun `the plan page goes with its route, undone out of existence or its set closed`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        r.model.openPlan(r.state.open!!.routes.single().id); settle()
        r.model.undo(); settle()                                                                // the route was drawn in that step
        assertFalse(r.state.planOpen)
        r.model.redo(); settle()
        r.model.openPlan(r.state.open!!.routes.single().id); settle()
        assertTrue(r.state.planOpen)
        r.model.closeSet(); settle()
        assertFalse(r.state.planOpen)
    }

    @Test
    fun `what is typed into one field in a row is one step to undo`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val point = r.state.detail!!.points[1]
        for (typed in listOf("3", "30", "300")) {
            val before = r.state.detail!!.points[1].values
            assertNull(r.model.applyPoint(id, point.id, before.copy(altitudeValue = typed), before, first = false, coalesce = "alt#1")); settle()
        }
        assertEquals(300.0, r.route(id).plan.perPoint.getValue(point.id).altitude!!.value, 0.0)
        r.model.undo(); settle()
        assertNull(r.route(id).plan.perPoint[point.id]?.altitude)                                // all of it, in one step
        val temp = r.state.detail!!.plan
        for (typed in listOf("2", "25")) {
            val before = r.state.detail!!.plan
            assertNull(r.model.applyPlan(id, before.copy(tempC = typed), before, coalesce = "temp#1")); settle()
        }
        assertEquals(25.0, r.route(id).plan.tempC)
        r.model.undo(); settle()
        assertEquals(temp, r.state.detail!!.plan)
    }

    @Test
    fun `a field of the plan changes that field alone`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val plan = r.route(id).plan
        val before = r.state.detail!!.plan
        assertNull(r.model.applyPlan(id, before.copy(fuelFlowLbHr = "1200"), before)); settle()
        assertEquals(plan.copy(fuelFlowLbHr = 1200.0), r.route(id).plan)
        val now = r.state.detail!!.plan
        assertNull(r.model.applyPlan(id, now, now)); settle()                                     // nothing typed, nothing changed
        assertEquals(plan.copy(fuelFlowLbHr = 1200.0), r.route(id).plan)
    }

    // -- Export with the threats ------------------------------------------------------------------------------------------

    @Test
    fun `an AMPS export carries whether the threats go with it, and a GPX never does`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        assertTrue(r.state.includeThreats)                                                      // on until the person says otherwise
        r.model.exportSet(withThreats = true); settle()
        assertTrue(r.shared.last().withThreats)
        r.model.exportRoute(id, withThreats = false); settle()
        assertFalse(r.shared.last().withThreats)
        r.model.shareRoute(id, HandoffFormat.GPX); settle()
        assertFalse(r.shared.last().withThreats)
        r.model.includeThreats(false); settle()
        assertFalse(r.state.includeThreats)
    }

    // -- What the review found ---------------------------------------------------------------------------------------------------

    @Test
    fun `a page that closed by itself does not open again when its route is held later`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet(""); settle()
        r.model.startDrawing(); r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        val route = r.state.open!!.routes.single().id
        r.model.openPlan(route); settle()
        r.model.undo(); settle()                                                                // the route goes, and the page with it
        assertFalse(r.state.planOpen)
        r.model.redo(); settle()
        r.selection.select(route); settle()                                                     // held on the map: the panel, not the page
        assertFalse(r.state.planOpen)
    }

    @Test
    fun `holding another route closes the page of the first`() = runTest(dispatcher) {
        val r = rig()
        openWithRoutes(r)
        val (one, two) = r.state.open!!.routes.map { it.id }
        r.model.openPlan(one); settle()
        r.selection.select(two); settle()
        assertFalse(r.state.planOpen)
        r.selection.select(one); settle()
        assertFalse(r.state.planOpen)                                                           // and holding the first again does not bring it back
    }

    @Test
    fun `two visits to one field are two steps to undo`() = runTest(dispatcher) {
        val r = rig()
        val id = openWithPlannedRoute(r)
        val start = r.state.detail!!.plan
        r.model.applyPlan(id, start.copy(tempC = "25"), start, coalesce = "temp:$id#a"); settle()
        val second = r.state.detail!!.plan
        r.model.applyPlan(id, second.copy(tempC = "30"), second, coalesce = "temp:$id#b"); settle()
        r.model.undo(); settle()
        assertEquals("25", r.state.detail!!.plan.tempC)
    }

    @Test
    fun `a second tap on Sketch a route while the first is making its set makes no second set`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch()
        r.model.startSketch()
        settle()
        assertEquals(1, r.state.sets.size)
        assertEquals(1, r.openSets.ids.value.size)
        assertNotNull(r.state.drawing)
    }

    @Test
    fun `a cancelled sketch puts back the set that was on the map, wherever it is in the session`() = runTest(dispatcher) {
        val r = rig()
        val mission = openMission(r)
        r.model.startSketch(); settle()                                                         // S1, made for this sketch
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        r.model.openSet(mission); settle()                                                      // the mission again; the session is [M, S1]
        r.model.startSketch(); settle()                                                         // S2
        r.model.cancelDrawing(); settle()
        assertEquals(mission, r.state.open!!.uuid)
        assertEquals(2, r.state.sets.size)
    }

    @Test
    fun `a set made for a sketch and named meanwhile is kept when the sketch is cancelled`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch(); settle()
        val made = r.state.open!!.uuid
        r.model.renameSet(made, "night infil"); settle()
        r.model.cancelDrawing(); settle()
        assertEquals(made, r.state.open!!.uuid)
        assertEquals("NIGHT INFIL", r.state.open!!.name)
    }

    @Test
    fun `a sketch dropped because another set was opened takes its empty set away, and leaves the other on the map`() = runTest(dispatcher) {
        val r = rig()
        r.model.createSet("OTHER"); settle()
        val other = r.state.open!!.uuid
        r.model.close(other); settle()
        r.model.startSketch(); settle()
        val made = r.state.open!!.uuid
        r.model.openSet(other); settle()
        r.sketching.dropDraftNotOn(other); settle()                                            // what the app does when another set opens
        assertEquals(other, r.state.open!!.uuid)
        assertFalse(r.state.sets.any { it.uuid == made })
        assertFalse(made in r.openSets.ids.value)
    }

    @Test
    fun `the marker goes with its sketch, so a later cancel in that set deletes nothing`() = runTest(dispatcher) {
        val r = rig()
        r.model.startSketch(); settle()
        val made = r.state.open!!.uuid
        r.model.addAtCrosshair(a); r.model.addAtCrosshair(b); r.model.finishDrawing(); settle()
        r.model.deleteRoute(r.state.open!!.routes.single().id); settle()                       // empty again, made-up name still
        r.model.startSketch(); settle()
        r.model.cancelDrawing(); settle()
        assertEquals(made, r.state.open!!.uuid)                                                // the person's set: never taken away
    }

}
