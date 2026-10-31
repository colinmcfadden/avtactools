package app.ezpztac.missionpacks

import app.ezpztac.network.ApiUser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The fixed sentences a client sends for a mission pack's history, and how a new item and a kept version of one are named: the
 * web's `packSentences.js`, held to `contracts/fixtures/packs/describe.json` (`summaries`) and `shared.json` (`myEditsName`). Every
 * client writes the same words, so a pack's history reads the same whoever made each change.
 *
 * An actor is who did it, as [actorName] names them; null or empty is "Someone".
 */
public object PackSentences {
    private val PREFIX = mapOf("lz" to "lz", "route" to "rt", "pointset" to "ps")
    private val KIND_WORDS = mapOf("lz" to "LZ/PZ", "route" to "route set", "pointset" to "point set")
    private const val SOMEONE = "Someone"

    /** The longest name a kept version is saved under, in UTF-16 units as the web counts them. */
    public const val MAX_KEPT_NAME: Int = 100

    private fun who(actor: String?): String = actor?.takeIf { it.isNotEmpty() } ?: SOMEONE

    /** Who a person is in the history: their name, else their sign-in address, else "Someone". */
    public fun actorName(name: String?, email: String?): String =
        name?.takeIf { it.isNotEmpty() } ?: email?.takeIf { it.isNotEmpty() } ?: SOMEONE

    /** Who the signed-in person is in the history ([actorName]); "Someone" when nobody is. */
    public fun actorName(user: ApiUser?): String = actorName(user?.name, user?.email)

    /** A new item's uuid in a pack: its kind's prefix (`lz-`, `rt-`, `ps-`) and an id the client chose. */
    public fun packItemId(kind: String, id: String): String {
        val prefix = requireNotNull(PREFIX[kind]) { "Not a pack item's kind: $kind" }
        return "$prefix-$id"
    }

    /**
     * What a new item is called: [name] trimmed as JavaScript trims (never upper-cased), or when that is blank its kind's default:
     * "LZ/PZ n" or "ROUTES n", n being one more than the [count] of items of that kind the pack has, and "LOCAL POINTS".
     */
    public fun newItemName(kind: String, name: String?, count: Int): String {
        val trimmed = Js.trim(name.orEmpty())
        if (trimmed.isNotEmpty()) return trimmed
        return when (kind) {
            "lz" -> "LZ/PZ ${count + 1}"
            "route" -> "ROUTES ${count + 1}"
            "pointset" -> "LOCAL POINTS"
            else -> throw IllegalArgumentException("Not a pack item's kind: $kind")
        }
    }

    /** The history's sentence for an item made in the pack; a point set's says how many points it has ([pointCount]). */
    public fun createSummary(actor: String?, kind: String, name: String, pointCount: Int? = null): String = when (kind) {
        "lz" -> "${who(actor)} added the LZ/PZ \"$name\"."
        "route" -> "${who(actor)} added the route set \"$name\"."
        else -> {
            val count = requireNotNull(pointCount) { "A point set's sentence says how many points it has" }
            "${who(actor)} added the point set \"$name\" (${grouped(count)} point${if (count == 1) "" else "s"})."
        }
    }

    /**
     * The `item.create` that makes item [item] of [kind] with [data], named as [newItemName] says ([count]: the items of that kind the
     * pack has), with [createSummary]'s sentence. A point set's [data] is its list of points.
     */
    public fun newItemOp(kind: String, item: String, name: String?, count: Int, data: JsonElement, actor: String?): JsonObject {
        val label = newItemName(kind, name, count)
        val points = if (kind == "pointset") (data as? JsonArray)?.size ?: 0 else null
        return JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("item.create"),
                "item" to JsonPrimitive(item),
                "kind" to JsonPrimitive(kind),
                "name" to JsonPrimitive(label),
                "data" to data,
                "summary" to JsonPrimitive(createSummary(actor, kind, label, points)),
            ),
        )
    }

    /** The history's sentence for an item renamed from [from] to [to]. Never cut. */
    public fun renameSummary(actor: String?, from: String, to: String): String = "${who(actor)} renamed \"$from\" to \"$to\"."

    /** The history's sentence for an item removed. Never cut. */
    public fun deleteSummary(actor: String?, name: String): String = "${who(actor)} removed \"$name\"."

    /** The history's sentence for a library record copied in; one with no name is called by its kind ("LZ/PZ", "ROUTE SET", "POINT SET"). */
    public fun copySummary(actor: String?, kind: String, recordName: String?): String {
        val named = recordName?.takeIf { it.isNotEmpty() } ?: requireNotNull(KIND_WORDS[kind]) { "Not a pack item's kind: $kind" }.uppercase()
        return "${who(actor)} added \"$named\" (a copy from their library)."
    }

    /** The history's sentence for an item updated from the original in the library of whoever copied it in. */
    public fun updateFromOriginalSummary(actor: String?, name: String): String = "${who(actor)} updated \"$name\" from their library."

    /** The `item.delete` that removes item [uuid], with its sentence. */
    public fun deleteItemOp(uuid: String, name: String, actor: String?): JsonObject = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("item.delete"),
            "item" to JsonPrimitive(uuid),
            "summary" to JsonPrimitive(deleteSummary(actor, name)),
        ),
    )

    /**
     * The name a person's own version of an item is saved to their library under when the pack would not take their edits:
     * "NAME (my edits)", cut to [MAX_KEPT_NAME] UTF-16 units. **Not like the web**, which can cut an emoji in half: the server cannot
     * store the half it leaves (so the save fails), and the whole character goes instead ([Js.cut]).
     */
    public fun myEditsName(name: String): String = Js.cut("$name (my edits)", MAX_KEPT_NAME)

    // A count as `toLocaleString("en-US")` writes it, a comma between every three digits, without the device's locale (whose digits
    // may not be ASCII).
    private fun grouped(count: Int): String {
        val digits = kotlin.math.abs(count.toLong()).toString()
        val groups = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (count < 0) "-$groups" else groups
    }
}
