package app.ezpztac.data

import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open set of routes. What every open document does (the pause before a save, undo, a failed save, a switch) is held in [DiagramSessionTest],
 * which exercises the same [DocumentSession]; this is what a set of routes needs of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteSessionTest {
    private class Rig(scope: TestScope) {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = RouteRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = RouteSession(repository, scope.backgroundScope)
        suspend fun saved(uuid: String) = repository.open(uuid)!!
    }

    private fun route(id: String = "sketch-1", name: String = "ROUTE 1") = SketchRoute(
        id = id, name = name, color = "#FF453A",
        points = listOf(
            RoutePoint(id = "p1", lat = 34.7, lon = -84.1, kind = RoutePoint.KIND_AMPS, ptType = "target", name = ".TGT"),
            RoutePoint(id = "p2", lat = 34.8, lon = -84.0, kind = RoutePoint.KIND_AMPS, ptType = "target", name = ".TGT"),
        ),
        plan = RoutePlan(),
    )

    @Test
    fun `a set opens, an edit shows at once and is saved after a pause`() = runTest {
        val r = Rig(this)
        val made = r.repository.create("MISSION 1", listOf(route()))
        assertTrue(r.session.open(made.id))
        assertEquals(made.id, r.session.active.value!!.id)
        r.session.edit("Rename route") { set -> set.mapRoute("sketch-1") { it.copy(name = "RENAMED") } }
        assertEquals("RENAMED", r.session.active.value!!.route("sketch-1")!!.name)
        runCurrent()
        assertEquals("ROUTE 1", r.saved(made.id).route("sketch-1")!!.name)                    // not written yet
        advanceTimeBy(DocumentSession.SAVE_AFTER_STILL_MS + 1)
        runCurrent()
        assertEquals("RENAMED", r.saved(made.id).route("sketch-1")!!.name)
        assertFalse(r.session.saveFailed.value)
    }

    @Test
    fun `there is nothing to open for a set that is not here`() = runTest {
        val r = Rig(this)
        assertFalse(r.session.open("nope"))
        assertNull(r.session.active.value)
    }

    @Test
    fun `undo takes an edit back and redo returns it`() = runTest {
        val r = Rig(this)
        val made = r.repository.create("MISSION 1", listOf(route()))
        r.session.open(made.id)
        r.session.edit("Add a route") { it.plus(route("sketch-2", "TWO")) }
        assertEquals(2, r.session.active.value!!.routes.size)
        assertEquals("Add a route", r.session.undo())
        assertEquals(1, r.session.active.value!!.routes.size)
        assertEquals("Add a route", r.session.redo())
        assertEquals(2, r.session.active.value!!.routes.size)
    }

    @Test
    fun `a set deleted elsewhere keeps what the person did next, as a new record the session follows`() = runTest {
        val r = Rig(this)
        val made = r.repository.create("MISSION 1", listOf(route()))
        r.device.sync()
        r.session.open(made.id)
        r.server.deleteElsewhere(RecordKind.ROUTE, made.id)
        r.device.sync()
        r.session.edit("Rename route") { set -> set.mapRoute("sketch-1") { it.copy(name = "KEPT") } }
        r.session.flush()
        val now = r.session.active.value!!
        assertNotEquals(made.id, now.id)
        assertNull(now.savedId)
        assertEquals("KEPT", r.saved(now.id).route("sketch-1")!!.name)
        r.session.undo()
        assertEquals(now.id, r.session.active.value!!.id)                                     // undo stays on the new record
        assertEquals("ROUTE 1", r.session.active.value!!.route("sketch-1")!!.name)
    }

    @Test
    fun `closing saves what is open and leaves nothing on screen`() = runTest {
        val r = Rig(this)
        val made = r.repository.create("MISSION 1", listOf(route()))
        r.session.open(made.id)
        r.session.edit("Rename route") { set -> set.mapRoute("sketch-1") { it.copy(name = "LAST") } }
        r.session.close()
        assertNull(r.session.active.value)
        assertEquals("LAST", r.saved(made.id).route("sketch-1")!!.name)
    }

    @Test
    fun `a change to a set that is not open is written to its record`() = runTest {
        val r = Rig(this)
        val a = r.repository.create("A", listOf(route()))
        val b = r.repository.create("B", listOf(route()))
        r.session.open(a.id)
        assertTrue(r.session.update(b.id) { it.plus(route("sketch-2")) })
        assertEquals(2, r.saved(b.id).routes.size)
        assertEquals(1, r.saved(a.id).routes.size)
        assertFalse(r.session.update("nope") { it })
    }
}
