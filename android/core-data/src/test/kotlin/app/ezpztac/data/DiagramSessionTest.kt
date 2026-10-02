package app.ezpztac.data

import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import app.ezpztac.sync.RecordKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagramSessionTest {
    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Rig(scope: TestScope) {
        val server = FakeServer()
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = DiagramSession(repository, scope.backgroundScope)
        suspend fun saved(uuid: String) = repository.open(uuid)!!
    }

    private fun TestScope.rig() = Rig(this)

    // -- Editing ------------------------------------------------------------------------------------------

    @Test
    fun `an edit shows at once, and is saved after a pause`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        assertEquals("LZ CROW", r.session.active.value!!.name)                              // the map draws this at once
        runCurrent()
        assertEquals("LZ HAWK", r.saved(made.id).name)                                      // the database has not heard yet
        advanceTimeBy(DiagramSession.SAVE_AFTER_STILL_MS + 1)
        runCurrent()
        assertEquals("LZ CROW", r.saved(made.id).name)
    }

    @Test
    fun `a drag's many edits are one save`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "LZ HAWK")
        r.device.sync()
        r.session.open(made.id)
        repeat(50) { i ->
            r.session.edit("Move") { it.copy(name = "step $i") }
            advanceTimeBy(10)
        }
        advanceTimeBy(DiagramSession.SAVE_AFTER_STILL_MS + 1)
        runCurrent()
        assertEquals("step 49", r.saved(made.id).name)
        assertEquals(1, r.device.outbox().size)                                             // one update queued, not fifty
    }

    @Test
    fun `flushing saves now, and saving twice does nothing the second time`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "now") }
        r.session.flush()
        assertEquals("now", r.saved(made.id).name)
        val updatedAt = r.saved(made.id).updatedAt
        r.session.flush()
        assertEquals(updatedAt, r.saved(made.id).updatedAt)                                 // nothing was written again
    }

    @Test
    fun `opening another diagram saves the first, and closing saves and clears`() = runTest {
        val r = rig()
        val a = r.repository.create(target, "A")
        val b = r.repository.create(target, "B")
        r.session.open(a.id)
        r.session.edit("Rename") { it.copy(name = "A edited") }
        assertTrue(r.session.open(b.id))
        assertEquals("A edited", r.saved(a.id).name)
        assertEquals("B", r.session.active.value!!.name)
        r.session.edit("Rename") { it.copy(name = "B edited") }
        r.session.close()
        assertEquals("B edited", r.saved(b.id).name)
        assertNull(r.session.active.value)
    }

    @Test
    fun `opening what is not there changes nothing`() = runTest {
        val r = rig()
        val a = r.repository.create(target, "A")
        r.session.open(a.id)
        assertFalse(r.session.open("never-made"))
        assertEquals("A", r.session.active.value!!.name)
    }

    @Test
    fun `an edit that changes nothing is not an edit`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "A")
        r.session.open(made.id)
        r.session.edit("Nothing") { it }
        assertEquals(0, r.session.undoDepth.value)
        advanceUntilIdle()
        assertEquals(1, r.device.outbox().size)                                             // only the create: no update was made
    }

    @Test
    fun `with nothing open an edit goes nowhere`() = runTest {
        val r = rig()
        r.session.edit("Rename") { it.copy(name = "x") }
        assertNull(r.session.active.value)
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `a quiet change is saved but is not an edit to undo`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "A")
        r.session.open(made.id)
        r.session.setQuietly { it.copy(view = it.view.copy(mapStyle = "outdoors")) }
        assertEquals(0, r.session.undoDepth.value)
        r.session.flush()
        assertEquals("outdoors", r.saved(made.id).view.mapStyle)
    }

    @Test
    fun `undoing an edit does not bring back the base map it was made on`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "v0")
        r.session.open(made.id)
        r.session.edit("First") { it.copy(name = "v1") }
        r.session.setQuietly { it.copy(view = it.view.copy(mapStyle = "outdoors")) }
        r.session.edit("Second") { it.copy(name = "v2") }
        r.session.undo()
        r.session.undo()
        assertEquals("v0", r.session.active.value!!.name)
        assertEquals("outdoors", r.session.active.value!!.view.mapStyle)                     // the name went back; the map did not
        r.session.redo()
        r.session.redo()
        assertEquals("v2", r.session.active.value!!.name)
        assertEquals("outdoors", r.session.active.value!!.view.mapStyle)
    }

    @Test
    fun `a quiet change to what is already so does nothing, and with nothing open it goes nowhere`() = runTest {
        val r = rig()
        r.session.setQuietly { it.copy(name = "x") }
        assertNull(r.session.active.value)
        val made = r.repository.create(target, "A")
        r.session.open(made.id)
        r.session.setQuietly { it }
        advanceUntilIdle()
        assertEquals(1, r.device.outbox().size)                                             // only the create
    }

    // -- When saving goes wrong -------------------------------------------------------------------------------

    private suspend inline fun <reified T : Throwable> failsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}, but nothing was thrown")
    }

    /** A store that can be made to fail, as a full disk or a locked database would. */
    private class FlakyStore(private val inner: InMemorySyncStore = InMemorySyncStore()) : SyncStore, RecordFeed {
        var failing = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) throw java.io.IOException("the disk is full")
            return inner.transaction(block)
        }
        override fun observe(kind: RecordKind) = inner.observe(kind)
    }

    private class FlakyRig(scope: TestScope) {
        val store = FlakyStore()
        val server = FakeServer()
        val device = Device("A", server, store = store)
        val repository = DiagramRepository(device.repository, store, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
    }

    @Test
    fun `a save that fails in the background does not end the app, and is tried again`() = runTest {
        val r = FlakyRig(this)
        val made = r.repository.create(target, "A")
        r.session.open(made.id)
        r.store.failing = true
        r.session.edit("Rename") { it.copy(name = "kept") }
        advanceTimeBy(DiagramSession.SAVE_AFTER_STILL_MS + 1)
        runCurrent()                                                                          // nothing was thrown out of the scope
        assertTrue(r.session.saveFailed.value)
        assertEquals("kept", r.session.active.value!!.name)                                   // the change is still here
        r.store.failing = false
        r.session.flush()
        assertFalse(r.session.saveFailed.value)
        assertEquals("kept", r.repository.open(made.id)!!.name)
    }

    @Test
    fun `a diagram that cannot be saved stays open when another is asked for`() = runTest {
        val r = FlakyRig(this)
        val a = r.repository.create(target, "A")
        val b = r.repository.create(target, "B")
        r.session.open(a.id)
        r.session.edit("Rename") { it.copy(name = "A edited") }
        r.store.failing = true
        failsWith<java.io.IOException> { r.session.open(b.id) }
        assertEquals("A edited", r.session.active.value!!.name)                               // not swapped for B with the edit lost
    }

    @Test
    fun `closing always closes, and still says the save failed`() = runTest {
        val r = FlakyRig(this)
        val a = r.repository.create(target, "A")
        r.session.open(a.id)
        r.session.edit("Rename") { it.copy(name = "A edited") }
        r.store.failing = true
        failsWith<java.io.IOException> { r.session.close() }
        assertNull(r.session.active.value)                                                    // an account that signs out leaves nothing open
        assertFalse(r.session.saveFailed.value)
        r.store.failing = false
        advanceUntilIdle()
        assertEquals("A", r.repository.open(a.id)!!.name)                                     // and nothing is written later for a closed diagram
    }

    @Test
    fun `a diagram deleted elsewhere keeps what the person did next, as a new record`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "LZ HAWK")
        r.device.sync()
        r.session.open(made.id)
        r.server.deleteElsewhere(RecordKind.LZ, made.id)
        r.device.sync()                                                                       // the deletion arrives while the diagram is open
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.session.flush()
        val now = r.session.active.value!!
        assertNotEquals(made.id, now.id)
        assertEquals("LZ CROW", r.repository.open(now.id)!!.name)
        assertNull(r.repository.open(made.id))                                                // the old one stays gone
        assertEquals(JsonNull, now.savedId)                                                   // the server has never heard of this record
        r.session.edit("Again") { it.copy(name = "LZ EAGLE") }
        r.session.undo()
        assertEquals("LZ CROW", r.session.active.value!!.name)                                // undo works, on the new record
        r.session.undo()                                                                      // and back past the moment it was made anew
        assertEquals("LZ HAWK", r.session.active.value!!.name)
        assertEquals(now.id, r.session.active.value!!.id)                                     // still the new record: the next save does not hit the old one
        r.session.flush()
        assertEquals("LZ HAWK", r.repository.open(now.id)!!.name)
        assertEquals(1, r.device.names(RecordKind.LZ).size)
    }

    @Test
    fun `a diagram deleted here but not yet told to the server is not edited back to life`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "LZ HAWK")
        r.device.sync()                                                                       // the server has it, so the delete is queued, not forgotten
        r.session.open(made.id)
        r.repository.delete(made.id)
        assertNull(r.device.record(RecordKind.LZ, made.id))
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.session.flush()
        assertNotEquals(made.id, r.session.active.value!!.id)
        assertNull(r.device.record(RecordKind.LZ, made.id))                                   // the deletion stands
        assertEquals(listOf("LZ CROW"), r.device.names(RecordKind.LZ).filter { it.startsWith("LZ C") })
        r.device.sync()
        assertEquals(listOf("LZ CROW"), r.server.live(RecordKind.LZ).map { it.name })         // and the server ends up with the one the person kept
    }

    @Test
    fun `a drag that lasts longer than the pause is saved when it stops, not partway`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "start")
        r.session.open(made.id)
        repeat(6) { i ->
            r.session.edit("Move") { it.copy(name = "step $i") }
            advanceTimeBy(DiagramSession.SAVE_AFTER_STILL_MS - 100)                           // never still for the whole pause
            runCurrent()
        }
        assertEquals("start", r.saved(made.id).name)                                          // nothing was written during the drag
        advanceTimeBy(DiagramSession.SAVE_AFTER_STILL_MS)
        runCurrent()
        assertEquals("step 5", r.saved(made.id).name)
    }

    // -- Undo ------------------------------------------------------------------------------------------------

    @Test
    fun `undo and redo walk back and forward through the edits, naming each`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "v0")
        r.session.open(made.id)
        r.session.edit("First") { it.copy(name = "v1") }
        r.session.edit("Second") { it.copy(name = "v2") }
        assertEquals(2, r.session.undoDepth.value)
        assertEquals("Second", r.session.undo())
        assertEquals("v1", r.session.active.value!!.name)
        assertEquals("First", r.session.undo())
        assertEquals("v0", r.session.active.value!!.name)
        assertNull(r.session.undo())                                                         // nothing left
        assertEquals(2, r.session.redoDepth.value)
        assertEquals("First", r.session.redo())
        assertEquals("v1", r.session.active.value!!.name)
        assertEquals("Second", r.session.redo())
        assertEquals("v2", r.session.active.value!!.name)
        assertNull(r.session.redo())
    }

    @Test
    fun `a new edit after an undo ends the redo`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "v0")
        r.session.open(made.id)
        r.session.edit("First") { it.copy(name = "v1") }
        r.session.undo()
        r.session.edit("Other") { it.copy(name = "other") }
        assertEquals(0, r.session.redoDepth.value)
        assertNull(r.session.redo())
    }

    @Test
    fun `an undone edit is saved as undone`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "v0")
        r.session.open(made.id)
        r.session.edit("First") { it.copy(name = "v1") }
        r.session.flush()
        r.session.undo()
        r.session.flush()
        assertEquals("v0", r.saved(made.id).name)
    }

    @Test
    fun `undo remembers a hundred edits and no more`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "v0")
        r.session.open(made.id)
        repeat(DiagramSession.MAX_UNDO + 20) { i -> r.session.edit("Edit") { it.copy(name = "v${i + 1}") } }
        assertEquals(DiagramSession.MAX_UNDO, r.session.undoDepth.value)
        repeat(DiagramSession.MAX_UNDO) { r.session.undo() }
        assertEquals("v20", r.session.active.value!!.name)                                   // the oldest 20 are forgotten
    }

    @Test
    fun `opening another diagram starts a fresh undo`() = runTest {
        val r = rig()
        val a = r.repository.create(target, "A")
        val b = r.repository.create(target, "B")
        r.session.open(a.id)
        r.session.edit("Rename") { it.copy(name = "A2") }
        r.session.open(b.id)
        assertEquals(0, r.session.undoDepth.value)
        assertNull(r.session.undo())                                                         // cannot undo A's edit from inside B
    }

    // -- A save that fails -------------------------------------------------------------------------------------

    @Test
    fun `a record that vanished underneath is not a failed save, the work is kept as a new record`() = runTest {
        val r = rig()
        val made = r.repository.create(target, "A")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "A2") }
        r.repository.delete(made.id)                                                         // gone, as if deleted on another device and synced
        r.session.flush()
        assertEquals("A2", r.session.active.value!!.name)                                    // what the person sees is not thrown away
        assertNotEquals(made.id, r.session.active.value!!.id)
        assertNull(r.device.record(RecordKind.LZ, made.id))
        assertEquals("A2", r.repository.open(r.session.active.value!!.id)!!.name)
    }

    @Test
    fun `a save that failed for a moment is written by the next flush`() = runTest {
        val inner = InMemorySyncStore()
        val flaky = object : app.ezpztac.sync.SyncStore, app.ezpztac.sync.RecordFeed by inner {
            var failing = false
            override suspend fun <T> transaction(block: suspend app.ezpztac.sync.SyncTransaction.() -> T): T {
                if (failing) error("the database was busy")
                return inner.transaction(block)
            }
        }
        val repository = DiagramRepository(app.ezpztac.sync.SyncRepository(flaky), flaky, RecordingScheduler())
        val session = DiagramSession(repository, backgroundScope)
        val made = repository.create(target, "A")
        session.open(made.id)
        session.edit("Rename") { it.copy(name = "A2") }
        flaky.failing = true
        assertTrue(runCatching { session.flush() }.isFailure)
        flaky.failing = false
        session.flush()                                                                      // still owed, so it is written now
        assertEquals("A2", repository.open(made.id)!!.name)
    }
}
