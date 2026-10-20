package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.scene3d.RouteHeights
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routes in 3D on the ground: asked for once they settle, the last placed kept meanwhile, a failure changing nothing. */
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

        override suspend fun at(points: List<LatLon>): RouteHeights {
            asked += points
            hold?.await()
            return answer(points)
        }
    }

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
}
