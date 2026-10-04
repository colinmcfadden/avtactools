package app.ezpztac.workspace

import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.geo.MgrsConverter
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
        assertEquals("A threat needs a name.", r.state.editing!!.error)                  // said in the form, where Save is
        assertNull(r.state.error)                                                         // not in the sheet's banner, which can be scrolled out of sight
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

    // -- What the form says ---------------------------------------------------------------------------------------------------

    @Test
    fun `typing again takes the refusal away, and a form that is then right saves`() = runTest(dispatcher) {
        val r = rig()
        r.model.beginAdd(LatLon(34.75, -84.05)); advanceUntilIdle()
        val draft = r.state.editing!!.draft
        r.model.updateDraft(draft.copy(radars = draft.radars.mapIndexed { i, rd -> if (i == 0) rd.copy(rangeNmi = "far") else rd }))
        r.model.saveEdit(); advanceUntilIdle()
        assertEquals("Detection range is not a number.", r.state.editing!!.error)
        r.model.updateDraft(draft); advanceUntilIdle()
        assertNull(r.state.editing!!.error)
        r.model.saveEdit(); advanceUntilIdle()
        assertEquals(1, r.state.threats.size)
    }

    @Test
    fun `a threat deleted while its form was open is not brought back`() = runTest(dispatcher) {
        val r = rig()
        val id = r.store.add(threat()); advanceUntilIdle()
        r.model.beginEdit(id); advanceUntilIdle()
        r.store.remove(id)
        r.model.saveEdit(); advanceUntilIdle()
        assertTrue(r.store.entries.value.isEmpty())
        assertNull(r.state.editing)
        assertEquals("That threat is no longer here.", r.state.error)
    }

    // -- The list and the held threat ----------------------------------------------------------------------------------------

    @Test
    fun `a row says what the threat is, how far each radar reaches and where it is`() = runTest(dispatcher) {
        val r = rig()
        r.store.add(threat("SA-8"))
        r.store.add(Threat("Odd one", "SHAPMFF-------", 34.75, -84.05, "", "SOF", radars = listOf(Radars.default(Radars.ENGAGEMENT).copy(rangeNmi = 12.5))))
        advanceUntilIdle()
        val rows = r.state.threats
        assertEquals(listOf("SAM Launcher", "Custom symbol"), rows.map { it.symbol })
        assertEquals("Detection 25 nm · Engagement 15 nm", rows[0].ranges)
        assertEquals("Engagement 12.5 nm", rows[1].ranges)
        assertEquals(MgrsConverter.toMgrs(34.75, -84.05)!!.format(), rows[0].grid)
    }

    @Test
    fun `the held threat is told in full, a radar to a line, and a hidden one is not held`() = runTest(dispatcher) {
        val r = rig()
        val id = r.store.add(threat().copy(information = "Seen at 0300", source = "HUMINT",
            radars = listOf(Radars.default(Radars.DETECTION), Radars.default(Radars.ENGAGEMENT).copy(aglNotMsl = false, showRangeRings = false))))
        r.model.select(id); advanceUntilIdle()
        val held = r.state.held!!
        assertEquals("SA-8", held.name)
        assertEquals("HUMINT", held.source)
        assertEquals("Seen at 0300", held.information)
        assertEquals("34.75000, -84.05000", held.latLon)
        assertEquals(listOf("Detection · 25 nm · antenna 20 ft AGL · rings on", "Engagement · 15 nm · antenna 20 ft MSL · rings off"), held.radars)
        r.model.toggleVisible(id); advanceUntilIdle()
        assertNull(r.state.held)
        r.selection.select("not-there"); advanceUntilIdle()
        assertNull(r.state.held)                                                           // a selection that names nothing shows nothing
    }

    // -- Moving ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a nudge moves the threat that many feet, and a typed grid puts it there or says what is wrong`() = runTest(dispatcher) {
        val r = rig()
        val id = r.store.add(threat()); advanceUntilIdle()
        r.model.nudge(id, 200.0, -50.0)
        val moved = r.store.entries.value.single().threat
        val expected = app.ezpztac.planning.GraphicEdits.offset(LatLon(34.75, -84.05), 200.0 * 0.3048, -50.0 * 0.3048)
        assertEquals(expected.lat, moved.lat, 1e-9)
        assertEquals(expected.lon, moved.lon, 1e-9)
        assertNull(r.model.moveToText(id, "16S GD 66993 52949"))
        assertEquals(34.78, r.store.entries.value.single().threat.lat, 0.01)
        val before = r.store.entries.value.single().threat
        assertNotNull(r.model.moveToText(id, "not a place"))
        assertEquals(before, r.store.entries.value.single().threat)                       // a refusal moves nothing
        r.model.nudge("nope", 10.0, 10.0)
        assertEquals(1, r.store.entries.value.size)
    }

    @Test
    fun `removing all forgets every threat, here and in the file`() = runTest(dispatcher) {
        val r = rig()
        r.store.add(threat("A")); r.store.add(threat("B")); advanceUntilIdle()
        r.model.select(r.store.entries.value.first().id)
        r.model.beginAdd(LatLon(34.0, -84.0))
        r.model.removeAll(); advanceUntilIdle()
        assertTrue(r.store.entries.value.isEmpty())
        assertNull(r.selection.held.value)
        assertNull(r.state.editing)
    }

    // -- A mission and its threats -----------------------------------------------------------------------------------------------

    @Test
    fun `with no threats a mission is shared as it is`() = runTest(dispatcher) {
        val r = rig()
        val mission = ExportFile("MISSION 1.msnx", byteArrayOf(1, 2, 3))
        var shared: List<ExportFile>? = null
        r.model.shareWithThreats(mission) { shared = it }
        advanceUntilIdle()
        assertEquals(listOf("MISSION 1.msnx"), shared!!.map { it.fileName })
        assertNull(r.state.error)
    }

    @Test
    fun `with threats the mission goes out with the ths that travels with it, named for it`() = runTest(dispatcher) {
        val r = rig()
        r.store.add(threat("A")); r.store.add(threat("B")); advanceUntilIdle()
        val mission = ExportFile("MISSION 1.msnx", byteArrayOf(1, 2, 3))
        var shared: List<ExportFile>? = null
        r.model.shareWithThreats(mission) { shared = it }
        advanceUntilIdle()
        assertEquals(listOf("MISSION 1.msnx", "MISSION 1.ths"), shared!!.map { it.fileName })
        assertTrue(shared[1].bytes.size > 1_000)
        assertNull(r.state.error)
    }
}
