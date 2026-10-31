package app.ezpztac.data

import app.cash.turbine.test
import app.ezpztac.planning.RouteColors
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.RecordingScheduler
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sets as the map draws them and a route point's name is looked up in them: every set, each with its own colour, shown or not. */
class LocalPointsTest {
    private class Memory : PointSetViewStore {
        override fun load() = emptyMap<String, PointSetView>()

        override fun save(views: Map<String, PointSetView>) {}
    }

    private class Rig {
        val device = Device("A", FakeServer())
        val repository = PointSetRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val views = PointSetViews(Memory())
        val local = LocalPoints(repository, views)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private val lps = Fixtures.bytes("sqlite/local-points.lps")

    @Test
    fun `no sets, nothing on the map`() = rig {
        assertTrue(local.sets.first().isEmpty())
    }

    @Test
    fun `each set is shown in a colour of its own, in the order the device holds them`() = rig {
        repository.import(lps, "ONE.lps")
        repository.import(lps, "TWO.lps")
        repository.import(lps, "THREE.lps")
        val sets = local.sets.first()
        assertEquals(listOf("ONE", "TWO", "THREE"), sets.map { it.set.name })
        assertEquals(RouteColors.PALETTE.take(3), sets.map { it.color })
        assertTrue(sets.all { it.visible })
        assertEquals(9, sets.first().set.points.size)
    }

    @Test
    fun `collecting them settles each set's view, so a colour stays when another set goes`() = rig {
        repository.import(lps, "ONE.lps")
        repository.import(lps, "TWO.lps")
        local.sets.first()
        assertEquals(2, views.views.value.size)

        val first = repository.observe().first().first()
        repository.delete(first.uuid)
        val remaining = local.sets.first().single()
        assertEquals("TWO", remaining.set.name)
        assertEquals(RouteColors.PALETTE[1], remaining.color)                                  // not shifted down to the first colour
        assertEquals(setOf(remaining.set.id), views.views.value.keys)                          // and the deleted set's view is gone
    }

    @Test
    fun `hiding a set hides it from the map but it is still one of the sets`() = rig {
        repository.import(lps, "ONE.lps")
        local.sets.test {
            val set = awaitItem().single()
            views.toggle(set.set.id)
            val hidden = expectMostRecentItem().single()
            assertFalse(hidden.visible)
            assertEquals(9, hidden.set.points.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a conflict copy is marked as one`() = rig {
        val set = (repository.import(lps, "ONE.lps") as ImportOutcome.Imported).set
        val original = device.record(RecordKind.POINT_SET, set.id)!!                          // read before the transaction: its lock is not re-entrant
        device.store.transaction { put(original.copy(uuid = "copy-1", conflictOf = set.id, name = "ONE (copy)")) }
        val sets = local.sets.first()
        assertEquals(set.id, sets.single { it.set.id == "copy-1" }.conflictOf)
        assertNull(sets.single { it.set.id == set.id }.conflictOf)
    }
}
