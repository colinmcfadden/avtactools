package app.ezpztac.data

import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.model.SketchRoute
import app.ezpztac.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class RouteExportTest {
    private val template = MissionTemplate { Fixtures.repoBytes("frontend/public/msnx_template.msnx") }
    private val export = RouteExport(template)
    private val today = LocalDate.of(2026, 10, 3)

    private fun named(id: String, lat: Double) = RoutePoint(id = id, lat = lat, lon = -84.1, kind = RoutePoint.KIND_AMPS, ptType = "turn", name = id.uppercase())

    private fun route(name: String, plan: RoutePlan = RoutePlan()) = SketchRoute(
        id = "r-$name", name = name, color = "#FF453A", plan = plan, points = listOf(named("a", 34.70), named("b", 34.71), named("c", 34.72)),
    )

    private fun entries(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> generateSequence { zip.nextEntry }.forEach { names += it.name } }
        return names
    }

    @Test
    fun `routes are exported as a mission named for them`() {
        val ready = export.build(listOf(route("INGRESS"), route("EGRESS")), today) as ExportResult.Ready
        assertEquals("INGRESS_EGRESS.msnx", ready.fileName)
        assertNull(ready.warning)
        val parts = entries(ready.bytes)
        assertTrue(parts.toString(), "mission/routes.xml" in parts)
        assertTrue(parts.toString(), "mission.gpx" in parts)
        assertEquals(entries(template.bytes()).sorted(), parts.sorted())                       // the package has what the template had
    }

    @Test
    fun `a route with fewer than two named points is refused by name`() {
        val lone = route("LONE").copy(points = listOf(named("a", 34.7), RoutePoint(id = "s", lat = 34.71, lon = -84.1, kind = RoutePoint.KIND_SHAPING)))
        assertEquals(ExportResult.Refused("LONE needs at least two named points before it can be exported."), export.build(listOf(route("FINE"), lone), today))
        val idless = route("IDLESS").copy(points = listOf(named("a", 34.7), RoutePoint(lat = 34.71, lon = -84.1, kind = RoutePoint.KIND_AMPS)))
        assertEquals(ExportResult.Refused("IDLESS needs at least two named points before it can be exported."), export.build(listOf(idless), today))
    }

    @Test
    fun `nothing to export is said`() {
        assertEquals(ExportResult.Refused("There is no route to export."), export.build(emptyList(), today))
    }

    @Test
    fun `a template that cannot be used is a refusal, in words`() {
        val broken = RouteExport { "not a zip".toByteArray() }
        val refused = broken.build(listOf(route("A")), today)
        assertTrue(refused.toString(), refused is ExportResult.Refused && refused.message.startsWith("The mission could not be built: "))
    }

    @Test
    fun `a route planned for another airframe opens in AMPS as a UH-60L, and says so`() {
        val chinook = route("CH47", RoutePlan(aircraft = "CH-47F Chinook", aircraftProfile = "ch47f"))
        val ready = export.build(listOf(route("A"), chinook), today) as ExportResult.Ready
        assertEquals(
            "This mission will open in AMPS as a UH-60L, not a CH-47F Chinook. Planned speeds, altitudes, and winds still export correctly. " +
                "To get a true CH-47F Chinook file, an administrator needs to attach that airframe's AMPS package to the profile.",
            ready.warning,
        )
        assertNotNull(ready.bytes)
    }

    @Test
    fun `the UH-60L slug is recognised however it is written, and an unnamed airframe is called what it is`() {
        assertNull((export.build(listOf(route("A", RoutePlan(aircraftProfile = "UH60L"))), today) as ExportResult.Ready).warning)
        val anon = route("B", RoutePlan(aircraft = "  ", aircraftProfile = "mystery"))
        assertEquals(
            "This mission will open in AMPS as a UH-60L, not the selected aircraft. Planned speeds, altitudes, and winds still export correctly. " +
                "To get a file for the selected aircraft, an administrator needs to attach that airframe's AMPS package to the profile.",
            (export.build(listOf(anon), today) as ExportResult.Ready).warning,
        )
    }

    @Test
    fun `a file name is made safe, and is never empty`() {
        fun name(vararg names: String) = RouteExport.fileName(names.map { route(it) })
        assertEquals("ROUTE 1.msnx", name("ROUTE 1"))
        assertEquals("A_B.msnx", name("A", "B"))
        assertEquals("A_B_C.msnx", name("A/B", "C"))                                           // a slash would be a path
        assertEquals("NIGHT_RUN.msnx", name("NIGHT*RUN"))
        assertEquals("ROUTES.msnx", name("///"))
        assertEquals("ROUTES.msnx", name(""))
        assertEquals("A_B.msnx", name("..A..B.."))                                             // no dot-dot to climb out of the folder
        assertEquals("ÉCLAIR.msnx", name("ÉCLAIR"))                                            // letters of any alphabet are letters
        assertEquals(RouteExport.fileName(listOf(route("A".repeat(300)))).length, 80 + ".msnx".length)
        assertTrue(name("A\u0000B").none { it == '\u0000' })
        assertTrue(name("a:b|c?d\"e<f>g\\h").matches(Regex("""[A-Za-z_.]+""")))
    }

    @Test
    fun `routes of a set are all of them, or the one asked for`() {
        val set = RouteSet(id = "s", name = "SET", routes = listOf(route("A"), route("B")))
        assertEquals(listOf("A", "B"), export.routesOf(set).map { it.name })
        assertEquals(listOf("B"), export.routesOf(set, "r-B").map { it.name })
        assertEquals(emptyList<SketchRoute>(), export.routesOf(set, "nope"))
    }

    @Test
    fun `two exports of the same routes differ only in the ids AMPS is given`() {
        val a = (export.build(listOf(route("A")), today) as ExportResult.Ready).bytes
        val b = (export.build(listOf(route("A")), today) as ExportResult.Ready).bytes
        assertEquals(entries(a), entries(b))
    }
}
