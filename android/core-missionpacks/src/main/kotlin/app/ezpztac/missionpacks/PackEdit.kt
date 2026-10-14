package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A version of a pack item as an editor has it: its name and its document in the item's shape. */
public data class EditVersion(val name: String?, val doc: JsonElement?)

/** What one change is sent as: the operations, in order, and what the item is called once they are taken. */
public data class ComposedEdit(val ops: List<JsonObject>, val name: String)

/**
 * The sentence for a change to an item's content, for the history: `describe(before, after, name, actor)`, as
 * [PackLz.describeLzChange], [PackRoutes.describeRouteChange] and [PackPoints.describePointsChange] write it.
 */
public fun interface ChangeSentence {
    public fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String
}

/**
 * What one change an editor made to a pack item is sent as: the web's `packEdit.js` (`composeEdit`, usePackItemSync's flush
 * without the timing), held to every case of `contracts/fixtures/packs/edit.json`.
 */
public object PackEdit {
    /**
     * The operations for item [uuid] that carry the editor's change ([mine]) since the version it was last in step with ([base]), or
     * null when there is none.
     *
     *  - An `item.rename` first, when the editor's name, trimmed as JavaScript trims, is not blank and is not `base.name` (not the
     *    item's: a name the pack changed meanwhile is not a rename here).
     *  - Then, only when the content changed: the operations that bring the pack's data into today's shape (from [shared], the shared
     *    part of the item's data, to [current], today's shape of it; none for an item already in it), so every path of the change
     *    exists; then the change itself, from `base.doc` to `mine.doc`.
     *  - Every operation carries one summary: [describe]'s sentence for the change, with the new name or the item's, when the content
     *    changed; else the rename's.
     *
     * [shared] and [current] are asked only when the content changed. Throws [PackDiffException] when the content would have to be
     * replaced whole.
     */
    public fun composeEdit(
        uuid: String,
        base: EditVersion,
        mine: EditVersion,
        item: PackItemView,
        shared: () -> JsonElement?,
        current: () -> JsonElement?,
        describe: ChangeSentence,
        actor: String?,
    ): ComposedEdit? {
        val name = Js.trim(mine.name.orEmpty())
        val renamed = name.isNotEmpty() && name != base.name
        val changes = PackDiff.diffItem(uuid, base.doc, mine.doc)
        if (!renamed && changes.isEmpty()) return null
        val reshape = if (changes.isNotEmpty()) PackDiff.diffItem(uuid, shared(), current()) else emptyList()
        val rename = if (renamed) {
            listOf(JsonObject(linkedMapOf("type" to JsonPrimitive("item.rename"), "item" to JsonPrimitive(uuid), "name" to JsonPrimitive(name))))
        } else {
            emptyList()
        }
        val summary = if (changes.isNotEmpty()) {
            describe.describe(base.doc, mine.doc, name.ifEmpty { item.name }, actor)
        } else {
            PackSentences.renameSummary(actor, base.name.orEmpty(), name)
        }
        val ops = (rename + reshape + changes).map { op -> JsonObject(op + ("summary" to JsonPrimitive(summary))) }
        return ComposedEdit(ops, if (renamed) name else base.name.orEmpty())
    }
}
