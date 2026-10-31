package app.ezpztac.data

import app.ezpztac.formats.MsnxException
import app.ezpztac.formats.MsnxReader
import app.ezpztac.formats.MsnxMutator
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.OverridePatch
import app.ezpztac.planning.SketchOps
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A saved mission as a person uses it: opened from its file, changed, saved back **into that file**, and carried to another device with the file. What the app does not read
 * (the vehicle model, the calculation blobs of every leg) must come through every save untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedMissionTest {
    private class Rig(scope: TestScope, val server: FakeServer = FakeServer(), label: String = "A") {
        val device = Device(label, server)
        val scheduler = RecordingScheduler()
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = RouteSession(repository, scope.backgroundScope)
        val focus = MapFocus()
        val importer = MissionImporter(repository, session, focus)
    }

    private val bytes = Fixtures.bytes("msnx/template.msnx")
    private val template = MsnxReader.read(bytes)

    private suspend fun Rig.imported(): RouteSet {
        val outcome = importer.import(bytes, template, "template.msnx") as MissionImportOutcome.Imported
        session.close()
        return outcome.set
    }

    private suspend fun Rig.storedFile(uuid: String) = device.repository.file(RecordKind.MISSION, uuid)!!

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                out[entry.name] = z.readBytes()
            }
        }
        return out
    }

    @Test
    fun `opening a mission reads its routes out of the file, with their ids and plans`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val open = r.repository.open(id)!!
        assertEquals("a mission, not a set drawn here", "template.msnx", open.mission!!.fileName)
        assertEquals(template.routes.single().points, open.routes.single().points)
        assertEquals(template.routes.single().segmentId, open.routes.single().segmentId)
    }

    @Test
    fun `saving a mission with nothing changed does not touch its file`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val before = r.device.record(RecordKind.MISSION, id)!!.file!!
        val open = r.repository.open(id)!!
        r.repository.save(open)
        assertEquals(before, r.device.record(RecordKind.MISSION, id)!!.file)
    }

    @Test
    fun `a rename, a hidden route and a colour are saved without rewriting the file`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val before = r.device.record(RecordKind.MISSION, id)!!.file!!
        val open = r.repository.open(id)!!
        val route = open.routes.single()
        r.repository.save(open.copy(name = "RENAMED", routes = listOf(route.copy(visible = false, color = "#0A84FF"))))
        val after = r.device.record(RecordKind.MISSION, id)!!
        assertEquals("RENAMED", after.name)
        assertEquals(before, after.file)
        assertEquals(listOf("#0A84FF"), app.ezpztac.model.RouteSets.missionColors(after.data))
    }

    @Test
    fun `renaming a mission that is not open changes only its name`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val before = r.device.record(RecordKind.MISSION, id)!!.file!!
        r.repository.rename(id, "NEW NAME")
        assertEquals("NEW NAME", r.device.record(RecordKind.MISSION, id)!!.name)
        assertEquals(before, r.device.record(RecordKind.MISSION, id)!!.file)
    }

    @Test
    fun `a point that is moved is written into the file, and everything else in the file stays as it was`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val open = r.repository.open(id)!!
        val route = open.routes.single()
        val first = route.points.first()
        r.repository.save(open.copy(routes = listOf(SketchOps.move(route, first.id!!, first.lat + 0.001, first.lon - 0.001, null))))

        val written = r.storedFile(id)
        val back = MsnxReader.read(written).routes.single().points.first()
        assertEquals(first.lat + 0.001, back.lat, 1e-9)
        assertEquals(first.lon - 0.001, back.lon, 1e-9)
        // The parts this app never changes are the very bytes they were.
        val original = entries(bytes)
        val now = entries(written)
        for (name in original.keys - setOf("mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml")) {
            assertTrue("$name changed", original.getValue(name).contentEquals(now.getValue(name)))
        }
    }

    @Test
    fun `a plan edit is written into the file and read back as the plan`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val open = r.repository.open(id)!!
        val route = open.routes.single()
        val named = route.points.filter { it.kind == RoutePoint.KIND_AMPS }.last()
        val edited = SketchOps.withOverride(route, named.id!!, OverridePatch(altitude = AltitudeSetting(1500.0, AltitudeSetting.REF_MSL)))
        r.repository.save(open.copy(routes = listOf(edited)))

        val reopened = r.repository.open(id)!!
        assertEquals(AltitudeSetting(1500.0, AltitudeSetting.REF_MSL), reopened.routes.single().plan.perPoint.getValue(named.id!!).altitude)
    }

    @Test
    fun `a point put on the line becomes a named point of the file, after the one it follows`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val open = r.repository.open(id)!!
        val route = open.routes.single()
        val between = route.points[10]
        val next = route.points[11]
        val newId = "11111111-2222-4333-8444-555555555555"
        val inserted = SketchOps.insertMissionPoint(route, (between.lat + next.lat) / 2, (between.lon + next.lon) / 2) { newId }
        assertEquals(route.points.size + 1, inserted.points.size)
        r.repository.save(open.copy(routes = listOf(inserted)))

        val back = r.repository.open(id)!!.routes.single()
        assertEquals(route.points.size + 1, back.points.size)
        val made = back.points.single { it.id == newId }
        assertEquals(SketchOps.MISSION_NEW_POINT_NAME, MsnxMutator.NEW_POINT_NAME)
        assertEquals(MsnxMutator.NEW_POINT_NAME, made.name)
        assertEquals(RoutePoint.KIND_AMPS, made.kind)
        assertEquals(route.points[10].id, back.points[10].id)
        assertEquals(newId, back.points[11].id)
    }

    @Test
    fun `saving again and again keeps writing onto the file as it is now, and nothing is lost`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        var open = r.repository.open(id)!!
        val route = open.routes.single()
        val a = route.points.first()
        open = open.copy(routes = listOf(SketchOps.move(route, a.id!!, a.lat + 0.002, a.lon, null)))
        r.repository.save(open)
        val b = open.routes.single().points.last()
        open = open.copy(routes = listOf(SketchOps.move(open.routes.single(), b.id!!, b.lat, b.lon + 0.002, null)))
        r.repository.save(open)

        val back = MsnxReader.read(r.storedFile(id)).routes.single().points
        assertEquals(a.lat + 0.002, back.first().lat, 1e-9)
        assertEquals(b.lon + 0.002, back.last().lon, 1e-9)
    }

    @Test
    fun `an open mission is kept as a new one when it was deleted elsewhere while it was open`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        val open = r.repository.open(id)!!
        val first = open.routes.single().points.first()
        r.device.repository.delete(RecordKind.MISSION, id)                          // gone from this device's lists, as a sync that brought a deletion would leave it
        val saved = r.repository.save(open.copy(routes = listOf(SketchOps.move(open.routes.single(), first.id!!, first.lat + 0.003, first.lon, null))))
        assertNotEquals(id, saved.id)
        assertNotNull(saved.mission)
        val kept = MsnxReader.read(r.storedFile(saved.id)).routes.single().points.first()
        assertEquals(first.lat + 0.003, kept.lat, 1e-9)
        assertEquals(1, r.device.repository.records(RecordKind.MISSION).size)
    }

    @Test
    fun `a mission reaches another device with its file, and opens there with the edits`() = runTest {
        val server = FakeServer()
        val a = Rig(this, server, "A")
        val b = Rig(this, server, "B")
        val id = a.imported().id
        val open = a.repository.open(id)!!
        val first = open.routes.single().points.first()
        a.repository.save(open.copy(routes = listOf(SketchOps.move(open.routes.single(), first.id!!, first.lat + 0.004, first.lon, null))))
        a.device.sync()
        b.device.sync()

        val there = b.repository.open(id)!!
        assertEquals(first.lat + 0.004, there.routes.single().points.first().lat, 1e-9)
        assertEquals(a.storedFile(id).toList(), b.storedFile(id).toList())
    }

    @Test
    fun `two devices that move the same mission keep both files, and each opens`() = runTest {
        val server = FakeServer()
        val a = Rig(this, server, "A")
        val b = Rig(this, server, "B")
        val id = a.imported().id
        a.device.sync(); b.device.sync()
        suspend fun move(rig: Rig, dLat: Double) {
            val open = rig.repository.open(id)!!
            val p = open.routes.single().points.first()
            rig.repository.save(open.copy(routes = listOf(SketchOps.move(open.routes.single(), p.id!!, p.lat + dLat, p.lon, null))))
        }
        move(a, 0.005); a.device.sync()
        move(b, 0.007)
        val report = b.device.sync()

        val copy = report.conflicts.single().copy
        val theirs = b.repository.open(id)!!.routes.single().points.first()
        val mine = b.repository.open(copy)!!.routes.single().points.first()
        assertEquals(template.routes.single().points.first().lat + 0.005, theirs.lat, 1e-9)
        assertEquals(template.routes.single().points.first().lat + 0.007, mine.lat, 1e-9)
    }

    @Test
    fun `a mission whose file has not reached this device cannot be opened, and says so`() = runTest {
        val r = Rig(this)
        val made = r.device.repository.create(RecordKind.MISSION, "NO FILE", app.ezpztac.model.RouteSets.missionSummary(emptyList()))
        val error = runCatching { r.repository.open(made.uuid) }.exceptionOrNull()
        assertTrue(error is MsnxException)
        assertTrue(error!!.message!!.isNotEmpty())
    }

    @Test
    fun `a list shows missions beside sets, and a deleted mission is gone from it and from the store`() = runTest {
        val r = Rig(this)
        val id = r.imported().id
        r.repository.create("A SET")
        val listed = r.repository.observe().first()
        assertEquals(listOf(false, true), listed.map { it.isMission }.sorted())
        r.repository.delete(id)
        assertEquals(listOf("A SET"), r.repository.observe().first().map { it.name })
        assertFalse(r.device.repository.records(RecordKind.MISSION).any { it.uuid == id })
    }
}
