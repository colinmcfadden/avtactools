package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A new point set for a pack: the `item.create` that makes it, or why there is none. */
public sealed interface NewPointSet {
    public data class Made(val op: JsonObject) : NewPointSet

    /** `empty_point_set`: no points. The pack's server would take it, but a library copy of it could never be saved. */
    public data class Refused(val reason: String) : NewPointSet
}

/**
 * A set of local points as a mission pack item: the pure part of the web's `usePackPoints.js`, held to
 * `contracts/fixtures/packs/shared.json` (`points`) and `describe.json` (`points`, `newPointSetOp`). Its data is the list of points the
 * library saves; its colour and whether it is shown are each person's own and never in the pack. Points are not edited (on the web
 * either): what changes is a set's name, and what arrives is whatever anyone else did.
 */
public object PackPoints {
    public const val EMPTY_POINT_SET: String = "empty_point_set"

    /**
     * Points ready to go in a pack (`pointsForPack`): the same points in the same order, each with an id no other has. An id is kept if it
     * is text (empty too) or a finite number; anything else is `pt-<index>`. Ids are told apart with their type (7 and "7" are two), and
     * one already taken has `-<index>` added until it is free, which makes a number id text. A point that is null becomes one holding
     * only its id, as JavaScript's spread makes it (`{...null}`). No points, or points that are not a list, are none.
     */
    public fun pointsForPack(points: JsonElement?): JsonArray {
        val seen = HashSet<String>()
        return JsonArray(
            (points as? JsonArray).orEmpty().mapIndexed { index, point ->
                var id: JsonElement = Js.prop(point, "id")?.takeIf { Js.isId(it) } ?: JsonPrimitive("pt-$index")
                while (Js.idKey(id) in seen) id = JsonPrimitive("${Js.text(id)}-$index")
                seen.add(Js.idKey(id))
                val fields = Js.spread(point)                                           // `{...point, id}`
                fields["id"] = id
                JsonObject(fields)
            },
        )
    }

    /**
     * The `item.create` that puts [points] in the pack as a new set ([pointsForPack]'s list), its uuid `ps-` and [newId], named as
     * [PackSentences.newItemName] says. A set that comes to no points is refused, before an id is taken.
     */
    public fun newPointSetOp(name: String?, points: JsonElement?, actor: String?, newId: () -> String): NewPointSet {
        val list = pointsForPack(points)
        if (list.isEmpty()) return NewPointSet.Refused(EMPTY_POINT_SET)
        return NewPointSet.Made(PackSentences.newItemOp("pointset", PackSentences.packItemId("pointset", newId()), name, 0, list, actor))
    }

    /**
     * One sentence for the pack's history about a change to a point set (`describePointsChange`, held to describe.json's `points`
     * cases): added, else removed, else the one point changed or how many. Cut to 300 UTF-16 units, never through a character.
     */
    public fun describePointsChange(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String {
        val set = name?.takeIf { it.isNotEmpty() } ?: "the points"
        var refs = 0
        val unique = { (refs++).toString() }
        // `new Map((list ?? []).map((p) => [p.id, p]))`: no filter, so every point without an id is one, under undefined.
        fun byId(list: JsonElement?): LinkedHashMap<String, JsonElement> {
            val map = LinkedHashMap<String, JsonElement>()
            (list as? JsonArray).orEmpty().forEach { map[Js.mapKey(Js.prop(it, "id"), unique)] = it }
            return map
        }
        val was = byId(before)
        val now = byId(after)
        val added = now.keys.count { it !in was }
        val removed = was.keys.count { it !in now }
        val changed = now.keys.filter { it in was && !PackDiff.sameData(was[it], now[it]) }
        fun points(count: Int) = if (count == 1) "point" else "points"
        val what = when {
            added > 0 -> "added $added ${points(added)} to $set"
            removed > 0 -> "removed $removed ${points(removed)} from $set"
            changed.size == 1 -> {
                val pointName = Js.prop(now[changed[0]], "name")
                "changed ${if (Js.truthy(pointName)) Js.text(pointName) else "a point"} in $set"
            }
            changed.isNotEmpty() -> "changed ${changed.size} points in $set"
            else -> "edited $set"
        }
        return Js.cut("${actor?.takeIf { it.isNotEmpty() } ?: "Someone"} $what.", PackLz.MAX_SENTENCE)
    }
}
