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
import kotlinx.coroutines.test.advanceTimeBy
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

        // Finishing, reopening and duplicating: what was asked and for whom, held while [actHold] is set, refused with [actFail].
        var actFail: Exception? = null
        var actHold: CompletableDeferred<Unit>? = null
        var made = PackMade("p-copy", "OP DK 2")
        private suspend fun act(what: String) {
            asked += what
            actHold?.await()
            actFail?.let { throw it }
        }
        override suspend fun finish(uuid: String, user: Int) = act("finish $uuid as $user")
        override suspend fun reopen(uuid: String, user: Int) = act("reopen $uuid as $user")
        override suspend fun duplicate(uuid: String, name: String, user: Int): PackMade {
            act("duplicate $uuid $name as $user")
            return made
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

        /** Thrown by the editors' last save before a finish. */
        var flushFail: Exception? = null
        override fun refresh() {
            log += "refresh"
        }
        override suspend fun flushOpen() {
            log += "flush"
            flushFail?.let { throw it }
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

    // -- Finishing, reopening and duplicating (P2d) ------------------------------------------------------------------------------

    /** Pack OP DK as its session holds it: [role] this person's, finished by [by] when [finished], with [pending] edits not yet confirmed. */
    private fun dkState(role: String = "owner", finished: Boolean = false, by: String? = "Colin McFadden", pending: Int = 0, status: PackStatus = PackStatus.LIVE): PackState {
        val pack = buildJsonObject {
            put("uuid", JsonPrimitive("p-dk"))
            put("name", JsonPrimitive("OP DK"))
            put("status", JsonPrimitive(if (finished) "finished" else "active"))
            put("role", JsonPrimitive(role))
            if (finished) {
                put("finished_at", JsonPrimitive("2026-10-05T14:20:00"))
                put("finished_by", if (by == null) kotlinx.serialization.json.JsonNull else buildJsonObject { put("id", JsonPrimitive(7)); put("name", JsonPrimitive(by)) })
            }
        }
        val ops = List(pending) { app.ezpztac.missionpacks.PendingOp(buildJsonObject { put("client_op_id", JsonPrimitive("op-$it")) }, app.ezpztac.missionpacks.PendingState.SENT) }
        val session = PackSession(7, pack, JsonArray(emptyList()), emptyMap(), emptyMap(), emptyList(), 10, ops, emptyList(), finished || role == "viewer", null, false, emptyMap())
        return PackState("p-dk", status, session, emptyList(), emptyList(), null)
    }

    private suspend fun TestScope.inDk(r: Rig, state: PackState = dkState()) {
        r.door.user.value = 7
        advanceUntilIdle()
        r.door.open.value = state
        advanceUntilIdle()
        r.door.log.clear()
        r.api.asked.clear()
    }

    @Test
    fun `the owner finishes the pack, asked first, the editors written, then finished, the pack and the lists asked for again, and said`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askFinish()
        assertEquals(PackDialog.Finish("p-dk", "OP DK"), r.model.dialog.value)
        r.model.finish()
        assertNull("the question goes first, as on the web", r.model.dialog.value)
        advanceUntilIdle()
        assertEquals(listOf("finish p-dk as 7"), r.api.asked)
        assertEquals(listOf("flush", "refresh"), r.door.log)
        assertEquals(listOf("OP DK is finished and read-only for everyone."), r.notices)
    }

    @Test
    fun `changes still on their way are waited for, and a pack that does not take them in time is not finished`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(pending = 2))
        r.model.askFinish()
        r.model.finish()
        advanceTimeBy(5_000)
        assertTrue("not yet", r.api.asked.isEmpty())
        r.door.open.value = dkState(pending = 0)                                                   // they reached the pack
        advanceUntilIdle()
        assertEquals(listOf("finish p-dk as 7"), r.api.asked)
        // Offline: they never go.
        r.api.asked.clear()
        r.notices.clear()
        r.door.open.value = dkState(pending = 1, status = PackStatus.OFFLINE)
        advanceUntilIdle()
        r.model.askFinish()
        r.model.finish()
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertEquals(listOf("Your last changes are still on their way to OP DK. Finish it once they are sent."), r.notices)
    }

    @Test
    fun `a last change that could not be written stops the finish, in words`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.door.flushFail = IllegalStateException("disk full")
        r.model.askFinish()
        r.model.finish()
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertEquals(listOf("The last change to OP DK could not be saved, so it was not finished."), r.notices)
    }

    @Test
    fun `only the owner is asked about finishing an active pack and reopening a finished one`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(role = "editor"))
        r.model.askFinish()
        assertNull(r.model.dialog.value)
        r.door.open.value = dkState(role = "editor", finished = true)
        advanceUntilIdle()
        r.model.askReopen()
        assertNull(r.model.dialog.value)
        r.door.open.value = dkState(finished = true)
        advanceUntilIdle()
        r.model.askFinish()
        assertNull("finished already", r.model.dialog.value)
        r.model.askReopen()
        assertEquals(PackDialog.Reopen("p-dk", "OP DK"), r.model.dialog.value)
        r.model.finish()                                                                            // not what was asked: nothing happens
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
    }

    @Test
    fun `the owner reopens the pack, and a refusal is said in the app's words`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(finished = true))
        r.model.askReopen()
        r.model.reopen()
        advanceUntilIdle()
        assertEquals(listOf("reopen p-dk as 7"), r.api.asked)
        assertEquals(listOf("refresh"), r.door.log)
        assertEquals(listOf("OP DK is open for editing again."), r.notices)
        r.notices.clear()
        r.api.actFail = ApiException(403, "owner_only", "Only the pack's owner can do that.")
        r.model.askReopen()
        r.model.reopen()
        advanceUntilIdle()
        assertEquals(listOf("Only the pack's owner can do that."), r.notices)
    }

    @Test
    fun `one finish or reopen at a time, and what it came to is not said to the next account`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.api.actHold = CompletableDeferred()
        r.model.askFinish()
        r.model.finish()
        advanceUntilIdle()
        r.door.open.value = dkState(finished = true)
        advanceUntilIdle()
        r.model.askReopen()
        r.model.reopen()
        advanceUntilIdle()
        assertEquals(listOf(PackPanelViewModel.STILL_WORKING), r.notices)
        r.door.user.value = 8                                                                       // signed out, and someone else in
        advanceUntilIdle()
        r.api.actHold?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(PackPanelViewModel.STILL_WORKING), r.notices)
    }

    @Test
    fun `anyone in the pack duplicates it, named as they type it in capitals, and the copy opens`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(role = "viewer", finished = true))
        r.model.askDuplicate()
        assertEquals(PackDialog.Duplicate("p-dk", "OP DK", "OP DK 2"), r.model.dialog.value)
        r.api.actHold = CompletableDeferred()
        r.model.duplicate("  op eagle  ")
        advanceUntilIdle()
        assertEquals(true, (r.model.dialog.value as PackDialog.Duplicate).sending)
        r.model.dismissDialog()                                                                     // not while it is made
        assertTrue(r.model.dialog.value is PackDialog.Duplicate)
        r.model.duplicate("again")                                                                  // nor a second one
        r.api.made = PackMade("p-eagle", "OP EAGLE")
        r.api.actHold?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("duplicate p-dk OP EAGLE as 7"), r.api.asked)
        assertNull(r.model.dialog.value)
        assertEquals("p-eagle", r.door.open.value?.uuid)
        assertEquals(listOf("Made OP EAGLE."), r.notices)
    }

    @Test
    fun `a name that will not do is said in the dialog, and nothing is sent`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askDuplicate()
        r.model.duplicate("   ")
        assertEquals("Give it a name.", (r.model.dialog.value as PackDialog.Duplicate).error)
        r.model.duplicate("A".repeat(101))
        assertEquals("Give it a name of 1 to 100 characters.", (r.model.dialog.value as PackDialog.Duplicate).error)
        r.model.duplicate("OP" + Char(9) + "DK")
        assertEquals("Give it a name of 1 to 100 characters.", (r.model.dialog.value as PackDialog.Duplicate).error)
        // A hundred characters, emoji among them, is a hundred: counted as characters, not as the halves an emoji is written in.
        r.model.duplicate(String(Character.toChars(0x1F681)).repeat(100))
        advanceUntilIdle()
        assertEquals(1, r.api.asked.size)
        assertTrue(r.notices.isEmpty() || r.notices == listOf("Made OP DK 2."))
    }

    @Test
    fun `a copy refused is said in the dialog, and one whose answer was lost says it may have been made`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askDuplicate()
        r.api.actFail = ApiException(400, "invalid_name", "Give the pack a name of 1 to 100 characters.")
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        val refused = r.model.dialog.value as PackDialog.Duplicate
        assertEquals("Give it a name of 1 to 100 characters.", refused.error)
        assertFalse(refused.sending)
        assertEquals("OP DK 2", refused.name)
        r.api.actFail = app.ezpztac.network.NetworkException("read timed out", null, requestMayHaveBeenSent = true)
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        assertEquals(LOST_COPY, (r.model.dialog.value as PackDialog.Duplicate).error)
        assertEquals("p-dk", r.door.open.value?.uuid)                                               // nothing opened
        r.api.actFail = app.ezpztac.network.NetworkException("no route", null, requestMayHaveBeenSent = false)
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        assertEquals("There is no connection to the server. Try again when you are back online.", (r.model.dialog.value as PackDialog.Duplicate).error)
    }

    @Test
    fun `a question goes with its pack and its account, but a copy being made waits for its answer`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askFinish()
        r.door.open.value = null                                                                     // back in the Library
        advanceUntilIdle()
        assertNull(r.model.dialog.value)
        r.door.open.value = dkState()
        advanceUntilIdle()
        r.model.askDuplicate()
        r.api.actHold = CompletableDeferred()
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        r.door.open.value = null
        advanceUntilIdle()
        assertTrue("still being made", r.model.dialog.value is PackDialog.Duplicate)
        r.door.user.value = 8
        advanceUntilIdle()
        assertNull("another account", r.model.dialog.value)
        r.api.actHold?.complete(Unit)
        advanceUntilIdle()
        assertTrue("nothing said to them", r.notices.none { it.startsWith("Made") })
        assertNull(r.door.open.value)
    }

    @Test
    fun `the open pack says who this person is in it, who finished it and when, from its session or else its line`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(finished = true))
        val open = r.model.open.value!!
        assertTrue(open.owner)
        assertEquals("Colin McFadden", open.finishedBy)
        assertEquals(
            "OP DK was finished by Colin McFadden on Oct 5 at 14:20." to "It is read-only for everyone.",
            PackWords.finished(open, ZoneId.of("UTC"), java.time.Instant.parse("2026-10-12T00:00:00Z")),
        )
        r.door.open.value = dkState(finished = true, by = null)                                      // the account that finished it went
        advanceUntilIdle()
        assertEquals(
            "OP DK was finished by its owner on Oct 5, 2026 at 10:20." to "It is read-only for everyone.",
            PackWords.finished(r.model.open.value!!, ZoneId.of("America/New_York"), java.time.Instant.parse("2027-01-12T00:00:00Z")),
        )
        // Before the device holds it: its line in the list.
        r.api.packs = listOf(razor.copy(role = "viewer", finishedBy = PackPerson(4, "Sam Bell")))
        r.model.refresh()
        r.door.open.value = PackState("p-rz", PackStatus.LOADING, null, emptyList(), emptyList(), null)
        advanceUntilIdle()
        val listed = r.model.open.value!!
        assertFalse(listed.owner)
        assertEquals("viewer", listed.role)
        assertEquals("OP RAZORBILL was finished by Sam Bell on Sep 22 at 14:00.", PackWords.finished(listed, ZoneId.of("UTC"), java.time.Instant.parse("2026-10-12T00:00:00Z")).first)
    }

    @Test
    fun `leaving the pack, or packs stopping, while its changes are on their way is not their having arrived, and nothing is finished`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(pending = 2))
        r.model.askFinish()
        r.model.finish()
        advanceTimeBy(2_000)
        r.door.open.value = null                                                                     // back to Personal: its client still drains the edits
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertEquals(listOf("OP DK was not finished: it is not open here now."), r.notices)
        // Another pack opened meanwhile.
        r.notices.clear()
        r.door.open.value = dkState(pending = 1)
        advanceUntilIdle()
        r.model.askFinish()
        r.model.finish()
        advanceTimeBy(1_000)
        r.door.open.value = PackState("p-ex", PackStatus.LIVE, null, emptyList(), emptyList(), null)
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertEquals(listOf("OP DK was not finished: it is not open here now."), r.notices)
        // Packs stopped for the account (signed out, below the minimum version): nothing is sent, and nothing said to whoever is here next.
        r.notices.clear()
        r.door.open.value = dkState(pending = 1)
        advanceUntilIdle()
        r.model.askFinish()
        r.model.finish()
        advanceTimeBy(1_000)
        r.door.user.value = null
        r.door.open.value = null
        advanceUntilIdle()
        assertTrue(r.api.asked.isEmpty())
        assertTrue(r.notices.isEmpty())
    }

    @Test
    fun `a pack whose client is being replaced holds nothing yet, and is waited for until it says what is pending`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r, dkState(pending = 1))
        r.model.askFinish()
        r.model.finish()
        advanceTimeBy(1_000)
        r.door.open.value = PackState("p-dk", PackStatus.LOADING, null, emptyList(), emptyList(), null)    // read back from the device first
        advanceTimeBy(1_000)
        assertTrue("not settled by holding nothing", r.api.asked.isEmpty())
        r.door.open.value = dkState(pending = 0)
        advanceUntilIdle()
        assertEquals(listOf("finish p-dk as 7"), r.api.asked)
    }

    @Test
    fun `a copy made whose answer could not be read is said to be there, and one lost behind the proxy may have been made`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askDuplicate()
        r.api.actFail = ApiException(201, "unreadable_response", "The server's answer could not be read.")
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        assertNull("made: not to be made again", r.model.dialog.value)
        assertEquals(listOf(COPY_MADE), r.notices)
        assertEquals("p-dk", r.door.open.value?.uuid)
        for (gateway in listOf(502, 504, 524)) {
            r.model.askDuplicate()
            r.api.actFail = ApiException(gateway, null, "Bad gateway")
            r.model.duplicate("OP DK 2")
            advanceUntilIdle()
            assertEquals("$gateway", LOST_COPY, (r.model.dialog.value as PackDialog.Duplicate).error)
        }
        r.api.actFail = ApiException(500, null, "boom")                                             // the server's own failure: it did not make it
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        assertEquals("Something went wrong on the server. Try again.", (r.model.dialog.value as PackDialog.Duplicate).error)
    }

    @Test
    fun `what was said of a name goes when it is typed again, but not while the copy is made`() = runTest(dispatcher) {
        val r = Rig(this)
        inDk(r)
        r.model.askDuplicate()
        r.model.duplicate(" ")
        assertEquals("Give it a name.", (r.model.dialog.value as PackDialog.Duplicate).error)
        r.model.duplicateEdited()
        assertNull((r.model.dialog.value as PackDialog.Duplicate).error)
        r.api.actHold = CompletableDeferred()
        r.model.duplicate("OP DK 2")
        advanceUntilIdle()
        r.model.duplicateEdited()
        assertTrue((r.model.dialog.value as PackDialog.Duplicate).sending)
    }

    @Test
    fun `New pack closes the switcher before it opens, and is not offered without a way to make one`() = runTest(dispatcher) {
        val r = Rig(this)
        val log = mutableListOf<String>()
        actionsOf(r.model, close = { log += "close" }, onNewPack = { log += "new pack" }).newPack!!()
        assertEquals(listOf("close", "new pack"), log)
        assertNull(actionsOf(r.model, close = {}).newPack)
    }

    @Test
    fun `Manage teams closes the switcher before Teams opens, and is not offered without a way to open it`() = runTest(dispatcher) {
        val r = Rig(this)
        val log = mutableListOf<String>()
        actionsOf(r.model, close = { log += "close" }, onTeams = { log += "teams" }).teams!!()
        assertEquals(listOf("close", "teams"), log)
        assertNull(actionsOf(r.model, close = {}).teams)
    }
}
