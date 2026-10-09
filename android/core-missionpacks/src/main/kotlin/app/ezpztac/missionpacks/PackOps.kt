package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One item of a mission pack as the operations see it: [data] is the JSON the library saves for its kind (an LZ's
 * `lz_data`, a route set's `route_data`, a point set's list of points), and JSON null once the item is deleted.
 */
public data class PackItemState(val kind: String, val name: String, val data: JsonElement, val deleted: Boolean)

/** How an operation went: applied, skipped (well formed, but what it edits is gone or in the way) or invalid (malformed). */
public enum class OpStatus { APPLIED, SKIPPED, INVALID }

/** The items after an operation, and how it went. [reason] says why it was skipped or invalid, in the web's words. */
public data class OpResult(val items: Map<String, PackItemState>, val status: OpStatus, val reason: String?)

/**
 * What one edit does to a mission pack's items: the web's `packOps.js`, which the server (`backend/pack_ops.py`) follows
 * too, held to every case of `contracts/fixtures/packs/ops.json`. Edits are addressed by stable ids ("move helicopter h-3
 * on diagram d-1"), never by a position in a list, so two people editing different things never collide and two editing
 * the same field leave the later one in the server's order. See docs/MISSION_PACKS.md.
 *
 * Nothing passed in is changed. The web copies an item's data whole before an edit; JSON trees are immutable here, so
 * only the objects and lists on the edited path are new and everything else keeps its instance, which is what lets a
 * caller tell cheaply what an edit touched.
 */
public object PackOps {
    public val ITEM_KINDS: List<String> = listOf("lz", "route", "pointset")

    /** What a client may send. `item.replace` is the server's own (update from original). */
    public val CLIENT_OP_TYPES: List<String> = listOf("item.create", "item.delete", "item.rename", "set", "patch", "upsert", "insert", "remove")
    public val SERVER_OP_TYPES: List<String> = listOf("item.replace")
    public const val MAX_NAME_LENGTH: Int = 100

