package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.PackFacts
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.network.ApiException
import app.ezpztac.network.Invite
import app.ezpztac.network.NetworkException
import app.ezpztac.network.Team
import app.ezpztac.network.TeamMember
import app.ezpztac.network.TeamSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The calls Teams makes, each for the account asked for. */
interface TeamsApi {
    suspend fun teams(user: Int): List<TeamSummary>

    /** A team and its members, and for its owner or an admin its invitations not yet answered (null when they could not be had). */
    suspend fun team(id: Int, user: Int): TeamPage

    /** Makes a team [user] owns. Not repeatable: an answer lost on the way may have made it. */
    suspend fun createTeam(name: String, user: Int): Team

    suspend fun renameTeam(id: Int, name: String, user: Int)

    suspend fun deleteTeam(id: Int, user: Int)

    /** Makes member [userId] a `member`, an `admin`, or the `owner` (the team is handed over: its owner becomes an admin). */
    suspend fun changeMember(id: Int, userId: Int, role: String, user: Int)

    /** Takes member [userId] off the team, or (the caller's own id) leaves it. */
    suspend fun removeMember(id: Int, userId: Int, user: Int)

    /** Invites [email] to the team as [role]; false when the invitation was made but its email did not go. */
    suspend fun invite(id: Int, email: String, role: String, user: Int): Boolean

    suspend fun revokeInvite(id: Int, inviteId: Int, user: Int)
}

/** A team as its page shows it: the team, and its invitations waiting (none for a member; null when they could not be had). */
data class TeamPage(val team: Team, val invites: List<Invite>?)

/** What is asked before it is done, about the team it was asked on ([teamId]): a question is never put to another. */
sealed interface TeamsConfirm {
    val teamId: Int

    data class HandOver(override val teamId: Int, val member: TeamMember) : TeamsConfirm

    data class Remove(override val teamId: Int, val member: TeamMember) : TeamsConfirm

    data class Revoke(override val teamId: Int, val invite: Invite) : TeamsConfirm

    data class Leave(override val teamId: Int) : TeamsConfirm

    data class Delete(override val teamId: Int) : TeamsConfirm
}

/** A team's name being asked for: a new team, or ([teamId]) that team renamed. */
data class TeamNaming(val rename: Boolean, val name: String, val error: String? = null, val sending: Boolean = false, val teamId: Int? = null)

data class TeamsUi(
    val open: Boolean = false,
    val me: Int? = null,
    val teams: List<TeamSummary> = emptyList(),
    /** The list has answered for this account: empty then means in no team. */
    val loaded: Boolean = false,
    val listError: String? = null,
    /** The team shown, and (on a phone) whether its page is up over the list. */
    val chosen: Int? = null,
    val detail: Boolean = false,
    val page: TeamPage? = null,
    val teamError: String? = null,
    val email: String = "",
    val inviteRole: String = "member",
    val emailError: String? = null,
    val busy: Boolean = false,
    val confirm: TeamsConfirm? = null,
    val naming: TeamNaming? = null,
) {
    /** The team shown, once its page has come (never another team's page, while the chosen one's is on its way). */
    val team: Team? get() = page?.team?.takeIf { it.id == chosen }
    val manager: Boolean get() = team?.role == "owner" || team?.role == "admin"
    val owner: Boolean get() = team?.role == "owner"

    /** The team shown's name while its page is on its way: the list's. */
    val chosenName: String? get() = team?.name ?: teams.firstOrNull { it.id == chosen }?.name

    /** Whether [member] can be taken off by this person: never themselves or the owner; the owner takes off anyone else, an admin members only. */
    fun removable(member: TeamMember): Boolean =
        member.userId != me && member.role != "owner" && (owner || (team?.role == "admin" && member.role == "member"))

    /** Whether [asked] may still be done here: about the team shown, and still this person's to do. */
    fun allows(asked: TeamsConfirm): Boolean = asked.teamId == team?.id && when (asked) {
        is TeamsConfirm.HandOver -> owner && asked.member.userId != me
        is TeamsConfirm.Delete -> owner
        is TeamsConfirm.Remove -> removable(asked.member)
        is TeamsConfirm.Revoke -> manager
        is TeamsConfirm.Leave -> !owner
    }

    /**
     * Shows team [id]: what was begun for another (an address typed, its role, a question, a rename not on its way) goes with it, so none of it is ever done to the
     * wrong team.
     */
    fun showing(id: Int?, detail: Boolean = this.detail): TeamsUi = if (id == chosen) {
        copy(detail = detail)
    } else {
        copy(
            chosen = id, detail = detail, email = "", emailError = null, inviteRole = "member", teamError = null,
            confirm = null, naming = naming?.takeIf { !it.rename || it.sending },
        )
    }
}

