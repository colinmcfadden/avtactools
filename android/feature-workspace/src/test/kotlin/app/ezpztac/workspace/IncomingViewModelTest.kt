package app.ezpztac.workspace

import app.ezpztac.data.FileInspector
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.formats.LpsReader
import app.ezpztac.formats.ThsReader
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A file another app hands to this one is looked at and offered; it is imported only when the person accepts. These are the rules: nothing is added on
 * arrival, declining leaves no trace, a threat file joins the picture and a points file becomes a saved set exactly as the section's own import does, and a
 * file that cannot be opened is told in words and closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private class Memory : ThreatVault {
        var picture: ThreatPicture? = null
        override fun load(): ThreatPicture? = picture
        override fun save(picture: ThreatPicture) { this.picture = picture }
        override fun wipe() { picture = null }
    }

    private class Rig(scope: TestScope) {
        val incoming = IncomingFiles()
        val selection = ThreatSelection()
        val threats = ThreatStore(Memory(), scope.backgroundScope, StandardTestDispatcher(scope.testScheduler)) { 1_000L }
        private val device = Device("A", FakeServer())
        val syncStore = device.store as InMemorySyncStore
        val sets = PointSetRepository(device.repository, syncStore, RecordingScheduler())
        val model = IncomingViewModel(incoming, FileInspector(ThreatTransfer(ThsWriter { ByteArray(0) })), threats, selection, sets)
            .also { it.worker = StandardTestDispatcher(scope.testScheduler) }
        val state get() = model.state.value
        suspend fun savedSets() = syncStore.transaction { records(RecordKind.POINT_SET).size }
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    private val threatFile = Fixtures.bytes("sqlite/threats.ths")
    private val pointsFile = Fixtures.bytes("sqlite/local-points.lps")
    private val missionFile = Fixtures.bytes("msnx/sketch-export.msnx")
    private val threatCount = ThsReader.read(threatFile).size
    private val pointCount = LpsReader.read(pointsFile, "x").points.size

    @Test
    fun `nothing is shown when nothing has arrived`() = runTest(dispatcher) {
        val r = rig()
        assertFalse(r.state.visible)
        assertNull(r.state.offer)
    }

    @Test
    fun `a file that has arrived is shown as being read until it has been looked at`() = runTest(dispatcher) {
        val r = rig()
        r.model.worker = StandardTestDispatcher(TestCoroutineScheduler())                    // a worker that is busy elsewhere
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        assertEquals(IncomingOfferUi.Reading("SA-6 site.ths"), r.state.offer)
        assertTrue(r.state.visible)
        assertTrue(r.threats.entries.value.isEmpty())
    }

    @Test
    fun `a file is offered once it has been looked at, and nothing is added meanwhile`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        assertEquals(IncomingOfferUi.Threats("SA-6 site.ths", threatCount), r.state.offer)
        assertTrue(r.threats.entries.value.isEmpty())
        assertEquals(0, r.savedSets())
        assertTrue(r.state.visible)
    }

    @Test
    fun `accepting a threat file adds its threats to the picture, holds the last, and says so`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        r.model.accept(); advanceUntilIdle()
        assertEquals(threatCount, r.threats.entries.value.size)
        assertEquals(r.threats.entries.value.last().id, r.selection.held.value)
        assertEquals("Added ${if (threatCount == 1) "1 threat" else "$threatCount threats"} from SA-6 site.ths.", r.state.result)
        assertNull(r.state.offer)
        assertTrue(r.incoming.files.value.isEmpty())
    }

    @Test
    fun `accepting adds to what is already there and does not replace it`() = runTest(dispatcher) {
        val r = rig()
        val existing = ThsReader.read(threatFile).first()
        r.threats.add(existing)
        r.incoming.offer("more.ths", threatFile)
        advanceUntilIdle()
        r.model.accept(); advanceUntilIdle()
        assertEquals(1 + threatCount, r.threats.entries.value.size)
    }

    @Test
    fun `declining forgets the file and leaves no trace`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.decline(); advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertNull(r.selection.held.value)
        assertNull(r.state.result)
        assertEquals(IncomingOfferUi.Points("NORTH GA.LPS", "NORTH GA", pointCount), r.state.offer)    // the next file is now at the front
        assertEquals(0, r.savedSets())
    }

    @Test
    fun `accepting a points file saves it as a set, as the Points section's own import does`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        assertEquals(IncomingOfferUi.Points("NORTH GA.LPS", "NORTH GA", pointCount), r.state.offer)
        assertEquals(0, r.savedSets())
        r.model.accept(); advanceUntilIdle()
        assertEquals(1, r.savedSets())
        assertEquals("Saved NORTH GA with ${if (pointCount == 1) "1 point" else "$pointCount points"}.", r.state.result)
        assertFalse(r.state.busy)
        assertTrue(r.incoming.files.value.isEmpty())
    }

    @Test
    fun `a second tap while a set is being saved saves nothing more`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.accept()
        r.model.accept()
        advanceUntilIdle()
        assertEquals(1, r.savedSets())
    }

    @Test
    fun `a mission is recognised and cannot be accepted, only closed`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("plan.msnx", missionFile)
        advanceUntilIdle()
        assertEquals(IncomingOfferUi.Mission("plan.msnx"), r.state.offer)
        r.model.accept(); advanceUntilIdle()
        assertEquals(IncomingOfferUi.Mission("plan.msnx"), r.state.offer)                  // accepting does nothing
        r.model.decline(); advanceUntilIdle()
        assertNull(r.state.offer)
    }

    @Test
    fun `a file that is none of these is told in words, and accepting it does nothing`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("notes.txt", "hello".toByteArray())
        advanceUntilIdle()
        val offer = r.state.offer as IncomingOfferUi.Problem
        assertEquals("notes.txt", offer.fileName)
        r.model.accept(); advanceUntilIdle()
        assertTrue(r.state.offer is IncomingOfferUi.Problem)
        assertTrue(r.threats.entries.value.isEmpty())
        assertEquals(0, r.savedSets())
    }

    @Test
    fun `a file that could not be read is told with its reason`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.refuse("gone.lps", "That file could not be read.")
        advanceUntilIdle()
        assertEquals(IncomingOfferUi.Problem("gone.lps", "That file could not be read."), r.state.offer)
        r.model.decline(); advanceUntilIdle()
        assertFalse(r.state.visible)
    }

    @Test
    fun `a file not yet looked at cannot be accepted`() = runTest(dispatcher) {
        val r = rig()
        r.model.worker = StandardTestDispatcher(TestCoroutineScheduler())                    // arrived, and the worker has not got to it
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        r.model.accept()
        advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertEquals(IncomingOfferUi.Reading("SA-6 site.ths"), r.state.offer)                // still waiting to be looked at, not imported blind
    }

    @Test
    fun `files behind the first are counted`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("a.ths", threatFile)
        r.incoming.offer("b.ths", threatFile)
        r.incoming.offer("c.ths", threatFile)
        advanceUntilIdle()
        assertEquals(2, r.state.waiting)
        r.model.decline(); advanceUntilIdle()
        assertEquals(1, r.state.waiting)
        r.model.decline(); r.model.decline(); advanceUntilIdle()
        assertEquals(0, r.state.waiting)
        assertFalse(r.state.visible)
    }

    @Test
    fun `clearing the files, as a sign-out does, leaves nothing to offer`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("a.ths", threatFile)
        advanceUntilIdle()
        r.incoming.clear(); advanceUntilIdle()
        assertFalse(r.state.visible)
        r.model.accept(); advanceUntilIdle()                                                  // nothing to accept
        assertTrue(r.threats.entries.value.isEmpty())
    }

    @Test
    fun `a result stays until it is closed, and closing it leaves the next offer`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("a.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.accept(); advanceUntilIdle()
        assertTrue(r.state.result != null)
        assertTrue(r.state.offer is IncomingOfferUi.Points)                                   // the result is shown over the next question
        r.model.closeResult(); advanceUntilIdle()
        assertNull(r.state.result)
        assertTrue(r.state.offer is IncomingOfferUi.Points)
    }

    @Test
    fun `a file that one reader refuses is refused in that reader's words and imports nothing`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("broken.lps", Fixtures.bytes("sqlite/values.db"))
        advanceUntilIdle()
        val offer = r.state.offer as IncomingOfferUi.Problem
        assertTrue(offer.message.isNotBlank())
        assertEquals(0, r.savedSets())
    }
}
