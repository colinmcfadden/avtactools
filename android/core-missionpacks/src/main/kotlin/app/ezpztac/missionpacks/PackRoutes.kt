package app.ezpztac.missionpacks

import app.ezpztac.model.RoutePlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A set of sketched routes as a mission pack item: the web's `packRoutes.js`, held to `contracts/fixtures/packs/shared.json` and
 * `describe.json`. Its data is what the library saves for sketched routes (`{version: 1, routes}`), less what is each person's own:
 * which routes they have hidden, and the set the web's sketch files them under (`visible`, `setId`).
 */
public object PackRoutes {
    public val OWN_ROUTE_FIELDS: List<String> = listOf("visible", "setId")

    // The palette a route with no colour is given one from, by its id (useRouteSketch's colorFor): the web's ROUTE_COLORS.
    private val COLORS = listOf("#FF453A", "#0A84FF", "#32D74B", "#FFD60A", "#BF5AF2", "#FF9F0A", "#64D2FF", "#FF375F")

    private val json = Json { encodeDefaults = true }

    // defaultRoutePlan() with no profile: the UH-60L's, as RoutePlan's defaults are.
    private val DEFAULT_PLAN: JsonObject = json.encodeToJsonElement(RoutePlan.serializer(), RoutePlan()).jsonObject

    // `{...route}` without each person's own fields.
    private fun withoutOwn(route: JsonElement?): JsonObject {
        val fields = Js.spread(route)
        OWN_ROUTE_FIELDS.forEach { fields.remove(it) }
        return JsonObject(fields)
    }

    /** A set of routes, as an editor keeps them, as its pack item has them (`routeSetData`): `{version: 1, routes}` without their own fields. */
    public fun routeSetData(routes: JsonElement?): JsonObject =
        JsonObject(linkedMapOf("version" to JsonPrimitive(1), "routes" to JsonArray((routes as? JsonArray).orEmpty().map(::withoutOwn))))

    /**
     * The shared part of a route item's raw data (`sharedRouteData`): when `routes` is a list, each element that is an object (or a list,
     * which becomes an object keyed by position, as JavaScript's spread makes it) loses its own fields, and anything else is kept as it is.
     * No data is an empty object; data that is not an object is given back as it is.
     */
    public fun sharedRouteData(data: JsonElement?): JsonElement {
        if (data == null || data is JsonNull) return JsonObject(emptyMap())
        if (data !is JsonObject) return data
        val routes = data["routes"] as? JsonArray ?: return data
        return JsonObject(LinkedHashMap(data).apply { put("routes", JsonArray(routes.map(::sharedRoute))) })
    }

    private fun sharedRoute(route: JsonElement): JsonElement = if (route is JsonObject || route is JsonArray) withoutOwn(route) else route

    /**
     * Today's shape of a route item's data: the document an editor here works on, in the item's form (the web's
     * `routeSetData(routesFromItem(item, setId))`, held to shared.json's `routeShape`). Each route that is an object with an `id` (null
     * too) is restored as the web's sketch restores a set's route ([restoreSketchRoute]), less its own fields.
     *
     * **Not like the web**, in the cases shared.json marks `webBug`, and the same for an editor's document and its shape, so a set that
     * holds such a thing sends nothing for it, ever:
     *  - a route that is not an object with an `id` (null, a number, text, true, a list, an object with no id) is kept where it stands, as
     *    [sharedRouteData] has it, where the web's shape leaves it out (and its editor keeps it, or fails on it);
     *  - the set's own fields and its version are kept as they were, where the web drops them and makes the version 1 (a set with no
     *    version is given 1, as the web gives it).
     */
    public fun routeShape(data: JsonElement?): JsonObject {
        val set = data as? JsonObject
        val routes = (set?.get("routes") as? JsonArray).orEmpty().map { route ->
            if (route is JsonObject && "id" in route) withoutOwn(restoreSketchRoute(route)) else sharedRoute(route)
        }
        val fields = LinkedHashMap<String, JsonElement>()
        fields["version"] = set?.get("version") ?: JsonPrimitive(1)
        set?.forEach { (key, value) -> if (key != "routes" && key != "version") fields[key] = value }
        fields["routes"] = JsonArray(routes)
        return JsonObject(fields)
    }

    /**
     * A set's route as the web's sketch restores it from saved data (useRouteSketch's `restoreSketchRoute` for a set's route, then
     * routeCalc's `ensureRoutePlan`): its id and every field it has kept; a missing or falsy `color` the colour of its id; missing or
     * falsy `elevations` `{}`; the default plan with the route's own plan spread over it as JavaScript spreads (a value given wins whole,
     * null too); and a TOT from before clocks were set on points moved onto its point. `visible` is set, as the sketch sets it, for the
     * caller to take out.
     */
    internal fun restoreSketchRoute(route: JsonObject): JsonObject {
        val restored = Js.spread(route)
        val id = route["id"]
        restored["color"] = route["color"]?.takeIf(Js::truthy) ?: JsonPrimitive(colorFor(id))
        restored["visible"] = JsonPrimitive(true)
        restored["elevations"] = route["elevations"]?.takeIf(Js::truthy) ?: JsonObject(emptyMap())
        restored["plan"] = ensuredPlan(route)
        return JsonObject(restored)
    }

