package app.ezpztac.data

import app.ezpztac.formats.FormatException
import app.ezpztac.formats.LpsReader
import app.ezpztac.formats.MsnxReader
import app.ezpztac.formats.ThsReader
import app.ezpztac.model.Mission
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What a file from another app is, said before anything is imported. The files are the contract's own fixtures, so the readers are the ones that read the web's. */
class FileInspectorTest {
    private val inspector = FileInspector(ThreatTransfer(ThsWriter { ByteArray(0) }))
    private val threats = Fixtures.bytes("sqlite/threats.ths")
    private val points = Fixtures.bytes("sqlite/local-points.lps")

    @Test
    fun `a threat file says how many threats it would add`() {
        val preview = inspector.inspect("SA-6 site.ths", threats) as FilePreview.Threats
        assertEquals(ThsReader.read(threats).size, preview.threats.size)
        assertTrue(preview.threats.isNotEmpty())
    }

    @Test
    fun `a points file says the set it would make and how many points are in it`() {
        val preview = inspector.inspect("NORTH GA.LPS", points) as FilePreview.Points
        assertEquals("NORTH GA", preview.setName)
        assertEquals(LpsReader.read(points, "x").points.size, preview.count)
    }

    @Test
    fun `a points file called only dot lps is called local points, as the import does`() {
        assertEquals("LOCAL POINTS", (inspector.inspect(".lps", points) as FilePreview.Points).setName)
    }

    @Test
    fun `what is in a file decides, not what it is called`() {
        assertTrue(inspector.inspect("points.lps", threats) is FilePreview.Threats)
        assertTrue(inspector.inspect("threats.ths", points) is FilePreview.Points)
        assertTrue(inspector.inspect("anything", points) is FilePreview.Points)
    }

    @Test
    fun `a mission says how many routes and named points would come in, and what it was planned for`() {
        val preview = inspector.inspect("plan.msnx", Fixtures.bytes("msnx/template.msnx")) as FilePreview.Mission
        assertEquals(1, preview.routes)
        assertEquals("UH-60L", preview.aircraft)
        val route = MsnxReader.read(Fixtures.bytes("msnx/template.msnx")).routes.single()
        assertEquals(route.points.count { it.kind == RoutePoint.KIND_AMPS }, preview.namedPoints)
        assertTrue(preview.namedPoints in 1 until route.points.size)                          // shaping points are not counted
    }

    @Test
    fun `a mission with several routes counts each`() {
        val preview = inspector.inspect("two.msnx", Fixtures.bytes("msnx/sketch-two-routes.msnx")) as FilePreview.Mission
        assertEquals(MsnxReader.read(Fixtures.bytes("msnx/sketch-two-routes.msnx")).routes.count { it.points.isNotEmpty() }, preview.routes)
    }

    @Test
    fun `a zip that is not a mission is refused in the reader's words`() {
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("readme.txt")); it.write("hi".toByteArray()); it.closeEntry() }
        }.toByteArray()
        val words = try { MsnxReader.read(zip); null } catch (e: FormatException) { e.message }!!
        assertEquals(FilePreview.Problem(words), inspector.inspect("x.zip", zip))
    }

    @Test
    fun `a file named dot msnx that is not a zip is refused rather than read`() {
        assertTrue(inspector.inspect("x.msnx", "not a zip".toByteArray()) is FilePreview.Problem)
    }

    @Test
    fun `a threat file with no threats in it is refused in words`() {
        val preview = inspector.inspect("empty.ths", Fixtures.bytes("sqlite/threats-empty.ths")) as FilePreview.Problem
        assertEquals(refusalOf { ThsReader.read(Fixtures.bytes("sqlite/threats-empty.ths")) }!!, preview.message)
    }

    @Test
    fun `a points file the reader refuses is refused in the reader's own words`() {
        val bad = Fixtures.bytes("sqlite/values.db")
        val words = refusalOf { LpsReader.read(bad, "x.lps") }!!
        assertEquals(FilePreview.Problem(words), inspector.inspect("x.lps", bad))
    }

    @Test
    fun `a file that is none of these says what the app does open`() {
        val preview = inspector.inspect("notes.txt", "hello".toByteArray()) as FilePreview.Problem
        assertTrue(preview.message, preview.message.contains(".LPS") && preview.message.contains(".ths") && preview.message.contains(".msnx"))
    }

    @Test
    fun `a file with the right name and the wrong insides is refused by the reader, never an exception`() {
        assertTrue(inspector.inspect("x.ths", "not a database".toByteArray()) is FilePreview.Problem)
        assertTrue(inspector.inspect("x.lps", threats.copyOf(120)) is FilePreview.Problem)
        assertTrue(inspector.inspect("x.ths", ByteArray(0)) is FilePreview.Problem)
    }

    @Test
    fun `a mission whose routes have no points has nothing to bring in`() {
        val empty = Mission(listOf(MissionRoute("A", points = emptyList(), plan = RoutePlan()), MissionRoute("B", points = emptyList(), plan = RoutePlan())))
        assertEquals(FilePreview.Problem("This mission has no routes with points to bring in."), inspector.missionPreview(empty))
        assertEquals(FilePreview.Problem("This mission has no routes with points to bring in."), inspector.missionPreview(Mission(emptyList())))
    }

    @Test
    fun `only routes with points are counted, and shaping points are not named points`() {
        val point = { id: String, kind: String -> RoutePoint(id = id, lat = 1.0, lon = 1.0, kind = kind, name = id) }
        val mission = Mission(
            listOf(
                MissionRoute("EMPTY", points = emptyList(), plan = RoutePlan()),
                MissionRoute("A", points = listOf(point("a", RoutePoint.KIND_AMPS), point("s", RoutePoint.KIND_SHAPING), point("b", RoutePoint.KIND_AMPS)), plan = RoutePlan()),
            ),
            aircraft = app.ezpztac.model.MissionAircraft("Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L", "UH-60L"),
        )
        val preview = inspector.missionPreview(mission) as FilePreview.Mission
        assertEquals(1, preview.routes)
        assertEquals(2, preview.namedPoints)
        assertEquals("UH-60L", preview.aircraft)
    }

    private fun refusalOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: FormatException) {
        e.message
    }.also { if (it == null) fail("expected a refusal") }
}
