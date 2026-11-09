package app.ezpztac.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Asking which build of the FAA's charts is live, past every cache, and what it holds. */
class ChartCallsTest {
    @Test
    fun `the config is asked for past every cache only when asked to be`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("config: a chart build live") }
            rig.client.config()
            rig.client.config(fresh = true)
            val (first, fresh) = rig.requestsTo("/api/config")
            assertNull(first.getHeader("Cache-Control"))
            assertEquals("no-cache", fresh.getHeader("Cache-Control"))
            assertNull(fresh.getHeader("Authorization"))
        }
    }

    @Test
    fun `a build's coverage comes as its text, asked for with no token`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setBody("""{"build":"b"}""") }
            assertEquals("""{"build":"b"}""", rig.client.chartCoverage("/charts/vfr/2026-10-29.1/coverage.json"))
            assertNull(rig.requestsTo("/charts/vfr/2026-10-29.1/coverage.json").single().getHeader("Authorization"))
        }
    }

    @Test
    fun `a coverage past the bound is refused as it arrives`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setBody("x".repeat((CHART_COVERAGE_MAX_BYTES + 1).toInt())) }
            val refused = assertThrows<ApiException> { runBlocking { rig.client.chartCoverage("/charts/vfr/b/coverage.json") } }
            assertEquals("too_large", refused.code)
        }
    }

    @Test
    fun `anything but a build's coverage is refused before it is sent`() = runBlocking<Unit> {
        Rig().use { rig ->
            for (path in listOf("/api/auth/me", "/charts/vfr/../coverage.json", "charts/vfr/b/coverage.json", "/charts/vfr/b/x/coverage.json", "https://elsewhere.test/charts/vfr/b/coverage.json")) {
                assertThrows<IllegalArgumentException> { runBlocking { rig.client.chartCoverage(path) } }
            }
            assertEquals(0, rig.requestsTo("/api/auth/me").size)
        }
    }
}
