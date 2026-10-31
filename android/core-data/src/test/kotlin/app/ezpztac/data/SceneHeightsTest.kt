package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import app.ezpztac.scene3d.RouteHeights
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routes in 3D on the ground: asked for once they settle, the last placed kept meanwhile, a failure changing nothing, one that may pass asked again. */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneHeightsTest {
    private fun route(id: String, toLat: Double = 34.61, visible: Boolean = true) = SketchRoute(
        id = id, name = "ROUTE $id", color = "#22c55e", visible = visible,
        points = listOf(
            RoutePoint(id = "a", lat = 34.6, lon = -84.1, kind = "amps", ptType = "ip", name = ".SP"),
            RoutePoint(id = "b", lat = toLat, lon = -84.09, kind = "amps", ptType = "ip", name = ".RP"),
        ),
        plan = RoutePlan(),
    )

    private class Ground : GroundUnder {
        val asked = mutableListOf<List<LatLon>>()
        var answer: (List<LatLon>) -> RouteHeights = { points -> RouteHeights(points.map { 300.0 }, points.map { -30.35 }) }
        var hold: CompletableDeferred<Unit>? = null
        /** Thrown by the next asks, one each, before any answer. */
        val failures = ArrayDeque<Exception>()

        override suspend fun at(points: List<LatLon>): RouteHeights {
            asked += points
            hold?.await()
            failures.removeFirstOrNull()?.let { throw it }
            return answer(points)
        }
    }

    private val noSignal get() = NetworkException("no route", requestMayHaveBeenSent = false)

    @Test
    fun `routes are placed once they have been still for 400 ms, asked for once however often they changed`() = runTest {
        val ground = Ground()
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1", 34.611)))
        advanceTimeBy(200)
        heights.routes(listOf(route("1", 34.612)))
        advanceTimeBy(200)
        heights.routes(listOf(route("1", 34.613)))
        advanceTimeBy(399)
        runCurrent()
        assertTrue(ground.asked.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, ground.asked.size)
        assertEquals(LatLon(34.6, -84.1), ground.asked.single().first())
        assertEquals(listOf("1"), heights.placed.value.map { it.id })
        // The same routes again ask nothing.
        heights.routes(listOf(route("1", 34.613)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, ground.asked.size)
    }

    @Test
    fun `the routes last placed stay while the next answer comes, and an answer that fails changes nothing`() = runTest {
        val ground = Ground()
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1")))
        advanceTimeBy(500)
        runCurrent()
        val first = heights.placed.value
        assertEquals(1, first.size)
        ground.hold = CompletableDeferred()
        heights.routes(listOf(route("1"), route("2")))
        advanceTimeBy(500)
        runCurrent()
        assertEquals("kept while the answer is out", first, heights.placed.value)
        ground.hold!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("1", "2"), heights.placed.value.map { it.id })
        ground.hold = null
        ground.answer = { throw java.io.IOException("no route") }
        heights.routes(listOf(route("2")))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("1", "2"), heights.placed.value.map { it.id })
    }

    @Test
    fun `no routes to draw, or only hidden ones, is nothing at once, and closing asks nothing more`() = runTest {
        val ground = Ground()
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1")))
        advanceTimeBy(500)
        runCurrent()
        heights.routes(listOf(route("1", visible = false)))
        assertTrue(heights.placed.value.isEmpty())
        heights.routes(listOf(route("2")))
        heights.close()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, ground.asked.size)
        assertTrue(heights.placed.value.isEmpty())
    }

    @Test
    fun `an ask that met no connection or the server down is made again after 5 s and then 10 s, and placed when it answers`() = runTest {
        val ground = Ground()
        ground.failures += listOf(noSignal, ApiException(503, null, "busy"))
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1")))
        assertEquals(RoutesPlacing.PLACING, heights.standing.value)
        // Asked at 400 ms, again 5 s after that failed, and again 10 s after the second.
        advanceTimeBy(401)
        runCurrent()
        assertEquals(1, ground.asked.size)
        assertEquals(RoutesPlacing.PLACING, heights.standing.value)
        advanceTimeBy(4_998)
        runCurrent()
        assertEquals("not before 5 s", 1, ground.asked.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, ground.asked.size)
        advanceTimeBy(9_998)
        runCurrent()
        assertEquals("then 10 s", 2, ground.asked.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, ground.asked.size)
        assertEquals(listOf("1"), heights.placed.value.map { it.id })
        assertEquals(RoutesPlacing.PLACED, heights.standing.value)
    }

    @Test
    fun `after three asks that fail the routes stand failed, what was placed still drawn, and the same routes or a retry ask again`() = runTest {
        val ground = Ground()
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1")))
        advanceTimeBy(500)
        runCurrent()
        val before = heights.placed.value
        ground.failures += listOf(noSignal, RateLimitedException("slow down", null), noSignal)
        heights.routes(listOf(route("1"), route("2")))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, ground.asked.size)
        assertEquals(RoutesPlacing.FAILED, heights.standing.value)
        assertEquals("kept", before, heights.placed.value)
        // Nothing more by itself.
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(4, ground.asked.size)
        // The same routes, given again, ask again.
        heights.routes(listOf(route("1"), route("2")))
        assertEquals(RoutesPlacing.PLACING, heights.standing.value)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(5, ground.asked.size)
        assertEquals(listOf("1", "2"), heights.placed.value.map { it.id })
        // A retry asks only for routes that failed.
        heights.retry()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(5, ground.asked.size)
        ground.failures += listOf(ApiException(400, "bad_request", "no"))
        heights.routes(listOf(route("2")))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(RoutesPlacing.FAILED, heights.standing.value)
        heights.retry()
        assertEquals(RoutesPlacing.PLACING, heights.standing.value)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(7, ground.asked.size)
        assertEquals(listOf("2"), heights.placed.value.map { it.id })
        assertEquals(RoutesPlacing.PLACED, heights.standing.value)
    }

    @Test
    fun `a failure that will not pass is not asked again by itself`() = runTest {
        for (failure in listOf(ApiException(400, "bad_request", "no"), ApiException(200, "unreadable_response", "?"), SessionEndedException(SignedOutReason.SESSION_ENDED, null, "gone"))) {
            val ground = Ground()
            ground.failures += failure
            val heights = SceneHeights(ground, backgroundScope)
            heights.routes(listOf(route("1")))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("${failure.message}", 1, ground.asked.size)
            assertEquals(RoutesPlacing.FAILED, heights.standing.value)
            heights.close()
        }
    }

    @Test
    fun `the routes stand as none, placing, placed, and none again once closed or with nothing to draw`() = runTest {
        val ground = Ground()
        val heights = SceneHeights(ground, backgroundScope)
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
        heights.routes(listOf(route("1", visible = false)))
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
        heights.routes(listOf(route("1")))
        assertEquals(RoutesPlacing.PLACING, heights.standing.value)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(RoutesPlacing.PLACED, heights.standing.value)
        heights.routes(emptyList())
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
        heights.routes(listOf(route("1")))
        heights.close()
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
    }

    @Test
    fun `an ask that fails as the view closes says nothing and asks nothing more`() = runTest {
        val ground = Ground()
        ground.hold = CompletableDeferred()
        ground.failures += noSignal
        val heights = SceneHeights(ground, backgroundScope)
        heights.routes(listOf(route("1")))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, ground.asked.size)
        heights.close()
        ground.hold!!.complete(Unit)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, ground.asked.size)
        assertEquals(RoutesPlacing.NONE, heights.standing.value)
    }
}
