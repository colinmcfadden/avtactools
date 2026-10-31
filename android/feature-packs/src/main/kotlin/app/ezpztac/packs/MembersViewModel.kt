package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.PackFacts
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.network.Invite
import app.ezpztac.network.PackMember
import app.ezpztac.network.UserBrief
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What is asked before it is done: handing the pack over, taking someone off, withdrawing an invitation, no longer sharing with a team. */
sealed interface MembersConfirm {
    data class HandOver(val member: PackMember) : MembersConfirm

    data class Remove(val member: PackMember) : MembersConfirm

    data class Revoke(val invite: Invite) : MembersConfirm

    data class StopSharing(val team: SharedTeam) : MembersConfirm
}

data class MembersUi(
    val open: Boolean = false,
    val uuid: String = "",
    /** The pack's name: the open pack's until its people are loaded, then the server's. */
    val pack: String = "",
    val people: PackPeople? = null,
    /** Why the people could not be loaded: said with *Try again*, over what was shown last. */
    val loadError: String? = null,
    /** This person's own teams, to share the pack with. */
    val teams: List<TeamOption> = emptyList(),
    /** The invite field: what is typed, what it found, who it holds (a teammate chosen from it, or a whole address typed), and as what they come in. */
    val query: String = "",
    val found: List<UserBrief> = emptyList(),
    val chosen: Invitee? = null,
    val role: String = "editor",
    val inviteError: String? = null,
    /** The team to share with, and what it could do. */
    val shareTo: Int? = null,
    val shareRole: String = "editor",
    /** Who is here now, by account (the open pack's presence), and this account. */
    val here: Set<Int> = emptySet(),
    val me: Int? = null,
    val busy: Boolean = false,
    val confirm: MembersConfirm? = null,
    /** Opened from Invite: the invite field takes the keyboard. */
    val focusInvite: Boolean = false,
) {
    /** This person owns the pack (as the server said last): only then are inviting, roles, the invitations and sharing offered. */
    val owner: Boolean get() = people?.owner == true

    /** The teams it could be shared with: the person's own, but the one it is shared with now. */
    val shareable: List<TeamOption> get() = teams.filter { it.id != people?.shared?.id }

    /** The team a share would go to: the one picked while it is offered, else the first. */
    val shareTeam: TeamOption? get() = shareable.firstOrNull { it.id == shareTo } ?: shareable.firstOrNull()

    /** What is said under the invite field: who it holds, else how to find someone. */
    val inviteHint: String
        get() = chosen?.takeIf { it.id != null }?.let { "${it.name.ifBlank { it.email }}, ${it.email}. Type to look for someone else." } ?: MembersWords.HINT
}

/**
 * Members of the open pack (the design's Members, the web's MembersDialog): who is in it, here now or when they last looked, and for its owner inviting people (a
 * teammate by name, anyone else by email address), their roles, handing the pack over, taking someone off, the invitations waiting (send again, withdraw) and sharing
 * with a team. What is changed is read again from the server, and the open pack is asked for what is new; one change at a time. Every call is made for the account the
 * page was opened for, and what it came to is said only to that account. The page is the open pack's: another pack, or another account, puts it away.
 */
@HiltViewModel
class MembersViewModel @Inject constructor(private val api: PeopleApi, private val door: PackDoor) : ViewModel() {
    private val _state = MutableStateFlow(MembersUi())
    val state: StateFlow<MembersUi> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** What a change came to, in words. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var pageUser: Int? = null
    private var loads = 0
    private var search: Job? = null

    // The change on its way, which outlives the page it was asked from (one at a time, whichever page is up), and which page that was: what it came to lands on
    // that page only, never on one opened since.
    private var working: Job? = null
    private var page = 0

