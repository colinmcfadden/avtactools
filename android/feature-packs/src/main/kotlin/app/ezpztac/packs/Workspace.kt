package app.ezpztac.packs

import app.ezpztac.data.PackFacts
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.network.Invite
import app.ezpztac.network.InviteAccepted
import app.ezpztac.network.PackItemCounts
import app.ezpztac.network.PackSummary
import app.ezpztac.network.TeamSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement

/**
 * The workspace edits go to: the Library (the person's own), or one Mission Pack (docs/MISSION_PACKS.md). One is open at a time, as on the web.
 */

/**
 * Setting the person's own documents aside while a pack is open, and putting them back on the way out (the web parks the Library's open LZ/PZs):
 * switching never discards anything. The app's, since it knows what was open.
 */
interface Parking {
    /** The person's own LZ/PZ and set of routes leave the editors, written first; the LZ/PZs and sets open in their session stay listed for the way back. */
    suspend fun park()

    /** The person's own LZ/PZ and set of routes that were on the map come back. */
    suspend fun unpark()
}

/**
 * The pack open last, so the next launch opens it again (the web's `ezpz.openPack`). On the device only, and each account's own: another person
 * signing in on the device never opens it.
 */
interface LastPack {
    fun id(user: Int): String?

    fun remember(user: Int, uuid: String?)
}

/**
 * What the switcher asks of the packs the device runs: who they run for (null: they do not run here now: signed out, Mission Packs off, the gate),
 * which is open, and opening and closing one (`PackWorkspace`, joined to the editors).
 */
interface PackDoor {
    val user: StateFlow<Int?>

    val open: StateFlow<PackState?>

    suspend fun openPack(uuid: String)

    suspend fun closePack()

    /** Asks the open pack for what is new now: after a finish or a reopen, which are not operations of its own. */
    fun refresh() {}

    /** Writes whatever of the open pack the editors hold now, so a change of the last moments is on its way before the pack is finished. Throws when a save failed. */
    suspend fun flushOpen() {}

    /** Where this person is pointing on the map ([lat], [lon]), for everyone else in the open pack; null for nowhere. Said at most ten times a second. */
    fun point(lat: Double?, lon: Double?) {}

    /**
     * Item [item] of the open pack as this person sees it, as the pack's data: the editor's document while it is open in one (a change the pack has not
     * taken, or holds back while it is paused, included), else the pack's own. Null when the open pack has no such item.
     */
    suspend fun seenData(item: String): JsonElement? = open.value?.item(item)?.data
}

/** The calls the switcher makes: the packs this person can open, the invitations waiting for them, their teams, and answering an invitation. */
interface PacksApi {
    suspend fun packs(): List<PackSummary>

    suspend fun invites(): List<Invite>

    suspend fun teams(): List<TeamSummary>

    /** What the invitation let them in to: a pack or a team. */
    suspend fun accept(inviteId: Int): InviteAccepted

    suspend fun decline(inviteId: Int)

    /** Finishes pack [uuid] for the account [user] (its owner only): read-only for everyone, the owner included. */
    suspend fun finish(uuid: String, user: Int)

    /** Reopens pack [uuid] for the account [user] (its owner only). */
    suspend fun reopen(uuid: String, user: Int)

    /** A new pack [user] owns, named [name], with a copy of everything in [uuid]: what it was made as. Not repeatable (a lost answer may have made it). */
    suspend fun duplicate(uuid: String, name: String, user: Int): PackMade
}

/** A pack made by duplicating another: its uuid and the name the server gave it. */
data class PackMade(val uuid: String, val name: String)

/** A question about the open pack the frame shows, whatever asked it: finishing it, reopening it, or the copy's name ([uuid]: the pack asked about). */
sealed interface PackDialog {
    val uuid: String
    val pack: String

    data class Finish(override val uuid: String, override val pack: String) : PackDialog

    data class Reopen(override val uuid: String, override val pack: String) : PackDialog

