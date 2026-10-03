package app.ezpztac.map

import app.ezpztac.model.LatLon
import app.ezpztac.model.Radars
import app.ezpztac.model.ThreatEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One visible threat marker, drawn as its MIL-STD symbol over the map. */
data class ThreatPin(val id: String, val name: String, val sidc: String, val at: LatLon, val selected: Boolean)

/** One radar's maximum range, drawn around its threat. */
data class ThreatRing(val threatId: String, val type: Int, val at: LatLon, val rangeNmi: Double) {
    val color: String get() = if (type == Radars.ENGAGEMENT) "#EF4444" else "#FBBF24"
    val dashed: Boolean get() = type != Radars.ENGAGEMENT
}

/**
 * The local threat picture as the map draws it. Hidden threats contribute nothing. Markers are Compose symbols, while range rings are GeoJSON lines so
 * MapLibre keeps them geodesically anchored while the camera moves. No mask or server response is kept here.
 */
data class ThreatScene(val pins: List<ThreatPin> = emptyList(), val rings: List<ThreatRing> = emptyList()) {
    val isEmpty: Boolean get() = pins.isEmpty() && rings.isEmpty()

    fun geoJson(): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            rings.forEach { ring ->
                add(feature("ring", line(circle(ring.at, ring.rangeNmi)), "color" to JsonPrimitive(ring.color), "dashed" to JsonPrimitive(ring.dashed)))
            }
        }
    }.toString()

    private fun feature(role: String, geometry: JsonElement, vararg properties: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("role", role)
            properties.forEach { (key, value) -> put(key, value) }
        }
        put("geometry", geometry)
    }

    private fun line(points: List<LatLon>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { point -> add(buildJsonArray { add(JsonPrimitive(point.lon)); add(JsonPrimitive(point.lat)) }) } }
    }

    private fun circle(center: LatLon, radiusNmi: Double): List<LatLon> = (0..RING_SEGMENTS).map { i ->
        destination(center, radiusNmi, i * 360.0 / RING_SEGMENTS)
    }

    private fun destination(from: LatLon, distanceNmi: Double, bearingDeg: Double): LatLon {
        val angular = distanceNmi / EARTH_RADIUS_NMI
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0)
    }

    companion object {
        val EMPTY = ThreatScene()
        private const val EARTH_RADIUS_NMI = 3440.065
        private const val RING_SEGMENTS = 96

        fun of(entries: List<ThreatEntry>, selectedId: String? = null): ThreatScene {
            val visible = entries.filter { it.visible && it.threat.lat.isFinite() && it.threat.lon.isFinite() }
            return ThreatScene(
                pins = visible.map { ThreatPin(it.id, it.threat.name, it.threat.milstdId, LatLon(it.threat.lat, it.threat.lon), it.id == selectedId) },
                rings = visible.flatMap { entry ->
                    entry.threat.radars.filter { it.showRangeRings && it.rangeNmi.isFinite() && it.rangeNmi > 0 }.map {
                        ThreatRing(entry.id, it.type, LatLon(entry.threat.lat, entry.threat.lon), it.rangeNmi)
                    }
                },
            )
        }
    }
}
