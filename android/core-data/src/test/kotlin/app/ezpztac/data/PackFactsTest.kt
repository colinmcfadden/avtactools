package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.missionpacks.PendingOp
import app.ezpztac.missionpacks.PendingState
import app.ezpztac.model.DiagramTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the panels say of an open pack and its items (P2b): one rule for each, shared by the Pack, LZ/PZ and Routes panels. */
@OptIn(ExperimentalCoroutinesApi::class)
class PackFactsTest {
    private fun session(team: Boolean = false, audience: Int = 9, status: String = "active", readOnly: Boolean = false, pending: List<PendingOp> = emptyList()) = PackSession(
        1,
        buildJsonObject {
            put("uuid", "p")
            put("name", "OP DK")
            put("status", status)
            put("audience_count", audience)
            if (team) put("team", buildJsonObject { put("id", 3) }) else put("team", JsonNull)
        },
        buildJsonArray {
            add(buildJsonObject { put("user_id", 1); put("name", "Colin McFadden") })
            add(buildJsonObject { put("user_id", 2); put("name", "Sam Bell") })
        },
        emptyMap(), emptyMap(), emptyList(), 10, pending, emptyList(), readOnly, null, false, emptyMap(),
    )

    private fun here(id: Int, name: String, item: String?) = buildJsonObject {
        put("user_id", id)
        put("name", name)
        put("focus", buildJsonObject { item?.let { put("item", it) } })
    }

    private val lz = PackItemView("lz-1", "lz", "LZ HAWK", JsonObject(emptyMap()), buildJsonObject { put("source", buildJsonObject { put("original", "changed") }) })
    private val other = PackItemView("lz-2", "lz", "LZ CROW", JsonObject(emptyMap()), buildJsonObject { put("source", JsonNull) })

    @Test
    fun `who can see a pack is its members, and with a team the team's, never fewer than its members`() {
        assertEquals(2, PackFacts.audience(session(team = false, audience = 9)))
        assertEquals(9, PackFacts.audience(session(team = true, audience = 9)))
        assertEquals(2, PackFacts.audience(session(team = true, audience = 0)))
        assertEquals("Saved to OP DK as you work. 2 members can see it.", PackFacts.savedLine(PackState("p", PackStatus.LIVE, session(), emptyList(), emptyList(), null)))
    }

    @Test
    fun `an item waits while it is made here or a change to it is on its way`() {
        val state = PackState("p", PackStatus.LIVE, session(pending = listOf(PendingOp(buildJsonObject { put("item", "lz-1") }, PendingState.QUEUED))), listOf(lz, other), emptyList(), null)
        assertTrue(PackFacts.waiting(state, lz))
        assertFalse(PackFacts.waiting(state, other))
        assertTrue(PackFacts.waiting(state, other.copy(pendingCreate = true)))
    }

    @Test
    fun `where an item came from, and who else has it open, each once and by a short name`() {
        assertTrue(PackFacts.fromLibrary(lz))
        assertEquals("changed", PackFacts.original(lz))
        assertFalse(PackFacts.fromLibrary(other))                                                  // a source of null is none
        val state = PackState("p", PackStatus.LIVE, session(), listOf(lz), listOf(here(1, "Colin McFadden", "lz-1"), here(2, "Sam Bell", "lz-1"), here(2, "Sam Bell", "lz-1"), here(3, "Jess Reed", "lz-2")), null)
        assertEquals(listOf("Sam B."), PackFacts.withItOpen(state, "lz-1"))                       // not this person, and Sam's two devices once
        assertEquals("Sam B. is also here", PackFacts.alsoHere(listOf("Sam B.")))
        assertEquals("Sam B. and 1 more are also here", PackFacts.alsoHere(listOf("Sam B.", "Jess R.")))         // the web's ActiveCard
        assertEquals("Sam B. and 2 more are also here", PackFacts.alsoHere(listOf("Sam B.", "Jess R.", "Kim L.")))
        assertNull(PackFacts.alsoHere(emptyList()))
        assertEquals("Sam B., Kim L. and Jess R. have it open", PackFacts.hasItOpen(listOf("Sam B.", "Kim L.", "Jess R.")))
    }

    @Test
    fun `how a pack stands, and an item named by its local id only in its own pack`() {
        assertEquals("live", PackFacts.standing(PackState("p", PackStatus.LIVE, session(), emptyList(), emptyList(), null)))
        assertEquals("live · view only", PackFacts.standing(PackState("p", PackStatus.LIVE, session(readOnly = true), emptyList(), emptyList(), null)))
        assertEquals("finished · read-only", PackFacts.standing(PackState("p", PackStatus.LIVE, session(status = "finished", readOnly = true), emptyList(), emptyList(), null)))
        // Never "live" while nothing is sent: paused for the account's sake, opening, out of reach, gone.
        assertEquals("paused", PackFacts.standing(PackState("p", PackStatus.PAUSED, session(), emptyList(), emptyList(), null)))
        assertEquals("opening", PackFacts.standing(PackState("p", PackStatus.LOADING, null, emptyList(), emptyList(), null)))
        assertEquals("cannot be reached", PackFacts.standing(PackState("p", PackStatus.ERROR, null, emptyList(), emptyList(), null)))
        assertEquals("no longer available", PackFacts.standing(PackState("p", PackStatus.GONE, session(), emptyList(), emptyList(), null)))
        val state = PackState("p", PackStatus.LIVE, session(), listOf(lz), emptyList(), null)
        assertEquals(lz, PackFacts.itemOf(state, "pack:p:lz-1"))
        assertNull(PackFacts.itemOf(state, "pack:q:lz-1"))
        assertNull(PackFacts.itemOf(state, "lib-uuid"))
        assertEquals("Mission Pack", PackFacts.name(PackState("p", PackStatus.LOADING, null, emptyList(), emptyList(), null)))
    }

