package app.ezpztac.data

import app.ezpztac.model.AircraftDraft
import app.ezpztac.model.AircraftProfile
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The airframes the app knows: the master list kept on the device, the user's own from the sync store, and the one this device plans with. */
@OptIn(ExperimentalCoroutinesApi::class)
class AircraftProfilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val uh60 = AircraftProfile(id = 1, slug = "uh60l", name = "UH-60L Black Hawk", designation = "UH-60L")
    private val ch47 = AircraftProfile(id = 2, slug = "ch47f", name = "CH-47F Chinook", designation = "CH-47F", iconKey = "ch47", rotorDiameterM = 18.29, rotorTipClearanceM = 75.0)
    private val mh6 = AircraftProfile(id = 3, slug = "mh6", name = "MH-6 Little Bird", designation = "MH-6", iconKey = "mh6", rotorDiameterM = 8.33)
    private val master = listOf(uh60, ch47, mh6)

    private class Store(var kept: List<AircraftProfile> = emptyList()) : MasterProfileStore {
        var writes = 0
        override fun read() = kept
        override fun write(profiles: List<AircraftProfile>) {
            kept = profiles
            writes++
        }
    }

    private class Choice(var slug: String? = null) : ActiveAircraftChoice {
        override fun slug() = slug
        override fun choose(slug: String) {
            this.slug = slug
        }
    }

    private class Rig(scope: TestScope, kept: List<AircraftProfile>, chosen: String?, var fetch: suspend () -> List<AircraftProfile>) {
        val server = FakeServer()
        val device = Device("A", server)
        val store = Store(kept)
        val choice = Choice(chosen)
        val scheduler = RecordingScheduler()
        // Not backgroundScope: advanceUntilIdle leaves a background scope's work alone, and the lists are work a test waits for.
        val profiles = AircraftProfiles(device.store as InMemorySyncStore, store, { fetch() }, choice, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), device.repository, scheduler)
    }

    private fun TestScope.rig(kept: List<AircraftProfile> = emptyList(), chosen: String? = null, fetch: suspend () -> List<AircraftProfile> = { emptyList() }) =
        Rig(this, kept, chosen, fetch).also { advanceUntilIdle() }

    private suspend fun Rig.own(name: String, slug: String?, designation: String = name, synced: Boolean = true, rotor: Double = 12.0) {
        val data = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("designation", JsonPrimitive(designation)); put("rotor_diameter_m", JsonPrimitive(rotor)); put("icon_key", JsonPrimitive("generic"))
            if (slug != null) put("slug", JsonPrimitive(slug))
        }
        val made = device.repository.create(RecordKind.AIRCRAFT, name, JsonObject(data))
        if (synced) device.sync()
        made.uuid
    }

    // -- What is known -------------------------------------------------------------------------------------------------

    @Test
    fun `before anything has loaded the built-in UH-60L is the only airframe, and the mission aircraft`() = runTest {
        val r = rig()
        assertEquals(emptyList<AircraftProfile>(), r.profiles.profiles.value)
        assertEquals(AircraftProfile.FALLBACK, r.profiles.active.value)
    }

    @Test
    fun `the master list kept from last time is there at once, with no signal`() = runTest {
        val r = rig(kept = master)
        assertEquals(master, r.profiles.profiles.value)
        assertEquals(listOf(false, false, false), r.profiles.entries.value.map { it.own })
    }

    @Test
    fun `only master airframes are taken from what was kept`() = runTest {
        val r = rig(kept = master + uh60.copy(slug = "mine", isSystem = false))
        assertEquals(master.map { it.slug }, r.profiles.profiles.value.map { it.slug })
    }

    // -- Refreshing ----------------------------------------------------------------------------------------------------

    @Test
    fun `refreshing keeps the new list for next time and shows it`() = runTest {
        val r = rig(fetch = { master })
        assertTrue(r.profiles.refresh())
        advanceUntilIdle()
        assertEquals(master, r.profiles.profiles.value)
        assertEquals(master, r.store.kept)
    }

    @Test
    fun `a refresh that finds nothing new writes nothing`() = runTest {
        val r = rig(kept = master, fetch = { master })
        assertTrue(r.profiles.refresh())
        assertEquals(0, r.store.writes)
    }

    @Test
    fun `a refresh that fails leaves the list as it was, and says why to a caller who asks`() = runTest {
        val r = rig(kept = master, fetch = { throw IllegalStateException("no signal") })
        try {
            r.profiles.refresh()
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals("no signal", e.message)
        }
        r.profiles.refreshQuietly()                                                        // and says nothing to one who does not
        advanceUntilIdle()
        assertEquals(master, r.profiles.profiles.value)
        assertEquals(0, r.store.writes)
    }

    @Test
    fun `a server that sends no master airframes is a fault, not an empty list`() = runTest {
        val r = rig(kept = master, fetch = { listOf(uh60.copy(isSystem = false)) })
        assertFalse(r.profiles.refresh())
        assertEquals(master, r.profiles.profiles.value)
        assertEquals(0, r.store.writes)
    }

    @Test
    fun `the user's own profiles the server also lists are not taken as master ones`() = runTest {
        val r = rig(fetch = { master + mh6.copy(id = 99, slug = "mine", isSystem = false) })
        r.profiles.refresh()
        advanceUntilIdle()
        assertEquals(master.map { it.slug }, r.profiles.profiles.value.map { it.slug })
    }

    // -- The user's own ------------------------------------------------------------------------------------------------

    @Test
    fun `own profiles follow the master list, by name, and are the user's`() = runTest {
        val r = rig(kept = master)
        r.own("Zulu Heli", "zulu")
        r.own("Alpha Heli", "alpha")
        advanceUntilIdle()
        assertEquals(listOf("uh60l", "ch47f", "mh6", "alpha", "zulu"), r.profiles.profiles.value.map { it.slug })
        val own = r.profiles.entries.value.filter { it.own }
        assertEquals(listOf(false, false), own.map { it.profile.isSystem })
        assertEquals("Alpha Heli", own[0].profile.name)
        assertEquals(12.0, own[0].profile.rotorDiameterM, 0.0)
        assertEquals(SyncStatus.SYNCED, own[0].sync)
        assertTrue(own[0].uuid != null)
    }

    @Test
    fun `an own profile the server has not seen has no slug, is listed, and cannot be chosen`() = runTest {
        val r = rig(kept = master)
        r.own("Draft Heli", slug = null, synced = false)
        advanceUntilIdle()
        val draft = r.profiles.entries.value.single { it.own }
        assertEquals("", draft.profile.slug)                                                // not the UH-60L's "uh60l" it would otherwise inherit
        assertFalse(draft.usable)
        assertEquals(SyncStatus.PENDING, draft.sync)
        assertEquals(master.map { it.slug }, r.profiles.profiles.value.map { it.slug })
        assertFalse(r.profiles.select(""))
    }

    @Test
    fun `a profile that comes back from the server with its slug can be chosen`() = runTest {
        val r = rig(kept = master)
        r.own("Draft Heli", slug = "draft-heli")
        advanceUntilIdle()
        assertTrue(r.profiles.select("draft-heli"))
        advanceUntilIdle()
        assertEquals("Draft Heli", r.profiles.active.value.name)
    }

    // -- Making, changing and deleting the user's own --------------------------------------------------------------------

    private val night = AircraftDraft(name = "Night Hawk", designation = "MH-60M", rotorDiameterM = "16.4", rotorTipClearanceM = "55")

    private fun Rig.ownEntries() = profiles.entries.value.filter { it.own }

    @Test
    fun `a profile made here is kept at once with no signal, queued, and the sync is asked for`() = runTest {
        val r = rig(kept = master)
        assertEquals(null, r.profiles.create(night))
        advanceUntilIdle()
        val made = r.ownEntries().single()
        assertEquals("Night Hawk", made.profile.name)
        assertEquals("MH-60M", made.profile.designation)
        assertEquals(16.4, made.profile.rotorDiameterM, 0.0)
        assertEquals(55.0, made.profile.rotorTipClearanceM, 0.0)
        assertEquals("custom", made.profile.perfSource)
        assertFalse(made.profile.isSystem)
        assertEquals(SyncStatus.PENDING, made.sync)
        assertTrue(made.waiting)
        assertFalse(made.usable)
        assertEquals(listOf(Operation.CREATE), r.device.outbox().map { it.operation })
        assertEquals(1, r.scheduler.requested)
        assertEquals(master.map { it.slug }, r.profiles.profiles.value.map { it.slug })               // not yet something a diagram can name
    }

    @Test
    fun `a draft that is not good makes nothing and does not ask for a sync`() = runTest {
        val r = rig(kept = master)
        assertEquals("Name and designation are required.", r.profiles.create(night.copy(designation = " ")))
        assertEquals("Rotor diameter must be between 1 and 60.", r.profiles.create(night.copy(rotorDiameterM = "61")))
        advanceUntilIdle()
        assertEquals(emptyList<AircraftEntry>(), r.ownEntries())
        assertEquals(emptyList<Operation>(), r.device.outbox().map { it.operation })
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `once the server has named a profile made here it can be chosen`() = runTest {
        val r = rig(kept = master)
        r.profiles.create(night)
        r.device.sync()
        advanceUntilIdle()
        val uuid = r.ownEntries().single().uuid!!
        assertEquals(SyncStatus.SYNCED, r.ownEntries().single().sync)
        assertFalse(r.profiles.select("night-hawk"))                                                    // the server has not named it yet
        // The server names it from the designation and the next pull brings the name back.
        val sent = r.device.record(RecordKind.AIRCRAFT, uuid)!!
        r.server.editElsewhere(RecordKind.AIRCRAFT, uuid, data = JsonObject(sent.data + ("slug" to JsonPrimitive("night-hawk"))))
        r.device.sync()
        advanceUntilIdle()
        assertTrue(r.profiles.select("night-hawk"))
        advanceUntilIdle()
        assertEquals("Night Hawk", r.profiles.active.value.name)
        assertEquals(16.4, r.profiles.active.value.rotorDiameterM, 0.0)
    }

    @Test
    fun `changing a profile saves the new numbers, keeps what the form does not show, and asks for a sync`() = runTest {
        val r = rig(kept = master)
        r.own("Alpha Heli", "alpha")
        advanceUntilIdle()
        val entry = r.ownEntries().single()
        val before = r.scheduler.requested
        assertEquals(null, r.profiles.update(entry.uuid!!, AircraftDraft.of(entry.profile).copy(name = "Alpha II", rotorDiameterM = "14.5")))
        advanceUntilIdle()
        val changed = r.ownEntries().single()
        assertEquals("Alpha II", changed.profile.name)
        assertEquals(14.5, changed.profile.rotorDiameterM, 0.0)
        assertEquals("alpha", changed.profile.slug)                                                     // the server's, kept
        assertEquals(SyncStatus.PENDING, changed.sync)
        assertTrue(changed.usable)
        assertEquals(listOf(Operation.UPDATE), r.device.outbox().map { it.operation })
        assertEquals(before + 1, r.scheduler.requested)
    }

    @Test
    fun `a profile that has gone cannot be changed, and says so`() = runTest {
        val r = rig(kept = master)
        assertEquals("That aircraft is no longer here.", r.profiles.update("nowhere", night))
        assertEquals(0, r.scheduler.requested)
    }

    @Test
    fun `a change the form refuses is not saved`() = runTest {
        val r = rig(kept = master)
        r.own("Alpha Heli", "alpha")
        advanceUntilIdle()
        val entry = r.ownEntries().single()
        assertEquals("Cruise airspeed must be between 1 and 400.", r.profiles.update(entry.uuid!!, AircraftDraft.of(entry.profile).copy(defaultAirspeedKts = "0")))
        advanceUntilIdle()
        assertEquals(entry, r.ownEntries().single())
        assertEquals(emptyList<Operation>(), r.device.outbox().map { it.operation })
    }

    @Test
    fun `deleting a profile removes it, asks for a sync, and the mission aircraft falls back if it was that one`() = runTest {
        val r = rig(kept = master)
        r.own("Alpha Heli", "alpha")
        advanceUntilIdle()
        assertTrue(r.profiles.select("alpha"))
        advanceUntilIdle()
        assertEquals("alpha", r.profiles.active.value.slug)
        val before = r.scheduler.requested
        r.profiles.delete(r.ownEntries().single().uuid!!)
        advanceUntilIdle()
        assertEquals(emptyList<AircraftEntry>(), r.ownEntries())
        assertEquals("uh60l", r.profiles.active.value.slug)
        assertEquals(listOf(Operation.DELETE), r.device.outbox().map { it.operation })
        assertEquals(before + 1, r.scheduler.requested)
    }

    @Test
    fun `a profile the server never saw leaves nothing to send once it is deleted`() = runTest {
        val r = rig(kept = master)
        r.profiles.create(night)
        advanceUntilIdle()
        r.profiles.delete(r.ownEntries().single().uuid!!)
        advanceUntilIdle()
        assertEquals(emptyList<AircraftEntry>(), r.ownEntries())
        assertEquals(emptyList<Operation>(), r.device.outbox().map { it.operation })
    }

    @Test
    fun `the user's version kept beside a conflict is listed so it can be settled, and is never chosen`() = runTest {
        val r = rig(kept = master)
        r.own("Alpha Heli", "alpha")
        advanceUntilIdle()
        val entry = r.ownEntries().single()
        val uuid = entry.uuid!!
        r.server.editElsewhere(RecordKind.AIRCRAFT, uuid, name = "Alpha (changed elsewhere)")
        r.profiles.update(uuid, AircraftDraft.of(entry.profile).copy(name = "Alpha (mine)", rotorDiameterM = "13"))
        r.device.sync()
        advanceUntilIdle()
        val own = r.ownEntries()
        assertEquals(2, own.size)
        val copy = own.single { it.conflictOf != null }
        assertEquals(uuid, copy.conflictOf)
        assertEquals(SyncStatus.CONFLICT, copy.sync)
        assertEquals(13.0, copy.profile.rotorDiameterM, 0.0)
        assertFalse(copy.usable)
        assertFalse(copy.waiting)                                                                       // it is not waiting for the server: it is waiting for a person
        assertEquals(1, r.profiles.profiles.value.count { it.slug == "alpha" })
        assertFalse(own.single { it.conflictOf == null }.waiting)
    }

    @Test
    fun `a copy kept beside a conflict is never waiting for the server, whether or not it carries a slug`() {
        val unnamed = AircraftProfile(slug = "", isSystem = false)
        assertTrue(AircraftEntry(unnamed, own = true, uuid = "a", sync = SyncStatus.PENDING).waiting)
        assertFalse(AircraftEntry(unnamed, own = true, uuid = "b", sync = SyncStatus.CONFLICT, conflictOf = "a").waiting)
        assertFalse(AircraftEntry(unnamed, own = true, uuid = "b", sync = SyncStatus.CONFLICT, conflictOf = "a").usable)
        assertFalse(AircraftEntry(AircraftProfile(slug = "alpha"), own = true, uuid = "c", sync = SyncStatus.SYNCED).waiting)
        assertFalse(AircraftEntry(unnamed, own = false, uuid = null, sync = null).waiting)         // the admin's list has no waiting
    }

    // -- The chosen airframe -------------------------------------------------------------------------------------------

    @Test
    fun `the choice is remembered, and the mission aircraft follows it`() = runTest {
        val r = rig(kept = master)
        assertEquals("uh60l", r.profiles.active.value.slug)                                // nothing chosen: the UH-60L, as the web
        assertTrue(r.profiles.select("ch47f"))
        advanceUntilIdle()
        assertEquals("ch47f", r.profiles.active.value.slug)
        assertEquals("ch47f", r.choice.slug)
        assertEquals(75.0, r.profiles.active.value.rotorTipClearanceM, 0.0)
    }

    @Test
    fun `a choice remembered from an earlier launch is the mission aircraft once the list is there`() = runTest {
        val r = rig(kept = master, chosen = "mh6")
        assertEquals("mh6", r.profiles.active.value.slug)
    }

    @Test
    fun `an airframe nobody knows cannot be chosen, and nothing changes`() = runTest {
        val r = rig(kept = master, chosen = "mh6")
        assertFalse(r.profiles.select("nonesuch"))
        assertEquals("mh6", r.choice.slug)
        assertEquals("mh6", r.profiles.active.value.slug)
    }

    @Test
    fun `when the chosen airframe has gone the UH-60L takes over, and comes back when it does`() = runTest {
        val r = rig(kept = master, chosen = "ch47f", fetch = { listOf(uh60, mh6) })
        assertEquals("ch47f", r.profiles.active.value.slug)
        r.profiles.refresh()
        advanceUntilIdle()
        assertEquals("uh60l", r.profiles.active.value.slug)
        assertEquals("ch47f", r.choice.slug)                                                // the person's choice is not rewritten behind their back
        r.fetch = { master }
        r.profiles.refresh()
        advanceUntilIdle()
        assertEquals("ch47f", r.profiles.active.value.slug)
    }

    @Test
    fun `with no UH-60L in the list the built-in one stands in`() = runTest {
        val r = rig(kept = listOf(ch47, mh6))
        assertEquals(AircraftProfile.FALLBACK, r.profiles.active.value)
    }

    // -- The file ------------------------------------------------------------------------------------------------------

    @Test
    fun `the master list is kept in a file and read back whole`() {
        val file = File(folder.newFolder(), "aircraft/master.json")
        val store = FileMasterProfileStore(file)
        assertEquals(emptyList<AircraftProfile>(), store.read())                            // nothing kept yet
        store.write(master)
        assertEquals(master, FileMasterProfileStore(file).read())
        assertFalse(File(file.parentFile, "master.json.tmp").exists())
        store.write(listOf(mh6))
        assertEquals(listOf(mh6), store.read())
    }

    @Test
    fun `a file that cannot be read is no list`() {
        val file = File(folder.newFolder(), "master.json")
        file.writeText("{ this is not json")
        assertEquals(emptyList<AircraftProfile>(), FileMasterProfileStore(file).read())
        file.writeText("[]")
        assertEquals(emptyList<AircraftProfile>(), FileMasterProfileStore(file).read())
    }

    @Test
    fun `a field a newer server adds to a profile does not stop the list being read`() {
        val file = File(folder.newFolder(), "master.json")
        file.writeText("""[{"slug":"x","name":"X","designation":"X","future_field":{"a":1}}]""")
        val read = FileMasterProfileStore(file).read()
        assertEquals("x", read.single().slug)
    }
}
