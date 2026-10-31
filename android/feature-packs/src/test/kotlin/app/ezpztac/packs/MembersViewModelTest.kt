package app.ezpztac.packs

import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackMember
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.UserBrief
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Members of the open pack: who is in it and here, inviting, roles, handing over, taking off, the invitations waiting, sharing, and the account it is for. */
@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val me = 6

    private fun member(id: Int, name: String, role: String = "editor", seen: String? = "2026-10-12T10:00:00") =
        PackMember(id, name, "${name.lowercase().replace(' ', '.')}@army.mil", role, "2026-10-01T10:00:00", seen)

    private val colin = member(6, "Colin McFadden", "owner")
    private val sam = member(7, "Sam Bell")
    private val marcus = member(9, "Marcus Webb", "viewer", seen = null)
    private val lopez = Invite(5, "sgt.lopez@army.mil", "editor", "pending", null, "2026-10-18T10:00:00", "2026-10-04T10:00:00")

    private fun person(id: Int) = JsonObject(mapOf("user_id" to JsonPrimitive(id), "name" to JsonPrimitive("x")))

    private class FakeDoor(user: Int?) : PackDoor {
        override val user = MutableStateFlow(user)
        override val open = MutableStateFlow<PackState?>(null)
        val log = mutableListOf<String>()
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
        override fun refresh() {
            log += "refresh"
        }
    }

    private inner class FakeApi : PeopleApi {
        val log = mutableListOf<String>()
        var role = "owner"
        var members = listOf(colin, sam, marcus)
        var pending = listOf(lopez)
        var shared: SharedTeam? = null
        var teams = listOf(TeamSummary(3, "B Co 2-10 AVN", "member", 18, "x"), TeamSummary(4, "HHC", "owner", 1, "x"))
        var peopleFail: Exception? = null
        var peopleHold: CompletableDeferred<Unit>? = null
        var actFail: Exception? = null
        var actHold: CompletableDeferred<Unit>? = null
        var emailed = true
        var found = listOf(UserBrief(7, "Sam Bell", "sam.bell@army.mil"), UserBrief(8, "Sid Ray", "sid.ray@army.mil"))

        var teamsFail: Exception? = null
        override suspend fun people(uuid: String, user: Int): PackPeople {
            log += "people $uuid as $user"
            // What the server holds when it is asked, answered when the hold lets go.
            val answer = PackPeople(if (uuid == "p-dk") "OP DK" else "OP EAGLE", role, members, if (role == "owner") pending else emptyList(), shared)
            val failing = peopleFail
            peopleHold?.await()
            failing?.let { throw it }
            return answer
        }
        override suspend fun teams(user: Int): List<TeamSummary> {
            teamsFail?.let { throw it }
            return teams
        }
        override suspend fun searchPeople(query: String, user: Int): List<UserBrief> {
            log += "search $query as $user"
            return found
        }
        private suspend fun act(what: String) {
            log += what
            actHold?.await()
            actFail?.let { throw it }
        }
        override suspend fun createPack(name: String, description: String, team: TeamPick?, uuid: String, user: Int): PackCreated = error("not asked")
        override suspend fun updatePack(uuid: String, change: PackChange, user: Int) = act("update $uuid $change as $user")
        override suspend fun addMember(uuid: String, userId: Int, role: String, user: Int) = act("add $userId as $role as $user")
        override suspend fun invite(uuid: String, email: String, role: String, user: Int): Boolean {
            act("invite $email as $role as $user")
            return emailed
        }
        override suspend fun changeMember(uuid: String, userId: Int, role: String, user: Int) = act("change $userId to $role as $user")
        override suspend fun removeMember(uuid: String, userId: Int, user: Int) = act("remove $userId as $user")
        override suspend fun resendInvite(uuid: String, inviteId: Int, user: Int): Boolean {
            act("resend $inviteId as $user")
            return emailed
        }
        override suspend fun revokeInvite(uuid: String, inviteId: Int, user: Int) = act("revoke $inviteId as $user")
    }

    private inner class Rig(scope: TestScope) {
        val api = FakeApi()
        val door = FakeDoor(me).also { it.open.value = PackState("p-dk", PackStatus.LIVE, null, emptyList(), listOf(person(6), person(7)), null) }
        val model = MembersViewModel(api, door)
        val notices = mutableListOf<String>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }
    }

    private fun TestScope.opened(invite: Boolean = false) = Rig(this).also {
        advanceUntilIdle()
        it.model.open("OP DK", invite)
        advanceUntilIdle()
    }

    // -- Opening -------------------------------------------------------------------------------

    @Test
    fun `the page opens on the open pack, with its people, who is here, the invitations waiting and the person's teams`() = runTest(dispatcher) {
        val r = opened()
        val st = r.model.state.value
        assertTrue(st.open)
        assertEquals("p-dk", st.uuid)
        val people = st.people!!
        assertEquals(listOf(6, 7, 9), people.members.map { it.userId })
        assertEquals(listOf(5), people.pending!!.map { it.id })
        assertEquals(setOf(6, 7), st.here)
        assertEquals(me, st.me)
        assertTrue(st.owner)
        assertEquals(listOf(3, 4), st.shareable.map { it.id })
        assertEquals(listOf("people p-dk as $me"), r.api.log)
        r.door.open.value = r.door.open.value!!.copy(people = listOf(person(9)))                     // someone else comes
        advanceUntilIdle()
        assertEquals(setOf(9), r.model.state.value.here)
    }

    @Test
    fun `nothing opens with no pack open or nobody signed in, and Invite on an open page only takes the field to the keyboard`() = runTest(dispatcher) {
        val r = Rig(this)
        r.door.open.value = null
        r.model.open("OP DK")
        assertFalse(r.model.state.value.open)
        r.door.open.value = PackState("p-dk", PackStatus.LIVE, null, emptyList(), emptyList(), null)
        r.door.user.value = null
        r.model.open("OP DK")
        assertFalse(r.model.state.value.open)
        val o = opened()
        o.model.query("sam")
        o.model.open("OP DK", invite = true)
        assertEquals("sam", o.model.state.value.query)                                              // the same page, kept
        assertTrue(o.model.state.value.focusInvite)
        o.model.focused()
        assertFalse(o.model.state.value.focusInvite)
    }

    @Test
    fun `another pack opened, the pack closed, or another account puts the page away`() = runTest(dispatcher) {
        for (change in listOf<(Rig) -> Unit>(
            { it.door.open.value = PackState("p-other", PackStatus.LIVE, null, emptyList(), emptyList(), null) },
            { it.door.open.value = null },
            { it.door.user.value = 8 },
        )) {
            val r = opened()
            change(r)
            advanceUntilIdle()
            assertFalse(r.model.state.value.open)
        }
    }

    @Test
    fun `people that could not be had are said with a way to try again, over what was shown`() = runTest(dispatcher) {
        val r = opened()
        r.api.peopleFail = NetworkException("down", requestMayHaveBeenSent = false)
        r.model.load()
        advanceUntilIdle()
        assertEquals("There is no connection to the server. Try again when you are back online.", r.model.state.value.loadError)
        assertEquals(3, r.model.state.value.people!!.members.size)                                   // what was there stays
        r.api.peopleFail = null
        r.model.load()
        advanceUntilIdle()
        assertNull(r.model.state.value.loadError)
    }

    @Test
    fun `an older answer that comes after a newer one is not shown, nor one for a page put away`() = runTest(dispatcher) {
        val r = opened()
        val hold = CompletableDeferred<Unit>()
        r.api.peopleHold = hold
        r.model.load()                                                                                // asked while it was all three
        advanceUntilIdle()
        r.api.members = listOf(colin)
        r.api.peopleHold = null
        r.model.load()                                                                                // asked later, answered first
        advanceUntilIdle()
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(6), r.model.state.value.people!!.members.map { it.userId })

        // Another pack opened while a load for this one was out: its answer, or its failure, lands on nothing.
        val late = CompletableDeferred<Unit>()
        r.api.peopleHold = late
        r.api.peopleFail = ApiException(500, null, "boom")
        r.model.load()
        advanceUntilIdle()
        r.api.peopleHold = null
        r.api.peopleFail = null
        r.door.open.value = PackState("p-eagle", PackStatus.LIVE, null, emptyList(), emptyList(), null)
        advanceUntilIdle()
        r.model.open("OP EAGLE")
        advanceUntilIdle()
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals("OP EAGLE", r.model.state.value.people!!.pack)
        assertNull(r.model.state.value.loadError)
    }

    @Test
    fun `teams that could not be had leave the ones known`() = runTest(dispatcher) {
        val r = opened()
        r.api.teamsFail = ApiException(500, null, "boom")
        r.api.members = listOf(colin)
        r.model.load()
        advanceUntilIdle()
        assertEquals(1, r.model.state.value.people!!.members.size)
        assertNull(r.model.state.value.loadError)
        assertEquals(listOf(3, 4), r.model.state.value.shareable.map { it.id })
    }

    @Test
    fun `putting the page away empties it, and what was on its way to it lands on nothing`() = runTest(dispatcher) {
        val r = opened()
        r.api.peopleHold = CompletableDeferred()
        r.model.load()
        r.model.query("si")
        advanceUntilIdle()
        r.model.close()
        assertEquals(MembersUi(), r.model.state.value)
        r.api.peopleHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(MembersUi(), r.model.state.value)
    }

    @Test
    fun `a change still finishes once the page is put away, and what it came to is said`() = runTest(dispatcher) {
        val r = opened()
        r.api.actHold = CompletableDeferred()
        r.model.role(sam, "viewer")
        advanceUntilIdle()
        r.model.close()
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("Sam Bell is a viewer now", r.notices.last())

        val o = opened()
        o.model.query("a@b.co")
        o.api.actHold = CompletableDeferred()
        o.api.actFail = ApiException(409, "already_member", "x")
        o.model.invite()
        advanceUntilIdle()
        o.model.close()
        o.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("They are already in it.", o.notices.last())                                   // said, since its field has gone
        assertNull(o.model.state.value.inviteError)
    }

    @Test
    fun `a change on its way holds the page opened again, and lands only on the page it was asked from`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("a@b.co")
        r.api.actHold = CompletableDeferred()
        r.api.actFail = ApiException(409, "already_member", "x")
        r.model.invite()
        advanceUntilIdle()
        r.model.close()
        r.model.open("OP DK")
        advanceUntilIdle()
        assertTrue(r.model.state.value.busy)                                                         // still on its way
        r.model.role(sam, "viewer")
        assertEquals(PackPanelViewModel.STILL_WORKING, r.notices.last())
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.state.value.busy)
        assertNull(r.model.state.value.inviteError)                                                  // not under the new page's field
        assertEquals("They are already in it.", r.notices.last())
        assertTrue(r.api.log.none { it.startsWith("change") })
    }

    @Test
    fun `someone not the owner sees the list only`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.role = "editor"
        r.api.shared = SharedTeam(3, "B Co 2-10 AVN", "editor")
        r.model.open("OP DK")
        advanceUntilIdle()
        assertFalse(r.model.state.value.owner)
        r.model.role(sam, "viewer")
        r.model.askRemove(sam)
        r.model.askRevoke(lopez)
        r.model.resend(lopez)
        r.model.share()
        r.model.sharedRole("viewer")
        r.model.askStopSharing()
        r.model.query("a@b.co")
        r.model.invite()
        advanceUntilIdle()
        assertNull(r.model.state.value.confirm)
        assertEquals(listOf("people p-dk as $me"), r.api.log)
    }

    @Test
    fun `a role is an editor or a viewer, for whoever is invited or the team`() = runTest(dispatcher) {
        val r = opened()
        r.model.inviteRole("owner")
        r.model.shareRole("admin")
        assertEquals("editor", r.model.state.value.role)
        assertEquals("editor", r.model.state.value.shareRole)
    }

    @Test
    fun `a pack handed over takes the owner's controls away at once, and a question left from before is not done without a word`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(sam, "owner")
        r.api.peopleHold = CompletableDeferred()                                                      // the people read again only later
        r.model.confirm()
        advanceUntilIdle()
        val people = r.model.state.value.people!!
        assertFalse(r.model.state.value.owner)
        assertEquals("owner", people.members.first { it.userId == 7 }.role)
        assertEquals("editor", people.members.first { it.userId == 6 }.role)
        r.api.peopleHold!!.complete(Unit)
        advanceUntilIdle()

        // A question asked while the person still owned it: let go once a load says they do not, and said if confirmed before.
        val o = opened()
        o.model.askRemove(sam)
        o.api.role = "editor"
        o.model.load()
        advanceUntilIdle()
        assertNull(o.model.state.value.confirm)
        val p = opened()
        p.model.askRemove(sam)
        p.api.peopleHold = CompletableDeferred()
        p.model.role(marcus, "owner")                                                                 // the hand-over question takes its place
        p.model.confirm()
        advanceUntilIdle()
        p.model.askRemove(marcus)                                                                     // no longer the owner: not asked
        assertNull(p.model.state.value.confirm)
        p.api.peopleHold!!.complete(Unit)
        advanceUntilIdle()
    }


    // -- Inviting ----------------------------------------------------------------------------------

    @Test
    fun `a teammate found is chosen into the field, and typing again lets them go`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("si")
        advanceUntilIdle()
        assertEquals(listOf(8), r.model.state.value.found.map { it.id })                            // Sam is in it already
        r.model.pick(r.model.state.value.found.single())
        assertEquals("Sid Ray", r.model.state.value.query)
        assertEquals("Sid Ray, sid.ray@army.mil. Type to look for someone else.", r.model.state.value.inviteHint)
        r.model.query("Sid Ra")
        assertNull(r.model.state.value.chosen)
        assertEquals(MembersWords.HINT, r.model.state.value.inviteHint)
    }

    @Test
    fun `a teammate is added and an address invited, as the role beside the field, then the field empties and everything is asked again`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("si")
        advanceUntilIdle()
        r.model.pick(r.model.state.value.found.single())
        r.model.inviteRole("viewer")
        r.model.invite()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("add 8 as viewer as $me"))
        assertEquals(listOf("Sid Ray is in OP DK now"), r.notices)
        assertEquals("", r.model.state.value.query)
        assertNull(r.model.state.value.chosen)
        assertEquals(listOf("refresh"), r.door.log)
        assertEquals(2, r.api.log.count { it.startsWith("people") })

        r.model.query("  Marcus.Webb@Army.mil ")
        r.api.emailed = false
        r.model.invite()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("invite marcus.webb@army.mil as viewer as $me"))
        assertEquals("Invited marcus.webb@army.mil, but the email did not go. Send it again under Pending.", r.notices.last())
    }

    @Test
    fun `an invitation refused is said under the field, and what was typed stays`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("a@b.co")
        r.api.actFail = ApiException(409, "already_member", "x")
        r.model.invite()
        advanceUntilIdle()
        assertEquals("They are already in it.", r.model.state.value.inviteError)
        assertEquals("a@b.co", r.model.state.value.query)
        assertTrue(r.notices.isEmpty())
        r.model.query("a@b.c")
        assertNull(r.model.state.value.inviteError)
    }

    @Test
    fun `a name typed and not chosen is refused in words, and nothing is sent`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("si")
        r.model.invite()
        advanceUntilIdle()
        assertEquals(NewPackViewModel.PICK_SOMEONE, r.model.state.value.inviteError)
        assertTrue(r.api.log.none { it.startsWith("add") || it.startsWith("invite") })
        r.model.query("")
        r.model.invite()                                                                              // an empty field: nothing to say
        assertNull(r.model.state.value.inviteError)
    }

    // -- Members -----------------------------------------------------------------------------------

    @Test
    fun `a member is made a viewer or an editor at once, and the owner's row is fixed`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(sam, "viewer")
        advanceUntilIdle()
        assertEquals("change 7 to viewer as $me", r.api.log[1])
        assertEquals("Sam Bell is a viewer now", r.notices.last())
        r.model.role(marcus, "editor")
        advanceUntilIdle()
        assertEquals("Marcus Webb is an editor now", r.notices.last())
        val before = r.api.log.size
        r.model.role(colin, "viewer")
        r.model.role(sam, "editor")                                                                   // what they are already
        r.model.role(sam, "admin")
        advanceUntilIdle()
        assertEquals(before, r.api.log.size)
    }

    @Test
    fun `handing the pack over is asked first, then made`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(sam, "owner")
        assertEquals(MembersConfirm.HandOver(sam), r.model.state.value.confirm)
        assertTrue(r.api.log.none { it.startsWith("change") })
        r.model.dismissConfirm()
        assertNull(r.model.state.value.confirm)
        r.model.role(sam, "owner")
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("change 7 to owner as $me"))
        assertEquals("Sam Bell owns OP DK now. You are an editor.", r.notices.last())
    }

    @Test
    fun `taking someone off, withdrawing an invitation and no longer sharing are asked first`() = runTest(dispatcher) {
        val r = opened()
        r.model.askRemove(sam)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("remove 7 as $me"))
        assertEquals("Sam Bell is no longer in OP DK", r.notices.last())
        r.model.askRemove(colin)                                                                      // the owner is never taken off
        assertNull(r.model.state.value.confirm)

        r.model.askRevoke(lopez)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("revoke 5 as $me"))
        assertEquals("Withdrew the invitation to sgt.lopez@army.mil", r.notices.last())

        r.api.shared = SharedTeam(3, "B Co 2-10 AVN", "editor")
        r.model.load()
        advanceUntilIdle()
        r.model.askStopSharing()
        assertEquals(MembersConfirm.StopSharing(SharedTeam(3, "B Co 2-10 AVN", "editor")), r.model.state.value.confirm)
        r.model.confirm()
        advanceUntilIdle()
        assertEquals("update p-dk ${PackChange(setTeam = true, team = null)} as $me", r.api.log.first { it.startsWith("update") })
        assertEquals("B Co 2-10 AVN no longer has this pack", r.notices.last())
    }

    @Test
    fun `an invitation sent again says whether its email went`() = runTest(dispatcher) {
        val r = opened()
        r.model.resend(lopez)
        advanceUntilIdle()
        assertEquals("Sent the invitation to sgt.lopez@army.mil again", r.notices.last())
        r.api.emailed = false
        r.model.resend(lopez)
        advanceUntilIdle()
        assertEquals("The invitation to sgt.lopez@army.mil is waiting, but its email did not go. Try again later.", r.notices.last())
    }

    // -- Sharing -----------------------------------------------------------------------------------

    @Test
    fun `the pack is shared with the team picked, as what it was picked to do, and what a shared team can do is changed`() = runTest(dispatcher) {
        val r = opened()
        r.model.shareTo(4)
        r.model.shareRole("viewer")
        r.model.share()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("update p-dk ${PackChange(setTeam = true, team = TeamPick(4, "viewer"))} as $me"))
        assertEquals("Shared with HHC to view", r.notices.last())
        r.model.shareTo(99)                                                                           // not one of theirs
        assertEquals(4, r.model.state.value.shareTo)

        r.api.shared = SharedTeam(4, "HHC", "viewer")
        r.model.load()
        advanceUntilIdle()
        assertEquals(listOf(3), r.model.state.value.shareable.map { it.id })                        // the one shared is not offered again
        assertEquals(3, r.model.state.value.shareTeam!!.id)
        r.model.sharedRole("viewer")                                                                  // as it is: nothing
        r.model.sharedRole("editor")
        advanceUntilIdle()
        assertEquals("update p-dk ${PackChange(setTeam = true, team = TeamPick(4, "editor"))} as $me", r.api.log.last { it.startsWith("update") })
        assertEquals("HHC can edit this pack now", r.notices.last())
    }

    // -- One at a time, for one account ------------------------------------------------------------

    @Test
    fun `one change at a time, another asked meanwhile is said not to be done`() = runTest(dispatcher) {
        val r = opened()
        r.api.actHold = CompletableDeferred()
        r.model.role(sam, "viewer")
        advanceUntilIdle()
        assertTrue(r.model.state.value.busy)
        r.model.resend(lopez)
        assertEquals(PackPanelViewModel.STILL_WORKING, r.notices.last())
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.state.value.busy)
        assertTrue(r.api.log.none { it.startsWith("resend") })
    }

    @Test
    fun `a refusal is said in the app's words, and what a change came to is never said to another account`() = runTest(dispatcher) {
        val r = opened()
        r.api.actFail = ApiException(403, "owner_only", "x")
        r.model.role(sam, "viewer")
        advanceUntilIdle()
        assertEquals("Only the pack's owner can do that.", r.notices.last())

        val o = opened()
        o.api.actHold = CompletableDeferred()
        o.model.role(sam, "viewer")
        advanceUntilIdle()
        o.door.user.value = 8
        advanceUntilIdle()
        o.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(o.notices.isEmpty())
        assertTrue(o.door.log.isEmpty())                                                             // nor the other account's pack asked
    }

    // -- Words -----------------------------------------------------------------------------------

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-12T14:00:00Z")

    @Test
    fun `where someone is, when they looked and when an invitation stops working are said as the web says them, by the calendar`() {
        assertEquals("Here now", MembersWords.seen(sam, setOf(7), me, utc, now))
        assertNull(MembersWords.seen(colin, setOf(6), me, utc, now))                                  // this person themselves
        assertEquals("Not opened yet", MembersWords.seen(marcus, emptySet(), me, utc, now))
        assertEquals("Seen 4 h ago", MembersWords.seen(sam, emptySet(), me, utc, now))
        assertEquals("just now", MembersWords.timeAgo(now.minusSeconds(29), utc, now))
        assertEquals("1 min ago", MembersWords.timeAgo(now.minusSeconds(30), utc, now))              // rounded as JavaScript rounds
        assertEquals("59 min ago", MembersWords.timeAgo(now.minusSeconds(59 * 60), utc, now))
        assertEquals("2 h ago", MembersWords.timeAgo(now.minusSeconds(90 * 60), utc, now))
        assertEquals("Oct 11", MembersWords.timeAgo(now.minusSeconds(24 * 3600), utc, now))
        assertEquals("expires today", MembersWords.expiry("2026-10-12T23:00:00", utc, now))
        assertEquals("expires tomorrow", MembersWords.expiry("2026-10-13T08:00:00", utc, now))        // the web says "today": within a day
        assertEquals("expires in 6 days", MembersWords.expiry("2026-10-18T10:00:00", utc, now))
        assertEquals("expired", MembersWords.expiry("2026-10-12T13:00:00", utc, now))
        assertEquals("Invited Oct 4 as Editor · expires in 6 days", MembersWords.pending(lopez, utc, now))
        assertEquals("Oct 12 at 10:00", MembersWords.lastSeen(sam, utc, now))
        assertEquals("never", MembersWords.lastSeen(marcus, utc, now))
        // The calendar is the person's: 14:00 UTC is 10:00 in New York, where 02:00 UTC tomorrow is still today.
        val ny = ZoneId.of("America/New_York")
        assertEquals("expires today", MembersWords.expiry("2026-10-13T02:00:00", ny, now))
        assertEquals("expires tomorrow", MembersWords.expiry("2026-10-14T02:00:00", ny, now))
        assertEquals("Oct 12 at 06:00", MembersWords.lastSeen(sam, ny, now))
    }
}
