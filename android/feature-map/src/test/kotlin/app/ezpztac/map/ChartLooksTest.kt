package app.ezpztac.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the VFR map asks which build is live: the web's `useVfrCharts`, by the clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChartLooksTest {
    private companion object {
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
    }

    private fun build(name: String = "b", expires: String = "2028-01-01T09:01Z") = VfrLive(
        build = name, tiles = "", coverage = "", tileSize = 512, minZoom = 5, maxZoom = 14, effective = "2027-01-01T09:01Z", expires = expires,
        bounds = ChartBox(-88.0, 32.0, -72.0, 40.0), fallback = emptyList(),
    )

    private fun covered(name: String = "b", expires: String = "2028-01-01T09:01Z") =
        VfrCharts(build(name, expires), ChartCoverage(name, 5, emptyList()))

    private class Source(initial: VfrCharts) : VfrChartSource {
        override val charts = MutableStateFlow(initial)
        override val tileFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var looks = 0
        var holdNext: CompletableDeferred<Unit>? = null
        var failNext: Exception? = null
        override suspend fun look() {
            looks++
            failNext?.let { failNext = null; throw it }
            holdNext?.let { holdNext = null; it.await() }
        }
    }

    private fun TestScope.clock(): () -> Long = { START + testScheduler.currentTime }

    private fun TestScope.after(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `the first time it shows it looks, and every 5 minutes after`() = runTest {
        val source = Source(covered())
        val job = launch { ChartLooks(source, clock()).run() }
        runCurrent()
        assertEquals(1, source.looks)
        after(5 * MINUTE - 1)
        assertEquals(1, source.looks)
        after(1)
        assertEquals(2, source.looks)
        after(5 * MINUTE)
        assertEquals(3, source.looks)
        job.cancel()
    }

    @Test
    fun `a failed tile looks once a minute at most`() = runTest {
        val source = Source(covered())
        val job = launch { ChartLooks(source, clock()).run() }
        runCurrent()
        after(MINUTE - 1)
        source.tileFailures.emit(Unit)
        runCurrent()
        assertEquals("under a minute since the last look", 1, source.looks)
        after(1)
        source.tileFailures.emit(Unit)
        runCurrent()
        assertEquals(2, source.looks)
        source.tileFailures.emit(Unit)
        source.tileFailures.emit(Unit)
        runCurrent()
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `it looks just after the live build runs out, and not at that very moment`() = runTest {
        // Two minutes on from the start of the clock.
        val source = Source(covered(expires = "2027-01-15T08:02Z"))
        val at = requireNotNull(chartInstant("2027-01-15T08:00Z"))
        val looks = ChartLooks(source) { at + testScheduler.currentTime }
        val job = launch { looks.run() }
        runCurrent()
        assertEquals(1, source.looks)
        after(2 * MINUTE + 61_000 - 1)
        assertEquals(1, source.looks)
        after(1)
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `a build already run out does not hurry it, every 5 minutes as before`() = runTest {
        val source = Source(covered(expires = "2020-01-01T09:01Z"))
        val job = launch { ChartLooks(source, clock()).run() }
        runCurrent()
        after(5 * MINUTE - 1)
        assertEquals(1, source.looks)
        after(1)
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `a look under way is not asked for twice, and is not cut short by the build it brings`() = runTest {
        val source = Source(covered())
        val held = CompletableDeferred<Unit>()
        source.holdNext = held
        val job = launch { ChartLooks(source, clock()).run() }
        runCurrent()
        after(5 * MINUTE)
        assertEquals("the first has not answered", 1, source.looks)
        source.charts.value = covered("c", "2028-02-01T09:01Z")               // what a look finds, part way through
        runCurrent()
        held.complete(Unit)
        runCurrent()
        after(5 * MINUTE)
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `back on the map within 5 minutes it does not look, and after them it does`() = runTest {
        val source = Source(covered())
        val looks = ChartLooks(source, clock())
        var job = launch { looks.run() }
        runCurrent()
        job.cancel()
        after(4 * MINUTE)
        job = launch { looks.run() }
        runCurrent()
        assertEquals(1, source.looks)
        job.cancel()
        after(MINUTE)
        job = launch { looks.run() }
        runCurrent()
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `back on the map with no coverage for this build it looks at once`() = runTest {
        val source = Source(covered())
        val looks = ChartLooks(source, clock())
        var job = launch { looks.run() }
        runCurrent()
        job.cancel()
        source.charts.value = VfrCharts(build("c"), null)
        after(1000)
        job = launch { looks.run() }
        runCurrent()
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `a look that throws costs that look, and the next is still made`() = runTest {
        val source = Source(covered())
        source.failNext = IllegalStateException("anything")
        val job = launch { ChartLooks(source, clock()).run() }
        runCurrent()
        assertEquals(1, source.looks)
        after(5 * MINUTE)
        assertEquals(2, source.looks)
        job.cancel()
    }

    @Test
    fun `leaving before a look has started does not stop later ones`() = runTest {
        val source = Source(covered())
        val looks = ChartLooks(source, clock())
        val first = launch { looks.run() }
        runCurrent()
        first.cancel()
        // Cancelled at once: the look it would have made never ran.
        val second = launch { looks.run() }
        second.cancel()
        runCurrent()
        after(5 * MINUTE)
        val third = launch { looks.run() }
        runCurrent()
        assertEquals(2, source.looks)
        third.cancel()
    }
}
