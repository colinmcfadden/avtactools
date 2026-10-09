package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The data of a pack item would have to be replaced whole, which no operation does. */
public class PackDiffException(message: String) : IllegalArgumentException(message)

/**
 * What an editor's change to a pack item is sent as: the operations that turn the item's data as the pack has it into the
 * data as the editor has it, which [PackOps] then applies. The web's `packDiff.js`, held to every case of
 * `contracts/fixtures/packs/diff.json`: the same change becomes the same operations, in the same order. Small and
 * addressed by id, so two people changing different things never touch each other's work (docs/MISSION_PACKS.md §3):
 *
 *  - an object's changed fields go as one `patch` at that object, and a field that is itself an object, or a list of
 *    things with ids, is followed into rather than replaced;
 *  - in a list whose elements all have ids (helicopters, routes, a route's points, a set's points), an element that went
 *    is a `remove`, a new one an `insert` after its neighbour, a changed one a patch at that element, and one that moved
 *    a `remove` and an `insert`;
 *  - anything else that changed (a number, a boundary of [lat, lon] pairs) goes whole, as a field of its object's patch.
 *
 * A missing field and a null one are the same here, as they are to every reader of these documents: no operation deletes
 * a key, so a field that went is sent as null. The operations hold that null as [JsonNull] (and an insert at the front
 * says `"after": null`), so they must be written as the trees they are, never through a serializer that leaves nulls out.
 *
 * Not like the web, on purpose (diff.json marks these `webBug`): the web reads a field the object does not have through
 * JavaScript's prototype, so a field named `constructor`, `toString` and the like that is null on one side and missing on
 * the other is a difference to it, and one that went makes it fail; and it drops a changed field named `__proto__` from a
 * patch. Here only an object's own fields are read, and `__proto__` is data like any other key.
 */
public object PackDiff {
    private const val REPLACED_WHOLE = "A pack item's data can only be changed inside, never replaced whole."

    // Keys never followed into: a change under one goes whole in its object's patch. The web's, kept so both send the same.
    private val FORBIDDEN_KEYS = setOf("__proto__", "constructor", "prototype")

    /**
     * Whether [a] and [b] are equal as JSON, with a missing field and a null one the same. Kotlin's null is a field that
     * is not there; numbers compare by value (`1` is `1.0`), text is never a number, key order does not matter and list
     * order does.
     */
    public fun sameData(a: JsonElement?, b: JsonElement?): Boolean {
        if (a === b) return true
        val aNull = a == null || a is JsonNull
        val bNull = b == null || b is JsonNull
        if (aNull || bNull) return aNull && bNull
        if (a is JsonArray || b is JsonArray) {
            if (a !is JsonArray || b !is JsonArray || a.size != b.size) return false
            return a.indices.all { sameData(a[it], b[it]) }
        }
        if (a is JsonObject && b is JsonObject) {
            for ((key, value) in a) if (!sameData(value, b[key])) return false
            for ((key, value) in b) if (key !in a && !sameData(null, value)) return false
            return true
        }
        return Js.strictEquals(a, b)
    }

