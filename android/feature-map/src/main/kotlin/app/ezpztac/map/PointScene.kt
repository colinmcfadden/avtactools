package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.model.SetPoint
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A set of local points as it is to be drawn: the colour it is in and its points. Only sets that are shown are given to the scene. */
data class DrawnPointSet(val id: String, val color: String, val points: List<SetPoint>)

/** One local point as the map shows it: a dot in its set's colour, named. */
data class PointPin(val setId: String, val pointId: String, val at: LatLon, val name: String, val color: String, val selected: Boolean)

/**
 * What the map draws for the sets of local points that are shown, as plain data, so *what* is drawn is tested without a GPU (the MapLibre glue is not).
 * Each point is a dot in its set's colour; the one the person is holding is larger, with an amber ring. A point whose position is not a number cannot be
 * drawn but is not lost: it is still in its set.
 */
data class PointScene(val pins: List<PointPin> = emptyList()) {
    val isEmpty: Boolean get() = pins.isEmpty()

    /**
     * The points as GeoJSON for the map's source, which clusters them (a set can be thousands of points). Coordinates are `[longitude, latitude]`, as
     * GeoJSON has them. Each feature carries its `color`, whether it is `selected`, and its `name` (map text is not used: the style has no glyphs, so a
     * name is a Compose label over the map).
     */
    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            pins.forEach { pin ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("properties") {
                            put("color", pin.color)
                            put("selected", pin.selected)
                            put("name", pin.name)
                        }
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") { add(JsonPrimitive(pin.at.lon)); add(JsonPrimitive(pin.at.lat)) }
                        }
                    },
                )
            }
        }
    }.toString()

    companion object {
        val EMPTY = PointScene()

        /**
         * The scene of [sets], in order, with [selectedPointId] of [selectedSetId] held. A point id is only unique within its set, so both name it; a
         * selection that names a point not drawn marks none.
         */
        fun of(sets: List<DrawnPointSet>, selectedSetId: String? = null, selectedPointId: String? = null): PointScene = PointScene(
            sets.flatMap { set ->
                set.points.filter { it.lat.isFinite() && it.lon.isFinite() }.map { p ->
                    PointPin(set.id, p.id, LatLon(p.lat, p.lon), p.name, set.color, selected = set.id == selectedSetId && p.id == selectedPointId)
                }
            },
        )
    }
}
