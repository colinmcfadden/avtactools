package app.ezpztac.data

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramTarget
import app.ezpztac.sync.Device
import app.ezpztac.sync.FakeServer
import app.ezpztac.sync.InMemorySyncStore
import app.ezpztac.sync.RecordingScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The open LZ/PZs the map draws dimmed beside the one being worked on: the person's own open ones in the Library, the pack's while a pack is open, never
 * one the person hid, each read again only when it changed.
 */
class OtherDiagramsTest {
    private class Rig {
        val device = Device("A", FakeServer())
        val repository = DiagramRepository(device.repository, device.store as InMemorySyncStore, RecordingScheduler())
        val openSet = OpenDiagrams(InMemoryOpenDiagramStore())
        val onMap = DiagramsOnMap(InMemoryHiddenDiagramStore())
        val others = OtherDiagrams(repository, openSet, onMap, Dispatchers.Unconfined)
        val active = MutableStateFlow<String?>(null)
        val pack = MutableStateFlow<PackState?>(null)

        /** A new LZ/PZ called [name], opened: the open set has it, as the panel puts it there. */
        suspend fun made(name: String, lat: Double = 34.78): String =
            repository.create(DiagramTarget(lat, -84.08, "16S GD 66993 52949"), name).id.also(openSet::add)
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig().block() }

    private fun lzItem(uuid: String, name: String, lat: Double = 34.7) = PackItemView(
        uuid, "lz", name,
        buildJsonObject {
            put("status", "targeted")
            putJsonObject("target") {
                put("lat", lat)
                put("lon", -84.1)
                put("mgrs", "16S GD 00000 00000")
            }
        },
    )

    /** What is drawn once it has come to [names] (each change on the way to it may be told on its own). */
    private suspend fun ReceiveTurbine<List<Diagram>>.until(vararg names: String): List<Diagram> {
        while (true) {
            val drawn = awaitItem()
            if (drawn.map { it.name } == names.toList()) return drawn
        }
    }

    private fun packOf(vararg items: PackItemView) = PackState("p", PackStatus.LIVE, null, items.toList(), emptyList(), null)

    @Test
    fun `the open ones but the one being worked on, in the order they were opened`() = rig {
        val hawk = made("LZ HAWK")
        val dove = made("LZ DOVE")
        val crow = made("LZ CROW")
        active.value = dove
        others.observe(active, pack).test {
            until("LZ HAWK", "LZ CROW")
            // Another opened: the one left is drawn, the one opened is not.
            active.value = hawk
            until("LZ DOVE", "LZ CROW")
            assertEquals(crow, openSet.ids.value.last())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one hidden is not drawn, and is again once shown`() = rig {
        val hawk = made("LZ HAWK")
        made("LZ DOVE")
        others.observe(active, pack).test {
            until("LZ HAWK", "LZ DOVE")
            onMap.toggle(hawk)
            until("LZ DOVE")
            onMap.toggle(hawk)
            until("LZ HAWK", "LZ DOVE")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a change to one is drawn, and the others are not read again for it`() = rig {
        val hawk = made("LZ HAWK")
        val dove = made("LZ DOVE")
        others.observe(active, pack).test {
            val before = until("LZ HAWK", "LZ DOVE")
            repository.rename(dove, "LZ PIGEON")                                               // a sync, or the Library, changes its record
            val after = until("LZ HAWK", "LZ PIGEON")
            assertSame("its record did not change: the same document", before[0], after[0])
            assertNotSame(before[1], after[1])
            assertEquals(hawk, after[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one closed or deleted is no longer drawn`() = rig {
        val hawk = made("LZ HAWK")
        val dove = made("LZ DOVE")
        others.observe(active, pack).test {
            until("LZ HAWK", "LZ DOVE")
            openSet.remove(hawk)
            until("LZ DOVE")
            repository.delete(dove)
            until()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `in a pack its other LZ PZs are drawn, the person's own set aside until it is left`() = rig {
        made("LZ HAWK")
        val one = lzItem("one", "LZ/PZ 1")
        val two = lzItem("two", "LZ/PZ 2")
        val routes = PackItemView("r", "route", "ROUTES 1", JsonObject(emptyMap()))
        others.observe(active, pack).test {
            until("LZ HAWK")
            active.value = "pack:p:two"
            pack.value = packOf(one, routes, two)
            val drawn = until("LZ/PZ 1")
            assertEquals("pack:p:one", drawn.single().id)
            // Someone pointing changes the pack's state, not its items: nothing is read again.
            pack.value = packOf(one, routes, two).copy(people = listOf(JsonObject(emptyMap())))
            active.value = null
            val both = until("LZ/PZ 1", "LZ/PZ 2")
            assertSame(drawn.single(), both.first())
            // Hidden by its own id, and renamed by someone else: read again with its new name.
            onMap.toggle("pack:p:two")
            pack.value = packOf(one.copy(name = "LZ EAGLE"), routes, two)
            until("LZ EAGLE")
            // The pack left: the person's own come back.
            pack.value = null
            until("LZ HAWK")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
