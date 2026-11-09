package app.ezpztac.android

import app.ezpztac.network.ApiException
import app.ezpztac.network.AppConfig
import app.ezpztac.network.NetworkException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Which build of the FAA's charts the device holds: remembered as the token is, and what it holds kept beside it. */
class ChartConfigTest {
    private class Memory(var text: String? = null) : ChartMemory {
        var writes = 0
        override fun chartConfig() = text
        override fun setChartConfig(text: String?) { this.text = text; writes++ }
    }

    private class Kept(var text: String? = null) : CoverageFile {
        var reads = 0
        var writes = 0
        override fun read(): String? { reads++; return text }
        override fun write(text: String) { this.text = text; writes++ }
    }

    private class Calls(var config: () -> AppConfig, var coverage: (String) -> String) : ChartCalls {
        val coverages = mutableListOf<String>()
        override suspend fun config(): AppConfig = config.invoke()
        override suspend fun coverage(path: String): String { coverages += path; return coverage.invoke(path) }
    }

    private fun vfr(build: String = "b1") = AppConfig.Charts.Vfr(
        build = build, cycle = "2026-10-29", tiles = "/charts/vfr/$build/{z}/{x}/{y}.webp", tileSize = 512, minZoom = 5, maxZoom = 14,
        effective = "2026-10-29T09:01Z", expires = "2026-12-24T09:01Z", coverage = "/charts/vfr/$build/coverage.json",
        bounds = listOf(-125.0, 24.0, -66.0, 50.0), fallback = listOf(listOf(-158.9, 18.6, -154.7, 22.6)),
    )

    private fun config(vfr: AppConfig.Charts.Vfr?) = AppConfig(
        1, "1.7.6", AppConfig.MinAppVersion(), AppConfig.Maintenance(false), AppConfig.Services(false, false), AppConfig.MapboxConfig("pk.x"),
        charts = vfr?.let { AppConfig.Charts(it) },
    )

    private fun coverage(build: String = "b1") = """{"build":"$build","minLevel":5,"charts":[]}"""

    private val json = Json { ignoreUnknownKeys = true }
    private fun remembered(vfr: AppConfig.Charts.Vfr) = json.encodeToString(AppConfig.Charts.Vfr.serializer(), vfr)

    private fun chartConfig(calls: ChartCalls, memory: Memory = Memory(), kept: Kept = Kept()) =
        ChartConfig(calls, memory, kept, "https://api.test/", emptyFlow())

    private val noCalls get() = Calls({ throw NetworkException("none", requestMayHaveBeenSent = false) }, { throw NetworkException("none", requestMayHaveBeenSent = false) })

    @Test
    fun `a build remembered is drawn at once, with no answer from anyone`() {
        val charts = chartConfig(noCalls, Memory(remembered(vfr()))).charts.value
        assertEquals("b1", charts.live?.build)
        assertEquals("https://api.test/charts/vfr/b1/{z}/{x}/{y}.webp", charts.live?.tiles)
    }

    @Test
    fun `nothing remembered, or what cannot be read, is no build`() {
        assertNull(chartConfig(noCalls).charts.value.live)
        assertNull(chartConfig(noCalls, Memory("{not json")).charts.value.live)
        assertNull(chartConfig(noCalls, Memory("""{"build":3}""")).charts.value.live)
    }

    @Test
    fun `the config's build is kept for the next start`() {
        val memory = Memory()
        chartConfig(noCalls, memory).update(vfr())
        assertEquals("b1", chartConfig(noCalls, memory).charts.value.live?.build)
    }

    @Test
    fun `the same build changes nothing`() {
        val memory = Memory(remembered(vfr()))
        val config = chartConfig(noCalls, memory)
        val before = config.charts.value
        config.update(vfr())
        assertEquals(0, memory.writes)
        assertEquals(before, config.charts.value)
    }

    @Test
    fun `an answer with no build takes the VFR map back to ArcGIS, and forgets it`() {
        val memory = Memory(remembered(vfr()))
        val config = chartConfig(noCalls, memory)
        config.update(null)
        assertNull(config.charts.value.live)
        assertNull(memory.text)
    }

    @Test
    fun `a build the server names that cannot be drawn is not remembered`() {
        val memory = Memory(remembered(vfr()))
        val config = chartConfig(noCalls, memory)
        config.update(vfr("b2").copy(tileSize = 0))
        assertNull(config.charts.value.live)
        assertNull(memory.text)
    }

    @Test
    fun `a look fetches what the build holds, and keeps it on the device`() = runBlocking<Unit> {
        val kept = Kept()
        val calls = Calls({ config(vfr()) }, { coverage() })
        val config = chartConfig(calls, kept = kept)
        config.look()
        assertEquals("b1", config.charts.value.coverage?.build)
        assertEquals(listOf("/charts/vfr/b1/coverage.json"), calls.coverages)
        assertEquals(coverage(), kept.text)
    }

    @Test
    fun `what the build holds is not fetched twice`() = runBlocking<Unit> {
        val calls = Calls({ config(vfr()) }, { coverage() })
        val config = chartConfig(calls)
        config.look()
        config.look()
        assertEquals(1, calls.coverages.size)
    }

    @Test
    fun `a new build lets go of the last one's coverage and fetches its own`() = runBlocking<Unit> {
        var build = "b1"
        val calls = Calls({ config(vfr(build)) }, { coverage(build) })
        val config = chartConfig(calls)
        config.look()
        build = "b2"
        config.update(vfr("b2"))
        assertNull("the words fall back to the build's own edition", config.charts.value.coverage)
        config.look()
        assertEquals("b2", config.charts.value.coverage?.build)
    }

    @Test
    fun `a look with no answer leaves what was known`() = runBlocking<Unit> {
        val memory = Memory(remembered(vfr()))
        val config = chartConfig(noCalls, memory)
        config.look()
        assertEquals("b1", config.charts.value.live?.build)
        assertNotNull(memory.text)
    }

    @Test
    fun `a look whose coverage fails keeps the build, and a coverage of another build is not taken`() = runBlocking<Unit> {
        val failing = Calls({ config(vfr()) }, { throw ApiException(500, "x", "x") })
        val config = chartConfig(failing)
        config.look()
        assertEquals("b1", config.charts.value.live?.build)
        assertNull(config.charts.value.coverage)
        failing.coverage = { coverage("other") }
        config.look()
        assertNull(config.charts.value.coverage)
    }

    @Test
    fun `a path that is not a build's coverage costs that look`() = runBlocking<Unit> {
        val config = chartConfig(Calls({ config(vfr()) }, { throw IllegalArgumentException("no") }))
        config.look()
        assertEquals("b1", config.charts.value.live?.build)
    }

    @Test
    fun `an offline start has the coverage kept for the build remembered, read once, and never another build's`() = runBlocking<Unit> {
        val kept = Kept(coverage())
        val config = chartConfig(noCalls, Memory(remembered(vfr())), kept)
        config.look()
        config.look()
        assertEquals("b1", config.charts.value.coverage?.build)
        assertEquals(1, kept.reads)

        val stale = chartConfig(noCalls, Memory(remembered(vfr("b2"))), Kept(coverage("b1")))
        stale.look()
        assertNull(stale.charts.value.coverage)
    }
}
