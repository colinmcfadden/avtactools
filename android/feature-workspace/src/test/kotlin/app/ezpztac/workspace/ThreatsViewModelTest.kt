package app.ezpztac.workspace

import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.model.LatLon
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThreatsViewModelTest {
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
        val selection = ThreatSelection()
        val store = ThreatStore(Memory(), scope.backgroundScope, StandardTestDispatcher(scope.testScheduler)) { 1_000L }
        private val template = File("../../backend/threat_template.ths").readBytes()
        val model = ThreatsViewModel(store, selection, ThreatTransfer(ThsWriter { template })).also {
            it.worker = StandardTestDispatcher(scope.testScheduler)
        }
        val state get() = model.state.value
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }
    private fun threat(name: String = "SA-8") = Threat(name, "SHGPEWRR------", 34.75, -84.05, "", "SOF", radars = Radars.defaultPair())

    @Test
    fun `a new threat needs a map position and valid form before it is added and held`() = runTest(dispatcher) {
        val r = rig()
        r.model.beginAdd(null); advanceUntilIdle()
        assertEquals("Move the map to where the threat should go first.", r.state.error)

        val at = LatLon(34.75, -84.05)
        r.model.beginAdd(at); advanceUntilIdle()
        assertEquals("Threat 1", r.state.editing!!.draft.name)
        r.model.updateDraft(r.state.editing!!.draft.copy(name = "   "))
        r.model.saveEdit(); advanceUntilIdle()
        assertEquals("A threat needs a name.", r.state.error)
        assertTrue(r.state.threats.isEmpty())

        r.model.updateDraft(r.state.editing!!.draft.copy(name = "SA-8"))
        r.model.saveEdit(); advanceUntilIdle()
        assertEquals("Threat added.", r.state.note)
        assertNull(r.state.editing)
        assertEquals("SA-8", r.state.threats.single().name)
        assertEquals(r.state.threats.single().id, r.selection.held.value)
    }

    @Test
    fun `editing keeps the position while changing the threat`() = runTest(dispatcher) {
        val r = rig()
        val id = r.store.add(threat()); advanceUntilIdle()
        r.model.beginEdit(id); advanceUntilIdle()
        r.model.updateDraft(r.state.editing!!.draft.copy(name = "SA-9", source = "S2"))
        r.model.saveEdit(); advanceUntilIdle()
        assertEquals("SA-9", r.store.entries.value.single().threat.name)
        assertEquals("S2", r.store.entries.value.single().threat.source)
        assertEquals(34.75, r.store.entries.value.single().threat.lat, 0.0)
        assertEquals(-84.05, r.store.entries.value.single().threat.lon, 0.0)
        assertEquals("Threat updated.", r.state.note)
    }

    @Test
    fun `a held threat can be hidden moved and removed`() = runTest(dispatcher) {
        val r = rig()
        val id = r.store.add(threat()); advanceUntilIdle()
        r.model.select(id); advanceUntilIdle()
        assertTrue(r.state.threats.single().held)

        r.model.toggleVisible(id); advanceUntilIdle()
        assertFalse(r.state.threats.single().visible)
        assertNull(r.selection.held.value)

        r.model.moveToCrosshair(id, null); advanceUntilIdle()
        assertEquals("Move the map to the new position first.", r.state.error)
        r.model.moveToCrosshair(id, LatLon(35.0, -85.0)); advanceUntilIdle()
        assertEquals(35.0, r.store.entries.value.single().threat.lat, 0.0)
        assertEquals("Threat moved to the map centre.", r.state.note)

        r.model.remove(id); advanceUntilIdle()
        assertTrue(r.state.threats.isEmpty())
    }

    @Test
    fun `importing an AMPS file adds its threats and holds the last one`() = runTest(dispatcher) {
        val r = rig()
        r.model.importFile(Fixtures.bytes("sqlite/threats.ths")); advanceUntilIdle()
        assertFalse(r.state.importing)
        assertTrue(r.state.threats.isNotEmpty())
        assertEquals("Imported ${r.state.threats.size} threats.", r.state.note)
        assertEquals(r.state.threats.last().id, r.selection.held.value)
        assertNull(r.state.error)
    }

    @Test
    fun `sharing builds one ths event and an empty picture is refused`() = runTest(dispatcher) {
        val r = rig()
        r.model.export(); advanceUntilIdle()
        assertEquals("There are no threats to export.", r.state.error)
        assertFalse(r.state.exporting)

        r.store.add(threat()); advanceUntilIdle()
        val files = mutableListOf<ExportFile>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.model.exports.collect { files += it } }
        r.model.export("MISSION 1.msnx"); advanceUntilIdle()
        val file = files.single()
        assertEquals("MISSION 1.ths", file.fileName)
        assertTrue(file.bytes.size > 1_000)
        assertEquals("Threat file ready to share.", r.state.note)
        assertFalse(r.state.exporting)
        assertNotNull(file.bytes)
    }
}
