package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.network.ThreatMaskDto
import app.ezpztac.network.ThreatMaskRadarDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a threat's terrain mask does and does not do on its own: it is asked for by a press, once, and it goes with the threat. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreatMasksTest {
    private class Vault : ThreatVault {
        override fun load(): ThreatPicture? = null
        override fun save(picture: ThreatPicture) {}
        override fun wipe() {}
    }

    private class Api : ThreatMaskApi {
        val asked = mutableListOf<Pair<LatLon, List<Radar>>>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto {
            asked += at to radars
            gate?.await()
            return ThreatMaskDto(listOf(listOf(34.0, -85.0), listOf(35.0, -84.0)), listOf(ThreatMaskRadarDto(0, "data:image/png;base64,AAAA")))
        }
    }

    private class Rig(scope: TestScope) {
        val store = ThreatStore(Vault(), CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler)) { 1_000L }
        val api = Api()
        val masks = ThreatMasks(api, store, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
    }

    private fun threat() = Threat("SA-8", "SHGPEWRR------", 34.75, -84.05, "name that stays", "SOF", radars = Radars.defaultPair())

    @Test
    fun `a mask is for the place and the radars it was asked for`() = runTest {
        val r = Rig(this)
        val id = r.store.add(threat()); advanceUntilIdle()
        r.masks.request(id); advanceUntilIdle()
        val mask = (r.masks.states.value.getValue(id) as MaskState.Ready).mask
        assertTrue(mask.isFor(threat()))
        assertTrue(mask.isFor(threat().copy(name = "renamed", information = "other notes")))              // what is not sent does not make it out of date
        assertFalse(mask.isFor(threat().copy(lat = 34.76)))
        assertFalse(mask.isFor(threat().copy(radars = Radars.defaultPair().map { it.copy(rangeNmi = it.rangeNmi + 1) })))
    }

    @Test
    fun `only the position and the radars are sent`() = runTest {
        val r = Rig(this)
        val id = r.store.add(threat()); advanceUntilIdle()
        r.masks.request(id); advanceUntilIdle()
        val (at, radars) = r.api.asked.single()
        assertEquals(LatLon(34.75, -84.05), at)
        assertEquals(threat().radars, radars)
    }

    @Test
    fun `a second press while the server is working does not ask again`() = runTest {
        val r = Rig(this)
        r.api.gate = CompletableDeferred()
        val id = r.store.add(threat()); advanceUntilIdle()
        val first = launch { r.masks.request(id) }
        advanceUntilIdle()
        assertEquals(MaskState.Working, r.masks.states.value[id])
        r.masks.request(id)
        assertEquals(1, r.api.asked.size)
        r.api.gate!!.complete(Unit); advanceUntilIdle()
        first.join()
        assertTrue(r.masks.states.value[id] is MaskState.Ready)
    }

    @Test
    fun `a threat taken away while the server works has no mask to keep`() = runTest {
        val r = Rig(this)
        r.api.gate = CompletableDeferred()
        val id = r.store.add(threat()); advanceUntilIdle()
        val asking = launch { r.masks.request(id) }
        advanceUntilIdle()
        r.store.remove(id); advanceUntilIdle()
        r.api.gate!!.complete(Unit); advanceUntilIdle()
        asking.join()
        assertNull(r.masks.states.value[id])
    }

    @Test
    fun `wiping the picture takes every mask with it`() = runTest {
        val r = Rig(this)
        val a = r.store.add(threat())
        val b = r.store.add(threat().copy(name = "two"))
        advanceUntilIdle()
        r.masks.request(a); r.masks.request(b); advanceUntilIdle()
        assertEquals(2, r.masks.states.value.size)
        r.store.wipe(); advanceUntilIdle()
        assertTrue(r.masks.states.value.isEmpty())
    }

    @Test
    fun `a request for a threat that is not there asks for nothing`() = runTest {
        val r = Rig(this)
        r.masks.request("nobody"); advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertTrue(r.masks.states.value.isEmpty())
    }

    @Test
    fun `a bounds answer that is not a box is a failure, not a crash`() = runTest {
        val r = Rig(this)
        val odd = object : ThreatMaskApi {
            override suspend fun mask(at: LatLon, radars: List<Radar>) = ThreatMaskDto(emptyList(), emptyList())
        }
        val masks = ThreatMasks(odd, r.store, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val id = r.store.add(threat()); advanceUntilIdle()
        masks.request(id); advanceUntilIdle()
        assertEquals(MaskState.Failed(ThreatMasks.COULD_NOT), masks.states.value[id])
    }
}
