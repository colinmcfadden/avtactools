package app.ezpztac.data

import app.ezpztac.formats.MsnxReader
import app.ezpztac.model.Mission
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.planning.MissionRoutes
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A mission's routes becoming a set of the person's own: saved, opened, the map taken to it, and the file left alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class MissionImporterTest {
    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val scheduler = RecordingScheduler()
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = RouteSession(repository, scope.backgroundScope)
        val focus = MapFocus()
        val importer = MissionImporter(repository, session, focus)
    }

    private val template = MsnxReader.read(Fixtures.bytes("msnx/template.msnx"))
    private val two = MsnxReader.read(Fixtures.bytes("msnx/sketch-two-routes.msnx"))

    @Test
    fun `a mission's routes become a new set, named for the file, with their points and plans`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(template, "Neptune Run.msnx") as MissionImportOutcome.Imported
        assertEquals("NEPTUNE RUN", outcome.set.name)
        val saved = r.repository.open(outcome.set.id)!!
        assertEquals(1, saved.routes.size)
        val route = saved.routes.single()
        assertEquals("NEPTUNE", route.name)
        assertEquals(template.routes.single().points, route.points)
        assertEquals(template.routes.single().plan, route.plan)
        assertTrue(route.id.startsWith("sketch-"))
    }

    @Test
    fun `it is queued for the server like any set the person makes`() = runTest {
        val r = Rig(this)
        r.importer.import(template, "a.msnx")
        assertTrue(r.scheduler.requested > 0)
    }

    @Test
    fun `every route of a mission comes in, each in a colour of its own`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(two, "two.msnx") as MissionImportOutcome.Imported
        val withPoints = two.routes.count { it.points.isNotEmpty() }
        assertEquals(withPoints, outcome.set.routes.size)
        assertEquals(RouteColors.PALETTE.take(withPoints), outcome.set.routes.map { it.color })
    }

    @Test
    fun `the new set is opened`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(template, "a.msnx") as MissionImportOutcome.Imported
        assertTrue(outcome.opened)
        assertEquals(outcome.set.id, r.session.active.value!!.id)
    }

    @Test
    fun `the map is taken to the routes, at a zoom that fits them`() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        val seen = mutableListOf<MapFocus.Request>()
        val job = launch { r.focus.requests.collect { seen += it } }
        val outcome = r.importer.import(template, "a.msnx") as MissionImportOutcome.Imported
        val extent = MissionRoutes.extentOf(outcome.set.routes)!!
        assertEquals(1, seen.size)
        assertEquals(extent.center, seen.single().at)
        assertTrue(seen.single().zoom in 3.0..17.0)
        job.cancel()
    }

    @Test
    fun `a file called only dot msnx, or with a long name, still makes a usable name`() = runTest {
        val r = Rig(this)
        assertEquals("IMPORTED MISSION", (r.importer.import(template, ".msnx") as MissionImportOutcome.Imported).set.name)
        assertEquals("IMPORTED MISSION", (r.importer.import(template, "   .MSNX") as MissionImportOutcome.Imported).set.name)
        val long = (r.importer.import(template, "x".repeat(300) + ".msnx") as MissionImportOutcome.Imported).set.name
        assertEquals(80, long.length)
    }

    @Test
    fun `a mission with no route that has a point is refused, and nothing is made`() = runTest {
        val r = Rig(this)
        val empty = Mission(listOf(MissionRoute("EMPTY", points = emptyList(), plan = RoutePlan())))
        val outcome = r.importer.import(empty, "empty.msnx") as MissionImportOutcome.Refused
        assertEquals("This mission has no routes with points to bring in.", outcome.message)
        assertTrue(r.repository.observe().first().isEmpty())
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `importing the same mission twice makes two sets, since each import is a copy`() = runTest {
        val r = Rig(this)
        r.importer.import(template, "a.msnx")
        r.importer.import(template, "a.msnx")
        assertEquals(2, r.repository.observe().first().size)
    }

    @Test
    fun `the point of a route that was a shaping point stays one`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(template, "a.msnx") as MissionImportOutcome.Imported
        val shaping = outcome.set.routes.single().points.filter { it.kind == RoutePoint.KIND_SHAPING }
        assertNotNull(shaping.firstOrNull())
        assertTrue(shaping.all { it.ptType == null })
    }

    @Test
    fun `a set made from a mission exports as a mission that reads back with the same named points in the same order`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(template, "template.msnx") as MissionImportOutcome.Imported
        val export = RouteExport(MissionTemplate { Fixtures.repoBytes("frontend/public/msnx_template.msnx") })
        val built = export.build(outcome.set.routes, java.time.LocalDate.of(2026, 10, 3)) as ExportResult.Ready
        val back = MsnxReader.read(built.bytes)
        fun named(m: Mission) = m.routes.single().points.filter { it.kind == RoutePoint.KIND_AMPS }.map { it.name?.removePrefix(".") }
        assertEquals(named(template), named(back))
        assertEquals(template.routes.single().name, back.routes.single().name)
    }
}
