package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiException
import app.ezpztac.network.LidarBuildDto
import app.ezpztac.network.LidarResolveDto
import app.ezpztac.network.LidarTargetDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the build flow does that the web's fixture cannot show: what a run of the app leaves behind, and what is let go of when, however a view ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class LidarBuildsTest {
    private val here = LatLon(34.591552, -84.128225)
    private val key = "0123456789abcdef"

    /** A server that answers a lookup as not built, a build as queued, and every ask about it as running; and remembers every call in order. */
    private class Server(var releaseHangs: Boolean = false) : LidarApi {
        val calls = mutableListOf<String>()
        var buildFails = false

        var streams = false

        override suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean): LidarResolveDto {
            calls += if (stream) "resolve stream" else "resolve"
            if (stream && streams) return LidarResolveDto("fedcba9876543210", available = true, streamed = true, url = "/lidar/tilesets/fedcba9876543210/tileset.json")
            return LidarResolveDto("0123456789abcdef", available = false, canBuild = true, target = LidarTargetDto(at.lat, at.lon, 500.0))
        }

        override suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?): LidarBuildDto {
            calls += "build keep=$keep watcher=$watcher"
            if (buildFails) throw ApiException(503, "builder_unavailable", "down")
            return LidarBuildDto("0123456789abcdef", "queued", stage = "waiting", position = 0)
        }

        override suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto {
            calls += "status $key"
            return LidarBuildDto(key, "running", stage = "processing points", elapsedS = 10)
        }

        override suspend fun release(key: String, watcher: String?) {
            calls += "release $key watcher=$watcher"
            if (releaseHangs) awaitCancellation()
        }
    }

    // Under the test's background scope: a poll loop left running ends with the test, and a failed assertion is reported rather than run into the ground.
    private fun TestScope.rig(server: Server = Server(), waiting: LidarWaiting = InMemoryLidarWaiting("android-test-watcher")): Triple<LidarBuilds, CoroutineScope, Server> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        return Triple(LidarBuilds(server, waiting, scope), scope, server)
    }

    @Test
    fun `the first lookup of a run lets go of what the last run waited on, before it asks for anything, and only once`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher").apply { setInFlight(setOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")) }
        val (builds, scope, server) = rig(waiting = waiting)
        val first = builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals(
            listOf("release aaaaaaaaaaaaaaaa watcher=android-test-watcher", "release bbbbbbbbbbbbbbbb watcher=android-test-watcher", "resolve"),
            server.calls.take(3),
        )
        first.close()
        runCurrent()
        server.calls.clear()
        builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals("resolve", server.calls.first())                                        // nothing more to let go of
        scope.cancel()
    }

    @Test
    fun `a release that does not answer holds the first lookup three seconds at most, and its key is left for the next run`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher").apply { setInFlight(setOf("aaaaaaaaaaaaaaaa")) }
        val (builds, scope, server) = rig(server = Server(releaseHangs = true), waiting = waiting)
        builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals(listOf("release aaaaaaaaaaaaaaaa watcher=android-test-watcher"), server.calls)
        advanceTimeBy(LidarBuilds.RELEASE_WAIT_MS - 1)
        runCurrent()
        assertEquals(1, server.calls.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("resolve", server.calls[1])
        // Not yet let go of (its release never answered), so still kept for the next run; and this run's own build waited on.
        assertEquals(setOf("aaaaaaaaaaaaaaaa", key), waiting.inFlight())
        scope.cancel()
    }

    @Test
    fun `the last run's builds are let go of when the app starts, before anyone opens 3D`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher").apply { setInFlight(setOf("aaaaaaaaaaaaaaaa")) }
        val (_, scope, server) = rig(waiting = waiting)
        runCurrent()
        assertEquals(listOf("release aaaaaaaaaaaaaaaa watcher=android-test-watcher"), server.calls)
        assertTrue(waiting.inFlight().isEmpty())
        scope.cancel()
    }

    @Test
    fun `a second lookup while the first one builds lets go of nothing of the first's`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher")
        val (builds, scope, server) = rig(waiting = waiting)
        val first = builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals(setOf(key), waiting.inFlight())
        builds.start(scope, LatLon(34.7, -84.3), keep = false)
        runCurrent()
        assertTrue("the first's build is still wanted", server.calls.none { it.startsWith("release") })
        assertEquals(LidarPhase.BUILDING, first.state.value.phase)
        scope.cancel()
    }

    /** A server whose asks about a build are answered one at a time by the test, as it says. */
    private class Held : LidarApi {
        val calls = mutableListOf<String>()
        val asks = ArrayDeque<CompletableDeferred<LidarBuildDto>>()

        override suspend fun resolve(at: LatLon, radiusM: Double?, stream: Boolean) = LidarResolveDto("0123456789abcdef", available = false, canBuild = true)

        override suspend fun build(at: LatLon, keep: Boolean, watcher: String?, radiusM: Double?) = LidarBuildDto("0123456789abcdef", "queued")

        override suspend fun status(key: String, watcher: String?, keep: Boolean): LidarBuildDto {
            calls += "status"
            return CompletableDeferred<LidarBuildDto>().also { asks.addLast(it) }.await()
        }

        override suspend fun release(key: String, watcher: String?) {
            calls += "release $key"
        }
    }

    @Test
    fun `a failure that comes in just as the view closes is not acted on, and the build is let go`() = runTest {
        val server = Held()
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val builds = LidarBuilds(server, InMemoryLidarWaiting("android-test-watcher"), scope)
        val run = builds.start(scope, here, keep = false)
        runCurrent()
        // Four asks fail; the fifth would end it as lost, and it fails just as the view closes: its answer was on its way first.
        repeat(LidarBuilds.MAX_POLL_FAILURES - 1) {
            advanceTimeBy(LidarBuilds.POLL_MS + 1)
            runCurrent()
            server.asks.removeFirst().completeExceptionally(ApiException(503, "builder_unavailable", "down"))
            runCurrent()
        }
        advanceTimeBy(LidarBuilds.POLL_MS + 1)
        runCurrent()
        server.asks.removeFirst().completeExceptionally(ApiException(503, "builder_unavailable", "down"))
        run.close()
        runCurrent()
        assertEquals("release $key", server.calls.last())
        assertTrue("not said to have failed", run.state.value.phase != LidarPhase.ERROR)
        scope.cancel()
    }

    @Test
    fun `the build waited on is kept on the device until it ends or is let go`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher")
        val (builds, scope, server) = rig(waiting = waiting)
        val run = builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals(setOf(key), waiting.inFlight())                                          // an app ended now leaves it to the next run
        assertEquals(LidarPhase.BUILDING, run.state.value.phase)
        run.close()
        runCurrent()
        assertTrue(waiting.inFlight().isEmpty())
        assertEquals("release $key watcher=android-test-watcher", server.calls.last())
        scope.cancel()
    }

    @Test
    fun `a view whose scope ends without closing still lets go of its build`() = runTest {
        val (builds, appScope, server) = rig()
        val viewScope = CoroutineScope(appScope.coroutineContext + Job(appScope.coroutineContext[Job]))
        builds.start(viewScope, here, keep = false)
        runCurrent()
        viewScope.cancel()                                                                   // a view model cleared
        runCurrent()
        assertEquals("release $key watcher=android-test-watcher", server.calls.last())
        appScope.cancel()
    }

    @Test
    fun `trying again lets go of the last pass's build once, and never of the new one's`() = runTest {
        val (builds, scope, server) = rig()
        val run = builds.start(scope, here, keep = false)
        runCurrent()
        run.retry()
        runCurrent()
        assertEquals(1, server.calls.count { it.startsWith("release") })
        assertEquals(LidarPhase.BUILDING, run.state.value.phase)
        advanceTimeBy(LidarBuilds.POLL_MS + 1)
        runCurrent()
        assertEquals("status $key", server.calls.last())                                      // the new pass still waits, and asks
        assertEquals(1, server.calls.count { it.startsWith("release") })
        run.close()
        runCurrent()
        assertEquals(2, server.calls.count { it.startsWith("release") })
        scope.cancel()
    }

    @Test
    fun `a failure lets go of nothing more, as the build it waited on is no longer waited on`() = runTest {
        val waiting = InMemoryLidarWaiting("android-test-watcher")
        val server = Server().apply { buildFails = true }
        val (builds, scope, _) = rig(server = server, waiting = waiting)
        val run = builds.start(scope, here, keep = false)
        runCurrent()
        assertEquals(LidarPhase.ERROR, run.state.value.phase)
        assertEquals(LidarProblem.BUILD_REFUSED, run.state.value.problem)
        assertTrue(waiting.inFlight().isEmpty())
        run.close()
        runCurrent()
        assertTrue(server.calls.none { it.startsWith("release") })
        scope.cancel()
    }

    @Test
    fun `a save's build is kept, waited on by nobody, and nothing is said if it cannot be asked for`() = runTest {
        val server = Server().apply { buildFails = true }
        val (builds, scope, _) = rig(server = server)
        builds.requestKept(here)
        builds.requestKept(LatLon(Double.NaN, -84.0))
        runCurrent()
        // Asked first whether the place streams: a build would only spend minutes where a first view is seconds away.
        assertEquals(listOf("resolve stream", "build keep=true watcher=null"), server.calls)
        scope.cancel()
    }

    @Test
    fun `a save builds nothing where the place streams, as the web's does`() = runTest {
        val server = Server().apply { streams = true }
        val (builds, scope, _) = rig(server = server)
        builds.requestKept(here)
        runCurrent()
        assertEquals(listOf("resolve stream"), server.calls)
        scope.cancel()
    }

    @Test
    fun `a watcher is an opaque id the service takes, new each time one is made`() {
        val one = LidarWaiting.newWatcher()
        val two = LidarWaiting.newWatcher()
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(one))
        assertNotEquals(one, two)
    }

    @Test
    fun `no target, nothing asked`() = runTest {
        val (builds, scope, server) = rig()
        val run = builds.start(scope, LatLon(Double.NaN, -84.0), keep = false)
        runCurrent()
        assertEquals(LidarPhase.IDLE, run.state.value.phase)
        assertTrue(server.calls.isEmpty())
        scope.cancel()
    }

    // -- Build on save -------------------------------------------------------------------------------------------------------

    private class Asked {
        val at = mutableListOf<LatLon>()
        val onSave = BuildOnSave { at += it }
    }

    @Test
    fun `an LZ seen unsaved and then saved asks for its build, once`() {
        val asked = Asked()
        asked.onSave.see(listOf(BuildOnSave.Seen("a", saved = false, target = here)))
        assertTrue(asked.at.isEmpty())
        asked.onSave.see(listOf(BuildOnSave.Seen("a", saved = true, target = here)))
        assertEquals(listOf(here), asked.at)
        asked.onSave.see(listOf(BuildOnSave.Seen("a", saved = true, target = here)))           // a later update of it
        asked.onSave.see(listOf(BuildOnSave.Seen("a", saved = false, target = here)))          // a boundary drawn again clears its analysis
        asked.onSave.see(listOf(BuildOnSave.Seen("a", saved = true, target = here)))           // and the next one is no new save
        assertEquals(1, asked.at.size)
    }

    @Test
    fun `LZs that arrive saved, and one with no target, ask for nothing`() {
        val asked = Asked()
        asked.onSave.see(emptyList())
        asked.onSave.see(listOf(BuildOnSave.Seen("b", saved = true, target = here), BuildOnSave.Seen("c", saved = true, target = here)))
        asked.onSave.see(listOf(BuildOnSave.Seen("d", saved = false, target = null)))
        asked.onSave.see(listOf(BuildOnSave.Seen("d", saved = true, target = null)))
        assertTrue(asked.at.isEmpty())
        BuildOnSave.None.see(listOf(BuildOnSave.Seen("e", saved = false, target = here)))
        BuildOnSave.None.see(listOf(BuildOnSave.Seen("e", saved = true, target = here)))
    }

    @Suppress("unused")
    private val unused = CompletableDeferred<Unit>()
}
