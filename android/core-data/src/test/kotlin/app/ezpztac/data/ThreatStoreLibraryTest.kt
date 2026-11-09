package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import app.ezpztac.model.ThreatSets
import app.ezpztac.network.ThreatMaskDto
import app.ezpztac.network.ThreatMaskRadarDto
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.Operation
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.sync.SyncStore
import app.ezpztac.sync.SyncTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threats kept in the Library (`AGENTS.md` §2, the owner's decisions of 2026-10-12 and 2026-11-07): saved by themselves a moment after a change, there again after a restart and a
 * sign-out, one named set per Library item with several open at once, a device's old picture carried over, and what another device changed laid over this one's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreatStoreLibraryTest {
    private class MemoryVault(var kept: ThreatPicture? = null) : ThreatVault {
        var failWipe = false
        override fun load(): ThreatPicture? = kept
        override fun save(picture: ThreatPicture) { kept = picture }
        override fun wipe() {
            if (failWipe) error("cannot delete")
            kept = null
        }
    }

    /** A store whose writes can be made to fail: the disk full, or the database gone wrong. */
    private class Flaky(private val real: InMemorySyncStore) : SyncStore, RecordFeed by real {
        var failing = false
        override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T {
            if (failing) error("the disk is full")
            return real.transaction(block)
        }
    }

    private inner class Rig(val scope: TestScope, server: FakeServer = FakeServer(), label: String = "A", val vault: MemoryVault = MemoryVault()) {
        val flaky = Flaky(InMemorySyncStore())
        val device = Device(label, server, store = flaky)
        val scheduler = RecordingScheduler()
        val repo = ThreatSetRepository(device.repository, flaky, scheduler)
        val open = OpenThreatSets(InMemoryOpenThreatSetStore())
        var store = newStore()

        fun newStore() = ThreatStore(
            vault, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler), repo, open,
        ) { now }

        /** The app ended and started again: memory is gone, and what is on the device is not. */
        fun restarted(): ThreatStore = newStore().also { store = it }

        suspend fun record(id: String) = repo.record(id)

        val entries: List<ThreatEntry> get() = store.entries.value
        val set get() = store.sets.value.single()
    }

    private var now = 1_000_000L

    private fun threat(name: String = "SA-6", lat: Double = 34.5, lon: Double = -84.5) = Threat(name, "SHGPEWRR------", lat, lon, "", "SOF", radars = Radars.defaultPair())

    private fun TestScope.rig(server: FakeServer = FakeServer(), label: String = "A", vault: MemoryVault = MemoryVault()) = Rig(this, server, label, vault)

    private suspend fun TestScope.attached(r: Rig, saves: Boolean = true) {
        r.store.attach("7", saves)
        advanceUntilIdle()
    }

    private fun threatsIn(document: JsonObject): List<JsonObject> = document.getValue("threats").jsonArray.map { it.jsonObject }

    // -- Saved as the person works ---------------------------------------------------------------------------------------------

    @Test
    fun `a change is saved by itself after the pause, and the set says how it stands`() = runTest {
        val r = rig()
        attached(r)
        r.store.add(threat("SA-8"))
        assertEquals(ThreatSetStanding.SAVING, r.set.standing)

        advanceTimeBy(ThreatStore.SAVE_PAUSE_MS - 1)
        assertTrue(r.repo.records().isEmpty())                                                    // still waiting for the next change

        advanceUntilIdle()
        assertEquals(ThreatSetStanding.SAVED, r.set.standing)
        val saved = r.repo.records().single()
        assertEquals(ThreatSets.FIRST_SET_NAME, saved.name)
        assertEquals(r.set.id, saved.uuid)
        assertEquals(listOf(r.set.id), r.open.ids.value)
        assertEquals(1, threatsIn(saved.data).size)
        assertTrue(r.scheduler.requested > 0)                                                     // and synced
    }

    @Test
    fun `many changes in one pause are one write`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        repeat(50) { r.store.move(id, 34.5 + it * 0.001, -84.5); advanceTimeBy(10) }
        advanceUntilIdle()
        assertEquals(Operation.CREATE, r.device.outbox().single().operation)
        assertEquals(34.5 + 49 * 0.001, r.entries.single().threat.lat, 1e-9)
    }

    @Test
    fun `a dragged threat is written once the finger lifts, not while it moves`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        advanceUntilIdle()
        val before = r.repo.records().single().localVersion
        r.store.previewMove(id, 35.0, -85.0)
        advanceUntilIdle()
        assertEquals(before, r.repo.records().single().localVersion)

        r.store.settle()
        advanceUntilIdle()
        assertNotEquals(before, r.repo.records().single().localVersion)
        assertEquals(35.0, threatsIn(r.repo.records().single().data).single().getValue("lat").toString().toDouble(), 1e-9)
    }

    @Test
    fun `a set made and emptied before it was saved leaves nothing in the Library`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        r.store.remove(id)
        advanceUntilIdle()
        assertTrue(r.repo.records().isEmpty())
        assertEquals(ThreatSetStanding.SAVED, r.set.standing)
    }

    @Test
    fun `a set emptied once saved is saved empty, and stays in the Library`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        advanceUntilIdle()
        r.store.remove(id)
        advanceUntilIdle()
        assertTrue(threatsIn(r.repo.records().single().data).isEmpty())
    }

    @Test
    fun `a change that cannot be written is said, kept, and written with the next one`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        advanceUntilIdle()

        r.flaky.failing = true
        r.store.move(id, 35.0, -85.0)
        advanceUntilIdle()
        assertEquals(ThreatSetStanding.NOT_SAVED, r.set.standing)
        assertEquals(35.0, r.entries.single().threat.lat, 1e-9)                                   // nothing is taken from the person for it

        r.flaky.failing = false
        r.store.setVisible(id, false)
        advanceUntilIdle()
        assertEquals(ThreatSetStanding.SAVED, r.set.standing)
        assertEquals(35.0, threatsIn(r.repo.records().single().data).single().getValue("lat").toString().toDouble(), 1e-9)
    }

    @Test
    fun `flush writes what is waiting now, without waiting for the pause`() = runTest {
        val r = rig()
        attached(r)
        r.store.add(threat())
        r.store.flush()
        assertEquals(1, r.repo.records().size)
    }

    // -- After a restart, and across a sign-out ----------------------------------------------------------------------------------

    @Test
    fun `the open sets come back after the app is ended, ids and all`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat("SA-8"))
        advanceUntilIdle()

        r.restarted()
        attached(r)
        assertEquals(listOf(id), r.entries.map { it.id })
        assertEquals("SA-8", r.entries.single().threat.name)
        assertEquals(ThreatSetStanding.SAVED, r.set.standing)
    }

    @Test
    fun `signing out saves what was changed, empties the map, and signing back in brings it all back`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat("SA-8"))
        val wipesBefore = r.store.wipes.value

        r.store.signOut()                                                                         // inside the pause: nothing may be lost to it
        assertTrue(r.entries.isEmpty())
        assertTrue(r.store.sets.value.isEmpty())
        assertTrue(r.store.wipes.value > wipesBefore)
        assertEquals(1, r.repo.records().size)
        assertEquals(1, r.open.ids.value.size)                                                    // the same person comes back to what they had open

        attached(r)
        assertEquals(listOf(id), r.entries.map { it.id })
    }

    @Test
    fun `a set closed stays closed after a restart, and stays in the Library`() = runTest {
        val r = rig()
        attached(r)
        r.store.add(threat())
        advanceUntilIdle()
        assertTrue(r.store.close(r.set.id))
        assertTrue(r.entries.isEmpty())

        r.restarted()
        attached(r)
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.repo.records().size)
    }

    // -- A picture from before threats were saved ---------------------------------------------------------------------------------

    private fun picture() = ThreatPicture(5_000L, listOf(ThreatEntry("t-1", threat("A")), ThreatEntry("t-2", threat("B"), visible = false)))

    @Test
    fun `a device's picture becomes one set in the account's Library, and the sealed file goes`() = runTest {
        val r = rig(vault = MemoryVault(picture()))
        assertEquals(2, r.entries.size)                                                           // shown before anyone has signed in, as it always was

        attached(r)
        val saved = r.repo.records().single()
        assertEquals(ThreatSets.FIRST_SET_NAME, saved.name)
        assertEquals(listOf(saved.uuid), r.open.ids.value)
        assertNull(r.vault.kept)
        assertEquals(listOf("t-1", "t-2"), r.entries.map { it.id })
        assertFalse(r.entries[1].visible)
        assertEquals(RecordKind.THREAT_SET, r.device.outbox().single().kind)                      // on its way to the server
    }

    @Test
    fun `a carry-over the app was ended in the middle of makes no second set`() = runTest {
        val r = rig(vault = MemoryVault(picture()))
        r.vault.failWipe = true                                                                   // the file outlives the set made from it, as a crash between the two would leave it
        attached(r)
        assertNotNull(r.vault.kept)

        r.restarted()
        attached(r)
        assertEquals(1, r.repo.records().size)
        assertEquals(1, r.open.ids.value.size)
    }

    @Test
    fun `carrying over takes a free name when the Library has a set of that name already`() = runTest {
        val r = rig(vault = MemoryVault(picture()))
        r.repo.create("old", ThreatSets.FIRST_SET_NAME, ThreatSets.write(app.ezpztac.model.ThreatSetDocument(emptyList())))
        attached(r)
        assertEquals(setOf("THREATS", "THREATS 2"), r.repo.records().map { it.name }.toSet())
    }

    // -- An account that cannot save them -------------------------------------------------------------------------------------------

    @Test
    fun `an account that cannot save threats keeps them on the device, makes no record, and loses them at sign-out`() = runTest {
        val r = rig()
        attached(r, saves = false)
        assertFalse(r.store.saves.value)
        r.store.add(threat())
        advanceUntilIdle()
        assertTrue(r.repo.records().isEmpty())
        assertTrue(r.store.sets.value.isEmpty())
        assertEquals(1, r.vault.kept!!.entries.size)

        r.store.signOut()
        assertNull(r.vault.kept)
        assertTrue(r.entries.isEmpty())
    }

    @Test
    fun `saving turned off takes the sets off the map and leaves them in the Library`() = runTest {
        val r = rig()
        attached(r)
        assertTrue(r.store.saves.value)
        r.store.add(threat())
        advanceUntilIdle()

        attached(r, saves = false)
        assertFalse(r.store.saves.value)
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.repo.records().size)
    }

    // -- Masks -------------------------------------------------------------------------------------------------------------------------

    private class MaskApi : ThreatMaskApi {
        val asked = mutableListOf<LatLon>()
        override suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto {
            asked += at
            return ThreatMaskDto(listOf(listOf(34.0, -85.0), listOf(35.0, -84.0)), listOf(ThreatMaskRadarDto(0, "data:image/png;base64,AAAA")))
        }
    }

    private fun TestScope.masks(r: Rig, api: ThreatMaskApi = MaskApi()) = ThreatMasks(api, r.store, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    @Test
    fun `a mask is saved with its threat and comes back with it, without asking the server`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        val masks = masks(r)
        advanceUntilIdle()
        masks.request(id)
        advanceUntilIdle()
        assertNotNull(threatsIn(r.repo.records().single().data).single()["mask"])

        r.restarted()
        val api = MaskApi()
        val after = masks(r, api)
        attached(r)
        val mask = (after.states.value.getValue(id) as MaskState.Ready).mask
        assertTrue(mask.isFor(r.entries.single().threat))
        assertEquals("data:image/png;base64,AAAA", mask.images.single().dataUri)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a mask the threat has moved away from is not written, and hiding one takes it out of the set`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat())
        val masks = masks(r)
        advanceUntilIdle()
        masks.request(id)
        advanceUntilIdle()

        masks.hide(id)
        advanceUntilIdle()
        assertNull(threatsIn(r.repo.records().single().data).single()["mask"])

        masks.request(id)
        advanceUntilIdle()
        r.store.move(id, 35.0, -85.0)
        advanceUntilIdle()
        assertNull(threatsIn(r.repo.records().single().data).single()["mask"])
    }

    // -- The sets ------------------------------------------------------------------------------------------------------------------------

    private suspend fun TestScope.saved(r: Rig, name: String, vararg threats: Threat): String {
        r.store.addAsSet(threats.toList(), name)
        advanceUntilIdle()
        return r.store.sets.value.last().id
    }

    @Test
    fun `a file comes in as a set of its own, and several are open and drawn at once`() = runTest {
        val r = rig()
        attached(r)
        val a = saved(r, "north ga", threat("A"))
        val b = saved(r, "SOUTH", threat("B"), threat("C"))
        assertEquals(listOf("NORTH GA", "SOUTH"), r.store.sets.value.map { it.name })
        assertEquals(listOf("A", "B", "C"), r.entries.map { it.threat.name })
        assertEquals(setOf(a, b), r.repo.records().map { it.uuid }.toSet())
    }

    @Test
    fun `new threats go into the set chosen, else the last opened`() = runTest {
        val r = rig()
        attached(r)
        val a = saved(r, "ONE", threat("A"))
        val b = saved(r, "TWO", threat("B"))
        assertEquals(b, r.store.activeSet.value)

        r.store.setActive(a)
        r.store.add(threat("C"))
        advanceUntilIdle()
        assertEquals(2, threatsIn(r.record(a)!!.data).size)
        assertEquals(1, threatsIn(r.record(b)!!.data).size)
    }

    @Test
    fun `a set is renamed in capitals, open or closed, and a blank name is refused`() = runTest {
        val r = rig()
        attached(r)
        val id = saved(r, "ONE", threat())
        assertFalse(r.store.rename(id, "   "))
        assertTrue(r.store.rename(id, "kilo"))
        assertEquals("KILO", r.set.name)
        advanceUntilIdle()
        assertEquals("KILO", r.record(id)!!.name)

        r.store.close(id)
        assertFalse(r.store.renameSaved(id, ""))
        assertTrue(r.store.renameSaved(id, "lima"))
        assertEquals("LIMA", r.record(id)!!.name)
        assertFalse(r.store.renameSaved("no-such-set", "MIKE"))
    }

    @Test
    fun `a new set takes a free name, and the screen is told what it got`() = runTest {
        val r = rig()
        attached(r)
        saved(r, "ONE", threat("A"))
        saved(r, "ONE", threat("B"))
        assertEquals(listOf("ONE", "ONE 2"), r.store.sets.value.map { it.name })
        assertEquals(setOf("ONE", "ONE 2"), r.repo.records().map { it.name }.toSet())
    }

    @Test
    fun `a set that cannot be saved will not close, and a set that can does`() = runTest {
        val r = rig()
        attached(r)
        val id = r.store.add(threat()).let { advanceUntilIdle(); r.set.id }
        r.flaky.failing = true
        r.store.move(r.entries.single().id, 35.0, -85.0)
        assertFalse(r.store.close(id))
        assertEquals(1, r.store.sets.value.size)
        assertEquals(ThreatSetStanding.NOT_SAVED, r.set.standing)

        r.flaky.failing = false
        assertTrue(r.store.close(id))
        assertTrue(r.store.sets.value.isEmpty())
        assertEquals(35.0, threatsIn(r.record(id)!!.data).single().getValue("lat").toString().toDouble(), 1e-9)
    }

    @Test
    fun `a saved set that was emptied and cannot be written will not close`() = runTest {
        val r = rig()
        attached(r)
        val threatId = r.store.add(threat())
        advanceUntilIdle()
        val id = r.set.id
        r.flaky.failing = true
        r.store.remove(threatId)
        assertFalse(r.store.close(id))
    }

    @Test
    fun `opening a saved set puts it beside the others, once`() = runTest {
        val r = rig()
        attached(r)
        val a = saved(r, "ONE", threat("A"))
        r.store.close(a)
        saved(r, "TWO", threat("B"))

        assertTrue(r.store.open(a))
        assertTrue(r.store.open(a))
        assertEquals(listOf("TWO", "ONE"), r.store.sets.value.map { it.name })
        assertEquals(2, r.open.ids.value.size)
        assertFalse(r.store.open("no-such-set"))
    }

    @Test
    fun `no two threats on the map share an id, even in a set and its copy`() = runTest {
        val r = rig()
        attached(r)
        val a = saved(r, "ONE", threat("A"), threat("B"))
        val copy = r.repo.duplicate(a, "ONE 2")!!

        assertTrue(r.store.open(copy.uuid))
        val ids = r.entries.map { it.id }
        assertEquals(4, ids.size)
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `deleting a set takes it off the map, and nothing more of it is saved`() = runTest {
        val r = rig()
        attached(r)
        val id = saved(r, "ONE", threat())
        r.store.add(threat("B"))                                                                  // a save is waiting
        r.store.delete(id)
        advanceUntilIdle()
        assertTrue(r.entries.isEmpty())
        assertTrue(r.open.ids.value.isEmpty())
        assertNull(r.record(id))
        assertTrue(r.repo.records().isEmpty())
    }

    @Test
    fun `remove all empties the open sets and leaves them, and what was being done is let go`() = runTest {
        val r = rig()
        attached(r)
        val id = saved(r, "ONE", threat("A"), threat("B"))
        val wipes = r.store.wipes.value
        r.store.removeAll()
        advanceUntilIdle()
        assertTrue(r.entries.isEmpty())
        assertEquals(listOf("ONE"), r.store.sets.value.map { it.name })
        assertTrue(threatsIn(r.record(id)!!.data).isEmpty())
        assertTrue(r.store.wipes.value > wipes)
    }

    @Test
    fun `what a newer version wrote comes through a change`() = runTest {
        val r = rig()
        attached(r)
        val document = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"version": 9, "future": {"a": 1}, "threats": [
                {"id": "t-1", "name": "A", "milstdId": "SHGPEWRR------", "lat": 34.5, "lon": -84.5, "information": "", "source": "SOF", "radars": [], "newer": true},
                "not a threat"
            ]}""",
        ).jsonObject
        r.repo.create("set-1", "OLD", document)
        assertTrue(r.store.open("set-1"))
        r.store.move("t-1", 35.0, -85.0)
        advanceUntilIdle()

        val written = r.record("set-1")!!.data
        assertEquals(9, written.getValue("version").toString().toInt())
        assertNotNull(written["future"])
        val threats = written.getValue("threats") as JsonArray
        assertEquals(2, threats.size)
        assertEquals(true.toString(), threats[0].jsonObject.getValue("newer").toString())
    }

    // -- Two devices --------------------------------------------------------------------------------------------------------------------

    private suspend fun TestScope.sharedSet(server: FakeServer, a: Rig, b: Rig): String {
        attached(a)
        attached(b)
        a.store.add(threat("A"))
        advanceUntilIdle()
        a.device.sync()
        b.device.sync()
        val id = a.set.id
        assertTrue(b.store.open(id))
        advanceUntilIdle()
        return id
    }

    @Test
    fun `a change another device made shows here, and one not yet saved here is kept with it`() = runTest {
        val server = FakeServer()
        val a = rig(server, "A")
        val b = rig(server, "B")
        val id = sharedSet(server, a, b)
        val shared = b.entries.single().id

        a.store.add(threat("NEW"))
        advanceUntilIdle()
        a.device.sync()

        b.store.move(shared, 35.0, -85.0)                                                         // inside the pause when the other's change comes
        b.device.sync()
        runCurrent()
        assertEquals(setOf("A", "NEW"), b.entries.map { it.threat.name }.toSet())
        assertEquals(35.0, b.entries.first { it.id == shared }.threat.lat, 1e-9)

        advanceUntilIdle()
        b.device.sync()
        a.device.sync()
        advanceUntilIdle()
        assertEquals(35.0, a.entries.first { it.id == shared }.threat.lat, 1e-9)
        assertEquals(2, a.entries.size)
        assertEquals(1, b.repo.records().size)                                                    // laid over, so there was no conflict to keep a copy of
        assertEquals(id, b.set.id)
    }

    @Test
    fun `a change made on both devices before either synced keeps both, side by side and on the map`() = runTest {
        val server = FakeServer()
        val a = rig(server, "A")
        val b = rig(server, "B")
        val id = sharedSet(server, a, b)
        val shared = a.entries.single().id

        a.store.move(shared, 35.0, -85.0)
        b.store.move(shared, 36.0, -86.0)
        advanceUntilIdle()
        a.device.sync()
        b.device.sync()
        advanceUntilIdle()

        val sets = b.store.sets.value
        assertEquals(listOf(id, sets[1].id), sets.map { it.id })
        assertEquals("THREATS (from B, 14:32)", sets[1].name)
        assertEquals(35.0, sets[0].entries.single().threat.lat, 1e-9)                             // the record is the server's
        assertEquals(36.0, sets[1].entries.single().threat.lat, 1e-9)                             // and this device's work has not vanished
        assertEquals(id, b.repo.records().first { it.uuid == sets[1].id }.conflictOf)
    }

    @Test
    fun `a set deleted on another device goes from this one's map`() = runTest {
        val server = FakeServer()
        val a = rig(server, "A")
        val b = rig(server, "B")
        val id = sharedSet(server, a, b)

        a.store.delete(id)
        advanceUntilIdle()
        a.device.sync()
        b.device.sync()
        advanceUntilIdle()
        assertTrue(b.store.sets.value.isEmpty())
        assertTrue(b.open.ids.value.isEmpty())
    }

    @Test
    fun `a set deleted elsewhere while it was being changed here is kept under a new identity`() = runTest {
        val r = rig()
        attached(r)
        val threatId = r.store.add(threat())
        advanceUntilIdle()
        val id = r.set.id

        r.store.move(threatId, 35.0, -85.0)
        r.repo.delete(id)                                                                         // as a pull would
        advanceUntilIdle()

        val kept = r.set.id
        assertNotEquals(id, kept)
        assertEquals(listOf(kept), r.open.ids.value)
        assertEquals(35.0, threatsIn(r.record(kept)!!.data).single().getValue("lat").toString().toDouble(), 1e-9)
    }
}
