package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.ActiveAircraftChoice
import app.ezpztac.data.MasterProfileStore
import app.ezpztac.data.PlanningApi
import app.ezpztac.data.RouteHeld
import app.ezpztac.data.RoutePlanning
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.WindQuestion
import kotlinx.coroutines.CompletableDeferred
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.Airspeed
import app.ezpztac.model.Wind
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

    private class FakeApi : PlanningApi {
        var elevationsAnswer: suspend (List<LatLon>) -> List<Double?> = { points -> points.map { 1000.0 } }
        var windsAnswer: suspend (List<WindQuestion>) -> Map<String, PointWindDto> = { emptyMap() }
        override suspend fun elevations(points: List<LatLon>) = elevationsAnswer(points)
        override suspend fun routeWinds(points: List<WindQuestion>) = windsAnswer(points)
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
        val api = FakeApi()
        val model = RoutesViewModel(repository, session, selection, sketching, resolver ?: ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) }, RoutePlanning(api, session))
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

    // -- The held route: plan, nav log, points -----------------------------------------------------------------

    /** A set with one route of four points (target, ip, ip, target) that has been finished, so it is held, on a date of its own. */
    private fun TestScope.openWithPlannedRoute(r: Rig): String {
        r.model.createSet(""); settle()
        r.model.startDrawing()
        for (at in listOf(a, b, c, LatLon(34.73, -84.07))) r.model.addAtCrosshair(at)
        r.model.finishDrawing(); settle()
        val id = r.state.open!!.routes.single().id
        r.model.applyPlan(id, PlanDraft.of(r.session.active.value!!.route(id)!!.plan).copy(date = "2026-10-03")); settle()
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
        assertTrue(detail.totals!!.startsWith("Total "))
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
        assertNull(r.model.applyPlan(id, draft)); settle()
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
        assertEquals("Wind speed is not a number.", r.model.applyPlan(id, r.state.detail!!.plan.copy(windSpeed = "gale"))); settle()
        assertEquals(plan, r.route(id).plan)
        assertNull(r.state.error)                                                             // the words go to the form, not to the banner
        assertEquals("That route is no longer here.", r.model.applyPlan("nope", r.state.detail!!.plan))
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
        assertEquals(ShapingPointUi(log[1].id, held = true), r.state.detail!!.heldShaping)
        assertEquals(listOf(false, false, false), r.state.detail!!.points.map { it.held })      // a named row is not the held one
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
    fun `fetching elevations marks the route as having them`() = runTest(dispatcher) {
        val r = rig()
        openWithPlannedRoute(r)
        assertFalse(r.state.detail!!.hasElevations)
        r.model.fetchElevations(); settle()
        assertEquals(PlanningNote("Ground elevations for 4 points.", failed = false), r.state.note)
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
}
