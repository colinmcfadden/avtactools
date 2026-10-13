package app.ezpztac.missionpacks

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.DiagramView
import app.ezpztac.model.RouteSet
import app.ezpztac.model.RouteSets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What a kind of pack item is to an editor here: how an item becomes the document the editor works on ([fromItem]), and how that
 * document is the item's data again ([docOf]), keeping out what is each person's own ([ownOf]: kept on the device, never in the pack).
 * The web has one hook per kind (usePackLz, usePackRoutes); these are their pure halves.
 *
 * A document's shape is the item's data as the editor gives it back. An item's [currentShape] is the shape of the document made from
 * it, so an editor's document and the item's shape can never disagree: an item opened and not changed sends nothing.
 */
public interface PackKind<D : Any> {
    /** The item kind: `lz` or `route`. */
    public val kind: String

    /** The shared part of an item's raw data (the web's `shared`), which [PackEdit]'s reshape starts from. */
    public fun shared(data: JsonElement?): JsonElement

    /** The document an editor works on for [item], under the editor's id [localId], with this person's own fields [own] (or the defaults). */
    public fun fromItem(localId: String, item: PackItemView, own: JsonObject?): D

    /**
     * [document] as its item's data: its shape. [carryFrom] is the item's data as the pack had it when the editor was last in step with
     * it, which keeps what this version cannot hold.
     */
    public fun docOf(document: D, carryFrom: JsonElement?): JsonElement

    /** Today's shape of [item]: what [PackEdit]'s reshape brings the pack's data to before an editor's first change to it. */
    public fun currentShape(localId: String, item: PackItemView): JsonElement = docOf(fromItem(localId, item, null), item.data)

    public fun nameOf(document: D): String

    /** This person's own fields of [document]: kept on the device beside the pack, never sent. */
    public fun ownOf(document: D): JsonObject

    /** [document] with the content [shared] (a shape) and [name], keeping its own fields and its identity. */
    public fun withShared(document: D, shared: JsonElement, name: String): D

    /** The history's sentence for a change from [before] to [after], two of this kind's shapes. */
    public fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String

    /**
     * [ops], a change that makes a shape of this kind into [after] (what this person sends, or someone else's change laid over this person's
     * versions), with each value that is one to a person kept whole: an operation that changes some of its fields carries the rest as [after]
     * has them, so two people's changes never leave it half one's and half the other's. The operations as they are by default; a route
     * point's place is one ([RoutePackKind]).
     */
    public fun keepWhole(ops: List<JsonObject>, after: JsonElement?): List<JsonObject> = ops
}

/**
 * An LZ/PZ item as a [Diagram] (usePackLz). Each person's own fields are the view (base map, LZ outline, slope map); the times and
 * whether it is dirty are the document's and are never in the pack either. A diagram holds every field the web's shape has, so its shape
 * is [PackLz.lzItemData], with the fields a newer version wrote put back from the pack's data ([PackLz.carryUnknown]).
 */
public class LzPackKind(private val env: DiagramNormalizer.Environment = DiagramNormalizer.Environment()) : PackKind<Diagram> {
    override val kind: String = "lz"

    private val json = Json { encodeDefaults = true }

    override fun shared(data: JsonElement?): JsonElement = PackLz.sharedLzData(data)

    override fun fromItem(localId: String, item: PackItemView, own: JsonObject?): Diagram {
        val diagram = PackLz.lzDiagramFromItem(localId, item, env)
        val view = own?.get("view") ?: return diagram
        return diagram.copy(view = viewOf(diagram, view))
    }

    override fun docOf(document: Diagram, carryFrom: JsonElement?): JsonElement = PackLz.carryUnknown(carryFrom, PackLz.lzItemData(document))

    override fun nameOf(document: Diagram): String = document.name

    override fun ownOf(document: Diagram): JsonObject =
        JsonObject(mapOf("view" to json.encodeToJsonElement(DiagramView.serializer(), document.view)))

