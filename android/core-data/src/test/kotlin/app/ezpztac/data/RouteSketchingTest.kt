package app.ezpztac.data

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.planning.Designation
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drawing a route by hand: what starting, adding, undoing, finishing and cancelling do to the draft and to the open set. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteSketchingTest {
    private val a = LatLon(34.70, -84.10)
    private val b = LatLon(34.71, -84.09)
    private val c = LatLon(34.72, -84.08)
    private val d = LatLon(34.73, -84.07)

    private val uh60 = AircraftProfile(id = 1, slug = "uh60l", name = "UH-60L Black Hawk", designation = "UH-60L")
    private val ch47 = AircraftProfile(
        id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", defaultAirspeedKts = 130.0, defaultAltitudeFt = 100.0,
    )

    private class Store(var kept: List<AircraftProfile>) : MasterProfileStore {
        override fun read() = kept
        override fun write(profiles: List<AircraftProfile>) {
            kept = profiles
        }
    }

    private class Choice(var slug: String?) : ActiveAircraftChoice {
        override fun slug() = slug
        override fun choose(slug: String) {
            this.slug = slug
        }
    }

    private class CountingIds : RouteIds {
        var routes = 0
        var points = 0
        override fun route() = "sketch-${++routes}"
        override fun point() = "pt-${++points}"
    }

    private class Rig(scope: TestScope, chosen: String?, master: List<AircraftProfile>) {
        val device = Device("A", FakeServer())
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = RouteSession(repository, scope.backgroundScope)
        val profiles = AircraftProfiles(
            device.store as InMemorySyncStore, Store(master), { master }, Choice(chosen),
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler(),
        )
        val ids = CountingIds()
        val sketching = RouteSketching(session, profiles).also { it.ids = ids }
    }

    private fun TestScope.rig(chosen: String? = null) = Rig(this, chosen, listOf(uh60, ch47)).also { advanceUntilIdle() }

    private suspend fun Rig.open(name: String = "MISSION"): String {
        val made = repository.create(name)
        session.open(made.id)
        return made.id
    }

    private val Rig.set get() = session.active.value!!

    private fun Rig.draw(vararg at: LatLon) = at.forEach { assertTrue(sketching.addPoint(it)) }

    // -- Starting ---------------------------------------------------------------------------------------------

    @Test
    fun `with no set open there is nothing to draw on`() = runTest {
        val r = rig()
        assertEquals("Open a set of routes first.", r.sketching.start())
        assertNull(r.sketching.draft.value)
        assertFalse(r.sketching.addPoint(a))
    }

    @Test
    fun `starting begins an empty draft on the open set and changes nothing in it`() = runTest {
        val r = rig()
        val id = r.open()
        assertNull(r.sketching.start())
        assertEquals(RouteDraft(id, emptyList()), r.sketching.draft.value)
        assertEquals(0, r.set.routes.size)
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `starting again while drawing keeps the points so far`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.draw(a, b)
        assertNull(r.sketching.start())
        assertEquals(2, r.sketching.draft.value!!.points.size)
    }

    // -- Adding and taking back --------------------------------------------------------------------------------

    @Test
    fun `points are added in order, and nothing is added when nothing is being drawn`() = runTest {
        val r = rig()
        r.open()
        assertFalse(r.sketching.addPoint(a))
        r.sketching.start()
        r.draw(a, b, c)
        assertEquals(listOf(a, b, c).map { it.lat }, r.sketching.draft.value!!.points.map { it.lat })
    }

    @Test
    fun `a position that is not one is refused`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        assertFalse(r.sketching.addPoint(LatLon(91.0, 0.0)))
        assertFalse(r.sketching.addPoint(LatLon(0.0, 181.0)))
        assertFalse(r.sketching.addPoint(LatLon(Double.NaN, 0.0)))
        assertFalse(r.sketching.addPoint(LatLon(0.0, Double.NaN)))
        assertTrue(r.sketching.addPoint(LatLon(90.0, 180.0)))                               // the edges themselves are positions
        assertTrue(r.sketching.addPoint(LatLon(-90.0, -180.0)))
        assertEquals(2, r.sketching.draft.value!!.points.size)
    }

    @Test
    fun `a point put down with a name keeps what the person said it is`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.sketching.addPoint(a, Designation(ptType = "release", name = ".RP1"))
        assertEquals("release", r.sketching.draft.value!!.points.single().designation!!.ptType)
    }

    @Test
    fun `the last point is taken back, one at a time, and there is nothing to take back from nothing`() = runTest {
        val r = rig()
        r.open()
        r.sketching.removeLastPoint()                                                       // no draft: nothing happens
        r.sketching.start()
        r.sketching.removeLastPoint()                                                       // an empty draft: nothing happens
        r.draw(a, b, c)
        r.sketching.removeLastPoint()
        assertEquals(listOf(a.lat, b.lat), r.sketching.draft.value!!.points.map { it.lat })
    }

    @Test
    fun `cancelling forgets the points and leaves the set as it was`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.draw(a, b)
        r.sketching.cancel()
        assertNull(r.sketching.draft.value)
        assertEquals(0, r.set.routes.size)
        assertFalse(r.sketching.addPoint(c))
    }

    // -- Finishing ---------------------------------------------------------------------------------------------

    @Test
    fun `a route needs two points`() = runTest {
        val r = rig()
        r.open()
        assertEquals(SketchFinish.Refused("Nothing is being drawn."), r.sketching.finish())
        r.sketching.start()
        assertFalse(r.sketching.draft.value!!.canFinish)
        r.draw(a)
        assertEquals(SketchFinish.Refused("A route needs at least 2 points."), r.sketching.finish())
        assertEquals(1, r.sketching.draft.value!!.points.size)                              // drawing goes on
        r.draw(b)
        assertTrue(r.sketching.draft.value!!.canFinish)
    }

    @Test
    fun `finishing makes a route of the set with the web's designations, one undo step`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.draw(a, b, c, d)
        val done = r.sketching.finish() as SketchFinish.Done
        assertNull(r.sketching.draft.value)
        val route = r.set.routes.single()
        assertEquals(done.routeId, route.id)
        assertEquals("sketch-1", route.id)
        assertEquals("ROUTE 1", route.name)
        assertEquals(listOf(".TGT", ".SP", ".RP", ".TGT"), route.points.map { it.name })      // the standard attack profile
        assertEquals(listOf("pt-1", "pt-2", "pt-3", "pt-4"), route.points.map { it.id })
        assertTrue(route.visible)
        assertEquals(1, r.session.undoDepth.value)
        assertEquals("Draw route", r.session.undo())
        assertEquals(0, r.set.routes.size)
    }

    @Test
    fun `the name is what the person typed, upper-cased, or ROUTE n counting the routes already there`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start(); r.draw(a, b); r.sketching.finish("  night run ")
        r.sketching.start(); r.draw(a, b); r.sketching.finish("")
        r.sketching.start(); r.draw(a, b); r.sketching.finish("   ")
        assertEquals(listOf("NIGHT RUN", "ROUTE 2", "ROUTE 3"), r.set.routes.map { it.name })
    }

    @Test
    fun `each route takes the first colour the set is not using`() = runTest {
        val r = rig()
        r.open()
        repeat(3) { r.sketching.start(); r.draw(a, b); r.sketching.finish() }
        assertEquals(listOf("#FF453A", "#0A84FF", "#32D74B"), r.set.routes.map { it.color })
        r.session.edit("Remove first") { it.without(it.routes.first().id) }
        r.sketching.start(); r.draw(a, b); r.sketching.finish()
        assertEquals("#FF453A", r.set.routes.last().color)                                 // the freed colour comes back first
    }

    @Test
    fun `the route is planned for the mission aircraft`() = runTest {
        val r = rig(chosen = "ch47f")
        r.open()
        r.sketching.start(); r.draw(a, b); r.sketching.finish()
        val plan = r.set.routes.single().plan
        assertEquals("ch47f", plan.aircraftProfile)
        assertEquals("CH-47F Chinook", plan.aircraft)
        assertEquals(130.0, plan.airspeed.value, 0.0)
        assertEquals(100.0, plan.altitude.value, 0.0)
    }

    @Test
    fun `a designation made while drawing wins over the automatic one`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.sketching.addPoint(a)
        r.sketching.addPoint(b, Designation(ptType = "release", name = ".RP9"))
        r.sketching.addPoint(c)
        r.sketching.finish()
        val second = r.set.routes.single().points[1]
        assertEquals("release", second.ptType)
        assertEquals(".RP9", second.name)
        assertEquals(RoutePoint.KIND_AMPS, second.kind)
    }

    @Test
    fun `a draft whose set has been closed is dropped and refused, and makes nothing`() = runTest {
        val r = rig()
        r.open()
        r.sketching.start()
        r.draw(a, b)
        r.session.close()
        assertFalse(r.sketching.addPoint(c))
        val refused = r.sketching.finish()
        assertEquals(SketchFinish.Refused("The set this was drawn on is no longer open."), refused)
        assertNull(r.sketching.draft.value)
    }

    @Test
    fun `a draft started on one set is not finished into another`() = runTest {
        val r = rig()
        r.open("ONE")
        r.sketching.start()
        r.draw(a, b)
        val other = r.repository.create("TWO")
        r.session.open(other.id)
        assertFalse(r.sketching.addPoint(c))
        assertEquals(SketchFinish.Refused("The set this was drawn on is no longer open."), r.sketching.finish())
        assertEquals(0, r.set.routes.size)
        assertNull(r.sketching.start().also { assertEquals(other.id, r.sketching.draft.value!!.setId) })   // and a new draft starts on the one that is open
    }
}
