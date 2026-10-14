package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonObject

/** Where an open pack stands with the server. */
public enum class PackStatus {
    /** Nothing to show yet: the device holds no copy of the pack and the server has not answered. */
    LOADING,

    /** The device's own copy, which can be read and edited; the server has not answered yet (no signal, or it is down). */
    OFFLINE,

    /** In step with the server, asking it every few seconds what is new: there is no live stream, or it is down. */
    POLLING,

    /** In step with the server, which tells it of every change as it is made. */
    LIVE,

    /** It could not be loaded and the device has no copy: tried again by itself, less often the longer it fails. */
    ERROR,

    /**
     * Stopped for the account's sake, not the pack's: signed out, someone else signed in, not through the `.mil` gate, or Mission
     * Packs turned off for this person. Every edit waits, whole, until the account can send it (owner decision, 2026-10-08).
     */
    PAUSED,

    /** Deleted, or no longer this person's to see. Edits it had not taken are kept in the library. */
    GONE,
}

/**
 * One open pack as the screens see it. [items] is what the person sees, their own edits included, in order ([PackItemView]: its
 * `data` is the session's own instance, so a screen can tell cheaply whether it changed). [people] is who has it open, as the live
 * stream says (`{session, user_id, name, focus}` each); [error] is why it last could not be loaded or followed.
 */
public data class PackState(
    val uuid: String,
    val status: PackStatus,
    val session: PackSession?,
    val items: List<PackItemView>,
    val people: List<JsonObject>,
    val error: PackFailure?,
) {
    /** The item [uuid] as the person sees it, or null when it is not there (or deleted). */
    public fun item(uuid: String): PackItemView? = items.firstOrNull { it.uuid == uuid }

    /** Finished, or this person may only view it: nothing they change is taken. */
    public val readOnly: Boolean get() = session?.readOnly == true
}

/**
 * How often the client asks, in milliseconds: [pollMs] for what is new when there is no live stream; [presenceMs] at most between
 * two of this person's presence frames; [retryMs] the pauses before a load, a send or the stream is tried again, the last repeated;
 * [eventsPage] how many events one page of the log asks for (the server gives 500 at most).
 */
public data class PackTiming(
    val pollMs: Long = 3_000,
    val presenceMs: Long = 100,
    val retryMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
    val eventsPage: Int = 500,
) {
    init {
        require(retryMs.isNotEmpty()) { "A retry needs at least one pause" }
    }

    /** The pause before try [attempt] (0 for the first retry). */
    public fun retry(attempt: Int): Long = retryMs[minOf(attempt, retryMs.size - 1)]
}

/** The person signed in, as the packs know them: their id, and the name the history gives their changes. */
public data class PackUser(val id: Int, val name: String)

/** What happened to a pack that the person should hear about, whichever screen they are on. Never the server's own words. */
public sealed interface PackNotice {
    /** The pack it is about. */
    public val pack: String

    /** While the pack is open, it would not take [count] of the person's edits ([reasons]: `pack_finished`, `read_only`, `gone`, …). */
    public data class Dropped(override val pack: String, val count: Int, val reasons: Set<String>) : PackNotice

    /**
     * The person's own version of the items the pack would not take their edits to was saved to their library ([saved]). Said by the engine for
     * edits a pack dropped, and by an editor for a change it held back (the pack paused, closed or gone) when its document closed first.
     */
    public data class Kept(
        override val pack: String,
        val packName: String?,
        val saved: List<KeptOutcome.Saved>,
        val reasons: Set<String>,
    ) : PackNotice

    /** A version the pack would not take had nothing to keep ([reason]: `empty_point_set`). */
    public data class NothingToKeep(override val pack: String, val key: String, val reason: String) : PackNotice

    /** [count] of the person's edits reached the pack after what they changed was gone (deleted by someone else), and changed nothing. */
    public data class OwnSkipped(override val pack: String, val count: Int) : PackNotice

    /** The pack was deleted or is no longer the person's to see ([why]: `not_found`, `forbidden`, `removed`). */
    public data class Gone(override val pack: String, val why: String) : PackNotice

    /** Sending and following stopped for the account's sake ([code]: `feature_disabled`, `other_account`, …); nothing was dropped. */
    public data class Paused(override val pack: String, val code: String?) : PackNotice

    /**
     * A change made in an editor was put back to the pack's version, because the pack would not take it ([reason]: `read_only`, `paused`,
     * `gone`, `closed`, …), or nothing was made ([localId] null: a new item the pack refused). Said by the editors joined to the open pack
     * (core-data's `PackWorkspace`), never by the engine: its own refusals of edits already on their way are [Dropped].
     */
    public data class Refused(override val pack: String, val localId: String?, val reason: String) : PackNotice

    /**
     * The item open in an editor ([localId], called [name]) left it: someone removed it, the pack is gone, or the pack is no longer the open one
     * (closed or turned off under it). [kept] is what was made of a change here that had not been sent: the person's version, saved to their
     * library as "NAME (my edits)"; null when there was none, when it is not kept yet (nobody's packs were on: it is kept once its account's
     * are, and said by [Kept]), or when another account's packs are on (never kept into theirs). Said by the editors joined to the open pack,
     * never by the engine.
     */
    public data class ItemRemoved(override val pack: String, val localId: String, val name: String, val kept: KeptOutcome.Saved?) : PackNotice
}
