package app.ezpztac.packs

import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.InvitePack
import app.ezpztac.network.InviteTeam
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackPerson
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The workspace: switching between the Library and the packs, the switcher's lists, invitations, a pack that goes, and a change of account. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private fun pack(uuid: String, name: String, role: String = "editor", status: String = "active", members: Int = 4, counts: PackItemCounts = PackItemCounts(3, 2, 0), finishedAt: String? = null) =
        PackSummary(
            uuid, name, "", status, role, PackPerson(1, "Alex Park"), headSeq = 10, seenSeq = 10, memberCount = members, audienceCount = members,
            itemCount = counts.lz + counts.route + counts.pointset, itemCounts = counts, finishedAt = finishedAt, createdAt = "2026-09-01T10:00:00", updatedAt = "2026-09-01T10:00:00",
        )

    /** The server's lists, as asked: each answer is what was there when it was asked, held while [hold] is set (a slow network). */
    private class FakeApi : PacksApi {
        var packs = listOf<PackSummary>()
        var invites = listOf<Invite>()
        var teams = listOf<TeamSummary>()
        var fail: Exception? = null
        var answerFail: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        val asked = mutableListOf<String>()
        private suspend fun <T> list(value: T): T {
            val failing = fail
            hold?.await()
            failing?.let { throw it }
            return value
        }
        override suspend fun packs(): List<PackSummary> = list(packs)
        override suspend fun invites(): List<Invite> = list(invites)
        override suspend fun teams(): List<TeamSummary> = list(teams)
        override suspend fun accept(inviteId: Int): InviteAccepted {
            asked += "accept $inviteId"
            answerFail?.let { throw it }
            val invite = invites.first { it.id == inviteId }
            invites = invites - invite
            val joined = invite.pack?.let { p -> packs.firstOrNull { it.uuid == p.uuid } }
            return InviteAccepted(invite, pack = joined)
        }
        override suspend fun decline(inviteId: Int) {
            asked += "decline $inviteId"
            answerFail?.let { throw it }
            invites = invites.filter { it.id != inviteId }
        }
    }

    private class FakeDoor : PackDoor {
        override val user = MutableStateFlow<Int?>(null)
        override val open = MutableStateFlow<PackState?>(null)
        val log = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        /** Thrown once the switch is made, as PackWorkspace throws a save that failed on the way. */
        var fail: Exception? = null
        /** Thrown before anything opened (the device's database). */
        var refuse: Exception? = null
        override suspend fun openPack(uuid: String) {
            log += "open $uuid"
            hold?.await()
            refuse?.let { throw it }
            open.value = PackState(uuid, PackStatus.LIVE, null, emptyList(), emptyList(), null)
            fail?.let { throw it }
        }
        override suspend fun closePack() {
            log += "close"
            hold?.await()
            open.value = null
            fail?.let { throw it }
        }
    }

    /** The person's own documents: [own] while one is in the editor. */
    private class FakeParking(private val log: MutableList<String>) : Parking {
        var own = true
        var fail: Exception? = null
        override suspend fun park() {
            log += "park"
            fail?.let { throw it }
            own = false
        }
        override suspend fun unpark() {
            log += "unpark"
            own = true
        }
    }

    private class FakeLastPack : LastPack {
        val stored = mutableMapOf<Int, String>()
        override fun id(user: Int): String? = stored[user]
        override fun remember(user: Int, uuid: String?) { if (uuid == null) stored.remove(user) else stored[user] = uuid }
    }

    private inner class Rig(scope: TestScope) {
        val api = FakeApi()
        val door = FakeDoor()
        val parking = FakeParking(door.log)
        val last = FakeLastPack()
        val model = WorkspaceViewModel(api, door, parking, last, object : LibraryTally { override val counts = flowOf(LibraryCounts(12, 4, 3)) }).also {
            it.zone = { ZoneId.of("UTC") }
        }
        val notices = mutableListOf<String>()
        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }
    }

    private val dk = pack("p-dk", "OP DK", role = "owner")
    private val ex = pack("p-ex", "B CO FIELD EX", members = 18)
    private val razor = pack("p-rz", "OP RAZORBILL", status = "finished", finishedAt = "2026-09-22T14:00:00")

    private fun person(id: Int, name: String) = buildJsonObject { put("user_id", JsonPrimitive(id)); put("name", JsonPrimitive(name)) }

    // -- Switching -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `opening a pack sets the person's own work aside, opens it and remembers it for them, and the Library puts it back`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        assertEquals(listOf("park", "open p-dk", "park"), r.door.log)                              // and again once it is open: nothing of their own came in meanwhile
        assertEquals("p-dk", r.last.id(7))
        r.door.log.clear()
        r.model.switchTo("p-ex")                                                                   // pack to pack: nothing of the person's own moves
        advanceUntilIdle()
        assertEquals(listOf("open p-ex"), r.door.log)
        r.door.log.clear()
        r.model.switchTo(null)
        advanceUntilIdle()
        assertEquals(listOf("close", "unpark"), r.door.log)
        assertNull(r.last.id(7))
        assertTrue(r.parking.own)
        r.door.log.clear()
        r.model.switchTo(null)                                                                     // already the Library
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.door.log)
    }

    @Test
    fun `something of the person's own that opened while the pack did is set aside too`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.hold = CompletableDeferred()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        r.parking.own = true                                                                       // the launch's own restore, while the pack was opening
        r.door.hold?.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.parking.own)
    }

    @Test
    fun `work that could not be saved keeps the pack shut, and says so`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.parking.fail = IllegalStateException("disk full")
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        assertEquals(listOf("park"), r.door.log)
        assertNull(r.door.open.value)
        assertNull(r.last.id(7))
        assertEquals(listOf("Your LZ/PZ could not be saved, so the pack was not opened. It is still open here."), r.notices)
        assertFalse(r.model.switcher.value.busy)
    }

    @Test
    fun `a write that failed on the way is said, and the switch it came with stands`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.fail = IllegalStateException("database")
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        assertEquals(listOf("The last change to an item of the pack could not be saved."), r.notices)
        assertEquals("p-dk", r.door.open.value?.uuid)
        assertEquals("p-dk", r.last.id(7))
        assertFalse(r.parking.own)
    }

    @Test
    fun `a pack that did not open puts the person's own work back, and is not remembered`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.refuse = IllegalStateException("database")
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        assertEquals(listOf("park", "open p-dk", "unpark"), r.door.log)
        assertTrue(r.parking.own)
        assertNull(r.last.id(7))
    }

    @Test
    fun `switches are made one at a time, and the latest asked for wins over one still waiting`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.hold = CompletableDeferred()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        assertTrue(r.model.switcher.value.busy)
        r.model.switchTo("p-ex")                                                                   // waits
        r.model.switchTo("p-rz")                                                                   // replaces it
        r.door.hold?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("park", "open p-dk", "park", "open p-rz"), r.door.log)
        assertEquals("p-rz", r.door.open.value?.uuid)
        assertEquals("p-rz", r.last.id(7))
        assertFalse(r.model.switcher.value.busy)
    }

    // -- Launch and accounts ---------------------------------------------------------------------------------------------------------

    @Test
    fun `once packs run for someone their last pack opens again at once, never another account's`() = runTest(dispatcher) {
        val r = Rig(this)
        r.last.remember(7, "p-dk")
        r.door.user.value = 8                                                                      // someone else
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.door.log)
        r.door.user.value = null
        advanceUntilIdle()
        r.api.hold = CompletableDeferred()                                                         // the lists are slow: the pack does not wait for them
        r.door.user.value = 7
        advanceUntilIdle()
        assertEquals(listOf("park", "open p-dk", "park"), r.door.log)
        assertTrue(r.model.available.value)
    }

    @Test
    fun `the last pack opens again once in a process, not over what the person did since`() = runTest(dispatcher) {
        val r = Rig(this)
        r.last.remember(7, "p-dk")
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.switchTo(null)
        advanceUntilIdle()
        r.last.remember(7, "p-dk")                                                                 // as if remembered again by another device
        r.door.log.clear()
        r.door.user.value = null                                                                   // packs stop, and start again (Mission Packs off and on)
        advanceUntilIdle()
        r.door.user.value = 7
        advanceUntilIdle()
        assertFalse(r.door.log.contains("open p-dk"))
    }

    @Test
    fun `another account never sees the lists of the one before, even when its own cannot be loaded`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(dk)
        r.api.invites = listOf(Invite(5, null, "editor", "pending", PackPerson(2, "Alex Park"), "x", "x", pack = InvitePack("p-ib", "OP IBIS")))
        r.api.teams = listOf(TeamSummary(1, "B Co 2-10 AVN", "member", 30, "x"))
        r.door.user.value = 7
        advanceUntilIdle()
        assertEquals(listOf("OP DK"), r.model.switcher.value.packs.map { it.name })
        r.door.user.value = null
        advanceUntilIdle()
        r.model.switcher.value.let { assertTrue(it.packs.isEmpty() && it.invites.isEmpty() && it.teams == TEAMLESS && !it.loaded) }   // cleared at the sign-out
        r.api.fail = ApiException(500, null, "boom")
        r.door.user.value = 8
        advanceUntilIdle()
        val s = r.model.switcher.value
        assertTrue(s.packs.isEmpty() && s.invites.isEmpty())
        assertEquals(TEAMLESS, s.teams)
        assertFalse(s.loaded)                                                                      // so not "No packs yet" either
        assertEquals("Something went wrong on the server. Try again.", s.error)
    }

    @Test
    fun `a load asked for one account that answers after another signed in is not shown`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(dk)
        r.api.hold = CompletableDeferred()
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.user.value = null
        advanceUntilIdle()
        val slow = r.api.hold!!
        r.api.hold = null
        slow.complete(Unit)                                                                        // 7's answer, after the sign-out
        advanceUntilIdle()
        assertTrue(r.model.switcher.value.packs.isEmpty())
        r.api.packs = listOf(ex)
        val slower = CompletableDeferred<Unit>()
        r.api.hold = slower
        r.door.user.value = 8
        advanceUntilIdle()
        r.api.hold = null
        r.api.packs = listOf(razor)
        r.door.user.value = 9                                                                      // and another, before 8's answered
        advanceUntilIdle()
        slower.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.switcher.value.packs.any { it.name == "B CO FIELD EX" })
    }

    @Test
    fun `an older load that answers last does not undo a newer one`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.invites = listOf(Invite(5, null, "editor", "pending", PackPerson(2, "Alex Park"), "x", "x", pack = InvitePack("p-dk", "OP DK")))
        r.door.user.value = 7
        advanceUntilIdle()
        val old = CompletableDeferred<Unit>()
        r.api.hold = old
        r.model.refresh()                                                                          // still has the invitation
        advanceUntilIdle()
        r.api.invites = emptyList()                                                                // accepted elsewhere
        r.api.hold = null
        r.model.refresh()
        advanceUntilIdle()
        old.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.model.switcher.value.invites.isEmpty())
    }

    @Test
    fun `a list that could not be refreshed keeps the last one and says why, until one is`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(dk)
        r.door.user.value = 7
        advanceUntilIdle()
        r.api.fail = ApiException(500, null, "boom")
        r.model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("OP DK"), r.model.switcher.value.packs.map { it.name })
        assertEquals("Something went wrong on the server. Try again.", r.model.switcher.value.error)
        assertTrue(r.model.switcher.value.loaded)
        r.api.fail = null
        r.model.refresh()
        advanceUntilIdle()
        assertNull(r.model.switcher.value.error)
    }

    // -- A pack that goes ------------------------------------------------------------------------------------------------------------

    @Test
    fun `a pack that has gone takes the person back to the Library at once, told why`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(dk)
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        r.door.log.clear()
        r.api.hold = CompletableDeferred()                                                         // the lists are slow: leaving does not wait for them
        r.door.open.value = PackState("p-dk", PackStatus.GONE, null, emptyList(), emptyList(), null)
        advanceUntilIdle()
        assertEquals(listOf("OP DK is no longer available to you."), r.notices)
        assertEquals(listOf("close", "unpark"), r.door.log)
        assertNull(r.last.id(7))
    }

    @Test
    fun `a pack that goes while a switch is made is left once it is done, and not if the person has already left it`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        r.door.log.clear()
        r.door.hold = CompletableDeferred()
        r.model.switchTo("p-ex")                                                                   // in flight
        advanceUntilIdle()
        r.door.open.value = PackState("p-dk", PackStatus.GONE, null, emptyList(), emptyList(), null)
        r.door.hold?.complete(Unit)
        advanceUntilIdle()
        // The way out of p-dk is not taken: p-ex is open now.
        assertEquals("p-ex", r.door.open.value?.uuid)
        assertFalse(r.door.log.contains("close"))
    }

    // -- The switcher ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the switcher lists the Library, the packs worked on before the finished ones, who is here and the teams`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(razor, dk, ex)
        r.api.teams = listOf(TeamSummary(1, "B Co 2-10 AVN", "member", 30, "2026-01-01"), TeamSummary(2, "HHC", "member", 9, "2026-01-01"))
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        r.door.open.value = PackState(
            "p-dk", PackStatus.LIVE, null, emptyList(),
            listOf(person(7, "Colin McFadden"), person(9, "Sam B."), person(11, "Jess R.")), null,
        )
        advanceUntilIdle()
        val s = r.model.switcher.value
        assertEquals("Personal · 12 LZ/PZs, 4 route sets, 3 point sets", s.library)
        assertEquals(listOf("OP DK", "B CO FIELD EX", "OP RAZORBILL"), s.packs.map { it.name })
        assertEquals(listOf(true, false, false), s.packs.map { it.current })
        assertEquals(listOf(false, false, true), s.packs.map { it.finished })
        assertEquals("p-dk", s.current)
        assertEquals("4 members · you are Owner", s.packs[0].meta)
        assertEquals(listOf("2 here now", "3 LZ/PZ · 2 route sets"), s.packs[0].chips)              // the others here: not this person
        assertEquals(listOf("3 LZ/PZ · 2 route sets"), s.packs[1].chips)                           // nobody known to be in a pack not open
        assertEquals("Finished Sep 22 · read-only", s.packs[2].meta)
        assertEquals(emptyList<String>(), s.packs[2].chips)
        assertEquals("B Co 2-10 AVN and 1 other team", s.teams)
        assertEquals("OP DK", r.model.open.value?.name)
        assertEquals(listOf(true, false, false), r.model.open.value?.people?.map { it.me })
    }

    @Test
    fun `the pack open has its row before any list has come`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.fail = ApiException(500, null, "boom")                                               // offline, say
        r.door.user.value = 7
        r.door.open.value = PackState("p-dk", PackStatus.OFFLINE, null, emptyList(), emptyList(), null)
        advanceUntilIdle()
        val s = r.model.switcher.value
        assertEquals("p-dk", s.current)
        assertEquals(listOf("Mission Pack"), s.packs.map { it.name })
        assertTrue(s.packs.single().current)
        assertFalse(s.loaded)
    }

    @Test
    fun `this person is who the pack's session says, and the pack's name and state are the session's`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        val session = PackSession(
            9, buildJsonObject { put("uuid", JsonPrimitive("p-dk")); put("name", JsonPrimitive("OP DARK KNIGHT")); put("status", JsonPrimitive("finished")) },
            JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 0, emptyList(), emptyList(), readOnly = true, gone = null, diverged = false, view = emptyMap(),
        )
        r.door.open.value = PackState("p-dk", PackStatus.LIVE, session, emptyList(), listOf(person(7, "Colin McFadden"), person(9, "Sam B.")), null)
        advanceUntilIdle()
        val open = r.model.open.value!!
        assertEquals("OP DARK KNIGHT", open.name)
        assertTrue(open.finished && open.readOnly)
        assertEquals(listOf(false, true), open.people.map { it.me })
        assertEquals("Read-only for everyone", PackWords.status(open))
    }

    // -- Invitations -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `accepting an invitation opens the pack, closes the switcher and then says what was joined, and declining only refreshes`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.packs = listOf(dk.copy(role = "editor"))
        r.api.invites = listOf(
            Invite(5, null, "editor", "pending", PackPerson(2, "Alex Park"), "2026-11-01", "2026-10-01", pack = InvitePack("p-dk", "OP DK")),
            Invite(6, null, "member", "pending", PackPerson(2, "Alex Park"), "2026-11-01", "2026-10-01", team = InviteTeam(3, "HHC")),
        )
        r.door.user.value = 7
        advanceUntilIdle()
        assertEquals(listOf("Alex Park invited you to OP DK as Editor", "Alex Park invited you to the team HHC"), r.model.switcher.value.invites.map { it.words })
        r.model.answer(6, accept = false)
        advanceUntilIdle()
        assertEquals(listOf("decline 6"), r.api.asked)
        assertEquals(listOf(5), r.model.switcher.value.invites.map { it.id })
        val closed = mutableListOf<String>()
        r.model.answer(5, accept = true, onJoined = { closed += "closed after ${r.notices.size} notices" })
        advanceUntilIdle()
        assertEquals(listOf("closed after 0 notices"), closed)
        assertEquals(listOf("You joined OP DK as an editor."), r.notices)
        assertEquals(listOf("park", "open p-dk", "park"), r.door.log)
        assertTrue(r.model.switcher.value.invites.isEmpty())
        r.model.openJoined(5, "p-dk")                                                              // the same invitation, from a link: not opened twice
        r.model.switchTo(null)
        advanceUntilIdle()
        r.door.log.clear()
        r.model.openJoined(5, "p-dk")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.door.log)
    }

    @Test
    fun `an answer that did not go through is said in the switcher, not under it, until it is opened again`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.invites = listOf(Invite(5, null, "editor", "pending", PackPerson(2, "Alex Park"), "x", "x", pack = InvitePack("p-dk", "OP DK")))
        r.door.user.value = 7
        advanceUntilIdle()
        r.api.answerFail = ApiException(500, null, "boom")
        var closed = false
        r.model.answer(5, accept = true, onJoined = { closed = true })
        advanceUntilIdle()
        assertEquals("Something went wrong on the server. Try again.", r.model.switcher.value.answerError)
        assertEquals(emptyList<String>(), r.notices)
        assertFalse(closed)
        assertFalse(r.model.switcher.value.busy)
        r.model.switcherOpened()
        advanceUntilIdle()
        assertNull(r.model.switcher.value.answerError)
    }

    @Test
    fun `an invitation link that let the person in opens the pack, once`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.model.openJoined(5, "p-dk")
        advanceUntilIdle()
        assertEquals(listOf("park", "open p-dk", "park"), r.door.log)
        r.model.switchTo(null)
        advanceUntilIdle()
        r.door.log.clear()
        r.model.openJoined(5, "p-dk")                                                              // composed again before the toast went
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.door.log)
    }

    @Test
    fun `a pack joined before packs ran opens once they do, over the pack remembered`() = runTest(dispatcher) {
        val r = Rig(this)
        r.last.remember(7, "p-dk")
        r.model.openJoined(5, "p-ib")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), r.door.log)                                              // nothing parked for a pack that cannot open yet
        r.door.user.value = 7
        advanceUntilIdle()
        assertEquals("p-ib", r.door.open.value?.uuid)
        assertFalse(r.door.log.contains("open p-dk"))
    }

    @Test
    fun `a pack joined while a switch is made opens after it`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.hold = CompletableDeferred()
        r.model.switchTo("p-dk")
        advanceUntilIdle()
        r.model.openJoined(5, "p-ib")
        r.door.hold?.complete(Unit)
        advanceUntilIdle()
        assertEquals("p-ib", r.door.open.value?.uuid)
    }
}
