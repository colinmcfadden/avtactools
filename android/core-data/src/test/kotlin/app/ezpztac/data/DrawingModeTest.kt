package app.ezpztac.data

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A boundary and a route are both drawn by tapping the map, so only one is drawn at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawingModeTest {
    @Test
    fun `the mode is free until someone takes it, and taking it twice is fine`() {
        val mode = DrawingMode()
        assertNull(mode.begin(DrawingMode.Kind.ROUTE))
        assertNull(mode.begin(DrawingMode.Kind.ROUTE))
    }

    @Test
    fun `the other kind is refused while one is held, and says which to finish`() {
        val mode = DrawingMode()
        mode.begin(DrawingMode.Kind.ROUTE)
        assertEquals("Finish or cancel the route first.", mode.begin(DrawingMode.Kind.BOUNDARY))
        mode.end(DrawingMode.Kind.ROUTE)
        assertNull(mode.begin(DrawingMode.Kind.BOUNDARY))
        assertEquals("Finish or cancel the boundary first.", mode.begin(DrawingMode.Kind.ROUTE))
    }

    @Test
    fun `ending what is not held changes nothing`() {
        val mode = DrawingMode()
        mode.begin(DrawingMode.Kind.BOUNDARY)
        mode.end(DrawingMode.Kind.ROUTE)
        assertEquals("Finish or cancel the boundary first.", mode.begin(DrawingMode.Kind.ROUTE))
        mode.end(DrawingMode.Kind.BOUNDARY)
        mode.end(DrawingMode.Kind.BOUNDARY)
        assertNull(mode.begin(DrawingMode.Kind.ROUTE))
    }

    // -- Both kinds of drawing, sharing one mode ------------------------------------------------------------

    private val a = LatLon(34.70, -84.10)
    private val b = LatLon(34.71, -84.09)
    private val c = LatLon(34.72, -84.08)

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val diagrams = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val diagramSession = DiagramSession(diagrams, scope.backgroundScope)
        val routes = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val mode = DrawingMode()
        val aircraft = AircraftProfiles(
            device.store as InMemorySyncStore, object : MasterProfileStore {
                override fun read() = emptyList<AircraftProfile>()
                override fun write(profiles: List<AircraftProfile>) {}
            }, { emptyList() }, object : ActiveAircraftChoice {
                override fun slug(): String? = null
                override fun choose(slug: String) {}
            },
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler(),
        )
        val boundary = BoundaryDrawing(diagramSession, mode)
        val sketching = RouteSketching(routeSession, aircraft, mode)

        suspend fun openBoth() {
            diagramSession.open(diagrams.create(DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949"), "LZ HAWK").id)
            routeSession.open(routes.create("MISSION").id)
        }
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    @Test
    fun `a route cannot be started while a boundary is being drawn, and the boundary is not disturbed`() = runTest {
        val r = rig()
        r.openBoth()
        assertNull(r.boundary.start())
        r.boundary.addPoint(a)
        assertEquals("Finish or cancel the boundary first.", r.sketching.start())
        assertNull(r.sketching.draft.value)
        assertEquals(1, r.boundary.draft.value!!.points.size)
    }

    @Test
    fun `a boundary cannot be started while a route is being drawn, and the diagram is left alone`() = runTest {
        val r = rig()
        r.openBoth()
        val before = r.diagramSession.active.value
        assertNull(r.sketching.start())
        assertEquals("Finish or cancel the route first.", r.boundary.start())
        assertNull(r.boundary.draft.value)
        assertEquals(before, r.diagramSession.active.value)                                  // no edit was made on the way to refusing
        assertEquals(0, r.diagramSession.undoDepth.value)
    }

    @Test
    fun `finishing or cancelling a boundary lets a route be drawn`() = runTest {
        val r = rig()
        r.openBoth()
        r.boundary.start(); r.boundary.addPoint(a); r.boundary.addPoint(b); r.boundary.addPoint(c)
        assertNull(r.boundary.finish())
        assertNull(r.sketching.start())
        r.sketching.cancel()
        r.boundary.start()
        assertNotNull(r.boundary.draft.value)
        r.boundary.cancel()
        assertNull(r.sketching.start())
    }

    @Test
    fun `finishing or cancelling a route lets a boundary be drawn`() = runTest {
        val r = rig()
        r.openBoth()
        r.sketching.start(); r.sketching.addPoint(a); r.sketching.addPoint(b)
        assertTrue(r.sketching.finish() is SketchFinish.Done)
        assertNull(r.boundary.start())
        r.boundary.cancel()
        r.sketching.start()
        r.sketching.cancel()
        assertNull(r.boundary.start())
    }

    @Test
    fun `a draft dropped because its document was closed lets go of the mode`() = runTest {
        val r = rig()
        r.openBoth()
        r.sketching.start(); r.sketching.addPoint(a); r.sketching.addPoint(b)
        r.routeSession.close()
        assertTrue(r.sketching.finish() is SketchFinish.Refused)
        assertNull(r.boundary.start())
        r.boundary.addPoint(a); r.boundary.addPoint(b); r.boundary.addPoint(c)
        r.diagramSession.close()
        assertEquals("The diagram this was drawn on is no longer open.", r.boundary.finish())
        r.routeSession.open(r.routes.create("AGAIN").id)
        assertNull(r.sketching.start())
    }

    @Test
    fun `a refused start leaves the mode with whoever had it`() = runTest {
        val r = rig()
        r.openBoth()
        r.sketching.start()
        r.boundary.start()                                                                   // refused
        r.sketching.cancel()
        assertNull(r.boundary.start())                                                       // the refused one took nothing, and the route let go
        assertTrue(r.boundary.draft.value != null)
    }
}