    /** The copy's [name] as typed; [sending] while it is made (the dialog stays, and cannot be put away), [error] why it was not. */
    data class Duplicate(override val uuid: String, override val pack: String, val name: String, val sending: Boolean = false, val error: String? = null) : PackDialog

    companion object {
        /** The server's limit for a pack's name, counted as characters. */
        const val MAX_NAME = 100

        /** The copy's first name, the web's: "OP DK 2", cut to the limit by characters (never through one). */
        fun copyName(name: String): String = cut("$name 2", MAX_NAME)

        /** Why [name] (trimmed, in capitals) will not do as a pack's name, or null: the server's rule (1 to 100 characters, no control characters). */
        fun refusal(name: String): String? = when {
            name.isEmpty() -> "Give it a name."
            name.codePointCount(0, name.length) > MAX_NAME || name.any { it.code < 0x20 || it.code == 0x7F } -> PackPanelViewModel.NAME_REFUSED
            else -> null
        }

        private fun cut(text: String, max: Int): String =
            if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
    }
}

/** The person's Library in numbers, for the switcher's Library row. */
data class LibraryCounts(val lz: Int = 0, val routes: Int = 0, val points: Int = 0)

/** Where the Library's numbers come from (the repositories, in the app). */
interface LibraryTally {
    val counts: Flow<LibraryCounts>
}

/** Someone who has the open pack open now (the live service's presence), [me] when it is this person. */
data class PersonUi(val id: Int?, val name: String, val initials: String, val me: Boolean, val here: Boolean = false)

/** The open pack, as the frame shows it: its name, who has it open, and how it stands with the server. */
data class OpenPackUi(
    val uuid: String,
    val name: String,
    val people: List<PersonUi>,
    val status: PackStatus,
    val finished: Boolean,
    val readOnly: Boolean,
    /** Edits on the device that the pack has not confirmed yet. */
    val pending: Int,
    /** This person's role in it (owner, editor or viewer); null before anything says. */
    val role: String? = null,
    /** Who finished it, by their whole name; null when nobody is named (their account went). */
    val finishedBy: String? = null,
    /** When it was finished, as the server wrote it. */
    val finishedAt: String? = null,
) {
    /** The others here now. */
    val others: Int get() = people.count { !it.me }

    /** This person owns it: only the owner finishes and reopens a pack. */
    val owner: Boolean get() = role == "owner"
}

/**
 * An invitation waiting for this person, in its parts: [who] invited them to [what] (a pack's name, or "the team X" when [team]), as [role] for a pack. The
 * card draws [what] in the pack's colour; [words] is the whole sentence.
 */
data class InviteUi(val id: Int, val who: String, val what: String, val team: Boolean, val role: String?, val error: String? = null) {
    val words: String get() = "$who invited you to ${if (team) "the team " else ""}$what${role?.let { " as $it" }.orEmpty()}"
}

/** A pack in the switcher's list. */
data class PackRowUi(val uuid: String, val name: String, val meta: String, val chips: List<String>, val finished: Boolean, val current: Boolean)

data class SwitcherUi(
    val invites: List<InviteUi> = emptyList(),
    /** The workspace open now: a pack's uuid, or null for the Library. */
    val current: String? = null,
    val library: String = "Personal",
    val packs: List<PackRowUi> = emptyList(),
    val teams: String = TEAMLESS,
    /** A load has answered for this account: an empty list then means no packs (not before, nor after only failed loads). */
    val loaded: Boolean = false,
    /** What became of the last invitation answered, when it did not go through: said here, since a toast would be under the switcher. */
    val answerError: String? = null,
    /** The last time the lists were asked for it did not work: what is shown is what was there. */
    val error: String? = null,
    /** An invitation is being answered, or a workspace opened. */
    val busy: Boolean = false,
)

