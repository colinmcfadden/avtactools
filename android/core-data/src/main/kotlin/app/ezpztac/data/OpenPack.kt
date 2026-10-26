package app.ezpztac.data

import app.ezpztac.missionpacks.PackItemView
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.missionpacks.PackSession
import app.ezpztac.missionpacks.PackState
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.DiagramTarget
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the LZ/PZ and Routes panels do with the open Mission Pack (docs/ANDROID_SCREENS_PLAN.md, P2b): which pack is open and what it holds, and making,
 * opening and closing its items in the editors. [PackWorkspace] in the app; [None] where packs do not run.
 */
interface OpenPack {
    /** The open pack as the engine holds it; null in the Library. */
    val state: StateFlow<PackState?>

    /**
     * What the panels draw of the open pack ([PackGlance]), once for each change they would show: a presence frame that only moves someone's pointer (up to ten
     * times a second for each person) is the same glance, and is not passed on.
     */
    val glance: Flow<PackGlance?> get() = state.map { it?.let(PackGlance::of) }.distinctUntilChanged()

    /** A new LZ/PZ at [target] in the open pack, opened; its local id, or null when it was not made (the person is told why). */
    suspend fun createLz(target: DiagramTarget, name: String? = null): String?

    /** A new, empty route set in the open pack, opened; its local id, or null when it was not made. */
    suspend fun createRouteSet(name: String? = null): String?

    /** Opens [itemUuid] of the open pack in its editor; false when it has none, or is not in the pack. */
    suspend fun openItem(itemUuid: String): Boolean

    /** Closes [itemUuid] in its editor; it stays in the pack. */
    suspend fun closeItem(itemUuid: String)

    /** Takes [itemUuid] out of the pack, for everyone in it; null once it is on its way, else why not (`read_only`, `loading`, …). */
    suspend fun removeItem(itemUuid: String): String?

    /** The open pack's lock ([PackLock]), once for each change: null outside a pack and while the open one can be changed. A presence frame never changes it. */
    val lock: Flow<PackLock?> get() = state.map { it?.let(PackLock::of) }.distinctUntilChanged()

    /** The open pack's lock now, for a refusal made as an action is asked for. */
    fun lockNow(): PackLock? = state.value?.let(PackLock::of)

    companion object {
        /** No pack ever open: the panels as they were before packs. */
        val None: OpenPack = object : OpenPack {
            override val state: StateFlow<PackState?> = MutableStateFlow(null)

            override suspend fun createLz(target: DiagramTarget, name: String?): String? = null

            override suspend fun createRouteSet(name: String?): String? = null

            override suspend fun openItem(itemUuid: String): Boolean = false

            override suspend fun closeItem(itemUuid: String) = Unit

            override suspend fun removeItem(itemUuid: String): String? = "missing"
        }
    }
}

/**
 * The open pack is read-only (finished by its owner, or this person a viewer in it), and why, in words for each place that says it: nothing in it may be changed, so
 * the editors offer nothing that would change one of its items, and refuse what is asked anyway with [reason] (P2c, docs/ANDROID_SCREENS_PLAN.md). Its briefs are
 * the exception ([briefs]): a brief is open to every member in every state of the pack (docs/MISSION_BRIEF.md §3), and nothing else is.
 */
data class PackLock(
    val pack: String,
    val name: String,
    /** "OP DK is finished: nothing in it can be changed.", or "You can view OP DK but not change it." */
    val reason: String,
    /** A tool's line where it is off: "Off in a finished pack" (the design's), "Off: you can view this pack, not change it". */
    val short: String,
    val finished: Boolean,
    /** The pack's brief items, which the lock lets through. */
    val briefs: Set<String> = emptySet(),
) {
    /** What was under way when it turned read-only is said to have stopped after this: "OP DK was finished", "You can only view OP DK now". */
    val stopped: String get() = if (finished) "$name was finished" else "You can only view $name now"

    /** A plan's line under its title: "Read-only · OP DK is finished" (the design's), "Read-only · you can view OP DK". */
    val pane: String get() = if (finished) "Read-only · $name is finished" else "Read-only · you can view $name"

    /** Why [documentId] cannot be changed: it is an item of this pack. Null for the person's own, another pack's, and this pack's briefs. */
    fun reasonFor(documentId: String?): String? = reason.takeIf { PackRef.parse(documentId)?.let { it.pack == pack && it.item !in briefs } == true }

    companion object {
        /** The lock of [state], or null while its pack can be changed (or before the device holds it, when nothing is known: edits wait then, never lost). */
        fun of(state: PackState): PackLock? {
            if (!state.readOnly) return null
            val finished = PackFacts.finished(state)
            val name = PackFacts.name(state)
            return PackLock(
                pack = state.uuid,
                name = name,
                reason = if (finished) "$name is finished: nothing in it can be changed." else "You can view $name but not change it.",
                short = if (finished) "Off in a finished pack" else "Off: you can view this pack, not change it",
                finished = finished,
                briefs = state.items.filter { it.kind == "brief" }.mapTo(HashSet()) { it.uuid },
            )
        }
    }
}

