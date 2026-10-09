package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonElement

/**
 * Saves a person's own version of pack items to their library, as new records: what is kept of edits a pack would not take
 * (it was finished, they were made a viewer or removed, or it was deleted, while the edits were on their way). Nothing a person
 * made is lost silently: once their pack is not open, such edits are kept here without asking (owner decision, 2026-10-08).
 * The library's own store in the app (core-data), a recording one in tests.
 */
public interface PackKeeper {
    /**
     * Saves each of [versions] (from [pack]) to the library, and says what became of each by its key. Keeping one again under the
     * same key (the app stopped between keeping and clearing) changes nothing and answers as the first time. A version left out of
     * the answer is not kept and is offered again later; a failure that keeps nothing throws.
     */
    public suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome>
}

/**
 * One item's version to keep. [key] names it for good (`pack:item:first dropped client_op_id`), so keeping it twice makes one
 * record. [name] is already the kept name ("NAME (my edits)", [PackSentences.myEditsName]); [data] is the item's data as the pack
 * holds that kind, which the keeper turns into the library's shape ([PackActions.libraryData]).
 */
public data class KeptVersion(val key: String, val kind: String, val name: String, val data: JsonElement)

/** What became of a [KeptVersion]. */
public sealed interface KeptOutcome {
    public val key: String

    /** Saved as the library record [libraryUuid]. */
    public data class Saved(override val key: String, val kind: String, val libraryUuid: String, val name: String) : KeptOutcome

    /** Nothing to save ([reason]: `empty_point_set`, a set with no points, which the library cannot hold). */
    public data class NothingToKeep(override val key: String, val reason: String) : KeptOutcome
}