/** The words of the switcher and the frame for a pack, the web's (WorkspaceSwitcher.jsx, TopBar.jsx), as plain functions. */
object PackWords {
    private val ROLES = mapOf("owner" to "Owner", "editor" to "Editor", "viewer" to "Viewer")

    fun role(role: String?): String = ROLES[role] ?: role.orEmpty()

    private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

    /** "3 LZ/PZ · 2 route sets · 1 point set", what a pack holds; empty for nothing. */
    fun counts(counts: PackItemCounts?): String = listOfNotNull(
        counts?.lz?.takeIf { it > 0 }?.let { "$it LZ/PZ" },
        counts?.route?.takeIf { it > 0 }?.let { plural(it, "route set") },
        counts?.pointset?.takeIf { it > 0 }?.let { plural(it, "point set") },
    ).joinToString(" · ")

    /** The Library's row: "Personal · 12 LZ/PZs, 4 route sets, 3 point sets". */
    fun library(counts: LibraryCounts?): String {
        if (counts == null) return "Personal"
        val what = listOf(plural(counts.lz, "LZ/PZ", "LZ/PZs"), plural(counts.routes, "route set"), plural(counts.points, "point set")).joinToString(", ")
        return "Personal · $what"
    }

    /** A pack's line: "4 members · you are Owner", or "Finished Sep 22 · read-only" (the date in [zone], the person's). */
    fun packMeta(pack: PackSummary, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        if (pack.status == "finished") {
            listOf("Finished", shortDate(pack.finishedAt, zone, today)).filter { it.isNotEmpty() }.joinToString(" ") + " · read-only"
        } else {
            "${plural(pack.audienceCount.takeIf { it > 0 } ?: pack.memberCount.coerceAtLeast(1), "member")} · you are ${role(pack.role)}"
        }

    /** The teams line: "You are not in a team yet", "B Co 2-10 AVN", "B Co 2-10 AVN and 1 other team". */
    fun teams(teams: List<TeamSummary>): String = when (teams.size) {
        0 -> TEAMLESS
        1 -> teams[0].name
        else -> "${teams[0].name} and ${plural(teams.size - 1, "other team")}"
    }

    /** An invitation in its parts: "Alex Park invited you to OP IBIS as Editor", or "… to the team B CO". */
    fun inviteUi(invite: Invite): InviteUi {
        val who = invite.invitedBy?.name?.takeIf { it.isNotBlank() } ?: "Someone"
        val team = invite.team
        return if (team != null && invite.pack == null) {
            InviteUi(invite.id, who, team.name, team = true, role = null)
        } else {
            InviteUi(invite.id, who, invite.pack?.name ?: "a pack", team = false, role = role(invite.role))
        }
    }

    /** The sentence of [invite]. */
    fun invite(invite: Invite): String = inviteUi(invite).words

    /**
     * "Sep 22" from a server time, in [zone] (the person's), as the web's `shortDate` does: with its offset, or without as UTC (what the server writes);
     * ", 2025" when it is not this year. Nothing for one that cannot be read.
     */
    fun shortDate(iso: String?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        if (iso.isNullOrBlank()) return ""
        val instant = try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                return ""
            }
        }
        val date = instant.atZone(zone).toLocalDate()
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val day = "${months[date.monthValue - 1]} ${date.dayOfMonth}"
        return if (date.year == today.year) day else "$day, ${date.year}"
    }

    /**
     * How the open pack stands, said under the chips (the web's top bar): opening, cannot be reached, read-only, changes going or waiting for a
     * connection, or everything synced, live or not.
     */
    fun status(pack: OpenPackUi): String = when {
        pack.status == PackStatus.LOADING -> "Opening…"
        pack.status == PackStatus.ERROR -> "Cannot reach the pack"
        pack.status == PackStatus.GONE -> "No longer available"
        pack.status == PackStatus.PAUSED -> "Paused · changes kept on this device"
        pack.finished -> "Read-only for everyone"
        pack.pending > 0 && pack.status == PackStatus.OFFLINE -> "Offline · changes will send when back"
        pack.pending > 0 -> "Saving changes…"
        pack.status == PackStatus.OFFLINE -> "Offline"
        pack.status == PackStatus.POLLING -> "All changes synced"
        else -> "Live · all changes synced"
    }

    /**
     * A finished pack in the web's words, in two sentences (the design sets the first apart): "OP DK was finished by Colin McFadden on Oct 5 at 14:20."
     * and "It is read-only for everyone.". By "its owner" when nobody is named; the date in [zone] (the person's), with the year when it is not [now]'s.
     */
    fun finished(pack: OpenPackUi, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): Pair<String, String> {
        val by = pack.finishedBy?.trim()?.takeIf { it.isNotEmpty() } ?: "its owner"
        val at = PackFacts.instant(pack.finishedAt)?.let { " on ${PackFacts.dateAtTime(it, zone, now)}" }.orEmpty()
        return "${pack.name} was finished by $by$at." to "It is read-only for everyone."
    }

    /** What the workspace chip says to a screen reader: the design's "Workspace: Mission Pack OP DK. 4 people here now: …. Change workspace". */
    fun chipDescription(pack: OpenPackUi?): String {
        if (pack == null) return "Workspace: Personal. Change workspace"
        if (pack.finished) return "Workspace: Mission Pack ${pack.name}, finished and read-only. Change workspace"
        val names = pack.people.map { it.name }
        val here = when {
            names.isEmpty() -> ""
            names.size == 1 -> " 1 person here now: ${names[0]}."
            names.size <= 3 -> " ${names.size} people here now: ${names.dropLast(1).joinToString(", ")} and ${names.last()}."
            else -> " ${names.size} people here now: ${names.take(3).joinToString(", ")} and ${names.size - 3} more."
        }
        return "Workspace: Mission Pack ${pack.name}.$here Change workspace"
    }

    /** What the tablet rail's workspace tile says (the design's AT screens): the chip's words, but a finished pack is "finished" alone. */
    fun railDescription(pack: OpenPackUi?): String =
        if (pack?.finished == true) "Workspace: Mission Pack ${pack.name}, finished. Change workspace" else chipDescription(pack)

    /** A person's initials, for their face in the chip: the account button's rule ([initialsOf]): "Colin McFadden" is CM, "Sam B." is SB. */
    fun initials(name: String): String = initialsOf(name)

    /** The open pack from the engine's state, named from its session or else its line in the list. */
    fun openPack(state: PackState, listed: List<PackSummary>, me: Int?): OpenPackUi {
        val session = state.session
        val row = listed.firstOrNull { it.uuid == state.uuid }
        val name = session?.pack?.get("name")?.let(::textOf) ?: row?.name ?: "Mission Pack"
        val finished = (session?.status ?: row?.status) == "finished"
        val people = state.people.mapNotNull { person(it, me) }.distinctBy { it.id ?: it.name }
        // The session says how it stands now; the list, only before the device holds it.
        val role = if (session != null) session.pack["role"]?.let(::textOf) else row?.role
        val by = if (session != null) (session.pack["finished_by"] as? JsonObject)?.get("name")?.let(::textOf) else row?.finishedBy?.name
        val at = if (session != null) session.pack["finished_at"]?.let(::textOf) else row?.finishedAt
        return OpenPackUi(
            state.uuid, name, people, state.status, finished, state.readOnly, session?.pending?.size ?: 0,
            role = role, finishedBy = by?.takeIf { it.isNotBlank() }, finishedAt = at,
        )
    }

    private fun textOf(element: kotlinx.serialization.json.JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun person(json: JsonObject, me: Int?): PersonUi? {
        val id = (json["user_id"] as? JsonPrimitive)?.intOrNull
        val name = (json["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        return PersonUi(id, name, initials(name), me != null && id == me)
    }
}

const val TEAMLESS = "You are not in a team yet"
