package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.ActiveAircraftChoice
import app.ezpztac.data.InMemoryOpenRouteSetStore
import app.ezpztac.data.LocalPoints
import app.ezpztac.data.MasterProfileStore
import app.ezpztac.data.OpenRouteSets
import app.ezpztac.data.PlanningApi
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.PointSetView
import app.ezpztac.data.PointSetViewStore
import app.ezpztac.data.PointSetViews
import app.ezpztac.data.RouteExport
import app.ezpztac.data.RouteHandoffExport
import app.ezpztac.data.RoutePlanning
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSelection
import app.ezpztac.data.RouteSession
import app.ezpztac.data.RouteSketching
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.LatLon
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.WindQuestion
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The working set when a set's record goes while it is open, with Main unconfined as the app's `Main.immediate` runs it: the save that closing makes keeps the work
 * under a new id, which joins the session as it is made, and must not then be opened as "the one before" (AGENTS.md §14: a queued dispatcher hides this).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutesSessionOrderTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Rig(scope: TestScope) {
        val server = FakeServer()
        val device = Device("A", server)
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler(), UnconfinedTestDispatcher(scope.testScheduler))
        val session = RouteSession(repository, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scope.testScheduler)))
        val profiles = AircraftProfiles(
            device.store as InMemorySyncStore,
            object : MasterProfileStore {
                override fun read() = emptyList<AircraftProfile>()
                override fun write(profiles: List<AircraftProfile>) {}
            },
            { emptyList() },
            object : ActiveAircraftChoice {
                override fun slug(): String? = null
                override fun choose(slug: String) {}
            },
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scope.testScheduler)), device.repository, RecordingScheduler(),
        )
        val openSets = OpenRouteSets(InMemoryOpenRouteSetStore())
        val pointSets = PointSetRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val model = RoutesViewModel(
            repository, session, RouteSelection(), RouteSketching(session, profiles), ConflictResolver { _, _, _ -> },
            RoutePlanning(object : PlanningApi {
                override suspend fun elevations(points: List<LatLon>) = points.map { 1000.0 }
                override suspend fun routeWinds(points: List<WindQuestion>) = emptyMap<String, PointWindDto>()
            }, session),
            RouteExport { ByteArray(0) }, RouteHandoffExport(),
            LocalPoints(pointSets, PointSetViews(object : PointSetViewStore {
                override fun load() = emptyMap<String, PointSetView>()
                override fun save(views: Map<String, PointSetView>) {}
            })),
            openSets,
        )
    }

    /** ONE and TWO in the session, TWO on the map with an edit not yet saved, and TWO's record deleted elsewhere. */
    private suspend fun TestScope.goneWhileOpen(r: Rig): String {
        r.model.createSet("ONE"); advanceUntilIdle()
        r.model.createSet("TWO"); advanceUntilIdle()
        val two = r.session.active.value!!.id
        r.session.edit("Rename") { it.copy(name = "TWO EDITED") }                                // owed: saved after a pause
        r.repository.delete(two)                                                               // as a pull from another device would
        return two
    }

    @Test
    fun `closing a set whose record went keeps the work in the Library and opens the one before, not the copy`() = runTest(dispatcher) {
        val r = Rig(this)
        val two = goneWhileOpen(r)
        r.model.close(two); advanceUntilIdle()
        assertEquals("ONE", r.session.active.value?.name)
        assertEquals(1, r.openSets.ids.value.size)
        assertTrue(r.repository.observeOnce().any { it.name == "TWO EDITED" })                 // the work is kept
    }

    @Test
    fun `deleting a set whose record went does not open the copy its save made`() = runTest(dispatcher) {
        val r = Rig(this)
        val two = goneWhileOpen(r)
        r.model.deleteSet(two); advanceUntilIdle()
        assertEquals("ONE", r.session.active.value?.name)
        assertEquals(1, r.openSets.ids.value.size)
        assertFalse(r.openSets.ids.value.any { it == two })
    }

    private suspend fun RouteRepository.observeOnce() = observe().first()
}
