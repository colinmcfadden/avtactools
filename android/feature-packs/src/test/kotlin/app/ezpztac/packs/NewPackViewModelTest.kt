package app.ezpztac.packs

import app.ezpztac.missionpacks.PackState
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.UserBrief
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/** New Mission Pack: the form, the teammate search, making the pack under one uuid, bringing people in, and an account that changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewPackViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val me = 7

    private class FakeDoor(user: Int?) : PackDoor {
        override val user = MutableStateFlow(user)
        override val open = MutableStateFlow<PackState?>(null)
        override suspend fun openPack(uuid: String) = Unit
        override suspend fun closePack() = Unit
    }

    /** The server: what was asked and for whom, each answer held while [hold] is set and refused as set. */
    private class FakeApi : PeopleApi {
        val log = mutableListOf<String>()
        var teams = listOf(TeamSummary(3, "B Co 2-10 AVN", "member", 18, "x"), TeamSummary(4, "HHC", "owner", 1, "x"))
        var teamsFail: Exception? = null
        var teamsHold: CompletableDeferred<Unit>? = null
        var people = listOf(UserBrief(11, "Sam Bell", "sam.bell@army.mil"), UserBrief(12, "Jess Reed", "jess.reed@army.mil"))
        var searchFail: Exception? = null
        var createFail: Exception? = null
        var createHold: CompletableDeferred<Unit>? = null
        /** What the server has under a uuid already (a try whose answer was lost). */
        var existing: PackCreated? = null
        var updateFail: Exception? = null
        val refuse = mutableMapOf<String, Exception>()
        val notEmailed = mutableSetOf<String>()

        override suspend fun teams(user: Int): List<TeamSummary> {
            log += "teams as $user"
            teamsHold?.await()
            teamsFail?.let { throw it }
            return teams
        }

        override suspend fun searchPeople(query: String, user: Int): List<UserBrief> {
            log += "search $query as $user"
            searchFail?.let { throw it }
            return people.filter { it.name.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true) }
        }

        override suspend fun createPack(name: String, description: String, team: TeamPick?, uuid: String, user: Int): PackCreated {
            log += "create $name|$description|$team|$uuid as $user"
            createHold?.await()
            createFail?.let { throw it }
            existing?.takeIf { it.uuid == uuid }?.let { return it.copy(created = false) }
            return PackCreated(uuid, name, description, team, created = true)
        }

        override suspend fun updatePack(uuid: String, change: PackChange, user: Int) {
            log += "update $uuid $change as $user"
            updateFail?.let { throw it }
        }

        override suspend fun addMember(uuid: String, userId: Int, role: String, user: Int) {
            log += "add $userId as $role to $uuid as $user"
            refuse["$userId"]?.let { throw it }
        }

        override suspend fun invite(uuid: String, email: String, role: String, user: Int): Boolean {
            log += "invite $email as $role to $uuid as $user"
            refuse[email]?.let { throw it }
            return email !in notEmailed
        }

        // Members' calls: New pack never makes them.
        override suspend fun people(uuid: String, user: Int): PackPeople = error("not asked")
        override suspend fun changeMember(uuid: String, userId: Int, role: String, user: Int) = error("not asked")
        override suspend fun removeMember(uuid: String, userId: Int, user: Int) = error("not asked")
        override suspend fun resendInvite(uuid: String, inviteId: Int, user: Int): Boolean = error("not asked")
        override suspend fun revokeInvite(uuid: String, inviteId: Int, user: Int) = error("not asked")
    }

    private class Rig(val scope: TestScope, user: Int?) {
        val api = FakeApi()
        val door = FakeDoor(user)
        val model = NewPackViewModel(api, door).also { m ->
            var n = 0
            m.newUuid = { "uuid-${++n}" }
        }
        val made = mutableListOf<String>()
        val withLibrary = mutableListOf<String>()
        val notices = mutableListOf<String>()

        init {
            // Listening at once: advanceUntilIdle never runs the background scope's own work, and a pack made with nobody listening is not kept.
            val now = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(now) { model.made.collect { made += it } }
            scope.backgroundScope.launch(now) { model.withLibrary.collect { withLibrary += it } }
            scope.backgroundScope.launch(now) { model.notices.collect { notices += it } }
        }
    }

    private fun TestScope.rig(user: Int? = me) = Rig(this, user).also { advanceUntilIdle() }

    private fun TestScope.opened(user: Int? = me) = rig(user).also { it.model.start(); advanceUntilIdle() }

    // -- The form ---------------------------------------------------------------------------------

    @Test
    fun `a pack made to start with items from the Library says so after it is made, and one not does not`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.withLibrary(true)
        assertTrue(r.model.state.value.withLibrary)
        r.model.create()
        assertTrue(r.model.state.value.busy)
        r.model.withLibrary(false)                                                                  // not while it is made
        assertTrue(r.model.state.value.withLibrary)
        advanceUntilIdle()
        assertEquals(listOf("uuid-1"), r.made)
        assertEquals(listOf("uuid-1"), r.withLibrary)

        val o = opened()
        o.model.name("OP EAGLE")
        o.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-1"), o.made)
        assertTrue(o.withLibrary.isEmpty())
        o.model.withLibrary(true)                                                                   // nothing to set with the form put away
        assertFalse(o.model.state.value.withLibrary)
    }

    @Test
    fun `the form opens empty, and someone in a team shares with their first team, as an editor, by default`() = runTest(dispatcher) {
        val r = opened()
        val s = r.model.state.value
        assertTrue(s.open)
        assertEquals(Audience.TEAM, s.audience)
        assertEquals(3, s.teamId)
        assertEquals("editor", s.teamRole)
        assertEquals("18 members", s.team!!.count)
        assertEquals("1 member", s.teams[1].count)
        assertNull(s.teamUnavailable)
        assertEquals(listOf("teams as $me"), r.api.log)
        assertEquals("Also invite by name or email", s.inviteLabel)
    }

    @Test
    fun `someone in no team keeps Just me, and cannot choose a team, told why`() = runTest(dispatcher) {
        val r = rig()
        r.api.teams = emptyList()
        r.model.start()
        advanceUntilIdle()
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
        r.model.audience(Audience.TEAM)
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
        assertEquals(NewPackUi.NO_TEAM, r.model.state.value.teamUnavailable)
    }

    @Test
    fun `teams that could not be had leave Just me and say so, never that there are none`() = runTest(dispatcher) {
        val r = rig()
        r.api.teamsFail = NetworkException("down", requestMayHaveBeenSent = false)
        r.model.start()
        advanceUntilIdle()
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
        assertEquals(NewPackUi.TEAMS_FAILED, r.model.state.value.teamUnavailable)
        r.model.audience(Audience.TEAM)
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
    }

    @Test
    fun `teams that come late are the default unless the person has chosen, and a chosen team is the one sent`() = runTest(dispatcher) {
        val r = rig()
        r.api.teamsHold = CompletableDeferred()
        r.model.start()
        advanceUntilIdle()
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
        assertNull(r.model.state.value.teamUnavailable)                                              // not known yet: nothing said
        r.api.teamsHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(Audience.TEAM, r.model.state.value.audience)                                     // the web leaves a late list unshared

        r.model.close()
        r.api.teamsHold = CompletableDeferred()
        r.model.start()
        advanceUntilIdle()
        r.model.audience(Audience.PEOPLE)
        r.api.teamsHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(Audience.PEOPLE, r.model.state.value.audience)                                   // their choice stands
        assertEquals("Invite by name or email", r.model.state.value.inviteLabel)

        r.model.close()                                                                              // and is this form's only
        r.model.start()
        advanceUntilIdle()
        assertEquals(Audience.TEAM, r.model.state.value.audience)
    }

    @Test
    fun `teams that come while the pack is made change nothing, so a try again is made as first asked`() = runTest(dispatcher) {
        val r = rig()
        r.api.teamsHold = CompletableDeferred()
        r.model.start()
        advanceUntilIdle()
        r.model.name("OP DK")
        r.api.createHold = CompletableDeferred()
        r.api.createFail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.model.create()                                                                             // Just me, as shown
        advanceUntilIdle()
        r.api.teamsHold!!.complete(Unit)
        advanceUntilIdle()
        r.api.createHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(Audience.JUST_ME, r.model.state.value.audience)
        assertTrue(r.model.state.value.teamsLoaded)
        r.api.createFail = null
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("create OP DK||null|uuid-1 as $me", "create OP DK||null|uuid-1 as $me"), r.api.log.filter { it.startsWith("create") })
    }

    @Test
    fun `opening twice keeps what is typed, and no form opens with nobody to make it for`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("op dk")
        r.model.start()
        assertEquals("OP DK", r.model.state.value.name)
        assertFalse(rig(user = null).also { it.model.start() }.model.state.value.open)
    }

    @Test
    fun `the name is in capitals and at most 100 characters, though one too long can be made shorter, and the description at most 2000`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("op dk")
        assertEquals("OP DK", r.model.state.value.name)
        val heli = String(Character.toChars(0x1F681))
        r.model.name("A".repeat(99) + heli)
        assertEquals(100, r.model.state.value.name.codePointCount(0, r.model.state.value.name.length))   // 101 UTF-16 units, 100 characters
        r.model.name("A".repeat(99) + heli + "B")
        assertEquals("A".repeat(99) + heli, r.model.state.value.name)
        r.model.description("x".repeat(2000))
        r.model.description("x".repeat(2001))
        assertEquals(2000, r.model.state.value.description.length)
    }

    @Test
    fun `a team is chosen only from the person's own, and a role is an editor or a viewer`() = runTest(dispatcher) {
        val r = opened()
        r.model.team(4)
        assertEquals("HHC", r.model.state.value.team!!.name)
        r.model.team(99)
        assertEquals(4, r.model.state.value.teamId)
        r.model.teamRole("viewer")
        r.model.teamRole("owner")
        assertEquals("viewer", r.model.state.value.teamRole)
    }

    // -- People ----------------------------------------------------------------------------------

    @Test
    fun `teammates are looked for a moment after typing stops, from two characters, leaving out those chosen`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("s")
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("search") })
        r.model.query("sa")
        advanceTimeBy(NewPackViewModel.SEARCH_PAUSE_MS - 1)
        assertTrue(r.api.log.none { it.startsWith("search") })                                     // still typing
        r.model.query("sam")
        advanceUntilIdle()
        assertEquals(listOf("search sam as $me"), r.api.log.filter { it.startsWith("search") })     // one search, for what was typed last
        assertEquals(listOf(11), r.model.state.value.found.map { it.id })

        r.model.pick(r.model.state.value.found.single())
        assertEquals("", r.model.state.value.query)
        assertEquals(listOf("user:11"), r.model.state.value.invitees.map { it.key })
        r.model.query("army")
        advanceUntilIdle()
        assertEquals(listOf(12), r.model.state.value.found.map { it.id })                            // Sam is chosen already
    }

    @Test
    fun `what is looked for is trimmed as the web trims it, and a search that fails finds nobody`() = runTest(dispatcher) {
        val r = opened()
        val spaced = Char(0xFEFF) + " " + Char(0xA0) + "sam" + Char(0x2003)
        r.model.query(spaced)
        advanceUntilIdle()
        assertEquals("search sam as $me", r.api.log.last())
        assertEquals(spaced, r.model.state.value.query)                                             // the field keeps what was typed
        r.api.searchFail = ApiException(500, null, "boom")
        r.model.query("jess")
        advanceUntilIdle()
        assertTrue(r.model.state.value.found.isEmpty())
    }

    @Test
    fun `an answer for what is no longer typed is not shown`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("sam")
        advanceTimeBy(NewPackViewModel.SEARCH_PAUSE_MS + 1)
        r.model.query("s")                                                                           // shorter than a search: nothing found
        advanceUntilIdle()
        assertTrue(r.model.state.value.found.isEmpty())
    }

    @Test
    fun `a whole email address is offered as an invitation, in small letters, and is never searched`() = runTest(dispatcher) {
        val r = opened()
        r.model.query("  Marcus.Webb@Army.mil ")
        advanceUntilIdle()
        assertEquals("marcus.webb@army.mil", r.model.state.value.typedEmail)
        assertTrue(r.api.log.none { it.startsWith("search") })
        listOf("a@b", "a b@c.d", "@b.c", "a@b.c" + Char(0xA0) + "x").forEach { r.model.query(it); assertNull(it, r.model.state.value.typedEmail) }
        r.model.query("Marcus.Webb@Army.mil")
        r.model.pickEmail()
        r.model.query("marcus.webb@army.mil")
        r.model.pickEmail()                                                                         // the same address once
        val invitee = r.model.state.value.invitees.single()
        assertEquals("marcus.webb@army.mil", invitee.label)
        assertNull(invitee.initials)
        assertEquals("", r.model.state.value.query)
    }

    @Test
    fun `someone chosen can be made a viewer and back, or taken off`() = runTest(dispatcher) {
        val r = opened()
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        assertEquals(1, r.model.state.value.invitees.size)
        assertEquals("SB", r.model.state.value.invitees.single().initials)
        r.model.role("user:11", "viewer")
        assertEquals("viewer", r.model.state.value.invitees.single().role)
        r.model.query("sam")
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))                                // chosen again: still a viewer, as on the web
        assertEquals("viewer", r.model.state.value.invitees.single().role)
        assertEquals("", r.model.state.value.query)
        r.model.role("user:11", "owner")
        assertEquals("viewer", r.model.state.value.invitees.single().role)
        r.model.remove("user:11")
        assertTrue(r.model.state.value.invitees.isEmpty())
    }

    // -- Making it -----------------------------------------------------------------------------

    @Test
    fun `a name that will not do is said at its field, and nothing is sent`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("   ")
        r.model.create()
        assertEquals("Give the pack a name.", r.model.state.value.nameError)
        r.model.name(Char(0xFEFF) + " " + Char(0xA0))                                                // blank as the web sees it: a byte order mark and spaces
        r.model.create()
        assertEquals("Give the pack a name.", r.model.state.value.nameError)
        r.model.name("OP" + Char(0x07) + "DK")
        r.model.create()
        assertEquals(PackPanelViewModel.NAME_REFUSED, r.model.state.value.nameError)
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("create") })
        r.model.name("OP DK")
        assertNull(r.model.state.value.nameError)                                                    // typing again takes it away
    }

    @Test
    fun `a pack shared with a team is made under the form's uuid, then each person is brought in, and it is handed over to be opened`() = runTest(dispatcher) {
        val r = opened()
        r.model.name(Char(0xFEFF) + "  op dk " + Char(0x3000))
        r.model.description(Char(0xFEFF) + "  North Georgia night LZ/PZ planning.  " + Char(0xA0))
        r.model.teamRole("viewer")
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        r.model.query("marcus.webb@army.mil")
        r.model.pickEmail()
        r.model.role("email:marcus.webb@army.mil", "viewer")
        r.model.create()
        assertTrue(r.model.state.value.busy)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "create OP DK|North Georgia night LZ/PZ planning.|TeamPick(teamId=3, role=viewer)|uuid-1 as $me",
                "add 11 as editor to uuid-1 as $me",
                "invite marcus.webb@army.mil as viewer to uuid-1 as $me",
            ),
            r.api.log.drop(1),
        )
        assertEquals(listOf("uuid-1"), r.made)
        assertEquals(listOf("Made OP DK. You are its owner."), r.notices)
        assertFalse(r.model.state.value.open)
    }

    @Test
    fun `Just me sends no team and nobody, though the people chosen before stay in the form`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("SOLO")
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        r.model.audience(Audience.JUST_ME)
        assertEquals(1, r.model.state.value.invitees.size)
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("create SOLO||null|uuid-1 as $me"), r.api.log.drop(1))
    }

    @Test
    fun `specific people send no team`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.audience(Audience.PEOPLE)
        r.model.pick(UserBrief(12, "Jess Reed", "jess.reed@army.mil"))
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("create OP DK||null|uuid-1 as $me", "add 12 as editor to uuid-1 as $me"), r.api.log.drop(1))
    }

    @Test
    fun `someone who could not be brought in is said with why, and the pack stands`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.audience(Audience.PEOPLE)
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        r.model.pick(UserBrief(12, "Jess Reed", "jess.reed@army.mil"))
        r.model.query("a@b.co")
        r.model.pickEmail()
        r.model.query("c@d.co")
        r.model.pickEmail()
        r.api.refuse["11"] = ApiException(409, "already_member", "x")
        r.api.refuse["a@b.co"] = RateLimitedException("x", 60)
        r.api.notEmailed += "c@d.co"
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-1"), r.made)
        assertEquals(
            listOf("Made OP DK. You are its owner.", "Could not add Sam Bell, a@b.co. Try again from Members.", "The email to c@d.co did not go. Send it again from Members."),
            r.notices,
        )
        assertTrue(r.api.log.contains("add 12 as editor to uuid-1 as $me"))                          // one refused stops nobody after them
    }

    @Test
    fun `a name typed and not chosen from the list is refused at its field, and nothing is sent`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.query("sam")
        advanceUntilIdle()
        r.model.create()
        advanceUntilIdle()
        assertEquals(NewPackViewModel.PICK_SOMEONE, r.model.state.value.peopleError)
        assertTrue(r.api.log.none { it.startsWith("create") })
        r.model.query("")                                                                             // typing again takes it away
        assertNull(r.model.state.value.peopleError)
        r.model.audience(Audience.JUST_ME)
        r.model.query("sam")                                                                          // Just me: the field is not looked at
        r.model.create()
        advanceUntilIdle()
        assertEquals(1, r.api.log.count { it.startsWith("create") })
    }

    @Test
    fun `a whole address typed and not added is invited, once`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.query("Marcus.Webb@Army.mil")
        r.model.create()
        advanceUntilIdle()
        assertEquals("invite marcus.webb@army.mil as editor to uuid-1 as $me", r.api.log.last())
        val again = opened()
        again.model.name("OP DK")
        again.model.query("a@b.co")
        again.model.pickEmail()
        again.model.role("email:a@b.co", "viewer")
        again.model.query("a@b.co")                                                                   // typed again: the one chosen stands, as a viewer
        again.model.create()
        advanceUntilIdle()
        assertEquals(listOf("invite a@b.co as viewer to uuid-1 as $me"), again.api.log.filter { it.startsWith("invite") })
    }

    @Test
    fun `one reason for everyone who could not be brought in is said once`() {
        assertNull(NewPackViewModel.failedWords(emptyList()))
        assertEquals(
            "Could not add Sam Bell: They are already in it. Try again from Members.",
            NewPackViewModel.failedWords(listOf("Sam Bell" to "They are already in it.")),
        )
        assertEquals(
            "Could not add Sam Bell, Jess Reed: Too many invitations for now. Try again later. Try again from Members.",
            NewPackViewModel.failedWords(listOf("Sam Bell" to "Too many invitations for now. Try again later.", "Jess Reed" to "Too many invitations for now. Try again later.")),
        )
        assertNull(NewPackViewModel.notEmailedWords(emptyList()))
    }

    @Test
    fun `a pack the server would not make is said at the name, and the next try is made under the same uuid`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.api.createFail = NetworkException("lost", requestMayHaveBeenSent = true)
        r.model.create()
        advanceUntilIdle()
        assertFalse(r.model.state.value.busy)
        assertTrue(r.model.state.value.open)
        assertEquals("There is no connection to the server. Try again when you are back online.", r.model.state.value.error)
        assertNull(r.model.state.value.nameError)                                                     // the name was fine
        assertTrue(r.made.isEmpty())
        r.api.createFail = null
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-1", "uuid-1"), r.api.log.filter { it.startsWith("create") }.map { it.substringAfterLast('|').substringBefore(' ') })
        assertEquals(listOf("uuid-1"), r.made)
    }

    @Test
    fun `a try that finds the pack an earlier one made sets on it what was changed since, and says it by that name`() = runTest(dispatcher) {
        val r = opened()
        r.api.existing = PackCreated("uuid-1", "OP DK", "", TeamPick(3, "editor"), created = true)
        r.model.name("OP DUKE")
        r.model.teamRole("viewer")
        r.model.create()
        advanceUntilIdle()
        assertEquals("update uuid-1 ${PackChange(name = "OP DUKE", setTeam = true, team = TeamPick(3, "viewer"))} as $me", r.api.log.last())
        assertEquals(listOf("Made OP DUKE. You are its owner."), r.notices)
    }

    @Test
    fun `a found pack is given only what differs, its sharing too, either way`() = runTest(dispatcher) {
        // Made for Just me, then asked again for a team: shared, and nothing else sent (each field sent is in the pack's history).
        val shared = opened()
        shared.api.existing = PackCreated("uuid-1", "OP DK", "", null, created = true)
        shared.model.name("OP DK")
        shared.model.create()
        advanceUntilIdle()
        assertEquals("update uuid-1 ${PackChange(setTeam = true, team = TeamPick(3, "editor"))} as $me", shared.api.log.last())
        // Made for a team, then asked again for Just me: no longer shared.
        val unshared = opened()
        unshared.api.existing = PackCreated("uuid-1", "OP DK", "", TeamPick(3, "editor"), created = true)
        unshared.model.name("OP DK")
        unshared.model.audience(Audience.JUST_ME)
        unshared.model.create()
        advanceUntilIdle()
        assertEquals("update uuid-1 ${PackChange(setTeam = true, team = null)} as $me", unshared.api.log.last())
        // Only what it is for.
        val described = opened()
        described.api.existing = PackCreated("uuid-1", "OP DK", "", TeamPick(3, "editor"), created = true)
        described.model.name("OP DK")
        described.model.description("Night LZ/PZ planning.")
        described.model.create()
        advanceUntilIdle()
        assertEquals("update uuid-1 ${PackChange(description = "Night LZ/PZ planning.")} as $me", described.api.log.last())
    }

    @Test
    fun `a try that finds the pack as it was asked changes nothing, and one whose change fails says the name it has`() = runTest(dispatcher) {
        val r = opened()
        r.api.existing = PackCreated("uuid-1", "OP DK", "", TeamPick(3, "editor"), created = true)
        r.model.name("OP DK")
        r.model.create()
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("update") })

        // A change that does not reach it keeps the form, said at the name, for the next try to set it; nobody is brought in before.
        val again = opened()
        again.api.existing = PackCreated("uuid-1", "OP DK", "", null, created = true)
        again.api.updateFail = NetworkException("down", requestMayHaveBeenSent = false)
        again.model.name("OP DUKE")
        again.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        again.model.create()
        advanceUntilIdle()
        assertTrue(again.made.isEmpty() && again.notices.isEmpty())
        assertTrue(again.model.state.value.open && !again.model.state.value.busy)
        assertEquals(
            "${NewPackViewModel.NOT_YET} There is no connection to the server. Try again when you are back online.",
            again.model.state.value.error,
        )
        assertTrue(again.api.log.none { it.startsWith("add") })
        again.api.updateFail = null
        again.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-1"), again.made)
        assertEquals(listOf("Made OP DUKE. You are its owner."), again.notices)
        assertEquals(2, again.api.log.count { it.startsWith("update") })
    }

    @Test
    fun `a uuid the server will not take is given up for the next try`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.api.createFail = ApiException(409, "uuid_taken", "That uuid is taken.")
        r.model.create()
        advanceUntilIdle()
        r.api.createFail = null
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-2"), r.made)
    }

    @Test
    fun `the next form makes another pack`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("ONE")
        r.model.create()
        advanceUntilIdle()
        r.model.start()
        advanceUntilIdle()
        r.model.name("TWO")
        r.model.create()
        advanceUntilIdle()
        assertEquals(listOf("uuid-1", "uuid-2"), r.made)
    }

    @Test
    fun `while it is made the form stays, cannot be put away and nothing in it changes`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.model.pick(UserBrief(11, "Sam Bell", "sam.bell@army.mil"))
        r.api.createHold = CompletableDeferred()
        r.model.create()
        advanceUntilIdle()
        r.model.close()
        r.model.create()                                                                             // a second tap is not a second try
        r.model.remove("user:11")
        r.model.role("user:11", "viewer")
        r.model.audience(Audience.JUST_ME)
        r.model.pick(UserBrief(12, "Jess Reed", "jess.reed@army.mil"))
        r.model.query("jess")
        advanceUntilIdle()
        val s = r.model.state.value
        assertEquals("", s.query)                                                                     // nothing is looked for either
        assertTrue(s.open && s.busy)
        assertEquals(listOf("user:11"), s.invitees.map { it.key })
        assertEquals("editor", s.invitees.single().role)
        assertEquals(Audience.TEAM, s.audience)
        r.api.createHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, r.api.log.count { it.startsWith("create") })
        assertTrue(r.api.log.none { it.startsWith("search") })
    }

    // -- Another account ------------------------------------------------------------------------

    @Test
    fun `another account puts the form away, and its teams, search and pack are never asked as them`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.door.user.value = 8
        advanceUntilIdle()
        assertFalse(r.model.state.value.open)
        r.model.create()
        r.model.query("sam")
        advanceUntilIdle()
        assertEquals(listOf("teams as $me"), r.api.log)                                               // nothing more, as anyone
    }

    @Test
    fun `a pack asked for the moment the account changed, before the form has heard, is not made`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.door.user.value = 8
        r.model.create()                                                                             // nothing has run since the change
        advanceUntilIdle()
        assertTrue(r.api.log.none { it.startsWith("create") })
        assertFalse(r.model.state.value.open)
    }

    @Test
    fun `a pack made while the account changed is neither opened nor said`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.api.createHold = CompletableDeferred()
        r.model.create()
        advanceUntilIdle()
        r.door.user.value = 8
        advanceUntilIdle()
        assertTrue(r.model.state.value.busy)                                                       // its answer is still to come
        r.api.createHold!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.made.isEmpty())
        assertTrue(r.notices.isEmpty())
        assertFalse(r.model.state.value.open)
    }

    @Test
    fun `a pack that failed while the account changed is not said to the next one`() = runTest(dispatcher) {
        val r = opened()
        r.model.name("OP DK")
        r.api.createHold = CompletableDeferred()
        r.api.createFail = ApiException(500, null, "boom")
        r.model.create()
        advanceUntilIdle()
        r.door.user.value = null
        r.api.createHold!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(r.model.state.value.open)
        assertNull(r.model.state.value.error)
    }
}
