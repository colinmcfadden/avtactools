package app.ezpztac.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.network.TeamSummary
import app.ezpztac.network.UserBrief
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who can open a new pack: its maker alone, a team (and anyone added), or people chosen by name or email (the web's "Who can open it"). */
enum class Audience(val label: String) { JUST_ME("Just me"), TEAM("A team"), PEOPLE("Specific people") }

/** Someone to bring into the new pack: a teammate found by name ([id]), or anyone by [email]; [role] `editor` or `viewer`. */
data class Invitee(val id: Int?, val name: String, val email: String, val role: String = "editor") {
    /** One person once: a teammate by their id, anyone else by their address. */
    val key: String get() = id?.let { "user:$it" } ?: "email:$email"

    /** What they are called: their name, or the address they are invited at. */
    val label: String get() = name.ifBlank { email }

    /** Their face's letters: a teammate's initials; someone invited by address has none (a letter icon stands in). */
    val initials: String? get() = if (id != null) initialsOf(name, email) else null
}

/** A team the pack can be opened to: "B Co 2-10 AVN" and "18 members". */
data class TeamOption(val id: Int, val name: String, val members: Int) {
    val count: String get() = "$members ${if (members == 1) "member" else "members"}"
}

data class NewPackUi(
    /** The form is up. */
    val open: Boolean = false,
    val name: String = "",
    val description: String = "",
    val audience: Audience = Audience.JUST_ME,
    /** The person's teams once they are known ([teamsLoaded]); none known means *A team* cannot be chosen, and [teamsError] says why when they could not be had. */
    val teams: List<TeamOption> = emptyList(),
    val teamsLoaded: Boolean = false,
    val teamsError: Boolean = false,
    val teamId: Int? = null,
    /** What the team can do: `editor` or `viewer`. */
    val teamRole: String = "editor",
    /** Who else is brought in, in the order chosen. */
    val invitees: List<Invitee> = emptyList(),
    /** What is typed in the person field, and the teammates it found. */
    val query: String = "",
    val found: List<UserBrief> = emptyList(),
    /** A whole email address typed (lower-cased): it is offered as an invitation of its own. */
    val typedEmail: String? = null,
    /** Why the name will not do: said at the name field. */
    val nameError: String? = null,
    /** Why what is typed in the person field was not taken: said at that field. */
    val peopleError: String? = null,
    /** Why the pack was not made (or not set as asked): said where it is in sight however far the form is scrolled. */
    val error: String? = null,
    /** The pack is being made: the form stays, and cannot be put away. */
    val busy: Boolean = false,
) {
    /** The team chosen, while *A team* is. */
    val team: TeamOption? get() = teams.firstOrNull { it.id == teamId }

    /** Why *A team* cannot be chosen, in words, or null when it can (or the teams are still on their way). */
    val teamUnavailable: String?
        get() = when {
            teamsError -> TEAMS_FAILED
            teamsLoaded && teams.isEmpty() -> NO_TEAM
            else -> null
        }

    /** What the person field is called: "Also invite" beside a team, "Invite" for people alone. */
    val inviteLabel: String get() = if (audience == Audience.TEAM) "Also invite by name or email" else "Invite by name or email"

    companion object {
        const val NO_TEAM = "You are not in a team yet. Make one under Manage teams."
        const val TEAMS_FAILED = "Your teams could not be loaded, so the pack cannot be shared with a team now. You can share it later from Members."
    }
}

/**
 * New Mission Pack (the design's NewPack; the web's NewPackDialog): a name, what it is for, who can open it (just this person; a team, and what it can do; or
 * people chosen by name among their teammates or by email address), then made, and opened by whoever listens to [made].
 *
 * Made under a uuid this form chose, so a try whose answer was lost and is made again is the same pack, never a second (the web sends none); what was changed
 * in the form since that try is set on it. Each person is brought in once it is made, one at a time, and one who cannot be is said with why: the pack stands
 * (the web keeps only the name). The teams are asked for each time the form opens, and *A team* is chosen for someone in one however late they come, unless the
 * person has chosen already (the web takes it once, as the dialog opens, and a list that comes later leaves the pack unshared). Everything is made for the
 * account here when it was asked, and what it came to is said only to that account.
 */
@HiltViewModel
class NewPackViewModel @Inject constructor(private val api: PeopleApi, private val door: PackDoor) : ViewModel() {
    private val _state = MutableStateFlow(NewPackUi())
    val state: StateFlow<NewPackUi> = _state.asStateFlow()

    private val _made = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** A pack made: its uuid, to be opened. */
    val made: SharedFlow<String> = _made.asSharedFlow()

    /** What making it came to, in words. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Where a pack's uuid comes from. A test sets its own. */
    internal var newUuid: () -> String = { UUID.randomUUID().toString() }

    // The account the form was opened for, and the uuid the pack is made under: the same for every try of one form, a new one for each form (start).
    private var formUser: Int? = null
    private var uuid: String? = null
    private var audienceChosen = false
    private var search: Job? = null
    private var teamsAsked: Job? = null

    init {
        // A form is one account's: another account (or packs stopping) puts it away, unless its pack is being made, which ends by itself.
        viewModelScope.launch {
            door.user.collect { now -> if (_state.value.open && !_state.value.busy && now != formUser) close() }
        }
    }

    /** The form opens, empty, with the person's teams asked for again. */
    fun start() {
        if (_state.value.open) return
        val user = door.user.value ?: return
        formUser = user
        uuid = newUuid()
        audienceChosen = false
        search?.cancel()
        _state.value = NewPackUi(open = true)
        teamsAsked?.cancel()
        teamsAsked = viewModelScope.launch {
            val teams = try {
                api.teams(user).map(::option)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (door.user.value == user) _state.update { if (it.open) it.copy(teamsError = true) else it }
                return@launch
            }
            if (door.user.value != user) return@launch
            _state.update { s ->
                if (!s.open) return@update s
                s.copy(
                    teams = teams, teamsLoaded = true, teamId = teams.firstOrNull()?.id,
                    // A team is the default for someone in one, unless they have chosen already.
                    audience = if (!audienceChosen && teams.isNotEmpty()) Audience.TEAM else s.audience,
                )
            }
        }
    }

    /** The form is put away; not while the pack is being made, whose answer is still to come. */
    fun close() {
        if (_state.value.busy) return
        search?.cancel()
        teamsAsked?.cancel()
        formUser = null
        _state.value = NewPackUi()
    }

    /** The name as typed, in capitals as the web types it, never past the server's limit (one too long can always be made shorter). */
    fun name(typed: String) {
        val name = typed.uppercase()
        _state.update { s -> if (characters(name) > MAX_NAME && characters(name) > characters(s.name)) s else s.copy(name = name, nameError = null) }
    }

    fun description(typed: String) {
        _state.update { s -> if (typed.length > MAX_DESCRIPTION && typed.length > s.description.length) s else s.copy(description = typed) }
    }

    fun audience(chosen: Audience) {
        val s = _state.value
        if (s.busy || (chosen == Audience.TEAM && s.teams.isEmpty())) return
        audienceChosen = true
        _state.update { it.copy(audience = chosen) }
    }

    fun team(id: Int) {
        _state.update { s -> if (s.teams.any { it.id == id }) s.copy(teamId = id) else s }
    }

    fun teamRole(role: String) {
        if (role in ROLES) _state.update { it.copy(teamRole = role) }
    }

    /**
     * The person field: a whole email address is offered as an invitation; two characters or more of anything else are looked for among the person's teammates
     * a moment after typing stops, leaving out those already chosen. A search that fails finds nobody, without a word (the field's hint says how else to add
     * someone).
     */
    fun query(typed: String) {
        if (!_state.value.open || _state.value.busy) return
        val text = jsTrim(typed)
        val email = text.takeIf { EMAIL.matches(it) }?.lowercase()
        val searching = email == null && text.length >= MIN_SEARCH
        _state.update { it.copy(query = typed, typedEmail = email, found = if (searching) it.found else emptyList(), peopleError = null) }
        search?.cancel()
        if (!searching) return
        val user = formUser ?: return
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
            _state.update { s -> if (jsTrim(s.query) != text) s else s.copy(found = found.filter { f -> s.invitees.none { it.id == f.id } }) }
        }
    }

    /** A teammate found is brought in, as an editor; the field empties for the next. */
    fun pick(person: UserBrief) = add(Invitee(person.id, person.name, person.email))

    /** The address typed is invited, as an editor. */
    fun pickEmail() {
        val email = _state.value.typedEmail ?: return
        add(Invitee(null, "", email))
    }

    private fun add(invitee: Invitee) {
        search?.cancel()
        _state.update { s ->
            if (s.busy) return@update s
            // Someone already chosen stays as they are (a viewer stays a viewer), as on the web.
            val invitees = if (s.invitees.any { it.key == invitee.key }) s.invitees else s.invitees + invitee
            s.copy(invitees = invitees, query = "", found = emptyList(), typedEmail = null, peopleError = null)
        }
    }

    /** Someone chosen is to be an editor or a viewer. */
    fun role(key: String, role: String) {
        if (role !in ROLES) return
        _state.update { s -> if (s.busy) s else s.copy(invitees = s.invitees.map { if (it.key == key) it.copy(role = role) else it }) }
    }

    fun remove(key: String) {
        _state.update { s -> if (s.busy) s else s.copy(invitees = s.invitees.filter { it.key != key }) }
    }

    /**
     * Makes the pack, then brings each person in (a teammate added, anyone else invited by email), and hands it over to be opened. A name that will not do is
     * said at its field before anything is sent, and so is a name typed in the person field that was not chosen from the list; a whole address typed there is
     * taken as the person meant it, invited. A pack the server would not make is said in the app's words ([NewPackUi.error]), and the form stays for another
     * try under the same uuid. A try that finds the pack an earlier one made (its answer lost) sets on it what has been changed since.
     */
    fun create() {
        val asked = _state.value
        if (asked.busy || !asked.open) return
        val name = jsTrim(asked.name)
        refusal(name)?.let { why ->
            _state.update { it.copy(nameError = why) }
            return
        }
        val choosing = asked.audience != Audience.JUST_ME
        if (choosing && asked.typedEmail == null && jsTrim(asked.query).isNotEmpty()) {
            _state.update { it.copy(peopleError = PICK_SOMEONE) }
            return
        }
        val user = formUser ?: return
        if (door.user.value != user) {
            close()
            return
        }
        val under = uuid ?: newUuid().also { uuid = it }
        val description = jsTrim(asked.description)
        val team = asked.team?.takeIf { asked.audience == Audience.TEAM }?.let { TeamPick(it.id, asked.teamRole) }
        // An address typed and not yet added is one the person meant to invite.
        val typed = asked.typedEmail?.takeIf { email -> asked.invitees.none { it.key == "email:$email" } }?.let { Invitee(null, "", it) }
        val people = if (!choosing) emptyList() else asked.invitees + listOfNotNull(typed)
        search?.cancel()
        audienceChosen = true                                                                       // what was shown when Create was pressed is the choice: teams that come later change nothing
        _state.update { it.copy(busy = true, nameError = null, error = null, found = emptyList()) }
        viewModelScope.launch {
            val made = try {
                api.createPack(name, description, team, under, user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = PackFailure.of(e)
                if (failure.code == UUID_TAKEN) uuid = newUuid()                                     // a pack deleted since had it: the next try takes another
                _state.update { it.copy(busy = false, error = PackMessages.words(failure)) }
                if (door.user.value != user) close()                                                // nothing is said to another account
                return@launch
            }
            val change = PackChange.between(made, name, description, team)
            if (!made.created && change != null) {
                try {
                    api.updatePack(made.uuid, change, user)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Made as the first try asked, without what was changed since: the form stays, and the next try sets it.
                    _state.update { it.copy(busy = false, error = "$NOT_YET ${PackMessages.words(PackFailure.of(e))}") }
                    if (door.user.value != user) close()
                    return@launch
                }
            }
            val failed = mutableListOf<Pair<String, String>>()
            val notEmailed = mutableListOf<String>()
            for (person in people) {
                try {
                    if (person.id != null) {
                        api.addMember(made.uuid, person.id, person.role, user)
                    } else if (!api.invite(made.uuid, person.email, person.role, user)) {
                        notEmailed += person.label
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += person.label to PackMessages.words(PackFailure.of(e))
                }
            }
            _state.value = NewPackUi()
            if (door.user.value != user) return@launch                                             // made, but neither opened nor said for another account
            _made.tryEmit(made.uuid)
            _notices.tryEmit("Made ${change?.name?.takeIf { !made.created } ?: made.name}. You are its owner.")
            failedWords(failed)?.let { _notices.tryEmit(it) }
            notEmailedWords(notEmailed)?.let { _notices.tryEmit(it) }
        }
    }

    companion object {
        /** The server's limits for a pack's name (characters) and its description. */
        const val MAX_NAME = 100
        const val MAX_DESCRIPTION = 2000

        /** Search begins at this many characters, this long after typing stops (the web's). */
        const val MIN_SEARCH = 2
        const val SEARCH_PAUSE_MS = 250L

        private val ROLES = setOf("editor", "viewer")

        /** A name in the person field that was not chosen from what it found. */
        const val PICK_SOMEONE = "Choose someone from the list, type their whole email address, or clear the field."

        /** A pack made by an earlier try whose answer was lost, which this try could not change. */
        const val NOT_YET = "The pack was made by the last try, but what you changed since did not reach it."
        private const val UUID_TAKEN = "uuid_taken"

        /** JavaScript's spaces: what its `trim` and `\s` take, the byte order mark among them. */
        private const val SPACES = "[\\s\\p{Z}\\x{FEFF}]"

        /** A whole email address, as the web tests one (`^[^\s@]+@[^\s@]+\.[^\s@]+$`). */
        private val EMAIL = Regex("^[^@\\s\\p{Z}\\x{FEFF}]+@[^@\\s\\p{Z}\\x{FEFF}]+\\.[^@\\s\\p{Z}\\x{FEFF}]+$")
        private val EDGES = Regex("^$SPACES+|$SPACES+$")

        /** Why [name] (trimmed) will not do as a pack's name, or null: blank (the web's words), past 100 characters, or a control character in it. */
        fun refusal(name: String): String? = when {
            name.isEmpty() -> "Give the pack a name."
            characters(name) > MAX_NAME || name.any { it.code < 0x20 || it.code == 0x7F } -> PackPanelViewModel.NAME_REFUSED
            else -> null
        }

        /**
         * Those who could not be brought in, by name: with the reason when it is one for them all ("Could not add Sam Bell: They are already in it. Try again
         * from Members."), else by name alone.
         */
        fun failedWords(failed: List<Pair<String, String>>): String? {
            if (failed.isEmpty()) return null
            val who = failed.joinToString(", ") { it.first }
            val reason = failed.map { it.second }.distinct().singleOrNull()
            return if (reason != null) "Could not add $who: ${reason.removeSuffix(".")}. Try again from Members." else "Could not add $who. Try again from Members."
        }

        /** Those invited whose email did not go (the web does not say). */
        fun notEmailedWords(who: List<String>): String? =
            if (who.isEmpty()) null else "The email to ${who.joinToString(", ")} did not go. Send it again from Members."

        private fun option(team: TeamSummary) = TeamOption(team.id, team.name, team.memberCount)

        private fun characters(text: String) = text.codePointCount(0, text.length)

        private fun jsTrim(text: String) = text.replace(EDGES, "")
    }
}