    override fun withShared(document: Diagram, shared: JsonElement, name: String): Diagram {
        val input = Js.spread(shared)
        input["id"] = JsonPrimitive(document.id)
        input["name"] = JsonPrimitive(name)
        val options = DiagramNormalizer.Options(
            savedId = document.savedId,
            dirty = document.dirty,
            createdAt = document.createdAt,
            updatedAt = document.updatedAt,
        )
        // A shape has no view of its own, so the document's is kept: what someone else changed is never how this person sees it.
        return DiagramNormalizer.normalize(JsonObject(input), options, env).copy(view = document.view)
    }

    override fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String =
        PackLz.describeLzChange(before, after, name, actor)

    // The view kept on the device, read as the web reads one (a value it does not understand is the default), without the clock.
    private fun viewOf(diagram: Diagram, view: JsonElement): DiagramView = DiagramNormalizer.normalize(
        JsonObject(mapOf("view" to view)),
        DiagramNormalizer.Options(id = diagram.id, createdAt = diagram.createdAt, updatedAt = diagram.updatedAt),
        env,
    ).view
}

/**
 * A route set item as a [RouteSet] (usePackRoutes). Each person's own field is which routes they have hidden, by id.
 *
 * A [RouteSet]'s routes are typed, and the types cannot hold everything a pack's set can (a plan whose altitude is null, a colour that
 * is a number, a route with no id): read and written again they would change, and that change would be sent for everyone. So the
 * shape is worked out on the JSON ([PackRoutes.routeShape]), and a document's shape is the shape of the data it was made from with the
 * editor's own changes laid over it: the difference between that data read as types and the document, as the operations [PackDiff]
 * makes, applied to the JSON. What the editor did not change goes back exactly as it came. A route the types cannot read stays in the
 * set, where it stands, and the editor leaves it alone; unless the editor's document still has a version of it from before the pack's took
 * that form, which is then measured as the pack has it, so a change to it goes as operations at it.
 *
 * A route is never written beside another with its id. Once it was: the types could not read a route with a null in its ground
 * elevations (a point moved off its fetched ground sends the ground that went as null), so the editor's own copy of it was taken for a new
 * route, and the set went whole with the route in it twice, each person's later moves landing on a different copy (2026-10-13).
 *
 * Two things are written from the types, because nothing else can carry the change:
 *  - where the types filled in what the data lacks (a plan's altitude, airspeed or per-point values that are null are read as the
 *    defaults), a change inside it is sent as the editor's whole value of the field the data lacks, at the nearest object it has;
 *  - where the routes are not a list the operations can address (two with one id, in the pack's set or the editor's), they go whole: each
 *    route the editor did not change as it came, each left alone in its place, and the rest from the types.
 */
public object RoutePackKind : PackKind<RouteSet> {
    override val kind: String = "route"

    private const val OWN_HIDDEN = "hidden"

    override fun shared(data: JsonElement?): JsonElement = PackRoutes.sharedRouteData(data)

    override fun fromItem(localId: String, item: PackItemView, own: JsonObject?): RouteSet {
        val hidden = (own?.get(OWN_HIDDEN) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }.toSet()
        val set = RouteSets.parse(localId, null, item.name, PackRoutes.routeShape(item.data))
        return withHidden(set, hidden)
    }

    override fun docOf(document: RouteSet, carryFrom: JsonElement?): JsonElement {
        val shape = PackRoutes.routeShape(carryFrom)
        val read = RouteSets.parse(document.id, null, document.name, shape)
        val after = typed(document)
        val measured = measuredAgainst(shape, read, after)
        val before = JsonObject(mapOf("routes" to JsonArray(measured.map { it.version })))
        if (PackDiff.sameData(before, after)) return shape
        // Two routes with one id: an operation addressed by it reaches the first, whichever of them the editor changed, so they go whole.
        if (!addressable(shape)) return wholeRoutes(shape, measured, after)
        var data: JsonElement = shape
        for (op in PackDiff.diffData(before, after)) {
            // An operation on the set itself is on its routes, which are then not a list it can address: they go whole. Any other is
            // never dropped: one that does not apply to the JSON is made coarser until it does.
            val next = if ((op.getValue("path") as JsonArray).isEmpty()) null else applied(data, op) ?: coarsened(data, op, after)?.let { applied(data, it) }
            data = next ?: return wholeRoutes(shape, measured, after)
        }
        return data
    }

    override fun nameOf(document: RouteSet): String = document.name

