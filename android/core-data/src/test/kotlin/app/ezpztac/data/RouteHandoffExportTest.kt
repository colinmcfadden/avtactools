package app.ezpztac.data

import app.ezpztac.formats.RouteHandoff
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RouteHandoffExportTest {
    private val export = RouteHandoffExport()
    private val now = Instant.parse("2026-10-03T12:34:56.789Z")

    private fun route(name: String = "NEPTUNE RUN", vararg names: String?) = SketchRoute(
        id = "sketch-1", name = name, color = "#FF453A",
        points = names.mapIndexed { i, n -> RoutePoint(id = "p$i", lat = 34.7 + i * 0.01, lon = -84.1, name = n, kind = if (n == null) RoutePoint.KIND_SHAPING else RoutePoint.KIND_AMPS) },
    )

    @Test
    fun `a GPX is the route's points, named for the route`() {
        val ready = export.build(route("NEPTUNE RUN", ".LZ", null, ".TGT"), HandoffFormat.GPX, now) as ExportResult.Ready
        assertEquals("NEPTUNE_RUN.gpx", ready.fileName)
        assertEquals(RouteHandoff.gpx(route("NEPTUNE RUN", ".LZ", null, ".TGT")), String(ready.bytes, Charsets.UTF_8))
        assertEquals(null, ready.warning)
    }

    @Test
    fun `a flight plan carries the time it was made`() {
        val ready = export.build(route("A", ".LZ", ".TGT"), HandoffFormat.FPL, now) as ExportResult.Ready
        assertEquals("A.fpl", ready.fileName)
        assertTrue(String(ready.bytes, Charsets.UTF_8).contains("<created>2026-10-03T12:34:56.789Z</created>"))
    }

    @Test
    fun `the file is UTF-8 with no byte order mark`() {
        val ready = export.build(route("A", "É"), HandoffFormat.GPX, now) as ExportResult.Ready
        assertEquals('<'.code.toByte(), ready.bytes[0])
    }

    @Test
    fun `a route with no points is refused by name`() {
        val refused = export.build(route("EMPTY"), HandoffFormat.GPX, now) as ExportResult.Refused
        assertEquals("EMPTY has no points to share.", refused.message)
    }

    @Test
    fun `every format has its own extension`() {
        assertEquals(listOf("gpx", "fpl"), HandoffFormat.entries.map { it.extension })
    }
}
