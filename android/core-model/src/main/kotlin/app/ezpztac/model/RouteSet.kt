package app.ezpztac.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The routes saved together under one name: what the web keeps in a saved route's `route_data` (`{ version: 1, routes: [...] }`, the routes drawn in
 * one sitting) and what one `kind: sketch` record on the server holds. One record is one [RouteSet], whole, so a set made here opens on the web and
 * back.
 *
 * Nothing is dropped on the way through. A field a newer web release adds to the document, a route, a point or the plan is kept in `extras` and written
 * back; a route this version cannot read at all is kept in [unreadable] and written back after the others. (A field *inside* a per-point override, or
 * inside an airspeed, altitude or wind, is the one place not kept: those have no room for it.)
 */
public data class RouteSet(
    /** The record's identity (its `client_uuid`), which is how the set is found whatever else changes. */
    val id: String,
    /** The server's id once it has one; null for a set made here that has not synced. */
    val savedId: Int? = null,
    val name: String,
    val routes: List<SketchRoute>,
    /** Top-level fields of the document other than `routes`, `version` included: a version this release does not know is written back as it was found. */
    val extras: JsonObject = JsonObject(emptyMap()),
    /** Routes in the document that could not be read as a route, as they were. */
    val unreadable: List<JsonElement> = emptyList(),
) {
    /** The route named [routeId], if it is in the set. */
    public fun route(routeId: String): SketchRoute? = routes.firstOrNull { it.id == routeId }

    /** The set with [routeId] changed by [change]; the same set when there is no such route or [change] leaves it as it was. */
    public fun mapRoute(routeId: String, change: (SketchRoute) -> SketchRoute): RouteSet {
        var found = false
        val next = routes.map { r ->
            if (r.id != routeId) r else change(r).also { found = true }
        }
        return if (found && next != routes) copy(routes = next) else this
    }

    /** The set with [route] added at the end. */
    public fun plus(route: SketchRoute): RouteSet = copy(routes = routes + route)

    /** The set without [routeId]. */
    public fun without(routeId: String): RouteSet = if (routes.any { it.id == routeId }) copy(routes = routes.filterNot { it.id == routeId }) else this
}

/** Reading a [RouteSet] from the web's `route_data` and writing it back. */
public object RouteSets {
    /** The document's own version, which the web writes as 1. A document made with no version is given this one. */
    public const val VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }

    /**
     * The set a record holds. Tolerant: a document with no `routes` is an empty set, and a route that cannot be read as one is kept as it was
     * ([RouteSet.unreadable]) instead of being thrown away or failing the whole set.
     */
    public fun parse(id: String, savedId: Int?, name: String, data: JsonObject): RouteSet {
        val raw = (data["routes"] as? JsonArray) ?: JsonArray(emptyList())
        val routes = ArrayList<SketchRoute>()
        val unreadable = ArrayList<JsonElement>()
        for (element in raw) {
            val route = readRoute(element)
            if (route != null) routes += route else unreadable += element
        }
        val extras = JsonObject(data.filterKeys { it != "routes" })
        return RouteSet(id = id, savedId = savedId, name = name, routes = routes, extras = extras, unreadable = unreadable)
    }

    /** The document for [set]: the routes as the web writes them, then what could not be read, with the fields this version does not know kept. */
    public fun serialize(set: RouteSet): JsonObject {
        val routes = set.routes.map(::writeRoute) + set.unreadable
        return JsonObject(mapOf("version" to JsonPrimitive(VERSION)) + set.extras + mapOf("routes" to JsonArray(routes)))
    }

    private fun readRoute(element: JsonElement): SketchRoute? {
        val obj = element as? JsonObject ?: return null
        val route = try {
            json.decodeFromJsonElement(SketchRoute.serializer(), obj)
        } catch (_: IllegalArgumentException) {                                        // SerializationException is one
            return null
        }
        val rawPoints = obj["points"] as? JsonArray
        val points = route.points.mapIndexed { i, p ->
            val raw = rawPoints?.getOrNull(i) as? JsonObject
            if (raw == null) p else p.copy(extras = unknown(raw, RoutePoint.serializer()))
        }
        val rawPlan = obj["plan"] as? JsonObject
        val plan = if (rawPlan == null) route.plan else route.plan.copy(extras = unknown(rawPlan, RoutePlan.serializer()))
        return route.copy(points = points, plan = plan, extras = unknown(obj, SketchRoute.serializer()))
    }

    private fun writeRoute(route: SketchRoute): JsonElement {
        val points = route.points.map { p -> withExtras(json.encodeToJsonElement(RoutePoint.serializer(), p).jsonObject, p.extras) }
        val plan = withExtras(json.encodeToJsonElement(RoutePlan.serializer(), route.plan).jsonObject, route.plan.extras)
        val typed = json.encodeToJsonElement(SketchRoute.serializer(), route).jsonObject
        return withExtras(JsonObject(typed + mapOf("points" to JsonArray(points), "plan" to plan)), route.extras)
    }

    /** The keys of [obj] that [serializer]'s class does not have: what a newer release added. */
    private fun unknown(obj: JsonObject, serializer: KSerializer<*>): JsonObject {
        val known = serializer.descriptor.elementNames.toSet()
        return JsonObject(obj.filterKeys { it !in known })
    }

    /** [typed] with [extras] added, [typed] winning: a field this version knows is never overwritten by a stale copy. */
    private fun withExtras(typed: JsonObject, extras: JsonObject): JsonObject =
        if (extras.isEmpty()) typed else JsonObject(typed + extras.filterKeys { it !in typed })
}