    /** An item's uuid as the server allows one. `\z`, not `$`: Java's `$` also matches before a final line break. */
    public val ITEM_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}\\z")

    // A key that would reach an object's prototype in JavaScript. Refused everywhere, so every client agrees on what is malformed.
    private val FORBIDDEN_KEYS = listOf("__proto__", "constructor", "prototype")
    private val NOT_BLANK = Regex("[^ \t\n\r]")

    private const val TARGET_MISSING = "target_missing"
    private const val NOT_AN_OBJECT = "not_an_object"
    private const val NOT_AN_ARRAY = "not_an_array"

    /** Why [op] is malformed, or null. A malformed operation is a client's bug: the server refuses its whole batch. */
    public fun validate(op: JsonElement?): String? {
        if (op !is JsonObject) return "bad_op"
        val type = op.text("type")
        if (type !in CLIENT_OP_TYPES && type !in SERVER_OP_TYPES) return "unknown_type"
        val item = op.text("item")
        if (item == null || !ITEM_ID.matches(item)) return "bad_item"
        when (type) {
            "item.create" -> {
                val kind = op.text("kind")
                if (kind !in ITEM_KINDS) return "bad_kind"
                if (!isName(op["name"])) return "bad_name"
                val data = op["data"]
                if (if (kind == "pointset") data !is JsonArray else data !is JsonObject) return "bad_data"
                return null
            }
            "item.rename" -> return if (isName(op["name"])) null else "bad_name"
            "item.delete" -> return null
            "item.replace" -> return if (op["data"] is JsonObject || op["data"] is JsonArray) null else "bad_data"
        }
        val path = op["path"]
        if (path !is JsonArray || !path.all { isKeySegment(it) || isIdSegment(it) }) return "bad_path"
        val last = path.lastOrNull()
        val value = op["value"]
        return when (type) {
            "set" -> when {
                path.isEmpty() -> "bad_path"
                "value" !in op -> "bad_value"
                // Setting an element whole keeps its identity.
                isIdSegment(last) && !(value is JsonObject && "id" in value && Js.strictEquals(value["id"], idOf(last))) -> "bad_value"
                else -> null
            }
            "patch" -> when {
                value !is JsonObject -> "bad_value"
                isIdSegment(last) && "id" in value && !Js.strictEquals(value["id"], idOf(last)) -> "bad_value"
                else -> null
            }
            "upsert" -> if (isElement(value)) null else "bad_value"
            "insert" -> {
                val after = op["after"]
                when {
                    "after" !in op || !(after is JsonNull || Js.isId(after)) -> "bad_after"
                    isElement(value) -> null
                    else -> "bad_value"
                }
            }
            "remove" -> if (isIdSegment(last)) null else "bad_path"
            else -> "unknown_type"
        }
    }

    /**
     * Applies one operation. A skipped or invalid one gives back [items] itself; an applied one gives a new map in which
     * only the item it edited is new (in its old place, or last if it is new).
     */
    public fun apply(items: Map<String, PackItemState>, op: JsonElement): OpResult {
        val invalid = validate(op)
        if (invalid != null) return OpResult(items, OpStatus.INVALID, invalid)
        // validate has made sure of the shape from here on.
        val operation = op as JsonObject
        val type = operation.text("type")
        val uuid = operation.text("item").orEmpty()
        fun skipped(reason: String) = OpResult(items, OpStatus.SKIPPED, reason)
        fun applied(item: PackItemState): OpResult {
            val next = LinkedHashMap(items)
            next[uuid] = item
            return OpResult(next, OpStatus.APPLIED, null)
        }

        val current = items[uuid]
        if (type == "item.create") {
            // A deleted item's uuid is never reused.
            if (current != null) return skipped("item_exists")
            return applied(PackItemState(operation.text("kind").orEmpty(), operation.text("name").orEmpty(), operation.getValue("data"), deleted = false))
        }
        if (current == null || current.deleted) return skipped("item_missing")

        return when (type) {
            "item.rename" -> applied(current.copy(name = operation.text("name").orEmpty()))
            // Deleted means gone: the content is not kept.
            "item.delete" -> applied(current.copy(name = "", data = JsonNull, deleted = true))
            "item.replace" -> {
                val data = operation.getValue("data")
                when {
                    current.kind == "pointset" && data !is JsonArray -> skipped(NOT_AN_ARRAY)
                    current.kind != "pointset" && data !is JsonObject -> skipped(NOT_AN_OBJECT)
                    else -> applied(current.copy(data = data))
                }
            }
            else -> when (val edit = edit(current.data, type, operation)) {
                is Edit.Done -> applied(current.copy(data = edit.value))
                is Edit.Failed -> skipped(edit.reason)
            }
        }
    }

    // -- Validation ---------------------------------------------------------------------------------------------------

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isKeySegment(segment: JsonElement): Boolean = Js.isString(segment) && (segment as JsonPrimitive).content !in FORBIDDEN_KEYS

    private fun isIdSegment(segment: JsonElement?): Boolean = segment is JsonObject && segment.size == 1 && "id" in segment && Js.isId(segment["id"])

    private fun idOf(segment: JsonElement?): JsonElement? = (segment as? JsonObject)?.get("id")

    private fun isElement(value: JsonElement?): Boolean = value is JsonObject && "id" in value && Js.isId(value["id"])

    // Not blank, and at most 100 characters counted as code points (as Python and the web's Array.from count them).
    private fun isName(value: JsonElement?): Boolean {
        if (!Js.isString(value)) return false
        val name = (value as JsonPrimitive).content
        return NOT_BLANK.containsMatchIn(name) && name.codePointCount(0, name.length) <= MAX_NAME_LENGTH
    }

    // -- Edits --------------------------------------------------------------------------------------------------------

    private sealed interface Segment {
        class Key(val name: String) : Segment
        class Id(val id: JsonElement) : Segment
    }

    // An edit's outcome: the new tree, or why it was skipped.
    private sealed interface Edit {
        class Done(val value: JsonElement) : Edit
        class Failed(val reason: String) : Edit
    }

    private inline fun Edit.rebuilt(parent: (JsonElement) -> JsonElement): Edit = when (this) {
        is Edit.Done -> Edit.Done(parent(value))
        is Edit.Failed -> this
    }

    private fun edit(data: JsonElement, type: String?, op: JsonObject): Edit {
        val path = (op.getValue("path") as JsonArray).map { segment ->
            if (segment is JsonObject) Segment.Id(segment.getValue("id")) else Segment.Key((segment as JsonPrimitive).content)
        }
        val value = op["value"] ?: JsonNull
        return when (type) {
            "set" -> set(data, path, 0, value)
            "patch" -> at(data, path, 0) { node -> if (node is JsonObject) Edit.Done(node.merged(value as JsonObject)) else Edit.Failed(NOT_AN_OBJECT) }
            "upsert" -> upsert(data, path, value as JsonObject)
            "insert" -> insert(data, path, op.getValue("after"), value as JsonObject)
            "remove" -> remove(data, path)
            else -> Edit.Failed("unknown_type")
        }
    }

    private fun JsonArray.indexOfId(id: JsonElement): Int = indexOfFirst { it is JsonObject && "id" in it && Js.strictEquals(it["id"], id) }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject {
        val fields = LinkedHashMap(this)
        fields[key] = value                                                              // a key it has keeps its place
        return JsonObject(fields)
    }

    private fun JsonArray.with(index: Int, value: JsonElement): JsonArray {
        val elements = toMutableList()
        elements[index] = value
        return JsonArray(elements)
    }

    // The fields of [patch] laid over these: a key already here keeps its place, a new one goes last, as a spread does.
    private fun JsonObject.merged(patch: JsonObject): JsonObject {
        val fields = LinkedHashMap(this)
        fields.putAll(patch)
        return JsonObject(fields)
    }

    // Follows [path] from [depth] without making anything, edits what it ends at, and rebuilds the way back. A key that is
    // missing or null is "target_missing", as is an id no element has.
    private fun at(node: JsonElement, path: List<Segment>, depth: Int, edit: (JsonElement) -> Edit): Edit {
        if (depth == path.size) return edit(node)
        return when (val segment = path[depth]) {
            is Segment.Key -> {
                if (node !is JsonObject) return Edit.Failed(NOT_AN_OBJECT)
                val child = node[segment.name]
                if (child == null || child is JsonNull) return Edit.Failed(TARGET_MISSING)
                at(child, path, depth + 1, edit).rebuilt { node.with(segment.name, it) }
            }
            is Segment.Id -> {
                if (node !is JsonArray) return Edit.Failed(NOT_AN_ARRAY)
                val index = node.indexOfId(segment.id)
                if (index < 0) return Edit.Failed(TARGET_MISSING)
                at(node[index], path, depth + 1, edit).rebuilt { node.with(index, it) }
            }
        }
    }

    // Sets a field. Objects missing (or null) on the way are made, unless an id has to be found inside one, which can never succeed.
    private fun set(node: JsonElement, path: List<Segment>, depth: Int, value: JsonElement): Edit {
        val last = depth == path.size - 1
        return when (val segment = path[depth]) {
            is Segment.Key -> {
                if (node !is JsonObject) return Edit.Failed(NOT_AN_OBJECT)
                if (last) return Edit.Done(node.with(segment.name, value))
                val child = node[segment.name]
                if (child != null && child !is JsonNull) return set(child, path, depth + 1, value).rebuilt { node.with(segment.name, it) }
                val rest = path.subList(depth + 1, path.size)
                if (rest.any { it !is Segment.Key }) return Edit.Failed(TARGET_MISSING)
                val made = rest.foldRight(value) { key, inner -> JsonObject(mapOf((key as Segment.Key).name to inner)) }
                Edit.Done(node.with(segment.name, made))
            }
            is Segment.Id -> {
                if (node !is JsonArray) return Edit.Failed(NOT_AN_ARRAY)
                val index = node.indexOfId(segment.id)
                if (index < 0) return Edit.Failed(TARGET_MISSING)
                if (last) Edit.Done(node.with(index, value)) else set(node[index], path, depth + 1, value).rebuilt { node.with(index, it) }
            }
        }
    }

    // Edits the list [path] names, which is made, empty, if its last key is missing or null.
    private fun inList(root: JsonElement, path: List<Segment>, change: (JsonArray) -> Edit): Edit {
        if (path.isEmpty()) return if (root is JsonArray) change(root) else Edit.Failed(NOT_AN_ARRAY)
        return at(root, path.subList(0, path.size - 1), 0) { parent ->
            when (val last = path.last()) {
                is Segment.Key -> if (parent !is JsonObject) {
                    Edit.Failed(NOT_AN_OBJECT)
                } else {
                    when (val list = parent[last.name]) {
                        null, JsonNull -> change(JsonArray(emptyList())).rebuilt { parent.with(last.name, it) }
                        is JsonArray -> change(list).rebuilt { parent.with(last.name, it) }
                        else -> Edit.Failed(NOT_AN_ARRAY)
                    }
                }
                // Only objects are found by id, so an element is never a list: this only says why not.
                is Segment.Id -> when {
                    parent !is JsonArray -> Edit.Failed(NOT_AN_ARRAY)
                    parent.indexOfId(last.id) < 0 -> Edit.Failed(TARGET_MISSING)
                    else -> Edit.Failed(NOT_AN_ARRAY)
                }
            }
        }
    }

    private fun upsert(root: JsonElement, path: List<Segment>, value: JsonObject): Edit = inList(root, path) { list ->
        val index = list.indexOfId(value.getValue("id"))
        Edit.Done(if (index < 0) JsonArray(list + value) else list.with(index, (list[index] as JsonObject).merged(value)))
    }

    // After the element with id `after`; first when `after` is null; last when that element is gone.
    private fun insert(root: JsonElement, path: List<Segment>, after: JsonElement, value: JsonObject): Edit = inList(root, path) { list ->
        if (list.indexOfId(value.getValue("id")) >= 0) return@inList Edit.Failed("element_exists")
        val elements = list.toMutableList()
        val index = if (after is JsonNull) -1 else list.indexOfId(after)
        when {
            after is JsonNull -> elements.add(0, value)
            index < 0 -> elements.add(value)
            else -> elements.add(index + 1, value)
        }
        Edit.Done(JsonArray(elements))
    }

    private fun remove(root: JsonElement, path: List<Segment>): Edit {
        val last = path.last() as Segment.Id
        return at(root, path.subList(0, path.size - 1), 0) { node ->
            if (node !is JsonArray) return@at Edit.Failed(NOT_AN_ARRAY)
            val index = node.indexOfId(last.id)
            if (index < 0) Edit.Failed(TARGET_MISSING) else Edit.Done(JsonArray(node.filterIndexed { i, _ -> i != index }))
        }
    }
}