    // routeCalc's ensureRoutePlan, on JSON.
    private fun ensuredPlan(route: JsonObject): JsonObject {
        val saved = route["plan"]
        val plan = LinkedHashMap<String, JsonElement>(DEFAULT_PLAN)
        if (Js.truthy(saved)) plan.putAll(Js.spread(saved))
        val tot = Js.prop(saved, "tot")
        val time = Js.prop(tot, "time")
        if (time != null && Js.truthy(time) && !hasAnyClock(plan["perPoint"])) {
            val date = Js.prop(tot, "date")
            if (date != null && Js.truthy(date)) plan["date"] = date
            val pointId = Js.prop(tot, "pointId")
            val anchor = if (Js.truthy(pointId)) pointId else planPoints(route).firstOrNull()?.let { Js.prop(it, "id") }
            if (Js.truthy(anchor)) {
                val key = Js.text(anchor)
                val perPoint = Js.spread(plan["perPoint"])
                val entry = Js.spread(Js.prop(plan["perPoint"], key))
                entry["clock"] = time
                perPoint[key] = JsonObject(entry)
                plan["perPoint"] = JsonObject(perPoint)
            }
        }
        plan.remove("tot")
        return JsonObject(plan)
    }

    // `Object.values(plan.perPoint || {}).some((o) => o?.clock)`.
    private fun hasAnyClock(perPoint: JsonElement?): Boolean =
        Js.truthy(perPoint) && Js.values(perPoint).any { Js.truthy(Js.prop(it, "clock")) }

    // routeCalc's planPoints: the points that are not shaping and have an id. Points that are not a list, or not objects, are none.
    private fun planPoints(route: JsonObject): List<JsonObject> = (route["points"] as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>()
        .filter { !Js.strictEquals(it["kind"], SHAPING) && Js.truthy(it["id"]) }

    private val SHAPING = JsonPrimitive("shaping")

    // The same colour for the same route on every screen: each character of String(id) (its first UTF-16 unit, for one outside the
    // basic plane) folded as h = (h * 31 + unit) mod 2^32.
    private fun colorFor(id: JsonElement?): String {
        val text = Js.text(id)
        var hash = 0L
        var i = 0
        while (i < text.length) {
            hash = (hash * 31 + text[i].code) % 4_294_967_296L
            i += if (Character.isHighSurrogate(text[i]) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) 2 else 1
        }
        return COLORS[(hash % COLORS.size).toInt()]
    }

    // -- What a change was, in words, for the pack's history ----------------------------------------------------------------

    // `new Map(list.filter((e) => e && e.id !== undefined).map((e) => [e.id, e]))`.
    private fun byId(list: JsonElement?, unique: () -> String): LinkedHashMap<String, JsonElement> {
        val map = LinkedHashMap<String, JsonElement>()
        (list as? JsonArray).orEmpty().filter { Js.truthy(it) && Js.prop(it, "id") != null }.forEach { map[Js.mapKey(Js.prop(it, "id"), unique)] = it }
        return map
    }

    private fun named(value: JsonElement?, otherwise: String): String = if (Js.truthy(value)) Js.text(value) else otherwise

    /**
     * One sentence for the pack's history about a change to a route set ("Sam B. moved .RP on RED 1."): `describeRouteChange`, held to
     * describe.json's `routes` cases. [before] and [after] are the set's shared data; [name] is the set's. Cut to 300 UTF-16 units,
     * never through a character.
     */
    public fun describeRouteChange(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String {
        val set = name?.takeIf { it.isNotEmpty() } ?: "the routes"
        var refs = 0
        val unique = { (refs++).toString() }
        val phrases = ArrayList<String>()
        val was = byId(Js.prop(before, "routes"), unique)
        val now = byId(Js.prop(after, "routes"), unique)
        for ((id, route) in now) {
            val label = named(Js.prop(route, "name"), "a route")
            val old = was[id]
            if (old == null) {
                phrases += "added the route $label to $set"
                continue
            }
            val oldName = Js.prop(old, "name")
            if (!Js.sameValue(oldName, Js.prop(route, "name"))) {
                phrases += if (Js.truthy(oldName)) "renamed the route ${Js.text(oldName)} to $label" else "named a route $label"
            }
            val oldPoints = byId(Js.prop(old, "points"), unique)
            val newPoints = byId(Js.prop(route, "points"), unique)
            for ((pointId, point) in newPoints) {
                val previous = oldPoints[pointId]
                val pointName = named(Js.prop(point, "name"), "a point")
                when {
                    previous == null -> phrases += "added $pointName to $label"
                    !Js.sameValue(Js.prop(previous, "lat"), Js.prop(point, "lat")) || !Js.sameValue(Js.prop(previous, "lon"), Js.prop(point, "lon")) ->
                        phrases += "moved $pointName on $label"
                    !PackDiff.sameData(previous, point) -> phrases += "changed $pointName on $label"
                }
            }
            for ((pointId, point) in oldPoints) {
                if (pointId !in newPoints) phrases += "removed ${named(Js.prop(point, "name"), "a point")} from $label"
            }
            if (!PackDiff.sameData(Js.prop(old, "plan"), Js.prop(route, "plan"))) phrases += "changed the plan of $label"
            if (!PackDiff.sameData(Js.prop(old, "elevations"), Js.prop(route, "elevations"))) phrases += "updated the ground elevations of $label"
        }
        for ((id, route) in was) {
            if (id in now) continue
            val routeName = Js.prop(route, "name")
            phrases += if (Js.truthy(routeName)) "removed the route ${Js.text(routeName)} from $set" else "removed a route from $set"
        }
        val what = phrases.firstOrNull() ?: "edited $set"
        return Js.cut("${actor?.takeIf { it.isNotEmpty() } ?: "Someone"} $what.", PackLz.MAX_SENTENCE)
    }
}