/**
 * The open pack as the LZ/PZ and Routes panels draw it: its name and how it stands, its items, which of them wait for the pack to take a change of this person's,
 * and who else has each open. Nothing that moves with a pointer, so two states that differ only in where someone is pointing are the same glance. Items are
 * compared by their data's instance, never its contents (an item no change touched keeps its instance, and an item can be megabytes).
 */
class PackGlance private constructor(
    val uuid: String,
    /** The pack's name; "the pack" before the device holds it. */
    val name: String,
    /** The device holds the pack: false while it opens for the first time, or cannot be reached ([status] says which). */
    val loaded: Boolean,
    val status: PackStatus,
    val readOnly: Boolean,
    /** "live", "live · view only", "finished · read-only", "paused" ([PackFacts.standing]). */
    val standing: String,
    /** "Saved to OP DK as you work. 4 members can see it." */
    val savedLine: String,
    /** Why nothing in it can be changed, while it is read-only. */
    val lock: PackLock?,
    /** The open item's foot while it is read-only: "Finished by Colin M. on Oct 5 at 14:20", "In OP DK. You can view it but not change it." */
    val lockedLine: String?,
    /** What a panel says at its foot while the pack is read-only (the web's hint): it is finished, or this person a viewer. */
    val lockedHint: String?,
    val items: List<PackItemView>,
    private val pending: Set<String>,
    private val openBy: Map<String, List<String>>,
) {
    /** The item [localId] names, or null when it is not one of this pack's (the person's own, another pack's) or is no longer there. */
    fun itemOf(localId: String?): PackItemView? {
        val ref = localId?.let(PackRef::parse) ?: return null
        return if (ref.pack == uuid) items.firstOrNull { it.uuid == ref.item } else null
    }

    /** The pack has not yet taken a change of this person's to [item] ([PackFacts.waiting]). */
    fun waiting(item: PackItemView): Boolean = item.pendingCreate || item.uuid in pending

    /** The others who have [itemUuid] open now, by their short names ([PackFacts.withItOpen]). */
    fun withItOpen(itemUuid: String): List<String> = openBy[itemUuid].orEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackGlance) return false
        return uuid == other.uuid && name == other.name && loaded == other.loaded && status == other.status && readOnly == other.readOnly &&
            standing == other.standing && savedLine == other.savedLine && lock == other.lock && lockedLine == other.lockedLine && lockedHint == other.lockedHint &&
            pending == other.pending && openBy == other.openBy && sameItems(items, other.items)
    }

    override fun hashCode(): Int = uuid.hashCode() * 31 + items.size

    companion object {
        /** The glance at [state]; dates in [zone], a year said only when it is not [now]'s. */
        fun of(state: PackState, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): PackGlance {
            val session = state.session
            val loaded = session != null
            val pending = session?.pending.orEmpty().mapNotNull { (it.op["item"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
            val openBy = state.items.associate { it.uuid to PackFacts.withItOpen(state, it.uuid) }.filterValues { it.isNotEmpty() }
            return PackGlance(
                uuid = state.uuid, name = if (loaded) PackFacts.name(state) else "the pack", loaded = loaded, status = state.status, readOnly = state.readOnly,
                standing = PackFacts.standing(state), savedLine = if (loaded) PackFacts.savedLine(state) else "Saved to the pack as you work.",
                lock = PackLock.of(state), lockedLine = PackFacts.lockedLine(state, zone, now), lockedHint = PackFacts.lockedHint(state),
                items = state.items, pending = pending, openBy = openBy,
            )
        }

        // The same items: the same names and kinds, and the same instance of each one's data (its contents are never walked).
        private fun sameItems(a: List<PackItemView>, b: List<PackItemView>): Boolean {
            if (a === b) return true
            if (a.size != b.size) return false
            return a.indices.all { i ->
                val x = a[i]
                val y = b[i]
                x === y || (x.uuid == y.uuid && x.kind == y.kind && x.name == y.name && x.pendingCreate == y.pendingCreate && x.data === y.data && x.info == y.info)
            }
        }
    }
}

/** What the panels say of an open pack and its items, read from the engine's state (the web's words, usePackWorkspace.jsx and the panels). */
object PackFacts {
    /** The pack's name, or "Mission Pack" before it has one. */
    fun name(state: PackState): String = state.session?.let { text(it.pack["name"]) } ?: "Mission Pack"

    /** The open pack's item [localId] names, or null when it is not one (the person's own) or no longer there. */
    fun itemOf(state: PackState?, localId: String?): PackItemView? {
        val ref = localId?.let(PackRef::parse) ?: return null
        if (state == null || ref.pack != state.uuid) return null
        return state.item(ref.item)
    }

    /** Who can see the pack (packAudience): its members, and with a team the team's too, never fewer than its members. */
    fun audience(session: PackSession): Int {
        val own = session.members.size
        val shared = when (val team = session.pack["team"]) {
            null, JsonNull -> false
            is JsonPrimitive -> team.booleanOrNull ?: (team.content.isNotEmpty() && team.content != "0")
            else -> true                                                                           // the team, as the server writes it
        }
        return if (shared) maxOf(own, (session.pack["audience_count"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0) else own
    }

    /** The item has a change of this person's not yet taken by the pack: made here and not confirmed, or an edit to it on its way (the web's "Waiting to send"). */
    fun waiting(state: PackState, item: PackItemView): Boolean =
        item.pendingCreate || state.session?.pending.orEmpty().any { text(it.op["item"]) == item.uuid }

    /** Where the item was copied from, if it was. */
    fun fromLibrary(item: PackItemView): Boolean = item.info?.get("source") is JsonObject

    /** What became of the original it was copied from: `changed`, `deleted`, or null (told only to whoever copied it in). */
    fun original(item: PackItemView): String? = text((item.info?.get("source") as? JsonObject)?.get("original"))

    /** The others who have [itemUuid] open now (the live service's `focus.item`), each once, by their short names. */
    fun withItOpen(state: PackState, itemUuid: String): List<String> {
        val me = state.session?.me
        return state.people
            .filter { (it["user_id"] as? JsonPrimitive)?.intOrNull != me }
            .filter { text((it["focus"] as? JsonObject)?.get("item")) == itemUuid }
            .mapNotNull { text(it["name"]) }
            .map(::shortName)
            .distinct()
    }

    /** "Sam Bell" is "Sam B."; one word stays whole; nothing is "Someone" (Avatar.jsx's `shortName`). Never half a character. */
    fun shortName(name: String?): String {
        // JavaScript's \s: the ASCII spaces, every Unicode space separator and the byte order mark (Java's \s is the ASCII ones only).
        val words = name.orEmpty().split(JS_SPACES).filter { it.isNotEmpty() }
        if (words.size < 2) return words.firstOrNull() ?: "Someone"
        val last = words.last()
        return "${words.first()} ${String(Character.toChars(last.codePointAt(0)))}."
    }

    /** "Sam B. has it open", "Sam B. and Jess R. have it open"; empty for nobody. */
    fun hasItOpen(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names[0]} has it open"
        else -> names.dropLast(1).joinToString(", ") + " and ${names.last()} have it open"
    }

    /** "Sam B. is also here", "Sam B. and 2 more are also here" (the web's ActiveCard); null when nobody else is. */
    fun alsoHere(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "${names[0]} is also here"
        else -> "${names[0]} and ${names.size - 1} more are also here"
    }

    /**
     * How the pack stands, for a panel's head: "live", "live · view only", "finished · read-only" (the web's), and "paused" while it is stopped for the account's
     * sake, when nothing is sent; "opening" and "cannot be reached" before the device holds it, "no longer available" once it is gone.
     */
    fun standing(state: PackState): String = when {
        state.status == PackStatus.GONE -> "no longer available"
        state.session == null -> if (state.status == PackStatus.ERROR) "cannot be reached" else "opening"
        state.status == PackStatus.PAUSED -> "paused"
        state.session?.let { text(it.pack["status"]) } == "finished" -> "finished · read-only"
        state.readOnly -> "live · view only"
        else -> "live"
    }

    /** The pack has been finished by its owner: read-only for everyone until it is reopened. */
    fun finished(state: PackState): Boolean = state.session?.let { text(it.pack["status"]) } == "finished"

    /** Said when something new is asked for in a read-only pack (the web's words). */
    const val NOTHING_NEW = "This pack is read-only, so nothing new can be added to it."

    /**
     * The open item's foot while the pack is read-only: "Finished by Colin M. on Oct 5 at 14:20" (the design's; "the owner" when the account has gone, and no date
     * when there is none), or "In OP DK. You can view it but not change it." (the web's); null while it can be changed.
     */
    fun lockedLine(state: PackState, zone: ZoneId, now: Instant): String? {
        if (!state.readOnly) return null
        if (!finished(state)) return "In ${name(state)}. You can view it but not change it."
        val pack = state.session?.pack
        val by = text((pack?.get("finished_by") as? JsonObject)?.get("name"))?.takeIf { it.isNotBlank() }?.let(::shortName) ?: "the owner"
        val at = instant(text(pack?.get("finished_at")))
        return "Finished by $by" + if (at != null) " on ${dateAtTime(at, zone, now)}" else ""
    }

    /** What the panels say at their foot while the pack is read-only (the web's hint, less the LZ card export Android has not got). */
    fun lockedHint(state: PackState): String? = when {
        !state.readOnly -> null
        finished(state) -> "This pack is finished: you can look at everything in it and export its routes, but nothing can be changed."
        else -> "You are a viewer in this pack: you can look at everything in it, but not change it. Its owner can make you an editor."
    }

    /** A server time: with its offset, or without as UTC (the API writes naive UTC times); null for none or one that cannot be read. */
    fun instant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        // Which it is is told from the text first, so the common case (no offset) parses once and throws nothing.
        return try {
            if (OFFSET.containsMatchIn(iso)) OffsetDateTime.parse(iso).toInstant() else LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // A time that says where it is: "Z", or "+05:00" / "-0500" at its end.
    private val OFFSET = Regex("(?:[zZ]|[+-]\\d\\d:?\\d\\d)$")

    /** "13:42", the clock in [zone]; 24-hour, as crews read it. */
    fun clockTime(at: Instant, zone: ZoneId): String {
        val time = at.atZone(zone)
        return "${pad(time.hour)}:${pad(time.minute)}"
    }

    /** "Oct 4", or "Oct 4, 2025" in another year. */
    fun shortDate(at: Instant, zone: ZoneId, now: Instant): String {
        val date = at.atZone(zone).toLocalDate()
        val day = "${MONTHS[date.monthValue - 1]} ${date.dayOfMonth}"
        return if (date.year == now.atZone(zone).year) day else "$day, ${date.year}"
    }

    /** "Oct 5 at 14:20", or "Oct 5, 2025 at 14:20" in another year (the web's `dateAtTime`). */
    fun dateAtTime(at: Instant, zone: ZoneId, now: Instant): String = "${shortDate(at, zone, now)} at ${clockTime(at, zone)}"

    private fun pad(n: Int) = if (n < 10) "0$n" else n.toString()

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Saved to OP DK as you work. 4 members can see it." (the web's card foot). */
    fun savedLine(state: PackState): String {
        val members = state.session?.let(::audience) ?: 0
        return "Saved to ${name(state)} as you work. $members ${if (members == 1) "member" else "members"} can see it."
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** What JavaScript's `\s` matches: Java's own, the space separators (no-break, the ideographic and the rest) and U+FEFF. */
private val JS_SPACES = Regex("[\\s\\p{Z}\\x{FEFF}]+")
