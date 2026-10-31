package app.ezpztac.data

import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The threat picture: memory first, one sealed file behind it, kept until sign-out (no expiry since the owner's decision of 2026-10-12). What matters
 * here is what is *not* allowed to happen: a wiped picture coming back from a write that was already queued, and a failing file taking threats away from a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreatStoreTest {
    private class MemoryVault(var kept: ThreatPicture? = null) : ThreatVault {
        var saves = 0
        var wipes = 0
        var failSave = false
        var failLoad = false
        var failWipe = false
        override fun load(): ThreatPicture? {
            if (failLoad) error("the file is damaged")
            return kept
        }
        override fun save(picture: ThreatPicture) {
            if (failSave) error("the disk is full")
            kept = picture
            saves++
        }
        override fun wipe() {
            wipes++
            if (failWipe) error("cannot delete")
            kept = null
        }
    }

    private var now = 1_000_000L

    private fun threat(name: String = "SA-6", lat: Double = 34.5, lon: Double = -84.5) =
        Threat(name, "SHGPEWRR------", lat, lon, "", "SOF", radars = Radars.defaultPair())

    /** On the test's scheduler, not its backgroundScope: `advanceUntilIdle` leaves a background scope's work alone, and a write is work a test waits for. */
    private fun TestScope.store(vault: MemoryVault = MemoryVault()) =
        ThreatStore(vault, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler)) { now }

    // -- Making and changing threats -----------------------------------------------------------------------------------------

    @Test
    fun `a new store holds nothing and keeps nothing`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        advanceUntilIdle()
        assertEquals(emptyList<ThreatEntry>(), store.entries.value)
        assertEquals(0, vault.saves)
    }

    @Test
    fun `a threat is added shown, at the end, and written with the time it was changed`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        val a = store.add(threat("A"))
        now += 5_000
        val b = store.add(threat("B"))
        advanceUntilIdle()
        assertEquals(listOf(a, b), store.entries.value.map { it.id })
        assertEquals(listOf("A", "B"), store.entries.value.map { it.threat.name })
        assertTrue(store.entries.value.all { it.visible })
        assertEquals(1_005_000L, vault.kept!!.savedAtMillis)
        assertEquals(store.entries.value, vault.kept!!.entries)
    }

    @Test
    fun `threats made in the same millisecond are told apart`() = runTest {
        val store = store()
        val ids = (1..5).map { store.add(threat("T$it")) }
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun `an import adds every threat in order, as one change with one write`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        val ids = store.addAll(listOf(threat("A"), threat("B"), threat("C")))
        advanceUntilIdle()
        assertEquals(3, ids.toSet().size)
        assertEquals(listOf("A", "B", "C"), store.entries.value.map { it.threat.name })
        assertEquals(1, vault.saves)
        assertEquals(emptyList<String>(), store.addAll(emptyList()))
        advanceUntilIdle()
        assertEquals(1, vault.saves)                                                          // nothing imported is not a change
    }

    @Test
    fun `replacing a threat keeps its place and whether it is shown`() = runTest {
        val store = store()
        val a = store.add(threat("A")); store.add(threat("B"))
        store.setVisible(a, false)
        assertTrue(store.replace(a, threat("A2")))
        assertEquals(listOf("A2", "B"), store.entries.value.map { it.threat.name })
        assertFalse(store.entries.value.first().visible)
    }

    @Test
    fun `moving changes the position and nothing else`() = runTest {
        val store = store()
        val a = store.add(threat("A"))
        val before = store.entries.value.single()
        assertTrue(store.move(a, 35.0, -85.0))
        val after = store.entries.value.single()
        assertEquals(35.0, after.threat.lat, 0.0)
        assertEquals(-85.0, after.threat.lon, 0.0)
        assertEquals(before.threat.copy(lat = 35.0, lon = -85.0), after.threat)
        assertEquals(before.id, after.id)
    }

    @Test
    fun `removing takes one threat out and the others stay`() = runTest {
        val store = store()
        val a = store.add(threat("A")); val b = store.add(threat("B"))
        assertTrue(store.remove(a))
        assertEquals(listOf(b), store.entries.value.map { it.id })
    }

    @Test
    fun `a threat that is not there is not changed and is not a change`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        store.add(threat())
        advanceUntilIdle()
        val saves = vault.saves
        assertFalse(store.replace("nope", threat("X")))
        assertFalse(store.move("nope", 1.0, 2.0))
        assertFalse(store.setVisible("nope", false))
        assertFalse(store.remove("nope"))
        advanceUntilIdle()
        assertEquals(saves, vault.saves)
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun `taking away the last threat removes the file rather than saving an empty picture`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        val a = store.add(threat())
        advanceUntilIdle()
        assertTrue(vault.kept != null)
        store.remove(a)
        advanceUntilIdle()
        assertNull(vault.kept)
        assertEquals(1, vault.saves)
    }

    // -- How long a picture lasts -------------------------------------------------------------------------------------------

    private fun picture(savedAt: Long) = ThreatPicture(savedAt, listOf(ThreatEntry("threat-1-0", threat("KEPT"))))

    @Test
    fun `a picture from the last launch is there at once, however old it is`() = runTest {
        // The owner's decision of 2026-10-12: threats no longer expire (they are to be saved and shared). A month-old picture is still the crew's.
        val vault = MemoryVault(picture(now - 30L * 24 * 3_600_000L))
        val store = store(vault)
        assertEquals(listOf("KEPT"), store.entries.value.map { it.threat.name })
        assertEquals(0, vault.wipes)
    }

    @Test
    fun `a picture saved in the future, because the clock was set back, is kept rather than guessed at`() = runTest {
        val store = store(MemoryVault(picture(now + 10L * 24 * 3_600_000L)))
        assertEquals(1, store.entries.value.size)
    }

    // -- Wiping --------------------------------------------------------------------------------------------------------------

    @Test
    fun `a wipe forgets the threats in memory and in the file`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        store.add(threat())
        advanceUntilIdle()
        store.wipe()
        assertEquals(emptyList<ThreatEntry>(), store.entries.value)
        assertNull(vault.kept)
    }

    @Test
    fun `a write already queued cannot bring back what a wipe took`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        store.add(threat())                                                                   // the write for this is queued, not yet run
        store.wipe()
        advanceUntilIdle()                                                                    // and now it runs
        assertNull(vault.kept)
        assertEquals(emptyList<ThreatEntry>(), store.entries.value)
    }

    @Test
    fun `threats added after a wipe are kept again`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        store.add(threat("OLD")); advanceUntilIdle()
        store.wipe()
        store.add(threat("NEW")); advanceUntilIdle()
        assertEquals(listOf("NEW"), vault.kept!!.entries.map { it.threat.name })
    }

    @Test
    fun `a file that will not be deleted does not stop the memory being cleared, and does not throw`() = runTest {
        val vault = MemoryVault().apply { failWipe = true }
        val store = store(vault)
        store.add(threat())
        store.wipe()
        assertEquals(emptyList<ThreatEntry>(), store.entries.value)
    }

    // -- A file that fails ---------------------------------------------------------------------------------------------------

    @Test
    fun `a file that cannot be written costs the next launch its picture, not this session its threats`() = runTest {
        val vault = MemoryVault().apply { failSave = true }
        val store = store(vault)
        store.add(threat("A"))
        advanceUntilIdle()                                                                    // the failed write is caught, not thrown
        assertEquals(listOf("A"), store.entries.value.map { it.threat.name })
        vault.failSave = false
        store.add(threat("B")); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vault.kept!!.entries.map { it.threat.name })           // and the next change writes the whole picture again
    }

    @Test
    fun `a file that cannot be read is no picture`() = runTest {
        val store = store(MemoryVault(picture(now)).apply { failLoad = true })
        assertEquals(emptyList<ThreatEntry>(), store.entries.value)
    }

    @Test
    fun `what is written is what is in memory when the write runs, so a burst of changes ends with the last`() = runTest {
        val vault = MemoryVault()
        val store = store(vault)
        val a = store.add(threat("A"))
        store.move(a, 1.0, 2.0)
        store.move(a, 3.0, 4.0)
        advanceUntilIdle()
        assertEquals(3.0, vault.kept!!.entries.single().threat.lat, 0.0)
        assertNotEquals(0, vault.saves)
    }
}