    init {
        // Who is here, narrowed to the accounts: presence changes many times a second while people point.
        viewModelScope.launch {
            door.open.map { it?.uuid to hereOf(it?.people.orEmpty()) }.distinctUntilChanged().collect { (uuid, here) ->
                _state.update { s -> if (s.open && s.uuid == uuid) s.copy(here = here) else s }
            }
        }
        viewModelScope.launch {
            combine(door.user, door.open.map { it?.uuid }.distinctUntilChanged()) { user, uuid -> user to uuid }.collect { (user, uuid) ->
                val s = _state.value
                if (s.open && (user != pageUser || uuid != s.uuid)) shut()
            }
        }
    }

    /** Opens the page for the open pack, called [name] until its people come; from Invite ([invite]) the invite field takes the keyboard. */
    fun open(name: String, invite: Boolean = false) {
        val user = door.user.value ?: return
        val pack = door.open.value ?: return
        val s = _state.value
        if (s.open && s.uuid == pack.uuid && pageUser == user) {
            if (invite) _state.update { it.copy(focusInvite = true) }
            return
        }
        pageUser = user
        page++
        search?.cancel()
        _state.value = MembersUi(
            open = true, uuid = pack.uuid, pack = name, here = hereOf(pack.people), me = user, focusInvite = invite, busy = working?.isActive == true,
        )
        load()
    }

    /** Puts the page away. A change on its way still finishes, and what it came to is said. */
    fun close() = shut()

    private fun shut() {
        search?.cancel()
        loads++
        page++
        pageUser = null
        _state.value = MembersUi()
    }

    /** The invite field has taken the keyboard it was opened for. */
    fun focused() {
        _state.update { it.copy(focusInvite = false) }
    }

