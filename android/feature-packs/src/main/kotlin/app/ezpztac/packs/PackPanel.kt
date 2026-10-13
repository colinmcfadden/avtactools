package app.ezpztac.packs

import app.ezpztac.data.PackFacts
import app.ezpztac.missionpacks.KeptOutcome
import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackNotice
import app.ezpztac.network.PackEvent
import app.ezpztac.network.PackEventPage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the Pack panel does with the open pack beyond what the switcher does: its history and how far this person has looked in it (the web's
 * `getEvents`, `markSeen`), and its items: opened in or closed from their editor, renamed and removed for everyone (core-data's `PackWorkspace`).
 */
interface PackPanelDoor {
    /** The items of the open pack open in the editors now, by uuid. */
    val openHere: Flow<Set<String>>

    /** What happened to the packs and to the items open in the editors that the person should hear about. */
    val notices: Flow<PackNotice>

    /** A page of [pack]'s history after [since], oldest first. */
    suspend fun events(pack: String, since: Long, limit: Int): PackEventPage

    /** Moves how far this person has looked in [pack] to [seq]; how far the server now says they have. */
    suspend fun markSeen(pack: String, seq: Long): Long

    /** Opens [item] in its editor; false when it has none (a point set) or is not in the pack. */
    suspend fun openItem(item: String): Boolean

    /** Closes [item] in its editor, where it is open; it stays in the pack. */
    suspend fun closeItem(item: String)

    /** Renames [item] for everyone; null once it is on its way, else why not (`PackWorkspace`'s reasons). */
    suspend fun renameItem(item: String, name: String): String?

    /** Removes [item] from the pack for everyone; null once it is on its way, else why not. */
    suspend fun removeItem(item: String): String?
}

/** The Pack panel's two views: what the pack holds, and what has happened in it. */
enum class PackTab { ITEMS, HISTORY }

/**
 * One item of the pack in the panel (the design's PackItems row): its name, a dot when someone else changed it since this person looked ([isNew]),
 * who changed it last and when ([meta]), whether it came from someone's Library and what became of the original ([original]: `changed`, `deleted`),
 * who else has it open ([editing]), and whether it is open here. [description] is the row for a screen reader.
 */
data class PackItemUi(
    val uuid: String,
    val kind: String,
    val name: String,
    val isNew: Boolean,
    val who: PersonUi?,
    val meta: String,
    val fromLibrary: Boolean,
    val original: String?,
    val editing: List<String>,
    val openHere: Boolean,
    val sending: Boolean,
    val canOpen: Boolean,
    val description: String,
)

/** The items of one kind, under their heading ("LZ/PZ", "Routes", "Point sets"). */
data class ItemGroupUi(val kind: String, val title: String, val items: List<PackItemUi>)

/**
 * One event of the history: [lead] is said first and stands out (the person who did it, or "Edit skipped:"), then [rest]; [meta] is when and on what
 * ("13:42 · LZ IBIS"). [who] is null for the pack's own events. [fresh] while it is new since this person looked.
 */
data class EventUi(val seq: Long, val who: PersonUi?, val lead: String, val rest: String, val skipped: Boolean, val meta: String, val fresh: Boolean)

/** Events under one heading: "New since you looked · 3", "Earlier today", "Yesterday", "Oct 4". */
data class EventGroupUi(val title: String, val fresh: Boolean, val events: List<EventUi>)

/** A choice of a history filter: what it says, and what it keeps (null for everything). */
data class FilterChoice(val label: String, val key: String?)

/** The history as shown: [groups] newest first, and the two filters, whose person, which item. */
data class HistoryUi(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val groups: List<EventGroupUi> = emptyList(),
    val people: List<FilterChoice> = listOf(FilterChoice(EVERYONE, null)),
    val person: FilterChoice = FilterChoice(EVERYONE, null),
    val items: List<FilterChoice> = listOf(FilterChoice(ALL_ITEMS, null)),
    val item: FilterChoice = FilterChoice(ALL_ITEMS, null),
) {
    /** What the list says when there is nothing in it: still loading, could not load, nothing matches; null with something to show. */
    val emptyText: String?
        get() = when {
            groups.isNotEmpty() -> null
            failed -> FAILED
            loading -> "Loading the history…"
            else -> "Nothing matches."
        }

    /** A load that failed once some events are shown: said above them, as the web says it, so an old list is not taken for the whole. */
    val failure: String? get() = if (failed && groups.isNotEmpty()) FAILED else null

    companion object {
        const val EVERYONE = "Everyone"
        const val ALL_ITEMS = "All items"
        const val FAILED = "The history could not be loaded. It is tried again when the pack changes."
    }
}

/**
 * The Pack panel (the design's PackItems and PackHistory, the web's PackPanel): the pack's name and what this person is in it, how it stands, what
 * it is for, who is in it and who is here now, then its items by kind or its history. [loaded] is false until the pack's copy is on the device.
 */
data class PackPanelUi(
    val uuid: String? = null,
    val name: String = "",
    val loaded: Boolean = false,
    val unreachable: Boolean = false,
    val subtitle: String = "",
    val finished: Boolean = false,
    val readOnly: Boolean = false,
    val canEdit: Boolean = false,
    /** This person owns the pack: Members offers them inviting, and the panel an *Invite* of its own. */
    val owner: Boolean = false,
    val description: String? = null,
    val members: Int = 0,
    val faces: List<PersonUi> = emptyList(),
    val here: Int = 0,
    val tab: PackTab = PackTab.ITEMS,
    val newCount: Int = 0,
    val groups: List<ItemGroupUi> = emptyList(),
    val history: HistoryUi = HistoryUi(),
) {
    /** The members line: "4 members · 2 here now". */
    val membersLine: String
        get() = PackPanelWords.count(members, "member", "members") + if (here > 0) " · $here here now" else ""

    /** The members row as a screen reader hears it (the design's): "4 members, 2 here now. Manage members". */
    val membersButton: String
        get() = PackPanelWords.count(members, "member", "members") + (if (here > 0) ", $here here now" else "") + ". Manage members"

    /** A dot on History while something someone else changed since this person looked is waiting there. */
    val historyHasNew: Boolean get() = newCount > 0 && tab != PackTab.HISTORY

    /** What the Items view says when the pack holds nothing. */
    val emptyItems: String?
        get() = if (groups.isNotEmpty() || !loaded) {
            null
        } else if (readOnly) {
            "This pack is empty."
        } else {
            "Nothing in this pack yet. Set a target or sketch a route while the pack is open, and it goes into the pack."
        }
}

/** The Pack panel's words, the web's (PackPanel.jsx, usePackWorkspace.jsx, time.js), with times in the person's zone. */
object PackPanelWords {
    private val GROUPS = listOf("lz" to "LZ/PZ", "route" to "Routes", "pointset" to "Point sets")
    private val ROLE_WORDS = mapOf("owner" to "the owner", "editor" to "an editor", "viewer" to "a viewer")

    // Why the server did not apply an edit (pack_ops.py).
    private val REASON_WORDS = mapOf(
        "item_missing" to "the item had already been removed",
        "target_missing" to "what it changed had already been removed",
        "item_exists" to "the same item had already been added",
        "element_exists" to "the same thing had already been added",
        "not_an_array" to "the item had changed shape under it",
        "not_an_object" to "the item had changed shape under it",
    )

    // Why a change made here was not taken by the pack (usePackWorkspace.jsx's REFUSED_WORDS, and the engine's other refusals).
    private val REFUSED_WORDS = mapOf(
        "read_only" to "you can only look at this pack, so changes are not saved to it",
        "gone" to "the pack is no longer available",
        "loading" to "the pack is still opening",
        "closed" to "no pack is open",
        "empty_point_set" to "a point set needs at least one point",
        "paused" to "sending to the pack is paused for now",
        "missing" to "it is no longer in the pack",
    )

    // Why edits on their way were never taken (usePackWorkspace.jsx's LOST_WORDS).
    private val LOST_WORDS = mapOf(
        "pack_finished" to "the pack was finished",
        "read_only" to "you can only look at it now",
        "gone" to "it is no longer available to you",
        "signed_out" to "you signed out before they were sent",
    )

    /** "1 member", "4 members". */
    fun count(n: Int, one: String, many: String): String = "${withCommas(n)} ${if (n == 1) one else many}"

    /** "Mission Pack · you are the owner". */
    fun subtitle(role: String?): String = "Mission Pack · you are ${ROLE_WORDS[role] ?: "a member"}"

    /** "Sam Bell" is "Sam B."; one word stays whole; nothing is "Someone" (Avatar.jsx's `shortName`). */
    fun shortName(name: String?): String = PackFacts.shortName(name)

    /** A server time: with its offset, or without as UTC (the API writes naive UTC times); null for none or one that cannot be read. */
    fun instant(iso: String?): Instant? = PackFacts.instant(iso)

    /** "Sam B. has it open", "Sam B. and Jess R. have it open". */
    fun hasItOpen(names: List<String>): String = PackFacts.hasItOpen(names)

    /** "13:42", the clock in [zone]; 24-hour, as crews read it. */
    fun clockTime(at: Instant, zone: ZoneId): String = PackFacts.clockTime(at, zone)

    /** "Oct 4", or "Oct 4, 2025" in another year. */
    fun shortDate(at: Instant, zone: ZoneId, now: Instant): String = PackFacts.shortDate(at, zone, now)

    /**
     * When an item last changed: "just now", "4 min ago" within the hour, "13:21" earlier today, else "Oct 4" (the design's words). **Not like the
     * web**, whose `whenText` gives a clock time for up to a day, yesterday's included: here a time on another day is its date.
     */
    fun whenText(iso: String?, zone: ZoneId, now: Instant): String {
        val at = instant(iso) ?: return ""
        val minutes = Math.floorDiv(now.toEpochMilli() - at.toEpochMilli() + 30_000, 60_000)       // JavaScript's Math.round of the minutes
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() -> clockTime(at, zone)
            else -> shortDate(at, zone, now)
        }
    }

    /** The history's day headings: "Earlier today", "Yesterday", else the date. */
    fun dayLabel(at: Instant, zone: ZoneId, now: Instant): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> "Earlier today"
            today.minusDays(1) -> "Yesterday"
            else -> if (day.isAfter(today)) "Earlier today" else shortDate(at, zone, now)
        }
    }

    /** What an item holds, beside its name: "2 routes", "1,234 points"; nothing for an LZ/PZ. */
    fun detail(item: PackItemView): String? = when (item.kind) {
        "route" -> ((item.data as? JsonObject)?.get("routes") as? JsonArray)?.size.let { count(it ?: 0, "route", "routes") }
        "pointset" -> count((item.data as? JsonArray)?.size ?: 0, "point", "points")
        else -> null
    }

    /** The headings items are grouped under, in the web's order: LZ/PZ, routes, point sets (a kind this version does not know is left out). */
    fun groups(): List<Pair<String, String>> = GROUPS

    /** A kind in words, for a sentence: "LZ/PZ", "route set", "point set". */
    fun kindWords(kind: String): String = when (kind) {
        "lz" -> "LZ/PZ"
        "route" -> "route set"
        "pointset" -> "point set"
        else -> "item"
    }

    /** The history's line for [event]: who first, then what; a skipped edit says why it changed nothing (PackPanel.jsx's `eventLine`). */
    fun eventLine(event: PackEvent, itemName: String?): Pair<String, String> {
        if (event.status == "skipped") {
            val why = REASON_WORDS[event.reason] ?: "it no longer applied"
            val what = event.summary.takeIf { it.isNotEmpty() }?.let { " (${it.removeSuffix(".")})" }.orEmpty()
            return "Edit skipped:" to " $why. ${shortName(event.actor.name)}’s change was not applied$what."
        }
        val summary = event.summary.ifEmpty { "${shortName(event.actor.name)} changed ${itemName ?: "the pack"}." }
        val actor = event.actor.name
        return if (actor.isNotEmpty() && event.actor.id != null && summary.startsWith(actor)) actor to summary.substring(actor.length) else "" to summary
    }

    /** Where an event happened: the item's name, else the name it had, else "Removed item"; "Members" or "Pack" for the pack's own. */
    fun where(event: PackEvent, itemName: String?): String {
        if (event.item != null) return itemName ?: (event.op["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Removed item"
        return if (event.type.startsWith("member") || event.type.startsWith("invite")) "Members" else "Pack"
    }

    /** "That change was not saved to the pack: …." */
    fun refused(reason: String): String = "That change was not saved to the pack: ${REFUSED_WORDS[reason] ?: "the pack would not take it"}."

    /**
     * What a notice says, in a toast; null for what is said elsewhere (a pack gone: the switcher says it as it leaves). [packName] names the pack
     * a notice is about when it is the open one. [stillOpen] is whether that pack is still open and there: an item leaves the editor when it was
     * removed from an open pack, and also when its pack goes or is closed, which is not its removal (then only what was kept of it is said).
     */
    fun notice(notice: PackNotice, packName: String?, stillOpen: Boolean = true): String? {
        val pack = packName ?: "the pack"
        return when (notice) {
            is PackNotice.Refused -> refused(notice.reason)
            is PackNotice.ItemRemoved -> if (stillOpen) {
                "“${notice.name}” was removed from the pack." + notice.kept?.let { " Your changes to it are in your Library as “${it.name}”." }.orEmpty()
            } else {
                notice.kept?.let { "Your changes to “${notice.name}” are in your Library as “${it.name}”." }
            }
            is PackNotice.Kept -> {
                val what = if (notice.saved.size == 1) "A change" else "${notice.saved.size} changes"
                val why = notice.reasons.firstNotNullOfOrNull { LOST_WORDS[it] } ?: "the pack would not take them"
                "$what to ${notice.packName ?: pack} could not be saved: $why. ${keptWords(notice.saved)}"
            }
            is PackNotice.Dropped -> {
                val what = if (notice.count == 1) "One of your changes was" else "${notice.count} of your changes were"
                val why = notice.reasons.firstNotNullOfOrNull { LOST_WORDS[it] } ?: "the pack would not take them"
                "$what not taken by $pack: $why. Your version goes to your Library when you leave the pack."
            }
            is PackNotice.NothingToKeep -> "A change could not be kept: ${REFUSED_WORDS[notice.reason] ?: "there was nothing to keep"}."
            is PackNotice.OwnSkipped ->
                (if (notice.count == 1) "One of your changes" else "${notice.count} of your changes") + " changed nothing, as what it changed was already gone. The history says which."
            is PackNotice.Paused -> null                                                       // the status line says so, and why it waits
            is PackNotice.Gone -> null
        }
    }

    private fun keptWords(saved: List<KeptOutcome.Saved>): String = when (saved.size) {
        0 -> ""
        1 -> "It is in your Library as “${saved[0].name}”."
        else -> "They are in your Library: " + saved.joinToString(", ") { "“${it.name}”" } + "."
    }

    private fun withCommas(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}

/** What the server says of an item beside its content (`PackItemView.info`). */
internal object ItemInfo {
    /** The event that last changed it. */
    fun seq(item: PackItemView): Long? = (item.info?.get("seq") as? JsonPrimitive)?.longOrNull

    /** Who changed it last, else who made it: `{id, name}`. */
    fun who(item: PackItemView): JsonObject? = (item.info?.get("updated_by") as? JsonObject) ?: (item.info?.get("created_by") as? JsonObject)

    fun whoId(person: JsonObject?): Int? = (person?.get("id") as? JsonPrimitive)?.intOrNull

    fun whoName(person: JsonObject?): String? = (person?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun updatedAt(item: PackItemView): String? = text(item.info?.get("updated_at")) ?: text(item.info?.get("created_at"))

    /** Where it was copied from, if it was. */
    fun source(item: PackItemView): JsonObject? = item.info?.get("source") as? JsonObject

    fun original(item: PackItemView): String? = text(source(item)?.get("original"))

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}