    private fun pointing(at: Double, item: String? = "lz-1") = buildJsonObject {
        put("user_id", 2)
        put("name", "Sam Bell")
        put("focus", buildJsonObject { item?.let { put("item", it) }; put("at", buildJsonArray { add(at); add(-84.0) }) })
    }

    @Test
    fun `a glance is the same while only a pointer moves, and changes with what a panel shows`() {
        val items = listOf(lz, other)
        fun state(people: List<JsonObject>, items: List<PackItemView> = listOf(lz, other), status: PackStatus = PackStatus.LIVE) =
            PackState("p", status, session(), items, people, null)
        val first = PackGlance.of(state(listOf(pointing(34.1))))
        assertEquals(first, PackGlance.of(state(listOf(pointing(34.2)))))                         // the pointer moved: nothing a panel shows
        assertNotEquals(first, PackGlance.of(state(listOf(pointing(34.2, item = "lz-2")))))       // Sam has another item open
        assertNotEquals(first, PackGlance.of(state(listOf(pointing(34.1)), items = listOf(lz.copy(data = JsonObject(emptyMap())), other))))   // an item changed
        assertNotEquals(first, PackGlance.of(state(listOf(pointing(34.1)), items = listOf(lz.copy(name = "LZ RAVEN"), other))))
        assertNotEquals(first, PackGlance.of(state(listOf(pointing(34.1)), status = PackStatus.PAUSED)))
        // An item is the same by its data's instance, never its contents: an equal copy is a change (an item can be megabytes, and is never walked).
        assertNotEquals(first, PackGlance.of(state(listOf(pointing(34.1)), items = items.map { it.copy(data = JsonObject(LinkedHashMap((it.data as JsonObject)))) })))
        assertEquals(first, PackGlance.of(state(listOf(pointing(34.1)), items = items.map { it.copy() })))
        assertEquals(listOf("Sam B."), first.withItOpen("lz-1"))
        assertEquals(lz, first.itemOf("pack:p:lz-1"))
        assertNull(first.itemOf("pack:q:lz-1"))
    }

    @Test
    fun `the panels are told of the pack once for each change they would show, never for a pointer moving`() = runTest {
        val open = object : OpenPack {
            override val state = MutableStateFlow<PackState?>(null)
            override suspend fun createLz(target: DiagramTarget, name: String?): String? = null
            override suspend fun createRouteSet(name: String?): String? = null
            override suspend fun openItem(itemUuid: String) = false
            override suspend fun closeItem(itemUuid: String) = Unit
            override suspend fun removeItem(itemUuid: String): String? = null
        }
        val seen = mutableListOf<PackGlance?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { open.glance.collect { seen += it } }
        open.state.value = PackState("p", PackStatus.LIVE, session(), listOf(lz), listOf(pointing(34.1)), null)
        open.state.value = PackState("p", PackStatus.LIVE, session(), listOf(lz), listOf(pointing(34.2)), null)
        open.state.value = PackState("p", PackStatus.LIVE, session(), listOf(lz), listOf(pointing(34.3)), null)
        open.state.value = PackState("p", PackStatus.LIVE, session(), listOf(lz), listOf(pointing(34.3, item = null)), null)
        job.cancel()
        assertEquals(3, seen.size)                                                                // none, one glance, Sam closing it
        assertEquals(listOf("Sam B."), seen[1]!!.withItOpen("lz-1"))
        assertTrue(seen[2]!!.withItOpen("lz-1").isEmpty())
    }

    @Test
    fun `before the device holds the pack it is the pack, saved to as you work, and opening or out of reach`() {
        val opening = PackGlance.of(PackState("p", PackStatus.LOADING, null, emptyList(), emptyList(), null))
        assertFalse(opening.loaded)
        assertEquals("the pack", opening.name)
        assertEquals("Saved to the pack as you work.", opening.savedLine)
        assertEquals("opening", opening.standing)
        assertTrue(PackGlance.of(PackState("p", PackStatus.LIVE, session(), emptyList(), emptyList(), null)).loaded)
    }

    @Test
    fun `a short name is the first word and the last one's first letter, never half a character`() {
        assertEquals("Sam B.", PackFacts.shortName(" Sam   Bell "))
        assertEquals("Cher", PackFacts.shortName("Cher"))
        assertEquals("Someone", PackFacts.shortName(null))
        assertEquals("Ann 𝐀.", PackFacts.shortName("Ann 𝐀lpha"))
    }
}
