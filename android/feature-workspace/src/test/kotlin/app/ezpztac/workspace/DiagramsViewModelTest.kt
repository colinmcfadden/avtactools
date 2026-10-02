package app.ezpztac.workspace

import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagramsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val grid = "16S GD 66993 52949"

    /** One device with the real repository, session and engine over an in-memory store; only the server is a stand-in. */
    private class Rig(scope: TestScope, label: String = "A", val server: FakeServer = FakeServer(), realEngine: Boolean = false, resolver: ConflictResolver? = null) {
        val device = Device(label, server)
        val scheduler = RecordingScheduler()
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, scheduler)
        val session = DiagramSession(repository, scope.backgroundScope)
        val resolutions = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        val model = DiagramsViewModel(
            repository, session,
            resolver ?: if (realEngine) device.engine else ConflictResolver { kind, uuid, how -> resolutions += Triple(kind, uuid, how) },
        )
        val rows get() = model.state.value.rows
    }

    private fun TestScope.rig(resolver: ConflictResolver? = null) = Rig(this, resolver = resolver).also { advanceUntilIdle() }

    // -- The list ------------------------------------------------------------------------------------------

    @Test
    fun `with nothing made the list is empty`() = runTest(dispatcher) {
        val r = rig()
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertFalse(r.model.state.value.creating)
        assertNull(r.model.state.value.error)
    }

    @Test
    fun `a diagram made elsewhere appears without asking`() = runTest(dispatcher) {
        val r = rig()
        r.repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
        advanceUntilIdle()
        assertEquals(listOf("LZ HAWK"), r.rows.map { it.name })
        assertEquals(grid, r.rows.single().grid)
        assertEquals(SyncStatus.PENDING, r.rows.single().sync)
    }

    // -- Making one ----------------------------------------------------------------------------------------

    @Test
    fun `a grid makes a diagram, named for the grid, and opens it`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        r.model.create("", grid)
        advanceUntilIdle()
        val row = r.rows.single()
        assertEquals("LZ $grid", row.name)
        assertEquals(grid, row.grid)
        assertEquals(DiagramStatus.TARGETED, row.status)
        assertTrue(row.isActive)
        assertEquals(row.uuid, r.session.active.value?.id)
        assertFalse(r.model.state.value.creating)                                           // the form closes once it worked
        assertTrue(r.scheduler.requested > 0)                                               // and the server is to be told
    }

    @Test
    fun `a typed name is kept, trimmed`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("  LZ HAWK  ", grid)
        advanceUntilIdle()
        assertEquals("LZ HAWK", r.rows.single().name)
    }

    @Test
    fun `a latitude and longitude work as well as a grid, and give the grid`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("", "34.783817, -84.08219")
        advanceUntilIdle()
        assertEquals(grid, r.rows.single().grid)
        assertEquals(34.783817, r.session.active.value!!.target!!.lat, 1e-5)
    }

    @Test
    fun `a target that is not understood says so and makes nothing`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        r.model.create("LZ X", "somewhere nice")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        assertTrue(r.model.state.value.creating)                                            // the form stays, with what was typed
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertNull(r.session.active.value)
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `pressing create twice makes one`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        r.model.create("two", grid)                                                         // a double tap, before the first finished
        advanceUntilIdle()
        assertEquals(listOf("one"), r.rows.map { it.name })
        assertFalse(r.model.state.value.busy)
    }

    @Test
    fun `starting, cancelling and dismissing are only about the form and the message`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCreating()
        advanceUntilIdle()
        assertTrue(r.model.state.value.creating)
        r.model.create("", "nonsense")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        r.model.dismissError()
        advanceUntilIdle()
        assertNull(r.model.state.value.error)
        assertTrue(r.model.state.value.creating)
        r.model.cancelCreating()
        advanceUntilIdle()
        assertFalse(r.model.state.value.creating)
    }

    // -- Opening -------------------------------------------------------------------------------------------

    @Test
    fun `opening another diagram makes it the active one`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val first = r.rows.single().uuid
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        val second = r.rows.first { it.name == "two" }.uuid
        assertEquals(second, r.session.active.value?.id)
        r.model.open(first)
        advanceUntilIdle()
        assertEquals(first, r.session.active.value?.id)
        assertEquals(listOf(first), r.rows.filter { it.isActive }.map { it.uuid })          // exactly one is marked
    }

    @Test
    fun `opening the open diagram does not drop its unsaved edits`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.session.edit("Rename") { it.copy(name = "typed on the map") }
        r.model.open(uuid)
        advanceUntilIdle()
        assertEquals("typed on the map", r.session.active.value?.name)
        assertEquals(1, r.session.undoDepth.value)                                          // not reopened: what can be undone still can
    }

    @Test
    fun `a diagram that is gone says so`() = runTest(dispatcher) {
        val r = rig()
        r.model.open("never-here")
        advanceUntilIdle()
        assertEquals("That diagram is no longer here.", r.model.state.value.error)
        assertNull(r.session.active.value)
    }

    // -- Renaming ------------------------------------------------------------------------------------------

    @Test
    fun `renaming a diagram that is not open changes its record`() = runTest(dispatcher) {
        val r = rig()
        r.repository.create(DiagramTarget(34.783817, -84.08219, grid), "old")
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.model.rename(uuid, "  new  ")
        advanceUntilIdle()
        assertEquals("new", r.rows.single().name)
        assertEquals("new", r.repository.open(uuid)!!.name)
    }

    @Test
    fun `renaming the open diagram goes through the session, so unsaved edits are not lost`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("old", grid)
        advanceUntilIdle()
        val uuid = r.rows.single().uuid
        r.session.edit("Move") { it.copy(mapData = JsonObject(mapOf("zoom" to JsonPrimitive(15)))) }
        r.model.rename(uuid, "new")
        advanceUntilIdle()
        val saved = r.repository.open(uuid)!!
        assertEquals("new", saved.name)
        assertEquals(JsonPrimitive(15), saved.mapData["zoom"])                              // the earlier, unsaved edit was written with it
        assertEquals("new", r.session.active.value?.name)
        assertEquals(2, r.session.undoDepth.value)                                          // and both can still be undone
    }

    @Test
    fun `a blank name is refused and nothing changes`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("keep", grid)
        advanceUntilIdle()
        r.model.rename(r.rows.single().uuid, "   ")
        advanceUntilIdle()
        assertEquals("A diagram needs a name.", r.model.state.value.error)
        assertEquals("keep", r.rows.single().name)
    }

    // -- Deleting ------------------------------------------------------------------------------------------

    @Test
    fun `deleting the open diagram closes it`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        r.model.delete(r.rows.single().uuid)
        advanceUntilIdle()
        assertEquals(emptyList<DiagramRow>(), r.rows)
        assertNull(r.session.active.value)
    }

    @Test
    fun `deleting another diagram leaves the open one open`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        val one = r.rows.single().uuid
        r.model.create("two", "16S GD 67993 52949")
        advanceUntilIdle()
        r.model.delete(one)
        advanceUntilIdle()
        assertEquals(listOf("two"), r.rows.map { it.name })
        assertEquals("two", r.session.active.value?.name)
    }

    @Test
    fun `a delete is told to the server, not only forgotten here`() = runTest(dispatcher) {
        val r = rig()
        r.model.create("one", grid)
        advanceUntilIdle()
        r.device.sync()
        val before = r.scheduler.requested
        r.model.delete(r.rows.single().uuid)
        advanceUntilIdle()
        assertTrue(r.scheduler.requested > before)
        r.device.sync()
        assertEquals(0, r.server.live(RecordKind.LZ).size)
    }

    // -- Conflicts -----------------------------------------------------------------------------------------

    /** A conflict made the real way: two devices change the one diagram, and the second to sync is the one that finds out. */
    private suspend fun TestScope.conflicted(realEngine: Boolean = false): Rig {
        val server = FakeServer()
        val a = Rig(this, "A", server)
        advanceUntilIdle()
        val made = a.repository.create(DiagramTarget(34.783817, -84.08219, grid), "LZ HAWK")
        a.device.sync()
        val b = Rig(this, "B", server, realEngine)
        b.device.sync()
        a.repository.rename(made.id, "LZ CROW")
        b.repository.rename(made.id, "LZ EAGLE")
        a.device.sync()
        b.device.sync()
        advanceUntilIdle()
        return b
    }

    @Test
    fun `a conflict copy is marked, so the screen can offer the choice`() = runTest(dispatcher) {
        val b = conflicted()
        val copy = b.rows.single { it.conflictOf != null }
        val original = b.rows.single { it.conflictOf == null }
        assertEquals("LZ CROW", original.name)                                              // the other device's, as the server has it
        assertTrue(copy.name.startsWith("LZ EAGLE"))                                        // this device's, kept
        assertEquals(original.uuid, copy.conflictOf)
    }

    @Test
    fun `settling a conflict asks the resolver for exactly that copy and choice`() = runTest(dispatcher) {
        val b = conflicted()
        val copy = b.rows.single { it.conflictOf != null }
        b.model.resolve(copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)
        advanceUntilIdle()
        assertEquals(listOf(Triple(RecordKind.LZ, copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)), b.resolutions)
    }

    @Test
    fun `keeping theirs through the real engine removes the copy`() = runTest(dispatcher) {
        val b = conflicted(realEngine = true)
        val copy = b.rows.single { it.conflictOf != null }
        b.model.resolve(copy.uuid, SyncEngine.Resolution.KEEP_THEIRS)
        advanceUntilIdle()
        assertEquals(listOf("LZ CROW"), b.rows.map { it.name })
    }

    // -- Failures ------------------------------------------------------------------------------------------

    @Test
    fun `a failure shows its own message, and the screen is usable again`() = runTest(dispatcher) {
        val r = rig(resolver = ConflictResolver { _, _, _ -> error("the server said no") })
        r.model.resolve("x", SyncEngine.Resolution.KEEP_MINE)
        advanceUntilIdle()
        assertEquals("the server said no", r.model.state.value.error)
        assertFalse(r.model.state.value.busy)
    }

    @Test
    fun `a failure with no message gets one in words`() = runTest(dispatcher) {
        val r = rig(resolver = ConflictResolver { _, _, _ -> throw IllegalStateException() })
        r.model.resolve("x", SyncEngine.Resolution.KEEP_MINE)
        advanceUntilIdle()
        assertEquals("The conflict could not be settled.", r.model.state.value.error)
    }

    @Test
    fun `a new action clears the last error`() = runTest(dispatcher) {
        val r = rig()
        r.model.open("never-here")
        advanceUntilIdle()
        assertNotNull(r.model.state.value.error)
        r.model.create("fine", grid)
        advanceUntilIdle()
        assertNull(r.model.state.value.error)
    }
}
