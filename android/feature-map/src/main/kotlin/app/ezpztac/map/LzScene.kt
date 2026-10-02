package app.ezpztac.map

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A slope raster to lay over the ground: a PNG as a data URI, and the box it covers. */
data class SlopeImage(val south: Double, val west: Double, val north: Double, val east: Double, val dataUri: String)

/**
 * What the map draws for the open diagram, as plain data, so *what* is drawn is tested without a GPU (the MapLibre glue that draws it is
 * not, and stays thin). Today: the target, the analysis boundary, a boundary the person drew, and the slope raster. The planning graphics
 * join it as they are built.
 */
data class LzScene(
    val target: LatLon?,
    /** The analysis boundary: a polygon of three points or more, not closed (the first point is not repeated). */
    val boundary: List<LatLon>,
    /** A boundary the person drew and has not analysed yet: a line (two points) or a polygon (three or more). */
    val drawn: List<LatLon>,
    val slope: SlopeImage?,
) {
    val isEmpty: Boolean get() = target == null && boundary.isEmpty() && drawn.isEmpty() && slope == null

    /**
     * The vector part as GeoJSON, for the map's source. Coordinates are `[longitude, latitude]`, as GeoJSON has them (the diagram stores
     * `[latitude, longitude]`), and a polygon's ring is closed. Each feature carries a `role` the layers choose their style by.
     */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            if (boundary.size >= 3) add(feature("boundary", polygon(boundary)))
            when {
                drawn.size >= 3 -> add(feature("drawn", polygon(drawn)))
                drawn.size == 2 -> add(feature("drawn", line(drawn)))
            }
            target?.let { add(feature("target", point(it))) }
        }
    }.toString()

    private fun feature(role: String, geometry: JsonObject): JsonObject = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") { put("role", role) }
        put("geometry", geometry)
    }

    private fun position(p: LatLon): JsonArray = buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }

    private fun point(p: LatLon) = buildJsonObject { put("type", "Point"); put("coordinates", position(p)) }

    private fun line(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { add(position(it)) } }
    }

    private fun polygon(points: List<LatLon>) = buildJsonObject {
        put("type", "Polygon")
        putJsonArray("coordinates") {
            add(buildJsonArray { (points + points.first()).forEach { add(position(it)) } })
        }
    }

    companion object {
        val EMPTY = LzScene(null, emptyList(), emptyList(), null)

        /** The scene of [diagram] (nothing for none), with [slope] if it has been measured. */
        fun of(diagram: Diagram?, slope: SlopeImage? = null): LzScene {
            if (diagram == null) return EMPTY
            return LzScene(
                target = diagram.target?.let { LatLon(it.lat, it.lon) },
                boundary = DiagramGeometry.boundary(diagram),
                drawn = DiagramGeometry.drawn(diagram),
                slope = slope,
            )
        }
    }
}