/**
 * Teams (the web's TeamsDialog; no native design): the person's teams, and the one chosen with its members and roles, inviting by email, the invitations waiting,
 * renaming, and leaving or deleting it; and a new team. What each role may do is the server's: the owner everything, an admin inviting and taking members off, a
 * member leaving. One change at a time (a new team too), for the account the page was opened for, landing only on the page and the team it was asked from; what it
 * came to is said only to that account, and another account puts the page away.
 */
@HiltViewModel
class TeamsViewModel @Inject constructor(private val api: TeamsApi, private val door: PackDoor) : ViewModel() {
    private val _state = MutableStateFlow(TeamsUi())
    val state: StateFlow<TeamsUi> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** What a change came to, in words. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var pageUser: Int? = null
    private var page = 0
    private var listLoads = 0
    private var teamLoads = 0
    private var working: Job? = null

    // A team just made is chosen before the list that has it comes back: missing from the list for now is not gone.
    private var made: Int? = null

    init {
        viewModelScope.launch {
            door.user.collect { now -> if (_state.value.open && now != pageUser) shut() }
        }
    }

    /** Opens the page on the person's teams, the first chosen. */
    fun open() {
        val user = door.user.value ?: return
        if (_state.value.open && pageUser == user) return
        pageUser = user
        page++
        made = null
        _state.value = TeamsUi(open = true, me = user, busy = working?.isActive == true)
        loadList()
    }

    fun close() = shut()

    private fun shut() {
        page++
        listLoads++
        teamLoads++
        pageUser = null
        made = null
        _state.value = TeamsUi()
    }

    /** Asks for the person's teams again. The team chosen stays while it is in the list (or was just made); else the first is. */
    fun loadList() {
        val s = _state.value
        if (!s.open) return
        val user = pageUser ?: return
        val number = ++listLoads
        viewModelScope.launch {
            val teams = try {
                api.teams(user)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (number == listLoads && pageUser == user) _state.update { it.copy(listError = TEAMS_FAILED) }
                return@launch
            }
            if (number != listLoads || pageUser != user || door.user.value != user) return@launch
            val before = _state.value.chosen
            if (teams.any { it.id == made }) made = null                                               // the list has it now
            val keep = before?.takeIf { id -> id == made || teams.any { it.id == id } }
            val chosen = keep ?: teams.firstOrNull()?.id
            _state.update { it.showing(chosen, detail = it.detail && chosen == before).copy(teams = teams, loaded = true, listError = null) }
            if (chosen != before || _state.value.page?.team?.id != chosen) loadTeam()
        }
    }

    /** Shows team [id] (a phone puts its page up over the list). */
    fun choose(id: Int) {
        val s = _state.value
        if (!s.open || (s.teams.none { it.id == id } && id != made)) return
        _state.update { it.showing(id, detail = true) }
        if (_state.value.page?.team?.id != id) loadTeam()
    }

    /** A phone's page goes back to the list. */
    fun back() {
        _state.update { it.copy(detail = false) }
    }

