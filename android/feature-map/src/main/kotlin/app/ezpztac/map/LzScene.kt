package app.ezpztac.map

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.cos

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
    val graphics: GraphicsScene = GraphicsScene.EMPTY,
) {
    val isEmpty: Boolean get() = target == null && boundary.isEmpty() && drawn.isEmpty() && slope == null && graphics.isEmpty

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
            graphics.sectors.forEach { add(feature("sector", polygon(it.ring), "selected" to JsonPrimitive(it.selected))) }
            // The keep-out ring round the aircraft that is held: another aircraft's rotor tip should stay outside it.
            graphics.aircraft.filter { it.selected }.forEach { a ->
                add(feature("ring", polygon(GraphicsScene.circle(a.at, a.diameterM / 2 + a.clearanceM).dropLast(1))))
            }
            graphics.separations.forEach { add(feature("sep", line(listOf(it.from, it.to)), "violating" to JsonPrimitive(it.violating))) }
            graphics.pzMarkers.forEach { pz ->
                add(feature("pz", line(listOf(pz.anchor, pz.tip)), "selected" to JsonPrimitive(pz.selected)))
                add(feature("pz-anchor", point(pz.anchor)))
                add(feature("pz-tip", point(pz.tip), "bearing" to JsonPrimitive(GraphicsScene.bearing(pz.anchor, pz.tip))))
            }
            graphics.goArounds.forEach { ga ->
                add(feature("ga", point(ga.at), "icon" to JsonPrimitive(if (ga.direction == "right") "ga-right" else "ga-left"), "rotation" to JsonPrimitive(ga.rotationDeg)))
            }
            graphics.aircraft.forEach { a ->
                val k0 = a.diameterM / (METERS_PER_POINT_AT_ZOOM_0 * cos(Math.toRadians(a.at.lat))) / AIRCRAFT_ICON_PX
                add(
                    feature(
                        "heli", point(a.at),
                        "icon" to JsonPrimitive(aircraftImage(a.iconKey, a.violating)), "rotation" to JsonPrimitive(a.rotationDeg), "k0" to JsonPrimitive(k0),
                        "selected" to JsonPrimitive(a.selected),
                    ),
                )
            }
            target?.let { add(feature("target", point(it))) }
            graphics.selectedAt?.let { add(feature("halo", point(it))) }
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

        /** The aircraft bitmaps are this many points across, so the icon's size at a zoom is the aircraft's size on the ground over this. */
        const val AIRCRAFT_ICON_PX = 192

        /** Metres one point covers at the equator on zoom 0 of MapLibre's 512-point tiles. */
        const val METERS_PER_POINT_AT_ZOOM_0 = 78_271.516964

        /** The name an aircraft's picture is added to the style under, per silhouette and state. */
        fun aircraftImage(iconKey: String, violating: Boolean): String = "ac-$iconKey-${if (violating) "violation" else "normal"}"

        /**
         * The scene of [diagram] (nothing for none), with [slope] if it has been measured. [profiles] and [active] say what each placed
         * aircraft is, and [selected] is the graphic the person is holding.
         */
        fun of(
            diagram: Diagram?,
            slope: SlopeImage? = null,
            profiles: List<AircraftProfile> = emptyList(),
            active: AircraftProfile = AircraftProfile.FALLBACK,
            selected: GraphicRef? = null,
        ): LzScene {
            if (diagram == null) return EMPTY
            return LzScene(
                target = diagram.target?.let { LatLon(it.lat, it.lon) },
                boundary = DiagramGeometry.boundary(diagram),
                drawn = DiagramGeometry.drawn(diagram),
                slope = slope,
                graphics = GraphicsScene.of(diagram, profiles, active, selected),
            )
        }
    }
}
