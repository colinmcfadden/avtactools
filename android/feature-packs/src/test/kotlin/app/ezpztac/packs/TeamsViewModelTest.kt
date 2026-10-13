package app.ezpztac.packs

import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackState
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.NetworkException
import app.ezpztac.network.Team
import app.ezpztac.network.TeamMember
import app.ezpztac.network.TeamSummary
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Teams: the list, a team's page, a new team and its name, inviting, roles, taking off, the invitations waiting, leaving and deleting, and the account it is for. */
@OptIn(ExperimentalCoroutinesApi::class)
class TeamsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val me = 6

    private fun member(id: Int, name: String, role: String) = TeamMember(id, name, "${name.lowercase().replace(' ', '.')}@army.mil", role, "2026-09-01T10:00:00")

    private val colin = member(6, "Colin McFadden", "owner")
    private val sam = member(7, "Sam Bell", "admin")
    private val jess = member(8, "Jess Reyes", "member")
    private val lopez = Invite(5, "sgt.lopez@army.mil", "member", "pending", null, "2026-10-18T10:00:00", "2026-10-04T10:00:00")

    private class FakeDoor(user: Int?) : PackDoor {
        override val user = MutableStateFlow(user)
        override val open = MutableStateFlow<PackState?>(null)
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
    }

    private inner class FakeApi : TeamsApi {
        val log = mutableListOf<String>()
        var teams = listOf(TeamSummary(3, "B Co 2-10 AVN", "owner", 3, "2026-09-01T10:00:00"), TeamSummary(4, "HHC", "member", 12, "2026-09-02T10:00:00"))
        var roles = mutableMapOf(3 to "owner", 4 to "member")
        var invites: List<Invite>? = listOf(lopez)
        var listFail: Exception? = null
        var listHold: CompletableDeferred<Unit>? = null
        var teamFail: Exception? = null
        var teamHold: CompletableDeferred<Unit>? = null
        var actFail: Exception? = null
        var actHold: CompletableDeferred<Unit>? = null
        var emailed = true
        var nextId = 9

        override suspend fun teams(user: Int): List<TeamSummary> {
            log += "teams as $user"
            val answer = teams
            val failing = listFail
            listHold?.await()
            failing?.let { throw it }
            return answer
        }

        override suspend fun team(id: Int, user: Int): TeamPage {
            log += "team $id as $user"
            val role = roles[id] ?: "member"
            val name = teams.firstOrNull { it.id == id }?.name ?: "NEW"
            val members = if (id == 3) listOf(colin, sam, jess) else listOf(colin.copy(role = role), sam.copy(role = "owner"))
            val answer = TeamPage(Team(id, name, role, members.size, "2026-09-01T10:00:00", members), if (role == "owner" || role == "admin") invites else emptyList())
            val failing = teamFail
            teamHold?.await()
            failing?.let { throw it }
            return answer
        }

        private suspend fun act(what: String) {
            log += what
            actHold?.await()
            actFail?.let { throw it }
        }

        override suspend fun createTeam(name: String, user: Int): Team {
            act("create $name as $user")
            return Team(nextId, name, "owner", 1, "2026-10-12T10:00:00", listOf(colin))
        }
        override suspend fun renameTeam(id: Int, name: String, user: Int) = act("rename $id to $name as $user")
        override suspend fun deleteTeam(id: Int, user: Int) {
            act("delete $id as $user")
            teams = teams.filter { it.id != id }
        }
        override suspend fun changeMember(id: Int, userId: Int, role: String, user: Int) {
            act("change $userId to $role in $id as $user")
            if (role == "owner") roles[id] = "admin"                                                  // handed over: the owner is an admin now
        }
        override suspend fun removeMember(id: Int, userId: Int, user: Int) {
            act("remove $userId from $id as $user")
            if (userId == user) teams = teams.filter { it.id != id }
        }
        override suspend fun invite(id: Int, email: String, role: String, user: Int): Boolean {
            act("invite $email as $role to $id as $user")
            return emailed
        }
        override suspend fun revokeInvite(id: Int, inviteId: Int, user: Int) = act("revoke $inviteId in $id as $user")
    }

    private inner class Rig(scope: TestScope, user: Int? = me) {
        val api = FakeApi()
        val door = FakeDoor(user)
        val model = TeamsViewModel(api, door)
        val notices = mutableListOf<String>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.notices.collect { notices += it } }
        }
    }

    private fun TestScope.opened(setUp: FakeApi.() -> Unit = {}) = Rig(this).also {
        it.api.setUp()
        advanceUntilIdle()
        it.model.open()
        advanceUntilIdle()
    }

    // -- The list and a team's page --------------------------------------------------------------

    @Test
    fun `the page opens on the person's teams with the first shown, its members and, for its owner, the invitations waiting`() = runTest(dispatcher) {
        val r = opened()
        val s = r.model.state.value
        assertTrue(s.open && s.loaded)
        assertEquals(listOf(3, 4), s.teams.map { it.id })
        assertEquals(3, s.chosen)
        assertFalse(s.detail)                                                                         // a phone shows the list first
        assertEquals(listOf(6, 7, 8), s.team!!.members.map { it.userId })
        assertEquals(listOf(5), s.page!!.invites!!.map { it.id })
        assertTrue(s.owner && s.manager)
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log)
        r.model.choose(4)
        advanceUntilIdle()
        assertTrue(r.model.state.value.detail)
        assertEquals("member", r.model.state.value.team!!.role)
        assertFalse(r.model.state.value.manager)
        assertTrue(r.model.state.value.page!!.invites!!.isEmpty())
        r.model.back()
        assertFalse(r.model.state.value.detail)
        r.model.choose(99)                                                                            // not one of theirs
        assertEquals(4, r.model.state.value.chosen)
    }

    @Test
    fun `nothing opens with nobody signed in, and another account puts the page away`() = runTest(dispatcher) {
        val r = Rig(this, user = null)
        r.model.open()
        assertFalse(r.model.state.value.open)
        val o = opened()
        o.door.user.value = 8
        advanceUntilIdle()
        assertFalse(o.model.state.value.open)
    }

    @Test
    fun `teams or a team that could not be had are said with a way to try again`() = runTest(dispatcher) {
        val r = Rig(this)
        r.api.listFail = NetworkException("down", requestMayHaveBeenSent = false)
        r.model.open()
        advanceUntilIdle()
        assertEquals(TeamsViewModel.TEAMS_FAILED, r.model.state.value.listError)
        assertFalse(r.model.state.value.loaded)
        r.api.listFail = null
        r.api.teamFail = ApiException(500, null, "boom")
        r.model.loadList()
        advanceUntilIdle()
        assertNull(r.model.state.value.listError)
        assertEquals(TeamsViewModel.TEAM_FAILED, r.model.state.value.teamError)
        r.api.teamFail = null
        r.model.loadTeam()
        advanceUntilIdle()
        assertNull(r.model.state.value.teamError)
        assertEquals(3, r.model.state.value.team!!.id)
    }

    @Test
    fun `a team that has gone says so in a team's words, and the list is asked for again`() = runTest(dispatcher) {
        for (failure in listOf(ApiException(404, "team_not_found", "x"), ApiException(404, null, "x"))) {
            val r = opened()
            r.api.teamFail = failure
            val before = r.api.log.size
            r.model.loadTeam()
            advanceUntilIdle()
            assertEquals("That team is gone, or you are no longer in it.", r.model.state.value.teamError)
            assertEquals(listOf("team 3 as $me", "teams as $me"), r.api.log.drop(before).take(2))
        }
        val r = opened()
        r.api.teamFail = ApiException(403, "feature_disabled", "x")                                   // a refusal with words of its own says them
        r.model.loadTeam()
        advanceUntilIdle()
        assertEquals("Mission Packs are not turned on for your account yet.", r.model.state.value.teamError)
    }

    @Test
    fun `an older answer that comes after a newer one is not shown, nor a team's for another team chosen meanwhile`() = runTest(dispatcher) {
        val r = opened()
        val hold = CompletableDeferred<Unit>()
        r.api.listHold = hold
        r.model.loadList()
        advanceUntilIdle()
        r.api.teams = r.api.teams.take(1)
        r.api.listHold = null
        r.model.loadList()
        advanceUntilIdle()
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(3), r.model.state.value.teams.map { it.id })

        val o = opened()
        val teamHold = CompletableDeferred<Unit>()
        o.api.teamHold = teamHold
        o.model.loadTeam()                                                                            // team 3, slow
        advanceUntilIdle()
        o.api.teamHold = null
        o.model.choose(4)
        advanceUntilIdle()
        teamHold.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, o.model.state.value.team!!.id)

        val f = opened()
        val failHold = CompletableDeferred<Unit>()
        f.api.teamHold = failHold
        f.api.teamFail = ApiException(500, null, "boom")
        f.model.loadTeam()                                                                            // team 3, slow, and failing
        advanceUntilIdle()
        f.api.teamHold = null
        f.api.teamFail = null
        f.model.choose(4)
        advanceUntilIdle()
        failHold.complete(Unit)
        advanceUntilIdle()
        assertNull(f.model.state.value.teamError)                                                     // not said over team 4
        assertEquals(4, f.model.state.value.team!!.id)
    }

    @Test
    fun `right after choosing, before its page has come, the team is not known and nothing is done to it`() = runTest(dispatcher) {
        val r = opened()
        r.api.teamHold = CompletableDeferred()
        r.model.choose(4)
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals(4, s.chosen)
        assertNull(s.team)                                                                            // team 3's page is not team 4's
        assertEquals("HHC", s.chosenName)
        assertFalse(s.manager)
        r.model.email("a@b.co")
        r.model.invite()
        r.model.rename()
        r.model.askDelete()
        r.model.askRevoke(lopez)
        r.model.role(jess, "admin")
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("invite") || it.startsWith("change") })
        assertNull(r.model.state.value.naming)
        assertNull(r.model.state.value.confirm)
    }

    @Test
    fun `a list without the team shown shows the first, back on the list`() = runTest(dispatcher) {
        val r = opened()
        r.model.choose(4)
        advanceUntilIdle()
        r.api.teams = r.api.teams.take(1)                                                             // taken off team 4 elsewhere
        r.model.loadList()
        advanceUntilIdle()
        assertEquals(3, r.model.state.value.chosen)
        assertFalse(r.model.state.value.detail)
        assertEquals(3, r.model.state.value.team!!.id)
        r.api.teams = emptyList()
        r.model.loadList()
        advanceUntilIdle()
        assertNull(r.model.state.value.chosen)
        assertNull(r.model.state.value.team)
        assertNull(r.model.state.value.teamError)
    }

    @Test
    fun `what was begun for one team is not carried to another`() = runTest(dispatcher) {
        val r = opened { roles[4] = "admin" }
        r.model.email("a@b.co")
        r.model.inviteRole("admin")
        r.model.email("nope")
        r.model.invite()
        r.model.askRemove(jess)
        assertTrue(r.model.state.value.confirm is TeamsConfirm.Remove)
        r.model.choose(4)
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("", s.email)
        assertNull(s.emailError)
        assertEquals("member", s.inviteRole)                                                          // an admin of team 4 invites members only
        assertNull(s.confirm)
        r.model.rename()
        assertEquals(4, r.model.state.value.naming!!.teamId)
        r.model.choose(3)
        advanceUntilIdle()
        assertNull(r.model.state.value.naming)                                                        // a rename is of one team
        r.model.newTeam()
        r.model.choose(4)
        advanceUntilIdle()
        assertEquals(TeamNaming(rename = false, name = ""), r.model.state.value.naming)               // a new team is nobody's yet
    }

    // -- A new team, and a name ------------------------------------------------------------------

    @Test
    fun `a new team is named, made, and shown at once before the list has it`() = runTest(dispatcher) {
        val r = opened()
        r.model.newTeam()
        r.model.name("   ")
        r.model.submitName()
        assertEquals("Give it a name.", r.model.state.value.naming!!.error)
        r.model.name("OP" + Char(0x07))
        r.model.submitName()
        assertEquals("Give it a name of 1 to 100 characters.", r.model.state.value.naming!!.error)
        r.model.name("A".repeat(101))
        assertEquals("OP" + Char(0x07), r.model.state.value.naming!!.name)                          // never typed past 100
        r.model.name(Char(0xFEFF) + " A Co 2-10 AVN ")
        r.api.listHold = CompletableDeferred()                                                        // the list that has it comes later
        r.model.submitName()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("create A Co 2-10 AVN as $me"))
        assertNull(r.model.state.value.naming)
        assertEquals(9, r.model.state.value.chosen)
        assertTrue(r.model.state.value.detail)
        assertEquals(listOf("Made A Co 2-10 AVN. You are its owner."), r.notices)
        assertEquals(1, r.api.log.count { it == "team 9 as $me" })                                    // asked for once
        r.api.listHold!!.complete(Unit)                                                               // an older list without it
        advanceUntilIdle()
        assertEquals(9, r.model.state.value.chosen)                                                  // missing for now is not gone
    }

    @Test
    fun `a team just made is let go once the list has had it`() = runTest(dispatcher) {
        val r = opened()
        r.model.newTeam()
        r.model.name("A Co")
        r.model.submitName()
        advanceUntilIdle()
        assertEquals(9, r.model.state.value.chosen)
        r.api.teams = r.api.teams + TeamSummary(9, "A Co", "owner", 1, "x")
        r.model.loadList()
        advanceUntilIdle()
        assertEquals(9, r.model.state.value.chosen)
        r.api.teams = r.api.teams.filter { it.id != 9 }                                               // deleted elsewhere since
        r.model.loadList()
        advanceUntilIdle()
        assertEquals(3, r.model.state.value.chosen)
    }

    @Test
    fun `a name is sent once, cannot be changed or put away while it is, and waits for a change under way`() = runTest(dispatcher) {
        val r = opened()
        r.model.newTeam()
        r.model.name("X")
        r.api.actHold = CompletableDeferred()
        r.model.submitName()
        r.model.submitName()
        advanceUntilIdle()
        r.model.name("Y")
        r.model.dismissName()
        assertEquals(TeamNaming(rename = false, name = "X", sending = true), r.model.state.value.naming)
        assertEquals(1, r.api.log.count { it.startsWith("create") })
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()

        val o = opened()
        o.api.actHold = CompletableDeferred()
        o.model.role(jess, "admin")
        advanceUntilIdle()
        o.model.newTeam()
        o.model.name("X")
        o.model.submitName()
        assertEquals(PackPanelViewModel.STILL_WORKING, o.notices.last())
        assertFalse(o.model.state.value.naming!!.sending)
        assertTrue(o.api.log.none { it.startsWith("create") })
    }

    @Test
    fun `a new team whose answer was lost may have been made, and is not made again by a word`() = runTest(dispatcher) {
        val r = opened()
        r.model.newTeam()
        r.model.name("HHC 2")
        r.api.actFail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.model.submitName()
        advanceUntilIdle()
        assertEquals(TeamsViewModel.LOST_TEAM, r.model.state.value.naming!!.error)
        assertFalse(r.model.state.value.naming!!.sending)
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log.takeLast(2))                  // asked again, so it can be found
        r.api.actFail = ApiException(400, "invalid_name", "x")
        r.model.submitName()
        advanceUntilIdle()
        assertEquals("Give it a name of 1 to 100 characters.", r.model.state.value.naming!!.error)
        r.api.actFail = ApiException(502, null, "x")
        r.model.submitName()
        advanceUntilIdle()
        assertEquals(TeamsViewModel.LOST_TEAM, r.model.state.value.naming!!.error)

        val o = opened()                                                                              // a rename is repeatable: no doubt to raise
        o.model.rename()
        o.model.name("B CO")
        o.api.actFail = ApiException(502, null, "x")
        o.model.submitName()
        advanceUntilIdle()
        assertEquals("Something went wrong on the server. Try again.", o.model.state.value.naming!!.error)
    }

    @Test
    fun `an answer is lost after the request went, or behind a gateway, never before it was sent`() {
        assertTrue(TeamsViewModel.lostAnswer(NetworkException("x", requestMayHaveBeenSent = true)))
        assertFalse(TeamsViewModel.lostAnswer(NetworkException("x", requestMayHaveBeenSent = false)))
        for (status in listOf(502, 504, 520, 522, 524)) assertTrue("$status", TeamsViewModel.lostAnswer(ApiException(status, null, "x")))
        for (status in listOf(500, 503, 519, 525, 409)) assertFalse("$status", TeamsViewModel.lostAnswer(ApiException(status, null, "x")))
    }

    @Test
    fun `a new team whose answer could not be read was made`() = runTest(dispatcher) {
        val r = opened()
        r.model.newTeam()
        r.model.name("A Co")
        r.api.actFail = ApiException(201, null, "unreadable")
        r.model.submitName()
        advanceUntilIdle()
        assertNull(r.model.state.value.naming)
        assertEquals("Made A Co. You are its owner.", r.notices.last())
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log.takeLast(2))
    }

    @Test
    fun `its owner or an admin renames a team, a member cannot`() = runTest(dispatcher) {
        val r = opened()
        r.model.rename()
        assertEquals(TeamNaming(rename = true, name = "B Co 2-10 AVN", teamId = 3), r.model.state.value.naming)
        r.model.name("B CO 2-10 AVN")
        r.model.submitName()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("rename 3 to B CO 2-10 AVN as $me"))
        assertEquals("Renamed B Co 2-10 AVN to B CO 2-10 AVN", r.notices.last())
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log.takeLast(2))
        r.model.choose(4)
        advanceUntilIdle()
        r.model.rename()
        assertNull(r.model.state.value.naming)
    }

    // -- Inviting ---------------------------------------------------------------------------------

    @Test
    fun `an address is invited, trimmed and in small letters, as a member or (the owner's choice) an admin`() = runTest(dispatcher) {
        val r = opened()
        r.model.email("nope")
        r.model.invite()
        assertEquals("Type their whole email address.", r.model.state.value.emailError)
        r.model.email("  Marcus.Webb@Army.mil ")
        assertNull(r.model.state.value.emailError)
        r.model.inviteRole("admin")
        r.model.invite()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("invite marcus.webb@army.mil as admin to 3 as $me"))
        assertEquals("Invited marcus.webb@army.mil to B Co 2-10 AVN", r.notices.last())
        assertEquals("", r.model.state.value.email)
        r.api.emailed = false
        r.model.email("a@b.co")
        r.model.invite()
        advanceUntilIdle()
        assertEquals("Invited a@b.co to B Co 2-10 AVN, but the email did not go. Withdraw it and invite them again.", r.notices.last())
    }

    @Test
    fun `an invitation refused is said under the field and what was typed stays, and an admin invites members only`() = runTest(dispatcher) {
        val r = opened()
        r.model.email("a@b.co")
        r.api.actFail = ApiException(409, "already_member", "x")
        r.model.invite()
        advanceUntilIdle()
        assertEquals("They are already in it.", r.model.state.value.emailError)
        assertEquals("a@b.co", r.model.state.value.email)                                             // the web clears it
        r.api.actFail = ApiException(400, "invalid_role", "x")
        r.model.invite()
        advanceUntilIdle()
        assertEquals("Only the team's owner can invite an admin.", r.model.state.value.emailError)

        val o = opened { roles[3] = "admin" }
        o.model.inviteRole("admin")
        assertEquals("member", o.model.state.value.inviteRole)
        o.model.email("a@b.co")
        o.model.invite()
        advanceUntilIdle()
        assertTrue(o.api.log.contains("invite a@b.co as member to 3 as $me"))
        o.model.choose(4)                                                                             // a member: no inviting
        advanceUntilIdle()
        o.model.email("c@d.co")
        o.model.invite()
        advanceUntilIdle()
        assertTrue(o.api.log.none { it.startsWith("invite c@d.co") })
    }

    @Test
    fun `an answer for a team no longer shown is said, never put on the page of the team shown`() = runTest(dispatcher) {
        val r = opened { roles[4] = "admin" }
        r.model.email("a@b.co")
        r.api.actHold = CompletableDeferred()
        r.api.actFail = ApiException(409, "already_member", "x")
        r.model.invite()
        advanceUntilIdle()
        r.model.choose(4)
        advanceUntilIdle()
        r.model.email("c@d.co")
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(r.model.state.value.emailError)
        assertEquals("They are already in it.", r.notices.last())

        r.api.actFail = null
        r.api.actHold = CompletableDeferred()
        r.model.invite()                                                                              // c@d.co to team 4
        advanceUntilIdle()
        r.model.choose(3)
        advanceUntilIdle()
        r.model.email("e@f.co")
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("e@f.co", r.model.state.value.email)                                             // not cleared for team 4's invitation
        assertEquals("Invited c@d.co to HHC", r.notices.last())
    }

    // -- Members ----------------------------------------------------------------------------------

    @Test
    fun `the owner makes someone an admin or a member, and handing the team over is asked first`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(jess, "admin")
        advanceUntilIdle()
        assertTrue(r.api.log.contains("change 8 to admin in 3 as $me"))
        assertEquals("Jess Reyes is an admin now", r.notices.last())
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log.takeLast(2))                  // every change asks again
        r.model.role(sam, "member")
        advanceUntilIdle()
        assertEquals("Sam Bell is a member now", r.notices.last())
        val before = r.api.log.size
        r.model.role(colin, "member")                                                                 // their own role is fixed
        r.model.role(jess, "member")                                                                  // what they are already
        r.model.role(jess, "viewer")
        advanceUntilIdle()
        assertEquals(before, r.api.log.size)
        r.model.role(sam, "owner")
        assertEquals(TeamsConfirm.HandOver(3, sam), r.model.state.value.confirm)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("change 7 to owner in 3 as $me"))
        assertEquals("Sam Bell owns B Co 2-10 AVN now", r.notices.last())
    }

    @Test
    fun `handing the team over makes this person an admin at once, before the team is read again`() = runTest(dispatcher) {
        val r = opened()
        r.model.inviteRole("admin")
        r.model.role(sam, "owner")
        r.api.teamHold = CompletableDeferred()
        r.model.confirm()
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("admin", s.team!!.role)
        assertEquals(listOf("admin", "owner", "member"), s.team!!.members.map { it.role })
        assertFalse(s.owner)
        assertEquals("member", s.inviteRole)
        assertFalse(s.removable(sam))
    }

    @Test
    fun `a question asked while the team was being handed over is let go once this person is not the owner`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(sam, "owner")
        r.api.actHold = CompletableDeferred()
        r.model.confirm()
        advanceUntilIdle()
        r.model.askDelete()                                                                           // still the owner while it is on its way
        assertEquals(TeamsConfirm.Delete(3), r.model.state.value.confirm)
        r.api.teamHold = CompletableDeferred()
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(r.model.state.value.confirm)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("delete") })
    }

    @Test
    fun `a question no longer this person's to ask is let go once the team is read again, and not done`() = runTest(dispatcher) {
        val r = opened()
        r.model.role(sam, "owner")
        r.api.roles[3] = "admin"                                                                      // handed over from another device
        r.model.loadTeam()
        advanceUntilIdle()
        assertNull(r.model.state.value.confirm)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("change") })

        val o = opened()
        o.model.askRevoke(lopez)                                                                      // an invitation: still theirs to withdraw as an admin
        o.api.roles[3] = "admin"
        o.model.loadTeam()
        advanceUntilIdle()
        assertEquals(TeamsConfirm.Revoke(3, lopez), o.model.state.value.confirm)
    }

    @Test
    fun `a question is done only to the team it was asked about and only while it is still theirs to do`() {
        val team = Team(3, "B", "owner", 3, "x", listOf(colin, sam, jess))
        val owner = TeamsUi(open = true, me = 6, chosen = 3, page = TeamPage(team, emptyList()))
        assertTrue(owner.allows(TeamsConfirm.HandOver(3, sam)))
        assertFalse(owner.allows(TeamsConfirm.HandOver(4, sam)))                                      // another team
        assertFalse(owner.allows(TeamsConfirm.HandOver(3, colin)))                                    // themselves
        assertTrue(owner.allows(TeamsConfirm.Delete(3)))
        assertFalse(owner.allows(TeamsConfirm.Leave(3)))                                              // the owner hands over first
        assertTrue(owner.allows(TeamsConfirm.Remove(3, jess)))
        assertTrue(owner.allows(TeamsConfirm.Revoke(3, lopez)))
        val admin = owner.copy(me = 7, page = TeamPage(team.copy(role = "admin"), emptyList()))
        assertFalse(admin.allows(TeamsConfirm.HandOver(3, jess)))
        assertFalse(admin.allows(TeamsConfirm.Delete(3)))
        assertTrue(admin.allows(TeamsConfirm.Leave(3)))
        assertTrue(admin.allows(TeamsConfirm.Remove(3, jess)))
        assertFalse(admin.allows(TeamsConfirm.Remove(3, sam.copy(userId = 10))))
        assertTrue(admin.allows(TeamsConfirm.Revoke(3, lopez)))
        val plain = owner.copy(me = 8, page = TeamPage(team.copy(role = "member"), emptyList()))
        assertFalse(plain.allows(TeamsConfirm.Revoke(3, lopez)))
        assertFalse(plain.allows(TeamsConfirm.Remove(3, jess.copy(userId = 10))))
        assertTrue(plain.allows(TeamsConfirm.Leave(3)))
        assertFalse(owner.copy(chosen = 4).allows(TeamsConfirm.Delete(3)))                            // its page is not the team shown's
    }

    @Test
    fun `only the owner changes roles, only its owner or an admin withdraws an invitation`() = runTest(dispatcher) {
        val r = opened { roles[3] = "admin" }
        r.model.role(jess, "admin")
        r.model.role(jess, "owner")
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("change") })
        assertNull(r.model.state.value.confirm)
        r.model.askDelete()
        assertNull(r.model.state.value.confirm)
        r.model.askRevoke(lopez)
        assertEquals(TeamsConfirm.Revoke(3, lopez), r.model.state.value.confirm)
        r.model.dismissConfirm()
        r.model.choose(4)
        advanceUntilIdle()
        r.model.askRevoke(lopez)                                                                      // a member
        assertNull(r.model.state.value.confirm)
    }

    @Test
    fun `who can be taken off is the server's rule, and it is asked first`() = runTest(dispatcher) {
        val r = opened()
        val s = r.model.state.value
        assertTrue(s.removable(sam) && s.removable(jess))                                             // the owner: anyone else
        assertFalse(s.removable(colin))                                                               // never themselves or the owner
        r.model.askRemove(colin)
        assertNull(r.model.state.value.confirm)
        r.model.askRemove(jess)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("remove 8 from 3 as $me"))
        assertEquals("Jess Reyes is no longer in B Co 2-10 AVN", r.notices.last())

        val admin = TeamsUi(me = 7, chosen = 3, page = TeamPage(Team(3, "B", "admin", 3, "x", listOf(colin, sam, jess)), emptyList()))
        assertTrue(admin.removable(jess))
        assertFalse(admin.removable(colin))
        assertFalse(admin.removable(sam.copy(userId = 10)))                                           // an admin removes members only
        val plain = TeamsUi(me = 8, chosen = 3, page = TeamPage(Team(3, "B", "member", 3, "x", listOf(colin, sam, jess)), emptyList()))
        assertFalse(plain.removable(sam.copy(role = "member", userId = 10)))
    }

    @Test
    fun `withdrawing an invitation is asked first`() = runTest(dispatcher) {
        val r = opened()
        r.model.askRevoke(lopez)
        assertEquals(TeamsConfirm.Revoke(3, lopez), r.model.state.value.confirm)
        r.model.dismissConfirm()
        assertNull(r.model.state.value.confirm)
        r.model.askRevoke(lopez)
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("revoke 5 in 3 as $me"))
        assertEquals("Withdrew the invitation to sgt.lopez@army.mil", r.notices.last())
    }

    @Test
    fun `leaving a team or deleting it is asked first, and takes it off the list with the next one shown`() = runTest(dispatcher) {
        val r = opened()
        r.model.askLeave()                                                                            // the owner cannot leave
        assertNull(r.model.state.value.confirm)
        r.model.choose(4)
        advanceUntilIdle()
        r.model.askDelete()                                                                           // nor can a member delete
        assertNull(r.model.state.value.confirm)
        r.model.askLeave()
        r.api.listHold = CompletableDeferred()
        r.model.confirm()
        advanceUntilIdle()
        assertTrue(r.api.log.contains("remove $me from 4 as $me"))
        assertEquals("You left HHC", r.notices.last())
        assertEquals(listOf(3), r.model.state.value.teams.map { it.id })
        assertEquals(3, r.model.state.value.chosen)
        assertFalse(r.model.state.value.detail)
        assertEquals(3, r.model.state.value.team!!.id)
        r.api.listHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(3), r.model.state.value.teams.map { it.id })

        val o = opened()
        o.model.askDelete()
        o.model.confirm()
        advanceUntilIdle()
        assertTrue(o.api.log.contains("delete 3 as $me"))
        assertEquals("Deleted B Co 2-10 AVN", o.notices.last())
        assertEquals(listOf(4), o.model.state.value.teams.map { it.id })
        assertEquals(4, o.model.state.value.team!!.id)
    }

    @Test
    fun `leaving one team while another is shown keeps the one shown`() = runTest(dispatcher) {
        val r = opened()
        r.model.choose(4)
        advanceUntilIdle()
        r.model.askLeave()
        r.api.actHold = CompletableDeferred()
        r.model.confirm()
        advanceUntilIdle()
        r.model.choose(3)
        advanceUntilIdle()
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, r.model.state.value.chosen)
        assertTrue(r.model.state.value.detail)
        assertEquals(3, r.model.state.value.team!!.id)
        assertEquals(listOf(3), r.model.state.value.teams.map { it.id })
    }

    @Test
    fun `a change whose answer could not be read was made`() = runTest(dispatcher) {
        val r = opened()
        r.api.actFail = ApiException(200, null, "unreadable")
        r.model.role(jess, "admin")
        advanceUntilIdle()
        assertEquals(TeamsViewModel.DONE_UNREAD, r.notices.last())
        r.model.email("a@b.co")
        r.model.invite()
        advanceUntilIdle()
        assertEquals("", r.model.state.value.email)                                                   // done: the field empties
        assertNull(r.model.state.value.emailError)
    }

    // -- One at a time, for one account ---------------------------------------------------------

    @Test
    fun `one change at a time, landing only on the page it was asked from, and never said to another account`() = runTest(dispatcher) {
        val r = opened()
        r.model.email("a@b.co")
        r.api.actHold = CompletableDeferred()
        r.api.actFail = ApiException(409, "already_member", "x")
        r.model.invite()
        advanceUntilIdle()
        r.model.role(jess, "admin")
        assertEquals(PackPanelViewModel.STILL_WORKING, r.notices.last())
        r.model.close()
        r.model.open()
        advanceUntilIdle()
        assertTrue(r.model.state.value.busy)
        r.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.state.value.busy)
        assertNull(r.model.state.value.emailError)                                                   // not under the new page's field
        assertEquals("They are already in it.", r.notices.last())
        assertEquals(listOf("teams as $me", "team 3 as $me"), r.api.log.takeLast(2))                  // the page open asks again

        val o = opened()
        o.api.actHold = CompletableDeferred()
        o.model.role(jess, "admin")
        advanceUntilIdle()
        o.door.user.value = 8
        advanceUntilIdle()
        val asked = o.api.log.size
        o.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(o.notices.isEmpty())
        assertEquals(asked, o.api.log.size)                                                           // nothing asked for a page put away

        val c = opened()
        c.api.actHold = CompletableDeferred()
        c.model.role(jess, "admin")
        advanceUntilIdle()
        c.model.close()
        val before = c.api.log.size
        c.api.actHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(before, c.api.log.size)
        assertEquals("Jess Reyes is an admin now", c.notices.last())                                  // still said: the same account
    }

    // -- Words ------------------------------------------------------------------------------------

    @Test
    fun `a team and its people are said in the web's words`() {
        val utc = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-12T14:00:00Z")
        assertEquals("3 members · Owner", TeamsWords.listMeta(TeamSummary(3, "B", "owner", 3, "x")))
        assertEquals("1 member · Member", TeamsWords.listMeta(TeamSummary(3, "B", "member", 1, "x")))
        assertEquals("3 members · you are the owner · since Sep 1", TeamsWords.meta(Team(3, "B", "owner", 3, "2026-09-01T10:00:00", listOf(colin, sam, jess)), utc, now))
        assertEquals("1 member · you are an admin", TeamsWords.meta(Team(3, "B", "admin", 1, "x", listOf(sam)), utc, now))
        assertEquals("jess.reyes@army.mil · joined Sep 1", TeamsWords.joined(jess, utc, now))
        assertEquals("Invited Oct 4 as member · expires in 6 days", TeamsWords.pending(lopez, utc, now))
        assertEquals("Invited Oct 4 as admin · expires in 6 days", TeamsWords.pending(lopez.copy(role = "admin"), utc, now))
    }

    @Test
    fun `a refusal about a team is a team's words, not a pack's`() {
        assertEquals("Only the team's owner can do that.", TeamsViewModel.teamWords(PackFailure(403, "owner_only")))
        assertEquals("Only the team's owner can invite an admin.", TeamsViewModel.teamWords(PackFailure(400, "invalid_role")))
        assertEquals("That team is gone, or you are no longer in it.", TeamsViewModel.teamWords(PackFailure(404)))
        assertEquals("They are no longer a member.", TeamsViewModel.teamWords(PackFailure(404, "member_not_found")))
        assertEquals("They are already in it.", TeamsViewModel.teamWords(PackFailure(409, "already_member")))
        assertEquals("Something went wrong on the server. Try again.", TeamsViewModel.teamWords(PackFailure(500)))
    }
}
