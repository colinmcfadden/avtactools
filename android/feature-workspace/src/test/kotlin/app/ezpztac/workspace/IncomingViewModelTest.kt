package app.ezpztac.workspace

import app.ezpztac.data.FileInspector
import app.ezpztac.data.IncomingFiles
import app.ezpztac.data.MapFocus
import app.ezpztac.data.MissionImporter
import app.ezpztac.data.PointSetRepository
import app.ezpztac.data.RouteRepository
import app.ezpztac.data.RouteSession
import app.ezpztac.data.SessionPointSets
import app.ezpztac.data.ThreatFileImport
import app.ezpztac.data.ThreatFileLog
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.formats.LpsReader
import app.ezpztac.formats.ThsReader
import app.ezpztac.model.PointSet
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import review: every waiting file is looked at and shown with where it would go, and nothing is brought in until the person says Import. These are its
 * rules: nothing is added on arrival, removing or cancelling leaves no trace, a threat file joins the picture on the device, a points file becomes a saved set
 * or one held for this session as chosen, a mission is kept with its file, and a file that cannot be brought in is told in words and brings nothing.
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
        val routes = RouteRepository(device.repository, syncStore, RecordingScheduler(), StandardTestDispatcher(scope.testScheduler))
        val routeSession = RouteSession(routes, scope.backgroundScope)
        val session = SessionPointSets()
        val threatFiles = ThreatFileLog(threats)
        val model = IncomingViewModel(
            incoming, FileInspector(ThreatTransfer(ThsWriter { ByteArray(0) })), threats, selection, sets, MissionImporter(routes, routeSession, MapFocus()),
            session, threatFiles,
        )
            .also { it.worker = StandardTestDispatcher(scope.testScheduler) }
            .also { it.allowThreats(true) }                                                      // the account has threats; one without them says so
        val said = mutableListOf<String>()
        val state get() = model.state.value
        suspend fun savedSets() = syncStore.transaction { records(RecordKind.POINT_SET) }
        suspend fun savedRouteSets() = syncStore.transaction { records(RecordKind.ROUTE).size }
        suspend fun savedMissions() = syncStore.transaction { records(RecordKind.MISSION).size }
        suspend fun loggedThreatFiles(): List<ThreatFileImport> = threatFiles.files.first()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { said += it } }
        }
    }

    private fun TestScope.rig() = Rig(this).also { advanceUntilIdle() }

    private val threatFile = Fixtures.bytes("sqlite/threats.ths")
    private val pointsFile = Fixtures.bytes("sqlite/local-points.lps")
    private val missionFile = Fixtures.bytes("msnx/template.msnx")
    private val threatCount = ThsReader.read(threatFile).size
    private val pointCount = LpsReader.read(pointsFile, "x").points.size

    private fun threats(n: Int) = if (n == 1) "1 threat" else "$n threats"
    private fun points(n: Int) = if (n == 1) "1 point" else "${withCommas(n.toLong())} points"

    @Test
    fun `nothing is shown when nothing has arrived`() = runTest(dispatcher) {
        val r = rig()
        assertFalse(r.state.visible)
        assertTrue(r.state.items.isEmpty())
    }

    @Test
    fun `a file that has arrived is shown as being read until it has been looked at, and Import waits for it`() = runTest(dispatcher) {
        val r = rig()
        r.model.worker = StandardTestDispatcher(TestCoroutineScheduler())                    // a worker that is busy elsewhere
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        assertEquals(listOf(ImportItemUi.Reading(1, "SA-6 site.ths")), r.state.items)
        assertTrue(r.state.visible)
        assertTrue(r.state.reading)
        assertEquals("Looking at what is in the files…", r.state.summary)
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())                                         // not imported blind
        assertEquals(1, r.incoming.files.value.size)
    }

    @Test
    fun `every file is shown with what is in it and where it goes, and nothing is brought in meanwhile`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        r.incoming.offer("template.msnx", missionFile)
        r.incoming.offer("notes.txt", "hello".toByteArray())
        advanceUntilIdle()
        val (threatItem, pointsItem, missionItem, problem) = r.state.items
        assertEquals(ImportItemUi.Threats(1, "SA-6 site.ths", IncomingViewModel.sizeOf(threatFile.size.toLong()), threatCount), threatItem)
        assertEquals(ImportItemUi.Points(2, "NORTH GA.LPS", IncomingViewModel.sizeOf(pointsFile.size.toLong()), pointCount, "NORTH GA", ImportPlace.LIBRARY), pointsItem)
        missionItem as ImportItemUi.Mission
        assertEquals(1, missionItem.routes)
        assertEquals("UH-60L", missionItem.aircraft)
        assertTrue(missionItem.namedPoints > 0)
        assertEquals("notes.txt", (problem as ImportItemUi.Problem).fileName)
        assertEquals(3, r.state.importable)
        assertEquals("2 to Library · 1 stays on this device", r.state.summary)
        assertTrue(r.threats.entries.value.isEmpty())
        assertTrue(r.savedSets().isEmpty())
        assertEquals(0, r.savedMissions())
    }

    @Test
    fun `the summary says where each goes as the choices change`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("a.LPS", pointsFile)
        r.incoming.offer("b.LPS", pointsFile)
        advanceUntilIdle()
        assertEquals("2 to Library", r.state.summary)
        r.model.setPlace(1, ImportPlace.SESSION); advanceUntilIdle()
        assertEquals("1 to Library · 1 stays in this session", r.state.summary)
        r.model.setPlace(2, ImportPlace.SESSION); advanceUntilIdle()
        assertEquals("2 stay in this session", r.state.summary)
        r.model.setPlace(2, ImportPlace.DEVICE); advanceUntilIdle()                          // points are never on the device only
        assertEquals(ImportPlace.SESSION, (r.state.items[1] as ImportItemUi.Points).place)
    }

    @Test
    fun `a review of nothing that can be brought in says so`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("notes.txt", "hello".toByteArray())
        advanceUntilIdle()
        assertEquals(0, r.state.importable)
        assertEquals("Nothing here can be imported.", r.state.summary)
    }

    @Test
    fun `Import brings each file in where it goes, says what each came to, and closes`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        r.incoming.offer("template.msnx", missionFile)
        advanceUntilIdle()
        r.model.setName(2, "KGVL TAXI")
        r.model.importAll(); advanceUntilIdle()

        assertEquals(threatCount, r.threats.entries.value.size)                               // the threats are in the picture,
        assertEquals(r.threats.entries.value.last().id, r.selection.held.value)               // the last held,
        assertEquals(listOf(ThreatFileImport("SA-6 site.ths", threatCount)), r.loggedThreatFiles())   // and the file listed under Imports
        assertEquals(listOf("KGVL TAXI"), r.savedSets().map { it.name })                      // the points saved under the name typed
        assertEquals(1, r.savedMissions())                                                    // the mission kept with its file
        assertEquals(0, r.savedRouteSets())
        assertEquals("TEMPLATE", r.routeSession.active.value!!.name)                          // and opened
        assertEquals(
            listOf(
                "Added ${threats(threatCount)} from SA-6 site.ths.",
                "Saved KGVL TAXI with ${points(pointCount)}.",
                "Brought in 1 route from template.msnx as TEMPLATE.",
            ),
            r.said,
        )
        assertFalse(r.state.visible)
        assertFalse(r.state.busy)
        assertTrue(r.session.sets.value.isEmpty())
    }

    @Test
    fun `points for this session are shown and used but never saved`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.setPlace(1, ImportPlace.SESSION)
        r.model.setName(1, "  TAXI  ")
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.savedSets().isEmpty())
        val held = r.session.sets.value.single()
        assertEquals("TAXI", held.name)
        assertEquals(pointCount, held.points.size)
        assertEquals(listOf("Showing TAXI for this session: ${points(pointCount)}, not saved."), r.said)
        assertFalse(r.state.visible)
    }

    @Test
    fun `a name left blank is the file's`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.setName(1, "   ")
        r.model.importAll(); advanceUntilIdle()
        assertEquals(listOf("NORTH GA"), r.savedSets().map { it.name })
    }

    @Test
    fun `a session that holds as many sets as it can says so and holds no more`() = runTest(dispatcher) {
        val r = rig()
        repeat(SessionPointSets.MAX) { r.session.add(PointSet(id = "s$it", name = "S$it", points = emptyList())) }
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.setPlace(1, ImportPlace.SESSION)
        r.model.importAll(); advanceUntilIdle()
        assertEquals(SessionPointSets.MAX, r.session.sets.value.size)
        assertTrue(r.savedSets().isEmpty())
        assertEquals(listOf(SessionPointSets.FULL), r.said)
    }

    @Test
    fun `removing a file takes it out of the list and brings nothing in from it`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.remove(1); advanceUntilIdle()
        assertEquals(listOf(2L), r.state.items.map { it.id })
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertEquals(1, r.savedSets().size)
    }

    @Test
    fun `cancelling lets every file go and leaves no trace`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        r.incoming.offer("template.msnx", missionFile)
        advanceUntilIdle()
        r.model.cancel(); advanceUntilIdle()
        assertFalse(r.state.visible)
        assertTrue(r.threats.entries.value.isEmpty())
        assertTrue(r.selection.held.value == null)
        assertTrue(r.savedSets().isEmpty())
        assertEquals(0, r.savedMissions())
        assertTrue(r.said.isEmpty())
        assertTrue(r.loggedThreatFiles().isEmpty())
    }

    @Test
    fun `an account without threats is told so, and Import adds none`() = runTest(dispatcher) {
        val r = rig()
        r.model.allowThreats(false)
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        assertEquals(ImportItemUi.Problem(1, "SA-6 site.ths", IncomingViewModel.NO_THREATS), r.state.items[0])
        assertEquals(1, r.state.importable)
        assertEquals("1 to Library", r.state.summary)
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
        assertTrue(r.loggedThreatFiles().isEmpty())
        assertEquals(1, r.savedSets().size)
    }

    @Test
    fun `Import pressed twice brings each file in once`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        r.incoming.offer("template.msnx", missionFile)
        advanceUntilIdle()
        r.model.importAll()
        r.model.importAll()
        advanceUntilIdle()
        assertEquals(1, r.savedSets().size)
        assertEquals(1, r.savedMissions())
    }

    @Test
    fun `a file that arrives while files are being brought in waits for the next look`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.importAll()
        assertTrue(r.model.state.first { it.busy }.busy)
        r.incoming.offer("SA-6 site.ths", threatFile)
        advanceUntilIdle()
        assertEquals(1, r.savedSets().size)
        assertTrue(r.threats.entries.value.isEmpty())
        assertEquals(listOf("SA-6 site.ths"), r.state.items.map { it.fileName })              // the review stays open with it
    }

    @Test
    fun `nothing is removed or cancelled while files are being brought in`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        r.incoming.offer("template.msnx", missionFile)
        advanceUntilIdle()
        r.model.importAll()
        r.model.remove(2)
        r.model.cancel()
        advanceUntilIdle()
        assertEquals(1, r.savedSets().size)
        assertEquals(1, r.savedMissions())                                                    // both went in, as they were when Import was pressed
    }

    @Test
    fun `a file one reader refuses is shown in that reader's words and brings nothing`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("broken.lps", Fixtures.bytes("sqlite/values.db"))
        advanceUntilIdle()
        val problem = r.state.items.single() as ImportItemUi.Problem
        assertTrue(problem.message.isNotBlank())
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.savedSets().isEmpty())
        assertFalse(r.state.visible)
    }

    @Test
    fun `a file that could not be read is shown with its reason`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.refuse("gone.lps", "That file could not be read.")
        advanceUntilIdle()
        assertEquals(listOf(ImportItemUi.Problem(1, "gone.lps", "That file could not be read.")), r.state.items)
    }

    @Test
    fun `clearing the files, as a sign-out does, leaves nothing to bring in`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("a.ths", threatFile)
        advanceUntilIdle()
        r.incoming.clear(); advanceUntilIdle()
        assertFalse(r.state.visible)
        r.model.importAll(); advanceUntilIdle()
        assertTrue(r.threats.entries.value.isEmpty())
    }

    @Test
    fun `a file chosen in the app joins the review like any other`() = runTest(dispatcher) {
        val r = rig()
        r.model.offer("NORTH GA.LPS", pointsFile)
        r.model.refuse("a file you chose", "That file could not be read.")
        advanceUntilIdle()
        assertEquals(listOf("NORTH GA.LPS", "a file you chose"), r.state.items.map { it.fileName })
    }

    @Test
    fun `the words after a mission is brought in say where it is when it could not be opened`() = runTest(dispatcher) {
        val r = rig()
        assertEquals("Brought in 1 route from a.msnx as A.", r.model.missionResult(1, "a.msnx", "A", opened = true))
        assertEquals("Brought in 1,200 routes from a.msnx as A. It is in your list of route sets and missions.", r.model.missionResult(1200, "a.msnx", "A", opened = false))
        assertEquals("Brought in 1 route from a.msnx as A. It is in your Library: switch to Personal to open it.", r.model.missionResult(1, "a.msnx", "A", opened = false, inPack = true))
    }

    /** A worker that does nothing until [run] is called: a file being read for a while. */
    private class SlowWorker {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        fun run() = scheduler.advanceUntilIdle()
    }

    /** Lets [slow] and the test's own scheduler take turns until both are done (a read on one, the store and the session on the other). */
    private fun TestScope.finish(slow: SlowWorker) = repeat(6) { slow.run(); advanceUntilIdle() }

    @Test
    fun `a set for this session still being read at a sign-out is not shown to whoever signs in next`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        r.model.setPlace(1, ImportPlace.SESSION)
        val slow = SlowWorker().also { r.model.worker = it.dispatcher }
        r.model.importAll(); advanceUntilIdle()                                               // the file is being read
        r.incoming.clear(); r.session.clear()                                                 // as a sign-out does, first
        finish(slow)
        assertTrue(r.session.sets.value.isEmpty())
        assertTrue(r.said.isEmpty())
        assertFalse(r.state.busy)
    }

    @Test
    fun `a mission still being brought in at a sign-out is kept for its account but not opened on the next person's map`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("template.msnx", missionFile)
        advanceUntilIdle()
        val slow = SlowWorker().also { r.model.worker = it.dispatcher }
        r.model.importAll(); advanceUntilIdle()
        r.incoming.clear()
        finish(slow)
        assertEquals(null, r.routeSession.active.value)
        assertTrue(r.said.isEmpty())
    }

    @Test
    fun `files saved to the Library as a sign-out came are not told to whoever signs in next`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        val slow = SlowWorker().also { r.model.worker = it.dispatcher }
        r.model.importAll(); advanceUntilIdle()
        r.incoming.clear()
        finish(slow)
        assertTrue(r.said.isEmpty())
    }

    @Test
    fun `what each file came to is said once the review has closed, not under it`() = runTest(dispatcher) {
        val r = rig()
        r.incoming.offer("SA-6 site.ths", threatFile)
        r.incoming.offer("NORTH GA.LPS", pointsFile)
        advanceUntilIdle()
        val slow = SlowWorker().also { r.model.worker = it.dispatcher }
        r.model.importAll(); advanceUntilIdle()
        assertEquals(threatCount, r.threats.entries.value.size)                               // the threats are in, and the points still being saved:
        assertTrue(r.state.visible)
        assertTrue(r.said.isEmpty())                                                           // so nothing is said yet, under the review
        finish(slow)
        assertFalse(r.state.visible)
        assertEquals(listOf("Added ${threats(threatCount)} from SA-6 site.ths.", "Saved NORTH GA with ${points(pointCount)}."), r.said)
    }

    @Test
    fun `once the review is cancelled, files of the same pick still being read are not put in it`() = runTest(dispatcher) {
        val r = rig()
        val pick = r.model.pickStarted()
        r.model.offer("a.ths", threatFile, pick)
        advanceUntilIdle()
        r.model.cancel(); advanceUntilIdle()
        r.model.offer("b.ths", threatFile, pick)                                               // read after the person said no
        r.model.refuse("c.ths", "That file could not be read.", pick)
        advanceUntilIdle()
        assertFalse(r.state.visible)
        val next = r.model.pickStarted()                                                       // a new pick is put to them as usual
        r.model.offer("d.ths", threatFile, next)
        advanceUntilIdle()
        assertEquals(listOf("d.ths"), r.state.items.map { it.fileName })
    }

    @Test
    fun `a size is said as the design says it, in plain digits`() {
        assertEquals("900 bytes", IncomingViewModel.sizeOf(900))
        assertEquals("1 KB", IncomingViewModel.sizeOf(1024))
        assertEquals("82 KB", IncomingViewModel.sizeOf(82 * 1024 + 100))
        assertEquals("1.5 MB", IncomingViewModel.sizeOf(1_572_864))
    }
}
