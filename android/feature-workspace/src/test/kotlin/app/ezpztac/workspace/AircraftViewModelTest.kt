package app.ezpztac.workspace

import app.ezpztac.data.AircraftProfiles
import app.ezpztac.data.InMemoryAircraftChoice
import app.ezpztac.data.InMemoryMasterProfileStore
import app.ezpztac.model.AircraftDraft
import app.ezpztac.model.AircraftProfile
import app.ezpztac.sync.ConflictResolver
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncEngine
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/** The aircraft screen's logic: what is listed, what a tap on it opens, and what saving does to the user's own profiles. */
@OptIn(ExperimentalCoroutinesApi::class)
class AircraftViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val uh60 = AircraftProfile(id = 1, slug = "uh60l", name = "UH-60L Black Hawk", designation = "UH-60L")
    private val ch47 = AircraftProfile(id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0, perfSource = "published")

    private class Rig(scope: TestScope, master: List<AircraftProfile>, server: FakeServer = FakeServer(), resolver: ConflictResolver) {
        val device = Device("A", server)
        val scheduler = RecordingScheduler()
        val aircraft = AircraftProfiles(
            device.store as InMemorySyncStore, InMemoryMasterProfileStore(master), { emptyList() }, InMemoryAircraftChoice(),
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, scheduler,
        )
        val model = AircraftViewModel(aircraft, resolver)
        val state get() = model.state.value
    }

    private val resolved = mutableListOf<Triple<RecordKind, String, SyncEngine.Resolution>>()

    private fun TestScope.rig(master: List<AircraftProfile> = listOf(uh60, ch47)) =
        Rig(this, master, resolver = { kind, uuid, how -> resolved += Triple(kind, uuid, how) }).also { advanceUntilIdle() }

    private suspend fun Rig.ownSynced(name: String, slug: String) {
        device.repository.create(
            RecordKind.AIRCRAFT, name,
            JsonObject(mapOf("designation" to JsonPrimitive("X-1"), "slug" to JsonPrimitive(slug), "rotor_diameter_m" to JsonPrimitive(12.0), "icon_key" to JsonPrimitive("generic"))),
        )
        device.sync()
    }

    private val night = AircraftDraft(name = "Night Hawk", designation = "MH-60M")

    // -- The list ------------------------------------------------------------------------------------------------

    @Test
    fun `the admin's airframes are listed first, with the mission aircraft marked and the numbers a planner reads`() = runTest(dispatcher) {
        val r = rig()
        val rows = r.state.rows
        assertEquals(listOf("uh60l", "ch47f"), rows.map { it.key })
        assertEquals("UH-60L — UH-60L Black Hawk", rows[0].title)
        assertEquals("76 m spacing · 100 kt", rows[0].detail)
        assertEquals(listOf(true, false), rows.map { it.chosen })                                  // nothing chosen: the UH-60L
        assertEquals("93 m spacing · 100 kt", rows[1].detail)                                      // 18.29 + 75 = 93.29, as the planner rounds it
        assertEquals(listOf(false, true), rows.map { it.unverified })
        assertEquals(listOf(false, false), rows.map { it.own })
        assertNull(r.state.form)
        assertNull(r.state.error)
    }

    @Test
    fun `the user's own profiles follow, keyed by their record, and one the server has not named cannot be used`() = runTest(dispatcher) {
        val r = rig()
        r.ownSynced("Alpha Heli", "alpha")
        r.aircraft.create(night)
        advanceUntilIdle()
        val own = r.state.rows.filter { it.own }
        assertEquals(listOf("X-1 — Alpha Heli", "MH-60M — Night Hawk"), own.map { it.title })
        assertTrue(own.all { it.uuid != null && it.key == it.uuid })
        assertEquals(listOf(true, false), own.map { it.usable })
        assertEquals(listOf(SyncStatus.SYNCED, SyncStatus.PENDING), own.map { it.sync })
    }

    @Test
    fun `choosing an airframe makes it the mission aircraft`() = runTest(dispatcher) {
        val r = rig()
        r.model.choose("ch47f")
        advanceUntilIdle()
        assertEquals(listOf(false, true), r.state.rows.map { it.chosen })
        assertNull(r.state.error)
    }

    @Test
    fun `choosing one that cannot be chosen yet says so, and changes nothing`() = runTest(dispatcher) {
        val r = rig()
        r.aircraft.create(night)
        advanceUntilIdle()
        r.model.choose("")
        advanceUntilIdle()
        assertEquals("That aircraft can be chosen once it has synced.", r.state.error)
        assertEquals(listOf(true, false), r.state.rows.take(2).map { it.chosen })
        r.model.dismissError()
        advanceUntilIdle()
        assertNull(r.state.error)
    }

    @Test
    fun `a conflict copy of the mission aircraft is not also marked as the mission aircraft`() = runTest(dispatcher) {
        val server = FakeServer()
        val r = Rig(this, listOf(uh60), server, resolver = { _, _, _ -> })
        advanceUntilIdle()
        r.ownSynced("Alpha Heli", "alpha")
        advanceUntilIdle()
        val uuid = r.state.rows.single { it.own }.uuid!!
        r.aircraft.select("alpha")
        server.editElsewhere(RecordKind.AIRCRAFT, uuid, name = "Alpha (changed elsewhere)")
        r.aircraft.update(uuid, AircraftDraft.of(r.aircraft.entries.value.single { it.own }.profile).copy(name = "Alpha (mine)"))
        r.device.sync()
        advanceUntilIdle()
        val own = r.state.rows.filter { it.own }
        assertEquals(2, own.size)                                                            // the server's, and the user's kept beside it
        assertEquals(1, own.count { it.chosen })                                             // exactly one is the mission aircraft
        assertNull(own.single { it.chosen }.conflictOf)
        assertEquals(SyncStatus.CONFLICT, own.single { it.conflictOf != null }.sync)
    }

    // -- Opening the form ----------------------------------------------------------------------------------------

    @Test
    fun `new aircraft opens a blank form, and cancel closes it`() = runTest(dispatcher) {
        val r = rig()
        r.model.startNew()
        advanceUntilIdle()
        assertEquals(AircraftFormUi(editing = null, draft = AircraftDraft()), r.state.form)
        r.model.cancel()
        advanceUntilIdle()
        assertNull(r.state.form)
    }

    @Test
    fun `copying an airframe opens a new profile with its numbers and a name that says it is a copy`() = runTest(dispatcher) {
        val r = rig()
        r.model.startCopy("ch47f")
        advanceUntilIdle()
        val form = r.state.form!!
        assertNull(form.editing)
        assertEquals(AircraftDraft.copyOf(ch47), form.draft)
        assertEquals("CH-47F Chinook (copy)", form.draft.name)
    }

    @Test
    fun `changing an own profile opens it with its numbers`() = runTest(dispatcher) {
        val r = rig()
        r.ownSynced("Alpha Heli", "alpha")
        advanceUntilIdle()
        val own = r.state.rows.single { it.own }
        r.model.startEdit(own.uuid!!)
        advanceUntilIdle()
        val form = r.state.form!!
        assertEquals(own.uuid, form.editing)
        assertEquals("Alpha Heli", form.draft.name)
        assertEquals("12", form.draft.rotorDiameterM)
    }

    @Test
    fun `an airframe of the admin's cannot be changed, only copied`() = runTest(dispatcher) {
        val r = rig()
        r.model.startEdit("ch47f")
        r.model.startCopy("nowhere")
        advanceUntilIdle()
        assertNull(r.state.form)
    }

    @Test
    fun `what is typed is kept, and typing clears the last refusal`() = runTest(dispatcher) {
        val r = rig()
        r.model.startNew()
        r.model.change(AircraftDraft(name = "Night"))
        r.model.save()
        advanceUntilIdle()
        assertNotNull(r.state.form!!.error)
        r.model.change(AircraftDraft(name = "Night Hawk"))
        advanceUntilIdle()
        assertNull(r.state.form!!.error)
        assertEquals("Night Hawk", r.state.form!!.draft.name)
    }

    // -- Saving --------------------------------------------------------------------------------------------------

    @Test
    fun `saving a new aircraft makes it, closes the form and asks for a sync`() = runTest(dispatcher) {
        val r = rig()
        r.model.startNew()
        r.model.change(night)
        r.model.save()
        advanceUntilIdle()
        assertNull(r.state.form)
        val made = r.state.rows.single { it.own }
        assertEquals("MH-60M — Night Hawk", made.title)
        assertEquals(1, r.scheduler.requested)
    }

    @Test
    fun `an aircraft that is not good stays in the form with the reason, and makes nothing`() = runTest(dispatcher) {
        val r = rig()
        r.model.startNew()
        r.model.change(night.copy(rotorDiameterM = "61"))
        r.model.save()
        advanceUntilIdle()
        val form = r.state.form!!
        assertEquals("Rotor diameter must be between 1 and 60.", form.error)
        assertFalse(form.busy)
        assertEquals(night.copy(rotorDiameterM = "61"), form.draft)                                // what was typed is still there
        assertTrue(r.state.rows.none { it.own })
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `saving twice before the first has finished makes one aircraft`() = runTest(dispatcher) {
        val r = rig()
        r.model.startNew()
        r.model.change(night)
        r.model.save()
        r.model.save()
        advanceUntilIdle()
        assertEquals(1, r.state.rows.count { it.own })
        assertEquals(1, r.scheduler.requested)
    }

    @Test
    fun `saving a change updates the aircraft in place`() = runTest(dispatcher) {
        val r = rig()
        r.ownSynced("Alpha Heli", "alpha")
        advanceUntilIdle()
        val uuid = r.state.rows.single { it.own }.uuid!!
        r.model.startEdit(uuid)
        advanceUntilIdle()
        r.model.change(r.state.form!!.draft.copy(name = "Alpha II", rotorDiameterM = "14.5"))
        r.model.save()
        advanceUntilIdle()
        assertNull(r.state.form)
        val changed = r.state.rows.single { it.own }
        assertEquals(uuid, changed.uuid)
        assertEquals("X-1 — Alpha II", changed.title)
        assertEquals("75 m spacing · 100 kt", changed.detail)                                      // 14.5 + 60 = 74.5, rounded as the planner does
        assertEquals(SyncStatus.PENDING, changed.sync)
    }

    // -- The list's own actions ----------------------------------------------------------------------------------

    @Test
    fun `deleting an own aircraft removes it`() = runTest(dispatcher) {
        val r = rig()
        r.ownSynced("Alpha Heli", "alpha")
        advanceUntilIdle()
        r.model.delete(r.state.rows.single { it.own }.uuid!!)
        advanceUntilIdle()
        assertTrue(r.state.rows.none { it.own })
        assertEquals(listOf("uh60l", "ch47f"), r.state.rows.map { it.key })
    }

    @Test
    fun `a conflict copy is settled as a record of its own kind`() = runTest(dispatcher) {
        val r = rig()
        r.model.resolve("copy-1", SyncEngine.Resolution.KEEP_BOTH)
        advanceUntilIdle()
        assertEquals(listOf(Triple(RecordKind.AIRCRAFT, "copy-1", SyncEngine.Resolution.KEEP_BOTH)), resolved)
    }

    @Test
    fun `a conflict that cannot be settled says so`() = runTest(dispatcher) {
        val r = Rig(this, listOf(uh60), resolver = { _, _, _ -> error("boom") })
        advanceUntilIdle()
        r.model.resolve("copy-1", SyncEngine.Resolution.KEEP_MINE)
        advanceUntilIdle()
        assertEquals("The conflict could not be settled.", r.state.error)
    }
}
