package app.ezpztac.android

import app.ezpztac.data.DiagramRepository
import app.ezpztac.data.DiagramSession
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SequentialIds
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val target = DiagramTarget(34.783817, -84.08219, "16S GD 66993 52949")

    private class Flaky(private val inner: InMemorySyncStore = InMemorySyncStore()) : SyncStore, RecordFeed by inner {
        var failing = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) error("the database was busy")
            return inner.transaction(block)
        }
    }

    private class Rig(scope: TestScope) {
        val store = Flaky()
        val repository = DiagramRepository(SyncRepository(store, SequentialIds("t")), store, RecordingScheduler())
        val session = DiagramSession(repository, scope.backgroundScope)
        val model = HomeViewModel(session)
        val seen = mutableListOf<OpenedDiagram>()

        init {
            // Unconfined, so the collector is subscribed the moment it starts and nothing emitted after is missed.
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.opened.collect { seen += it } }
        }
    }

    @Test
    fun `opening a diagram says where the map should go and which base map it had`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        assertEquals(listOf(OpenedDiagram(made.id, LatLon(34.783817, -84.08219), "satellite")), r.seen)
    }

    @Test
    fun `editing the open diagram does not send the map back to it`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.model.baseMapChosen("topo")
        advanceUntilIdle()
        assertEquals(1, r.seen.size)
    }

    @Test
    fun `opening a second diagram says so`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val a = r.repository.create(target, "A")
        val b = r.repository.create(DiagramTarget(35.0, -85.0, "16S FD 00000 00000"), "B")
        r.session.open(a.id)
        advanceUntilIdle()
        r.session.open(b.id)
        advanceUntilIdle()
        assertEquals(listOf(a.id, b.id), r.seen.map { it.id })
        assertEquals(LatLon(35.0, -85.0), r.seen.last().at)
    }

    @Test
    fun `a screen that is built later is not told about a diagram that was already open`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        advanceUntilIdle()
        val late = mutableListOf<OpenedDiagram>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.opened.collect { late += it } }   // a turn of the phone
        advanceUntilIdle()
        assertEquals(emptyList<OpenedDiagram>(), late)
    }

    @Test
    fun `the base map the person chooses is saved with the diagram`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.model.baseMapChosen("topo")
        r.session.flush()
        assertEquals("topo", r.repository.open(made.id)!!.view.mapStyle)
        assertEquals(0, r.session.undoDepth.value)
    }

    @Test
    fun `choosing a base map with nothing open does nothing`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        r.model.baseMapChosen("topo")
        advanceUntilIdle()
        assertEquals(emptyList<OpenedDiagram>(), r.seen)
    }

    @Test
    fun `going to the background writes what is unsaved at once`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.model.appStopped()
        advanceUntilIdle()                                                                    // far less than the save pause
        assertEquals("LZ CROW", r.repository.open(made.id)!!.name)
    }

    @Test
    fun `a write that fails on the way to the background does not end the app`() = runTest(dispatcher) {
        val r = Rig(this)
        advanceUntilIdle()
        val made = r.repository.create(target, "LZ HAWK")
        r.session.open(made.id)
        r.session.edit("Rename") { it.copy(name = "LZ CROW") }
        r.store.failing = true
        r.model.appStopped()
        advanceUntilIdle()
        assertEquals(true, r.session.saveFailed.value)
        assertEquals("LZ CROW", r.session.active.value!!.name)                               // kept, and owed
    }
}