    override fun ownOf(document: RouteSet): JsonObject =
        JsonObject(mapOf(OWN_HIDDEN to JsonArray(document.routes.filterNot { it.visible }.map { JsonPrimitive(it.id) })))

    override fun withShared(document: RouteSet, shared: JsonElement, name: String): RouteSet {
        val hidden = document.routes.filterNot { it.visible }.mapTo(HashSet()) { it.id }
        val set = RouteSets.parse(document.id, document.savedId, name, PackRoutes.routeShape(shared))
        return withHidden(set, hidden)
    }

    override fun describe(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String =
        PackRoutes.describeRouteChange(before, after, name, actor)

    /**
     * A point's place is one value: a patch that moves a point of a route in one direction only (each arrow of the nudge pad changes one coordinate)
     * carries the other coordinate too, as [after] has it. Each field of a patch stands on its own in the pack, the later on each, so a move north here and
     * a move to a typed grid there would otherwise leave the point at the grid's longitude and this latitude: where nobody put it. The web moves a point by
     * dragging it, which changes both.
     */
    override fun keepWhole(ops: List<JsonObject>, after: JsonElement?): List<JsonObject> = ops.map { op ->
        val value = op["value"] as? JsonObject
        val path = op["path"] as? JsonArray
        if ((op["type"] as? JsonPrimitive)?.content != "patch" || value == null || path == null || ("lat" in value) == ("lon" in value) || !isPointPath(path)) {
            return@map op
        }
        val point = path.fold<JsonElement, JsonElement?>(after) { at, segment -> step(at, segment) } as? JsonObject ?: return@map op
        val other = if ("lat" in value) "lon" else "lat"
        val place = point[other] ?: return@map op
        JsonObject(LinkedHashMap(op).apply { put("value", JsonObject(LinkedHashMap(value).apply { put(other, place) })) })
    }

    // `["routes", {id}, "points", {id}]`: a point of a route.
    private fun isPointPath(path: JsonArray): Boolean =
        path.size == 4 && Js.strictEquals(path[0], ROUTES) && path[1] is JsonObject && Js.strictEquals(path[2], POINTS) && path[3] is JsonObject

    private val ROUTES = JsonPrimitive("routes")
    private val POINTS = JsonPrimitive("points")

    private const val SET = "set"

    // [data] with [op] applied, or null when it does not apply.
    private fun applied(data: JsonElement, op: JsonObject): JsonElement? {
        val result = PackOps.apply(mapOf(SET to PackItemState(kind, "", data, deleted = false)), JsonObject(op + ("item" to JsonPrimitive(SET))))
        return if (result.status == OpStatus.APPLIED) result.items.getValue(SET).data else null
    }

    /**
     * [op], which does not apply to [data], as a patch that does: at the deepest object on its way that [data] has, setting the next field
     * to the editor's whole value of it ([after]). That field is one the data has as null or not at all, or a list without the element
     * the change is in. Null when that object is the set itself.
     */
    private fun coarsened(data: JsonElement, op: JsonObject, after: JsonElement): JsonObject? {
        val path = (op.getValue("path") as JsonArray).let { if ((op["type"] as? JsonPrimitive)?.content == "remove") it.dropLast(1) else it.toList() }
        var node: JsonElement? = data
        var deepest = -1
        for ((depth, segment) in path.withIndex()) {
            if (node is JsonObject && segment is JsonPrimitive) deepest = depth
            node = step(node, segment) ?: break
        }
        if (deepest < 1) return null
        val value = path.subList(0, deepest + 1).fold<JsonElement, JsonElement?>(after) { at, segment -> step(at, segment) } ?: return null
        val field = JsonObject(mapOf((path[deepest] as JsonPrimitive).content to value))
        return JsonObject(linkedMapOf("type" to JsonPrimitive("patch"), "path" to JsonArray(path.subList(0, deepest)), "value" to field))
    }

    // One step along an operation's path, as PackOps takes it: an object's field (none when it is null), or a list's element by id.
    private fun step(node: JsonElement?, segment: JsonElement): JsonElement? = when (segment) {
        is JsonObject -> (node as? JsonArray)?.firstOrNull { it is JsonObject && "id" in it && Js.strictEquals(it["id"], segment["id"]) }
        else -> (node as? JsonObject)?.get((segment as JsonPrimitive).content)?.takeUnless { it is JsonNull }
    }

    /** A route of the pack's set that the editor's routes are measured against: [json] as the pack has it, and [version], what it is compared as. */
    private class Measured(val json: JsonElement, val version: JsonElement)

    /**
     * The routes of the pack's set ([shape]) that the editor's ([after]) are measured against, in their order: each the types read, as they write it; and
     * one they cannot read only when the editor has a route with its id and the pack has no route with it that they can (the editor's document was made
     * before the pack's route took a form this version cannot read), as the pack has it. Measured as the types read it there, the editor's route would be a
     * new one, and be written beside the pack's with the same id; measured as the pack has it, a change to it goes as operations at it. Every other route
     * the types cannot read is left alone.
     */
    private fun measuredAgainst(shape: JsonObject, read: RouteSet, after: JsonObject): List<Measured> {
        val unreadable = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>()).apply { addAll(read.unreadable) }
        val readableIds = read.routes.mapTo(HashSet()) { it.id }
        val editorIds = (after.getValue("routes") as JsonArray).mapNotNullTo(HashSet()) { textId(it) }
        // Each route the types read, as they wrote it, in the order the pack has them.
        val typed = (typed(read).getValue("routes") as JsonArray).iterator()
        val measured = ArrayList<Measured>()
        val taken = HashSet<String>()
        for (route in shape.getValue("routes") as JsonArray) {
            if (route !in unreadable) {
                measured += Measured(route, typed.next())
                continue
            }
            val id = textId(route) ?: continue
            if (id in editorIds && id !in readableIds && taken.add(id)) measured += Measured(route, route)
        }
        return measured
    }

