package app.ezpztac.workspace

import app.ezpztac.data.InMemoryOpenThreatSetStore
import app.ezpztac.data.OpenThreatSets
import app.ezpztac.data.ThreatMaskApi
import app.ezpztac.data.ThreatMasks
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatSetRepository
import app.ezpztac.data.ThreatSetStanding
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.network.ThreatMaskDto
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The Threats panel's saved sets, and what the Library asks of them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThreatSetsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private object NoVault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) = Unit
        override fun wipe() = Unit
    }

    private object NoMasks : ThreatMaskApi {
        override suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto = error("nothing here asks for a mask")
    }

    private class Rig(scope: TestScope) {
        val device = Device("A", FakeServer())
        val repo = ThreatSetRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val open = OpenThreatSets(InMemoryOpenThreatSetStore())
        val store = ThreatStore(NoVault, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler), repo, open)
        val selection = ThreatSelection()
        val settled = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()
        private val template = File("../../backend/threat_template.ths").readBytes()
        val model = ThreatsViewModel(
            store, selection, ThreatTransfer(ThsWriter { template }), ThreatMasks(NoMasks, store, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))),
            ConflictResolver { kind, uuid, how -> settled += Triple(kind, uuid, how) },
        ).also { it.worker = StandardTestDispatcher(scope.testScheduler) }
        val notices = mutableListOf<String>()
        val state get() = model.state.value

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.state.collect {} }
        }
    }

    private suspend fun TestScope.rig() = Rig(this).also { it.store.attach("7", saves = true); advanceUntilIdle() }

    private fun threat(name: String = "SA-8") = Threat(name, "SHGPEWRR------", 34.75, -84.05, "", "SOF", radars = Radars.defaultPair())

    @Test
    fun `the panel shows the sets open, each with its threats and how it stands, and where a new threat goes`() = runTest(dispatcher) {
        val r = rig()
        assertTrue(r.state.saves)
        r.store.addAsSet(listOf(threat("A")), "ONE"); advanceUntilIdle()
        r.store.addAsSet(listOf(threat("B"), threat("C")), "TWO"); advanceUntilIdle()

        val (one, two) = r.state.sets
        assertEquals("ONE", one.name)
        assertEquals(listOf("A"), one.rows.map { it.name })
        assertEquals(ThreatSetStanding.SAVED, one.standing)
        assertFalse(one.active)
        assertEquals(listOf("B", "C"), two.rows.map { it.name })
        assertTrue(two.active)
        assertEquals(3, r.state.threats.size)

        r.model.useSet(one.id); advanceUntilIdle()
        assertTrue(r.state.sets[0].active)
        assertFalse(r.state.sets[1].active)

        r.store.add(threat("D"))
        advanceUntilIdle()
        assertEquals(listOf("A", "D"), r.state.sets[0].rows.map { it.name })
    }

    @Test
    fun `closing a set lets go of a threat held from it and of its form`() = runTest(dispatcher) {
        val r = rig()
        val threat = r.store.addAsSet(listOf(threat("A")), "ONE").single(); advanceUntilIdle()
        r.selection.select(threat)
        r.model.beginEdit(threat); advanceUntilIdle()
        assertNotNull(r.state.editing)

        r.model.closeSet(r.state.sets.single().id); advanceUntilIdle()
        assertTrue(r.state.sets.isEmpty())
        assertNull(r.selection.held.value)
        assertNull(r.state.editing)
        assertEquals(1, r.repo.records().size)
    }

    @Test
    fun `the Library opens a set, renames one, and deletes one`() = runTest(dispatcher) {
        val r = rig()
        r.store.addAsSet(listOf(threat("A")), "ONE"); advanceUntilIdle()
        val id = r.state.sets.single().id
        r.model.closeSet(id); advanceUntilIdle()

        r.model.openSaved(id); advanceUntilIdle()
        assertEquals(listOf("ONE"), r.state.sets.map { it.name })

        assertEquals("A set needs a name.", r.model.renameSaved(id, "  "))
        assertNull(r.model.renameSaved(id, "kilo")); advanceUntilIdle()
        assertEquals("KILO", r.repo.record(id)!!.name)
        assertEquals("KILO", r.state.sets.single().name)

        r.selection.select(r.state.threats.single().id)
        r.model.deleteSaved(id); advanceUntilIdle()
        assertTrue(r.state.sets.isEmpty())
        assertNull(r.selection.held.value)
        assertNull(r.repo.record(id))
    }

    @Test
    fun `a name taken can be taken over, the other set going, and a set is never replaced by itself`() = runTest(dispatcher) {
        val r = rig()
        r.store.addAsSet(listOf(threat("A")), "ONE"); advanceUntilIdle()
        r.store.addAsSet(listOf(threat("B")), "TWO"); advanceUntilIdle()
        val (one, two) = r.state.sets

        assertNull(r.model.renameSaved(one.id, "ONE", replacing = one.id)); advanceUntilIdle()
        assertNotNull(r.repo.record(one.id))

        assertNull(r.model.renameSaved(one.id, "TWO", replacing = two.id)); advanceUntilIdle()
        assertNull(r.repo.record(two.id))
        assertEquals("TWO", r.repo.record(one.id)!!.name)
        assertEquals(listOf(one.id), r.state.sets.map { it.id })
    }

    @Test
    fun `a set that will not open is said, and a conflict is settled as a set of threats`() = runTest(dispatcher) {
        val r = rig()
        r.model.openSaved("no-such-set"); advanceUntilIdle()
        assertEquals(listOf("That set could not be opened."), r.notices)

        r.model.resolve("copy-1", SyncEngine.Resolution.KEEP_BOTH); advanceUntilIdle()
        assertEquals(listOf(Triple(RecordKind.THREAT_SET, "copy-1", SyncEngine.Resolution.KEEP_BOTH)), r.settled)
    }

    @Test
    fun `remove all leaves the sets, with nothing in them`() = runTest(dispatcher) {
        val r = rig()
        r.store.addAsSet(listOf(threat("A")), "ONE"); advanceUntilIdle()
        r.model.removeAll(); advanceUntilIdle()
        assertEquals(listOf("ONE"), r.state.sets.map { it.name })
        assertTrue(r.state.threats.isEmpty())
    }
}
