package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.model.RoutePoint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What a named route point is drawn as, by its AMPS type, as the web draws it (`MsnxRouteLayer`'s `createIcon`): an IP point (the SP, the RP or an IP) a
 * square, a target a triangle, and a turn point (a checkpoint), or a type this version does not know, a circle. A shaping point is a small dot ([RouteScene]).
 */
enum class PinShape(val key: String) {
    CIRCLE("circle"),
    SQUARE("square"),
    TRIANGLE("triangle");

    companion object {
        fun of(ptType: String?): PinShape = when (ptType) {
            "ip" -> SQUARE
            "target" -> TRIANGLE
            else -> CIRCLE
        }
    }
}

/**
 * A point on a route as the map shows it: a named AMPS point is a [shape] in the route's colour (the owner's rule of 2026-10-21: an RP a square, a target a
 * triangle, a checkpoint a circle), a shaping point only bends the line.
 */
data class RoutePin(val id: String?, val at: LatLon, val name: String, val amps: Boolean, val selected: Boolean, val shape: PinShape = PinShape.CIRCLE)

/** One route as drawn: its line through every point in flight order, and its points. */
data class RouteLine(val id: String, val name: String, val color: String, val line: List<LatLon>, val pins: List<RoutePin>, val selected: Boolean)

/**
 * What the map draws for the open set of routes, as plain data, so *what* is drawn is tested without a GPU (the MapLibre glue is not). Only visible
 * routes are drawn. The route the person is working on is drawn heavier and shows its shaping points, which can be dragged to bend it; the others
 * show only their named points.
 */
data class RouteScene(
    val routes: List<RouteLine> = emptyList(),
    /** The points of a route being drawn right now, in order: a dashed line with a dot at each, so the first is seen too. */
    val draft: List<LatLon> = emptyList(),
) {
    val isEmpty: Boolean get() = routes.isEmpty() && draft.isEmpty()

    /** The pins of every drawn route, for the labels the screen puts over the map (the style has no glyphs, so a name cannot be map text). */
    val labelled: List<Pair<RouteLine, RoutePin>> get() = routes.flatMap { r -> r.pins.filter { it.amps }.map { r to it } }

    /**
     * The vector part as GeoJSON, for the map's source. Coordinates are `[longitude, latitude]`, as GeoJSON has them. Each feature carries a `role`
     * the layers choose their style by, and a route's features carry its `color`.
     */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            routes.forEach { r ->
                if (r.line.size >= 2) add(feature("route", line(r.line), "color" to JsonPrimitive(r.color), "selected" to JsonPrimitive(r.selected)))
            }
            // Shaping points under the named ones, so a named point sitting on one is not hidden by it.
            routes.filter { it.selected }.forEach { r ->
                r.pins.filter { !it.amps }.forEach { add(feature("shape", point(it.at), "color" to JsonPrimitive(r.color), "selected" to JsonPrimitive(it.selected))) }
            }
            routes.forEach { r ->
                r.pins.filter { it.amps }.forEach {
                    add(
                        feature(
                            "pin", point(it.at), "color" to JsonPrimitive(r.color), "selected" to JsonPrimitive(it.selected), "shape" to JsonPrimitive(it.shape.key),
                            "icon" to JsonPrimitive(pinImage(it.shape, r.color, it.selected)),
                        ),
                    )
                }
            }
            if (draft.size >= 2) add(feature("draft", line(draft)))
            draft.forEach { add(feature("draft-vertex", point(it))) }
        }
    }.toString()

    private fun feature(role: String, geometry: JsonObject, vararg properties: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("role", role)
            properties.forEach { (k, v) -> put(k, v) }
        }
        put("geometry", geometry)
    }

    private fun point(p: LatLon) = buildJsonObject {
        put("type", "Point")
        putJsonArray("coordinates") { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }
    }

    private fun line(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { p -> add(buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }) } }
    }

    companion object {
        val EMPTY = RouteScene()

        /**
         * The name a named point's picture is given to the map under: one for each shape, colour and whether it is held ([RoutePinImages] draws it). The colour is
         * kept to letters and digits, so whatever a route's colour is written as names one picture.
         */
        fun pinImage(shape: PinShape, color: String, held: Boolean): String =
            "route-pin-${shape.key}-${if (held) "held" else "plain"}-${color.lowercase().replace(Regex("[^0-9a-z]"), "_")}"

        /**
         * The scene of [set] (nothing for none): its visible routes, with [selectedRouteId] heavier and [selectedPointId] (a point of that route)
         * marked, and [draft] the points of a route being drawn.
         */
        fun of(set: RouteSet?, selectedRouteId: String? = null, selectedPointId: String? = null, draft: List<LatLon> = emptyList()): RouteScene {
            if (set == null) return RouteScene(draft = draft)
            val lines = set.routes.filter { it.visible }.map { route ->
                val chosen = route.id == selectedRouteId
                val placed = route.points.filter { it.lat.isFinite() && it.lon.isFinite() }            // a point with no position cannot be drawn, but is not lost
                RouteLine(
                    id = route.id, name = route.name, color = route.color, selected = chosen,
                    line = placed.map { LatLon(it.lat, it.lon) },
                    pins = placed.map { p ->
                        RoutePin(
                            p.id, LatLon(p.lat, p.lon), p.name.orEmpty(), amps = p.kind == RoutePoint.KIND_AMPS, selected = chosen && p.id != null && p.id == selectedPointId,
                            shape = PinShape.of(p.ptType),
                        )
                    },
                )
            }
            return RouteScene(routes = lines, draft = draft)
        }
    }
}