    /**
     * The operations (without `item` or `client_op_id`) that turn [before] into [after]. Applied in order to [before] they
     * give data that is [sameData] as [after]. Throws [PackDiffException] if the item's data would have to be replaced
     * whole: an object turned into something else, or a list with no ids.
     */
    public fun diffData(before: JsonElement?, after: JsonElement?): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        diffValue(emptyList(), before, after, out)
        return out
    }

    /** The same, as operations on pack item [item], ready to be sent. */
    public fun diffItem(item: String, before: JsonElement?, after: JsonElement?): List<JsonObject> =
        diffData(before, after).map { op -> JsonObject(op + ("item" to JsonPrimitive(item))) }

    /**
     * The positions in [values] of its longest run that only goes up: the elements of a list that kept their order. When
     * several runs are as long, the one the web's `longestRising` picks, because which elements are said to have moved is
     * in the operations: a lower-bound binary search over the run ends, then read back from the last.
     */
    internal fun longestRising(values: List<Int>): Set<Int> {
        val tails = ArrayList<Int>()
        val tailIndex = ArrayList<Int>()
        val previous = IntArray(values.size) { -1 }
        values.forEachIndexed { i, value ->
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tails[mid] < value) lo = mid + 1 else hi = mid
            }
            if (lo == tails.size) {
                tails.add(value)
                tailIndex.add(i)
            } else {
                tails[lo] = value
                tailIndex[lo] = i
            }
            previous[i] = if (lo > 0) tailIndex[lo - 1] else -1
        }
        val kept = LinkedHashSet<Int>()
        var i = if (tails.isEmpty()) -1 else tailIndex[tails.size - 1]
        while (i >= 0) {
            kept.add(i)
            i = previous[i]
        }
        return kept
    }

    /** Every element an object with an id, no id twice: a list whose elements can be addressed. An empty list is one. */
    private fun isIdList(value: JsonElement?): Boolean {
        if (value !is JsonArray) return false
        val seen = HashSet<String>()
        for (element in value) {
            if (element !is JsonObject || !Js.isId(element["id"])) return false
            if (!seen.add(Js.idKey(element.getValue("id")))) return false
        }
        return true
    }

    private fun idOf(element: JsonElement): JsonElement = (element as JsonObject).getValue("id")

    private fun idSegment(element: JsonElement): JsonObject = JsonObject(mapOf("id" to idOf(element)))

    private fun operation(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))

    // Only ever reached with two objects or two lists with ids, or at the top: anything else is sent whole inside its
    // object's patch (diffObject).
    private fun diffValue(path: List<JsonElement>, before: JsonElement?, after: JsonElement?, out: MutableList<JsonObject>) {
        if (sameData(before, after)) return
        when {
            before is JsonObject && after is JsonObject -> diffObject(path, before, after, out)
            isIdList(before) && isIdList(after) -> diffList(path, before as JsonArray, after as JsonArray, out)
            else -> throw PackDiffException(REPLACED_WHOLE)
        }
    }

    private fun diffObject(path: List<JsonElement>, before: JsonObject, after: JsonObject, out: MutableList<JsonObject>) {
        val fields = LinkedHashMap<String, JsonElement>()
        val deeper = ArrayList<String>()
        // The new version's keys, then those only the old one had, each in the order JavaScript lists them: the order the
        // operations come out in is part of the contract.
        val keys = Js.orderedKeys(after.keys) + Js.orderedKeys(before.keys).filter { it !in after }
        for (key in keys) {
            val was = before[key]
            val now = after[key]
            if (sameData(was, now)) continue
            val followable = key !in FORBIDDEN_KEYS && ((was is JsonObject && now is JsonObject) || (isIdList(was) && isIdList(now)))
            // Sent as it is: a tree is never changed, so the editor's own can go (the web copies it). A field that went is null.
            if (followable) deeper.add(key) else fields[key] = now ?: JsonNull
        }
        if (fields.isNotEmpty()) {
            // The patch's fields as the web's object holds them: array indexes first.
            val value = JsonObject(Js.orderedKeys(fields.keys).associateWithTo(LinkedHashMap<String, JsonElement>()) { fields.getValue(it) })
            out.add(operation("type" to JsonPrimitive("patch"), "path" to JsonArray(path), "value" to value))
        }
        deeper.forEach { key -> diffValue(path + JsonPrimitive(key), before[key], after[key], out) }
    }

    private fun diffList(path: List<JsonElement>, before: JsonArray, after: JsonArray, out: MutableList<JsonObject>) {
        val was = HashMap<String, IndexedValue<JsonElement>>()
        before.forEachIndexed { index, element -> was[Js.idKey(idOf(element))] = IndexedValue(index, element) }
        val now = after.mapTo(HashSet()) { Js.idKey(idOf(it)) }

        // Those still there whose order did not change stay put; the rest of those still there moved.
        val stayed = after.filter { Js.idKey(idOf(it)) in was }
        val inOrder = longestRising(stayed.map { was.getValue(Js.idKey(idOf(it))).index })
        val moved = stayed.filterIndexed { i, _ -> i !in inOrder }.mapTo(HashSet()) { Js.idKey(idOf(it)) }

        before.forEach { element ->
            val key = Js.idKey(idOf(element))
            if (key !in now || key in moved) {
                out.add(operation("type" to JsonPrimitive("remove"), "path" to JsonArray(path + idSegment(element))))
            }
        }
        after.forEachIndexed { index, element ->
            val key = Js.idKey(idOf(element))
            if (key !in was || key in moved) {
                val neighbour = if (index == 0) JsonNull else idOf(after[index - 1])
                out.add(operation("type" to JsonPrimitive("insert"), "path" to JsonArray(path), "after" to neighbour, "value" to element))
            } else {
                diffValue(path + idSegment(element), was.getValue(key).value, element, out)
            }
        }
    }
}
