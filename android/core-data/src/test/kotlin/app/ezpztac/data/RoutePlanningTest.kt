package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import app.ezpztac.network.WindQuestion
import app.ezpztac.model.PointOverride
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/** Fetching ground elevations and winds for a route: what is asked, what is kept, and the words when it cannot be done. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanningTest {
    private class FakeApi : PlanningApi {
        val elevationAsks = mutableListOf<List<LatLon>>()
        val windAsks = mutableListOf<List<WindQuestion>>()
        var elevationsAnswer: suspend (List<LatLon>) -> List<Double?> = { points -> points.map { 1000.0 } }
        var winds: suspend (List<WindQuestion>) -> Map<String, PointWindDto> = { emptyMap() }
        override suspend fun elevations(points: List<LatLon>): List<Double?> { elevationAsks += points; return elevationsAnswer(points) }
        override suspend fun routeWinds(points: List<WindQuestion>): Map<String, PointWindDto> { windAsks += points; return winds(points) }
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val session = RouteSession(repository, scope.backgroundScope)
        val api = FakeApi()
        val planning = RoutePlanning(api, session).also { it.zone = { ZoneId.of("UTC") } }
    }

    private fun point(id: String, lat: Double, kind: String = RoutePoint.KIND_AMPS) = RoutePoint(id = id, lat = lat, lon = -84.1, kind = kind, ptType = "turn", name = id)

    private fun route(plan: RoutePlan = RoutePlan()) = SketchRoute(
        id = "r1", name = "ROUTE 1", color = "#FF453A", plan = plan,
        points = listOf(point("p1", 34.70), point("p2", 34.71), point("shape", 34.715, RoutePoint.KIND_SHAPING), point("p3", 34.72), point("p4", 34.73)),
    )

    private suspend fun Rig.open(route: SketchRoute = route()): String {
        val made = repository.create("MISSION", listOf(route))
        session.open(made.id)
        return made.id
    }

    private fun wind(dir: Int, speed: Double, source: String, temp: Double? = null) = PointWindDto(dir, speed, variable = false, tempC = temp, station = "KXXX", source = source, distanceMiles = 3.0)

    // -- Winds --------------------------------------------------------------------------------------------------

    @Test
    fun `winds are asked for each named point, with the instant it is wanted at in the device's zone`() = runTest {
        val r = Rig(this)
        val set = r.open(route(RoutePlan(date = "2026-10-03")))
        r.planning.zone = { ZoneId.of("America/New_York") }
        r.planning.fetchWinds(set, "r1")
        val asked = r.api.windAsks.single()
        assertEquals(listOf("p1", "p2", "p3", "p4"), asked.map { it.id })                         // the shaping point is not asked about
        assertEquals(listOf(34.70, 34.71, 34.72, 34.73), asked.map { it.lat })
        assertEquals(List(4) { "2026-10-03T16:00:00.000Z" }, asked.map { it.time })                // local midday in daylight time
    }

    @Test
    fun `with no date the winds are asked for now`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.planning.fetchWinds(set, "r1")
        assertEquals(List(4) { null }, r.api.windAsks.single().map { it.time })
    }

    @Test
    fun `the winds found are merged into the plan, and the outcome says where they came from`() = runTest {
        val r = Rig(this)
        val set = r.open(route(RoutePlan(perPoint = mapOf("p2" to PointOverride(clock = "12:00:00")))))
        r.api.winds = {
            mapOf("p1" to wind(270, 12.0, "METAR", temp = 18.0), "p2" to wind(280, 14.0, "METAR", temp = 17.0), "p4" to wind(300, 20.0, "TAF", temp = 16.0))
        }
        val outcome = r.planning.fetchWinds(set, "r1")
        assertEquals(PlanningOutcome.Done("3 points · 2 METAR, 1 TAF"), outcome)
        val plan = r.session.active.value!!.route("r1")!!.plan
        assertEquals(270.0, plan.perPoint.getValue("p1").wind!!.dirTrue, 0.0)
        assertEquals(14.0, plan.perPoint.getValue("p2").wind!!.speedKts, 0.0)
        assertEquals("12:00:00", plan.perPoint.getValue("p2").clock)                              // what the point already had stays
        assertEquals(null, plan.perPoint["p3"])                                                   // no answer for it
        assertEquals(18.0, plan.tempC)                                                            // the first point's temperature
    }

    @Test
    fun `one point is one point`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.winds = { mapOf("p1" to wind(90, 5.0, "METAR")) }
        assertEquals(PlanningOutcome.Done("1 point · 1 METAR"), r.planning.fetchWinds(set, "r1"))
    }

    @Test
    fun `fetched winds are not a step to undo`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.winds = { mapOf("p1" to wind(90, 5.0, "METAR")) }
        r.planning.fetchWinds(set, "r1")
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `no station in reach changes nothing, and says so`() = runTest {
        val r = Rig(this)
        val set = r.open()
        val before = r.session.active.value
        assertEquals(PlanningOutcome.Done("No reporting stations were found near this route."), r.planning.fetchWinds(set, "r1"))
        assertEquals(before, r.session.active.value)
    }

    @Test
    fun `winds land in the route they were asked for, even if another set is open by the time the server answers`() = runTest {
        val r = Rig(this)
        val first = r.open()
        val held = CompletableDeferred<Map<String, PointWindDto>>()
        r.api.winds = { held.await() }
        val fetch = async { r.planning.fetchWinds(first, "r1") }
        advanceUntilIdle()
        val other = r.repository.create("OTHER", listOf(route()))
        r.session.open(other.id)
        held.complete(mapOf("p1" to wind(45, 9.0, "METAR")))
        assertEquals(PlanningOutcome.Done("1 point · 1 METAR"), fetch.await())
        assertEquals(45.0, r.repository.open(first)!!.route("r1")!!.plan.perPoint.getValue("p1").wind!!.dirTrue, 0.0)
        assertEquals(null, r.session.active.value!!.route("r1")!!.plan.perPoint["p1"])             // the open set is not touched
    }

    @Test
    fun `a route that is not in the open set is not fetched for`() = runTest {
        val r = Rig(this)
        val set = r.open()
        assertEquals(PlanningOutcome.Failed("That route is no longer open."), r.planning.fetchWinds(set, "nope"))
        assertEquals(PlanningOutcome.Failed("That route is no longer open."), r.planning.fetchWinds("another-set", "r1"))
        assertTrue(r.api.windAsks.isEmpty())
    }

    @Test
    fun `a route with no named point has nothing to ask`() = runTest {
        val r = Rig(this)
        val set = r.open(route().copy(points = listOf(point("a", 1.0, RoutePoint.KIND_SHAPING))))
        assertEquals(PlanningOutcome.Failed("The route has no named points."), r.planning.fetchWinds(set, "r1"))
        assertEquals(PlanningOutcome.Failed("The route has no named points."), r.planning.fetchElevations(set, "r1"))
        assertTrue(r.api.windAsks.isEmpty() && r.api.elevationAsks.isEmpty())
    }

    @Test
    fun `a failure is in the app's words, whatever the server said`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.winds = { throw NetworkException("no route to host", null, requestMayHaveBeenSent = false) }
        assertEquals(PlanningOutcome.Failed("There is no connection to the server, so the winds could not be fetched."), r.planning.fetchWinds(set, "r1"))
        r.api.winds = { throw SessionEndedException(SignedOutReason.SESSION_ENDED, "x", "signed out") }
        assertEquals(PlanningOutcome.Failed("Sign in again to fetch the winds."), r.planning.fetchWinds(set, "r1"))
        r.api.winds = { throw RateLimitedException("slow", 5) }
        assertEquals(PlanningOutcome.Failed("Too many requests. Wait a moment and try again."), r.planning.fetchWinds(set, "r1"))
        r.api.winds = { throw ApiException(500, null, "'str' object has no attribute 'get'") }
        assertEquals(PlanningOutcome.Failed("The winds could not be fetched. Try again in a moment."), r.planning.fetchWinds(set, "r1"))
        r.api.winds = { throw IllegalStateException("boom") }
        assertEquals(PlanningOutcome.Failed("The winds could not be fetched. Try again in a moment."), r.planning.fetchWinds(set, "r1"))
        assertEquals(0, r.session.active.value!!.route("r1")!!.plan.perPoint.size)
    }

    @Test
    fun `stopping the screen stops the fetch, and is not a failure`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.winds = { throw CancellationException("the screen went away") }
        try {
            r.planning.fetchWinds(set, "r1")
            fail("a cancellation was swallowed")
        } catch (_: CancellationException) {
        }
    }

    // -- Elevations ---------------------------------------------------------------------------------------------

    @Test
    fun `elevations are asked for the named points, in order, and kept by point`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.elevationsAnswer = { listOf(1100.0, 1200.0, 1300.0, 1400.0) }
        assertEquals(PlanningOutcome.Done("Ground elevations for 4 points."), r.planning.fetchElevations(set, "r1"))
        assertEquals(listOf(LatLon(34.70, -84.1), LatLon(34.71, -84.1), LatLon(34.72, -84.1), LatLon(34.73, -84.1)), r.api.elevationAsks.single())
        assertEquals(mapOf("p1" to 1100.0, "p2" to 1200.0, "p3" to 1300.0, "p4" to 1400.0), r.session.active.value!!.route("r1")!!.elevations)
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `points the server could not read are left out, and what was already known stays`() = runTest {
        val r = Rig(this)
        val set = r.open(route().copy(elevations = mapOf("p1" to 5.0, "p4" to 9.0)))
        r.api.elevationsAnswer = { listOf(null, 1200.0, null, 1400.0) }
        assertEquals(PlanningOutcome.Done("Ground elevations for 2 of 4 points."), r.planning.fetchElevations(set, "r1"))
        assertEquals(mapOf("p1" to 5.0, "p2" to 1200.0, "p4" to 1400.0), r.session.active.value!!.route("r1")!!.elevations)   // a fresh one replaces, an old one is kept
    }

    @Test
    fun `when none can be read nothing changes, and it says why`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.elevationsAnswer = { points -> points.map { null } }
        assertEquals(PlanningOutcome.Failed("The elevation service could not read the ground here."), r.planning.fetchElevations(set, "r1"))
        assertEquals(emptyMap<String, Double>(), r.session.active.value!!.route("r1")!!.elevations)
    }

    @Test
    fun `an elevation failure is in the app's words`() = runTest {
        val r = Rig(this)
        val set = r.open()
        r.api.elevationsAnswer = { throw NetworkException("timeout", null, requestMayHaveBeenSent = true) }
        assertEquals(PlanningOutcome.Failed("There is no connection to the server, so the elevations could not be fetched."), r.planning.fetchElevations(set, "r1"))
        r.api.elevationsAnswer = { throw ApiException(400, null, "Invalid points") }
        assertEquals(PlanningOutcome.Failed("The elevations could not be fetched. Try again in a moment."), r.planning.fetchElevations(set, "r1"))
        r.api.elevationsAnswer = { throw IllegalStateException("boom") }
        assertEquals(PlanningOutcome.Failed("The elevations could not be fetched. Try again in a moment."), r.planning.fetchElevations(set, "r1"))
        assertFalse(r.session.active.value!!.route("r1")!!.elevations.isNotEmpty())
    }

    @Test
    fun `elevations land in the route they were asked for when another set is open by then`() = runTest {
        val r = Rig(this)
        val first = r.open()
        val held = CompletableDeferred<List<Double?>>()
        r.api.elevationsAnswer = { held.await() }
        val fetch = async { r.planning.fetchElevations(first, "r1") }
        advanceUntilIdle()
        r.session.open(r.repository.create("OTHER", listOf(route())).id)
        held.complete(listOf(1.0, 2.0, 3.0, 4.0))
        fetch.await()
        assertEquals(4, r.repository.open(first)!!.route("r1")!!.elevations.size)
        assertEquals(0, r.session.active.value!!.route("r1")!!.elevations.size)
    }
}
