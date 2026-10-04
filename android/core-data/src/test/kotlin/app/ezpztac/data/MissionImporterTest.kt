package app.ezpztac.data

import app.ezpztac.formats.MsnxReader
import app.ezpztac.model.Mission
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSets
import app.ezpztac.planning.MissionRoutes
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mission brought in as it is: its file kept as the saved document, opened as a set of routes, queued for the server, and the map taken to it. (Opening and saving it again
 * is [SavedMissionTest].)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MissionImporterTest {
    private class Rig(scope: TestScope) {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = RouteSession(repository, scope.backgroundScope)
        val focus = MapFocus()
        val importer = MissionImporter(repository, session, focus)
    }

    private val templateBytes = Fixtures.bytes("msnx/template.msnx")
    private val template = MsnxReader.read(templateBytes)
    private val twoBytes = Fixtures.bytes("msnx/sketch-two-routes.msnx")
    private val two = MsnxReader.read(twoBytes)

    @Test
    fun `the file is kept as the saved document, named for the file, with a summary for lists`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(templateBytes, template, "Neptune Run.msnx") as MissionImportOutcome.Imported
        assertEquals("NEPTUNE RUN", outcome.set.name)
        val record = r.device.record(RecordKind.MISSION, outcome.set.id)!!
        assertEquals("NEPTUNE RUN", record.name)
        assertEquals("Neptune Run.msnx", record.file!!.name)
        assertTrue(templateBytes.contentEquals(r.device.repository.file(RecordKind.MISSION, outcome.set.id)!!))
        assertEquals(listOf("NEPTUNE"), (record.data["routes"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonObject)["name"]!!.toString().trim('"') })
        assertEquals(1, r.device.repository.records(RecordKind.MISSION).size)
        assertEquals(0, r.device.repository.records(RecordKind.ROUTE).size)         // not a set drawn here: nothing in the sets
    }

    @Test
    fun `it is queued for the server with its file, like anything the person makes`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(templateBytes, template, "a.msnx") as MissionImportOutcome.Imported
        assertTrue(r.scheduler.requested > 0)
        val entry = r.device.outbox().single()
        assertEquals(RecordKind.MISSION, entry.kind)
        assertEquals(Operation.CREATE, entry.operation)
        r.device.sync()
        // The server was sent the file itself, byte for byte, under the name it had.
        val held = r.server.live(RecordKind.MISSION).single()
        assertTrue(templateBytes.contentEquals(held.file!!))
        assertEquals("a.msnx", held.fileName)
        assertEquals(outcome.set.id, held.uuid)
    }

    @Test
    fun `the mission is opened as a set of its routes, which know the mission they are part of`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(templateBytes, template, "a.msnx") as MissionImportOutcome.Imported
        assertTrue(outcome.opened)
        val open = r.session.active.value!!
        assertEquals(outcome.set.id, open.id)
        assertEquals("a.msnx", open.mission!!.fileName)
        val route = open.routes.single()
        assertEquals("NEPTUNE", route.name)
        assertEquals(template.routes.single().segmentId, route.segmentId)
        assertEquals(template.routes.single().points, route.points)
        assertEquals(template.routes.single().plan, route.plan)
    }

    @Test
    fun `every route of a mission comes in, each in a colour of its own`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(twoBytes, two, "two.msnx") as MissionImportOutcome.Imported
        assertEquals(two.routes.size, outcome.set.routes.size)
        assertEquals(RouteColors.PALETTE.take(two.routes.size), outcome.set.routes.map { it.color })
    }

    @Test
    fun `a route with no points is still a route of the mission, because the file has it`() = runTest {
        val r = Rig(this)
        val withEmpty = Mission(listOf(MissionRoute("EMPTY", "seg-empty", emptyList(), RoutePlan()), template.routes.single()))
        val outcome = r.importer.import(templateBytes, withEmpty, "mixed.msnx") as MissionImportOutcome.Imported
        assertEquals(listOf("EMPTY", "NEPTUNE"), outcome.set.routes.map { it.name })
        assertEquals(listOf("seg-empty", template.routes.single().segmentId), outcome.set.routes.map { it.segmentId })
    }

    @Test
    fun `the map is taken to the routes, at a zoom that fits them`() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        val seen = mutableListOf<MapFocus.Request>()
        val job = launch { r.focus.requests.collect { seen += it } }
        val outcome = r.importer.import(templateBytes, template, "a.msnx") as MissionImportOutcome.Imported
        kotlinx.coroutines.yield()                                                   // the collector is resumed after the importer returns, since opening the mission hops threads
        val extent = MissionRoutes.extentOf(outcome.set.routes)!!
        assertEquals(1, seen.size)
        assertEquals(extent.center, seen.single().at)
        assertTrue(seen.single().zoom in 3.0..17.0)
        job.cancel()
    }

    @Test
    fun `a file called only dot msnx, or with a long name, still makes a usable name`() = runTest {
        val r = Rig(this)
        assertEquals("IMPORTED MISSION", (r.importer.import(templateBytes, template, ".msnx") as MissionImportOutcome.Imported).set.name)
        assertEquals("IMPORTED MISSION", (r.importer.import(templateBytes, template, "   .MSNX") as MissionImportOutcome.Imported).set.name)
        val long = (r.importer.import(templateBytes, template, "x".repeat(300) + ".msnx") as MissionImportOutcome.Imported).set.name
        assertEquals(80, long.length)
    }

    @Test
    fun `a mission with no route that has a point is refused, and nothing is made`() = runTest {
        val r = Rig(this)
        val empty = Mission(listOf(MissionRoute("EMPTY", points = emptyList(), plan = RoutePlan())))
        val outcome = r.importer.import(templateBytes, empty, "empty.msnx") as MissionImportOutcome.Refused
        assertEquals("This mission has no routes with points to bring in.", outcome.message)
        assertTrue(r.repository.observe().first().isEmpty())
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `importing the same mission twice makes two missions, each with its own file and identity`() = runTest {
        val r = Rig(this)
        r.importer.import(templateBytes, template, "a.msnx")
        r.importer.import(templateBytes, template, "a.msnx")
        val listed = r.repository.observe().first()
        assertEquals(2, listed.size)
        assertEquals(2, listed.map { it.uuid }.toSet().size)
        assertTrue(listed.all { it.isMission })
    }

    @Test
    fun `the point of a route that was a shaping point stays one`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(templateBytes, template, "a.msnx") as MissionImportOutcome.Imported
        val shaping = outcome.set.routes.single().points.filter { it.kind == RoutePoint.KIND_SHAPING }
        assertNotNull(shaping.firstOrNull())
        assertTrue(shaping.all { it.ptType == null })
    }

    @Test
    fun `a mission's summary reads back as the colours the routes were given`() = runTest {
        val r = Rig(this)
        val outcome = r.importer.import(twoBytes, two, "two.msnx") as MissionImportOutcome.Imported
        val summary = r.device.record(RecordKind.MISSION, outcome.set.id)!!.data
        assertEquals(outcome.set.routes.map { it.color }, RouteSets.missionColors(summary))
        assertNull(RouteSets.missionColors(summary).firstOrNull { it == null })
    }
}