    // The id of [route] when it is text, as every route the types can read has one.
    private fun textId(route: JsonElement): String? = ((route as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    // Whether no two routes of the pack's set share an id: an operation finds a route by its id, and takes the first that has it.
    private fun addressable(shape: JsonObject): Boolean {
        val seen = HashSet<String>()
        return (shape.getValue("routes") as JsonArray).all { route ->
            val id = (route as? JsonObject)?.get("id")?.takeIf(Js::isId) ?: return@all true
            seen.add(Js.idKey(id))
        }
    }

    // The set with the editor's routes written whole, in the places of the routes they were measured against (any more follow): one the editor did not
    // change goes back as it came, wherever it now stands, and one left alone (the types could not read it) keeps its place. Each of the editor's routes is
    // written once, and one left alone has an id none of them has (or one the set already held twice), so no route is ever written a second time.
    private fun wholeRoutes(shape: JsonObject, measured: List<Measured>, after: JsonObject): JsonObject {
        val places = Collections.newSetFromMap(IdentityHashMap<JsonElement, Boolean>()).apply { measured.forEach { add(it.json) } }
        val came = measured.toMutableList()
        val mine = (after.getValue("routes") as JsonArray).map { edited ->
            val same = came.indexOfFirst { PackDiff.sameData(it.version, edited) }
            if (same < 0) edited else came.removeAt(same).json
        }.iterator()
        val routes = ArrayList<JsonElement>()
        for (route in shape.getValue("routes") as JsonArray) {
            if (route !in places) routes += route else if (mine.hasNext()) routes += mine.next()
        }
        mine.forEachRemaining { routes += it }
        return JsonObject(LinkedHashMap(shape).apply { put("routes", JsonArray(routes)) })
    }

    private fun withHidden(set: RouteSet, hidden: Set<String>): RouteSet =
        if (hidden.isEmpty()) set else set.copy(routes = set.routes.map { if (it.id in hidden) it.copy(visible = false) else it })

    // The routes the types could read, as they write them, without each person's own fields: what an editor's change is measured on.
    private fun typed(set: RouteSet): JsonObject {
        val written = RouteSets.serialize(set.copy(extras = JsonObject(emptyMap()), unreadable = emptyList())).getValue("routes") as JsonArray
        val routes = written.map { route -> JsonObject(route.jsonObject - PackRoutes.OWN_ROUTE_FIELDS.toSet()) }
        return JsonObject(mapOf("routes" to JsonArray(routes)))
    }
}