    /** Asks for the pack's people (and this person's teams) again; only the latest answer, for the page as it is, is shown. */
    fun load() {
        val s = _state.value
        if (!s.open) return
        val user = pageUser ?: return
        val uuid = s.uuid
        val number = ++loads
        viewModelScope.launch {
            val people = try {
                api.people(uuid, user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current(number, uuid, user)) _state.update { it.copy(loadError = PackMessages.words(PackFailure.of(e))) }
                return@launch
            }
            // Teams that could not be had leave sharing as it was offered: the people are what the page is for.
            val teams = try {
                api.teams(user).map { TeamOption(it.id, it.name, it.memberCount) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!current(number, uuid, user)) return@launch
            // A question asked of an owner is let go once they are not one (handed over meanwhile).
            _state.update { it.copy(people = people, pack = people.pack, teams = teams ?: it.teams, loadError = null, confirm = it.confirm.takeIf { people.owner }) }
        }
    }

    private fun current(number: Int, uuid: String, user: Int) =
        number == loads && _state.value.open && _state.value.uuid == uuid && pageUser == user && door.user.value == user

    // -- Inviting ---------------------------------------------------------------------------

    /**
     * The invite field (the web's PersonPicker in its choosing mode): typing lets go of who it held; a whole address typed is who it holds; two characters or more
     * of anything else are looked for among the person's teammates a moment after typing stops, leaving out those in the pack.
     */
    fun query(typed: String) {
        val s = _state.value
        if (!s.open || s.busy) return
        val text = jsTrim(typed)
        val email = text.takeIf { EMAIL.matches(it) }?.lowercase()
        val searching = email == null && text.length >= MIN_SEARCH
        _state.update {
            it.copy(query = typed, chosen = email?.let { e -> Invitee(null, "", e) }, found = if (searching) it.found else emptyList(), inviteError = null)
        }
        search?.cancel()
        if (!searching) return
        val user = pageUser ?: return
        search = viewModelScope.launch {
            delay(SEARCH_PAUSE_MS)
            val found = try {
                api.searchPeople(text, user)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (door.user.value != user) return@launch
            _state.update { st ->
                if (!st.open || jsTrim(st.query) != text) st else st.copy(found = found.filter { f -> st.people?.members.orEmpty().none { it.userId == f.id } })
            }
        }
    }

    /** A teammate found is who the field holds; the field shows their name. */
    fun pick(person: UserBrief) {
        search?.cancel()
        _state.update { it.copy(chosen = Invitee(person.id, person.name, person.email), query = person.name.ifBlank { person.email }, found = emptyList(), inviteError = null) }
    }

    /** What whoever is invited next comes in as: `editor` or `viewer`. */
    fun inviteRole(role: String) {
        if (role in ROLES) _state.update { it.copy(role = role) }
    }

    /**
     * Brings in who the field holds, as the role beside it: a teammate is added at once, anyone else is invited by email. Once done the field empties for the next;
     * a refusal is said under it, and what was typed stays. A name typed and not chosen is refused in words, before anything is sent.
     */
    fun invite() {
        val s = _state.value
        if (!s.open || !s.owner) return
        val chosen = s.chosen
        if (chosen == null) {
            if (jsTrim(s.query).isNotEmpty()) _state.update { it.copy(inviteError = NewPackViewModel.PICK_SOMEONE) }
            return
        }
        val role = s.role
        act(
            onFailure = { words -> _state.update { it.copy(inviteError = words) } },
            onDone = { _state.update { it.copy(query = "", chosen = null, found = emptyList(), inviteError = null) } },
        ) { user ->
            if (chosen.id != null) {
                api.addMember(s.uuid, chosen.id, role, user)
                "${chosen.label} is in ${s.pack} now"
            } else if (api.invite(s.uuid, chosen.email, role, user)) {
                "Invited ${chosen.email}"
            } else {
                "Invited ${chosen.email}, but the email did not go. Send it again under Pending."
            }
        }
    }

    // -- Members ---------------------------------------------------------------------------

    /** Makes [member] an editor or a viewer; making them the owner is asked first (it cannot be taken back here). The owner's own row is fixed. */
    fun role(member: PackMember, role: String) {
        val s = _state.value
        if (!s.owner || member.role == "owner" || role == member.role) return
        when (role) {
            "owner" -> _state.update { it.copy(confirm = MembersConfirm.HandOver(member)) }
            "editor", "viewer" -> act { user ->
                api.changeMember(s.uuid, member.userId, role, user)
                "${MembersWords.label(member)} is ${if (role == "viewer") "a viewer" else "an editor"} now"
            }
        }
    }

    /** Asks before [member] is taken off the pack (never its owner). */
    fun askRemove(member: PackMember) {
        if (_state.value.owner && member.role != "owner") _state.update { it.copy(confirm = MembersConfirm.Remove(member)) }
    }

    // -- Invitations waiting ------------------------------------------------------------------

    /** Sends [invite] again, with a new link (the old one stops working). */
    fun resend(invite: Invite) {
        val s = _state.value
        if (!s.owner) return
        val email = invite.email.orEmpty()
        act { user ->
            if (api.resendInvite(s.uuid, invite.id, user)) "Sent the invitation to $email again" else "The invitation to $email is waiting, but its email did not go. Try again later."
        }
    }

    /** Asks before [invite] is withdrawn. */
    fun askRevoke(invite: Invite) {
        if (_state.value.owner) _state.update { it.copy(confirm = MembersConfirm.Revoke(invite)) }
    }

    // -- Sharing with a team ------------------------------------------------------------------

    fun shareTo(teamId: Int) {
        _state.update { s -> if (s.shareable.any { it.id == teamId }) s.copy(shareTo = teamId) else s }
    }

    fun shareRole(role: String) {
        if (role in ROLES) _state.update { it.copy(shareRole = role) }
    }

    /** Shares the pack with the team picked, as what it was picked to do; one team at a time, so this one takes the place of any other. */
    fun share() {
        val s = _state.value
        if (!s.owner) return
        val team = s.shareTeam ?: return
        val role = s.shareRole
        act { user ->
            api.updatePack(s.uuid, PackChange(setTeam = true, team = TeamPick(team.id, role)), user)
            "Shared with ${team.name}${if (role == "viewer") " to view" else ""}"
        }
    }

    /** What the team the pack is shared with can do there. */
    fun sharedRole(role: String) {
        val s = _state.value
        val shared = s.people?.shared ?: return
        if (!s.owner || role !in ROLES || role == shared.role) return
        act { user ->
            api.updatePack(s.uuid, PackChange(setTeam = true, team = TeamPick(shared.id, role)), user)
            "${shared.name} can ${if (role == "viewer") "only view" else "edit"} this pack now"
        }
    }

    /** Asks before the pack is no longer shared with its team. */
    fun askStopSharing() {
        val s = _state.value
        val shared = s.people?.shared ?: return
        if (s.owner) _state.update { it.copy(confirm = MembersConfirm.StopSharing(shared)) }
    }

    // -- What is asked first ------------------------------------------------------------------

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    /** Does what was asked about. */
    fun confirm() {
        val s = _state.value
        val asked = s.confirm ?: return
        _state.update { it.copy(confirm = null) }
        if (!s.owner) return                                                                         // a load that says they are not lets the question go
        when (asked) {
            // Handed over: this person is an editor at once, before the people are read again, so no owner's control stays on offer meanwhile.
            is MembersConfirm.HandOver -> act(onDone = { _state.update { it.copy(people = it.people?.handedTo(asked.member.userId, it.me)) } }) { user ->
                api.changeMember(s.uuid, asked.member.userId, "owner", user)
                "${MembersWords.label(asked.member)} owns ${s.pack} now. You are an editor."
            }
            is MembersConfirm.Remove -> act { user ->
                api.removeMember(s.uuid, asked.member.userId, user)
                "${MembersWords.label(asked.member)} is no longer in ${s.pack}"
            }
            is MembersConfirm.Revoke -> act { user ->
                api.revokeInvite(s.uuid, asked.invite.id, user)
                "Withdrew the invitation to ${asked.invite.email.orEmpty()}"
            }
            is MembersConfirm.StopSharing -> act { user ->
                api.updatePack(s.uuid, PackChange(setTeam = true, team = null), user)
                "${asked.team.name} no longer has this pack"
            }
        }
    }

    /**
     * Makes one change at a time for the page's account ([block] says what it came to), then asks the open pack for what is new (a member or a share is an event in
     * its log) and the people again. A refusal is said in the app's words, at [onFailure] when given. Nothing is said to another account.
     */
    private fun act(onFailure: ((String) -> Unit)? = null, onDone: () -> Unit = {}, block: suspend (user: Int) -> String) {
        val s = _state.value
        if (!s.open) return
        val user = pageUser ?: return
        if (working?.isActive == true) {
            say(user, PackPanelViewModel.STILL_WORKING)
            return
        }
        val uuid = s.uuid
        val asked = page
        _state.update { it.copy(busy = true) }
        working = viewModelScope.launch {
            try {
                val words = block(user)
                if (page == asked) onDone()
                say(user, words)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val words = PackMessages.words(PackFailure.of(e))
                if (onFailure != null && page == asked) onFailure(words) else say(user, words)
            } finally {
                _state.update { if (it.open) it.copy(busy = false) else it }
                if (door.user.value == user && door.open.value?.uuid == uuid) door.refresh()
                if (onPage(uuid, user)) load()
            }
        }
    }

    private fun onPage(uuid: String, user: Int) = _state.value.open && _state.value.uuid == uuid && pageUser == user

    private fun say(user: Int, words: String) {
        if (door.user.value == user) _notices.tryEmit(words)
    }

    companion object {
        const val MIN_SEARCH = NewPackViewModel.MIN_SEARCH
        const val SEARCH_PAUSE_MS = NewPackViewModel.SEARCH_PAUSE_MS
        private val ROLES = setOf("editor", "viewer")
        private const val SPACES = "[\\s\\p{Z}\\x{FEFF}]"
        private val EMAIL = Regex("^[^@\\s\\p{Z}\\x{FEFF}]+@[^@\\s\\p{Z}\\x{FEFF}]+\\.[^@\\s\\p{Z}\\x{FEFF}]+$")
        private val EDGES = Regex("^$SPACES+|$SPACES+$")

        private fun jsTrim(text: String) = text.replace(EDGES, "")
    }
}

/** The people once this person ([me]) has handed the pack to [owner]: they are its owner, this person an editor. */
internal fun PackPeople.handedTo(owner: Int, me: Int?): PackPeople = copy(
    role = "editor",
    members = members.map { m ->
        when (m.userId) {
            owner -> m.copy(role = "owner")
            me -> m.copy(role = "editor")
            else -> m
        }
    },
)

/** The accounts here now, from the open pack's presence. */
internal fun hereOf(people: List<JsonObject>): Set<Int> = people.mapNotNull { (it["user_id"] as? JsonPrimitive)?.intOrNull }.toSet()

/** Members' words: the web's (MembersDialog.jsx, time.js), with times in the person's zone. */
object MembersWords {
    const val HINT = "Name search only finds people on your teams. To invite anyone else, type their full email address."
    const val INTRO = "Owners manage who can see and edit this pack. Viewers can look but not change anything."
    const val NOTE = "Everyone with access sees every item in this pack. Threats are never shared."

    /** Who they are: their name, else their address. */
    fun label(member: PackMember): String = member.name.ifBlank { member.email }

    fun roleWord(role: String?): String = when (role) {
        "owner" -> "Owner"
        "viewer" -> "Viewer"
        else -> "Editor"
    }

    /** Where someone is: "Here now", "Seen 2 h ago", "Not opened yet"; nothing for this person themselves. */
    fun seen(member: PackMember, here: Set<Int>, me: Int?, zone: ZoneId, now: Instant): String? = when {
        member.userId == me -> null
        member.userId in here -> "Here now"
        else -> PackFacts.instant(member.seenAt)?.let { "Seen ${timeAgo(it, zone, now)}" } ?: "Not opened yet"
    }

    /** "just now", "4 min ago", "2 h ago", else the date (time.js's `timeAgo`: minutes and hours rounded as JavaScript rounds). */
    fun timeAgo(at: Instant, zone: ZoneId, now: Instant): String {
        val minutes = Math.floorDiv(now.toEpochMilli() - at.toEpochMilli() + 30_000, 60_000)
        if (minutes < 1) return "just now"
        if (minutes < 60) return "$minutes min ago"
        val hours = Math.floorDiv(minutes + 30, 60)
        if (hours < 24) return "$hours h ago"
        return PackFacts.shortDate(at, zone, now)
    }

    /** An invitation waiting: "Invited Oct 4 as Editor · expires in 6 days". */
    fun pending(invite: Invite, zone: ZoneId, now: Instant): String {
        val sent = PackFacts.instant(invite.createdAt)?.let { PackFacts.shortDate(it, zone, now) }.orEmpty()
        return "Invited $sent as ${roleWord(invite.role)} · ${expiry(invite.expiresAt, zone, now)}"
    }

    /**
     * When an invitation stops working, by the calendar in the person's zone: "expires today", "expires tomorrow", "expires in 6 days", or "expired". **Not like the
     * web**, whose "expires today" is any time within a day, tomorrow morning included.
     */
    fun expiry(iso: String?, zone: ZoneId, now: Instant): String {
        val at = PackFacts.instant(iso) ?: return "expiry unknown"
        if (!at.isAfter(now)) return "expired"
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate())
        return when (days) {
            0L -> "expires today"
            1L -> "expires tomorrow"
            else -> "expires in $days days"
        }
    }

    /** "Oct 3 at 14:20", or "never": when someone last looked, for the question before they are taken off. */
    fun lastSeen(member: PackMember, zone: ZoneId, now: Instant): String =
        PackFacts.instant(member.seenAt)?.let { "${PackFacts.shortDate(it, zone, now)} at ${PackFacts.clockTime(it, zone)}" } ?: "never"
}