    /**
     * Asks for the team shown, and its invitations for its owner or an admin. A team that has gone (deleted, or this person taken off) sends the list to be asked
     * again, which chooses another.
     */
    fun loadTeam() {
        val s = _state.value
        val id = s.chosen
        if (id == null) {
            _state.update { it.copy(teamError = null) }
            return
        }
        val user = pageUser ?: return
        val number = ++teamLoads
        _state.update { it.copy(teamError = null) }
        viewModelScope.launch {
            val answer = try {
                api.team(id, user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (number != teamLoads || pageUser != user || _state.value.chosen != id) return@launch
                val failure = PackFailure.of(e)
                val gone = failure.status == 404 || failure.code == "team_not_found"
                _state.update { it.copy(teamError = if (gone || failure.code != null) teamWords(failure) else TEAM_FAILED) }
                if (gone) loadList()
                return@launch
            }
            if (number != teamLoads || pageUser != user || door.user.value != user || _state.value.chosen != id) return@launch
            // A question asked of a role this person no longer has is let go.
            _state.update { st -> st.copy(page = answer, teamError = null).let { next -> next.copy(confirm = next.confirm?.takeIf(next::allows)) } }
        }
    }

    // -- Naming -----------------------------------------------------------------------------

    fun newTeam() {
        if (_state.value.open) _state.update { it.copy(naming = TeamNaming(rename = false, name = "")) }
    }

    fun rename() {
        val s = _state.value
        val team = s.team ?: return
        if (s.manager) _state.update { it.copy(naming = TeamNaming(rename = true, name = team.name, teamId = team.id)) }
    }

    /** The name as typed, never past 100 characters (one too long can always be made shorter). */
    fun name(typed: String) {
        _state.update { s ->
            val n = s.naming ?: return@update s
            if (n.sending || (characters(typed) > MAX_NAME && characters(typed) > characters(n.name))) s else s.copy(naming = n.copy(name = typed, error = null))
        }
    }

    fun dismissName() {
        _state.update { s -> if (s.naming?.sending == true) s else s.copy(naming = null) }
    }

    /**
     * Makes the team named (its owner), or renames the team it was asked for. A name that will not do is said in the dialog before anything is sent; a refusal there
     * too. A new team is chosen at once. Its answer lost, it may have been made (the call is not repeatable): said so, and the list asked for again. It is a change
     * like any: one at a time.
     */
    fun submitName() {
        val s = _state.value
        val naming = s.naming ?: return
        if (naming.sending) return
        val name = jsTrim(naming.name)
        val refused = when {
            name.isEmpty() -> "Give it a name."
            characters(name) > MAX_NAME || name.any { it.code < 0x20 || it.code == 0x7F } -> "Give it a name of 1 to 100 characters."
            else -> null
        }
        if (refused != null) {
            _state.update { it.copy(naming = naming.copy(error = refused)) }
            return
        }
        val user = pageUser ?: return
        if (working?.isActive == true) {
            say(user, PackPanelViewModel.STILL_WORKING)
            return
        }
        val renaming = if (naming.rename) {
            s.team?.takeIf { it.id == naming.teamId } ?: run {
                _state.update { it.copy(naming = null) }                                               // the team it was asked for is not the one shown
                return
            }
        } else {
            null
        }
        val asked = page
        _state.update { it.copy(naming = naming.copy(sending = true, error = null), busy = true) }
        working = viewModelScope.launch {
            try {
                if (renaming != null) {
                    api.renameTeam(renaming.id, name, user)
                    if (page == asked) _state.update { it.copy(naming = null) }
                    say(user, "Renamed ${renaming.name} to $name")
                } else {
                    val team = api.createTeam(name, user)
                    if (page == asked) {
                        made = team.id
                        _state.update { it.showing(team.id, detail = true).copy(naming = null) }
                    }
                    say(user, "Made $name. You are its owner.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.status in 200..299) {
                    // Done, but its answer could not be read: the list asked again shows it.
                    if (page == asked) _state.update { it.copy(naming = null) }
                    say(user, if (renaming != null) "Renamed ${renaming.name} to $name" else "Made $name. You are its owner.")
                } else {
                    val words = if (renaming == null && lostAnswer(e)) LOST_TEAM else teamWords(PackFailure.of(e))
                    if (page == asked) _state.update { st -> st.copy(naming = st.naming?.copy(sending = false, error = words)) } else say(user, words)
                }
            } finally {
                _state.update { if (it.open) it.copy(busy = false) else it }
                if (_state.value.open && pageUser == user) {
                    loadList()
                    loadTeam()
                }
            }
        }
    }

    // -- Inviting ---------------------------------------------------------------------------

    fun email(typed: String) {
        _state.update { it.copy(email = typed, emailError = null) }
    }

    fun inviteRole(role: String) {
        val s = _state.value
        if (role == "member" || (role == "admin" && s.owner)) _state.update { it.copy(inviteRole = role) }
    }

    /** Invites the address typed, as the role beside it (only the owner invites an admin). What was typed stays when it is refused. */
    fun invite() {
        val s = _state.value
        val team = s.team ?: return
        if (!s.manager) return
        val address = jsTrim(s.email).lowercase()
        if (!EMAIL.matches(address)) {
            _state.update { it.copy(emailError = "Type their whole email address.") }
            return
        }
        val role = if (s.owner) s.inviteRole else "member"
        act(
            team.id,
            onFailure = { words -> _state.update { it.copy(emailError = words) } },
            onDone = { _state.update { it.copy(email = "", emailError = null) } },
        ) { user ->
            if (api.invite(team.id, address, role, user)) "Invited $address to ${team.name}" else "Invited $address to ${team.name}, but the email did not go. Withdraw it and invite them again."
        }
    }

    fun askRevoke(invite: Invite) {
        val s = _state.value
        val team = s.team ?: return
        if (s.manager) _state.update { it.copy(confirm = TeamsConfirm.Revoke(team.id, invite)) }
    }

    // -- Members ----------------------------------------------------------------------------

    /** Makes [member] an admin or a member (the owner only); making them the owner is asked first. */
    fun role(member: TeamMember, role: String) {
        val s = _state.value
        val team = s.team ?: return
        if (!s.owner || member.userId == s.me || member.role == "owner" || role == member.role) return
        when (role) {
            "owner" -> _state.update { it.copy(confirm = TeamsConfirm.HandOver(team.id, member)) }
            "admin", "member" -> act(team.id) { user ->
                api.changeMember(team.id, member.userId, role, user)
                "${label(member)} is ${if (role == "admin") "an admin" else "a member"} now"
            }
        }
    }

    fun askRemove(member: TeamMember) {
        val s = _state.value
        val team = s.team ?: return
        if (s.removable(member)) _state.update { it.copy(confirm = TeamsConfirm.Remove(team.id, member)) }
    }

    fun askLeave() {
        val s = _state.value
        val team = s.team ?: return
        if (!s.owner) _state.update { it.copy(confirm = TeamsConfirm.Leave(team.id)) }
    }

    fun askDelete() {
        val s = _state.value
        val team = s.team ?: return
        if (s.owner) _state.update { it.copy(confirm = TeamsConfirm.Delete(team.id)) }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    /**
     * Does what was asked about, if it is still about the team shown and still this person's to do. Handing the team over makes this person an admin at once, before
     * the team is read again; leaving or deleting the team takes it off the list, and another is shown if it was the one shown.
     */
    fun confirm() {
        val s = _state.value
        val asked = s.confirm ?: return
        _state.update { it.copy(confirm = null) }
        if (!s.allows(asked)) return
        val team = s.team ?: return
        when (asked) {
            // A question asked while it was on its way (Delete, as the owner) is let go once this person is no longer the owner.
            is TeamsConfirm.HandOver -> act(team.id, onDone = { handedOver(asked.member.userId) }) { user ->
                api.changeMember(team.id, asked.member.userId, "owner", user)
                "${label(asked.member)} owns ${team.name} now"
            }
            is TeamsConfirm.Remove -> act(team.id) { user ->
                api.removeMember(team.id, asked.member.userId, user)
                "${label(asked.member)} is no longer in ${team.name}"
            }
            is TeamsConfirm.Revoke -> act(team.id) { user ->
                api.revokeInvite(team.id, asked.invite.id, user)
                "Withdrew the invitation to ${asked.invite.email.orEmpty()}"
            }
            is TeamsConfirm.Leave -> act(null, onDone = { gone(team.id) }) { user ->
                api.removeMember(team.id, user, user)
                "You left ${team.name}"
            }
            is TeamsConfirm.Delete -> act(null, onDone = { gone(team.id) }) { user ->
                api.deleteTeam(team.id, user)
                "Deleted ${team.name}"
            }
        }
    }

    /** The team shown handed to [owner]: this person an admin on the page at once, and a question no longer theirs let go. */
    private fun handedOver(owner: Int) {
        _state.update { s ->
            val next = s.copy(page = s.page?.handedTo(owner, s.me), inviteRole = "member")
            next.copy(confirm = next.confirm?.takeIf(next::allows))
        }
    }

    /** A team left or deleted: off the list here at once (the list asked again catches up); if it was the one shown, the next one is. */
    private fun gone(id: Int) {
        val shown = _state.value.chosen == id
        _state.update { s ->
            val teams = s.teams.filter { it.id != id }
            if (shown) s.showing(teams.firstOrNull()?.id, detail = false).copy(teams = teams, page = null) else s.copy(teams = teams)
        }
        if (shown) loadTeam()
    }

    /**
     * Makes one change at a time for the page's account ([block] says what it came to); what it came to lands only on the page it was asked from, and (with
     * [teamId]) only while that team is shown, else it is said. An answer that could not be read is a change made. Then whatever page is open for the account
     * asks for the list and the team again.
     */
    private fun act(teamId: Int?, onFailure: ((String) -> Unit)? = null, onDone: () -> Unit = {}, block: suspend (user: Int) -> String) {
        val s = _state.value
        if (!s.open) return
        val user = pageUser ?: return
        if (working?.isActive == true) {
            say(user, PackPanelViewModel.STILL_WORKING)
            return
        }
        val asked = page
        _state.update { it.copy(busy = true) }
        working = viewModelScope.launch {
            val here = { page == asked && (teamId == null || _state.value.chosen == teamId) }
            try {
                val words = block(user)
                if (here()) onDone()
                say(user, words)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.status in 200..299) {
                    if (here()) onDone()
                    say(user, DONE_UNREAD)
                } else {
                    val words = teamWords(PackFailure.of(e))
                    if (onFailure != null && here()) onFailure(words) else say(user, words)
                }
            } finally {
                _state.update { if (it.open) it.copy(busy = false) else it }
                if (_state.value.open && pageUser == user) {
                    loadList()
                    loadTeam()
                }
            }
        }
    }

    private fun say(user: Int, words: String) {
        if (door.user.value == user) _notices.tryEmit(words)
    }

    companion object {
        const val MAX_NAME = 100
        const val TEAMS_FAILED = "Your teams could not be loaded."
        const val TEAM_FAILED = "The team could not be loaded."
        const val LOST_TEAM = "The answer did not come back, so the team may have been made. Look in the list before trying again."
        const val DONE_UNREAD = "That was done, but its answer could not be read."
        private const val SPACES = "[\\s\\p{Z}\\x{FEFF}]"
        private val EMAIL = Regex("^[^@\\s\\p{Z}\\x{FEFF}]+@[^@\\s\\p{Z}\\x{FEFF}]+\\.[^@\\s\\p{Z}\\x{FEFF}]+$")
        private val EDGES = Regex("^$SPACES+|$SPACES+$")

        private fun jsTrim(text: String) = text.replace(EDGES, "")
        private fun characters(text: String) = text.codePointCount(0, text.length)
        private fun label(member: TeamMember) = member.name.ifBlank { member.email }

        /** A refusal in words: a team's own where the pack's would name the wrong thing, else the app's. */
        internal fun teamWords(failure: PackFailure): String = when (failure.code) {
            "owner_only" -> "Only the team's owner can do that."
            "invalid_role" -> "Only the team's owner can invite an admin."
            // A team the server cannot find, and no code to say more: the team's words, not a pack's.
            null -> PackMessages.words(if (failure.status == 404) failure.copy(code = "team_not_found") else failure)
            else -> PackMessages.words(failure)
        }

        /** An answer that may have been lost after the server made the team: none after the request went, or a gateway's. */
        internal fun lostAnswer(e: Exception): Boolean =
            (e is NetworkException && e.requestMayHaveBeenSent) || (e is ApiException && (e.status == 502 || e.status == 504 || e.status in 520..524))
    }
}

/** The team once this person ([me]) has handed it to [owner]: they are its owner, this person an admin. */
internal fun TeamPage.handedTo(owner: Int, me: Int?): TeamPage = copy(
    team = team.copy(
        role = "admin",
        members = team.members.map { m ->
            when (m.userId) {
                owner -> m.copy(role = "owner")
                me -> m.copy(role = "admin")
                else -> m
            }
        },
    ),
)

/** Teams' words: the web's (TeamsDialog.jsx), with dates in the person's zone. */
object TeamsWords {
    const val INTRO = "A team lets you find each other by name and share a Mission Pack with everyone in it at once."
    const val EMPTY =
        "You are not in a team yet. Make one for your company or section, then invite people by email. Once they join, you can find them by name and share packs with everyone at once."
    const val INVITE_HINT = "They get an email with a link to join. Invitations last 14 days."

    fun roleWord(role: String?): String = when (role) {
        "owner" -> "Owner"
        "admin" -> "Admin"
        else -> "Member"
    }

    /** A team in the list: "18 members · Owner". */
    fun listMeta(team: TeamSummary): String = "${count(team.memberCount)} · ${roleWord(team.role)}"

    /** The team shown: "18 members · you are the owner · since Sep 1". */
    fun meta(team: Team, zone: ZoneId, now: Instant): String {
        val since = PackFacts.instant(team.createdAt)?.let { " · since ${PackFacts.shortDate(it, zone, now)}" }.orEmpty()
        val you = when (team.role) {
            "owner" -> "the owner"
            "admin" -> "an admin"
            else -> "a member"
        }
        return "${count(team.members.size)} · you are $you$since"
    }

    /** A member's line under their name: "sam@army.mil · joined Oct 4". */
    fun joined(member: TeamMember, zone: ZoneId, now: Instant): String =
        member.email + (PackFacts.instant(member.joinedAt)?.let { " · joined ${PackFacts.shortDate(it, zone, now)}" }.orEmpty())

    /** An invitation waiting: "Invited Oct 4 as member · expires in 6 days" (the web's words, and when it stops working, as Members says it). */
    fun pending(invite: Invite, zone: ZoneId, now: Instant): String {
        val sent = PackFacts.instant(invite.createdAt)?.let { PackFacts.shortDate(it, zone, now) }.orEmpty()
        return "Invited $sent as ${if (invite.role == "admin") "admin" else "member"} · ${MembersWords.expiry(invite.expiresAt, zone, now)}"
    }

    fun count(n: Int) = "$n ${if (n == 1) "member" else "members"}"
}
