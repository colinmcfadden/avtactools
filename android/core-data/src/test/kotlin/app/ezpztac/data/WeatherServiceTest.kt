package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.Notams
import app.ezpztac.model.WeatherSnapshot
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import app.ezpztac.network.WeatherReportDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weather at each diagram's target: when it is fetched, what is kept with it, and what is said when it cannot be. */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherServiceTest {
    private class Api : WeatherApi {
        var calls = mutableListOf<LatLon>()
        var answer: suspend (LatLon) -> WeatherReportDto = { report("KRYY") }
        override suspend fun weather(at: LatLon): WeatherReportDto {
            calls += at
            return answer(at)
        }
    }

    private class Memory(var kept: Map<String, WeatherSnapshot> = emptyMap(), var failing: Boolean = false) : WeatherCache {
        var saves = 0
        override fun load() = kept
        override fun save(snapshots: Map<String, WeatherSnapshot>) {
            if (failing) error("the disk is full")
            kept = snapshots
            saves++
        }
    }

    private companion object {
        fun report(station: String) = WeatherReportDto(station, "Station $station", windSpeedKts = JsonPrimitive(10), notams = buildJsonObject {})
    }

    private val here = LatLon(34.0, -84.6)
    private var now = 1_000_000L

    /** On the test's scheduler, not its backgroundScope: `advanceUntilIdle` leaves a background scope's work alone, and a fetch is work a test waits for. */
    private fun TestScope.service(api: Api = Api(), cache: Memory = Memory()) =
        WeatherService(api, cache, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))) { now }

    private fun snapshot(at: LatLon = here, fetched: Long = now) = WeatherSnapshot(at, fetched, null, Notams.Clear)

    // -- Refreshing ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a diagram nobody has asked about has nothing known`() = runTest {
        assertEquals(WeatherState(), service().stateOf("d1"))
    }

    @Test
    fun `a refresh fetches, keeps the answer with the time it was fetched, and writes it to the cache`() = runTest {
        val api = Api(); val cache = Memory()
        val service = service(api, cache)
        service.refresh("d1", here)
        runCurrent()
        val state = service.stateOf("d1")
        assertFalse(state.fetching)
        assertNull(state.failure)
        val snapshot = state.snapshot!!
        assertEquals(1_000_000L, snapshot.fetchedAtMillis)
        assertEquals("KRYY", snapshot.observation!!.stationId)
        assertEquals(here, snapshot.at)
        assertEquals(listOf(here), api.calls)
        assertEquals(setOf("d1"), cache.kept.keys)
        assertEquals(state.snapshot, cache.kept.getValue("d1"))
    }

    @Test
    fun `while a fetch is running the state says so, and a second ask does not start another`() = runTest {
        val api = Api(); val gate = CompletableDeferred<Unit>()
        api.answer = { gate.await(); report("KRYY") }
        val service = service(api)
        service.refresh("d1", here)
        runCurrent()
        assertTrue(service.stateOf("d1").fetching)
        service.refresh("d1", here)
        service.ensureFresh("d1", here)
        runCurrent()
        assertEquals(1, api.calls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(service.stateOf("d1").fetching)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `each diagram has its own weather`() = runTest {
        val api = Api()
        api.answer = { at -> report(if (at.lat < 35) "SOUTH" else "NORTH") }
        val service = service(api)
        service.refresh("a", LatLon(34.0, -84.0))
        service.refresh("b", LatLon(36.0, -84.0))
        advanceUntilIdle()
        assertEquals("SOUTH", service.stateOf("a").snapshot!!.observation!!.stationId)
        assertEquals("NORTH", service.stateOf("b").snapshot!!.observation!!.stationId)
    }

    // -- Opening a diagram ---------------------------------------------------------------------------------------------------------

    @Test
    fun `opening a diagram with nothing known fetches`() = runTest {
        val api = Api(); val service = service(api)
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `what is fresh and for the same place is not fetched again, until it is half an hour old`() = runTest {
        val api = Api(); val service = service(api)
        service.ensureFresh("d1", here); advanceUntilIdle()
        now += WeatherService.REFETCH_AFTER_MS                                                // exactly as old as it is allowed to be
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(1, api.calls.size)
        now += 1
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `a degree of longitude is shorter toward the pole, so the same step east is a smaller move there`() = runTest {
        val api = Api(); val service = service(api)
        val north = LatLon(60.0, 10.0)
        service.ensureFresh("d1", north); advanceUntilIdle()
        service.ensureFresh("d1", LatLon(60.0, 10.012)); advanceUntilIdle()                   // 0.012 degrees east is about 670 m at 60 north (1.3 km at the equator)
        assertEquals(1, api.calls.size)
        service.ensureFresh("d1", LatLon(60.0, 10.025)); advanceUntilIdle()                   // about 1.4 km
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `a new fetch does not show the last failure while it is running`() = runTest {
        val api = Api(); val service = service(api)
        api.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        service.refresh("d1", here); advanceUntilIdle()
        assertNotNull(service.stateOf("d1").failure)
        val gate = CompletableDeferred<Unit>()
        api.answer = { gate.await(); report("KRYY") }
        service.refresh("d1", here); runCurrent()
        val running = service.stateOf("d1")
        assertTrue(running.fetching)
        assertNull(running.failure)                                                           // it is being tried again: the last answer is not the news
        gate.complete(Unit); advanceUntilIdle()
        assertNull(service.stateOf("d1").failure)
    }

    @Test
    fun `a target that has moved a block is the same place, and one that has moved a kilometre and more is another`() = runTest {
        val api = Api(); val service = service(api)
        service.ensureFresh("d1", here); advanceUntilIdle()
        service.ensureFresh("d1", LatLon(here.lat + 0.005, here.lon)); advanceUntilIdle()     // about 550 m
        assertEquals(1, api.calls.size)
        service.ensureFresh("d1", LatLon(here.lat + 0.02, here.lon)); advanceUntilIdle()      // about 2.2 km
        assertEquals(2, api.calls.size)
        assertEquals(LatLon(here.lat + 0.02, here.lon), service.stateOf("d1").snapshot!!.at)
    }

    @Test
    fun `the report kept from the last launch is there at once, with its age, and is fetched again only if it is old`() = runTest {
        val cache = Memory(mapOf("d1" to snapshot(fetched = now - 10 * 60_000)))
        val api = Api()
        val service = service(api, cache)
        assertEquals(now - 10 * 60_000, service.stateOf("d1").snapshot!!.fetchedAtMillis)
        assertEquals("10 min ago", service.stateOf("d1").snapshot!!.age(now))
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(0, api.calls.size)                                                       // 10 minutes old: as fresh as it needs to be

        now += 25 * 60_000
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(1, api.calls.size)
    }

    // -- When it cannot be had -------------------------------------------------------------------------------------------------------

    @Test
    fun `a failed fetch keeps the old report, says why in words, and is not retried for a minute`() = runTest {
        val api = Api(); val service = service(api)
        service.refresh("d1", here); advanceUntilIdle()
        val old = service.stateOf("d1").snapshot
        now += 2 * WeatherService.REFETCH_AFTER_MS
        api.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        service.ensureFresh("d1", here); advanceUntilIdle()
        val state = service.stateOf("d1")
        assertEquals(old, state.snapshot)                                                     // what was known is not taken away
        assertEquals("No connection, so the weather could not be updated.", state.failure)
        assertFalse(state.fetching)
        assertEquals(2, api.calls.size)

        now += WeatherService.RETRY_AFTER_MS - 1
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(2, api.calls.size)                                                       // asked again at once: not tried again
        now += 1
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `asking for a refresh by hand is not held back by a recent failure, and a success clears the failure`() = runTest {
        val api = Api(); val service = service(api)
        api.answer = { throw NetworkException("no signal", null, requestMayHaveBeenSent = false) }
        service.refresh("d1", here); advanceUntilIdle()
        assertNotNull(service.stateOf("d1").failure)
        assertNull(service.stateOf("d1").snapshot)                                            // never fetched: nothing to show but the reason
        api.answer = { report("KRYY") }
        service.refresh("d1", here); advanceUntilIdle()
        assertNull(service.stateOf("d1").failure)
        assertNotNull(service.stateOf("d1").snapshot)
        service.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(2, api.calls.size)                                                       // fresh and the same place: nothing to fetch
        // The old failure no longer holds anything back: a target that moves a kilometre is fetched at once, inside the minute the failure would have held it.
        // (Without this the test above cannot tell, because a fresh report for the same place is not fetched again whatever failed before.)
        val elsewhere = LatLon(here.lat + 0.02, here.lon)
        service.ensureFresh("d1", elsewhere); advanceUntilIdle()
        assertEquals(3, api.calls.size)
        assertEquals(elsewhere, api.calls.last())
    }

    @Test
    fun `what is said of a failure is the app's words, never the server's`() = runTest {
        val api = Api(); val service = service(api)
        fun failure() = service.stateOf("d1").failure!!
        api.answer = { throw ApiException(500, null, "could not convert string to float: 'x'") }
        service.refresh("d1", here); advanceUntilIdle()
        assertEquals("The weather service could not answer. Try again in a moment.", failure())
        assertFalse(failure().contains("float"))
        api.answer = { throw SessionEndedException(SignedOutReason.SESSION_ENDED, "session_revoked", "x") }
        service.refresh("d1", here); advanceUntilIdle()
        assertEquals("Sign in again to update the weather.", failure())
        api.answer = { throw RateLimitedException("slow down", 30) }
        service.refresh("d1", here); advanceUntilIdle()
        assertEquals("Too many requests. Wait a moment and try again.", failure())
        api.answer = { throw IllegalStateException("a Python type name") }
        service.refresh("d1", here); advanceUntilIdle()
        assertEquals("The weather could not be updated. Try again in a moment.", failure())
    }

    @Test
    fun `a fetch that is cancelled is no longer fetching, and is not a failure`() = runTest {
        val api = Api(); val gate = CompletableDeferred<Unit>()
        api.answer = { gate.await(); report("KRYY") }
        val job = Job()
        val service = WeatherService(api, Memory(), CoroutineScope(job + StandardTestDispatcher(testScheduler))) { now }
        service.refresh("d1", here)
        runCurrent()
        assertTrue(service.stateOf("d1").fetching)
        job.cancel()
        runCurrent()
        assertFalse(service.stateOf("d1").fetching)
        assertNull(service.stateOf("d1").failure)
    }

    // -- The cache -----------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a cache that cannot be written does not take the new report away`() = runTest {
        val service = service(cache = Memory(failing = true))
        service.refresh("d1", here); advanceUntilIdle()
        assertNotNull(service.stateOf("d1").snapshot)
        assertNull(service.stateOf("d1").failure)
    }

    @Test
    fun `only the newest forty reports are kept`() = runTest {
        val cache = Memory()
        val service = service(cache = cache)
        for (i in 1..45) {
            now += 1_000
            service.refresh("d$i", here); advanceUntilIdle()
        }
        assertEquals(WeatherService.MAX_KEPT, cache.kept.size)
        assertFalse("d1" in cache.kept)
        assertTrue("d45" in cache.kept)
        assertEquals(45, service.states.value.size)                                           // all are shown this session; only the newest are kept for the next
    }

    @Test
    fun `clearing forgets everything, here and in the cache, as at sign-out`() = runTest {
        val cache = Memory()
        val service = service(cache = cache)
        service.refresh("d1", here); advanceUntilIdle()
        service.clear()
        assertEquals(WeatherState(), service.stateOf("d1"))
        assertTrue(service.states.value.isEmpty())
        assertTrue(cache.kept.isEmpty())
        val api = Api()
        val again = service(api, cache)
        again.ensureFresh("d1", here); advanceUntilIdle()
        assertEquals(1, api.calls.size)                                                       // nothing was left to show
    }
}
