package app.ezpztac.data

import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What a copy into a pack writes first: the open document, if it is the record asked for, and the id it is held under once written. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWritesTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val scheduler = RecordingScheduler()
        val diagrams = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val routes = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val diagramSession = DiagramSession(diagrams, scope.backgroundScope)
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val writes = SessionWrites(diagramSession, routeSession)
    }

    @Test
    fun `the open LZ is written before it is copied, and nothing else is`() = runTest {
        val r = Rig(this)
        val open = r.diagrams.create(target, "LZ HAWK")
        val other = r.diagrams.create(target, "LZ CROW")
        r.diagramSession.open(open.id)
        r.diagramSession.edit("Rename") { it.copy(name = "LZ HAWK 2") }
        runCurrent()
        assertEquals(other.id, r.writes.write(RecordKind.LZ, other.id))
        assertEquals("LZ HAWK", r.device.record(RecordKind.LZ, open.id)!!.name)              // not the one asked about: left to its pause
        assertEquals(open.id, r.writes.write(RecordKind.LZ, open.id))
        assertEquals("LZ HAWK 2", r.device.record(RecordKind.LZ, open.id)!!.name)            // written now
    }

    @Test
    fun `an open LZ whose record was deleted elsewhere is kept under a new id, and that is the id it is copied by`() = runTest {
        val r = Rig(this)
        val open = r.diagrams.create(target, "LZ HAWK")
        r.diagramSession.open(open.id)
        r.diagramSession.edit("Rename") { it.copy(name = "LZ HAWK 2") }
        r.device.repository.delete(RecordKind.LZ, open.id)                                       // a pull's delete
        val now = r.writes.write(RecordKind.LZ, open.id)
        assertNotEquals(open.id, now)
        assertEquals("LZ HAWK 2", r.device.record(RecordKind.LZ, now)!!.name)
    }

    @Test
    fun `the open set of routes is written too, and a set of points is as it is`() = runTest {
        val r = Rig(this)
        val set = r.routes.create("NIGHT INFIL")
        r.routeSession.open(set.id)
        r.routeSession.edit("Rename") { it.copy(name = "DAY EXFIL") }
        runCurrent()
        assertEquals(set.id, r.writes.write(RecordKind.ROUTE, set.id))
        assertEquals("DAY EXFIL", r.device.record(RecordKind.ROUTE, set.id)!!.name)
        assertEquals("p1", r.writes.write(RecordKind.POINT_SET, "p1"))
    }
}
